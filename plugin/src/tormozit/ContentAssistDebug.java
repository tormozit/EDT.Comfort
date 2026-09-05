package tormozit;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.swt.widgets.Display;

/**
 * Логи Content Assist в общем журнале {@link GlobalLogView} (при «Общем логировании»).
 * Файловый NDJSON / temp-logs для отладки сняты перед релизом.
 */
public final class ContentAssistDebug
{
    private static final AtomicInteger validateCalls = new AtomicInteger();
    private static final AtomicInteger agentValidateLogs = new AtomicInteger();

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

    public static void perfLog(String location, long elapsedMs, int thresholdMs, String extraJson)
    {
        perfWrite(location, elapsedMs, Display.getCurrent() != null, extraJson);
    }

    // ---- Замер задержек автодополнения (временная диагностика) ----------------
    //
    // Логирование БЕЗУСЛОВНОЕ: не зависит от «Общего логирования» и не пишет в журнал
    // «Комфорт». Приёмник — .tmp/temp-logs/assist-perf.log (буферизованный, без I/O
    // на UI-потоке). Порог SLOW_MS только помечает строку полем "slow", но никогда
    // не решает, писать её или нет.

    private static final String PERF_TOPIC = "assist-perf"; //$NON-NLS-1$
    /** Выше этого — строка помечается {@code "slow":true}. */
    private static final long SLOW_MS = 40;
    /** Выше этого — сторожевой поток снимает стек UI-потока прямо во время блокировки. */
    private static final long STALL_DUMP_MS = 250;
    private static final long WATCHDOG_STEP_MS = 50;
    private static final int STALL_FRAMES = 30;

    /** UI-поток (для снятия стека сторожем). */
    private static volatile Thread uiThread;
    /** Имя внешней (верхнеуровневой) секции, открытой на UI-потоке; {@code null} — секции нет. */
    private static volatile String uiSectionName;
    private static volatile long uiSectionStartNanos;
    private static volatile boolean uiSectionDumped;
    /** Глубина вложенности секций на UI-потоке (пишет только UI-поток). */
    private static int uiSectionDepth;
    private static volatile Thread watchdog;

    /**
     * Начало замеряемой секции. Возвращает метку времени для {@link #perfEnd}.
     *
     * <p>Верхнеуровневая секция на UI-потоке дополнительно ставится под сторожевой поток:
     * если UI не вернётся за {@link #STALL_DUMP_MS}, в лог попадёт стек UI-потока — видно,
     * в каком именно вызове EDT висим, без гадания.
     */
    public static long perfStart(String location)
    {
        long now = System.nanoTime();
        if (Display.getCurrent() != null)
        {
            uiThread = Thread.currentThread();
            if (uiSectionDepth++ == 0)
            {
                uiSectionName = location;
                uiSectionStartNanos = now;
                uiSectionDumped = false;
                ensureWatchdog();
            }
        }
        return now;
    }

    /** Конец замеряемой секции: одна строка в лог всегда. */
    public static void perfEnd(String location, long startNanos, String extraJson)
    {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        boolean ui = Display.getCurrent() != null;
        if (ui && uiSectionDepth > 0 && --uiSectionDepth == 0)
            uiSectionName = null;
        perfWrite(location, elapsedMs, ui, extraJson);
    }

    /** Разовая отметка без секции (счётчик, событие). */
    public static void perfMark(String location, String extraJson)
    {
        perfWrite(location, -1, Display.getCurrent() != null, extraJson);
    }

    private static void perfWrite(String location, long elapsedMs, boolean ui, String extraJson)
    {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"loc\":\"").append(jsonEscape(location)).append('"'); //$NON-NLS-1$
        if (elapsedMs >= 0)
            sb.append(",\"ms\":").append(elapsedMs) //$NON-NLS-1$
                .append(",\"slow\":").append(elapsedMs >= SLOW_MS); //$NON-NLS-1$
        sb.append(",\"ui\":").append(ui); //$NON-NLS-1$
        if (!ui)
            sb.append(",\"thread\":\"").append(jsonEscape(Thread.currentThread().getName())) //$NON-NLS-1$
                .append('"');
        if (extraJson != null && !extraJson.isEmpty())
            sb.append(",\"d\":").append(extraJson); //$NON-NLS-1$
        sb.append('}');
        Global.tempLog(PERF_TOPIC, sb.toString());
    }

    private static void ensureWatchdog()
    {
        if (watchdog != null)
            return;
        synchronized (ContentAssistDebug.class)
        {
            if (watchdog != null)
                return;
            Thread t = new Thread(ContentAssistDebug::watchdogLoop, "ComfortAssistPerfWatchdog"); //$NON-NLS-1$
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            t.start();
            watchdog = t;
        }
    }

    private static void watchdogLoop()
    {
        while (true)
        {
            try
            {
                Thread.sleep(WATCHDOG_STEP_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            try
            {
                dumpUiStackIfStalled();
            }
            catch (Exception ignored)
            {
                // диагностика не должна ронять поток
            }
        }
    }

    private static void dumpUiStackIfStalled()
    {
        String section = uiSectionName;
        Thread ui = uiThread;
        if (section == null || ui == null || uiSectionDumped)
            return;
        long elapsedMs = (System.nanoTime() - uiSectionStartNanos) / 1_000_000L;
        if (elapsedMs < STALL_DUMP_MS)
            return;
        uiSectionDumped = true;
        StackTraceElement[] stack = ui.getStackTrace();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("UI STALL ").append(elapsedMs).append(" ms in ").append(section); //$NON-NLS-1$ //$NON-NLS-2$
        int n = Math.min(STALL_FRAMES, stack.length);
        for (int i = 0; i < n; i++)
            sb.append(System.lineSeparator()).append("    at ").append(stack[i]); //$NON-NLS-1$
        Global.tempLog(PERF_TOPIC, sb.toString());
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
