package tormozit;

/**
 * Замеры и диагностика обогащения автодополнения из ИР (порт RDT {@code ПриПолученииДанныхТ9}).
 */
public final class IrCompletionDebug
{
    private static final String TAG = "IrCompletion"; //$NON-NLS-1$

    private IrCompletionDebug() {}

    static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    public static void problem(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    static void step(String phase, String detail)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, phase + ": " + detail); //$NON-NLS-1$
    }

    static void timing(String label, long startedMs)
    {
        if (!Global.isLogEnabled())
            return;
        long ms = System.currentTimeMillis() - startedMs;
        Global.log(TAG, label + " " + ms + " мс"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void logApplyIrCompletion(int irCount, String baseKind)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "applyIrCompletion ir=" + irCount + " base=" + baseKind); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void logSnapshotCache(boolean hit, int contextKey, long docStamp)
    {
        if (!Global.isLogEnabled())
            return;
        Global.log(TAG, "snapshotCache " + (hit ? "hit" : "miss") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " ctx=" + contextKey + " stamp=" + docStamp); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void logIrPopupRefresh(String phase)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "ir popup refresh " + phase); //$NON-NLS-1$
    }

    static void logFastIrMergeReset(long elapsedMs, String preFirst, String newFirst)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "fastIrMergeReset elapsed=" + elapsedMs //$NON-NLS-1$
                + "ms preFirst=" + preFirst + " newFirst=" + newFirst); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void logFastIrMergeSkip(String reason, String preFirst, String newFirst)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "fastIrMergeSkip reason=" + reason //$NON-NLS-1$
                + " preFirst=" + preFirst + " newFirst=" + newFirst); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
