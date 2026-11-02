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

import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.Environments;
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
            (Function<Object[], Object>) args -> AbstractTypeMembers.byOwner(args[0]));
        System.getProperties().put("tormozit.dynamicFeatureAccess.formItemTypes", //$NON-NLS-1$
            (Function<Object[], Object>) FormItemTypes::expand);
        System.getProperties().put("tormozit.bslValidator.openType", //$NON-NLS-1$
            (Function<Object[], Object>) args -> AbstractTypeMembers.validatorOpenType(args[0], args[1], args[2], args[3], args[4]));
        Bundle bundle = FrameworkUtil.getBundle(DynamicFeatureAccessComputerHook.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(WeavingHook.class, new ProviderWeavingHook(), null);
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
     * {@code getParentType()}); результат кэшируется на тип.
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
            // ВсеЭлементыФормы в списке открытых типов EDT нет: имя элемента формы — динамическое, и набор полон
            // только у модуля загруженной формы с элементами. Иначе вместо проверяемого имени возвращается
            // FormAllItems, которое есть среди типов, — тип пропускается.
            if ("Structure".equals(name) && typeNames instanceof java.util.Collection<?> names //$NON-NLS-1$
                && names.contains("FormAllItems") && !flaggedIsMethodCall(access, parts, index) //$NON-NLS-1$
                && !formItemsKnown(access))
                return "FormAllItems"; //$NON-NLS-1$
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
            if (ABSTRACT_TYPE.equals(name))
                return verified ? name + ".comfort-members-merged" : name; //$NON-NLS-1$
            return flaggedIsMethodCall(access, parts, index) ? name + ".comfort-method-checked" : name; //$NON-NLS-1$
        }

        /** Модуль принадлежит загруженной форме, и у неё есть элементы: имя вне списка — действительно неизвестное. */
        private static boolean formItemsKnown(Object access)
        {
            com._1c.g5.v8.dt.bsl.model.Module module = access instanceof org.eclipse.emf.ecore.EObject object
                ? org.eclipse.xtext.EcoreUtil2.getContainerOfType(object, com._1c.g5.v8.dt.bsl.model.Module.class)
                : null;
            return module != null && module.getOwner() instanceof com._1c.g5.v8.dt.form.model.Form form
                && !form.eIsProxy() && !form.getItems().isEmpty();
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
                || !(context.eContainer() instanceof Type owner) || !ABSTRACT_TYPE.equals(owner.getName())
                || BUSY.get() != null || !(result instanceof java.util.List<?> own))
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
                        if (names.add(key(member)))
                            merged.add(member);
                }
                return new org.eclipse.emf.common.util.DelegatingEList.UnmodifiableEList<>(merged);
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
