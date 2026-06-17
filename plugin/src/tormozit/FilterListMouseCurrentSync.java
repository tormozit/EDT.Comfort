package tormozit;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Синхронизация «текущей» строки списка с элементом под курсором мыши
 * (боковой хинт / selection), без смены фокуса.
 */
public final class FilterListMouseCurrentSync
{

    private static final String INSTALLED_KEY = "tormozit.filterListMouseCurrentSync"; //$NON-NLS-1$
    private static final String LISTENER_KEY = "tormozit.filterListMouseCurrentSyncListener"; //$NON-NLS-1$
    private static final String HOVER_ROW_Y_KEY = "tormozit.filterListMouseHoverRowY"; //$NON-NLS-1$
    private FilterListMouseCurrentSync() {}

    public static void installForTable(Table table, IntPredicate blocked, IntConsumer onIndexChanged)
    {

        if (table == null || table.isDisposed() || onIndexChanged == null)
            return;
        if (Boolean.TRUE.equals(table.getData(INSTALLED_KEY)))
            return;
        table.setData(INSTALLED_KEY, Boolean.TRUE);
        Listener listener = event -> handleTableMouseMove(table, blocked, onIndexChanged, event);
        table.setData(LISTENER_KEY, listener);
        table.addListener(SWT.MouseMove, listener);
        table.addDisposeListener(e -> uninstall(table));
    }

