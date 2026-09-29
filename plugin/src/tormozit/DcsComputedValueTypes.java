package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.bm.core.event.BmChangeEvent;
import com._1c.g5.v8.bm.core.event.BmEvent;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.event.BmEventFilter;
import com._1c.g5.v8.bm.integration.event.IBmAsyncEventListener;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.path.DcsPath;
import com._1c.g5.v8.dt.dcs.settings.DcsAvailableFieldInfo;
import com._1c.g5.v8.dt.dcs.settings.DcsAvailableSettingsSourceForSchema;
import com._1c.g5.v8.dt.dcs.settings.SettingsContext;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaControlContext;
import com._1c.g5.v8.dt.dcs.ui.EditorPage;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.ql.typesystem.TypeDescriptionSource;

/**
 * Показывает тусклым цветом вычисленный тип поля в колонке «Тип значения», если явный тип не
 * задан: поля наборов данных и вычисляемые поля. Работает везде, где EDT строит страницы СКД
 * на {@link DataCompositionSchemaControlContext}: редактор макета схемы компоновки
 * ({@link DataCompositionSchemaEditorHook}) и окно настроек динамического списка
 * ({@link DynamicListSettingsDialogHook}).
 *
 * <p>Тип берётся тем же расчётом, что и дерево доступных полей на вкладке «Настройки» —
 * {@link DcsAvailableSettingsSourceForSchema}. Готовый источник вкладки «Настройки» не годится:
 * он пересчитывается только при её активации, поэтому строим свой, в фоновой задаче (как и сама
 * EDT).
 *
 * <p>Поводы для пересчёта: BM-изменения ресурса схемы (редактор макета), переключение вкладок и
 * перерисовка строк таблиц самой EDT — в окне динамического списка правки в BM не попадают, а
 * таблицы полей EDT перерисовывает после каждого изменения запроса и полей. Чтобы поводы без
 * изменений не стоили полного расчёта, он выполняется только при смене «отпечатка» схемы
 * (запросы, объекты, поля, выражения); открытие и переключение вкладок считают всегда.
 *
 * <p>Текст ставится в ячейку провайдером надписей колонки, поэтому штатное Ctrl+C
 * ({@code DefaultCopyTextCommandHandler} берёт текст ячейки Grid) его копирует, а редактор
 * ячейки показывает значение модели (пустое) — в режиме редактирования подсказка пропадает.
 */
final class DcsComputedValueTypes
{
    private static final String INSTALLED_KEY = "tormozit.dcs.computedValueTypes"; //$NON-NLS-1$
    private static final int RECOMPUTE_DELAY_MS = 400;
    private static final int EDITOR_ACTIVE_RETRY_MS = 500;

    /** Поля страницы {@code DataSets} с просмотрщиками полей (пакет {@code ...datasets.fields} не экспортирован). */
    private static final String[] DATASET_FIELD_VIEWERS = { "queryFieldsFullViewer", //$NON-NLS-1$
        "queryFieldsInUnionViewer", "objectFieldsFullViewer", "objectFieldsInUnionViewer", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "unionFieldsFullViewer", "unionFieldsInUnionViewer" }; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String FIELDS_COLUMN_ENUM =
        "com._1c.g5.v8.dt.dcs.ui.datasets.fields.DataSetsFieldsViewerBase$FieldsColumn"; //$NON-NLS-1$
    /** {@code CalculatedFields.VALUE_TYPE_COL_INDEX}. */
    private static final int CALCULATED_VALUE_TYPE_COLUMN = 9;

    /**
     * Назначения полей, в которых ищем поле. Первое — выбор; остальные — для полей, которым
     * ограничение использования закрывает выбор.
     */
    private static final DcsAvailableSettingsSourceForSchema.FieldUse[] FIELD_USES = {
        DcsAvailableSettingsSourceForSchema.FieldUse.eSelect,
        DcsAvailableSettingsSourceForSchema.FieldUse.eFilter,
        DcsAvailableSettingsSourceForSchema.FieldUse.eOrder,
        DcsAvailableSettingsSourceForSchema.FieldUse.eGroupping,
        DcsAvailableSettingsSourceForSchema.FieldUse.eCalculatedFieldExpression };

