package tormozit;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IColorProvider;
import org.eclipse.jface.viewers.IFontProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.IToolTipProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchFilterWithHistory;

/**
 * Многословный фильтр ({@link SmartMatcher}, плоское И по словам) с раскраской совпадений
 * в панели «Все подписки на события» ({@code EventHandlersEditor} бандла
 * {@code com._1c.g5.v8.dt.eventhandlers.ui}) — как в отдельном редакторе EDT, так и на
 * встроенной странице редактора объекта ({@link MdEventHandlersPageHook}).
 *
 * <p>Штатное поведение (декомпиляция {@code MainSection}): поле поиска — уже
 * {@link SearchBox}, но матчинг делает {@code EventHandlersSearchHistoryWithFilter}
 * ({@code SearchFilterWithHistory}) через {@code FuzzyPattern} и только по двум полям —
 * полному имени владельца метода и имени подписки; поиск запускается от 3 символов, а
 * фильтр вешается на дерево только при непустом запросе. Раскраску вхождений штатный
 * {@code AbstractEventHandlersStyledLabelProvider} берёт из того же
 * {@code SearchFilterWithHistory}, найденного среди фильтров дерева.
 *
 * <p>Что делает хук: штатный {@code SearchFilterWithHistory} снимается с дерева (его
 * {@code FuzzyPattern} больше не участвует, и штатная раскраска сама отключается —
 * {@code getSearchFilter()} возвращает {@code null}), вместо него — свой
 * {@link ViewerFilter} на {@link SmartMatcher} по видимой подписи узла (её же даёт
 * штатный label provider) и своя раскраска вхождений поверх штатного
 * {@code IStyledLabelProvider}, внедрённая в штатный
 * {@link DelegatingStyledCellLabelProvider} (плюс {@code COLORS_ON_SELECTION}, иначе
 * цвет пропадает на выделенной строке). {@code SearchBox} получает персистентную
 * историю ({@link FilterInputBox#attachHistory} / {@link FilterInputBox.Scope#EVENT_HANDLERS})
 * и порог длины запроса 0 — фильтрация с первого символа.
 *
 * <p>Узел виден, если под фильтр попадает его собственная подпись или подпись любого
 * потомка (папка события — по имени события, элемент — по тому, что видно в строке).
 * Штатный фильтр по источникам/событиям/обработчикам («Текущий отбор») не трогается и
 * работает вместе с нашим (JFace применяет фильтры по И).
 */
public final class EventHandlersFilterHook implements IStartup
{
    private static final String TAG = "EventHandlersFilter"; //$NON-NLS-1$

    private static final String EDITOR_CLASS =
        "com._1c.g5.v8.dt.eventhandlers.ui.editor.EventHandlersEditor"; //$NON-NLS-1$

