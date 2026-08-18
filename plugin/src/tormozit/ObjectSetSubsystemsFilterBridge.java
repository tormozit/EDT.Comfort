package tormozit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.events.TreeAdapter;
import org.eclipse.swt.events.TreeEvent;
import org.eclipse.ui.IPartListener2;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.navigator.INavigatorContentService;
import org.eclipse.ui.navigator.INavigatorFilterService;
import org.eclipse.ui.navigator.ICommonFilterDescriptor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.dt.common.IEObjectTrie;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Обёртка штатного фильтра подсистем в <em>навигаторе</em>:
 * <ul>
 *   <li>при включённой «звезде» наборов фильтр по непустому add-target набору вытесняет
 *       фильтр подсистем;</li>
 *   <li>режим «чёрный список» из {@link FilterBySubsystemsDialogHook} инвертирует штатный
 *       белый список подсистем (те же {@code FilterBySubsystemsSettings}).</li>
 * </ul>
 *
 * <p>Не путать с {@link FilterBySubsystemsDialogHook}: в диалоге «Отбор / Фильтр по подсистемам»
 * наборы объектов не отображаются (TODO — см. javadoc там).
 */
public final class ObjectSetSubsystemsFilterBridge implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.objectSetSubsystemsBridge"; //$NON-NLS-1$
    private static final String WRAPPER_MARKER = "tormozit.objectSetSubsystemsWrapper"; //$NON-NLS-1$
    private static final String EXPAND_SYNC_MARKER = "tormozit.objectSetExpandSync"; //$NON-NLS-1$
    private static final String MEMENTO_REAPPLY_MARKER = "tormozit.subsystemsMementoReapply"; //$NON-NLS-1$
    private static final String NATIVE_FILTER_ID =
        "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSubsystemsFilter"; //$NON-NLS-1$
    private static final String NATIVE_FILTER_CLASS =
        "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSubsystemsFilter"; //$NON-NLS-1$

    private static final String SUBSYSTEMS_FILTER_COMMAND_ID =
        "com._1c.g5.v8.dt.navigator.ui.filterBySubsystems"; //$NON-NLS-1$
    private static final String FOCUS_NAVIGATOR_COMMAND_ID =
        "com._1c.g5.v8.dt.ui.commands.focusNavigator"; //$NON-NLS-1$

    /** Повтор Data→Settings после открытия view: индекс подсистем может ещё не быть готов. */
    private static final int MEMENTO_REAPPLY_MAX_ATTEMPTS = 24;
    private static final int MEMENTO_REAPPLY_DELAY_MS = 250;
    private static final int MEMENTO_REAPPLY_STABLE_HITS = 2;

    private static boolean resourceListenerInstalled;
    private static boolean competingFilterListenerInstalled;
    private static final Set<String> pendingAutoAddPaths = new HashSet<>();
    private static boolean gitChangedRefreshPending;
    private static int visibleChevronStripGen;

    private static final ViewerFilter PASS_THROUGH_NATIVE = new ViewerFilter()
    {
        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            return true;
        }
    };

    @Override
    public void earlyStartup()
    {
        installMdObjectCreateListener();
        installCompetingNavigatorFilterListener();
        Display.getDefault().asyncExec(() -> {
            ObjectSetsNavigatorFilterSupport.syncFromCommandToggle();
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            if (ObjectSetsNavigatorFilterSupport.isActive())
                scheduleFilterRefreshAll(0, "earlyStartup"); //$NON-NLS-1$
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
        });
    }

    private static void installMdObjectCreateListener()
    {
        if (resourceListenerInstalled)
            return;
        resourceListenerInstalled = true;
        ResourcesPlugin.getWorkspace().addResourceChangeListener(new MdObjectCreateListener());
    }

    private static void installCompetingNavigatorFilterListener()
    {
        if (competingFilterListenerInstalled || !PlatformUI.isWorkbenchRunning())
            return;
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        ICommandService commandService = wb.getService(ICommandService.class);
        if (commandService == null)
            return;
        competingFilterListenerInstalled = true;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                // ничего: реагируем только на завершение команд
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                if (FOCUS_NAVIGATOR_COMMAND_ID.equals(commandId)
                    || commandId != null && commandId.contains("showInNavigator")) //$NON-NLS-1$
                    scheduleSyncVisibleChevrons();
                if (!SUBSYSTEMS_FILTER_COMMAND_ID.equals(commandId))
                    return;
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> {
                    // После «Установить»/активации штатный фильтр снова появляется в viewer —
                    // переподхватить в обёртку (иначе чёрный список не применяется).
                    rebindAllNavigatorBridges("subsystemsFilterCommand"); //$NON-NLS-1$
                    display.timerExec(300, () ->
                        rebindAllNavigatorBridges("subsystemsFilterCommand-delayed")); //$NON-NLS-1$
                    if (ObjectSetsNavigatorFilterSupport.isActive()
                            && isAnyNavigatorSubsystemsFilterActive())
                        ObjectSetsNavigatorFilterSupport.deactivateBecauseCompetingFilter();
                });
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
                // ничего
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                // ничего
            }
        });
    }

    private static boolean isAnyNavigatorSubsystemsFilterActive()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return false;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
                if (view != null && ObjectSetsNavigatorFilterSupport.isCompetingNavigatorFilterActive(view))
                    return true;
            }
        }
        return false;
    }

    private static final class MdObjectCreateListener implements IResourceChangeListener
    {
        @Override
        public void resourceChanged(IResourceChangeEvent event)
        {
            if (event.getType() != IResourceChangeEvent.POST_CHANGE)
                return;
            if (!ObjectSetsNavigatorFilterSupport.isActive())
                return;
            IResourceDelta root = event.getDelta();
            if (root == null)
                return;
            collectNewMdObjectPaths(root);
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(ObjectSetSubsystemsFilterBridge::flushPendingAutoAdds);
        }

        private static void collectNewMdObjectPaths(IResourceDelta delta)
        {
            if (delta == null)
                return;
            IResource resource = delta.getResource();
            if (resource != null && resource.getType() != IResource.PROJECT
                && delta.getKind() == IResourceDelta.ADDED)
            {
                String path = resource.getProject().getName() + '|'
                    + resource.getProjectRelativePath().toString().replace('\\', '/');
                synchronized (pendingAutoAddPaths)
                {
                    pendingAutoAddPaths.add(path);
                }
            }
            for (IResourceDelta child : delta.getAffectedChildren())
                collectNewMdObjectPaths(child);
        }
    }

    private static void flushPendingAutoAdds()
    {
        maybeRefreshGitChangedFilter();
        Set<String> batch;
        synchronized (pendingAutoAddPaths)
        {
            if (pendingAutoAddPaths.isEmpty())
                return;
            batch = new HashSet<>(pendingAutoAddPaths);
            pendingAutoAddPaths.clear();
        }
        for (String entry : batch)
        {
            int sep = entry.indexOf('|');
            if (sep <= 0)
                continue;
            String projectName = entry.substring(0, sep);
            String relPath = entry.substring(sep + 1);
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.isOpen())
                continue;
            ObjectSetsItems.tryAutoAddRootObjectToActiveSet(project, relPath);
        }
    }

    /**
     * При активном фильтре навигатора и активном системном наборе («<Измененные Git>») —
     * отложенное (debounce) пересчитывание фильтра после изменений ресурсов.
     */
    private static void maybeRefreshGitChangedFilter()
    {
        if (!ObjectSetsNavigatorFilterSupport.isActive())
            return;
        if (!ObjectSetsAddTargetState.getInstance().isAnyAddTargetSystemSet())
            return;
        if (gitChangedRefreshPending)
            return;
        gitChangedRefreshPending = true;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(400, () -> {
            gitChangedRefreshPending = false;
            if (!ObjectSetsNavigatorFilterSupport.isActive())
                return;
            refreshAllNavigators();
        });
    }

    private static void scheduleFilterRefreshAll(int attempt, String source)
    {
        if (!ObjectSetsNavigatorFilterSupport.isActive())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 250;
        display.timerExec(delay, () -> {
            if (!ObjectSetsNavigatorFilterSupport.isActive())
                return;
            boolean refreshed = refreshAllNavigators();
            if (!refreshed && attempt < 12)
                scheduleFilterRefreshAll(attempt + 1, source);
        });
    }

    private static void scheduleFilterRefresh(IViewPart navigator, int attempt, String source)
    {
        if (!ObjectSetsNavigatorFilterSupport.isActive() || navigator == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 200;
        display.timerExec(delay, () -> {
            if (!ObjectSetsNavigatorFilterSupport.isActive())
                return;
            CommonViewer viewer = getCommonViewer(navigator);
            if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
            {
                if (attempt < 15)
                    scheduleFilterRefresh(navigator, attempt + 1, source);
                return;
            }
            installBridge(navigator, viewer);
            refreshNavigator(navigator);
        });
    }

    static void onFilterStateChanged()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable refresh = () -> refreshAllNavigators();
        if (display.getThread() == Thread.currentThread())
            refresh.run();
        else
            display.asyncExec(refresh);
    }

    private static boolean refreshAllNavigators()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return false;
        boolean refreshed = false;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
                if (view != null)
                {
                    refreshNavigator(view);
                    refreshed = true;
                }
            }
        }
        return refreshed;
    }

    static void refreshNavigator(IViewPart navigator)
    {
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
            return;
        ObjectSetsItems.beginAddTargetTreeFilterRefresh();
        installBridge(navigator, viewer);
        viewer.refresh();
        syncGroupExpandIndicators(viewer);
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isNavigatorView(view))
                    tryHook(view);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryHookFromRef(ref, "partOpened"); } //$NON-NLS-1$
            @Override public void partVisible(IWorkbenchPartReference ref) { tryHookFromRef(ref, "partVisible"); } //$NON-NLS-1$
            @Override public void partActivated(IWorkbenchPartReference ref) { tryHookFromRef(ref, "partActivated"); } //$NON-NLS-1$
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) { tryHookFromRef(ref, "partInputChanged"); } //$NON-NLS-1$
        });
    }

    private static void tryHookFromRef(IWorkbenchPartReference ref, String source)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        if (isNavigatorView(part))
        {
            IViewPart navigator = (IViewPart) part;
            tryHook(navigator);
            scheduleSubsystemsMementoReapply(navigator);
        }
    }

    private static boolean isNavigatorView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return Global.NAVIGATOR_VIEW_ID.equals(id)
            || part.getClass().getName().contains("internal.navigator.ui.Navigator"); //$NON-NLS-1$
    }

    private static void tryHook(IViewPart navigator)
    {
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        boolean alreadyHooked = Boolean.TRUE.equals(tree.getData(HOOK_MARKER));
        installBridge(navigator, viewer);
        tree.setData(HOOK_MARKER, Boolean.TRUE);
        scheduleSubsystemsMementoReapply(navigator);
        if (!alreadyHooked && ObjectSetsNavigatorFilterSupport.isActive())
            scheduleFilterRefresh(navigator, 0, "tryHook"); //$NON-NLS-1$
    }

    /**
     * Штатный {@code Navigator.init} кладёт memento в {@code filterBySubsystemsData}, а
     * {@code getFilterBySubsystemsSettings()} один раз строит Settings из Data. Если BM/индекс
     * подсистем ещё не готов — часть FQN не матчится и неполный Settings кэшируется. Повторяем
     * {@code getFilterSettings(data)} после готовности и подменяем кэш Navigator + NSF.
     */
    private static void scheduleSubsystemsMementoReapply(IViewPart navigator)
    {
        if (navigator == null)
            return;
        CommonViewer viewer = getCommonViewer(navigator);
        Tree tree = viewer != null ? viewer.getTree() : null;
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(MEMENTO_REAPPLY_MARKER)))
            return;
        tree.setData(MEMENTO_REAPPLY_MARKER, Boolean.TRUE);
        scheduleSubsystemsMementoReapplyAttempt(navigator, 0, -1, 0);
    }

    private static void scheduleSubsystemsMementoReapplyAttempt(
            IViewPart navigator, int attempt, int lastChecked, int stableHits)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 150 : MEMENTO_REAPPLY_DELAY_MS;
        display.timerExec(delay, () -> {
            if (navigator.getSite() == null)
                return;
            CommonViewer viewer = getCommonViewer(navigator);
            Tree tree = viewer != null ? viewer.getTree() : null;
            if (tree == null || tree.isDisposed())
                return;

            int checked = tryReapplySubsystemsMemento(navigator, attempt);
            if (checked == -1)
            {
                tree.setData(MEMENTO_REAPPLY_MARKER, Boolean.FALSE);
                return;
            }
            if (checked == -2)
            {
                if (attempt < MEMENTO_REAPPLY_MAX_ATTEMPTS)
                    scheduleSubsystemsMementoReapplyAttempt(navigator, attempt + 1, lastChecked, 0);
                else
                    tree.setData(MEMENTO_REAPPLY_MARKER, Boolean.FALSE);
                return;
            }

            int nextStable = (checked == lastChecked && lastChecked >= 0) ? stableHits + 1 : 0;
            boolean done = nextStable >= MEMENTO_REAPPLY_STABLE_HITS
                    || attempt >= MEMENTO_REAPPLY_MAX_ATTEMPTS;
            if (done)
            {
                tree.setData(MEMENTO_REAPPLY_MARKER, Boolean.FALSE);
                rebindNavigatorBridge(navigator, "mementoReapplyDone"); //$NON-NLS-1$
                return;
            }
            scheduleSubsystemsMementoReapplyAttempt(navigator, attempt + 1, checked, nextStable);
        });
    }

    /**
     * @return число checkedSubsystemIds после apply; {@code -1} нет data; {@code -2} ещё не готово
     */
    private static int tryReapplySubsystemsMemento(IViewPart navigator, int attempt)
    {
        Object data = Global.invoke(navigator, "getFilterBySubsystemsData"); //$NON-NLS-1$
        if (data == null)
            return -1;
        Object manager = Global.getField(navigator, "filterBySubsystemsManager"); //$NON-NLS-1$
        if (manager == null)
            return -1;

        CommonViewer viewer = getCommonViewer(navigator);
        ViewerFilter nativeFilter = viewer != null ? resolveNativeFilter(navigator, viewer) : null;
        if (nativeFilter != null)
        {
            Object canStart = Global.invoke(nativeFilter, "canStartFiltering", data); //$NON-NLS-1$
            if (!Boolean.TRUE.equals(canStart))
            {
                return -2;
            }
        }

        Object newSettings = Global.invoke(manager, "getFilterSettings", data); //$NON-NLS-1$
        if (newSettings == null)
            return -2;

        int checkedActual = countCheckedSubsystemIds(newSettings);
        Global.setField(navigator, "filterBySubsystemsSettings", newSettings); //$NON-NLS-1$
        if (nativeFilter != null)
        {
            Global.setField(nativeFilter, "filterBySubsystemsSettings", newSettings); //$NON-NLS-1$
            invalidateNativeFilterCaches(nativeFilter, newSettings);
        }

        return checkedActual;
    }

    private static void invalidateNativeFilterCaches(ViewerFilter nativeFilter, Object settings)
    {
        Object infos = Global.getField(nativeFilter, "navigatorFilterInfos"); //$NON-NLS-1$
        if (infos instanceof Map<?, ?> map)
            map.clear();
        Object recalcObj = Global.getField(nativeFilter, "projectsForRecalculating"); //$NON-NLS-1$
        if (!(recalcObj instanceof Map<?, ?>))
            return;
        @SuppressWarnings("unchecked")
        Map<Object, Object> recalc = (Map<Object, Object>) recalcObj;
        Object allProjects = Global.invoke(settings, "getAllProjects"); //$NON-NLS-1$
        if (!(allProjects instanceof Set<?> projects))
            return;
        for (Object dtProject : projects)
        {
            if (dtProject == null)
                continue;
            Object nameObj = Global.invoke(dtProject, "getName"); //$NON-NLS-1$
            if (!(nameObj instanceof String name) || name.isEmpty())
                continue;
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
            if (project != null && project.exists())
                recalc.put(project, Boolean.TRUE);
        }
    }

    private static int countCheckedSubsystemIds(Object settings)
    {
        Object allProjects = Global.invoke(settings, "getAllProjects"); //$NON-NLS-1$
        if (!(allProjects instanceof Set<?> projects))
            return 0;
        int total = 0;
        for (Object project : projects)
        {
            Object ids = Global.invoke(settings, "getCheckedSubsystemIds", project); //$NON-NLS-1$
            if (ids instanceof Set<?> set)
                total += set.size();
        }
        return total;
    }

    private static void rebindAllNavigatorBridges(String source)
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
                if (view != null)
                    rebindNavigatorBridge(view, source);
            }
        }
    }

    /** После очистки поиска/смены фильтров viewer — снова подхватить native в обёртку. */
    static void rebindNavigatorBridge(IViewPart navigator, String source)
    {
        if (navigator == null)
            return;
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
            return;
        installBridge(navigator, viewer);
        viewer.refresh();
    }

    /**
     * Как {@link #rebindNavigatorBridge}, но без {@code refresh} — когда вызывающий уже
     * обновляет viewer (активация штатного поиска).
     */
    static void adoptNativeAfterFilterUiChange(IViewPart navigator, String source)
    {
        if (navigator == null)
            return;
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
            return;
        installBridge(navigator, viewer);
    }

    /**
     * Объект (или потомок top-объекта) из выбранных подсистем при чёрном списке — не показывать
     * и не класть в поисковый trie навигатора.
     */
    static boolean isHiddenBySubsystemBlacklist(IProject project, QualifiedName path)
    {
        if (project == null || path == null || path.isEmpty())
            return false;
        ViewerFilter nativeFilter = findActiveNativeSubsystemsFilter();
        if (nativeFilter == null)
            return false;
        Object settings = Global.getField(nativeFilter, "filterBySubsystemsSettings"); //$NON-NLS-1$
        if (!FilterBySubsystemsDialogHook.isBlacklistMode(settings))
            return false;
        IEObjectTrie trie = resolveSelectedSubsystemsTrie(nativeFilter, project);
        if (trie == null)
            return false;
        boolean hidden = isCoveredBySelectedSubsystemsTrie(trie, path);
        return hidden;
    }

    private static ViewerFilter findActiveNativeSubsystemsFilter()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        IViewPart navigator = page.findView(Global.NAVIGATOR_VIEW_ID);
        if (navigator == null)
            return null;
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
            return null;
        CombinedSubsystemsFilter wrapper = findWrapperFilter(viewer);
        if (wrapper != null && isNativeSubsystemsFilter(wrapper.nativeFilter))
            return wrapper.nativeFilter;
        return resolveNativeFilter(navigator, viewer);
    }

    private static IEObjectTrie resolveSelectedSubsystemsTrie(ViewerFilter nativeFilter, IProject project)
    {
        Global.invoke(nativeFilter, "calculateFilteredInfo", project); //$NON-NLS-1$
        Object infosObj = Global.getField(nativeFilter, "navigatorFilterInfos"); //$NON-NLS-1$
        if (!(infosObj instanceof Map<?, ?> infos))
            return null;
        Object info = infos.get(project);
        if (info == null)
            return null;
        Object trie = Global.getField(info, "filteredObjectTrie"); //$NON-NLS-1$
        return trie instanceof IEObjectTrie ? (IEObjectTrie) trie : null;
    }

    /**
     * Путь покрыт trie выбранных подсистем: точное вхождение или префикс top-объекта
     * ({@code Type.Name…}, ≥2 сегмента). Один сегмент ({@code Catalog}) — только промежуточный
     * узел trie, не скрывает соседей вне подсистем.
     */
    private static boolean isCoveredBySelectedSubsystemsTrie(IEObjectTrie trie, QualifiedName path)
    {
        QualifiedName cur = path;
        while (true)
        {
            if (cur.getSegmentCount() >= 2 && trie.belongsTo(cur))
                return true;
            if (cur.getSegmentCount() <= 1)
                return false;
            cur = cur.skipLast(1);
        }
    }

    private static void installBridge(IViewPart navigator, CommonViewer viewer)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        CombinedSubsystemsFilter existing = findWrapperFilter(viewer);
        syncExpandPreCheckFilters(viewer);
        if (existing != null)
        {
            rebindNativeIntoWrapper(navigator, viewer, existing);
            if (tree != null)
            {
                tree.setData(WRAPPER_MARKER, Boolean.TRUE);
                installGroupExpandSyncListener(navigator, viewer, tree);
            }
            return;
        }
        if (tree != null)
            tree.setData(WRAPPER_MARKER, Boolean.FALSE);

        ViewerFilter nativeFilter = resolveNativeFilter(navigator, viewer);
        boolean nativeMissing = nativeFilter == null;
        if (nativeFilter == null)
            nativeFilter = PASS_THROUGH_NATIVE;

        CombinedSubsystemsFilter wrapper = new CombinedSubsystemsFilter(nativeFilter, nativeMissing);
        if (!nativeMissing)
            viewer.removeFilter(nativeFilter);
        viewer.addFilter(wrapper);
        if (tree != null)
        {
            tree.setData(WRAPPER_MARKER, Boolean.TRUE);
            installGroupExpandSyncListener(navigator, viewer, tree);
        }
        ObjectSetsDebug.step("bridge", nativeMissing //$NON-NLS-1$
            ? "installed CombinedSubsystemsFilter (pass-through native)" //$NON-NLS-1$
            : "installed CombinedSubsystemsFilter"); //$NON-NLS-1$
    }

    /**
     * Чтобы плюс у папки учитывал ViewerFilter. Поле, а не {@code setExpandPreCheckFilters}:
     * публичный сеттер сам делает {@code refresh()}.
     */
    private static void syncExpandPreCheckFilters(CommonViewer viewer)
    {
        if (!(viewer instanceof TreeViewer treeViewer))
            return;
        boolean want = ObjectSetsNavigatorFilterSupport.isActive();
        Object current = Global.getField(treeViewer, "isExpandableCheckFilters"); //$NON-NLS-1$
        if (Boolean.valueOf(want).equals(current))
            return;
        Global.setField(treeViewer, "isExpandableCheckFilters", want); //$NON-NLS-1$
    }

    /**
     * Штатный {@code NavigatorSubsystemsFilter} после активации снова оказывается в
     * {@code viewer.getFilters()} рядом с обёрткой — убрать дубликат и подставить в wrapper.
     */
    private static void rebindNativeIntoWrapper(
            IViewPart navigator, CommonViewer viewer, CombinedSubsystemsFilter wrapper)
    {
        ViewerFilter adopted = null;
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter == wrapper || !isNativeSubsystemsFilter(filter))
                continue;
            viewer.removeFilter(filter);
            adopted = filter;
        }
        if (adopted == null)
        {
            ViewerFilter fromService = resolveNativeFromFilterService(navigator);
            if (fromService != null && fromService != wrapper.nativeFilter
                    && fromService != PASS_THROUGH_NATIVE)
                adopted = fromService;
        }
        if (adopted == null)
            return;
        wrapper.nativeFilter = adopted;
        wrapper.passThroughNative = false;
    }

    private static ViewerFilter resolveNativeFilter(IViewPart navigator, CommonViewer viewer)
    {
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter instanceof CombinedSubsystemsFilter combined)
                return combined.nativeFilter == PASS_THROUGH_NATIVE ? null : combined.nativeFilter;
            if (isNativeSubsystemsFilter(filter))
                return filter;
        }
        return resolveNativeFromFilterService(navigator);
    }

    private static ViewerFilter resolveNativeFromFilterService(IViewPart navigator)
    {
        if (!(navigator instanceof CommonNavigator commonNavigator))
            return null;
        INavigatorContentService contentService = commonNavigator.getNavigatorContentService();
        if (contentService == null)
            return null;
        INavigatorFilterService filterService = contentService.getFilterService();
        if (filterService == null)
            return null;
        ICommonFilterDescriptor[] descriptors = filterService.getVisibleFilterDescriptors();
        if (descriptors == null)
            return null;
        for (ICommonFilterDescriptor descriptor : descriptors)
        {
            if (descriptor == null || !NATIVE_FILTER_ID.equals(descriptor.getId()))
                continue;
            ViewerFilter filter = filterService.getViewerFilter(descriptor);
            if (isNativeSubsystemsFilter(filter))
                return filter;
        }
        return null;
    }

    private static boolean isNativeSubsystemsFilter(ViewerFilter filter)
    {
        return filter != null && NATIVE_FILTER_CLASS.equals(filter.getClass().getName());
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }

    /**
     * После Ctrl+T штатный reveal заново ставит hasChildren у уже показанных папок
     * (фильтр не влияет на ITreeContentProvider.hasChildren). Убираем кнопку «>»
     * только у уже построенных узлов — без полного обхода модели.
     */
    static void scheduleSyncVisibleChevrons()
    {
        if (!ObjectSetsNavigatorFilterSupport.isActive())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(ObjectSetSubsystemsFilterBridge::syncVisibleEmptyGroupChevrons);
        display.timerExec(0, ObjectSetSubsystemsFilterBridge::syncVisibleEmptyGroupChevrons);
        scheduleDebouncedVisibleChevronStrip();
    }

    private static void scheduleDebouncedVisibleChevronStrip()
    {
        if (!ObjectSetsNavigatorFilterSupport.isActive())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int gen = ++visibleChevronStripGen;
        display.timerExec(80, () -> {
            if (gen != visibleChevronStripGen)
                return;
            syncVisibleEmptyGroupChevrons();
        });
    }

    private static void syncVisibleEmptyGroupChevrons()
    {
        if (!ObjectSetsNavigatorFilterSupport.isActive() || !PlatformUI.isWorkbenchRunning())
            return;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
                if (view != null)
                    syncMaterializedExpandIndicators(getCommonViewer(view));
            }
        }
    }

    private static void syncMaterializedExpandIndicators(CommonViewer viewer)
    {
        if (!ObjectSetsNavigatorFilterSupport.isActive() || viewer == null)
            return;
        if (!(viewer instanceof TreeViewer treeViewer))
            return;
        CombinedSubsystemsFilter wrapper = findWrapperFilter(viewer);
        if (wrapper == null)
            return;
        var contentProvider = viewer.getContentProvider();
        if (!(contentProvider instanceof ITreeContentProvider treeContentProvider))
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        syncMaterializedTreeItems(tree, treeViewer, viewer, treeContentProvider, wrapper);
    }

    /**
     * У пустых узлов-групп EDT (не объектов МД) убирает кнопку разворачивания.
     */
    private static void syncGroupExpandIndicators(CommonViewer viewer)
    {
        if (!ObjectSetsNavigatorFilterSupport.isActive() || viewer == null)
            return;
        if (!(viewer instanceof TreeViewer treeViewer))
            return;
        CombinedSubsystemsFilter wrapper = findWrapperFilter(viewer);
        if (wrapper == null)
            return;
        var contentProvider = viewer.getContentProvider();
        if (!(contentProvider instanceof ITreeContentProvider treeContentProvider))
            return;
        Object input = viewer.getInput();
        if (input == null)
            return;
        syncGroupExpandLevel(
            treeViewer, viewer, treeContentProvider, wrapper, treeContentProvider.getElements(input));
        Tree tree = viewer.getTree();
        if (tree != null && !tree.isDisposed())
            syncMaterializedTreeItems(tree, treeViewer, viewer, treeContentProvider, wrapper);
    }

    private static void installGroupExpandSyncListener(IViewPart navigator, CommonViewer viewer, Tree tree)
    {
        if (tree == null || Boolean.TRUE.equals(tree.getData(EXPAND_SYNC_MARKER)))
            return;
        tree.addTreeListener(new TreeAdapter()
        {
            @Override
            public void treeExpanded(TreeEvent event)
            {
                if (!ObjectSetsNavigatorFilterSupport.isActive())
                    return;
                CombinedSubsystemsFilter wrapper = findWrapperFilter(viewer);
                var contentProvider = viewer.getContentProvider();
                if (wrapper == null || !(contentProvider instanceof ITreeContentProvider tcp))
                    return;
                if (!(viewer instanceof TreeViewer treeViewer))
                    return;
                Object element = event.item != null ? event.item.getData() : null;
                if (element != null && treeContentProviderHasChildren(tcp, element))
                {
                    Object[] rawChildren = tcp.getChildren(element);
                    Object[] visible = filterVisibleChildren(viewer, wrapper, element, rawChildren);
                    syncGroupExpandLevel(treeViewer, viewer, tcp, wrapper, visible);
                }
                if (event.item instanceof TreeItem treeItem)
                    syncMaterializedTreeItemsFromItem(treeItem, treeViewer, viewer, tcp, wrapper);
                scheduleDebouncedVisibleChevronStrip();
            }
        });
        tree.setData(EXPAND_SYNC_MARKER, Boolean.TRUE);
    }

    private static boolean treeContentProviderHasChildren(ITreeContentProvider tcp, Object element)
    {
        return tcp != null && element != null && tcp.hasChildren(element);
    }

    private static void syncMaterializedTreeItems(
            Tree tree,
            TreeViewer treeViewer,
            Viewer viewer,
            ITreeContentProvider treeContentProvider,
            CombinedSubsystemsFilter wrapper)
    {
        for (TreeItem item : tree.getItems())
            syncMaterializedTreeItemsFromItem(item, treeViewer, viewer, treeContentProvider, wrapper);
    }

    private static void syncMaterializedTreeItemsFromItem(
            TreeItem item,
            TreeViewer treeViewer,
            Viewer viewer,
            ITreeContentProvider treeContentProvider,
            CombinedSubsystemsFilter wrapper)
    {
        if (item == null || item.isDisposed())
            return;
        stripEmptyGroupChevron(item, treeViewer, viewer, treeContentProvider, wrapper);
        for (TreeItem child : item.getItems())
            syncMaterializedTreeItemsFromItem(child, treeViewer, viewer, treeContentProvider, wrapper);
    }

    /**
     * Только один уровень: у переданных узлов снимаем «&gt;», вглубь не идём. Рекурсия по всей
     * модели заставляла провайдер EDT построить всё поддерево («Общие» — тысячи узлов, ~1 с на
     * разворот), тогда как дерево строит детей лениво. Плюс у потомков пересчитывается своим
     * {@code treeExpanded}, когда их родителя реально развернут.
     */
    private static void syncGroupExpandLevel(
            TreeViewer treeViewer,
            Viewer viewer,
            ITreeContentProvider treeContentProvider,
            CombinedSubsystemsFilter wrapper,
            Object[] elements)
    {
        if (elements == null)
            return;
        for (Object element : elements)
        {
            if (element == null)
                continue;
            applyGroupExpandIndicator(treeViewer, viewer, treeContentProvider, wrapper, element);
        }
    }

    /**
     * У пустых групп EDT после фильтра — без кнопки «&gt;». Только {@code setHasChildren(false)};
     * не вызывать {@code setHasChildren(true)} — ломает отображение детей у непустых веток.
     */
    private static void applyGroupExpandIndicator(
            TreeViewer treeViewer,
            Viewer viewer,
            ITreeContentProvider treeContentProvider,
            CombinedSubsystemsFilter wrapper,
            Object element)
    {
        if (element == null || !NavigatorTreeElementLabels.isGroupNode(element))
            return;
        Object[] rawChildren = treeContentProviderHasChildren(treeContentProvider, element)
            ? treeContentProvider.getChildren(element) : new Object[0];
        Object[] visibleChildren = filterVisibleChildren(viewer, wrapper, element, rawChildren);
        if (visibleChildren.length == 0)
            treeViewer.setHasChildren(element, false);
    }

    /**
     * Свёрнутая пустая папка держит SWT-dummy ребёнка — из-за него плюс не снимается
     * через {@link TreeViewer#setHasChildren(Object, boolean)} (поиск виджета по element).
     */
    private static void stripEmptyGroupChevron(
            TreeItem item,
            TreeViewer treeViewer,
            Viewer viewer,
            ITreeContentProvider treeContentProvider,
            CombinedSubsystemsFilter wrapper)
    {
        if (item == null || item.isDisposed())
            return;
        Object element = item.getData();
        if (element == null || !NavigatorTreeElementLabels.isGroupNode(element))
            return;
        Object[] rawChildren = treeContentProviderHasChildren(treeContentProvider, element)
            ? treeContentProvider.getChildren(element) : new Object[0];
        Object[] visibleChildren = filterVisibleChildren(viewer, wrapper, element, rawChildren);
        if (visibleChildren.length > 0)
            return;
        for (TreeItem kid : item.getItems())
        {
            if (!kid.isDisposed())
                kid.dispose();
        }
        if (!item.isDisposed())
            treeViewer.setHasChildren(element, false);
    }

    private static Object[] filterVisibleChildren(
            Viewer viewer, CombinedSubsystemsFilter wrapper, Object parent, Object[] children)
    {
        if (children == null || children.length == 0)
            return new Object[0];
        Object[] visible = new Object[children.length];
        int count = 0;
        for (Object child : children)
        {
            if (child != null && wrapper.select(viewer, parent, child))
                visible[count++] = child;
        }
        if (count == visible.length)
            return visible;
        Object[] trimmed = new Object[count];
        System.arraycopy(visible, 0, trimmed, 0, count);
        return trimmed;
    }

    private static CombinedSubsystemsFilter findWrapperFilter(CommonViewer viewer)
    {
        if (viewer == null)
            return null;
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter instanceof CombinedSubsystemsFilter wrapper)
                return wrapper;
        }
        return null;
    }

    static final class CombinedSubsystemsFilter extends ViewerFilter
    {
        ViewerFilter nativeFilter;
        boolean passThroughNative;

        CombinedSubsystemsFilter(ViewerFilter nativeFilter, boolean passThroughNative)
        {
            this.nativeFilter = nativeFilter;
            this.passThroughNative = passThroughNative;
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            ViewerFilter effective = adoptNativeIfPresent(viewer);
            boolean nativeVisible = passThroughNative
                || effective.select(viewer, parentElement, element);

            if (ObjectSetsNavigatorFilterSupport.isActive())
            {
                String projectName = resolveProjectName(viewer, element, parentElement);
                if (projectName != null)
                {
                    ObjectSets.SetDef set =
                        ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
                    if (set != null && (set.system || !set.items.isEmpty()))
                    {
                        // Непустой add-target набор (или системный «<Измененные Git>»)
                        // вытесняет фильтр подсистем (в т.ч. чёрный список).
                        return ObjectSetsItems.isVisibleInAddTargetSetTree(
                            viewer, element, projectName);
                    }
                }
            }

            return applySubsystemBlacklist(viewer, effective, element, nativeVisible);
        }

        /**
         * Убрать с viewer все копии штатного фильтра (после активации EDT снова addFilter'ит)
         * и подставить в эту обёртку.
         */
        private ViewerFilter adoptNativeIfPresent(Viewer viewer)
        {
            if (!(viewer instanceof org.eclipse.jface.viewers.StructuredViewer sv))
                return nativeFilter;
            ViewerFilter adopted = null;
            for (ViewerFilter filter : sv.getFilters())
            {
                if (filter == this || !isNativeSubsystemsFilter(filter))
                    continue;
                sv.removeFilter(filter);
                adopted = filter;
            }
            if (adopted != null)
            {
                nativeFilter = adopted;
                passThroughNative = false;
            }
            return nativeFilter;
        }

        /**
         * Инверсия штатного белого списка только для top-объектов МД.
         * Папки-коллекции ({@code CatalogNavigatorAdapter$Folder}, «Общие»…) не инвертируем:
         * штатный фильтр показывает папку, если есть ХОТЯ БЫ один объект из выбранных подсистем;
         * при {@code !nativeVisible} ветка вроде «Справочники» пропадала целиком, хотя почти все
         * объекты вне чёрного списка. Видимость папки — через отфильтрованных детей.
         */
        private static boolean applySubsystemBlacklist(
                Viewer viewer, ViewerFilter nativeFilter, Object element, boolean nativeVisible)
        {
            Object settings = resolveBlacklistSettings(viewer, nativeFilter);
            boolean blacklist = FilterBySubsystemsDialogHook.isBlacklistMode(settings);
            if (!blacklist)
                return nativeVisible;
            if (element instanceof IProject)
                return true;
            String typeName = element != null ? element.getClass().getName() : ""; //$NON-NLS-1$
            if (typeName.endsWith(".Configuration") //$NON-NLS-1$
                    || typeName.endsWith(".ConfigurationImpl")) //$NON-NLS-1$
                return true;
            if (isNavigatorCollectionFolder(nativeFilter, element, typeName))
                return true;
            Object top = Global.invoke(nativeFilter, "isTopElement", element); //$NON-NLS-1$
            if (!Boolean.TRUE.equals(top))
                return true;
            boolean visible = !nativeVisible;
            if (decideLogsLeft > 0)
            {
                decideLogsLeft--;
            }
            return visible;
        }

        /** Как {@code NavigatorSubsystemsFilter.isTopCollectionNavigatorAdapter}. */
        private static boolean isNavigatorCollectionFolder(
                ViewerFilter nativeFilter, Object element, String typeName)
        {
            Object folder = Global.invoke(nativeFilter, "isTopCollectionNavigatorAdapter", element); //$NON-NLS-1$
            if (Boolean.TRUE.equals(folder))
                return true;
            return typeName.contains("$Folder") //$NON-NLS-1$
                    || typeName.endsWith(".CommonNavigatorAdapter"); //$NON-NLS-1$
        }

        private static int decideLogsLeft = 40;

        private static Object resolveBlacklistSettings(Viewer viewer, ViewerFilter nativeFilter)
        {
            Object settings = Global.getField(nativeFilter, "filterBySubsystemsSettings"); //$NON-NLS-1$
            if (settings != null)
                return settings;
            if (!(viewer instanceof CommonViewer commonViewer))
                return null;
            Object navigator = commonViewer.getCommonNavigator();
            if (navigator == null)
                return null;
            return Global.invoke(navigator, "getFilterBySubsystemsSettings"); //$NON-NLS-1$
        }

        private static String resolveProjectName(Viewer viewer, Object element, Object parent)
        {
            org.eclipse.core.resources.IProject project = projectFromTreePath(parent);
            if (project == null)
                project = projectFromTreePath(element);
            if (project == null)
                project = workspaceProject(element);
            if (project == null)
                project = workspaceProject(parent);
            if (project == null && viewer instanceof TreeViewer treeViewer)
            {
                Object cp = treeViewer.getContentProvider();
                if (cp instanceof ITreeContentProvider treeContentProvider)
                    project = walkToWorkspaceProject(treeContentProvider, element);
            }
            return project != null ? project.getName() : null;
        }

        private static IProject projectFromTreePath(Object element)
        {
            if (!(element instanceof TreePath treePath))
                return null;
            Object first = treePath.getFirstSegment();
            return workspaceProject(first);
        }

        private static IProject workspaceProject(Object element)
        {
            if (element instanceof IProject project)
                return project;
            if (element instanceof IDtProject dtProject)
            {
                org.eclipse.core.resources.IProject ws = dtProject.getWorkspaceProject();
                if (ws != null)
                    return ws;
            }
            org.eclipse.core.resources.IResource resource = NavigatorResourceResolver.resolve(element);
            if (resource != null && resource.getProject() != null)
                return resource.getProject();
            Object dtProject = Global.call(element, "getDtProject"); //$NON-NLS-1$
            if (dtProject instanceof IDtProject dt)
                return dt.getWorkspaceProject();
            Object ws = Global.call(element, "getWorkspaceProject"); //$NON-NLS-1$
            if (ws instanceof IProject project)
                return project;
            return null;
        }

        private static IProject walkToWorkspaceProject(ITreeContentProvider provider, Object element)
        {
            Object current = element;
            while (current != null)
            {
                IProject project = workspaceProject(current);
                if (project != null)
                    return project;
                try
                {
                    current = provider.getParent(current);
                }
                catch (RuntimeException ignored)
                {
                    break;
                }
            }
            return null;
        }
    }
}