    private final DataCompositionSchemaControlContext context;
    private final Display display;
    private final List<ColumnViewer> viewers = new ArrayList<>();
    private final Job job;
    /** Следующий пересчёт — полный, даже при неизменном отпечатке схемы. */
    private final AtomicBoolean forceNext = new AtomicBoolean(true);
    /** Пересчёт по перерисовке строк уже запрошен — не дёргать задачу на каждую ячейку. */
    private final AtomicBoolean cellTriggerArmed = new AtomicBoolean();
    private IBmAsyncEventListener bmListener;
    private URI schemaUri;
    /** Ключ — {@link #pathKey} пути данных поля, значение — представление типа. */
    private volatile Map<String, String> types = Map.of();
    private String lastFingerprint;
    private boolean refreshPending;
    /** Идёт наше обновление таблиц — их перерисовка не повод для пересчёта. */
    private boolean refreshing;

    private DcsComputedValueTypes(DataCompositionSchemaControlContext context, Display display)
    {
        this.context = context;
        this.display = display;
        this.job = new Job("Типы полей схемы компоновки") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                cellTriggerArmed.set(false);
                recompute(forceNext.getAndSet(false));
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
    }

    /** Подключает показ к страницам СКД контекста; повторный вызов для тех же страниц ничего не делает. */
    static void install(DataCompositionSchemaControlContext context)
    {
        if (context == null)
            return;
        Composite dataSetsPage = null;
        Composite calculatedPage = null;
        for (EditorPage editorPage : context.getPages())
        {
            if (!(editorPage instanceof Composite composite) || composite.isDisposed())
                continue;
            String name = editorPage.getClass().getSimpleName();
            if ("DataSets".equals(name)) //$NON-NLS-1$
                dataSetsPage = composite;
            else if ("CalculatedFields".equals(name)) //$NON-NLS-1$
                calculatedPage = composite;
        }
        if (dataSetsPage == null || dataSetsPage.getData(INSTALLED_KEY) != null)
            return;
        DcsComputedValueTypes instance = new DcsComputedValueTypes(context, dataSetsPage.getDisplay());
        dataSetsPage.setData(INSTALLED_KEY, instance);
        for (String fieldName : DATASET_FIELD_VIEWERS)
            instance.wrapDataSetFieldsViewer(Global.getField(dataSetsPage, fieldName));
        if (calculatedPage != null
            && Global.invoke(calculatedPage, "getViewer") instanceof ColumnViewer viewer) //$NON-NLS-1$
            instance.wrapColumn(viewer, CALCULATED_VALUE_TYPE_COLUMN);
        CTabFolder tabs = enclosingTabFolder(dataSetsPage);
        if (tabs != null)
            tabs.addListener(SWT.Selection, e -> instance.schedule(true));
        instance.hookBmChanges();
        dataSetsPage.addDisposeListener(e -> instance.dispose());
        instance.schedule(true);
    }

    /** В редакторе макета страница лежит прямо в папке вкладок, в окне динамического списка — глубже. */
    private static CTabFolder enclosingTabFolder(Composite page)
    {
        for (Composite parent = page.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof CTabFolder tabs)
                return tabs;
        return null;
    }

