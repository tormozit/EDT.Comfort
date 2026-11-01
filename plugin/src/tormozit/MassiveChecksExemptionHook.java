package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.ui.editor.input.IDtEditorInput;

/**
 * Что пересчитывать проверками без вопроса: <b>модули и объекты, открытые в редакторах</b>, и
 * <b>ручной запуск проверки</b> проекта. Решение принимает шлюз пересчёта в раннем бандле
 * ({@code CheckDeactivationController.requiresDeactivation}); он спрашивает этот класс через
 * {@link #isOpenObject} и {@link #isManual}. Шлюз перекрывает и штатный режим «Отключить массовые
 * проверки» (ключ {@code disableMassiveChecks}). Отложенный шлюзом объект при открытии в редакторе
 * пересчитывается сразу ({@link DeferredCheckContexts#recomputeOpened}).
 *
 * <p>Окно ручного запуска открывают команда ЕДТ «Запустить проверку» и наша «Проверить»
 * ({@link ComfortCheckRecompute}); оно держится до конца расчёта. Параметр «Только открытые модули»
 * тяжёлых проверок смотрит только на открытость ({@link #isOpenOrManual}), ручной запуск его не отменяет;
 * исключение — «Запустить проверки» на странице проверок ({@link #openAllModulesWindow}).
 *
 * <p>Пути модулей хранятся в обеих формах — кодированной и декодированной, — потому что ЕДТ
 * пользуется обеими.
 */
