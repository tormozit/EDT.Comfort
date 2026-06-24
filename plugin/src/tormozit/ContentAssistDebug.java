package tormozit;

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
    private static final Path AGENT_LOG_PATH = Path.of("C:\\VC\\EDT.Comfort\\debug-72952b.log"); //$NON-NLS-1$

    private ContentAssistDebug() {}

    // #region agent log
    public static void agentLog(String hypothesisId, String location, String message, String dataJson)
    {
        try
        {
            String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
            String line = String.format(
                "{\"sessionId\":\"72952b\",\"hypothesisId\":\"%s\",\"location\":\"%s\",\"message\":\"%s\",\"data\":%s,\"timestamp\":%d}%n", //$NON-NLS-1$
                agentEsc(hypothesisId), agentEsc(location), agentEsc(message), data,
                System.currentTimeMillis());
            Files.writeString(AGENT_LOG_PATH, line, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        }
        catch (Exception ignored) {}
    }

    public static boolean shouldLogValidateLiteral()
    {
        return agentValidateLogs.incrementAndGet() <= 5;
    }

    private static String agentEsc(String s)
    {
        if (s == null)
            return ""; //$NON-NLS-1$
        return s.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
