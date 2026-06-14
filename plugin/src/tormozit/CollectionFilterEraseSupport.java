package tormozit;

import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * Подсветка smart-фильтра в {@link org.eclipse.swt.SWT#EraseItem} без O(n) lookup.
 */
final class CollectionFilterEraseSupport
{
    private CollectionFilterEraseSupport() {}

    static void handleEraseItem(
        Table table,
        TableItem item,
        Event e,
        SmartMatcher matcher,
        TableItem skipSelectionItem,
        CollectionTableHost host)
    {
        handleEraseItem(table, item, e, matcher, skipSelectionItem, host, 0);
    }

    static void handleEraseItem(
        Table table,
        TableItem item,
        Event e,
        SmartMatcher matcher,
        TableItem skipSelectionItem,
        CollectionTableHost host,
        int visibleColumnOffset)
    {
        if (matcher == null || matcher.isEmpty || host == null)
            return;
        if (item == skipSelectionItem)
            return;

        int logical = CollectionTableItemKeys.logicalRow(item);
        if (logical < 0)
        {
            int displayIndex = CollectionTableItemKeys.displayIndex(item, table);
            logical = host.displayIndexToLogical(displayIndex);
        }
        if (logical < 0 || e.index < 0)
            return;

        int visibleCol = visibleColumnOffset + e.index;
        String text = item.getText(e.index);
        if (text == null || text.isEmpty())
            text = host.getCellDisplayText(logical, visibleCol);
        CollectionFilterCellHighlight.paintEraseBackground(e, table, item, text, matcher);
    }
}
