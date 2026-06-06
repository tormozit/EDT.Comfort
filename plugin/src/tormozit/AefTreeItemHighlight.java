package tormozit;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Shell;

/**
 * AEF/LWT: чекбоксы и иконки не трогаем; умный фильтр — {@link SmartOutlineFilter};
 * красная подсветка — {@link AefComponentSearchHighlight} ({@code DtTreeViewFilter}).
 */
public final class AefTreeItemHighlight implements SmartLabelHighlight
{
    static final String SAVED_LISTENER_KEY = "tormozit.aefSearchListener"; //$NON-NLS-1$

    private String rawPattern = ""; //$NON-NLS-1$
    private ViewerFilter smartFilter;

    public AefTreeItemHighlight(String initialPattern)
    {
        setHighlightPattern(initialPattern);
    }

    public void bindContext(ViewerFilter smartFilter)
    {
        this.smartFilter = smartFilter;
    }

    @Override
    public void setHighlightPattern(String pattern)
    {
        rawPattern = pattern != null ? pattern : ""; //$NON-NLS-1$
    }

    public void apply(TreeViewer viewer, Shell shell)
    {
        if (viewer != null)
            clearBoldFlags(viewer);
        AefComponentSearchHighlight.apply(viewer, shell, rawPattern, smartFilter);
    }

    private void clearBoldFlags(TreeViewer viewer)
    {
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        for (Object root : tcp.getElements(viewer.getInput()))
            walkClearBold(tcp, root);
    }

    private void walkClearBold(ITreeContentProvider tcp, Object element)
    {
        if (element == null)
            return;
        if (isAefTreeItem(element))
            Global.invoke(element, "setBold", false); //$NON-NLS-1$
        if (tcp.hasChildren(element))
        {
            for (Object child : tcp.getChildren(element))
                walkClearBold(tcp, child);
        }
    }

    static boolean isAefTreeItem(Object element)
    {
        return element != null && element.getClass().getName().contains("TreeItemViewModel"); //$NON-NLS-1$
    }
}
