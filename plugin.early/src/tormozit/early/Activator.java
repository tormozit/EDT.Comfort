package tormozit.early;

import java.util.List;
import java.util.function.BiFunction;
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
        EarlyWeaving.install(context);
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
        /** Совпадает с MdEditorTabsHook.PROP_PAGES_CREATED. */
        private static final String PROP_PAGES_CREATED = "tormozit.mdEditor.pagesCreated"; //$NON-NLS-1$
        private static final String TREE_VIEWER = "org.eclipse.jface.viewers.AbstractTreeViewer"; //$NON-NLS-1$
        private static final String SEARCH_TREE_LAYOUT =
            "com._1c.g5.v8.dt.internal.search.ui.provider.SearchResultTreeLayoutManager"; //$NON-NLS-1$
        private static final String PROP_COMMON_CHILDREN = "tormozit.commonNode.children"; //$NON-NLS-1$
        private static final String PROP_COMMON_SEARCH_NODE = "tormozit.commonNode.searchNode"; //$NON-NLS-1$
        /** Совпадает с WorkbenchTabsHook.EditorModelMatching.PROPERTY. */
        static final String PROP_EDITOR_MODEL_EQUALS = "tormozit.editor.modelEquals"; //$NON-NLS-1$
        private static final List<String> TARGETS = List.of(BSL_PARSER, CUSTOM_BSL_PARSER, HELPER,
            SUPPRESSION_PROVIDER, FOLDING_PROVIDER, TAB_FOLDER, PART_LIST_CONTROL, COMPARISON_EDITOR,
            MERGE_COMMIT_CALLBACK, EDITOR_MATCHING, GRANULAR_EDITOR, TREE_VIEWER, SEARCH_TREE_LAYOUT);

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
            System.getProperties().putIfAbsent(PROP_PAGES_CREATED, (Consumer<Object>) editor -> {});
            System.getProperties().putIfAbsent(PROP_COMMON_CHILDREN,
                (BiFunction<Object, Object, Object>) (parent, children) -> children);
            System.getProperties().putIfAbsent(PROP_COMMON_SEARCH_NODE,
                (BiFunction<Object, Object, Object>) (adapter, node) -> node);
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
                // Класс остаётся штатным: лучше без исправления, чем без парсера.
                if (TAB_FOLDER.equals(name))
                    System.setProperty(PROP_TAB_FOLDER_CHEVRON, "error " + t); //$NON-NLS-1$
                if (PART_LIST_CONTROL.equals(name))
                    System.setProperty(PROP_PART_LIST_CONTROL, "error " + t); //$NON-NLS-1$
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

        /** Кадры стека остаются исходными: вставки без ветвлений и не меняют высоту стека в точках кадров. */
        static byte[] transform(String className, byte[] bytes)
        {
            if (TREE_VIEWER.equals(className) || SEARCH_TREE_LAYOUT.equals(className))
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
            String method = tree ? "getSortedChildren" : "insertWorkbenchAdapter"; //$NON-NLS-1$ //$NON-NLS-2$
            String descriptor = tree ? "(Ljava/lang/Object;)[Ljava/lang/Object;" //$NON-NLS-1$
                : "(Lorg/eclipse/ui/model/IWorkbenchAdapter;Ljava/lang/Object;Lcom/_1c/g5/v8/bm/integration/IBmModel;)Lcom/_1c/g5/v8/dt/internal/search/ui/provider/MatchTreeItem;"; //$NON-NLS-1$
            String property = tree ? PROP_COMMON_CHILDREN : PROP_COMMON_SEARCH_NODE;
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

        /**
         * В отличие от остальных вставок здесь есть ветвления, поэтому кадры стека пересчитываются
         * ({@code COMPUTE_FRAMES}); общий предок типов — {@code Object}, классы EDT не загружаются.
         */
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