    private static final String PATCHED_KEY = "tormozit.eventHandlersFilterPatched"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)       { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)    {}
                @Override public void windowDeactivated(IWorkbenchWindow w)  {}
                @Override public void windowClosed(IWorkbenchWindow w)       {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;

        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
                patchIfEventHandlersEditor(ref.getEditor(false));
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)    { patchFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { patchFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void patchFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IWorkbenchPart part = ((IEditorReference)ref).getPart(false);
                if (part instanceof IEditorPart editorPart)
                    patchIfEventHandlersEditor(editorPart);
            }
        });
    }

    private static void patchIfEventHandlersEditor(Object editor)
    {
        if (editor != null && EDITOR_CLASS.equals(editor.getClass().getName()))
            patchEditor(editor);
    }

    /**
     * Подключает фильтр к уже созданному {@code EventHandlersEditor} — в том числе к
     * встроенному в страницу редактора объекта, который не является частью workbench
     * и не приходит через {@link IPartListener2} (см. {@link MdEventHandlersPageHook}).
     */
    static void patchEditor(Object editor)
    {
        scheduleTryPatch(editor, 0);
    }

    private static void scheduleTryPatch(Object editor, int attempt)
    {
        if (editor == null || attempt >= MAX_ATTEMPTS)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        Runnable task = () ->
        {
            if (tryPatch(editor))
                return;
            scheduleTryPatch(editor, attempt + 1);
        };
        if (attempt == 0)
            display.asyncExec(task);
        else
            display.timerExec(RETRY_MS, task);
    }

    /** @return {@code true}, если делать больше нечего (пропатчено, отключено или недоступно). */
    private static boolean tryPatch(Object editor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return true;
        try
        {
            Object mainSection = Global.invoke(editor, "getMainSection"); //$NON-NLS-1$
            if (mainSection == null)
                return false;

            Object viewerObj = Global.invoke(mainSection, "getEventHandlersTreeViewer"); //$NON-NLS-1$
            Object searchBoxObj = Global.getField(mainSection, "searchBox"); //$NON-NLS-1$
            if (!(viewerObj instanceof TreeViewer viewer) || !(searchBoxObj instanceof SearchBox searchBox)
                || searchBox.isDisposed())
                return false;

            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return false;
            if (Boolean.TRUE.equals(tree.getData(PATCHED_KEY)))
                return true;

            LabelHighlight highlight = installHighlight(viewer);
            if (highlight == null)
                return false;

            tree.setData(PATCHED_KEY, Boolean.TRUE);

            SearchFilter filter = new SearchFilter(highlight);
            final int[] applyGeneration = { 0 };

            searchBox.setToolTipText(
                FilterInputBox.FLAT_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
            searchBox.setMinimumSearchTextLength(0);
            searchBox.setJobScheduleDelay(0);
            FilterInputBox.attachHistory(searchBox, FilterInputBox.Scope.EVENT_HANDLERS);
            searchBox.setSearchListener(new SearchBox.ISearchListener()
            {
                @Override
                public void performSearch(String text, IProgressMonitor monitor)
                {
                    // История / Enter. Живой ввод — SWT.Modify: Job SearchBox при
                    // restart=true глотает последний символ (см. ModuleMergeStructureFilterHook).
                    scheduleApply(searchBox, mainSection, viewer, filter, highlight, applyGeneration);
                }
            });
            searchBox.setRunSearchOnTextChange(false);
            searchBox.addModifyListener(e ->
            {
                if (searchBox.isDisposed())
                    return;
                scheduleApply(searchBox, mainSection, viewer, filter, highlight, applyGeneration);
            });

            String initial = searchBox.getText();
            if (initial != null && !initial.isEmpty())
                apply(searchBox, mainSection, viewer, filter, highlight);

            Debug.log("tryPatch PATCH OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "tryPatch", e); //$NON-NLS-1$
            return true;
        }
    }

    private static void scheduleApply(SearchBox searchBox, Object mainSection, TreeViewer viewer,
        SearchFilter filter, LabelHighlight highlight, int[] applyGeneration)
    {
        if (searchBox == null || searchBox.isDisposed())
            return;
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;
        int generation = ++applyGeneration[0];
        display.asyncExec(() ->
        {
            if (searchBox.isDisposed() || generation != applyGeneration[0])
                return;
            apply(searchBox, mainSection, viewer, filter, highlight);
        });
    }

    private static void apply(SearchBox searchBox, Object mainSection, TreeViewer viewer,
        SearchFilter filter, LabelHighlight highlight)
    {
        try
        {
            if (viewer.getControl() == null || viewer.getControl().isDisposed())
                return;

            String pattern = searchBox != null && !searchBox.isDisposed()
                ? searchBox.getText().trim() : ""; //$NON-NLS-1$
            boolean filtering = !pattern.isEmpty();
            filter.setPattern(pattern);
            highlight.setHighlightPattern(pattern);

            removeStockSearchFilter(viewer);

            boolean attached = false;
            for (ViewerFilter existing : viewer.getFilters())
                if (existing == filter)
                    attached = true;
            if (filtering && !attached)
                viewer.addFilter(filter);
            else if (!filtering && attached)
                viewer.removeFilter(filter);
            else
                viewer.refresh();

            // Как штатный SearchListener: при активном фильтре («Текущий отбор» тоже)
            // дерево раскрывается полностью, иначе остаётся как есть.
            if (filtering || hasActiveEventHandlersFilter(mainSection))
                viewer.expandAll();
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "apply", e); //$NON-NLS-1$
        }
    }

    /** Штатный {@code EventHandlersSearchHistoryWithFilter} — снимаем: его паттерн больше не заполняется. */
    private static void removeStockSearchFilter(TreeViewer viewer)
    {
        for (ViewerFilter existing : viewer.getFilters())
            if (existing instanceof SearchFilterWithHistory)
                viewer.removeFilter(existing);
    }

    /** Отбор по источникам/событиям/обработчикам («Текущий отбор») задан. */
    private static boolean hasActiveEventHandlersFilter(Object mainSection)
    {
        Object filter = Global.invoke(mainSection, "getEventHandlersFilter"); //$NON-NLS-1$
        if (filter == null)
            return false;
        for (String getter : new String[] { "getSources", "getEvents", "getHandlers" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object value = Global.invoke(filter, getter);
            if (value instanceof Collection<?> collection && !collection.isEmpty())
                return true;
        }
        return false;
    }

    /**
     * Внедряет раскраску в штатный {@link DelegatingStyledCellLabelProvider} дерева
     * (label provider выставлен на самом {@code TreeViewer}, отдельных колонок у дерева нет).
     */
    private static LabelHighlight installHighlight(TreeViewer viewer)
    {
        IBaseLabelProvider raw = viewer.getLabelProvider();
        if (!(raw instanceof DelegatingStyledCellLabelProvider delegating))
            return null;

        IStyledLabelProvider inner = delegating.getStyledStringProvider();
        if (inner instanceof LabelHighlight existing)
        {
            SmartMatchHighlight.enableColorsOnSelection(delegating);
            return existing;
        }
        if (inner == null)
            return null;

        LabelHighlight highlight = new LabelHighlight(inner);
        if (!injectStyledStringProvider(delegating, highlight))
            return null;
        SmartMatchHighlight.enableColorsOnSelection(delegating);
        return highlight;
    }

    private static boolean injectStyledStringProvider(DelegatingStyledCellLabelProvider provider,
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
                        return true;
                    }
                    catch (Exception ignored)
                    {
                        // следующее поле / суперкласс
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    /**
     * Узел виден, если {@link SmartMatcher} совпал с его видимой подписью или с подписью
     * любого потомка. Подпись берётся у штатного label provider — ровно то, что
     * пользователь видит в строке (имя события, имя подписки, владелец обработчика).
     */
    private static final class SearchFilter extends ViewerFilter
    {
        private final LabelHighlight labels;

        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

        private final Map<Object, Boolean> subtreeMemo = new IdentityHashMap<>();

        SearchFilter(LabelHighlight labels)
        {
            this.labels = labels;
        }

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            return matchesOwnOrDescendant(viewer, element);
        }

        private boolean matchesOwnOrDescendant(Viewer viewer, Object element)
        {
            Boolean memo = subtreeMemo.get(element);
            if (memo != null)
                return memo.booleanValue();

            String text = labels.plainText(element);
            boolean result = text != null && !text.isEmpty() && matcher.matches(text);
            if (!result && viewer instanceof TreeViewer treeViewer
                && treeViewer.getContentProvider() instanceof ITreeContentProvider tcp)
            {
                Object[] children = tcp.getChildren(element);
                if (children != null)
                {
                    for (Object child : children)
                    {
                        if (matchesOwnOrDescendant(viewer, child))
                        {
                            result = true;
                            break;
                        }
                    }
                }
            }
            subtreeMemo.put(element, result);
            return result;
        }
    }

    /**
     * Раскраска вхождений фильтра поверх штатного {@code IStyledLabelProvider}. Цвет/шрифт/
     * подсказку делегирует ему же — {@link DelegatingStyledCellLabelProvider} читает их
     * только у своего внутреннего provider'а.
     */
    private static final class LabelHighlight implements IStyledLabelProvider, SmartLabelHighlight,
        IColorProvider, IFontProvider, IToolTipProvider
    {
        private final IStyledLabelProvider delegate;

        private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

        LabelHighlight(IStyledLabelProvider delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void setHighlightPattern(String pattern)
        {
            highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        /** Видимая подпись узла без стилей — по ней же идёт и фильтрация. */
        String plainText(Object element)
        {
            StyledString styled = delegate.getStyledText(element);
            return styled != null ? styled.getString() : null;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = delegate.getStyledText(element);
            if (styled == null)
                styled = new StyledString();
            if (highlightMatcher.isEmpty)
                return styled;
            String text = styled.getString();
            if (text == null || text.isEmpty() || !highlightMatcher.matches(text))
                return styled;
            List<SmartMatcher.HighlightRange> ranges = highlightMatcher.getHighlightRanges(text);
            SmartMatchHighlight.applyRanges(styled, ranges);
            return styled;
        }

        @Override
        public Image getImage(Object element)
        {
            return delegate.getImage(element);
        }

        @Override
        public Color getForeground(Object element)
        {
            if (delegate instanceof IColorProvider colors)
                return colors.getForeground(element);
            Object color = Global.invoke(delegate, "getForeground", element); //$NON-NLS-1$
            return color instanceof Color c ? c : null;
        }

        @Override
        public Color getBackground(Object element)
        {
            if (delegate instanceof IColorProvider colors)
                return colors.getBackground(element);
            Object color = Global.invoke(delegate, "getBackground", element); //$NON-NLS-1$
            return color instanceof Color c ? c : null;
        }

        @Override
        public Font getFont(Object element)
        {
            if (delegate instanceof IFontProvider fonts)
                return fonts.getFont(element);
            Object font = Global.invoke(delegate, "getFont", element); //$NON-NLS-1$
            return font instanceof Font f ? f : null;
        }

        @Override
        public String getToolTipText(Object element)
        {
            if (delegate instanceof IToolTipProvider tips)
                return tips.getToolTipText(element);
            Object tip = Global.invoke(delegate, "getToolTipText", element); //$NON-NLS-1$
            return tip instanceof String s ? s : null;
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            delegate.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            delegate.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate.isLabelProperty(element, property);
        }

        @Override
        public void dispose()
        {
            delegate.dispose();
        }
    }

    private static final class Debug
    {
        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
