package tormozit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * «Свернуть все другие»: оставляет развёрнутой только цепочку родителей текущего
 * выделения; все прочие ветки сворачиваются (включая сам выделенный узел, если он
 * был развёрнут). Общая логика для навигатора и будущих деревьев.
 */
final class TreeCollapseOthers
{
    private TreeCollapseOthers() {}

    /** Есть непустое выделение в дереве. */
    static boolean isApplicable(Tree tree)
    {
        return tree != null && !tree.isDisposed() && tree.getSelectionCount() > 0;
    }

    /**
     * Сворачивает лишние ветки через {@link AbstractTreeViewer#setExpandedElements}:
     * синхронизирует состояние модели JFace (предпочтительно для CommonViewer и т.п.).
     */
    static void collapseOthers(AbstractTreeViewer viewer)
    {
        if (viewer == null)
            return;
        Control control = viewer.getControl();
        if (!(control instanceof Tree tree) || tree.isDisposed())
            return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;

        Set<Object> keep = new LinkedHashSet<>();
        for (TreeItem item : selection)
        {
            if (item == null || item.isDisposed())
                continue;
            for (TreeItem parent = item.getParentItem(); parent != null; parent = parent.getParentItem())
            {
                Object data = parent.getData();
                if (data != null)
                    keep.add(data);
            }
        }

        tree.setRedraw(false);
        try
        {
            viewer.setExpandedElements(keep.toArray());
            if (selection.length > 0 && !selection[0].isDisposed())
                tree.showItem(selection[0]);
        }
        finally
        {
            if (!tree.isDisposed())
                tree.setRedraw(true);
        }
    }

    /**
     * То же для «голого» SWT-{@link Tree} без JFace-viewer (будущие места без
     * {@link AbstractTreeViewer}).
     */
    static void collapseOthers(Tree tree)
    {
        if (!isApplicable(tree))
            return;

        Map<TreeItem, Boolean> keep = new IdentityHashMap<>();
        for (TreeItem item : tree.getSelection())
        {
            if (item == null || item.isDisposed())
                continue;
            for (TreeItem parent = item.getParentItem(); parent != null; parent = parent.getParentItem())
                keep.put(parent, Boolean.TRUE);
        }

        List<TreeItem> toCollapse = new ArrayList<>();
        collectExpandedNotKept(tree.getItems(), keep, toCollapse);

        tree.setRedraw(false);
        try
        {
            for (TreeItem item : toCollapse)
            {
                if (!item.isDisposed() && item.getExpanded())
                    item.setExpanded(false);
            }
            TreeItem[] selection = tree.getSelection();
            if (selection.length > 0 && !selection[0].isDisposed())
                tree.showItem(selection[0]);
        }
        finally
        {
            if (!tree.isDisposed())
                tree.setRedraw(true);
        }
    }

    private static void collectExpandedNotKept(
        TreeItem[] items, Map<TreeItem, Boolean> keep, List<TreeItem> toCollapse)
    {
        if (items == null)
            return;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            // Сначала дети — пока узел ещё развёрнут и дочерние TreeItem доступны.
            if (item.getExpanded())
                collectExpandedNotKept(item.getItems(), keep, toCollapse);
            if (item.getExpanded() && !keep.containsKey(item))
                toCollapse.add(item);
        }
    }
}
