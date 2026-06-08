package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Future;

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
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.dt.navigator.util.NavigatorUtil;

/**
 * Умный фильтр и подсветка в навигаторе EDT ({@code com._1c.g5.v8.dt.ui2.navigator}).
 * Сортировка навигатора не меняется (без {@link SmartOutlineComparator}).
 *
 * <p><b>НЕ ТРОГАТЬ ГРУППЫ</b> — см. {@link NavigatorTreeElementLabels#isGroupNode(Object)}:
 * служебные папки EDT не фильтруются и не раскрашиваются по паттерну ({@link NavigatorOutlineFilter},
 * {@link NavigatorStyledCellLabelWrapper}).
 *
 * <p><b>При выводе фильтрованного дерева раскрывать узлы на пути к совпадениям</b> — см.
 * {@link NavigatorNativeSearchBridge} (штатный {@code NavigatorSearchFilter} + наш matcher).
 */
public final class NavigatorFilterHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.navigatorFilterPatched"; //$NON-NLS-1$
    private static final String LAST_PATTERN_KEY = "tormozit.navigatorLastPattern"; //$NON-NLS-1$
    private static final String SEARCH_CACHE_KEY = "tormozit.navigatorSearchCache"; //$NON-NLS-1$
    private static final String HIGHLIGHT_KEY = "tormozit.navigatorHighlight"; //$NON-NLS-1$
    private static final String RAW_LABEL_PROVIDER_KEY = "tormozit.navigatorRawLabelProvider"; //$NON-NLS-1$
    private static final String WRAP_LABEL_PROVIDER_KEY = "tormozit.navigatorWrapLabelProvider"; //$NON-NLS-1$
    private static final String VIEWER_LABEL_PROVIDER_KEY = "tormozit.navigatorViewerLabelProvider"; //$NON-NLS-1$
    private static final String INJECT_HIGHLIGHT_KEY = "tormozit.navigatorInjectHighlight"; //$NON-NLS-1$
    private static final String REQUESTED_PATTERN_KEY = "tormozit.navigatorRequestedPattern"; //$NON-NLS-1$
    private static final String SNAPSHOT_KEY = "tormozit.navigatorSnapshot"; //$NON-NLS-1$
    private static final String SNAPSHOT_SIZE_KEY = "tormozit.navigatorSnapshotSize"; //$NON-NLS-1$
    private static final String WORKER_FUTURE_KEY = "tormozit.navigatorWorkerFuture"; //$NON-NLS-1$
    private static final String WORKER_SEQ_KEY = "tormozit.navigatorWorkerSeq"; //$NON-NLS-1$
    private static final String FILTER_ACTIVE_KEY = "tormozit.navigatorFilterActive"; //$NON-NLS-1$
    private static final String STORED_NAV_FILTERS_KEY = "tormozit.storedNavFilters"; //$NON-NLS-1$
    private static final String NAV_SEARCH_FILTER = "NavigatorSearchFilter"; //$NON-NLS-1$
    private static final String NATIVE_NAV_SEARCH_FILTER_ID =
            "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSearchFilter"; //$NON-NLS-1$
    private static final int FILTER_EMIT_MIN_DELTA = 32;
    private static final long FILTER_EMIT_MAX_NS = 50_000_000L;
    private static final int FILTER_WORKER_DEBOUNCE_MS = 50;
    private static volatile String lastGiveUpReason = ""; //$NON-NLS-1$
    /**
     * ФИЛЬТРАЦИЯ НЕ ДОЛЖНА БЛОКИРОВАТЬ ВВОД:
     * поиск и обход дерева — в отдельном потоке, UI-поток только применяет готовый результат.
     */
    private static final ScheduledExecutorService FILTER_WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "NavigatorFilterWorker"); //$NON-NLS-1$
        thread.setDaemon(true);
        return thread;
    });

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
        if (!ComfortSettings.isReplaceListFiltersEnabled())
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

        final IBaseLabelProvider labelBase = rawLp;
        final Object labelSource = resolveNavigatorLabelSource(rawLp);
        final NavigatorSearchTextCache searchCache = new NavigatorSearchTextCache();
        final java.util.function.Function<Object, String> matchTextFn = el -> searchCache.searchText(el, labelSource);
        ILabelProvider filterLabels = new ILabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return matchTextFn.apply(element);
            }

            @Override
            public org.eclipse.swt.graphics.Image getImage(Object element)
            {
                return resolveNavigatorImage(labelSource, element);
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

        SmartLabelHighlight highlight = installNavigatorHighlight(viewer, rawLp, filterLabels, searchCache,
                initialPattern, labelSource, matchTextFn);
        tree.setData(RAW_LABEL_PROVIDER_KEY, rawLp);
        if (highlight instanceof StyledCellLabelProvider)
            tree.setData(WRAP_LABEL_PROVIDER_KEY, (IBaseLabelProvider) highlight);
        if (highlight == null)
        {
            lastGiveUpReason = "highlight=null lp=" + rawLp.getClass().getName(); //$NON-NLS-1$
            if (attempt == 0 || attempt % 3 == 0)
                NavigatorFilterDebug.log("try#" + attempt + " WAIT " + lastGiveUpReason); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        if (!NavigatorNativeSearchBridge.install(navigator, viewer, tree))
            NavigatorFilterDebug.log("nativeBridge WARN install failed"); //$NON-NLS-1$

        ViewerComparator comparator = viewer.getComparator();
        NavigatorFilterDebug.log("try#" + attempt + " PATCH OK mode=" + searchInput.mode() //$NON-NLS-1$ //$NON-NLS-2$
                + " lp=" + rawLp.getClass().getSimpleName() //$NON-NLS-1$
                + " comparator=" + (comparator != null ? comparator.getClass().getSimpleName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                + " " + NavigatorFilterDebug.filtersDesc(viewer) //$NON-NLS-1$
                + " pattern=\"" + initialPattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        logNativeSearchState(navigator);

        applyHighlightState(viewer, tree, highlight, searchCache, initialPattern);

        final SearchBoxFilterAccess input = searchInput;
        Control focusControl = searchInput.focusControl();
        Object nativeListener = Global.invoke(searchBox, "getSearchListener"); //$NON-NLS-1$
        boolean listenerOk = searchInput.attachPatternListener(navigator, nativeListener, null, pattern -> {
            String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
            tree.setData(REQUESTED_PATTERN_KEY, safePattern);
            NavigatorFilterDebug.log("modify pattern=\"" + safePattern + "\" mode=" + input.mode()); //$NON-NLS-1$ //$NON-NLS-2$
            // ФИЛЬТРАЦИЯ НЕ ДОЛЖНА БЛОКИРОВАТЬ ВВОД: фильтр — штатный SearchJob; здесь только подсветка.
            applyHighlightState(viewer, tree, highlight, searchCache, safePattern);
            if (safePattern.isEmpty())
                triggerNativeSearchClear(navigator);
        });
        NavigatorFilterDebug.log("patternListener attached=" + listenerOk + " mode=" + input.mode()); //$NON-NLS-1$ //$NON-NLS-2$

        if (focusControl != null)
            FilterFieldListNavigation.installTreeNavigation(focusControl, tree);

        storeNavigatorHookState(tree, viewer, highlight, rawLp);
        tree.setData(PATCHED_KEY, Boolean.TRUE);
        tree.setData(SEARCH_CACHE_KEY, searchCache);
        tree.setData(HIGHLIGHT_KEY, highlight);
        return true;
    }

    /** Только подсветка на UI; фильтрация — штатный SearchJob (не блокирует ввод). */
    private static void applyHighlightState(CommonViewer viewer, Tree tree, SmartLabelHighlight highlight,
            NavigatorSearchTextCache searchCache, String pattern)
    {
        if (viewer == null || tree == null || tree.isDisposed() || highlight == null)
            return;
        String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        if (searchCache != null)
            searchCache.onPatternChanged(safePattern);
        highlight.setHighlightPattern(safePattern);
        boolean filtering = !safePattern.isEmpty();
        ensureNavigatorLabelProvider(viewer, tree, filtering);
        ensureNavigatorHighlight(viewer);
        tree.setData(LAST_PATTERN_KEY, safePattern);
        tree.setData(FILTER_ACTIVE_KEY, Boolean.valueOf(filtering));
    }

    private static void storeNavigatorHookState(Tree tree, CommonViewer viewer, SmartLabelHighlight highlight,
            IBaseLabelProvider rawLp)
    {
        if (tree == null || viewer == null)
            return;
        if (highlight instanceof IStyledLabelProvider && rawLp instanceof DelegatingStyledCellLabelProvider)
        {
            tree.setData(INJECT_HIGHLIGHT_KEY, highlight);
            tree.setData(VIEWER_LABEL_PROVIDER_KEY, rawLp);
        }
        else
        {
            tree.setData(INJECT_HIGHLIGHT_KEY, null);
            tree.setData(VIEWER_LABEL_PROVIDER_KEY, viewer.getLabelProvider());
        }
    }

    /**
     * {@code NavigatorDecoratingLabelProvider} — Delegating: git-суффикс и прочие декорации в его
     * {@code update()}/{@code decorateLibrary}. Нельзя заменять его обёрткой на viewer.
     */
    private static SmartLabelHighlight installNavigatorHighlight(CommonViewer viewer, IBaseLabelProvider rawLp,
            ILabelProvider filterLabels, NavigatorSearchTextCache searchCache, String initialPattern,
            Object labelSource, java.util.function.Function<Object, String> matchTextFn)
    {
        if (rawLp instanceof DelegatingStyledCellLabelProvider delegating)
        {
            IStyledLabelProvider innerStyled = delegating.getStyledStringProvider();
            NavigatorHighlightStyledProvider smartLp = innerStyled != null
                    ? new NavigatorHighlightStyledProvider(innerStyled, filterLabels, NavigatorTreeElementLabels::isGroupNode,
                            labelSource, matchTextFn, searchCache)
                    : new NavigatorHighlightStyledProvider(null, filterLabels, NavigatorTreeElementLabels::isGroupNode,
                            labelSource, matchTextFn, searchCache);
            smartLp.setHighlightPattern(initialPattern);
            injectStyledStringProvider(delegating, smartLp);
            return smartLp;
        }

        if (rawLp instanceof StyledCellLabelProvider styled)
        {
            NavigatorStyledCellLabelWrapper wrapper =
                    new NavigatorStyledCellLabelWrapper(styled, searchCache, labelSource);
            wrapper.setHighlightPattern(initialPattern);
            return wrapper;
        }

        IStyledLabelProvider innerStyled = rawLp instanceof IStyledLabelProvider
                ? (IStyledLabelProvider) rawLp : null;
        SmartOutlineLabelProvider smartLp = innerStyled != null
                ? new SmartOutlineLabelProvider(innerStyled, filterLabels, NavigatorTreeElementLabels::isGroupNode)
                : new SmartOutlineLabelProvider(null, filterLabels, NavigatorTreeElementLabels::isGroupNode);
        smartLp.setHighlightPattern(initialPattern);
        viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(smartLp));
        return smartLp;
    }

    /** Пустой фильтр — штатный label provider на viewer (git-суффикс, уголок иконки). */
    private static void ensureNavigatorLabelProvider(CommonViewer viewer, Tree tree, boolean filtering)
    {
        if (viewer == null || tree == null)
            return;
        IBaseLabelProvider raw = (IBaseLabelProvider) tree.getData(RAW_LABEL_PROVIDER_KEY);
        if (raw == null)
            return;

        Object inject = tree.getData(INJECT_HIGHLIGHT_KEY);
        if (inject instanceof IStyledLabelProvider && raw instanceof DelegatingStyledCellLabelProvider delegating)
        {
            injectStyledStringProvider(delegating, (IStyledLabelProvider) inject);
            if (viewer.getLabelProvider() != raw)
                viewer.setLabelProvider(raw);
            tree.setData(VIEWER_LABEL_PROVIDER_KEY, raw);
            return;
        }

        IBaseLabelProvider wrap = (IBaseLabelProvider) tree.getData(WRAP_LABEL_PROVIDER_KEY);
        if (wrap == null)
            return;

        IBaseLabelProvider want = filtering ? wrap : raw;
        if (viewer.getLabelProvider() != want)
            viewer.setLabelProvider(want);
        tree.setData(VIEWER_LABEL_PROVIDER_KEY, want);
    }

    /** {@code CommonNavigator.refresh()} сбрасывает label provider из content extension. */
    private static void ensureNavigatorHighlight(CommonViewer viewer)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        boolean filtering = Boolean.TRUE.equals(tree.getData(FILTER_ACTIVE_KEY));
        IBaseLabelProvider before = viewer.getLabelProvider();
        ensureNavigatorLabelProvider(viewer, tree, filtering);
        IBaseLabelProvider after = viewer.getLabelProvider();
        if (after != before && NavigatorFilterDebug.isEnabled())
        {
            NavigatorFilterDebug.log("ensureHighlight: lp=" + (after != null ? after.getClass().getSimpleName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                    + " filtering=" + filtering //$NON-NLS-1$
                    + " was=" + (before != null ? before.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        }
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

    /**
     * Реальный label provider для иконок — не Decorating и не его StyledLabelProviderAdapter
     * (адаптер getImage() снова вызывает Decorating → цикл).
     */
    private static Object resolveNavigatorLabelSource(IBaseLabelProvider rawLp)
    {
        if (rawLp instanceof DelegatingStyledCellLabelProvider delegating)
            return unwrapNavigatorImageSource(delegating.getStyledStringProvider());
        return unwrapNavigatorImageSource(rawLp);
    }

    private static Object unwrapNavigatorImageSource(Object source)
    {
        if (source == null)
            return null;
        if (source.getClass().getName().contains("StyledLabelProviderAdapter")) //$NON-NLS-1$
        {
            Object provider = Global.getField(source, "provider"); //$NON-NLS-1$
            if (provider != null)
                return provider;
        }
        return source;
    }

    private static org.eclipse.swt.graphics.Image resolveNavigatorImage(Object imageSource, Object element)
    {
        if (imageSource == null)
            return null;
        if (imageSource instanceof ILabelProvider)
            return ((ILabelProvider) imageSource).getImage(element);
        Object img = Global.invoke(imageSource, "getImage", element); //$NON-NLS-1$
        return img instanceof org.eclipse.swt.graphics.Image ? (org.eclipse.swt.graphics.Image) img : null;
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
            SmartLabelHighlight highlight, NavigatorSearchTextCache searchCache, String pattern, boolean forceRefresh)
    {
        applyFilter(navigator, viewer, smartFilter, highlight, searchCache, pattern, forceRefresh, false);
    }

    private static void applyFilter(IViewPart navigator, CommonViewer viewer, SmartOutlineFilter smartFilter,
            SmartLabelHighlight highlight, NavigatorSearchTextCache searchCache, String pattern, boolean forceRefresh,
            boolean fromWorker)
    {
        if (viewer.getControl() == null || viewer.getControl().isDisposed())
        {
            NavigatorFilterDebug.log("applyFilter SKIP viewer disposed"); //$NON-NLS-1$
            return;
        }
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
        {
            NavigatorFilterDebug.log("applyFilter SKIP tree disposed"); //$NON-NLS-1$
            return;
        }
        String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        if (!fromWorker && smartFilter instanceof NavigatorOutlineFilter)
            ((NavigatorOutlineFilter) smartFilter).clearPrecomputedResult();
        Object requestedObj = tree.getData(REQUESTED_PATTERN_KEY);
        if (requestedObj instanceof String && !safePattern.equals(requestedObj))
        {
            NavigatorFilterDebug.log("applyFilter SKIP superseded requested=\"" + requestedObj //$NON-NLS-1$
                    + "\" apply=\"" + safePattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (!fromWorker && searchCache != null)
            searchCache.onPatternChanged(safePattern);
        boolean filtering = !safePattern.isEmpty();
        boolean filterWasActive = Boolean.TRUE.equals(tree.getData(FILTER_ACTIVE_KEY));
        Object lastObj = tree.getData(LAST_PATTERN_KEY);
        String lastPattern = lastObj instanceof String ? (String) lastObj : ""; //$NON-NLS-1$
        // ФИЛЬТРАЦИЯ НЕ ДОЛЖНА БЛОКИРОВАТЬ ВВОД:
        // при неизмененном паттерне не гоняем лишние refresh/suppress (кроме порций от worker).
        if (!fromWorker && !forceRefresh && safePattern.equals(lastPattern)
                && isSmartFilterAttached(viewer, smartFilter) == filtering)
        {
            NavigatorFilterDebug.log("applyFilter SKIP unchanged pattern=\"" + safePattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        boolean clearingFilter = !filtering && !lastPattern.isEmpty();
        IStructuredSelection savedSelection = null;
        if (!filtering && viewer.getSelection() instanceof IStructuredSelection)
            savedSelection = (IStructuredSelection) viewer.getSelection();
        boolean batchRedraw = !fromWorker;
        if (batchRedraw)
            tree.setRedraw(false);
        try
        {
            if (!fromWorker)
            {
                smartFilter.refreshPattern(safePattern);
                highlight.setHighlightPattern(safePattern);
                ensureNavigatorLabelProvider(viewer, tree, filtering);
                if (filtering)
                {
                    ensureSmartFilter(viewer, smartFilter);
                    // ФИЛЬТРАЦИЯ НЕ ДОЛЖНА БЛОКИРОВАТЬ ВВОД:
                    // тяжелое подавление native-search только на входе в режим фильтрации.
                    if (!filterWasActive)
                    {
                        suppressNativeSearch(navigator, viewer);
                        clearNavigatorTreePredicate(navigator);
                    }
                }
                else
                {
                    removeSmartFilter(viewer, smartFilter);
                    if (filterWasActive || clearingFilter)
                        resetNavigatorContentState(navigator, viewer);
                }
            }
            else
            {
                ensureNavigatorLabelProvider(viewer, tree, filtering);
                ensureSmartFilter(viewer, smartFilter);
            }
            viewer.refresh();
            ensureNavigatorHighlight(viewer);
            requestedObj = tree.getData(REQUESTED_PATTERN_KEY);
            if (requestedObj instanceof String && !safePattern.equals(requestedObj))
            {
                NavigatorFilterDebug.log("applyFilter ABORT superseded pattern=\"" + safePattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (filtering)
            {
                if (smartFilter instanceof NavigatorOutlineFilter)
                    ((NavigatorOutlineFilter) smartFilter).applyFilterExpansion(viewer);
                else
                    smartFilter.applyTreeExpansion(viewer);
            }
            else
                restoreTreeStateAfterClear(viewer, smartFilter, savedSelection);
            IBaseLabelProvider lp = viewer.getLabelProvider();
            NavigatorFilterDebug.log("refresh pattern=\"" + safePattern + "\" treeItems=" + tree.getItemCount() //$NON-NLS-1$ //$NON-NLS-2$
                    + " lp=" + (lp != null ? lp.getClass().getSimpleName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                    + " smartFilterAttached=" + isSmartFilterAttached(viewer, smartFilter) //$NON-NLS-1$
                    + " force=" + forceRefresh //$NON-NLS-1$
                    + " worker=" + fromWorker //$NON-NLS-1$
                    + " " + NavigatorFilterDebug.filtersDesc(viewer)); //$NON-NLS-1$
            if (navigator != null)
                logNativeSearchState(navigator);
            tree.setData(LAST_PATTERN_KEY, safePattern);
            tree.setData(FILTER_ACTIVE_KEY, Boolean.valueOf(filtering));
        }
        finally
        {
            if (batchRedraw)
                tree.setRedraw(true);
        }
    }

    /**
     * Подготовка к асинхронному поиску без refresh: паттерн, фильтр и подсветка на UI,
     * дерево остаётся в прежнем виде до первой порции от worker.
     */
    private static void prepareAsyncFilterSearch(IViewPart navigator, CommonViewer viewer, Tree tree,
            NavigatorOutlineFilter smartFilter, SmartLabelHighlight highlight, String pattern)
    {
        if (viewer == null || tree == null || tree.isDisposed() || smartFilter == null || highlight == null)
            return;
        String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        if (safePattern.isEmpty())
            return;
        Object requestedObj = tree.getData(REQUESTED_PATTERN_KEY);
        if (requestedObj instanceof String && !safePattern.equals(requestedObj))
            return;
        boolean filterWasActive = Boolean.TRUE.equals(tree.getData(FILTER_ACTIVE_KEY));
        smartFilter.refreshPattern(safePattern);
        highlight.setHighlightPattern(safePattern);
        ensureNavigatorLabelProvider(viewer, tree, true);
        ensureSmartFilter(viewer, smartFilter);
        if (!filterWasActive)
        {
            suppressNativeSearch(navigator, viewer);
            clearNavigatorTreePredicate(navigator);
        }
        tree.setData(FILTER_ACTIVE_KEY, Boolean.TRUE);
    }

    private static void startFilterWorker(IViewPart navigator, CommonViewer viewer, Tree tree,
            NavigatorOutlineFilter smartFilter, SmartLabelHighlight highlight, NavigatorSearchTextCache searchCache,
            ILabelProvider filterLabels, String pattern)
    {
        String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        Object requestedObj = tree.getData(REQUESTED_PATTERN_KEY);
        if (requestedObj instanceof String && !safePattern.equals(requestedObj))
            return;
        cancelWorkerTask(tree);
        if (safePattern.isEmpty())
        {
            applyFilter(navigator, viewer, smartFilter, highlight, searchCache, safePattern, true, false);
            return;
        }
        int seq = nextWorkerSeq(tree);
        long requestedAtNs = System.nanoTime();
        NavigatorFilterDebug.log("worker schedule seq=" + seq + " pattern=\"" + safePattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Future<?> future = FILTER_WORKER.submit(() -> {
            TreeSnapshot snapshot = buildTreeSnapshot(viewer, filterLabels);
            if (snapshot == null || snapshot.nodes.isEmpty())
            {
                NavigatorFilterDebug.log("worker fallback: snapshot=" + (snapshot == null ? "null" : "empty")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                {
                    display.asyncExec(() -> {
                        Object requested = tree.getData(REQUESTED_PATTERN_KEY);
                        if (requested instanceof String && safePattern.equals(requested))
                            applyFilter(navigator, viewer, smartFilter, highlight, searchCache, safePattern, true,
                                    false);
                    });
                }
                return;
            }
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
                display.asyncExec(() -> {
                    tree.setData(SNAPSHOT_KEY, snapshot);
                    tree.setData(SNAPSHOT_SIZE_KEY, Integer.valueOf(snapshot.nodes.size()));
                });
            }
            NavigatorFilterDebug.log("worker start seq=" + seq + " pattern=\"" + safePattern //$NON-NLS-1$ //$NON-NLS-2$
                    + "\" nodes=" + snapshot.nodes.size()); //$NON-NLS-1$
            runFilterWorkerTask(seq, safePattern, navigator, viewer, tree, smartFilter, highlight, searchCache,
                    snapshot, requestedAtNs);
        });
        tree.setData(WORKER_FUTURE_KEY, future);
    }

    private static int nextWorkerSeq(Tree tree)
    {
        if (tree == null)
            return 1;
        Object current = tree.getData(WORKER_SEQ_KEY);
        int seq = current instanceof Integer ? ((Integer) current).intValue() + 1 : 1;
        tree.setData(WORKER_SEQ_KEY, Integer.valueOf(seq));
        return seq;
    }

    private static void runFilterWorkerTask(int seq, String pattern, IViewPart navigator, CommonViewer viewer, Tree tree,
            NavigatorOutlineFilter smartFilter, SmartLabelHighlight highlight, NavigatorSearchTextCache searchCache,
            TreeSnapshot snapshot, long requestedAtNs)
    {
        if (snapshot == null || snapshot.nodes.isEmpty())
        {
            NavigatorFilterDebug.log("worker skip: snapshot=" + (snapshot == null ? "null" : "empty")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }

        String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        long workerStartNs = System.nanoTime();
        int scannedNodes = 0;
        int partialEmits = 0;
        try
        {
            if (safePattern.isEmpty())
            {
                publishWorkerPointers(seq, safePattern, navigator, viewer, tree, smartFilter, highlight, searchCache,
                        new FilterPointers(Collections.emptySet(), Collections.emptySet()), true,
                        requestedAtNs, workerStartNs, scannedNodes, partialEmits);
                return;
            }

            SmartMatcher matcher = new SmartMatcher(safePattern);
            Set<Object> visibleElements = new HashSet<>();
            Set<Object> expand = new LinkedHashSet<>();
            long lastEmitNs = System.nanoTime();
            int lastEmitVisibleSize = 0;

            for (int i = 0; i < snapshot.nodes.size(); i++)
            {
                if (Thread.currentThread().isInterrupted())
                {
                    NavigatorFilterDebug.log("metric worker_cancel seq=" + seq //$NON-NLS-1$
                            + " scan_ms=" + ((System.nanoTime() - workerStartNs) / 1_000_000L) //$NON-NLS-1$
                            + " scanned=" + scannedNodes); //$NON-NLS-1$
                    return;
                }
                NodeSnapshot node = snapshot.nodes.get(i);
                scannedNodes++;
                if (node.group || !matcher.matches(node.searchText))
                    continue;

                visibleElements.add(node.element);
                int parent = node.parentIndex;
                while (parent >= 0)
                {
                    if (Thread.currentThread().isInterrupted())
                    {
                        NavigatorFilterDebug.log("metric worker_cancel seq=" + seq //$NON-NLS-1$
                                + " scan_ms=" + ((System.nanoTime() - workerStartNs) / 1_000_000L) //$NON-NLS-1$
                                + " scanned=" + scannedNodes); //$NON-NLS-1$
                        return;
                    }
                    NodeSnapshot parentNode = snapshot.nodes.get(parent);
                    visibleElements.add(parentNode.element);
                    expand.add(parentNode.element);
                    parent = parentNode.parentIndex;
                }

                long now = System.nanoTime();
                boolean firstMatchEmit = partialEmits == 0;
                if (firstMatchEmit
                        || visibleElements.size() - lastEmitVisibleSize >= FILTER_EMIT_MIN_DELTA
                        || now - lastEmitNs >= FILTER_EMIT_MAX_NS)
                {
                    partialEmits++;
                    publishWorkerPointers(seq, safePattern, navigator, viewer, tree, smartFilter, highlight, searchCache,
                            new FilterPointers(new HashSet<>(visibleElements), new LinkedHashSet<>(expand)), false,
                            requestedAtNs, workerStartNs, scannedNodes, partialEmits);
                    lastEmitVisibleSize = visibleElements.size();
                    lastEmitNs = now;
                }
            }

            publishWorkerPointers(seq, safePattern, navigator, viewer, tree, smartFilter, highlight, searchCache,
                    new FilterPointers(new HashSet<>(visibleElements), new LinkedHashSet<>(expand)), true,
                    requestedAtNs, workerStartNs, scannedNodes, partialEmits);
        }
        catch (RuntimeException e)
        {
            NavigatorFilterDebug.log("worker error seq=" + seq + " pattern=\"" + safePattern //$NON-NLS-1$ //$NON-NLS-2$
                    + "\" " + e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void cancelWorkerTask(Tree tree)
    {
        if (tree == null)
            return;
        Object pending = tree.getData(WORKER_FUTURE_KEY);
        if (pending instanceof Future<?>)
        {
            Future<?> future = (Future<?>) pending;
            boolean cancelled = future.cancel(true);
            if (cancelled && NavigatorFilterDebug.isEnabled())
                NavigatorFilterDebug.log("metric cancel_worker cancelled=true"); //$NON-NLS-1$
        }
        tree.setData(WORKER_FUTURE_KEY, null);
    }

    private static TreeSnapshot buildTreeSnapshot(CommonViewer viewer, ILabelProvider filterLabels)
    {
        if (viewer == null)
            return null;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return null;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        Object input = viewer.getInput();
        if (input == null)
            return null;
        List<NodeSnapshot> nodes = new ArrayList<>();
        Map<Object, Integer> seen = new java.util.IdentityHashMap<>();
        for (Object root : tcp.getElements(input))
            collectSnapshotNode(root, -1, tcp, filterLabels, nodes, seen);
        return new TreeSnapshot(nodes);
    }

    private static void collectSnapshotNode(Object element, int parentIndex, ITreeContentProvider tcp,
            ILabelProvider filterLabels, List<NodeSnapshot> nodes, Map<Object, Integer> seen)
    {
        if (element == null || seen.containsKey(element))
            return;
        NodeSnapshot node = new NodeSnapshot();
        node.element = element;
        node.parentIndex = parentIndex;
        node.group = NavigatorTreeElementLabels.isGroupNode(element);
        String text = filterLabels != null ? filterLabels.getText(element) : null;
        node.searchText = text != null ? text : ""; //$NON-NLS-1$
        int myIndex = nodes.size();
        nodes.add(node);
        seen.put(element, Integer.valueOf(myIndex));
        Object[] children = tcp.getChildren(element);
        if (children == null)
            return;
        for (Object child : children)
            collectSnapshotNode(child, myIndex, tcp, filterLabels, nodes, seen);
    }

    private static void publishWorkerPointers(int seq, String safePattern, IViewPart navigator, CommonViewer viewer, Tree tree,
            NavigatorOutlineFilter smartFilter, SmartLabelHighlight highlight, NavigatorSearchTextCache searchCache,
            FilterPointers pointers, boolean completed, long requestedAtNs, long workerStartNs,
            int scannedNodes, int partialEmits)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (tree == null || tree.isDisposed() || viewer.getControl() == null || viewer.getControl().isDisposed())
                return;
            Object requested = tree.getData(REQUESTED_PATTERN_KEY);
            if (!(requested instanceof String) || !safePattern.equals(requested))
            {
                NavigatorFilterDebug.log("worker drop seq=" + seq + " stale pattern=\"" + safePattern //$NON-NLS-1$ //$NON-NLS-2$
                        + "\" requested=\"" + requested + "\""); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (safePattern.isEmpty())
                smartFilter.clearPrecomputedResult();
            else
                smartFilter.setPrecomputedResult(pointers.visible, pointers.expand, true);
            // Как SearchJob: промежуточные порции только обновляют precomputed, UI — в финале
            // (или на первой порции для быстрой обратной связи). Иначе большая порция блокирует ввод.
            if (!completed && partialEmits > 1)
            {
                if (NavigatorFilterDebug.isEnabled())
                    NavigatorFilterDebug.log("worker seq=" + seq + " partial skip ui emit=" + partialEmits); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            if (NavigatorFilterDebug.isEnabled())
            {
                long nowNs = System.nanoTime();
                NavigatorFilterDebug.log("worker seq=" + seq + " pattern=\"" + safePattern + "\" visible=" //$NON-NLS-1$ //$NON-NLS-2$
                        + pointers.visible.size() + " expand=" + pointers.expand.size() //$NON-NLS-1$
                        + " completed=" + completed //$NON-NLS-1$
                        + " queue_ms=" + ((workerStartNs - requestedAtNs) / 1_000_000L) //$NON-NLS-1$
                        + " scan_ms=" + ((nowNs - workerStartNs) / 1_000_000L) //$NON-NLS-1$
                        + " scanned=" + scannedNodes //$NON-NLS-1$
                        + " partial_emits=" + partialEmits); //$NON-NLS-1$
            }
            long uiStartNs = System.nanoTime();
            ensureNavigatorLabelProvider(viewer, tree, true);
            ensureSmartFilter(viewer, smartFilter);
            ensureNavigatorHighlight(viewer);
            NavigatorNonBlockingUi.applyPrecomputedUi(viewer, tree, smartFilter, pointers.visible, pointers.expand,
                    completed);
            tree.setData(LAST_PATTERN_KEY, safePattern);
            tree.setData(FILTER_ACTIVE_KEY, Boolean.TRUE);
            if (completed)
            {
                NavigatorFilterDebug.log("worker ui done pattern=\"" + safePattern + "\" visible=" //$NON-NLS-1$ //$NON-NLS-2$
                        + pointers.visible.size() + " treeItems=" + tree.getItemCount()); //$NON-NLS-1$
            }
            if (NavigatorFilterDebug.isEnabled())
            {
                long uiEndNs = System.nanoTime();
                NavigatorFilterDebug.log("metric ui_queue seq=" + seq //$NON-NLS-1$
                        + " ui_queue_ms=" + ((uiEndNs - uiStartNs) / 1_000_000L) //$NON-NLS-1$
                        + " total_input_to_ui_ms=" + ((uiEndNs - requestedAtNs) / 1_000_000L)); //$NON-NLS-1$
            }
        });
    }

    private static void applyFilter(IViewPart navigator, CommonViewer viewer, SmartOutlineFilter smartFilter,
            SmartLabelHighlight highlight, NavigatorSearchTextCache searchCache, String pattern)
    {
        applyFilter(navigator, viewer, smartFilter, highlight, searchCache, pattern, false);
    }

    private static final class NodeSnapshot
    {
        Object element;
        int parentIndex;
        boolean group;
        String searchText;
    }

    private static final class TreeSnapshot
    {
        final List<NodeSnapshot> nodes;

        TreeSnapshot(List<NodeSnapshot> nodes)
        {
            this.nodes = nodes != null ? nodes : Collections.emptyList();
        }
    }

    private static final class FilterPointers
    {
        final Set<Object> visible;
        final Set<Object> expand;

        FilterPointers(Set<Object> visible, Set<Object> expand)
        {
            this.visible = visible != null ? visible : Collections.emptySet();
            this.expand = expand != null ? expand : Collections.emptySet();
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

    private static boolean isSmartFilterAttached(CommonViewer viewer, SmartOutlineFilter smartFilter)
    {
        if (viewer == null || smartFilter == null)
            return false;
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter == smartFilter)
                return true;
        }
        return false;
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
        String deactivateMethod = "none"; //$NON-NLS-1$
        int removed = 0;

        if (navigator != null)
            deactivateMethod = tryInvokeDeactivateFilterWithLog(navigator);
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
        NavigatorFilterDebug.log("suppressNative deactivateFilter=" + deactivateMethod //$NON-NLS-1$
                + " removedNavFilters=" + removed); //$NON-NLS-1$
        if ("none".equals(deactivateMethod) && removed == 0) //$NON-NLS-1$
            NavigatorFilterDebug.log("suppressNative WARN native search may still be active"); //$NON-NLS-1$
    }

    /** @return имя сработавшей сигнатуры или {@code "none"} */
    private static String tryInvokeDeactivateFilterWithLog(IViewPart navigator)
    {
        if (Global.invokeVoid(navigator, "deactivateFilter")) //$NON-NLS-1$
            return "deactivateFilter()"; //$NON-NLS-1$
        if (Global.invokeVoid(navigator, "deactivateFilter", "")) //$NON-NLS-1$ //$NON-NLS-2$
            return "deactivateFilter(\"\")"; //$NON-NLS-1$
        if (Global.invokeVoid(navigator, "deactivateFilter", Boolean.FALSE)) //$NON-NLS-1$
            return "deactivateFilter(false)"; //$NON-NLS-1$
        if (Global.invokeVoid(navigator, "deactivateFilter", StructuredSelection.EMPTY)) //$NON-NLS-1$
            return "deactivateFilter(EMPTY)"; //$NON-NLS-1$
        if (Global.invokeVoid(navigator, "deactivateFilter", StructuredSelection.EMPTY, Boolean.FALSE)) //$NON-NLS-1$
            return "deactivateFilter(EMPTY,false)"; //$NON-NLS-1$
        return "none"; //$NON-NLS-1$
    }

    private static void resetNavigatorContentState(IViewPart navigator, CommonViewer viewer)
    {
        triggerNativeSearchClear(navigator);
        if (viewer != null)
        {
            Tree tree = viewer.getTree();
            if (tree != null)
            {
                NavigatorNativeSearchBridge.restoreStoredNativeFilters(viewer, tree);
                NavigatorNativeSearchBridge.install(navigator, viewer, tree);
            }
        }
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
        tryInvokeDeactivateFilterWithLog(navigator);
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
