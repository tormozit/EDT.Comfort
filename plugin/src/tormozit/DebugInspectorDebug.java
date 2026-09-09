package tormozit;

/**
 * Диагностика патча окон инспектора отладки.
 *
 * <p>Включение: параметры → Комфорт → «Вести журнал».
 */
public final class DebugInspectorDebug
{
    private static final String TAG = "DebugInspector"; //$NON-NLS-1$

    private DebugInspectorDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (isEnabled())
            Global.log(TAG, msg);
    }

    /** Сообщение о сбое / нештатная ситуация. */
    public static void problem(String msg)
    {
        if (isEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }

    /** Ключевое событие с префиксом фазы. */
    public static void step(String phase, String detail)
    {
        if (!isEnabled())
            return;
        if (detail == null || detail.isEmpty())
            Global.log(TAG, phase);
        else
            Global.log(TAG, phase + " " + detail); //$NON-NLS-1$
    }

    /** Короткое имя класса для диагностики. */
    public static String cn(Object value)
    {
        if (value == null)
            return "null"; //$NON-NLS-1$
        return value.getClass().getSimpleName();
    }
}
