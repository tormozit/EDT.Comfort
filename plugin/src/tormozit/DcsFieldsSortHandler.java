package tormozit;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmEditingContext;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.dcs.dataset.IDataSetWrapper;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetFieldFolder;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetUnion;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaNestedDataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DataSetField;
import com._1c.g5.v8.dt.dcs.typedvalue.TypedValueFactory;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaControlContext;
import com._1c.g5.v8.dt.dcs.ui.DataCompositionSchemaEditor;
import com._1c.g5.v8.dt.dcs.ui.DcsEvent;
import com._1c.g5.v8.dt.dcs.ui.DcsEvent.DcsEventType;
import com._1c.g5.v8.dt.dcs.ui.EditorPage;
import com._1c.g5.v8.dt.dcs.ui.calculated.CalculatedFields;
import com._1c.g5.v8.dt.dcs.ui.datasets.DataSets;
import com._1c.g5.v8.dt.dcs.ui.datasets.IDataSetsWrapper;
import com._1c.g5.v8.dt.dcs.ui.parameters.Parameters;
import com._1c.g5.v8.dt.dcs.ui.util.DcsUiUtil;
import com._1c.g5.v8.dt.dcs.util.DcsUtil;
import com._1c.g5.v8.dt.ui.util.DtHandlerUtil;

/**
 * Подменю «Сортировать по» в контекстном меню таблиц конструктора схемы компоновки:
 * полей набора данных («Поле», «Путь к данным», «Заголовок») и вычисляемых полей
 * («Путь к данным», «Выражение», «Заголовок»).
 *
 * <p>Сортировка меняет <b>порядок в самой схеме</b> — список полей набора
 * ({@code DataSet.getFields()}) или список вычисляемых полей
 * ({@code DataCompositionSchema.getCalculatedFields()}) — внутри BM-транзакции, а не
 * порядок показа: результат сохраняется в схему и виден в XML и в конфигураторе 1С.
 *
 * <p>В полях набора данных сортируется только уровень выделенного поля (его «братья»);
 * при пустом выделении — верхний уровень набора. Вложенные поля переезжают вместе со своим
 * полем-владельцем (папкой, вложенным набором). Вычисляемые поля — плоский список,
 * сортируется он целиком.
 *
 * <p>Важно: содержимое папок и вложенных наборов сама EDT всегда показывает по алфавиту
 * имён (в {@code DataSetsFieldsTree} потомки узла лежат в {@code TreeMap}), поэтому на таких
 * уровнях новый порядок виден только в XML и в конфигураторе, а не в дереве полей.
 *
 * <p>Ключи сортировки повторяют текст соответствующих колонок таблиц
 * ({@code DataSetsFieldsLabelProvider}, {@code CalculatedFieldsLabelProvider}).
 */
public class DcsFieldsSortHandler extends AbstractHandler
{
    /** Идентификатор параметра команды: ключ сортировки. */
    public static final String PARAM_KEY = "tormozit.dcs.sortFields.key"; //$NON-NLS-1$

    /** Ключ сортировки — колонка таблицы или имя набора данных. */
    private enum SortKey
    {
        FIELD, PATH, TITLE, EXPRESSION, NAME;

        static SortKey parse(String value)
        {
            if ("field".equals(value)) //$NON-NLS-1$
                return FIELD;
            if ("path".equals(value)) //$NON-NLS-1$
                return PATH;
            if ("title".equals(value)) //$NON-NLS-1$
                return TITLE;
            if ("expression".equals(value)) //$NON-NLS-1$
                return EXPRESSION;
            if ("name".equals(value)) //$NON-NLS-1$
                return NAME;
            return null;
        }
    }

