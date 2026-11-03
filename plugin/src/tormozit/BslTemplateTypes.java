package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.common.IStaticExpressionValueComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.typesystem.IInvocationTypesComputerParametersDependent;
import com._1c.g5.v8.dt.bsl.typesystem.IInvocationTypesComputerExtension;
import com._1c.g5.v8.dt.dcs.model.core.Presentation;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.ContextDefWithRefItem;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.moxel.Cell;
import com._1c.g5.v8.dt.moxel.Drawing;
import com._1c.g5.v8.dt.moxel.NamedItemCells;
import com._1c.g5.v8.dt.moxel.NamedItemDrawing;
import com._1c.g5.v8.dt.moxel.SpreadsheetPoint;
import com._1c.g5.v8.dt.moxel.content.LocalString;
import com._1c.g5.v8.dt.moxel.geometry.GeometryTools;
import com._1c.g5.v8.dt.moxel.sheet.SheetAccessor;
import com._1c.g5.v8.dt.moxel.sheet.Selection;
import com._1c.g5.v8.dt.moxel.sheet.RowsSelection;
import com._1c.g5.v8.dt.moxel.sheet.ColumnsSelection;
import com._1c.g5.v8.dt.moxel.sheet.CellsSelection;
import com._1c.g5.v8.dt.moxel.sheet.TableSelection;

/**
 * Модель содержимого конкретного макета: коллекции СКД, параметры и рисунки табличного документа,
 * получение именованной области. GetTemplateInvocationTypesComputer из
 * com._1c.g5.v8.dt.bsl 28.0.1.v202605050943 уже разрешил макет, включая проект-родитель.
 * Обогащаем результат только этой успешной ветки, не повторяя поиск по имени.
 */
