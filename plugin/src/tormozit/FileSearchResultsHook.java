package tormozit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DecoratingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.IOpenListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.search.internal.ui.text.FileMatch;
import org.eclipse.search.internal.ui.text.FileSearchQuery;
import org.eclipse.search.internal.ui.text.LineElement;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.search.ui.text.AbstractTextSearchResult;
import org.eclipse.search.ui.text.AbstractTextSearchViewPage;
import org.eclipse.search.ui.text.FileTextSearchScope;
import org.eclipse.search.ui.text.Match;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.MenuItem;
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
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

public final class FileSearchResultsHook implements IStartup
{
    private static final String SEARCH_VIEW_ID = "org.eclipse.search.ui.views.SearchView";
    private static final String PAGE_CLASS_MARKER = "FileSearchPage";
    private static final String HOOKED_KEY = "tormozit.fileSearchResultsHooked";
    private static final String TREE_OPEN_HOOKED_KEY = "tormozit.fileSearchTreeOpenHooked";
    private static final String REMOVE_BLOCK_KEY = "tormozit.fileSearchRemoveBlocked"; //$NON-NLS-1$
    private static final String SETTINGS_SECTION = "FileSearchResults"; //$NON-NLS-1$
    /** Второстепенные данные (положение разделителя, порядок/ширина колонок таблицы) — в
     * {@link IDialogSettings}, сохраняются при закрытии/пересоздании панели, а не живьём. */
    private static final String KEY_SASH_LEFT = "sashLeft"; //$NON-NLS-1$
    private static final String KEY_SASH_RIGHT = "sashRight"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
    private static final String KEY_COL_FILL_MODE = "colFillMode"; //$NON-NLS-1$
    private static final String KEY_COL_PATH_WIDTH = "colPathWidth"; //$NON-NLS-1$
    private static final String KEY_COL_FILE_WIDTH = "colFileWidth"; //$NON-NLS-1$
    private static final String KEY_COL_TYPE_WIDTH = "colTypeWidth"; //$NON-NLS-1$
    private static final String KEY_COL_LINE_WIDTH = "colLineWidth"; //$NON-NLS-1$
    private static final String KEY_COL_TEXT_WIDTH = "colTextWidth"; //$NON-NLS-1$

    /** Как в {@code org.eclipse.ui.actions.OpenWithMenu}: не переиспользовать чужой редактор по тому же input. */
    private static final int OPEN_WITH_MATCH =
        IWorkbenchPage.MATCH_INPUT | IWorkbenchPage.MATCH_ID | IWorkbenchPage.MATCH_IGNORE_SIZE;

    private static volatile boolean searchQueryRunning;
    /** Пока true — сразу переводим выделение с первого листа на корневую строку (как ConfigSearch). */
    private static volatile boolean guardFirstRootSelection;
    private static volatile boolean searchCoversMultipleProjects;
    /** Минимальный интервал пересборки таблицы, пока поиск ещё идёт. */
    private static final int TABLE_REFRESH_WHILE_SEARCHING_MS = 500;
    private static volatile long lastTableRefreshWhileSearchingMs;

    private static final Map<TableViewer, TableColumn> TABLE_COLUMNS_BY_VIEWER = new IdentityHashMap<>();
    private static TableViewer cachedResultTableViewer;
    private static FormTableInteraction cachedTableInteraction;
    private static TableColumn cachedPathColumn;
    private static TableColumn cachedFileColumn;
    private static TableColumn cachedTypeColumn;
    private static TableColumn cachedLineColumn;
    private static TableColumn cachedTextColumn;

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

