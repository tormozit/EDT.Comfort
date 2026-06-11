package tormozit;

import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Логи навигатора EDT через {@link Global}.
 * Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class NavigatorFilterDebug
{
    private static final String TAG = "NavigatorFilter"; //$NON-NLS-1$

    private NavigatorFilterDebug() {}

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    public static String safe(Object o)
    {
        if (o == null)
            return "<null>"; //$NON-NLS-1$
        return o.getClass().getName();
    }

    public static String filtersDesc(CommonViewer viewer)
    {
        if (viewer == null)
            return "viewer=null"; //$NON-NLS-1$
        ViewerFilter[] filters = viewer.getFilters();
        if (filters.length == 0)
            return "filters=0"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("filters=").append(filters.length).append(" ["); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i < filters.length; i++)
        {
            if (i > 0)
                sb.append(", "); //$NON-NLS-1$
            sb.append(filters[i].getClass().getSimpleName());
        }
        return sb.append(']').toString();
    }
}
