package tormozit.edt.applications;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Хук панели «Приложения» EDT.
 *
 * <p>Добавляет:
 * <ul>
 *   <li>Колонку «Конфигуратор» — дата начала SSH-сеанса, статус.</li>
 *   <li>Клик по ячейке активного сеанса → отключение.</li>
 *   <li>Кнопку «Отключить конфигуратор» в тулбар.</li>
 *   <li>Кнопку «Диагностика пула» — пишет полный дамп в Error Log.</li>
 *   <li>Пункт «Отключить конфигуратор» в контекстное меню.</li>
 * </ul>
 */
public class ApplicationsViewHook implements IStartup
{
    private static final String APPLICATIONS_VIEW_CLASS =
        "com.e1c.g5.dt.internal.applications.ui.view.ApplicationsView"; //$NON-NLS-1$

    private static final String COL_CONFIG_TITLE   = "Конфигуратор";
    private static final String COL_CONFIG_TOOLTIP =
        "Дата начала SSH-сеанса конфигуратора. Нажмите ячейку для отключения.";
    private static final int    COL_DB_WIDTH     = 200;
    private static final int    COL_CONFIG_WIDTH = 165;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"); //$NON-NLS-1$

    // =======================================================================
    // IStartup
    // =======================================================================

    @Override
    public void earlyStartup()
    {
        DesignerSessionPoolAccessor.getInstance().startPolling();

        Display.getDefault().asyncExec(() ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();
            for (IWorkbenchWindow w : wb.getWorkbenchWindows())
                hookWindow(w);

            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
        });
    }

    // =======================================================================
    // Подключение к окну / панели
    // =======================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isApplicationsView(view)) hookView(view);
            }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref.getPart(false);
                if (isApplicationsView(part))
                    Display.getDefault().asyncExec(() -> hookView(part));
            }
            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static boolean isApplicationsView(Object part)
    {
        return part != null &&
               APPLICATIONS_VIEW_CLASS.equals(part.getClass().getName());
    }

    // =======================================================================
    // Хук панели
    // =======================================================================

    private void hookView(Object part)
    {
        if (!(part instanceof IViewPart)) return;
        IViewPart view = (IViewPart) part;

        ColumnViewer viewer = findViewer(view);
        if (viewer == null) return;

        Control control = viewer.getControl();
        if (control == null || control.isDisposed()) return;

        if (control instanceof Tree)
        {
            Tree tree = (Tree) control;
            setupMultiColumnTree(viewer, tree);
            addClickableColumn(viewer, tree);
        }

        addToolbarButtons(view, viewer);
        addContextMenuItem(viewer, control);
        registerRedrawOnPoolChange(viewer);
    }

    // =======================================================================
    // 1. Многоколоночное дерево
    // =======================================================================

    private void setupMultiColumnTree(ColumnViewer viewer, Tree tree)
    {
        if (tree.getColumnCount() > 0 || tree.getHeaderVisible()) return;

        final IBaseLabelProvider origProvider = viewer.getLabelProvider();

        // Колонка 0 — «Инфобаза»
        TreeViewerColumn col0 = new TreeViewerColumn((TreeViewer) viewer, SWT.NONE);
        col0.getColumn().setText("Инфобаза");
        col0.getColumn().setWidth(COL_DB_WIDTH);
        col0.getColumn().setResizable(true);
        col0.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                if (origProvider instanceof CellLabelProvider)
                    ((CellLabelProvider) origProvider).update(cell);
                else if (origProvider instanceof ILabelProvider)
                {
                    ILabelProvider lp = (ILabelProvider) origProvider;
                    cell.setText(lp.getText(cell.getElement()));
                    cell.setImage(lp.getImage(cell.getElement()));
                }
            }
        });

        // Колонка 1 — «Конфигуратор»
        TreeViewerColumn col1 = new TreeViewerColumn((TreeViewer) viewer, SWT.NONE);
        col1.getColumn().setText(COL_CONFIG_TITLE);
        col1.getColumn().setToolTipText(COL_CONFIG_TOOLTIP);
        col1.getColumn().setWidth(COL_CONFIG_WIDTH);
        col1.getColumn().setResizable(true);
        col1.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                Object element = cell.getElement();
                DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                LocalDateTime start = acc.getSessionStart(acc.findPoolKey(element));
                if (start != null)
                {
                    cell.setText("\u25CF " + DATE_FMT.format(start)); // ● дата //$NON-NLS-1$
                    cell.setForeground(
                        cell.getControl().getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
                }
                else
                {
                    cell.setText("\u2014"); // — //$NON-NLS-1$
                    cell.setForeground(
                        cell.getControl().getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
                }
                cell.setBackground(null);
            }

            @Override
            public String getToolTipText(Object element)
            {
                return DesignerSessionPoolAccessor.getInstance().isConnected(element)
                    ? "Нажмите для отключения конфигуратора"
                    : "Конфигуратор не подключён";
            }
        });

        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
    }

    // =======================================================================
    // 2. Кликабельная колонка
    // =======================================================================

    private void addClickableColumn(ColumnViewer viewer, Tree tree)
    {
        // Смена курсора при наведении
        tree.addMouseMoveListener(new MouseMoveListener()
        {
            @Override
            public void mouseMove(MouseEvent e)
            {
                boolean hand = columnAt(tree, e.x, e.y) == 1 && connectedAt(tree, e.x, e.y);
                tree.setCursor(hand ? tree.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
            }
        });

        // Клик ЛКМ
        tree.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseDown(MouseEvent e)
            {
                if (e.button != 1) return;

                int col = columnAt(tree, e.x, e.y);
                if (col != 1) return;
                TreeItem item = itemAt(tree, e.x, e.y);
                if (item == null)
                {
                    DesignerSessionPoolAccessor.log("col1 click: TreeItem not found"); //$NON-NLS-1$
                    return;
                }

                Object element = item.getData();
                DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                Object poolKey = acc.findPoolKey(element);
                if (poolKey == null) return;
                acc.release(poolKey);
                if (!viewer.getControl().isDisposed())
                    viewer.update(element, null);
            }
        });
    }

    // =======================================================================
    // 3. Кнопки тулбара
    // =======================================================================

    private void addToolbarButtons(IViewPart view, ColumnViewer viewer)
    {
        IActionBars bars = view.getViewSite().getActionBars();
        IToolBarManager tb = bars.getToolBarManager();

        // «Отключить конфигуратор»
        Action disconnect = new Action("Отключить конфигуратор")
        {
            @Override
            public void run() { disconnectSelected(viewer); }
        };
        disconnect.setToolTipText("Отключить SSH-сеансы конфигуратора выбранных инфобаз");
        tb.add(new Separator());
        tb.add(disconnect);
        bars.updateActionBars();
    }

    // =======================================================================
    // 4. Контекстное меню
    // =======================================================================

    private void addContextMenuItem(ColumnViewer viewer, Control control)
    {
        Menu menu = control.getMenu();
        if (menu == null) { menu = new Menu(control); control.setMenu(menu); }
        final Menu finalMenu = menu;

        MenuAdapter adapter = new MenuAdapter()
        {
            private final List<MenuItem> added = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
                if (sel.isEmpty()) return;
                DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                if (sel.toList().stream().noneMatch(acc::isConnected)) return;

                added.add(new MenuItem(finalMenu, SWT.SEPARATOR));
                MenuItem item = new MenuItem(finalMenu, SWT.PUSH);
                item.setText("Отключить конфигуратор");
                final IStructuredSelection snap = sel;
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        disconnectItems(snap.toList(), viewer);
                    }
                });
                added.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> toDispose = new ArrayList<>(added);
                added.clear();
                ((Menu)e.widget).getDisplay().asyncExec(() ->
                    toDispose.forEach(mi -> { if (!mi.isDisposed()) mi.dispose(); }));
            }
        };

        menu.addMenuListener(adapter);
        control.addDisposeListener(
            ev -> { if (!finalMenu.isDisposed()) finalMenu.removeMenuListener(adapter); });
    }

    // =======================================================================
    // 5. Автообновление при смене состояния пула
    // =======================================================================

    private void registerRedrawOnPoolChange(ColumnViewer viewer)
    {
        DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
        Runnable listener = () ->
            Display.getDefault().asyncExec(() ->
            {
                Control c = viewer.getControl();
                if (c != null && !c.isDisposed()) viewer.refresh();
            });
        acc.addChangeListener(listener);
        viewer.getControl().addDisposeListener(e -> acc.removeChangeListener(listener));
    }

    // =======================================================================
    // Отключение
    // =======================================================================

    private void disconnectSelected(ColumnViewer viewer)
    {
        IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
        if (!sel.isEmpty()) disconnectItems(sel.toList(), viewer);
    }

    private void disconnectItems(List<?> elements, ColumnViewer viewer)
    {
        DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
        boolean any = false;
        for (Object el : elements)
        {
            Object key = acc.findPoolKey(el);
            if (key != null) { acc.release(key); any = true; }
        }
        if (any)
            Display.getDefault().asyncExec(() ->
            {
                if (!viewer.getControl().isDisposed()) viewer.refresh();
            });
    }

    // =======================================================================
    // Вспомогательные методы — дерево
    // =======================================================================

    private static int columnAt(Tree tree, int x, int y)
    {
        TreeItem item = itemAt(tree, x, y);
        if (item == null) return -1;
        for (int i = 0; i < tree.getColumnCount(); i++)
            if (item.getBounds(i).contains(x, y)) return i;
        return -1;
    }

    private static boolean connectedAt(Tree tree, int x, int y)
    {
        TreeItem item = itemAt(tree, x, y);
        return item != null &&
               DesignerSessionPoolAccessor.getInstance().isConnected(item.getData());
    }

    private static TreeItem itemAt(Tree tree, int x, int y)
    {
        TreeItem item = tree.getItem(new Point(x, y));
        if (item != null) return item;
        for (TreeItem ti : tree.getItems())
        {
            TreeItem found = findInItem(ti, x, y);
            if (found != null) return found;
        }
        return null;
    }

    private static TreeItem findInItem(TreeItem ti, int x, int y)
    {
        for (int i = 0; i < ti.getParent().getColumnCount(); i++)
            if (ti.getBounds(i).contains(x, y)) return ti;
        if (ti.getExpanded())
            for (TreeItem child : ti.getItems())
            {
                TreeItem found = findInItem(child, x, y);
                if (found != null) return found;
            }
        return null;
    }

    // =======================================================================
    // Вспомогательные методы — viewer
    // =======================================================================

    private static ColumnViewer findViewer(Object view)
    {
        Class<?> cls = view.getClass();
        while (cls != null)
        {
            for (Field f : cls.getDeclaredFields())
            {
                if (!ColumnViewer.class.isAssignableFrom(f.getType())) continue;
                try
                {
                    f.setAccessible(true);
                    Object val = f.get(view);
                    if (val instanceof ColumnViewer) return (ColumnViewer) val;
                }
                catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    static String extractKey(Object item)
    {
        return DesignerSessionPoolAccessor.nameOf(item);
    }
}
