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
}
