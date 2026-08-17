package tormozit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.egit.core.RepositoryCache;
import org.eclipse.egit.core.RepositoryUtil;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
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
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Колонки «Файл»/«Тип»/«Путь»/«Статус» и многословный фильтр ({@link SmartMatcher}, AND по словам)
 * в таблице файлов коммита панели «История Git»
 * ({@code org.eclipse.egit.ui.internal.history.GitHistoryPage} → {@code CommitFileDiffViewer}).
 *
 * <p>Таблица файлов коммита — штатный {@code CommitFileDiffViewer} (extends {@code TableViewer}),
 * элементы — {@code FileDiff}. Таблица создаётся без заголовков и явных колонок
 * ({@code SWT.HIDE_SELECTION}), {@link org.eclipse.egit.ui.internal.history.FileDiffLabelProvider}
 * показывает {@code FileDiff.getPath()} (repo-относительный путь).
 *
 * <p>Патч добавляет:
 * <ul>
 *   <li>Заголовки + 4 колонки: «Файл» (repo-путь, штатная иконка), «Тип» (расширение), «Путь»
 *       (полное имя метаданных через {@link GetRef#resolveFullNameOrNull}) и «Статус»
 *       (Добавлен/Изменён/Удалён/Переименован/Скопирован — {@code FileDiff.getChange()},
 *       по образцу колонки «Статус» в панели «Индексирование Git», см. {@code GitStagingFilterHook}).</li>
 *   <li>Сортировка по клику на заголовок колонки (как в {@link GitStagingFilterHook}):
 *       повторный клик меняет направление; индикатор в шапке.</li>
 *   <li>{@link FormTableInteraction}: выбор ячейки, подсветка активной колонки,
 *       копирование текста ячейки (Ctrl+C / меню).</li>
 *   <li>Поле фильтра {@link FilterInputBox} ({@code SearchBox} с лупой) над таблицей
 *       (re-parent через wrapper → tableStack → columnHost)
 *       с подсказкой {@link FilterInputBox#FLAT_FILTER_TOOLTIP} и историей
 *       (штатный EDT {@code SearchBox}).</li>
 *   <li>Фильтр по склейке {@code resolveFullNameOrNull(path) + ";" + FileDiff.getPath()}
 *       ({@code null} → пустая строка) с подсветкой совпадений
 *       ({@link SmartMatchHighlight}).</li>
 *   <li>История запросов в штатном поле «Найти» ({@code FindToolbar#patternField}):
 *       кнопка ▾ и Ctrl+↓ ({@link FilterHistoryUi}), отдельно от фильтра файлов.</li>
 *   <li>Если при открытии страница пуста и нет {@code GitHistoryPage} (связь с
 *       редактором выключена, штатный {@code GenericHistoryView} не восстанавливает
 *       вход), показывается история последнего репозитория или репозитория активного
 *       проекта — иначе панель остаётся пустой без кнопок Git, в том числе без
 *       подменю выбора репозитория. Уже показанную другую страницу (локальная
 *       история) не подменяем.</li>
 * </ul>
 *
 * <p>Колонки и фильтр файлов: Параметры → Комфорт → «Улучшать списки»
 * ({@link ComfortSettings#PREF_REPLACE_LIST_FILTERS}). История поиска коммитов
 * и восстановление страницы Git ставятся всегда. Логирование: Параметры → Комфорт
 * → «Общее логирование».
 */
public final class GitHistoryHook implements IStartup
{
    private static final String TEAM_HISTORY_VIEW_ID = "org.eclipse.team.ui.GenericHistoryView"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.gitHistoryFileColumnsPatched"; //$NON-NLS-1$
    private static final String SEARCH_HISTORY_PATCHED_KEY =
        "tormozit.gitHistorySearchHistoryPatched"; //$NON-NLS-1$
    private static final String SEARCH_HISTORY_WRAP_KEY =
        "tormozit.gitHistorySearchHistoryWrap"; //$NON-NLS-1$
    private static final String FIND_TOOLBAR_CLASS =
        "org.eclipse.egit.ui.internal.history.FindToolbar"; //$NON-NLS-1$
    private static final String GIT_HISTORY_PAGE_CLASS =
        "org.eclipse.egit.ui.internal.history.GitHistoryPage"; //$NON-NLS-1$
    private static final String GIT_HISTORY_PAGE_SOURCE_CLASS =
        "org.eclipse.egit.ui.internal.history.GitHistoryPageSource"; //$NON-NLS-1$
    private static final String HISTORY_PAGE_INPUT_CLASS =
        "org.eclipse.egit.ui.internal.history.HistoryPageInput"; //$NON-NLS-1$
    private static final String SEARCH_HISTORY_SCOPE_ID = "gitHistoryCommitSearch"; //$NON-NLS-1$
    private static final String SEARCH_HISTORY_BUTTON_TOOLTIP =
        "История поиска коммитов (или Ctrl+↓ в поле)"; //$NON-NLS-1$

    /** Окна, на которые уже повешен part-listener переустановки ▾. */
    private static final Set<IWorkbenchWindow> searchHistoryWindows =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private static int findToolbarRescanGen;
    private static boolean restoringGitHistoryPage;
    private static final String SORT_STATE_KEY = "tormozit.gitHistorySortState"; //$NON-NLS-1$
    private static final String SETTINGS_SECTION = "GitHistoryFileColumns"; //$NON-NLS-1$
    private static final String KEY_LAST_REPO = "lastRepoGitDir"; //$NON-NLS-1$
    /** Второстепенные данные (положение разделителя) — в {@link IDialogSettings}, сохраняются при
     * закрытии/пересоздании панели, а не живьём при каждом драге. */
    private static final String KEY_SASH_LEFT = "sashLeft"; //$NON-NLS-1$
    private static final String KEY_SASH_RIGHT = "sashRight"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
    private static final String KEY_COL_FILL_MODE = "colFillMode"; //$NON-NLS-1$
    private static final String KEY_COL_FILE_WIDTH = "colFileWidth"; //$NON-NLS-1$
    private static final String KEY_COL_TYPE_WIDTH = "colTypeWidth"; //$NON-NLS-1$
    private static final String KEY_COL_PATH_WIDTH = "colPathWidth"; //$NON-NLS-1$
    private static final String KEY_COL_STATUS_WIDTH = "colStatusWidth"; //$NON-NLS-1$

    private static final String COLUMN_LOGICAL_KEY = "tormozit.gitHistoryColumnLogical"; //$NON-NLS-1$
    private static final int COL_FILE = 0;
    private static final int COL_TYPE = 1;
    private static final int COL_PATH = 2;
    private static final int COL_STATUS = 3;

    private static final String EGIT_UI_PLUGIN_ID = "org.eclipse.egit.ui"; //$NON-NLS-1$

    private static final String EGIT_PREF_COLUMN_AUTHOR = "HistoryView_ColumnAuthorShow"; //$NON-NLS-1$
    private static final String EGIT_PREF_COLUMN_AUTHOR_DATE = "HistoryView_ColumnAuthorDateShow"; //$NON-NLS-1$
    private static final String EGIT_PREF_COLUMN_COMMITTER = "HistoryView_ColumnCommitterShow"; //$NON-NLS-1$
    private static final String EGIT_PREF_COLUMN_COMMITTER_DATE = "HistoryView_ColumnCommitterDateShow"; //$NON-NLS-1$
    private static final String EGIT_PREF_RELATIVE_DATE = "resourcehistory_show_relative_date"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            bootstrapCommitListPrefsOnce();
            installSearchHistory(Display.getDefault());
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
            Debug.log("earlyStartup: installed"); //$NON-NLS-1$
        });
    }

    /**
     * Однократно: видимы «Автор» и «Дата фиксации»; скрыты «Коммитер» и
     * «Дата изменения автором»; «Относительные даты» выключены.
     * Дальше выбор пользователя не перезаписываем.
     */
    private static void bootstrapCommitListPrefsOnce()
    {
        if (ComfortSettings.isGitHistoryBootstrapped())
            return;
        try
        {
            IPreferenceStore egit = new ScopedPreferenceStore(
                InstanceScope.INSTANCE, EGIT_UI_PLUGIN_ID);
            egit.setValue(EGIT_PREF_COLUMN_AUTHOR, true);
            egit.setValue(EGIT_PREF_COLUMN_AUTHOR_DATE, false);
            egit.setValue(EGIT_PREF_COLUMN_COMMITTER, false);
            egit.setValue(EGIT_PREF_COLUMN_COMMITTER_DATE, true);
            egit.setValue(EGIT_PREF_RELATIVE_DATE, false);
            if (egit instanceof ScopedPreferenceStore scoped)
                scoped.save();
            ComfortSettings.setGitHistoryBootstrapped(true);
            Debug.log("bootstrapCommitListPrefsOnce: applied"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Debug.log("bootstrapCommitListPrefsOnce EXCEPTION: " + e); //$NON-NLS-1$
        }
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isHistoryView(view))
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
            @Override public void partInputChanged(IWorkbenchPartReference ref) { tryFromRef(ref); }

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isHistoryView(part))
                    schedulePatch((IViewPart) part, 0);
            }
        });
    }

    private static boolean isHistoryView(Object part)
    {
        if (!(part instanceof IViewPart view))
            return false;
        String id = view.getSite().getId();
        return TEAM_HISTORY_VIEW_ID.equals(id)
            || "org.eclipse.egit.ui.HistoryView".equals(id); //$NON-NLS-1$
    }

    private static void schedulePatch(IViewPart view, int attempt)
    {
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 150;
        display.timerExec(delay, () ->
        {
            boolean pageReady = ensureGitHistoryPage(view);
            boolean colsReady = !ComfortSettings.isReplaceListFiltersEnabled()
                || tryPatch(view);
            if ((!pageReady || !colsReady) && attempt < 20)
                schedulePatch(view, attempt + 1);
            else if (attempt >= 20)
                Debug.log("tryPatch GIVE UP after 20 attempts pageReady=" //$NON-NLS-1$
                    + pageReady + " colsReady=" + colsReady); //$NON-NLS-1$
        });
    }

    // -----------------------------------------------------------------------
    // tryPatch — основная логика патчинга
    // -----------------------------------------------------------------------

    private static boolean tryPatch(IViewPart view)
    {
        try
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return true;

            Object historyPage = Global.call(view, "getHistoryPage"); //$NON-NLS-1$
            if (historyPage == null)
            {
                Debug.log("tryPatch: getHistoryPage() returned null"); //$NON-NLS-1$
                return false;
            }
            if (!isGitHistoryPage(historyPage))
            {
                Debug.log("tryPatch: skip non-git page=" + historyPage.getClass().getName()); //$NON-NLS-1$
                return true;
            }

            Debug.log("tryPatch: historyPage=" + historyPage.getClass().getName()); //$NON-NLS-1$

            // GenericHistoryView может содержить несколько GitHistoryPage (разные репо).
            // Патчим только активную.
            if (!isGenericHistoryView(view))
                return false;

            Object fileViewerObj = Global.getField(historyPage, "fileViewer"); //$NON-NLS-1$
            if (!(fileViewerObj instanceof TableViewer fileViewer))
            {
                Debug.log("tryPatch: fileViewer=" //$NON-NLS-1$
                    + (fileViewerObj == null ? "null" : fileViewerObj.getClass().getName())); //$NON-NLS-1$
                return false;
            }

            Table table = fileViewer.getTable();
            if (table == null || table.isDisposed())
                return false;

            if (Boolean.TRUE.equals(table.getData(PATCHED_KEY)))
            {
                rememberLastRepo(historyPage);
                return true;
            }

            // Сохраняем оригинальный FileDiffLabelProvider до замены.
            CellLabelProvider origLabelProvider = null;
            IBaseLabelProvider currentLp = fileViewer.getLabelProvider();
            if (currentLp instanceof CellLabelProvider clp)
                origLabelProvider = clp;

            TableColumn[] cols = installColumns(table);
            installFilterComposite(fileViewer, table, origLabelProvider, cols[0], cols[1], cols[2], cols[3]);
            rememberLastRepo(historyPage);

            Debug.log("tryPatch: OK"); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            Debug.log("tryPatch EXCEPTION: " + e); //$NON-NLS-1$
            return false;
        }
    }

    private static boolean isGenericHistoryView(IViewPart view)
    {
        return view != null && TEAM_HISTORY_VIEW_ID.equals(view.getSite().getId());
    }

    /**
     * Штатный {@code GenericHistoryView} без связи с редактором не создаёт
     * {@code GitHistoryPage} (нет bootstrap-входа) — пустая страница без кнопок Git,
     * в том числе без подменю выбора репозитория. Показываем историю последнего
     * репозитория или репозитория активного проекта.
     * Уже показанную другую страницу (локальная история и т.п.) не трогаем:
     * клик по строке активирует панель и не должен переключать её в Git.
     *
     * @return {@code true}, если восстанавливать нечего (Git уже есть, другая
     *         страница на месте, или это не панель истории); {@code false} —
     *         повторить позже (панель ещё пуста, репозитории не готовы)
     */
    private static boolean ensureGitHistoryPage(IViewPart view)
    {
        if (!isHistoryView(view))
            return true;
        Object page = Global.call(view, "getHistoryPage"); //$NON-NLS-1$
        if (isGitHistoryPage(page))
        {
            rememberLastRepo(page);
            return true;
        }
        if (page != null)
        {
            Debug.log("ensureGitHistoryPage: skip existing page=" + page.getClass().getName()); //$NON-NLS-1$
            return true;
        }
        if (restoringGitHistoryPage)
            return false;
        Repository repo = resolveRepoToShow(view);
        if (repo == null)
        {
            Debug.log("ensureGitHistoryPage: no repository yet"); //$NON-NLS-1$
            return false;
        }
        Object input = historyPageInput(repo);
        Object pageSource = gitHistoryPageSource();
        if (input == null || pageSource == null)
            return false;
        restoringGitHistoryPage = true;
        try
        {
            Object shown = Global.invoke(view, "showHistoryPageFor", //$NON-NLS-1$
                input, Boolean.TRUE, Boolean.TRUE, pageSource);
            boolean ok = isGitHistoryPage(shown);
            Debug.log("ensureGitHistoryPage: repo=" + repo.getDirectory() //$NON-NLS-1$
                + " shown=" + (shown == null ? "null" : shown.getClass().getSimpleName()) //$NON-NLS-1$ //$NON-NLS-2$
                + " ok=" + ok); //$NON-NLS-1$
            if (ok)
                rememberLastRepo(shown);
            return ok;
        }
        catch (Exception e)
        {
            Debug.log("ensureGitHistoryPage EXCEPTION: " + e); //$NON-NLS-1$
            return false;
        }
        finally
        {
            restoringGitHistoryPage = false;
        }
    }

    private static boolean isGitHistoryPage(Object page)
    {
        return page != null && GIT_HISTORY_PAGE_CLASS.equals(page.getClass().getName());
    }

    private static Object gitHistoryPageSource()
    {
        try
        {
            return Class.forName(GIT_HISTORY_PAGE_SOURCE_CLASS).getField("INSTANCE").get(null); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Debug.log("gitHistoryPageSource: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static Object historyPageInput(Repository repo)
    {
        try
        {
            return Class.forName(HISTORY_PAGE_INPUT_CLASS)
                .getConstructor(Repository.class)
                .newInstance(repo);
        }
        catch (Exception e)
        {
            Debug.log("historyPageInput: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void rememberLastRepo(Object historyPage)
    {
        Object repoObj = Global.getField(historyPage, "currentRepo"); //$NON-NLS-1$
        if (!(repoObj instanceof Repository repo) || repo.getDirectory() == null)
            return;
        dialogSettings().put(KEY_LAST_REPO, repo.getDirectory().getAbsolutePath());
    }

    private static Repository resolveRepoToShow(IViewPart view)
    {
        Repository last = repoFromGitDir(dialogSettings().get(KEY_LAST_REPO));
        if (last != null)
            return last;

        IProject project = Global.getActiveProject(view, false);
        if (project != null)
        {
            RepositoryMapping mapping = RepositoryMapping.getMapping(project);
            if (mapping != null && mapping.getRepository() != null)
                return mapping.getRepository();
        }

        Repository[] all = RepositoryCache.INSTANCE.getAllRepositories();
        if (all != null && all.length > 0)
            return all[0];

        List<String> configured = RepositoryUtil.INSTANCE.getConfiguredRepositories();
        if (configured != null)
        {
            for (String path : configured)
            {
                Repository repo = repoFromGitDir(path);
                if (repo != null)
                    return repo;
            }
        }
        return null;
    }

    private static Repository repoFromGitDir(String path)
    {
        if (path == null || path.isBlank())
            return null;
        String absolute = RepositoryUtil.INSTANCE.getAbsoluteRepositoryPath(path);
        File dir = new File(absolute != null && !absolute.isBlank() ? absolute : path);
        Repository cached = RepositoryCache.INSTANCE.getRepository(dir);
        if (cached != null)
            return cached;
        try
        {
            if (dir.isDirectory())
                return RepositoryCache.INSTANCE.lookupRepository(dir);
        }
        catch (IOException e)
        {
            Debug.log("repoFromGitDir: " + dir + " → " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Колонки
    // -----------------------------------------------------------------------

    /** @return [0]=«Файл», [1]=«Тип», [2]=«Путь», [3]=«Статус» */
    private static TableColumn[] installColumns(Table table)
    {
        // Реальные ширины/порядок/режим заполнения выставляются и персистятся в installFilterComposite
        // (см. columnLayout.setColumnData) — здесь только временные значения до реального layout.
        TableColumn fileCol = new TableColumn(table, SWT.LEFT, 0);
        fileCol.setText("Файл"); //$NON-NLS-1$
        fileCol.setToolTipText("Путь к файлу в репозитории" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        fileCol.setResizable(true);
        fileCol.setMoveable(true);
        fileCol.setWidth(300);
        fileCol.setData(COLUMN_LOGICAL_KEY, Integer.valueOf(COL_FILE));

        TableColumn typeCol = new TableColumn(table, SWT.LEFT, 1);
        typeCol.setText("Тип"); //$NON-NLS-1$
        typeCol.setToolTipText("Расширение файла" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        typeCol.setResizable(true);
        typeCol.setMoveable(true);
        typeCol.setWidth(60);
        typeCol.setData(COLUMN_LOGICAL_KEY, Integer.valueOf(COL_TYPE));

        TableColumn pathCol = new TableColumn(table, SWT.LEFT, 2);
        pathCol.setText("Путь"); //$NON-NLS-1$
        pathCol.setToolTipText("Полное имя объекта метаданных" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        pathCol.setResizable(true);
        pathCol.setMoveable(true);
        pathCol.setWidth(250);
        pathCol.setData(COLUMN_LOGICAL_KEY, Integer.valueOf(COL_PATH));

        TableColumn statusCol = new TableColumn(table, SWT.LEFT, 3);
        statusCol.setText("Статус"); //$NON-NLS-1$
        statusCol.setToolTipText("Статус изменения файла в коммите" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        statusCol.setResizable(true);
        statusCol.setMoveable(true);
        statusCol.setWidth(90);
        statusCol.setData(COLUMN_LOGICAL_KEY, Integer.valueOf(COL_STATUS));

        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        return new TableColumn[] { fileCol, typeCol, pathCol, statusCol };
    }

    // -----------------------------------------------------------------------
    // Сортировка по клику на заголовок
    // -----------------------------------------------------------------------

    /** Состояние сортировки на одну таблицу (повторный клик — смена направления). */
    private static final class SortState
    {
        int logical = -1;
        boolean ascending = true;
    }

    /**
     * Клик по заголовку: сортировка по логической колонке (не по visual index после reorder).
     * Выделение сохраняем по значениям «Файл» — без {@link TableViewer#setSelection}
     * (VIRTUAL+hashlookup сбрасывает).
     */
    private static void installColumnSort(TableViewer viewer, Table table, TableColumn[] cols,
        FormTableInteraction[] interactionRef)
    {
        SortState state = new SortState();
        table.setData(SORT_STATE_KEY, state);
        for (TableColumn col : cols)
        {
            col.addListener(SWT.Selection, e ->
            {
                Object data = col.getData(COLUMN_LOGICAL_KEY);
                if (!(data instanceof Integer logicalObj))
                    return;
                sortBy(viewer, table, state, logicalObj.intValue(), col, interactionRef);
            });
        }
    }

    private static void sortBy(TableViewer viewer, Table table, SortState state, int logical,
        TableColumn column, FormTableInteraction[] interactionRef)
    {
        if (viewer == null || table == null || table.isDisposed() || column == null || column.isDisposed())
            return;
        state.ascending = state.logical == logical ? !state.ascending : true;
        state.logical = logical;
        applySortState(viewer, table, state, interactionRef, true);
        Debug.log("sortBy logical=" + logical + " ascending=" + state.ascending); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Повторно применяет уже выбранную пользователем сортировку (без смены направления).
     * Нужно после смены коммита: EGit подставляет новый набор {@code FileDiff}, и без
     * повторного {@code setComparator} список снова идёт в штатном порядке.
     */
    private static void applySortState(TableViewer viewer, Table table, SortState state,
        FormTableInteraction[] interactionRef, boolean restoreSelection)
    {
        if (viewer == null || table == null || table.isDisposed() || state == null || state.logical < 0)
            return;
        TableColumn column = columnByLogical(table, state.logical);
        if (column == null || column.isDisposed())
            return;
        String[] savedFiles = restoreSelection ? captureSelectedFileColumnValues(table) : new String[0];
        final int sortLogical = state.logical;
        final boolean ascending = state.ascending;
        viewer.setComparator(new ViewerComparator()
        {
            @Override
            public int compare(Viewer v, Object e1, Object e2)
            {
                int cmp = String.CASE_INSENSITIVE_ORDER.compare(
                    sortKey(e1, sortLogical), sortKey(e2, sortLogical));
                return ascending ? cmp : -cmp;
            }
        });
        table.setSortColumn(column);
        table.setSortDirection(ascending ? SWT.UP : SWT.DOWN);
        if (restoreSelection)
            selectRowsByFileColumnValues(table, savedFiles);
        FormTableInteraction interaction = interactionRef != null ? interactionRef[0] : null;
        if (interaction != null)
            interaction.resyncSelectionTheme();
    }

    private static TableColumn columnByLogical(Table table, int logical)
    {
        if (table == null || table.isDisposed())
            return null;
        for (TableColumn col : table.getColumns())
        {
            if (col == null || col.isDisposed())
                continue;
            Object data = col.getData(COLUMN_LOGICAL_KEY);
            if (data instanceof Integer value && value.intValue() == logical)
                return col;
        }
        return null;
    }

    /** Ключ сортировки — тот же текст, что в колонке / filterTextResolver. */
    private static String sortKey(Object element, int logical)
    {
        Object pathObj = Global.call(element, "getPath"); //$NON-NLS-1$
        String path = pathObj instanceof String s ? s : ""; //$NON-NLS-1$
        return switch (logical)
        {
            case COL_TYPE -> extensionOf(path);
            case COL_PATH ->
            {
                String fullName = GetRef.resolveFullNameOrNull(path);
                yield fullName != null ? fullName : ""; //$NON-NLS-1$
            }
            case COL_STATUS -> statusText(element);
            default -> path;
        };
    }

    private static String[] captureSelectedFileColumnValues(Table table)
    {
        if (table == null || table.isDisposed())
            return new String[0];
        TableItem[] sel = table.getSelection();
        if (sel == null || sel.length == 0)
            return new String[0];
        ArrayList<String> paths = new ArrayList<>(sel.length);
        for (TableItem item : sel)
        {
            if (item == null || item.isDisposed())
                continue;
            String file = captureFileColumnValueFromItem(item);
            if (file != null && !file.isEmpty())
                paths.add(file);
        }
        return paths.toArray(new String[0]);
    }

    private static String captureFileColumnValueFromItem(TableItem item)
    {
        // Материализация VIRTUAL, затем путь из модели (не getText(0) — после reorder индекс
        // визуальной колонки ≠ «Файл»).
        Object data = item.getData();
        String fromModel = fileColumnValueOf(data);
        if (fromModel != null && !fromModel.isEmpty())
            return fromModel;
        String fileCol = item.getText(0);
        return fileCol != null && !fileCol.isEmpty() ? fileCol : null;
    }

    private static void selectRowsByFileColumnValues(Table table, String[] fileColumnValues)
    {
        if (fileColumnValues == null || fileColumnValues.length == 0
            || table == null || table.isDisposed())
            return;
        LinkedHashSet<String> want = new LinkedHashSet<>();
        for (String v : fileColumnValues)
        {
            if (v != null && !v.isEmpty())
                want.add(v);
        }
        if (want.isEmpty())
            return;
        int count = table.getItemCount();
        ArrayList<Integer> indices = new ArrayList<>(want.size());
        for (int i = 0; i < count && indices.size() < want.size(); i++)
        {
            TableItem item = table.getItem(i);
            if (item == null || item.isDisposed())
                continue;
            String col0 = captureFileColumnValueFromItem(item);
            if (col0 != null && want.contains(col0))
                indices.add(Integer.valueOf(i));
        }
        if (indices.isEmpty())
            return;
        int[] idx = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++)
            idx[i] = indices.get(i).intValue();
        table.setSelection(idx);
        table.showSelection();
        TableItem[] selItems = table.getSelection();
        Event sel = new Event();
        sel.type = SWT.Selection;
        sel.widget = table;
        if (selItems.length > 0)
            sel.item = selItems[0];
        table.notifyListeners(SWT.Selection, sel);
    }

    /**
     * После смены коммита EGit заново заполняет таблицу и (при отборе «Все изменения
     * ресурса») выделяет связанные файлы. Сортировку по шапке нужно поставить снова,
     * а первую выделенную строку — показать в видимой области: штатный reveal
     * проигрывает гонке с асинхронной загрузкой diff.
     */
    private static void installCommitSwitchHooks(TableViewer viewer, Table table,
        FormTableInteraction[] interactionRef)
    {
        final Object[] lastInput = { new Object() };
        final boolean[] awaitingEgitSelect = { false };
        viewer.addPostSelectionChangedListener(event ->
        {
            if (table.isDisposed())
                return;
            Object input = viewer.getInput();
            if (input != lastInput[0])
            {
                lastInput[0] = input;
                awaitingEgitSelect[0] = true;
                Object data = table.getData(SORT_STATE_KEY);
                if (data instanceof SortState state)
                    applySortState(viewer, table, state, interactionRef, false);
                scheduleRevealFirstSelected(table);
                return;
            }
            if (awaitingEgitSelect[0] && table.getSelectionCount() > 0)
            {
                awaitingEgitSelect[0] = false;
                scheduleRevealFirstSelected(table);
            }
        });
    }

    private static void scheduleRevealFirstSelected(Table table)
    {
        if (table == null || table.isDisposed())
            return;
        Display display = table.getDisplay();
        Runnable reveal = () -> revealFirstSelectedRow(table);
        display.asyncExec(reveal);
        display.timerExec(50, reveal);
        display.timerExec(200, reveal);
        display.timerExec(600, reveal);
        display.timerExec(1200, reveal);
    }

    private static void revealFirstSelectedRow(Table table)
    {
        if (table == null || table.isDisposed())
            return;
        int[] sel = table.getSelectionIndices();
        if (sel == null || sel.length == 0)
            return;
        int first = sel[0];
        for (int i = 1; i < sel.length; i++)
        {
            if (sel[i] < first)
                first = sel[i];
        }
        if (first < 0 || first >= table.getItemCount())
            return;
        TableItem item = table.getItem(first);
        if (item != null && !item.isDisposed())
            table.showItem(item);
    }

    // -----------------------------------------------------------------------
    // Фильтр + re-parent таблицы
    // -----------------------------------------------------------------------

    private static void installFilterComposite(TableViewer fileViewer, Table table,
        CellLabelProvider origLabelProvider, TableColumn fileCol, TableColumn typeCol, TableColumn pathCol,
        TableColumn statusCol)
    {
        Composite revInfoSplit = table.getParent();
        if (revInfoSplit == null || revInfoSplit.isDisposed())
            return;

        Composite graphDetailSplit = revInfoSplit.getParent();
        if (graphDetailSplit == null || graphDetailSplit.isDisposed())
            return;

        Composite historyControl = graphDetailSplit.getParent();
        if (historyControl == null || historyControl.isDisposed())
            return;

        Debug.log("revInfoSplit=" + revInfoSplit.getClass().getSimpleName()
            + " children=" + childrenStr(revInfoSplit));
        Debug.log("graphDetailSplit=" + graphDetailSplit.getClass().getSimpleName()
            + " layout=" + historyControl.getLayout().getClass().getSimpleName()
            + " children=" + childrenStr(graphDetailSplit));
        Debug.log("historyControl layout=" + historyControl.getLayout().getClass().getSimpleName()
            + " children=" + childrenStr(historyControl));

        // --- Новый горизонтальный SashForm: graphDetailSplit | wrapper ---
        SashForm horizontalSplit =
            new SashForm(historyControl, SWT.HORIZONTAL);

        // graphDetailSplit (VERTICAL: graph + revInfoSplit с commitAndDiff) — влево.
        graphDetailSplit.setParent(horizontalSplit);
        graphDetailSplit.setLayoutData(null);

        Debug.log("after graphDetailSplit reparent: historyControl children="
            + childrenStr(historyControl) + " hSplit children=" + childrenStr(horizontalSplit));

        // Wrapper (фильтр + tableStack) — вправо, на всю высоту панели.
        Composite wrapper = new Composite(horizontalSplit, SWT.NONE);
        wrapper.setLayout(new GridLayout(1, false));

        // Фильтр + подсветка (объявляем до SearchBox — callback замыкает на них).
        GitHistoryFileFilter filter = new GitHistoryFileFilter();
        fileViewer.addFilter(filter);

        GitHistoryFileLabelProvider labelProvider =
            new GitHistoryFileLabelProvider(origLabelProvider);
        fileViewer.setLabelProvider(labelProvider);

        final FormTableInteraction[] interactionRef = new FormTableInteraction[1];

        // EDT SearchBox: лупа + clear + история (SWT.SEARCH на Win32 лупу не даёт).
        final FilterInputBox[] filterBoxRef = new FilterInputBox[1];
        // Последний реально применённый текст — для FocusOut-догона (SearchBox глотает delayed).
        final String[] lastAppliedFilter = { "" }; //$NON-NLS-1$
        final Runnable[] applyFilterRef = new Runnable[1];
        applyFilterRef[0] = () ->
        {
            FilterInputBox box = filterBoxRef[0];
            if (box == null || box.isDisposed())
                return;
            String text = box.getText();
            if (text == null)
                text = ""; //$NON-NLS-1$
            // Ключ = значение колонки «Файл» (col 0) текущей строки — до перезаполнения.
            String savedFile = captureFileColumnValue(table);
            Debug.log("filterApply savedFile=" + savedFile //$NON-NLS-1$
                + " selIdxBefore=" + (table.isDisposed() ? -2 : table.getSelectionIndex()) //$NON-NLS-1$
                + " filterText=[" + text + "]" //$NON-NLS-1$ //$NON-NLS-2$
                + " lastApplied=[" + lastAppliedFilter[0] + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            filter.setPattern(text);
            labelProvider.setHighlightPattern(text);
            lastAppliedFilter[0] = text;
            // Сброс до refresh: иначе SWT оставляет старый индекс → «другой» файл.
            table.deselectAll();
            fileViewer.setSelection(StructuredSelection.EMPTY);
            refreshWithRedrawOff(fileViewer);
            Debug.log("filterApply afterRefresh itemCount=" //$NON-NLS-1$
                + (table.isDisposed() ? -2 : table.getItemCount())); //$NON-NLS-1$
            // После перезаполнения — строка с тем же значением колонки «Файл».
            if (savedFile != null && !savedFile.isEmpty())
                selectRowByFileColumnValue(table, savedFile);
            // Прежний файл отфильтрован (не нашёлся) — переносим выделение на первую видимую строку.
            FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(table);
            FormTableInteraction interaction = interactionRef[0];
            if (interaction != null)
                interaction.resyncSelectionTheme();
        };
        filterBoxRef[0] = FilterInputBox.forGitHistory(wrapper, () -> applyFilterRef[0].run());
        FilterInputBox filterBox = filterBoxRef[0];
        filterBox.widget().addListener(SWT.Traverse, e ->
        {
            if (e.detail == SWT.TRAVERSE_ESCAPE)
            {
                filterBox.setText(""); //$NON-NLS-1$
                applyFilterRef[0].run();
                e.doit = false;
            }
        });
        // SearchBox.focusLost снимает ValueChangeListener на время displayMessage —
        // отложенный поиск после clear/X теряется. Догоняем расхождение поле ↔ фильтр.
        filterBox.widget().addListener(SWT.FocusOut, e ->
        {
            String text = filterBox.getText();
            if (text == null)
                text = ""; //$NON-NLS-1$
            if (text.equals(lastAppliedFilter[0]))
                return;
            Debug.log("filterFocusOut forceApply field=[" + text //$NON-NLS-1$
                + "] lastApplied=[" + lastAppliedFilter[0] + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            applyFilterRef[0].run();
        });

        Control filterKeys = filterBox.inputControl();
        if (filterKeys == null)
            filterKeys = filterBox.widget();
        FilterInputBoxListNavigation.installTableNavigation(filterKeys, table, null);

        // Эталон: tableStack (null) → columnHost (TableColumnLayout) → table.
        Composite tableStack = new Composite(wrapper, SWT.NONE);
        tableStack.setLayout(null);
        tableStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite columnHost = new Composite(tableStack, SWT.NONE);
        TableColumnLayout columnLayout = new TableColumnLayout(true);
        columnHost.setLayout(columnLayout);

        table.setParent(columnHost);
        table.setLayoutData(null);

        IDialogSettings columnSettings = dialogSettings();
        int fileWidth = FormTableColumnState.readWidth(columnSettings, KEY_COL_FILE_WIDTH, 300, 1);
        int typeWidth = FormTableColumnState.readWidth(columnSettings, KEY_COL_TYPE_WIDTH, 60, 1);
        int pathWidth = FormTableColumnState.readWidth(columnSettings, KEY_COL_PATH_WIDTH, 250, 1);
        int statusWidth = FormTableColumnState.readWidth(columnSettings, KEY_COL_STATUS_WIDTH, 90, 1);
        columnLayout.setColumnData(fileCol, new ColumnPixelData(fileWidth, true, true));
        columnLayout.setColumnData(typeCol, new ColumnPixelData(typeWidth, true, true));
        columnLayout.setColumnData(pathCol, new ColumnPixelData(pathWidth, true, true));
        columnLayout.setColumnData(statusCol, new ColumnPixelData(statusWidth, true, true));

        FormTableInteraction interaction =
            new FormTableInteraction(table, fileViewer, (item, col) ->
            {
                if (item == null || item.isDisposed() || col < 0)
                    return ""; //$NON-NLS-1$
                String text = item.getText(col);
                return text != null ? text : ""; //$NON-NLS-1$
            });
        interaction.setOwnerDrawColumns(fileCol, typeCol, pathCol, statusCol);
        interaction.setColumnReorderEnabled(true);
        // Отбор/«Различные значения колонки» работают по ЭЛЕМЕНТУ модели (FileDiff), а не по
        // TableItem — тот же расчёт, что и в GitHistoryFileLabelProvider.update() для колонок
        // «Тип»/«Путь» (там же — только пишется в ViewerCell, а не возвращается строкой). Без
        // этого резолвера FormTableInteraction тихо падал на fallback через
        // fileViewer.getLabelProvider(col): т.к. все 3 колонки — «сырые» TableColumn без
        // TableViewerColumn, JFace для ЛЮБОГО col возвращает один и тот же общий
        // GitHistoryFileLabelProvider, чей однопараметрический getText(element) всегда отдаёт
        // текст колонки «Файл» — отбор/группировка по «Тип»/«Путь» тогда фактически шли по «Файл».
        interaction.setFilterTextResolver((element, col) ->
        {
            if (col == 0)
                return labelProvider.getText(element);
            Object pathObj = Global.call(element, "getPath"); //$NON-NLS-1$
            String path = pathObj instanceof String s ? s : ""; //$NON-NLS-1$
            if (col == 1)
                return extensionOf(path);
            if (col == 2)
            {
                String fullName = GetRef.resolveFullNameOrNull(path);
                return fullName != null ? fullName : ""; //$NON-NLS-1$
            }
            if (col == 3)
                return statusText(element);
            return ""; //$NON-NLS-1$
        });
        FormTableColumnState.loadOrder(columnSettings, KEY_COL_ORDER, table);
        boolean hasSavedColumnWidths = FormTableColumnState.hasSavedColumnWidths(columnSettings, KEY_COL_FILL_MODE,
            KEY_COL_FILE_WIDTH, KEY_COL_TYPE_WIDTH, KEY_COL_PATH_WIDTH, KEY_COL_STATUS_WIDTH);
        interaction.install(hasSavedColumnWidths);
        interactionRef[0] = interaction;
        installColumnSort(fileViewer, table, new TableColumn[] { fileCol, typeCol, pathCol, statusCol },
            interactionRef);
        installCommitSwitchHooks(fileViewer, table, interactionRef);
        table.addDisposeListener(e ->
        {
            boolean fillMode = interaction.isColumnsExactFill();
            FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER, KEY_COL_FILL_MODE, fillMode,
                new String[] { KEY_COL_FILE_WIDTH, KEY_COL_TYPE_WIDTH, KEY_COL_PATH_WIDTH, KEY_COL_STATUS_WIDTH },
                new TableColumn[] { fileCol, typeCol, pathCol, statusCol }, table);
        });

        horizontalSplit.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        IDialogSettings sashSettings = dialogSettings();
        int leftW = FormTableColumnState.readWidth(sashSettings, KEY_SASH_LEFT, 50, 1);
        int rightW = FormTableColumnState.readWidth(sashSettings, KEY_SASH_RIGHT, 50, 1);
        Debug.log("sashLoad left=" + leftW + " right=" + rightW); //$NON-NLS-1$ //$NON-NLS-2$
        horizontalSplit.setWeights(new int[] { leftW, rightW });
        installSashWeightPersistence(horizontalSplit);

        Debug.log("before layout: hSplit=" + horizontalSplit
            + " visible=" + horizontalSplit.getVisible()
            + " hSplit.children=" + childrenStr(horizontalSplit)
            + " hSplit.bounds=" + horizontalSplit.getBounds());
        Debug.log("graphDetailSplit in hSplit: visible=" + graphDetailSplit.getVisible()
            + " bounds=" + graphDetailSplit.getBounds()
            + " childCount=" + graphDetailSplit.getChildren().length);
        Debug.log("wrapper in hSplit: visible=" + wrapper.getVisible()
            + " bounds=" + wrapper.getBounds());

        historyControl.layout(true, true);
        historyControl.redraw();

        Debug.log("after layout: hSplit.bounds=" + horizontalSplit.getBounds()
            + " hSplit.visible=" + horizontalSplit.getVisible()
            + " weights=" + java.util.Arrays.toString(horizontalSplit.getWeights())); //$NON-NLS-1$
        Debug.log("graphDetailSplit bounds=" + graphDetailSplit.getBounds()
            + " visible=" + graphDetailSplit.getVisible());
        Debug.log("wrapper bounds=" + wrapper.getBounds()
            + " visible=" + wrapper.getVisible());
        Debug.log("historyControl bounds=" + historyControl.getBounds()
            + " children=" + childrenStr(historyControl));

        table.setData(PATCHED_KEY, Boolean.TRUE);
    }

    /**
     * Значение колонки «Файл» (индекс 0) у текущей выделенной строки.
     * Для VIRTUAL сначала материализуем item ({@code getData}), затем {@code getText(0)}.
     */
    private static String captureFileColumnValue(Table table)
    {
        if (table == null || table.isDisposed())
            return null;
        int idx = table.getSelectionIndex();
        if (idx < 0)
            return null;
        TableItem item = table.getItem(idx);
        if (item == null || item.isDisposed())
            return null;
        // Материализация VIRTUAL → текст колонки 0 = FileDiff.getPath().
        return captureFileColumnValueFromItem(item);
    }

    /** Текст колонки «Файл» для элемента ({@code FileDiff.getPath()}). */
    private static String fileColumnValueOf(Object fileDiff)
    {
        Object pathObj = Global.call(fileDiff, "getPath"); //$NON-NLS-1$
        return pathObj instanceof String s && !s.isEmpty() ? s : null;
    }

    /**
     * После перезаполнения списка: найти строку с тем же значением колонки «Файл»
     * и сделать её текущей. Без {@link TableViewer#setSelection} (VIRTUAL+hashlookup
     * сбрасывает выделение) — только SWT-таблица + событие Selection.
     */
    private static void selectRowByFileColumnValue(Table table, String fileColumnValue)
    {
        if (fileColumnValue == null || fileColumnValue.isEmpty()
            || table == null || table.isDisposed())
            return;
        int count = table.getItemCount();
        int found = -1;
        for (int i = 0; i < count; i++)
        {
            TableItem item = table.getItem(i);
            if (item == null || item.isDisposed())
                continue;
            Object data = item.getData();
            String col0 = item.getText(0);
            if (col0 == null || col0.isEmpty())
                col0 = fileColumnValueOf(data);
            if (fileColumnValue.equals(col0))
            {
                found = i;
                break;
            }
        }
        if (found < 0)
        {
            Debug.log("selectRowByFileColumnValue MISS file=" + fileColumnValue //$NON-NLS-1$
                + " itemCount=" + count); //$NON-NLS-1$
            return;
        }
        table.setSelection(found);
        table.showSelection();
        TableItem[] selItems = table.getSelection();
        Event sel = new Event();
        sel.type = SWT.Selection;
        sel.widget = table;
        if (selItems.length > 0)
            sel.item = selItems[0];
        table.notifyListeners(SWT.Selection, sel);
        int gotIdx = table.getSelectionIndex();
        String gotFile = gotIdx >= 0 ? captureFileColumnValue(table) : null;
        Debug.log("selectRowByFileColumnValue OK file=" + fileColumnValue //$NON-NLS-1$
            + " wantIdx=" + found //$NON-NLS-1$
            + " idx=" + gotIdx //$NON-NLS-1$
            + " gotFile=" + gotFile //$NON-NLS-1$
            + " match=" + fileColumnValue.equals(gotFile)); //$NON-NLS-1$
    }

    /** Второстепенные данные — сохраняем при закрытии/пересоздании панели, не живьём на резайз. */
    private static void installSashWeightPersistence(SashForm sashForm)
    {
        sashForm.addDisposeListener(e ->
        {
            int[] w = sashForm.getWeights();
            if (w.length == 2 && w[0] > 0 && w[1] > 0)
            {
                IDialogSettings settings = dialogSettings();
                settings.put(KEY_SASH_LEFT, w[0]);
                settings.put(KEY_SASH_RIGHT, w[1]);
                Debug.log("sashSave left=" + w[0] + " right=" + w[1]); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    private static void refreshWithRedrawOff(TableViewer viewer)
    {
        Table table = viewer.getTable();
        if (table == null || table.isDisposed())
            return;
        table.setRedraw(false);
        try
        {
            viewer.refresh();
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    // -----------------------------------------------------------------------
    // Склейка «Путь + ; + Файл» для фильтра
    // -----------------------------------------------------------------------

    private static String matchText(Object element)
    {
        Object pathObj = Global.call(element, "getPath"); //$NON-NLS-1$
        if (!(pathObj instanceof String path) || path.isEmpty())
            return ""; //$NON-NLS-1$
        String fullName = GetRef.resolveFullNameOrNull(path);
        return (fullName != null ? fullName : "") + ";" + path; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String fileNameOf(String path)
    {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** Расширение файла (без точки) по пути; пусто, если точки нет. */
    private static String extensionOf(String path)
    {
        String name = fileNameOf(path);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : ""; //$NON-NLS-1$
    }

    /**
     * Текст статуса по {@code FileDiff.getChange()} ({@code org.eclipse.jgit.diff.DiffEntry.ChangeType} —
     * получен рефлексией, как и остальные поля {@code FileDiff}, без прямой compile-зависимости от
     * внутреннего API egit-ui).
     */
    private static String statusText(Object fileDiff)
    {
        Object change = Global.call(fileDiff, "getChange"); //$NON-NLS-1$
        if (change == null)
            return ""; //$NON-NLS-1$
        return switch (change.toString())
        {
            case "ADD" -> "Добавлен"; //$NON-NLS-1$ //$NON-NLS-2$
            case "MODIFY" -> "Изменён"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DELETE" -> "Удалён"; //$NON-NLS-1$ //$NON-NLS-2$
            case "RENAME" -> "Переименован"; //$NON-NLS-1$ //$NON-NLS-2$
            case "COPY" -> "Скопирован"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> ""; //$NON-NLS-1$
        };
    }

    // -----------------------------------------------------------------------
    // ViewerFilter
    // -----------------------------------------------------------------------

    private static final class GitHistoryFileFilter extends ViewerFilter
    {
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        boolean isEmpty()
        {
            return matcher.isEmpty;
        }

        @Override
        public boolean select(org.eclipse.jface.viewers.Viewer viewer,
            Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            String text = matchText(element);
            return !text.isEmpty() && matcher.matches(text);
        }
    }

    // -----------------------------------------------------------------------
    // Multi-column CellLabelProvider + подсветка
    // -----------------------------------------------------------------------

    /**
     * Multi-column label provider: column 0 — оригинальный
     * {@code FileDiffLabelProvider} (иконки, dimmed foreground, tooltip rename),
     * column 1 — расширение, column 2 — {@code resolveFullNameOrNull(path)},
     * column 3 — статус. Подсветка совпадений {@link SmartMatchHighlight}
     * только в колонках «Файл»/«Тип»/«Путь» (не в «Статус»).
     */
    private static final class GitHistoryFileLabelProvider
        extends StyledCellLabelProvider
        implements SmartLabelHighlight, ILabelProvider
    {
        private final CellLabelProvider origProvider;
        private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

        GitHistoryFileLabelProvider(CellLabelProvider origProvider)
        {
            this.origProvider = origProvider;
        }

        @Override
        public void initialize(ColumnViewer viewer, ViewerColumn column)
        {
            super.initialize(viewer, column);
            if (origProvider != null)
                Global.invoke(origProvider, "initialize", viewer, column); //$NON-NLS-1$
        }

        @Override
        public void setHighlightPattern(String pattern)
        {
            highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        }

        @Override
        public void update(ViewerCell cell)
        {
            if (cell == null)
                return;
            int col = cell.getColumnIndex();
            if (col == 0 && origProvider != null)
            {
                origProvider.update(cell);
            }
            else if (col == 1)
            {
                Object element = cell.getElement();
                Object pathObj = Global.call(element, "getPath"); //$NON-NLS-1$
                String path = pathObj instanceof String s ? s : ""; //$NON-NLS-1$
                cell.setText(extensionOf(path));
            }
            else if (col == 2)
            {
                Object element = cell.getElement();
                Object pathObj = Global.call(element, "getPath"); //$NON-NLS-1$
                String path = pathObj instanceof String s ? s : ""; //$NON-NLS-1$
                String fullName = GetRef.resolveFullNameOrNull(path);
                cell.setText(fullName != null ? fullName : ""); //$NON-NLS-1$
                // Как у штатной колонки «Файл»: dimmed foreground для unmarked FileDiff.
                copyFileColumnStyle(cell, element);
            }
            else if (col == 3)
            {
                Object element = cell.getElement();
                cell.setText(statusText(element));
                copyFileColumnStyle(cell, element);
            }

            // Всегда вызываем appendMatchRanges — иначе при очистке фильтра
            // старые StyleRange (SWT переиспользует TableItem) остаются висеть.
            // В «Статус» (col 3) подсветку не делаем: там фиксированные метки, не текст фильтра.
            String text = cell.getText();
            List<SmartMatcher.HighlightRange> ranges = col != 3
                && !highlightMatcher.isEmpty
                && text != null && !text.isEmpty()
                && highlightMatcher.matches(matchText(cell.getElement()))
                    ? highlightMatcher.getHighlightRanges(text)
                    : List.of();
            SmartMatchHighlight.appendMatchRanges(cell, ranges);
        }

        /** Копирует foreground/background/font штатного {@code FileDiffLabelProvider}. */
        private void copyFileColumnStyle(ViewerCell cell, Object element)
        {
            if (origProvider instanceof ColumnLabelProvider clp)
            {
                cell.setForeground(clp.getForeground(element));
                cell.setBackground(clp.getBackground(element));
                cell.setFont(clp.getFont(element));
                return;
            }
            Object fg = Global.invoke(origProvider, "getForeground", element); //$NON-NLS-1$
            cell.setForeground(fg instanceof Color c ? c : null);
            Object bg = Global.invoke(origProvider, "getBackground", element); //$NON-NLS-1$
            cell.setBackground(bg instanceof Color c ? c : null);
        }

        @Override
        public String getText(Object element)
        {
            if (origProvider instanceof ILabelProvider ilp)
                return ilp.getText(element);
            Object text = Global.invoke(origProvider, "getText", element); //$NON-NLS-1$
            return text instanceof String s ? s : ""; //$NON-NLS-1$
        }

        @Override
        public Image getImage(Object element)
        {
            if (origProvider instanceof ILabelProvider ilp)
                return ilp.getImage(element);
            Object img = Global.invoke(origProvider, "getImage", element); //$NON-NLS-1$
            return img instanceof Image i ? i : null;
        }

        @Override
        public String getToolTipText(Object element)
        {
            Object tip = Global.invoke(origProvider, "getToolTipText", element); //$NON-NLS-1$
            return tip instanceof String s ? s : null;
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            if (origProvider instanceof ILabelProvider ilp)
                ilp.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            if (origProvider instanceof ILabelProvider ilp)
                ilp.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            if (origProvider instanceof ILabelProvider ilp)
                return ilp.isLabelProperty(element, property);
            return false;
        }

        @Override
        public void dispose()
        {
            if (origProvider != null)
                origProvider.dispose();
        }
    }

    // -----------------------------------------------------------------------
    // Логи
    // -----------------------------------------------------------------------

    private static String childrenStr(Composite c)
    {
        if (c == null || c.isDisposed())
            return "null"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("["); //$NON-NLS-1$
        for (int i = 0; i < c.getChildren().length; i++)
        {
            if (i > 0)
                sb.append(", "); //$NON-NLS-1$
            org.eclipse.swt.widgets.Control ch = c.getChildren()[i];
            sb.append(ch.getClass().getSimpleName());
            sb.append(ch.getVisible() ? "(vis)" : "(hid)"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(ch.getBounds());
        }
        return sb.append("]").toString(); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // История запросов в поле «Найти» (FindToolbar)
    // -----------------------------------------------------------------------

    /**
     * Панель поиска создаётся при первом показе и пересоздаётся при скрытии
     * ({@code HistorySearchBar.isDynamic()}). Ctrl+↓ — через {@code Display.addFilter}:
     * у {@code FindToolbar} свой {@code KeyListener} на голую стрелку вниз.
     * Кнопка ▾ живёт в той же колонке, что и поле ({@code [поле][▾]|тулбар}),
     * иначе третья колонка GridLayout обрезается шириной CoolBar.
     * Смена активной части (клик в редакторе) вызывает {@code updateActionBars}
     * и пересоздание dynamic-вклада — патч повторяем с задержкой.
     */
    private static void installSearchHistory(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener appear = event ->
        {
            if (!(event.widget instanceof Control control) || control.isDisposed())
                return;
            Composite toolbar = locateFindToolbar(control);
            if (toolbar != null)
                tryPatchFindToolbar(toolbar);
        };
        display.addFilter(SWT.Show, appear);
        display.addFilter(SWT.Activate, appear);

        display.addFilter(SWT.KeyDown, event ->
        {
            if (!(event.widget instanceof Text text) || text.isDisposed())
                return;
            if (!Boolean.TRUE.equals(text.getData(SEARCH_HISTORY_PATCHED_KEY)))
                return;
            if (event.keyCode != SWT.ARROW_DOWN || (event.stateMask & SWT.CTRL) == 0)
                return;
            event.doit = false;
            FilterHistoryUi.openPopup(text, text, SEARCH_HISTORY_SCOPE_ID);
        });

        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookSearchHistoryWindow(window);
        wb.addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w) { hookSearchHistoryWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w) {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w)
            {
                if (w != null)
                    searchHistoryWindows.remove(w);
            }
        });
    }

    private static void hookSearchHistoryWindow(IWorkbenchWindow window)
    {
        if (window == null || !searchHistoryWindows.add(window))
            return;
        Shell shell = window.getShell();
        if (shell != null && !shell.isDisposed())
            scanFindToolbar(shell);
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partActivated(IWorkbenchPartReference ref) { scheduleFindToolbarRescan(); }
            @Override public void partDeactivated(IWorkbenchPartReference ref) { scheduleFindToolbarRescan(); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { scheduleFindToolbarRescan(); }
            @Override public void partVisible(IWorkbenchPartReference ref) { scheduleFindToolbarRescan(); }
            @Override public void partOpened(IWorkbenchPartReference ref) { scheduleFindToolbarRescan(); }
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    /**
     * {@code HistorySearchBar.isDynamic()} — после {@code updateActionBars}
     * createControl ещё не успел вернуть новый FindToolbar.
     */
    private static void scheduleFindToolbarRescan()
    {
        int gen = ++findToolbarRescanGen;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(50, () ->
        {
            if (gen == findToolbarRescanGen)
                rescanFindToolbars();
        });
        display.timerExec(200, () ->
        {
            if (gen == findToolbarRescanGen)
                rescanFindToolbars();
        });
    }

    private static void rescanFindToolbars()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                for (IViewReference ref : page.getViewReferences())
                {
                    IViewPart view = ref.getView(false);
                    if (isHistoryView(view))
                        tryPatchFindFromView(view);
                }
            }
        }
    }

    private static void tryPatchFindFromView(IViewPart view)
    {
        if (view == null)
            return;
        try
        {
            Object historyPage = Global.call(view, "getHistoryPage"); //$NON-NLS-1$
            Object searchBar = historyPage != null ? Global.getField(historyPage, "searchBar") : null; //$NON-NLS-1$
            Object toolbar = searchBar != null ? Global.getField(searchBar, "toolbar") : null; //$NON-NLS-1$
            if (toolbar instanceof Composite findToolbar && !findToolbar.isDisposed())
                tryPatchFindToolbar(findToolbar);
        }
        catch (Exception ignored)
        {
            // страница ещё не создана
        }
        try
        {
            Object bars = view.getViewSite().getActionBars();
            Object manager = Global.call(bars, "getToolBarManager"); //$NON-NLS-1$
            Object control = Global.call(manager, "getControl"); //$NON-NLS-1$
            if (control instanceof Control root && !root.isDisposed())
                scanFindToolbar(root);
        }
        catch (Exception ignored)
        {
            // тулбар ещё не создан
        }
    }

    private static void scanFindToolbar(Control root)
    {
        if (root == null || root.isDisposed())
            return;
        if (isFindToolbar(root))
            tryPatchFindToolbar((Composite) root);
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                scanFindToolbar(child);
        }
    }

    private static Composite findFindToolbar(Control start)
    {
        for (Control current = start; current != null; current = current.getParent())
        {
            if (isFindToolbar(current))
                return (Composite) current;
        }
        return null;
    }

    /**
     * Show/Activate часто приходит на родительский {@code ToolBar} после
     * {@code isDynamic()} recreate — вверх по родителям FindToolbar не видно.
     */
    private static Composite locateFindToolbar(Control start)
    {
        Composite found = findFindToolbar(start);
        if (found != null)
            return found;
        if (start instanceof ToolBar || start.getClass().getSimpleName().contains("CoolBar"))
            return findFindToolbarInTree(start, 0);
        return null;
    }

    private static Composite findFindToolbarInTree(Control root, int depth)
    {
        if (root == null || root.isDisposed() || depth > 4)
            return null;
        if (isFindToolbar(root))
            return (Composite) root;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Composite found = findFindToolbarInTree(child, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean isFindToolbar(Control control)
    {
        return control instanceof Composite
            && FIND_TOOLBAR_CLASS.equals(control.getClass().getName());
    }

    private static void tryPatchFindToolbar(Composite findToolbar)
    {
        if (findToolbar == null || findToolbar.isDisposed())
            return;
        Object field = Global.getField(findToolbar, "patternField"); //$NON-NLS-1$
        if (!(field instanceof Text text) || text.isDisposed())
            return;

        if (!Boolean.TRUE.equals(text.getData(SEARCH_HISTORY_PATCHED_KEY)))
        {
            text.setData(SEARCH_HISTORY_PATCHED_KEY, Boolean.TRUE);
            FilterHistoryUi.wireKeyboard(text, SEARCH_HISTORY_SCOPE_ID);
            text.addListener(SWT.DefaultSelection, e ->
            {
                if (e.detail == SWT.ICON_CANCEL)
                    return;
                FilterHistoryStore.remember(SEARCH_HISTORY_SCOPE_ID, text.getText());
            });
        }

        Composite wrap = wrapOf(text);
        if (wrap != null)
        {
            if (!hasHistoryLabel(wrap))
                restoreHistoryButton(wrap, text);
            findToolbar.layout(true, true);
            Composite parent = findToolbar.getParent();
            if (parent != null && !parent.isDisposed())
                parent.layout(true, true);
            return;
        }
        if (text.getParent() != findToolbar)
            return;
        if (Boolean.TRUE.equals(text.getData(SEARCH_HISTORY_WRAP_KEY)))
            return;
        wrapPatternField(text, findToolbar);
    }

    private static Composite wrapOf(Text text)
    {
        Composite parent = text.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        if (Boolean.TRUE.equals(parent.getData(SEARCH_HISTORY_WRAP_KEY)))
            return parent;
        return null;
    }

    private static boolean hasHistoryLabel(Composite wrap)
    {
        if (wrap == null || wrap.isDisposed())
            return false;
        for (Control child : wrap.getChildren())
        {
            if (child instanceof Label && !child.isDisposed())
                return true;
            if (child instanceof Composite composite)
            {
                for (Control nested : composite.getChildren())
                {
                    if (nested instanceof Label && !nested.isDisposed())
                        return true;
                }
            }
        }
        return false;
    }

    private static void restoreHistoryButton(Composite wrap, Text text)
    {
        Composite buttonsRow = null;
        for (Control child : wrap.getChildren())
        {
            if (child instanceof Composite composite && child != text)
            {
                buttonsRow = composite;
                break;
            }
        }
        if (buttonsRow == null)
            buttonsRow = FilterHistoryUi.createButtonsRow(wrap);
        FilterHistoryUi.addHistoryButton(buttonsRow, text, SEARCH_HISTORY_SCOPE_ID,
            SEARCH_HISTORY_BUTTON_TOOLTIP + Global.pluginSignForTooltip());
    }

    /**
     * Ряд {@code [поле][▾]} занимает первую колонку FindToolbar — штатный
     * {@code GridLayout} на 2 колонки не трогаем (CoolBar считает ширину
     * вклада до нашего патча и обрезает лишнюю колонку).
     */
    private static void wrapPatternField(Text text, Composite findToolbar)
    {
        text.setData(SEARCH_HISTORY_WRAP_KEY, Boolean.TRUE);
        Control after = siblingAfter(text);
        Object layoutData = text.getLayoutData();

        Composite row = new Composite(findToolbar, SWT.NONE);
        row.setData(SEARCH_HISTORY_WRAP_KEY, Boolean.TRUE);
        GridLayout rowLayout = new GridLayout(1, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        rowLayout.horizontalSpacing = 2;
        row.setLayout(rowLayout);
        if (layoutData != null)
            row.setLayoutData(layoutData);
        else
            row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        text.setParent(row);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (after != null && !after.isDisposed())
            row.moveAbove(after);

        Composite buttonsRow = FilterHistoryUi.createButtonsRow(row);
        FilterHistoryUi.addHistoryButton(buttonsRow, text, SEARCH_HISTORY_SCOPE_ID,
            SEARCH_HISTORY_BUTTON_TOOLTIP + Global.pluginSignForTooltip());
        findToolbar.layout(true, true);
        Composite parent = findToolbar.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
    }

    private static Control siblingAfter(Control control)
    {
        Composite parent = control.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++)
        {
            if (children[i] == control)
                return i + 1 < children.length ? children[i + 1] : null;
        }
        return null;
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static final class Debug
    {
        private static final String TAG = "GitHistoryFileColumns"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
