package tormozit;

import org.eclipse.core.runtime.jobs.Job;

/**
 * Логи диалога «Открыть объект метаданных»: {@code Global.log} → {@code [OpenMdObject] …}.
 * Отключить: {@code -Dtormozit.openMdObject.debug=false}.
 */
public final class OpenMdObjectDebug
{
    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("tormozit.openMdObject.debug", "true")); //$NON-NLS-1$ //$NON-NLS-2$

    private OpenMdObjectDebug() {}

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static void log(String msg)
    {
        if (ENABLED)
            Global.log("[OpenMdObject] " + msg); //$NON-NLS-1$
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