    public static void installForOutlineTree(TreeViewer viewer, BooleanSupplier suppress)
    {

        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(INSTALLED_KEY)))
            return;
        tree.setData(INSTALLED_KEY, Boolean.TRUE);
        Listener listener = event -> handleTreeMouseMove(viewer, tree, suppress, event);
        tree.setData(LISTENER_KEY, listener);
        tree.addListener(SWT.MouseMove, listener);
        tree.addDisposeListener(e -> uninstall(tree));
    }

    public static void uninstall(Control control)
    {

        if (control == null || control.isDisposed())
            return;
        Object stored = control.getData(LISTENER_KEY);
        if (stored instanceof Listener listener)
        {

            control.removeListener(SWT.MouseMove, listener);
            control.setData(LISTENER_KEY, null);
        }

        control.setData(INSTALLED_KEY, null);
        control.setData(HOVER_ROW_Y_KEY, null);
    }

    private static void handleTableMouseMove(Table table, IntPredicate blocked,
            IntConsumer onIndexChanged, Event event)
    {

        if (event.type != SWT.MouseMove || table.isDisposed())
            return;
        TableItem item = tableItemAt(table, event.x, event.y);
        if (item == null || item.isDisposed())
            return;
        int index = table.indexOf(item);
        if (index < 0 || index == table.getSelectionIndex())
            return;
        if (blocked != null && blocked.test(index))
            return;
        onIndexChanged.accept(index);
    }

    private static void handleTreeMouseMove(TreeViewer viewer, Tree tree,
            BooleanSupplier suppress, Event event)
    {

        if (event.type != SWT.MouseMove || tree.isDisposed())
            return;
        if (suppress != null && suppress.getAsBoolean())
            return;
        TreeItem item = treeItemAt(tree, event.x, event.y);
        if (item == null || item.isDisposed())
            return;
        Object element = item.getData();
        if (element == null)
            return;
        int rowY = treeItemRowY(item);
        if (rowY < 0)
            return;
        Integer lastRowY = (Integer) tree.getData(HOVER_ROW_Y_KEY);
        if (lastRowY != null && lastRowY == rowY)
            return;
        tree.setData(HOVER_ROW_Y_KEY, rowY);
        boolean jfaceMatches = viewer.getSelection() instanceof IStructuredSelection sel
            && !sel.isEmpty() && element.equals(sel.getFirstElement());
        if (!jfaceMatches)
            viewer.setSelection(new StructuredSelection(element), false);
        else
            refreshOutlineRowHoverHint(tree, element);
    }

    /** Selection уже совпадает (SWT hover), но строка сменилась — обновить боковую подсказку без repaint дерева. */
    @SuppressWarnings("unchecked")
    private static void refreshOutlineRowHoverHint(Tree tree, Object element)
    {

        Object hoverHint = tree.getData(BslSideHintOutlineInstall.ROW_HOVER_HINT_KEY);
        if (hoverHint instanceof Consumer<?> consumer)
            ((Consumer<Object>) consumer).accept(element);

    }

    /**
     * {@link Table#getItem(Point)} не попадает в пустую область строки справа от текста —
     * проверяем bounds ячеек (как hover ОС).
     */
    private static TableItem tableItemAt(Table table, int x, int y)
    {

        if (table == null || table.isDisposed() || x < 0 || y < 0)
            return null;
        TableItem hit = table.getItem(new Point(x, y));
        if (hit != null && !hit.isDisposed())
            return hit;
        int count = table.getItemCount();
        if (count <= 0)
            return null;
        int cols = table.getColumnCount();
        if (cols <= 0)
            cols = 1;
        for (int i = table.getTopIndex(); i < count; i++)
        {

            TableItem item = table.getItem(i);
            if (item == null || item.isDisposed())
                continue;
            Rectangle rowBounds = null;
            for (int c = 0; c < cols; c++)
            {

                Rectangle bounds = item.getBounds(c);
                if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
                    continue;
                if (bounds.contains(x, y))
                    return item;
                if (rowBounds == null)
                    rowBounds = new Rectangle(bounds.x, bounds.y, bounds.width, bounds.height);
                else
                    rowBounds = rowBounds.union(bounds);
            }

            if (rowBounds != null)
            {

                if (y < rowBounds.y)
                    break;
                if (rowBounds.contains(x, y))
                    return item;
                if (y > rowBounds.y + rowBounds.height)
                    continue;
            }

        }

        return null;
    }

    private static int treeItemRowY(TreeItem item)
    {

        if (item == null || item.isDisposed())
            return -1;
        Rectangle bounds = item.getBounds(0);
        return bounds != null && bounds.height > 0 ? bounds.y : -1;
    }

    /** Аналог {@link #tableItemAt} для дерева — эталон {@code DebugInspectorTreeEnhancement}. */
    private static TreeItem treeItemAt(Tree tree, int x, int y)
    {

        if (tree == null || tree.isDisposed() || x < 0 || y < 0)
            return null;
        // Y-полоса строки — надёжнее getItem(Point) на тексте (owner-draw / styled label).
        TreeItem byRow = treeItemAtByRowY(tree, x, y);
        if (byRow != null && !byRow.isDisposed())
            return byRow;
        for (TreeItem root : tree.getItems())
        {

            TreeItem found = findTreeItemInBounds(tree, root, x, y);
            if (found != null)
                return found;
        }

        return null;
    }

    /** Попадание в полосу строки по Y на всю ширину клиентской области. */
    private static TreeItem treeItemAtByRowY(Tree tree, int x, int y)
    {

        if (tree == null || tree.isDisposed() || x < 0 || y < 0)
            return null;
        int clientW = tree.getClientArea().width;
        if (x >= clientW)
            return null;
        for (TreeItem root : tree.getItems())
        {

            TreeItem found = findTreeItemByRowY(tree, root, x, y, clientW);
            if (found != null)
                return found;
        }

        return null;
    }

    private static TreeItem findTreeItemByRowY(Tree tree, TreeItem item, int x, int y, int clientW)
    {

        Rectangle bounds = item.getBounds(0);
        if (bounds != null && bounds.height > 0
            && x >= 0 && x < clientW
            && y >= bounds.y && y < bounds.y + bounds.height)
            return item;
        if (item.getExpanded())
        {

            for (TreeItem child : item.getItems())
            {

                TreeItem found = findTreeItemByRowY(tree, child, x, y, clientW);
                if (found != null)
                    return found;
            }

        }

        return null;
    }

    private static TreeItem findTreeItemInBounds(Tree tree, TreeItem item, int x, int y)
    {

        if (tree == null || item == null || item.isDisposed())
            return null;
        int clientW = tree.getClientArea().width;
        Rectangle bounds = item.getBounds(0);
        if (bounds != null && bounds.height > 0
            && x >= 0 && x < clientW
            && y >= bounds.y && y < bounds.y + bounds.height)
            return item;
        int cols = tree.getColumnCount();
        if (cols <= 0)
            cols = 1;
        Rectangle rowBounds = null;
        for (int c = 0; c < cols; c++)
        {

            Rectangle colBounds = item.getBounds(c);
            if (colBounds == null || colBounds.width <= 0 || colBounds.height <= 0)
                continue;
            if (colBounds.contains(x, y))
                return item;
            if (rowBounds == null)
                rowBounds = new Rectangle(colBounds.x, colBounds.y, colBounds.width, colBounds.height);
            else
                rowBounds = rowBounds.union(colBounds);
        }

        if (rowBounds != null && rowBounds.height > 0
            && x >= 0 && x < clientW
            && y >= rowBounds.y && y < rowBounds.y + rowBounds.height)
            return item;
        if (item.getExpanded())
        {

            for (TreeItem child : item.getItems())
            {

                TreeItem found = findTreeItemInBounds(tree, child, x, y);
                if (found != null)
                    return found;
            }

        }

        return null;
    }

}

