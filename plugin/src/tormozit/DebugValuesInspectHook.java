package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;

/**
 * «Инспектировать» в контекстном меню панели «Значения» (таблица коллекции).
 */
public final class DebugValuesInspectHook implements IStartup
{
    private static final String VALUES_VIEW_ID = "com._1c.g5.v8.dt.debug.ui.values.ValuesView"; //$NON-NLS-1$
    private static final String HOOK_MARKER = "tormozit.debugValuesInspectMenuHooked"; //$NON-NLS-1$
    private static final String HOOK_MENU_KEY = "tormozit.debugValuesInspectMenu"; //$NON-NLS-1$
    private static final String ITEM_ADDED_KEY = "tormozit.debugValuesInspectAdded"; //$NON-NLS-1$
    private static final String HIDE_LISTENER_KEY = "tormozit.debugValuesInspectHideListener"; //$NON-NLS-1$
    private static final String VIEW_KEY = "tormozit.debugValuesView"; //$NON-NLS-1$
    private static final String CLICK_COLUMN_KEY = "tormozit.valuesClickColumn"; //$NON-NLS-1$
    private static final String CLICK_ITEM_KEY = "tormozit.valuesClickItem"; //$NON-NLS-1$
    private static final String PERF_HOOK_MARKER = "tormozit.debugValuesPerfHooked"; //$NON-NLS-1$
    private static final String COLUMN_WIDTHS_KEY = "tormozit.valuesColumnWidths"; //$NON-NLS-1$
    private static final String LAST_PERF_LOG_KEY = "tormozit.valuesLastPerfLog"; //$NON-NLS-1$

    private static final String ITEM_TEXT = "Инспектировать"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
        "Открыть элемент коллекции в инспекторе" //$NON-NLS-1$
            + Global.pluginSignForTooltip();

