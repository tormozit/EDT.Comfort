package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.xtext.ui.editor.autoedit.SingleLineTerminalsStrategy;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexProvider;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.e1c.g5.v8.dt.check.settings.CheckSettingsChange;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;
import com.e1c.g5.v8.dt.check.settings.ICheckSettingsChangeListener;

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
        // #region agent log
        // Отметка сборки: файл stale-markers.log обязан появиться сразу после старта плагина.
        if (ContentAssistSessionReloader.STALE_MARKERS_LOG)
            Global.tempLog("stale-markers", "плагин запущен, диагностика протухших маркеров v4"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        Global.cleanOrphanedFormNativeTempDirs();

        super.start(context);
        instance = this;
        CheckRecomputeNotifications.install();
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
        BslQueryLiteralSupport.installCheckWeavingHook();
        BslOccurrenceMarkerHook.installLiteralCallbacks();
        BslEditorFoldingHook.install();
        BslFormTypeContextEnrichment.installWeavingHook();
        ConfigSearchDialogHook.installWeavingHook();
        DtGranularEditorHook.installWeavingHook();
        MdEditorTreeHook.installWeavingHook();
        NavigatorFilterHook.installProblemSeverityWeaving();
        MdEventHandlersPageHook.installWeavingHook();
        OpenHelperAttributePropertiesHook.installWeavingHook();
        WorkbenchTabsHook.installWeavingHook();
        ProblemViewHook.installWeavingHook();
        MoxelEditorHook.installWeavingHooks();
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
        BslDocCommentTypeMerge.install();
        BslXtextDocumentHook.install();
        StaticFeatureAccessReplacement.start();
        QueryParameterAutoCloseFix.install();
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        CheckRecomputeNotifications.stop();
        StaticFeatureAccessReplacement.stop();
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
        private static final long INTERVAL_NS = 60_000_000_000L;
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
            pending.clear();
            lastToastNs.clear();
        }

        private static void accept(Object[] event)
        {
            if (!running)
                return;
            pending.put(projectKey(event[3]), event);
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

        private static void showEvent(String projectKey, Object[] event)
        {
            long now = System.nanoTime();
            lastToastNs.entrySet().removeIf(entry -> now - entry.getValue().longValue() >= INTERVAL_NS);
            Long last = lastToastNs.get(projectKey);
            if (last != null && now - last.longValue() < INTERVAL_NS)
                return;
            String logMessage = (String) event[0];
            long timeMs = ((Long) event[2]).longValue();
            var toast = ToastNotification.show("Полный пересчёт проверок", //$NON-NLS-1$
                event[1] + ". Подробности — в журнале EDT.", 15_000, //$NON-NLS-1$
                () -> LongOperationNotifyHook.openLogEntry(logMessage, timeMs), "Открыть запись журнала"); //$NON-NLS-1$
            if (toast != null)
                lastToastNs.put(projectKey, Long.valueOf(now));
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
     * Штатная проверка не должна выдавать те же проблемы, что её замены: если в проекте включена
     * хотя бы одна из пяти замен, штатная отключается (и снова отключается при её включении).
     * Об этом сказано в описаниях всех шести проверок.
     */
    private static final class StaticFeatureAccessReplacement
    {
        private static final String BUILTIN_ID = "bsl-legacy-check-static-feature-access"; //$NON-NLS-1$
        private static final String[] REPLACEMENT_IDS = { ComfortCheckIds.STATIC_ACCESS_PARAMETERS,
            ComfortCheckIds.STATIC_ACCESS_OBSOLETE, ComfortCheckIds.STATIC_ACCESS_COMPATIBILITY,
            ComfortCheckIds.STATIC_ACCESS_VARIABLE, ComfortCheckIds.STATIC_ACCESS_EVENT_HANDLER };
        private static volatile boolean active;
        private static volatile ICheckRepository repository;

        private static final IResourceChangeListener PROJECTS = event -> {
            IResourceDelta delta = event.getDelta();
            if (delta == null)
                return;
            for (IResourceDelta child : delta.getAffectedChildren())
                if (child.getResource() instanceof IProject
                    && (child.getKind() == IResourceDelta.ADDED
                        || (child.getFlags() & IResourceDelta.OPEN) != 0))
                {
                    schedule();
                    return;
                }
        };

        private static final ICheckSettingsChangeListener SETTINGS = new ICheckSettingsChangeListener()
        {
            @Override
            public void onChange(IProject project, Collection<CheckSettingsChange> changes)
            {
                schedule();
            }

            @Override
            public void onPreferenceChange(IProject project)
            {
                schedule();
            }
        };

        private static final Job JOB = new Job("Замена штатной проверки доступа к свойствам") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                if (!active)
                    return Status.OK_STATUS;
                ICheckRepository checks = Global.getOsgiService(ICheckRepository.class);
                if (checks == null)
                {
                    Global.tempLog("StaticFeatureAccessReplacement", "ICheckRepository недоступен"); //$NON-NLS-1$ //$NON-NLS-2$
                    schedule();
                    return Status.OK_STATUS;
                }
                if (repository != checks)
                {
                    if (repository != null)
                        repository.removeChangeListener(SETTINGS);
                    checks.addChangeListener(SETTINGS);
                    repository = checks;
                }
                for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                {
                    if (!project.isAccessible() || monitor.isCanceled())
                        continue;
                    try
                    {
                        if (!isAnyReplacementEnabled(checks, project))
                            continue;
                        Set<CheckUid> uids = checks.getCheckUidForCheckId(BUILTIN_ID, project);
                        List<ICheckSettings> changes = new ArrayList<>();
                        for (CheckUid uid : uids)
                        {
                            ICheckSettings settings = checks.getSettings(uid, project);
                            if (settings != null && settings.isEnabled())
                            {
                                settings.setEnabled(false);
                                changes.add(settings);
                            }
                        }
                        if (!changes.isEmpty())
                        {
                            checks.applyChanges(changes, project);
                            Global.tempLog("StaticFeatureAccessReplacement", //$NON-NLS-1$
                                "отключена штатная проверка: " + project.getName()); //$NON-NLS-1$
                        }
                    }
                    catch (RuntimeException failure)
                    {
                        Global.tempLog("StaticFeatureAccessReplacement", //$NON-NLS-1$
                            project.getName() + ": " + failure); //$NON-NLS-1$
                    }
                }
                return Status.OK_STATUS;
            }
        };

        private static boolean isAnyReplacementEnabled(ICheckRepository checks, IProject project)
        {
            for (String id : REPLACEMENT_IDS)
                for (CheckUid uid : checks.getCheckUidForCheckId(id, project))
                {
                    ICheckSettings settings = checks.getSettings(uid, project);
                    if (settings != null && settings.isEnabled())
                        return true;
                }
            return false;
        }

        static void start()
        {
            active = true;
            ResourcesPlugin.getWorkspace().addResourceChangeListener(PROJECTS, IResourceChangeEvent.POST_CHANGE);
            schedule();
        }

        static void stop()
        {
            active = false;
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(PROJECTS);
            if (repository != null)
                repository.removeChangeListener(SETTINGS);
            repository = null;
            JOB.cancel();
        }

        private static void schedule()
        {
            if (active)
                JOB.schedule(2_000);
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
}
