package tormozit;

/**
 * Логи дополнения doc-hover BSL описанием из ИР.
 * Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class IrBslHoverDebug
{
    private static final String TAG = "IrBslHover"; //$NON-NLS-1$

    private IrBslHoverDebug() {}

    static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    static void step(String phase, String detail)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, phase + ": " + detail); //$NON-NLS-1$
    }

    static void problem(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }
}
