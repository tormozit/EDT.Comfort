package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
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
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;

/**
 * Доработки {@code DynamicFeatureAccessComputer} — вычислителя членов выражения «через точку».
 *
 * <p>Сейчас одна: методы значения определяемого типа (1C-Company/1c-edt-issues#2402). Методы
 * {@link TypeSet} ищет {@code DynamicFeatureAccessComputer$TypeSetMethodProvider.get(имя)}: сначала в
 * {@code getBaseTypes()} — у набора определяемого типа он пуст, — потом в {@code types(context)}, но
 * там берёт только {@code BslContextDefMethod} (методы модулей) и отбрасывает платформенные
 * ({@code Пустая()}, {@code УникальныйИдентификатор()}). Свойства EDT ищет через {@code types(context)}
 * без такого отбора, поэтому с ними всё работает. Итог для методов — ложная проблема «Свойство (метод)
 * объекта не обнаружено» и «Ничего не найдено» в подсказке при наведении.
 *
 * <p>Если провайдер ничего не нашёл, а набор принадлежит определяемому типу, методы с этим именем
 * добираются из всех типов {@code types(context)}. Через этот провайдер идут и проверка модуля, и
 * подсказка, и переход к определению — правка одна на всех.
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

    private static final AtomicBoolean weavingHookInstalled = new AtomicBoolean();
    private static volatile boolean woven;

    /** Регистрация {@link WeavingHook}; как можно раньше из {@code Activator.start}. */
    public static void installWeavingHook()
    {
        if (!weavingHookInstalled.compareAndSet(false, true))
            return;
        System.getProperties().put(PROP_TYPE_SET_METHODS,
            (Function<Object[], Object>) DynamicFeatureAccessComputerHook::typeSetMethods);
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
        BslDocCommentDescriptionFix.registerExtraTransformer(new ProviderTransformer(), TARGET);
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
                || !(args[1] instanceof TypeSet set) || !(args[3] instanceof String name)
                || !(BslEditorHyperlinkHook.typeProducer(set) instanceof DefinedType))
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
        catch (RuntimeException ignored)
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
                return transformProvider(classfileBuffer);
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
            if (wovenClass.getState() != WovenClass.TRANSFORMING || !TARGET.equals(wovenClass.getClassName()))
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
            catch (Throwable ignored)
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
