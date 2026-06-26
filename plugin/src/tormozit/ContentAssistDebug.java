package tormozit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private static final Path DEBUG_MODE_LOG = Path.of("C:\\VC\\EDT.Comfort\\debug-c201d2.log"); //$NON-NLS-1$
    private static final String DEBUG_MODE_SESSION_ID = "c201d2"; //$NON-NLS-1$
    /** Маркер сборки для literal assist — сверять в debug-c201d2.log (H00/H0). */
    public static final String LITERAL_ASSIST_BUILD = "20260626-fix8b"; //$NON-NLS-1$

    private ContentAssistDebug() {}

    // #region agent log
    /** H00 при старте EDT — однозначная версия bytecode в debug-c201d2.log. */
    public static void logLiteralAssistBuildStamp()
    {
        debugModeLog("H00", "ContentAssistDebug", "buildStamp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"build\":\"" + LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** NDJSON в debug-c201d2.log — отладка IR Ctrl+Space (без флажка логирования). */
    public static void debugModeLog(String hypothesisId, String location, String message,
                                    String dataJson)
    {
        try
        {
            String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
            String line = "{\"sessionId\":\"" + DEBUG_MODE_SESSION_ID //$NON-NLS-1$
                + "\",\"hypothesisId\":\"" + hypothesisId //$NON-NLS-1$
                + "\",\"location\":\"" + location //$NON-NLS-1$
                + "\",\"message\":\"" + jsonEscape(message) //$NON-NLS-1$
                + "\",\"data\":" + data //$NON-NLS-1$
                + ",\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
            Files.writeString(DEBUG_MODE_LOG, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception ignored)
        {
        }
    }

    /** NDJSON в debug-0ae881.log — без флажка «Общее логирование». */
    public static void sessionLog(String hypothesisId, String location, String message, String dataJson)
    {
        try
        {
            String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
            String line = "{\"sessionId\":\"" + DEBUG_SESSION_ID //$NON-NLS-1$
                + "\",\"hypothesisId\":\"" + hypothesisId //$NON-NLS-1$
                + "\",\"location\":\"" + location //$NON-NLS-1$
                + "\",\"message\":\"" + jsonEscape(message) //$NON-NLS-1$
                + "\",\"data\":" + data //$NON-NLS-1$
                + ",\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
            Files.writeString(DEBUG_SESSION_LOG, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
