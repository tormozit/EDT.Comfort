package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.util.BslUtil;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

/** Доработки TypesComputer из EDT bsl 28.0.1.v202605050943; общий адаптер возвратов также для свойств формы. */
final class BslTypesComputerHook
{
    private static final String TARGET = "com._1c.g5.v8.dt.bsl.resource.TypesComputer"; //$NON-NLS-1$
    private static final String CALLBACK = "tormozit.expression.plusTypes"; //$NON-NLS-1$
    private static final String DESCRIPTOR =
        "(Lcom/_1c/g5/v8/dt/bsl/model/BinaryExpression;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;"; //$NON-NLS-1$
    private static final AtomicBoolean installed = new AtomicBoolean();

    private BslTypesComputerHook() {}

    static void install()
    {
        if (!installed.compareAndSet(false, true))
            return;
        System.getProperties().put(CALLBACK, (Function<Object[], Object>) BslTypesComputerHook::afterBinaryTypes);
        if (Boolean.TRUE.equals(System.getProperties().get("tormozit.typeComputation.early"))) //$NON-NLS-1$
        {
            return;
        }
        boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(new ComputerTransformer(), TARGET);
        if (!ok)
            installed.set(false);
    }

    /** EDT для пустой левой части или Arbitrary возвращает только Number, теряя строковый вариант. */
    private static Object afterBinaryTypes(Object[] args)
    {
        BinaryExpression expression = (BinaryExpression)args[1];
        if (expression.getOperation() != BinaryOperation.PLUS)
            return args[0];
        // Штатный _compute уже вычислил левую часть; повторный обход удваивает работу на каждом +.
        @SuppressWarnings("unchecked")
        List<TypeItem> left = (List<TypeItem>)args[0];
        boolean unknown = left.isEmpty();
        for (TypeItem item : left)
        {
            String name = McoreUtil.getTypeName(item);
            unknown |= name == null || "Arbitrary".equals(name); //$NON-NLS-1$
        }
        if (!unknown)
            return args[0];
        List<TypeItem> result = new ArrayList<>(left);
        IRuntimeVersionSupport versions = (IRuntimeVersionSupport)args[2];
        Version version = versions.getRuntimeVersionOrDefault(expression, Version.LATEST);
        IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
        for (String name : List.of("Number", "String")) //$NON-NLS-1$ //$NON-NLS-2$
            if (result.stream().noneMatch(item -> name.equals(McoreUtil.getTypeName(item))))
                result.addAll(BslUtil.getTypeByName(name, provider));
        return result;
    }

    /**
     * Возврат метода EDT → Function({результат, аргумент 1, versionSupport, this, аргумент 2}).
     * Для бинарного выражения перехватывается результат штатного computeTypes левой части.
     * Оба потребителя используют подтверждённые сигнатуры с EObject и Environments.
     */
    static byte[] transformReturnCallback(String className, byte[] bytes, String method, String descriptor,
        String callback)
    {
        String owner = className.replace('.', '/');
        String cast = org.objectweb.asm.Type.getReturnType(descriptor).getInternalName();
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!method.equals(name) || !descriptor.equals(desc))
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitMethodInsn(int opcode, String calledOwner, String calledName,
                        String calledDescriptor, boolean isInterface)
                    {
                        super.visitMethodInsn(opcode, calledOwner, calledName, calledDescriptor, isInterface);
                        if (CALLBACK.equals(callback) && owner.equals(calledOwner)
                            && "computeTypes".equals(calledName) //$NON-NLS-1$
                            && "(Lorg/eclipse/emf/ecore/EObject;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;".equals(calledDescriptor)) //$NON-NLS-1$
                            emitCallback();
                    }

                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN && !CALLBACK.equals(callback))
                            emitCallback();
                        super.visitInsn(opcode);
                    }

                    private void emitCallback()
                    {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        super.visitLdcInsn(callback);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                        super.visitInsn(Opcodes.SWAP);
                        super.visitInsn(Opcodes.ICONST_5);
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
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitFieldInsn(Opcodes.GETFIELD, owner, "versionSupport", "Lcom/_1c/g5/v8/dt/platform/version/IRuntimeVersionSupport;"); //$NON-NLS-1$ //$NON-NLS-2$
                        super.visitInsn(Opcodes.AASTORE);
                        super.visitInsn(Opcodes.DUP);
                        super.visitInsn(Opcodes.ICONST_3);
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitInsn(Opcodes.AASTORE);
                        super.visitInsn(Opcodes.DUP);
                        super.visitInsn(Opcodes.ICONST_4);
                        super.visitVarInsn(Opcodes.ALOAD, 2);
                        super.visitInsn(Opcodes.AASTORE);
                        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        super.visitTypeInsn(Opcodes.CHECKCAST, cast);
                        touched.set(true);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static final class ComputerTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes)
        {
            return TARGET.replace('.', '/').equals(name)
                ? transformReturnCallback(TARGET, bytes, "_compute", DESCRIPTOR, CALLBACK) : null; //$NON-NLS-1$
        }
    }
}