    @Override
    public Object execute(ExecutionEvent event)
    {
        SortKey key = SortKey.parse(event.getParameter(PARAM_KEY));
        if (key == null)
            return null;

        DataCompositionSchemaEditor editor =
            DtHandlerUtil.getActiveEditor(event, DataCompositionSchemaEditor.class);
        DataCompositionSchemaControlContext context =
            editor != null ? editor.getControlContext() : DcsUiUtil.getActualControlContext();
        if (context == null || !context.isEditable())
            return null;

        // Команда одна на все таблицы, поэтому что сортировать решает активная страница
        // редактора; ключ различает только две таблицы одной страницы «Наборы данных»
        // (дерево наборов — «Имя», таблица полей — остальные ключи).
        EditorPage active = editor != null ? editor.getActivePage() : null;
        if (active instanceof Parameters parameters)
        {
            sortParameters(context, parameters, key);
            return null;
        }
        if (active instanceof CalculatedFields || key == SortKey.EXPRESSION)
        {
            CalculatedFields page = findPage(editor, context, CalculatedFields.class);
            if (page != null)
                sortCalculatedFields(context, page, key);
            return null;
        }
        DataSets dataSets = findPage(editor, context, DataSets.class);
        if (dataSets == null)
            return null;
        if (key == SortKey.NAME)
            sortDataSets(event, context, dataSets);
        else
            sortDataSetFields(event, context, dataSets, key);
        return null;
    }

    // =======================================================================
    // Поля набора данных
    // =======================================================================

    private static void sortDataSetFields(ExecutionEvent event,
        DataCompositionSchemaControlContext context, DataSets dataSets, SortKey key)
    {
        DataSet dataSet = dataSets.getCurrentDataSet();
        IDataSetsWrapper wrapper = dataSets.getCurrentDataSetWrapper();
        if (dataSet == null || wrapper == null)
            return;

        List<DataSetField> siblings =
            siblingsToSort(wrapper, selectedElement(event, context, DataSetField.class));
        if (siblings.size() < 2)
            return;
        if (!sortFields(context, dataSet, wrapper, siblings, key))
            return;

        // Порядок верхнего уровня дерево полей берёт из порядка обёрток, а те строятся
        // по списку модели — обёртки нужно пересобрать (init) до обновления таблицы.
        wrapper.init();
        refreshDataSetFieldsViewer(dataSets);
    }


    /**
     * Уровень сортировки: «братья» выделенного поля, либо верхний уровень набора.
     *
     * <p>Верхний уровень считается так же, как его считает
     * {@code DataSetsFieldsContentProvider.getElements}: все поля набора минус потомки
     * папок и вложенных наборов — {@code getChildFields(null)} отдаёт весь список полей,
     * а не только корневые.
     */
    private static List<DataSetField> siblingsToSort(IDataSetsWrapper wrapper, DataSetField selected)
    {
        DataSetField parent = selected != null ? wrapper.getAncestorField(selected) : null;
        if (parent != null)
            return new ArrayList<>(wrapper.getChildFields(parent));

        List<DataSetField> all = wrapper.getChildFields(null);
        LinkedHashSet<DataSetField> roots = new LinkedHashSet<>(all);
        for (DataSetField field : all)
            if (field instanceof DataCompositionSchemaDataSetFieldFolder
                || field instanceof DataCompositionSchemaNestedDataSet)
                roots.removeAll(wrapper.getChildFields(field));
        return new ArrayList<>(roots);
    }

