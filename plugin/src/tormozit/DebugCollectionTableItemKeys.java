package tormozit;

import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Кэш displayIndex / logicalRow на {@link TableItem} — без {@code table.indexOf} в hot path.
 */
final class DebugCollectionTableItemKeys
{
    static final String DISPLAY_INDEX = "tormozit.displayIndex"; //$NON-NLS-1$
    static final String LOGICAL_ROW = "tormozit.logicalRow"; //$NON-NLS-1$

    private DebugCollectionTableItemKeys() {}

    static void bindRow(TableItem item, int displayIndex, int logicalRow)
    {
        if (item == null)
            return;
        item.setData(DISPLAY_INDEX, displayIndex);
        item.setData(LOGICAL_ROW, logicalRow);
    }

    static int logicalRow(TableItem item)
    {
        if (item == null)
            return -1;
        Object data = item.getData(LOGICAL_ROW);
        if (data instanceof Integer logical)
            return logical;
        return -1;
    }

    static int displayIndex(TableItem item, Table table)
    {
        if (item == null)
            return -1;
        Object data = item.getData(DISPLAY_INDEX);
        if (data instanceof Integer display)
            return display;
        if (table != null && !table.isDisposed())
            return table.indexOf(item);
        return -1;
    }
}
