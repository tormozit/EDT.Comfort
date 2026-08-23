package tormozit;

import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Выбор ячейки и подсветка активной ячейки/строки в многоколоночном {@link Tree} — то же
 * поведение, что у таблиц плагина ({@link FormTableInteraction}) и панели «Индексирование Git»,
 * но для чужого (штатного) дерева, к которому колонки дописаны плагином.
 *
 * <p>Зачем отдельно от {@link FormTableInteraction}: тот работает с {@link org.eclipse.swt.widgets.Table}
 * и создаёт таблицу «под себя» (оверлей заголовка, порядок колонок), а здесь дерево уже создано
 * EDT со своим стилем и своими слушателями — трогать можно только рисование и выбор ячейки.
 *
 * <p><b>Клик мимо первой колонки.</b> Штатное дерево редактора формы создано без
 * {@link SWT#FULL_SELECTION}, поэтому клик во второй и далее колонках не выделяет строку вовсе.
 * Выделение ставится через {@link TreeViewer} (а не {@code tree.setSelection}), иначе о нём не
 * узнают ни JFace, ни EDT: эскиз формы и панель «Свойства» остались бы на прежнем элементе.
 *
 * <p>Системная подсветка выделения в светлой теме не гасится: текущая строка выглядит так же, как
 * в дереве реквизитов формы и прочих штатных списках EDT, а плагин добавляет к ней только акцент
 * активной ячейки (её фон и рамку). В тёмной теме системная подсветка стирала бы этот акцент,
 * поэтому там всё рисуется самим плагином.
 *
 * <p>Ctrl+C намеренно не перехватывается: у штатных деревьев копирование своё (в редакторе формы
 * это копирование элемента формы), и подменять его нельзя.
 */
final class FormTreeInteraction
{
    private static final String INSTALLED_KEY = "tormozit.formTreeInteraction"; //$NON-NLS-1$

    /**
     * Оттенки подсветки — общие для всех списков плагина. Системная подсветка выделения в светлой
     * теме сохраняется (см. {@link #onEraseItem}), поэтому режим тот же, что у таблиц.
     */
    private static final ListSelectionPalette.Mode PALETTE =
        ListSelectionPalette.Mode.NATIVE_SELECTION;

    private final Tree tree;

    private final TreeViewer viewer;

    private TreeItem selectedItem;

    /**
     * Строка последнего клика, пока выделение дерева ещё не переехало на неё. Живёт до конца
     * текущего цикла событий (см. {@link #clearPendingRowLater()}) и только для отрисовки.
     */
    private TreeItem pendingRow;

    private int activeColumn;

    private Color ownedRowBg;

    private Color ownedInactiveRowBg;

    private Color ownedActiveCellBg;

    private Color ownedFrame;

    /**
     * Снимок выделения: {@link Tree#getSelection()} — нативный вызов, создающий массив, а
     * отрисовка спрашивает выделение на КАЖДУЮ ячейку (и не по разу). На прокрутке это давало
     * сотни таких вызовов в секунду и было главной статьёй расхода времени в обработчиках
     * отрисовки. Снимок живёт {@link #SELECTION_SNAPSHOT_MS} — заведомо дольше одной перерисовки
     * и заведомо незаметно для глаза, даже если выделение сменили программно (без событий).
     */
    private TreeItem[] selectionSnapshot;

    private long selectionSnapshotAt;

    /** Срок жизни снимка выделения, мс. */
    private static final long SELECTION_SNAPSHOT_MS = 20;

    private FormTreeInteraction(Tree tree, TreeViewer viewer)
    {
        this.tree = tree;
        this.viewer = viewer;
    }

    /** Подключить к дереву (идемпотентно). */
    static FormTreeInteraction install(Tree tree, TreeViewer viewer)
    {
        if (tree == null || tree.isDisposed())
            return null;
        if (tree.getData(INSTALLED_KEY) instanceof FormTreeInteraction existing)
            return existing;
        FormTreeInteraction interaction = new FormTreeInteraction(tree, viewer);
        tree.setData(INSTALLED_KEY, interaction);
        interaction.hook();
        return interaction;
    }

    static FormTreeInteraction of(Tree tree)
    {
        return tree != null && !tree.isDisposed()
            && tree.getData(INSTALLED_KEY) instanceof FormTreeInteraction interaction ? interaction : null;
    }

    private void hook()
    {
        ListSelectionThemeColors.markOptOut(tree);
        tree.addListener(SWT.MouseDown, this::onMouseDown);
        tree.addListener(SWT.EraseItem, this::onEraseItem);
        tree.addListener(SWT.PaintItem, this::onPaintItem);
        tree.addListener(SWT.FocusIn, event -> {
            invalidateColors();
            redrawRow(activeRow());
        });
        tree.addListener(SWT.FocusOut, event -> {
            invalidateColors();
            redrawRow(activeRow());
        });
        // Полный tree.redraw() на Selection в больших списках (Задачи) блокирует UI:
        // перерисовываем только прежнюю и новую строки — как в FormTableInteraction.
        tree.addListener(SWT.Selection, event -> {
            TreeItem previous = selectedItem;
            syncFromSelection();
            invalidateColors();
            redrawRow(previous);
            redrawRow(selectedItem);
        });
        tree.addListener(SWT.Dispose, event -> invalidateColors());
    }

    /** Индекс активной колонки (0, если ещё не выбрана). */
    int activeColumn()
    {
        int column = activeColumn;
        return column >= 0 && column < tree.getColumnCount() ? column : 0;
    }

    /**
     * Текущая строка: та, по которой был клик, — пока она остаётся выделенной.
     *
     * <p>Считается КАЖДЫЙ раз, а не только по событиям выбора: выделение в дереве меняют и мимо
     * {@link SWT#Selection} — программно, из эскиза формы и панели «Свойства»
     * ({@code TreeViewer.setSelection} события не шлёт). Запомненная строка тогда оставалась бы
     * подсвеченной рядом с новой выделенной — это и есть «не убирается подсветка старой строки».
     * Если выделения нет вовсе (например, клик по пустому месту), подсветка остаётся на прежней
     * строке — как в остальных списках плагина.
     */
    TreeItem activeRow()
    {
        // Строка, по которой только что кликнули, но выделение в дереве ещё не переехало: своё
        // мы ставим отложенно (см. onMouseDown), нативное — тоже не всегда до нашей отрисовки.
        // Без этого первый кадр после клика рисовался по СТАРОМУ выделению, а колонка была уже
        // новой: активная ячейка вспыхивала в прежней строке и лишь потом переезжала в целевую.
        if (pendingRow != null && !pendingRow.isDisposed())
            return pendingRow;
        if (selectedItem != null && !selectedItem.isDisposed() && isRowSelected(selectedItem))
            return selectedItem;
        TreeItem[] selection = selection();
        if (selection.length > 0)
            return selection[0];
        return selectedItem != null && !selectedItem.isDisposed() ? selectedItem : null;
    }

    // -----------------------------------------------------------------------
    // Выбор ячейки
    // -----------------------------------------------------------------------

    private void onMouseDown(Event e)
    {
        boolean rightClick = e.button == 3;
        if (e.button != 1 && !rightClick)
            return;
        TreeItem item = rowAt(tree, e.x, e.y);
        if (item == null)
            return;
        // Штатным попаданием дерево считает только текст первой колонки: клик по отступу, значку,
        // пустому месту справа от подписи и по любой добавленной колонке оно игнорирует. Ровно
        // такие клики выделяем сами — иначе часть площади ячейки «мёртвая».
        boolean nativeHit = tree.getItem(new Point(e.x, e.y)) == item;
        boolean multiSelect = (e.stateMask & (SWT.CTRL | SWT.SHIFT)) != 0;
        int column = columnAtX(tree, e.x);
        TreeItem previous = selectedItem;
        selectedItem = item;
        activeColumn = column < 0 ? 0 : column;
        // Клик почти наверняка меняет выделение, а снимок живёт SELECTION_SNAPSHOT_MS — иначе
        // отрисовка ниже спросила бы выделение у устаревшего кэша.
        invalidateSelection();
        // До конца текущего цикла событий рисуем по строке клика, а не по выделению дерева.
        pendingRow = multiSelect ? null : item;

        // Подсветка рисуется ПЕРВОЙ и немедленно: redraw() лишь помечает область грязной, а
        // фактическая отрисовка ждёт свободного цикла событий — а его занимает смена выделения
        // (эскиз формы и панель «Свойства» перестраиваются синхронно). Из-за этого подсветка
        // появлялась с заметной задержкой после клика.
        invalidateColors();
        redrawRow(previous);
        redrawRow(item);
        tree.update();

        boolean selectHere = (rightClick || !nativeHit) && !multiSelect && viewer != null
            && !viewer.getControl().isDisposed() && item.getData() != null && !isRowSelected(item);
        if (!selectHere)
        {
            // Выделение ставит само дерево — держим строку клика до конца обработки события.
            clearPendingRowLater();
            return;
        }
        Object element = item.getData();
        if (rightClick)
        {
            // Правый клик: строка должна стать текущей ДО построения контекстного меню
            // (SWT шлёт MouseDown раньше MenuDetect), поэтому здесь без откладывания.
            viewer.setSelection(new StructuredSelection(element), false);
            pendingRow = null;
            return;
        }
        // Левый клик: тяжёлую перестройку эскиза и панели «Свойства» запускаем после отрисовки.
        tree.getDisplay().asyncExec(() -> {
            if (!tree.isDisposed() && !viewer.getControl().isDisposed())
                viewer.setSelection(new StructuredSelection(element), false);
        });
        clearPendingRowLater();
    }

    /** Перерисовать одну строку: полный {@code redraw()} дерева на клик избыточен. */
    private void redrawRow(TreeItem item)
    {
        if (item == null || item.isDisposed())
            return;
        Rectangle bounds = rowBounds(tree, item);
        if (bounds == null)
            return;
        tree.redraw(0, bounds.y, tree.getClientArea().width, bounds.height, false);
    }

    /**
     * Снять «строку клика» после того, как обработка клика завершится: к этому моменту выделение
     * (наше отложенное или нативное) уже переехало, и рисовать можно снова по нему. Ставится в
     * очередь ПОСЛЕ отложенного {@code setSelection} — {@code asyncExec} выполняется по порядку.
     */
    private void clearPendingRowLater()
    {
        tree.getDisplay().asyncExec(() -> {
            if (tree.isDisposed())
                return;
            TreeItem row = pendingRow;
            pendingRow = null;
            invalidateSelection();
            if (row != null && activeRow() != row)
            {
                invalidateColors();
                redrawRow(row);
                redrawRow(activeRow());
            }
        });
    }

    private void syncFromSelection()
    {
        pendingRow = null;
        invalidateSelection();
        if (selectedItem != null && !selectedItem.isDisposed() && isRowSelected(selectedItem))
            return;
        TreeItem[] selection = selection();
        if (selection.length > 0)
            selectedItem = selection[0];
    }

    private TreeItem[] selection()
    {
        long now = System.currentTimeMillis();
        if (selectionSnapshot == null || now - selectionSnapshotAt > SELECTION_SNAPSHOT_MS)
        {
            selectionSnapshot = tree.getSelection();
            selectionSnapshotAt = now;
        }
        return selectionSnapshot;
    }

    private void invalidateSelection()
    {
        selectionSnapshot = null;
    }

    private boolean isRowSelected(TreeItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        for (TreeItem selected : selection())
        {
            if (selected == item)
                return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Подсветка
    // -----------------------------------------------------------------------

    private void onEraseItem(Event e)
    {
        if (!(e.item instanceof TreeItem item))
            return;
        TreeItem active = activeRow();
        if (!isRowSelected(item) && item != active)
            return;
        boolean activeRow = item == active;
        Color rowBg = activeRow ? rowSelectionBackground() : inactiveRowSelectionBackground();
        Color bg = activeRow && e.index == activeColumn() ? activeCellBackground(rowBg) : rowBg;
        e.gc.setBackground(bg);
        e.gc.fillRectangle(e.x, e.y, e.width, e.height);
        e.detail &= ~SWT.BACKGROUND;
        if (ListSelectionThemeColors.isDarkList(tree))
        {
            // Тёмная тема: системная подсветка кладётся поверх нашей заливки единым цветом на всю
            // строку и стирает различие «активная ячейка / прочие ячейки» — гасим её.
            // В светлой теме она, наоборот, нужна: именно она даёт голубой оттенок текущей строки,
            // как в дереве реквизитов формы и остальных штатных списках EDT.
            e.detail &= ~SWT.SELECTED;
            e.detail &= ~SWT.HOT;
        }
    }

    private void onPaintItem(Event e)
    {
        if (!(e.item instanceof TreeItem item) || item != activeRow() || e.index != activeColumn())
            return;
        Rectangle bounds = item.getBounds(e.index);
        if (bounds == null || bounds.isEmpty())
            return;
        // Цвет рамки кэшируется наравне с остальными: создание и освобождение нативного Color
        // на каждую отрисовку ячейки было вторым по стоимости местом при прокрутке.
        if (ownedFrame == null || ownedFrame.isDisposed())
            ownedFrame = ListSelectionPalette.activeCellFrame(
                activeCellBackground(rowSelectionBackground()));
        e.gc.setForeground(ownedFrame);
        e.gc.drawRectangle(bounds.x, bounds.y, Math.max(0, bounds.width - 1),
            Math.max(0, bounds.height - 1));
    }

    private Color rowSelectionBackground()
    {
        if (ownedRowBg == null || ownedRowBg.isDisposed())
            ownedRowBg = ListSelectionPalette.rowSelectionBackground(tree, PALETTE);
        return ownedRowBg;
    }

    /** Фон прочих выбранных строк при мультивыделении (слабее текущей). */
    private Color inactiveRowSelectionBackground()
    {
        if (ownedInactiveRowBg == null || ownedInactiveRowBg.isDisposed())
            ownedInactiveRowBg = ListSelectionPalette.inactiveRowSelectionBackground(tree, PALETTE);
        return ownedInactiveRowBg;
    }

    private Color activeCellBackground(Color rowBg)
    {
        if (ownedActiveCellBg == null || ownedActiveCellBg.isDisposed())
            ownedActiveCellBg = ListSelectionPalette.activeCellBackground(tree, rowBg, PALETTE);
        return ownedActiveCellBg;
    }

    private void invalidateColors()
    {
        ownedRowBg = disposed(ownedRowBg);
        ownedInactiveRowBg = disposed(ownedInactiveRowBg);
        ownedActiveCellBg = disposed(ownedActiveCellBg);
        ownedFrame = disposed(ownedFrame);
    }

    private static Color disposed(Color color)
    {
        if (color != null && !color.isDisposed())
            color.dispose();
        return null;
    }

    // -----------------------------------------------------------------------
    // Попадание курсора
    // -----------------------------------------------------------------------

    /**
     * Строка под точкой дерева — по вертикали, независимо от того, попала ли точка в текст.
     *
     * <p>{@code Tree.getItem(Point)} на Win32 отвечает только для текста первой колонки, а
     * {@code TreeItem.getBounds(0)} возвращает прямоугольник этого же текста, не всей ячейки.
     * Поэтому попадание ищется по вертикальному диапазону строки, а горизонталь не проверяется
     * вовсе: по горизонтали строка занимает всю ширину дерева.
     */
    static TreeItem rowAt(Tree tree, int x, int y)
    {
        if (tree == null || tree.isDisposed())
            return null;
        TreeItem item = tree.getItem(new Point(x, y));
        if (item != null)
            return item;
        // Обход только видимых строк — от верхней вниз. Полный обход дерева здесь стоил
        // на больших формах десятки миллисекунд на каждый клик.
        int height = Math.max(tree.getItemHeight(), 1);
        int limit = tree.getClientArea().height / height + 2;
        int seen = 0;
        for (TreeItem row = tree.getTopItem(); row != null && seen < limit; row = nextVisibleRow(row))
        {
            seen++;
            if (row.isDisposed())
                continue;
            Rectangle bounds = rowBounds(tree, row);
            if (bounds != null && y >= bounds.y && y < bounds.y + bounds.height)
                return row;
        }
        return null;
    }

    /** Следующая строка в порядке показа: первый развёрнутый потомок, иначе следующий сосед. */
    private static TreeItem nextVisibleRow(TreeItem row)
    {
        if (row.getExpanded() && row.getItemCount() > 0)
            return row.getItem(0);
        for (TreeItem current = row; current != null; current = current.getParentItem())
        {
            TreeItem parent = current.getParentItem();
            TreeItem[] siblings = parent != null ? parent.getItems() : current.getParent().getItems();
            for (int i = 0; i < siblings.length - 1; i++)
            {
                if (siblings[i] == current)
                    return siblings[i + 1];
            }
        }
        return null;
    }

    /** Прямоугольник строки: годится любой непустой прямоугольник её ячеек — нужна только высота. */
    private static Rectangle rowBounds(Tree tree, TreeItem item)
    {
        if (item == null || item.isDisposed())
            return null;
        Rectangle bounds = item.getBounds();
        if (bounds != null && bounds.height > 0)
            return bounds;
        for (int i = 0; i < tree.getColumnCount(); i++)
        {
            bounds = item.getBounds(i);
            if (bounds != null && bounds.height > 0)
                return bounds;
        }
        return null;
    }

    /**
     * Колонка под точкой — по ширинам колонок в их визуальном порядке, а не по
     * {@code TreeItem.getBounds(index)}: у первой колонки тот отдаёт только область текста,
     * и клик по отступу или пустому месту ячейки не относился бы ни к какой колонке.
     */
    static int columnAtX(Tree tree, int x)
    {
        if (tree == null || tree.isDisposed() || tree.getColumnCount() == 0)
            return -1;
        int offset = -horizontalScroll(tree);
        for (int visual : tree.getColumnOrder())
        {
            int width = tree.getColumn(visual).getWidth();
            if (x >= offset && x < offset + width)
                return visual;
            offset += width;
        }
        return -1;
    }

    private static int horizontalScroll(Tree tree)
    {
        ScrollBar bar = tree.getHorizontalBar();
        return bar != null && bar.isVisible() ? bar.getSelection() : 0;
    }
}