    /**
     * Переставляет в списке полей набора данных блоки (поле со всеми потомками)
     * перечисленных «братьев» по возрастанию ключа. Позиции всех прочих полей
     * не меняются.
     *
     * @return {@code true}, если порядок действительно изменился
     */
    private static boolean sortFields(DataCompositionSchemaControlContext context, DataSet dataSet,
        IDataSetsWrapper wrapper, List<DataSetField> siblings, SortKey key)
    {
        List<DataSetField> fields = dataSet.getFields();
        Map<Object, Integer> indexes = new HashMap<>();
        for (int i = 0; i < fields.size(); i++)
            indexes.put(fieldKey(fields.get(i)), Integer.valueOf(i));

        Map<DataSetField, List<Integer>> blocks = new IdentityHashMap<>(); // ключи — объекты обёртки
        List<Integer> positions = new ArrayList<>();
        for (DataSetField sibling : siblings)
        {
            List<Integer> block = blockIndexes(wrapper, sibling, indexes);
            if (block.isEmpty())
                return false; // поле не из этого набора — сортировать нечего
            blocks.put(sibling, block);
            positions.addAll(block);
        }
        positions.sort(null);

        List<DataSetField> sorted = new ArrayList<>(siblings);
        sorted.sort(keyComparator(field -> dataSetFieldText(field, key, wrapper,
            context.getV8project(), context.getCurrentLanguageCode())));

        List<Integer> newOrder = new ArrayList<>(positions.size());
        for (DataSetField sibling : sorted)
            newOrder.addAll(blocks.get(sibling));

        int[] target = identityOrder(fields.size());
        boolean changed = false;
        for (int k = 0; k < positions.size(); k++)
        {
            int position = positions.get(k).intValue();
            int source = newOrder.get(k).intValue();
            target[position] = source;
            changed |= position != source;
        }
        if (!changed)
            return false;

        applyOrder(context, dataSet, owner -> ((DataSet) owner).getFields(), target);
        return true;
    }

    /**
     * Ключ поля для сопоставления объекта обёртки с элементом списка модели.
     *
     * <p>Сопоставлять по самим объектам нельзя: {@code DataSets.getCurrentDataSet()} каждый раз
     * перерезолвливает набор через {@code IBmEngine.getObjectById(bmGetId())}, поэтому поля
     * набора и поля, которые держит обёртка, — разные экземпляры EMF одного BM-объекта.
     */
    private static Object fieldKey(DataSetField field)
    {
        Object key = modelKey(field);
        if (key != field)
            return key;
        String path = dataSetFieldPath(field);
        return path.isEmpty() ? field : "path:" + path; //$NON-NLS-1$
    }

    /** Ключ объекта модели: BM-идентификатор, если он есть, иначе сам объект. */
    private static Object modelKey(EObject object)
    {
        if (object instanceof IBmObject bmObject)
        {
            long id = bmObject.bmGetId();
            if (id != -1L)
                return Long.valueOf(id);
        }
        return object;
    }

    /** Индексы поля и всех его потомков в списке полей набора, по возрастанию. */
    private static List<Integer> blockIndexes(IDataSetsWrapper wrapper, DataSetField field,
        Map<Object, Integer> indexes)
    {
        List<Integer> block = new ArrayList<>();
        collectBlock(wrapper, field, indexes, block);
        block.sort(null);
        return block;
    }

    private static void collectBlock(IDataSetsWrapper wrapper, DataSetField field,
        Map<Object, Integer> indexes, List<Integer> block)
    {
        Integer index = indexes.get(fieldKey(field));
        if (index != null)
            block.add(index);
        if (!(field instanceof DataCompositionSchemaDataSetFieldFolder)
            && !(field instanceof DataCompositionSchemaNestedDataSet))
            return;
        for (DataSetField child : wrapper.getChildFields(field))
            collectBlock(wrapper, child, indexes, block);
    }

    /**
     * Обновляет таблицу полей набора. Пакет {@code ...dcs.ui.datasets.fields} бандл
     * не экспортирует, поэтому и сам просмотрщик, и его {@code notify} доступны
     * только рефлексией.
     */
    private static void refreshDataSetFieldsViewer(DataSets dataSets)
    {
        Object fieldsViewer = Global.invoke(dataSets, "getCurrentFieldsViewer"); //$NON-NLS-1$
        if (fieldsViewer != null)
            Global.invokeVoid(fieldsViewer, "notify", //$NON-NLS-1$
                new DcsEvent(DcsEventType.DATASETS_FIELDS_CHANGED));
    }

    // =======================================================================
    // Вычисляемые поля
    // =======================================================================

