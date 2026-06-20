package tormozit;

/**
 * Логи хука «Фильтр по подсистемам» через {@link Global}.
 * Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class FilterBySubsystemsDialogDebug
{
    private static final String TAG = "FilterBySubsystemsDialog"; //$NON-NLS-1$

    private FilterBySubsystemsDialogDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    public static void step(String phase, String detail)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, phase + ": " + detail); //$NON-NLS-1$
    }
}
