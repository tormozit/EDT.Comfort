package tormozit;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.contentassist.ICompletionProposal;

/**
 * Логи Content Assist в общем журнале {@link GlobalLogView}.
 */
public final class ContentAssistDebug
{
    private static final AtomicInteger validateCalls = new AtomicInteger();
    private static final AtomicInteger agentValidateLogs = new AtomicInteger();
    private static final Path DEBUG_SESSION_LOG = Path.of("C:\\VC\\EDT.Comfort\\debug-0ae881.log"); //$NON-NLS-1$
    private static final String DEBUG_SESSION_ID = "0ae881"; //$NON-NLS-1$
    private static final Path DEBUG_MODE_LOG = Path.of("C:\\VC\\EDT.Comfort\\debug-267a94.log"); //$NON-NLS-1$
    private static final String DEBUG_MODE_SESSION_ID = "267a94"; //$NON-NLS-1$
    /** Временный файл для диагностики лага ввода (perfLog) — не «Журнал Комфорт», см. comfort-logging.mdc п.2. */
    private static final Path PERF_LOG = Path.of("C:\\VC\\EDT.Comfort\\debug-perf-query-lag.log"); //$NON-NLS-1$
    private static final BlockingQueue<String> PERF_QUEUE = new LinkedBlockingQueue<>(20_000);
    /** Маркер сборки для literal assist. */
    public static final String LITERAL_ASSIST_BUILD = "20260708-sync-selection"; //$NON-NLS-1$
    /** NDJSON в debug-267a94.log — временная отладка; выкл. после фикса. */
    private static final boolean NDJSON_ENABLED = false;
    /** Доп. шум только с {@code -Dtormozit.contentAssistVerbose=true}. */
    private static final boolean VERBOSE =
        Boolean.getBoolean("tormozit.contentAssistVerbose"); //$NON-NLS-1$

    /**
     * Асинхронная очередь для disk-write логов — никакого I/O на UI-потоке.
     * Ёмкость 50 000 строк (~5 МБ); при переполнении строки отбрасываются без исключения.
     */
    private static final BlockingQueue<String> DEBUG_MODE_QUEUE = new LinkedBlockingQueue<>(50_000);
    private static final BlockingQueue<String> SESSION_QUEUE = new LinkedBlockingQueue<>(10_000);

    static
    {
        if (NDJSON_ENABLED)
        {
            try { Files.deleteIfExists(DEBUG_MODE_LOG); } catch (IOException e) { /* best-effort */ }
            startAsyncWriter("ContentAssistDebugModeWriter", DEBUG_MODE_LOG, DEBUG_MODE_QUEUE); //$NON-NLS-1$
        }
        if (VERBOSE)
        {
            try { Files.deleteIfExists(DEBUG_SESSION_LOG); } catch (IOException e) { /* best-effort */ }
            startAsyncWriter("ContentAssistSessionWriter", DEBUG_SESSION_LOG, SESSION_QUEUE); //$NON-NLS-1$
        }
        try { Files.deleteIfExists(PERF_LOG); } catch (IOException e) { /* best-effort */ }
        startAsyncWriter("ContentAssistPerfLagWriter", PERF_LOG, PERF_QUEUE); //$NON-NLS-1$
    }

