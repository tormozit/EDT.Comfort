package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Единое поведение таблиц в формах плагина: выбор ячейки, копирование,
 * подсветка строки, активной ячейки и заголовка колонки.
 */
final class FormTableInteraction implements ColumnValuesDialog.Owner, ColumnFilterMenuBuilder.Owner
{
    private static final String COPY_MENU_KEY = "tormozit.formTableCopyMenu"; //$NON-NLS-1$
    private static final String COLUMN_HEADER_KEY = "tormozit.formTableColHeader"; //$NON-NLS-1$
    private static final String AUTO_HEADER_TOOLTIP_KEY = "tormozit.formTableColAutoHeaderTip"; //$NON-NLS-1$
    /** Колонка скрыта хозяином таблицы через {@link #setColumnHidden} (не пользователем drag'ом до нуля). */
    private static final String COLUMN_HIDDEN_KEY = "tormozit.formTableColHidden"; //$NON-NLS-1$
    private static final int HEADER_ACCENT_HEIGHT = 2;
    private static final int HEADER_SEPARATOR_HEIGHT = 1;
    /** Горизонтальный запас шапки Win32 (отступы без sort-иконки). */
    private static final int HEADER_TEXT_INSET = 16;
    /** Пауза без новых {@code Resize}-событий колонки, после которой накопленное сужение применяется (мс). */
    private static final int RESIZE_COMMIT_DEBOUNCE_MS = 60;

    /** Сторона квадратной иконки «снять фильтр» (в заголовке колонки и в меню). */
    private static final int FILTER_GLYPH_SIZE = 12;

    /** Таблицы с установленным взаимодействием — для поиска цели команды по фокусу. */
    private static final Map<Control, FormTableInteraction> INSTANCES = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface FormTableCellAccess
    {
        /** Отображаемый текст ячейки; {@code null} → пустая строка. */
        String cellText(TableItem item, int column);
    }

    /**
     * Текст ячейки по ЭЛЕМЕНТУ МОДЕЛИ (без живого {@link TableItem}) — для отбора по значению.
     *
     * <p>Отдельный интерфейс, а не {@link FormTableCellAccess}: {@link ViewerFilter#select} вызывается
     * JFace на сырых элементах входа ДО создания {@code TableItem}, поэтому item-ориентированный
     * {@code cellText} там неприменим. {@code null} → колонка не поддерживает отбор.
     */
    @FunctionalInterface
    interface FormTableFilterTextResolver
    {
        String filterText(Object element, int column);
    }

    /**
     * Внешний провайдер отбора по значению ячейки для таблиц БЕЗ {@link TableViewer} — напр.
     * «Коллекция» отладчика со своей моделью строк ({@code BitSet} + фоновый скан), несовместимой с
     * JFace {@link ViewerFilter}. Когда задан, команды отбора идут через него, а не через
     * {@code multiSelectViewer}: состояние отбора (карта {@code column}→{@code value}) всё равно
     * хранится в {@link FormTableInteraction} (единый источник для меню/счётчика/подписей), хост лишь
     * применяет/снимает его и поставляет элемент модели активной строки + различные значения колонки.
     *
     * <p>Ключ колонки — тот же, что у {@link FormTableFilterTextResolver#filterText} и
     * {@link FormTableCellAccess#cellText}: индекс колонки в {@link Table} (с точки зрения SWT).
     */
    interface ColumnValueFilterHost
    {
        /** Элемент модели активной строки (напр. {@code Integer} logical row) или {@code null}. */
        Object activeElement();

        /**
         * Применить отбор AND по колонкам (копия карты; пустая → отбор снят). Хост сам пересчитывает
         * видимые строки и обновляет таблицу, в т.ч. сохраняя активную ячейку по элементу из
         * {@link #activeElement} (к моменту вызова он ещё соответствует ПРЕЖНЕЙ активной строке —
         * хосту нужно запомнить его ДО пересчёта).
         */
        void applyFilters(java.util.Map<Integer, String> columnValueFilters);

        /**
         * Различные значения {@code column} (без дублей) + число строк с этим значением, СРЕДИ строк,
         * проходящих все ОСТАЛЬНЫЕ фильтры (сторонний substring-поиск и отбор по другим колонкам);
         * собственный отбор по {@code column} игнорируется — как у viewer-режима
         * {@link FormTableInteraction}. {@code honorOtherFilters=false} — по ВСЕМ строкам источника.
         */
        java.util.List<ColumnValuesDialog.ValueRow> distinctValues(int column, boolean honorOtherFilters);
    }

    private static final TableColumn[] NO_OWNER_DRAW_COLUMNS = new TableColumn[0];

    private final Table table;
    /** Опциональный override; иначе текст из label provider / {@code TableItem.getText}. */
    private final FormTableCellAccess cellAccess;

    private Runnable selectionSync;
    private Runnable copyHook;
    private TableViewer multiSelectViewer;

    private TableItem selectedItem;
    private TableItem selectionAnchor;
    private int suppressTableToViewerSync;
    private TableColumn activeColumnWidget;
    private boolean columnReorderEnabled = true;
    private Color ownedRowBg;
    private Color ownedInactiveRowBg;
    private Color ownedActiveCellBg;
    private Color ownedHeaderAccentBg;
    private Color ownedHeaderSeparatorBg;

    /** Отбор по значению: индекс создания колонки → эталонное значение ячейки (AND между колонками). */
    private final Map<Integer, String> columnValueFilters = new LinkedHashMap<>();
    private final Map<Integer, Canvas> filterIndicators = new LinkedHashMap<>();
    private final List<MenuItem> filterMenuItems = new ArrayList<>();
    private FormTableFilterTextResolver filterTextResolver;
    private ColumnValueFilterHost columnValueFilterHost;
    private boolean externalMenuPopulation;
    private ViewerFilter columnValueViewerFilter;
    private Runnable substringFilterClearer;
    private Image filterGlyph;

    private Canvas headerSeparator;
    private Canvas headerHighlight;
    private Composite columnHost;
    private Composite overlayRoot;
    private ControlAdapter tableResizeListener;
    private ControlAdapter stackResizeListener;
    private ControlAdapter columnHeaderListener;
    private SelectionAdapter horizontalScrollListener;
    private Runnable pendingHeaderOverlayUpdate;

    private Listener eraseItemListener;
    private Listener paintItemListener;
    private Listener focusListener;
    private Listener selectionListener;
    private Listener menuDetectListener;
    private Listener keyFilter;
    private Listener mouseDownListener;
    /** Programmatic {@code viewer.setSelection} часто не шлёт SWT.Selection — см. {@link #syncActiveCellFromSelection}. */
    private ISelectionChangedListener viewerSelectionListener;
    private TableColumn[] ownerDrawColumns = NO_OWNER_DRAW_COLUMNS;

    /** Ширины колонок (визуальный порядок) на момент последнего фактического применения (commit) сужения/fill. */
    private int[] lastKnownVisualWidths;
    /** Реентерабельный флаг: наши собственные setWidth (fill/shrink) не должны запускать логику повторно. */
    private boolean selfAdjusting;
    /** Дебаунс auto-fill при ресайзе таблицы/хоста. */
    private Runnable pendingAutoFill;
    /** Auto-fill + пропорц. сужение при drag включены; opt-out для мест со своей логикой колонок. */
    private boolean columnAutoResizeEnabled = true;
    /** Колонка, чей drag сейчас накапливается (см. {@link #onUserColumnResize}); {@code null} — нет активного. */
    private TableColumn pendingResizeColumn;
    /** Ширины (визуальный порядок) на момент НАЧАЛА накопления текущего drag {@link #pendingResizeColumn}. */
    private int[] pendingResizeBaseline;
    /** Запланированный (и переоткладываемый на каждое новое событие) commit сужения после паузы в drag. */
    private Runnable pendingResizeCommit;
    /** Ширина клиентской области таблицы на момент последнего реального auto-fill; -1 — ещё не запускался. */
    private int lastAutoFillClientWidth = -1;
    /** Умещались ли колонки в ширину таблицы на момент последнего известного состояния (без overflow). */
    private boolean columnsFitBefore = true;
    /** Точно ли колонки заполняли ширину таблицы (правая граница последней = граница таблицы), без запаса. */
    private boolean columnsExactFillBefore = true;

    FormTableInteraction(Table table, FormTableCellAccess cellAccess)
    {
        this.table = table;
        this.cellAccess = cellAccess;
    }

    /** Текст ячейки — из label provider колонок {@code viewer} (универсально). */
    FormTableInteraction(Table table, TableViewer viewer)
    {
        this(table, viewer, null);
    }

    FormTableInteraction(Table table, TableViewer viewer, FormTableCellAccess cellAccess)
    {
        this(table, cellAccess);
        setTableViewer(viewer);
    }

    void setTableViewer(TableViewer viewer)
    {
        multiSelectViewer = viewer;
        selectionSync = viewer == null
            ? null
            : () -> syncTableViewerSelection(table, viewer);
    }

    /**
     * Текст ячейки для копирования / dark-theme paint:
     * 1) явный {@link FormTableCellAccess}, если задан;
     * 2) label provider колонки {@link TableViewer} (в т.ч. {@link CellLabelProvider} через update);
     * 3) {@link TableItem#getText(int)}.
     */
    private String resolveCellText(TableItem item, int column)
    {
        if (item == null || item.isDisposed())
            return ""; //$NON-NLS-1$
        String result;
        if (cellAccess != null)
        {
            String custom = cellAccess.cellText(item, column);
            result = custom != null ? custom : ""; //$NON-NLS-1$
        }
        else
        {
            Object element = item.getData();
            if (multiSelectViewer != null && element != null && column >= 0)
            {
                String fromProvider = textFromColumnLabelProvider(multiSelectViewer, item, column, element);
                if (fromProvider != null && !fromProvider.isEmpty())
                    result = fromProvider;
                else
                {
                    String plain = item.getText(column);
                    result = plain != null ? plain : ""; //$NON-NLS-1$
                }
            }
            else
            {
                String plain = item.getText(column);
                result = plain != null ? plain : ""; //$NON-NLS-1$
            }
        }
        return result;
    }

