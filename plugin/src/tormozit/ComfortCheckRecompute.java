package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.TreeIterator;
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
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
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
 */
public final class ComfortCheckRecompute
{
    /** Сегмент обычных (NORMAL) модельных проверок. */
    private static final String M_CHECKS_SEGMENT = "M_CHECKS_SEGMENT"; //$NON-NLS-1$

    /** Сегмент сложных (COMPLEX) модельных проверок. */
    private static final String CM_CHECKS_SEGMENT = "CM_CHECKS_SEGMENT"; //$NON-NLS-1$

    /** Сколько ждать завершения перепроверки в фоне, прежде чем сообщить о таймауте. */
    private static final long WAIT_COMPLETION_TIMEOUT_MS = 60_000L;

    private ComfortCheckRecompute() {}

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
                    int errorCount = countErrors(project, targets, objects);
                    toastWithAction("Проверить", "Завершена проверка объекта " + objectsLabel //$NON-NLS-1$
                        + " с вложенными. Обнаружено " + errorCount + " ошибок.", //$NON-NLS-1$ //$NON-NLS-2$
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
     * Считает маркеры уровня «ошибка» для проверенных объектов, включая вложенные.
     * <p>
     * Штатные и наши проверки пишут {@link MarkerSeverity#MAJOR}/{@link MarkerSeverity#CRITICAL}/
     * {@link MarkerSeverity#BLOCKER} (см. {@code IssueSeverity}), а не {@link MarkerSeverity#ERRORS}.
     */
    private static int countErrors(IProject project, List<IBmObject> targets,
        Collection<? extends EObject> sources)
    {
        IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
        if (markerManager == null)
            return 0;

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

        int errorCount = 0;
        HashSet<String> seen = new HashSet<>();
        for (Object objectId : objectIds)
        {
            for (Marker[] batch : new Marker[][] {
                markerManager.getNestedMarkers(project, objectId),
                markerManager.getMarkers(project, objectId) })
            {
                if (batch == null)
                    continue;
                for (Marker marker : batch)
                {
                    if (marker == null)
                        continue;
                    String markerId = marker.getMarkerId();
                    if (markerId != null)
                    {
                        if (!seen.add(markerId))
                            continue;
                    }
                    else if (!seen.add("idhash:" + System.identityHashCode(marker))) //$NON-NLS-1$
                        continue;

                    if (isErrorLevel(marker.getSeverity()))
                        errorCount++;
                }
            }
        }
        return errorCount;
    }

    private static boolean isErrorLevel(MarkerSeverity severity)
    {
        return severity == MarkerSeverity.ERRORS
            || severity == MarkerSeverity.BLOCKER
            || severity == MarkerSeverity.CRITICAL
            || severity == MarkerSeverity.MAJOR;
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
                String formName = basicForm instanceof MdObject md ? md.getName() : null;
                if (formName == null || formName.isBlank())
                    formName = basicForm.eClass().getName();
                return "Форма." + formName; //$NON-NLS-1$
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
            Object ctx = session.getObjectContext(bmObject, segmentId);
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

    private static void toast(String title, String message)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, 5_000));
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
