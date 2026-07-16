package tormozit;


import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
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
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
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
import com.e1c.g5.dt.applications.IApplicationEvent;
import com.e1c.g5.dt.applications.IApplicationListener;
import com.e1c.g5.dt.applications.IApplicationManager;
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

    /** Начальная ширина колонок с флажками (одинаковая для обеих). */
    private static final int CHECKBOX_COLUMN_WIDTH = 140;

    /**
     * Порядок констант = порядок колонок = индекс ({@code ordinal()}).
     * Вставить/убрать/переставить колонку: только этот enum.
     */
    private enum Column
    {
        DB      ("Инфобаза",         null,                                                   200, true,  SWT.NONE  ),
        PLATFORM("Версия платформы", "Версия платформы для взаимодействия EDT с базой",       80, true,  SWT.NONE  ),
        SSH     ("Конфигуратор SSH",  "Дата сеанса конфигуратора SSH. Клик — отключить.",    165, true,  SWT.NONE  ),
        IR      ("Приложение ИР",    "Версия платформы и дата сеанса ИР. Клик — подключить или отключить.", 165, true,  SWT.NONE  ),
        AUTO    ("Авто ИР",             "Автоматически подключать приложение ИР при обращениях к нему", CHECKBOX_COLUMN_WIDTH, true, SWT.CENTER),
        DYN_AUTO("Динамическое обновление",
                 "Автоматически нажимать «Обновить динамически», если к базе подключено приложение ИР",
                 CHECKBOX_COLUMN_WIDTH, true, SWT.CENTER);

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

    private static final String APPLICATIONS_VIEW_ID =
        "com.e1c.g5.dt.applications.ui.view"; //$NON-NLS-1$

    private static final Set<IWorkbenchWindow> PROJECT_SYNC_HOOKED_WINDOWS =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Set<IWorkbenchPage> APPS_CONTEXT_LISTENERS =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private static final int PROJECT_SYNC_MAX_ATTEMPTS = 25;

    private static final int HOOK_VIEW_MAX_ATTEMPTS = 25;

    private static final int STOCK_OVERWRITE_CORRECTION_MAX = 6;

    private static final Set<IWorkbenchPage> PAGE_SYNC_BOOTSTRAPPED =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Set<IViewPart> STOCK_SELECTION_DETACHED_VIEWS =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private static final Map<IViewPart, IApplicationListener> STOCK_APPLICATION_LISTENER_GUARDS =
        Collections.synchronizedMap(new IdentityHashMap<>());

    private static final String HOOKED_KEY = "tormozit.applicationsView.hooked"; //$NON-NLS-1$

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
                @Override public void windowClosed(IWorkbenchWindow w)
                {
                    PROJECT_SYNC_HOOKED_WINDOWS.remove(w);
                }
            });
        });
    }

    // =======================================================================
    // Подключение к окну / панели
    // =======================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null || !PROJECT_SYNC_HOOKED_WINDOWS.add(window))
            return;

        IWorkbenchPage page = window.getActivePage();
        if (page != null)
            bootstrapPageProjectSync(page);
        else
            schedulePageProjectBootstrap(window, 0);

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref.getPart(false);
                if (isApplicationsView(part))
                {
                    IWorkbenchPage p = part.getSite().getPage();
                    detachStockNavigatorSelectionListener((IViewPart) part);
                    if (p != null)
                    {
                        bootstrapPageProjectSync(p);
                        scheduleProjectSyncForPage(p, 0);
                        scheduleStockOverwriteCorrection(p, 0);
                    }
                    scheduleHookApplicationsView((IViewPart) part, 0);
                }
                else if (ref.getPage() != null)
                    bootstrapPageProjectSync(ref.getPage());
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref.getPart(false);
                if (part == null)
                    return;
                IWorkbenchPage p = part.getSite().getPage();
                if (p != null)
                    bootstrapPageProjectSync(p);
            }

            @Override public void partClosed(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref.getPart(false);
                if (isApplicationsView(part))
                    removeStockApplicationListenerGuard((IViewPart) part);
            }
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref.getPart(false);
                if (isApplicationsView(part))
                    scheduleHookApplicationsView((IViewPart) part, 0);
            }
        });
    }

    private static void ensureApplicationsContextListener(IWorkbenchPage page)
    {
        if (page == null || !APPS_CONTEXT_LISTENERS.add(page))
            return;
        ActiveProjectTracker.addListener(page, (p, previous, current) ->
        {
            syncApplicationsProjectForPage(page);
            scheduleStockOverwriteCorrection(page, 0);
        });
    }

    private static void bootstrapPageProjectSync(IWorkbenchPage page)
    {
        if (page == null || !PAGE_SYNC_BOOTSTRAPPED.add(page))
            return;
        ActiveProjectTracker.bootstrapPage(page);
        ensureApplicationsContextListener(page);
        scheduleHookApplicationsViewsForPage(page);
        scheduleProjectSyncForPage(page, 0);
        scheduleStockOverwriteCorrection(page, 0);
    }

    private static void schedulePageProjectBootstrap(IWorkbenchWindow window, int attempt)
    {
        if (window == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(attempt == 0 ? 0 : 100, () ->
        {
            IWorkbenchPage page = window.getActivePage();
            if (page != null)
            {
                bootstrapPageProjectSync(page);
                return;
            }
            if (attempt < PROJECT_SYNC_MAX_ATTEMPTS)
                schedulePageProjectBootstrap(window, attempt + 1);
        });
    }

    private static void applyApplicationsProjectIfMismatch(IWorkbenchPage page)
    {
        if (page == null)
            return;
        IProject expected = ActiveProjectTracker.resolveContextProject(page);
        if (expected == null)
        {
            if (ActiveProjectTracker.shouldPreferEditorProject(page))
                syncApplicationsProjectForPage(page);
            return;
        }
        IViewPart appsView = findApplicationsView(page);
        if (appsView == null)
            return;
        Object current = Global.invoke(appsView, "getCurrentProject"); //$NON-NLS-1$
        if (expected.equals(current))
            return;
        applyApplicationsProject(page, expected);
    }

    /** Штатный ApplicationsView слушает selection навигатора без учёта фокуса — откатываем перезапись. */
    private static void scheduleStockOverwriteCorrection(IWorkbenchPage page, int attempt)
    {
        if (page == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () ->
        {
            if (!ActiveProjectTracker.shouldPreferEditorProject(page))
                return;
            IProject expected = ActiveProjectTracker.resolveContextProject(page);
            if (expected == null)
                return;
            IViewPart appsView = findApplicationsView(page);
            if (appsView == null)
                return;
            Object current = Global.invoke(appsView, "getCurrentProject"); //$NON-NLS-1$
            if (!expected.equals(current))
                applyApplicationsProject(page, expected);
            if (attempt < STOCK_OVERWRITE_CORRECTION_MAX)
                scheduleStockOverwriteCorrection(page, attempt + 1);
        });
    }

    private static void applyApplicationsProjectPreferEditor(IWorkbenchPage page)
    {
        applyApplicationsProjectPreferEditor(page, false);
    }

    private static void applyApplicationsProjectPreferEditor(IWorkbenchPage page, boolean forceReload)
    {
        if (page == null)
            return;
        IEditorPart editor = page.getActiveEditor();
        if (editor != null)
        {
            IProject project = ActiveProjectTracker.resolveEditorProject(editor);
            if (project != null)
            {
                applyApplicationsProject(page, project, forceReload);
                return;
            }
            return;
        }
        applyApplicationsProjectFromNavigator(page, forceReload);
    }

    private static void scheduleHookApplicationsViewsForPage(IWorkbenchPage page)
    {
        if (page == null)
            return;
        for (IViewReference ref : page.getViewReferences())
        {
            if (!APPLICATIONS_VIEW_ID.equals(ref.getId()))
                continue;
            IViewPart view = ref.getView(false);
            if (view != null)
                scheduleHookApplicationsView(view, 0);
            else
                scheduleHookApplicationsViewByReference(ref, 0);
        }
    }

    private static void scheduleHookApplicationsViewByReference(IViewReference ref, int attempt)
    {
        if (ref == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            IViewPart view = ref.getView(false);
            if (view != null && tryHookApplicationsView(view))
                return;
            if (attempt >= HOOK_VIEW_MAX_ATTEMPTS)
                return;
            scheduleHookApplicationsViewByReference(ref, attempt + 1);
        });
    }

    private static void scheduleHookApplicationsView(IViewPart view, int attempt)
    {
        if (view == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            if (tryHookApplicationsView(view) || attempt >= HOOK_VIEW_MAX_ATTEMPTS)
                return;
            scheduleHookApplicationsView(view, attempt + 1);
        });
    }

    private static boolean tryHookApplicationsView(IViewPart view)
    {
        if (!isApplicationsView(view))
            return true;
        ColumnViewer viewer = findViewer(view);
        if (viewer == null)
            return false;
        Control control = viewer.getControl();
        if (control == null || control.isDisposed() || !(control instanceof Tree))
            return false;
        Tree tree = (Tree) control;
        if (isComfortHookApplied(tree))
        {
            detachStockNavigatorSelectionListener(view);
            return true;
        }
        tree.setData(HOOKED_KEY, null);
        new ApplicationsViewHook().hookView(view);
        detachStockNavigatorSelectionListener(view);
        return isComfortHookApplied(tree);
    }

    private static boolean isComfortHookApplied(Tree tree)
    {
        return tree != null && !tree.isDisposed()
            && Boolean.TRUE.equals(tree.getData(HOOKED_KEY))
            && tree.getColumnCount() >= Column.values().length;
    }

    private static void ensureHookApplicationsView(IViewPart appsView)
    {
        if (appsView == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> scheduleHookApplicationsView(appsView, 0));
    }

    /** Проект «Приложений»: навигатор — только при фокусе в нём; иначе — редактор, если открыт. */
    private static void syncApplicationsProjectForPage(IWorkbenchPage page)
    {
        if (page == null)
            return;
        IProject expected = ActiveProjectTracker.resolveContextProject(page);
        if (expected != null)
            applyApplicationsProject(page, expected);
        else if (ActiveProjectTracker.shouldPreferEditorProject(page))
            applyApplicationsProjectPreferEditor(page);
        else
            applyApplicationsProjectFromNavigator(page);
    }

    /** Повторная синхронизация после restore workbench (редактор/панель могут подняться позже хука). */
    private static void scheduleProjectSyncForPage(IWorkbenchPage page, int attempt)
    {
        if (page == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () ->
        {
            IWorkbenchWindow window = page.getWorkbenchWindow();
            if (window == null || window.getShell().isDisposed())
                return;
            if (tryProjectSyncForPage(page) || attempt >= PROJECT_SYNC_MAX_ATTEMPTS)
            {
                applyApplicationsProjectIfMismatch(page);
                return;
            }
            scheduleProjectSyncForPage(page, attempt + 1);
        });
    }

    private static boolean tryProjectSyncForPage(IWorkbenchPage page)
    {
        IViewPart appsView = findApplicationsView(page);
        syncApplicationsProjectForPage(page);
        if (appsView == null)
            return false;
        IProject expected = ActiveProjectTracker.resolveContextProject(page);
        if (expected == null)
        {
            if (page.getActiveEditor() != null || page.getActivePart() instanceof IEditorPart)
                return false;
            return true;
        }
        return expected.equals(Global.invoke(appsView, "getCurrentProject")); //$NON-NLS-1$
    }

    private static void detachStockNavigatorSelectionListener(IViewPart appsView)
    {
        if (!isApplicationsView(appsView) || STOCK_SELECTION_DETACHED_VIEWS.contains(appsView))
            return;
        try
        {
            Field updaterField = appsView.getClass().getDeclaredField("updater"); //$NON-NLS-1$
            updaterField.setAccessible(true);
            Object updater = updaterField.get(appsView);
            if (updater == null)
                return;
            Global.invokeVoid(updater, "unsubscribe"); //$NON-NLS-1$
            Field appMgrField = appsView.getClass().getDeclaredField("applicationManager"); //$NON-NLS-1$
            appMgrField.setAccessible(true);
            Object appMgr = appMgrField.get(appsView);
            IApplicationListener stockListener = (IApplicationListener) updater;
            IApplicationListener guard = new StockApplicationListenerGuard(appsView, stockListener);
            if (appMgr instanceof IApplicationManager)
            {
                ((IApplicationManager) appMgr).addAppllicationListener(guard);
                STOCK_APPLICATION_LISTENER_GUARDS.put(appsView, guard);
            }
            ResourcesPlugin.getWorkspace().addResourceChangeListener((IResourceChangeListener) updater);
            STOCK_SELECTION_DETACHED_VIEWS.add(appsView);
            IWorkbenchPage page = appsView.getSite().getPage();
            if (page != null)
                syncApplicationsProjectForPage(page);
        }
        catch (Exception ignored) {}
    }

    private static void removeStockApplicationListenerGuard(IViewPart appsView)
    {
        STOCK_SELECTION_DETACHED_VIEWS.remove(appsView);
        IApplicationListener guard = STOCK_APPLICATION_LISTENER_GUARDS.remove(appsView);
        if (guard == null || !isApplicationsView(appsView))
            return;
        try
        {
            Field appMgrField = appsView.getClass().getDeclaredField("applicationManager"); //$NON-NLS-1$
            appMgrField.setAccessible(true);
            Object appMgr = appMgrField.get(appsView);
            if (appMgr instanceof IApplicationManager)
                ((IApplicationManager) appMgr).removeAppllicationListener(guard);
        }
        catch (Exception ignored) {}
    }

    /**
     * Штатный Updater на LIFECYCLE_STATE_CHANGED вызывает updateViewUsingSelection
     * (проект навигатора без учёта фокуса). Перехватываем lifecycle: сохраняем отбор
     * панели и перезагружаем список через updateViewUsingProject в UI-потоке (asyncExec).
     */
    private static final class StockApplicationListenerGuard implements IApplicationListener
    {
        private final IViewPart appsView;
        private final IApplicationListener delegate;

        StockApplicationListenerGuard(IViewPart appsView, IApplicationListener delegate)
        {
            this.appsView = appsView;
            this.delegate = delegate;
        }

        @Override
        public void applicationChanged(IApplicationEvent event)
        {
            if (event != null
                && event.getEventType() == IApplicationEvent.ApplicationEventType.LIFECYCLE_STATE_CHANGED)
            {
                IWorkbenchPage page = appsView.getSite().getPage();
                if (page != null)
                {
                    IProject reload = (IProject) Global.invoke(appsView, "getCurrentProject"); //$NON-NLS-1$
                    if (reload == null)
                        reload = ActiveProjectTracker.peek(page);
                    if (reload == null)
                        reload = ActiveProjectTracker.resolveContextProject(page);
                    final IProject project = reload;
                    Display display = appsView.getSite().getShell().getDisplay();
                    if (display == null || display.isDisposed())
                        display = Display.getDefault();
                    if (display != null && !display.isDisposed())
                    {
                        display.asyncExec(() ->
                        {
                            if (project != null)
                                applyApplicationsProject(page, project, true);
                            else
                                applyApplicationsProjectPreferEditor(page, true);
                        });
                    }
                    return;
                }
            }
            delegate.applicationChanged(event);
        }
    }

    private static IViewPart findApplicationsView(IWorkbenchPage page)
    {
        if (page == null)
            return null;
        IViewPart view = page.findView(APPLICATIONS_VIEW_ID);
        return isApplicationsView(view) ? view : null;
    }

    private static void applyApplicationsProject(IWorkbenchPage page, IProject project)
    {
        applyApplicationsProject(page, project, false);
    }

    private static void applyApplicationsProject(IWorkbenchPage page, IProject project, boolean forceReload)
    {
        if (page == null || project == null)
            return;
        IViewPart appsView = findApplicationsView(page);
        if (appsView == null)
            return;
        Object current = Global.invoke(appsView, "getCurrentProject"); //$NON-NLS-1$
        if (project.equals(current) && !forceReload)
            return;
        Global.invoke(appsView, "updateViewUsingProject", project); //$NON-NLS-1$
        ensureHookApplicationsView(appsView);
        if (ActiveProjectTracker.shouldPreferEditorProject(page))
            scheduleStockOverwriteCorrection(page, 0);
    }

    private static void applyApplicationsProjectFromNavigator(IWorkbenchPage page)
    {
        applyApplicationsProjectFromNavigator(page, false);
    }

    private static void applyApplicationsProjectFromNavigator(IWorkbenchPage page, boolean forceReload)
    {
        if (page == null)
            return;
        if (ActiveProjectTracker.shouldPreferEditorProject(page))
        {
            applyApplicationsProjectPreferEditor(page, forceReload);
            return;
        }
        IViewPart appsView = findApplicationsView(page);
        if (appsView == null)
            return;
        IProject project = ActiveProjectTracker.getNavigatorProject(page);
        if (project != null)
            applyApplicationsProject(page, project, forceReload);
        else
        {
            Global.invoke(appsView, "updateViewUsingCurrentSelection"); //$NON-NLS-1$
            ensureHookApplicationsView(appsView);
        }
    }

    private static boolean isApplicationsView(Object part)
    {
        if (part instanceof IViewPart)
        {
            IViewPart view = (IViewPart) part;
            if (view.getViewSite() != null
                    && APPLICATIONS_VIEW_ID.equals(view.getViewSite().getId()))
                return true;
        }
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

        DesignerSessionPoolAccessor.getInstance().startPolling();

        if (control instanceof Tree)
        {
            Tree tree = (Tree) control;
            if (isComfortHookApplied(tree))
                return;
            tree.setData(HOOKED_KEY, null);
            setupColumns(viewer, tree);
            addClickHandlers(viewer, tree);
            addToolbarButtons(view, viewer);
            addContextMenu(viewer, control);
            registerRedrawOnPoolChange(viewer);
            registerRedrawOnIrChange(viewer);
            tree.setData(HOOKED_KEY, Boolean.TRUE);
        }
        else
        {
            addToolbarButtons(view, viewer);
            addContextMenu(viewer, control);
            registerRedrawOnPoolChange(viewer);
            registerRedrawOnIrChange(viewer);
        }
    }

    // =======================================================================
    // 1. Колонки
    // =======================================================================

    private void setupColumns(ColumnViewer viewer, Tree tree)
    {
        while (tree.getColumnCount() > 0)
            tree.getColumn(0).dispose();
        tree.setHeaderVisible(false);
        tree.setLinesVisible(false);

        final IBaseLabelProvider origProvider = viewer.getLabelProvider();

        for (Column col : Column.values())
        {
            TreeViewerColumn tvc = new TreeViewerColumn((TreeViewer) viewer, col.style);
            tvc.getColumn().setText(col.title);
            if (col.tooltip != null)
                tvc.getColumn().setToolTipText(col.tooltip + Global.pluginSignForTooltip());
            tvc.getColumn().setWidth(col.width);
            tvc.getColumn().setResizable(col.resizable);
            tvc.setLabelProvider(makeLabelProvider(col, origProvider));
            if (col == Column.AUTO)
                tvc.setEditingSupport(makeAutoEditingSupport(viewer));
            else if (col == Column.DYN_AUTO)
                tvc.setEditingSupport(makeDynamicAutoEditingSupport(viewer));
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
                        RuntimeInstallation inst;
                        try
                        {
                            inst = getRuntimeInstallation(project, ib);
                        }
                        catch (Exception e)
                        {
                            Global.log("getRuntimeInstallation: " + e.getMessage()); //$NON-NLS-1$
                            return;
                        }
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
                        LocalDateTime start = ir.getSessionStart(cell.getElement());
                        if (start != null)
                        {
                            renderSessionCell(cell, start,
                                ir.getSessionPlatformVersion(cell.getElement()));
                            return;
                        }
                        Display d = cell.getControl().getDisplay();
                        InfobaseReference ib = getInfobase(cell.getElement());
                        if (ir.isConnecting(ib))
                        {
                            cell.setText("Подключение…"); //$NON-NLS-1$
                            cell.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
                        }
                        else
                        {
                            cell.setText("Подключить"); //$NON-NLS-1$
                            cell.setForeground(d.getSystemColor(SWT.COLOR_DARK_BLUE));
                        }
                        cell.setBackground(null);
                    }
                    @Override public String getToolTipText(Object element)
                    {
                        IRApplication ir = IRApplication.getInstance();
                        InfobaseReference ib = getInfobase(element);
                        if (ir.isConnected(ib))
                            return "Нажмите для отключения приложения ИР"; //$NON-NLS-1$
                        if (ir.isConnecting(ib))
                            return "Подключение приложения ИР…"; //$NON-NLS-1$
                        return "Нажмите для подключения приложения ИР"; //$NON-NLS-1$
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

            case DYN_AUTO:
                return new CellLabelProvider()
                {
                    @Override public void update(ViewerCell cell)
                    {
                        boolean on = IRApplication.getInstance()
                            .isDynamicAutoUpdate(cell.getElement());
                        cell.setText(on ? "\u2611" : "\u2610"); //$NON-NLS-1$ //$NON-NLS-2$
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

    private EditingSupport makeDynamicAutoEditingSupport(ColumnViewer viewer)
    {
        return new EditingSupport(viewer)
        {
            @Override protected boolean canEdit(Object e)  { return true; }
            @Override protected Object  getValue(Object e) { return IRApplication.getInstance().isDynamicAutoUpdate(e); }
            @Override protected org.eclipse.jface.viewers.CellEditor getCellEditor(Object e)
            {
                return new CheckboxCellEditor(((TreeViewer) viewer).getTree());
            }
            @Override protected void setValue(Object e, Object v)
            {
                IRApplication.getInstance().setDynamicAutoUpdate(e, (Boolean) v);
                viewer.update(e, null);
            }
        };
    }

    // =======================================================================
    // RuntimeInstallation
    // =======================================================================

    static RuntimeInstallation getRuntimeInstallation(IProject project, InfobaseReference infobase)
    {
        IRConnectDebug.logRuntimeInstallationLookupStart(project, infobase);
        if (project == null || infobase == null) 
        {    
            return null;
        }
        BundleContext ctx = Global.ourContext();
        ServiceReference<?> ref = ctx.getServiceReference(
            "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager"); //$NON-NLS-1$
        IRConnectDebug.logServiceReference(ref);
        Object syncMgr = ctx.getService(ref);
        IRConnectDebug.logSyncMgr(syncMgr);
        IResolvableRuntimeInstallation resolvable =
            (IResolvableRuntimeInstallation) Global.invoke(
                syncMgr, "getInstallation", project, infobase); //$NON-NLS-1$
        IRConnectDebug.logResolvable(syncMgr, resolvable);
        try
        {
            RuntimeInstallation result = resolvable.resolve(
                List.of("com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"), //$NON-NLS-1$
                infobase.getAppArch());
            IRConnectDebug.logResolveResult(result);
            return result;
        }
        catch (Exception e)
        {
            IRConnectDebug.logResolveException(e, project, infobase);
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
            case IR:  return true;
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
                if (ir.isConnected(ib))
                    ir.disconnect(ib);
                else if (element instanceof IInfobaseApplication app)
                    ir.connectInfobaseApplication(app);
                break;
            }
            case AUTO:
            {
                IRApplication ir = IRApplication.getInstance();
                ir.setAutoConnect(element, !ir.isAutoConnect(element));
                break;
            }
            case DYN_AUTO:
            {
                IRApplication ir = IRApplication.getInstance();
                ir.setDynamicAutoUpdate(element, !ir.isDynamicAutoUpdate(element));
                break;
            }
            default: return;
        }
        if (!viewer.getControl().isDisposed()) viewer.update(element, null);
    }

    // =======================================================================
    // 3. Тулбар: кнопки «Запустить конфигуратор» и «Комфорт»
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
                item.setText("Комфорт"); //$NON-NLS-1$
                item.setToolTipText("Управление подключениями инфобаз" + Global.pluginSignForTooltip()); //$NON-NLS-1$
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

            fillIrMenu(menu, sel, irReg, anyIr, anySsh, viewer);

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
    // 4. Контекстное меню: CASCADE «Комфорт»
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
                    cascade.setText("Комфорт"); //$NON-NLS-1$
                    added.add(cascade);

                    // new Menu(cascade) — единственный корректный способ создать
                    // подменю для CASCADE-пункта в SWT
                    Menu sub = new Menu(cascade);
                    cascade.setMenu(sub);

                    fillIrMenu(sub, sel, irReg, anyIr, anySsh, viewer);
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

    private static void fillIrMenu(Menu menu, IStructuredSelection sel,
        IRApplication irReg, boolean anyIr, boolean anySsh, ColumnViewer viewer)
    {
        addItem(menu, "Подключить приложение ИР", true, () -> { //$NON-NLS-1$
            sel.toList().forEach(el -> irReg.connectInfobaseApplication((IInfobaseApplication)el));
            safeRefresh(viewer);
        });
        addItem(menu, "Показать приложение ИР", anyIr, () -> { //$NON-NLS-1$
            sel.toList().forEach(el -> {
                IRSession session = irReg.getSessionByInfobase(getInfobase(el));
                if (session != null) session.showIrApplication();
            });
        });
        addItem(menu, "Отключить приложение ИР", anyIr, () -> { //$NON-NLS-1$
            sel.toList().forEach(el -> irReg.disconnect(getInfobase(el)));
            safeRefresh(viewer);
        });
        new MenuItem(menu, SWT.SEPARATOR);
        addItem(menu, "Отключить конфигуратор", anySsh, () -> { //$NON-NLS-1$
            disconnectSsh(sel.toList(), viewer);
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
