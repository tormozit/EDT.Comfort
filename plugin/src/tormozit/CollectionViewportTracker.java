package tormozit;

import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;

/**
 * Горизонтальный viewport колонок таблицы «Коллекция».
 */
final class CollectionViewportTracker
{
    private CollectionViewportTracker() {}

    static int firstVisibleColumn(Table table)
    {
        if (table == null || table.isDisposed())
            return 0;
        int count = table.getColumnCount();
        if (count <= 0)
            return 0;
        ScrollBar bar = table.getHorizontalBar();
        int scrollX = bar != null ? bar.getSelection() : 0;
        int x = 0;
        for (int i = 0; i < count; i++)
        {
            TableColumn col = table.getColumn(i);
            int w = col.getWidth();
            if (x + w > scrollX)
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
        ScrollBar bar = table.getHorizontalBar();
        int scrollX = bar != null ? bar.getSelection() : 0;
        int clientW = table.getClientArea().width;
        int x = 0;
        int last = count - 1;
        for (int i = 0; i < count; i++)
        {
            TableColumn col = table.getColumn(i);
            int w = col.getWidth();
            if (x >= scrollX + clientW)
                return Math.max(0, i - 1);
            last = i;
            x += w;
        }
        return last;
    }
}
