package tormozit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.forms.editor.FormEditor;
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

import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;

/**
 * Редактор объекта метаданных, уже открытый на какой-либо вкладке, при повторном открытии без
 * указания элемента (переход к определению, Ctrl+клик, «Открыть объект метаданных») не должен
 * менять активную вкладку.
 *
 * <p>Повторное открытие приходит в {@code DtGranularEditor.showEditorInput(IDtEditorInput)} с новым
 * входом без свойства и выделения; штатный код выбирает страницу «по умолчанию» и переключает на неё.
 * Метод инструментируется ASM через {@code WeavingHook}: в начале вызывается предикат через
 * {@code System.getProperties} (без зависимости {@code md.ui} → Комфорт); {@code true} — выйти,
 * ничего не меняя. Вход самого редактора ({@code createPages}) и переходы к элементу (есть свойство
 * или выделение) идут штатным путём.
 */
final class DtGranularEditorHook
{
    static final String PROP_KEEP_PAGE = "tormozit.dtGranularEditor.keepPage"; //$NON-NLS-1$
    private static final String TARGET = "com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor"; //$NON-NLS-1$
    private static final String INPUT_INTERNAL = "com/_1c/g5/v8/dt/ui/editor/input/IDtEditorInput"; //$NON-NLS-1$
    private static final String SHOW_DESC = "(L" + INPUT_INTERNAL + ";)V"; //$NON-NLS-1$ //$NON-NLS-2$

    private static final AtomicBoolean weavingHookInstalled = new AtomicBoolean();

    private DtGranularEditorHook()
    {
    }

    /** Регистрация {@link WeavingHook}; как можно раньше из {@code Activator.start}. */
    static void installWeavingHook()
    {
        if (!weavingHookInstalled.compareAndSet(false, true))
            return;
        System.getProperties().put(PROP_KEEP_PAGE, (BiPredicate<Object, Object>) DtGranularEditorHook::shouldKeepPage);
        Bundle bundle = FrameworkUtil.getBundle(DtGranularEditorHook.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(WeavingHook.class, new EditorWeavingHook(), null);
    }

    /** Вызов из инструментированного {@code showEditorInput}: {@code true} — страницу не трогать. */
    static boolean shouldKeepPage(Object editorObj, Object inputObj)
    {
        if (!(editorObj instanceof FormEditor editor) || !(inputObj instanceof IDtEditorInput<?> input))
            return false;
        Object current = editor.getEditorInput();
        if (current == null || current == input)
            return false;
        if (editor.getActivePage() < 0 || !isNoSpecificFeature(input))
            return false;
        ISelection selection = input.getSelection();
        if (selection == null || selection.isEmpty())
            return true;
        // Переход к самому объекту передаёт выделением сам объект — это не элемент внутри редактора.
        if (!(selection instanceof IStructuredSelection structured))
            return false;
        Object model = input.getModel();
        for (Object element : structured.toList())
        {
            if (!sameObject(element, model))
                return false;
        }
        return true;
    }

    /**
     * Свойства нет, либо это собственное свойство {@code form} формы: переход к форме приходит с ним
     * и выделением в виде самой формы (иначе открыть форму «как объект» нельзя).
     */
    private static boolean isNoSpecificFeature(IDtEditorInput<?> input)
    {
        EStructuralFeature feature = input.getFeature();
        return feature == null
            || input.getModel() instanceof BasicForm && "form".equals(feature.getName()); //$NON-NLS-1$
    }

    /** {@code getModel()} отдаёт новый экземпляр при каждом вызове — сравниваем по URI объекта. */
    private static boolean sameObject(Object a, Object b)
    {
        if (a == b)
            return true;
        if (!(a instanceof EObject ea) || !(b instanceof EObject eb))
            return false;
        URI ua = EcoreUtil.getURI(ea);
        return ua != null && ua.equals(EcoreUtil.getURI(eb));
    }

    private static final class EditorWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            if (wovenClass.getState() != WovenClass.TRANSFORMING || !TARGET.equals(wovenClass.getClassName()))
                return;
            try
            {
                byte[] transformed = transform(wovenClass.getBytes());
                if (transformed != null)
                    wovenClass.setBytes(transformed);
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    private static byte[] transform(byte[] classfileBuffer)
    {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            private String owner;

            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces)
            {
                owner = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || !"showEditorInput".equals(name) || !SHOW_DESC.equals(descriptor)) //$NON-NLS-1$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitCode()
                    {
                        super.visitCode();
                        emitKeepPageGuard(this, owner);
                        touched.set(true);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    /**
     * В начале метода: {@code if (props.get(PROP) instanceof BiPredicate p && p.test(this, input)) return;}.
     * Фреймы записываются явно (локальные: {@code this}, вход), поэтому пересчёт всего класса не нужен.
     */
    private static void emitKeepPageGuard(MethodVisitor mv, String owner)
    {
        Object[] locals = { owner, INPUT_INTERNAL };
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(PROP_KEEP_PAGE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        Label pop = new Label();
        Label end = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BiPredicate"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, pop);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiPredicate"); //$NON-NLS-1$
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiPredicate", "test", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;Ljava/lang/Object;)Z", true); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(pop);
        mv.visitFrame(Opcodes.F_NEW, 2, locals, 1, new Object[] { "java/lang/Object" }); //$NON-NLS-1$
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
        mv.visitFrame(Opcodes.F_NEW, 2, locals, 0, new Object[0]);
    }
}
