package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jface.dialogs.IDialogSettings;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.views.markers.MarkerField;
import org.eclipse.ui.views.markers.MarkerItem;

/**
 * Панели маркеров «Закладки» ({@link IPageLayout#ID_BOOKMARKS}) и «Задачи»
 * ({@link IPageLayout#ID_TASK_LIST}): колонки «Модуль» / «Метод», выбор ячейки
 * ({@link FormTreeInteraction}), раскладка колонок — issue
 * <a href="https://github.com/tormozit/EDT.Comfort/issues/372">#372</a>.
 *
 * <p>Штатные панели построены на {@link Tree}, не на {@link org.eclipse.swt.widgets.Table},
 * поэтому подключается {@link FormTreeInteraction} (аналог {@link FormTableInteraction} для
 * дерева).
 *
 * <p>Колонка «Модуль» — полное имя модуля BSL ({@link GetRef#pathToModuleRef}, например
 * {@code Справочник.Валюты.МодульМенеджера}). Колонка «Метод» — имя процедуры/функции по строке
 * маркера ({@link GetRef#findEnclosingMethodName}). Поля регистрируются как {@code markerField};
 * при старте дописываются в дескрипторы генераторов закладок и задач (в Eclipse 2023
 * {@code markerContentGeneratorExtension} ещё не подмешивает поля — платформенный #2193). При
 * открытии панели колонки дополнительно гарантируются в дереве (в т.ч. при memento без них).
 * Порядок и ширины колонок запоминаются в {@link IDialogSettings} при закрытии панели
 * (отдельно для «Закладок» и «Задач»).
 */
public final class MarkersListViewsHook implements IStartup
{
    static final String MODULE_FIELD_ID = "tormozit.comfort.bookmarkModuleField"; //$NON-NLS-1$
    static final String METHOD_FIELD_ID = "tormozit.comfort.bookmarkMethodField"; //$NON-NLS-1$

    private static final String MODULE_COLUMN_TITLE = "Модуль"; //$NON-NLS-1$
    private static final String METHOD_COLUMN_TITLE = "Метод"; //$NON-NLS-1$
    private static final String INSTALLED_KEY = "tormozit.markersListViewsHook"; //$NON-NLS-1$
    private static final String MODULE_COLUMN_KEY = "tormozit.markersModuleColumn"; //$NON-NLS-1$
    private static final String METHOD_COLUMN_KEY = "tormozit.markersMethodColumn"; //$NON-NLS-1$
    private static final int MODULE_COLUMN_WIDTH = 280;
    private static final int METHOD_COLUMN_WIDTH = 140;
    private static final int MIN_COLUMN_WIDTH = 20;
    private static final String SETTINGS_SECTION_BOOKMARKS = "BookmarksView"; //$NON-NLS-1$
    private static final String SETTINGS_SECTION_TASKS = "TasksView"; //$NON-NLS-1$
    private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
    private static final String WIDTH_KEY_PREFIX = "colWidth."; //$NON-NLS-1$
    /** Ключ {@code TreeColumn.setData} в ExtendedMarkersView — {@link MarkerField}. */
    private static final String MARKER_FIELD_DATA = "MARKER_FIELD"; //$NON-NLS-1$
    private static final String HEADER_SORT_WIRED_KEY = "tormozit.markersHeaderSort"; //$NON-NLS-1$
    private static final String HEADER_SORT_TEXT_KEY = "tormozit.markersHeaderSortText"; //$NON-NLS-1$
    private static final String TREE_SORT_COLUMN_KEY = "tormozit.markersSortColumn"; //$NON-NLS-1$
    private static final String TREE_SORT_ASC_KEY = "tormozit.markersSortAsc"; //$NON-NLS-1$
    private static final String MARKER_SUPPORT_REGISTRY =
        "org.eclipse.ui.views.markers.internal.MarkerSupportRegistry"; //$NON-NLS-1$
    private static final String BOOKMARKS_GENERATOR = "org.eclipse.ui.ide.bookmarksGenerator"; //$NON-NLS-1$
    private static final String TASKS_GENERATOR = "org.eclipse.ui.ide.tasksGenerator"; //$NON-NLS-1$

