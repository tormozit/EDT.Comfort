package tormozit;

import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.IOpenListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.OpenEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultListener;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.search.ui.SearchResultEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
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
 *
 * <p>#165 (только диагностика в журнале «Комфорт»): Open / panelHealth / searchResultChanged /
 * queryRemoved — без изменения поведения агрегации и Open.
 */
public final class SearchViewAggregationHook implements IStartup
{
    private static final String SEARCH_VIEW_ID = "org.eclipse.search.ui.views.SearchView"; //$NON-NLS-1$
    private static final String PAGE_CLASS_MARKER = "ConfigurationSearchViewPage"; //$NON-NLS-1$

    private static final String HOOKED_KEY = "tormozit.searchAggregationHooked"; //$NON-NLS-1$
    private static final String TREE_COUNT_LABEL_HOOKED_KEY = "tormozit.searchTreeCountLabelHooked"; //$NON-NLS-1$
    private static final String OPEN_DIAG_HOOKED_KEY = "tormozit.searchAggregationOpenDiag"; //$NON-NLS-1$
    private static final String WATCHDOG_HOOKED_KEY = "tormozit.searchAggregationWatchdog"; //$NON-NLS-1$
    private static final String RESIZE_DIAG_HOOKED_KEY = "tormozit.searchAggregationResizeDiag"; //$NON-NLS-1$
    /** Период опроса {@link #installPanelWatchdog} — раз в 1с, независимо от кликов/поисков. */
    private static final int WATCHDOG_INTERVAL_MS = 1000;
    /**
     * #165: после Open deferred-{@code CTabFolder.onMouse} может скрыть content «Поиск» при
     * визуально всё ещё выбранной вкладке. Короткое окно: filter мыши на PartStack + немедленный
     * desync-heal. Не откатываем чужие вкладки (клик по «Журнал Комфорт» — штатно).
     */
    /**
     * Clip_851980: race {@code onMouse} пришёл на +5с после open — при 3000ms окно уже
     * истекло, filter не сработал. Держим запас под deferred mouse после открытия редактора.
     */
    private static final int OPEN_PROTECT_MS = 10000;
    /** Как {@code Messages.IMatchItem_Total_matches_count_pattern__0} в search.ui. */
    private static final String MATCH_COUNT_SUFFIX_PATTERN = " ({0} \u0441\u043E\u043E\u0442\u0432\u0435\u0442\u0441\u0442\u0432\u0438\u0439)"; //$NON-NLS-1$

