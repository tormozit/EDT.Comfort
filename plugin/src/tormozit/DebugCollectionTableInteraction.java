package tormozit;

import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Выделение строки + активной ячейки и Ctrl+C для таблицы «Коллекция».
 */
final class DebugCollectionTableInteraction
{
    private final Table table;
    private final DebugCollectionTableHost host;
    private final FormTableInteraction interaction;

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
    }

    void dispose()
    {
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
}
