package tormozit;

import java.util.function.IntUnaryOperator;

import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Сохранение позиции таблицы по logicalRow при снятии фильтра.
 */
final class CollectionFilterViewportAnchor
{
    private CollectionFilterViewportAnchor() {}

    static int captureLogicalRow(
        Table table,
        CollectionTableInteraction interaction,
        IntUnaryOperator displayToLogical)
    {
        if (table == null || table.isDisposed() || displayToLogical == null)
            return -1;

        if (interaction != null)
        {
            TableItem selected = interaction.selectedItem();
            if (selected != null && !selected.isDisposed())
            {
                int logical = CollectionTableItemKeys.logicalRow(selected);
                if (logical >= 0)
                    return logical;
                int display = CollectionTableItemKeys.displayIndex(selected, table);
                logical = displayToLogical.applyAsInt(display);
                if (logical >= 0)
                    return logical;
            }
        }

        int top = table.getTopIndex();
        if (top >= 0)
            return displayToLogical.applyAsInt(top);
        return -1;
    }

    static void restoreLogicalRow(
        Table table,
        int logicalRow,
        int totalRows,
        CollectionTableInteraction interaction)
    {
        if (table == null || table.isDisposed() || logicalRow < 0 || totalRows <= 0)
            return;

        int row = Math.min(logicalRow, totalRows - 1);
        if (table.getItemCount() <= row)
            return;

        table.setTopIndex(row);

        TableItem item = table.getItem(row);
        if (item != null && interaction != null)
        {
            int column = Math.max(0, interaction.activeColumn());
            interaction.selectCell(item, column);
        }
        else
            table.setSelection(row);
    }
}
