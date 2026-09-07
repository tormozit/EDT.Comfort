package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextEditBasedChange;
import org.eclipse.ltk.core.refactoring.TextEditBasedChangeGroup;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.ViewForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.lcore.refactoring.IFullTextSearchChange;

/**
 * Все доработки окна мастера рефакторинга LTK — «Рефакторинг», «Переименовать элемент», «Мастер
 * переименования». Три независимые части, у каждой своя привязка и жизненный цикл, общее — только
 * распознавание окна ({@link #isRefactoringWizardDialog}):
 *
 * <ul>
 * <li><b>Табличный режим</b> страницы «Вносимые изменения» ({@link PreviewTablePane}, {@link PreviewRow}):
 * те же изменения плоским списком с колонками «Файл», «Модуль», «Метод», «Родитель», «Тип родителя»,
 * «Категория», «Пометка», «Подходит», сортировкой, отбором по значению ячейки и множественным
 * выделением; кнопки «Табличный режим» и «Только помеченные (N)» — в тулбаре штатной панели. Устройство
 * — как у табличного режима страницы «Проверки» окна «Параметры»
 * ({@code ValidationChecksFilterHook.ChecksTablePane}): дерево и таблица в одном {@link Composite} со
 * стеком {@link TopControlStack} внутри штатной {@code ViewerPane}. Пометки — состояние узлов дерева
 * (таблица шлёт дереву тот же {@link CheckStateChangedEvent}); колонки контекста считает
 * {@link BslOccurrenceContextResolver} в фоне.</li>
 *
 * <li><b>Начальная расстановка пометок</b> полнотекстовых вхождений ({@link Marks}): каждому
 * вхождению пометка выставляется ровно по признаку «Подходит» — штатная расстановка EDT замещается.</li>
 *
 * <li><b>Панель «Текущая строка»</b> в панели сравнения того же окна ({@link CurrentLines}) плюс
 * запоминание размера окна между открытиями.</li>
 * </ul>
 */
public final class RefactoringPreviewHook
{
    /** Диалог мастера рефакторинга — общее распознавание окна для всех трёх частей. */
    private static final String DIALOG_NAME_PART_REFACTORING = "Refactoring"; //$NON-NLS-1$
    private static final String DIALOG_NAME_PART_DIALOG = "Dialog"; //$NON-NLS-1$
    /** Страница предпросмотра LTK — штатная {@code PreviewWizardPage} или её потомок EDT. */
    private static final String PREVIEW_PAGE_NAME_PART = "PreviewWizardPage"; //$NON-NLS-1$

    private static final String PAGE_HANDLED_KEY = "tormozit.refactoringPreviewTablePage"; //$NON-NLS-1$

    /** Страница предпросмотра может быть не первой страницей мастера — ждём её с повторами. */
    private static final int RETRY_DELAY_MS = 300;
    private static final int MAX_ATTEMPTS = 40;

