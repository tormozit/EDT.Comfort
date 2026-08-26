package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.ui.IStartup;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com._1c.g5.v8.dt.bsl.common.IBslModuleTextInsertInfo;

/**
 * Пустая строка-разделитель перед создаваемым обработчиком события или подписки, как
 * в конфигураторе.
 *
 * <p>Штатная EDT вставляет процедуру вплотную к предыдущему методу. Общая точка —
 * {@code BslModuleRegionsInfoServiceProvider.wrap}: лупа в панели «Свойства», подписка,
 * схема модуля. Подмена поля сервиса не срабатывает (Guice отдаёт другой экземпляр, чем
 * уже внедрён в lookup) — поэтому {@code wrap} инструментируется ASM, как
 * {@link BslDocCommentDescriptionFix}: вызов через {@code System.getProperties}, без
 * зависимости {@code bsl.ui} → Комфорт.
 *
 * @see <a href="https://github.com/tormozit/EDT.Comfort/issues/394">issue 394</a>
 */
public final class BslHandlerBlankLineHook implements IStartup
{
    static final String PROP_AFTER_WRAP = "tormozit.bslHandler.afterWrap"; //$NON-NLS-1$
    private static final String TAG = "BslHandlerBlankLine"; //$NON-NLS-1$
    private static final String TARGET =
        "com._1c.g5.v8.dt.bsl.ui.event.BslModuleRegionsInfoServiceProvider"; //$NON-NLS-1$
    private static final String TARGET_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/ui/event/BslModuleRegionsInfoServiceProvider"; //$NON-NLS-1$
    private static final String WRAP_DESC =
        "(Lcom/_1c/g5/v8/dt/bsl/common/IBslModuleTextInsertInfo;Ljava/lang/String;)Ljava/lang/String;"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        System.getProperties().put(PROP_AFTER_WRAP,
            (BiFunction<Object, Object, Object>) BslHandlerBlankLineHook::afterWrap);
        boolean ok = BslDocCommentDescriptionFix.registerExtraTransformer(new WrapTransformer(), TARGET);
        if (!ok)
            Global.logError(TAG, "ASM transformer for wrap() not registered", null); //$NON-NLS-1$
    }

    /** Вызов из инструментированного {@code wrap}: {@code (insertInfo, content) → content}. */
    public static Object afterWrap(Object insertInfo, Object contentObj)
    {
        String content = contentObj instanceof String s ? s : null;
        int pos = insertInfo instanceof IBslModuleTextInsertInfo info ? info.getPosition() : -1;
        String out = ensureSeparatingBlankLineBeforeHandler(documentOf(insertInfo), pos, content);
        return out != null ? out : contentObj;
    }

    /**
     * Пустая строка-разделитель перед новым обработчиком, как в конфигураторе.
     * Идемпотентно: если разделитель уже есть в документе или в {@code content}, ничего не добавляет.
     */
    static String ensureSeparatingBlankLineBeforeHandler(IXtextDocument document, int offset, String content)
    {
        if (content == null || content.isEmpty() || document == null || offset <= 0)
            return content;
        try
        {
            int safeOffset = Math.min(offset, Math.max(0, document.getLength() - 1));
            String ld = document.getLineDelimiter(document.getLineOfOffset(safeOffset));
            if (ld == null || ld.isEmpty())
                ld = "\r\n"; //$NON-NLS-1$

            if (hasBlankLineBefore(document, offset))
                return stripLeadingLineDelimiters(content);

            if (countLeadingLineDelimiters(content) >= 2)
                return content;

            String body = stripLeadingLineDelimiters(content);
            StringBuilder prefix = new StringBuilder();
            char charBefore = document.getChar(offset - 1);
            if (charBefore != '\n' && charBefore != '\r')
                prefix.append(ld);
            prefix.append(ld);
            return prefix.toString() + body;
        }
        catch (BadLocationException e)
        {
            return content;
        }
    }

    private static IXtextDocument documentOf(Object info)
    {
        if (info == null)
            return null;
        Object doc = Global.getField(info, "val$document"); //$NON-NLS-1$
        return doc instanceof IXtextDocument d ? d : null;
    }

    private static boolean hasBlankLineBefore(IXtextDocument document, int offset) throws BadLocationException
    {
        if (offset <= 0)
            return true;
        int length = document.getLength();
        if (length <= 0)
            return true;
        int line = document.getLineOfOffset(Math.min(offset, length - 1));
        int lineStart = document.getLineOffset(line);
        if (offset == lineStart && document.getLineLength(line) == 0)
            return true;
        if (offset == lineStart && document.get(lineStart, document.getLineLength(line)).trim().isEmpty())
            return true;
        if (line > 0)
        {
            int prevStart = document.getLineOffset(line - 1);
            if (document.get(prevStart, document.getLineLength(line - 1)).trim().isEmpty())
                return true;
        }
        return false;
    }

    private static String stripLeadingLineDelimiters(String text)
    {
        if (text == null || text.isEmpty())
            return text;
        int i = 0;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '\r')
            {
                i++;
                if (i < text.length() && text.charAt(i) == '\n')
                    i++;
            }
            else if (c == '\n')
                i++;
            else
                break;
        }
        return text.substring(i);
    }

    private static int countLeadingLineDelimiters(String text)
    {
        if (text == null || text.isEmpty())
            return 0;
        int count = 0;
        int i = 0;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '\r')
            {
                i++;
                if (i < text.length() && text.charAt(i) == '\n')
                    i++;
                count++;
            }
            else if (c == '\n')
            {
                i++;
                count++;
            }
            else
                break;
        }
        return count;
    }

    private static final class WrapTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!TARGET_INTERNAL.equals(className))
                return null;
            try
            {
                return transformWrap(classfileBuffer);
            }
            catch (Throwable t)
            {
                return null;
            }
        }
    }

    static byte[] transformWrap(byte[] classfileBuffer)
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
                if (!"wrap".equals(name) || !WRAP_DESC.equals(descriptor)) //$NON-NLS-1$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                            emitAfterWrap(this, touched);
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    /**
     * Стек при {@code ARETURN}: {@code result}. Локальная 1 — {@code insertInfo}.
     * Вызов {@code BiFunction.apply(insertInfo, result)} из {@link #PROP_AFTER_WRAP}.
     */
    private static void emitAfterWrap(MethodVisitor mv, AtomicBoolean touched)
    {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(PROP_AFTER_WRAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BiFunction"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP_X2);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String"); //$NON-NLS-1$
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
        touched.set(true);
    }
}
