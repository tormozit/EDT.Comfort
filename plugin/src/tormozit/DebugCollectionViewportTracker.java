package tormozit;

import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

/**
 * Горизонтальный viewport колонок таблицы «Коллекция».
 */
final class DebugCollectionViewportTracker
{
    private DebugCollectionViewportTracker() {}

    static int firstVisibleColumn(Table table)
    {
        if (table == null || table.isDisposed())
            return 0;
        int count = table.getColumnCount();
        if (count <= 0)
            return 0;
        int scrollX = horizontalScroll(table);
        int scrollRight = scrollX + clientWidth(table);
        int x = 0;
        for (int i = 0; i < count; i++)
        {
            int w = columnWidth(table, i);
            if (w <= 0)
                continue;
            if (intersectsViewport(x, w, scrollX, scrollRight))
                return i;
            x += w;
        }
        return 0;
    }

    static int lastVisibleColumn(Table table)
    {
        if (table == null || table.isDisposed())
            return 0;
        int count = table.getColumnCount();
        if (count <= 0)
            return 0;
        int scrollX = horizontalScroll(table);
        int scrollRight = scrollX + clientWidth(table);
        int x = 0;
        int last = 0;
        boolean any = false;
        for (int i = 0; i < count; i++)
        {
            int w = columnWidth(table, i);
            if (w <= 0)
                continue;
            if (intersectsViewport(x, w, scrollX, scrollRight))
            {
                last = i;
                any = true;
            }
            x += w;
        }
        return any ? last : count - 1;
    }

    /**
     * Диапазон видимых model-колонок для data-таблицы split-раскладки.
     * data col {@code i} → visible/model col {@code i + 1}; col 0 — «Индекс» в отдельной панели.
     *
     * @return {@code [from, to]} включительно
     */
    static int[] visibleModelColumnRange(Table dataTable, int visibleColumnCount, int fixedColumnCount)
    {
        int maxCol = Math.max(0, visibleColumnCount - 1);
        int fixed = Math.max(1, fixedColumnCount);
        if (maxCol <= 0)
            return new int[] { 0, 0 };
        if (dataTable == null || dataTable.isDisposed() || dataTable.getColumnCount() <= 0)
            return new int[] { fixed, maxCol };

        int clientW = clientWidth(dataTable);
        if (clientW <= 0)
            return new int[] { fixed, maxCol };

        int dataFirst = firstVisibleColumn(dataTable);
        int dataLast = lastVisibleColumn(dataTable);
        int modelFrom = Math.min(maxCol, dataFirst + fixed);
        int modelTo = Math.min(maxCol, dataLast + fixed);
        if (modelTo < modelFrom)
            modelTo = modelFrom;
        return new int[] { modelFrom, modelTo };
    }

    static void scrollColumnIntoView(Table table, int columnIndex)
    {
        if (table == null || table.isDisposed())
            return;
        int count = table.getColumnCount();
        if (columnIndex < 0 || columnIndex >= count)
            return;
        TableColumn column = table.getColumn(columnIndex);
        if (column == null || column.isDisposed())
            return;
        int colWidth = column.getWidth();
        if (colWidth <= 0)
            return;

        table.showColumn(column);

        ScrollBar bar = table.getHorizontalBar();
        if (bar == null || bar.isDisposed())
            return;

        int colLeft = 0;
        for (int i = 0; i < columnIndex; i++)
            colLeft += columnWidth(table, i);

        int scrollX = horizontalScroll(table);
        int clientW = clientWidth(table);
        if (clientW <= 0)
            return;
        int scrollRight = scrollX + clientW;

        int newScroll = scrollX;
        if (colLeft < scrollX)
            newScroll = colLeft;
        else if (colLeft + colWidth > scrollRight)
            newScroll = colLeft + colWidth - clientW;

        int maxScroll = Math.max(bar.getMinimum(), bar.getMaximum() - bar.getThumb() + 1);
        newScroll = Math.max(bar.getMinimum(), Math.min(maxScroll, newScroll));
        if (newScroll != scrollX)
            bar.setSelection(newScroll);
    }

    private static boolean intersectsViewport(int x, int width, int scrollX, int scrollRight)
    {
        return x < scrollRight && x + width > scrollX;
    }

    private static int horizontalScroll(Table table)
    {
        ScrollBar bar = table.getHorizontalBar();
        return bar != null ? bar.getSelection() : 0;
    }

    private static int clientWidth(Table table)
    {
        int clientW = table.getClientArea().width;
        if (clientW > 0)
            return clientW;
        return Math.max(0, table.getBounds().width);
    }

    private static int columnWidth(Table table, int index)
    {
        TableColumn col = table.getColumn(index);
        return col != null ? col.getWidth() : 0;
    }
}
