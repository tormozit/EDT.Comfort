package tormozit;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.IMemento;

/**
 * Сохранение и восстановление порядка колонок таблицы ({@link Table#getColumnOrder()}).
 */
final class FormTableColumnOrder
{
    private FormTableColumnOrder()
    {
    }

    static void load(IDialogSettings settings, String key, Table table)
    {
        if (settings == null || key == null || table == null || table.isDisposed())
            return;
        int count = table.getColumnCount();
        if (count <= 0)
            return;
        int[] order = parseOrder(settings.get(key), count);
        if (order != null)
            table.setColumnOrder(order);
    }

    static void load(IMemento memento, String key, Table table)
    {
        if (memento == null || key == null || table == null || table.isDisposed())
            return;
        int count = table.getColumnCount();
        if (count <= 0)
            return;
        int[] order = parseOrder(memento.getString(key), count);
        if (order != null)
            table.setColumnOrder(order);
    }

    static void save(IDialogSettings settings, String key, Table table)
    {
        if (settings == null || key == null || table == null || table.isDisposed())
            return;
        if (table.getColumnCount() <= 0)
            return;
        settings.put(key, formatOrder(table.getColumnOrder()));
    }

    static String formatOrder(Table table)
    {
        if (table == null || table.isDisposed() || table.getColumnCount() <= 0)
            return ""; //$NON-NLS-1$
        return formatOrder(table.getColumnOrder());
    }

    private static int[] parseOrder(String raw, int columnCount)
    {
        if (raw == null || raw.isBlank() || columnCount <= 0)
            return null;
        String[] parts = raw.split(","); //$NON-NLS-1$
        if (parts.length != columnCount)
            return null;
        boolean[] seen = new boolean[columnCount];
        int[] order = new int[columnCount];
        for (int i = 0; i < columnCount; i++)
        {
            String part = parts[i].trim();
            if (part.isEmpty())
                return null;
            int index;
            try
            {
                index = Integer.parseInt(part);
            }
            catch (NumberFormatException ex)
            {
                return null;
            }
            if (index < 0 || index >= columnCount || seen[index])
                return null;
            seen[index] = true;
            order[i] = index;
        }
        return order;
    }

    private static String formatOrder(int[] order)
    {
        if (order == null || order.length == 0)
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < order.length; i++)
        {
            if (i > 0)
                sb.append(',');
            sb.append(order[i]);
        }
        return sb.toString();
    }
}
