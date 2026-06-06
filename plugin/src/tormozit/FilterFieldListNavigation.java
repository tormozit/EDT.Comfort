package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Навигация по списку/дереву стрелками и Page Up/Down из поля фильтра
 * (фокус и каретка остаются в {@link Text}, {@code e.doit = false}).
 */
public final class FilterFieldListNavigation
{
    private static final String INSTALLED_KEY = "tormozit.filterFieldNavInstalled"; //$NON-NLS-1$

    @FunctionalInterface
    public interface TableIndexListener
    {
        void onIndexChanged(int newIdx);
    }

    private FilterFieldListNavigation() {}

    public static boolean isNavigationKey(int keyCode)
    {
        return keyCode == SWT.ARROW_DOWN || keyCode == SWT.ARROW_UP
                || keyCode == SWT.PAGE_DOWN || keyCode == SWT.PAGE_UP;
    }

    public static void installTableNavigation(Text filterText, Table table)
    {
        installTableNavigation(filterText, table, null);
    }

    public static void installTableNavigation(Text filterText, Table table, TableIndexListener onIndexChanged)
    {
        installTableNavigation((Control) filterText, table, onIndexChanged);
    }

    private static void installTableNavigation(Control filterControl, Table table, TableIndexListener onIndexChanged)
    {
        if (filterControl == null || table == null)
            return;
        if (Boolean.TRUE.equals(filterControl.getData(INSTALLED_KEY)))
            return;
        filterControl.setData(INSTALLED_KEY, Boolean.TRUE);

        filterControl.addListener(SWT.KeyDown, event -> {
            if (!isNavigationKey(event.keyCode))
                return;
            int newIdx = navigateTable(table, event.keyCode);
            if (newIdx >= 0 && onIndexChanged != null)
                onIndexChanged.onIndexChanged(newIdx);
            event.doit = false;
            keepFilterFocus(filterControl);
        });
        filterControl.addListener(SWT.Traverse, event -> {
            if (!isNavigationKey(event.keyCode))
                return;
            event.doit = false;
            event.detail = SWT.TRAVERSE_NONE;
        });
    }

    private static void handleTableNavigation(Control filterControl, Table table,
            TableIndexListener onIndexChanged, Event event)
    {
        if (!isNavigationKey(event.keyCode))
            return;
        int newIdx = navigateTable(table, event.keyCode);
        if (newIdx >= 0 && onIndexChanged != null)
            onIndexChanged.onIndexChanged(newIdx);
        event.doit = false;
        if (event.type == SWT.Traverse)
            event.detail = SWT.TRAVERSE_NONE;
        keepFilterFocus(filterControl);
    }

    /**
     * @return новый индекс выделения или {@code -1}, если навигация не выполнена
     */
    public static int navigateTable(Table table, int keyCode)
    {
        if (table == null || table.isDisposed() || table.getItemCount() == 0)
            return -1;

        int idx = table.getSelectionIndex();
        int count = table.getItemCount();
        int itemH = table.getItemHeight();
        int page = itemH > 0 ? Math.max(1, table.getClientArea().height / itemH) : 10;

        int newIdx;
        switch (keyCode)
        {
            case SWT.ARROW_DOWN: newIdx = Math.min(idx + 1, count - 1);    break;
            case SWT.ARROW_UP:   newIdx = Math.max(idx - 1, 0);            break;
            case SWT.PAGE_DOWN:  newIdx = Math.min(idx + page, count - 1); break;
            case SWT.PAGE_UP:    newIdx = Math.max(idx - page, 0);         break;
            default: return -1;
        }

        table.setSelection(newIdx);
        table.showSelection();
        return newIdx;
    }

    public static void installTreeNavigation(Text filterText, Tree tree)
    {
        installTreeNavigation((Control) filterText, tree);
    }

    public static void installTreeNavigation(StyledText filterText, Tree tree)
    {
        installTreeNavigation((Control) filterText, tree);
    }

    public static void installTreeNavigation(Control filterControl, Tree tree)
    {
        if (filterControl == null || tree == null)
            return;
        if (Boolean.TRUE.equals(filterControl.getData(INSTALLED_KEY)))
            return;
        filterControl.setData(INSTALLED_KEY, Boolean.TRUE);

        filterControl.addListener(SWT.KeyDown, event -> {
            if (!isNavigationKey(event.keyCode))
                return;
            navigateTree(tree, event.keyCode);
            event.doit = false;
            keepFilterFocus(filterControl);
        });
        filterControl.addListener(SWT.Traverse, event -> {
            if (!isNavigationKey(event.keyCode))
                return;
            event.doit = false;
            event.detail = SWT.TRAVERSE_NONE;
        });
    }

