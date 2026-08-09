package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Отбор по значению ячейки для {@code Tree}/{@code TreeViewer} — компактный переиспользуемый
 * аналог соответствующей части {@link FormTableInteraction} (issue #266, п.2–5): «Отобрать/Снять
 * отбор по значению ячейки», «Различные значения колонки», «Отключить все отборы», «Отобрано
 * элементов: N». Не поддерживает режим заполнения ширины из issue (п.1). Значок в заголовке (п.6)
 * — тот требует overlay поверх {@code TableColumn}/{@code columnHost}, которого у штатного
 * EGit-дерева нет; попытка через отдельный плавающий {@code Shell} была откачена —
 * рассинхронизация с заголовком при скролле (два независимо композируемых окна). Вместо него —
 * просто « *» в конце текста заголовка отфильтрованной колонки ({@link #updateColumnHeaderMarkers}),
 * без окон и пересчёта координат.
 *
 * <p>Пункты меню — общий код с {@link FormTableInteraction} через {@link ColumnFilterMenuBuilder}:
 * та же группа («Отобрать/Снять отбор», «Различные значения колонки», «Отключить все отборы»,
 * «Отобрано элементов: N»), тот же способ подключения к меню — {@code SWT.Show} пересобирает группу
 * заново при каждом показе (см. {@link #rebuildFilterMenuItems}, по образцу {@code
 * FormTableInteraction.ensureCopyMenu}). Группа всегда идёт в КОРЕНЬ штатного контекстного меню
 * {@code tree} (не в подменю «Комфорт»), обрамлённая разделителями — штатное меню EGit не
 * переопределяется, только дополняется.
 *
 * <p>Дерево может быть иерархическим (папки/группы) — учитывается через {@code
 * ITreeContentProvider.hasChildren}: узел без потомков считается листом (участвует в отборе и
 * подсчёте различных значений), узел с потомками — техническая группировка (сама никогда не
 * фильтруется по значению, видна, если отбору соответствует хоть один потомок-лист), по образцу
 * рекурсии в {@code GitStagingFilterHook.GitStagingSearchFilter}.
 */
final class TreeColumnValueFilterSupport implements ColumnValuesDialog.Owner, ColumnFilterMenuBuilder.Owner
{
    private static final String MENU_MARKER = "tormozit.treeColumnValueFilterMenu"; //$NON-NLS-1$

    /** Деревья с установленным взаимодействием — для поиска цели общих команд Alt+W/Alt+F по фокусу. */
    private static final Map<Control, TreeColumnValueFilterSupport> INSTANCES = new ConcurrentHashMap<>();

    /** Точка входа для {@link ColumnFilterMenuBuilder#FILTER_COMMAND_ID} (Alt+W) — общей с {@link FormTableInteraction}. Не находит цель — тихо ничего не делает. */
    static void toggleFilterOnFocusedTree()
    {
        Display display = Display.getCurrent();
        if (display == null)
            return;
        for (Control c = display.getFocusControl(); c != null && !c.isDisposed(); c = c.getParent())
        {
            TreeColumnValueFilterSupport support = INSTANCES.get(c);
            if (support != null)
            {
                support.toggleActiveCellFilter();
                return;
            }
        }
    }

    /** Точка входа для {@link ColumnFilterMenuBuilder#COLUMN_VALUES_COMMAND_ID} (Alt+F) — общей с {@link FormTableInteraction}. Не находит цель — тихо ничего не делает. */
    static void openColumnValuesOnFocusedTree()
    {
        Display display = Display.getCurrent();
        if (display == null)
            return;
        for (Control c = display.getFocusControl(); c != null && !c.isDisposed(); c = c.getParent())
        {
            TreeColumnValueFilterSupport support = INSTANCES.get(c);
            if (support != null)
            {
                support.openColumnValuesDialog();
                return;
            }
        }
    }

    /** Текст ячейки по ЭЛЕМЕНТУ МОДЕЛИ (не живому {@code TreeItem}) для отбора/подсчёта различных значений; {@code null} — колонка не поддерживает отбор. */
    @FunctionalInterface
    interface CellTextResolver
    {
        String cellText(Object element, int column);
    }

    private final TreeViewer viewer;
    private final Tree tree;
    private final CellTextResolver textResolver;
    private final Supplier<Object> activeElementSupplier;
    private final Supplier<Integer> activeColumnSupplier;

    /** Отбор по значению: физический индекс колонки → эталонное значение ячейки (AND между колонками). */
    private final Map<Integer, String> columnValueFilters = new LinkedHashMap<>();
    private ViewerFilter viewerFilter;
    private final List<MenuItem> filterMenuItems = new ArrayList<>();
    /** Заголовок колонки ДО пометки «*» — чтобы восстановить при снятии отбора (см. {@link #updateColumnHeaderMarkers}). */
    private final Map<Integer, String> originalColumnHeaderText = new LinkedHashMap<>();

    TreeColumnValueFilterSupport(TreeViewer viewer, Tree tree, CellTextResolver textResolver,
        Supplier<Object> activeElementSupplier, Supplier<Integer> activeColumnSupplier)
    {
        this.viewer = viewer;
        this.tree = tree;
        this.textResolver = textResolver;
        this.activeElementSupplier = activeElementSupplier;
        this.activeColumnSupplier = activeColumnSupplier;
    }

    void install()
    {
        INSTANCES.put(tree, this);
        tree.addDisposeListener(e -> INSTANCES.remove(tree));
        installViewerFilter();
        installMenu();
    }

    // -----------------------------------------------------------------------
    // Отбор: применение/снятие
    // -----------------------------------------------------------------------

    private void installViewerFilter()
    {
        viewerFilter = new ViewerFilter()
        {
            @Override
            public boolean select(Viewer v, Object parentElement, Object element)
            {
                return selectRecursive(element);
            }
        };
        viewer.addFilter(viewerFilter);
    }

    /** Узел без потомков — по своему отбору; узел с потомками — виден, если виден хоть один потомок. */
    private boolean selectRecursive(Object element)
    {
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider tcp))
            return true;
        if (!tcp.hasChildren(element))
            return matchesAllColumnFilters(element);
        for (Object child : tcp.getChildren(element))
        {
            if (selectRecursive(child))
                return true;
        }
        return false;
    }

    private boolean matchesAllColumnFilters(Object element)
    {
        for (Map.Entry<Integer, String> entry : columnValueFilters.entrySet())
        {
            String text = textResolver.cellText(element, entry.getKey().intValue());
            if (!entry.getValue().equals(text != null ? text : "")) //$NON-NLS-1$
                return false;
        }
        return true;
    }

    @Override
    public void applyColumnFilterValue(int column, String value)
    {
        if (column < 0 || value == null)
            return;
        columnValueFilters.put(Integer.valueOf(column), value);
        viewer.refresh();
        updateColumnHeaderMarkers();
    }

    @Override
    public void clearColumnFilter(int column)
    {
        if (columnValueFilters.remove(Integer.valueOf(column)) == null)
            return;
        viewer.refresh();
        updateColumnHeaderMarkers();
    }

    @Override
    public void clearAllFilters()
    {
        if (columnValueFilters.isEmpty())
            return;
        columnValueFilters.clear();
        viewer.refresh();
        updateColumnHeaderMarkers();
    }

    /**
     * Простая замена значку в заголовке (тот — тупиковый путь, см. class-javadoc): у заголовка
     * отфильтрованной колонки в конец текста добавляется « *» — без окон/пересчёта координат,
     * только {@code TreeColumn.setText}.
     */
    private void updateColumnHeaderMarkers()
    {
        int count = tree.getColumnCount();
        for (int i = 0; i < count; i++)
        {
            TreeColumn col = tree.getColumn(i);
            if (col.isDisposed())
                continue;
            Integer key = Integer.valueOf(i);
            boolean filtered = columnValueFilters.containsKey(key);
            if (filtered)
            {
                String original = originalColumnHeaderText.get(key);
                if (original == null)
                {
                    original = col.getText();
                    originalColumnHeaderText.put(key, original);
                }
                String marked = original + " *"; //$NON-NLS-1$
                if (!marked.equals(col.getText()))
                    col.setText(marked);
            }
            else
            {
                String original = originalColumnHeaderText.remove(key);
                if (original != null && !original.equals(col.getText()))
                    col.setText(original);
            }
        }
    }

    /** Текст заголовка {@code column} без «* »-пометки (для описаний/заголовка диалога — не задваивать звёздочку). */
    private String cleanColumnHeaderText(int column)
    {
        String original = originalColumnHeaderText.get(Integer.valueOf(column));
        return original != null ? original : tree.getColumn(column).getText();
    }

    @Override
    public boolean hasActiveFilters()
    {
        return !columnValueFilters.isEmpty();
    }

    @Override
    public boolean canFilterByActiveCell()
    {
        Object element = activeElementSupplier.get();
        int column = activeColumnSupplier.get().intValue();
        return element != null && column >= 0 && textResolver.cellText(element, column) != null;
    }

    /** По активной колонке уже наложен отбор ровно по значению активной ячейки. */
    @Override
    public boolean isActiveCellFiltered()
    {
        int column = activeColumnSupplier.get().intValue();
        String applied = columnValueFilters.get(Integer.valueOf(column));
        if (applied == null)
            return false;
        Object element = activeElementSupplier.get();
        if (element == null)
            return false;
        String value = textResolver.cellText(element, column);
        return value != null && applied.equals(value);
    }

    @Override
    public void toggleActiveCellFilter()
    {
        int column = activeColumnSupplier.get().intValue();
        if (isActiveCellFiltered())
        {
            clearColumnFilter(column);
            return;
        }
        Object element = activeElementSupplier.get();
        if (element == null)
            return;
        String value = textResolver.cellText(element, column);
        if (value == null)
            return;
        applyColumnFilterValue(column, value);
    }

    @Override
    public boolean canBrowseColumnValues()
    {
        int column = activeColumnSupplier.get().intValue();
        return column >= 0 && column < tree.getColumnCount();
    }

    @Override
    public Image clearAllIcon()
    {
        return ColumnFilterMenuBuilder.filterGlyph(tree.getDisplay());
    }

    // -----------------------------------------------------------------------
    // Различные значения колонки
    // -----------------------------------------------------------------------

    private List<ColumnValuesDialog.ValueRow> computeColumnValueRows(int column)
    {
        return computeColumnValueRows(column, true);
    }

    /**
     * Различные значения {@code column} + число строк-листьев с этим значением.
     * {@code honorOtherFilters=true} — среди строк, проходящих ОСТАЛЬНЫЕ отборы (свой по
     * {@code column} всегда игнорируется — иначе список сужался бы до одного текущего значения;
     * сторонние {@code ViewerFilter} — учитываются). {@code false} — по ВСЕМ строкам-листьям,
     * полностью игнорируя прочие отборы (флажок «Учитывать отбор» в окне снят).
     */
    @Override
    public List<ColumnValuesDialog.ValueRow> computeColumnValueRows(int column, boolean honorOtherFilters)
    {
        List<ColumnValuesDialog.ValueRow> result = new ArrayList<>();
        if (column < 0)
            return result;
        ViewerFilter[] otherFilters = honorOtherFilters ? viewer.getFilters() : new ViewerFilter[0];
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object element : collectLeaves())
        {
            boolean matches = true;
            for (ViewerFilter filter : otherFilters)
            {
                if (filter == viewerFilter)
                    continue;
                if (!filter.select(viewer, null, element))
                {
                    matches = false;
                    break;
                }
            }
            if (matches && honorOtherFilters)
            {
                for (Map.Entry<Integer, String> entry : columnValueFilters.entrySet())
                {
                    if (entry.getKey().intValue() == column)
                        continue;
                    String text = textResolver.cellText(element, entry.getKey().intValue());
                    if (!entry.getValue().equals(text != null ? text : "")) //$NON-NLS-1$
                    {
                        matches = false;
                        break;
                    }
                }
            }
            if (!matches)
                continue;
            String value = textResolver.cellText(element, column);
            if (value == null)
                continue;
            counts.merge(value, Integer.valueOf(1), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet())
            result.add(new ColumnValuesDialog.ValueRow(entry.getKey(), entry.getValue().intValue()));
        return result;
    }

    /** Текущий отбор БЕЗ {@code excludedColumn} — для поля «текущий отбор» в {@link ColumnValuesDialog}. */
    private String activeFiltersDescriptionExcluding(int excludedColumn)
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, String> entry : columnValueFilters.entrySet())
        {
            int column = entry.getKey().intValue();
            if (column == excludedColumn)
                continue;
            if (!first)
                sb.append(" И "); //$NON-NLS-1$
            else
                sb.append("Отбор: "); //$NON-NLS-1$
            first = false;
            String header = column >= 0 && column < tree.getColumnCount()
                ? cleanColumnHeaderText(column)
                : null;
            if (header == null || header.isEmpty())
                header = "Колонка " + (column + 1); //$NON-NLS-1$
            sb.append(header).append(" = \"").append(entry.getValue()).append('"'); //$NON-NLS-1$
        }
        return sb.toString();
    }

    @Override
    public String activeFiltersDescription()
    {
        return activeFiltersDescriptionExcluding(-1);
    }

    private List<Object> collectLeaves()
    {
        List<Object> result = new ArrayList<>();
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider tcp))
            return result;
        Object input = viewer.getInput();
        if (input == null)
            return result;
        for (Object root : tcp.getElements(input))
            collectLeavesInto(tcp, root, result);
        return result;
    }

    private void collectLeavesInto(ITreeContentProvider tcp, Object element, List<Object> out)
    {
        if (!tcp.hasChildren(element))
        {
            out.add(element);
            return;
        }
        for (Object child : tcp.getChildren(element))
            collectLeavesInto(tcp, child, out);
    }

    /** Число видимых строк-листьев (после ВСЕХ отборов, включая сторонние) — читаем сам виджет, как {@code FormTableInteraction}. */
    @Override
    public int filteredElementCount()
    {
        return countLeaves(tree.getItems());
    }

    private int countLeaves(TreeItem[] items)
    {
        int count = 0;
        for (TreeItem item : items)
        {
            TreeItem[] children = item.getItems();
            count += children.length == 0 ? 1 : countLeaves(children);
        }
        return count;
    }

    @Override
    public void openColumnValuesDialog()
    {
        openColumnValuesDialog(activeColumnSupplier.get().intValue());
    }

    private void openColumnValuesDialog(int column)
    {
        if (column < 0 || column >= tree.getColumnCount())
            return;
        String header = cleanColumnHeaderText(column);
        Object activeElement = activeElementSupplier.get();
        String initialValue = activeElement != null ? textResolver.cellText(activeElement, column) : null;
        List<ColumnValuesDialog.ValueRow> rows = computeColumnValueRows(column, true);
        String originalFilterValue = columnValueFilters.get(Integer.valueOf(column));
        String otherFiltersDescription = activeFiltersDescriptionExcluding(column);
        new ColumnValuesDialog(tree.getShell(), this, column, header, rows, initialValue, originalFilterValue,
            otherFiltersDescription).open();
    }

    // -----------------------------------------------------------------------
    // Контекстное меню (корень штатного меню Tree, группа обрамлена разделителями)
    // -----------------------------------------------------------------------

    private void installMenu()
    {
        Menu menu = tree.getMenu();
        if (menu == null)
        {
            Debug.log("installMenu: no context menu yet"); //$NON-NLS-1$
            return;
        }
        if (menu.isDisposed() || Boolean.TRUE.equals(menu.getData(MENU_MARKER)))
            return;
        menu.setData(MENU_MARKER, Boolean.TRUE);
        // Пункты зависят от активной ячейки и от набора наложенных фильтров, а у SWT MenuItem нет
        // setVisible — поэтому набор пересобирается на каждом показе, как у
        // FormTableInteraction.ensureCopyMenu.
        menu.addListener(SWT.Show, ev -> rebuildFilterMenuItems(menu));
    }

    private void rebuildFilterMenuItems(Menu menu)
    {
        for (MenuItem item : filterMenuItems)
        {
            if (!item.isDisposed())
                item.dispose();
        }
        filterMenuItems.clear();
        if (menu.isDisposed())
            return;
        ColumnFilterMenuBuilder.rebuildFilterMenuItems(menu, filterMenuItems, this);
    }

    // -----------------------------------------------------------------------
    // Логи
    // -----------------------------------------------------------------------

    private static final class Debug
    {
        private static final String TAG = "TreeColumnValueFilter"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
