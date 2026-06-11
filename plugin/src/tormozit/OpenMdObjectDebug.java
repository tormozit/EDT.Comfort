package tormozit;

import org.eclipse.core.runtime.jobs.Job;

/**
 * Логи диалога «Открыть объект метаданных» через {@link Global}.
 * Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class OpenMdObjectDebug
{
    private static final String TAG = "OpenMdObject"; //$NON-NLS-1$

    private OpenMdObjectDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    public static String filterDesc(Object filter)
    {
        if (filter == null)
            return "null";
        String type = filter.getClass().getSimpleName();
        if (filter instanceof org.eclipse.ui.dialogs.OpenMdObjectItemsFilter) {
            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter f =
                    (org.eclipse.ui.dialogs.OpenMdObjectItemsFilter) filter;
            return type + "@" + Integer.toHexString(System.identityHashCode(filter))
                    + " pat=\"" + f.getPattern() + "\""; //$NON-NLS-1$
        }
        try {
            Object pat = Global.invoke(filter, "getPattern");
            return type + " pat=\"" + pat + "\""; //$NON-NLS-1$
        } catch (Exception e) {
            return type;
        }
    }

    public static String jobState(Job job)
    {
        if (job == null)
            return "null";
        switch (job.getState()) {
            case Job.RUNNING: return "RUNNING";
            case Job.WAITING: return "WAITING";
            case Job.SLEEPING: return "SLEEPING";
            default: return "NONE";
        }
    }
}
