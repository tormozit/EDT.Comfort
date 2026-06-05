package tormozit;

import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Логи навигатора EDT: {@code Global.log} → {@code [NavigatorFilter] …}.
 * Отключить: {@code -Dtormozit.navigatorFilter.debug=false}.
 */
public final class NavigatorFilterDebug
{
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("tormozit.navigatorFilter.debug", "true")); //$NON-NLS-1$ //$NON-NLS-2$

    private NavigatorFilterDebug() {}

    public static boolean isEnabled()
    {
        return ENABLED;
    }

    public static void log(String msg)
    {
        if (ENABLED)
            Global.log("[NavigatorFilter] " + msg); //$NON-NLS-1$
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
