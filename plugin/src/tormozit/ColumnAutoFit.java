package tormozit;

import java.util.function.ToIntFunction;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;

/**
 * Растягивание/сужение колонок дерева вместе с его окном/панелью: колонки всегда занимают всю ширину
 * клиентской области, пропорционально своим текущим ширинам.
 *
 * <p>Нужно там, где колонки создаёт не плагин, а штатный код, задающий ширины ОДИН раз при построении
 * и не реагирующий на ресайз (issue #273): деревья инспектора отладчика и панелей «Выражения» —
 * платформенный {@code InternalTreeModelViewer} ({@code initColumns}), панель «Индексирование Git» —
 * колонки {@link GitStagingFilterHook} с сохранёнными ширинами. Результат один: при расширении окна
 * справа оставалось пустое место, при сужении появлялась горизонтальная прокрутка.
 *
 * <p>Сам пересчёт ширин — общий с таблицами плагина ({@link ColumnWidthFit}); здесь только политика и
 * подписка на события:
 * <ul>
 * <li>ширину клиентской области колонки заполняют ВСЕГДА (в отличие от {@link FormTableInteraction},
 * который лишь поддерживает ранее активный режим заполнения и не включает его самовольно);</li>
 * <li>ручные ширины уважаются: «умещались ли колонки до» считается по ПРОШЛОЙ ширине клиентской
 * области, поэтому переполнение, созданное самим пользователем (растащил границу колонки между двумя
 * ресайзами окна), обратно не схлопывается.</li>
 * </ul>
 *
 * <p>Подключается к {@link Tree}, а не к окну: попапы инспектора и панели пересоздают деревья, и
 * подгонка ставится/снимается вместе с ними. Повторный {@link #install} на том же дереве возвращает
 * уже подключённый экземпляр — партлистенеры могут звать его сколько угодно раз.
 */
final class ColumnAutoFit
{
    private static final String INSTALLED_KEY = "tormozit.columnAutoFit"; //$NON-NLS-1$

    /** Задержки первичной подгонки: колонки создаются не сразу (у платформы — из своего PaintListener). */
    private static final int[] INITIAL_FIT_DELAYS_MS = { 0, 50, 150, 400, 800 };

    /**
     * Сколько раз за одну подгонку пересчитывать ширины. Пересчёт САМ меняет ширину клиентской области:
     * колонки шире клиента → появляется горизонтальная полоса → клиентская область ниже → может появиться
     * вертикальная полоса → клиентская область уже → колонки снова шире клиента. Одного прохода не хватает
     * (в «Индексирование Git» горизонтальная полоса из-за этого висела постоянно), поэтому повторяем по
     * фактической ширине клиента, пока не совпадёт.
     */
    private static final int MAX_FIT_PASSES = 3;

    /** Задержки отложенной проверки после подгонки (мс) — см. {@link #scheduleVerify()}. */
    private static final int[] VERIFY_DELAYS_MS = { 0, 60, 250 };

    /** Метка «на колонку уже подписаны» — см. {@link #hookColumns()}. */
    private static final String COLUMN_HOOKED_KEY = "tormozit.columnAutoFitColumn"; //$NON-NLS-1$

    /** Пауза без новых событий ресайза колонки, после которой применяется сужение соседей (мс). */
    private static final int DRAG_COMMIT_DEBOUNCE_MS = 60;

    private final Tree tree;
    private final ColumnWidthFit.Columns columns;
    private final ToIntFunction<Tree> widthBudget;
    private final Listener resizeListener;
    private final Listener paintListener;
    private final Listener columnResizeListener;
    private boolean adjusting;
    /** Бюджет ширины при прошлой подгонке — база для «умещались ли колонки ДО» (см. {@link #fit()}). */
    private int lastClientWidth;
    /** Ширины колонок в визуальном порядке на начало серии событий текущего перетаскивания границы. */
    private int[] dragBaselineWidths;
    /** Колонка, границу которой сейчас тащат (индекс в порядке создания), или -1. */
    private int dragColumnIndex = -1;
    private Runnable pendingDragCommit;
    /** Последние известные ширины (визуальный порядок) — снимок до начала перетаскивания. */
    private int[] lastKnownVisualWidths;

