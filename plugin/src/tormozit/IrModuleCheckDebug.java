package tormozit;

/** Логи ожидания и разбора результатов команды «Проверить модуль ИР» (журнал «Комфорт»). */
final class IrModuleCheckDebug
{
    private static final String TAG = "IrModuleCheck"; //$NON-NLS-1$
    private IrModuleCheckDebug() {}

    static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    static void problem(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }
}