    private static void startAsyncWriter(String threadName, Path logPath, BlockingQueue<String> queue)
    {
        Thread t = new Thread(() ->
        {
            try (BufferedWriter bw = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            {
                while (!Thread.currentThread().isInterrupted())
                {
                    String line = queue.take(); // blocks until available
                    bw.write(line);
                    // drain the rest of the batch to reduce flush calls
                    String next;
                    while ((next = queue.poll()) != null)
                        bw.write(next);
                    bw.flush();
                }
            }
            catch (InterruptedException ignored)
            {
                Thread.currentThread().interrupt();
            }
            catch (IOException e)
            {
                // best-effort logging — ignore write failures
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
    }

    private ContentAssistDebug() {}

    // #region agent log
    /** H00 при старте EDT — однозначная версия bytecode в debug-c201d2.log. */
    public static void logLiteralAssistBuildStamp()
    {
        debugModeLog("H00", "ContentAssistDebug", "buildStamp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"build\":\"" + LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** NDJSON автооткрытия — в файл; в журнал при «Общем логировании». */
    public static void logAutoOpen(int autoOpenSeq, String hypothesisId, String location,
        String message, String dataJson)
    {
        String inner = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
        String trimmed = inner.endsWith("}") ? inner.substring(0, inner.length() - 1) : inner; //$NON-NLS-1$
        String data = trimmed + ",\"autoOpenSeq\":" + autoOpenSeq //$NON-NLS-1$
            + ",\"build\":\"" + LITERAL_ASSIST_BUILD + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
        debugModeLog(hypothesisId, location, message, data);
        agentLog(hypothesisId, location, message, data);
    }

    /** Трассировка решений popup — NDJSON + журнал «Комфорт». */
    public static void traceAssist(String hypothesisId, String location, String message,
                                   String dataJson)
    {
        debugModeLog(hypothesisId, location, message, dataJson);
        agentLog(hypothesisId, location, message, dataJson);
    }

    /** Decision-point автооткрытия в журнал «Комфорт». */
    public static void logAutoOpenDecision(String decision, int caret, int irN)
    {
        agentLog("H78", "onWordsTablePrepared", "autoOpenDecision", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"decision\":\"" + jsonEscape(decision) + "\",\"caret\":" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"irN\":" + irN + "}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** NDJSON в debug-267a94.log. */
    public static void debugModeLog(String hypothesisId, String location, String message,
                                    String dataJson)
    {
        if (!NDJSON_ENABLED)
            return;
        if (!VERBOSE && isNoisyNdjsonLocation(location, message))
            return;
        try
        {
            String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
            String line = "{\"sessionId\":\"" + DEBUG_MODE_SESSION_ID //$NON-NLS-1$
                + "\",\"hypothesisId\":\"" + hypothesisId //$NON-NLS-1$
                + "\",\"location\":\"" + location //$NON-NLS-1$
                + "\",\"message\":\"" + jsonEscape(message) //$NON-NLS-1$
                + "\",\"data\":" + data //$NON-NLS-1$
                + ",\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
            DEBUG_MODE_QUEUE.offer(line); // non-blocking; drops if queue full
        }
        catch (Exception ignored)
        {
        }
    }

    /** Hot-path шум на каждое `.` / recompute — только с -Dtormozit.contentAssistVerbose=true. */
    private static boolean isNoisyNdjsonLocation(String location, String message)
    {
        if (location == null)
            return false;
        switch (location)
        {
            case "applyStockAssistImagesFromDelegate": //$NON-NLS-1$
            case "mergeIrProposals": //$NON-NLS-1$
            case "browserContentLoad": //$NON-NLS-1$
            case "sidePanelRefresh": //$NON-NLS-1$
            case "literalOpenPhase": //$NON-NLS-1$
            case "literalBrowserPhase": //$NON-NLS-1$
            case "literalCreatorResolve": //$NON-NLS-1$
            case "literalListState": //$NON-NLS-1$
            case "literalMergeTimeline": //$NON-NLS-1$
            case "literalOpenFlash": //$NON-NLS-1$
            case "htmlApplyPoll": //$NON-NLS-1$
            case "irActivationWarmup": //$NON-NLS-1$
            case "isStringLiteralAssistContext": //$NON-NLS-1$
            case "computeCompletionProposals": //$NON-NLS-1$
            case "computeProposalsLight": //$NON-NLS-1$
            case "filterCachedProposalsForPopup": //$NON-NLS-1$
            case "applyPopupListSync": //$NON-NLS-1$
            case "mergeDedupAudit": //$NON-NLS-1$
            case "recomputePopupList": //$NON-NLS-1$
            case "isCompletionAutoOpenAwaitingWords": //$NON-NLS-1$
            case "coldCreatorBootstrap": //$NON-NLS-1$
            case "ContentAssistSessionReloader.install": //$NON-NLS-1$
            case "ContentAssistPatcher.applyPatch": //$NON-NLS-1$
            case "installCtrlSpaceFilter": //$NON-NLS-1$
            case "prepareAssistContextAsync": //$NON-NLS-1$
            case "fetchCompletionSnapshot": //$NON-NLS-1$
            case "applyIrCompletion": //$NON-NLS-1$
                return true;
            case "assistSessionStarted": //$NON-NLS-1$
                return !"session".equals(message) && !"sessionStarted".equals(message); //$NON-NLS-1$ //$NON-NLS-2$
            case "onWordsTablePrepared": //$NON-NLS-1$
                return "branch".equals(message) || "merge".equals(message) //$NON-NLS-1$ //$NON-NLS-2$
                    || "skipDuplicate".equals(message); //$NON-NLS-1$
            default:
                return false;
        }
    }

    /** NDJSON в debug-0ae881.log — без флажка «Общее логирование». */
    public static void sessionLog(String hypothesisId, String location, String message, String dataJson)
    {
        try
        {
//            String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
//            String line = "{\"sessionId\":\"" + DEBUG_SESSION_ID //$NON-NLS-1$
//                + "\",\"hypothesisId\":\"" + hypothesisId //$NON-NLS-1$
//                + "\",\"location\":\"" + location //$NON-NLS-1$
//                + "\",\"message\":\"" + jsonEscape(message) //$NON-NLS-1$
//                + "\",\"data\":" + data //$NON-NLS-1$
//                + ",\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
//            SESSION_QUEUE.offer(line); // non-blocking; drops if queue full
        }
        catch (Exception ignored)
        {
        }
    }

    private static String jsonEscape(String value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        return value.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Экранирование для NDJSON data (package для reloader). */
    static String jsonEscapeForLog(String value)
    {
        return jsonEscape(value);
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
    // #endregion

    /** Диагностика assist (H1/H2/…) — только в «Журнал Комфорт» при «Общем логировании». */
    public static void agentLog(String hypothesisId, String location, String message, String dataJson)
    {
        if (!Global.isLogEnabled())
            return;
        String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
        Global.log("contentAssist", //$NON-NLS-1$
            "[" + hypothesisId + "] " + location + " " + message + " " + data); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Временная диагностика лага ввода в «Редакторе запроса» (не «Журнал Комфорт» —
     * см. comfort-logging.mdc п.2): пишет в {@code debug-perf-query-lag.log} БЕЗУСЛОВНО,
     * на каждый вызов — без флажков, без порога. {@code thresholdMs} не используется для
     * фильтрации, только помечает вызов как «медленный» в самой записи. Снять после фикса.
     */
    public static void perfLog(String location, long elapsedMs, int thresholdMs, String extraJson)
    {
        String extra = extraJson != null && !extraJson.isEmpty() ? extraJson : "{}"; //$NON-NLS-1$
        String line = "{\"location\":\"" + jsonEscape(location) //$NON-NLS-1$
            + "\",\"ms\":" + elapsedMs //$NON-NLS-1$
            + ",\"slow\":" + (elapsedMs >= thresholdMs) //$NON-NLS-1$
            + ",\"data\":" + extra //$NON-NLS-1$
            + ",\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
        PERF_QUEUE.offer(line); // non-blocking; drops if queue full
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
            return " types=[]";
        StringBuilder sb = new StringBuilder(" types=[");
        int n = Math.min(max, arr.length);
        for (int i = 0; i < n; i++)
        {
            if (i > 0) sb.append(',');
            sb.append(arr[i] == null ? "null" : arr[i].getClass().getSimpleName());
        }
        if (arr.length > n) sb.append("…");
        return sb.append(']').toString();
    }

    public static void resetValidateStats()
    {
        validateCalls.set(0);
    }

    public static String proposalLabel(ICompletionProposal p)
    {
        if (p == null)
            return "null";
        String d = p.getDisplayString();
        String type = p.getClass().getSimpleName();
        if (p instanceof SmartCompletionProposal)
            type += "→" + ((SmartCompletionProposal) p).getDelegate().getClass().getSimpleName();
        String text = d == null ? "" : d;
        if (text.length() > 48)
            text = text.substring(0, 48) + "…";
        return type + ":" + text;
    }

    public static String eventSummary(DocumentEvent e)
    {
        if (e == null)
            return "null";
        String t = e.getText();
        return "off=" + e.getOffset() + " len=" + (t == null ? 0 : t.length()) //$NON-NLS-1$ //$NON-NLS-2$
            + " text=\"" + (t == null ? "" : t.replace('\n', ' ')) + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
