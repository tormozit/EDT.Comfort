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
        Global.cleanOrphanedFormNativeTempDirs();

        super.start(context);
        instance = this;
        // Раньше всего остального: WeavingHook ловит только классы, загруженные после его
        // регистрации, а следующие ниже попытки присоединить агент стоят сотен миллисекунд
        // каждая — за это время BslDocumentationComment успевает загрузиться.
        BslDocCommentComputedTypes.installWeavingHook();
        BslDocCommentDescriptionFix.installWeavingHook();
        BslDocCommentTypeMerge.installWeavingHook();
        BslFormTypeContextEnrichment.installWeavingHook();
        BslHandlerBlankLineHook.installWeavingHook();
        MdEditorTreeHook.installWeavingHook();
        OpenHelperAttributePropertiesHook.installWeavingHook();
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
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED,
            ComfortSettings.DEFAULT_BRACKET_CONTENT_HINT_ENABLED);
        settings.getPreferenceStore().setDefault(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES,
            ComfortSettings.DEFAULT_BRACKET_CONTENT_HINT_MIN_LINES);
        ComfortSettings.applyIndentGuideDefaults(settings.getPreferenceStore());
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
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        StaticFeatureAccessReplacement.stop();
        IRApplication.disconnectAll();

        ContentAssistManager mgr = ContentAssistManager.getInstance();
        if (mgr != null)
            mgr.stop();

        Global.stopTempLogFlusher();

        instance = null;
        super.stop(context);
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
}
