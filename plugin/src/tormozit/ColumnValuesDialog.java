package tormozit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Модальное окно «Различные значения колонки»: список различных значений активной колонки исходной
 * таблицы/дерева + число строк с этим значением, фильтр по подстроке сверху. Выбор строки сразу
 * накладывает отбор в источнике ({@link #owner}) по выбранному значению.
 *
 * <p>Источник абстрагирован через {@link Owner} — реализуют {@link FormTableInteraction} (Table) и
 * {@link TreeColumnValueFilterSupport} (Tree, issue #266). Общий класс, а не по копии на источник:
 * два реальных потребителя, вынесен в свой файл.
 */
final class ColumnValuesDialog extends Dialog
{
    private static final String SETTINGS_SECTION = "FormTableColumnValuesDialog"; //$NON-NLS-1$
    private static final String KEY_COL_VALUE_WIDTH = "colValueWidth"; //$NON-NLS-1$
    private static final String KEY_COL_COUNT_WIDTH = "colCountWidth"; //$NON-NLS-1$
    /** Был ли режим заполнения по ширине активен при закрытии — приоритетнее сохранённых пиксельных
     * ширин, которые могут не совпасть впритык с шириной таблицы при следующем открытии. */
    private static final String KEY_COL_FILL_MODE = "colFillMode"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
    private static final int DEFAULT_VALUE_COL_WIDTH = 260;
    private static final int DEFAULT_COUNT_COL_WIDTH = 80;
    private static final int MIN_VALUE_COL_WIDTH = 100;
    private static final int MIN_COUNT_COL_WIDTH = 50;

    /** Строка списка: значение колонки исходной таблицы/дерева + число строк с этим значением. */
    static final class ValueRow
    {
        final String value;
        final int count;

        ValueRow(String value, int count)
        {
            this.value = value;
            this.count = count;
        }
    }

    /** Источник отбора — {@link FormTableInteraction} (Table) или {@link TreeColumnValueFilterSupport} (Tree). */
    interface Owner
    {
        /** Наложить отбор на {@code column} по конкретному значению. */
        void applyColumnFilterValue(int column, String value);

        /** Снять отбор по {@code column}. */
        void clearColumnFilter(int column);

        /** См. {@code honorOtherFilters} — среди строк, проходящих остальные отборы, или по всем. */
        List<ValueRow> computeColumnValueRows(int column, boolean honorOtherFilters);
    }

    private final Owner owner;
    private final int column;
    private final String columnHeader;
    private final List<ValueRow> rows;
    private final String initialValue;
    /** Значение отбора по {@code column} ДО открытия окна ({@code null} — отбора не было); восстанавливается при закрытии не через ОК. */
    private final String originalFilterValue;
    /** Текущий отбор источника БЕЗ {@code column} — для копируемого поля сверху ({@code ""}, если его нет). */
    private final String otherFiltersDescription;

    private TableViewer viewer;
    private Table dialogTable;
    private TableColumn valueColumn;
    private TableColumn countColumn;
    private FilterInputBox filterInput;
    private Button honorOtherFiltersCheckbox;
    private FormTableInteraction dialogInteraction;
    private boolean sortByCount;
    private boolean sortAscending = true;
    /** На программное выделение текущей строки при открытии отбор в owner не накладывается. */
    private boolean suppressSelectionApply;
    private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

    ColumnValuesDialog(Shell parentShell, Owner owner, int column, String columnHeader,
        List<ValueRow> rows, String initialValue, String originalFilterValue, String otherFiltersDescription)
    {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE);
        this.owner = owner;
        this.column = column;
        this.columnHeader = columnHeader;
        this.rows = rows;
        this.initialValue = initialValue;
        this.originalFilterValue = originalFilterValue;
        this.otherFiltersDescription = otherFiltersDescription != null ? otherFiltersDescription : ""; //$NON-NLS-1$
    }

    /**
     * X/Escape по умолчанию просто закрывают окно (в {@link Window} без явного {@code
     * setReturnCode} возврат остаётся {@code OK} — 0 по умолчанию), из-за чего в {@link #close()}
     * нельзя было бы отличить их от настоящего ОК. Заворачиваем оба пути в {@link #cancelPressed()}
     * — как нажатие кнопки «Отмена» — чтобы код возврата всегда был {@code CANCEL}.
     */
    @Override
    protected void handleShellCloseEvent()
    {
        cancelPressed();
    }

    @Override
    protected void configureShell(Shell newShell)
    {
        super.configureShell(newShell);
        String title = columnHeader != null && !columnHeader.isEmpty()
            ? "Различные значения колонки «" + columnHeader + "»" //$NON-NLS-1$ //$NON-NLS-2$
            : "Различные значения колонки"; //$NON-NLS-1$
        newShell.setText(Global.withPluginWindowTitle(title));
    }

    @Override
    protected Point getInitialSize()
    {
        IDialogSettings settings = dialogSettings();
        if (settings.get("DIALOG_WIDTH") == null) //$NON-NLS-1$
            return new Point(420, 480);
        return super.getInitialSize();
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings()
    {
        return dialogSettings();
    }

    @Override
    protected int getDialogBoundsStrategy()
    {
        return DIALOG_PERSISTSIZE | DIALOG_PERSISTLOCATION;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite)super.createDialogArea(parent);
        area.setLayout(new GridLayout(1, false));

        Composite otherFiltersRow = new Composite(area, SWT.NONE);
        otherFiltersRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        otherFiltersRow.setLayout(new GridLayout(2, false));

        honorOtherFiltersCheckbox = new Button(otherFiltersRow, SWT.CHECK);
        honorOtherFiltersCheckbox.setText("Учитывать отбор"); //$NON-NLS-1$
        honorOtherFiltersCheckbox.setToolTipText(
            "Учитывать текущий отбор источника (кроме этой колонки) при группировке различных значений"); //$NON-NLS-1$
        honorOtherFiltersCheckbox.setSelection(true);
        honorOtherFiltersCheckbox.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        honorOtherFiltersCheckbox.addListener(SWT.Selection, e -> reloadRows());

        Text otherFiltersText = new Text(otherFiltersRow, SWT.BORDER | SWT.READ_ONLY);
        otherFiltersText.setText(otherFiltersDescription);
        otherFiltersText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        filterInput = FilterInputBox.forColumnValues(area, this::applyTextFilter);
        filterInput.widget().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite tableHost = new Composite(area, SWT.NONE);
        tableHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TableColumnLayout tableLayout = new TableColumnLayout();
        tableHost.setLayout(tableLayout);

        // SWT.MULTI — множественное выделение полезно для копирования нескольких значений разом.
        viewer = new TableViewer(tableHost, SWT.FULL_SELECTION | SWT.MULTI | SWT.BORDER);
        dialogTable = viewer.getTable();
        dialogTable.setHeaderVisible(true);
        ThemeAwareColors.applyGridLines(dialogTable);

        IDialogSettings settings = dialogSettings();
        boolean hasSavedColumnWidths = FormTableColumnState.hasSavedColumnWidths(
            settings, KEY_COL_FILL_MODE, KEY_COL_VALUE_WIDTH, KEY_COL_COUNT_WIDTH);
        int valueWidth = FormTableColumnState.readWidth(settings, KEY_COL_VALUE_WIDTH,
            DEFAULT_VALUE_COL_WIDTH, MIN_VALUE_COL_WIDTH);
        int countWidth = FormTableColumnState.readWidth(settings, KEY_COL_COUNT_WIDTH,
            DEFAULT_COUNT_COL_WIDTH, MIN_COUNT_COL_WIDTH);

        TableViewerColumn valueVc = new TableViewerColumn(viewer, SWT.NONE);
        valueColumn = valueVc.getColumn();
        valueColumn.setText("Значение"); //$NON-NLS-1$
        // SelectionAwareStyledCellLabelProvider, а НЕ DelegatingStyledCellLabelProvider: у
        // последнего нет способа передать StyledCellLabelProvider.COLORS_ON_SELECTION, и без
        // этого флага JFace намеренно игнорирует цвета StyleRange на ВЫДЕЛЕННЫХ строках —
        // подсветка вхождений фильтра пропадала именно на активной строке. См. класс-javadoc
        // SelectionAwareStyledCellLabelProvider и ObjectSetsView.NameLabelProvider (тот же приём).
        valueVc.setLabelProvider(new SelectionAwareStyledCellLabelProvider(new ValueLabelProvider()));
        tableLayout.setColumnData(valueColumn, new ColumnPixelData(valueWidth, true, true));

        TableViewerColumn countVc = new TableViewerColumn(viewer, SWT.RIGHT);
        countColumn = countVc.getColumn();
        countColumn.setText("Строк"); //$NON-NLS-1$
        countVc.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof ValueRow row ? Integer.toString(row.count) : ""; //$NON-NLS-1$
            }
        });
        tableLayout.setColumnData(countColumn, new ColumnPixelData(countWidth, true, true));

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.addFilter(new ViewerFilter()
        {
            @Override
            public boolean select(Viewer v, Object parentElement, Object element)
            {
                return element instanceof ValueRow row && matcher.matches(row.value);
            }
        });

        dialogInteraction = new FormTableInteraction(dialogTable, viewer, (item, col) ->
        {
            if (!(item.getData() instanceof ValueRow row))
                return ""; //$NON-NLS-1$
            return col == 0 ? row.value : Integer.toString(row.count);
        });
        // Owner-draw для «Значение» — как у остальных стилизованных колонок в проекте
        // (ObjectSetsView.nameColumn и т.п.); цвет подсветки вхождений на активной строке
        // обеспечивает SelectionAwareStyledCellLabelProvider выше (COLORS_ON_SELECTION), это —
        // независимая настройка перерисовки самого FormTableInteraction.
        dialogInteraction.setOwnerDrawColumns(valueColumn);
        FormTableColumnState.loadOrder(settings, KEY_COL_ORDER, dialogTable);
        dialogInteraction.install(hasSavedColumnWidths);

        installSortListeners();

        viewer.setInput(rows);
        applySort();

        // Отбор в owner накладывается по АКТИВНОЙ ячейке (dialogInteraction.selectedItem()),
        // не по первому элементу выделения viewer — при мультивыделении (Ctrl/Shift-клик для
        // копирования) это осталось бы последним КЛИКНУТЫМ значением, а не произвольным первым
        // по порядку. Слушатель добавлен ПОСЛЕ dialogInteraction.install(), поэтому активная
        // ячейка внутри dialogInteraction уже обновлена к моменту его срабатывания.
        dialogTable.addListener(SWT.Selection, e -> applyActiveRowFilter());
        dialogTable.addListener(SWT.MouseDoubleClick, e -> okPressed());
        installFilterKeyNavigation();

        selectInitialValue();
        filterInput.scheduleFocusWhenReady();

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "ОК", true); //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
    }

    /**
     * Отбор по активной строке накладывается в {@link #applyActiveRowFilter()} только в ОТВЕТ
     * на {@code SWT.Selection} (клик/навигация клавишами — см. {@link
     * #selectTableIndexAndNotify(int)}). Если Enter нажат СРАЗУ при открытии окна, до любой
     * навигации — выделение с момента открытия не менялось, новый {@code SWT.Selection} не
     * возникал, и без явного вызова здесь окно закрывалось бы через ОК, ничего не применив.
     */
    @Override
    protected void okPressed()
    {
        applyActiveRowFilter();
        super.okPressed();
    }

    /**
     * Два режима закрытия: ОК/двойной клик по строке — оставить текущий (уже применённый вживую)
     * отбор; «Отмена», X, Escape — откатить {@code column} к {@link #originalFilterValue}
     * (тому, что было до открытия окна), а не оставлять последнее значение, просмотренное при
     * навигации по списку.
     */
    @Override
    public boolean close()
    {
        saveColumnWidths();
        if (getReturnCode() != OK)
        {
            if (originalFilterValue != null)
                owner.applyColumnFilterValue(column, originalFilterValue);
            else
                owner.clearColumnFilter(column);
        }
        return super.close();
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private void saveColumnWidths()
    {
        if (valueColumn == null || countColumn == null
            || valueColumn.isDisposed() || countColumn.isDisposed())
            return;
        boolean fillMode = dialogInteraction != null && dialogInteraction.isColumnsExactFill();
        FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER,
            KEY_COL_FILL_MODE, fillMode,
            new String[] { KEY_COL_VALUE_WIDTH, KEY_COL_COUNT_WIDTH },
            new TableColumn[] { valueColumn, countColumn }, dialogTable);
    }

    private void applyTextFilter()
    {
        matcher = new SmartMatcher(filterInput != null ? filterInput.getText() : null);
        if (viewer == null || viewer.getControl().isDisposed())
            return;
        viewer.refresh();
        // Прежнее выделение отфильтровано текстом — переносим его на первую видимую строку
        // (иначе после сужения списка активная ячейка держится за скрытую строку).
        FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(dialogTable);
    }

    /** ↓/↑/PgUp/PgDn из поля фильтра — навигация по списку; Enter — как двойной клик (закрыть). */
    private void installFilterKeyNavigation()
    {
        if (filterInput == null || filterInput.isDisposed())
            return;
        Control filterKeys = filterInput.inputControl();
        if (filterKeys == null)
            filterKeys = filterInput.widget();
        FilterInputBoxListNavigation.installTableOpenOnEnter(filterKeys, dialogTable,
            this::selectTableIndexAndNotify, this::okPressed);
    }

    /**
     * Программное {@code table.setSelection(idx)} — как {@code viewer.setSelection(...)} — НЕ
     * генерирует {@code SWT.Selection} (задокументированное соглашение SWT: {@code setXxx} не
     * стреляет событиями, в отличие от настоящего клика мышью). {@code dialogInteraction}
     * обновляет активную ячейку/подсветку ТОЛЬКО в ответ на {@code SWT.Selection}/{@code
     * MouseDown} — без ручного {@code notifyListeners} строка визуально не активировалась бы и
     * отбор не накладывался бы: навигация клавишами вела бы себя иначе, чем клик (тот же приём,
     * что в {@code FilterInputBoxListNavigation.fireTableSelection}, — там не подходит напрямую,
     * т.к. используется свой {@code onIndexChanged}).
     */
    private void selectTableIndexAndNotify(int idx)
    {
        if (idx < 0 || idx >= dialogTable.getItemCount())
            return;
        dialogTable.setSelection(idx);
        dialogTable.showSelection();
        TableItem[] selected = dialogTable.getSelection();
        if (selected.length == 0)
            return;
        Event selectionEvent = new Event();
        selectionEvent.type = SWT.Selection;
        selectionEvent.widget = dialogTable;
        selectionEvent.item = selected[0];
        dialogTable.notifyListeners(SWT.Selection, selectionEvent);
    }

    private void applyActiveRowFilter()
    {
        if (suppressSelectionApply)
            return;
        TableItem active = dialogInteraction.selectedItem();
        if (active != null && !active.isDisposed() && active.getData() instanceof ValueRow row)
            owner.applyColumnFilterValue(column, row.value);
    }

    /**
     * Значение с подсветкой вхождений {@link #matcher} (плоский AND, без иерархии — колонка
     * содержит одиночные значения ячеек, не составные имена).
     */
    private StyledString highlightedText(String text)
    {
        String value = text != null ? text : ""; //$NON-NLS-1$
        if (matcher.isEmpty || value.isEmpty())
            return new StyledString(value);
        List<SmartMatcher.HighlightRange> raw = matcher.getHighlightRanges(value);
        if (raw.isEmpty())
            return new StyledString(value);
        List<int[]> ranges = new ArrayList<>();
        for (SmartMatcher.HighlightRange hr : raw)
            ranges.add(new int[] { hr.offset, hr.offset + hr.length });
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges)
        {
            if (merged.isEmpty() || r[0] >= merged.get(merged.size() - 1)[1])
                merged.add(r);
            else
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], r[1]);
        }
        StyledString.Styler styler = SmartMatchHighlight.styler(dialogTable);
        StyledString styled = new StyledString();
        int pos = 0;
        for (int[] r : merged)
        {
            if (r[0] > pos)
                styled.append(value.substring(pos, r[0]));
            int end = Math.min(r[1], value.length());
            if (r[0] < end)
                styled.append(value.substring(r[0], end), styler);
            pos = end;
        }
        if (pos < value.length())
            styled.append(value.substring(pos));
        return styled;
    }

    /** Отдельный класс, а не анонимный: {@code LabelProvider} и {@code IStyledLabelProvider} нельзя совместить в одном anonymous-выражении. */
    private final class ValueLabelProvider extends LabelProvider
        implements DelegatingStyledCellLabelProvider.IStyledLabelProvider
    {
        @Override
        public StyledString getStyledText(Object element)
        {
            return element instanceof ValueRow row ? highlightedText(row.value) : new StyledString();
        }
    }

    private void installSortListeners()
    {
        valueColumn.addListener(SWT.Selection, e ->
        {
            if (sortByCount)
            {
                sortByCount = false;
                sortAscending = true;
            }
            else
                sortAscending = !sortAscending;
            applySort();
        });
        countColumn.addListener(SWT.Selection, e ->
        {
            if (!sortByCount)
            {
                sortByCount = true;
                sortAscending = true;
            }
            else
                sortAscending = !sortAscending;
            applySort();
        });
    }

    private void applySort()
    {
        Comparator<ValueRow> comparator = sortByCount
            ? Comparator.comparingInt((ValueRow r) -> r.count)
            : Comparator.comparing((ValueRow r) -> r.value, String.CASE_INSENSITIVE_ORDER);
        if (!sortAscending)
            comparator = comparator.reversed();
        rows.sort(comparator);
        if (!dialogTable.isDisposed())
        {
            dialogTable.setSortColumn(sortByCount ? countColumn : valueColumn);
            dialogTable.setSortDirection(sortAscending ? SWT.UP : SWT.DOWN);
        }
        if (viewer != null && !viewer.getControl().isDisposed())
            viewer.refresh();
    }

    /** Флажок «Учитывать отбор» переключён — пересчитать список различных значений с нуля. */
    private void reloadRows()
    {
        // ValueRow после пересчёта — новые объекты без equals по value: штатный
        // preservingSelection() у TableViewer не находит прежнее выделение → строка теряется.
        String keepValue = currentSelectedValue();
        boolean honorOtherFilters = honorOtherFiltersCheckbox != null && honorOtherFiltersCheckbox.getSelection();
        List<ValueRow> recomputed = owner.computeColumnValueRows(column, honorOtherFilters);
        rows.clear();
        rows.addAll(recomputed);
        applySort();
        restoreSelectionByValue(keepValue);
    }

    /** Значение активной (или первой выделенной) строки — ключ для восстановления после reload. */
    private String currentSelectedValue()
    {
        if (dialogInteraction != null)
        {
            TableItem active = dialogInteraction.selectedItem();
            if (active != null && !active.isDisposed() && active.getData() instanceof ValueRow row)
                return row.value;
        }
        if (dialogTable != null && !dialogTable.isDisposed())
        {
            TableItem[] sel = dialogTable.getSelection();
            if (sel.length > 0 && sel[0].getData() instanceof ValueRow row)
                return row.value;
        }
        return null;
    }

    /**
     * После {@link #reloadRows()} вернуть выделение на то же значение (по строке value среди
     * видимых {@code TableItem}). Если значения больше нет в списке — первая видимая строка.
     */
    private void restoreSelectionByValue(String value)
    {
        if (dialogTable == null || dialogTable.isDisposed() || dialogTable.getItemCount() == 0)
            return;
        if (value != null)
        {
            TableItem[] items = dialogTable.getItems();
            for (int i = 0; i < items.length; i++)
            {
                if (items[i].getData() instanceof ValueRow row && value.equals(row.value))
                {
                    selectTableIndexAndNotify(i);
                    return;
                }
            }
        }
        FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(dialogTable);
    }

    private void selectInitialValue()
    {
        if (initialValue == null)
            return;
        // Отбор ещё пуст (фильтр по подстроке только что открыт) — порядок rows == порядок
        // видимых TableItem, индекс в списке валиден и как индекс в dialogTable.
        for (int i = 0; i < rows.size(); i++)
        {
            if (initialValue.equals(rows.get(i).value))
            {
                suppressSelectionApply = true;
                try
                {
                    selectTableIndexAndNotify(i);
                }
                finally
                {
                    suppressSelectionApply = false;
                }
                break;
            }
        }
    }
}
