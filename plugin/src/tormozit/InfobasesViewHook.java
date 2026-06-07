package tormozit;

import java.util.function.Consumer;

import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.swt.widgets.Composite;
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
 * Умный фильтр и подсветка вхождений в панели «Информационные базы»
 * ({@code com._1c.g5.v8.dt.internal.platform.services.ui.infobases.InfobasesView}).
 *
 * <p>Подключается к штатному {@code SearchBox} в {@code createInfobasesNavigator}
 * (в {@code InfobasesView} нет поля {@code searchBox}, только {@code filter} —
 * {@link InfobaseByNameFilter}), отключает нативный фильтр и подставляет {@link SmartOutlineFilter}
 * с {@link CellLabelHighlightWrapper} (штатный {@code ColumnLabelProvider}) для подсветки.
 *
 * <p>Логирование: {@code -Dtormozit.infobasesView.debug=false} — отключить.
 */
public final class InfobasesViewHook implements IStartup
{
    private static final String INFOBASES_VIEW_ID =
            "com._1c.g5.v8.dt.platform.services.ui.infobases_view"; //$NON-NLS-1$
    /** Запасной вариант — по имени класса. */
    private static final String INFOBASES_VIEW_CLASS =
            "com._1c.g5.v8.dt.internal.platform.services.ui.infobases.InfobasesView"; //$NON-NLS-1$

    private static final String PATCHED_KEY  = "tormozit.infobasesViewPatched";  //$NON-NLS-1$
    private static final String LAST_PAT_KEY = "tormozit.infobasesLastPattern";  //$NON-NLS-1$

