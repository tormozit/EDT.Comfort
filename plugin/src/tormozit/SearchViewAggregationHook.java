package tormozit;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
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

/**
 * Доработка панели глобального поиска по метаданным (см. также {@link FileSearchResultsHook} для поиска по файлам).
 *
 * <p>Issue #79 (п.4): при выборе в панели глобального поиска ({@code org.eclipse.search.ui.views.SearchView},
 * страница {@code com._1c.g5.v8.dt.internal.search.ui.page.ConfigurationSearchViewPage}) узла-группы
 * (объект с несколькими дочерними модулями/подобъектами) в правой таблице должны отображаться
 * вхождения ВСЕХ потомков, а не только собственные вхождения выбранного узла.
 *
 * <p>Штатная логика: {@code TreeSearchViewPageLayout} вешает на левый {@code TreeViewer}
 * {@code addPostSelectionChangedListener}, который на каждое изменение выбора вызывает
 * {@code TreeSearchViewTablePartModel.changeSource(selection.toArray())}; внутри для каждого
 * выбранного {@code MatchTreeItem} берётся только {@code item.getTableItems()} — собственные
 * вхождения узла, без рекурсии по {@code item.getChildren()}.
 *
 * <p>Хук добавляет свой {@code addPostSelectionChangedListener} на тот же {@code TreeViewer}
 * (регистрируется позже штатного — срабатывает после него) и при обнаружении среди выбранных
 * элементов узла с непустыми потомками — перекрывает результат: рекурсивно собирает вхождения
 * всех потомков и повторно вызывает {@code changeSource(...)} с полным списком. Одновременно
 * строится карта «вхождение → путь» (конкатенация подписей узлов от корня), используемая
 * колонкой «Путь», добавляемой в правую таблицу.
 *
 * <p>Все внутренние 1С-классы ({@code ConfigurationSearchViewPage}, {@code TreeSearchViewPageLayout},
 * {@code MatchTreeItem}, {@code TreeSearchViewTablePartModel}) — из закрытого пакета
 * {@code com._1c.g5.v8.dt.internal.search.ui.*}, поэтому весь доступ — только через
 * {@link Global#getField}/{@link Global#invoke} (см. журнал: Параметры → Комфорт → «Общее логирование»).
 *
 * <p>Включение: Параметры → Комфорт → «Улучшать списки» ({@link ComfortSettings#PREF_REPLACE_LIST_FILTERS}).
 */
public final class SearchViewAggregationHook implements IStartup
{
    private static final String SEARCH_VIEW_ID = "org.eclipse.search.ui.views.SearchView"; //$NON-NLS-1$
    private static final String PAGE_CLASS_MARKER = "ConfigurationSearchViewPage"; //$NON-NLS-1$

    private static final String HOOKED_KEY = "tormozit.searchAggregationHooked"; //$NON-NLS-1$
    private static final String TREE_COUNT_LABEL_HOOKED_KEY = "tormozit.searchTreeCountLabelHooked"; //$NON-NLS-1$
    /** Как {@code Messages.IMatchItem_Total_matches_count_pattern__0} в search.ui. */
    private static final String MATCH_COUNT_SUFFIX_PATTERN = " ({0} \u0441\u043E\u043E\u0442\u0432\u0435\u0442\u0441\u0442\u0432\u0438\u0439)"; //$NON-NLS-1$

