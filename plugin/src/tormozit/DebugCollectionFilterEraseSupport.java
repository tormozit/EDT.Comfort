package tormozit;

import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import java.util.List;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.SWT;

/**
 * Подсветка smart-фильтра в {@link org.eclipse.swt.SWT#EraseItem} без O(n) lookup.
 */
final class DebugCollectionFilterEraseSupport
{
    private DebugCollectionFilterEraseSupport() {}

    static void handleEraseItem(
        Table table,
        TableItem item,
        Event e,
        SmartMatcher matcher,
        TableItem skipSelectionItem,
        DebugCollectionTableHost host)
    {
        handleEraseItem(table, item, e, matcher, skipSelectionItem, host, 0);
    }

    static void handleEraseItem(
        Table table,
        TableItem item,
        Event e,
        SmartMatcher matcher,
        TableItem skipSelectionItem,
        DebugCollectionTableHost host,
        int visibleColumnOffset)
    {
        if (matcher == null || matcher.isEmpty || host == null)
            return;
        if (item == skipSelectionItem)
            return;

        int logical = DebugCollectionTableItemKeys.logicalRow(item);
        if (logical < 0)
        {
            int displayIndex = DebugCollectionTableItemKeys.displayIndex(item, table);
            logical = host.displayIndexToLogical(displayIndex);
        }
        if (logical < 0 || e.index < 0)
            return;

        int visibleCol = visibleColumnOffset + e.index;
        String text = item.getText(e.index);
        if (text == null || text.isEmpty())
            text = host.getCellDisplayText(logical, visibleCol);
        DebugCollectionFilterCellHighlight.paintEraseBackground(e, table, item, text, matcher);
    }

    /**
     * Подсветка smart-фильтра — жёлтый фон под совпадением в {@link SWT#EraseItem} (до отрисовки текста).
     */
    private static final class DebugCollectionFilterCellHighlight
    {
        private static final int HIGHLIGHT_R = 255;
        private static final int HIGHLIGHT_G = 255;
        private static final int HIGHLIGHT_B = 160;
        /** Win32 LVM: отступ текста ячейки от левого края subitem. */
        private static final int TABLE_CELL_TEXT_MARGIN = 6;

        private DebugCollectionFilterCellHighlight() {}

        static void paintEraseBackground(Event e, Table table, TableItem item, String text, SmartMatcher matcher)
        {
            if (e == null || e.gc == null || table == null || table.isDisposed())
                return;

            GC gc = e.gc;
            Color prevBg = gc.getBackground();
            Color listBg = table.getBackground();
            if (listBg == null || listBg.isDisposed())
                listBg = table.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
            gc.setBackground(listBg);
            gc.fillRectangle(e.x, e.y, e.width, e.height);

            if (text != null && !text.isEmpty() && matcher != null && !matcher.isEmpty && matcher.matches(text))
            {
                List<SmartMatcher.HighlightRange> ranges = matcher.getHighlightRanges(text);
                Color highlight = new Color(gc.getDevice(), HIGHLIGHT_R, HIGHLIGHT_G, HIGHLIGHT_B);
                Font prevFont = gc.getFont();
                Font tableFont = table.getFont();
                if (tableFont != null && !tableFont.isDisposed())
                    gc.setFont(tableFont);
                int textOriginX = cellTextOriginX(table, item, e.index, e.x);
                try
                {
                    gc.setBackground(highlight);
                    for (SmartMatcher.HighlightRange range : ranges)
                    {
                        if (range.offset < 0 || range.length <= 0 || range.offset + range.length > text.length())
                            continue;
                        String prefix = text.substring(0, range.offset);
                        String match = text.substring(range.offset, range.offset + range.length);
                        int x = textOriginX + gc.textExtent(prefix, SWT.DRAW_TRANSPARENT | SWT.DRAW_DELIMITER).x;
                        int w = gc.textExtent(match, SWT.DRAW_TRANSPARENT | SWT.DRAW_DELIMITER).x;
                        gc.fillRectangle(x, e.y + 1, w, Math.max(1, e.height - 2));
                    }
                }
                finally
                {
                    if (!highlight.isDisposed())
                        highlight.dispose();
                    gc.setFont(prevFont);
                }
            }

            gc.setBackground(prevBg);
            e.detail &= ~SWT.BACKGROUND;
        }

        private static int cellTextOriginX(Table table, TableItem item, int columnIndex, int cellX)
        {
            if (item != null && columnIndex >= 0)
            {
                Rectangle imageBounds = item.getImageBounds(columnIndex);
                if (imageBounds != null && !imageBounds.isEmpty())
                    return imageBounds.x + imageBounds.width + 2;
            }
            return cellX + TABLE_CELL_TEXT_MARGIN;
        }
    }

}
