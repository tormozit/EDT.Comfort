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
        CheckSchedulerWeaving.install(context);
        AssistLeafLookupWeaving.install(context);
        ApplicationResetDiagnostics.install(context);
        ApplicationRestorationWeaving.install(context);
        QueryNullCastWeaving.install(context);
        FormDataWeaving.install(context);
        TypeComputationWeaving.install(context);
        ArrayElementTypesWeaving.install(context);
        ArrayTypeReferenceWeaving.install(context);
        StringLiteralTypeNamesWeaving.install(context);
        QuickFixPresentationWeaving.install(context);
        DynamicFeatureWeaving.install(context);
        StructureKeyWeaving.install(context);
        SystemEnumEnvironmentWeaving.install(context);
        UnknownMemberWeaving.install(context);
        CheckRecomputeNotifications.install(context);
        EarlyWeaving.install(context);
        context.registerService(WeavingHook.class, new OccurrenceLiteralWeaving(), null);
        context.registerService(WeavingHook.class, new NaparnikWeaving(), null);
        TextSearchInvocationWeaving.install(context);
        ReferenceSearchResultWeaving.install(context);
        DerivedProgressProjectNames.install(context);
        DerivedCheckCancellation.install(context);
        CheckDurations.install(context);
        CheckExecutorEnabledWeaving.install(context);
        EmptyExceptStatementWeaving.install(context);
        P2InstallOperationWeaving.install(context);
        DebugValueEditorDialogWeaving.install(context);
    }

    @Override
    public void stop(BundleContext context)
    {
        System.getProperties().put(CheckRecomputeNotifications.CALLBACK, (Consumer<Object[]>) event -> {});
        System.getProperties().put(CheckRecomputeNotifications.CALLBACK + ".full", //$NON-NLS-1$
            (java.util.function.Predicate<Object[]>) event -> false);
        System.getProperties().put(DerivedProgressProjectNames.CALLBACK,
            (BiFunction<Object, String, String>) (manager, title) -> title);
        System.getProperties().put(CheckDurations.CALLBACK + ".begin", (Consumer<Object>) check -> {}); //$NON-NLS-1$
        System.getProperties().put(CheckDurations.CALLBACK + ".end", (Consumer<Object>) check -> {}); //$NON-NLS-1$
        System.getProperties().put(CheckDurations.CALLBACK + ".monitor", //$NON-NLS-1$
            (BiFunction<Object, Object, Object>) (check, monitor) -> monitor);
    }

    /** Уточнение штатной empty-except-statement без добавления второй проверки (#988). */
    private static final class EmptyExceptStatementWeaving implements WeavingHook
    {
        private static final String TARGET = "com.e1c.v8codestyle.bsl.check.EmptyExceptStatementCheck";
        private static final String CALLBACK = "tormozit.emptyExcept.issue";
        private static final String TITLE =
            "Конструкция \"Попытка…Исключение…КонецПопытки\" имеет пустое исключение";
        private static final String EMIT_DESCRIPTOR =
            "(Lcom/e1c/g5/v8/dt/check/components/BasicCheck$ResultAcceptor;"
            + "Lcom/_1c/g5/v8/dt/bsl/model/TryExceptStatement;Lorg/eclipse/emf/common/util/EList;)V";

        static void install(BundleContext context)
        {
            context.registerService(WeavingHook.class, new EmptyExceptStatementWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                ClassReader reader = new ClassReader(woven.getBytes());
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] patched = { 0, 0 };
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions)
                    {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if ("configureCheck".equals(name))
                            return new MethodVisitor(Opcodes.ASM9, mv)
                            {
                                @Override
                                public void visitFieldInsn(int opcode, String owner, String field, String desc)
                                {
                                    if (opcode == Opcodes.GETSTATIC
                                        && "com/e1c/v8codestyle/bsl/check/Messages".equals(owner)
                                        && ("EmptyExceptStatementCheck_title".equals(field)
                                            || "EmptyExceptStatementCheck_description".equals(field))
                                        && "Ljava/lang/String;".equals(desc))
                                    {
                                        mv.visitLdcInsn(TITLE);
                                        patched[0]++;
                                    }
                                    else
                                        super.visitFieldInsn(opcode, owner, field, desc);
                                }
                            };
                        if (!"lambda$2".equals(name) || !EMIT_DESCRIPTOR.equals(descriptor)
                            || (access & Opcodes.ACC_STATIC) == 0)
                            return mv;
                        patched[1]++;
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                                    "()Ljava/util/Properties;", false);
                                mv.visitLdcInsn(CALLBACK);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                mv.visitInsn(Opcodes.DUP);
                                Label original = new Label();
                                mv.visitJumpInsn(Opcodes.IFNULL, original);
                                mv.visitInsn(Opcodes.ICONST_2);
                                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                                for (int i = 0; i < 2; i++)
                                {
                                    mv.visitInsn(Opcodes.DUP);
                                    mv.visitInsn(Opcodes.ICONST_0 + i);
                                    mv.visitVarInsn(Opcodes.ALOAD, i);
                                    mv.visitInsn(Opcodes.AASTORE);
                                }
                                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer",
                                    "accept", "(Ljava/lang/Object;)V", true);
                                mv.visitInsn(Opcodes.RETURN);
                                mv.visitLabel(original);
                                mv.visitFrame(Opcodes.F_SAME1, 0, null, 1,
                                    new Object[] { "java/util/function/Consumer" });
                                mv.visitInsn(Opcodes.POP);
                            }
                        };
                    }
                }, 0);
                // Публикуем только полную правку известной формы класса.
                if (patched[0] == 2 && patched[1] == 1)
                    woven.setBytes(writer.toByteArray());

            }
            catch (Throwable ignored)
            {
            }
        }
    }

    /**
     * debug.ui 9.3.400.v202605050943: у объектного значения тип ещё не выбран.
     * lambda$0 вызывает getSelectedDelegate().createContent без проверки на null (#986).
     */
    private static final class DebugValueEditorDialogWeaving implements WeavingHook
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.internal.debug.ui.values.modify.BslValueEditorDialog";
        private static final String OWNER = TARGET.replace('.', '/');
        private static final String DELEGATE =
            "com/_1c/g5/v8/dt/internal/debug/ui/values/modify/IBslValueTypeEditor";
        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent("tormozit.debugValueEditor.dialog",
                (Consumer<Object[]>) event -> {});
            context.registerService(WeavingHook.class, new DebugValueEditorDialogWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                ClassReader reader = new ClassReader(woven.getBytes());
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                boolean[] patched = { false };
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions)
                    {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        String phase = "<init>".equals(name) ? "types"
                            : "initializeBounds".equals(name) && "()V".equals(descriptor) ? "default"
                            : "okPressed".equals(name) && "()V".equals(descriptor) ? "ok" : null;
                        if (phase != null)
                        {
                            return new MethodVisitor(Opcodes.ASM9, mv)
                            {
                                private void callback()
                                {
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                                        "()Ljava/util/Properties;", false);
                                    mv.visitLdcInsn("tormozit.debugValueEditor.dialog");
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                    mv.visitInsn(Opcodes.ICONST_2);
                                    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                                    mv.visitInsn(Opcodes.DUP);
                                    mv.visitInsn(Opcodes.ICONST_0);
                                    mv.visitLdcInsn(phase);
                                    mv.visitInsn(Opcodes.AASTORE);
                                    mv.visitInsn(Opcodes.DUP);
                                    mv.visitInsn(Opcodes.ICONST_1);
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitInsn(Opcodes.AASTORE);
                                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer",
                                        "accept", "(Ljava/lang/Object;)V", true);
                                }

                                @Override
                                public void visitCode()
                                {
                                    super.visitCode();
                                    if ("ok".equals(phase))
                                        callback();
                                }

                                @Override
                                public void visitInsn(int opcode)
                                {
                                    if (opcode == Opcodes.RETURN && !"ok".equals(phase))
                                        callback();
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                        if (!"lambda$0".equals(name)
                            || !"(Lorg/eclipse/jface/viewers/SelectionChangedEvent;)V".equals(descriptor))
                            return mv;
                        patched[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "getSelectedDelegate",
                                    "()L" + DELEGATE + ";", false);
                                Label selected = new Label();
                                mv.visitJumpInsn(Opcodes.IFNONNULL, selected);
                                mv.visitInsn(Opcodes.RETURN);
                                mv.visitLabel(selected);
                                mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        };
                    }
                }, 0);
                if (patched[0])
                    woven.setBytes(writer.toByteArray());
            }
            catch (Throwable failure)
            {
                System.setProperty("tormozit.debugValueEditor.weaveFailure", failure.toString());
            }
        }
    }

    /**
     * p2.operations 2.7.200.v20231105-1139 / 2.7.700.v20250720-1530: InstallOperation
     * отбрасывает downgrade до планировщика. Только для группы Комфорта направляем его
     * в штатную ветку dealWithUpdates: remove установленной IU + add выбранной IU.
     * Проверка LOCK_UPDATE и разрешение зависимостей остаются штатными (#976).
     */
    private static final class P2InstallOperationWeaving implements WeavingHook
    {
        private static final String TARGET = "org.eclipse.equinox.p2.operations.InstallOperation";
        private static final String IU = "org/eclipse/equinox/p2/metadata/IInstallableUnit";
        private static final String VERSION = "org/eclipse/equinox/p2/metadata/Version";
        private static final String GROUP = "tormozit.comfort.feature.feature.group";
        private static final String CALLBACK = "tormozit.p2.install.replacementComparison";
        private static final java.nio.file.Path LOG = java.nio.file.Path.of(
            "C:/VC/EDT.Comfort/.tmp/plugin-version-install/runtime.log");
        private final FrameworkLog fallback;

        private P2InstallOperationWeaving(BundleContext context)
        {
            var reference = context.getServiceReference(FrameworkLog.class);
            fallback = reference == null ? null : context.getService(reference);
        }

        static void install(BundleContext context)
        {
            var hook = new P2InstallOperationWeaving(context);
            System.getProperties().put(CALLBACK, (BiFunction<String, Integer, Integer>) hook::replacementComparison);
            context.registerService(WeavingHook.class, hook, null);
            hook.diagnostic("INSTALL pid=" + ProcessHandle.current().pid());
        }

        private int replacementComparison(String id, int comparison)
        {
            int result = GROUP.equals(id) && comparison < 0 ? 1 : comparison;
            diagnostic("COMPARE id=" + id + "; original=" + comparison + "; replacement=" + result);
            return result;
        }

        private synchronized void diagnostic(String text)
        {
            // Временная безусловная диагностика #976; сохранять до подтверждения исправления.
            try
            {
                java.nio.file.Files.createDirectories(LOG.getParent());
                java.nio.file.Files.writeString(LOG, java.time.OffsetDateTime.now() + " " + text + "\r\n",
                    java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            }
            catch (Exception failure)
            {
                System.setProperty(CALLBACK + ".logFailure", failure.toString());
                if (fallback != null)
                    fallback.log(new FrameworkLogEntry("tormozit.comfort.early", FrameworkLogEntry.ERROR,
                        0, "P2 install diagnostic write failed: " + text, 0, failure, null));
            }
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                byte[] bytes = instrument(woven.getBytes());
                if (bytes != null)
                    woven.setBytes(bytes);
                diagnostic("WEAVE " + System.getProperty(CALLBACK + ".status"));
            }
            catch (Throwable failure)
            {
                System.setProperty(CALLBACK + ".status", "FAILED: " + failure);
                diagnostic("WEAVE FAILED " + failure);
            }
        }

        private static byte[] instrument(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] matches = {0, 0, 0, 0}; // Метод, getVersion, compareTo, dealWithUpdates.
            boolean[] compatible = {true};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!name.equals("computeProfileChangeRequest") || !descriptor.equals(
                        "(Lorg/eclipse/core/runtime/MultiStatus;Lorg/eclipse/core/runtime/IProgressMonitor;)V"))
                        return original;
                    matches[0]++;
                    return new MethodVisitor(Opcodes.ASM9, original)
                    {
                        private int lastLoad = -1;
                        private int entryLocal = -1;

                        @Override
                        public void visitVarInsn(int opcode, int local)
                        {
                            lastLoad = opcode == Opcodes.ALOAD ? local : -1;
                            super.visitVarInsn(opcode, local);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String called, String type,
                            boolean isInterface)
                        {
                            if (opcode == Opcodes.INVOKEINTERFACE && owner.equals(IU)
                                && called.equals("getVersion") && type.equals("()L" + VERSION + ";"))
                            {
                                if (matches[1]++ == 0)
                                    entryLocal = lastLoad;
                            }
                            super.visitMethodInsn(opcode, owner, called, type, isInterface);
                            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(VERSION)
                                && called.equals("compareTo") && type.equals("(Ljava/lang/Object;)I"))
                            {
                                matches[2]++;
                                if (matches[1] != 2 || entryLocal < 0)
                                {
                                    compatible[0] = false;
                                    return;
                                }
                                // Стек: comparison -> callback, id, Integer(comparison).
                                // Новых переходов/локальных переменных нет: исходные stack frames сохраняются.
                                EarlyWeaving.emitGetProperty(mv, CALLBACK, "java/util/function/BiFunction");
                                mv.visitInsn(Opcodes.SWAP);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                                    "(I)Ljava/lang/Integer;", false);
                                mv.visitVarInsn(Opcodes.ALOAD, entryLocal);
                                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, IU, "getId", "()Ljava/lang/String;", true);
                                mv.visitInsn(Opcodes.SWAP);
                                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                            }
                            if (owner.equals(TARGET.replace('.', '/')) && called.equals("dealWithUpdates")
                                && type.equals("(Lorg/eclipse/core/runtime/MultiStatus;Lorg/eclipse/equinox/p2/engine/IProfile;L"
                                    + IU + ";L" + IU + ";)V"))
                                matches[3]++;
                            lastLoad = -1;
                        }
                    };
                }
            }, 0);
            boolean applied = compatible[0] && matches[0] == 1 && matches[1] == 2
                && matches[2] == 1 && matches[3] == 2;
            System.setProperty(CALLBACK + ".status", (applied ? "woven " : "NOT WOVEN ")
                + java.util.Arrays.toString(matches));
            return applied ? writer.toByteArray() : null;
        }
    }

    /**
     * check 6.0.100.v202605050943 / 6.0.101.v202607090722: scheduleModelScopedFullRebuild
     * запускает порции последовательно через runAsync(...).get(), удерживая исходную транзакцию.
     * Остановка связанного проекта запрещает открытие транзакции фоновой порции — цикл #972.
     * Прямой Executor сохраняет Future и обработку ошибок, а BmModel.executeReadonlyTask
     * использует уже открытую транзакцию текущего потока (dt.core 26.0.1.v202605050943).
     */
    private static final class CheckSchedulerWeaving implements WeavingHook
    {
        private static final String TARGET = "com.e1c.g5.v8.dt.internal.check.CheckScheduler";
        private static final String EXECUTOR = "tormozit.checkScheduler.fullRebuild.executor";
        private static final String STATUS = EXECUTOR + ".status";
        private static final String METHOD = "scheduleModelScopedFullRebuild";
        private static final String DESCRIPTOR = "(Lorg/eclipse/core/resources/IProject;Ljava/util/Set;"
            + "Lcom/e1c/g5/v8/dt/check/CheckComplexity;Lcom/_1c/g5/v8/bm/core/IBmTransaction;"
            + "Lorg/eclipse/core/runtime/IProgressMonitor;)V";

        static void install(BundleContext context)
        {
            System.getProperties().put(EXECUTOR, (java.util.concurrent.Executor) Runnable::run);
            System.setProperty(STATUS, "installed; waiting for CheckScheduler");
            context.registerService(WeavingHook.class, new CheckSchedulerWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                byte[] bytes = instrument(woven.getBytes());
                if (bytes != null)
                    woven.setBytes(bytes);
            }
            catch (Throwable failure)
            {
                System.setProperty(STATUS, "FAILED: " + failure);
            }
        }

        private static byte[] instrument(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] matches = {0, 0, 0}; // Метод, runAsync, get: только подтверждённая последовательная реализация.
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions)
                {
                    MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor) || (access & Opcodes.ACC_STATIC) != 0)
                        return original;
                    matches[0]++;
                    return new MethodVisitor(Opcodes.ASM9, original)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String called, String type, boolean isInterface)
                        {
                            if (owner.equals("java/util/concurrent/CompletableFuture"))
                            {
                                if (opcode == Opcodes.INVOKESTATIC && called.equals("runAsync")
                                    && type.equals("(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;"))
                                {
                                    matches[1]++;
                                    EarlyWeaving.emitGetProperty(mv, EXECUTOR, "java/util/concurrent/Executor");
                                    super.visitMethodInsn(opcode, owner, called,
                                        "(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
                                        isInterface);
                                    return;
                                }
                                if (opcode == Opcodes.INVOKEVIRTUAL && called.equals("get") && type.equals("()Ljava/lang/Object;"))
                                    matches[2]++;
                            }
                            super.visitMethodInsn(opcode, owner, called, type, isInterface);
                        }
                    };
                }
            }, 0);
            boolean compatible = matches[0] == 1 && matches[1] == 1 && matches[2] == 1;
            System.setProperty(STATUS, (compatible ? "woven; inline pages; " : "NOT WOVEN; incompatible implementation; ")
                + "methods=" + matches[0] + "; runAsync=" + matches[1] + "; get=" + matches[2]);
            return compatible ? writer.toByteArray() : null;
        }
    }

    /** Точная замена линейного CustomLeafNodeFinder; до основного бандла остаётся штатный код. */
    private static final class AssistLeafLookupWeaving implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.bsl.assistLeafLookup"; //$NON-NLS-1$
        private static final String TARGET = "com._1c.g5.v8.dt.bsl.ui.contentassist.antlr." //$NON-NLS-1$
            + "BslParserBasedContentAssistContextFactory$StatefulFactory$CustomLeafNodeFinder"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            context.registerService(WeavingHook.class, new AssistLeafLookupWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            ClassReader reader = new ClassReader(woven.getBytes());
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            AtomicBoolean touched = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions)
                {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"searchIn".equals(name) //$NON-NLS-1$
                        || !"(Lorg/eclipse/xtext/nodemodel/INode;)Lorg/eclipse/xtext/nodemodel/ILeafNode;".equals(descriptor)) //$NON-NLS-1$
                        return visitor;
                    return new MethodVisitor(Opcodes.ASM9, visitor)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            Label stock = new Label();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            super.visitLdcInsn(CALLBACK);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            super.visitInsn(Opcodes.DUP);
                            super.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Function"); //$NON-NLS-1$
                            super.visitJumpInsn(Opcodes.IFEQ, stock);
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.ICONST_3);
                            super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.DUP);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitInsn(Opcodes.DUP);
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, TARGET.replace('.', '/'), "offset", "I"); //$NON-NLS-1$ //$NON-NLS-2$
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitInsn(Opcodes.DUP);
                            super.visitInsn(Opcodes.ICONST_2);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, TARGET.replace('.', '/'), "leading", "Z"); //$NON-NLS-1$ //$NON-NLS-2$
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "org/eclipse/xtext/nodemodel/ILeafNode"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.ARETURN);
                            super.visitLabel(stock);
                            super.visitInsn(Opcodes.POP);
                            touched.set(true);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            if (touched.get())
                woven.setBytes(writer.toByteArray());
            System.getProperties().put(CALLBACK + ".woven", touched.get()); //$NON-NLS-1$
        }
    }

    /**
     * check 6.0.100.v202605050943 {@code CheckExecutor}: собственное время каждой проверки, сумма по
     * всем потокам. Три вызова {@code ICheck.check} заменены вызовом добавленного статического
     * метода, который зовёт тот же {@code check} между {@code .begin} и {@code .end}. Основной бандл
     * читает суммы из {@code .totals} ({проверка → нс}) до и после пересчёта. Монитор проверки
     * перед вызовом проходит через функцию {@code .monitor} (проверка, монитор) → монитор: основной
     * бандл откладывает свои проверки, пока UI ждёт поток проверки; по умолчанию монитор тот же.
     */
    private static final class CheckDurations implements WeavingHook
    {
        static final String CALLBACK = "tormozit.checkDuration"; //$NON-NLS-1$
        private static final String TARGET = "com.e1c.g5.v8.dt.internal.check.CheckExecutor"; //$NON-NLS-1$
        private static final String CHECK = "com/e1c/g5/v8/dt/check/ICheck"; //$NON-NLS-1$
        private static final String CHECK_DESCRIPTOR = "(Ljava/lang/Object;Lcom/e1c/g5/v8/dt/check/ICheckResultAcceptor;" //$NON-NLS-1$
            + "Lcom/e1c/g5/v8/dt/check/ICheckParameters;Ljava/lang/Object;Lorg/eclipse/core/runtime/IProgressMonitor;)V"; //$NON-NLS-1$
        private static final String WRAPPER = "tormozit$timedCheck"; //$NON-NLS-1$
        private static final String WRAPPER_DESCRIPTOR = "(L" + CHECK + ";" + CHECK_DESCRIPTOR.substring(1); //$NON-NLS-1$ //$NON-NLS-2$
        private static final int MAX_DEPTH = 64;

        private final Map<Object, java.util.concurrent.atomic.LongAdder> totals =
            new java.util.concurrent.ConcurrentHashMap<>();
        private final ThreadLocal<Calls> calls = ThreadLocal.withInitial(Calls::new);

        /** Идущие вызовы потока: вложенная проверка вычитается из собственного времени внешней. */
        private static final class Calls
        {
            final Object[] checks = new Object[MAX_DEPTH];
            final long[] starts = new long[MAX_DEPTH];
            final long[] nested = new long[MAX_DEPTH];
            int depth;
        }

        static void install(BundleContext context)
        {
            var hook = new CheckDurations();
            System.getProperties().put(CALLBACK + ".begin", (Consumer<Object>) hook::begin); //$NON-NLS-1$
            System.getProperties().put(CALLBACK + ".end", (Consumer<Object>) hook::end); //$NON-NLS-1$
            System.getProperties().put(CALLBACK + ".totals", //$NON-NLS-1$
                (java.util.function.Supplier<Map<Object, Long>>) hook::totals);
            System.getProperties().put(CALLBACK + ".monitor", //$NON-NLS-1$
                (BiFunction<Object, Object, Object>) (check, monitor) -> monitor);
            context.registerService(WeavingHook.class, hook, null);
        }

        private void begin(Object check)
        {
            Calls stack = calls.get();
            // Переполнение — только от исключений, пропустивших .end: начать заново
            if (stack.depth >= MAX_DEPTH)
                stack.depth = 0;
            stack.checks[stack.depth] = check;
            stack.nested[stack.depth] = 0;
            stack.starts[stack.depth++] = System.nanoTime();
        }

        private void end(Object check)
        {
            long now = System.nanoTime();
            Calls stack = calls.get();
            while (stack.depth > 0)
            {
                int top = --stack.depth;
                Object current = stack.checks[top];
                stack.checks[top] = null;
                if (current != check)
                    continue;
                long elapsed = now - stack.starts[top];
                totals.computeIfAbsent(check, key -> new java.util.concurrent.atomic.LongAdder())
                    .add(elapsed - stack.nested[top]);
                if (top > 0)
                    stack.nested[top - 1] += elapsed;
                return;
            }
        }

        private Map<Object, Long> totals()
        {
            Map<Object, Long> copy = new java.util.HashMap<>();
            totals.forEach((check, nanos) -> copy.put(check, Long.valueOf(nanos.sum())));
            return copy;
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            String owner = TARGET.replace('.', '/');
            try
            {
                var reader = new ClassReader(woven.getBytes());
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] replaced = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions)
                    {
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions))
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String called, String calledName,
                                String calledDescriptor, boolean isInterface)
                            {
                                if (opcode != Opcodes.INVOKEINTERFACE || !CHECK.equals(called)
                                    || !"check".equals(calledName) || !CHECK_DESCRIPTOR.equals(calledDescriptor)) //$NON-NLS-1$
                                {
                                    super.visitMethodInsn(opcode, called, calledName, calledDescriptor, isInterface);
                                    return;
                                }
                                // Стек тот же: проверка и пять аргументов -> пусто
                                replaced[0]++;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, WRAPPER, WRAPPER_DESCRIPTOR, false);
                            }
                        };
                    }

                    @Override
                    public void visitEnd()
                    {
                        if (replaced[0] > 0)
                            emitWrapper(super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                                WRAPPER, WRAPPER_DESCRIPTOR, null, null));
                        super.visitEnd();
                    }
                }, 0);
                if (replaced[0] == 3)
                    woven.setBytes(writer.toByteArray());
                System.setProperty(CALLBACK + ".status", replaced[0] == 3 //$NON-NLS-1$
                    ? "woven" : "skipped: check calls=" + replaced[0]); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException failure)
            {
                System.setProperty(CALLBACK + ".status", "weave failed: " + failure); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /** {@code monitor = .monitor(check, monitor); begin(check); try { check.check(…); } finally { end(check); }} */
        private static void emitWrapper(MethodVisitor mv)
        {
            Label start = new Label();
            Label done = new Label();
            Label failed = new Label();
            mv.visitCode();
            mv.visitTryCatchBlock(start, done, failed, null);
            EarlyWeaving.emitGetProperty(mv, CALLBACK + ".monitor", "java/util/function/BiFunction"); //$NON-NLS-1$ //$NON-NLS-2$
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/eclipse/core/runtime/IProgressMonitor"); //$NON-NLS-1$
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            emitCallback(mv, ".begin"); //$NON-NLS-1$
            mv.visitLabel(start);
            for (int i = 0; i < 6; i++)
                mv.visitVarInsn(Opcodes.ALOAD, i);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, CHECK, "check", CHECK_DESCRIPTOR, true); //$NON-NLS-1$
            mv.visitLabel(done);
            emitCallback(mv, ".end"); //$NON-NLS-1$
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(failed);
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"}); //$NON-NLS-1$
            emitCallback(mv, ".end"); //$NON-NLS-1$
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void emitCallback(MethodVisitor mv, String suffix)
        {
            EarlyWeaving.emitGetProperty(mv, CALLBACK + suffix, "java/util/function/Consumer"); //$NON-NLS-1$
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
        }
    }

    /**
     * «Стоп» у задания производных данных в панели «Состояние» (derived 17.0.200.v202605050943,
     * check 6.0.100.v202605050943). Штатно {@code DerivedDataProgressReporter$ProgressIndicatingJob} —
     * только индикатор: отмену он никому не передаёт, и очередь дорабатывает. Отмена индикатора
     * пользователем (не из самого {@code DerivedDataProgressReporter}) останавливает его сегмент для
     * модели проекта: идущим расчётам проверок отменяется монитор задачи (с ним — транзакция BM), а
     * объекты, дошедшие до {@code compute}, не считаются и уходят в отложенные Комфорта — то же
     * предложение «Запустить», что у шлюза {@code MassChanges}. Монитор пропущенного объекта не
     * отменяется: отменённый {@code WorkerManager$TaskProcessor} не помечает рассчитанным, и конвейер
     * ставит объект в очередь снова. Открытые в редакторах объекты считаются, как в шлюзе.
     * Остановка снимается ручным запуском проекта, полным пересчётом и тишиной {@link #QUIET_END_MS}.
     */
    private static final class DerivedCheckCancellation implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.derivedCancel"; //$NON-NLS-1$
        private static final String REPORTER = "com._1c.g5.v8.internal.derived.DerivedDataProgressReporter"; //$NON-NLS-1$
        private static final String JOB = REPORTER + "$ProgressIndicatingJob"; //$NON-NLS-1$
        private static final List<String> COMPUTERS = List.of(
            "com.e1c.g5.v8.dt.internal.check.derived.LanguageCheckDerivedDataComputer", //$NON-NLS-1$
            "com.e1c.g5.v8.dt.internal.check.derived.ModelCheckDerivedDataComputer"); //$NON-NLS-1$
        private static final String COMPUTE_DESCRIPTOR = "(Lcom/_1c/g5/v8/derived/context/IObjectDerivedDataContext;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/bm/integration/IBmModel;Lorg/eclipse/core/runtime/IProgressMonitor;)V"; //$NON-NLS-1$
        private static final String SCHEDULE_JOB_DESCRIPTOR =
            "(Ljava/lang/String;Lcom/_1c/g5/v8/derived/pipeline/PipelineSegmentStatus;)V"; //$NON-NLS-1$
        /** Пропущенные объекты отдаются в отложенные, когда перестают приходить. */
        private static final long QUIET_FLUSH_MS = 2_000L;
        /**
         * Остановка кончается, когда объекты сегмента долго не приходят: очередь иссякла. Отменённые
         * идущие расчёты возвращаются в очередь не сразу — в первом прогоне через 11 с.
         */
        private static final long QUIET_END_MS = 30_000L;
        private static final String STOP_REASON = "проверка остановлена пользователем"; //$NON-NLS-1$

        static volatile DerivedCheckCancellation instance;

        /** Остановленные сегменты модели и пропущенные объекты; под монитором самой записи. */
        private static final class Stop
        {
            final Object project;
            final java.util.Set<String> segments = java.util.concurrent.ConcurrentHashMap.newKeySet();
            /** «сегмент\nобъект» → {идентификатор объекта, EClass, сегмент, класс контекста}. */
            final Map<String, Object[]> skipped = new java.util.LinkedHashMap<>();
            volatile long last = System.currentTimeMillis();
            int total;

            Stop(Object project)
            {
                this.project = project;
            }
        }

        private final BundleContext context;
        /** Индикатор → {менеджер производных данных, сегмент}. */
        private final Map<Object, Object[]> jobs = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        /** Модель проекта → остановка. */
        private final Map<Object, Stop> stops = new java.util.concurrent.ConcurrentHashMap<>();
        /** Менеджер производных данных → {модель, IProject} (находится при «Стоп»). */
        private final Map<Object, Object[]> models = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        /** Модель → сегмент → мониторы начатых расчётов. Монитор свой у каждой задачи; ссылки слабые. */
        private final Map<Object, Map<String, java.util.Set<Object>>> running =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private final Map<String, java.lang.reflect.Method> methods = new java.util.concurrent.ConcurrentHashMap<>();
        private java.util.concurrent.ScheduledExecutorService timer;

        private DerivedCheckCancellation(BundleContext context)
        {
            this.context = context;
        }

        static void install(BundleContext context)
        {
            var hook = new DerivedCheckCancellation(context);
            System.getProperties().put(CALLBACK + ".job", (Consumer<Object[]>) hook::registerJob); //$NON-NLS-1$
            System.getProperties().put(CALLBACK + ".canceling", (Consumer<Object[]>) hook::canceling); //$NON-NLS-1$
            System.getProperties().put(CALLBACK + ".gate", (Function<Object[], Object>) hook::gate); //$NON-NLS-1$
            instance = hook;
            context.registerService(WeavingHook.class, hook, null);
        }

        /** {индикатор, менеджер, сегмент}. */
        private void registerJob(Object[] args)
        {
            jobs.put(args[0], new Object[] {args[1], args[2]});
        }

        /** {индикатор}: {@code canceling()} индикатора — из панели «Состояние» или из самого отчётчика. */
        private void canceling(Object[] args)
        {
            try
            {
                Object job = args[0];
                String internal = reporterCaller();
                Object[] info = jobs.get(job);
                if (internal != null || info == null)
                    return;
                Object[] target = modelOf(info[0]);
                if (target == null)
                    return;
                Object model = target[0];
                String segment = (String) info[1];
                Stop stop = stops.computeIfAbsent(model, key -> new Stop(target[1]));
                stop.segments.add(segment);
                stop.last = System.currentTimeMillis();
                startTimer();
                List<Object> monitors = new java.util.ArrayList<>();
                Map<String, java.util.Set<Object>> bySegment = running.get(model);
                java.util.Set<Object> started = bySegment != null ? bySegment.get(segment) : null;
                if (started != null)
                    synchronized (started)
                    {
                        monitors.addAll(started);
                        started.clear();
                    }
                for (Object monitor : monitors)
                    cancel(monitor);
            }
            catch (Throwable failure)
            {
            }
        }

        /**
         * {контекст, модель, монитор} на входе {@code compute} вычислителя проверок.
         *
         * @return {@code TRUE} — сегмент остановлен: объект отложен, расчёт пропускается
         */
        private Object gate(Object[] args)
        {
            try
            {
                Object derivedContext = args[0];
                Object model = args[1];
                String segment = (String) call(derivedContext, "getSegmentId"); //$NON-NLS-1$
                Stop stop = stops.get(model);
                if (stop != null && stop.segments.contains(segment))
                {
                    Object objectId = call(derivedContext, "getObjectId"); //$NON-NLS-1$
                    // Открытое в редакторе считается и при остановке — как в шлюзе MassChanges
                    if (objectId != null && !CheckRecomputeNotifications.MassChanges.test(
                        CheckRecomputeNotifications.MassChanges.OPEN, objectId))
                    {
                        synchronized (stop)
                        {
                            stop.skipped.putIfAbsent(segment + '\n' + objectId, new Object[] {objectId,
                                call(derivedContext, "getEClass"), segment, derivedContext.getClass()}); //$NON-NLS-1$
                            stop.total++;
                        }
                        stop.last = System.currentTimeMillis();
                        return Boolean.TRUE;
                    }
                }
                running.computeIfAbsent(model, key -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(segment,
                        key -> java.util.Collections.synchronizedSet(
                            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>())))
                    .add(args[2]);
            }
            catch (Throwable failure)
            {
            }
            return Boolean.FALSE;
        }

        /** Ручной запуск проекта (шлюз {@code MassChanges}): остановка проекта снимается. */
        void resume(Object project)
        {
            if (stops.isEmpty() || project == null)
                return;
            for (var entry : stops.entrySet())
                if (project.equals(entry.getValue().project) && stops.remove(entry.getKey(), entry.getValue()))
                    end(entry.getValue(), "manual"); //$NON-NLS-1$
        }

        /** Полный пересчёт: остановки больше не действуют. */
        void reset(String reason)
        {
            for (var entry : stops.entrySet())
                if (stops.remove(entry.getKey(), entry.getValue()))
                    end(entry.getValue(), reason);
        }

        private void end(Stop stop, String reason)
        {
            flush(stop);
        }

        private synchronized void startTimer()
        {
            if (timer != null)
                return;
            timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable ->
            {
                Thread thread = new Thread(runnable, "Комфорт: остановленные проверки"); //$NON-NLS-1$
                thread.setDaemon(true);
                return thread;
            });
            timer.scheduleWithFixedDelay(this::tick, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
        }

        private void tick()
        {
            try
            {
                long now = System.currentTimeMillis();
                for (var entry : stops.entrySet())
                {
                    Stop stop = entry.getValue();
                    long quiet = now - stop.last;
                    if (quiet >= QUIET_END_MS)
                    {
                        if (stops.remove(entry.getKey(), stop))
                            end(stop, "quiet"); //$NON-NLS-1$
                    }
                    else if (quiet >= QUIET_FLUSH_MS)
                        flush(stop);
                }
            }
            catch (Throwable failure)
            {
            }
        }

        /** Пропущенные объекты — в отложенные основного бандла с предложением «Запустить». */
        private void flush(Stop stop)
        {
            List<Object[]> entries;
            synchronized (stop)
            {
                if (stop.skipped.isEmpty())
                    return;
                entries = List.copyOf(stop.skipped.values());
                stop.skipped.clear();
            }
            CheckRecomputeNotifications notifications = CheckRecomputeNotifications.instance;
            if (notifications != null && stop.project != null)
                notifications.publish(new Object[] {CheckRecomputeNotifications.PREFIX + "user-stop; project=" //$NON-NLS-1$
                    + stop.project + "; contexts=" + entries.size(), STOP_REASON, //$NON-NLS-1$
                    Long.valueOf(System.currentTimeMillis()), stop.project, entries, Boolean.FALSE});
        }

        /** Метод {@code DerivedDataProgressReporter}, из которого идёт отмена, или {@code null} — отменил пользователь. */
        private static String reporterCaller()
        {
            return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> REPORTER.equals(frame.getClassName()))
                .map(StackWalker.StackFrame::getMethodName)
                .findFirst().orElse(null));
        }

        private void cancel(Object monitor) throws ReflectiveOperationException
        {
            java.lang.reflect.Method method = method(monitor.getClass(), "setCanceled", boolean.class); //$NON-NLS-1$
            method.invoke(monitor, Boolean.TRUE);
        }

        private Object call(Object target, String name) throws ReflectiveOperationException
        {
            return method(target.getClass(), name).invoke(target);
        }

        private java.lang.reflect.Method method(Class<?> type, String name, Class<?>... parameters)
            throws ReflectiveOperationException
        {
            String key = type.getName() + '#' + name;
            java.lang.reflect.Method method = methods.get(key);
            if (method == null)
            {
                method = type.getMethod(name, parameters);
                method.setAccessible(true);
                methods.put(key, method);
            }
            return method;
        }

        /** {Модель BM, IProject} проекта менеджера производных данных: {@code getSupplierId} — ID проекта EDT. */
        private Object[] modelOf(Object manager)
        {
            Object[] known = models.get(manager);
            if (known != null)
                return known;
            var projects = context.getServiceReference("com._1c.g5.v8.dt.core.platform.IDtProjectManager"); //$NON-NLS-1$
            var bmModels = context.getServiceReference("com._1c.g5.v8.dt.core.platform.IBmModelManager"); //$NON-NLS-1$
            if (projects == null || bmModels == null)
            {
                return null;
            }
            try
            {
                Object projectService = context.getService(projects);
                Object modelService = context.getService(bmModels);
                String projectId = (String) call(manager, "getSupplierId"); //$NON-NLS-1$
                ClassLoader loader = projectService.getClass().getClassLoader();
                Class<?> projectApi = Class.forName("com._1c.g5.v8.dt.core.platform.IDtProjectManager", false, loader); //$NON-NLS-1$
                Object project = projectApi.getMethod("getDtProject", String.class).invoke(projectService, projectId); //$NON-NLS-1$
                if (project == null)
                    return null;
                Class<?> modelApi = Class.forName("com._1c.g5.v8.dt.core.platform.IBmModelManager", false, //$NON-NLS-1$
                    modelService.getClass().getClassLoader());
                Class<?> dtProject = Class.forName("com._1c.g5.v8.dt.core.platform.IDtProject", false, loader); //$NON-NLS-1$
                Object model = modelApi.getMethod("getModel", dtProject).invoke(modelService, project); //$NON-NLS-1$
                Object workspaceProject = dtProject.getMethod("getWorkspaceProject").invoke(project); //$NON-NLS-1$
                if (model == null)
                    return null;
                Object[] target = {model, workspaceProject};
                models.put(manager, target);
                return target;
            }
            catch (ReflectiveOperationException | RuntimeException failure)
            {
                return null;
            }
            finally
            {
                context.ungetService(projects);
                context.ungetService(bmModels);
            }
        }

        @Override
        public void weave(WovenClass woven)
        {
            String name = woven.getClassName();
            if (woven.getState() != WovenClass.TRANSFORMING
                || !(REPORTER.equals(name) || JOB.equals(name) || COMPUTERS.contains(name)))
                return;
            try
            {
                var reader = new ClassReader(woven.getBytes());
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] points = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String desc,
                        String signature, String[] exceptions)
                    {
                        MethodVisitor original = super.visitMethod(access, method, desc, signature, exceptions);
                        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0)
                            return original;
                        if (REPORTER.equals(name) && "scheduleJob".equals(method) && SCHEDULE_JOB_DESCRIPTOR.equals(desc)) //$NON-NLS-1$
                            return new MethodVisitor(Opcodes.ASM9, original)
                            {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String called, String descriptor,
                                    boolean isInterface)
                                {
                                    if (opcode == Opcodes.INVOKEVIRTUAL && JOB.replace('.', '/').equals(owner)
                                        && "schedule".equals(called) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                                    {
                                        points[0]++;
                                        // Стек: индикатор → индикатор, {индикатор, менеджер, сегмент} → accept.
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitLdcInsn(3);
                                        super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                        super.visitInsn(Opcodes.DUP_X1);
                                        super.visitInsn(Opcodes.SWAP);
                                        super.visitLdcInsn(0);
                                        super.visitInsn(Opcodes.SWAP);
                                        super.visitInsn(Opcodes.AASTORE);
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitLdcInsn(1);
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        super.visitFieldInsn(Opcodes.GETFIELD, REPORTER.replace('.', '/'),
                                            "derivedDataStatusManager", "Lcom/_1c/g5/v8/derived/IDerivedDataManager;"); //$NON-NLS-1$ //$NON-NLS-2$
                                        super.visitInsn(Opcodes.AASTORE);
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitLdcInsn(2);
                                        super.visitVarInsn(Opcodes.ALOAD, 1);
                                        super.visitInsn(Opcodes.AASTORE);
                                        EarlyWeaving.emitGetProperty(this, CALLBACK + ".job", "java/util/function/Consumer"); //$NON-NLS-1$ //$NON-NLS-2$
                                        super.visitInsn(Opcodes.SWAP);
                                        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", //$NON-NLS-1$
                                            "accept", "(Ljava/lang/Object;)V", true); //$NON-NLS-1$ //$NON-NLS-2$
                                    }
                                    super.visitMethodInsn(opcode, owner, called, descriptor, isInterface);
                                }
                            };
                        if (JOB.equals(name) && "canceling".equals(method) && "()V".equals(desc)) //$NON-NLS-1$ //$NON-NLS-2$
                            return new MethodVisitor(Opcodes.ASM9, original)
                            {
                                @Override
                                public void visitCode()
                                {
                                    super.visitCode();
                                    points[0]++;
                                    EarlyWeaving.emitGetProperty(this, CALLBACK + ".canceling", "java/util/function/Consumer"); //$NON-NLS-1$ //$NON-NLS-2$
                                    super.visitLdcInsn(1);
                                    super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitLdcInsn(0);
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitInsn(Opcodes.AASTORE);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", //$NON-NLS-1$
                                        "accept", "(Ljava/lang/Object;)V", true); //$NON-NLS-1$ //$NON-NLS-2$
                                }
                            };
                        if (COMPUTERS.contains(name) && "compute".equals(method) && COMPUTE_DESCRIPTOR.equals(desc)) //$NON-NLS-1$
                            return new MethodVisitor(Opcodes.ASM9, original)
                            {
                                @Override
                                public void visitCode()
                                {
                                    super.visitCode();
                                    points[0]++;
                                    EarlyWeaving.emitGetProperty(this, CALLBACK + ".gate", "java/util/function/Function"); //$NON-NLS-1$ //$NON-NLS-2$
                                    super.visitLdcInsn(3);
                                    super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                    for (int i = 0; i < 3; i++)
                                    {
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitLdcInsn(i);
                                        super.visitVarInsn(Opcodes.ALOAD, i + 1);
                                        super.visitInsn(Opcodes.AASTORE);
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", //$NON-NLS-1$
                                        "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean"); //$NON-NLS-1$
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", //$NON-NLS-1$ //$NON-NLS-2$
                                        "()Z", false); //$NON-NLS-1$
                                    Label proceed = new Label();
                                    super.visitJumpInsn(Opcodes.IFEQ, proceed);
                                    super.visitInsn(Opcodes.RETURN);
                                    super.visitLabel(proceed);
                                    // Локальные — только параметры, как на входе; NOP не даёт кадру совпасть с кадром исходного кода.
                                    super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                    super.visitInsn(Opcodes.NOP);
                                }
                            };
                        return original;
                    }
                }, 0);
                // В каждом классе — ровно одна точка: schedule() в scheduleJob, вход canceling(), вход compute.
                if (points[0] == 1)
                    woven.setBytes(writer.toByteArray());
            }
            catch (Throwable failure)
            {
            }
        }
    }


    /**
     * derived 17.0.200.v202605050943: scheduleJob берёт имя сегмента без проекта.
     * Меняем только подпись создаваемого Job; getSupplierId менеджера — ID проекта EDT.
     */
    private static final class DerivedProgressProjectNames implements WeavingHook
    {
        private static final String TARGET =
            "com._1c.g5.v8.internal.derived.DerivedDataProgressReporter"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.derivedProgress.projectName"; //$NON-NLS-1$
        private static final String PROJECT_MANAGER =
            "com._1c.g5.v8.dt.core.platform.IDtProjectManager"; //$NON-NLS-1$
        private final BundleContext context;

        private DerivedProgressProjectNames(BundleContext context)
        {
            this.context = context;
        }

        static void install(BundleContext context)
        {
            var hook = new DerivedProgressProjectNames(context);
            System.getProperties().put(CALLBACK,
                (BiFunction<Object, String, String>) hook::withProjectName);
            context.registerService(WeavingHook.class, hook, null);
        }

        private String withProjectName(Object manager, String title)
        {
            var reference = context.getServiceReference(PROJECT_MANAGER);
            if (reference == null)
                return title;
            try
            {
                Object service = context.getService(reference);
                if (service == null)
                    return title;
                String projectId = (String) manager.getClass().getMethod("getSupplierId").invoke(manager); //$NON-NLS-1$
                ClassLoader loader = service.getClass().getClassLoader();
                Class<?> api = Class.forName(PROJECT_MANAGER, false, loader);
                Object project = api.getMethod("getDtProject", String.class).invoke(service, projectId); //$NON-NLS-1$
                if (project == null)
                    return title;
                Class<?> projectApi = Class.forName(
                    "com._1c.g5.v8.dt.core.platform.IDtProject", false, loader); //$NON-NLS-1$
                // getDtProjects возвращает активные контексты; внешние библиотеки не считаем проектами РМ.
                Collection<?> projects = (Collection<?>) api.getMethod("getDtProjects").invoke(service); //$NON-NLS-1$
                var workspaceProject = projectApi.getMethod("getWorkspaceProject"); //$NON-NLS-1$
                int activeProjects = 0;
                for (Object candidate : projects)
                {
                    if (workspaceProject.invoke(candidate) != null && ++activeProjects > 1)
                        break;
                }
                if (activeProjects <= 1)
                    return title;
                String name = (String) projectApi.getMethod("getName").invoke(project); //$NON-NLS-1$
                return name == null || name.isBlank() ? title : title + " (" + name + ")"; //$NON-NLS-1$
            }
            catch (ReflectiveOperationException | RuntimeException failure)
            {
                System.setProperty(CALLBACK + ".status", "name failed: " + failure); //$NON-NLS-1$ //$NON-NLS-2$
                return title;
            }
            finally
            {
                context.ungetService(reference);
            }
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                var reader = new ClassReader(woven.getBytes());
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] calls = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions)
                    {
                        var original = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!name.equals("scheduleJob") || !desc.equals( //$NON-NLS-1$
                            "(Ljava/lang/String;Lcom/_1c/g5/v8/derived/pipeline/PipelineSegmentStatus;)V")) //$NON-NLS-1$
                            return original;
                        return new MethodVisitor(Opcodes.ASM9, original)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String method,
                                String descriptor, boolean isInterface)
                            {
                                super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                                if (opcode != Opcodes.INVOKEINTERFACE || !owner.equals(
                                    "com/_1c/g5/v8/internal/derived/IComputationTaskManager") //$NON-NLS-1$
                                    || !method.equals("getSegmentName") //$NON-NLS-1$
                                    || !descriptor.equals("(Ljava/lang/String;)Ljava/lang/String;")) //$NON-NLS-1$
                                    return;
                                // Стек: аргументы конструктора Job, title -> callback, manager, title.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(CALLBACK);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD, TARGET.replace('.', '/'),
                                    "derivedDataStatusManager", "Lcom/_1c/g5/v8/derived/IDerivedDataManager;"); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String"); //$NON-NLS-1$
                                calls[0]++;
                            }
                        };
                    }
                }, 0);
                if (calls[0] == 1)
                    woven.setBytes(writer.toByteArray());
                System.setProperty(CALLBACK + ".status", calls[0] == 1 //$NON-NLS-1$
                    ? "woven" : "skipped: name calls=" + calls[0]); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException failure)
            {
                System.setProperty(CALLBACK + ".status", "weave failed: " + failure); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * EDT core 26.0.1.v202605050943: DtRestorationJob.attemptToRestore останавливает все проекты
     * через DELETE, затем запускает CLEAN_IMPORT. Applications 6.0.0.v202605050943 воспринимает
     * DELETE как настоящее удаление приложений. При восстановлении оставляем только cleanup:
     * фоновой задаче сразу передаётся false, поэтому после перезапуска модели она не удалит привязки.
     */
    private static final class ApplicationRestorationWeaving implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.applicationRestoration.shouldDelete";
        private static final String TARGET = "com.e1c.g5.dt.internal.applications.ApplicationManager";
        private static final String CLEANER = TARGET.replace('.', '/') + "$AsynchronousApplicationCleaner";
        private static final String CLEANUP_DESC = "(Lorg/eclipse/core/resources/IProject;Z)V";
        private static final StackWalker WALKER = StackWalker.getInstance();

        static void install(BundleContext context)
        {
            System.getProperties().put(CALLBACK, (Function<Boolean, Boolean>) ApplicationRestorationWeaving::shouldDelete);
            context.registerService(WeavingHook.class, new ApplicationRestorationWeaving(), null);
        }

        private static boolean shouldDelete(boolean requested)
        {
            if (!requested)
                return false;
            // stopProject -> stopContext -> onProjectStorageClosing выполняются синхронно.
            // Проверяем инициатора до Job.schedule(): в фоновой задаче этого стека уже нет.
            boolean restoration = WALKER.walk(frames -> frames.anyMatch(frame -> frame.getClassName()
                .equals("com._1c.g5.v8.dt.core.lifecycle.DtRestorationJob")
                && frame.getMethodName().equals("attemptToRestore")));
            if (restoration)
                diagnostic("preserve applications during DtRestorationJob; shouldDelete=false", null);
            return !restoration;
        }

        private static void diagnostic(String text, Throwable failure)
        {
            Object callback = System.getProperties().get(ApplicationResetDiagnostics.CALLBACK);
            if (callback instanceof Consumer<?> consumer)
            {
                @SuppressWarnings("unchecked")
                Consumer<Object[]> sink = (Consumer<Object[]>) consumer;
                sink.accept(new Object[] {"APPLICATION RESTORATION " + text, failure});
            }
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                var reader = new ClassReader(woven.getBytes());
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] calls = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions)
                    {
                        var original = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!name.equals("onProjectStorageClosing") || !desc.equals(
                            "(Lcom/_1c/g5/v8/dt/core/lifecycle/ProjectContext;)V"))
                            return original;
                        return new MethodVisitor(Opcodes.ASM9, original)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String method,
                                String descriptor, boolean isInterface)
                            {
                                if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(CLEANER)
                                    && method.equals("cleanupProjectApplications") && descriptor.equals(CLEANUP_DESC))
                                {
                                    // Стек cleaner, project, boolean; остальные аргументы сохраняются.
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                                        "(Z)Ljava/lang/Boolean;", false);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                                        "()Ljava/util/Properties;", false);
                                    super.visitLdcInsn(CALLBACK);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function");
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                                        "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                                    calls[0]++;
                                }
                                super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                            }
                        };
                    }
                }, 0);
                if (calls[0] == 2)
                    woven.setBytes(writer.toByteArray());
                diagnostic("weave cleanup calls=" + calls[0] + "/2; "
                    + (calls[0] == 2 ? "OK" : "NOT APPLIED"), null);
            }
            catch (Throwable failure)
            {
                diagnostic("NOT APPLIED", failure);
            }
        }
    }

    /** Временная диагностика потери привязок; включена до активации проектов, переживает перезапуск. */
    private static final class ApplicationResetDiagnostics implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.applicationReset.diagnostic";
        private static final String CORE = "com._1c.g5.v8.dt.";
        private static final String APPS = "com.e1c.g5.dt.internal.applications.ApplicationManager";
        private static final String BASES = CORE + "internal.platform.services.core.infobases.";
        private static final String LIFECYCLE = CORE + "internal.lifecycle.";
        private static final String CRITICAL =
            "com._1c.g5.v8.bm.core.internal.lifecycle.CriticalErrorHandler";
        private static final String RECOVERY = CORE + "internal.core.platform.RecoveryOrchestrator";
        // Согласованная диагностика сохраняется между запусками основного бандла.
        private static final java.nio.file.Path FILE = java.nio.file.Path.of(
            "C:/VC/EDT.Comfort/.tmp/application-reset/diagnostic.log");
        private final FrameworkLog fallback;

        private ApplicationResetDiagnostics(BundleContext context)
        {
            var reference = context.getServiceReference(FrameworkLog.class);
            fallback = reference == null ? null : context.getService(reference);
        }

        static void install(BundleContext context)
        {
            var diagnostics = new ApplicationResetDiagnostics(context);
            System.getProperties().put(CALLBACK, (Consumer<Object[]>) diagnostics::record);
            diagnostics.write("SESSION pid=" + ProcessHandle.current().pid()
                + " workspace=" + System.getProperty("osgi.instance.area"));
            context.registerService(WeavingHook.class, diagnostics, null);
            diagnostics.snapshot();
        }

        private void snapshot()
        {
            try
            {
                String area = System.getProperty("osgi.instance.area");
                var projects = java.nio.file.Path.of(java.net.URI.create(area)).resolve(
                    ".metadata/.plugins/org.eclipse.core.resources/.projects");
                try (var directories = java.nio.file.Files.list(projects))
                {
                    for (var project : directories.toList())
                    {
                        var settings = project.resolve("com._1c.g5.v8.dt.platform.services.core");
                        if (!java.nio.file.Files.isDirectory(settings))
                            continue;
                        try (var files = java.nio.file.Files.walk(settings))
                        {
                            for (var file : files.filter(path -> path.getFileName().toString()
                                .equals("AssociationData.properties")).toList())
                                write("SNAPSHOT " + projects.relativize(file) + " "
                                    + java.nio.file.Files.readString(file).replace('\r', ' ').replace('\n', ' '));
                        }
                    }
                }
            }
            catch (Exception failure)
            {
                write("SNAPSHOT FAILED " + failure);
            }
        }

        private void record(Object[] args)
        {
            try
            {
                String event = String.valueOf(args[0]);
                var text = new StringBuilder(event);
                for (int i = 1; i < args.length; i++)
                {
                    text.append(" arg").append(i).append('=').append(args[i]);
                    if (args[i] instanceof Throwable error)
                    {
                        var stack = new java.io.StringWriter();
                        error.printStackTrace(new java.io.PrintWriter(stack));
                        text.append('\n').append(stack);
                    }
                }
                // Стек только редких запросов очистки/записи, а не каждого открытия проекта.
                if (event.endsWith(" enter") && (event.contains("cleanupProjectApplications")
                    || event.contains("dissociate") || event.contains("storeAssociationProperties")
                    || event.contains("onProjectStorageClosing") || event.contains("reportFatalError")))
                    for (var frame : Thread.currentThread().getStackTrace())
                        if (!frame.getClassName().equals(Thread.class.getName())
                            && !frame.getClassName().equals(ApplicationResetDiagnostics.class.getName()))
                            text.append("\n  at ").append(frame);
                write(text.toString());
            }
            catch (Throwable failure)
            {
                write("RECORD FAILED " + failure);
            }
        }

        private synchronized void write(String text)
        {
            try
            {
                java.nio.file.Files.createDirectories(FILE.getParent());
                // Два файла по ~4 МБ: не обнулять на старте, не накапливать журнал без границ.
                if (java.nio.file.Files.exists(FILE) && java.nio.file.Files.size(FILE) >= 4 * 1024 * 1024)
                    java.nio.file.Files.move(FILE, FILE.resolveSibling("diagnostic.previous.log"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                java.nio.file.Files.writeString(FILE, java.time.OffsetDateTime.now() + " ["
                    + Thread.currentThread().getName() + "] " + text + "\r\n",
                    java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            }
            catch (Exception failure)
            {
                if (fallback != null)
                    try
                    {
                        fallback.log(new FrameworkLogEntry("tormozit.comfort.early", FrameworkLogEntry.ERROR,
                            0, "Application reset diagnostic write failed: " + text, 0, failure, null));
                    }
                    catch (Throwable ignored) { /* Отказ диагностики не прерывает EDT. */ }
            }
        }

        @Override
        public void weave(WovenClass woven)
        {
            String owner = woven.getClassName();
            if (woven.getState() != WovenClass.TRANSFORMING || !(owner.equals(APPS)
                || owner.equals(APPS + "$AsynchronousApplicationCleaner")
                || owner.equals(BASES + "InfobaseAssociationManager")
                || owner.equals(BASES + "InfobaseProjectSettingsStore")
                || owner.equals(LIFECYCLE + "ServicesOrchestrator")
                || owner.equals(CORE + "lifecycle.LifecycleException")
                || owner.equals(CRITICAL) || owner.equals(RECOVERY)
                || owner.equals(LIFECYCLE + "LifecyclePlugin")))
                return;
            try
            {
                var reader = new ClassReader(woven.getBytes());
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] matches = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions)
                    {
                        var original = super.visitMethod(access, name, desc, signature, exceptions);
                        String project = "Lorg/eclipse/core/resources/IProject;";
                        String context = "L" + CORE.replace('.', '/') + "core/lifecycle/ProjectContext;";
                        String association = "L" + BASES.replace('.', '/').replace("/internal/", "/")
                            + "InfobaseAssociationContext;";
                        String lifecycleContext = "Lcom/_1c/g5/v8/dt/lifecycle/ILifecycleContext;";
                        boolean selected = owner.equals(APPS) && name.equals("onProjectStorageClosing")
                            && desc.equals("(" + context + ")V")
                            || owner.equals(APPS + "$AsynchronousApplicationCleaner")
                                && name.equals("cleanupProjectApplications") && desc.equals("(" + project + "Z)V")
                            || owner.equals(BASES + "InfobaseAssociationManager") && name.equals("dissociate")
                                && desc.equals("(" + project
                                    + "Lcom/_1c/g5/v8/dt/platform/services/model/InfobaseReference;" + association + ")V")
                            || owner.equals(BASES + "InfobaseProjectSettingsStore")
                                && name.equals("storeAssociationProperties")
                                && desc.equals("(" + project + "Ljava/util/Map;" + association + ")V")
                            || owner.equals(LIFECYCLE + "ServicesOrchestrator")
                                && (name.equals("startServices") || name.equals("stopServices"))
                                && desc.equals("(" + lifecycleContext + "Lorg/eclipse/core/runtime/IProgressMonitor;)V")
                            || owner.equals(LIFECYCLE + "LifecyclePlugin") && name.equals("createErrorStatus")
                                && desc.equals("(Ljava/lang/String;Ljava/lang/Throwable;)Lorg/eclipse/core/runtime/IStatus;")
                            || owner.equals(CORE + "lifecycle.LifecycleException") && name.equals("<init>")
                                && desc.equals("(Ljava/lang/String;Ljava/lang/Throwable;)V")
                            || owner.equals(CRITICAL) && name.equals("handleCriticalError")
                                && desc.equals("(Ljava/lang/Exception;)V")
                            || owner.equals(RECOVERY) && name.equals("reportFatalError") && desc.equals("()V");
                        if (!selected)
                            return original;
                        matches[0]++;
                        return new MethodVisitor(Opcodes.ASM9, original)
                        {
                            private void emit(String phase)
                            {
                                var types = org.objectweb.asm.Type.getArgumentTypes(desc);
                                boolean stop = name.equals("onProjectStorageClosing");
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                                    "()Ljava/util/Properties;", false);
                                super.visitLdcInsn(CALLBACK);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                super.visitLdcInsn(types.length + (stop ? 2 : 1));
                                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitLdcInsn(owner.substring(owner.lastIndexOf('.') + 1) + "." + name + phase);
                                super.visitInsn(Opcodes.AASTORE);
                                int local = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                                for (int i = 0; i < types.length; i++)
                                {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitLdcInsn(i + 1);
                                    super.visitVarInsn(types[i].getOpcode(Opcodes.ILOAD), local);
                                    if (types[i].getSort() == org.objectweb.asm.Type.BOOLEAN)
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                                            "(Z)Ljava/lang/Boolean;", false);
                                    super.visitInsn(Opcodes.AASTORE);
                                    local += types[i].getSize();
                                }
                                if (stop)
                                {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitLdcInsn(types.length + 1);
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        CORE.replace('.', '/') + "core/lifecycle/ProjectContext", "getStopType",
                                        "()Lcom/_1c/g5/v8/dt/core/lifecycle/ProjectStopType;", false);
                                    super.visitInsn(Opcodes.AASTORE);
                                }
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer",
                                    "accept", "(Ljava/lang/Object;)V", true);
                            }

                            @Override public void visitCode()
                            {
                                super.visitCode();
                                emit(" enter");
                            }

                            @Override public void visitInsn(int opcode)
                            {
                                if ((opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN)
                                    && !name.equals("createErrorStatus") && !name.equals("<init>")
                                    && !name.equals("handleCriticalError"))
                                    emit(" returned");
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }, 0);
                int expected = owner.equals(LIFECYCLE + "ServicesOrchestrator") ? 2 : 1;
                if (matches[0] == expected)
                    woven.setBytes(writer.toByteArray());
                write("WEAVE " + owner + " methods=" + matches[0] + "/" + expected
                    + (matches[0] == expected ? " OK" : " NOT APPLIED"));
            }
            catch (Throwable failure)
            {
                record(new Object[] {"WEAVE FAILED " + owner, failure});
            }
        }
    }

    /**
     * EDT 22.0.0.v202605050943, com._1c.g5.v8.dt.bsl.ui:
     * BslReferenceSearchResult.accept убирает повторы только по URI источника.
     * Полнотекстовые вхождения одного комментария различаются selection, а не URI.
     * Основной бандл ставит функцию ключа; до его старта сохраняется штатный URI.
     */
    private static final class ReferenceSearchResultWeaving implements WeavingHook
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.bsl.ui.editor.findref.BslReferenceSearchResult"; //$NON-NLS-1$
        private static final String REFERENCE = "org/eclipse/xtext/resource/IReferenceDescription"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.referenceSearch.occurrenceKey"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK, (Function<Object[], Object>) args -> args[1]);
            System.setProperty(CALLBACK + ".status", "registered"); //$NON-NLS-1$ //$NON-NLS-2$
            context.registerService(WeavingHook.class, new ReferenceSearchResultWeaving(), null);
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
                System.setProperty(CALLBACK + ".status", transformed == null ? "bytecode differs" : "woven"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            catch (RuntimeException | LinkageError failure)
            {
                System.setProperty(CALLBACK + ".status", "error " + failure); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private static byte[] transform(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] replacements = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"accept".equals(name) || !("(L" + REFERENCE + ";)V").equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        return delegate;
                    return new MethodVisitor(Opcodes.ASM9, delegate)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                            String desc, boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                            if (opcode != Opcodes.INVOKEINTERFACE || !REFERENCE.equals(owner)
                                || !"getSourceEObjectUri".equals(method) //$NON-NLS-1$
                                || !"()Lorg/eclipse/emf/common/util/URI;".equals(desc)) //$NON-NLS-1$
                                return;
                            // Стек: Set, URI → Set, Object[]{reference, URI} → Set, key.
                            super.visitInsn(Opcodes.ICONST_2);
                            super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.DUP_X1);
                            super.visitInsn(Opcodes.SWAP);
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitInsn(Opcodes.SWAP);
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitInsn(Opcodes.DUP);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitInsn(Opcodes.AASTORE);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            super.visitLdcInsn(CALLBACK);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                            replacements[0]++;
                        }
                    };
                }
            }, 0);
            return replacements[0] == 1 ? writer.toByteArray() : null;
        }
    }

    /**
     * ContextDefImpl.allProperties в mcore 8.6.0.v202607090722 добавляет наследуемый Key вторым. Результат
     * allMethods проходит через свою функцию: члены типов-наследников абстрактного типа («ОбъектМетаданных»)
     * добавляет основной бандл.
     */
    private static final class StructureKeyWeaving implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.platform.structureKey"; //$NON-NLS-1$
        private static final String METHODS_CALLBACK = "tormozit.platform.contextMethods"; //$NON-NLS-1$
        private static final String UTIL_METHODS_DESC = "(Lcom/_1c/g5/v8/dt/mcore/ContextDef;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/dt/bsl/model/Module;Lcom/e1c/g5/dt/core/api/naming/INamingService;)Ljava/util/List;"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK, (Function<Object[], Object>) args -> args[0]);
            System.getProperties().putIfAbsent(METHODS_CALLBACK, (Function<Object[], Object>) args -> args[0]);
            context.registerService(WeavingHook.class, new StructureKeyWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            boolean util = "com._1c.g5.v8.dt.bsl.util.BslUtil".equals(woven.getClassName()); //$NON-NLS-1$
            if (woven.getState() != WovenClass.TRANSFORMING
                || !util && !"com._1c.g5.v8.dt.mcore.impl.ContextDefImpl".equals(woven.getClassName())) //$NON-NLS-1$
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
                    // Редактор ищет члены не через ContextDef.allMethods, а через BslUtil.allMethods: у типа берутся
                    // только собственные методы и ссылочные контексты — наследники абстрактного типа не видны.
                    String callback = util ? "allMethods".equals(name) ? METHODS_CALLBACK : null //$NON-NLS-1$
                        : "allProperties".equals(name) ? CALLBACK //$NON-NLS-1$
                        : "allMethods".equals(name) ? METHODS_CALLBACK : null; //$NON-NLS-1$
                    if (callback == null || !(util ? UTIL_METHODS_DESC : "()Lorg/eclipse/emf/common/util/EList;") //$NON-NLS-1$
                        .equals(descriptor))
                        return mv;
                    String resultType = util ? "java/util/List" : "org/eclipse/emf/common/util/EList"; //$NON-NLS-1$ //$NON-NLS-2$
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(callback);
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
                                super.visitTypeInsn(Opcodes.CHECKCAST, resultType);
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            if (touched.get())
                woven.setBytes(writer.toByteArray());
            if (!util)
                System.getProperties().put(CALLBACK + ".woven", touched.get()); //$NON-NLS-1$
        }
    }

    /**
     * {@code EnvironmentalExtension.environments(EObject)} — окружение любого члена платформенного типа: им
     * пользуются проверки, подсказки и переход к определению. В данных платформы у значений части системных
     * перечислений окружение уже окружения самого перечисления (в справке доступность одна на всё
     * перечисление), и проверка членов после точки выдаёт ложную ошибку. Каждый возврат метода проходит через
     * функцию основного бандла {@code {окружение, член}}; до основного бандла она возвращает окружение без
     * изменений.
     */
    private static final class SystemEnumEnvironmentWeaving implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.platform.environments"; //$NON-NLS-1$
        private static final String ENVIRONMENTS = "com/_1c/g5/v8/dt/mcore/util/Environments"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK, (Function<Object[], Object>) args -> args[0]);
            context.registerService(WeavingHook.class, new SystemEnumEnvironmentWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING
                || !"com._1c.g5.v8.dt.mcore.impl.EnvironmentalExtension".equals(woven.getClassName())) //$NON-NLS-1$
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
                    if (mv == null || !"environments".equals(name) //$NON-NLS-1$
                        || !("(Lorg/eclipse/emf/ecore/EObject;)L" + ENVIRONMENTS + ";").equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
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
                                super.visitTypeInsn(Opcodes.CHECKCAST, ENVIRONMENTS);
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

    /**
     * {@code BslJavaValidator}: проверка «Свойство (метод) объекта не обнаружено» после точки
     * ({@code checkDynamicFeatureAccessForUnknownLeftPart}) выполняется только при статическом флаге
     * {@code exhaustiveValidation}, который класс читает из JVM-свойства при загрузке. То же свойство читают
     * валидаторы форм, и при включении на весь EDT они выдают десятки тысяч лишних замечаний, поэтому флаг
     * включается точечно: в статической инициализации {@code BslJavaValidator} вместо значения свойства пишется
     * {@code true}.
     *
     * <p>Кроме того, {@code unknownLeftPart} пропускает типы, члены которых считались открытыми: каждая такая
     * константа-имя типа в методе заменена вызовом функции основного бандла {@code {имя типа, обращение}}. Она
     * возвращает то же имя (тип пропускается, штатное поведение) или другое (тип проверяется): у типов с
     * динамическими ключами ({@code Структура}, строка таблицы и т. п.) проверяются вызовы методов, а у
     * {@code MetadataObject} — всё, когда основной бандл объединил члены его типов-наследников; {@code PictureLib}
     * (набор членов полный) проверяется всегда. До основного бандла
     * функция по умолчанию возвращает имя без изменений. Вплетение применяется, только если найдены все
     * ожидаемые вставки; итог — в системном свойстве {@code STATUS}.
     */
    private static final class UnknownMemberWeaving implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.bslValidator.openType"; //$NON-NLS-1$
        private static final String CHECK_LAST_MEMBER = "tormozit.bslValidator.checkLastMember";
        private static final String DYNAMIC_ALLOWED = "tormozit.bslValidator.dynamicAllowed"; //$NON-NLS-1$
        private static final String STATUS = "tormozit.bslValidator.unknownMemberPatch"; //$NON-NLS-1$
        private static final String TARGET = "com._1c.g5.v8.dt.bsl.validation.BslJavaValidator"; //$NON-NLS-1$
        private static final String TARGET_INTERNAL = TARGET.replace('.', '/');
        /** Типы из списка открытых в {@code unknownLeftPart}: динамические ключи или объединяемые члены. */
        private static final java.util.Set<String> OPEN_TYPES = java.util.Set.of("MetadataObject", "Structure", //$NON-NLS-1$ //$NON-NLS-2$
            "FixedStructure", "ValueTableRow", "ValueTreeRow", "QueryResultSelection", "ConstantsSet", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "FormDataStructure", "FormDataCollectionItem", "FormDataTreeItem", "PictureLib", "SequencesManager"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK, (Function<Object[], Object>) args -> args[0]);
            System.getProperties().putIfAbsent(CHECK_LAST_MEMBER, (Function<Object[], Object>) args -> args);
            System.getProperties().putIfAbsent(DYNAMIC_ALLOWED, (Function<Object[], Object>) args -> Boolean.TRUE);
            context.registerService(WeavingHook.class, new UnknownMemberWeaving(), null);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                ClassReader reader = new ClassReader(woven.getBytes());
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] replaced = new int[1];
                int[] flag = new int[1];
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions)
                    {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (mv != null && "<clinit>".equals(name)) //$NON-NLS-1$
                            return new MethodVisitor(Opcodes.ASM9, mv)
                            {
                                @Override
                                public void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor)
                                {
                                    if (opcode == Opcodes.PUTSTATIC && TARGET_INTERNAL.equals(owner)
                                        && "exhaustiveValidation".equals(field) && "Z".equals(fieldDescriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                                    {
                                        // Значение, прочитанное из JVM-свойства, заменяется на true
                                        super.visitInsn(Opcodes.POP);
                                        super.visitInsn(Opcodes.ICONST_1);
                                        flag[0]++;
                                    }
                                    super.visitFieldInsn(opcode, owner, field, fieldDescriptor);
                                }
                            };
                        if (mv != null && "checkDynamicFeatureAccessForUnknownLeftPart".equals(name))
                            return checkLastMember(mv);
                        if (mv == null || !"unknownLeftPart".equals(name)) //$NON-NLS-1$
                            return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitLdcInsn(Object value)
                            {
                                if (!(value instanceof String type) || !OPEN_TYPES.contains(type))
                                {
                                    super.visitLdcInsn(value);
                                    return;
                                }
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(CALLBACK);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                // Аргументы {имя типа, обращение, части цепочки, индекс, имена типов}: обращение — первый
                                // параметр unknownLeftPart, части, индекс и имена типов — локальные 4, 6 и 8
                                // (помечается часть с индексом - 1)
                                super.visitInsn(Opcodes.ICONST_5);
                                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitLdcInsn(type);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_1);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_2);
                                super.visitVarInsn(Opcodes.ALOAD, 4);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_3);
                                super.visitVarInsn(Opcodes.ILOAD, 6);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(I)Ljava/lang/Integer;", false); //$NON-NLS-1$
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_4);
                                super.visitVarInsn(Opcodes.ALOAD, 8);
                                super.visitInsn(Opcodes.AASTORE);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String"); //$NON-NLS-1$
                                replaced[0]++;
                            }
                        };
                    }
                }, 0);
                boolean complete = flag[0] == 1 && replaced[0] == OPEN_TYPES.size();
                if (complete)
                    woven.setBytes(writer.toByteArray());
                System.getProperties().put(STATUS, complete ? "woven" //$NON-NLS-1$
                    : "skipped: flag=" + flag[0] + " types=" + replaced[0] + "/" + OPEN_TYPES.size()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            catch (RuntimeException | LinkageError error)
            {
                System.getProperties().put(STATUS, "failed: " + error); //$NON-NLS-1$
            }
        }

        private static MethodVisitor checkLastMember(MethodVisitor delegate)
        {
            return new MethodVisitor(Opcodes.ASM9, delegate)
            {
                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor)
                {
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                    if (opcode == Opcodes.GETSTATIC && TARGET_INTERNAL.equals(owner)
                        && "exhaustiveValidation".equals(name) && "Z".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        emitDynamicAllowed(this);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface)
                {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    if (!"com/_1c/g5/v8/dt/bsl/resource/DynamicFeatureAccessComputer".equals(owner)
                        || !"getLastObject".equals(name)
                        || !"(Lcom/_1c/g5/v8/dt/bsl/model/DynamicFeatureAccess;Lcom/_1c/g5/v8/dt/mcore/util/Environments;Z)Ljava/util/List;".equals(descriptor))
                        return;
                    super.visitVarInsn(Opcodes.ASTORE, 4);
                    EarlyWeaving.emitGetProperty(this, CHECK_LAST_MEMBER, "java/util/function/Function");
                    super.visitInsn(Opcodes.ICONST_3);
                    super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                    int[] slots = {4, 1, 3}; // Результат разрешения, обращение, окружения.
                    for (int i = 0; i < slots.length; i++)
                    {
                        super.visitInsn(Opcodes.DUP);
                        super.visitInsn(Opcodes.ICONST_0 + i);
                        super.visitVarInsn(Opcodes.ALOAD, slots[i]);
                        super.visitInsn(Opcodes.AASTORE);
                    }
                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                    super.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
                    super.visitInsn(Opcodes.DUP);
                    super.visitInsn(Opcodes.ICONST_1);
                    super.visitInsn(Opcodes.AALOAD);
                    super.visitTypeInsn(Opcodes.CHECKCAST, "com/_1c/g5/v8/dt/bsl/model/DynamicFeatureAccess");
                    super.visitVarInsn(Opcodes.ASTORE, 1);
                    super.visitInsn(Opcodes.ICONST_0);
                    super.visitInsn(Opcodes.AALOAD);
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List");
                }
            };
        }

        /**
         * Стек при {@code GETSTATIC exhaustiveValidation}: {@code [boolean]}. Значение заменяется на
         * {@code {обращение, окружения, значение}} — основной бандл возвращает {@code false}, если цепочку
         * проверяет статическая проверка. Вставка без ветвлений, кадры стека не меняются.
         */
        private static void emitDynamicAllowed(MethodVisitor visitor)
        {
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", //$NON-NLS-1$ //$NON-NLS-2$
                "(I)Ljava/lang/Integer;", false); //$NON-NLS-1$
            visitor.visitVarInsn(Opcodes.ASTORE, 5);
            EarlyWeaving.emitGetProperty(visitor, DYNAMIC_ALLOWED, "java/util/function/Function"); //$NON-NLS-1$
            visitor.visitInsn(Opcodes.ICONST_3);
            visitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
            visitor.visitInsn(Opcodes.DUP);
            visitor.visitInsn(Opcodes.ICONST_0);
            visitor.visitVarInsn(Opcodes.ALOAD, 1);
            visitor.visitInsn(Opcodes.AASTORE);
            visitor.visitInsn(Opcodes.DUP);
            visitor.visitInsn(Opcodes.ICONST_1);
            visitor.visitVarInsn(Opcodes.ALOAD, 3);
            visitor.visitInsn(Opcodes.AASTORE);
            visitor.visitInsn(Opcodes.DUP);
            visitor.visitInsn(Opcodes.ICONST_2);
            visitor.visitVarInsn(Opcodes.ALOAD, 5);
            visitor.visitInsn(Opcodes.AASTORE);
            visitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
            visitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean"); //$NON-NLS-1$
            visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Типы элементов массива: ранний перехват обоих штатных способов чтения типа. */
    private static final class ArrayElementTypesWeaving implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.arrayElementTypes.variableTypes"; //$NON-NLS-1$
        private static final String TYPE_HINT_ENVIRONMENTS = "tormozit.preprocessor.typeHintEnvironments"; //$NON-NLS-1$
        private static final String TARGET = "com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil"; //$NON-NLS-1$
        private static final String COMPUTER_TARGET = "com._1c.g5.v8.dt.bsl.resource.TypesComputer"; //$NON-NLS-1$
        private static final String PROPOSAL_TARGET = "com._1c.g5.v8.dt.bsl.ui.contentassist.BslProposalProvider"; //$NON-NLS-1$
        private static final String STATUS = "tormozit.arrayElementTypes.early"; //$NON-NLS-1$

        private static void adjustTypeHintEnvironments(MethodVisitor visitor)
        {
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                "()Ljava/util/Properties;", false); //$NON-NLS-1$
            visitor.visitLdcInsn(TYPE_HINT_ENVIRONMENTS);
            visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
            visitor.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
            visitor.visitVarInsn(Opcodes.ALOAD, 1);
            visitor.visitVarInsn(Opcodes.ALOAD, 2);
            visitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
            visitor.visitTypeInsn(Opcodes.CHECKCAST, "com/_1c/g5/v8/dt/mcore/util/Environments"); //$NON-NLS-1$
            visitor.visitVarInsn(Opcodes.ASTORE, 2);
        }
        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK,
                (Function<Object, Object>) payload -> ((Object[])payload)[0]);
            System.getProperties().putIfAbsent(TYPE_HINT_ENVIRONMENTS,
                (java.util.function.BiFunction<Object, Object, Object>) (expression, environments) -> environments);
            context.registerService(WeavingHook.class, new ArrayElementTypesWeaving(), null);
            System.getProperties().put(STATUS, Boolean.TRUE);
        }

        @Override
        public void weave(WovenClass woven)
        {
            String name = woven.getClassName();
            if (woven.getState() != WovenClass.TRANSFORMING
                || !TARGET.equals(name) && !COMPUTER_TARGET.equals(name) && !PROPOSAL_TARGET.equals(name))
                return;
            try
            {
                byte[] transformed = PROPOSAL_TARGET.equals(name)
                    ? transformMemberProposalEnvironments(woven.getBytes())
                    : transform(name.replace('.', '/'), woven.getBytes());
                if (transformed == null)
                    throw new IllegalStateException("Array element types method not found: " + name); //$NON-NLS-1$
                woven.setBytes(transformed);
            }
            catch (RuntimeException | LinkageError failure)
            {
                System.getProperties().put(STATUS + "." + name, Boolean.FALSE); //$NON-NLS-1$
                System.getProperties().put(STATUS + ".failure", failure.toString()); //$NON-NLS-1$
            }
        }

        /** EDT прекращает подбор членов сразу после getEnvsForDynamicFeatureAccess, если окружение пусто. */
        private static byte[] transformMemberProposalEnvironments(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean changed = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (delegate == null || !"getEnvsForDynamicFeatureAccess".equals(name) //$NON-NLS-1$
                        || !"(Lorg/eclipse/emf/ecore/EObject;)Lcom/_1c/g5/v8/dt/mcore/util/Environments;".equals(descriptor)) //$NON-NLS-1$
                        return delegate;
                    return new MethodVisitor(Opcodes.ASM9, delegate)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                // Стек: исходное окружение -> callback, узел, исходное окружение.
                                EarlyWeaving.emitGetProperty(this, TYPE_HINT_ENVIRONMENTS, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "com/_1c/g5/v8/dt/mcore/util/Environments"); //$NON-NLS-1$
                                changed.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return changed.get() ? writer.toByteArray() : null;
        }

        static byte[] transform(String className, byte[] bytes)
        {
            boolean variableUtility = TARGET.replace('.', '/').equals(className);
            if (!variableUtility && !COMPUTER_TARGET.replace('.', '/').equals(className))
                return null;
            var reader = new org.objectweb.asm.ClassReader(bytes);
            var writer = new org.objectweb.asm.ClassWriter(reader, org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
            AtomicBoolean changed = new AtomicBoolean();
            reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer)
            {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    var visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean selected = variableUtility
                        ? "getVariableTypesAtOffset".equals(name) && descriptor.equals( //$NON-NLS-1$
                            "(Lcom/_1c/g5/v8/dt/bsl/model/Variable;ILcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;") //$NON-NLS-1$
                        : "computeTypes".equals(name) && descriptor.equals( //$NON-NLS-1$
                            "(Lorg/eclipse/emf/ecore/EObject;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;"); //$NON-NLS-1$
                    if (!selected)
                        return visitor;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, visitor)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            if (!variableUtility)
                                adjustTypeHintEnvironments(this);
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == org.objectweb.asm.Opcodes.ARETURN)
                            {
                                visitVarInsn(org.objectweb.asm.Opcodes.ASTORE, 3);
                                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "java/lang/System", //$NON-NLS-1$
                                    "getProperties", "()Ljava/util/Properties;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                visitLdcInsn(CALLBACK);
                                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/util/Properties", //$NON-NLS-1$
                                    "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                int argumentCount = variableUtility ? 4 : 3;
                                visitLdcInsn(Integer.valueOf(argumentCount));
                                visitTypeInsn(org.objectweb.asm.Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                for (int index = 0; index < argumentCount; index++)
                                {
                                    visitInsn(org.objectweb.asm.Opcodes.DUP);
                                    visitLdcInsn(Integer.valueOf(index));
                                    boolean integer = variableUtility && index == 2;
                                    int slot = index == 0 ? 3 : variableUtility ? index - 1 : index;
                                    visitVarInsn(integer ? org.objectweb.asm.Opcodes.ILOAD
                                        : org.objectweb.asm.Opcodes.ALOAD, slot);
                                    if (integer)
                                        visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "java/lang/Integer", //$NON-NLS-1$
                                            "valueOf", "(I)Ljava/lang/Integer;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                    visitInsn(org.objectweb.asm.Opcodes.AASTORE);
                                }
                                visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "java/util/function/Function", //$NON-NLS-1$
                                    "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$
                                visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, "java/util/List"); //$NON-NLS-1$
                                changed.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            System.getProperties().put(STATUS + "." + className.replace('/', '.'), changed.get()); //$NON-NLS-1$
            return changed.get() ? writer.toByteArray() : null;
        }
    }

    /** Полный тип массива в штатных ссылках документации — общий для наведения и автодополнения. */
    private static final class ArrayTypeReferenceWeaving implements WeavingHook
    {
        private static final String TARGET = "com._1c.g5.v8.dt.internal.bsl.ui.documentation.BslDocumentationProvider"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.arrayElementTypes.reference"; //$NON-NLS-1$
        private static final String STATUS = "tormozit.arrayTypeReference.early"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK,
                (Function<Object, Object>) payload -> ((Object[])payload)[0]);
            context.registerService(WeavingHook.class, new ArrayTypeReferenceWeaving(), null);
            System.getProperties().put(STATUS, Boolean.TRUE);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            byte[] transformed = transform(woven.getBytes());
            System.getProperties().put(STATUS + ".woven", transformed != null); //$NON-NLS-1$
            if (transformed != null)
                woven.setBytes(transformed);
        }

        static byte[] transform(byte[] bytes)
        {
            var reader = new ClassReader(bytes);
            var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean changed = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"convertTypeItemToReference".equals(name) || !descriptor.equals( //$NON-NLS-1$
                        "(Lcom/_1c/g5/v8/dt/mcore/TypeItem;Lorg/eclipse/emf/ecore/resource/Resource;ZLcom/_1c/g5/v8/dt/platform/version/Version;Z)Lcom/_1c/g5/v8/dt/internal/bsl/ui/documentation/page/IPageReference;")) //$NON-NLS-1$
                        return original;
                    return new MethodVisitor(Opcodes.ASM9, original)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                super.visitVarInsn(Opcodes.ASTORE, 6);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(CALLBACK);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                super.visitLdcInsn(6);
                                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                for (int index = 0; index < 6; index++)
                                {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitLdcInsn(index);
                                    int slot = index == 0 ? 6 : index == 5 ? 0 : index;
                                    super.visitVarInsn(index == 3 ? Opcodes.ILOAD : Opcodes.ALOAD, slot);
                                    if (index == 3)
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", //$NON-NLS-1$ //$NON-NLS-2$
                                            "(Z)Ljava/lang/Boolean;", false); //$NON-NLS-1$
                                    super.visitInsn(Opcodes.AASTORE);
                                }
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST,
                                    "com/_1c/g5/v8/dt/internal/bsl/ui/documentation/page/IPageReference"); //$NON-NLS-1$
                                changed.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return changed.get() ? writer.toByteArray() : null;
        }
    }

    /** Общий каталог имён типов перед штатным расчётом проверки и автодополнения. */
    private static final class StringLiteralTypeNamesWeaving implements WeavingHook
    {
        private static final String ROOT = "com._1c.g5.v8.dt.bsl.ui.";
        private static final String BASE = ROOT + "contentassist.stringliteral.AbstractStringLiteralProposalProvider";
        private static final String DESCRIPTION = ROOT + "internal.contentassist.stringliteral.StringLiteralProposalProviderTypeDescription";
        private static final String CALLBACK = "tormozit.stringLiteralTypeNames.proposals";
        private static final String CTOR_TARGET = ROOT + "contentassist.BslProposalProvider";
        private static final String CTOR_CALLBACK = "tormozit.constructorTypeNames.descriptions";
        private static final String PREFIX = "(Lorg/eclipse/emf/ecore/EObject;Ljava/lang/String;Lorg/eclipse/xtext/scoping/IScopeProvider;"
            + "Lcom/_1c/g5/modeling/xtext/scoping/IIndexSlicePredicateService;";

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK, (Function<Object[], Object>)args -> null);
            System.getProperties().putIfAbsent(CTOR_CALLBACK, (Function<Object[], Object>)args -> null);
            context.registerService(WeavingHook.class, new StringLiteralTypeNamesWeaving(), null);
        }

        private static boolean supportsBundleVersion(WovenClass woven)
        {
            var version = woven.getBundleWiring().getBundle().getVersion();
            return version.getMajor() == 22 && version.getMinor() == 0 && version.getMicro() == 0;
        }

        @Override
        public void weave(WovenClass woven)
        {
            String target = woven.getClassName();
            if (woven.getState() == WovenClass.TRANSFORMING && CTOR_TARGET.equals(target)
                && supportsBundleVersion(woven))
            {
                try
                {
                    byte[] transformed = transformConstructors(woven.getBytes());
                    System.getProperties().put(CTOR_CALLBACK + ".woven", transformed != null);
                    if (transformed != null)
                        woven.setBytes(transformed);
                }
                catch (RuntimeException | LinkageError failure)
                {
                    System.getProperties().put(CTOR_CALLBACK + ".error", failure.toString());
                }
                return;
            }
            if (woven.getState() != WovenClass.TRANSFORMING || !(BASE.equals(target) || DESCRIPTION.equals(target)))
                return;
            try
            {
                if (!supportsBundleVersion(woven))
                {
                    return;
                }
                boolean base = BASE.equals(target);
                String methodName = base ? "createProposalsBaseType" : "computeProposals";
                String methodDescriptor = PREFIX + (base ? "Lcom/google/common/base/Predicate;" : "")
                    + "Z)Ljava/util/List;";
                var reader = new ClassReader(woven.getBytes());
                var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] changed = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions)
                    {
                        MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                        if (!methodName.equals(name) || !methodDescriptor.equals(descriptor))
                            return original;
                        changed[0]++;
                        return new MethodVisitor(Opcodes.ASM9, original)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                EarlyWeaving.emitGetProperty(this, CALLBACK, "java/util/function/Function");
                                super.visitLdcInsn(6);
                                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                                for (int index = 0; index < 6; index++)
                                {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitLdcInsn(index);
                                    int slot = index == 5 ? (base ? 6 : 5) : index;
                                    super.visitVarInsn(index == 5 ? Opcodes.ILOAD : Opcodes.ALOAD, slot);
                                    if (index == 5)
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                                            "(Z)Ljava/lang/Boolean;", false);
                                    super.visitInsn(Opcodes.AASTORE);
                                }
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                                super.visitInsn(Opcodes.DUP);
                                Label fallback = new Label();
                                super.visitJumpInsn(Opcodes.IFNULL, fallback);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List");
                                super.visitInsn(Opcodes.ARETURN);
                                super.visitLabel(fallback);
                                // На ветке отказа остаётся один Object; исходные локальные переменные не менялись.
                                super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Object"});
                                super.visitInsn(Opcodes.POP);
                            }
                        };
                    }
                }, 0);
                if (changed[0] == 1)
                    woven.setBytes(writer.toByteArray());
            }
            catch (RuntimeException | LinkageError failure)
            {
                // Ошибка необязательного вплетения не прерывает загрузку класса.
            }
        }

        static byte[] transformConstructors(byte[] bytes)
        {
            var reader = new ClassReader(bytes);
            var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            String scope = "com/_1c/g5/modeling/xtext/scoping/ISlicedScope";
            String descriptor = "(L" + scope + ";Ljava/util/Collection;Lorg/eclipse/emf/ecore/EObject;Z)Ljava/lang/Iterable;";
            int[] changed = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String methodDescriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor original = super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                    if (!name.equals("createProposalsForCtors"))
                        return original;
                    return new MethodVisitor(Opcodes.ASM9, original)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                            String callDescriptor, boolean isInterface)
                        {
                            if (owner.equals(scope) && methodName.equals("getAllElements")
                                && callDescriptor.equals("(Ljava/util/Collection;)Ljava/lang/Iterable;"))
                            {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD, reader.getClassName(), "isRussian", "Z");
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, reader.getClassName(),
                                    "comfort$constructorTypes", descriptor, false);
                                changed[0]++;
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, methodName, callDescriptor, isInterface);
                        }
                    };
                }
            }, 0);
            if (changed[0] != 1)
                return null;
            var helper = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "comfort$constructorTypes", descriptor, null, null);
            helper.visitCode();
            EarlyWeaving.emitGetProperty(helper, CTOR_CALLBACK, "java/util/function/Function");
            helper.visitInsn(Opcodes.ICONST_3);
            helper.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            for (int index = 0; index < 3; index++)
            {
                helper.visitInsn(Opcodes.DUP);
                helper.visitLdcInsn(index);
                if (index == 0)
                    helper.visitVarInsn(Opcodes.ALOAD, 2);
                else if (index == 1)
                    helper.visitVarInsn(Opcodes.ALOAD, 0);
                else
                {
                    helper.visitVarInsn(Opcodes.ILOAD, 3);
                    helper.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                }
                helper.visitInsn(Opcodes.AASTORE);
            }
            helper.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            helper.visitInsn(Opcodes.DUP);
            Label fallback = new Label();
            helper.visitJumpInsn(Opcodes.IFNULL, fallback);
            helper.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Iterable");
            helper.visitInsn(Opcodes.ARETURN);
            helper.visitLabel(fallback);
            helper.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { "java/lang/Object" });
            helper.visitInsn(Opcodes.POP);
            helper.visitVarInsn(Opcodes.ALOAD, 0);
            helper.visitVarInsn(Opcodes.ALOAD, 1);
            helper.visitMethodInsn(Opcodes.INVOKEINTERFACE, scope, "getAllElements",
                "(Ljava/util/Collection;)Ljava/lang/Iterable;", true);
            helper.visitInsn(Opcodes.ARETURN);
            helper.visitMaxs(0, 4);
            helper.visitEnd();
            return writer.toByteArray();
        }
    }


    /** Подпись подавления проверки — первая строка сообщения; само описание проблемы сохраняется целиком. */
    private static final class QuickFixPresentationWeaving implements WeavingHook
    {
        private static final String TARGET = "com._1c.g5.v8.dt.bsl.ui.quickfix.BslQuickfixProvider"; //$NON-NLS-1$
        private static final String STATUS = "tormozit.quickFixPresentation.early"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            context.registerService(WeavingHook.class, new QuickFixPresentationWeaving(), null);
            System.getProperties().put(STATUS, Boolean.TRUE);
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (woven.getState() != WovenClass.TRANSFORMING || !TARGET.equals(woven.getClassName()))
                return;
            byte[] transformed = transform(woven.getBytes());
            System.getProperties().put(STATUS + ".woven", transformed != null); //$NON-NLS-1$
            if (transformed != null)
                woven.setBytes(transformed);
        }

        static byte[] transform(byte[] bytes)
        {
            var reader = new ClassReader(bytes);
            var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean changed = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"skipCheckQuickFix".equals(name) || !descriptor.equals( //$NON-NLS-1$
                        "(Lorg/eclipse/xtext/validation/Issue;Lorg/eclipse/xtext/ui/editor/quickfix/IssueResolutionAcceptor;)V")) //$NON-NLS-1$
                        return original;
                    return new MethodVisitor(Opcodes.ASM9, original)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface)
                        {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            if (opcode != Opcodes.INVOKEINTERFACE || !"org/eclipse/xtext/validation/Issue".equals(owner) //$NON-NLS-1$
                                || !"getMessage".equals(name) || !"()Ljava/lang/String;".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                                return;
                            super.visitLdcInsn("(?s)\\R.*"); //$NON-NLS-1$
                            super.visitLdcInsn(""); //$NON-NLS-1$
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceFirst", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false); //$NON-NLS-1$
                            super.visitLdcInsn(":\\s*$"); //$NON-NLS-1$
                            super.visitLdcInsn(""); //$NON-NLS-1$
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replaceFirst", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false); //$NON-NLS-1$
                            changed.set(true);
                        }
                    };
                }
            }, 0);
            return changed.get() ? writer.toByteArray() : null;
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
        private static final String INDEX_ACCESS = "tormozit.indexAccess.types"; //$NON-NLS-1$
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
            System.getProperties().putIfAbsent(INDEX_ACCESS, (Function<Object[], Object>) args -> args[0]);
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
            AtomicBoolean indexAccess = new AtomicBoolean();
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
                    else if (COMPUTER.equals(className) && "_compute".equals(name) //$NON-NLS-1$
                        && "(Lcom/_1c/g5/v8/dt/bsl/model/IndexAccess;Lcom/_1c/g5/v8/dt/mcore/util/Environments;)Ljava/util/List;".equals(descriptor)) //$NON-NLS-1$
                    {
                        callback = INDEX_ACCESS;
                        indexAccess.set(true);
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
            return property.get()
                && (!COMPUTER.equals(className) || binary.get() && attributeAccess.get() && indexAccess.get())
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
        private static final String RETURN_MEMO = "returnMemo"; //$NON-NLS-1$
        /** Закрытый {@code computeReturnTypes} с множеством посещённых: вход запоминания типов возврата. */
        private static final String RETURN_TYPES_DESCRIPTOR = "(Lorg/eclipse/xtext/scoping/IScope;" //$NON-NLS-1$
            + "Lorg/eclipse/xtext/scoping/IScopeProvider;Lorg/eclipse/xtext/naming/IQualifiedNameConverter;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/dt/bsl/documentation/comment/BslMultiLineCommentDocumentationProvider;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/dt/core/platform/IV8ProjectManager;ZLorg/eclipse/emf/ecore/EObject;Ljava/util/Set;" //$NON-NLS-1$
            + "Lcom/e1c/g5/dt/core/api/platform/BmOperationContext;)Ljava/util/Collection;"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            for (String operation : List.of("comment", "convert", "reference", "html", "property", "docDependency")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                System.getProperties().putIfAbsent(PREFIX + operation, (Function<Object[], Object>) args -> args[0]);
            // null — штатный расчёт; основной бандл подставляет запомненный результат.
            System.getProperties().putIfAbsent(PREFIX + RETURN_MEMO, (Function<Object[], Object>) args -> null);
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
                    if (visitor != null && COMMENT.equals(className) && "computeReturnTypes".equals(name) //$NON-NLS-1$
                        && RETURN_TYPES_DESCRIPTOR.equals(descriptor) && (access & Opcodes.ACC_STATIC) == 0)
                    {
                        // Пролог: r = returnMemo([this, аргументы…]); if (r != null) return r;
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                Label stock = new Label();
                                org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(descriptor);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PREFIX + RETURN_MEMO);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                super.visitIntInsn(Opcodes.BIPUSH, args.length + 1);
                                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.AASTORE);
                                int slot = 1;
                                for (int i = 0; i < args.length; i++)
                                {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitIntInsn(Opcodes.BIPUSH, i + 1);
                                    if (args[i].getSort() == org.objectweb.asm.Type.BOOLEAN)
                                    {
                                        super.visitVarInsn(Opcodes.ILOAD, slot);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", //$NON-NLS-1$ //$NON-NLS-2$
                                            "(Z)Ljava/lang/Boolean;", false); //$NON-NLS-1$
                                    }
                                    else
                                        super.visitVarInsn(Opcodes.ALOAD, slot);
                                    super.visitInsn(Opcodes.AASTORE);
                                    slot += args[i].getSize();
                                }
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitInsn(Opcodes.DUP);
                                super.visitJumpInsn(Opcodes.IFNULL, stock);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Collection"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.ARETURN);
                                super.visitLabel(stock);
                                // Кадр относительно начального: локальные — параметры метода, на стеке null.
                                super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Object"}); //$NON-NLS-1$
                                super.visitInsn(Opcodes.POP);
                                touched.set(true);
                                System.getProperties().put(PREFIX + RETURN_MEMO + ".woven", Boolean.TRUE); //$NON-NLS-1$
                            }
                        };
                    }
                    if (visitor != null && COMMENT.equals(className)
                        && "computeTypesByLinkPart".equals(name)) //$NON-NLS-1$
                    {
                        // Слот множества посещённых методов (единственный параметр Set) — третьим
                        // аргументом колбэка: по нему основной бандл пропускает разбор
                        // комментария уже посещённой функции.
                        int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                        int found = -1;
                        for (org.objectweb.asm.Type arg : org.objectweb.asm.Type.getArgumentTypes(descriptor))
                        {
                            if ("Ljava/util/Set;".equals(arg.getDescriptor())) //$NON-NLS-1$
                                found = found == -1 ? slot : -2;
                            slot += arg.getSize();
                        }
                        final int visitedSlot = found;
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String called,
                                String desc, boolean isInterface)
                            {
                                super.visitMethodInsn(opcode, owner, called, desc, isInterface);
                                if (opcode != Opcodes.INVOKESTATIC
                                    || !"com/_1c/g5/v8/dt/bsl/documentation/comment/BslCommentUtils".equals(owner) //$NON-NLS-1$
                                    || !"getObjectByLinkPart".equals(called)) //$NON-NLS-1$
                                    return;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(PREFIX + "docDependency"); //$NON-NLS-1$
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitInsn(visitedSlot >= 0 ? Opcodes.ICONST_3 : Opcodes.ICONST_2);
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
                                if (visitedSlot >= 0)
                                {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitInsn(Opcodes.ICONST_2);
                                    super.visitVarInsn(Opcodes.ALOAD, visitedSlot);
                                    super.visitInsn(Opcodes.AASTORE);
                                }
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "org/eclipse/emf/ecore/EObject"); //$NON-NLS-1$
                                touched.set(true);
                            }
                        };
                    }
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
        private static final String NAMING_TARGET =
            "com._1c.g5.v8.dt.bsl.typesystem.NamingCollectionServiceProvider"; //$NON-NLS-1$
        private static final String PROP_NAMING = "tormozit.dynamicFeatureAccess.namingCollections"; //$NON-NLS-1$
        private static final String PROP_PROPERTY_TRACE = "tormozit.dynamicFeatureAccess.propertyTrace"; //$NON-NLS-1$
        /** Результат {@code getAllMethodAndProperties}: члены по типам-владельцам для подписи «~ Владелец». */
        private static final String PROP_ALL_MEMBERS = "tormozit.dynamicFeatureAccess.allMembers"; //$NON-NLS-1$
        private static final String ALL_MEMBERS_DESC = "(Ljava/util/List;Lorg/eclipse/emf/ecore/resource/Resource;)" //$NON-NLS-1$
            + "Ljava/util/Collection;"; //$NON-NLS-1$
        /**
         * Список типов на входе обоих методов поиска членов: {@code {типы, ресурс}} → тот же или дополненный список.
         * Неспециализированный тип элемента формы получает члены всех своих расширений.
         */
        private static final String PROP_FORM_ITEM_TYPES = "tormozit.dynamicFeatureAccess.formItemTypes"; //$NON-NLS-1$
        /**
         * То же для поиска методов проверкой члена после точки: он берёт у типа только собственные методы, поэтому
         * к абстрактному «ОбъектМетаданных» добавляются его наследники.
         */
        private static final String PROP_METHOD_TYPES = "tormozit.dynamicFeatureAccess.methodTypes"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(PROP_NAMING, (Function<Object[], Object>) args -> args[0]);
            System.getProperties().putIfAbsent(PROP_METHOD_TYPES, (Function<Object[], Object>) args -> args[0]);
            // Основной бандл позже заменит эти функции; до него результат строго штатный.
            System.getProperties().putIfAbsent(PROP_PROPERTY_TRACE, (Function<Object[], Object>) args -> args[0]);
            System.getProperties().putIfAbsent(PROP_ALL_MEMBERS, (Function<Object[], Object>) args -> args[0]);
            System.getProperties().putIfAbsent(PROP_FORM_ITEM_TYPES, (Function<Object[], Object>) args -> args[0]);
            context.registerService(WeavingHook.class, new DynamicFeatureWeaving(), null);
        }

        /** В начале метода: {@code types = функция({types, resource})}; {@code types} — локальная 1. */
        private static void expandTypesAtEntry(MethodVisitor mv, int resourceLocal, String castTo, String property,
            int nameLocal)
        {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                "()Ljava/util/Properties;", false); //$NON-NLS-1$
            mv.visitLdcInsn(property);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
            mv.visitInsn(nameLocal >= 0 ? Opcodes.ICONST_3 : Opcodes.ICONST_2);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.AASTORE);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitVarInsn(Opcodes.ALOAD, resourceLocal);
            mv.visitInsn(Opcodes.AASTORE);
            if (nameLocal >= 0)
            {
                // Третий элемент — искомое имя члена
                mv.visitInsn(Opcodes.DUP);
                mv.visitInsn(Opcodes.ICONST_2);
                mv.visitVarInsn(Opcodes.ALOAD, nameLocal);
                mv.visitInsn(Opcodes.AASTORE);
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
            mv.visitTypeInsn(Opcodes.CHECKCAST, castTo);
            mv.visitVarInsn(Opcodes.ASTORE, 1);
        }

        @Override
        public void weave(WovenClass woven)
        {
            boolean naming = NAMING_TARGET.equals(woven.getClassName());
            if (woven.getState() != WovenClass.TRANSFORMING || !naming && !TARGET.equals(woven.getClassName()))
                return;
            try
            {
                byte[] transformed = naming ? transformNaming(woven.getBytes()) : transform(woven.getBytes());
                if (transformed != null)
                    woven.setBytes(transformed);
            }
            catch (RuntimeException | LinkageError error)
            {
            }
        }

        private static void callbackResult(MethodVisitor mv, String callback, String resultType, int... locals)
        {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                "()Ljava/util/Properties;", false); //$NON-NLS-1$
            mv.visitLdcInsn(callback);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
            mv.visitInsn(Opcodes.SWAP);
            mv.visitIntInsn(Opcodes.BIPUSH, locals.length + 1);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
            mv.visitInsn(Opcodes.DUP_X1);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitInsn(Opcodes.AASTORE);
            for (int index = 0; index < locals.length; index++)
            {
                mv.visitInsn(Opcodes.DUP);
                mv.visitIntInsn(Opcodes.BIPUSH, index + 1);
                mv.visitVarInsn(Opcodes.ALOAD, locals[index]);
                mv.visitInsn(Opcodes.AASTORE);
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
            mv.visitTypeInsn(Opcodes.CHECKCAST, resultType);
        }

        /** Реестр дополняется перед кэшированием по версии платформы, одновременно для обоих его потребителей. */
        static byte[] transformNaming(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            AtomicBoolean touched = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"readNamingCollection".equals(name) //$NON-NLS-1$
                        || !"(Lcom/_1c/g5/v8/dt/platform/version/Version;)Ljava/util/Map;".equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                callbackResult(this, PROP_NAMING, "java/util/Map", 1); //$NON-NLS-1$
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return touched.get() ? writer.toByteArray() : null;
        }

        private static final String PROPERTY_DESC = "(Ljava/util/Collection;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/dt/mcore/util/Environments;Ljava/lang/String;Z" //$NON-NLS-1$
            + "Lorg/eclipse/emf/ecore/resource/Resource;Lorg/eclipse/xtext/util/CancelIndicator;)Ljava/util/List;"; //$NON-NLS-1$

        private static final String METHOD_DESC = "(Ljava/util/Collection;" //$NON-NLS-1$
            + "Lcom/_1c/g5/v8/dt/mcore/util/Environments;Ljava/lang/String;IZ" //$NON-NLS-1$
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
                    // BslUtil.getRealObject заново берёт окружение узла при разрешении вызываемого метода.
                    if (mv != null && "getLastObject".equals(name) && descriptor.equals( //$NON-NLS-1$
                        "(Lcom/_1c/g5/v8/dt/bsl/model/DynamicFeatureAccess;Lcom/_1c/g5/v8/dt/mcore/util/Environments;Z)Ljava/util/List;")) //$NON-NLS-1$
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                ArrayElementTypesWeaving.adjustTypeHintEnvironments(this);
                                touched.set(true);
                            }
                        };
                    if (mv != null && "getAllMethodAndProperties".equals(name) && ALL_MEMBERS_DESC.equals(descriptor)) //$NON-NLS-1$
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                expandTypesAtEntry(this, 2, "java/util/List", PROP_FORM_ITEM_TYPES, -1); //$NON-NLS-1$
                                touched.set(true);
                            }

                            @Override
                            public void visitInsn(int opcode)
                            {
                                if (opcode == Opcodes.ARETURN)
                                {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                        "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                    super.visitLdcInsn(PROP_ALL_MEMBERS);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Function"); //$NON-NLS-1$
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitInsn(Opcodes.ICONST_1);
                                    super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                    super.visitInsn(Opcodes.DUP_X1);
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitInsn(Opcodes.ICONST_0);
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitInsn(Opcodes.AASTORE);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply", //$NON-NLS-1$ //$NON-NLS-2$
                                        "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Collection"); //$NON-NLS-1$
                                    touched.set(true);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    // Поиск методов (проверка члена после точки): типы расширяются так же, как для свойств.
                    // Локальные: 1 — типы, 6 — ресурс.
                    if (mv != null && "getObjectFromContextDefOfTypes".equals(name) //$NON-NLS-1$
                        && METHOD_DESC.equals(descriptor))
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                expandTypesAtEntry(this, 6, "java/util/Collection", PROP_METHOD_TYPES, 3); //$NON-NLS-1$
                                touched.set(true);
                            }
                        };
                    if (mv == null || !"getObjectFromContextDefOfTypes".equals(name) //$NON-NLS-1$
                        || !PROPERTY_DESC.equals(descriptor))
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            expandTypesAtEntry(this, 5, "java/util/Collection", PROP_FORM_ITEM_TYPES, -1); //$NON-NLS-1$
                            touched.set(true);
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                // {результат, типы, имя, ресурс}
                                callbackResult(this, PROP_PROPERTY_TRACE, "java/util/List", 1, 3, 5); //$NON-NLS-1$
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
        /** Вызывается для каждого собранного контекста проверок с причиной изменения, при любом флажке режима. */
        private static final String CONTROLLER =
            "com.e1c.g5.v8.dt.internal.check.derived.CheckDerivedDataContributor$CheckDeactivationController"; //$NON-NLS-1$
        private static final String PROJECT = "Lorg/eclipse/core/resources/IProject;"; //$NON-NLS-1$
        private static final String TRANSACTION = "Lcom/_1c/g5/v8/bm/core/IBmTransaction;"; //$NON-NLS-1$
        private static final String MONITOR = "Lorg/eclipse/core/runtime/IProgressMonitor;"; //$NON-NLS-1$
        private static final String REQUEST = PROJECT + "Ljava/util/Set;Ljava/util/Collection;"; //$NON-NLS-1$

        private final FrameworkLog log;
        private final String bundleId;
        private final java.util.concurrent.atomic.AtomicLong eventIds = new java.util.concurrent.atomic.AtomicLong();
        private final MassChanges massChanges = new MassChanges(this);
        /** Отложенное остановкой пользователя ({@code DerivedCheckCancellation}) идёт тем же предложением. */
        static volatile CheckRecomputeNotifications instance;

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
            instance = diagnostics;
            System.getProperties().put(CALLBACK, (Consumer<Object[]>) diagnostics::record);
            System.getProperties().put(CALLBACK + ".gate", (Function<Object[], Object>) diagnostics.massChanges::gate); //$NON-NLS-1$
            DeferredCheckResults results = new DeferredCheckResults(context, diagnostics);
            if (results.load())
            {
                System.getProperties().put(CALLBACK + ".defer", (Consumer<Object[]>) results::defer);
                System.getProperties().put(CALLBACK + ".settings", (Consumer<Object[]>) results::deferSettings);
                System.getProperties().put(CALLBACK + ".results", (Function<Object[], Object>) results::access);
                System.getProperties().put(CALLBACK + ".full", //$NON-NLS-1$
                    (java.util.function.Predicate<Object[]>) results::deferFull);
            }
            context.registerService(WeavingHook.class, diagnostics, null);
            diagnostics.write("installed; earlyBundle=" + context.getBundle().getVersion() //$NON-NLS-1$
                + "; waiting for CheckScheduler, ProjectCheckMetadataManager, DerivedDataManager, CheckDeactivationController", null); //$NON-NLS-1$
        }

        @Override
        public void weave(WovenClass wovenClass)
        {
            String name = wovenClass.getClassName();
            if (wovenClass.getState() != WovenClass.TRANSFORMING
                || !(SCHEDULER.equals(name) || METADATA.equals(name) || DERIVED.equals(name) || CONTROLLER.equals(name)))
                return;
            try
            {
                ClassReader reader = new ClassReader(wovenClass.getBytes());
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] matches = {0};
                int[] deferredCalls = {0};
                int[] settingsCalls = {0};
                int[] gateCalls = {0};
                int[] fullCalls = {0};
                boolean deferFull = SCHEDULER.equals(name)
                    && System.getProperties().containsKey(CALLBACK + ".full"); //$NON-NLS-1$
                boolean deferSettings = SCHEDULER.equals(name)
                    && System.getProperties().containsKey(CALLBACK + ".settings"); //$NON-NLS-1$
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
                        if (deferSettings && "onChange".equals(method) //$NON-NLS-1$
                            && ("(" + PROJECT + "Ljava/util/Collection;)V").equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                            return new MethodVisitor(Opcodes.ASM9, visitor)
                            {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String called,
                                    String calledDescriptor, boolean isInterface)
                                {
                                    // Перепроверка по изменённым настройкам (CheckScheduler$1) — только с
                                    // разрешения пользователя; снятие маркеров и смена важности штатные.
                                    if (opcode == Opcodes.INVOKEINTERFACE
                                        && "com/_1c/g5/v8/bm/integration/IBmModel".equals(owner) //$NON-NLS-1$
                                        && "execute".equals(called) //$NON-NLS-1$
                                        && "(Lcom/_1c/g5/v8/bm/integration/IBmTask;)Ljava/lang/Object;".equals(calledDescriptor)) //$NON-NLS-1$
                                    {
                                        settingsCalls[0]++;
                                        super.visitVarInsn(Opcodes.ASTORE, 9);
                                        super.visitVarInsn(Opcodes.ASTORE, 8);
                                        EarlyWeaving.emitGetProperty(this, CALLBACK + ".settings", "java/util/function/Consumer"); //$NON-NLS-1$ //$NON-NLS-2$
                                        super.visitLdcInsn(4);
                                        super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                        int[] sources = {8, 9, 1, 3}; // модель, задача, проект, проверки
                                        for (int i = 0; i < sources.length; i++)
                                        {
                                            super.visitInsn(Opcodes.DUP);
                                            super.visitLdcInsn(i);
                                            super.visitVarInsn(Opcodes.ALOAD, sources[i]);
                                            super.visitInsn(Opcodes.AASTORE);
                                        }
                                        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", //$NON-NLS-1$
                                            "accept", "(Ljava/lang/Object;)V", true); //$NON-NLS-1$ //$NON-NLS-2$
                                        super.visitInsn(Opcodes.ACONST_NULL); // результат execute снимает штатный pop
                                        return;
                                    }
                                    super.visitMethodInsn(opcode, owner, called, calledDescriptor, isInterface);
                                }
                            };
                        String event = event(name, method, descriptor);
                        if (event == null || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                            return visitor;
                        matches[0]++;
                        if (CONTROLLER.equals(name))
                            return new MethodVisitor(Opcodes.ASM9, visitor)
                            {
                                /**
                                 * Каждый ireturn: результат проходит через шлюз Комфорта —
                                 * {исходный Boolean, контроллер, контекст, причина, контекст выполнения}.
                                 * Без ветвлений: кадры стека метода не меняются.
                                 */
                                @Override
                                public void visitInsn(int opcode)
                                {
                                    if (opcode == Opcodes.IRETURN)
                                    {
                                        gateCalls[0]++;
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", //$NON-NLS-1$ //$NON-NLS-2$
                                            "(Z)Ljava/lang/Boolean;", false); //$NON-NLS-1$
                                        EarlyWeaving.emitGetProperty(this, CALLBACK + ".gate", "java/util/function/Function"); //$NON-NLS-1$ //$NON-NLS-2$
                                        super.visitInsn(Opcodes.SWAP);
                                        super.visitLdcInsn(5);
                                        super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                        super.visitInsn(Opcodes.DUP_X1);
                                        super.visitInsn(Opcodes.SWAP);
                                        super.visitLdcInsn(0);
                                        super.visitInsn(Opcodes.SWAP);
                                        super.visitInsn(Opcodes.AASTORE);
                                        for (int i = 1; i < 5; i++)
                                        {
                                            super.visitInsn(Opcodes.DUP);
                                            super.visitLdcInsn(i);
                                            super.visitVarInsn(Opcodes.ALOAD, i - 1);
                                            super.visitInsn(Opcodes.AASTORE);
                                        }
                                        super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", //$NON-NLS-1$
                                            "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$
                                        super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean"); //$NON-NLS-1$
                                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", //$NON-NLS-1$ //$NON-NLS-2$
                                            "()Z", false); //$NON-NLS-1$
                                    }
                                    super.visitInsn(opcode);
                                }
                            };
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                // До запуска фоновой работы и ожидания Future.get(): поздний шлюз
                                // контекстов сюда ещё не дошёл (дедлок при перезапуске расширения, #972).
                                if (deferFull && "full-check-scope".equals(event)) //$NON-NLS-1$
                                {
                                    EarlyWeaving.emitGetProperty(this, CALLBACK + ".full", "java/util/function/Predicate"); //$NON-NLS-1$ //$NON-NLS-2$
                                    super.visitLdcInsn(4);
                                    super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitInsn(Opcodes.ICONST_0);
                                    super.visitLdcInsn(event);
                                    super.visitInsn(Opcodes.AASTORE);
                                    for (int i = 1; i < 4; i++)
                                    {
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitLdcInsn(i);
                                        super.visitVarInsn(Opcodes.ALOAD, i);
                                        super.visitInsn(Opcodes.AASTORE);
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", //$NON-NLS-1$
                                        "test", "(Ljava/lang/Object;)Z", true); //$NON-NLS-1$ //$NON-NLS-2$
                                    Label original = new Label();
                                    super.visitJumpInsn(Opcodes.IFEQ, original);
                                    super.visitInsn(Opcodes.RETURN);
                                    super.visitLabel(original);
                                    super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                    fullCalls[0]++;
                                }
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

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String method,
                                String descriptor, boolean isInterface)
                            {
                                // Единственный заказ по изменению поставщиков; clearance остаётся штатным.
                                if (METADATA.equals(name) && opcode == Opcodes.INVOKEINTERFACE
                                    && owner.equals("com/e1c/g5/v8/dt/check/ICheckScheduler")
                                    && method.equals("scheduleValidation")
                                    && descriptor.equals("(" + REQUEST + MONITOR + ")V")
                                    && System.getProperties().containsKey(CALLBACK + ".defer"))
                                {
                                    deferredCalls[0]++;
                                    // Сохраняем пять исходных аргументов, включая receiver, для fail-open.
                                    for (int i = 4; i >= 0; i--)
                                        super.visitVarInsn(Opcodes.ASTORE, 5 + i);
                                    EarlyWeaving.emitGetProperty(this, CALLBACK + ".defer", "java/util/function/Consumer");
                                    super.visitLdcInsn(6);
                                    super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                                    for (int i = 0; i < 5; i++)
                                    {
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitLdcInsn(i);
                                        super.visitVarInsn(Opcodes.ALOAD, 5 + i);
                                        super.visitInsn(Opcodes.AASTORE);
                                    }
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitLdcInsn(5);
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitFieldInsn(Opcodes.GETFIELD, name.replace('.', '/'),
                                        "recomputationTasks", "Ljava/util/Map;");
                                    super.visitInsn(Opcodes.AASTORE);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer",
                                        "accept", "(Ljava/lang/Object;)V", true);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                            }
                        };
                    }
                }, 0);
                int expected = SCHEDULER.equals(name) ? 3 : 1;
                String status = "class=" + name + "; bundle=" //$NON-NLS-1$ //$NON-NLS-2$
                    + wovenClass.getBundleWiring().getBundle().getVersion()
                    + "; methods=" + matches[0] + "/" + expected; //$NON-NLS-1$ //$NON-NLS-2$
                boolean deferCompatible = !METADATA.equals(name)
                    || !System.getProperties().containsKey(CALLBACK + ".defer") || deferredCalls[0] == 1;
                deferCompatible &= !deferSettings || settingsCalls[0] == 1;
                deferCompatible &= !deferFull || fullCalls[0] == 1;
                status += "; fullCalls=" + fullCalls[0]; //$NON-NLS-1$
                // В requiresDeactivation 6.0.100 четыре ireturn; без шлюза вплетать нечего
                deferCompatible &= !CONTROLLER.equals(name) || gateCalls[0] > 0;
                status += "; deferredCalls=" + deferredCalls[0] + "; settingsCalls=" + settingsCalls[0] //$NON-NLS-1$ //$NON-NLS-2$
                    + "; gateCalls=" + gateCalls[0]; //$NON-NLS-1$
                if (matches[0] == expected && metadataField[0] && deferCompatible)
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

        /** Долговечные пометки по проекту и полному UID; доступ UI через JDK Function. */
        private static final class DeferredCheckResults
        {
            private final java.io.File file;
            private final CheckRecomputeNotifications diagnostics;
            private java.util.Properties state = new java.util.Properties();

            DeferredCheckResults(BundleContext context, CheckRecomputeNotifications diagnostics)
            {
                file = context.getDataFile("deferred-check-results.properties");
                this.diagnostics = diagnostics;
            }

            boolean load()
            {
                try
                {
                    if (file == null)
                        throw new java.io.IOException("Bundle data area unavailable");
                    if (file.isFile())
                        try (var input = new java.io.FileInputStream(file)) { state.load(input); }
                    return true;
                }
                catch (Exception error)
                {
                    return false;
                }
            }

            private void save(java.util.Properties next) throws java.io.IOException
            {
                file.getParentFile().mkdirs();
                // Durable before suppressing the original request; retain previous file on write failure.
                byte[] previous = file.isFile() ? java.nio.file.Files.readAllBytes(file.toPath()) : null;
                try (var output = new java.io.FileOutputStream(file))
                {
                    next.store(output, "Deferred validation results");
                    output.getFD().sync();
                }
                catch (java.io.IOException error)
                {
                    if (previous != null)
                        try { java.nio.file.Files.write(file.toPath(), previous); }
                        catch (java.io.IOException restore) { error.addSuppressed(restore); }
                    throw error;
                }
                state = next;
            }

            synchronized Object access(Object[] args)
            {
                String prefix = args[1] + "\n";
                Map<String, String> values = new java.util.HashMap<>();
                for (String key : state.stringPropertyNames())
                    if (key.startsWith(prefix))
                        values.put(key.substring(prefix.length()), state.getProperty(key));
                if ("snapshot".equals(args[0]))
                    return Map.copyOf(values);
                if ("complete".equals(args[0]) && args[2] instanceof Map<?, ?> completed)
                {
                    java.util.Properties next = new java.util.Properties();
                    next.putAll(state);
                    completed.forEach((uid, token) -> next.remove(prefix + uid, token));
                    try { save(next); }
                    catch (java.io.IOException error)
                    {
                        throw new IllegalStateException(error);
                    }
                }
                return null;
            }

            /** Любой автоматический полный пересчёт — в постоянное отложенное, до сбора контекстов. */
            synchronized boolean deferFull(Object[] event)
            {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                // Разрешение относится к самой ручной команде, а не к двухминутному окну:
                // смена совместимости в том же проекте во время окна остаётся автоматической.
                for (StackTraceElement frame : stack)
                {
                    String type = frame.getClassName();
                    if (type.equals("tormozit.ComfortCheckRecompute") //$NON-NLS-1$
                        || type.startsWith("tormozit.ValidationChecksFilterHook$") //$NON-NLS-1$
                        || type.equals("com._1c.g5.v8.dt.internal.ui.validation.development.ScheduleValidationHandler")) //$NON-NLS-1$
                    {
                        return false;
                    }
                }
                try
                {
                    java.util.Properties next = new java.util.Properties();
                    next.putAll(state);
                    String token = java.util.UUID.randomUUID().toString();
                    var checkIds = new java.util.LinkedHashSet<String>();
                    for (Object uid : (Collection<?>) event[2])
                    {
                        String id = (String) uid.getClass().getMethod("getCheckId").invoke(uid); //$NON-NLS-1$
                        String contributor = (String) uid.getClass().getMethod("getContributorId").invoke(uid); //$NON-NLS-1$
                        next.setProperty(event[1] + "\n" + contributor + "\t" + id, token); //$NON-NLS-1$ //$NON-NLS-2$
                        checkIds.add(id);
                    }
                    save(next);
                    String reason = notificationReason(event, stack);
                    diagnostics.publish(new Object[] { "deferred-full-checks", //$NON-NLS-1$
                        "Отложен полный пересчёт проверок (" + checkIds.size() //$NON-NLS-1$
                            + ").\nПричина запуска: " + reason //$NON-NLS-1$
                            + ".\nРезультаты неактуальны. Можно обновить в списке «Проблемы».", //$NON-NLS-1$
                        Long.valueOf(System.currentTimeMillis()), event[1], java.util.Set.copyOf(checkIds) });
                    return true;
                }
                catch (Exception error)
                {
                    // Несохранённую работу не терять и не запускать опасный полный пересчёт молча.
                    throw new IllegalStateException("Cannot defer full validation for " + event[1], error); //$NON-NLS-1$
                }
            }

            synchronized void defer(Object[] args)
            {
                try
                {
                    java.util.Properties next = new java.util.Properties();
                    next.putAll(state);
                    String token = java.util.UUID.randomUUID().toString();
                    var contributors = new java.util.LinkedHashSet<String>();
                    for (var entry : ((Map<?, ?>) args[5]).entrySet())
                    {
                        String prefix = args[1] + "\n" + entry.getKey() + "\t";
                        Object delta = entry.getValue();
                        for (String field : List.of("removed", "added", "updated"))
                        {
                            var member = delta.getClass().getDeclaredField(field);
                            member.setAccessible(true);
                            for (Object check : (Collection<?>) member.get(delta))
                            {
                                if ("removed".equals(field))
                                    next.remove(prefix + check);
                                else
                                {
                                    next.setProperty(prefix + check, token);
                                    contributors.add(String.valueOf(entry.getKey()));
                                }
                            }
                        }
                    }
                    save(next);
                    diagnostics.publish(new Object[] { "deferred-checks",
                        "Изменены проверки " + String.join(", ", contributors)
                            + ".\nРезультаты неактуальны. Можно обновить в списке «Проверки».",
                        Long.valueOf(System.currentTimeMillis()), args[1],
                        java.util.Set.copyOf((Collection<?>) args[2]) });
                }
                catch (Exception error)
                {
                    // API подтверждён ProjectCheckMetadataManager.javap.txt; типы EDT не грузим при старте.
                    try
                    {
                        ClassLoader loader = args[0].getClass().getClassLoader();
                        Class<?> scheduler = loader.loadClass("com.e1c.g5.v8.dt.check.ICheckScheduler");
                        scheduler.getMethod("scheduleValidation", loader.loadClass("org.eclipse.core.resources.IProject"),
                            java.util.Set.class, Collection.class, loader.loadClass("org.eclipse.core.runtime.IProgressMonitor"))
                            .invoke(args[0], args[1], args[2], args[3], args[4]);
                    }
                    catch (ReflectiveOperationException fallback)
                    {
                        throw new IllegalStateException(fallback);
                    }
                }
            }

            /** CheckScheduler.onChange: {модель, задача перепроверки, проект, Set&lt;CheckUid&gt;}. */
            synchronized void deferSettings(Object[] args)
            {
                try
                {
                    java.util.Properties next = new java.util.Properties();
                    next.putAll(state);
                    String token = java.util.UUID.randomUUID().toString();
                    var checkIds = new java.util.LinkedHashSet<String>();
                    for (Object uid : (Collection<?>) args[3])
                    {
                        String id = (String) uid.getClass().getMethod("getCheckId").invoke(uid);
                        String contributor = (String) uid.getClass().getMethod("getContributorId").invoke(uid);
                        next.setProperty(args[2] + "\n" + contributor + "\t" + id, token);
                        checkIds.add(id);
                    }
                    save(next);
                    diagnostics.publish(new Object[] { "deferred-checks",
                        "Изменены настройки проверок (" + checkIds.size()
                            + ").\nРезультаты неактуальны. Можно обновить в списке «Проверки».",
                        Long.valueOf(System.currentTimeMillis()), args[2], java.util.Set.copyOf(checkIds) });
                }
                catch (Exception error)
                {
                    // Штатный путь: IBmModel.execute(task); типы видны загрузчику бандла проверок.
                    try
                    {
                        ClassLoader loader = args[1].getClass().getClassLoader();
                        loader.loadClass("com._1c.g5.v8.bm.integration.IBmModel")
                            .getMethod("execute", loader.loadClass("com._1c.g5.v8.bm.integration.IBmTask"))
                            .invoke(args[0], args[1]);
                    }
                    catch (ReflectiveOperationException fallback)
                    {
                        throw new IllegalStateException(fallback);
                    }
                }
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
            if (CONTROLLER.equals(type) && "requiresDeactivation".equals(method) //$NON-NLS-1$
                && ("(Lcom/_1c/g5/v8/derived/context/IObjectDerivedDataContext;Ljava/lang/Object;" //$NON-NLS-1$
                    + "Lcom/_1c/g5/v8/derived/IAsyncComputationExecutionContext;)Z").equals(descriptor)) //$NON-NLS-1$
                return "changed-context"; //$NON-NLS-1$
            return null;
        }

        private void record(Object[] event)
        {
            try
            {
                String phase = (String) event[0];
                // Полный пересчёт — новая работа: «Стоп» прежней больше не действует.
                if (DerivedCheckCancellation.instance != null
                    && ("derived-recompute-all".equals(phase) || "full-check-scope".equals(phase))) //$NON-NLS-1$ //$NON-NLS-2$
                    DerivedCheckCancellation.instance.reset(phase);
                String text = phase + "; thread=" + Thread.currentThread().getName(); //$NON-NLS-1$
                if ("validation-request".equals(phase) || "full-check-scope".equals(phase)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    text += "; project=" + event[1] + "; checks=" + event[2]; //$NON-NLS-1$ //$NON-NLS-2$
                    if ("validation-request".equals(phase)) //$NON-NLS-1$
                    {
                        Collection<?> objects = (Collection<?>) event[3];
                        text += "; objects=" + (objects == null ? "null" : objects.size()) //$NON-NLS-1$ //$NON-NLS-2$
                            + "; projectScope=" + (objects != null && objects.isEmpty()); //$NON-NLS-1$
                        massChanges.schedulerInitiator(event[1], Thread.currentThread().getStackTrace());
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
                            ? "Назначен пересчет " + checkCount(event[2]) + " проверок проекта " //$NON-NLS-1$ //$NON-NLS-2$
                                + projectName(event[1])
                            : "Запрошен полный пересчёт производных данных") + ".\nПричина: " + reason, //$NON-NLS-1$ //$NON-NLS-2$
                        Long.valueOf(System.currentTimeMillis()), event[1]};
                    publish(notification);
                }
            }
            catch (Throwable error)
            {
                write("diagnostic callback failed; event=" + event[0], error); //$NON-NLS-1$
            }
        }

        private void publish(Object[] notification)
        {
            try
            {
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
                        // Отложенные шлюзом контексты — своя очередь проекта, списки складываются
                        boolean gate = notification.length > 4 && notification[4] instanceof List<?>;
                        pending.compute(gate ? List.of(notification[3], "gate") : notification[3], (key, previous) -> //$NON-NLS-1$
                            gate ? MassChanges.mergeOffers(previous, notification)
                                : previous != null && previous.length > 4 && notification.length <= 4 ? previous : notification);
                    }
                }
                if (consumer != null)
                    consumer.accept(notification);
            }
            catch (Throwable error)
            {
                // Отказ уведомления не должен запускать уже отложенную перепроверку.
            }
        }

        /** Пустой набор в scheduleScopedFullRebuild — все проверки (CheckScheduler.internalScheduleValidation). */
        private static String checkCount(Object checks)
        {
            return checks instanceof Collection<?> set && !set.isEmpty()
                ? String.valueOf(set.size()) : "всех"; //$NON-NLS-1$
        }

        /** IProject.toString — «P/Имя»; типы ресурсов в раннем бандле не грузим. */
        private static String projectName(Object project)
        {
            String text = String.valueOf(project);
            return text.startsWith("P/") ? text.substring(2) : text; //$NON-NLS-1$
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
                if (type.equals(SCHEDULER + "$CompatibilityChangeListener")) //$NON-NLS-1$
                    return "Изменение совместимости конфигурации"; //$NON-NLS-1$
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

        /**
         * Шлюз пересчёта проверок: ответ штатного {@code CheckDeactivationController.requiresDeactivation}
         * проходит через {@link #gate}. Причина изменения не важна. Открытые в редакторах модули и
         * объекты пересчитываются сразу и в объём не входят. Без ручного запуска пачка больше
         * {@link #THRESHOLD} изменённых объектов пересчитывается только с согласия пользователя:
         * первые объекты пачки идут штатно, контексты остальных отключаются, как при «Отключить
         * массовые проверки», и запоминаются. Когда контексты перестают приходить, основной бандл
         * сохраняет их в постоянный список и предлагает «Запустить» отложенное; пропущенный шлюзом
         * контекст снимается со списка.
         */
        private static final class MassChanges
        {
            private static final int THRESHOLD = 100;
            /** Сколько ждать следующих контекстов той же операции. */
            private static final long QUIET_MS = 2_000L;
            /** Основной бандл: идёт ли ручной запуск проверки проекта ({@code Predicate<IProject>}). */
            private static final String MANUAL = CALLBACK + ".manual"; //$NON-NLS-1$
            /** Основной бандл: открыт ли объект контекста в редакторе ({@code Predicate<путь | Long>}). */
            private static final String OPEN = CALLBACK + ".open"; //$NON-NLS-1$
            /** Основной бандл: контекст пропущен ({@code BiConsumer<IProject, «сегмент\nобъект»>}). */
            private static final String PASSED = CALLBACK + ".passed"; //$NON-NLS-1$
            /** Короткие названия служб-причин для текста уведомления. */
            private static final Map<String, String> REASONS = Map.of(
                "COMPARISON_MANAGER", "объединение", //$NON-NLS-1$
                "REFACTORING_SERVICE", "рефакторинг", //$NON-NLS-1$
                "SYNCHRONIZATION_MANAGER", "изменение файлов", //$NON-NLS-1$
                "CHECK_SCHEDULER", "планировщик проверок", //$NON-NLS-1$
                // DerivedDataContextManager: служба-причина — сам класс IDerivedDataManager
                "class:com._1c.g5.v8.derived.IDerivedDataManager", "производные данные"); //$NON-NLS-1$

            private final CheckRecomputeNotifications owner;
            /** Контекст выполнения проекта → пачка; под монитором this. */
            private final Map<Object, Batch> batches = new java.util.HashMap<>();
            /** Контекст выполнения → IProject; контекстов по одному на проект. */
            private final Map<Object, Object> projects = new java.util.concurrent.ConcurrentHashMap<>();
            /** IProject → чем вызван последний запрос к планировщику проверок (CHECK_SCHEDULER). */
            private final Map<Object, String> schedulerInitiators = new java.util.concurrent.ConcurrentHashMap<>();
            private final Map<String, java.util.Optional<java.lang.reflect.Method>> methods =
                new java.util.concurrent.ConcurrentHashMap<>();
            private java.util.concurrent.ScheduledExecutorService timer;

            MassChanges(CheckRecomputeNotifications owner)
            {
                this.owner = owner;
            }

            /**
             * {@code {исходный ответ, контроллер, контекст, причина, контекст выполнения}} → итоговый
             * {@code Boolean}; {@code true} — контекст отключён.
             */
            Object gate(Object[] args)
            {
                try
                {
                    Object result = decide(args);
                    // Прошедший контекст основной бандл снимает с постоянного списка отложенных
                    if (Boolean.FALSE.equals(result) && args[4] != null)
                        passed(project(args[4]), args[2]);
                    return result;
                }
                catch (Throwable error)
                {
                    owner.write("check gate failed; cause=" + args[3] + "; error=" + error, null); //$NON-NLS-1$ //$NON-NLS-2$
                    return args[0];
                }
            }

            private void passed(Object project, Object context) throws ReflectiveOperationException
            {
                @SuppressWarnings("unchecked")
                var consumer = (java.util.function.BiConsumer<Object, Object>) System.getProperties().get(PASSED);
                Object objectId = consumer != null && project != null ? call(context, "getObjectId") : null; //$NON-NLS-1$
                if (objectId != null)
                    consumer.accept(project, call(context, "getSegmentId") + "\n" + objectId); //$NON-NLS-1$ //$NON-NLS-2$
            }

            private Object decide(Object[] args) throws ReflectiveOperationException
            {
                Object original = args[0];
                // Отключён штатным «Отключить массовые проверки» — тоже в предложение «Запустить»
                boolean deactivated = Boolean.TRUE.equals(original);
                if (args[4] == null)
                    return original;
                Object project = project(args[4]);
                // Ручной запуск и открытое в редакторе пересчитываются сразу, в том числе вопреки
                // штатному «Отключить массовые проверки», и в объём пачки не входят
                if (isManual(project))
                {
                    // Ручной запуск («Запустить» отложенного тоже) снимает остановку пользователем
                    if (DerivedCheckCancellation.instance != null)
                        DerivedCheckCancellation.instance.resume(project);
                    return Boolean.FALSE;
                }
                Object context = args[2];
                Object objectId = call(context, "getObjectId"); //$NON-NLS-1$
                if (objectId == null)
                    return original;
                if (test(OPEN, objectId))
                    return Boolean.FALSE;
                String segment = String.valueOf(call(context, "getSegmentId")); //$NON-NLS-1$
                String cause = causeName(args[3]);
                synchronized (this)
                {
                    Batch batch = batches.get(args[4]);
                    if (batch == null)
                    {
                        batch = new Batch(project, args[1]);
                        batches.put(args[4], batch);
                        schedule(args[4], QUIET_MS);
                    }
                    batch.last = System.currentTimeMillis();
                    batch.calls++;
                    batch.causes.merge(cause, 1, Integer::sum);
                    batch.segments.merge(segment, 1, Integer::sum);
                    // Порог — по объектам: модули объекта или формы — один объект с его BM-объектом.
                    // Сопоставить BM-идентификатор с путём здесь нечем, поэтому объём — большее из
                    // двух чисел: верхних BM-объектов и объектов по путям модулей.
                    boolean bm = !(objectId instanceof String);
                    String key = bm ? "bm:" + objectId : objectKey((String) objectId); //$NON-NLS-1$
                    if (!deactivated && batch.passed.contains(key))
                        return original;
                    if (!deactivated && !batch.blockedIds.contains(key)
                        && Math.max(batch.passedBm + (bm ? 1 : 0), batch.passed.size() - batch.passedBm + (bm ? 0 : 1)) <= THRESHOLD)
                    {
                        batch.passed.add(key);
                        if (bm)
                            batch.passedBm++;
                        return original;
                    }
                    batch.blockedIds.add(key);
                    batch.blocked.putIfAbsent(segment + '\n' + objectId,
                        new Object[] {objectId, call(context, "getEClass"), segment, context.getClass()}); //$NON-NLS-1$
                    return Boolean.TRUE;
                }
            }

            /**
             * {@code /Проект/src/Тип/Имя/…} → папка объекта; форма — отдельный объект
             * ({@code …/Имя/Forms/Форма}), корень конфигурации — {@code /Проект/src/Configuration}.
             */
            static String objectKey(String path)
            {
                String[] parts = path.split("/"); //$NON-NLS-1$
                int depth = parts.length > 3 && "Configuration".equals(parts[3]) ? 4 //$NON-NLS-1$
                    : parts.length > 7 && "Forms".equals(parts[5]) ? 7 : 5; //$NON-NLS-1$
                return String.join("/", java.util.Arrays.copyOf(parts, Math.min(depth, parts.length))); //$NON-NLS-1$
            }

            /**
             * Запрос к {@code CheckScheduler.scheduleValidation}: запоминает подтверждённый инициатор.
             * Глубокий анализ модулей после сборки Xtext; неизвестный инициатор не угадываем.
             */
            void schedulerInitiator(Object project, StackTraceElement[] stack)
            {
                if (project == null)
                    return;
                boolean analysis = false;
                boolean fullBuild = false;
                for (StackTraceElement frame : stack)
                {
                    String type = frame.getClassName();
                    analysis |= type.endsWith(".BslDeepAnalisysService") //$NON-NLS-1$
                        && frame.getMethodName().equals("scheduleDeepAnalisys"); //$NON-NLS-1$
                    fullBuild |= type.endsWith("XtextBuilder") && frame.getMethodName().equals("fullBuild"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (analysis)
                    schedulerInitiators.put(project, fullBuild
                        ? "полная сборка проекта" : "сборка изменённых модулей"); //$NON-NLS-1$ //$NON-NLS-2$
                else
                    schedulerInitiators.remove(project);
            }

            /** Отложенное до показа предложения складывается: {@code event[4]} — список контекстов. */
            static Object[] mergeOffers(Object[] previous, Object[] next)
            {
                if (previous == null || previous.length <= 4 || !(previous[4] instanceof List<?> earlier))
                    return next;
                List<Object> all = new java.util.ArrayList<>(earlier);
                all.addAll((List<?>) next[4]);
                java.util.Set<String> reasons = new java.util.LinkedHashSet<>(List.of(String.valueOf(previous[1]).split(", "))); //$NON-NLS-1$
                reasons.addAll(List.of(String.valueOf(next[1]).split(", "))); //$NON-NLS-1$
                Object[] merged = next.clone();
                merged[1] = String.join(", ", reasons); //$NON-NLS-1$
                merged[4] = List.copyOf(all);
                return merged;
            }

            private boolean isManual(Object project)
            {
                return test(MANUAL, project);
            }

            /** Предикат основного бандла; до его старта — {@code false}. */
            private static boolean test(String property, Object value)
            {
                @SuppressWarnings("unchecked")
                var predicate = (java.util.function.Predicate<Object>) System.getProperties().get(property);
                return predicate != null && value != null && predicate.test(value);
            }

            private Object project(Object executionContext) throws ReflectiveOperationException
            {
                Object project = projects.get(executionContext);
                if (project == null)
                {
                    project = call(call(executionContext, "getDtProject"), "getWorkspaceProject"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (project != null)
                        projects.put(executionContext, project);
                }
                return project;
            }

            private String causeName(Object cause) throws ReflectiveOperationException
            {
                Object service = serviceId(cause);
                // Причиной бывает и класс службы (IDerivedDataManager.class)
                return service instanceof String text ? text
                    : service instanceof Class<?> type ? "class:" + type.getName() //$NON-NLS-1$
                    : service == null ? "null" : "class:" + service.getClass().getName(); //$NON-NLS-1$ //$NON-NLS-2$
            }

            private synchronized void schedule(Object key, long delayMs)
            {
                if (timer == null)
                    timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable ->
                    {
                        Thread thread = new Thread(runnable, "Комфорт: счёт изменённых объектов проверок"); //$NON-NLS-1$
                        thread.setDaemon(true);
                        return thread;
                    });
                timer.schedule(() ->
                {
                    try
                    {
                        check(key);
                    }
                    catch (Throwable error)
                    {
                        owner.write("mass-change check failed", error); //$NON-NLS-1$
                    }
                }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }

            private void check(Object key)
            {
                Batch done;
                synchronized (this)
                {
                    Batch batch = batches.get(key);
                    if (batch == null)
                        return;
                    long wait = batch.last + QUIET_MS - System.currentTimeMillis();
                    if (wait > 0)
                    {
                        schedule(key, wait);
                        return;
                    }
                    batches.remove(key);
                    done = batch;
                }
                report(done);
            }

            private void report(Batch batch)
            {
                int passed = batch.passed.size();
                int blocked = batch.blockedIds.size();
                String text = "check-gate-batch; project=" + batch.project + "; passed=" + passed //$NON-NLS-1$ //$NON-NLS-2$
                    + "; blocked=" + blocked + "; calls=" + batch.calls + "; causes=" + batch.causes //$NON-NLS-1$ //$NON-NLS-2$
                    + "; segments=" + batch.segments + "; durationMs=" + (batch.last - batch.first) //$NON-NLS-1$ //$NON-NLS-2$
                    + "; thread=" + batch.thread; //$NON-NLS-1$
                if (blocked == 0 || batch.project == null)
                    return;
                java.util.Set<String> reasons = new java.util.LinkedHashSet<>();
                for (String cause : batch.causes.keySet())
                {
                    String initiator = "CHECK_SCHEDULER".equals(cause) //$NON-NLS-1$
                        ? schedulerInitiators.get(batch.project) : null;
                    reasons.add(initiator != null ? initiator
                        : REASONS.getOrDefault(cause, cause.substring(cause.lastIndexOf('.') + 1)));
                }
                // Текст с числом объектов строит основной бандл: объект модуля и BM-объект — один объект.
                // При «Отключить массовые проверки» откладывание ожидаемо — предложение скрывается само.
                owner.publish(new Object[] {PREFIX + text, String.join(", ", reasons), //$NON-NLS-1$
                    Long.valueOf(System.currentTimeMillis()), batch.project, List.copyOf(batch.blocked.values()),
                    Boolean.valueOf(massiveChecksDisabled(batch.controller))});
            }

            /** Значение флажка, которое контроллер читает при первом вызове (поле {@code currentDeactivationModeValue}). */
            private boolean massiveChecksDisabled(Object controller)
            {
                try
                {
                    var mode = controller.getClass().getDeclaredField("currentDeactivationModeValue"); //$NON-NLS-1$
                    mode.setAccessible(true);
                    return Boolean.TRUE.equals(mode.get(controller));
                }
                catch (ReflectiveOperationException | RuntimeException e)
                {
                    owner.write("massive checks mode unknown: " + e, null); //$NON-NLS-1$
                    return false;
                }
            }

            /** CheckDeactivationController.requiresDeactivation: BmEvent.getServiceId либо сама причина. */
            private Object serviceId(Object cause) throws ReflectiveOperationException
            {
                if (cause == null || cause instanceof String)
                    return cause;
                java.lang.reflect.Method method = method(cause, "getServiceId"); //$NON-NLS-1$
                return method != null ? method.invoke(cause) : cause;
            }

            private Object call(Object target, String name) throws ReflectiveOperationException
            {
                java.lang.reflect.Method method = target != null ? method(target, name) : null;
                return method != null ? method.invoke(target) : null;
            }

            /** Открытый метод без параметров; классы реализаций EDT бывают непубличными. */
            private java.lang.reflect.Method method(Object target, String name)
            {
                return methods.computeIfAbsent(target.getClass().getName() + '#' + name, key ->
                {
                    try
                    {
                        java.lang.reflect.Method method = target.getClass().getMethod(name);
                        method.setAccessible(true);
                        return java.util.Optional.of(method);
                    }
                    catch (ReflectiveOperationException | RuntimeException e)
                    {
                        return java.util.Optional.empty();
                    }
                }).orElse(null);
            }

            private static final class Batch
            {
                final Object project;
                final Object controller;
                /** Объекты ({@code bm:id} или папка объекта), пересчитанные штатно; их остальные контексты тоже. */
                final java.util.Set<Object> passed = new java.util.HashSet<>();
                /** Сколько из {@link #passed} — BM-объекты. */
                int passedBm;
                final java.util.Set<Object> blockedIds = new java.util.HashSet<>();
                /** «сегмент\nобъект» → {объект, EClass, сегмент, класс контекста} для «Запустить». */
                final Map<String, Object[]> blocked = new java.util.LinkedHashMap<>();
                final Map<String, Integer> causes = new java.util.TreeMap<>();
                final Map<String, Integer> segments = new java.util.TreeMap<>();
                final long first = System.currentTimeMillis();
                final String thread = Thread.currentThread().getName();
                long last;
                int calls;

                Batch(Object project, Object controller)
                {
                    this.project = project;
                    this.controller = controller;
                }
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
        private static final String BSL_HIGHLIGHTING =
            "com._1c.g5.v8.dt.bsl.ui.syntaxcoloring.BslHighlightingReconciler"; //$NON-NLS-1$
        private static final String XTEXT_DOCUMENT = "org.eclipse.xtext.ui.editor.model.XtextDocument"; //$NON-NLS-1$
        private static final String BSL_XTEXT_DOCUMENT = "com._1c.g5.v8.dt.bsl.ui.editor.BslXtextDocument"; //$NON-NLS-1$
        private static final String PROP_VALIDATION_TRACE = "tormozit.bslXtextDoc.validationTrace"; //$NON-NLS-1$
        /** Совпадает с {@code BslXtextDocumentHook.ValidationTrace.PROP_ERROR}: текст исключения из ATHROW. */
        private static final String PROP_VALIDATION_ERROR = "tormozit.bslXtextDoc.validationTrace.error"; //$NON-NLS-1$
        private static final String PROP_MODEL_HIGHLIGHTING = "tormozit.bslHighlighting.modelChanged"; //$NON-NLS-1$
        private static final String DOC_LOCKER =
            "com._1c.g5.v8.dt.bsl.ui.editor.BslXtextDocument$CustomXtextDocumentLocker"; //$NON-NLS-1$
        private static final String PROP_VALIDATION_REENTRY = "tormozit.bslXtextDoc.validationReentry"; //$NON-NLS-1$
        private static final String HANDLY_RECONCILING_WORK =
            "org.eclipse.handly.xtext.ui.editor.HandlyXtextDocument$T2MReconcilingUnitOfWork"; //$NON-NLS-1$
        /** Совпадает с {@code BslXtextDocumentHook.PROP_SKIP_WAIT}. */
        static final String PROP_SKIP_WAIT = "tormozit.bslXtextDoc.skipWaitUpdatingDataModel"; //$NON-NLS-1$
        static final String PROP_SKIP_LOCAL_DERIVED_WAIT = "tormozit.bslXtextDoc.skipLocalDerivedWait"; //$NON-NLS-1$
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
            DCS_LOCAL_STRING_EDITOR, DCS_FIELD_EDITING, DCS_PARAMETER_EDITING, DOC_LOCKER, BSL_HIGHLIGHTING,
            XTEXT_DOCUMENT, BSL_XTEXT_DOCUMENT);

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
            System.getProperties().put(PROP_SKIP_LOCAL_DERIVED_WAIT,
                (java.util.function.Predicate<Object>) AssistReadLock::skipLocalDerivedWait);
            System.getProperties().putIfAbsent(PROP_MODEL_HIGHLIGHTING,
                (java.util.function.BiPredicate<Object, Object>) (reconciler, resource) -> false);
            System.getProperties().putIfAbsent(PROP_VALIDATION_TRACE,
                (java.util.function.BiConsumer<Object, String>) (document, phase) -> {});
            System.getProperties().putIfAbsent(PROP_VALIDATION_ERROR,
                (java.util.function.Consumer<Object>) error -> {});
            System.getProperties().put(PROP_VALIDATION_REENTRY,
                (java.util.function.BiPredicate<Object, Object>) (work, currentJob) ->
                    currentJob != null && work != null && HANDLY_RECONCILING_WORK.equals(work.getClass().getName()));
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
                {
                    System.setProperty(PROP_DOC_LOCKER_PATCH, "error " + t); //$NON-NLS-1$
                    System.setProperty(PROP_VALIDATION_REENTRY + ".patch", "error " + t); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (XTEXT_DOCUMENT.equals(name) || BSL_XTEXT_DOCUMENT.equals(name))
                    System.setProperty(PROP_VALIDATION_TRACE
                        + (BSL_XTEXT_DOCUMENT.equals(name) ? ".bsl.patch" : ".patch"), "error " + t); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
            if (XTEXT_DOCUMENT.equals(className))
                return transformValidationTrace(bytes, false);
            if (BSL_XTEXT_DOCUMENT.equals(className))
                return transformValidationTrace(bytes, true);
            if (DOC_LOCKER.equals(className))
            {
                byte[] locked = transformDocLocker(bytes);
                if (locked == null)
                {
                    System.setProperty(PROP_VALIDATION_REENTRY + ".patch", "skipped: locker patch missing"); //$NON-NLS-1$ //$NON-NLS-2$
                    return null;
                }
                return transformValidationReentry(locked);
            }
            if (BSL_HIGHLIGHTING.equals(className))
                return transformModelHighlighting(bytes);
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

        /** Временная диагностика: только наблюдение, без чтения модели через readOnly. */
        private static byte[] transformValidationTrace(byte[] bytes, boolean bsl)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean trace = "checkAndUpdateAnnotations".equals(name) && "()V".equals(descriptor) //$NON-NLS-1$ //$NON-NLS-2$
                        || "notifyModelListeners".equals(name) && descriptor.equals( //$NON-NLS-1$
                            "(Lorg/eclipse/xtext/resource/XtextResource;)V") //$NON-NLS-1$
                        || "fireDocumentChanged".equals(name) && descriptor.equals( //$NON-NLS-1$
                            "(Lorg/eclipse/jface/text/DocumentEvent;)V"); //$NON-NLS-1$
                    if (mv == null || !trace)
                        return mv;
                    touched[0]++;
                    String phaseName = (bsl ? "bsl." : "") + name; //$NON-NLS-1$ //$NON-NLS-2$
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            emitTrace(phaseName + ".enter"); //$NON-NLS-1$
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ATHROW)
                                emitThrown();
                            if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW)
                                emitTrace(phaseName + (opcode == Opcodes.RETURN ? ".exit" : ".throw")); //$NON-NLS-1$ //$NON-NLS-2$
                            super.visitInsn(opcode);
                        }

                        /**
                         * Стек {@code [throwable]} → {@code [throwable]}: дубликат уходит приёмнику
                         * исключения (основной бандл пишет его текст), сам throwable остаётся для
                         * штатного ATHROW и последующего трейса. Вставка без ветвлений — кадры
                         * стека остаются исходными.
                         */
                        private void emitThrown()
                        {
                            super.visitInsn(Opcodes.DUP);
                            emitGetProperty(this, PROP_VALIDATION_ERROR, "java/util/function/Consumer"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", //$NON-NLS-1$
                                "accept", "(Ljava/lang/Object;)V", true); //$NON-NLS-1$ //$NON-NLS-2$
                        }

                        private void emitTrace(String phase)
                        {
                            emitGetProperty(this, PROP_VALIDATION_TRACE, "java/util/function/BiConsumer"); //$NON-NLS-1$
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitLdcInsn(phase);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;Ljava/lang/Object;)V", true); //$NON-NLS-1$
                        }
                    };
                }
            }, 0);
            int expected = bsl ? 1 : 3;
            boolean complete = touched[0] == expected;
            System.setProperty(PROP_VALIDATION_TRACE + (bsl ? ".bsl.patch" : ".patch"), //$NON-NLS-1$ //$NON-NLS-2$
                (complete ? "woven" : "missing") + " methods=" + touched[0] + " expected=" + expected); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return complete ? writer.toByteArray() : null;
        }

        /**
         * {@link #DOC_LOCKER}: ветвление и обработчик исключений — кадры пересчитываются
         * ({@code COMPUTE_FRAMES}, общий предок — {@code Object}, классы EDT не загружаются), как в
         * {@link #transformFoldingProvider}. Функции ставит {@link #install} до хука, поэтому
         * вплетённый код зовёт их без проверок.
         */
        /** BSL проверяет активность и stamp сам; только тяжёлый вызов родителя переносится в Job. */
        private static byte[] transformModelHighlighting(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            String owner = reader.getClassName();
            String parent = "org/eclipse/xtext/ui/editor/syntaxcoloring/HighlightingReconciler"; //$NON-NLS-1$
            String desc = "(Lorg/eclipse/xtext/resource/XtextResource;Lorg/eclipse/xtext/util/CancelIndicator;)V"; //$NON-NLS-1$
            String helper = "comfort$modelChanged"; //$NON-NLS-1$
            int[] calls = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"modelChanged".equals(name) || !desc.equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String target, String method, String signature,
                            boolean itf)
                        {
                            if (opcode == Opcodes.INVOKESPECIAL && parent.equals(target)
                                && "modelChanged".equals(method) && desc.equals(signature)) //$NON-NLS-1$
                            {
                                calls[0]++;
                                super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, helper, desc, false);
                            }
                            else
                                super.visitMethodInsn(opcode, target, method, signature, itf);
                        }
                    };
                }

                @Override
                public void visitEnd()
                {
                    MethodVisitor mv = super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                        helper, desc, null, null);
                    mv.visitCode();
                    emitGetProperty(mv, PROP_MODEL_HIGHLIGHTING, "java/util/function/BiPredicate"); //$NON-NLS-1$
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiPredicate", "test", //$NON-NLS-1$ //$NON-NLS-2$
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", true); //$NON-NLS-1$
                    Label original = new Label();
                    mv.visitJumpInsn(Opcodes.IFEQ, original);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitLabel(original);
                    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitVarInsn(Opcodes.ALOAD, 2);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "modelChanged", desc, false); //$NON-NLS-1$
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                    super.visitEnd();
                }
            }, 0);
            System.setProperty(PROP_MODEL_HIGHLIGHTING + ".patch", "calls=" + calls[0]); //$NON-NLS-1$ //$NON-NLS-2$
            return calls[0] == 1 ? writer.toByteArray() : null;
        }

        /**
         * BSL UI: modify вызывает checkAndUpdateAnnotations и на штатном выходе, и в finally.
         * Для Handly T2M внутри собственной проверки это отменяет саму проверку и ставит её снова.
         * Дополняем обе штатные защиты instanceof только для текущего validationJob документа.
         * Внешние задания и синхронизация при редактировании по-прежнему запрашивают проверку.
         */
        private static byte[] transformValidationReentry(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            // Та же проверка типа управляет уведомлениями модели. На первом проходе
            // выбираем только защиты непосредственно перед checkAndUpdateAnnotations.
            java.util.Set<Integer> validationGuards = new java.util.HashSet<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    if (!"modify".equals(name) || !READ_FOR_ASSIST_DESC.equals(descriptor)) //$NON-NLS-1$
                        return null;
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        private int guardOrdinal;

                        @Override
                        public void visitTypeInsn(int opcode, String type)
                        {
                            if (opcode == Opcodes.INSTANCEOF
                                && "org/eclipse/xtext/ui/editor/model/edit/ReconcilingUnitOfWork".equals(type)) //$NON-NLS-1$
                                guardOrdinal++;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface)
                        {
                            if (opcode == Opcodes.INVOKEVIRTUAL && BSL_XTEXT_DOCUMENT.replace('.', '/').equals(owner)
                                && "checkAndUpdateAnnotations".equals(name) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                                validationGuards.add(guardOrdinal);
                        }
                    };
                }
            }, 0);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] guards = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null || !"modify".equals(name) || !READ_FOR_ASSIST_DESC.equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        private int guardOrdinal;

                        @Override
                        public void visitTypeInsn(int opcode, String type)
                        {
                            super.visitTypeInsn(opcode, type);
                            if (opcode != Opcodes.INSTANCEOF
                                || !"org/eclipse/xtext/ui/editor/model/edit/ReconcilingUnitOfWork".equals(type)) //$NON-NLS-1$
                                return;
                            if (!validationGuards.contains(++guardOrdinal))
                                return;
                            guards[0]++;
                            // original || (Handly T2M && currentJob != null && currentJob == validationJob).
                            // Вставка без ветвлений сохраняет исходные кадры и высоту стека на выходе.
                            emitGetProperty(mv, PROP_VALIDATION_REENTRY, "java/util/function/BiPredicate"); //$NON-NLS-1$
                            mv.visitVarInsn(Opcodes.ALOAD, 1);
                            emitCurrentJob();
                            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiPredicate", "test", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z", true); //$NON-NLS-1$
                            emitCurrentJob();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitFieldInsn(Opcodes.GETFIELD, DOC_LOCKER.replace('.', '/'), "this$0", //$NON-NLS-1$
                                "Lcom/_1c/g5/v8/dt/bsl/ui/editor/BslXtextDocument;"); //$NON-NLS-1$
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BSL_XTEXT_DOCUMENT.replace('.', '/'),
                                "getValidationJob", "()Lorg/eclipse/core/runtime/jobs/Job;", false); //$NON-NLS-1$ //$NON-NLS-2$
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false); //$NON-NLS-1$
                            mv.visitInsn(Opcodes.IAND);
                            mv.visitInsn(Opcodes.IOR);
                        }

                        private void emitCurrentJob()
                        {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/eclipse/core/runtime/jobs/Job", "getJobManager", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Lorg/eclipse/core/runtime/jobs/IJobManager;", false); //$NON-NLS-1$
                            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/eclipse/core/runtime/jobs/IJobManager", //$NON-NLS-1$
                                "currentJob", "()Lorg/eclipse/core/runtime/jobs/Job;", true); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    };
                }
            }, 0);
            boolean complete = guards[0] == 2 && !validationGuards.contains(0);
            System.setProperty(PROP_VALIDATION_REENTRY + ".patch", //$NON-NLS-1$
                (complete ? "woven" : "missing") + " guards=" + guards[0] + " expected=2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return complete ? writer.toByteArray() : bytes;
        }

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
            int[] touched = new int[3];
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
                    if ("waitLocalDerivedDataCompute".equals(name) //$NON-NLS-1$
                        && "(Lcom/_1c/g5/v8/dt/bsl/resource/BslResource;)V".equals(descriptor)) //$NON-NLS-1$
                    {
                        touched[2]++;
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                emitGetProperty(this, PROP_SKIP_LOCAL_DERIVED_WAIT, "java/util/function/Predicate"); //$NON-NLS-1$
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", //$NON-NLS-1$
                                    "test", "(Ljava/lang/Object;)Z", true); //$NON-NLS-1$ //$NON-NLS-2$
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
            boolean woven = touched[0] == 1 && touched[1] == 1 && touched[2] == 1;
            System.setProperty(PROP_DOC_LOCKER_PATCH, (woven ? "woven" : "skipped") //$NON-NLS-1$ //$NON-NLS-2$
                + " wait=" + touched[0] + " read=" + touched[1] + " localWait=" + touched[2]); //$NON-NLS-1$ //$NON-NLS-2$
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

            /**
             * doResolveCrossReferences берёт resource.getLock() до localResolveCrossReference.
             * Наш читатель уже держит этот монитор: Thread.sleep его не отпускает, поэтому
             * локальное разрешение не продвинется за штатный секундный таймаут.
             */
            static boolean skipLocalDerivedWait(Object resource)
            {
                boolean skip = false;
                try
                {
                    Object monitor = invoke(resource, "getLock"); //$NON-NLS-1$
                    skip = depth() > 0 && monitor != null && Thread.holdsLock(monitor);
                }
                catch (Exception error)
                {
                    observe("localDerivedWait.error " + error, 0); //$NON-NLS-1$
                }
                observe("localDerivedWait skip=" + skip, 0); //$NON-NLS-1$
                return skip;
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
    /**
     * check 6.0.100.v202605050943 {@code CheckExecutor}: каждый вызов {@code ICheckSettings.isEnabled()}
     * заменён вызовом функции {@code tormozit.checkReplacement.enabled}, которую задаёт основной бандл
     * (динамическое замещение штатных проверок проверками Комфорта). Вплетение здесь, а не в основном бандле:
     * к его старту {@code CheckExecutor} уже загружен. До старта основного бандла работает функция по умолчанию —
     * штатный {@code isEnabled}. Итог вплетения — в {@code .status}.
     */
    private static final class CheckExecutorEnabledWeaving implements WeavingHook
    {
        private static final String TARGET = "com.e1c.g5.v8.dt.internal.check.CheckExecutor"; //$NON-NLS-1$
        private static final String SETTINGS_TYPE = "com/e1c/g5/v8/dt/check/settings/ICheckSettings"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.checkReplacement.enabled"; //$NON-NLS-1$
        private static volatile java.lang.reflect.Method isEnabledMethod;

        static void install(BundleContext context)
        {
            System.getProperties().putIfAbsent(CALLBACK,
                (java.util.function.Predicate<Object>) CheckExecutorEnabledWeaving::stockEnabled);
            context.registerService(WeavingHook.class, new CheckExecutorEnabledWeaving(), null);
        }

        /** Штатный {@code isEnabled} без прямой ссылки на тип EDT: ранний бандл его не видит. */
        private static boolean stockEnabled(Object settings)
        {
            try
            {
                java.lang.reflect.Method method = isEnabledMethod;
                if (method == null)
                {
                    method = Class.forName("com.e1c.g5.v8.dt.check.settings.ICheckSettings", false, //$NON-NLS-1$
                        settings.getClass().getClassLoader()).getMethod("isEnabled"); //$NON-NLS-1$
                    isEnabledMethod = method;
                }
                return (Boolean)method.invoke(settings);
            }
            catch (ReflectiveOperationException | RuntimeException failure)
            {
                return true;
            }
        }

        @Override
        public void weave(WovenClass type)
        {
            if (type.getState() != WovenClass.TRANSFORMING || !TARGET.equals(type.getClassName()))
                return;
            try
            {
                int[] calls = { 0 };
                byte[] bytes = rewrite(type.getBytes(), calls);
                if (bytes != null)
                    type.setBytes(bytes);
                System.setProperty(CALLBACK + ".status", (bytes != null ? "woven" : "skipped") + " calls=" + calls[0]); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            catch (RuntimeException | LinkageError failure)
            {
                // При несовместимой версии EDT штатные проверки остаются как есть.
                System.setProperty(CALLBACK + ".status", "weave failed: " + failure); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private static byte[] rewrite(byte[] bytes, int[] calls)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions))
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                            String methodDescriptor, boolean isInterface)
                        {
                            if (opcode != Opcodes.INVOKEINTERFACE || !SETTINGS_TYPE.equals(owner)
                                || !"isEnabled".equals(method) || !"()Z".equals(methodDescriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                super.visitMethodInsn(opcode, owner, method, methodDescriptor, isInterface);
                                return;
                            }
                            // В стеке settings: Predicate.test(settings) вместо settings.isEnabled().
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                "()Ljava/util/Properties;", false); //$NON-NLS-1$
                            super.visitLdcInsn(CALLBACK);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate"); //$NON-NLS-1$
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", //$NON-NLS-1$
                                "test", "(Ljava/lang/Object;)Z", true); //$NON-NLS-1$ //$NON-NLS-2$
                            calls[0]++;
                        }
                    };
                }
            }, 0);
            return calls[0] > 0 ? writer.toByteArray() : null;
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
