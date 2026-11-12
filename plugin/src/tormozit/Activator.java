package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.ui.editor.autoedit.SingleLineTerminalsStrategy;
import org.eclipse.xtext.ui.editor.contentassist.ContentAssistContext;
import org.eclipse.xtext.ui.editor.contentassist.ICompletionProposalAcceptor;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
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
import com._1c.g5.v8.dt.bsl.documentation.comment.LinkPart;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider;
import com._1c.g5.v8.dt.bsl.model.BslContextDef;
import com._1c.g5.v8.dt.bsl.model.BslIndexPackage;
import com._1c.g5.v8.dt.bsl.model.ModuleContextDefIndex;
import com._1c.g5.v8.dt.bsl.ui.BslSharedImages;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com.e1c.g5.dt.core.api.platform.BmOperationContext;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.scoping.IScopeProvider;
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
import com._1c.g5.v8.dt.rights.model.Right;
import com._1c.g5.v8.dt.rights.model.RightValue;
import com._1c.g5.v8.dt.rights.model.util.RightsModelUtil;
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
        Global.tempLog("validation-run", "plugin start"); //$NON-NLS-1$ //$NON-NLS-2$
        Global.cleanOrphanedFormNativeTempDirs();

        super.start(context);
        instance = this;
        PortalLoginNotifications.install();
        RightsEditDialogHook.start(context);
        BuiltinCheckReplacements.start(context);
        QlHubOpenModules.start();
        SuppressionRanges.start();
        ProblemViewHook.AnchorLocation.start();
        MarkerCreationDates.start();
        EmptyExceptStatementFix.start();
        DocCommentLinks.start();
        UiWaitCheckDeferral.start();
        QueryNullCastFix.start();
        CheckRecomputeNotifications.install();
        CheckThreadPriority.start();
        DeepCheckTrace.start(); // #region agent log — временно: долгая расширенная проверка
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
        // Ошибка необязательной доработки установщика не должна срывать запуск плагина.
        try { AvailableSoftwareDialogHook.installUpdateRefreshHook(); }
        catch (Throwable failure)
        {
            try { Global.tempLogException("available-software", "install update refresh hook", failure); } //$NON-NLS-1$ //$NON-NLS-2$
            catch (Throwable ignored) { }
        }
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
            ComfortSettings.PREF_ALPHABETIC_METHODS_ENABLED,
            ComfortSettings.DEFAULT_ALPHABETIC_METHODS_ENABLED);
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
        PortalLoginNotifications.stop();
        BuiltinCheckReplacements.stop();
        QlHubOpenModules.stop();
        SuppressionRanges.stop();
        ProblemViewHook.AnchorLocation.stop();
        MarkerCreationDates.stop();
        EmptyExceptStatementFix.stop();
        DocCommentLinks.stop();
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

    /** #1025: одно уведомление за сеанс, включая запросы до запуска основного бандла. */
    private static final class PortalLoginNotifications
    {
        private static final String NOTIFY = "tormozit.portalLogin.notify"; //$NON-NLS-1$
        private static final String PENDING = "tormozit.portalLogin.pending"; //$NON-NLS-1$
        private static final java.util.concurrent.atomic.AtomicBoolean notified =
            new java.util.concurrent.atomic.AtomicBoolean();
        private static final java.util.concurrent.atomic.AtomicBoolean authenticating =
            new java.util.concurrent.atomic.AtomicBoolean();
        private static volatile boolean running;

        static void install()
        {
            running = true;
            Object pending;
            var properties = System.getProperties();
            synchronized (properties)
            {
                properties.put(NOTIFY, (Consumer<Runnable>) PortalLoginNotifications::request);
                pending = properties.remove(PENDING);
            }
            Global.tempLog("portal-login", "INSTALL pending=" + (pending instanceof Runnable)); //$NON-NLS-1$ //$NON-NLS-2$
            if (pending instanceof Runnable login)
                request(login);
        }

        private static void request(Runnable login)
        {
            boolean first = running && notified.compareAndSet(false, true);
            Global.tempLog("portal-login", "REQUEST first=" + first); //$NON-NLS-1$ //$NON-NLS-2$
            if (!first)
                return;
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> showPending(display, login));
        }

        private static void showPending(Display display, Runnable login)
        {
            if (!running || display.isDisposed())
                return;
            if (!org.eclipse.ui.PlatformUI.isWorkbenchRunning()
                || org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows().length == 0)
            {
                display.timerExec(500, () -> showPending(display, login));
                return;
            }
            Shell toast = ToastNotification.show("Авторизация на сайте 1С", //$NON-NLS-1$
                "Для доступа к этому ресурсу требуется вход через Портал 1С:ИТС. После входа повторите операцию.", //$NON-NLS-1$
                5_000, () -> login(login), "Войти через Портал 1С:ИТС"); //$NON-NLS-1$
            Global.tempLog("portal-login", "SHOW created=" + (toast != null)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static void login(Runnable action)
        {
            boolean start = running && authenticating.compareAndSet(false, true);
            Global.tempLog("portal-login", "LOGIN start=" + start); //$NON-NLS-1$ //$NON-NLS-2$
            if (!start)
                return;
            try
            {
                action.run();
                Global.tempLog("portal-login", "LOGIN returned"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException failure)
            {
                Global.tempLog("portal-login", "LOGIN failed=" + failure); //$NON-NLS-1$ //$NON-NLS-2$
                ToastNotification.show("Авторизация на сайте 1С", //$NON-NLS-1$
                    "Не удалось открыть авторизацию: " + failure.getMessage(), 8_000); //$NON-NLS-1$
            }
            finally
            {
                authenticating.set(false);
            }
        }

        static void stop()
        {
            running = false;
            System.getProperties().remove(NOTIFY);
        }
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
     * Доработки RightsEditDialog и входного выделения его контроллера.
     * API и точки изменения проверены в rights.ui 13.0.100.v202605050943:
     * методы HTTP-сервисов (#924), массовое применение состояний и помеченных прав (#1051).
     */
    private static final class RightsEditDialogHook implements WeavingHook, ClassFileTransformer
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.internal.rights.ui.dialogs.RightsEditDialog"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.rightsEditDialog.objects"; //$NON-NLS-1$
        private static final String ACTION = "tormozit.rightsEditDialog.action";
        private static final String DEPENDENCIES = "tormozit.rightsEditDialog.dependencies";
        private static final String CONTROLLER =
            "com._1c.g5.v8.dt.rights.ui.editors.controllers.RightsEditorController";
        private static final String PANEL = "com._1c.g5.v8.dt.internal.rights.ui.panels.RightsPanel";
        private static final int SET_CHECKED = 301;
        private static final int UNSET_CHECKED = 302;
        private static final Map<Object, Boolean> REMOVAL_MODES = new java.util.WeakHashMap<>();
        private final java.util.Set<String> woven = java.util.concurrent.ConcurrentHashMap.newKeySet();

        static void start(BundleContext context)
        {
            System.getProperties().put(CALLBACK,
                (BiFunction<Object, Object, Object>)RightsEditDialogHook::expandObjects);
            System.getProperties().put(ACTION, (BiFunction<Object, Object, Object>)RightsEditDialogHook::action);
            System.getProperties().put(DEPENDENCIES,
                (BiFunction<Object, Object, Object>)RightsEditDialogHook::dependencyDirection);
            RightsEditDialogHook hook = new RightsEditDialogHook();
            context.registerService(WeavingHook.class, hook, null);
            List<String> loaded = new ArrayList<>();
            for (String target : List.of(TARGET, CONTROLLER, PANEL))
            {
                try
                {
                    Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.rights.ui");
                    if (bundle == null)
                        throw new IllegalStateException("Бандл редактора прав EDT недоступен");
                    bundle.loadClass(target);
                    if (!hook.woven.contains(target))
                        loaded.add(target);
                }
                catch (ClassNotFoundException | RuntimeException | LinkageError failure)
                {
                    Global.logError("RightsEditDialog", "start", failure);
                }
            }
            if (!loaded.isEmpty())
                BslDocCommentDescriptionFix.registerExtraTransformer(hook, loaded.toArray(String[]::new));
        }

        static void stop()
        {
            System.getProperties().put(CALLBACK, (BiFunction<Object, Object, Object>)(input, add) -> input);
            System.getProperties().put(ACTION, (BiFunction<Object, Object, Object>)(event, dialog) ->
                event instanceof Collection<?> ? event : Boolean.FALSE);
            System.getProperties().put(DEPENDENCIES, (BiFunction<Object, Object, Object>)(checked, panel) -> checked);
            REMOVAL_MODES.clear();
        }

        @Override
        public void weave(WovenClass type)
        {
            if (type.getState() != WovenClass.TRANSFORMING
                || !TARGET.equals(type.getClassName()) && !CONTROLLER.equals(type.getClassName())
                    && !PANEL.equals(type.getClassName()))
                return;
            byte[] rewritten = rewrite(type.getClassName(), type.getBytes());
            if (rewritten != null)
            {
                type.setBytes(rewritten);
                woven.add(type.getClassName());
            }
        }

        private static Object expandObjects(Object input, Object dialog)
        {
            if (!(input instanceof Collection<?> objects))
                return input;
            if (!Boolean.TRUE.equals(Global.getField(dialog, "isAddAction"))) //$NON-NLS-1$
            {
                // Панель может содержать родителей методов; права нужны именно входным строкам.
                LinkedHashSet<EObject> selected = new LinkedHashSet<>();
                for (Object row : rows(dialog))
                    if (Global.invoke(row, "getEObject") instanceof EObject object)
                        selected.add(object);
                return selected;
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
            String target = name == null ? "" : name.replace('/', '.');
            if (!TARGET.equals(target) && !CONTROLLER.equals(target) && !PANEL.equals(target))
                return null;
            return rewrite(target, bytes);
        }

        private static List<?> rows(Object dialog)
        {
            Object content = Global.getField(dialog, "content");
            if (!(content instanceof List<?> rows) || rows.isEmpty())
                throw new IllegalStateException("Не получены выбранные объекты редактора прав");
            return rows;
        }

        private static boolean isEdit(Object dialog)
        {
            return Boolean.FALSE.equals(Global.getField(dialog, "isAddAction"));
        }

        private static Object action(Object event, Object dialog)
        {
            if (!isEdit(dialog))
                return event instanceof Collection<?> ? event : Boolean.FALSE;
            if ("updateButtons".equals(event))
            {
                updateButtons(dialog);
                return Boolean.TRUE;
            }
            if (event instanceof Composite parent)
            {
                configureButtons(dialog, parent);
                return Boolean.TRUE;
            }
            if (event instanceof Collection<?>)
            {
                LinkedHashSet<Object> roles = new LinkedHashSet<>();
                for (Object row : rows(dialog))
                    roles.add(java.util.Objects.requireNonNull(Global.invoke(row, "getRole"), "Роль строки"));
                return roles;
            }
            if (event instanceof Integer id
                && (id == IDialogConstants.OK_ID || id == SET_CHECKED))
            {
                boolean removal = isRemoval(dialog);
                if (id == IDialogConstants.OK_ID && removal)
                {
                    return Boolean.TRUE;
                }
                int operation = id == SET_CHECKED && removal ? UNSET_CHECKED : id;
                try
                {
                    if (apply(dialog, operation))
                    {
                        // Сохранение уже выполнено для всех строк. Старый контроллер должен
                        // пропустить свой блок сохранения content[0], для этого возвращаем CANCEL.
                        requireInvoke(dialog, "setReturnCode", IDialogConstants.CANCEL_ID);
                        requireInvoke(dialog, "close");
                    }
                }
                catch (RuntimeException | LinkageError failure)
                {
                    Global.logError("RightsEditDialog", "apply", failure);
                    Shell shell = (Shell)Global.invoke(dialog, "getShell");
                    MessageDialog.openError(shell, "Редактирование прав", "Не удалось применить права: "
                        + failure.getMessage());
                }
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }

        private static void configureButtons(Object dialog, Composite parent)
        {
            Button all = (Button)Global.invoke(dialog, "getButton", IDialogConstants.OK_ID);
            all.setText("Установить все");
            if (all.getLayoutData() instanceof GridData data)
                data.widthHint = Math.max(data.widthHint, all.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x);
            all.setToolTipText(TooltipText.wrap(all,
                "Применить все показанные состояния прав ко всем выбранным объектам: помеченные права установить, непомеченные снять, для наследуемых оставить штатное наследование."
                    + Global.pluginSignForTooltip()));
            Button set = (Button)Global.invoke(dialog, "createButton", parent, SET_CHECKED,
                "Установить помеченные", false);
            set.moveAbove(all);
            Object panel = java.util.Objects.requireNonNull(Global.getField(dialog, "rightsPanel"));
            CheckboxTreeViewer viewer = (CheckboxTreeViewer)java.util.Objects.requireNonNull(Global.getField(panel, "viewer"));
            Composite listParent = viewer.getTree().getParent();
            Composite modeArea = new Composite(listParent, SWT.NONE);
            GridLayout modeLayout = new GridLayout(1, false);
            modeLayout.marginWidth = 0;
            modeLayout.marginHeight = 0;
            modeLayout.marginTop = 4;
            modeLayout.marginBottom = 4;
            modeArea.setLayout(modeLayout);
            modeArea.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            modeArea.moveAbove(viewer.getTree());
            Button removal = new Button(modeArea, SWT.CHECK);
            removal.setText("Для снятия прав");
            removal.setToolTipText(TooltipText.wrap(removal,
                "Подготовить пометки для снятия прав. При переключении все пометки очищаются. Помеченные права снимаются кнопкой «Снять помеченные»."
                    + Global.pluginSignForTooltip()));
            removal.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            REMOVAL_MODES.put(panel, Boolean.FALSE);
            removal.addListener(SWT.Selection, event -> {
                changeMode(panel, removal.getSelection());
                requireInvoke(dialog, "updateOkButton");
                parent.layout(true, true);
            });
            removal.addListener(SWT.Dispose, event -> REMOVAL_MODES.remove(panel));
            requireInvoke(dialog, "updateOkButton");
            if (rows(dialog).size() > 1)
            {
                Shell shell = (Shell)Global.invoke(dialog, "getShell");
                shell.setText("Редактировать права на объекты");
                requireInvoke(dialog, "setMessage", "Выбрано строк: " + rows(dialog).size()
                    + ". Начальные состояния прав показаны по первому объекту. Действие применяется ко всем выбранным строкам.");
            }
        }

        private static void changeMode(Object panel, boolean removal)
        {
            // AbstractViewerPanel хранит пометки скрытых фильтром строк в этих наборах.
            // Поля и checkAll проверены в dt.ui 23.0.0.v202605050943.
            Object checkedState = Global.getField(panel, "checkedElements");
            Object grayedState = Global.getField(panel, "grayedElements");
            if (!(checkedState instanceof Collection<?> checked) || !(grayedState instanceof Collection<?> grayed))
                throw new IllegalStateException("Не получены сохранённые пометки списка прав EDT");
            REMOVAL_MODES.put(panel, removal);
            checked.clear();
            grayed.clear();
            requireInvoke(panel, "checkAll", false);
        }

        private static boolean isRemoval(Object dialog)
        {
            return Boolean.TRUE.equals(REMOVAL_MODES.get(Global.getField(dialog, "rightsPanel")));
        }

        private static void updateButtons(Object dialog)
        {
            boolean removal = isRemoval(dialog);
            Button all = (Button)Global.invoke(dialog, "getButton", IDialogConstants.OK_ID);
            Button marked = (Button)Global.invoke(dialog, "getButton", SET_CHECKED);
            // updateOkButton сначала применяет штатную доступность, затем ограничиваем режимом.
            if (all != null && removal)
                all.setEnabled(false);
            if (marked != null)
            {
                marked.setText(removal ? "Снять помеченные" : "Установить помеченные");
                marked.setToolTipText(TooltipText.wrap(marked, (removal
                    ? "Снять только помеченные права у всех выбранных объектов. Остальные права сохраняются с учётом зависимостей EDT."
                    : "Установить только помеченные права у всех выбранных объектов. Остальные права сохраняются с учётом зависимостей EDT.")
                        + Global.pluginSignForTooltip()));
                if (marked.getLayoutData() instanceof GridData data)
                    data.widthHint = Math.max(data.widthHint, marked.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x);
            }
        }

        private static Object dependencyDirection(Object value, Object panel)
        {
            boolean checked = Boolean.TRUE.equals(value);
            boolean removal = Boolean.TRUE.equals(REMOVAL_MODES.get(panel));
            boolean direction = checked != removal;
            return direction;
        }

        private static void requireInvoke(Object target, String method, Object... arguments)
        {
            if (!Global.invokeVoid(target, method, arguments))
                throw new IllegalStateException("Не найден метод EDT: " + method);
        }

        private static List<Right> panelRights(Object panel, String method)
        {
            Object result = Global.invoke(panel, method);
            if (!(result instanceof Collection<?> values))
                throw new IllegalStateException("Не получен список прав: " + method);
            List<Right> rights = new ArrayList<>();
            for (Object value : values)
                if (value instanceof Right right)
                    rights.add(right);
            return rights;
        }

        @SuppressWarnings("unchecked")
        private static boolean apply(Object dialog, int id)
        {
            Object controller = java.util.Objects.requireNonNull(Global.getField(dialog, "controller"));
            Object panel = java.util.Objects.requireNonNull(Global.getField(dialog, "rightsPanel"));
            List<Right> grayed = panelRights(panel, "getGrayedElements");
            List<Right> checked = panelRights(panel, "getCheckedElements");
            checked.removeIf(right -> RightsModelUtil.isRightContains(right, grayed));
            if (id != IDialogConstants.OK_ID && checked.isEmpty())
            {
                requireInvoke(dialog, "setMessage", "Пометьте права, которые требуется установить или снять.");
                return false;
            }

            Map<Object, Map<EObject, Map<Right, RightValue>>> changesByRole = new LinkedHashMap<>();
            for (Object row : rows(dialog))
            {
                EObject object = (EObject)java.util.Objects.requireNonNull(Global.invoke(row, "getEObject"));
                Object role = java.util.Objects.requireNonNull(Global.invoke(row, "getRole"));
                Object description = java.util.Objects.requireNonNull(Global.invoke(row, "getRoleDescription"));
                if (!Boolean.TRUE.equals(Global.invoke(row, "canEditRole")))
                    throw new IllegalStateException("Роль недоступна для редактирования: " + Global.invoke(row, "getRoleName"));
                Map<Right, RightValue> requested;
                if (id == IDialogConstants.OK_ID)
                {
                    Object result = Global.invoke(controller, "getNewObjectRights", grayed, checked, object);
                    if (!(result instanceof Map<?, ?>))
                        throw new IllegalStateException("Не получены состояния прав объекта " + object);
                    requested = (Map<Right, RightValue>)result;
                }
                else
                {
                    requested = new LinkedHashMap<>();
                    for (Right right : checked)
                        requested.put(right, id == SET_CHECKED ? RightValue.SET : RightValue.UNSET);
                }
                Map<EObject, Map<Right, RightValue>> changes = changesByRole.computeIfAbsent(role,
                    key -> new LinkedHashMap<>());
                // Та же обработка доступности, зависимых прав и вложенных объектов, что у штатного ОК.
                Map<EObject, Map<Right, RightValue>> rowChanges = new LinkedHashMap<>();
                requireInvoke(controller, "collectRightChangesToEditRightValues", description, object,
                    requested, rowChanges, true);
                rowChanges.forEach((changedObject, rights) -> changes.computeIfAbsent(changedObject,
                    key -> new LinkedHashMap<>()).putAll(rights));
            }

            // Все планы рассчитываем до первой записи; один штатный task на роль.
            List<Object> tasks = new ArrayList<>();
            for (var entry : changesByRole.entrySet())
            {
                if (entry.getValue().isEmpty())
                    continue;
                Object task = Global.newInstance("com._1c.g5.v8.dt.rights.tasks.EditRightValuesTask",
                    controller.getClass().getClassLoader(), entry.getKey(), entry.getValue(),
                    Global.getField(controller, "project"), Global.getField(controller, "eventBroker"),
                    Global.getField(controller, "sorter"));
                tasks.add(java.util.Objects.requireNonNull(task, "Не создана задача изменения прав EDT"));
            }
            Object editingContext = java.util.Objects.requireNonNull(Global.getField(controller, "editingContext"));
            for (Object task : tasks)
            {
                Object result = Global.invoke(editingContext, "execute", task);
                if (!(result instanceof org.eclipse.core.runtime.IStatus status))
                    throw new IllegalStateException("Не получен результат изменения прав EDT");
                if (!status.isOK())
                    throw new IllegalStateException(status.getMessage(), status.getException());
            }
            return true;
        }

        private static byte[] rewrite(String target, byte[] bytes)
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
                    if (PANEL.equals(target))
                    {
                        if (!method.equals("getDependedRights") || !descriptor.equals(
                            "(Lcom/_1c/g5/v8/dt/rights/model/Right;Ljava/util/HashSet;Z)Ljava/util/Collection;"))
                            return visitor;
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitCode()
                            {
                                super.visitCode();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                                    "()Ljava/util/Properties;", false);
                                super.visitLdcInsn(DEPENDENCIES);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction");
                                super.visitVarInsn(Opcodes.ILOAD, 3);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                                    "(Z)Ljava/lang/Boolean;", false);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                                super.visitVarInsn(Opcodes.ISTORE, 3);
                                returns[0]++;
                            }
                        };
                    }
                    if (CONTROLLER.equals(target))
                    {
                        if (!method.equals("openEditObjectRightsWizard")
                            || !descriptor.equals("(Lorg/eclipse/swt/widgets/Shell;)V"))
                            return visitor;
                        return new MethodVisitor(Opcodes.ASM9, visitor)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name, String desc,
                                boolean isInterface)
                            {
                                if (owner.equals("java/util/List") && name.equals("subList")
                                    && desc.equals("(II)Ljava/util/List;"))
                                {
                                    // list, 0, 1 -> list: передаём в диалог всё выделение.
                                    super.visitInsn(Opcodes.POP2);
                                    returns[0]++;
                                }
                                else
                                    super.visitMethodInsn(opcode, owner, name, desc, isInterface);
                            }
                        };
                    }
                    boolean objects = method.equals("getCurrentSelectedObjects")
                        && descriptor.equals("()Ljava/util/Collection;");
                    boolean roles = method.equals("getContentRoles") && descriptor.equals("()Ljava/util/Collection;");
                    boolean inputs = method.equals("setRightsInputs") && descriptor.equals("()V");
                    boolean buttons = method.equals("createButtonsForButtonBar")
                        && descriptor.equals("(Lorg/eclipse/swt/widgets/Composite;)V");
                    boolean press = method.equals("buttonPressed") && descriptor.equals("(I)V");
                    boolean update = method.equals("updateOkButton") && descriptor.equals("()V");
                    if (!objects && !roles && !inputs && !buttons && !press && !update)
                        return visitor;
                    return new MethodVisitor(Opcodes.ASM9, visitor)
                    {
                        private void callback(String key)
                        {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                                "()Ljava/util/Properties;", false);
                            super.visitLdcInsn(key);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction");
                        }

                        private void applyCallback()
                        {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                        }

                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            if (press)
                            {
                                callback(ACTION);
                                super.visitVarInsn(Opcodes.ILOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                                    "(I)Ljava/lang/Integer;", false);
                                applyCallback();
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                                org.objectweb.asm.Label original = new org.objectweb.asm.Label();
                                super.visitJumpInsn(Opcodes.IFEQ, original);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(original);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                returns[0]++;
                            }
                        }

                        @Override
                        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label)
                        {
                            // Обе штатные проверки content.size() == 1 теперь допускают >= 1.
                            if ((roles || inputs) && opcode == Opcodes.IF_ICMPNE)
                            {
                                opcode = Opcodes.IF_ICMPLT;
                                returns[0]++;
                            }
                            super.visitJumpInsn(opcode, label);
                        }

                        @Override
                        public void visitInsn(int opcode)
                        {
                            if ((objects || roles) && opcode == Opcodes.ARETURN)
                            {
                                callback(objects ? CALLBACK : ACTION);
                                super.visitInsn(Opcodes.SWAP);
                                applyCallback();
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Collection");
                                returns[0]++;
                            }
                            else if ((buttons || update) && opcode == Opcodes.RETURN)
                            {
                                callback(ACTION);
                                if (buttons)
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                else
                                    super.visitLdcInsn("updateButtons");
                                applyCallback();
                                super.visitInsn(Opcodes.POP);
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
     * Общий резолвер «см.»: настоящий BSL Method/FormalParam вместо проверки одного имени (#1026).
     * Точки входа подтверждены bsl.comment 6.1.0.v202605050943: проверка, гиперссылки,
     * комментарий и вычисление типов вызывают LinkPart.getActualObjectForPart.
     */
    private static final class DocCommentLinks
    {
        private static final String CALLBACK = "tormozit.docCommentLink.resolve"; //$NON-NLS-1$
        private static final String PROPOSALS = "tormozit.docCommentLink.proposals"; //$NON-NLS-1$
        private static final String DOCUMENTATION_NODE = "tormozit.docCommentLink.documentationNode"; //$NON-NLS-1$
        private static final java.util.Map<URI, Model> MODELS = new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.Set<URI> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private static final ThreadLocal<Boolean> PREPARING = new ThreadLocal<>();

        private record Model(long stamp, XtextResource resource) {}

        static void start()
        {
            System.getProperties().put(CALLBACK,
                (java.util.function.Function<Object[], Object>)DocCommentLinks::resolve);
            System.getProperties().put(PROPOSALS, (Consumer<Object[]>)DocCommentLinks::complete);
            System.getProperties().put(DOCUMENTATION_NODE,
                (java.util.function.Function<Object[], Object>)DocCommentLinks::isDocumentationComment);
        }

        static void stop()
        {
            System.getProperties().put(CALLBACK, (java.util.function.Function<Object[], Object>)args -> args[0]);
            System.getProperties().put(PROPOSALS, (Consumer<Object[]>)args -> {});
            System.getProperties().put(DOCUMENTATION_NODE, (java.util.function.Function<Object[], Object>)args -> args[0]);
            MODELS.clear();
        }

        private static boolean isDocumentationComment(Object[] args)
        {
            boolean result = Boolean.TRUE.equals(args[0]);
            try
            {
                if (!result || !(args[1] instanceof com._1c.g5.v8.dt.bsl.model.Method method)
                    || !(args[2] instanceof INode node))
                    return result;
                Object provider = Global.getField(args[3], "commentProvider"); //$NON-NLS-1$
                if (!(provider instanceof BslMultiLineCommentDocumentationProvider comments))
                    return result;
                var documentation = comments.getDocumentationNodes(method);
                result = documentationContains(node, documentation);
                return result;
            }
            catch (RuntimeException | LinkageError ignored)
            {
                return result;
            }
        }

        private static boolean documentationContains(INode comment, java.util.List<? extends INode> documentation)
        {
            int start = comment.getTotalOffset();
            int end = comment.getTotalEndOffset();
            for (INode node : documentation)
                if (start >= node.getTotalOffset() && end <= node.getTotalEndOffset())
                    return true;
            return false;
        }

        /** Кандидаты ссылки «см. Модуль.Метод»: штатный список включает только Экспорт. */
        private static void complete(Object[] args)
        {
            try
            {
                Object provider = args[0];
                LinkPart link = (LinkPart)args[1];
                int offset = ((Integer)args[2]).intValue();
                EObject source = (EObject)args[3];
                ContentAssistContext assist = (ContentAssistContext)args[4];
                ICompletionProposalAcceptor acceptor = (ICompletionProposalAcceptor)args[5];
                BmOperationContext context = (BmOperationContext)args[6];
                boolean enabled = BslDocCommentComputedTypes.isExtendedTypesEnabled(source);
                if (!enabled)
                    return;
                // Точно как начало createProposalsForLinkPartCommentContent в EDT:
                // offset и смещения частей находятся в координатах строки комментария.
                int part = -1;
                for (var pair : link.getPartsWithOffset())
                {
                    if (offset < pair.getSecond().intValue())
                        break;
                    part++;
                }
                if (part < 1)
                    return;
                Object scope = Global.getField(provider, "bslScope"); //$NON-NLS-1$
                Object projectManager = Global.getField(provider, "v8projectManager"); //$NON-NLS-1$
                if (!(scope instanceof IScopeProvider scopes) || !(projectManager instanceof IV8ProjectManager projects))
                    return;
                EObject owner = link.getActualObjectForPart(part - 1, scopes, source, projects, context);
                if (owner instanceof BasicForm form)
                    owner = form.getForm();
                if (owner == null || owner.eIsProxy())
                    return;
                if (part >= 2 && owner instanceof com._1c.g5.v8.dt.bsl.model.Method method)
                {
                    // Штатная ветка внешнего модуля работает только с mcore.Method,
                    // а неэкспортная цель резолвится в настоящий BSL Method.
                    var names = completionParameterNames(method);
                    acceptCompletionNames(provider, names, null, assist, acceptor);
                    return;
                }
                boolean formMethods = owner instanceof AbstractForm;
                String featureName = formMethods || owner instanceof CommonModule ? "module" : "managerModule"; //$NON-NLS-1$ //$NON-NLS-2$
                var feature = owner.eClass().getEStructuralFeature(featureName);
                if (feature == null || !(owner.eGet(feature, false) instanceof EObject proxy))
                    return;
                URI uri = EcoreUtil.getURI(proxy);
                String fqn = context.getNamingService().getDependentObjectFqnAsString(uri,
                    BslIndexPackage.Literals.MODULE_CONTEXT_DEF_INDEX);
                var index = context.getPlatformTransaction().getTopObjectByFqn(context.getNamespace(uri), fqn);
                if (!(index instanceof ModuleContextDefIndex moduleIndex) || moduleIndex.getContextDef() == null)
                    return;
                // Только индекс имён: для списка не нужны AST, типы возврата и связывание модуля.
                var names = completionNames(moduleIndex.getContextDef(), formMethods);
                var image = BslSharedImages.getImage("com._1c.g5.v8.dt.bsl.ui/obj16/meth_obj.png"); //$NON-NLS-1$
                acceptCompletionNames(provider, names, image, assist, acceptor);
            }
            catch (RuntimeException | LinkageError ignored)
            {
            }
        }

        private static void acceptCompletionNames(Object provider, java.util.List<String> names, Image image,
            ContentAssistContext context, ICompletionProposalAcceptor acceptor)
        {
            for (String name : names)
            {
                Object proposal = Global.invoke(provider, "createSimpleProposals", name, name, image, context); //$NON-NLS-1$
                if (proposal instanceof ICompletionProposal completion)
                {
                    acceptor.accept(completion);
                }
            }
        }

        private static java.util.List<String> completionParameterNames(com._1c.g5.v8.dt.bsl.model.Method method)
        {
            var names = new java.util.ArrayList<String>();
            for (var parameter : method.getFormalParams())
                if (parameter.getName() != null && !parameter.getName().isEmpty())
                    names.add(parameter.getName());
            return names;
        }

        private static com._1c.g5.v8.dt.bsl.model.FormalParam parameterFromMethod(
            com._1c.g5.v8.dt.bsl.model.Method method, String name)
        {
            for (var parameter : method.getFormalParams())
                if (name.equalsIgnoreCase(parameter.getName()))
                    return parameter;
            return null;
        }

        private static java.util.List<String> completionNames(BslContextDef context, boolean formMethods)
        {
            var seen = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            // В обычном модуле экспортные уже предложены EDT. У формы штатная ветка
            // предлагает реквизиты/элементы, а методы не предлагает вовсе.
            if (!formMethods)
                for (var method : context.getMethods())
                    if (method.getName() != null)
                        seen.add(method.getName());
            var names = new java.util.ArrayList<String>();
            String all = context.getAllMethodNames();
            if (all != null)
                for (String name : all.split(",")) //$NON-NLS-1$
                    if (!name.isEmpty() && seen.add(name))
                        names.add(name);
            return names;
        }

        private static Object resolve(Object[] args)
        {
            EObject result = (EObject)args[0];
            LinkPart link = (LinkPart)args[1];
            int part = ((Integer)args[2]).intValue();
            try
            {
                var parts = link.getPartsWithOffset();
                if (result != null || part < 1 || part >= parts.size())
                    return result;
                IScopeProvider scopes = (IScopeProvider)args[3];
                EObject source = (EObject)args[4];
                IV8ProjectManager projects = (IV8ProjectManager)args[5];
                BmOperationContext context = (BmOperationContext)args[6];
                if (source == null || scopes == null || context == null || projects == null)
                    return null;
                boolean enabled = BslDocCommentComputedTypes.isExtendedTypesEnabled(source);
                if (!enabled)
                    return result;
                // Предыдущий сегмент разрешается тем же общим механизмом. Это также
                // позволяет ссылаться на параметр найденного неэкспортного метода.
                EObject owner = link.getActualObjectForPart(part - 1, scopes, source, projects, context);
                String name = parts.get(part).getFirst();
                if (owner instanceof com._1c.g5.v8.dt.bsl.model.Method method)
                {
                    result = parameterFromMethod(method, name);
                    return result;
                }
                if (owner instanceof BasicForm form)
                    owner = form.getForm();
                if (owner == null || owner.eIsProxy())
                    return null;
                String featureName = owner instanceof AbstractForm || owner instanceof CommonModule
                    ? "module" : "managerModule"; //$NON-NLS-1$ //$NON-NLS-2$
                var feature = owner.eClass().getEStructuralFeature(featureName);
                if (feature == null || !(owner.eGet(feature, false) instanceof EObject proxy))
                    return null;
                URI uri = EcoreUtil.getURI(proxy);
                // BslBmLinkProvider.update(Resource) сохраняет и модель открытого
                // редактора. Читаем готовую запись, включая несохранённые правки,
                // без getResource/getObject: они могут загрузить и пересчитать цель.
                Resource nativeResource = peekNativeResource(uri);
                if (nativeResource != null)
                {
                    if (ready(nativeResource))
                    {
                        result = findMethod((XtextResource)nativeResource, name);
                        return result;
                    }
                    // Облегчённая модель не перекрывает подготовленную. linked=false
                    // означает отсутствие расчёта типов, а не отсутствие объявления.
                }
                // Методы берём из AST, а не allMethodNames: нужны тело, параметры,
                // комментарий и исходный URI для всех потребителей ссылки.
                var sourceResource = source.eResource();
                var set = sourceResource != null ? sourceResource.getResourceSet() : null;
                Resource existing = set != null ? set.getResource(uri.trimFragment(), false) : null;
                if (ready(existing) && sameSource(nativeResource, existing))
                {
                    result = findMethod((XtextResource)existing, name);
                    return result;
                }
                var file = ForeignModuleModel.fileOf(uri.trimFragment());
                if (file == null)
                    return null;
                Model cached = MODELS.get(uri.trimFragment());
                if (cached != null && cached.stamp() == file.getModificationStamp() && ready(cached.resource())
                    && sameSource(nativeResource, cached.resource()))
                {
                    result = findMethod(cached.resource(), name);
                    return result;
                }
                Resource parsed = parsedModule(nativeResource) != null ? nativeResource : existing;
                if (parsedModule(parsed) != null)
                {
                    // Ctrl+клик, проверка существования и имена параметров читают AST.
                    // Для них нельзя требовать полного расчёта возвращаемого типа.
                    result = findMethod(parsed, name);
                    if (result != null)
                        request(uri, source);
                    return result;
                }
                boolean peek = peekOnly();
                boolean inner = Thread.currentThread()
                    instanceof com._1c.g5.v8.dt.bsl.resource.BslInnerProcessorThread;
                // BslResource.linkBatched явно пропускает BslInnerProcessorThread.
                // В нём повторная загрузка цели не может подготовить её типы.
                if (peek || inner || Boolean.TRUE.equals(PREPARING.get()))
                {
                    request(uri, source);
                    return null;
                }
                // Обычный фоновый расчёт типов/проверки может подготовить цель сразу.
                // В цепочке подсказки этот путь закрыт, даже если она считается не в UI.
                Model model = load(uri, file);
                result = model != null ? findMethod(model.resource(), name) : null;
                if (model == null)
                    request(uri, source);
                return result;
            }
            catch (RuntimeException | LinkageError ignored)
            {
                return result;
            }
        }

        private static com._1c.g5.v8.dt.bsl.model.Module parsedModule(Resource resource)
        {
            // getContents() у DerivedStateAwareResource может запускать производное состояние;
            // готовый корень берём непосредственно из результата разбора.
            return resource instanceof XtextResource xtext && xtext.getParseResult() != null
                && xtext.getParseResult().getRootASTElement() instanceof com._1c.g5.v8.dt.bsl.model.Module module
                    ? module : null;
        }

        private static boolean sameSource(Resource current, Resource prepared)
        {
            if (current == null || current == prepared)
                return true;
            if (!(current instanceof XtextResource first) || !(prepared instanceof XtextResource second))
                return false;
            var firstParse = first.getParseResult();
            var secondParse = second.getParseResult();
            // Файловый штамп не меняется от несохранённой правки открытого документа.
            // Модель с диска допустима только для точно того же текста.
            return firstParse != null && secondParse != null && firstParse.getRootNode() != null
                && secondParse.getRootNode() != null
                && firstParse.getRootNode().getText().equals(secondParse.getRootNode().getText());
        }

        private static com._1c.g5.v8.dt.bsl.model.Method methodFromModel(Resource resource, String name)
        {
            var module = parsedModule(resource);
            if (module != null)
                for (var method : module.allMethods())
                    if (name.equalsIgnoreCase(method.getName()))
                        return method;
            return null;
        }

        private static EObject findMethod(Resource resource, String name)
        {
            return methodFromModel(resource, name);
        }

        private static void publishReturnTypes(XtextResource resource)
        {
            var parsed = resource.getParseResult();
            if (parsed != null && parsed.getRootASTElement()
                instanceof com._1c.g5.v8.dt.bsl.model.Module module)
            {
                // computeTypesByLinkPart дополняет комментарий только из этого кэша.
                // Подготовка отдельного ресурса сама по себе запись туда не гарантирует.
                BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
            }
        }

        private static Resource peekNativeResource(URI uri)
        {
            try
            {
                var services = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
                Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.bsl.bm"); //$NON-NLS-1$
                if (services == null || bundle == null)
                    return null;
                Object provider = services.get(bundle.loadClass("com._1c.g5.v8.dt.bsl.bm.BslBmLinkProvider")); //$NON-NLS-1$
                // XtextBmLinkProvider.allLoadedResources / getIfPresent подтверждены
                // dt.bm.xtext: используем только чтение, не штатный загружающий getter.
                Object cache = Global.getField(provider, "allLoadedResources"); //$NON-NLS-1$
                Object found = cache != null ? Global.invoke(cache, "getIfPresent", uri.trimFragment()) : null; //$NON-NLS-1$
                return found instanceof Resource resource ? resource : null;
            }
            catch (ClassNotFoundException | RuntimeException | LinkageError ignored)
            {
                return null;
            }
        }

        private static boolean ready(Resource resource)
        {
            return resource instanceof com._1c.g5.v8.dt.bsl.resource.BslResource bsl
                && bsl.getParseResult() != null && bsl.isFullyInitialized() && bsl.isLinkedBatch();
        }

        private static boolean peekOnly()
        {
            if (Display.getCurrent() != null)
                return true;
            // Оба вида подсказок используют чтение готовой модели. Учитываем также
            // штатного провайдера документации, вызываемого вне нашего чтения AST.
            return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getMethodName().startsWith("readOnlyPeekAst")
                || "getAdditionalProposalInfo".equals(frame.getMethodName())
                || frame.getClassName().startsWith("tormozit.BslEditorHoverHook")
                || frame.getClassName().startsWith("tormozit.ParamHintHtmlModifier")
                || frame.getClassName().startsWith("com._1c.g5.v8.dt.internal.bsl.ui.documentation.")));
        }

        private static Model load(URI uri, org.eclipse.core.resources.IFile file)
        {
            URI resourceUri = uri.trimFragment();
            long stamp = file.getModificationStamp();
            var services = IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(resourceUri);
            var set = services != null ? BslOccurrenceContextResolver.newLiveResourceSet(services, file.getProject()) : null;
            if (set == null)
                return null;
            Boolean previous = PREPARING.get();
            PREPARING.set(Boolean.TRUE);
            try
            {
                // Готовая модель с живым индексом — тот же путь, что ForeignModuleModel.
                // Не создаём модель из снимка текста открытого редактора (#877).
                XtextResource resource = BslOccurrenceContextResolver.loadLinkedResource(resourceUri, set);
                if (!ready(resource) || stamp != file.getModificationStamp())
                    return null;
                publishReturnTypes(resource);
                Model model = new Model(stamp, resource);
                if (MODELS.size() >= 64)
                    MODELS.clear();
                MODELS.put(resourceUri, model);
                return model;
            }
            finally
            {
                if (previous == null)
                    PREPARING.remove();
                else
                    PREPARING.set(previous);
            }
        }

        private static void request(URI uri, EObject source)
        {
            URI resourceUri = uri.trimFragment();
            if (!PENDING.add(resourceUri))
                return;
            var file = ForeignModuleModel.fileOf(resourceUri);
            var consumerResource = source.eResource();
            var consumerMethod = EcoreUtil2.getContainerOfType(source, com._1c.g5.v8.dt.bsl.model.Method.class);
            URI consumer = EcoreUtil.getURI(consumerMethod != null ? consumerMethod : source);
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
            {
                PENDING.remove(resourceUri);
                return;
            }
            // Поиск открытого документа — UI, чтение/подготовка — отдельное задание.
            // Несохранённый документ не заменяется моделью с диска.
            display.asyncExec(() ->
            {
                if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(source))
                {
                    PENDING.remove(resourceUri);
                    return;
                }
                var document = file != null ? ForeignModuleModel.openDocument(file) : null;
                org.eclipse.core.runtime.jobs.Job job = org.eclipse.core.runtime.jobs.Job.create(
                    "Подготовка метода по ссылке в комментарии", monitor ->
                    {
                        try
                        {
                            if (file != null && !monitor.isCanceled()
                                && BslDocCommentComputedTypes.isExtendedTypesEnabled(source))
                            {
                                Model model = document == null ? load(resourceUri, file)
                                    : document.readOnly(resource ->
                                    {
                                        if (resource == null || resource.getResourceSet() == null)
                                            return null;
                                        XtextResource prepared = BslOccurrenceContextResolver.loadLinkedResource(
                                            resourceUri, resource.getResourceSet());
                                        if (ready(prepared))
                                            publishReturnTypes(prepared);
                                        return ready(prepared) ? new Model(file.getModificationStamp(), prepared) : null;
                                    });
                                if (model != null && BslDocCommentComputedTypes.isExtendedTypesEnabled(source))
                                {
                                    // И модель открытого документа нужна следующему обращению.
                                    // Раньше сохранялась только модель, загруженная с диска.
                                    MODELS.put(resourceUri, model);
                                    // Обновление типов потребителя выполняется отдельно от подсказки,
                                    // без запуска автопроверок и ожидания в потоке её показа.
                                    BslStructureInsertCommentTypes.refreshDocReturnConsumer(
                                        consumerResource, consumer);
                                    BslDocCommentComputedTypes.bumpTypesGeneration();
                                    // Первый список мог открыться пустым до подготовки цели.
                                    // Как в DocReturnDependencies: пересчитать и уже открытый список.
                                    display.asyncExec(ContentAssistSessionReloader::refreshPopupIfOpen);
                                }
                            }
                        }
                        catch (RuntimeException | LinkageError ignored)
                        {
                        }
                        finally
                        {
                            PENDING.remove(resourceUri);
                        }
                    });
                job.setRule(ForeignModuleModel.SERIAL);
                job.setSystem(true);
                job.schedule();
            });
        }
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

    // #region agent log — временно: почему расширенная проверка одного модуля долго стоит на 0%
    /**
     * Временная диагностика, тема {@code deep-check}. События заданий проверки и сборки и раз в
     * {@link #PERIOD_MS} снимок потоков производных данных и сборки: состояние, прирост процессорного
     * времени, что считают, кого ждут; плюс куча и прирост времени GC. Для проверки редактора —
     * полный стек. PID разделяет записи IDE и отладочной EDT. Пишет всегда, без флажка.
     */
    private static final class DeepCheckTrace
    {
        private static final String TOPIC = "deep-check"; //$NON-NLS-1$
        private static final long PID = ProcessHandle.current().pid();
        private static final long PERIOD_MS = 5_000L;
        private static final java.util.Map<Long, Long> CPU = new java.util.HashMap<>();
        private static final java.util.Set<org.eclipse.core.runtime.jobs.Job> RUNNING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
        private static long gcMs = -1;

        static void start()
        {
            try
            {
                org.eclipse.core.runtime.jobs.Job.getJobManager().addJobChangeListener(
                    new org.eclipse.core.runtime.jobs.JobChangeAdapter()
                    {
                        @Override
                        public void scheduled(org.eclipse.core.runtime.jobs.IJobChangeEvent event)
                        {
                            jobEvent("scheduled", event); //$NON-NLS-1$
                        }

                        @Override
                        public void running(org.eclipse.core.runtime.jobs.IJobChangeEvent event)
                        {
                            if (tracked(event.getJob()))
                                RUNNING.add(event.getJob());
                            jobEvent("running", event); //$NON-NLS-1$
                        }

                        @Override
                        public void done(org.eclipse.core.runtime.jobs.IJobChangeEvent event)
                        {
                            RUNNING.remove(event.getJob());
                            jobEvent("done", event); //$NON-NLS-1$
                        }
                    });
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable ->
                {
                    Thread thread = new Thread(runnable, "Комфорт: временный лог расширенной проверки"); //$NON-NLS-1$
                    thread.setDaemon(true);
                    return thread;
                }).scheduleWithFixedDelay(DeepCheckTrace::sample, PERIOD_MS, PERIOD_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
                Global.tempLog(TOPIC, "start pid=" + PID); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                Global.tempLogException(TOPIC, "start", t); //$NON-NLS-1$
            }
        }

        private static boolean tracked(org.eclipse.core.runtime.jobs.Job job)
        {
            if (job == null)
                return false;
            String name = String.valueOf(job.getName()).toLowerCase();
            return name.contains("проверк") || name.contains("сборк") || name.contains("валидац") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || job.getClass().getName().contains("Derived"); //$NON-NLS-1$
        }

        private static void jobEvent(String kind, org.eclipse.core.runtime.jobs.IJobChangeEvent event)
        {
            org.eclipse.core.runtime.jobs.Job job = event.getJob();
            if (!tracked(job))
                return;
            org.eclipse.core.runtime.IStatus result = event.getResult();
            Global.tempLog(TOPIC, "job " + kind + " pid=" + PID + " name=\"" + job.getName() + "\" class=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + job.getClass().getName() + " id=" + Integer.toHexString(System.identityHashCode(job)) //$NON-NLS-1$
                + (result != null ? " result=" + result.getSeverity() : "") //$NON-NLS-1$ //$NON-NLS-2$
                + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
        }

        private static void sample()
        {
            try
            {
                java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
                StringBuilder busy = new StringBuilder();
                int idle = 0;
                ThreadGroup root = Thread.currentThread().getThreadGroup();
                while (root.getParent() != null)
                    root = root.getParent();
                Thread[] threads = new Thread[root.activeCount() + 16];
                int count = root.enumerate(threads, true);
                for (int i = 0; i < count; i++)
                {
                    Thread thread = threads[i];
                    if (thread == null)
                        continue;
                    String name = thread.getName();
                    String lowerName = name.toLowerCase(java.util.Locale.ROOT);
                    if (!name.startsWith("derived_data_executor_") && !name.startsWith("LCBuilderState") //$NON-NLS-1$ //$NON-NLS-2$
                        && !lowerName.contains("проверк")) //$NON-NLS-1$
                        continue;
                    long id = thread.getId();
                    long cpu = mx.getThreadCpuTime(id);
                    Long previous = CPU.put(id, cpu);
                    long cpuDelta = previous == null || cpu < 0 ? -1 : (cpu - previous) / 1_000_000L;
                    java.lang.management.ThreadInfo info = mx.getThreadInfo(id, 400);
                    if (info == null)
                        continue;
                    StackTraceElement[] stack = info.getStackTrace();
                    if (isIdle(stack))
                    {
                        idle++;
                        continue;
                    }
                    busy.append("\n    ").append(name).append(' ').append(info.getThreadState()) //$NON-NLS-1$
                        .append(" prio=").append(thread.getPriority()) //$NON-NLS-1$
                        .append(" cpu+=").append(cpuDelta).append("ms") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(" depth=").append(stack.length); //$NON-NLS-1$
                    if (info.getLockName() != null)
                        busy.append(" lock=").append(info.getLockName()).append(" owner=") //$NON-NLS-1$ //$NON-NLS-2$
                            .append(info.getLockOwnerName());
                    busy.append(" top=").append(frame(firstOwn(stack, false))) //$NON-NLS-1$
                        .append(" comfort=").append(frame(firstOwn(stack, true))) //$NON-NLS-1$
                        .append(" task=").append(frame(taskFrame(stack))) //$NON-NLS-1$
                        .append(" deep=").append(contains(stack, "performDeepAnalisys")); //$NON-NLS-1$ //$NON-NLS-2$
                    // Проверка Xтекст раньше выпадала из фильтра из-за заглавной «П».
                    // Полный стек отличает расчёт типов от ожидания блокировки модели.
                    if (lowerName.contains("проверк") && lowerName.contains("текст")) //$NON-NLS-1$ //$NON-NLS-2$
                        for (StackTraceElement element : stack)
                            busy.append("\n        at ").append(element); //$NON-NLS-1$
                }
                long gc = 0;
                for (java.lang.management.GarbageCollectorMXBean bean : java.lang.management.ManagementFactory
                    .getGarbageCollectorMXBeans())
                    gc += Math.max(0, bean.getCollectionTime());
                long gcDelta = gcMs < 0 ? -1 : gc - gcMs;
                gcMs = gc;
                Runtime runtime = Runtime.getRuntime();
                long usedMb = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
                StringBuilder jobs = new StringBuilder();
                for (org.eclipse.core.runtime.jobs.Job job : RUNNING)
                    jobs.append(jobs.length() == 0 ? "" : "; ").append(job.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                Global.tempLog(TOPIC, "sample pid=" + PID + " heapMb=" + usedMb + "/" + (runtime.maxMemory() >> 20) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " gc+=" + gcDelta + "ms jobs=[" + jobs + "] idle=" + idle + " busy:" + busy); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
            catch (Throwable t)
            {
                Global.tempLogException(TOPIC, "sample", t); //$NON-NLS-1$
            }
        }

        /** Поток пула ждёт задачу. */
        private static boolean isIdle(StackTraceElement[] stack)
        {
            for (int i = 0; i < Math.min(stack.length, 12); i++)
            {
                String method = stack[i].getMethodName();
                if (method.equals("getTask") || method.equals("awaitWork") || method.equals("schedulingLoop") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    || method.equals("startJob")) //$NON-NLS-1$
                    return true;
            }
            return false;
        }

        /** Первый кадр не из JDK/EMF/Guava; {@code comfort} — первый кадр плагина. */
        private static StackTraceElement firstOwn(StackTraceElement[] stack, boolean comfort)
        {
            for (StackTraceElement element : stack)
            {
                String type = element.getClassName();
                if (comfort ? type.startsWith("tormozit.") //$NON-NLS-1$
                    : type.startsWith("com._1c.") || type.startsWith("com.e1c.") || type.startsWith("tormozit.")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return element;
            }
            return null;
        }

        /** Кадр над {@code WorkerManager$TaskProcessor.processTask} — какой расчёт производных данных идёт. */
        private static StackTraceElement taskFrame(StackTraceElement[] stack)
        {
            for (int i = 1; i < stack.length; i++)
                if (stack[i].getMethodName().equals("processTask")) //$NON-NLS-1$
                    return stack[i - 1];
            return null;
        }

        private static boolean contains(StackTraceElement[] stack, String method)
        {
            for (StackTraceElement element : stack)
                if (element.getMethodName().equals(method))
                    return true;
            return false;
        }

        private static String frame(StackTraceElement element)
        {
            if (element == null)
                return "-"; //$NON-NLS-1$
            String type = element.getClassName();
            return type.substring(type.lastIndexOf('.') + 1) + "." + element.getMethodName() + ":" //$NON-NLS-1$ //$NON-NLS-2$
                + element.getLineNumber();
        }
    }
    // #endregion
}
