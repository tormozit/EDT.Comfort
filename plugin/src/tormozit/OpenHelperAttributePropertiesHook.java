package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.ui.commands.ShowPropertiesHandler;

/**
 * Реквизит/измерение/ресурс МД не имеет своего редактора: {@code OpenHelper} открывает
 * редактор владельца. Общая точка всех переходов — {@code openEditor}/{@code activateEditor}
 * (диалог «Открыть объект метаданных», переход к определению, навигатор, поиск и т.п.).
 * После успешного открытия панель «Свойства» активируется последней.
 *
 * <p>Инструментирование {@link com._1c.g5.v8.dt.ui.util.OpenHelper}, как
 * {@link BslHandlerBlankLineHook}: вызов через {@code System.getProperties}, без зависимости
 * {@code dt.ui} → Комфорт.
 */
public final class OpenHelperAttributePropertiesHook implements IStartup
{
    static final String PROP_AFTER_OPENED = "tormozit.openHelper.afterOpened"; //$NON-NLS-1$
    private static final String TAG = "OpenHelperAttributeProperties"; //$NON-NLS-1$
    private static final String TARGET = "com._1c.g5.v8.dt.ui.util.OpenHelper"; //$NON-NLS-1$
    private static final String TARGET_INTERNAL = "com/_1c/g5/v8/dt/ui/util/OpenHelper"; //$NON-NLS-1$
    private static final String OPEN_DESC =
        "(Lorg/eclipse/emf/ecore/EObject;Lorg/eclipse/emf/ecore/EStructuralFeature;" //$NON-NLS-1$
            + "Lorg/eclipse/jface/viewers/ISelection;)Lorg/eclipse/ui/IEditorPart;"; //$NON-NLS-1$
    private static final String ACTIVATE_DESC =
        "(Lorg/eclipse/emf/ecore/EObject;Lorg/eclipse/emf/ecore/EStructuralFeature;" //$NON-NLS-1$
            + "Lorg/eclipse/jface/viewers/ISelection;)Z"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        System.getProperties().put(PROP_AFTER_OPENED,
            (BiConsumer<Object, Object>) OpenHelperAttributePropertiesHook::afterOpened);
        boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(new OpenTransformer(), TARGET);
        if (!ok)
            Global.logError(TAG, "ASM transformer for OpenHelper not registered", null); //$NON-NLS-1$
    }

    /**
     * Вызов из инструментированного {@code OpenHelper}: объект и выделение, которые
     * передали в {@code openEditor}/{@code activateEditor}.
     */
    public static void afterOpened(Object obj, Object selection)
    {
        if (!isMdObjectAttribute(asEObject(obj)) && !isMdObjectAttribute(selectionFirst(selection)))
            return;
        scheduleActivateProperties();
    }

    private static EObject asEObject(Object obj)
    {
        return obj instanceof EObject eObject ? eObject : null;
    }

    private static EObject selectionFirst(Object selection)
    {
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
            return null;
        Object first = structured.getFirstElement();
        return first instanceof EObject eObject ? eObject : null;
    }

    /** Активация панели «Свойства» после текущего события UI — чтобы она получила фокус последней. */
    static void scheduleActivateProperties()
    {
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(OpenHelperAttributePropertiesHook::activateProperties);
    }

    static boolean isMdObjectAttribute(EObject obj)
    {
        if (obj instanceof BasicFeature)
        {
            String typeName = obj.eClass().getName();
            return typeName == null || !typeName.contains("CommonAttribute"); //$NON-NLS-1$
        }
        return obj != null && "StandardAttribute".equals(obj.eClass().getName()); //$NON-NLS-1$
    }

    private static void activateProperties()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return;
        IEditorPart editor = page.getActiveEditor();
        if (editor == null || editor.getSite() == null)
            return;
        ShowPropertiesHandler.run(editor.getSite());
    }

    private static final class OpenTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!TARGET_INTERNAL.equals(className))
                return null;
            try
            {
                return transformOpenHelper(classfileBuffer);
            }
            catch (Throwable t)
            {
                return null;
            }
        }
    }

    static byte[] transformOpenHelper(byte[] classfileBuffer)
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
                boolean open = "openEditor".equals(name) && OPEN_DESC.equals(descriptor); //$NON-NLS-1$
                boolean activate = "activateEditor".equals(name) && ACTIVATE_DESC.equals(descriptor); //$NON-NLS-1$
                if (!open && !activate)
                    return mv;
                boolean objectResult = open;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (objectResult && opcode == Opcodes.ARETURN)
                            emitAfterOpenedIfNonNull(this, touched);
                        else if (!objectResult && opcode == Opcodes.IRETURN)
                            emitAfterOpenedIfTrue(this, touched);
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    /**
     * Стек при {@code ARETURN}: {@code editor}. Локальные 1 и 3 — {@code EObject} и {@code ISelection}.
     * Если редактор не {@code null} — {@code BiConsumer.accept(eObject, selection)}.
     */
    private static void emitAfterOpenedIfNonNull(MethodVisitor mv, AtomicBoolean touched)
    {
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, skip);
        emitAcceptOpened(mv);
        mv.visitLabel(skip);
        touched.set(true);
    }

    /**
     * Стек при {@code IRETURN}: {@code boolean}. Локальные 1 и 3 — {@code EObject} и {@code ISelection}.
     * Если {@code true} — {@code BiConsumer.accept(eObject, selection)}.
     */
    private static void emitAfterOpenedIfTrue(MethodVisitor mv, AtomicBoolean touched)
    {
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        emitAcceptOpened(mv);
        mv.visitLabel(skip);
        touched.set(true);
    }

    private static void emitAcceptOpened(MethodVisitor mv)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(PROP_AFTER_OPENED);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BiConsumer"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiConsumer"); //$NON-NLS-1$
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;Ljava/lang/Object;)V", true); //$NON-NLS-1$
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
    }
}