    private RefactoringPreviewHook()
    {
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, RefactoringPreviewHook::handleShow);
        Marks.install(display);
        CurrentLines.install(display);
    }

    static boolean isRefactoringWizardDialog(Shell shell)
    {
        Object data = shell != null ? shell.getData() : null;
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(DIALOG_NAME_PART_REFACTORING) && name.contains(DIALOG_NAME_PART_DIALOG);
    }

    /**
     * Показ любого контрола внутри окна мастера: страница предпросмотра становится видимой именно
     * так ({@code WizardDialog.showPage} → {@code setVisible(true)}), отдельного события у неё нет.
     */
    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        Shell shell = control.getShell();
        if (shell == null || shell.isDisposed() || !isRefactoringWizardDialog(shell))
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> scheduleAttach(shell, 0));
    }

    private static void scheduleAttach(Shell shell, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        if (tryAttach(shell))
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(RETRY_DELAY_MS, () -> scheduleAttach(shell, attempt + 1));
    }

    /**
     * @return {@code false}, если страницу предпросмотра ещё стоит подождать; {@code true} — режим
     *     подключён, подключать нечего или вёрстка страницы не та
     */
    private static boolean tryAttach(Shell shell)
    {
        if (shell == null || shell.isDisposed() || !(shell.getData() instanceof IWizardContainer container))
            return true;
        IWizardPage page = container.getCurrentPage();
        // Не страница предпросмотра — её показ придёт своим событием
        if (page == null || !page.getClass().getName().contains(PREVIEW_PAGE_NAME_PART))
            return true;
        if (Boolean.TRUE.equals(pageFlag(page)))
            return true;
        if (!(Global.getField(page, "fTreeViewer") instanceof CheckboxTreeViewer treeViewer)) //$NON-NLS-1$
            return false;
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        markPageHandled(page);
        try
        {
            PreviewTablePane.install(treeViewer);
        }
        catch (RuntimeException e)
        {
            // Вёрстка страницы могла измениться в новой версии EDT — она остаётся штатной.
        }
        return true;
    }

    private static Object pageFlag(IWizardPage page)
    {
        Control control = page.getControl();
        return control != null && !control.isDisposed() ? control.getData(PAGE_HANDLED_KEY) : Boolean.TRUE;
    }

    private static void markPageHandled(IWizardPage page)
    {
        Control control = page.getControl();
        if (control != null && !control.isDisposed())
            control.setData(PAGE_HANDLED_KEY, Boolean.TRUE);
    }

    /** Строка таблицы: лист дерева изменений и всё, что о нём удалось вычислить. */
    private static final class PreviewRow implements OccurrenceContextResolveJob.Target
    {
        /** Узел дерева ({@code PreviewNode} из неэкспортированного пакета LTK — только рефлексия). */
        final Object node;
        final String text;
        final IFile file;
        final int offset;
        final int length;

        String fileName = ""; //$NON-NLS-1$
        String fileType = ""; //$NON-NLS-1$
        String module = ""; //$NON-NLS-1$
        String method = ""; //$NON-NLS-1$
        String parent = ""; //$NON-NLS-1$
        String syntaxKind = ""; //$NON-NLS-1$
        String lineText = ""; //$NON-NLS-1$
        int highlightStart;
        int highlightLength;
        /** {@code null} — тип ещё не вычисляли (в ячейке «?»). */
        String parentType;
        /**
         * Колонка «Подходит»: {@code null} — ещё не вычислено, иначе значение из
         * {@link BslOccurrenceContextResolver}. Вхождение-ссылку EDT уже разрешил как настоящую →
         * сразу {@code SUITABLE_YES}; вхождение полнотекстового поиска (литерал/комментарий) —
         * анализом «Родителя», внутри кода — после «Типа родителя».
         */
        String suitable;
        /** Искомый элемент (переименовываемый объект); {@code null} — колонка «Подходит» скрыта. */
        BslOccurrenceContextResolver.Sought sought;
        /** Вхождение найдено полнотекстовым поиском (не разрешённая моделью ссылка). */
        boolean fullText;

        PreviewRow(Object node, String text, IFile file, int offset, int length)
        {
            this.node = node;
            this.text = text;
            this.file = file;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public IFile file()
        {
            return file;
        }

        /** Колонки «Метод», «Родитель», «Тип родителя», «Категория» есть только у модулей. */
        @Override
        public boolean needsContext()
        {
            return offset >= 0 && length > 0 && BslModuleMethodResolver.isBslModule(file);
        }

        /** Позиция вхождения известна заранее — из группы правок LTK. */
        @Override
        public int[] resolveRegion()
        {
            return offset >= 0 && length > 0 ? new int[] {offset, length} : null;
        }

        @Override
        public int[] resolvedRegion()
        {
            return resolveRegion();
        }

        @Override
        public void applyFast(OccurrenceContextResolveJob.FastContext context)
        {
            method = context.method;
            parent = context.parent;
            syntaxKind = context.syntaxKind;
            lineText = context.lineText;
            highlightStart = context.highlightStart;
            highlightLength = context.highlightLength;
            if (sought != null && fullText
                && (BslOccurrenceContextResolver.KIND_LITERAL.equals(syntaxKind)
                    || BslOccurrenceContextResolver.KIND_COMMENT.equals(syntaxKind)))
            {
                // Литерал / комментарий: типа родителя нет — судим по цепочке «Родитель».
                String occName = highlightLength > 0 && highlightStart >= 0
                    && highlightStart + highlightLength <= lineText.length()
                        ? lineText.substring(highlightStart, highlightStart + highlightLength) : ""; //$NON-NLS-1$
                suitable = BslOccurrenceContextResolver.suitabilityByLiteralParent(sought, parent, occName);
            }
        }

        @Override
        public void applyParentType(String type)
        {
            parentType = type;
            if (sought != null && fullText && suitable == null)
                suitable = BslOccurrenceContextResolver.suitabilityByType(sought, type);
        }

        /**
         * Литерал с выражением-родителем: тип посчитал ИР. Его ответ важнее предварительной оценки
         * по текстовой цепочке «Родитель» из {@link #applyFast}. {@code null} — ИР недоступен/ошибка,
         * оставляем как есть.
         */
        @Override
        public void applyIrParentType(String type)
        {
            if (type == null)
                return;
            parentType = type;
            if (sought != null && fullText && !type.isBlank())
            {
                String verdict = BslOccurrenceContextResolver.suitabilityByType(sought, type);
                if (verdict != null && !verdict.isBlank()
                    && !BslOccurrenceContextResolver.SUITABLE_UNKNOWN.equals(verdict))
                    suitable = verdict;
            }
        }

        @Override public String occurrenceLineText() { return lineText; }
        @Override public int occurrenceHighlightStart() { return highlightStart; }
        @Override public int occurrenceHighlightLength() { return highlightLength; }
    }

    /** Таблица вместо дерева изменений и всё её поведение. */
    private static final class PreviewTablePane
    {
        private static final String SETTINGS_SECTION = "tormozit.refactoringPreviewTable"; //$NON-NLS-1$
        private static final String TOGGLE_ID = SETTINGS_SECTION + ".toggle"; //$NON-NLS-1$
        private static final String MARKED_ONLY_ID = SETTINGS_SECTION + ".markedOnly"; //$NON-NLS-1$
        private static final String SUITABLE_ONLY_ID = SETTINGS_SECTION + ".suitableOnly"; //$NON-NLS-1$
        /** Штатные кнопки EDT: пометить / снять пометку со всех полнотекстовых вхождений. */
        private static final String CHECK_ALL_FULL_TEXT_ID =
            "com._1c.g5.v8.dt.lcore.ui.refactoring.checkAllFullTextSearchChanges"; //$NON-NLS-1$
        private static final String UNCHECK_ALL_FULL_TEXT_ID =
            "com._1c.g5.v8.dt.lcore.ui.refactoring.uncheckAllFullTextSearchChanges"; //$NON-NLS-1$
        // Порядок колонок сменился (Подходит теперь сразу за «Тип родителя») — старый сохранённый
        // порядок ссылается на прежние индексы, поэтому ключ новый.
        private static final String KEY_COL_ORDER = "columnOrder2"; //$NON-NLS-1$
        /** Ширины на момент закрытия были чистым авто-заполнением, а не ручной подгонкой. */
        private static final String KEY_COLUMNS_FILL = "columnsFill"; //$NON-NLS-1$
        private static final String[] WIDTH_KEYS = {"markWidth", "changeWidth", "fileWidth", "fileTypeWidth", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "moduleWidth", "methodWidth", "parentWidth", "parentTypeWidth", "suitableWidth", "syntaxWidth", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "textWidth"}; //$NON-NLS-1$
        private static final int[] DEFAULT_WIDTHS = {74, 320, 200, 90, 260, 180, 180, 220, 80, 90, 320};
        private static final int MIN_COLUMN_WIDTH = 40;

        /**
         * Индекс колонки «Пометка» — первая: именно в ней стиль {@code SWT.CHECK} рисует нативный
         * флажок строки, поэтому из «Изменения» флажки в табличном режиме уходят сами собой.
         * По этой же колонке бьёт кнопка «Только помеченные».
         */
        private static final int MARK_COLUMN_INDEX = 0;
        private static final String MARK_YES = "Да"; //$NON-NLS-1$
        private static final String MARK_NO = "Нет"; //$NON-NLS-1$

        /** Пометка на меню таблицы: свои пункты дописаны, второй раз не создавать. */
        private static final String MARK_MENU_KEY = "tormozit.refactoringPreviewMarkMenu"; //$NON-NLS-1$

        private static final String UNKNOWN_TYPE = "?"; //$NON-NLS-1$
        private static final String PARENT_TYPE_TITLE = BslOccurrenceContextResolver.COL_PARENT_TYPE;

        /** Значения {@code PreviewNode.getActive()}. */
        private static final int INACTIVE = 0;
        private static final int PARTLY_ACTIVE = 1;

        private static ImageData tableModeIconData;

        private final CheckboxTreeViewer treeViewer;
        private final Composite stack;
        private final TopControlStack stackLayout;
        private final Composite tableHost;
        private final Table table;
        private final CheckboxTableViewer viewer;
        private final List<PreviewRow> rows = new ArrayList<>();
        /** Свои пункты контекстного меню — их доступность зависит от выделения (см. prepareMarkMenu). */
        private final List<MenuItem> markMenuItems = new ArrayList<>();

        private FormTableInteraction interaction;
        private TableColumn parentTypeColumn;
        private TableColumn suitableColumn;
        /** Искомый элемент (переименовываемый объект) — из заголовка окна; {@code null} → колонка «Подходит» скрыта. */
        private BslOccurrenceContextResolver.Sought sought;
        private OccurrenceContextResolveJob contextResolver;
        private IAction toggleAction;
        private IAction markedOnlyAction;
        private ActionContributionItem markedOnlyItem;
        private IAction suitableOnlyAction;
        private ActionContributionItem suitableOnlyItem;
        /** Отбор «Только подходящие» — отдельный {@link ViewerFilter}, чтобы «Подходит=?» не скрывались. */
        private ViewerFilter suitableOnlyFilter;
        private ToolBarManager toolBarManager;
        private boolean tableMode;
        private boolean syncing;

        private PreviewTablePane(CheckboxTreeViewer treeViewer, Composite stack, TopControlStack stackLayout,
            Composite tableHost, Table table, CheckboxTableViewer viewer)
        {
            this.treeViewer = treeViewer;
            this.stack = stack;
            this.stackLayout = stackLayout;
            this.tableHost = tableHost;
            this.table = table;
            this.viewer = viewer;
        }

        /**
         * @return {@code null}, если вёрстка страницы не та, которую мы знаем — тогда страница
         *     остаётся штатной, только с деревом
         */
        static PreviewTablePane install(CheckboxTreeViewer treeViewer)
        {
            Tree tree = treeViewer.getTree();
            if (!(tree.getParent() instanceof ViewForm pane))
                return null;

            Composite stack = new Composite(pane, SWT.NONE);
            TopControlStack stackLayout = new TopControlStack();
            stack.setLayout(stackLayout);
            if (!tree.setParent(stack))
            {
                stack.dispose();
                return null;
            }

            // tableHost с layout(null) + columnHost с TableColumnLayout — раскладка, которую
            // ожидает FormTableInteraction для overlay заголовка колонок.
            Composite tableHost = new Composite(stack, SWT.NONE);
            tableHost.setLayout(null);
            Composite columnHost = new Composite(tableHost, SWT.NONE);
            TableColumnLayout columnLayout = new TableColumnLayout();
            columnHost.setLayout(columnLayout);

            // CheckboxTableViewer, а не TableViewer: пометки строк восстанавливает сам JFace по
            // ICheckStateProvider, иначе сортировка и отбор их теряют.
            CheckboxTableViewer viewer = new CheckboxTableViewer(
                new Table(columnHost, SWT.CHECK | SWT.FULL_SELECTION | SWT.MULTI));
            Table table = viewer.getTable();
            table.setHeaderVisible(true);
            ThemeAwareColors.applyGridLines(table);

            PreviewTablePane result =
                new PreviewTablePane(treeViewer, stack, stackLayout, tableHost, table, viewer);
            result.sought = resolveSought(tree.getShell());
            result.createColumns(columnLayout);

            stackLayout.topControl = tree;
            pane.setContent(stack);
            stack.layout();
            pane.layout(true, true);

            result.wireListeners();
            result.installToggle(pane);
            result.setTableMode(ComfortSettings.isRefactoringPreviewTableMode());
            return result;
        }

        /** Искомый элемент из полного имени, положенного на {@code Shell} мастера в {@link RefactoringWizardTitleHook}. */
        private static BslOccurrenceContextResolver.Sought resolveSought(Shell shell)
        {
            if (shell == null || shell.isDisposed())
                return null;
            return shell.getData(RefactoringWizardTitleHook.SHELL_RENAME_FULL_NAME_KEY) instanceof String fqn
                ? BslOccurrenceContextResolver.parseSought(fqn) : null;
        }

        private void createColumns(TableColumnLayout columnLayout)
        {
            IDialogSettings settings = dialogSettings();
            ILabelProvider treeLabels = treeLabelProvider();

            TableViewerColumn markColumn = new TableViewerColumn(viewer, SWT.NONE);
            markColumn.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    // В ячейке — нативный флажок строки (SWT.CHECK); «Да»/«Нет» для сортировки и
                    // отбора отдаёт cellText.
                    return ""; //$NON-NLS-1$
                }
            });
            // Колонка «Пометка» — только флажок: фиксированная минимальная ширина, без ручного
            // ресайза и без заголовка (в такую ширину он не встанет); смысл — в подсказке заголовка.
            FormTableInteraction.applyIconColumn(markColumn.getColumn(), columnLayout, 6);
            markColumn.getColumn().setToolTipText(TooltipText.wrap(table,
                "Пометка" + Global.pluginSignForTooltip())); //$NON-NLS-1$

            TableViewerColumn changeColumn = new TableViewerColumn(viewer, SWT.NONE);
            changeColumn.getColumn().setText("Изменение"); //$NON-NLS-1$
            changeColumn.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    return element instanceof PreviewRow row ? row.text : ""; //$NON-NLS-1$
                }

                @Override
                public Image getImage(Object element)
                {
                    if (treeLabels == null || !(element instanceof PreviewRow row))
                        return null;
                    return treeLabels.getImage(row.node);
                }

                @Override
                public Color getForeground(Object element)
                {
                    return isSuitableRow(element) ? suitableTextColor() : null;
                }
            });
            applyWidth(columnLayout, changeColumn.getColumn(), settings, 1);

            addTextColumn(columnLayout, settings, 2, "Файл", row -> row.fileName); //$NON-NLS-1$
            addTextColumn(columnLayout, settings, 3, "Тип файла", row -> row.fileType); //$NON-NLS-1$
            addTextColumn(columnLayout, settings, 4, "Модуль", row -> row.module); //$NON-NLS-1$
            addTextColumn(columnLayout, settings, 5, "Метод", row -> row.method); //$NON-NLS-1$
            TableColumn parentColumn = addTextColumn(columnLayout, settings, 6,
                BslOccurrenceContextResolver.COL_PARENT, row -> row.parent);
            parentTypeColumn = addColoredTextColumn(columnLayout, settings, 7, PARENT_TYPE_TITLE,
                row -> row.parentType != null ? row.parentType : UNKNOWN_TYPE);
            suitableColumn = addColoredTextColumn(columnLayout, settings, 8,
                BslOccurrenceContextResolver.COL_SUITABLE, PreviewTablePane::suitableCell);
            TableColumn syntaxColumn = addTextColumn(columnLayout, settings, 9,
                BslOccurrenceContextResolver.COL_SYNTAX_KIND, row -> row.syntaxKind);
            TableColumn occurrenceTextColumn = OccurrenceContextResolveJob.addTextColumn(viewer).getColumn();
            applyWidth(columnLayout, occurrenceTextColumn, settings, 10);

            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.setCheckStateProvider(new ICheckStateProvider()
            {
                @Override
                public boolean isChecked(Object element)
                {
                    return element instanceof PreviewRow row && activeState(row.node) != INACTIVE;
                }

                @Override
                public boolean isGrayed(Object element)
                {
                    return element instanceof PreviewRow row && activeState(row.node) == PARTLY_ACTIVE;
                }
            });
            viewer.setInput(rows);

            interaction = new FormTableInteraction(table, viewer,
                (item, col) -> cellText(item != null ? item.getData() : null, col));
            interaction.setFilterTextResolver(PreviewTablePane::cellText);
            interaction.setColumnReorderEnabled(true);
            interaction.setOwnerDrawColumns(occurrenceTextColumn);
            FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, table);
            // true — пользователь уже подстраивал ширины сам, режим заполнения по ширине не навязываем.
            // Ширины, оставшиеся от авто-заполнения, за «подстроенные» не считаются (KEY_COLUMNS_FILL).
            interaction.install(
                FormTableColumnState.hasSavedColumnWidths(settings, KEY_COLUMNS_FILL, WIDTH_KEYS));
            interaction.enableHeaderSort();
            BslOccurrenceContextResolver.applyColumnHeaderTooltips(interaction, parentColumn,
                parentTypeColumn, syntaxColumn);
            interaction.setHeaderTooltipExtra(suitableColumn,
                BslOccurrenceContextResolver.TIP_SUITABLE + Global.pluginSignForTooltip());
            // «Подходит» имеет смысл только при известном искомом объекте (заголовок мастера).
            if (sought == null)
                interaction.setColumnHidden(suitableColumn, true, DEFAULT_WIDTHS[8]);
            // Порог MAX_VALUE — окно рефакторинга модальное, кнопки «Рассчитать типы» тут нет:
            // «Тип родителя» всегда считается автоматически системным проходом, как и раньше.
            contextResolver = new OccurrenceContextResolveJob(table, viewer, parentTypeColumn,
                PARENT_TYPE_TITLE, Integer.MAX_VALUE, null);
            // Литеральные вхождения: «Тип родителя» модель BSL не даёт (точки перед литералом нет) —
            // спрашиваем подключённое приложение ИР, но только для строк в поле зрения (ИР медленный
            // и однопоточный).
            contextResolver.setIrParentTypeResolver(BslOccurrenceContextResolver::parentTypeViaIr);
            contextResolver.setRowsPublishedCallback(this::enableMarksBySuitability);
            contextResolver.trackViewportScrolling();
            installMarkMenu();
        }

        private TableColumn addTextColumn(TableColumnLayout columnLayout, IDialogSettings settings, int index,
            String title, java.util.function.Function<PreviewRow, String> text)
        {
            TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
            column.getColumn().setText(title);
            column.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    if (!(element instanceof PreviewRow row))
                        return ""; //$NON-NLS-1$
                    String value = text.apply(row);
                    return value != null ? value : ""; //$NON-NLS-1$
                }
            });
            applyWidth(columnLayout, column.getColumn(), settings, index);
            return column.getColumn();
        }

        /**
         * Текстовая колонка, в которой значение «подходящего» вхождения красится тёмно-зелёным текстом
         * (та же оценка, что в колонке «Подходит»): «Тип родителя» и сама «Подходит».
         */
        private TableColumn addColoredTextColumn(TableColumnLayout columnLayout, IDialogSettings settings,
            int index, String title, java.util.function.Function<PreviewRow, String> text)
        {
            TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
            column.getColumn().setText(title);
            column.setLabelProvider(new ColumnLabelProvider()
            {
                @Override
                public String getText(Object element)
                {
                    if (!(element instanceof PreviewRow row))
                        return ""; //$NON-NLS-1$
                    String value = text.apply(row);
                    return value != null ? value : ""; //$NON-NLS-1$
                }

                @Override
                public Color getForeground(Object element)
                {
                    return isSuitableRow(element) ? suitableTextColor() : null;
                }
            });
            applyWidth(columnLayout, column.getColumn(), settings, index);
            return column.getColumn();
        }

        private static boolean isSuitableRow(Object element)
        {
            return element instanceof PreviewRow row
                && BslOccurrenceContextResolver.isSuitableYes(row.suitable);
        }

        /** Тёмно-зелёный цвет текста «подходящих» ячеек — общий с панелью результатов «Найти ссылки». */
        private Color suitableTextColor()
        {
            return BslOccurrenceContextResolver.suitableTextColor(table.getDisplay());
        }

        private static void applyWidth(TableColumnLayout columnLayout, TableColumn column,
            IDialogSettings settings, int index)
        {
            int width = FormTableColumnState.readWidth(settings, WIDTH_KEYS[index], DEFAULT_WIDTHS[index],
                MIN_COLUMN_WIDTH);
            // addTrim=false — ширина сохранённая (уже фактическая), иначе раскладка прибавляла бы к ней
            // COLUMN_TRIM, и колонки росли бы от открытия к открытию.
            columnLayout.setColumnData(column, new ColumnPixelData(width, true, false));
        }

        private ILabelProvider treeLabelProvider()
        {
            return treeViewer.getLabelProvider() instanceof ILabelProvider labels ? labels : null;
        }

        private void wireListeners()
        {
            table.addListener(SWT.Selection, event ->
            {
                if (event.detail == SWT.CHECK)
                    toggleCheck(event.item);
                else
                    syncSelectionToTree();
            });
            // Пробел переключает пометку у всех выделенных строк. Штатную обработку гасим:
            // иначе система переключит ещё и флажок строки с фокусом, отменив нашу.
            table.addListener(SWT.KeyDown, event ->
            {
                if (event.character == ' ' && event.stateMask == 0)
                {
                    event.doit = false;
                    setChecked(selectedRows(), !allChecked(selectedRows()));
                }
            });
            // Наведение на колонку-флажок не должно вызывать нативную подсказку Win32 ListView
            // (увеличенная копия иконки соседней ячейки / пустой тултип). Гасим её для всей полосы
            // первой колонки; уходя из неё — возвращаем штатные подсказки по обрезанному тексту.
            Listener markTooltipGuard = event ->
            {
                if (table.isDisposed())
                    return;
                boolean overMark = event.type != SWT.MouseExit && isOverMarkColumn(event.x, event.y);
                String current = table.getToolTipText();
                if (overMark)
                {
                    if (!"".equals(current)) //$NON-NLS-1$
                        table.setToolTipText(""); //$NON-NLS-1$
                }
                else if ("".equals(current)) //$NON-NLS-1$
                {
                    table.setToolTipText(null);
                }
            };
            table.addListener(SWT.MouseMove, markTooltipGuard);
            table.addListener(SWT.MouseExit, markTooltipGuard);
            treeViewer.addSelectionChangedListener(event -> syncSelectionFromTree());
            treeViewer.getTree().addListener(SWT.Selection, event -> refreshRows());
            table.addDisposeListener(event ->
            {
                contextResolver.cancel();
                BslOccurrenceContextResolver.clearCaches();
                saveColumnLayout();
                // Режим — как он оставлен на момент закрытия окна: следующее открытие начнётся с него.
                ComfortSettings.setRefactoringPreviewTableMode(tableMode);
            });
        }

        /** Точка {@code (x, y)} таблицы находится в полосе первой колонки («Пометка» с флажком). */
        private boolean isOverMarkColumn(int x, int y)
        {
            if (table.isDisposed() || table.getColumnCount() == 0)
                return false;
            TableItem item = table.getItem(new Point(x, y));
            if (item == null || item.isDisposed())
                return false;
            Rectangle bounds = item.getBounds(0);
            // Флажок рисуется левее getBounds(0).x — берём всё слева от правой границы колонки.
            return bounds != null && x < bounds.x + bounds.width;
        }

        /** Пункты пометки в контекстном меню таблицы (меню создаёт {@link FormTableInteraction}). */
        private void installMarkMenu()
        {
            // Меню таблицы FormTableInteraction создаёт лениво, в своём слушателе SWT.MenuDetect
            // (ensureCopyMenu) — на момент install() его ещё нет. Наш слушатель добавлен позже, значит
            // и вызывается позже: к этому моменту меню уже создано, дописываем в него свои пункты.
            table.addListener(SWT.MenuDetect, event -> prepareMarkMenu());
        }

        private void prepareMarkMenu()
        {
            Menu menu = table.getMenu();
            if (menu == null || menu.isDisposed())
                return;
            if (menu.getData(MARK_MENU_KEY) == null)
            {
                menu.setData(MARK_MENU_KEY, Boolean.TRUE);
                new MenuItem(menu, SWT.SEPARATOR);
                markMenuItems.add(MarkSelectionCommands.addSetItem(menu, () -> setChecked(selectedRows(), true)));
                markMenuItems.add(MarkSelectionCommands.addClearItem(menu, () -> setChecked(selectedRows(), false)));
            }
            boolean hasSelection = !selectedRows().isEmpty();
            for (MenuItem item : markMenuItems)
            {
                if (!item.isDisposed())
                    item.setEnabled(hasSelection);
            }
        }

        /**
         * Кнопка табличного режима — в тулбаре штатной панели над деревом изменений. Тулбар
         * принадлежит {@code ToolBarManager} панели, а его {@code update(true)} чужие «сырые»
         * {@code ToolItem} стирает (вызываем и мы сами, и EDT) —
         * поэтому кнопка оформлена действием в менеджере, как у переключателей панелей сравнения.
         */
        private void installToggle(ViewForm pane)
        {
            if (!(Global.invoke(pane, "getToolBarManager") instanceof ToolBarManager manager)) //$NON-NLS-1$
                return;
            toolBarManager = manager;
            Action toggle = new Action("", IAction.AS_CHECK_BOX) //$NON-NLS-1$
            {
                @Override
                public void run()
                {
                    applyMode(isChecked());
                    ComfortSettings.setRefactoringPreviewTableMode(isChecked());
                }
            };
            toggle.setId(TOGGLE_ID);
            toggle.setImageDescriptor(tableModeIconDescriptor());
            toggle.setToolTipText(TooltipText.wrap(pane,
                "Табличный режим: изменения плоским списком с колонками «Файл», «Модуль», «Метод», " //$NON-NLS-1$
                    + "«Родитель», «Тип родителя» и «Категория»" + Global.pluginSignForTooltip())); //$NON-NLS-1$
            toggle.setChecked(ComfortSettings.isRefactoringPreviewTableMode());
            manager.add(toggle);
            toggleAction = toggle;

            Action markedOnly = new Action("Только помеченные (0)", IAction.AS_CHECK_BOX) //$NON-NLS-1$
            {
                @Override
                public void run()
                {
                    applyMarkedOnlyFilter(isChecked());
                }
            };
            markedOnly.setId(MARKED_ONLY_ID);
            markedOnly.setToolTipText(TooltipText.wrap(pane,
                "Оставить в списке только помеченные изменения (отбор «Пометка» = «Да»)" //$NON-NLS-1$
                    + Global.pluginSignForTooltip()));
            markedOnlyAction = markedOnly;
            // Явный ActionContributionItem с MODE_FORCE_TEXT — подпись со счётчиком видна всегда.
            // БЕЗ значка: у ToolItem с картинкой И текстом текст встаёт ПОД картинкой, и строка
            // тулбара становится вдвое выше. Текст без картинки — обычная высота тулбара.
            markedOnlyItem = new ActionContributionItem(markedOnly);
            markedOnlyItem.setMode(ActionContributionItem.MODE_FORCE_TEXT);
            manager.add(new Separator(MARKED_ONLY_ID + ".sep")); //$NON-NLS-1$
            manager.add(markedOnlyItem);

            // «Только подходящие» — альтернатива «Только помеченных»; появляется, лишь когда искомое
            // разобрано (иначе колонки «Подходит» нет).
            if (sought != null)
            {
                Action suitableOnly = new Action("Только подходящие (0)", IAction.AS_CHECK_BOX) //$NON-NLS-1$
                {
                    @Override
                    public void run()
                    {
                        applySuitableOnlyFilter(isChecked());
                    }
                };
                suitableOnly.setId(SUITABLE_ONLY_ID);
                suitableOnly.setToolTipText(TooltipText.wrap(pane,
                    "Скрыть изменения с «Подходит» = «Нет»; строки «?» (тип родителя ещё не вычислен) остаются" //$NON-NLS-1$
                        + Global.pluginSignForTooltip()));
                suitableOnlyAction = suitableOnly;
                suitableOnlyItem = new ActionContributionItem(suitableOnly);
                suitableOnlyItem.setMode(ActionContributionItem.MODE_FORCE_TEXT);
                manager.add(suitableOnlyItem);
            }

            manager.update(true);
            overrideMarkAllActions(manager);
            watchToolBar(manager);
            updateMarkedOnly();
        }

        /**
         * Штатные «пометить/снять пометку со всех полнотекстовых вхождений» меняют {@code Change}
         * и обновляют дерево; таблица об этом не узнаёт. Подменяем действие вкладки, чтобы после
         * штатного {@code run()} перечитать пометки строк. Эталон подмены —
         * {@code CompareConfigMenuHook.wrapToolbarSelectAllActions}.
         */
        private void overrideMarkAllActions(ToolBarManager manager)
        {
            for (IContributionItem item : manager.getItems())
            {
                if (!(item instanceof ActionContributionItem aci))
                    continue;
                IAction action = aci.getAction();
                if (action == null || action instanceof MarkAllRefreshAction
                    || !isFullTextMarkAllAction(action))
                    continue;
                MarkAllRefreshAction wrapper = new MarkAllRefreshAction(action);
                Global.setFieldForce(aci, "action", wrapper); //$NON-NLS-1$
            }
        }

        private static boolean isFullTextMarkAllAction(IAction action)
        {
            String id = action.getId();
            if (CHECK_ALL_FULL_TEXT_ID.equals(id) || UNCHECK_ALL_FULL_TEXT_ID.equals(id))
                return true;
            String cn = action.getClass().getName();
            return cn.endsWith("CheckAllFullTextSearchChangesAction") //$NON-NLS-1$
                || cn.endsWith("UncheckAllFullTextSearchChangesAction"); //$NON-NLS-1$
        }

        /**
         * Прочие кнопки панели тоже работают с деревом. {@code SWT.Selection} приходит на
         * {@link ToolItem}, не на тулбар — слушаем фильтром дисплея, чтобы пережить
         * {@code ToolBarManager.update(true)}.
         */
        private void watchToolBar(ToolBarManager manager)
        {
            ToolBar bar = manager.getControl();
            if (bar == null || bar.isDisposed())
                return;
            Display display = bar.getDisplay();
            Listener refresh = event -> {
                if (!(event.widget instanceof ToolItem item) || item.getParent() != bar)
                    return;
                if (table.isDisposed())
                    return;
                display.asyncExec(this::refreshRows);
            };
            display.addFilter(SWT.Selection, refresh);
            table.addDisposeListener(event -> {
                if (!display.isDisposed())
                    display.removeFilter(SWT.Selection, refresh);
            });
        }

        /** Делегат штатной кнопки пометки полнотекстовых вхождений: после неё обновляем таблицу. */
        private final class MarkAllRefreshAction extends Action
        {
            private final IAction original;

            MarkAllRefreshAction(IAction original)
            {
                super(original.getText() != null ? original.getText() : "", original.getStyle()); //$NON-NLS-1$
                this.original = original;
                setId(original.getId());
                setImageDescriptor(original.getImageDescriptor());
                setDisabledImageDescriptor(original.getDisabledImageDescriptor());
                setHoverImageDescriptor(original.getHoverImageDescriptor());
                setToolTipText(original.getToolTipText());
                setEnabled(original.isEnabled());
            }

            @Override
            public void run()
            {
                original.run();
                refreshRows();
            }
        }

        void setTableMode(boolean value)
        {
            applyMode(value);
            if (toggleAction != null)
                toggleAction.setChecked(value);
            // Виджет кнопки не следит за действием сам — обновляем вклад после смены пометки.
            if (toolBarManager != null && toolBarManager.find(TOGGLE_ID) instanceof ActionContributionItem item)
                item.update();
        }

        private void applyMode(boolean value)
        {
            if (stack.isDisposed())
                return;
            tableMode = value;
            if (value)
                reload();
            else
                contextResolver.cancel();
            stackLayout.topControl = value ? tableHost : treeViewer.getTree();
            stack.layout();
            updateMarkedOnly();
        }

        /** Кнопка-переключатель «Только помеченные (N)»: наложить/снять отбор «Пометка» = «Да». */
        private void applyMarkedOnlyFilter(boolean markedOnly)
        {
            if (interaction == null)
                return;
            if (markedOnly)
            {
                setSuitableOnlyFilterActive(false); // альтернатива «Только подходящих»
                interaction.applyColumnFilterValue(MARK_COLUMN_INDEX, MARK_YES);
            }
            else
            {
                interaction.clearColumnFilter(MARK_COLUMN_INDEX);
            }
            updateMarkedOnly();
        }

        /**
         * Кнопка-переключатель «Только подходящие (N)»: скрыть строки с «Подходит» = «Нет».
         * Строки «Подходит» = «?» (тип родителя ещё не вычислен) НЕ отбрасываются — они могут
         * стать подходящими. Поэтому это отдельный {@link ViewerFilter}, а не отбор колонки по «Да».
         */
        private void applySuitableOnlyFilter(boolean suitableOnly)
        {
            if (interaction == null)
                return;
            if (suitableOnly)
                interaction.clearColumnFilter(MARK_COLUMN_INDEX); // альтернатива «Только помеченных»
            setSuitableOnlyFilterActive(suitableOnly);
            updateMarkedOnly();
        }

        private void setSuitableOnlyFilterActive(boolean active)
        {
            if (viewer == null || viewer.getControl().isDisposed())
                return;
            if (active)
            {
                if (suitableOnlyFilter == null)
                    suitableOnlyFilter = new ViewerFilter()
                    {
                        @Override
                        public boolean select(Viewer v, Object parent, Object element)
                        {
                            return !(element instanceof PreviewRow row)
                                || !BslOccurrenceContextResolver.SUITABLE_NO.equals(suitableCell(row));
                        }
                    };
                if (!isSuitableOnlyFilterActive())
                    viewer.addFilter(suitableOnlyFilter);
            }
            else if (suitableOnlyFilter != null && isSuitableOnlyFilterActive())
            {
                viewer.removeFilter(suitableOnlyFilter);
            }
        }

        private boolean isSuitableOnlyFilterActive()
        {
            if (suitableOnlyFilter == null || viewer == null || viewer.getControl().isDisposed())
                return false;
            for (ViewerFilter f : viewer.getFilters())
                if (f == suitableOnlyFilter)
                    return true;
            return false;
        }

        /** Обновляет подпись со счётчиком, состояние нажатия и доступность кнопок отбора. */
        private void updateMarkedOnly()
        {
            if (markedOnlyAction != null)
            {
                int marked = 0;
                for (PreviewRow row : rows)
                {
                    if (activeState(row.node) != INACTIVE)
                        marked++;
                }
                markedOnlyAction.setText("Только помеченные (" + marked + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                markedOnlyAction.setEnabled(tableMode);
                boolean filtered = interaction != null && interaction.isColumnFiltered(MARK_COLUMN_INDEX);
                if (markedOnlyAction.isChecked() != filtered)
                    markedOnlyAction.setChecked(filtered);
                if (markedOnlyItem != null)
                    markedOnlyItem.update();
            }
            if (suitableOnlyAction != null)
            {
                int suitable = 0;
                for (PreviewRow row : rows)
                {
                    if (BslOccurrenceContextResolver.isSuitableYes(row.suitable))
                        suitable++;
                }
                suitableOnlyAction.setText("Только подходящие (" + suitable + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                suitableOnlyAction.setEnabled(tableMode);
                boolean filtered = isSuitableOnlyFilterActive();
                if (suitableOnlyAction.isChecked() != filtered)
                    suitableOnlyAction.setChecked(filtered);
                if (suitableOnlyItem != null)
                    suitableOnlyItem.update();
            }
        }

        /**
         * После публикации порции строк фоновым проходом «Тип родителя»/ИР: у полнотекстовых
         * вхождений, ставших подходящими, включаем пометку (штатная расстановка {@link Marks} шла
         * по текстовой оценке и не знала про уточнение через ИР/модель).
         */
        private void enableMarksBySuitability(java.util.List<OccurrenceContextResolveJob.Target> batch)
        {
            if (table.isDisposed())
                return;
            List<PreviewRow> toMark = new ArrayList<>();
            for (OccurrenceContextResolveJob.Target target : batch)
            {
                if (target instanceof PreviewRow row && row.fullText
                    && BslOccurrenceContextResolver.isSuitableYes(row.suitable)
                    && activeState(row.node) == INACTIVE)
                    toMark.add(row);
            }
            if (!toMark.isEmpty())
                setChecked(toMark, true);
            // Под активным отбором «Только подходящие» строки, ставшие «Нет», должны уйти, а «?» —
            // остаться: viewer.update стили не перефильтровывает, нужен refresh.
            else if (isSuitableOnlyFilterActive() && !viewer.getControl().isDisposed())
                viewer.refresh();
            updateMarkedOnly();
        }

        /** Пересобирает строки по тем же правилам, по которым показывает изменения дерево. */
        private void reload()
        {
            if (table.isDisposed() || !(treeViewer.getContentProvider() instanceof ITreeContentProvider provider))
                return;
            Object input = treeViewer.getInput();
            if (input == null)
                return;
            rows.clear();
            for (Object root : filtered(provider.getElements(input), input))
                collect(provider, root, 0);
            viewer.refresh();
            syncSelectionFromTree();
            contextResolver.reschedule(rows);
            updateMarkedOnly();
        }

        private void collect(ITreeContentProvider provider, Object node, int depth)
        {
            if (depth > 32)
                return;
            Object[] children = filtered(provider.getChildren(node), node);
            if (children.length == 0)
            {
                PreviewRow row = buildRow(node);
                if (row != null)
                    rows.add(row);
                return;
            }
            for (Object child : children)
                collect(provider, child, depth + 1);
        }

        private Object[] filtered(Object[] elements, Object parent)
        {
            Object[] result = elements != null ? elements : new Object[0];
            for (ViewerFilter filter : treeViewer.getFilters())
                result = filter.filter(treeViewer, parent, result);
            return result;
        }

        private PreviewRow buildRow(Object node)
        {
            ILabelProvider labels = treeLabelProvider();
            String text = labels != null ? labels.getText(node) : String.valueOf(Global.invoke(node, "getText")); //$NON-NLS-1$
            IRegion region = occurrenceRegion(node);
            IFile file = fileOf(node);
            PreviewRow row = new PreviewRow(node, text != null ? text : "", file, //$NON-NLS-1$
                region != null ? region.getOffset() : -1, region != null ? region.getLength() : 0);
            if (file != null)
            {
                row.fileName = file.getName();
                row.fileType = fileExtension(file);
                row.module = moduleLabel(file);
            }
            if (!row.needsContext())
                row.parentType = ""; //$NON-NLS-1$
            row.sought = sought;
            row.fullText = isFullTextOccurrence(node);
            if (sought != null && !row.fullText)
                // Вхождение-ссылку EDT уже разрешил как настоящую ссылку на объект — подходит.
                row.suitable = BslOccurrenceContextResolver.SUITABLE_YES;
            return row;
        }

        /** Вхождение найдено полнотекстовым поиском (изменение реализует {@link IFullTextSearchChange}). */
        private static boolean isFullTextOccurrence(Object node)
        {
            for (Object current = node; current != null; current = Global.invoke(current, "getParent")) //$NON-NLS-1$
            {
                Object change = Global.invoke(current, "getChange"); //$NON-NLS-1$
                if (change instanceof IFullTextSearchChange)
                    return true;
                if (change instanceof Change)
                    return false;
            }
            return false;
        }

        private static String fileExtension(IFile file)
        {
            String extension = file.getFileExtension();
            return extension != null ? extension : ""; //$NON-NLS-1$
        }

        /**
         * Место вхождения в тексте изменяемого файла: у листа-группы правок его отдаёт сама группа,
         * у листа-изменения (вхождение полнотекстового поиска — одна правка) берём его правку.
         */
        private static IRegion occurrenceRegion(Object node)
        {
            if (Global.invoke(node, "getChangeGroup") instanceof TextEditBasedChangeGroup group) //$NON-NLS-1$
                return group.getRegion();
            if (!(Global.invoke(node, "getChange") instanceof TextEditBasedChange change)) //$NON-NLS-1$
                return null;
            List<TextEdit> edits = new ArrayList<>();
            collectLeafEdits(Global.invoke(change, "getEdit"), edits); //$NON-NLS-1$
            if (edits.isEmpty())
                return null;
            TextEdit first = edits.get(0);
            return new org.eclipse.jface.text.Region(first.getOffset(), first.getLength());
        }

        private static void collectLeafEdits(Object edit, List<TextEdit> result)
        {
            if (!(edit instanceof TextEdit textEdit))
                return;
            if (textEdit instanceof MultiTextEdit)
            {
                for (TextEdit child : textEdit.getChildren())
                    collectLeafEdits(child, result);
                return;
            }
            result.add(textEdit);
        }

        /** Изменяемый файл — у ближайшего узла-изменения вверх по дереву. */
        private static IFile fileOf(Object node)
        {
            for (Object current = node; current != null; current = Global.invoke(current, "getParent")) //$NON-NLS-1$
            {
                if (Global.invoke(current, "getChange") instanceof Change change) //$NON-NLS-1$
                {
                    IFile file = fileOfChange(change);
                    if (file != null)
                        return file;
                }
            }
            return null;
        }

        /**
         * Файл изменения. У файловых изменений LTK ({@code TextFileChange} и его потомков EDT)
         * {@code getModifiedElement()} — сам {@link IFile}. У изменений модулей EDT
         * (полнотекстовый поиск: {@code FullTextSearchSourceFileChange} и родня) изменяемый
         * элемент — handly-файл ({@code BslFile} и прочие {@code WorkspaceSourceFile}), файл
         * даёт их {@code getFile_()}. Изменения объектов BM ({@code BmObjectTextContentChange})
         * модифицируют {@link EObject} — файл ищем через {@code IResourceLookup}.
         */
        private static IFile fileOfChange(Change change)
        {
            Object modified = change.getModifiedElement();
            if (modified instanceof IFile file)
                return file;
            if (Global.invoke(modified, "getFile_") instanceof IFile handlyFile) //$NON-NLS-1$
                return handlyFile;
            if (modified instanceof EObject model)
            {
                IFile file = modelFile(model);
                if (file != null)
                    return file;
            }
            if (change instanceof TextFileChange textFileChange)
                return textFileChange.getFile();
            // Обёрточные изменения EDT файла не несут — у строки просто нет колонок модуля.
            return null;
        }

        private static IFile modelFile(EObject model)
        {
            IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
            if (lookup == null)
                return null;
            for (EObject owner = model; owner != null; owner = owner.eContainer())
            {
                IFile file = lookup.getPlatformResource(owner);
                if (file != null && file.exists())
                    return file;
                if (owner.eResource() != null)
                {
                    file = lookup.getPlatformResource(owner.eResource());
                    if (file != null && file.exists())
                        return file;
                }
            }
            return null;
        }

        /** Модуль в терминах 1С ({@code Справочник.Номенклатура.МодульОбъекта}); не модуль — пусто. */
        private static String moduleLabel(IFile file)
        {
            if (!BslModuleMethodResolver.isBslModule(file))
                return ""; //$NON-NLS-1$
            String module = GetRef.resolveSetTextModuleName(file);
            return module != null ? module : ""; //$NON-NLS-1$
        }

        /** Значение колонки «Пометка»: помеченное (в т.ч. частично) изменение — «Да», иначе «Нет». */
        private static String markText(PreviewRow row)
        {
            return row != null && activeState(row.node) != INACTIVE ? MARK_YES : MARK_NO;
        }

        private static int activeState(Object node)
        {
            return Global.invoke(node, "getActive") instanceof Integer active ? active.intValue() : INACTIVE; //$NON-NLS-1$
        }

        // ---- Пометки ----

        private void toggleCheck(Widget item)
        {
            if (!(item instanceof TableItem tableItem) || !(tableItem.getData() instanceof PreviewRow row))
                return;
            setChecked(List.of(row), tableItem.getChecked());
        }

        private List<PreviewRow> selectedRows()
        {
            List<PreviewRow> selected = new ArrayList<>();
            IStructuredSelection selection = viewer.getStructuredSelection();
            for (Object element : selection.toList())
            {
                if (element instanceof PreviewRow row)
                    selected.add(row);
            }
            return selected;
        }

        private boolean allChecked(Collection<PreviewRow> target)
        {
            for (PreviewRow row : target)
            {
                if (activeState(row.node) == INACTIVE)
                    return false;
            }
            return !target.isEmpty();
        }

        private void setChecked(Collection<PreviewRow> target, boolean value)
        {
            if (target.isEmpty())
                return;
            for (PreviewRow row : target)
                fireCheck(row.node, value);
            afterCheckChanged();
        }

        /**
         * Дереву посылается тот же {@link CheckStateChangedEvent}, что и при клике по флажку в нём:
         * состояние изменения ставит штатный слушатель LTK, серые пометки родителей — слушатель EDT.
         * Свой {@code setEnabled} в обход них не зовём.
         */
        private void fireCheck(Object node, boolean value)
        {
            treeViewer.setChecked(node, value);
            Global.invoke(treeViewer, "fireCheckStateChanged", //$NON-NLS-1$
                new CheckStateChangedEvent(treeViewer, node, value));
        }

        private void afterCheckChanged()
        {
            // Изменение с запретом на редактирование пометку не принимает — состояние узлов
            // перечитываем, а не считаем свою правку применённой.
            treeViewer.refresh();
            refreshRows();
        }

        private void refreshRows()
        {
            if (table.isDisposed())
                return;
            if (tableMode)
                viewer.refresh();
            updateMarkedOnly();
        }

        // ---- Синхронизация выделения ----

        private void syncSelectionToTree()
        {
            if (syncing || !tableMode)
                return;
            List<PreviewRow> selected = selectedRows();
            if (selected.isEmpty())
                return;
            // В дерево уходит одна строка — за ней следует панель сравнения ниже.
            Object node = selected.get(selected.size() - 1).node;
            syncing = true;
            try
            {
                expandAncestors(node);
                treeViewer.setSelection(new StructuredSelection(node), true);
            }
            finally
            {
                syncing = false;
            }
        }

        private void expandAncestors(Object node)
        {
            List<Object> ancestors = new ArrayList<>();
            for (Object parent = Global.invoke(node, "getParent"); parent != null; //$NON-NLS-1$
                parent = Global.invoke(parent, "getParent")) //$NON-NLS-1$
            {
                ancestors.add(0, parent);
            }
            for (Object ancestor : ancestors)
                treeViewer.expandToLevel(ancestor, 1);
        }

        private void syncSelectionFromTree()
        {
            if (syncing || !tableMode || table.isDisposed())
                return;
            Object selected = treeViewer.getStructuredSelection().getFirstElement();
            PreviewRow target = null;
            for (PreviewRow row : rows)
            {
                if (row.node == selected)
                {
                    target = row;
                    break;
                }
            }
            if (target == null)
                return;
            syncing = true;
            try
            {
                viewer.setSelection(new StructuredSelection(target), true);
                if (interaction != null)
                    interaction.revealSelection();
            }
            finally
            {
                syncing = false;
            }
        }

        // ---- Прочее ----

        private static String cellText(Object element, int column)
        {
            if (!(element instanceof PreviewRow row))
                return ""; //$NON-NLS-1$
            return switch (column)
            {
                case 0 -> markText(row);
                case 1 -> row.text;
                case 2 -> row.fileName;
                case 3 -> row.fileType;
                case 4 -> row.module;
                case 5 -> row.method;
                case 6 -> row.parent;
                case 7 -> row.parentType != null ? row.parentType : UNKNOWN_TYPE;
                case 8 -> suitableCell(row);
                case 9 -> row.syntaxKind;
                case 10 -> row.lineText;
                default -> ""; //$NON-NLS-1$
            };
        }

        /** Значение колонки «Подходит»: «?» до расчёта; пусто — если искомое неизвестно / не BSL-модуль. */
        private static String suitableCell(PreviewRow row)
        {
            if (row == null || row.sought == null || !row.needsContext())
                return ""; //$NON-NLS-1$
            return row.suitable != null ? row.suitable : BslOccurrenceContextResolver.SUITABLE_UNKNOWN;
        }

        private void saveColumnLayout()
        {
            if (table == null || table.isDisposed())
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

        /**
         * Иконка кнопки табличного режима: контур таблицы с разлиновкой, системными цветами.
         * Кэшируется готовая {@link ImageData} — она не зависит от дисплея, в отличие от
         * {@link Image}, и переживает пересоздание тулбара менеджером.
         */
        private static synchronized ImageDescriptor tableModeIconDescriptor()
        {
            if (tableModeIconData == null)
            {
                Display display = Display.getCurrent();
                if (display == null || display.isDisposed())
                    return null;
                int size = 16;
                Image image = new Image(display, size, size);
                GC gc = new GC(image);
                try
                {
                    gc.setAdvanced(true);
                    gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
                    gc.fillRectangle(0, 0, size, size);
                    gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
                    gc.drawRectangle(2, 3, size - 6, size - 7);
                    gc.drawLine(2, 6, size - 4, 6);
                    gc.drawLine(2, 9, size - 4, 9);
                    gc.drawLine(6, 3, 6, size - 4);
                }
                finally
                {
                    gc.dispose();
                }
                tableModeIconData = image.getImageData();
                image.dispose();
            }
            return new ImageDescriptor()
            {
                @Override
                public ImageData getImageData()
                {
                    return tableModeIconData;
                }
            };
        }
    }

    // =========================================================================
    // Начальная расстановка пометок полнотекстовых вхождений
    // =========================================================================

    /**
     * Вхождения старого имени, найденные полнотекстовым поиском (комментарии, строковые литералы,
     * тексты запросов, справка), EDT показывает без пометки — все до одного
     * ({@code CustomPreviewWizardPage.setChange} обходит дерево и снимает пометку с каждого
     * {@link IFullTextSearchChange}). Комфорт полностью замещает эту расстановку: каждому вхождению
     * пометка выставляется ровно по признаку «Подходит» — «Тип родителя совместим с искомым»
     * ({@link BslOccurrenceContextResolver#suitabilityByLiteralParent}). «Да» — помечено, иначе снято.
     *
     * <p>Искомый объект — из полного имени в заголовке мастера ({@link RefactoringWizardTitleHook},
     * {@code SHELL_RENAME_FULL_NAME_KEY}). Имя не разобрано — запасной критерий «полное имя»
     * ({@link #isFullNameOccurrence}). Пометки расставляются один раз на набор изменений; ручной
     * выбор пользователя после этого не перетирается.
     */
    private static final class Marks
    {
        /** Набор изменений, для которого пометки уже расставлены. */
        private static final String PROCESSED_CHANGE_KEY = "tormozit.refactoringPreviewMarksChange"; //$NON-NLS-1$
        /** Набор изменений мастер может подставить в страницу уже после её показа — ждём с повторами. */
        private static final int MARKS_RETRY_DELAY_MS = 300;
        private static final int MARKS_MAX_ATTEMPTS = 20;
        private static final String TEMP_LOG_TOPIC = "refactoring-preview-marks"; //$NON-NLS-1$

        private Marks()
        {
        }

        static void install(Display display)
        {
            display.addFilter(SWT.Show, Marks::handleShow);
        }

        private static void handleShow(Event event)
        {
            if (!(event.widget instanceof Control control) || control.isDisposed())
                return;
            Shell shell = control.getShell();
            if (shell == null || shell.isDisposed() || !isRefactoringWizardDialog(shell))
                return;
            Display display = shell.getDisplay();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> scheduleApply(shell, 0));
        }

        private static void scheduleApply(Shell shell, int attempt)
        {
            if (shell.isDisposed() || attempt >= MARKS_MAX_ATTEMPTS)
                return;
            if (applyMarks(shell))
                return;
            Display display = shell.getDisplay();
            if (display == null || display.isDisposed())
                return;
            display.timerExec(MARKS_RETRY_DELAY_MS, () -> scheduleApply(shell, attempt + 1));
        }

        /**
         * @return {@code false}, если ждём набор изменений на открытой странице предпросмотра —
         *     тогда попытку надо повторить; {@code true} — сделано или ждать нечего
         */
        private static boolean applyMarks(Shell shell)
        {
            if (shell == null || shell.isDisposed())
                return true;
            if (!(shell.getData() instanceof IWizardContainer container))
                return true;
            IWizardPage page = container.getCurrentPage();
            if (page == null || !page.getClass().getName().contains(PREVIEW_PAGE_NAME_PART))
                return true;
            if (!(Global.getField(page, "fChange") instanceof Change change)) //$NON-NLS-1$
                return false;
            if (shell.getData(PROCESSED_CHANGE_KEY) == change)
                return true;
            shell.setData(PROCESSED_CHANGE_KEY, change);

            try
            {
                BslOccurrenceContextResolver.Sought sought =
                    shell.getData(RefactoringWizardTitleHook.SHELL_RENAME_FULL_NAME_KEY) instanceof String fqn
                        ? BslOccurrenceContextResolver.parseSought(fqn) : null;
                int changed = applyFullTextSearchMarks(change, new HashMap<>(), sought);
                if (changed > 0)
                    refreshTree(page);
            }
            catch (RuntimeException e)
            {
                Global.tempLogException(TEMP_LOG_TOPIC, "applyMarks", e); //$NON-NLS-1$
            }
            return true;
        }

        /**
         * Обход дерева изменений тот же, что у {@code CustomPreviewWizardPage}: композит — вглубь,
         * лист — решение по одному изменению. Каждому полнотекстовому вхождению выставляется пометка
         * ровно по признаку «Подходит»: {@code Да} — помечено, иначе снято.
         *
         * @return сколько изменений сменили состояние пометки
         */
        private static int applyFullTextSearchMarks(Change change, Map<Object, String> contentCache,
            BslOccurrenceContextResolver.Sought sought)
        {
            if (change instanceof CompositeChange composite)
            {
                int changed = 0;
                for (Change child : composite.getChildren())
                    changed += applyFullTextSearchMarks(child, contentCache, sought);
                return changed;
            }
            if (!(change instanceof IFullTextSearchChange))
                return 0;
            boolean want = isSuitableChange(change, contentCache, sought);
            if (change.isEnabled() == want)
                return 0;
            change.setEnabled(want);
            // Запрет на редактирование изменение гасит само — считаем только реально изменённые.
            return change.isEnabled() == want ? 1 : 0;
        }

        /**
         * Все правки изменения — вхождение с «Подходит» = «Да»? Критерий — тот же
         * {@link BslOccurrenceContextResolver#suitabilityByLiteralParent} (полнотекстовые вхождения —
         * литералы, комментарии, тексты запросов и справка). Искомое не разобрано — запасной критерий
         * «полное имя» ({@link #isFullNameOccurrence}).
         */
        private static boolean isSuitableChange(Change change, Map<Object, String> contentCache,
            BslOccurrenceContextResolver.Sought sought)
        {
            if (!(change instanceof TextEditBasedChange textChange))
                return false;
            List<TextEdit> edits = new ArrayList<>();
            collectLeafEdits(Global.invoke(change, "getEdit"), edits); //$NON-NLS-1$
            if (edits.isEmpty())
                return false;
            String content = currentContent(textChange, contentCache);
            if (content == null)
                return false;
            for (TextEdit edit : edits)
            {
                if (!isSuitableOccurrence(content, edit.getOffset(), edit.getLength(), sought))
                    return false;
            }
            return true;
        }

        private static boolean isSuitableOccurrence(String content, int offset, int length,
            BslOccurrenceContextResolver.Sought sought)
        {
            if (sought == null)
                return isFullNameOccurrence(content, offset, length);
            if (content == null || length <= 0 || offset < 0 || offset + length > content.length())
                return false;
            String occurrence = content.substring(offset, offset + length);
            String parent = BslOccurrenceContextResolver.parentText(content, offset);
            return BslOccurrenceContextResolver.SUITABLE_YES.equals(
                BslOccurrenceContextResolver.suitabilityByLiteralParent(sought, parent, occurrence));
        }

        private static void collectLeafEdits(Object edit, List<TextEdit> result)
        {
            if (!(edit instanceof TextEdit textEdit))
                return;
            if (textEdit instanceof MultiTextEdit)
            {
                for (TextEdit child : textEdit.getChildren())
                    collectLeafEdits(child, result);
                return;
            }
            result.add(textEdit);
        }

        /**
         * Текущий текст, к которому относятся смещения правок: файл модуля, текст запроса в модели и
         * т.п. Читается один раз на изменяемый элемент — вхождений в одном файле бывают десятки.
         */
        private static String currentContent(TextEditBasedChange change, Map<Object, String> contentCache)
        {
            Object key = change.getModifiedElement();
            if (key != null && contentCache.containsKey(key))
                return contentCache.get(key);
            String content = null;
            try
            {
                content = change.getCurrentContent(new NullProgressMonitor());
            }
            catch (Exception e)
            {
                Global.tempLogException(TEMP_LOG_TOPIC, "getCurrentContent " + change.getName(), e); //$NON-NLS-1$
            }
            if (key != null)
                contentCache.put(key, content);
            return content;
        }

        /**
         * Вхождение {@code [offset, offset + length)} — полное имя объекта: перед ним точка, перед
         * точкой имя типа метаданных, а сразу за ним имя не продолжается.
         */
        private static boolean isFullNameOccurrence(String content, int offset, int length)
        {
            if (content == null || length <= 0 || offset < 1 || offset + length > content.length())
                return false;
            int after = offset + length;
            if (after < content.length() && isNamePart(content.charAt(after)))
                return false;
            if (content.charAt(offset - 1) != '.')
                return false;
            int typeEnd = offset - 1;
            int typeStart = typeEnd;
            while (typeStart > 0 && isNamePart(content.charAt(typeStart - 1)))
                typeStart--;
            if (typeStart == typeEnd)
                return false;
            return MdTypeMapping.isMdTypeToken(content.substring(typeStart, typeEnd));
        }

        private static boolean isNamePart(char c)
        {
            return c == '_' || Character.isLetterOrDigit(c);
        }

        /**
         * Пометки в дереве берутся из состояния изменений при отрисовке строки
         * ({@code ChangeElementTreeViewer.applyCheckedState} по {@code PreviewNode.getActive()}),
         * поэтому после правки состояния достаточно перерисовать дерево.
         */
        private static void refreshTree(IWizardPage page)
        {
            if (!(Global.getField(page, "fTreeViewer") instanceof CheckboxTreeViewer viewer)) //$NON-NLS-1$
                return;
            Control control = viewer.getControl();
            if (control == null || control.isDisposed())
                return;
            viewer.refresh();
        }
    }

    // =========================================================================
    // Панель «Текущая строка» в панели сравнения + запоминание размера окна
    // =========================================================================

    /**
     * Панель «Текущая строка» ({@link CompareCurrentLinesPanel}) в предпросмотре изменений: панель
     * сравнения там — {@code TextEditChangePreviewViewer$ComparePreviewer}, наследник
     * {@link CompareViewerSwitchingPane} со своим {@code CompareConfiguration} (без
     * {@link org.eclipse.compare.CompareEditorInput}), поэтому pane ищется обходом дерева виджетов.
     * Внутри — {@link TextMergeViewer}, синхронизация — {@link TwoSideCurrentLinesSync}. Стороны
     * подписаны {@link #LABEL_BEFORE} / {@link #LABEL_AFTER}. Плюс запоминание размера окна между
     * открытиями (штатный {@code RefactoringWizardDialog2} размер не сохраняет).
     */
    private static final class CurrentLines
    {
        private static final String LABEL_BEFORE = "Текст ДО рефакторинга"; //$NON-NLS-1$
        private static final String LABEL_AFTER = "Текст ПОСЛЕ рефакторинга"; //$NON-NLS-1$

        private static final String SHELL_HANDLED_KEY = "tormozit.refactoringPreviewCurrentLinesShell"; //$NON-NLS-1$
        private static final String PANEL_ATTACHED_KEY = "tormozit.refactoringPreviewCurrentLinesAttached"; //$NON-NLS-1$
        private static final String SIZE_MEMORY_KEY = "tormozit.refactoringWizardSizeMemory"; //$NON-NLS-1$
        private static final String SIZE_RESTORED_ON_PREVIEW_KEY = "tormozit.refactoringWizardSizeRestoredOnPreview"; //$NON-NLS-1$
        private static final String SETTINGS_SECTION = "tormozit.refactoringWizardDialog"; //$NON-NLS-1$
        private static final String KEY_DIALOG_WIDTH = "DIALOG_WIDTH"; //$NON-NLS-1$
        private static final String KEY_DIALOG_HEIGHT = "DIALOG_HEIGHT"; //$NON-NLS-1$

        private static final int MAX_FAST_ATTEMPTS = 40;
        private static final int FAST_RETRY_DELAY_MS = 50;
        /** Предпросмотр может быть не первой страницей мастера — ждём дольше, но не бесконечно. */
        private static final int MAX_SLOW_ATTEMPTS = 1200;
        private static final int SLOW_RETRY_DELAY_MS = 500;

        private static final int ATTACH_WAIT = 0;
        private static final int ATTACH_DONE = 1;

        private CurrentLines()
        {
        }

        static void install(Display display)
        {
            display.addFilter(SWT.Show, CurrentLines::handleShow);
        }

        private static void handleShow(Event event)
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (Boolean.TRUE.equals(shell.getData(SHELL_HANDLED_KEY)))
                return;
            if (!isRefactoringWizardDialog(shell))
                return;
            shell.setData(SHELL_HANDLED_KEY, Boolean.TRUE);
            installShellSizeMemory(shell);
            scheduleAttach(shell, 0, false);
        }

        /**
         * Размер окна мастера рефакторинга — восстановление при открытии и запоминание при закрытии.
         */
        private static void installShellSizeMemory(Shell shell)
        {
            if (Boolean.TRUE.equals(shell.getData(SIZE_MEMORY_KEY)))
                return;
            shell.setData(SIZE_MEMORY_KEY, Boolean.TRUE);

            // Размер запоминается по событию Resize: в DisposeListener окно ОС уже уничтожено
            // и getSize() отдаёт неверные значения.
            Point[] lastSize = { null };
            shell.addListener(SWT.Resize, e ->
            {
                if (shell.getMaximized() || shell.getMinimized())
                    return;
                Point size = shell.getSize();
                if (size.x > 0 && size.y > 0)
                    lastSize[0] = size;
            });
            shell.addDisposeListener(e -> saveShellSize(lastSize[0]));

            restoreShellSize(shell);
            Display display = shell.getDisplay();
            if (display != null && !display.isDisposed())
            {
                display.asyncExec(() -> restoreShellSize(shell));
                display.timerExec(100, () -> restoreShellSize(shell));
            }
        }

        private static void restoreShellSize(Shell shell)
        {
            if (shell == null || shell.isDisposed() || shell.getMaximized())
                return;
            IDialogSettings settings = dialogSettings();
            if (settings.get(KEY_DIALOG_WIDTH) == null || settings.get(KEY_DIALOG_HEIGHT) == null)
                return;

            int width;
            int height;
            try
            {
                width = settings.getInt(KEY_DIALOG_WIDTH);
                height = settings.getInt(KEY_DIALOG_HEIGHT);
            }
            catch (NumberFormatException e)
            {
                return;
            }
            if (width <= 0 || height <= 0)
                return;

            Point current = shell.getSize();
            if (current.x == width && current.y == height)
                return;

            Rectangle old = shell.getBounds();
            // Центр окна не сдвигаем — иначе увеличенное окно «уползает» вправо-вниз.
            Rectangle target = new Rectangle(old.x + (old.width - width) / 2,
                old.y + (old.height - height) / 2, width, height);
            shell.setBounds(clampToMonitor(shell, target));
        }

        private static void saveShellSize(Point size)
        {
            if (size == null || size.x <= 0 || size.y <= 0)
                return;
            IDialogSettings settings = dialogSettings();
            settings.put(KEY_DIALOG_WIDTH, size.x);
            settings.put(KEY_DIALOG_HEIGHT, size.y);
        }

        private static Rectangle clampToMonitor(Shell shell, Rectangle bounds)
        {
            Rectangle area = shell.getMonitor().getClientArea();
            Point minimum = shell.getMinimumSize();
            int width = Math.max(minimum.x, Math.min(bounds.width, area.width));
            int height = Math.max(minimum.y, Math.min(bounds.height, area.height));
            int x = Math.max(area.x, Math.min(bounds.x, area.x + area.width - width));
            int y = Math.max(area.y, Math.min(bounds.y, area.y + area.height - height));
            return new Rectangle(x, y, width, height);
        }

        private static IDialogSettings dialogSettings()
        {
            IDialogSettings top = Activator.getDefault().getDialogSettings();
            IDialogSettings section = top.getSection(SETTINGS_SECTION);
            if (section == null)
                section = top.addNewSection(SETTINGS_SECTION);
            return section;
        }

        private static void scheduleAttach(Shell shell, int attempt, boolean slow)
        {
            Display display = shell.getDisplay();
            if (display == null || display.isDisposed())
                return;
            int max = slow ? MAX_SLOW_ATTEMPTS : MAX_FAST_ATTEMPTS;
            int delay = slow ? SLOW_RETRY_DELAY_MS : (attempt == 0 ? 100 : FAST_RETRY_DELAY_MS);
            if (attempt >= max)
            {
                if (!slow)
                    scheduleAttach(shell, 0, true);
                return;
            }
            display.timerExec(delay, () ->
            {
                if (shell.isDisposed())
                    return;
                if (tryAttach(shell) == ATTACH_WAIT)
                    scheduleAttach(shell, attempt + 1, slow);
            });
        }

        private static int tryAttach(Shell shell)
        {
            CompareViewerSwitchingPane pane = findComparePane(shell);
            if (pane == null || pane.isDisposed())
                return ATTACH_WAIT;
            if (Boolean.TRUE.equals(pane.getData(PANEL_ATTACHED_KEY)))
                return ATTACH_DONE;

            Viewer viewer = pane.getViewer();
            if (!(viewer instanceof TextMergeViewer mergeViewer))
                return ATTACH_WAIT;

            Control viewerControl = viewer.getControl();
            if (viewerControl == null || viewerControl.isDisposed())
                return ATTACH_WAIT;
            if (viewerControl.getParent() != pane)
                return ATTACH_WAIT;

            StyledText leftText = MergeViewerReflection.extractStyledText(mergeViewer, "fLeft"); //$NON-NLS-1$
            StyledText rightText = MergeViewerReflection.extractStyledText(mergeViewer, "fRight"); //$NON-NLS-1$
            if (leftText == null || leftText.isDisposed() || rightText == null || rightText.isDisposed())
                return ATTACH_WAIT;

            pane.setData(PANEL_ATTACHED_KEY, Boolean.TRUE);
            attach(pane, viewerControl, mergeViewer, leftText, rightText, shell);
            // Мастер может ещё раз подогнать размер под страницу предпросмотра — перекрываем
            // один раз, чтобы смена выбранного изменения не откатывала ручной размер.
            if (!Boolean.TRUE.equals(shell.getData(SIZE_RESTORED_ON_PREVIEW_KEY)))
            {
                shell.setData(SIZE_RESTORED_ON_PREVIEW_KEY, Boolean.TRUE);
                restoreShellSize(shell);
                Display display = shell.getDisplay();
                if (display != null && !display.isDisposed())
                    display.asyncExec(() -> restoreShellSize(shell));
            }
            return ATTACH_DONE;
        }

        /** В окне мастера рефакторинга панель предпросмотра одна — берём первую найденную. */
        private static CompareViewerSwitchingPane findComparePane(Composite composite)
        {
            if (composite == null || composite.isDisposed())
                return null;
            for (Control child : composite.getChildren())
            {
                if (child instanceof CompareViewerSwitchingPane pane && !pane.isDisposed())
                    return pane;
                if (child instanceof Composite nested)
                {
                    CompareViewerSwitchingPane found = findComparePane(nested);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private static void attach(CompareViewerSwitchingPane pane, Control viewerControl, TextMergeViewer viewer,
            StyledText leftText, StyledText rightText, Shell shell)
        {
            /*
             * Содержимое pane (ViewForm) — control вьюера напрямую. Оборачиваем: control вьюера и
             * панель «Текущая строка» в свой composite, его же подставляем как содержимое pane.
             */
            Composite wrapper = new Composite(pane, SWT.NONE);
            GridLayout wrapperLayout = new GridLayout(1, false);
            wrapperLayout.marginWidth = 0;
            wrapperLayout.marginHeight = 0;
            wrapper.setLayout(wrapperLayout);

            viewerControl.setParent(wrapper);
            viewerControl.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            CompareConfiguration config = resolveConfig(pane, viewer);
            String semanticLeft = LABEL_BEFORE;
            String semanticRight = LABEL_AFTER;
            applyHeaderLabels(config, viewer, semanticLeft, semanticRight);

            CompareCurrentLinesPanel panel = CompareCurrentLinesPanel.create(wrapper, semanticLeft, semanticRight);
            panel.getControl().setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

            pane.setContent(wrapper);
            pane.layout(true, true);
            applyHeaderLabels(config, viewer, semanticLeft, semanticRight);
            scheduleHeaderLabelRefresh(config, viewer, semanticLeft, semanticRight);

            addToolbarActions(pane, panel, shell);

            TwoSideCurrentLinesSync.hook(panel, leftText, rightText, viewer, config, semanticLeft, semanticRight);
            CompareWhitespaceSupport.installTwoWay(viewer);

            /*
             * Смена выбранного изменения в дереве «Вносимые изменения» переиспользует тот же вьюер,
             * но при смене типа предпросмотра вьюер пересоздаётся, и наш wrapper уничтожается вместе
             * с панелью. Тогда присоединяемся заново.
             */
            wrapper.addDisposeListener(e ->
            {
                if (!pane.isDisposed())
                    pane.setData(PANEL_ATTACHED_KEY, null);
                if (!shell.isDisposed())
                    scheduleAttach(shell, 0, false);
            });
        }

        /**
         * {@code CompareConfiguration} предпросмотра: своё поле {@code ComparePreviewer}, иначе —
         * у самого вьюера ({@code ContentMergeViewer.getCompareConfiguration()} защищённый).
         */
        private static CompareConfiguration resolveConfig(CompareViewerSwitchingPane pane, TextMergeViewer viewer)
        {
            Object fromPane = Global.getField(pane, "fCompareConfiguration"); //$NON-NLS-1$
            if (fromPane instanceof CompareConfiguration config)
                return config;
            Object fromViewer = Global.invoke(viewer, "getCompareConfiguration"); //$NON-NLS-1$
            return fromViewer instanceof CompareConfiguration config ? config : null;
        }

        /**
         * Пишет подписи сторон в {@link CompareConfiguration} и прямо в
         * {@code ContentMergeViewer.fLeftLabel}/{@code fRightLabel}: только config недостаточно
         * (шапка уже нарисована), только CLabel недостаточно (штатный {@code updateHeader} при смене
         * выбранного изменения перечитывает подписи из config и откатывает наши).
         */
        private static void applyHeaderLabels(CompareConfiguration config, TextMergeViewer viewer,
            String leftLabel, String rightLabel)
        {
            if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
                return;
            if (config != null)
            {
                config.setLeftLabel(leftLabel);
                config.setRightLabel(rightLabel);
            }
            boolean mirrored = config != null && config.isMirrored();
            MergeViewerReflection.setLabelText(viewer, "fLeftLabel", //$NON-NLS-1$
                TwoSideCurrentLinesSync.visualSideLabel(leftLabel, rightLabel, mirrored, true));
            MergeViewerReflection.setLabelText(viewer, "fRightLabel", //$NON-NLS-1$
                TwoSideCurrentLinesSync.visualSideLabel(leftLabel, rightLabel, mirrored, false));
        }

        /** Повторная установка шапки после отложенного {@code updateHeader} (layout, смена входа). */
        private static void scheduleHeaderLabelRefresh(CompareConfiguration config, TextMergeViewer viewer,
            String leftLabel, String rightLabel)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
            display.timerExec(100, () -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
            display.timerExec(500, () -> applyHeaderLabels(config, viewer, leftLabel, rightLabel));
        }

        /**
         * Переключатели «Текущие строки» и маркеров вхождений — в командную панель самого
         * просмотрщика сравнения. Тулбар принадлежит {@code ToolBarManager}, его {@code update(true)}
         * чужие {@code ToolItem} не сохраняет — поэтому шелл помечаем обслуженным для универсального
         * скана {@link OccurrencesToggleHook}, а уже добавленное им убираем.
         */
        private static void addToolbarActions(CompareViewerPane pane, CompareCurrentLinesPanel panel, Shell shell)
        {
            IToolBarManager toolBarManager = CompareViewerPane.getToolBarManager(pane);
            if (toolBarManager == null)
                return;

            IContributionItem[] existingItems = toolBarManager.getItems();
            toolBarManager.removeAll();

            toolBarManager.add(panel.createVisibilityToggleAction());
            toolBarManager.add(OccurrencesToggleHook.createToggleAction());
            toolBarManager.add(new Separator());
            for (IContributionItem item : existingItems)
            {
                // Кнопка вхождений от прошлого присоединения — иначе в панели два переключателя
                if (!OccurrencesToggleHook.isStaleToggleItem(item))
                    toolBarManager.add(item);
            }

            toolBarManager.update(true);

            OccurrencesToggleHook.markShellHandled(shell);
            OccurrencesToggleHook.removeDialogItems(shell);
        }
    }
}
