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
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import java.util.function.Function;
import java.util.function.Predicate;
import org.eclipse.jface.viewers.StyledString;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.search.core.IModelObjectTreeSearchEngine;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.ICommonFilterDescriptor;
import org.eclipse.ui.navigator.INavigatorContentService;
import org.eclipse.ui.navigator.INavigatorFilterService;

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
        final Runnable[] nativeRecoveryPending = { null };
        boolean listenerOk = searchInput.attachPatternListener(navigator, pattern -> {
            String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
            tree.setData(REQUESTED_PATTERN_KEY, safePattern);
            NavigatorFilterDebug.log("modify pattern=\"" + safePattern + "\" mode=" + input.mode()); //$NON-NLS-1$ //$NON-NLS-2$
            if (!safePattern.isEmpty())
                ObjectSetsNavigatorFilterSupport.deactivateBecauseCompetingFilter();
            // ФИЛЬТРАЦИЯ НЕ ДОЛЖНА БЛОКИРОВАТЬ ВВОД: фильтр — штатный SearchJob; здесь только подсветка.
            applyHighlightState(viewer, tree, highlight, searchCache, safePattern);
            if (safePattern.isEmpty() && searchInput.isWidgetSearchEmpty())
                onSearchCleared(navigator, viewer, tree);
            else if (!safePattern.isEmpty())
                NavigatorNativeSearchBridge.scheduleNativeFilterRecovery(navigator, viewer, nativeRecoveryPending);
        });
        NavigatorFilterDebug.log("patternListener attached=" + listenerOk + " mode=" + input.mode()); //$NON-NLS-1$ //$NON-NLS-2$

        if (focusControl != null)
        {
            FilterInputBoxListNavigation.installTreeNavigation(focusControl, tree);
            focusControl.addDisposeListener(e -> {
                Display display = focusControl.getDisplay();
                if (nativeRecoveryPending[0] != null && display != null && !display.isDisposed())
                    display.timerExec(-1, nativeRecoveryPending[0]);
            });
        }

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

    private static String readNativeActivePattern(IViewPart navigator)
    {
        Object state = navigator != null ? Global.invoke(navigator, "getSearchFilterState") : null; //$NON-NLS-1$
        if (state == null)
            return ""; //$NON-NLS-1$
        String pattern = (String) Global.invoke(state, "getActivePattern"); //$NON-NLS-1$
        return pattern != null ? pattern : ""; //$NON-NLS-1$
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }


    /**
     * Подсветка + серый квалификатор справа для inject-пути ({@code NavigatorDecoratingLabelProvider}).
     * Родной git-суффикс добавляет внешний Decorating после {@link #getStyledText}.
     */
    private static final class NavigatorHighlightStyledProvider extends SmartOutlineLabelProvider
    {
        private final NavigatorSearchTextCache searchCache;
        private String highlightPattern = ""; //$NON-NLS-1$

        NavigatorHighlightStyledProvider(IStyledLabelProvider baseStyled, ILabelProvider basePlain,
                Predicate<Object> skipHighlight, Object labelSource, Function<Object, String> matchTextFn,
                NavigatorSearchTextCache searchCache)
        {
            super(baseStyled, basePlain, skipHighlight, labelSource, matchTextFn);
            this.searchCache = searchCache;
        }

        @Override
        public void setHighlightPattern(String pattern)
        {
            highlightPattern = pattern != null ? pattern : ""; //$NON-NLS-1$
            super.setHighlightPattern(highlightPattern);
            if (searchCache != null)
                searchCache.onPatternChanged(highlightPattern);
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = obtainBaseStyledText(element);
            if (highlightPattern.isEmpty() || NavigatorTreeElementLabels.isGroupNode(element))
                return styled;

            SmartMatcher matcher = new SmartMatcher(highlightPattern);
            String plainText = styled.getString();
            if (!matcher.matches(resolveMatchText(element, plainText)))
                return styled;

            NavigatorFuzzySearch.QualifierMatch qualifier = resolveQualifier(element);
            if (qualifier != null)
                return NavigatorLabelQualifier.applyToStyledString(styled, element, qualifier, matcher);

            applyHighlightIfNeeded(element, styled);
            return styled;
        }

        private NavigatorFuzzySearch.QualifierMatch resolveQualifier(Object element)
        {
            MdObject mdObject = NavigatorTreeElementLabels.resolveMdObject(element);
            if (mdObject == null)
                return null;

            String name = mdObject.getName() != null ? mdObject.getName() : ""; //$NON-NLS-1$
            return searchCache != null
                    ? searchCache.qualifier(mdObject, highlightPattern, name)
                    : NavigatorFuzzySearch.findQualifierMatch(mdObject, highlightPattern, name);
        }
    }


    /**
     * Встраивание умного matcher в штатный {@code NavigatorSearchFilter}: подмена только
     * {@code searchEngine}. Фильтрация, SearchJob, {@code applyFilterNonBlockingUi} и
     * {@code expandTreeViewerStepByStep} остаются нативными — ввод не блокируется.
     */
    private static final class NavigatorNativeSearchBridge
    {
        private static final String NATIVE_ENGINE_KEY = "tormozit.nativeSearchEngine"; //$NON-NLS-1$
        private static final String COMFORT_ENGINE_KEY = "tormozit.comfortSearchEngine"; //$NON-NLS-1$
        private static final String NATIVE_FILTER_ID =
                "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSearchFilter"; //$NON-NLS-1$
        /** {@code searchDelay(500) + jobScheduleDelay(100) + типичный SearchJob} — после нативного job. */
        private static final int NATIVE_SEARCH_RECOVERY_DELAY_MS = 950;
        private static final int NATIVE_SEARCH_PATTERN_RETRY_MS = 200;
        private static final int NATIVE_SEARCH_PATTERN_RETRY_MAX = 6;

        private NavigatorNativeSearchBridge() {}

        /**
         * После паузы ввода: штатный SearchJob делает {@code collapseAll}, trie строится, но
         * {@code expandTreeViewerStepByStep} часто пропускается (отмена job) — дерево остаётся
         * на корнях проектов при {@code isSearchFilterActive=true} (см. логи, гипотеза G).
         */
        static void scheduleNativeFilterRecovery(IViewPart navigator, CommonViewer viewer, Runnable[] holder)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed() || holder == null)
                return;
            if (holder[0] != null)
                display.timerExec(-1, holder[0]);
            holder[0] = () -> syncNativeSearchUiAfterTyping(navigator, viewer, holder, 0);
            display.timerExec(NATIVE_SEARCH_RECOVERY_DELAY_MS, holder[0]);
        }

        static void syncNativeSearchUiAfterTyping(IViewPart navigator, CommonViewer viewer, Runnable[] holder,
                int attempt)
        {
            if (!(navigator instanceof CommonNavigator commonNavigator) || viewer == null)
                return;
            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return;
            Object searchBox = Global.getField(navigator, "searchBox"); //$NON-NLS-1$
            SearchBoxFilterAccess searchInput = SearchBoxFilterAccess.resolveQuiet(navigator, searchBox);
            String pattern = searchInput != null ? searchInput.readPattern().trim() : ""; //$NON-NLS-1$
            if (pattern.length() < 2)
                return;
            boolean active = Boolean.TRUE.equals(Global.invoke(navigator, "isSearchFilterActive")); //$NON-NLS-1$
            String nativePattern = readNativeActivePattern(navigator);
            if (!pattern.equals(nativePattern) && attempt < NATIVE_SEARCH_PATTERN_RETRY_MAX)
            {
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed() && holder != null)
                {
                    holder[0] = () -> syncNativeSearchUiAfterTyping(navigator, viewer, holder, attempt + 1);
                    display.timerExec(NATIVE_SEARCH_PATTERN_RETRY_MS, holder[0]);
                }
                return;
            }
            boolean needActivate = !active || !pattern.equals(nativePattern);
            Object patchedFilter = resolveNavigatorSearchFilter(navigator);
            ensureNativeFilterOnViewer(viewer, patchedFilter);
            invokeNavigatorUtil("applyFilterNonBlockingUi", commonNavigator, NATIVE_FILTER_ID); //$NON-NLS-1$
            if (needActivate)
                invokeNavigatorUtil("activateFilterNonBlockingUi", commonNavigator, NATIVE_FILTER_ID); //$NON-NLS-1$
            viewer.refresh();
            expandFilteredTree(viewer);
        }

        private static void invokeNavigatorUtil(String method, CommonNavigator navigator, String filterId)
        {
            try
            {
                Class<?> util = Class.forName("com._1c.g5.v8.dt.navigator.util.NavigatorUtil"); //$NON-NLS-1$
                java.lang.reflect.Method m = util.getDeclaredMethod(method, CommonNavigator.class, String.class);
                m.setAccessible(true);
                m.invoke(null, navigator, filterId);
            }
            catch (Exception ignored) {}
        }

        private static void expandFilteredTree(CommonViewer viewer)
        {
            if (viewer == null)
                return;
            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return;
            Display display = tree.getDisplay();
            if (display == null || display.isDisposed())
                return;
            try
            {
                Class<?> helper = Class.forName("com._1c.g5.v8.dt.common.ui.controls.search.UISearchHelper"); //$NON-NLS-1$
                java.lang.reflect.Method m = helper.getDeclaredMethod("expandTreeViewerStepByStep", //$NON-NLS-1$
                        Display.class, CommonViewer.class, org.eclipse.core.runtime.IProgressMonitor.class,
                        org.eclipse.jface.viewers.ISelection.class);
                m.setAccessible(true);
                m.invoke(null, display, viewer, new NullProgressMonitor(), viewer.getSelection());
            }
            catch (Exception ignored) {}
        }

        public static boolean install(IViewPart navigator, CommonViewer viewer, Tree tree)
        {
            if (navigator == null || viewer == null || tree == null || tree.isDisposed())
                return false;
            restoreStoredNativeFilters(viewer, tree);

            Object navFilter = resolveNavigatorSearchFilter(navigator);
            if (navFilter == null)
            {
                NavigatorFilterDebug.log("nativeBridge SKIP NavigatorSearchFilter not found"); //$NON-NLS-1$
                return false;
            }

            IModelObjectTreeSearchEngine nativeDelegate = resolveNativeDelegate(navFilter, tree);
            if (tree.getData(COMFORT_ENGINE_KEY) != null)
            {
                Object current = Global.getField(navFilter, "searchEngine"); //$NON-NLS-1$
                if (current instanceof ComfortNavigatorSearchEngine)
                    return true;
            }

            if (nativeDelegate == null)
            {
                NavigatorFilterDebug.log("nativeBridge SKIP searchEngine=null"); //$NON-NLS-1$
                return false;
            }

            Object v8ProjectManager = Global.getField(navFilter, "v8ProjectManager"); //$NON-NLS-1$
            IV8ProjectManager projectManager = v8ProjectManager instanceof IV8ProjectManager
                    ? (IV8ProjectManager) v8ProjectManager : null;
            Object bmModelManager = Global.getField(navFilter, "modelManager"); //$NON-NLS-1$
            IBmModelManager modelManager = bmModelManager instanceof IBmModelManager
                    ? (IBmModelManager) bmModelManager : Global.getOsgiService(IBmModelManager.class);

            ComfortNavigatorSearchEngine comfortEngine =
                    new ComfortNavigatorSearchEngine(nativeDelegate, projectManager, modelManager);
            Global.setField(navFilter, "searchEngine", comfortEngine); //$NON-NLS-1$
            tree.setData(NATIVE_ENGINE_KEY, nativeDelegate);
            tree.setData(COMFORT_ENGINE_KEY, comfortEngine);
            ensureNativeFilterOnViewer(viewer, navFilter);
            NavigatorFilterDebug.log("nativeBridge installed comfortEngine on NavigatorSearchFilter"); //$NON-NLS-1$
            return true;
        }

        public static void restoreStoredNativeFilters(CommonViewer viewer, Tree tree)
        {
            if (viewer == null || tree == null)
                return;
            Object stored = tree.getData("tormozit.storedNavFilters"); //$NON-NLS-1$
            if (!(stored instanceof ViewerFilter[]))
                return;
            for (ViewerFilter filter : (ViewerFilter[]) stored)
            {
                if (filter != null)
                    viewer.addFilter(filter);
            }
            tree.setData("tormozit.storedNavFilters", null); //$NON-NLS-1$
            NavigatorFilterDebug.log("nativeBridge restored stored NavigatorSearchFilter(s)"); //$NON-NLS-1$
        }

        private static void ensureNativeFilterOnViewer(CommonViewer viewer, Object navFilter)
        {
            if (viewer == null || !(navFilter instanceof ViewerFilter filter))
                return;
            for (ViewerFilter existing : viewer.getFilters())
            {
                if (existing == filter)
                    return;
                if (existing != null && existing.getClass().getName().contains("NavigatorSearchFilter")) //$NON-NLS-1$
                    viewer.removeFilter(existing);
            }
            viewer.addFilter(filter);
        }

        private static Object resolveNavigatorSearchFilter(IViewPart navigator)
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
                return filter;
            }
            return null;
        }

        private static IModelObjectTreeSearchEngine resolveNativeDelegate(Object navFilter, Tree tree)
        {
            Object stored = tree != null ? tree.getData(NATIVE_ENGINE_KEY) : null;
            if (stored instanceof IModelObjectTreeSearchEngine engine)
                return engine;

            Object fromFilter = Global.getField(navFilter, "searchEngine"); //$NON-NLS-1$
            if (fromFilter instanceof ComfortNavigatorSearchEngine)
                return null;
            if (fromFilter instanceof IModelObjectTreeSearchEngine engine)
                return engine;
            return null;
        }
    }

}