    // -----------------------------------------------------------------------
    // IStartup
    // -----------------------------------------------------------------------

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            // Основной триггер: каждый выполненный/завершённый поиск. В отличие от событий
            // активации/открытия панели срабатывает надёжно даже, когда панель уже была
            // открыта до выполнения нового поиска и не получила ни одного part-события.
            NewSearchUI.addQueryListener(new IQueryListener()
            {
                @Override public void queryAdded(ISearchQuery query)     { onQueryEvent("queryAdded"); } //$NON-NLS-1$
                @Override public void queryRemoved(ISearchQuery query)    {}
                @Override public void queryStarting(ISearchQuery query)   { onSearchStarting(); }
                @Override public void queryFinished(ISearchQuery query)   { onSearchFinished(); onQueryEvent("queryFinished"); } //$NON-NLS-1$
            });
        });
    }

    private static void onQueryEvent(String source)
    {
        log("onQueryEvent: " + source); //$NON-NLS-1$
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            IViewPart view = findSearchViewPart();
            if (view != null)
                schedulePatch(view, 0);
            else
                log("onQueryEvent: search view part не найден"); //$NON-NLS-1$
        });
    }

    /** Пока идёт поиск — удерживаем первую строку дерева и обновляем агрегированную таблицу. */
    private static volatile boolean searchQueryRunning;
    /** После старта поиска блокируем штатный спуск к терминальному узлу (в т.ч. в конце). */
    private static volatile boolean guardFirstRootSelection;

    private static final class SearchViewViewers
    {
        final TreeViewer tree;
        final TableViewer table;

        SearchViewViewers(TreeViewer tree, TableViewer table)
        {
            this.tree = tree;
            this.table = table;
        }
    }

    private static void onSearchStarting()
    {
        searchQueryRunning = true;
        guardFirstRootSelection = true;
        SAVED_TABLE_SELECTION_BY_VIEWER.clear();
        log("onSearchStarting: watch first root"); //$NON-NLS-1$
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            IViewPart view = findSearchViewPart();
            if (view != null)
                schedulePatch(view, 0);
            startFirstRootWatch(0);
        });
    }

    private static void onSearchFinished()
    {
        searchQueryRunning = false;
        log("onSearchFinished: continue first root watch"); //$NON-NLS-1$
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> startFirstRootWatch(0));
    }

    /**
     * Пока идёт поиск — как только в дереве появляется первая строка, выбираем её
     * и периодически обновляем агрегированную таблицу (прирост результатов в реальном времени).
     */
    private static void startFirstRootWatch(int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (!searchQueryRunning && !guardFirstRootSelection)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (!searchQueryRunning && !guardFirstRootSelection)
                return;

            SearchViewViewers viewers = resolveViewers(findSearchViewPart());
            if (viewers == null)
            {
                if (searchQueryRunning || attempt < 300)
                    startFirstRootWatch(attempt + 1);
                return;
            }

            Object firstRoot = getFirstRootTreeElement(viewers.tree);
            if (firstRoot == null)
            {
                if (searchQueryRunning || attempt < 300)
                    startFirstRootWatch(attempt + 1);
                return;
            }

            Object current = viewers.tree.getStructuredSelection().getFirstElement();
            if (!firstRoot.equals(current))
            {
                log("watchFirstRoot: " + current + " -> " + firstRoot); //$NON-NLS-1$ //$NON-NLS-2$
                viewers.tree.setSelection(new StructuredSelection(firstRoot), true);
                showFirstTreeItem(viewers.tree);
            }
            applyAggregationIfNeeded(viewers.tree, viewers.table,
                Collections.singletonList(firstRoot), Collections.emptyList(), "watchLoop"); //$NON-NLS-1$

            if (searchQueryRunning)
            {
                startFirstRootWatch(attempt + 1);
                return;
            }
            if (!guardFirstRootSelection)
                return;
            if (firstRoot.equals(viewers.tree.getStructuredSelection().getFirstElement()))
                guardFirstRootSelection = false;
            else if (attempt < 60)
                startFirstRootWatch(attempt + 1);
            else
                guardFirstRootSelection = false;
        });
    }

    private static SearchViewViewers resolveViewers(IViewPart view)
    {
        if (!(view instanceof ISearchResultViewPart))
            return null;
        ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
        if (activePage == null || !activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
            return null;
        Object treeLayout = Global.getField(activePage, "treeLayout"); //$NON-NLS-1$
        if (treeLayout == null)
            return null;
        Object viewerObj = Global.invoke(treeLayout, "getViewer"); //$NON-NLS-1$
        Object tableViewerObj = Global.getField(treeLayout, "tableViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TreeViewer) || !(tableViewerObj instanceof TableViewer))
            return null;
        TreeViewer treeViewer = (TreeViewer) viewerObj;
        if (treeViewer.getTree() == null || treeViewer.getTree().isDisposed())
            return null;
        return new SearchViewViewers(treeViewer, (TableViewer) tableViewerObj);
    }

    private static void showFirstTreeItem(TreeViewer treeViewer)
    {
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getItemCount() <= 0)
            return;
        TreeItem firstItem = tree.getItem(0);
        if (firstItem != null && !firstItem.isDisposed())
            tree.showItem(firstItem);
    }

    private static IViewPart findSearchViewPart()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null) return null;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            IWorkbenchPage page = window.getActivePage();
            if (page == null) continue;
            IViewReference ref = page.findViewReference(SEARCH_VIEW_ID);
            if (ref != null)
            {
                IViewPart view = ref.getView(false);
                if (view != null)
                    return view;
            }
        }
        return null;
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null) return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null) continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isSearchView(view))
                    schedulePatch((IViewPart) view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)    { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)   { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isSearchView(part))
                    schedulePatch((IViewPart) part, 0);
            }
        });
    }

    private static boolean isSearchView(Object part)
    {
        if (!(part instanceof IViewPart)) return false;
        IViewPart vp = (IViewPart) part;
        return vp.getViewSite() != null && SEARCH_VIEW_ID.equals(vp.getViewSite().getId());
    }

    // -----------------------------------------------------------------------
    // Патч с повторными попытками (активная страница появляется не сразу
    // и может смениться, если пользователь переключил вид поиска)
    // -----------------------------------------------------------------------

    private static void schedulePatch(IViewPart view, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 150;
        display.timerExec(delay, () -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return; // настройку могли выключить, пока ждали появления страницы
            if (!tryPatch(view) && attempt < 40)
                schedulePatch(view, attempt + 1);
        });
    }

    private static Object getFirstRootTreeElement(TreeViewer treeViewer)
    {
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getItemCount() <= 0)
            return null;
        TreeItem first = tree.getItem(0);
        if (first == null || first.isDisposed())
            return null;
        return first.getData();
    }

    private static boolean tryPatch(IViewPart view)
    {
        try
        {
            if (!(view instanceof ISearchResultViewPart))
                return false;
            ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
            if (activePage == null)
            {
                log("tryPatch: activePage=null"); //$NON-NLS-1$
                return false;
            }
            if (!activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
            {
                // другой вид страницы результатов (не конфигурационный поиск) — не наш случай
                return true;
            }

            Object treeLayout = Global.getField(activePage, "treeLayout"); //$NON-NLS-1$
            if (treeLayout == null)
            {
                log("tryPatch: treeLayout=null (страница ещё не создала createControl)"); //$NON-NLS-1$
                return false;
            }

            Object viewerObj = Global.invoke(treeLayout, "getViewer"); //$NON-NLS-1$
            if (!(viewerObj instanceof TreeViewer))
            {
                log("tryPatch: getViewer() не вернул TreeViewer: " + viewerObj); //$NON-NLS-1$
                return false;
            }
            TreeViewer treeViewer = (TreeViewer) viewerObj;
            if (treeViewer.getTree() == null || treeViewer.getTree().isDisposed())
                return false;

            installTreeMatchCountLabelProvider(treeViewer);

            if (treeViewer.getTree().getData(HOOKED_KEY) != null)
                return true; // уже установлен для этого дерева

            Object tableViewerObj = Global.getField(treeLayout, "tableViewer"); //$NON-NLS-1$
            if (!(tableViewerObj instanceof TableViewer))
            {
                log("tryPatch: tableViewer=null/не TableViewer"); //$NON-NLS-1$
                return false;
            }
            TableViewer tableViewer = (TableViewer) tableViewerObj;

            installAggregationListener(treeViewer, tableViewer);
            installPathColumn(tableViewer);
            installTableCopyHandler(treeViewer, tableViewer);

            treeViewer.getTree().setData(HOOKED_KEY, Boolean.TRUE);
            log("tryPatch: PATCH OK для " + activePage.getClass().getName()); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            log("tryPatch EXCEPTION: " + e); //$NON-NLS-1$
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Агрегация вхождений потомков
    // -----------------------------------------------------------------------

    /** Карта «вхождение (MatchTreeTableItem) → путь», используется {@link #installPathColumn}. */
    private static final Map<Object, Map<Object, String>> PATH_MAPS_BY_TABLE_VIEWER =
            new IdentityHashMap<>();

    /** Колонка «Путь» каждого {@code TableViewer} — для показа/скрытия по состоянию агрегации. */
    private static final Map<TableViewer, TableColumn> PATH_COLUMN_BY_TABLE_VIEWER =
            new IdentityHashMap<>();

    /** {@code DeferredContentProvider} таблицы — для смены порядка сортировки. */
    private static final Map<TableViewer, Object> DEFERRED_PROVIDER_BY_TABLE_VIEWER =
            new IdentityHashMap<>();

    /** Ключ строки таблицы для восстановления выделения: путь узла + колонка «Свойство». */
    private static final class TableRowKey
    {
        final String path;
        final String property;

        TableRowKey(String path, String property)
        {
            this.path = path != null ? path : ""; //$NON-NLS-1$
            this.property = property != null ? property : ""; //$NON-NLS-1$
        }

        boolean matches(TableRowKey other)
        {
            if (other == null || !property.equals(other.property))
                return false;
            if (path.equals(other.path))
                return true;
            return pathsEqual(path, other.path);
        }

        @Override
        public String toString()
        {
            return "{" + path + " | " + property + "}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    /** Выделение таблицы по ключам path+property. */
    private static final Map<TableViewer, List<TableRowKey>> SAVED_TABLE_SELECTION_BY_VIEWER =
            new IdentityHashMap<>();

    private static final String RESTORING_SELECTION_KEY = "tormozit.searchAggregationRestoring"; //$NON-NLS-1$
    private static final String COPY_HOOKED_KEY = "tormozit.searchAggregationCopyHooked"; //$NON-NLS-1$

    private static final int PATH_COLUMN_WIDTH = 130;

    private static void installAggregationListener(TreeViewer treeViewer, TableViewer tableViewer)
    {
        // Основной кэш — при клике по строке таблицы (без смены узла дерева).
        tableViewer.addSelectionChangedListener(event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (Boolean.TRUE.equals(tableViewer.getData(RESTORING_SELECTION_KEY)))
                return;
            saveTableSelection(treeViewer, tableViewer, treeViewer.getStructuredSelection().toList(), "table"); //$NON-NLS-1$
        });

        treeViewer.addPostSelectionChangedListener(event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return; // выключено в настройках — не вмешиваемся в штатное поведение
            try
            {
                IStructuredSelection selection = event.getStructuredSelection();
                List<Object> selectedNodes = selection.toList();

                // EDT при появлении результатов спускается к первому терминальному узлу.
                if (guardFirstRootSelection)
                {
                    Object firstRoot = getFirstRootTreeElement(treeViewer);
                    if (firstRoot == null)
                        return;
                    Object current = selectedNodes.isEmpty() ? null : selectedNodes.get(0);
                    if (!firstRoot.equals(current))
                    {
                        log("redirectToFirstRoot: " + current + " -> " + firstRoot); //$NON-NLS-1$ //$NON-NLS-2$
                        treeViewer.setSelection(new StructuredSelection(firstRoot), true);
                        showFirstTreeItem(treeViewer);
                        return;
                    }
                }

                if (applyAggregationIfNeeded(treeViewer, tableViewer, selectedNodes,
                        copySavedSelection(tableViewer), "postListener")) //$NON-NLS-1$
                    return;

                applyTableSortOrder(tableViewer, false);
                hidePathColumn(tableViewer);
                List<TableRowKey> previousSelection = copySavedSelection(tableViewer);
                if (!previousSelection.isEmpty())
                    scheduleRestoreTableSelection(treeViewer, tableViewer, selectedNodes, previousSelection, true);
            }
            catch (Exception e)
            {
                log("aggregation listener EXCEPTION: " + e); //$NON-NLS-1$
            }
        });
    }

    /**
     * @return {@code true}, если выбран групповой узел и выполнена агрегация
     */
    private static boolean applyAggregationIfNeeded(TreeViewer treeViewer, TableViewer tableViewer,
            List<Object> selectedNodes, List<TableRowKey> previousSelection, String source)
    {
        boolean needsAggregation = false;
        for (Object o : selectedNodes)
        {
            if (hasChildren(o))
            {
                needsAggregation = true;
                break;
            }
        }
        if (!needsAggregation)
            return false;

        Set<Object> nodesForChangeSource = new LinkedHashSet<>();
        Map<Object, String> pathByItem = new IdentityHashMap<>();
        for (Object node : selectedNodes)
            collectRecursively(node, nodesForChangeSource, pathByItem);

        PATH_MAPS_BY_TABLE_VIEWER.put(tableViewer, pathByItem);
        applyTableSortOrder(tableViewer, true);

        Object model = tableViewer.getInput();
        if (model == null)
        {
            log("aggregation: tableViewer.getInput()==null"); //$NON-NLS-1$
            return true;
        }

        log("aggregation: cachedSelection=" + describeRowKeys(previousSelection) //$NON-NLS-1$
            + " liveBeforeChangeSource=" + describeTableSelectionState(tableViewer)); //$NON-NLS-1$

        Object[] items = nodesForChangeSource.toArray();
        Global.invoke(model, "changeSource", (Object) items); //$NON-NLS-1$
        showPathColumn(tableViewer);
        scheduleRestoreTableSelection(treeViewer, tableViewer, selectedNodes, previousSelection, false);

        log("aggregation: nodes=" + items.length + " paths=" + pathByItem.size() //$NON-NLS-1$ //$NON-NLS-2$
            + " for " + selectedNodes.size() + " selected node(s)" //$NON-NLS-1$ //$NON-NLS-2$
            + " restoreKeys=" + previousSelection.size()); //$NON-NLS-1$
        return true;
    }

    private static List<TableRowKey> copySavedSelection(TableViewer tableViewer)
    {
        List<TableRowKey> saved = SAVED_TABLE_SELECTION_BY_VIEWER.get(tableViewer);
        if (saved == null || saved.isEmpty())
            return Collections.emptyList();
        return new ArrayList<>(saved);
    }

    private static void saveTableSelection(TreeViewer treeViewer, TableViewer tableViewer,
            List<Object> contextNodes, String source)
    {
        List<Object> elements = captureTableSelection(tableViewer);
        if (elements.isEmpty())
        {
            log("saveSelection(" + source + "): пусто " + describeTableSelectionState(tableViewer)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        List<Object> treeContext = treeViewer.getStructuredSelection().toList();
        if (isTerminalTreeSelection(treeContext)
            && !isTableReadyForRestore(tableViewer, treeContext, true))
        {
            log("saveSelection(" + source + "): таблица ещё не соответствует узлу дерева " //$NON-NLS-1$ //$NON-NLS-2$
                + describeTableSelectionState(tableViewer));
            return;
        }

        List<TableRowKey> keys = new ArrayList<>();
        for (Object element : elements)
        {
            TableRowKey key = rowKeyForElement(treeViewer, tableViewer, element);
            if (key != null && !key.property.isEmpty() && !key.path.isEmpty())
                keys.add(key);
        }
        if (keys.isEmpty())
        {
            log("saveSelection(" + source + "): ключи пусты " + describeTableSelectionState(tableViewer)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        SAVED_TABLE_SELECTION_BY_VIEWER.put(tableViewer, keys);
        log("saveSelection(" + source + "): " + describeRowKeys(keys) //$NON-NLS-1$ //$NON-NLS-2$
            + " " + describeTableSelectionState(tableViewer)); //$NON-NLS-1$
    }

    private static TableRowKey rowKeyForElement(TreeViewer treeViewer, TableViewer tableViewer, Object element)
    {
        if (element == null)
            return null;
        return new TableRowKey(
            pathForTableItem(treeViewer, tableViewer, element),
            extractPropertyText(element));
    }

    private static String pathForTableItem(TreeViewer treeViewer, TableViewer tableViewer, Object tableItem)
    {
        Map<Object, String> pathMap = PATH_MAPS_BY_TABLE_VIEWER.get(tableViewer);
        if (pathMap != null)
        {
            String cached = pathMap.get(tableItem);
            if (cached != null)
                return cached;
        }
        Object owner = findOwningTreeNode(treeViewer, tableItem);
        if (owner != null)
            return formatPathForTableItem(tableItem, owner);
        return ""; //$NON-NLS-1$
    }

    private static boolean pathsEqual(String a, String b)
    {
        if (a == null || b == null)
            return false;
        if (a.equals(b))
            return true;
        String na = canonicalizeMdPath(a);
        String nb = canonicalizeMdPath(b);
        if (!na.isEmpty() && na.equals(nb))
            return true;
        return pathsCompatible(na, nb);
    }

    /** Модульный путь длиннее пути узла формы или наоборот — одна и та же ветка. */
    private static boolean pathsCompatible(String a, String b)
    {
        if (a == null || b == null || a.isEmpty() || b.isEmpty())
            return false;
        return a.startsWith(b + ".") || b.startsWith(a + "."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String nodePathForSelection(List<Object> selectedNodes)
    {
        if (selectedNodes == null || selectedNodes.size() != 1)
            return ""; //$NON-NLS-1$
        return buildCanonicalPathFromNode(selectedNodes.get(0));
    }

    /** Таблица показывает строки текущего узла, а не предыдущий состав (DeferredContentProvider догрузил). */
    private static boolean isTableReadyForRestore(TableViewer tableViewer, List<Object> contextNodes,
            boolean terminal)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed() || contextNodes == null || contextNodes.isEmpty())
            return true;

        int tableCount = table.getItemCount();
        if (tableCount == 0)
            return false;

        if (terminal)
            return isTerminalTableContentReady(table, contextNodes.get(0), tableCount);

        Map<Object, String> pathMap = PATH_MAPS_BY_TABLE_VIEWER.get(tableViewer);
        if (pathMap == null || pathMap.isEmpty())
            return tableCount == 0;

        int belong = 0;
        int foreign = 0;
        for (TableItem item : table.getItems())
        {
            Object data = item.getData();
            if (data == null)
                continue;
            if (pathMap.containsKey(data))
                belong++;
            else
                foreign++;
        }
        if (foreign > 0)
            return false;
        return belong > 0;
    }

    private static boolean isTerminalTableContentReady(Table table, Object terminalNode, int tableCount)
    {
        Object items = Global.invoke(terminalNode, "getTableItems"); //$NON-NLS-1$
        if (!(items instanceof List<?> expectedItems))
            return false;
        if (expectedItems.isEmpty())
            return tableCount == 0;

        int belong = 0;
        int foreign = 0;
        for (TableItem item : table.getItems())
        {
            Object data = item.getData();
            if (data == null)
                continue;
            if (expectedItems.contains(data))
                belong++;
            else
                foreign++;
        }
        if (foreign > 0)
            return false;
        return belong > 0;
    }

    private static boolean rowBelongsToCurrentContext(TableViewer tableViewer, List<Object> contextNodes,
            Object row, boolean terminal)
    {
        if (row == null || contextNodes == null || contextNodes.isEmpty())
            return false;
        if (terminal)
        {
            if (contextNodes.size() != 1)
                return false;
            Object items = Global.invoke(contextNodes.get(0), "getTableItems"); //$NON-NLS-1$
            return items instanceof List<?> list && list.contains(row);
        }
        Map<Object, String> pathMap = PATH_MAPS_BY_TABLE_VIEWER.get(tableViewer);
        return pathMap != null && pathMap.containsKey(row);
    }

    /** Не вызывать синхронно сразу после {@code changeSource} — таблица ещё со старыми строками. */
    private static void scheduleRestoreTableSelection(TreeViewer treeViewer, TableViewer tableViewer,
            List<Object> contextNodes, List<TableRowKey> previousKeys, boolean terminal)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        table.getDisplay().asyncExec(
            () -> restoreTableSelection(treeViewer, tableViewer, contextNodes, previousKeys, terminal, 0));
    }

    private static int expectedTableItemCount(List<Object> selectedNodes)
    {
        int expected = 0;
        for (Object node : selectedNodes)
        {
            Object items = Global.invoke(node, "getTableItems"); //$NON-NLS-1$
            if (items instanceof List<?> list)
                expected += list.size();
        }
        return expected;
    }

    private static boolean isTerminalTreeSelection(List<Object> selectedNodes)
    {
        return selectedNodes != null && selectedNodes.size() == 1 && !hasChildren(selectedNodes.get(0));
    }

    private static String normalizePath(String path)
    {
        if (path == null || path.isEmpty())
            return ""; //$NON-NLS-1$
        return path.replaceAll("\\s*\\(\\d+\\s+соответств[^)]*\\)", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String buildCanonicalPathFromNode(Object node)
    {
        return buildCanonicalMdPathFromNode(node);
    }

    /**
     * MD-путь из цепочки узлов дерева поиска: типы в ед.ч., пары «Тип.Имя».
     */
    private static String buildCanonicalMdPathFromNode(Object node)
    {
        List<String> labels = new ArrayList<>();
        for (Object cur = node; cur != null; cur = Global.invoke(cur, "getParent")) //$NON-NLS-1$
        {
            String label = stripColonFragment(normalizePath(extractLabel(cur)));
            if (!label.isEmpty())
                labels.add(0, label);
        }
        if (labels.isEmpty())
            return ""; //$NON-NLS-1$
        if (labels.size() > 1 && !labels.get(0).contains(".") && !isMdTypeSegment(labels.get(0))) //$NON-NLS-1$
            labels.remove(0);

        String deepest = labels.get(labels.size() - 1);
        if (deepest.contains(".")) //$NON-NLS-1$
            return canonicalizeMdPath(trimPathDisplaySuffix(deepest));

        StringBuilder path = new StringBuilder();
        for (int i = 0; i < labels.size(); )
        {
            String segment = labels.get(i);
            String typeRu = toSingularRuType(segment);
            if (typeRu != null && i + 1 < labels.size())
            {
                if (path.length() > 0)
                    path.append('.');
                path.append(typeRu).append('.').append(labels.get(i + 1));
                i += 2;
            }
            else if (segment.contains(".")) //$NON-NLS-1$
            {
                return canonicalizeMdPath(trimPathDisplaySuffix(segment));
            }
            else
            {
                i++;
            }
        }
        return canonicalizeMdPath(trimPathDisplaySuffix(path.toString()));
    }

    private static boolean isMdTypeSegment(String segment)
    {
        return toSingularRuType(segment) != null;
    }

    private static String toSingularRuType(String segment)
    {
        if (segment == null || segment.isEmpty())
            return null;
        String ru = MdTypeMapping.anyToRu(segment);
        if (ru != null)
            return ru;
        ru = MdTypeMapping.ruPluralToRu(segment);
        if (ru != null)
            return ru;
        return MdTypeMapping.treeGroupLabelToRu(segment);
    }

    /** Единый MD-путь для колонки «Путь» и сравнения ключей. */
    private static String canonicalizeMdPath(String path)
    {
        if (path == null || path.isEmpty())
            return ""; //$NON-NLS-1$
        path = stripColonFragment(trimPathDisplaySuffix(normalizePath(path)));
        if (path.isEmpty())
            return ""; //$NON-NLS-1$

        String[] parts = path.split("\\.", -1); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (String part : parts)
        {
            if (part.isEmpty())
                continue;
            String typeRu = toSingularRuType(part);
            if (typeRu != null)
                part = typeRu;
            if (sb.length() > 0)
                sb.append('.');
            sb.append(part);
        }
        return ensureFormContextSuffix(sb.toString());
    }

    /** Контекст формы: {@code …Форма.ИмяФормы.Форма}. */
    private static String ensureFormContextSuffix(String path)
    {
        if (path == null || path.isEmpty())
            return ""; //$NON-NLS-1$
        if (path.endsWith(".Форма")) //$NON-NLS-1$
            return path;
        int lastDot = path.lastIndexOf('.');
        if (lastDot >= 0)
        {
            String last = path.substring(lastDot + 1);
            if (MdTypeMapping.isModuleTypeSuffix(last))
                return path;
        }
        if (path.contains(".Форма.")) //$NON-NLS-1$
            return path + ".Форма"; //$NON-NLS-1$
        return path;
    }

    /** Путь для колонки «Путь» — короткий MD-путь без дублирования колонки «Свойство». */
    private static String formatPathForTableItem(Object tableItem, Object ownerNode)
    {
        String source;
        String path = bmTopObjectPathFromTableItem(tableItem);
        if (path != null && !path.isEmpty())
            source = "bmObject"; //$NON-NLS-1$
        else
        {
            path = modulePathFromTableItem(tableItem);
            if (path != null && !path.isEmpty())
                source = "module"; //$NON-NLS-1$
            else
            {
                path = mdPathFromTableItemFile(tableItem);
                if (path != null && !path.isEmpty())
                    source = "mdFile"; //$NON-NLS-1$
                else
                {
                    path = buildCanonicalMdPathFromNode(ownerNode);
                    source = "ownerNode"; //$NON-NLS-1$
                    logObjectApiOnce("ownerNode", ownerNode); //$NON-NLS-1$
                }
            }
        }
        String result = canonicalizeMdPath(path);
        if (result == null || result.isEmpty())
        {
            log("formatPathForTableItem: EMPTY source=" + source //$NON-NLS-1$
                + " ownerLabel=" + extractLabel(ownerNode)); //$NON-NLS-1$
        }
        return result;
    }

    /** Убирает суффикс {@code :...} — он показывается в колонке «Свойство». */
    private static String stripColonFragment(String value)
    {
        if (value == null || value.isEmpty())
            return ""; //$NON-NLS-1$
        int colon = value.indexOf(':');
        if (colon > 0)
            return value.substring(0, colon).trim();
        return value.trim();
    }

    private static String trimPathDisplaySuffix(String value)
    {
        if (value == null || value.isEmpty())
            return value != null ? value : ""; //$NON-NLS-1$
        while (value.endsWith(".") || value.endsWith(":")) //$NON-NLS-1$ //$NON-NLS-2$
            value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String modulePathFromTableItem(Object tableItem)
    {
        String path = projectRelativePathFromTableItem(tableItem);
        if (path == null || path.isEmpty())
            return null;
        GetRef.ModuleRef ref = GetRef.pathToModuleRef(path);
        return ref != null ? ref.modulePath : null;
    }

    /**
     * Путь через верхний BM-объект совпадения — тот же механизм, что и штатный двойной клик по строке
     * поиска для открытия редактора объекта. Надёжнее и проще, чем парсинг подписей дерева:
     * работает для любого типа {@code Match} (не только {@code TextSearchModelMatch}) и даёт
     * точное полное имя верхнего MD-объекта без каких-либо таблиц соответствия.
     *
     * <p>{@code com._1c.g5.v8.dt.search.core.Match} (базовый класс всех совпадений поиска) хранит
     * {@code long getMetadataTopObjectId()} (публичный) и {@code protected Optional<IBmObject> resolveObjectById(long)}.
     * {@code IBmObject extends EObject} — полученный объект можно напрямую отдать в уже существующий
     * {@link GetRef#eObjectToFullName(EObject)} (тот же код, что используется для навигатора/редакторов).
     * Оба метода найдены декомпиляцией {@code com._1c.g5.v8.dt.search.core_*.jar} и {@code com._1c.g5.v8.bm.core_*.jar}
     * из {@code C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.pde.core\.bundle_pool\plugins}.
     *
     * <p>Может вернуть {@code null}, если BM-модель уже диспозирована/транзакция неактивна —
     * тогда {@link #formatPathForTableItem} падает в файловые/{@code ownerNode}-стратегии.
     */
    private static String bmTopObjectPathFromTableItem(Object tableItem)
    {
        try
        {
            Object match = Global.invoke(tableItem, "getData"); //$NON-NLS-1$
            if (match == null)
                return null;
            Object topIdObj = Global.invoke(match, "getMetadataTopObjectId"); //$NON-NLS-1$
            if (!(topIdObj instanceof Long topId) || topId < 0) // IBmObject.BM_NULL_ID == -1
                return null;
            Object optObj = Global.invoke(match, "resolveObjectById", topId); //$NON-NLS-1$
            if (!(optObj instanceof java.util.Optional<?> opt) || opt.isEmpty())
                return null;
            Object bmObject = opt.get();
            if (!(bmObject instanceof EObject))
                return null;
            return GetRef.eObjectToFullName((EObject) bmObject);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static final Set<String> LOGGED_API_CLASSES = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Разовая диагностика (по разу на класс): вызывает все публичные нуль-арг методы объекта
     * (getX()/isX()) и логирует имя + тип/значение результата. Оставлено на случай новых
     * непокрытых типов совпадений в будущем; основной путь теперь —
     * {@link #bmTopObjectPathFromTableItem}.
     */
    private static void logObjectApiOnce(String label, Object obj)
    {
        if (obj == null)
            return;
        Class<?> cls = obj.getClass();
        String key = label + ":" + cls.getName(); //$NON-NLS-1$
        if (!LOGGED_API_CLASSES.add(key))
            return;
        StringBuilder sb = new StringBuilder();
        sb.append("API ").append(label).append(" class=").append(cls.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
        {
            for (java.lang.reflect.Method m : c.getDeclaredMethods())
            {
                if (m.getParameterCount() != 0)
                    continue;
                String name = m.getName();
                if (!(name.startsWith("get") || name.startsWith("is"))) //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                if (name.equals("getClass")) //$NON-NLS-1$
                    continue;
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                    continue;
                sb.append("\n  ").append(name).append("(): ").append(m.getReturnType().getName()); //$NON-NLS-1$ //$NON-NLS-2$
                try
                {
                    m.setAccessible(true);
                    Object value = m.invoke(obj);
                    if (value == null)
                        sb.append(" = null"); //$NON-NLS-1$
                    else if (value instanceof String || value instanceof Number || value instanceof Boolean)
                        sb.append(" = ").append(value); //$NON-NLS-1$
                    else
                        sb.append(" -> ").append(value.getClass().getName()) //$NON-NLS-1$
                          .append(" toString=").append(String.valueOf(value)); //$NON-NLS-1$
                }
                catch (Exception e)
                {
                    sb.append(" [ошибка вызова: ").append(e).append(']'); //$NON-NLS-1$
                }
            }
        }
        log(sb.toString());
    }

    private static String projectRelativePathFromTableItem(Object tableItem)
    {
        try
        {
            Object match = Global.invoke(tableItem, "getData"); //$NON-NLS-1$
            if (match == null)
                return null;
            Object file = Global.invoke(match, "getFile"); //$NON-NLS-1$
            if (file == null)
                return null; // матчи внутри бизнес-модели (реквизиты/команды формы и т.п.) без файла — ожидаемо,
                            // путь для них строится через bmTopObjectPathFromTableItem/ownerNode (см. formatPathForTableItem)
            Object relPath = Global.invoke(file, "getProjectRelativePath"); //$NON-NLS-1$
            if (relPath == null)
                return null;
            Object pathStr = Global.invoke(relPath, "toString"); //$NON-NLS-1$
            return pathStr instanceof String ? (String) pathStr : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /** Путь из {@code Match.getFile()} — модуль или полное имя МД (форма, макет…). */
    private static String mdPathFromTableItemFile(Object tableItem)
    {
        try
        {
            String path = projectRelativePathFromTableItem(tableItem);
            if (path == null || path.isEmpty())
                return null;
            GetRef.ModuleRef ref = GetRef.pathToModuleRef(path);
            if (ref != null && ref.modulePath != null && !ref.modulePath.isEmpty())
                return ref.modulePath;
            return GetRef.pathToFullName(path);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static String extractPropertyText(Object tableItem)
    {
        try
        {
            Object styled = Global.invoke(tableItem, "getPropertyText"); //$NON-NLS-1$
            if (styled instanceof StyledString)
                return ((StyledString) styled).getString();
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static Object findOwningTreeNode(TreeViewer treeViewer, Object tableItem)
    {
        Object input = treeViewer.getInput();
        if (input == null || tableItem == null)
            return null;
        if (input instanceof Object[])
        {
            for (Object root : (Object[]) input)
            {
                Object found = findOwningTreeNodeRec(root, tableItem);
                if (found != null)
                    return found;
            }
            return null;
        }
        return findOwningTreeNodeRec(input, tableItem);
    }

    @SuppressWarnings("unchecked")
    private static Object findOwningTreeNodeRec(Object node, Object tableItem)
    {
        if (node == null)
            return null;

        Object ownItems = Global.invoke(node, "getTableItems"); //$NON-NLS-1$
        if (ownItems instanceof List<?>)
        {
            for (Object item : (List<Object>) ownItems)
            {
                if (item == tableItem || (item != null && item.equals(tableItem)))
                    return node;
            }
        }

        Object children = Global.invoke(node, "getChildren"); //$NON-NLS-1$
        if (children instanceof List<?>)
        {
            for (Object child : (List<Object>) children)
            {
                Object found = findOwningTreeNodeRec(child, tableItem);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /** Читает выделение из TableViewer; если пусто — из SWT {@link Table#getSelection()}. */
    private static List<Object> captureTableSelection(TableViewer tableViewer)
    {
        List<Object> fromViewer = tableViewer.getStructuredSelection().toList();
        if (!fromViewer.isEmpty())
            return new ArrayList<>(fromViewer);
        return captureSwtTableSelection(tableViewer);
    }

    private static List<Object> captureSwtTableSelection(TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return Collections.emptyList();

        List<Object> fromSwt = new ArrayList<>();
        for (TableItem item : table.getSelection())
        {
            Object data = item.getData();
            if (data != null)
                fromSwt.add(data);
        }
        return fromSwt;
    }

    /**
     * Нетерминальный узел: ищем строку по полному ключу path+property.
     * Терминальный узел: сначала path сохранённого ключа = path узла, затем ищем property в таблице.
     */
    private static List<Object> findRowsToRestore(TreeViewer treeViewer, TableViewer tableViewer,
            List<Object> selectedNodes, List<TableRowKey> wanted, boolean terminal)
    {
        if (wanted == null || wanted.isEmpty())
            return Collections.emptyList();

        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return Collections.emptyList();

        if (terminal)
            return findRowsForTerminal(table, selectedNodes, wanted);

        List<Object> found = new ArrayList<>();
        for (TableRowKey key : wanted)
        {
            for (TableItem item : table.getItems())
            {
                Object data = item.getData();
                if (data == null)
                    continue;
                TableRowKey candidate = rowKeyForElement(treeViewer, tableViewer, data);
                if (key.matches(candidate) && rowBelongsToCurrentContext(tableViewer, selectedNodes, data, false))
                {
                    found.add(data);
                    break;
                }
            }
        }
        return found;
    }

    private static List<Object> findRowsForTerminal(Table table, List<Object> selectedNodes, List<TableRowKey> wanted)
    {
        if (selectedNodes.size() != 1)
            return Collections.emptyList();

        List<Object> found = new ArrayList<>();
        for (TableRowKey key : wanted)
        {
            for (TableItem item : table.getItems())
            {
                Object data = item.getData();
                if (data == null)
                    continue;
                if (key.property.equals(extractPropertyText(data))
                    && rowBelongsToCurrentContext(null, selectedNodes, data, true))
                {
                    found.add(data);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Рекурсивно обходит дерево от {@code node} вниз, собирая в {@code nodesOut} все узлы
     * (листовые и групповые с непустым {@code getTableItems()}), которые нужно передать
     * в {@code changeSource(Object[])} — он сам для каждого такого узла вызовет {@code getTableItems()}.
     * Одновременно самостоятельно читаем {@code getTableItems()} тех же узлов для построения
     * карты {@code pathByItem} (путь — конкатенация подписей узлов от корня выбора до владельца).
     */
    @SuppressWarnings("unchecked")
    private static void collectRecursively(Object node,
            Set<Object> nodesOut, Map<Object, String> pathByItem)
    {
        Object ownItems = Global.invoke(node, "getTableItems"); //$NON-NLS-1$
        if (ownItems instanceof List<?> && !((List<?>) ownItems).isEmpty())
        {
            nodesOut.add(node); // changeSource сам вызовет node.getTableItems()
            for (Object tableItem : (List<Object>) ownItems)
                pathByItem.put(tableItem, formatPathForTableItem(tableItem, node));
        }

        Object children = Global.invoke(node, "getChildren"); //$NON-NLS-1$
        if (children instanceof List<?>)
        {
            for (Object child : (List<Object>) children)
                collectRecursively(child, nodesOut, pathByItem);
        }
    }

    private static String extractLabel(Object matchTreeItem)
    {
        try
        {
            Object styled = Global.invoke(matchTreeItem, "getStyledText"); //$NON-NLS-1$
            if (styled instanceof StyledString)
                return ((StyledString) styled).getString();
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Колонка «Путь»
    // -----------------------------------------------------------------------

    private static void installPathColumn(TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        int[] orderBefore = table.getColumnOrder(); // типично [0,1,2] = иконка/Свойство/Текст

        TableViewerColumn column = new TableViewerColumn(tableViewer, SWT.LEFT);
        TableColumn swtColumn = column.getColumn();
        swtColumn.setText("Путь"); //$NON-NLS-1$
        swtColumn.setResizable(true);
        swtColumn.setMoveable(false);
        swtColumn.setWidth(0); // по умолчанию скрыта — показывается только при агрегации

        column.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                Map<Object, String> pathByItem = PATH_MAPS_BY_TABLE_VIEWER.get(tableViewer);
                String path = pathByItem != null ? pathByItem.get(cell.getElement()) : null;
                cell.setText(path != null ? path : ""); //$NON-NLS-1$
            }
        });

        table.addDisposeListener(e -> {
            PATH_MAPS_BY_TABLE_VIEWER.remove(tableViewer);
            PATH_COLUMN_BY_TABLE_VIEWER.remove(tableViewer);
            DEFERRED_PROVIDER_BY_TABLE_VIEWER.remove(tableViewer);
            SAVED_TABLE_SELECTION_BY_VIEWER.remove(tableViewer);
        });

        PATH_COLUMN_BY_TABLE_VIEWER.put(tableViewer, swtColumn);

        // Новая колонка физически добавляется последней — переставляем визуально перед «Свойство»
        // (вторая в штатном порядке после иконки): новый порядок — иконка, Путь, Свойство, Текст.
        int newColumnIndex = table.getColumnCount() - 1; // физический индекс добавленной колонки
        try
        {
            int[] newOrder = new int[orderBefore.length + 1];
            newOrder[0] = orderBefore[0];       // иконка — первой
            newOrder[1] = newColumnIndex;         // «Путь» — второй, перед «Свойством»
            for (int i = 1; i < orderBefore.length; i++)
                newOrder[i + 1] = orderBefore[i];
            table.setColumnOrder(newOrder);
        }
        catch (Exception e)
        {
            log("installPathColumn: не удалось переставить колонку перед «Свойством»: " + e); //$NON-NLS-1$
        }
    }

    private static void installTableCopyHandler(TreeViewer treeViewer, TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed() || table.getData(COPY_HOOKED_KEY) != null)
            return;
        table.setData(COPY_HOOKED_KEY, Boolean.TRUE);
        table.addListener(SWT.KeyDown, event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if ((event.stateMask & (SWT.MOD1 | SWT.CTRL)) == 0)
                return;
            if (event.keyCode != 'c' && event.keyCode != 'C')
                return;
            if (copySelectedTableRowsToClipboard(treeViewer, tableViewer))
                event.doit = false;
        });
    }

    private static boolean copySelectedTableRowsToClipboard(TreeViewer treeViewer, TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return false;
        TableItem[] selected = table.getSelection();
        if (selected == null || selected.length == 0)
            return false;

        StringBuilder clipboard = new StringBuilder();
        int[] columnOrder = table.getColumnOrder();
        for (TableItem item : selected)
        {
            if (item == null || item.isDisposed())
                continue;
            Object element = item.getData();
            if (clipboard.length() > 0)
                clipboard.append('\n');
            boolean firstCol = true;
            for (int col : columnOrder)
            {
                TableColumn column = table.getColumn(col);
                if (column == null || column.isDisposed() || column.getWidth() <= 0)
                    continue;
                if (column.getText() == null || column.getText().isEmpty())
                    continue; // иконка
                if (!firstCol)
                    clipboard.append('\t');
                firstCol = false;
                clipboard.append(cellTextForCopy(treeViewer, tableViewer, element, item, col, table));
            }
        }
        if (clipboard.length() == 0)
            return false;
        Clipboard cb = new Clipboard(table.getDisplay());
        try
        {
            cb.setContents(
                new Object[] { clipboard.toString() },
                new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            cb.dispose();
        }
        return true;
    }

    private static String cellTextForCopy(TreeViewer treeViewer, TableViewer tableViewer, Object element,
            TableItem item, int columnIndex, Table table)
    {
        TableColumn pathColumn = PATH_COLUMN_BY_TABLE_VIEWER.get(tableViewer);
        if (pathColumn != null && table.getColumn(columnIndex) == pathColumn && element != null)
        {
            Map<Object, String> pathByItem = PATH_MAPS_BY_TABLE_VIEWER.get(tableViewer);
            if (pathByItem != null)
            {
                String path = pathByItem.get(element);
                if (path != null)
                    return path;
            }
            Object owner = findOwningTreeNode(treeViewer, element);
            if (owner != null)
                return formatPathForTableItem(element, owner);
        }

        String text = item.getText(columnIndex);
        if (text != null && !text.isEmpty())
            return text;

        if (element == null)
            return ""; //$NON-NLS-1$
        TableColumn column = table.getColumn(columnIndex);
        String header = column != null ? column.getText() : ""; //$NON-NLS-1$
        if ("Свойство".equals(header)) //$NON-NLS-1$
            return extractPropertyText(element);
        return extractMatchText(element);
    }

    private static String extractMatchText(Object tableItem)
    {
        try
        {
            Object styled = Global.invoke(tableItem, "getDecoratedText"); //$NON-NLS-1$
            if (styled == null)
                styled = Global.invoke(tableItem, "getStyledText"); //$NON-NLS-1$
            if (styled instanceof StyledString)
                return ((StyledString) styled).getString();
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    private static void showPathColumn(TableViewer tableViewer)
    {
        TableColumn column = PATH_COLUMN_BY_TABLE_VIEWER.get(tableViewer);
        if (column != null && !column.isDisposed() && column.getWidth() == 0)
            column.setWidth(PATH_COLUMN_WIDTH);
    }

    private static void hidePathColumn(TableViewer tableViewer)
    {
        TableColumn column = PATH_COLUMN_BY_TABLE_VIEWER.get(tableViewer);
        if (column != null && !column.isDisposed() && column.getWidth() != 0)
            column.setWidth(0);
    }

    /**
     * Штатный {@code DeferredContentProvider} сортирует по {@code Comparable} строки
     * ({@code MatchTreeTableItem}: группа свойства, затем «Свойство»). При агрегации
     * нужен порядок «Путь, Свойство».
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyTableSortOrder(TableViewer tableViewer, boolean pathThenProperty)
    {
        Object provider = tableViewer.getContentProvider();
        if (provider == null || !provider.getClass().getName().contains("DeferredContentProvider")) //$NON-NLS-1$
            return;
        DEFERRED_PROVIDER_BY_TABLE_VIEWER.put(tableViewer, provider);
        Comparator order = pathThenProperty
            ? (a, b) -> compareRowsByPathProperty(tableViewer, a, b)
            : Comparator.naturalOrder();
        Global.invoke(provider, "setSortOrder", order); //$NON-NLS-1$
    }

    private static int compareRowsByPathProperty(TableViewer tableViewer, Object a, Object b)
    {
        Map<Object, String> pathByItem = PATH_MAPS_BY_TABLE_VIEWER.get(tableViewer);
        String pathA = pathByItem != null ? pathByItem.get(a) : null;
        String pathB = pathByItem != null ? pathByItem.get(b) : null;
        if (pathA == null)
            pathA = ""; //$NON-NLS-1$
        if (pathB == null)
            pathB = ""; //$NON-NLS-1$
        int cmp = normalizePath(pathA).compareToIgnoreCase(normalizePath(pathB));
        if (cmp != 0)
            return cmp;
        cmp = extractPropertyText(a).compareToIgnoreCase(extractPropertyText(b));
        if (cmp != 0)
            return cmp;
        if (a instanceof Comparable<?> ca && b instanceof Comparable<?> cb)
        {
            try
            {
                return ((Comparable<Object>) ca).compareTo(b);
            }
            catch (ClassCastException ignored)
            {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Повторно пытается выделить те же строки, что были выделены до {@code changeSource(...)} —
     * содержимое таблицы перестраивается асинхронно в фоновом задании модели, поэтому сразу
     * после {@code changeSource} нужных строк в таблице ещё нет — повторяем попытку с задержкой.
     */
    private static void restoreTableSelection(TreeViewer treeViewer, TableViewer tableViewer,
            List<Object> contextNodes, List<TableRowKey> previousKeys, boolean terminal, int attempt)
    {
        if (previousKeys == null || previousKeys.isEmpty())
        {
            if (attempt == 0)
                log("restoreTableSelection: skip — ключи пусты"); //$NON-NLS-1$
            return;
        }
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return;

        if (!isTableReadyForRestore(tableViewer, contextNodes, terminal))
        {
            if (attempt >= 40)
                return;
            table.getDisplay().timerExec(100,
                () -> restoreTableSelection(treeViewer, tableViewer, contextNodes, previousKeys, terminal, attempt + 1));
            return;
        }

        List<Object> matched = findRowsToRestore(treeViewer, tableViewer, contextNodes, previousKeys, terminal);

        if (attempt == 0)
        {
            log("restoreTableSelection: " + (terminal ? "terminal" : "aggregation") //$NON-NLS-1$ //$NON-NLS-2$
                + " keys=" + describeRowKeys(previousKeys) //$NON-NLS-1$
                + " matched=" + matched.size() //$NON-NLS-1$
                + " " + describeTableSelectionState(tableViewer)); //$NON-NLS-1$
        }

        if (!matched.isEmpty())
        {
            tableViewer.setData(RESTORING_SELECTION_KEY, Boolean.TRUE);
            try
            {
                tableViewer.setSelection(new org.eclipse.jface.viewers.StructuredSelection(matched), true);
            }
            finally
            {
                tableViewer.setData(RESTORING_SELECTION_KEY, null);
            }
            if (!tableViewer.getStructuredSelection().isEmpty())
            {
                if (attempt == 0)
                    log("restoreTableSelection: OK matched=" + matched.size()); //$NON-NLS-1$
                return;
            }
        }

        if (attempt >= 20)
        {
            log("restoreTableSelection: FAIL keys=" + describeRowKeys(previousKeys) //$NON-NLS-1$
                + " matched=" + matched.size() //$NON-NLS-1$
                + " " + describeTableSelectionState(tableViewer)); //$NON-NLS-1$
            return;
        }
        table.getDisplay().timerExec(100,
            () -> restoreTableSelection(treeViewer, tableViewer, contextNodes, previousKeys, terminal, attempt + 1));
    }

    private static boolean hasChildren(Object node)
    {
        Object children = Global.invoke(node, "getChildren"); //$NON-NLS-1$
        return children instanceof List<?> && !((List<?>) children).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Счётчик результатов на всех узлах дерева поиска
    // -----------------------------------------------------------------------

    private static void installTreeMatchCountLabelProvider(TreeViewer treeViewer)
    {
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getData(TREE_COUNT_LABEL_HOOKED_KEY) != null)
            return;

        IBaseLabelProvider rawLp = treeViewer.getLabelProvider();
        IStyledLabelProvider innerStyled = null;
        if (rawLp instanceof DelegatingStyledCellLabelProvider)
            innerStyled = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
        else if (rawLp instanceof IStyledLabelProvider)
            innerStyled = (IStyledLabelProvider) rawLp;

        if (innerStyled instanceof MatchTreeCountLabelWrapper)
        {
            tree.setData(TREE_COUNT_LABEL_HOOKED_KEY, Boolean.TRUE);
            return;
        }

        MatchTreeCountLabelWrapper wrapper = new MatchTreeCountLabelWrapper(innerStyled);
        if (innerStyled != null && rawLp instanceof DelegatingStyledCellLabelProvider)
            injectTreeStyledStringProvider((DelegatingStyledCellLabelProvider) rawLp, wrapper);
        else
            treeViewer.setLabelProvider(new DelegatingStyledCellLabelProvider(wrapper));

        tree.setData(TREE_COUNT_LABEL_HOOKED_KEY, Boolean.TRUE);
        log("installTreeMatchCountLabelProvider: OK"); //$NON-NLS-1$
    }

    private static void injectTreeStyledStringProvider(DelegatingStyledCellLabelProvider provider,
            IStyledLabelProvider smartProvider)
    {
        Class<?> cls = provider.getClass();
        while (cls != null)
        {
            for (java.lang.reflect.Field field : cls.getDeclaredFields())
            {
                if (!IStyledLabelProvider.class.isAssignableFrom(field.getType()))
                    continue;
                try
                {
                    field.setAccessible(true);
                    field.set(provider, smartProvider);
                    return;
                }
                catch (Exception ignored)
                {
                }
            }
            cls = cls.getSuperclass();
        }
        log("injectTreeStyledStringProvider: field not found"); //$NON-NLS-1$
    }

    private static boolean isMatchTreeItem(Object element)
    {
        return element != null && element.getClass().getName().contains("MatchTreeItem"); //$NON-NLS-1$
    }

    private static long countMatchItemsRecursively(Object node)
    {
        if (node == null)
            return 0;
        long count = 0;
        Object ownItems = Global.invoke(node, "getTableItems"); //$NON-NLS-1$
        if (ownItems instanceof List<?> list)
            count += list.size();
        Object children = Global.invoke(node, "getChildren"); //$NON-NLS-1$
        if (children instanceof List<?> childList)
        {
            for (Object child : childList)
                count += countMatchItemsRecursively(child);
        }
        return count;
    }

    private static long parseMatchCountFromLabel(String label)
    {
        if (label == null || label.isEmpty())
            return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\(\\s*(\\d+)\\s+\\u0441\\u043E\\u043E\\u0442\\u0432\\u0435\\u0442") //$NON-NLS-1$
            .matcher(label);
        if (!m.find())
            return -1;
        try
        {
            return Long.parseLong(m.group(1));
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }

    private static String extractBaseTreeText(Object node)
    {
        Object text = Global.getField(node, "text"); //$NON-NLS-1$
        if (text instanceof String s && !s.isEmpty())
            return s;
        return normalizePath(extractLabel(node));
    }

    private static StyledString styledTextWithTotalCount(Object node, long totalCount)
    {
        try
        {
            Object styled = Global.invoke(node, "getStyledText", totalCount); //$NON-NLS-1$
            if (styled instanceof StyledString)
                return (StyledString) styled;
        }
        catch (Exception ignored)
        {
        }
        StyledString result = new StyledString(extractBaseTreeText(node));
        if (totalCount > 0)
        {
            result.append(
                MessageFormat.format(MATCH_COUNT_SUFFIX_PATTERN, Long.valueOf(totalCount)),
                StyledString.COUNTER_STYLER);
        }
        return result;
    }

    private static final class MatchTreeCountLabelWrapper extends LabelProvider implements IStyledLabelProvider
    {
        private final IStyledLabelProvider delegate;

        MatchTreeCountLabelWrapper(IStyledLabelProvider delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            if (!isMatchTreeItem(element))
                return delegate != null ? delegate.getStyledText(element) : new StyledString(""); //$NON-NLS-1$

            long totalCount = countMatchItemsRecursively(element);
            if (totalCount <= 0)
            {
                StyledString plain = delegate != null ? delegate.getStyledText(element) : null;
                return plain != null ? plain : new StyledString(extractBaseTreeText(element));
            }

            if (delegate != null)
            {
                StyledString delegateStyled = delegate.getStyledText(element);
                if (delegateStyled != null)
                {
                    long shownCount = parseMatchCountFromLabel(delegateStyled.getString());
                    if (shownCount == totalCount)
                        return delegateStyled;
                }
            }

            StyledString fixed = styledTextWithTotalCount(element, totalCount);
            return fixed;
        }

        @Override
        public org.eclipse.swt.graphics.Image getImage(Object element)
        {
            return delegate != null ? delegate.getImage(element) : null;
        }

        @Override
        public void dispose()
        {
            if (delegate != null)
                delegate.dispose();
            super.dispose();
        }
    }

    private static String describeRowKeys(List<TableRowKey> keys)
    {
        if (keys == null || keys.isEmpty())
            return "[]"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("["); //$NON-NLS-1$
        int limit = Math.min(keys.size(), 3);
        for (int i = 0; i < limit; i++)
        {
            if (i > 0)
                sb.append(", "); //$NON-NLS-1$
            sb.append(keys.get(i));
        }
        if (keys.size() > limit)
            sb.append(", ...+").append(keys.size() - limit); //$NON-NLS-1$
        sb.append("] size=").append(keys.size()); //$NON-NLS-1$
        return sb.toString();
    }

    private static String describeTableSelectionState(TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        int itemCount = table == null || table.isDisposed() ? -1 : table.getItemCount();
        int swtSelCount = table == null || table.isDisposed() ? -1 : table.getSelectionCount();
        int viewerSelCount = tableViewer.getStructuredSelection().size();
        return "{tableItems=" + itemCount + " viewerSel=" + viewerSelCount + " swtSel=" + swtSelCount + "}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    // -----------------------------------------------------------------------
    // Логирование
    // -----------------------------------------------------------------------

    private static void log(String message)
    {
        Global.log("SearchViewAggregation", message); //$NON-NLS-1$
    }

    public SearchViewAggregationHook() {}
}