    private static void handleTreeNavigation(Control filterControl, Tree tree, Event event)
    {
        if (!isNavigationKey(event.keyCode))
            return;
        navigateTree(tree, event.keyCode);
        event.doit = false;
        if (event.type == SWT.Traverse)
            event.detail = SWT.TRAVERSE_NONE;
        keepFilterFocus(filterControl);
    }

    /** После programmatic selection Tree/Table забирают фокус — возвращаем в поле фильтра. */
    private static void keepFilterFocus(Control filterControl)
    {
        if (filterControl == null || filterControl.isDisposed())
            return;
        Display display = filterControl.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (!filterControl.isDisposed())
                filterControl.forceFocus();
        });
    }

    public static void navigateTree(Tree tree, int keyCode)
    {
        if (tree == null || tree.isDisposed() || tree.getItemCount() == 0)
            return;

        TreeItem[] selection = tree.getSelection();
        TreeItem targetItem = null;

        int itemHeight = tree.getItemHeight();
        int pageSteps = itemHeight > 0 ? tree.getClientArea().height / itemHeight : 10;
        if (pageSteps <= 0)
            pageSteps = 10;

        if (selection.length == 0)
        {
            if (keyCode == SWT.ARROW_DOWN || keyCode == SWT.PAGE_DOWN)
                targetItem = tree.getItem(0);
            else
                targetItem = getLastVisibleItem(tree);
        }
        else
        {
            TreeItem current = selection[0];

            if (keyCode == SWT.ARROW_DOWN)
                targetItem = getNextVisibleItem(tree, current);
            else if (keyCode == SWT.ARROW_UP)
                targetItem = getPreviousVisibleItem(tree, current);
            else if (keyCode == SWT.PAGE_DOWN)
            {
                targetItem = current;
                for (int i = 0; i < pageSteps; i++)
                {
                    TreeItem next = getNextVisibleItem(tree, targetItem);
                    if (next == null)
                        break;
                    targetItem = next;
                }
            }
            else if (keyCode == SWT.PAGE_UP)
            {
                targetItem = current;
                for (int i = 0; i < pageSteps; i++)
                {
                    TreeItem prev = getPreviousVisibleItem(tree, targetItem);
                    if (prev == null)
                        break;
                    targetItem = prev;
                }
            }
        }

        if (targetItem != null && !targetItem.isDisposed())
        {
            tree.setSelection(targetItem);
            tree.showItem(targetItem);

            Event selectionEvent = new Event();
            selectionEvent.widget = tree;
            selectionEvent.item = targetItem;
            tree.notifyListeners(SWT.Selection, selectionEvent);
        }
    }

    private static TreeItem getNextVisibleItem(Tree tree, TreeItem item)
    {
        if (item.getExpanded() && item.getItemCount() > 0)
            return item.getItem(0);
        return getNextSiblingOrParentSibling(tree, item);
    }

    private static TreeItem getNextSiblingOrParentSibling(Tree tree, TreeItem item)
    {
        TreeItem parent = item.getParentItem();
        TreeItem[] siblings = parent == null ? tree.getItems() : parent.getItems();

        int index = indexOfItem(siblings, item);
        if (index >= 0 && index < siblings.length - 1)
            return siblings[index + 1];

        if (parent != null)
            return getNextSiblingOrParentSibling(tree, parent);
        return null;
    }

    private static TreeItem getPreviousVisibleItem(Tree tree, TreeItem item)
    {
        TreeItem parent = item.getParentItem();
        TreeItem[] siblings = parent == null ? tree.getItems() : parent.getItems();

        int index = indexOfItem(siblings, item);
        if (index > 0)
        {
            TreeItem prevSibling = siblings[index - 1];
            return getLastVisibleDescendant(prevSibling);
        }
        return parent;
    }

    private static TreeItem getLastVisibleDescendant(TreeItem item)
    {
        if (item.getExpanded() && item.getItemCount() > 0)
            return getLastVisibleDescendant(item.getItem(item.getItemCount() - 1));
        return item;
    }

    private static TreeItem getLastVisibleItem(Tree tree)
    {
        if (tree.getItemCount() == 0)
            return null;
        TreeItem lastRoot = tree.getItem(tree.getItemCount() - 1);
        return getLastVisibleDescendant(lastRoot);
    }

    private static int indexOfItem(TreeItem[] items, TreeItem item)
    {
        for (int i = 0; i < items.length; i++)
        {
            if (items[i] == item)
                return i;
        }
        return -1;
    }
}
