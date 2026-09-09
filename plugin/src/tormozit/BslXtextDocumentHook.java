package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.widgets.Display;
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

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Штатная EDT в {@code CustomXtextDocumentLocker.waitUpdatingDataModel} на UI-потоке
 * спит до 5 с ({@code Thread.sleep(25)}), пока не сменится parse result — в том числе
 * при Ctrl+S из {@code BslBreakpointMarkerUpdater.updateMarker}. Комфорт на потоке UI
 * этот цикл пропускает: сохранение не блокирует интерфейс.
 *
 * <p>Инструментирование как {@link BslHandlerBlankLineHook}: вызов через
 * {@code System.getProperties}, без зависимости {@code bsl.ui} → Комфорт.
 */
public final class BslXtextDocumentHook implements IStartup
{
    static final String PROP_SKIP_WAIT = "tormozit.bslXtextDoc.skipWaitUpdatingDataModel"; //$NON-NLS-1$
    private static final String TAG = "BslXtextDocument"; //$NON-NLS-1$
    private static final String TARGET =
        "com._1c.g5.v8.dt.bsl.ui.editor.BslXtextDocument$CustomXtextDocumentLocker"; //$NON-NLS-1$
    private static final String TARGET_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/ui/editor/BslXtextDocument$CustomXtextDocumentLocker"; //$NON-NLS-1$
    private static final String WAIT_DESC =
        "(Lcom/_1c/g5/v8/dt/bsl/resource/BslResource;I)V"; //$NON-NLS-1$
    private static final String CMD_SAVE = "org.eclipse.ui.file.save"; //$NON-NLS-1$

    private static final AtomicBoolean transformerRegistered = new AtomicBoolean();
    private static final ThreadLocal<Long> SAVE_STARTED_MS = new ThreadLocal<>();
    private static final ThreadLocal<String> SAVE_MODULE = new ThreadLocal<>();

    @Override
    public void earlyStartup()
    {
        System.getProperties().put(PROP_SKIP_WAIT,
            (BooleanSupplier) BslXtextDocumentHook::skipWaitUpdatingDataModel);
        if (!registerTransformer())
        {
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
                display.asyncExec(() ->
                {
                    if (!display.isDisposed())
                        display.timerExec(2000, BslXtextDocumentHook::registerTransformer);
                });
            }
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(BslXtextDocumentHook::installSaveLog);
    }

    /**
     * Вызов из инструментированного {@code waitUpdatingDataModel}: {@code true} —
     * сразу выйти, не ждать смены parse result.
     */
    public static boolean skipWaitUpdatingDataModel()
    {
        return Display.getCurrent() != null;
    }

    private static boolean registerTransformer()
    {
        if (transformerRegistered.get())
            return true;
        boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(
            new WaitUpdatingTransformer(), TARGET);
        if (ok)
            transformerRegistered.set(true);
        else
            Global.logError(TAG, "ASM transformer for waitUpdatingDataModel not registered", null); //$NON-NLS-1$
        return ok;
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
            if (!SaveDebug.isEnabled())
                return;
            IEditorPart editor = resolveEditor(event);
            BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
            if (bsl == null)
                return;
            String module = moduleLabel(bsl);
            SAVE_STARTED_MS.set(Long.valueOf(System.currentTimeMillis()));
            SAVE_MODULE.set(module);
            boolean dirty = editor != null && editor.isDirty();
            SaveDebug.step("save", module + " dirty=" + dirty); //$NON-NLS-1$ //$NON-NLS-2$
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
        SaveDebug.step("save-end", name + " " + outcome + " " + spent + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
    }

    private static final class WaitUpdatingTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!TARGET_INTERNAL.equals(className))
                return null;
            try
            {
                return transformWaitUpdating(classfileBuffer);
            }
            catch (Throwable t)
            {
                return null;
            }
        }
    }

    static byte[] transformWaitUpdating(byte[] classfileBuffer)
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
                if (mv == null)
                    return null;
                if (!"waitUpdatingDataModel".equals(name) || !WAIT_DESC.equals(descriptor)) //$NON-NLS-1$
                    return mv;
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
}
