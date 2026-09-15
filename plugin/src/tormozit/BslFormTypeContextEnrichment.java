// BslFormTypeContextEnrichment.java
package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
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
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.model.typesytem.TypeSystemMode;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeState;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProvider;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProviderCollector;
import com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Type;
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

    private BslFormTypeContextEnrichment() {}

    /** Регистрация {@link WeavingHook}; как можно раньше из {@code Activator.start}. */
    public static void installWeavingHook()
    {
        if (!installed.compareAndSet(false, true))
            return;
        Bundle bundle = FrameworkUtil.getBundle(BslFormTypeContextEnrichment.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(WeavingHook.class, new FormTypesWeavingHook(), null);
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
            for (Method method : module.allMethods())
                enrichMethodUnchecked(method);
            Collection<Variable> variables = TypeSystemUtil.getAllVariableForBlock(module);
            if (variables != null)
            {
                for (Variable variable : variables)
                    enrichStateCollector(variable.getTypeStateProvider());
            }
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
        try
        {
            for (Object item : types)
                enrichTypeItem(item);
        }
        catch (Throwable ignored)
        {
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
