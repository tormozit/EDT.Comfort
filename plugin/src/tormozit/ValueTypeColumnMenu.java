package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.nebula.widgets.grid.Grid;
import org.eclipse.nebula.widgets.grid.GridItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

import com._1c.g5.v8.dt.common.ui.widgets.tableex.TableEx;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;

/** Общее меню навигации по типу ячейки SWT Table/Tree и многострочных таблиц СКД. */
final class ValueTypeColumnMenu
{
    private static final String KEY = "tormozit.valueTypeColumnMenu"; //$NON-NLS-1$
    private static final String ROOT_MENU_KEY = KEY + ".root"; //$NON-NLS-1$
    private static final String ROOT_POSITION_KEY = KEY + ".rootPosition"; //$NON-NLS-1$
    private final Control host;
    private final Control control;
    private final Map<Widget, TypeColumn> columns = new HashMap<>();
    private Widget activeColumn;
    private Cell detectedCell;

    private record Cell(Widget column, Object element) {}
    private record TypeColumn(Function<Object, TypeDescription> typeOf, Function<Object, EObject> contextOf) {}
    private record RootPosition(int index, MenuItem next) {}

    static void useRootMenu(Control control)
    {
        control.setData(ROOT_MENU_KEY, Boolean.TRUE);
    }

    /** Запоминаем соседний пункт: другие наполнители могут ещё вставить свои команды. */
    static void rememberRootPosition(Menu menu, MenuItem replaced)
    {
        int index = menu.indexOf(replaced);
        MenuItem next = null;
        for (int i = index + 1; i < menu.getItemCount(); i++)
        {
            MenuItem candidate = menu.getItem(i);
            // Наши пункты с прошлого показа будут удалены перед повторным наполнением.
            if (!GotoDefinitionFieldMenuHook.isReferenceMenuItem(candidate))
            {
                next = candidate;
                break;
            }
        }
        menu.setData(ROOT_POSITION_KEY, new RootPosition(index, next));
    }

    static int rootInsertIndex(Menu menu)
    {
        if (!(menu.getData(ROOT_POSITION_KEY) instanceof RootPosition position))
            return -1;
        if (position.next() != null)
            return position.next().isDisposed() ? -1 : menu.indexOf(position.next());
        return Math.min(position.index(), menu.getItemCount());
    }

    private ValueTypeColumnMenu(Control host)
    {
        this.host = host;
        control = host instanceof TableEx table ? table.getDataControl() : host;
        control.addListener(SWT.MouseDown, event ->
        {
            Cell cell = cellAt(new Point(event.x, event.y));
            activeColumn = cell != null ? cell.column() : null;
        });
        control.addListener(SWT.MenuDetect, this::menuDetected);
    }

    static void install(Control host, int columnIndex, Function<Object, TypeDescription> typeOf,
        Function<Object, EObject> contextOf)
    {
        if (host == null || host.isDisposed()
            || !(host instanceof Tree || host instanceof Table || host instanceof TableEx))
            return;
        ValueTypeColumnMenu support;
        if (host.getData(KEY) instanceof ValueTypeColumnMenu installed)
            support = installed;
        else
        {
            support = new ValueTypeColumnMenu(host);
            host.setData(KEY, support);
        }
        Widget column = host instanceof Tree tree ? tree.getColumn(columnIndex)
            : host instanceof Table table ? table.getColumn(columnIndex)
                : ((TableEx)host).getColumn(columnIndex);
        support.columns.put(column, new TypeColumn(typeOf, contextOf));
    }

    private void menuDetected(Event event)
    {
        detectedCell = event.detail == SWT.MENU_KEYBOARD ? selectedCell()
            : cellAt(control.toControl(event.x, event.y));
        Menu menu = control.getMenu();
        if (menu == null || menu.isDisposed())
        {
            menu = new Menu(control);
            control.setMenu(menu);
        }
        if (menu.getData(KEY) == this)
            return;
        menu.setData(KEY, this);
        // После штатного MenuManager с removeAllWhenShown.
        menu.addListener(SWT.Show, eventShown ->
        {
            Cell cell = host instanceof TableEx ? selectedCell() : detectedCell;
            TypeColumn column = cell != null ? columns.get(cell.column()) : null;
            TypeDescription type = column != null ? column.typeOf().apply(cell.element()) : null;
            EObject context = column != null ? column.contextOf().apply(cell.element()) : null;
            String text = referenceText(type, context);
            Runnable openTypes = type != null && !type.getTypes().isEmpty()
                ? () -> SelectTypeDialogHook.openReadOnly(control.getShell(), type, context) : null;
            GotoDefinitionFieldMenuHook.updateTypeReferenceMenu((Menu)eventShown.widget, control, text,
                Boolean.TRUE.equals(host.getData(ROOT_MENU_KEY)), openTypes);
        });
    }

    private Cell selectedCell()
    {
        if (host instanceof TableEx table)
        {
            Grid grid = table.getDataControl();
            GridItem item = grid.getFocusItem();
            Point point = grid.getFocusCell();
            if (item == null || item.isDisposed() || point == null || point.x < 0)
                return null;
            Item row = table.getItemByGridItem(item);
            return row != null ? new Cell(table.getColumnByGridColumn(item, point.x), row.getData()) : null;
        }
        if (host instanceof Tree tree)
        {
            TreeItem[] selection = tree.getSelection();
            FormTreeInteraction interaction = FormTreeInteraction.of(tree);
            Widget column = activeColumn;
            if (interaction != null && interaction.activeColumn() >= 0
                && interaction.activeColumn() < tree.getColumnCount())
                column = tree.getColumn(interaction.activeColumn());
            return selection.length > 0 ? new Cell(column, selection[0].getData()) : null;
        }
        TableItem[] selection = ((Table)host).getSelection();
        return selection.length > 0 ? new Cell(activeColumn, selection[0].getData()) : null;
    }

    private Cell cellAt(Point point)
    {
        if (host instanceof TableEx)
            return selectedCell();
        if (host instanceof Tree tree)
        {
            TreeItem item = FormTreeInteraction.rowAt(tree, point.x, point.y);
            int column = FormTreeInteraction.columnAtX(tree, point.x);
            return item != null && column >= 0 ? new Cell(tree.getColumn(column), item.getData()) : null;
        }
        Table table = (Table)host;
        int limit = table.getClientArea().height / Math.max(1, table.getItemHeight()) + 2;
        for (int i = table.getTopIndex(); i < table.getItemCount() && limit-- > 0; i++)
        {
            TableItem item = table.getItem(i);
            for (int column = 0; column < table.getColumnCount(); column++)
            {
                Rectangle bounds = item.getBounds(column);
                if (bounds.contains(point))
                    return new Cell(table.getColumn(column), item.getData());
            }
        }
        return null;
    }

    /** Полные имена из модели, независимо от сокращённого текста ячейки. */
    static String referenceText(TypeDescription type, EObject context)
    {
        if (type == null || type.getTypes().isEmpty())
            return null;
        List<String> names = new ArrayList<>();
        for (TypeItem item : type.getTypes())
        {
            if (item.eIsProxy() && context != null && EcoreUtil.resolve(item, context) instanceof TypeItem resolved)
                item = resolved;
            String name = McoreUtil.getTypeNameRu(item);
            if (name == null || name.isBlank())
                name = McoreUtil.getTypeName(item);
            if (name != null && !name.isBlank())
                names.add(name);
        }
        return String.join(", ", names); //$NON-NLS-1$
    }
}