    private static volatile boolean windowsHooked;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            patchGenerators();
            hookWorkbench();
        });
    }

    /**
     * Дописывает {@link ModuleField}/{@link MethodField} в {@code allFields}/{@code initialVisible}
     * генераторов закладок и задач. Реестр и дескриптор — internal API Eclipse, доступ только через
     * reflection.
     */
    private static void patchGenerators()
    {
        try
        {
            Class<?> registryClass = Class.forName(MARKER_SUPPORT_REGISTRY);
            Method getInstance = registryClass.getMethod("getInstance"); //$NON-NLS-1$
            Object registry = getInstance.invoke(null);
            if (registry == null)
                return;
            // Сначала «Модуль», затем «Метод» — так колонки идут в initialVisible на чистой раскладке.
            patchGenerator(registry, BOOKMARKS_GENERATOR);
            patchGenerator(registry, TASKS_GENERATOR);
        }
        catch (ReflectiveOperationException | RuntimeException ex)
        {
            // Колонки всё равно появятся через ensureComfortColumns.
        }
    }

    private static void patchGenerator(Object registry, String generatorId)
    {
        Object descriptor = Global.invoke(registry, "getContentGenDescriptor", generatorId); //$NON-NLS-1$
        if (descriptor == null)
            return;
        appendRegisteredField(registry, descriptor, MODULE_FIELD_ID);
        appendRegisteredField(registry, descriptor, METHOD_FIELD_ID);
    }


    private static boolean isTargetViewId(String viewId)
    {
        return IPageLayout.ID_BOOKMARKS.equals(viewId) || IPageLayout.ID_TASK_LIST.equals(viewId);
    }

    private static String settingsSectionFor(String viewId)
    {
        if (IPageLayout.ID_TASK_LIST.equals(viewId))
            return SETTINGS_SECTION_TASKS;
        return SETTINGS_SECTION_BOOKMARKS;
    }

    private static void appendRegisteredField(Object registry, Object descriptor, String fieldId)
    {
        Object fieldObj = Global.invoke(registry, "getField", fieldId); //$NON-NLS-1$
        if (!(fieldObj instanceof MarkerField field))
            return;
        appendField(descriptor, "allFields", field, fieldId); //$NON-NLS-1$
        appendField(descriptor, "initialVisible", field, fieldId); //$NON-NLS-1$
    }

    private static void appendField(Object descriptor, String arrayField, MarkerField field,
        String fieldId)
    {
        Object raw = Global.getField(descriptor, arrayField);
        if (!(raw instanceof MarkerField[] fields))
            return;
        for (MarkerField existing : fields)
        {
            if (existing == field)
                return;
            if (existing != null && fieldId.equals(configId(existing)))
                return;
        }
        MarkerField[] next = new MarkerField[fields.length + 1];
        System.arraycopy(fields, 0, next, 0, fields.length);
        next[fields.length] = field;
        Global.setField(descriptor, arrayField, next);
    }

    private static String configId(MarkerField field)
    {
        try
        {
            if (field.getConfigurationElement() != null)
                return field.getConfigurationElement().getAttribute("id"); //$NON-NLS-1$
        }
        catch (RuntimeException ignored)
        {
        }
        return null;
    }

    private static void hookWorkbench()
    {
        if (windowsHooked)
            return;
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        windowsHooked = true;
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
            }
        });
        // saveState панели маркеров идёт в persist до dispose колонок. Старые «Модуль»/«Метод»
        // без MARKER_FIELD роняют NPE — чиним непосредственно перед shutdown.
        workbench.addWorkbenchListener(new IWorkbenchListener()
        {
            @Override
            public boolean preShutdown(IWorkbench wb, boolean forced)
            {
                repairMarkerFields();
                return true;
            }

            @Override
            public void postShutdown(IWorkbench wb)
            {
            }
        });
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
    }

    /**
     * Перед persist: у колонок «Модуль»/«Метод» должен быть {@code MARKER_FIELD}, иначе
     * {@code ExtendedMarkersView.saveState} → NPE ({@code markerField == null}).
     */
    private static void repairMarkerFields()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        MarkerField moduleField = resolveRegisteredField(MODULE_FIELD_ID);
        MarkerField methodField = resolveRegisteredField(METHOD_FIELD_ID);
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
        {
            if (window == null)
                continue;
            for (IWorkbenchPage page : window.getPages())
            {
                if (page == null)
                    continue;
                for (IViewReference ref : page.getViewReferences())
                {
                    if (ref == null || !isTargetViewId(ref.getId()))
                        continue;
                    IWorkbenchPart part = ref.getPart(false);
                    if (!(part instanceof IViewPart view))
                        continue;
                    TreeViewer viewer = resolveViewer(view);
                    if (viewer == null)
                        continue;
                    Control control = viewer.getControl();
                    if (!(control instanceof Tree tree) || tree.isDisposed())
                        continue;
                    List<TreeColumn> toDispose = new ArrayList<>();
                    for (TreeColumn column : tree.getColumns())
                    {
                        if (column == null || column.isDisposed())
                            continue;
                        if (column.getData(MARKER_FIELD_DATA) instanceof MarkerField)
                            continue;
                        boolean moduleCol = Boolean.TRUE.equals(column.getData(MODULE_COLUMN_KEY))
                            || MODULE_COLUMN_TITLE.equals(column.getText());
                        boolean methodCol = Boolean.TRUE.equals(column.getData(METHOD_COLUMN_KEY))
                            || METHOD_COLUMN_TITLE.equals(column.getText());
                        if (moduleCol)
                            ensureMarkerFieldData(column, moduleField);
                        else if (methodCol)
                            ensureMarkerFieldData(column, methodField);
                        else
                            continue;
                        if (!(column.getData(MARKER_FIELD_DATA) instanceof MarkerField))
                            toDispose.add(column);
                    }
                    for (TreeColumn column : toDispose)
                    {
                        if (!column.isDisposed())
                            column.dispose();
                    }
                }
            }
        }
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                tryInstall(ref);
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                tryInstall(ref);
            }

            @Override
            public void partHidden(IWorkbenchPartReference ref)
            {
                // persist/saveState при уходе с вида — до него колонки уже должны иметь MARKER_FIELD.
                // tryInstall может не сработать (выкл. настройка) — repair безусловный.
                tryInstall(ref);
                if (ref != null && isTargetViewId(ref.getId()))
                    repairMarkerFields();
            }
        });
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
                tryInstall(ref);
        }
    }

    private static void tryInstall(IWorkbenchPartReference ref)
    {
        if (ref == null || !isTargetViewId(ref.getId()))
            return;
        IWorkbenchPart part = ref.getPart(false);
        if (!(part instanceof IViewPart view))
            return;
        installOnView(view);
    }

    private static void installOnView(IViewPart view)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        String viewId = view.getViewSite() != null ? view.getViewSite().getId() : null;
        if (!isTargetViewId(viewId))
            return;
        TreeViewer viewer = resolveViewer(view);
        if (viewer == null)
            return;
        Control control = viewer.getControl();
        if (!(control instanceof Tree tree) || tree.isDisposed())
            return;
        boolean columnsAdded = ensureComfortColumns(view, viewer, tree, viewId);
        if (Boolean.TRUE.equals(tree.getData(INSTALLED_KEY)))
        {
            // Колонки могли появиться уже после первой отрисовки — дорисовать ячейки.
            if (columnsAdded)
                viewer.refresh();
            return;
        }
        tree.setData(INSTALLED_KEY, Boolean.TRUE);
        loadColumnLayout(tree, viewId);
        FormTreeInteraction.install(tree, viewer);
        final String layoutViewId = viewId;
        tree.addDisposeListener(e -> saveColumnLayout(tree, layoutViewId));
        // TreeViewerColumn после setInput сам ячейки не заполняет.
        if (columnsAdded)
            viewer.refresh();
    }

    /** Viewer панели маркеров — {@code ExtendedMarkersView.getViewer()} (package API). */
    private static TreeViewer resolveViewer(IViewPart view)
    {
        Object viewer = Global.invoke(view, "getViewer"); //$NON-NLS-1$
        return viewer instanceof TreeViewer treeViewer ? treeViewer : null;
    }

    /**
     * Если штатная раскладка (memento) не показала «Модуль»/«Метод» — добавляем колонки сами.
     * Идемпотентно по ключу колонки и заголовку.
     */
    /** @return {@code true}, если хотя бы одна колонка создана сейчас */
    private static boolean ensureComfortColumns(IViewPart view, TreeViewer viewer, Tree tree, String viewId)
    {
        boolean added = false;
        added |= ensureColumn(view, viewer, tree, viewId, MODULE_COLUMN_KEY, MODULE_COLUMN_TITLE, MODULE_COLUMN_WIDTH,
            MODULE_FIELD_ID, MarkersListViewsHook::moduleText);
        added |= ensureColumn(view, viewer, tree, viewId, METHOD_COLUMN_KEY, METHOD_COLUMN_TITLE, METHOD_COLUMN_WIDTH,
            METHOD_FIELD_ID, MarkersListViewsHook::methodText);
        return added;
    }

    @FunctionalInterface
    private interface ColumnText
    {
        String text(Object element);
    }

    /** @return {@code true}, если колонка создана сейчас */
    private static boolean ensureColumn(IViewPart view, TreeViewer viewer, Tree tree, String viewId,
        String columnKey, String title, int width, String fieldId, ColumnText text)
    {
        // ExtendedMarkersView.saveState для каждой колонки читает MARKER_FIELD и зовёт
        // getFieldWidth → field.equals(...). Колонка без MarkerField → NPE при закрытии EDT.
        MarkerField field = resolveRegisteredField(fieldId);
        TreeColumn existing = findColumn(tree, columnKey, title);
        if (existing != null)
        {
            ensureMarkerFieldData(existing, field);
            wireHeaderSort(viewer, existing, text);
            return false;
        }
        if (field == null)
            return false;
        int restored = FormTableColumnState.readWidth(dialogSettings(viewId), widthKey(fieldId), width,
            MIN_COLUMN_WIDTH);
        TreeViewerColumn column = new TreeViewerColumn(viewer, SWT.LEFT);
        column.getColumn().setText(title);
        column.getColumn().setWidth(restored);
        column.getColumn().setMoveable(true);
        column.getColumn().setData(columnKey, Boolean.TRUE);
        column.getColumn().setData(MARKER_FIELD_DATA, field);
        column.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                if (cell == null)
                    return;
                cell.setText(text.text(cell.getElement()));
            }
        });
        wireHeaderSort(viewer, column.getColumn(), text);
        return true;
    }

    /** Проставляет {@code MARKER_FIELD}, если на колонке его ещё нет. */
    private static void ensureMarkerFieldData(TreeColumn column, MarkerField field)
    {
        if (column == null || column.isDisposed() || field == null)
            return;
        if (column.getData(MARKER_FIELD_DATA) instanceof MarkerField)
            return;
        column.setData(MARKER_FIELD_DATA, field);
    }

    /**
     * Сортировка кликом по шапке «Модуль»/«Метод».
     * Штатный {@code setPrimarySortField} Markers для наших колонок ненадёжен (поле может не быть
     * в компараторе генератора, reflection на private API молча падает). Сортируем видимые строки
     * через {@link ViewerComparator} по тексту ячейки — как в остальных списках Комфорт.
     */
    private static void wireHeaderSort(TreeViewer viewer, TreeColumn column, ColumnText text)
    {
        if (viewer == null || column == null || column.isDisposed() || text == null)
            return;
        if (Boolean.TRUE.equals(column.getData(HEADER_SORT_WIRED_KEY)))
            return;
        column.setData(HEADER_SORT_TEXT_KEY, text);
        // Снимаем чужие Selection-слушатели шапки (в т.ч. штатный Markers): иначе клик уходит
        // в setPrimarySortField и наша сортировка не срабатывает / сбрасывается refreshContents.
        Listener[] existing = column.getListeners(SWT.Selection);
        if (existing != null)
        {
            for (Listener listener : existing)
                column.removeListener(SWT.Selection, listener);
        }
        column.addSelectionListener(SelectionListener.widgetSelectedAdapter(e ->
            sortByComfortColumn(viewer, column)));
        column.setData(HEADER_SORT_WIRED_KEY, Boolean.TRUE);
    }

    private static void sortByComfortColumn(TreeViewer viewer, TreeColumn column)
    {
        if (viewer == null || column == null || column.isDisposed())
            return;
        Tree tree = column.getParent();
        if (tree == null || tree.isDisposed())
            return;
        Object textObj = column.getData(HEADER_SORT_TEXT_KEY);
        if (!(textObj instanceof ColumnText text))
            return;

        boolean ascending = true;
        if (column.equals(tree.getData(TREE_SORT_COLUMN_KEY)))
            ascending = !Boolean.TRUE.equals(tree.getData(TREE_SORT_ASC_KEY));
        tree.setData(TREE_SORT_COLUMN_KEY, column);
        tree.setData(TREE_SORT_ASC_KEY, Boolean.valueOf(ascending));
        tree.setSortColumn(column);
        tree.setSortDirection(ascending ? SWT.UP : SWT.DOWN);

        final boolean asc = ascending;
        final ColumnText extractor = text;
        viewer.setComparator(new ViewerComparator()
        {
            @Override
            public int compare(Viewer v, Object e1, Object e2)
            {
                int cmp = nullToEmpty(extractor.text(e1))
                    .compareToIgnoreCase(nullToEmpty(extractor.text(e2)));
                return asc ? cmp : -cmp;
            }
        });
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private static MarkerField resolveRegisteredField(String fieldId)
    {
        if (fieldId == null || fieldId.isBlank())
            return null;
        try
        {
            Class<?> registryClass = Class.forName(MARKER_SUPPORT_REGISTRY);
            Object registry = registryClass.getMethod("getInstance").invoke(null); //$NON-NLS-1$
            if (registry == null)
                return null;
            Object fieldObj = Global.invoke(registry, "getField", fieldId); //$NON-NLS-1$
            return fieldObj instanceof MarkerField markerField ? markerField : null;
        }
        catch (ReflectiveOperationException | RuntimeException ex)
        {
            return null;
        }
    }

    private static TreeColumn findColumn(Tree tree, String columnKey, String title)
    {
        for (TreeColumn column : tree.getColumns())
        {
            if (column == null || column.isDisposed())
                continue;
            if (Boolean.TRUE.equals(column.getData(columnKey)))
                return column;
            if (title.equals(column.getText()))
                return column;
        }
        return null;
    }

    private static IDialogSettings dialogSettings(String viewId)
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        String sectionName = settingsSectionFor(viewId);
        IDialogSettings section = top.getSection(sectionName);
        if (section == null)
            section = top.addNewSection(sectionName);
        return section;
    }

    private static String widthKey(String columnId)
    {
        return WIDTH_KEY_PREFIX + columnId;
    }

    /**
     * Стабильный id колонки: id {@link MarkerField} из {@code plugin.xml}, иначе наши ключи
     * «Модуль»/«Метод», иначе заголовок ({@code title:…}).
     */
    private static String columnId(TreeColumn column)
    {
        if (column == null || column.isDisposed())
            return null;
        if (Boolean.TRUE.equals(column.getData(MODULE_COLUMN_KEY)))
            return MODULE_FIELD_ID;
        if (Boolean.TRUE.equals(column.getData(METHOD_COLUMN_KEY)))
            return METHOD_FIELD_ID;
        Object data = column.getData(MARKER_FIELD_DATA);
        if (data instanceof MarkerField field)
        {
            String id = configId(field);
            if (id != null && !id.isBlank())
                return id;
        }
        String title = column.getText();
        if (title == null || title.isBlank())
            return null;
        if (MODULE_COLUMN_TITLE.equals(title))
            return MODULE_FIELD_ID;
        if (METHOD_COLUMN_TITLE.equals(title))
            return METHOD_FIELD_ID;
        return "title:" + title; //$NON-NLS-1$
    }

    private static void loadColumnLayout(Tree tree, String viewId)
    {
        if (tree == null || tree.isDisposed())
            return;
        IDialogSettings settings = dialogSettings(viewId);
        for (TreeColumn column : tree.getColumns())
        {
            if (column == null || column.isDisposed())
                continue;
            String id = columnId(column);
            if (id == null)
                continue;
            int width = FormTableColumnState.readWidth(settings, widthKey(id), column.getWidth(),
                MIN_COLUMN_WIDTH);
            if (width > 0 && column.getWidth() != width)
                column.setWidth(width);
        }
        applyColumnOrder(tree, settings.get(KEY_COL_ORDER));
    }

    private static void applyColumnOrder(Tree tree, String raw)
    {
        if (raw == null || raw.isBlank() || tree == null || tree.isDisposed())
            return;
        TreeColumn[] columns = tree.getColumns();
        if (columns.length <= 1)
            return;
        Map<String, Integer> idToIndex = new HashMap<>();
        for (int i = 0; i < columns.length; i++)
        {
            String id = columnId(columns[i]);
            if (id != null)
                idToIndex.putIfAbsent(id, Integer.valueOf(i));
        }
        List<Integer> order = new ArrayList<>(columns.length);
        Set<Integer> used = new HashSet<>();
        for (String part : raw.split(",")) //$NON-NLS-1$
        {
            String id = part.trim();
            if (id.isEmpty())
                continue;
            Integer index = idToIndex.get(id);
            if (index == null || !used.add(index))
                continue;
            order.add(index);
        }
        for (int i = 0; i < columns.length; i++)
        {
            if (used.add(Integer.valueOf(i)))
                order.add(Integer.valueOf(i));
        }
        if (order.size() != columns.length)
            return;
        int[] physical = new int[order.size()];
        for (int i = 0; i < order.size(); i++)
            physical[i] = order.get(i).intValue();
        tree.setColumnOrder(physical);
    }

    private static void saveColumnLayout(Tree tree, String viewId)
    {
        if (tree == null || tree.isDisposed() || tree.getColumnCount() <= 0)
            return;
        IDialogSettings settings = dialogSettings(viewId);
        int[] visual = tree.getColumnOrder();
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < visual.length; i++)
        {
            int physical = visual[i];
            if (physical < 0 || physical >= tree.getColumnCount())
                continue;
            TreeColumn column = tree.getColumn(physical);
            if (column == null || column.isDisposed())
                continue;
            String id = columnId(column);
            if (id == null)
                continue;
            if (order.length() > 0)
                order.append(',');
            order.append(id);
            int width = column.getWidth();
            if (width >= MIN_COLUMN_WIDTH)
                settings.put(widthKey(id), Integer.toString(width));
        }
        if (order.length() > 0)
            settings.put(KEY_COL_ORDER, order.toString());
    }

    private static String moduleText(Object element)
    {
        if (!(element instanceof MarkerItem item))
            return ""; //$NON-NLS-1$
        IMarker marker = item.getMarker();
        if (marker == null || !marker.exists())
            return ""; //$NON-NLS-1$
        return ModuleField.moduleName(marker);
    }

    private static String methodText(Object element)
    {
        if (!(element instanceof MarkerItem item))
            return ""; //$NON-NLS-1$
        IMarker marker = item.getMarker();
        if (marker == null || !marker.exists())
            return ""; //$NON-NLS-1$
        return MethodField.methodName(marker);
    }

    /**
     * Колонка «Модуль» для генераторов закладок/задач ({@code markerField} в {@code plugin.xml}).
     * Полное имя модуля кэшируется по пути файла и штампу (включая пустой результат).
     */
    public static final class ModuleField extends MarkerField
    {
        private static final int CACHE_LIMIT = 4096;
        private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

        @Override
        public String getValue(MarkerItem item)
        {
            if (item == null)
                return ""; //$NON-NLS-1$
            IMarker marker = item.getMarker();
            if (marker == null || !marker.exists())
                return ""; //$NON-NLS-1$
            return moduleName(marker);
        }

        @Override
        public int getDefaultColumnWidth(Control control)
        {
            return MODULE_COLUMN_WIDTH;
        }

        static String moduleName(IMarker marker)
        {
            IResource resource = marker.getResource();
            if (!(resource instanceof IFile file) || !file.exists())
                return ""; //$NON-NLS-1$
            String name = file.getName();
            if (name == null || !name.regionMatches(true, name.length() - 4, ".bsl", 0, 4)) //$NON-NLS-1$
                return ""; //$NON-NLS-1$

            long stamp = file.getModificationStamp();
            String key = file.getFullPath() + "#" + stamp; //$NON-NLS-1$
            String cached = CACHE.get(key);
            if (cached != null)
                return cached;

            String resolved = resolveModuleName(file);
            putBounded(CACHE, key, resolved, CACHE_LIMIT);
            return resolved;
        }

        private static String resolveModuleName(IFile file)
        {
            String rel = file.getProjectRelativePath().toString().replace('\\', '/');
            GetRef.ModuleRef ref = GetRef.pathToModuleRef(rel);
            if (ref == null && !rel.startsWith("src/") && !rel.contains("/src/")) //$NON-NLS-1$ //$NON-NLS-2$
                ref = GetRef.pathToModuleRef("src/" + rel); //$NON-NLS-1$
            return ref != null ? ref.toRefPrefix() : ""; //$NON-NLS-1$
        }
    }

    /**
     * Колонка «Метод» для генераторов закладок/задач ({@code markerField} в {@code plugin.xml}).
     * Разбор и кэши — в разделяемом {@link BslModuleMethodResolver}.
     */
    public static final class MethodField extends MarkerField
    {
        @Override
        public String getValue(MarkerItem item)
        {
            if (item == null)
                return ""; //$NON-NLS-1$
            IMarker marker = item.getMarker();
            if (marker == null || !marker.exists())
                return ""; //$NON-NLS-1$
            return methodName(marker);
        }

        @Override
        public int getDefaultColumnWidth(Control control)
        {
            return METHOD_COLUMN_WIDTH;
        }

        static String methodName(IMarker marker)
        {
            IResource resource = marker.getResource();
            int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
            if (line < 1 || !(resource instanceof IFile file))
                return ""; //$NON-NLS-1$
            // null = файл не прочитан — повторим позже (резолвер сам не кэширует этот случай).
            String method = BslModuleMethodResolver.methodAtLine(file, line);
            return method != null ? method : ""; //$NON-NLS-1$
        }
    }

    /** Кладёт значение в кэш; при переполнении сбрасывает целиком (проще LRU на ConcurrentHashMap). */
    private static void putBounded(Map<String, String> cache, String key, String value, int limit)
    {
        if (cache.size() >= limit)
            cache.clear();
        cache.put(key, value);
    }
}
