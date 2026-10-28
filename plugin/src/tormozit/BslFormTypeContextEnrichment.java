// BslFormTypeContextEnrichment.java
package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.emf.common.util.EList;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;

import com._1c.g5.v8.dt.bsl.typesystem.BslTreeTypeSystem;
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

import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.bsl.model.typesytem.TypeSystemMode;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeState;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProvider;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProviderCollector;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;

/**
 * Дополнение типа формы экспортом её модуля и допуск {@code ПолучитьФорму} в
 * {@code GetOrOpenFormInvocationTypesComputer}.
 * <p>
 * EDT при {@code см. ОбщаяФорма.…} и {@code ОткрытьФорму/ПолучитьФорму("…")}
 * кладёт в {@code refContextDefs} только {@code Form.getFormContext()} (реквизиты/
 * элементы). Экспортные процедуры и переменные модуля живут в
 * {@code Module.getContextDef()} и туда не попадают — после точки их не видно.
 * Подмешивание экспорта — только при флажке проекта
 * {@link BslDocCommentComputedTypes#PREF_EXTENDED_TYPE_COMPUTATION}.
 * <p>
 * Допуск глобального {@code ПолучитьФорму} (реквизиты конкретной формы) — безусловный:
 * это дыра EDT в {@code StaticFeatureAccess}, не «расширенный» расчёт.
 * Типы из {@code см.} в параметрах считает уже загруженный {@code BslTreeTypeSystem};
 * обогащение экспортом — после {@code installTypeSystem} через подкласс в
 * {@link BslStructureInsertCommentTypes}.
 */
public final class BslFormTypeContextEnrichment
{
    /** Защита от рекурсии {@code installTypeSystem} модуля формы из чужого модуля. */
    private static final Set<Module> INSTALLING_FORM_MODULE =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /** Модули формы, для которых полный расчёт уже делали в этой сессии JVM. */
    private static final Set<Module> FORM_MODULE_INSTALLED =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private static final String TARGET_GET_OR_OPEN =
        "com._1c.g5.v8.dt.bsl.typesystem.GetOrOpenFormInvocationTypesComputer"; //$NON-NLS-1$
    private static final String TARGET_TYPE_SYSTEM_UTIL =
        "com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil"; //$NON-NLS-1$
    private static final String SELF_INTERNAL = "tormozit/BslFormTypeContextEnrichment"; //$NON-NLS-1$
    private static final String AFTER_TYPES_LIST_DESC =
        "(Ljava/util/List;)Ljava/util/List;"; //$NON-NLS-1$
    private static final String AFTER_TYPES_COLLECTION_DESC =
        "(Ljava/util/Collection;)Ljava/util/Collection;"; //$NON-NLS-1$
    private static final String IS_GET_FORM_DESC = "(Ljava/lang/String;)Z"; //$NON-NLS-1$

    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean attributeTransformerInstalled = new AtomicBoolean();
    private static final String ATTRIBUTE_INFERRER =
        "com._1c.g5.v8.dt.form.resource.FormImplicitRefInferrer"; //$NON-NLS-1$
    private static final String PROPERTY_COMPUTER =
        "com._1c.g5.v8.dt.bsl.resource.TypesComputer"; //$NON-NLS-1$
    private static final String ATTRIBUTE_CALLBACK = "tormozit.formAttribute.types"; //$NON-NLS-1$
    private static final String ATTRIBUTE_ACCESS_CALLBACK = "tormozit.formAttribute.accessTypes"; //$NON-NLS-1$
    private static final ThreadLocal<Set<StaticFeatureAccess>> ATTRIBUTE_ACCESS_VISITING =
        ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    private BslFormTypeContextEnrichment() {}