    /** Вычисляемые поля — плоский список схемы, сортируется целиком. */
    private static void sortCalculatedFields(DataCompositionSchemaControlContext context,
        CalculatedFields page, SortKey key)
    {
        DataCompositionSchema schema = context.getDataCompositionSchema();
        if (schema == null)
            return;
        boolean sorted = sortWholeList(context, schema, schema.getCalculatedFields(),
            owner -> ((DataCompositionSchema) owner).getCalculatedFields(),
            field -> calculatedFieldText(field, key, context.getV8project(),
                context.getCurrentLanguageCode()));
        if (sorted)
            refresh(page.getViewer());
    }

    // =======================================================================
    // Параметры
    // =======================================================================

    /** Параметры — плоский список схемы, сортируется целиком. */
    private static void sortParameters(DataCompositionSchemaControlContext context, Parameters page,
        SortKey key)
    {
        DataCompositionSchema schema = context.getDataCompositionSchema();
        if (schema == null)
            return;
        boolean sorted = sortWholeList(context, schema, schema.getParameters(),
            owner -> ((DataCompositionSchema) owner).getParameters(),
            parameter -> parameterText(parameter, key, context.getCurrentLanguageCode()));
        if (sorted)
            refresh(page.getViewer());
    }

    private static String parameterText(DataCompositionSchemaParameter parameter, SortKey key,
        String language)
    {
        switch (key)
        {
        case NAME:
            return nullToEmpty(parameter.getName());
        case TITLE:
            // ParametersLabelProvider: заголовок — строка нужного языка.
            return nullToEmpty(DcsUtil.getLangString(parameter.getTitle(), language));
        default:
            return ""; //$NON-NLS-1$
        }
    }

    // =======================================================================
    // Ресурсы
    // =======================================================================

    /**
     * Сортирует ресурсы схемы (плоский список {@code getTotalFields()}) по колонке «Поле» —
     * это путь к данным ресурса ({@code ResourcesLabelProvider}).
     *
     * <p>Вызывается не из меню EDT, а из пункта, который вешает на таблицу ресурсов
     * {@link DataCompositionSchemaEditorHook}: своего меню у этой таблицы EDT не создаёт
     * (в {@code DcsUiUtil.addContextMenuToViewer} идентификатор меню передан как {@code null}),
     * поэтому декларативного вклада для неё не существует.
     *
     * @param resourcesViewer просмотрщик таблицы ресурсов; обновляется после перестановки
     */
    public static void sortResourcesByField(DataCompositionSchemaControlContext context,
        ColumnViewer resourcesViewer)
    {
        if (context == null || !context.isEditable())
            return;
        DataCompositionSchema schema = context.getDataCompositionSchema();
        if (schema == null)
            return;
        boolean sorted = sortWholeList(context, schema, schema.getTotalFields(),
            owner -> ((DataCompositionSchema) owner).getTotalFields(),
            resource -> nullToEmpty(resource.getDataPath()));
        if (sorted)
            refresh(resourcesViewer);
    }

    // =======================================================================
    // Дерево наборов данных
    // =======================================================================

    /**
     * Сортирует по имени «братьев» выделенного набора данных: элементы объединения, если набор
     * лежит в объединении, иначе наборы верхнего уровня схемы. Без выделения — верхний уровень.
     */
    private static void sortDataSets(ExecutionEvent event,
        DataCompositionSchemaControlContext context, DataSets page)
    {
        DataCompositionSchema schema = context.getDataCompositionSchema();
        if (schema == null)
            return;
        DataSet selected = selectedElement(event, context, DataSet.class);
        EObject container = selected != null ? selected.eContainer() : schema;
        boolean sorted;
        if (container instanceof DataCompositionSchemaDataSetUnion union)
            sorted = sortWholeList(context, union, union.getItems(),
                owner -> ((DataCompositionSchemaDataSetUnion) owner).getItems(),
                dataSet -> nullToEmpty(dataSet.getName()));
        else
            sorted = sortWholeList(context, schema, schema.getDataSets(),
                owner -> ((DataCompositionSchema) owner).getDataSets(),
                dataSet -> nullToEmpty(dataSet.getName()));
        if (sorted)
            refresh(page.getDataSetsViewer());
    }

