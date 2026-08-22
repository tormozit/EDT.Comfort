package tormozit;

import java.util.function.IntPredicate;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.internal.win32.OS;
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
    /** Сторона штатной иконки объектов EDT (obj16). */
    private static final int ICON_SIZE_PX = 16;
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

        /**
         * Колонка не участвует в АВТО-заполнении по ширине: свободное место и нехватка делятся
         * между остальными. В отличие от {@link #excluded} колонка полноценно существует —
         * пользователь тащит её границу как обычно, и при перетаскивании чужой границы она
         * сужается наравне с прочими. Нужно узким колонкам с запомненной шириной (добавленные
         * колонки дерева элементов формы), чтобы ресайз панели их не раздувал.
         */
        default boolean fixedWidth(int index)
        {
            return false;
        }

        int clientWidth();

        /** Индексы колонок (в порядке создания) в ВИЗУАЛЬНОМ порядке — {@code getColumnOrder()} контрола. */
        int[] visualOrder();

        Control control();
    }

    /** Ширина колонки только с картинкой: {@link #ICON_SIZE_PX} плюс горизонтальные отступы ячейки. */
    static int iconColumnWidth()
    {
        return ICON_SIZE_PX + CELL_TEXT_INSET_PX;
    }

    /** Колонки {@link Table}. */
    static final class TableColumns implements Columns
    {
        private final Table table;
        private final IntPredicate excluded;
        private final IntPredicate fixedWidth;

        /**
         * @param excluded признак «колонка скрыта хозяином» (см. {@link Columns#excluded}); {@code null} —
         *            исключённых колонок нет.
         */
        TableColumns(Table table, IntPredicate excluded)
        {
            this(table, excluded, null);
        }

        /** @param fixedWidth см. {@link Columns#fixedWidth}; {@code null} — таких колонок нет. */
        TableColumns(Table table, IntPredicate excluded, IntPredicate fixedWidth)
        {
            this.table = table;
            this.excluded = excluded;
            this.fixedWidth = fixedWidth;
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
        public boolean fixedWidth(int index)
        {
            return fixedWidth != null && fixedWidth.test(index);
        }

        @Override
        public int clientWidth()
        {
            return table.isDisposed() ? 0 : table.getClientArea().width;
        }

        @Override
        public int[] visualOrder()
        {
            return table.isDisposed() ? new int[0] : table.getColumnOrder();
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
        private final IntPredicate fixedWidth;

        /** @param excluded см. {@link TableColumns#TableColumns(Table, IntPredicate)}. */
        TreeColumns(Tree tree, IntPredicate excluded)
        {
            this(tree, excluded, null);
        }

        /** @param fixedWidth см. {@link Columns#fixedWidth}; {@code null} — таких колонок нет. */
        TreeColumns(Tree tree, IntPredicate excluded, IntPredicate fixedWidth)
        {
            this.tree = tree;
            this.excluded = excluded;
            this.fixedWidth = fixedWidth;
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
        public boolean fixedWidth(int index)
        {
            return fixedWidth != null && fixedWidth.test(index);
        }

        @Override
        public int clientWidth()
        {
            return tree.isDisposed() ? 0 : tree.getClientArea().width;
        }

        @Override
        public int[] visualOrder()
        {
            return tree.isDisposed() ? new int[0] : tree.getColumnOrder();
        }

        @Override
        public Control control()
        {
            return tree;
        }
    }

    /**
     * Зажата ли клавиша Ctrl в данный момент (Win32, состояние клавиши на момент применения). С Ctrl
     * перетаскивание границы колонки не трогает соседей — пользователь осознанно делает колонку шире
     * контрола, вместе с горизонтальной прокруткой.
     */
    static boolean isCtrlPressed()
    {
        return (OS.GetKeyState(OS.VK_CONTROL) & 0x8000) != 0;
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
     * Перетаскивание границы колонки: колонки ПРАВЕЕ перетаскиваемой сужаются (или растут) так, чтобы
     * сохранить заполнение по ширине и не породить горизонтальную прокрутку. Колонки трогаются ТОЛЬКО на
     * величину, которую не удалось поглотить/освободить за счёт уже имеющегося запаса:
     * <ul>
     * <li>граница вправо (колонка выросла) — если было свободное место, рост сначала расходует его,
     * соседей не трогая; сужение соседей применяется только к части роста, это место превысившей;</li>
     * <li>граница влево (колонка сузилась), а до этого было свободное место — соседи не трогаются вообще,
     * освободившееся место остаётся пустым;</li>
     * <li>граница влево при переполнении или точном совпадении — сужение сначала расходуется на
     * устранение переполнения, а его ИЗБЫТОК идёт на пропорциональный рост соседей.</li>
     * </ul>
     * Non-resizable и исключённые колонки не трогаются, их ширина вычитается из перераспределяемого
     * остатка. При нехватке места соседи сужаются до {@code minWidth}.
     *
     * @param draggedIndex индекс перетаскиваемой колонки в порядке создания.
     * @param before ширины колонок в ВИЗУАЛЬНОМ порядке на начало серии событий текущего перетаскивания.
     * @param clientWidth ширина, под которую держим колонки (у деревьев с общими колонками — их общий
     *            бюджет, см. {@code ColumnAutoFit}).
     * @return {@code true}, если ширины соседей действительно изменились.
     */
    static boolean applyProportionalShrink(Columns columns, int draggedIndex, int[] before, int minWidth,
        int clientWidth)
    {
        int[] order = columns.visualOrder();
        if (before == null || before.length != order.length || draggedIndex < 0)
            return false;
        int draggedPos = -1;
        for (int v = 0; v < order.length; v++)
        {
            if (order[v] == draggedIndex)
            {
                draggedPos = v;
                break;
            }
        }
        if (draggedPos < 0 || draggedPos >= order.length - 1)
            return false; // колонок правее нет
        int rawDelta = columns.width(draggedIndex) - before[draggedPos];
        if (rawDelta == 0)
            return false;
        int totalBefore = 0;
        for (int w : before)
            totalBefore += w;
        int effectiveDelta;
        if (rawDelta > 0)
        {
            int freeSpace = Math.max(0, clientWidth - totalBefore);
            effectiveDelta = rawDelta - Math.min(rawDelta, freeSpace);
            if (effectiveDelta <= 0)
                return false; // рост поглощён свободным местом — соседей не трогаем
        }
        else if (totalBefore < clientWidth)
        {
            return false; // запас свободного места уже был — сужение просто уменьшает общую ширину
        }
        else
        {
            int shrinkAmount = -rawDelta;
            int overflow = Math.max(0, totalBefore - clientWidth);
            int excessShrink = shrinkAmount - Math.min(shrinkAmount, overflow);
            if (excessShrink <= 0)
                return false; // весь shrink ушёл на устранение переполнения
            effectiveDelta = -excessShrink;
        }
        int rightBefore = 0;
        for (int v = draggedPos + 1; v < order.length; v++)
            rightBefore += before[v];
        int resizableTarget = rightBefore - effectiveDelta;
        int minResizable = 0;
        int rc = 0;
        int[] rcVisual = new int[order.length - draggedPos - 1];
        int[] rcStart = new int[order.length - draggedPos - 1];
        for (int v = draggedPos + 1; v < order.length; v++)
        {
            int index = order[v];
            if (columns.excluded(index))
            {
                // Скрытая нулевой шириной колонка — не кандидат: вычитаем её ФАКТИЧЕСКУЮ ширину, а не
                // before[v], иначе при скрытии во время перетаскивания остаток уменьшился бы на уже
                // отсутствующие пиксели.
                resizableTarget -= columns.width(index);
            }
            else if (columns.resizable(index))
            {
                rcVisual[rc] = v;
                rcStart[rc] = before[v];
                rc++;
                minResizable += minWidth;
            }
            else
            {
                resizableTarget -= before[v];
            }
        }
        if (rc == 0)
            return false;
        if (resizableTarget < minResizable)
            resizableTarget = minResizable;
        int startSum = 0;
        for (int s = 0; s < rc; s++)
            startSum += rcStart[s];
        int[] shares = new int[rc];
        int assigned = 0;
        for (int s = 0; s < rc; s++)
        {
            int share = startSum > 0
                ? (int)((long)resizableTarget * rcStart[s] / startSum)
                : resizableTarget / rc;
            share = Math.max(minWidth, share);
            shares[s] = share;
            assigned += share;
        }
        int remainder = resizableTarget - assigned;
        if (remainder != 0)
            shares[rc - 1] = Math.max(minWidth, shares[rc - 1] + remainder);
        // Соседи уже упёрлись в пол и целевые ширины не изменились — применять нечего (иначе на каждый
        // пиксель перетаскивания шёл бы лишний setWidth на неизменные значения, что давало дёргание).
        boolean anyChange = false;
        for (int s = 0; s < rc; s++)
        {
            if (columns.width(order[rcVisual[s]]) != shares[s])
            {
                anyChange = true;
                break;
            }
        }
        if (!anyChange)
            return false;
        for (int s = 0; s < rc; s++)
            columns.setWidth(order[rcVisual[s]], shares[s]);
        clampTotalToClientWidth(columns, minWidth);
        return true;
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
            if (!columns.resizable(i) || columns.excluded(i) || columns.fixedWidth(i))
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