    /** Регистрация {@link WeavingHook}; как можно раньше из {@code Activator.start}. */
    public static void installWeavingHook()
    {
        if (!installed.compareAndSet(false, true))
            return;
        System.getProperties().put(ATTRIBUTE_CALLBACK,
            (java.util.function.Function<Object[], Object>) BslFormTypeContextEnrichment::afterAttributeTypes);
        System.getProperties().put(ATTRIBUTE_ACCESS_CALLBACK,
            (java.util.function.Function<Object[], Object>) BslFormTypeContextEnrichment::afterAttributeAccessTypes);
        Bundle bundle = FrameworkUtil.getBundle(BslFormTypeContextEnrichment.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(WeavingHook.class, new FormTypesWeavingHook(), null);
    }

    /** Уже загруженные вычислители и сохранённые контексты форм проходят тот же нормализатор. */
    static void installAttributeTypes()
    {
        if (!attributeTransformerInstalled.compareAndSet(false, true))
            return;
        if (Boolean.TRUE.equals(System.getProperties().get("tormozit.typeComputation.early"))) //$NON-NLS-1$
        {
            return;
        }
        boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(new AttributeTypesTransformer(),
            ATTRIBUTE_INFERRER, PROPERTY_COMPUTER);
        if (!ok)
            attributeTransformerInstalled.set(false);
    }

    /** Пустой тип реквизита формы означает Произвольный, а не отсутствие значения. */
    private static Object afterAttributeTypes(Object[] args)
    {
        Object result = args[0];
        DerivedProperty property = result instanceof DerivedProperty derived ? derived
            : args[1] instanceof DerivedProperty derived ? derived : null;
        FormAttribute attribute = args[1] instanceof FormAttribute formAttribute ? formAttribute
            : property != null && property.getSource() instanceof FormAttribute formAttribute ? formAttribute : null;
        if (property == null || attribute == null || attribute.eIsProxy())
            return result;
        if (!property.getTypes().isEmpty() || attribute.getValueType() == null
            || !attribute.getValueType().getTypes().isEmpty())
            return result;
        IRuntimeVersionSupport versions = (IRuntimeVersionSupport)args[2];
        Version version = versions.getRuntimeVersionOrDefault(attribute, Version.LATEST);
        IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
        TypeItem arbitrary = (TypeItem)provider.getProxy("Arbitrary"); //$NON-NLS-1$
        // Чтение уже сохранённого BM-контекста может идти в read-only транзакции.
        if (result instanceof List<?>)
            return List.of(arbitrary);
        var container = McoreFactory.eINSTANCE.createTypeContainerRef();
        container.getTypes().add(arbitrary);
        property.setTypeContainer(container);
        return result;
    }

    /** Тип произвольного реквизита в точке чтения — по предшествующему безусловному присваиванию. */
    private static Object afterAttributeAccessTypes(Object[] args)
    {
        StaticFeatureAccess access = (StaticFeatureAccess)args[1];
        if (access.getFeatureEntries().size() != 1
            || !(access.getFeatureEntries().get(0).getFeature() instanceof DerivedProperty property)
            || !(property.getSource() instanceof FormAttribute attribute)
            || attribute.getValueType() == null)
            return args[0];
        for (TypeItem item : attribute.getValueType().getTypes())
            if (!"Arbitrary".equals(McoreUtil.getTypeName(item))) //$NON-NLS-1$
                return args[0];
        Method method = EcoreUtil2.getContainerOfType(access, Method.class);
        if (method == null)
            return args[0];
        Set<StaticFeatureAccess> visiting = ATTRIBUTE_ACCESS_VISITING.get();
        if (!visiting.add(access))
            return args[0];
        try
        {
            Expression assigned = null;
            boolean reached = false;
            for (Statement statement : method.getStatements())
            {
                if (EcoreUtil.isAncestor(statement, access))
                {
                    reached = true;
                    if (writesAttribute(statement, property))
                        assigned = null;
                    break;
                }
                if (statement instanceof SimpleStatement simple && refersToAttribute(simple.getLeft(), property))
                    assigned = simple.getRight();
                else if (writesAttribute(statement, property))
                    assigned = null;
            }
            if (!reached || assigned == null)
            {
                return args[0];
            }
            TypesComputer computer = (TypesComputer)args[3];
            List<TypeItem> computed = computer.computeTypes(assigned, (Environments)args[4]);
            return computed.isEmpty() ? args[0] : new ArrayList<>(computed);
        }
        finally
        {
            visiting.remove(access);
            if (visiting.isEmpty())
                ATTRIBUTE_ACCESS_VISITING.remove();
        }
    }

    private static boolean writesAttribute(Statement statement, Property property)
    {
        if (statement instanceof SimpleStatement simple && refersToAttribute(simple.getLeft(), property))
            return true;
        for (SimpleStatement simple : EcoreUtil2.getAllContentsOfType(statement, SimpleStatement.class))
            if (refersToAttribute(simple.getLeft(), property))
                return true;
        return false;
    }

    private static boolean refersToAttribute(Expression expression, Property property)
    {
        if (expression instanceof StaticFeatureAccess access)
            for (var entry : access.getFeatureEntries())
                if (entry.getFeature() == property || entry.getFeature() != null
                    && EcoreUtil.getURI(entry.getFeature()).equals(EcoreUtil.getURI(property)))
                    return true;
        // Запись через объект формы тоже отменяет ранее установленный тип.
        return expression instanceof DynamicFeatureAccess access
            && access.getName() != null && (access.getName().equalsIgnoreCase(property.getName())
                || access.getName().equalsIgnoreCase(property.getNameRu()));
    }

    /** Точки возврата подтверждены FormImplicitRefInferrer/TypesComputer из EDT 28.0.1.v202605050943. */
    static byte[] transformAttributeTypes(String className, byte[] bytes)
    {
        boolean inferrer = ATTRIBUTE_INFERRER.equals(className);
        String descriptor = inferrer
            ? "(Lcom/_1c/g5/v8/dt/form/model/FormAttribute;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Lcom/_1c/g5/v8/dt/mcore/DerivedProperty;" //$NON-NLS-1$
            : "(Lcom/_1c/g5/v8/dt/mcore/Property;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;"; //$NON-NLS-1$
        byte[] transformed = BslTypesComputerHook.transformReturnCallback(className, bytes,
            inferrer ? "inferProperty" : "_compute", descriptor, ATTRIBUTE_CALLBACK); //$NON-NLS-1$ //$NON-NLS-2$
        if (!inferrer && transformed != null)
            transformed = BslTypesComputerHook.transformReturnCallback(className, transformed, "_compute", //$NON-NLS-1$
                "(Lcom/_1c/g5/v8/dt/bsl/model/StaticFeatureAccess;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;", //$NON-NLS-1$
                ATTRIBUTE_ACCESS_CALLBACK);
        return transformed;
    }

    private static final class AttributeTypesTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain,
            byte[] bytes)
        {
            String className = name == null ? "" : name.replace('/', '.'); //$NON-NLS-1$
            return ATTRIBUTE_INFERRER.equals(className) || PROPERTY_COMPUTER.equals(className)
                ? transformAttributeTypes(className, bytes) : null;
        }
    }

    /**
     * Глобальный {@code ПолучитьФорму}/{@code GetForm} — то, что EDT отсекает в
     * {@code StaticFeatureAccess} до разбора имени формы.
     */
    public static boolean isStaticGetFormMethod(String name)
    {
        if (name == null || name.isEmpty())
            return false;
        return "getform".equalsIgnoreCase(name) //$NON-NLS-1$
            || "получитьформу".equalsIgnoreCase(name); //$NON-NLS-1$
    }

    /** Глобальный {@code ОткрытьФорму}/{@code OpenForm}. */
    public static boolean isStaticOpenFormMethod(String name)
    {
        if (name == null || name.isEmpty())
            return false;
        return "openform".equalsIgnoreCase(name) //$NON-NLS-1$
            || "открытьформу".equalsIgnoreCase(name); //$NON-NLS-1$
    }

    /** Имя формы в строковом аргументе {@code ПолучитьФорму}/{@code ОткрытьФорму}. */
    public static boolean isStaticGetOrOpenFormMethod(String name)
    {
        return isStaticGetFormMethod(name) || isStaticOpenFormMethod(name);
    }

    /** Обогащает типы формы экспортом модуля; список тот же экземпляр. */
    public static List<?> afterFormTypes(List<?> types)
    {
        enrichTypes(types);
        return types;
    }

    /** То же для {@code Collection} из документирующего комментария. */
    public static Collection<?> afterCommentTypes(Collection<?> types)
    {
        enrichTypes(types);
        return types;
    }

    /**
     * После {@code BslTreeTypeSystem.installTypeSystem}: типы из {@code см.} уже лежат
     * в состояниях параметров, но без экспорта модуля. Weaving {@code BslTreeTypeSystem}
     * невозможен (синглтон загружен до Comfort) — обогащаем состояния на месте.
     * Только при «Расширенном вычислении типов».
     */
    public static void enrichModule(Module module)
    {
        if (module == null || module.eIsProxy())
            return;
        if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(module))
            return;
        try
        {
            BslStructureInsertCommentTypes.enrichExportModuleVariables(module);
            for (Method method : module.allMethods())
                enrichMethodUnchecked(method);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Узкий проход для {@code lightInstallingTypeSystem} — только текущий метод.
     * Только при «Расширенном вычислении типов».
     */
    public static void enrichMethod(Method method)
    {
        if (method == null || method.eIsProxy())
            return;
        if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(method))
            return;
        enrichMethodUnchecked(method);
    }

    private static void enrichMethodUnchecked(Method method)
    {
        try
        {
            for (FormalParam param : method.getFormalParams())
                enrichStateCollector(param.getTypeStateProvider());
            enrichStateCollectorList(method.getFinalInParamState());
            enrichStateCollectorList(method.getFinalOutParamState());
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Добавляет {@code Module.getContextDef()} к {@code refContextDefs} типа формы.
     * Только при «Расширенном вычислении типов».
     */
    public static void enrichTypes(Collection<?> types)
    {
        if (types == null || types.isEmpty())
            return;
        boolean scopeOwner = BslStructureInsertCommentTypes.beginExportVarsScope();
        try
        {
            for (Object item : types)
                enrichTypeItem(item);
        }
        catch (Throwable ignored)
        {
        }
        finally
        {
            BslStructureInsertCommentTypes.endExportVarsScope(scopeOwner);
        }
    }

    private static void enrichStateCollectorList(
        org.eclipse.emf.common.util.EList<VariableTypeStateProviderCollector> collectors)
    {
        if (collectors == null || collectors.isEmpty())
            return;
        for (VariableTypeStateProviderCollector collector : collectors)
            enrichStateCollector(collector);
    }

    private static void enrichStateCollector(VariableTypeStateProviderCollector collector)
    {
        if (collector == null)
            return;
        for (TypeSystemMode mode : TypeSystemMode.values())
        {
            VariableTypeStateProvider provider = collector.get(mode);
            if (provider == null)
                continue;
            List<VariableTypeState> states = provider.getAll();
            if (states == null || states.isEmpty())
                continue;
            for (VariableTypeState state : states)
            {
                if (state == null || !state.hasTypes())
                    continue;
                for (Object item : state.getTypes())
                    enrichTypeItem(item);
            }
        }
    }

    private static void enrichTypeItem(Object item)
    {
        if (!(item instanceof Type type))
            return;
        ContextDef contextDef = type.getContextDef();
        if (contextDef == null)
            return;
        EList<ContextDef> refs = contextDef.getRefContextDefs();
        if (refs == null || refs.isEmpty())
            return;
        List<ContextDef> toAdd = new ArrayList<>();
        for (ContextDef ref : new ArrayList<>(refs))
        {
            if (ref == null || ref.eIsProxy())
                continue;
            EObject container = ref.eContainer();
            if (!(container instanceof AbstractForm form) || form.eIsProxy())
                continue;
            // Экспорт модуля — только «Расширенное вычисление типов»; реквизиты уже в ref.
            if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(form))
                continue;
            Module module = form.getModule();
            if (module == null || module.eIsProxy())
                continue;
            prepareFormModuleExportContext(module);
            ContextDef moduleCtx = module.getContextDef();
            if (moduleCtx == null || moduleCtx.eIsProxy())
                continue;
            if (refs.contains(moduleCtx) || toAdd.contains(moduleCtx))
                continue;
            toAdd.add(moduleCtx);
        }
        if (!toAdd.isEmpty())
            refs.addAll(toAdd);
    }

    /**
     * Модуль формы из {@code см. …} в чужом модуле: состояния переменных уже могли
     * обогатиться, но {@code ContextDef} экспорта — снимок {@code ContextDefTypesBuilder}.
     */
    private static void prepareFormModuleExportContext(Module module)
    {
        try
        {
            ensureFormModuleTypesInstalled(module);
            BslStructureInsertCommentTypes.enrichExportModuleVariables(module);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Модуль формы из {@code см. …} в чужом модуле: без полного расчёта типов
     * {@code Перем … Экспорт; // см. …} не попадает в {@code ContextDef}.
     */
    private static void ensureFormModuleTypesInstalled(Module module)
    {
        if (module == null || module.eIsProxy())
            return;
        if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(module))
            return;
        if (FORM_MODULE_INSTALLED.contains(module))
            return;
        if (!INSTALLING_FORM_MODULE.add(module))
            return;
        try
        {
            BslTreeTypeSystem tree = BslStructureInsertCommentTypes.peekTreeTypeSystem();
            if (tree == null)
                return;
            BslStructureInsertCommentTypes.installTypeSystemNonInterruptable(tree, module);
            FORM_MODULE_INSTALLED.add(module);
        }
        catch (Throwable ignored)
        {
        }
        finally
        {
            INSTALLING_FORM_MODULE.remove(module);
        }
    }

    static byte[] transformGetOrOpenForm(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null)
                    return null;
                if ("getTypes".equals(name) //$NON-NLS-1$
                    && descriptor != null
                    && descriptor.endsWith(")Ljava/util/List;")) //$NON-NLS-1$
                {
                    return new EnrichListReturnVisitor(mv, touched);
                }
                if ("computeTypes".equals(name) //$NON-NLS-1$
                    && descriptor != null
                    && descriptor.endsWith(")Ljava/util/List;")) //$NON-NLS-1$
                {
                    return new AllowStaticGetFormVisitor(mv, touched);
                }
                return mv;
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    static byte[] transformTypeSystemUtil(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null)
                    return null;
                if (!"computeCommentTypes".equals(name) //$NON-NLS-1$
                    || descriptor == null
                    || !descriptor.endsWith(")Ljava/util/Collection;")) //$NON-NLS-1$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                        {
                            visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                                "afterCommentTypes", AFTER_TYPES_COLLECTION_DESC, false); //$NON-NLS-1$
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    /** Перед {@code ARETURN} списка типов вызывает {@link #afterFormTypes}. */
    private static final class EnrichListReturnVisitor extends MethodVisitor
    {
        private final AtomicBoolean touched;

        EnrichListReturnVisitor(MethodVisitor mv, AtomicBoolean touched)
        {
            super(Opcodes.ASM9, mv);
            this.touched = touched;
        }

        @Override
        public void visitInsn(int opcode)
        {
            if (opcode == Opcodes.ARETURN)
            {
                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                    "afterFormTypes", AFTER_TYPES_LIST_DESC, false); //$NON-NLS-1$
                touched.set(true);
            }
            super.visitInsn(opcode);
        }
    }

    /**
     * После проверки {@code OpenForm}, перед {@code emptyList}, допускает
     * {@code ПолучитьФорму}/{@code GetForm} с переходом на ту же метку, что и у
     * успешного {@code OpenForm}.
     */
    private static final class AllowStaticGetFormVisitor extends MethodVisitor
    {
        private final AtomicBoolean touched;
        private boolean sawOpenFormLdc;
        private boolean afterOpenFormEquals;
        private Label continueAfterOpenFormCheck;
        private boolean gatePatched;

        AllowStaticGetFormVisitor(MethodVisitor mv, AtomicBoolean touched)
        {
            super(Opcodes.ASM9, mv);
            this.touched = touched;
        }

        @Override
        public void visitLdcInsn(Object value)
        {
            if ("OpenForm".equals(value)) //$NON-NLS-1$
                sawOpenFormLdc = true;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
            boolean isInterface)
        {
            if (sawOpenFormLdc
                && opcode == Opcodes.INVOKEVIRTUAL
                && "equalsIgnoreCase".equals(name)) //$NON-NLS-1$
            {
                afterOpenFormEquals = true;
                sawOpenFormLdc = false;
            }
            if (!gatePatched
                && continueAfterOpenFormCheck != null
                && opcode == Opcodes.INVOKESTATIC
                && "java/util/Collections".equals(owner) //$NON-NLS-1$
                && "emptyList".equals(name)) //$NON-NLS-1$
            {
                // aload_1 (Invocation) уже не на стеке — заново:
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    "com/_1c/g5/v8/dt/bsl/model/Invocation", //$NON-NLS-1$
                    "getMethodAccess", //$NON-NLS-1$
                    "()Lcom/_1c/g5/v8/dt/bsl/model/FeatureAccess;", //$NON-NLS-1$
                    true);
                visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    "com/_1c/g5/v8/dt/bsl/model/FeatureAccess", //$NON-NLS-1$
                    "getName", //$NON-NLS-1$
                    "()Ljava/lang/String;", //$NON-NLS-1$
                    true);
                visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL,
                    "isStaticGetFormMethod", IS_GET_FORM_DESC, false); //$NON-NLS-1$
                visitJumpInsn(Opcodes.IFNE, continueAfterOpenFormCheck);
                gatePatched = true;
                touched.set(true);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label)
        {
            if (afterOpenFormEquals && opcode == Opcodes.IFNE)
            {
                continueAfterOpenFormCheck = label;
                afterOpenFormEquals = false;
            }
            super.visitJumpInsn(opcode, label);
        }
    }

    private static final class FormTypesWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            String className = wovenClass.getClassName();
            try
            {
                byte[] transformed = null;
                if (TARGET_GET_OR_OPEN.equals(className))
                    transformed = transformGetOrOpenForm(wovenClass.getBytes());
                else if (TARGET_TYPE_SYSTEM_UTIL.equals(className))
                    transformed = transformTypeSystemUtil(wovenClass.getBytes());
                if (transformed != null)
                {
                    wovenClass.getDynamicImports().add("tormozit"); //$NON-NLS-1$
                    wovenClass.setBytes(transformed);
                }
            }
            catch (Throwable ignored)
            {
            }
        }
    }
}