            NewSearchUI.addQueryListener(new IQueryListener()
            {
                @Override public void queryAdded(ISearchQuery query)
                {
                    // Только поиск по файлам — иначе guard залипает после поиска по конфигурации
                    // и каждый клик в дереве FileSearch редиректит на корень.
                    if (!(query instanceof FileSearchQuery))
                        return;
                    // История/повторный показ без Dispose панели — сначала зафиксировать текущие ширины.
                    saveResultColumnStateOnUiThread();
                    searchCoversMultipleProjects = computeCoversMultipleProjects(query);
                    // Переключение из истории не даёт queryStarting — поднимаем guard сами.
                    if (!searchQueryRunning)
                        guardFirstRootSelection = true;
                    onQueryEvent();
                }
                @Override public void queryRemoved(ISearchQuery query)    {}
                @Override public void queryStarting(ISearchQuery query)
                {
                    if (!(query instanceof FileSearchQuery))
                        return;
                    // Панель не закрывается — Dispose не вызовется; сохранить ширины до новых результатов.
                    saveResultColumnStateOnUiThread();
                    searchQueryRunning = true;
                    guardFirstRootSelection = true;
                    lastTableRefreshWhileSearchingMs = 0L;
                    searchCoversMultipleProjects = computeCoversMultipleProjects(query);
                    // Не ждём queryFinished: штат уже выделяет первый лист при появлении
                    // результатов — наш watch/redirect должен стартовать сразу.
                    onQueryEvent();
                }
                @Override public void queryFinished(ISearchQuery query)
                {
                    if (!(query instanceof FileSearchQuery))
                        return;
                    searchQueryRunning = false;
                    searchCoversMultipleProjects = computeCoversMultipleProjects(query);
                    onQueryEvent();
                }
            });
        });
    }

    /**
     * {@code true}, если поиск реально охватывает больше одного проекта — тогда второй/третий
     * корень дерева результатов (отдельный проект) может появиться значительно позже первого, и
     * включать {@link TreeExpander#notifyContentLoaded} по первому же появившемуся корню
     * преждевременно (тот же случай, что для {@code ConfigSearchResultsHook.startFirstRootWatch}).
     * <p>
     * Без отбора по проекту (область «Рабочая область») {@code getRoots()} возвращает не список
     * проектов, а один элемент — сам {@code IWorkspaceRoot} (декомпиляция
     * {@code FileTextSearchScope.newWorkspaceScope()}), у которого {@code getProject()==null} —
     * поэтому такой корень считается отдельно, через число проектов в рабочей области.
     */
    private static boolean computeCoversMultipleProjects(ISearchQuery query)
    {
        if (!(query instanceof FileSearchQuery fsq))
            return false;
        FileTextSearchScope scope = fsq.getSearchScope();
        IResource[] roots = scope != null ? scope.getRoots() : null;
        if (roots == null)
            return false;
        Set<IProject> projects = new HashSet<>();
        for (IResource root : roots)
        {
            if (root == null)
                continue;
            if (root instanceof org.eclipse.core.resources.IWorkspaceRoot workspaceRoot)
            {
                for (IProject project : workspaceRoot.getProjects())
                    projects.add(project);
            }
            else if (root.getProject() != null)
                projects.add(root.getProject());
        }
        boolean multi = projects.size() > 1;
        log("computeCoversMultipleProjects: " + multi + " (" + projects.size() + ")");
        return multi;
    }

    private static void onQueryEvent()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            IViewPart view = findSearchViewPart();
            if (view != null)
            {
                schedulePatch(view, 0);
                startFirstRootWatch(view, 0);
            }
        });
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
                    schedulePatch(view, 0);
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
        if (!(part instanceof IViewPart vp))
            return false;
        return vp.getViewSite() != null && SEARCH_VIEW_ID.equals(vp.getViewSite().getId());
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

    private static void schedulePatch(IViewPart view, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 150;
        display.timerExec(delay, () -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (!tryPatch(view) && attempt < 40)
                schedulePatch(view, attempt + 1);
        });
    }

    private static boolean tryPatch(IViewPart view)
    {
        try
        {
            if (!(view instanceof ISearchResultViewPart))
                return false;
            ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
            if (activePage == null)
                return false;
            if (!activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
                return true;

            Object viewerObj = Global.getField(activePage, "fViewer");
            if (!(viewerObj instanceof TreeViewer treeViewer))
            {
                Global.invoke(activePage, "setLayout",
                    Integer.valueOf(AbstractTextSearchViewPage.FLAG_LAYOUT_TREE));
                return false;
            }

            Tree tree = treeViewer.getTree();
            if (tree == null || tree.isDisposed())
                return false;

            if (tree.getData(HOOKED_KEY) != null)
            {
                reinstallHandlers(activePage, view);
                // EDT мог сменить label provider на новом поиске — вернуть decorating + счётчики.
                installFileTreeMatchCount(treeViewer);
                return true;
            }

            Object viewerContainerObj = Global.getField(activePage, "fViewerContainer");
            if (!(viewerContainerObj instanceof Composite viewerContainer) || viewerContainer.isDisposed())
                return false;

            installSplitLayout(treeViewer, viewerContainer, activePage, view);
            CreateDebuggerBreakpoints.installToolbarAction(view);

            tree.setData(HOOKED_KEY, Boolean.TRUE);
            log("tryPatch: OK");
            return true;
        }
        catch (Exception e)
        {
            log("tryPatch EXCEPTION: " + e);
            return false;
        }
    }

    /**
     * {@code activePage} ({@code AbstractTextSearchViewPage}) — трёхуровневая структура виджетов
     * (декомпиляция/исходники, {@code .tmp/bundles/ui-workbench-source/org/eclipse/ui/part/PageBook.java}
     * + {@code .tmp/bundles/search-ui-source/.../AbstractTextSearchViewPage.java}):
     * СНАРУЖИ {@code SearchView} — сам {@code PageBookView} — держит СВОЙ {@code book}
     * ({@code org.eclipse.ui.part.PageBook}), переключающий МЕЖДУ страницами результатов целиком
     * ({@code book.showPage(activePage.getControl())}, где {@code getControl()} возвращает
     * {@code fPagebook}); ВНУТРИ страницы — сам {@code fPagebook} (тоже {@code PageBook}), переключающий
     * между "идёт поиск" ({@code fBusyLabel}) и результатами ({@code fViewerContainer} — простой
     * {@code Composite} с {@code FillLayout}, единственный потомок — сам {@code Tree}/{@code Table}).
     *
     * <p>{@code PageBook.showPage(Control page)} молча выходит (no-op), если
     * {@code page.getParent() != this} (сам класс, не переопределяем) — т.е. КАЖДЫЙ уровень PageBook
     * работает, только если контрол, которым он управляет, остаётся его НЕПОСРЕДСТВЕННЫМ потомком.
     * Прежняя версия хука репарентила САМ {@code fPagebook} (= {@code activePage.getControl()},
     * контрол, которым управляет ВНЕШНИЙ {@code book} панели поиска) внутрь новой {@code SashForm} —
     * после этого {@code fPagebook.getParent() != book}, и последующие переключения СТРАНИЦ результатов
     * (смена вида поиска через историю/новый поиск другого типа) переставали показывать/скрывать её
     * корректно: внешний {@code book} у СЕБЯ дома молча не находил {@code fPagebook} среди прямых
     * потомков и не мог ни показать её снова, ни (что менее заметно) гарантированно скрыть при уходе —
     * отсюда репорт «показываются элементы управления от поиска по конфигурации» при возврате
     * к результатам поиска по файлам из истории: СВОЙ (файловый) контрол так и оставался скрыт,
     * а предыдущая (конфигурационная) страница технически так и оставалась «текущей» у внешнего book.
     *
     * <p>Правильная точка врезки — {@code fViewerContainer} (аналог {@code pageContainer} в
     * {@link ConfigSearchResultsHook#installMatchTableSplitPane}): её родитель — ВНУТРЕННИЙ
     * {@code fPagebook}, а сам {@code fPagebook} — контрол, который трекает ВНЕШНИЙ {@code book},
     * НИКОГДА не трогаем. {@code SashForm} создаётся ВНУТРИ {@code fViewerContainer},
     * дерево (единственный текущий потомок) переносится в неё — оба уровня {@code PageBook.showPage()}
     * продолжают работать как раньше, ручной вызов {@code showPage} через рефлексию был нужен только
     * из-за ошибочного репарентинга и теперь не требуется.
     */
    private static void installSplitLayout(TreeViewer treeViewer, Composite viewerContainer, Object page, IViewPart view)
    {
        Tree tree = treeViewer.getTree();
        log("installSplitLayout: tree.parent=" + tree.getParent()
            + " viewerContainer=" + viewerContainer + " viewerContainer.children=" + viewerContainer.getChildren().length);

        Control[] children = viewerContainer.getChildren();
        if (children.length != 1)
        {
            log("installSplitLayout: неожиданное число потомков viewerContainer=" + children.length);
            return;
        }
        Control nativeTree = children[0];

        // Create SashForm INSIDE viewerContainer — viewerContainer сам никуда не переносится
        // (см. javadoc метода), только его единственный потомок (дерево) переносится в SashForm.
        SashForm sashForm = new SashForm(viewerContainer, SWT.HORIZONTAL);
        sashForm.setSashWidth(3);
        nativeTree.setParent(sashForm);

        // Create the table on the right side
        TableViewer tableViewer = createResultTable(sashForm);
        cachedResultTableViewer = tableViewer;
        registerOpenHandler(tableViewer, treeViewer, page);
        registerContextMenu(tableViewer, treeViewer, page);
        registerTreeContextMenu(treeViewer, page);
        blockRemoveMatches(page, treeViewer, tableViewer);

        IDialogSettings sashSettings = dialogSettings();
        sashForm.setWeights(new int[] {
            FormTableColumnState.readWidth(sashSettings, KEY_SASH_LEFT, 60, 1),
            FormTableColumnState.readWidth(sashSettings, KEY_SASH_RIGHT, 40, 1)
        });

        viewerContainer.layout(true, true);
        sashForm.layout();

        // Второстепенные данные — сохраняем при закрытии/пересоздании панели, не живьём на резайз.
        sashForm.addDisposeListener(e ->
        {
            int[] w = sashForm.getWeights();
            if (w.length == 2)
            {
                IDialogSettings settings = dialogSettings();
                settings.put(KEY_SASH_LEFT, w[0]);
                settings.put(KEY_SASH_RIGHT, w[1]);
            }
        });

        treeViewer.addPostSelectionChangedListener(event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            // EDT при появлении результатов спускается к первому листу — сразу на корень,
            // не дожидаясь конца поиска и таймера watch (как ConfigSearchResultsHook).
            if (guardFirstRootSelection)
            {
                if (redirectSelectionToFirstRoot(treeViewer))
                {
                    updateTableFromSelection(treeViewer, tableViewer);
                    // После окончания поиска один редирект достаточен — иначе guard
                    // залипает и каждый клик снова активирует корень + пересчёт декораций.
                    if (!searchQueryRunning)
                        guardFirstRootSelection = false;
                    return;
                }
                if (!searchQueryRunning)
                    guardFirstRootSelection = false;
            }
            updateTableFromSelection(treeViewer, tableViewer);
        });

        if (!searchQueryRunning)
            updateTableFromSelection(treeViewer, tableViewer);

        installFileTreeMatchCount(treeViewer);
        TreeExpander.installWhitelisted(
                TreeExpander.Target.SEARCH_FILES, treeViewer);

        log("installSplitLayout: done");
    }

    private static TableViewer createResultTable(Composite parent)
    {
        // FormTableInteraction (подсветка заголовка колонки) требует, чтобы прямой родитель Table
        // либо использовал TableColumnLayout, либо не имел layout вообще (resolveOverlayRoot()) —
        // обычный FillLayout не подходит (см. эталон "tableStack" в RecentPlacesView.java).
        Composite tableStack = new Composite(parent, SWT.NONE);
        tableStack.setLayout(null);

        Table table = new Table(tableStack,
            SWT.MULTI | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        tableStack.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                if (!table.isDisposed())
                    table.setBounds(tableStack.getClientArea());
            }
        });

        TableViewer tableViewer = new TableViewer(table);
        tableViewer.setContentProvider(org.eclipse.jface.viewers.ArrayContentProvider.getInstance());

        IDialogSettings columnSettings = dialogSettings();
        TableViewerColumn pathCol = new TableViewerColumn(tableViewer, SWT.LEFT);
        pathCol.getColumn().setText("Путь");
        pathCol.getColumn().setToolTipText("Путь" + Global.pluginSignForTooltip());
        pathCol.getColumn().setResizable(true);
        pathCol.getColumn().setWidth(FormTableColumnState.readWidth(columnSettings, KEY_COL_PATH_WIDTH, 180, 1));
        pathCol.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof FileSearchRow row)
                    return row.path != null ? row.path : ""; //$NON-NLS-1$
                return ""; //$NON-NLS-1$
            }
        });
        TABLE_COLUMNS_BY_VIEWER.put(tableViewer, pathCol.getColumn());

        TableViewerColumn fileCol = new TableViewerColumn(tableViewer, SWT.LEFT);
        fileCol.getColumn().setText("Файл");
        fileCol.getColumn().setToolTipText("Файл" + Global.pluginSignForTooltip());
        fileCol.getColumn().setResizable(true);
        fileCol.getColumn().setWidth(FormTableColumnState.readWidth(columnSettings, KEY_COL_FILE_WIDTH, 250, 1));
        fileCol.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof FileSearchRow row)
                    return row.file != null ? row.file : ""; //$NON-NLS-1$
                return ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn typeCol = new TableViewerColumn(tableViewer, SWT.LEFT);
        typeCol.getColumn().setText("Тип");
        typeCol.getColumn().setToolTipText("Тип" + Global.pluginSignForTooltip());
        typeCol.getColumn().setResizable(true);
        typeCol.getColumn().setWidth(FormTableColumnState.readWidth(columnSettings, KEY_COL_TYPE_WIDTH, 50, 1));
        typeCol.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof FileSearchRow row && row.iFile != null)
                {
                    String ext = row.iFile.getFileExtension();
                    return ext != null ? ext : ""; //$NON-NLS-1$
                }
                return ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn lineCol = new TableViewerColumn(tableViewer, SWT.RIGHT);
        lineCol.getColumn().setText("Номер строки");
        lineCol.getColumn().setToolTipText("Номер строки" + Global.pluginSignForTooltip());
        lineCol.getColumn().setResizable(true);
        lineCol.getColumn().setWidth(FormTableColumnState.readWidth(columnSettings, KEY_COL_LINE_WIDTH, 60, 1));
        lineCol.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof FileSearchRow row)
                    return row.lineNumber > 0 ? String.valueOf(row.lineNumber) : ""; //$NON-NLS-1$
                return ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn textCol = new TableViewerColumn(tableViewer, SWT.LEFT);
        textCol.getColumn().setText("Текст");
        textCol.getColumn().setToolTipText("Текст" + Global.pluginSignForTooltip());
        textCol.getColumn().setResizable(true);
        textCol.getColumn().setWidth(FormTableColumnState.readWidth(columnSettings, KEY_COL_TEXT_WIDTH, 300, 1));
        textCol.setLabelProvider(new SelectionAwareStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (!(element instanceof FileSearchRow row))
                    return new StyledString("");
                String text = row.text != null ? row.text : "";
                if (row.matchOffsets == null || row.matchOffsets.length == 0)
                    return new StyledString(text);
                StyledString ss = new StyledString();
                int pos = 0;
                for (int i = 0; i < row.matchOffsets.length; i++)
                {
                    int off = row.matchOffsets[i];
                    int len = row.matchLengths[i];
                    // plainStyler(), а не без стиля вообще — иначе у "голого" куска текста нет ни
                    // одного StyleRange (см. SmartMatchHighlight.plainStyler()).
                    if (off > pos)
                        ss.append(text.substring(pos, off), SmartMatchHighlight.plainStyler());
                    int end = off + len;
                    if (end > text.length()) end = text.length();
                    if (end > off)
                        ss.append(text.substring(off, end), SmartMatchHighlight.textOnlyStyler(table));
                    pos = end;
                }
                if (pos < text.length())
                    ss.append(text.substring(pos), SmartMatchHighlight.plainStyler());
                return ss;
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

        tableViewer.setComparator(new org.eclipse.jface.viewers.ViewerComparator()
        {
            @Override
            public int compare(org.eclipse.jface.viewers.Viewer viewer,
                    Object e1, Object e2)
            {
                if (!(e1 instanceof FileSearchRow r1) || !(e2 instanceof FileSearchRow r2))
                    return 0;
                int cmp = compareStrings(r1.path, r2.path);
                if (cmp != 0) return cmp;
                cmp = compareStrings(r1.file, r2.file);
                if (cmp != 0) return cmp;
                return Integer.compare(r1.lineNumber, r2.lineNumber);
            }
        });

        FormTableColumnState.loadOrder(columnSettings, KEY_COL_ORDER, table);
        boolean hasSavedColumnWidths = FormTableColumnState.hasSavedColumnWidths(columnSettings, KEY_COL_FILL_MODE,
            KEY_COL_PATH_WIDTH, KEY_COL_FILE_WIDTH, KEY_COL_TYPE_WIDTH, KEY_COL_LINE_WIDTH, KEY_COL_TEXT_WIDTH);
        FormTableInteraction interaction = new FormTableInteraction(table, tableViewer);
        interaction.setOwnerDrawColumns(textCol.getColumn());
        interaction.install(hasSavedColumnWidths);
        interaction.enableHeaderSort();
        cachedTableInteraction = interaction;
        cachedPathColumn = pathCol.getColumn();
        cachedFileColumn = fileCol.getColumn();
        cachedTypeColumn = typeCol.getColumn();
        cachedLineColumn = lineCol.getColumn();
        cachedTextColumn = textCol.getColumn();
        TableColumn pathColumn = cachedPathColumn;
        TableColumn fileColumn = cachedFileColumn;
        TableColumn typeColumn = cachedTypeColumn;
        TableColumn lineColumn = cachedLineColumn;
        TableColumn textColumn = cachedTextColumn;
        // Второстепенные данные — при закрытии панели; при повторном поиске — явно в
        // {@link #saveResultColumnStateOnUiThread} (Dispose не срабатывает, панель остаётся открытой).
        table.addDisposeListener(e ->
        {
            saveResultColumnState(table, interaction, pathColumn, fileColumn, typeColumn, lineColumn, textColumn);
            if (cachedResultTableViewer == tableViewer)
            {
                cachedResultTableViewer = null;
                cachedTableInteraction = null;
                cachedPathColumn = null;
                cachedFileColumn = null;
                cachedTypeColumn = null;
                cachedLineColumn = null;
                cachedTextColumn = null;
            }
        });

        return tableViewer;
    }

    /** Сохранить порядок/ширины колонок таблицы результатов поиска по файлам. UI-поток. */
    private static void saveResultColumnState()
    {
        TableViewer tableViewer = cachedResultTableViewer;
        if (tableViewer == null)
            return;
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        saveResultColumnState(table, cachedTableInteraction, cachedPathColumn, cachedFileColumn,
            cachedTypeColumn, cachedLineColumn, cachedTextColumn);
    }

    private static void saveResultColumnState(Table table, FormTableInteraction interaction,
            TableColumn pathColumn, TableColumn fileColumn, TableColumn typeColumn,
            TableColumn lineColumn, TableColumn textColumn)
    {
        if (table == null || table.isDisposed())
            return;
        boolean fillMode = interaction != null && interaction.isColumnsExactFill();
        FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER, KEY_COL_FILL_MODE, fillMode,
            new String[] { KEY_COL_PATH_WIDTH, KEY_COL_FILE_WIDTH, KEY_COL_TYPE_WIDTH,
                KEY_COL_LINE_WIDTH, KEY_COL_TEXT_WIDTH },
            new TableColumn[] { pathColumn, fileColumn, typeColumn, lineColumn, textColumn }, table);
    }

    /** Как {@link #saveResultColumnState()}, но безопасно из любого потока (query listener). */
    private static void saveResultColumnStateOnUiThread()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            saveResultColumnState();
        else
            display.syncExec(FileSearchResultsHook::saveResultColumnState);
    }

    private static int compareStrings(String a, String b)
    {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    }

    private static final class FileSearchRow
    {
        final String path;
        final String file;
        final int lineNumber;
        final String text;
        final IFile iFile;
        final LineElement lineElement;
        final int[] matchOffsets;
        final int[] matchLengths;

        FileSearchRow(String path, String file, int lineNumber, String text,
                IFile iFile, LineElement lineElement, int[] matchOffsets, int[] matchLengths)
        {
            this.path = path;
            this.file = file;
            this.lineNumber = lineNumber;
            this.text = text;
            this.iFile = iFile;
            this.lineElement = lineElement;
            this.matchOffsets = matchOffsets;
            this.matchLengths = matchLengths;
        }
    }

    private static void updateTableFromSelection(TreeViewer treeViewer, TableViewer tableViewer)
    {
        try
        {
            IStructuredSelection sel = treeViewer.getStructuredSelection();
            List<Object> selectedNodes = sel.toList();
            if (selectedNodes.isEmpty())
            {
                tableViewer.setInput(List.of());
                return;
            }

            ITreeContentProvider cp = (ITreeContentProvider) treeViewer.getContentProvider();
            List<FileSearchRow> rows = new ArrayList<>();
            Object resultInput = treeViewer.getInput();
            AbstractTextSearchResult searchResult = resultInput instanceof AbstractTextSearchResult r ? r : null;
            for (Object node : selectedNodes)
                collectRows(node, cp, rows, searchResult);

            rows.sort(Comparator
                .<FileSearchRow, String>comparing(r -> r.path != null ? r.path : "", String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.file != null ? r.file : "", String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(r -> r.lineNumber));

            tableViewer.setInput(rows);
        }
        catch (Exception e)
        {
            log("updateTable: " + e);
        }
    }

    private static void collectRows(Object node, ITreeContentProvider cp,
            List<FileSearchRow> out, AbstractTextSearchResult searchResult)
    {
        if (node instanceof IFile file)
        {
            Object[] children = cp.getChildren(node);
            for (Object child : children)
            {
                if (child instanceof LineElement le)
                {
                    String filePath = file.getProjectRelativePath().toString();
                    String mdPath = resolveMdPath(filePath);
                    int[] offs = new int[0], lens = new int[0];
                    if (searchResult != null)
                    {
                        List<Integer> offList = new ArrayList<>();
                        List<Integer> lenList = new ArrayList<>();
                        for (Match m : searchResult.getMatches(file))
                        {
                            if (m instanceof FileMatch fm && le.equals(fm.getLineElement()))
                            {
                                offList.add(fm.getOffset() - le.getOffset());
                                lenList.add(fm.getLength());
                            }
                        }
                        offs = offList.stream().mapToInt(Integer::intValue).toArray();
                        lens = lenList.stream().mapToInt(Integer::intValue).toArray();
                    }
                    out.add(new FileSearchRow(mdPath, filePath,
                        le.getLine(), le.getContents(), file, le, offs, lens));
                }
            }
        }
        else if (cp.hasChildren(node))
        {
            Object[] children = cp.getChildren(node);
            for (Object child : children)
                collectRows(child, cp, out, searchResult);
        }
    }

    private static String resolveMdPath(String projectRelativePath)
    {
        String fullName = GetRef.resolveFullNameOrNull(projectRelativePath);
        return fullName != null ? fullName : projectRelativePath;
    }

    private static void registerOpenHandler(TableViewer tableViewer,
            TreeViewer treeViewer, Object page)
    {
        Table table = tableViewer.getTable();
        table.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseDoubleClick(MouseEvent e)
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                openFileInEditor(tableViewer, treeViewer, page);
            }
        });
        replaceTreeOpenHandler(treeViewer, page);
    }

    private static void replaceTreeOpenHandler(TreeViewer treeViewer, Object page)
    {
        if (Boolean.TRUE.equals(treeViewer.getData(TREE_OPEN_HOOKED_KEY)))
            return;
        treeViewer.setData(TREE_OPEN_HOOKED_KEY, Boolean.TRUE);
        try
        {
            Object listenerListObj = Global.getField(treeViewer, "openListeners");
            Object[] saved = listenerListObj != null
                ? (Object[]) Global.invoke(listenerListObj, "getListeners")
                : new Object[0];
            if (listenerListObj != null)
                Global.invoke(listenerListObj, "clear");
            treeViewer.addOpenListener(event -> {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                {
                    for (Object o : saved)
                    {
                        if (o instanceof IOpenListener ol)
                            ol.open(event);
                    }
                    return;
                }
                openFileInEditorFromTree(treeViewer, page);
            });
        }
        catch (Exception e)
        {
            log("replaceTreeOpenHandler: " + e);
            treeViewer.addOpenListener(ev -> {
                if (ComfortSettings.isReplaceListFiltersEnabled())
                    openFileInEditorFromTree(treeViewer, page);
            });
        }
    }

    private static void registerContextMenu(TableViewer tableViewer,
            TreeViewer treeViewer, Object page)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        MenuManager menuManager = new MenuManager();
        menuManager.add(new Action("Открыть редактор объекта")
        {
            @Override
            public void run()
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                openSelectedRow(tableViewer, treeViewer);
            }
        });
        menuManager.add(new Action("Открыть редактор файла")
        {
            @Override
            public void run()
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                openFileInEditor(tableViewer, treeViewer, page);
            }
        });
        // "Копировать" не добавляем — FormTableInteraction.install() сама добавляет
        // "Копировать\tCtrl+C" в это же меню (активная ячейка, см. createResultTable).
        Menu menu = menuManager.createContextMenu(table);
        table.setMenu(menu);
    }

    /**
     * «Открыть редактор объекта» — раньше шло через штатный {@code page.showMatch(...)}
     * ({@code AbstractTextSearchViewPage}), который внутри вызывает {@code EditorOpener
     * .openAndSelect()}: если включена глобальная настройка Eclipse "Reuse editor" (Preferences →
     * General → Search), тот ищет УЖЕ ОТКРЫТЫЙ редактор для этого файла (декомпиляция) и
     * переиспользует ЕГО — даже если это простой текстовый редактор, а не объектный. Поэтому
     * команда не открывала объектный редактор, если файл уже был открыт как текст.
     * {@code OpenHelper.openEditor(IFile, ISelection)} эту логику не использует вовсе — тот же
     * вызов, что уже применяется для BSL-модулей, см. {@link #openObjectEditorForFile}.
     */
    private static void openSelectedRow(TableViewer tableViewer, TreeViewer treeViewer)
    {
        Table table = tableViewer.getTable();
        TableItem[] selection = table.getSelection();
        if (selection == null || selection.length == 0)
            return;
        Object data = selection[0].getData();
        if (!(data instanceof FileSearchRow row))
            return;
        if (row.lineElement == null || row.iFile == null)
            return;
        int[] bounds = resolveMatchBounds(row.iFile, row.lineElement, treeViewer, row);
        openObjectEditorForFile(row.iFile, bounds);
    }

    private static Match[] findMatches(TreeViewer treeViewer, IFile file, LineElement lineElement)
    {
        Object input = treeViewer.getInput();
        if (input instanceof AbstractTextSearchResult result)
        {
            Match[] allMatches = result.getMatches(file);
            if (allMatches == null)
                return null;
            List<Match> matched = new ArrayList<>();
            for (Match m : allMatches)
            {
                if (m instanceof FileMatch fm && lineElement.equals(fm.getLineElement()))
                    matched.add(fm);
            }
            return matched.toArray(new Match[0]);
        }
        return null;
    }

    private static final String TREE_MENU_ITEM_KEY = "tormozit.fileSearchTreeMenuItemAdded";
    private static final String TREE_MENU_LISTENER_KEY = "tormozit.fileSearchTreeMenuListener";

    private static void registerTreeContextMenu(TreeViewer treeViewer, Object page)
    {
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed())
            return;

        // Remove old listener if re-registering
        Listener oldListener = (Listener) tree.getData(TREE_MENU_LISTENER_KEY);
        if (oldListener != null)
            tree.removeListener(SWT.MenuDetect, oldListener);

        Listener listener = event -> {
            Menu menu = tree.getMenu();
            if (menu == null || menu.isDisposed())
            {
                menu = new Menu(tree);
                tree.setMenu(menu);
            }
            if (Boolean.TRUE.equals(menu.getData(TREE_MENU_ITEM_KEY)))
                return;
            menu.setData(TREE_MENU_ITEM_KEY, Boolean.TRUE);

            new MenuItem(menu, SWT.SEPARATOR);
            MenuItem menuItem = new MenuItem(menu, SWT.PUSH);
            menuItem.setText("Открыть редактор файла");
            menuItem.addListener(SWT.Selection, e -> openFileInEditorFromTree(treeViewer, page));
        };

        tree.setData(TREE_MENU_LISTENER_KEY, listener);
        tree.addListener(SWT.MenuDetect, listener);
    }

    private static void openFileInEditor(TableViewer tableViewer, TreeViewer treeViewer, Object page)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        TableItem[] selection = table.getSelection();
        if (selection == null || selection.length == 0)
            return;
        Object data = selection[0].getData();
        if (!(data instanceof FileSearchRow row))
            return;
        if (row.iFile == null || row.lineElement == null)
            return;

        openFileInEditorImpl(row.iFile, row.lineElement, treeViewer, page, row);
    }

    /**
     * ID встроенного в Eclipse простого текстового редактора ({@code org.eclipse.ui.editors.text.EditorsUI.DEFAULT_TEXT_EDITOR_ID}).
     * Задан литералом, чтобы не тянуть зависимость от бандла org.eclipse.ui.editors.
     */
    private static final String DEFAULT_TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor";

    /** «Открыть с помощью → Редактор XML» (org.eclipse.wst.xml.ui). */
    private static final String XML_EDITOR_ID =
        "org.eclipse.wst.xml.ui.internal.tabletree.XMLMultiPageEditorPart";

    private static final java.util.Set<String> XML_SOURCE_EXTENSIONS = java.util.Set.of(
        "form", "cmi", "xml", "mxlx", "mdo");

    private static void openFileInEditorImpl(IFile file, LineElement lineElement,
            TreeViewer treeViewer, Object page, FileSearchRow row)
    {
        if (file == null || lineElement == null)
            return;
        int[] bounds = resolveMatchBounds(file, lineElement, treeViewer, row);

        if (isBslModuleFile(file))
            openObjectEditorForFile(file, bounds);
        else
            openMatchInSourceEditor(file, bounds);
    }

    private static boolean isBslModuleFile(IFile file)
    {
        return file != null && "bsl".equalsIgnoreCase(file.getFileExtension());
    }

    private static int[] resolveMatchBounds(IFile file, LineElement lineElement,
            TreeViewer treeViewer, FileSearchRow row)
    {
        Match[] matches = findMatches(treeViewer, file, lineElement);
        if (matches != null && matches.length > 0 && matches[0] instanceof FileMatch fm)
            return new int[] { fm.getOffset(), Math.max(fm.getLength(), 1) };

        if (row != null && row.matchOffsets != null && row.matchOffsets.length > 0
            && row.matchLengths != null && row.matchLengths.length > 0)
        {
            int offset = lineElement.getOffset() + row.matchOffsets[0];
            return new int[] { offset, Math.max(row.matchLengths[0], 1) };
        }

        if (lineElement.getLine() > 0)
            return new int[] { lineElement.getOffset(), 1 };

        return new int[] { 0, 1 };
    }

    /** См. {@link #openSelectedRow} — общий вызов через {@code OpenHelper}, для любого типа файла. */
    private static void openObjectEditorForFile(IFile file, int[] bounds)
    {
        IWorkbenchPage wbPage = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage();
        if (wbPage == null)
            return;
        ITextSelection selection = new TextSelection(bounds[0], bounds[1]);
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper");
            Object helper = cls.getConstructor(IWorkbenchPage.class).newInstance(wbPage);
            for (java.lang.reflect.Method m : cls.getMethods())
            {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 2)
                    continue;
                if (m.getParameterTypes()[0].equals(IFile.class)
                    && m.getParameterTypes()[1].equals(ISelection.class))
                {
                    Object editorPart = m.invoke(helper, file, selection);
                    if (editorPart instanceof IEditorPart ep)
                        revealMatchInEditor(ep, bounds[0], bounds[1]);
                    return;
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static String resolveSourceEditorId(IFile file)
    {
        if (file == null)
            return DEFAULT_TEXT_EDITOR_ID;
        String ext = file.getFileExtension();
        if (ext != null && XML_SOURCE_EXTENSIONS.contains(ext.toLowerCase(java.util.Locale.ROOT)))
            return XML_EDITOR_ID;
        return DEFAULT_TEXT_EDITOR_ID;
    }

    private static void openMatchInSourceEditor(IFile file, int[] bounds)
    {
        IWorkbenchPage wbPage = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage();
        if (wbPage == null)
            return;
        String editorId = resolveSourceEditorId(file);
        IEditorInput input = new FileEditorInput(file);
        IEditorPart editor;
        try
        {
            editor = wbPage.openEditor(input, editorId, true, OPEN_WITH_MATCH);
        }
        catch (PartInitException e)
        {
            return;
        }
        revealMatchInEditor(editor, bounds[0], bounds[1]);
    }

    private static void revealMatchInEditor(IEditorPart editor, int offset, int length)
    {
        ITextEditor textEditor = findTextEditorInEditor(editor);
        if (textEditor == null)
            return;
        Display.getDefault().asyncExec(() -> {
            if (textEditor.getSite() != null
                && textEditor.getSite().getShell() != null
                && !textEditor.getSite().getShell().isDisposed())
            {
                textEditor.selectAndReveal(offset, length);
                Object widgetObj = textEditor.getAdapter(Control.class);
                if (widgetObj instanceof StyledText widget)
                    SearchMatchScrollSupport.applyLeftmost(widget, offset, offset + Math.max(0, length));
            }
        });
    }

    private static ITextEditor findTextEditorInEditor(IEditorPart editor)
    {
        if (editor == null)
            return null;
        if (editor instanceof ITextEditor te)
            return te;
        ITextEditor fromBsl = extractXtextEditorViaReflection(editor);
        if (fromBsl != null)
            return fromBsl;
        Object countObj = Global.invoke(editor, "getPageCount");
        if (!(countObj instanceof Integer count))
            return null;
        for (int i = 0; i < count; i++)
        {
            Object pageObj = Global.invoke(editor, "getEditor", Integer.valueOf(i));
            if (pageObj instanceof IEditorPart pageEditor)
            {
                ITextEditor nested = findTextEditorInEditor(pageEditor);
                if (nested != null)
                    return nested;
            }
            else if (pageObj instanceof ITextEditor te)
                return te;
        }
        return null;
    }

    private static ITextEditor extractXtextEditorViaReflection(IEditorPart editor)
    {
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil");
            java.lang.reflect.Method method = cls.getMethod("extractXtextEditor", IEditorPart.class);
            Object result = method.invoke(null, editor);
            if (result instanceof ITextEditor te)
                return te;
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static void openFileInEditorFromTree(TreeViewer treeViewer, Object page)
    {
        IStructuredSelection selection = treeViewer.getStructuredSelection();
        Object first = selection.getFirstElement();
        if (first == null)
            return;

        ITreeContentProvider cp = (ITreeContentProvider) treeViewer.getContentProvider();

        if (first instanceof IFile file)
        {
            LineElement le = findFirstLineElement(file, cp);
            if (le != null)
                openFileInEditorImpl(file, le, treeViewer, page, null);
        }
        else if (first instanceof LineElement le)
        {
            IFile file = findFileForLineElement(le, treeViewer);
            if (file != null)
                openFileInEditorImpl(file, le, treeViewer, page, null);
        }
    }

    private static LineElement findFirstLineElement(IFile file, ITreeContentProvider cp)
    {
        Object[] children = cp.getChildren(file);
        if (children == null)
            return null;
        for (Object child : children)
        {
            if (child instanceof LineElement le)
                return le;
        }
        return null;
    }

    private static IFile findFileForLineElement(LineElement lineElement, TreeViewer treeViewer)
    {
        ITreeContentProvider cp = (ITreeContentProvider) treeViewer.getContentProvider();
        Object input = treeViewer.getInput();
        if (cp.hasChildren(input))
            return findFileForLineElementRec(lineElement, cp, cp.getChildren(input));
        return null;
    }

    private static IFile findFileForLineElementRec(LineElement lineElement,
            ITreeContentProvider cp, Object[] nodes)
    {
        if (nodes == null)
            return null;
        for (Object node : nodes)
        {
            if (node instanceof IFile file)
            {
                Object[] fileChildren = cp.getChildren(file);
                if (fileChildren != null)
                {
                    for (Object child : fileChildren)
                    {
                        if (lineElement.equals(child))
                            return file;
                    }
                }
            }
            else if (cp.hasChildren(node))
            {
                IFile found = findFileForLineElementRec(lineElement, cp, cp.getChildren(node));
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /**
     * Пока идёт поиск / активен guard — как только в дереве есть первая строка, выделяем корень
     * (а не штатный первый лист) и обновляем таблицу. Раньше ждали {@code queryFinished}, из‑за
     * чего между активацией листа и нашего корня была заметная пауза на всё время поиска.
     * Паттерн — {@code ConfigSearchResultsHook.startFirstRootWatch}.
     */
    private static void startFirstRootWatch(IViewPart view, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            guardFirstRootSelection = false;
            return;
        }
        if (!searchQueryRunning && !guardFirstRootSelection)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        // Первый тик без паузы; дальше опрос корня раз в 100 мс.
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
            {
                guardFirstRootSelection = false;
                return;
            }
            if (!searchQueryRunning && !guardFirstRootSelection)
                return;
            if (!(view instanceof ISearchResultViewPart))
            {
                guardFirstRootSelection = false;
                return;
            }
            ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
            if (activePage == null || !activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
            {
                if (searchQueryRunning || attempt < 80)
                    startFirstRootWatch(view, attempt + 1);
                else
                    guardFirstRootSelection = false;
                return;
            }
            Object viewerObj = Global.getField(activePage, "fViewer");
            if (!(viewerObj instanceof TreeViewer tv))
            {
                if (searchQueryRunning || attempt < 80)
                    startFirstRootWatch(view, attempt + 1);
                else
                    guardFirstRootSelection = false;
                return;
            }
            Tree tree = tv.getTree();
            if (tree == null || tree.isDisposed() || tree.getItemCount() == 0
                || tree.getData(HOOKED_KEY) == null)
            {
                if (searchQueryRunning || attempt < 80)
                    startFirstRootWatch(view, attempt + 1);
                else
                    guardFirstRootSelection = false;
                return;
            }

            Object firstRoot = tree.getItem(0).getData();
            if (firstRoot == null)
            {
                if (searchQueryRunning || attempt < 80)
                    startFirstRootWatch(view, attempt + 1);
                else
                    guardFirstRootSelection = false;
                return;
            }

            Object current = tv.getStructuredSelection().getFirstElement();
            boolean redirected = false;
            if (!firstRoot.equals(current))
            {
                log("watchFirstRoot: " + current + " -> " + firstRoot);
                tv.setSelection(new StructuredSelection(firstRoot), true);
                // reveal у setSelection развернул путь до терминального узла — сбрасываем
                // и разворачиваем по своим правилам (как selectFirstTreeResult раньше).
                TreeExpander.resetExpansionAfterReveal(tv, !searchCoversMultipleProjects);
                redirected = true;
            }
            else if (!searchCoversMultipleProjects && attempt == 0)
            {
                // Первый тик уже на корне — применяем правила разворота один раз.
                TreeExpander.notifyContentLoaded(tv);
            }

            TableViewer tableViewer = cachedResultTableViewer;
            // Таблицу: сразу при редиректе/старте/финише; во время поиска — не чаще 500 мс.
            long now = System.currentTimeMillis();
            boolean dueWhileSearching = searchQueryRunning
                && (now - lastTableRefreshWhileSearchingMs >= TABLE_REFRESH_WHILE_SEARCHING_MS);
            if (tableViewer != null && !tableViewer.getTable().isDisposed()
                && (redirected || attempt == 0 || !searchQueryRunning || dueWhileSearching))
            {
                updateTableFromSelection(tv, tableViewer);
                if (searchQueryRunning)
                    lastTableRefreshWhileSearchingMs = now;
            }

            if (searchQueryRunning)
            {
                startFirstRootWatch(view, attempt + 1);
                return;
            }
            if (!guardFirstRootSelection)
                return;
            if (firstRoot.equals(tv.getStructuredSelection().getFirstElement()))
                guardFirstRootSelection = false;
            else if (attempt < 60)
                startFirstRootWatch(view, attempt + 1);
            else
                guardFirstRootSelection = false;
        });
    }

    /**
     * @return {@code true}, если выделение сменили на первую корневую строку
     */
    private static boolean redirectSelectionToFirstRoot(TreeViewer treeViewer)
    {
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getItemCount() == 0)
            return false;
        Object firstRoot = tree.getItem(0).getData();
        if (firstRoot == null)
            return false;
        Object current = treeViewer.getStructuredSelection().getFirstElement();
        if (firstRoot.equals(current))
            return false;
        log("redirectToFirstRoot: " + current + " -> " + firstRoot);
        treeViewer.setSelection(new StructuredSelection(firstRoot), true);
        TreeExpander.resetExpansionAfterReveal(treeViewer, !searchCoversMultipleProjects);
        return true;
    }

    private static void reinstallHandlers(Object page, IViewPart view)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        TableViewer tv = cachedResultTableViewer;
        if (tv == null || tv.getTable() == null || tv.getTable().isDisposed())
            return;

        ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
        if (activePage != null && activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
        {
            Object viewerObj = Global.getField(activePage, "fViewer");
            if (viewerObj instanceof TreeViewer treeViewer)
            {
                registerTreeContextMenu(treeViewer, activePage);
                blockRemoveMatches(activePage, treeViewer, tv);
            }
        }
    }

    /**
     * Штатный {@code FileSearchPage} вешает Delete на {@code org.eclipse.ui.edit.delete} →
     * {@code RemoveSelectedMatchesAction}: при выделении корня/папки снимаются все совпадения
     * под узлом — панель почти опустошается. В поиске по конфигурации Delete ничего не делает;
     * здесь повторяем то же: гасим действие удаления совпадений и глотаем клавишу.
     */
    private static void blockRemoveMatches(Object page, TreeViewer treeViewer, TableViewer tableViewer)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Tree tree = treeViewer.getTree();
        Table table = tableViewer.getTable();
        if (tree == null || tree.isDisposed() || table == null || table.isDisposed())
            return;

        disableRemoveMatchActions(page);

        if (Boolean.TRUE.equals(tree.getData(REMOVE_BLOCK_KEY)))
            return;
        tree.setData(REMOVE_BLOCK_KEY, Boolean.TRUE);

        // Снять глобальный handler Delete со страницы (иначе ActionBars всё ещё зовут remove).
        if (page instanceof AbstractTextSearchViewPage searchPage)
        {
            var site = searchPage.getSite();
            if (site != null)
            {
                var bars = site.getActionBars();
                if (bars != null)
                {
                    bars.setGlobalActionHandler(
                        org.eclipse.ui.actions.ActionFactory.DELETE.getId(), null);
                    bars.updateActionBars();
                }
            }
        }

        // Штатный SelectionChangedListener снова включает fRemoveSelectedMatches — гасим после него.
        treeViewer.addPostSelectionChangedListener(e -> {
            if (ComfortSettings.isReplaceListFiltersEnabled())
                disableRemoveMatchActions(page);
        });
        tableViewer.addSelectionChangedListener(e -> {
            if (ComfortSettings.isReplaceListFiltersEnabled())
                disableRemoveMatchActions(page);
        });

        Listener blockDel = e -> {
            if (e.keyCode != SWT.DEL)
                return;
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            e.doit = false;
            disableRemoveMatchActions(page);
        };
        tree.addListener(SWT.KeyDown, blockDel);
        table.addListener(SWT.KeyDown, blockDel);
    }

    private static void disableRemoveMatchActions(Object page)
    {
        if (page == null)
            return;
        Object removeSelected = Global.getField(page, "fRemoveSelectedMatches"); //$NON-NLS-1$
        if (removeSelected instanceof Action action)
        {
            action.setEnabled(false);
            // Иначе привязка Delete снова удалит совпадения, даже если пункт меню серый.
            if (action.getActionDefinitionId() != null)
                action.setActionDefinitionId(null);
        }
        Object removeCurrent = Global.getField(page, "fRemoveCurrentMatch"); //$NON-NLS-1$
        if (removeCurrent instanceof Action action)
            action.setEnabled(false);
    }

    private static void installFileTreeMatchCount(TreeViewer treeViewer)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        IBaseLabelProvider baseLp = treeViewer.getLabelProvider();
        if (baseLp == null)
            return;
        IStyledLabelProvider innerStyled;
        if (baseLp instanceof DecoratingStyledCellLabelProvider dscp)
        {
            IStyledLabelProvider styled = dscp.getStyledStringProvider();
            // Уже наши счётчики + штатный decorating — не переустанавливать.
            if (styled instanceof MatchCountStyledLabelProvider)
                return;
            innerStyled = styled;
        }
        else if (baseLp instanceof DelegatingStyledCellLabelProvider dscp)
            innerStyled = dscp.getStyledStringProvider();
        else if (baseLp instanceof IStyledLabelProvider slp)
            innerStyled = slp;
        else
            return;
        if (innerStyled instanceof MatchCountStyledLabelProvider mc)
            innerStyled = mc.inner;
        MatchCountStyledLabelProvider wrapper = new MatchCountStyledLabelProvider(innerStyled, treeViewer);
        treeViewer.setLabelProvider(new DecoratingStyledCellLabelProvider(
            wrapper, PlatformUI.getWorkbench().getDecoratorManager().getLabelDecorator(), null));
        treeViewer.refresh();
    }

    /**
     * {@code DelegatingStyledCellLabelProvider.getStyledStringProvider()} (JFace, декомпиляция
     * {@code .tmp/bundles/jface}) отдаёт исходный, НЕ декорированный провайдер — им изначально
     * оборачивается {@code DecoratingStyledCellLabelProvider} (стандартная обёртка Eclipse Search
     * для всех штатных декораторов, включая наш {@code MdObjectUsageDecorator}). Раньше здесь
     * подменялся весь label provider на голый {@code DelegatingStyledCellLabelProvider} без
     * декоратора — декорирование пропадало для ВСЕХ элементов дерева поиска по файлам. Ручной
     * вызов {@code ILabelDecorator.decorateText()} не помог: штатный декоратор EDT реализует
     * {@code IDelayedLabelDecorator} (асинхронно, через {@code prepareDecoration()} + отложенное
     * обновление) — простой синхронный вызов всегда возвращал недекорированный текст (подтверждено
     * логом). Поэтому здесь используется штатная {@link DecoratingStyledCellLabelProvider} —
     * она сама умеет ждать асинхронную декорацию и обновлять дерево по готовности.
     */
    private static final class MatchCountStyledLabelProvider implements IStyledLabelProvider
    {
        private final IStyledLabelProvider inner;
        private final TreeViewer treeViewer;

        MatchCountStyledLabelProvider(IStyledLabelProvider inner, TreeViewer treeViewer)
        {
            this.inner = inner;
            this.treeViewer = treeViewer;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            StyledString original = inner.getStyledText(element);
            if (!(element instanceof IResource resource) || element instanceof LineElement)
                return original;
            int count = countMatchesForResource(resource, treeViewer);
            if (count <= 0)
                return original;
            String cleanText = stripCountSuffix(original.getString());
            StyledString result = new StyledString();
            result.append(cleanText);
            result.append(" (" + count + ")");
            return result;
        }

        @Override
        public Image getImage(Object element) { return inner.getImage(element); }

        @Override
        public void addListener(ILabelProviderListener listener) { inner.addListener(listener); }

        @Override
        public void dispose() { inner.dispose(); }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return inner.isLabelProperty(element, property);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            inner.removeListener(listener);
        }
    }

    private static String stripCountSuffix(String text)
    {
        String result = text.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        result = result.replaceAll("\\s+\\d+$", "").trim();
        return result;
    }

    private static int countMatchesForResource(IResource resource, TreeViewer tv)
    {
        Object input = tv.getInput();
        if (!(input instanceof AbstractTextSearchResult result))
            return 0;
        if (resource instanceof IFile file)
            return result.getMatches(file) != null ? result.getMatches(file).length : 0;
        int total = 0;
        ITreeContentProvider cp = (ITreeContentProvider) tv.getContentProvider();
        for (Object child : cp.getChildren(resource))
        {
            if (child instanceof IFile file)
            {
                int cnt = result.getMatches(file) != null ? result.getMatches(file).length : 0;
                total += cnt;
            }
            else if (child instanceof IResource resChild)
            {
                total += countMatchesForResource(resChild, tv);
            }
        }
        return total;
    }

    /**
     * Строки, выбранные сейчас в этой странице результатов (таблица Комфорта, если в фокусе
     * и что-то выбрано — иначе штатное дерево), для тулбарной команды
     * {@link CreateDebuggerBreakpoints}. {@code null}, если эта страница сейчас не активна
     * (тулбар общий на панель — тогда пробуют другие хуки).
     */
    static List<CreateDebuggerBreakpoints.Target> currentBreakpointTargets(IViewPart view)
    {
        if (!(view instanceof ISearchResultViewPart))
            return null;
        ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
        if (activePage == null || !activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
            return null;
        Object viewerObj = Global.getField(activePage, "fViewer");
        if (!(viewerObj instanceof TreeViewer treeViewer))
            return null;

        List<FileSearchRow> rows = new ArrayList<>();
        TableViewer tableViewer = cachedResultTableViewer;
        Table table = tableViewer != null ? tableViewer.getTable() : null;
        if (table != null && !table.isDisposed() && table.getSelectionCount() > 0)
        {
            for (TableItem item : table.getSelection())
                if (item != null && item.getData() instanceof FileSearchRow row)
                    rows.add(row);
        }
        else
        {
            ITreeContentProvider cp = (ITreeContentProvider) treeViewer.getContentProvider();
            Object input = treeViewer.getInput();
            AbstractTextSearchResult searchResult = input instanceof AbstractTextSearchResult r ? r : null;
            for (Object node : treeViewer.getStructuredSelection().toList())
                collectRows(node, cp, rows, searchResult);
        }

        List<CreateDebuggerBreakpoints.Target> targets = new ArrayList<>();
        for (FileSearchRow row : rows)
            if (row.iFile != null && row.lineNumber > 0)
                targets.add(new CreateDebuggerBreakpoints.Target(row.iFile, row.lineNumber));
        return targets;
    }

    private static void log(String message)
    {
        Global.log("FileSearchResults", message);
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }
}
