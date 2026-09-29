package tormozit.bslparser;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

/**
 * Ранний старт: регистрация вплетения в парсер BSL до его загрузки (см. {@link BslParserHook}).
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
        ParserWeaving.install(context);
    }

    @Override
    public void stop(BundleContext context)
    {
    }

    /**
     * Вплетение через {@link WeavingHook}, вызовы наружу — через {@code System.getProperties}, без
     * ветвлений в байткоде (кадры стека чужих методов не пересчитываются).
     *
     * <p>Парсеры: {@link #PROP_BEFORE_CREATE_PARSER} в начале {@code createParser(XtextTokenStream)}.
     * Помощник частичного разбора: результат каждого {@code IParser.parse} в {@code reparse} проходит
     * через {@link #PROP_AFTER_CHUNK_PARSE}.
     */
    private static final class ParserWeaving
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
        private static final List<String> TARGETS = List.of(BSL_PARSER, CUSTOM_BSL_PARSER, HELPER, SUPPRESSION_PROVIDER);

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
            System.getProperties().put(PROP_BEFORE_CREATE_PARSER,
                (Consumer<Object>) stream -> BslParserHook.beforeCreateParser(stream));
            System.getProperties().put(PROP_AFTER_CHUNK_PARSE,
                (Function<Object, Object>) result -> BslParserHook.afterChunkParse(result));
            // Не «//…» бывает только скрытая нами директива: для разбора подавлений — пустой комментарий.
            System.getProperties().put(PROP_SUPPRESSION_COMMENT,
                (Function<Object, Object>) text -> text instanceof String s && !s.startsWith("//") ? "//" : text); //$NON-NLS-1$ //$NON-NLS-2$
            context.registerService(WeavingHook.class, new ParserWeaving(), null);
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
                // Класс остаётся штатным: лучше без исправления, чем без парсера.
            }
        }

        /** Кадры стека остаются исходными: вставки без ветвлений и не меняют высоту стека в точках кадров. */
        static byte[] transform(String className, byte[] bytes)
        {
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
}
