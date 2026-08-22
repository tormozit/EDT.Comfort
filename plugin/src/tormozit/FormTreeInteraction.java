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

    private int activeColumn;

    private Color ownedRowBg;

    private Color ownedInactiveRowBg;

    private Color ownedActiveCellBg;

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
        tree.addListener(SWT.FocusIn, event -> { invalidateColors(); tree.redraw(); });
        tree.addListener(SWT.FocusOut, event -> { invalidateColors(); tree.redraw(); });
        tree.addListener(SWT.Selection, event -> { syncFromSelection(); invalidateColors(); tree.redraw(); });
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
        if (selectedItem != null && !selectedItem.isDisposed() && isRowSelected(selectedItem))
            return selectedItem;
        TreeItem[] selection = tree.getSelection();
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
            return;
        Object element = item.getData();
        if (rightClick)
        {
            // Правый клик: строка должна стать текущей ДО построения контекстного меню
            // (SWT шлёт MouseDown раньше MenuDetect), поэтому здесь без откладывания.
            viewer.setSelection(new StructuredSelection(element), false);
            return;
        }
        // Левый клик: тяжёлую перестройку эскиза и панели «Свойства» запускаем после отрисовки.
        tree.getDisplay().asyncExec(() -> {
            if (!tree.isDisposed() && !viewer.getControl().isDisposed())
                viewer.setSelection(new StructuredSelection(element), false);
        });
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

    private void syncFromSelection()
    {
        if (selectedItem != null && !selectedItem.isDisposed() && isRowSelected(selectedItem))
            return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length > 0)
            selectedItem = selection[0];
    }

    private boolean isRowSelected(TreeItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        for (TreeItem selected : tree.getSelection())
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
        Color frame = ListSelectionPalette.activeCellFrame(activeCellBackground(rowSelectionBackground()));
        try
        {
            e.gc.setForeground(frame);
            e.gc.drawRectangle(bounds.x, bounds.y, Math.max(0, bounds.width - 1),
                Math.max(0, bounds.height - 1));
        }
        finally
        {
            if (!frame.isDisposed())
                frame.dispose();
        }
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
        return rowAtY(tree, tree.getItems(), y, 0);
    }

    private static TreeItem rowAtY(Tree tree, TreeItem[] items, int y, int depth)
    {
        if (items == null || depth > 32)
            return null;
        for (TreeItem item : items)
        {
            Rectangle bounds = rowBounds(tree, item);
            if (bounds != null && y >= bounds.y && y < bounds.y + bounds.height)
                return item;
            if (item.getExpanded() && item.getItemCount() > 0)
            {
                TreeItem found = rowAtY(tree, item.getItems(), y, depth + 1);
                if (found != null)
                    return found;
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
