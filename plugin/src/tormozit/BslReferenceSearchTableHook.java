package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.Page;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.xtext.resource.IReferenceDescription;

/**
 * Табличный режим панели результатов команды «Найти ссылки» на программный элемент BSL (переменную,
 * параметр, процедуру/функцию) — issue 464.
 *
 * <p>Штатно эти результаты (Xtext-запрос {@code BslReferenceQuery} → {@code BslReferenceSearchResult}
 * → страница {@code org.eclipse.xtext.ui.editor.findrefs.ReferenceSearchViewPage}) показаны деревом:
 * узлы-ресурсы, внутри — вхождения. Чтобы понять контекст вхождения, приходится раскрывать узлы и
 * щёлкать по каждому, а на крупных выборках (сотни-тысячи вхождений) штатная отрисовка дерева
 * подвешивает UI на секунды.
 *
 * <p>Здесь дерево заменяется плоской таблицей с колонками «Файл», «Тип файла», «Модуль», «Метод»,
 * «Родитель», «Тип родителя», «Категория», «Строка», «Текст», сортировкой, отбором по значению
 * ячейки и множественным выделением. Режим <b>неотключаемый</b>: кнопки-переключателя нет, для этого
 * вида поиска всегда таблица (в отличие от табличного режима окна «Рефакторинг»
 * {@link RefactoringPreviewTableHook}, где переключатель есть).
 *
 * <p>Устройство: дерево переносится в скрытый слой ({@link TopControlStack}) внутри того же
 * контейнера страницы, на его место встаёт наша таблица. Дерево остаётся живым (нужно штатным
 * кнопкам «следующее/предыдущее», удалению вхождений и открытию через {@code handleOpen}), но никогда
 * не отрисовывается — поэтому дорогой штатный {@code BslReferenceSearchResultLabelProvider}
 * (поднимает EMF-ресурс модуля на каждое вхождение при отрисовке) больше не вызывается, и подвисание
 * при завершении поиска уходит.
 *
 * <p>Колонки «Метод», «Родитель», «Тип родителя», «Категория» и позицию вхождения в тексте модуля
 * считает {@link BslOccurrenceContextResolver} / {@link BslModuleMethodResolver} в фоне (позиция —
 * из URI источника ссылки через {@code referenceNodeRegion}, как в {@code ConfigSearchResultsHook}).
 * До расчёта в ячейке типа стоит «?», в заголовке колонки — счётчик обработанных вхождений.
 */
public final class BslReferenceSearchTableHook implements IStartup
{
    private static final String SEARCH_VIEW_ID = "org.eclipse.search.ui.views.SearchView"; //$NON-NLS-1$
    /** Штатная страница результатов Xtext-поиска ссылок (регистрируется в {@code bsl.ui/plugin.xml}). */
    private static final String PAGE_CLASS_MARKER = "ReferenceSearchViewPage"; //$NON-NLS-1$
    private static final String RESULT_CLASS_MARKER = "BslReferenceSearchResult"; //$NON-NLS-1$
    private static final String HANDLED_KEY = "tormozit.bslReferenceSearchTable"; //$NON-NLS-1$

    private static final int RETRY_DELAY_MS = 100;
    private static final int MAX_ATTEMPTS = 100;
    private static final Object[] NO_ELEMENTS = new Object[0];

