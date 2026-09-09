package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
    private static final String TOPIC = "bsl-module-save"; //$NON-NLS-1$

    private static final AtomicBoolean transformerRegistered = new AtomicBoolean();
    private static final AtomicLong lastSkipLogMs = new AtomicLong();

    @Override
    public void earlyStartup()
    {
        System.getProperties().put(PROP_SKIP_WAIT,
            (BooleanSupplier) BslXtextDocumentHook::skipWaitUpdatingDataModel);
        if (registerTransformer())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(2000, BslXtextDocumentHook::registerTransformer);
    }

    /**
     * Вызов из инструментированного {@code waitUpdatingDataModel}: {@code true} —
     * сразу выйти, не ждать смены parse result.
     */
    public static boolean skipWaitUpdatingDataModel()
    {
        if (Display.getCurrent() == null)
            return false;
        long now = System.currentTimeMillis();
        long prev = lastSkipLogMs.get();
        if (now - prev >= 1_000L && lastSkipLogMs.compareAndSet(prev, now))
            Global.tempLog(TOPIC, "skip waitUpdatingDataModel (UI thread)"); //$NON-NLS-1$
        return true;
    }

    private static boolean registerTransformer()
    {
        if (transformerRegistered.get())
            return true;
        boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(
            new WaitUpdatingTransformer(), TARGET);
        if (ok)
        {
            transformerRegistered.set(true);
            Global.tempLog(TOPIC, "ASM waitUpdatingDataModel skip-on-UI registered"); //$NON-NLS-1$
        }
        else
        {
            Global.tempLog(TOPIC, "ASM waitUpdatingDataModel skip-on-UI NOT registered"); //$NON-NLS-1$
            Global.logError(TAG, "ASM transformer for waitUpdatingDataModel not registered", null); //$NON-NLS-1$
        }
        return ok;
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
