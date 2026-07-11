package tormozit;

/**
 * Журнал «Комфорт» для хука свойств точки останова ({@link BreakpointPropertiesHook}).
 */
final class BreakpointPropertiesDebug
{
    private static final String TAG = "BreakpointProperties"; //$NON-NLS-1$

    private BreakpointPropertiesDebug() {}

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

