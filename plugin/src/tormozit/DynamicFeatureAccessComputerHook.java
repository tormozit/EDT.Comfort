package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.ui.IStartup;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.validation.RangeBasedDiagnostic;
import org.eclipse.xtext.validation.ValidationMessageAcceptor;
import com.e1c.g5.v8.dt.check.BslDirectLocationIssue;
import com.e1c.g5.v8.dt.check.DirectLocation;
import com.e1c.g5.v8.dt.check.ICheckResultAcceptor;
import com._1c.g5.v8.dt.bsl.model.IfPreprocessor;
import com._1c.g5.v8.dt.bsl.model.PreprocessorConditional;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.PropertyType;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.typesystem.NamingCollectionServiceProvider;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;

/**
 * Доработки {@code DynamicFeatureAccessComputer} — вычислителя членов выражения «через точку».
 *
 * <p>Методы значения определяемого типа и характеристики (1C-Company/1c-edt-issues#2402). Методы
 * {@link TypeSet} ищет {@code DynamicFeatureAccessComputer$TypeSetMethodProvider.get(имя)}: сначала в
 * {@code getBaseTypes()} — у набора определяемого типа он пуст, — потом в {@code types(context)}, но
 * там берёт только {@code BslContextDefMethod} (методы модулей) и отбрасывает платформенные
 * ({@code Пустая()}, {@code УникальныйИдентификатор()}). Свойства EDT ищет через {@code types(context)}
 * без такого отбора, поэтому с ними всё работает. Итог для методов — ложная проблема «Свойство (метод)
 * объекта не обнаружено» и «Ничего не найдено» в подсказке при наведении.
 *
 * <p>Если провайдер ничего не нашёл, а набор принадлежит определяемому типу или плану видов
 * характеристик, методы с этим именем
 * добираются из всех типов {@code types(context)}. Через этот провайдер идут и проверка модуля, и
 * подсказка, и переход к определению — правка одна на всех.
 *
 * <p>Обобщённый {@code Отбор}: BSL 28.0.1.v202605050943 создаёт свойство именованной коллекции
 * с типом {@code FilterItem}, но без {@code DerivedProperty.source}. Валидатор
 * {@code isUnknownDFAPart} считает его неизвестным и через {@code unknownLeftPart} сообщает
 * ложную ошибку в цепочке. Источник связываем с платформенным шаблоном {@code FilterElementName}.
 * Копии Filter конкретных регистров находятся в модели метаданных; их проверка имён остаётся
 * штатной. Это вплетение ставит
 * ранний бандл {@code tormozit.comfort.early}, до загрузки вычислителя BSL.
 *
 * <p>Члены после закрытых коллекций метаданных проверяет динамическая проверка
 * («Свойство (метод) объекта не обнаружено»), но только там, где не работает статическая: обе
 * сообщают через один {@code unknownLeftPart}, и на слове после {@code Справочники.} было по две
 * пометки — {@code …-static-feature-access…} и {@code …-dynamic-feature-access…}. Разделение
 * делает {@link #dynamicAllowed}.
 *
 * <p>Строковая специализация {@code КлючИЗначение}: общий список свойств модели исключает
 * унаследованный произвольный ключ; собственный строковый ключ получает код документации
 * из базового свойства. Вплетение {@code ContextDefImpl.allProperties} ставит ранний бандл.
 *
 * <p>Окружение значений системных перечислений: в данных платформы у части значений оно уже окружения
 * самого перечисления (например, у {@code РежимРедактированияКолонки.Непосредственно} нет сервера), хотя в
 * справке доступность одна на всё перечисление. Проверка члена после точки сообщала ложное «Свойство (метод)
 * объекта не обнаружено [Сервер]». Окружение значения расширяется до окружения перечисления в
 * {@code EnvironmentalExtension.environments}, поэтому подсказки и переход к определению согласованы с
 * проверкой. Вплетение ставит ранний бандл {@code tormozit.comfort.early}.
 *
 * <p>Инструментирование через {@code WeavingHook}, как {@link OpenHelperAttributePropertiesHook}:
 * вызов через {@code System.getProperties}, без зависимости бандла BSL от Комфорта.
 */
public final class DynamicFeatureAccessComputerHook implements IStartup
{
    static final String PROP_TYPE_SET_METHODS = "tormozit.dynamicFeatureAccess.typeSetMethods"; //$NON-NLS-1$
    private static final String TARGET =
        "com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer$TypeSetMethodProvider"; //$NON-NLS-1$
    private static final String TARGET_INTERNAL = TARGET.replace('.', '/');
    private static final String GET_DESC = "(Ljava/lang/String;)Ljava/util/Collection;"; //$NON-NLS-1$
    private static final String PROP_PROPERTY_TRACE = "tormozit.dynamicFeatureAccess.propertyTrace"; //$NON-NLS-1$

    private static final AtomicBoolean weavingHookInstalled = new AtomicBoolean();
    private static volatile boolean woven;

