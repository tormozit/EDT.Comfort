package tormozit;

import java.util.regex.Pattern;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * Красная подсветка AEF: {@code DtTreeViewFilter.searchPattern} на дереве
 * (после {@code DtTreeView$SearchListener.performSearch}). Видимость — {@link SmartOutlineFilter}.
 */
final class AefComponentSearchHighlight
{
    private static final String DT_TREE_VIEW_FILTER = "DtTreeViewFilter"; //$NON-NLS-1$

    private AefComponentSearchHighlight() {}

    static void apply(TreeViewer viewer, Shell shell, String pattern, ViewerFilter smartFilter)
    {
        String query = pattern != null ? pattern : ""; //$NON-NLS-1$
        Object listener = resolveSearchListener(shell, viewer);

        if (query.isEmpty())
        {
            removeDtTreeViewFilters(viewer);
            if (viewer != null)
                viewer.refresh();
            restoreSmartFilterOnly(viewer, smartFilter);
            return;
        }

        if (listener == null || viewer == null)
            return;

        Object dtTreeView = Global.getField(listener, "parentComposite"); //$NON-NLS-1$
        if (!tryHighlightViaPerformSearch(viewer, shell, listener, dtFilterPattern(query)))
            return;

        patchDtTreeViewFilterPattern(viewer, highlightRegex(query));
        if (dtTreeView != null)
            Global.invokeVoid(dtTreeView, "refresh"); //$NON-NLS-1$
        viewer.refresh();
        restoreSmartFilterOnly(viewer, smartFilter);
    }

    private static Object resolveSearchListener(Shell shell, TreeViewer viewer)
    {
        if (shell != null)
        {
            Object listener = shell.getData(AefTreeItemHighlight.SAVED_LISTENER_KEY);
            if (listener != null)
                return listener;
        }
        if (viewer != null)
        {
            Control control = viewer.getControl();
            if (control != null)
                return control.getData(AefTreeItemHighlight.SAVED_LISTENER_KEY);
        }
        return null;
    }

    private static String dtFilterPattern(String query)
    {
        String trimmed = query.trim();
        if (trimmed.isEmpty())
            return trimmed;
        String[] parts = trimmed.split("\\s+"); //$NON-NLS-1$
        return parts[0].toLowerCase();
    }

    private static String highlightRegex(String query)
    {
        String trimmed = query.trim().toLowerCase();
        if (trimmed.isEmpty())
            return ""; //$NON-NLS-1$
        String[] parts = trimmed.split("\\s+"); //$NON-NLS-1$
        if (parts.length == 1)
            return Pattern.quote(parts[0]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++)
        {
            if (i > 0)
                sb.append('|');
            sb.append(Pattern.quote(parts[i]));
        }
        return sb.toString();
    }

    private static void patchDtTreeViewFilterPattern(TreeViewer viewer, String regex)
    {
        if (viewer == null || regex == null || regex.isEmpty())
            return;
        Object filter = findDtTreeViewFilter(viewer);
        if (filter == null)
            return;
        try
        {
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Global.setField(filter, "searchPattern", pattern); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }

    private static Object findDtTreeViewFilter(TreeViewer viewer)
    {
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter != null && filter.getClass().getName().contains(DT_TREE_VIEW_FILTER))
                return filter;
        }
        return null;
    }

    private static boolean tryHighlightViaPerformSearch(TreeViewer viewer, Shell shell, Object listener,
            String dtPattern)
    {
        Object searchBox = shell != null ? findSearchBox(shell) : null;
        boolean reattached = false;
        try
        {
            if (searchBox != null)
            {
                Global.invoke(searchBox, "setSearchListener", listener); //$NON-NLS-1$
                reattached = true;
            }
            return Global.invokeVoid(listener, "performSearch", dtPattern, new NullProgressMonitor()); //$NON-NLS-1$
        }
        finally
        {
            if (reattached && searchBox != null)
                Global.invoke(searchBox, "setSearchListener", (Object) null); //$NON-NLS-1$
        }
    }

    private static void removeDtTreeViewFilters(TreeViewer viewer)
    {
        if (viewer == null)
            return;
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter != null && filter.getClass().getName().contains(DT_TREE_VIEW_FILTER))
                viewer.removeFilter(filter);
        }
    }

    private static void restoreSmartFilterOnly(TreeViewer viewer, ViewerFilter smartFilter)
    {
        if (viewer == null)
            return;
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter == smartFilter)
                continue;
            if (filter != null && filter.getClass().getName().contains(DT_TREE_VIEW_FILTER))
                continue;
            viewer.removeFilter(filter);
        }
    }

    static Object findSearchBox(Composite root)
    {
        if (root == null || root.isDisposed())
            return null;
        for (Control child : root.getChildren())
        {
            if (child.getClass().getName().contains("SearchBox")) //$NON-NLS-1$
                return child;
            if (child instanceof Composite)
            {
                Object found = findSearchBox((Composite) child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
