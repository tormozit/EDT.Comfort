package tormozit;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Выбор ячейки и подсветка активной ячейки/строки в многоколоночном {@link Tree} — то же
 * поведение, что у таблиц плагина ({@link FormTableInteraction}) и панели «Индексирование Git»,
 * но для чужого (штатного) дерева: дерева элементов формы с колонками плагина и дерева прав роли.
 *
 * <p>Зачем отдельно от {@link FormTableInteraction}: тот работает с {@link org.eclipse.swt.widgets.Table}
 * и создаёт таблицу «под себя» (оверлей заголовка, порядок колонок), а здесь дерево уже создано
 * EDT со своим стилем и своими слушателями — трогать можно только рисование и выбор ячейки.
 * В дереве прав роли используется режим {@link #installHighlightOnly(Tree, TreeViewer)}: выбор
 * строк и редактирование остаются за EDT.
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
 * <p>Ctrl+C сам этот класс не трогает. Штатное копирование дерева (в редакторе формы —
 * копирование элемента формы) остаётся за первой колонкой; вызывающий код может подключить
 * {@link CopyCommandSupport#wireCopyOverride(org.eclipse.swt.widgets.Control, java.util.function.BooleanSupplier)}
 * и по {@link #activeColumn()} копировать текст ячейки только в своих колонках (см.
 * {@code FormEditorHook.ItemsTree}).
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

    /** В чужом редактируемом дереве оставляем выбор строк и правки его штатным слушателям. */
    private final boolean highlightOnly;

    private TreeItem selectedItem;

    /**
     * Строка последнего клика, пока выделение дерева ещё не переехало на неё. Живёт до конца
     * текущего цикла событий (см. {@link #clearPendingRowLater()}) и только для отрисовки.
     */
    private TreeItem pendingRow;

    private int activeColumn;

    private boolean columnActivated;

    private Listener selectionPaintFilter;

    private Color ownedRowBg;

    private Color ownedInactiveRowBg;

    private Color ownedActiveCellBg;

    private Color ownedFrame;

    private Color ownedColumnTint;

    /**
     * Снимок полного выделения используется при проверке строк и выборе текущей строки.
     * Во время отрисовки снимок проверяется по исходному SWT.SELECTED каждой ячейки,
     * до слушателей JFace; расхождение сбрасывает снимок. Это учитывает программный выбор,
     * не требуя полного чтения выделения на каждый кадр. Вне отрисовки срок жизни — {@link #SELECTION_SNAPSHOT_MS}
     * после окончания чтения; события выбора сбрасывают его сразу.
     */
    private TreeItem[] selectionSnapshot;

    private long selectionSnapshotAt;

    private int paintCellDepth;

    private final Map<TreeItem, Integer> visibleRowIndexes = new WeakHashMap<>();

    /** Срок жизни снимка выделения, мс. */
    private static final long SELECTION_SNAPSHOT_MS = 20;

    private FormTreeInteraction(Tree tree, TreeViewer viewer, boolean highlightOnly)
    {
        this.tree = tree;
        this.viewer = viewer;
        this.highlightOnly = highlightOnly;
    }

    /** Подключить к дереву (идемпотентно). */
    static FormTreeInteraction install(Tree tree, TreeViewer viewer)
    {
        if (tree == null || tree.isDisposed())
            return null;
        if (tree.getData(INSTALLED_KEY) instanceof FormTreeInteraction existing)
            return existing;
        FormTreeInteraction interaction = new FormTreeInteraction(tree, viewer, false);
        tree.setData(INSTALLED_KEY, interaction);
        interaction.hook();
        return interaction;
    }

    /** Подсветка активной ячейки без вмешательства в выбор строк и редактирование дерева EDT. */
    static FormTreeInteraction installHighlightOnly(Tree tree, TreeViewer viewer)
    {
        if (tree == null || tree.isDisposed())
            return null;
        if (tree.getData(INSTALLED_KEY) instanceof FormTreeInteraction existing)
            return existing;
        FormTreeInteraction interaction = new FormTreeInteraction(tree, viewer, true);
        tree.setData(INSTALLED_KEY, interaction);
        interaction.hook();
        return interaction;
    }

    static FormTreeInteraction of(Tree tree)
    {
        return tree != null && !tree.isDisposed()
            && tree.getData(INSTALLED_KEY) instanceof FormTreeInteraction interaction ? interaction : null;
    }

    private void redrawHighlightColumn(int column)
    {
        if (column < 3 || column >= tree.getColumnCount())
            return;
        TreeItem top = tree.getTopItem();
        if (top == null || top.isDisposed())
            return;
        Rectangle bounds = top.getBounds(column);
        Rectangle client = tree.getClientArea();
        Rectangle dirty = new Rectangle(bounds.x, client.y, bounds.width, client.height).intersection(client);
        if (dirty.isEmpty())
            return;
        tree.redraw(dirty.x, dirty.y, dirty.width, dirty.height, false);
    }

    private void redrawHighlightOnFocusChange()
    {
        if (columnActivated)
            redrawHighlightColumn(activeColumn());
        redrawRow(activeRow());
        redrawRowsFromSelection();
    }

    private void redrawRowsFromSelection()
    {
        for (TreeItem item : selection())
            redrawRow(item);
    }

    private void handlePaintCell(Event event, Listener action)
    {
        paintCellDepth++;
        try
        {
            action.handleEvent(event);
        }
        finally
        {
            paintCellDepth--;
        }
    }

    private void validateSelectionOnPaint(Event event)
    {
        if (event.widget != tree || !(event.item instanceof TreeItem item) || selectionSnapshot == null)
            return;
        // Без FULL_SELECTION SWT сообщает SELECTED только для первой видимой колонки.
        if ((tree.getStyle() & SWT.FULL_SELECTION) == 0)
        {
            int[] order = tree.getColumnOrder();
            int firstColumn = order.length > 0 ? order[0] : 0;
            if (event.index != firstColumn)
                return;
        }
        boolean cachedSelected = false;
        for (TreeItem selected : selectionSnapshot)
        {
            if (selected == item)
            {
                cachedSelected = true;
                break;
            }
        }
        // Display-фильтр выполняется раньше OwnerDrawLabelProvider.erase(), который
        // может снять SWT.SELECTED ради своей заливки. Здесь флаг ещё задан самим SWT.
        if (cachedSelected != ((event.detail & SWT.SELECTED) != 0))
        {
            invalidateSelection();
        }
    }

    private void hook()
    {
        ListSelectionThemeColors.markOptOut(tree);
        ThemeAwareColors.hideGridLinesInDarkTheme(tree);
        selectionPaintFilter = this::validateSelectionOnPaint;
        tree.getDisplay().addFilter(SWT.EraseItem, selectionPaintFilter);
        tree.addListener(SWT.MouseDown, highlightOnly ? this::onHighlightOnlyMouseDown : this::onMouseDown);
        tree.addListener(SWT.EraseItem, event -> handlePaintCell(event, this::onEraseItem));
        tree.addListener(SWT.PaintItem, event -> handlePaintCell(event, this::onPaintItem));
        if (highlightOnly)
            tree.addListener(SWT.Paint, this::onPaintEmptyRows);
        tree.addListener(SWT.FocusIn, event -> {
            invalidateColors();
            if (highlightOnly)
                redrawHighlightOnFocusChange();
            else
                redrawRow(activeRow());
        });
        tree.addListener(SWT.FocusOut, event -> {
            invalidateColors();
            if (highlightOnly)
                redrawHighlightOnFocusChange();
            else
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
        tree.addListener(SWT.Dispose, event -> {
            tree.getDisplay().removeFilter(SWT.EraseItem, selectionPaintFilter);
            invalidateColors();
            invalidateSelection();
            visibleRowIndexes.clear();
        });
    }

    /** Индекс активной колонки (0, если ещё не выбрана). */
    int activeColumn()
    {
        int column = activeColumn;
        return column >= 0 && column < tree.getColumnCount() ? column : 0;
    }

    /** Сделать колонку текущей после программного перехода к строке. */
    void activateColumn(int column)
    {
        if (tree.isDisposed() || column < 0 || column >= tree.getColumnCount())
            return;
        TreeItem previous = activeRow();
        int previousColumn = columnActivated ? activeColumn() : -1;
        activeColumn = column;
        if (highlightOnly)
            columnActivated = true;
        invalidateSelection();
        syncFromSelection();
        invalidateColors();
        if (highlightOnly)
        {
            redrawHighlightColumn(previousColumn);
            if (column != previousColumn)
                redrawHighlightColumn(column);
            redrawRow(previous);
            redrawRowsFromSelection();
        }
        else
        {
            redrawRow(previous);
            redrawRow(activeRow());
        }
        tree.update();
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

    private void onHighlightOnlyMouseDown(Event e)
    {
        if (e.button != 1 && e.button != 3)
            return;
        TreeItem item = rowAt(tree, e.x, e.y);
        int column = item != null ? columnAtX(tree, e.x) : -1;
        if (column < 0)
            return;
        if (e.button == 1 && column == 0)
        {
            Rectangle content = item.getBounds(0);
            if (content != null && e.x < content.x)
                return;
        }
        int previousColumn = columnActivated ? activeColumn() : -1;
        activeColumn = column;
        columnActivated = true;
        invalidateColors();
        redrawHighlightColumn(previousColumn);
        if (column != previousColumn)
            redrawHighlightColumn(column);
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
        if (selectionSnapshot == null
            || paintCellDepth == 0 && now - selectionSnapshotAt > SELECTION_SNAPSHOT_MS)
        {
            selectionSnapshot = tree.getSelection();
            selectionSnapshotAt = System.currentTimeMillis();
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
        boolean activeColumnCell = highlightOnly && columnActivated && activeColumn() >= 3
            && e.index == activeColumn();
        boolean selected = isRowSelected(item);
        if (!selected && item != active && !activeColumnCell)
            return;
        boolean activeRow = item == active;
        Color rowBg = activeRow ? rowSelectionBackground() : inactiveRowSelectionBackground();
        Color bg = highlightOnly && activeColumnCell && !selected
            ? columnTintBackground()
            : !highlightOnly && activeRow && e.index == activeColumn()
                ? activeCellBackground(rowBg) : rowBg;
        e.gc.setBackground(bg);
        Rectangle fill = activeColumnCell ? item.getBounds(e.index) : null;
        if (fill != null && !fill.isEmpty())
            e.gc.fillRectangle(fill);
        else
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
        if (!(e.item instanceof TreeItem item) || item != activeRow() || e.index != activeColumn()
            || highlightOnly && activeColumn() < 3)
            return;
        Rectangle bounds = item.getBounds(e.index);
        if (bounds == null || bounds.isEmpty())
            return;
        if (highlightOnly && columnActivated)
        {
            Color previousForeground = e.gc.getForeground();
            int previousWidth = e.gc.getLineWidth();
            try
            {
                e.gc.setForeground(tree.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND));
                e.gc.setLineWidth(2);
                e.gc.drawRectangle(bounds.x + 1, bounds.y + 1, Math.max(0, bounds.width - 3),
                    Math.max(0, bounds.height - 3));
            }
            finally
            {
                e.gc.setLineWidth(previousWidth);
                e.gc.setForeground(previousForeground);
            }
            return;
        }
        // Цвет рамки кэшируется наравне с остальными: создание и освобождение нативного Color
        // на каждую отрисовку ячейки было вторым по стоимости местом при прокрутке.
        if (ownedFrame == null || ownedFrame.isDisposed())
            ownedFrame = ListSelectionPalette.activeCellFrame(
                activeCellBackground(rowSelectionBackground()));
        e.gc.setForeground(ownedFrame);
        e.gc.drawRectangle(bounds.x, bounds.y, Math.max(0, bounds.width - 1),
            Math.max(0, bounds.height - 1));
    }

    /** Продолжить фон колонки под последней строкой, где SWT уже не шлёт EraseItem. */
    private void onPaintEmptyRows(Event e)
    {
        if (!columnActivated || activeColumn() < 3 || tree.isDisposed())
            return;
        TreeItem top = tree.getTopItem();
        if (top == null || top.isDisposed())
            return;
        Rectangle column = top.getBounds(activeColumn());
        Rectangle client = tree.getClientArea();
        if (column == null || column.isEmpty() || client.isEmpty())
            return;
        int bottom = client.y;
        int limit = client.height / Math.max(tree.getItemHeight(), 1) + 2;
        int seen = 0;
        VisibleRowCursor cursor = new VisibleRowCursor(tree, top, visibleRowIndexes);
        for (TreeItem row = top; row != null && seen < limit; row = cursor.next())
        {
            seen++;
            Rectangle bounds = rowBounds(tree, row);
            if (bounds == null)
                continue;
            if (bounds.y >= client.y + client.height)
                break;
            bottom = Math.max(bottom, bounds.y + bounds.height);
            if (bottom >= client.y + client.height)
                break;
        }
        if (bottom >= client.y + client.height)
            return;
        e.gc.setBackground(columnTintBackground());
        e.gc.fillRectangle(column.x, bottom, column.width, client.y + client.height - bottom);
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

    private Color columnTintBackground()
    {
        if (ownedColumnTint == null || ownedColumnTint.isDisposed())
        {
            Color base = tree.getBackground();
            if (!tree.isFocusControl())
                ownedColumnTint = ListSelectionThemeColors.isDarkList(tree)
                    ? ListSelectionPalette.rowSelectionBackground(tree, PALETTE)
                    : ListSelectionPalette.slightlyDarker(base, 0.12);
            else
            {
                RGB background = base.getRGB();
                RGB accent = tree.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
                double share = 0.12;
                ownedColumnTint = new Color(tree.getDisplay(),
                    (int)Math.round(background.red * (1 - share) + accent.red * share),
                    (int)Math.round(background.green * (1 - share) + accent.green * share),
                    (int)Math.round(background.blue * (1 - share) + accent.blue * share));
            }
        }
        return ownedColumnTint;
    }

    private void invalidateColors()
    {
        ownedRowBg = disposed(ownedRowBg);
        ownedInactiveRowBg = disposed(ownedInactiveRowBg);
        ownedActiveCellBg = disposed(ownedActiveCellBg);
        ownedFrame = disposed(ownedFrame);
        ownedColumnTint = disposed(ownedColumnTint);
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
        TreeItem top = tree.getTopItem();
        FormTreeInteraction interaction = of(tree);
        Map<TreeItem, Integer> indexes = interaction != null ? interaction.visibleRowIndexes : new WeakHashMap<>();
        VisibleRowCursor cursor = new VisibleRowCursor(tree, top, indexes);
        for (TreeItem row = top; row != null && seen < limit; row = cursor.next())
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

    /** Обход раскрытых строк через публичный SWT API, без массивов всех соседей на каждый шаг. */
    private static final class VisibleRowCursor
    {
        private final Tree tree;

        private final ArrayDeque<Level> levels = new ArrayDeque<>();

        private final Map<TreeItem, Integer> indexes;

        private TreeItem current;

        VisibleRowCursor(Tree tree, TreeItem first, Map<TreeItem, Integer> indexes)
        {
            this.tree = tree;
            this.indexes = indexes;
            current = first;
            // Начальные индексы ищем один раз на уровень, затем увеличиваем при переходе.
            // Верхняя строка может быть потомком: сохраняем и путь к её корневому узлу.
            for (TreeItem item = first; item != null;)
            {
                TreeItem parent = item.getParentItem();
                Integer cached = indexes.get(item);
                // Перестройка дерева могла сдвинуть индекс: всегда проверяем сам объект.
                boolean valid = cached != null && cached >= 0 && itemAt(parent, cached) == item;
                int index = valid ? cached : parent != null ? parent.indexOf(item) : tree.indexOf(item);
                indexes.put(item, index);
                levels.addFirst(new Level(parent, index));
                item = parent;
            }
        }

        TreeItem next()
        {
            if (current == null || current.isDisposed())
                return null;
            TreeItem child = current.getExpanded() ? itemAt(current, 0) : null;
            if (child != null)
            {
                levels.addLast(new Level(current, 0));
                current = child;
                indexes.put(current, 0);
                return current;
            }
            while (!levels.isEmpty())
            {
                Level level = levels.peekLast();
                int nextIndex = level.index + 1;
                TreeItem sibling = itemAt(level.parent, nextIndex);
                if (sibling != null)
                {
                    level.index = nextIndex;
                    current = sibling;
                    indexes.put(current, nextIndex);
                    return current;
                }
                levels.removeLast();
            }
            current = null;
            return null;
        }

        private TreeItem itemAt(TreeItem parent, int index)
        {
            try
            {
                return parent != null ? parent.getItem(index) : tree.getItem(index);
            }
            catch (IllegalArgumentException e)
            {
                // getItem(index) документированно бросает ERROR_INVALID_RANGE при
                // отсутствии такого ребёнка. Это конец списка, без getItemCount().
                return null;
            }
        }

        private static final class Level
        {
            private final TreeItem parent;

            private int index;

            Level(TreeItem parent, int index)
            {
                this.parent = parent;
                this.index = index;
            }
        }
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
