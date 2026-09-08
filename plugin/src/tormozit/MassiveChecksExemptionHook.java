package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com.e1c.g5.v8.dt.check.ICheckScheduler;

/**
 * Исключения из режима «Отключить режим массовых проверок» (свойства проекта → «Настройки для
 * разработчиков проверок», ключ {@code disableMassiveChecks}): флажок не должен глушить проверки
 * <b>модулей, открытых в редакторе</b>, и <b>ручной запуск проверки</b>.
 *
 * <p><b>Что делает флажок штатно.</b> {@code CheckDerivedDataContributor$CheckDeactivationController
 * .requiresDeactivation} глушит расчёт проверок для событий служб {@code SYNCHRONIZATION_MANAGER}
 * (синхронизация файлов рабочей области — сюда попадает сохранение модуля),
 * {@code CHECK_SCHEDULER} (команда «Запустить проверку»), {@code COMPARISON_MANAGER} и
 * {@code REFACTORING_SERVICE}. Единственная лазейка —
 * {@code ICheckScheduler.isDeactivatedCheckPermitted(objectId, project)}: если объект разрешён,
 * расчёт идёт как обычно.
 *
 * <p><b>Почему штатной лазейки мало.</b> Разрешение <b>одноразовое</b>: {@code CheckScheduler}
 * хранит его в наборе и в {@code isDeactivatedCheckPermitted} делает {@code set.remove(id)}. У
 * модуля контекстов два ({@code L_CHECKS_SEGMENT} и {@code CL_CHECKS_SEGMENT}), то есть запросов
 * тоже два, и второй уже не проходит. Вдобавок формы идентификатора расходятся:
 * {@code BslXtextEditor.doSave} кладёт разрешение как {@code URI.toPlatformString(true)}
 * (декодированный путь), а контекст модуля заводится по {@code toPlatformString(false)}
 * (кодированный) — на кириллических путях это разные строки, и разрешение не находится.
 *
 * <p><b>Что делает плагин.</b> Значения приватного набора разрешений
 * ({@code CheckScheduler.permittedChecks}: проект → набор разрешённых идентификаторов) заменяются
 * обёрткой {@link ExemptingSet}. Она ведёт себя как исходный набор, но в {@code remove(id)}
 * дополнительно отвечает «разрешено» для:
 * <ul>
 * <li>пути модуля, который открыт в редакторе или менялся в последние
 * {@value #WINDOW_MS} мс (сохранение модуля из редактора объекта тоже сюда попадает — окно
 * заводит слушатель изменений рабочей области, а не редактор);</li>
 * <li>любого объекта проекта в окне после ручного запуска проверки — команды ЕДТ «Запустить
 * проверку» и нашей команды «Проверить» ({@link ComfortCheckRecompute}).</li>
 * </ul>
 * Ответ не расходуется: обёртка ничего не удаляет, поэтому оба сегмента проверок модуля проходят.
 *
 * <p>Идентификаторы сравниваются в обеих формах — кодированной и декодированной, — потому что
 * ЕДТ пользуется обеими.
 */
