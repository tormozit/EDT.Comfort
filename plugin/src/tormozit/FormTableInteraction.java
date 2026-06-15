package tormozit;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
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
    private static final int HEADER_ACCENT_HEIGHT = 2;
    private static final int HEADER_SEPARATOR_HEIGHT = 1;

    @FunctionalInterface
    interface FormTableCellAccess
    {
        /** Отображаемый текст ячейки; {@code null} → пустая строка. */
        String cellText(TableItem item, int column);
    }

    private final Table table;
    private final FormTableCellAccess cellAccess;

    private Consumer<TableItem> selectionSync;
    private Runnable copyHook;

    private TableItem selectedItem;
    private TableColumn activeColumnWidget;
    private boolean columnReorderEnabled = true;
    private Color ownedRowBg;
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
    private MouseAdapter mouseListener;

    FormTableInteraction(Table table, FormTableCellAccess cellAccess)
    {
        this.table = table;
        this.cellAccess = cellAccess;
    }

    void setSelectionSync(Consumer<TableItem> selectionSync)
    {
        this.selectionSync = selectionSync;
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

        mouseListener = new MouseAdapter()
        {
            @Override
            public void mouseDown(MouseEvent e)
            {
                if (e.button != 1)
                    return;
                TableItem item = table.getItem(new Point(e.x, e.y));
                if (item == null)
                    return;
                int column = columnAt(e.x, e.y, item);
                selectCell(item, column >= 0 ? column : 0);
            }
        };
        table.addMouseListener(mouseListener);

        eraseItemListener = this::onEraseItem;
        table.addListener(SWT.EraseItem, eraseItemListener);
        paintItemListener = this::onPaintItem;
        table.addListener(SWT.PaintItem, paintItemListener);

        focusListener = e ->
        {
            invalidateHighlightColor();
            redrawSelectedRow();
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
        TableItem previous = selectedItem;
        selectedItem = item;
        activeColumnWidget = columnWidget(column);
        table.setSelection(item);
        syncSelection(item);
        redrawRow(previous);
        redrawRow(selectedItem);
        redrawHeader();
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

    private void onSelection(Event e)
    {
        TableItem row = currentSelectedRow();
        if (row == null)
            return;
        TableItem previous = selectedItem;
        selectedItem = row;
        if (activeColumnWidget == null || activeColumnWidget.isDisposed())
            activeColumnWidget = columnWidget(0);
        redrawRow(previous);
        redrawRow(selectedItem);
        redrawHeader();
        syncSelection(row);
    }

    private void syncSelection(TableItem item)
    {
        if (selectionSync != null && item != null && !item.isDisposed())
            selectionSync.accept(item);
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
            selectCell(item, column >= 0 ? column : 0);
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
        TableItem item = currentSelectedRow();
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
        if (!(e.item instanceof TableItem item) || item != currentSelectedRow())
            return;
        Color rowBg = rowSelectionBackground();
        int col = activeColumnIndex();
        Color bg = e.index == col ? activeCellBackground(rowBg) : rowBg;
        e.gc.setBackground(bg);
        e.gc.fillRectangle(e.x, e.y, e.width, e.height);
        e.detail &= ~SWT.BACKGROUND;
    }

    private void onPaintItem(Event e)
    {
        if (!(e.item instanceof TableItem item) || item != currentSelectedRow() || e.index != activeColumnIndex())
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
                    redrawSelectedRow();
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

    private void redrawSelectedRow()
    {
        redrawRow(currentSelectedRow());
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
        if (ownedActiveCellBg != null && !ownedActiveCellBg.isDisposed())
            ownedActiveCellBg.dispose();
        if (ownedHeaderAccentBg != null && !ownedHeaderAccentBg.isDisposed())
            ownedHeaderAccentBg.dispose();
        if (ownedHeaderSeparatorBg != null && !ownedHeaderSeparatorBg.isDisposed())
            ownedHeaderSeparatorBg.dispose();
        ownedRowBg = null;
        ownedActiveCellBg = null;
        ownedHeaderAccentBg = null;
        ownedHeaderSeparatorBg = null;
    }

    private void disposeColors()
    {
        invalidateHighlightColor();
    }
}