    // =======================================================================
    // Сортировка плоского списка целиком
    // =======================================================================

    /**
     * Сортирует список модели целиком по возрастанию ключа.
     *
     * @param items текущее содержимое списка (порядок модели)
     * @param list  как получить этот же список у транзакционной копии владельца
     * @return {@code true}, если порядок действительно изменился
     */
    private static <T extends EObject> boolean sortWholeList(
        DataCompositionSchemaControlContext context, EObject owner, List<T> items,
        Function<EObject, EList<? extends EObject>> list, Function<T, String> text)
    {
        if (items.size() < 2)
            return false;
        Map<Object, Integer> indexes = new HashMap<>();
        for (int i = 0; i < items.size(); i++)
            indexes.put(modelKey(items.get(i)), Integer.valueOf(i));

        List<T> sorted = new ArrayList<>(items);
        sorted.sort(keyComparator(text));

        int[] target = identityOrder(items.size());
        boolean changed = false;
        for (int i = 0; i < sorted.size(); i++)
        {
            Integer source = indexes.get(modelKey(sorted.get(i)));
            if (source == null)
                return false;
            target[i] = source.intValue();
            changed |= target[i] != i;
        }
        if (!changed)
            return false;

        applyOrder(context, owner, list, target);
        return true;
    }

    private static void refresh(ColumnViewer viewer)
    {
        if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed())
            viewer.refresh();
    }

    /** Первый выделенный элемент нужного типа, либо {@code null}. */
    private static <T> T selectedElement(ExecutionEvent event,
        DataCompositionSchemaControlContext context, Class<T> elementClass)
    {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (selection == null && context.getSelectionProvider() != null)
            selection = context.getSelectionProvider().getSelection();
        if (!(selection instanceof IStructuredSelection structured))
            return null;
        Object first = structured.getFirstElement();
        return elementClass.isInstance(first) ? elementClass.cast(first) : null;
    }

    // =======================================================================
    // Запись нового порядка
    // =======================================================================

    private static int[] identityOrder(int size)
    {
        int[] order = new int[size];
        for (int i = 0; i < size; i++)
            order[i] = i;
        return order;
    }

    /**
     * Применяет к списку новый порядок, заданный старыми индексами: {@code target[i]} —
     * старый индекс элемента, который должен оказаться на позиции {@code i}. Запись идёт
     * в BM-транзакции, если у редактора есть контекст редактирования.
     */
    private static void applyOrder(DataCompositionSchemaControlContext context, EObject owner,
        Function<EObject, EList<? extends EObject>> list, int[] target)
    {
        IBmEditingContext editingContext = context.getEditingContext();
        if (editingContext == null)
        {
            reorder(list.apply(owner), target);
            return;
        }
        editingContext.execute(new AbstractBmTask<Object>("Сортировка полей схемы компоновки") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction transaction, IProgressMonitor progressMonitor)
            {
                reorder(list.apply((EObject) transaction.toTransactionObject(owner)), target);
                return null;
            }
        });
    }

    /** Переставляет элементы списка так, чтобы на позиции {@code i} оказался {@code target[i]}-й. */
    private static <T> void reorder(EList<T> list, int[] target)
    {
        if (list.size() != target.length)
            return;
        List<T> before = new ArrayList<>(list);
        for (int i = 0; i < target.length; i++)
        {
            T wanted = before.get(target[i]);
            int current = list.indexOf(wanted);
            if (current > i)
                list.move(i, current);
        }
    }

    // =======================================================================
    // Ключи сортировки — тексты колонок таблиц
    // =======================================================================

    private static <T> Comparator<T> keyComparator(Function<T, String> text)
    {
        Collator collator = Collator.getInstance(new Locale("ru")); //$NON-NLS-1$
        collator.setStrength(Collator.SECONDARY);
        Map<T, String> keys = new IdentityHashMap<>();
        return (left, right) -> collator.compare(keys.computeIfAbsent(left, text),
            keys.computeIfAbsent(right, text));
    }

    private static String dataSetFieldText(DataSetField field, SortKey key, IDataSetsWrapper wrapper,
        IV8Project project, String language)
    {
        switch (key)
        {
        case FIELD:
            return dataSetFieldName(field);
        case PATH:
            return dataSetFieldPath(field);
        case TITLE:
            return dataSetFieldTitle(field, wrapper, project, language);
        default:
            return ""; //$NON-NLS-1$
        }
    }

    private static String calculatedFieldText(DataCompositionSchemaCalculatedField field, SortKey key,
        IV8Project project, String language)
    {
        switch (key)
        {
        case PATH:
            return nullToEmpty(field.getDataPath());
        case EXPRESSION:
            return nullToEmpty(field.getExpression());
        case TITLE:
            // CalculatedFieldsLabelProvider: заголовок берётся строкой нужного языка,
            // без синонимов — их у вычисляемого поля нет.
            return nullToEmpty(DcsUtil.getLangString(field.getTitle(), language));
        default:
            return ""; //$NON-NLS-1$
        }
    }

    /** Колонка «Заголовок» полей набора: синоним поля, иначе заголовок на текущем языке. */
    private static String dataSetFieldTitle(DataSetField field, IDataSetsWrapper wrapper,
        IV8Project project, String language)
    {
        String path = dataSetFieldPath(field);
        IDataSetWrapper.FieldSynonym synonym = path.isEmpty() ? null : wrapper.getFieldSynonym(path);
        if (synonym != null && !synonym.manual)
        {
            String text = synonym.synonym;
            return text == null || text.isEmpty() ? nullToEmpty(DcsUtil.dscPathToSynonym(path)) : text;
        }
        Presentation title = dataSetFieldTitleValue(field);
        if (title != null && project != null)
            return nullToEmpty(TypedValueFactory.INSTANCE.getValueText(project, language, title));
        return dataSetFieldName(field);
    }

    /** Колонка «Поле»: у папки поля нет. */
    private static String dataSetFieldName(DataSetField field)
    {
        if (field instanceof DataCompositionSchemaDataSetField schemaField)
            return nullToEmpty(schemaField.getField());
        if (field instanceof DataCompositionSchemaNestedDataSet nested)
            return nullToEmpty(nested.getField());
        return ""; //$NON-NLS-1$
    }

    /** Колонка «Путь к данным» полей набора. */
    private static String dataSetFieldPath(DataSetField field)
    {
        if (field instanceof DataCompositionSchemaDataSetField schemaField)
            return nullToEmpty(schemaField.getDataPath());
        if (field instanceof DataCompositionSchemaNestedDataSet nested)
            return nullToEmpty(nested.getDataPath());
        if (field instanceof DataCompositionSchemaDataSetFieldFolder folder)
            return nullToEmpty(folder.getDataPath());
        return ""; //$NON-NLS-1$
    }

    private static Presentation dataSetFieldTitleValue(DataSetField field)
    {
        if (field instanceof DataCompositionSchemaDataSetField schemaField)
            return schemaField.getTitle();
        if (field instanceof DataCompositionSchemaNestedDataSet nested)
            return nested.getTitle();
        if (field instanceof DataCompositionSchemaDataSetFieldFolder folder)
            return folder.getTitle();
        return null;
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    // =======================================================================
    // Страницы редактора
    // =======================================================================

    /** Страница редактора СКД нужного типа: активная, иначе первая подходящая. */
    private static <T extends EditorPage> T findPage(DataCompositionSchemaEditor editor,
        DataCompositionSchemaControlContext context, Class<T> pageClass)
    {
        EditorPage activePage = editor != null ? editor.getActivePage() : null;
        if (pageClass.isInstance(activePage))
            return pageClass.cast(activePage);
        for (EditorPage page : context.getPages())
            if (pageClass.isInstance(page))
                return pageClass.cast(page);
        return null;
    }
}
