package tormozit;

/** Диагностика прокачки сообщений ИР ({@code ПрочитатьУдалитьСообщенияПользователю}). */
public final class IrUserMessagesDebug
{
    private static final String TAG = "IrUserMessages"; //$NON-NLS-1$

    private IrUserMessagesDebug() {}

    public static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    public static void step(String phase, String detail)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, phase + (detail != null && !detail.isEmpty() ? ": " + detail : "")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
