package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
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

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Штатная EDT в {@code CustomXtextDocumentLocker.waitUpdatingDataModel} на UI-потоке
 * спит до 5 с ({@code Thread.sleep(25)}), пока не сменится parse result — в том числе
 * при Ctrl+S из {@code BslBreakpointMarkerUpdater.updateMarker}. Комфорт на потоке UI
 * этот цикл пропускает: сохранение не блокирует интерфейс.
 *
 * <p>Плетение — в {@link #install()} из {@code Activator.start()}, до восстановления
 * редакторов. {@code IStartup} слишком поздний: локер уже загружен.
 * Вызов через {@code System.getProperties}, без зависимости {@code bsl.ui} → Комфорт.
 */
public final class BslXtextDocumentHook implements IStartup
{
    static final String PROP_SKIP_WAIT = "tormozit.bslXtextDoc.skipWaitUpdatingDataModel"; //$NON-NLS-1$
    private static final String TARGET =
        "com._1c.g5.v8.dt.bsl.ui.editor.BslXtextDocument$CustomXtextDocumentLocker"; //$NON-NLS-1$
    private static final String TARGET_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/ui/editor/BslXtextDocument$CustomXtextDocumentLocker"; //$NON-NLS-1$
    private static final String WAIT_DESC =
        "(Lcom/_1c/g5/v8/dt/bsl/resource/BslResource;I)V"; //$NON-NLS-1$
    private static final String CMD_SAVE = "org.eclipse.ui.file.save"; //$NON-NLS-1$

    private static final AtomicBoolean transformerRegistered = new AtomicBoolean();
    private static final AtomicBoolean ASM_SEEN = new AtomicBoolean();
    private static final AtomicBoolean ASM_PATCHED = new AtomicBoolean();
    private static final AtomicInteger SAVE_SKIP = new AtomicInteger();
    private static final AtomicInteger SAVE_WAIT = new AtomicInteger();
    private static final ThreadLocal<Long> SAVE_STARTED_MS = new ThreadLocal<>();
    private static final ThreadLocal<String> SAVE_MODULE = new ThreadLocal<>();
    private static volatile boolean saveActive;
    private static volatile long saveUiUntilMs;
    private static final AtomicBoolean weavingRegistered = new AtomicBoolean();
    private static final AtomicBoolean uiInstalled = new AtomicBoolean();

    /**
     * Как можно раньше, из {@link Activator#start}: WeavingHook до загрузки локера.
     */
    public static void install()
    {
        System.getProperties().put(PROP_SKIP_WAIT,
            (BooleanSupplier) BslXtextDocumentHook::skipWaitUpdatingDataModel);
        registerWeavingHook();
        registerTransformer();
    }

    @Override
    public void earlyStartup()
    {
        install();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            installUi();
            registerTransformer();
            if (!display.isDisposed())
                display.timerExec(2000, BslXtextDocumentHook::registerTransformer);
        });
    }

    /**
     * Поток фонового расчёта автодополнения. Ctrl+Space считает список на UI и это
     * ожидание пропускает; фоновый расчёт того же списка должен вести себя так же,
     * иначе он отсиживает до 1000 мс там, где эталон не ждёт ничего.
     */
    private static final ThreadLocal<Boolean> ASSIST_BACKGROUND = new ThreadLocal<>();

    /** Метка на время фонового расчёта автодополнения. Снимать в {@code finally}. */
    public static void markAssistBackground(boolean on)
    {
        if (on)
            ASSIST_BACKGROUND.set(Boolean.TRUE);
        else
            ASSIST_BACKGROUND.remove();
    }

    /**
     * Вызов из инструментированного {@code waitUpdatingDataModel}: {@code true} —
     * сразу выйти, не ждать смены parse result.
     */
    public static boolean skipWaitUpdatingDataModel()
    {
        boolean ui = Display.getCurrent() != null
            || Boolean.TRUE.equals(ASSIST_BACKGROUND.get());
        if (saveActive && SaveDebug.isEnabled())
        {
            int n = ui ? SAVE_SKIP.incrementAndGet() : SAVE_WAIT.incrementAndGet();
            if (n == 1)
            {
                SaveDebug.step("waitUpdatingDataModel", //$NON-NLS-1$
                    (ui ? "skip" : "wait") //$NON-NLS-1$ //$NON-NLS-2$
                        + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
            }
        }
        return ui;
    }

    private static boolean registerTransformer()
    {
        if (!transformerRegistered.get())
        {
            boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(
                new WaitUpdatingTransformer(), TARGET);
            if (ok)
                transformerRegistered.set(true);
            else
                SaveDebug.problem("ASM transformer for waitUpdatingDataModel not registered"); //$NON-NLS-1$
        }
        retransformLockers();
        SaveDebug.step("asm", "transformer=" + transformerRegistered.get() //$NON-NLS-1$ //$NON-NLS-2$
            + " seen=" + ASM_SEEN.get() + " patched=" + ASM_PATCHED.get()); //$NON-NLS-1$ //$NON-NLS-2$
        return ASM_PATCHED.get();
    }

    private static void registerWeavingHook()
    {
        if (!weavingRegistered.compareAndSet(false, true))
            return;
        try
        {
            Bundle bundle = FrameworkUtil.getBundle(BslXtextDocumentHook.class);
            BundleContext context = bundle != null ? bundle.getBundleContext() : null;
            if (context == null)
            {
                SaveDebug.problem("WeavingHook: нет BundleContext"); //$NON-NLS-1$
                weavingRegistered.set(false);
                return;
            }
            context.registerService(WeavingHook.class, new LockerWeavingHook(), null);
            SaveDebug.step("asm", "WeavingHook registered"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Throwable t)
        {
            weavingRegistered.set(false);
            SaveDebug.problem("WeavingHook: " + t); //$NON-NLS-1$
        }
    }

    private static void retransformLockers()
    {
        Instrumentation inst = instrumentation();
        if (inst == null)
        {
            SaveDebug.step("asm", "retransform: Instrumentation is null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        int found = 0;
        int modifiable = 0;
        String error = null;
        for (Class<?> c : inst.getAllLoadedClasses())
        {
            String name = c.getName();
            if (name == null || !name.endsWith("BslXtextDocument$CustomXtextDocumentLocker")) //$NON-NLS-1$
                continue;
            found++;
            if (!inst.isModifiableClass(c))
            {
                SaveDebug.problem("locker not modifiable: " + name); //$NON-NLS-1$
                continue;
            }
            modifiable++;
            try
            {
                inst.retransformClasses(c);
            }
            catch (Throwable t)
            {
                error = t.toString();
            }
        }
        SaveDebug.step("asm", "retransform found=" + found + " modifiable=" + modifiable //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " seen=" + ASM_SEEN.get() + " patched=" + ASM_PATCHED.get() //$NON-NLS-1$ //$NON-NLS-2$
            + (error != null ? " error=" + error : "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Instrumentation instrumentation()
    {
        try
        {
            Field field = BslDocCommentDescriptionFix.class.getDeclaredField("instrumentation"); //$NON-NLS-1$
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof Instrumentation inst ? inst : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void installUi()
    {
        if (!uiInstalled.compareAndSet(false, true))
            return;
        installSaveLog();
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, event ->
        {
            if (!saveActive && System.currentTimeMillis() > saveUiUntilMs)
                return;
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            String title = shell.getText();
            if (title == null || title.isBlank())
                return;
            boolean modal = (shell.getStyle() & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL
                | SWT.SYSTEM_MODAL)) != 0;
            if (!modal && !looksLikeJobsDialog(title))
                return;
            SaveDebug.step("modal", (modal ? "modal " : "") + title //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " jobs=" + runningJobsBrief()); //$NON-NLS-1$
        });
    }

    private static boolean looksLikeJobsDialog(String title)
    {
        String t = title.toLowerCase();
        return t.contains("ожид") //$NON-NLS-1$
            || t.contains("wait") //$NON-NLS-1$
            || t.contains("фонов") //$NON-NLS-1$
            || t.contains("операция"); //$NON-NLS-1$
    }

    private static String runningJobsBrief()
    {
        Job[] jobs = Job.getJobManager().find(null);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Job job : jobs)
        {
            if (job.getState() != Job.RUNNING)
                continue;
            n++;
            if (n > 6)
            {
                sb.append(" …"); //$NON-NLS-1$
                break;
            }
            if (sb.length() > 0)
                sb.append("; "); //$NON-NLS-1$
            sb.append(job.getName());
        }
        return n == 0 ? "none" : n + " " + sb; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void installSaveLog()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commands == null)
            return;
        commands.addExecutionListener(SAVE_COMMANDS);
    }

    private static final IExecutionListener SAVE_COMMANDS = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!CMD_SAVE.equals(commandId))
                return;
            SAVE_STARTED_MS.remove();
            SAVE_MODULE.remove();
            saveActive = false;
            SAVE_SKIP.set(0);
            SAVE_WAIT.set(0);
            if (!SaveDebug.isEnabled())
                return;
            IEditorPart editor = resolveEditor(event);
            BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
            if (bsl == null)
                return;
            String module = moduleLabel(bsl);
            SAVE_STARTED_MS.set(Long.valueOf(System.currentTimeMillis()));
            SAVE_MODULE.set(module);
            saveActive = true;
            saveUiUntilMs = Long.MAX_VALUE;
            boolean dirty = editor != null && editor.isDirty();
            SaveDebug.step("save", module + " dirty=" + dirty //$NON-NLS-1$ //$NON-NLS-2$
                + " asm seen=" + ASM_SEEN.get() + " patched=" + ASM_PATCHED.get()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            endSave(commandId, "ok"); //$NON-NLS-1$
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            endSave(commandId, "fail"); //$NON-NLS-1$
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            endSave(commandId, "notHandled"); //$NON-NLS-1$
        }
    };

    private static void endSave(String commandId, String outcome)
    {
        if (!CMD_SAVE.equals(commandId))
            return;
        Long started = SAVE_STARTED_MS.get();
        String module = SAVE_MODULE.get();
        SAVE_STARTED_MS.remove();
        SAVE_MODULE.remove();
        if (started == null)
            return;
        long spent = System.currentTimeMillis() - started.longValue();
        String name = module != null && !module.isEmpty() ? module : "?"; //$NON-NLS-1$
        saveActive = false;
        saveUiUntilMs = System.currentTimeMillis() + 5_000L;
        SaveDebug.step("save-end", name + " " + outcome + " " + spent + "ms" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " skipWait=" + SAVE_SKIP.get() //$NON-NLS-1$
            + " waitModel=" + SAVE_WAIT.get() //$NON-NLS-1$
            + " asm seen=" + ASM_SEEN.get() + " patched=" + ASM_PATCHED.get()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static IEditorPart resolveEditor(ExecutionEvent event)
    {
        if (event != null)
        {
            try
            {
                IEditorPart fromEvent = HandlerUtil.getActiveEditor(event);
                if (fromEvent != null)
                    return fromEvent;
            }
            catch (RuntimeException ignored)
            {
            }
        }
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null)
            return null;
        return window.getActivePage().getActiveEditor();
    }

    private static String moduleLabel(BslXtextEditor editor)
    {
        IEditorInput input = editor.getEditorInput();
        if (input instanceof IFileEditorInput fileInput)
        {
            IFile file = fileInput.getFile();
            if (file != null)
                return file.getFullPath().toString();
        }
        return input != null ? input.getName() : "?"; //$NON-NLS-1$
    }

    private static boolean isLockerClass(String className)
    {
        return TARGET_INTERNAL.equals(className)
            || className.endsWith("BslXtextDocument$CustomXtextDocumentLocker"); //$NON-NLS-1$
    }

    private static final class SaveDebug
    {
        private static final String DEBUG_TAG = "BslModuleSave"; //$NON-NLS-1$

        private SaveDebug() {}

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void step(String phase, String detail)
        {
            if (!isEnabled())
                return;
            if (detail == null || detail.isEmpty())
                Global.log(DEBUG_TAG, phase);
            else
                Global.log(DEBUG_TAG, phase + " " + detail); //$NON-NLS-1$
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(DEBUG_TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }

    private static final class WaitUpdatingTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (className == null || !isLockerClass(className))
                return null;
            ASM_SEEN.set(true);
            if (!TARGET_INTERNAL.equals(className))
                SaveDebug.step("asm", "locker className=" + className); //$NON-NLS-1$ //$NON-NLS-2$
            try
            {
                byte[] out = transformWaitUpdating(classfileBuffer);
                if (out != null)
                    ASM_PATCHED.set(true);
                SaveDebug.step("asm", "transform patched=" + ASM_PATCHED.get() //$NON-NLS-1$ //$NON-NLS-2$
                    + " bytes=" + (out != null) //$NON-NLS-1$
                    + " redefine=" + (classBeingRedefined != null)); //$NON-NLS-1$
                return out;
            }
            catch (Throwable t)
            {
                SaveDebug.problem("transform: " + t); //$NON-NLS-1$
                return null;
            }
        }
    }

    static byte[] transformWaitUpdating(byte[] classfileBuffer)
    {
        if (classfileBuffer != null
            && new String(classfileBuffer, StandardCharsets.ISO_8859_1).contains(PROP_SKIP_WAIT))
        {
            ASM_SEEN.set(true);
            ASM_PATCHED.set(true);
            return null;
        }
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
                if (mv == null)
                    return null;
                if (!"waitUpdatingDataModel".equals(name)) //$NON-NLS-1$
                    return mv;
                if (!WAIT_DESC.equals(descriptor))
                    SaveDebug.step("asm", "waitUpdatingDataModel desc=" + descriptor); //$NON-NLS-1$ //$NON-NLS-2$
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitCode()
                    {
                        super.visitCode();
                        emitSkipIfUi(this);
                        touched.set(true);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!touched.get())
            SaveDebug.problem("waitUpdatingDataModel not found in locker"); //$NON-NLS-1$
        return touched.get() ? writer.toByteArray() : null;
    }

    /**
     * В начале метода: если {@link #PROP_SKIP_WAIT} — {@link BooleanSupplier} и он
     * вернул {@code true}, сразу {@code return}.
     */
    private static void emitSkipIfUi(MethodVisitor mv)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(PROP_SKIP_WAIT);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BooleanSupplier"); //$NON-NLS-1$
        Label notSupplier = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, notSupplier);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BooleanSupplier"); //$NON-NLS-1$
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BooleanSupplier", //$NON-NLS-1$
            "getAsBoolean", "()Z", true); //$NON-NLS-1$ //$NON-NLS-2$
        Label original = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, original);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(notSupplier);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(original);
    }

    private static final class LockerWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            if (!TARGET.equals(wovenClass.getClassName()))
                return;
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            ASM_SEEN.set(true);
            try
            {
                byte[] transformed = transformWaitUpdating(wovenClass.getBytes());
                if (transformed != null)
                {
                    wovenClass.setBytes(transformed);
                    ASM_PATCHED.set(true);
                    SaveDebug.step("asm", "WeavingHook patched locker"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            catch (Throwable t)
            {
                SaveDebug.problem("WeavingHook: " + t); //$NON-NLS-1$
            }
        }
    }
}