public final class BslTemplateTypes
    implements IInvocationTypesComputerExtension, IInvocationTypesComputerParametersDependent
{
    private static final String TARGET =
        "com._1c.g5.v8.dt.bsl.typesystem.GetTemplateInvocationTypesComputer"; //$NON-NLS-1$
    private static final String CALLBACK = "tormozit.template.contentTypes"; //$NON-NLS-1$
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean WEAVING_INSTALLED = new AtomicBoolean();
    private static final ThreadLocal<Set<Invocation>> AREA_VISITING =
        ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final Pattern PARAMETER_NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*"); //$NON-NLS-1$
    private static final Pattern TEXT_PARAMETER = Pattern.compile("\\[([\\p{L}_][\\p{L}\\p{N}_]*)\\]"); //$NON-NLS-1$

    public BslTemplateTypes() {}

    /** ПолучитьОбласть и имена наборов данных результата компоновщика макета. */
    @Override
    public List<TypeItem> getTypes(Invocation invocation)
    {
        if (!(invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(invocation))
            return List.of();
        boolean compose = "Execute".equalsIgnoreCase(access.getName()) || "Выполнить".equalsIgnoreCase(access.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        if (compose ? invocation.getParams().size() < 2 : invocation.getParams().size() != 1)
            return List.of();
        Set<Invocation> visiting = AREA_VISITING.get();
        if (!visiting.add(invocation))
            return List.of();
        try
        {
            var services = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(invocation.eResource().getURI());
            List<TypeItem> receivers = services.get(TypesComputer.class).computeTypes(access.getSource(), Environments.ALL);
            if (compose)
                return composeDataSetNames(invocation, services, receivers);
            var literal = services.get(IStaticExpressionValueComputer.class).getStringContent(invocation.getParams().get(0));
            if (literal == null)
                return List.of();
            String name = literal.getText();
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem source : receivers)
                for (TypeItem alternative : BslDocCommentTypeMerge.alternatives(source))
                    if (alternative instanceof Type type)
                        for (var adapter : type.eAdapters())
                            if (adapter instanceof SpreadsheetModel model)
                            {
                                SpreadsheetModel area = model.area(name);
                                if (area != null)
                                    result.add(spreadsheetType(type, area, invocation));
                            }
            return result;
        }
        catch (java.util.concurrent.CancellationException | org.eclipse.core.runtime.OperationCanceledException canceled)
        {
            throw canceled;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return List.of();
        }
        finally
        {
            visiting.remove(invocation);
            if (visiting.isEmpty())
                AREA_VISITING.remove();
        }
    }

    /** Только имена из первого параметра; типы элементов берутся у платформенной коллекции макета. */
    private static List<TypeItem> composeDataSetNames(Invocation invocation, IResourceServiceProvider services,
        List<TypeItem> receivers)
    {
        boolean composer = false;
        for (TypeItem receiver : receivers)
            for (TypeItem alternative : BslDocCommentTypeMerge.alternatives(receiver))
                if (EcoreUtil.resolve(alternative, invocation) instanceof Type type
                    && "DataCompositionTemplateComposer".equals(McoreUtil.getTypeName(type))) //$NON-NLS-1$
                    composer = true;
        if (!composer)
            return List.of();
        List<TypeItem> result = new ArrayList<>();
        for (TypeItem argument : services.get(TypesComputer.class).computeTypes(invocation.getParams().get(0), Environments.ALL))
            for (TypeItem alternative : BslDocCommentTypeMerge.alternatives(argument))
            {
                if (!(EcoreUtil.resolve(alternative, invocation) instanceof Type schema)
                    || !"DataCompositionSchema".equals(McoreUtil.getTypeName(schema)) || schema.getContextDef() == null) //$NON-NLS-1$
                    continue;
                Map<String, EObject> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (Property property : schema.getContextDef().allProperties())
                    if ("DataSets".equals(property.getName())) //$NON-NLS-1$
                        for (TypeItem collectionItem : property.getTypes())
                            if (EcoreUtil.resolve(collectionItem, invocation) instanceof Type collection
                                && collection.getContextDef() != null)
                                for (Property member : collection.getContextDef().allProperties())
                                    if (member instanceof DerivedProperty derived && derived.getSource() instanceof DataSet)
                                        names.put(member.getName(), null);
                if (names.isEmpty())
                    continue;
                Type template = elementType(List.of(), "DataCompositionTemplate", invocation); //$NON-NLS-1$
                if (template != null)
                {
                    namedProperties(template, "DataSets", names, null, invocation, false); //$NON-NLS-1$
                    result.add(template);
                }
            }
        return result;
    }

    /** Основной путь в EDT без разрешения самоприсоединения Java-агента. */
    static void installWeavingHook()
    {
        if (!WEAVING_INSTALLED.compareAndSet(false, true))
            return;
        System.getProperties().put(CALLBACK, (Function<Object[], Object>) BslTemplateTypes::afterTypes);
        var bundle = FrameworkUtil.getBundle(BslTemplateTypes.class);
        var context = bundle == null ? null : bundle.getBundleContext();
        if (context == null)
        {
            WEAVING_INSTALLED.set(false);
            return;
        }
        context.registerService(WeavingHook.class, new TemplateWeavingHook(), null);
    }

    static void install()
    {
        installWeavingHook();
        if (!INSTALLED.compareAndSet(false, true))
            return;
        System.getProperties().put(CALLBACK, (Function<Object[], Object>) BslTemplateTypes::afterTypes);
        boolean registered = BslDocCommentDescriptionFix.registerExtraTransformer(new TemplateTransformer(), TARGET);
        if (!registered)
            INSTALLED.set(false);
    }

    /** {штатные типы, вызов, найденный штатным вычислителем макет}. */
    private static Object afterTypes(Object[] args)
    {
        if (!(args[0] instanceof List<?> types) || !(args[1] instanceof Invocation invocation)
            || !(args[2] instanceof BasicTemplate template)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(invocation))
            return args[0];
        try
        {
            EObject content = template.getTemplate();
            if (content != null && content.eIsProxy())
                content = EcoreUtil.resolve(content, invocation);
            if (content == null || content.eIsProxy())
                return args[0];
            List<TypeItem> result = new ArrayList<>();
            for (Object item : types)
            {
                if (!(item instanceof TypeItem original))
                    continue;
                EObject resolved = EcoreUtil.resolve(original, invocation);
                if (resolved instanceof Type type && !type.eIsProxy()
                    && content instanceof DataCompositionSchema schema
                    && "DataCompositionSchema".equals(McoreUtil.getTypeName(type))) //$NON-NLS-1$
                    result.add(schemaType(type, schema, invocation));
                else if (resolved instanceof Type type && !type.eIsProxy()
                    && content instanceof SpreadsheetDocument document
                    && "SpreadsheetDocument".equals(McoreUtil.getTypeName(type))) //$NON-NLS-1$
                    result.add(spreadsheetType(type, new SpreadsheetModel(document), invocation));
                else
                    result.add(original);
            }
            return result;
        }
        catch (java.util.concurrent.CancellationException | org.eclipse.core.runtime.OperationCanceledException canceled)
        {
            throw canceled;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return args[0];
        }
    }

    private static Type schemaType(Type base, DataCompositionSchema schema, Invocation invocation)
    {
        Type result = copy(base);
        collection(result, "SettingVariants", schema.getSettingsVariants(), "DataCompositionSettingsVariant", invocation); //$NON-NLS-1$ //$NON-NLS-2$
        collection(result, "DataSets", schema.getDataSets(), null, invocation); //$NON-NLS-1$
        collection(result, "DataSources", schema.getDataSources(), "DataCompositionSchemaDataSource", invocation); //$NON-NLS-1$ //$NON-NLS-2$
        collection(result, "Parameters", schema.getParameters(), "DataCompositionSchemaParameter", invocation); //$NON-NLS-1$ //$NON-NLS-2$
        collection(result, "NestedDataCompositionSchema", schema.getNestedSchemas(), "NestedDataCompositionSchema", invocation); //$NON-NLS-1$ //$NON-NLS-2$
        collection(result, "Templates", schema.getTemplates(), "DataCompositionSchemaTemplateDescription", invocation); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    /** Копия хранит штатные методы и тип элемента обхода; общие объекты платформы не меняются. */
    private static Type copy(Type base)
    {
        Type result = BslDocCommentTypeMerge.copyWithRefContext(base, base.getEnvironments(), false);
        result.setIndexAccessible(base.isIndexAccessible());
        if (base.getCollectionElementTypes() != null)
        {
            var elements = McoreFactory.eINSTANCE.createTypeContainerRef();
            elements.getTypes().addAll(base.getCollectionElementTypes().allTypes());
            result.setCollectionElementTypes(elements);
        }
        return result;
    }

    private static void collection(Type owner, String propertyName, List<? extends EObject> items, String elementName,
        Invocation invocation)
    {
        Property property = property(owner, propertyName);
        if (property == null || items.isEmpty())
            return;
        List<TypeItem> replacements = new ArrayList<>();
        for (TypeItem item : property.getTypes())
        {
            if (!(EcoreUtil.resolve(item, invocation) instanceof Type base) || base.eIsProxy())
            {
                replacements.add(item);
                continue;
            }
            Type collection = copy(base);
            List<TypeItem> elements = base.getCollectionElementTypes() == null ? List.of()
                : base.getCollectionElementTypes().allTypes();
            for (EObject named : items)
            {
                Object rawName = feature(named, "name"); //$NON-NLS-1$
                if (!(rawName instanceof String name) || name.isBlank())
                    continue;
                String wanted = elementName != null ? elementName : named.eClass().getName();
                Type element = elementType(elements, wanted, invocation);
                if (element == null)
                    continue;
                if (named instanceof com._1c.g5.v8.dt.dcs.model.schema.NestedDataCompositionSchema nested
                    && nested.getSchema() != null)
                {
                    Property schema = property(element, "Schema"); //$NON-NLS-1$
                    if (schema != null && schema.getTypes().size() == 1
                        && EcoreUtil.resolve(schema.getTypes().get(0), invocation) instanceof Type type
                        && !type.eIsProxy())
                        replaceTypes(schema, List.of(schemaType(type, nested.getSchema(), invocation)));
                }
                DerivedProperty field = McoreFactory.eINSTANCE.createDerivedProperty();
                field.setName(name);
                field.setNameRu(name);
                field.setReadable(true);
                field.setEnvironments(Environments.ALL);
                field.setSource(named);
                replaceTypes(field, List.of(element));
                collection.getContextDef().getProperties().add(field);
            }
            replacements.add(collection);
        }
        replaceTypes(property, replacements);
    }

    private static Type elementType(List<TypeItem> elements, String name, EObject context)
    {
        for (TypeItem item : elements)
            if (EcoreUtil.resolve(item, context) instanceof Type type && name.equals(McoreUtil.getTypeName(type)))
                return copy(type);
        // Некоторые именованные коллекции EDT не задают collectionElementTypes.
        var rsp = org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(context.eResource().getURI());
        if (rsp == null)
            return null;
        var versions = rsp.get(com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport.class);
        var version = versions.getRuntimeVersionOrDefault(context, com._1c.g5.v8.dt.platform.version.Version.LATEST);
        var provider = com._1c.g5.v8.dt.platform.IEObjectProvider.Registry.INSTANCE
            .get(com._1c.g5.v8.dt.mcore.McorePackage.Literals.TYPE_ITEM, version);
        var description = provider == null ? null : provider.getEObjectDescription(name);
        EObject resolved = description == null ? null : EcoreUtil.resolve(description.getEObjectOrProxy(), context);
        return resolved instanceof Type type && !type.eIsProxy() ? copy(type) : null;
    }

    private static void replaceTypes(Property property, List<? extends TypeItem> types)
    {
        var container = McoreFactory.eINSTANCE.createTypeContainerRef();
        container.getTypes().addAll(types);
        property.setTypeContainer(container);
    }

    private static Property property(Type type, String name)
    {
        if (!(type.getContextDef() instanceof ContextDefWithRefItem context))
            return null;
        for (Property property : context.getProperties())
            if (name.equals(property.getName()))
                return property;
        for (Property original : new ArrayList<>(context.getRefProperties()))
            if (name.equals(original.getName()))
            {
                DerivedProperty property = McoreFactory.eINSTANCE.createDerivedProperty();
                property.setName(original.getName());
                property.setNameRu(original.getNameRu());
                property.setReadable(original.isReadable());
                property.setWritable(original.isWritable());
                property.setEnvironments(original.getEnvironments());
                property.setSource(original instanceof DerivedProperty derived ? derived.getSource() : original);
                replaceTypes(property, original.getTypes());
                context.getRefProperties().remove(original);
                context.getProperties().add(property);
                return property;
            }
        return null;
    }

    private static Object feature(EObject object, String name)
    {
        var feature = object.eClass().getEStructuralFeature(name);
        return feature == null ? null : object.eGet(feature);
    }

    private static Type spreadsheetType(Type base, SpreadsheetModel model, Invocation invocation)
    {
        Type result = copy(base);
        Map<String, EObject> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var row : model.document.getRows())
            for (var cell : row.getValue().getCells())
                if (model.contains(row.getKey(), cell.getKey()))
                    collectParameters(cell.getValue(), parameters);
        if (model.selections.isEmpty())
            for (Cell header : new Cell[] { model.document.getLeftHeader(), model.document.getCenterHeader(),
                model.document.getRightHeader(), model.document.getLeftFooter(), model.document.getCenterFooter(),
                model.document.getRightFooter() })
                if (header != null)
                    collectParameters(header, parameters);
        Map<String, EObject> drawings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<Drawing> included = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Drawing drawing : model.sheet.getAllDrawings())
            if (model.contains(drawing))
            {
                included.add(drawing);
                collectParameters(drawing, parameters);
            }
        for (var named : model.document.getNamedItems())
            if (named.getValue() instanceof NamedItemDrawing item)
                for (Drawing drawing : included)
                    if (drawing.getDrawingId() == item.getDrawingId())
                        drawings.putIfAbsent(named.getKey(), drawing);
        namedProperties(result, "Parameters", parameters, "Arbitrary", invocation, true); //$NON-NLS-1$ //$NON-NLS-2$
        namedProperties(result, "Drawings", drawings, "SpreadsheetDocumentDrawing", invocation, false); //$NON-NLS-1$ //$NON-NLS-2$
        result.eAdapters().add(model);
        return result;
    }

    private static void namedProperties(Type owner, String propertyName, Map<String, EObject> names,
        String elementName, Invocation invocation, boolean writable)
    {
        Property property = property(owner, propertyName);
        if (property == null)
            return;
        Type element = elementName == null ? null : elementType(List.of(), elementName, invocation);
        if (elementName != null && element == null)
            return;
        String placeholder = switch (propertyName)
        {
            case "Parameters" -> "ParameterName"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Drawings" -> "DrawingName"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> ""; //$NON-NLS-1$
        };
        List<TypeItem> types = new ArrayList<>();
        for (TypeItem original : property.getTypes())
            if (EcoreUtil.resolve(original, invocation) instanceof Type base && !base.eIsProxy())
            {
                Type collection = copy(base);
                // Убираем шаблон имени платформы и члены предыдущей области до добавления конкретных имён.
                collection.getContextDef().getProperties().removeIf(field -> placeholder.equals(field.getName())
                    || field instanceof DerivedProperty derived && isSpreadsheetSource(derived.getSource()));
                if (collection.getContextDef() instanceof ContextDefWithRefItem context)
                    context.getRefProperties().removeIf(field -> placeholder.equals(field.getName())
                        || field instanceof DerivedProperty derived && isSpreadsheetSource(derived.getSource()));
                for (var entry : names.entrySet())
                {
                    DerivedProperty field = McoreFactory.eINSTANCE.createDerivedProperty();
                    field.setName(entry.getKey());
                    field.setNameRu(entry.getKey());
                    field.setReadable(true);
                    field.setWritable(writable);
                    field.setEnvironments(base.getEnvironments());
                    field.setSource(entry.getValue());
                    replaceTypes(field, element != null ? List.of(element) : base.getCollectionElementTypes().allTypes());
                    collection.getContextDef().getProperties().add(field);
                }
                types.add(collection);
            }
            else
                types.add(original);
        replaceTypes(property, types);
    }

    private static boolean isSpreadsheetSource(EObject source)
    {
        return source instanceof Cell || source instanceof Drawing;
    }

    /** Поля модели соответствуют parameter/detailParameter/pictureParameter в сериализованном макете. */
    private static void collectParameters(EObject object, Map<String, EObject> names)
    {
        if (object == null)
            return;
        for (String feature : List.of("parameter", "detailParameter", "pictureParameter")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (feature(object, feature) instanceof String name && PARAMETER_NAME.matcher(name).matches())
                names.putIfAbsent(name, object);
        if (feature(object, "text") instanceof LocalString text) //$NON-NLS-1$
            for (var translation : text.getContent())
            {
                if (translation.getValue() == null)
                    continue;
                var matcher = TEXT_PARAMETER.matcher(translation.getValue());
                while (matcher.find())
                    names.putIfAbsent(matcher.group(1), object);
            }
    }

    /** Модель хранит только ссылки; исходный табличный документ не копируется и не сериализуется. */
    private static final class SpreadsheetModel extends AdapterImpl
    {
        final SpreadsheetDocument document;
        final SheetAccessor sheet;
        final List<Selection> selections;
        final String name;

        SpreadsheetModel(SpreadsheetDocument document)
        {
            this(document, new SheetAccessor(document), List.of(), ""); //$NON-NLS-1$
        }

        private SpreadsheetModel(SpreadsheetDocument document, SheetAccessor sheet, List<Selection> selections,
            String name)
        {
            this.document = document;
            this.sheet = sheet;
            this.selections = selections;
            this.name = name;
        }

        SpreadsheetModel area(String name)
        {
            for (var entry : document.getNamedItems())
                if (name.equalsIgnoreCase(entry.getKey()) && entry.getValue() instanceof NamedItemCells cells)
                {
                    Selection selection = sheet.getSelectionForArea(cells.getArea());
                    if (selection == null)
                        return null;
                    List<Selection> scoped = new ArrayList<>(selections);
                    scoped.add(selection);
                    return new SpreadsheetModel(document, sheet, List.copyOf(scoped), name);
                }
            return null;
        }

        boolean contains(int row, int column)
        {
            return selections.stream().allMatch(selection -> sheet.isCellInSelection(selection, row, column));
        }

        /** Отбор как в ClipboardCopyUtil EDT: строки — пересечение, ячейки/колонки — весь рисунок. */
        boolean contains(Drawing drawing)
        {
            for (Selection selection : selections)
            {
                if (selection instanceof TableSelection)
                    continue;
                if (selection instanceof RowsSelection rows)
                {
                    if (!sheet.getDrawingsInRows(rows.getNormalizedBeginRowIndex(),
                        rows.getNormalizedEndRowIndex(), true).contains(drawing))
                        return false;
                    continue;
                }
                if (drawing.getPosition() == null)
                    return false;
                var position = GeometryTools.normalizeRect(drawing.getPosition());
                if (selection instanceof CellsSelection cells)
                {
                    var bounds = cells.getNormalizedPosition();
                    if (!inside(position.getBegin(), position.getEnd(), bounds.x, bounds.x + bounds.width + 1, true)
                        || !inside(position.getBegin(), position.getEnd(), bounds.y, bounds.y + bounds.height + 1, false))
                        return false;
                }
                else if (selection instanceof ColumnsSelection columns)
                {
                    if (!inside(position.getBegin(), position.getEnd(), columns.getNormalizedBeginColumnIndex(),
                        columns.getNormalizedEndColumnIndex() + 1, true))
                        return false;
                }
                else
                    return false;
            }
            return true;
        }

        private static boolean inside(SpreadsheetPoint begin, SpreadsheetPoint end, int first, int afterLast,
            boolean horizontal)
        {
            return compare(begin, first, horizontal) >= 0 && compare(end, afterLast, horizontal) <= 0;
        }

        private static int compare(SpreadsheetPoint point, int boundary, boolean horizontal)
        {
            int cell = horizontal ? point.getCell().getX() : point.getCell().getY();
            int offset = horizontal ? point.getOffset().getX() : point.getOffset().getY();
            return cell == boundary ? Integer.compare(offset, 0) : Integer.compare(cell, boundary);
        }
    }

    /** Общий текст для страницы автодополнения и подсказки при наведении. */
    static String propertyDescription(Property property)
    {
        if (!(property instanceof DerivedProperty derived) || derived.getSource() == null)
            return null;
        EObject source = derived.getSource();
        if (isSpreadsheetSource(source))
            return (source instanceof Drawing && !property.isWritable() ? "Рисунок табличного документа: " //$NON-NLS-1$
                : "Параметр табличного документа: ") + property.getNameRu(); //$NON-NLS-1$
        if (!(source.eContainer() instanceof DataCompositionSchema))
            return null;
        StringBuilder text = new StringBuilder("Элемент схемы компоновки данных: ").append(property.getNameRu()); //$NON-NLS-1$
        Presentation title = source instanceof SettingsVariant variant ? variant.getPresentation()
            : source instanceof DataCompositionSchemaParameter parameter ? parameter.getTitle()
            : feature(source, "title") instanceof Presentation presentation ? presentation : null; //$NON-NLS-1$
        if (title != null)
        {
            if (title.getValue() != null && !title.getValue().isBlank())
                text.append("\n").append(title.getValue()); //$NON-NLS-1$
            if (title.getLocalValue() != null)
                for (var value : title.getLocalValue().getContent())
                    text.append("\n").append(value.getKey()).append(": ").append(value.getValue()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (source instanceof DataCompositionSchemaDataSetQuery query && query.getQuery() != null)
            text.append("\n\n").append(query.getQuery()); //$NON-NLS-1$
        return text.toString();
    }

    private static final class TemplateTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain,
            byte[] bytes)
        {
            if (!TARGET.replace('.', '/').equals(name))
                return null;
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean touched = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"computeTypes".equals(name) || !descriptor.equals( //$NON-NLS-1$
                        "(Lcom/_1c/g5/v8/dt/bsl/model/Invocation;Ljava/lang/String;)Ljava/util/List;")) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        private int templateSlot;
                        private int lastLoad;

                        @Override
                        public void visitVarInsn(int opcode, int slot)
                        {
                            super.visitVarInsn(opcode, slot);
                            lastLoad = opcode == Opcodes.ALOAD ? slot : 0;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            if ("getTemplateType".equals(name)) //$NON-NLS-1$
                                templateSlot = owner.equals("com/_1c/g5/v8/dt/metadata/mdclass/CommonTemplate") //$NON-NLS-1$
                                    || owner.equals("com/_1c/g5/v8/dt/metadata/mdclass/Template") ? lastLoad : 0; //$NON-NLS-1$
                            if (templateSlot == 0 || !TARGET.replace('.', '/').equals(owner)
                                || !"getTypesByTemplateType".equals(name)) //$NON-NLS-1$
                                return;
                            // Берём локальную переменную непосредственно перед getTemplateType().
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            super.visitLdcInsn(CALLBACK);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.SWAP);
                            super.visitInsn(Opcodes.ICONST_3);
                            super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.DUP_X1);
                            super.visitInsn(Opcodes.SWAP);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitInsn(Opcodes.SWAP);
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitInsn(Opcodes.DUP);
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitInsn(Opcodes.DUP);
                            super.visitInsn(Opcodes.ICONST_2);
                            super.visitVarInsn(Opcodes.ALOAD, templateSlot);
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List"); //$NON-NLS-1$
                            touched.set(true);
                            templateSlot = 0;
                        }
                    };
                }
            }, 0);
            return touched.get() ? writer.toByteArray() : null;
        }
    }

    private static final class TemplateWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                byte[] transformed = new TemplateTransformer().transform(null,
                    TARGET.replace('.', '/'), null, null, woven.getBytes());
                if (transformed != null)
                    woven.setBytes(transformed);
            }
            catch (RuntimeException | LinkageError failure)
            {
            }
        }
    }
}

