package tormozit;

import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Логи хука «Информационные базы» через {@link Global}.
 * Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class InfobasesViewDebug
{
    private static final String TAG = "InfobasesView"; //$NON-NLS-1$

    private InfobasesViewDebug() {}

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
            return "filters=[]"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("filters=["); //$NON-NLS-1$
        for (int i = 0; i < filters.length; i++)
        {
            if (i > 0)
                sb.append(", "); //$NON-NLS-1$
            sb.append(filters[i].getClass().getSimpleName());
        }
        return sb.append(']').toString();
    }
}
