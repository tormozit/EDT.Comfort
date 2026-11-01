package tormozit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;

import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Постоянный список контекстов проверок, отложенных шлюзом пересчёта раннего бандла, по проектам.
 * Переживает закрытие уведомления и перезапуск EDT. Контекст снимается со списка, когда шлюз его
 * пропустил ({@link #passed}): правка открытого объекта, ручной запуск, «Запустить» отложенного.
 *
 * <p>Элемент для пересчёта — {@code {идентификатор объекта, EClass, сегмент, класс контекста}}
 * ({@link ComfortCheckRecompute#recomputeContexts}); ключ — {@code «сегмент\nидентификатор»}, как в шлюзе.
 */
public final class DeferredCheckContexts
{
    private static final String FOLDER = "deferred-checks"; //$NON-NLS-1$

    private static final long SAVE_DELAY_MS = 1_000L;

    /** Имя проекта → ключ контекста → запись; загружается из файла при первом обращении. */
    private static final Map<String, Map<String, Entry>> projects = new ConcurrentHashMap<>();

    private static final Set<String> dirty = ConcurrentHashMap.newKeySet();

    private static final AtomicBoolean saveScheduled = new AtomicBoolean();

    private static final AtomicBoolean refreshScheduled = new AtomicBoolean();

    private static final Map<String, Class<?>> contextClasses = new ConcurrentHashMap<>();

    /** «проект\nключ» пересчитываемых сейчас: обновление списка открытых не запускает их повторно. */
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private DeferredCheckContexts()
    {
    }

    /**
     * @param objectId путь модуля ({@code toPlatformString(false)}) или BM-идентификатор
     * @param objectKey папка объекта или формы — по ней считаются объекты; {@code bm:id}, если файла нет
     */
    private record Entry(String segment, Object objectId, EClass eClass, String eClassName, String contextClass,
        String objectKey)
    {
        String key()
        {
            return segment + '\n' + objectId;
        }
    }

    /** Отложенные шлюзом контексты; ключи объектов читаются из модели — не в потоке UI. */
    public static void add(IProject project, List<?> contexts)
    {
        if (project == null || contexts == null || contexts.isEmpty())
            return;
        List<Object[]> entries = new ArrayList<>();
        List<Long> bmIds = new ArrayList<>();
        for (Object item : contexts)
        {
            if (item instanceof Object[] entry && entry.length == 4 && entry[0] != null && entry[2] != null)
            {
                entries.add(entry);
                if (entry[0] instanceof Long id)
                    bmIds.add(id);
            }
        }
        Map<Long, String> bmKeys = bmIds.isEmpty() ? Map.of() : ComfortCheckRecompute.bmObjectKeys(project, bmIds);
        Map<String, Entry> stored = load(project.getName());
        for (Object[] entry : entries)
        {
            String objectKey = entry[0] instanceof String path ? ComfortCheckRecompute.objectKey(path)
                : bmKeys.getOrDefault(entry[0], "bm:" + entry[0]); //$NON-NLS-1$
            EClass eClass = entry[1] instanceof EClass type ? type : null;
            if (entry[3] instanceof Class<?> type)
                contextClasses.putIfAbsent(type.getName(), type);
            Entry value = new Entry(String.valueOf(entry[2]), entry[0], eClass, eClassName(eClass),
                entry[3] instanceof Class<?> type ? type.getName() : String.valueOf(entry[3]), objectKey);
            stored.put(value.key(), value);
        }
        changed(project.getName());
    }

    /** Шлюз пропустил контекст; вызывается для каждого контекста — без чтения модели. */
    public static void passed(Object project, Object key)
    {
        if (!(project instanceof IProject workspaceProject) || key == null)
            return;
        Map<String, Entry> stored = load(workspaceProject.getName());
        if (!stored.isEmpty() && stored.remove(String.valueOf(key)) != null)
            changed(workspaceProject.getName());
    }

    /** Сколько объектов отложено во всех проектах. */
    public static int objectCount()
    {
        int count = 0;
        for (String name : projects.keySet())
            count += objectCount(ResourcesPlugin.getWorkspace().getRoot().getProject(name));
        return count;
    }

    /** Есть ли отложенное хоть в одном проекте — без подсчёта объектов. */
    public static boolean hasAny()
    {
        for (var stored : projects.values())
            if (!stored.isEmpty())
                return true;
        return false;
    }

    /** Пересчитать отложенное всех проектов, где оно есть. */
    public static void recomputeAll()
    {
        for (var stored : projects.entrySet())
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(stored.getKey());
            if (!stored.getValue().isEmpty() && project.isAccessible())
                recompute(project, List.copyOf(stored.getValue().values()), false);
        }
    }

    /** Сколько объектов отложено: объект с его модулями — один, форма — отдельный. */
    public static int objectCount(IProject project)
    {
        if (project == null)
            return 0;
        Set<String> objects = new HashSet<>();
        for (Entry entry : load(project.getName()).values())
            objects.add(entry.objectKey());
        return objects.size();
    }

    /** Пересчитать все отложенные контексты проекта с итоговым уведомлением. */
    public static void recompute(IProject project)
    {
        if (project == null)
            return;
        List<Entry> entries = List.copyOf(load(project.getName()).values());
        if (entries.isEmpty())
        {
            ComfortCheckRecompute.toast("Проверить", "Отложенного пересчета проверок в проекте " //$NON-NLS-1$
                + project.getName() + " нет."); //$NON-NLS-1$
            return;
        }
        recompute(project, entries, false);
    }

    /**
     * Отложенные объекты, которые сейчас открыты в редакторах, пересчитываются сразу и молча: шлюз
     * пропускает их без ручного запуска. Вызывать после обновления списка открытых.
     */
    public static void recomputeOpened()
    {
        for (var stored : projects.entrySet())
        {
            if (stored.getValue().isEmpty())
                continue;
            List<Entry> opened = new ArrayList<>();
            for (Entry entry : stored.getValue().values())
                if (!inFlight.contains(stored.getKey() + '\n' + entry.key())
                    && MassiveChecksExemptionHook.isOpenObject(entry.objectId()))
                    opened.add(entry);
            IProject project = opened.isEmpty() ? null
                : ResourcesPlugin.getWorkspace().getRoot().getProject(stored.getKey());
            if (project == null || !project.isAccessible())
                continue;
            for (Entry entry : opened)
                inFlight.add(stored.getKey() + '\n' + entry.key());
            // Разметка контекстов ждёт блокировку производных данных — не в потоке UI
            Job job = Job.create("Комфорт: пересчет открытых отложенных объектов", monitor -> //$NON-NLS-1$
            {
                recompute(project, opened, true);
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        }
    }

    /** Подгрузить сохранённые списки открытых проектов, чтобы {@link #recomputeOpened} их видел. */
    public static void loadAll()
    {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isAccessible())
                load(project.getName());
        refreshCommand();
    }

    /** Подпись «Отложенные (N)» кнопки панели «Проблемы» — после изменения списка. */
    private static void refreshCommand()
    {
        if (!refreshScheduled.compareAndSet(false, true))
            return;
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
        if (display == null || display.isDisposed())
        {
            refreshScheduled.set(false);
            return;
        }
        display.asyncExec(() -> display.timerExec(500, () ->
        {
            refreshScheduled.set(false);
            if (!org.eclipse.ui.PlatformUI.isWorkbenchRunning())
                return;
            var commands = org.eclipse.ui.PlatformUI.getWorkbench().getService(org.eclipse.ui.commands.ICommandService.class);
            if (commands != null)
                commands.refreshElements(ProblemViewRecomputeDeferredHandler.COMMAND_ID, null);
            // refreshElements обновляет только подпись; доступность кнопки E4 перечитывает по этому событию
            var events = org.eclipse.ui.PlatformUI.getWorkbench().getService(org.eclipse.e4.core.services.events.IEventBroker.class);
            if (events != null)
                events.send(org.eclipse.e4.ui.workbench.UIEvents.REQUEST_ENABLEMENT_UPDATE_TOPIC,
                    org.eclipse.e4.ui.workbench.UIEvents.ALL_ELEMENT_ID);
        }));
    }

    private static void recompute(IProject project, List<Entry> entries, boolean quiet)
    {
        List<Object[]> contexts = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Entry entry : entries)
        {
            Object[] context = toContext(entry);
            keys.add(entry.key());
            inFlight.add(project.getName() + '\n' + entry.key());
            if (context != null)
                contexts.add(context);
        }
        // Обработанные пересчётом и не восстановленные (тип или класс исчезли) снимаются со списка;
        // не состоявшийся пересчёт оставляет их отложенными
        java.util.function.Consumer<Boolean> settled = processed ->
        {
            Map<String, Entry> stored = load(project.getName());
            boolean removed = false;
            for (String key : keys)
            {
                if (processed.booleanValue())
                    removed |= stored.remove(key) != null;
                inFlight.remove(project.getName() + '\n' + key);
            }
            if (removed)
                changed(project.getName());
        };
        if (contexts.isEmpty())
            settled.accept(Boolean.TRUE);
        else
            ComfortCheckRecompute.recomputeContexts(project, contexts, quiet, settled);
    }

    private static Object[] toContext(Entry entry)
    {
        EClass eClass = entry.eClass() != null ? entry.eClass() : eClass(entry.eClassName());
        Class<?> contextClass = contextClass(entry.contextClass());
        if (eClass == null || contextClass == null)
            return null;
        return new Object[] {entry.objectId(), eClass, entry.segment(), contextClass};
    }

    private static String eClassName(EClass eClass)
    {
        return eClass != null && eClass.getEPackage() != null
            ? eClass.getEPackage().getNsURI() + '#' + eClass.getName() : ""; //$NON-NLS-1$
    }

    private static EClass eClass(String name)
    {
        int hash = name.lastIndexOf('#');
        EPackage ePackage = hash > 0 ? EPackage.Registry.INSTANCE.getEPackage(name.substring(0, hash)) : null;
        EClassifier classifier = ePackage != null ? ePackage.getEClassifier(name.substring(hash + 1)) : null;
        return classifier instanceof EClass type ? type : null;
    }

    /** Классы контекстов — в непубличном пакете бандла проверок. */
    private static Class<?> contextClass(String name)
    {
        Class<?> cached = contextClasses.get(name);
        if (cached != null)
            return cached;
        try
        {
            cached = ICheckRepository.class.getClassLoader().loadClass(name);
            contextClasses.put(name, cached);
            return cached;
        }
        catch (ClassNotFoundException | LinkageError e)
        {
            return null;
        }
    }

    private static Map<String, Entry> load(String projectName)
    {
        Map<String, Entry> stored = projects.get(projectName);
        if (stored != null)
            return stored;
        synchronized (projects)
        {
            stored = projects.get(projectName);
            if (stored != null)
                return stored;
            stored = new ConcurrentHashMap<>();
            Path file = file(projectName);
            try
            {
                if (file != null && Files.isRegularFile(file))
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
                    {
                        Entry entry = parse(line);
                        if (entry != null)
                            stored.put(entry.key(), entry);
                    }
            }
            catch (IOException | RuntimeException e)
            {
                Debug.log("чтение " + file + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            }
            projects.put(projectName, stored);
            return stored;
        }
    }

    /** Строка: сегмент, «L» и число либо «S» и путь, EClass ({@code nsURI#имя}), класс контекста, ключ объекта. */
    private static Entry parse(String line)
    {
        String[] parts = line.split("\t", -1); //$NON-NLS-1$
        if (parts.length != 5 || parts[1].length() < 2)
            return null;
        Object objectId = parts[1].charAt(0) == 'L' ? Long.valueOf(parts[1].substring(1)) : parts[1].substring(1);
        return new Entry(parts[0], objectId, null, parts[2], parts[3], parts[4]);
    }

    private static String format(Entry entry)
    {
        return entry.segment() + '\t' + (entry.objectId() instanceof Long ? "L" : "S") + entry.objectId() //$NON-NLS-1$ //$NON-NLS-2$
            + '\t' + entry.eClassName() + '\t' + entry.contextClass() + '\t' + entry.objectKey();
    }

    private static Path file(String projectName)
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
            return null;
        String name = java.net.URLEncoder.encode(projectName, StandardCharsets.UTF_8);
        return activator.getStateLocation().append(FOLDER).append(name + ".tsv").toFile().toPath(); //$NON-NLS-1$
    }

    private static void changed(String projectName)
    {
        dirty.add(projectName);
        refreshCommand();
        if (!saveScheduled.compareAndSet(false, true))
            return;
        Job job = Job.create("Комфорт: сохранение отложенных проверок", monitor -> //$NON-NLS-1$
        {
            saveScheduled.set(false);
            for (String name : List.copyOf(dirty))
            {
                dirty.remove(name);
                save(name);
            }
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule(SAVE_DELAY_MS);
    }

    private static void save(String projectName)
    {
        Path file = file(projectName);
        Map<String, Entry> stored = projects.get(projectName);
        if (file == null || stored == null)
            return;
        try
        {
            if (stored.isEmpty())
            {
                Files.deleteIfExists(file);
                return;
            }
            List<String> lines = new ArrayList<>();
            for (Entry entry : stored.values())
                lines.add(format(entry));
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException e)
        {
            Debug.log("запись " + file + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static final class Debug
    {
        private static final String TAG = "DeferredCheckContexts"; //$NON-NLS-1$

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