    // -----------------------------------------------------------------------
    // IStartup
    // -----------------------------------------------------------------------

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
            {
                InfobasesViewDebug.log("earlyStartup: workbench=null"); //$NON-NLS-1$
                return;
            }
            InfobasesViewDebug.log("earlyStartup: install window listener"); //$NON-NLS-1$
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
        });
    }

    // -----------------------------------------------------------------------
    // Подключение к окну / панели
    // -----------------------------------------------------------------------

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null) return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null) continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isInfobasesView(view))
                {
                    InfobasesViewDebug.log("hookWindow: found existing view, schedule patch"); //$NON-NLS-1$
                    schedulePatch(view, 0);
                }
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)   { tryFromRef(ref, "partOpened"); }    //$NON-NLS-1$
            @Override public void partVisible(IWorkbenchPartReference ref)  { tryFromRef(ref, "partVisible"); }   //$NON-NLS-1$
            @Override public void partActivated(IWorkbenchPartReference ref){ tryFromRef(ref, "partActivated"); } //$NON-NLS-1$
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryFromRef(IWorkbenchPartReference ref, String source)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isInfobasesView(part))
                {
                    InfobasesViewDebug.log("partListener source=" + source + ": schedule patch"); //$NON-NLS-1$
                    schedulePatch((IViewPart) part, 0);
                }
            }
        });
    }

    private static boolean isInfobasesView(Object part)
    {
        if (!(part instanceof IViewPart)) return false;
        IViewPart vp = (IViewPart) part;
        String id = vp.getViewSite().getId();
        if (INFOBASES_VIEW_ID.equals(id)) return true;
        return vp.getClass().getName().equals(INFOBASES_VIEW_CLASS);
    }

    // -----------------------------------------------------------------------
    // Патч с повторными попытками
    // -----------------------------------------------------------------------

    private static void schedulePatch(IViewPart view, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () -> {
            if (!tryPatch(view, attempt) && attempt < 20)
                schedulePatch(view, attempt + 1);
            else if (attempt >= 20)
                InfobasesViewDebug.log("tryPatch GIVE UP after 20 attempts"); //$NON-NLS-1$
        });
    }

    private static boolean tryPatch(IViewPart view, int attempt)
    {
        if (attempt == 0 || attempt % 5 == 0)
            InfobasesViewDebug.log("tryPatch #" + attempt + " class=" + view.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$

        CommonViewer viewer = getCommonViewer(view);
        if (viewer == null)
        {
            InfobasesViewDebug.log("tryPatch #" + attempt + " WAIT: viewer=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
        {
            InfobasesViewDebug.log("tryPatch #" + attempt + " WAIT: tree=null/disposed"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        if (tree.getData(PATCHED_KEY) != null)
        {
            InfobasesViewDebug.log("tryPatch #" + attempt + " SKIP: already patched"); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }

        Object searchBox = resolveSearchBox(view, viewer);
        if (searchBox == null)
        {
            if (attempt == 0 || attempt % 5 == 0)
                InfobasesViewDebug.log("tryPatch #" + attempt + " WAIT: searchBox=null " //$NON-NLS-1$ //$NON-NLS-2$
                        + describeSearchBoxProbe(view, viewer));
            return false;
        }
        InfobasesViewDebug.log("tryPatch #" + attempt + " searchBox=" + searchBox.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                + " " + SearchBoxFilterAccess.describe(searchBox)); //$NON-NLS-1$

        // Обеспечиваем видимость SearchBox (как в NavigatorFilterHook)
        for (String m : new String[] { "activateSearchBox", "showSearchBox", "expandSearchBox" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Global.invokeVoid(view, m);
        Global.invokeVoid(searchBox, "setVisible", Boolean.TRUE); //$NON-NLS-1$

        SearchBoxFilterAccess searchInput = SearchBoxFilterAccess.resolve(view, searchBox);
        if (searchInput == null)
        {
            InfobasesViewDebug.log("tryPatch #" + attempt + " FAIL: searchInput=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        InfobasesViewDebug.log("tryPatch #" + attempt + " searchInput mode=" + searchInput.mode()); //$NON-NLS-1$ //$NON-NLS-2$

        IBaseLabelProvider rawLp = resolveInfobasesLabelProvider(view, viewer);
        if (rawLp == null)
        {
            InfobasesViewDebug.log("tryPatch #" + attempt + " WAIT: rawLp=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        InfobasesViewDebug.log("tryPatch #" + attempt + " rawLp=" + rawLp.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$

        final NavigatorSearchTextCache searchCache = new NavigatorSearchTextCache();
        ILabelProvider filterLabels = buildFilterLabelProvider(rawLp, searchCache);

        SmartOutlineFilter smartFilter = new SmartOutlineFilter(filterLabels, true, false);
        String initialPattern = searchInput.readPattern();
        smartFilter.setPattern(initialPattern);
        InfobasesViewDebug.log("tryPatch #" + attempt + " initialPattern=\"" + initialPattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        SmartLabelHighlight highlight = installHighlight(viewer, rawLp, filterLabels, searchCache, initialPattern);
        if (highlight == null)
        {
            InfobasesViewDebug.log("tryPatch #" + attempt + " FAIL: highlight=null, rawLp=" + rawLp.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        InfobasesViewDebug.log("tryPatch #" + attempt + " highlight=" + highlight.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                + " lp(after)=" + viewer.getLabelProvider().getClass().getSimpleName()); //$NON-NLS-1$

        // Отключаем нативный InfobaseByNameFilter
        disableNativeFilter(view, viewer, "patch"); //$NON-NLS-1$

        if (!initialPattern.isEmpty())
        {
            viewer.addFilter(smartFilter);
            smartFilter.applyTreeExpansion(viewer);
        }

        applyFilter(view, viewer, smartFilter, highlight, searchCache, initialPattern);

        // Слушаем изменения SearchBox
        final Runnable[] pending = { null };
        final SearchBoxFilterAccess input = searchInput;
        Control focusControl = searchInput.focusControl();

        boolean listenerOk = searchInput.attachPatternListener(view, explicitPattern -> {
            Display d = Display.getDefault();
            if (pending[0] != null) d.timerExec(-1, pending[0]);
            pending[0] = () -> {
                String pattern = explicitPattern != null ? explicitPattern : input.readPattern();
                InfobasesViewDebug.log("modify pattern=\"" + pattern + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                        + (explicitPattern != null ? " explicit" : " read") //$NON-NLS-1$ //$NON-NLS-2$
                        + " mode=" + input.mode()); //$NON-NLS-1$
                applyFilter(view, viewer, smartFilter, highlight, searchCache, pattern);
            };
            d.timerExec(150, pending[0]);
        });
        InfobasesViewDebug.log("tryPatch #" + attempt + " listenerAttached=" + listenerOk //$NON-NLS-1$ //$NON-NLS-2$
                + " mode=" + input.mode()); //$NON-NLS-1$

        if (focusControl != null)
        {
            FilterFieldListNavigation.installTreeNavigation(focusControl, tree);
            focusControl.addDisposeListener(e -> {
                if (pending[0] != null && !focusControl.getDisplay().isDisposed())
                    focusControl.getDisplay().timerExec(-1, pending[0]);
            });
        }

        tree.setData(PATCHED_KEY, Boolean.TRUE);
        InfobasesViewDebug.log("tryPatch #" + attempt + " PATCH OK " + InfobasesViewDebug.filtersDesc(viewer)); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    // -----------------------------------------------------------------------
    // Применение фильтра
    // -----------------------------------------------------------------------

    private static void applyFilter(IViewPart view, CommonViewer viewer,
            SmartOutlineFilter smartFilter, SmartLabelHighlight highlight,
            NavigatorSearchTextCache searchCache, String pattern)
    {
        if (viewer.getControl() == null || viewer.getControl().isDisposed()) return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed()) return;

        String safePattern = pattern != null ? pattern : ""; //$NON-NLS-1$
        if (searchCache != null) searchCache.onPatternChanged(safePattern);

        boolean filtering = !safePattern.isEmpty();
        String lastPattern = tree.getData(LAST_PAT_KEY) instanceof String
                ? (String) tree.getData(LAST_PAT_KEY) : ""; //$NON-NLS-1$
        boolean clearingFilter = !filtering && !lastPattern.isEmpty();

        IStructuredSelection savedSelection = null;
        if (!filtering && viewer.getSelection() instanceof IStructuredSelection)
            savedSelection = (IStructuredSelection) viewer.getSelection();

        InfobasesViewDebug.log("applyFilter pattern=\"" + safePattern + "\"" //$NON-NLS-1$ //$NON-NLS-2$
                + " filtering=" + filtering + " clearing=" + clearingFilter //$NON-NLS-1$ //$NON-NLS-2$
                + " treeItems=" + tree.getItemCount() //$NON-NLS-1$
                + " " + InfobasesViewDebug.filtersDesc(viewer)); //$NON-NLS-1$

        tree.setRedraw(false);
        try
        {
            smartFilter.refreshPattern(safePattern);
            highlight.setHighlightPattern(safePattern);

            if (filtering)
            {
                ensureSmartFilter(viewer, smartFilter);
                disableNativeFilter(view, viewer, "applyFilter-before"); //$NON-NLS-1$
            }
            else
            {
                removeSmartFilter(viewer, smartFilter);
            }

            viewer.refresh();
            ensureHighlightLabelProvider(viewer, highlight);

            if (filtering)
            {
                ensureSmartFilter(viewer, smartFilter);
                disableNativeFilter(view, viewer, "applyFilter-after"); //$NON-NLS-1$
                smartFilter.applyTreeExpansion(viewer);
                viewer.refresh();
                ensureHighlightLabelProvider(viewer, highlight);
            }
            else if (clearingFilter && savedSelection != null)
            {
                restoreSelectionAndExpand(viewer, smartFilter, savedSelection);
            }

            tree.setData(LAST_PAT_KEY, safePattern);
        }
        finally
        {
            tree.setRedraw(true);
            IBaseLabelProvider lp = viewer.getLabelProvider();
            InfobasesViewDebug.log("applyFilter done treeItems=" + tree.getItemCount() //$NON-NLS-1$
                    + " lp=" + (lp != null ? lp.getClass().getSimpleName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                    + " " + InfobasesViewDebug.filtersDesc(viewer)); //$NON-NLS-1$
        }
    }

    private static void ensureSmartFilter(CommonViewer viewer, SmartOutlineFilter smartFilter)
    {
        for (org.eclipse.jface.viewers.ViewerFilter f : viewer.getFilters())
            if (f == smartFilter) return;
        InfobasesViewDebug.log("ensureSmartFilter: adding SmartOutlineFilter"); //$NON-NLS-1$
        viewer.addFilter(smartFilter);
    }

    private static void removeSmartFilter(CommonViewer viewer, SmartOutlineFilter smartFilter)
    {
        viewer.removeFilter(smartFilter);
        InfobasesViewDebug.log("removeSmartFilter: removed"); //$NON-NLS-1$
    }

    /** Сбрасываем нативный {@code InfobaseByNameFilter}: очищаем паттерн и снимаем с viewer-а. */
    private static void disableNativeFilter(IViewPart view, CommonViewer viewer, String where)
    {
        Object nativeFilter = Global.getField(view, "filter"); //$NON-NLS-1$
        if (nativeFilter != null)
        {
            Global.invoke(nativeFilter, "setSearchText", ""); //$NON-NLS-1$ //$NON-NLS-2$
            InfobasesViewDebug.log("disableNativeFilter[" + where + "]: cleared pattern in " //$NON-NLS-1$ //$NON-NLS-2$
                    + nativeFilter.getClass().getSimpleName());
        }
        else
        {
            InfobasesViewDebug.log("disableNativeFilter[" + where + "]: field 'filter' not found in view"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (viewer != null)
        {
            for (org.eclipse.jface.viewers.ViewerFilter f : viewer.getFilters())
            {
                if (f != null && f.getClass().getName().contains("InfobaseByNameFilter")) //$NON-NLS-1$
                {
                    viewer.removeFilter(f);
                    InfobasesViewDebug.log("disableNativeFilter[" + where + "]: removed InfobaseByNameFilter from viewer"); //$NON-NLS-1$ //$NON-NLS-2$
                    break;
                }
            }
        }
    }

    private static void restoreSelectionAndExpand(CommonViewer viewer,
            SmartOutlineFilter smartFilter, IStructuredSelection selection)
    {
        java.util.Set<Object> toExpand = new java.util.LinkedHashSet<>();
        smartFilter.collectRootExpansion(viewer, toExpand);
        if (selection != null && !selection.isEmpty())
            addAncestorChain(viewer, selection.getFirstElement(), toExpand);
        if (!toExpand.isEmpty())
            viewer.setExpandedElements(toExpand.toArray());
        if (selection != null && !selection.isEmpty())
            viewer.setSelection(selection, true);
    }

    private static void addAncestorChain(CommonViewer viewer, Object element, java.util.Set<Object> toExpand)
    {
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof org.eclipse.jface.viewers.ITreeContentProvider)) return;
        org.eclipse.jface.viewers.ITreeContentProvider tcp =
                (org.eclipse.jface.viewers.ITreeContentProvider) cp;
        Object parent = tcp.getParent(element);
        while (parent != null)
        {
            toExpand.add(parent);
            parent = tcp.getParent(parent);
        }
    }

    // -----------------------------------------------------------------------
    // Label provider / подсветка
    // -----------------------------------------------------------------------

    private static IBaseLabelProvider resolveInfobasesLabelProvider(IViewPart view, CommonViewer viewer)
    {
        IBaseLabelProvider lp = viewer.getLabelProvider();
        CellLabelProvider cellLp = findCellLabelProvider(lp);
        if (cellLp != null)
        {
            InfobasesViewDebug.log("resolveInfobasesLabelProvider: lp=" + InfobasesViewDebug.safe(lp) //$NON-NLS-1$
                    + " cellBase=" + cellLp.getClass().getSimpleName()); //$NON-NLS-1$
            return cellLp;
        }
        InfobasesViewDebug.log("resolveInfobasesLabelProvider: lp=" + InfobasesViewDebug.safe(lp)); //$NON-NLS-1$
        return lp;
    }

    /** {@code InfobaseTreeViewerLabelProvider} — {@link CellLabelProvider}, часто внутри обёртки navigator. */
    private static CellLabelProvider findCellLabelProvider(Object lp)
    {
        if (lp instanceof CellLabelHighlightWrapper
                || lp instanceof SmartStyledCellLabelWrapper
                || lp instanceof NavigatorStyledCellLabelWrapper)
            return null;
        if (lp instanceof StyledCellLabelProvider)
            return null;
        if (lp instanceof CellLabelProvider)
            return (CellLabelProvider) lp;
        if (lp == null)
            return null;
        for (Class<?> cls = lp.getClass(); cls != null; cls = cls.getSuperclass())
        {
            for (java.lang.reflect.Field field : cls.getDeclaredFields())
            {
                if (!CellLabelProvider.class.isAssignableFrom(field.getType())
                        || StyledCellLabelProvider.class.isAssignableFrom(field.getType()))
                    continue;
                try
                {
                    field.setAccessible(true);
                    Object value = field.get(lp);
                    if (value instanceof CellLabelProvider)
                        return (CellLabelProvider) value;
                }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** {@code CommonNavigator.refresh()} сбрасывает label provider из content extension. */
    private static void ensureHighlightLabelProvider(CommonViewer viewer, SmartLabelHighlight highlight)
    {
        if (viewer == null || highlight == null)
            return;
        IBaseLabelProvider current = viewer.getLabelProvider();
        if (current == highlight)
            return;
        if (highlight instanceof IBaseLabelProvider)
        {
            viewer.setLabelProvider((IBaseLabelProvider) highlight);
            InfobasesViewDebug.log("ensureHighlightLabelProvider: restored " //$NON-NLS-1$
                    + highlight.getClass().getSimpleName()
                    + " was=" + (current != null ? current.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static ILabelProvider buildFilterLabelProvider(IBaseLabelProvider rawLp,
            NavigatorSearchTextCache searchCache)
    {
        return new ILabelProvider()
        {
            @Override public String getText(Object element)
            {
                return searchCache.searchText(element, rawLp);
            }
            @Override public org.eclipse.swt.graphics.Image getImage(Object element)
            {
                if (rawLp instanceof ILabelProvider)
                    return ((ILabelProvider) rawLp).getImage(element);
                Object img = Global.invoke(rawLp, "getImage", element); //$NON-NLS-1$
                return img instanceof org.eclipse.swt.graphics.Image
                        ? (org.eclipse.swt.graphics.Image) img : null;
            }
            @Override public void addListener(org.eclipse.jface.viewers.ILabelProviderListener l)
            { if (rawLp != null) rawLp.addListener(l); }
            @Override public void dispose() {}
            @Override public boolean isLabelProperty(Object e, String p)
            { return rawLp != null && rawLp.isLabelProperty(e, p); }
            @Override public void removeListener(org.eclipse.jface.viewers.ILabelProviderListener l)
            { if (rawLp != null) rawLp.removeListener(l); }
        };
    }

    private static SmartLabelHighlight installHighlight(CommonViewer viewer, IBaseLabelProvider rawLp,
            ILabelProvider filterLabels, NavigatorSearchTextCache searchCache, String initialPattern)
    {
        if (rawLp instanceof CellLabelHighlightWrapper
                || rawLp instanceof NavigatorStyledCellLabelWrapper
                || rawLp instanceof SmartStyledCellLabelWrapper)
        {
            InfobasesViewDebug.log("installHighlight: already wrapped -> reuse"); //$NON-NLS-1$
            return (SmartLabelHighlight) rawLp;
        }

        if (rawLp instanceof StyledCellLabelProvider)
        {
            InfobasesViewDebug.log("installHighlight: StyledCellLabelProvider -> SmartStyledCellLabelWrapper"); //$NON-NLS-1$
            SmartStyledCellLabelWrapper wrapper = new SmartStyledCellLabelWrapper((StyledCellLabelProvider) rawLp);
            wrapper.setHighlightPattern(initialPattern);
            viewer.setLabelProvider(wrapper);
            return wrapper;
        }

        if (rawLp instanceof CellLabelProvider)
        {
            InfobasesViewDebug.log("installHighlight: CellLabelProvider -> CellLabelHighlightWrapper"); //$NON-NLS-1$
            CellLabelHighlightWrapper wrapper = new CellLabelHighlightWrapper((CellLabelProvider) rawLp);
            wrapper.setHighlightPattern(initialPattern);
            viewer.setLabelProvider(wrapper);
            return wrapper;
        }

        IStyledLabelProvider innerStyled = null;
        if (rawLp instanceof DelegatingStyledCellLabelProvider)
        {
            innerStyled = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
            InfobasesViewDebug.log("installHighlight: DelegatingStyledCellLabelProvider innerStyled=" //$NON-NLS-1$
                    + InfobasesViewDebug.safe(innerStyled));
        }
        else if (rawLp instanceof IStyledLabelProvider)
        {
            innerStyled = (IStyledLabelProvider) rawLp;
            InfobasesViewDebug.log("installHighlight: IStyledLabelProvider"); //$NON-NLS-1$
        }
        else
        {
            InfobasesViewDebug.log("installHighlight: ILabelProvider/other -> SmartOutlineLabelProvider(null,filterLabels)"); //$NON-NLS-1$
        }

        SmartOutlineLabelProvider smartLp = innerStyled != null
                ? new SmartOutlineLabelProvider(innerStyled, filterLabels)
                : new SmartOutlineLabelProvider(null, filterLabels);
        smartLp.setHighlightPattern(initialPattern);

        if (innerStyled != null && rawLp instanceof DelegatingStyledCellLabelProvider)
        {
            InfobasesViewDebug.log("installHighlight: inject into existing DelegatingStyledCellLabelProvider"); //$NON-NLS-1$
            injectStyledStringProvider((DelegatingStyledCellLabelProvider) rawLp, smartLp);
            return smartLp;
        }
        InfobasesViewDebug.log("installHighlight: wrap in new DelegatingStyledCellLabelProvider"); //$NON-NLS-1$
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
                        InfobasesViewDebug.log("injectStyledStringProvider: injected into field " //$NON-NLS-1$
                                + cls.getSimpleName() + "." + field.getName()); //$NON-NLS-1$
                        return;
                    }
                    catch (Exception e)
                    {
                        InfobasesViewDebug.log("injectStyledStringProvider: failed field " //$NON-NLS-1$
                                + field.getName() + " : " + e); //$NON-NLS-1$
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        InfobasesViewDebug.log("injectStyledStringProvider: no IStyledLabelProvider field found"); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Вспомогательные
    // -----------------------------------------------------------------------

    private static CommonViewer getCommonViewer(IViewPart view)
    {
        Object v = Global.invoke(view, "getCommonViewer"); //$NON-NLS-1$
        if (!(v instanceof CommonViewer))
        {
            InfobasesViewDebug.log("getCommonViewer: result=" + InfobasesViewDebug.safe(v)); //$NON-NLS-1$
            return null;
        }
        return (CommonViewer) v;
    }

    /**
     * В {@code InfobasesView} {@code SearchBox} не хранится в поле {@code searchBox}
     * (в отличие от навигатора): виджет создаётся локально в {@code createInfobasesNavigator},
     * на view остаётся только {@code filter} ({@code InfobaseByNameFilter}).
     */
    private static Object resolveSearchBox(IViewPart view, CommonViewer viewer)
    {
        Object direct = Global.getField(view, "searchBox"); //$NON-NLS-1$
        if (direct != null)
            return direct;

        Tree tree = viewer != null ? viewer.getTree() : null;
        if (tree != null && !tree.isDisposed())
        {
            for (Composite parent = tree.getParent(); parent != null; parent = parent.getParent())
            {
                Object found = AefComponentSearchHighlight.findSearchBox(parent);
                if (found != null)
                    return found;
            }
        }

        for (String field : new String[] { "mainPage", "pageBook" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Object page = Global.getField(view, field);
            if (page instanceof Composite)
            {
                Object found = AefComponentSearchHighlight.findSearchBox((Composite) page);
                if (found != null)
                    return found;
            }
        }

        for (Class<?> cls = view.getClass(); cls != null; cls = cls.getSuperclass())
        {
            for (java.lang.reflect.Field field : cls.getDeclaredFields())
            {
                if (!field.getType().getName().contains("SearchBox")) //$NON-NLS-1$
                    continue;
                try
                {
                    field.setAccessible(true);
                    Object value = field.get(view);
                    if (value != null)
                        return value;
                }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String describeSearchBoxProbe(IViewPart view, CommonViewer viewer)
    {
        StringBuilder sb = new StringBuilder("probe{"); //$NON-NLS-1$
        sb.append("filter=").append(InfobasesViewDebug.safe(Global.getField(view, "filter"))); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(" mainPage=").append(InfobasesViewDebug.safe(Global.getField(view, "mainPage"))); //$NON-NLS-1$ //$NON-NLS-2$
        Tree tree = viewer != null ? viewer.getTree() : null;
        if (tree == null || tree.isDisposed())
        {
            sb.append(" tree=null"); //$NON-NLS-1$
        }
        else
        {
            Composite parent = tree.getParent();
            sb.append(" treeParent=").append(InfobasesViewDebug.safe(parent)); //$NON-NLS-1$
            if (parent != null && !parent.isDisposed())
            {
                Control[] children = parent.getChildren();
                sb.append(" siblings=").append(children.length).append('['); //$NON-NLS-1$
                for (int i = 0; i < children.length && i < 6; i++)
                {
                    if (i > 0)
                        sb.append(','); //$NON-NLS-1$
                    sb.append(children[i].getClass().getSimpleName());
                }
                sb.append(']');
            }
        }
        return sb.append('}').toString();
    }
}
