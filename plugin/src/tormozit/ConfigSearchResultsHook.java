package tormozit;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.NewSearchUI;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.common.Functions;
import com._1c.g5.v8.dt.common.localization.LocalizationManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetLink;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTemplateDescription;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaTotalField;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.NestedDataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.mcore.Help;
import com._1c.g5.v8.dt.mcore.HelpPage;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Row;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaEditor;
import com._1c.g5.v8.dt.dcs.ui.EditorPage;
import com._1c.g5.v8.dt.dcs.ui.settings.Settings;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com._1c.g5.v8.dt.search.core.BmObjectMatch;
import com._1c.g5.v8.dt.search.core.refs.BmReferenceMatch;
import com._1c.g5.v8.dt.search.core.text.TextSearchFileMatch;
import com._1c.g5.v8.dt.search.core.text.TextSearchModelMatch;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPageLayout;
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
import org.eclipse.ui.texteditor.ITextEditor;

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
public final class ConfigSearchResultsHook implements IStartup
{
    private static final String SEARCH_VIEW_ID = "org.eclipse.search.ui.views.SearchView"; //$NON-NLS-1$
    private static final String PAGE_CLASS_MARKER = "ConfigurationSearchViewPage"; //$NON-NLS-1$
    private static final String SETTINGS_SECTION = "ConfigSearchResults"; //$NON-NLS-1$
    /** Второстепенные данные (положение разделителя, порядок/ширина колонок таблицы вхождений) — в
     * {@link IDialogSettings}, сохраняются при закрытии/пересоздании панели, а не живьём. */
    private static final String KEY_SASH_LEFT = "sashLeft"; //$NON-NLS-1$
    private static final String KEY_SASH_RIGHT = "sashRight"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "matchColumnOrder"; //$NON-NLS-1$
    private static final String KEY_COL_PATH_WIDTH = "matchColPathWidth"; //$NON-NLS-1$
    private static final String KEY_COL_PROPERTY_WIDTH = "matchColPropertyWidth"; //$NON-NLS-1$
    private static final String KEY_COL_LINE_WIDTH = "matchColLineWidth"; //$NON-NLS-1$
    private static final String KEY_COL_TEXT_WIDTH = "matchColTextWidth"; //$NON-NLS-1$
    /** Был ли при закрытии активен режим заполнения по ширине (см. {@link FormTableColumnState}). */
    private static final String KEY_COL_FILL_MODE = "matchColFillMode"; //$NON-NLS-1$

    /**
     * Временно (по просьбе пользователя, на время тестирования нашей таблицы вхождений
     * {@link #cachedMatchTableViewer}) — штатная таблица {@code treeLayout.tableViewer} не
     * патчится (агрегация/колонка "Путь"/копирование) и визуально скрыта
     * ({@code outer.setMaximizedControl}), чтобы не расходовать память на её ({@code changeSource})
     * дублирующее заполнение при больших выборках. Дерево и наша таблица работают как обычно.
     * Включить обратно — {@code true}.
     */
    private static final boolean NATIVE_TABLE_ENABLED = false;

