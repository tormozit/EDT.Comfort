package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.ui.editor.ComparisonTreeControl;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.ExternalPropertyPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;

/**
 * Многословный фильтр ({@link SmartMatcher}) в диалоге EDT «Настройка объединения модулей»
 * ({@code CompareBslModuleWithParsingModuleStructureDialog}): штатный {@code SearchViewerFilter}
 * матчит {@code StringUtils.fuzzyMatch} по подстроке без раскраски.
 *
 * <p>Штатный {@link SearchBox} остаётся на месте (лупа/крестик уже есть); меняются слушатель,
 * {@link ViewerFilter} и подсветка колонок. История — {@link FilterInputBox#attachHistory}
 * / {@link FilterInputBox.Scope#COMPARE_STRUCTURE} (та же, что у панели «Структура»
 * попарного сравнения). Штатный фильтр не получает паттерн ({@code searchFilter.pattern}
 * остаётся пустым), поэтому {@code fuzzyMatch} не участвует.
 */
public final class ModuleMergeStructureFilterHook implements IStartup
{
    private static final String DIALOG_SNIPPET = "CompareBslModuleWithParsingModuleStructureDialog"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.moduleMergeStructureFilterPatched"; //$NON-NLS-1$
    private static final String COLUMN_VIEWER_KEY = "org.eclipse.jface.columnViewer"; //$NON-NLS-1$
    private static final int MAX_ATTEMPTS = 40;
    private static final int RETRY_MS = 50;
    private static final int STOCK_EXPAND_LEVEL = 2;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (!isModuleMergeStructureDialog(shell))
                return;
            scheduleTryPatch(shell, 0);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        Debug.log("install: installed"); //$NON-NLS-1$
    }

    private static boolean isModuleMergeStructureDialog(Shell shell)
    {
        Object data = shell.getData();
        return data != null && data.getClass().getName().contains(DIALOG_SNIPPET);
    }

    private static void scheduleTryPatch(Shell shell, int attempt)
    {
        if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
            return;
        if (attempt >= MAX_ATTEMPTS)
        {
            Debug.log("tryPatch: не удалось после " + MAX_ATTEMPTS + " попыток"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        int delay = attempt == 0 ? 0 : RETRY_MS;
        shell.getDisplay().timerExec(delay, () ->
        {
            if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
                return;
            if (tryPatch(shell))
                return;
            scheduleTryPatch(shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        try
        {
            Object dialog = shell.getData();
            if (dialog == null || !dialog.getClass().getName().contains(DIALOG_SNIPPET))
                return true;

            SearchBox searchBox = findSearchBox(shell);
            Object comparisonViewObj = Global.getField(dialog, "comparisonView"); //$NON-NLS-1$
            if (searchBox == null || searchBox.isDisposed()
                || !(comparisonViewObj instanceof DtComparisonView comparisonView))
                return false;

            ComparisonTreeControl treeControl = comparisonView.getTreeControl();
            if (treeControl == null)
                return false;
            TreeViewer viewer = treeControl.getTreeViewer();
            if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
                return false;

            shell.setData(PATCHED_KEY, Boolean.TRUE);

            StructureSearchFilter filter = new StructureSearchFilter();
            List<SmartLabelHighlight> highlights = installHighlights(viewer);
            final int[] applyGeneration = { 0 };

            searchBox.setToolTipText(
                FilterInputBox.FLAT_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
            searchBox.setMinimumSearchTextLength(0);
            searchBox.setJobScheduleDelay(0);
            FilterInputBox.attachHistory(searchBox, FilterInputBox.Scope.COMPARE_STRUCTURE);
            searchBox.setSearchListener(new SearchBox.ISearchListener()
            {
                @Override
                public void performSearch(String text, IProgressMonitor monitor)
                {
                    // История / Enter. Живой ввод — SWT.Modify: Job SearchBox
                    // при restart=true глотает последний символ.
                    scheduleApply(searchBox, dialog, treeControl, comparisonView, filter, highlights,
                        applyGeneration);
                }
            });
            // Не Job на каждый символ: фильтрация с refresh внутри Modify рвёт
            // последнее нажатие (в логе был modify без apply).
            searchBox.setRunSearchOnTextChange(false);
            searchBox.addModifyListener(e ->
            {
                if (searchBox.isDisposed())
                    return;
                scheduleApply(searchBox, dialog, treeControl, comparisonView, filter, highlights,
                    applyGeneration);
            });
            FilterInputBoxListNavigation.installTreeNavigation(searchBox, viewer.getTree());
            wireFilterComboReapply(comparisonView, searchBox, dialog, treeControl, filter,
                highlights, applyGeneration);

            String initial = searchBox.getText();
            if (initial != null && !initial.isEmpty())
                apply(searchBox, dialog, treeControl, comparisonView, filter, highlights);

            Debug.log("tryPatch PATCH OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            shell.setData(PATCHED_KEY, null);
            Global.logError("ModuleMergeStructureFilter", "tryPatch", e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    /**
     * Combo «Фильтр:» вызывает {@code ComparisonTreeControl.setFilters} и вытесняет наш
     * {@link ViewerFilter}; после смены именованного фильтра возвращаем его, если в поле
     * ещё есть текст.
     */
    private static void wireFilterComboReapply(DtComparisonView comparisonView, SearchBox searchBox,
        Object dialog, ComparisonTreeControl treeControl, StructureSearchFilter filter,
        List<SmartLabelHighlight> highlights, int[] applyGeneration)
    {
        Object filterRow = Global.getField(comparisonView, "filterControl"); //$NON-NLS-1$
        Combo combo = filterRow instanceof Control control ? findCombo(control) : null;
        if (combo == null || combo.isDisposed())
            return;
        combo.addListener(SWT.Selection, e ->
        {
            if (searchBox.isDisposed())
                return;
            String text = searchBox.getText();
            if (text == null || text.isBlank())
                return;
            scheduleApply(searchBox, dialog, treeControl, comparisonView, filter, highlights,
                applyGeneration);
        });
    }

    private static Combo findCombo(Control root)
    {
        if (root instanceof Combo combo)
            return combo;
        if (root instanceof Composite composite)
            for (Control child : composite.getChildren())
            {
                Combo found = findCombo(child);
                if (found != null)
                    return found;
            }
        return null;
    }

    private static void scheduleApply(SearchBox searchBox, Object dialog, ComparisonTreeControl treeControl,
        DtComparisonView comparisonView, StructureSearchFilter filter, List<SmartLabelHighlight> highlights,
        int[] applyGeneration)
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
            apply(searchBox, dialog, treeControl, comparisonView, filter, highlights);
        });
    }

    private static void apply(SearchBox searchBox, Object dialog, ComparisonTreeControl treeControl,
        DtComparisonView comparisonView, StructureSearchFilter filter, List<SmartLabelHighlight> highlights)
    {
        try
        {
            if (treeControl == null || treeControl.isDisposed()
                || comparisonView == null || comparisonView.isDisposed())
                return;

            String pattern = searchBox != null && !searchBox.isDisposed()
                ? searchBox.getText().trim() : ""; //$NON-NLS-1$
            boolean filtering = !pattern.isEmpty();
            filter.setPattern(pattern);
            for (SmartLabelHighlight highlight : highlights)
                highlight.setHighlightPattern(pattern);

            Object stockFilter = Global.getField(dialog, "searchFilter"); //$NON-NLS-1$
            if (stockFilter instanceof ViewerFilter stockVf)
            {
                Global.setField(stockFilter, "pattern", ""); //$NON-NLS-1$ //$NON-NLS-2$
                for (ViewerFilter existing : treeControl.getFilters())
                    if (existing == stockVf)
                        treeControl.removeFilter(stockVf);
            }

            // Штатный applyFilters: повесить фильтр, если его ещё нет; иначе только
            // refresh(node). Снимать/вешать каждый раз не нужно — и опасно: addFilter
            // делает refresh, и при исключении в select дерево остаётся без фильтра.
            List<ViewerFilter> current = Arrays.asList(treeControl.getFilters());
            if (filtering)
            {
                if (!current.contains(filter))
                    treeControl.addFilter(filter);
            }
            else if (current.contains(filter))
                treeControl.removeFilter(filter);

            // Штатный applyFilters: refresh(node), не viewer.refresh().
            Object node = Global.getField(dialog, "node"); //$NON-NLS-1$
            if (node instanceof IPartialModelNode partial)
                treeControl.refresh(partial);
            else
                treeControl.getTreeViewer().refresh();

            TreeViewer viewer = treeControl.getTreeViewer();
            if (filtering)
            {
                viewer.expandAll();
                selectFirstVisibleIfSelectionLost(viewer, filter);
            }
            else
                comparisonView.expandToLevel(STOCK_EXPAND_LEVEL);
        }
        catch (RuntimeException e)
        {
            Global.logError("ModuleMergeStructureFilter", "apply", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Если прежняя активная строка скрыта фильтром — выделяем первую видимую,
     * которая сама совпала с запросом (не корень «Модуль объекта»).
     */
    private static void selectFirstVisibleIfSelectionLost(TreeViewer viewer, StructureSearchFilter filter)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getItemCount() == 0)
            return;
        TreeItem[] current = tree.getSelection();
        if (current != null && current.length > 0 && !current[0].isDisposed()
            && filter.matchesElement(current[0].getData()))
            return;
        TreeItem first = firstMatchingVisibleItem(tree.getItems(), filter);
        if (first == null)
            first = tree.getItem(0);
        if (first == null || first.isDisposed())
            return;
        Object data = first.getData();
        if (data != null)
            viewer.setSelection(new StructuredSelection(data), true);
        else
            tree.setSelection(first);
        tree.showItem(first);
        Event event = new Event();
        event.widget = tree;
        event.item = first;
        tree.notifyListeners(SWT.Selection, event);
    }

    private static TreeItem firstMatchingVisibleItem(TreeItem[] items, StructureSearchFilter filter)
    {
        if (items == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (filter.matchesElement(item.getData()))
                return item;
            TreeItem nested = firstMatchingVisibleItem(item.getItems(), filter);
            if (nested != null)
                return nested;
        }
        return null;
    }

    /** Корень «Модуль» / «Модуль объекта» — штатный фильтр его подпись не матчит. */
    private static boolean isStructureRoot(Object element)
    {
        return element instanceof ExternalPropertyPartialModelNode;
    }

    private static List<SmartLabelHighlight> installHighlights(TreeViewer viewer)
    {
        List<SmartLabelHighlight> result = new ArrayList<>();
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return result;

        int count = tree.getColumnCount();
        for (int i = 0; i < count; i++)
        {
            TreeViewerColumn column = resolveViewerColumn(viewer, tree, i);
            if (column == null)
                continue;
            Object lpObj = Global.invoke(column, "getLabelProvider"); //$NON-NLS-1$
            if (!(lpObj instanceof CellLabelProvider lp))
                continue;

            if (lp instanceof CellLabelHighlightWrapper existing)
            {
                existing.setSkipHighlight(ModuleMergeStructureFilterHook::isStructureRoot);
                SmartMatchHighlight.enableColorsOnSelection(existing);
                result.add(existing);
                continue;
            }
            if (lp instanceof DelegatingStyledCellLabelProvider delegating)
            {
                ObjectColumnHighlight highlight = installObjectColumnHighlight(delegating);
                if (highlight != null)
                    result.add(highlight);
                continue;
            }
            if (!isSideColumnProvider(lp))
                continue;

            CellLabelHighlightWrapper wrapper = new CellLabelHighlightWrapper(lp);
            wrapper.setSkipHighlight(ModuleMergeStructureFilterHook::isStructureRoot);
            SmartMatchHighlight.enableColorsOnSelection(wrapper);
            column.setLabelProvider(wrapper);
            result.add(wrapper);
        }
        return result;
    }

    private static boolean isSideColumnProvider(CellLabelProvider lp)
    {
        String name = lp.getClass().getSimpleName();
        return "ComparisonSideColumnLabelProvider".equals(name) //$NON-NLS-1$
            || "ModuleStructureLabelProvider".equals(name); //$NON-NLS-1$
    }

    private static ObjectColumnHighlight installObjectColumnHighlight(DelegatingStyledCellLabelProvider delegating)
    {
        IStyledLabelProvider inner = delegating.getStyledStringProvider();
        if (inner instanceof ObjectColumnHighlight existing)
        {
            SmartMatchHighlight.enableColorsOnSelection(delegating);
            return existing;
        }
        ObjectColumnHighlight highlight = new ObjectColumnHighlight(inner);
        injectStyledStringProvider(delegating, highlight);
        SmartMatchHighlight.enableColorsOnSelection(delegating);
        return highlight;
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

    private static TreeViewerColumn resolveViewerColumn(TreeViewer viewer, Tree tree, int index)
    {
        Object vc = Global.invoke(viewer, "getViewerColumn", Integer.valueOf(index)); //$NON-NLS-1$
        if (vc instanceof TreeViewerColumn tvc)
            return tvc;
        if (index >= 0 && index < tree.getColumnCount())
        {
            TreeColumn column = tree.getColumn(index);
            if (column != null)
            {
                Object colData = column.getData(COLUMN_VIEWER_KEY);
                if (colData instanceof TreeViewerColumn tvcFromData)
                    return tvcFromData;
            }
        }
        return null;
    }

    private static SearchBox findSearchBox(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof SearchBox sb)
            return sb;
        if (root instanceof Composite composite)
            for (Control child : composite.getChildren())
            {
                SearchBox found = findSearchBox(child);
                if (found != null)
                    return found;
            }
        return null;
    }

    /**
     * Как штатный {@code SearchViewerFilter}: корень виден, если виден потомок;
     * секция — если {@link SmartMatcher} совпал с подписью MAIN или OTHER.
     */
    private static final class StructureSearchFilter extends ViewerFilter
    {
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        private final Map<Object, Boolean> subtreeMemo = new IdentityHashMap<>();

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            subtreeMemo.clear();
        }

        boolean matchesElement(Object element)
        {
            return matchesOwn(element);
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

            boolean result = matchesOwn(element);
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

        private boolean matchesOwn(Object element)
        {
            // Как штатный SearchViewerFilter: корень — только дети; секция — MAIN/OTHER.
            if (isStructureRoot(element) || !(element instanceof IPartialModelNode node))
                return false;
            return matchesSide(node, ComparisonSide.MAIN) || matchesSide(node, ComparisonSide.OTHER);
        }

        private boolean matchesSide(IPartialModelNode node, ComparisonSide side)
        {
            String label = node.getSideLabel(side);
            return label != null && !label.isEmpty() && matcher.matches(label);
        }
    }

    private static final class ObjectColumnHighlight implements IStyledLabelProvider, SmartLabelHighlight
    {
        private final IStyledLabelProvider delegate;
        private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

        ObjectColumnHighlight(IStyledLabelProvider delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void setHighlightPattern(String pattern)
        {
            highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString styled = delegate.getStyledText(element);
            if (styled == null)
                styled = new StyledString();
            if (isStructureRoot(element) || highlightMatcher.isEmpty)
                return styled;
            String text = styled.getString();
            if (text == null || text.isEmpty() || !highlightMatcher.matches(text))
                return styled;
            SmartMatchHighlight.applyRanges(styled, highlightMatcher.getHighlightRanges(text));
            return styled;
        }

        @Override
        public Image getImage(Object element)
        {
            return delegate.getImage(element);
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
        private static final String TAG = "ModuleMergeStructureFilter"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
