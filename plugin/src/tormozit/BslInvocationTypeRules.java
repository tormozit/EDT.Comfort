package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.util.Pair;

import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.model.BooleanLiteral;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.NumberLiteral;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.model.VariablesScopeSpec;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.typesystem.IInvocationTypesComputerExtension;
import com._1c.g5.v8.dt.bsl.typesystem.IInvocationTypesComputerParametersDependent;
import com._1c.g5.v8.dt.bsl.typesystem.InvocationTypesComputerExtensionRegistry;
import com._1c.g5.v8.dt.bsl.typesystem.ValueTableDynamicContextDefProvider;
import com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainerDef;
import com._1c.g5.v8.dt.mcore.TypeContainerRef;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.md.resource.MdTypeUtil;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Правила вычисления результата функции по фактическим параметрам — перенос из ИР
 * ({@code ирКлсПолеТекстаПрограммы.НайтиПравилоВычисленияФункции} и функции {@code Вычислить…}, issue #893).
 * Перенесены правила, которых нет в EDT; правила, которые EDT считает штатно, не дублируются.
 *
 * <p>Правило ищется как в ИР: по типу контекста и имени метода, затем среди правил «*». Если тот же вызов
 * посчитал другой вычислитель этой точки расширения (штатный EDT, ssl-support, другие расширения «Комфорта»),
 * правило молчит: реестр EDT складывает результаты всех вычислителей, и ответ двух дал бы двойной тип.
 *
 * <p>Расхождения с ИР — только там, где в EDT иначе нельзя: ИР получает типы реквизитов пустым запросом к базе
 * и макеты — вызовом на сервере, здесь они берутся из модели метаданных проекта.
 *
 * <p>Работает при флажке проекта «Расширенный расчет типов», как и прочие расширения системы типов плагина.
 */
public class BslInvocationTypeRules
    implements IInvocationTypesComputerExtension
{
    // Временный переключатель контрольного замера CPU для обоих расширений, включая WithParameters.
    private static final boolean TEMPORARILY_DISABLED_FOR_CPU_COMPARISON = false;

    public BslInvocationTypeRules()
    {
        Global.tempLog("invocation-type-disabled", "CPU comparison version=1.0.18 disabled=" //$NON-NLS-1$ //$NON-NLS-2$
            + TEMPORARILY_DISABLED_FOR_CPU_COMPARISON + " class=" + getClass().getName()); //$NON-NLS-1$
    }

    /** Те же правила для вызовов, тип которых зависит от типов переменных-параметров. */
    public static final class WithParameters
        extends BslInvocationTypeRules
        implements IInvocationTypesComputerParametersDependent
    {
    }

    private enum Rule
    {
        COMMON_MODULE, ATTRIBUTES_VALUES, ATTRIBUTE_VALUE, COPY_RECURSIVE, ROW_TO_STRUCTURE, TABLE_TO_ARRAY,
        PRINT_FORM_TEMPLATE, OBJECT_MANAGER, COLLECTION_ADD, COPY_COLUMNS, UNLOAD_COLUMN, FORM_DATA_TO_VALUE,
        READ_JSON, STORED_VALUE, READ_JSON_VALUE
    }

    /** Контекст правила ИР ({@code ТипКонтекста}). */
    private enum Context
    {
        /** «*» — любой получатель. */
        ANY,
        /** «Глобальный» — вызов без получателя. */
        GLOBAL,
        /** Получатель — таблица значений. */
        VALUE_TABLE,
        /** «ОбщийМодуль.ОбщегоНазначения». */
        COMMON,
        /** «ОбщийМодуль.УправлениеПечатью». */
        PRINT_MANAGEMENT
    }

    private record Entry(Rule rule, Context context)
    {
    }

    private static final Map<String, Entry> RULES = new java.util.HashMap<>();

    private static void rule(Rule rule, Context context, String... names)
    {
        for (String name : names)
            RULES.put(name.toLowerCase(Locale.ROOT), new Entry(rule, context));
    }

    static
    {
        // БСП
        rule(Rule.COMMON_MODULE, Context.ANY, "ОбщийМодуль", "CommonModule"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.ATTRIBUTES_VALUES, Context.ANY, "ЗначенияРеквизитовОбъекта", "ObjectAttributesValues", //$NON-NLS-1$ //$NON-NLS-2$
            "ЗначенияРеквизитовОбъектов", "ObjectsAttributesValues"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.ATTRIBUTE_VALUE, Context.ANY, "ЗначениеРеквизитаОбъекта", "ObjectAttributeValue"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.COPY_RECURSIVE, Context.COMMON, "СкопироватьРекурсивно", "CopyRecursive"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.ROW_TO_STRUCTURE, Context.COMMON, "СтрокаТаблицыЗначенийВСтруктуру", "ValueTableRowToStructure"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.TABLE_TO_ARRAY, Context.COMMON, "ТаблицаЗначенийВМассив", "ValueTableToArray"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.PRINT_FORM_TEMPLATE, Context.PRINT_MANAGEMENT, "МакетПечатнойФормы", "PrintFormTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        // В ИР контекст этих двух правил записан как «ОбщегоНазначения» без «ОбщийМодуль.»
        rule(Rule.OBJECT_MANAGER, Context.COMMON, "МенеджерОбъектаПоПолномуИмени", "ObjectManagerByFullName", //$NON-NLS-1$ //$NON-NLS-2$
            "МенеджерОбъектаПоСсылке", "ObjectManagerByRef"); //$NON-NLS-1$ //$NON-NLS-2$
        // Платформа
        rule(Rule.COLLECTION_ADD, Context.ANY, "Добавить", "Add", "Вставить", "Insert"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        rule(Rule.COPY_COLUMNS, Context.VALUE_TABLE, "Скопировать", "Copy", "СкопироватьКолонки", "CopyColumns"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        rule(Rule.COPY_COLUMNS, Context.ANY, "ВыгрузитьКолонки", "UnloadColumns", "Выгрузить", "Unload"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        rule(Rule.UNLOAD_COLUMN, Context.ANY, "ВыгрузитьКолонку", "UnloadColumn"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.FORM_DATA_TO_VALUE, Context.GLOBAL, "ДанныеФормыВЗначение", "FormDataToValue"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.READ_JSON, Context.GLOBAL, "ПрочитатьJSON", "ReadJSON"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.STORED_VALUE, Context.GLOBAL, "ПолучитьИзВременногоХранилища", "GetFromTempStorage", //$NON-NLS-1$ //$NON-NLS-2$
            "ЗначениеИзСтрокиВнутр", "ValueFromStringInternal"); //$NON-NLS-1$ //$NON-NLS-2$
        rule(Rule.READ_JSON_VALUE, Context.GLOBAL, "ПрочитатьЗначениеJSON", "ReadJSONValue"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Функции, результат которых — строка, «несущая» тип своего первого параметра
     * ({@code ВычислитьПоместитьВоВременноеХранилище}: в ИР тип кладётся в метаданные строки).
     */
    private static final Set<String> WRITERS = Set.of("поместитьвовременноехранилище", "puttotempstorage", //$NON-NLS-1$ //$NON-NLS-2$
        "значениевстрокувнутр", "valuetostringinternal", "записатьзначениеjson", "writejsonvalue"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private static final Set<String> COMMON_MODULES = Set.of("общегоназначения", "common"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> PRINT_MANAGEMENT_MODULES = Set.of("управлениепечатью", "printmanagement"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*"); //$NON-NLS-1$
    private static final Pattern ALIAS = Pattern.compile("(?iu)\\s+(?:как|as)\\s+"); //$NON-NLS-1$

    /** Вызовы, для которых сейчас считается штатный тип ({@link #stockTypes}): правило для них молчит. */
    private static final ThreadLocal<Set<Invocation>> STOCK =
        ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    /** Цикл зависимостей писателя и читателя хранилища: тот же AST-вызов уже вычисляется этим правилом. */
    private static final ThreadLocal<Set<Invocation>> COMPUTING =
        ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    @Override
    public List<TypeItem> getTypes(Invocation invocation)
    {
        if (TEMPORARILY_DISABLED_FOR_CPU_COMPARISON)
            return List.of();
        FeatureAccess access = invocation.getMethodAccess();
        Entry entry = access == null || access.getName() == null ? null
            : RULES.get(access.getName().toLowerCase(Locale.ROOT));
        if (entry == null || STOCK.get().contains(invocation)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(invocation))
            return List.of();
        Set<Invocation> computing = COMPUTING.get();
        boolean entered = computing.add(invocation);
        if (!entered)
            return List.of();
        try
        {
            Call call = new Call(invocation, access);
            if (!call.inContext(entry.context()) || call.computedByOthers())
                return List.of();
            List<TypeItem> types = call.compute(entry.rule());
            return types == null ? List.of() : types;
        }
        catch (RuntimeException | LinkageError failure)
        {
            Global.logError("BslInvocationTypeRules", access.getName(), failure); //$NON-NLS-1$
            return List.of();
        }
        finally
        {
            computing.remove(invocation);
            if (computing.isEmpty())
                COMPUTING.remove();
        }
    }

    /** Сервисы системы типов BSL: одни на всю среду, берутся при первом вызове. */
    private record Services(TypesComputer types, DynamicFeatureAccessComputer features, IScopeProvider scopes,
        IRuntimeVersionSupport versions, InvocationTypesComputerExtensionRegistry registry,
        ValueTableDynamicContextDefProvider valueTables)
    {
        private static volatile Services instance;

        static Services of(Resource resource)
        {
            Services current = instance;
            if (current != null)
                return current;
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(resource.getURI());
            current = new Services(rsp.get(TypesComputer.class), rsp.get(DynamicFeatureAccessComputer.class),
                rsp.get(IScopeProvider.class), rsp.get(IRuntimeVersionSupport.class),
                rsp.get(InvocationTypesComputerExtensionRegistry.class),
                rsp.get(ValueTableDynamicContextDefProvider.class));
            instance = current;
            return current;
        }
    }

    /** Один вызов: {@code РодительскаяСтруктураТипа} — получатель, {@code МассивПараметров} — параметры. */
    private static final class Call
    {
        final Invocation invocation;
        final FeatureAccess access;
        final String name;
        final List<Expression> params;
        final Resource resource;
        final Services services;
        final Environments environments;

        Call(Invocation invocation, FeatureAccess access)
        {
            this.invocation = invocation;
            this.access = access;
            this.name = access.getName();
            this.params = invocation.getParams();
            this.resource = invocation.eResource();
            this.services = Services.of(resource);
            Environmental environmental = EcoreUtil2.getContainerOfType(invocation, Environmental.class);
            this.environments = environmental != null ? environmental.environments() : Environments.ALL;
        }

        // ---- выбор правила ----

        boolean inContext(Context context)
        {
            return switch (context)
            {
                case ANY -> true;
                case GLOBAL -> access instanceof StaticFeatureAccess;
                case VALUE_TABLE -> receiverTypes().stream().anyMatch(type -> "ValueTable".equals(typeName(type))); //$NON-NLS-1$
                case COMMON -> COMMON_MODULES.contains(lower(receiverModule()));
                case PRINT_MANAGEMENT -> PRINT_MANAGEMENT_MODULES.contains(lower(receiverModule()));
            };
        }

        /** Имя общего модуля-получателя; у вызова без получателя — модуль, в котором вызов записан. */
        String receiverModule()
        {
            if (access instanceof DynamicFeatureAccess dynamic)
                return dynamic.getSource() instanceof StaticFeatureAccess source ? source.getName() : null;
            URI uri = resource.getURI();
            return uri.segmentCount() >= 2 ? URI.decode(uri.segment(uri.segmentCount() - 2)) : null;
        }

        boolean computedByOthers()
        {
            for (IInvocationTypesComputerExtension computer : services.registry().getComputer(name))
                if (!(computer instanceof BslInvocationTypeRules) && !computer.getTypes(invocation).isEmpty())
                    return true;
            return false;
        }

        List<TypeItem> compute(Rule rule)
        {
            return switch (rule)
            {
                case COMMON_MODULE -> commonModule();
                case ATTRIBUTES_VALUES -> attributesValues();
                case ATTRIBUTE_VALUE -> attributeValue();
                case COPY_RECURSIVE -> copyRecursive();
                case ROW_TO_STRUCTURE -> rowToStructure();
                case TABLE_TO_ARRAY -> tableToArray();
                case PRINT_FORM_TEMPLATE -> printFormTemplate();
                case OBJECT_MANAGER -> objectManager();
                case COLLECTION_ADD -> collectionAdd();
                case COPY_COLUMNS -> copyColumns();
                case UNLOAD_COLUMN -> unloadColumn();
                case FORM_DATA_TO_VALUE -> formDataToValue();
                case READ_JSON -> readJson();
                case STORED_VALUE -> storedValue();
                case READ_JSON_VALUE -> readJsonValue();
            };
        }

        // ---- правила ----

        /** {@code ВычислитьОбщийМодуль}. */
        List<TypeItem> commonModule()
        {
            String moduleName = stringValue(param(0));
            if (moduleName == null || !IDENTIFIER.matcher(moduleName).matches() || commonModuleMd(moduleName) == null)
                return null;
            VariablesScopeSpec spec = BslFactory.eINSTANCE.createVariablesScopeSpec();
            spec.setModule(EcoreUtil2.getContainerOfType(invocation, Module.class));
            spec.setMethod(EcoreUtil2.getContainerOfType(invocation, Method.class));
            spec.setEnvironments(environments);
            IScope scope = services.scopes().getScope(spec, BslPackage.Literals.FAKE_REFERENCE__PROPERTY);
            IEObjectDescription element = scope.getSingleElement(QualifiedName.create(moduleName));
            if (element == null)
                return null;
            EObject property = EcoreUtil.resolve(element.getEObjectOrProxy(), invocation);
            return property instanceof Property found ? new ArrayList<>(found.getTypes()) : null;
        }

        /** {@code ВычислитьЗначенияРеквизитовОбъектаБСП}. */
        List<TypeItem> attributesValues()
        {
            if (params.size() < 2)
                return null;
            String names = null;
            Type structure = null;
            List<TypeItem> nameTypes = typesOf(param(1));
            if (!nameTypes.isEmpty())
            {
                String first = typeName(nameTypes.get(0));
                if ("String".equals(first)) //$NON-NLS-1$
                {
                    names = stringValue(param(1));
                    if (names == null)
                        return null;
                }
                else if ("Structure".equals(first) && resolve(nameTypes.get(0)) instanceof Type type) //$NON-NLS-1$
                    structure = type;
                else
                    return null;
            }
            boolean single = true;
            List<TypeItem> refs = typesOf(param(0));
            if (!refs.isEmpty() && "Array".equals(typeName(refs.get(0)))) //$NON-NLS-1$
            {
                single = false;
                refs = new ArrayList<>(TypeSystemUtil.getCollectionElementTypes(List.of(refs.get(0)), invocation));
            }
            Type result = null;
            if (names != null && !names.isBlank() && !refs.isEmpty() && MdTypeUtil.getTypeProducer(refs.get(0)) != null)
                result = attributesStructure(refs.get(0), names);
            if (result == null)
                result = structure != null ? copyStructure(structure) : namesStructure(names);
            if (result == null)
                return null;
            if (single)
                return List.of(result);
            return List.of(mapType(refs, List.of(result)));
        }

        /**
         * Поля пустого запроса ИР {@code ВЫБРАТЬ ПЕРВЫЕ 0 Т.<Имена> ИЗ <Таблица> КАК Т}: имя колонки — псевдоним
         * или путь без точек, тип — тип реквизита по пути. Путь не найден — запрос ИР падает, полей нет.
         */
        Type attributesStructure(TypeItem ref, String names)
        {
            Map<String, Collection<TypeItem>> fields = new LinkedHashMap<>();
            for (String part : names.replace("\r", "").replace("\n", " ").split(",")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {
                String[] pathAndAlias = ALIAS.split(part.trim(), 2);
                String path = pathAndAlias[0].trim();
                if (path.isEmpty())
                    return null;
                String key = pathAndAlias.length > 1 ? pathAndAlias[1].trim() : path.replace(".", ""); //$NON-NLS-1$ //$NON-NLS-2$
                Property property = propertyByPath(List.of(ref), path);
                if (property == null || !IDENTIFIER.matcher(key).matches())
                    return null;
                fields.put(key, property.getTypes());
            }
            return newStructure(fields);
        }

        /** {@code Новый Структура(ИменаРеквизитов)}: ключи без типов; не имена — структуры нет. */
        Type namesStructure(String names)
        {
            if (names == null || names.isBlank())
                return null;
            Map<String, Collection<TypeItem>> fields = new LinkedHashMap<>();
            for (String part : names.split(",")) //$NON-NLS-1$
            {
                String key = part.trim();
                if (!IDENTIFIER.matcher(key).matches())
                    return null;
                fields.put(key, List.of());
            }
            return newStructure(fields);
        }

        /** {@code ВычислитьЗначениеРеквизитаОбъектаБСП}: значение реквизита пустой ссылки первого подходящего типа. */
        List<TypeItem> attributeValue()
        {
            if (params.size() < 2)
                return null;
            String attribute = stringValue(param(1));
            if (attribute == null || !IDENTIFIER.matcher(attribute).matches())
                return null;
            for (TypeItem ref : typesOf(param(0)))
            {
                if (MdTypeUtil.getTypeProducer(ref) == null)
                    continue;
                Property property = propertyByPath(List.of(ref), attribute);
                if (property != null)
                    return new ArrayList<>(property.getTypes());
            }
            return null;
        }

        /** {@code ВычислитьФункцияКопированияПараметра}. */
        List<TypeItem> copyRecursive()
        {
            List<TypeItem> types = params.isEmpty() ? List.of() : typesOf(param(0));
            return types.isEmpty() ? null : new ArrayList<>(types);
        }

        /** {@code ВычислитьСтрокаТаблицыЗначенийВСтруктуруБСП}. */
        List<TypeItem> rowToStructure()
        {
            Type structure = params.isEmpty() ? null : rowStructure(typesOf(param(0)));
            return structure == null ? null : List.of(structure);
        }

        /** {@code ВычислитьТаблицаЗначенийВМассивБСП}: массив структур строки {@code Таблица[0]}. */
        List<TypeItem> tableToArray()
        {
            if (params.isEmpty())
                return null;
            List<TypeItem> rows = new ArrayList<>(TypeSystemUtil.getCollectionElementTypes(typesOf(param(0)), invocation));
            Type structure = rowStructure(rows);
            return List.of(arrayType(structure == null ? List.of() : List.of(structure)));
        }

        /** {@code ВычислитьМакетПечатнойФормыБСП}: макет по полному имени «Документ.Х.Макет» или «ОбщийМакет.Х». */
        List<TypeItem> printFormTemplate()
        {
            String path = stringValue(param(0));
            if (path == null)
                return null;
            String[] parts = path.trim().split("\\."); //$NON-NLS-1$
            BasicTemplate template = null;
            if (parts.length == 2 && ("ОбщийМакет".equalsIgnoreCase(parts[0]) || "CommonTemplate".equalsIgnoreCase(parts[0]))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Configuration configuration = configuration();
                if (configuration != null)
                    for (BasicTemplate common : configuration.getCommonTemplates())
                        if (parts[1].equalsIgnoreCase(common.getName()))
                            template = common;
            }
            else if (parts.length == 3)
            {
                MdObject owner = managerProducer(parts[0], parts[1]);
                var feature = owner == null ? null : owner.eClass().getEStructuralFeature("templates"); //$NON-NLS-1$
                if (feature != null && owner.eGet(feature) instanceof List<?> templates)
                    for (Object candidate : templates)
                        if (candidate instanceof BasicTemplate basic && parts[2].equalsIgnoreCase(basic.getName()))
                            template = basic;
            }
            String typeName = template == null ? null : templateTypeName(template.getTemplateType());
            Type type = typeName == null ? null : platformType(typeName);
            return type == null ? null : List.of(type);
        }

        /** {@code ВычислитьПолучитьМенеджерЛкс}: менеджер по ссылке или по полному имени «Справочник.Х». */
        List<TypeItem> objectManager()
        {
            if (params.isEmpty())
                return null;
            String fullName = stringValue(param(0));
            if (fullName != null)
            {
                String[] parts = fullName.trim().split("\\."); //$NON-NLS-1$
                Type manager = parts.length == 2 ? managerType(MdTypeMapping.anyToEnSing(parts[0]), parts[1]) : null;
                return manager == null ? null : List.of(manager);
            }
            for (TypeItem type : typesOf(param(0)))
            {
                MdObject md = MdTypeUtil.getTypeProducer(type);
                Type manager = md == null ? null : managerType(md.eClass().getName(), md.getName());
                if (manager != null)
                    return List.of(manager);
            }
            return null;
        }

        /**
         * {@code ВычислитьКоллекцияДобавить}: из нескольких типов элемента штатного результата
         * ({@code Элементы.Добавить(Тип("…"))}) — тип, указанный параметром {@code Тип("…")} или {@code ТипЗнч(…)}.
         */
        List<TypeItem> collectionAdd()
        {
            if (params.isEmpty() || param(0) == null)
                return null;
            List<TypeItem> stock = stockTypes();
            if (stock.size() < 2)
                return null;
            int index = "ВсеЭлементыФормы".equals(receiverTypes().stream().map(Call::typeNameRu).findFirst().orElse(null)) ? 1 //$NON-NLS-1$
                : name.equalsIgnoreCase("Вставить") || name.equalsIgnoreCase("Insert") ? 1 : 0; //$NON-NLS-1$ //$NON-NLS-2$
            Expression typeExpression = param(index);
            String typeLiteral = typeLiteral(typeExpression);
            if (typeLiteral != null)
            {
                Type type = typeByName(typeLiteral);
                if (type == null)
                    return null;
                for (TypeItem candidate : stock)
                    if (typeName(type).equals(typeName(candidate)))
                        return List.of(candidate);
                return List.of(type);
            }
            Expression valueTypeOf = typeOfArgument(typeExpression);
            if (valueTypeOf == null)
                return null;
            List<TypeItem> types = typesOf(valueTypeOf);
            return types.isEmpty() ? null : new ArrayList<>(types);
        }

        /**
         * {@code ВычислитьСкопироватьКолонки}: таблица значений с колонками получателя; имена колонок —
         * второй параметр у {@code Скопировать/Выгрузить}, первый у остальных. Колонки нет — ИР ловит исключение.
         */
        List<TypeItem> copyColumns()
        {
            boolean second = name.equalsIgnoreCase("Скопировать") || name.equalsIgnoreCase("Copy") //$NON-NLS-1$ //$NON-NLS-2$
                || name.equalsIgnoreCase("Выгрузить") || name.equalsIgnoreCase("Unload"); //$NON-NLS-1$ //$NON-NLS-2$
            Expression namesExpression = param(second ? 1 : 0);
            String names = namesExpression == null ? "" : stringValue(namesExpression); //$NON-NLS-1$
            if (names == null)
                return null;
            List<Property> columns = rowProperties(receiverRows());
            if (columns.isEmpty())
                return null;
            if (!names.isBlank())
            {
                List<Property> selected = new ArrayList<>();
                for (String part : names.split(",")) //$NON-NLS-1$
                {
                    Property column = byName(columns, part.trim());
                    if (column == null)
                        return null;
                    selected.add(column);
                }
                columns = selected;
            }
            Type table = valueTableType(columns);
            return table == null ? null : List.of(table);
        }

        /** {@code ВычислитьТаблицаЗначенийВыгрузитьКолонку}: массив значений колонки (имя или номер). */
        List<TypeItem> unloadColumn()
        {
            if (params.isEmpty() || receiverTypes().stream().anyMatch(type -> typeName(type).startsWith("CommonModule"))) //$NON-NLS-1$
                return null;
            List<Property> columns = rowProperties(receiverRows());
            Property column = null;
            if (param(0) instanceof NumberLiteral number)
            {
                int index = parseIndex(text(number));
                if (index >= 0 && index < columns.size())
                    column = columns.get(index);
            }
            else
            {
                String columnName = stringValue(param(0));
                if (columnName != null && IDENTIFIER.matcher(columnName).matches())
                    column = byName(columns, columnName);
            }
            return column == null || column.getTypes().isEmpty() ? null
                : List.of(arrayType(new ArrayList<>(column.getTypes())));
        }

        /** {@code ВычислитьДанныеФормыВЗначение}: тип из второго параметра {@code Тип("…")}. */
        List<TypeItem> formDataToValue()
        {
            String typeLiteral = params.size() < 2 ? null : typeLiteral(param(1));
            Type type = typeLiteral == null ? null : typeByName(typeLiteral);
            return type == null ? null : List.of(type);
        }

        /**
         * {@code ВычислитьПрочитатьJSON}: по второму параметру — {@code Массив, Структура} (нет, Ложь),
         * {@code Массив, Соответствие} (Истина), иначе все три.
         */
        List<TypeItem> readJson()
        {
            Expression second = param(1);
            String flag = second == null ? "" : text(second); //$NON-NLS-1$
            List<String> names;
            if (flag.isEmpty() || flag.equalsIgnoreCase("Ложь") || flag.equalsIgnoreCase("False")) //$NON-NLS-1$ //$NON-NLS-2$
                names = List.of("Array", "Structure"); //$NON-NLS-1$ //$NON-NLS-2$
            else if (flag.equalsIgnoreCase("Истина") || flag.equalsIgnoreCase("True")) //$NON-NLS-1$ //$NON-NLS-2$
                names = List.of("Array", "Map"); //$NON-NLS-1$ //$NON-NLS-2$
            else
                names = List.of("Array", "Structure", "Map"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List<TypeItem> types = new ArrayList<>();
            for (String typeName : names)
            {
                Type type = platformType(typeName);
                if (type != null)
                    types.add(type);
            }
            return types.isEmpty() ? null : types;
        }

        /**
         * {@code ВычислитьПолучитьИзВременногоХранилища}: тип значения, которое записала в адрес (строку)
         * функция-писатель ({@link #WRITERS}). В EDT тип не хранится в строке, поэтому аргумент прослеживается
         * по присваиваниям до вызова писателя.
         */
        List<TypeItem> storedValue()
        {
            List<TypeItem> types = writtenTypes(origins(param(0)));
            return types.isEmpty() ? null : types;
        }

        /**
         * {@code ВычислитьПрочитатьЗначениеJSON}: тип значения, записанного в строку писателем, иначе — тип данных
         * из текста JSON строкового литерала.
         */
        List<TypeItem> readJsonValue()
        {
            List<Expression> origins = origins(param(0));
            List<TypeItem> types = writtenTypes(origins);
            if (!types.isEmpty())
                return types;
            for (Expression origin : origins)
            {
                String json = stringValue(origin);
                Object data = json == null ? null : Json.parse(json);
                if (data == null)
                    continue;
                TypeItem type = jsonType(data, Json.allKeysAreNames(data));
                if (type != null)
                    return List.of(type);
            }
            return null;
        }

        List<TypeItem> writtenTypes(List<Expression> origins)
        {
            List<TypeItem> types = new ArrayList<>();
            for (Expression origin : origins)
                if (origin instanceof Invocation call && isWriter(call) && !call.getParams().isEmpty())
                    types.addAll(typesOf(call.getParams().get(0)));
            return types;
        }

        static boolean isWriter(Invocation call)
        {
            return call.getMethodAccess() instanceof StaticFeatureAccess method
                && WRITERS.contains(lower(method.getName()));
        }

        /**
         * Выражения, от которых получено значение {@code expression}: само выражение или правые части присваиваний
         * той же переменной (того же пути {@code А.Б}) до места чтения, рекурсивно. Присваиваний в методе нет —
         * ищутся во всём модуле (реквизит формы, переменная модуля).
         */
        List<Expression> origins(Expression expression)
        {
            List<Expression> result = new ArrayList<>();
            collectOrigins(expression, offsetOf(invocation), Collections.newSetFromMap(new IdentityHashMap<>()), result);
            return result;
        }

        void collectOrigins(Expression expression, int before, Set<EObject> visited, List<Expression> result)
        {
            if (expression == null || !visited.add(expression))
                return;
            if (!(expression instanceof StaticFeatureAccess || expression instanceof DynamicFeatureAccess)
                || expression instanceof Invocation)
            {
                result.add(expression);
                return;
            }
            String target = withoutSpaces(text(expression));
            Method method = EcoreUtil2.getContainerOfType(expression, Method.class);
            List<SimpleStatement> assignments = assignments(method, target, before);
            if (assignments.isEmpty())
                assignments = assignments(EcoreUtil2.getContainerOfType(expression, Module.class), target,
                    Integer.MAX_VALUE);
            for (SimpleStatement statement : assignments)
                collectOrigins(statement.getRight(), offsetOf(statement), visited, result);
        }

        static List<SimpleStatement> assignments(EObject scope, String target, int before)
        {
            List<SimpleStatement> result = new ArrayList<>();
            if (scope == null || target.isEmpty())
                return result;
            scope.eAllContents().forEachRemaining(object -> {
                if (object instanceof SimpleStatement statement && statement.getRight() != null
                    && statement.getLeft() != null && offsetOf(statement) < before
                    && target.equalsIgnoreCase(withoutSpaces(text(statement.getLeft()))))
                    result.add(statement);
            });
            return result;
        }

        /** Тип данных JSON ({@code ОбъектИзТекстаЖСОНЛкс}): объекты — структуры, а если ключ не имя — соответствия. */
        TypeItem jsonType(Object data, boolean structures)
        {
            if (data == Json.NULL)
                return platformType("Undefined"); //$NON-NLS-1$
            if (data instanceof String)
                return platformType("String"); //$NON-NLS-1$
            if (data instanceof Number)
                return platformType("Number"); //$NON-NLS-1$
            if (data instanceof Boolean)
                return platformType("Boolean"); //$NON-NLS-1$
            if (data instanceof List<?> list)
            {
                List<TypeItem> elements = new ArrayList<>();
                for (Object element : list)
                    addDistinct(elements, jsonType(element, structures));
                return arrayType(elements);
            }
            if (!(data instanceof Map<?, ?> object))
                return null;
            if (!structures)
            {
                List<TypeItem> values = new ArrayList<>();
                for (Object value : object.values())
                    addDistinct(values, jsonType(value, false));
                Type string = platformType("String"); //$NON-NLS-1$
                return mapType(string == null ? List.of() : List.of(string), values);
            }
            Map<String, Collection<TypeItem>> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : object.entrySet())
            {
                TypeItem type = jsonType(field.getValue(), true);
                fields.put(String.valueOf(field.getKey()), type == null ? List.of() : List.of(type));
            }
            return newStructure(fields);
        }

        /** Простые типы — по одному на имя; составные (структуры с полями и т.п.) — все. */
        static void addDistinct(List<TypeItem> types, TypeItem type)
        {
            if (type == null)
                return;
            if (!(type instanceof com._1c.g5.v8.dt.bsl.model.ExtendedType)
                && !(type instanceof com._1c.g5.v8.dt.bsl.model.ExtendedCollectionType))
                for (TypeItem existing : types)
                    if (typeName(existing).equals(typeName(type)))
                        return;
            types.add(type);
        }

        // ---- типы выражений ----

        Expression param(int index)
        {
            return index < params.size() ? params.get(index) : null;
        }

        List<TypeItem> typesOf(Expression expression)
        {
            if (expression == null)
                return List.of();
            List<TypeItem> types = services.types().computeTypes(expression, environments);
            return types == null ? List.of() : types;
        }

        List<TypeItem> receiverTypes()
        {
            return access instanceof DynamicFeatureAccess dynamic ? typesOf(dynamic.getSource()) : List.of();
        }

        /** Штатный тип вызова — как если бы правила не было. */
        List<TypeItem> stockTypes()
        {
            Set<Invocation> stock = STOCK.get();
            stock.add(invocation);
            try
            {
                List<TypeItem> types = services.types().computeTypes(invocation, environments);
                return types == null ? List.of() : types;
            }
            finally
            {
                stock.remove(invocation);
            }
        }

        List<TypeItem> receiverRows()
        {
            return new ArrayList<>(TypeSystemUtil.getCollectionElementTypes(receiverTypes(), invocation));
        }

        /** Свойства первого типа строки, у которого они есть ({@code ЛучшийТипИзТаблицыТипов}). */
        List<Property> rowProperties(List<TypeItem> rows)
        {
            for (TypeItem row : rows)
            {
                List<Property> properties = properties(List.of(row));
                if (!properties.isEmpty())
                    return properties;
            }
            return List.of();
        }

        Type rowStructure(List<TypeItem> rows)
        {
            List<Property> properties = rowProperties(rows);
            if (properties.isEmpty())
                return null;
            Type structure = baseStructure();
            if (structure == null)
                return null;
            for (Property property : properties)
                structure.getContextDef().getProperties().add(copyProperty(property, true));
            return structure;
        }

        List<Property> properties(Collection<TypeItem> types)
        {
            List<Property> result = new ArrayList<>();
            for (Pair<Collection<Property>, TypeItem> pair : services.features().getAllProperties(types, resource))
                result.addAll(pair.getFirst());
            return result;
        }

        Property propertyByPath(Collection<TypeItem> types, String path)
        {
            Property property = null;
            Collection<TypeItem> current = types;
            for (String segment : path.split("\\.")) //$NON-NLS-1$
            {
                property = byName(properties(current), segment.trim());
                if (property == null)
                    return null;
                current = property.getTypes();
            }
            return property;
        }

        static Property byName(List<Property> properties, String name)
        {
            for (Property property : properties)
                if (name.equalsIgnoreCase(property.getName()) || name.equalsIgnoreCase(property.getNameRu()))
                    return property;
            return null;
        }

        // ---- создание типов ----

        Type platformType(String typeName)
        {
            Version version = services.versions().getRuntimeVersionOrDefault(invocation, Version.LATEST);
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
            EObject resolved = EcoreUtil.resolve(provider.getProxy(typeName), invocation);
            return resolved instanceof Type type && !type.eIsProxy() ? type : null;
        }

        /** Тип по имени из {@code Тип("…")}: платформенный или объекта метаданных, по-русски или по-английски. */
        Type typeByName(String typeName)
        {
            IScope scope = services.scopes().getScope(invocation, McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
            IEObjectDescription element = scope.getSingleElement(QualifiedName.create(typeName.trim().split("\\."))); //$NON-NLS-1$
            EObject resolved = element == null ? null : EcoreUtil.resolve(element.getEObjectOrProxy(), invocation);
            return resolved instanceof Type type && !type.eIsProxy() ? type : null;
        }

        Type managerType(String kindEn, String objectName)
        {
            if (kindEn == null || objectName == null || objectName.isBlank())
                return null;
            IScope scope = services.scopes().getScope(invocation, McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
            IEObjectDescription element = scope.getSingleElement(QualifiedName.create(kindEn + "Manager", objectName)); //$NON-NLS-1$
            EObject resolved = element == null ? null : EcoreUtil.resolve(element.getEObjectOrProxy(), invocation);
            return resolved instanceof Type type && !type.eIsProxy() ? type : null;
        }

        MdObject managerProducer(String kind, String objectName)
        {
            Type manager = managerType(MdTypeMapping.anyToEnSing(kind), objectName);
            return manager == null ? null : MdTypeUtil.getTypeProducer(manager);
        }

        Configuration configuration()
        {
            var projects = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
            IV8Project project = projects == null ? null : projects.getProject(resource.getURI());
            return project instanceof IConfigurationAware aware ? aware.getConfiguration() : null;
        }

        CommonModule commonModuleMd(String moduleName)
        {
            Configuration configuration = configuration();
            if (configuration != null)
                for (CommonModule module : configuration.getCommonModules())
                    if (moduleName.equalsIgnoreCase(module.getName()))
                        return module;
            return null;
        }

        Type baseStructure()
        {
            Type structure = platformType("Structure"); //$NON-NLS-1$
            return structure == null ? null : BslDocCommentTypeMerge.copyWithRefContext(structure, environments, false);
        }

        Type newStructure(Map<String, Collection<TypeItem>> fields)
        {
            Type structure = baseStructure();
            if (structure == null)
                return null;
            for (Map.Entry<String, Collection<TypeItem>> field : fields.entrySet())
                structure.getContextDef().getProperties().add(newProperty(field.getKey(), field.getValue(), true));
            return structure;
        }

        /** Структура с теми же ключами и типами значений, что у структуры-параметра. */
        Type copyStructure(Type source)
        {
            Type structure = baseStructure();
            if (structure == null)
                return null;
            for (Property property : source.getContextDef().getProperties())
                structure.getContextDef().getProperties().add(copyProperty(property, true));
            return structure;
        }

        static DerivedProperty newProperty(String propertyName, Collection<TypeItem> types, boolean writable)
        {
            DerivedProperty property = McoreFactory.eINSTANCE.createDerivedProperty();
            property.setName(propertyName);
            property.setNameRu(propertyName);
            property.setReadable(true);
            property.setWritable(writable);
            property.setEnvironments(Environments.ALL);
            TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
            container.getTypes().addAll(types);
            property.setTypeContainer(container);
            return property;
        }

        static DerivedProperty copyProperty(Property source, boolean writable)
        {
            DerivedProperty property = newProperty(source.getName(), source.getTypes(), writable);
            property.setNameRu(source.getNameRu() == null || source.getNameRu().isBlank() ? source.getName()
                : source.getNameRu());
            if (source instanceof DerivedProperty derived)
                property.setSource(derived.getSource());
            return property;
        }

        Type arrayType(List<TypeItem> elements)
        {
            Version version = services.versions().getRuntimeVersionOrDefault(invocation, Version.LATEST);
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
            return TypeSystemUtil.createExtendedArrayType(elements, provider, invocation);
        }

        /** Соответствие с типами ключа и значения (как ssl-support {@code TypesComputerHelper.createCustomMapType}). */
        Type mapType(List<TypeItem> keys, List<TypeItem> values)
        {
            Type map = platformType("Map"); //$NON-NLS-1$
            Type keyAndValue = platformType("KeyAndValue"); //$NON-NLS-1$
            if (map == null || keyAndValue == null)
                return map;
            Type result = EcoreUtil2.cloneWithProxies(map);
            Type element = EcoreUtil2.cloneWithProxies(keyAndValue);
            TypeContainerDef elements = McoreFactory.eINSTANCE.createTypeContainerDef();
            elements.getTypes().add(element);
            result.setCollectionElementTypes(elements);
            for (Property property : element.getContextDef().getProperties())
            {
                List<TypeItem> types = "Key".equals(property.getName()) ? keys //$NON-NLS-1$
                    : "Value".equals(property.getName()) ? values : null; //$NON-NLS-1$
                if (types == null)
                    continue;
                TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
                container.getTypes().addAll(types);
                property.setTypeContainer(container);
            }
            // Только собственные методы копии: ссылочные принадлежат платформенному типу
            for (com._1c.g5.v8.dt.mcore.Method method : result.getContextDef().getMethods())
                if ("Get".equals(method.getName())) //$NON-NLS-1$
                {
                    method.getRetValType().clear();
                    method.getRetValType().addAll(values);
                }
            return result;
        }

        /** Таблица значений с колонками {@code columns} — тот же тип, что EDT создаёт для «Новый ТаблицаЗначений». */
        Type valueTableType(List<Property> columns)
        {
            Type platform = platformType("ValueTable"); //$NON-NLS-1$
            if (platform == null)
                return null;
            Type table = services.valueTables().computeDerivedValueTableType(invocation,
                EcoreUtil2.cloneWithProxies(platform));
            if (table == null || table.getCollectionElementTypes() == null
                || table.getCollectionElementTypes().allTypes().isEmpty()
                || !(table.getCollectionElementTypes().allTypes().get(0) instanceof Type row))
                return null;
            Type columnCollection = (Type)ValueTableDynamicContextDefProvider.getColumnCollectionType(table)
                .allTypes().get(0);
            Type columnType = platformType("ValueTableColumn"); //$NON-NLS-1$
            for (Property column : columns)
            {
                row.getContextDef().getProperties().add(copyProperty(column, true));
                if (columnType != null)
                {
                    DerivedProperty columnProperty = copyProperty(column, false);
                    TypeContainerRef types = McoreFactory.eINSTANCE.createTypeContainerRef();
                    types.getTypes().add(columnType);
                    columnProperty.setTypeContainer(types);
                    columnCollection.getContextDef().getProperties().add(columnProperty);
                }
            }
            return table;
        }

        // ---- значения выражений ----

        /** {@code ВычислитьЗначениеВыражения} для строки: литерал или сложение литералов. */
        static String stringValue(Expression expression)
        {
            if (expression instanceof StringLiteral literal)
                return new DefaultStringLiteralTextProvider(literal).getText();
            if (expression instanceof BinaryExpression binary && binary.getOperation() == BinaryOperation.PLUS)
            {
                String left = stringValue(binary.getLeft());
                String right = left == null ? null : stringValue(binary.getRight());
                return right == null ? null : left + right;
            }
            return null;
        }

        /** Имя типа из выражения {@code Тип("…")}; другое выражение — {@code null}. */
        static String typeLiteral(Expression expression)
        {
            if (!(expression instanceof Invocation call) || call.getParams().size() != 1
                || !(call.getMethodAccess() instanceof StaticFeatureAccess method)
                || !("Тип".equalsIgnoreCase(method.getName()) || "Type".equalsIgnoreCase(method.getName()))) //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            String value = stringValue(call.getParams().get(0));
            return value == null || value.isBlank() ? null : value;
        }

        /** Аргумент выражения {@code ТипЗнч(…)}; другое выражение — {@code null}. */
        static Expression typeOfArgument(Expression expression)
        {
            if (!(expression instanceof Invocation call) || call.getParams().size() != 1
                || !(call.getMethodAccess() instanceof StaticFeatureAccess method)
                || !("ТипЗнч".equalsIgnoreCase(method.getName()) || "TypeOf".equalsIgnoreCase(method.getName()))) //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            return call.getParams().get(0);
        }

        static String text(EObject object)
        {
            if (object instanceof BooleanLiteral bool)
                return bool.isIsTrue() ? "Истина" : "Ложь"; //$NON-NLS-1$ //$NON-NLS-2$
            ICompositeNode node = object == null ? null : NodeModelUtils.findActualNodeFor(object);
            String text = node == null ? null : NodeModelUtils.getTokenText(node);
            return text == null ? "" : text.trim(); //$NON-NLS-1$
        }

        static int offsetOf(EObject object)
        {
            ICompositeNode node = object == null ? null : NodeModelUtils.findActualNodeFor(object);
            return node == null ? -1 : node.getOffset();
        }

        static String withoutSpaces(String text)
        {
            return text.replaceAll("\\s+", ""); //$NON-NLS-1$ //$NON-NLS-2$
        }

        static int parseIndex(String text)
        {
            try
            {
                return Integer.parseInt(text.trim());
            }
            catch (NumberFormatException notInteger)
            {
                return -1;
            }
        }

        static TypeItem resolveItem(TypeItem item, EObject context)
        {
            return item != null && item.eIsProxy() ? (TypeItem)EcoreUtil.resolve(item, context) : item;
        }

        TypeItem resolve(TypeItem item)
        {
            return resolveItem(item, invocation);
        }

        static String typeName(TypeItem item)
        {
            String typeName = item == null ? null : McoreUtil.getTypeName(item);
            return typeName == null ? "" : typeName; //$NON-NLS-1$
        }

        static String typeNameRu(TypeItem item)
        {
            String typeName = item == null ? null : McoreUtil.getTypeNameRu(item);
            return typeName == null ? "" : typeName; //$NON-NLS-1$
        }

        /** Тип значения макета — как у штатного {@code ПолучитьМакет} ({@code GetTemplateInvocationTypesComputer}). */
        static String templateTypeName(TemplateType type)
        {
            if (type == null)
                return null;
            return switch (type)
            {
                case SPREADSHEET_DOCUMENT -> "SpreadsheetDocument"; //$NON-NLS-1$
                case TEXT_DOCUMENT -> "TextDocument"; //$NON-NLS-1$
                case BINARY_DATA -> "BinaryData"; //$NON-NLS-1$
                case ACTIVE_DOCUMENT -> "ActiveDocumentShell"; //$NON-NLS-1$
                case HTML_DOCUMENT -> "HTMLDocumentShell"; //$NON-NLS-1$
                case GEOGRAPHICAL_SCHEMA -> "GeographicalSchema"; //$NON-NLS-1$
                case GRAPHICAL_SCHEMA -> "GraphicalSchema"; //$NON-NLS-1$
                case DATA_COMPOSITION_SCHEMA -> "DataCompositionSchema"; //$NON-NLS-1$
                case DATA_COMPOSITION_APPEARANCE_TEMPLATE -> "DataCompositionAppearanceTemplate"; //$NON-NLS-1$
                default -> null;
            };
        }

        static String lower(String value)
        {
            return value == null ? "" : value.toLowerCase(Locale.ROOT); //$NON-NLS-1$
        }
    }

    /**
     * Разбор текста JSON в значения Java: объект — {@link LinkedHashMap}, массив — {@link List}, строка,
     * число, булево, {@link #NULL}. Текст не JSON — {@code null}.
     */
    private static final class Json
    {
        static final Object NULL = new Object();

        private final String text;
        private int position;

        private Json(String text)
        {
            this.text = text;
        }

        static Object parse(String text)
        {
            try
            {
                Json json = new Json(text);
                json.skipSpaces();
                if (json.position >= text.length() || (text.charAt(json.position) != '{' && text.charAt(json.position) != '['))
                    return null;
                Object value = json.value();
                json.skipSpaces();
                return json.position == text.length() ? value : null;
            }
            catch (IllegalArgumentException | IndexOutOfBoundsException notJson)
            {
                return null;
            }
        }

        /** Все ключи объектов — имена: {@code ПрочитатьJSON} без чтения в соответствие не падает. */
        static boolean allKeysAreNames(Object data)
        {
            if (data instanceof List<?> list)
                return list.stream().allMatch(Json::allKeysAreNames);
            if (data instanceof Map<?, ?> map)
                return map.entrySet().stream().allMatch(entry -> IDENTIFIER.matcher(String.valueOf(entry.getKey())).matches()
                    && allKeysAreNames(entry.getValue()));
            return true;
        }

        private Object value()
        {
            skipSpaces();
            char c = text.charAt(position);
            if (c == '{')
                return object();
            if (c == '[')
                return array();
            if (c == '"')
                return string();
            if (text.startsWith("true", position)) //$NON-NLS-1$
            {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) //$NON-NLS-1$
            {
                position += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", position)) //$NON-NLS-1$
            {
                position += 4;
                return NULL;
            }
            int start = position;
            while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) //$NON-NLS-1$
                position++;
            if (start == position)
                throw new IllegalArgumentException();
            return Double.valueOf(text.substring(start, position));
        }

        private Map<String, Object> object()
        {
            Map<String, Object> result = new LinkedHashMap<>();
            position++;
            skipSpaces();
            if (text.charAt(position) == '}')
            {
                position++;
                return result;
            }
            while (true)
            {
                skipSpaces();
                String key = string();
                skipSpaces();
                expect(':');
                result.put(key, value());
                skipSpaces();
                if (text.charAt(position) == ',')
                {
                    position++;
                    continue;
                }
                expect('}');
                return result;
            }
        }

        private List<Object> array()
        {
            List<Object> result = new ArrayList<>();
            position++;
            skipSpaces();
            if (text.charAt(position) == ']')
            {
                position++;
                return result;
            }
            while (true)
            {
                result.add(value());
                skipSpaces();
                if (text.charAt(position) == ',')
                {
                    position++;
                    continue;
                }
                expect(']');
                return result;
            }
        }

        private String string()
        {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (true)
            {
                char c = text.charAt(position++);
                if (c == '"')
                    return result.toString();
                if (c == '\\')
                {
                    char escaped = text.charAt(position++);
                    if (escaped == 'u')
                    {
                        result.append((char)Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    else
                        result.append(switch (escaped)
                        {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            case 'b' -> '\b';
                            case 'f' -> '\f';
                            default -> escaped;
                        });
                }
                else
                    result.append(c);
            }
        }

        private void expect(char c)
        {
            if (text.charAt(position) != c)
                throw new IllegalArgumentException();
            position++;
        }

        private void skipSpaces()
        {
            while (position < text.length() && Character.isWhitespace(text.charAt(position)))
                position++;
        }
    }
}
