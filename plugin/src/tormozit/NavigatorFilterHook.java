package tormozit;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.ViewerComparator;
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
 *
 * <p><b>НЕ ТРОГАТЬ ГРУППЫ</b> — см. {@link NavigatorTreeElementLabels#isGroupNode(Object)}:
 * служебные папки EDT не раскрашиваются по паттерну ({@link NavigatorHighlightStyledProvider},
 * {@link NavigatorStyledCellLabelWrapper}).
 *
 * <p>Фильтрация — штатный {@code NavigatorSearchFilter} через {@link NavigatorNativeSearchBridge}
 * ({@link ComfortNavigatorSearchEngine}); раскрытие путей — нативный {@code expandTreeViewerStepByStep}.
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
    private static final String FILTER_ACTIVE_KEY = "tormozit.navigatorFilterActive"; //$NON-NLS-1$
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
                onSearchCleared(navigator, viewer, tree);
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

    private static void onSearchCleared(IViewPart navigator, CommonViewer viewer, Tree tree)
    {
        triggerNativeSearchClear(navigator);
        if (viewer != null && tree != null)
        {
            NavigatorNativeSearchBridge.restoreStoredNativeFilters(viewer, tree);
            NavigatorNativeSearchBridge.install(navigator, viewer, tree);
        }
        NavigatorFilterDebug.log("searchCleared " + NavigatorFilterDebug.filtersDesc(viewer)); //$NON-NLS-1$
    }

    /** Сброс штатного SearchPerformer / SearchBox. */
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
