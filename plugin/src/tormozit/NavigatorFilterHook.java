package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
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
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Умный фильтр и подсветка в навигаторе EDT ({@code com._1c.g5.v8.dt.ui2.navigator}).
 * Сортировка навигатора не меняется (без {@link SmartOutlineComparator}).
 */
public final class NavigatorFilterHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.navigatorFilterPatched"; //$NON-NLS-1$
    private static final String LAST_PATTERN_KEY = "tormozit.navigatorLastPattern"; //$NON-NLS-1$
    private static final String SEARCH_CACHE_KEY = "tormozit.navigatorSearchCache"; //$NON-NLS-1$
    private static final String STORED_NAV_FILTERS_KEY = "tormozit.storedNavFilters"; //$NON-NLS-1$
    private static final String NAV_SEARCH_FILTER = "NavigatorSearchFilter"; //$NON-NLS-1$
    private static volatile String lastGiveUpReason = ""; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            NavigatorFilterDebug.log("install window listener"); //$NON-NLS-1$
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow window)
                {
                    hookWindow(window);
                }

                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        int refs = 0;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                refs++;
                IViewPart view = ref.getView(false);
                if (isNavigatorView(view))
                    schedulePatch(view, 0, "viewReferences"); //$NON-NLS-1$
            }
        }
        NavigatorFilterDebug.log("hookWindow refs=" + refs); //$NON-NLS-1$

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                scheduleFromRef(ref, "partOpened"); //$NON-NLS-1$
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                scheduleFromRef(ref, "partVisible"); //$NON-NLS-1$
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                scheduleFromRef(ref, "partActivated"); //$NON-NLS-1$
            }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void scheduleFromRef(IWorkbenchPartReference ref, String source)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        if (isNavigatorView(part))
            schedulePatch((IViewPart) part, 0, source);
    }

    private static boolean isNavigatorView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return Global.NAVIGATOR_VIEW_ID.equals(id)
                || part.getClass().getName().contains("internal.navigator.ui.Navigator"); //$NON-NLS-1$
    }

    private static void schedulePatch(IViewPart view, int attempt, String source)
    {
        if (view == null)
            return;
        if (attempt == 0)
        {
            NavigatorFilterDebug.log("schedule source=" + source //$NON-NLS-1$
                    + " viewId=" + view.getViewSite().getId() //$NON-NLS-1$
                    + " class=" + view.getClass().getName()); //$NON-NLS-1$
        }
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () -> {
            if (!tryPatch(view, attempt) && attempt < 20)
                schedulePatch(view, attempt + 1, source);
            else if (attempt >= 20)
                NavigatorFilterDebug.log("patch GIVE UP viewId=" + view.getViewSite().getId() //$NON-NLS-1$
                        + " reason=" + lastGiveUpReason); //$NON-NLS-1$
        });
    }

    private static boolean tryPatch(IViewPart navigator, int attempt)
    {
        String viewId = navigator.getViewSite().getId();
        if (attempt == 0 || attempt % 3 == 0)
        {
            NavigatorFilterDebug.log("try#" + attempt + " viewId=" + viewId //$NON-NLS-1$ //$NON-NLS-2$
                    + " class=" + navigator.getClass().getName()); //$NON-NLS-1$
        }

        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
        {
            lastGiveUpReason = "viewer=null"; //$NON-NLS-1$
            if (attempt == 0 || attempt % 3 == 0)
                NavigatorFilterDebug.log("try#" + attempt + " WAIT " + lastGiveUpReason); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
        {
            lastGiveUpReason = "tree=null"; //$NON-NLS-1$
            if (attempt == 0 || attempt % 3 == 0)
                NavigatorFilterDebug.log("try#" + attempt + " WAIT " + lastGiveUpReason); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        if (tree.getData(PATCHED_KEY) != null)
        {
            NavigatorFilterDebug.log("try#" + attempt + " SKIP already patched"); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }

        Object searchBox = Global.getField(navigator, "searchBox"); //$NON-NLS-1$
        if (searchBox == null)
        {
            lastGiveUpReason = "searchBox=null"; //$NON-NLS-1$
            if (attempt == 0 || attempt % 3 == 0)
                NavigatorFilterDebug.log("try#" + attempt + " WAIT " + lastGiveUpReason); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        SearchBoxFilterAccess searchInput = SearchBoxFilterAccess.resolve(navigator, searchBox);
        if (searchInput == null)
        {
            lastGiveUpReason = "searchInput=null"; //$NON-NLS-1$
            return false;
        }
        if (attempt == 0 || attempt % 3 == 0)
            NavigatorFilterDebug.log("try#" + attempt + " searchInput mode=" + searchInput.mode() //$NON-NLS-1$ //$NON-NLS-2$
                    + " " + SearchBoxFilterAccess.describe(searchBox)); //$NON-NLS-1$

        IBaseLabelProvider rawLp = resolveNavigatorLabelProvider(navigator, viewer);
        if (rawLp == null)
        {
            lastGiveUpReason = "labelProvider=null"; //$NON-NLS-1$
            if (attempt == 0 || attempt % 3 == 0)
                NavigatorFilterDebug.log("try#" + attempt + " WAIT " + lastGiveUpReason); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        if (rawLp instanceof NavigatorStyledCellLabelWrapper || rawLp instanceof SmartStyledCellLabelWrapper)
        {
            NavigatorFilterDebug.log("try#" + attempt + " SKIP already wrapped"); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }

        String initialPattern = searchInput.readPattern();
        boolean initialFiltering = !initialPattern.isEmpty();
        if (initialFiltering)
            deactivateNavigatorFilters(navigator, viewer);

        final IBaseLabelProvider labelBase = rawLp;
        final NavigatorSearchTextCache searchCache = new NavigatorSearchTextCache();
        ILabelProvider filterLabels = new ILabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return searchCache.searchText(element, labelBase);
            }

            @Override
            public org.eclipse.swt.graphics.Image getImage(Object element)
            {
                if (labelBase instanceof ILabelProvider)
                    return ((ILabelProvider) labelBase).getImage(element);
                Object img = Global.invoke(labelBase, "getImage", element); //$NON-NLS-1$
                return img instanceof org.eclipse.swt.graphics.Image
                        ? (org.eclipse.swt.graphics.Image) img : null;
            }

            @Override public void addListener(org.eclipse.jface.viewers.ILabelProviderListener listener)
            {
                if (labelBase != null)
                    labelBase.addListener(listener);
            }

            @Override public void dispose() {}

            @Override public boolean isLabelProperty(Object element, String property)
            {
                return labelBase != null && labelBase.isLabelProperty(element, property);
            }

            @Override public void removeListener(org.eclipse.jface.viewers.ILabelProviderListener listener)
            {
                if (labelBase != null)
                    labelBase.removeListener(listener);
            }
        };

        SmartOutlineFilter smartFilter = new SmartOutlineFilter(filterLabels, true, false);
        smartFilter.setPattern(initialPattern);

        SmartLabelHighlight highlight = installNavigatorHighlight(viewer, rawLp, filterLabels, searchCache, initialPattern);
        if (highlight == null)
        {
            lastGiveUpReason = "highlight=null lp=" + rawLp.getClass().getName(); //$NON-NLS-1$
            if (attempt == 0 || attempt % 3 == 0)
                NavigatorFilterDebug.log("try#" + attempt + " WAIT " + lastGiveUpReason); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        if (initialFiltering)
            viewer.addFilter(smartFilter);

        ViewerComparator comparator = viewer.getComparator();
        NavigatorFilterDebug.log("try#" + attempt + " PATCH OK mode=" + searchInput.mode() //$NON-NLS-1$ //$NON-NLS-2$
                + " lp=" + rawLp.getClass().getSimpleName() //$NON-NLS-1$
                + " comparator=" + (comparator != null ? comparator.getClass().getSimpleName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                + " " + NavigatorFilterDebug.filtersDesc(viewer) //$NON-NLS-1$
                + " pattern=\"" + initialPattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        logNativeSearchState(navigator);

        applyFilter(navigator, viewer, smartFilter, highlight, searchCache, initialPattern);

        final Runnable[] pending = new Runnable[1];
        final SearchBoxFilterAccess input = searchInput;
        Control focusControl = searchInput.focusControl();
        searchInput.disableNativeAutoSearch();
        boolean listenerOk = searchInput.attachPatternListener(navigator, explicitPattern -> {
            Display display = Display.getDefault();
            if (pending[0] != null)
                display.timerExec(-1, pending[0]);
            pending[0] = () -> {
                String pattern = explicitPattern != null ? explicitPattern : input.readPattern();
                NavigatorFilterDebug.log("modify pattern=\"" + pattern + "\" mode=" + input.mode() //$NON-NLS-1$ //$NON-NLS-2$
                        + (explicitPattern != null ? " explicit" : " read")); //$NON-NLS-1$ //$NON-NLS-2$
                applyFilter(navigator, viewer, smartFilter, highlight, searchCache, pattern);
            };
            display.timerExec(150, pending[0]);
        });
        NavigatorFilterDebug.log("patternListener attached=" + listenerOk + " mode=" + input.mode()); //$NON-NLS-1$ //$NON-NLS-2$

        if (focusControl != null)
        {
            FilterFieldListNavigation.installTreeNavigation(focusControl, tree);
            focusControl.addDisposeListener(e -> {
                if (pending[0] != null && !focusControl.getDisplay().isDisposed())
                    focusControl.getDisplay().timerExec(-1, pending[0]);
            });
        }

        tree.setData(PATCHED_KEY, Boolean.TRUE);
        tree.setData(SEARCH_CACHE_KEY, searchCache);
        return true;
    }

    private static SmartLabelHighlight installNavigatorHighlight(CommonViewer viewer, IBaseLabelProvider rawLp,
            ILabelProvider filterLabels, NavigatorSearchTextCache searchCache, String initialPattern)
    {
        if (rawLp instanceof StyledCellLabelProvider)
        {
            NavigatorStyledCellLabelWrapper wrapper =
                    new NavigatorStyledCellLabelWrapper((StyledCellLabelProvider) rawLp, searchCache);
            wrapper.setHighlightPattern(initialPattern);
            viewer.setLabelProvider(wrapper);
            return wrapper;
        }

        IStyledLabelProvider innerStyled = null;
        if (rawLp instanceof DelegatingStyledCellLabelProvider)
            innerStyled = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
        else if (rawLp instanceof IStyledLabelProvider)
            innerStyled = (IStyledLabelProvider) rawLp;

        SmartOutlineLabelProvider smartLp = innerStyled != null
                ? new SmartOutlineLabelProvider(innerStyled, filterLabels)
                : new SmartOutlineLabelProvider(null, filterLabels);
        smartLp.setHighlightPattern(initialPattern);

        if (innerStyled != null && rawLp instanceof DelegatingStyledCellLabelProvider)
        {
            injectStyledStringProvider((DelegatingStyledCellLabelProvider) rawLp, smartLp);
            return smartLp;
        }
        viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(smartLp));
        return smartLp;
    }

    private static void injectStyledStringProvider(DelegatingStyledCellLabelProvider provider,
            IStyledLabelProvider smartProvider)
    {
        Class<?> cls = provider.getClass();
        while (cls != null)
        {
            for (java.lang.reflect.Field field : cls.getDeclaredFields())
            {
                if (IStyledLabelProvider.class.isAssignableFrom(field.getType()))
                {
                    try
                    {
                        field.setAccessible(true);
                        field.set(provider, smartProvider);
                        return;
                    }
                    catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private static IBaseLabelProvider resolveNavigatorLabelProvider(IViewPart navigator, CommonViewer viewer)
    {
        IBaseLabelProvider lp = viewer.getLabelProvider();
        if (lp instanceof StyledCellLabelProvider || lp instanceof DelegatingStyledCellLabelProvider)
            return lp;
        if (lp instanceof ILabelProvider)
            return lp;

        for (String field : new String[] { "tree", "navigatorTree", "treeComponent", "contentArea" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object comp = Global.getField(navigator, field);
            if (comp == null)
                continue;
            Object nestedLp = Global.invoke(comp, "getLabelProvider"); //$NON-NLS-1$
            if (nestedLp instanceof IBaseLabelProvider)
                return (IBaseLabelProvider) nestedLp;
        }
        return lp;
    }

    private static void applyFilter(IViewPart navigator, CommonViewer viewer, SmartOutlineFilter smartFilter,
            SmartLabelHighlight highlight, NavigatorSearchTextCache searchCache, String pattern)
    {
        if (viewer.getControl() == null || viewer.getControl().isDisposed())
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        if (searchCache != null)
            searchCache.onPatternChanged(safePattern);
        boolean filtering = !safePattern.isEmpty();
        Object lastObj = tree.getData(LAST_PATTERN_KEY);
        String lastPattern = lastObj instanceof String ? (String) lastObj : ""; //$NON-NLS-1$
        boolean clearingFilter = !filtering && !lastPattern.isEmpty();
        IStructuredSelection savedSelection = null;
        if (!filtering && viewer.getSelection() instanceof IStructuredSelection)
            savedSelection = (IStructuredSelection) viewer.getSelection();
        tree.setRedraw(false);
        try
        {
            smartFilter.refreshPattern(safePattern);
            highlight.setHighlightPattern(safePattern);
            if (filtering)
            {
                ensureSmartFilter(viewer, smartFilter);
                suppressNativeSearch(navigator, viewer);
            }
            else
            {
                removeSmartFilter(viewer, smartFilter);
                if (clearingFilter)
                    resetNavigatorContentState(navigator, viewer);
            }
            viewer.refresh();
            // refresh() навигатора может сбросить фильтры
            if (filtering)
            {
                ensureSmartFilter(viewer, smartFilter);
                suppressNativeSearch(navigator, viewer);
                smartFilter.applyTreeExpansion(viewer);
                viewer.refresh();
            }
            else
                restoreTreeStateAfterClear(viewer, smartFilter, savedSelection);
            NavigatorFilterDebug.log("refresh pattern=\"" + safePattern + "\" treeItems=" + tree.getItemCount() //$NON-NLS-1$ //$NON-NLS-2$
                    + " " + NavigatorFilterDebug.filtersDesc(viewer)); //$NON-NLS-1$
            if (navigator != null)
                logNativeSearchState(navigator);
            tree.setData(LAST_PATTERN_KEY, safePattern);
        }
        finally
        {
            tree.setRedraw(true);
        }
    }

    private static void ensureSmartFilter(CommonViewer viewer, SmartOutlineFilter smartFilter)
    {
        if (viewer == null || smartFilter == null)
            return;
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter == smartFilter)
                return;
        }
        viewer.addFilter(smartFilter);
    }

    private static void restoreTreeStateAfterClear(CommonViewer viewer, SmartOutlineFilter smartFilter,
            IStructuredSelection selection)
    {
        Set<Object> toExpand = new LinkedHashSet<>();
        smartFilter.collectRootExpansion(viewer, toExpand);
        if (selection != null && !selection.isEmpty())
            addAncestorChain(viewer, selection.getFirstElement(), toExpand);
        if (!toExpand.isEmpty())
            viewer.setExpandedElements(toExpand.toArray());
        if (selection != null && !selection.isEmpty())
            viewer.setSelection(selection, true);
    }

    private static void addAncestorChain(CommonViewer viewer, Object element, Set<Object> toExpand)
    {
        if (viewer == null || element == null || toExpand == null)
            return;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        Object parent = tcp.getParent(element);
        while (parent != null)
        {
            toExpand.add(parent);
            parent = tcp.getParent(parent);
        }
    }

    private static void removeSmartFilter(CommonViewer viewer, SmartOutlineFilter smartFilter)
    {
        if (viewer == null || smartFilter == null)
            return;
        viewer.removeFilter(smartFilter);
    }

    private static void suppressNativeSearch(IViewPart navigator, CommonViewer viewer)
    {
        boolean deactivateOk = false;
        int removed = 0;

        if (navigator != null)
            deactivateOk = Global.invokeVoid(navigator, "deactivateFilter", StructuredSelection.EMPTY, Boolean.FALSE); //$NON-NLS-1$
        if (viewer != null)
        {
            Tree tree = viewer.getTree();
            List<ViewerFilter> stored = null;
            if (tree != null && tree.getData(STORED_NAV_FILTERS_KEY) == null)
                stored = new ArrayList<>();
            for (ViewerFilter filter : viewer.getFilters())
            {
                if (filter != null && filter.getClass().getName().contains(NAV_SEARCH_FILTER))
                {
                    if (stored != null)
                        stored.add(filter);
                    viewer.removeFilter(filter);
                    removed++;
                }
            }
            if (tree != null && stored != null && !stored.isEmpty())
                tree.setData(STORED_NAV_FILTERS_KEY, stored.toArray(new ViewerFilter[0]));
        }
        NavigatorFilterDebug.log("suppressNative deactivateFilter=" + deactivateOk //$NON-NLS-1$
                + " removedNavFilters=" + removed); //$NON-NLS-1$
    }

    private static void resetNavigatorContentState(IViewPart navigator, CommonViewer viewer)
    {
        triggerNativeSearchClear(navigator);
        clearNavigatorTreePredicate(navigator);

        if (viewer != null)
        {
            Tree tree = viewer.getTree();
            if (tree != null)
            {
                Object stored = tree.getData(STORED_NAV_FILTERS_KEY);
                if (stored instanceof ViewerFilter[])
                {
                    for (ViewerFilter filter : (ViewerFilter[]) stored)
                    {
                        if (filter != null)
                        {
                            Global.invokeVoid(filter, "refresh"); //$NON-NLS-1$
                            viewer.addFilter(filter);
                        }
                    }
                }
                tree.setData(STORED_NAV_FILTERS_KEY, null);
            }
            removeNavigatorSearchFiltersFromViewer(viewer);
        }
        if (navigator == null)
            return;
        Global.invokeVoid(navigator, "deactivate"); //$NON-NLS-1$
        tryInvokeDeactivateFilter(navigator);
        Global.invokeVoid(navigator, "applyFilterNonBlockingUi", ""); //$NON-NLS-1$ //$NON-NLS-2$
        NavigatorFilterDebug.log("resetContent " + NavigatorFilterDebug.filtersDesc(viewer)); //$NON-NLS-1$
    }

    /** Сброс штатного SearchPerformer (PredicateFilter + content provider). */
    private static void triggerNativeSearchClear(IViewPart navigator)
    {
        if (navigator == null)
            return;
        Object searchPerformer = Global.getField(navigator, "searchPerformer"); //$NON-NLS-1$
        if (searchPerformer != null)
            Global.invoke(searchPerformer, "performSearch", ""); //$NON-NLS-1$ //$NON-NLS-2$
        Object searchBox = Global.getField(navigator, "searchBox"); //$NON-NLS-1$
        if (searchBox != null)
            Global.invoke(searchBox, "performSearch", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Сброс PredicateFilter на AEF-дереве навигатора. */
    private static void clearNavigatorTreePredicate(IViewPart navigator)
    {
        if (navigator == null)
            return;
        for (String field : new String[] { "tree", "navigatorTree", "treeComponent", "contentArea" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object treeComp = Global.getField(navigator, field);
            if (treeComp == null)
                continue;
            Object model = Global.invoke(treeComp, "getTreeModel"); //$NON-NLS-1$
            if (model == null)
                model = Global.invoke(treeComp, "getModel"); //$NON-NLS-1$
            if (model == null)
                continue;
            Object predicateFilter = Global.invoke(model, "getFilter"); //$NON-NLS-1$
            if (predicateFilter != null)
                Global.invoke(predicateFilter, "setPredicate", (Object) null); //$NON-NLS-1$
            Global.invokeVoid(treeComp, "refresh"); //$NON-NLS-1$
            break;
        }
    }

    private static void removeNavigatorSearchFiltersFromViewer(CommonViewer viewer)
    {
        if (viewer == null)
            return;
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter != null && filter.getClass().getName().contains(NAV_SEARCH_FILTER))
                viewer.removeFilter(filter);
        }
    }

    private static void tryInvokeDeactivateFilter(IViewPart navigator)
    {
        if (Global.invokeVoid(navigator, "deactivateFilter")) //$NON-NLS-1$
            return;
        if (Global.invokeVoid(navigator, "deactivateFilter", "")) //$NON-NLS-1$
            return;
        if (Global.invokeVoid(navigator, "deactivateFilter", Boolean.FALSE)) //$NON-NLS-1$
            return;
        if (Global.invokeVoid(navigator, "deactivateFilter", StructuredSelection.EMPTY)) //$NON-NLS-1$
            return;
        Global.invokeVoid(navigator, "deactivateFilter", StructuredSelection.EMPTY, Boolean.FALSE); //$NON-NLS-1$
    }

    private static void deactivateNavigatorFilters(IViewPart navigator, CommonViewer viewer)
    {
        suppressNativeSearch(navigator, viewer);
    }

    private static void logNativeSearchState(IViewPart navigator)
    {
        Object active = Global.invoke(navigator, "isSearchFilterActive"); //$NON-NLS-1$
        Object state = Global.invoke(navigator, "getSearchFilterState"); //$NON-NLS-1$
        String pattern = null;
        if (state != null)
            pattern = (String) Global.invoke(state, "getActivePattern"); //$NON-NLS-1$
        NavigatorFilterDebug.log("nativeSearch active=" + active + " pattern=\"" + pattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }

}
