package tormozit;

/**
 * Логи боковой подсказки BSL (outline-popup) через {@link Global}.
 * Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class BslSideHintDebug
{
    private static final String TAG = "BslSideHint"; //$NON-NLS-1$

    private BslSideHintDebug() {}

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
}

