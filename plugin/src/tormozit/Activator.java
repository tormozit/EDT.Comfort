package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.ui.editor.autoedit.SingleLineTerminalsStrategy;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexProvider;
import com._1c.g5.v8.dt.bsl.model.IfPreprocessorStatement;
import com._1c.g5.v8.dt.bsl.model.IfStatement;
import com._1c.g5.v8.dt.bsl.model.LoopStatement;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessorStatement;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.TryExceptStatement;
import com._1c.g5.v8.dt.bsl.validation.BslSuppressionProvider;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPService;
import com._1c.g5.v8.dt.metadata.mdclass.URLTemplate;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.ql.model.CastOperationExpression;
import com._1c.g5.v8.dt.ql.model.NullLiteralExpression;
import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.BslDirectLocationIssue;
import com.e1c.g5.v8.dt.check.DirectLocation;
import com.e1c.g5.v8.dt.check.components.BasicCheck;

/**
 * Activator (точка входа) плагина EDT Compare - Open Object.
 *
 * <p><b>Важно:</b> В {@code start()} выполняется ТОЛЬКО инициализация
 * синглтонов без UI-кода — никакого {@code syncExec} или обращений к
 * Workbench. {@link ContentAssistManager#start()} вызывается
 * позже из {@code CompareConfigMenuHook.earlyStartup()}, когда Workbench
 * гарантированно инициализирован.
 */
public class Activator extends AbstractUIPlugin
{
    public static final String PLUGIN_ID = "tormozit"; //$NON-NLS-1$

    private static Activator instance;

    /** Метка сборки для проверки, что реально загружен новый код (не берётся из кэша/старого JAR). */
    private static final String BUILD_MARKER = "20260713-perf-diag-v6"; //$NON-NLS-1$

