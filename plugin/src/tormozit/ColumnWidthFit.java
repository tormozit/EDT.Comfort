package tormozit;

import java.util.function.IntPredicate;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;

/**
 * Общий расчёт ширин колонок для контролов с колонками: пропорциональное растягивание на всю ширину
 * клиентской области, пропорциональное сужение под неё и минимальная ширина колонки по шрифту контрола.
 *
 * <p>Сам расчёт от типа контрола не зависит — ему нужны только «число колонок, ширина i-й, можно ли её
 * менять, ширина клиентской области». Но у {@link TableColumn} и {@link TreeColumn} общий предок только
 * {@code Item}, где ширины уже нет, поэтому доступ к колонкам вынесен в {@link Columns} с двумя
 * реализациями ({@link TableColumns}, {@link TreeColumns}).
 *
 * <p>Здесь только арифметика ширин, без политики: РЕШЕНИЕ, растягивать ли сейчас колонки и какое
 * состояние считать «заполнением по ширине», остаётся за потребителем и у каждого своё —
 * {@link FormTableInteraction#autoFillColumns()} поддерживает ранее активный режим заполнения и не
 * включает его самовольно, инспектор отладчика ({@code DebugInspectorTreeEnhancement.ColumnAutoFit})
 * заполняет ширину всегда. Специфика контрола тоже у потребителя: у таблиц — {@code selfAdjusting},
 * синхронизация {@code TableColumnLayout} и подавление собственных событий ресайза колонки.
 */
final class ColumnWidthFit
{
    /** Абсолютный пол минимальной ширины колонки (px) — ниже не сужаем, даже если символ шрифта уже. */
    private static final int MIN_COLUMN_WIDTH_FLOOR_PX = 15;
    /** Минимальная ширина колонки в символах текущего шрифта контрола. */
    private static final int MIN_COLUMN_WIDTH_CHARS = 2;
    /** Горизонтальные отступы текста в ячейке Win32 (слева+справа) — прибавляются к минимуму в символах. */
    private static final int CELL_TEXT_INSET_PX = 8;

    private ColumnWidthFit()
    {
    }

    /** Доступ к колонкам контрола — минимум, нужный расчёту ширин. */
    interface Columns
    {
        int count();

        int width(int index);

        void setWidth(int index, int width);

        /** Можно ли менять ширину колонки (для non-resizable расчёт её не трогает). */
        boolean resizable(int index);

        /**
         * Исключена ли колонка из перераспределения. Скрытую хозяином нулевой шириной колонку трогать
         * нельзя: любой рост (в т.ч. до минимальной ширины) снова сделал бы её видимой.
         */
        boolean excluded(int index);

        int clientWidth();

        Control control();
    }

    /** Колонки {@link Table}. */
    static final class TableColumns implements Columns
    {
        private final Table table;
        private final IntPredicate excluded;

        /**
         * @param excluded признак «колонка скрыта хозяином» (см. {@link Columns#excluded}); {@code null} —
         *            исключённых колонок нет.
         */
        TableColumns(Table table, IntPredicate excluded)
        {
            this.table = table;
            this.excluded = excluded;
        }

        @Override
        public int count()
        {
            return table.isDisposed() ? 0 : table.getColumnCount();
        }

        @Override
        public int width(int index)
        {
            TableColumn column = table.getColumn(index);
            return column == null || column.isDisposed() ? 0 : column.getWidth();
        }

        @Override
        public void setWidth(int index, int width)
        {
            TableColumn column = table.getColumn(index);
            if (column != null && !column.isDisposed())
                column.setWidth(width);
        }

        @Override
        public boolean resizable(int index)
        {
            TableColumn column = table.getColumn(index);
            return column != null && !column.isDisposed() && column.getResizable();
        }

        @Override
        public boolean excluded(int index)
        {
            return excluded != null && excluded.test(index);
        }

        @Override
        public int clientWidth()
        {
            return table.isDisposed() ? 0 : table.getClientArea().width;
        }

        @Override
        public Control control()
        {
            return table;
        }
    }

    /** Колонки {@link Tree}. */
    static final class TreeColumns implements Columns
    {
        private final Tree tree;
        private final IntPredicate excluded;

        /** @param excluded см. {@link TableColumns#TableColumns(Table, IntPredicate)}. */
        TreeColumns(Tree tree, IntPredicate excluded)
        {
            this.tree = tree;
            this.excluded = excluded;
        }

        @Override
        public int count()
        {
            return tree.isDisposed() ? 0 : tree.getColumnCount();
        }

        @Override
        public int width(int index)
        {
            TreeColumn column = tree.getColumn(index);
            return column == null || column.isDisposed() ? 0 : column.getWidth();
        }

        @Override
        public void setWidth(int index, int width)
        {
            TreeColumn column = tree.getColumn(index);
            if (column != null && !column.isDisposed())
                column.setWidth(width);
        }

        @Override
        public boolean resizable(int index)
        {
            TreeColumn column = tree.getColumn(index);
            return column != null && !column.isDisposed() && column.getResizable();
        }

        @Override
        public boolean excluded(int index)
        {
            return excluded != null && excluded.test(index);
        }

        @Override
        public int clientWidth()
        {
            return tree.isDisposed() ? 0 : tree.getClientArea().width;
        }

        @Override
        public Control control()
        {
            return tree;
        }
    }

