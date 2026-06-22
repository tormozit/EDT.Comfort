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
import java.util.Set;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Обёртка штатного фильтра подсистем в <em>навигаторе</em>: при включённой «звезде» наборов
 * фильтр по непустому add-target набору вытесняет фильтр подсистем.
 *
 * <p>Не путать с {@link FilterBySubsystemsDialogHook}: в диалоге «Отбор / Фильтр по подсистемам»
 * наборы объектов не отображаются (TODO — см. javadoc там).
 */
public final class ObjectSetSubsystemsFilterBridge implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.objectSetSubsystemsBridge"; //$NON-NLS-1$
    private static final String WRAPPER_MARKER = "tormozit.objectSetSubsystemsWrapper"; //$NON-NLS-1$
    private static final String EXPAND_SYNC_MARKER = "tormozit.objectSetExpandSync"; //$NON-NLS-1$
    private static final String NATIVE_FILTER_ID =
        "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSubsystemsFilter"; //$NON-NLS-1$
    private static final String NATIVE_FILTER_CLASS =
        "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSubsystemsFilter"; //$NON-NLS-1$

    private static final String SUBSYSTEMS_FILTER_COMMAND_ID =
        "com._1c.g5.v8.dt.navigator.ui.filterBySubsystems"; //$NON-NLS-1$

    private static boolean resourceListenerInstalled;
    private static boolean competingFilterListenerInstalled;
    private static final Set<String> pendingAutoAddPaths = new HashSet<>();

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
            public void preExecute(String commandId, ExecutionEvent event) { }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                if (!SUBSYSTEMS_FILTER_COMMAND_ID.equals(commandId))
                    return;
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> {
                    if (!ObjectSetsNavigatorFilterSupport.isActive())
                        return;
                    if (isAnyNavigatorSubsystemsFilterActive())
                        ObjectSetsNavigatorFilterSupport.deactivateBecauseCompetingFilter();
                });
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception) { }

            @Override
            public void notHandled(String commandId, NotHandledException exception) { }
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
            refreshAllNavigators();
            if (attempt < 12)
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
            if (attempt < 4)
                scheduleFilterRefresh(navigator, attempt + 1, source);
        });
    }

    static void onFilterStateChanged()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable refresh = ObjectSetSubsystemsFilterBridge::refreshAllNavigators;
        if (display.getThread() == Thread.currentThread())
            refresh.run();
        else
            display.asyncExec(refresh);
    }

    private static void refreshAllNavigators()
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
                    refreshNavigator(view);
            }
        }
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
            @Override public void partOpened(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
        });
    }

    private static void tryHookFromRef(IWorkbenchPartReference ref)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        if (isNavigatorView(part))
        {
            IViewPart navigator = (IViewPart) part;
            tryHook(navigator);
            if (ObjectSetsNavigatorFilterSupport.isActive())
                scheduleFilterRefresh(navigator, 0, "partEvent"); //$NON-NLS-1$
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
        if (Boolean.TRUE.equals(tree.getData(HOOK_MARKER)))
        {
            if (ObjectSetsNavigatorFilterSupport.isActive())
                scheduleFilterRefresh(navigator, 0, "tryHook"); //$NON-NLS-1$
            return;
        }
        installBridge(navigator, viewer);
        tree.setData(HOOK_MARKER, Boolean.TRUE);
        if (ObjectSetsNavigatorFilterSupport.isActive())
            scheduleFilterRefresh(navigator, 0, "tryHookNew"); //$NON-NLS-1$
    }

    private static void installBridge(IViewPart navigator, CommonViewer viewer)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (hasWrapperFilter(viewer))
        {
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

    private static boolean hasWrapperFilter(CommonViewer viewer)
    {
        if (viewer == null)
            return false;
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter instanceof CombinedSubsystemsFilter)
                return true;
        }
        return false;
    }

    private static ViewerFilter resolveNativeFilter(IViewPart navigator, CommonViewer viewer)
    {
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter instanceof CombinedSubsystemsFilter combined)
                return combined.nativeFilter;
            if (isNativeSubsystemsFilter(filter))
                return filter;
        }
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
        syncGroupExpandRecursive(
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
                    syncGroupExpandRecursive(treeViewer, viewer, tcp, wrapper, visible);
                }
                if (event.item instanceof TreeItem treeItem)
                    syncMaterializedTreeItemsFromItem(treeItem, treeViewer, viewer, tcp, wrapper);
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
        Object element = item.getData();
        applyGroupExpandIndicator(treeViewer, viewer, treeContentProvider, wrapper, element);
        for (TreeItem child : item.getItems())
            syncMaterializedTreeItemsFromItem(child, treeViewer, viewer, treeContentProvider, wrapper);
    }

    private static void syncGroupExpandRecursive(
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
            Object[] rawChildren = treeContentProvider.hasChildren(element)
                ? treeContentProvider.getChildren(element) : new Object[0];
            Object[] visibleChildren = filterVisibleChildren(viewer, wrapper, element, rawChildren);
            syncGroupExpandRecursive(
                treeViewer, viewer, treeContentProvider, wrapper, visibleChildren);
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
        final ViewerFilter nativeFilter;
        final boolean passThroughNative;

        CombinedSubsystemsFilter(ViewerFilter nativeFilter, boolean passThroughNative)
        {
            this.nativeFilter = nativeFilter;
            this.passThroughNative = passThroughNative;
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (!ObjectSetsNavigatorFilterSupport.isActive())
                return true;
            String projectName = resolveProjectName(viewer, element, parentElement);
            if (projectName == null)
                return true;
            ObjectSets.SetDef set =
                ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
            if (set == null || set.items.isEmpty())
                return true;
            return ObjectSetsItems.isVisibleInAddTargetSetTree(
                viewer, element, projectName);
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
