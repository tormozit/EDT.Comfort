package tormozit;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.events.TreeAdapter;
import org.eclipse.swt.events.TreeEvent;
import org.eclipse.swt.widgets.Tree;

import java.util.HashSet;
import java.util.Set;

/**
 * Авторазворачивание цепочки единственных дочерних узлов при ручном expand.
 */
final class TreeSoleChildAutoExpand
{
    private static final String MARKER_KEY = "tormozit.treeSoleChildAutoExpand"; //$NON-NLS-1$

    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> IN_AUTO_EXPAND = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @FunctionalInterface
    interface VisibleChildFilter
    {
        boolean isVisible(AbstractTreeViewer viewer, Object parent, Object child);
    }

    @FunctionalInterface
    interface EnabledCheck
    {
        boolean isEnabled();
    }

    private static final EnabledCheck ALWAYS_ENABLED = () -> true;

    static void install(AbstractTreeViewer viewer)
    {
        install(viewer, TreeSoleChildAutoExpand::defaultVisible, ALWAYS_ENABLED);
    }

    static void installForComfortLists(AbstractTreeViewer viewer)
    {
        install(viewer, TreeSoleChildAutoExpand::defaultVisible, ComfortSettings::isReplaceListFiltersEnabled);
    }

    static void install(AbstractTreeViewer viewer, VisibleChildFilter filter)
    {
        install(viewer, filter, ALWAYS_ENABLED);
    }

    static void install(AbstractTreeViewer viewer, VisibleChildFilter filter, EnabledCheck enabled)
    {
        if (viewer == null || filter == null)
            return;

        EnabledCheck activeCheck = enabled != null ? enabled : ALWAYS_ENABLED;

        Tree tree = resolveTree(viewer);
        if (tree == null || tree.isDisposed() || Boolean.TRUE.equals(tree.getData(MARKER_KEY)))
            return;

        tree.setData(MARKER_KEY, Boolean.TRUE);
        tree.addTreeListener(new TreeAdapter()
        {
            @Override
            public void treeExpanded(TreeEvent event)
            {
                if (!activeCheck.isEnabled()
                        || Boolean.TRUE.equals(SUPPRESSED.get())
                        || Boolean.TRUE.equals(IN_AUTO_EXPAND.get()))
                    return;

                Object element = event.item != null ? event.item.getData() : null;
                if (element == null)
                    return;

                IN_AUTO_EXPAND.set(Boolean.TRUE);
                try
                {
                    expandSoleChildChain(viewer, element, filter);
                }
                finally
                {
                    IN_AUTO_EXPAND.set(Boolean.FALSE);
                }
            }
        });
    }

    static void runSuppressed(Runnable action)
    {
        if (action == null)
            return;

        Boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        try
        {
            action.run();
        }
        finally
        {
            SUPPRESSED.set(previous);
        }
    }

    private static Tree resolveTree(AbstractTreeViewer viewer)
    {
        if (viewer instanceof TreeViewer treeViewer)
            return treeViewer.getTree();

        Object widget = Global.call(viewer, "getTree"); //$NON-NLS-1$
        return widget instanceof Tree ? (Tree) widget : null;
    }

    private static boolean defaultVisible(AbstractTreeViewer viewer, Object parent, Object child)
    {
        for (ViewerFilter vf : viewer.getFilters())
        {
            if (!vf.select(viewer, parent, child))
                return false;
        }
        return true;
    }

    private static void expandSoleChildChain(AbstractTreeViewer viewer, Object element, VisibleChildFilter filter)
    {
        if (viewer == null || element == null)
            return;

        Object cpObj = viewer.getContentProvider();
        if (!(cpObj instanceof ITreeContentProvider cp))
            return;

        Set<String> labelsInChain = new HashSet<>();
        rememberLabel(labelsInChain, nodeLabel(viewer, element));

        Object current = element;
        while (cp.hasChildren(current))
        {
            Object[] raw = cp.getChildren(current);
            if (raw == null || raw.length == 0)
                break;

            Object onlyChild = null;
            int visibleCount = 0;
            for (Object child : raw)
            {
                if (child == null)
                    continue;
                if (!filter.isVisible(viewer, current, child))
                    continue;
                visibleCount++;
                onlyChild = child;
                if (visibleCount > 1)
                    break;
            }
            if (visibleCount != 1 || onlyChild == null)
                break;

            if (isLabelCycle(viewer, current, onlyChild, labelsInChain))
                break;

            if (!cp.hasChildren(onlyChild))
                break;

            if (viewer.getExpandedState(onlyChild))
            {
                rememberLabel(labelsInChain, nodeLabel(viewer, onlyChild));
                current = onlyChild;
                continue;
            }

            viewer.setExpandedState(onlyChild, true);
            rememberLabel(labelsInChain, nodeLabel(viewer, onlyChild));
            current = onlyChild;
        }
    }

    /**
     * Цикл в модели часто выглядит как одинаковая подпись у родителя и единственного потомка
     * или повтор подписи в уже пройденной цепочке авторазворачивания.
     */
    private static boolean isLabelCycle(AbstractTreeViewer viewer, Object parent, Object child, Set<String> labelsInChain)
    {
        String parentLabel = nodeLabel(viewer, parent);
        String childLabel = nodeLabel(viewer, child);
        if (parentLabel.isEmpty() || childLabel.isEmpty())
            return false;
        if (parentLabel.equals(childLabel))
            return true;
        return labelsInChain.contains(childLabel);
    }

    private static void rememberLabel(Set<String> labelsInChain, String label)
    {
        if (label != null && !label.isEmpty())
            labelsInChain.add(label);
    }

    private static String nodeLabel(AbstractTreeViewer viewer, Object element)
    {
        if (element == null)
            return ""; //$NON-NLS-1$

        IBaseLabelProvider lp = viewer.getLabelProvider();
        if (lp == null)
            return ""; //$NON-NLS-1$

        if (lp instanceof DelegatingStyledCellLabelProvider dscp)
        {
            IStyledLabelProvider inner = dscp.getStyledStringProvider();
            if (inner != null)
                return normalizeLabel(styledStringText(inner.getStyledText(element)));
        }
        else if (lp instanceof IStyledLabelProvider styledProvider)
        {
            return normalizeLabel(styledStringText(styledProvider.getStyledText(element)));
        }

        if (lp instanceof ILabelProvider labelProvider)
            return normalizeLabel(labelProvider.getText(element));

        return ""; //$NON-NLS-1$
    }

    private static String styledStringText(StyledString styled)
    {
        return styled != null ? styled.getString() : ""; //$NON-NLS-1$
    }

    private static String normalizeLabel(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.trim();
    }
}