    @Override
    public void start(BundleContext context) throws Exception
    {
        Global.clearTempLogs();
        Global.cleanOrphanedFormNativeTempDirs();

        super.start(context);
        instance = this;
        RightsEditDialogHook.start(context);
        BuiltinCheckReplacements.start(context);
        QlHubOpenModules.start();
        SuppressionRanges.start();
        ProblemViewHook.AnchorLocation.start();
        MarkerCreationDates.start();
        EmptyExceptStatementFix.start();
        UiWaitCheckDeferral.start();
        QueryNullCastFix.start();
        CheckRecomputeNotifications.install();
        CheckThreadPriority.start();
        FunctionReturnTypes.installLocalContextInvalidation();
        TypeDescriptionPresentationHook.install(context);
        PropertySheetUiContext.installSelectionCallbacks();
        FormEditorHook.installPreviewCallbacks();
        CommonNodeAlphabeticSorter.installGlobally();
        // Раньше всего остального: WeavingHook ловит только классы, загруженные после его
        // регистрации, а следующие ниже попытки присоединить агент стоят сотен миллисекунд
        // каждая — за это время BslDocumentationComment успевает загрузиться.
        BslDocCommentComputedTypes.installWeavingHook();
        BslDocCommentDescriptionFix.installWeavingHook();
        BslDocCommentTypeMerge.installWeavingHook();
        BslTemplateTypes.installWeavingHook();
        BslQueryLiteralSupport.installCheckWeavingHook();
        BslOccurrenceMarkerHook.installLiteralCallbacks();
        BslEditorFoldingHook.install();
        BslFormTypeContextEnrichment.installWeavingHook();
        DynamicFeatureAccessComputerHook.installWeavingHook();
        ConfigSearchDialogHook.installWeavingHook();
        BslReferenceSearchTableHook.installSearchResultCallbacks();
        DtGranularEditorHook.installWeavingHook();
        MdEditorTreeHook.installWeavingHook();
        ValidationChecksFilterHook.installWeavingHook(context);
        NavigatorFilterHook.installProblemSeverityWeaving();
        MdEventHandlersPageHook.installWeavingHook();
        OpenHelperAttributePropertiesHook.installWeavingHook();
        WorkbenchTabsHook.installWeavingHook();
        ProblemViewHook.installWeavingHook();
        MoxelEditorHook.installWeavingHooks();
        QueryWizardHook.installWeavingHook();
        try
        {
            NaparnikManualModeHook.bootFromActivator();
        }
        catch (Throwable ignored)
        {
        }
        try
        {
            BslModulePositionMemoryHook.bootUi();
        }
        catch (Throwable ignored)
        {
        }
        // Только инициализация синглтонов — никакого UI и обращений к Workbench.
        // manager.start() будет вызван из earlyStartup() после запуска Workbench.
        ContentAssistSettings settings =
            ContentAssistSettings.init(PLUGIN_ID);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_REPLACE_LIST_FILTERS,
            ComfortSettings.DEFAULT_REPLACE_LIST_FILTERS);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_PROBLEM_VIEW_UPDATE_GATE,
            ComfortSettings.DEFAULT_PROBLEM_VIEW_UPDATE_GATE);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_FILTER_MATCH_COLOR,
            ComfortSettings.DEFAULT_FILTER_MATCH_COLOR);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_DEBUG_LOG,
            ComfortSettings.DEFAULT_DEBUG_LOG);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_IMPROVE_DEBUGGER_WINDOWS,
            ComfortSettings.DEFAULT_IMPROVE_DEBUGGER_WINDOWS);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_CONTROL_MD_REFERENCES,
            ComfortSettings.DEFAULT_CONTROL_MD_REFERENCES);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED,
            ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_ENABLED);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR,
            ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR,
            ComfortSettings.DEFAULT_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_ENABLED,
            ComfortSettings.DEFAULT_IMPLICIT_VARIABLE_HIGHLIGHTING_ENABLED);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_COLOR,
            ComfortSettings.DEFAULT_IMPLICIT_VARIABLE_HIGHLIGHTING_COLOR);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_QUERY_LITERAL_HIGHLIGHTING_ENABLED,
            ComfortSettings.DEFAULT_QUERY_LITERAL_HIGHLIGHTING_ENABLED);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_ECONOMICAL_METHOD_COMMENT,
            ComfortSettings.DEFAULT_ECONOMICAL_METHOD_COMMENT);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED,
            ComfortSettings.DEFAULT_BRACKET_CONTENT_HINT_ENABLED);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES,
            ComfortSettings.DEFAULT_BRACKET_CONTENT_HINT_MIN_LINES);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_NAPARNIK_ASSIST_ROW_ENABLED,
            ComfortSettings.DEFAULT_NAPARNIK_ASSIST_ROW_ENABLED);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_COLLAPSE_ASSIST_PARAMS,
            ComfortSettings.DEFAULT_COLLAPSE_ASSIST_PARAMS);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_ASSIST_SIDE_HINT_DELAY,
            ComfortSettings.DEFAULT_ASSIST_SIDE_HINT_DELAY);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_COMPILE_CONTEXT_STATUS_WIDTH,
            ComfortSettings.DEFAULT_COMPILE_CONTEXT_STATUS_WIDTH);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_AUTO_COLLAPSE_REGIONS,
            ComfortSettings.DEFAULT_AUTO_COLLAPSE_REGIONS);
        ComfortSettings.applyIndentGuideDefaults(settings.getPreferenceStore());
        settings.getPreferenceStore().setDefault(ComfortSettings.PREF_FOLD_BATCH_QUERIES, true);
        settings.getPreferenceStore().setDefault(ComfortSettings.PREF_FOLD_TEXT_LITERALS, true);
        settings.getPreferenceStore().setDefault(ComfortSettings.PREF_FOLD_TEXT_LITERALS_INITIALLY, false);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_SPELLING_CHECK_IDENTIFIERS_VISIBLE,
            ComfortSettings.DEFAULT_SPELLING_CHECK_IDENTIFIERS_VISIBLE);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_GROUP_COMMON_MODULES_ENABLED,
            ComfortSettings.DEFAULT_GROUP_COMMON_MODULES_ENABLED);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_GROUP_COMMON_MODULES_SUFFIXES_1,
            ComfortSettings.DEFAULT_GROUP_COMMON_MODULES_SUFFIXES_1);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_GROUP_COMMON_MODULES_SUFFIXES_2,
            ComfortSettings.DEFAULT_GROUP_COMMON_MODULES_SUFFIXES_2);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_ALPHABETIC_COMMON_NODE_ENABLED,
            ComfortSettings.DEFAULT_ALPHABETIC_COMMON_NODE_ENABLED);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_MD_EDITOR_VERTICAL_TABS,
            ComfortSettings.DEFAULT_MD_EDITOR_VERTICAL_TABS);
        ContentAssistManager.init(settings);
        ComfortSettings.init(PLUGIN_ID);
        ComfortSettings.getInstance().getPreferenceStore().setDefault(
            ComfortSettings.PREF_PROBLEM_VIEW_UPDATE_GATE,
            ComfortSettings.DEFAULT_PROBLEM_VIEW_UPDATE_GATE);
        CommonNodeAlphabeticSorter.installCommonFolderLabels();
        settings.getPreferenceStore().addPropertyChangeListener(event -> {
            if (ComfortSettings.PREF_FILTER_MATCH_COLOR.equals(event.getProperty()))
                SmartMatchHighlight.clearColorCache();
        });
        getPreferenceStore().setDefault(
            ComfortSettings.PREF_DEBUG_INSPECTOR_AUTO_CLOSE,
            ComfortSettings.DEFAULT_DEBUG_INSPECTOR_AUTO_CLOSE);
        // Подключаем персистентное хранилище последних мест.
        RecentPlaces.getInstance().init(PLUGIN_ID);
        ObjectSets.getInstance().init(PLUGIN_ID);
        ObjectSetsAddTargetState.getInstance().init(PLUGIN_ID);
        for (ObjectSets.SetDef set : ObjectSets.getInstance().getAllSets())
            ObjectSetsAddTargetState.getInstance().ensureForProject(set.projectName);

        ComfortUpdateChecker.startDailyScheduler();
        IRModuleChangeCollector.ensureListenerInstalled();
        // Как можно раньше: WeavingHook до первой загрузки BslDocumentationComment
        BslDocCommentDescriptionFix.install();
        BslFormTypeContextEnrichment.installAttributeTypes();
        BslTypesComputerHook.install();
        BslTemplateTypes.install();
        BslDocCommentTypeMerge.install();
        BslLiteralValues.installTypeNameCache();
        BslXtextDocumentHook.install();
        QueryParameterAutoCloseFix.install();
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        BslLiteralValues.stopTypeNameCache();
        FunctionReturnTypes.stopLocalContextInvalidation();
        CheckRecomputeNotifications.stop();
        BuiltinCheckReplacements.stop();
        QlHubOpenModules.stop();
        SuppressionRanges.stop();
        ProblemViewHook.AnchorLocation.stop();
        MarkerCreationDates.stop();
        EmptyExceptStatementFix.stop();
        UiWaitCheckDeferral.stop();
        RightsEditDialogHook.stop();
        QueryNullCastFix.stop();
        IRApplication.disconnectAll();

        ContentAssistManager mgr = ContentAssistManager.getInstance();
        if (mgr != null)
            mgr.stop();

        Global.stopTempLogFlusher();

        instance = null;
        super.stop(context);
    }

    /** Уведомления о полном пересчёте; ранний бандл не зависит от Workbench. */
    private static final class CheckRecomputeNotifications
    {
        private static final String CALLBACK = "tormozit.checkRecompute.event.notify"; //$NON-NLS-1$
        private static final String PENDING = "tormozit.checkRecompute.event.pending"; //$NON-NLS-1$
        /** Шлюз раннего бандла пропускает ручной запуск без вопроса. */
        private static final String MANUAL = "tormozit.checkRecompute.event.manual"; //$NON-NLS-1$
        /** И объекты, открытые в редакторах. */
        private static final String OPEN = "tormozit.checkRecompute.event.open"; //$NON-NLS-1$
        /** Шлюз пропустил контекст ({@code BiConsumer<IProject, «сегмент\nобъект»>}) — снять с отложенных. */
        private static final String PASSED = "tormozit.checkRecompute.event.passed"; //$NON-NLS-1$
        /** Суффикс ключа очереди: отложенные шлюзом контексты не вытесняют прочие уведомления проекта. */
        private static final String GATE_KEY = "\tgate"; //$NON-NLS-1$
        private static final long INTERVAL_NS = 60_000_000_000L;
        /** Порог шлюза раннего бандла ({@code MassChanges.THRESHOLD}): меньшее число отложенных объектов не предлагаем. */
        private static final int GATE_OFFER_MIN_OBJECTS = 100;
        private static final java.util.concurrent.ConcurrentMap<String, Object[]> pending =
            new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.concurrent.atomic.AtomicBoolean posted =
            new java.util.concurrent.atomic.AtomicBoolean();
        private static volatile boolean running;
        private static final java.util.concurrent.ConcurrentMap<String, Long> lastToastNs =
            new java.util.concurrent.ConcurrentHashMap<>();

        static void install()
        {
            running = true;
            Object saved;
            var properties = System.getProperties();
            synchronized (properties)
            {
                properties.put(MANUAL, (java.util.function.Predicate<Object>) MassiveChecksExemptionHook::isManual);
                properties.put(OPEN, (java.util.function.Predicate<Object>) MassiveChecksExemptionHook::isOpenObject);
                properties.put(PASSED, (java.util.function.BiConsumer<Object, Object>) DeferredCheckContexts::passed);
                properties.put(CALLBACK, (java.util.function.Consumer<Object[]>) CheckRecomputeNotifications::accept);
                saved = properties.remove(PENDING);
            }
            if (saved instanceof java.util.Map<?, ?> events)
                for (Object value : events.values())
                    if (value instanceof Object[] event)
                        accept(event);
        }

        static void stop()
        {
            running = false;
            System.getProperties().remove(CALLBACK);
            System.getProperties().remove(MANUAL);
            System.getProperties().remove(OPEN);
            System.getProperties().remove(PASSED);
            pending.clear();
            lastToastNs.clear();
        }

        private static void accept(Object[] event)
        {
            if (!running)
                return;
            boolean gate = isGateOffer(event);
            String key = projectKey(event[3]) + (gate ? GATE_KEY : ""); //$NON-NLS-1$
            // Предложение отложенной проверки важнее сопутствующей диагностики того же проекта;
            // отложенные шлюзом контексты складываются, пока предложение не показано.
            pending.compute(key, (ignored, previous) -> gate ? mergeGateOffers(previous, event)
                : previous != null && previous.length > 4 && event.length <= 4 ? previous : event);
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed() && posted.compareAndSet(false, true))
                display.asyncExec(() -> showPending(display));
        }

        private static void showPending(Display display)
        {
            if (!running || display.isDisposed())
            {
                posted.set(false);
                return;
            }
            if (!org.eclipse.ui.PlatformUI.isWorkbenchRunning()
                || org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows().length == 0)
            {
                display.timerExec(500, () -> showPending(display));
                return;
            }
            posted.set(false);
            pending.forEach((key, event) -> {
                if (pending.remove(key, event))
                    showEvent(key, event);
            });
        }

        private static String projectKey(Object source)
        {
            if (source instanceof org.eclipse.core.resources.IProject project)
                return project.getName();
            // DerivedDataManager.model и IBmModelManager.getProject подтверждены javap.
            if (Global.getField(source, "model") instanceof com._1c.g5.v8.bm.integration.IBmModel model) //$NON-NLS-1$
            {
                IBmModelManager models = Global.getOsgiService(IBmModelManager.class);
                var project = models != null ? models.getProject(model) : null;
                if (project != null)
                    return project.getName();
            }
            return source.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(source)); //$NON-NLS-1$
        }

        /** {@code event[4]} — список контекстов, отложенных шлюзом раннего бандла. */
        private static boolean isGateOffer(Object[] event)
        {
            return event.length > 4 && event[4] instanceof java.util.List<?>;
        }

        private static Object[] mergeGateOffers(Object[] previous, Object[] next)
        {
            if (previous == null || !isGateOffer(previous))
                return next;
            java.util.List<Object> all = new java.util.ArrayList<>((java.util.List<?>) previous[4]);
            all.addAll((java.util.List<?>) next[4]);
            // event[1] — причины через запятую
            java.util.Set<String> reasons = new java.util.LinkedHashSet<>(
                java.util.List.of(String.valueOf(previous[1]).split(", "))); //$NON-NLS-1$
            reasons.addAll(java.util.List.of(String.valueOf(next[1]).split(", "))); //$NON-NLS-1$
            Object[] merged = next.clone();
            merged[1] = String.join(", ", reasons); //$NON-NLS-1$
            merged[4] = java.util.List.copyOf(all);
            return merged;
        }

        /** {@code objects} — все отложенные объекты проекта, «Запустить» пересчитывает их все. */
        private static void showGateOffer(org.eclipse.core.resources.IProject project, int objects, String reasons,
            boolean autoHide)
        {
            String title = "Запустить проверки проекта «" + project.getName() + "»?";
            String message = "Отложен пересчет проверок " + objects + " " //$NON-NLS-1$
                + Global.russianPlural(objects, "объекта", "объектов", "объектов") + ".\nПричина запуска: " + reasons; //$NON-NLS-1$
            Runnable run = () -> DeferredCheckContexts.recompute(project);
            // Число в предложении — весь список проекта: прежнее предложение проекта устарело
            Shell previous = gateOffers.remove(project.getName());
            if (previous != null && !previous.isDisposed())
                ToastNotification.close(previous);
            // Включён «Отключить массовые проверки»: откладывание ожидаемо, предложение скрывается само
            Shell shell = autoHide
                ? ToastNotification.show(title, message, 5_000, run, "Запустить проверки")
                : ToastNotification.showStickyUntilToastHover(title, message, run, "Запустить проверки");
            if (shell != null)
                gateOffers.put(project.getName(), shell);
        }

        /** Имя проекта → показанное предложение «Запустить» отложенного; только поток UI. */
        private static final java.util.Map<String, Shell> gateOffers = new java.util.HashMap<>();

        private static void showEvent(String projectKey, Object[] event)
        {
            // Предложение по отложенным контекстам не прореживается: без него они не пересчитаются
            if (isGateOffer(event) && event[3] instanceof org.eclipse.core.resources.IProject project)
            {
                java.util.List<?> contexts = (java.util.List<?>) event[4];
                boolean autoHide = event.length > 5 && Boolean.TRUE.equals(event[5]);
                // Ключи объектов читают модель — не в потоке UI
                org.eclipse.core.runtime.jobs.Job job = org.eclipse.core.runtime.jobs.Job.create(
                    "Комфорт: подсчёт отложенных объектов", monitor -> //$NON-NLS-1$
                    {
                        DeferredCheckContexts.add(project, contexts);
                        int objects = DeferredCheckContexts.objectCount(project);
                        Display display = Display.getDefault();
                        // Всё отложенное уже прошло шлюз (например, объект открыли) — предлагать нечего;
                        // меньше порога — отложено штатным «Отключить массовые проверки», не шлюзом
                        if (objects >= GATE_OFFER_MIN_OBJECTS && display != null && !display.isDisposed())
                            display.asyncExec(() -> showGateOffer(project, objects, String.valueOf(event[1]), autoHide));
                        return org.eclipse.core.runtime.Status.OK_STATUS;
                    });
                job.setSystem(true);
                job.schedule();
                return;
            }
            long now = System.nanoTime();
            lastToastNs.entrySet().removeIf(entry -> now - entry.getValue().longValue() >= INTERVAL_NS);
            Long last = lastToastNs.get(projectKey);
            if (last != null && now - last.longValue() < INTERVAL_NS)
                return;
            if (event.length > 4 && event[4] instanceof java.util.Set<?> requested
                && event[3] instanceof org.eclipse.core.resources.IProject project)
            {
                java.util.Set<String> checks = requested.stream().filter(String.class::isInstance)
                    .map(String.class::cast).collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (checks.isEmpty())
                    return;
                var offer = ToastNotification.showStickyUntilToastHover("Запустить проверки проекта «" + project.getName() + "»?",
                    String.valueOf(event[1]),
                    () -> ComfortCheckRecompute.recomputeProject(project, withDeferred(project, checks)),
                    "Запустить проверки");
                if (offer != null)
                    lastToastNs.put(projectKey, Long.valueOf(now));
                return;
            }
            String logMessage = (String) event[0];
            long timeMs = ((Long) event[2]).longValue();
            var toast = ToastNotification.show("Полный пересчёт проверок", //$NON-NLS-1$
                event[1] + ".", 15_000, //$NON-NLS-1$
                () -> LongOperationNotifyHook.openLogEntry(logMessage, timeMs), "Открыть запись журнала"); //$NON-NLS-1$
            if (toast != null)
                lastToastNs.put(projectKey, Long.valueOf(now));
        }

        /** Предложения чаще раза в минуту не показываются: «Запустить» берёт все отложенные проверки проекта. */
        @SuppressWarnings("unchecked")
        private static java.util.Set<String> withDeferred(org.eclipse.core.resources.IProject project,
            java.util.Set<String> checks)
        {
            java.util.Set<String> all = new java.util.LinkedHashSet<>(checks);
            Object bridge = System.getProperties().get("tormozit.checkRecompute.event.results"); //$NON-NLS-1$
            if (bridge instanceof java.util.function.Function<?, ?> function
                && ((java.util.function.Function<Object[], Object>) function)
                    .apply(new Object[] { "snapshot", String.valueOf(project) }) instanceof java.util.Map<?, ?> snapshot) //$NON-NLS-1$
                for (Object key : snapshot.keySet())
                {
                    String entry = String.valueOf(key);
                    all.add(entry.substring(entry.indexOf('\t') + 1));
                }
            return all;
        }
    }

    /**
     * В rights.ui 13.0.100.v202605050943 getCurrentSelectedObjects используется и для
     * списка прав (setRightsInputs), и для сохранения (buttonPressed). У HTTP-сервиса
     * права принадлежат дочерним объектам; одного корня недостаточно (issue 924).
     */
    private static final class RightsEditDialogHook implements WeavingHook, ClassFileTransformer
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.internal.rights.ui.dialogs.RightsEditDialog"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.rightsEditDialog.objects"; //$NON-NLS-1$
        private static volatile boolean woven;

        static void start(BundleContext context)
        {
            System.getProperties().put(CALLBACK,
                (BiFunction<Object, Object, Object>)RightsEditDialogHook::expandObjects);
            RightsEditDialogHook hook = new RightsEditDialogHook();
            context.registerService(WeavingHook.class, hook, null);
            try
            {
                Bundle bundle = org.eclipse.core.runtime.Platform.getBundle("com._1c.g5.v8.dt.rights.ui"); //$NON-NLS-1$
                if (bundle == null)
                    throw new IllegalStateException("Бандл редактора прав EDT недоступен"); //$NON-NLS-1$
                bundle.loadClass(TARGET);
                if (!woven)
                    BslDocCommentDescriptionFix.registerExtraTransformer(hook, TARGET);
            }
            catch (ClassNotFoundException | RuntimeException | LinkageError failure)
            {
                Global.logError("RightsEditDialog", "start", failure); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        static void stop()
        {
            System.getProperties().put(CALLBACK, (BiFunction<Object, Object, Object>)(input, add) -> input);
        }

        @Override
        public void weave(WovenClass type)
        {
            if (type.getState() != WovenClass.TRANSFORMING || !TARGET.equals(type.getClassName()))
                return;
            byte[] rewritten = rewrite(type.getBytes());
            if (rewritten != null)
            {
                type.setBytes(rewritten);
                woven = true;
            }
        }

        private static Object expandObjects(Object input, Object dialog)
        {
            if (!(input instanceof Collection<?> objects))
                return input;
            if (!Boolean.TRUE.equals(Global.getField(dialog, "isAddAction"))) //$NON-NLS-1$
            {
                // Контроллер сохраняет права content[0] и RightsPanel.initRights читает
                // текущие значения из той же строки; список прав должен относиться к ней.
                Object content = Global.getField(dialog, "content"); //$NON-NLS-1$
                Object selected = content instanceof java.util.List<?> rows && !rows.isEmpty()
                    ? Global.invoke(rows.get(0), "getEObject") : null; //$NON-NLS-1$
                if (selected instanceof com._1c.g5.v8.dt.metadata.mdclass.Method method)
                    return java.util.List.of(method);
                return input;
            }
            LinkedHashSet<Object> expanded = null;
            for (Object object : objects)
            {
                if (!(object instanceof HTTPService service))
                    continue;
                if (expanded == null)
                    expanded = new LinkedHashSet<>(objects);
                // Корень сохраняем: не меняем штатную обработку прав самого объекта.
                for (URLTemplate template : service.getUrlTemplates())
                {
                    expanded.addAll(template.getMethods());
                }
            }
            return expanded == null ? input : expanded;
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes)
        {
            if (!TARGET.replace('.', '/').equals(name))
                return null;
            return rewrite(bytes);
        }

        private static byte[] rewrite(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] returns = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
                    if (!"getCurrentSelectedObjects".equals(method) //$NON-NLS-1$
                        || !"()Ljava/util/Collection;".equals(descriptor)) //$NON-NLS-1$
                        return visitor;
                    return new MethodVisitor(Opcodes.ASM9, visitor)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                // Collection → BiFunction.apply(Collection, dialog).
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", //$NON-NLS-1$
                                    "getProperties", "()Ljava/util/Properties;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitLdcInsn(CALLBACK);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", //$NON-NLS-1$
                                    "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", //$NON-NLS-1$
                                    "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Collection"); //$NON-NLS-1$
                                returns[0]++;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return returns[0] == 0 ? null : writer.toByteArray();
        }
    }

    public static Activator getDefault()
    {
        return instance;
    }

    public class ExternalDependenciesModule extends AbstractServiceAwareModule
    {
        public ExternalDependenciesModule(Plugin bundle)
        {
            super(bundle);
        }
        @Override
        protected void configure()
        {
            bind(IConfigurationProjectManager.class).toService();
            bind(IV8ProjectManager.class).toService();
            bind(IBmModelManager.class).toService();
            bind(IResourceLookup.class).toService();
            bind(IRuntimeVersionSupport.class).toService();
            bind(IExportOperationFactory.class).toService();
        }
        @Override
        protected void doConfigure()
        {
            // TODO Auto-generated method stub
        }
    }

    private Injector injector;
    public synchronized Injector getInjector() {
        if (injector == null)
            injector = createInjector();
        return injector;
    }
    private Injector createInjector() {
        return Guice.createInjector(new ExternalDependenciesModule(this));
    }

    /**
     * empty-except-statement из com.e1c.v8codestyle.bsl 0.7.0.v20260515-0037 и
     * 0.8.0.v20260915-2022: штатное условие пустого списка операторов сохранено ранним
     * вплетением; здесь комментарий считается содержимым, маркер ограничен словом Исключение.
     */
    private static final class EmptyExceptStatementFix
    {
        private static final String CALLBACK = "tormozit.emptyExcept.issue"; //$NON-NLS-1$
        private static final String TITLE =
            "Конструкция \"Попытка…Исключение…КонецПопытки\" имеет пустое исключение";

        static void start()
        {
            System.getProperties().put(CALLBACK, (Consumer<Object[]>) EmptyExceptStatementFix::addIssue);
        }

        static void stop()
        {
            System.getProperties().remove(CALLBACK);
        }

        private static void addIssue(Object[] args)
        {
            if (!(args[0] instanceof BasicCheck.ResultAcceptor acceptor)
                || !(args[1] instanceof TryExceptStatement statement))
                return;
            ICompositeNode node = NodeModelUtils.findActualNodeFor(statement);
            if (node == null)
                return;
            ILeafNode except = null;
            for (ILeafNode leaf : node.getLeafNodes())
            {
                boolean ownKeyword = !leaf.isHidden() && leaf.getGrammarElement() instanceof Keyword
                    && NodeModelUtils.findActualSemanticObjectFor(leaf) == statement;
                if (except == null)
                {
                    if (ownKeyword && ("Исключение".equalsIgnoreCase(leaf.getText())
                        || "Except".equalsIgnoreCase(leaf.getText()))) //$NON-NLS-1$
                        except = leaf;
                    continue;
                }
                if (ownKeyword && ("КонецПопытки".equalsIgnoreCase(leaf.getText())
                    || "EndTry".equalsIgnoreCase(leaf.getText()))) //$NON-NLS-1$
                {
                    DirectLocation location = new DirectLocation(Integer.valueOf(except.getOffset()),
                        Integer.valueOf(except.getLength()), Integer.valueOf(except.getStartLine()), statement);
                    acceptor.addIssue(new BslDirectLocationIssue(TITLE, location));
                    return;
                }
                // Включая скрытые узлы комментариев; пробелы и переводы строк содержимым не считаются.
                if (!leaf.getText().isBlank())
                    return;
            }
        }
    }

    /**
     * Динамическое замещение без изменения профилей: только при выполнении CheckExecutor
     * считает штатные проверки неактивными. Включённость замен на это не влияет.
     * Вызовы ICheckSettings.isEnabled() в CheckExecutor подменяет ранний бандл (CheckExecutorEnabledWeaving):
     * к старту основного бандла CheckExecutor уже загружен. Здесь только функция, которую он вызывает.
     */
    private static final class BuiltinCheckReplacements
    {
        private static final String CALLBACK = "tormozit.checkReplacement.enabled"; //$NON-NLS-1$

        static void start(BundleContext context)
        {
            System.getProperties().put(CALLBACK, (Predicate<ICheckSettings>)settings -> {
                boolean replaced = settings.getId() != null
                    && ComfortCheckIds.REPLACED_BUILTIN_CHECKS.contains(settings.getId().getCheckId());
                return !replaced && settings.isEnabled();
            });
        }

        static void stop()
        {
            // Уже преобразованный класс продолжает обращаться к callback после остановки бандла.
            System.getProperties().put(CALLBACK, (Predicate<ICheckSettings>)ICheckSettings::isEnabled);
        }
    }

    /**
     * Флажок «Только в редакторах» штатной {@code bsl-ql-hub} (#870): параметр и вызов этой функции
     * в начале её {@code check} вплетает ранний бандл ({@code QlHubOpenModulesWeaving}). Правило — как у
     * {@code OpenModulesOnly} проверок Комфорта.
     */
    private static final class QlHubOpenModules
    {
        private static final String CALLBACK = "tormozit.qlHub.skip"; //$NON-NLS-1$
        private static final String PARAMETER = "onlyOpenModules"; //$NON-NLS-1$

        static void start()
        {
            System.getProperties().put(CALLBACK, (java.util.function.BiPredicate<Object, Object>)QlHubOpenModules::skip);
        }

        static void stop()
        {
            // Уже преобразованный класс продолжает обращаться к функции после остановки бандла.
            System.getProperties().put(CALLBACK, (java.util.function.BiPredicate<Object, Object>)(object, parameters) -> false);
        }

        private static boolean skip(Object object, Object parameters)
        {
            if (!(object instanceof org.eclipse.emf.ecore.EObject literal) || literal.eResource() == null)
                return false;
            boolean only;
            try
            {
                only = !(parameters instanceof com.e1c.g5.v8.dt.check.ICheckParameters checkParameters)
                    || checkParameters.getBoolean(PARAMETER);
            }
            catch (Exception e)
            {
                // Нет параметра в описании проверки — как OpenModulesOnly: флажок считается включённым.
                only = true;
            }
            return only && !MassiveChecksExemptionHook.isOpenOrManual(literal.eResource().getURI());
        }
    }

    /**
     * Дата «Создано» у маркеров модуля переживает перепроверку. EDT при каждой проверке строит маркеры заново
     * с текущим временем ({@code BmAwareResourceValidatorListener.createMarker}) и целиком заменяет ими старые.
     * Ранний бандл ({@code MarkerCreationDateWeaving}) перед записью отдаёт сюда проверенный ресурс и новые
     * маркеры; новая проблема, совпавшая со старой по ключу «код проверки + текст + якорь строки», получает её
     * дату. Якорь — строка относительно строки объявления метода (директивы и комментарии над ним встык —
     * отрицательное смещение), поэтому правка в одном методе не сбивает даты в остальных. Якорь пишется в маркер
     * ({@link #ANCHOR}): индекс маркеров хранит extra info целиком, а текст, по которому считался старый якорь,
     * к следующей проверке уже другой. Старые маркеры без якоря сопоставляются по абсолютной строке.
     */
    /**
     * Ключ extra info маркера: якорь строки — {@code m:Метод:смещение}, {@code t:ВремТаблица[#n]:смещение},
     * {@code head:}/{@code gap:}/{@code tail:} (см. {@code MarkerCreationDates}). Читает колонка «Положение».
     */
    static final String MARKER_ANCHOR = "comfort.anchor"; //$NON-NLS-1$

    /** Ключ extra info маркера хаба запросов: номер строки ошибки в тексте запроса. */
    static final String MARKER_QUERY_LINE = "comfort.queryLine"; //$NON-NLS-1$

    private static final class MarkerCreationDates
    {
        private static final String CALLBACK = "tormozit.markerCreatedAt.carry"; //$NON-NLS-1$
        private static final String ANCHOR = MARKER_ANCHOR;

        private static final String QUERY = "tormozit.markerCreatedAt.query"; //$NON-NLS-1$
        private static final String RESET = "tormozit.markerCreatedAt.reset"; //$NON-NLS-1$
        private static final String MARKER = "tormozit.markerCreatedAt.marker"; //$NON-NLS-1$
        private static final String BATCH = "tormozit.markerCreatedAt.batch"; //$NON-NLS-1$
        /**
         * Якоря ошибок хаба запросов (текст → якоря по порядку): хаб отдаёт в задачу только текст, маркер строит
         * {@code CheckExecutor.createMarker}. Сбрасывается на входе {@code validateModel} — проверка объекта.
         */
        private static final ThreadLocal<java.util.Map<String, java.util.ArrayDeque<String[]>>> PENDING =
            ThreadLocal.withInitial(java.util.HashMap::new);

        static void start()
        {
            System.getProperties().put(CALLBACK,
                (java.util.function.BiConsumer<Object, Object>)MarkerCreationDates::carry);
            System.getProperties().put(QUERY, (java.util.function.BiConsumer<Object, Object>)MarkerCreationDates::query);
            System.getProperties().put(RESET, (Consumer<Object>)MarkerCreationDates::reset);
            System.getProperties().put(MARKER, (Consumer<Object>)MarkerCreationDates::marker);
            System.getProperties().put(BATCH, (java.util.function.BiConsumer<Object, Object>)MarkerCreationDates::batch);
        }

        static void stop()
        {
            // Уже преобразованный класс продолжает обращаться к функции после остановки бандла.
            System.getProperties().put(CALLBACK, (java.util.function.BiConsumer<Object, Object>)(resource, markers) -> {});
            System.getProperties().put(QUERY, (java.util.function.BiConsumer<Object, Object>)(resource, problem) -> {});
            System.getProperties().put(RESET, (Consumer<Object>)object -> {});
            System.getProperties().put(MARKER, (Consumer<Object>)marker -> {});
            System.getProperties().put(BATCH, (java.util.function.BiConsumer<Object, Object>)(project, batch) -> {});
        }

        /**
         * Якорь строки запроса: ближайшее сверху начало запроса, создающего временную таблицу ({@code ПОМЕСТИТЬ}),
         * — имя таблицы и смещение от строки начала; повторное имя (после {@code УНИЧТОЖИТЬ}) — с номером.
         */
        private static String queryAnchor(org.eclipse.emf.ecore.resource.Resource resource, int line)
        {
            if (line <= 0 || resource.getContents().isEmpty()
                || !(resource.getContents().get(0) instanceof com._1c.g5.v8.dt.ql.model.QuerySchema schema))
                return null;
            String anchor = "head:" + line; //$NON-NLS-1$
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            for (var query : schema.getQueries())
            {
                if (!(query instanceof com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery select)
                    || select.getPlacementTable() == null || select.getPlacementTable().getTableName() == null)
                    continue;
                ICompositeNode node = NodeModelUtils.findActualNodeFor(select);
                if (node == null)
                    continue;
                int start = node.getStartLine();
                if (start > line)
                    break;
                String name = select.getPlacementTable().getTableName();
                int occurrence = seen.merge(name.toLowerCase(), 1, Integer::sum) - 1;
                anchor = "t:" + name + (occurrence > 0 ? "#" + occurrence : "") + ':' + (line - start); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return anchor;
        }

        /** Хаб запросов перед {@code getMessage} ошибки: якорь — в очередь по тексту ошибки. */
        private static void query(Object resourceObject, Object problem)
        {
            try
            {
                if (!(resourceObject instanceof org.eclipse.emf.ecore.resource.Resource resource))
                    return;
                String message;
                int line;
                if (problem instanceof org.eclipse.xtext.validation.Issue issue)
                {
                    message = issue.getMessage();
                    line = issue.getLineNumber() != null ? issue.getLineNumber() : -1;
                }
                else if (problem instanceof org.eclipse.emf.ecore.resource.Resource.Diagnostic diagnostic)
                {
                    message = diagnostic.getMessage();
                    line = diagnostic.getLine();
                }
                else
                    return;
                String anchor = queryAnchor(resource, line);
                if (message != null && anchor != null)
                    PENDING.get().computeIfAbsent(message, k -> new java.util.ArrayDeque<>())
                        .add(new String[] { anchor, String.valueOf(line) });
            }
            catch (RuntimeException e)
            {
                // Отказ якоря не должен мешать проверке.
            }
        }

        /** Вход {@code CheckExecutor.validateModel}: якоря прошлого объекта, не ставшие маркерами, не нужны. */
        private static void reset(Object object)
        {
            var pending = PENDING.get();
            if (!pending.isEmpty())
                pending.clear();
        }

        /** Выход {@code CheckExecutor.createMarker}: якорь ошибки хаба с тем же текстом — в маркер. */
        private static void marker(Object markerObject)
        {
            var pending = PENDING.get();
            if (pending.isEmpty() || !(markerObject instanceof com._1c.g5.v8.dt.validation.marker.Marker marker))
                return;
            var anchors = pending.get(marker.getMessage());
            String[] entry = anchors != null ? anchors.poll() : null;
            if (entry != null)
            {
                marker.getExtraInfo().put(ANCHOR, entry[0]);
                marker.getExtraInfo().put(MARKER_QUERY_LINE, entry[1]);
            }
        }

        /** Вход {@code MarkerManagerV2.setMarkers(project, batch)}: задачи с якорями получают старые даты. */
        private static void batch(Object projectObject, Object batchObject)
        {
            try
            {
                if (!(projectObject instanceof org.eclipse.core.resources.IProject project)
                    || !(batchObject instanceof com._1c.g5.v8.dt.validation.marker.MarkerUpdateBatch batch))
                    return;
                com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2 manager = null;
                for (var task : batch.getAddTasks())
                {
                    var markers = task.getMarkers();
                    if (markers == null || java.util.Arrays.stream(markers)
                        .noneMatch(marker -> marker.getExtraInfo().get(ANCHOR) != null))
                        continue;
                    if (manager == null)
                        manager = Global.getOsgiService(com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2.class);
                    if (manager == null)
                        return;
                    java.util.List<com._1c.g5.v8.dt.validation.marker.Marker> old;
                    try (java.util.stream.Stream<com._1c.g5.v8.dt.validation.marker.Marker> stream = manager.createReader(project)
                        .markers(com._1c.g5.v8.dt.validation.marker.MarkerFilter.createProjectFilter(project)
                            .addValue(com._1c.g5.v8.dt.validation.marker.MarkerIndex.OBJECT_ID, task.getObjectId())
                            .addValue(com._1c.g5.v8.dt.validation.marker.MarkerIndex.CHECK_ID, task.getCheckId())))
                    {
                        old = new java.util.ArrayList<>(stream.toList());
                    }
                    restore(java.util.List.of(task.getObjectId(), task.getCheckId()), markers, old);
                }
            }
            catch (RuntimeException e)
            {
                // Отказ переноса даты не должен мешать записи маркеров.
            }
        }

        /** Строки методов модуля по модели проверенного ресурса. */
        private static final class MethodLines
        {
            private final String[] names;
            /** Первая строка шапки: директивы и комментарии над объявлением встык. */
            private final int[] headers;
            private final int[] declarations;
            private final int[] ends;

            private MethodLines(java.util.List<Object[]> rows)
            {
                int n = rows.size();
                names = new String[n];
                headers = new int[n];
                declarations = new int[n];
                ends = new int[n];
                for (int i = 0; i < n; i++)
                {
                    Object[] row = rows.get(i);
                    names[i] = (String)row[0];
                    headers[i] = (Integer)row[1];
                    declarations[i] = (Integer)row[2];
                    ends[i] = (Integer)row[3];
                }
            }

            static MethodLines of(org.eclipse.emf.ecore.resource.Resource resource)
            {
                if (resource.getContents().isEmpty()
                    || !(resource.getContents().get(0) instanceof com._1c.g5.v8.dt.bsl.model.Module module))
                    return null;
                ICompositeNode moduleNode = NodeModelUtils.getNode(module);
                if (moduleNode == null)
                    return null;
                boolean[] blank = blankLines(moduleNode.getRootNode().getText());
                java.util.List<Object[]> rows = new java.util.ArrayList<>();
                for (com._1c.g5.v8.dt.bsl.model.Method method : module.allMethods())
                {
                    ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
                    if (node == null)
                        continue;
                    // Строка объявления — строка имени: директивы компиляции входят в узел метода и стоят выше.
                    int declaration = node.getStartLine();
                    org.eclipse.emf.ecore.EStructuralFeature nameFeature = method.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
                    if (nameFeature != null)
                    {
                        java.util.List<INode> nameNodes = NodeModelUtils.findNodesForFeature(method, nameFeature);
                        if (!nameNodes.isEmpty())
                            declaration = nameNodes.get(0).getStartLine();
                    }
                    rows.add(new Object[] { String.valueOf(method.getName()), Math.min(node.getStartLine(), declaration),
                        declaration, Math.max(node.getEndLine(), declaration) });
                }
                rows.sort(java.util.Comparator.comparingInt(row -> (Integer)row[2]));
                int previousEnd = 0;
                for (Object[] row : rows)
                {
                    int header = (Integer)row[1];
                    while (header - 1 > previousEnd && header - 1 < blank.length && !blank[header - 1])
                        header--;
                    row[1] = header;
                    previousEnd = (Integer)row[3];
                }
                return new MethodLines(rows);
            }

            /** {@code blank[line]} для строк с 1; разбивка текста на строки, не разбор BSL. */
            private static boolean[] blankLines(String text)
            {
                java.util.List<Boolean> lines = new java.util.ArrayList<>();
                lines.add(Boolean.FALSE);
                boolean blank = true;
                for (int i = 0; i < text.length(); i++)
                {
                    char c = text.charAt(i);
                    if (c == '\n')
                    {
                        lines.add(blank);
                        blank = true;
                    }
                    else if (!Character.isWhitespace(c))
                        blank = false;
                }
                lines.add(blank);
                boolean[] result = new boolean[lines.size()];
                for (int i = 0; i < result.length; i++)
                    result[i] = lines.get(i);
                return result;
            }

            String anchor(int line)
            {
                int n = declarations.length;
                // Последний метод, чья шапка начинается не ниже строки.
                int low = 0;
                int high = n - 1;
                int found = -1;
                while (low <= high)
                {
                    int middle = (low + high) >>> 1;
                    if (headers[middle] <= line)
                    {
                        found = middle;
                        low = middle + 1;
                    }
                    else
                        high = middle - 1;
                }
                if (found < 0)
                    return "head:" + line; //$NON-NLS-1$
                if (line <= ends[found])
                    return "m:" + names[found] + ':' + (line - declarations[found]); //$NON-NLS-1$
                if (found == n - 1)
                    return "tail:" + (line - ends[found]); //$NON-NLS-1$
                return "gap:" + names[found + 1] + ':' + (line - declarations[found + 1]); //$NON-NLS-1$
            }
        }

        private static int line(com._1c.g5.v8.dt.validation.marker.Marker marker)
        {
            return marker.getExtraInfo().getIntOrDefault(com._1c.g5.v8.dt.validation.marker.StandardExtraInfo.TEXT_LINE, -1);
        }

        private static String key(com._1c.g5.v8.dt.validation.marker.Marker marker, String anchor)
        {
            return marker.getCheckId() + '\u0001' + marker.getFeatureId() + '\u0001' + marker.getMessage() + '\u0001' + anchor;
        }

        /** Сколько помнить даты старых маркеров, не нашедших пары. */
        private static final long KEEP_MS = 10 * 60_000L;
        /**
         * Объект → ключ → {дата создания, когда отложена}: старые маркеры, не нашедшие пары при записи. При записи
         * модуля EDT пишет его маркеры дважды: сборка оставляет только ошибки Xtext и стирает остальные, через
         * несколько секунд производные данные пишут все проверки — даты стёртых берутся отсюда.
         */
        private static final java.util.Map<Object, java.util.Map<String, java.util.ArrayDeque<long[]>>> DROPPED =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true)
            {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(
                    java.util.Map.Entry<Object, java.util.Map<String, java.util.ArrayDeque<long[]>>> eldest)
                {
                    return size() > 2000;
                }
            });

        /**
         * Новым маркерам — даты совпавших старых (и отложенных прошлыми записями того же {@code scope}): сперва по
         * якорю, старые без якоря — по абсолютной строке; среди одинаковых ключей — по порядку строк. Не нашедшие
         * пары откладываются.
         */
        private static void restore(Object scope, com._1c.g5.v8.dt.validation.marker.Marker[] markers,
            java.util.List<com._1c.g5.v8.dt.validation.marker.Marker> old)
        {
            long now = System.currentTimeMillis();
            java.util.Map<String, java.util.ArrayDeque<long[]>> dates = new java.util.HashMap<>();
            var kept = DROPPED.remove(scope);
            if (kept != null)
                kept.forEach((key, queue) -> queue.stream().filter(entry -> now - entry[1] <= KEEP_MS)
                    .forEach(entry -> dates.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).add(entry)));
            old.sort(java.util.Comparator.comparingInt(MarkerCreationDates::line));
            for (var marker : old)
            {
                String anchor = marker.getExtraInfo().get(ANCHOR);
                dates.computeIfAbsent(key(marker, anchor != null ? anchor : "abs:" + line(marker)), //$NON-NLS-1$
                    k -> new java.util.ArrayDeque<>()).add(new long[] { marker.getCreatedAt(), now });
            }
            com._1c.g5.v8.dt.validation.marker.Marker[] ordered = markers.clone();
            java.util.Arrays.sort(ordered, java.util.Comparator.comparingInt(MarkerCreationDates::line));
            for (var marker : ordered)
            {
                String anchor = marker.getExtraInfo().get(ANCHOR);
                long[] date = null;
                if (anchor != null)
                {
                    var queue = dates.get(key(marker, anchor));
                    date = queue != null ? queue.poll() : null;
                }
                if (date == null)
                {
                    var queue = dates.get(key(marker, "abs:" + line(marker))); //$NON-NLS-1$
                    date = queue != null ? queue.poll() : null;
                }
                if (date != null && date[0] > 0 && date[0] < marker.getCreatedAt())
                    marker.setCreatedAt(date[0]);
            }
            dates.values().removeIf(java.util.ArrayDeque::isEmpty);
            if (!dates.isEmpty())
                DROPPED.put(scope, dates);
        }

        private static void carry(Object resourceObject, Object markersObject)
        {
            try
            {
                if (!(resourceObject instanceof org.eclipse.emf.ecore.resource.Resource resource)
                    || !(markersObject instanceof com._1c.g5.v8.dt.validation.marker.Marker[] markers)
                    || markers.length == 0 || resource.getURI() == null || !resource.getURI().isPlatformResource())
                    return;
                String object = resource.getURI().toPlatformString(true);
                org.eclipse.core.resources.IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                    .getRoot().getProject(resource.getURI().segment(1));
                com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2 manager =
                    Global.getOsgiService(com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2.class);
                if (manager == null || !project.isAccessible())
                    return;
                MethodLines methods = MethodLines.of(resource);
                for (var marker : markers)
                {
                    int line = line(marker);
                    if (methods != null && line > 0)
                        marker.getExtraInfo().put(ANCHOR, methods.anchor(line));
                }
                // Старые даты по ключу; среди одинаковых ключей — по порядку строк.
                java.util.List<com._1c.g5.v8.dt.validation.marker.Marker> old;
                try (java.util.stream.Stream<com._1c.g5.v8.dt.validation.marker.Marker> stream = manager.createReader(project)
                    .markers(com._1c.g5.v8.dt.validation.marker.MarkerFilter.createProjectFilter(project)
                        .addValue(com._1c.g5.v8.dt.validation.marker.MarkerIndex.OBJECT_ID, object)))
                {
                    old = new java.util.ArrayList<>(stream.toList());
                }
                restore(object, markers, old);
            }
            catch (RuntimeException e)
            {
                // Отказ переноса даты не должен мешать записи маркеров.
            }
        }
    }

    /**
     * Обход бага EDT: {@code //@skip-check} перед оператором, который начинается ключевым словом
     * ({@code Возврат}, {@code ВызватьИсключение} и т.п.), штатный {@code BslSuppressionProvider} не растягивает
     * на весь оператор — диапазон остаётся строкой комментария и первой строкой оператора, и многострочный
     * литерал не подавляется ни одной проверкой. Диапазон, который кончается на строке начала оператора,
     * растягивается до конца оператора. Блоки ({@code Если}, циклы, {@code Попытка}, препроцессор) штатный
     * расчёт обрабатывает сам. Функцию вызывает ранний бандл ({@code SuppressionRangesWeaving}) на выходе
     * {@code computeSuppressionRanges}, поэтому правка действует для всех проверок.
     */
    private static final class SuppressionRanges
    {
        private static final String CALLBACK = "tormozit.bslSuppression.ranges"; //$NON-NLS-1$

        static void start()
        {
            System.getProperties().put(CALLBACK, (BiFunction<Object, Object, Object>)SuppressionRanges::apply);
        }

        static void stop()
        {
            // Уже преобразованный класс продолжает обращаться к функции после остановки бандла.
            System.getProperties().put(CALLBACK, (BiFunction<Object, Object, Object>)(module, ranges) -> ranges);
        }

        private static Object apply(Object module, Object ranges)
        {
            try
            {
                return extend(module, ranges);
            }
            catch (RuntimeException e)
            {
                return ranges;
            }
        }

        private static Object extend(Object moduleObject, Object rangesObject)
        {
            if (!(moduleObject instanceof com._1c.g5.v8.dt.bsl.model.Module module)
                || !(rangesObject instanceof java.util.Map<?, ?> ranges) || ranges.isEmpty())
                return rangesObject;
            // Кандидаты: диапазон «комментарий + следующая строка» — кончается на строке после своего начала.
            java.util.Set<Integer> lines = new java.util.TreeSet<>();
            for (Object value : ranges.values())
            {
                if (!(value instanceof Collection<?> collection))
                    continue;
                for (Object item : collection)
                {
                    if (item instanceof BslSuppressionProvider.BslSuppressionRange range
                        && range.getStartLine() < range.getEndLine())
                        lines.add(range.getEndLine());
                }
            }
            if (lines.isEmpty())
                return rangesObject;
            ICompositeNode root = NodeModelUtils.findActualNodeFor(module);
            if (root == null)
                return rangesObject;
            int lastLine = ((java.util.TreeSet<Integer>)lines).last();
            java.util.Map<Integer, Integer> extendTo = new java.util.HashMap<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (ILeafNode leaf : root.getLeafNodes())
            {
                if (leaf.isHidden())
                    continue;
                int line = leaf.getStartLine();
                if (line > lastLine)
                    break;
                // Только первая значимая лексема строки.
                if (!lines.contains(line) || !seen.add(line))
                    continue;
                int end = statementEndLine(leaf);
                if (end > line)
                    extendTo.put(line, end);
            }
            if (extendTo.isEmpty())
                return rangesObject;
            // Один штатный диапазон может лежать в нескольких наборах — заменяем одним новым.
            java.util.Map<Object, BslSuppressionProvider.BslSuppressionRange> replaced = new java.util.IdentityHashMap<>();
            java.util.Map<Object, Object> result = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> entry : ranges.entrySet())
            {
                if (!(entry.getValue() instanceof Collection<?> collection))
                {
                    result.put(entry.getKey(), entry.getValue());
                    continue;
                }
                java.util.List<Object> items = new java.util.ArrayList<>(collection.size());
                for (Object item : collection)
                {
                    if (item instanceof BslSuppressionProvider.BslSuppressionRange range
                        && range.getStartLine() < range.getEndLine() && extendTo.containsKey(range.getEndLine()))
                        items.add(replaced.computeIfAbsent(range, key -> new BslSuppressionProvider.BslSuppressionRange(
                            range.getStartLine(), extendTo.get(range.getEndLine()), range.getCheckIds())));
                    else
                        items.add(item);
                }
                result.put(entry.getKey(), items);
            }
            return result;
        }

        /**
         * Последняя строка оператора, который начинается лексемой {@code leaf}; {@code -1} — лексема не
         * начинает оператор или это блок, который штатный расчёт растягивает сам.
         */
        private static int statementEndLine(ILeafNode leaf)
        {
            for (INode node = leaf.getParent(); node != null; node = node.getParent())
            {
                if (node.getOffset() != leaf.getOffset())
                    return -1;
                if (!(node.getSemanticElement() instanceof Statement statement))
                    continue;
                if (statement instanceof IfStatement || statement instanceof LoopStatement
                    || statement instanceof TryExceptStatement || statement instanceof IfPreprocessorStatement
                    || statement instanceof RegionPreprocessorStatement)
                    return -1;
                INode statementNode = NodeModelUtils.findActualNodeFor(statement);
                if (statementNode == null || statementNode.getOffset() != leaf.getOffset())
                    return -1;
                return statementNode.getEndLine();
            }
            return -1;
        }
    }

    /**
     * Проверки (любые, штатные и Комфорта) не держат UI. Проверка открытого модуля идёт в задании проверки редактора
     * внутри {@code readOnly} документа BSL — это монитор одного объекта на документ, и любое чтение
     * документа из UI (переход к определению, закрытие редактора) ждёт конца всего прохода проверок.
     * Ранний бандл ({@code CheckDurations}) отдаёт каждой проверке монитор из этой функции. Когда поток
     * UI ждёт монитор, захваченный потоком проверки (опрос — перед запуском каждой проверки и при каждом
     * {@code isCanceled} не чаще {@link #PROBE_PERIOD_NS}), проверка считается отменённой, задание
     * проверки отменяется (аннотации и маркеры остаются прежними, монитор документа освобождается)
     * и планируется заново через {@link #DELAY_MS}. Проверки вне задания проверки редактора
     * (полная перепроверка, производные данные) не трогаются: их некому перезапустить.
     */
    private static final class UiWaitCheckDeferral
    {
        private static final String CALLBACK = "tormozit.checkDuration.monitor"; //$NON-NLS-1$
        private static final long PROBE_PERIOD_NS = 50_000_000L;
        private static final long DELAY_MS = 1_000L;

        static void start()
        {
            System.getProperties().put(CALLBACK, (BiFunction<Object, Object, Object>)(check, monitor) ->
            {
                if (check == null || !(monitor instanceof org.eclipse.core.runtime.IProgressMonitor progress))
                    return monitor;
                Monitor wrapped = new Monitor(progress);
                // Проверка перед запуском: штатная проверка может не опрашивать монитор вовсе.
                wrapped.isCanceled();
                return wrapped;
            });
        }

        static void stop()
        {
            // Уже преобразованный CheckExecutor продолжает обращаться к функции после остановки бандла.
            System.getProperties().put(CALLBACK, (BiFunction<Object, Object, Object>)(check, monitor) -> monitor);
        }

        /** Поток UI ждёт монитор или блокировку, которыми владеет текущий поток. */
        private static boolean uiWaitsForCurrentThread()
        {
            if (!org.eclipse.ui.PlatformUI.isWorkbenchRunning())
                return false;
            Display display = org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
            Thread ui = display == null || display.isDisposed() ? null : display.getThread();
            if (ui == null || ui == Thread.currentThread())
                return false;
            Thread.State state = ui.getState();
            if (state != Thread.State.BLOCKED && state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING)
                return false;
            java.lang.management.ThreadInfo info =
                java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(ui.getId());
            return info != null && info.getLockOwnerId() == Thread.currentThread().getId();
        }

        private static final class Monitor extends org.eclipse.core.runtime.ProgressMonitorWrapper
        {
            private long nextProbe = System.nanoTime();
            private volatile boolean deferred;

            Monitor(org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                super(monitor);
            }

            @Override
            public boolean isCanceled()
            {
                if (deferred || super.isCanceled())
                    return true;
                long now = System.nanoTime();
                if (now - nextProbe < 0)
                    return false;
                nextProbe = now + PROBE_PERIOD_NS;
                if (!uiWaitsForCurrentThread())
                    return false;
                org.eclipse.core.runtime.jobs.Job job = org.eclipse.core.runtime.jobs.Job.getJobManager().currentJob();
                boolean editorValidation = job != null && job.getClass().getName().endsWith("ValidationJob"); //$NON-NLS-1$
                if (!editorValidation)
                    return false;
                deferred = true;
                setCanceled(true);
                job.cancel();
                job.schedule(DELAY_MS);
                return true;
            }
        }
    }

    /**
     * Приведение NULL допустимо независимо от целевого типа (issue 914).
     * В com._1c.g5.v8.dt.ql 22.1.0.v202605050943 CastOperatorChecker.checkType сначала
     * рассчитывает тип результата и проверяет операнд и параметры типа, затем единственным
     * вызовом isCrossCompoundType решает, выдавать ли «Тип приведения не найден в составном типе».
     * Дополняем только результат этого вызова; остальные проверки и тип результата сохраняются.
     * По Ql.xtext обычные скобки не создают отдельного узла: (NULL) тоже NullLiteralExpression.
     */
    private static final class QueryNullCastFix
    {
        private static final String CALLBACK = "tormozit.query.nullCast"; //$NON-NLS-1$

        static void start()
        {
            System.getProperties().put(CALLBACK, (Predicate<Object>)object ->
                object instanceof CastOperationExpression cast
                    && cast.getExpression() instanceof NullLiteralExpression);
        }

        static void stop()
        {
            // Загруженный класс EDT продолжает обращаться к мосту после остановки плагина.
            System.getProperties().put(CALLBACK, (Predicate<Object>)object -> false);
        }
    }

    /**
     * Штатный предикат Xtext ({@code SingleLineTerminalsStrategy.DEFAULT}), общий для редактора
     * модуля и редактора запроса, не вставляет закрывающую скобку перед началом идентификатора,
     * цифрой и рядом других символов, но не знает про "&" - начало параметра запроса
     * ({@code &Параметр}), поэтому "(&Параметр)" превращалось в "(&Параметр))".
     * Дополняем его напрямую: поле {@code DEFAULT} публичное и не {@code final}.
     */
    private static final class QueryParameterAutoCloseFix
    {
        static void install()
        {
            SingleLineTerminalsStrategy.StrategyPredicate original = SingleLineTerminalsStrategy.DEFAULT;
            SingleLineTerminalsStrategy.DEFAULT = (doc, offset) -> {
                if (doc.getLength() > offset && doc.getChar(offset) == '&')
                    return false;
                return original.isInsertClosingBracket(doc, offset);
            };
        }
    }

    /**
     * Потоки расчёта производных данных EDT ({@code derived_data_executor_N}: проверки, валидация
     * метаданных) — с обычным приоритетом вместо наивысшего, который ставит им EDT. Сторож перегрузки EDT ({@code HostResourceManager}
     * бандла {@code com._1c.g5.resourcemanagement}) засыпает на 50 мс и по запаздыванию пробуждения
     * объявляет перегрузку: при занятых всех ядрах он урезает потоки проверок вдвое или до одного
     * ({@code DerivedDataManagerProvider$1.gatherPermissions} в {@code com._1c.g5.v8.dt.core}
     * 26.0.1.v202605050943) и зовёт {@code System.gc()}. С обычным приоритетом проверки делят ядра
     * с потоком UI и сторожем на равных и при этом занимают все свободные ядра. Ниже обычного не
     * понижать: при неактивном окне EDT потоки обычного приоритета других процессов вытесняют
     * проверки, и прогон идёт в разы дольше.
     * <p>
     * Пул EDT заводит потоки сам, точки подключения к их созданию нет, поэтому потоки находятся
     * обходом группы потоков раз в {@link #PERIOD_MS}. На Windows приоритет Java переходит в ОС
     * ({@code THREAD_PRIORITY_NORMAL}); на Linux JVM по умолчанию приоритеты не применяет.
     */
    private static final class CheckThreadPriority
    {
        private static final String THREAD_PREFIX = "derived_data_executor_"; //$NON-NLS-1$
        private static final int PRIORITY = Thread.NORM_PRIORITY;
        private static final long PERIOD_MS = 2_000L;
        private static final String TAG = "CheckThreadPriority"; //$NON-NLS-1$

        static void start()
        {
            java.util.concurrent.ScheduledExecutorService timer =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable ->
                {
                    Thread thread = new Thread(runnable, "Комфорт: приоритет потоков проверок"); //$NON-NLS-1$
                    thread.setDaemon(true);
                    return thread;
                });
            timer.scheduleWithFixedDelay(CheckThreadPriority::lowerPriorities, 0L, PERIOD_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private static void lowerPriorities()
        {
            try
            {
                ThreadGroup root = Thread.currentThread().getThreadGroup();
                while (root.getParent() != null)
                    root = root.getParent();
                Thread[] threads = new Thread[root.activeCount() + 16];
                int count = root.enumerate(threads, true);
                for (int i = 0; i < count; i++)
                {
                    Thread thread = threads[i];
                    if (thread == null || thread.getPriority() <= PRIORITY
                        || !thread.getName().startsWith(THREAD_PREFIX))
                        continue;
                    int previous = thread.getPriority();
                    thread.setPriority(PRIORITY);
                    if (Global.isLogEnabled())
                        Global.log(TAG, "приоритет потока " + thread.getName() + ": " + previous + " -> " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + thread.getPriority());
                }
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "понижение приоритета потоков проверок", e); //$NON-NLS-1$
            }
        }
    }
}
