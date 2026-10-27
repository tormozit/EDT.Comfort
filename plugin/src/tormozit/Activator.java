package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.function.Predicate;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.plugin.AbstractUIPlugin;
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
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;

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
        BuiltinCheckReplacements.start(context);
        CheckRecomputeNotifications.install();
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
        BslQueryLiteralSupport.installCheckWeavingHook();
        BslOccurrenceMarkerHook.installLiteralCallbacks();
        BslEditorFoldingHook.install();
        BslFormTypeContextEnrichment.installWeavingHook();
        DynamicFeatureAccessComputerHook.installWeavingHook();
        ConfigSearchDialogHook.installWeavingHook();
        DtGranularEditorHook.installWeavingHook();
        MdEditorTreeHook.installWeavingHook();
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
        BslDocCommentTypeMerge.install();
        BslXtextDocumentHook.install();
        QueryParameterAutoCloseFix.install();
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        FunctionReturnTypes.stopLocalContextInvalidation();
        CheckRecomputeNotifications.stop();
        BuiltinCheckReplacements.stop();
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
                event[1] + ".", 15_000, //$NON-NLS-1$
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
     * Динамическое замещение без изменения профилей: только при выполнении CheckExecutor
     * считает штатные проверки неактивными. Включённость замен на это не влияет.
     * Вызовы ICheckSettings.isEnabled() подтверждены в com.e1c.g5.v8.dt.check 6.0.100.v202605050943:
     * проверки языка, модели и делегатов. Замена вызова сохраняет стек и штатную очистку маркеров.
     */
    private static final class BuiltinCheckReplacements implements WeavingHook, ClassFileTransformer
    {
        private static final String TARGET = "com.e1c.g5.v8.dt.internal.check.CheckExecutor"; //$NON-NLS-1$
        private static final String SETTINGS_TYPE = "com/e1c/g5/v8/dt/check/settings/ICheckSettings"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.checkReplacement.enabled"; //$NON-NLS-1$
        private static volatile boolean woven;

        static void start(BundleContext context)
        {
            System.getProperties().put(CALLBACK, (Predicate<ICheckSettings>)settings -> {
                boolean replaced = settings.getId() != null
                    && ComfortCheckIds.REPLACED_BUILTIN_CHECKS.contains(settings.getId().getCheckId());
                return !replaced && settings.isEnabled();
            });
            BuiltinCheckReplacements hook = new BuiltinCheckReplacements();
            context.registerService(WeavingHook.class, hook, null);
            try
            {
                Bundle checks = org.eclipse.core.runtime.Platform.getBundle("com.e1c.g5.v8.dt.check"); //$NON-NLS-1$
                if (checks == null)
                    throw new IllegalStateException("Бандл проверок EDT недоступен"); //$NON-NLS-1$
                checks.loadClass(TARGET);
                if (!woven)
                    BslDocCommentDescriptionFix.registerExtraTransformer(hook, TARGET);
            }
            catch (ClassNotFoundException | RuntimeException | LinkageError failure)
            {
            }
        }

        static void stop()
        {
            // Уже преобразованный класс продолжает обращаться к callback после остановки бандла.
            System.getProperties().put(CALLBACK, (Predicate<ICheckSettings>)ICheckSettings::isEnabled);
        }

        @Override
        public void weave(WovenClass type)
        {
            if (type.getState() == WovenClass.TRANSFORMING && TARGET.equals(type.getClassName()))
            {
                type.setBytes(rewrite(type.getBytes()));
                woven = true;
            }
        }

        @Override
        public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes)
        {
            return TARGET.replace('.', '/').equals(name) ? rewrite(bytes) : null;
        }

        private static byte[] rewrite(byte[] bytes)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] calls = { 0 };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions))
                    {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                            String descriptor, boolean isInterface)
                        {
                            if (opcode == Opcodes.INVOKEINTERFACE && SETTINGS_TYPE.equals(owner)
                                && "isEnabled".equals(method) && "()Z".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                            {
                                // В стеке settings; Predicate из java.base доступен загрузчику EDT.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", //$NON-NLS-1$
                                    "getProperties", "()Ljava/util/Properties;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitLdcInsn(CALLBACK);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", //$NON-NLS-1$
                                    "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$ //$NON-NLS-2$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate"); //$NON-NLS-1$
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", //$NON-NLS-1$
                                    "test", "(Ljava/lang/Object;)Z", true); //$NON-NLS-1$ //$NON-NLS-2$
                                calls[0]++;
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                        }
                    };
                }
            }, 0);
            if (calls[0] == 0)
                throw new IllegalStateException("В CheckExecutor не найдены вызовы isEnabled"); //$NON-NLS-1$
            return writer.toByteArray();
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
