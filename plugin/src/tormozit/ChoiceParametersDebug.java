package tormozit;

/**
 * Логи хука «Редактирование параметров выбора».
 * Включение: Параметры → Комфорт → «Вести журнал».
 */
final class ChoiceParametersDebug
{
    private static final String TAG = "choiceParams"; //$NON-NLS-1$

    private ChoiceParametersDebug() {}

    static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    static String quote(String s)
    {
        return s == null ? "null" : "\"" + s + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