public class MassiveChecksExemptionHook
    implements IStartup
{
    /** Приватное поле {@code CheckScheduler}: проект → набор разрешённых идентификаторов. */
    private static final String PERMITTED_FIELD = "permittedChecks"; //$NON-NLS-1$

    /** Команда ЕДТ «Запустить проверку» (страница «Настройки для разработчиков проверок»). */
    private static final String START_CHECK_COMMAND = "com._1c.g5.v8.dt.ui.command.startCheck"; //$NON-NLS-1$

    private static final String BSL_EXTENSION = "bsl"; //$NON-NLS-1$

    /** Сколько модуль считается «свежим» после изменения, а проект — «в ручной проверке». */
    private static final long WINDOW_MS = 120_000L;

    /** Путь модуля (в обеих формах) → до какого момента он исключён из режима. */
    private static final Map<String, Long> touchedModules = new ConcurrentHashMap<>();

    /** Имя проекта → до какого момента разрешён любой его объект (ручной запуск). */
    private static final Map<String, Long> manualProjects = new ConcurrentHashMap<>();

    /** Пути модулей, открытых в редакторах; обновляется в потоке UI. */
    private static final Set<String> openModules = ConcurrentHashMap.newKeySet();

    @Override
    public void earlyStartup()
    {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(event -> onWorkspaceChange(event),
            IResourceChangeEvent.POST_CHANGE);

        // earlyStartup идёт не в потоке UI — к рабочему столу только через asyncExec.
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(MassiveChecksExemptionHook::installUiParts);

        ensurePatched();
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
        ensurePatched();
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
            refreshOpenModules();
            PlatformUI.getWorkbench().addWindowListener(new WindowTracker());
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                WindowTracker.track(window);
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
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference reference : page.getEditorReferences())
                {
                    IEditorInput input = editorInput(reference);
                    if (input instanceof IFileEditorInput fileInput)
                        addPathForms(paths, fileInput.getFile());
                }
            }
        }
        openModules.clear();
        openModules.addAll(paths);
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

    private static void onWorkspaceChange(IResourceChangeEvent event)
    {
        IResourceDelta delta = event != null ? event.getDelta() : null;
        if (delta == null)
            return;
        Set<String> touched = new HashSet<>();
        try
        {
            delta.accept(child ->
            {
                if (child.getResource() instanceof IFile file)
                    addPathForms(touched, file);
                return true;
            });
        }
        catch (Exception e)
        {
            Debug.log("обход изменений рабочей области: " + e); //$NON-NLS-1$
        }
        if (touched.isEmpty())
            return;

        long until = deadline();
        for (String path : touched)
            touchedModules.put(path, Long.valueOf(until));
        purgeExpired();
        ensurePatched();
        Debug.log("изменены модули: " + touched.size() / 2); //$NON-NLS-1$
    }

    /**
     * Заменяет наборы разрешений обёртками. Вызывается перед каждым моментом, когда исключение
     * может понадобиться (изменение модуля, ручной запуск): проекты открываются и закрываются, а
     * наборы {@code CheckScheduler} заводит для каждого проекта свои.
     */
    private static synchronized void ensurePatched()
    {
        try
        {
            Object scheduler = unwrapPeaberry(Global.getOsgiService(ICheckScheduler.class));
            if (scheduler == null)
                return;
            Object field = Global.getField(scheduler, PERMITTED_FIELD);
            if (!(field instanceof Map<?, ?> raw))
            {
                Debug.log("поле " + PERMITTED_FIELD + " не найдено"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> permitted = (Map<Object, Object>)raw;
            for (Map.Entry<Object, Object> entry : permitted.entrySet())
            {
                if (!(entry.getKey() instanceof IProject project))
                    continue;
                if (entry.getValue() instanceof ExemptingSet || !(entry.getValue() instanceof Set<?> set))
                    continue;
                @SuppressWarnings("unchecked")
                Set<Object> delegate = (Set<Object>)set;
                permitted.replace(entry.getKey(), set, new ExemptingSet(project, delegate));
                Debug.log("набор разрешений обёрнут для проекта " + project.getName()); //$NON-NLS-1$
            }
        }
        catch (Exception | LinkageError e)
        {
            Debug.log("подмена набора разрешений: " + e); //$NON-NLS-1$
        }
    }

    /** Настоящая служба вместо динамического прокси peaberry, если это он. */
    private static Object unwrapPeaberry(Object service)
    {
        if (service == null || !service.getClass().getName().endsWith("$pbryglu")) //$NON-NLS-1$
            return service;
        try
        {
            Field handle = service.getClass().getDeclaredField("__pbry__"); //$NON-NLS-1$
            handle.setAccessible(true);
            Object handleValue = handle.get(service);
            Method get = handleValue.getClass().getMethod("get"); //$NON-NLS-1$
            get.setAccessible(true);
            Object real = get.invoke(handleValue);
            return real != null ? real : service;
        }
        catch (ReflectiveOperationException e)
        {
            Debug.log("разворачивание прокси службы: " + e); //$NON-NLS-1$
            return service;
        }
    }

    /** Разрешён ли расчёт проверок этого объекта, несмотря на режим массовых проверок. */
    static boolean isExempt(IProject project, Object objectId)
    {
        long now = System.currentTimeMillis();
        if (project != null && isActive(manualProjects.get(project.getName()), now))
            return true;
        String path = objectId instanceof URI uri ? uri.toPlatformString(false)
            : objectId instanceof String text ? text : null;
        if (path == null)
            return false;
        return openModules.contains(path) || isActive(touchedModules.get(path), now);
    }

    private static boolean isActive(Long until, long now)
    {
        return until != null && until.longValue() > now;
    }

    private static long deadline()
    {
        return System.currentTimeMillis() + WINDOW_MS;
    }

    private static void purgeExpired()
    {
        long now = System.currentTimeMillis();
        purgeExpired(touchedModules, now);
        purgeExpired(manualProjects, now);
    }

    private static void purgeExpired(Map<String, Long> map, long now)
    {
        for (Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator(); it.hasNext();)
        {
            if (!isActive(it.next().getValue(), now))
                it.remove();
        }
    }

    /**
     * Набор разрешений с исключениями. Штатный {@code isDeactivatedCheckPermitted} спрашивает
     * набор через {@code remove(id)} — то есть разрешение расходуется; для исключений плагина
     * ответ постоянный, поэтому {@code remove} для них ничего не удаляет и всегда отвечает
     * «разрешено».
     */
    private static final class ExemptingSet
        implements Set<Object>
    {
        private final IProject project;

        private final Set<Object> delegate;

        ExemptingSet(IProject project, Set<Object> delegate)
        {
            this.project = project;
            this.delegate = delegate;
        }

        @Override
        public boolean remove(Object objectId)
        {
            if (delegate.remove(objectId))
                return true;
            boolean exempt = isExempt(project, objectId);
            if (exempt)
                Debug.log("разрешено вопреки режиму массовых проверок: " + objectId); //$NON-NLS-1$
            return exempt;
        }

        @Override
        public boolean add(Object objectId)
        {
            return delegate.add(objectId);
        }

        @Override
        public boolean addAll(Collection<?> collection)
        {
            return delegate.addAll(collection);
        }

        @Override
        public void clear()
        {
            delegate.clear();
        }

        @Override
        public boolean contains(Object objectId)
        {
            return delegate.contains(objectId) || isExempt(project, objectId);
        }

        @Override
        public boolean containsAll(Collection<?> collection)
        {
            return delegate.containsAll(collection);
        }

        @Override
        public boolean isEmpty()
        {
            return delegate.isEmpty();
        }

        @Override
        public Iterator<Object> iterator()
        {
            return delegate.iterator();
        }

        @Override
        public boolean removeAll(Collection<?> collection)
        {
            return delegate.removeAll(collection);
        }

        @Override
        public boolean retainAll(Collection<?> collection)
        {
            return delegate.retainAll(collection);
        }

        @Override
        public int size()
        {
            return delegate.size();
        }

        @Override
        public Object[] toArray()
        {
            return delegate.toArray();
        }

        @Override
        public <T> T[] toArray(T[] array)
        {
            return delegate.toArray(array);
        }
    }

    /** Ручной запуск штатной командой «Запустить проверку» — окно на весь проект. */
    private static final class StartCheckListener
        implements org.eclipse.core.commands.IExecutionListener
    {
        @Override
        public void preExecute(String commandId, org.eclipse.core.commands.ExecutionEvent event)
        {
            if (!START_CHECK_COMMAND.equals(commandId))
                return;
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (project.isAccessible())
                    openManualWindow(project);
            }
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
        private static final org.eclipse.ui.IPartListener2 PART_LISTENER = new org.eclipse.ui.IPartListener2()
        {
            @Override
            public void partOpened(org.eclipse.ui.IWorkbenchPartReference reference)
            {
                refreshOpenModules();
                ensurePatched();
            }

            @Override
            public void partClosed(org.eclipse.ui.IWorkbenchPartReference reference)
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
