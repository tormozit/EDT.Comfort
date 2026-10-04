package tormozit;

import java.util.Set;

import org.eclipse.nebula.widgets.grid.Grid;
import org.eclipse.nebula.widgets.grid.GridItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;

import com._1c.g5.v8.dt.common.ui.widgets.tableex.TableEx;
import com._1c.g5.v8.dt.common.ui.widgets.tableex.TableExColumn;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaControlContext;
import com._1c.g5.v8.dt.dcs.ui.EditorPage;

/**
 * Подсветка шапки текущей колонки в списке {@link TableEx}. Работает везде, где EDT строит
 * страницы СКД на {@link DataCompositionSchemaControlContext}: редактор макета схемы компоновки
 * ({@link DataCompositionSchemaEditorHook}) и окно настроек динамического списка
 * ({@link DynamicListSettingsDialogHook}).
 *
 * <p>Строка такого списка «многоэтажная»: одна строка данных — несколько строк Nebula
 * {@code Grid}, и ячейки одной колонки грида на разных этажах относятся к разным колонкам
 * списка. Шапка — отдельный {@code Grid} с той же раскладкой, поэтому по одной лишь
 * выделенной ячейке не видно, под каким она заголовком.
 *
 * <p>Колонка списка по ячейке данных — {@code TableEx.getColumnByGridColumn}; адрес её ячейки
 * в шапке — {@code TableExColumn.getPoint()} ({@code y} — строка шапки, {@code x} — колонка
 * грида). Рисуем слушателем {@link SWT#Paint} самой шапки: он добавлен позже слушателя
 * {@code Grid}, поэтому выполняется после штатной отрисовки.
 */
final class TableExHeaderAccent
{
    /** Простые имена страниц-композитов конструктора СКД, списки которых подсвечиваем. */
    private static final Set<String> PAGES =
        Set.of("DataSets", "Links", "CalculatedFields", "Parameters", "Resources"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    private static final String ATTACHED_KEY = "tormozit.tableExHeaderAccent"; //$NON-NLS-1$
    private static final int ACCENT_HEIGHT = 2;
    private static final int TINT_ALPHA = 18;
    private static final int LINE_ALPHA = 130;

    private final TableEx table;
    private final Grid header;
    private final Grid data;
    /** Колонка, шапка которой подсвечена последней отрисовкой. */
    private TableExColumn shown;

    private TableExHeaderAccent(TableEx table)
    {
        this.table = table;
        this.header = table.getHeaderControl();
        this.data = table.getDataControl();
    }

    /** Подключает подсветку к спискам страниц СКД контекста; повторный вызов ничего не делает. */
    static void install(DataCompositionSchemaControlContext context)
    {
        if (context == null)
            return;
        for (EditorPage editorPage : context.getPages())
        {
            if (!(editorPage instanceof Composite pageComposite) || pageComposite.isDisposed())
                continue;
            if (PAGES.contains(editorPage.getClass().getSimpleName()))
                attachDescendants(pageComposite);
        }
    }

    private static void attachDescendants(Composite root)
    {
        if (root == null || root.isDisposed())
            return;
        for (Control child : root.getChildren())
        {
            if (child instanceof TableEx table)
                attach(table);
            else if (child instanceof Composite composite)
                attachDescendants(composite);
        }
    }

    private static void attach(TableEx table)
    {
        if (table.getData(ATTACHED_KEY) != null)
            return;
        TableExHeaderAccent accent = new TableExHeaderAccent(table);
        if (accent.header == null || accent.data == null)
            return;
        table.setData(ATTACHED_KEY, Boolean.TRUE);
        accent.header.addListener(SWT.Paint, accent::paintHeader);
        // Смена текущей ячейки всегда перерисовывает данные (у ячейки меняется фон), поэтому
        // отрисовка данных — единая точка, где замечаем смену колонки: и мышь, и клавиши,
        // и программное выделение.
        accent.data.addListener(SWT.Paint, event -> accent.syncHeader());
        accent.data.addListener(SWT.Selection, event -> accent.syncHeader());
    }

    private void syncHeader()
    {
        if (header.isDisposed() || currentColumn() == shown)
            return;
        header.redraw();
    }

    private TableExColumn currentColumn()
    {
        if (data.isDisposed())
            return null;
        GridItem item = data.getFocusItem();
        Point cell = data.getFocusCell();
        if (item == null || item.isDisposed() || cell == null || cell.x < 0)
            return null;
        return table.getColumnByGridColumn(item, cell.x);
    }

    private void paintHeader(Event event)
    {
        shown = currentColumn();
        Point cell = shown != null ? shown.getPoint() : null;
        if (cell == null || cell.x < 0 || cell.y < 0 || cell.y >= header.getItemCount()
            || cell.x >= header.getColumnCount())
            return;
        Rectangle bounds = header.getItem(cell.y).getBounds(cell.x);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
            return;
        GC gc = event.gc;
        gc.setBackground(header.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND));
        int alpha = gc.getAlpha();
        gc.setAlpha(TINT_ALPHA);
        gc.fillRectangle(bounds);
        gc.setAlpha(LINE_ALPHA);
        gc.fillRectangle(bounds.x, bounds.y + bounds.height - ACCENT_HEIGHT, bounds.width,
            ACCENT_HEIGHT);
        gc.setAlpha(alpha);
    }
}
