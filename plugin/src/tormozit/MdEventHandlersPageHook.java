package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IColorProvider;
import org.eclipse.jface.viewers.IFontProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.IToolTipProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.ScrolledForm;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IGlobalScopeProvider;
import org.eclipse.xtext.scoping.IScope;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.google.inject.Injector;

/**
 * Встраивает страницу «Подписки» в редактор объекта метаданных
 * ({@link DtGranularEditor}).
 *
 * <p>Страница воспроизводит результат команды «Все подписки на события» из контекстного
 * меню навигатора: тот же контент (встроенный EDT {@code EventHandlersEditor}) и фильтр
 * по производным типам объекта, что и у подменю «Найти подписки на события → Все», плюс
 * супертипы этих типов — наборы типов платформы («СправочникОбъект» и т.п.), чтобы были
 * видны и подписки на весь набор, а не только на конкретный объект.
 *
 * <p>Страница добавляется только для объектов с производными типами (ссылочные объекты,
 * регистры, константы) — критерий тот же, что у {@code ProdusedTypesUtil} EDT.
 *
 * <p>Страница — {@link DtGranularEditorPage}, поэтому заголовок формы тот же, что у
 * остальных вкладок редактора: тип → имя объекта → «Подписки на события». Встроенный
 * {@code EventHandlersEditor} свою шапку («Все подписки на события») не показывает.
 *
 * <p>Тяжёлое наполнение выполняется лениво при первой активации вкладки: прогрев типов
 * платформы и списка подписок — фоновым заданием, создание SWT-виджетов — в
 * {@code asyncExec}. Пока список не готов, на странице сообщение о загрузке; клик по
 * вкладке UI-поток не блокирует.
 *
 * <p>Бандл {@code com._1c.g5.v8.dt.eventhandlers.ui} не экспортирует пакеты, поэтому
 * его классы ({@code EventHandlersEditor}, {@code EventHandlersEditorInput},
 * {@code ProdusedTypesUtil}) загружаются рефлексией через {@link Platform#getBundle};
 * Guice-инъекция полей редактора — через {@code EventHandlersUiPlugin.getInjector()}.
 */
public final class MdEventHandlersPageHook implements IStartup
{
    private static final String TAG = "MdEventHandlersPageHook"; //$NON-NLS-1$

    private static final String BUNDLE_ID = "com._1c.g5.v8.dt.eventhandlers.ui"; //$NON-NLS-1$

    private static final String MODEL_BUNDLE_ID = "com._1c.g5.v8.dt.eventhandlers"; //$NON-NLS-1$

    private static final String UTIL_CLASS = MODEL_BUNDLE_ID + ".services.EventHandlersUtil"; //$NON-NLS-1$

    private static final String LOADING_TEXT = "Загрузка подписок на события…"; //$NON-NLS-1$

    private static final AtomicBoolean WARMUP_STARTED = new AtomicBoolean();

    private static volatile boolean warmupDone;

    private static volatile Job warmupJob;

    private static final String EDITOR_CLASS = BUNDLE_ID + ".editor.EventHandlersEditor"; //$NON-NLS-1$

    private static final String EDITOR_INPUT_CLASS = BUNDLE_ID + ".editor.EventHandlersEditorInput"; //$NON-NLS-1$

    private static final String UI_PLUGIN_CLASS =
        "com._1c.g5.v8.dt.internal.eventhandlers.ui.activator.EventHandlersUiPlugin"; //$NON-NLS-1$

    private static final String PRODUSED_TYPES_CLASS = BUNDLE_ID + ".service.ProdusedTypesUtil"; //$NON-NLS-1$

    /** Обработчик «Открыть» (двойной клик EDT вызывает его напрямую, минуя команду). */
    private static final String OPEN_OBJECT_HANDLER_CLASS = BUNDLE_ID + ".handlers.OpenObjectHandler"; //$NON-NLS-1$

    static final String PAGE_ID = "tormozit.mdEventHandlers"; //$NON-NLS-1$

    private static final String PAGE_TITLE = "Подписки на события"; //$NON-NLS-1$

    /** Пометка: шапка встроенного {@code EventHandlersEditor} уже сжата в 0. */
    private static final String KEY_HEAD_COLLAPSED = "tormozit.eventHandlers.headCollapsed"; //$NON-NLS-1$

    /** Пометка: сжатие шапки уже поставлено в очередь UI. */
    private static final String KEY_HEAD_COLLAPSE_SCHEDULED = "tormozit.eventHandlers.headCollapseScheduled"; //$NON-NLS-1$

    /** Вкладка «Обмен данными» — наша страница строго перед ней. */
    private static final String DATA_EXCHANGE_TAB = "Обмен данными"; //$NON-NLS-1$

    /** Запасная позиция, если вкладки «Обмен данными» нет: ERD {@code editors.diagrams.page}. */
    private static final String DATA_SCHEMA_TAB = "Схема данных"; //$NON-NLS-1$

    /**
     * Granular-редакторы, к которым страница уже добавлена (или решено не добавлять).
     * WeakHashMap без значений используется как WeakHashSet: не удерживает редакторы в памяти.
     */
    private final Set<DtGranularEditor<?>> hookedGranularEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Редакторы, для которых уже запланирован повторный hook (ожидание инициализации).
     * Защита от параллельного планирования дублирующих повторов.
     */
    private final Set<DtGranularEditor<?>> pendingRetryEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    /** Пауза между повторами ожидания инициализации granular-редактора, мс. */
    private static final int HOOK_RETRY_DELAY_MS = 200;

    /** Лимит повторов ожидания инициализации (30 с на редактор). */
    private static final int HOOK_MAX_ATTEMPTS = 150;

    /**
     * Прогрев {@code EventHandlersUtil.getConfigurationEvents} и списка подписок
     * вне UI-потока. Запускается только при первой активации вкладки «Подписки»,
     * не при открытии редактора. Первый вызов тянет типы платформы
     * ({@code PlatformTypesLoader.requestData}); повторные заходы на вкладку это уже не ждут.
     */
    private static void ensureWarmup(Configuration configuration)
    {
        if (!WARMUP_STARTED.compareAndSet(false, true))
            return;
        Job job = new Job("Комфорт: прогрев подписок на события") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    if (configuration != null && !monitor.isCanceled())
                        warmupConfigurationEvents(configuration);
                }
                finally
                {
                    warmupDone = true;
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setPriority(Job.LONG);
        warmupJob = job;
        job.schedule();
    }