    private ColumnAutoFit(Tree tree, ToIntFunction<Tree> widthBudget)
    {
        this.tree = tree;
        this.columns = new ColumnWidthFit.TreeColumns(tree, null);
        this.widthBudget = widthBudget;
        this.resizeListener = e -> fit();
        this.columnResizeListener = e ->
        {
            if (e.widget instanceof TreeColumn column && !column.isDisposed())
                onUserColumnResize(column);
        };
        // Бюджет ширины меняется не только с размером контрола: вертикальная полоса прокрутки появляется
        // и исчезает по мере наполнения/сворачивания дерева, а SWT.Resize при этом не приходит. Ловим по
        // факту отрисовки — работа делается только при реально изменившемся бюджете.
        this.paintListener = e ->
        {
            if (adjusting || tree.isDisposed())
                return;
            hookColumns(); // колонки могут быть пересозданы (панель/попап перестроили дерево)
            if (widthBudget() != lastClientWidth)
                fit();
        };
    }

    /** Подключить подгонку к дереву (идемпотентно). */
    static ColumnAutoFit install(Tree tree)
    {
        return install(tree, null);
    }

    /**
     * Подключить подгонку с собственным бюджетом ширины (идемпотентно).
     *
     * @param widthBudget ширина, под которую подгонять колонки; {@code null} — ширина клиентской области
     *            самого дерева. Нужен там, где ширины колонок разделяются несколькими деревьями с РАЗНОЙ
     *            клиентской областью: в «Индексирование Git» списки staged/unstaged синхронизируют ширины
     *            друг с другом, а вертикальная полоса прокрутки есть не у каждого — общий бюджет (минимум
     *            по обоим, т.е. с запасом на полосу) не даёт синхронизации создавать переполнение
     *            (см. {@code GitStagingFilterHook.stagingWidthBudget}).
     */
    static ColumnAutoFit install(Tree tree, ToIntFunction<Tree> widthBudget)
    {
        if (tree == null || tree.isDisposed())
            return null;
        if (tree.getData(INSTALLED_KEY) instanceof ColumnAutoFit existing)
            return existing;
        ColumnAutoFit autoFit = new ColumnAutoFit(tree, widthBudget);
        tree.setData(INSTALLED_KEY, autoFit);
        tree.addListener(SWT.Resize, autoFit.resizeListener);
        tree.addListener(SWT.Paint, autoFit.paintListener);
        autoFit.hookColumns();
        autoFit.rememberVisualWidths();
        Display display = tree.getDisplay();
        for (int delay : INITIAL_FIT_DELAYS_MS)
        {
            display.timerExec(delay, () ->
            {
                if (!tree.isDisposed())
                    autoFit.fit();
            });
        }
        return autoFit;
    }

    void dispose()
    {
        if (tree == null || tree.isDisposed())
            return;
        tree.removeListener(SWT.Resize, resizeListener);
        tree.removeListener(SWT.Paint, paintListener);
        for (TreeColumn column : tree.getColumns())
        {
            if (column == null || column.isDisposed() || column.getData(COLUMN_HOOKED_KEY) == null)
                continue;
            column.removeListener(SWT.Resize, columnResizeListener);
            column.setData(COLUMN_HOOKED_KEY, null);
        }
        tree.setData(INSTALLED_KEY, null);
    }

    private void fit()
    {
        if (adjusting || tree.isDisposed())
            return;
        if (columns.count() <= 0)
        {
            // Колонок ещё нет (панель отладчика без сессии, дерево до построения колонок). Сбрасываем
            // базу, чтобы следующая же отрисовка с уже готовыми колонками попала в подгонку, даже если
            // ширина клиентской области при их появлении не изменилась.
            lastClientWidth = 0;
            return;
        }
        int clientWidth = widthBudget();
        if (clientWidth <= 0)
            return;
        int total = ColumnWidthFit.totalWidth(columns);
        boolean fitBefore = lastClientWidth <= 0 || total <= lastClientWidth;
        lastClientWidth = clientWidth;
        if (total == clientWidth)
            return;
        if (total > clientWidth && !fitBefore)
            return; // переполнение было и до изменения размера окна — это выбор пользователя
        adjusting = true;
        int passes = 0;
        try
        {
            int minWidth = ColumnWidthFit.minColumnWidth(tree);
            while (passes < MAX_FIT_PASSES)
            {
                passes++;
                if (total < clientWidth)
                    ColumnWidthFit.grow(columns, total, clientWidth, minWidth);
                else
                    ColumnWidthFit.shrink(columns, total, clientWidth, minWidth);
                int actualClientWidth = widthBudget();
                total = ColumnWidthFit.totalWidth(columns);
                if (actualClientWidth <= 0)
                    break;
                lastClientWidth = actualClientWidth;
                if (total == actualClientWidth)
                    break;
                clientWidth = actualClientWidth; // полоса прокрутки изменила бюджет — ещё проход
            }
        }
        finally
        {
            adjusting = false;
        }
        rememberVisualWidths();
        scheduleVerify();
    }

