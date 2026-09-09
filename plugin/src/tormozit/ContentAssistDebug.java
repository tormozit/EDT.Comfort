package tormozit;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.swt.widgets.Display;

/**
 * Логи Content Assist в общем журнале {@link GlobalLogView} (при «Общем логировании»).
 * Временный канал {@code .tmp/temp-logs/assist-ui.log} — безусловный, для зависания UI.
 */
public final class ContentAssistDebug
{
    private static final AtomicInteger validateCalls = new AtomicInteger();
    private static final AtomicInteger agentValidateLogs = new AtomicInteger();
    /** Приёмник временных логов зависания автодополнения. */
    private static final String UI_TOPIC = "assist-ui"; //$NON-NLS-1$
    /** Через столько мс на UI-потоке снимаем стек, если секция ещё не закрыта. */
    private static final long UI_STALL_MS = 250;
    private static final AtomicInteger UI_WATCH_GEN = new AtomicInteger();
    private static volatile String uiWatchLocation = ""; //$NON-NLS-1$
    private static volatile Thread uiWatchThread;
    private static final ThreadLocal<java.util.ArrayDeque<String>> UI_SECTION_STACK =
        new ThreadLocal<>();
    private static final ScheduledExecutorService UI_WATCH = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "comfort-assist-ui-watch"); //$NON-NLS-1$
        t.setDaemon(true);
        return t;
    });

    /** Маркер сборки для literal assist. */
    public static final String LITERAL_ASSIST_BUILD = "20260708-sync-selection"; //$NON-NLS-1$

    private ContentAssistDebug() {}

    /** No-op: раньше NDJSON на диск; вызовы оставлены в коде без I/O. */
    public static void debugModeLog(String hypothesisId, String location, String message, String dataJson)
    {
    }

    /** No-op: раньше session NDJSON. */
    public static void sessionLog(String hypothesisId, String location, String message, String dataJson)
    {
    }

    public static void logLiteralAssistBuildStamp()
    {
    }

    public static void logAutoOpen(int autoOpenSeq, String hypothesisId, String location,
        String message, String dataJson)
    {
        agentLog(hypothesisId, location, message, dataJson);
    }

    public static void traceAssist(String hypothesisId, String location, String message,
        String dataJson)
    {
        agentLog(hypothesisId, location, message, dataJson);
    }

    public static void logAutoOpenDecision(String decision, int caret, int irN)
    {
        agentLog("H78", "onWordsTablePrepared", "autoOpenDecision", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"decision\":\"" + jsonEscape(decision) + "\",\"caret\":" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"irN\":" + irN + "}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String jsonEscapeForLog(String value)
    {
        return jsonEscape(value);
    }

    private static String jsonEscape(String value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        return value.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    public static String firstProposalKey(ICompletionProposal[] proposals)
    {
        if (proposals == null || proposals.length == 0)
            return ""; //$NON-NLS-1$
        String d = proposals[0].getDisplayString();
        if (d == null)
            return ""; //$NON-NLS-1$
        int colon = d.indexOf(':');
        String key = colon > 0 ? d.substring(0, colon).trim() : d.trim();
        return jsonEscape(key.length() > 40 ? key.substring(0, 40) : key);
    }

    /** Диагностика assist — только в «Журнал Комфорт» при «Общем логировании». */
    public static void agentLog(String hypothesisId, String location, String message, String dataJson)
    {
        if (!Global.isLogEnabled())
            return;
        String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
        Global.log("contentAssist", //$NON-NLS-1$
            "[" + hypothesisId + "] " + location + " " + message + " " + data); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // ---- Замер задержек автодополнения (безусловный temp-log) -----------------

    /** Разовая отметка с длительностью. */
    public static void perfLog(String location, long elapsedMs, int thresholdMs, String extraJson)
    {
        write(location, mergeJson(extraJson, "\"ms\":" + elapsedMs + prefixUi())); //$NON-NLS-1$
    }

    /** Начало секции: на UI включает сторожок стека {@link #UI_STALL_MS}. */
    public static long perfStart(String location)
    {
        boolean ui = Display.getCurrent() != null;
        write("begin." + location, "{\"ui\":" + ui + prefixThread() + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (ui)
            enterUiSection(location);
        return System.nanoTime();
    }

    /** Конец секции: пишет {@code ms} и снимает сторожок, если это внешняя UI-секция. */
    public static void perfEnd(String location, long startNanos, String extraJson)
    {
        long ms = startNanos > 0 ? (System.nanoTime() - startNanos) / 1_000_000L : -1L;
        boolean ui = Display.getCurrent() != null;
        if (ui)
            leaveUiSection();
        write(location, mergeJson(extraJson, "\"ms\":" + ms + ",\"ui\":" + ui + prefixThread())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Разовая отметка без замера. */
    public static void perfMark(String location, String extraJson)
    {
        boolean ui = Display.getCurrent() != null;
        write(location, mergeJson(extraJson, "\"ui\":" + ui + prefixThread())); //$NON-NLS-1$
    }

    private static void enterUiSection(String location)
    {
        java.util.ArrayDeque<String> stack = UI_SECTION_STACK.get();
        if (stack == null)
        {
            stack = new java.util.ArrayDeque<>();
            UI_SECTION_STACK.set(stack);
        }
        boolean first = stack.isEmpty();
        String loc = location == null ? "" : location; //$NON-NLS-1$
        stack.push(loc);
        uiWatchLocation = loc;
        if (!first)
            return;
        uiWatchThread = Thread.currentThread();
        int gen = UI_WATCH_GEN.incrementAndGet();
        UI_WATCH.schedule(() -> dumpUiStall(gen), UI_STALL_MS, TimeUnit.MILLISECONDS);
    }

    private static void leaveUiSection()
    {
        java.util.ArrayDeque<String> stack = UI_SECTION_STACK.get();
        if (stack == null || stack.isEmpty())
            return;
        stack.pop();
        if (stack.isEmpty())
        {
            UI_SECTION_STACK.remove();
            UI_WATCH_GEN.incrementAndGet();
            uiWatchThread = null;
            uiWatchLocation = ""; //$NON-NLS-1$
            return;
        }
        uiWatchLocation = stack.peek();
    }

    private static void dumpUiStall(int gen)
    {
        if (UI_WATCH_GEN.get() != gen)
            return;
        Thread ui = uiWatchThread;
        String loc = uiWatchLocation;
        if (ui == null)
            return;
        StringBuilder stack = new StringBuilder();
        int n = 0;
        for (StackTraceElement frame : ui.getStackTrace())
        {
            String s = frame.toString();
            if (s.startsWith("java.lang.Thread.getStackTrace")) //$NON-NLS-1$
                continue;
            if (n > 0)
                stack.append(" | "); //$NON-NLS-1$
            stack.append(s);
            n++;
            if (n >= 40)
                break;
        }
        write("uiStall", "{\"loc\":\"" + jsonEscape(loc) //$NON-NLS-1$ //$NON-NLS-2$
            + "\",\"ms\":" + UI_STALL_MS //$NON-NLS-1$
            + ",\"thread\":\"" + jsonEscape(ui.getName()) + "\"" //$NON-NLS-1$ //$NON-NLS-2$
            + ",\"stack\":\"" + jsonEscape(stack.toString()) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void write(String location, String json)
    {
        Global.tempLog(UI_TOPIC, location + " " + (json == null || json.isEmpty() ? "{}" : json)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String prefixUi()
    {
        return ",\"ui\":" + (Display.getCurrent() != null) + prefixThread(); //$NON-NLS-1$
    }

    private static String prefixThread()
    {
        return ",\"thread\":\"" + jsonEscape(Thread.currentThread().getName()) + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String mergeJson(String extraJson, String prefixFields)
    {
        String extra = extraJson == null || extraJson.isBlank() ? "{}" : extraJson.trim(); //$NON-NLS-1$
        if (extra.startsWith("{") && extra.endsWith("}")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            String inner = extra.substring(1, extra.length() - 1).trim();
            if (inner.isEmpty())
                return "{" + prefixFields + "}"; //$NON-NLS-1$ //$NON-NLS-2$
            return "{" + prefixFields + "," + inner + "}"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "{" + prefixFields + "}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static boolean shouldLogValidateLiteral()
    {
        if (!Global.isLogEnabled())
            return false;
        return agentValidateLogs.incrementAndGet() <= 5;
    }

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        Global.log("contentAssist", msg); //$NON-NLS-1$
    }

    public static void logValidate(boolean accepted, String filter, ICompletionProposal proposal, int offset)
    {
        validateCalls.incrementAndGet();
    }

    public static String sampleTypes(ICompletionProposal[] arr, int max)
    {
        if (arr == null || arr.length == 0)
            return " types=[]"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(" types=["); //$NON-NLS-1$
        int n = Math.min(max, arr.length);
        for (int i = 0; i < n; i++)
        {
            if (i > 0)
                sb.append(',');
            sb.append(arr[i] == null ? "null" : arr[i].getClass().getSimpleName()); //$NON-NLS-1$
        }
        if (arr.length > n)
            sb.append("…"); //$NON-NLS-1$
        return sb.append(']').toString();
    }

    public static void resetValidateStats()
    {
        validateCalls.set(0);
    }

    public static String proposalLabel(ICompletionProposal p)
    {
        if (p == null)
            return "null"; //$NON-NLS-1$
        String d = p.getDisplayString();
        String type = p.getClass().getSimpleName();
        if (p instanceof SmartCompletionProposal)
            type += "→" + ((SmartCompletionProposal) p).getDelegate().getClass().getSimpleName(); //$NON-NLS-1$
        String text = d == null ? "" : d; //$NON-NLS-1$
        if (text.length() > 48)
            text = text.substring(0, 48) + "…"; //$NON-NLS-1$
        return type + ":" + text; //$NON-NLS-1$
    }

    public static String eventSummary(DocumentEvent e)
    {
        if (e == null)
            return "null"; //$NON-NLS-1$
        String t = e.getText();
        return "off=" + e.getOffset() + " len=" + (t == null ? 0 : t.length()) //$NON-NLS-1$ //$NON-NLS-2$
            + " text=\"" + (t == null ? "" : t.replace('\n', ' ')) + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