    private static final String HOOKED_KEY = "tormozit.searchAggregationHooked"; //$NON-NLS-1$
    private static final String INPUT_WATCH_HOOKED_KEY = "tormozit.searchTreeInputWatchHooked"; //$NON-NLS-1$
    private static final String TREE_COUNT_LABEL_HOOKED_KEY = "tormozit.searchTreeCountLabelHooked"; //$NON-NLS-1$
    /** Как {@code Messages.IMatchItem_Total_matches_count_pattern__0} в search.ui. */
    private static final String MATCH_COUNT_SUFFIX_PATTERN = " ({0} соответствий)"; //$NON-NLS-1$

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
                @Override public void queryAdded(ISearchQuery query)
                {
                    Global.tempLog("search-tree-empty", "config.queryAdded: " + describeQuery(query)); //$NON-NLS-1$ //$NON-NLS-2$
                    // История/повторный показ без Dispose панели — сначала зафиксировать текущие ширины.
                    saveMatchColumnStateOnUiThread();
                    onQueryEvent("queryAdded"); //$NON-NLS-1$
                }
                @Override public void queryRemoved(ISearchQuery query)
                {
                    log("onQueryEvent: queryRemoved " + (query != null ? query.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                @Override public void queryStarting(ISearchQuery query)
                {
                    Global.tempLog("search-tree-empty", "config.queryStarting: " + describeQuery(query)); //$NON-NLS-1$ //$NON-NLS-2$
                    onSearchStarting();
                }
                @Override public void queryFinished(ISearchQuery query)
                {
                    Global.tempLog("search-tree-empty", "config.queryFinished: " + describeQuery(query)); //$NON-NLS-1$ //$NON-NLS-2$
                    onSearchFinished(); onQueryEvent("queryFinished"); //$NON-NLS-1$
                }
            });
        });
    }

    private static String describeQuery(ISearchQuery query)
    {
        if (query == null)
            return "null"; //$NON-NLS-1$
        return query.getClass().getName() + "@" + System.identityHashCode(query) //$NON-NLS-1$
            + " result=" + (query.getSearchResult() != null //$NON-NLS-1$
                ? query.getSearchResult().getClass().getName() + "@" + System.identityHashCode(query.getSearchResult()) //$NON-NLS-1$
                : "null"); //$NON-NLS-1$
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

    // -----------------------------------------------------------------------
    // Таблица мультивыбора вхождений (открытие + "Создать остановки отладчика")
    // -----------------------------------------------------------------------

    /**
     * Штатная правая таблица ({@code treeLayout.tableViewer}) создаётся 1С со стилем
     * {@code SWT.SINGLE} (декомпиляция {@code TreeSearchViewPageLayout.createViewer()}: маска стиля
     * {@code 268503808} — VIRTUAL|FULL_SELECTION|BORDER|V_SCROLL|H_SCROLL, бита MULTI нет), а стиль
     * SWT-виджета нельзя сменить после создания. Дерево слева, наоборот, создано с MULTI
     * (маска {@code 2818} = BORDER|V_SCROLL|H_SCROLL|MULTI) — мультивыбор УЗЛОВ уже работает,
     * но нужен мультивыбор конкретных СТРОК (нескольких вхождений внутри одного узла или из разных
     * узлов). Поэтому — третья (своя, SWT.MULTI) панель справа: {@code pageContainer}
     * (FillLayout, единственный штатный потомок — внутренний SashForm{@code treePart|tablePart})
     * оборачивается в новый внешний SashForm, где старый (нетронутый, без единой правки) внутренний
     * SashForm — слева, наша таблица — справа. Штатная агрегация/восстановление выделения/сортировка
     * (см. {@link #installAggregationListener}) не затрагиваются — работают как раньше со своей
     * (штатной, однострочно-выбираемой) таблицей; наша — полноценная ДОПОЛНИТЕЛЬНАЯ таблица вхождений
     * (показ + открытие двойным кликом/Enter + копирование), а не только источник для точек останова —
     * это лишь одна из команд над этим списком.
     */
    private static final String MATCH_PANE_HOOKED_KEY = "tormozit.searchAggregationMatchPaneHooked"; //$NON-NLS-1$

    private static TableViewer cachedMatchTableViewer;
    private static TableColumn cachedMatchPathColumn;
    private static TableColumn cachedMatchPropertyColumn;
    private static TableColumn cachedMatchLineColumn;
    private static TableColumn cachedMatchTextColumn;
    private static FormTableInteraction cachedMatchTableInteraction;
    private static final int MATCH_PATH_COLUMN_WIDTH = 220;
    private static final int MATCH_TEXT_COLUMN_WIDTH = 280;

    // Отложенное довычисление «Текст» для BslResourceMatchTreeTableItem (IMatchItemDeferredCalculation) —
    // штатный элемент результатов "Найти ссылки на объект" по BSL-модулям изначально отдаёт заглушку
    // "<Pending>" в getDecoratedText(), пока не вызван его calculate() (резолв EMF-ресурса модуля и
    // текста строки через Xtext node model — декомпиляция search-ui, .tmp/bundles/search-ui). Штатная
    // таблица результатов вызывает calculate() сама (предположительно лениво при отрисовке); наша
    // таблица строится через reflection-снимок и раньше никогда calculate() не вызывала — колонка
    // "Текст" оставалась с "<Pending>" навсегда. Довычисляем только для строк, видимых в данный момент
    // в НАШЕЙ таблице (по согласованию с пользователем — не для всех строк сразу), фоновым Job,
    // последовательно (calculate() трогает общий EMF ResourceSet — не распараллеливаем).
    private static final Map<Object, Boolean> deferredCalcScheduled = new IdentityHashMap<>();
    /** Очередь и {@link #deferredCalcWorkerActive} — всегда вместе, под этим замком (см. {@link #enqueueDeferredCalculations}). */
    private static final Object DEFERRED_CALC_LOCK = new Object();
    private static final java.util.ArrayDeque<MatchRow> deferredCalcQueue = new java.util.ArrayDeque<>();
    private static boolean deferredCalcWorkerActive;

    /**
     * Простое имя искомого объекта (последний сегмент из заголовка запроса, например
     * "ВажностьПроблемыУчета" из "Перечисление.ВажностьПроблемыУчета") — для подсветки его
     * вхождений в колонке "Текст" структурных совпадений ("Найти ссылки на объект"), у которых,
     * в отличие от текстового поиска, НЕТ готового диапазона вхождения от 1С (см.
     * {@link #highlightSearchedObjectOccurrences}). Обновляется в {@link #refreshMatchTable} —
     * дёшево, пересчитывается на каждое изменение выбора в дереве результатов.
     */
    private static volatile String cachedSearchedObjectSimpleName;

    private static final class MatchRow
    {
        final String path;
        final String property;
        final long lineNumber;
        /** Не final — обновляется после фонового {@code calculate()} для отложенных BSL-совпадений. */
        String text;
        /** С подсветкой вхождения (стили штатного {@code getDecoratedText()}/{@code getStyledText()}). */
        StyledString styledText;
        final IFile file;
        /** Исходный элемент таблицы (IMatchItem) — нужен для открытия через {@code handleOpen}. */
        final Object tableItem;

        MatchRow(String path, String property, long lineNumber, StyledString styledText, IFile file,
                Object tableItem)
        {
            this.path = path;
            this.property = property;
            this.lineNumber = lineNumber;
            this.styledText = styledText;
            this.text = styledText != null ? styledText.getString() : ""; //$NON-NLS-1$
            this.file = file;
            this.tableItem = tableItem;
        }
    }

    private static void installMatchTableSplitPane(IViewPart view, Object activePage, Object treeLayout,
            TreeViewer treeViewer)
    {
        Object pageContainerObj = Global.invoke(treeLayout, "getPageContainer"); //$NON-NLS-1$
        if (!(pageContainerObj instanceof Composite pageContainer) || pageContainer.isDisposed())
        {
            log("installMatchTableSplitPane: pageContainer недоступен"); //$NON-NLS-1$
            return;
        }
        if (pageContainer.getData(MATCH_PANE_HOOKED_KEY) != null)
            return; // уже установлена для этого экземпляра страницы

        Control[] children = pageContainer.getChildren();
        if (children.length != 1)
        {
            log("installMatchTableSplitPane: неожиданное число потомков pageContainer=" + children.length); //$NON-NLS-1$
            return;
        }
        Control nativeSplit = children[0];

        SashForm outer = new SashForm(pageContainer, SWT.HORIZONTAL);
        nativeSplit.setParent(outer);

        // FormTableInteraction (подсветка заголовка колонки) требует, чтобы прямой родитель Table
        // либо использовал TableColumnLayout, либо не имел layout вообще (resolveOverlayRoot()) —
        // SashForm со своим layout не подходит (см. эталон "tableStack" в RecentPlacesView.java).
        Composite matchTableStack = new Composite(outer, SWT.NONE);
        matchTableStack.setLayout(null);

        TableViewer matchViewer = new TableViewer(matchTableStack,
            SWT.MULTI | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        Table matchTable = matchViewer.getTable();
        matchTable.setHeaderVisible(true);
        matchTable.setLinesVisible(true);
        matchTableStack.addControlListener(new org.eclipse.swt.events.ControlAdapter()
        {
            @Override
            public void controlResized(org.eclipse.swt.events.ControlEvent e)
            {
                if (!matchTable.isDisposed())
                    matchTable.setBounds(matchTableStack.getClientArea());
            }
        });
        // Второстепенные данные — сохраняем при закрытии/пересоздании панели, не живьём на резайз.
        outer.addDisposeListener(e ->
        {
            int[] w = outer.getWeights();
            if (w.length == 2)
            {
                IDialogSettings settings = dialogSettings();
                settings.put(KEY_SASH_LEFT, w[0]);
                settings.put(KEY_SASH_RIGHT, w[1]);
            }
        });
        matchViewer.setContentProvider(org.eclipse.jface.viewers.ArrayContentProvider.getInstance());

        IDialogSettings matchSettings = dialogSettings();
        TableViewerColumn pathCol = new TableViewerColumn(matchViewer, SWT.LEFT);
        pathCol.getColumn().setText("Путь"); //$NON-NLS-1$
        pathCol.getColumn().setToolTipText("Путь" + Global.pluginSignForTooltip());
        pathCol.getColumn().setWidth(
            FormTableColumnState.readWidth(matchSettings, KEY_COL_PATH_WIDTH, MATCH_PATH_COLUMN_WIDTH, 1));
        cachedMatchPathColumn = pathCol.getColumn();
        pathCol.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof MatchRow row)
                    return row.path != null ? row.path : ""; //$NON-NLS-1$
                return ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn propertyCol = new TableViewerColumn(matchViewer, SWT.LEFT);
        propertyCol.getColumn().setText("Свойство"); //$NON-NLS-1$
        propertyCol.getColumn().setToolTipText("Свойство"); //$NON-NLS-1$
        propertyCol.getColumn().setWidth(
            FormTableColumnState.readWidth(matchSettings, KEY_COL_PROPERTY_WIDTH, 140, 1));
        propertyCol.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof MatchRow row)
                    return row.property != null ? row.property : ""; //$NON-NLS-1$
                return ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn lineCol = new TableViewerColumn(matchViewer, SWT.RIGHT);
        lineCol.getColumn().setText("Строка"); //$NON-NLS-1$
        lineCol.getColumn().setToolTipText("Номер строки" + Global.pluginSignForTooltip());
        lineCol.getColumn().setWidth(
            FormTableColumnState.readWidth(matchSettings, KEY_COL_LINE_WIDTH, 60, 1));
        lineCol.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (element instanceof MatchRow row)
                    return row.lineNumber > 0 ? String.valueOf(row.lineNumber) : ""; //$NON-NLS-1$
                return ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn textCol = new TableViewerColumn(matchViewer, SWT.LEFT);
        textCol.getColumn().setText("Текст"); //$NON-NLS-1$
        textCol.getColumn().setToolTipText("Текст"); //$NON-NLS-1$
        textCol.getColumn().setWidth(
            FormTableColumnState.readWidth(matchSettings, KEY_COL_TEXT_WIDTH, MATCH_TEXT_COLUMN_WIDTH, 1));
        textCol.setLabelProvider(new SelectionAwareStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (element instanceof MatchRow row && row.styledText != null)
                    return row.styledText;
                return new StyledString(""); //$NON-NLS-1$
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

        matchViewer.setComparator(new org.eclipse.jface.viewers.ViewerComparator()
        {
            @Override
            public int compare(org.eclipse.jface.viewers.Viewer viewer, Object e1, Object e2)
            {
                if (!(e1 instanceof MatchRow r1) || !(e2 instanceof MatchRow r2))
                    return 0;
                int cmp = compareStrings(r1.path, r2.path);
                if (cmp != 0) return cmp;
                cmp = compareStrings(r1.property, r2.property);
                if (cmp != 0) return cmp;
                return Long.compare(r1.lineNumber, r2.lineNumber);
            }
        });

        IDialogSettings sashSettings = dialogSettings();
        outer.setWeights(new int[] {
            FormTableColumnState.readWidth(sashSettings, KEY_SASH_LEFT, 40, 1),
            FormTableColumnState.readWidth(sashSettings, KEY_SASH_RIGHT, 60, 1)
        });
        if (!NATIVE_TABLE_ENABLED && nativeSplit instanceof SashForm nativeSashForm)
        {
            // Прячем ТОЛЬКО штатную таблицу (tablePart) внутри штатной SashForm treePart|tablePart —
            // дерево (treePart) остаётся видимым и рабочим, оно нужно для выбора строк в НАШУ таблицу.
            Object treePartObj = Global.getField(treeLayout, "treePart"); //$NON-NLS-1$
            if (treePartObj instanceof Control treePart && !treePart.isDisposed())
                nativeSashForm.setMaximizedControl(treePart);
            else
                log("installMatchTableSplitPane: treePart недоступен, штатная таблица не скрыта"); //$NON-NLS-1$
        }
        FormTableColumnState.loadOrder(matchSettings, KEY_COL_ORDER, matchTable);
        FormTableInteraction interaction = new FormTableInteraction(matchTable, matchViewer);
        interaction.setOwnerDrawColumns(textCol.getColumn());
        // Колонки «Путь»/«Текст» динамически прячутся (см. ниже, applyResults) — прячем их через
        // FormTableInteraction.setColumnHidden, тогда авто-заполнение их не растягивает обратно.
        boolean hasSavedColumnWidths = FormTableColumnState.hasSavedColumnWidths(matchSettings, KEY_COL_FILL_MODE,
            KEY_COL_PATH_WIDTH, KEY_COL_PROPERTY_WIDTH, KEY_COL_LINE_WIDTH, KEY_COL_TEXT_WIDTH);
        interaction.install(hasSavedColumnWidths);
        // Второстепенные данные — при закрытии панели; при повторном поиске — явно в
        // {@link #saveMatchColumnStateOnUiThread} (Dispose не срабатывает, панель остаётся открытой).
        outer.addDisposeListener(e -> saveMatchColumnState());

        // Скролл/resize открывают новые строки — довычисляем "<Pending>" и для них
        // (см. scheduleVisibleDeferredCalculations).
        matchTable.addListener(SWT.Resize, e -> scheduleVisibleDeferredCalculations(matchViewer));
        ScrollBar matchVBar = matchTable.getVerticalBar();
        if (matchVBar != null)
            matchVBar.addListener(SWT.Selection, e -> scheduleVisibleDeferredCalculations(matchViewer));

        cachedMatchTableViewer = matchViewer;
        cachedMatchTextColumn = textCol.getColumn();
        cachedMatchPropertyColumn = propertyCol.getColumn();
        cachedMatchLineColumn = lineCol.getColumn();
        cachedMatchTableInteraction = interaction;
        pageContainer.setData(MATCH_PANE_HOOKED_KEY, Boolean.TRUE);
        matchTable.addDisposeListener(e -> {
            if (cachedMatchTableViewer == matchViewer)
            {
                cachedMatchTableViewer = null;
                cachedMatchTextColumn = null;
                cachedMatchPathColumn = null;
                cachedMatchPropertyColumn = null;
                cachedMatchLineColumn = null;
                cachedMatchTableInteraction = null;
            }
        });

        installMatchTableOpenSupport(matchViewer, activePage, view.getSite().getPage());

        treeViewer.addPostSelectionChangedListener(event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            refreshMatchTable(treeViewer, matchViewer);
        });
        refreshMatchTable(treeViewer, matchViewer);

        pageContainer.layout(true, true);
        log("installMatchTableSplitPane: OK"); //$NON-NLS-1$
    }

    private static int compareStrings(String a, String b)
    {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    }

    /** @return число собранных вхождений (для {@link #scheduleFinalAggregationReapplyAttempt}), -1 если таблица недоступна. */
    private static int syncMatchTableToTree(TreeViewer treeViewer)
    {
        TableViewer matchViewer = cachedMatchTableViewer;
        return matchViewer != null ? refreshMatchTable(treeViewer, matchViewer) : -1;
    }

    private static int refreshMatchTable(TreeViewer treeViewer, TableViewer matchViewer)
    {
        if (matchViewer.getTable() == null || matchViewer.getTable().isDisposed())
            return -1;
        cachedSearchedObjectSimpleName = extractSearchedObjectSimpleName(treeViewer);
        List<Object> selectedNodes = treeViewer.getStructuredSelection().toList();
        List<Object> tableItems = new ArrayList<>();
        for (Object node : selectedNodes)
            collectTableItemsRecursively(node, tableItems);

        List<MatchRow> rows = new ArrayList<>();
        for (Object tableItem : tableItems)
        {
            Object match = Global.invoke(tableItem, "getData"); //$NON-NLS-1$
            IFile file = null;
            long lineNumber = 0;
            if (match instanceof TextSearchFileMatch fm)
            {
                file = fm.getFile();
                lineNumber = fm.getLineNumber();
            }
            String path = formatPathForTableItem(tableItem, null);
            rows.add(new MatchRow(path, extractPropertyText(tableItem), lineNumber,
                extractMatchStyledText(tableItem), file, tableItem));
        }
        matchViewer.setInput(rows);

        // При терминальном узле путь у всех строк одинаковый (сам узел и есть этот путь) — как и
        // у штатной таблицы (см. hidePathColumn/showPathColumn), колонку тогда прячем.
        if (cachedMatchPathColumn != null && !cachedMatchPathColumn.isDisposed()
            && cachedMatchTableInteraction != null)
        {
            // Живая ширина важнее settings: иначе каждый refresh сбрасывал бы ручную подгонку.
            int pathWhenVisible = cachedMatchPathColumn.getWidth();
            if (pathWhenVisible <= 0)
                pathWhenVisible = FormTableColumnState.readWidth(dialogSettings(), KEY_COL_PATH_WIDTH,
                    MATCH_PATH_COLUMN_WIDTH, 1);
            cachedMatchTableInteraction.setColumnHidden(cachedMatchPathColumn,
                isTerminalTreeSelection(selectedNodes), pathWhenVisible);
        }

        // «Найти ссылки на объект» (BmReferenceMatch) — структурные вхождения без текста/смещения,
        // колонка «Текст» у них всегда пустая (в отличие от текстового поиска, TextSearchModelMatch/
        // TextSearchFileMatch) — прячем её для ТАКОГО набора результатов, тем же приёмом, что и «Путь».
        if (cachedMatchTextColumn != null && !cachedMatchTextColumn.isDisposed()
            && cachedMatchTableInteraction != null)
        {
            boolean anyText = rows.stream().anyMatch(row -> row.text != null && !row.text.isBlank());
            int textWhenVisible = cachedMatchTextColumn.getWidth();
            if (textWhenVisible <= 0)
                textWhenVisible = FormTableColumnState.readWidth(dialogSettings(), KEY_COL_TEXT_WIDTH,
                    MATCH_TEXT_COLUMN_WIDTH, 1);
            cachedMatchTableInteraction.setColumnHidden(cachedMatchTextColumn, !rows.isEmpty() && !anyText,
                textWhenVisible);
        }
        scheduleVisibleDeferredCalculations(matchViewer);
        return tableItems.size();
    }

    /**
     * Сохранить порядок/ширины колонок таблицы вхождений и флаг fill-mode.
     * Вызывать с UI-потока. Путь/Текст могут быть временно скрыты (ширина 0) — 0 не пишем,
     * чтобы не затереть последнюю видимую ширину пользователя.
     */
    private static void saveMatchColumnState()
    {
        TableViewer matchViewer = cachedMatchTableViewer;
        if (matchViewer == null)
            return;
        Table matchTable = matchViewer.getTable();
        if (matchTable == null || matchTable.isDisposed())
            return;
        FormTableInteraction interaction = cachedMatchTableInteraction;
        boolean fillMode = interaction != null && interaction.isColumnsExactFill();
        int pathWidth = columnWidthOrZero(cachedMatchPathColumn);
        int propertyWidth = columnWidthOrZero(cachedMatchPropertyColumn);
        int lineWidth = columnWidthOrZero(cachedMatchLineColumn);
        int textWidth = columnWidthOrZero(cachedMatchTextColumn);
        FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER,
            KEY_COL_FILL_MODE, fillMode,
            new String[] { KEY_COL_PATH_WIDTH, KEY_COL_PROPERTY_WIDTH, KEY_COL_LINE_WIDTH, KEY_COL_TEXT_WIDTH },
            new int[] { pathWidth, propertyWidth, lineWidth, textWidth }, matchTable);
    }

    /** Как {@link #saveMatchColumnState()}, но безопасно из любого потока (query listener). */
    private static void saveMatchColumnStateOnUiThread()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            saveMatchColumnState();
        else
            display.syncExec(ConfigSearchResultsHook::saveMatchColumnState);
    }

    private static int columnWidthOrZero(TableColumn column)
    {
        return column != null && !column.isDisposed() ? column.getWidth() : 0;
    }

    /**
     * Простое имя искомого объекта из заголовка запроса ({@code ISearchQuery.getLabel()} —
     * штатный API, для {@code FindReferencesSearchInput} это ровно {@code ISearchInput.getLabel()},
     * т.е. чистое имя объекта без счётчика совпадений). Заголовок обычно вида
     * {@code Ссылки на "Перечисление.ВажностьПроблемыУчета"} — берём содержимое кавычек, если
     * они есть, иначе весь label целиком; затем — последний сегмент после точки (само совпадение
     * в тексте БСЛ обычно идёт с другим префиксом типа: "ПеречислениеСсылка."/"Перечисления." —
     * общий у них только последний сегмент).
     */
    private static String extractSearchedObjectSimpleName(TreeViewer treeViewer)
    {
        Object input = treeViewer.getInput();
        if (!(input instanceof ISearchResult searchResult))
            return null;
        ISearchQuery query = searchResult.getQuery();
        String label = query != null ? query.getLabel() : null;
        if (label == null || label.isBlank())
            return null;
        int start = label.indexOf('"');
        int end = start >= 0 ? label.indexOf('"', start + 1) : -1;
        String qualified = (start >= 0 && end > start) ? label.substring(start + 1, end) : label;
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 && dot + 1 < qualified.length() ? qualified.substring(dot + 1) : qualified;
    }

    /**
     * {@code true}, если {@code tableItem} реализует внутренний
     * {@code com._1c.g5.v8.dt.internal.search.ui.provider.IMatchItemDeferredCalculation}
     * (проверка по простому имени интерфейса — тип не экспортирован бандлом search-ui,
     * доступен только рефлексией, как и остальной обход {@code IMatchItem} в этом классе).
     */
    private static boolean isDeferredCalculationItem(Object tableItem)
    {
        if (tableItem == null)
            return false;
        for (Class<?> c = tableItem.getClass(); c != null; c = c.getSuperclass())
            for (Class<?> iface : c.getInterfaces())
                if ("IMatchItemDeferredCalculation".equals(iface.getSimpleName())) //$NON-NLS-1$
                    return true;
        return false;
    }

    /**
     * Ставит в очередь фонового довычисления только те строки, что сейчас реально видны в
     * {@code matchViewer} (плюс небольшой запас на неполную последнюю строку) — не весь список
     * результатов сразу. Повторные вызовы (скролл/resize/обновление таблицы) добавляют только новые
     * видимые элементы — уже поставленные или уже вычисленные повторно не планируются
     * ({@link #deferredCalcScheduled}).
     */
    private static void scheduleVisibleDeferredCalculations(TableViewer matchViewer)
    {
        Table table = matchViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        int itemCount = table.getItemCount();
        int itemHeight = table.getItemHeight();
        if (itemCount == 0 || itemHeight <= 0)
            return;
        int top = table.getTopIndex();
        int visibleRows = table.getClientArea().height / itemHeight + 1;
        int last = Math.min(itemCount - 1, top + visibleRows);
        List<MatchRow> newlyQueued = new ArrayList<>();
        for (int i = top; i <= last; i++)
        {
            Object data = table.getItem(i).getData();
            if (!(data instanceof MatchRow row) || row.tableItem == null)
                continue;
            if (deferredCalcScheduled.containsKey(row.tableItem))
                continue;
            if (!isDeferredCalculationItem(row.tableItem))
                continue;
            if (Boolean.TRUE.equals(Global.invoke(row.tableItem, "isCalculated"))) //$NON-NLS-1$
                continue;
            deferredCalcScheduled.put(row.tableItem, Boolean.TRUE);
            newlyQueued.add(row);
        }
        if (!newlyQueued.isEmpty())
            enqueueDeferredCalculations(matchViewer, newlyQueued);
    }

    /**
     * Добавляет строки в очередь и, если фоновый воркер сейчас не работает, запускает новый —
     * добавление в очередь и переключение {@link #deferredCalcWorkerActive} выполняются под одним
     * замком с {@link #drainDeferredCalcQueue}, иначе возможна гонка «воркер как раз опустошил очередь
     * и уже помечает себя неактивным, а мы в этот момент решаем, что он ещё активен, и не
     * перезапускаем» — новые элементы застряли бы в очереди до следующего скролла/обновления.
     */
    private static void enqueueDeferredCalculations(TableViewer matchViewer, List<MatchRow> rows)
    {
        boolean startWorker;
        synchronized (DEFERRED_CALC_LOCK)
        {
            deferredCalcQueue.addAll(rows);
            startWorker = !deferredCalcWorkerActive;
            if (startWorker)
                deferredCalcWorkerActive = true;
        }
        if (startWorker)
            startDeferredCalcWorker(matchViewer);
    }

    /**
     * Фоновый Job, вычисляющий очередь по одному элементу (последовательно — {@code calculate()}
     * трогает общий EMF ResourceSet штатного поиска, распараллеливать не будем) и после каждого
     * элемента точечно обновляющий его строку в таблице через {@link #applyDeferredCalcResult}.
     */
    private static void startDeferredCalcWorker(TableViewer matchViewer)
    {
        Job job = new Job("Комфорт: довычисление результатов поиска") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                while (true)
                {
                    MatchRow row = drainDeferredCalcQueue();
                    if (row == null)
                        return Status.OK_STATUS;
                    if (monitor.isCanceled())
                    {
                        synchronized (DEFERRED_CALC_LOCK) { deferredCalcWorkerActive = false; }
                        return Status.CANCEL_STATUS;
                    }
                    try
                    {
                        Global.invoke(row.tableItem, "calculate"); //$NON-NLS-1$
                    }
                    catch (Exception e)
                    {
                        log("startDeferredCalcWorker: calculate() упал: " + e); //$NON-NLS-1$
                    }
                    Display display = Display.getDefault();
                    if (display != null && !display.isDisposed())
                        display.asyncExec(() -> applyDeferredCalcResult(matchViewer, row));
                }
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Атомарно: снять голову очереди, а если она пуста — тут же пометить воркер неактивным. */
    private static MatchRow drainDeferredCalcQueue()
    {
        synchronized (DEFERRED_CALC_LOCK)
        {
            MatchRow row = deferredCalcQueue.poll();
            if (row == null)
                deferredCalcWorkerActive = false;
            return row;
        }
    }

    /** Перечитывает текст строки после {@code calculate()} и точечно обновляет её ячейку. */
    private static void applyDeferredCalcResult(TableViewer matchViewer, MatchRow row)
    {
        Table table = matchViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        StyledString styled = extractMatchStyledText(row.tableItem);
        row.styledText = styled;
        row.text = styled != null ? styled.getString() : ""; //$NON-NLS-1$
        matchViewer.update(row, null);
    }

    /**
     * Открытие вхождения (двойной клик / Enter) — переиспользует штатную логику диспетчеризации
     * по типу {@code Match} (TextSearchModelMatch/TextSearchFileMatch/BmObjectMatch/...):
     * {@code ConfigurationSearchViewPage.handleOpen(OpenEvent)} (приватный метод; тот же, что штатно
     * вызывается для дерева и штатной таблицы через {@code OpenAndLinkWithEditorHelper.create(...)}
     * — найдено декомпиляцией). Так открытие не дублируется по типам совпадений отдельным кодом.
     */
    private static void installMatchTableOpenSupport(TableViewer matchViewer, Object activePage,
            IWorkbenchPage workbenchPage)
    {
        Table table = matchViewer.getTable();
        Runnable openSelected = () -> {
            IStructuredSelection selection = matchViewer.getStructuredSelection();
            if (selection.isEmpty())
                return;
            List<Object> tableItems = new ArrayList<>();
            for (Object element : selection.toList())
                if (element instanceof MatchRow row && row.tableItem != null)
                    tableItems.add(row.tableItem);
            if (tableItems.isEmpty())
                return;
            if (tableItems.size() == 1 && openDcsMatch(tableItems.get(0), workbenchPage))
                return;
            org.eclipse.jface.viewers.OpenEvent openEvent = new org.eclipse.jface.viewers.OpenEvent(
                matchViewer, new StructuredSelection(tableItems));
            // ConfigurationSearchViewPage имеет ДВЕ перегрузки handleOpen (OpenEvent и IMatchItem,
            // обе с 1 аргументом) — Global.invoke(name, argc) их не различает и может молча
            // (без исключения наружу) попасть не в ту, поэтому здесь — явный тип параметра.
            try
            {
                java.lang.reflect.Method m = activePage.getClass()
                    .getDeclaredMethod("handleOpen", org.eclipse.jface.viewers.OpenEvent.class); //$NON-NLS-1$
                m.setAccessible(true);
                m.invoke(activePage, openEvent);
                scheduleLeftmostScrollForOpenedMatch(workbenchPage, 0);
            }
            catch (Exception e)
            {
                log("installMatchTableOpenSupport: handleOpen failed: " + e); //$NON-NLS-1$
            }
        };

        matchViewer.addDoubleClickListener(event -> {
            if (ComfortSettings.isReplaceListFiltersEnabled())
                openSelected.run();
        });
        table.addListener(SWT.KeyDown, event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
                openSelected.run();
        });
    }

    /**
     * Двойной клик по вхождению внутри схемы компоновки данных (СКД): открывает редактор
     * МД-объекта, содержащего схему (см. {@link GoToDefinition#openMdObjectViaOpenHelper}),
     * и переходит к найденному варианту настроек в дереве вариантов на встроенной странице
     * «Настройки» DCS-редактора; для вхождения в представлении варианта сразу выделяет
     * нужную колонку ({@code Variants.TITLE_COL_INDEX} — доступ рефлексией, пакет не экспортирован).
     *
     * <p>Переключение самой верхней вкладки редактора СКД («Наборы данных»/«Настройки»/…)
     * публичным API не управляется — {@code DataCompositionSchemaEditor} не хранит свой
     * {@code CTabFolder} в поле (найдено декомпиляцией). Если встроенный DCS-редактор
     * откроется не на вкладке «Настройки», нужный вариант в дереве вариантов всё равно уже
     * будет выделен — переключение вкладки вручную.
     *
     * @return {@code true}, если открытие/навигация обработаны здесь — штатный
     *         {@code handleOpen} для этого вхождения вызывать не нужно
     */
    private static boolean openDcsMatch(Object tableItem, IWorkbenchPage workbenchPage)
    {
        try
        {
            Object matchObj = Global.invoke(tableItem, "getData"); //$NON-NLS-1$
            // Справочная информация: штатный handleOpen открывает владельца (журнал/справочник…),
            // а нужен MdHelpContentEditor — тот же вызов, что OpenMdHelpContentAction:
            // OpenHelper.openEditor(mdObject, helpFeature).
            if (openHelpContentMatch(matchObj, workbenchPage))
                return true;
            if (!(matchObj instanceof TextSearchModelMatch match))
            {
                // BmReferenceMatch («Найти ссылки на объект») внутри формы: штатный handleOpen
                // открывает форму, но не выделяет элемент (декомпиляция: штатно зовёт 3-arg
                // OpenHelper.openEditor(EObject, feature, ISelection) с глубоко вложенным
                // getSource() — выделение в дерево формы не форвардит). Находим ближайший
                // предок-реквизит/команду/параметр/элемент формы и открываем ПОДТВЕРЖДЁННО рабочим
                // способом — OpenHelper.openEditor(EObject), 1-arg, без feature (см.
                // GoToDefinition#openTopLevelFormElement; тот же вызов, что и у уже существующего
                // CompareConfigOpenObjectHandler.openInEditor() для дерева сравнения).
                if (matchObj instanceof BmReferenceMatch)
                {
                    EObject leaf = resolveMatchLeaf(matchObj);
                    if (leaf != null && isInsideForm(leaf))
                    {
                        EObject formChildElement = findNearestFormChild(leaf);
                        if (formChildElement != null
                            && GoToDefinition.openTopLevelFormElement(formChildElement, workbenchPage))
                        {
                            try
                            {
                                workbenchPage.showView(IPageLayout.ID_PROP_SHEET);
                            }
                            catch (Exception e)
                            {
                            }
                            PropertyFieldFocus.schedule(workbenchPage, leaf, resolveMatchFeature(matchObj));
                            return true;
                        }
                    }
                    // Обычный реквизит/измерение/ресурс объекта (не форма/СКД/табл. документ).
                    // Вложенные leaf (Синоним.ru / Подсказка.ru) — см. openNestedMdObjectMemberMatch;
                    // совпадение прямо по реквизиту (Комментарий) — штатный handleOpen + фокус поля.
                    else if (leaf != null && isInsideMdObjectMember(leaf))
                    {
                        if (openNestedMdObjectMemberMatch(leaf, workbenchPage, resolveMatchFeature(matchObj)))
                            return true;
                        try
                        {
                            workbenchPage.showView(IPageLayout.ID_PROP_SHEET);
                        }
                        catch (Exception e)
                        {
                        }
                        PropertyFieldFocus.schedule(workbenchPage, leaf, resolveMatchFeature(matchObj));
                    }
                }
                return false;
            }

            Optional<?> matchedOpt = match.resolveMatchObject();
            if (matchedOpt.isEmpty() || !(matchedOpt.get() instanceof EObject matchedObject))
            {
                return false;
            }
            boolean insideDcs = isInsideDataCompositionSchema(matchedObject);
            boolean insideSpreadsheet = isInsideSpreadsheetDocument(matchedObject);
            // Текст запроса динамического списка (DynamicListExtInfo.queryText) намеренно НЕ
            // обрабатывается отдельно — попытки открыть DynamicListQueryDialog (и рефлексией, и
            // через клик по реальной AEF-ссылке) не смогли надёжно дать одновременно рабочее
            // открытие/выделение И редактируемый диалог; штатная обработка EDT (return false ниже)
            // хотя бы стабильно открывает форму и выделяет реквизит.
            if (!insideDcs && !insideSpreadsheet)
            {
                // Вложенное свойство реквизита МД (Синоним.ru / Подсказка.ru / Тип.Типы…): штатный
                // handleOpen → OpenHelper.openEditor(leaf, feature) поднимается до MdObject с
                // feature=attributes и selection=leaf (не реквизит) — редактор кратко выделяет
                // реквизит, затем активирует группу «Реквизиты». Для плоских свойств самого
                // реквизита (Комментарий) leaf уже BasicFeature — штатный путь корректен.
                if (openNestedMdObjectMemberMatch(matchedObject, workbenchPage, match.getFeature()))
                    return true;
                // Штатная обработка EDT (handleOpen) сама корректно открывает форму и выделяет
                // найденный элемент (реквизит/команду/элемент формы) — это уже подтверждено и трогать
                // не нужно (попытка сделать выделение самим сломала штатную активацию, см. откат
                // этой правки выше). Единственное, чего штатная обработка не делает сама — не
                // показывает панель «Свойства». Только показываем её (не трогая выделение/открытие)
                // и отдаём управление штатному handleOpen (return false) — панель сама подхватит
                // выделение, которое штатно выставит handleOpen, через ISelectionListener.
                if (isInsideForm(matchedObject) || isInsideMdObjectMember(matchedObject))
                {
                    try
                    {
                        workbenchPage.showView(IPageLayout.ID_PROP_SHEET);
                    }
                    catch (Exception e)
                    {
                    }
                }
                // Доводим до конкретного поля панели (см. PropertyFieldFocus) — и для реквизита
                // МД-объекта, и для элемента формы: у элемента формы свой редактор есть, но
                // свойство вроде «ПутьКДанным» правится всё равно только в панели «Свойства».
                if (isInsideMdObjectMember(matchedObject) || isInsideForm(matchedObject))
                    PropertyFieldFocus.schedule(workbenchPage, matchedObject, match.getFeature());
                return false;
            }

            // Общие макеты (и подобные объекты, где схема — корень своего BM-ресурса, eContainer()==null)
            // не имеют MdObject-владельца, достижимого через eContainer() — только через отдельный
            // BM-индекс top-object (тот же, что уже верно отдаёт колонку «Путь», см. hierarchicalPropertyPath).
            // getMetadataTopObjectId()/resolveObjectById() (базовый Match, resolveObjectById — protected,
            // отсюда рефлексия) — тот же механизм, что уже верно резолвит верхний МД-объект для колонки
            // «Путь» (см. bmTopObjectPathFromTableItem). getTopObjectId()/resolveMatchTopObject() у
            // TextSearchModelMatch — ДРУГОЙ, более локальный top (сама DataCompositionSchema, не МД-объект).
            MdObject mdObject = null;
            long metadataTopObjectId = match.getMetadataTopObjectId();
            Object resolvedTop = Global.invoke(match, "resolveObjectById", metadataTopObjectId); //$NON-NLS-1$
            if (resolvedTop instanceof Optional<?> topOpt && topOpt.isPresent()
                && topOpt.get() instanceof MdObject topMdObject)
                mdObject = topMdObject;
            if (mdObject == null)
                mdObject = GoToDefinition.findContainingMdObject(matchedObject);
            if (mdObject == null)
            {
                return false;
            }
            String fullName = GetRef.eObjectToFullName(mdObject);

            // Форма/макет объекта (в отличие от общего макета/формы или схемы-в-отчёте) — не
            // отдельный bm-топ-объект: mdObject/fullName выше указывают на ВЛАДЕЛЬЦА (напр.
            // Справочник), поэтому OpenHelper открыл бы его редактор, а не саму форму/макет.
            // Уточняем имя по URI собственного BM-ресурса вхождения (см. ownedChildFullNameFor) —
            // тот же механизм, что уже даёт верный путь в колонке «Путь».
            String ownedChildFullName = ownedChildFullNameFor(matchedObject);
            if (ownedChildFullName != null && !ownedChildFullName.equals(fullName))
            {
                fullName = ownedChildFullName;
            }

            // Устаревшие результаты поиска (после правки схемы BM пересоздаёт внутренние ID — старый
            // metadataTopObjectId выше уже ни на что не указывает): резолвим mdObject ЗАНОВО по
            // полному имени — тот же путь, что и «Перейти к определению» (GoToDefinition), не
            // зависящий от старых числовых ID. Если получилось — используем свежий объект вместо
            // потенциально мёртвой ссылки; если нет (имя само переименовано/удалено) — пробуем со
            // старым mdObject, как раньше (без регресса).
            EObject freshMdObject = null;
            try
            {
                Object bmModel = match.getModel();
                Object dtProjectObj = bmModel != null ? Global.getField(bmModel, "project") : null; //$NON-NLS-1$
                if (dtProjectObj instanceof IDtProject dtProject)
                {
                    IProject iProject = dtProject.getWorkspaceProject();
                    freshMdObject = GoToDefinition.resolveEObjectForFullName(fullName, workbenchPage, iProject);
                }
            }
            catch (Exception e)
            {
            }
            if (freshMdObject instanceof MdObject freshMd)
            {
                mdObject = freshMd;
            }

            boolean opened = GoToDefinition.openMdObjectViaOpenHelper(mdObject,
                fullName, workbenchPage, new StructuredSelection(matchedObject));
            if (!opened)
                return false;

            if (insideSpreadsheet)
                scheduleMoxelCellReveal(workbenchPage, matchedObject, 0);
            else
                scheduleDcsSettingsReveal(workbenchPage, matchedObject, match, 0);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** См. {@link #scheduleLeftmostScrollForOpenedMatch} — редактор активируется асинхронно. */
    private static void scheduleDcsSettingsReveal(IWorkbenchPage workbenchPage, EObject matchedObject,
            TextSearchModelMatch match, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () -> {
            try
            {
                DataCompositionSchemaEditor dcsEditor =
                    findDataCompositionSchemaEditor(workbenchPage.getActiveEditor());
                if (dcsEditor == null)
                {
                    if (attempt < 10)
                        scheduleDcsSettingsReveal(workbenchPage, matchedObject, match, attempt + 1);
                    return;
                }
                revealInDcsSettings(dcsEditor, matchedObject, match);
            }
            catch (Throwable t)
            {
            }
        });
    }

    private static DataCompositionSchemaEditor findDataCompositionSchemaEditor(IEditorPart activeEditor)
    {
        if (!(activeEditor instanceof DtGranularEditor<?> granularEditor))
        {
            return null;
        }
        Object activePage = granularEditor.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorEmbeddedEditorPage<?> embeddedPage))
        {
            return null;
        }
        Object embedded = embeddedPage.getEmbeddedEditor();
        return embedded instanceof DataCompositionSchemaEditor dcsEditor ? dcsEditor : null;
    }

    /**
     * Встроенный редактор табличного документа (Moxel) — тот же паттерн, что
     * {@link #findDataCompositionSchemaEditor}, но без типизированной проверки embedded-объекта:
     * {@code com._1c.g5.v8.dt.moxel.ui.editor.MoxelEditor} — в пакете {@code .ui.editor}, статус
     * экспорта не проверялся (в отличие от {@code DataCompositionSchemaEditor}), поэтому вместо
     * {@code instanceof} — сравнение id встроенной страницы
     * ({@code com._1c.g5.v8.dt.moxel.ui.TemplateEditorSpreadsheetPage.PAGE_ID},
     * найдено декомпиляцией: {@code "editors.commontemplate.pages.spreadsheet"}); дальше — только
     * рефлексия ({@code Global.invoke}), как для Variants/TableExViewer в DCS.
     */
    private static Object findMoxelEditor(IEditorPart activeEditor)
    {
        if (!(activeEditor instanceof DtGranularEditor<?> granularEditor))
        {
            return null;
        }
        Object activePage = granularEditor.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorEmbeddedEditorPage<?> embeddedPage))
        {
            return null;
        }
        Object embedded = embeddedPage.getEmbeddedEditor();
        return "editors.commontemplate.pages.spreadsheet".equals(embeddedPage.getId()) ? embedded : null; //$NON-NLS-1$
    }

    /**
     * Строка/колонка ячейки табличного документа из ключей охватывающих {@code EMap}-записей
     * ({@code SpreadsheetDocument.getRows()}/{@code Row.getCells()}) — тот же подъём, что и в
     * {@link #hierarchicalPropertyPath} для сегмента «Область(...)», только возвращает индексы,
     * а не строит текст. Индексы — «как в модели» (с нуля), см. {@link #oneBased} для отображения.
     */
    private static int[] findMoxelCellRowCol(EObject obj)
    {
        Object col = null;
        for (EObject cur = obj; cur != null; cur = cur.eContainer())
        {
            if (!(cur instanceof java.util.Map.Entry<?, ?> mapEntry))
                continue;
            Object value = mapEntry.getValue();
            if (value instanceof Cell && col == null)
                col = mapEntry.getKey();
            else if (value instanceof Row && col instanceof Number colNum)
            {
                Object rowKey = mapEntry.getKey();
                if (rowKey instanceof Number rowNum)
                    return new int[] { rowNum.intValue(), colNum.intValue() };
                return null;
            }
        }
        return null;
    }

    /** См. {@link #scheduleDcsSettingsReveal} — тот же retry-паттерн, редактор активируется асинхронно. */
    private static void scheduleMoxelCellReveal(IWorkbenchPage workbenchPage, EObject matchedObject, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () -> {
            try
            {
                Object moxelEditor = findMoxelEditor(workbenchPage.getActiveEditor());
                if (moxelEditor == null)
                {
                    if (attempt < 10)
                        scheduleMoxelCellReveal(workbenchPage, matchedObject, attempt + 1);
                    else
                        {
                        }
                    return;
                }
                int[] rowCol = findMoxelCellRowCol(matchedObject);
                if (rowCol == null)
                {
                    return;
                }
                Object moxelControl = Global.invoke(moxelEditor, "getMoxelControl"); //$NON-NLS-1$
                if (moxelControl == null)
                {
                    return;
                }
                Global.invoke(moxelControl, "setSelectionToCellSelection", rowCol[0], rowCol[1]); //$NON-NLS-1$
                Global.invoke(moxelControl, "ensureCellVisible", rowCol[0], rowCol[1]); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
            }
        });
    }

    /** Первый предок {@code obj} (включая сам {@code obj}) заданного типа — {@code null}, если не найден. */
    @SuppressWarnings("unchecked")
    private static <T extends EObject> T findAncestor(EObject obj, Class<T> type)
    {
        for (EObject cur = obj; cur != null; cur = cur.eContainer())
            if (type.isInstance(cur))
                return (T) cur;
        return null;
    }

    /**
     * Имя containment-feature, которым владеет объект типа {@code rowType} непосредственным потомком
     * на пути к {@code matchedObject} — то есть какое СВОЙСТВО строки таблицы реально совпало. Если
     * {@code matchedObject} сам уже искомого типа, используется {@code matchFeature} (feature
     * самого матча).
     *
     * <p>Сравнение по ТИПУ ({@code rowType.isInstance(cur)}), не по ссылке ({@code cur == rowObject}) —
     * BM отдаёт РАЗНЫЕ экземпляры на один и тот же логический объект при каждом отдельном подъёме
     * через {@code eContainer()} (подтверждено логированием {@code System.identityHashCode()}:
     * объект, найденный {@link #findAncestor}, и объект, встреченный при повторном подъёме в этом
     * же методе, — разные ссылки одного типа). Сравнение по ссылке поэтому в принципе ненадёжно;
     * по типу — надёжно, как и {@link #findAncestor} (который уже так работает).
     */
    private static String findRowFeatureName(EObject matchedObject, Class<? extends EObject> rowType,
            EStructuralFeature matchFeature)
    {
        if (rowType.isInstance(matchedObject))
            return matchFeature != null ? matchFeature.getName() : null;
        EObject prevChild = null;
        for (EObject cur = matchedObject; cur != null; cur = cur.eContainer())
        {
            if (rowType.isInstance(cur))
                return prevChild != null && prevChild.eContainingFeature() != null
                    ? prevChild.eContainingFeature().getName() : null;
            prevChild = cur;
        }
        return null;
    }

    /** Свойство → константа-индекс колонки (имя public static final int поля на КЛАССЕ СТРАНИЦЫ). */
    private static final Map<String, Map<String, String>> DCS_PAGE_COLUMN_BY_FEATURE = Map.of(
        "com._1c.g5.v8.dt.dcs.ui.calculated.CalculatedFields", Map.ofEntries( //$NON-NLS-1$
            Map.entry("dataPath", "DATA_PATH_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("expression", "EXPR_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("title", "TITLE_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("presentationExpression", "PRESENTATION_EXPR_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("valueType", "VALUE_TYPE_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("appearance", "APPEARANCE_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("inputParameters", "INPUT_PARAMETERS_COL_INDEX") //$NON-NLS-1$ //$NON-NLS-2$
        ),
        "com._1c.g5.v8.dt.dcs.ui.nested.NestedSchemas", Map.ofEntries( //$NON-NLS-1$
            Map.entry("name", "NAME_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("title", "HEADER_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("schema", "SCHEMA_COL_INDEX"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("settings", "SETTINGS_COL_INDEX") //$NON-NLS-1$ //$NON-NLS-2$
        )
    );

    /** См. {@link #DCS_PAGE_COLUMN_BY_FEATURE}, но для полей набора данных — константы это ЗНАЧЕНИЯ enum
     * {@code DataSetsFieldsViewerBase.FieldsColumn} (не поля страницы), см. {@link #scheduleDataSetFieldReveal}. */
    private static final Map<String, String> DATASET_FIELD_COLUMN_BY_FEATURE = Map.of(
        "dataPath", "PATH_COL_INDEX", //$NON-NLS-1$ //$NON-NLS-2$
        "title", "TITLE_COL_INDEX", //$NON-NLS-1$ //$NON-NLS-2$
        "field", "FIELD_COL_INDEX" //$NON-NLS-1$ //$NON-NLS-2$
    );

    /**
     * Свойство → буквальный индекс колонки — для страниц БЕЗ публичных констант индекса
     * (в отличие от {@link #DCS_PAGE_COLUMN_BY_FEATURE}). Индексы найдены декомпиляцией порядка
     * вызовов {@code DcsUiUtil.createColumn(...)} в соответствующих {@code createXxx(Composite)}
     * страниц (Resources/Links/Parameters) — не догадка, реальный порядок создания колонок.
     */
    private static final Map<String, Map<String, Integer>> DCS_PAGE_COLUMN_LITERAL_BY_FEATURE = Map.of(
        // Resources.createResources: Поле(Dcs_Field), Выражение(Dcs_Expression), Рассчитывать по(Dcs_Calculate_by)
        "com._1c.g5.v8.dt.dcs.ui.resources.Resources", Map.of( //$NON-NLS-1$
            "dataPath", 0, //$NON-NLS-1$
            "expression", 1 //$NON-NLS-1$
        ),
        // Links: Dcs_Link_source, Dcs_Link_target, Dcs_Source_expression, Dcs_Target_expression,
        // Dcs_Parameter, Dcs_Parameters_list, Dcs_Link_condition, Dcs_Initial_link_value, Dcs_Mandatory_link
        "com._1c.g5.v8.dt.dcs.ui.links.Links", Map.ofEntries( //$NON-NLS-1$
            Map.entry("sourceDataSet", 0), //$NON-NLS-1$
            Map.entry("destinationDataSet", 1), //$NON-NLS-1$
            Map.entry("sourceExpression", 2), //$NON-NLS-1$
            Map.entry("destinationExpression", 3), //$NON-NLS-1$
            Map.entry("parameter", 4), //$NON-NLS-1$
            Map.entry("parameterListAllowed", 5), //$NON-NLS-1$
            Map.entry("linkConditionExpression", 6), //$NON-NLS-1$
            Map.entry("startExpression", 7), //$NON-NLS-1$
            Map.entry("required", 8) //$NON-NLS-1$
        ),
        // Parameters: Dcs_Name, Dcs_Title, Dcs_Type, Dcs_Available_values, Dcs_Available_list_values,
        // Dcs_Value, Dcs_Expression, Dcs_Functional_option_parameter, Dcs_Include_available_fields,
        // Dcs_Availability_restriction, Dcs_Restrict_unfilled_values, Dcs_Usage, Dcs_Edit_parameters
        "com._1c.g5.v8.dt.dcs.ui.parameters.Parameters", Map.ofEntries( //$NON-NLS-1$
            Map.entry("name", 0), //$NON-NLS-1$
            Map.entry("title", 1), //$NON-NLS-1$
            Map.entry("valueType", 2), //$NON-NLS-1$
            Map.entry("availableValues", 3), //$NON-NLS-1$
            Map.entry("valueListAllowed", 4), //$NON-NLS-1$
            Map.entry("values", 5), //$NON-NLS-1$
            Map.entry("expression", 6), //$NON-NLS-1$
            Map.entry("functionalOptionsParameter", 7), //$NON-NLS-1$
            Map.entry("availableAsField", 8), //$NON-NLS-1$
            Map.entry("useRestriction", 9), //$NON-NLS-1$
            Map.entry("denyIncompleteValues", 10), //$NON-NLS-1$
            Map.entry("use", 11), //$NON-NLS-1$
            Map.entry("inputParameters", 12) //$NON-NLS-1$
        )
    );

    private static void revealInDcsSettings(DataCompositionSchemaEditor dcsEditor, EObject matchedObject,
            TextSearchModelMatch match)
    {
        SettingsVariant variant = findAncestor(matchedObject, SettingsVariant.class);
        if (variant != null)
        {
            revealSettingsVariant(dcsEditor, matchedObject, variant);
            return;
        }

        DataCompositionSchemaCalculatedField calcField =
            findAncestor(matchedObject, DataCompositionSchemaCalculatedField.class);
        if (calcField != null)
        {
            revealSimpleTableExPage(dcsEditor, "com._1c.g5.v8.dt.dcs.ui.calculated.CalculatedFields", //$NON-NLS-1$
                matchedObject, calcField, DataCompositionSchemaCalculatedField.class, match.getFeature());
            return;
        }

        DataCompositionSchemaTotalField totalField = findAncestor(matchedObject, DataCompositionSchemaTotalField.class);
        if (totalField != null)
        {
            revealSimpleTableExPage(dcsEditor, "com._1c.g5.v8.dt.dcs.ui.resources.Resources", //$NON-NLS-1$
                matchedObject, totalField, DataCompositionSchemaTotalField.class, match.getFeature());
            return;
        }

        DataCompositionSchemaDataSetLink link = findAncestor(matchedObject, DataCompositionSchemaDataSetLink.class);
        if (link != null)
        {
            revealSimpleTableExPage(dcsEditor, "com._1c.g5.v8.dt.dcs.ui.links.Links", //$NON-NLS-1$
                matchedObject, link, DataCompositionSchemaDataSetLink.class, match.getFeature());
            return;
        }

        DataCompositionSchemaParameter parameter = findAncestor(matchedObject, DataCompositionSchemaParameter.class);
        if (parameter != null)
        {
            revealSimpleTableExPage(dcsEditor, "com._1c.g5.v8.dt.dcs.ui.parameters.Parameters", //$NON-NLS-1$
                matchedObject, parameter, DataCompositionSchemaParameter.class, match.getFeature());
            return;
        }

        NestedDataCompositionSchema nested = findAncestor(matchedObject, NestedDataCompositionSchema.class);
        if (nested != null)
        {
            revealSimpleTableExPage(dcsEditor, "com._1c.g5.v8.dt.dcs.ui.nested.NestedSchemas", //$NON-NLS-1$
                matchedObject, nested, NestedDataCompositionSchema.class, match.getFeature());
            return;
        }

        DataCompositionSchemaTemplateDescription template =
            findAncestor(matchedObject, DataCompositionSchemaTemplateDescription.class);
        if (template != null)
        {
            // Содержимое самого макета (Moxel/табличный документ) — отдельная, ещё не решённая
            // задача (см. согласование по табличным документам); здесь только строка списка макетов.
            revealSimpleTableExPage(dcsEditor, "com._1c.g5.v8.dt.dcs.ui.templates.Templates", //$NON-NLS-1$
                matchedObject, template, DataCompositionSchemaTemplateDescription.class, match.getFeature());
            return;
        }

        DataSet dataSet = findAncestor(matchedObject, DataSet.class);
        if (dataSet != null)
        {
            revealDataSet(dcsEditor, matchedObject, match, dataSet);
            return;
        }

    }

    private static void revealSettingsVariant(DataCompositionSchemaEditor dcsEditor, EObject matchedObject,
            SettingsVariant variant)
    {
        Settings settingsPage = null;
        for (EditorPage page : dcsEditor.getPages())
        {
            if (page instanceof Settings s)
            {
                settingsPage = s;
                break;
            }
        }
        if (settingsPage == null)
        {
            return;
        }

        activateEditorTabFor(settingsPage);

        // com._1c.g5.v8.dt.dcs.ui.settings.variants.Variants и com._1c.g5.v8.dt.common.ui.widgets.tableex.TableExViewer
        // из НЕвыгруженных (unexported) OSGi-пакетов — "Settings" виден (пакет .settings экспортирован),
        // а вложенные .settings.variants/.widgets.tableex — нет (NoClassDefFoundError при прямом обращении
        // к типу в байткоде). Поэтому дальше — только через рефлексию (Global.invoke/getField),
        // без объявления Variants/TableExViewer как типов.
        Object variantsObj = Global.invoke(settingsPage, "getVariants"); //$NON-NLS-1$
        Object viewerObj = variantsObj != null ? Global.invoke(variantsObj, "getViewer") : null; //$NON-NLS-1$
        if (viewerObj == null)
        {
            return;
        }

        Global.invoke(viewerObj, "setSelection", //$NON-NLS-1$
            (org.eclipse.jface.viewers.ISelection) new StructuredSelection(variant), true);
        String featureName = findRowFeatureName(matchedObject, SettingsVariant.class, null);
        if ("presentation".equals(featureName)) //$NON-NLS-1$
        {
            Object titleColIndex = Global.getField(variantsObj, "TITLE_COL_INDEX"); //$NON-NLS-1$
            if (titleColIndex instanceof Integer col)
                activateGridCell(viewerObj, col.intValue());
        }
    }

    /**
     * Общий случай: страница с одной {@code TableExViewer} и {@code public getViewer()}
     * (Links/Parameters/CalculatedFields/Resources/NestedSchemas/Templates — все extends
     * {@code EditorPageBase} → {@code Composite}, см. javap). Строка — {@code rowObject}; колонка —
     * по {@link #DCS_PAGE_COLUMN_BY_FEATURE}, если для этой страницы есть карта соответствий
     * (константы найдены не для всех страниц — там, где их нет, точная колонка не активируется,
     * только строка).
     */
    private static void revealSimpleTableExPage(DataCompositionSchemaEditor dcsEditor, String pageClassName,
            EObject matchedObject, EObject rowObject, Class<? extends EObject> rowType, EStructuralFeature matchFeature)
    {
        for (EditorPage page : dcsEditor.getPages())
        {
            if (!page.getClass().getName().equals(pageClassName))
                continue;
            if (page instanceof Control pageControl)
                activateEditorTabFor(pageControl);

            Object viewerObj = Global.invoke(page, "getViewer"); //$NON-NLS-1$
            if (viewerObj == null)
            {
                return;
            }
            String featureName = findRowFeatureName(matchedObject, rowType, matchFeature);

            Integer column = resolveDcsColumnIndex(page, pageClassName, featureName);
            scheduleSelectRowAndCell(viewerObj, rowObject, column, 0);
            return;
        }
    }

    /** См. {@link #DCS_PAGE_COLUMN_BY_FEATURE}/{@link #DCS_PAGE_COLUMN_LITERAL_BY_FEATURE}. */
    private static Integer resolveDcsColumnIndex(EditorPage page, String pageClassName, String featureName)
    {
        if (featureName == null)
            return null;
        Map<String, String> columnMap = DCS_PAGE_COLUMN_BY_FEATURE.get(pageClassName);
        String constantName = columnMap != null ? columnMap.get(featureName) : null;
        if (constantName != null)
        {
            Object colIdx = Global.getField(page, constantName);
            if (colIdx instanceof Integer col)
                return col;
            return null;
        }
        Map<String, Integer> literalMap = DCS_PAGE_COLUMN_LITERAL_BY_FEATURE.get(pageClassName);
        return literalMap != null ? literalMap.get(featureName) : null;
    }

    /**
     * {@code setSelection(...)} повторяется на КАЖДОЙ попытке (не только проверка
     * {@code getSelectionIndices()}) — содержимое таблицы страницы может появиться асинхронно
     * ПОСЛЕ переключения вкладки (найдено логированием: «Ресурсы» первое время после активации
     * вкладки не содержат ни одной строки, однократный {@code setSelection} впустую пропадает,
     * а повторный (на уже заполненной таблице) — срабатывает).
     */
    private static void scheduleSelectRowAndCell(Object viewerObj, EObject rowObject, Integer column, int attempt)
    {
        Object tableExObj = Global.invoke(viewerObj, "getTable"); //$NON-NLS-1$
        // Resources.getViewer() отдаёт не сам DataCompositionSchemaTotalField, а обёртку
        // ResourcesContentProvider$ResourceItem (поле "field") — см. декомпиляцию getElements().
        // Остальные простые страницы (CalculatedFields/Links/Parameters/NestedSchemas) отдают
        // модельные объекты напрямую, поэтому оборачивание нужно только здесь.
        Object selectionElement = rowObject;
        if (rowObject instanceof DataCompositionSchemaTotalField totalField && tableExObj != null)
        {
            Object resourceItem = findResourceItemForTotalField(tableExObj, totalField);
            if (resourceItem != null)
                selectionElement = resourceItem;
        }
        Global.invoke(viewerObj, "setSelection", //$NON-NLS-1$
            (org.eclipse.jface.viewers.ISelection) new StructuredSelection(selectionElement), true);
        Object selectionIndicesObj = tableExObj != null ? Global.invoke(tableExObj, "getSelectionIndices") : null; //$NON-NLS-1$
        if (!(selectionIndicesObj instanceof int[] selectionIndices) || selectionIndices.length == 0)
        {
            if (attempt < 15)
            {
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                    display.timerExec(50, () -> scheduleSelectRowAndCell(viewerObj, rowObject, column, attempt + 1));
                return;
            }
            Object itemCount = tableExObj != null ? Global.invoke(tableExObj, "getItemCount") : null; //$NON-NLS-1$
            Object isDisposed = tableExObj != null ? Global.invoke(tableExObj, "isDisposed") : null; //$NON-NLS-1$
            Object inputObj = Global.invoke(viewerObj, "getInput"); //$NON-NLS-1$
            return;
        }
        if (column != null)
        {
            Point cell = new Point(column.intValue(), selectionIndices[0]);
            Global.invoke(tableExObj, "setCellSelection", cell); //$NON-NLS-1$
        }
    }

    /**
     * Ищет {@code ResourcesContentProvider$ResourceItem}, оборачивающий {@code totalField}, среди
     * текущих строк грида ({@code TableEx.getItems()} → {@code Item.getData()} — публичный SWT API).
     * Сравнение по {@code getDataPath()} (обычная строка, стабильна в отличие от идентичности BM-
     * объектов, см. {@link #findRowFeatureName}), а не по типу/ссылке — иначе не отличить разные
     * ресурсы схемы друг от друга.
     */
    private static Object findResourceItemForTotalField(Object tableExObj, DataCompositionSchemaTotalField totalField)
    {
        Object itemsObj = Global.invoke(tableExObj, "getItems"); //$NON-NLS-1$
        if (!(itemsObj instanceof org.eclipse.swt.widgets.Item[] items))
            return null;
        String dataPath = totalField.getDataPath();
        for (org.eclipse.swt.widgets.Item item : items)
        {
            Object data = item.getData();
            if (data == null)
                continue;
            Object fieldObj = Global.getField(data, "field"); //$NON-NLS-1$
            if (fieldObj instanceof DataCompositionSchemaTotalField f
                && java.util.Objects.equals(f.getDataPath(), dataPath))
                return data;
        }
        return null;
    }

    /**
     * Вкладка «Наборы данных»: дерево наборов ({@code getDataSetsViewer()}, публичный
     * {@code TreeViewer} — обычный JFace-класс, всегда экспортирован, рефлексия не нужна) + (если
     * вхождение внутри «Поля» набора) отдельная под-панель полей, которая появляется только ПОСЛЕ
     * того, как набор данных станет выбранным (см. {@link #scheduleDataSetFieldReveal}).
     */
    private static void revealDataSet(DataCompositionSchemaEditor dcsEditor, EObject matchedObject,
            TextSearchModelMatch match, DataSet dataSet)
    {
        for (EditorPage page : dcsEditor.getPages())
        {
            if (!page.getClass().getName().equals("com._1c.g5.v8.dt.dcs.ui.datasets.DataSets")) //$NON-NLS-1$
                continue;
            if (page instanceof Control pageControl)
                activateEditorTabFor(pageControl);

            Object treeViewerObj = Global.invoke(page, "getDataSetsViewer"); //$NON-NLS-1$
            if (treeViewerObj instanceof TreeViewer treeViewer)
            {
                treeViewer.setSelection(new StructuredSelection(dataSet), true);
            }
            else
            {
            }

            DataSetField field = findAncestor(matchedObject, DataSetField.class);
            if (field != null)
                scheduleDataSetFieldReveal(page, matchedObject, match.getFeature(), field, 0);
            else if (match.getFeature() != null && "query".equals(match.getFeature().getName())) //$NON-NLS-1$
                scheduleDataSetQueryTextReveal(page, match, 0);
            return;
        }
    }

    /**
     * Текст запроса набора данных ({@code DataCompositionSchemaDataSetQuery.getQuery()}) — встроенный
     * Xtext-редактор ({@code DataSets.getQueryEditor(): org.eclipse.xtext.ui.editor.embedded.EmbeddedEditor}),
     * появляется/переключается на нужный набор асинхронно ПОСЛЕ выбора в дереве (см.
     * {@link #revealDataSet}) — повтор по таймеру, как {@link #scheduleDataSetFieldReveal}.
     * {@code EmbeddedEditor.getViewer()} — публичный {@code XtextSourceViewer extends SourceViewer}
     * (класс {@code SourceViewer} уже импортирован и используется для БСЛ-модулей в
     * {@link #scheduleLeftmostScrollForOpenedMatch} — тот же {@code getTextWidget()}).
     */
    private static void scheduleDataSetQueryTextReveal(Object dataSetsPage, TextSearchModelMatch match, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () -> {
            try
            {
                Object embeddedEditor = Global.invoke(dataSetsPage, "getQueryEditor"); //$NON-NLS-1$
                if (embeddedEditor == null)
                {
                    if (attempt < 15)
                        scheduleDataSetQueryTextReveal(dataSetsPage, match, attempt + 1);
                    else
                        {
                        }
                    return;
                }
                Object viewerObj = Global.invoke(embeddedEditor, "getViewer"); //$NON-NLS-1$
                if (!(viewerObj instanceof SourceViewer sourceViewer))
                {
                    return;
                }
                StyledText widget = sourceViewer.getTextWidget();
                if (widget == null || widget.isDisposed())
                {
                    return;
                }
                int offset = match.getTextOffset();
                int length = match.getTextLength();
                if (offset < 0 || offset > widget.getCharCount())
                {
                    if (attempt < 15)
                        scheduleDataSetQueryTextReveal(dataSetsPage, match, attempt + 1);
                    else
                        {
                        }
                    return;
                }
                sourceViewer.setSelectedRange(offset, length);
                sourceViewer.revealRange(offset, length);
            }
            catch (Throwable t)
            {
            }
        });
    }

    /**
     * Под-панель «Поля» набора данных ({@code DataSets.getCurrentFieldsViewer()}) появляется/
     * обновляется асинхронно ПОСЛЕ выбора набора данных в дереве — повтор по таймеру, как
     * {@link #scheduleLeftmostScrollForOpenedMatch}. Класс под-панели/её колонок
     * ({@code DataSetsFieldsViewerBase.FieldsColumn}, вложенный enum) — из НЕэкспортированного
     * пакета {@code .datasets.fields}, поэтому его {@code Class} берём через classloader УЖЕ
     * полученного чужого экземпляра ({@code fieldsViewerBase.getClass().getClassLoader()}), а не
     * через собственный импорт — тот же приём, что и весь остальной рефлексивный доступ здесь.
     */
    private static void scheduleDataSetFieldReveal(Object dataSetsPage, EObject matchedObject,
            EStructuralFeature matchFeature, DataSetField field, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () -> {
            try
            {
                Object fieldsViewerBase = Global.invoke(dataSetsPage, "getCurrentFieldsViewer"); //$NON-NLS-1$
                if (fieldsViewerBase == null)
                {
                    if (attempt < 10)
                        scheduleDataSetFieldReveal(dataSetsPage, matchedObject, matchFeature, field, attempt + 1);
                    else
                        {
                        }
                    return;
                }
                Object treeViewerObj = Global.invoke(fieldsViewerBase, "getViewer"); //$NON-NLS-1$
                if (treeViewerObj == null)
                {
                    return;
                }
                Global.invoke(treeViewerObj, "setSelection", //$NON-NLS-1$
                    (org.eclipse.jface.viewers.ISelection) new StructuredSelection(field), true);
                String featureName = findRowFeatureName(matchedObject, DataSetField.class, matchFeature);

                String enumConstantName = featureName != null ? DATASET_FIELD_COLUMN_BY_FEATURE.get(featureName) : null;
                if (enumConstantName == null)
                    return;
                Class<?> fieldsColumnEnumClass = fieldsViewerBase.getClass().getClassLoader()
                    .loadClass("com._1c.g5.v8.dt.dcs.ui.datasets.fields.DataSetsFieldsViewerBase$FieldsColumn"); //$NON-NLS-1$
                Object enumConstant = Global.invoke(fieldsColumnEnumClass, "valueOf", enumConstantName); //$NON-NLS-1$
                Object colIdxObj = Global.invoke(fieldsViewerBase, "getColumnIndex", enumConstant); //$NON-NLS-1$
                if (colIdxObj instanceof Integer col)
                    activateGridCell(treeViewerObj, col.intValue());
                else
                    {
                    }
            }
            catch (Throwable t)
            {
            }
        });
    }

    /**
     * Выделяет активную ячейку грида (не запуск inline-редактора — {@code editElement} требует
     * установленного {@code ColumnViewerEditor}, которого здесь, похоже, нет: редактирование
     * варианта настроек в этой таблице — через отдельный диалог/команду
     * ({@code VariantsEditHandler}/{@code SettingsSetTitleDialog}), не через grid-редактор;
     * вызов {@code editElement} молча ничего не делал). {@code TableEx.setCellSelection(Point)} —
     * штатный API самого грида для «активной ячейки» (аналог {@code FormTableInteraction}
     * в этом же плагине, но для чужого виджета). Работает и для {@code TableExViewer}, и для
     * {@code TableExTreeViewer} — оба отдают {@code TableEx} через {@code getTable()}.
     */
    private static void activateGridCell(Object viewerObj, int col)
    {
        scheduleActivateGridCell(viewerObj, col, 0);
    }

    /**
     * {@code getSelectionIndices()} сразу после {@code setSelection(...)} не всегда успевает
     * отразить выделение (грид/страница ещё не отрисовались — например, «Ресурсы» заполняются
     * асинхронно после переключения вкладки) — повтор по таймеру, как
     * {@link #scheduleLeftmostScrollForOpenedMatch}.
     */
    private static void scheduleActivateGridCell(Object viewerObj, int col, int attempt)
    {
        Object tableExObj = Global.invoke(viewerObj, "getTable"); //$NON-NLS-1$
        if (tableExObj == null)
        {
            return;
        }
        Object selectionIndicesObj = Global.invoke(tableExObj, "getSelectionIndices"); //$NON-NLS-1$
        if (!(selectionIndicesObj instanceof int[] selectionIndices) || selectionIndices.length == 0)
        {
            if (attempt < 10)
            {
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                    display.timerExec(50, () -> scheduleActivateGridCell(viewerObj, col, attempt + 1));
                return;
            }
            return;
        }
        Point cell = new Point(col, selectionIndices[0]);
        Global.invoke(tableExObj, "setCellSelection", cell); //$NON-NLS-1$
    }

    /**
     * Переключает верхнюю вкладку редактора СКД («Наборы данных»/«Настройки»/…) на ту, что
     * содержит {@code pageControl} — сам {@code DataCompositionSchemaEditor} не хранит свой
     * {@code CTabFolder} в поле (см. {@link #openDcsMatch}), поэтому ищем его обходом дерева
     * SWT-контролов вверх от страницы (публичный SWT API: {@code getParent()}/{@code getItems()}).
     */
    private static void activateEditorTabFor(Control pageControl)
    {
        if (pageControl == null || pageControl.isDisposed())
            return;
        for (Control parent = pageControl.getParent(); parent != null; parent = parent.getParent())
        {
            if (!(parent instanceof CTabFolder folder))
                continue;
            for (CTabItem item : folder.getItems())
            {
                Control itemControl = item.getControl();
                if (itemControl != pageControl && !isAncestorOf(itemControl, pageControl))
                    continue;
                folder.setSelection(item);
                Event event = new Event();
                event.item = item;
                event.widget = folder;
                folder.notifyListeners(SWT.Selection, event);
                return;
            }
            return;
        }
    }

    private static boolean isAncestorOf(Control ancestor, Control control)
    {
        if (ancestor == null)
            return false;
        for (Control cur = control; cur != null; cur = cur.getParent())
            if (cur == ancestor)
                return true;
        return false;
    }

    private static boolean isInsideDataCompositionSchema(EObject leaf)
    {
        for (EObject cur = leaf; cur != null; cur = cur.eContainer())
            if (cur instanceof DataCompositionSchema)
                return true;
        return false;
    }

    /** Управляемая форма — как и СКД, отдельный BM-ресурс ({@code eContainer()==null} у самой {@code Form}). */
    private static boolean isInsideForm(EObject leaf)
    {
        for (EObject cur = leaf; cur != null; cur = cur.eContainer())
            if (cur instanceof Form)
                return true;
        return false;
    }

    /**
     * Обычный реквизит/измерение/ресурс объекта конфигурации ({@code BasicFeature} — общий
     * интерфейс-предок {@code DbObjectAttribute} (реквизиты справочников/документов/...),
     * {@code RegisterAttribute}/{@code RegisterDimension}/{@code RegisterResource} (измерения и
     * ресурсы регистров) и т.п., подтверждено decompile/{@code javap} пакета
     * {@code com._1c.g5.v8.dt.metadata.mdclass}). Колонка «Свойство» в этом случае указывает на сам
     * реквизит/измерение/ресурс или на что-то внутри него (напр. «Ресурсы.Имя.Тип.Типы»).
     */
    private static boolean isInsideMdObjectMember(EObject leaf)
    {
        return findNearestMdObjectMember(leaf) != null;
    }

    /**
     * Ближайший к {@code leaf} (включая сам {@code leaf}) реквизит/измерение/ресурс МД-объекта.
     */
    private static EObject findNearestMdObjectMember(EObject leaf)
    {
        for (EObject cur = leaf; cur != null; cur = cur.eContainer())
            if (cur instanceof BasicFeature)
                return cur;
        return null;
    }

    /**
     * Вхождение внутри реквизита МД, но не в самом реквизите (Синоним.ru, Подсказка.ru, Тип.Типы…):
     * открывает ближайший {@code BasicFeature} через 1-arg {@code OpenHelper.openEditor} (как
     * {@link GoToDefinition#openTopLevelFormElement}) и фокусирует поле панели «Свойства».
     * Штатный {@code handleOpen} здесь не вызываем — иначе OpenHelper поднимает selection=leaf до
     * MdObject с feature={@code attributes} и редактор активирует группу «Реквизиты».
     *
     * <p>Порядок как у рабочего плоского случая (Комментарий): сначала {@code showView} +
     * {@link PropertyFieldFocus#schedule}, затем {@code openEditor} — панель подхватывает
     * выделение асинхронно, а цикл ожидания уже крутится. Если планировать фокус после
     * {@code openEditor}, палитра часто ещё не переключена на реквизит.
     *
     * @return {@code true}, если открытие обработано здесь (штатный {@code handleOpen} не нужен)
     */
    private static boolean openNestedMdObjectMemberMatch(EObject leaf, IWorkbenchPage workbenchPage,
            EStructuralFeature matchFeature)
    {
        EObject member = findNearestMdObjectMember(leaf);
        if (member == null || member == leaf)
            return false;
        Global.tempLog(PropertyFieldFocus.LOG_TOPIC, "openNested start leaf=" //$NON-NLS-1$
                + describeEObject(leaf) + " member=" + describeEObject(member) //$NON-NLS-1$
                + " matchFeature=" + featureName(matchFeature)); //$NON-NLS-1$
        try
        {
            workbenchPage.showView(IPageLayout.ID_PROP_SHEET);
        }
        catch (Exception e)
        {
            Global.tempLog(PropertyFieldFocus.LOG_TOPIC, "openNested showView: " + e); //$NON-NLS-1$
        }
        // Ждём палитру ДО openEditor — тот же порядок, что showView+schedule → handleOpen
        // для «Комментарий»; иначе первые попытки фокуса идут в ещё старую палитру.
        PropertyFieldFocus.schedule(workbenchPage, leaf, matchFeature);
        try
        {
            if (new OpenHelper(workbenchPage).openEditor(member) == null)
            {
                Global.tempLog(PropertyFieldFocus.LOG_TOPIC, "openNested openEditor=null, cancel focus"); //$NON-NLS-1$
                PropertyFieldFocus.cancel();
                return false;
            }
        }
        catch (RuntimeException e)
        {
            Global.tempLog(PropertyFieldFocus.LOG_TOPIC, "openNested openEditor: " + e.getMessage()); //$NON-NLS-1$
            PropertyFieldFocus.cancel();
            return false;
        }
        Global.tempLog(PropertyFieldFocus.LOG_TOPIC, "openNested openEditor ok, focus loop running"); //$NON-NLS-1$
        return true;
    }

    private static String describeEObject(EObject obj)
    {
        if (obj == null)
            return "null"; //$NON-NLS-1$
        String name = null;
        try
        {
            Object n = Global.invoke(obj, "getName"); //$NON-NLS-1$
            if (n != null)
                name = String.valueOf(n);
        }
        catch (Exception e)
        {
        }
        return obj.eClass().getName() + (name != null ? "(" + name + ")" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "@" + Integer.toHexString(System.identityHashCode(obj)); //$NON-NLS-1$
    }

    private static String featureName(EStructuralFeature feature)
    {
        return feature != null ? feature.getName() : "null"; //$NON-NLS-1$
    }

    /**
     * Ближайший к {@code leaf} (включая сам {@code leaf}) предок-реквизит/команда/параметр/элемент
     * формы ({@code FormAttribute}/{@code FormCommand}/{@code FormParameter}/{@code FormField}).
     * Имена элементов формы уникальны во ВСЁМ дереве формы (в отличие от более ранней версии,
     * искавшей элемент на фиксированной "глубине" — вложенность {@code FormField} может быть любой,
     * а самый внутренний элемент однозначно идентифицирует место вхождения). См.
     * {@link GoToDefinition#openTopLevelFormElement}.
     */
    private static EObject findNearestFormChild(EObject leaf)
    {
        for (EObject cur = leaf; cur != null; cur = cur.eContainer())
            if (cur instanceof FormField || cur instanceof FormAttribute
                || cur instanceof FormCommand || cur instanceof FormParameter)
                return cur;
        return null;
    }

    /** Табличный документ (Moxel) — как и СКД/форма, отдельный BM-ресурс. */
    private static boolean isInsideSpreadsheetDocument(EObject leaf)
    {
        for (EObject cur = leaf; cur != null; cur = cur.eContainer())
            if (cur instanceof SpreadsheetDocument)
                return true;
        return false;
    }

    /**
     * Открывает редактор справочной информации ({@code MdHelpContentEditor}) вместо редактора
     * объекта-владельца. Тот же вызов, что {@code OpenMdHelpContentAction}:
     * {@code OpenHelper.openEditor(mdObject, helpFeature)} — маршрутизация через
     * {@code objectEditorInformation feature="help"} в {@code md.help.ui}.
     *
     * @return {@code true}, если совпадение — справочная информация и редактор открыт
     */
    private static boolean openHelpContentMatch(Object matchObj, IWorkbenchPage workbenchPage)
    {
        if (matchObj == null || workbenchPage == null)
            return false;
        try
        {
            MdObject mdObject = null;
            TextSearchFileMatch fileMatch = null;
            if (matchObj instanceof TextSearchFileMatch fm)
            {
                if (!isHelpContentFile(fm.getFile()))
                    return false;
                mdObject = resolveMatchTopAsMdObject(matchObj);
                fileMatch = fm;
            }
            else
            {
                EObject leaf = resolveMatchLeaf(matchObj);
                if (leaf == null || !isInsideHelp(leaf))
                    return false;
                mdObject = resolveMatchTopAsMdObject(matchObj);
                if (mdObject == null)
                    mdObject = GoToDefinition.findContainingMdObject(leaf);
            }
            IEditorPart editor = openMdHelpEditor(mdObject, workbenchPage);
            if (editor == null)
                return false;
            if (fileMatch != null)
                scheduleRevealInHelpEditor(workbenchPage, editor,
                    fileMatch.getFileOffset(), Math.max(fileMatch.getTextLength(), 1), 0);
            return true;
        }
        catch (Exception e)
        {
            log("openHelpContentMatch: " + e); //$NON-NLS-1$
            return false;
        }
    }

    /** Файл справочной информации: {@code …/Help/<lang>.html}. */
    private static boolean isHelpContentFile(IFile file)
    {
        if (file == null)
            return false;
        IPath path = file.getProjectRelativePath();
        if (path == null || path.segmentCount() < 2)
            return false;
        if (!"html".equalsIgnoreCase(path.getFileExtension())) //$NON-NLS-1$
            return false;
        return "Help".equalsIgnoreCase(path.segment(path.segmentCount() - 2)); //$NON-NLS-1$
    }

    private static boolean isInsideHelp(EObject leaf)
    {
        for (EObject cur = leaf; cur != null; cur = cur.eContainer())
            if (cur instanceof Help || cur instanceof HelpPage)
                return true;
        return false;
    }

    private static MdObject resolveMatchTopAsMdObject(Object match)
    {
        EObject top = resolveMatchTopMdObject(match);
        return top instanceof MdObject md ? md : null;
    }

    /**
     * Как {@code OpenMdHelpContentAction.run()}: feature типа {@code Help} + 2-arg
     * {@link OpenHelper#openEditor(EObject, EStructuralFeature)}.
     *
     * @return открытый редактор или {@code null}
     */
    private static IEditorPart openMdHelpEditor(MdObject mdObject, IWorkbenchPage workbenchPage)
    {
        if (mdObject == null)
            return null;
        EReference helpFeature = findHelpFeature(mdObject.eClass());
        if (helpFeature == null)
            return null;
        try
        {
            return new OpenHelper(workbenchPage).openEditor(mdObject, helpFeature);
        }
        catch (RuntimeException e)
        {
            log("openMdHelpEditor: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Переключает {@code HtmlMultiPageEditor} на вкладку исходника HTML и выделяет вхождение
     * ({@code selectAndReveal}) — как {@code FileSearchResultsHook.revealMatchInEditor}.
     * Редактор/страницы поднимаются асинхронно — повтор до готовности {@code ITextEditor}.
     */
    private static void scheduleRevealInHelpEditor(IWorkbenchPage workbenchPage, IEditorPart opened,
            int offset, int length, int attempt)
    {
        if (workbenchPage == null || offset < 0)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 50 : 100;
        display.timerExec(delay, () -> {
            if (display.isDisposed())
                return;
            IEditorPart editor = opened;
            if (editor == null || editor.getSite() == null)
                editor = workbenchPage.getActiveEditor();
            ITextEditor textEditor = resolveHelpTextEditor(editor);
            if (textEditor == null)
            {
                if (attempt < 15)
                    scheduleRevealInHelpEditor(workbenchPage, opened, offset, length, attempt + 1);
                return;
            }
            activateHelpHtmlSourcePage(editor, textEditor);
            try
            {
                textEditor.selectAndReveal(offset, length);
                Object widgetObj = textEditor.getAdapter(Control.class);
                if (widgetObj instanceof StyledText widget)
                    SearchMatchScrollSupport.applyLeftmost(widget, offset, offset + Math.max(0, length));
            }
            catch (Exception e)
            {
                if (attempt < 15)
                    scheduleRevealInHelpEditor(workbenchPage, opened, offset, length, attempt + 1);
                else
                    log("scheduleRevealInHelpEditor: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    /** {@code HtmlMultiPageEditor.getAdapter(ITextEditor)} → встроенный {@code StructuredTextEditor}. */
    private static ITextEditor resolveHelpTextEditor(IEditorPart editor)
    {
        if (editor == null)
            return null;
        ITextEditor adapted = editor.getAdapter(ITextEditor.class);
        if (adapted != null)
            return adapted;
        return TextEditor.resolveTextEditor(editor);
    }

    /** Вкладки: WYSIWYG → HTML (StructuredTextEditor) → Preview — активируем страницу исходника. */
    private static void activateHelpHtmlSourcePage(IEditorPart multiPage, ITextEditor textEditor)
    {
        if (multiPage == null || textEditor == null)
            return;
        Object countObj = Global.invoke(multiPage, "getPageCount"); //$NON-NLS-1$
        if (!(countObj instanceof Integer count))
            return;
        for (int i = 0; i < count; i++)
        {
            Object page = Global.invoke(multiPage, "getEditor", Integer.valueOf(i)); //$NON-NLS-1$
            if (page == textEditor)
            {
                Global.invoke(multiPage, "setActivePage", Integer.valueOf(i)); //$NON-NLS-1$
                return;
            }
        }
    }

    /** Как {@code MdHelpUtil.findHelpFeature}: ссылка на {@link McorePackage.Literals#HELP}. */
    private static EReference findHelpFeature(EClass eClass)
    {
        if (eClass == null)
            return null;
        EClass helpClass = McorePackage.Literals.HELP;
        for (EReference ref : eClass.getEAllReferences())
        {
            if (ref.getEType() == helpClass)
                return ref;
        }
        return null;
    }

    /**
     * После {@code handleOpen} штатное горизонтальное позиционирование модуля не гарантирует
     * видимость начала строки (левая часть часто важнее правой — конец длинной строки без контекста
     * малополезен). Догоняем открывшийся BSL-редактор и подкручиваем по горизонтали к самой левой
     * позиции, при которой вхождение (текущее выделение) всё ещё целиком видно.
     *
     * <p>Редактор становится активным не мгновенно (открытие/активация части — асинхронный процесс
     * workbench) — повтор каждые 100мс до 10 раз, как и {@link #scheduleFinalAggregationReapplyAttempt}.
     */
    private static void scheduleLeftmostScrollForOpenedMatch(IWorkbenchPage workbenchPage, int attempt)
    {
        if (workbenchPage == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () -> {
            IEditorPart activeEditor = workbenchPage.getActiveEditor();
            BslXtextEditor bslEditor = GetRef.getActiveBslEditor(activeEditor);
            if (bslEditor == null)
            {
                if (attempt < 10)
                    scheduleLeftmostScrollForOpenedMatch(workbenchPage, attempt + 1);
                return;
            }
            ISourceViewer viewer = bslEditor.getInternalSourceViewer();
            if (!(viewer instanceof SourceViewer sourceViewer))
            {
                if (attempt < 10)
                    scheduleLeftmostScrollForOpenedMatch(workbenchPage, attempt + 1);
                return;
            }
            StyledText widget = sourceViewer.getTextWidget();
            if (widget == null || widget.isDisposed())
                return;
            Point selection = widget.getSelectionRange(); // x=начало, y=длина
            if (selection == null)
                return;
            SearchMatchScrollSupport.applyLeftmost(widget, selection.x, selection.x + Math.max(0, selection.y));
        });
    }

    /**
     * Переключение на уже готовый (ранее выполненный) поиск из выпадающей истории панели поиска
     * НЕ порождает {@code queryStarting}/{@code queryFinished} ({@link IQueryListener}) — штатный
     * {@code SearchView} лишь подменяет {@code ISearchResult} через
     * {@code AbstractTextSearchViewPage.setInput(ISearchResult, Object)}, которая вызывает
     * {@code fViewer.setInput(search)} на дереве (декомпиляция/исходники search-ui,
     * {@code .tmp/bundles/search-ui-source/org/eclipse/search/ui/text/AbstractTextSearchViewPage.java}).
     * Поэтому наш watch первой корневой строки ({@link #startFirstRootWatch}), запускаемый из
     * {@link #onSearchStarting}/{@link #onSearchFinished}, для этого случая не срабатывал — корень
     * не выделялся, наша таблица вхождений оставалась пустой.
     *
     * <p>Ловим смену input напрямую на {@code TreeViewer} — оборачиваем штатный
     * {@code ITreeContentProvider} прозрачным делегатом, который форвардит все вызовы оригиналу и
     * дополнительно реагирует на {@code inputChanged(viewer, oldInput, newInput)} (см.
     * {@link #installTreeInputChangeWatch}). Это единственная универсальная точка: она срабатывает
     * как при переключении из истории, так и при обычном старте нового поиска — во втором случае
     * {@code guardFirstRootSelection}/{@code searchQueryRunning} уже выставлены {@code onSearchStarting}
     * (тот вызывается раньше — по {@code queryStarting}, до фактической подмены input на странице),
     * поэтому здесь достаточно проверить, что оба флага ещё не взведены, чтобы не дублировать запуск.
     */
    private static void onTreeInputChanged()
    {
        Global.tempLog("search-tree-empty", "config.onTreeInputChanged: guard=" + guardFirstRootSelection //$NON-NLS-1$ //$NON-NLS-2$
            + " running=" + searchQueryRunning); //$NON-NLS-1$
        if (guardFirstRootSelection || searchQueryRunning)
            return; // уже обрабатывается обычным стартом поиска (onSearchStarting)
        guardFirstRootSelection = true;
        searchGeneration++;
        SAVED_TABLE_SELECTION_BY_VIEWER.clear();
        log("onTreeInputChanged: watch first root (переключение поиска из истории), gen=" + searchGeneration); //$NON-NLS-1$
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> startFirstRootWatch(0));
    }

    /** См. {@link #onTreeInputChanged}. Устанавливается один раз на {@code TreeViewer} (флаг на {@code Tree}). */
    private static void installTreeInputChangeWatch(TreeViewer treeViewer)
    {
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed() || tree.getData(INPUT_WATCH_HOOKED_KEY) != null)
            return;
        Object contentProviderObj = treeViewer.getContentProvider();
        if (!(contentProviderObj instanceof ITreeContentProvider original))
        {
            log("installTreeInputChangeWatch: contentProvider не ITreeContentProvider: " + contentProviderObj); //$NON-NLS-1$
            return;
        }
        treeViewer.setContentProvider(new ITreeContentProvider()
        {
            @Override
            public Object[] getElements(Object inputElement) { return original.getElements(inputElement); }

            @Override
            public Object[] getChildren(Object parentElement) { return original.getChildren(parentElement); }

            @Override
            public Object getParent(Object element) { return original.getParent(element); }

            @Override
            public boolean hasChildren(Object element) { return original.hasChildren(element); }

            @Override
            public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
            {
                original.inputChanged(viewer, oldInput, newInput);
                if (newInput != null && newInput != oldInput)
                    onTreeInputChanged();
            }

            @Override
            public void dispose() { original.dispose(); }
        });
        tree.setData(INPUT_WATCH_HOOKED_KEY, Boolean.TRUE);
        log("installTreeInputChangeWatch: OK"); //$NON-NLS-1$
    }

    private static void onSearchStarting()
    {
        searchQueryRunning = true;
        guardFirstRootSelection = true;
        searchGeneration++;
        SAVED_TABLE_SELECTION_BY_VIEWER.clear();
        log("onSearchStarting: watch first root, gen=" + searchGeneration); //$NON-NLS-1$
        // Панель не закрывается — Dispose не вызовется; сохранить ширины до прихода новых результатов
        // (иначе refreshMatchTable/setColumnHidden подтянет устаревшие значения из settings).
        saveMatchColumnStateOnUiThread();
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
     * {@code true}, если текущий поиск реально охватывает больше одного проекта — тогда
     * второй/третий корень дерева результатов (отдельный проект) может появиться значительно
     * позже первого, и включать {@link TreeExpander#notifyContentLoaded} по первому же
     * появившемуся корню преждевременно.
     * <p>
     * Реальный набор проектов запроса — {@code SearchQuery.searchInput} (внутренний, не
     * экспортируется, поэтому через рефлексию) → {@code TextSearchInput.getProjects()}. Дерево не
     * входит в белый список виртуальных ({@code TreeSearchViewPageLayout.createViewer()}: маска
     * {@code 2818} = BORDER|V_SCROLL|H_SCROLL|MULTI, без VIRTUAL), поэтому единственная реальная
     * точка риска — этот вызов из {@code startFirstRootWatch}.
     */
    private static boolean searchCoversMultipleProjects(TreeViewer treeViewer)
    {
        Object searchResult = treeViewer.getInput();
        if (searchResult == null)
            return false;
        Object query = Global.invoke(searchResult, "getQuery"); //$NON-NLS-1$
        if (query == null)
            return false;
        Object searchInput = Global.getField(query, "searchInput"); //$NON-NLS-1$
        if (searchInput == null)
            return false;
        Object projects = Global.invoke(searchInput, "getProjects"); //$NON-NLS-1$
        boolean multi = projects instanceof java.util.Set<?> set && set.size() > 1;
        log("searchCoversMultipleProjects: " + multi //$NON-NLS-1$
            + (projects instanceof java.util.Set<?> set2 ? " (" + set2.size() + ")" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return multi;
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
                // reveal у setSelection выше уже развернул путь до терминального узла, с которого
                // редиректим — сбрасываем и разворачиваем заново по своим правилам. Если поиск идёт
                // больше чем по одному проекту, второй/третий корень (проект) может появиться
                // заметно позже первого — тогда «единственный корень» был бы преждевременным.
                TreeExpander.resetExpansionAfterReveal(viewers.tree,
                    !searchCoversMultipleProjects(viewers.tree));
            }
            else
            {
                // Надёжный сигнал «результаты реально загружены» — собственный ретрай TreeExpander
                // (3 с) при установке хука может не успеть, если поиск идёт дольше. Здесь выделение
                // уже верное (редирект выше не потребовался), reveal не разворачивал ничего лишнего —
                // просто применяем правила поверх текущего состояния.
                if (!searchCoversMultipleProjects(viewers.tree))
                    TreeExpander.notifyContentLoaded(viewers.tree);
            }
            applyAggregationIfNeeded(viewers.tree, viewers.table,
                Collections.singletonList(firstRoot), Collections.emptyList(), "watchLoop"); //$NON-NLS-1$
            syncMatchTableToTree(viewers.tree);

            if (searchQueryRunning)
            {
                startFirstRootWatch(attempt + 1);
                return;
            }
            if (!guardFirstRootSelection)
                return;
            if (firstRoot.equals(viewers.tree.getStructuredSelection().getFirstElement()))
            {
                guardFirstRootSelection = false;
                scheduleFinalAggregationReapply(viewers.tree, viewers.table, firstRoot);
            }
            else if (attempt < 60)
                startFirstRootWatch(attempt + 1);
            else
            {
                guardFirstRootSelection = false;
                scheduleFinalAggregationReapply(viewers.tree, viewers.table, firstRoot);
            }
        });
    }

    /**
     * Временный фикс (баг: таблица иногда остаётся с узкими (нерекурсивными) результатами
     * штатного {@code changeSource}, хотя наш {@code applyAggregationIfNeeded} логирует успешный
     * вызов). Гипотеза: {@code changeSource} — асинхронная фоновая задача модели; штатный листенер
     * реагирует на САМОЕ ПЕРВОЕ (настоящее) событие выбора и запускает СВОЙ {@code changeSource}
     * (узкий) одновременно с нашим (широким, рекурсивным) — если штатная фоновая задача завершится
     * позже наших (мы вызываем агрегацию до 7+ раз за то же окно из {@code startFirstRootWatch}),
     * она может перезаписать таблицу узким результатом уже ПОСЛЕ того, как наш код отработал и
     * залогировал успех. Лечим не причину (внутренняя модель EDT нам недоступна), а следствие:
     * ещё один, финальный вызов агрегации с задержкой — чтобы наш результат гарантированно был
     * последним, даже если штатная (асинхронная) задача была медленнее. Задержка обязательна:
     * попытка убрать её через {@code asyncExec} (выполнение сразу после текущего цикла обработки
     * событий) вызвала регресс — штатный {@code changeSource} успевает сработать ПОЗЖЕ нашего
     * немедленного повтора и перебивает результат узким списком. 100мс — компромисс между 300мс
     * (эмпирически подтверждённые 10/10, но заметнее мигание) и 50мс (заметно короче, но
     * недостаточно — штатная задача иногда не успевает, гонка проигрывается); это подстраховка
     * (fallback) на время, пока не появится точный сигнал завершения штатной асинхронной задачи
     * (см. обсуждение — вариант с {@code IJobChangeListener}, требует согласования и проверки перед
     * реализацией).
     * Видимый побочный эффект (кратковременное мигание) остаётся, целенаправленно устранён не был
     * — попытка условной проверки «уже верно» (без задержки убрать лишний повтор) тоже вызвала
     * регресс (см. историю правок), а {@code setRedraw} для маскировки перерисовки запрещён.
     */
    private static void scheduleFinalAggregationReapply(TreeViewer treeViewer, TableViewer tableViewer,
            Object node)
    {
        scheduleFinalAggregationReapplyAttempt(treeViewer, tableViewer, node, 0);
    }

    /**
     * Узел дерева (getTableItems()/getChildren()) может ещё догружаться асинхронно уже ПОСЛЕ того,
     * как сам узел выбран и стабилен (issue: гонка со временем загрузки модели узла, а не с порядком
     * наших вызовов — подтверждено логом {@code guardFirstRoot}: тот же самый объект узла в одном
     * прогоне поиска давал 12 вхождений, а в другом — насовсем застревал на 2, потому что единственный
     * фиксированный отложенный вызов срабатывал раньше догрузки). Поэтому — не один вызов, а повтор
     * каждые 100мс до 10 раз, пока результат не станет непустым (штатное заполнение узла — одноразовое,
     * непустого результата достаточно, чтобы понять, что оно уже случилось).
     */
    private static void scheduleFinalAggregationReapplyAttempt(TreeViewer treeViewer, TableViewer tableViewer,
            Object node, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () -> {
            if (treeViewer.getTree() == null || treeViewer.getTree().isDisposed())
                return;
            if (tableViewer.getTable() == null || tableViewer.getTable().isDisposed())
                return;
            Object current = treeViewer.getStructuredSelection().getFirstElement();
            if (!node.equals(current))
                return; // пользователь уже кликнул на что-то другое — не мешаем
            applyAggregationIfNeeded(treeViewer, tableViewer,
                Collections.singletonList(node), copySavedSelection(tableViewer), "finalReapply"); //$NON-NLS-1$
            int count = syncMatchTableToTree(treeViewer);
            if (attempt < 10 && count <= 0)
                scheduleFinalAggregationReapplyAttempt(treeViewer, tableViewer, node, attempt + 1);
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

    /**
     * Строки для тулбарной команды {@link CreateDebuggerBreakpoints}: явно выбранные строки
     * в своей (SWT.MULTI) таблице вхождений {@link #cachedMatchTableViewer} — если ничего не выбрано
     * специально, все строки текущего выбора дерева (таблица уже синхронизирована с ним, см.
     * {@link #refreshMatchTable}). Только вхождения с файлом и номером строки (BSL-модули,
     * {@link TextSearchFileMatch}) становятся целями — остальные (ссылки/свойства модели без
     * номера строки) просто пропускаются. {@code null}, если эта страница сейчас не активна
     * (тулбар общий на панель — тогда пробуют другие хуки).
     */
    static List<CreateDebuggerBreakpoints.Target> currentBreakpointTargets(IViewPart view)
    {
        SearchViewViewers viewers = resolveViewers(view); // также проверяет, что эта страница активна
        if (viewers == null)
            return null;

        TableViewer matchViewer = cachedMatchTableViewer;
        Table matchTable = matchViewer != null ? matchViewer.getTable() : null;
        if (matchTable == null || matchTable.isDisposed())
            return null;

        // Явно выбранные строки — если нет, берём все строки текущего выбора дерева
        // (таблица уже отфильтрована/синхронизирована с ним через refreshMatchTable).
        TableItem[] items = matchTable.getSelectionCount() > 0 ? matchTable.getSelection() : matchTable.getItems();

        List<CreateDebuggerBreakpoints.Target> targets = new ArrayList<>();
        for (TableItem item : items)
        {
            if (item != null && item.getData() instanceof MatchRow row
                && row.file != null && row.lineNumber > 0)
                targets.add(new CreateDebuggerBreakpoints.Target(row.file, (int) row.lineNumber));
        }
        return targets;
    }

    @SuppressWarnings("unchecked")
    private static void collectTableItemsRecursively(Object node, List<Object> out)
    {
        Object ownItems = Global.invoke(node, "getTableItems"); //$NON-NLS-1$
        if (ownItems instanceof List<?> list)
            out.addAll((List<Object>) list);

        Object children = Global.invoke(node, "getChildren"); //$NON-NLS-1$
        if (children instanceof List<?> childList)
        {
            for (Object child : (List<Object>) childList)
                collectTableItemsRecursively(child, out);
        }
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
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 150;
        display.timerExec(delay, () -> {
            if (display.isDisposed())
                return;
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return; // настройка выключена, пока ждали появления страницы
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
            Global.tempLog("search-tree-empty", "config.tryPatch: activePage=" //$NON-NLS-1$ //$NON-NLS-2$
                + (activePage != null ? activePage.getClass().getName() + "@" + System.identityHashCode(activePage) : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            if (activePage == null)
            {
                log("tryPatch: activePage=null"); //$NON-NLS-1$
                return false;
            }
            if (!activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
                return true; // другой вид страницы результатов (не конфигурационный поиск) — не наш случай

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

            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return true; // фича выключена настройкой

            installTreeMatchCountLabelProvider(treeViewer);

            if (treeViewer.getTree().getData(HOOKED_KEY) != null)
                return true; // фича уже установлена для этого дерева

            if (NATIVE_TABLE_ENABLED)
            {
                installAggregationListener(treeViewer, tableViewer);
                installPathColumn(tableViewer);
                installTableCopyHandler(treeViewer, tableViewer);
            }
            TreeExpander.installWhitelisted(
                    TreeExpander.Target.SEARCH_CONFIG, treeViewer);
            installMatchTableSplitPane(view, activePage, treeLayout, treeViewer);
            CreateDebuggerBreakpoints.installToolbarAction(view);
            installTreeInputChangeWatch(treeViewer);

            treeViewer.getTree().setData(HOOKED_KEY, Boolean.TRUE);
            Global.tempLog("search-tree-empty", "config.tryPatch: PATCH OK treeItems=" //$NON-NLS-1$ //$NON-NLS-2$
                + treeViewer.getTree().getItemCount() + " input=" //$NON-NLS-1$
                + (treeViewer.getInput() != null ? treeViewer.getInput().getClass().getName() : "null")); //$NON-NLS-1$
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
                        // reveal у setSelection выше уже развернул путь до терминального узла,
                        // с которого редиректим — сбрасываем и разворачиваем заново по своим правилам.
                        TreeExpander.resetExpansionAfterReveal(treeViewer,
                            !searchCoversMultipleProjects(treeViewer));
                        // ВАЖНО: programmatic setSelection() не порождает новое post-selection
                        // событие (JFace фича — post-selection реагирует только на реальные SWT.Selection
                        // от мыши/клавиатуры), поэтому applyAggregationIfNeeded нужно вызвать явно здесь —
                        // иначе таблица остаётся с тем, что штатный код успел наложить по терминальному
                        // узлу ДО этого редиректа, при живом (уже правильном) выделении узла-проекта
                        // в дереве. Раньше на это надеялся только параллельный таймер startFirstRootWatch,
                        // который не всегда успевал/уже был выключен к этому моменту.
                        applyAggregationIfNeeded(treeViewer, tableViewer,
                            Collections.singletonList(firstRoot), copySavedSelection(tableViewer),
                            "redirectToFirstRoot"); //$NON-NLS-1$
                        syncMatchTableToTree(treeViewer);
                        return;
                    }
                }

                if (applyAggregationIfNeeded(treeViewer, tableViewer, selectedNodes,
                        copySavedSelection(tableViewer), "postListener")) //$NON-NLS-1$
                {
                    syncMatchTableToTree(treeViewer);
                    return;
                }
                syncMatchTableToTree(treeViewer);

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
        if (!NATIVE_TABLE_ENABLED)
            return false; // штатная таблица временно отключена — см. javadoc NATIVE_TABLE_ENABLED

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
        return sb.toString();
    }

    /** Путь для колонки «Путь» — короткий MD-путь без дублирования колонки «Свойство». */
    private static String formatPathForTableItem(Object tableItem, Object ownerNode)
    {
        String source;
        String fileFullName = fullNameFromTableItemFile(tableItem);
        String bmPath = bmTopObjectPathFromTableItem(tableItem);
        String path;
        // Файл → полное имя МД, как FileSearchResultsHook.resolveMdPath — но только когда
        // оно уточняет вложенный объект (Форма/Макет/…), а не суффикс модуля того же топа.
        if (preferFileFullName(fileFullName, bmPath))
        {
            path = fileFullName;
            source = "fileFullName"; //$NON-NLS-1$
        }
        else if (bmPath != null && !bmPath.isEmpty())
        {
            path = bmPath;
            source = "bmObject"; //$NON-NLS-1$
        }
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
        String result = GetRef.stripLowValueModuleSuffix(canonicalizeMdPath(path));
        if (result == null || result.isEmpty())
        {
            log("formatPathForTableItem: EMPTY source=" + source //$NON-NLS-1$
                + " ownerLabel=" + extractLabel(ownerNode)); //$NON-NLS-1$
        }
        return result != null ? result : ""; //$NON-NLS-1$
    }

    /**
     * Полное имя МД по {@code Match.getFile()} — тот же резолв, что колонка «Путь»
     * в результатах поиска по файлам ({@link GetRef#resolveFullNameOrNull}).
     */
    private static String fullNameFromTableItemFile(Object tableItem)
    {
        String rel = projectRelativePathFromTableItem(tableItem);
        if (rel == null || rel.isEmpty())
            return null;
        return GetRef.resolveFullNameOrNull(rel);
    }

    /**
     * {@code true}, если файловый путь задаёт вложенный МД-объект относительно BM-топа
     * ({@code Справочник.Валюты} → {@code Справочник.Валюты.Форма.ФормаЭлемента}), а не только
     * вид модуля того же объекта ({@code …МодульОбъекта}/{@code …МодульМенеджера}).
     */
    private static boolean preferFileFullName(String fileFullName, String bmPath)
    {
        if (fileFullName == null || fileFullName.isEmpty())
            return false;
        if (bmPath == null || bmPath.isEmpty())
            return true;
        String fileCanon = GetRef.stripLowValueModuleSuffix(canonicalizeMdPath(fileFullName));
        String bmCanon = GetRef.stripLowValueModuleSuffix(canonicalizeMdPath(bmPath));
        if (fileCanon == null || fileCanon.isEmpty())
            return false;
        if (bmCanon == null || bmCanon.isEmpty())
            return true;
        if (fileCanon.equals(bmCanon))
            return true;
        if (!fileCanon.startsWith(bmCanon + ".")) //$NON-NLS-1$
            return fileCanon.length() > bmCanon.length();
        String rest = fileCanon.substring(bmCanon.length() + 1);
        return rest.indexOf('.') > 0;
    }

    /** Убирает суффикс {@code :...} (номер строки штатной подписи) из MD-пути. */
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
            String path = GetRef.eObjectToFullName((EObject) bmObject);
            return appendOwnedChildSegmentIfMissing(path, match);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * Формы/макеты, принадлежащие объекту метаданных (Справочник.Форма.Список1 и т.п.), не являются
     * отдельным bm-топ-объектом с точки зрения {@code getMetadataTopObjectId()} — у вхождения внутри
     * них он резолвится к объекту-владельцу (напр. Справочнику), поэтому базовый путь не содержит
     * сегмент самой формы/макета. Однако собственный BM-ресурс формы/макета (корень {@code eContainer()}
     * цепочки от найденного вхождения) имеет URI вида {@code bm://Конфигурация/Catalog.Справочник1.Form.ФормаСписка.Form}
     * / {@code bm://Конфигурация/Catalog.Справочник1.Template.СхемаКомпоновки.Template} — содержит
     * полный путь включая тип+имя формы/макета, с одним лишним хвостовым сегментом-маркером вида
     * ({@code .Form}/{@code .Template}), дублирующим тип последней пары. Убрав этот хвост и прогнав
     * через {@link MdTypeMapping#bmFqnToRuFullName(String)} (тот же маппер, что и для остального пути),
     * получаем полный путь. Найдено логированием фактических {@code eResource().getURI()} в разных
     * случаях (формы, макеты объекта, общие макеты/формы, СКД).
     */
    /**
     * Резолвит "найденный объект" вхождения (BM/EMF-объект, где сработало совпадение) единообразно
     * для разных подклассов {@code Match} — панель «Поиск» (текстовый поиск, {@link TextSearchModelMatch})
     * и «Найти ссылки на объект» ({@link BmReferenceMatch}, где источник ссылки — {@code getSource()})
     * дают РАЗНЫЕ подклассы, но обе задачи (колонки «Путь»/«Свойство») по сути одна и та же —
     * определить, ГДЕ внутри модели (в т.ч. внутри формы/СКД/табл. документа) лежит вхождение.
     */
    private static EObject resolveMatchLeaf(Object match)
    {
        try
        {
            if (match instanceof TextSearchModelMatch tsm)
            {
                Optional<?> opt = tsm.resolveMatchObject();
                return opt.isPresent() && opt.get() instanceof EObject eObj ? eObj : null;
            }
            if (match instanceof BmReferenceMatch brm)
            {
                Optional<?> opt = brm.getSource().resolve();
                return opt.isPresent() && opt.get() instanceof EObject eObj ? eObj : null;
            }
            if (match instanceof BmObjectMatch bom)
            {
                Optional<?> opt = bom.resolve();
                return opt.isPresent() && opt.get() instanceof EObject eObj ? eObj : null;
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    /** Feature вхождения — см. {@link #resolveMatchLeaf}, тот же разнобой подклассов {@code Match}. */
    private static EStructuralFeature resolveMatchFeature(Object match)
    {
        if (match instanceof TextSearchModelMatch tsm)
            return tsm.getFeature();
        if (match instanceof BmReferenceMatch brm)
            return brm.getFeature();
        return null;
    }

    /**
     * МД-объект-владелец вхождения ({@code getMetadataTopObjectId()} + {@code resolveObjectById()} —
     * оба метода объявлены на базовом {@code Match}, {@code resolveObjectById} — protected, отсюда
     * рефлексия; тот же механизм, что уже верно резолвит колонку «Путь», см.
     * {@link #bmTopObjectPathFromTableItem}), работает единообразно для ЛЮБОГО подкласса {@code Match}.
     */
    private static EObject resolveMatchTopMdObject(Object match)
    {
        try
        {
            Object topIdObj = Global.invoke(match, "getMetadataTopObjectId"); //$NON-NLS-1$
            if (!(topIdObj instanceof Long topId) || topId < 0)
                return null;
            Object optObj = Global.invoke(match, "resolveObjectById", topId); //$NON-NLS-1$
            if (!(optObj instanceof Optional<?> opt) || opt.isEmpty())
                return null;
            return opt.get() instanceof EObject eObj ? eObj : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * BM-объекты, полученные РАЗНЫМИ резолвами (напр. {@code eContainer()} у одного и тот же
     * логический объект через {@link #resolveMatchTopMdObject}), — РАЗНЫЕ Java-инстансы ({@code !=}),
     * даже когда представляют один и тот же объект БД (нестабильность идентичности BM-объектов,
     * многократно всплывавшая в этой сессии). {@code bmGetId()} — стабильный идентификатор в рамках
     * одной транзакции, в отличие от ссылочной идентичности. Без этого сравнения обход в
     * {@link #hierarchicalPropertyPath} не останавливался на объекте-владельце (полученном ЭТИМ,
     * а не {@code eContainer()}, путём) и добавлял лишний ведущий сегмент с его именем.
     */
    static boolean sameBmObject(EObject a, EObject b)
    {
        if (a == b)
            return true;
        if (a instanceof com._1c.g5.v8.bm.core.IBmObject ba && b instanceof com._1c.g5.v8.bm.core.IBmObject bb)
            return ba.bmGetId() == bb.bmGetId();
        return false;
    }

    private static String appendOwnedChildSegmentIfMissing(String path, Object match)
    {
        if (path == null || path.isEmpty())
            return path;
        try
        {
            EObject leaf = resolveMatchLeaf(match);
            if (leaf == null)
                return path;
            String childFullName = ownedChildFullNameFor(leaf);
            return childFullName != null ? childFullName : path;
        }
        catch (Exception e)
        {
            return path;
        }
    }

    /**
     * Полное имя формы/макета объекта (напр. {@code Справочник.Справочник1.Форма.ФормаСписка}) по
     * URI собственного BM-ресурса найденного вхождения — см. {@link #appendOwnedChildSegmentIfMissing}.
     * Используется и для колонки «Путь», и для навигации ({@link #openDcsMatch}), т.к. для таких
     * вложенных объектов {@code getMetadataTopObjectId()} резолвится к владельцу, а не к форме/макету.
     *
     * @return полное русское имя формы/макета, или {@code null}, если вхождение не в таком объекте
     *         (обычный МД-объект, общий макет/форма — там владелец и так резолвится верно)
     */
    private static String ownedChildFullNameFor(EObject leaf)
    {
        EObject root = leaf;
        for (EObject cur = leaf; cur != null; cur = cur.eContainer())
            root = cur;
        Resource resource = root.eResource();
        if (resource == null)
            return null;
        URI uri = resource.getURI();
        if (uri == null || !"bm".equals(uri.scheme())) //$NON-NLS-1$
            return null;
        String uriPath = uri.path();
        if (uriPath == null)
            return null;
        if (uriPath.startsWith("/")) //$NON-NLS-1$
            uriPath = uriPath.substring(1);
        String[] parts = uriPath.split("\\."); //$NON-NLS-1$
        if (parts.length >= 5 && parts[parts.length - 1].equals(parts[parts.length - 3]))
            uriPath = uriPath.substring(0, uriPath.length() - parts[parts.length - 1].length() - 1);
        return MdTypeMapping.bmFqnToRuFullName(uriPath);
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
        String hierarchicalPath = hierarchicalPropertyPath(tableItem);
        if (hierarchicalPath != null && !hierarchicalPath.isEmpty())
            return hierarchicalPath;
        try
        {
            Object styled = Global.invoke(tableItem, "getPropertyText"); //$NON-NLS-1$
            if (styled instanceof StyledString)
                return stripLineNumberSuffix(((StyledString) styled).getString());
        }
        catch (Exception ignored) {}
        return ""; //$NON-NLS-1$
    }

    /** Убирает хвостовой {@code :123} — номер строки уже в колонке «Строка». */
    private static String stripLineNumberSuffix(String value)
    {
        if (value == null || value.isEmpty())
            return ""; //$NON-NLS-1$
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon >= value.length() - 1)
            return value.trim();
        String after = value.substring(colon + 1).trim();
        if (after.isEmpty())
            return value.trim();
        for (int i = 0; i < after.length(); i++)
        {
            if (!Character.isDigit(after.charAt(i)))
                return value.trim();
        }
        return value.substring(0, colon).trim();
    }

    /** Технические обёртки EMF, не показываемые отдельным сегментом пути (одна голая ссылка без смысловой нагрузки). */
    private static final Set<String> DCS_FEATURE_SKIP =
        Set.of("template", "localValue"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Русская подпись feature — не своя таблица переводов, а штатный механизм платформы
     * ({@code com._1c.g5.v8.dt.common.Functions.featureToLabel()}, тот же {@code Function<EStructuralFeature,String>},
     * которым сама панель поиска строит {@code IMatchItem.getPropertyText()} — см. декомпиляцию
     * {@code TreeTableItemFactory.getPropertyDescription(EObject, EStructuralFeature)}).
     * Пробелы убираются — по требованию пользователя сегменты пути идут без пробелов
     * («Макет.ВариантыНастроек.Основной.Представление.ru»).
     *
     * <p>Для Moxel (табличный документ) {@code featureToLabel()} ничего не находит — декомпиляция
     * показала, что он ищет строку по ключу {@code nsURI|EClass|feature} в реестре
     * {@code LocalizationManager}, а бандл {@code com._1c.g5.v8.dt.moxel.ui} регистрирует переводы
     * НЕ для реальных EMF-классов Moxel, а для отдельной descriptor-модели панели свойств
     * ({@code CellsProperties} и т.п., см. {@code moxel.ui/plugin.xml},
     * {@code objectDescriptors}/{@code localization.bundles}) — те же самые русские строки 1С
     * ("Текст", "Параметр" и т.п.), но под другим именем EClass. Поэтому для Moxel —
     * {@link #moxelFeatureLabel}, тоже через {@code LocalizationManager}, просто с ключом по
     * descriptor-классу, а не по самодельной таблице переводов.
     */
    private static String dcsFeatureLabel(EStructuralFeature feature)
    {
        String label = rawFeatureLabel(feature);
        return label != null ? toCamelCase(label) : null;
    }

    /**
     * Подпись feature КАК ЕСТЬ, без склейки слов {@link #toCamelCase} — именно в таком виде
     * («Вычисляемые поля», а не «ВычисляемыеПоля») подпись показывает панель «Свойства», поэтому
     * для поиска поля в панели ({@link PropertyFieldFocus}) нужен этот вариант, а не
     * {@link #dcsFeatureLabel} (тот собирает сегменты пути, где пробелы недопустимы).
     */
    /** Подпись признака для фокуса поля панели «Свойства» ({@link PropertyFieldFocus}). */
    static String featureLabelForFocus(EStructuralFeature feature)
    {
        return rawFeatureLabel(feature);
    }

    private static String rawFeatureLabel(EStructuralFeature feature)
    {
        if (feature == null)
            return null;
        if (DCS_FEATURE_SKIP.contains(feature.getName()))
            return null;
        // Moxel-соответствие — ПЕРВЫМ: Functions.featureToLabel() никогда не возвращает null/пусто —
        // при неудачном поиске в реестре локализации он сам подставляет StringUtils.nameToText(name)
        // (например "text" → "Text"), поэтому проверка "label == null" после его вызова никогда не
        // сработала бы как признак «перевода нет» (найдено логированием: 0 вызовов moxelFeatureLabel).
        String label = moxelFeatureLabel(feature);
        if (label == null || label.isBlank())
        {
            try
            {
                label = Functions.featureToLabel().apply(feature);
            }
            catch (Exception e)
            {
                label = null;
            }
        }
        if (label == null || label.isBlank())
            label = feature.getName();
        return label;
    }

    /**
     * Ключ реестра {@code LocalizationManager}: {@code feature-names|<nsURI>|<descriptor-EClass>|<feature>}
     * (см. декомпиляцию {@code FeatureNameLocalizationProvider.getKey}). {@code nsURI} и соответствие
     * «реальный EClass → descriptor-EClass» — из {@code com._1c.g5.v8.dt.moxel.ui/plugin.xml}
     * ({@code objectDescriptors}), не догадка. Расширять список по мере необходимости (сейчас нужен
     * только {@code Cell→CellsProperties} для «Текст»/«Параметр» ячейки).
     */
    private static final String MOXEL_FEATURE_NAMES_KEY_PREFIX =
        "feature-names|http://g5.1c.ru/v8/dt/moxel/content|"; //$NON-NLS-1$
    private static final Map<String, String> MOXEL_DESCRIPTOR_CLASS_BY_ECLASS = Map.of(
        "Cell", "CellsProperties" //$NON-NLS-1$ //$NON-NLS-2$
    );

    private static String moxelFeatureLabel(EStructuralFeature feature)
    {
        String eClassName = feature.getEContainingClass().getName();
        String descriptorClass = MOXEL_DESCRIPTOR_CLASS_BY_ECLASS.get(eClassName);
        if (descriptorClass == null)
            return null;
        try
        {
            String key = MOXEL_FEATURE_NAMES_KEY_PREFIX + descriptorClass + "|" + feature.getName(); //$NON-NLS-1$
            String value = LocalizationManager.getInstance().getString(key);
            return value;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * «Вычисляемые поля» → «ВычисляемыеПоля» (не просто удаление пробелов — второе и следующие
     * слова обычной русской фразы начинаются со строчной буквы, при склейке без капитализации
     * граница слов исчезает: «Вычисляемыеполя»).
     */
    private static String toCamelCase(String phrase)
    {
        StringBuilder sb = new StringBuilder(phrase.length());
        for (String word : phrase.split("\\s+")) //$NON-NLS-1$
        {
            if (word.isEmpty())
                continue;
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1)
                sb.append(word.substring(1));
        }
        return sb.toString();
    }

    /** EMap-ключ (с нуля) → видимый номер строки/колонки табличного документа (с единицы). */
    private static String oneBased(Object key)
    {
        return key instanceof Number n ? String.valueOf(n.intValue() + 1) : String.valueOf(key);
    }

    private static String dcsElementName(EObject node)
    {
        Object name = Global.invoke(node, "getName"); //$NON-NLS-1$
        return (name instanceof String s && !s.isBlank()) ? s : null;
    }

    /**
     * Полный путь до найденного вхождения внутри модели МД-объекта — не только внутри схемы
     * компоновки данных (СКД), управляемой формы (Form) или табличного документа (Moxel/
     * SpreadsheetDocument), но и для обычных вложенных реквизитов/элементов метаданных — например
     * «Макет.ВариантыНастроек.Основной.Представление.ru», «Элементы.Поле1.Заголовок.ru» (без
     * промежуточных контейнеров — имена элементов формы уникальны во всём дереве формы),
     * «Область(3,2).Текст.ru»/«Область(4,1).Параметр» или «Реквизиты.Валюта2.Типы» (без пробелов
     * внутри сегментов) — вместо штатной короткой подписи {@code IMatchItem.getPropertyText()}
     * (см. {@link #extractPropertyText}). Алгоритм не специфичен для СКД: обход
     * {@code eContainer()}/{@code eContainingFeature()} через штатный {@link Functions#featureToLabel()}
     * — общая EMF-инфраструктура, работает для любой поддерживаемой модели (проверка типа match —
     * только чтобы не трогать вхождения БСЛ-модулей/файлов, для которых нет смысла).
     *
     * <p>Терминальный сегмент: если найденный объект — запись {@code EMap} (например, конкретная
     * локаль в {@code LocalString.getContent()}/{@code Titled.getTitle()}), путь оканчивается её
     * ключом (кодом локали); иначе — подписью features самого найденного объекта.
     *
     * <p>Для остальных типов вхождений (BSL-модуль, файловый поиск и т.п.) возвращает {@code null} —
     * вызывающий код ({@link #extractPropertyText}) откатывается на старое поведение.
     */
    private static String hierarchicalPropertyPath(Object tableItem)
    {
        try
        {
            Object matchObj = Global.invoke(tableItem, "getData"); //$NON-NLS-1$
            if (!(matchObj instanceof TextSearchModelMatch) && !(matchObj instanceof BmReferenceMatch))
                return null;

            EObject leaf = resolveMatchLeaf(matchObj);
            if (leaf == null)
                return null;

            // Границей обхода вверх служит МД-объект-владелец (getMetadataTopObjectId(), тот же,
            // что уже верно резолвит колонку «Путь» — см. bmTopObjectPathFromTableItem) — единый
            // механизм для ЛЮБОГО вложенного вхождения, не только DCS/формы/табл. документа: для
            // обычного реквизита объекта (напр. Реквизиты.Валюта2.Типы) обход останавливается на
            // самом объекте-владельце (Справочнике), а для вхождений внутри формы/СКД/табл. документа
            // не мешает — они всё равно root СВОЕГО отдельного BM-ресурса (eContainer()==null),
            // так что обход останавливается там раньше, до Справочника, независимо от top.
            EObject top = resolveMatchTopMdObject(matchObj);

            List<String> terminal = new ArrayList<>();
            EObject walkStart;
            if (leaf instanceof java.util.Map.Entry<?, ?> entry)
            {
                // Для DCS Presentation (LocalStringMapEntry -> LocalString -> Presentation -> ...)
                // сегмент "Заголовок"/"Представление" даёт containingFeature обёртки Presentation при
                // проходе цикла ниже (её feature — не "content"). Но title-подобные EMap<String,String>
                // без обёртки (FormCommand/FormAttribute/FormField.getTitle()) хранятся ПРЯМО на
                // объекте — там нет такой обёртки, и без этой ветки терялся сегмент "Заголовок"
                // (было "КомандыФормы.Команда2.ru" вместо "КомандыФормы.Команда2.Заголовок.ru").
                EStructuralFeature entryFeature = leaf.eContainingFeature();
                if (entryFeature != null && !"content".equals(entryFeature.getName())) //$NON-NLS-1$
                {
                    String entryFeatureLabel = dcsFeatureLabel(entryFeature);
                    if (entryFeatureLabel != null)
                        terminal.add(entryFeatureLabel);
                }
                Object key = entry.getKey();
                if (key != null)
                    terminal.add(String.valueOf(key));
                walkStart = leaf.eContainer();
            }
            else
            {
                String featureLabel = dcsFeatureLabel(resolveMatchFeature(matchObj));
                if (featureLabel != null)
                    terminal.add(featureLabel);
                walkStart = leaf;
            }

            LinkedList<String> path = new LinkedList<>();
            Object pendingCellColumn = null;
            // Элементы формы (FormField) могут быть вложены рекурсивно (напр. колонка Код внутри
            // поля-таблицы Список — оба хранятся через одну и ту же containment-feature "items",
            // FormItemContainer.getItems()) — но имена элементов формы уникальны во ВСЁМ дереве
            // формы, поэтому промежуточные контейнеры (Список) в пути не нужны: "Элементы.Код",
            // а не "Элементы.Список.Элементы.Код". formItemNameEmitted отслеживает, что имя
            // самого внутреннего (ближайшего к вхождению) элемента уже добавлено — остальные имена
            // и повторные метки "Элементы" в этой же цепочке пропускаются.
            boolean formItemNameEmitted = false;
            for (EObject node = walkStart; node != null && !sameBmObject(node, top); node = node.eContainer())
            {
                // Табличный документ (Moxel): Cell/Row — сами по себе не EMF containment-узлы с
                // осмысленным именем/подписью, а лишь ЗНАЧЕНИЯ записей EMap
                // (SpreadsheetDocument.getRows()/Row.getCells(), ключи — номер строки/колонки).
                // Один сегмент "Область(строка,колонка)" собирается из ДВУХ ключей этих EMap-записей
                // вместо generic-меток по containment-feature.
                if (node instanceof Cell || node instanceof Row)
                    continue;
                if (node instanceof java.util.Map.Entry<?, ?> mapEntry)
                {
                    Object value = mapEntry.getValue();
                    if (value instanceof Cell)
                    {
                        pendingCellColumn = mapEntry.getKey();
                        continue;
                    }
                    if (value instanceof Row && pendingCellColumn != null)
                    {
                        // EMap-ключи (SpreadsheetDocument.getRows()/Row.getCells()) — с нуля,
                        // видимые номера строки/колонки в редакторе — с единицы.
                        path.addFirst("Область(" + oneBased(mapEntry.getKey()) + "," //$NON-NLS-1$ //$NON-NLS-2$
                            + oneBased(pendingCellColumn) + ")"); //$NON-NLS-1$
                        continue;
                    }
                }

                EStructuralFeature containingFeature = node.eContainingFeature();
                // ВАЖНО: без instanceof FormField — контейнер "Список" (таблица, тип Table)
                // содержит колонки ТОЖЕ через "items" (FormItemContainer), но САМ Table НЕ
                // реализует FormField (проверено декомпиляцией TableImpl) — проверка типа узла
                // ошибочно исключала таблицы-контейнеры из дедупликации, оставляя "Список" в пути.
                boolean isNestedFormItem = containingFeature != null
                    && "items".equals(containingFeature.getName()); //$NON-NLS-1$

                String name = dcsElementName(node);
                if (name != null && (!isNestedFormItem || !formItemNameEmitted))
                {
                    path.addFirst(name);
                    if (isNestedFormItem)
                        formItemNameEmitted = true;
                }
                String label = dcsFeatureLabel(containingFeature);
                if (label != null && (!isNestedFormItem || path.isEmpty() || !label.equals(path.getFirst())))
                    path.addFirst(label);
            }
            path.addAll(terminal);
            return path.isEmpty() ? null : String.join(".", path); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            log("hierarchicalPropertyPath: " + e); //$NON-NLS-1$
            return null;
        }
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

    /** Временная диагностика: краткое описание узла дерева для логов (метка + hasChildren). */
    private static String describeNodeForLog(Object node)
    {
        if (node == null)
            return "null"; //$NON-NLS-1$
        return "'" + extractLabel(node) + "'@" + System.identityHashCode(node) //$NON-NLS-1$ //$NON-NLS-2$
            + " hasChildren=" + hasChildren(node); //$NON-NLS-1$
    }

    private static String describeNodesForLog(List<Object> nodes)
    {
        if (nodes == null || nodes.isEmpty())
            return "[]"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("["); //$NON-NLS-1$
        for (int i = 0; i < nodes.size(); i++)
        {
            if (i > 0)
                sb.append(", "); //$NON-NLS-1$
            sb.append(describeNodeForLog(nodes.get(i)));
        }
        return sb.append(']').toString();
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
        StyledString styled = extractMatchStyledText(tableItem);
        return styled != null ? styled.getString() : ""; //$NON-NLS-1$
    }

    /**
     * Как {@link #extractMatchText}, но с подсветкой вхождения — тем же стилем, что и в панели
     * текстового поиска ({@link SmartMatchHighlight#textOnlyStyler}, цвет фильтра из настроек
     * плагина — не штатные стили 1С — для единообразия подсветки между обоими режимами поиска).
     * {@code TextSearchFileMatch} и {@code TextSearchModelMatch} имеют одинаковые по сигнатуре
     * {@code getText()}/{@code getTextOffset()}/{@code getTextLength()} (смещение/длина вхождения
     * в тексте) — читаются рефлексией единообразно для обоих типов. Для типов без текста вхождения
     * (напр. {@code BmObjectMatch}) — обычный текст без подсветки (штатный {@code getDecoratedText()}/
     * {@code getStyledText()}, но без его стилей).
     */
    private static StyledString extractMatchStyledText(Object tableItem)
    {
        Object match = Global.invoke(tableItem, "getData"); //$NON-NLS-1$
        Object textObj = match != null ? Global.invoke(match, "getText") : null; //$NON-NLS-1$
        if (!(textObj instanceof String text))
        {
            StyledString plain = plainMatchStyledText(tableItem);
            return plain != null ? highlightSearchedObjectOccurrences(plain.getString()) : null;
        }

        Object offObj = Global.invoke(match, "getTextOffset"); //$NON-NLS-1$
        Object lenObj = Global.invoke(match, "getTextLength"); //$NON-NLS-1$
        if (!(offObj instanceof Integer off) || !(lenObj instanceof Integer len)
            || len <= 0 || off < 0 || off > text.length())
            return new StyledString(text);
        int end = Math.min(off + len, text.length());

        // Многострочный текст (например запрос СКД/динамического списка) — match.getText() отдаёт
        // ВЕСЬ текст целиком, а не одну строку (как для БСЛ, где вхождение уже приходит построчно) —
        // SWT-ячейка таблицы показывает только первую строку, остальное обрезается визуально. Поэтому
        // вырезаем именно ту строку, где реально произошло совпадение, а не весь текст с начала.
        int lineStart = text.lastIndexOf('\n', Math.max(off - 1, 0)) + 1; //$NON-NLS-1$
        int lineEndNl = text.indexOf('\n', end); //$NON-NLS-1$
        int lineEnd = lineEndNl < 0 ? text.length() : lineEndNl;
        while (lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r')
            lineEnd--;
        String rawLine = text.substring(lineStart, lineEnd);
        int leadWs = 0;
        while (leadWs < rawLine.length() && Character.isWhitespace(rawLine.charAt(leadWs)))
            leadWs++;
        int trailWs = 0;
        while (trailWs < rawLine.length() - leadWs && Character.isWhitespace(rawLine.charAt(rawLine.length() - 1 - trailWs)))
            trailWs++;
        String line = rawLine.substring(leadWs, rawLine.length() - trailWs);
        int relOff = off - lineStart - leadWs;
        int relEnd = relOff + (end - off);
        relOff = Math.max(0, Math.min(relOff, line.length()));
        relEnd = Math.max(relOff, Math.min(relEnd, line.length()));

        TableViewer matchViewer = cachedMatchTableViewer;
        Control styleContext = matchViewer != null ? matchViewer.getTable() : null;
        StyledString ss = new StyledString();
        // plainStyler(), а не без стиля вообще — иначе у "голого" куска текста нет ни одного
        // StyleRange (см. SmartMatchHighlight.plainStyler()).
        if (relOff > 0)
            ss.append(line.substring(0, relOff), SmartMatchHighlight.plainStyler());
        if (relEnd > relOff)
            ss.append(line.substring(relOff, relEnd), SmartMatchHighlight.textOnlyStyler(styleContext));
        if (relEnd < line.length())
            ss.append(line.substring(relEnd), SmartMatchHighlight.plainStyler());
        return ss;
    }

    /** Текст без подсветки — из штатного {@code getDecoratedText()}/{@code getStyledText()}, без его стилей. */
    private static StyledString plainMatchStyledText(Object tableItem)
    {
        try
        {
            Object styled = Global.invoke(tableItem, "getDecoratedText"); //$NON-NLS-1$
            if (styled == null)
                styled = Global.invoke(tableItem, "getStyledText"); //$NON-NLS-1$
            if (styled instanceof StyledString ss)
                return new StyledString(ss.getString());
        }
        catch (Exception ignored) {}
        return null;
    }

    /**
     * Подсвечивает вхождения {@link #cachedSearchedObjectSimpleName} в тексте структурных
     * совпадений ("Найти ссылки на объект" — {@code BmReferenceMatch}/{@code BslReferenceMatch}),
     * у которых, в отличие от текстового поиска, НЕТ готового диапазона от 1С: у {@code calculate()}
     * в {@code BslResourceMatchTreeTableItem} offset/length всегда {@code OptionalInt.empty()}
     * (декомпиляция search-ui). Подсвечиваем ВСЕ непересекающиеся вхождения простого имени —
     * по согласованию с пользователем, раз штатного диапазона нет.
     */
    private static StyledString highlightSearchedObjectOccurrences(String text)
    {
        if (text == null)
            return new StyledString(""); //$NON-NLS-1$
        String name = cachedSearchedObjectSimpleName;
        if (name == null || name.isEmpty())
            return new StyledString(text);
        TableViewer matchViewer = cachedMatchTableViewer;
        Control styleContext = matchViewer != null ? matchViewer.getTable() : null;
        StyledString ss = new StyledString();
        int pos = 0;
        int idx;
        while ((idx = text.indexOf(name, pos)) >= 0)
        {
            if (idx > pos)
                ss.append(text.substring(pos, idx), SmartMatchHighlight.plainStyler());
            ss.append(text.substring(idx, idx + name.length()), SmartMatchHighlight.textOnlyStyler(styleContext));
            pos = idx + name.length();
        }
        if (pos < text.length())
            ss.append(text.substring(pos), SmartMatchHighlight.plainStyler());
        return ss;
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
            .compile("\\(\\s*(\\d+)\\s+соответ") //$NON-NLS-1$
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
        Global.log("ConfigSearchResults", message); //$NON-NLS-1$
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    public ConfigSearchResultsHook() {}

    /**
     * Активация конкретного поля в панели «Свойства» после открытия вхождения, найденного в
     * реквизите/измерении/ресурсе МД-объекта (см. {@link #isInsideMdObjectMember}). Такие дочерние
     * объекты не имеют собственного редактора — их свойства правятся ТОЛЬКО в панели «Свойства»,
     * поэтому после {@code handleOpen} (открыл редактор владельца и выделил сам реквизит) остаётся
     * поставить курсор в то поле, на которое указывает колонка «Свойство» результата поиска:
     * для «Ресурсы.ВажностьПроблемы.Тип.Типы» — в поле «Тип».
     *
     * <p>Панель — AEF2-сцена ({@code com._1c.g5.properties.ui.PropertySheetPage}: {@code getScene()},
     * {@code getPaletteModel()}), её бандлы в {@code Require-Bundle} плагина не заявлены, поэтому
     * весь доступ — рефлексией через {@link Global#invoke}/{@link Global#getField}, как и в
     * {@code PropertySheetControlInterop}.
     *
     * <p>Фокус ставится штатным механизмом AEF, а не «руками» по SWT-контролу:
     * {@code StandardComponent.setFocus()} (public, без аргументов) кладёт в очередь сцены
     * {@code FocusEvent(первый viewModel)}, который {@code com._1c.g5.aef2.views.View} и отрабатывает
     * (подтверждено {@code javap -c} по {@code com._1c.g5.aef2.standard_16.1.100}). Это одинаково
     * работает и для LWT-, и для SWT-рендерера панели — в отличие от {@code Control.setFocus()},
     * который для LWT-полей бесполезен (они рисуются на общем canvas и не являются SWT-контролами).
     *
     * <p>Поле ищется по EMF-признаку, а не по подписи («Тип») — подписи не уникальны. Источник
     * связи «компонент → признак» — карта {@code componentToDefinitionMap} построителя палитры
     * (см. {@link #findFieldComponent}).
     */
    /**
     * Активация поля панели «Свойства». Package-visible: второй потребитель —
     * {@link ProblemViewPropertyFocusHook} (ошибки битых ссылок на картинки).
     * При появлении третьего потребителя — вынести в {@code PropertyFieldFocus.java}.
     */
    static final class PropertyFieldFocus
    {
        /** Панель наполняется асинхронно (MdPropertySheetPage ведёт свой прогресс) — ждём до ~6с. */
        private static final int MAX_ATTEMPTS = 40;
        private static final int RETRY_DELAY_MS = 150;
        /** Тема {@link Global#tempLog} — см. {@code .tmp/temp-logs/propfocus.log}. */
        static final String LOG_TOPIC = "propfocus"; //$NON-NLS-1$

        /**
         * Метка текущего цикла ожидания. Каждое новое открытие вхождения обесценивает предыдущий
         * цикл: без этого циклы от нескольких открытий подряд работают ПАРАЛЛЕЛЬНО (в логе видно по
         * перемешанным номерам попыток) и дерутся за фокус — старый цикл продолжает ставить фокус
         * уже после того, как панель переключилась на другой объект.
         */
        private static volatile Object activeToken;

        private PropertyFieldFocus() {}

        /** Обесценить текущий цикл ожидания (открытие сорвалось / заменено). */
        static void cancel()
        {
            activeToken = new Object();
            Global.tempLog(LOG_TOPIC, "cancel"); //$NON-NLS-1$
        }

        /**
         * Запуск ожидания и активации поля.
         *
         * @param leaf найденный объект (может быть как сам реквизит, так и вложенный в него объект)
         * @param matchFeature признак вхождения; для вложенного пути вроде
         *        «ПутьКДанным.Сегменты» поле панели — первый уровень цепочки, не последний сегмент
         */
        static void schedule(IWorkbenchPage workbenchPage, EObject leaf, EStructuralFeature matchFeature)
        {
            if (workbenchPage == null || leaf == null)
            {
                Global.tempLog(LOG_TOPIC, "schedule skip: page/leaf null"); //$NON-NLS-1$
                return;
            }
            EObject member = nearestMember(leaf);
            List<EStructuralFeature> chain = featureChainTopDown(leaf, matchFeature);
            Global.tempLog(LOG_TOPIC, "schedule leaf=" + describeEObject(leaf) //$NON-NLS-1$
                    + " member=" + describeEObject(member) //$NON-NLS-1$
                    + " matchFeature=" + featureName(matchFeature) //$NON-NLS-1$
                    + " chain=" + describeFeatureChain(chain)); //$NON-NLS-1$
            if (member == null || chain.isEmpty())
            {
                Global.tempLog(LOG_TOPIC, "schedule skip: member/chain empty"); //$NON-NLS-1$
                return;
            }
            Object token = new Object();
            activeToken = token;
            retry(workbenchPage, member, chain, 0, token);
        }

        /**
         * Прямой фокус: объект палитры и признак уже известны (маркер проверки и т.п.),
         * без вычисления {@link #nearestMember}/{@link #featureChainTopDown}.
         */
        static void scheduleExact(IWorkbenchPage workbenchPage, EObject member, EStructuralFeature feature)
        {
            if (workbenchPage == null || member == null || feature == null)
            {
                Global.tempLog(LOG_TOPIC, "scheduleExact skip: page/member/feature null"); //$NON-NLS-1$
                return;
            }
            Global.tempLog(LOG_TOPIC, "scheduleExact member=" + describeEObject(member) //$NON-NLS-1$
                    + " feature=" + featureName(feature)); //$NON-NLS-1$
            Object token = new Object();
            activeToken = token;
            retry(workbenchPage, member, List.of(feature), 0, token);
        }

        /**
         * Объект, чьи свойства покажет панель: реквизит МД ({@code BasicFeature}), элемент формы
         * ({@link FormItem} — группа/кнопка/поле/декорация), команда/реквизит/параметр формы.
         * {@code FormItem} нужен для картинок на {@code PopupGroupExtInfo}: палитра показывает
         * группу, а не ExtInfo.
         */
        private static boolean isPanelMember(EObject obj)
        {
            return obj instanceof BasicFeature || obj instanceof FormItem || obj instanceof FormAttribute
                || obj instanceof FormCommand || obj instanceof FormParameter;
        }

        private static EObject nearestMember(EObject leaf)
        {
            for (EObject cur = leaf; cur != null; cur = cur.eContainer())
                if (isPanelMember(cur))
                    return cur;
            return null;
        }

        /**
         * Признаки от объекта панели к вхождению, сверху вниз. Панель «Свойства» показывает
         * только первый уровень: для «Элементы.ИсторияСтатусовТаблица.ДанныеКартинкиСтроки.Сегменты»
         * цепочка {@code [rowPictureDataPath, segments]}, поле панели — {@code ДанныеКартинкиСтроки}.
         * Для «Ресурсы.ВажностьПроблемы.Тип.Типы» — {@code [type, types]}, поле — «Тип».
         * Если вхождение прямо в объекте панели (Имя/Комментарий) — один признак вхождения.
         */
        private static List<EStructuralFeature> featureChainTopDown(EObject leaf,
                EStructuralFeature matchFeature)
        {
            List<EStructuralFeature> nested = new ArrayList<>();
            for (EObject cur = leaf; cur != null && !isPanelMember(cur); cur = cur.eContainer())
            {
                EStructuralFeature containing = cur.eContainingFeature();
                if (containing != null)
                    nested.add(containing);
            }
            Collections.reverse(nested);
            List<EStructuralFeature> chain = new ArrayList<>(nested);
            if (matchFeature != null && (chain.isEmpty() || chain.get(chain.size() - 1) != matchFeature))
                chain.add(matchFeature);
            return chain;
        }

        private static String describeFeatureChain(List<EStructuralFeature> chain)
        {
            if (chain == null || chain.isEmpty())
                return "empty"; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            for (EStructuralFeature feature : chain)
            {
                if (sb.length() > 0)
                    sb.append('.');
                sb.append(featureName(feature));
            }
            return sb.toString();
        }

        private static void retry(IWorkbenchPage workbenchPage, EObject member,
                List<EStructuralFeature> chain, int attempt, Object token)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.timerExec(RETRY_DELAY_MS, () -> {
                if (token != activeToken)
                {
                    Global.tempLog(LOG_TOPIC, "retry aborted stale token attempt=" + attempt); //$NON-NLS-1$
                    return;
                }
                try
                {
                    if (tryFocus(workbenchPage, member, chain, attempt))
                        return;
                }
                catch (Throwable t)
                {
                    Global.tempLog(LOG_TOPIC, "tryFocus throw attempt=" + attempt + " " + t); //$NON-NLS-1$ //$NON-NLS-2$
                    Global.logError("ConfigSearchResults", "PropertyFieldFocus.tryFocus", t); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (attempt + 1 < MAX_ATTEMPTS)
                    retry(workbenchPage, member, chain, attempt + 1, token);
                else
                    Global.tempLog(LOG_TOPIC, "give up after " + MAX_ATTEMPTS + " attempts chain=" //$NON-NLS-1$ //$NON-NLS-2$
                            + describeFeatureChain(chain) + " member=" + describeEObject(member)); //$NON-NLS-1$
            });
        }

        private static boolean tryFocus(IWorkbenchPage workbenchPage, EObject member,
                List<EStructuralFeature> chain, int attempt)
        {
            IViewPart view = findPropertySheetView(workbenchPage);
            Object page = view != null ? PropertyNameIdentifierHook.resolvePropertySheetPage(view) : null;
            if (page == null)
            {
                Global.tempLog(LOG_TOPIC, "attempt=" + attempt + " no property sheet page view=" //$NON-NLS-1$ //$NON-NLS-2$
                        + (view != null ? view.getClass().getSimpleName() : "null")); //$NON-NLS-1$
                return false;
            }
            // Панель ещё может показывать ПРЕДЫДУЩИЙ объект — тогда нужное поле найдётся, но не то.
            if (!paletteShowsObject(page, member))
            {
                Global.tempLog(LOG_TOPIC, "attempt=" + attempt + " palette not yet on member=" //$NON-NLS-1$ //$NON-NLS-2$
                        + describeEObject(member) + " palette=" + describePaletteObjects(page)); //$NON-NLS-1$
                return false;
            }

            // Поле «Тип» в панели «Свойства» перекрыто нашим же SWT-оверлеем (TypeComboOverlayHook):
            // видимый ввод — его Text, а штатный LightCombo под ним только визуально закрыт.
            // Для таких свойств штатный AEF-путь НЕ годится и не является запасным: он поставил бы
            // фокус в невидимый контрол под оверлеем — причём успешно и на первой же попытке,
            // прекратив ожидание раньше, чем оверлей вообще успеет присоединиться. Поэтому здесь
            // либо оверлей, либо ничего: если его нет (составной тип — комбобокса под ним нет
            // вовсе), то фокусировать и нечего.
            String label = chain.isEmpty() ? null : featureLabelForFocus(chain.get(0));
            if (TypeComboOverlayHook.coversProperty(label))
            {
                boolean overlayFocused = TypeComboOverlayHook.focusPropertyOverlay(view, label);
                Global.tempLog(LOG_TOPIC, "attempt=" + attempt + " typeOverlay label=" + label //$NON-NLS-1$ //$NON-NLS-2$
                        + " ok=" + overlayFocused); //$NON-NLS-1$
                return overlayFocused;
            }

            Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
            if (scene == null)
            {
                Global.tempLog(LOG_TOPIC, "attempt=" + attempt + " scene=null"); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
            Object fieldComponent = findFieldComponent(scene, chain);
            if (fieldComponent == null)
            {
                Global.tempLog(LOG_TOPIC, "attempt=" + attempt + " field not found chain=" //$NON-NLS-1$ //$NON-NLS-2$
                        + describeFeatureChain(chain) + " label=" + label); //$NON-NLS-1$
                return false;
            }
            boolean focused = focusFieldComponent(scene, fieldComponent);
            Global.tempLog(LOG_TOPIC, "attempt=" + attempt + " focus chain=" + describeFeatureChain(chain) //$NON-NLS-1$ //$NON-NLS-2$
                    + " label=" + label + " component=" //$NON-NLS-1$ //$NON-NLS-2$
                    + fieldComponent.getClass().getSimpleName() + " ok=" + focused); //$NON-NLS-1$
            return focused;
        }

        private static String describePaletteObjects(Object page)
        {
            Object paletteModel = Global.invoke(page, "getPaletteModel"); //$NON-NLS-1$
            Object objects = paletteModel != null ? Global.invoke(paletteModel, "getObjects") : null; //$NON-NLS-1$
            if (!(objects instanceof Iterable<?> iterable))
                return "none"; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Object obj : iterable)
            {
                if (n > 0)
                    sb.append(',');
                sb.append(obj instanceof EObject eObj ? describeEObject(eObj) : String.valueOf(obj));
                if (++n >= 3)
                {
                    sb.append(",…"); //$NON-NLS-1$
                    break;
                }
            }
            return n == 0 ? "empty" : sb.toString(); //$NON-NLS-1$
        }

        /**
         * View панели «Свойства». Первая версия искала только по {@code Global.PROPERTIES_SHEET_ID}
         * ({@code org.eclipse.ui.views.properties.PropertySheet}), тогда как панель показывается по
         * ДРУГОМУ id — {@code IPageLayout.ID_PROP_SHEET} ({@code org.eclipse.ui.views.PropertySheet}),
         * и всегда получала {@code null} (лог {@code propfocus}: {@code page=<null>}). Проверка
         * самого признака «это панель свойств» — общая с остальными хуками панели
         * ({@code PropertyNameIdentifierHook.isPropertySheetView}), чтобы id не расходились.
         */
        private static IViewPart findPropertySheetView(IWorkbenchPage workbenchPage)
        {
            for (IViewReference ref : workbenchPage.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (PropertyNameIdentifierHook.isPropertySheetView(view))
                    return view;
            }
            return null;
        }

        /**
         * Панель уже переключилась на нужный объект. {@code PropertyPaletteModel.getObjects()} —
         * показываемые объекты; сравнение — {@link ConfigSearchResultsHook#sameBmObject} (BM-объект
         * из результатов поиска и из редактора — разные Java-инстансы с одним {@code bmGetId()}).
         */
        private static boolean paletteShowsObject(Object page, EObject member)
        {
            Object paletteModel = Global.invoke(page, "getPaletteModel"); //$NON-NLS-1$
            Object objects = paletteModel != null ? Global.invoke(paletteModel, "getObjects") : null; //$NON-NLS-1$
            if (!(objects instanceof Iterable))
                return false;
            for (Object obj : (Iterable<?>)objects)
                if (obj instanceof EObject eObj && sameShownObject(eObj, member))
                    return true;
            return false;
        }

        /**
         * {@link ConfigSearchResultsHook#sameBmObject} сравнивает по {@code bmGetId()} и работает
         * только для {@code IBmObject} (МД-объекты и их реквизиты), иначе скатывается к сравнению
         * ссылок. Элемент формы — вложенный EObject внутри BM-ресурса формы, и объект из результатов
         * поиска с объектом, выделенным в редакторе, — разные инстансы: {@code ==} не сработает.
         * Поэтому запасной вариант — сравнение EMF-URI (ресурс + фрагмент), одинакового у обоих.
         */
        private static boolean sameShownObject(EObject a, EObject b)
        {
            if (sameBmObject(a, b))
                return true;
            try
            {
                URI uriA = EcoreUtil.getURI(a);
                return uriA != null && uriA.equals(EcoreUtil.getURI(b));
            }
            catch (Exception e)
            {
                return false;
            }
        }

        /**
         * Компонент-редактор поля по EMF-признаку. Связь «компонент → определение поля» хранит НЕ
         * сам компонент, а построивший его {@code DefinitionDrivenComponent} — в приватном поле
         * {@code componentToDefinitionMap} ({@code Map<IComponent, IDefinition>}); у самого
         * {@code FieldComponent} методов {@code getFieldDefinition()}/{@code getDefinition()} нет
         * вовсе. Обход ищет узлы с этой картой (палитра — дерево {@code SectionDefinitionComponent},
         * по одному на секцию свойств) и сверяет {@code IFieldDefinition.getFeaturePaths()} с
         * цепочкой поиска СВЕРХУ ВНИЗ: путь определения должен быть префиксом цепочки.
         * Для «ДанныеКартинкиСтроки.Сегменты» поле панели — {@code rowPictureDataPath}, а не
         * вложенные {@code segments}.
         *
         * <p>Каждое поле панели присутствует в карте ДВУМЯ записями с одним и тем же определением —
         * редактор ({@code DtTextComponent}, {@code MultilanguageComponent},
         * {@code TypeDescriptionComponent}, …) и {@code LabelComponent} (подпись, фокуса не имеет и
         * поэтому исключается).
         */
        private static Object findFieldComponent(Object scene, List<EStructuralFeature> chain)
        {
            List<String> names = new ArrayList<>(chain.size());
            for (EStructuralFeature feature : chain)
                if (feature != null && feature.getName() != null)
                    names.add(feature.getName());
            if (names.isEmpty())
                return null;
            int[] bestLen = { 0 };
            Object[] best = { null };
            findInTree(Global.invoke(scene, "getComponent"), names, 0, best, bestLen); //$NON-NLS-1$
            return best[0];
        }

        private static void findInTree(Object component, List<String> chain, int depth,
                Object[] best, int[] bestLen)
        {
            if (component == null || depth > 32)
                return;
            Object map = Global.getField(component, "componentToDefinitionMap"); //$NON-NLS-1$
            if (map instanceof Map)
            {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>)map).entrySet())
                {
                    Object candidate = entry.getKey();
                    if (candidate == null
                        || candidate.getClass().getName().contains("LabelComponent")) //$NON-NLS-1$
                        continue;
                    int len = definitionPrefixLen(entry.getValue(), chain);
                    if (len > bestLen[0])
                    {
                        bestLen[0] = len;
                        best[0] = candidate;
                    }
                }
            }
            for (Object child : childComponents(component))
                findInTree(child, chain, depth + 1, best, bestLen);
        }

        /**
         * Насколько путь определения совпадает с цепочкой поиска сверху вниз.
         * Полный путь определения должен быть префиксом цепочки: {@code [rowPictureDataPath]}
         * подходит к {@code [rowPictureDataPath, segments]}, а последний сегмент {@code segments}
         * сам по себе поле панели не ищет.
         */
        private static int definitionPrefixLen(Object definition, List<String> chain)
        {
            int best = 0;
            for (List<String> path : featurePathsOfDefinition(definition))
            {
                if (path.isEmpty() || path.size() > chain.size())
                    continue;
                int n = 0;
                while (n < path.size() && path.get(n).equals(chain.get(n)))
                    n++;
                if (n == path.size() && n > best)
                    best = n;
            }
            // scheduleExact(picture) на FormItem: определение поля — [extInfo, picture],
            // а цепочка из одного уже известного признака — [picture].
            if (best == 0 && chain.size() == 1)
            {
                String name = chain.get(0);
                for (List<String> path : featurePathsOfDefinition(definition))
                    if (path.contains(name))
                        return 1;
            }
            return best;
        }

        /**
         * Дочерние компоненты: обычные {@code getComponents()} плюс {@code getDefinitionComponent()} —
         * {@code PropertyPaletteComponent} держит построитель палитры в отдельном поле, и в
         * {@code getComponents()} он не обязан попадать.
         */
        private static List<Object> childComponents(Object component)
        {
            List<Object> out = new ArrayList<>();
            Object children = Global.invoke(component, "getComponents"); //$NON-NLS-1$
            if (children instanceof Iterable)
                for (Object child : (Iterable<?>)children)
                    if (child != null)
                        out.add(child);
            Object definitionComponent = Global.invoke(component, "getDefinitionComponent"); //$NON-NLS-1$
            if (definitionComponent != null && !out.contains(definitionComponent))
                out.add(definitionComponent);
            return out;
        }

        /**
         * Пути EMF-признаков определения поля ({@code IFieldDefinition.getFeaturePaths()} →
         * {@code FeaturePath[]}) — каждый путь как список имён признаков, СВЕРХУ ВНИЗ
         * (первый сегмент — свойство объекта панели).
         */
        private static List<List<String>> featurePathsOfDefinition(Object definition)
        {
            List<List<String>> out = new ArrayList<>();
            Object paths = definition != null ? Global.invoke(definition, "getFeaturePaths") : null; //$NON-NLS-1$
            if (paths instanceof Object[] arr)
                for (Object path : arr)
                    addFeaturePath(path, out);
            else if (paths instanceof Iterable)
                for (Object path : (Iterable<?>)paths)
                    addFeaturePath(path, out);
            return out;
        }

        private static void addFeaturePath(Object featurePath, List<List<String>> out)
        {
            Object features = featurePath != null ? Global.invoke(featurePath, "getFeaturePath") : null; //$NON-NLS-1$
            if (!(features instanceof EStructuralFeature[] arr))
                return;
            List<String> names = new ArrayList<>(arr.length);
            for (EStructuralFeature f : arr)
                if (f != null)
                    names.add(f.getName());
            if (!names.isEmpty())
                out.add(names);
        }

        /**
         * Фокус ставится ПРЯМО на нативный контрол представления, а не через
         * {@code StandardComponent.setFocus()}. Причина (декомпиляция, подтверждена логом
         * {@code propfocus}: {@code setFocus done, but focus NOT confirmed for DataPathComponent}):
         * {@code setFocus()} шлёт {@code FocusEvent(Iterables.getFirst(getViewModels()))}, а
         * {@code LwtView.handleFocusEvent} реагирует, только если viewModel события совпал с
         * viewModel САМОГО представления. У составного поля (текст + кнопки «...»/«x», как у
         * «Путь к данным») первый viewModel — контейнерный, его представление фокус не принимает,
         * и событие уходит в никуда.
         *
         * <p>Поэтому перебираются нативные контролы всех представлений компонента и его потомков
         * (подписи пропускаются) до первого, у которого фокус ПОДТВЕРДИЛСЯ: у LWT это
         * {@code ILightControl.setFocus(FocusSource)}/{@code isFocused()} (LWT-поля рисуются на
         * общем canvas и SWT-фокуса не имеют), у SWT-рендерера — обычные
         * {@code Control.setFocus()}/{@code isFocusControl()}.
         */
        private static boolean focusFieldComponent(Object scene, Object fieldComponent)
        {
            for (Object nativeControl : editorNativeControls(scene, fieldComponent))
                if (focusNativeControl(nativeControl))
                    return true;
            return false;
        }

        /**
         * Нативные контролы редакторов компонента и его потомков в порядке обхода. Подписи
         * ({@code LabelViewModel}/{@code LabelComponent}) исключаются — фокус нужен в поле ввода.
         */
        private static List<Object> editorNativeControls(Object scene, Object component)
        {
            Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
            Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
            List<Object> out = new ArrayList<>();
            if (mapObj instanceof Map)
                collectEditorNativeControls((Map<?, ?>)mapObj, component, out, 0);
            return out;
        }

        private static void collectEditorNativeControls(Map<?, ?> viewModelToView, Object component,
                List<Object> out, int depth)
        {
            if (component == null || depth > 8)
                return;
            Object viewModels = Global.invoke(component, "getViewModels"); //$NON-NLS-1$
            if (viewModels instanceof Iterable)
            {
                for (Object viewModel : (Iterable<?>)viewModels)
                {
                    if (viewModel == null || viewModel.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
                        continue;
                    Object view = viewModelToView.get(viewModel);
                    Object nativeControl = view != null ? Global.invoke(view, "getNativeControl") : null; //$NON-NLS-1$
                    if (nativeControl != null && !out.contains(nativeControl))
                        out.add(nativeControl);
                }
            }
            for (Object child : childComponents(component))
                if (!child.getClass().getName().contains("LabelComponent")) //$NON-NLS-1$
                    collectEditorNativeControls(viewModelToView, child, out, depth + 1);
        }

        /** @return {@code true}, если контрол реально ЗАБРАЛ фокус (а не просто принял вызов) */
        private static boolean focusNativeControl(Object nativeControl)
        {
            if (nativeControl instanceof Control control)
                return !control.isDisposed() && control.setFocus() && control.isFocusControl();
            Object focusSource = lwtKeyboardFocusSource(nativeControl);
            if (focusSource == null)
                return false;
            Object result = Global.invoke(nativeControl, "setFocus", focusSource); //$NON-NLS-1$
            return Boolean.TRUE.equals(result)
                || Boolean.TRUE.equals(Global.invoke(nativeControl, "isFocused")); //$NON-NLS-1$
        }

        /**
         * {@code com._1c.g5.lwt.FocusSource.Keyboard} — аргумент {@code ILightControl.setFocus}.
         * Бандл LWT в {@code Require-Bundle} плагина не заявлен, поэтому класс грузится по имени
         * через загрузчик самого контрола (тот же приём, что и в
         * {@code TypeComboOverlayHook.resolveHost} для {@code SwtLightComposite}).
         */
        private static volatile Object lwtKeyboardFocusSource;

        private static Object lwtKeyboardFocusSource(Object nativeControl)
        {
            Object cached = lwtKeyboardFocusSource;
            if (cached != null)
                return cached;
            try
            {
                Class<?> focusSourceClass = Class.forName("com._1c.g5.lwt.FocusSource", true, //$NON-NLS-1$
                    nativeControl.getClass().getClassLoader());
                cached = focusSourceClass.getField("Keyboard").get(null); //$NON-NLS-1$
                lwtKeyboardFocusSource = cached;
                return cached;
            }
            catch (Exception e)
            {
                return null;
            }
        }
    }
}
