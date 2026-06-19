package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
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

    private final Table table;
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

    private Listener eraseItemListener;
    private Listener paintItemListener;
    private Listener focusListener;
    private Listener selectionListener;
    private Listener menuDetectListener;
    private Listener keyFilter;
    private Listener mouseDownListener;

    FormTableInteraction(Table table, FormTableCellAccess cellAccess)
    {
        this.table = table;
        this.cellAccess = cellAccess;
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

    void setColumnReorderEnabled(boolean columnReorderEnabled)
    {
        this.columnReorderEnabled = columnReorderEnabled;
    }

    void install()
    {
        if (table == null || table.isDisposed())
            return;

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
        table.addDisposeListener(e -> dispose());
    }

    void dispose()
    {
        if (keyFilter != null && table != null && !table.isDisposed())
            table.getDisplay().removeFilter(SWT.KeyDown, keyFilter);
        uninstallHeaderOverlays();
        disposeColors();
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
        TableItem item = selectedItem != null && !selectedItem.isDisposed()
            ? selectedItem
            : currentSelectedRow();
        if (item == null || item.isDisposed() || activeColumnIndex() < 0)
            return;
        String text = cellAccess.cellText(item, activeColumnIndex());
        if (text == null)
            text = ""; //$NON-NLS-1$
        Clipboard clipboard = new Clipboard(table.getDisplay());
        clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        clipboard.dispose();
        if (copyHook != null)
            copyHook.run();
    }

    private void onEraseItem(Event e)
    {
        if (!(e.item instanceof TableItem item) || !isRowSelected(item))
            return;
        int col = activeColumnIndex();
        boolean activeRow = item == selectedItem;
        Color rowBg = activeRow ? rowSelectionBackground() : inactiveRowSelectionBackground();
        Color bg = activeRow && e.index == col ? activeCellBackground(rowBg) : rowBg;
        e.gc.setBackground(bg);
        e.gc.fillRectangle(e.x, e.y, e.width, e.height);
        e.detail &= ~SWT.BACKGROUND;
    }

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

    private void installHeaderOverlays()
    {
        if (!resolveOverlayRoot())
            return;

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
                updateHeaderOverlays();
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
                    updateHeaderOverlays();
                }
            };
            horizontal.addSelectionListener(horizontalScrollListener);
        }

        table.getDisplay().asyncExec(this::updateHeaderOverlays);
        installColumnHeaderListeners();
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
                    updateHeaderOverlays();
                }

                @Override
                public void controlMoved(ControlEvent e)
                {
                    updateHeaderOverlays();
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
        if (tableParent.getLayout() instanceof org.eclipse.jface.layout.TableColumnLayout)
        {
            columnHost = tableParent;
            overlayRoot = tableParent.getParent();
            if (overlayRoot == null || overlayRoot.isDisposed())
                return false;
            if (overlayRoot.getLayout() != null)
                return false;
            installColumnHostBoundsMaintainer();
        }
        else if (overlayRoot.getLayout() != null)
        {
            return false;
        }
        return true;
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
                updateHeaderOverlays();
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

    private boolean isRowSelected(TableItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        if (useViewerForMultiSelect())
        {
            Object data = item.getData();
            if (data == null)
                return false;
            return multiSelectViewer.getStructuredSelection().toList().contains(data);
        }
        for (TableItem s : table.getSelection())
        {
            if (s == item)
                return true;
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
        Rectangle bounds = rowBounds(item);
        if (bounds == null || bounds.isEmpty())
            return;
        table.redraw(bounds.x, bounds.y, bounds.width, bounds.height, false);
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
        updateHeaderOverlays();
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
        Color base = table.getBackground();
        if (base == null || base.isDisposed())
            base = table.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        double factor = table.isFocusControl() ? 0.12 : 0.08;
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
        Color base = table.getBackground();
        if (base == null || base.isDisposed())
            base = table.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        double factor = table.isFocusControl() ? 0.045 : 0.03;
        ownedInactiveRowBg = slightlyDarker(base, factor);
        return ownedInactiveRowBg;
    }

    private Color activeCellBackground(Color rowBg)
    {
        if (ownedActiveCellBg != null && !ownedActiveCellBg.isDisposed())
            return ownedActiveCellBg;
        ownedActiveCellBg = slightlyDarker(rowBg, table.isFocusControl() ? 0.08 : 0.06);
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