    private static void warmupConfigurationEvents(Configuration configuration)
    {
        try
        {
            Bundle modelBundle = Platform.getBundle(MODEL_BUNDLE_ID);
            if (modelBundle != null)
            {
                Class<?> util = modelBundle.loadClass(UTIL_CLASS);
                Global.invoke(util, "getConfigurationEvents", configuration); //$NON-NLS-1$
            }
            if (configuration.getEventSubscriptions() != null)
            {
                for (EventSubscription subscription : configuration.getEventSubscriptions())
                {
                    if (subscription != null)
                        subscription.getEvent();
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            Global.logError(TAG, "warmup event handlers", e); //$NON-NLS-1$
        }
    }

    // =========================================================================
    // Флажки в дереве подписок: подмена стиля штатного дерева
    // =========================================================================

    /**
     * Встроенный редактор страницы → производные типы её объекта, не зависящие от фильтра.
     * Наборы типов сюда не входят: в диалоге «Редактирование типа данных» набор — это строка-группа,
     * и поиск по её имени выделил бы группу вместо строки самого объекта.
     */
    private static final Map<Object, List<Object>> EDITOR_OBJECT_TYPES =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Число раскрытых строк дерева (диагностика). */
    static int expandedCount(Tree tree)
    {
        return expandedCount(tree.getItems());
    }

    private static int expandedCount(TreeItem[] items)
    {
        int n = 0;
        for (TreeItem item : items)
            if (item.getExpanded())
                n += 1 + expandedCount(item.getItems());
        return n;
    }

    /** Подписка включена: объект страницы к ней подключён (прямо или через набор/определяемый тип). */
    static boolean isMarked(Object page, EventSubscription subscription)
    {
        return page instanceof EventHandlersPage handlersPage && handlersPage.markState(subscription) != 0;
    }

    /**
     * Страница определяемого типа (просмотр), не наполненная из-за отсутствия подписок с этим
     * типом в источнике: число подписок — 0.
     */
    static boolean hasNoDefinedTypeSubscriptions(Object page)
    {
        if (!(page instanceof EventHandlersPage handlersPage) || !handlersPage.readOnly || handlersPage.filled)
            return false;
        handlersPage.collectDefinedTypeSources();
        return handlersPage.producedTypes.isEmpty();
    }

    /**
     * Типы объекта встроенной страницы для команды «Открыть связь»; {@code null} — редактор
     * не встроенный (у отдельного редактора «текущий объект» берётся из отбора панели).
     */
    static List<Object> objectTypesOf(Object embeddedEditor)
    {
        return EDITOR_OBJECT_TYPES.get(embeddedEditor);
    }

    private static final String SUB_SECTION_CLASS = BUNDLE_ID + ".sections.SubSection"; //$NON-NLS-1$

    private static final AtomicBoolean weavingHookInstalled = new AtomicBoolean();

    /**
     * Стиль штатного дерева ({@code SubSection.getViewerStyle() = SWT.MULTI}) задан в коде и
     * позже не меняется, а флажки в {@code Tree} возможны только с {@code SWT.CHECK} при создании.
     * Поэтому, как {@code MdEditorTreeHook}, подменяем возврат метода. Регистрируется из
     * {@link Activator#start} до загрузки {@code SubSection}.
     */
    static void installWeavingHook()
    {
        if (!weavingHookInstalled.compareAndSet(false, true))
            return;
        org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(MdEventHandlersPageHook.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(WeavingHook.class, new SubSectionStyleWeavingHook(), null);
    }

    /**
     * Сколько ближайших подсекций встроенного редактора получат флажки. Секции создаются в
     * порядке: подписки, источники, обработчики (см. {@code MainSection.createViewers}) — флажки
     * нужны первым двум.
     */
    private static volatile int checkStyleArmed;

    /** Вызывается из подменённого {@code SubSection.getViewerStyle()}. */
    public static int subSectionTreeStyle(Object subSection, int style)
    {
        if (checkStyleArmed <= 0)
            return style;
        checkStyleArmed--;
        return style | SWT.CHECK;
    }

    private static final class SubSectionStyleWeavingHook implements WeavingHook
    {
        @Override
        public void weave(WovenClass wovenClass)
        {
            if (wovenClass.getState() != WovenClass.TRANSFORMING
                || !SUB_SECTION_CLASS.equals(wovenClass.getClassName()))
                return;
            try
            {
                byte[] transformed = transformViewerStyle(wovenClass.getBytes());
                if (transformed == null)
                    return;
                wovenClass.getDynamicImports().add("tormozit"); //$NON-NLS-1$
                wovenClass.setBytes(transformed);
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    private static byte[] transformViewerStyle(byte[] source)
    {
        ClassReader reader = new ClassReader(source);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean touched = new AtomicBoolean();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
        {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"getViewerStyle".equals(name) || !"()I".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                    return mv;
                return new MethodVisitor(Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitInsn(int opcode)
                    {
                        if (opcode == Opcodes.IRETURN)
                        {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "tormozit/MdEventHandlersPageHook", //$NON-NLS-1$
                                "subSectionTreeStyle", "(Ljava/lang/Object;I)I", false); //$NON-NLS-1$ //$NON-NLS-2$
                            touched.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    // =========================================================================
    // IStartup
    // =========================================================================

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    // =========================================================================
    // Подключение к окну / редактору
    // =========================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart ed = ref.getEditor(false);
                if (ed instanceof DtGranularEditor<?>)
                    hookGranularEditor((DtGranularEditor<?>)ed);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override public void partActivated(IWorkbenchPartReference ref)
            {
                hookFromRef(ref);
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference))
                    return;
                IWorkbenchPart part = ((IEditorReference)ref).getPart(false);
                if (part instanceof DtGranularEditor<?>)
                    hookGranularEditor((DtGranularEditor<?>)part);
            }
        });
    }

    private void hookGranularEditor(DtGranularEditor<?> editor)
    {
        hookGranularEditor(editor, 0);
    }

    private void hookGranularEditor(DtGranularEditor<?> editor, int attempt)
    {
        if (hookedGranularEditors.contains(editor))
            return;
        try
        {
            // Пока редактор не инициализирован (loading-страница, модель ещё не подставлена),
            // вкладки основных страниц не созданы — добавлять нашу рано (встанет первой).
            // Повторяем по таймеру: покрывает и восстановленные при старте редакторы,
            // у которых после partOpened модель ещё null и активаций больше не будет.
            if (editor.getModel() == null || !isEditorInitialized(editor))
            {
                scheduleRetry(editor, attempt);
                return;
            }

            Object model = editor.getModel();
            if (!(model instanceof MdObject))
            {
                hookedGranularEditors.add(editor);
                return;
            }

            if (model instanceof DefinedType definedType)
            {
                // Определяемый тип: подписки, у которых он указан источником, — только просмотр.
                if (hookedGranularEditors.add(editor))
                    addEventHandlersPage(editor, definedType, new ArrayList<>(), true);
                return;
            }

            List<Object> producedTypes = collectProducedTypes((MdObject)model);
            if (producedTypes.isEmpty())
            {
                // Нет производных типов (не ссылочный объект, не регистр, не константа)
                hookedGranularEditors.add(editor);
                return;
            }

            if (!hookedGranularEditors.add(editor))
                return;

            addEventHandlersPage(editor, (MdObject)model, producedTypes, false);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "hook granular editor", e); //$NON-NLS-1$
        }
    }

    private void scheduleRetry(DtGranularEditor<?> editor, int attempt)
    {
        if (attempt >= HOOK_MAX_ATTEMPTS || editor.getSite() == null)
            return;
        Composite container = (Composite)Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (container != null && container.isDisposed())
            return; // редактор закрыт до инициализации
        if (!pendingRetryEditors.add(editor))
            return;

        Display.getDefault().timerExec(HOOK_RETRY_DELAY_MS, () ->
        {
            pendingRetryEditors.remove(editor);
            hookGranularEditor(editor, attempt + 1);
        });
    }

    /** Готовность granular-редактора: private-поле {@code initialized} EDT (страницы созданы). */
    private static boolean isEditorInitialized(DtGranularEditor<?> editor)
    {
        Object initialized = Global.getField(editor, "initialized"); //$NON-NLS-1$
        return Boolean.TRUE.equals(initialized);
    }

    // =========================================================================
    // Добавление страницы
    // =========================================================================

    private static void addEventHandlersPage(DtGranularEditor<?> editor, MdObject mdObject,
        List<Object> producedTypes, boolean readOnly)
    {
        try
        {
            EventHandlersPage page = new EventHandlersPage(mdObject, producedTypes, readOnly);
            page.initialize(editor);

            Composite container = (Composite)Global.invoke(editor, "getContainer"); //$NON-NLS-1$
            if (container == null || container.isDisposed())
                return;

            page.createPartControl(container);
            int insertIndex = resolveInsertIndex(editor);
            if (insertIndex >= 0)
                editor.addPage(insertIndex, page);
            else
                editor.addPage(page);
        }
        catch (PartInitException | RuntimeException e)
        {
            Global.logError(TAG, "add page", e); //$NON-NLS-1$
        }
    }

    /**
     * Индекс вкладки «Обмен данными» — вставлять строго перед ней.
     * Если её нет — перед «Схема данных». Если и той нет — {@code -1} (в конец).
     */
    static int resolveInsertIndex(DtGranularEditor<?> editor)
    {
        // Вкладка «Определяемые типы» (MdEditorDefinedTypesPageHook) идёт сразу за нашей.
        int definedTypes = indexOfPageId(editor, MdEditorDefinedTypesPageHook.PAGE_ID);
        if (definedTypes >= 0)
            return definedTypes;
        int dataExchange = indexOfTab(editor, DATA_EXCHANGE_TAB, "Data exchange"); //$NON-NLS-1$
        if (dataExchange >= 0)
            return dataExchange;
        return indexOfTab(editor, DATA_SCHEMA_TAB, "Data schema"); //$NON-NLS-1$
    }

    /** Индекс страницы редактора по идентификатору; {@code -1} — такой страницы нет. */
    static int indexOfPageId(DtGranularEditor<?> editor, String pageId)
    {
        if (Global.getField(editor, "pages") instanceof List<?> pages) //$NON-NLS-1$
        {
            for (int i = 0; i < pages.size(); i++)
                if (pages.get(i) instanceof IFormPage page && pageId.equals(page.getId()))
                    return i;
        }
        return -1;
    }

    /**
     * Индекс страницы/вкладки по заголовку. Учитывает суффикс числа в тексте вкладки
     * ({@code «Обмен данными ?»}), если счётчик уже успел подписаться.
     */
    private static int indexOfTab(DtGranularEditor<?> editor, String titleRu, String titleEn)
    {
        Object pagesObj = Global.getField(editor, "pages"); //$NON-NLS-1$
        if (pagesObj instanceof List<?> pages)
        {
            for (int i = 0; i < pages.size(); i++)
            {
                if (pages.get(i) instanceof IFormPage page && isTabTitle(page.getTitle(), titleRu, titleEn))
                    return i;
            }
        }
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (container instanceof CTabFolder folder)
        {
            for (CTabItem item : folder.getItems())
            {
                if (isTabTitle(item.getText(), titleRu, titleEn))
                    return folder.indexOf(item);
            }
        }
        return -1;
    }

    private static boolean isTabTitle(String text, String titleRu, String titleEn)
    {
        if (text == null || text.isEmpty())
            return false;
        return titleRu.equals(text) || titleEn.equals(text)
            || text.startsWith(titleRu + " ") || text.startsWith(titleEn + " "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // =========================================================================
    // Produced types (рефлексия по ProdusedTypesUtil EDT)
    // =========================================================================

    private static List<Object> collectProducedTypes(MdObject mdObject)
    {
        List<Object> result = new ArrayList<>();
        Bundle bundle = Platform.getBundle(BUNDLE_ID);
        if (bundle == null)
            return result;

        try
        {
            Class<?> util = bundle.loadClass(PRODUSED_TYPES_CLASS);
            for (String methodName : new String[] { "getObjectModuleType", //$NON-NLS-1$
                "getManagerModuleType", "getRecordSetModuleType" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Object type = Global.invoke(util, methodName, mdObject);
                if (type != null)
                    result.add(type);
            }
        }
        catch (ClassNotFoundException e)
        {
            Global.logError(TAG, "load ProdusedTypesUtil", e); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Производные типы объекта плюс их супертипы — наборы типов платформы
     * ({@code CatalogObject} / «СправочникОбъект», {@code InformationRegisterRecordSet}
     * / «РегистрСведенийНаборЗаписей» и т.п.).
     *
     * <p>Источник подписки может быть задан не конкретным типом
     * («СправочникОбъект.Товары»), а всем набором («СправочникОбъект» — любой
     * справочник). {@code EventHandlersFilter} EDT сравнивает источники подписки с
     * элементами отбора напрямую ({@code getSource().getTypes().contains(item)}),
     * поэтому без наборов типов такие подписки на странице не видны.
     *
     * <p>Имя набора — часть имени производного типа до точки. Сам набор берётся так же,
     * как это делает {@code FilterSourceViewerContentProvider} EDT: глобальная область
     * видимости по ссылке {@code TypeDescription.types} (односегментные имена — это
     * наборы типов) и {@code EcoreUtil.resolve} относительно конфигурации — только так
     * получается тот же экземпляр {@code TypeItem}, что и в источниках подписок.
     */
    private static List<Object> withSourceTypeSets(Injector injector, Configuration configuration,
        List<Object> producedTypes)
    {
        List<Object> result = new ArrayList<>(producedTypes);
        try
        {
            Set<String> typeSetNames = new LinkedHashSet<>();
            for (Object producedType : producedTypes)
            {
                if (!(producedType instanceof TypeItem typeItem) || typeItem.getName() == null)
                    continue;
                int dot = typeItem.getName().indexOf('.');
                if (dot > 0)
                    typeSetNames.add(typeItem.getName().substring(0, dot));
            }
            if (typeSetNames.isEmpty())
                return result;

            IGlobalScopeProvider scopeProvider = injector.getInstance(IGlobalScopeProvider.class);
            IScope scope = scopeProvider.getScope(configuration.eResource(),
                McorePackage.Literals.TYPE_DESCRIPTION__TYPES,
                description -> description.getName().getSegmentCount() == 1);

            for (String typeSetName : typeSetNames)
            {
                IEObjectDescription description = scope.getSingleElement(QualifiedName.create(typeSetName));
                if (description == null)
                    continue;
                EObject typeSet = EcoreUtil.resolve(description.getEObjectOrProxy(), configuration);
                if (typeSet instanceof TypeItem && !typeSet.eIsProxy() && !result.contains(typeSet))
                    result.add(typeSet);
            }
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "collect source type sets", e); //$NON-NLS-1$
        }
        return result;
    }

    // =========================================================================
    // Базовая конфигурация проекта объекта (как AbstractEventHandlersHandler EDT)
    // =========================================================================

    private static Configuration resolveBaseConfiguration(MdObject mdObject)
    {
        IV8ProjectManager projectManager =
            (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        if (projectManager == null)
            return null;

        IV8Project project = projectManager.getProject(mdObject);
        if (project instanceof IExtensionProject extension)
            project = extension.getParent();
        if (project instanceof IConfigurationProject configurationProject)
            return configurationProject.getConfiguration();
        return null;
    }

    // =========================================================================
    // Guice-инжектор бандла eventhandlers.ui
    // =========================================================================

    private static Injector resolveInjector(Bundle bundle)
    {
        try
        {
            Class<?> pluginClass = bundle.loadClass(UI_PLUGIN_CLASS);
            Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
            Object injector = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
            return injector instanceof Injector injectorImpl ? injectorImpl : null;
        }
        catch (ClassNotFoundException e)
        {
            Global.logError(TAG, "load EventHandlersUiPlugin", e); //$NON-NLS-1$
            return null;
        }
    }

    // =========================================================================
    // Страница «Подписки на события»
    // =========================================================================

    /**
     * Вкладка granular-редактора: лёгкий каркас создаётся при добавлении, тяжёлое
     * наполнение (встроенный {@code EventHandlersEditor} EDT + фильтр по объекту) —
     * фоном при первой активации ({@link #setActive(boolean)}): вкладка сразу
     * показывает сообщение о загрузке и не блокирует UI.
     *
     * <p>Наследует {@link DtGranularEditorPage}, чтобы шапка формы совпадала со штатной
     * («Справочники → Имя → Подписки на события»). {@code createFormContent} у базового класса
     * {@code final} — контент в {@link #createPageControls}, раскладка — {@link FillLayout},
     * иначе штатный {@code ColumnLayout} сожмёт встроенный редактор.
     */
    private static final class EventHandlersPage extends DtGranularEditorPage<MdObject>
    {
        private final MdObject mdObject;

        private final List<Object> producedTypes;

        /**
         * Элементы отбора: производные типы объекта и их супертипы (наборы типов
         * платформы). Наборы известны только после получения инжектора и конфигурации —
         * до наполнения страницы здесь одни производные типы.
         */
        private List<Object> filterSources;

        /** Производные типы и наборы типов объекта — без определяемых типов (те пересчитываются). */
        private List<Object> baseFilterSources;

        private Composite host;

        private Object embeddedEditor;

        private boolean filled;

        /** Наполнение уже поставлено в очередь (фон + asyncExec), повторно не планировать. */
        private boolean fillScheduled;

        /** Мост команд {@code com._1c.g5.v8.dt.eventhandlers.ui.*} к встроенному редактору. */
        private IExecutionListener commandBridge;

        /** Защита от рекурсии: перевыполненная команда снова попадает в preExecute. */
        private boolean bridging;

        /** Переключатель «Только помеченные»: включён — подписки объекта, выключен — все совместимые. */
        private boolean onlyMarked = true;

        /** Бандл/инжектор, сохранённые после {@link #fill()} для двойного клика. */
        private Bundle pageBundle;

        private Injector pageInjector;

        /**
         * Режим просмотра (редактор определяемого типа): без флажков и правки «Источника».
         * {@link #producedTypes} — элементы источников подписок конфигурации, соответствующие
         * определяемому типу; пересчитываются при каждой активации, пока страница не наполнена.
         */
        private final boolean readOnly;

        EventHandlersPage(MdObject mdObject, List<Object> producedTypes, boolean readOnly)
        {
            super(PAGE_ID, PAGE_TITLE);
            this.mdObject = mdObject;
            this.producedTypes = producedTypes;
            this.filterSources = producedTypes;
            this.readOnly = readOnly;
        }

        /** Элементы источников подписок, обозначающие определяемый тип страницы. */
        private void collectDefinedTypeSources()
        {
            producedTypes.clear();
            Configuration configuration = resolveBaseConfiguration(mdObject);
            if (configuration == null)
                return;
            for (EventSubscription subscription : configuration.getEventSubscriptions())
            {
                TypeDescription source = subscription.getSource();
                if (source == null)
                    continue;
                for (TypeItem type : source.getTypes())
                    if (isDefinedTypeItem(type) && !producedTypes.contains(type)
                        && mdObject.getName() != null
                        && (("DefinedType." + mdObject.getName()).equals(type.getName()) //$NON-NLS-1$
                            || ("ОпределяемыйТип." + mdObject.getName()).equals(type.getNameRu()))) //$NON-NLS-1$
                        producedTypes.add(type);
            }
            filterSources = producedTypes;
        }

        @Override
        protected Layout createPageLayout()
        {
            return new FillLayout();
        }

        @Override
        protected void createPageControls(IManagedForm managedForm)
        {
            host = new Composite(managedForm.getForm().getBody(), SWT.NONE);
            host.setLayout(new FillLayout());
        }

        @Override
        public void setActive(boolean active)
        {
            if (!active || host == null || host.isDisposed())
            {
                super.setActive(active);
                return;
            }

            if (readOnly && !filled)
            {
                collectDefinedTypeSources();
                if (producedTypes.isEmpty())
                {
                    showMessage("Подписок на события с этим определяемым типом в источнике нет."); //$NON-NLS-1$
                    super.setActive(active);
                    return;
                }
            }

            if (!filled && !fillScheduled)
            {
                fillScheduled = true;
                showLoading();
                scheduleFillAfterWarmup();
            }
            else if (filled && embeddedEditor != null)
            {
                refreshDefinedTypeSources();
                if (!isObjectFilterApplied(embeddedEditor))
                    configureObjectFilter(embeddedEditor);
            }
            super.setActive(active);
        }

        private void scheduleFillAfterWarmup()
        {
            Display display = host.getDisplay();
            if (display == null || display.isDisposed())
                return;
            ensureWarmup(resolveBaseConfiguration(mdObject));
            Runnable fillUi = () ->
            {
                if (display.isDisposed() || host == null || host.isDisposed() || filled)
                    return;
                fill();
            };
            if (warmupDone)
            {
                display.asyncExec(fillUi);
                return;
            }
            Job job = warmupJob;
            if (job == null)
            {
                display.asyncExec(fillUi);
                return;
            }
            job.addJobChangeListener(new JobChangeAdapter()
            {
                @Override
                public void done(IJobChangeEvent event)
                {
                    if (!display.isDisposed())
                        display.asyncExec(fillUi);
                }
            });
            if (warmupDone)
                display.asyncExec(fillUi);
        }

        private void showLoading()
        {
            showMessage(LOADING_TEXT);
        }

        private void showMessage(String text)
        {
            clearHost();
            Label label = new Label(host, SWT.WRAP);
            label.setText(text);
            host.layout(true, true);
        }

        private void clearHost()
        {
            if (host == null || host.isDisposed())
                return;
            for (Control child : host.getChildren())
                child.dispose();
        }

        @Override
        public void setFocus()
        {
            if (embeddedEditor != null)
                Global.invoke(embeddedEditor, "setFocus"); //$NON-NLS-1$
            else
                super.setFocus();
        }

        @Override
        public void dispose()
        {
            removeCommandBridge();
            if (embeddedEditor != null)
            {
                Global.invokeVoid(embeddedEditor, "dispose"); //$NON-NLS-1$
                embeddedEditor = null;
            }
            super.dispose();
        }

        private void fill()
        {
            filled = true;
            try
            {
                Bundle bundle = Platform.getBundle(BUNDLE_ID);
                if (bundle == null)
                {
                    showError("Бандл " + BUNDLE_ID + " не найден"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                Injector injector = resolveInjector(bundle);
                if (injector == null)
                {
                    showError("Не найден Guice-инжектор бандла обработчиков событий"); //$NON-NLS-1$
                    return;
                }

                Configuration configuration = resolveBaseConfiguration(mdObject);
                if (configuration == null)
                {
                    showError("Не удалось определить конфигурацию объекта"); //$NON-NLS-1$
                    return;
                }

                // Подписки бывают заданы на набор типов целиком («СправочникОбъект»),
                // а не на конкретный производный тип объекта — добавляем и наборы.
                if (readOnly)
                {
                    filterSources = producedTypes;
                    baseFilterSources = filterSources;
                }
                else
                {
                    filterSources = withSourceTypeSets(injector, configuration, producedTypes);
                    baseFilterSources = filterSources;
                    filterSources = withDefinedTypes(configuration, baseFilterSources);
                }

                Object editor = bundle.loadClass(EDITOR_CLASS).getDeclaredConstructor().newInstance();
                injector.injectMembers(editor);

                Object input = bundle.loadClass(EDITOR_INPUT_CLASS)
                    .getConstructor(Configuration.class).newInstance(configuration);

                if (!(getEditor() instanceof DtGranularEditor<?> granularEditor))
                {
                    showError("Редактор объекта метаданных недоступен"); //$NON-NLS-1$
                    return;
                }

                IEditorSite site = granularEditor.createEmbeddedEditorSite((IEditorPart)editor);

                Global.invoke(editor, "init", site, input); //$NON-NLS-1$
                clearHost();
                checkStyleArmed = readOnly ? 0 : 2;
                try
                {
                    Global.invoke(editor, "createPartControl", host); //$NON-NLS-1$
                }
                finally
                {
                    checkStyleArmed = 0;
                }
                hideEmbeddedFormHeading();
                configureObjectFilter(editor);

                embeddedEditor = editor;
                EDITOR_OBJECT_TYPES.put(editor, producedTypes);
                pageBundle = bundle;
                pageInjector = injector;
                // Встроенный редактор не является частью workbench — хуки фильтра и
                // команды «Открыть обработчик» его сами не увидят (см. patchEditor).
                EventHandlersFilterHook.patchEditor(editor);
                // granularEditor — чтобы обработчик своего же объекта открывался переходом
                // внутри этого редактора, а не повторным открытием поверх него.
                EventHandlersOpenHandlerHook.patchEditor(editor, granularEditor);
                installDoubleClickOpen();
                installCommandBridge();
                TreeViewer subscriptionsViewer = (TreeViewer)Global.invoke(
                    Global.invoke(editor, "getMainSection"), "getEventHandlersTreeViewer"); //$NON-NLS-1$ //$NON-NLS-2$
                if (readOnly)
                {
                    if (subscriptionsViewer != null && !subscriptionsViewer.getControl().isDisposed())
                    {
                        TreeExpander.installWhitelisted(TreeExpander.Target.EVENT_HANDLERS, subscriptionsViewer);
                        CopyCommandSupport.wireCopyOverride(subscriptionsViewer.getTree());
                    }
                }
                else
                    installSubscriptionMarks(subscriptionsViewer);
                MdEditorTabsHook.requestRefresh(granularEditor);
            }
            catch (Exception | LinkageError e)
            {
                Global.logError(TAG, "fill page", e); //$NON-NLS-1$
                showError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }

        // =========================================================================
        // Пометки подписок и переключатель «Только помеченные»
        // =========================================================================

        private static final String MARKS_LOG = "eventSubscriptionMarks"; //$NON-NLS-1$

        /** Изменение источника подписки: добавить тип ({@code typeToAdd != null}) или убрать типы объекта. */
        private record SourceChange(EventSubscription subscription, TypeItem typeToAdd)
        {
        }

        private TreeViewer marksViewer;

        /** События типов объекта и их наборов — по одному на имя. */
        private List<Object> compatibleEvents()
        {
            Map<String, Object> byName = new LinkedHashMap<>();
            for (Object source : filterSources)
            {
                if (source instanceof Type type)
                {
                    for (com._1c.g5.v8.dt.mcore.Event event : type.allEvents())
                        byName.putIfAbsent(event.getName(), event);
                }
            }
            return new ArrayList<>(byName.values());
        }

        private Set<String> compatibleEventNames()
        {
            Set<String> names = new HashSet<>();
            for (Object event : compatibleEvents())
                names.add(((com._1c.g5.v8.dt.mcore.Event)event).getName());
            return names;
        }

        /** 0 — объект не подключён; 1 — подключён самим типом; 2 — только через набор типов. */
        private int markState(EventSubscription subscription)
        {
            TypeDescription source = subscription.getSource();
            if (source == null)
                return 0;
            List<TypeItem> types = source.getTypes();
            for (Object type : producedTypes)
                if (types.contains(type))
                    return 1;
            for (Object type : filterSources)
                if (types.contains(type))
                    return 2;
            // Объект входит в определяемый тип источника — подключён косвенно (менять здесь нельзя).
            for (TypeItem type : types)
                if (definedTypeState(type) != 0)
                    return 2;
            return 0;
        }

        private static boolean isDefinedTypeItem(TypeItem type)
        {
            String name = type.getName();
            String nameRu = type.getNameRu();
            return (name != null && name.startsWith("DefinedType.")) //$NON-NLS-1$
                || (nameRu != null && nameRu.startsWith("ОпределяемыйТип.")); //$NON-NLS-1$
        }

        /**
         * К источникам отбора добавляются определяемые типы, в составе которых есть производные
         * типы объекта (ссылочные типы среди них не бывают — их нет в {@link #producedTypes}).
         * Элементы берутся из источников подписок конфигурации: именно эти экземпляры
         * {@code TypeItem} сравнивает штатный фильтр ({@code getSource().getTypes().contains}).
         */
        private List<Object> withDefinedTypes(Configuration configuration, List<Object> sources)
        {
            List<Object> result = new ArrayList<>(sources);
            try
            {
                for (EventSubscription subscription : configuration.getEventSubscriptions())
                {
                    TypeDescription source = subscription.getSource();
                    if (source == null)
                        continue;
                    for (TypeItem type : source.getTypes())
                    {
                        if (!isDefinedTypeItem(type) || result.contains(type))
                            continue;
                        DefinedType definedType = findDefinedType(type);
                        TypeDescription description = definedType != null ? definedType.getTypeDescription() : null;
                        if (description == null)
                            continue;
                        for (Object produced : producedTypes)
                            if (description.getTypes().contains(produced))
                            {
                                result.add(type);
                                break;
                            }
                    }
                }
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "collect defined types", e); //$NON-NLS-1$
            }
            return result;
        }

        /**
         * Состав определяемых типов мог измениться (флажок в списке источников, правка в другом
         * редакторе) — пересчитываем источники отбора и, если они другие, переприменяем отбор.
         */
        private void refreshDefinedTypeSources()
        {
            if (readOnly || baseFilterSources == null || embeddedEditor == null)
                return;
            Configuration configuration = resolveBaseConfiguration(mdObject);
            if (configuration == null)
                return;
            List<Object> updated = withDefinedTypes(configuration, baseFilterSources);
            if (new HashSet<>(updated).equals(new HashSet<>(filterSources)))
                return;
            filterSources = updated;
            Global.tempLog(MARKS_LOG, "источники отбора пересчитаны: " + filterSources.size()); //$NON-NLS-1$
            configureObjectFilter(embeddedEditor, true);
        }

        /** Определяемый тип модели по элементу «Источника» ({@code DefinedType.Имя}). */
        private DefinedType findDefinedType(TypeItem type)
        {
            if (!isDefinedTypeItem(type))
                return null;
            Configuration configuration = resolveBaseConfiguration(mdObject);
            if (configuration == null)
                return null;
            String name = type.getName() != null && type.getName().startsWith("DefinedType.") //$NON-NLS-1$
                ? type.getName().substring("DefinedType.".length()) //$NON-NLS-1$
                : type.getNameRu().substring("ОпределяемыйТип.".length()); //$NON-NLS-1$
            for (DefinedType definedType : configuration.getDefinedTypes())
                if (name.equals(definedType.getName()))
                    return definedType;
            return null;
        }

        /** Как {@link #markState}, но для состава определяемого типа: 0 — нет, 1 — сам тип, 2 — набор. */
        private int definedTypeState(TypeItem type)
        {
            DefinedType definedType = findDefinedType(type);
            TypeDescription description = definedType != null ? definedType.getTypeDescription() : null;
            if (description == null)
                return 0;
            List<TypeItem> types = description.getTypes();
            for (Object produced : producedTypes)
                if (types.contains(produced))
                    return 1;
            for (Object set : filterSources)
                if (types.contains(set))
                    return 2;
            return 0;
        }

        private void installSubscriptionMarks(TreeViewer viewer)
        {
            if (viewer == null || viewer.getControl().isDisposed())
                return;
            marksViewer = viewer;
            TreeExpander.installWhitelisted(TreeExpander.Target.EVENT_HANDLERS, viewer);
            Tree tree = viewer.getTree();
            if ((tree.getStyle() & SWT.CHECK) == 0)
            {
                Global.tempLog(MARKS_LOG, "дерево создано без SWT.CHECK — подмена стиля не сработала"); //$NON-NLS-1$
                return;
            }

            // Штатное дерево обновляет строки через label provider, а состояние флажков
            // строкам надо ставить самим: каждое обращение к подписи планирует сверку.
            IBaseLabelProvider raw = viewer.getLabelProvider();
            if (raw instanceof DelegatingStyledCellLabelProvider delegating
                && delegating.getStyledStringProvider() != null)
            {
                boolean injected = EventHandlersFilterHook.injectStyledStringProvider(delegating,
                    new MarkLabels(delegating.getStyledStringProvider(), this::scheduleSyncChecks));
                Global.tempLog(MARKS_LOG, "label provider injected=" + injected); //$NON-NLS-1$
            }
            else
                Global.tempLog(MARKS_LOG, "label provider не Delegating: " + raw); //$NON-NLS-1$

            tree.addListener(SWT.Selection, e ->
            {
                if (e.detail != SWT.CHECK)
                    return;
                if (e.item instanceof TreeItem item && item.getData() instanceof EventSubscription subscription)
                {
                    // Клик по флажку выделяет строку сам не всегда — активируем её явно
                    // (заодно перестраивается список источников выбранной подписки).
                    viewer.setSelection(new StructuredSelection(subscription), true);
                    toggleMarks(List.of(subscription));
                    // Приводим в соответствие модели, если запись не состоялась.
                    scheduleSyncChecks();
                    return;
                }
                // Флажок события/переопределения менять нельзя: тут же возвращаем расчётное
                // состояние, не дожидаясь очереди UI — пользователь не должен видеть смены.
                syncChecks(tree.getItems());
            });

            // Внутри редактора объекта штатное дерево Ctrl+C само не копирует (Win32 не доводит
            // сочетание до KeyDown, а global Copy забирает редактор) — подмена команды Copy.
            CopyCommandSupport.wireCopyOverride(tree);
            tree.setData(EventHandlersFilterHook.RESTORE_ON_CLEAR_KEY, (Runnable)this::restoreAfterSearchCleared);

            // Слушатель добавлен после штатных, поэтому список источников уже перестроен под
            // выбранную подписку, когда мы в нём выделяем строку.
            viewer.addSelectionChangedListener(e ->
            {
                stateLog("selectionChanged: " + selectionKeys(viewer.getTree()) //$NON-NLS-1$
                    + " search='" + searchText() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                selectConnectedSource(e.getStructuredSelection());
                syncSourceChecks();
            });
            installSourceChecks();

            // Свёртки пользователя — часть запоминаемого состояния дерева.
            tree.addListener(SWT.Expand, e ->
            {
                stateLog("event Expand: " + (e.item instanceof TreeItem ti ? rowKey(ti.getData()) : null)); //$NON-NLS-1$
                scheduleSyncChecks();
            });
            tree.addListener(SWT.Collapse, e ->
            {
                stateLog("event Collapse: " + (e.item instanceof TreeItem ti ? rowKey(ti.getData()) : null)); //$NON-NLS-1$
                scheduleSyncChecks();
            });
            tree.addListener(SWT.Selection, e -> scheduleSyncChecks());

            installOnlyMarkedToggle();
            scheduleSyncChecks();
        }

        /**
         * Выбрана одна подписка — в списке источников выделяется тип, через который объект
         * страницы к ней подключён: сам производный тип, иначе набор типов.
         */
        private void selectConnectedSource(org.eclipse.jface.viewers.IStructuredSelection selection)
        {
            if (selection.size() != 1 || !(selection.getFirstElement() instanceof EventSubscription subscription))
                return;
            TypeDescription source = subscription.getSource();
            if (source == null)
                return;
            List<TypeItem> types = source.getTypes();
            Object connected = null;
            for (Object type : producedTypes)
                if (types.contains(type))
                {
                    connected = type;
                    break;
                }
            if (connected == null)
                for (Object type : filterSources)
                    if (types.contains(type))
                    {
                        connected = type;
                        break;
                    }
            if (connected == null)
                return;

            Object mainSection = Global.invoke(embeddedEditor, "getMainSection"); //$NON-NLS-1$
            if (!(Global.getField(mainSection, "paramsSections") instanceof Collection<?> sections)) //$NON-NLS-1$
                return;
            for (Object section : sections)
            {
                if (!(Global.invoke(section, "getViewer") instanceof TreeViewer sourceViewer) //$NON-NLS-1$
                    || sourceViewer.getControl().isDisposed()
                    || sourceViewer.getContentProvider() == null
                    || !sourceViewer.getContentProvider().getClass().getSimpleName().equals("SourceViewerContentProvider")) //$NON-NLS-1$
                    continue;
                sourceViewer.setSelection(new StructuredSelection(connected), true);
                return;
            }
        }

        // -----------------------------------------------------------------
        // Флажки в списке источников выбранной подписки (определяемые типы)
        // -----------------------------------------------------------------

        private TreeViewer sourceViewer;

        /** Дерево «Источник» штатного редактора: подсекция с {@code SourceViewerContentProvider}. */
        private TreeViewer findSourceViewer()
        {
            Object mainSection = Global.invoke(embeddedEditor, "getMainSection"); //$NON-NLS-1$
            if (!(Global.getField(mainSection, "paramsSections") instanceof Collection<?> sections)) //$NON-NLS-1$
                return null;
            for (Object section : sections)
            {
                if (Global.invoke(section, "getViewer") instanceof TreeViewer candidate //$NON-NLS-1$
                    && !candidate.getControl().isDisposed()
                    && candidate.getContentProvider() != null
                    && candidate.getContentProvider().getClass().getSimpleName().equals("SourceViewerContentProvider")) //$NON-NLS-1$
                    return candidate;
            }
            return null;
        }

        private void installSourceChecks()
        {
            sourceViewer = findSourceViewer();
            if (sourceViewer == null || (sourceViewer.getTree().getStyle() & SWT.CHECK) == 0)
            {
                Global.tempLog(MARKS_LOG, "список источников не найден или без SWT.CHECK: " + sourceViewer); //$NON-NLS-1$
                return;
            }
            Tree tree = sourceViewer.getTree();
            CopyCommandSupport.wireCopyOverride(tree);
            tree.addListener(SWT.Selection, e ->
            {
                if (e.detail != SWT.CHECK)
                    return;
                if (e.item instanceof TreeItem item && item.getData() instanceof TypeItem type
                    && findDefinedType(type) != null)
                    toggleDefinedType(type);
                // Остальные строки менять нельзя — возвращаем расчётное состояние сразу.
                syncSourceChecks();
            });
        }

        /**
         * Определяемый тип: отмечен, если в его составе есть тип объекта (серый — только набор типов).
         * Остальные строки: отмечена та, через которую объект подключён к подписке.
         */
        private void syncSourceChecks()
        {
            if (sourceViewer == null || sourceViewer.getControl().isDisposed())
                return;
            for (TreeItem item : sourceViewer.getTree().getItems())
            {
                if (!(item.getData() instanceof TypeItem type))
                    continue;
                if (findDefinedType(type) != null)
                {
                    int state = definedTypeState(type);
                    setCheck(item, state != 0, state == 2);
                }
                else
                    setCheck(item, filterSources.contains(type), false);
            }
        }

        /**
         * Список источников строится при выборе подписки, а запись в модель идёт позже — без
         * пересчёта он показывает состояние «на шаг назад». Вызывается после завершения записи.
         */
        private void refreshSourceList()
        {
            if (sourceViewer == null || sourceViewer.getControl().isDisposed())
                return;
            sourceViewer.refresh();
            if (marksViewer != null)
                selectConnectedSource(marksViewer.getStructuredSelection());
            syncSourceChecks();
        }

        private EventSubscription currentSubscription()
        {
            return marksViewer != null
                && marksViewer.getStructuredSelection().getFirstElement() instanceof EventSubscription subscription
                    ? subscription : null;
        }

        private void toggleDefinedType(TypeItem definedTypeItem)
        {
            DefinedType definedType = findDefinedType(definedTypeItem);
            EventSubscription subscription = currentSubscription();
            if (definedType == null || subscription == null)
                return;
            int state = definedTypeState(definedTypeItem);
            if (state == 2)
            {
                ToastNotification.show(PAGE_TITLE,
                    "Определяемый тип содержит набор типов целиком — объект нельзя отключить от него отдельно", 5_000); //$NON-NLS-1$
                return;
            }
            TypeItem toAdd = null;
            if (state == 0)
            {
                toAdd = typeForEvent(subscription);
                if (toAdd == null)
                {
                    ToastNotification.show(PAGE_TITLE,
                        "Событие подписки не поддерживается типами объекта", 5_000); //$NON-NLS-1$
                    return;
                }
            }
            TypeItem addFinal = toAdd;
            Job job = new Job("Комфорт: изменение определяемого типа") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    applyDefinedTypeChange(definedType, addFinal);
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            job.schedule();
        }

        private void applyDefinedTypeChange(DefinedType definedType, TypeItem typeToAdd)
        {
            try
            {
                IBmModelManager models = (IBmModelManager)Global.getServiceByClass(IBmModelManager.class);
                IBmModel model = models != null ? models.getModel(definedType) : null;
                if (model == null)
                    throw new IllegalStateException("BM-модель определяемого типа не найдена"); //$NON-NLS-1$
                Global.tempLog(MARKS_LOG, "определяемый тип " + definedType.getName() //$NON-NLS-1$
                    + (typeToAdd != null ? ": добавить " + typeToAdd.getName() : ": убрать типы объекта")); //$NON-NLS-1$ //$NON-NLS-2$
                model.execute(new AbstractBmTask<Void>("comfort.definedTypeMarks") //$NON-NLS-1$
                {
                    @Override
                    public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        DefinedType target = transaction.toTransactionObject(definedType);
                        TypeDescription description = target.getTypeDescription();
                        if (description == null)
                            return null;
                        if (typeToAdd != null)
                        {
                            TypeItem type = typeToAdd instanceof IBmObject
                                ? transaction.toTransactionObject(typeToAdd) : typeToAdd;
                            if (description.getTypes().stream().noneMatch(t -> sameType(t, type)))
                                description.getTypes().add(type);
                        }
                        else
                            description.getTypes()
                                .removeIf(t -> producedTypes.stream().anyMatch(p -> sameType(t, p)));
                        return null;
                    }
                });
            }
            catch (RuntimeException e)
            {
                Global.tempLog(MARKS_LOG, "ошибка записи определяемого типа: " + e); //$NON-NLS-1$
                Global.logError(TAG, "apply defined type change", e); //$NON-NLS-1$
                Display display = Display.getDefault();
                if (!display.isDisposed())
                    display.asyncExec(() -> ToastNotification.show(PAGE_TITLE,
                        "Не удалось изменить определяемый тип: " + e.getMessage(), 6_000)); //$NON-NLS-1$
                return;
            }
            Display display = Display.getDefault();
            if (!display.isDisposed())
                display.asyncExec(() ->
                {
                    refreshDefinedTypeSources();
                    refreshSourceList();
                    syncSourceChecks();
                    scheduleSyncChecks();
                    if (getEditor() instanceof DtGranularEditor<?> granularEditor)
                        MdEditorTabsHook.requestRefresh(granularEditor);
                });
        }

        private boolean syncChecksScheduled;

        private void scheduleSyncChecks()
        {
            if (syncChecksScheduled || marksViewer == null || marksViewer.getControl().isDisposed())
                return;
            syncChecksScheduled = true;
            marksViewer.getControl().getDisplay().asyncExec(() ->
            {
                syncChecksScheduled = false;
                if (marksViewer == null || marksViewer.getControl().isDisposed())
                    return;
                Tree tree = marksViewer.getTree();
                // Строки дерева — новые объекты, значит его перестроили (штатный обработчик
                // событий BM зовёт refresh, а папки событий каждый раз создаются заново).
                // Пока не перестраивали — запоминаем состояние, после — возвращаем.
                // JFace при пересборке переиспользует те же TreeItem для новых элементов, поэтому
                // сравниваются не строки, а их данные (папки событий создаются заново).
                boolean rebuilt = lastRoots != null && !sameData(rootData(tree), lastRoots);
                stateLog("sync: раскрыто до=" + expandedCount(tree)); //$NON-NLS-1$
                stateLog("sync: rebuilt=" + rebuilt + " lastRootsNull=" + (lastRoots == null) //$NON-NLS-1$ //$NON-NLS-2$
                    + " roots=" + tree.getItemCount() + " selection=" + selectionKeys(tree) //$NON-NLS-1$ //$NON-NLS-2$
                    + " search='" + searchText() + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                if (rebuilt)
                    restoreTreeState(tree);
                else
                    captureTreeState(tree);
                lastRoots = rootData(tree);
                stateLog("sync: раскрыто перед syncChecks=" + expandedCount(tree)); //$NON-NLS-1$
                syncChecks(tree.getItems());
                stateLog("sync: раскрыто после syncChecks=" + expandedCount(tree)); //$NON-NLS-1$
            });
        }

        /** Подписки, пометку которых сняли: остаются в списке, пока дерево не перестроят осознанно. */
        private final Set<String> pinnedNames = new HashSet<>();

        private PinFilter pinFilter;

        /** Данные корневых строк на момент последней сверки (по ним узнаём, что дерево пересобрано). */
        private Object[] lastRoots;

        private Set<String> previousExpanded = new HashSet<>();

        private static Object[] rootData(Tree tree)
        {
            TreeItem[] items = tree.getItems();
            Object[] data = new Object[items.length];
            for (int i = 0; i < items.length; i++)
                data[i] = items[i].getData();
            return data;
        }

        private static boolean sameData(Object[] a, Object[] b)
        {
            if (a.length != b.length)
                return false;
            for (int i = 0; i < a.length; i++)
                if (a[i] != b[i])
                    return false;
            return true;
        }

        private final Set<String> expandedKeys = new HashSet<>();

        /** Свёртки, какими они были без поискового текста — их возвращает очистка поиска. */
        private final Set<String> baseExpandedKeys = new HashSet<>();

        private final List<String> selectedKeys = new ArrayList<>();

        private String topKey;

        private static final String STATE_LOG = "eventhandlers-tree-state"; //$NON-NLS-1$

        /** Безусловная диагностика состояния дерева (временная, по правилам репозитория). */
        private static void stateLog(String message)
        {
            Global.tempLog(STATE_LOG, message);
        }

        private static List<String> selectionKeys(Tree tree)
        {
            List<String> keys = new ArrayList<>();
            for (TreeItem item : tree.getSelection())
                keys.add(String.valueOf(rowKey(item.getData())));
            return keys;
        }

        private static boolean sameItems(TreeItem[] a, TreeItem[] b)
        {
            if (a.length != b.length)
                return false;
            for (int i = 0; i < a.length; i++)
                if (a[i] != b[i])
                    return false;
            return true;
        }

        /** Ключ строки, переживающий пересборку дерева: подписка — по имени, событие — по имени события. */
        private static String rowKey(Object data)
        {
            if (data instanceof EventSubscription subscription)
                return "S:" + subscription.getName(); //$NON-NLS-1$
            if (data != null && data.getClass().getName().endsWith(".EventFolder")) //$NON-NLS-1$
            {
                Object event = Global.invoke(data, "getEvent"); //$NON-NLS-1$
                if (event instanceof com._1c.g5.v8.dt.mcore.Event mcoreEvent)
                    return "F:" + mcoreEvent.getName(); //$NON-NLS-1$
            }
            return null;
        }

        /** Дерево раскрывается целиком при (пере)сборке, если наложен поиск по подстроке ИЛИ включён «Только помеченные». */
        private boolean autoExpandAll()
        {
            return onlyMarked || !searchText().isEmpty();
        }

        /** Текст поиска дерева подписок (пусто — поиск не задан). */
        private String searchText()
        {
            Object mainSection = Global.invoke(embeddedEditor, "getMainSection"); //$NON-NLS-1$
            Object box = mainSection != null ? Global.getField(mainSection, "searchBox") : null; //$NON-NLS-1$
            Object text = box != null ? Global.invoke(box, "getText") : null; //$NON-NLS-1$
            return text instanceof String s ? s.trim() : ""; //$NON-NLS-1$
        }

        /**
         * Очистка поиска: штатный код раскрыл бы всё дерево. Вместо этого — свёртки, какими они были
         * до поиска, и текущая строка (её ветка при необходимости раскрывается).
         */
        private void restoreAfterSearchCleared()
        {
            if (marksViewer == null || marksViewer.getControl().isDisposed())
                return;
            Tree tree = marksViewer.getTree();
            stateLog("clear: вызвано; base=" + baseExpandedKeys.size() + " selectedKeys=" + selectedKeys //$NON-NLS-1$ //$NON-NLS-2$
                + " selectionNow=" + selectionKeys(tree) + " roots=" + tree.getItemCount()); //$NON-NLS-1$ //$NON-NLS-2$
            tree.getDisplay().asyncExec(() ->
            {
                if (tree.isDisposed())
                    return;
                stateLog("clear: onlyMarked=" + onlyMarked + " selectedKeys=" + selectedKeys + " selectionNow=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + selectionKeys(tree) + " roots=" + tree.getItemCount()); //$NON-NLS-1$
                // «Только помеченные»: дерево остаётся как есть (свёртки, накопленные к этому
                // моменту, возвращает restoreTreeState). Иначе — свёртки «до поиска».
                if (!onlyMarked)
                {
                    marksViewer.collapseAll();
                    expandedKeys.clear();
                    expandedKeys.addAll(baseExpandedKeys);
                }
                restoreTreeState(tree);
                lastRoots = rootData(tree);
                stateLog("clear: готово selectionNow=" + selectionKeys(tree)); //$NON-NLS-1$
                scheduleSyncChecks();
            });
        }

        private void captureTreeState(Tree tree)
        {
            Set<String> before = new HashSet<>(expandedKeys);
            expandedKeys.clear();
            collectExpanded(tree.getItems());
            Set<String> added = new HashSet<>(expandedKeys);
            added.removeAll(before);
            Set<String> removed = new HashSet<>(before);
            removed.removeAll(expandedKeys);
            if (!added.isEmpty() || !removed.isEmpty())
                stateLog("capture: раскрыто +" + added + " свёрнуто -" + removed); //$NON-NLS-1$ //$NON-NLS-2$
            // Пока задан поиск, дерево раскрыто под результаты — свёртки «до поиска» не затираем.
            if (searchText().isEmpty())
            {
                baseExpandedKeys.clear();
                baseExpandedKeys.addAll(expandedKeys);
            }
            selectedKeys.clear();
            for (TreeItem item : tree.getSelection())
            {
                String key = rowKey(item.getData());
                if (key != null)
                    selectedKeys.add(key);
            }
            TreeItem top = tree.getTopItem();
            topKey = top != null ? rowKey(top.getData()) : null;
            stateLog("capture: expanded=" + expandedKeys.size() + " base=" + baseExpandedKeys.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " selectedKeys=" + selectedKeys + " top=" + topKey + " search='" + searchText() + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        private void collectExpanded(TreeItem[] items)
        {
            for (TreeItem item : items)
            {
                if (!item.getExpanded())
                    continue;
                String key = rowKey(item.getData());
                if (key != null)
                    expandedKeys.add(key);
                collectExpanded(item.getItems());
            }
        }

        private void restoreTreeState(Tree tree)
        {
            stateLog("restore: expandedKeys=" + expandedKeys.size() + " selectedKeys=" + selectedKeys //$NON-NLS-1$ //$NON-NLS-2$
                + " top=" + topKey + " roots=" + tree.getItemCount()); //$NON-NLS-1$ //$NON-NLS-2$
            tree.setRedraw(false);
            try
            {
                if (autoExpandAll())
                    marksViewer.expandAll();
                else
                    expandSaved(tree.getItems());
                List<Object> selection = new ArrayList<>();
                for (String key : selectedKeys)
                {
                    TreeItem item = findRow(tree.getItems(), key);
                    if (item != null)
                    {
                        selection.add(item.getData());
                        stateLog("restore: " + key + " найдена среди строк"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    else
                    {
                        // Ветка строки свёрнута и её строки ещё не созданы — ищем по модели,
                        // reveal при выделении раскроет путь.
                        Object element = findElementInModel(tree, key);
                        stateLog("restore: " + key + (element != null ? " найдена в модели" : " НЕ найдена")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        if (element != null)
                            selection.add(element);
                    }
                }
                stateLog("restore: после expandSaved раскрыто=" + expandedCount(tree)); //$NON-NLS-1$
                if (!selection.isEmpty())
                {
                    marksViewer.setSelection(new StructuredSelection(selection), true);
                    stateLog("restore: выделено, selectionNow=" + selectionKeys(tree)); //$NON-NLS-1$
                }
                TreeItem top = topKey != null ? findRow(tree.getItems(), topKey) : null;
                if (top != null)
                    tree.setTopItem(top);
                // Прежняя верхняя строка может оказаться выше/ниже выделенной после перестройки.
                tree.showSelection();
            }
            finally
            {
                tree.setRedraw(true);
            }
        }

        /** Элемент по ключу среди детей корневых узлов через content provider (без созданных строк). */
        private Object findElementInModel(Tree tree, String key)
        {
            if (!(marksViewer.getContentProvider() instanceof org.eclipse.jface.viewers.ITreeContentProvider provider))
                return null;
            for (TreeItem root : tree.getItems())
            {
                Object[] children = provider.getChildren(root.getData());
                if (children == null)
                    continue;
                for (Object child : children)
                    if (key.equals(rowKey(child)) && passesFilters(root.getData(), child))
                        return child;
            }
            return null;
        }

        private boolean passesFilters(Object parent, Object element)
        {
            for (ViewerFilter filter : marksViewer.getFilters())
                if (!filter.select(marksViewer, parent, element))
                    return false;
            return true;
        }

        private void expandSaved(TreeItem[] items)
        {
            for (TreeItem item : items)
            {
                String key = rowKey(item.getData());
                if (key == null || !expandedKeys.contains(key))
                    continue;
                marksViewer.setExpandedState(item.getData(), true);
                expandSaved(item.getItems());
            }
        }

        private static TreeItem findRow(TreeItem[] items, String key)
        {
            for (TreeItem item : items)
            {
                if (key.equals(rowKey(item.getData())))
                    return item;
                if (item.getExpanded())
                {
                    TreeItem found = findRow(item.getItems(), key);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        /**
         * Штатный фильтр подписок плюс исключение для {@link #pinnedNames}: подписка со снятой
         * пометкой (и её событие) остаётся видимой, пока фильтр не пересоберут.
         */
        private final class PinFilter extends ViewerFilter
        {
            private final ViewerFilter stock;

            PinFilter(ViewerFilter stock)
            {
                this.stock = stock;
            }

            @Override
            public boolean select(org.eclipse.jface.viewers.Viewer viewer, Object parentElement, Object element)
            {
                if (!pinnedNames.isEmpty())
                {
                    if (element instanceof EventSubscription subscription
                        && pinnedNames.contains(subscription.getName()))
                        return true;
                    if (element != null && element.getClass().getName().endsWith(".EventFolder") //$NON-NLS-1$
                        && Global.invoke(element, "getSubscriptions") instanceof Collection<?> subscriptions) //$NON-NLS-1$
                    {
                        for (Object subscription : subscriptions)
                            if (subscription instanceof EventSubscription s && pinnedNames.contains(s.getName()))
                                return true;
                    }
                }
                return stock.select(viewer, parentElement, element);
            }
        }

        /**
         * Подписка — по модели ({@link #markState}); событие — по видимым под ним подпискам:
         * отмечено, если отмечена хоть одна, серым — если не все.
         */
        private void syncChecks(TreeItem[] items)
        {
            for (TreeItem item : items)
            {
                Object data = item.getData();
                if (data instanceof EventSubscription subscription)
                {
                    int state = markState(subscription);
                    setCheck(item, state != 0, state == 2);
                    continue;
                }
                if (item.getExpanded())
                    syncChecks(item.getItems());
                // Сводка — по подпискам события, прошедшим фильтры дерева, а не по созданным строкам:
                // у свёрнутой ветки дочерних строк нет, и флажок иначе сбрасывался бы при сворачивании.
                int total = 0;
                int marked = 0;
                if (Global.invoke(data, "getSubscriptions") instanceof Collection<?> subscriptions //$NON-NLS-1$
                    && rowKey(data) != null)
                {
                    for (Object candidate : subscriptions)
                    {
                        if (candidate instanceof EventSubscription subscription
                            && passesTreeFilters(data, subscription))
                        {
                            total++;
                            if (markState(subscription) != 0)
                                marked++;
                        }
                    }
                }
                setCheck(item, marked > 0, marked > 0 && marked < total);
            }
        }

        private boolean passesTreeFilters(Object folder, EventSubscription subscription)
        {
            for (ViewerFilter filter : marksViewer.getFilters())
                if (!filter.select(marksViewer, folder, subscription))
                    return false;
            return true;
        }

        private static void setCheck(TreeItem item, boolean checked, boolean grayed)
        {
            if (item.getChecked() != checked)
                item.setChecked(checked);
            if (item.getGrayed() != grayed)
                item.setGrayed(grayed);
        }

        /** Переключатель в панели над деревом подписок (штатный SWT-ToolBar подсекции). */
        private void installOnlyMarkedToggle()
        {
            Object mainSection = Global.invoke(embeddedEditor, "getMainSection"); //$NON-NLS-1$
            Object section = mainSection != null ? Global.getField(mainSection, "eventHandlersSection") : null; //$NON-NLS-1$
            Object client = section != null ? Global.invoke(section, "getTextClient") : null; //$NON-NLS-1$
            if (!(client instanceof ToolBar bar) || bar.isDisposed())
            {
                Global.tempLog(MARKS_LOG, "панель дерева не найдена: " + client); //$NON-NLS-1$
                return;
            }
            ToolItem item = new ToolItem(bar, SWT.CHECK);
            item.setText("Только помеченные"); //$NON-NLS-1$
            item.setSelection(true);
            item.setToolTipText(TooltipText.wrap(bar,
                "Только помеченные.\nВыключено — показать все подписки на события, совместимые с объектом." //$NON-NLS-1$
                    + Global.pluginSignForTooltip()));
            item.addListener(SWT.Selection, e ->
            {
                boolean marked = item.getSelection();
                if (!marked && compatibleEvents().isEmpty())
                {
                    item.setSelection(true);
                    ToastNotification.show(PAGE_TITLE, "Не найдены события, совместимые с объектом", 4_000); //$NON-NLS-1$
                    return;
                }
                onlyMarked = marked;
                configureObjectFilter(embeddedEditor, true);
            });
            if (bar.getParent() != null)
                bar.getParent().layout(true, true);
            if (host != null && !host.isDisposed())
                host.layout(true, true);
        }

        private void toggleMarks(List<EventSubscription> subscriptions)
        {
            boolean mark = markState(subscriptions.get(0)) == 0;
            List<SourceChange> changes = new ArrayList<>();
            int inherited = 0;
            int incompatible = 0;
            int definedTypes = 0;
            for (EventSubscription subscription : subscriptions)
            {
                int state = markState(subscription);
                if (mark)
                {
                    if (state != 0)
                        continue;
                    if (hasDefinedType(subscription))
                    {
                        definedTypes++;
                        continue;
                    }
                    TypeItem type = typeForEvent(subscription);
                    if (type == null)
                        incompatible++;
                    else
                        changes.add(new SourceChange(subscription, type));
                }
                else if (state == 1)
                    changes.add(new SourceChange(subscription, null));
                else if (state == 2)
                    inherited++;
            }
            if (definedTypes > 0)
                ToastNotification.show(PAGE_TITLE,
                    "Источник подписки — определяемый тип. Добавить тип в такой источник нельзя, но можно добавить тип в сам определяемый тип.", 5_000); //$NON-NLS-1$
            else if (inherited > 0)
                ToastNotification.show(PAGE_TITLE,
                    "Подписка задана набором типов целиком — объект нельзя отключить от неё отдельно", 5_000); //$NON-NLS-1$
            else if (incompatible > 0)
                ToastNotification.show(PAGE_TITLE,
                    "Событие подписки не поддерживается типами объекта", 5_000); //$NON-NLS-1$
            if (changes.isEmpty())
                return;
            for (SourceChange change : changes)
                if (change.typeToAdd() == null)
                    pinnedNames.add(change.subscription().getName());

            Job job = new Job("Комфорт: подключение объекта к подпискам") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    applyChanges(changes);
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            job.schedule();
        }

        /** В «Источнике» есть определяемый тип ({@code DefinedType.Имя} / «ОпределяемыйТип.Имя»). */
        private boolean hasDefinedType(EventSubscription subscription)
        {
            TypeDescription source = subscription.getSource();
            if (source == null)
                return false;
            for (TypeItem type : source.getTypes())
                if (isDefinedTypeItem(type))
                    return true;
            return false;
        }

        /** Тип объекта, у которого есть событие подписки (имя события — русское или английское). */
        private TypeItem typeForEvent(EventSubscription subscription)
        {
            String eventName = subscription.getEvent();
            if (eventName == null)
                return null;
            for (Object produced : producedTypes)
            {
                if (!(produced instanceof Type type))
                    continue;
                for (com._1c.g5.v8.dt.mcore.Event event : type.allEvents())
                    if (eventName.equals(event.getName()) || eventName.equals(event.getNameRu()))
                        return type;
            }
            return null;
        }

        private static boolean sameType(Object a, Object b)
        {
            if (a == b || (a != null && a.equals(b)))
                return true;
            if (a instanceof EObject ea && b instanceof EObject eb)
                return EcoreUtil.getURI(ea).equals(EcoreUtil.getURI(eb));
            return false;
        }

        private void applyChanges(List<SourceChange> changes)
        {
            EventSubscription first = changes.get(0).subscription();
            try
            {
                IBmModelManager models = (IBmModelManager)Global.getServiceByClass(IBmModelManager.class);
                IBmModel model = models != null ? models.getModel(first) : null;
                if (model == null)
                    throw new IllegalStateException("BM-модель подписки не найдена"); //$NON-NLS-1$
                Global.tempLog(MARKS_LOG, "запись: " + changes.size() + " подписок"); //$NON-NLS-1$ //$NON-NLS-2$
                model.execute(new AbstractBmTask<Void>("comfort.eventSubscriptionMarks") //$NON-NLS-1$
                {
                    @Override
                    public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        for (SourceChange change : changes)
                        {
                            EventSubscription subscription =
                                transaction.toTransactionObject(change.subscription());
                            TypeDescription source = subscription.getSource();
                            if (source == null)
                                continue;
                            TypeItem toAdd = change.typeToAdd();
                            if (toAdd != null)
                            {
                                TypeItem type = toAdd instanceof IBmObject
                                    ? transaction.toTransactionObject(toAdd) : toAdd;
                                if (source.getTypes().stream().noneMatch(t -> sameType(t, type)))
                                    source.getTypes().add(type);
                            }
                            else
                                source.getTypes().removeIf(t -> producedTypes.stream().anyMatch(p -> sameType(t, p)));
                            Global.tempLog(MARKS_LOG, "изменена подписка " + subscription.getName()); //$NON-NLS-1$
                        }
                        return null;
                    }
                });
            }
            catch (RuntimeException e)
            {
                Global.tempLog(MARKS_LOG, "ошибка записи: " + e);  //$NON-NLS-1$
                Global.logError(TAG, "apply subscription marks", e); //$NON-NLS-1$
                Display display = Display.getDefault();
                if (!display.isDisposed())
                    display.asyncExec(() -> ToastNotification.show(PAGE_TITLE,
                        "Не удалось изменить подписку: " + e.getMessage(), 6_000)); //$NON-NLS-1$
                return;
            }
            Display display = Display.getDefault();
            if (display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                // Дерево здесь не трогаем: штатный редактор сам перестроит его по событию BM,
                // а сверка состояния (scheduleSyncChecks) вернёт свёртки и текущую строку.
                scheduleSyncChecks();
                refreshSourceList();
                if (getEditor() instanceof DtGranularEditor<?> granularEditor)
                    MdEditorTabsHook.requestRefresh(granularEditor);
            });
        }

        // =========================================================================
        // Двойной клик / Enter («Открыть»)
        // =========================================================================

        /**
         * Штатный {@code SubSection$ViewerDoubleClickListener} EDT создаёт
         * {@code OpenObjectHandler} напрямую (мимо команды — мост не срабатывает)
         * с {@code activeEditor} из состояния workbench, а там — гранулярная
         * оболочка: обработчик молча выходит. Наш слушатель на каждом дереве
         * страницы повторяет вызов обработчика с корректным контекстом.
         *
         * <p>Дерево подписок исключено: там двойной клик — «Открыть обработчик»
         * ({@link EventHandlersOpenHandlerHook}), иначе открывались бы сразу и
         * объект, и обработчик.
         */
        private void installDoubleClickOpen()
        {
            Tree eventHandlersTree = EventHandlersOpenHandlerHook.resolveEventHandlersTree(embeddedEditor);
            for (Tree tree : collectTrees(host))
            {
                if (tree == eventHandlersTree)
                    continue;
                tree.addListener(SWT.DefaultSelection, e -> openTreeSelection((Tree)e.widget));
            }
        }

        private static List<Tree> collectTrees(Composite composite)
        {
            List<Tree> result = new ArrayList<>();
            collectTrees(composite, result);
            return result;
        }

        private static void collectTrees(Composite composite, List<Tree> result)
        {
            for (Control child : composite.getChildren())
            {
                if (child instanceof Tree tree)
                    result.add(tree);
                else if (child instanceof Composite inner)
                    collectTrees(inner, result);
            }
        }

        /**
         * Элемент строки ведёт на объект метаданных этой страницы. Как в штатном
         * {@code OpenObjectHandler}: {@code MdObject} — сам объект, {@code TypeItem} —
         * его объект-контейнер, {@code MethodContainer} — контейнер его {@code getOwnerTypeItem()}.
         */
        private boolean isHostObject(Object element)
        {
            Object target = element;
            if (target != null && target.getClass().getName().endsWith(".MethodContainer")) //$NON-NLS-1$
                target = Global.invoke(target, "getOwnerTypeItem"); //$NON-NLS-1$
            if (!(target instanceof EObject eObject))
                return false;

            MdObject object = null;
            for (EObject current = eObject; current != null; current = current.eContainer())
            {
                if (current instanceof MdObject found)
                {
                    object = found;
                    break;
                }
            }
            if (object == null)
                return false;

            return object == mdObject
                || (EcoreUtil.getURI(object) != null && EcoreUtil.getURI(object).equals(EcoreUtil.getURI(mdObject)));
        }

        private void openTreeSelection(Tree tree)
        {
            if (embeddedEditor == null || pageBundle == null || pageInjector == null)
                return;
            TreeItem[] items = tree.getSelection();
            if (items.length == 0)
                return;

            List<Object> elements = new ArrayList<>(items.length);
            for (TreeItem item : items)
                if (item.getData() != null)
                    elements.add(item.getData());
            if (elements.isEmpty())
                return;

            // Строка источника/обработчика, ведущая на объект ЭТОГО же редактора: открывать
            // его заново не нужно — редактор уже открыт, страница подписок в нём и находится.
            if (elements.size() == 1 && isHostObject(elements.get(0)))
                return;

            try
            {
                IHandlerService handlerService =
                    PlatformUI.getWorkbench().getService(IHandlerService.class);
                if (handlerService == null)
                    return;

                StructuredSelection selection = new StructuredSelection(elements);
                EvaluationContext context =
                    new EvaluationContext(handlerService.getCurrentState(), selection);
                context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);
                context.addVariable(ISources.ACTIVE_EDITOR_NAME, embeddedEditor);
                Object input = Global.invoke(embeddedEditor, "getEditorInput"); //$NON-NLS-1$
                if (input != null)
                    context.addVariable(ISources.ACTIVE_EDITOR_INPUT_NAME, input);

                ExecutionEvent event =
                    new ExecutionEvent(null, new HashMap<>(), null, context);

                Object handler = pageInjector.getInstance(pageBundle.loadClass(OPEN_OBJECT_HANDLER_CLASS));
                Global.invoke(handler, "execute", event); //$NON-NLS-1$
            }
            catch (Exception | LinkageError e)
            {
                Global.logError(TAG, "double click open", e); //$NON-NLS-1$
            }
        }

        // =========================================================================
        // Мост команд (исправление «Открыть» и соседних команд контекстного меню)
        // =========================================================================

        /**
         * Команды {@code com._1c.g5.v8.dt.eventhandlers.ui.*} («Открыть», фильтры, Delete)
         * ищут редактор через {@code HandlerUtil.getActiveEditor}, а активна — гранулярная
         * оболочка, не встроенный редактор: штатные обработчики молча бездействуют.
         * Мост перевыполняет команду в контексте, где {@code activeEditor} — встроенный
         * редактор (штатный обработчик затем отрабатывает; исходный вызов безопасно гаснет).
         */
        private void installCommandBridge()
        {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService == null)
                return;

            commandBridge = new IExecutionListener()
            {
                @Override
                public void preExecute(String commandId, ExecutionEvent event)
                {
                    if (bridging || embeddedEditor == null || !isActivePage())
                        return;
                    if (commandId == null || !commandId.startsWith(BUNDLE_ID + '.')) //$NON-NLS-1$
                        return;

                    bridging = true;
                    try
                    {
                        IHandlerService handlerService =
                            PlatformUI.getWorkbench().getService(IHandlerService.class);
                        if (handlerService == null)
                            return;

                        Object selection = getTreeSelection();
                        IEvaluationContext context =
                            new EvaluationContext(handlerService.getCurrentState(), selection);
                        context.addVariable(ISources.ACTIVE_EDITOR_NAME, embeddedEditor);
                        Object input = Global.invoke(embeddedEditor, "getEditorInput"); //$NON-NLS-1$
                        if (input != null)
                            context.addVariable(ISources.ACTIVE_EDITOR_INPUT_NAME, input);
                        if (selection != null)
                            context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);

                        Event trigger =
                            event.getTrigger() instanceof Event swtEvent ? swtEvent : null;
                        Command command = commandService.getCommand(commandId);
                        handlerService.executeCommandInContext(
                            new ParameterizedCommand(command, null), trigger, context);
                    }
                    catch (Exception e)
                    {
                        Global.logError(TAG, "bridge command " + commandId, e); //$NON-NLS-1$
                    }
                    finally
                    {
                        bridging = false;
                    }
                }

                @Override public void postExecuteSuccess(String commandId, Object returnValue) {}
                @Override public void postExecuteFailure(String commandId, ExecutionException exception) {}
                @Override public void notHandled(String commandId, NotHandledException exception) {}
            };
            commandService.addExecutionListener(commandBridge);
        }

        private void removeCommandBridge()
        {
            if (commandBridge == null)
                return;
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService != null)
                commandService.removeExecutionListener(commandBridge);
            commandBridge = null;
        }

        private Object getTreeSelection()
        {
            Object mainSection = Global.invoke(embeddedEditor, "getMainSection"); //$NON-NLS-1$
            return mainSection != null ? Global.invoke(mainSection, "getSelection") : null; //$NON-NLS-1$
        }

        private boolean isActivePage()
        {
            if (!(getEditor() instanceof DtGranularEditor<?> granularEditor))
                return false;
            // Сайт страницы (init) не вызывается — берём сайт granular-редактора
            if (granularEditor.getSite() == null || granularEditor.getSite().getPage() == null)
                return false;
            return granularEditor.getSite().getPage().getActiveEditor() == granularEditor
                && granularEditor.getActivePageInstance() == EventHandlersPage.this;
        }

        /**
         * Совпадает ли текущий фильтр страницы с целевым (по производным типам объекта).
         * Сравниваются источники как множество; события/обработчики и флаг
         * «все источники» должны быть сброшены — это состояние после нашего
         * {@link #configureObjectFilter}.
         */
        private boolean isObjectFilterApplied(Object editor)
        {
            Object mainSection = Global.invoke(editor, "getMainSection"); //$NON-NLS-1$
            if (mainSection == null)
                return false;
            Object filter = Global.invoke(mainSection, "getEventHandlersFilter"); //$NON-NLS-1$
            if (filter == null)
                return false;

            @SuppressWarnings("unchecked")
            Collection<Object> sources =
                (Collection<Object>)Global.invoke(filter, "getSources"); //$NON-NLS-1$
            // Сравнение как множество: источники фильтра (HashSet) против набора производных
            // типов объекта (устойчиво к дублям в списке).
            if (sources == null)
                return false;
            @SuppressWarnings("unchecked")
            Collection<Object> events =
                (Collection<Object>)Global.invoke(filter, "getEvents"); //$NON-NLS-1$
            if (onlyMarked)
            {
                if (sources.size() != new HashSet<>(filterSources).size()
                    || !sources.containsAll(filterSources))
                    return false;
                if (events != null && !events.isEmpty())
                    return false;
            }
            else
            {
                if (!sources.isEmpty())
                    return false;
                Set<String> actual = new HashSet<>();
                if (events != null)
                    for (Object event : events)
                        actual.add(((com._1c.g5.v8.dt.mcore.Event)event).getName());
                if (!actual.equals(compatibleEventNames()))
                    return false;
            }

            @SuppressWarnings("unchecked")
            Collection<Object> handlers =
                (Collection<Object>)Global.invoke(filter, "getHandlers"); //$NON-NLS-1$
            if (handlers != null && !handlers.isEmpty())
                return false;

            return !Boolean.TRUE.equals(Global.invoke(filter, "isContainAllSources")); //$NON-NLS-1$
        }

        /**
         * Фильтр по производным типам объекта — как у команды «Найти подписки на события → Все».
         * Вызывается и при первом наполнении, и при каждой повторной активации страницы
         * (пользователь мог снять фильтр), поэтому должен быть идемпотентным.
         */
        private void configureObjectFilter(Object editor)
        {
            configureObjectFilter(editor, false);
        }

        /**
         * @param keepState перестройка по переключателю «Только помеченные»: свёртки и текущая
         *            строка возвращаются, новые узлы разворачивает {@link TreeExpander}, а не
         *            {@code expandAll}. Первое наполнение и повторный вход на вкладку — как раньше.
         */
        private void configureObjectFilter(Object editor, boolean keepState)
        {
            Object mainSection = Global.invoke(editor, "getMainSection"); //$NON-NLS-1$
            if (mainSection == null)
                return;
            Object filter = Global.invoke(mainSection, "getEventHandlersFilter"); //$NON-NLS-1$
            if (filter == null)
                return;

            Global.invoke(filter, "clear"); //$NON-NLS-1$
            @SuppressWarnings("unchecked")
            Collection<Object> sources =
                (Collection<Object>)Global.invoke(filter, "getSources"); //$NON-NLS-1$
            if (onlyMarked)
            {
                if (sources != null)
                    sources.addAll(filterSources);
            }
            else
            {
                // Все совместимые подписки: отбор по событиям типов объекта, без отбора по источникам.
                @SuppressWarnings("unchecked")
                Collection<Object> events =
                    (Collection<Object>)Global.invoke(filter, "getEvents"); //$NON-NLS-1$
                if (events != null)
                    events.addAll(compatibleEvents());
            }

            TreeViewer viewer = (TreeViewer)Global.invoke(mainSection, "getEventHandlersTreeViewer"); //$NON-NLS-1$
            if (viewer != null && !viewer.getControl().isDisposed())
            {
                // Идемпотентность: addFilter ничего не делает, если такой же фильтр уже добавлен
                // (JFace проверяет через equals), а без refresh пересборка не произойдёт.
                // Штатный фильтр заменяет обёртка: она держит видимыми подписки со снятой
                // пометкой, пока дерево не перестроено по существенной причине (см. этот метод).
                pinnedNames.clear();
                lastRoots = null;
                if (keepState)
                    captureTreeState(viewer.getTree());
                if (pinFilter == null || pinFilter.stock != filter)
                    pinFilter = new PinFilter((ViewerFilter)filter);
                viewer.removeFilter((ViewerFilter)filter);
                viewer.addFilter(pinFilter);
                viewer.refresh();
                if (keepState)
                    restoreTreeState(viewer.getTree());
            }

            Global.invoke(mainSection, "refreshExpandFilter"); //$NON-NLS-1$
            Global.invoke(mainSection, "enableExpandFilter"); //$NON-NLS-1$

            if (viewer != null && !viewer.getControl().isDisposed())
            {
                if (keepState)
                {
                    if (autoExpandAll())
                        viewer.expandAll();
                    else
                        TreeExpander.notifyContentLoaded(viewer);
                    // Развёртывание выше выделенной строки сдвигает её — возвращаем в видимую область.
                    viewer.getTree().showSelection();
                }
                else
                    viewer.expandAll();
                if (isActivePage())
                    viewer.getTree().setFocus();
            }
        }

        /**
         * Встроенный {@code EventHandlersEditor} рисует свою шапку формы
         * («Все подписки на события»). На вкладке редактора объекта её место занимает
         * штатный заголовок {@link DtGranularEditorPage}.
         *
         * <p>В бандле forms EDT нет {@code Form.setHeadVisible}. {@code Form} создаётся
         * с {@code SWT.NO_BACKGROUND}, а штатный layout резервирует высоту шапки даже
         * при {@code setVisible(false)} — получается прозрачная полоса, через которую
         * видны соседние вкладки. Поэтому шапку не только прячем, но и сжимаем в 0
         * после каждой раскладки формы.
         */
        private void hideEmbeddedFormHeading()
        {
            ScrolledForm inner = findScrolledForm(host);
            if (inner == null || inner.isDisposed())
                return;
            Form form = inner.getForm();
            if (form == null || form.isDisposed())
                return;

            inner.setText(""); //$NON-NLS-1$
            form.setText(""); //$NON-NLS-1$
            form.setImage(null);
            form.setSeparatorVisible(false);

            Composite head = form.getHead();
            if (head != null && !head.isDisposed())
                head.setVisible(false);

            if (form.getData(KEY_HEAD_COLLAPSED) == null)
            {
                form.setData(KEY_HEAD_COLLAPSED, Boolean.TRUE);
                // Form.layout() во время WM_SIZE идёт после Resize-слушателей и
                // перезаписывает границы. Сжатие — asyncExec, после полной раскладки.
                form.addListener(SWT.Resize, e -> scheduleCollapseEmbeddedFormHead(form));
                if (head != null && !head.isDisposed())
                    head.addListener(SWT.Resize, e ->
                    {
                        if (!head.isDisposed() && head.getSize().y > 0)
                            scheduleCollapseEmbeddedFormHead(form);
                    });
            }

            form.layout(true, true);
            if (host != null && !host.isDisposed())
                host.layout(true, true);
            collapseEmbeddedFormHead(form);
        }

        /**
         * Ставит сжатие шапки в очередь UI: штатный {@code FormLayout} успеет
         * закончить текущую раскладку и не перезапишет границы в том же ходе.
         */
        private static void scheduleCollapseEmbeddedFormHead(Form form)
        {
            if (form == null || form.isDisposed())
                return;
            if (Boolean.TRUE.equals(form.getData(KEY_HEAD_COLLAPSE_SCHEDULED)))
                return;
            form.setData(KEY_HEAD_COLLAPSE_SCHEDULED, Boolean.TRUE);
            form.getDisplay().asyncExec(() ->
            {
                if (form.isDisposed())
                    return;
                form.setData(KEY_HEAD_COLLAPSE_SCHEDULED, Boolean.FALSE);
                collapseEmbeddedFormHead(form);
            });
        }

        /**
         * Штатный {@code FormLayout} всегда кладёт тело под шапку. После него
         * сжимаем шапку в 0 и растягиваем тело на всю клиентскую область.
         */
        private static void collapseEmbeddedFormHead(Form form)
        {
            if (form == null || form.isDisposed())
                return;
            Rectangle ca = form.getClientArea();
            Composite head = form.getHead();
            if (head != null && !head.isDisposed())
            {
                if (head.getVisible())
                    head.setVisible(false);
                Point headSize = head.getSize();
                if (headSize.y != 0 || headSize.x != ca.width)
                    head.setBounds(0, 0, ca.width, 0);
            }
            Composite body = form.getBody();
            if (body != null && !body.isDisposed())
            {
                Point loc = body.getLocation();
                Point size = body.getSize();
                if (loc.x != 0 || loc.y != 0 || size.x != ca.width || size.y != ca.height)
                    body.setBounds(0, 0, ca.width, ca.height);
            }
        }

        private static ScrolledForm findScrolledForm(Composite composite)
        {
            if (composite == null || composite.isDisposed())
                return null;
            for (Control child : composite.getChildren())
            {
                if (child instanceof ScrolledForm form)
                    return form;
                if (child instanceof Composite inner)
                {
                    ScrolledForm found = findScrolledForm(inner);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private void showError(String message)
        {
            if (host == null || host.isDisposed())
                return;
            for (Control child : host.getChildren())
                child.dispose();
            Label label = new Label(host, SWT.WRAP);
            label.setText("Не удалось открыть подписки на события: " + message); //$NON-NLS-1$
            host.layout(true, true);
        }
    }

    /**
     * Обёртка штатного {@link IStyledLabelProvider} дерева подписок: всё отдаёт штатному, а на
     * каждое обращение к подписи строки вызывает {@code onLabel} — так узнаём, что строки
     * дерева обновились и флажкам пора сверить состояние.
     */
    private static final class MarkLabels implements IStyledLabelProvider, IColorProvider, IFontProvider,
        IToolTipProvider
    {
        private final IStyledLabelProvider delegate;

        private final Runnable onLabel;

        MarkLabels(IStyledLabelProvider delegate, Runnable onLabel)
        {
            this.delegate = delegate;
            this.onLabel = onLabel;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            onLabel.run();
            return delegate.getStyledText(element);
        }

        @Override
        public Image getImage(Object element)
        {
            return delegate.getImage(element);
        }

        @Override
        public Color getForeground(Object element)
        {
            if (delegate instanceof IColorProvider colors)
                return colors.getForeground(element);
            Object color = Global.invoke(delegate, "getForeground", element); //$NON-NLS-1$
            return color instanceof Color c ? c : null;
        }

        @Override
        public Color getBackground(Object element)
        {
            if (delegate instanceof IColorProvider colors)
                return colors.getBackground(element);
            Object color = Global.invoke(delegate, "getBackground", element); //$NON-NLS-1$
            return color instanceof Color c ? c : null;
        }

        @Override
        public Font getFont(Object element)
        {
            if (delegate instanceof IFontProvider fonts)
                return fonts.getFont(element);
            Object font = Global.invoke(delegate, "getFont", element); //$NON-NLS-1$
            return font instanceof Font f ? f : null;
        }

        @Override
        public String getToolTipText(Object element)
        {
            if (delegate instanceof IToolTipProvider tips)
                return tips.getToolTipText(element);
            Object tip = Global.invoke(delegate, "getToolTipText", element); //$NON-NLS-1$
            return tip instanceof String s ? s : null;
        }

        @Override
        public void addListener(ILabelProviderListener listener)
        {
            delegate.addListener(listener);
        }

        @Override
        public void removeListener(ILabelProviderListener listener)
        {
            delegate.removeListener(listener);
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return delegate.isLabelProperty(element, property);
        }

        @Override
        public void dispose()
        {
            delegate.dispose();
        }
    }
}
