// BslDocCommentDescriptionFix.java
package tormozit;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.eclipse.xtext.resource.IResourceServiceProvider;

/**
 * Корневой фикс EDT: хвост {@code Имя - Тип - описание} из
 * {@code TypeSection.getDescription()} (Section) → {@code FieldDefinition.description}.
 * {@code sourceDescription} хранит имя типа — его не трогаем.
 * <p>
 * Установка: WeavingHook + постоянный Instrumentation transformer.
 * Запасной путь: {@link #recoverParamDescription} (re-parse) для уже собранного ValueContent.
 */
public final class BslDocCommentDescriptionFix
{
    static final String PROP_AFTER_PARSE = "tormozit.bslDocComment.afterParse"; //$NON-NLS-1$
    private static final String TARGET_COMMENT =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment"; //$NON-NLS-1$
    private static final String TARGET_COMMENT_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/documentation/comment/BslDocumentationComment"; //$NON-NLS-1$
    private static final String TARGET_UTILS =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslCommentUtils"; //$NON-NLS-1$
    private static final String TARGET_UTILS_INTERNAL =
        "com/_1c/g5/v8/dt/bsl/documentation/comment/BslCommentUtils"; //$NON-NLS-1$
    private static final String PARSE_VOID_DESC = "(Ljava/util/List;Ljava/util/List;)V"; //$NON-NLS-1$
    private static final String COMMENT_RETURN =
        "Lcom/_1c/g5/v8/dt/bsl/documentation/comment/BslDocumentationComment;"; //$NON-NLS-1$
    private static final String TEXT_PART =
        "com._1c.g5.v8.dt.bsl.documentation.comment.TextPart"; //$NON-NLS-1$
    private static final String LINK_PART =
        "com._1c.g5.v8.dt.bsl.documentation.comment.LinkPart"; //$NON-NLS-1$
    private static final String BSL_COMMENT_BUNDLE = "com._1c.g5.v8.dt.bsl.comment"; //$NON-NLS-1$
    private static final String PROVIDER_CLASS =
        "com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider"; //$NON-NLS-1$
    private static final String AXIOM_JAVAC =
        "C:\\Program Files\\1C\\1CE\\components\\axiom-jdk-full-17.0.16+12-x86_64\\bin\\javac.exe"; //$NON-NLS-1$

    private static final AtomicBoolean installed = new AtomicBoolean();
    private static volatile Instrumentation instrumentation;
    private static volatile boolean transformerRegistered;

    private BslDocCommentDescriptionFix() {}

