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
    private static final Path DEBUG_SESSION_LOG =
        Path.of("C:\\VC\\EDT.Comfort\\debug-9e4a68.log"); //$NON-NLS-1$

    private ContentAssistDebug() {}

    // #region agent log
    public static void debugSessionLog(
        String hypothesisId, String location, String message, String dataJson)
    {
        try
        {
            long ts = System.currentTimeMillis();
            String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
            String line = "{\"sessionId\":\"9e4a68\",\"hypothesisId\":\"" + hypothesisId //$NON-NLS-1$
                + "\",\"location\":\"" + location + "\",\"message\":\"" + message //$NON-NLS-1$ //$NON-NLS-2$
                + "\",\"data\":" + data + ",\"timestamp\":" + ts + "}\n"; //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(DEBUG_SESSION_LOG, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception ignored) {}
    }

    public static String jsonStr(String value)
    {
        if (value == null)
            return "\"\""; //$NON-NLS-1$
        String s = value.length() > 80 ? value.substring(0, 80) + "…" : value; //$NON-NLS-1$
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /** Замер фазы: elapsedMs с момента {@code startedMs}. */
    public static void debugSessionTiming(
        String hypothesisId, String location, String phase, long startedMs, String extraJson)
    {
        long elapsed = System.currentTimeMillis() - startedMs;
        String extra = extraJson != null && !extraJson.isEmpty() ? extraJson : ""; //$NON-NLS-1$
        if (!extra.isEmpty() && !extra.startsWith(",")) //$NON-NLS-1$
            extra = "," + extra; //$NON-NLS-1$
        debugSessionLog(hypothesisId, location, phase,
            "{\"elapsedMs\":" + elapsed + extra + "}"); //$NON-NLS-1$ //$NON-NLS-2$
    }

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
    // #endregion

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
