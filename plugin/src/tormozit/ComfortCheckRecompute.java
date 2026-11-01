package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.IDerivedDataUpdate;
import com._1c.g5.v8.derived.context.IContextCollectingSession;
import com._1c.g5.v8.derived.context.IObjectDerivedDataContext;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.validation.ISubordinateObjectsProvider;
import com._1c.g5.v8.dt.validation.marker.IMarkerInfo;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerIndex;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;
import com.e1c.g5.v8.dt.check.ICheck;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Точечный перезапуск проверок конфигурации для конкретных объектов, без пересчёта всего проекта.
 * <p>
 * {@code ICheckScheduler.scheduleValidation} для этой задачи не работает (проверено), а
 * {@code IDerivedDataManager.recomputeAll()} пересчитывает проект целиком — десятки секунд на
 * средней конфигурации. Рабочий путь — пометить объекту контекст производных данных сегмента
 * проверок как «полная перепроверка» ({@code setFullRebuild} + {@code setInactive(false)} +
 * {@code addCheckIds}) и продавить отложенные обновления через {@code applyForcedUpdates}.
 * <p>
 * Штатный {@code CheckContextCollectingSession#addFullCheck} этого недостаточно: он не снимает
 * флаг {@code inactive} у контекста, и помеченные проверки не запускаются. Эталон — путь
 * «Запустить автопроверки» в меню навигатора.
 * <p>
 * Для объекта МД дополнительно помечаются вложенные {@link BasicForm} (у форм свой BM).
 * {@code BasicForm.getForm()} вызывается только внутри {@code updateDerivedData}.
 * <p>
 * Модельных сегментов мало: проверки текста модулей живут в языковых сегментах
 * {@code L_CHECKS_SEGMENT} / {@code CL_CHECKS_SEGMENT}, и контекст там заводится не на BM-объект,
 * а на путь файла {@code *.bsl}. Пока такие контексты не помечались, запуск проверки по модулю не
 * делал ничего: проблемы модуля обновлялись только при изменении его текста (единственный путь,
 * который эти контексты собирает, — синхронизация файлов рабочей области). Поэтому вместе с
 * объектом помечаются все {@code *.bsl} его папки — модуль объекта, модуль менеджера, модули форм
 * и команд, — см. {@link #markLanguageFullRebuild}.
 * <p>
 * Ручной запуск не должен глушиться режимом «Отключить массовые проверки», поэтому перед
 * пометкой открывается окно исключения ({@link MassiveChecksExemptionHook#openManualWindow}).
 */
public final class ComfortCheckRecompute
{
    /** Сегмент обычных (NORMAL) модельных проверок. */
    private static final String M_CHECKS_SEGMENT = "M_CHECKS_SEGMENT"; //$NON-NLS-1$

    /** Сегмент сложных (COMPLEX) модельных проверок. */
    private static final String CM_CHECKS_SEGMENT = "CM_CHECKS_SEGMENT"; //$NON-NLS-1$

    /** Сегмент обычных (NORMAL) языковых проверок — проверки текста модулей. */
    private static final String L_CHECKS_SEGMENT = "L_CHECKS_SEGMENT"; //$NON-NLS-1$

    /** Сегмент сложных (COMPLEX) языковых проверок. */
    private static final String CL_CHECKS_SEGMENT = "CL_CHECKS_SEGMENT"; //$NON-NLS-1$

    /** Контекст языковых проверок; пакет internal бандла проверок не экспортируется. */
    private static final String LANGUAGE_CONTEXT_CLASS =
        "com.e1c.g5.v8.dt.internal.check.context.LanguageCheckObjectContext"; //$NON-NLS-1$

    private static final String BSL_EXTENSION = "bsl"; //$NON-NLS-1$

    private static volatile Class<?> languageContextClass;

    /** Сколько ждать завершения перепроверки в фоне, прежде чем сообщить о таймауте. */
    private static final long WAIT_COMPLETION_TIMEOUT_MS = 60_000L;

    /** Предел ожидания полной проверки проекта: на большой конфигурации она идёт десятки минут. */
    private static final long PROJECT_WAIT_TIMEOUT_MS = 60 * 60_000L;

    /** Пауза после затишья: убеждаемся, что следующей волны проверок не будет. */
    private static final long SETTLE_PAUSE_MS = 3_000L;

    private ComfortCheckRecompute() {}

    /** Значок «Запустить проверки» — тот же, что у штатной команды запуска проверок EDT. */
    public static org.eclipse.swt.graphics.Image icon()
    {
        String key = "tormozit.checks.recompute"; //$NON-NLS-1$
        var registry = Activator.getDefault().getImageRegistry();
        org.eclipse.swt.graphics.Image image = registry.get(key);
        if (image == null)
        {
            var descriptor = org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(
                "com._1c.g5.v8.dt.ui", "icons/obj16/check.png"); //$NON-NLS-1$ //$NON-NLS-2$
            if (descriptor != null)
            {
                registry.put(key, descriptor);
                image = registry.get(key);
            }
        }
        return image;
    }

    /**
     * Полная проверка всех объектов проекта — то, что должно происходить на корневом узле.
     * <p>
     * Точечная разметка контекстов здесь не годится: перепроверять пришлось бы каждый объект
     * конфигурации, а разметка одной только конфигурации отрабатывает мгновенно и не проверяет
     * ничего. Поэтому зовём штатный планировщик так же, как это делает команда ЕДТ «Запустить
     * проверку»: {@code ICheckScheduler.scheduleValidation} с пустыми наборами проверок и
     * объектов — при пустом наборе объектов он уходит в {@code scheduleScopedFullRebuild},
     * то есть полную перепроверку проекта.
     *
     * @param project проект; {@code null} — ничего не делать
     */
    public static void recomputeProject(IProject project)
    {
        recomputeProject(project, Set.of());
    }

    /** Перепроверка проекта указанными кодами; пустой набор означает все проверки. */
    public static void recomputeProject(IProject project, Set<String> requestedChecks)
    {
        Set<String> checkIds = new LinkedHashSet<>(requestedChecks);
        Debug.log("проверка проекта: вход, проект=" //$NON-NLS-1$
            + (project == null ? "null" : project.getName())); //$NON-NLS-1$
        if (project == null || !project.isAccessible())
        {
            toast("Запустить проверки", "Проект недоступен."); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        ICheckScheduler scheduler = Global.getOsgiService(ICheckScheduler.class);
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null ? provider.get(project) : null;
        if (scheduler == null || manager == null)
        {
            toast("Запустить проверки", "Планировщик проверок недоступен для проекта " + project.getName() + "."); //$NON-NLS-1$
            return;
        }

        if (!checkIds.isEmpty())
        {
            ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
            if (repository == null)
            {
                toast("Запустить проверки", "Каталог проверок недоступен.");
                return;
            }
            // Планировщик не выполняет выключенные проверки. Не превращаем пустой итог в «все».
            checkIds.removeIf(id -> repository.toUid(id, project).stream().noneMatch(uid ->
            {
                var settings = repository.getSettings(uid, project);
                return settings != null && settings.isEnabled();
            }));
            if (checkIds.isEmpty())
            {
                toast("Запустить проверки", "Нет включённых обновлённых проверок. Их можно включить в списке «Проверки».");
                return;
            }
        }
        Map<String, String> resultTokens = deferredResultTokens(project, checkIds);

        // Режим «Отключить массовые проверки» не должен глушить то, что запросил пользователь
        MassiveChecksExemptionHook.openManualWindow(project);
        long startMs = System.currentTimeMillis();
        Map<Object, Long> durationsBefore = CheckDurations.snapshot();
        toast("Запустить проверки", "Запущен пересчет " + checkCount(checkIds) + " проверок проекта " //$NON-NLS-1$ //$NON-NLS-2$
            + project.getName() + ". По окончании будет показано уведомление.");

        IDtProjectManager projectManager = Global.getOsgiService(IDtProjectManager.class);
        // Менеджер хранит активные контексты; внешние библиотеки в число проектов РМ не входят.
        boolean severalProjects = projectManager != null && projectManager.getDtProjects().stream()
            .filter(candidate -> candidate.getWorkspaceProject() != null).limit(2).count() > 1;
        String jobTitle = "Комфорт: проверка проекта" //$NON-NLS-1$
            + (severalProjects ? " (" + project.getName() + ")" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Job job = Job.create(jobTitle, monitor ->
        {
            try
            {
                Debug.log("проверка проекта: планировщик вызван"); //$NON-NLS-1$
                scheduler.scheduleValidation(project, checkIds, List.of(), monitor);
                Debug.log("проверка проекта: планировщик отработал, ждём завершения"); //$NON-NLS-1$
                // Ход расчёта показывает штатный индикатор EDT; ожидание конца — ради уведомления, в «Состоянии» его не видно
                Job wait = Job.create(jobTitle + ": ожидание завершения", waitMonitor -> //$NON-NLS-1$
                {
                    if (notifyProjectComplete(manager, project, checkIds, startMs, durationsBefore, waitMonitor))
                        completeDeferredResults(project, resultTokens);
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                });
                wait.setSystem(true);
                wait.schedule();
            }
            catch (RuntimeException e)
            {
                toast("Запустить проверки", "Не удалось запустить проверку проекта " + project.getName() //$NON-NLS-1$
                    + ": " + e.getClass().getSimpleName()); //$NON-NLS-1$
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.schedule();
    }

    /**
     * Пересчёт контекстов, отложенных шлюзом раннего бандла (большая пачка без ручного запуска).
     * Элемент — {@code {идентификатор объекта, EClass, сегмент, класс контекста}}; контекст заводится
     * заново той же перегрузкой {@code getTypedObjectContext}, что и у языковых проверок.
     *
     * @param quiet без уведомлений и без окна ручного запуска — для объектов, открытых в редакторах:
     *     шлюз пропускает их и так, а окно пропустило бы и чужую массовую пачку
     * @param settled ровно один раз: {@code true} — расчёт завершён или ни один контекст не удалось
     *     завести (повторять нечего), {@code false} — пересчёт не состоялся или прерван
     */
    public static void recomputeContexts(IProject project, List<?> contexts, boolean quiet,
        java.util.function.Consumer<Boolean> settled)
    {
        if (project == null || !project.isAccessible() || contexts == null || contexts.isEmpty())
        {
            if (!quiet)
                toast("Запустить проверки", "Нечего перепроверять: проект недоступен или список пуст."); //$NON-NLS-1$
            settled.accept(Boolean.FALSE);
            return;
        }
        Set<String> checkIds = enabledCheckIds(project);
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null ? provider.get(project) : null;
        if (checkIds.isEmpty() || manager == null)
        {
            if (!quiet)
                toast("Запустить проверки", "Менеджер проверок недоступен или в профиле проекта " + project.getName() //$NON-NLS-1$
                    + " нет включённых проверок."); //$NON-NLS-1$
            settled.accept(Boolean.FALSE);
            return;
        }

        // Шлюз пропускает ручной запуск; окно держит цикл ожидания ниже
        if (!quiet)
            MassiveChecksExemptionHook.openManualWindow(project);
        int[] marked = { 0 };
        boolean scheduled = manager.updateDerivedData(new IDerivedDataUpdate()
        {
            @Override
            public void update(IContextCollectingSession session, IBmModel model)
            {
                for (Object item : contexts)
                {
                    if (item instanceof Object[] entry && entry.length == 4 && markStoredContext(session, entry, checkIds))
                        marked[0]++;
                }
            }
        }, 0L, "comfort-recompute-checks"); //$NON-NLS-1$
        Debug.log("отложенные контексты: всего=" + contexts.size() + ", помечено=" + marked[0] //$NON-NLS-1$ //$NON-NLS-2$
            + (quiet ? ", открытые" : "")); //$NON-NLS-1$ //$NON-NLS-2$
        if (!scheduled || marked[0] == 0)
        {
            if (!quiet)
                toast("Запустить проверки", "Не удалось запланировать пересчет отложенных проверок проекта " //$NON-NLS-1$
                    + project.getName() + "."); //$NON-NLS-1$
            settled.accept(Boolean.valueOf(scheduled));
            return;
        }
        Map<Object, Long> durationsBefore = CheckDurations.snapshot();
        manager.applyForcedUpdates();
        long startMs = System.currentTimeMillis();
        if (!quiet)
            toast("Запустить проверки", "Запущен пересчет отложенных проверок проекта " + project.getName() //$NON-NLS-1$
                + ". По окончании будет показано уведомление.");

        Job job = Job.create("Комфорт: пересчет отложенных проверок", monitor -> //$NON-NLS-1$
        {
            // Счёт объектов читает модель — здесь, а не в потоке UI нажатия «Запустить»
            int count = quiet ? 0 : countContextObjects(project, contexts);
            String label = count + " " + Global.russianPlural(count, "объекта", "объектов", "объектов") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + " проекта " + project.getName(); //$NON-NLS-1$
            long deadline = System.currentTimeMillis() + PROJECT_WAIT_TIMEOUT_MS;
            boolean completed = false;
            try
            {
                while (!monitor.isCanceled() && System.currentTimeMillis() < deadline)
                {
                    if (!quiet)
                        MassiveChecksExemptionHook.openManualWindow(project);
                    if (!manager.waitAllComputations(WAIT_COMPLETION_TIMEOUT_MS))
                        continue;
                    // Конец расчёта — до паузы проверки затишья
                    long endMs = System.currentTimeMillis();
                    Thread.sleep(SETTLE_PAUSE_MS);
                    if (manager.isIdle())
                    {
                        completed = true;
                        settled.accept(Boolean.TRUE);
                        if (quiet)
                            break;
                        int[] counts = countProjectProblems(project, Set.of());
                        toastWithAction("Запустить проверки", "Завершен пересчет проверок " + label //$NON-NLS-1$
                            + " за " + LongOperationNotifyHook.formatDuration(endMs - startMs) //$NON-NLS-1$
                            + ". В проекте ошибок: " + counts[0] + ", предупреждений: " + counts[1] //$NON-NLS-1$ //$NON-NLS-2$
                            + CheckDurations.top(durationsBefore, project, endMs - startMs),
                            () -> ProblemViewMarkers.showChecked(project, List.of(), Set.of()),
                            "Показать результаты"); //$NON-NLS-1$
                        break;
                    }
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                if (!completed)
                    settled.accept(Boolean.FALSE);
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.setSystem(quiet);
        job.schedule();
    }

    /**
     * Сколько объектов метаданных за отложенными контекстами: сам объект и его модули — один объект,
     * форма — отдельный. Ключ — папка объекта или формы; модель читается, поэтому не в потоке UI.
     */
    public static int countContextObjects(IProject project, List<?> contexts)
    {
        Set<String> objects = new HashSet<>();
        List<Long> bmIds = new ArrayList<>();
        for (Object item : contexts)
        {
            if (!(item instanceof Object[] entry) || entry.length == 0)
                continue;
            if (entry[0] instanceof String path)
                objects.add(objectKey(path));
            else if (entry[0] instanceof Long id)
                bmIds.add(id);
        }
        if (!bmIds.isEmpty())
            objects.addAll(bmObjectKeys(project, bmIds).values());
        return objects.size();
    }

    /**
     * {@code /Проект/src/Тип/Имя/…} → {@code /Проект/src/Тип/Имя}; форма — отдельный объект
     * ({@code …/Имя/Forms/Форма}), корень конфигурации — {@code /Проект/src/Configuration}. Тот же
     * ключ у порога шлюза в раннем бандле.
     */
    static String objectKey(String path)
    {
        String[] parts = path.split("/"); //$NON-NLS-1$
        int depth = parts.length > 3 && "Configuration".equals(parts[3]) ? 4 //$NON-NLS-1$
            : parts.length > 7 && "Forms".equals(parts[5]) ? 7 : 5; //$NON-NLS-1$
        return String.join("/", java.util.Arrays.copyOf(parts, Math.min(depth, parts.length))); //$NON-NLS-1$
    }

    /** Ключи объектов по BM-идентификаторам: верхний объект → его файл → папка объекта. */
    static Map<Long, String> bmObjectKeys(IProject project, List<Long> ids)
    {
        Map<Long, String> keys = new java.util.HashMap<>();
        IBmModelManager models = Global.getOsgiService(IBmModelManager.class);
        IBmModel model = models != null ? models.getModel(project) : null;
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        try
        {
            if (model != null && lookup != null)
            {
                model.executeReadonlyTask(new AbstractBmTask<Void>("comfort.countDeferredObjects") //$NON-NLS-1$
                {
                    @Override
                    public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        for (Long id : ids)
                        {
                            IBmObject object = transaction.getObjectById(id.longValue());
                            IBmObject top = object == null || object.bmIsTop() ? object : object.bmGetTopObject();
                            IFile file = top instanceof EObject eObject ? lookup.getPlatformResource(eObject) : null;
                            keys.put(id, file != null
                                ? objectKey(URI.createPlatformResourceURI(file.getFullPath().toString(), true)
                                    .toPlatformString(false))
                                : "bm:" + (top != null ? top.bmGetId() : id)); //$NON-NLS-1$
                        }
                        return null;
                    }
                }, true);
                return keys;
            }
        }
        catch (RuntimeException e)
        {
            Debug.log("счёт отложенных объектов: " + e); //$NON-NLS-1$
        }
        keys.clear();
        ids.forEach(id -> keys.put(id, "bm:" + id)); //$NON-NLS-1$
        return keys;
    }

    /** Отложенный контекст заново: BM-объект — по числовому идентификатору, модуль — по пути файла. */
    private static boolean markStoredContext(IContextCollectingSession session, Object[] entry, Set<String> checkIds)
    {
        try
        {
            Method method = entry[0] instanceof Long
                ? IContextCollectingSession.class.getMethod("getTypedObjectContext", //$NON-NLS-1$
                    long.class, EClass.class, String.class, Class.class)
                : IContextCollectingSession.class.getMethod("getTypedObjectContext", //$NON-NLS-1$
                    Object.class, EClass.class, String.class, Class.class);
            Object ctx = method.invoke(session, entry[0], entry[1], entry[2], entry[3]);
            return markContextFullRebuild(ctx, checkIds);
        }
        catch (Exception e)
        {
            Debug.log("отложенный контекст " + entry[2] + " " + entry[0] + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> deferredResultTokens(IProject project, Set<String> checkIds)
    {
        Object bridge = System.getProperties().get("tormozit.checkRecompute.event.results");
        if (!(bridge instanceof java.util.function.Function<?, ?> function))
            return Map.of();
        Map<String, String> snapshot = (Map<String, String>) ((java.util.function.Function<Object[], Object>) function)
            .apply(new Object[] { "snapshot", String.valueOf(project) });
        Map<String, String> tokens = new java.util.HashMap<>();
        ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
        if (repository == null)
            return Map.of();
        for (var entry : snapshot.entrySet())
        {
            int separator = entry.getKey().indexOf('\t');
            if (separator < 0)
                continue;
            String id = entry.getKey().substring(separator + 1);
            if (!checkIds.isEmpty() && !checkIds.contains(id))
                continue;
            CheckUid uid = new CheckUid(id, entry.getKey().substring(0, separator));
            if (!repository.toUid(id, project).contains(uid))
                continue;
            var settings = repository.getSettings(uid, project);
            if (settings != null && settings.isEnabled())
                tokens.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(tokens);
    }

    @SuppressWarnings("unchecked")
    private static void completeDeferredResults(IProject project, Map<String, String> tokens)
    {
        if (tokens.isEmpty())
            return;
        Object bridge = System.getProperties().get("tormozit.checkRecompute.event.results");
        if (bridge instanceof java.util.function.Function<?, ?> function)
            ((java.util.function.Function<Object[], Object>) function)
                .apply(new Object[] { "complete", String.valueOf(project), tokens });
    }

    /**
     * Ждёт завершения проверки проекта. Ожидание идёт отрезками: полная проверка большой
     * конфигурации длится куда дольше {@link #WAIT_COMPLETION_TIMEOUT_MS}, а отменить задачу
     * пользователь должен мочь в любой момент.
     */
    private static boolean notifyProjectComplete(IDerivedDataManager manager, IProject project,
        Set<String> checkIds, long startMs, Map<Object, Long> durationsBefore,
        org.eclipse.core.runtime.IProgressMonitor monitor)
    {
        long deadline = System.currentTimeMillis() + PROJECT_WAIT_TIMEOUT_MS;
        long endMs;
        try
        {
            while (true)
            {
                MassiveChecksExemptionHook.openManualWindow(project);
                if (monitor != null && monitor.isCanceled())
                    return false;
                if (System.currentTimeMillis() > deadline)
                {
                    toast("Запустить проверки", "Проверка проекта " + project.getName() //$NON-NLS-1$
                        + " не завершилась за " + (PROJECT_WAIT_TIMEOUT_MS / 60_000) + " мин."); //$NON-NLS-1$ //$NON-NLS-2$
                    return false;
                }
                if (!manager.waitAllComputations(WAIT_COMPLETION_TIMEOUT_MS))
                    continue;
                endMs = System.currentTimeMillis();
                // Проверка идёт волнами: за паузой приходит следующая пачка объектов, и счёт
                // сразу после первого затишья выходит заниженным
                Thread.sleep(SETTLE_PAUSE_MS);
                if (monitor != null && monitor.isCanceled())
                    return false;
                if (manager.isIdle())
                    break;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }

        int[] counts = countProjectProblems(project, checkIds);
        toastWithAction("Запустить проверки", "Завершен пересчет " + checkCount(checkIds) + " проверок проекта " //$NON-NLS-1$ //$NON-NLS-2$
            + project.getName() + " за " + LongOperationNotifyHook.formatDuration(endMs - startMs) //$NON-NLS-1$
            + ". Ошибок: " + counts[0] + ", Предупреждений: " + counts[1] //$NON-NLS-1$ //$NON-NLS-2$
            + CheckDurations.top(durationsBefore, project, endMs - startMs),
            () -> ProblemViewMarkers.showChecked(project, List.of(), Set.of()), "Показать результаты"); //$NON-NLS-1$
        return true;
    }

    /**
     * Проблемы всего проекта — из того же источника, что и шапка панели.
     * <p>
     * Считать потоком маркеров под отбором проекта нельзя: числа выходят заниженными. У менеджера
     * маркеров есть готовый агрегат {@link IMarkerInfo} с количеством по каждой критичности —
     * именно из него {@code MarkerStats} панели строит «Ошибок / Предупреждений».
     */
    private static String checkCount(Set<String> checkIds)
    {
        return checkIds.isEmpty() ? "всех" : String.valueOf(checkIds.size()); //$NON-NLS-1$
    }

    /** Пустой набор — все проблемы проекта; иначе только маркеры указанных проверок (по их коду). */
    private static int[] countProjectProblems(IProject project, Set<String> checkIds)
    {
        IMarkerInfo info;
        if (checkIds.isEmpty())
        {
            IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
            info = markerManager != null ? markerManager.getMarkerInfo(project) : null;
        }
        else
        {
            IMarkerManagerV2 markers = Global.getOsgiService(IMarkerManagerV2.class);
            ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
            if (markers == null || repository == null)
                return new int[] { 0, 0 };
            MarkerFilter filter = MarkerFilter.createProjectFilter(project);
            for (String id : checkIds)
            {
                Set<CheckUid> uids = repository.toUid(id, project);
                // Внутренние проверки BSL без CheckUid пишут в маркер свой код.
                if (uids.isEmpty())
                    filter.addValue(MarkerIndex.CHECK_ID, id);
                for (CheckUid uid : uids)
                {
                    String code = repository.getShortUid(uid, project);
                    if (code != null)
                        filter.addValue(MarkerIndex.CHECK_ID, code);
                }
            }
            info = markers.createReader(project).getMarkerInfo(filter);
        }
        return errorsAndWarnings(info);
    }

    /** Деление как в {@code MarkerStats} панели: ошибки — ERRORS, предупреждения — BLOCKER…TRIVIAL. */
    private static int[] errorsAndWarnings(IMarkerInfo info)
    {
        if (info == null)
            return new int[] { 0, 0 };

        int warnings = 0;
        for (MarkerSeverity severity : new MarkerSeverity[] { MarkerSeverity.BLOCKER, MarkerSeverity.CRITICAL,
            MarkerSeverity.MAJOR, MarkerSeverity.MINOR, MarkerSeverity.TRIVIAL })
        {
            warnings += info.getCount(severity);
        }
        return new int[] { info.getCount(MarkerSeverity.ERRORS), warnings };
    }

    /**
     * Перезапускает все включённые в профиле проверки для указанных объектов проекта.
     *
     * @param project проект объектов; {@code null} — ничего не делать
     * @param objects объекты (обычно top-объекты метаданных); не-BM объекты пропускаются
     */
    public static void recomputeObjects(IProject project, Collection<? extends EObject> objects)
    {
        try
        {
            recomputeObjectsBody(project, objects);
        }
        catch (Throwable t)
        {
            toast("Запустить проверки", "Ошибка при запуске проверки: " + t.getClass().getSimpleName() //$NON-NLS-1$
                + (t.getMessage() != null ? " — " + t.getMessage() : "")); //$NON-NLS-1$
        }
    }

    /**
     * То же, что {@link #recomputeObjects}, но сначала в фоне дожидается затишья менеджера
     * производных данных. Нужно там, где перепроверку запускает не пользователь, а сам плагин
     * сразу после изменения файлов проекта: ЕДТ в этот момент ещё перечитывает изменённое, и
     * немедленный запуск упирается в «Предыдущая перепроверка ещё выполняется». Расчёт производных
     * данных идёт вне заданий Eclipse, поэтому в панели фоновых задач его не видно.
     *
     * @param project проект объектов; {@code null} — ничего не делать
     * @param objects объекты для перепроверки
     */
    public static void recomputeObjectsWhenIdle(IProject project, Collection<? extends EObject> objects)
    {
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null && project != null ? provider.get(project) : null;
        if (manager == null)
        {
            recomputeObjects(project, objects);
            return;
        }
        Job job = Job.create("Комфорт: ожидание перед перепроверкой", monitor -> //$NON-NLS-1$
        {
            long deadline = System.currentTimeMillis() + WAIT_COMPLETION_TIMEOUT_MS;
            try
            {
                // Расчёт идёт волнами: после затишья может прийти следующая пачка
                while (!manager.isIdle())
                {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0 || monitor.isCanceled())
                        break;
                    manager.waitAllComputations(left);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return org.eclipse.core.runtime.Status.CANCEL_STATUS;
            }
            if (!monitor.isCanceled())
                recomputeObjects(project, objects);
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private static void recomputeObjectsBody(IProject project, Collection<? extends EObject> objects)
    {
        if (project == null || objects == null || objects.isEmpty())
        {
            toast("Запустить проверки", "Не выбран объект для проверки."); //$NON-NLS-1$
            return;
        }

        Set<String> checkIds = enabledCheckIds(project);
        if (checkIds.isEmpty())
        {
            toast("Запустить проверки", //$NON-NLS-1$
                "В профиле проекта нет включённых проверок — перепроверять нечего.");
            return;
        }

        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null ? provider.get(project) : null;
        if (manager == null)
        {
            toast("Запустить проверки", "Менеджер проверок недоступен для проекта " + project.getName() + "."); //$NON-NLS-1$
            return;
        }

        if (!manager.isIdle())
        {
            toast("Запустить проверки", //$NON-NLS-1$
                "Предыдущая перепроверка ещё выполняется. Повторите через несколько секунд.");
            return;
        }

        // Источники — как пришли из UI (для формы это BasicForm/DocumentFormImpl).
        // Form.model.Form резолвим только внутри updateDerivedData.
        List<EObject> sources = new ArrayList<>();
        for (EObject object : objects)
        {
            if (object == null)
                continue;
            if (object instanceof IBmObject || object instanceof BasicForm)
                sources.add(object);
        }
        if (sources.isEmpty())
        {
            toast("Запустить проверки", "Выбранный элемент нельзя точечно перепроверить."); //$NON-NLS-1$
            return;
        }

        String objectsLabel = describeObjects(sources);
        toast("Запустить проверки", //$NON-NLS-1$
            "Запущен пересчет всех проверок объекта " + objectsLabel
                + " с вложенными. По окончании будет показано уведомление.");

        // Проверки текста модулей живут в языковых сегментах и заводятся не на BM-объект, а на
        // путь файла — модельной пометки объекта для них недостаточно.
        List<IFile> moduleFiles = collectModuleFiles(sources);

        // Режим «Отключить массовые проверки» не должен глушить то, что пользователь запросил сам.
        MassiveChecksExemptionHook.openManualWindow(project);

        List<IBmObject> targets = new ArrayList<>();
        Set<Long> targetIds = new HashSet<>();
        int[] marked = { 0 };
        boolean scheduled = manager.updateDerivedData(new IDerivedDataUpdate()
        {
            @Override
            public void update(IContextCollectingSession session, IBmModel model)
            {
                for (EObject source : sources)
                {
                    for (EObject item : expandToCheckItems(source))
                    {
                        EObject target = toCheckTarget(item);
                        if (!(target instanceof IBmObject bmObject))
                            continue;
                        long id = bmObject.bmGetId();
                        if (!targetIds.add(id))
                            continue;
                        targets.add(bmObject);
                        boolean okM = markSegmentFullRebuild(session, bmObject, M_CHECKS_SEGMENT, checkIds);
                        boolean okCm = markSegmentFullRebuild(session, bmObject, CM_CHECKS_SEGMENT, checkIds);
                        if (okM || okCm)
                            marked[0]++;
                    }
                }

                for (IFile moduleFile : moduleFiles)
                {
                    boolean okL = markLanguageFullRebuild(session, moduleFile, L_CHECKS_SEGMENT, checkIds);
                    boolean okCl = markLanguageFullRebuild(session, moduleFile, CL_CHECKS_SEGMENT, checkIds);
                    if (okL || okCl)
                        marked[0]++;
                }
            }
        }, 0L, "comfort-recompute-checks"); //$NON-NLS-1$
        if (!scheduled)
        {
            toast("Запустить проверки", "Не удалось запланировать перепроверку объекта " + objectsLabel + "."); //$NON-NLS-1$
            return;
        }
        if (targets.isEmpty())
        {
            toast("Запустить проверки", "Не удалось определить объект проверки для " + objectsLabel + "."); //$NON-NLS-1$
            return;
        }
        if (marked[0] == 0)
        {
            toast("Запустить проверки", //$NON-NLS-1$
                "Не удалось пометить проверки для " + objectsLabel + " — перепроверка не запущена.");
            return;
        }
        Map<Object, Long> durationsBefore = CheckDurations.snapshot();
        manager.applyForcedUpdates();
        notifyWhenComplete(manager, project, objectsLabel, sources, targets, durationsBefore);
    }

    /**
     * Разбор привязки маркеров проверенного объекта — в журнал «Комфорт» и только при включённом
     * флажке «Вести журнал»: проход идёт по всем маркерам проекта.
     * <p>
     * Нужен потому, что запрос маркеров по идентификатору объекта возвращает не всё, что панель
     * показывает для того же объекта. В строке видно все четыре привязки маркера
     * ({@code markerObjectId}, {@code topObjectId}, {@code sourceObjectId},
     * {@code sourceObjectTopObjectId}) — по ним и определяется, каким полем зацеплена
     * недосчитанная проблема.
     */
    private static void logRelatedMarkers(IMarkerManager markerManager, IProject project,
        Collection<Object> objectIds, Collection<? extends EObject> sources)
    {
        if (!Global.isLogEnabled())
            return;
        try
        {
            String name = null;
            for (EObject source : sources)
            {
                if (source instanceof MdObject md && md.getName() != null)
                {
                    name = md.getName();
                    break;
                }
            }
            if (name == null)
                return;

            int shown = 0;
            for (Marker marker : markerManager.markers(MarkerFilter.createProjectFilter(project))
                .collect(Collectors.toList()))
            {
                if (marker == null || shown >= 20)
                    continue;
                String ids = String.valueOf(marker.getMarkerObjectId()) + '|' + marker.getTopObjectId() + '|'
                    + marker.getSourceObjectId() + '|' + marker.getSourceObjectTopObjectId();
                boolean related = ids.contains(name) || objectIds.contains(marker.getTopObjectId())
                    || objectIds.contains(marker.getMarkerObjectId());
                if (!related)
                    continue;
                shown++;
                Debug.log("маркер: критичность=" + marker.getSeverity() //$NON-NLS-1$
                    + ", проверка=" + marker.getCheckId() //$NON-NLS-1$
                    + ", объект=" + marker.getMarkerObjectId() //$NON-NLS-1$
                    + ", top=" + marker.getTopObjectId() //$NON-NLS-1$
                    + ", источник=" + marker.getSourceObjectId() //$NON-NLS-1$
                    + ", top источника=" + marker.getSourceObjectTopObjectId() //$NON-NLS-1$
                    + ", сообщение=" + marker.getMessage()); //$NON-NLS-1$
            }
            Debug.log("маркеров, связанных с «" + name + "», найдено " + shown); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (RuntimeException e)
        {
            Global.logError(Debug.TAG, "разбор маркеров проекта", e); //$NON-NLS-1$
        }
    }

    /**
     * Ждёт в фоне завершения текущего цикла перепроверки и сообщает об этом тостом — иначе
     * пользователь не видит, когда можно смотреть на обновлённый результат в панели.
     */
    private static void notifyWhenComplete(IDerivedDataManager manager, IProject project, String objectsLabel,
        Collection<? extends EObject> objects, List<IBmObject> targets, Map<Object, Long> durationsBefore)
    {
        long startMs = System.currentTimeMillis();
        Job waitJob = Job.create("Комфорт: перепроверка конфигурации", monitor -> //$NON-NLS-1$
        {
            monitor.beginTask("Перепроверка конфигурации", org.eclipse.core.runtime.IProgressMonitor.UNKNOWN); //$NON-NLS-1$
            try
            {
                boolean completed = manager.waitAllComputations(WAIT_COMPLETION_TIMEOUT_MS);
                if (completed)
                {
                    long durationMs = System.currentTimeMillis() - startMs;
                    Set<Object> topIds = checkedTopIds(project, targets, objects);
                    int[] counts = countProblems(project, targets, objects, topIds);
                    toastWithAction("Запустить проверки", "Завершен пересчет всех проверок объекта " + objectsLabel //$NON-NLS-1$
                        + " с вложенными за " + LongOperationNotifyHook.formatDuration(durationMs) //$NON-NLS-1$
                        + ". Ошибок: " + counts[0] //$NON-NLS-1$
                        + ", Предупреждений: " + counts[1] //$NON-NLS-1$
                        + CheckDurations.top(durationsBefore, project, durationMs),
                        () -> ProblemViewMarkers.showChecked(project, objects, topIds), "Показать результаты"); //$NON-NLS-1$
                }
                else
                {
                    toast("Запустить проверки", "Перепроверка объекта " + objectsLabel //$NON-NLS-1$
                        + " с вложенными не завершилась за " //$NON-NLS-1$
                        + (WAIT_COMPLETION_TIMEOUT_MS / 1000) + " с.");
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                monitor.done();
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        waitJob.schedule();
    }

    /**
     * Считает проблемы проверенных объектов так же, как шапка панели «Проблемы конфигурации»:
     * «Ошибок: N, Предупреждений: M».
     * <p>
     * Деление на два числа — как в {@code MarkerStats} панели: ошибка это только
     * {@link MarkerSeverity#ERRORS} (группа «Ошибки конфигурации»), а предупреждения — все прочие
     * критичности от {@link MarkerSeverity#BLOCKER} до {@link MarkerSeverity#TRIVIAL} (группы
     * «Значительные», «Незначительные», «Тривиальные»).
     * <p>
     * Отбор — как у области «Текущий объект» панели ({@code LazyProblemView.filterByScope}
     * в ui.validation 11.0.100): {@link MarkerIndex#TOP_OBJECT_ID} из идентификаторов top-объектов
     * и их подчинённых top-объектов ({@link ISubordinateObjectsProvider}). По BM-объекту
     * ({@code getNestedMarkers}) не считать: проблемы текста модуля привязаны к файлу и теряются.
     *
     * @param topIds проверенные top-объекты с подчинёнными — {@link #checkedTopIds}
     * @return массив из двух чисел: ошибки и предупреждения
     */
    private static int[] countProblems(IProject project, List<IBmObject> targets,
        Collection<? extends EObject> sources, Set<Object> topIds)
    {
        IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
        IMarkerManagerV2 markers = Global.getOsgiService(IMarkerManagerV2.class);
        if (markerManager == null || markers == null)
            return new int[] { 0, 0 };

        LinkedHashSet<Object> objectIds = new LinkedHashSet<>();
        for (IBmObject bmObject : checkedBmObjects(targets, sources))
            objectIds.add(Long.valueOf(bmObject.bmGetId()));
        MarkerFilter filter = MarkerFilter.createProjectFilter(project);
        for (Object topId : topIds)
            filter.addValue(MarkerIndex.TOP_OBJECT_ID, topId);
        int[] counts = errorsAndWarnings(markers.createReader(project).getMarkerInfo(filter));
        Debug.log("счёт по объекту: отбор=" + filter + ", ошибок=" + counts[0] //$NON-NLS-1$ //$NON-NLS-2$
            + ", предупреждений=" + counts[1]); //$NON-NLS-1$
        logRelatedMarkers(markerManager, project, objectIds, sources);
        return counts;
    }

    /**
     * Идентификаторы top-объектов проверенного набора вместе с их подчинёнными top-объектами
     * ({@link ISubordinateObjectsProvider}) — по ним панель отбирает область «Текущий объект».
     */
    private static Set<Object> checkedTopIds(IProject project, List<IBmObject> targets,
        Collection<? extends EObject> sources)
    {
        LinkedHashSet<Object> ownTopIds = new LinkedHashSet<>();
        for (IBmObject bmObject : checkedBmObjects(targets, sources))
        {
            IBmObject top = bmObject.bmGetTopObject();
            ownTopIds.add(Long.valueOf((top != null ? top : bmObject).bmGetId()));
        }
        LinkedHashSet<Object> topIds = new LinkedHashSet<>();
        ISubordinateObjectsProvider subordinates = Global.getOsgiService(ISubordinateObjectsProvider.class);
        for (Object topId : ownTopIds)
        {
            topIds.add(topId);
            if (subordinates != null)
                topIds.addAll(subordinates.getSubordinateTopObjectIds(project, topId));
        }
        return topIds;
    }

    private static List<IBmObject> checkedBmObjects(List<IBmObject> targets, Collection<? extends EObject> sources)
    {
        List<IBmObject> objects = new ArrayList<>(targets);
        if (sources != null)
        {
            for (EObject source : sources)
            {
                if (source instanceof IBmObject bm)
                    objects.add(bm);
            }
        }
        return objects;
    }

    /**
     * Человекочитаемое имя объектов для тостов запуска/завершения перепроверки, например
     * {@code ОбщаяФорма.Форма1} или {@code 3 объекта: ..., ..., ...} для нескольких.
     * <p>
     * Для {@link BasicForm} не вызываем {@code bmGetFqn()} — на форме он тянет модель Form
     * до сессии DD и срывает последующий {@code updateDerivedData}.
     */
    private static String describeObjects(Collection<? extends EObject> objects)
    {
        List<String> names = new ArrayList<>();
        for (EObject object : objects)
            names.add(describeOneSafe(object));
        if (names.isEmpty())
            return "?"; //$NON-NLS-1$
        if (names.size() == 1)
            return names.get(0);
        return names.size() + " объекта: " + String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String describeOneSafe(EObject object)
    {
        if (object == null)
            return "?"; //$NON-NLS-1$
        try
        {
            if (object instanceof BasicForm basicForm)
            {
                String formName = object instanceof MdObject mdForm ? mdForm.getName() : null;
                if (formName == null || formName.isBlank())
                    formName = basicForm.eClass().getName();
                String owner = describeFormOwner(basicForm);
                if (owner != null)
                    return owner + ".Форма." + formName; //$NON-NLS-1$
                String selfType = MdTypeMapping.anyToRu(basicForm.eClass().getName());
                return (selfType != null ? selfType : "Форма") + "." + formName; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (object instanceof IBmObject bmObject)
            {
                String name = MdTypeMapping.bmFqnToRuFullName(bmObject.bmGetFqn());
                if (name != null)
                    return name;
                String fqn = bmObject.bmGetFqn();
                if (fqn != null)
                    return fqn;
            }
            if (object instanceof MdObject md && md.getName() != null && !md.getName().isBlank())
                return object.eClass().getName() + "." + md.getName(); //$NON-NLS-1$
        }
        catch (RuntimeException ignored)
        {
        }
        return object.eClass().getName();
    }

    /**
     * Владелец формы полным русским именем: {@code Справочник.Валюты}. Нужен, чтобы в уведомлении
     * стояло «Справочник.Валюты.Форма.ФормаСписка», а не безадресное «Форма.ФормаСписка».
     * <p>
     * Имя берётся у владельца, а не у самой формы: {@code bmGetFqn()} на {@link BasicForm} тянет
     * модель {@code Form} до сессии производных данных и срывает последующий
     * {@code updateDerivedData}. Обход контейнера такой цены не имеет.
     *
     * @return полное имя владельца или {@code null}, если владельца нет (общая форма лежит прямо в
     * конфигурации)
     */
    private static String describeFormOwner(BasicForm basicForm)
    {
        EObject owner = basicForm.eContainer();
        if (!(owner instanceof MdObject ownerObject) || "Configuration".equals(owner.eClass().getName())) //$NON-NLS-1$
            return null;
        try
        {
            if (owner instanceof IBmObject bmOwner)
            {
                String ru = MdTypeMapping.bmFqnToRuFullName(bmOwner.bmGetFqn());
                if (ru != null)
                    return ru;
            }
            String ownerName = ownerObject.getName();
            if (ownerName == null || ownerName.isBlank())
                return null;
            String ownerType = MdTypeMapping.anyToRu(owner.eClass().getName());
            return (ownerType != null ? ownerType : owner.eClass().getName()) + "." + ownerName; //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Что помечать проверками для выбранного узла.
     * <p>
     * Сам объект всегда. Если это не форма, а объект МД (документ, справочник…) — ещё все
     * вложенные {@link BasicForm}: у форм свой BM ({@code form.model.Form}), и fullRebuild
     * родителя их не пересчитывает.
     * <p>
     * Только перечисление {@code BasicForm} из containment (без {@code getForm()}): сам Form
     * резолвится в {@link #toCheckTarget} уже в сессии DD.
     */
    private static List<EObject> expandToCheckItems(EObject source)
    {
        List<EObject> items = new ArrayList<>();
        if (source == null)
            return items;
        items.add(source);
        if (source instanceof BasicForm)
            return items;
        try
        {
            TreeIterator<EObject> it = source.eAllContents();
            while (it.hasNext())
            {
                EObject next = it.next();
                if (next instanceof BasicForm)
                    items.add(next);
            }
        }
        catch (RuntimeException ignored)
        {
        }
        return items;
    }

    /**
     * Приводит объект к тому, на который навешаны проверки.
     * <p>
     * Из навигатора и из области отбора панели приходит объект метаданных формы
     * ({@code BasicForm}: {@code DocumentFormImpl}, {@code CommonFormImpl} и т.п.), а проверки форм
     * зарегистрированы на {@code form.model.Form} — вложенный объект, который и привязан к
     * BM-транзакции. Без этого перехода контекст сегмента проверок для формы не находится.
     * <p>
     * Вызывать только внутри {@code IDerivedDataUpdate#update}.
     */
    private static EObject toCheckTarget(EObject object)
    {
        if (object instanceof BasicForm basicForm)
        {
            AbstractForm form = basicForm.getForm();
            if (form != null)
                return form;
        }
        return object;
    }

    /**
     * Помечает контекст сегмента проверок на полную перепроверку всех {@code checkIds}.
     *
     * @return {@code true}, если контекст успешно помечен
     */
    private static boolean markSegmentFullRebuild(IContextCollectingSession session, IBmObject bmObject,
        String segmentId, Set<String> checkIds)
    {
        try
        {
            return markContextFullRebuild(session.getObjectContext(bmObject, segmentId), checkIds);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Помечает на полную перепроверку контекст языкового сегмента модуля.
     * <p>
     * Контекст языковых проверок заводится не на BM-объект, а на путь файла — ровно так, как это
     * делает {@code CheckScheduler}: идентификатор объекта это
     * {@code URI.createPlatformResourceURI(путь, true).toPlatformString(false)}, класс контекста —
     * {@code LanguageCheckObjectContext}, класс объекта — {@code BslPackage.Literals.MODULE}.
     * Пакет с контекстом бандл проверок не экспортирует, поэтому класс грузится загрузчиком самого
     * бандла, а метод сессии вызывается через интерфейс (у него две перегрузки на четыре
     * аргумента, и выбирать нужную должны мы, а не механизм подбора).
     *
     * @return {@code true}, если контекст успешно помечен
     */
    private static boolean markLanguageFullRebuild(IContextCollectingSession session, IFile moduleFile,
        String segmentId, Set<String> checkIds)
    {
        try
        {
            Class<?> contextClass = languageContextClass();
            if (contextClass == null)
                return false;
            String objectId =
                URI.createPlatformResourceURI(moduleFile.getFullPath().toString(), true).toPlatformString(false);
            Method method = IContextCollectingSession.class.getMethod("getTypedObjectContext", //$NON-NLS-1$
                Object.class, EClass.class, String.class, Class.class);
            Object ctx = method.invoke(session, objectId, BslPackage.Literals.MODULE, segmentId, contextClass);
            return markContextFullRebuild(ctx, checkIds);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Класс контекста языковых проверок из непубличного пакета бандла проверок. */
    private static Class<?> languageContextClass()
    {
        Class<?> cached = languageContextClass;
        if (cached != null)
            return cached;
        try
        {
            cached = ICheckRepository.class.getClassLoader().loadClass(LANGUAGE_CONTEXT_CLASS);
            languageContextClass = cached;
            return cached;
        }
        catch (ClassNotFoundException | LinkageError e)
        {
            return null;
        }
    }

    /** Общая часть разметки: полная перепроверка всех {@code checkIds} по готовому контексту. */
    private static boolean markContextFullRebuild(Object ctx, Set<String> checkIds)
    {
        try
        {
            if (ctx == null)
                return false;
            if (ctx instanceof IObjectDerivedDataContext typed)
            {
                typed.setFullRebuild(true);
                typed.setInactive(false);
            }
            else
            {
                ctx.getClass().getMethod("setFullRebuild", boolean.class).invoke(ctx, Boolean.TRUE); //$NON-NLS-1$
                ctx.getClass().getMethod("setInactive", boolean.class).invoke(ctx, Boolean.FALSE); //$NON-NLS-1$
            }
            ctx.getClass().getMethod("addCheckIds", Set.class).invoke(ctx, checkIds); //$NON-NLS-1$
            if (ctx instanceof IObjectDerivedDataContext typed)
                typed.setFullRebuild(true);
            else
                ctx.getClass().getMethod("setFullRebuild", boolean.class).invoke(ctx, Boolean.TRUE); //$NON-NLS-1$
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Файлы модулей проверяемых объектов: у объекта МД это его собственная папка со всеми
     * вложенными {@code *.bsl} (модуль объекта, модуль менеджера, модули форм и команд).
     */
    private static List<IFile> collectModuleFiles(Collection<? extends EObject> sources)
    {
        List<IFile> files = new ArrayList<>();
        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        if (lookup == null)
            return files;

        Set<String> seen = new HashSet<>();
        for (EObject source : sources)
        {
            IFile objectFile;
            try
            {
                objectFile = lookup.getPlatformResource(source);
            }
            catch (RuntimeException e)
            {
                continue;
            }
            IContainer folder = objectFile != null ? objectFile.getParent() : null;
            if (folder == null || !folder.isAccessible())
                continue;
            try
            {
                folder.accept((IResource resource) ->
                {
                    if (resource instanceof IFile file && BSL_EXTENSION.equalsIgnoreCase(file.getFileExtension())
                        && seen.add(file.getFullPath().toString()))
                    {
                        files.add(file);
                    }
                    return true;
                });
            }
            catch (CoreException e)
            {
                continue;
            }
        }
        return files;
    }

    static void toast(String title, String message)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, 5_000));
    }

    /**
     * Самые долгие проверки пересчёта по суммам раннего бандла ({@code tormozit.checkDuration.totals}):
     * собственное время проверки, сложенное по всем потокам. Туда попадают и проверки, параллельно
     * шедшие по другим поводам.
     */
    private static final class CheckDurations
    {
        /** Короче — список не нужен. */
        private static final long MIN_WALL_MS = 20_000L;
        private static final int TOP = 3;

        private CheckDurations()
        {
        }

        /** Суммы на этот момент; пусто — ранний бандл замер не поставил. */
        @SuppressWarnings("unchecked")
        static Map<Object, Long> snapshot()
        {
            Object totals = System.getProperties().get("tormozit.checkDuration.totals"); //$NON-NLS-1$
            return totals instanceof java.util.function.Supplier<?> supplier
                ? (Map<Object, Long>)supplier.get() : Map.of();
        }

        /**
         * Строка для уведомления: три самые долгие проверки с названиями и их доли в общем однопоточном
         * времени всех проверок — столько сэкономит отключение проверки. Пусто, если пересчёт был
         * короче 20 с.
         */
        static String top(Map<Object, Long> before, IProject project, long wallMs)
        {
            if (wallMs <= MIN_WALL_MS)
                return ""; //$NON-NLS-1$
            ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
            Map<String, Long> byTitle = new java.util.HashMap<>();
            long total = 0;
            for (var entry : snapshot().entrySet())
            {
                long nanos = entry.getValue().longValue() - before.getOrDefault(entry.getKey(), Long.valueOf(0)).longValue();
                if (nanos > 0 && entry.getKey() instanceof ICheck<?> check)
                {
                    byTitle.merge(title(repository, check, project), Long.valueOf(nanos), Long::sum);
                    total += nanos;
                }
            }
            if (total <= 0)
                return ""; //$NON-NLS-1$
            long all = total;
            String list = byTitle.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP)
                .map(entry -> percent(entry.getValue().longValue(), all) + " - " + entry.getKey()) //$NON-NLS-1$
                .collect(Collectors.joining("\n")); //$NON-NLS-1$
            return ".\nСамые долгие:\n" + list; //$NON-NLS-1$
        }

        private static String percent(long part, long total)
        {
            long value = Math.round(part * 100.0 / total);
            return value < 1 ? "<1%" : value + "%"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * Название проверки, как в списке «Проверки»; без него — идентификатор. Короткий код
         * {@code SU…} не показывать: его назначает реестр проекта, человеку он ничего не говорит.
         */
        private static String title(ICheckRepository repository, ICheck<?> check, IProject project)
        {
            String id = check.getCheckId();
            if (repository != null && id != null)
            {
                for (CheckUid uid : repository.toUid(id, project))
                {
                    var settings = repository.getSettings(uid, project);
                    String title = settings != null ? settings.getTitle() : null;
                    if (title != null && !title.isBlank())
                        return title;
                }
            }
            return id != null ? id : check.getClass().getSimpleName();
        }
    }

    /** Журнал «Комфорт» — при включённом флажке «Вести журнал». */
    private static final class Debug
    {
        static final String TAG = "CheckRecompute"; //$NON-NLS-1$

        private Debug()
        {
        }

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }

    private static void toastWithAction(String title, String message, Runnable action, String actionLabel)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, 5_000, action, actionLabel));
    }

    /** Идентификаторы проверок, включённых в профиле проекта. */
    private static Set<String> enabledCheckIds(IProject project)
    {
        ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
        IDtProject dtProject = Global.getDtProjectFromWorkspaceProject(project);
        if (repository == null || dtProject == null)
            return Set.of();
        Map<IDtProject, Set<CheckUid>> uidsByProject = repository.getCheckUids();
        Set<CheckUid> uids = uidsByProject != null ? uidsByProject.get(dtProject) : null;
        if (uids == null || uids.isEmpty())
            return Set.of();
        return uids.stream().map(CheckUid::getCheckId).collect(Collectors.toSet());
    }
}