    /** Регистрация {@link WeavingHook}; как можно раньше из {@code Activator.start}. */
    public static void installWeavingHook()
    {
        if (!weavingHookInstalled.compareAndSet(false, true))
            return;
        System.getProperties().put(PROP_TYPE_SET_METHODS,
            (Function<Object[], Object>) DynamicFeatureAccessComputerHook::typeSetMethods);
        System.getProperties().put(PROP_PROPERTY_TRACE,
            (Function<Object[], Object>) DynamicFeatureAccessComputerHook::traceProperty);
        System.getProperties().put("tormozit.platform.structureKey", //$NON-NLS-1$
            (Function<Object[], Object>) args -> AbstractTypeMembers.properties(StructureKeyDefinition.complete(args),
                args[1]));
        System.getProperties().put("tormozit.platform.contextMethods", //$NON-NLS-1$
            (Function<Object[], Object>) args -> AbstractTypeMembers.methods(args[0], args[1]));
        System.getProperties().put("tormozit.platform.environments", //$NON-NLS-1$
            (Function<Object[], Object>) SystemEnumEnvironments::extend);
        System.getProperties().put("tormozit.dynamicFeatureAccess.allMembers", //$NON-NLS-1$
            (Function<Object[], Object>) args -> AbstractTypeMembers.byOwner(
                SpecialMemberTemplates.withoutGenericFormItems(args[0])));
        System.getProperties().put("tormozit.dynamicFeatureAccess.formItemTypes", //$NON-NLS-1$
            (Function<Object[], Object>) FormItemTypes::expand);
        System.getProperties().put("tormozit.dynamicFeatureAccess.methodTypes", //$NON-NLS-1$
            (Function<Object[], Object>) args -> AbstractTypeMembers.withChildren(FormItemTypes.expand(args), args[1], args[2]));
        System.getProperties().put("tormozit.bslValidator.checkLastMember",
            (Function<Object[], Object>) DynamicFeatureAccessComputerHook::checkLastMember);
        System.getProperties().put("tormozit.bslValidator.openType", //$NON-NLS-1$
            (Function<Object[], Object>) args -> AbstractTypeMembers.validatorOpenType(args[0], args[1], args[2], args[3], args[4]));
        System.getProperties().put("tormozit.bslValidator.dynamicAllowed", //$NON-NLS-1$
            (Function<Object[], Object>) DynamicFeatureAccessComputerHook::dynamicAllowed);
        System.getProperties().put("tormozit.bslValidator.preprocessorPartError", //$NON-NLS-1$
            (Function<Object, Object>) DynamicFeatureAccessComputerHook::preprocessorPartError);
        System.getProperties().put("tormozit.legacyCheck.rangeIssue", //$NON-NLS-1$
            (java.util.function.BiPredicate<Object, Object>) DynamicFeatureAccessComputerHook::preprocessorPartIssue);
        System.getProperties().put("tormozit.dynamicFeatureAccess.namingCollections", //$NON-NLS-1$
            (Function<Object[], Object>) SpecialMemberTemplates::namingCollections);
        Bundle bundle = FrameworkUtil.getBundle(DynamicFeatureAccessComputerHook.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(WeavingHook.class, new ProviderWeavingHook(), null);
    }


    /**
     * Ошибка проверки {@code bsl-legacy-check-if-preprocessor-part-environments} — на открывающей инструкции ветки
     * ({@code #Если … Тогда}, {@code #ИначеЕсли … Тогда}, {@code #Иначе}), а не на её теле. Аргумент —
     * {@code List.of(List.of(сообщение, объект, свойство, код, данные), приёмник)} из вплетения раннего бандла.
     */
    private static Object preprocessorPartError(Object payload)
    {
        java.util.List<?> call = (java.util.List<?>)payload;
        java.util.List<?> args = (java.util.List<?>)call.get(0);
        ValidationMessageAcceptor acceptor = (ValidationMessageAcceptor)call.get(1);
        String message = (String)args.get(0);
        EObject object = (EObject)args.get(1);
        EStructuralFeature feature = (EStructuralFeature)args.get(2);
        String code = (String)args.get(3);
        String[] data = (String[])args.get(4);
        int[] region = object instanceof PreprocessorConditional conditional ? openingDirectiveRegion(conditional) : null;
        if (region != null)
            acceptor.acceptError(message, object, region[0], region[1], code, data);
        else
            acceptor.acceptError(message, object, feature, ValidationMessageAcceptor.INSIGNIFICANT_INDEX, code, data);
        return null;
    }

    /**
     * Диагностика проверки ветки препроцессора с текстовым диапазоном → замечание {@link BslDirectLocationIssue}:
     * штатный {@code LegacyCheckAdapter} диапазон отбрасывает и ставит замечание на весь объект.
     *
     * @return {@code true} — замечание добавлено, штатная обработка не нужна
     */
    private static boolean preprocessorPartIssue(Object acceptor, Object diagnostic)
    {
        if (!(diagnostic instanceof RangeBasedDiagnostic range) || !(acceptor instanceof ICheckResultAcceptor results))
            return false;
        String code = range.getIssueCode();
        if (!"code-never-compiled".equals(code) && !"unreachable-code".equals(code)) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        EObject source = range.getSourceEObject();
        if (source == null)
            return false;
        ICompositeNode root = NodeModelUtils.getNode(EcoreUtil.getRootContainer(source));
        Integer line = root != null
            ? Integer.valueOf(NodeModelUtils.getLineAndColumn(root, range.getOffset()).getLine()) : null;
        DirectLocation location = new DirectLocation(Integer.valueOf(range.getOffset()),
            Integer.valueOf(range.getLength()), line, source);
        results.addIssue(source, new BslDirectLocationIssue(range.getMessage(), location, range.getIssueData()));
        return true;
    }

    /**
     * Диапазон открывающей инструкции: от её первого токена ({@code #Если} — у директивы, {@code #ИначеЕсли} и
     * {@code #Иначе} — у самой ветки) до последнего значимого токена перед телом ветки.
     *
     * @return {смещение, длина} либо {@code null}
     */
    private static int[] openingDirectiveRegion(PreprocessorConditional conditional)
    {
        ICompositeNode node = NodeModelUtils.getNode(conditional);
        if (node == null)
            return null;
        ICompositeNode startNode = node;
        if (conditional.eContainer() instanceof IfPreprocessor directive && directive.getIfPart() == conditional)
            startNode = NodeModelUtils.getNode(directive);
        if (startNode == null)
            return null;
        ICompositeNode itemNode = conditional.getItem() != null ? NodeModelUtils.getNode(conditional.getItem()) : null;
        int itemStart = itemNode != null ? itemNode.getTotalOffset() : Integer.MAX_VALUE;
        int start = -1;
        int end = -1;
        for (ILeafNode leaf : startNode.getLeafNodes())
        {
            if (leaf.isHidden() || leaf.getTotalLength() == 0)
                continue;
            if (leaf.getTotalOffset() >= itemStart)
                break;
            if (start < 0)
                start = leaf.getTotalOffset();
            end = leaf.getTotalEndOffset();
        }
        return start >= 0 && end > start ? new int[] { start, end - start } : null;
    }

    /** Типы-менеджеры метаданных: именованные члены такого типа EDT получает из произведённых типов. */
    private static final java.util.Set<String> METADATA_MANAGERS = java.util.Set.of(
        "CatalogsManager", "DocumentsManager", "ChartsOfCharacteristicTypesManager",
        "ChartsOfCalculationTypesManager", "ExchangePlansManager", "EnumsManager",
        "InformationRegistersManager", "AccumulationRegistersManager", "ConstantsManager", "SequencesManager",
        "DocumentJournalsManager", "ReportsManager", "DataProcessorsManager", "TasksManager",
        "FilterCriteriaManager", "SettingsStoragesManager", "BusinessProcessesManager", "WSReferencesManager",
        "CalculationRegistersManager", "ChartsOfAccountsManager", "ExternalDataSourcesManager",
        "IntegrationServicesManager", "AccountingRegistersManager");
    /** Те же имена в нижнем регистре — сверка с именами типов модели без учёта регистра. */
    private static final java.util.Set<String> METADATA_MANAGERS_LOWER = METADATA_MANAGERS.stream()
        .map(name -> name.toLowerCase(java.util.Locale.ROOT))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * Разрешение динамической проверке рассмотреть цепочку: {@code {обращение, окружения, значение}} из
     * {@code BslJavaValidator.checkDynamicFeatureAccessForUnknownLeftPart}. Наружу — только {@link Boolean}:
     * в байткоде после вызова стоит {@code CHECKCAST Boolean}. {@code false} — цепочку уже проверяет
     * статическая проверка ({@link #staticallyChecked}), иначе обе сообщали бы об одном слове через
     * {@code unknownLeftPart}.
     */
    private static Object dynamicAllowed(Object[] args)
    {
        Object value = args[2];
        boolean enabled = value instanceof Boolean flag ? flag : Integer.valueOf(1).equals(value);
        try
        {
            if (enabled && args[0] instanceof DynamicFeatureAccess feature
                && args[1] instanceof Environments environments)
            {
                TypesComputer types = typesComputer(feature);
                if (types != null && staticallyChecked(feature, environments, types))
                    return Boolean.FALSE;
            }
        }
        catch (RuntimeException exception)
        {
            // Остаётся штатное поведение проверки.
        }
        return Boolean.valueOf(enabled);
    }

    /**
     * Итог {@code getLastObject} для {@code BslJavaValidator.checkDynamicFeatureAccessForUnknownLeftPart}:
     * член не найден, а левая часть — закрытая коллекция метаданных, значит обращение ошибочно. Цепочку,
     * которую уже проверяет статическая проверка, не трогаем — см. {@link #staticallyChecked}.
     */
    private static Object checkLastMember(Object[] args)
    {
        var entries = (Collection<?>) args[0];
        var access = (DynamicFeatureAccess) args[1];
        var environments = (Environments) args[2];
        if (!(access.eResource() instanceof XtextResource resource) || resource.getResourceServiceProvider() == null)
            return args;

        var services = resource.getResourceServiceProvider();
        TypesComputer types = services.get(TypesComputer.class);
        DynamicFeatureAccessComputer members = services.get(DynamicFeatureAccessComputer.class);
        DynamicFeatureAccess missing = null;
        for (DynamicFeatureAccess part = access; part != null; part = precedingMember(part))
        {
            Collection<?> known = part == access ? entries : part.getFeatureEntries();
            if (part.getSource() == null || part.getName() == null || part.getName().isEmpty()
                || isMethodAccess(part) || hasResolvedMember(known))
                continue;
            var sourceTypes = types.computeTypes(part.getSource(), environments);
            if (sourceTypes.isEmpty() || !sourceTypes.stream().allMatch(DynamicFeatureAccessComputerHook::isClosedCollection))
                continue;
            if (part == access || !hasResolvedMember(members.getLastObject(part, environments, true)))
                missing = part;
        }
        return missing == null ? args : new Object[] {java.util.List.of(), missing};
    }

    private static boolean hasResolvedMember(Collection<?> entries)
    {
        return entries.stream().anyMatch(entry ->
            !(entry instanceof FeatureEntry feature && feature.getFeature() instanceof DerivedProperty property
                && property.getSource() == null && property.eContainer() == null));
    }

    private static DynamicFeatureAccess precedingMember(DynamicFeatureAccess access)
    {
        var source = access.getSource();
        if (source instanceof Invocation invocation)
            return invocation.getMethodAccess() instanceof DynamicFeatureAccess member ? member : null;
        if (source instanceof IndexAccess index)
            source = index.getSource();
        return source instanceof DynamicFeatureAccess member ? member : null;
    }

    private static boolean isMethodAccess(DynamicFeatureAccess access)
    {
        return access.eContainer() instanceof Invocation invocation && invocation.getMethodAccess() == access;
    }

    private static boolean isClosedCollection(TypeItem type)
    {
        return METADATA_MANAGERS.contains(java.util.Objects.toString(McoreUtil.getTypeName(type), ""))
            || type instanceof PropertyType property && isMetadataCollection(property.getName())
                && !property.getPropertyName().isEmpty();
    }

    private static boolean isMetadataCollection(String name)
    {
        return "MetadataObjectCollection".equals(name) || "MetadataObjectPropertyValueCollection".equals(name);
    }

    /**
     * Цепочку уже проверяет статическая проверка
     * {@code BslJavaValidator.checkStaticFeatureAccessForUnknownLeftPart}. Её корень — обращение с типом,
     * для которого EDT ищет члены пользователя ({@code canHaveUserMethodOrProperty} того же валидатора):
     * набор типов, имя с квалификатором ({@code CatalogObject.Имя}), тип-менеджер метаданных или
     * {@code ConfigurationMetadataObject} (обращения к {@code Метаданные}). Тогда статическая проверка
     * сама доходит до верха цепочки и сообщает об отсутствующем члене, а динамическая только
     * продублировала бы пометку на том же слове: обе сообщают через {@code unknownLeftPart}.
     * Поэтому динамическая оставляет себе цепочки, в которые статическая не входит, — с корнем-вызовом
     * типа без квалификатора ({@code РеквизитФормыВЗначение(…).Товары.Реквизит}).
     */
    private static boolean staticallyChecked(DynamicFeatureAccess access, Environments environments,
        TypesComputer computer)
    {
        StaticFeatureAccess name = leftmostName(access);
        if (name == null)
            return false;
        java.util.List<TypeItem> declared;
        try
        {
            declared = computer.computeTypes(name, environments);
        }
        catch (RuntimeException exception)
        {
            return false;
        }
        for (TypeItem item : declared)
        {
            if (item instanceof TypeSet)
                return true;
            String typeName = McoreUtil.getTypeName(item);
            if (typeName != null && (typeName.indexOf('.') >= 0 || "ConfigurationMetadataObject".equals(typeName)
                || METADATA_MANAGERS_LOWER.contains(typeName.toLowerCase(java.util.Locale.ROOT))))
                return true;
        }
        return false;
    }

    /** Самое левое обращение цепочки; {@code null}, если цепочка начинается не с имени. */
    private static StaticFeatureAccess leftmostName(DynamicFeatureAccess access)
    {
        EObject current = access.getSource();
        for (int depth = 0; current != null && depth < 32; depth++)
        {
            if (current instanceof StaticFeatureAccess name)
                return name;
            if (current instanceof DynamicFeatureAccess part)
                current = part.getSource();
            else if (current instanceof Invocation invocation)
                current = invocation.getMethodAccess();
            else if (current instanceof IndexAccess index)
                current = index.getSource();
            else
                return null;
        }
        return null;
    }

    private static TypesComputer typesComputer(EObject object)
    {
        return object != null && object.eResource() instanceof XtextResource resource
            && resource.getResourceServiceProvider() != null
            ? resource.getResourceServiceProvider().get(TypesComputer.class) : null;
    }

    @Override
    public void earlyStartup()
    {
        installWeavingHook();
        if (woven)
            return;
        // Класс мог загрузиться до регистрации WeavingHook — тогда остаётся только агент.
        BslDocCommentDescriptionFix.registerExtraTransformer(
            new ProviderTransformer(), TARGET);
    }

    /** Строковая специализация ключа; общий Key соответствия сюда не попадает. */
    static boolean isStringKey(Property property)
    {
        return property != null && "Key".equals(property.getName()) //$NON-NLS-1$
            && property.eContainer() instanceof com._1c.g5.v8.dt.mcore.ContextDef context
            && context.eContainer() instanceof Type owner && "KeyAndValue".equals(owner.getName()) //$NON-NLS-1$
            && property.getTypes().size() == 1
            && "String".equals(com._1c.g5.v8.dt.mcore.util.McoreUtil.getTypeName(property.getTypes().get(0))); //$NON-NLS-1$
    }

    /** Окружение значения системного перечисления не уже окружения самого перечисления. */
    private static final class SystemEnumEnvironments
    {
        /** {@code {окружение, член}} — окружение члена, штатное или расширенное до окружения перечисления. */
        static Object extend(Object[] args)
        {
            Object result = args[0];
            try
            {
                if (result instanceof Environments environments && args[1] instanceof Property property
                    && property.eContainer() instanceof com._1c.g5.v8.dt.mcore.ContextDef context
                    && context.eContainer() instanceof Type owner && owner.isSysEnum())
                {
                    Environments ownerEnvironments = owner.environments();
                    if (ownerEnvironments != null && !ownerEnvironments.isEmpty()
                        && !environments.containsAll(ownerEnvironments))
                        return ownerEnvironments;
                }
            }
            catch (RuntimeException exception)
            {
                // Остаётся штатное окружение.
            }
            return result;
        }
    }

    /** Исправление определения свойства, общее для расчёта типов и документации EDT. */
    private static final class StructureKeyDefinition
    {
        static Object complete(Object[] args)
        {
            if (!(args[1] instanceof com._1c.g5.v8.dt.mcore.ContextDef context)
                || !(context.eContainer() instanceof Type owner) || !"KeyAndValue".equals(owner.getName())) //$NON-NLS-1$
                return args[0];
            @SuppressWarnings("unchecked")
            var properties = (org.eclipse.emf.common.util.EList<Property>)args[0];
            Property key = null;
            for (Property property : context.getProperties())
                if (isStringKey(property))
                    key = property;
            if (key == null)
                return properties;
            var result = new org.eclipse.emf.common.util.BasicEList<Property>();
            int removed = 0;
            for (Property property : properties)
            {
                if (property != key && "Key".equals(property.getName())) //$NON-NLS-1$
                {
                    // Специализация из $String_Arbitrary$KeyAndValue.type не имеет своего code.
                    // Код берём из унаследованного определения, а тип оставляем строковым.
                    if (key.getCode() == -1 && property.getCode() != -1)
                        key.setCode(property.getCode());
                    removed++;
                }
                else
                    result.add(property);
            }
            return removed == 0 ? properties : org.eclipse.emf.common.util.ECollections.unmodifiableEList(result);
        }
    }

    /**
     * Члены абстрактного типа «ОбъектМетаданных» ({@code MetadataObject}) — свойства и методы всех его
     * типов-наследников. Подмена в результате {@code ContextDefImpl.allProperties/allMethods}: тот же набор
     * видят автодополнение, проверки, подсказки и значения литералов. Штатные {@code allProperties} и
     * {@code allMethods} берут у родителей только {@code getProperties()/getMethods()}, поэтому члены
     * наследников не попадают в соседние типы и вложенного вызова нет. Типы-наследники ищутся среди типов
     * платформы той же версии (имена начинаются с имени предка, принадлежность подтверждает цепочка
     * {@code getParentType()}).
     */
    private static final class AbstractTypeMembers
    {
        private static final String ABSTRACT_TYPE = "MetadataObject"; //$NON-NLS-1$
        private static final java.util.Map<Type, java.util.List<java.lang.ref.WeakReference<Type>>> CHILDREN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
        /** Типы-наследники найдены: набор членов {@code MetadataObject} полный, проверку членов можно включать. */
        private static volatile boolean verified;

        /**
         * Имя типа из списка открытых в {@code BslJavaValidator.unknownLeftPart}: то же имя — тип пропускается
         * (штатное поведение), другое — проверяется. {@code MetadataObject} проверяется, когда объединение членов
         * наследников подтверждено ({@link #verified}). Типы с динамическими ключами ({@code Структура}, строка
         * таблицы и т. п.) проверяются только для вызова метода: динамические у них ключи, а не методы. Метод
         * ли помечаемый элемент, определяется не по последнему обращению цепочки, а по самому помечаемому — части
         * с номером {@code index - 1}: в {@code Выборка.Поле.Выгрузить()} помечалось бы поле выборки.
         */
        static String validatorOpenType(Object typeName, Object access, Object parts, Object index, Object typeNames)
        {
            String name = String.valueOf(typeName);
            // Член найден, но EDT считает его неизвестным: isUnknownDFAPart принимает любое DerivedProperty без
            // source за заглушку имени, которую поиск создаёт для неизвестного элемента коллекции. Такое же свойство
            // без source объявляет и контекст формы (ПодчиненныеЭлементы группы). Объявленное в ContextDef — не
            // заглушка: возвращается имя из набора типов — тип пропускается.
            if ("Structure".equals(name) && typeNames instanceof java.util.Collection<?> declared //$NON-NLS-1$
                && flaggedIsDeclaredDerivedProperty(parts, index))
                for (Object candidate : declared)
                    if (candidate instanceof String text)
                        return text;
            // ВсеЭлементыФормы в списке открытых типов EDT нет: имя элемента формы — динамическое, набор неполон
            // даже у формы с элементами (их добавляет код в других модулях). Вместо проверяемого имени
            // возвращается FormAllItems, которое есть среди типов, — тип пропускается.
            if ("Structure".equals(name) && typeNames instanceof java.util.Collection<?> names //$NON-NLS-1$
                && names.contains("FormAllItems") && !flaggedIsMethodCall(access, parts, index)) //$NON-NLS-1$
                return "FormAllItems"; //$NON-NLS-1$
            // ЭлементыФормы (ПодчиненныеЭлементы группы): имя дочернего элемента — динамическое, состав зависит
            // от формы, а не от типа. Имя вне списка не проверяется, вызов метода — проверяется.
            if ("Structure".equals(name) && typeNames instanceof java.util.Collection<?> children //$NON-NLS-1$
                && children.contains("FormItems") && !flaggedIsMethodCall(access, parts, index)) //$NON-NLS-1$
                return "FormItems"; //$NON-NLS-1$
            // Внешняя обработка/отчёт: экспортные методы и реквизиты — у конкретного объекта, набор типа неполный.
            // Возвращается имя такого типа из набора — тип пропускается для любого члена, в том числе метода.
            if ("Structure".equals(name) && typeNames instanceof java.util.Collection<?> external) //$NON-NLS-1$
                for (Object candidate : external)
                    if (candidate instanceof String text
                        && (text.startsWith("ExternalDataProcessor") || text.startsWith("ExternalReport"))) //$NON-NLS-1$ //$NON-NLS-2$
                        return text;
            // Шаблонный анонимный тип («СправочникОбъектИмяСправочника» от Справочники[Имя].СоздатьЭлемент()):
            // только свои члены, реквизиты зависят от имени, известного только при выполнении.
            if ("Structure".equals(name) && typeNames instanceof java.util.Collection<?> generic) //$NON-NLS-1$
                for (Object candidate : generic)
                    if (candidate instanceof String text && PlatformTemplateTypes.isTemplateName(text))
                        return text;
            // Имена элементов именованной коллекции задаются при выполнении, список свойств типа неполон.
            // Используем тот же реестр EDT, что DynamicFeatureAccessComputer при создании DerivedProperty.
            if ("Structure".equals(name) && !flaggedIsMethodCall(access, parts, index)) //$NON-NLS-1$
            {
                String collection = namingCollectionName(access, typeNames);
                if (collection != null)
                    return collection;
            }
            // БиблиотекаКартинок: набор членов полный, EDT пропускает тип напрасно — проверяется всегда.
            if ("PictureLib".equals(name)) //$NON-NLS-1$
                return name + ".comfort-members-complete"; //$NON-NLS-1$
            if ("SequencesManager".equals(name))
                return name + ".comfort-members-complete";
            if (ABSTRACT_TYPE.equals(name))
                return verified ? name + ".comfort-members-merged" : name; //$NON-NLS-1$
            return flaggedIsMethodCall(access, parts, index) ? name + ".comfort-method-checked" : name; //$NON-NLS-1$
        }

        private static String namingCollectionName(Object access, Object typeNames)
        {
            if (!(access instanceof EObject object) || !(object.eResource() instanceof XtextResource resource)
                || !(typeNames instanceof java.util.Collection<?> names))
                return null;
            var services = resource.getResourceServiceProvider();
            if (services == null)
                return null;
            NamingCollectionServiceProvider collections = services.get(NamingCollectionServiceProvider.class);
            IRuntimeVersionSupport versions = services.get(IRuntimeVersionSupport.class);
            if (collections == null || versions == null)
                return null;
            Version version = versions.getRuntimeVersionOrDefault(resource, Version.LATEST);
            for (Object candidate : names)
                if (candidate instanceof String text && !isMetadataCollection(text) && !METADATA_MANAGERS.contains(text)
                    && collections.isNamingCollectionTypeName(text, version))
                    return text;
            return null;
        }

        /** Единственный найденный член помечаемой части — свойство без source, объявленное в контексте типа. */
        private static boolean flaggedIsDeclaredDerivedProperty(Object parts, Object index)
        {
            if (!(parts instanceof java.util.List<?> list) || !(index instanceof Integer position)
                || position < 1 || position > list.size()
                || !(list.get(position - 1) instanceof com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess flagged))
                return false;
            var entries = flagged.getFeatureEntries();
            return entries.size() == 1
                && entries.get(0).getFeature() instanceof com._1c.g5.v8.dt.mcore.DerivedProperty property
                && property.getSource() == null
                && property.eContainer() instanceof com._1c.g5.v8.dt.mcore.ContextDef;
        }

        private static boolean flaggedIsMethodCall(Object access, Object parts, Object index)
        {
            Object flagged = parts instanceof java.util.List<?> list && index instanceof Integer position
                && position >= 1 && position <= list.size() ? list.get(position - 1) : access;
            if (flagged instanceof com._1c.g5.v8.dt.bsl.model.Invocation)
                return true;
            return flagged instanceof com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess dynamic
                && dynamic.eContainer() instanceof com._1c.g5.v8.dt.bsl.model.Invocation invocation
                && invocation.getMethodAccess() == dynamic;
        }

        /**
         * Список типов поиска методов: к абстрактному типу добавляются его наследники. Поиск метода берёт у типа
         * только собственные методы ({@code getMethods}), а не {@code allMethods}, где объединение уже сделано.
         */
        static Object withChildren(Object types, Object resource, Object memberName)
        {
            if (!(types instanceof java.util.Collection<?> list) || list.isEmpty() || list.size() > 32)
                return types;
            try
            {
                Type abstractType = null;
                for (Object candidate : list)
                    if (candidate instanceof TypeItem item
                        && EcoreUtil.resolve(item, (org.eclipse.emf.ecore.resource.Resource)resource) instanceof Type type
                        && ABSTRACT_TYPE.equals(type.getName()))
                    {
                        abstractType = type;
                        break;
                    }
                if (abstractType == null)
                    return types;
                java.util.List<Object> result = new java.util.ArrayList<>(list);
                // Одноимённый метод у наследников описан по-разному: у части платформа не отмечает возвращаемое
                // значение (ПолучитьИменаПредопределенных у планов счетов и видов расчёта). Если есть описания
                // со значением — остаются только они, иначе проверка видит «процедуру» вместо функции.
                java.util.List<Type> owners = new java.util.ArrayList<>();
                boolean anyReturns = false;
                for (Type child : children(abstractType))
                {
                    Method found = child.getContextDef() != null ? methodNamed(child, memberName) : null;
                    if (found == null)
                    {
                        if (memberName == null)
                            owners.add(child);
                        continue;
                    }
                    owners.add(child);
                    anyReturns |= found.isRetVal();
                }
                for (Type child : owners)
                {
                    Method found = memberName != null ? methodNamed(child, memberName) : null;
                    if (anyReturns && found != null && !found.isRetVal())
                        continue;
                    if (!result.contains(child))
                        result.add(child);
                }
                return result;
            }
            catch (RuntimeException | LinkageError failure)
            {
                return types;
            }
        }

        private static Method methodNamed(Type type, Object memberName)
        {
            if (!(memberName instanceof String name) || type.getContextDef() == null)
                return null;
            for (Method method : type.getContextDef().getMethods())
                if (name.equalsIgnoreCase(method.getName()) || name.equalsIgnoreCase(method.getNameRu()))
                    return method;
            return null;
        }

        static Object properties(Object result, Object self)
        {
            return merge(result, self, true);
        }

        static Object methods(Object result, Object self)
        {
            return merge(result, self, false);
        }

        /**
         * Результат {@code getAllMethodAndProperties}: тройки {@code {методы, свойства, тип}}. Члены абстрактного
         * типа, объявленные в типах-наследниках, переносятся в тройки этих наследников — подпись «~ Владелец» в
         * списке и подсказка берут тип из тройки. Одноимённый член у нескольких наследников попадает в тройку каждого:
         * штатное группирование по имени подписывает его перечнем владельцев.
         */
        @SuppressWarnings("unchecked")
        static Object byOwner(Object result)
        {
            if (!(result instanceof java.util.Collection<?> triples))
                return result;
            try
            {
                java.util.List<Object> output = new java.util.ArrayList<>();
                boolean changed = false;
                for (Object raw : triples)
                {
                    if (!(raw instanceof org.eclipse.xtext.util.Triple<?, ?, ?> triple)
                        || !(triple.getThird() instanceof Type owner) || !ABSTRACT_TYPE.equals(owner.getName()))
                    {
                        output.add(raw);
                        continue;
                    }
                    java.util.List<Type> children = children(owner);
                    java.util.Set<Type> childSet = new java.util.HashSet<>(children);
                    java.util.List<Method> keptMethods = new java.util.ArrayList<>();
                    java.util.List<Property> keptProperties = new java.util.ArrayList<>();
                    java.util.Map<Type, java.util.List<Method>> methodsOf = new java.util.LinkedHashMap<>();
                    java.util.Map<Type, java.util.List<Property>> propertiesOf = new java.util.LinkedHashMap<>();
                    for (Object item : (java.util.Collection<Object>)triple.getFirst())
                    {
                        Method method = (Method)item;
                        if (!childSet.contains(containerType(method)))
                        {
                            keptMethods.add(method);
                            continue;
                        }
                        for (Type child : children)
                            if (child.getContextDef() != null)
                                for (Method own : child.getContextDef().getMethods())
                                    if (sameName(own, method))
                                        methodsOf.computeIfAbsent(child, k -> new java.util.ArrayList<>()).add(own);
                    }
                    for (Object item : (java.util.Collection<Object>)triple.getSecond())
                    {
                        Property property = (Property)item;
                        if (!childSet.contains(containerType(property)))
                        {
                            keptProperties.add(property);
                            continue;
                        }
                        for (Type child : children)
                            if (child.getContextDef() != null)
                                for (Property own : child.getContextDef().getProperties())
                                    if (sameName(own, property))
                                        propertiesOf.computeIfAbsent(child, k -> new java.util.ArrayList<>()).add(own);
                    }
                    if (methodsOf.isEmpty() && propertiesOf.isEmpty())
                    {
                        output.add(raw);
                        continue;
                    }
                    changed = true;
                    if (!keptMethods.isEmpty() || !keptProperties.isEmpty())
                        output.add(org.eclipse.xtext.util.Tuples.create(keptMethods, keptProperties, owner));
                    java.util.Set<Type> ordered = new java.util.LinkedHashSet<>(methodsOf.keySet());
                    ordered.addAll(propertiesOf.keySet());
                    for (Type child : ordered)
                        output.add(org.eclipse.xtext.util.Tuples.create(
                            methodsOf.getOrDefault(child, java.util.List.<Method>of()),
                            propertiesOf.getOrDefault(child, java.util.List.<Property>of()), child));
                }
                return changed ? output : result;
            }
            catch (RuntimeException exception)
            {
                return result;
            }
        }

        private static Type containerType(EObject member)
        {
            EObject container = member.eContainer();
            return container != null && container.eContainer() instanceof Type type ? type : null;
        }

        private static boolean sameName(com._1c.g5.v8.dt.mcore.DuallyNamedElement left,
            com._1c.g5.v8.dt.mcore.DuallyNamedElement right)
        {
            return left.getName() != null && left.getName().equalsIgnoreCase(right.getName());
        }

        @SuppressWarnings("unchecked")
        private static Object merge(Object result, Object self, boolean properties)
        {
            if (!(self instanceof com._1c.g5.v8.dt.mcore.ContextDef context)
                || !(context.eContainer() instanceof Type owner)
                || BUSY.get() != null || !(result instanceof java.util.List<?> own))
                return result;
            if (!ABSTRACT_TYPE.equals(owner.getName()))
                return result;
            BUSY.set(Boolean.TRUE);
            try
            {
                java.util.List<Type> children = children(owner);
                if (children.isEmpty())
                    return result;
                java.util.Set<String> names = new java.util.HashSet<>();
                for (Object item : own)
                    names.add(key((com._1c.g5.v8.dt.mcore.DuallyNamedElement)item));
                java.util.List<Object> merged = new java.util.ArrayList<>((java.util.List<Object>)own);
                for (Type child : children)
                {
                    com._1c.g5.v8.dt.mcore.ContextDef def = child.getContextDef();
                    if (def == null)
                        continue;
                    for (com._1c.g5.v8.dt.mcore.DuallyNamedElement member : properties
                        ? (java.util.List<? extends com._1c.g5.v8.dt.mcore.DuallyNamedElement>)def.getProperties()
                        : (java.util.List<? extends com._1c.g5.v8.dt.mcore.DuallyNamedElement>)def.getMethods())
                    {
                        if (names.add(key(member)))
                        {
                            merged.add(member);
                            continue;
                        }
                        // Описание со значением заменяет описание-процедуру того же метода у другого наследника
                        if (!properties && member instanceof Method method && method.isRetVal())
                            for (int index = own.size(); index < merged.size(); index++)
                                if (merged.get(index) instanceof Method kept && !kept.isRetVal()
                                    && key(kept).equals(key(member)))
                                    merged.set(index, member);
                    }
                }
                // Штатный allMethods возвращает EList; BslUtil.allMethods — изменяемый список, его так и оставляем.
                return result instanceof org.eclipse.emf.common.util.EList
                    ? new org.eclipse.emf.common.util.DelegatingEList.UnmodifiableEList<>(merged) : merged;
            }
            catch (RuntimeException exception)
            {
                return result;
            }
            finally
            {
                BUSY.remove();
            }
        }

        private static String key(com._1c.g5.v8.dt.mcore.DuallyNamedElement member)
        {
            return (member.getName() != null ? member.getName() : "").toLowerCase(java.util.Locale.ROOT); //$NON-NLS-1$
        }

        private static java.util.List<Type> children(Type owner)
        {
            java.util.List<java.lang.ref.WeakReference<Type>> cached = CHILDREN.get(owner);
            if (cached != null)
            {
                java.util.List<Type> live = new java.util.ArrayList<>(cached.size());
                for (java.lang.ref.WeakReference<Type> reference : cached)
                {
                    Type type = reference.get();
                    if (type == null)
                    {
                        live = null;
                        break;
                    }
                    live.add(type);
                }
                if (live != null)
                    return live;
            }
            java.util.List<Type> found = find(owner);
            verified = verified || !found.isEmpty();
            java.util.List<java.lang.ref.WeakReference<Type>> references = new java.util.ArrayList<>(found.size());
            for (Type type : found)
                references.add(new java.lang.ref.WeakReference<>(type));
            CHILDREN.put(owner, references);
            return found;
        }

        private static java.util.List<Type> find(Type owner)
        {
            java.util.List<Type> found = new java.util.ArrayList<>();
            URI ownerUri = EcoreUtil.getURI(owner);
            com._1c.g5.v8.dt.platform.IEObjectProvider provider = null;
            for (com._1c.g5.v8.dt.platform.version.Version version
                : com._1c.g5.v8.dt.platform.version.Version.getPlatformSupportVersions())
            {
                com._1c.g5.v8.dt.platform.IEObjectProvider candidate = com._1c.g5.v8.dt.platform.IEObjectProvider.Registry.INSTANCE
                    .get(com._1c.g5.v8.dt.mcore.McorePackage.Literals.TYPE_ITEM, version);
                org.eclipse.xtext.resource.IEObjectDescription description = candidate != null
                    ? candidate.getEObjectDescription(owner.getName()) : null;
                if (description != null && ownerUri.equals(EcoreUtil.getURI(description.getEObjectOrProxy())))
                {
                    provider = candidate;
                    break;
                }
            }
            if (provider == null)
                return found;
            String prefix = owner.getName().toLowerCase(java.util.Locale.ROOT);
            for (org.eclipse.xtext.resource.IEObjectDescription description : provider.getEObjectDescriptions(
                d -> d.getName() != null && d.getName().toString().length() > prefix.length()
                    && d.getName().toString().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)))
            {
                if (EcoreUtil.resolve(description.getEObjectOrProxy(), owner) instanceof Type child
                    && child != owner && descends(child, owner))
                    found.add(child);
            }
            return found;
        }

        private static boolean descends(Type type, Type owner)
        {
            TypeItem current = type.getParentType();
            for (int depth = 0; current != null && depth < 32; depth++)
            {
                if (!(EcoreUtil.resolve(current, owner) instanceof Type parent))
                    return false;
                if (parent == owner)
                    return true;
                current = parent.getParentType();
            }
            return false;
        }
    }

    /**
     * Дополнения ИР к шаблонным членам. Именованные коллекции подключаются к штатному реестру EDT:
     * поиск свойства создаёт DerivedProperty с введённым именем и типами элементов коллекции.
     * КомандыФормы включены для имён, добавляемых при выполнении: генерация EDT знает только команды формы.
     * COMОбъект уже допускает произвольные обращения в штатном валидаторе, тип значения неизвестен.
     */
    private static final class SpecialMemberTemplates
    {
        /**
         * FormItemPropertyContextDefInferrer создаёт типы коллекций внутри свойств конкретной формы,
         * ссылаясь из их контекста на платформенный контекст. Унаследованный ControlName — шаблон,
         * а не элемент этой формы. Убираем его и из соседних платформенных списков того же типа,
         * а для всех элементов формы — также из контекстов её дочерних коллекций FormItems.
         * Поиск динамических имён остаётся штатным.
         */
        static Object withoutGenericFormItems(Object result)
        {
            if (!(result instanceof Collection<?> triples))
                return result;
            java.util.Set<String> concreteCollections = new java.util.HashSet<>();
            for (Object raw : triples)
                if (raw instanceof org.eclipse.xtext.util.Triple<?, ?, ?> triple
                    && triple.getThird() instanceof Type owner && isFormItemCollection(owner.getName())
                    && org.eclipse.xtext.EcoreUtil2.getContainerOfType(owner,
                        com._1c.g5.v8.dt.form.model.Form.class) != null)
                    concreteCollections.add(owner.getName());
            java.util.List<Object> output = new java.util.ArrayList<>();
            boolean changed = false;
            for (Object raw : triples)
            {
                if (!(raw instanceof org.eclipse.xtext.util.Triple<?, ?, ?> triple)
                    || !(triple.getThird() instanceof Type owner)
                    || !concreteCollections.contains(owner.getName())
                    || !(triple.getSecond() instanceof Collection<?> properties))
                {
                    output.add(raw);
                    continue;
                }
                java.util.List<Object> kept = new java.util.ArrayList<>();
                for (Object item : properties)
                {
                    if (item instanceof Property property && "ControlName".equals(property.getName()) //$NON-NLS-1$
                        && property.eContainer() instanceof com._1c.g5.v8.dt.mcore.ContextDef context
                        && context.eContainer() instanceof Type declaration
                        && isFormItemCollection(FormItemTypes.segmentOf(declaration)))
                        continue;
                    kept.add(item);
                }
                if (kept.size() == properties.size())
                    output.add(raw);
                else
                {
                    changed = true;
                    output.add(org.eclipse.xtext.util.Tuples.create(triple.getFirst(), kept, owner));
                }
            }
            return changed ? output : result;
        }

        private static boolean isFormItemCollection(String name)
        {
            return "FormAllItems".equals(name) || "FormItems".equals(name); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static final java.util.Map<String, java.util.List<String>> COLLECTIONS = java.util.Map.of(
            "FormCommands", java.util.List.of("FormCommand"), //$NON-NLS-1$ //$NON-NLS-2$
            "PanelPages", java.util.List.of("PanelPage"), //$NON-NLS-1$ //$NON-NLS-2$
            "CustomFields", java.util.List.of("CustomField"), //$NON-NLS-1$ //$NON-NLS-2$
            "DataCompositionSchemaDataSets", java.util.List.of("DataCompositionSchemaDataSetQuery", //$NON-NLS-1$ //$NON-NLS-2$
                "DataCompositionSchemaDataSetObject", "DataCompositionSchemaDataSetUnion"), //$NON-NLS-1$ //$NON-NLS-2$
            "DataCompositionTemplateDataSets", java.util.List.of("DataCompositionTemplateDataSetQuery", //$NON-NLS-1$ //$NON-NLS-2$
                "DataCompositionTemplateDataSetObject", "DataCompositionTemplateDataSetUnion"), //$NON-NLS-1$ //$NON-NLS-2$
            "DataCompositionSchemaParameters", java.util.List.of("DataCompositionSchemaParameter"), //$NON-NLS-1$ //$NON-NLS-2$
            "FilterSettings", java.util.List.of("FilterItemControl"), //$NON-NLS-1$ //$NON-NLS-2$
            "DataCompositionSettingsVariants", java.util.List.of("DataCompositionSettingsVariant")); //$NON-NLS-1$ //$NON-NLS-2$

        /** {штатный реестр, версия}; вызывается до помещения реестра в кэш NamingCollectionServiceProvider. */
        static Object namingCollections(Object[] args)
        {
            if (!(args[0] instanceof java.util.Map<?, ?> original) || !(args[1] instanceof Version version))
                return args[0];
            java.util.Map<Object, Object> result = new java.util.HashMap<>(original);
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
            if (provider == null)
                return args[0];
            for (var rule : COLLECTIONS.entrySet())
            {
                if (original.containsKey(rule.getKey()))
                    continue;
                if (provider.getEObjectDescription(rule.getKey()) == null)
                    continue; // Тип отсутствует в этой версии платформы.
                java.util.List<URI> elements = new java.util.ArrayList<>();
                for (String name : rule.getValue())
                {
                    var description = provider.getEObjectDescription(name);
                    if (description != null)
                        elements.add(EcoreUtil.getURI(description.getEObjectOrProxy()));
                }
                if (elements.size() == rule.getValue().size())
                    result.put(rule.getKey(), java.util.List.copyOf(elements));
            }
            return result;
        }


    }

    /** Дополняет источник найденного свойства, сохраняя штатный список результата. */
    static Object traceProperty(Object[] args)
    {
        completeGenericFilterSource(args);
        return args[0];
    }

    private static void completeGenericFilterSource(Object[] args)
    {
        if (!(args[0] instanceof Collection<?> found) || found.isEmpty()
            || !(args[1] instanceof Collection<?> types) || !(args[3] instanceof Resource resource))
            return;
        for (Object raw : types)
        {
            if (!(raw instanceof TypeItem item) || !isGenericFilter(item))
                continue;
            if (!(EcoreUtil.resolve(item, resource) instanceof Type type) || type.getContextDef() == null)
                continue;
            for (Property template : type.getContextDef().allProperties())
            {
                if (!template.isDependsOnMetadata() || !"FilterElementName".equals(template.getName())) //$NON-NLS-1$
                    continue;
                for (Object entry : found)
                    if (entry instanceof com._1c.g5.v8.dt.bsl.model.FeatureEntry feature
                        && feature.getFeature() instanceof DerivedProperty property && property.getSource() == null)
                    {
                        property.setSource(template);
                    }
                return;
            }
        }
    }

    private static boolean isGenericFilter(TypeItem item)
    {
        URI uri = EcoreUtil.getURI(item);
        return uri != null
            && uri.toString().startsWith("platform:/plugin/com._1c.g5.v8.dt.platform_v") //$NON-NLS-1$
            && "Filter.type".equals(uri.lastSegment()); //$NON-NLS-1$
    }

    /**
     * Вызов из инструментированного {@code TypeSetMethodProvider.get} перед возвратом:
     * {@code {найденное, typeSet, context, имя}}. Возвращает дополненный результат.
     */
    @SuppressWarnings("unchecked")
    static Object typeSetMethods(Object[] args)
    {
        Object result = args[0];
        try
        {
            if (!(result instanceof Collection<?> found) || !found.isEmpty()
                || !(args[1] instanceof TypeSet set) || !(args[3] instanceof String name))
                return result;
            var producer = BslEditorHyperlinkHook.typeProducer(set);
            if (!(producer instanceof DefinedType) && !(producer instanceof ChartOfCharacteristicTypes))
                return result;
            EObject context = args[2] instanceof EObject object ? object : null;
            Collection<Object> methods = (Collection<Object>)found;
            for (TypeItem item : set.types(context))
            {
                if (!(item instanceof Type type) || type.getContextDef() == null)
                    continue;
                for (Method method : type.getContextDef().allMethods())
                    if ((name.equalsIgnoreCase(method.getName()) || name.equalsIgnoreCase(method.getNameRu()))
                        && !methods.contains(method))
                        methods.add(method);
            }
        }
        catch (RuntimeException exception)
        {
            // Не удалось добрать — остаётся штатный результат EDT.
        }
        return result;
    }

    private static final class ProviderTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!TARGET_INTERNAL.equals(className))
                return null;
            try
            {
                byte[] transformed = transformProvider(classfileBuffer);
                return transformed;
            }
            catch (Throwable t)
            {
                return null;
            }
        }
    }