    /** Суммарная ширина всех колонок (включая non-resizable и скрытые — это фактическая занятая ширина). */
    static int totalWidth(Columns columns)
    {
        int total = 0;
        for (int i = 0, count = columns.count(); i < count; i++)
            total += columns.width(i);
        return total;
    }

    /**
     * Минимальная ширина колонки: {@link #MIN_COLUMN_WIDTH_CHARS} символов текущего шрифта контрола плюс
     * горизонтальные отступы ячейки ({@link #CELL_TEXT_INSET_PX}) — чтобы эти символы реально были видны,
     * а не съедались отступами. Не уже {@link #MIN_COLUMN_WIDTH_FLOOR_PX}.
     */
    static int minColumnWidth(Control control)
    {
        if (control == null || control.isDisposed())
            return MIN_COLUMN_WIDTH_FLOOR_PX;
        GC gc = new GC(control);
        try
        {
            int charsWidth = (int)Math.ceil(
                MIN_COLUMN_WIDTH_CHARS * (double)gc.textExtent("00").x / 2); //$NON-NLS-1$
            return Math.max(MIN_COLUMN_WIDTH_FLOOR_PX, charsWidth + CELL_TEXT_INSET_PX);
        }
        finally
        {
            gc.dispose();
        }
    }

    /**
     * Растянуть колонки на всю ширину клиентской области: свободное место {@code clientWidth - total}
     * делится между resizable-колонками пропорционально их текущим ширинам, остаток округления
     * добавляется последней из них.
     *
     * @return сколько колонок участвовало в растягивании (0 — растягивать было нечего).
     */
    static int grow(Columns columns, int total, int clientWidth, int minWidth)
    {
        int[] idx = stretchableColumns(columns);
        int count = idx.length;
        if (count == 0)
            return 0;
        int extra = clientWidth - total;
        int stretchSum = 0;
        for (int index : idx)
            stretchSum += columns.width(index);
        int assigned = 0;
        for (int s = 0; s < count; s++)
        {
            int index = idx[s];
            int share = stretchSum > 0
                ? (int)((long)extra * columns.width(index) / stretchSum)
                : extra / count;
            if (share < 0)
                share = 0;
            columns.setWidth(index, columns.width(index) + share);
            assigned += share;
        }
        int remainder = extra - assigned;
        if (remainder != 0)
        {
            int last = idx[count - 1];
            columns.setWidth(last, Math.max(minWidth, columns.width(last) + remainder));
        }
        clampTotalToClientWidth(columns, minWidth);
        return count;
    }

    /**
     * Сузить колонки под ширину клиентской области: нехватка {@code total - clientWidth} снимается с
     * resizable-колонок пропорционально их текущим ширинам, но не уже {@code minWidth}. Если все колонки
     * уперлись в минимум, переполнение остаётся — это уже нехватка места по факту (см. вызывающий код).
     *
     * @return сколько колонок участвовало в сужении (0 — сужать было нечего).
     */
    static int shrink(Columns columns, int total, int clientWidth, int minWidth)
    {
        int[] idx = stretchableColumns(columns);
        int count = idx.length;
        if (count == 0)
            return 0;
        int deficit = total - clientWidth;
        int shrinkSum = 0;
        for (int index : idx)
            shrinkSum += columns.width(index);
        int assigned = 0;
        for (int s = 0; s < count; s++)
        {
            int index = idx[s];
            int cut = shrinkSum > 0
                ? (int)((long)deficit * columns.width(index) / shrinkSum)
                : deficit / count;
            if (cut < 0)
                cut = 0;
            int newWidth = Math.max(minWidth, columns.width(index) - cut);
            assigned += columns.width(index) - newWidth;
            columns.setWidth(index, newWidth);
        }
        int remainder = deficit - assigned;
        if (remainder > 0)
        {
            int last = idx[count - 1];
            columns.setWidth(last, Math.max(minWidth, columns.width(last) - remainder));
        }
        clampTotalToClientWidth(columns, minWidth);
        return count;
    }

    /**
     * Финальная защита от горизонтального скролла: если после растягивания/сужения сумма ширин всё же
     * превысила ширину клиентской области (округления при пропорциональном делении, неточности измерения
     * {@code clientArea} и т.п.) — обрезаем избыток с последней resizable-колонки (и, если не хватит, с
     * предыдущих), не глубже {@code minWidth}. Лучше колонки чуть уже, чем горизонтальный скролл.
     */
    static void clampTotalToClientWidth(Columns columns, int minWidth)
    {
        int count = columns.count();
        int overshoot = totalWidth(columns) - columns.clientWidth();
        if (overshoot <= 0)
            return;
        for (int i = count - 1; i >= 0 && overshoot > 0; i--)
        {
            if (!columns.resizable(i))
                continue;
            int reducible = columns.width(i) - minWidth;
            if (reducible <= 0)
                continue;
            int cut = Math.min(reducible, overshoot);
            columns.setWidth(i, columns.width(i) - cut);
            overshoot -= cut;
        }
    }

    /** Индексы колонок, участвующих в перераспределении: resizable и не исключённые. */
    private static int[] stretchableColumns(Columns columns)
    {
        int count = columns.count();
        int[] idx = new int[count];
        int found = 0;
        for (int i = 0; i < count; i++)
        {
            if (!columns.resizable(i) || columns.excluded(i))
                continue;
            idx[found++] = i;
        }
        if (found == count)
            return idx;
        int[] trimmed = new int[found];
        System.arraycopy(idx, 0, trimmed, 0, found);
        return trimmed;
    }
}