    private static final String INDEX_HEADER_RU = "Индекс"; //$NON-NLS-1$
    private static final String INDEX_HEADER_EN = "Index"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MenuDetect, DebugValuesInspectHook::handleMenuDetect);
        DebugValuesDebug.step("install", "MenuDetect filter"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Table table))
            return;

        long start = DebugValuesDebug.begin();
        AbstractDebugView view = valuesViewForTable(table);
        if (view == null || !isIndexedValuesTable(table))
        {
            DebugValuesDebug.step("MenuDetect", "skip no ValuesView " + DebugValuesDebug.tableBrief(table)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        table.setData(VIEW_KEY, view);
        ensurePerfListeners(table);

        Point loc = table.toControl(event.x, event.y);
        selectRowAt(table, loc);
        long columnStart = DebugValuesDebug.begin();
        int column = columnAt(table, loc.x, loc.y);
        DebugValuesDebug.perfSlow("columnAt", columnStart, DebugValuesDebug.tableBrief(table) //$NON-NLS-1$
            + " col=" + column); //$NON-NLS-1$

        if (column >= 0)
            table.setData(CLICK_COLUMN_KEY, Integer.valueOf(column));

        Menu menu = table.getMenu();
        if (menu == null || menu.isDisposed())
        {
            DebugValuesDebug.step("MenuDetect", "skip menu=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        ensureMenuListener(view, table, menu);
        scheduleMenuAugment(table, view);
        DebugValuesDebug.perfSlow("MenuDetect", start, DebugValuesDebug.tableBrief(table)); //$NON-NLS-1$
    }

    private static void ensureMenuListener(AbstractDebugView view, Table table, Menu menu)
    {
        Object hooked = table.getData(HOOK_MENU_KEY);
        if (hooked == menu && Boolean.TRUE.equals(menu.getData(HOOK_MARKER)))
            return;

        table.setData(HOOK_MENU_KEY, menu);
        menu.setData(HOOK_MARKER, Boolean.TRUE);
        menu.addMenuListener(buildMenuListener(view, table));
        DebugValuesDebug.step("MenuDetect", "MenuAdapter attached " + DebugValuesDebug.tableBrief(table)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void scheduleMenuAugment(Table table, AbstractDebugView view)
    {
        Display display = table.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            augmentContextMenu(table, view);
            if (table.isDisposed())
                return;
            Menu menu = table.getMenu();
            if (menu != null && !menu.isDisposed() && !hasInspectMenuItem(menu))
                display.asyncExec(() -> augmentContextMenu(table, view));
        });
    }

    private static void augmentContextMenu(Table table, AbstractDebugView view)
    {
        if (table == null || table.isDisposed() || view == null)
            return;

        long start = DebugValuesDebug.begin();
        Menu menu = table.getMenu();
        if (menu == null || menu.isDisposed())
        {
            DebugValuesDebug.step("menuAugment", "menu=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (isMenuAugmentFlagSet(menu) && hasInspectMenuItem(menu))
            return;

        if (!DebugSessionHelper.isDebugSuspended(null))
        {
            DebugValuesDebug.step("menuAugment", "skip not suspended"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        IBslVariable variable = resolveInspectableVariable(view, table);
        if (variable == null)
        {
            DebugValuesDebug.step("menuAugment", "skip no variable " + selectionBrief(view, table)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        MenuItem item = createInspectMenuItem(menu, view, table);
        if (item == null)
            return;

        markMenuAugmented(menu);
        DebugValuesDebug.perf("menuAugment", start, DebugValuesDebug.tableBrief(table) //$NON-NLS-1$
            + " " + variableBrief(variable)); //$NON-NLS-1$
    }

    private static boolean isMenuAugmentFlagSet(Menu menu)
    {
        return menu != null && !menu.isDisposed() && Boolean.TRUE.equals(menu.getData(ITEM_ADDED_KEY));
    }

    private static boolean hasInspectMenuItem(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return false;
        for (MenuItem item : menu.getItems())
        {
            if (item != null && !item.isDisposed() && ITEM_TEXT.equals(item.getText()))
                return true;
        }
        return false;
    }

    private static void markMenuAugmented(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return;
        menu.setData(ITEM_ADDED_KEY, Boolean.TRUE);
        if (Boolean.TRUE.equals(menu.getData(HIDE_LISTENER_KEY)))
            return;
        menu.setData(HIDE_LISTENER_KEY, Boolean.TRUE);
        menu.addListener(SWT.Hide, e -> clearMenuAugmentFlag(menu));
    }

    private static void clearMenuAugmentFlag(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return;
        menu.setData(ITEM_ADDED_KEY, null);
        DebugValuesDebug.step("menuHidden", "cleared flag"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static MenuItem createInspectMenuItem(Menu menu, AbstractDebugView view, Table table)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(ITEM_TEXT);
        item.setToolTipText(ITEM_TOOLTIP);
        Image inspectImage = BslInspectSupport.loadInspectCommandImage();
        if (inspectImage != null)
        {
            item.setImage(inspectImage);
            menu.addDisposeListener(ev -> {
                if (!inspectImage.isDisposed())
                    inspectImage.dispose();
            });
        }
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                runInspect(view, table);
            }
        });
        return item;
    }

    private static void selectRowAt(Table table, Point loc)
    {
        if (table == null || table.isDisposed() || loc == null)
            return;
        TableItem item = table.getItem(loc);
        if (item == null || item.isDisposed())
            return;
        table.setSelection(new TableItem[] { item });
        table.setFocus();
        table.setData(CLICK_ITEM_KEY, item);
        StructuredViewer viewer = structuredViewer(table.getData(VIEW_KEY));
        if (viewer != null)
        {
            Object element = elementForRow(viewer, table, item);
            if (element != null)
                viewer.setSelection(new StructuredSelection(element));
        }
    }

    private static MenuAdapter buildMenuListener(AbstractDebugView view, Table table)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(1);

            @Override
            public void menuShown(MenuEvent e)
            {
                long start = DebugValuesDebug.begin();
                if (!DebugSessionHelper.isDebugSuspended(null))
                {
                    DebugValuesDebug.step("menuShown", "skip not suspended"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                IBslVariable variable = resolveInspectableVariable(view, table);
                if (variable == null)
                {
                    DebugValuesDebug.step("menuShown", "skip no variable " + selectionBrief(view, table)); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                Menu menu = (Menu) e.widget;
                if (isMenuAugmentFlagSet(menu) && hasInspectMenuItem(menu))
                    return;

                MenuItem item = createInspectMenuItem(menu, view, table);
                addedItems.add(item);
                markMenuAugmented(menu);
                DebugValuesDebug.perf("menuShown", start, DebugValuesDebug.tableBrief(table) //$NON-NLS-1$
                    + " " + variableBrief(variable)); //$NON-NLS-1$
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                Menu menu = (Menu) e.widget;
                List<MenuItem> snapshot = new ArrayList<>(addedItems);
                addedItems.clear();
                clearMenuAugmentFlag(menu);
                menu.getDisplay().asyncExec(() ->
                {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };
    }

    private static void runInspect(AbstractDebugView view, Table table)
    {
        long start = DebugValuesDebug.begin();
        IBslVariable variable = resolveInspectableVariable(view, table);
        if (variable == null)
        {
            DebugValuesDebug.step("inspect", "skip no variable " + selectionBrief(view, table)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        int column = clickColumn(table);
        String columnHeader = columnHeader(table, column);

        String exprText = BslInspectSupport.resolveValuesInspectExpression(view, variable);
        IWatchExpression watch = BslInspectSupport.newWatchExpression(exprText);
        if (watch == null)
        {
            String toWatch = safeToWatchBrief(variable);
            DebugValuesDebug.step("inspect", "watch=null expr=" + DebugValuesDebug.quote(exprText) //$NON-NLS-1$ //$NON-NLS-2$
                + " toWatch=" + DebugValuesDebug.quote(toWatch) //$NON-NLS-1$
                + " " + variableBrief(variable)); //$NON-NLS-1$
            return;
        }

        IBslStackFrame frame = variable.getStackFrame();
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);
        if (frame == null)
        {
            DebugValuesDebug.step("inspect", "frame=null " + variableBrief(variable)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        IDebugMonitoringManager monitoringManager = Global.getOsgiService(IDebugMonitoringManager.class);
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        org.eclipse.swt.widgets.Shell parent = window != null ? window.getShell() : null;
        if (parent == null || parent.isDisposed())
            return;

        Point anchor = inspectAnchor(table);
        InspectorPendingFocus.set(columnHeader);

        long popupStart = DebugValuesDebug.begin();
        BslInspectSupport.openInspectPopup(parent, anchor, watch, frame, monitoringManager);
        DebugValuesDebug.perfSlow("inspect.popup", popupStart, DebugValuesDebug.tableBrief(table)); //$NON-NLS-1$

        String expr = BslInspectSupport.watchExpressionText(watch);
        DebugValuesDebug.perfSlow("inspect.run", start, DebugValuesDebug.tableBrief(table) //$NON-NLS-1$
            + " column=" + column //$NON-NLS-1$
            + " header=" + DebugValuesDebug.quote(columnHeader) //$NON-NLS-1$
            + " exprLen=" + expr.length() //$NON-NLS-1$
            + " " + variableBrief(variable)); //$NON-NLS-1$
    }

    private static String safeToWatchBrief(IBslVariable variable)
    {
        if (variable == null)
            return ""; //$NON-NLS-1$
        try
        {
            String expr = variable.toWatchExpression();
            return expr != null ? expr.trim() : ""; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "err:" + e.getMessage(); //$NON-NLS-1$
        }
    }

    private static Point inspectAnchor(Table table)
    {
        if (table == null || table.isDisposed())
            return new Point(100, 100);
        Rectangle bounds = table.getBounds();
        Point origin = table.toDisplay(0, 0);
        return new Point(origin.x + bounds.width / 2, origin.y + 20);
    }

    private static int clickColumn(Table table)
    {
        if (table == null || table.isDisposed())
            return -1;
        Object data = table.getData(CLICK_COLUMN_KEY);
        if (data instanceof Integer column)
            return column.intValue();
        return -1;
    }

    private static String columnHeader(Table table, int column)
    {
        if (table == null || table.isDisposed() || column < 0 || column >= table.getColumnCount())
            return ""; //$NON-NLS-1$
        TableColumn col = table.getColumn(column);
        if (col == null || col.isDisposed())
            return ""; //$NON-NLS-1$
        String text = col.getText();
        return text != null ? text.trim() : ""; //$NON-NLS-1$
    }

    private static ISelection selectionOf(AbstractDebugView view)
    {
        ISelectionProvider provider = view.getSite().getSelectionProvider();
        return provider != null ? provider.getSelection() : null;
    }

    private static AbstractDebugView valuesViewForTable(Table table)
    {
        AbstractDebugView active = activeValuesView(table);
        if (active != null)
            return active;
        return findValuesViewContaining(table);
    }

    private static AbstractDebugView activeValuesView(Table table)
    {
        if (table == null || table.isDisposed())
            return null;
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IWorkbenchPart part = page.getActivePart();
            if (!(part instanceof AbstractDebugView debugView))
                return null;
            if (!VALUES_VIEW_ID.equals(part.getSite().getId()))
                return null;
            return debugView;
        }
        catch (Exception e)
        {
            DebugValuesDebug.step("activeValuesView", "error " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static AbstractDebugView findValuesViewContaining(Table table)
    {
        if (table == null || table.isDisposed())
            return null;
        try
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (IWorkbenchPage page : window.getPages())
                {
                    IViewPart part = page.findView(VALUES_VIEW_ID);
                    if (!(part instanceof AbstractDebugView debugView))
                        continue;
                    StructuredViewer viewer = viewerOf(debugView);
                    if (viewer == null)
                        continue;
                    Control control = viewer.getControl();
                    if (control == table || isDescendantOf(table, control))
                        return debugView;
                }
            }
        }
        catch (Exception e)
        {
            DebugValuesDebug.step("findValuesView", "error " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static boolean isDescendantOf(Control child, Control ancestor)
    {
        if (child == null || ancestor == null || child.isDisposed() || ancestor.isDisposed())
            return false;
        Control current = child;
        while (current != null && !current.isDisposed())
        {
            if (current == ancestor)
                return true;
            current = current.getParent();
        }
        return false;
    }

    private static StructuredViewer viewerOf(AbstractDebugView view)
    {
        if (view == null)
            return null;
        try
        {
            Object viewer = view.getViewer();
            if (viewer instanceof StructuredViewer structured)
                return structured;
        }
        catch (Exception e)
        {
            DebugValuesDebug.step("viewerOf", "error " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static StructuredViewer structuredViewer(Object viewData)
    {
        if (viewData instanceof AbstractDebugView debugView)
            return viewerOf(debugView);
        return null;
    }

    private static Object elementForRow(StructuredViewer viewer, Table table, TableItem item)
    {
        if (viewer == null || table == null || table.isDisposed() || item == null || item.isDisposed())
            return null;
        int index = table.indexOf(item);
        if (index < 0)
            return null;
        if (viewer instanceof TableViewer tableViewer)
            return tableViewer.getElementAt(index);
        return null;
    }

    private static IBslVariable resolveInspectableVariable(AbstractDebugView view, Table table)
    {
        IBslVariable fromSelection = variableFromSelection(selectionOf(view));
        if (fromSelection != null)
            return fromSelection;

        TableItem row = clickItem(table);
        if (row == null)
            return null;

        IBslVariable fromData = asBslVariable(row.getData());
        if (fromData != null)
            return fromData;

        StructuredViewer viewer = viewerOf(view);
        Object element = elementForRow(viewer, table, row);
        return asBslVariable(element);
    }

    private static TableItem clickItem(Table table)
    {
        if (table == null || table.isDisposed())
            return null;
        Object data = table.getData(CLICK_ITEM_KEY);
        if (data instanceof TableItem item && !item.isDisposed())
            return item;
        TableItem[] selection = table.getSelection();
        if (selection != null && selection.length == 1 && selection[0] != null && !selection[0].isDisposed())
            return selection[0];
        return null;
    }

    private static IBslVariable variableFromSelection(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
            return null;
        return asBslVariable(structured.getFirstElement());
    }

    private static IBslVariable asBslVariable(Object element)
    {
        if (element instanceof IBslVariable variable)
            return variable;
        return null;
    }

    private static String selectionBrief(AbstractDebugView view, Table table)
    {
        StringBuilder sb = new StringBuilder();
        ISelection selection = selectionOf(view);
        if (selection instanceof IStructuredSelection structured)
        {
            sb.append("selSize=").append(structured.size()); //$NON-NLS-1$
            if (structured.size() == 1)
            {
                Object element = structured.getFirstElement();
                sb.append(" class=").append(element != null ? element.getClass().getSimpleName() : "null"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else
        {
            sb.append("sel=").append(selection == null ? "null" : selection.getClass().getSimpleName()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (table != null && !table.isDisposed())
            sb.append(" tableSel=").append(table.getSelection().length); //$NON-NLS-1$
        return sb.toString();
    }

    private static String variableBrief(IBslVariable variable)
    {
        if (variable == null)
            return "var=null"; //$NON-NLS-1$
        try
        {
            return "var=" + DebugValuesDebug.quote(variable.getName()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return "var=?"; //$NON-NLS-1$
        }
    }

    private static boolean isIndexedValuesTable(Table table)
    {
        if (table == null || table.isDisposed() || table.getColumnCount() < 2)
            return false;
        String header = columnHeader(table, 0);
        return INDEX_HEADER_RU.equalsIgnoreCase(header) || INDEX_HEADER_EN.equalsIgnoreCase(header);
    }

    private static int columnAt(Table table, int x, int y)
    {
        if (table == null || table.isDisposed())
            return -1;
        TableItem item = table.getItem(new Point(x, y));
        if (item == null)
            return -1;

        int[] widths = columnWidths(table);
        if (widths.length == 0)
            return 0;

        int colX = 0;
        for (int i = 0; i < widths.length; i++)
        {
            int width = widths[i];
            if (width <= 0)
                continue;
            if (x >= colX && x < colX + width)
                return i;
            colX += width;
        }
        return widths.length - 1;
    }

    private static int[] columnWidths(Table table)
    {
        Object cached = table.getData(COLUMN_WIDTHS_KEY);
        if (cached instanceof int[] widths && widths.length == table.getColumnCount())
            return widths;

        int count = table.getColumnCount();
        int[] widths = new int[count];
        for (int i = 0; i < count; i++)
        {
            TableColumn column = table.getColumn(i);
            widths[i] = column != null && !column.isDisposed() ? column.getWidth() : 0;
        }
        table.setData(COLUMN_WIDTHS_KEY, widths);
        return widths;
    }

    private static void invalidateColumnWidths(Table table)
    {
        if (table != null && !table.isDisposed())
            table.setData(COLUMN_WIDTHS_KEY, null);
    }

    private static void ensurePerfListeners(Table table)
    {
        if (table == null || table.isDisposed())
            return;
        if (Boolean.TRUE.equals(table.getData(PERF_HOOK_MARKER)))
            return;
        table.setData(PERF_HOOK_MARKER, Boolean.TRUE);

        table.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                invalidateColumnWidths(table);
            }
        });

        table.addListener(SWT.Selection, e ->
        {
            long start = DebugValuesDebug.begin();
            table.getDisplay().asyncExec(() ->
                DebugValuesDebug.perfSlow("Selection", start, DebugValuesDebug.tableBrief(table)));
        });

        addScrollBarPerfListener(table, table.getVerticalBar(), "ScrollV"); //$NON-NLS-1$
        addScrollBarPerfListener(table, table.getHorizontalBar(), "ScrollH"); //$NON-NLS-1$

        table.addListener(SWT.Paint, e ->
        {
            if (!shouldLogPerf(table, "Paint")) //$NON-NLS-1$
                return;
            long start = DebugValuesDebug.begin();
            table.getDisplay().asyncExec(() ->
                DebugValuesDebug.perfSlow("Paint", start, DebugValuesDebug.tableBrief(table)));
        });

        DebugValuesDebug.step("perf", "listeners attached " + DebugValuesDebug.tableBrief(table)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void addScrollBarPerfListener(Table table, ScrollBar bar, String kind)
    {
        if (table == null || table.isDisposed() || bar == null || bar.isDisposed())
            return;
        bar.addListener(SWT.Selection, e ->
        {
            if (!shouldLogPerf(table, kind))
                return;
            long start = DebugValuesDebug.begin();
            table.getDisplay().asyncExec(() ->
                DebugValuesDebug.perfSlow(kind, start, DebugValuesDebug.tableBrief(table)));
        });
    }

    private static boolean shouldLogPerf(Table table, String kind)
    {
        if (table == null || table.isDisposed())
            return false;
        String key = LAST_PERF_LOG_KEY + "." + kind; //$NON-NLS-1$
        long now = System.currentTimeMillis();
        Object prev = table.getData(key);
        if (prev instanceof Long last && now - last < 500L)
            return false;
        table.setData(key, Long.valueOf(now));
        return true;
    }
}