    @Override
    public void earlyStartup()
    {
        // earlyStartup идёт НЕ в UI-потоке — вся работа с Display только через asyncExec.
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            try
            {
                IWorkbench wb = PlatformUI.getWorkbench();
                if (wb == null)
                    return;
                for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                    hookWindow(window);
                wb.addWindowListener(new org.eclipse.ui.IWindowListener()
                {
                    @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                    @Override public void windowActivated(IWorkbenchWindow w) {}
                    @Override public void windowDeactivated(IWorkbenchWindow w) {}
                    @Override public void windowClosed(IWorkbenchWindow w) {}
                });
                NewSearchUI.addQueryListener(new IQueryListener()
                {
                    @Override public void queryAdded(ISearchQuery query) {}
                    @Override public void queryRemoved(ISearchQuery query) {}
                    // queryStarting — цикл ожидания страницы стартует заранее: таблица встаёт на место
                    // дерева раньше, чем штатный поставщик успеет отрисовать крупную выборку.
                    @Override public void queryStarting(ISearchQuery query) { onQueryEvent(); }
                    @Override public void queryFinished(ISearchQuery query) { onQueryEvent(); }
                });
                Global.tempLog("ref-search-table", "earlyStartup: слушатели подключены"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException | LinkageError e)
            {
                Global.tempLog("ref-search-table", "earlyStartup failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
            try
            {
                startUiStallWatch(display);
            }
            catch (RuntimeException | LinkageError e)
            {
                Global.tempLog("ref-search-table", "stall watch failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    private static void onQueryEvent()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            IViewPart view = findSearchViewPart();
            if (view != null)
                schedulePatch(view, 0);
        });
    }

    /**
     * ВРЕМЕННАЯ ДИАГНОСТИКА (issue 464 — подвисание UI на крупных выборках). Фоновый поток раз в
     * 250 мс проверяет «пульс» UI-потока (таймер каждые 200 мс); если UI не отвечал &gt; 900 мс —
     * снимает стек UI-потока прямо во время зависания. Снять после разбора.
     */
    private static void startUiStallWatch(Display display)
    {
        Thread ui = display.getThread();
        if (ui == null)
            return;
        java.util.concurrent.atomic.AtomicLong heartbeat =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        Runnable[] beat = new Runnable[1];
        beat[0] = () ->
        {
            heartbeat.set(System.currentTimeMillis());
            if (!display.isDisposed())
                display.timerExec(200, beat[0]);
        };
        display.timerExec(200, beat[0]);
        Thread watch = new Thread(() ->
        {
            boolean reported = false;
            while (!display.isDisposed())
            {
                try
                {
                    Thread.sleep(250);
                }
                catch (InterruptedException e)
                {
                    return;
                }
                long lag = System.currentTimeMillis() - heartbeat.get();
                if (lag > 900 && !reported)
                {
                    reported = true;
                    StringBuilder sb = new StringBuilder("UI stalled ~" + lag + " ms; ui-thread stack:\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    StackTraceElement[] stack = ui.getStackTrace();
                    for (int i = 0; i < Math.min(stack.length, 40); i++)
                        sb.append("  at ").append(stack[i]).append('\n'); //$NON-NLS-1$
                    Global.tempLog("ref-search-stall", sb.toString()); //$NON-NLS-1$
                }
                else if (lag < 300)
                {
                    reported = false;
                }
            }
        }, "comfort-ref-search-stall-watch"); //$NON-NLS-1$
        watch.setDaemon(true);
        watch.start();
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
                if (isSearchView(view))
                    schedulePatch(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isSearchView(part))
                    schedulePatch((IViewPart)part, 0);
            }
        });
    }

    private static boolean isSearchView(Object part)
    {
        return part instanceof IViewPart vp && vp.getViewSite() != null
            && SEARCH_VIEW_ID.equals(vp.getViewSite().getId());
    }

    private static IViewPart findSearchViewPart()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return null;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                continue;
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

    private static void schedulePatch(IViewPart view, int attempt)
    {
        if (view == null || attempt >= MAX_ATTEMPTS)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (tryPatch(view))
            return;
        display.timerExec(RETRY_DELAY_MS, () -> schedulePatch(view, attempt + 1));
    }

    /**
     * @return {@code true} — режим подключён либо к этой странице не относится (ждать больше нечего);
     *     {@code false} — страница ещё не готова, стоит повторить
     */
    private static boolean tryPatch(IViewPart view)
    {
        if (!(view instanceof ISearchResultViewPart searchView))
            return true;
        Object activePage = searchView.getActivePage();
        if (activePage == null || !activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
            return true;
        if (!(activePage instanceof Page))
            return true;
        if (!(Global.invoke(activePage, "getViewer") instanceof TreeViewer treeViewer)) //$NON-NLS-1$
            return false;
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getParent() == null || tree.getParent().isDisposed())
            return false;

        Composite host = tree.getParent();
        Object existing = host.getData(HANDLED_KEY);
        if (existing instanceof ReferenceTablePane pane)
        {
            Global.tempLog("ref-search-table", "tryPatch: страница уже с таблицей → reload"); //$NON-NLS-1$ //$NON-NLS-2$
            pane.reload();
            return true;
        }
        try
        {
            long t0 = System.currentTimeMillis();
            ReferenceTablePane pane = ReferenceTablePane.install(activePage, treeViewer);
            if (pane != null)
                host.setData(HANDLED_KEY, pane);
            Global.tempLog("ref-search-table", "install: " + (System.currentTimeMillis() - t0) + " ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (RuntimeException | LinkageError e)
        {
            // Вёрстка/классы страницы могли измениться в новой версии EDT — остаётся штатное дерево.
            Global.tempLog("ref-search-table", "install failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return true;
    }

    /** Строка таблицы: лист дерева результатов и всё, что о вхождении удалось вычислить. */
    private static final class ReferenceRow implements OccurrenceContextResolveJob.Target
    {
        final IFile file;
        final URI sourceUri;
        final EReference reference;
        final int indexInList;
        /** Простое имя искомого элемента (из заголовка результата) — для поиска токена вхождения. */
        String targetName;
        /** Точный диапазон из полнотекстового вхождения ({@code getSelection()}); {@code null} — нет. */
        int[] knownRegion;

        String project = ""; //$NON-NLS-1$
        String fileName = ""; //$NON-NLS-1$
        String fileType = ""; //$NON-NLS-1$
        String module = ""; //$NON-NLS-1$
        String method = ""; //$NON-NLS-1$
        String parent = ""; //$NON-NLS-1$
        String syntaxKind = ""; //$NON-NLS-1$
        String lineText = ""; //$NON-NLS-1$
        /** Смещение и длина вхождения внутри {@link #lineText} — подсветка в колонке «Текст». */
        int highlightStart;
        int highlightLength;
        int line;
        /** Координаты вхождения в тексте модуля, вычислены быстрым проходом. */
        int[] region;
        /** {@code null} — контекст ещё не вычисляли (в ячейке «?»). */
        String parentType;

        ReferenceRow(IFile file, URI sourceUri, EReference reference, int indexInList)
        {
            this.file = file;
            this.sourceUri = sourceUri;
            this.reference = reference;
            this.indexInList = indexInList;
        }

        @Override
        public IFile file()
        {
            return file;
        }

        @Override
        public boolean needsContext()
        {
            return sourceUri != null && BslModuleMethodResolver.isBslModule(file);
        }

        @Override
        public int[] resolveRegion()
        {
            if (knownRegion != null)
                return knownRegion;
            return BslOccurrenceContextResolver.referenceNodeRegion(file, sourceUri, reference,
                indexInList, true, targetName);
        }

        @Override
        public int[] resolvedRegion()
        {
            return region;
        }

        @Override
        public void applyFast(OccurrenceContextResolveJob.FastContext context)
        {
            region = context.offset >= 0 ? new int[] {context.offset, context.length} : null;
            line = context.line;
            lineText = context.lineText;
            highlightStart = context.highlightStart;
            highlightLength = context.highlightLength;
            parent = context.parent;
            syntaxKind = context.syntaxKind;
            method = context.method;
        }

        @Override
        public void applyParentType(String type)
        {
            parentType = type;
        }

        @Override public String occurrenceLineText() { return lineText; }
        @Override public int occurrenceHighlightStart() { return highlightStart; }
        @Override public int occurrenceHighlightLength() { return highlightLength; }
    }

    /** Таблица вместо дерева результатов и всё её поведение. */
    private static final class ReferenceTablePane
    {
        private static final String SETTINGS_SECTION = "tormozit.bslReferenceSearchTable"; //$NON-NLS-1$
        private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
        private static final String KEY_COLUMNS_FILL = "columnsFill"; //$NON-NLS-1$
        private static final String[] WIDTH_KEYS = {"projectWidth", "fileWidth", "fileTypeWidth", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "moduleWidth", "methodWidth", "parentWidth", "parentTypeWidth", "syntaxWidth", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "lineWidth", "textWidth"}; //$NON-NLS-1$ //$NON-NLS-2$
        private static final int[] DEFAULT_WIDTHS = {150, 200, 80, 240, 180, 170, 200, 110, 60, 320};
        private static final int MIN_COLUMN_WIDTH = 40;

        private static final String UNKNOWN_TYPE = "?"; //$NON-NLS-1$
        private static final String PARENT_TYPE_TITLE = BslOccurrenceContextResolver.COL_PARENT_TYPE;
        private static final int DEBOUNCE_MS = 300;
        /** До этого числа строк «Тип родителя» считается автоматически; больше — только по кнопке
         * «Рассчитать типы» (проход поднимает модель BSL на каждый модуль — секунды на модуль). */
        private static final int TYPE_AUTO_THRESHOLD = 25;
        private static final String COMPUTE_TYPES_ACTION_ID = "tormozit.bslRefSearch.computeParentTypes"; //$NON-NLS-1$

        private final Object page;
        private final TreeViewer treeViewer;
        private final Composite host;
        private final TopControlStack stackLayout;
        private final Composite tableHost;
        private final Table table;
        private final TableViewer viewer;
        private final List<ReferenceRow> rows = new ArrayList<>();

        private FormTableInteraction interaction;
        private OccurrenceContextResolveJob contextResolver;
        private org.eclipse.ui.IActionBars actionBars;
        private org.eclipse.jface.action.Action computeTypesAction;
        private boolean reloadScheduled;

        private ReferenceTablePane(Object page, TreeViewer treeViewer, Composite host,
            TopControlStack stackLayout, Composite tableHost, Table table, TableViewer viewer)
        {
            this.page = page;
            this.treeViewer = treeViewer;
            this.host = host;
            this.stackLayout = stackLayout;
            this.tableHost = tableHost;
            this.table = table;
            this.viewer = viewer;
        }

        static ReferenceTablePane install(Object page, TreeViewer treeViewer)
        {
            Tree tree = treeViewer.getTree();
            Composite host = tree.getParent();

            // host штатно несёт FillLayout с деревом единственным потомком (ReferenceSearchViewPage
            // .createControl). Меняем на стек: дерево уезжает вниз, наверх — tableHost.
            TopControlStack stackLayout = new TopControlStack();
            host.setLayout(stackLayout);

            Composite tableHost = new Composite(host, SWT.NONE);
            tableHost.setLayout(null);
            Composite columnHost = new Composite(tableHost, SWT.NONE);
            TableColumnLayout columnLayout = new TableColumnLayout();
            columnHost.setLayout(columnLayout);

            TableViewer viewer = new TableViewer(
                new Table(columnHost, SWT.MULTI | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL));
            Table table = viewer.getTable();
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);
            tableHost.addControlListener(new org.eclipse.swt.events.ControlAdapter()
            {
                @Override
                public void controlResized(org.eclipse.swt.events.ControlEvent e)
                {
                    if (!columnHost.isDisposed())
                        columnHost.setBounds(tableHost.getClientArea());
                }
            });

            ReferenceTablePane pane =
                new ReferenceTablePane(page, treeViewer, host, stackLayout, tableHost, table, viewer);
            pane.createColumns(columnLayout);

            stackLayout.topControl = tableHost;
            host.layout(true, true);
            tree.setRedraw(false);
            pane.detachStockTree();

            pane.wireListeners();
            pane.reload();
            return pane;
        }

        private void createColumns(TableColumnLayout columnLayout)
        {
            IDialogSettings settings = dialogSettings();
            addColumn(columnLayout, settings, 0, "Проект", r -> r.project); //$NON-NLS-1$
            addColumn(columnLayout, settings, 1, "Файл", r -> r.fileName); //$NON-NLS-1$
            addColumn(columnLayout, settings, 2, "Тип файла", r -> r.fileType); //$NON-NLS-1$
            addColumn(columnLayout, settings, 3, "Модуль", r -> r.module); //$NON-NLS-1$
            addColumn(columnLayout, settings, 4, "Метод", r -> r.method); //$NON-NLS-1$
            TableColumn parentColumn =
                addColumn(columnLayout, settings, 5, BslOccurrenceContextResolver.COL_PARENT, r -> r.parent);
            TableColumn parentTypeColumn = addColumn(columnLayout, settings, 6, PARENT_TYPE_TITLE,
                r -> r.parentType != null ? r.parentType : UNKNOWN_TYPE);
            TableColumn syntaxColumn = addColumn(columnLayout, settings, 7,
                BslOccurrenceContextResolver.COL_SYNTAX_KIND, r -> r.syntaxKind);
            addColumn(columnLayout, settings, 8, "Строка", r -> r.line > 0 ? String.valueOf(r.line) : ""); //$NON-NLS-1$ //$NON-NLS-2$
            TableColumn textColumn = addTextColumn(columnLayout, settings, 9);

            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.setComparator(new ViewerComparator()
            {
                @Override
                public int compare(org.eclipse.jface.viewers.Viewer v, Object e1, Object e2)
                {
                    if (!(e1 instanceof ReferenceRow r1) || !(e2 instanceof ReferenceRow r2))
                        return 0;
                    int cmp = compareStr(r1.project, r2.project);
                    if (cmp != 0)
                        return cmp;
                    cmp = compareStr(r1.module, r2.module);
                    if (cmp != 0)
                        return cmp;
                    cmp = compareStr(r1.fileName, r2.fileName);
                    if (cmp != 0)
                        return cmp;
                    return Integer.compare(r1.line, r2.line);
                }
            });
            viewer.setInput(rows);

            interaction = new FormTableInteraction(table, viewer,
                (item, col) -> cellText(item != null ? item.getData() : null, col));
            interaction.setFilterTextResolver(ReferenceTablePane::cellText);
            interaction.setColumnReorderEnabled(true);
            interaction.setOwnerDrawColumns(textColumn);
            FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);
            interaction.install(
                FormTableColumnState.hasSavedColumnWidths(settings, KEY_COLUMNS_FILL, WIDTH_KEYS));
            interaction.enableHeaderSort();
            BslOccurrenceContextResolver.applyColumnHeaderTooltips(interaction, parentColumn,
                parentTypeColumn, syntaxColumn);

            contextResolver = new OccurrenceContextResolveJob(table, viewer, parentTypeColumn,
                PARENT_TYPE_TITLE, TYPE_AUTO_THRESHOLD, this::refreshComputeTypesAction);
            contextResolver.trackViewportScrolling();
            installComputeTypesAction();
        }

        /** Колонка «Текст» — строка кода с подсветкой вхождения (общая с окном «Рефакторинг»). */
        private TableColumn addTextColumn(TableColumnLayout columnLayout, IDialogSettings settings, int index)
        {
            TableColumn column = OccurrenceContextResolveJob.addTextColumn(viewer).getColumn();
            int width = FormTableColumnState.readWidth(settings, WIDTH_KEYS[index], DEFAULT_WIDTHS[index],
                MIN_COLUMN_WIDTH);
            columnLayout.setColumnData(column, new ColumnPixelData(width, true, false));
            return column;
        }

        private TableColumn addColumn(TableColumnLayout columnLayout, IDialogSettings settings, int index,
            String title, java.util.function.Function<ReferenceRow, String> text)
        {
            TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
            column.getColumn().setText(title);
            column.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    if (!(element instanceof ReferenceRow row))
                        return ""; //$NON-NLS-1$
                    String value = text.apply(row);
                    return value != null ? value : ""; //$NON-NLS-1$
                }
            });
            int width = FormTableColumnState.readWidth(settings, WIDTH_KEYS[index], DEFAULT_WIDTHS[index],
                MIN_COLUMN_WIDTH);
            columnLayout.setColumnData(column.getColumn(), new ColumnPixelData(width, true, false));
            return column.getColumn();
        }

        /**
         * Кнопка «Рассчитать типы родителей» в тулбаре панели «Поиск»: досчитывает «Тип родителя»
         * для строк вне видимой области (при выборке больше {@link #TYPE_AUTO_THRESHOLD} авторасчёт
         * идёт только по видимой области — тяжёлая колонка).
         */
        private void installComputeTypesAction()
        {
            if (!(Global.invoke(page, "getSite") instanceof org.eclipse.ui.part.IPageSite site)) //$NON-NLS-1$
                return;
            actionBars = site.getActionBars();
            if (actionBars == null || actionBars.getToolBarManager() == null)
                return;
            computeTypesAction = new org.eclipse.jface.action.Action("Рассчитать типы") //$NON-NLS-1$
            {
                @Override
                public void run()
                {
                    contextResolver.computeAllTypes();
                }
            };
            computeTypesAction.setId(COMPUTE_TYPES_ACTION_ID);
            computeTypesAction.setEnabled(false);
            org.eclipse.jface.action.ActionContributionItem item =
                new org.eclipse.jface.action.ActionContributionItem(computeTypesAction);
            item.setMode(org.eclipse.jface.action.ActionContributionItem.MODE_FORCE_TEXT);
            org.eclipse.jface.action.Separator separator =
                new org.eclipse.jface.action.Separator(COMPUTE_TYPES_ACTION_ID + ".sep"); //$NON-NLS-1$
            actionBars.getToolBarManager().add(separator);
            actionBars.getToolBarManager().add(item);
            actionBars.getToolBarManager().update(true);
            actionBars.updateActionBars();
            refreshComputeTypesAction();
        }

        private void refreshComputeTypesAction()
        {
            if (computeTypesAction == null || table.isDisposed())
                return;
            int n = contextResolver.deferredTypeCount();
            computeTypesAction.setEnabled(n > 0);
            computeTypesAction.setText(n > 0
                ? "Рассчитать типы (" + n + ")" //$NON-NLS-1$ //$NON-NLS-2$
                : "Рассчитать типы"); //$NON-NLS-1$
            computeTypesAction.setToolTipText(TooltipText.wrap(table, (n > 0
                ? "Досчитать колонку «Тип родителя» для остальных " + n + " вхождений (тяжёлый проход, можно остановить)" //$NON-NLS-1$ //$NON-NLS-2$
                : "Все типы родителей рассчитаны") + Global.pluginSignForTooltip())); //$NON-NLS-1$
            if (actionBars != null)
            {
                actionBars.getToolBarManager().update(true);
                actionBars.updateActionBars();
            }
        }

        private void removeComputeTypesAction()
        {
            if (computeTypesAction == null || actionBars == null || actionBars.getToolBarManager() == null)
                return;
            actionBars.getToolBarManager().remove(COMPUTE_TYPES_ACTION_ID);
            actionBars.getToolBarManager().remove(COMPUTE_TYPES_ACTION_ID + ".sep"); //$NON-NLS-1$
            actionBars.getToolBarManager().update(true);
            actionBars.updateActionBars();
            computeTypesAction = null;
        }

        /**
         * Отключить штатный поставщик содержимого дерева. Он (`ReferenceSearchResultContentProvider
         * $UIUpdater`, UI-поток) на КАЖДУЮ ссылку зовёт `viewer.add()` → `Tree.getItems()` — O(n²)
         * нативных вызовов, на 2000 вхождений это и есть подвисание на 15 сек. Свою таблицу строим
         * из `BslReferenceSearchResult.getMatchingReferences()` напрямую.
         */
        private void detachStockTree()
        {
            try
            {
                Object stock = treeViewer.getContentProvider();
                Object input = treeViewer.getInput();
                if (input instanceof org.eclipse.search.ui.ISearchResult result
                    && stock instanceof org.eclipse.search.ui.ISearchResultListener listener)
                {
                    result.removeListener(listener);
                }
                treeViewer.setContentProvider(new ITreeContentProvider()
                {
                    @Override public Object[] getElements(Object inputElement) { return NO_ELEMENTS; }
                    @Override public Object[] getChildren(Object parentElement) { return NO_ELEMENTS; }
                    @Override public Object getParent(Object element) { return null; }
                    @Override public boolean hasChildren(Object element) { return false; }
                });
            }
            catch (RuntimeException | LinkageError e)
            {
                Global.tempLog("ref-search-table", "detachStockTree: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private void wireListeners()
        {
            table.addListener(SWT.DefaultSelection, event -> openSelected());
            table.addListener(SWT.MouseDoubleClick, event -> openSelected());
            table.addDisposeListener(event ->
            {
                contextResolver.cancel();
                removeComputeTypesAction();
                BslOccurrenceContextResolver.clearCaches();
                saveColumnLayout();
                if (!host.isDisposed())
                    host.setData(HANDLED_KEY, null);
            });
        }

        // ---- Пересборка строк ----

        /**
         * Перечитывает вхождения из того же дерева результатов. Дебаунс — на случай нескольких
         * событий подряд (переоткрытие результата, {@code partActivated}); сам поиск Xtext
         * одноразовый, поэтому промежуточных перестроений во время поиска нет.
         */
        void reload()
        {
            if (table.isDisposed() || reloadScheduled)
                return;
            reloadScheduled = true;
            table.getDisplay().timerExec(DEBOUNCE_MS, () ->
            {
                reloadScheduled = false;
                if (!table.isDisposed())
                    rebuildRows();
            });
        }

        private void rebuildRows()
        {
            if (table.isDisposed())
                return;
            long t0 = System.currentTimeMillis();
            String targetName = searchTargetName();
            rows.clear();
            for (IReferenceDescription reference : matchingReferences())
            {
                ReferenceRow row = buildRow(reference);
                if (row != null)
                {
                    row.targetName = targetName;
                    rows.add(row);
                }
            }
            long tBuild = System.currentTimeMillis();
            viewer.refresh();
            long tRefresh = System.currentTimeMillis();
            contextResolver.reschedule(rows);
            Global.tempLog("ref-search-table", "rebuildRows: " + rows.size() + " строк; сбор " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (tBuild - t0) + " мс, refresh таблицы " + (tRefresh - tBuild) + " мс"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Плоский список вхождений напрямую из результата поиска (минуя дерево). */
        @SuppressWarnings("unchecked")
        private List<IReferenceDescription> matchingReferences()
        {
            Object result = treeViewer.getInput();
            if (result == null)
                result = Global.getField(page, "searchResult"); //$NON-NLS-1$
            Object list = Global.invoke(result, "getMatchingReferences"); //$NON-NLS-1$
            if (!(list instanceof List<?> raw))
                return List.of();
            List<IReferenceDescription> refs = new ArrayList<>(raw.size());
            for (Object o : raw)
            {
                if (o instanceof IReferenceDescription d)
                    refs.add(d);
            }
            return refs;
        }

        /**
         * Простое имя искомого элемента из заголовка результата поиска (последний идентификатор в
         * кавычках). Xtext на многие вхождения даёт грубый источник — по этому имени в
         * {@code BslOccurrenceContextResolver} ищется точный токен.
         */
        private String searchTargetName()
        {
            Object result = treeViewer.getInput();
            if (result == null)
                result = Global.getField(page, "searchResult"); //$NON-NLS-1$
            Object label = Global.invoke(result, "getLabel"); //$NON-NLS-1$
            Global.tempLog("ref-node", "searchTargetName label=«" + label + "»"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!(label instanceof String s))
                return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("['\"‘’«]([\\p{L}\\p{N}_]+)['\"‘’»]").matcher(s); //$NON-NLS-1$
            String last = null;
            while (m.find())
                last = m.group(1);
            return last;
        }

        private ReferenceRow buildRow(IReferenceDescription reference)
        {
            URI sourceUri = reference.getSourceEObjectUri();
            IFile file = platformFile(sourceUri);
            ReferenceRow row = new ReferenceRow(file, sourceUri, reference.getEReference(),
                reference.getIndexInList());
            row.knownRegion = fullTextSelection(reference);
            if (file != null)
            {
                row.project = file.getProject() != null ? file.getProject().getName() : ""; //$NON-NLS-1$
                row.fileName = file.getName();
                String ext = file.getFileExtension();
                row.fileType = ext != null ? ext : ""; //$NON-NLS-1$
                row.module = moduleLabel(file);
            }
            if (!row.needsContext())
                row.parentType = ""; //$NON-NLS-1$
            return row;
        }

        /** Точный диапазон для полнотекстового вхождения ({@code LabelReferenceDescription.getSelection}). */
        private static int[] fullTextSelection(IReferenceDescription reference)
        {
            Object sel = Global.invoke(reference, "getSelection"); //$NON-NLS-1$
            if (sel instanceof org.eclipse.jface.text.ITextSelection ts && ts.getOffset() >= 0
                && ts.getLength() > 0)
                return new int[] {ts.getOffset(), ts.getLength()};
            return null;
        }

        private static IFile platformFile(URI uri)
        {
            if (uri == null)
                return null;
            try
            {
                URI trimmed = uri.trimFragment();
                if (!trimmed.isPlatformResource())
                    return null;
                String path = trimmed.toPlatformString(true);
                if (path == null || path.isEmpty())
                    return null;
                IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(path));
                return file != null && file.exists() ? file : null;
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }

        private static String moduleLabel(IFile file)
        {
            if (!BslModuleMethodResolver.isBslModule(file))
                return ""; //$NON-NLS-1$
            String module = GetRef.resolveSetTextModuleName(file);
            return module != null ? module : ""; //$NON-NLS-1$
        }

        // ---- Открытие / выделение ----

        /** Открыть вхождение в редакторе: xtext-опенер по URI источника ссылки (с фрагментом). */
        private void openSelected()
        {
            ReferenceRow row = firstSelected();
            if (row == null || row.sourceUri == null)
                return;
            try
            {
                URI moduleUri = row.sourceUri.trimFragment();
                org.eclipse.xtext.resource.IResourceServiceProvider provider =
                    org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
                        .getResourceServiceProvider(moduleUri);
                Object opener = provider != null
                    ? provider.get(org.eclipse.xtext.ui.editor.IURIEditorOpener.class) : null;
                if (opener instanceof org.eclipse.xtext.ui.editor.IURIEditorOpener uriOpener)
                    uriOpener.open(row.sourceUri, true);
            }
            catch (RuntimeException | LinkageError e)
            {
                Global.tempLog("ref-search-table", "open failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private ReferenceRow firstSelected()
        {
            for (Object element : viewer.getStructuredSelection().toList())
            {
                if (element instanceof ReferenceRow row)
                    return row;
            }
            return null;
        }

        // ---- Прочее ----

        private static String cellText(Object element, int column)
        {
            if (!(element instanceof ReferenceRow row))
                return ""; //$NON-NLS-1$
            return switch (column)
            {
                case 0 -> row.project;
                case 1 -> row.fileName;
                case 2 -> row.fileType;
                case 3 -> row.module;
                case 4 -> row.method;
                case 5 -> row.parent;
                case 6 -> row.parentType != null ? row.parentType : UNKNOWN_TYPE;
                case 7 -> row.syntaxKind;
                case 8 -> row.line > 0 ? String.valueOf(row.line) : ""; //$NON-NLS-1$
                case 9 -> row.lineText;
                default -> ""; //$NON-NLS-1$
            };
        }

        private static int compareStr(String a, String b)
        {
            return String.CASE_INSENSITIVE_ORDER.compare(a != null ? a : "", b != null ? b : ""); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void saveColumnLayout()
        {
            if (table.isDisposed())
                return;
            TableColumn[] columns = table.getColumns();
            if (columns.length != WIDTH_KEYS.length)
                return;
            FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER, KEY_COLUMNS_FILL,
                interaction != null && interaction.isColumnsExactFill(), WIDTH_KEYS, columns, table);
        }

        private static IDialogSettings dialogSettings()
        {
            IDialogSettings root = Activator.getDefault().getDialogSettings();
            IDialogSettings section = root.getSection(SETTINGS_SECTION);
            return section != null ? section : root.addNewSection(SETTINGS_SECTION);
        }
    }
}
