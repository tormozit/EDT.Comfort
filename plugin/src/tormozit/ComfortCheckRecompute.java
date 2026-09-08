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
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.IDerivedDataUpdate;
import com._1c.g5.v8.derived.context.IContextCollectingSession;
import com._1c.g5.v8.derived.context.IObjectDerivedDataContext;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com._1c.g5.v8.dt.validation.marker.IMarkerInfo;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
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
 * «Проверить» в меню навигатора.
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
        Debug.log("проверка проекта: вход, проект=" //$NON-NLS-1$
            + (project == null ? "null" : project.getName())); //$NON-NLS-1$
        if (project == null || !project.isAccessible())
        {
            toast("Проверить", "Проект недоступен."); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        ICheckScheduler scheduler = Global.getOsgiService(ICheckScheduler.class);
        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null ? provider.get(project) : null;
        if (scheduler == null || manager == null)
        {
            toast("Проверить", "Планировщик проверок недоступен для проекта " + project.getName() + "."); //$NON-NLS-1$
            return;
        }

        // Режим «Отключить массовые проверки» не должен глушить то, что запросил пользователь
        MassiveChecksExemptionHook.openManualWindow(project);
        toast("Проверить", "Запущена проверка всех объектов проекта " + project.getName() //$NON-NLS-1$
            + ". По окончании будет показано уведомление.");

        Job job = Job.create("Комфорт: проверка проекта " + project.getName(), monitor -> //$NON-NLS-1$
        {
            try
            {
                Debug.log("проверка проекта: планировщик вызван"); //$NON-NLS-1$
                scheduler.scheduleValidation(project, Set.of(), List.of(), monitor);
                Debug.log("проверка проекта: планировщик отработал, ждём завершения"); //$NON-NLS-1$
                notifyProjectComplete(manager, project, monitor);
            }
            catch (RuntimeException e)
            {
                toast("Проверить", "Не удалось запустить проверку проекта " + project.getName() //$NON-NLS-1$
                    + ": " + e.getClass().getSimpleName()); //$NON-NLS-1$
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        job.schedule();
    }

    /**
     * Ждёт завершения проверки проекта. Ожидание идёт отрезками: полная проверка большой
     * конфигурации длится куда дольше {@link #WAIT_COMPLETION_TIMEOUT_MS}, а отменить задачу
     * пользователь должен мочь в любой момент.
     */
    private static void notifyProjectComplete(IDerivedDataManager manager, IProject project,
        org.eclipse.core.runtime.IProgressMonitor monitor)
    {
        long deadline = System.currentTimeMillis() + PROJECT_WAIT_TIMEOUT_MS;
        try
        {
            while (true)
            {
                if (monitor != null && monitor.isCanceled())
                    return;
                if (System.currentTimeMillis() > deadline)
                {
                    toast("Проверить", "Проверка проекта " + project.getName() //$NON-NLS-1$
                        + " не завершилась за " + (PROJECT_WAIT_TIMEOUT_MS / 60_000) + " мин."); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                if (!manager.waitAllComputations(WAIT_COMPLETION_TIMEOUT_MS))
                    continue;
                // Проверка идёт волнами: за паузой приходит следующая пачка объектов, и счёт
                // сразу после первого затишья выходит заниженным
                Thread.sleep(SETTLE_PAUSE_MS);
                if (manager.isIdle())
                    break;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return;
        }

        int[] counts = countProjectProblems(project);
        toastWithAction("Проверить", "Завершена проверка всех объектов проекта " + project.getName() //$NON-NLS-1$
            + ". Ошибок: " + counts[0] + ", Предупреждений: " + counts[1], //$NON-NLS-1$ //$NON-NLS-2$
            ProblemViewMarkers::show, "Показать результаты"); //$NON-NLS-1$
    }

    /**
     * Проблемы всего проекта — из того же источника, что и шапка панели.
     * <p>
     * Считать потоком маркеров под отбором проекта нельзя: числа выходят заниженными. У менеджера
     * маркеров есть готовый агрегат {@link IMarkerInfo} с количеством по каждой критичности —
     * именно из него {@code MarkerStats} панели строит «Ошибок / Предупреждений».
     */
    private static int[] countProjectProblems(IProject project)
    {
        IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
        IMarkerInfo info = markerManager != null ? markerManager.getMarkerInfo(project) : null;
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
            toast("Проверить", "Ошибка при запуске проверки: " + t.getClass().getSimpleName() //$NON-NLS-1$
                + (t.getMessage() != null ? " — " + t.getMessage() : "")); //$NON-NLS-1$
        }
    }

    private static void recomputeObjectsBody(IProject project, Collection<? extends EObject> objects)
    {
        if (project == null || objects == null || objects.isEmpty())
        {
            toast("Проверить", "Не выбран объект для проверки."); //$NON-NLS-1$
            return;
        }

        Set<String> checkIds = enabledCheckIds(project);
        if (checkIds.isEmpty())
        {
            toast("Проверить", //$NON-NLS-1$
                "В профиле проекта нет включённых проверок — перепроверять нечего.");
            return;
        }

        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null ? provider.get(project) : null;
        if (manager == null)
        {
            toast("Проверить", "Менеджер проверок недоступен для проекта " + project.getName() + "."); //$NON-NLS-1$
            return;
        }

        if (!manager.isIdle())
        {
            toast("Проверить", //$NON-NLS-1$
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
            toast("Проверить", "Выбранный элемент нельзя точечно перепроверить."); //$NON-NLS-1$
            return;
        }

        String objectsLabel = describeObjects(sources);
        toast("Проверить", //$NON-NLS-1$
            "Запущена проверка объекта " + objectsLabel
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
            toast("Проверить", "Не удалось запланировать перепроверку объекта " + objectsLabel + "."); //$NON-NLS-1$
            return;
        }
        if (targets.isEmpty())
        {
            toast("Проверить", "Не удалось определить объект проверки для " + objectsLabel + "."); //$NON-NLS-1$
            return;
        }
        if (marked[0] == 0)
        {
            toast("Проверить", //$NON-NLS-1$
                "Не удалось пометить проверки для " + objectsLabel + " — перепроверка не запущена.");
            return;
        }
        manager.applyForcedUpdates();
        notifyWhenComplete(manager, project, objectsLabel, sources, targets);
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
        Collection<? extends EObject> objects, List<IBmObject> targets)
    {
        Job waitJob = Job.create("Комфорт: перепроверка конфигурации", monitor -> //$NON-NLS-1$
        {
            monitor.beginTask("Перепроверка конфигурации", org.eclipse.core.runtime.IProgressMonitor.UNKNOWN); //$NON-NLS-1$
            try
            {
                boolean completed = manager.waitAllComputations(WAIT_COMPLETION_TIMEOUT_MS);
                if (completed)
                {
                    int[] counts = countProblems(project, targets, objects);
                    toastWithAction("Проверить", "Завершена проверка объекта " + objectsLabel //$NON-NLS-1$
                        + " с вложенными. Ошибок: " + counts[0] //$NON-NLS-1$
                        + ", Предупреждений: " + counts[1], //$NON-NLS-1$
                        () -> showResults(objects), "Показать результаты"); //$NON-NLS-1$
                }
                else
                {
                    toast("Проверить", "Перепроверка объекта " + objectsLabel //$NON-NLS-1$
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
     * Берутся <b>все</b> маркеры проверенных объектов: {@code getNestedMarkers} отдаёт проблемы
     * вложенных объектов (модуля, форм, команд), {@code getMarkers} — самого объекта. Никаких
     * отборов по идентификаторам: попытка собрать «правильный» набор идентификаторов
     * (top-объекты, подчинённые top-объекты) раз за разом теряла часть проблем.
     *
     * @return массив из двух чисел: ошибки и предупреждения
     */
    private static int[] countProblems(IProject project, List<IBmObject> targets,
        Collection<? extends EObject> sources)
    {
        int[] counts = { 0, 0 };
        IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
        if (markerManager == null)
            return counts;

        LinkedHashSet<Object> objectIds = new LinkedHashSet<>();
        for (IBmObject bmObject : targets)
            objectIds.add(Long.valueOf(bmObject.bmGetId()));
        if (sources != null)
        {
            for (EObject source : sources)
            {
                if (source instanceof IBmObject bm)
                    objectIds.add(Long.valueOf(bm.bmGetId()));
            }
        }

        HashSet<String> seen = new HashSet<>();
        for (Object objectId : objectIds)
        {
            // getNestedMarkers отдаёт и проблемы вложенных объектов — модуля, форм, команд
            for (Marker[] batch : new Marker[][] { markerManager.getNestedMarkers(project, objectId),
                markerManager.getMarkers(project, objectId) })
            {
                if (batch == null)
                    continue;
                for (Marker marker : batch)
                {
                    if (marker == null)
                        continue;
                    String markerId = marker.getMarkerId();
                    if (!seen.add(markerId != null ? markerId //$NON-NLS-1$
                        : "idhash:" + System.identityHashCode(marker))) //$NON-NLS-1$
                        continue;

                    MarkerSeverity severity = marker.getSeverity();
                    if (severity == MarkerSeverity.ERRORS)
                        counts[0]++;
                    else if (severity != null && severity != MarkerSeverity.NONE)
                        counts[1]++;
                }
            }
        }
        Debug.log("счёт по объекту: объектов=" + objectIds.size() //$NON-NLS-1$
            + ", маркеров=" + seen.size() + ", ошибок=" + counts[0] //$NON-NLS-1$ //$NON-NLS-2$
            + ", предупреждений=" + counts[1] + ", идентификаторы=" + objectIds); //$NON-NLS-1$ //$NON-NLS-2$
        logRelatedMarkers(markerManager, project, objectIds, sources);
        return counts;
    }

    /**
     * По клику на тост: для одного проверенного объекта — активирует его редактор и открывает
     * панель «Ошибки конфигурации» с отбором «Текущий объект». Для нескольких объектов
     * «текущий объект» не имеет смысла — просто показывает панель как есть.
     */
    private static void showResults(Collection<? extends EObject> objects)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return;

        if (objects != null && objects.size() == 1)
        {
            EObject object = objects.iterator().next();
            try
            {
                new OpenHelper(page).openEditor(object);
            }
            catch (RuntimeException ignored)
            {
            }
            ProblemViewMarkers.showForCurrentObject();
            return;
        }

        ProblemViewMarkers.show();
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

    private static void toast(String title, String message)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, 5_000));
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
