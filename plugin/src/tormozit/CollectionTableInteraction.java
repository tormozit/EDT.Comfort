package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Выделение строки + активной ячейки и Ctrl+C для таблицы «Коллекция».
 */
final class CollectionTableInteraction
{
    private final Table table;
    private final CollectionTableHost host;
    private final int firstVisibleColumn;

    private TableItem selectedItem;
    private int activeColumn = -1;
    private Color ownedRowBg;
    private Color ownedActiveCellBg;

    private Listener eraseItemListener;
    private Listener paintItemListener;
    private Listener focusListener;
    private Listener keyFilter;
    private MouseAdapter mouseListener;

    CollectionTableInteraction(Table table, CollectionTableHost host)
    {
        this(table, host, 0);
    }

    CollectionTableInteraction(Table table, CollectionTableHost host, int firstVisibleColumn)
    {
        this.table = table;
        this.host = host;
        this.firstVisibleColumn = Math.max(0, firstVisibleColumn);
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
        };
        table.addListener(SWT.FocusIn, focusListener);
        table.addListener(SWT.FocusOut, focusListener);

        keyFilter = this::onKeyFilter;
        table.getDisplay().addFilter(SWT.KeyDown, keyFilter);
        table.addDisposeListener(e -> dispose());
    }

    void dispose()
    {
        if (keyFilter != null && table != null && !table.isDisposed())
            table.getDisplay().removeFilter(SWT.KeyDown, keyFilter);
        disposeColors();
    }

    void selectCell(TableItem item, int column)
    {
        if (item == null || item.isDisposed())
            return;
        TableItem previous = selectedItem;
        selectedItem = item;
        activeColumn = column;
        table.setSelection(item);
        redrawRow(previous);
        redrawRow(selectedItem);
    }

    int activeColumn()
    {
        return activeColumn;
    }

    /** Индекс видимой колонки модели (с учётом split-table). */
    int modelVisibleColumn()
    {
        return firstVisibleColumn + Math.max(0, activeColumn);
    }

    TableItem selectedItem()
    {
        return selectedItem;
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

    private void copyActiveCell()
    {
        if (selectedItem == null || selectedItem.isDisposed() || activeColumn < 0)
            return;
        int displayIndex = CollectionTableItemKeys.displayIndex(selectedItem, table);
        int logical = host.displayIndexToLogical(displayIndex);
        if (logical < 0)
            return;
        String text = host.getCellDisplayText(logical, firstVisibleColumn + activeColumn);
        if (text == null)
            text = ""; //$NON-NLS-1$
        Clipboard clipboard = new Clipboard(table.getDisplay());
        clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        clipboard.dispose();
        ComfortCollectionDebug.step("copy", "row=" + logical + " col=" + activeColumn); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void onEraseItem(Event e)
    {
        if (!(e.item instanceof TableItem item) || item != currentSelectedRow())
            return;
        Color rowBg = rowSelectionBackground();
        Color bg = e.index == activeColumn ? activeCellBackground(rowBg) : rowBg;
        e.gc.setBackground(bg);
        e.gc.fillRectangle(e.x, e.y, e.width, e.height);
        e.detail &= ~SWT.BACKGROUND;
    }

    private void onPaintItem(Event e)
    {
        if (!(e.item instanceof TableItem item) || item != currentSelectedRow() || e.index != activeColumn)
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
        ownedRowBg = null;
        ownedActiveCellBg = null;
    }

    private void disposeColors()
    {
        invalidateHighlightColor();
    }
}