    /**
     * Подписка на ресайз каждой колонки — метится на самой колонке, поэтому идемпотентна и корректно
     * доподписывается на колонки, созданные позже (штатный viewer и {@code GitStagingFilterHook}
     * пересоздают их при смене содержимого/настроек).
     */
    private void hookColumns()
    {
        if (tree.isDisposed())
            return;
        for (TreeColumn column : tree.getColumns())
        {
            if (column == null || column.isDisposed() || column.getData(COLUMN_HOOKED_KEY) != null)
                continue;
            column.setData(COLUMN_HOOKED_KEY, Boolean.TRUE);
            column.addListener(SWT.Resize, columnResizeListener);
        }
    }

    /**
     * Пользователь тащит границу колонки (или ширину поменяли программно мимо нас). Пока перетаскивание
     * идёт, Win32 держит СВОЙ модальный цикл: менять ширины соседей синхронно из этого же события нельзя —
     * цикл пересчитает позицию под курсором и «отрастит» перетаскиваемую колонку обратно (резонанс,
     * см. {@code FormTableInteraction.onUserColumnResize}). Поэтому сужение соседей применяется одним
     * commit'ом после паузы в событиях.
     */
    private void onUserColumnResize(TreeColumn column)
    {
        if (adjusting || tree.isDisposed())
            return;
        int index = tree.indexOf(column);
        if (index < 0)
            return;
        if (dragColumnIndex != index)
        {
            dragColumnIndex = index;
            dragBaselineWidths = lastKnownVisualWidths;
        }
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        if (pendingDragCommit != null)
            display.timerExec(-1, pendingDragCommit);
        pendingDragCommit = () ->
        {
            pendingDragCommit = null;
            commitDrag();
        };
        display.timerExec(DRAG_COMMIT_DEBOUNCE_MS, pendingDragCommit);
    }

    /** Применение накопленного за паузу перетаскивания — вне активного модального цикла делителя. */
    private void commitDrag()
    {
        int index = dragColumnIndex;
        int[] before = dragBaselineWidths;
        dragColumnIndex = -1;
        dragBaselineWidths = null;
        if (tree.isDisposed() || index < 0 || index >= columns.count())
        {
            rememberVisualWidths();
            return;
        }
        int minWidth = ColumnWidthFit.minColumnWidth(tree);
        adjusting = true;
        try
        {
            if (columns.width(index) < minWidth) // native drag ничем не ограничен снизу, вплоть до нуля
                columns.setWidth(index, minWidth);
            // С зажатым Ctrl соседей не трогаем — как в таблицах плагина: пользователь осознанно делает
            // колонку шире контрола вместе с горизонтальной прокруткой (см. ColumnWidthFit.isCtrlPressed).
            if (before != null && !ColumnWidthFit.isCtrlPressed())
                ColumnWidthFit.applyProportionalShrink(columns, index, before, minWidth, widthBudget());
        }
        finally
        {
            adjusting = false;
        }
        rememberVisualWidths();
    }

    /** Запомнить текущие ширины (визуальный порядок) — база для следующей серии перетаскивания. */
    private void rememberVisualWidths()
    {
        if (tree.isDisposed())
        {
            lastKnownVisualWidths = null;
            return;
        }
        int[] order = columns.visualOrder();
        int[] widths = new int[order.length];
        for (int v = 0; v < order.length; v++)
            widths[v] = columns.width(order[v]);
        lastKnownVisualWidths = widths;
    }

    /** Ширина, под которую подгоняются колонки: бюджет владельца либо клиентская область самого дерева. */
    private int widthBudget()
    {
        if (widthBudget == null)
            return columns.clientWidth();
        int budget = widthBudget.applyAsInt(tree);
        return budget > 0 ? budget : columns.clientWidth();
    }

    /**
     * Отложенная проверка после подгонки. Вертикальная полоса прокрутки может появиться уже ПОСЛЕ наших
     * {@code setWidth} (дерево дозаполняется, раскрывается узел, приходит содержимое) — тогда клиентская
     * область становится уже на ширину полосы, и колонки снова не влезают: именно так в
     * «Индексирование Git» оставался постоянный горизонтальный скролл у списка со вертикальной полосой,
     * тогда как у короткого списка без неё всё было ровно. Проверка идёт по фактической ширине клиента и
     * при совпадении ничего не делает.
     */
    private void scheduleVerify()
    {
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        for (int delay : VERIFY_DELAYS_MS)
        {
            display.timerExec(delay, () ->
            {
                if (!tree.isDisposed() && !adjusting && widthBudget() != lastClientWidth)
                    fit();
            });
        }
    }

}
