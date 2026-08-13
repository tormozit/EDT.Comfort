package tormozit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;

/**
 * «Свернуть все другие»: оставляет развёрнутой только цепочку родителей текущего
 * выделения; все прочие ветки сворачиваются (включая сам выделенный узел, если он
 * был развёрнут). Подключается автоматически к подменю «Комфорт» на деревьях
 * ({@link ComfortSubmenuHelper#findOrCreateComfortSubmenu}) и вручную там, где
 * подменю собирается иначе ({@link #addPushItem}).
 */
final class TreeCollapseOthers
{
    static final String ITEM_TEXT = "Свернуть все другие"; //$NON-NLS-1$
    static final String ITEM_TOOLTIP =
            "Свернуть все ветки дерева, кроме родителей текущего выделения" //$NON-NLS-1$
            + Global.pluginSignForTooltip();

    private static final String COMFORT_HOOK_MARKER = "tormozit.treeCollapseOthers.comfort"; //$NON-NLS-1$

    private TreeCollapseOthers() {}

    /** Есть непустое выделение в дереве. */
    static boolean isApplicable(Tree tree)
    {
        return tree != null && !tree.isDisposed() && tree.getSelectionCount() > 0;
    }

    /**
     * Текст, тултип и иконка пункта (штатная {@link ISharedImages#IMG_ELCL_COLLAPSEALL},
     * dispose не нужен).
     */
    static void decorateMenuItem(MenuItem item)
    {
        if (item == null || item.isDisposed())
            return;
        item.setText(ITEM_TEXT);
        item.setToolTipText(ITEM_TOOLTIP);
        Image image = itemImage();
        if (image != null)
            item.setImage(image);
    }

    /** Иконка «Свернуть всё» из shared images workbench; не dispose. */
    static Image itemImage()
    {
        try
        {
            return PlatformUI.getWorkbench().getSharedImages()
                .getImage(ISharedImages.IMG_ELCL_COLLAPSEALL);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * Один раз вешает на подменю «Комфорт» пункт {@link #ITEM_TEXT}, если владелец
     * контекстного меню — {@link Tree}. Для текстовых полей и таблиц — no-op.
     */
    static void ensureComfortMenuItem(Menu comfortSub)
    {
        if (comfortSub == null || comfortSub.isDisposed())
            return;
        if (Boolean.TRUE.equals(comfortSub.getData(COMFORT_HOOK_MARKER)))
            return;
        comfortSub.setData(COMFORT_HOOK_MARKER, Boolean.TRUE);

        MenuAdapter listener = new MenuAdapter()
        {
            private final List<MenuItem> added = new ArrayList<>(1);

            @Override
            public void menuShown(MenuEvent e)
            {
                Tree tree = resolveOwnerTree(comfortSub);
                if (!isApplicable(tree))
                    return;

                MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(
                    comfortSub, SWT.PUSH, ITEM_TEXT);
                decorateMenuItem(item);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        Tree current = resolveOwnerTree(comfortSub);
                        if (isApplicable(current))
                            collapseOthers(current);
                    }
                });
                added.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(added);
                added.clear();
                comfortSub.getDisplay().asyncExec(() -> {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };
        comfortSub.addMenuListener(listener);
        comfortSub.addDisposeListener(ev -> {
            if (!comfortSub.isDisposed())
                comfortSub.removeMenuListener(listener);
        });
    }

    /**
     * Сразу добавляет пункт в уже собираемое меню (тулбар/контекст «Приложения» и т.п.).
     * Без сортировки — вызывающий сам выбирает место в меню.
     */
    static void addPushItem(Menu menu, Tree tree)
    {
        if (menu == null || menu.isDisposed() || !isApplicable(tree))
            return;
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        decorateMenuItem(item);
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                if (isApplicable(tree))
                    collapseOthers(tree);
            }
        });
    }

    /** Дерево-владелец контекстного меню, из которого открыто подменю {@code comfortSub}. */
    static Tree resolveOwnerTree(Menu comfortSub)
    {
        if (comfortSub == null || comfortSub.isDisposed())
            return null;
        MenuItem parentItem = comfortSub.getParentItem();
        if (parentItem == null || parentItem.isDisposed())
            return null;
        Menu contextMenu = parentItem.getParent();
        if (contextMenu == null || contextMenu.isDisposed())
            return null;
        Control owner = contextMenu.getParent();
        return owner instanceof Tree tree && !tree.isDisposed() ? tree : null;
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
     * То же для SWT-{@link Tree} (в т.ч. когда JFace-viewer недоступен). События
     * Expand/Collapse синхронизируют состояние TreeViewer, если он есть.
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
