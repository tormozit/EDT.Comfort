package tormozit.early;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
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
        CheckRecomputeNotifications.install(context);
        EarlyWeaving.install(context);
    }

    @Override
    public void stop(BundleContext context)
    {
        System.getProperties().put(CheckRecomputeNotifications.CALLBACK, (Consumer<Object[]>) event -> {});
    }

    /** Уведомления о полном пересчёте проверок; ранний бандл не зависит от Workbench. */
    private static final class CheckRecomputeNotifications implements WeavingHook
    {
        private static final String CALLBACK = "tormozit.checkRecompute.event"; //$NON-NLS-1$
        private static final String SCHEDULER = "com.e1c.g5.v8.dt.internal.check.CheckScheduler"; //$NON-NLS-1$
        private static final String METADATA =
            "com.e1c.g5.v8.dt.internal.check.repository.ProjectCheckMetadataManager"; //$NON-NLS-1$
        private static final String DERIVED = "com._1c.g5.v8.internal.derived.DerivedDataManager"; //$NON-NLS-1$
        private static final String FULL_SCOPE_DESC =
            "(Lorg/eclipse/core/resources/IProject;Ljava/util/Set;Lcom/e1c/g5/v8/dt/check/CheckComplexity;" //$NON-NLS-1$
                + "Lcom/_1c/g5/v8/bm/core/IBmTransaction;Lorg/eclipse/core/runtime/IProgressMonitor;)V"; //$NON-NLS-1$

        static void install(BundleContext context)
        {
            CheckRecomputeNotifications notifications = new CheckRecomputeNotifications();
            System.getProperties().put(CALLBACK, (Consumer<Object[]>) notifications::notifyFullRecompute);
            context.registerService(WeavingHook.class, notifications, null);
        }

        @Override
        public void weave(WovenClass wovenClass)
        {
            String name = wovenClass.getClassName();
            if (wovenClass.getState() != WovenClass.TRANSFORMING
                || !(SCHEDULER.equals(name) || DERIVED.equals(name)))
                return;
            try
            {
                ClassReader reader = new ClassReader(wovenClass.getBytes());
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] matches = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
                {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String descriptor,
                        String signature, String[] exceptions)
                    {
                        MethodVisitor visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
                        String event = null;
                        if (SCHEDULER.equals(name) && "scheduleScopedFullRebuild".equals(method) //$NON-NLS-1$
                            && FULL_SCOPE_DESC.equals(descriptor))
                            event = "full-check-scope"; //$NON-NLS-1$
                        if (DERIVED.equals(name) && "recomputeAll".equals(method) && "()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                            event = "derived-recompute-all"; //$NON-NLS-1$
                        if (event == null || (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                            return visitor;
                        matches[0]++;
                        String phase = event;
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                EarlyWeaving.emitGetProperty(this, CALLBACK, "java/util/function/Consumer"); //$NON-NLS-1$
                                int count = SCHEDULER.equals(name) ? 4 : 2;
                                visitLdcInsn(count);
                                visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object"); //$NON-NLS-1$
                                visitInsn(Opcodes.DUP);
                                visitInsn(Opcodes.ICONST_0);
                                visitLdcInsn(phase);
                                visitInsn(Opcodes.AASTORE);
                                for (int i = 1; i < count; i++)
                                {
                                    visitInsn(Opcodes.DUP);
                                    visitLdcInsn(i);
                                    visitVarInsn(Opcodes.ALOAD, SCHEDULER.equals(name) ? i : 0);
                                    visitInsn(Opcodes.AASTORE);
                                }
                                visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
                            }
                        };
                    }
                }, 0);
                if (matches[0] == 1)
                    wovenClass.setBytes(writer.toByteArray());
            }
            catch (Throwable ignored)
            {
                // Несовместимый класс остаётся штатным; уведомления не должны мешать EDT.
            }
        }

        private void notifyFullRecompute(Object[] event)
        {
            try
            {
                String reason = notificationReason(event, Thread.currentThread().getStackTrace());
                if (reason == null)
                    return;
                String message = ("full-check-scope".equals(event[0]) //$NON-NLS-1$
                    ? "Назначена полная перепроверка проекта " + event[1] //$NON-NLS-1$
                    : "Запрошен полный пересчёт производных данных") + ".\nПричина: " + reason; //$NON-NLS-1$ //$NON-NLS-2$
                Object[] notification = {message, event[1]};
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
                        pending.put(event[1], notification);
                    }
                }
                if (consumer != null)
                    consumer.accept(notification);
            }
            catch (Throwable ignored)
            {
                // Отказ уведомления не должен прерывать расчёт EDT.
            }
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
        private static final String DOC_LOCKER =
            "com._1c.g5.v8.dt.bsl.ui.editor.BslXtextDocument$CustomXtextDocumentLocker"; //$NON-NLS-1$
        /** Совпадает с {@code BslXtextDocumentHook.PROP_SKIP_WAIT}. */
        static final String PROP_SKIP_WAIT = "tormozit.bslXtextDoc.skipWaitUpdatingDataModel"; //$NON-NLS-1$
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
            SUPPRESSION_PROVIDER, FOLDING_PROVIDER, FOLDING_STRUCTURE, TAB_FOLDER, PART_LIST_CONTROL, COMPARISON_EDITOR,
            MERGE_COMMIT_CALLBACK, EDITOR_MATCHING, GRANULAR_EDITOR, TREE_VIEWER, SEARCH_TREE_LAYOUT, NAVIGATOR_MAPPER,
            FORM_PRESENTATION, FORM_PAGE, PROPERTY_SHEET, PROPERTY_SHEET_MONITOR,
            MULTILANGUAGE_COMPONENT, SINGLE_LANGUAGE_COMPONENT,
            DCS_LOCAL_STRING_EDITOR, DCS_FIELD_EDITING, DCS_PARAMETER_EDITING, DOC_LOCKER);

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
                    System.setProperty(PROP_DOC_LOCKER_PATCH, "error " + t); //$NON-NLS-1$
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
            if (DOC_LOCKER.equals(className))
                return transformDocLocker(bytes);
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

        /**
         * {@link #DOC_LOCKER}: ветвление и обработчик исключений — кадры пересчитываются
         * ({@code COMPUTE_FRAMES}, общий предок — {@code Object}, классы EDT не загружаются), как в
         * {@link #transformFoldingProvider}. Функции ставит {@link #install} до хука, поэтому
         * вплетённый код зовёт их без проверок.
         */
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
            int[] touched = new int[2];
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
                    if ("readOnlyForContentAssist".equals(name) && READ_FOR_ASSIST_DESC.equals(descriptor)) //$NON-NLS-1$
                    {
                        touched[1]++;
                        return new AssistReadVisitor(mv);
                    }
                    return mv;
                }
            }, ClassReader.EXPAND_FRAMES);
            boolean woven = touched[0] == 1 && touched[1] == 1;
            System.setProperty(PROP_DOC_LOCKER_PATCH, (woven ? "woven" : "skipped") //$NON-NLS-1$ //$NON-NLS-2$
                + " wait=" + touched[0] + " read=" + touched[1]); //$NON-NLS-1$ //$NON-NLS-2$
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
}