    private static final class ProviderWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            if (wovenClass.getState() != WovenClass.TRANSFORMING
                || !TARGET.equals(wovenClass.getClassName()))
                return;
            try
            {
                byte[] transformed = transformProvider(wovenClass.getBytes());
                if (transformed != null)
                {
                    wovenClass.setBytes(transformed);
                    woven = true;
                }
            }
            catch (Throwable exception)
            {
            }
        }
    }

    static byte[] transformProvider(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                return "java/lang/Object"; //$NON-NLS-1$
            }
        };
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || !"get".equals(name) || !GET_DESC.equals(descriptor)) //$NON-NLS-1$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                            emitTypeSetMethods(this, touched);
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    /**
     * Стек при {@code ARETURN}: найденные методы. Если в свойствах есть {@link Function} —
     * результат заменяется на {@code apply({найденное, typeSet, context, имя})}.
     */
    private static void emitTypeSetMethods(MethodVisitor mv, AtomicBoolean touched)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(PROP_TYPE_SET_METHODS);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        // найденное, функция
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Function"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
        mv.visitInsn(Opcodes.SWAP);
        // функция, найденное
        mv.visitInsn(Opcodes.ICONST_4);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP_X1);
        mv.visitInsn(Opcodes.SWAP);
        // функция, массив, массив, найденное
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitInsn(Opcodes.AASTORE);
        emitStoreField(mv, 1, "typeSet", "Lcom/_1c/g5/v8/dt/mcore/TypeSet;"); //$NON-NLS-1$ //$NON-NLS-2$
        emitStoreField(mv, 2, "context", "Lorg/eclipse/emf/ecore/EObject;"); //$NON-NLS-1$ //$NON-NLS-2$
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Collection"); //$NON-NLS-1$
        touched.set(true);
    }

    /** Стек: массив → массив; кладёт в элемент {@code index} поле провайдера. */
    private static void emitStoreField(MethodVisitor mv, int index, String field, String descriptor)
    {
        mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(Opcodes.BIPUSH, index);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_INTERNAL, field, descriptor);
        mv.visitInsn(Opcodes.AASTORE);
    }
}
