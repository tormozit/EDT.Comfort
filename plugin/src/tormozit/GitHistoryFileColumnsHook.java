package tormozit;

import java.util.List;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
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
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Колонки «Файл» + «Путь» и многословный фильтр ({@link SmartMatcher}, AND по словам)
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
 *   <li>Заголовки + 2 колонки: «Файл» (repo-путь, штатная иконка) и «Путь»
 *       (полное имя метаданных через {@link GetRef#resolveFullNameOrNull}).</li>
 *   <li>{@link FormTableInteraction}: выбор ячейки, подсветка активной колонки,
 *       копирование текста ячейки (Ctrl+C / меню).</li>
 *   <li>Поле фильтра {@link FilterInputBox} ({@code SearchBox} с лупой) над таблицей
 *       (re-parent через wrapper → tableStack → columnHost)
 *       с подсказкой {@link FilterInputBox#FLAT_FILTER_TOOLTIP} и историей
 *       (штатный EDT {@code SearchBox}).</li>
 *   <li>Фильтр по склейке {@code resolveFullNameOrNull(path) + ";" + FileDiff.getPath()}
 *       ({@code null} → пустая строка) с подсветкой совпадений
 *       ({@link SmartMatchHighlight}).</li>
 * </ul>
 *
 * <p>Включение: Параметры → Комфорт → «Улучшать списки»
 * ({@link ComfortSettings#PREF_REPLACE_LIST_FILTERS}).
 * Логирование: Параметры → Комфорт → «Общее логирование».
 */
public final class GitHistoryFileColumnsHook implements IStartup
{
    private static final String TEAM_HISTORY_VIEW_ID = "org.eclipse.team.ui.GenericHistoryView"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.gitHistoryFileColumnsPatched"; //$NON-NLS-1$
    private static final String COPY_CMD = "org.eclipse.ui.edit.copy"; //$NON-NLS-1$

    private static final String EGIT_UI_PLUGIN_ID = "org.eclipse.egit.ui"; //$NON-NLS-1$

    /** Активная таблица файлов для перехвата Ctrl+C (Win32 не шлёт букву в KeyDown). */
    private static volatile Table copyTargetTable;
    private static volatile FormTableInteraction copyTargetInteraction;
    private static boolean copyExecutionListenerInstalled;
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
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
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
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

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
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (!tryPatch(view) && attempt < 20)
                schedulePatch(view, attempt + 1);
            else if (attempt >= 20)
                Debug.log("tryPatch GIVE UP after 20 attempts"); //$NON-NLS-1$
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
                return true;

            // Сохраняем оригинальный FileDiffLabelProvider до замены.
            CellLabelProvider origLabelProvider = null;
            IBaseLabelProvider currentLp = fileViewer.getLabelProvider();
            if (currentLp instanceof CellLabelProvider clp)
                origLabelProvider = clp;

            TableColumn[] cols = installColumns(table);
            installFilterComposite(fileViewer, table, origLabelProvider, cols[0], cols[1]);

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

    // -----------------------------------------------------------------------
    // Колонки
    // -----------------------------------------------------------------------

    /** @return [0]=«Файл», [1]=«Путь» */
    private static TableColumn[] installColumns(Table table)
    {
        TableColumn fileCol = new TableColumn(table, SWT.LEFT, 0);
        fileCol.setText("Файл"); //$NON-NLS-1$
        fileCol.setToolTipText("Путь к файлу в репозитории" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        fileCol.setResizable(true);
        fileCol.setWidth(ComfortSettings.getGitHistoryColumnWidth("file", 300)); //$NON-NLS-1$
        fileCol.addListener(SWT.Resize, e ->
        {
            int w = fileCol.getWidth();
            if (w > 0)
                ComfortSettings.setGitHistoryColumnWidth("file", w); //$NON-NLS-1$
        });

        TableColumn pathCol = new TableColumn(table, SWT.LEFT, 1);
        pathCol.setText("Путь"); //$NON-NLS-1$
        pathCol.setToolTipText("Полное имя объекта метаданных" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        pathCol.setResizable(true);
        pathCol.setWidth(250);

        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        return new TableColumn[] { fileCol, pathCol };
    }

    // -----------------------------------------------------------------------
    // Фильтр + re-parent таблицы
    // -----------------------------------------------------------------------

    private static void installFilterComposite(TableViewer fileViewer, Table table,
        CellLabelProvider origLabelProvider, TableColumn fileCol, TableColumn pathCol)
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

        int fileWidth = ComfortSettings.getGitHistoryColumnWidth("file", 300); //$NON-NLS-1$
        columnLayout.setColumnData(fileCol, new ColumnPixelData(fileWidth, true, true));
        columnLayout.setColumnData(pathCol, new ColumnWeightData(1, 50, true));

        FormTableInteraction interaction =
            new FormTableInteraction(table, fileViewer, (item, col) ->
            {
                if (item == null || item.isDisposed() || col < 0)
                    return ""; //$NON-NLS-1$
                String text = item.getText(col);
                return text != null ? text : ""; //$NON-NLS-1$
            });
        interaction.setOwnerDrawColumns(fileCol, pathCol);
        interaction.setColumnReorderEnabled(true);
        interaction.install();
        interactionRef[0] = interaction;
        wireCellCopyCommand(table, interaction);

        horizontalSplit.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        int leftW = ComfortSettings.getGitHistorySashWeight("left", 50); //$NON-NLS-1$
        int rightW = ComfortSettings.getGitHistorySashWeight("right", 50); //$NON-NLS-1$
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
     * Win32: Ctrl+C не доходит до {@code SWT.KeyDown} (акселератор Edit→Copy).
     * Перехват {@code org.eclipse.ui.edit.copy} — как в {@code PreferenceSearchFilterAugmenter}.
     */
    private static void wireCellCopyCommand(Table table, FormTableInteraction interaction)
    {
        copyTargetTable = table;
        copyTargetInteraction = interaction;
        table.addDisposeListener(e ->
        {
            if (copyTargetTable == table)
            {
                copyTargetTable = null;
                copyTargetInteraction = null;
            }
        });
        installCopyExecutionListener();
    }

    private static void installCopyExecutionListener()
    {
        if (copyExecutionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        copyExecutionListenerInstalled = true;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                // После штатного Copy EGit перезаписываем буфер текстом активной ячейки.
                handlePossibleCellCopy(commandId);
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleCellCopy(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
    }

    private static void handlePossibleCellCopy(String commandId)
    {
        if (!COPY_CMD.equals(commandId))
            return;
        Table table = copyTargetTable;
        FormTableInteraction interaction = copyTargetInteraction;
        if (table == null || table.isDisposed() || interaction == null || !table.isFocusControl())
            return;
        TableItem item = interaction.selectedItem();
        if (item == null || item.isDisposed())
        {
            int idx = table.getSelectionIndex();
            if (idx < 0)
                return;
            item = table.getItem(idx);
        }
        if (item == null || item.isDisposed())
            return;
        int col = interaction.activeColumn();
        if (col < 0)
            col = 0;
        String text = item.getText(col);
        if (text == null)
            text = ""; //$NON-NLS-1$
        Clipboard clipboard = new Clipboard(table.getDisplay());
        try
        {
            clipboard.setContents(new Object[] { text },
                new Transfer[] { TextTransfer.getInstance() });
            Debug.log("cellCopy via command col=" + col + " len=" + text.length()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            clipboard.dispose();
        }
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
        item.getData();
        String fileCol = item.getText(0);
        if (fileCol != null && !fileCol.isEmpty())
            return fileCol;
        return fileColumnValueOf(item.getData());
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

    private static void installSashWeightPersistence(SashForm sashForm)
    {
        // Не сохраняем веса, пока первичная раскладка не успокоится (Resize при open
        // иначе затирает prefs значением 50/50).
        final boolean[] armed = { false };
        Display.getDefault().timerExec(500, () -> armed[0] = true);

        final Runnable[] pending = new Runnable[1];
        Runnable save = () ->
        {
            pending[0] = null;
            if (!armed[0] || sashForm.isDisposed())
                return;
            int[] w = sashForm.getWeights();
            if (w.length == 2 && w[0] > 0 && w[1] > 0)
            {
                ComfortSettings.setGitHistorySashWeights(w[0], w[1]);
                Debug.log("sashSave left=" + w[0] + " right=" + w[1]); //$NON-NLS-1$ //$NON-NLS-2$
            }
        };
        Listener scheduleSave = e ->
        {
            if (!armed[0])
                return;
            if (pending[0] != null)
                Display.getDefault().timerExec(-1, pending[0]);
            pending[0] = save;
            Display.getDefault().timerExec(300, save);
        };
        // Перетаскивание разделителя: SashForm сам Resize не шлёт — только дети / Sash.
        for (Control child : sashForm.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            if ("Sash".equals(child.getClass().getSimpleName())) //$NON-NLS-1$
                child.addListener(SWT.Selection, scheduleSave);
            else
                child.addListener(SWT.Resize, scheduleSave);
        }
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
     * column 1 — {@code resolveFullNameOrNull(path)}. Подсветка совпадений
     * {@link SmartMatchHighlight} по тексту каждой колонки.
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
                String fullName = GetRef.resolveFullNameOrNull(path);
                cell.setText(fullName != null ? fullName : ""); //$NON-NLS-1$
                // Как у штатной колонки «Файл»: dimmed foreground для unmarked FileDiff.
                copyFileColumnStyle(cell, element);
            }

            // Всегда вызываем appendMatchRanges — иначе при очистке фильтра
            // старые StyleRange (SWT переиспользует TableItem) остаются висеть.
            String text = cell.getText();
            List<SmartMatcher.HighlightRange> ranges = !highlightMatcher.isEmpty
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

    private static final class Debug
    {
        private static final String TAG = "GitHistoryFileColumns"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            Global.tempLog(TAG, msg);
        }
    }
}
