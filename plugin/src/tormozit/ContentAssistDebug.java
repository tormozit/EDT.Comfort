package tormozit;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.contentassist.ICompletionProposal;

/**
 * Логи content assist в консоль EDT: {@code Global.log} → {@code [Tormozit …] [ContentAssist] …}.
 * Отключить: {@code -Dtormozit.contentAssist.debug=false}.
 */
public final class ContentAssistDebug
{
    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("tormozit.contentAssist.debug", "true")); //$NON-NLS-1$ //$NON-NLS-2$

    private static final AtomicInteger validateCalls = new AtomicInteger();
    private static final AtomicInteger validateReject = new AtomicInteger();

    private ContentAssistDebug() {}

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static void log(String msg)
    {
        if (ENABLED)
            Global.log("[ContentAssist] " + msg); //$NON-NLS-1$
    }

    public static void logValidate(boolean accepted, String filter, ICompletionProposal proposal, int offset)
    {
        if (!ENABLED)
            return;
        int n = validateCalls.incrementAndGet();
        boolean raw = !(proposal instanceof SmartCompletionProposal);
        if (raw && n <= 5)
            log("validate on RAW (not SmartCompletionProposal) — штатный incremental popup"); //$NON-NLS-1$
        if (!accepted)
        {
            int r = validateReject.incrementAndGet();
            if (r <= 15 || raw)
                log("validate REJECT #" + r + " filter=\"" + filter + "\" offset=" + offset //$NON-NLS-1$ //$NON-NLS-2$
                    + " item=" + proposalLabel(proposal)); //$NON-NLS-1$
        }
        else if (n <= 2)
        {
            log("validate ok #" + n + " filter=\"" + filter + "\" " + proposalLabel(proposal)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
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
        validateReject.set(0);
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