    /** Уже повешен диагностический {@link ISearchResultListener} (weak). */
    private static final Set<ISearchResult> RESULT_LISTENERS_INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Счётчик открытий (двойных кликов) для диагностики #165: несколько кликов подряд (быстрее
     * 1.5с) порождают перекрывающиеся цепочки {@code timerExec} у {@link #schedulePanelHealthDiag},
     * и без этого счётчика их +0/+100/+500/+1500ms логи могли перемешиваться (что и видно было
     * в присланном логе — "+1500ms" от предыдущего клика печатался после "open:" следующего).
     * Каждый {@code open()} захватывает свой {@code seq}; устаревшие (перекрытые новым кликом)
     * срабатывания молча пропускаются.
     */
    private static volatile int openSeq;
    /** {@link System#currentTimeMillis()} до которого действует защита после Open. */
    private static volatile long openProtectDeadlineMs;
    /** Момент {@code open} для дельты в логах heal/block. */
    private static volatile long openArmedAtMs;
    private static volatile WeakReference<org.eclipse.swt.custom.CTabFolder> searchPartStackFolderRef;
    private static volatile WeakReference<org.eclipse.swt.custom.CTabItem> searchPartStackItemRef;
    private static volatile boolean partStackMouseGuardInstalled;

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
            installPartStackMouseGuard();
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
                @Override public void queryRemoved(ISearchQuery query)
                {
                    log("onQueryEvent: queryRemoved " + (query != null ? query.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
                }
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
    /**
     * Счётчик «поколения» поиска: увеличивается в {@link #onSearchStarting()}. Отложенные
     * (asyncExec/timerExec) продолжения — {@code restoreTableSelection}, диагностика
     * {@code panelHealth} — захватывают текущее значение при планировании и сверяют его при
     * срабатывании; если за это время стартовал новый поиск, продолжение — гонка с устаревшими
     * данными (старые ключи/узлы/виджеты могли быть уже пересозданы) и должно молча выйти,
     * а не пытаться применить результат к новому состоянию панели.
     */
    private static volatile int searchGeneration;

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
        searchGeneration++;
        SAVED_TABLE_SELECTION_BY_VIEWER.clear();
        log("onSearchStarting: watch first root, gen=" + searchGeneration); //$NON-NLS-1$
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
        // #165 триггерится двойным кликом по строке результата, не завершением поиска —
        // автофикс только в open/panelHealth/watchdog, здесь не лечим.
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

    /**
     * Диагностика #165 (watchdog/resize/hide/panelHealth) должна работать независимо от
     * «Улучшать списки» (ComfortSettings.isReplaceListFiltersEnabled): по факту репорта — баг
     * опустошения панели воспроизводится и при ВЫКЛЮЧЕННОЙ этой настройке (значит дело не в
     * агрегации/колонке «Путь»), но раньше schedulePatch/tryPatch целиком гейтились этим флагом —
     * с выключенной настройкой ни один диагностический слушатель даже не устанавливался. Теперь
     * гейт — «включена хотя бы агрегация ИЛИ общее логирование» (иначе диагностика молчала бы,
     * даже если пользователь явно включил логирование, чтобы найти этот баг).
     */
    private static void schedulePatch(IViewPart view, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled() && !Global.isLogEnabled())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 150;
        display.timerExec(delay, () -> {
            if (display.isDisposed())
                return;
            if (!ComfortSettings.isReplaceListFiltersEnabled() && !Global.isLogEnabled())
                return; // обе настройки могли выключить, пока ждали появления страницы
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

            Object tableViewerObj = Global.getField(treeLayout, "tableViewer"); //$NON-NLS-1$
            if (!(tableViewerObj instanceof TableViewer))
            {
                log("tryPatch: tableViewer=null/не TableViewer"); //$NON-NLS-1$
                return false;
            }
            TableViewer tableViewer = (TableViewer) tableViewerObj;

            // Диагностика #165 — своя, независимая от «Улучшать списки» (у каждого install*
            // своя защита от повторной установки через *_HOOKED_KEY, поэтому вызывать безусловно
            // безопасно): баг опустошения панели воспроизводится и с выключенной агрегацией,
            // так что диагностика обязана работать сама по себе, без привязки к этой фиче.
            installSearchResultDiagListener(activePage);
            installOpenDiagMonitor(activePage, treeViewer, tableViewer);
            installPanelWatchdog(activePage, treeViewer, tableViewer);
            installResizeDiag(treeViewer, tableViewer);

            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return true; // диагностика уже установлена, сама фича — выключена, дальше не идём

            installTreeMatchCountLabelProvider(treeViewer);

            if (treeViewer.getTree().getData(HOOKED_KEY) != null)
                return true; // фича уже установлена для этого дерева

            installAggregationListener(treeViewer, tableViewer);
            installPathColumn(tableViewer);
            installTableCopyHandler(treeViewer, tableViewer);
            TreeSoleChildAutoExpand.installWhitelisted(
                    TreeSoleChildAutoExpand.Target.SEARCH_CONFIG, treeViewer);

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
            try
            {
                saveTableSelection(treeViewer, tableViewer, treeViewer.getStructuredSelection().toList(), "table"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                // Раньше здесь не было try/catch: необработанное исключение из SWT
                // SelectionChanged-листенера могло "дозвучать" в цепочке событий двойного клика
                // (issue: панель результатов поиска иногда опустошается после открытия объекта).
                log("tableViewer.selectionChanged EXCEPTION: " + e); //$NON-NLS-1$
            }
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
        int gen = searchGeneration; // см. Javadoc searchGeneration — снимок «поколения» на момент планирования
        table.getDisplay().asyncExec(
            () -> restoreTableSelection(treeViewer, tableViewer, contextNodes, previousKeys, terminal, 0, gen));
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
        swtColumn.setToolTipText("Путь" + Global.pluginSignForTooltip());
        swtColumn.setResizable(true);
        swtColumn.setMoveable(false);
        swtColumn.setWidth(0); // по умолчанию скрыта — показывается только при агрегации

        // ВАЖНО: обычный CellLabelProvider рисуется нативной отрисовкой Table (Windows custom draw);
        // в тёмной теме для строки под курсором (выделенной) это иногда даёт невидимый текст
        // (не тот цвет переднего плана, что у выделения). Соседние штатные колонки «Свойство»/«Текст»
        // используют owner-draw через (Delegating)StyledCellLabelProvider — там такого не бывает,
        // т.к. цвет текста берётся из GC в PaintItem, а не из нативной отрисовки строки.
        // Поэтому колонку «Путь» тоже делаем owner-draw через StyledString.
        column.setLabelProvider(new DelegatingStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                Map<Object, String> pathByItem = PATH_MAPS_BY_TABLE_VIEWER.get(tableViewer);
                String path = pathByItem != null ? pathByItem.get(element) : null;
                return new StyledString(path != null ? path : ""); //$NON-NLS-1$
            }

            @Override
            public Image getImage(Object element) { return null; }

            @Override
            public void addListener(ILabelProviderListener listener) {}

            @Override
            public void dispose() {}

            @Override
            public boolean isLabelProperty(Object element, String property) { return false; }

            @Override
            public void removeListener(ILabelProviderListener listener) {}
        }));

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
            List<Object> contextNodes, List<TableRowKey> previousKeys, boolean terminal, int attempt, int gen)
    {
        if (gen != searchGeneration)
        {
            // Пока ждали (timerExec-цепочка), стартовал новый поиск — ключи/узлы этого вызова
            // относятся к прежнему поколению результатов. Молча выходим, не трогая уже
            // пересозданные виджеты/модель новой панели (см. Javadoc searchGeneration).
            if (attempt == 0)
                log("restoreTableSelection: skip — устарело (новый поиск), gen=" + gen //$NON-NLS-1$
                    + " current=" + searchGeneration); //$NON-NLS-1$
            return;
        }
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
                () -> restoreTableSelection(treeViewer, tableViewer, contextNodes, previousKeys, terminal, attempt + 1, gen));
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
            () -> restoreTableSelection(treeViewer, tableViewer, contextNodes, previousKeys, terminal, attempt + 1, gen));
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
    // Диагностика #165 (только журнал «Комфорт», без изменения поведения)
    // -----------------------------------------------------------------------

    /**
     * Снимок состояния панели поиска для диагностики #165. Раньше проверялись только счётчики
     * элементов нашего {@code treeViewer}/{@code tableViewer} — но по факту двух логов, где
     * {@code panelHealth} рапортовал "здоров" (items не пусты), а пользователь тем не менее видел
     * пустую панель с плейсхолдером "Поиск...", это оказалось недостаточно: наши виджеты — это
     * КОНКРЕТНЫЕ объекты, захваченные при установке хука на страницу результатов; сами по себе
     * они остаются живыми и полными данных, даже если {@code SearchView} в этот момент показывает
     * СОВСЕМ ДРУГУЮ страницу (например, свежий фоновый поиск EDT переключил активную страницу через
     * pagebook) — тогда то, что видит пользователь, вообще не наши дерево/таблица, а чужой пустой
     * плейсхолдер поверх/вместо них. Поэтому дополнительно: {@code samePage}, {@code treeVisible},
     * и {@code searchPartVisible} — иначе штатный клик на «Журнал Комфорт» (ради лога) даёт
     * ложный {@code treeVisible=false} и выглядит как #165 (ошибка разбора Clip_851973).
     */
    private static final class PanelState
    {
        final int treeItems;
        final boolean treeVisible;
        final boolean samePage;
        final boolean searchPartVisible;
        final String activePageClass;
        final String tableState;
        final String inputState;

        PanelState(int treeItems, boolean treeVisible, boolean samePage, boolean searchPartVisible,
                String activePageClass, String tableState, String inputState)
        {
            this.treeItems = treeItems;
            this.treeVisible = treeVisible;
            this.samePage = samePage;
            this.searchPartVisible = searchPartVisible;
            this.activePageClass = activePageClass;
            this.tableState = tableState;
            this.inputState = inputState;
        }

        /**
         * «Проблема» #165 — пользователь смотрит на Search ({@code searchPartVisible}), а дерево
         * пусто/не видно/не та страница. Скрытость из‑за другой вкладки стека — не проблема.
         */
        boolean isProblem()
        {
            if (!searchPartVisible)
                return false;
            return !samePage || !treeVisible || treeItems == 0;
        }

        @Override
        public String toString()
        {
            return "{treeItems=" + treeItems + " treeVisible=" + treeVisible //$NON-NLS-1$ //$NON-NLS-2$
                + " searchPartVisible=" + searchPartVisible //$NON-NLS-1$
                + " " + tableState + " " + inputState //$NON-NLS-1$ //$NON-NLS-2$
                + " activePage=" + activePageClass + " samePage=" + samePage + "}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    private static PanelState computePanelState(TreeViewer treeViewer, TableViewer tableViewer,
            ISearchResultPage page)
    {
        Tree tree = treeViewer != null ? treeViewer.getTree() : null;
        boolean treeDisposed = tree == null || tree.isDisposed();
        int treeItems = treeDisposed ? -1 : tree.getItemCount();
        boolean treeVisible = !treeDisposed && tree.isVisible();

        boolean samePage = false;
        boolean searchPartVisible = false;
        String activePageClass = "?"; //$NON-NLS-1$
        try
        {
            IViewPart view = findSearchViewPart();
            if (!(view instanceof ISearchResultViewPart resultView))
                activePageClass = "NO_SEARCH_VIEW"; //$NON-NLS-1$
            else
            {
                ISearchResultPage currentActive = resultView.getActivePage();
                samePage = currentActive == page;
                activePageClass = currentActive != null ? currentActive.getClass().getSimpleName() : "null"; //$NON-NLS-1$
                IWorkbenchPage wbPage = view.getSite() != null ? view.getSite().getPage() : null;
                searchPartVisible = wbPage != null && wbPage.isPartVisible(view);
            }
        }
        catch (Exception e)
        {
            activePageClass = "ERR:" + e.getClass().getSimpleName(); //$NON-NLS-1$
        }

        String inputState = "input=?"; //$NON-NLS-1$
        try
        {
            Object input = page != null ? Global.invoke(page, "getInput") : null; //$NON-NLS-1$
            if (input == null)
                inputState = "input=null"; //$NON-NLS-1$
            else
            {
                Object elements = Global.invoke(input, "getElements"); //$NON-NLS-1$
                int elemCount = elements instanceof Collection<?> ? ((Collection<?>) elements).size() : -1;
                inputState = "input=" + input.getClass().getSimpleName() + " elements=" + elemCount; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (Exception e)
        {
            inputState = "input=ERR:" + e.getClass().getSimpleName(); //$NON-NLS-1$
        }

        return new PanelState(treeItems, treeVisible, samePage, searchPartVisible, activePageClass,
            describeTableSelectionState(tableViewer), inputState);
    }

    private static String describePanelState(TreeViewer treeViewer, TableViewer tableViewer,
            ISearchResultPage page)
    {
        return computePanelState(treeViewer, tableViewer, page).toString();
    }

    private static void installOpenDiagMonitor(ISearchResultPage page, TreeViewer treeViewer,
            TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed() || table.getData(OPEN_DIAG_HOOKED_KEY) != null)
            return;
        table.setData(OPEN_DIAG_HOOKED_KEY, Boolean.TRUE);

        IOpenListener openListener = new IOpenListener()
        {
            @Override
            public void open(OpenEvent event)
            {
                // Диагностика #165 — независимо от «Улучшать списки» (см. Javadoc tryPatch).
                int seq = ++openSeq;
                beginOpenPartStackProtect(treeViewer.getTree());
                log("open: seq=" + seq + " " + describePanelState(treeViewer, tableViewer, page)); //$NON-NLS-1$ //$NON-NLS-2$
                schedulePanelHealthDiag(page, treeViewer, tableViewer, 0, seq);
                schedulePanelHealthDiag(page, treeViewer, tableViewer, 100, seq);
                schedulePanelHealthDiag(page, treeViewer, tableViewer, 500, seq);
                schedulePanelHealthDiag(page, treeViewer, tableViewer, 1500, seq);
            }
        };
        tableViewer.addOpenListener(openListener);
        treeViewer.addOpenListener(openListener);
    }

    /**
     * Непрерывный опрос состояния панели раз в {@link #WATCHDOG_INTERVAL_MS} — в отличие от
     * {@link #installOpenDiagMonitor} (который проверяет только 1.5с после клика), живёт всё время
     * жизни страницы результатов. Нужен потому, что по факту двух присланных логов опустошение
     * панели пользователь замечал не сразу после клика, а позже (когда просто смотрел на панель) —
     * то есть вне 1.5-секундного окна `panelHealth`. Логируем только на ПЕРЕХОДЕ между
     * здоровым/проблемным состоянием (не каждую секунду), чтобы не заспамить журнал «Комфорт».
     */
    private static void installPanelWatchdog(ISearchResultPage page, TreeViewer treeViewer,
            TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed() || table.getData(WATCHDOG_HOOKED_KEY) != null)
            return;
        table.setData(WATCHDOG_HOOKED_KEY, Boolean.TRUE);

        boolean[] wasProblem = { false };
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable[] tickHolder = new Runnable[1];
        tickHolder[0] = () -> {
            Tree tree = treeViewer.getTree();
            Table tbl = tableViewer.getTable();
            if (tree == null || tree.isDisposed() || tbl == null || tbl.isDisposed())
                return; // страница закрыта/пересоздана — часы сами останавливаются, не перепланируем
            // Диагностика #165 — независимо от «Улучшать списки» (см. Javadoc tryPatch).
            {
                PanelState state = computePanelState(treeViewer, tableViewer, page);
                boolean isProblem = state.isProblem();
                if (isProblem != wasProblem[0])
                {
                    log((isProblem ? "WATCHDOG_PROBLEM_START: " : "WATCHDOG_PROBLEM_END: ") + state); //$NON-NLS-1$ //$NON-NLS-2$
                    if (isProblem && !state.treeVisible)
                        log("WATCHDOG_ANCESTOR_CHAIN: " + describeAncestorChain(tree)); //$NON-NLS-1$
                    wasProblem[0] = isProblem;
                }
                // Автофикс — только сразу из HIDE/onMouse (см. installResizeDiag), не раз в секунду.
            }
            display.timerExec(WATCHDOG_INTERVAL_MS, tickHolder[0]);
        };
        display.timerExec(WATCHDOG_INTERVAL_MS, tickHolder[0]);
    }

    /**
     * Синхронный перехват {@code SWT.Resize}/{@code SWT.Show}/{@code SWT.Hide} на дерево/таблицу
     * и ВСЮ цепочку родителей до {@code Shell}. По факту первого пойманного
     * {@code WATCHDOG_PROBLEM_START} (treeItems=1, samePage=true, но treeVisible=false, и при
     * этом ни одного {@code RESIZE} в логе) — размер не менялся, кто-то вызвал
     * {@code setVisible(false)} на одном из родителей ({@code Control.isVisible()} учитывает всю
     * цепочку, а {@code SWT.Resize} на {@code setVisible} не срабатывает — отсюда и молчание
     * прежней версии этой диагностики). {@code SWT.Show}/{@code SWT.Hide} ловят именно это,
     * синхронно, с указанием какого именно виджета в цепочке это коснулось.
     */
    private static void installResizeDiag(TreeViewer treeViewer, TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed() || table.getData(RESIZE_DIAG_HOOKED_KEY) != null)
            return;
        table.setData(RESIZE_DIAG_HOOKED_KEY, Boolean.TRUE);

        Control tree = treeViewer.getTree();
        Listener listener = event -> {
            // Диагностика #165 — независимо от «Улучшать списки» (см. Javadoc tryPatch),
            // log() сам молчит, если выключено «Общее логирование».
            Control src = event.widget instanceof Control ? (Control) event.widget : null;
            String who = src == tree ? "tree" //$NON-NLS-1$
                : src == table ? "table" //$NON-NLS-1$
                : src != null ? src.getClass().getSimpleName() + "@" + System.identityHashCode(src) : "?"; //$NON-NLS-1$ //$NON-NLS-2$
            String eventName = event.type == SWT.Resize ? "RESIZE" : event.type == SWT.Show ? "SHOW" : "HIDE"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            log(eventName + " " + who + ": " + describeBounds(src)); //$NON-NLS-1$ //$NON-NLS-2$
            // HIDE синхронно из setVisible(false) — стек покажет вызывающий код.
            // Штатный клик на другую вкладку стека (в т.ч. «Журнал Комфорт») тоже даёт HIDE —
            // desync-heal сработает только если selection всё ещё «Поиск» (см. plan #165).
            if (event.type != SWT.Hide)
                return;
            String stack = describeCurrentStackTrace();
            log("HIDE stacktrace: " + stack); //$NON-NLS-1$
            boolean fromCTab = stack.contains("CTabFolder.onMouse") //$NON-NLS-1$
                || stack.contains("CTabFolder.setSelection"); //$NON-NLS-1$
            if (!fromCTab)
                return;
            if (!isOpenProtectActive())
            {
                // Clip_851980: onMouse пришёл после истечения окна — filter уже не действует.
                log("OPEN_PROTECT: HIDE/onMouse outside protect +" + msSinceOpenArmed() + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            Display display = event.display != null ? event.display : Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> {
                if (tree.isDisposed())
                    return;
                tryHealSearchPartStackDesync("HIDE/onMouse"); //$NON-NLS-1$
            });
        };
        addAncestorListenerChain(tree, listener);
        addAncestorListenerChain(table, listener);
    }

    /** Вешает Resize/Show/Hide-листенер на сам control и всех родителей до {@code Shell}. */
    private static void addAncestorListenerChain(Control control, Listener listener)
    {
        Control cur = control;
        for (int depth = 0; cur != null && !cur.isDisposed() && depth < 20; depth++)
        {
            cur.addListener(SWT.Resize, listener);
            cur.addListener(SWT.Show, listener);
            cur.addListener(SWT.Hide, listener);
            if (cur instanceof org.eclipse.swt.widgets.Shell)
                break;
            cur = cur.getParent();
        }
    }

    private static String describeBounds(Control control)
    {
        if (control == null || control.isDisposed())
            return "disposed"; //$NON-NLS-1$
        Rectangle b = control.getBounds();
        // getVisible() — собственный флаг видимости этого виджета (без учёта родителей);
        // isVisible() — с учётом всей цепочки. Расхождение между ними у РАЗНЫХ виджетов
        // цепочки как раз укажет, на каком уровне видимость была снята.
        return "bounds=" + b.width + "x" + b.height //$NON-NLS-1$ //$NON-NLS-2$
            + " ownVisible=" + control.getVisible() + " chainVisible=" + control.isVisible(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Стек текущего потока (обычно UI-потока) — для HIDE-события, которое летит синхронно из места вызова {@code setVisible(false)}. */
    private static String describeCurrentStackTrace()
    {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        // Пропускаем верхние кадры самой диагностики (getStackTrace/эта лямбда/SWT dispatch) —
        // начинаем с кадра, где реально начинается чужой код, пока не упрёмся в предсказуемый потолок.
        int printed = 0;
        for (StackTraceElement el : trace)
        {
            String cls = el.getClassName();
            if (cls.equals(Thread.class.getName()) || cls.contains("SearchViewAggregationHook")) //$NON-NLS-1$
                continue;
            sb.append("\n    at ").append(el); //$NON-NLS-1$
            if (++printed >= 40)
                break;
        }
        return sb.toString();
    }

    /** Резервный дамп всей цепочки родителей — на случай, если SHOW/HIDE-событие не долетело до установки листенера. */
    private static String describeAncestorChain(Control control)
    {
        StringBuilder sb = new StringBuilder();
        Control cur = control;
        for (int depth = 0; cur != null && !cur.isDisposed() && depth < 20; depth++)
        {
            if (depth > 0)
                sb.append(" > "); //$NON-NLS-1$
            sb.append(cur.getClass().getSimpleName()).append('@').append(System.identityHashCode(cur))
                .append('(').append(describeBounds(cur)).append(')');
            if (cur instanceof org.eclipse.swt.widgets.Shell)
                break;
            cur = cur.getParent();
        }
        return sb.toString();
    }

    private static boolean isOpenProtectActive()
    {
        return System.currentTimeMillis() < openProtectDeadlineMs;
    }

    private static long msSinceOpenArmed()
    {
        long armed = openArmedAtMs;
        return armed <= 0 ? -1 : System.currentTimeMillis() - armed;
    }

    /** Запомнить PartStack «Поиск» и включить короткое окно защиты после Open. */
    private static void beginOpenPartStackProtect(Control tree)
    {
        openArmedAtMs = System.currentTimeMillis();
        openProtectDeadlineMs = openArmedAtMs + OPEN_PROTECT_MS;
        PartStackTab stack = findSearchPartStackTab(tree);
        if (stack == null)
        {
            searchPartStackFolderRef = null;
            searchPartStackItemRef = null;
            log("OPEN_PROTECT: PartStack не найден, protect +" + OPEN_PROTECT_MS + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        searchPartStackFolderRef = new WeakReference<>(stack.folder);
        searchPartStackItemRef = new WeakReference<>(stack.item);
        log("OPEN_PROTECT: armed +" + OPEN_PROTECT_MS + "ms tab=" + describeCTabItem(stack.item)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Блокирует MouseDown/Up на PartStack-{@code CTabFolder} только в {@link #OPEN_PROTECT_MS}
     * после Open — чтобы deferred-{@code onMouse} не делал {@code setSelection}.
     */
    private static void installPartStackMouseGuard()
    {
        if (partStackMouseGuardInstalled)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        partStackMouseGuardInstalled = true;
        Listener guard = event -> {
            if (!isOpenProtectActive())
                return;
            WeakReference<org.eclipse.swt.custom.CTabFolder> ref = searchPartStackFolderRef;
            org.eclipse.swt.custom.CTabFolder folder = ref != null ? ref.get() : null;
            if (folder == null || folder.isDisposed() || event.widget != folder)
                return;
            event.doit = false;
            String kind = event.type == SWT.MouseDown ? "MouseDown" //$NON-NLS-1$
                : event.type == SWT.MouseUp ? "MouseUp" : "Mouse"; //$NON-NLS-1$ //$NON-NLS-2$
            log("OPEN_PROTECT: blocked " + kind + " on PartStack +" + msSinceOpenArmed() + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
        display.addFilter(SWT.MouseDown, guard);
        display.addFilter(SWT.MouseUp, guard);
        log("OPEN_PROTECT: Display mouse filter installed"); //$NON-NLS-1$
    }

    /**
     * Восстановление рассинхрона #165: вкладка «Поиск» выбрана ({@code getSelection()==item}),
     * а её content скрыт. Чужую вкладку стека не трогаем.
     */
    private static void tryHealSearchPartStackDesync(String reason)
    {
        if (!isOpenProtectActive())
            return;
        WeakReference<org.eclipse.swt.custom.CTabFolder> folderRef = searchPartStackFolderRef;
        WeakReference<org.eclipse.swt.custom.CTabItem> itemRef = searchPartStackItemRef;
        org.eclipse.swt.custom.CTabFolder folder = folderRef != null ? folderRef.get() : null;
        org.eclipse.swt.custom.CTabItem ourItem = itemRef != null ? itemRef.get() : null;
        if (folder == null || folder.isDisposed() || ourItem == null || ourItem.isDisposed())
            return;
        org.eclipse.swt.custom.CTabItem selected = folder.getSelection();
        if (selected != ourItem)
        {
            // Штатный уход на другую вкладку (в т.ч. «Журнал Комфорт») — не наш баг.
            log("SELF_HEAL desync skip (" + reason + "): selection=" //$NON-NLS-1$ //$NON-NLS-2$
                + describeCTabItem(selected) + " +" + msSinceOpenArmed() + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Control content = ourItem.getControl();
        if (content == null || content.isDisposed() || content.getVisible())
            return;

        log("SELF_HEAL desync (" + reason + "): content hidden while Поиск selected +" //$NON-NLS-1$ //$NON-NLS-2$
            + msSinceOpenArmed() + "ms"); //$NON-NLS-1$
        folder.setRedraw(false);
        try
        {
            org.eclipse.swt.custom.CTabItem other = findOtherCTabItem(folder, ourItem);
            if (other != null)
            {
                // early-return setSelection(index==selected) не вызывает setVisible(true).
                folder.setSelection(other);
                folder.setSelection(ourItem);
            }
            else
            {
                // Единственная вкладка — безопасный setVisible на уже выбранном content.
                content.setVisible(true);
            }
        }
        finally
        {
            if (!folder.isDisposed())
                folder.setRedraw(true);
        }
        Control after = ourItem.isDisposed() ? null : ourItem.getControl();
        log("SELF_HEAL desync done: contentOwnVisible=" //$NON-NLS-1$
            + (after == null || after.isDisposed() ? "?" : Boolean.toString(after.getVisible())) //$NON-NLS-1$
            + " +" + msSinceOpenArmed() + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class PartStackTab
    {
        final org.eclipse.swt.custom.CTabFolder folder;
        final org.eclipse.swt.custom.CTabItem item;

        PartStackTab(org.eclipse.swt.custom.CTabFolder folder, org.eclipse.swt.custom.CTabItem item)
        {
            this.folder = folder;
            this.item = item;
        }
    }

    private static PartStackTab findSearchPartStackTab(Control tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
        Control cur = tree;
        for (int depth = 0; cur != null && !cur.isDisposed() && depth < 20; depth++)
        {
            Control parent = cur.getParent();
            if (parent instanceof org.eclipse.swt.custom.CTabFolder)
            {
                org.eclipse.swt.custom.CTabFolder folder = (org.eclipse.swt.custom.CTabFolder) parent;
                for (org.eclipse.swt.custom.CTabItem item : folder.getItems())
                {
                    if (item != null && !item.isDisposed() && item.getControl() == cur)
                        return new PartStackTab(folder, item);
                }
                return null;
            }
            if (parent instanceof org.eclipse.swt.widgets.Shell || parent == null)
                break;
            cur = parent;
        }
        return null;
    }

    private static org.eclipse.swt.custom.CTabItem findOtherCTabItem(
            org.eclipse.swt.custom.CTabFolder folder, org.eclipse.swt.custom.CTabItem ourItem)
    {
        for (org.eclipse.swt.custom.CTabItem item : folder.getItems())
        {
            if (item != null && !item.isDisposed() && item != ourItem)
                return item;
        }
        return null;
    }

    private static String describeCTabItem(org.eclipse.swt.custom.CTabItem item)
    {
        if (item == null || item.isDisposed())
            return "null"; //$NON-NLS-1$
        Control c = item.getControl();
        return "'" + item.getText() + "' ctrlVisible=" //$NON-NLS-1$ //$NON-NLS-2$
            + (c == null || c.isDisposed() ? "?" : Boolean.toString(c.getVisible())); //$NON-NLS-1$
    }

    private static void schedulePanelHealthDiag(ISearchResultPage page, TreeViewer treeViewer,
            TableViewer tableViewer, int delayMs, int seq)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(delayMs, () -> {
            if (seq != openSeq)
                return; // перекрыто более новым кликом — не мешаем логи разных открытий
            if (treeViewer.getTree() == null || treeViewer.getTree().isDisposed())
                return;
            if (tableViewer.getTable() == null || tableViewer.getTable().isDisposed())
                return;
            PanelState state = computePanelState(treeViewer, tableViewer, page);
            log("panelHealth seq=" + seq + " +" + delayMs + "ms: " + state); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            // Только диагностика — автофикс только из HIDE→asyncExec (desync), не отсюда.
            if (state.isProblem())
                log("TREE_EMPTIED seq=" + seq + " +" + delayMs + "ms: " + state); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        });
    }

    private static void installSearchResultDiagListener(ISearchResultPage page)
    {
        try
        {
            Object input = Global.invoke(page, "getInput"); //$NON-NLS-1$
            if (!(input instanceof ISearchResult result))
                return;
            synchronized (RESULT_LISTENERS_INSTALLED)
            {
                if (!RESULT_LISTENERS_INSTALLED.add(result))
                    return;
            }
            result.addListener(new ISearchResultListener()
            {
                @Override
                public void searchResultChanged(SearchResultEvent event)
                {
                    if (event == null)
                        return;
                    String kind = event.getClass().getSimpleName();
                    log("searchResultChanged: " + kind); //$NON-NLS-1$
                    if (!kind.contains("Reset") && !kind.contains("Remove")) //$NON-NLS-1$ //$NON-NLS-2$
                        return;
                    Display display = Display.getDefault();
                    if (display == null || display.isDisposed())
                        return;
                    display.asyncExec(() -> {
                        SearchViewViewers viewers = resolveViewers(findSearchViewPart());
                        if (viewers == null)
                            return;
                        IViewPart view = findSearchViewPart();
                        ISearchResultPage active = view instanceof ISearchResultViewPart
                            ? ((ISearchResultViewPart) view).getActivePage() : null;
                        log("searchResultChanged after " + kind + ": " //$NON-NLS-1$ //$NON-NLS-2$
                            + describePanelState(viewers.tree, viewers.table, active));
                    });
                }
            });
            log("installSearchResultDiagListener: OK"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            log("installSearchResultDiagListener EXCEPTION: " + e); //$NON-NLS-1$
        }
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
