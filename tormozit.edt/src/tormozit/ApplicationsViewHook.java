package tormozit;


import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.CheckboxCellEditor;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
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
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.core.runtimes.environments.IResolvableRuntimeInstallation;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.RuntimeInstallation;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Хук панели «Приложения» EDT.
 *
 * <h3>Как добавить новую колонку</h3>
 * <ol>
 *   <li>Добавьте константу в {@link Column} в нужной позиции.</li>
 *   <li>Добавьте {@code case} в {@link #makeLabelProvider}.</li>
 *   <li>Если кликабельна — добавьте в {@link #isCursorHand} и {@link #handleColumnClick}.</li>
 * </ol>
 */
public class ApplicationsViewHook implements IStartup
{
    // =======================================================================
    // Описание колонок — единственное место для изменения состава и порядка
    // =======================================================================

    /**
     * Порядок констант = порядок колонок = индекс ({@code ordinal()}).
     * Вставить/убрать/переставить колонку: только этот enum.
     */
    private enum Column
    {
        DB      ("Инфобаза",         null,                                                   200, true,  SWT.NONE  ),
        PLATFORM("Версия платформы", "Версия платформы для взаимодействия EDT с базой",       80, true,  SWT.NONE  ),
        SSH     ("Конфигуратор SSH",  "Дата сеанса конфигуратора SSH. Клик — отключить.",    165, true,  SWT.NONE  ),
        IR      ("Приложение ИР",    "Версия платформы и дата сеанса ИР. Клик — отключить.", 165, true,  SWT.NONE  ),
        AUTO    ("Авто",             "Автоматически подключать приложение ИР",                 45, false, SWT.CENTER);

        final String  title, tooltip;
        final int     width, style;
        final boolean resizable;

        Column(String title, String tooltip, int width, boolean resizable, int style)
        {
            this.title = title; this.tooltip = tooltip;
            this.width = width; this.resizable = resizable; this.style = style;
        }

        int index() { return ordinal(); }

        static Column forIndex(int i)
        {
            Column[] v = values();
            return i >= 0 && i < v.length ? v[i] : null;
        }
    }

    // =======================================================================
    // Константы
    // =======================================================================

    private static final String APPLICATIONS_VIEW_CLASS =
        "com.e1c.g5.dt.internal.applications.ui.view.ApplicationsView"; //$NON-NLS-1$

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd'д'HH:mm:ss"); //$NON-NLS-1$

    // =======================================================================
    // IStartup
    // =======================================================================

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
//          Activator.getDefault().getInjector().injectMembers(this); // Слишком рано?
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
            setupColumns(viewer, tree);
            addClickHandlers(viewer, tree);
        }
        DesignerSessionPoolAccessor.getInstance().startPolling();
        addToolbarButtons(view, viewer);
        addContextMenu(viewer, control);
        registerRedrawOnPoolChange(viewer);
        registerRedrawOnIrChange(viewer);
    }

    // =======================================================================
    // 1. Колонки
    // =======================================================================

    private void setupColumns(ColumnViewer viewer, Tree tree)
    {
        if (tree.getColumnCount() > 0 || tree.getHeaderVisible()) return;

        final IBaseLabelProvider origProvider = viewer.getLabelProvider();

        for (Column col : Column.values())
        {
            TreeViewerColumn tvc = new TreeViewerColumn((TreeViewer) viewer, col.style);
            tvc.getColumn().setText(col.title);
            if (col.tooltip != null) tvc.getColumn().setToolTipText(col.tooltip);
            tvc.getColumn().setWidth(col.width);
            tvc.getColumn().setResizable(col.resizable);
            tvc.setLabelProvider(makeLabelProvider(col, origProvider));
            if (col == Column.AUTO)
                tvc.setEditingSupport(makeAutoEditingSupport(viewer));
        }

        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
    }

    /** Добавить колонку = добавить case сюда. */
    private CellLabelProvider makeLabelProvider(Column col, IBaseLabelProvider origProvider)
    {
        switch (col)
        {
            case DB:
                return new CellLabelProvider()
                {
                    @Override public void update(ViewerCell cell)
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
                };

            case PLATFORM:
                return new CellLabelProvider()
                {
                    @Override public void update(ViewerCell cell)
                    {
                        Object el = cell.getElement();
                        InfobaseReference ib = getInfobase(el);
                        IProject project     = (IProject) Global.getField(el, "project"); //$NON-NLS-1$
                        RuntimeInstallation inst = getRuntimeInstallation(project, ib);
                        cell.setText(inst != null ? inst.getVersionWithBuild() : ""); //$NON-NLS-1$
                    }
                };

            case SSH:
                return new CellLabelProvider()
                {
                    @Override public void update(ViewerCell cell)
                    {
                        DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                        LocalDateTime start = acc.getSessionStart(
                            acc.findPoolKey(getInfobase(cell.getElement())));
                        renderSessionCell(cell, start, ""); //$NON-NLS-1$
                    }
                    @Override public String getToolTipText(Object element)
                    {
                        return DesignerSessionPoolAccessor.getInstance().isConnected(getInfobase(element))
                            ? "Нажмите для отключения конфигуратора SSH" //$NON-NLS-1$
                            : "Конфигуратор SSH не подключён"; //$NON-NLS-1$
                    }
                };

            case IR:
                return new CellLabelProvider()
                {
                    @Override public void update(ViewerCell cell)
                    {
                        IRApplication ir = IRApplication.getInstance();
                        renderSessionCell(cell, ir.getSessionStart(cell.getElement()),
                            ir.getSessionPlatformVersion(cell.getElement()));
                    }
                    @Override public String getToolTipText(Object element)
                    {
                        return IRApplication.getInstance().isConnected(getInfobase(element))
                            ? "Нажмите для отключения приложения ИР" //$NON-NLS-1$
                            : "Приложение ИР не подключено"; //$NON-NLS-1$
                    }
                };

            case AUTO:
                return new CellLabelProvider()
                {
                    @Override public void update(ViewerCell cell)
                    {
                        boolean auto = IRApplication.getInstance()
                            .isAutoConnect(cell.getElement());
                        cell.setText(auto ? "\u2611" : "\u2610"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                };

            default:
                return new CellLabelProvider()
                {
                    @Override public void update(ViewerCell cell) { cell.setText(""); } //$NON-NLS-1$
                };
        }
    }

    private EditingSupport makeAutoEditingSupport(ColumnViewer viewer)
    {
        return new EditingSupport(viewer)
        {
            @Override protected boolean canEdit(Object e)  { return true; }
            @Override protected Object  getValue(Object e) { return IRApplication.getInstance().isAutoConnect(e); }
            @Override protected org.eclipse.jface.viewers.CellEditor getCellEditor(Object e)
            {
                return new CheckboxCellEditor(((TreeViewer) viewer).getTree());
            }
            @Override protected void setValue(Object e, Object v)
            {
                IRApplication.getInstance().setAutoConnect(e, (Boolean) v);
                viewer.update(e, null);
            }
        };
    }

    // =======================================================================
    // RuntimeInstallation
    // =======================================================================

    static RuntimeInstallation getRuntimeInstallation(IProject project, InfobaseReference infobase)
    {
        if (project == null || infobase == null) return null;
        try
        {
            BundleContext ctx = Global.ourContext();
            if (ctx == null) return null;
            ServiceReference<?> ref = ctx.getServiceReference(
                "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager"); //$NON-NLS-1$
            if (ref == null) return null;
            Object syncMgr = ctx.getService(ref);
            if (syncMgr == null) return null;
            IResolvableRuntimeInstallation resolvable =
                (IResolvableRuntimeInstallation) Global.invoke(
                    syncMgr, "getInstallation", project, infobase); //$NON-NLS-1$
            if (resolvable == null) return null;
            return resolvable.resolve(
                List.of("com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"), //$NON-NLS-1$
                infobase.getAppArch());
        }
        catch (Exception e)
        {
            Global.log("getRuntimeInstallation: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    // =======================================================================
    // Отрисовка ячейки сеанса
    // =======================================================================

    private static void renderSessionCell(ViewerCell cell, LocalDateTime start, String version)
    {
        Display d = cell.getControl().getDisplay();
        if (start != null)
        {
            String prefix = (version != null && !version.isEmpty()) ? version + ", " : ""; //$NON-NLS-1$ //$NON-NLS-2$
            cell.setText(prefix + DATE_FMT.format(start));
            cell.setForeground(d.getSystemColor(SWT.COLOR_DARK_GREEN));
        }
        else
        {
            cell.setText("\u2014"); //$NON-NLS-1$
            cell.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
        }
        cell.setBackground(null);
    }

    // =======================================================================
    // 2. Клики и курсор
    // =======================================================================

    private void addClickHandlers(ColumnViewer viewer, Tree tree)
    {
        tree.addMouseMoveListener(new MouseMoveListener()
        {
            @Override public void mouseMove(MouseEvent e)
            {
                Column col          = Column.forIndex(columnAt(tree, e.x, e.y));
                TreeItem item       = itemAt(tree, e.x, e.y);
                InfobaseReference ib = item == null ? null : getInfobase(item.getData());
                tree.setCursor(isCursorHand(col, ib)
                    ? tree.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
            }
        });

        tree.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseDown(MouseEvent e)
            {
                if (e.button != 1) return;
                Column col   = Column.forIndex(columnAt(tree, e.x, e.y));
                TreeItem item = itemAt(tree, e.x, e.y);
                if (col == null || item == null) return;
                handleColumnClick(col, item.getData(), viewer);
            }
        });
    }

    /** Добавить кликабельную колонку = добавить case. */
    private static boolean isCursorHand(Column col, InfobaseReference ib)
    {
        if (col == null || ib == null) return false;
        switch (col)
        {
            case SSH: return DesignerSessionPoolAccessor.getInstance().isConnected(ib);
            case IR:  return IRApplication.getInstance().isConnected(ib);
            default:  return false;
        }
    }

    /** Добавить обработку для новой колонки = добавить case. */
    private static void handleColumnClick(Column col, Object element, ColumnViewer viewer)
    {
        switch (col)
        {
            case SSH:
            {
                DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
                Object key = acc.findPoolKey(getInfobase(element));
                if (key != null) acc.release(key);
                break;
            }
            case IR:
            {
                IRApplication ir = IRApplication.getInstance();
                InfobaseReference ib = getInfobase(element);
                if (ir.isConnected(ib)) ir.disconnect(ib);
                break;
            }
            default: return;
        }
        if (!viewer.getControl().isDisposed()) viewer.update(element, null);
    }

    // =======================================================================
    // 3. Тулбар: кнопки «Запустить конфигуратор» и «Подключение»
    // =======================================================================

    private void addToolbarButtons(IViewPart view, ColumnViewer viewer)
    {
        IActionBars bars = view.getViewSite().getActionBars();
        IToolBarManager tb = bars.getToolBarManager();
        ToolItem[] connectionRef = { null };
        ToolItem[] launchRef     = { null };
        tb.add(new Separator());

//        // Кнопка «Запустить конфигуратор» — дублирует одноимённый пункт контекстного меню
//        tb.add(new ContributionItem()
//        {
//            @Override public void fill(ToolBar bar, int index)
//            {
//                if (bar != null && !bar.isDisposed()) {
//                    bar.setFont(org.eclipse.jface.resource.JFaceResources.getDialogFont());
//                }
//                ToolItem item = index >= 0
//                    ? new ToolItem(bar, SWT.PUSH, index)
//                    : new ToolItem(bar, SWT.PUSH);
//                item.setText("Запустить конфигуратор"); //$NON-NLS-1$
//                item.setToolTipText("Запустить конфигуратор 1С для выбранной инфобазы"); //$NON-NLS-1$
//                item.setEnabled(false);
//                item.addSelectionListener(new SelectionAdapter()
//                {
//                    @Override public void widgetSelected(SelectionEvent e)
//                    {
//                        // "com._1c.g5.v8.dt.internal.platform.services.ui.infobases.actions.LaunchDesignerAction"
//                        IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
//                        if (sel.size() == 1)
//                        {
//                            disconnectSsh(sel.toList(), viewer);
//                            InfobaseReference infobase = ApplicationsViewHook.getInfobaseFromApplication(sel.getFirstElement());
//                            IProject project = (IProject) Global.getField(sel.getFirstElement(), "project");
//                            String connectionString = IRApplication.buildConnectionString(infobase, true);
//                            RuntimeInstallation runtimeInstallation = ApplicationsViewHook.getRuntimeInstallation(project, infobase);
//                            try
//                            {
//                                Runtime.getRuntime().exec("" + runtimeInstallation.getLocation().getPath() + "1cv8.exe");
//                            }
//                            catch (IOException e1)
//                            {
//                                // TODO Auto-generated catch block
//                                e1.printStackTrace();
//                            }
//                        }
//                    }
//                });
//                launchRef[0] = item;
//            }
//        });

        tb.add(new ContributionItem()
        {
            @Override public void fill(ToolBar bar, int index)
            {
                ToolItem item = index >= 0
                    ? new ToolItem(bar, SWT.DROP_DOWN, index)
                    : new ToolItem(bar, SWT.DROP_DOWN);
                item.setText("Подключение"); //$NON-NLS-1$
                item.setToolTipText("Управление подключениями инфобаз (Tormozit)"); //$NON-NLS-1$
                item.setEnabled(false);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override public void widgetSelected(SelectionEvent e)
                    {
                        showConnectionMenu(item, bar, viewer);
                    }
                });
                connectionRef[0] = item;
            }
        });
        bars.updateActionBars();

        viewer.addSelectionChangedListener(event ->
        {
            IStructuredSelection sel = (IStructuredSelection) event.getSelection();

            ToolItem conn = connectionRef[0];
            if (conn != null && !conn.isDisposed())
                conn.setEnabled(!sel.isEmpty());

            ToolItem launch = launchRef[0];
            if (launch != null && !launch.isDisposed())
                launch.setEnabled(sel.size() == 1 && getInfobase(sel.getFirstElement()) != null);
        });
    }

    private static void showConnectionMenu(ToolItem item, ToolBar bar, ColumnViewer viewer)
    {
        try
        {
            IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
            if (sel.isEmpty()) return;

            DesignerSessionPoolAccessor sshAcc = DesignerSessionPoolAccessor.getInstance();
            IRApplication irReg        = IRApplication.getInstance();
            boolean anySsh = sel.toList().stream().anyMatch(el -> sshConnected(sshAcc, el));
            boolean anyIr  = sel.toList().stream().anyMatch(el -> irReg.isConnected(getInfobase(el)));

            Menu menu = new Menu(bar.getShell(), SWT.POP_UP);

            addItem(menu, "Подключить приложение ИР", true, () -> { //$NON-NLS-1$
                sel.toList().forEach(el -> irReg.connectInfobaseApplication((IInfobaseApplication)el));
                safeRefresh(viewer);
            });
            addItem(menu, "Отключить приложение ИР", anyIr, () -> { //$NON-NLS-1$
                sel.toList().forEach(el -> irReg.disconnect(getInfobase(el)));
                safeRefresh(viewer);
            });
            new MenuItem(menu, SWT.SEPARATOR);
            addItem(menu, "Отключить конфигуратор", anySsh, () -> { //$NON-NLS-1$
                disconnectSsh(sel.toList(), viewer);
            });

            Rectangle b = item.getBounds();
            Point loc = bar.toDisplay(b.x, b.y + b.height);
            menu.setLocation(loc);
            menu.setVisible(true);
            menu.addMenuListener(new MenuAdapter()
            {
                @Override public void menuHidden(MenuEvent e)
                {
                    bar.getDisplay().asyncExec(menu::dispose);
                }
            });
        }
        catch (Exception ex)
        {
            Global.log("showConnectionMenu: " + ex); //$NON-NLS-1$
        }
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
                try
                {
                    IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
                    if (sel.isEmpty()) return;

                    DesignerSessionPoolAccessor sshAcc = DesignerSessionPoolAccessor.getInstance();
                    IRApplication irReg        = IRApplication.getInstance();
                    boolean anySsh = sel.toList().stream().anyMatch(el -> sshConnected(sshAcc, el));
                    boolean anyIr  = sel.toList().stream().anyMatch(el -> irReg.isConnected(getInfobase(el)));

                    added.add(new MenuItem(finalMenu, SWT.SEPARATOR));

                    MenuItem cascade = new MenuItem(finalMenu, SWT.CASCADE);
                    cascade.setText("Подключение"); //$NON-NLS-1$
                    added.add(cascade);

                    // new Menu(cascade) — единственный корректный способ создать
                    // подменю для CASCADE-пункта в SWT
                    Menu sub = new Menu(cascade);
                    cascade.setMenu(sub);

                    addItem(sub, "Подключить приложение ИР", true, () -> { //$NON-NLS-1$
                        sel.toList().forEach(el -> irReg.connectInfobaseApplication((IInfobaseApplication)el));
                        safeRefresh(viewer);
                    });
                    addItem(sub, "Отключить приложение ИР", anyIr, () -> { //$NON-NLS-1$
                        sel.toList().forEach(el -> irReg.disconnect(getInfobase(el)));
                        safeRefresh(viewer);
                    });
                    new MenuItem(sub, SWT.SEPARATOR);
                    addItem(sub, "Отключить конфигуратор", anySsh, () -> { //$NON-NLS-1$
                        disconnectSsh(sel.toList(), viewer);
                    });
                }
                catch (Exception ex)
                {
                    Global.log("addContextMenu.menuShown: " + ex); //$NON-NLS-1$
                }
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(added);
                added.clear();
                finalMenu.getDisplay().asyncExec(() ->
                    snapshot.forEach(mi -> { if (!mi.isDisposed()) mi.dispose(); }));
            }
        };

        menu.addMenuListener(adapter);
        control.addDisposeListener(
            ev -> { if (!finalMenu.isDisposed()) finalMenu.removeMenuListener(adapter); });
    }

    private void registerRedrawOnPoolChange(ColumnViewer viewer)
    {
        DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
        Runnable r = () -> Display.getDefault().asyncExec(() -> safeRefresh(viewer));
        acc.addChangeListener(r);
        viewer.getControl().addDisposeListener(e -> acc.removeChangeListener(r));
    }

    private void registerRedrawOnIrChange(ColumnViewer viewer)
    {
        IRApplication ir = IRApplication.getInstance();
        Runnable r = () -> Display.getDefault().asyncExec(() -> safeRefresh(viewer));
        ir.addChangeListener(r);
        viewer.getControl().addDisposeListener(e -> ir.removeChangeListener(r));
    }

    // =======================================================================
    // Вспомогательные методы
    // =======================================================================

    private static void disconnectSsh(List<?> elements, ColumnViewer viewer)
    {
        DesignerSessionPoolAccessor acc = DesignerSessionPoolAccessor.getInstance();
        boolean any = false;
        for (Object el : elements)
        {
            Object key = acc.findPoolKey(getInfobase(el));
            if (key != null) { acc.release(key); any = true; }
        }
        if (any) Display.getDefault().asyncExec(() -> safeRefresh(viewer));
    }

    private static void safeRefresh(ColumnViewer viewer)
    {
        Control c = viewer.getControl();
        if (c != null && !c.isDisposed()) viewer.refresh();
    }

    private static boolean sshConnected(DesignerSessionPoolAccessor acc, Object element)
    {
        try { return acc.isConnected(getInfobase(element)); }
        catch (Exception e) { return false; }
    }

    private static void addItem(Menu menu, String text, boolean enabled, Runnable action)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.setEnabled(enabled);
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override public void widgetSelected(SelectionEvent e) { action.run(); }
        });
    }

    /** Безопасно извлекает {@code InfobaseReference} из элемента дерева. */
    static InfobaseReference getInfobase(Object element)
    {
        if (element == null) return null;
        try
        {
            InfobaseReference ib = (InfobaseReference) Global.call(element, "getInfobase"); //$NON-NLS-1$
            if (ib != null) return ib;
            return (InfobaseReference) Global.getField(element, "infobase"); //$NON-NLS-1$
        }
        catch (Exception e) { return null; }
    }

    // устаревшее имя, оставлено для совместимости с другими классами
    static InfobaseReference getInfobaseFromApplication(Object element) { return getInfobase(element); }
    static String extractKey(Object item) { return DesignerSessionPoolAccessor.nameOf(item); }

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
            TreeItem f = findInItem(ti, x, y);
            if (f != null) return f;
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
                TreeItem f = findInItem(child, x, y);
                if (f != null) return f;
            }
        return null;
    }

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
}
