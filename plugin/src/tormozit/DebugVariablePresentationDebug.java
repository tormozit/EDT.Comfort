package tormozit;

/**
 * Диагностика {@link DebugVariablePresentationHook} — префикс {@code [N]} в деревьях отладчика.
 *
 * <p>Включение: параметры → Комфорт → «Общее логирование».
 */
public final class DebugVariablePresentationDebug
{
    private static final String TAG = "DebugVarPresentation"; //$NON-NLS-1$

    private DebugVariablePresentationDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (isEnabled())
            Global.log(TAG, msg);
    }

    public static void problem(String msg)
    {
        if (isEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    public static void step(String phase, String detail)
    {
        if (!isEnabled())
            return;
        if (detail == null || detail.isEmpty())
            Global.log(TAG, phase);
        else
            Global.log(TAG, phase + " " + detail); //$NON-NLS-1$
    }
}
