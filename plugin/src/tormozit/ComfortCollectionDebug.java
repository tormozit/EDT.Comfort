package tormozit;

/**
 * Диагностика окон «Коллекция».
 *
 * <p>Включение: параметры → Комфорт → «Общее логирование».
 */
public final class ComfortCollectionDebug
{
    private static final String TAG = "Collection"; //$NON-NLS-1$

    private ComfortCollectionDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (isEnabled())
            Global.log(TAG, msg);
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

    public static void problem(String msg)
    {
        if (isEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }
}