    private static String textFromColumnLabelProvider(
        TableViewer viewer,
        TableItem item,
        int column,
        Object element)
    {
        if (viewer == null || element == null || column < 0)
            return null;
        CellLabelProvider cellLp;
        try
        {
            cellLp = viewer.getLabelProvider(column);
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
        if (cellLp == null)
            return null;
        if (cellLp instanceof SelectionAwareStyledCellLabelProvider selectionAware)
        {
            String s = selectionAware.textForCopy(element);
            if (s != null && !s.isEmpty())
                return s;
        }
        if (cellLp instanceof DelegatingStyledCellLabelProvider delegating)
        {
            DelegatingStyledCellLabelProvider.IStyledLabelProvider styled = delegating.getStyledStringProvider();
            if (styled != null)
            {
                StyledString ss = styled.getStyledText(element);
                if (ss != null)
                {
                    String s = ss.getString();
                    if (s != null && !s.isEmpty())
                        return s;
                }
            }
        }
        if (cellLp instanceof ColumnLabelProvider columnLp)
        {
            String t = columnLp.getText(element);
            if (t != null && !t.isEmpty())
                return t;
        }
        if (cellLp instanceof ILabelProvider labelProvider)
        {
            String t = labelProvider.getText(element);
            if (t != null && !t.isEmpty())
                return t;
        }
        // CellLabelProvider без getText (как «Файл»/«Номер строки» в поиске по файлам):
        // update(ViewerCell) заполняет текст, иначе item.getText пуст и Ctrl+C уходит в дерево.
        return textFromViewerCellAfterUpdate(viewer, item, column, cellLp);
    }

    private static String textFromViewerCellAfterUpdate(
        TableViewer viewer,
        TableItem item,
        int column,
        CellLabelProvider cellLp)
    {
        if (viewer == null || item == null || item.isDisposed() || cellLp == null || column < 0)
            return null;
        Rectangle bounds = item.getBounds(column);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
            return null;
        int x = bounds.x + Math.max(1, Math.min(bounds.width / 2, bounds.width - 1));
        int y = bounds.y + Math.max(0, bounds.height / 2);
        ViewerCell cell = viewer.getCell(new Point(x, y));
        if (cell == null || cell.getItem() != item || cell.getColumnIndex() != column)
            return null;
        try
        {
            cellLp.update(cell);
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
        String t = cell.getText();
        return t != null && !t.isEmpty() ? t : null;
    }

    static void syncTableViewerSelection(Table table, TableViewer viewer)
    {
        if (table == null || table.isDisposed() || viewer == null)
            return;
        TableItem[] sel = table.getSelection();
        List<Object> elements = new ArrayList<>();
        for (TableItem ti : sel)
        {
            Object data = ti.getData();
            if (data != null)
                elements.add(data);
        }
        viewer.setSelection(new StructuredSelection(elements));
    }

    void setCopyHook(Runnable copyHook)
    {
        this.copyHook = copyHook;
    }

    /** Колонки с {@code DelegatingStyledCellLabelProvider} (owner-draw), напр. «Имя». */
    void setOwnerDrawColumns(TableColumn... columns)
    {
        if (columns == null || columns.length == 0)
            ownerDrawColumns = NO_OWNER_DRAW_COLUMNS;
        else
            ownerDrawColumns = columns.clone();
    }

    void setColumnReorderEnabled(boolean columnReorderEnabled)
    {
        this.columnReorderEnabled = columnReorderEnabled;
    }

    /**
     * Текст ячейки по элементу модели для «Отобрать по значению ячейки». Нужен там, где текст
     * берётся из {@link FormTableCellAccess} (по {@code TableItem}) — при отборе живого item ещё нет.
     * Не задан → значение берётся из label provider колонки, а если и его нет, отбор по этой
     * колонке недоступен (пункт меню заблокирован).
     */
    void setFilterTextResolver(FormTableFilterTextResolver filterTextResolver)
    {
        this.filterTextResolver = filterTextResolver;
    }

    /**
     * Сброс постороннего отбора по подстроке (поле поиска над таблицей) — вызывается из
     * «Отключить все отборы» вместе со сбросом отбора по значениям колонок.
     */
    void setSubstringFilterClearer(Runnable substringFilterClearer)
    {
        this.substringFilterClearer = substringFilterClearer;
    }

    /**
     * Внешний провайдер отбора для таблиц без {@link TableViewer} (см. {@link ColumnValueFilterHost}).
     * Должен быть задан ДО {@link #install()} и вместе с {@link #setFilterTextResolver} — иначе
     * {@code canFilterByActiveCell()} остаётся {@code false} (как без viewer).
     */
    void setColumnValueFilterHost(ColumnValueFilterHost columnValueFilterHost)
    {
        this.columnValueFilterHost = columnValueFilterHost;
    }

    /**
     * Меню таблицы полностью контролирует хозяин (он стирает все пункты на каждом показе и зовёт
     * {@link #populateFilterMenuItems} из своего rebuildContextMenu). Иначе {@link FormTableInteraction}
     * сам создаёт «Копировать» и вешает SWT.Show-пересборщик пунктов отбора. Должно быть задано ДО
     * {@link #install()}.
     */
    void setExternalMenuPopulation(boolean externalMenuPopulation)
    {
        this.externalMenuPopulation = externalMenuPopulation;
    }

    void install()
    {
        install(false);
    }

    /**
     * @param hasSavedColumnWidths потребитель восстановил ширины колонок из сохранённых настроек
     * (пользователь ранее сам их подстроил, в т.ч. специально оставил свободное место) — тогда режим
     * заполнения по ширине НЕ включается самовольно, даже если колонки сейчас умещаются в таблицу.
     * {@code false} (в т.ч. {@link #install()}) — ширины дефолтные (сохранённых настроек нет): если они
     * уже умещаются в ширину таблицы, сразу входим в режим заполнения (см. {@link #columnsExactFillBefore}).
     */
    void install(boolean hasSavedColumnWidths)
    {
        if (table == null || table.isDisposed())
            return;

        ListSelectionThemeColors.markOptOut(table);
        INSTANCES.put(table, this);

        mouseDownListener = this::onMouseDown;
        table.addListener(SWT.MouseDown, mouseDownListener);

        eraseItemListener = this::onEraseItem;
        table.addListener(SWT.EraseItem, eraseItemListener);
        paintItemListener = this::onPaintItem;
        table.addListener(SWT.PaintItem, paintItemListener);

        focusListener = e ->
        {
            invalidateHighlightColor();
            redrawSelectedRows();
            redrawHeader();
        };
        table.addListener(SWT.FocusIn, focusListener);
        table.addListener(SWT.FocusOut, focusListener);

        selectionListener = this::onSelection;
        table.addListener(SWT.Selection, selectionListener);

        if (multiSelectViewer != null)
        {
            viewerSelectionListener = event -> syncActiveCellFromSelection();
            multiSelectViewer.addSelectionChangedListener(viewerSelectionListener);
        }

        menuDetectListener = this::onMenuDetect;
        table.addListener(SWT.MenuDetect, menuDetectListener);

        keyFilter = this::onKeyFilter;
        table.getDisplay().addFilter(SWT.KeyDown, keyFilter);
        // Win32: Ctrl+C не доходит до KeyDown (акселератор Edit→Copy) — только через команду.
        CopyCommandSupport.wireCopyOverride(table, this::copyActiveCell);
        installHeaderOverlays();
        ListSelectionThemeColors.installSelectionPrePaintFilter(table, (t, item, col) ->
        {
            if (!isOwnerDrawColumn(col))
                return null;
            Color bg = selectionCellBackground(t, item, col);
            if (bg == null || bg.isDisposed())
                return null;
            return new Color(t.getDisplay(), bg.getRGB());
        }, "formTable"); //$NON-NLS-1$
        rememberVisualWidths();
        columnsExactFillBefore = !hasSavedColumnWidths;
        scheduleAutoFill();
        table.addDisposeListener(e -> dispose());
    }

    void dispose()
    {
        if (table != null)
            INSTANCES.remove(table);
        if (viewerSelectionListener != null && multiSelectViewer != null)
        {
            multiSelectViewer.removeSelectionChangedListener(viewerSelectionListener);
            viewerSelectionListener = null;
        }
        if (keyFilter != null && table != null && !table.isDisposed())
            table.getDisplay().removeFilter(SWT.KeyDown, keyFilter);
        if (pendingAutoFill != null && table != null && !table.isDisposed())
        {
            table.getDisplay().timerExec(-1, pendingAutoFill);
            pendingAutoFill = null;
        }
        cancelPendingResizeCommit();
        pendingResizeColumn = null;
        pendingResizeBaseline = null;
        if (columnValueViewerFilter != null && multiSelectViewer != null
            && multiSelectViewer.getControl() != null && !multiSelectViewer.getControl().isDisposed())
        {
            multiSelectViewer.removeFilter(columnValueViewerFilter);
        }
        columnValueViewerFilter = null;
        columnValueFilters.clear();
        filterMenuItems.clear();
        uninstallHeaderOverlays();
        if (filterGlyph != null && !filterGlyph.isDisposed())
            filterGlyph.dispose();
        filterGlyph = null;
        disposeColors();
    }

    /**
     * После {@code viewer.refresh()} / {@code setInput} — перерисовать подсветку выделения.
     * Проходит по ВСЕМ строкам (не только выделенным) — SWT переиспользует {@code TableItem} между
     * refresh-ами; если под тем же физическим индексом раньше была выделенная строка с уже
     * выставленным {@link #syncCellBackgrounds} фоном, а после refresh она перестала быть
     * выделенной, фон нужно явно сбросить — иначе он останется от предыдущих данных.
     */
    void resyncSelectionTheme()
    {
        syncActiveCellFromSelection();
        if (!table.isDisposed() && ListSelectionThemeColors.isDarkList(table))
        {
            for (TableItem item : table.getItems())
                syncCellBackgrounds(item);
        }
        redrawSelectedRows();
    }

    /**
     * Подтянуть рамку «активной ячейки» к текущему нативному/viewer-выделению.
     * {@code viewer.setSelection(...)} после {@code setInput}/{@code refresh} часто не шлёт
     * {@code SWT.Selection}, а {@link #selectedItem} остаётся на старом (переиспользованном)
     * {@code TableItem} — фон строки уже на новой, рамка ячейки на старой.
     * Если активная строка ещё в выделении — не трогаем (Ctrl+клик по другой строке).
     */
    void syncActiveCellFromSelection()
    {
        if (table == null || table.isDisposed())
            return;
        if (selectedItem != null && selectedItem.isDisposed())
            selectedItem = null;
        if (selectedItem != null && isRowSelected(selectedItem))
            return;
        TableItem row = null;
        TableItem[] sel = table.getSelection();
        if (sel.length > 0)
            row = sel[0];
        else if (useViewerForMultiSelect())
        {
            Object first = multiSelectViewer.getStructuredSelection().getFirstElement();
            if (first != null)
            {
                for (TableItem item : table.getItems())
                {
                    if (first.equals(item.getData()))
                    {
                        row = item;
                        break;
                    }
                }
            }
        }
        if (row == null || row.isDisposed())
            return;
        int col = activeColumnIndex();
        if (col < 0)
            col = 0;
        TableItem previous = selectedItem;
        updateActiveCell(row, col);
        if (previous != null && previous != row && !previous.isDisposed())
            redrawRow(previous);
        redrawRow(row);
        redrawHeader();
    }

    void selectCell(TableItem item, int column)
    {
        if (item == null || item.isDisposed())
            return;
        TableItem[] previousSelection = table.getSelection();
        TableItem previousActive = selectedItem;
        selectSingleRow(item);
        updateActiveCell(item, column);
        redrawAffectedRows(previousSelection, table.getSelection(), previousActive);
        if (!useViewerForMultiSelect())
            syncSelection();
    }

    private void onMouseDown(Event e)
    {
        if (e.button != 1)
            return;
        TableItem item = table.getItem(new Point(e.x, e.y));
        if (item == null)
            return;
        int column = columnAt(e.x, e.y, item);
        if (column < 0)
            column = 0;
        if (isMultiSelect())
        {
            int mods = e.stateMask & (SWT.MOD1 | SWT.MOD2);
            if (useViewerForMultiSelect() && mods != 0)
            {
                updateActiveCell(item, column);
                return;
            }
            TableItem[] previousSelection = table.getSelection();
            TableItem previousActive = selectedItem;
            if ((mods & SWT.MOD2) != 0)
                extendRangeSelection(item);
            else if ((mods & SWT.MOD1) != 0)
                toggleRowSelection(item);
            else if (isRowSelected(item) && multiSelectionCount() > 1)
            {
                // Клик по уже выделенной строке — не сбрасывать мультивыделение (drag).
            }
            else
                selectSingleRow(item);
            updateActiveCell(item, column);
            redrawAffectedRows(previousSelection, table.getSelection(), previousActive);
            if (!useViewerForMultiSelect())
                syncSelection();
            if (useViewerForMultiSelect() && mods == 0)
                e.doit = false;
            else if (!useViewerForMultiSelect() && mods != 0)
                e.doit = false;
        }
        else
        {
            selectCell(item, column);
        }
    }

    int activeColumn()
    {
        return activeColumnIndex();
    }

    private int activeColumnIndex()
    {
        if (activeColumnWidget == null || activeColumnWidget.isDisposed() || table.isDisposed())
            return -1;
        return table.indexOf(activeColumnWidget);
    }

    private TableColumn columnWidget(int column)
    {
        if (table.isDisposed() || column < 0 || column >= table.getColumnCount())
            return null;
        return table.getColumn(column);
    }

    static FormTableInteraction of(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        return INSTANCES.get(control);
    }

    TableItem selectedItem()
    {
        return selectedItem;
    }

    private boolean isMultiSelect()
    {
        return (table.getStyle() & SWT.MULTI) != 0;
    }

    private boolean useViewerForMultiSelect()
    {
        return isMultiSelect() && multiSelectViewer != null
            && multiSelectViewer.getControl() != null
            && !multiSelectViewer.getControl().isDisposed();
    }

    private int viewerSelectionCount()
    {
        if (!useViewerForMultiSelect())
            return 0;
        return multiSelectViewer.getStructuredSelection().size();
    }

    private int multiSelectionCount()
    {
        if (useViewerForMultiSelect())
            return viewerSelectionCount();
        return table.getSelectionCount();
    }

    private void applyViewerSelection(StructuredSelection selection)
    {
        if (!useViewerForMultiSelect())
            return;
        suppressTableToViewerSync++;
        multiSelectViewer.setSelection(selection);
        syncTableFromViewer();
        table.getDisplay().asyncExec(() ->
        {
            if (table.isDisposed())
                return;
            suppressTableToViewerSync = Math.max(0, suppressTableToViewerSync - 1);
        });
    }

    private void syncTableFromViewer()
    {
        if (!useViewerForMultiSelect())
            return;
        List<?> elements = multiSelectViewer.getStructuredSelection().toList();
        table.deselectAll();
        for (int i = 0; i < table.getItemCount(); i++)
        {
            Object data = table.getItem(i).getData();
            if (data != null && elements.contains(data))
                table.select(i);
        }
    }

    private void selectSingleRow(TableItem item)
    {
        selectionAnchor = item;
        Object data = item.getData();
        if (useViewerForMultiSelect() && data != null)
        {
            applyViewerSelection(new StructuredSelection(data));
            ensureNativeSelection(item);
        }
        else
            table.setSelection(item);
    }

    /**
     * Гарантировать НАТИВНОЕ выделение строки. На виртуальной таблице с переопределённым
     * хостом {@code setSelectionToWidget} (EGit CommitFileDiffViewer) {@code viewer.setSelection()}
     * внутри {@link #applyViewerSelection} нативное выделение не выставляет — без него текущая
     * строка рисуется лишь нашей тонкой заливкой в {@link #onEraseItem}, без полного нативного
     * оформления выделения. Для штатных таблиц выделение уже выставлено viewer.setSelection-ом,
     * {@code isRowSelected} здесь истинен — форс пропускается. Programmatic
     * {@code table.setSelection} не порождает SWT.Selection.
     */
    private void ensureNativeSelection(TableItem item)
    {
        if (item == null || item.isDisposed() || table.isDisposed())
            return;
        if (isRowSelected(item))
            return;
        table.setSelection(new TableItem[] { item });
    }

    /** Ключ элемента модели — зеркально {@link #rowKey(TableItem)}, но по самому элементу
     *  (без живого TableItem): для поиска активной строки среди входа viewer, когда она
     *  не материализована (виртуальная таблица). Разделитель тот же, что в rowKey. */
    private String elementKey(Object element)
    {
        if (element == null || table.isDisposed())
            return null;
        int cols = Math.max(1, table.getColumnCount());
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cols; c++)
        {
            String text = filterElementText(element, c);
            sb.append(text != null ? text : "").append('\u0001'); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /** Индекс элемента с заданным ключом среди отображаемых строк (вход + фильтры viewer),
     *  в порядке отображения. {@code -1}, если не найден. */
    private int findDisplayedIndexByKey(String key)
    {
        if (key == null || multiSelectViewer == null || table.isDisposed())
            return -1;
        if (!(multiSelectViewer.getContentProvider() instanceof IStructuredContentProvider cp))
            return -1;
        Object[] elements = cp.getElements(multiSelectViewer.getInput());
        if (elements == null)
            return -1;
        ViewerFilter[] filters = multiSelectViewer.getFilters();
        int idx = 0;
        for (Object el : elements)
        {
            boolean passes = true;
            for (ViewerFilter f : filters)
            {
                if (!f.select(multiSelectViewer, null, el))
                {
                    passes = false;
                    break;
                }
            }
            if (!passes)
                continue;
            if (key.equals(elementKey(el)))
                return idx;
            idx++;
        }
        return -1;
    }

    private void toggleRowSelection(TableItem item)
    {
        Object data = item.getData();
        if (data == null)
            return;
        if (useViewerForMultiSelect())
        {
            List<Object> next = new ArrayList<>(multiSelectViewer.getStructuredSelection().toList());
            if (next.contains(data))
                next.remove(data);
            else
                next.add(data);
            applyViewerSelection(new StructuredSelection(next));
            return;
        }
        int idx = table.indexOf(item);
        if (idx < 0)
            return;
        if (table.isSelected(idx))
        {
            if (table.getSelectionCount() <= 1)
                table.deselectAll();
            else
                table.deselect(idx);
        }
        else
            table.select(idx);
    }

    private void extendRangeSelection(TableItem item)
    {
        TableItem anchor = selectionAnchor;
        if (anchor == null || anchor.isDisposed())
            anchor = currentSelectedRow();
        if (anchor == null)
        {
            selectSingleRow(item);
            return;
        }
        int anchorIdx = table.indexOf(anchor);
        int clickIdx = table.indexOf(item);
        if (anchorIdx < 0 || clickIdx < 0)
        {
            selectSingleRow(item);
            return;
        }
        int from = Math.min(anchorIdx, clickIdx);
        int to = Math.max(anchorIdx, clickIdx);
        if (useViewerForMultiSelect())
        {
            TableItem[] all = table.getItems();
            List<Object> elements = new ArrayList<>();
            for (int i = from; i <= to; i++)
            {
                Object d = all[i].getData();
                if (d != null)
                    elements.add(d);
            }
            applyViewerSelection(new StructuredSelection(elements));
            return;
        }
        table.setSelection(from, to);
    }

    private void updateActiveCell(TableItem item, int column)
    {
        selectedItem = item;
        activeColumnWidget = columnWidget(column);
    }

    private void onSelection(Event e)
    {
        TableItem row = currentSelectedRow();
        if (row == null)
        {
            if (suppressTableToViewerSync <= 0)
                syncSelection();
            return;
        }
        TableItem previousActive = selectedItem;
        if (!isMultiSelect() || selectedItem == null || !isRowSelected(selectedItem))
            selectedItem = row;
        if (activeColumnWidget == null || activeColumnWidget.isDisposed())
            activeColumnWidget = columnWidget(0);
        if (isMultiSelect())
        {
            redrawSelectedRows();
            if (previousActive != null && !isRowSelected(previousActive))
                redrawRow(previousActive);
        }
        else
        {
            redrawRow(previousActive);
            redrawRow(selectedItem);
        }
        redrawHeader();
        if (suppressTableToViewerSync <= 0)
            syncSelection();
    }

    private void syncSelection()
    {
        if (selectionSync != null)
            selectionSync.run();
    }

    private void onKeyFilter(Event e)
    {
        if (table == null || table.isDisposed() || !table.isFocusControl())
            return;
        if (e.keyCode == 'c' && (e.stateMask & SWT.MOD1) != 0)
        {
            copyActiveCell();
            e.doit = false;
        }
    }

    private void onMenuDetect(Event e)
    {
        Point loc = table.toControl(e.x, e.y);
        TableItem item = table.getItem(loc);
        if (item != null)
        {
            int column = columnAt(loc.x, loc.y, item);
            if (column < 0)
                column = 0;
            TableItem[] previousSelection = table.getSelection();
            TableItem previousActive = selectedItem;
            if (!isMultiSelect() || !isRowSelected(item))
                selectSingleRow(item);
            updateActiveCell(item, column);
            redrawAffectedRows(previousSelection, table.getSelection(), previousActive);
            if (!useViewerForMultiSelect())
                syncSelection();
        }
        ensureCopyMenu();
    }

    private void ensureCopyMenu()
    {
        // Внешний режим: меню полностью контролирует хозяин (он стирает все пункты при каждом
        // показе), поэтому здесь не создаём ни «Копировать», ни SWT.Show-пересборщик — хозяин
        // зовёт {@link #populateFilterMenuItems(Menu)} из своего rebuildContextMenu.
        if (externalMenuPopulation)
            return;
        Menu menu = table.getMenu();
        if (menu == null)
        {
            menu = new Menu(table);
            table.setMenu(menu);
        }
        if (menu.getData(COPY_MENU_KEY) != null)
            return;
        menu.setData(COPY_MENU_KEY, Boolean.TRUE);
        MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
        copyItem.setText("Копировать\tCtrl+C"); //$NON-NLS-1$
        copyItem.addListener(SWT.Selection, ev -> copyActiveCell());

        // Пункты отбора зависят от активной ячейки и от набора наложенных фильтров, а у SWT
        // MenuItem нет setVisible — поэтому их набор пересобирается на каждом показе меню.
        // Меню общее с другими хуками (они дописывают в него свои пункты), свои пункты держим
        // в конце и трогаем только их.
        Menu tableMenu = menu;
        tableMenu.addListener(SWT.Show, ev -> rebuildFilterMenuItems(tableMenu));
    }

    /**
     * Для внешнего режима ({@link #setExternalMenuPopulation}): дополнить меню хозяина «Копировать»
     * и пунктами отбора. Хозяин сам стирает все пункты перед показом, поэтому метод добавляет их
     * заново при каждом вызове (в отличие от {@link #ensureCopyMenu}, где «Копировать» создаётся
     * один раз). Вызывается из rebuildContextMenu хозяина — напр. {@code DebugCollectionWindow}.
     */
    void populateFilterMenuItems(Menu menu)
    {
        if (menu == null || menu.isDisposed() || table.isDisposed())
            return;
        MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
        copyItem.setText("Копировать\tCtrl+C"); //$NON-NLS-1$
        copyItem.addListener(SWT.Selection, ev -> copyActiveCell());
        rebuildFilterMenuItems(menu);
    }

    private void rebuildFilterMenuItems(Menu menu)
    {
        for (MenuItem item : filterMenuItems)
        {
            if (!item.isDisposed())
                item.dispose();
        }
        filterMenuItems.clear();
        if (menu == null || menu.isDisposed() || table.isDisposed())
            return;
        ColumnFilterMenuBuilder.rebuildFilterMenuItems(menu, filterMenuItems, this);
    }

    @Override
    public boolean hasActiveFilters()
    {
        return !columnValueFilters.isEmpty();
    }

    @Override
    public int filteredElementCount()
    {
        return table.getItemCount();
    }

    @Override
    public Image clearAllIcon()
    {
        return filterGlyph();
    }

    /** Полный текущий отбор — по строке на колонку, для подсказки у счётчика отобранных строк. */
    @Override
    public String activeFiltersDescription()
    {
        return activeFiltersDescriptionExcluding(-1);
    }

    /**
     * Текст ячейки по ЭЛЕМЕНТУ модели (для отбора; живого {@code TableItem} на момент
     * {@link ViewerFilter#select} ещё нет): явный {@link FormTableFilterTextResolver}, иначе label
     * provider колонки. {@code null} — отбор по этой колонке не поддерживается (например,
     * {@link CellLabelProvider} без {@code getText}, кладущий текст только в {@code update}).
     */
    private String filterElementText(Object element, int column)
    {
        if (element == null || column < 0)
            return null;
        if (filterTextResolver != null)
        {
            String text = filterTextResolver.filterText(element, column);
            return text != null ? text : ""; //$NON-NLS-1$
        }
        if (multiSelectViewer == null)
            return null;
        CellLabelProvider cellLp;
        try
        {
            cellLp = multiSelectViewer.getLabelProvider(column);
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
        if (cellLp instanceof SelectionAwareStyledCellLabelProvider selectionAware)
        {
            String s = selectionAware.textForCopy(element);
            if (s != null)
                return s;
        }
        if (cellLp instanceof DelegatingStyledCellLabelProvider delegating)
        {
            DelegatingStyledCellLabelProvider.IStyledLabelProvider styled = delegating.getStyledStringProvider();
            if (styled != null)
            {
                StyledString ss = styled.getStyledText(element);
                if (ss != null && ss.getString() != null)
                    return ss.getString();
            }
        }
        if (cellLp instanceof ColumnLabelProvider columnLp)
        {
            String t = columnLp.getText(element);
            return t != null ? t : ""; //$NON-NLS-1$
        }
        if (cellLp instanceof ILabelProvider labelProvider)
        {
            String t = labelProvider.getText(element);
            return t != null ? t : ""; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Отбор по {@code column} в принципе возможен — без вычисления конкретного значения (для
     * enable/disable пункта меню и «Различные значения колонки», где активной ячейки может ещё не быть).
     * Условия зеркалируют ветки {@link #filterElementText}.
     */
    private boolean supportsElementFilterText(int column)
    {
        if (column < 0)
            return false;
        if (filterTextResolver != null)
            return true;
        if (multiSelectViewer == null)
            return false;
        CellLabelProvider cellLp;
        try
        {
            cellLp = multiSelectViewer.getLabelProvider(column);
        }
        catch (RuntimeException ignored)
        {
            return false;
        }
        if (cellLp instanceof SelectionAwareStyledCellLabelProvider)
            return true;
        if (cellLp instanceof DelegatingStyledCellLabelProvider delegating)
            return delegating.getStyledStringProvider() != null;
        return cellLp instanceof ColumnLabelProvider || cellLp instanceof ILabelProvider;
    }

    private TableItem activeRow()
    {
        if (selectedItem != null && !selectedItem.isDisposed())
            return selectedItem;
        return currentSelectedRow();
    }

    /** Доступен ли механизм отбора по значению ячейки (viewer ИЛИ внешний {@link ColumnValueFilterHost}). */
    private boolean isColumnValueFilteringAvailable()
    {
        return multiSelectViewer != null || columnValueFilterHost != null;
    }

    /**
     * Элемент модели активной строки для отбора: внешний хост — его {@link
     * ColumnValueFilterHost#activeElement} (напр. {@code Integer} logical row, без живого
     * {@code TableItem}); иначе {@link TableItem#getData} активной строки viewer-режима.
     */
    private Object activeFilterElement()
    {
        if (columnValueFilterHost != null)
            return columnValueFilterHost.activeElement();
        TableItem item = activeRow();
        return item != null && !item.isDisposed() ? item.getData() : null;
    }

    @Override
    public boolean canFilterByActiveCell()
    {
        if (!isColumnValueFilteringAvailable() || table.isDisposed())
            return false;
        int column = activeColumnIndex();
        if (column < 0)
            return false;
        Object element = activeFilterElement();
        if (element == null)
            return false;
        return filterElementText(element, column) != null;
    }

    /** По активной колонке уже наложен отбор ровно по значению активной ячейки. */
    @Override
    public boolean isActiveCellFiltered()
    {
        int column = activeColumnIndex();
        if (column < 0)
            return false;
        String applied = columnValueFilters.get(Integer.valueOf(column));
        if (applied == null)
            return false;
        Object element = activeFilterElement();
        if (element == null)
            return false;
        String value = filterElementText(element, column);
        return value != null && applied.equals(value);
    }

    @Override
    public void toggleActiveCellFilter()
    {
        if (isActiveCellFiltered())
            clearColumnFilter(activeColumnIndex());
        else
            filterByActiveCell();
    }

    /**
     * Отбор/снятие отбора по активной ячейке таблицы под фокусом — точка входа для команды
     * {@link ColumnFilterMenuBuilder#FILTER_COMMAND_ID} (сочетание клавиш настраивается в «Клавиши»).
     */
    static void toggleFilterOnFocusedTable()
    {
        Display display = Display.getCurrent();
        if (display == null)
            return;
        for (Control c = display.getFocusControl(); c != null && !c.isDisposed(); c = c.getParent())
        {
            FormTableInteraction interaction = INSTANCES.get(c);
            if (interaction != null)
            {
                interaction.toggleActiveCellFilter();
                return;
            }
        }
    }

    private void filterByActiveCell()
    {
        int column = activeColumnIndex();
        if (column < 0)
            return;
        Object element = activeFilterElement();
        if (element == null)
            return;
        String value = filterElementText(element, column);
        if (value == null)
            return;
        applyColumnFilterValue(column, value);
    }

    /** Наложить отбор на {@code column} по конкретному значению — используется меню и {@link ColumnValuesDialog}. */
    @Override
    public void applyColumnFilterValue(int column, String value)
    {
        if (!isColumnValueFilteringAvailable() || column < 0 || value == null)
            return;
        if (columnValueFilterHost != null)
        {
            columnValueFilters.put(Integer.valueOf(column), value);
            // Хост сам пересчитывает видимые строки, обновляет таблицу и восстанавливает активную
            // ячейку (viewer-овский SelectionSnapshot/refreshKeepingActiveCell ему недоступен).
            columnValueFilterHost.applyFilters(new LinkedHashMap<>(columnValueFilters));
            // Индикатор-«крестик» в шапке колонки — без этого external-режим его не показывал:
            // refreshKeepingActiveCell (с updateFilterIndicators внутри) зовётся только в viewer-ветке.
            updateFilterIndicators();
            redrawHeader();
            return;
        }
        // Снимок ДО любых мутаций фильтра: JFace-овский addFilter() внутри
        // installColumnValueViewerFilter() сам делает refresh() и перетасовывает TableItem —
        // после него activeRow() указывает уже на ЧУЖОЙ элемент.
        SelectionSnapshot snapshot = captureSelection();
        columnValueFilters.put(Integer.valueOf(column), value);
        installColumnValueViewerFilter();
        refreshKeepingActiveCell(snapshot);
    }

    /**
     * Активная строка + выделение для восстановления после {@code refresh()}.
     *
     * <p>Хранится И элемент модели, И ТЕКСТОВЫЙ КЛЮЧ строки. Только элемента недостаточно:
     * провайдер содержимого может ПЕРЕСОЗДАТЬ объекты строк на refresh (EGit-овские
     * {@code FileDiff} в «Истории Git» создаются заново, а {@code equals()} у них не
     * переопределён, так что ни {@code ==}, ни {@code List.contains} старый объект уже не
     * находят). Тот же класс проблемы описан в javadoc {@link #isRowSelected}
     * ({@code MatchRow}/{@code FileSearchRow} без {@code equals()}).
     */
    private static final class SelectionSnapshot
    {
        final Object activeElement;
        final String activeKey;
        final int activeColumn;
        final List<Object> selectedElements;
        final List<String> selectedKeys;

        SelectionSnapshot(Object activeElement, String activeKey, int activeColumn,
            List<Object> selectedElements, List<String> selectedKeys)
        {
            this.activeElement = activeElement;
            this.activeKey = activeKey;
            this.activeColumn = activeColumn;
            this.selectedElements = selectedElements;
            this.selectedKeys = selectedKeys;
        }
    }

    private SelectionSnapshot captureSelection()
    {
        TableItem active = activeRow();
        boolean activeAlive = active != null && !active.isDisposed();
        Object activeElement = activeAlive ? active.getData() : null;
        String activeKey = activeAlive ? rowKey(active) : null;
        List<Object> selectedElements = new ArrayList<>();
        List<String> selectedKeys = new ArrayList<>();
        if (!table.isDisposed())
        {
            for (TableItem row : table.getSelection())
            {
                Object data = row.getData();
                if (data != null)
                    selectedElements.add(data);
                String key = rowKey(row);
                if (key != null)
                    selectedKeys.add(key);
            }
        }
        return new SelectionSnapshot(activeElement, activeKey, activeColumnIndex(),
            selectedElements, selectedKeys);
    }

    /**
     * Текст всех ячеек строки — устойчивый ключ для сопоставления строк ДО и ПОСЛЕ {@code refresh()},
     * когда объекты модели пересоздаются. Берётся из {@link #resolveCellText} (тот же путь, что для
     * копирования), поэтому работает и без {@link FormTableFilterTextResolver}.
     */
    private String rowKey(TableItem item)
    {
        if (item == null || item.isDisposed() || table.isDisposed())
            return null;
        int cols = Math.max(1, table.getColumnCount());
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cols; c++)
        {
            String text = resolveCellText(item, c);
            sb.append(text != null ? text : "").append(''); //$NON-NLS-1$
        }
        return sb.toString();
    }

    @Override
    public boolean canBrowseColumnValues()
    {
        int column = activeColumnIndex();
        return isColumnValueFilteringAvailable() && column >= 0 && supportsElementFilterText(column);
    }

    /**
     * Разные значения активной колонки (без дублей) + число строк с этим значением, СРЕДИ строк,
     * проходящих все ОСТАЛЬНЫЕ фильтры (сторонний ViewerFilter вроде отбора по подстроке и отбор
     * по другим колонкам) — собственный отбор по {@code column} игнорируется, иначе при уже
     * наложенном отборе список сужался бы до одного текущего значения.
     */
    private List<ColumnValuesDialog.ValueRow> computeColumnValueRows(int column)
    {
        return computeColumnValueRows(column, true);
    }

    /**
     * {@code honorOtherFilters=true} — считать различные значения СРЕДИ строк, проходящих все
     * ОСТАЛЬНЫЕ фильтры (сторонний ViewerFilter вроде отбора по подстроке и отбор по другим
     * колонкам; собственный отбор по {@code column} всегда игнорируется — см. класс-javadoc
     * {@link ColumnValuesDialog}). {@code false} — считать по ВСЕМ строкам источника, полностью
     * игнорируя прочие фильтры (флажок «Учитывать отбор» в окне снят).
     */
    @Override
    public List<ColumnValuesDialog.ValueRow> computeColumnValueRows(int column, boolean honorOtherFilters)
    {
        List<ColumnValuesDialog.ValueRow> result = new ArrayList<>();
        if (column < 0)
            return result;
        if (columnValueFilterHost != null)
        {
            // Внешняя модель (напр. коллекция отладчика): только хост знает, как обойти свои строки
            // и учесть сторонний substring-поиск + отбор по другим колонкам.
            List<ColumnValuesDialog.ValueRow> rows = columnValueFilterHost.distinctValues(column, honorOtherFilters);
            return rows != null ? rows : result;
        }
        if (multiSelectViewer == null)
            return result;
        if (!(multiSelectViewer.getContentProvider() instanceof IStructuredContentProvider provider))
            return result;
        Object[] elements = provider.getElements(multiSelectViewer.getInput());
        if (elements == null)
            return result;
        ViewerFilter[] allFilters = honorOtherFilters ? multiSelectViewer.getFilters() : new ViewerFilter[0];
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object element : elements)
        {
            boolean matches = true;
            for (ViewerFilter filter : allFilters)
            {
                if (filter == columnValueViewerFilter)
                    continue;
                if (!filter.select(multiSelectViewer, null, element))
                {
                    matches = false;
                    break;
                }
            }
            if (matches && honorOtherFilters)
            {
                for (Map.Entry<Integer, String> entry : columnValueFilters.entrySet())
                {
                    if (entry.getKey().intValue() == column)
                        continue;
                    String text = filterElementText(element, entry.getKey().intValue());
                    if (!entry.getValue().equals(text != null ? text : "")) //$NON-NLS-1$
                    {
                        matches = false;
                        break;
                    }
                }
            }
            if (!matches)
                continue;
            String value = filterElementText(element, column);
            if (value == null)
                continue;
            counts.merge(value, Integer.valueOf(1), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            result.add(new ColumnValuesDialog.ValueRow(entry.getKey(), entry.getValue().intValue()));
        return result;
    }

    /**
     * Как {@link #activeFiltersDescription()}, но без записи по {@code excludedColumn} — для поля
     * «текущий отбор» в {@link ColumnValuesDialog}: колонка, значения которой сейчас просматривают,
     * там не нужна (она — предмет самого окна, а не контекст).
     */
    private String activeFiltersDescriptionExcluding(int excludedColumn)
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, String> entry : columnValueFilters.entrySet())
        {
            int column = entry.getKey().intValue();
            if (column == excludedColumn)
                continue;
            if (!first)
                sb.append(" И "); //$NON-NLS-1$
            else
                sb.append("Отбор: "); //$NON-NLS-1$
            first = false;
            String header = column >= 0 && column < table.getColumnCount()
                ? table.getColumn(column).getText()
                : null;
            if (header == null || header.isEmpty())
                header = "Колонка " + (column + 1); //$NON-NLS-1$
            sb.append(header).append(" = \"").append(entry.getValue()).append('"'); //$NON-NLS-1$
        }
        return sb.toString();
    }

    @Override
    public void openColumnValuesDialog()
    {
        int column = activeColumnIndex();
        if (!canBrowseColumnValues() || table.getShell() == null)
            return;
        String header = column < table.getColumnCount() ? table.getColumn(column).getText() : ""; //$NON-NLS-1$
        Object activeElement = activeFilterElement();
        String initialValue = activeElement != null
            ? filterElementText(activeElement, column)
            : null;
        List<ColumnValuesDialog.ValueRow> rows = computeColumnValueRows(column, true);
        String originalFilterValue = columnValueFilters.get(Integer.valueOf(column));
        String otherFiltersDescription = activeFiltersDescriptionExcluding(column);
        new ColumnValuesDialog(table.getShell(), this, column, header, rows, initialValue, originalFilterValue,
            otherFiltersDescription).open();
    }

    /** Открыть «Различные значения колонки» для таблицы под фокусом — точка входа для {@link ColumnFilterMenuBuilder#COLUMN_VALUES_COMMAND_ID}. */
    static void openColumnValuesOnFocusedTable()
    {
        Display display = Display.getCurrent();
        if (display == null)
            return;
        for (Control c = display.getFocusControl(); c != null && !c.isDisposed(); c = c.getParent())
        {
            FormTableInteraction interaction = INSTANCES.get(c);
            if (interaction != null)
            {
                interaction.openColumnValuesDialog();
                return;
            }
        }
    }

    private void installColumnValueViewerFilter()
    {
        if (columnValueViewerFilter != null || multiSelectViewer == null)
            return;
        // Один фильтр на все колонки: AND по всем записям columnValueFilters. Снятие отбора
        // очищает карту, но сам фильтр остаётся во viewer — с пустой картой он пропускает всё.
        columnValueViewerFilter = new ViewerFilter()
        {
            @Override
            public boolean select(Viewer viewer, Object parentElement, Object element)
            {
                for (Map.Entry<Integer, String> entry : columnValueFilters.entrySet())
                {
                    String text = filterElementText(element, entry.getKey().intValue());
                    if (!entry.getValue().equals(text != null ? text : "")) //$NON-NLS-1$
                        return false;
                }
                return true;
            }
        };
        multiSelectViewer.addFilter(columnValueViewerFilter);
    }

    @Override
    public void clearColumnFilter(int column)
    {
        if (columnValueFilters.remove(Integer.valueOf(column)) == null)
            return;
        if (columnValueFilterHost != null)
        {
            columnValueFilterHost.applyFilters(new LinkedHashMap<>(columnValueFilters));
            updateFilterIndicators();
            redrawHeader();
            return;
        }
        SelectionSnapshot snapshot = captureSelection();
        refreshKeepingActiveCell(snapshot);
    }

    @Override
    public void clearAllFilters()
    {
        boolean hadColumnFilters = !columnValueFilters.isEmpty();
        columnValueFilters.clear();
        // Сначала снять посторонний отбор по подстроке (поле поиска над таблицей) — его слушатель
        // сам обновит viewer, а наш refresh ниже сведёт оба сброса в один визуальный апдейт.
        if (substringFilterClearer != null)
            substringFilterClearer.run();
        if (columnValueFilterHost != null)
        {
            // Внешний хост: substringFilterClearer уже сбросил поле поиска; сообщаем хосту пустой
            // набор column-value — он пересчитает строки уже без обоих отборов.
            if (hadColumnFilters || substringFilterClearer != null)
                columnValueFilterHost.applyFilters(new LinkedHashMap<>());
            updateFilterIndicators();
            redrawHeader();
            return;
        }
        SelectionSnapshot snapshot = captureSelection();
        if (hadColumnFilters || substringFilterClearer != null)
            refreshKeepingActiveCell(snapshot);
    }

    /**
     * {@code viewer.refresh()} с сохранением активной ячейки ПО ЭЛЕМЕНТУ модели ({@code TableItem}
     * после refresh переиспользуются произвольно, ссылки на виджеты не выживают).
     *
     * <p><b>Выделение отдельно восстанавливать не нужно</b> — {@code StructuredViewer.refresh()}
     * уже делает это сам через внутренний {@code preservingSelection()} (сохраняет {@code
     * getSelection()} до перестроения, восстанавливает после). Раньше здесь ЕЩЁ РАЗ вручную
     * выставлялось выделение через {@code applyViewerSelection()}/{@code table.setSelection()} —
     * это гонялось со штатным восстановлением JFace и было причиной пропадающей подсветки активной
     * строки именно у той строки, что была активна на момент наложения/снятия отбора (двойное,
     * рассинхронизированное выставление выделения на одном и том же {@code TableItem}). «Активная
     * ячейка» ({@link #selectedItem}/{@link #activeColumnWidget}) — своё понятие
     * {@code FormTableInteraction}, JFace о нём не знает, поэтому её всё равно нужно восстанавливать
     * вручную по элементу.
     */
    private void refreshKeepingActiveCell(SelectionSnapshot snapshot)
    {
        if (multiSelectViewer == null || table.isDisposed())
            return;
        Object activeElement = snapshot.activeElement;
        int column = snapshot.activeColumn;
        List<Object> selectedElements = snapshot.selectedElements;

        multiSelectViewer.refresh();

        selectedItem = null;
        selectionAnchor = null;

        // Восстановление выделения по элементам модели. Выставляем его через ШТАТНЫЙ
        // viewer.setSelection(), а НЕ через table.select() + синтетический SWT.Selection:
        // синтетическое событие уходило внешним слушателям (у «Истории Git» — в EGit-овский
        // CommitFileDiffViewer, который асинхронно грузит diff и через ~1 с сам перетряхивал
        // таблицу, сбивая выделение). setSelection синхронизирует и viewer, и нативную таблицу,
        // не порождая ложного пользовательского события.
        // Сопоставление сначала по объекту модели, при промахе — по текстовому ключу строки
        // (объекты могли быть пересозданы провайдером содержимого, см. javadoc SelectionSnapshot).
        List<String> selectedKeys = snapshot.selectedKeys;
        String activeKey = snapshot.activeKey;
        List<Object> elementsToSelect = new ArrayList<>();
        TableItem activeRowAfter = null;
        for (TableItem row : table.getItems())
        {
            Object data = row.getData();
            if (data == null)
                continue;
            String key = null;
            boolean selected = !selectedElements.isEmpty() && selectedElements.contains(data);
            if (!selected && !selectedKeys.isEmpty())
            {
                key = rowKey(row);
                selected = key != null && selectedKeys.contains(key);
            }
            if (selected)
                elementsToSelect.add(data);
            if (activeRowAfter == null)
            {
                if (activeElement != null && data == activeElement)
                {
                    activeRowAfter = row;
                }
                else if (activeKey != null)
                {
                    if (key == null)
                        key = rowKey(row);
                    if (activeKey.equals(key))
                        activeRowAfter = row;
                }
            }
        }
        suppressTableToViewerSync++;
        try
        {
            if (elementsToSelect.isEmpty())
            {
                table.deselectAll();
                multiSelectViewer.setSelection(StructuredSelection.EMPTY, false);
            }
            else
            {
                multiSelectViewer.setSelection(new StructuredSelection(elementsToSelect), false);
            }
        }
        finally
        {
            table.getDisplay().asyncExec(() ->
            {
                if (!table.isDisposed())
                    suppressTableToViewerSync = Math.max(0, suppressTableToViewerSync - 1);
            });
        }
        if (activeRowAfter != null)
        {
            updateActiveCell(activeRowAfter, column);
            ensureNativeSelection(activeRowAfter);
        }
        else if (activeKey != null && !table.isDisposed())
        {
            // Виртуальная таблица (EGit CommitFileDiffViewer): после refresh активная строка
            // часто оказывается за пределами вьюпорта и не материализована — её нет среди
            // table.getItems() с живым getData(), и цикл выше её не нашёл. Ищем по ключу среди
            // элементов входа viewer, выбираем по индексу (надёжно для VIRTUAL) и асинхронно
            // (после showSelection → materialize) ставим активную ячейку.
            int idx = findDisplayedIndexByKey(activeKey);
            if (idx >= 0 && idx < table.getItemCount())
            {
                final int col0 = column;
                final int finalIdx = idx;
                table.setSelection(new int[] { idx });
                table.showSelection();
                table.getDisplay().asyncExec(() ->
                {
                    if (table.isDisposed() || finalIdx >= table.getItemCount())
                        return;
                    TableItem it = table.getItem(finalIdx);
                    if (it == null || it.isDisposed())
                        return;
                    updateActiveCell(it, col0);
                    ensureNativeSelection(it);
                    redrawSelectedRows();
                    redrawHeader();
                });
            }
        }

        updateFilterIndicators();
        redrawSelectedRows();
        redrawHeader();

        // Внешний слушатель (EGit diff-loader) может сбить нативное выделение ПОСЛЕ нас —
        // восстанавливаем его через тик, если активная строка потеряла выделение.
        table.getDisplay().asyncExec(() ->
        {
            if (table.isDisposed())
                return;
            if (selectedItem != null && !selectedItem.isDisposed() && !isRowSelected(selectedItem))
            {
                ensureNativeSelection(selectedItem);
                redrawSelectedRows();
            }
        });
    }

    private void copyActiveCell()
    {
        String text = activeSelectionText();
        if (text == null)
            return;
        Clipboard clipboard = new Clipboard(table.getDisplay());
        clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        clipboard.dispose();
        if (copyHook != null)
            copyHook.run();
    }

    /**
     * Текст активной ячейки (как для копирования по Ctrl+C/меню) — для внешних обработчиков
     * копирования (например, {@code IHandlerService}-перехват "org.eclipse.ui.edit.copy" в местах,
     * где Ctrl+C не доходит до {@code SWT.KeyDown}, см. {@code PreferenceSearchFilterAugmenter}).
     * {@code null}, если активной ячейки сейчас нет.
     */
    String activeCellText()
    {
        TableItem item = selectedItem != null && !selectedItem.isDisposed()
            ? selectedItem
            : currentSelectedRow();
        int idx = activeColumnIndex();
        if (item == null || item.isDisposed() || idx < 0)
            return null;
        String text = resolveCellText(item, idx);
        return text != null ? text : ""; //$NON-NLS-1$
    }

    /**
     * Текст для копирования (Ctrl+C/меню/внешние обработчики — тот же случай, что и
     * {@link #activeCellText()}): одна выделенная строка — активная ячейка, как раньше.
     * Несколько выделенных строк (таблица {@code SWT.MULTI}) — ячейки АКТИВНОЙ колонки по всем
     * выделенным строкам, в порядке отображения (не порядке выделения), через перевод строки —
     * не вся строка целиком (правило проекта: копировать только активную колонку).
     * {@code null}, если активной колонки сейчас нет.
     */
    String activeSelectionText()
    {
        TableItem[] selection = table.getSelection();
        if (selection.length <= 1)
            return activeCellText();

        int column = activeColumnIndex();
        if (column < 0)
            return null;
        Arrays.sort(selection, Comparator.comparingInt(table::indexOf));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selection.length; i++)
        {
            if (i > 0)
                sb.append('\n');
            String text = resolveCellText(selection[i], column);
            sb.append(text != null ? text : ""); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Только фон ячейки строки под курсором — текст (в т.ч. его цвет, выравнивание, обрезка)
     * остаётся штатной отрисовкой SWT, как у невыделенных строк.
     */
    private void onEraseItem(Event e)
    {
        if (!(e.item instanceof TableItem item))
            return;
        if (!isRowSelected(item) && item != selectedItem)
            return;
        Color bg = selectionCellBackground(table, item, e.index);
        if (bg == null)
            return;
        // Одна и та же отрисовка для обеих тем. Раньше тёмная шла через
        // ListSelectionThemeColors.fillSelectionBackground со своим расчётом границ
        // (item.getBounds(col) + растягивание последней колонки до края клиентской области) —
        // из-за этого прямоугольник ячейки не совпадал с тем, что заливает светлая тема, и
        // различие «активная ячейка / прочие ячейки текущей строки» в тёмной теме пропадало.
        // В светлой теме оформление всегда было единообразным именно потому, что здесь
        // заливается ровно прямоугольник ячейки из события.
        e.gc.setBackground(bg);
        e.gc.fillRectangle(e.x, e.y, e.width, e.height);
        e.detail &= ~SWT.BACKGROUND;
        if (ListSelectionThemeColors.isDarkList(table))
        {
            // Заливка выше кладёт верный цвет в верный прямоугольник (подтверждено логом
            // formtable-erase), но с оставшимся флагом SELECTED Windows дорисовывает поверх неё
            // СВОЮ подсветку выделения — единым цветом на всю строку, стирая различие «активная
            // ячейка / прочие ячейки текущей строки». Заметно это было только в обычных колонках:
            // owner-draw колонка («Текст») переживала затирание, т.к. её фон кладётся повторно
            // позже, в фазе PaintItem (installSelectionPrePaintFilter).
            // Только тёмная тема — в светлой оформление и так корректно, поведение не меняем.
            e.detail &= ~SWT.SELECTED;
            e.detail &= ~SWT.HOT;
        }
    }

    private Color selectionCellBackground(Table t, TableItem item, int column)
    {
        if (!isRowSelected(item) && item != selectedItem)
            return null;
        boolean activeRow = item == selectedItem;
        Color rowBg = activeRow ? rowSelectionBackground() : inactiveRowSelectionBackground();
        if (rowBg == null)
            return null;
        boolean isActiveCell = activeRow && column == activeColumnIndex();
        if (isActiveCell)
            return activeCellBackground(rowBg);
        return rowBg;
    }

    /** Рамка активной ячейки поверх фона — не текст, применяется одинаково в обеих темах. */
    private void onPaintItem(Event e)
    {
        if (!(e.item instanceof TableItem item) || item != selectedItem || e.index != activeColumnIndex())
            return;
        Rectangle bounds = item.getBounds(e.index);
        if (bounds == null || bounds.isEmpty())
            return;
        Color rowBg = rowSelectionBackground();
        Color base = activeCellBackground(rowBg);
        Color frame = slightlyDarker(base, 0.12);
        try
        {
            e.gc.setForeground(frame);
            e.gc.drawRectangle(bounds.x, bounds.y, Math.max(0, bounds.width - 1), Math.max(0, bounds.height - 1));
        }
        finally
        {
            if (!frame.isDisposed())
                frame.dispose();
        }
    }

    private boolean isOwnerDrawColumn(int columnIndex)
    {
        if (ownerDrawColumns.length == 0 || columnIndex < 0 || columnIndex >= table.getColumnCount())
            return false;
        TableColumn column = table.getColumn(columnIndex);
        for (TableColumn ownerDraw : ownerDrawColumns)
        {
            if (ownerDraw == column)
                return true;
        }
        return false;
    }

    private void installHeaderOverlays()
    {
        installColumnHeaderListeners();

        if (!resolveOverlayRoot())
        {
            table.getDisplay().asyncExec(this::scheduleHeaderOverlayUpdate);
            return;
        }

        headerSeparator = new Canvas(overlayRoot, SWT.NO_MERGE_PAINTS | SWT.DOUBLE_BUFFERED);
        headerSeparator.setEnabled(false);
        headerSeparator.addPaintListener(new PaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                e.gc.setBackground(headerSeparatorColor());
                e.gc.fillRectangle(0, 0, e.width, e.height);
            }
        });

        headerHighlight = new Canvas(overlayRoot, SWT.NO_MERGE_PAINTS | SWT.DOUBLE_BUFFERED);
        headerHighlight.setEnabled(false);
        headerHighlight.addPaintListener(new PaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                Color accent = headerAccentColor();
                e.gc.setBackground(accent);
                e.gc.fillRectangle(0, 0, e.width, e.height);
            }
        });

        tableResizeListener = new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                scheduleHeaderOverlayUpdate();
                scheduleAutoFill();
            }
        };
        table.addControlListener(tableResizeListener);
        if (columnHost != null)
            columnHost.addControlListener(tableResizeListener);

        ScrollBar horizontal = table.getHorizontalBar();
        if (horizontal != null)
        {
            horizontalScrollListener = new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    scheduleHeaderOverlayUpdate();
                }
            };
            horizontal.addSelectionListener(horizontalScrollListener);
        }

        table.getDisplay().asyncExec(this::scheduleHeaderOverlayUpdate);
    }

    private void installColumnHeaderListeners()
    {
        if (table == null || table.isDisposed())
            return;
        if (columnHeaderListener == null)
        {
            columnHeaderListener = new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
                {
                    scheduleHeaderOverlayUpdate();
                    if (e.widget instanceof TableColumn col && !col.isDisposed())
                    {
                        onUserColumnResize(col);
                    }
                }

                @Override
                public void controlMoved(ControlEvent e)
                {
                    scheduleHeaderOverlayUpdate();
                    redrawSelectedRows();
                }
            };
        }
        for (TableColumn column : table.getColumns())
        {
            if (column.isDisposed() || column.getData(COLUMN_HEADER_KEY) != null)
                continue;
            if (columnReorderEnabled)
                column.setMoveable(true);
            column.setData(COLUMN_HEADER_KEY, Boolean.TRUE);
            column.addControlListener(columnHeaderListener);
        }
    }

    /**
     * Управление колонками: авто-растягивание на всю ширину + пропорциональное сужение правых колонок
     * при перетаскивании границы без Ctrl. Выключается для таблиц со своей логикой колонок (напр.
     * fixed-панель «Коллекции» до её миграции на эту единую логику).
     */
    void setColumnAutoResizeEnabled(boolean enabled)
    {
        columnAutoResizeEnabled = enabled;
    }

    /**
     * Пользовательский ресайз колонки (native drag делителя, реже двойной клик по нему) на Win32.
     * {@code SWT.Resize} колонки приходит через {@code HDN_ITEMCHANGED}, а MouseDown/MouseUp по шапке в
     * SWT не доходят (заголовок — отдельное окно SysHeader32) — надёжного сигнала «drag закончился» нет.
     * Делитель колонки при активном перетаскивании работает как СВОЙ модальный native-loop (аналог
     * известного Win32-ограничения, см. класс-javadoc): если синхронно, из обработчика этого же события,
     * менять ширину ДРУГИХ колонок, native-loop пересчитывает позицию под курсором заново и «отращивает»
     * перетаскиваемую колонку в ответ — получается резонанс с растущим без остановки drag-колонки, пока
     * не отпустят мышь. Поэтому реальное сужение соседей применяется не на каждое событие, а одним
     * commit'ом после паузы в событиях ({@link #schedulePendingResizeCommit}) — вне активного native-loop.
     */
    private void onUserColumnResize(TableColumn col)
    {
        if (selfAdjusting)
            return; // событие от нашего же commit — не переоткладывать накопление заново
        if (col == null || col.isDisposed() || !columnAutoResizeEnabled)
        {
            cancelPendingResizeCommit();
            pendingResizeColumn = null;
            pendingResizeBaseline = null;
            rememberVisualWidths();
            return;
        }
        if (pendingResizeColumn != col)
        {
            pendingResizeColumn = col;
            pendingResizeBaseline = lastKnownVisualWidths;
        }
        schedulePendingResizeCommit();
    }

    /** (Пере)отложить commit накопленного сужения на {@link #RESIZE_COMMIT_DEBOUNCE_MS} мс без новых событий. */
    private void schedulePendingResizeCommit()
    {
        if (table == null || table.isDisposed())
            return;
        Display display = table.getDisplay();
        if (display == null || display.isDisposed())
            return;
        if (pendingResizeCommit != null)
            display.timerExec(-1, pendingResizeCommit);
        pendingResizeCommit = () -> {
            pendingResizeCommit = null;
            commitPendingResize();
        };
        display.timerExec(RESIZE_COMMIT_DEBOUNCE_MS, pendingResizeCommit);
    }

    private void cancelPendingResizeCommit()
    {
        if (pendingResizeCommit != null && table != null && !table.isDisposed())
        {
            table.getDisplay().timerExec(-1, pendingResizeCommit);
            pendingResizeCommit = null;
        }
    }

    /** Фактическое применение накопленного за паузу в drag сужения — вне активного native-loop делителя. */
    private void commitPendingResize()
    {
        TableColumn col = pendingResizeColumn;
        int[] before = pendingResizeBaseline;
        pendingResizeColumn = null;
        pendingResizeBaseline = null;
        if (col == null || col.isDisposed() || !columnAutoResizeEnabled || table == null || table.isDisposed())
        {
            rememberVisualWidths();
            return;
        }
        clampColumnMinWidth(col); // и при зажатом Ctrl тоже — native drag ничем не ограничен снизу
        boolean beforeValid = before != null && before.length == table.getColumnCount();
        if (beforeValid && !ColumnWidthFit.isCtrlPressed())
            applyProportionalShrink(col, before);
        rememberVisualWidths();
        updateFitState(); // для последующего сужения ТАБЛИЦЫ — знать, было ли переполнение уже до него
    }

    /** Пересчитать {@link #columnsFitBefore} по текущему фактическому состоянию колонок и ширины таблицы. */
    private void updateFitState()
    {
        if (table == null || table.isDisposed())
            return;
        int total = 0;
        int cols = table.getColumnCount();
        for (int i = 0; i < cols; i++)
            total += table.getColumn(i).getWidth();
        int clientW = table.getClientArea().width;
        columnsFitBefore = total <= clientW;
        columnsExactFillBefore = total == clientW;
    }

    /**
     * Активен ли сейчас режим заполнения по ширине (правая граница последней колонки = граница таблицы).
     * Потребителям, персистящим ширины колонок между открытиями окна — использовать перед сохранением:
     * если ширины — чистый результат авто-заполнения (а не ручной подгонки пользователем), сохранять их
     * как «сохранённые настройки» не нужно, иначе при следующем открытии {@link #install(boolean)} с
     * {@code hasSavedColumnWidths=true} навсегда отключит повторное авто-заполнение для этого окна.
     */
    boolean isColumnsExactFill()
    {
        if (table == null || table.isDisposed())
            return false;
        int total = 0;
        int cols = table.getColumnCount();
        for (int i = 0; i < cols; i++)
            total += table.getColumn(i).getWidth();
        return total == table.getClientArea().width;
    }

    /** Подтянуть ширину колонки до {@link #minColumnWidth()}, если native drag увёл её ниже (в т.ч. до 0). */
    private void clampColumnMinWidth(TableColumn col)
    {
        if (col == null || col.isDisposed() || isHiddenColumn(col))
            return; // скрытую хозяином колонку (нулевая ширина по метке) до минимума не подтягиваем
        int minWidth = minColumnWidth();
        if (col.getWidth() >= minWidth)
            return;
        selfAdjusting = true;
        try
        {
            col.setWidth(minWidth);
        }
        finally
        {
            selfAdjusting = false;
        }
    }

    /** Минимальная ширина колонки по шрифту таблицы — общий расчёт, см. {@link ColumnWidthFit#minColumnWidth}. */
    private int minColumnWidth()
    {
        return ColumnWidthFit.minColumnWidth(table);
    }

    /** Доступ к колонкам таблицы для общего расчёта ширин ({@link ColumnWidthFit}). */
    private ColumnWidthFit.Columns columnAccess()
    {
        return new ColumnWidthFit.TableColumns(table, i -> isHiddenColumn(table.getColumn(i)));
    }

    /** Запомнить текущие ширины колонок (визуальный порядок) — база до-драг снимка для следующей серии. */
    private void rememberVisualWidths()
    {
        lastKnownVisualWidths = visualOrderWidths();
    }

    /** Ширины колонок в визуальном порядке (с учётом {@link Table#getColumnOrder()}). */
    private int[] visualOrderWidths()
    {
        if (table == null || table.isDisposed())
            return new int[0];
        int[] order = table.getColumnOrder();
        int[] widths = new int[order.length];
        for (int v = 0; v < order.length; v++)
        {
            TableColumn c = table.getColumn(order[v]);
            widths[v] = c.isDisposed() ? 0 : c.getWidth();
        }
        return widths;
    }

    /**
     * Скрыта ли колонка хозяином таблицы (нулевая ширина + метка {@link #COLUMN_HIDDEN_KEY} от
     * {@link #setColumnHidden}). Такие колонки авто-заполнение/сужение не трогает: любой рост (в т.ч. до
     * {@link #minColumnWidth()}) снова сделал бы их видимыми. Нулевая ширина БЕЗ метки — это пользователь
     * увёл границу drag'ом в ноль, её как раз надо подтянуть до минимума ({@link #clampColumnMinWidth}).
     */
    private static boolean isHiddenColumn(TableColumn column)
    {
        return column.getWidth() <= 0 && column.getData(COLUMN_HIDDEN_KEY) != null;
    }

    /**
     * Скрыть/показать колонку — вместо прямого {@code column.setWidth(0)} у хозяина таблицы. Приём штатной
     * панели результатов поиска (см. {@code ConfigSearchResultsHook}: «Путь» скрывается на терминальном узле
     * дерева, «Текст» — для структурных вхождений без текста): колонка остаётся в таблице, но нулевой ширины.
     *
     * <p>Ширина таблицы при этом не меняется, поэтому обычная защита от повторных срабатываний по неизменному
     * {@code clientArea} ({@link #lastAutoFillClientWidth}) заблокировала бы пересчёт заполнения — сбрасываем
     * её: после скрытия освободившееся место отдаётся остальным колонкам (если режим заполнения был активен),
     * после показа общая ширина снова подгоняется под таблицу.
     *
     * @param widthWhenVisible ширина при показе ({@code hidden=false}); при скрытии игнорируется.
     */
    void setColumnHidden(TableColumn column, boolean hidden, int widthWhenVisible)
    {
        if (column == null || column.isDisposed())
            return;
        column.setData(COLUMN_HIDDEN_KEY, hidden ? Boolean.TRUE : null);
        int width = hidden ? 0 : Math.max(minColumnWidth(), widthWhenVisible);
        if (column.getWidth() != width)
        {
            // selfAdjusting — чтобы наш же setWidth не был принят за пользовательский drag границы
            // (иначе commitPendingResize подтянул бы скрываемую колонку до minColumnWidth()).
            selfAdjusting = true;
            try
            {
                column.setWidth(width);
            }
            finally
            {
                selfAdjusting = false;
            }
        }
        rememberVisualWidths();
        lastAutoFillClientWidth = -1;
        scheduleAutoFill();
    }

    /**
     * Авто-заполнение ширины (отложенный запуск через timerExec=0) при реальном изменении ширины
     * таблицы/хоста — растягивание или сужение всех resizable-колонок пропорционально их текущим ширинам.
     * Для таблиц с {@link TableColumnLayout} запускается ПОСЛЕ его синхронной раскладки, поэтому наши
     * ширины оказываются последними и не конфликтуют с layout.
     */
    private void scheduleAutoFill()
    {
        if (table == null || table.isDisposed())
            return;
        Display display = table.getDisplay();
        if (display == null || display.isDisposed())
            return;
        if (pendingAutoFill != null)
            display.timerExec(-1, pendingAutoFill);
        pendingAutoFill = () -> {
            pendingAutoFill = null;
            autoFillColumns();
        };
        display.timerExec(0, pendingAutoFill);
    }

    /**
     * Растягиваем/сужаем колонки при реальном изменении ширины таблицы/хоста — только чтобы СОХРАНИТЬ
     * ранее активный режим заполнения по ширине (правая граница последней колонки = граница таблицы),
     * а не включить его самовольно там, где до этого специально был запас свободного места:
     * <ul>
     * <li>таблица расширилась ({@code total < clientW}) — растягиваем на всю ширину ТОЛЬКО если до этого
     * изменения колонки точно её заполняли ({@link #columnsExactFillBefore}). Если был запас свободного
     * места — просто становится больше свободного места, колонки не трогаем;</li>
     * <li>таблица сузилась ({@code total > clientW}) — сужаем ТОЛЬКО если до этого колонки хотя бы
     * умещались без переполнения ({@link #columnsFitBefore}, заполнение точно или с запасом). Если
     * переполнение уже было ДО этого (создано перетаскиванием колонки, не сужением таблицы) — не трогаем.</li>
     * </ul>
     * Срабатывает только при реальном изменении ширины таблицы/хоста — не на любое postCondition-уведомление
     * controlResized, иначе после сужения колонки drag'ом (см. {@link #applyProportionalShrink}) сюда
     * прилетало бы лишнее срабатывание и трогало ВСЕ колонки.
     */
    private void autoFillColumns()
    {
        if (table == null || table.isDisposed() || !columnAutoResizeEnabled)
            return;
        if (selfAdjusting)
            return;
        int cols = table.getColumnCount();
        if (cols <= 0)
            return;
        int clientW = table.getClientArea().width;
        if (clientW <= 0)
            return;
        if (clientW == lastAutoFillClientWidth)
            return;
        lastAutoFillClientWidth = clientW;
        int total = 0;
        for (int i = 0; i < cols; i++)
            total += table.getColumn(i).getWidth();
        if (total < clientW)
        {
            if (columnsExactFillBefore)
            {
                growColumnsToFill(total, clientW, cols);
                columnsFitBefore = true;
                columnsExactFillBefore = true;
            }
            else
            {
                // Был запас свободного места (не заполняли по ширине) — не включаем режим заполнения
                // самовольно, просто становится больше свободного места.
                columnsFitBefore = true;
                columnsExactFillBefore = false;
            }
        }
        else if (total == clientW)
        {
            columnsFitBefore = true;
            columnsExactFillBefore = true;
        }
        else if (columnsFitBefore)
        {
            shrinkColumnsToFit(total, clientW, cols);
        }
        else
        {
            columnsExactFillBefore = false; // переполнение уже было ДО этого — не наш случай, не трогаем
        }
    }

    private void growColumnsToFill(int total, int clientW, int cols)
    {
        int stretchCount;
        selfAdjusting = true;
        try
        {
            stretchCount = ColumnWidthFit.grow(columnAccess(), total, clientW, minColumnWidth());
            if (stretchCount > 0)
                rebindColumnLayoutData();
        }
        finally
        {
            selfAdjusting = false;
        }
        if (stretchCount == 0)
            return;
    }

    private void shrinkColumnsToFit(int total, int clientW, int cols)
    {
        int deficit = total - clientW;
        int shrinkCount;
        selfAdjusting = true;
        try
        {
            shrinkCount = ColumnWidthFit.shrink(columnAccess(), total, clientW, minColumnWidth());
            if (shrinkCount > 0)
                rebindColumnLayoutData();
        }
        finally
        {
            selfAdjusting = false;
        }
        if (shrinkCount == 0)
            return;
        int actualTotal = 0;
        for (int i = 0; i < cols; i++)
            actualTotal += table.getColumn(i).getWidth();
        // Если уперлись в пол и полностью сузить не удалось — переполнение остаётся, и это уже
        // не результат сужения ТАБЛИЦЫ, а нехватка места по факту: дальше не наш случай (как обычный overflow).
        columnsFitBefore = actualTotal <= clientW;
        columnsExactFillBefore = actualTotal == clientW;
    }

    /**
     * Перетаскивание границы БЕЗ Ctrl. Колонки правее трогаются ТОЛЬКО на величину, которую не удалось
     * поглотить/освободить за счёт уже имеющегося запаса, и только когда правая граница последней колонки
     * совпадает (или превосходит) границу таблицы — т.е. активен режим заполнения по ширине:
     * <ul>
     * <li>граница вправо (колонка выросла, {@code delta > 0}) — если в таблице было свободное место
     * ({@code totalBefore < clientW}), рост сначала расходует его, соседей не трогая; сужение соседей
     * применяется только к части роста, которая это свободное место превысила;</li>
     * <li>граница влево (колонка сузилась, {@code delta < 0}), а до этого было свободное место
     * ({@code totalBefore < clientW}, режим заполнения НЕ активен) — соседи не трогаются вообще,
     * освободившееся место остаётся пустым;</li>
     * <li>граница влево, а до этого было переполнение или точное совпадение ({@code totalBefore >= clientW})
     * — сужение СНАЧАЛА расходуется на устранение переполнения, соседей не трогая. Как только переполнение
     * устранено (граница совпала), дальнейший ИЗБЫТОК сужения поддерживает заполнение по ширине —
     * идёт на пропорциональный рост соседей.</li>
     * </ul>
     * Non-resizable правые колонки не трогаются — их ширина вычитается из перераспределяемого остатка.
     * При нехватке места правые сужаются до {@link #minColumnWidth()}. База ({@code before}) — снимок на
     * начало серии событий текущего drag, применяется одним commit'ом ({@link #commitPendingResize}).
     *
     * <p>Сам расчёт — общий с деревьями ({@link ColumnWidthFit#applyProportionalShrink}); здесь только
     * специфика таблицы: подавление собственных событий ресайза и синхронизация {@link TableColumnLayout}.
     * Собственная ширина {@code dragged} к моменту вызова уже подтянута к минимуму в
     * {@link #commitPendingResize} ({@link #clampColumnMinWidth}).
     */
    private void applyProportionalShrink(TableColumn dragged, int[] before)
    {
        if (table == null || table.isDisposed() || !columnAutoResizeEnabled)
            return;
        if (dragged == null || dragged.isDisposed())
            return;
        selfAdjusting = true;
        try
        {
            boolean applied = ColumnWidthFit.applyProportionalShrink(columnAccess(), table.indexOf(dragged),
                before, minColumnWidth(), table.getClientArea().width);
            if (applied)
                rebindColumnLayoutData();
        }
        finally
        {
            selfAdjusting = false;
        }
    }

    /**
     * Финальная защита от горизонтального скролла: если после fill/shrink/сужения соседей сумма ширин
     * всё же превысила ширину таблицы (округления при пропорциональном делении, неточности измерения
     * {@code clientArea} и т.п.) — обрезаем избыток с последней resizable-колонки (и, если не хватит, с
     * предыдущих), не глубже {@link #minColumnWidth()}. Лучше колонки чуть уже, чем горизонтальный скролл.
     * Вызывать ВНУТРИ уже открытого {@code selfAdjusting=true} блока, перед {@link #rebindColumnLayoutData()}.
     */
    private void clampTotalToClientWidth()
    {
        if (table == null || table.isDisposed())
            return;
        ColumnWidthFit.clampTotalToClientWidth(columnAccess(), minColumnWidth());
    }

    /**
     * Синхронизация {@link TableColumnLayout}-данных с реально выставленными ширинами после fill/shrink.
     * Без неё layout при следующем ресайзе хоста вернёт колонкам исходные {@link ColumnPixelData}-ширины,
     * отменив правку. Сам {@code setColumnData} раскладку не запускает, а данные уже совпадают с текущими
     * ширинами — вызывать {@code layout()} не нужно.
     */
    private void rebindColumnLayoutData()
    {
        if (table == null || table.isDisposed())
            return;
        Composite host = table.getParent();
        if (host == null || host.isDisposed()
            || !(host.getLayout() instanceof TableColumnLayout layout))
            return;
        int cols = table.getColumnCount();
        for (int i = 0; i < cols; i++)
        {
            TableColumn c = table.getColumn(i);
            if (c.isDisposed())
                continue;
            // Скрытая нулевой шириной колонка (см. isHiddenColumn) остаётся нулевой — иначе layout
            // при следующей раскладке вернул бы ей 1 px и сделал видимой.
            int width = isHiddenColumn(c) ? 0 : Math.max(1, c.getWidth());
            layout.setColumnData(c, new ColumnPixelData(width, c.getResizable(), i < cols - 1));
        }
    }

    private void uninstallColumnHeaderListeners()
    {
        if (table == null || table.isDisposed() || columnHeaderListener == null)
            return;
        for (TableColumn column : table.getColumns())
        {
            if (column.isDisposed() || column.getData(COLUMN_HEADER_KEY) == null)
                continue;
            column.removeControlListener(columnHeaderListener);
            column.setData(COLUMN_HEADER_KEY, null);
            column.setData(AUTO_HEADER_TOOLTIP_KEY, null);
        }
    }

    private void updateColumnHeaderTooltips()
    {
        if (table == null || table.isDisposed() || !table.getHeaderVisible())
            return;
        for (TableColumn column : table.getColumns())
        {
            if (column.isDisposed())
                continue;
            String header = column.getText();
            if (header == null || header.isEmpty())
            {
                clearAutoHeaderTooltip(column);
                continue;
            }
            String current = column.getToolTipText();
            boolean auto = Boolean.TRUE.equals(column.getData(AUTO_HEADER_TOOLTIP_KEY));
            if (current != null && !current.isEmpty() && (!auto || !current.equals(header)))
                continue;
            String desired = isHeaderTextTruncated(column, header) ? header : null;
            if (!Objects.equals(current, desired))
            {
                column.setToolTipText(desired);
                column.setData(AUTO_HEADER_TOOLTIP_KEY, desired != null ? Boolean.TRUE : null);
            }
        }
    }

    private void clearAutoHeaderTooltip(TableColumn column)
    {
        if (column.isDisposed() || !Boolean.TRUE.equals(column.getData(AUTO_HEADER_TOOLTIP_KEY)))
            return;
        String current = column.getToolTipText();
        if (current != null && !current.isEmpty())
            column.setToolTipText(null);
        column.setData(AUTO_HEADER_TOOLTIP_KEY, null);
    }

    private boolean isHeaderTextTruncated(TableColumn column, String text)
    {
        if (column.isDisposed() || text == null || text.isEmpty())
            return false;
        int available = column.getWidth() - HEADER_TEXT_INSET;
        if (available <= 0)
            return false;
        GC gc = new GC(table);
        try
        {
            gc.setFont(table.getFont());
            int textWidth = gc.textExtent(text, SWT.DRAW_TRANSPARENT).x;
            return textWidth > available;
        }
        finally
        {
            gc.dispose();
        }
    }

    private boolean resolveOverlayRoot()
    {
        Composite tableParent = table.getParent();
        if (tableParent == null || tableParent.isDisposed())
            return false;

        columnHost = null;
        overlayRoot = tableParent;
        boolean resolved = true;
        if (tableParent.getLayout() instanceof org.eclipse.jface.layout.TableColumnLayout)
        {
            columnHost = tableParent;
            overlayRoot = tableParent.getParent();
            if (overlayRoot == null || overlayRoot.isDisposed())
                resolved = false;
            else if (overlayRoot.getLayout() != null)
                resolved = false;
            else
                installColumnHostBoundsMaintainer();
        }
        else if (overlayRoot.getLayout() != null)
        {
            resolved = false;
        }
        return resolved;
    }

    private void installColumnHostBoundsMaintainer()
    {
        if (columnHost == null || overlayRoot == null)
            return;
        stackResizeListener = new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                layoutColumnHostInOverlayRoot();
                scheduleHeaderOverlayUpdate();
            }
        };
        overlayRoot.addControlListener(stackResizeListener);
        layoutColumnHostInOverlayRoot();
    }

    private void layoutColumnHostInOverlayRoot()
    {
        if (columnHost == null || overlayRoot == null || columnHost.isDisposed() || overlayRoot.isDisposed())
            return;
        Rectangle area = overlayRoot.getClientArea();
        columnHost.setBounds(0, 0, area.width, area.height);
    }

    private void scheduleHeaderOverlayUpdate()
    {
        if (table == null || table.isDisposed())
            return;
        Display display = table.getDisplay();
        if (display == null || display.isDisposed())
            return;
        if (pendingHeaderOverlayUpdate != null)
            display.timerExec(-1, pendingHeaderOverlayUpdate);
        pendingHeaderOverlayUpdate = () -> {
            pendingHeaderOverlayUpdate = null;
            updateHeaderOverlays();
        };
        display.timerExec(0, pendingHeaderOverlayUpdate);
    }

    private void uninstallHeaderOverlays()
    {
        if (table != null && !table.isDisposed())
        {
            if (tableResizeListener != null)
                table.removeControlListener(tableResizeListener);
            if (columnHost != null && !columnHost.isDisposed())
                columnHost.removeControlListener(tableResizeListener);
            ScrollBar horizontal = table.getHorizontalBar();
            if (horizontal != null && horizontalScrollListener != null)
                horizontal.removeSelectionListener(horizontalScrollListener);
        }
        if (overlayRoot != null && !overlayRoot.isDisposed() && stackResizeListener != null)
            overlayRoot.removeControlListener(stackResizeListener);
        uninstallColumnHeaderListeners();
        disposeFilterIndicators();
        if (headerSeparator != null && !headerSeparator.isDisposed())
            headerSeparator.dispose();
        if (headerHighlight != null && !headerHighlight.isDisposed())
            headerHighlight.dispose();
        headerSeparator = null;
        headerHighlight = null;
        columnHost = null;
        overlayRoot = null;
        tableResizeListener = null;
        stackResizeListener = null;
        horizontalScrollListener = null;
    }

    private void updateHeaderOverlays()
    {
        installColumnHeaderListeners();
        updateColumnHeaderTooltips();
        if (headerSeparator == null || headerSeparator.isDisposed())
            return;
        updateHeaderSeparatorBounds();
        updateHeaderHighlightBounds();
        updateFilterIndicatorBounds();
    }

    private void updateHeaderSeparatorBounds()
    {
        if (headerSeparator == null || headerSeparator.isDisposed() || table.isDisposed())
            return;
        if (!table.getHeaderVisible())
        {
            headerSeparator.setVisible(false);
            return;
        }
        int headerH = table.getHeaderHeight();
        if (headerH <= 0)
        {
            headerSeparator.setVisible(false);
            return;
        }
        Point origin = tableOriginInOverlayRoot();
        Rectangle client = table.getClientArea();
        if (client.width <= 0)
        {
            headerSeparator.setVisible(false);
            return;
        }
        int x = origin.x + table.getBorderWidth();
        int y = origin.y + headerH - HEADER_SEPARATOR_HEIGHT;
        headerSeparator.setBounds(x, y, client.width, HEADER_SEPARATOR_HEIGHT);
        headerSeparator.setVisible(true);
        headerSeparator.moveAbove(columnHost != null ? columnHost : table);
        headerSeparator.redraw();
    }

    private void updateHeaderHighlightBounds()
    {
        if (headerHighlight == null || headerHighlight.isDisposed() || table.isDisposed())
            return;
        if (!table.getHeaderVisible() || activeColumnIndex() < 0)
        {
            headerHighlight.setVisible(false);
            return;
        }
        Rectangle columnHeader = columnHeaderBounds(activeColumnIndex());
        if (columnHeader == null || columnHeader.isEmpty())
        {
            headerHighlight.setVisible(false);
            return;
        }
        Point origin = tableOriginInOverlayRoot();
        int x = origin.x + table.getBorderWidth() + columnHeader.x;
        int y = origin.y + columnHeader.y + columnHeader.height - HEADER_ACCENT_HEIGHT - HEADER_SEPARATOR_HEIGHT;
        headerHighlight.setBounds(x, y, columnHeader.width, HEADER_ACCENT_HEIGHT);
        headerHighlight.setVisible(true);
        headerHighlight.moveAbove(headerSeparator != null && !headerSeparator.isDisposed()
            ? headerSeparator
            : (columnHost != null ? columnHost : table));
        headerHighlight.redraw();
    }

    /**
     * Значки «снять отбор» в шапке — по одному на отфильтрованную колонку. Создаются/удаляются
     * при изменении набора фильтров; позиционируются вместе с прочими оверлеями шапки.
     */
    private void updateFilterIndicators()
    {
        if (table == null || table.isDisposed())
            return;
        List<Integer> stale = new ArrayList<>();
        for (Map.Entry<Integer, Canvas> entry : filterIndicators.entrySet())
        {
            if (!columnValueFilters.containsKey(entry.getKey()))
                stale.add(entry.getKey());
        }
        for (Integer column : stale)
        {
            Canvas canvas = filterIndicators.remove(column);
            if (canvas != null && !canvas.isDisposed())
                canvas.dispose();
        }
        if (overlayRoot == null || overlayRoot.isDisposed())
            return;
        for (Map.Entry<Integer, String> entry : columnValueFilters.entrySet())
        {
            Canvas canvas = filterIndicators.get(entry.getKey());
            if (canvas == null || canvas.isDisposed())
            {
                canvas = createFilterIndicator(entry.getKey().intValue());
                if (canvas == null)
                    continue;
                filterIndicators.put(entry.getKey(), canvas);
            }
            canvas.setToolTipText("Снять отбор по значению «" + entry.getValue() + "»"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        updateFilterIndicatorBounds();
    }

    private Canvas createFilterIndicator(int column)
    {
        if (overlayRoot == null || overlayRoot.isDisposed())
            return null;
        Canvas canvas = new Canvas(overlayRoot, SWT.NO_MERGE_PAINTS | SWT.DOUBLE_BUFFERED);
        canvas.setBackground(table.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        canvas.setCursor(table.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        canvas.addPaintListener(new PaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                Image glyph = filterGlyph();
                if (glyph == null || glyph.isDisposed())
                    return;
                Rectangle glyphBounds = glyph.getBounds();
                Point size = ((Canvas)e.widget).getSize();
                int x = Math.max(0, (size.x - glyphBounds.width) / 2);
                int y = Math.max(0, (size.y - glyphBounds.height) / 2);
                e.gc.drawImage(glyph, x, y);
            }
        });
        canvas.addListener(SWT.MouseUp, e -> clearColumnFilter(column));
        return canvas;
    }

    private void updateFilterIndicatorBounds()
    {
        if (filterIndicators.isEmpty() || table == null || table.isDisposed())
            return;
        boolean headerVisible = table.getHeaderVisible() && table.getHeaderHeight() > 0;
        Point origin = tableOriginInOverlayRoot();
        Rectangle client = table.getClientArea();
        int left = origin.x + table.getBorderWidth();
        for (Map.Entry<Integer, Canvas> entry : filterIndicators.entrySet())
        {
            Canvas canvas = entry.getValue();
            if (canvas == null || canvas.isDisposed())
                continue;
            Rectangle header = headerVisible ? columnHeaderBounds(entry.getKey().intValue()) : null;
            if (header == null || header.isEmpty() || header.width < FILTER_GLYPH_SIZE + 4)
            {
                canvas.setVisible(false);
                continue;
            }
            int size = Math.min(FILTER_GLYPH_SIZE, Math.max(0, header.height - 4));
            int x = left + header.x + header.width - size - 2;
            int y = origin.y + header.y + Math.max(0, (header.height - size) / 2);
            // При горизонтальном скролле заголовок может уехать за пределы клиентской области —
            // значок не должен рисоваться поверх соседних контролов.
            if (size <= 0 || x < left || x + size > left + client.width)
            {
                canvas.setVisible(false);
                continue;
            }
            canvas.setBounds(x, y, size, size);
            canvas.setVisible(true);
            canvas.moveAbove(headerHighlight != null && !headerHighlight.isDisposed()
                ? headerHighlight
                : (columnHost != null ? columnHost : table));
            canvas.redraw();
        }
    }

    private void disposeFilterIndicators()
    {
        for (Canvas canvas : filterIndicators.values())
        {
            if (canvas != null && !canvas.isDisposed())
                canvas.dispose();
        }
        filterIndicators.clear();
    }

    /** Общий глиф «×» для значка в шапке и для пункта меню «Отключить все фильтры». */
    private Image filterGlyph()
    {
        if (filterGlyph != null && !filterGlyph.isDisposed())
            return filterGlyph;
        if (table == null || table.isDisposed())
            return null;
        Display display = table.getDisplay();
        Image image = new Image(display, FILTER_GLYPH_SIZE, FILTER_GLYPH_SIZE);
        GC gc = new GC(image);
        try
        {
            gc.setAdvanced(true);
            gc.setAntialias(SWT.ON);
            gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(0, 0, FILTER_GLYPH_SIZE, FILTER_GLYPH_SIZE);
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            gc.setLineWidth(2);
            int pad = 3;
            gc.drawLine(pad, pad, FILTER_GLYPH_SIZE - pad, FILTER_GLYPH_SIZE - pad);
            gc.drawLine(FILTER_GLYPH_SIZE - pad, pad, pad, FILTER_GLYPH_SIZE - pad);
        }
        finally
        {
            gc.dispose();
        }
        filterGlyph = image;
        return filterGlyph;
    }

    private Point tableOriginInOverlayRoot()
    {
        if (overlayRoot == null || table.isDisposed())
            return new Point(0, 0);
        if (columnHost != null && !columnHost.isDisposed())
        {
            Point hostLoc = columnHost.getLocation();
            Point tableLoc = table.getLocation();
            return new Point(hostLoc.x + tableLoc.x, hostLoc.y + tableLoc.y);
        }
        return table.getLocation();
    }

    /**
     * Нативное выделение — источник истины для покраски (именно на него ориентируются
     * EraseItem/PaintItem и реальная нативная отрисовка Windows). Раньше при {@code SWT.MULTI}
     * здесь проверялось ТОЛЬКО {@code multiSelectViewer.getStructuredSelection()} — если после
     * клика таблица обновится (например, {@code TableViewer.setInput(...)} с новыми объектами строк,
     * не совпадающими по {@code equals()} со старыми, как {@code MatchRow}/{@code FileSearchRow} без
     * переопределённого {@code equals()}), выделение во viewer теряется, а нативное {@code Table}
     * всё ещё показывает строку выделенной — эта проверка возвращала {@code false}, и вся наша
     * покраска (фон строки/активной ячейки, синхронизация фона owner-draw колонки) отключалась,
     * оставляя строку на откуп нативной Windows-отрисовке (несогласованной между обычными и
     * owner-draw колонками — отсюда разные цвета в одной "текущей" строке).
     */
    private boolean isRowSelected(TableItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        for (TableItem s : table.getSelection())
        {
            if (s == item)
                return true;
        }
        if (useViewerForMultiSelect())
        {
            Object data = item.getData();
            if (data != null)
                return multiSelectViewer.getStructuredSelection().toList().contains(data);
        }
        return false;
    }

    private TableItem currentSelectedRow()
    {
        TableItem[] selection = table.getSelection();
        if (selection.length > 0)
            return selection[0];
        return selectedItem;
    }

    private void redrawRow(TableItem item)
    {
        if (item == null || item.isDisposed() || table.isDisposed())
            return;
        syncCellBackgrounds(item);
        Rectangle bounds = rowBounds(item);
        if (bounds == null || bounds.isEmpty())
            return;
        table.redraw(bounds.x, bounds.y, bounds.width, bounds.height, false);
    }

    /**
     * Фон ячеек строки — через штатное {@code TableItem.setBackground(колонка, …)}, а не только
     * заливкой в {@link #onEraseItem}.
     *
     * <p>Заливки в {@code EraseItem} достаточно для общего фона СТРОКИ, но не для отдельной ячейки:
     * у обычных (не owner-draw) колонок нативная отрисовка строки перекрывает её, и более светлый
     * оттенок АКТИВНОЙ ячейки пропадал — он был виден только в owner-draw колонке («Текст»), куда
     * цвет доезжал через {@code cell.getBackground()} (то же самое {@code TableItem}-свойство,
     * которое {@code StyledCellLabelProvider} накладывает на GC перед отрисовкой текста).
     * Per-cell background — единственный механизм, который одинаково уважают ОБА пути отрисовки,
     * поэтому синхронизируем его для ВСЕХ колонок тем же цветом, что даёт
     * {@link #selectionCellBackground} ({@code null} для невыделенных строк — сброс к штатному).
     *
     * <p>Только тёмная тема: в светлой оформление и так соблюдается везде единообразно, и вмешиваться
     * в нативную отрисовку там незачем.
     */
    private void syncCellBackgrounds(TableItem item)
    {
        if (item == null || item.isDisposed() || table.isDisposed())
            return;
        if (!ListSelectionThemeColors.isDarkList(table))
            return;
        int cols = table.getColumnCount();
        for (int idx = 0; idx < cols; idx++)
            item.setBackground(idx, selectionCellBackground(table, item, idx));
    }

    private void redrawRows(TableItem[] items)
    {
        if (items == null)
            return;
        for (TableItem item : items)
            redrawRow(item);
    }

    private void redrawAffectedRows(TableItem[] previousSelection, TableItem[] currentSelection,
        TableItem previousActive)
    {
        redrawRows(previousSelection);
        redrawRows(currentSelection);
        if (previousActive != null && !isRowSelected(previousActive))
            redrawRow(previousActive);
        redrawHeader();
    }

    private Rectangle rowBounds(TableItem item)
    {
        if (item == null || item.isDisposed())
            return null;
        int cols = table.getColumnCount();
        if (cols <= 0)
            return item.getBounds();
        Rectangle bounds = item.getBounds(0);
        for (int col = 1; col < cols; col++)
            bounds = bounds.union(item.getBounds(col));
        return bounds;
    }

    private void redrawSelectedRows()
    {
        redrawRows(table.getSelection());
        if (selectedItem != null && !isRowSelected(selectedItem))
            redrawRow(selectedItem);
    }

    private void redrawHeader()
    {
        scheduleHeaderOverlayUpdate();
    }

    /**
     * Границы заголовка колонки в координатах клиентской области таблицы.
     * {@code column} — индекс создания ({@link Table#indexOf(TableColumn)}), не визуальная
     * позиция: после reorder {@link Table#getColumnOrder()} визуальный порядок ≠ 0..n-1.
     */
    private Rectangle columnHeaderBounds(int column)
    {
        if (column < 0 || column >= table.getColumnCount())
            return null;
        int width = table.getColumn(column).getWidth();
        int height = table.getHeaderHeight();
        int x;
        if (table.getItemCount() > 0)
        {
            // getBounds(creationIndex) уже учитывает columnOrder и горизонтальный скролл.
            Rectangle cell = table.getItem(0).getBounds(column);
            x = cell.x;
        }
        else
        {
            x = 0;
            int[] order = table.getColumnOrder();
            for (int visual = 0; visual < order.length; visual++)
            {
                int creation = order[visual];
                if (creation == column)
                    break;
                x += table.getColumn(creation).getWidth();
            }
            ScrollBar horizontal = table.getHorizontalBar();
            if (horizontal != null)
                x -= horizontal.getSelection();
        }
        return new Rectangle(x, 0, width, height);
    }

    private static int columnAt(int x, int y, TableItem item)
    {
        if (item == null)
            return -1;
        int count = item.getParent().getColumnCount();
        for (int i = 0; i < count; i++)
        {
            Rectangle bounds = item.getBounds(i);
            if (bounds.contains(x, y))
                return i;
        }
        return 0;
    }

    private Color rowSelectionBackground()
    {
        if (ownedRowBg != null && !ownedRowBg.isDisposed())
            return ownedRowBg;
        if (ListSelectionThemeColors.isDarkList(table))
        {
            ownedRowBg = ListSelectionThemeColors.listSelectionBackground(table, table.isFocusControl());
            return ownedRowBg;
        }
        Color base = table.getBackground();
        if (base == null || base.isDisposed())
            base = table.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        double factor = table.isFocusControl() ? 0.045 : 0.03;
        ownedRowBg = slightlyDarker(base, factor);
        return ownedRowBg;
    }

    /** Фон прочих выбранных строк при мультивыделении (слабее текущей). */
    private Color inactiveRowSelectionBackground()
    {
        if (!isMultiSelect())
            return rowSelectionBackground();
        if (ownedInactiveRowBg != null && !ownedInactiveRowBg.isDisposed())
            return ownedInactiveRowBg;
        if (ListSelectionThemeColors.isDarkList(table))
        {
            ownedInactiveRowBg = ListSelectionThemeColors.inactiveRowSelectionBackground(
                table, table.isFocusControl());
            return ownedInactiveRowBg;
        }
        Color base = table.getBackground();
        if (base == null || base.isDisposed())
            base = table.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        double factor = table.isFocusControl() ? 0.034 : 0.0225;
        ownedInactiveRowBg = slightlyDarker(base, factor);
        return ownedInactiveRowBg;
    }

    private Color activeCellBackground(Color rowBg)
    {
        if (ownedActiveCellBg != null && !ownedActiveCellBg.isDisposed())
            return ownedActiveCellBg;
        if (ListSelectionThemeColors.isDarkList(table))
        {
            ownedActiveCellBg = ListSelectionThemeColors.activeCellBackground(table, rowBg);
            return ownedActiveCellBg;
        }
        ownedActiveCellBg = slightlyDarker(rowBg, table.isFocusControl() ? 0.034 : 0.0225);
        return ownedActiveCellBg;
    }

    private Color headerAccentColor()
    {
        if (ownedHeaderAccentBg != null && !ownedHeaderAccentBg.isDisposed())
            return ownedHeaderAccentBg;
        Color base = table.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        double factor = table.isFocusControl() ? 0.12 : 0.07;
        ownedHeaderAccentBg = slightlyDarker(base, factor);
        return ownedHeaderAccentBg;
    }

    private Color headerSeparatorColor()
    {
        if (ownedHeaderSeparatorBg != null && !ownedHeaderSeparatorBg.isDisposed())
            return ownedHeaderSeparatorBg;
        Color base = table.getBackground();
        if (base == null || base.isDisposed())
            base = table.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        ownedHeaderSeparatorBg = tableGridLineColor(base);
        return ownedHeaderSeparatorBg;
    }

    /** Как у нативных линий между строками (SWT {@code getSlightlyDifferentBackgroundColor}). */
    private static Color tableGridLineColor(Color base)
    {
        RGB rgb = base.getRGB();
        int offset = 8;
        int r = rgb.red > 127 ? rgb.red - offset : rgb.red + offset;
        int g = rgb.green > 127 ? rgb.green - offset : rgb.green + offset;
        int b = rgb.blue > 127 ? rgb.blue - offset : rgb.blue + offset;
        return new Color(base.getDevice(), clampChannel(r), clampChannel(g), clampChannel(b));
    }

    private static Color slightlyDarker(Color base, double factor)
    {
        Device device = base.getDevice();
        RGB rgb = base.getRGB();
        int r = clampChannel((int) (rgb.red * (1.0 - factor)));
        int g = clampChannel((int) (rgb.green * (1.0 - factor)));
        int b = clampChannel((int) (rgb.blue * (1.0 - factor)));
        return new Color(device, r, g, b);
    }

    private static int clampChannel(int value)
    {
        return Math.max(0, Math.min(255, value));
    }

    private void invalidateHighlightColor()
    {
        if (ownedRowBg != null && !ownedRowBg.isDisposed())
            ownedRowBg.dispose();
        if (ownedInactiveRowBg != null && !ownedInactiveRowBg.isDisposed())
            ownedInactiveRowBg.dispose();
        if (ownedActiveCellBg != null && !ownedActiveCellBg.isDisposed())
            ownedActiveCellBg.dispose();
        if (ownedHeaderAccentBg != null && !ownedHeaderAccentBg.isDisposed())
            ownedHeaderAccentBg.dispose();
        if (ownedHeaderSeparatorBg != null && !ownedHeaderSeparatorBg.isDisposed())
            ownedHeaderSeparatorBg.dispose();
        ownedRowBg = null;
        ownedInactiveRowBg = null;
        ownedActiveCellBg = null;
        ownedHeaderAccentBg = null;
        ownedHeaderSeparatorBg = null;
    }

    private void disposeColors()
    {
        invalidateHighlightColor();
    }

}
