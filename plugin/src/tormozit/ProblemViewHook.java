package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.State;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.descriptor.basic.MPartDescriptor;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.menu.MHandledToolItem;
import org.eclipse.e4.ui.model.application.ui.menu.MToolBar;
import org.eclipse.e4.ui.model.application.ui.menu.MToolBarElement;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IOpenListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.RegistryToggleState;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.md.MdUtil;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.validation.marker.DelayedMarkerUpdateListener;
import com._1c.g5.v8.dt.validation.marker.IMarkerInfo;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerUpdateListener;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerIndex;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.MarkersChangedEvent;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerReader;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ISuppressSettingsManager;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Панель проблем конфигурации ({@code com._1c.g5.v8.dt.ui.problemView}), issue 401.
 *
 * <ul>
 * <li><b>Заголовок.</b> «Ошибки конфигурации» → «Проблемы конфигурации»: панель
 * показывает не только ошибки, но и предупреждения, а «ошибка конфигурации» —
 * это отдельный вид проблемы (он же отдельный флажок в «Настройках отбора», см.
 * {@link ProblemFiltersDialogHook}).</li>
 * <li><b>Подсказка строки итогов.</b> Штатная надпись «Ошибок / Предупреждений»
 * считает по критичности (группы дерева), а не по типу строки. Подсказка
 * объясняет это: тип «Прочая ошибка» (красный крестик) при критичности
 * «Значительная» попадает в предупреждения.</li>
 * <li><b>Тип «Прочая ошибка».</b> Штатное имя {@code IssueType.ERROR} совпадает
 * с критичностью «Ошибки конфигурации». В дереве и подсказке строки тип
 * показывается как «Прочая ошибка». В окне отбора отдельного флажка этого типа
 * нет: он привязан к «Показывать ошибки конфигурации», у которого своя подсказка
 * ({@link ProblemFiltersDialogHook}).</li>
 * <li><b>Двойной щелчок в колонке «Код проверки»</b> открывает настройку этой
 * проверки на странице «Проверки» параметров проекта — вместо перехода к самой
 * проблеме, который остаётся на всех остальных колонках. Штатное открытие
 * редактора для этой колонки подавляется, иначе поверх настроек открывался бы
 * ещё и редактор объекта.</li>
 * </ul>
 *
 * <p><b>Все доработки поведения панели подчиняются флажку</b> Параметры → Комфорт →
 * «Улучшать списки» ({@link ComfortSettings#PREF_REPLACE_LIST_FILTERS}): имя панели,
 * заслонка обновлений (тумблер «Фильтр обновлений» в тулбаре панели), подпись «Область: …»,
 * подсказка строки итогов, имя типа
 * «Прочая ошибка», индикатор неполноты списка слева от итогов,
 * открытие настройки проверки двойным щелчком и свои области отбора ({@link ProblemViewComfortScope}). Флажок
 * читается в момент срабатывания, а его переключение обрабатывается сразу
 * ({@link #listenReplaceListFilters}) — перезапуск EDT не нужен. Команды, добавленные
 * плагином в меню и тулбар панели, флажку не подчиняются: это не изменение штатного
 * поведения, а отдельные команды.</p>
 *
 * <p>То же открытие настройки ({@link #openCheckSettings}) переиспользуют команда
 * «Открыть настройку проверки» в тулбаре и контекстном меню панели
 * ({@code ProblemViewOpenCheckSettingsHandler}) и кнопка в подсказке предупреждения
 * в редакторе модуля ({@code BslCheckSettingsHoverContributor}).</p>
 *
 * <p>Отдельный файл, а не вложенный класс: точка входа из {@code plugin.xml}.
 */
public final class ProblemViewHook implements IStartup
{
    /** Страница «Проверки» в свойствах проекта ({@code ValidationPreferencePage.PROPERTIES_PAGE_ID}). */
    private static final String CHECKS_PAGE_ID = "com.e1c.g5.v8.dt.checks.properties"; //$NON-NLS-1$
    private static final String CODE_COLUMN_TITLE = "Код проверки"; //$NON-NLS-1$
    private static final String VIEW_TITLE = "Проблемы конфигурации"; //$NON-NLS-1$
    private static final String SUPPRESS_ISSUE_COMMAND_ID =
        "com._1c.g5.v8.dt.ui.command.suppressIssue"; //$NON-NLS-1$

    private static final String SCOPE_LABEL_KEY = "tormozit.problemViewScopeLabel"; //$NON-NLS-1$
    private static final String STATS_TOOLTIP_KEY = "tormozit.problemViewStatsTooltip"; //$NON-NLS-1$
    private static final String TYPE_RENAME_KEY = "tormozit.problemViewTypeRename"; //$NON-NLS-1$
    private static final String OPEN_OVERRIDE_KEY = "tormozit.problemViewOpenOverride"; //$NON-NLS-1$
    /** Отделяет дописанный отбор от штатных итогов — он же признак «уже дописано». */
    private static final String SCOPE_SEPARATOR = "   │   "; //$NON-NLS-1$
    private static final int SCOPE_REFRESH_MS = 300;

    /** Заслонки обновления по панелям — см. {@link #installResultChangeGate(IViewPart)}. */
    private static final Map<IViewPart, ResultChangeGate> gates = new WeakHashMap<>();
    private static final String MESSAGES_CLASS = "com._1c.g5.v8.dt.internal.ui.validation.Messages"; //$NON-NLS-1$
    private static final String PLUGIN_CLASS =
        "com._1c.g5.v8.dt.internal.ui.validation.V8UiValidationPlugin"; //$NON-NLS-1$
    private static final String CHANGE_LISTENER_CLASS =
        "com._1c.g5.v8.dt.internal.ui.validation.AbstractSetting$ChangeListener"; //$NON-NLS-1$
    /**
     * Своё короткое название вместо штатного {@code Messages.Scope} («Область
     * возникновения»): к значению режима дописывается ещё и полное имя объекта,
     * и длинный штатный заголовок в одной строке с итогами уже мешает.
     */
    private static final String SCOPE_TITLE = "Область"; //$NON-NLS-1$
    /**
     * Подсказка штатной надписи «Ошибок / Предупреждений»: счётчики идут по
     * критичности (группы дерева), а не по типу строки.
     */
    private static final String STATS_TOOLTIP =
        "Счётчики считают по критичности (группы в дереве), а не по типу проблемы.\n" //$NON-NLS-1$
            + "«Ошибок» — только группа «Ошибки конфигурации»: синтаксис, разбор, сборка метаданных.\n" //$NON-NLS-1$
            + "«Предупреждений» — остальные группы: блокирующие, критические, значительные, незначительные, тривиальные.\n"; //$NON-NLS-1$ 
    /** Режимы {@code ProblemFilters.Scope}, у которых есть конкретный источник отбора. */
    private static final String SCOPE_CURRENT_OBJECT = "CURRENT_OBJECT"; //$NON-NLS-1$
    private static final String SCOPE_CURRENT_ELEMENT = "CURRENT_ELEMENT"; //$NON-NLS-1$
    private static final String SCOPE_CURRENT_PROJECT = "CURRENT_PROJECT"; //$NON-NLS-1$
    private static final String SCOPE_SUBSYSTEM_FILTER = "SUBSYSTEM_FILTER"; //$NON-NLS-1$
    /** Режим без отбора по области — им же подписывается включённое «Показывать все». */
    private static final String SCOPE_ALL = "ALL"; //$NON-NLS-1$

    private static final String SCOPE_SELECTION_CLASS =
        "com._1c.g5.v8.dt.internal.ui.validation.ScopeSelection"; //$NON-NLS-1$
    private static final String SCOPE_ENUM_CLASS =
        "com._1c.g5.v8.dt.internal.ui.validation.ProblemFilters$Scope"; //$NON-NLS-1$
    /** Штатная команда радио «Области возникновения» (тулбар-пулдаун / меню панели). */
    private static final String NATIVE_SCOPE_COMMAND_ID =
        "com._1c.g5.v8.dt.ui.command.filtersScopeRadio"; //$NON-NLS-1$
    /** Команда включения/выключения фильтра по подсистемам в навигаторе. */
    private static final String NAVIGATOR_SUBSYSTEMS_FILTER_COMMAND_ID =
        "com._1c.g5.v8.dt.navigator.ui.filterBySubsystems"; //$NON-NLS-1$
    /** Период «дешёвой» перепроверки, что панель не сбросила нашу синтетическую область. */
    private static final int COMFORT_REASSERT_MS = 3000;

    /** Установлен ли уже {@link #installComfortScope} для панели. */
    private static final Map<IViewPart, Boolean> comfortInstalled = new WeakHashMap<>();
    /** Синтетическая {@code ScopeSelection}, наложенная нами на панель (для сверки). */
    private static final Map<IViewPart, Object> comfortSyntheticSelection = new WeakHashMap<>();
    /** Имя штатной области ({@code CURRENT_ELEMENT}/{@code CURRENT_PROJECT}/{@code ALL}), которую мы выставили. */
    private static final Map<IViewPart, String> comfortAppliedScope = new WeakHashMap<>();
    /** Проект, под который наложена область (для сверки со сменой активного проекта). */
    private static final Map<IViewPart, IProject> comfortAppliedProject = new WeakHashMap<>();
    /** Штатные имена панели по {@code MPart} / {@code MPartDescriptor} — см. {@link #stockLabel}. */
    private static final Map<Object, String> stockLabels = new WeakHashMap<>();

    private static volatile boolean installed;

    /** Регистрируется до загрузки панели: поле создаётся до первого layout и кадра. */
    public static void installWeavingHook()
    {
        CheckSelectorCreation.install();
    }

    private static final class CheckSelectorCreation implements WeavingHook
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.internal.ui.validation.lazytree.LazyProblemView"; //$NON-NLS-1$
        private static final String CALLBACK = "tormozit.problemView.created"; //$NON-NLS-1$
        private static boolean registered;

        static synchronized void install()
        {
            if (registered)
                return;
            Bundle bundle = FrameworkUtil.getBundle(ProblemViewHook.class);
            BundleContext context = bundle != null ? bundle.getBundleContext() : null;
            if (context == null)
                return;
            System.getProperties().put(CALLBACK, (java.util.function.Consumer<Object>) part ->
            {
                if (part instanceof IViewPart view)
                {
                    try
                    {
                        UpdateWaitIndicator.install(view);
                    }
                    catch (RuntimeException error)
                    {
                    }
                }
            });
            context.registerService(WeavingHook.class, new CheckSelectorCreation(), null);
            registered = true;
        }

        @Override
        public void weave(WovenClass woven)
        {
            if (!TARGET.equals(woven.getClassName()) || woven.getState() != WovenClass.TRANSFORMING)
                return;
            try
            {
                byte[] transformed = transform(woven.getBytes());
                if (transformed != null)
                    woven.setBytes(transformed);
            }
            catch (RuntimeException error)
            {
            }
        }

        private static byte[] transform(byte[] original)
        {
            ClassReader reader = new ClassReader(original);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            boolean[] touched = { false };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"createPartControl".equals(name) //$NON-NLS-1$
                        || !"(Lorg/eclipse/swt/widgets/Composite;)V".equals(descriptor)) //$NON-NLS-1$
                        return method;
                    return new MethodVisitor(Opcodes.ASM9, method)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.RETURN)
                            {
                                // Мост через JDK Consumer — существующий паттерн репозитория.
                                // У EDT нет зависимости от бандла Комфорт; кадры стека не меняются.
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                                    "()Ljava/util/Properties;", false); //$NON-NLS-1$
                                super.visitLdcInsn(CALLBACK);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer"); //$NON-NLS-1$
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", //$NON-NLS-1$ //$NON-NLS-2$
                                    "(Ljava/lang/Object;)V", true); //$NON-NLS-1$
                                touched[0] = true;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return touched[0] ? writer.toByteArray() : null;
        }
    }

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(ProblemViewHook::install);
    }

    private static void install()
    {
        if (installed)
            return;
        installed = true;

        IWorkbench workbench = PlatformUI.getWorkbench();
        applyDescriptorTitle(workbench);
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
            }
        });

        listenReplaceListFilters(workbench);
        SuppressionNotification.install(workbench);
        syncUpdateGateCommandState(ComfortSettings.isProblemViewUpdateGateEnabled());

        Debug.log("install: installed"); //$NON-NLS-1$
    }

    /**
     * Переключили «Улучшать списки» — привести панель в соответствие сразу, не
     * дожидаясь перезапуска EDT: имя вернуть штатное (или переименовать заново) и
     * снять нашу область отбора. Подпись «Область: …», подсказка итогов, заслонка
     * обновлений и индикатор неполноты списка читают флажок при каждом срабатывании и
     * подстраиваются сами.
     */
    private static void listenReplaceListFilters(IWorkbench workbench)
    {
        ComfortSettings settings = ComfortSettings.getInstance();
        if (settings == null)
            return;
        settings.getPreferenceStore().addPropertyChangeListener(event ->
        {
            if (!ComfortSettings.PREF_REPLACE_LIST_FILTERS.equals(event.getProperty()))
                return;
            Display.getDefault().asyncExec(() ->
            {
                applyDescriptorTitle(workbench);
                for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                {
                    for (IWorkbenchPage page : window.getPages())
                    {
                        for (IViewReference ref : page.getViewReferences())
                        {
                            IViewPart view = ref.getView(false);
                            if (view == null || !isProblemView(view))
                                continue;
                            applyTitle(view);
                            ClassLoader loader = view.getClass().getClassLoader();
                            Object filters = problemFilters(loader);
                            if (filters != null)
                                applyComfortScope(view, filters, loader);
                            Label status = UpdateWaitIndicator.statusLabel(view);
                            UpdateWaitIndicator.apply(view, status);
                            applyStatsTooltip(status);
                            if (view.getAdapter(TreeViewer.class) instanceof TreeViewer viewer)
                                viewer.refresh();
                        }
                    }
                }
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
                applyTitle(ref.getView(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                applyTitle(partOf(ref));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                applyTitle(partOf(ref));
            }
        });
    }

    private static IWorkbenchPart partOf(IWorkbenchPartReference ref)
    {
        return ref != null ? ref.getPart(false) : null;
    }

    /**
     * То же имя — в списке окна «Показать панель» (и в быстром доступе).
     *
     * <p>Вкладка открытой панели берёт имя из {@code MPart} (см. {@link #applyTitle}), а список
     * представлений строится не по открытым панелям, а по дескрипторам модели e4
     * ({@code MApplication.getDescriptors()}, {@code ShowViewDialog}). Пока переименован только
     * {@code MPart}, в списке остаётся штатное «Ошибки конфигурации» — одна и та же панель
     * называется по-разному в двух местах интерфейса.
     *
     * <p>Дескрипторы собираются из реестра расширений при каждом запуске, поэтому подпись
     * ставится заново на старте и никуда не сохраняется.
     */
    private static void applyDescriptorTitle(IWorkbench workbench)
    {
        MApplication application = workbench.getService(MApplication.class);
        if (application == null)
            return;
        boolean enabled = ComfortSettings.isReplaceListFiltersEnabled();
        for (MPartDescriptor descriptor : application.getDescriptors())
        {
            if (!ProblemViewMarkers.PROBLEM_VIEW_ID.equals(descriptor.getElementId()))
                continue;
            String wanted = enabled ? VIEW_TITLE : stockLabel(descriptor, descriptor.getLabel());
            if (wanted != null && !wanted.equals(descriptor.getLabel()))
            {
                descriptor.setLabel(wanted);
                Debug.log("applyDescriptorTitle: " + wanted); //$NON-NLS-1$
            }
        }
    }

    /**
     * Штатное имя панели, запомненное до первого переименования: при выключенном
     * «Улучшать списки» имя надо вернуть, а взять его больше неоткуда — в модели e4
     * уже стоит наше.
     *
     * @param owner {@code MPart} панели или её {@code MPartDescriptor}
     * @param current текущее имя (наше или ещё штатное)
     * @return штатное имя или {@code null}, если оно ни разу не наблюдалось
     */
    private static String stockLabel(Object owner, String current)
    {
        synchronized (stockLabels)
        {
            if (current != null && !VIEW_TITLE.equals(current))
                stockLabels.put(owner, current);
            return stockLabels.get(owner);
        }
    }

    /**
     * Имя панели живёт в модели e4, а не в самом {@code IViewPart}: штатный
     * {@code setPartName} у чужой панели недоступен, зато {@code MPart.setLabel}
     * меняет и заголовок вкладки, и подпись в списке представлений.
     */
    private static void applyTitle(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart view) || !isProblemView(view))
            return;
        if (ComfortSettings.resetProblemViewUpdateGateOnFirstOpen())
            syncUpdateGateCommandState(false);
        Object mpart = view.getSite().getService(MPart.class);
        if (mpart instanceof MPart model)
        {
            String wanted = ComfortSettings.isReplaceListFiltersEnabled()
                ? VIEW_TITLE : stockLabel(model, model.getLabel());
            if (wanted != null && !wanted.equals(model.getLabel()))
            {
                model.setLabel(wanted);
                Debug.log("applyTitle: " + wanted); //$NON-NLS-1$
            }
        }
        showSuppressIssueToolbarButton(view);
        Display.getDefault().asyncExec(() -> showSuppressIssueToolbarButton(view));
        installResultChangeGate(view);
        installScopeLabel(view);
        installOpenOverride(view);
        installTypeRename(view);
        installComfortScope(view);
        UpdateWaitIndicator.install(view);
        refreshComfortScope(view);
    }

    /** Открывает панель с точным отбором по коду проверки (issue 750). */
    static void showForCheck(CheckUid uid, IProject project)
    {
        if (uid == null)
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return;
        try
        {
            // Инициализируем метаданные проекта, из настроек которого вызвана команда.
            // Выбор хранит CheckUid; внутренние коды получаем для всех проектов отдельно.
            ICheckRepository checks = Global.getOsgiService(ICheckRepository.class);
            if (project != null && checks == null && !ValidationChecksFilterHook.isInternalBslCheckUid(uid))
                throw new IllegalStateException("Служба проверок недоступна"); //$NON-NLS-1$
            if (project != null && !ValidationChecksFilterHook.isInternalBslCheckUid(uid))
                checks.getShortUid(uid, project);
            IViewPart view = page.showView(ProblemViewMarkers.PROBLEM_VIEW_ID);
            UpdateWaitIndicator.install(view);
            // partOpened может прийти до создания контролов; после showView поля уже готовы.
            UpdateWaitIndicator.wrapMarkerManager(view);
            UpdateWaitIndicator.setCheckCode(view, uid);
        }
        catch (PartInitException | RuntimeException e)
        {
            ToastNotification.show("Найти проблемы", "Не удалось установить отбор: " + e.getMessage(), 8_000); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Точный отбор нужен и потребителям currentFilter(), который EDT строит заново. */
    static MarkerFilter withCheckCode(IWorkbenchPart part, MarkerFilter filter)
    {
        UpdateWaitIndicator.State state = part instanceof IViewPart view ? UpdateWaitIndicator.stateOf(view) : null;
        String checkId = state != null ? state.checkId : null;
        return checkId != null ? UpdateWaitIndicator.applyCheckCode(filter, state.checkCodes) : filter;
    }

    /**
     * EDT прячет штатную кнопку «Подавить ошибку» через visibleWhen по выделению.
     * Из-за этого меняется высота области списка при выборе и снятии выбора.
     * Кнопка остаётся на месте, а доступность команды по-прежнему определяет EDT.
     */
    private static void showSuppressIssueToolbarButton(IViewPart view)
    {
        if (view.getSite() == null)
            return;
        Object part = view.getSite().getService(MPart.class);
        if (!(part instanceof MPart model))
            return;
        MToolBar toolbar = model.getToolbar();
        if (toolbar == null)
            return;
        for (MToolBarElement element : toolbar.getChildren())
        {
            if (!(element instanceof MHandledToolItem item) || item.getCommand() == null
                || !SUPPRESS_ISSUE_COMMAND_ID.equals(item.getCommand().getElementId()))
                continue;
            if (item.getVisibleWhen() != null)
                item.setVisibleWhen(null);
            if (!item.isVisible())
                item.setVisible(true);
        }
    }

    /**
     * Панель обновляется, только когда изменился её собственный результат.
     *
     * <p>Штатно панель слушает изменения маркеров <b>всего проекта</b>
     * ({@code LazyProblemView.updateListener} у {@code IMarkerManagerV2}) и на каждое такое
     * событие перестраивает дерево и переписывает надпись с итогами. Пока по конфигурации идёт
     * проверка, коммиттер маркеров рассылает событие примерно раз в полторы секунды, и панель
     * моргает целиком, даже если под текущим отбором ничего не поменялось: дерево перерисовывается,
     * а надпись на миг теряет дописанное «Область: …».
     *
     * <p>Штатный слушатель подменяется обёрткой, и решение принимается <b>по отбору панели, а не
     * по её содержимому</b>: {@link MarkerChangeTap} знает, какие объекты попали в текущую пачку
     * коммита маркеров, и событие не передаётся только тогда, когда ни один из них не входит в
     * отбор панели. В область входят и пути {@code *.bsl} выбранных объектов: коммиттер
     * помечает uncommitted путь файла, а штатный отбор «Текущий элемент» часто отдаёт только
     * числовой {@code bmGetId} формы. Проверка стоит O(размера отбора), хранилище маркеров
     * не читается.
     *
     * <p>Сомнение всегда в пользу обновления: наблюдатель пачки не установлен, пачку не удалось
     * разобрать, отбор не снят или в нём нет объектов — событие проходит, то есть остаётся штатное
     * поведение.
     *
     * <p><b>Чего здесь больше нет и почему.</b> Прежняя редакция сверяла отпечаток результата
     * (количество и сумма хэшей маркеров под отбором). Отпечаток требовал чтения хранилища,
     * зависел от асинхронного снимка отбора и на области «Текущий элемент» всегда давал «0:0» —
     * то есть не отвечал на нужный вопрос. Отсюда росли костыли: перепроверки через 2 и 5 секунд
     * (событие могло прийти раньше публикации изменений) и аварийный клапан на минуту без
     * обновлений. Инцидент 08.09.2026: после сохранения модуля отпечаток «0:0» под отбором с
     * модулем сравнили с «0:0» под отбором без модуля, событие проглотили вместе с
     * перепроверками, и панель показала проблемы только после ручного переключения области.
     *
     * <p><b>Диагностика</b> (Журнал Комфорт, флажок «Вести журнал»): установка заслонки и
     * наблюдателя пачки, каждое решение с причиной, проекты события, сколько событий подряд не
     * передано, снятый отбор (отдельно помечается отбор «ВСЕГДА ЛОЖЬ» — его строит
     * {@code buildTreeFilter}, когда область ни во что не разрешилась).
     */
    private static void installResultChangeGate(IViewPart view)
    {
        try
        {
            if (gates.containsKey(view))
                return;
            Object manager = Global.getField(view, "markerManager"); //$NON-NLS-1$
            Object listener = Global.getField(view, "updateListener"); //$NON-NLS-1$
            if (!(manager instanceof IMarkerManagerV2 markerManager)
                || !(listener instanceof IMarkerUpdateListener stock))
            {
                String msg = "заслонка обновлений: поля панели не найдены — markerManager=" //$NON-NLS-1$
                    + className(manager) + ", updateListener=" + className(listener) //$NON-NLS-1$
                    + " (панель обновляется штатно)"; //$NON-NLS-1$
                Debug.log(msg);
                return;
            }
            installMarkerEventProbe(markerManager);
            MarkerChangeTap.get().install(markerManager);
            ResultChangeGate gate = new ResultChangeGate(view, stock);
            gates.put(view, gate);
            markerManager.removeListener(stock);
            markerManager.addListener(gate);
            gate.refreshFilterSnapshot();
            syncUpdateGateCommandState(ComfortSettings.isProblemViewUpdateGateEnabled());
            String installed = "заслонка обновлений: установлена на " + view.getClass().getName() //$NON-NLS-1$
                + ", штатный слушатель " + stock.getClass().getName() //$NON-NLS-1$
                + ", менеджер " + identity(markerManager) //$NON-NLS-1$
                + ", тумблер=" + ComfortSettings.isProblemViewUpdateGateEnabled(); //$NON-NLS-1$
            Debug.log(installed);
        }
        catch (RuntimeException e)
        {
            Debug.log("заслонка обновлений: не установлена — " + e); //$NON-NLS-1$
        }
    }

    /**
     * Тумблер тулбара: выключить заслонку — перечитать дерево по текущему отбору.
     * Включить — следующее событие снова идёт через сверку.
     */
    static void applyUpdateGateEnabled(boolean enabled)
    {
        ComfortSettings.setProblemViewUpdateGateEnabled(enabled);
        syncUpdateGateCommandState(enabled);
        if (enabled)
            return;
        for (ResultChangeGate gate : new java.util.ArrayList<>(gates.values()))
            gate.releaseHeldEvents();
    }

    static void syncUpdateGateCommandState(boolean enabled)
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        Command command = commandService.getCommand(ProblemViewUpdateGateHandler.COMMAND_ID);
        if (command == null)
            return;
        State state = command.getState(RegistryToggleState.STATE_ID);
        if (state != null)
            state.setValue(Boolean.valueOf(enabled));
        commandService.refreshElements(ProblemViewUpdateGateHandler.COMMAND_ID, null);
    }

    private static volatile boolean markerProbeInstalled;

    /**
     * Наблюдатель за событиями об изменении маркеров (issue 475): ничего не подменяет и
     * ничего не задерживает, только пишет в журнал факт события.
     *
     * <p>По журналу issue 475 видно, что в {@link ResultChangeGate} за семь минут не пришло
     * ни одного события — при том что в модуле лежала синтаксическая ошибка, а перепроверка
     * объекта отчиталась «0 ошибок». Наблюдатель отвечает на два вопроса: рассылает ли события
     * хоть кто-нибудь и тот ли это экземпляр менеджера маркеров, который держит панель
     * (менеджеры — {@code IManagedService}, экземпляр сервиса и экземпляр панели могут
     * оказаться разными, и тогда заслонка слушает не тот канал).
     *
     * @param viewManager менеджер маркеров, взятый из поля панели
     */
    private static void installMarkerEventProbe(Object viewManager)
    {
        if (markerProbeInstalled)
            return;
        markerProbeInstalled = true;
        try
        {
            IMarkerManagerV2 serviceV2 = Global.getOsgiService(IMarkerManagerV2.class);
            IMarkerManager serviceV1 = Global.getOsgiService(IMarkerManager.class);
            String probe = "наблюдатель маркеров: менеджер панели " + identity(viewManager) //$NON-NLS-1$
                + ", сервис IMarkerManagerV2 " + identity(serviceV2) //$NON-NLS-1$
                + (serviceV2 == viewManager ? " (тот же экземпляр)" : " (ДРУГОЙ экземпляр)") //$NON-NLS-1$ //$NON-NLS-2$
                + ", сервис IMarkerManager " + identity(serviceV1); //$NON-NLS-1$
            Debug.log(probe);
            if (serviceV2 != null && serviceV2 != viewManager)
                serviceV2.addListener(new MarkerEventProbe("сервис V2")); //$NON-NLS-1$
            if (serviceV1 != null)
                serviceV1.addListener(new MarkerEventProbe("сервис V1")); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError e)
        {
            Global.logError(Debug.TAG, "наблюдатель маркеров: не установлен", e); //$NON-NLS-1$
        }
    }

    /** Класс и хэш объекта для журнала: по хэшу видно, один это экземпляр или разные. */
    private static String identity(Object value)
    {
        return value == null ? "нет" //$NON-NLS-1$
            : value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)); //$NON-NLS-1$
    }

    /** См. {@link #installMarkerEventProbe(Object)}. */
    private static final class MarkerEventProbe implements IMarkerUpdateListener
    {
        private final String channel;

        private int count;

        MarkerEventProbe(String channel)
        {
            this.channel = channel;
        }

        @Override
        public void handleMarkersChanged(MarkersChangedEvent event)
        {
            count++;
            String msg = "наблюдатель маркеров: событие " + count + " (" + channel + "), проекты " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ResultChangeGate.changedProjectNames(event);
            Debug.log(msg);
        }
    }

    /**
     * Что именно изменилось в текущей пачке коммита маркеров — сведения, которых нет в самом
     * событии ({@code MarkersChangedEvent} несёт только список проектов).
     *
     * <p><b>Откуда берутся.</b> Рассылает события {@code Committer}
     * ({@code com._1c.g5.v8.dt.internal.validation.marker}) — один на менеджер маркеров. Он копит
     * записи в очереди {@code records} и раз в такт (1500 мс, {@code e1c.dt.marker.commitIntervalMs})
     * забирает их через {@code poll()}, коммитит и рассылает событие. Самих маркеров в записи нет
     * ({@code commitLater} кладёт {@code COMMIT} с {@code markers = null}), но есть хранилище
     * проекта {@code ProjectMarkerStorage}, а у него — набор идентификаторов объектов, которые
     * уходят в этот коммит ({@code markUncommitted}, публичный {@code isUncommitted}).
     *
     * <p><b>Как вклиниваемся.</b> Обёртка очереди подключает наблюдение за наборами
     * {@code uncommittedIds} до помещения записи в очередь: {@code commitLater()} вызывает
     * {@code markUncommitted()} уже после {@code offer()}. Добавленные идентификаторы сохраняем
     * отдельно до сверки события; штатный {@code commit()} может очистить наборы раньше
     * {@code poll()}. Штатные операции наборов и очереди остаются у их исходных экземпляров.
     * При сверке сравниваем сохранённые идентификаторы с отбором, маркеры из индекса не читаем.
     *
     * <p><b>Почему так, а не отпечатком.</b> Отпечаток (количество и сумма хэшей маркеров под
     * отбором) требовал чтения хранилища, зависел от снимка отбора и не отвечал на нужный вопрос:
     * под отбором «Текущий элемент» он всегда выходил «0:0» и не менялся ни при появлении, ни при
     * исчезновении проблем модуля. На нём панель и застревала со старым списком.
     *
     * <p>Сомнение всегда в пользу обновления: если пачку не удалось разобрать, отбор не снят или
     * в нём нет объектов — событие проходит.
     */
    private static final class MarkerChangeTap
    {
        /** Класс отправителя событий; поле с его экземпляром ищем по имени класса. */
        private static final String COMMITTER_CLASS_SUFFIX = ".Committer"; //$NON-NLS-1$

        private static final String RECORDS_FIELD = "records"; //$NON-NLS-1$

        private static final String RECORD_STORAGE_FIELD = "storage"; //$NON-NLS-1$

        private static final String GET_PROJECT_NAME_METHOD = "getProjectName"; //$NON-NLS-1$

        /** Идентификаторы объектов отбора: больше — считаем отбор слишком широким и не решаем. */
        private static final int MAX_SCOPE_IDS = 200;

        /** Сколько последних снимков отбора учитывать — см. {@link #setScope}. */
        private static final int SCOPE_HISTORY = 3;

        /** Последние снимки отбора; область наблюдателя — их объединение. */
        private final java.util.ArrayDeque<Snapshot> scopeHistory = new java.util.ArrayDeque<>();

        /**
         * Путь модуля ({@code Module.bsl}) и числовой {@code bmGetId} одной формы
         * приходят в отборе вместе, а в следующем снимке путь часто пропадает.
         * Коммиттер при этом помечает uncommitted именно путь — без этой связи
         * {@code isUncommitted(Long)} даёт false и панель пропускает обновление.
         */
        private final java.util.Map<Object, java.util.Set<Object>> relatedIds = new java.util.HashMap<>();

        private static final MarkerChangeTap INSTANCE = new MarkerChangeTap();

        /** Идентификаторы объектов из отбора панели; пусто — решать не по чему. */
        private volatile Object[] scopeIds = new Object[0];

        /**
         * Проекты отбора панели. Идентификаторы объектов ({@code bmGetId}) уникальны только внутри
         * проекта, поэтому запись чужого проекта с тем же числом дала бы ложное «наше» и лишнее
         * обновление; пусто — проект не ограничиваем.
         */
        private volatile java.util.Set<String> scopeProjects = java.util.Set.of();

        /** Наблюдатели хранилищ не удерживают закрытые проекты. */
        private final Map<Object, StorageJournal> storageJournals = new java.util.WeakHashMap<>();

        /** Копия id привязана к записи, а не ко всему хранилищу или интервалу таймера. */
        private final Map<Object, CommitChange> recordChanges = new java.util.WeakHashMap<>();

        /** Данные одного решения забираются вместе, без разрыва между счётчиками и флагами. */
        private final Object batchLock = new Object();

        private final Set<CommitChange> batchChanges = new java.util.LinkedHashSet<>();

        private int inspected;

        /** Пачку разобрать не удалось — что изменилось, неизвестно. */
        private boolean unknown;

        private volatile boolean installed;

        static MarkerChangeTap get()
        {
            return INSTANCE;
        }

        /** Подменяет очередь коммиттера обёрткой. Повторные вызовы дёшевы. */
        synchronized void install(Object markerManager)
        {
            if (installed)
                return;
            try
            {
                // Панель держит службу через прокси peaberry — у него нет ни полей, ни коммиттера
                Object owner = Global.unwrapServiceProxy(markerManager);
                Object committer = fieldByClassSuffix(owner, COMMITTER_CLASS_SUFFIX);
                if (committer == null)
                {
                    // Экземпляр панели и экземпляр службы могут быть разными, коммиттер — один
                    Object service = Global.unwrapServiceProxy(Global.getOsgiService(IMarkerManagerV2.class));
                    if (service != null && service != owner)
                    {
                        committer = fieldByClassSuffix(service, COMMITTER_CLASS_SUFFIX);
                        if (committer != null)
                            owner = service;
                    }
                }
                if (committer == null)
                {
                    String msg = "наблюдатель пачки: коммиттер не найден ни у " + className(markerManager) //$NON-NLS-1$
                        + ", ни у службы из реестра"; //$NON-NLS-1$
                    Debug.log(msg);
                    return;
                }
                Debug.log("наблюдатель пачки: коммиттер найден у " + className(owner)); //$NON-NLS-1$
                Object queue = Global.getField(committer, RECORDS_FIELD);
                if (!(queue instanceof java.util.Queue<?> raw))
                {
                    String msg = "наблюдатель пачки: поле " + RECORDS_FIELD + " не очередь"; //$NON-NLS-1$ //$NON-NLS-2$
                    Debug.log(msg);
                    return;
                }
                if (raw instanceof ObservingQueue)
                {
                    installed = true;
                    return;
                }
                @SuppressWarnings("unchecked")
                java.util.Queue<Object> delegate = (java.util.Queue<Object>)raw;
                java.lang.reflect.Field field = findField(committer.getClass(), RECORDS_FIELD);
                if (field == null)
                    return;
                field.setAccessible(true);
                field.set(committer, new ObservingQueue(delegate));
                for (Object record : delegate)
                    watchStorage(Global.getField(record, RECORD_STORAGE_FIELD));
                installed = true;
                Debug.log("наблюдатель пачки: очередь коммиттера подменена"); //$NON-NLS-1$
            }
            catch (Exception | LinkageError e)
            {
                Debug.log("наблюдатель пачки: не установлен — " + e); //$NON-NLS-1$
            }
        }

        /**
         * Повторная попытка установки: при открытии панели служба маркеров может быть ещё не
         * поднята, а другого случая вклиниться потом не представится.
         */
        void ensureInstalled()
        {
            if (!installed)
                install(Global.getOsgiService(IMarkerManagerV2.class));
        }

        /**
         * Идентификаторы объектов из отбора панели — по ним и спрашиваем хранилище.
         *
         * <p>Берётся <b>объединение последних {@value #SCOPE_HISTORY} снимков</b>, а не только
         * последний. Панель отдаёт свой отбор то полным, то усечённым: сразу после доставки
         * события она ещё перестраивается, текущий элемент не доразрешён, и из отбора пропадает
         * модуль ({@code OBJECT_ID + путь Module.bsl} → только {@code OBJECT_ID}). Решать по
         * такому снимку нельзя: коммиттер помечает uncommitted путь файла, {@code isUncommitted}
         * по числовому id даёт false, и панель пропускает обновление (журнал 10.09.2026).
         * Число и путь из одного снимка запоминаются и подставляются, пока число ещё в
         * объединении. Объединение смещает ошибку в безопасную сторону — в худшем случае
         * лишнее обновление.
         *
         * <p>Отбор «Текущий элемент» часто содержит только {@code Long} формы, а коммиттер
         * помечает путь {@code Module.bsl}. Связь из снимка тогда пустая: панель эти два
         * идентификатора в одном фильтре не отдаёт. Пути модулей выбранных объектов
         * дописываются из {@code scopeSelection} — тот же файл, что в {@code uncommittedIds}.
         */
        void setScope(MarkerFilter filter, java.util.Set<Object> extraIds)
        {
            java.util.LinkedHashSet<Object> ids = new java.util.LinkedHashSet<>();
            for (MarkerIndex index : new MarkerIndex[] { MarkerIndex.OBJECT_ID, MarkerIndex.TOP_OBJECT_ID })
            {
                java.util.Set<Object> values = filter.getValues(index);
                if (values == null)
                    continue;
                for (Object value : values)
                {
                    // -1 приходит из отбора «ВСЕГДА ЛОЖЬ»: такого объекта нет, совпасть не с чем
                    if (!Long.valueOf(-1L).equals(value))
                        ids.add(value);
                }
            }
            if (extraIds != null)
                ids.addAll(extraIds);
            // Проекты — из той же истории, что и идентификаторы: иначе идентификаторы прошлого
            // снимка остались бы в области, а проект уже не совпал бы, и изменения были бы
            // отброшены как чужие
            Snapshot snapshot = new Snapshot(ids, projectNames(filter));
            synchronized (scopeHistory)
            {
                scopeHistory.addLast(snapshot);
                while (scopeHistory.size() > SCOPE_HISTORY)
                    scopeHistory.removeFirst();
                java.util.LinkedHashSet<Object> unionIds = new java.util.LinkedHashSet<>();
                java.util.LinkedHashSet<String> unionProjects = new java.util.LinkedHashSet<>();
                for (Snapshot item : scopeHistory)
                {
                    unionIds.addAll(item.ids());
                    unionProjects.addAll(item.projects());
                }
                rememberRelations(ids);
                java.util.LinkedHashSet<Object> expanded = new java.util.LinkedHashSet<>(unionIds);
                for (Object id : unionIds)
                {
                    java.util.Set<Object> related = relatedIds.get(id);
                    if (related != null)
                        expanded.addAll(related);
                }
                int unionSize = expanded.size();
                scopeIds = unionSize > MAX_SCOPE_IDS ? new Object[0] : expanded.toArray();
                scopeProjects = unionProjects;
            }
        }

        /** Запоминает, какие пути модуля шли в одном снимке с какими числовыми id. */
        private void rememberRelations(java.util.Set<Object> snapshotIds)
        {
            java.util.ArrayList<Object> numbers = new java.util.ArrayList<>();
            java.util.ArrayList<Object> paths = new java.util.ArrayList<>();
            for (Object id : snapshotIds)
            {
                if (id instanceof String)
                    paths.add(id);
                else if (id instanceof Long || id instanceof Integer)
                    numbers.add(id);
            }
            if (numbers.isEmpty() || paths.isEmpty())
                return;
            for (Object number : numbers)
                relatedIds.computeIfAbsent(number, key -> new java.util.LinkedHashSet<>()).addAll(paths);
            for (Object path : paths)
                relatedIds.computeIfAbsent(path, key -> new java.util.LinkedHashSet<>()).addAll(numbers);
        }

        /** Снимок отбора панели: объекты и проекты снимаются и учитываются вместе. */
        private record Snapshot(java.util.Set<Object> ids, java.util.Set<String> projects)
        {
        }

        /** Забирает решение по накопленной пачке и начинает копить заново. */
        Verdict take()
        {
            int records;
            boolean lost;
            Set<CommitChange> captured;
            synchronized (batchLock)
            {
                records = inspected;
                inspected = 0;
                lost = unknown;
                unknown = false;
                captured = new java.util.LinkedHashSet<>(batchChanges);
                batchChanges.clear();
            }
            Object[] ids = scopeIds;
            Set<String> projects = scopeProjects;
            boolean sawHit = false;
            for (CommitChange change : captured)
            {
                Set<Object> changes = change.ids;
                boolean ownProject = projects.isEmpty() || projects.contains(change.project);
                if (!ownProject)
                    continue;
                if (changes == null || changes.isEmpty())
                {
                    lost = true;
                    continue;
                }
                if (changes.contains(Long.valueOf(-1L)))
                    sawHit = true; // EDT помечает -1 изменения, относящиеся ко всему проекту.
                for (Object id : ids)
                    sawHit |= changes.contains(id);
            }

            if (!installed)
                return finishTake(false, false, "наблюдатель пачки не установлен"); //$NON-NLS-1$
            if (lost)
                return finishTake(false, sawHit, "в пачке есть общее уведомление или нет сведений об объектах"); //$NON-NLS-1$
            if (ids.length == 0)
                return finishTake(false, false, "в отборе панели нет объектов — решать не по чему"); //$NON-NLS-1$
            if (records == 0)
                return finishTake(false, false, "пачка пуста — сведений об изменениях нет"); //$NON-NLS-1$
            if (sawHit)
                return finishTake(false, true, "в пачке есть объект из отбора панели"); //$NON-NLS-1$
            return finishTake(true, false, "ни одна из " + records //$NON-NLS-1$
                + " записей пачки не тронула объекты отбора: " + previewIds(ids)); //$NON-NLS-1$
        }

        private static Verdict finishTake(boolean skip, boolean touched, String reason)
        {
            return new Verdict(skip, touched, reason);
        }

        /** Область не анализируется: не удерживать копии id до будущего включения отбора. */
        void discard()
        {
            synchronized (batchLock)
            {
                batchChanges.clear();
                inspected = 0;
                unknown = false;
            }
        }

        /**
         * Пути {@code *.bsl} выбранных в панели объектов. Коммиттер помечает
         * uncommitted именно их, а отбор панели — числовой {@code bmGetId}.
         */
        static java.util.Set<Object> modulePathsOf(IViewPart view)
        {
            java.util.LinkedHashSet<Object> paths = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<Object> numbers = new java.util.LinkedHashSet<>();
            Object selection = Global.getField(view, "scopeSelection"); //$NON-NLS-1$
            Object objects = selection == null ? null : Global.invoke(selection, "getSelectedObjects"); //$NON-NLS-1$
            if (objects instanceof Map<?, ?> byProject)
            {
                for (Object perProject : byProject.values())
                {
                    if (!(perProject instanceof Collection<?> items))
                        continue;
                    for (Object item : items)
                    {
                        if (item instanceof IBmObject bm)
                            numbers.add(Long.valueOf(bm.bmGetId()));
                        if (item instanceof EObject object)
                            addBslPaths(NavigatorResourceResolver.resolve(object), paths);
                    }
                }
            }
            UpdateWaitIndicator.rememberLiveModuleIds(view, numbers, paths);
            return paths;
        }

        private static void addBslPaths(IResource resource, java.util.Set<Object> paths)
        {
            if (resource instanceof IFile file)
            {
                if ("bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                    paths.add(file.getFullPath().toString());
                resource = file.getParent();
            }
            if (!(resource instanceof IContainer folder) || !folder.isAccessible())
                return;
            try
            {
                folder.accept(member ->
                {
                    if (member instanceof IFile file && "bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                        paths.add(file.getFullPath().toString());
                    return paths.size() < MAX_SCOPE_IDS;
                });
            }
            catch (CoreException ignored)
            {
                // без путей заслонка не узнает модуль — останется только числовой id
            }
        }

        /** Имена проектов отбора: в значениях {@code PROJECT} лежат сами {@link IProject}. */
        private static java.util.Set<String> projectNames(MarkerFilter filter)
        {
            java.util.Set<Object> values = filter.getValues(MarkerIndex.PROJECT);
            if (values == null || values.isEmpty())
                return java.util.Set.of();
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (Object value : values)
            {
                if (value instanceof IProject project)
                    names.add(project.getName());
            }
            return names;
        }

        /**
         * Идентификаторы отбора для журнала: по ним видно, в той ли форме они, что и в хранилище
         * (у одного и того же модуля это либо число {@code bmGetId}, либо путь к файлу).
         */
        private static String previewIds(Object[] ids)
        {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < ids.length && i < 8; i++)
                text.append(i == 0 ? "" : ", ").append(describeId(ids[i])); //$NON-NLS-1$ //$NON-NLS-2$
            if (ids.length > 8)
                text.append(", … всего ").append(ids.length); //$NON-NLS-1$
            return text.toString();
        }

        private static String describeId(Object id)
        {
            if (id == null)
                return "null"; //$NON-NLS-1$
            return id.getClass().getSimpleName() + ":" + id; //$NON-NLS-1$
        }

        /**
         * Подключает сохранение идентификаторов до markUncommitted(). В EDT оба элемента
         * uncommittedIds создаются в конструкторе как Set и затем только читаются/очищаются.
         * Наблюдатель сохраняет addAll даже при повторном добавлении уже существующих id.
         */
        private StorageJournal watchStorage(Object storage)
        {
            if (storage == null)
                return null;
            synchronized (storageJournals)
            {
                StorageJournal existing = storageJournals.get(storage);
                if (existing != null)
                    return existing;
                Object raw = Global.getField(storage, "uncommittedIds"); //$NON-NLS-1$
                Object name = Global.invoke(storage, GET_PROJECT_NAME_METHOD);
                if (!(raw instanceof Set<?>[] stores) || !(name instanceof String project))
                    return null;
                for (Set<?> store : stores)
                    if (store == null)
                        return null;
                StorageJournal journal = new StorageJournal(project);
                for (int i = 0; i < stores.length; i++)
                {
                    @SuppressWarnings("unchecked")
                    Set<Object> delegate = (Set<Object>)stores[i];
                    stores[i] = new ObservingIds(delegate, journal);
                }
                storageJournals.put(storage, journal);
                return journal;
            }
        }

        /**
         * commitLater() в том же потоке после offer(COMMIT) вызывает markUncommitted(),
         * который делает addAll(ids). Контекст потока связывает эти два вызова, даже если
         * коммиттер успеет забрать запись до addAll. CHECK_DUPLICATES приходит уже после них.
         */
        private void prepareRecord(Object record)
        {
            Object storage = Global.getField(record, RECORD_STORAGE_FIELD);
            StorageJournal journal = watchStorage(storage);
            Object rawType = Global.getField(record, "type"); //$NON-NLS-1$
            if (journal != null && rawType instanceof Enum<?> type && "COMMIT".equals(type.name())) //$NON-NLS-1$
            {
                CommitChange change = new CommitChange(journal.project);
                synchronized (recordChanges)
                {
                    recordChanges.put(record, change);
                }
                journal.nextCommit.set(change);
            }
        }

        /** Осматривает тип записи; идентификаторы берём из журнала добавлений, а не после clear(). */
        private void inspect(Object record)
        {
            Object storage = Global.getField(record, RECORD_STORAGE_FIELD);
            Object recordType = Global.getField(record, "type"); //$NON-NLS-1$
            try
            {
                // Этот тип записи нужен только штатной диагностике дубликатов. Он не
                // коммитит маркеры и не добавляет проект в событие обновления.
                if (recordType instanceof Enum<?> type && "CHECK_DUPLICATES".equals(type.name())) //$NON-NLS-1$
                    return;
                Object project = storage == null ? null : Global.invoke(storage, GET_PROJECT_NAME_METHOD);
                CommitChange change;
                synchronized (recordChanges)
                {
                    change = recordChanges.remove(record);
                }
                synchronized (batchLock)
                {
                    inspected++;
                    if (!(project instanceof String name))
                    {
                        unknown = true;
                        return;
                    }
                    if (change != null)
                        batchChanges.add(change);
                    if (!scopeProjects.isEmpty() && !scopeProjects.contains(name))
                        return;
                    if (change == null)
                    {
                        // NOTIFY сообщает только о проекте. Для COMMIT без подключённого
                        // наблюдателя (например, уже стоявшего в очереди) данных тоже нет.
                        unknown = true;
                    }
                }
            }
            catch (Exception | LinkageError e)
            {
                synchronized (batchLock)
                {
                    unknown = true;
                }
                Debug.log("наблюдатель пачки: запись не разобрана — " + e); //$NON-NLS-1$
            }
        }

        /** Полная копия идентификаторов одной записи COMMIT, не зависящая от clear(). */
        private static final class CommitChange
        {
            final String project;

            volatile Set<Object> ids;

            CommitChange(String project)
            {
                this.project = project;
            }
        }

        /** Контекст производителя записи; не смешивает параллельные проверки разных потоков. */
        private static final class StorageJournal
        {
            final String project;

            final ThreadLocal<CommitChange> nextCommit = new ThreadLocal<>();

            StorageJournal(String project)
            {
                this.project = project;
            }

            void remember(Collection<?> ids)
            {
                CommitChange change = nextCommit.get();
                nextCommit.remove();
                if (change != null)
                    change.ids = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(ids));
            }
        }

        /** Все штатные операции идут в исходный concurrent Set; добавления ещё и запоминаются. */
        private static final class ObservingIds extends java.util.AbstractSet<Object>
        {
            private final Set<Object> delegate;

            private final StorageJournal journal;

            ObservingIds(Set<Object> delegate, StorageJournal journal)
            {
                this.delegate = delegate;
                this.journal = journal;
            }

            @Override
            public boolean add(Object id)
            {
                boolean result = delegate.add(id);
                journal.remember(java.util.Collections.singleton(id));
                return result;
            }

            @Override
            public boolean addAll(Collection<?> ids)
            {
                boolean result = delegate.addAll(ids);
                journal.remember(ids);
                return result;
            }

            @Override
            public boolean contains(Object id)
            {
                return delegate.contains(id);
            }

            @Override
            public boolean remove(Object id)
            {
                return delegate.remove(id);
            }

            @Override
            public void clear()
            {
                delegate.clear();
            }

            @Override
            public java.util.Iterator<Object> iterator()
            {
                return delegate.iterator();
            }

            @Override
            public int size()
            {
                return delegate.size();
            }
        }

        /** Первое поле объекта, тип которого оканчивается на {@code suffix}. */
        private static Object fieldByClassSuffix(Object owner, String suffix)
            throws ReflectiveOperationException
        {
            for (Class<?> type = owner.getClass(); type != null && type != Object.class; type =
                type.getSuperclass())
            {
                for (java.lang.reflect.Field field : type.getDeclaredFields())
                {
                    if (!field.getType().getName().endsWith(suffix))
                        continue;
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null)
                        return value;
                }
            }
            return null;
        }

        private static java.lang.reflect.Field findField(Class<?> type, String name)
        {
            for (Class<?> current = type; current != null && current != Object.class; current =
                current.getSuperclass())
            {
                try
                {
                    return current.getDeclaredField(name);
                }
                catch (NoSuchFieldException ignored)
                {
                    // ищем дальше по иерархии
                }
            }
            return null;
        }

        /** Решение по пачке: передавать событие панели или нет. */
        private record Verdict(boolean skip, boolean touched, String reason)
        {
        }

        /**
         * Очередь записей коммиттера: работает как исходная, но на выдаче записи успевает
         * осмотреть её. Коммиттер разбирает очередь только через {@code poll()}.
         */
        private static final class ObservingQueue
            extends java.util.AbstractQueue<Object>
        {
            private final java.util.Queue<Object> delegate;

            ObservingQueue(java.util.Queue<Object> delegate)
            {
                this.delegate = delegate;
            }

            @Override
            public boolean offer(Object record)
            {
                try
                {
                    INSTANCE.prepareRecord(record);
                }
                catch (RuntimeException | LinkageError ignored)
                {
                    // Не мешаем штатной очереди при недоступности наблюдателя.
                }
                return delegate.offer(record);
            }

            @Override
            public Object poll()
            {
                Object record = delegate.poll();
                if (record != null)
                    INSTANCE.inspect(record);
                return record;
            }

            @Override
            public Object peek()
            {
                return delegate.peek();
            }

            @Override
            public java.util.Iterator<Object> iterator()
            {
                return delegate.iterator();
            }

            @Override
            public int size()
            {
                return delegate.size();
            }
        }
    }

    /** См. {@link #installResultChangeGate(IViewPart)}. */
    private static final class ResultChangeGate implements IMarkerUpdateListener
    {
        /** Пауза перед решением: события коммиттера идут пачками, решать на каждое незачем. */
        private static final int EVALUATE_DELAY_MS = 100;

        /** Как часто спрашивать, закончились ли проверки текущего проекта. */
        private static final int SOURCE_IDLE_POLL_MS = 400;

        /**
         * После {@code isIdle()} ещё один такт: следующая пачка маркеров часто
         * приходит через ~1 с. Не держим спиннер десятки секунд на индекс Lucene.
         */
        private static final int SOURCE_IDLE_SETTLE_MS = 400;

        /** Сколько символов описания отбора писать в журнал. */
        private static final int FILTER_LOG_LIMIT = 400;

        private final IViewPart view;

        private final IMarkerUpdateListener stock;

        /** Отбор панели, снятый в UI-потоке: в потоке события его строить нельзя. */
        private volatile MarkerFilter filterSnapshot;

        /** При отборе по подсистемам не определяем принадлежность изменённых объектов. */
        private volatile boolean subsystemFilterActive = true;

        /** «Показывать все» снимает отбор по области, поэтому анализировать принадлежность нельзя. */
        private volatile boolean showAllActive = true;

        /** В этих областях события объединяем, но не делим на «свои» и «чужие». */
        private boolean skipOwnershipAnalysis()
        {
            return showAllActive || subsystemFilterActive
                || ProblemViewComfortScope.mode() != ProblemViewComfortScope.Mode.NONE;
        }

        /**
         * Последнее событие, ожидающее сверки. Сверка его забирает: иначе поле остаётся
         * заполненным навсегда и каждое следующее событие выглядит как вытеснившее несверенное.
         */
        private final java.util.concurrent.atomic.AtomicReference<MarkersChangedEvent> pending =
            new java.util.concurrent.atomic.AtomicReference<>();

        /** Диагностика: описание снятого отбора и когда он снят. */
        private volatile String filterDescription = "нет"; //$NON-NLS-1$

        private volatile long filterSnapshotAt;

        /** Диагностика: сколько событий подряд не дошло до панели. */
        private volatile int swallowedInRow;

        /** Диагностика: сколько всего событий пришло и сколько дошло до панели. */
        private volatile int eventCount;

        private volatile int passedCount;

        /** Диагностика: когда событие последний раз доходило до панели. */
        private volatile long lastPassedAt;

        /** Решение принимается в своей задаче: событие приходит в потоке коммиттера маркеров. */
        private final Job evaluateJob;

        /** Спиннер, пока проверки ещё считают объекты текущей области. */
        private final Job sourceIdleJob;

        /** Когда {@code isIdle()} стал true; 0 — сейчас не idle. */
        private volatile long sourceIdleSince;

        ResultChangeGate(IViewPart view, IMarkerUpdateListener stock)
        {
            this.view = view;
            this.stock = stock;
            // Приведение обязательно: Job.create перегружен под ICoreRunnable и IJobFunction,
            // а лямбда без результата подходит обеим
            this.evaluateJob = Job.create("Комфорт: сверка результата панели проблем", //$NON-NLS-1$
                (ICoreRunnable)monitor -> evaluate());
            this.evaluateJob.setSystem(true);
            this.sourceIdleJob = Job.create("Комфорт: ожидание расчёта проверок панели проблем", //$NON-NLS-1$
                (ICoreRunnable)monitor -> retryWhenSourceIdle());
            this.sourceIdleJob.setSystem(true);
        }

        @Override
        public void handleMarkersChanged(MarkersChangedEvent event)
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled()
                || !ComfortSettings.isProblemViewUpdateGateEnabled())
            {
                MarkerChangeTap.get().discard();
                stock.handleMarkersChanged(event);
                return;
            }
            // Считать отпечаток прямо здесь нельзя: это поток коммиттера маркеров, он в этот
            // момент держит хранилище — читать его отсюда и задерживать коммит одинаково плохо
            eventCount++;
            MarkersChangedEvent previous = pending.getAndSet(event);
            if (previous != null)
            {
                // Пачка схлопывается в одну сверку: проекты вытесненного события в отпечаток
                // уже не попадут, и его изменения до панели могут не дойти вовсе
                Debug.log("заслонка обновлений: событие " + eventCount //$NON-NLS-1$
                    + " вытеснило несверенное предыдущее (проекты " //$NON-NLS-1$
                    + changedProjectNames(previous) + ")"); //$NON-NLS-1$
            }
            evaluateJob.cancel();
            evaluateJob.schedule(EVALUATE_DELAY_MS);
        }

        /**
         * Решение по событию: обновлять панель или нет.
         *
         * <p>Ничего не читает из хранилища маркеров коммиттера. Всё, что нужно для отсечения
         * чужих объектов, уже известно {@link MarkerChangeTap}. Если ни один из них не входит в
         * отбор панели — список от этой пачки не изменится, и обновлять нечего. Своё — сразу
         * панели. Спиннер горит, пока проверки этой области ещё считают: список неполный.
         * Индекс Lucene по таймауту не ждём.
         */
        private void evaluate()
        {
            MarkersChangedEvent event = pending.getAndSet(null);
            if (event == null)
                return;
            try
            {
                if (skipOwnershipAnalysis())
                {
                    MarkerChangeTap.get().discard();
                    applySourceWait(!isSourceIdle(event, filterSnapshot));
                    deliver(event, "без анализа «свой/чужой»"); //$NON-NLS-1$
                    return;
                }
                MarkerChangeTap.get().ensureInstalled();
                MarkerChangeTap.Verdict verdict = MarkerChangeTap.get().take();
                if (verdict.skip())
                {
                    swallowedInRow++;
                    // Изменения не касаются объектов отбора — списку меняться не с чего, ждать
                    // нечего. Раньше индикатор снимался только по общему isIdle() проекта, и на
                    // расширенной проверке всей конфигурации он висел минутами при полном списке
                    applySourceWait(false);
                    String msg ="заслонка обновлений: событие " + eventCount + " (проекты " //$NON-NLS-1$ //$NON-NLS-2$
                        + changedProjectNames(event) + ") не передано — " + verdict.reason() //$NON-NLS-1$
                        + ", подряд не передано " + swallowedInRow //$NON-NLS-1$
                        + ", отбор снят " + sinceText(filterSnapshotAt) + ": " + filterDescription; //$NON-NLS-1$
                    Debug.log(msg);
                    return;
                }
                boolean sourceIdle = isSourceIdle(event, filterSnapshot);
                applySourceWait(!sourceIdle);
                deliver(event, verdict.reason());
            }
            catch (Throwable t)
            {
                // Ошибка здесь означает, что событие до панели не дошло и уже не дойдёт:
                // повторного события про это изменение маркеров не будет
                Global.logError(Debug.TAG, "заслонка обновлений: решение сорвалось, событие " //$NON-NLS-1$
                    + eventCount + " потеряно", t); //$NON-NLS-1$
                throw t;
            }
        }

        private boolean isSourceIdle(MarkersChangedEvent event, MarkerFilter snapshot)
        {
            try
            {
                IProject project = firstChangedProject(event);
                if (project == null && snapshot != null)
                {
                    Set<Object> values = snapshot.getValues(MarkerIndex.PROJECT);
                    if (values != null)
                    {
                        for (Object value : values)
                        {
                            if (value instanceof IProject found)
                            {
                                project = found;
                                break;
                            }
                        }
                    }
                }
                if (project == null)
                    return true;
                IDerivedDataManagerProvider provider =
                    Global.getOsgiService(IDerivedDataManagerProvider.class);
                IDerivedDataManager manager = provider != null ? provider.get(project) : null;
                return manager == null || manager.isIdle();
            }
            catch (RuntimeException | LinkageError ignored)
            {
                return true;
            }
        }

        private static IProject firstChangedProject(MarkersChangedEvent event)
        {
            Collection<IProject> changed = event != null ? event.getChangedProjects() : null;
            if (changed == null)
                return null;
            for (IProject project : changed)
            {
                if (project != null && project.isAccessible())
                    return project;
            }
            return null;
        }

        private void applySourceWait(boolean busy)
        {
            if (busy)
                sourceIdleSince = 0;
            UpdateWaitIndicator.setWaitingForIndex(view, busy);
            if (busy)
                sourceIdleJob.schedule(SOURCE_IDLE_POLL_MS);
            else
                sourceIdleJob.cancel();
        }

        private void retryWhenSourceIdle()
        {
            if (view.getSite() == null)
            {
                UpdateWaitIndicator.setWaitingForIndex(view, false);
                return;
            }
            if (!isSourceIdle(null, filterSnapshot))
            {
                sourceIdleSince = 0;
                sourceIdleJob.schedule(SOURCE_IDLE_POLL_MS);
                return;
            }
            long now = System.currentTimeMillis();
            if (sourceIdleSince == 0)
                sourceIdleSince = now;
            long waited = now - sourceIdleSince;
            if (waited < SOURCE_IDLE_SETTLE_MS)
            {
                sourceIdleJob.schedule(SOURCE_IDLE_POLL_MS);
                return;
            }
            UpdateWaitIndicator.setWaitingForIndex(view, false);
        }

        /** Тумблер выключили: перечитать дерево, включая уже отброшенные события. */
        synchronized void releaseHeldEvents()
        {
            if (view.getSite() == null)
                return;
            evaluateJob.cancel();
            sourceIdleJob.cancel();
            UpdateWaitIndicator.setWaitingForIndex(view, false);
            MarkersChangedEvent held = pending.getAndSet(null);
            if (stock instanceof DelayedMarkerUpdateListener listener)
            {
                // Так EDT обновляет панель при смене её отбора: новая задача заново читает
                // маркеры по getMarkerFilter(), даже если отложенного события уже нет.
                listener.scheduleUpdateJob(true);
                refreshFilterSnapshot();
                Debug.log("заслонка обновлений: тумблер выключен, дерево перечитывается по отбору"); //$NON-NLS-1$
            }
            else if (held != null)
            {
                deliver(held, "тумблер «Фильтр обновлений» выключен"); //$NON-NLS-1$
            }
        }

        /** Передаёт событие штатному слушателю панели. */
        private synchronized void deliver(MarkersChangedEvent event, String reason)
        {
            swallowedInRow = 0;
            passedCount++;
            lastPassedAt = System.currentTimeMillis();
            String msg = "заслонка обновлений: событие " + eventCount + " (проекты " //$NON-NLS-1$ //$NON-NLS-2$
                + changedProjectNames(event) + ") передано панели, всего передано " //$NON-NLS-1$
                + passedCount + " из " + eventCount + " — " + reason; //$NON-NLS-1$ //$NON-NLS-2$
            Debug.log(msg);
            stock.handleMarkersChanged(event);
            // Отбор мог измениться вместе с результатом (например, сменился текущий объект)
            refreshFilterSnapshot();
        }

        /** Снимает текущий отбор панели в UI-потоке — там его строить безопасно. */
        void refreshFilterSnapshot()
        {
            Display.getDefault().asyncExec(() ->
            {
                try
                {
                    if (view.getSite() == null)
                        return;
                    Object filters = problemFilters(view.getClass().getClassLoader());
                    showAllActive = filters == null
                        || Boolean.TRUE.equals(Global.invoke(filters, "isShowAll")); //$NON-NLS-1$
                    subsystemFilterActive = filters == null
                        || (SCOPE_SUBSYSTEM_FILTER.equals(scopeName(Global.invoke(filters, "getScope"))) //$NON-NLS-1$
                            && !showAllActive);
                    Object filter = Global.invoke(view, "getMarkerFilter"); //$NON-NLS-1$
                    if (filter instanceof MarkerFilter markerFilter)
                    {
                        filterSnapshot = markerFilter;
                        filterSnapshotAt = System.currentTimeMillis();
                        MarkerChangeTap.get().setScope(markerFilter, MarkerChangeTap.modulePathsOf(view));
                        String description = describeFilter(markerFilter);
                        if (!description.equals(filterDescription))
                        {
                            String msg = "заслонка обновлений: отбор панели снят заново — " + description //$NON-NLS-1$
                                + " (был " + filterDescription + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                            Debug.log(msg);
                        }
                        filterDescription = description;
                    }
                    else
                    {
                        Debug.log("заслонка обновлений: панель вернула не отбор — " + className(filter)); //$NON-NLS-1$
                    }
                }
                catch (RuntimeException e)
                {
                    Global.logError(Debug.TAG, "заслонка обновлений: отбор панели не снят", e); //$NON-NLS-1$
                    filterSnapshot = null;
                    filterDescription = "нет"; //$NON-NLS-1$
                }
            });
        }

        /**
         * Описание отбора для журнала. Отдельно помечается отбор «всегда ложь»: его строит
         * {@code LazyProblemView.buildTreeFilter}, когда область отбора ни во что не
         * разрешилась, и под ним маркеров всегда ноль — то есть отпечаток застывает.
         */
        private static String describeFilter(MarkerFilter filter)
        {
            String text = String.valueOf(filter);
            if (text.equals(alwaysFalseDescription()))
                return "ВСЕГДА ЛОЖЬ (" + text + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            return text.length() > FILTER_LOG_LIMIT ? text.substring(0, FILTER_LOG_LIMIT) + "…" : text; //$NON-NLS-1$
        }

        private static String alwaysFalseDescription()
        {
            String known = alwaysFalseDescription;
            if (known == null)
            {
                known = String.valueOf(MarkerFilter.createAlwaysFalseFilter());
                alwaysFalseDescription = known;
            }
            return known;
        }

        private static volatile String alwaysFalseDescription;

        private static String changedProjectNames(MarkersChangedEvent event)
        {
            Collection<IProject> changed = event != null ? event.getChangedProjects() : null;
            if (changed == null || changed.isEmpty())
                return "нет (отпечаток посчитается по пустому набору проектов)"; //$NON-NLS-1$
            return changed.stream().map(IProject::getName).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }

        private static String sinceText(long moment)
        {
            if (moment == 0L)
                return "ни разу"; //$NON-NLS-1$
            return (System.currentTimeMillis() - moment) + " мс назад"; //$NON-NLS-1$
        }
    }

    /**
     * Подпись «Область: …» в строке над деревом, сразу за итогами по видам
     * проблем (issue 401). Для режимов «Текущий проект», «Текущий объект» и
     * «Текущий элемент» дописывается ещё и сам источник отбора (имя проекта,
     * полное имя объекта или элемента) — иначе непонятно, чей это список.
     *
     * <p>При включённом «Показывать все» отбор по области не применяется вовсе,
     * поэтому подпись показывает «Все проекты», а не выбранный в настройках режим.
     *
     * <p>Область возникновения — самый «дорогой» отбор панели: он один способен
     * убрать из списка почти всё. Штатно он виден только внутри окна «Настройки
     * отбора», поэтому пустой список легко принять за отсутствие проблем.
     *
     * <p>Текст дописывается в саму штатную надпись с итогами. Панель переписывает
     * её при каждом обновлении маркеров и о своих записях никак не сообщает
     * ({@code Label} события смены текста не шлёт), поэтому дополнение
     * восстанавливается по таймеру: сравнивается только строка, и лишь при
     * расхождении вызывается {@code setText}. На ту же надпись ставится
     * {@link #applyStatsTooltip подсказка} про «Ошибок / Предупреждений».
     */
    private static void installScopeLabel(IViewPart view)
    {
        Object statusObj = Global.getField(view, "statusLabel"); //$NON-NLS-1$
        if (!(statusObj instanceof Label status) || status.isDisposed())
        {
            Debug.log("installScopeLabel: statusLabel not found"); //$NON-NLS-1$
            return;
        }
        if (Boolean.TRUE.equals(status.getData(SCOPE_LABEL_KEY)))
            return;
        status.setData(SCOPE_LABEL_KEY, Boolean.TRUE);

        ClassLoader loader = view.getClass().getClassLoader();
        Object filters = problemFilters(loader);
        appendScope(view, status, filters, loader);
        applyStatsTooltip(status);
        listenScopeChanges(view, status, filters, loader);
        keepScopeAppended(view, status, filters, loader);
        Debug.log("installScopeLabel: installed"); //$NON-NLS-1$
    }

    /**
     * Подсказка штатной надписи с итогами: что в «Ошибок», что в «Предупреждений».
     * Снимается вместе с флажком «Улучшать списки». Текст ставится один раз —
     * повторный {@code setToolTipText} сбросил бы уже показанную подсказку.
     */
    private static void applyStatsTooltip(Label status)
    {
        if (status == null || status.isDisposed())
            return;
        boolean enable = ComfortSettings.isReplaceListFiltersEnabled();
        boolean applied = Boolean.TRUE.equals(status.getData(STATS_TOOLTIP_KEY));
        if (enable == applied)
            return;
        status.setToolTipText(enable
            ? TooltipText.wrap(status, STATS_TOOLTIP + Global.pluginSignForTooltip())
            : null);
        status.setData(STATS_TOOLTIP_KEY, enable ? Boolean.TRUE : null);
    }

    /**
     * В дереве и подсказках строк штатное имя {@link IssueType#ERROR} совпадает
     * с критичностью {@code ERRORS}. Подменяем только там, где элемент — этот
     * тип, не группу «Ошибки конфигурации».
     */
    private static void installTypeRename(IViewPart view)
    {
        if (!(view.getAdapter(TreeViewer.class) instanceof TreeViewer viewer))
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || Boolean.TRUE.equals(tree.getData(TYPE_RENAME_KEY)))
            return;
        ClassLoader loader = view.getClass().getClassLoader();
        int wrapped = 0;
        for (int i = 0; i < tree.getColumnCount(); i++)
        {
            TreeViewerColumn column = resolveViewerColumn(viewer, tree, i);
            if (column == null)
                continue;
            Object lpObj = Global.invoke(column, "getLabelProvider"); //$NON-NLS-1$
            if (lpObj instanceof TypeRenameLabelProvider || !(lpObj instanceof CellLabelProvider lp))
                continue;
            TypeRenameLabelProvider wrapper = new TypeRenameLabelProvider(lp, loader);
            if (!Global.invokeVoid(column, "setLabelProvider", wrapper, Boolean.FALSE)) //$NON-NLS-1$
            {
                Debug.log("installTypeRename: 2-arg setLabelProvider not found"); //$NON-NLS-1$
                continue;
            }
            wrapped++;
        }
        if (wrapped > 0)
        {
            tree.setData(TYPE_RENAME_KEY, Boolean.TRUE);
            Debug.log("installTypeRename: wrapped " + wrapped + " columns"); //$NON-NLS-1$
        }
    }

    private static TreeViewerColumn resolveViewerColumn(TreeViewer viewer, Tree tree, int index)
    {
        Object vc = Global.invoke(viewer, "getViewerColumn", Integer.valueOf(index)); //$NON-NLS-1$
        if (vc instanceof TreeViewerColumn tvc)
            return tvc;
        if (index >= 0 && index < tree.getColumnCount())
        {
            TreeColumn column = tree.getColumn(index);
            if (column != null && column.getData("org.eclipse.jface.columnViewer") instanceof TreeViewerColumn fromData) //$NON-NLS-1$
                return fromData;
        }
        return null;
    }

    private static IssueType issueTypeOf(Object element, ClassLoader loader)
    {
        if (element == null || loader == null)
            return null;
        try
        {
            Class<?> lazy = loader.loadClass(
                "com._1c.g5.v8.dt.internal.ui.validation.lazytree.LazyTreeNode"); //$NON-NLS-1$
            Object node = lazy.isInstance(element) ? element : Adapters.adapt(element, lazy);
            if (node != null)
            {
                Object marker = Global.invoke(node, "getMarker"); //$NON-NLS-1$
                if (marker instanceof Marker m)
                    return issueTypeOfMarker(m, loader);
                Object group = Global.invoke(node, "getGroup"); //$NON-NLS-1$
                Object id = group != null ? Global.invoke(group, "getId") : null; //$NON-NLS-1$
                if (id instanceof IssueType type)
                    return type;
            }
            if (element instanceof Marker marker)
                return issueTypeOfMarker(marker, loader);
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static IssueType issueTypeOfMarker(Marker marker, ClassLoader loader)
    {
        if (marker.getSeverity() == MarkerSeverity.ERRORS)
            return null;
        try
        {
            Class<?> helper = loader.loadClass("com._1c.g5.v8.dt.internal.ui.validation.UIHelper"); //$NON-NLS-1$
            Object type = Global.invoke(helper, "getIssueType", marker); //$NON-NLS-1$
            return type instanceof IssueType t ? t : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * Подмена штатного имени {@link IssueType#ERROR} в тексте ячейки и подсказке.
     * Штатный провайдер не диспозится ({@code setLabelProvider(..., false)}).
     */
    private static final class TypeRenameLabelProvider extends ColumnLabelProvider
    {
        private final CellLabelProvider delegate;

        private final ClassLoader loader;

        private TypeRenameLabelProvider(CellLabelProvider delegate, ClassLoader loader)
        {
            this.delegate = delegate;
            this.loader = loader;
        }

        @Override
        public void update(ViewerCell cell)
        {
            delegate.update(cell);
            String rewritten = rewrite(cell.getElement(), cell.getText());
            if (rewritten != null && !rewritten.equals(cell.getText()))
                cell.setText(rewritten);
        }

        @Override
        public String getToolTipText(Object element)
        {
            Object tip = Global.invoke(delegate, "getToolTipText", element); //$NON-NLS-1$
            return rewrite(element, tip instanceof String s ? s : null);
        }

        private String rewrite(Object element, String text)
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return text;
            return ValidationChecksFilterHook.replaceDisplayedType(issueTypeOf(element, loader), text);
        }

        @Override
        public void dispose()
        {
            // Штатный провайдер живёт у колонки; не диспозить его вместе с обёрткой.
        }
    }

    /**
     * Двойной щелчок в колонке «Код проверки» открывает настройку проверки, а
     * не редактор объекта проблемы (issue 401).
     *
     * <p>Штатное открытие делает {@code OpenAndLinkWithEditorHelper$InternalListener},
     * зарегистрированный у {@code TreeViewer} панели как {@link IOpenListener}
     * (срабатывает по {@code SWT.DefaultSelection}, не по {@code MouseDoubleClick} —
     * поэтому фильтром {@code Display} его не перехватить). Снимаем этот listener
     * штатным {@code removeOpenListener} и ставим свой: для колонки «Код проверки» —
     * настройка проверки, для остальных колонок — делегирование снятому listener'у,
     * то есть прежнее поведение. «Связь с редактором» живёт в отдельном
     * {@code selectionChanged} того же объекта и не затрагивается.
     */
    private static void installOpenOverride(IViewPart view)
    {
        if (!(view.getAdapter(TreeViewer.class) instanceof TreeViewer viewer))
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || Boolean.TRUE.equals(tree.getData(OPEN_OVERRIDE_KEY)))
            return;

        IOpenListener stock = findStockOpenListener(viewer);
        if (stock == null)
        {
            Debug.log("installOpenOverride: stock open listener not found"); //$NON-NLS-1$
            return;
        }
        tree.setData(OPEN_OVERRIDE_KEY, Boolean.TRUE);

        int[] lastColumn = { -1 };
        tree.addListener(SWT.MouseDown, ev -> lastColumn[0] = columnIndexAt(tree, ev.x, ev.y));
        // Навигация клавишами уводит от «колонки последнего клика» — тогда Enter
        // должен открывать объект штатно.
        tree.addListener(SWT.KeyDown, ev -> lastColumn[0] = -1);

        viewer.removeOpenListener(stock);
        viewer.addOpenListener(event ->
        {
            int index = lastColumn[0];
            Marker marker = ComfortSettings.isReplaceListFiltersEnabled()
                ? firstMarker(event.getSelection()) : null;
            if (marker != null && index >= 0 && index < tree.getColumnCount()
                && CODE_COLUMN_TITLE.equals(columnTitle(tree, index)))
            {
                openCheckSettings(tree.getShell(), marker);
                return;
            }
            stock.open(event);
        });
        tree.addDisposeListener(e -> tree.setData(OPEN_OVERRIDE_KEY, null));
        Debug.log("installOpenOverride: installed"); //$NON-NLS-1$
    }

    private static IOpenListener findStockOpenListener(TreeViewer viewer)
    {
        Object listenerList = Global.getField(viewer, "openListeners"); //$NON-NLS-1$
        Object raw = listenerList != null ? Global.invoke(listenerList, "getListeners") : null; //$NON-NLS-1$
        if (!(raw instanceof Object[] listeners))
            return null;
        for (Object listener : listeners)
        {
            if (listener instanceof IOpenListener open
                && "org.eclipse.ui.OpenAndLinkWithEditorHelper$InternalListener".equals(listener.getClass().getName())) //$NON-NLS-1$
                return open;
        }
        return null;
    }

    private static int columnIndexAt(Tree tree, int x, int y)
    {
        TreeItem item = tree.getItem(new Point(x, y));
        if (item == null)
            return -1;
        for (int i = 0; i < tree.getColumnCount(); i++)
        {
            if (item.getBounds(i).contains(x, y))
                return i;
        }
        return -1;
    }

    private static String columnTitle(Tree tree, int index)
    {
        TreeColumn column = tree.getColumn(index);
        return column != null ? column.getText() : null;
    }

    private static Marker firstMarker(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured))
            return null;
        return markerOf(structured.getFirstElement());
    }

    /** Дописывает отбор к штатным итогам, заменяя ранее дописанное. */
    private static void appendScope(IViewPart view, Label status, Object filters, ClassLoader loader)
    {
        if (status.isDisposed())
            return;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            // Флажок «Улучшать списки» снят — вернуть штатные итоги без дописанного отбора
            int appended = status.getText().indexOf(SCOPE_SEPARATOR);
            if (appended >= 0)
                status.setText(status.getText().substring(0, appended));
            return;
        }
        String suffix = scopeSuffix(view, filters, loader);
        if (suffix == null)
            return;
        String text = status.getText();
        int appended = text.indexOf(SCOPE_SEPARATOR);
        String wanted = (appended >= 0 ? text.substring(0, appended) : text) + suffix;
        if (wanted.equals(text))
            return;
        status.setText(wanted);
        // Смена подписи означает и смену отбора (например, выбрали другой объект):
        // отпечаток результата надо считать уже по новому отбору
        ResultChangeGate gate = gates.get(view);
        if (gate != null)
            gate.refreshFilterSnapshot();
    }

    private static String scopeSuffix(IViewPart view, Object filters, ClassLoader loader)
    {
        // Наши варианты области не относятся к штатному enum: под ними панель
        // работает в режиме «Текущий элемент»/«Текущий проект», но показывать надо
        // выбранный вариант — с именем проекта (и набора).
        String comfortLabel = ComfortSettings.isReplaceListFiltersEnabled() ? comfortScopeLabel(view) : null;
        if (comfortLabel != null && !Boolean.TRUE.equals(Global.invoke(filters, "isShowAll"))) //$NON-NLS-1$
            return SCOPE_SEPARATOR + SCOPE_TITLE + ": " + comfortLabel; //$NON-NLS-1$

        // При «Показывать все» панель полностью пропускает отбор по области
        // (LazyProblemView: isShowAll() -> область не применяется), поэтому
        // выбранный в настройках режим показывать нельзя — он ничего не отбирает.
        boolean showAll = Boolean.TRUE.equals(Global.invoke(filters, "isShowAll")); //$NON-NLS-1$
        String scopeName = showAll ? SCOPE_ALL : scopeName(Global.invoke(filters, "getScope")); //$NON-NLS-1$
        if (scopeName == null)
            return null;
        String value = message(loader, scopeField(scopeName));
        if (value == null)
        {
            Debug.log("scopeSuffix: no message for " + scopeName); //$NON-NLS-1$
            return null;
        }
        String detail = scopeDetail(view, scopeName);
        return SCOPE_SEPARATOR + SCOPE_TITLE + ": " + value //$NON-NLS-1$
            + (detail != null ? ": " + detail : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Источник отбора для режимов «Текущий проект» / «Текущий объект» /
     * «Текущий элемент»: панель строит его из своих {@code scopeSelection} +
     * {@code scopeSelectionManager} ({@code LazyProblemView.getCurrentSelectedObjectIds}),
     * оттуда же берём и подпись.
     *
     * <ul>
     * <li>«Текущий проект» — имена выделенных проектов;</li>
     * <li>«Текущий объект» — выделенный элемент поднят до объекта МД верхнего
     * уровня (как в самом отборе);</li>
     * <li>«Текущий элемент» — сам выделенный элемент, без подъёма.</li>
     * </ul>
     *
     * @return имена через запятую или {@code null}, если источник не определён
     */
    private static String scopeDetail(IViewPart view, String scopeName)
    {
        Object selection = Global.getField(view, "scopeSelection"); //$NON-NLS-1$
        if (selection == null)
            return null;
        if (SCOPE_CURRENT_PROJECT.equals(scopeName))
            return join(projectNames(selection));
        if (!SCOPE_CURRENT_OBJECT.equals(scopeName) && !SCOPE_CURRENT_ELEMENT.equals(scopeName))
            return null;

        Object manager = Global.getField(view, "scopeSelectionManager"); //$NON-NLS-1$
        boolean liftToTopObject = SCOPE_CURRENT_OBJECT.equals(scopeName);
        Object objects = Global.invoke(selection, "getSelectedObjects"); //$NON-NLS-1$
        if (!(objects instanceof Map<?, ?> byProject))
            return null;

        Set<String> names = new LinkedHashSet<>();
        for (Object perProject : byProject.values())
        {
            if (!(perProject instanceof Collection<?> items))
                continue;
            for (Object item : items)
                add(names, fullNameOfScopeObject(manager, item, liftToTopObject));
        }
        return join(names);
    }

    /**
     * Проекты отбора: как в штатном {@code ScopeSelectionManager.getCurrentProject} —
     * и явно выделенные проекты, и проекты выделенных объектов (при выделении
     * объекта список проектов пуст, а отбор всё равно работает).
     */
    private static Set<String> projectNames(Object selection)
    {
        Set<String> names = new LinkedHashSet<>();
        addProjectNames(names, Global.invoke(selection, "getSelectedProjects")); //$NON-NLS-1$
        if (Global.invoke(selection, "getSelectedObjects") instanceof Map<?, ?> byProject) //$NON-NLS-1$
            addProjectNames(names, byProject.keySet());
        return names;
    }

    private static void addProjectNames(Set<String> names, Object projects)
    {
        if (!(projects instanceof Collection<?> items))
            return;
        for (Object project : items)
        {
            if (project instanceof IProject resource)
                add(names, resource.getName());
        }
    }

    private static String fullNameOfScopeObject(Object manager, Object item, boolean liftToTopObject)
    {
        if (!(item instanceof EObject object))
            return null;
        // Подпись обновляется по таймеру: исключение из чужого резолва оборвало бы цепочку тиков.
        try
        {
            if (com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.CONFIGURATION.isSuperTypeOf(object.eClass()))
                return "<Конфигурация>";
            Object top = liftToTopObject && manager != null
                ? Global.invoke(manager, "getTopMdObject", object) //$NON-NLS-1$
                : null;
            EObject target = top instanceof EObject topObject ? topObject : object;
            if (com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.CONFIGURATION.isSuperTypeOf(target.eClass()))
                return "<Конфигурация>";
            return GetRef.eObjectToFullName(target);
        }
        catch (Exception e)
        {
            Debug.log("fullNameOfScopeObject: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void add(Set<String> names, String name)
    {
        if (name != null && !name.isBlank())
            names.add(name);
    }

    private static String join(Set<String> names)
    {
        return names.isEmpty() ? null : String.join(", ", names); //$NON-NLS-1$
    }

    private static void keepScopeAppended(IViewPart view, Label status, Object filters, ClassLoader loader)
    {
        Display display = status.getDisplay();
        Runnable[] tick = new Runnable[1];
        tick[0] = () ->
        {
            if (status.isDisposed())
                return;
            UpdateWaitIndicator.apply(view, status);
            appendScope(view, status, filters, loader);
            applyStatsTooltip(status);
            display.timerExec(SCOPE_REFRESH_MS, tick[0]);
        };
        display.timerExec(SCOPE_REFRESH_MS, tick[0]);
    }

    private static String scopeName(Object scope)
    {
        return scope instanceof Enum<?> constant ? constant.name() : null;
    }

    /**
     * Подпись нашей области над деревом: «Отобранное в проекте &lt;Проект&gt; навигатора»
     * либо «Набор «&lt;Набор&gt;» проекта &lt;Проект&gt;» (+ « пуст», если набор без
     * объектов). Проект — из фактически наложенной области ({@link #comfortAppliedProject}),
     * а если её нет (набор пуст) — активный проект страницы.
     */
    private static String comfortScopeLabel(IViewPart view)
    {
        ProblemViewComfortScope.Mode mode = ProblemViewComfortScope.mode();
        IProject project = comfortAppliedProject.get(view);
        String projectName = project != null ? project.getName() : null;
        if (mode == ProblemViewComfortScope.Mode.NAVIGATOR)
        {
            return projectName != null
                ? "Отобранное в проекте " + projectName + " навигатора" //$NON-NLS-1$ //$NON-NLS-2$
                : ProblemViewComfortScope.NAVIGATOR_LABEL;
        }
        if (mode == ProblemViewComfortScope.Mode.ACTIVE_SETS)
        {
            boolean empty = project == null; // область по объектам не наложена → набор пуст
            if (projectName == null && view.getSite() != null)
            {
                IProject active = ActiveProjectTracker.resolveContextProject(view.getSite().getPage());
                projectName = active != null ? active.getName() : null;
            }
            if (projectName == null)
                return empty ? "Активный набор проекта пуст" : ProblemViewComfortScope.ACTIVE_SETS_LABEL; //$NON-NLS-1$
            ObjectSets.SetDef set = activeSet(projectName);
            String setPart = set != null ? "Набор " + ObjectSets.quotedName(set) : "Активный набор"; //$NON-NLS-1$ //$NON-NLS-2$
            boolean suffixEmpty = empty && (set == null || !set.isDefaultSet());
            return setPart + " проекта " + projectName + (suffixEmpty ? " пуст" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return null;
    }

    private static ObjectSets.SetDef activeSet(String projectName)
    {
        try
        {
            return ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /** Имя поля {@code Messages} с названием режима: {@code CURRENT_PROJECT} → {@code Scope_current_project}. */
    private static String scopeField(String constantName)
    {
        if ("ALL".equals(constantName)) //$NON-NLS-1$
            return "Scope_All"; //$NON-NLS-1$
        if ("SUBSYSTEM_FILTER".equals(constantName)) //$NON-NLS-1$
            return "Scope_subsystems_filter"; //$NON-NLS-1$
        return "Scope_" + constantName.toLowerCase(); //$NON-NLS-1$
    }

    /**
     * Отбор живёт в {@code ProblemFilters} — том же объекте, на который подписана
     * сама панель, поэтому подпись меняется вместе со списком. Пакет
     * {@code internal} наружу не экспортирован, так что слушателя подставляем
     * динамическим прокси (см. правило про неэкспортированные супертипы).
     */
    private static void listenScopeChanges(IViewPart view, Label status, Object filters, ClassLoader loader)
    {
        if (filters == null)
            return;
        try
        {
            Class<?> listenerType = loader.loadClass(CHANGE_LISTENER_CLASS);
            Display display = status.getDisplay();
            InvocationHandler handler = (proxy, method, args) ->
            {
                if ("accept".equals(method.getName())) //$NON-NLS-1$
                    display.asyncExec(() ->
                    {
                        appendScope(view, status, filters, loader);
                        // «Показывать все» может смениться при той же подписи области.
                        ResultChangeGate gate = gates.get(view);
                        if (gate != null)
                            gate.refreshFilterSnapshot();
                    });
                return defaultProxyResult(method, args, proxy);
            };
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[] { listenerType }, handler);
            Global.invoke(filters, "addChangeListener", listener); //$NON-NLS-1$
            status.addDisposeListener(event -> Global.invoke(filters, "removeChangeListener", listener)); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Debug.log("listenScopeChanges: " + e); //$NON-NLS-1$
        }
    }

    private static Object defaultProxyResult(java.lang.reflect.Method method, Object[] args, Object proxy)
    {
        return switch (method.getName())
        {
            case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null); //$NON-NLS-1$
            case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
            case "toString" -> "ProblemViewHook.scopeListener"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    private static Object problemFilters(ClassLoader loader)
    {
        try
        {
            return loader.loadClass(PLUGIN_CLASS).getMethod("getProblemFilters").invoke(null); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Debug.log("problemFilters: " + e); //$NON-NLS-1$
            return null;
        }
    }

    /** Локализованная строка EDT из неэкспортированного {@code Messages}. */
    private static String message(ClassLoader loader, String fieldName)
    {
        try
        {
            Field field = loader.loadClass(MESSAGES_CLASS).getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof String text && !text.isBlank() ? text.trim() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static boolean isProblemView(IViewPart view)
    {
        return view != null && ProblemViewMarkers.PROBLEM_VIEW_ID.equals(view.getViewSite().getId());
    }

    // === Области отбора «Отобранное в проекте навигатора» / «Активный набор проекта» (issue 462) ===
    //
    // Радио добавляет ProblemFiltersDialogHook, режим хранит ProblemViewComfortScope. Здесь:
    // синтетическая ScopeSelection из объектов области + перевод штатной «Области возникновения»
    // в CURRENT_ELEMENT (по ней панель отбирает маркеры по конкретным объектам и их потомкам).

    private static void installComfortScope(IViewPart view)
    {
        synchronized (comfortInstalled)
        {
            if (comfortInstalled.containsKey(view))
                return;
            comfortInstalled.put(view, Boolean.TRUE);
        }
        ClassLoader loader = view.getClass().getClassLoader();
        Object filters = problemFilters(loader);
        if (filters == null)
        {
            Debug.log("installComfortScope: no ProblemFilters"); //$NON-NLS-1$
            return;
        }

        installComfortGlobalListeners();

        // Смена активного проекта страницы — пересобрать нашу область (её проект =
        // активный проект). Это не «каждый клик»: слушатель срабатывает только на
        // реальную смену контекстного проекта, потому потока обновлений нет.
        IWorkbenchPage page = view.getSite().getPage();
        ActiveProjectTracker.bootstrapPage(page);
        ActiveProjectTracker.ContextProjectListener projectListener = (p, previous, current) ->
        {
            if (!java.util.Objects.equals(previous, current) && view.getSite() != null
                && ProblemViewComfortScope.mode() != ProblemViewComfortScope.Mode.NONE)
            {
                Display.getDefault().asyncExec(() -> applyComfortScope(view, filters, loader));
            }
        };
        ActiveProjectTracker.addListener(page, projectListener);

        // Метку установки снять при закрытии панели (иначе следующий её экземпляр
        // не переустановит хук).
        if (view.getAdapter(TreeViewer.class) instanceof TreeViewer viewer
            && viewer.getTree() != null && !viewer.getTree().isDisposed())
        {
            viewer.getTree().addDisposeListener(e ->
            {
                ActiveProjectTracker.removeListener(page, projectListener);
                synchronized (comfortInstalled)
                {
                    comfortInstalled.remove(view);
                }
                comfortSyntheticSelection.remove(view);
                comfortAppliedScope.remove(view);
                comfortAppliedProject.remove(view);
            });
        }

        startComfortReassertTicker(view, filters, loader);
    }

    private static volatile boolean comfortGlobalListeners;

    /**
     * Слушатели наборов — один раз на процесс: панель проблем — синглтон, набор
     * объектов у режимов общий, поэтому на изменение наборов пересобираем область
     * во всех открытых панелях.
     */
    private static void installComfortGlobalListeners()
    {
        if (comfortGlobalListeners)
            return;
        comfortGlobalListeners = true;
        Runnable rebuildAll = () -> Display.getDefault().asyncExec(ProblemViewHook::rebuildComfortScopeEverywhere);
        try
        {
            ObjectSets.getInstance().addChangeListener(rebuildAll);
            ObjectSetsAddTargetState.getInstance().addListener(rebuildAll);
        }
        catch (RuntimeException e)
        {
            Debug.log("installComfortGlobalListeners: " + e); //$NON-NLS-1$
        }
        ProblemViewComfortScope.addListener(
            () -> Display.getDefault().asyncExec(ProblemViewHook::rebuildComfortScopeEverywhere));

        // Выбор штатного радио «Области возникновения» (в диалоге его перехватывает
        // ProblemFiltersDialogHook, а вот пулдаун/меню панели идут прямо через команду)
        // — сбрасывает наш режим.
        ICommandService commandService = PlatformUI.isWorkbenchRunning()
            ? PlatformUI.getWorkbench().getService(ICommandService.class) : null;
        if (commandService != null)
        {
            commandService.addExecutionListener(new IExecutionListener()
            {
                @Override public void postExecuteSuccess(String commandId, Object returnValue)
                {
                    if (NATIVE_SCOPE_COMMAND_ID.equals(commandId))
                    {
                        ProblemViewComfortScope.setMode(ProblemViewComfortScope.Mode.NONE);
                    }
                    else if (NAVIGATOR_SUBSYSTEMS_FILTER_COMMAND_ID.equals(commandId)
                        && ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NAVIGATOR)
                    {
                        // Фильтр по подсистемам в навигаторе применяется асинхронно.
                        Display.getDefault().timerExec(400, ProblemViewHook::rebuildComfortScopeEverywhere);
                    }
                }

                @Override public void preExecute(String commandId, org.eclipse.core.commands.ExecutionEvent event) {}
                @Override public void postExecuteFailure(String commandId, ExecutionException exception) {}
                @Override public void notHandled(String commandId, NotHandledException exception) {}
            });
        }
    }

    private static void rebuildComfortScopeEverywhere()
    {
        java.util.List<IViewPart> views;
        synchronized (comfortInstalled)
        {
            views = new java.util.ArrayList<>(comfortInstalled.keySet());
        }
        for (IViewPart view : views)
        {
            if (view.getSite() == null)
                continue;
            ClassLoader loader = view.getClass().getClassLoader();
            Object filters = problemFilters(loader);
            if (filters != null)
                applyComfortScope(view, filters, loader);
        }
    }

    /**
     * Вызывается на каждый показ панели: если режим активен — восстановить нашу
     * область, если панель её сбросила. Дешёвая сверка, без пересборки на каждый
     * показ (полная пересборка — только на смену режима/наборов).
     */
    private static void refreshComfortScope(IViewPart view)
    {
        if (view.getSite() == null || ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NONE)
            return;
        ClassLoader loader = view.getClass().getClassLoader();
        Object filters = problemFilters(loader);
        if (filters != null)
            reassertComfortScope(view, filters, loader);
    }

    /**
     * Полное наложение: разрешить объекты области в фоне (открывает BM-транзакции),
     * затем в UI-потоке подменить {@code scopeSelection} и обновить панель.
     */
    private static void applyComfortScope(IViewPart view, Object filters, ClassLoader loader)
    {
        if (view.getSite() == null)
            return;
        ProblemViewComfortScope.Mode mode = ProblemViewComfortScope.mode();
        if (mode == ProblemViewComfortScope.Mode.NONE || !ComfortSettings.isReplaceListFiltersEnabled())
        {
            clearComfortScope(view, filters, loader);
            return;
        }
        IWorkbenchPage page = view.getSite().getPage();
        // Панель отбирает по одному проекту (getCurrentProject), поэтому область
        // ограничивается активным проектом.
        IProject active = ActiveProjectTracker.resolveContextProject(page);
        Map<String, List<String>> refs = ProblemViewComfortScope.collectRefs();

        // Нечем сузить: «Отобранное в проекте навигатора» без активного отбора —
        // это весь проект навигатора; «Активный набор проекта» без набора — без
        // ограничения. Так режим никогда не «залипает» на текущем выделении.
        IProject fallbackProject =
            mode == ProblemViewComfortScope.Mode.NAVIGATOR ? active : null;

        Job job = Job.create("Комфорт: область панели проблем", (ICoreRunnable)monitor -> //$NON-NLS-1$
        {
            Map<IProject, Set<EObject>> data =
                refs.isEmpty() ? Map.of() : resolveComfortScopeObjects(page, refs, active);
            Display.getDefault().asyncExec(
                () -> injectComfortScope(view, filters, loader, data, fallbackProject));
        });
        job.setSystem(true);
        job.schedule();
    }

    /** Разрешение владеющих ссылок в {@link EObject} метаданных активного проекта. */
    private static Map<IProject, Set<EObject>> resolveComfortScopeObjects(
        IWorkbenchPage page, Map<String, List<String>> refs, IProject preferred)
    {
        Map<IProject, Set<EObject>> result = new LinkedHashMap<>();
        // Ровно один проект: активный, если он есть среди отобранных, иначе первый.
        String chosen = chooseComfortScopeProject(refs, preferred);
        for (Map.Entry<String, List<String>> entry : refs.entrySet())
        {
            String projectName = entry.getKey();
            if (projectName == null || !projectName.equals(chosen))
                continue;
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.isOpen())
                continue;
            Set<EObject> objects = new LinkedHashSet<>();
            for (String ref : entry.getValue())
            {
                EObject eObject = null;
                try
                {
                    eObject = GoToDefinition.resolveEObjectForFullName(ref, page, project);
                }
                catch (RuntimeException e)
                {
                    Debug.log("resolveComfortScopeObjects: " + ref + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (eObject != null)
                    objects.add(eObject);
            }
            if (!objects.isEmpty())
                result.put(project, objects);
        }
        return result;
    }

    /**
     * Проект, по объектам которого сужаем: только активный, и только если отбор
     * навигатора его затрагивает. Иначе {@code null} — {@link #injectComfortScope}
     * возьмёт весь активный проект ({@code CURRENT_PROJECT}).
     */
    private static String chooseComfortScopeProject(Map<String, List<String>> refs, IProject preferred)
    {
        String preferredName = preferred != null ? preferred.getName() : null;
        return preferredName != null && refs.containsKey(preferredName) ? preferredName : null;
    }

    private static void injectComfortScope(IViewPart view, Object filters, ClassLoader loader,
        Map<IProject, Set<EObject>> data, IProject fallbackProject)
    {
        if (view.getSite() == null || ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NONE
            || !ComfortSettings.isReplaceListFiltersEnabled())
            return;

        Object selection;
        String scopeName;
        IProject scopeProject;
        if (!data.isEmpty())
        {
            selection = newObjectsScopeSelection(loader, data);
            scopeName = SCOPE_CURRENT_ELEMENT;
            scopeProject = data.keySet().iterator().next();
        }
        else if (fallbackProject != null)
        {
            selection = newProjectScopeSelection(loader, fallbackProject);
            scopeName = SCOPE_CURRENT_PROJECT;
            scopeProject = fallbackProject;
        }
        else
        {
            // «Активный набор проекта» без набора — без ограничения по области.
            selection = newProjectScopeSelection(loader, null);
            scopeName = SCOPE_ALL;
            scopeProject = null;
        }
        if (selection == null)
            return;

        comfortSyntheticSelection.put(view, selection);
        comfortAppliedScope.put(view, scopeName);
        if (scopeProject != null)
            comfortAppliedProject.put(view, scopeProject);
        else
            comfortAppliedProject.remove(view);
        Global.setField(view, "scopeSelection", selection); //$NON-NLS-1$
        forceComfortScope(filters, loader, scopeName);
        // Пока наша область активна, панель не должна перестраивать scopeSelection на
        // каждое выделение в навигаторе/редакторе — снимаем её слушатель выделения.
        setPanelSelectionTracking(view, false);
        Global.invoke(filters, "update"); //$NON-NLS-1$
    }

    /**
     * Тихая сверка (тикер): если поле {@code scopeSelection} или область кто-то
     * подменил — вернуть наши <b>без</b> {@code update()}: содержимое не менялось.
     */
    private static void reassertComfortScope(IViewPart view, Object filters, ClassLoader loader)
    {
        if (view.getSite() == null || ProblemViewComfortScope.mode() == ProblemViewComfortScope.Mode.NONE
            || !ComfortSettings.isReplaceListFiltersEnabled())
        {
            clearComfortScope(view, filters, loader);
            return;
        }
        Object synthetic = comfortSyntheticSelection.get(view);
        String scopeName = comfortAppliedScope.get(view);
        if (synthetic == null || scopeName == null)
        {
            applyComfortScope(view, filters, loader);
            return;
        }
        // Активный проект сменился — область относится к другому проекту, пересобрать.
        IProject appliedProject = comfortAppliedProject.get(view);
        if (appliedProject != null && view.getSite().getPage() != null
            && !appliedProject.equals(ActiveProjectTracker.resolveContextProject(view.getSite().getPage())))
        {
            applyComfortScope(view, filters, loader);
            return;
        }
        setPanelSelectionTracking(view, false);
        if (Global.getField(view, "scopeSelection") != synthetic) //$NON-NLS-1$
            Global.setField(view, "scopeSelection", synthetic); //$NON-NLS-1$
        if (!scopeName.equals(scopeName(Global.invoke(filters, "getScope")))) //$NON-NLS-1$
            forceComfortScope(filters, loader, scopeName);
    }

    /**
     * Слушатель выделения самой панели ({@code LazyProblemView implements ISelectionListener},
     * регистрируется через {@code ISelectionService.addPostSelectionListener}).
     */
    private static void setPanelSelectionTracking(IViewPart view, boolean enabled)
    {
        if (!(view instanceof ISelectionListener listener) || view.getSite() == null)
            return;
        IWorkbenchWindow window = view.getSite().getWorkbenchWindow();
        ISelectionService service = window != null ? window.getSelectionService() : null;
        if (service == null)
            return;
        try
        {
            if (enabled)
                service.addPostSelectionListener(listener);
            else
                service.removePostSelectionListener(listener);
        }
        catch (RuntimeException e)
        {
            Debug.log("setPanelSelectionTracking: " + e); //$NON-NLS-1$
        }
    }

    private static void clearComfortScope(IViewPart view, Object filters, ClassLoader loader)
    {
        setPanelSelectionTracking(view, true);
        String applied = comfortAppliedScope.remove(view);
        comfortAppliedProject.remove(view);
        if (comfortSyntheticSelection.remove(view) == null)
            return;
        // Если область всё ещё та, что выставили мы (режим выключили нашим флажком) —
        // вернуть в «Показывать всё», иначе список остался бы сужен нашим
        // CURRENT_ELEMENT/CURRENT_PROJECT. Если область уже другая (пользователь выбрал
        // штатное радио) — не трогать его выбор.
        boolean stillOurs = applied != null
            && applied.equals(scopeName(Global.invoke(filters, "getScope"))); //$NON-NLS-1$
        Object all = stillOurs ? scopeConstant(loader, SCOPE_ALL) : null;
        if (all != null)
        {
            Global.invokeVoid(filters, "setScope", all); //$NON-NLS-1$
            Global.invokeVoid(filters, "setShowAll", Boolean.TRUE); //$NON-NLS-1$
        }
        Object manager = Global.getField(view, "scopeSelectionManager"); //$NON-NLS-1$
        Object fresh = manager != null
            ? Global.invoke(manager, "getScopeFromNavigatorSelection", view.getSite()) : null; //$NON-NLS-1$
        if (fresh == null)
            fresh = newProjectScopeSelection(loader, null);
        if (fresh != null)
            Global.setField(view, "scopeSelection", fresh); //$NON-NLS-1$
        Global.invoke(filters, "update"); //$NON-NLS-1$
    }

    /** Синтетическая область с объектами по проектам ({@code CURRENT_ELEMENT}). */
    private static Object newObjectsScopeSelection(ClassLoader loader, Map<IProject, Set<EObject>> data)
    {
        Object selection = newEmptyScopeSelection(loader);
        if (selection != null && data != null && !data.isEmpty()
            && Global.invoke(selection, "getSelectedObjects") instanceof Map<?, ?> mapObj) //$NON-NLS-1$
        {
            @SuppressWarnings("unchecked")
            Map<IProject, Set<EObject>> map = (Map<IProject, Set<EObject>>)mapObj;
            map.putAll(data);
        }
        return selection;
    }

    /** Синтетическая область с одним проектом ({@code CURRENT_PROJECT}); {@code null} — пустая. */
    private static Object newProjectScopeSelection(ClassLoader loader, IProject project)
    {
        Object selection = newEmptyScopeSelection(loader);
        if (selection != null && project != null
            && Global.invoke(selection, "getSelectedProjects") instanceof java.util.Set<?> setObj) //$NON-NLS-1$
        {
            @SuppressWarnings("unchecked")
            java.util.Set<IProject> set = (java.util.Set<IProject>)setObj;
            set.add(project);
        }
        return selection;
    }

    private static Object newEmptyScopeSelection(ClassLoader loader)
    {
        try
        {
            return loader.loadClass(SCOPE_SELECTION_CLASS).getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            Debug.log("newEmptyScopeSelection: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void forceComfortScope(Object filters, ClassLoader loader, String scopeName)
    {
        Object scope = scopeConstant(loader, scopeName);
        if (scope == null)
            return;
        // Всегда showAll=false: наши режимы оставляют отбор по критичности/типу, а
        // сама область при SCOPE_ALL просто ничего не сужает.
        if (Boolean.TRUE.equals(Global.invoke(filters, "isShowAll"))) //$NON-NLS-1$
            Global.invokeVoid(filters, "setShowAll", Boolean.FALSE); //$NON-NLS-1$
        if (Global.invoke(filters, "getScope") != scope) //$NON-NLS-1$
            Global.invokeVoid(filters, "setScope", scope); //$NON-NLS-1$
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object scopeConstant(ClassLoader loader, String name)
    {
        try
        {
            return Enum.valueOf((Class)loader.loadClass(SCOPE_ENUM_CLASS), name);
        }
        catch (ReflectiveOperationException | IllegalArgumentException e)
        {
            Debug.log("scopeConstant: " + name + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static void startComfortReassertTicker(IViewPart view, Object filters, ClassLoader loader)
    {
        Display display = Display.getDefault();
        Runnable[] tick = new Runnable[1];
        tick[0] = () ->
        {
            if (view.getSite() == null)
                return;
            if (ProblemViewComfortScope.mode() != ProblemViewComfortScope.Mode.NONE
                && view.getSite().getPage().isPartVisible(view))
            {
                reassertComfortScope(view, filters, loader);
            }
            display.timerExec(COMFORT_REASSERT_MS, tick[0]);
        };
        display.timerExec(COMFORT_REASSERT_MS, tick[0]);
    }

    /**
     * Код проверки может быть обычным ({@code undefined-variable}) или коротким
     * в пределах проекта ({@code SU47}). Все команды открытия передают его в
     * {@link ValidationChecksFilterHook}, который разрешает полный {@link CheckUid}
     * и назначает текущую строку после создания списка. Штатный {@code applyData}
     * не получает цель, чтобы не было второго назначения строки.
     */
    static void openCheckSettings(Shell shell, Marker marker)
    {
        if (marker == null)
            return;
        openCheckSettings(shell, marker.getProject(), marker.getCheckId());
    }

    /** То же по проекту и коду проверки — без маркера (подсказка редактора модуля). */
    static void openCheckSettings(Shell shell, IProject project, String code)
    {
        if (code == null || code.isBlank() || project == null)
        {
            Debug.log("openCheckSettings: no check id or project"); //$NON-NLS-1$
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put(ValidationChecksFilterHook.OPEN_CHECK_DATA_KEY, code);
        Debug.log("openCheckSettings: " + code); //$NON-NLS-1$
        Shell target = normalizeParentShell(shell != null ? shell : Display.getDefault().getActiveShell());
        PreferenceDialog dialog =
            PreferencesUtil.createPropertyDialogOn(target, project, CHECKS_PAGE_ID, null, data);
        if (dialog == null)
            return;
        CheckDescriptionRefresh.install(dialog);
        dialog.open();
    }

    /** Открывает страницу параметров проекта с идентификатором {@code pageId}. */
    static void openProjectPage(Shell shell, IProject project, String pageId)
    {
        if (project == null)
            return;
        Shell target = normalizeParentShell(shell != null ? shell : Display.getDefault().getActiveShell());
        PreferenceDialog dialog = PreferencesUtil.createPropertyDialogOn(target, project, pageId, null, null);
        if (dialog != null)
            dialog.open();
    }

    /**
     * Родитель окна параметров — только полноценное окно, не всплывающая подсказка.
     * <p>
     * Подсказка предупреждения в редакторе модуля ({@code BslCheckSettingsHoverContributor})
     * зовёт открытие из своего {@code Shell} без рамки ({@code SWT.ON_TOP} / без
     * {@link SWT#TITLE}), и он же в этот момент активный. Подсказка закрывается сразу по
     * нажатию кнопки, поэтому к моменту закрытия окна параметров владелец уже уничтожен:
     * Windows активировать некого и переводит фокус на другое приложение. Поднимаемся по
     * цепочке владельцев до окна с рамкой, а если такого нет — берём окно EDT.
     */
    private static Shell normalizeParentShell(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            if ((current.getStyle() & SWT.TITLE) != 0 && (current.getStyle() & SWT.ON_TOP) == 0)
                return current;
            current = current.getParent() instanceof Shell parent ? parent : null;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window != null ? window.getShell() : shell;
    }

    /**
     * Пока штатные Job обновления и загрузки списка ещё работают, надпись
     * «0 элементов» вводит в заблуждение: {@code LazyProblemView.update} сначала
     * ставит в очередь UI очистку дерева, потом на том же Job ждёт
     * {@code IMarkerReader.getMarkerInfo} (индекс Lucene), и только после ответа
     * пишет итоги. Если итог пока ноль, штатный текст — {@code MarkerStats_0_items}.
     *
     * <p>На области «Текущий элемент» Job иногда строит отбор только из числового
     * id объекта, без пути {@code Module.bsl}. Маркеры висят на файле модуля, запрос
     * даёт 0, дерево очищается. В тот же объект фильтра, который уже ушёл в
     * {@code updateViewer}, возвращаем пути модуля с последнего ненулевого запроса
     * по тем же id — и индекс, и дерево видят полный отбор. Повтор
     * {@code selectionChanged} здесь не помогает: он тоже даёт усечённый отбор.
     * Повтор с редактора оставляем только для отбора «всегда ложь».
     *
     * <p>Пока проверки ещё считают объекты текущей области ({@code isIdle()} ложь)
     * или штатный Job грузит список, слева от итогов крутится индикатор:
     * список неполный. Итоги в шапке — штатные, как у списка: пустое дерево
     * не подписываем прошлым «Ошибок: N».
     */
    private static final class UpdateWaitIndicator
    {
        /** Прежний текстовый суффикс — снимаем, если ещё остался в шапке. */
        private static final String WAIT_TEXT = "Обновление…"; //$NON-NLS-1$

        private static final String WAIT_SUFFIX = ", " + WAIT_TEXT; //$NON-NLS-1$

        private static final String LAZY_MESSAGES =
            "com._1c.g5.v8.dt.internal.ui.validation.lazytree.Messages"; //$NON-NLS-1$

        private static final String STATS_MESSAGES =
            "com._1c.g5.v8.dt.internal.ui.validation.Messages"; //$NON-NLS-1$

        private static final String MARKER_STATS =
            "com._1c.g5.v8.dt.internal.ui.validation.MarkerStats"; //$NON-NLS-1$

        private static final Map<IViewPart, State> views = new WeakHashMap<>();

        private static final Set<Job> tracked = ConcurrentHashMap.newKeySet();

        private static volatile boolean jobListenerInstalled;

        private static volatile String updateJobName;

        private static volatile String loadJobName;

        private static volatile String zeroItemsText;

        private static final ThreadLocal<Boolean> PEEKING = new ThreadLocal<>();

        private UpdateWaitIndicator() {}

        static void install(IViewPart view)
        {
            if (view.getSite() == null)
                return;
            State existing;
            synchronized (views)
            {
                existing = views.get(view);
                if (existing == null)
                    views.put(view, new State());
            }
            if (existing != null)
            {
                // Ранний partOpened мог прийти до контролов. Вызов из конца
                // createPartControl подключает поле синхронно, без ожидания таймера.
                installCheckSelector(view, existing);
                return;
            }
            rememberJobNames(view.getClass().getClassLoader());
            wrapMarkerManager(view);
            installCheckCodeReset(view);
            installJobListener();
            TreeViewer viewer = view.getAdapter(TreeViewer.class);
            Tree tree = viewer != null ? viewer.getTree() : null;
            if (tree != null && !tree.isDisposed())
            {
                installCheckSelector(view, stateOf(view));
                tree.addDisposeListener(e ->
                {
                    State disposed;
                    synchronized (views)
                    {
                        disposed = views.remove(view);
                    }
                    if (disposed != null && disposed.spinner != null)
                        disposed.spinner.setActive(false);
                });
            }
            apply(view, statusLabel(view));
        }

        static void apply(IViewPart view, Label status)
        {
            if (status == null || status.isDisposed())
                return;
            State state;
            synchronized (views)
            {
                state = views.get(view);
            }
            if (state == null)
                return;
            applyRootCheckCode(view, state, false);
            boolean waiting = ComfortSettings.isReplaceListFiltersEnabled()
                && (state.waitingForIndex || !tracked.isEmpty());
            String original = status.getText();
            String base = stripSuffix(original);
            String stats = stripWaitSuffix(base);
            String wantedBase = null;
            if (!waiting)
            {
                String stock = stockStatusText(view);
                if (base.endsWith(WAIT_SUFFIX) || WAIT_TEXT.equals(base))
                    wantedBase = stock;
                else if (isRealStats(stats) && !isRealStats(stock) && !stock.isBlank())
                    wantedBase = stock;
            }
            if (wantedBase != null && !wantedBase.equals(base))
            {
                int scopeAt = original.indexOf(SCOPE_SEPARATOR);
                String extra = scopeAt >= 0 ? original.substring(scopeAt) : ""; //$NON-NLS-1$
                status.setText(wantedBase + extra);
            }
            applySpinner(status, state, waiting);
        }

        private static void applyAll()
        {
            applyAll(false);
        }

        private static void applyAll(boolean updateFinished)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                List<IViewPart> snapshot;
                synchronized (views)
                {
                    snapshot = List.copyOf(views.keySet());
                }
                for (IViewPart view : snapshot)
                {
                    State state = stateOf(view);
                    if (updateFinished && state != null)
                        applyRootCheckCode(view, state, true);
                    apply(view, statusLabel(view));
                }
            });
        }

        private static void applySpinner(Label status, State state, boolean waiting)
        {
            if (!waiting && state.spinner == null)
                return;
            if (state.spinner == null || state.spinner.isDisposed())
                state.spinner = WaitSpinner.attach(status, "Список проблем ещё обновляется"); //$NON-NLS-1$
            if (state.spinner != null)
                state.spinner.setActive(waiting);
        }

        private static Label statusLabel(IViewPart view)
        {
            Object statusObj = Global.getField(view, "statusLabel"); //$NON-NLS-1$
            return statusObj instanceof Label status && !status.isDisposed() ? status : null;
        }

        private static String stripSuffix(String text)
        {
            if (text == null)
                return ""; //$NON-NLS-1$
            int appended = text.indexOf(SCOPE_SEPARATOR);
            return appended >= 0 ? text.substring(0, appended) : text;
        }

        private static String stripWaitSuffix(String text)
        {
            if (text == null || text.isEmpty())
                return ""; //$NON-NLS-1$
            if (WAIT_TEXT.equals(text))
                return ""; //$NON-NLS-1$
            if (text.endsWith(WAIT_SUFFIX))
                return text.substring(0, text.length() - WAIT_SUFFIX.length());
            return text;
        }

        private static boolean isRealStats(String text)
        {
            if (text == null || text.isBlank())
                return false;
            if (WAIT_TEXT.equals(text) || text.endsWith(WAIT_SUFFIX))
                return false;
            String zero = zeroItemsText;
            return zero == null || !zero.equals(text);
        }

        private static String stockStatusText(IViewPart view)
        {
            Object info = Global.invoke(view, "getEstimatedMarkerInfo"); //$NON-NLS-1$
            try
            {
                Class<?> statsClass = view.getClass().getClassLoader().loadClass(MARKER_STATS);
                Object stats = Global.invoke(statsClass, "of", info); //$NON-NLS-1$
                Object text = stats != null ? Global.invoke(stats, "getStatusText") : null; //$NON-NLS-1$
                if (text instanceof String s && !s.isBlank())
                    return s;
            }
            catch (Exception ignored)
            {
            }
            String zero = zeroItemsText;
            return zero != null ? zero : ""; //$NON-NLS-1$
        }

        private static void rememberJobNames(ClassLoader loader)
        {
            if (updateJobName != null)
                return;
            try
            {
                Class<?> lazy = loader.loadClass(LAZY_MESSAGES);
                updateJobName = stringField(lazy, "LazyProblemView_UpdateJobName"); //$NON-NLS-1$
                loadJobName = stringField(lazy, "LazyTreeNodeContentProvider_LoadMarkersJobName"); //$NON-NLS-1$
                Class<?> stats = loader.loadClass(STATS_MESSAGES);
                zeroItemsText = stringField(stats, "MarkerStats_0_items"); //$NON-NLS-1$
            }
            catch (Exception ignored)
            {
            }
        }

        private static String stringField(Class<?> type, String name)
        {
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(null);
                return value instanceof String s ? s : null;
            }
            catch (Exception ignored)
            {
                return null;
            }
        }

        private static boolean isTrackedJob(Job job)
        {
            if (job == null)
                return false;
            String name = job.getName();
            if (name == null || name.isBlank())
                return false;
            return name.equals(updateJobName) || name.equals(loadJobName);
        }

        private static void installJobListener()
        {
            if (jobListenerInstalled)
                return;
            jobListenerInstalled = true;
            Job.getJobManager().addJobChangeListener(new JobChangeAdapter()
            {
                @Override
                public void aboutToRun(IJobChangeEvent event)
                {
                    Job job = event.getJob();
                    if (!isTrackedJob(job))
                        return;
                    tracked.add(job);
                    applyAll();
                }

                @Override
                public void done(IJobChangeEvent event)
                {
                    Job job = event.getJob();
                    if (job == null || !tracked.remove(job))
                        return;
                    // update() ждёт окончания UI-обновления корня через CountDownLatch.
                    // Его done — точка, где пересозданный корень уже можно переотобрать.
                    applyAll(job.getName().equals(updateJobName));
                }
            });
        }

        private static void wrapMarkerManager(IViewPart view)
        {
            Object manager = Global.getField(view, "markerManager"); //$NON-NLS-1$
            if (!(manager instanceof IMarkerManagerV2 origin))
                return;
            if (!(Proxy.isProxyClass(origin.getClass())
                && Proxy.getInvocationHandler(origin) instanceof ManagerTap))
            {
                IMarkerManagerV2 wrapped = (IMarkerManagerV2)Proxy.newProxyInstance(
                    IMarkerManagerV2.class.getClassLoader(),
                    new Class<?>[] { IMarkerManagerV2.class },
                    new ManagerTap(origin, view));
                boolean installed = Global.setFieldForce(view, "markerManager", wrapped); //$NON-NLS-1$
                if (!installed)
                    return;
            }
            // Менеджер и текущий читатель имеют независимый жизненный цикл:
            // уже обёрнутый менеджер не означает, что поле markerReader тоже обёрнуто.
            Object current = Global.getField(view, "markerReader"); //$NON-NLS-1$
            if (current instanceof IMarkerReader reader
                && !(Proxy.isProxyClass(reader.getClass())
                    && Proxy.getInvocationHandler(reader) instanceof ReaderTap))
            {
                IMarkerReader wrappedReader = (IMarkerReader)Proxy.newProxyInstance(
                    IMarkerReader.class.getClassLoader(),
                    new Class<?>[] { IMarkerReader.class },
                    new ReaderTap(reader, view));
                Global.setFieldForce(view, "markerReader", wrappedReader); //$NON-NLS-1$
            }
        }

        /** Штатное «Показывать все» снимает также отбор, установленный нашей командой. */
        private static void installCheckCodeReset(IViewPart view)
        {
            ICommandService commands = view.getSite().getService(ICommandService.class);
            IExecutionListener listener = new IExecutionListener()
            {
                @Override public void preExecute(String commandId, ExecutionEvent event)
                {
                    if (NATIVE_SCOPE_COMMAND_ID.equals(commandId)
                        && SCOPE_ALL.equals(event.getParameter("org.eclipse.ui.commands.radioStateParameter"))) //$NON-NLS-1$
                        clearCheckCodeForReset(view);
                }
                @Override public void postExecuteSuccess(String commandId, Object result) {}
                @Override public void postExecuteFailure(String commandId, ExecutionException error) {}
                @Override public void notHandled(String commandId, NotHandledException error) {}
            };
            if (commands != null)
                commands.addExecutionListener(listener);
            ClassLoader loader = view.getClass().getClassLoader();
            Object filters = problemFilters(loader);
            Object filterListener = null;
            State state = stateOf(view);
            if (state != null)
                state.checkCodeShowAll = Boolean.TRUE.equals(Global.invoke(filters, "isShowAll")); //$NON-NLS-1$
            try
            {
                Class<?> listenerType = loader.loadClass(CHANGE_LISTENER_CLASS);
                filterListener = Proxy.newProxyInstance(loader, new Class<?>[] { listenerType },
                    (proxy, method, args) ->
                    {
                        if ("accept".equals(method.getName())) //$NON-NLS-1$
                            Display.getDefault().asyncExec(() ->
                            {
                                State current = stateOf(view);
                                if (current == null)
                                    return;
                                boolean showAll = Boolean.TRUE.equals(Global.invoke(filters, "isShowAll")); //$NON-NLS-1$
                                boolean reset = showAll && !current.checkCodeShowAll;
                                current.checkCodeShowAll = showAll;
                                if (reset)
                                    clearCheckCodeForReset(view);
                            });
                        return defaultProxyResult(method, args, proxy);
                    });
                Global.invokeVoid(filters, "addChangeListener", filterListener); //$NON-NLS-1$
            }
            catch (ClassNotFoundException e)
            {
            }
            Object installedFilterListener = filterListener;
            TreeViewer viewer = view.getAdapter(TreeViewer.class);
            if (viewer != null)
                viewer.getTree().addDisposeListener(event ->
                {
                    if (commands != null)
                        commands.removeExecutionListener(listener);
                    if (installedFilterListener != null)
                        Global.invokeVoid(filters, "removeChangeListener", installedFilterListener); //$NON-NLS-1$
                });
        }

        private static void clearCheckCodeForReset(IViewPart view)
        {
            State state = stateOf(view);
            if (state != null && state.checkId != null)
                setCheckCode(view, null);
        }

        private static final class State
        {
            volatile boolean retryingEmpty;

            volatile boolean retriedEmpty;

            volatile boolean waitingForIndex;

            volatile Set<Object> lastGoodObjectIds = Set.of();

            volatile Set<String> lastGoodProjects = Set.of();

            volatile Set<Object> liveModulePaths = Set.of();

            volatile Set<Object> liveNumericIds = Set.of();

            WaitSpinner spinner;

            volatile String checkId;
            volatile CheckUid checkUid;
            volatile Map<IProject, String> checkCodes = Map.of();

            CCombo checkSelector;

            Canvas clearCheckCode;
            org.eclipse.swt.graphics.Color checkTextColor;
            org.eclipse.swt.graphics.Color checkMessageColor;

            List<CheckChoice> checkChoices = List.of();

            Object checkCodeRoot;

            MarkerFilter checkCodeRootSource;

            String checkCodeRootApplied;

            boolean checkCodeShowAll;
        }

        private record CheckChoice(CheckUid uid, String label) {}

        /** Отбор по проверке всегда виден рядом со штатным поиском и имеет свой сброс. */
        private static void installCheckSelector(IViewPart view, State state)
        {
            if (state == null || state.checkSelector != null && !state.checkSelector.isDisposed())
                return;
            TreeViewer viewer = view.getAdapter(TreeViewer.class);
            if (viewer == null || viewer.getTree().isDisposed())
                return;
            SearchBox search = findProblemSearchBox(viewer.getTree().getParent());
            if (search == null || !(search.getParent().getLayout() instanceof GridLayout layout))
                return;
            Composite header = search.getParent();
            Label status = statusLabel(view);
            if (status == null || status.getParent() != header)
                return;
            // Итоги, поиск и выбор проверки — одна строка; сброс внутри поля.
            layout.numColumns = 4;
            layout.makeColumnsEqualWidth = false;
            if (status.getLayoutData() instanceof GridData data)
            {
                data.horizontalSpan = 1;
                data.grabExcessHorizontalSpace = true;
                data.widthHint = 450;
            }
            if (search.getLayoutData() instanceof GridData data)
            {
                data.horizontalSpan = 1;
                data.grabExcessHorizontalSpace = false;
                data.widthHint = 200;
                data.minimumWidth = 100;
            }
            Label label = new Label(header, SWT.NONE);
            label.setText("Проверка:"); //$NON-NLS-1$
            CCombo selector = new CCombo(header, SWT.BORDER | SWT.FLAT | SWT.READ_ONLY);
            // SearchBox использует курсив для приглашения «Поиск…».
            // Шрифт значений и списка берём у строки панели, а не у приглашения.
            selector.setFont(header.getFont());
            selector.setBackground(search.getBackground());
            // Цвет SearchBox при пустом поиске — цвет приглашения, не значения.
            selector.setForeground(header.getForeground());
            // Берём обычный цвет текста, а не серый цвет приглашения SearchBox.
            if (Global.getField(search, "textColor") instanceof org.eclipse.swt.graphics.Color color) //$NON-NLS-1$
                selector.setForeground(color);
            state.checkTextColor = selector.getForeground();
            state.checkMessageColor = Global.getField(search, "messageColor") instanceof org.eclipse.swt.graphics.Color color //$NON-NLS-1$
                ? color : state.checkTextColor;
            GridData selectorData = new GridData(SWT.FILL, SWT.FILL, false, false);
            selectorData.widthHint = 240;
            selector.setLayoutData(selectorData);
            selector.setToolTipText(TooltipText.wrap(selector,
                "Отбор по проверке. «Все проверки» снимает этот отбор." + Global.pluginSignForTooltip())); //$NON-NLS-1$
            Canvas clear = new Canvas(selector, SWT.DOUBLE_BUFFERED);
            clear.setBackground(search.getBackground());
            clear.addPaintListener(event ->
            {
                paintSearchButton(clear, search, event.gc,
                    state.checkUid != null ? "clearButtonImage" : "disabledClearButtonImage"); //$NON-NLS-1$ //$NON-NLS-2$
            });
            clear.setToolTipText(TooltipText.wrap(clear,
                "Снять отбор по проверке" + Global.pluginSignForTooltip())); //$NON-NLS-1$
            state.checkSelector = selector;
            state.clearCheckCode = clear;
            installCheckSelectorInteraction(view, state, search);
            selector.addListener(SWT.Selection, event ->
            {
                int selected = selector.getSelectionIndex();
                CheckUid uid = selected > 0 && selected <= state.checkChoices.size()
                    ? state.checkChoices.get(selected - 1).uid() : null;
                setCheckCodeFromSelector(view, uid);
            });
            clear.addListener(SWT.MouseUp, event ->
            {
                if (event.button == 1)
                {
                    setCheckCodeFromSelector(view, null);
                }
            });
            selector.add("Все проверки"); //$NON-NLS-1$
            updateCheckSelectorValue(state);
            header.requestLayout();
        }

        /** Поля text/arrow/list/popup и порядок Resize подтверждены в исходнике SWT CCombo. */
        private static void installCheckSelectorInteraction(IViewPart view, State state, SearchBox search)
        {
            CCombo selector = state.checkSelector;
            // Нативный Button стрелки CCombo имеет собственную палитру Windows.
            // Его поведение остаётся, а видимая кнопка рисует тот же ресурс, что SearchBox.
            Canvas arrowFace = new Canvas(selector, SWT.DOUBLE_BUFFERED);
            arrowFace.setBackground(search.getBackground());
            arrowFace.setToolTipText(TooltipText.wrap(arrowFace,
                "Выбрать проверку" + Global.pluginSignForTooltip())); //$NON-NLS-1$
            arrowFace.addPaintListener(event ->
            {
                if (Boolean.TRUE.equals(arrowFace.getData("hot")) || selector.isFocusControl()) //$NON-NLS-1$
                {
                    Object painter = Global.getField(search, "borderPainter"); //$NON-NLS-1$
                    if (Global.invoke(painter, "getBorderColor") instanceof org.eclipse.swt.graphics.Color color) //$NON-NLS-1$
                    {
                        event.gc.setBackground(color);
                        var area = arrowFace.getClientArea();
                        event.gc.fillRectangle(0, 1, area.width, Math.max(0, area.height - 1));
                    }
                }
                paintSearchButton(arrowFace, search, event.gc, "downArrowImage"); //$NON-NLS-1$
            });
            arrowFace.addListener(SWT.MouseEnter, event ->
            {
                arrowFace.setData("hot", Boolean.TRUE); //$NON-NLS-1$
                arrowFace.redraw();
            });
            arrowFace.addListener(SWT.MouseExit, event ->
            {
                arrowFace.setData("hot", null); //$NON-NLS-1$
                arrowFace.redraw();
            });
            selector.addListener(SWT.FocusIn, event -> arrowFace.redraw());
            selector.addListener(SWT.FocusOut, event -> arrowFace.redraw());
            arrowFace.addListener(SWT.MouseUp, event ->
            {
                if (event.button != 1)
                    return;
                boolean opening = !selector.getListVisible();
                if (opening)
                    refreshCheckChoices(view, state);
                selector.setFocus();
                selector.setListVisible(opening);
            });
            Runnable positionClear = () ->
            {
                if (selector.isDisposed())
                    return;
                Object text = Global.getField(selector, "text"); //$NON-NLS-1$
                Object arrow = Global.getField(selector, "arrow"); //$NON-NLS-1$
                if (text instanceof Control field && arrow instanceof Control button)
                {
                    int height = selector.getClientArea().height;
                    Object size = Global.getField(search, "buttonSize"); //$NON-NLS-1$
                    int buttonWidth = size instanceof Integer width ? width : button.getBounds().width;
                    int arrowX = Math.max(0, selector.getClientArea().width - buttonWidth);
                    button.setBounds(arrowX, 0, buttonWidth, height);
                    arrowFace.setBounds(button.getBounds());
                    arrowFace.moveAbove(null);
                    int x = Math.max(0, arrowX - buttonWidth);
                    field.setBounds(0, 0, x, height);
                    state.clearCheckCode.setBounds(x, 0, buttonWidth, height);
                    state.clearCheckCode.moveAbove(null);
                }
            };
            // Штатный Resize CCombo уже разложил text/arrow; оставляем место крестика.
            selector.addListener(SWT.Resize, event -> positionClear.run());
            positionClear.run();
            org.eclipse.swt.widgets.Listener hover = event ->
            {
                if (event.widget != Global.getField(selector, "list") //$NON-NLS-1$
                    || !(event.widget instanceof org.eclipse.swt.widgets.List list))
                    return;
                var area = list.getClientArea();
                int index = event.x >= 0 && event.x < area.width && event.y >= 0 && event.y < area.height
                    ? list.getTopIndex() + event.y / list.getItemHeight() : -1;
                if (index >= 0 && index < list.getItemCount())
                    list.setSelection(index);
                // setSelection не посылает Selection: наведение не меняет отбор.
            };
            org.eclipse.swt.widgets.Listener commit = event ->
            {
                if (event.widget != Global.getField(selector, "list") //$NON-NLS-1$
                    || !(event.widget instanceof org.eclipse.swt.widgets.List list)
                    || !(event.type == SWT.MouseUp && event.button == 1
                        || event.type == SWT.KeyDown && event.character == SWT.CR))
                    return;
                int index = list.getSelectionIndex();
                CheckUid uid = index > 0 && index <= state.checkChoices.size()
                    ? state.checkChoices.get(index - 1).uid() : null;
                // После подсветки наведением Windows не посылает Selection при
                // клике в ту же строку. Подтверждаем её до штатного закрытия popup.
                if (index >= 0 && !java.util.Objects.equals(uid, state.checkUid))
                {
                    org.eclipse.swt.widgets.Event selection = new org.eclipse.swt.widgets.Event();
                    selection.time = event.time;
                    selection.stateMask = event.stateMask;
                    list.notifyListeners(SWT.Selection, selection);
                }
            };
            org.eclipse.swt.widgets.Listener closed = event ->
            {
                if (event.widget != Global.getField(selector, "popup")) //$NON-NLS-1$
                    return;
                updateCheckSelectorValue(state);
            };
            org.eclipse.swt.widgets.Listener opening = event ->
            {
                if (selector.getListVisible())
                    return;
                boolean arrow = event.type == SWT.Selection
                    && event.widget == Global.getField(selector, "arrow"); //$NON-NLS-1$
                boolean text = event.widget == Global.getField(selector, "text") //$NON-NLS-1$
                    && (event.type == SWT.MouseDown && event.button == 1
                        || event.type == SWT.KeyDown && (event.stateMask & SWT.ALT) != 0
                            && (event.keyCode == SWT.ARROW_UP || event.keyCode == SWT.ARROW_DOWN));
                if (arrow || text)
                    refreshCheckChoices(view, state);
            };
            Display display = selector.getDisplay();
            display.addFilter(SWT.MouseMove, hover);
            display.addFilter(SWT.MouseUp, commit);
            display.addFilter(SWT.KeyDown, commit);
            display.addFilter(SWT.Hide, closed);
            // До dropDown(): список и его размеры готовы к первому кадру popup.
            display.addFilter(SWT.Selection, opening);
            display.addFilter(SWT.MouseDown, opening);
            display.addFilter(SWT.KeyDown, opening);
            selector.addDisposeListener(event ->
            {
                display.removeFilter(SWT.MouseMove, hover);
                display.removeFilter(SWT.MouseUp, commit);
                display.removeFilter(SWT.KeyDown, commit);
                display.removeFilter(SWT.Hide, closed);
                display.removeFilter(SWT.Selection, opening);
                display.removeFilter(SWT.MouseDown, opening);
                display.removeFilter(SWT.KeyDown, opening);
            });
        }

        /** Ресурсы принадлежат SearchBox: повторно используем их, не создаём и не освобождаем. */
        private static void paintSearchButton(Canvas button, SearchBox search,
            org.eclipse.swt.graphics.GC gc, String imageField)
        {
            if (Global.getField(search, imageField) instanceof org.eclipse.swt.graphics.Image image
                && !image.isDisposed())
            {
                var area = button.getClientArea();
                var bounds = image.getBounds();
                gc.drawImage(image, (area.width - bounds.width) / 2, (area.height - bounds.height) / 2);
            }
        }

        private static SearchBox findProblemSearchBox(Composite parent)
        {
            for (Control child : parent.getChildren())
            {
                if (child instanceof SearchBox search)
                    return search;
                if (child instanceof Composite nested)
                {
                    SearchBox found = findProblemSearchBox(nested);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        /** Считаем только перед раскрытием и без нашего отбора по выбранной проверке. */
        private static void refreshCheckChoices(IViewPart view, State state)
        {
            if (state.checkSelector == null || state.checkSelector.isDisposed())
                return;
            ICheckRepository checks = Global.getOsgiService(ICheckRepository.class);
            List<CheckChoice> choices = new java.util.ArrayList<>();
            try
            {
                if (checks == null)
                    throw new IllegalStateException("Служба проверок недоступна"); //$NON-NLS-1$
                Object result = Global.invoke(view, "getMarkerFilter"); //$NON-NLS-1$
                Object readerValue = Global.getField(view, "markerReader"); //$NON-NLS-1$
                if (!(result instanceof MarkerFilter filter) || !(readerValue instanceof IMarkerReader reader))
                    throw new IllegalStateException("Панель проблем ещё не готова"); //$NON-NLS-1$
                if (Proxy.isProxyClass(reader.getClass())
                    && Proxy.getInvocationHandler(reader) instanceof ReaderTap tap)
                    reader = tap.origin;
                restoreModulePaths(view, filter);
                var descriptions = checks.getChecksWithDescriptions();
                Map<CheckUid, Long> counts = new LinkedHashMap<>();
                Set<Object> projects = filter.getValues(MarkerIndex.PROJECT);
                for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                {
                    if (!project.isOpen() || !projects.isEmpty() && !projects.contains(project))
                        continue;
                    MarkerFilter projectFilter = forCheckCountProject(filter, project);
                    int groupLimit = Math.max(1, descriptions.size() + 1);
                    var groups = reader.getGroupInfos(MarkerIndex.CHECK_ID, groupLimit, projectFilter);
                    while (groups.size() == groupLimit)
                    {
                        groupLimit = Math.multiplyExact(groupLimit, 2);
                        groups = reader.getGroupInfos(MarkerIndex.CHECK_ID, groupLimit, projectFilter);
                    }
                    for (var group : groups)
                    {
                        String code = group.getRepresentative().getCheckId();
                        CheckUid uid = code != null ? checks.getUidForShortUid(code, project) : null;
                        var internal = ValidationChecksFilterHook.getInternalBslCheck(code);
                        if (uid == null && internal != null)
                            uid = internal.getId();
                        if (uid != null && group.getSize() > 0)
                            counts.merge(uid, (long)group.getSize(), Long::sum);
                    }
                }
                counts.forEach((uid, count) ->
                {
                    var description = descriptions.get(uid);
                    var internal = ValidationChecksFilterHook.getInternalBslCheck(uid.getCheckId());
                    String title = description != null ? description.getTitle()
                        : internal != null ? internal.getTitle() : uid.getCheckId();
                    choices.add(new CheckChoice(uid, title + " [" + uid.getCheckId() + "] — " + count)); //$NON-NLS-1$ //$NON-NLS-2$
                });
            }
            catch (RuntimeException error)
            {
                ToastNotification.show("Отбор по проверке", "Не удалось посчитать проблемы: " + error.getMessage(), 8_000); //$NON-NLS-1$ //$NON-NLS-2$
            }
            choices.sort(java.util.Comparator.comparing(CheckChoice::label, String.CASE_INSENSITIVE_ORDER));
            boolean changed = !state.checkChoices.equals(choices) || state.checkSelector.getItemCount() == 0;
            if (changed)
            {
                state.checkChoices = List.copyOf(choices);
                state.checkSelector.removeAll();
                state.checkSelector.add("Все проверки"); //$NON-NLS-1$
                for (CheckChoice choice : choices)
                    state.checkSelector.add(choice.label());
            }
            updateCheckSelectorValue(state);
        }

        private static MarkerFilter forCheckCountProject(MarkerFilter source, IProject project)
        {
            MarkerFilter filter = MarkerFilter.createProjectFilter(project).setSearchPattern(source.getSearchPattern());
            for (MarkerIndex index : source.getIndexes())
            {
                if (index != MarkerIndex.PROJECT)
                    for (Object value : source.getValues(index))
                        filter.addValue(index, value);
                source.getConditions(index).getValues().forEach((value, dependent) ->
                {
                    if (index != MarkerIndex.PROJECT || project.equals(value))
                        dependent.indexes().forEach(dependentIndex ->
                        {
                            for (Object dependentValue : dependent.getValues(dependentIndex))
                                filter.addCondition(index, value, dependentIndex, dependentValue);
                        });
                });
            }
            return filter;
        }

        /** Обновление закрытого поля не запускает подсчёт и не добавляет нулевые строки в список. */
        private static void updateCheckSelectorValue(State state)
        {
            if (state.checkSelector == null || state.checkSelector.isDisposed())
                return;
            int selected = state.checkUid == null ? 0 : -1;
            for (int i = 0; i < state.checkChoices.size(); i++)
            {
                CheckChoice choice = state.checkChoices.get(i);
                if (choice.uid().equals(state.checkUid))
                    selected = i + 1;
            }
            if (selected >= 0)
                state.checkSelector.select(selected);
            else
            {
                state.checkSelector.deselectAll();
                ICheckRepository checks = Global.getOsgiService(ICheckRepository.class);
                var description = checks != null ? checks.getChecksWithDescriptions().get(state.checkUid) : null;
                var internal = ValidationChecksFilterHook.getInternalBslCheck(state.checkUid.getCheckId());
                String title = description != null ? description.getTitle()
                    : internal != null ? internal.getTitle() : state.checkUid.getCheckId();
                state.checkSelector.setText(title + " [" + state.checkUid.getCheckId() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            // Меняем только закрытое поле: строки выпадающего списка остаются обычного цвета.
            if (Global.getField(state.checkSelector, "text") instanceof Control field) //$NON-NLS-1$
                field.setForeground(state.checkUid == null ? state.checkMessageColor : state.checkTextColor);
            state.clearCheckCode.setEnabled(state.checkId != null);
            // Canvas не меняет нарисованное изображение при setEnabled(): обновляем явно.
            state.clearCheckCode.redraw();
        }

        private static void setCheckCodeFromSelector(IViewPart view, CheckUid code)
        {
            try
            {
                setCheckCode(view, code);
            }
            catch (RuntimeException error)
            {
                ToastNotification.show("Отбор по проверке", error.getMessage(), 8_000); //$NON-NLS-1$
            }
        }

        /**
         * LazyTreeNode получает свой читатель напрямую из V8UiValidationPlugin,
         * в обход markerReader панели. Его отбор берётся из RootNode.treeFilter.
         * Поле и unload() подтверждены в LazyProblemView$RootNode / LazyTreeNode.
         */
        private static void applyRootCheckCode(IViewPart view, State state, boolean force)
        {
            TreeViewer viewer = view.getAdapter(TreeViewer.class);
            Object root = viewer != null ? viewer.getInput() : null;
            if (viewer == null || viewer.getTree().isDisposed() || root == null
                || Global.getField(root, "this$0") != view) //$NON-NLS-1$
                return;
            if (root != state.checkCodeRoot)
            {
                Object source = Global.getField(root, "treeFilter"); //$NON-NLS-1$
                if (!(source instanceof MarkerFilter filter))
                    return;
                state.checkCodeRoot = root;
                state.checkCodeRootSource = filter;
                state.checkCodeRootApplied = null;
            }
            String checkId = state.checkId;
            if (java.util.Objects.equals(checkId, state.checkCodeRootApplied)
                && (!force || checkId == null))
                return;
            MarkerFilter wanted = checkId != null ? applyCheckCode(state.checkCodeRootSource, state.checkCodes)
                : state.checkCodeRootSource;
            boolean installed = Global.setFieldForce(root, "treeFilter", wanted); //$NON-NLS-1$
            if (!installed)
                return;
            // Сохраняем исходный фильтр отдельно: смена A → B и снятие отбора
            // не пересекаются с кодом, ранее установленным в этом же корне.
            state.checkCodeRootApplied = checkId;
            boolean unloaded = Global.invokeVoid(root, "unload"); //$NON-NLS-1$
            if (unloaded)
            {
                // Штатный LazyTreeNodeContentProvider сбрасывает виртуальные строки
                // через setHasChildren(false), затем заново запрашивает их число.
                // refresh() сохраняет старые TreeItem и их уже загруженные узлы.
                viewer.setHasChildren(root, false);
                boolean hasChildren = Boolean.TRUE.equals(Global.invoke(root, "hasChildren")); //$NON-NLS-1$
                viewer.setHasChildren(root, hasChildren);
            }
        }

        static void setCheckCode(IViewPart view, CheckUid uid)
        {
            String checkId = uid != null ? uid.toString() : null;
            State state = stateOf(view);
            if (state == null)
                throw new IllegalStateException("Панель проблем ещё не готова"); //$NON-NLS-1$
            installCheckSelector(view, state);
            if (checkId != null && (state.checkSelector == null || state.checkSelector.isDisposed()))
                throw new IllegalStateException("Поле отбора по проверке недоступно"); //$NON-NLS-1$
            // Снятие отбора не должно зависеть от подключения читателя:
            // обёртка читает checkId при каждом запросе, null снимает ограничение сразу.
            if (checkId == null)
            {
                state.checkId = null;
                state.checkUid = null;
                state.checkCodes = Map.of();
            }
            else
                wrapMarkerManager(view);
            Object reader = Global.getField(view, "markerReader"); //$NON-NLS-1$
            if (checkId != null && (reader == null || !Proxy.isProxyClass(reader.getClass())
                || !(Proxy.getInvocationHandler(reader) instanceof ReaderTap)))
                throw new IllegalStateException("Отбор панели проблем не подключён"); //$NON-NLS-1$
            Object updateListener = Global.getField(view, "updateListener"); //$NON-NLS-1$
            if (checkId != null && !(updateListener instanceof DelayedMarkerUpdateListener))
                throw new IllegalStateException("Не удалось обновить панель проблем"); //$NON-NLS-1$
            Map<IProject, String> checkCodes = new LinkedHashMap<>();
            if (uid != null)
            {
                if (ValidationChecksFilterHook.isInternalBslCheckUid(uid))
                {
                    // Внутренний код одинаков во всех проектах; короткого UID в репозитории EDT нет.
                    for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                        checkCodes.put(project, uid.getCheckId());
                }
                else
                {
                    ICheckRepository checks = Global.getOsgiService(ICheckRepository.class);
                    if (checks == null)
                        throw new IllegalStateException("Служба проверок недоступна"); //$NON-NLS-1$
                    checks.getShortUid(uid).forEach((project, code) ->
                        checkCodes.put(project.getWorkspaceProject(), code));
                }
            }
            boolean mappingChanged = !state.checkCodes.equals(checkCodes);
            state.checkCodes = Map.copyOf(checkCodes);
            state.checkUid = uid;
            state.checkId = checkId;
            applyRootCheckCode(view, state, mappingChanged);
            updateCheckSelectorValue(state);
            if (updateListener instanceof DelayedMarkerUpdateListener listener)
                listener.scheduleUpdateJob(true);
        }

        /** Тот же фильтр используется для итогов и всех уровней дерева. */
        private static void applyCheckCode(IViewPart view, Object[] args)
        {
            State state = stateOf(view);
            String checkId = state != null ? state.checkId : null;
            if (checkId == null || args == null)
                return;
            for (int index = 0; index < args.length; index++)
            {
                if (args[index] instanceof MarkerFilter filter)
                    args[index] = applyCheckCode(filter, state.checkCodes);
                else if (args[index] instanceof MarkerFilter[] filters)
                {
                    MarkerFilter[] narrowed = filters.clone();
                    for (int position = 0; position < narrowed.length; position++)
                        narrowed[position] = applyCheckCode(narrowed[position], state.checkCodes);
                    args[index] = narrowed;
                }
            }
        }

        private static MarkerFilter applyCheckCode(MarkerFilter filter, Map<IProject, String> projectCodes)
        {
            if (filter == null)
                return null;
            Set<Object> codes = filter.getValues(MarkerIndex.CHECK_ID);
            Set<Object> projects = filter.getValues(MarkerIndex.PROJECT);
            // Фильтры EDT живут в узлах и отложенных запросах дерева. Нельзя добавлять
            // в них код на месте: прежний код останется после смены/снятия нашего отбора.
            MarkerFilter narrowed = new MarkerFilter().setSearchPattern(filter.getSearchPattern());
            for (MarkerIndex index : filter.getIndexes())
            {
                if (index != MarkerIndex.CHECK_ID)
                    for (Object value : filter.getValues(index))
                        narrowed.addValue(index, value);
                MarkerFilter.Conditions conditions = filter.getConditions(index);
                conditions.getValues().forEach((value, dependent) ->
                {
                    if (index != MarkerIndex.CHECK_ID)
                        dependent.indexes().forEach(dependentIndex ->
                        {
                            for (Object dependentValue : dependent.getValues(dependentIndex))
                                narrowed.addCondition(index, value, dependentIndex, dependentValue);
                        });
                });
            }
            // ShortUid — локальный код проекта, а не идентификатор проверки.
            // Условие связывает каждый код с его проектом: совпавший SUxx другого
            // проекта не подмешивает чужую проверку. API и пары подтверждены в QueryBuilder.
            boolean matched = false;
            for (Map.Entry<IProject, String> entry : projectCodes.entrySet())
            {
                IProject project = entry.getKey();
                String code = entry.getValue();
                if ((!codes.isEmpty() && !codes.contains(code))
                    || (!projects.isEmpty() && !projects.contains(project)))
                    continue;
                var dependent = filter.getConditions(MarkerIndex.CHECK_ID).getValues().get(code);
                if (dependent != null && !dependent.getValues(MarkerIndex.PROJECT).isEmpty()
                    && !dependent.getValues(MarkerIndex.PROJECT).contains(project))
                    continue;
                narrowed.addCondition(MarkerIndex.CHECK_ID, code, MarkerIndex.PROJECT, project);
                matched = true;
            }
            return matched ? narrowed : MarkerFilter.createAlwaysFalseFilter();
        }

        static void setWaitingForIndex(IViewPart view, boolean waiting)
        {
            State state = stateOf(view);
            if (state == null)
                return;
            if (state.waitingForIndex == waiting)
            {
                if (waiting)
                    applyAll();
                return;
            }
            state.waitingForIndex = waiting;
            applyAll();
        }

        static void rememberLiveModuleIds(IViewPart view, Set<Object> numbers, Set<Object> paths)
        {
            State state = stateOf(view);
            if (state == null)
                return;
            state.liveNumericIds = numbers == null ? Set.of() : Set.copyOf(numbers);
            state.liveModulePaths = paths == null ? Set.of() : Set.copyOf(paths);
        }

        private static State stateOf(IViewPart view)
        {
            synchronized (views)
            {
                return views.get(view);
            }
        }

        private static MarkerFilter unwrapFilter(Object arg)
        {
            if (arg instanceof MarkerFilter filter)
                return filter;
            if (arg instanceof MarkerFilter[] filters)
                return filters.length > 0 ? filters[0] : null;
            return null;
        }

        private static boolean hasResourcePath(Set<Object> ids)
        {
            if (ids == null)
                return false;
            for (Object id : ids)
            {
                if (id instanceof String)
                    return true;
            }
            return false;
        }

        private static Set<Object> numericIds(Set<Object> ids)
        {
            LinkedHashSet<Object> numbers = new LinkedHashSet<>();
            if (ids == null)
                return numbers;
            for (Object id : ids)
            {
                if (id instanceof Long || id instanceof Integer)
                    numbers.add(id);
            }
            return numbers;
        }

        private static Set<String> projectNames(Set<Object> values)
        {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if (values == null)
                return names;
            for (Object value : values)
            {
                if (value instanceof IProject project)
                    names.add(project.getName());
                else if (value != null)
                    names.add(String.valueOf(value));
            }
            return names;
        }

        private static boolean argsContainMarkerFilter(Object[] args)
        {
            if (args == null)
                return false;
            for (Object arg : args)
            {
                if (arg instanceof MarkerFilter || arg instanceof MarkerFilter[])
                    return true;
            }
            return false;
        }

        /**
         * Журнал 08.09.2026: полный отбор {@code OBJECT_ID=id + путь Module.bsl} даёт
         * маркеры, усечённый {@code OBJECT_ID=id} — ноль. {@code updateViewer} уже
         * держит тот же экземпляр фильтра: добавляем недостающие пути в него до
         * запроса индекса. Нужно и для {@code markers}/{@code getGroupInfos}: иначе
         * шапка считает 1, а дерево пустое.
         */
        private static void restoreModulePaths(IViewPart view, Object[] args)
        {
            if (args == null)
                return;
            for (Object arg : args)
            {
                if (arg instanceof MarkerFilter[] filters)
                {
                    for (MarkerFilter filter : filters)
                        restoreModulePaths(view, filter);
                }
                else
                    restoreModulePaths(view, unwrapFilter(arg));
            }
        }

        private static void restoreModulePaths(IViewPart view, MarkerFilter filter)
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled() || filter == null)
                return;
            if (String.valueOf(filter).equals(ResultChangeGate.alwaysFalseDescription()))
                return;
            State state = stateOf(view);
            if (state == null)
                return;
            Set<Object> current = filter.getValues(MarkerIndex.OBJECT_ID);
            if (current == null || current.isEmpty() || hasResourcePath(current))
                return;
            Set<Object> last = state.lastGoodObjectIds;
            Set<Object> live = state.liveModulePaths;
            if (live == null)
                live = Set.of();
            if (!hasResourcePath(last) && !hasResourcePath(live))
                return;
            Set<Object> currentIds = numericIds(current);
            Set<Object> lastIds = numericIds(last);
            Set<Object> liveIds = state.liveNumericIds;
            if (liveIds == null)
                liveIds = Set.of();
            boolean sameLast = !currentIds.isEmpty() && !lastIds.isEmpty() && lastIds.containsAll(currentIds);
            boolean sameLive = hasResourcePath(live);
            if (sameLive && !liveIds.isEmpty())
            {
                sameLive = false;
                for (Object id : currentIds)
                {
                    if (liveIds.contains(id))
                    {
                        sameLive = true;
                        break;
                    }
                }
            }
            if (!sameLast && !sameLive)
                return;
            Set<Object> source = sameLast && hasResourcePath(last) ? last : live;
            Set<String> lastProjects = state.lastGoodProjects;
            Set<String> currentProjects = projectNames(filter.getValues(MarkerIndex.PROJECT));
            if (sameLast && !lastProjects.isEmpty() && !currentProjects.isEmpty())
            {
                boolean sameProject = false;
                for (String name : currentProjects)
                {
                    if (lastProjects.contains(name))
                    {
                        sameProject = true;
                        break;
                    }
                }
                if (!sameProject)
                    return;
            }
            for (Object id : source)
            {
                if (!current.contains(id))
                    filter.addValue(MarkerIndex.OBJECT_ID, id);
            }
        }

        private static void rememberGoodFilter(IViewPart view, Object filterArg, boolean empty)
        {
            if (Boolean.TRUE.equals(PEEKING.get()))
                return;
            if (empty)
                return;
            State state = stateOf(view);
            MarkerFilter filter = unwrapFilter(filterArg);
            if (state == null || filter == null)
                return;
            Set<Object> ids = filter.getValues(MarkerIndex.OBJECT_ID);
            if (!hasResourcePath(ids))
                return;
            state.lastGoodObjectIds = Set.copyOf(ids);
            state.lastGoodProjects = Set.copyOf(projectNames(filter.getValues(MarkerIndex.PROJECT)));
        }

        /**
         * {@code getMarkerInfo} вернул 0 за нули миллисекунд — Job сейчас запишет
         * «0 элементов» как готовый итог. По журналу это часто отбор, который ещё
         * не снялся; клик в модуле тогда заново вызывает {@code selectionChanged} и
         * список появляется. Делаем то же без клика, один раз.
         */
        private static void onEmptyMarkerInfo(IViewPart view, Object filterArg, boolean empty)
        {
            if (Boolean.TRUE.equals(PEEKING.get()))
                return;
            State state = stateOf(view);
            if (state == null)
                return;
            if (!empty)
            {
                if (state.retryingEmpty || state.retriedEmpty)
                {
                    state.retryingEmpty = false;
                    state.retriedEmpty = false;
                    applyAll();
                }
                return;
            }
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            MarkerFilter filter = unwrapFilter(filterArg);
            boolean alwaysFalse = filter != null
                && String.valueOf(filter).equals(ResultChangeGate.alwaysFalseDescription());
            if (!alwaysFalse)
            {
                state.retryingEmpty = false;
                state.retriedEmpty = false;
                return;
            }
            if (state.retriedEmpty)
            {
                state.retryingEmpty = false;
                state.retriedEmpty = false;
                applyAll();
                return;
            }
            state.retryingEmpty = true;
            state.retriedEmpty = true;
            applyAll();
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
            {
                state.retryingEmpty = false;
                state.retriedEmpty = false;
                return;
            }
            display.asyncExec(() -> retryFromEditor(view, state));
        }

        private static void retryFromEditor(IViewPart view, State state)
        {
            if (view.getSite() == null)
            {
                state.retryingEmpty = false;
                state.retriedEmpty = false;
                return;
            }
            try
            {
                IEditorPart editor = view.getSite().getPage().getActiveEditor();
                if (editor != null)
                {
                    ISelection selection = null;
                    if (editor.getSite() != null && editor.getSite().getSelectionProvider() != null)
                        selection = editor.getSite().getSelectionProvider().getSelection();
                    Global.invoke(view, "selectionChanged", editor, selection); //$NON-NLS-1$
                }
                Object listener = Global.getField(view, "updateListener"); //$NON-NLS-1$
                if (listener == null)
                {
                    state.retryingEmpty = false;
                    state.retriedEmpty = false;
                    return;
                }
                Global.invoke(listener, "scheduleUpdateJob", Boolean.TRUE); //$NON-NLS-1$
            }
            catch (RuntimeException ignored)
            {
                state.retryingEmpty = false;
                state.retriedEmpty = false;
            }
        }

        private static final class ManagerTap implements InvocationHandler
        {
            private final IMarkerManagerV2 origin;

            private final IViewPart view;

            ManagerTap(IMarkerManagerV2 origin, IViewPart view)
            {
                this.origin = origin;
                this.view = view;
            }

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                throws Throwable
            {
                if (method.getDeclaringClass() == Object.class)
                    return method.invoke(origin, args);
                try
                {
                    Object result = method.invoke(origin, args == null ? new Object[0] : args);
                    if (result instanceof IMarkerReader reader
                        && !(Proxy.isProxyClass(reader.getClass())
                            && Proxy.getInvocationHandler(reader) instanceof ReaderTap))
                    {
                        return Proxy.newProxyInstance(IMarkerReader.class.getClassLoader(),
                            new Class<?>[] { IMarkerReader.class }, new ReaderTap(reader, view));
                    }
                    return result;
                }
                catch (InvocationTargetException e)
                {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtime)
                        throw runtime;
                    if (cause instanceof Error error)
                        throw error;
                    throw e;
                }
            }
        }

        private static final class ReaderTap implements InvocationHandler
        {
            private final IMarkerReader origin;

            private final IViewPart view;

            ReaderTap(IMarkerReader origin, IViewPart view)
            {
                this.origin = origin;
                this.view = view;
            }

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                throws Throwable
            {
                if (method.getDeclaringClass() == Object.class)
                    return method.invoke(origin, args);
                try
                {
                    if (argsContainMarkerFilter(args))
                    {
                        restoreModulePaths(view, args);
                        applyCheckCode(view, args);
                    }
                    Object result = method.invoke(origin, args == null ? new Object[0] : args);
                    if ("getMarkerInfo".equals(method.getName()) && result instanceof IMarkerInfo info) //$NON-NLS-1$
                    {
                        Object filterArg = args != null && args.length > 0 ? args[0] : null;
                        boolean empty = info.getTotalCount() == 0;
                        rememberGoodFilter(view, filterArg, empty);
                        onEmptyMarkerInfo(view, filterArg, empty);
                    }
                    return result;
                }
                catch (InvocationTargetException e)
                {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtime)
                        throw runtime;
                    if (cause instanceof Error error)
                        throw error;
                    throw e;
                }
            }
        }
    }

    /**
     * Описание проверки (HTML) на странице «Проверки» параметров проекта рисуется
     * через {@code Browser.execute("document.body.innerHTML = …")}. При открытии
     * страницы с уже выбранной проверкой ({@code applyData}) описание пустое: EDT
     * вызывает {@code execute} сразу после {@code setText} начального документа, а
     * до окончания его загрузки {@code execute} молча ничего не делает. Повторяем
     * отрисовку по событию загрузки документа — состояние {@code CheckViewer}
     * (проект и настройка проверки) к этому моменту уже проставлено.
     *
     * <p>Диалог сюда приходит уже созданным: {@code PropertyDialog.createDialogOn}
     * внутри {@link PreferencesUtil#createPropertyDialogOn} сам вызывает
     * {@code create()}. Повторный {@code create()} создаёт второй shell и теряет
     * выбранную страницу вместе с отбором по проекту — вызывать его нельзя.
     * Поэтому, кроме слушателя, планируем и разовую перерисовку в очереди UI:
     * документ мог загрузиться ещё до установки слушателя.
     */
    private static final class CheckDescriptionRefresh
    {
        private CheckDescriptionRefresh() {}

        static void install(PreferenceDialog dialog)
        {
            Object page = dialog.getSelectedPage();
            if (!(page instanceof IPreferencePage preferencePage))
                return;
            Browser browser = findBrowser(preferencePage.getControl());
            if (browser == null)
            {
                Debug.log("CheckDescriptionRefresh: no browser on checks page"); //$NON-NLS-1$
                return;
            }
            Composite checkViewer = browser.getParent();
            Runnable refresh = () -> {
                if (!browser.isDisposed())
                    Global.invokeVoid(checkViewer, "updateHtmlContent"); //$NON-NLS-1$
            };
            browser.addProgressListener(ProgressListener.completedAdapter(e -> refresh.run()));
            browser.getDisplay().asyncExec(refresh);
        }

        private static Browser findBrowser(Control control)
        {
            if (control instanceof Browser browser)
                return browser;
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                {
                    Browser found = findBrowser(child);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }
    }

    private static Marker markerOf(Object element)
    {
        if (element instanceof Marker marker)
            return marker;
        Object marker = Global.invoke(element, "getMarker"); //$NON-NLS-1$
        return marker instanceof Marker m ? m : null;
    }

    /** Переход из результатов поиска ссылок на проверку. */
    static void openSuppressionSettings(IProject project, String targetFqn, String containmentFqn, CheckUid uid)
    {
        SuppressionNotification.openDialog(project, targetFqn, containmentFqn, uid);
    }

    /** Уведомление о модельных подавлениях, которые не видны сразу в тексте модуля. */
    private static final class SuppressionNotification implements IExecutionListener
    {
        private List<Marker> pending = List.of();

        static void install(IWorkbench workbench)
        {
            ICommandService commands = workbench.getService(ICommandService.class);
            if (commands != null)
                commands.addExecutionListener(new SuppressionNotification());
        }

        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!SUPPRESS_ISSUE_COMMAND_ID.equals(commandId))
                return;
            pending = List.of();
            IWorkbenchPart part = HandlerUtil.getActivePart(event);
            java.util.stream.Stream<?> selected = ProblemViewMarkers.selectedMarkers(part);
            if (selected != null)
                pending = selected.filter(Marker.class::isInstance).map(Marker.class::cast)
                    .filter(com._1c.g5.v8.dt.validation.marker.BmObjectMarker.class::isInstance)
                    .toList();
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            if (!SUPPRESS_ISSUE_COMMAND_ID.equals(commandId))
                return;
            List<Marker> markers = pending;
            pending = List.of();
            if (markers.isEmpty())
                return;
            ICheckRepository checks = Global.getOsgiService(ICheckRepository.class);
            if (checks == null)
                return;
            for (Marker marker : markers)
            {
                try
                {
                    IProject project = marker.getProject();
                    CheckUid uid = project != null
                        ? checks.getUidForShortUid(marker.getCheckId(), project) : null;
                    if (uid == null)
                        continue;
                    String targetFqn = marker.provideObject((java.util.function.Function<EObject, String>)
                        object -> object instanceof IBmObject bm && bm.bmGetTopObject() != null
                            ? bm.bmGetTopObject().bmGetFqn() : null);
                    String containmentFqn = marker.provideObject((java.util.function.Function<EObject, String>)
                        object -> object instanceof IBmObject bm ? containmentFqn(bm) : null);
                    if (targetFqn != null)
                        notifyWhenApplied(marker, project, targetFqn, containmentFqn, uid, 0);
                }
                catch (RuntimeException e)
                {
                }
            }
        }

        private static void notifyWhenApplied(Marker marker, IProject project, String targetFqn,
            String containmentFqn, CheckUid uid, int attempt)
        {
            Display.getDefault().timerExec(attempt == 0 ? 0 : 100, () ->
            {
                Boolean applied = Boolean.FALSE;
                try
                {
                    ISuppressSettingsManager suppressions = Global.getOsgiService(ISuppressSettingsManager.class);
                    if (suppressions != null)
                        applied = marker.provideObject((java.util.function.Function<EObject, Boolean>) object ->
                            suppressions.isSuppressed(object, uid.getCheckId(), uid.getContributorId()));
                }
                catch (RuntimeException e)
                {
                }
                if (Boolean.TRUE.equals(applied))
                    show(marker.getMessage(), project, targetFqn, containmentFqn, uid);
                else if (attempt < 20)
                    notifyWhenApplied(marker, project, targetFqn, containmentFqn, uid, attempt + 1);
            });
        }

        private static String containmentFqn(IBmObject object)
        {
            try
            {
                Bundle bundle = Platform.getBundle("com.e1c.g5.v8.dt.check.suppress"); //$NON-NLS-1$
                Class<?> util = bundle.loadClass("com.e1c.g5.v8.dt.check.suppress.util.SuppressUtil"); //$NON-NLS-1$
                return (String)util.getMethod("getContainmentFqnByTarget", IBmObject.class) //$NON-NLS-1$
                    .invoke(null, object);
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                return null;
            }
        }

        private static void show(String problem, IProject project, String targetFqn,
            String containmentFqn, CheckUid uid)
        {
            String objectName = MdTypeMapping.bmFqnToRuFullName(targetFqn);
            String message = "Подавлена проверка: " + problem + "\nОбъект: " //$NON-NLS-1$ //$NON-NLS-2$
                + (objectName != null ? objectName : targetFqn);
            ToastNotification.show("Подавление проверки", message, 5_000, //$NON-NLS-1$
                () -> openDialog(project, targetFqn, containmentFqn, uid),
                "Открыть подавление"); //$NON-NLS-1$
        }

        private static void openDialog(IProject project, String targetFqn, String containmentFqn,
            CheckUid uid)
        {
            try
            {
                IV8ProjectManager projects = Global.getOsgiService(IV8ProjectManager.class);
                IBmModelManager models = Global.getOsgiService(IBmModelManager.class);
                ICheckRepository checks = Global.getOsgiService(ICheckRepository.class);
                IV8Project v8Project = projects != null ? projects.getProject(project) : null;
                if (v8Project == null || models == null || checks == null)
                    throw new IllegalStateException("Службы EDT недоступны"); //$NON-NLS-1$
                EObject target = "Configuration".equals(targetFqn) //$NON-NLS-1$
                    && v8Project instanceof com._1c.g5.v8.dt.core.platform.IConfigurationProject configurationProject
                        ? configurationProject.getConfiguration()
                        : GoToDefinition.resolveEObjectByQualifiedName(targetFqn, v8Project);
                if (!(target instanceof MdObject))
                    throw new IllegalStateException("Объект настройки не найден: " + targetFqn); //$NON-NLS-1$
                EStructuralFeature feature = target.eClass().getEAllStructuralFeatures().stream()
                    .filter(MdUtil::isSuppressSettingsFeature).findFirst().orElse(null);
                if (feature == null)
                    throw new IllegalStateException("Свойство подавлений не найдено: " + targetFqn); //$NON-NLS-1$
                IWorkbenchPage editorPage = activateOpenEditor(project, targetFqn);
                Bundle ui = Platform.getBundle("com.e1c.g5.v8.dt.check.suppress.ui"); //$NON-NLS-1$
                Bundle mdUi = Platform.getBundle("com._1c.g5.v8.dt.md.ui"); //$NON-NLS-1$
                if (ui == null || mdUi == null)
                    throw new IllegalStateException("Бандл диалога подавлений недоступен"); //$NON-NLS-1$
                Object model = Global.newInstance(ui.loadClass(
                    "com.e1c.g5.v8.dt.check.suppress.ui.internal.aef.models.BmSuppressionSettingsLinkModel"), //$NON-NLS-1$
                    target, feature, v8Project, models, checks);
                if (model == null)
                    throw new IllegalStateException("Модель диалога подавлений не создана"); //$NON-NLS-1$
                Object component = Global.newInstance(ui.loadClass(
                    "com.e1c.g5.v8.dt.check.suppress.ui.internal.aef.components.SuppressionSettingsDialogComponent"), //$NON-NLS-1$
                    model);
                Object engine = createEngine(mdUi);
                if (component == null || engine == null)
                    throw new IllegalStateException("Компонент диалога подавлений не создан"); //$NON-NLS-1$
                installEngineDisposal(mdUi, component, engine, () ->
                {
                    if (editorPage != null)
                        ConfigSearchResultsHook.PropertyFieldFocus.scheduleExact(editorPage, target, feature);
                });
                Object scene = Global.invoke(engine, "showDialog", component, model); //$NON-NLS-1$
                if (scene == null)
                {
                    Global.invokeVoid(engine, "dispose"); //$NON-NLS-1$
                    throw new IllegalStateException("EDT не создала сцену диалога"); //$NON-NLS-1$
                }
                selectSuppression(component, model, uid, containmentFqn, 0);
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                ToastNotification.show("Подавление проверки", //$NON-NLS-1$
                    "Не удалось открыть настройку подавления: " + e.getMessage(), 8_000); //$NON-NLS-1$
            }
        }

        /** Показывает свойство только через уже открытый редактор объекта того же проекта. */
        private static IWorkbenchPage activateOpenEditor(IProject project, String targetFqn)
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IEditorPart editor = ref.getEditor(false);
                    if (!(editor instanceof DtGranularEditor<?>)
                        || !project.equals(Global.getActiveProject(editor, false)))
                        continue;
                    IFile editorFile = editor.getEditorInput().getAdapter(IFile.class);
                    String editorRef = editorFile != null
                        ? GoToDefinition.fullNameFromFile(editorFile) : null;
                    if (editorRef == null)
                        editorRef = GetRef.getRefFromEditor(editor);
                    String editorFqn = editorRef != null
                        ? MdTypeMapping.anyFullNameToBmFqn(editorRef) : null;
                    if (editorFqn == null || !targetFqn.equalsIgnoreCase(editorFqn))
                        continue;
                    try
                    {
                        page.activate(editor);
                        page.showView(IPageLayout.ID_PROP_SHEET);
                        return page;
                    }
                    catch (PartInitException | RuntimeException e)
                    {
                        return null;
                    }
                }
            }
            return null;
        }

        private static void installEngineDisposal(Bundle bundle, Object component, Object engine,
            Runnable afterClose)
            throws ReflectiveOperationException
        {
            Class<?> listenerClass = bundle.loadClass("com._1c.g5.aef2.events.IEventChannelListener"); //$NON-NLS-1$
            InvocationHandler handler = (proxy, method, args) ->
            {
                if ("eventReceived".equals(method.getName()) && args != null && args.length == 1) //$NON-NLS-1$
                {
                    String eventName = args[0].getClass().getSimpleName();
                    if ("CommitEvent".equals(eventName) || "DiscardEvent".equals(eventName)) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        Global.invokeVoid(engine, "dispose"); //$NON-NLS-1$
                        Display.getDefault().asyncExec(afterClose);
                    }
                }
                return null;
            };
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[] { listenerClass }, handler);
            Global.invokeVoid(component, "addListener", listener); //$NON-NLS-1$
        }

        private static Object createEngine(Bundle bundle) throws ReflectiveOperationException
        {
            Object engine = Global.invoke(bundle.loadClass("com._1c.g5.aef2.engines.EngineFactory"), //$NON-NLS-1$
                "createEngine", "Comfort.SuppressionDialog"); //$NON-NLS-1$ //$NON-NLS-2$
            if (engine == null)
                return null;
            registerRenderer(bundle, engine, "com._1c.g5.v8.dt.md.ui.aef.swt.MdSwtRenderer", //$NON-NLS-1$
                "com._1c.g5.aef2.swt.renderers.SwtRenderingParameters"); //$NON-NLS-1$
            registerRenderer(bundle, engine, "com._1c.g5.v8.dt.ui.aef.swt.SwtMdDialogRenderer", //$NON-NLS-1$
                "com._1c.g5.aef2.swt.renderers.SwtDialogRenderingParameters"); //$NON-NLS-1$
            registerRenderer(bundle, engine, "com._1c.g5.v8.dt.md.ui.aef.lwt.MdLwtRenderer", //$NON-NLS-1$
                "com._1c.g5.aef2.lwt.LwtRenderingParameters"); //$NON-NLS-1$
            registerRenderer(bundle, engine, "com._1c.g5.v8.dt.ui.aef.lwt.LwtMdDialogRenderer", //$NON-NLS-1$
                "com._1c.g5.aef2.lwt.LwtDialogRenderingParameters"); //$NON-NLS-1$
            Object heavy = Global.invoke(bundle.loadClass("com._1c.g5.aef2.utils.Aef2Utils"), //$NON-NLS-1$
                "areHeavyControlsPreferred"); //$NON-NLS-1$
            Class<?> dialogRenderer = bundle.loadClass(Boolean.TRUE.equals(heavy)
                ? "com._1c.g5.v8.dt.ui.aef.swt.SwtMdDialogRenderer" //$NON-NLS-1$
                : "com._1c.g5.v8.dt.ui.aef.lwt.LwtMdDialogRenderer"); //$NON-NLS-1$
            Global.invoke(engine, "setDialogRenderer", dialogRenderer); //$NON-NLS-1$
            return engine;
        }

        private static void registerRenderer(Bundle bundle, Object engine, String renderer,
            String parameters) throws ReflectiveOperationException
        {
            Global.invoke(engine, "registerRenderer", bundle.loadClass(renderer), //$NON-NLS-1$
                bundle.loadClass(parameters));
        }

        private static void selectSuppression(Object component, Object model, CheckUid uid,
            String containmentFqn, int attempt)
        {
            Display.getDefault().timerExec(attempt == 0 ? 0 : 100, () ->
            {
                Object tree = Global.getField(component, "treeComponent"); //$NON-NLS-1$
                Object provider = Global.invoke(model, "getSuppressionsProvider"); //$NON-NLS-1$
                String fqn = containmentFqn != null ? containmentFqn
                    : (String)Global.invoke(model, "getSuppressTopObjectFqn"); //$NON-NLS-1$
                Object entry = suppressionEntry(provider, fqn, uid);
                SuppressionSelection selection = new SuppressionSelection(false, false);
                if (tree != null && entry != null)
                {
                    Global.invokeVoid(model, "setSelection", entry); //$NON-NLS-1$
                    Global.invokeVoid(tree, "setSelection", List.of(entry)); //$NON-NLS-1$
                    selection = selectNativeSuppression(tree, entry);
                }
                if ((!selection.selected() || !selection.focused()) && attempt < 30)
                    selectSuppression(component, model, uid, containmentFqn, attempt + 1);
                else if (!selection.selected())
                    ToastNotification.show("Подавление проверки", //$NON-NLS-1$
                        "Диалог открыт, но строку проверки не удалось выделить", 8_000); //$NON-NLS-1$
            });
        }

        /** Выделяем отображаемую модель и проверяем реальное выделение SWT, а не постановку события AEF в очередь. */
        private record SuppressionSelection(boolean selected, boolean focused) {}

        private static SuppressionSelection selectNativeSuppression(Object tree, Object entry)
        {
            Object mapper = Global.invoke(tree, "getMapper"); //$NON-NLS-1$
            Object viewModel = Global.invoke(mapper, "mapModelToView", entry); //$NON-NLS-1$
            if (viewModel == null)
            {
                return new SuppressionSelection(false, false);
            }
            Object treeViewModel = Global.invoke(tree, "getControlViewModel"); //$NON-NLS-1$
            Object scene = Global.invoke(tree, "getScene"); //$NON-NLS-1$
            Object dialogRenderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
            // SwtDialogRenderer и LwtDialogRenderer делегируют нативные элементы contentRenderer.
            Object renderer = Global.getField(dialogRenderer, "contentRenderer"); //$NON-NLS-1$
            Object views = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
            Object view = views instanceof Map<?, ?> map ? map.get(treeViewModel) : null;
            Object nativeControl = Global.invoke(view, "getNativeControl"); //$NON-NLS-1$
            Object swtControl = nativeControl instanceof Control ? nativeControl
                : Global.invoke(nativeControl, "getSwtControl"); //$NON-NLS-1$
            TreeViewer viewer = swtControl instanceof Composite composite ? suppressionTreeViewer(composite) : null;
            if (viewer != null && !viewer.getTree().isDisposed())
            {
                viewer.expandToLevel(viewModel, 0);
                viewer.setSelection(new org.eclipse.jface.viewers.StructuredSelection(viewModel), true);
                viewer.getTree().showSelection();
                boolean focused = viewer.getTree().setFocus();
                boolean selected = viewer.getStructuredSelection().getFirstElement() == viewModel;
                return new SuppressionSelection(selected, focused);
            }
            return new SuppressionSelection(false, false);
        }

        /** Ищем viewer только внутри нативного контрола компонента дерева этого диалога. */
        private static TreeViewer suppressionTreeViewer(Composite composite)
        {
            if (composite.isDisposed())
                return null;
            if (composite.getData("com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer") instanceof TreeViewer viewer) //$NON-NLS-1$
                return viewer;
            for (Control child : composite.getChildren())
                if (child instanceof Composite nested)
                {
                    TreeViewer viewer = suppressionTreeViewer(nested);
                    if (viewer != null)
                        return viewer;
                }
            return null;
        }

        private static Object suppressionEntry(Object provider, String fqn, CheckUid uid)
        {
            if (provider == null || fqn == null)
                return null;
            try
            {
                java.lang.reflect.Method method = provider.getClass().getMethod("getSuppressions", String.class); //$NON-NLS-1$
                method.setAccessible(true);
                Object entries = method.invoke(provider, fqn);
                return entries instanceof Map<?, ?> map ? map.get(uid) : null;
            }
            catch (ReflectiveOperationException e)
            {
                return null;
            }
        }

        @Override public void postExecuteFailure(String commandId, ExecutionException exception) { pending = List.of(); }
        @Override public void notHandled(String commandId, NotHandledException exception) { pending = List.of(); }
    }

    /** Имя класса объекта для журнала. */
    private static String className(Object value)
    {
        return value == null ? "нет" : value.getClass().getName(); //$NON-NLS-1$
    }

    private static final class Debug
    {
        static final String TAG = "ProblemView"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
