package tormozit;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.contentassist.ICompletionProposal;

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