    private void wrapDataSetFieldsViewer(Object fieldsViewer)
    {
        if (fieldsViewer == null
            || !(Global.invoke(fieldsViewer, "getViewer") instanceof ColumnViewer viewer)) //$NON-NLS-1$
            return;
        Object column = fieldsColumn(fieldsViewer, "VALUE_TYPE_COL_INDEX"); //$NON-NLS-1$
        if (column != null && Global.invoke(fieldsViewer, "getColumnIndex", column) instanceof Integer index) //$NON-NLS-1$
            wrapColumn(viewer, index);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object fieldsColumn(Object fieldsViewer, String name)
    {
        try
        {
            Class<?> enumClass =
                Class.forName(FIELDS_COLUMN_ENUM, false, fieldsViewer.getClass().getClassLoader());
            return Enum.valueOf((Class<Enum>) enumClass, name);
        }
        catch (Exception | LinkageError e)
        {
            return null;
        }
    }

    /**
     * Заменяет провайдер надписей колонки обёрткой. {@code ColumnViewer.getViewerColumn} и
     * {@code ViewerColumn.getLabelProvider} в JFace пакетные — через {@link Global#invoke}.
     *
     * <p>{@code ViewerColumn.setLabelProvider} вызывает {@code dispose} у прежнего провайдера,
     * а он у EDT общий для всех колонок: {@code BaseLabelProvider.dispose} только снимает его
     * слушателей изменения надписей. Провайдеры полей наборов и вычисляемых полей таких
     * событий не шлют, так что остальные колонки ничего не теряют.
     */
    private void wrapColumn(ColumnViewer viewer, int columnIndex)
    {
        if (viewer.getControl() == null || viewer.getControl().isDisposed())
            return;
        if (!(Global.invoke(viewer, "getViewerColumn", columnIndex) instanceof ViewerColumn viewerColumn)) //$NON-NLS-1$
            return;
        Object current = Global.invoke(viewerColumn, "getLabelProvider"); //$NON-NLS-1$
        if (!(current instanceof CellLabelProvider delegate) || current instanceof HintLabelProvider)
            return;
        viewerColumn.setLabelProvider(new HintLabelProvider(delegate, this));
        viewers.add(viewer);
    }

    private void hookBmChanges()
    {
        IBmModel bmModel = context.getBmModel();
        DataCompositionSchema schema = context.getDataCompositionSchema();
        Resource resource = schema != null ? schema.eResource() : null;
        if (bmModel == null || resource == null)
            return;
        schemaUri = resource.getURI();
        bmListener = this::onBmEvent;
        bmModel.addAsyncEventListener(bmListener, BmEventFilter.changeFilter());
    }

    /** Как {@code DataCompositionSchemaEditor.handleAsyncEvent}: изменения ресурса схемы. */
    private void onBmEvent(BmEvent event)
    {
        if (event.getChangeEvents() == null)
            return;
        for (BmChangeEvent change : event.getChangeEvents().values())
        {
            EObject object = change.getObject();
            Resource resource = object != null ? object.eResource() : null;
            if (resource != null && Objects.equals(resource.getURI(), schemaUri))
            {
                schedule(false);
                return;
            }
        }
    }

    /** Перерисовка строки таблицы самой EDT — повод проверить отпечаток схемы. */
    private void onCellUpdated()
    {
        if (!refreshing && cellTriggerArmed.compareAndSet(false, true))
            job.schedule(RECOMPUTE_DELAY_MS);
    }

    private void schedule(boolean force)
    {
        if (force)
            forceNext.set(true);
        job.schedule(RECOMPUTE_DELAY_MS);
    }

    private void dispose()
    {
        job.cancel();
        IBmModel bmModel = context.getBmModel();
        if (bmListener != null && bmModel != null)
            bmModel.removeAsyncEventListener(bmListener);
        bmListener = null;
    }

    private String typeFor(String dataPath)
    {
        return dataPath == null ? null : types.get(pathKey(dataPath));
    }

    /** Фоновый расчёт — как {@code Settings.refreshAvailableFieldsSource}. */
    private void recompute(boolean force)
    {
        Map<String, String> result = new HashMap<>();
        try
        {
            DataCompositionSchema schema = context.getDataCompositionSchema();
            IV8Project v8project = context.getV8project();
            if (schema == null || v8project == null)
                return;
            Set<String> paths = new LinkedHashSet<>();
            StringBuilder fingerprint = new StringBuilder();
            collectPaths(schema, paths, fingerprint);
            String print = fingerprint.toString();
            if (!force && print.equals(lastFingerprint))
                return;
            lastFingerprint = print;
            if (!paths.isEmpty())
            {
                int alias = v8project.getScriptVariant().getValue();
                // Как DcsSettingsService динамического списка: признак списка меняет набор доступных полей.
                SettingsContext settingsContext = new SettingsContext(v8project.getDtProject(),
                    context.getBmModel(), context.getMdTypeIndex(), context.getEmfIndexManager(),
                    context.isDynamicListContext());
                DcsAvailableSettingsSourceForSchema source =
                    new DcsAvailableSettingsSourceForSchema(settingsContext);
                Version version = context.getVersion() != null ? context.getVersion() : v8project.getVersion();
                source.init(v8project, schema, context.getCurrentLanguageCode(), alias, version);
                Map<String, DcsAvailableFieldInfo> infos = findFieldInfos(source, paths);
                for (String path : paths)
                {
                    String text = typeText(infos.get(pathKey(path)), alias);
                    if (text != null)
                        result.put(pathKey(path), text);
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            // Схема в промежуточном состоянии — прежние типы остаются до следующего пересчёта.
            lastFingerprint = null;
            return;
        }
        if (result.equals(types))
            return;
        types = Map.copyOf(result);
        if (!display.isDisposed())
            display.asyncExec(this::refreshViewers);
    }

    /**
     * Пути данных полей без явного типа: поля всех наборов (с вложенными в объединения) и
     * вычисляемые поля. Попутно — отпечаток всего, от чего зависят типы.
     */
    private static void collectPaths(DataCompositionSchema schema, Set<String> paths, StringBuilder fingerprint)
    {
        for (DataSet dataSet : schema.getDataSets())
            collectDataSetPaths(dataSet, paths, fingerprint);
        for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields())
        {
            boolean untyped = isEmpty(field.getValueType());
            fingerprint.append("\nC|").append(field.getDataPath()).append('|') //$NON-NLS-1$
                .append(field.getExpression()).append('|').append(untyped);
            if (untyped && field.getDataPath() != null)
                paths.add(field.getDataPath());
        }
    }

    private static void collectDataSetPaths(DataSet dataSet, Set<String> paths, StringBuilder fingerprint)
    {
        fingerprint.append("\nD|").append(dataSet.eClass().getName()).append('|').append(dataSet.getName()); //$NON-NLS-1$
        if (dataSet instanceof DataCompositionSchemaDataSetQuery query)
            fingerprint.append('|').append(query.getQuery());
        else if (dataSet instanceof DataCompositionSchemaDataSetObject object)
            fingerprint.append('|').append(object.getObjectName());
        for (DataSetField field : dataSet.getFields())
        {
            if (!(field instanceof DataCompositionSchemaDataSetField dataSetField))
            {
                fingerprint.append("\nF|").append(field.eClass().getName()); //$NON-NLS-1$
                continue;
            }
            boolean untyped = isEmpty(dataSetField.getValueType());
            fingerprint.append("\nF|").append(dataSetField.getDataPath()).append('|') //$NON-NLS-1$
                .append(dataSetField.getField()).append('|').append(untyped);
            if (untyped && dataSetField.getDataPath() != null)
                paths.add(dataSetField.getDataPath());
        }
        if (dataSet instanceof DataCompositionSchemaDataSetUnion union)
            for (DataSet item : union.getItems())
                collectDataSetPaths(item, paths, fingerprint);
    }

    private static boolean isEmpty(TypeDescription type)
    {
        return type == null || type.getTypes().isEmpty();
    }

    /**
     * Как {@code DcsAvailableFields.findField}: доступные поля берутся списком потомков
     * родительского пути, по одному запросу на родителя и назначение.
     */
    private static Map<String, DcsAvailableFieldInfo> findFieldInfos(
        DcsAvailableSettingsSourceForSchema source, Set<String> paths)
    {
        Map<String, DcsAvailableFieldInfo> infos = new HashMap<>();
        Set<String> loadedParents = new HashSet<>();
        for (DcsAvailableSettingsSourceForSchema.FieldUse use : FIELD_USES)
        {
            for (String path : paths)
            {
                if (infos.containsKey(pathKey(path)))
                    continue;
                try
                {
                    DcsPath parent = new DcsPath(path).nearParentPath();
                    if (!loadedParents.add(use + "|" + parent)) //$NON-NLS-1$
                        continue;
                    List<DcsAvailableFieldInfo> children = new ArrayList<>();
                    source.getChildFields(new DcsPath(), use, parent, children, 1,
                        DcsAvailableSettingsSourceForSchema.OrderType.eOBTitle, false);
                    for (DcsAvailableFieldInfo info : children)
                        if (info.dataPath != null)
                            for (DcsPath dataPath : info.dataPath)
                                if (dataPath != null)
                                    infos.putIfAbsent(pathKey(dataPath.toString()), info);
                }
                catch (Exception e)
                {
                    // Путь, который источник не разбирает, — просто без подсказки.
                }
            }
        }
        return infos;
    }

    /** Представление как у штатной колонки: имена типов через запятую, без квалификаторов. */
    private static String typeText(DcsAvailableFieldInfo info, int alias)
    {
        if (info == null || info.isFolder)
            return null;
        TypeDescriptionSource valueType = info.valueType;
        if (valueType == null || valueType.isEmpty())
            return null;
        StringBuilder text = new StringBuilder();
        for (TypeItem type : valueType.getTypes())
        {
            String name = alias == 0 ? McoreUtil.getTypeName(type) : McoreUtil.getTypeNameRu(type);
            if (name == null || name.isEmpty())
                continue;
            if (text.length() > 0)
                text.append(", "); //$NON-NLS-1$
            text.append(name);
        }
        return text.length() > 0 ? text.toString() : null;
    }

    private static String pathKey(String dataPath)
    {
        String normalized = dataPath;
        try
        {
            normalized = new DcsPath(dataPath).toString();
        }
        catch (Exception e)
        {
            // Остаётся исходная строка.
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** Идущее редактирование ячейки обновление просмотрщика оборвало бы — откладываем. */
    private void refreshViewers()
    {
        refreshPending = false;
        refreshing = true;
        try
        {
            for (ColumnViewer viewer : viewers)
            {
                if (viewer.getControl() == null || viewer.getControl().isDisposed())
                    continue;
                if (viewer.isCellEditorActive())
                {
                    refreshPending = true;
                    continue;
                }
                viewer.refresh();
            }
        }
        finally
        {
            refreshing = false;
        }
        if (refreshPending && !display.isDisposed())
            display.timerExec(EDITOR_ACTIVE_RETRY_MS, this::refreshViewers);
    }

    /**
     * Провайдер надписей колонки «Тип значения»: штатный текст, а при пустом — вычисленный тип
     * тусклым цветом. {@code dispose} штатному не передаёт — он общий для всех колонок и
     * освобождается их собственными {@code ViewerColumn}.
     */
    private static final class HintLabelProvider extends CellLabelProvider
    {
        private final CellLabelProvider delegate;
        private final DcsComputedValueTypes owner;

        HintLabelProvider(CellLabelProvider delegate, DcsComputedValueTypes owner)
        {
            this.delegate = delegate;
            this.owner = owner;
        }

        @Override
        public void update(ViewerCell cell)
        {
            delegate.update(cell);
            owner.onCellUpdated();
            String text = cell.getText();
            if (text != null && !text.isEmpty())
                return;
            Object element = cell.getElement();
            String dataPath = element instanceof DataCompositionSchemaDataSetField dataSetField
                ? dataSetField.getDataPath()
                : element instanceof DataCompositionSchemaCalculatedField calculatedField
                    ? calculatedField.getDataPath() : null;
            String hint = owner.typeFor(dataPath);
            if (hint == null)
                return;
            cell.setText(hint);
            cell.setForeground(ThemeAwareColors.effectiveSystemColor(
                cell.getControl().getDisplay(), SWT.COLOR_DARK_GRAY));
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate.isLabelProperty(element, property);
        }

        @Override
        public String getToolTipText(Object element)
        {
            return delegate.getToolTipText(element);
        }

        @Override
        public Image getToolTipImage(Object element)
        {
            return delegate.getToolTipImage(element);
        }

        @Override
        public Color getToolTipBackgroundColor(Object element)
        {
            return delegate.getToolTipBackgroundColor(element);
        }

        @Override
        public Color getToolTipForegroundColor(Object element)
        {
            return delegate.getToolTipForegroundColor(element);
        }

        @Override
        public Font getToolTipFont(Object element)
        {
            return delegate.getToolTipFont(element);
        }

        @Override
        public Point getToolTipShift(Object element)
        {
            return delegate.getToolTipShift(element);
        }

        @Override
        public boolean useNativeToolTip(Object element)
        {
            return delegate.useNativeToolTip(element);
        }

        @Override
        public int getToolTipTimeDisplayed(Object element)
        {
            return delegate.getToolTipTimeDisplayed(element);
        }

        @Override
        public int getToolTipDisplayDelayTime(Object element)
        {
            return delegate.getToolTipDisplayDelayTime(element);
        }

        @Override
        public int getToolTipStyle(Object element)
        {
            return delegate.getToolTipStyle(element);
        }
    }
}
