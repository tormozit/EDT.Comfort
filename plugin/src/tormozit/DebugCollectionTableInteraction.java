package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolTip;

/**
 * Выделение строки + активной ячейки и Ctrl+C для таблицы «Коллекция».
 */
final class DebugCollectionTableInteraction
{
    private static final int TOOLTIP_DEBOUNCE_MS = 50;

    private final Table table;
    private final DebugCollectionTableHost host;
    private final FormTableInteraction interaction;

    private ToolTip cellToolTip;
    private Listener mouseMoveListener;
    private Listener mouseExitListener;
    private Runnable pendingTooltipUpdate;
    private int lastTooltipLogicalRow = -1;
    private int lastTooltipVisibleCol = -1;

    DebugCollectionTableInteraction(Table table, DebugCollectionTableHost host)
    {
        this.table = table;
        this.host = host;
        this.interaction = new FormTableInteraction(table, this::cellText);
        interaction.setColumnReorderEnabled(false);
        interaction.setCopyHook(this::logCopy);
    }

    void install()
    {
        interaction.install();
        installCellToolTip();
    }

    void dispose()
    {
        removeCellToolTip();
        interaction.dispose();
    }

    void selectCell(TableItem item, int column)
    {
        interaction.selectCell(item, column);
    }

    int activeColumn()
    {
        return interaction.activeColumn();
    }

    /** Индекс видимой колонки модели (с учётом split-table). */
    int modelVisibleColumn()
    {
        return host.firstVisibleColumnIndex(table) + Math.max(0, interaction.activeColumn());
    }

    TableItem selectedItem()
    {
        return interaction.selectedItem();
    }

    private String cellText(TableItem item, int column)
    {
        int displayIndex = DebugCollectionTableItemKeys.displayIndex(item, table);
        int logical = host.displayIndexToLogical(displayIndex);
        if (logical < 0)
            return ""; //$NON-NLS-1$
        String text = host.getCellDisplayText(logical, host.firstVisibleColumnIndex(table) + column);
        return text != null ? text : ""; //$NON-NLS-1$
    }

    private void logCopy()
    {
        TableItem item = interaction.selectedItem();
        if (item == null || item.isDisposed())
            return;
        int displayIndex = DebugCollectionTableItemKeys.displayIndex(item, table);
        int logical = host.displayIndexToLogical(displayIndex);
        DebugCollectionDebug.step("copy", "row=" + logical + " col=" + interaction.activeColumn()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void installCellToolTip()
    {
        Shell shell = table.getShell();
        if (shell == null || shell.isDisposed())
            return;
        cellToolTip = new ToolTip(shell, SWT.NONE);
        cellToolTip.setAutoHide(true);
        mouseMoveListener = this::onMouseMove;
        mouseExitListener = this::onMouseExit;
        table.addListener(SWT.MouseMove, mouseMoveListener);
        table.addListener(SWT.MouseExit, mouseExitListener);
    }

    private void removeCellToolTip()
    {
        if (pendingTooltipUpdate != null && !table.isDisposed())
        {
            table.getDisplay().timerExec(-1, pendingTooltipUpdate);
            pendingTooltipUpdate = null;
        }
        if (!table.isDisposed())
        {
            if (mouseMoveListener != null)
                table.removeListener(SWT.MouseMove, mouseMoveListener);
            if (mouseExitListener != null)
                table.removeListener(SWT.MouseExit, mouseExitListener);
        }
        hideCellToolTip();
        if (cellToolTip != null && !cellToolTip.isDisposed())
            cellToolTip.dispose();
        cellToolTip = null;
        mouseMoveListener = null;
        mouseExitListener = null;
        lastTooltipLogicalRow = -1;
        lastTooltipVisibleCol = -1;
    }

    private void onMouseExit(Event event)
    {
        hideCellToolTip();
        lastTooltipLogicalRow = -1;
        lastTooltipVisibleCol = -1;
    }

    private void onMouseMove(Event event)
    {
        if (table.isDisposed() || cellToolTip == null || cellToolTip.isDisposed())
            return;
        TableItem item = table.getItem(new Point(event.x, event.y));
        if (item == null)
        {
            hideCellToolTip();
            lastTooltipLogicalRow = -1;
            lastTooltipVisibleCol = -1;
            return;
        }
        int col = columnAt(event.x, event.y, item);
        if (col < 0)
        {
            hideCellToolTip();
            return;
        }
        int displayIndex = DebugCollectionTableItemKeys.displayIndex(item, table);
        int logical = host.displayIndexToLogical(displayIndex);
        int visibleCol = host.firstVisibleColumnIndex(table) + col;
        if (logical < 0)
        {
            hideCellToolTip();
            return;
        }
        if (logical == lastTooltipLogicalRow && visibleCol == lastTooltipVisibleCol)
            return;
        lastTooltipLogicalRow = logical;
        lastTooltipVisibleCol = visibleCol;
        if (pendingTooltipUpdate != null)
            table.getDisplay().timerExec(-1, pendingTooltipUpdate);
        final int tooltipLogical = logical;
        final int tooltipVisibleCol = visibleCol;
        final TableItem tooltipItem = item;
        final int tooltipCol = col;
        pendingTooltipUpdate = () -> {
            pendingTooltipUpdate = null;
            if (table.isDisposed() || cellToolTip == null || cellToolTip.isDisposed()
                || tooltipItem.isDisposed())
                return;
            String tip = host.getCellHoverToolTip(tooltipLogical, tooltipVisibleCol);
            if (tip == null || tip.isEmpty())
            {
                hideCellToolTip();
                return;
            }
            cellToolTip.setText(tip);
            Rectangle bounds = tooltipItem.getBounds(tooltipCol);
            Point loc = table.toDisplay(bounds.x, bounds.y + bounds.height);
            cellToolTip.setLocation(loc.x, loc.y);
            cellToolTip.setVisible(true);
        };
        table.getDisplay().timerExec(TOOLTIP_DEBOUNCE_MS, pendingTooltipUpdate);
    }

    private void hideCellToolTip()
    {
        if (cellToolTip != null && !cellToolTip.isDisposed())
            cellToolTip.setVisible(false);
    }

    private static int columnAt(int x, int y, TableItem item)
    {
        if (item == null)
            return -1;
        Table parent = item.getParent();
        for (int i = 0; i < parent.getColumnCount(); i++)
        {
            if (item.getBounds(i).contains(x, y))
                return i;
        }
        return -1;
    }
}
