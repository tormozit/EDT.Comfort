package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerCell;
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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Единое поведение таблиц в формах плагина: выбор ячейки, копирование,
 * подсветка строки, активной ячейки и заголовка колонки.
 */
final class FormTableInteraction
{
    private static final String COPY_MENU_KEY = "tormozit.formTableCopyMenu"; //$NON-NLS-1$
    private static final String COLUMN_HEADER_KEY = "tormozit.formTableColHeader"; //$NON-NLS-1$
    private static final String AUTO_HEADER_TOOLTIP_KEY = "tormozit.formTableColAutoHeaderTip"; //$NON-NLS-1$
    private static final int HEADER_ACCENT_HEIGHT = 2;
    private static final int HEADER_SEPARATOR_HEIGHT = 1;
    /** Горизонтальный запас шапки Win32 (отступы без sort-иконки). */
    private static final int HEADER_TEXT_INSET = 16;

    @FunctionalInterface
    interface FormTableCellAccess
    {
        /** Отображаемый текст ячейки; {@code null} → пустая строка. */
        String cellText(TableItem item, int column);
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
    private TableColumn[] ownerDrawColumns = NO_OWNER_DRAW_COLUMNS;

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
        Global.tempLog("search-copy-dispatch", "resolveCellText: column=" + column
            + " element=" + (item.getData() != null ? item.getData().getClass().getSimpleName() : "null")
            + " result=" + logShort(result));
        return result;
    }

    private static String logShort(String s)
    {
        if (s == null)
            return "null";
        return s.length() > 120 ? s.substring(0, 120) + "…(" + s.length() + ")" : s;
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
            Global.tempLog("search-copy-dispatch", "textFromColumnLabelProvider: getLabelProvider(" + column + ") threw "
                + ignored);
            return null;
        }
        Global.tempLog("search-copy-dispatch", "textFromColumnLabelProvider: column=" + column
            + " provider=" + (cellLp != null ? cellLp.getClass().getSimpleName() : "null"));
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

    void install()
    {
        if (table == null || table.isDisposed())
            return;

        ListSelectionThemeColors.markOptOut(table);

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

        menuDetectListener = this::onMenuDetect;
        table.addListener(SWT.MenuDetect, menuDetectListener);

        keyFilter = this::onKeyFilter;
        table.getDisplay().addFilter(SWT.KeyDown, keyFilter);
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
        table.addDisposeListener(e -> dispose());
    }

    void dispose()
    {
        if (keyFilter != null && table != null && !table.isDisposed())
            table.getDisplay().removeFilter(SWT.KeyDown, keyFilter);
        uninstallHeaderOverlays();
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
        if (!table.isDisposed() && ListSelectionThemeColors.isDarkList(table))
        {
            for (TableItem item : table.getItems())
                syncCellBackgrounds(item);
        }
        redrawSelectedRows();
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
            applyViewerSelection(new StructuredSelection(data));
        else
            table.setSelection(item);
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
        Global.tempLog("search-copy-dispatch", "activeCellText: itemData=" + (item != null ? item.getData() : "null")
            + " activeColumnIndex=" + idx + " selectedItemLive=" + (selectedItem != null && !selectedItem.isDisposed()));
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
        if (!(e.item instanceof TableItem item) || !isRowSelected(item))
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
        if (!isRowSelected(item))
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

    private Rectangle columnHeaderBounds(int column)
    {
        if (column < 0 || column >= table.getColumnCount())
            return null;
        int x = 0;
        for (int i = 0; i < column; i++)
            x += table.getColumn(i).getWidth();
        ScrollBar horizontal = table.getHorizontalBar();
        if (horizontal != null)
            x -= horizontal.getSelection();
        int width = table.getColumn(column).getWidth();
        int height = table.getHeaderHeight();
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
