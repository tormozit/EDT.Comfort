package tormozit.edt.applications;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.ContributionItem;
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
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
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
 *   <li>Колонку «Конфигуратор SSH» — дата SSH-сеанса, клик для отключения.</li>
 *   <li>Колонку «Приложение ИР»    — дата сеанса ИР, клик для отключения.</li>
 *   <li>Dropdown-кнопку «Подключение» в тулбар с подменю из трёх пунктов.</li>
 *   <li>CASCADE-подменю «Подключение» в контекстное меню.</li>
 * </ul>
 */
public class ApplicationsViewHook implements IStartup
{
    private static final String APPLICATIONS_VIEW_CLASS =
        "com.e1c.g5.dt.internal.applications.ui.view.ApplicationsView"; //$NON-NLS-1$

    // Индексы колонок
    private static final int COL_DB     = 0;
    private static final int COL_SSH    = 1;
    private static final int COL_IR     = 2;

    private static final int    COL_DB_WIDTH  = 200;
    private static final int    COL_SSH_WIDTH = 165;
    private static final int    COL_IR_WIDTH  = 165;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM HH:mm:ss"); //$NON-NLS-1$

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
            addClickableColumns(viewer, tree);
        }

        addToolbarButtons(view, viewer);
        addContextMenu(viewer, control);
        registerRedrawOnPoolChange(viewer);
    }

    // =======================================================================
    // 1. Колонки дерева
    // =======================================================================

    private void setupMultiColumnTree(ColumnViewer viewer, Tree tree)
    {
        if (tree.getColumnCount() > 0 || tree.getHeaderVisible()) return;

        final IBaseLabelProvider origProvider = viewer.getLabelProvider();

        // ---- Колонка 0: «Инфобаза» ----
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

        // ---- Колонка 1: «Конфигуратор SSH» ----
        TreeViewerColumn col1 = new TreeViewerColumn((TreeViewer) viewer, SWT.NONE);
        col1.getColumn().setText("Конфигуратор SSH");
        col1.getColumn().setToolTipText(
            "Дата начала SSH-сеанса конфигуратора. Нажмите ячейку для отключения.");
        col1.getColumn().setWidth(COL_SSH_WIDTH);
        col1.getColumn().setResizable(true);
        col1.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                Object el = cell.getElement();
                DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                LocalDateTime start = acc.getSessionStart(acc.findPoolKey(el));
                renderSessionCell(cell, start);
            }

            @Override
            public String getToolTipText(Object element)
            {
                return DesignerSessionPoolAccessor.getInstance().isConnected(element)
                    ? "Нажмите для отключения конфигуратора"
                    : "Конфигуратор не подключён";
            }
        });

        // ---- Колонка 2: «Приложение ИР» ----
        TreeViewerColumn col2 = new TreeViewerColumn((TreeViewer) viewer, SWT.NONE);
        col2.getColumn().setText("Приложение ИР");
        col2.getColumn().setToolTipText(
            "Дата начала сеанса приложения ИР. Нажмите ячейку для отключения.");
        col2.getColumn().setWidth(COL_IR_WIDTH);
        col2.getColumn().setResizable(true);
        col2.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                Object el = cell.getElement();
                LocalDateTime start = IRApplicationRegistry.getInstance().getSessionStart(el);
                renderSessionCell(cell, start);
            }

            @Override
            public String getToolTipText(Object element)
            {
                return IRApplicationRegistry.getInstance().isConnected(element)
                    ? "Нажмите для отключения приложения ИР"
                    : "Приложение ИР не подключено";
            }
        });

        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
    }

    /**
     * Отрисовка ячейки статуса: «● дата» зелёным или «—» серым.
     * Используется обеими колонками подключений.
     */
    private static void renderSessionCell(ViewerCell cell, LocalDateTime start)
    {
        if (start != null)
        {
            cell.setText("\u25CF " + DATE_FMT.format(start)); // ● //$NON-NLS-1$
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

    // =======================================================================
    // 2. Клик и курсор для колонок подключений
    // =======================================================================

    private void addClickableColumns(ColumnViewer viewer, Tree tree)
    {
        // Смена курсора при наведении
        tree.addMouseMoveListener(new MouseMoveListener()
        {
            @Override
            public void mouseMove(MouseEvent e)
            {
                int col = columnAt(tree, e.x, e.y);
                TreeItem item = itemAt(tree, e.x, e.y);
                Object element = item != null ? item.getData() : null;

                boolean hand =
                    (col == COL_SSH && DesignerSessionPoolAccessor.getInstance().isConnected(element))
                    || (col == COL_IR  && IRApplicationRegistry.getInstance().isConnected(element));

                tree.setCursor(hand
                    ? tree.getDisplay().getSystemCursor(SWT.CURSOR_HAND)
                    : null);
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
                if (col != COL_SSH && col != COL_IR) return;

                TreeItem item = itemAt(tree, e.x, e.y);
                if (item == null) return;
                Object element = item.getData();

                if (col == COL_SSH)
                {
                    DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                    Object poolKey = acc.findPoolKey(element);
                    if (poolKey == null) return;
                    acc.release(poolKey);
                }
                else // COL_IR
                {
                    if (!IRApplicationRegistry.getInstance().isConnected(element)) return;
                    IRApplicationRegistry.getInstance().disconnect(element);
                }

                if (!viewer.getControl().isDisposed())
                    viewer.update(element, null);
            }
        });
    }

    // =======================================================================
    // 3. Тулбар: dropdown «Подключение»
    // =======================================================================

    /**
     * Добавляет в тулбар кнопку-dropdown «Подключение».
     * При любом клике (и по тексту, и по стрелке) открывается подменю.
     */
    private void addToolbarButtons(IViewPart view, ColumnViewer viewer)
    {
        IActionBars bars = view.getViewSite().getActionBars();
        IToolBarManager tb = bars.getToolBarManager();

        tb.add(new Separator());
        tb.add(new ContributionItem()
        {
            @Override
            public void fill(ToolBar bar, int index)
            {
                ToolItem item = index >= 0
                    ? new ToolItem(bar, SWT.DROP_DOWN, index)
                    : new ToolItem(bar, SWT.DROP_DOWN);
                item.setText("Подключение");
                item.setToolTipText("Управление подключениями инфобаз");

                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        showConnectionMenu(item, bar, viewer);
                    }
                });
            }
        });
        bars.updateActionBars();
    }

    /**
     * Создаёт и показывает временное SWT pop-up меню «Подключение» под кнопкой.
     */
    private static void showConnectionMenu(ToolItem item, ToolBar bar, ColumnViewer viewer)
    {
        Menu menu = new Menu(bar.getShell(), SWT.POP_UP);

        // «Подключить приложение ИР» (заглушка)
        MenuItem connectIr = new MenuItem(menu, SWT.PUSH);
        connectIr.setText("Подключить приложение ИР");
        connectIr.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
                sel.toList().forEach(el -> IRApplicationRegistry.getInstance().connect(el));
                if (!viewer.getControl().isDisposed()) viewer.refresh();
            }
        });

        // «Отключить приложение ИР» (заглушка)
        MenuItem disconnectIr = new MenuItem(menu, SWT.PUSH);
        disconnectIr.setText("Отключить приложение ИР");
        disconnectIr.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
                sel.toList().forEach(el -> IRApplicationRegistry.getInstance().disconnect(el));
                if (!viewer.getControl().isDisposed()) viewer.refresh();
            }
        });

        new MenuItem(menu, SWT.SEPARATOR);

        // «Отключить конфигуратор»
        MenuItem disconnectConf = new MenuItem(menu, SWT.PUSH);
        disconnectConf.setText("Отключить конфигуратор");
        disconnectConf.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                disconnectSsh(((IStructuredSelection) viewer.getSelection()).toList(), viewer);
            }
        });

        // Позиционируем под кнопкой тулбара
        Rectangle bounds = item.getBounds();
        Point loc = bar.toDisplay(bounds.x, bounds.y + bounds.height);
        menu.setLocation(loc);
        menu.setVisible(true);

        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuHidden(MenuEvent e)
            {
                bar.getDisplay().asyncExec(menu::dispose);
            }
        });
    }

    // =======================================================================
    // 4. Контекстное меню: CASCADE «Подключение»
    // =======================================================================

    private void addContextMenu(ColumnViewer viewer, Control control)
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

                DesignerSessionPoolAccessor sshAcc = DesignerSessionPoolAccessor.getInstance();
                IRApplicationRegistry irReg = IRApplicationRegistry.getInstance();

                boolean anySshConnected = sel.toList().stream().anyMatch(sshAcc::isConnected);
                boolean anyIrConnected  = sel.toList().stream().anyMatch(irReg::isConnected);

                // Разделитель
                added.add(new MenuItem(finalMenu, SWT.SEPARATOR));

                // Подменю «Подключение» (CASCADE)
                MenuItem cascade = new MenuItem(finalMenu, SWT.CASCADE);
                cascade.setText("Подключение");
                added.add(cascade);

                Menu sub = new Menu(finalMenu);
                cascade.setMenu(sub);

                // «Подключить приложение ИР»
                MenuItem connectIr = new MenuItem(sub, SWT.PUSH);
                connectIr.setText("Подключить приложение ИР");
                final IStructuredSelection snap = sel;
                connectIr.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        snap.toList().forEach(el -> irReg.connect(el));
                        if (!viewer.getControl().isDisposed()) viewer.refresh();
                    }
                });

                // «Отключить приложение ИР»
                MenuItem disconnectIr = new MenuItem(sub, SWT.PUSH);
                disconnectIr.setText("Отключить приложение ИР");
                disconnectIr.setEnabled(anyIrConnected);
                disconnectIr.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        snap.toList().forEach(el -> irReg.disconnect(el));
                        if (!viewer.getControl().isDisposed()) viewer.refresh();
                    }
                });

                new MenuItem(sub, SWT.SEPARATOR);

                // «Отключить конфигуратор»
                MenuItem disconnectConf = new MenuItem(sub, SWT.PUSH);
                disconnectConf.setText("Отключить конфигуратор");
                disconnectConf.setEnabled(anySshConnected);
                disconnectConf.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        disconnectSsh(snap.toList(), viewer);
                    }
                });
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> toDispose = new ArrayList<>(added);
                added.clear();
                ((Menu) e.widget).getDisplay().asyncExec(() ->
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
    // Отключение SSH
    // =======================================================================

    private static void disconnectSsh(List<?> elements, ColumnViewer viewer)
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