public class MassiveChecksExemptionHook
    implements IStartup
{
    /** Команда ЕДТ «Запустить проверку» (страница «Общие настройки проверок»). */
    private static final String START_CHECK_COMMAND = "com._1c.g5.v8.dt.ui.command.startCheck"; //$NON-NLS-1$

    private static final String BSL_EXTENSION = "bsl"; //$NON-NLS-1$

    /** Сколько проект считается «в ручной проверке» после последнего продления окна. */
    private static final long WINDOW_MS = 120_000L;

    /** Имя проекта → до какого момента разрешён любой его объект (ручной запуск). */
    private static final Map<String, Long> manualProjects = new ConcurrentHashMap<>();

    /** Имя проекта → до какого момента флажок «Только открытые модули» не действует («Запустить проверки»). */
    private static final Map<String, Long> allModulesProjects = new ConcurrentHashMap<>();

    /** Пути модулей, открытых в редакторах; обновляется в потоке UI. */
    private static final Set<String> openModules = ConcurrentHashMap.newKeySet();

    /**
     * Папки объектов, открытых в редакторе объекта (общий модуль, справочник и т. п.), с завершающим «/»:
     * его модули, модули форм и команд открыты вместе с ним. Обновляется в потоке UI.
     */
    private static final Set<String> openObjectFolders = ConcurrentHashMap.newKeySet();

    /** BM-идентификаторы объектов редакторов объектов и их верхних объектов; обновляется в потоке UI. */
    private static final Set<Long> openObjectIds = ConcurrentHashMap.newKeySet();

    @Override
    public void earlyStartup()
    {
        // earlyStartup идёт не в потоке UI — к рабочему столу только через asyncExec.
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(MassiveChecksExemptionHook::installUiParts);
    }

    /**
     * Разрешает проверки любого объекта проекта на ближайшее окно — для ручного запуска, когда
     * пользователь сам попросил проверить и ждать «до следующей правки текста» бессмысленно.
     *
     * @param project проект ручного запуска; {@code null} игнорируется
     */
    public static void openManualWindow(IProject project)
    {
        if (project == null)
            return;
        manualProjects.put(project.getName(), Long.valueOf(deadline()));
        Debug.log("ручной запуск: окно для проекта " + project.getName()); //$NON-NLS-1$
    }

    /** Слушатели рабочего стола: список открытых модулей и перехват команды «Запустить проверку». */
    private static void installUiParts()
    {
        try
        {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService != null)
                commandService.addExecutionListener(new StartCheckListener());
            PlatformUI.getWorkbench().addWindowListener(new WindowTracker());
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                WindowTracker.track(window);
            DeferredCheckContexts.loadAll();
            refreshOpenModules();
        }
        catch (Exception | LinkageError e)
        {
            Debug.log("установка слушателей рабочего стола: " + e); //$NON-NLS-1$
        }
    }

    /** Пути всех открытых редакторов модулей; вызывать только в потоке UI. */
    private static void refreshOpenModules()
    {
        Set<String> paths = new HashSet<>();
        Set<String> folders = new HashSet<>();
        Set<Long> objectIds = new HashSet<>();
        Set<IEditorReference> references = new HashSet<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference reference : page.getEditorReferences())
                {
                    references.add(reference);
                    WindowTracker.trackEditor(reference);
                    IEditorInput input = editorInput(reference);
                    if (input instanceof IFileEditorInput fileInput)
                        addPathForms(paths, fileInput.getFile());
                    else if (input instanceof IDtEditorInput<?> dtInput)
                    {
                        addObjectFolderForms(folders, dtInput.getModel());
                        addObjectIds(objectIds, dtInput.getModel());
                    }
                }
            }
        }
        openModules.clear();
        openModules.addAll(paths);
        openObjectFolders.clear();
        openObjectFolders.addAll(folders);
        openObjectIds.clear();
        openObjectIds.addAll(objectIds);
        WindowTracker.untrackClosedEditors(references);
        // Отложенный шлюзом объект открыли — пересчитать его сразу
        DeferredCheckContexts.recomputeOpened();
    }

    private static IEditorInput editorInput(IEditorReference reference)
    {
        try
        {
            // restore=false: закрытые (неподнятые) редакторы не поднимаем ради их входа.
            return reference.getEditorInput();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Обе формы пути модуля — кодированная и декодированная: ЕДТ пользуется обеими. */
    private static void addPathForms(Collection<String> target, IFile file)
    {
        if (file == null || !BSL_EXTENSION.equalsIgnoreCase(file.getFileExtension()))
            return;
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        target.add(uri.toPlatformString(false));
        target.add(uri.toPlatformString(true));
    }

    /** Папка объекта редактора объекта в обеих формах, с «/» на конце (как {@code ComfortCheckRecompute}). */
    private static void addObjectFolderForms(Collection<String> target, EObject model)
    {
        IResourceLookup lookup = model != null ? Global.getOsgiService(IResourceLookup.class) : null;
        if (lookup == null)
            return;
        try
        {
            IFile objectFile = lookup.getPlatformResource(model);
            IContainer folder = objectFile != null ? objectFile.getParent() : null;
            if (folder == null)
                return;
            URI uri = URI.createPlatformResourceURI(folder.getFullPath().toString(), true);
            target.add(uri.toPlatformString(false) + "/"); //$NON-NLS-1$
            target.add(uri.toPlatformString(true) + "/"); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Debug.log("папка объекта редактора: " + e); //$NON-NLS-1$
        }
    }

    /** Идентификатор объекта редактора и его верхнего объекта: модельные проверки заводятся по BM-идентификатору. */
    private static void addObjectIds(Collection<Long> target, EObject model)
    {
        if (!(model instanceof IBmObject bmObject))
            return;
        try
        {
            target.add(Long.valueOf(bmObject.bmGetId()));
            IBmObject top = bmObject.bmIsTop() ? null : bmObject.bmGetTopObject();
            if (top != null)
                target.add(Long.valueOf(top.bmGetId()));
        }
        catch (RuntimeException e)
        {
            Debug.log("идентификатор объекта редактора: " + e); //$NON-NLS-1$
        }
    }

    /** Модуль лежит в папке объекта, открытого в редакторе объекта. */
    private static boolean inOpenObject(String path)
    {
        for (String folder : openObjectFolders)
            if (path.startsWith(folder))
                return true;
        return false;
    }

    /**
     * Модуль открыт либо идёт «Запустить проверки» его проекта — для параметра «Только открытые
     * модули» тяжёлых проверок. Прочий ручной запуск проверки флажок не отменяет: пользователь сам
     * выбрал объём. Недавнее изменение файла не учитывается: после получения изменений из Git
     * изменёнными оказываются сотни модулей.
     *
     * @param uri URI ресурса модуля ({@code platform:/resource/Проект/...})
     */
    public static boolean isOpenOrManual(URI uri)
    {
        if (uri != null && uri.isPlatformResource() && uri.segmentCount() > 1
            && isActive(allModulesProjects.get(URI.decode(uri.segment(1))), System.currentTimeMillis()))
            return true;
        return isModuleOpen(uri);
    }

    /**
     * Флажок «Только открытые модули» не действует для проекта на ближайшее окно — команда
     * «Запустить проверки» страницы проверок; продлевать до конца расчёта.
     */
    public static void openAllModulesWindow(IProject project)
    {
        if (project != null)
            allModulesProjects.put(project.getName(), Long.valueOf(deadline()));
    }

    /** Конец «Запустить проверки»: флажок «Только открытые модули» снова действует. */
    public static void closeAllModulesWindow(IProject project)
    {
        if (project != null)
            allModulesProjects.remove(project.getName());
    }

    /** Идёт ручной запуск проверки проекта — для шлюза пересчёта в раннем бандле. */
    public static boolean isManual(Object project)
    {
        return project instanceof IProject workspaceProject
            && isActive(manualProjects.get(workspaceProject.getName()), System.currentTimeMillis());
    }

    /**
     * Объект контекста проверок открыт в редакторе — для шлюза пересчёта в раннем бандле. Путь модуля
     * ({@code toPlatformString(false)}) или BM-идентификатор; недавняя правка не учитывается: после
     * получения изменений из Git изменёнными оказываются сотни модулей.
     */
    public static boolean isOpenObject(Object objectId)
    {
        if (objectId instanceof Long id)
            return openObjectIds.contains(id);
        return objectId instanceof String path && (openModules.contains(path) || inOpenObject(path));
    }

    /** Открыт модуль или его объект метаданных; ручной запуск и недавняя правка не учитываются. */
    public static boolean isModuleOpen(URI uri)
    {
        if (uri == null || !uri.isPlatformResource())
            return false;
        String raw = uri.toPlatformString(false);
        String decoded = uri.toPlatformString(true);
        return openModules.contains(raw) || openModules.contains(decoded) || inOpenObject(raw)
            || inOpenObject(decoded);
    }

    private static boolean isActive(Long until, long now)
    {
        return until != null && until.longValue() > now;
    }

    private static long deadline()
    {
        return System.currentTimeMillis() + WINDOW_MS;
    }

    /** Ручной запуск штатной командой «Запустить проверку» — окно на весь проект. */
    private static final class StartCheckListener
        implements org.eclipse.core.commands.IExecutionListener
    {
        /** Как часто продлевать окно, пока идёт расчёт. */
        private static final long HOLD_POLL_MS = 1_000L;

        /** Сколько менеджер должен простаивать подряд: проверка идёт волнами с паузами между ними. */
        private static final long HOLD_SETTLE_MS = 3_000L;

        /** Предел удержания окна: полная проверка большой конфигурации идёт десятки минут. */
        private static final long HOLD_LIMIT_MS = 60 * 60_000L;

        @Override
        public void preExecute(String commandId, org.eclipse.core.commands.ExecutionEvent event)
        {
            if (!START_CHECK_COMMAND.equals(commandId))
                return;
            List<IProject> projects = new ArrayList<>();
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (project.isAccessible())
                {
                    openManualWindow(project);
                    projects.add(project);
                }
            }
            holdManualWindow(projects);
        }

        /**
         * Продлевает окно ручного запуска до конца расчёта. Команда только планирует проверку, а
         * расчёт идёт дольше {@value #WINDOW_MS} мс; без продления остаток прогона откладывал бы
         * шлюз пересчёта.
         */
        private static void holdManualWindow(List<IProject> projects)
        {
            IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
            if (projects.isEmpty() || provider == null)
                return;
            Job job = Job.create("Комфорт: окно ручной проверки", monitor -> //$NON-NLS-1$
            {
                long start = System.currentTimeMillis();
                long idleSince = -1;
                boolean busy = false;
                try
                {
                    while (!monitor.isCanceled() && System.currentTimeMillis() - start < HOLD_LIMIT_MS)
                    {
                        long now = System.currentTimeMillis();
                        boolean idle = true;
                        for (IProject project : projects)
                        {
                            if (!project.isAccessible())
                                continue;
                            manualProjects.put(project.getName(), Long.valueOf(deadline()));
                            IDerivedDataManager manager = provider.get(project);
                            if (manager != null && !manager.isIdle())
                                idle = false;
                        }
                        if (!idle)
                        {
                            busy = true;
                            idleSince = -1;
                        }
                        else if (idleSince < 0)
                            idleSince = now;
                        // До начала расчёта менеджер тоже простаивает — без расчёта держим только первое окно
                        else if (busy ? now - idleSince >= HOLD_SETTLE_MS : now - start >= WINDOW_MS)
                            break;
                        Thread.sleep(HOLD_POLL_MS);
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                Debug.log("ручной запуск: окно удерживалось " //$NON-NLS-1$
                    + (System.currentTimeMillis() - start) / 1000 + " с"); //$NON-NLS-1$
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        }

        @Override
        public void notHandled(String commandId, org.eclipse.core.commands.NotHandledException exception)
        {
        }

        @Override
        public void postExecuteFailure(String commandId, org.eclipse.core.commands.ExecutionException exception)
        {
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
        }
    }

    /** Открытие и закрытие редакторов: поддерживает список открытых модулей. */
    private static final class WindowTracker
        implements org.eclipse.ui.IWindowListener
    {
        private static final Set<IEditorReference> trackedEditors = new HashSet<>();
        private static final AtomicBoolean refreshPending = new AtomicBoolean();

        // При восстановлении EDT сначала отдаёт DtEditorProxyInput, а настоящий вход
        // подставляет после загрузки проекта. makeInitialized обновляет имя и подсказку
        // вкладки; повторного partOpened при этом нет.
        private static final IPropertyListener EDITOR_PROPERTY_LISTENER = (source, property) ->
        {
            if (property != IWorkbenchPartConstants.PROP_INPUT
                && property != IWorkbenchPartConstants.PROP_PART_NAME
                && property != IWorkbenchPartConstants.PROP_TITLE)
                return;
            if (!refreshPending.compareAndSet(false, true))
                return;
            Display.getDefault().asyncExec(() ->
            {
                refreshPending.set(false);
                refreshOpenModules();
            });
        };

        static void trackEditor(IEditorReference reference)
        {
            if (trackedEditors.add(reference))
                reference.addPropertyListener(EDITOR_PROPERTY_LISTENER);
        }

        static void untrackClosedEditors(Set<IEditorReference> references)
        {
            for (Iterator<IEditorReference> it = trackedEditors.iterator(); it.hasNext();)
            {
                IEditorReference reference = it.next();
                if (!references.contains(reference))
                {
                    reference.removePropertyListener(EDITOR_PROPERTY_LISTENER);
                    it.remove();
                }
            }
        }

        private static final org.eclipse.ui.IPartListener2 PART_LISTENER = new org.eclipse.ui.IPartListener2()
        {
            @Override
            public void partOpened(org.eclipse.ui.IWorkbenchPartReference reference)
            {
                refreshOpenModules();
            }

            @Override
            public void partClosed(org.eclipse.ui.IWorkbenchPartReference reference)
            {
                refreshOpenModules();
            }

            @Override
            public void partInputChanged(org.eclipse.ui.IWorkbenchPartReference reference)
            {
                refreshOpenModules();
            }
        };

        static void track(IWorkbenchWindow window)
        {
            if (window == null)
                return;
            window.getPartService().addPartListener(PART_LISTENER);
        }

        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            track(window);
            refreshOpenModules();
        }

        @Override
        public void windowClosed(IWorkbenchWindow window)
        {
            refreshOpenModules();
        }

        @Override
        public void windowActivated(IWorkbenchWindow window)
        {
        }

        @Override
        public void windowDeactivated(IWorkbenchWindow window)
        {
        }
    }

    private static final class Debug
    {
        private static final String TAG = "MassiveChecksExemptionHook"; //$NON-NLS-1$

        private Debug()
        {
        }

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
