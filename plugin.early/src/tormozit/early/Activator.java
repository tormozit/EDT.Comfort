package tormozit.early;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.osgi.framework.log.FrameworkLog;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

/**
 * Ранний старт: регистрация вплетений в классы EDT и SWT до их загрузки — парсер BSL
 * (см. {@link BslParserHook}), провайдер свёрток BSL, область вкладок воркбенча. Перечень и причины —
 * в README бандла.
 *
 * <p>Бандл помечен запущенным ({@code META-INF/p2.inf}, в PDE — {@code bundles.info}) и стартует
 * вместе с фреймворком, до приложения и открытия проектов. Класс не касается типов Xtext:
 * иначе ранний старт активировал бы бандл Xtext. Вызовы {@link BslParserHook} — через лямбды,
 * класс грузится при первом разборе.
 */
public final class Activator
    implements BundleActivator
{
    @Override
    public void start(BundleContext context)
    {
        QueryNullCastWeaving.install(context);
        FormDataWeaving.install(context);
        TypeComputationWeaving.install(context);
        DynamicFeatureWeaving.install(context);
        StructureKeyWeaving.install(context);
        CheckRecomputeNotifications.install(context);
        EarlyWeaving.install(context);
        context.registerService(WeavingHook.class, new OccurrenceLiteralWeaving(), null);
        context.registerService(WeavingHook.class, new NaparnikWeaving(), null);
        TextSearchInvocationWeaving.install(context);
    }

    @Override
    public void stop(BundleContext context)
    {
        System.getProperties().put(CheckRecomputeNotifications.CALLBACK, (Consumer<Object[]>) event -> {});
    }

    /** ContextDefImpl.allProperties в mcore 8.6.0.v202607090722 добавляет наследуемый Key вторым. */
    private static final class StructureKeyWeaving implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.platform.structureKey"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK, (Function<Object[], Object>) args -> args[0]);
            context.registerService(WeavingHook.class, new StructureKeyWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING
                || !"com._1c.g5.v8.dt.mcore.impl.ContextDefImpl".equals(woven.getClassName())) //$NON-NLS-1$
                return;
            ClassReader reader = new ClassReader(woven.getBytes());
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean touched = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"allProperties".equals(name) || !"()Lorg/eclipse/emf/common/util/EList;".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(CALLBACK);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitInsn(Opcodes.ICONST_2);
                                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.DUP_X1);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_1);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "org/eclipse/emf/common/util/EList"); //$NON-NLS-1$
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            if (touched.get())
                woven.setBytes(writer.toByteArray());
            System.getProperties().put(CALLBACK + ".woven", touched.get()); //$NON-NLS-1$
        }
    }

    /** Вычислители типов должны быть вплетены до загрузки; поздний Instrumentation может отсутствовать. */
    private static final class TypeComputationWeaving implements WeavingHook
    {
        private static final String STATUS = "tormozit.typeComputation.early"; //$NON-NLS-1$
        private static final String INFERRER = "com._1c.g5.v8.dt.form.resource.FormImplicitRefInferrer"; //$NON-NLS-1$
        private static final String COMPUTER = "com._1c.g5.v8.dt.bsl.resource.TypesComputer"; //$NON-NLS-1$
        private static final String ATTRIBUTE = "tormozit.formAttribute.types"; //$NON-NLS-1$
        private static final String BINARY = "tormozit.expression.plusTypes"; //$NON-NLS-1$
        private static final String ATTRIBUTE_ACCESS = "tormozit.formAttribute.accessTypes"; //$NON-NLS-1$
        private final BundleContext context;

        private TypeComputationWeaving(BundleContext context)
        {
            this.context = context;
        }

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(ATTRIBUTE, (Function<Object[], Object>) args -> args[0]);
            System.getProperties().putIfAbsent(BINARY, (Function<Object[], Object>) args -> args[0]);
            System.getProperties().putIfAbsent(ATTRIBUTE_ACCESS, (Function<Object[], Object>) args -> args[0]);
            context.registerService(WeavingHook.class, new TypeComputationWeaving(context), null);
            System.getProperties().put(STATUS, Boolean.TRUE);
        }

        @Override
        public void weave(WovenClass woven)
        {
            String name = woven.getClassName();
            if (woven.getState() != WovenClass.TRANSFORMING || !INFERRER.equals(name) && !COMPUTER.equals(name))
                return;
            try
            {
                byte[] bytes = transform(name, woven.getBytes());
                if (bytes == null)
                    throw new IllegalStateException("Type computation method not found: " + name); //$NON-NLS-1$
                woven.setBytes(bytes);
                System.getProperties().put(STATUS + "." + name, Boolean.TRUE); //$NON-NLS-1$
                log("woven " + name, null); //$NON-NLS-1$
            }
            catch (RuntimeException failure)
            {
                System.getProperties().put(STATUS + "." + name, Boolean.FALSE); //$NON-NLS-1$
                log("weaving failed " + name, failure); //$NON-NLS-1$
            }
        }

        private void log(String message, Throwable failure)
        {
            var reference = context.getServiceReference(FrameworkLog.class);
            if (reference == null)
                return;
            FrameworkLog log = context.getService(reference);
            try
            {
                if (log != null)
                    log.log(new FrameworkLogEntry("tormozit.comfort.early", //$NON-NLS-1$
                        failure == null ? FrameworkLogEntry.INFO : FrameworkLogEntry.ERROR,
                        0, "[TypeComputationWeaving] " + message, 0, failure, null)); //$NON-NLS-1$
            }
            finally
            {
                context.ungetService(reference);
            }
        }

        static byte[] transform(String className, byte[] bytes)
        {
            String owner = className.replace('.', '/');
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean property = new AtomicBoolean();
            AtomicBoolean binary = new AtomicBoolean();
            AtomicBoolean attributeAccess = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    String callback;
                    if (INFERRER.equals(className) && "inferProperty".equals(name) //$NON-NLS-1$
                        && "(Lcom/_1c/g5/v8/dt/form/model/FormAttribute;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Lcom/_1c/g5/v8/dt/mcore/DerivedProperty;".equals(descriptor)) //$NON-NLS-1$
                    {
                        callback = ATTRIBUTE;
                        property.set(true);
                    }
                    else if (COMPUTER.equals(className) && "_compute".equals(name) //$NON-NLS-1$
                        && "(Lcom/_1c/g5/v8/dt/mcore/Property;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;".equals(descriptor)) //$NON-NLS-1$
                    {
                        callback = ATTRIBUTE;
                        property.set(true);
                    }
                    else if (COMPUTER.equals(className) && "_compute".equals(name) //$NON-NLS-1$
                        && "(Lcom/_1c/g5/v8/dt/bsl/model/BinaryExpression;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;".equals(descriptor)) //$NON-NLS-1$
                    {
                        callback = BINARY;
                        binary.set(true);
                    }
                    else if (COMPUTER.equals(className) && "_compute".equals(name) //$NON-NLS-1$
                        && "(Lcom/_1c/g5/v8/dt/bsl/model/StaticFeatureAccess;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;".equals(descriptor)) //$NON-NLS-1$
                    {
                        callback = ATTRIBUTE_ACCESS;
                        attributeAccess.set(true);
                    }
                    else
                        return mv;
                    String cast = org.objectweb.asm.Type.getReturnType(descriptor).getInternalName();
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String calledOwner, String calledName,
                            String calledDescriptor, boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, calledOwner, calledName, calledDescriptor, isInterface);
                            if (BINARY.equals(callback) && owner.equals(calledOwner)
                                && "computeTypes".equals(calledName) //$NON-NLS-1$
                                && "(Lorg/eclipse/emf/ecore/EObject;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;".equals(calledDescriptor)) //$NON-NLS-1$
                                emitCallback();
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN && !BINARY.equals(callback))
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
                            for (int index : new int[] {1, 3, 4})
                            {
                                super.visitInsn(Opcodes.DUP);
                                super.visitLdcInsn(index);
                                super.visitVarInsn(Opcodes.ALOAD, index == 1 ? 1 : index == 3 ? 0 : 2);
                                super.visitInsn(Opcodes.AASTORE);
                            }
                            super.visitInsn(Opcodes.DUP);
                            super.visitInsn(Opcodes.ICONST_2);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, owner, "versionSupport", "Lcom/_1c/g5/v8/dt/platform/version/IRuntimeVersionSupport;"); //$NON-NLS-1$ //$NON-NLS-2$
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            super.visitTypeInsn(Opcodes.CHECKCAST, cast);
                        }
                    };
                }
            }, 0);
            return property.get() && (!COMPUTER.equals(className) || binary.get() && attributeAccess.get())
                ? writer.toByteArray() : null;
        }
    }

    /** Классы комментариев и формы могут загрузиться до основного бандла. */
    private static final class FormDataWeaving implements WeavingHook
    {
        private static final String PREFIX = "tormozit.formData."; //$NON-NLS-1$
        private static final String COMMENT = "com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment"; //$NON-NLS-1$
        private static final String CONVERTER = "com._1c.g5.v8.dt.internal.platform.core.uconverter.UObjectConverterServiceProvider"; //$NON-NLS-1$
        private static final String DOCUMENTATION = "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationProvider"; //$NON-NLS-1$
        private static final String PAGE = "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.PageUtil"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            for (String operation : List.of("comment", "convert", "reference", "html", "property")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                System.getProperties().putIfAbsent(PREFIX + operation, (Function<Object[], Object>) args -> args[0]);
            context.registerService(WeavingHook.class, new FormDataWeaving(), null);
            System.getProperties().put(PREFIX + "early", Boolean.TRUE); //$NON-NLS-1$
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING)
                return;
            byte[] bytes = transform(woven.getClassName(), woven.getBytes());
            if (bytes != null)
            {
                woven.setBytes(bytes);
            }
        }

        static byte[] transform(String className, byte[] bytes)
        {
            if (!List.of(COMMENT, CONVERTER, DOCUMENTATION, PAGE).contains(className))
                return null;
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean touched = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (visitor != null && DOCUMENTATION.equals(className)
                        && "getDocByNotDerivedProperty".equals(name) //$NON-NLS-1$
                        && descriptor.startsWith("(Lcom/_1c/g5/v8/dt/mcore/Property;")) //$NON-NLS-1$
                    {
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PREFIX + "property"); //$NON-NLS-1$
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.ICONST_1);
                                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "com/_1c/g5/v8/dt/mcore/Property"); //$NON-NLS-1$
                                super.visitVarInsn(Opcodes.ASTORE, 1);
                                touched.set(true);
                            }
                        };
                    }
                    String operation;
                    int[] slots;
                    if (COMMENT.equals(className) && ("computeTypes".equals(name) //$NON-NLS-1$
                        || "computeParameterTypes".equals(name)) //$NON-NLS-1$
                        && descriptor.endsWith(")Ljava/util/Collection;")) //$NON-NLS-1$
                    {
                        operation = "comment"; //$NON-NLS-1$
                        slots = new int[] {"computeTypes".equals(name) ? 1 : -1, 8}; //$NON-NLS-1$
                    }
                    else if (CONVERTER.equals(className) && "convert".equals(name) //$NON-NLS-1$
                        && descriptor.startsWith("(Lcom/_1c/g5/v8/dt/mcore/TypeItem;")) //$NON-NLS-1$
                    {
                        operation = "convert"; //$NON-NLS-1$
                        slots = new int[] {1};
                    }
                    else if (DOCUMENTATION.equals(className) && "convertTypeItemToReference".equals(name)) //$NON-NLS-1$
                    {
                        operation = "reference"; //$NON-NLS-1$
                        slots = new int[] {1, 2};
                    }
                    else if (PAGE.equals(className) && ("generateReferenceFullHtml".equals(name) //$NON-NLS-1$
                        || "generateReferenceShortHtml".equals(name))) //$NON-NLS-1$
                    {
                        operation = "html"; //$NON-NLS-1$
                        slots = new int[] {0};
                    }
                    else
                        return visitor;
                    if (visitor == null)
                        return null;
                    return new MethodVisitor(Opcodes.ASM9, visitor)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PREFIX + operation);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitLdcInsn(slots.length + 1);
                                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.DUP_X1);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitInsn(Opcodes.AASTORE);
                                for (int index = 0; index < slots.length; index++)
                                {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitLdcInsn(index + 1);
                                    if (slots[index] < 0)
                                        super.visitInsn(Opcodes.ACONST_NULL);
                                    else
                                        super.visitVarInsn(Opcodes.ALOAD, slots[index]);
                                    super.visitInsn(Opcodes.AASTORE);
                                }
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST,
                                    org.objectweb.asm.Type.getReturnType(descriptor).getInternalName());
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return touched.get() ? writer.toByteArray() : null;
        }
    }

    /** Вычислитель BSL загружается до основного бандла: вплетение ставится здесь. */
    private static final class DynamicFeatureWeaving implements WeavingHook
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer"; //$NON-NLS-1$
        private static final String PROP_PROPERTY_TRACE = "tormozit.dynamicFeatureAccess.propertyTrace"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            // Основной бандл позже заменит эти функции; до него результат строго штатный.
            System.getProperties().putIfAbsent(PROP_PROPERTY_TRACE, (Function<Object[], Object>) args -> args[0]);
            context.registerService(WeavingHook.class, new DynamicFeatureWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                byte[] transformed = transform(woven.getBytes());
                if (transformed != null)
                    woven.setBytes(transformed);
            }
            catch (RuntimeException | LinkageError error)
            {
            }
        }

        private static final String PROPERTY_DESC = "(Ljava/util/Collection;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/dt/mcore/util/Environments;Ljava/lang/String;Z" //$NON-NLS-1$
            + "Lorg/eclipse/emf/ecore/resource/Resource;Lorg/eclipse/xtext/util/CancelIndicator;)Ljava/util/List;"; //$NON-NLS-1$

        static byte[] transform(byte[] classfileBuffer)
        {
            ClassReader reader = new ClassReader(classfileBuffer);
            // Добавленный вызов не меняет ветвление и типы локальных переменных.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean touched = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null || !"getObjectFromContextDefOfTypes".equals(name) //$NON-NLS-1$
                        || !PROPERTY_DESC.equals(descriptor))
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                // Callback дополняет source найденного свойства; список сохраняется.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PROP_PROPERTY_TRACE);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitInsn(Opcodes.ICONST_4);
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
                                super.visitVarInsn(Opcodes.ALOAD, 3);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_3);
                                super.visitVarInsn(Opcodes.ALOAD, 5);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List"); //$NON-NLS-1$
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }

                    };
                }
            }, 0);
            return touched.get() ? writer.toByteArray() : null;
        }

    }

    /** Контекст операторных слов в строках: класс задания загружается до основного бандла. */
    private static final class OccurrenceLiteralWeaving implements WeavingHook
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.bsl.ui.editor.occurrences.BslOccurrenceMarker$InnerMarkOccurrencesJob"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.occurrences.insideLiteral"; //$NON-NLS-1$
        private static final String QUERY_CALLBACK = "tormozit.occurrences.queryBrackets"; //$NON-NLS-1$
        private static final String STATUS = "tormozit.occurrences.literalPatch"; //$NON-NLS-1$

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                byte[] transformed = transform(woven.getBytes());
                if (transformed != null)
                    woven.setBytes(transformed);
                System.setProperty(STATUS, transformed != null ? "woven context+query" : "methods not found"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException | LinkageError error)
            {
                System.setProperty(STATUS, "error " + error); //$NON-NLS-1$
            }
        }

        private static byte[] transform(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if ("run".equals(name) //$NON-NLS-1$
                        && "(Lorg/eclipse/core/runtime/IProgressMonitor;)Lorg/eclipse/core/runtime/IStatus;".equals(descriptor)) //$NON-NLS-1$
                    {
                        return new MethodVisitor(Opcodes.ASM9, method)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                emitQueryBrackets(this, reader.getClassName());
                                touched[0]++;
                            }
                        };
                    }
                    if (!"isLogicalBracket".equals(name) || !"(Ljava/lang/String;)Z".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        return method;
                    return new MethodVisitor(Opcodes.ASM9, method)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            visitLdcInsn(CALLBACK);
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            visitInsn(Opcodes.DUP);
                            Label pop = new Label();
                            Label proceed = new Label();
                            visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Predicate"); //$NON-NLS-1$
                            visitJumpInsn(Opcodes.IFEQ, pop);
                            visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate"); //$NON-NLS-1$
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Z", true); //$NON-NLS-1$
                            visitJumpInsn(Opcodes.IFEQ, proceed);
                            visitInsn(Opcodes.ICONST_0);
                            visitInsn(Opcodes.IRETURN);
                            Object[] locals = { reader.getClassName(), "java/lang/String" }; //$NON-NLS-1$
                            visitLabel(pop);
                            visitFrame(Opcodes.F_NEW, locals.length, locals, 1,
                                new Object[] { "java/lang/Object" }); //$NON-NLS-1$
                            visitInsn(Opcodes.POP);
                            visitLabel(proceed);
                            visitFrame(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]);
                            touched[0]++;
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return touched[0] == 2 ? writer.toByteArray() : null;
        }

        /** Вызов приватного replaceAnnotations — тот же, что в штатной ветке логических скобок. */
        private static void emitQueryBrackets(MethodVisitor method, String owner)
        {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                "()Ljava/util/Properties;", false); //$NON-NLS-1$
            method.visitLdcInsn(QUERY_CALLBACK);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
            method.visitInsn(Opcodes.DUP);
            Label pop = new Label();
            Label proceed = new Label();
            Label apply = new Label();
            method.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Function"); //$NON-NLS-1$
            method.visitJumpInsn(Opcodes.IFEQ, pop);
            method.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
            method.visitInsn(Opcodes.DUP);
            method.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/Map"); //$NON-NLS-1$
            method.visitJumpInsn(Opcodes.IFEQ, pop);
            method.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map"); //$NON-NLS-1$
            method.visitVarInsn(Opcodes.ASTORE, 2);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/eclipse/core/runtime/IProgressMonitor", //$NON-NLS-1$
                "isCanceled", "()Z", true); //$NON-NLS-1$ //$NON-NLS-2$
            method.visitJumpInsn(Opcodes.IFEQ, apply);
            method.visitFieldInsn(Opcodes.GETSTATIC, "org/eclipse/core/runtime/Status", //$NON-NLS-1$
                "CANCEL_STATUS", "Lorg/eclipse/core/runtime/IStatus;"); //$NON-NLS-1$ //$NON-NLS-2$
            method.visitInsn(Opcodes.ARETURN);
            Object[] locals = { owner, "org/eclipse/core/runtime/IProgressMonitor" }; //$NON-NLS-1$
            method.visitLabel(apply);
            method.visitFrame(Opcodes.F_NEW, 3,
                new Object[] { owner, "org/eclipse/core/runtime/IProgressMonitor", "java/util/Map" }, //$NON-NLS-1$ //$NON-NLS-2$
                0, new Object[0]);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 2);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, owner, "viewer", //$NON-NLS-1$
                "Lorg/eclipse/jface/text/source/ISourceViewer;"); //$NON-NLS-1$
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/eclipse/jface/text/source/ISourceViewer", //$NON-NLS-1$
                "getAnnotationModel", "()Lorg/eclipse/jface/text/source/IAnnotationModel;", true); //$NON-NLS-1$ //$NON-NLS-2$
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "replaceAnnotations", //$NON-NLS-1$
                "(Ljava/util/Map;Lorg/eclipse/jface/text/source/IAnnotationModel;)V", false); //$NON-NLS-1$
            method.visitFieldInsn(Opcodes.GETSTATIC, "org/eclipse/core/runtime/Status", //$NON-NLS-1$
                "OK_STATUS", "Lorg/eclipse/core/runtime/IStatus;"); //$NON-NLS-1$ //$NON-NLS-2$
            method.visitInsn(Opcodes.ARETURN);
            method.visitLabel(pop);
            method.visitFrame(Opcodes.F_NEW, locals.length, locals, 1, new Object[] { "java/lang/Object" }); //$NON-NLS-1$
            method.visitInsn(Opcodes.POP);
            method.visitLabel(proceed);
            method.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]);
        }
    }

    /**
     * Полнотекстовый проход переименования и поиска ссылок (issue #860):
     * {@code BslTextSearchSupplier.filterMatcherForSemanticObject} первой проверкой
     * {@code semantic.eContainer() instanceof Invocation && !isMethod} отбрасывает не только вызов
     * {@code X.Имя()}, но и аргумент вызова {@code Ф(X.Имя)}. Эта единственная инструкция
     * {@code instanceof} заменена функцией {@link #PROP_DROP_AS_CALL}: «родитель — вызов, и обращение —
     * сам вызываемый метод». Аргумент вызова проходит к остальным правилам того же фильтра.
     * Ветвлений и локальных переменных вставка не добавляет — кадры стека прежние.
     */
    private static final class TextSearchInvocationWeaving implements WeavingHook
    {
        private static final String TARGET = "com._1c.g5.v8.dt.bsl.bm.ui.refactoring.BslTextSearchSupplier"; //$NON-NLS-1$
        private static final String METHOD = "filterMatcherForSemanticObject"; //$NON-NLS-1$
        private static final String METHOD_DESC = "(Lorg/eclipse/emf/ecore/EObject;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/dt/search/core/text/TextSearchFileMatch;Lorg/eclipse/emf/ecore/EObject;Z" //$NON-NLS-1$
            + "Ljava/lang/String;Lorg/eclipse/xtext/scoping/IScope;Lorg/eclipse/emf/common/util/URI;" //$NON-NLS-1$
            + "Ljava/util/Collection;)V"; //$NON-NLS-1$
        private static final String INVOCATION = "com/_1c/g5/v8/dt/bsl/model/Invocation"; //$NON-NLS-1$
        /** Номер локальной переменной семантического объекта вхождения (третий параметр). */
        private static final int SEMANTIC_SLOT = 3;
        static final String PROP_DROP_AS_CALL = "tormozit.bslTextSearch.dropAsCall"; //$NON-NLS-1$
        static final String STATUS = "tormozit.bslTextSearch.invocationPatch"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            System.getProperties().put(PROP_DROP_AS_CALL,
                (BiFunction<Object, Object, Object>) TextSearchInvocationWeaving::isCalledMethod);
            context.registerService(WeavingHook.class, new TextSearchInvocationWeaving(), null);
        }

        /**
         * {@code container} — вызов ({@code Invocation}), а {@code semantic} — его вызываемый метод
         * ({@code getMethodAccess()}). Типы модели BSL здесь недоступны — по имени интерфейса и рефлексией.
         */
        private static Object isCalledMethod(Object container, Object semantic)
        {
            if (container == null || semantic == null || !implementsInvocation(container.getClass()))
                return Boolean.FALSE;
            try
            {
                return container.getClass().getMethod("getMethodAccess").invoke(container) == semantic; //$NON-NLS-1$
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                // Штатное поведение: любой вызов-родитель отбрасывает вхождение.
                return Boolean.TRUE;
            }
        }

        private static boolean implementsInvocation(Class<?> type)
        {
            for (Class<?> current = type; current != null; current = current.getSuperclass())
            {
                for (Class<?> face : current.getInterfaces())
                {
                    if (INVOCATION.replace('/', '.').equals(face.getName()) || implementsInvocation(face))
                        return true;
                }
            }
            return false;
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                byte[] transformed = transform(woven.getBytes());
                if (transformed != null)
                    woven.setBytes(transformed);
                System.setProperty(STATUS, transformed != null ? "woven" : "skipped: bytecode differs"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException | LinkageError error)
            {
                System.setProperty(STATUS, "error " + error); //$NON-NLS-1$
            }
        }

        /**
         * Заменяется только первая проверка метода: {@code ALOAD 3; INVOKEINTERFACE eContainer;
         * INSTANCEOF Invocation}. Стек {@code [container]} → {@code [boolean]}, как у {@code instanceof}.
         */
        private static byte[] transform(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null || !METHOD.equals(name) || !METHOD_DESC.equals(descriptor))
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        /** 0 — ждём ALOAD 3, 1 — ждём eContainer, 2 — ждём INSTANCEOF, -1 — первая проверка позади. */
                        private int state;

                        @Override
                        public void visitVarInsn(int opcode, int varIndex)
                        {
                            super.visitVarInsn(opcode, varIndex);
                            if (state >= 0)
                                state = opcode == Opcodes.ALOAD && varIndex == SEMANTIC_SLOT ? 1 : -1;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            if (state >= 0)
                                state = state == 1 && "eContainer".equals(name) //$NON-NLS-1$
                                    && "()Lorg/eclipse/emf/ecore/EObject;".equals(descriptor) ? 2 : -1; //$NON-NLS-1$
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type)
                        {
                            if (state == 2 && opcode == Opcodes.INSTANCEOF && INVOCATION.equals(type))
                            {
                                state = -1;
                                // [container] -> [container, semantic, function] -> [function, container, semantic].
                                super.visitVarInsn(Opcodes.ALOAD, SEMANTIC_SLOT);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PROP_DROP_AS_CALL);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.DUP_X2);
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean"); //$NON-NLS-1$
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                touched[0]++;
                                return;
                            }
                            super.visitTypeInsn(opcode, type);
                            if (state >= 0)
                                state = -1;
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            super.visitInsn(opcode);
                            if (state >= 0)
                                state = -1;
                        }

                        @Override
                        public void visitJumpInsn(int opcode, Label label)
                        {
                            super.visitJumpInsn(opcode, label);
                            if (state >= 0)
                                state = -1;
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor)
                        {
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                            if (state >= 0)
                                state = -1;
                        }

                        @Override
                        public void visitLdcInsn(Object value)
                        {
                            super.visitLdcInsn(value);
                            if (state >= 0)
                                state = -1;
                        }
                    };
                }
            }, 0);
            // Метод изменился — класс остаётся штатным, частичный патч не применяем.
            return touched[0] == 1 ? writer.toByteArray() : null;
        }
    }

    /**
     * 1С:Напарник в режиме «Ручной» (issue #378): классы завершения кода грузятся раньше активации
     * основного бандла, вплетение оттуда не срабатывало (временный лог 21.10.2026: ни одного
     * {@code weave}, {@code instrumentation=false}).
     *
     * <p>{@code CodeCompletionContext.replace} и {@code HintPainter.setHintAt} — ранний выход, если
     * функция {@link #PROP_REPLACE} / {@link #PROP_HINT} вернула {@code false}; функций нет (основной
     * бандл не стартовал) — штатно. {@code CodeCompletionViewModel.documentChanged} и {@code askNew} —
     * выход при {@code !isBalanced()}.
     */
    private static final class NaparnikWeaving implements WeavingHook
    {
        /** Совпадает с {@code NaparnikManualModeHook.PROP_REPLACE}. */
        private static final String PROP_REPLACE = "tormozit.naparnik.replace"; //$NON-NLS-1$
        /** Совпадает с {@code NaparnikManualModeHook.PROP_HINT}. */
        private static final String PROP_HINT = "tormozit.naparnik.hint"; //$NON-NLS-1$
        /** Итог по классам: {@code ViewModel=woven Context=woven HintPainter=woven}, иначе причина. */
        private static final String STATUS = "tormozit.naparnik.patch"; //$NON-NLS-1$

        private static final String VM = "com.e1c.edt.ai.ui.CodeCompletionViewModel"; //$NON-NLS-1$
        private static final String VM_INTERNAL = "com/e1c/edt/ai/ui/CodeCompletionViewModel"; //$NON-NLS-1$
        private static final String CTX = "com.e1c.edt.ai.ui.CodeCompletionContext"; //$NON-NLS-1$
        private static final String PAINTER = "com.e1c.edt.ai.ui.HintPainter"; //$NON-NLS-1$

        private static final String CLIPBOARD_DESC = "Lcom/e1c/edt/ai/ui/IClipboard;"; //$NON-NLS-1$
        private static final String DOCUMENT_CHANGED_DESC = "(Lorg/eclipse/jface/text/DocumentEvent;)V"; //$NON-NLS-1$
        private static final String REPLACE_DESC = "(IILjava/lang/String;)V"; //$NON-NLS-1$
        private static final String SET_HINT_DESC = "(Ljava/lang/String;Ljava/lang/String;I)V"; //$NON-NLS-1$

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING)
                return;
            String name = woven.getClassName();
            String label = VM.equals(name) ? "ViewModel" : CTX.equals(name) ? "Context" //$NON-NLS-1$ //$NON-NLS-2$
                : PAINTER.equals(name) ? "HintPainter" : null; //$NON-NLS-1$
            if (label == null)
                return;
            String result;
            try
            {
                byte[] transformed = VM.equals(name) ? transformViewModel(woven.getBytes())
                    : CTX.equals(name) ? transformPredicate(woven.getBytes(), "replace", REPLACE_DESC, PROP_REPLACE, 3) //$NON-NLS-1$
                    : transformPredicate(woven.getBytes(), "setHintAt", SET_HINT_DESC, PROP_HINT, 1); //$NON-NLS-1$
                if (transformed != null)
                    woven.setBytes(transformed);
                result = transformed != null ? "woven" : "skipped"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException | LinkageError error)
            {
                result = "error(" + error + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            synchronized (NaparnikWeaving.class)
            {
                String previous = System.getProperty(STATUS);
                System.setProperty(STATUS, (previous == null ? "" : previous + " ") + label + "=" + result); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        private static byte[] transformViewModel(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            if (!hasViewModelMembers(reader))
                return null;
            ClassWriter writer = writer(reader);
            boolean[] touched = { false };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null)
                        return null;
                    if (!("documentChanged".equals(name) && DOCUMENT_CHANGED_DESC.equals(descriptor) //$NON-NLS-1$
                        || "askNew".equals(name) && "()V".equals(descriptor))) //$NON-NLS-1$ //$NON-NLS-2$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            Label ok = new Label();
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, VM_INTERNAL, "isBalanced", "()Z", false); //$NON-NLS-1$ //$NON-NLS-2$
                            visitJumpInsn(Opcodes.IFNE, ok);
                            visitInsn(Opcodes.RETURN);
                            visitLabel(ok);
                            touched[0] = true;
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return touched[0] ? writer.toByteArray() : null;
        }

        /** Ранний {@code return}, если функция {@code prop} вернула {@code false} на аргумент {@code textLocal}. */
        private static byte[] transformPredicate(byte[] bytes, String method, String methodDesc, String prop,
            int textLocal)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = writer(reader);
            boolean[] touched = { false };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null || !method.equals(name) || !methodDesc.equals(descriptor))
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            visitLdcInsn(prop);
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            visitInsn(Opcodes.DUP);
                            Label skip = new Label();
                            visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Predicate"); //$NON-NLS-1$
                            visitJumpInsn(Opcodes.IFEQ, skip);
                            visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate"); //$NON-NLS-1$
                            visitVarInsn(Opcodes.ALOAD, textLocal);
                            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Z", true); //$NON-NLS-1$
                            Label ok = new Label();
                            visitJumpInsn(Opcodes.IFNE, ok);
                            visitInsn(Opcodes.RETURN);
                            visitLabel(ok);
                            Label end = new Label();
                            visitJumpInsn(Opcodes.GOTO, end);
                            visitLabel(skip);
                            visitInsn(Opcodes.POP);
                            visitLabel(end);
                            touched[0] = true;
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return touched[0] ? writer.toByteArray() : null;
        }

        private static ClassWriter writer(ClassReader reader)
        {
            return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            {
                @Override
                protected String getCommonSuperClass(String type1, String type2)
                {
                    return "java/lang/Object"; //$NON-NLS-1$
                }
            };
        }

        /** Сверка с версией Напарника, под которую написана вставка: иначе класс остаётся штатным. */
        private static boolean hasViewModelMembers(ClassReader reader)
        {
            boolean[] found = new boolean[6];
            reader.accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                    Object value)
                {
                    if ("clipboard".equals(name) && CLIPBOARD_DESC.equals(descriptor)) //$NON-NLS-1$
                        found[0] = true;
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    if ("isEnabled".equals(name) && "()Z".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        found[1] = true;
                    else if ("isBalanced".equals(name) && "()Z".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        found[2] = true;
                    else if ("reset".equals(name) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        found[3] = true;
                    else if ("documentChanged".equals(name) && DOCUMENT_CHANGED_DESC.equals(descriptor)) //$NON-NLS-1$
                        found[4] = true;
                    else if ("askNew".equals(name) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        found[5] = true;
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (boolean f : found)
                if (!f)
                    return false;
            return true;
        }
    }

    /** Системный журнал инициаторов полного пересчёта и уведомления; без зависимости от Workbench. */
    private static final class CheckRecomputeNotifications implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.checkRecompute.event"; //$NON-NLS-1$
        private static final String PREFIX = "[ComfortCheckDiagnostics] "; //$NON-NLS-1$
        private static final String SCHEDULER = "com.e1c.g5.v8.dt.internal.check.CheckScheduler"; //$NON-NLS-1$
        private static final String METADATA =
            "com.e1c.g5.v8.dt.internal.check.repository.ProjectCheckMetadataManager"; //$NON-NLS-1$
        private static final String DERIVED = "com._1c.g5.v8.internal.derived.DerivedDataManager"; //$NON-NLS-1$
        private static final String PROJECT = "Lorg/eclipse/core/resources/IProject;"; //$NON-NLS-1$
        private static final String TRANSACTION = "Lcom/_1c/g5/v8/bm/core/IBmTransaction;"; //$NON-NLS-1$
        private static final String MONITOR = "Lorg/eclipse/core/runtime/IProgressMonitor;"; //$NON-NLS-1$
        private static final String REQUEST = PROJECT + "Ljava/util/Set;Ljava/util/Collection;"; //$NON-NLS-1$

        private final FrameworkLog log;
        private final String bundleId;
        private final java.util.concurrent.atomic.AtomicLong eventIds = new java.util.concurrent.atomic.AtomicLong();

        private CheckRecomputeNotifications(FrameworkLog log, String bundleId)
        {
            this.log = log;
            this.bundleId = bundleId;
        }

        static void install(BundleContext context)
        {
            var reference = context.getServiceReference(FrameworkLog.class);
            FrameworkLog log = reference != null ? context.getService(reference) : null;
            if (log == null)
            {
                System.setProperty(CALLBACK + ".status", "FrameworkLog unavailable"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            CheckRecomputeNotifications diagnostics =
                new CheckRecomputeNotifications(log, context.getBundle().getSymbolicName());
            System.getProperties().put(CALLBACK, (Consumer<Object[]>) diagnostics::record);
            context.registerService(WeavingHook.class, diagnostics, null);
            diagnostics.write("installed; earlyBundle=" + context.getBundle().getVersion() //$NON-NLS-1$
                + "; waiting for CheckScheduler, ProjectCheckMetadataManager, DerivedDataManager", null); //$NON-NLS-1$
        }

        @Override
        public void weave(WovenClass wovenClass)
        {
            String name = wovenClass.getClassName();
            if (wovenClass.getState() != WovenClass.TRANSFORMING
                || !(SCHEDULER.equals(name) || METADATA.equals(name) || DERIVED.equals(name)))
                return;
            try
            {
                ClassReader reader = new ClassReader(wovenClass.getBytes());
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] matches = {0};
                boolean[] metadataField = {!METADATA.equals(name)};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public FieldVisitor visitField(int access, String field, String descriptor,
                        String signature, Object value)
                    {
                        if ("recomputationTasks".equals(field) && "Ljava/util/Map;".equals(descriptor) //$NON-NLS-1$ //$NON-NLS-2$
                            && (access & Opcodes.ACC_STATIC) == 0)
                            metadataField[0] = true;
                        return super.visitField(access, field, descriptor, signature, value);
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor,
                        String signature, String[] exceptions)
                    {
                        MethodVisitor visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
                        String event = event(name, method, descriptor);
                        if (event == null || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                            return visitor;
                        matches[0]++;
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                EarlyWeaving.emitGetProperty(this, CALLBACK, "java/util/function/Consumer"); //$NON-NLS-1$
                                int count = SCHEDULER.equals(name) ? 4 : 2;
                                visitLdcInsn(count);
                                visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                visitInsn(Opcodes.DUP);
                                visitInsn(Opcodes.ICONST_0);
                                visitLdcInsn(event);
                                visitInsn(Opcodes.AASTORE);
                                for (int i = 1; i < count; i++)
                                {
                                    visitInsn(Opcodes.DUP);
                                    visitLdcInsn(i);
                                    visitVarInsn(Opcodes.ALOAD, SCHEDULER.equals(name) ? i : 0);
                                    if (METADATA.equals(name))
                                        visitFieldInsn(Opcodes.GETFIELD, name.replace('.', '/'), "recomputationTasks", //$NON-NLS-1$
                                            "Ljava/util/Map;"); //$NON-NLS-1$
                                    visitInsn(Opcodes.AASTORE);
                                }
                                visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
                            }
                        };
                    }
                }, 0);
                int expected = SCHEDULER.equals(name) ? 3 : 1;
                String status = "class=" + name + "; bundle=" //$NON-NLS-1$ //$NON-NLS-2$
                    + wovenClass.getBundleWiring().getBundle().getVersion()
                    + "; methods=" + matches[0] + "/" + expected; //$NON-NLS-1$ //$NON-NLS-2$
                if (matches[0] == expected && metadataField[0])
                {
                    wovenClass.setBytes(writer.toByteArray());
                    write("woven; " + status, null); //$NON-NLS-1$
                }
                else
                    write("NOT WOVEN: incompatible methods/fields; " + status, null); //$NON-NLS-1$
            }
            catch (Throwable error)
            {
                write("NOT WOVEN: " + name, error); //$NON-NLS-1$
            }
        }

        private static String event(String type, String method, String descriptor)
        {
            if (SCHEDULER.equals(type))
            {
                if ("scheduleValidation".equals(method) //$NON-NLS-1$
                    && (descriptor.equals("(" + REQUEST + MONITOR + ")V") //$NON-NLS-1$ //$NON-NLS-2$
                        || descriptor.equals("(" + REQUEST + "Z" + TRANSACTION + MONITOR + ")V"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return "validation-request"; //$NON-NLS-1$
                if ("scheduleScopedFullRebuild".equals(method) //$NON-NLS-1$
                    && descriptor.equals("(" + PROJECT + "Ljava/util/Set;" //$NON-NLS-1$ //$NON-NLS-2$
                        + "Lcom/e1c/g5/v8/dt/check/CheckComplexity;" + TRANSACTION + MONITOR + ")V")) //$NON-NLS-1$ //$NON-NLS-2$
                    return "full-check-scope"; //$NON-NLS-1$
            }
            if (METADATA.equals(type) && "scheduleChangedCheckRecomputationIfNecessary".equals(method) //$NON-NLS-1$
                && "()V".equals(descriptor)) //$NON-NLS-1$
                return "check-contributors"; //$NON-NLS-1$
            if (DERIVED.equals(type) && "recomputeAll".equals(method) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                return "derived-recompute-all"; //$NON-NLS-1$
            return null;
        }

        private void record(Object[] event)
        {
            try
            {
                String phase = (String) event[0];
                String text = phase + "; thread=" + Thread.currentThread().getName(); //$NON-NLS-1$
                if ("validation-request".equals(phase) || "full-check-scope".equals(phase)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    text += "; project=" + event[1] + "; checks=" + event[2]; //$NON-NLS-1$ //$NON-NLS-2$
                    if ("validation-request".equals(phase)) //$NON-NLS-1$
                    {
                        Collection<?> objects = (Collection<?>) event[3];
                        text += "; objects=" + (objects == null ? "null" : objects.size()) //$NON-NLS-1$ //$NON-NLS-2$
                            + "; projectScope=" + (objects != null && objects.isEmpty()); //$NON-NLS-1$
                    }
                    else
                        text += "; complexity=" + event[3]; //$NON-NLS-1$
                }
                else if ("check-contributors".equals(phase)) //$NON-NLS-1$
                {
                    text += "; changes="; //$NON-NLS-1$
                    if (event[1] instanceof Map<?, ?> changes)
                    {
                        for (var entry : changes.entrySet())
                        {
                            text += " [" + entry.getKey(); //$NON-NLS-1$
                            for (String field : List.of("added", "updated", "removed")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            {
                                var member = entry.getValue().getClass().getDeclaredField(field);
                                member.setAccessible(true);
                                text += "; " + field + "=" + member.get(entry.getValue()); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                            text += "]"; //$NON-NLS-1$
                        }
                        if (changes.isEmpty())
                            text += "none"; //$NON-NLS-1$
                    }
                    else
                        text += "none"; //$NON-NLS-1$
                }
                else
                    text += "; manager=" + event[1].getClass().getName() + "@" //$NON-NLS-1$ //$NON-NLS-2$
                        + Integer.toHexString(System.identityHashCode(event[1]));
                text += "; eventId=" + eventIds.incrementAndGet(); //$NON-NLS-1$
                Exception stack = new Exception("Diagnostic call stack (not an error)"); //$NON-NLS-1$
                boolean full = "full-check-scope".equals(phase) || "derived-recompute-all".equals(phase); //$NON-NLS-1$ //$NON-NLS-2$
                String reason = full ? notificationReason(event, stack.getStackTrace()) : null;
                if (full)
                    text += "; reason=" + (reason != null ? reason : "manual-request"); //$NON-NLS-1$ //$NON-NLS-2$
                write(text, stack);
                if (full && reason != null)
                {
                    Object[] notification = {PREFIX + text,
                        ("full-check-scope".equals(phase) //$NON-NLS-1$
                            ? "Назначена полная перепроверка проекта " + event[1] //$NON-NLS-1$
                            : "Запрошен полный пересчёт производных данных") + ".\nПричина: " + reason, //$NON-NLS-1$ //$NON-NLS-2$
                        Long.valueOf(System.currentTimeMillis()), event[1]};
                    Consumer<Object[]> consumer;
                    var properties = System.getProperties();
                    synchronized (properties)
                    {
                        @SuppressWarnings("unchecked")
                        Consumer<Object[]> callback = (Consumer<Object[]>) properties.get(CALLBACK + ".notify"); //$NON-NLS-1$
                        consumer = callback;
                        if (consumer == null)
                        {
                            @SuppressWarnings("unchecked")
                            Map<Object, Object[]> pending = (Map<Object, Object[]>) properties.computeIfAbsent(
                                CALLBACK + ".pending", key -> new java.util.LinkedHashMap<>()); //$NON-NLS-1$
                            pending.put(event[1], notification);
                        }
                    }
                    if (consumer != null)
                        consumer.accept(notification);
                }
            }
            catch (Throwable error)
            {
                write("diagnostic callback failed; event=" + event[0], error); //$NON-NLS-1$
            }
        }

        /** Только подтверждённые инициаторы; неизвестную причину не подменяем догадкой. null — ручная команда. */
        private static String notificationReason(Object[] event, StackTraceElement[] stack)
        {
            boolean contributors = false;
            boolean settings = false;
            for (StackTraceElement frame : stack)
            {
                String type = frame.getClassName();
                if (type.equals("tormozit.ComfortCheckRecompute") //$NON-NLS-1$
                    || type.equals("com._1c.g5.v8.dt.internal.ui.validation.development.ScheduleValidationHandler")) //$NON-NLS-1$
                    return null;
                contributors |= type.equals(METADATA)
                    && frame.getMethodName().equals("scheduleChangedCheckRecomputationIfNecessary"); //$NON-NLS-1$
                settings |= type.equals(SCHEDULER) && frame.getMethodName().equals("onChange"); //$NON-NLS-1$
            }
            if (contributors)
            {
                boolean onlyComfort = event.length > 2 && event[2] instanceof Collection<?> checks
                    && !checks.isEmpty() && checks.stream()
                        .allMatch(check -> String.valueOf(check).startsWith("tormozit.comfort.checks:")); //$NON-NLS-1$
                return onlyComfort ? "Обновлены проверки «Комфорт»" : "Изменён состав или версии проверок"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (settings)
                return "Изменены настройки проверок"; //$NON-NLS-1$
            return "Источник запуска не распознан"; //$NON-NLS-1$
        }

        private void write(String message, Throwable stack)
        {
            try
            {
                log.log(new FrameworkLogEntry(bundleId, FrameworkLogEntry.INFO, 0,
                    PREFIX + message, 0, stack, null));
            }
            catch (Throwable ignored)
            {
                // Даже отказ журнала не должен прерывать расчёт EDT или загрузку класса.
            }
        }
    }

    /**
     * Вплетение через {@link WeavingHook}, вызовы наружу — через {@code System.getProperties}, без
     * ветвлений в байткоде (кадры стека чужих методов не пересчитываются).
     *
     * <p>Парсеры: {@link #PROP_BEFORE_CREATE_PARSER} в начале {@code createParser(XtextTokenStream)}.
     * Помощник частичного разбора: результат каждого {@code IParser.parse} в {@code reparse} проходит
     * через {@link #PROP_AFTER_CHUNK_PARSE}.
     */
    private static final class EarlyWeaving
        implements WeavingHook
    {
        static final String PROP_BEFORE_CREATE_PARSER = "tormozit.bslParser.beforeCreateParser"; //$NON-NLS-1$
        static final String PROP_AFTER_CHUNK_PARSE = "tormozit.bslParser.afterChunkParse"; //$NON-NLS-1$
        static final String PROP_SUPPRESSION_COMMENT = "tormozit.bslParser.suppressionComment"; //$NON-NLS-1$

        private static final String BSL_PARSER = "com._1c.g5.v8.dt.bsl.parser.antlr.BslParser"; //$NON-NLS-1$
        private static final String CUSTOM_BSL_PARSER = "com._1c.g5.v8.dt.bsl.parser.antlr.CustomBslParser"; //$NON-NLS-1$
        private static final String HELPER = "com._1c.g5.v8.dt.bsl.parser.antlr.BslPartialParsingHelper"; //$NON-NLS-1$
        /**
         * {@code extractSuppressions(ILeafNode)} отрезает {@code "//"} от текста любого листа {@code SL_COMMENT}
         * и падает ({@code StringIndexOutOfBoundsException}) на скрытой нами односимвольной директиве,
         * например скобке в условии {@code #Если Не (…) Тогда}. Валидация модуля при этом обрывается.
         */
        private static final String SUPPRESSION_PROVIDER = "com._1c.g5.v8.dt.bsl.validation.BslSuppressionProvider"; //$NON-NLS-1$
        /**
         * Провайдер свёрток BSL EDT: в начало {@code isInitiallyCollapsed(EObject)} вставлен вызов
         * функции основного бандла ({@link #PROP_INITIALLY_COLLAPSED}) — «Автоматически сворачиваемые
         * области» (issue #527). Из основного бандла вплетение не успевает: класс грузится раньше
         * его активации. Функции может ещё не быть (основной бандл не стартовал) — тогда вставка
         * ничего не делает.
         */
        private static final String FOLDING_PROVIDER = "com._1c.g5.v8.dt.bsl.ui.folding.BslFoldingRegionProvider"; //$NON-NLS-1$
        static final String PROP_INITIALLY_COLLAPSED = "tormozit.bslFolding.initiallyCollapsed"; //$NON-NLS-1$
        private static final String FOLDING_STRUCTURE = "org.eclipse.xtext.ui.editor.folding.DefaultFoldingStructureProvider"; //$NON-NLS-1$
        static final String PROP_FOLDING_UPDATE = "tormozit.bslFolding.update"; //$NON-NLS-1$
        static final String PROP_LITERAL_FOLDING = "tormozit.bslFolding.literals"; //$NON-NLS-1$
        private static final String FOLDING_PREFERENCES =
            "com._1c.g5.v8.dt.bsl.ui.folding.BslFoldingPreferencePage"; //$NON-NLS-1$
        static final String PROP_FOLDING_PREFERENCES = "tormozit.bslFolding.preferences"; //$NON-NLS-1$
        static final String PROP_INITIAL_FOLDING_PREFERENCES = "tormozit.bslFolding.initialPreferences"; //$NON-NLS-1$
        private static final String INITIALLY_COLLAPSED_DESC = "(Lorg/eclipse/emf/ecore/EObject;)Z"; //$NON-NLS-1$
        /**
         * Область вкладок воркбенча: штатная кнопка списка вкладок видна всегда, а не только при
         * скрытых вкладках (issue #730). SWT показывает её по флагу {@code showChevron}, который
         * {@code setItemSize(GC)} пересчитывает сам; вставка добавляет к условию «папка помечена
         * ключом {@link #TAB_FOLDER_MARK}». Пометку ставит основной бандл
         * ({@code tormozit.WorkbenchTabsHook}), непомеченные папки ведут себя штатно. Из основного
         * бандла вплетение не успевает: SWT загружен задолго до его активации.
         */
        private static final String TAB_FOLDER = "org.eclipse.swt.custom.CTabFolder"; //$NON-NLS-1$
        private static final String TAB_FOLDER_INTERNAL = "org/eclipse/swt/custom/CTabFolder"; //$NON-NLS-1$
        /** Должен совпадать с {@code PartListButton.KEY} в {@code tormozit.WorkbenchTabsHook}. */
        static final String TAB_FOLDER_MARK = "tormozit.workbench.partListButton"; //$NON-NLS-1$
        /**
         * Итог вплетения в {@link #TAB_FOLDER} для основного бандла: строка, начинающаяся с
         * {@code "woven"}, — вставка применена. Свойства нет — класс через хук не проходил.
         */
        static final String PROP_TAB_FOLDER_CHEVRON = "tormozit.tabFolder.chevronPatch"; //$NON-NLS-1$
        /**
         * Список вкладок воркбенча (кнопка в области вкладок): его строка поиска ищет по подстроке
         * и не учитывает имя проекта, которое основной бандл добавляет к подписям строк
         * ({@code tormozit.WorkbenchTabsHook.PartListLabels}). В {@code setMatcherString} текст
         * запроса перед {@code SearchPattern.setPattern} проходит через
         * {@link #PROP_PART_LIST_PATTERN}, в {@code findElement} результат
         * {@code ILabelProvider.getText} — через {@link #PROP_PART_LIST_STOCK_TEXT}. Класс
         * загружается вместе с воркбенчем, раньше активации основного бандла. До неё функции —
         * тождественные (ставит {@link #install}), основной бандл подменяет их своими.
         */
        private static final String PART_LIST_CONTROL =
            "org.eclipse.e4.ui.internal.workbench.renderers.swt.AbstractTableInformationControl"; //$NON-NLS-1$
        /** Должен совпадать с {@code PROP_STOCK_TEXT} в {@code tormozit.WorkbenchTabsHook.PartListLabels}. */
        static final String PROP_PART_LIST_STOCK_TEXT = "tormozit.partList.stockText"; //$NON-NLS-1$
        /** Должен совпадать с {@code PROP_PATTERN} в {@code tormozit.WorkbenchTabsHook.PartListLabels}. */
        static final String PROP_PART_LIST_PATTERN = "tormozit.partList.pattern"; //$NON-NLS-1$
        /** Итог вплетения в {@link #PART_LIST_CONTROL} для основного бандла, как {@link #PROP_TAB_FOLDER_CHEVRON}. */
        static final String PROP_PART_LIST_CONTROL = "tormozit.partList.controlPatch"; //$NON-NLS-1$
        private static final String COMPARISON_EDITOR =
            "com._1c.g5.v8.dt.internal.compare.ui.editor.DtComparisonEditor"; //$NON-NLS-1$
        static final String PROP_MERGE_CONFIRMATION = "tormozit.compare.mergeConfirmation"; //$NON-NLS-1$
        private static final String PROP_AFTER_MERGE = "tormozit.compare.afterMerge"; //$NON-NLS-1$
        /** Задание штатного объединения Git: индексирует изменённые файлы и создаёт коммит слияния. */
        private static final String MERGE_COMMIT_CALLBACK =
            "com._1c.g5.v8.dt.compare.git.merge.AbstractMergePerformer$MergeCallback"; //$NON-NLS-1$
        /** Совпадает с CompareConfigMenuHook.MergePreflight.BEFORE_COMMIT. */
        private static final String PROP_BEFORE_MERGE_COMMIT = "tormozit.compare.beforeMergeCommit"; //$NON-NLS-1$
        private static final String EDITOR_MATCHING = "com._1c.g5.v8.dt.ui.editor.DtEditorMatchingStrategy"; //$NON-NLS-1$
        private static final String GRANULAR_EDITOR = "com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor"; //$NON-NLS-1$
        private static final String PROPERTY_SHEET = "com._1c.g5.v8.dt.md.ui.properties.MdPropertySheetPage"; //$NON-NLS-1$
        private static final String PROPERTY_SHEET_MONITOR = PROPERTY_SHEET + "$Monitor"; //$NON-NLS-1$
        private static final String PROP_SELECTION_INVALIDATE = "tormozit.propertySheet.invalidate"; //$NON-NLS-1$
        private static final String PROP_SELECTION_OPERATION = "tormozit.propertySheet.operation"; //$NON-NLS-1$
        private static final String PROP_SELECTION_RESULT = "tormozit.propertySheet.result"; //$NON-NLS-1$
        private static final String PROP_SELECTION_PATCH = "tormozit.propertySheet.patch"; //$NON-NLS-1$
        private static final String PROP_SELECTION_PROGRESS = "tormozit.propertySheet.progress"; //$NON-NLS-1$
        private static final String PROP_SELECTION_PROGRESS_DONE = "tormozit.propertySheet.progressDone"; //$NON-NLS-1$
        private static final String PROP_SELECTION_MONITOR_PATCH = "tormozit.propertySheet.monitorPatch"; //$NON-NLS-1$
        /** Совпадает с MdEditorTabsHook.PROP_PAGES_CREATED. */
        private static final String PROP_PAGES_CREATED = "tormozit.mdEditor.pagesCreated"; //$NON-NLS-1$
        private static final String TREE_VIEWER = "org.eclipse.jface.viewers.AbstractTreeViewer"; //$NON-NLS-1$
        private static final String FORM_PRESENTATION =
            "com._1c.g5.v8.dt.form.internal.presentation.wysiwyg.FormWysiwygRepresentation"; //$NON-NLS-1$
        private static final String FORM_PAGE = "com._1c.g5.v8.dt.form.ui.editor.FormEditorPage"; //$NON-NLS-1$
        private static final String PROP_FORM_ALLOWED = "tormozit.formPreview.allowed"; //$NON-NLS-1$
        private static final String PROP_FORM_EVENT = "tormozit.formPreview.event"; //$NON-NLS-1$
        private static final String PROP_FORM_PATCH = "tormozit.formPreview.patch."; //$NON-NLS-1$
        private static final String SEARCH_TREE_LAYOUT =
            "com._1c.g5.v8.dt.internal.search.ui.provider.SearchResultTreeLayoutManager"; //$NON-NLS-1$
        private static final String PROP_COMMON_CHILDREN = "tormozit.commonNode.children"; //$NON-NLS-1$
        private static final String PROP_COMMON_SEARCH_NODE = "tormozit.commonNode.searchNode"; //$NON-NLS-1$
        private static final String NAVIGATOR_MAPPER =
            "com._1c.g5.v8.dt.ui.aef.component.NavigatorTreeComponent$NavigatorMapper"; //$NON-NLS-1$
        private static final String PROP_COMMON_VIEW_NODE = "tormozit.commonNode.viewNode"; //$NON-NLS-1$
        /** Совпадает с WorkbenchTabsHook.EditorModelMatching.PROPERTY. */
        static final String PROP_EDITOR_MODEL_EQUALS = "tormozit.editor.modelEquals"; //$NON-NLS-1$
        private static final String MULTILANGUAGE_COMPONENT =
            "com._1c.g5.v8.dt.md.ui.aef.components.AbstractMultilanguageComponent"; //$NON-NLS-1$
        private static final String SINGLE_LANGUAGE_COMPONENT =
            "com._1c.g5.v8.dt.md.ui.aef.components.AbstractSingleLanguageComponent"; //$NON-NLS-1$
        private static final String PROP_LOCAL_STRING_SOURCE = "tormozit.localStringDialog.source"; //$NON-NLS-1$
        private static final String DCS_LOCAL_STRING_EDITOR =
            "com._1c.g5.v8.dt.dcs.ui.valueeditors.LocalStringEditor"; //$NON-NLS-1$
        private static final String DCS_FIELD_EDITING =
            "com._1c.g5.v8.dt.dcs.ui.datasets.fields.DataSetsFieldsEditingSupport"; //$NON-NLS-1$
        private static final String DCS_PARAMETER_EDITING =
            "com._1c.g5.v8.dt.dcs.ui.parameters.ParametersEditingSupport"; //$NON-NLS-1$
        /**
         * Блокировщик документа BSL. Грузится раньше активации основного бандла, а агента
         * перезагрузки классов в EDT нет — вплетение из основного бандла не срабатывало ни разу
         * (временный лог 15.10.2026 10:33: {@code weaveCalls=0}, {@code instrumentation=false}).
         *
         * <p>{@code waitUpdatingDataModel}: ранний выход по {@link #PROP_SKIP_WAIT} — функцию ставит
         * основной бандл ({@code BslXtextDocumentHook}), до него — «внутри расчёта автодополнения».
         *
         * <p>{@code readOnlyForContentAssist}: штатный метод не берёт ни монитора, ни блокировки и
         * считает по модели, которую в это время перестраивают, — гонка, «Переполнение стека».
         * Вплетены монитор ресурса и блокировка чтения ({@link AssistReadLock}) — порядок как у
         * {@code internalReadOnly}, без запуска разбора.
         */
        private static final String DOC_LOCKER =
            "com._1c.g5.v8.dt.bsl.ui.editor.BslXtextDocument$CustomXtextDocumentLocker"; //$NON-NLS-1$
        /** Совпадает с {@code BslXtextDocumentHook.PROP_SKIP_WAIT}. */
        static final String PROP_SKIP_WAIT = "tormozit.bslXtextDoc.skipWaitUpdatingDataModel"; //$NON-NLS-1$
        static final String PROP_ASSIST_READ_BEGIN = "tormozit.bslXtextDoc.assistReadLockBegin"; //$NON-NLS-1$
        static final String PROP_ASSIST_READ_LOCKED = "tormozit.bslXtextDoc.assistReadLocked"; //$NON-NLS-1$
        static final String PROP_ASSIST_READ_END = "tormozit.bslXtextDoc.assistReadLockEnd"; //$NON-NLS-1$
        /** {@code IntSupplier}: глубина вплетённых {@code readOnlyForContentAssist} на текущем потоке. */
        static final String PROP_ASSIST_READ_DEPTH = "tormozit.bslXtextDoc.assistReadDepth"; //$NON-NLS-1$
        /**
         * Необязательный наблюдатель основного бандла {@code BiConsumer<String, Long>} (событие,
         * миллисекунды): ожидание монитора и блокировки, ошибки рефлексии.
         */
        static final String PROP_ASSIST_READ_OBSERVER = "tormozit.bslXtextDoc.assistReadObserver"; //$NON-NLS-1$
        /** Итог вплетения в {@link #DOC_LOCKER}: {@code woven wait=1 read=1} или {@code error …}. */
        static final String PROP_DOC_LOCKER_PATCH = "tormozit.bslXtextDoc.lockerPatch"; //$NON-NLS-1$
        private static final String WAIT_UPDATING_DESC =
            "(Lcom/_1c/g5/v8/dt/bsl/resource/BslResource;I)V"; //$NON-NLS-1$
        private static final String READ_FOR_ASSIST_DESC =
            "(Lorg/eclipse/xtext/util/concurrent/IUnitOfWork;)Ljava/lang/Object;"; //$NON-NLS-1$
        private static final List<String> TARGETS = List.of(BSL_PARSER, CUSTOM_BSL_PARSER, HELPER,
            SUPPRESSION_PROVIDER, FOLDING_PROVIDER, FOLDING_STRUCTURE, FOLDING_PREFERENCES, TAB_FOLDER, PART_LIST_CONTROL, COMPARISON_EDITOR,
            MERGE_COMMIT_CALLBACK, EDITOR_MATCHING, GRANULAR_EDITOR, TREE_VIEWER, SEARCH_TREE_LAYOUT, NAVIGATOR_MAPPER,
            FORM_PRESENTATION, FORM_PAGE, PROPERTY_SHEET, PROPERTY_SHEET_MONITOR,
            MULTILANGUAGE_COMPONENT, SINGLE_LANGUAGE_COMPONENT,
            DCS_LOCAL_STRING_EDITOR, DCS_FIELD_EDITING, DCS_PARAMETER_EDITING, DOC_LOCKER);

        private static final String EXTRACT_SUPPRESSIONS_DESC = "(Lorg/eclipse/xtext/nodemodel/ILeafNode;)Ljava/util/Set;"; //$NON-NLS-1$
        private static final String ILEAF_NODE = "org/eclipse/xtext/nodemodel/ILeafNode"; //$NON-NLS-1$

        private static final String CREATE_PARSER_DESC =
            "(Lorg/eclipse/xtext/parser/antlr/XtextTokenStream;)Lcom/_1c/g5/v8/dt/bsl/parser/antlr/internal/InternalBslParser;"; //$NON-NLS-1$
        private static final String REPARSE_DESC =
            "(Lorg/eclipse/xtext/parser/IParser;Lorg/eclipse/xtext/parser/IParseResult;Lorg/eclipse/xtext/util/ReplaceRegion;)Lorg/eclipse/xtext/parser/IParseResult;"; //$NON-NLS-1$
        private static final String IPARSER = "org/eclipse/xtext/parser/IParser"; //$NON-NLS-1$
        private static final String IPARSE_RESULT = "org/eclipse/xtext/parser/IParseResult"; //$NON-NLS-1$

        /** Свойства ставятся до хука: вплетённый код вызывает их без проверки на {@code null}. */
        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(PROP_FOLDING_UPDATE,
                (BiFunction<Object, Boolean, Boolean>) (provider, initialize) -> Boolean.FALSE);
            System.getProperties().putIfAbsent(PROP_LITERAL_FOLDING,
                (java.util.function.BiConsumer<Object, Object>) (document, acceptor) -> {});
            System.getProperties().putIfAbsent(PROP_FOLDING_PREFERENCES,
                (java.util.function.BiConsumer<Object, Object>) (page, parent) -> {});
            System.getProperties().putIfAbsent(PROP_INITIAL_FOLDING_PREFERENCES,
                (java.util.function.BiConsumer<Object, Object>) (page, parent) -> {});
            System.getProperties().putIfAbsent(PROP_SELECTION_INVALIDATE, (Consumer<Object>) page -> {});
            System.getProperties().putIfAbsent(PROP_SELECTION_OPERATION,
                (BiFunction<Object, Runnable, Runnable>) (page, runnable) -> runnable);
            System.getProperties().putIfAbsent(PROP_SELECTION_RESULT,
                (BiFunction<Object, Runnable, Runnable>) (page, runnable) -> runnable);
            System.getProperties().putIfAbsent(PROP_SELECTION_PROGRESS,
                (BiFunction<Object, Runnable, Runnable>) (monitor, runnable) -> runnable);
            System.getProperties().putIfAbsent(PROP_SELECTION_PROGRESS_DONE,
                (BiFunction<Object, Runnable, Runnable>) (monitor, runnable) -> runnable);
            System.getProperties().putIfAbsent(PROP_FORM_ALLOWED,
                (BiFunction<Object, Boolean, Boolean>) (representation, allowed) -> allowed);
            System.getProperties().putIfAbsent(PROP_FORM_EVENT,
                (BiFunction<Object, String, Object>) (source, phase) -> null);
            System.getProperties().putIfAbsent(PROP_PAGES_CREATED, (Consumer<Object>) editor -> {});
            System.getProperties().putIfAbsent(PROP_COMMON_CHILDREN,
                (BiFunction<Object, Object, Object>) (parent, children) -> children);
            System.getProperties().putIfAbsent(PROP_COMMON_SEARCH_NODE,
                (BiFunction<Object, Object, Object>) (adapter, node) -> node);
            System.getProperties().putIfAbsent(PROP_COMMON_VIEW_NODE,
                (BiFunction<Object, Object, Object>) (model, node) -> node);
            System.getProperties().put(PROP_BEFORE_CREATE_PARSER,
                (Consumer<Object>) stream -> BslParserHook.beforeCreateParser(stream));
            System.getProperties().put(PROP_AFTER_CHUNK_PARSE,
                (Function<Object, Object>) result -> BslParserHook.afterChunkParse(result));
            // Не «//…» бывает только скрытая нами директива: для разбора подавлений — пустой комментарий.
            System.getProperties().put(PROP_SUPPRESSION_COMMENT,
                (Function<Object, Object>) text -> text instanceof String s && !s.startsWith("//") ? "//" : text); //$NON-NLS-1$ //$NON-NLS-2$
            System.getProperties().putIfAbsent(PROP_PART_LIST_STOCK_TEXT, (Function<Object, Object>) text -> text);
            System.getProperties().putIfAbsent(PROP_PART_LIST_PATTERN, (Function<Object, Object>) text -> text);
            System.getProperties().putIfAbsent(PROP_EDITOR_MODEL_EQUALS,
                (BiFunction<Object, Object, Boolean>) java.util.Objects::equals);
            System.getProperties().putIfAbsent(PROP_MERGE_CONFIRMATION, (Function<Object, Object>) editor ->
            {
                try
                {
                    var method = editor.getClass().getDeclaredMethod("openMergeConfirmationDialog"); //$NON-NLS-1$
                    method.setAccessible(true);
                    return method.invoke(editor);
                }
                catch (ReflectiveOperationException e)
                {
                    throw new IllegalStateException(e);
                }
            });
            System.getProperties().putIfAbsent(PROP_AFTER_MERGE, (Function<Object, Object>) editor -> null);
            System.getProperties().putIfAbsent(PROP_BEFORE_MERGE_COMMIT, (Function<Object, Object>) callback -> null);
            System.getProperties().putIfAbsent(PROP_LOCAL_STRING_SOURCE,
                (BiFunction<Object, Object, Object>) (dialog, component) -> dialog);
            // До основного бандла: ожидание бессмысленно только внутри расчёта — разбор ждёт наш монитор.
            System.getProperties().putIfAbsent(PROP_SKIP_WAIT,
                (java.util.function.BooleanSupplier) () -> AssistReadLock.depth() > 0);
            // Вход и выход — только здесь: подмена посреди вызова дала бы выход не из того монитора.
            System.getProperties().put(PROP_ASSIST_READ_BEGIN, (Function<Object, Object>) AssistReadLock::begin);
            System.getProperties().put(PROP_ASSIST_READ_LOCKED, (Runnable) AssistReadLock::locked);
            System.getProperties().put(PROP_ASSIST_READ_END, (java.util.function.Supplier<Object>) AssistReadLock::end);
            System.getProperties().put(PROP_ASSIST_READ_DEPTH, (java.util.function.IntSupplier) AssistReadLock::depth);
            context.registerService(WeavingHook.class, new EarlyWeaving(), null);
        }

        @Override
        public void weave(WovenClass wovenClass)
        {
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            String name = wovenClass.getClassName();
            if (!TARGETS.contains(name))
                return;
            try
            {
                byte[] transformed = transform(name, wovenClass.getBytes());
                if (transformed != null)
                    wovenClass.setBytes(transformed);
            }
            catch (Throwable t)
            {
                if (PROPERTY_SHEET.equals(name))
                    System.setProperty(PROP_SELECTION_PATCH, "error " + t); //$NON-NLS-1$
                if (PROPERTY_SHEET_MONITOR.equals(name))
                    System.setProperty(PROP_SELECTION_MONITOR_PATCH, "error " + t); //$NON-NLS-1$
                // Класс остаётся штатным: лучше без исправления, чем без парсера.
                if (TAB_FOLDER.equals(name))
                    System.setProperty(PROP_TAB_FOLDER_CHEVRON, "error " + t); //$NON-NLS-1$
                if (PART_LIST_CONTROL.equals(name))
                    System.setProperty(PROP_PART_LIST_CONTROL, "error " + t); //$NON-NLS-1$
                if (FORM_PRESENTATION.equals(name) || FORM_PAGE.equals(name))
                    System.setProperty(PROP_FORM_PATCH + name, "error " + t); //$NON-NLS-1$
                if (DOC_LOCKER.equals(name))
                    System.setProperty(PROP_DOC_LOCKER_PATCH, "error " + t); //$NON-NLS-1$
            }
        }

        /** Перехватывает подтверждение до переключения редактора в режим объединения. */
        private static byte[] transformComparisonEditor(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[2];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"startMerge".equals(name) || !"()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String desc,
                            boolean isInterface)
                        {
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(COMPARISON_EDITOR.replace('.', '/'))
                                && "openMergeConfirmationDialog".equals(method) && "()Z".equals(desc)) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                                    "()Ljava/util/Properties;", false);
                                super.visitLdcInsn(PROP_MERGE_CONFIRMATION);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                                touched[0]++;
                            }
                            else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(COMPARISON_EDITOR.replace('.', '/'))
                                && "runMergeProcess".equals(method) && "(Z)V".equals(desc)) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                                // После штатного процесса, до обработки результата и закрытия сравнения.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                                    "()Ljava/util/Properties;", false);
                                super.visitLdcInsn(PROP_AFTER_MERGE);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                                super.visitInsn(Opcodes.POP);
                                touched[1]++;
                            }
                            else
                                super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                        }
                    };
                }
            }, 0);
            System.setProperty("tormozit.compare.mergeConfirmationPatch",
                touched[0] == 1 && touched[1] == 1 ? "woven" : "missing merge call");
            return touched[0] == 1 && touched[1] == 1 ? writer.toByteArray() : null;
        }

        /**
         * Вызов в начале задания, до сбора изменённых файлов в индекс: всё, что основной бандл
         * запишет на диск в этой точке, попадает в коммит слияния.
         */
        private static byte[] transformMergeCommitCallback(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null || !"addChangesToIndexAndMergeAdditionalFiles".equals(name) //$NON-NLS-1$
                        || !"(Lorg/eclipse/core/runtime/IProgressMonitor;)V".equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            super.visitLdcInsn(PROP_BEFORE_MERGE_COMMIT);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                            super.visitInsn(Opcodes.POP);
                            touched[0]++;
                        }
                    };
                }
            }, 0);
            System.setProperty("tormozit.compare.mergeCommitPatch", touched[0] == 1 ? "woven" : "missing method"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return touched[0] == 1 ? writer.toByteArray() : null;
        }

        /**
         * Issue 726: сохраняем проверки входа EDT и меняем только Objects.equals двух моделей.
         * Вставка без ветвлений и локальных переменных сохраняет исходные кадры стека.
         */
        private static byte[] transformEditorMatching(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null || !"matches".equals(name) //$NON-NLS-1$
                        || !"(Lorg/eclipse/ui/IEditorReference;Lorg/eclipse/ui/IEditorInput;)Z".equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface)
                        {
                            if (opcode == Opcodes.INVOKESTATIC && "java/util/Objects".equals(owner) //$NON-NLS-1$
                                && "equals".equals(name) //$NON-NLS-1$
                                && "(Ljava/lang/Object;Ljava/lang/Object;)Z".equals(descriptor)) //$NON-NLS-1$
                            {
                                // [requested, existing, function] -> [function, requested, existing].
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PROP_EDITOR_MODEL_EQUALS);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.DUP_X2);
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean"); //$NON-NLS-1$
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                touched[0]++;
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    };
                }
            }, 0);
            // При изменении структуры метода EDT не применять частичный патч.
            return touched[0] == 1 ? writer.toByteArray() : null;
        }

        /** Сохранённое название и назначение значков вкладкам до showEditorInput и первой отрисовки. */
        private static byte[] transformGranularEditor(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[2];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv != null && "createPages".equals(name) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface)
                            {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                                if (opcode == Opcodes.INVOKESPECIAL && "org/eclipse/ui/forms/editor/FormEditor".equals(owner) //$NON-NLS-1$
                                    && "createPages".equals(name) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                                {
                                    emitGetProperty(this, PROP_PAGES_CREATED, "java/util/function/Consumer"); //$NON-NLS-1$
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                        "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
                                    touched[1]++;
                                }
                            }
                        };
                    if (mv == null || !"init".equals(name) //$NON-NLS-1$
                        || !"(Lorg/eclipse/ui/IEditorSite;Lcom/_1c/g5/v8/dt/ui/editor/input/IDtEditorInput;)V".equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            if (opcode == Opcodes.INVOKESPECIAL && "org/eclipse/ui/forms/editor/FormEditor".equals(owner) //$NON-NLS-1$
                                && "init".equals(name) //$NON-NLS-1$
                                && "(Lorg/eclipse/ui/IEditorSite;Lorg/eclipse/ui/IEditorInput;)V".equals(descriptor)) //$NON-NLS-1$
                            {
                                // Ссылка ещё хранит название из MPart. Позже makeInitialized установит актуальное.
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "org/eclipse/ui/internal/PartSite"); //$NON-NLS-1$
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/eclipse/ui/internal/PartSite", //$NON-NLS-1$
                                    "getPartReference", "()Lorg/eclipse/ui/IWorkbenchPartReference;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/eclipse/ui/IWorkbenchPartReference", //$NON-NLS-1$
                                    "getTitle", "()Ljava/lang/String;", true); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GRANULAR_EDITOR.replace('.', '/'),
                                    "setPartName", "(Ljava/lang/String;)V", false); //$NON-NLS-1$ //$NON-NLS-2$
                                touched[0]++;
                            }
                        }
                    };
                }
            }, 0);
            return touched[0] == 1 && touched[1] == 1 ? writer.toByteArray() : null;
        }

        /** Ворота пересчёта и замеры реальной работы, без новых ветвлений и кадров стека. */
        private static byte[] transformFormPreview(String className, byte[] bytes)
        {
            boolean representation = FORM_PRESENTATION.equals(className);
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[3];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean gate = representation && "isRebuildAllowed".equals(name) //$NON-NLS-1$
                        && "()Z".equals(descriptor); //$NON-NLS-1$
                    boolean timed = representation
                        ? "rebuildInternal".equals(name) && descriptor.equals( //$NON-NLS-1$
                            "(Lcom/_1c/g5/v8/dt/form/model/Form;Lcom/_1c/g5/v8/dt/form/mapping/model/CommandInterfaceMapping;Lcom/_1c/g5/v8/dt/form/model/NativeRenderEvent;Z)V") //$NON-NLS-1$
                        : "createPageControlsInternal".equals(name) //$NON-NLS-1$
                            && "(Lorg/eclipse/ui/forms/IManagedForm;)V".equals(descriptor); //$NON-NLS-1$
                    if (!gate && !timed)
                        return mv;
                    touched[gate ? 0 : 1]++;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        private void callback(String phase)
                        {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            super.visitLdcInsn(PROP_FORM_EVENT);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitLdcInsn(phase);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                            super.visitInsn(Opcodes.POP);
                        }

                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            if (timed)
                                callback(representation ? "render.begin" : "open.begin"); //$NON-NLS-1$ //$NON-NLS-2$
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (gate && opcode == Opcodes.IRETURN)
                            {
                                // Исходный boolean остаётся аргументом: штатное false не превращаем в true.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Z)Ljava/lang/Boolean;", false); //$NON-NLS-1$
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PROP_FORM_ALLOWED);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean"); //$NON-NLS-1$
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Z", false); //$NON-NLS-1$
                            }
                            if (timed && (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW))
                            {
                                callback(representation
                                    ? (opcode == Opcodes.RETURN ? "render.end" : "render.error") //$NON-NLS-1$ //$NON-NLS-2$
                                    : (opcode == Opcodes.RETURN ? "open.end" : "open.error")); //$NON-NLS-1$ //$NON-NLS-2$
                                touched[2]++;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            boolean complete = touched[0] == (representation ? 1 : 0)
                && touched[1] == 1 && touched[2] > 0;
            System.setProperty(PROP_FORM_PATCH + className, complete ? "ok" : "unsupported"); //$NON-NLS-1$ //$NON-NLS-2$
            return complete ? writer.toByteArray() : null;
        }

        /**
         * MdPropertySheetPage: отмена монитора инвалидирует токен, Runnable операции
         * захватывает его, все пять путей передачи результата в UI сохраняют тот же токен.
         * Имя синтетической lambda берётся из bootstrap, а не из её порядкового номера.
         * Все точки подтверждены javap целевого бандла; неполный патч не применяется.
         */
        private static byte[] transformPropertySheet(byte[] bytes)
        {
            String submitDesc = "(Lorg/eclipse/core/resources/IProject;Lorg/eclipse/jface/viewers/ISelection;Ljava/util/List;)V"; //$NON-NLS-1$
            String operationDesc = "(Lorg/eclipse/jface/viewers/ISelection;Ljava/util/List;)V"; //$NON-NLS-1$
            String[] operationName = { null };
            ClassReader reader = new ClassReader(bytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    if (!"submitSelectionOperation".equals(name) || !submitDesc.equals(descriptor)) //$NON-NLS-1$
                        return null;
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor,
                            Handle bootstrap, Object... arguments)
                        {
                            for (Object argument : arguments)
                                if (argument instanceof Handle handle
                                    && handle.getOwner().equals(PROPERTY_SHEET.replace('.', '/'))
                                    && operationDesc.equals(handle.getDesc()))
                                    operationName[0] = handle.getName();
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[3];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean invalidate = "()V".equals(descriptor) //$NON-NLS-1$
                        && ("cancelPreviousSelectionProcess".equals(name) || "dispose".equals(name)); //$NON-NLS-1$ //$NON-NLS-2$
                    boolean submit = "submitSelectionOperation".equals(name) && submitDesc.equals(descriptor); //$NON-NLS-1$
                    boolean operation = name.equals(operationName[0]) && operationDesc.equals(descriptor);
                    if (!invalidate && !submit && !operation)
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        private void callback(String key, boolean wrap)
                        {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            super.visitLdcInsn(key);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST,
                                wrap ? "java/util/function/BiFunction" : "java/util/function/Consumer"); //$NON-NLS-1$ //$NON-NLS-2$
                            if (wrap)
                                super.visitInsn(Opcodes.SWAP);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            if (wrap)
                            {
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Runnable"); //$NON-NLS-1$
                            }
                            else
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
                        }

                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            if (invalidate)
                            {
                                callback(PROP_SELECTION_INVALIDATE, false);
                                touched[0]++;
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor,
                            Handle bootstrap, Object... arguments)
                        {
                            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
                            if (submit && descriptor.endsWith(")Ljava/lang/Runnable;")) //$NON-NLS-1$
                            {
                                callback(PROP_SELECTION_OPERATION, true);
                                touched[1]++;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface)
                        {
                            if (operation && opcode == Opcodes.INVOKEVIRTUAL
                                && "org/eclipse/swt/widgets/Display".equals(owner) //$NON-NLS-1$
                                && "asyncExec".equals(name) && "(Ljava/lang/Runnable;)V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                callback(PROP_SELECTION_RESULT, true);
                                touched[2]++;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    };
                }
            }, 0);
            boolean complete = touched[0] == 2 && touched[1] == 1 && touched[2] == 5;
            System.setProperty(PROP_SELECTION_PATCH, (complete ? "ok " : "unsupported ") //$NON-NLS-1$ //$NON-NLS-2$
                + java.util.Arrays.toString(touched));
            return complete ? writer.toByteArray() : null;
        }

        /** У отменённого Monitor не выполняются отложенные startProgress/stopProgress с layout. */
        private static byte[] transformPropertySheetMonitor(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[2];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    int index = "beginTask".equals(name) && "(Ljava/lang/String;I)V".equals(descriptor) ? 0 //$NON-NLS-1$ //$NON-NLS-2$
                        : "done".equals(name) && "()V".equals(descriptor) ? 1 : -1; //$NON-NLS-1$ //$NON-NLS-2$
                    if (index < 0)
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface)
                        {
                            if (opcode == Opcodes.INVOKEVIRTUAL && "org/eclipse/swt/widgets/Display".equals(owner) //$NON-NLS-1$
                                && "asyncExec".equals(name) && "(Ljava/lang/Runnable;)V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(index == 0 ? PROP_SELECTION_PROGRESS : PROP_SELECTION_PROGRESS_DONE);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Runnable"); //$NON-NLS-1$
                                touched[index]++;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    };
                }
            }, 0);
            boolean complete = touched[0] == 1 && touched[1] == 1;
            System.setProperty(PROP_SELECTION_MONITOR_PATCH, (complete ? "ok " : "unsupported ") //$NON-NLS-1$ //$NON-NLS-2$
                + java.util.Arrays.toString(touched));
            return complete ? writer.toByteArray() : null;
        }

        /** Связывает диалог с открывшим его компонентом до open(), включая наследников. */
        private static byte[] transformLocalStringSource(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if ((access & Opcodes.ACC_STATIC) != 0)
                        return original;
                    return new MethodVisitor(Opcodes.ASM9, original)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String desc,
                            boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                            if (opcode != Opcodes.INVOKEVIRTUAL || !"createMultiLanguageDialog".equals(method) //$NON-NLS-1$
                                || !owner.equals(reader.getClassName())
                                || !desc.endsWith(")Lcom/_1c/g5/v8/dt/ui/dialog/LocalStringDialog;")) //$NON-NLS-1$
                                return;
                            // Стек: dialog -> function, dialog, component -> dialog.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            super.visitLdcInsn(PROP_LOCAL_STRING_SOURCE);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.SWAP);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "com/_1c/g5/v8/dt/ui/dialog/LocalStringDialog"); //$NON-NLS-1$
                            touched[0]++;
                        }
                    };
                }
            }, 0);
            return touched[0] == 1 ? writer.toByteArray() : null;
        }

        /** СКД: редактор ячейки запоминает владельца, диалог получает его через cellEditor. */
        private static byte[] transformDcsLocalStringSource(String className, byte[] bytes)
        {
            boolean dialog = DCS_LOCAL_STRING_EDITOR.equals(className);
            String method = dialog ? "createDialog" : "getCellEditor"; //$NON-NLS-1$ //$NON-NLS-2$
            String desc = dialog
                ? "(Lorg/eclipse/swt/widgets/Shell;Lcom/_1c/g5/v8/dt/core/platform/IV8Project;Lorg/eclipse/emf/ecore/EObject;Ljava/lang/String;ZLjava/util/Map;)Lcom/_1c/g5/v8/dt/ui/dialog/LocalStringDialog;" //$NON-NLS-1$
                : "(Ljava/lang/Object;)Lorg/eclipse/jface/viewers/CellEditor;"; //$NON-NLS-1$
            String result = dialog ? "com/_1c/g5/v8/dt/ui/dialog/LocalStringDialog" //$NON-NLS-1$
                : "org/eclipse/jface/viewers/CellEditor"; //$NON-NLS-1$
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!method.equals(name) || !desc.equals(descriptor))
                        return original;
                    return new MethodVisitor(Opcodes.ASM9, original)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PROP_LOCAL_STRING_SOURCE);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitVarInsn(Opcodes.ALOAD, dialog ? 0 : 1);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, result);
                                touched[0]++;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return touched[0] == 2 ? writer.toByteArray() : null;
        }
        /** Кадры стека остаются исходными: вставки без ветвлений и не меняют высоту стека в точках кадров. */
        static byte[] transform(String className, byte[] bytes)
        {
            if (DOC_LOCKER.equals(className))
                return transformDocLocker(bytes);
            if (DCS_LOCAL_STRING_EDITOR.equals(className) || DCS_FIELD_EDITING.equals(className)
                || DCS_PARAMETER_EDITING.equals(className))
                return transformDcsLocalStringSource(className, bytes);
            if (MULTILANGUAGE_COMPONENT.equals(className) || SINGLE_LANGUAGE_COMPONENT.equals(className))
                return transformLocalStringSource(bytes);
            if (PROPERTY_SHEET.equals(className))
                return transformPropertySheet(bytes);
            if (PROPERTY_SHEET_MONITOR.equals(className))
                return transformPropertySheetMonitor(bytes);
            if (FORM_PRESENTATION.equals(className) || FORM_PAGE.equals(className))
                return transformFormPreview(className, bytes);
            if (TREE_VIEWER.equals(className) || SEARCH_TREE_LAYOUT.equals(className)
                || NAVIGATOR_MAPPER.equals(className))
                return transformCommonTree(className, bytes);
            if (GRANULAR_EDITOR.equals(className))
                return transformGranularEditor(bytes);
            if (EDITOR_MATCHING.equals(className))
                return transformEditorMatching(bytes);
            if (COMPARISON_EDITOR.equals(className))
                return transformComparisonEditor(bytes);
            if (MERGE_COMMIT_CALLBACK.equals(className))
                return transformMergeCommitCallback(bytes);
            if (FOLDING_PROVIDER.equals(className))
                return transformFoldingProvider(bytes);
            if (FOLDING_PREFERENCES.equals(className))
                return transformFoldingPreferences(bytes);
            if (FOLDING_STRUCTURE.equals(className))
                return transformFoldingStructure(bytes);
            if (TAB_FOLDER.equals(className))
                return transformTabFolder(bytes);
            if (PART_LIST_CONTROL.equals(className))
                return transformPartListControl(bytes);
            boolean helper = HELPER.equals(className);
            boolean suppression = SUPPRESSION_PROVIDER.equals(className);
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null)
                        return null;
                    if (suppression)
                    {
                        if (!"extractSuppressions".equals(name) || !EXTRACT_SUPPRESSIONS_DESC.equals(descriptor)) //$NON-NLS-1$
                            return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                boolean isInterface)
                            {
                                super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                                if (opcode == Opcodes.INVOKEINTERFACE && ILEAF_NODE.equals(owner) && "getText".equals(mname)) //$NON-NLS-1$
                                {
                                    emitFilter(this, PROP_SUPPRESSION_COMMENT, "java/lang/String"); //$NON-NLS-1$
                                    touched[0]++;
                                }
                            }
                        };
                    }
                    if (!helper && "createParser".equals(name) && CREATE_PARSER_DESC.equals(descriptor)) //$NON-NLS-1$
                    {
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                emitBeforeCreateParser(this);
                                touched[0]++;
                            }
                        };
                    }
                    if (helper && "reparse".equals(name) && REPARSE_DESC.equals(descriptor)) //$NON-NLS-1$
                    {
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                boolean isInterface)
                            {
                                super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                                if (opcode == Opcodes.INVOKEINTERFACE && IPARSER.equals(owner) && "parse".equals(mname)) //$NON-NLS-1$
                                {
                                    emitResultFilter(this, PROP_AFTER_CHUNK_PARSE);
                                    touched[0]++;
                                }
                            }
                        };
                    }
                    return mv;
                }
            }, 0);
            return touched[0] > 0 ? writer.toByteArray() : null;
        }

        /** Обработка результата без новых ветвлений и локальных переменных. */
        private static byte[] transformCommonTree(String className, byte[] bytes)
        {
            boolean tree = TREE_VIEWER.equals(className);
            boolean mapper = NAVIGATOR_MAPPER.equals(className);
            String method = tree ? "getSortedChildren" : mapper ? "createModelToView" : "insertWorkbenchAdapter"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String descriptor = tree ? "(Ljava/lang/Object;)[Ljava/lang/Object;" //$NON-NLS-1$
                : mapper ? "(Ljava/lang/Object;)Lcom/_1c/g5/aef2/standard/viewModels/TreeItemViewModel;" //$NON-NLS-1$
                : "(Lorg/eclipse/ui/model/IWorkbenchAdapter;Ljava/lang/Object;Lcom/_1c/g5/v8/bm/integration/IBmModel;)Lcom/_1c/g5/v8/dt/internal/search/ui/provider/MatchTreeItem;"; //$NON-NLS-1$
            String property = tree ? PROP_COMMON_CHILDREN : mapper ? PROP_COMMON_VIEW_NODE : PROP_COMMON_SEARCH_NODE;
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] returns = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                    String[] exceptions)
                {
                    MethodVisitor original = super.visitMethod(access, name, desc, signature, exceptions);
                    if (!method.equals(name) || !descriptor.equals(desc))
                        return original;
                    return new MethodVisitor(Opcodes.ASM9, original)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                emitGetProperty(this, property, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", //$NON-NLS-1$
                                    "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitTypeInsn(Opcodes.CHECKCAST, tree ? "[Ljava/lang/Object;" //$NON-NLS-1$
                                    : mapper ? "com/_1c/g5/aef2/standard/viewModels/TreeItemViewModel" //$NON-NLS-1$
                                    : "com/_1c/g5/v8/dt/internal/search/ui/provider/MatchTreeItem"); //$NON-NLS-1$
                                returns[0]++;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            System.setProperty(property + ".patch", "returns=" + returns[0]); //$NON-NLS-1$ //$NON-NLS-2$
            return returns[0] > 0 ? writer.toByteArray() : null;
        }

        /** Проверенный пролог без новых локальных переменных; исходные кадры стека сохраняются. */
        private static byte[] transformFoldingStructure(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"calculateProjectionAnnotationModel".equals(name) || !"(Z)V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            emitGetProperty(this, PROP_FOLDING_UPDATE, "java/util/function/BiFunction"); //$NON-NLS-1$
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ILOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Z)Ljava/lang/Boolean;", false); //$NON-NLS-1$
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", //$NON-NLS-1$
                                "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$
                            super.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Z", false); //$NON-NLS-1$
                            Label original = new Label();
                            super.visitJumpInsn(Opcodes.IFEQ, original);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(original);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            touched[0]++;
                        }
                    };
                }
            }, 0);
            System.setProperty(PROP_FOLDING_UPDATE + ".patch", "methods=" + touched[0]); //$NON-NLS-1$ //$NON-NLS-2$
            return touched[0] == 1 ? writer.toByteArray() : null;
        }

        /** Родитель штатных полей — local 4; добавляем поля до pack первой группы (javap EDT 22). */
        private static byte[] transformFoldingPreferences(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] matches = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"createFieldEditors".equals(name) || !"()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        return method;
                    return new MethodVisitor(Opcodes.ASM9, method)
                    {
                        private String pendingProperty;

                        @Override
                        public void visitLdcInsn(Object value)
                        {
                            super.visitLdcInsn(value);
                            if ("tryExceptEnabled".equals(value)) //$NON-NLS-1$
                                pendingProperty = PROP_FOLDING_PREFERENCES;
                            else if ("foldPreprocInitially".equals(value)) //$NON-NLS-1$
                                pendingProperty = PROP_INITIAL_FOLDING_PREFERENCES;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            if (pendingProperty != null && opcode == Opcodes.INVOKEVIRTUAL && "addField".equals(name) //$NON-NLS-1$
                                && "(Lorg/eclipse/jface/preference/FieldEditor;)V".equals(descriptor)) //$NON-NLS-1$
                            {
                                emitGetProperty(this, pendingProperty, "java/util/function/BiConsumer"); //$NON-NLS-1$
                                pendingProperty = null;
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitVarInsn(Opcodes.ALOAD, 4);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", true); //$NON-NLS-1$
                                matches[0]++;
                            }
                        }
                    };
                }
            }, 0);
            System.setProperty(PROP_FOLDING_PREFERENCES + ".patch", "matches=" + matches[0]); //$NON-NLS-1$ //$NON-NLS-2$
            return matches[0] == 2 ? writer.toByteArray() : null;
        }

        /** Кадры стека для условия начального сворачивания пересчитываются без загрузки EDT-классов. */
        private static byte[] transformFoldingProvider(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            {
                @Override
                protected String getCommonSuperClass(String type1, String type2)
                {
                    return "java/lang/Object"; //$NON-NLS-1$
                }
            };
            boolean[] touched = new boolean[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv != null && "computeCommentFolding".equals(name) //$NON-NLS-1$
                        && ("(Lorg/eclipse/xtext/ui/editor/model/IXtextDocument;"
                            + "Lorg/eclipse/xtext/ui/editor/folding/IFoldingRegionAcceptor;)V").equals(descriptor)) //$NON-NLS-1$
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                emitGetProperty(this, PROP_LITERAL_FOLDING, "java/util/function/BiConsumer"); //$NON-NLS-1$
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitVarInsn(Opcodes.ALOAD, 2);
                                visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", true); //$NON-NLS-1$
                                touched[0] = true;
                            }
                        };
                    if (mv == null || !"isInitiallyCollapsed".equals(name) //$NON-NLS-1$
                        || !INITIALLY_COLLAPSED_DESC.equals(descriptor))
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            emitInitiallyCollapsedPrologue(this);
                            touched[0] = true;
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return touched[0] ? writer.toByteArray() : null;
        }

        /**
         * {@code setMatcherString}: стек перед {@code SearchPattern.setPattern} {@code [matcher, pattern]} →
         * {@code [matcher, (String) pattern.apply(pattern)]}. {@code findElement}: стек после
         * {@code ILabelProvider.getText} {@code [text]} → {@code [(String) stockText.apply(text)]}.
         * Вставка применяется, только если найдены оба места; итог — в {@link #PROP_PART_LIST_CONTROL}.
         */
        private static byte[] transformPartListControl(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] matches = new int[2];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean pattern = "setMatcherString".equals(name); //$NON-NLS-1$
                    if (mv == null || !pattern && !"findElement".equals(name)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                            boolean isInterface)
                        {
                            if (pattern && opcode == Opcodes.INVOKEVIRTUAL && "setPattern".equals(mname) //$NON-NLS-1$
                                && "(Ljava/lang/String;)V".equals(mdesc) && owner.endsWith("/SearchPattern")) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                emitFilter(this, PROP_PART_LIST_PATTERN, "java/lang/String"); //$NON-NLS-1$
                                matches[0]++;
                            }
                            super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                            if (!pattern && opcode == Opcodes.INVOKEINTERFACE && "getText".equals(mname) //$NON-NLS-1$
                                && "org/eclipse/jface/viewers/ILabelProvider".equals(owner)) //$NON-NLS-1$
                            {
                                emitFilter(this, PROP_PART_LIST_STOCK_TEXT, "java/lang/String"); //$NON-NLS-1$
                                matches[1]++;
                            }
                        }
                    };
                }
            }, 0);
            boolean ok = matches[0] == 1 && matches[1] == 1;
            System.setProperty(PROP_PART_LIST_CONTROL, (ok ? "woven " : "skipped ") //$NON-NLS-1$ //$NON-NLS-2$
                + matches[0] + "/" + matches[1]); //$NON-NLS-1$
            return ok ? writer.toByteArray() : null;
        }

        /**
         * {@code setItemSize(GC)}: к каждому значению {@code showChevron} и видимости панели кнопки
         * добавляется {@code | (getData(MARK) != null)}. Когда вкладки не помещаются, SWT вычитает
         * ширину кнопки из области вкладок; у помеченной папки кнопка уже видима и учтена в
         * {@code getRightItemEdge}, поэтому вычитаемое умножается на {@code 1 - помечена}.
         * Вставки без ветвлений и новых локальных переменных — кадры стека остаются исходными.
         *
         * <p>Вставка применяется, только если найдены все места байткода, на которые она рассчитана
         * (три присваивания, три вызова видимости, одно вычитание ширины); иначе класс остаётся
         * штатным. Итог — в {@link #PROP_TAB_FOLDER_CHEVRON}.
         */
        private static byte[] transformTabFolder(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] matches = new int[3];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null || !"setItemSize".equals(name) //$NON-NLS-1$
                        || !"(Lorg/eclipse/swt/graphics/GC;)Z".equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        private boolean chevronSize;

                        /** Стек {@code []} → {@code [помечена ? 1 : 0]}. */
                        private void marked()
                        {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitLdcInsn(TAB_FOLDER_MARK);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAB_FOLDER_INTERNAL, "getData", //$NON-NLS-1$
                                "(Ljava/lang/String;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "nonNull", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Z", false); //$NON-NLS-1$
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fname, String fdesc)
                        {
                            if (opcode == Opcodes.PUTFIELD && TAB_FOLDER_INTERNAL.equals(owner)
                                && "showChevron".equals(fname) && "Z".equals(fdesc)) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                marked();
                                super.visitInsn(Opcodes.IOR);
                                matches[0]++;
                            }
                            super.visitFieldInsn(opcode, owner, fname, fdesc);
                            if (chevronSize && opcode == Opcodes.GETFIELD
                                && "org/eclipse/swt/graphics/Point".equals(owner) //$NON-NLS-1$
                                && "x".equals(fname) && "I".equals(fdesc)) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                super.visitInsn(Opcodes.ICONST_1);
                                marked();
                                super.visitInsn(Opcodes.ISUB);
                                super.visitInsn(Opcodes.IMUL);
                                chevronSize = false;
                                matches[2]++;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                            boolean isInterface)
                        {
                            if ("org/eclipse/swt/widgets/ToolBar".equals(owner)) //$NON-NLS-1$
                            {
                                if ("setVisible".equals(mname) && "(Z)V".equals(mdesc)) //$NON-NLS-1$ //$NON-NLS-2$
                                {
                                    marked();
                                    super.visitInsn(Opcodes.IOR);
                                    matches[1]++;
                                }
                                if ("computeSize".equals(mname) //$NON-NLS-1$
                                    && "(II)Lorg/eclipse/swt/graphics/Point;".equals(mdesc)) //$NON-NLS-1$
                                    chevronSize = true;
                            }
                            super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                        }
                    };
                }
            }, 0);
            boolean ok = matches[0] == 3 && matches[1] == 3 && matches[2] == 1;
            System.setProperty(PROP_TAB_FOLDER_CHEVRON, (ok ? "woven " : "skipped ") //$NON-NLS-1$ //$NON-NLS-2$
                + matches[0] + "/" + matches[1] + "/" + matches[2]); //$NON-NLS-1$ //$NON-NLS-2$
            return ok ? writer.toByteArray() : null;
        }

        /**
         * {@code f = System.getProperties().get(PROP); if (f instanceof Function
         * && Boolean.TRUE.equals(f.apply(element))) return true;}; локальная 1 — {@code element}.
         */
        private static void emitInitiallyCollapsedPrologue(MethodVisitor mv)
        {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                "()Ljava/util/Properties;", false); //$NON-NLS-1$
            mv.visitLdcInsn(PROP_INITIALLY_COLLAPSED);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
            mv.visitInsn(Opcodes.DUP);
            org.objectweb.asm.Label notFunction = new org.objectweb.asm.Label();
            org.objectweb.asm.Label rest = new org.objectweb.asm.Label();
            mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Function"); //$NON-NLS-1$
            mv.visitJumpInsn(Opcodes.IFEQ, notFunction);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "equals", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Z", false); //$NON-NLS-1$
            mv.visitJumpInsn(Opcodes.IFEQ, rest);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(notFunction);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(rest);
        }

        /** {@code ((Consumer) System.getProperties().get(PROP)).accept(stream)}; локальная 1 — поток. */
        private static void emitBeforeCreateParser(MethodVisitor mv)
        {
            emitGetProperty(mv, PROP_BEFORE_CREATE_PARSER, "java/util/function/Consumer"); //$NON-NLS-1$
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
        }

        /** Стек {@code [result]} → {@code [(IParseResult) ((Function) prop).apply(result)]}. */
        private static void emitResultFilter(MethodVisitor mv, String prop)
        {
            emitFilter(mv, prop, IPARSE_RESULT);
        }

        /**
         * {@link #DOC_LOCKER}: ветвление и обработчик исключений — кадры пересчитываются
         * ({@code COMPUTE_FRAMES}, общий предок — {@code Object}, классы EDT не загружаются), как в
         * {@link #transformFoldingProvider}. Функции ставит {@link #install} до хука, поэтому
         * вплетённый код зовёт их без проверок.
         */
        private static byte[] transformDocLocker(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            {
                @Override
                protected String getCommonSuperClass(String type1, String type2)
                {
                    return "java/lang/Object"; //$NON-NLS-1$
                }
            };
            int[] touched = new int[2];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null)
                        return null;
                    if ("waitUpdatingDataModel".equals(name) && WAIT_UPDATING_DESC.equals(descriptor)) //$NON-NLS-1$
                    {
                        touched[0]++;
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                // if (skip.getAsBoolean()) return;
                                emitGetProperty(this, PROP_SKIP_WAIT, "java/util/function/BooleanSupplier"); //$NON-NLS-1$
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BooleanSupplier", //$NON-NLS-1$
                                    "getAsBoolean", "()Z", true); //$NON-NLS-1$ //$NON-NLS-2$
                                Label original = new Label();
                                super.visitJumpInsn(Opcodes.IFEQ, original);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(original);
                            }
                        };
                    }
                    if ("readOnlyForContentAssist".equals(name) && READ_FOR_ASSIST_DESC.equals(descriptor)) //$NON-NLS-1$
                    {
                        touched[1]++;
                        return new AssistReadVisitor(mv);
                    }
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);
            boolean woven = touched[0] == 1 && touched[1] == 1;
            System.setProperty(PROP_DOC_LOCKER_PATCH, (woven ? "woven" : "skipped") //$NON-NLS-1$ //$NON-NLS-2$
                + " wait=" + touched[0] + " read=" + touched[1]); //$NON-NLS-1$ //$NON-NLS-2$
            return woven ? writer.toByteArray() : null;
        }

        /**
         * Тело {@code readOnlyForContentAssist}:
         * <pre>
         * MONITORENTER begin(this); locked();
         * try { тело } — перед каждым ARETURN и в обработчике любого исключения: MONITOREXIT end();
         * </pre>
         * Обработчик — последняя запись таблицы исключений, внутренние обработчики он не заслоняет.
         * Новых методов и локальных переменных нет; объект монитора хранит {@link AssistReadLock}.
         */
        private static final class AssistReadVisitor extends MethodVisitor
        {
            private final Label start = new Label();
            private final Label end = new Label();
            private final Label handler = new Label();

            AssistReadVisitor(MethodVisitor mv)
            {
                super(Opcodes.ASM9, mv);
            }

            @Override
            public void visitCode()
            {
                super.visitCode();
                emitGetProperty(mv, PROP_ASSIST_READ_BEGIN, "java/util/function/Function"); //$NON-NLS-1$
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                mv.visitInsn(Opcodes.MONITORENTER);
                // Защищённый участок — сразу за входом в монитор: выход парный при любом исключении.
                mv.visitLabel(start);
                emitGetProperty(mv, PROP_ASSIST_READ_LOCKED, "java/lang/Runnable"); //$NON-NLS-1$
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            @Override
            public void visitInsn(int opcode)
            {
                if (opcode == Opcodes.ARETURN)
                    emitExit();
                super.visitInsn(opcode);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals)
            {
                mv.visitLabel(end);
                mv.visitTryCatchBlock(start, end, handler, null);
                mv.visitLabel(handler);
                emitExit();
                mv.visitInsn(Opcodes.ATHROW);
                super.visitMaxs(maxStack, maxLocals);
            }

            /** {@code MONITOREXIT end()}; стек не меняет. */
            private void emitExit()
            {
                emitGetProperty(mv, PROP_ASSIST_READ_END, "java/util/function/Supplier"); //$NON-NLS-1$
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", //$NON-NLS-1$ //$NON-NLS-2$
                    "()Ljava/lang/Object;", true); //$NON-NLS-1$
                mv.visitInsn(Opcodes.MONITOREXIT);
            }
        }

        /**
         * Монитор ресурса и блокировка чтения документа на время вплетённого
         * {@code readOnlyForContentAssist}: тот же порядок, что у штатного {@code internalReadOnly}
         * ({@code XtextDocument.getResourceLock()}, затем {@code acquireReadLock}), но без
         * {@code updateContentBeforeRead}, то есть без запуска разбора. Все, кто меняет модель, идут
         * через {@code modify} (монитор, затем запись) и ждут; вложенные {@code readOnly} внутри
         * расчёта входят повторно и разбор не запускают ({@code getReadHoldCount() > 1}).
         *
         * <p>Только рефлексия по именам: типы Xtext не загружаются. Методы не бросают и не возвращают
         * {@code null} — иначе упадёт метод EDT; без нужного метода монитор — сам блокировщик.
         */
        private static final class AssistReadLock
        {
            private static final class Entry
            {
                final Object locker;
                final Object monitor;
                final long startNs = System.nanoTime();
                final Thread thread = Thread.currentThread();
                volatile String phase = "monitor"; //$NON-NLS-1$
                volatile long phaseNs = startNs;
                java.util.concurrent.ScheduledFuture<?> probe;
                boolean readLocked;

                Entry(Object locker, Object monitor)
                {
                    this.locker = locker;
                    this.monitor = monitor;
                }
            }

            private static final ThreadLocal<java.util.ArrayDeque<Entry>> STACK =
                ThreadLocal.withInitial(java.util.ArrayDeque::new);
            private static final java.util.Map<Class<?>,
                java.util.Map<String, java.util.Optional<java.lang.reflect.Method>>> METHODS =
                    new java.util.concurrent.ConcurrentHashMap<>();
            /** С какого ожидания сообщать наблюдателю: порог наблюдения, не задержка. */
            private static final long REPORT_MS = 5;

            // #region agent log
            private static final java.util.concurrent.ScheduledThreadPoolExecutor LOCK_PROBES = createLockProbes();

            private static java.util.concurrent.ScheduledThreadPoolExecutor createLockProbes()
            {
                java.util.concurrent.ScheduledThreadPoolExecutor executor =
                    new java.util.concurrent.ScheduledThreadPoolExecutor(1, action -> {
                        Thread thread = new Thread(action, "Comfort: assist lock diagnostics"); //$NON-NLS-1$
                        thread.setDaemon(true);
                        return thread;
                    });
                executor.setRemoveOnCancelPolicy(true);
                return executor;
            }

            private static void logLockStage(Entry entry, String event, long elapsedNs)
            {
                observe("lockDiag." + event + " request=" + entry.thread.getId() + ":" + entry.startNs //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " requester=" + entry.thread.getName() + " phase=" + entry.phase //$NON-NLS-1$ //$NON-NLS-2$
                    + " monitor=" + entry.monitor.getClass().getName() + "@" //$NON-NLS-1$ //$NON-NLS-2$
                    + Integer.toHexString(System.identityHashCode(entry.monitor))
                    + " elapsedNs=" + elapsedNs, elapsedNs / 1_000_000L); //$NON-NLS-1$
            }

            /** Снимок во время ожидания: ожидающий поток, владельцы и работающие с моделью потоки. */
            private static void probeLock(Entry entry)
            {
                String phase = entry.phase;
                if ("acquired".equals(phase) || "done".equals(phase)) //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                long phaseNs = entry.phaseNs;
                try
                {
                    java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
                    // Владельца ожидаемого монитора JVM сообщает и без дорогого
                    // перечисления всех удерживаемых мониторов/синхронизаторов.
                    java.lang.management.ThreadInfo[] threads = bean.dumpAllThreads(false, false);
                    java.util.Map<Long, java.lang.management.ThreadInfo> byId = new java.util.HashMap<>();
                    for (java.lang.management.ThreadInfo thread : threads)
                        byId.put(thread.getThreadId(), thread);
                    java.util.Set<Long> selected = new java.util.LinkedHashSet<>();
                    long owner = entry.thread.getId();
                    while (owner >= 0 && selected.add(owner))
                    {
                        java.lang.management.ThreadInfo thread = byId.get(owner);
                        owner = thread != null ? thread.getLockOwnerId() : -1;
                    }
                    for (java.lang.management.ThreadInfo thread : threads)
                    {
                        for (java.lang.management.MonitorInfo monitor : thread.getLockedMonitors())
                            if (monitor.getIdentityHashCode() == System.identityHashCode(entry.monitor)
                                && monitor.getClassName().equals(entry.monitor.getClass().getName()))
                                selected.add(thread.getThreadId());
                        for (StackTraceElement frame : thread.getStackTrace())
                        {
                            String name = frame.getClassName();
                            if (name.contains("Validation") || name.contains("Reconciler") //$NON-NLS-1$ //$NON-NLS-2$
                                || name.contains("XtextDocument") || name.contains("DocumentLocker")) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                selected.add(thread.getThreadId());
                                break;
                            }
                        }
                    }
                    StringBuilder text = new StringBuilder("lockDiag.sample request=") //$NON-NLS-1$
                        .append(entry.thread.getId()).append(':').append(entry.startNs)
                        .append(" sampledPhase=").append(phase).append(" currentPhase=").append(entry.phase) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(" phaseWaitNs=").append(System.nanoTime() - phaseNs); //$NON-NLS-1$
                    for (Long id : selected)
                    {
                        java.lang.management.ThreadInfo thread = byId.get(id);
                        if (thread == null)
                            continue;
                        text.append("\nthread=").append(id).append(' ').append(thread.getThreadName()) //$NON-NLS-1$
                            .append(" state=").append(thread.getThreadState()).append(" waitsFor=") //$NON-NLS-1$ //$NON-NLS-2$
                            .append(thread.getLockInfo()).append(" ownerId=").append(thread.getLockOwnerId()) //$NON-NLS-1$
                            .append(" ownerName=").append(thread.getLockOwnerName()); //$NON-NLS-1$
                        for (StackTraceElement frame : thread.getStackTrace())
                            text.append("\n  at ").append(frame); //$NON-NLS-1$
                        for (java.lang.management.MonitorInfo monitor : thread.getLockedMonitors())
                            text.append("\n  holdsMonitor ").append(monitor).append(" at ") //$NON-NLS-1$ //$NON-NLS-2$
                                .append(monitor.getLockedStackFrame());
                        for (java.lang.management.LockInfo lock : thread.getLockedSynchronizers())
                            text.append("\n  holdsSynchronizer ").append(lock); //$NON-NLS-1$
                    }
                    observe(text.toString(), (System.nanoTime() - entry.startNs) / 1_000_000L);
                }
                catch (Throwable t)
                {
                    observe("lockDiag.sampleError " + t, 0); //$NON-NLS-1$
                }
            }
            // #endregion

            private AssistReadLock()
            {
            }

            static Object begin(Object locker)
            {
                Object monitor = locker;
                try
                {
                    java.lang.reflect.Field outer = findField(locker.getClass(), "this$0"); //$NON-NLS-1$
                    Object document = outer != null ? outer.get(locker) : null;
                    Object lock = invoke(document, "getResourceLock"); //$NON-NLS-1$
                    if (lock != null)
                        monitor = lock;
                }
                catch (Throwable t)
                {
                    observe("beginError " + t, 0); //$NON-NLS-1$
                }
                Entry entry = new Entry(locker, monitor);
                STACK.get().push(entry);
                // #region agent log
                logLockStage(entry, "begin", 0); //$NON-NLS-1$
                try
                {
                    entry.probe = LOCK_PROBES.scheduleWithFixedDelay(() -> probeLock(entry),
                        150, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                catch (RuntimeException e)
                {
                    observe("lockDiag.scheduleError " + e, 0); //$NON-NLS-1$
                }
                // #endregion
                return monitor;
            }

            static void locked()
            {
                Entry entry = STACK.get().peek();
                if (entry == null)
                    return;
                // #region agent log
                long monitorAcquiredNs = System.nanoTime();
                entry.phaseNs = monitorAcquiredNs;
                entry.phase = "readLock"; //$NON-NLS-1$
                logLockStage(entry, "monitorAcquired", monitorAcquiredNs - entry.startNs); //$NON-NLS-1$
                long readStartedNs = System.nanoTime();
                // #endregion
                try
                {
                    invoke(entry.locker, "acquireReadLock"); //$NON-NLS-1$
                    entry.readLocked = true;
                }
                catch (Throwable t)
                {
                    observe("readLockError " + t, 0); //$NON-NLS-1$
                }
                // #region agent log
                long readAcquiredNs = System.nanoTime();
                entry.phase = "acquired"; //$NON-NLS-1$
                if (entry.probe != null)
                    entry.probe.cancel(false);
                logLockStage(entry, "readAcquired readLocked=" + entry.readLocked, readAcquiredNs - readStartedNs); //$NON-NLS-1$
                // #endregion
                long waitMs = (System.nanoTime() - entry.startNs) / 1_000_000L;
                if (waitMs >= REPORT_MS || !entry.readLocked)
                    observe("readLock readLocked=" + entry.readLocked + " depth=" + STACK.get().size(), waitMs); //$NON-NLS-1$ //$NON-NLS-2$
            }

            static Object end()
            {
                java.util.ArrayDeque<Entry> stack = STACK.get();
                Entry entry = stack.poll();
                if (stack.isEmpty())
                    STACK.remove();
                if (entry == null)
                    return new Object(); // не бывает: вход и выход парные; MONITOREXIT бросит IllegalMonitorState
                // #region agent log
                entry.phase = "done"; //$NON-NLS-1$
                if (entry.probe != null)
                    entry.probe.cancel(false);
                logLockStage(entry, "end", System.nanoTime() - entry.startNs); //$NON-NLS-1$
                // #endregion
                if (entry.readLocked)
                {
                    try
                    {
                        invoke(entry.locker, "releaseReadLock"); //$NON-NLS-1$
                    }
                    catch (Throwable t)
                    {
                        observe("releaseError " + t, 0); //$NON-NLS-1$
                    }
                }
                return entry.monitor;
            }

            static int depth()
            {
                java.util.ArrayDeque<Entry> stack = STACK.get();
                int depth = stack.size();
                if (depth == 0)
                    STACK.remove();
                return depth;
            }

            @SuppressWarnings("unchecked")
            private static void observe(String event, long ms)
            {
                try
                {
                    Object observer = System.getProperties().get(PROP_ASSIST_READ_OBSERVER);
                    if (observer instanceof java.util.function.BiConsumer<?, ?> consumer)
                        ((java.util.function.BiConsumer<String, Long>) consumer).accept(event, Long.valueOf(ms));
                }
                catch (Throwable ignored)
                {
                }
            }

            /** Метод без параметров в классе объекта или его предке, в том числе закрытый. */
            private static Object invoke(Object target, String name) throws Exception
            {
                if (target == null)
                    return null;
                Class<?> type = target.getClass();
                java.util.Optional<java.lang.reflect.Method> method = METHODS
                    .computeIfAbsent(type, key -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(name, key -> java.util.Optional.ofNullable(findMethod(type, key)));
                return method.isPresent() ? method.get().invoke(target) : null;
            }

            private static java.lang.reflect.Method findMethod(Class<?> type, String name)
            {
                for (Class<?> c = type; c != null; c = c.getSuperclass())
                {
                    try
                    {
                        java.lang.reflect.Method m = c.getDeclaredMethod(name);
                        m.setAccessible(true);
                        return m;
                    }
                    catch (NoSuchMethodException ignored)
                    {
                    }
                    catch (Throwable t)
                    {
                        return null;
                    }
                }
                return null;
            }

            private static java.lang.reflect.Field findField(Class<?> type, String name)
            {
                for (Class<?> c = type; c != null; c = c.getSuperclass())
                {
                    try
                    {
                        java.lang.reflect.Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        return f;
                    }
                    catch (NoSuchFieldException ignored)
                    {
                    }
                    catch (Throwable t)
                    {
                        return null;
                    }
                }
                return null;
            }
        }

        /** Стек {@code [value]} → {@code [(castTo) ((Function) prop).apply(value)]}. */
        private static void emitFilter(MethodVisitor mv, String prop, String castTo)
        {
            emitGetProperty(mv, prop, "java/util/function/Function"); //$NON-NLS-1$
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
            mv.visitTypeInsn(Opcodes.CHECKCAST, castTo);
        }

        private static void emitGetProperty(MethodVisitor mv, String prop, String castTo)
        {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                "()Ljava/util/Properties;", false); //$NON-NLS-1$
            mv.visitLdcInsn(prop);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
            mv.visitTypeInsn(Opcodes.CHECKCAST, castTo);
        }
    }
    /** CastOperatorChecker из com._1c.g5.v8.dt.ql 22.1.0.v202605050943: исключение для NULL (#914). */
    private static final class QueryNullCastWeaving implements WeavingHook
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.internal.ql.typesystem.CastOperatorChecker"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.query.nullCast"; //$NON-NLS-1$
        private static final String CROSS_DESCRIPTOR =
            "(Lcom/_1c/g5/v8/dt/ql/typesystem/TypeDescriptionSource;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/dt/ql/typesystem/TypeDescriptionSource;Lorg/eclipse/emf/ecore/EObject;)Z"; //$NON-NLS-1$
        private static final String CHECK_DESCRIPTOR =
            "(Lorg/eclipse/emf/ecore/EObject;Lcom/_1c/g5/v8/dt/ql/typesystem/IExpressionTypeChecker$CheckResult;" //$NON-NLS-1$
            + "Ljava/util/Map;)Lcom/_1c/g5/v8/dt/ql/typesystem/IExpressionTypeChecker$CheckResult;"; //$NON-NLS-1$
        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK, (java.util.function.Predicate<Object>) object -> false);
            context.registerService(WeavingHook.class, new QueryNullCastWeaving(), null);
        }

        @Override
        public void weave(WovenClass type)
        {
            if (type.getState() != WovenClass.TRANSFORMING || !TARGET.equals(type.getClassName()))
                return;
            try
            {
                byte[] bytes = rewrite(type.getBytes());
                if (bytes != null)
                    type.setBytes(bytes);
            }
            catch (RuntimeException | LinkageError failure)
            {
                // При несовместимой версии EDT остаётся штатная проверка.
            }
        }
        private static byte[] rewrite(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] calls = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"checkType".equals(name) || !CHECK_DESCRIPTOR.equals(descriptor)) //$NON-NLS-1$
                        return method;
                    return new MethodVisitor(Opcodes.ASM9, method)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            if (opcode != Opcodes.INVOKEVIRTUAL || !TARGET.replace('.', '/').equals(owner)
                                || !"isCrossCompoundType".equals(name) || !CROSS_DESCRIPTOR.equals(descriptor)) //$NON-NLS-1$
                                return;
                            // В стеке штатный boolean. Добавляем «или операнд — NULL», без новых ветвей.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", //$NON-NLS-1$
                                "getProperties", "()Ljava/util/Properties;", false); //$NON-NLS-1$ //$NON-NLS-2$
                            super.visitLdcInsn(CALLBACK);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", //$NON-NLS-1$
                                "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$ //$NON-NLS-2$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate"); //$NON-NLS-1$
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", //$NON-NLS-1$
                                "test", "(Ljava/lang/Object;)Z", true); //$NON-NLS-1$ //$NON-NLS-2$
                            super.visitInsn(Opcodes.IOR);
                            calls[0]++;
                        }
                    };
                }
            }, 0);
            return calls[0] == 1 ? writer.toByteArray() : null;
        }
    }
}