    public static void install()
    {
        if (!installed.compareAndSet(false, true))
        {
            return;
        }

        System.getProperties().put(PROP_AFTER_PARSE,
            (Consumer<Object>) BslDocCommentDescriptionFix::afterParse);

        Bundle bundle = FrameworkUtil.getBundle(BslDocCommentDescriptionFix.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
        {
            context.registerService(WeavingHook.class, new ParseWeavingHook(), null);
        }

        try
        {
            Instrumentation inst = ensureInstrumentation();
            if (inst == null)
            {
                return;
            }
            registerPermanentTransformer(inst);

            for (Class<?> c : inst.getAllLoadedClasses())
            {
                String name = c.getName();
                if (!TARGET_COMMENT.equals(name) && !TARGET_UTILS.equals(name))
                    continue;
                retransform(inst, c);
            }
        }
        catch (Throwable t)
        {
        }
    }

    public static void afterParse(Object commentObj)
    {
        if (commentObj == null)
            return;
        String cn = commentObj.getClass().getName();
        if (!TARGET_COMMENT.equals(cn))
            return;
        try
        {
            normalize(commentObj);
        }
        catch (Throwable t)
        {
        }
    }

    /** @return число параметров, у которых перенесли описание */
    static int normalize(Object comment)
    {
        if (comment == null)
            return 0;
        Object section = Global.invoke(comment, "getParametersSection"); //$NON-NLS-1$
        if (section == null)
            return 0;
        Object fieldsObj = Global.invoke(section, "getParameterDefinitions"); //$NON-NLS-1$
        if (!(fieldsObj instanceof List<?> fields) || fields.isEmpty())
            return 0;

        int moved = 0;
        for (Object field : fields)
        {
            if (field == null)
                continue;
            Object fieldDesc = Global.invoke(field, "getDescription"); //$NON-NLS-1$
            if (hasText(fieldDesc))
                continue;
            Object typeSectionsObj = Global.invoke(field, "getTypeSections"); //$NON-NLS-1$
            if (!(typeSectionsObj instanceof List<?> typeSections) || typeSections.isEmpty())
                continue;
            if (fieldDesc == null)
                continue;

            boolean fieldMoved = false;
            for (Object typeSection : typeSections)
            {
                if (typeSection == null)
                    continue;
                // В old-format: имя типа → sourceDescription; хвост после 2-го «-» →
                // TypeSection.getDescription() (Section). sourceDescription трогать нельзя —
                // иначе в описание параметра попадают имена типов (дубль в param-hint).
                Object source = Global.invoke(typeSection, "getDescription"); //$NON-NLS-1$
                if (!hasText(source))
                    continue;
                Object partsObj = Global.invoke(source, "getParts"); //$NON-NLS-1$
                if (!(partsObj instanceof List<?> parts) || parts.isEmpty())
                    continue;
                @SuppressWarnings({ "unchecked", "rawtypes" }) //$NON-NLS-1$ //$NON-NLS-2$
                List rawParts = (List) parts;
                Global.invoke(fieldDesc, "addParts", new ArrayList<>(rawParts)); //$NON-NLS-1$
                rawParts.clear();
                fieldMoved = true;
            }
            if (fieldMoved)
                moved++;
        }
        return moved;
    }

    /**
     * Запасной путь для UI: re-parse комментария метода и текст описания параметра.
     * Пишет в {@code ValueContent.description}, если он пуст.
     */
    public static String recoverParamDescription(Object method, String paramName, Object valueContent)
    {
        String text = recoverParamDescription(method, paramName);
        if (text != null && valueContent != null)
            applyDescriptionToValueContent(valueContent, text);
        return text;
    }

    public static String recoverParamDescription(Object method, String paramName)
    {
        if (method == null || paramName == null || paramName.isEmpty())
            return null;
        if (!(method instanceof EObject eObject))
        {
            return null;
        }
        try
        {
            Bundle commentBundle = Platform.getBundle(BSL_COMMENT_BUNDLE);
            if (commentBundle == null)
            {
                return null;
            }
            Class<?> utilsClass = commentBundle.loadClass(TARGET_UTILS);
            Class<?> providerClass = commentBundle.loadClass(PROVIDER_CLASS);
            Bundle bslModel = Platform.getBundle("com._1c.g5.v8.dt.bsl.model"); //$NON-NLS-1$
            if (bslModel == null)
            {
                return null;
            }
            Class<?> bslMethodClass = bslModel.loadClass("com._1c.g5.v8.dt.bsl.model.Method"); //$NON-NLS-1$
            if (!bslMethodClass.isInstance(method))
            {
                return null;
            }

            Resource resource = eObject.eResource();
            if (resource == null || resource.getURI() == null)
            {
                return null;
            }
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(resource.getURI());
            if (rsp == null)
            {
                return null;
            }
            Object provider = rsp.get(providerClass);
            if (provider == null)
            {
                return null;
            }
            Object linesObj = Global.invoke(provider, "getCommentLines", eObject); //$NON-NLS-1$
            if (!(linesObj instanceof List<?> lines) || lines.isEmpty())
            {
                return null;
            }

            java.lang.reflect.Method parseMethod = utilsClass.getMethod(
                "parseTemplateComment", List.class, bslMethodClass, boolean.class); //$NON-NLS-1$
            for (boolean oldFormat : new boolean[] { true, false })
            {
                Object comment = parseMethod.invoke(null, lines, method, Boolean.valueOf(oldFormat));
                if (comment == null)
                    continue;
                normalize(comment);
                String text = readFieldDescriptionText(comment, paramName);
                if (text != null && !text.isBlank())
                    return text.trim();
            }
        }
        catch (Throwable t)
        {
        }
        return null;
    }

    public static void applyDescriptionToValueContent(Object valueContent, String description)
    {
        if (valueContent == null || description == null || description.isBlank())
            return;
        String current = asString(Global.invoke(valueContent, "getDescription")); //$NON-NLS-1$
        if (current != null && !current.isBlank())
            return;
        Global.setFieldForce(valueContent, "description", description); //$NON-NLS-1$
    }

    public static void onInstrumentation(Instrumentation inst)
    {
        instrumentation = inst;
    }

    /**
     * Доп. transformer (param-hint doc pages и т.п.).
     * При отсутствии Instrumentation поднимает agent (как {@link #install()}).
     *
     * @return {@code true}, если transformer зарегистрирован
     */
    public static boolean registerExtraTransformer(ClassFileTransformer transformer,
        String... retransformClassNames)
    {
        if (transformer == null)
            return false;
        if (instrumentation == null)
        {
            try
            {
                ensureInstrumentation();
            }
            catch (Throwable t)
            {
                return false;
            }
        }
        if (instrumentation == null)
        {
            return false;
        }
        try
        {
            instrumentation.addTransformer(transformer, true);
            if (retransformClassNames != null)
            {
                for (Class<?> c : instrumentation.getAllLoadedClasses())
                {
                    String name = c.getName();
                    for (String want : retransformClassNames)
                    {
                        if (want.equals(name))
                        {
                            retransform(instrumentation, c);
                            break;
                        }
                    }
                }
            }
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static String readFieldDescriptionText(Object comment, String paramName)
    {
        Object section = Global.invoke(comment, "getParametersSection"); //$NON-NLS-1$
        if (section == null)
            return null;
        Object field = Global.invoke(section, "getParameterByName", paramName); //$NON-NLS-1$
        if (field == null)
            return null;
        return descriptionToString(Global.invoke(field, "getDescription")); //$NON-NLS-1$
    }

    private static String descriptionToString(Object description)
    {
        if (description == null)
            return null;
        Object partsObj = Global.invoke(description, "getParts"); //$NON-NLS-1$
        if (!(partsObj instanceof List<?> parts) || parts.isEmpty())
            return null;
        StringBuilder sb = new StringBuilder();
        Integer prevLine = null;
        for (Object part : parts)
        {
            if (part == null)
                continue;
            Object lineObj = Global.invoke(part, "getLineNumber"); //$NON-NLS-1$
            if (lineObj instanceof Integer line)
            {
                if (prevLine != null && !prevLine.equals(line))
                    sb.append('\n');
                prevLine = line;
            }
            String cn = part.getClass().getName();
            if (TEXT_PART.equals(cn))
            {
                String text = asString(Global.invoke(part, "getText")); //$NON-NLS-1$
                if (text != null)
                    sb.append(text);
            }
            else if (LINK_PART.equals(cn))
            {
                String text = asString(Global.invoke(part, "getInitialContent")); //$NON-NLS-1$
                if (text != null)
                    sb.append(text);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean hasText(Object description)
    {
        return descriptionToString(description) != null;
    }

    private static String asString(Object value)
    {
        return value instanceof String s ? s : null;
    }

    private static void registerPermanentTransformer(Instrumentation inst)
    {
        if (transformerRegistered)
            return;
        inst.addTransformer(new ParseClassFileTransformer(), true);
        transformerRegistered = true;
    }

    private static boolean retransform(Instrumentation inst, Class<?> target)
    {
        try
        {
            if (!inst.isModifiableClass(target))
            {
                return false;
            }
            inst.retransformClasses(target);
            return true;
        }
        catch (UnmodifiableClassException e)
        {
            return false;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private static Instrumentation ensureInstrumentation() throws Exception
    {
        if (instrumentation != null)
            return instrumentation;

        Path agentJar = writeAgentJar();
        String pid = Long.toString(ProcessHandle.current().pid());
        Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine"); //$NON-NLS-1$
        Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid); //$NON-NLS-1$
        try
        {
            vmClass.getMethod("loadAgent", String.class).invoke(vm, //$NON-NLS-1$
                agentJar.toAbsolutePath().toString());
        }
        finally
        {
            vmClass.getMethod("detach").invoke(vm); //$NON-NLS-1$
        }

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (instrumentation == null && System.nanoTime() < deadline)
            Thread.sleep(20);
        return instrumentation;
    }

    private static Path writeAgentJar() throws IOException, InterruptedException
    {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "edt-comfort-agents"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(dir);
        Path jar = dir.resolve("bsl-doc-comment-fix-agent.jar"); //$NON-NLS-1$
        Path classes = dir.resolve("agent-classes"); //$NON-NLS-1$
        Files.createDirectories(classes);

        String agentSrc = ""
            + "package tormozit;\n"
            + "import java.lang.instrument.Instrumentation;\n"
            + "public class BslDocCommentFixAgent {\n"
            + "  public static void agentmain(String args, Instrumentation inst) throws Exception {\n"
            + "    for (Class<?> c : inst.getAllLoadedClasses()) {\n"
            + "      if (\"tormozit.BslDocCommentDescriptionFix\".equals(c.getName())) {\n"
            + "        c.getMethod(\"onInstrumentation\", Instrumentation.class).invoke(null, inst);\n"
            + "        return;\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
        Path srcFile = dir.resolve("BslDocCommentFixAgent.java"); //$NON-NLS-1$
        Files.writeString(srcFile, agentSrc, StandardCharsets.UTF_8);

        Path javac = resolveJavac();
        Process compile = new ProcessBuilder(
            javac.toString(),
            "-d", classes.toString(), //$NON-NLS-1$
            srcFile.toString())
            .redirectErrorStream(true)
            .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (compile.waitFor() != 0)
            throw new IOException("javac agent failed: " + compileOut); //$NON-NLS-1$

        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0"); //$NON-NLS-1$
        attrs.putValue("Agent-Class", "tormozit.BslDocCommentFixAgent"); //$NON-NLS-1$ //$NON-NLS-2$
        attrs.putValue("Can-Redefine-Classes", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        attrs.putValue("Can-Retransform-Classes", "true"); //$NON-NLS-1$ //$NON-NLS-2$

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), mf))
        {
            Path classFile = classes.resolve("tormozit").resolve("BslDocCommentFixAgent.class"); //$NON-NLS-1$ //$NON-NLS-2$
            jos.putNextEntry(new JarEntry("tormozit/BslDocCommentFixAgent.class")); //$NON-NLS-1$
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }
        return jar;
    }

    private static Path resolveJavac() throws IOException
    {
        String javaHome = System.getProperty("java.home"); //$NON-NLS-1$
        if (javaHome != null)
        {
            Path home = Path.of(javaHome);
            Path javac = home.resolve("bin").resolve("javac.exe"); //$NON-NLS-1$ //$NON-NLS-2$
            if (Files.isRegularFile(javac))
                return javac;
            javac = home.resolve("bin").resolve("javac"); //$NON-NLS-1$ //$NON-NLS-2$
            if (Files.isRegularFile(javac))
                return javac;
            Path parent = home.getParent();
            if (parent != null)
            {
                javac = parent.resolve("bin").resolve("javac.exe"); //$NON-NLS-1$ //$NON-NLS-2$
                if (Files.isRegularFile(javac))
                    return javac;
            }
        }
        Path axiom = Path.of(AXIOM_JAVAC);
        if (Files.isRegularFile(axiom))
            return axiom;
        throw new IOException("javac not found"); //$NON-NLS-1$
    }

    static byte[] transformClass(String internalName, byte[] classfileBuffer)
    {
        if (TARGET_COMMENT_INTERNAL.equals(internalName))
            return transformCommentClass(classfileBuffer);
        if (TARGET_UTILS_INTERNAL.equals(internalName))
            return transformUtilsClass(classfileBuffer);
        return null;
    }

    private static byte[] transformCommentClass(byte[] classfileBuffer)
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
        final AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null)
                    return null;
                if (("parse".equals(name) || "parseOldFormat".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                    && PARSE_VOID_DESC.equals(descriptor))
                {
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.RETURN)
                            {
                                emitHookFromThis(this);
                                touched.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static byte[] transformUtilsClass(byte[] classfileBuffer)
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
        final AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null)
                    return null;
                if (!"parseTemplateComment".equals(name) && !"parse".equals(name)) //$NON-NLS-1$ //$NON-NLS-2$
                    return mv;
                Type returnType = Type.getReturnType(descriptor);
                if (!COMMENT_RETURN.equals(returnType.getDescriptor()))
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.ARETURN)
                        {
                            emitHookFromStack(this);
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return touched.get() ? writer.toByteArray() : null;
    }

    private static void emitHookFromThis(MethodVisitor mv)
    {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        emitConsumerAccept(mv);
    }

    private static void emitHookFromStack(MethodVisitor mv)
    {
        mv.visitInsn(Opcodes.DUP);
        emitConsumerAccept(mv);
    }

    /** Стек: …, comment → после вызова comment остаётся (accept съедает копию). */
    private static void emitConsumerAccept(MethodVisitor mv)
    {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
            "()Ljava/util/Properties;", false); //$NON-NLS-1$
        mv.visitLdcInsn(PROP_AFTER_PARSE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
        mv.visitInsn(Opcodes.DUP);
        Label skip = new Label();
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer"); //$NON-NLS-1$
        mv.visitJumpInsn(Opcodes.IFEQ, skip);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer"); //$NON-NLS-1$
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
            "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
        Label end = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(end);
    }

    private static final class ParseWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            String name = wovenClass.getClassName();
            if (!TARGET_COMMENT.equals(name) && !TARGET_UTILS.equals(name))
                return;
            if (wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            try
            {
                String internal = name.replace('.', '/');
                byte[] transformed = transformClass(internal, wovenClass.getBytes());
                if (transformed != null)
                {
                    wovenClass.setBytes(transformed);
                }
            }
            catch (Throwable t)
            {
            }
        }
    }

    private static final class ParseClassFileTransformer implements ClassFileTransformer
    {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer)
        {
            if (!TARGET_COMMENT_INTERNAL.equals(className)
                && !TARGET_UTILS_INTERNAL.equals(className))
                return null;
            try
            {
                return transformClass(className, classfileBuffer);
            }
            catch (Throwable t)
            {
                return null;
            }
        }
    }
}
