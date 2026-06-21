package tormozit;

/**
 * Диагностика панелей «Выражения» / «Переменные» (подавление полного refresh после «Изменить значение»).
 *
 * <p>Включение: параметры → Комфорт → «Общее логирование».
 */
public final class DebugExpressionsDebug
{
    private static final String TAG = "Expressions"; //$NON-NLS-1$

    private DebugExpressionsDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
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

    public static void problem(String detail)
    {
        if (!isEnabled())
            return;
        Global.log(TAG, "[!] " + detail); //$NON-NLS-1$
    }
}
