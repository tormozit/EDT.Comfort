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
import org.eclipse.jface.viewers.EditingSupport;
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

import tormozit.edt.Reflect;

/**
 * Хук панели «Приложения» EDT.
 *
 * <p>Добавляет:
 * <ul>
 *   <li>Колонку «Конфигуратор SSH» — дата SSH-сеанса, клик для отключения.</li>
 *   <li>Колонку «Приложение ИР»    — версия платформы + дата сеанса, клик для отключения.</li>
 *   <li>Dropdown-кнопку «Подключение» в тулбар — недоступна при пустом выделении.</li>
 *   <li>CASCADE-подменю «Подключение» в контекстное меню.</li>
 * </ul>
 */
public class ApplicationsViewHook implements IStartup
{
    private static final String APPLICATIONS_VIEW_CLASS =
        "com.e1c.g5.dt.internal.applications.ui.view.ApplicationsView"; //$NON-NLS-1$

    private static final int COL_DB  = 0;
    private static final int COL_SSH = 1;
    private static final int COL_IR  = 2;
    private static final int COL_AUTO = 3;

    private static final int COL_DB_WIDTH   = 200;
    private static final int COL_SSH_WIDTH  = 165;
    private static final int COL_IR_WIDTH   = 165;
    private static final int COL_AUTO_WIDTH = 45;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd'д'HH:mm:ss"); //$NON-NLS-1$

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
        return part != null && APPLICATIONS_VIEW_CLASS.equals(part.getClass().getName());
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
        registerRedrawOnIrChange(viewer);
    }

    // =======================================================================
    // 1. Колонки
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
            "Версия платформы и дата начала сеанса конфигуратора SSH. Нажмите ячейку для отключения.");
        col1.getColumn().setWidth(COL_SSH_WIDTH);
        col1.getColumn().setResizable(true);
        col1.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                Object el = cell.getElement();
                DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                Object infobase = getInfobaseFromApplication(el);
                LocalDateTime start   = acc.getSessionStart(acc.findPoolKey(infobase));
                String version = IRApplicationRegistry.extractEDTPlatformVersion(infobase);
                renderSessionCell(cell, start, version);
            }

            @Override
            public String getToolTipText(Object element)
            {
                Object infobase = getInfobaseFromApplication(element);
                return DesignerSessionPoolAccessor.getInstance().isConnected(infobase)
                    ? "Нажмите для отключения конфигуратора SSH"
                    : "Конфигуратор SSH не подключен";
            }
        });

        // ---- Колонка 2: «Приложение ИР» ----
        TreeViewerColumn col2 = new TreeViewerColumn((TreeViewer) viewer, SWT.NONE);
        col2.getColumn().setText("Приложение ИР");
        col2.getColumn().setToolTipText(
            "Версия платформы и дата начала сеанса ИР. Нажмите ячейку для отключения.");
        col2.getColumn().setWidth(COL_IR_WIDTH);
        col2.getColumn().setResizable(true);
        col2.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                Object el      = cell.getElement();
                IRApplicationRegistry ir = IRApplicationRegistry.getInstance();
                LocalDateTime start   = ir.getSessionStart(el);
                String        version = ir.getSessionPlatformVersion(el);
                renderSessionCell(cell, start, version);
            }

            @Override
            public String getToolTipText(Object element)
            {
                Object infobase = getInfobaseFromApplication(element);
                return IRApplicationRegistry.getInstance().isConnected(infobase)
                    ? "Нажмите для отключения приложения ИР"
                    : "Приложение ИР не подключено";
            }
        });

        // ---- Колонка 3: «Авто» (чекбокс авто-подключения ИР) ----
        TreeViewerColumn col3 = new TreeViewerColumn((TreeViewer) viewer, SWT.CENTER);
        col3.getColumn().setText("Авто");
        col3.getColumn().setToolTipText("Автоматически подключать приложение ИР");
        col3.getColumn().setWidth(COL_AUTO_WIDTH);
        col3.getColumn().setResizable(false);
        col3.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                boolean auto = IRApplicationRegistry.getInstance().isAutoConnect(cell.getElement());
                cell.setText(auto ? "\u2611" : "\u2610"); // ☑ / ☐ //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
        col3.setEditingSupport(new EditingSupport(viewer)
        {
            @Override protected boolean canEdit(Object element) { return true; }
            @Override protected org.eclipse.jface.viewers.CellEditor getCellEditor(Object element)
            {
                return new org.eclipse.jface.viewers.CheckboxCellEditor(
                    ((TreeViewer) viewer).getTree());
            }
            @Override protected Object getValue(Object element)
            {
                return IRApplicationRegistry.getInstance().isAutoConnect(element);
            }
            @Override protected void setValue(Object element, Object value)
            {
                IRApplicationRegistry.getInstance().setAutoConnect(element, (Boolean) value);
                viewer.update(element, null);
            }
        });

        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
    }

    /**
     * Отрисовка ячейки: «версия, дата» зелёным или «—» серым.
     */
    private static void renderSessionCell(ViewerCell cell, LocalDateTime start, String version)
    {
        if (start != null)
        {
            String text = (version != null && !version.isEmpty())
                ? version + ", " + DATE_FMT.format(start) //$NON-NLS-1$
                : DATE_FMT.format(start);
            cell.setText(text);
            cell.setForeground(cell.getControl().getDisplay()
                .getSystemColor(SWT.COLOR_DARK_GREEN));
        }
        else
        {
            cell.setText("\u2014"); // — //$NON-NLS-1$
            cell.setForeground(cell.getControl().getDisplay()
                .getSystemColor(SWT.COLOR_DARK_GRAY));
        }
        cell.setBackground(null);
    }

    // =======================================================================
    // 2. Клик и курсор
    // =======================================================================

    static Object getInfobaseFromApplication(Object element)
    {
        if (element == null) return null;
        Object ib = Reflect.call(element, "getInfobase"); //$NON-NLS-1$
        return ib != null ? ib : Reflect.getField(element, "infobase"); //$NON-NLS-1$
    }
    
    private void addClickableColumns(ColumnViewer viewer, Tree tree)
    {
        tree.addMouseMoveListener(new MouseMoveListener()
        {
            @Override
            public void mouseMove(MouseEvent e)
            {
                int      col     = columnAt(tree, e.x, e.y);
                TreeItem item    = itemAt(tree, e.x, e.y);
                Object   infobase = item==null ? null : getInfobaseFromApplication(item.getData());

                boolean hand =
                    (col == COL_SSH && DesignerSessionPoolAccessor.getInstance().isConnected(infobase))
                 || (col == COL_IR  && IRApplicationRegistry.getInstance().isConnected(infobase));

                tree.setCursor(hand
                    ? tree.getDisplay().getSystemCursor(SWT.CURSOR_HAND)
                    : null);
            }
        });

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
                Object infobase = item==null ? null : getInfobaseFromApplication(item.getData());

                if (col == COL_SSH)
                {
                    DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                    Object poolKey = acc.findPoolKey(infobase);
                    if (poolKey == null) return;
                    acc.release(poolKey);
                }
                else
                {
                    if (!IRApplicationRegistry.getInstance().isConnected(infobase)) return;
                    IRApplicationRegistry.getInstance().disconnect(infobase);
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
     * Кнопка «Подключение» (SWT.DROP_DOWN).
     * <ul>
     *   <li>При любом клике открывает подменю.</li>
     *   <li>Недоступна когда ничего не выделено.</li>
     * </ul>
     */
    private void addToolbarButtons(IViewPart view, ColumnViewer viewer)
    {
        IActionBars bars = view.getViewSite().getActionBars();
        IToolBarManager tb = bars.getToolBarManager();

        // Массив для захвата ссылки из fill() в лямбду selection listener-а
        ToolItem[] toolItemRef = { null };

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
                item.setEnabled(false); // недоступна при пустом выделении

                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        showConnectionMenu(item, bar, viewer);
                    }
                });
                toolItemRef[0] = item;
            }
        });
        bars.updateActionBars();

        // Включаем/выключаем кнопку при изменении выделения
        viewer.addSelectionChangedListener(event ->
        {
            ToolItem ti = toolItemRef[0];
            if (ti != null && !ti.isDisposed())
                ti.setEnabled(!event.getSelection().isEmpty());
        });
    }

    private static void showConnectionMenu(ToolItem item, ToolBar bar, ColumnViewer viewer)
    {
        Menu menu = new Menu(bar.getShell(), SWT.POP_UP);

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
                // Контекстное меню не показываем при пустом выделении
                if (sel.isEmpty()) return;

                DesignerSessionPoolAccessor sshAcc = DesignerSessionPoolAccessor.getInstance();
                IRApplicationRegistry       irReg  = IRApplicationRegistry.getInstance();

                boolean anySsh = sel.toList().stream().anyMatch(sshAcc::isConnected);
                boolean anyIr  = sel.toList().stream().anyMatch(irReg::isConnected);

                added.add(new MenuItem(finalMenu, SWT.SEPARATOR));

                MenuItem cascade = new MenuItem(finalMenu, SWT.CASCADE);
                cascade.setText("Подключение");
                added.add(cascade);

                Menu sub = new Menu(finalMenu);
                cascade.setMenu(sub);

                final IStructuredSelection snap = sel;

                MenuItem connectIr = new MenuItem(sub, SWT.PUSH);
                connectIr.setText("Подключить приложение ИР");
                connectIr.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        snap.toList().forEach(el -> irReg.connect(el));
                        if (!viewer.getControl().isDisposed()) viewer.refresh();
                    }
                });

                MenuItem disconnectIr = new MenuItem(sub, SWT.PUSH);
                disconnectIr.setText("Отключить приложение ИР");
                disconnectIr.setEnabled(anyIr);
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

                MenuItem disconnectConf = new MenuItem(sub, SWT.PUSH);
                disconnectConf.setText("Отключить конфигуратор");
                disconnectConf.setEnabled(anySsh);
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
    // 5. Автообновление
    // =======================================================================

    private void registerRedrawOnPoolChange(ColumnViewer viewer)
    {
        DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
        Runnable r = () -> Display.getDefault().asyncExec(() ->
        {
            Control c = viewer.getControl();
            if (c != null && !c.isDisposed()) viewer.refresh();
        });
        acc.addChangeListener(r);
        viewer.getControl().addDisposeListener(e -> acc.removeChangeListener(r));
    }

    private void registerRedrawOnIrChange(ColumnViewer viewer)
    {
        IRApplicationRegistry ir = IRApplicationRegistry.getInstance();
        Runnable r = () -> Display.getDefault().asyncExec(() ->
        {
            Control c = viewer.getControl();
            if (c != null && !c.isDisposed()) viewer.refresh();
        });
        ir.addChangeListener(r);
        viewer.getControl().addDisposeListener(e -> ir.removeChangeListener(r));
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
            Object key = acc.findPoolKey(getInfobaseFromApplication(el));
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
