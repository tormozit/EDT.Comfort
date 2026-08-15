package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
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
import org.eclipse.ui.forms.editor.FormPage;
import org.eclipse.ui.handlers.IHandlerService;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.google.inject.Injector;

/**
 * Встраивает страницу «Подписки на события» в редактор объекта метаданных
 * ({@link DtGranularEditor}).
 *
 * <p>Страница воспроизводит результат команды «Все подписки на события» из контекстного
 * меню навигатора: тот же контент (встроенный EDT {@code EventHandlersEditor}), тот же
 * фильтр по производным типам объекта, что и у подменю «Найти подписки на события → Все».
 *
 * <p>Страница добавляется только для объектов с производными типами (ссылочные объекты,
 * регистры, константы) — критерий тот же, что у {@code ProdusedTypesUtil} EDT.
 *
 * <p>Тяжёлое наполнение (создание встроенного редактора и фильтра) выполняется лениво —
 * при первой активации вкладки, а не при открытии редактора.
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

    private static final String EDITOR_CLASS = BUNDLE_ID + ".editor.EventHandlersEditor"; //$NON-NLS-1$

    private static final String EDITOR_INPUT_CLASS = BUNDLE_ID + ".editor.EventHandlersEditorInput"; //$NON-NLS-1$

    private static final String UI_PLUGIN_CLASS =
        "com._1c.g5.v8.dt.internal.eventhandlers.ui.activator.EventHandlersUiPlugin"; //$NON-NLS-1$

    private static final String PRODUSED_TYPES_CLASS = BUNDLE_ID + ".service.ProdusedTypesUtil"; //$NON-NLS-1$

    /** Обработчик «Открыть» (двойной клик EDT вызывает его напрямую, минуя команду). */
    private static final String OPEN_OBJECT_HANDLER_CLASS = BUNDLE_ID + ".handlers.OpenObjectHandler"; //$NON-NLS-1$

    /** Тема временного лога двойного клика по строкам страницы. */
    private static final String OPEN_LOG_TOPIC = "eventhandlers-open-handler"; //$NON-NLS-1$

    private static final String PAGE_ID = "tormozit.mdEventHandlers"; //$NON-NLS-1$

    private static final String PAGE_TITLE = "Подписки на события"; //$NON-NLS-1$

    /** Заголовок ERD-вкладки {@code editors.diagrams.page} — наша страница строго перед ней. */
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

            List<Object> producedTypes = collectProducedTypes((MdObject)model);
            if (producedTypes.isEmpty())
            {
                // Нет производных типов (не ссылочный объект, не регистр, не константа)
                hookedGranularEditors.add(editor);
                return;
            }

            if (!hookedGranularEditors.add(editor))
                return;

            addEventHandlersPage(editor, (MdObject)model, producedTypes);
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
        List<Object> producedTypes)
    {
        try
        {
            EventHandlersPage page = new EventHandlersPage(mdObject, producedTypes);
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
     * Индекс вкладки «Схема данных» (ERD) — вставлять строго перед ней.
     * Если вкладки нет — {@code -1} (добавить в конец).
     */
    private static int resolveInsertIndex(DtGranularEditor<?> editor)
    {
        Object container = Global.invoke(editor, "getContainer"); //$NON-NLS-1$
        if (container instanceof CTabFolder folder)
        {
            for (CTabItem item : folder.getItems())
            {
                if (DATA_SCHEMA_TAB.equals(item.getText()))
                    return folder.indexOf(item);
            }
        }
        return -1;
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
     * при первой активации ({@link #setActive(boolean)}).
     */
    private static final class EventHandlersPage extends FormPage
    {
        private final MdObject mdObject;

        private final List<Object> producedTypes;

        private Composite host;

        private Object embeddedEditor;

        private boolean filled;

        /** Мост команд {@code com._1c.g5.v8.dt.eventhandlers.ui.*} к встроенному редактору. */
        private IExecutionListener commandBridge;

        /** Защита от рекурсии: перевыполненная команда снова попадает в preExecute. */
        private boolean bridging;

        /** Бандл/инжектор, сохранённые после {@link #fill()} для двойного клика. */
        private Bundle pageBundle;

        private Injector pageInjector;

        EventHandlersPage(MdObject mdObject, List<Object> producedTypes)
        {
            super(PAGE_ID, PAGE_TITLE);
            this.mdObject = mdObject;
            this.producedTypes = producedTypes;
        }

        @Override
        protected void createFormContent(IManagedForm managedForm)
        {
            Composite body = managedForm.getForm().getBody();
            body.setLayout(new FillLayout());
            host = new Composite(body, SWT.NONE);
            host.setLayout(new FillLayout());
        }

        @Override
        public void setActive(boolean active)
        {
            if (active && host != null && !host.isDisposed())
            {
                if (!filled)
                    fill();
                // Пользователь мог снять фильтр по объекту (случайно или штатным
                // «Удалить фильтр») — восстанавливаем его при каждой активации страницы,
                // но только если фильтр уже не равен целевому: иначе повторное применение
                // (clear + addAll + refresh + expandAll) сбросит состояния деревьев.
                else if (embeddedEditor != null && !isObjectFilterApplied(embeddedEditor))
                    configureObjectFilter(embeddedEditor);
            }
            super.setActive(active);
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
                Global.invoke(editor, "createPartControl", host); //$NON-NLS-1$
                configureObjectFilter(editor);

                embeddedEditor = editor;
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
            }
            catch (Exception | LinkageError e)
            {
                Global.logError(TAG, "fill page", e); //$NON-NLS-1$
                showError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
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

            boolean same = object == mdObject
                || (EcoreUtil.getURI(object) != null && EcoreUtil.getURI(object).equals(EcoreUtil.getURI(mdObject)));
            Global.tempLog(OPEN_LOG_TOPIC, "строка=" + element.getClass().getSimpleName() //$NON-NLS-1$
                + ", объект строки=" + EcoreUtil.getURI(object) //$NON-NLS-1$
                + ", объект страницы=" + EcoreUtil.getURI(mdObject) + ", свой=" + same); //$NON-NLS-1$ //$NON-NLS-2$
            return same;
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
            {
                Global.tempLog(OPEN_LOG_TOPIC, "двойной клик по строке своего объекта — не открываем"); //$NON-NLS-1$
                return;
            }

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
            if (sources == null
                || sources.size() != new HashSet<>(producedTypes).size()
                || !sources.containsAll(producedTypes))
                return false;

            @SuppressWarnings("unchecked")
            Collection<Object> events =
                (Collection<Object>)Global.invoke(filter, "getEvents"); //$NON-NLS-1$
            if (events != null && !events.isEmpty())
                return false;

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
            if (sources != null)
                sources.addAll(producedTypes);

            TreeViewer viewer = (TreeViewer)Global.invoke(mainSection, "getEventHandlersTreeViewer"); //$NON-NLS-1$
            if (viewer != null && !viewer.getControl().isDisposed())
            {
                // Идемпотентность: addFilter ничего не делает, если такой же фильтр уже добавлен
                // (JFace проверяет через equals), а без refresh пересборка не произойдёт.
                viewer.addFilter((ViewerFilter)filter);
                viewer.refresh();
            }

            Global.invoke(mainSection, "refreshExpandFilter"); //$NON-NLS-1$
            Global.invoke(mainSection, "enableExpandFilter"); //$NON-NLS-1$

            if (viewer != null && !viewer.getControl().isDisposed())
            {
                viewer.expandAll();
                viewer.getTree().setFocus();
            }
        }

        private void showError(String message)
        {
            if (host == null || host.isDisposed())
                return;
            Global.tempLog(TAG, "fill failed: " + message); //$NON-NLS-1$
            for (Control child : host.getChildren())
                child.dispose();
            Label label = new Label(host, SWT.WRAP);
            label.setText("Не удалось открыть подписки на события: " + message); //$NON-NLS-1$
            host.layout(true, true);
        }
    }
}
