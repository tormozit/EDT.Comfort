package tormozit;

import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.IMemento;

/**
 * Сохранение и восстановление порядка ({@link Table#getColumnOrder()}) и ширин колонок таблицы —
 * вместе, одним вызовом на каждый источник хранения ({@link IDialogSettings} / {@link IMemento}).
 *
 * <p>Опциональный флаг режима заполнения по ширине (см. {@link FormTableInteraction#install(boolean)},
 * {@link FormTableInteraction#isColumnsExactFill()}) приоритетнее сохранённых пиксельных ширин: если при
 * закрытии окна режим заполнения был активен, сохранённые ширины не считаются «пользовательской»
 * настройкой — они почти наверняка не совпадут впритык с шириной таблицы при следующем открытии (другой
 * размер окна и т.п.), важен сам факт «был режим заполнения», а не конкретные старые пиксели. Актуален
 * только для потребителей {@link FormTableInteraction}; для остальных (напр. weight-based колонки,
 * см. {@code MdObjectPickDialog}) используются варианты без флага.
 */
final class FormTableColumnState
{
    private FormTableColumnState()
    {
    }

    // ---- порядок колонок ----

    static void loadOrder(IDialogSettings settings, String key, Table table)
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

    static void loadOrder(IMemento memento, String key, Table table)
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

    static void saveOrder(IDialogSettings settings, String key, Table table)
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

    // ---- ширины колонок ----

    static int readWidth(IDialogSettings settings, String key, int defaultWidth, int minWidth)
    {
        if (settings == null || key == null)
            return defaultWidth;
        String raw = settings.get(key);
        if (raw == null || raw.isBlank())
            return defaultWidth;
        try
        {
            int w = Integer.parseInt(raw.trim());
            return w >= minWidth ? w : defaultWidth;
        }
        catch (NumberFormatException ex)
        {
            return defaultWidth;
        }
    }

    /**
     * Есть ли смысл передавать {@code true} в {@link FormTableInteraction#install(boolean)}: не в режиме
     * заполнения на момент последнего закрытия, и хотя бы одна из ширин реально была сохранена.
     */
    static boolean hasSavedColumnWidths(IDialogSettings settings, String fillModeKey, String... widthKeys)
    {
        if (settings == null)
            return false;
        if (settings.getBoolean(fillModeKey))
            return false;
        for (String key : widthKeys)
            if (settings.get(key) != null)
                return true;
        return false;
    }

    // ---- порядок + ширины вместе ----

    /**
     * Сохранить ширины колонок и их порядок одним вызовом (без флага режима заполнения — для
     * потребителей без {@link FormTableInteraction#isColumnsExactFill()}). {@code widthKeys.length ==
     * columns.length}.
     */
    static void saveOrderAndWidths(IDialogSettings settings, String orderKey,
        String[] widthKeys, TableColumn[] columns, Table table)
    {
        if (settings == null || widthKeys == null || columns == null || widthKeys.length != columns.length)
            return;
        for (int i = 0; i < columns.length; i++)
        {
            TableColumn c = columns[i];
            if (c != null && !c.isDisposed())
                settings.put(widthKeys[i], Integer.toString(c.getWidth()));
        }
        saveOrder(settings, orderKey, table);
    }

    /**
     * Вариант с уже вычисленными ширинами (например, live-значение с fallback на кэш при временно
     * недоступной колонке) вместо прямого {@code column.getWidth()} — ширина не пишется, если {@code
     * widths[i] <= 0} (нет валидного значения). {@code widthKeys.length == widths.length}.
     */
    static void saveOrderAndWidths(IDialogSettings settings, String orderKey,
        String[] widthKeys, int[] widths, Table table)
    {
        if (settings == null || widthKeys == null || widths == null || widthKeys.length != widths.length)
            return;
        for (int i = 0; i < widths.length; i++)
            if (widths[i] > 0)
                settings.put(widthKeys[i], Integer.toString(widths[i]));
        saveOrder(settings, orderKey, table);
    }

    /**
     * То же самое, но с флагом режима заполнения (для потребителей {@link FormTableInteraction}) —
     * приоритетнее сохранённых пиксельных ширин, см. класс-javadoc.
     */
    static void saveOrderAndWidths(IDialogSettings settings, String orderKey,
        String fillModeKey, boolean fillMode, String[] widthKeys, TableColumn[] columns, Table table)
    {
        if (settings == null || widthKeys == null || columns == null || widthKeys.length != columns.length)
            return;
        settings.put(fillModeKey, fillMode);
        for (int i = 0; i < columns.length; i++)
        {
            TableColumn c = columns[i];
            if (c != null && !c.isDisposed())
                settings.put(widthKeys[i], Integer.toString(c.getWidth()));
        }
        saveOrder(settings, orderKey, table);
    }

    /** То же самое, но с флагом режима заполнения, для варианта с уже вычисленными ширинами ({@code int[]}). */
    static void saveOrderAndWidths(IDialogSettings settings, String orderKey,
        String fillModeKey, boolean fillMode, String[] widthKeys, int[] widths, Table table)
    {
        if (settings == null || widthKeys == null || widths == null || widthKeys.length != widths.length)
            return;
        settings.put(fillModeKey, fillMode);
        for (int i = 0; i < widths.length; i++)
            if (widths[i] > 0)
                settings.put(widthKeys[i], Integer.toString(widths[i]));
        saveOrder(settings, orderKey, table);
    }

    /** Вариант для записи в {@link IMemento} (workbench state) — тоже одним вызовом, без флага режима. */
    static void writeOrderAndWidthsToMemento(IMemento memento, String orderKey,
        String[] widthKeys, int[] widths, Table table)
    {
        if (memento == null || widthKeys == null || widths == null || widthKeys.length != widths.length)
            return;
        for (int i = 0; i < widthKeys.length; i++)
            if (widths[i] > 0)
                memento.putString(widthKeys[i], Integer.toString(widths[i]));
        if (table != null && !table.isDisposed() && table.getColumnCount() > 0)
            memento.putString(orderKey, formatOrder(table));
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
