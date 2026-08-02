package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

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
 */
public final class ComfortCheckRecompute
{
    private static final String LOG_TOPIC = "comfort-check-recompute"; //$NON-NLS-1$

    /** Сегмент обычных (NORMAL) модельных проверок. */
    private static final String M_CHECKS_SEGMENT = "M_CHECKS_SEGMENT"; //$NON-NLS-1$

    /** Сегмент сложных (COMPLEX) модельных проверок. */
    private static final String CM_CHECKS_SEGMENT = "CM_CHECKS_SEGMENT"; //$NON-NLS-1$

    /** Сколько ждать завершения перепроверки в фоне, прежде чем сообщить о таймауте. */
    private static final long WAIT_COMPLETION_TIMEOUT_MS = 60_000L;

    /** Команда панели «Ошибки конфигурации» для переключения области отбора (radio). */
    private static final String SCOPE_COMMAND_ID = "com._1c.g5.v8.dt.ui.command.filtersScopeRadio"; //$NON-NLS-1$
    private static final String SCOPE_PARAMETER_ID = "org.eclipse.ui.commands.radioStateParameter"; //$NON-NLS-1$
    private static final String SCOPE_CURRENT_OBJECT = "CURRENT_OBJECT"; //$NON-NLS-1$

    private ComfortCheckRecompute() {}

    /**
     * Перезапускает все включённые в профиле проверки для указанных объектов проекта.
     *
     * @param project проект объектов; {@code null} — ничего не делать
     * @param objects объекты (обычно top-объекты метаданных); не-BM объекты пропускаются
     */
    public static void recomputeObjects(IProject project, Collection<? extends EObject> objects)
    {
        if (project == null || objects == null || objects.isEmpty())
            return;

        Set<String> checkIds = enabledCheckIds(project);
        if (checkIds.isEmpty())
        {
            Global.tempLog(LOG_TOPIC, "нет включённых проверок для проекта " + project.getName()); //$NON-NLS-1$
            return;
        }

        IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager manager = provider != null ? provider.get(project) : null;
        if (manager == null)
        {
            Global.tempLog(LOG_TOPIC, "IDerivedDataManager недоступен для " + project.getName()); //$NON-NLS-1$
            return;
        }

        if (!manager.isIdle())
        {
            Global.tempLog(LOG_TOPIC, "менеджер производных данных ещё занят предыдущим циклом" //$NON-NLS-1$
                + " (isIdle=false) — пометка пропущена, иначе она молча не учитывается движком"); //$NON-NLS-1$
            toast("Проверить", //$NON-NLS-1$
                "Предыдущая перепроверка ещё выполняется. Повторите через несколько секунд.");
            return;
        }

        String threadName = Thread.currentThread().getName();
        Global.tempLog(LOG_TOPIC, "перепроверка: проект=" + project.getName() //$NON-NLS-1$
            + ", объектов=" + objects.size() + ", проверок=" + checkIds.size() //$NON-NLS-1$ //$NON-NLS-2$
            + ", поток=" + threadName + ", isIdle=" + manager.isIdle() //$NON-NLS-1$ //$NON-NLS-2$
            + ", status=" + manager.getDerivedDataStatus()); //$NON-NLS-1$

        List<IBmObject> targets = new ArrayList<>();
        for (EObject object : objects)
        {
            EObject target = toCheckTarget(object);
            if (!(target instanceof IBmObject bmObject))
            {
                Global.tempLog(LOG_TOPIC, "пропущен не-BM объект: " + target); //$NON-NLS-1$
                continue;
            }
            Global.tempLog(LOG_TOPIC, "цель: id=" + bmObject.bmGetId() + ", fqn=" + bmObject.bmGetFqn() //$NON-NLS-1$ //$NON-NLS-2$
                + ", isComputed(до)=" + manager.isComputed(bmObject.bmGetId(), checkIds)); //$NON-NLS-1$
            targets.add(bmObject);
        }

        if (targets.isEmpty())
        {
            Global.tempLog(LOG_TOPIC, "нет ни одного BM-объекта среди переданных — перепроверять нечего"); //$NON-NLS-1$
            return;
        }

        String objectsLabel = describeObjects(objects);
        toast("Проверить", //$NON-NLS-1$
            "Запущена проверка объекта " + objectsLabel + ". По окончании будет показано уведомление.");

        int[] marked = { 0 };
        boolean scheduled = manager.updateDerivedData(new IDerivedDataUpdate()
        {
            @Override
            public void update(IContextCollectingSession session, IBmModel model)
            {
                Global.tempLog(LOG_TOPIC, "update(): поток=" + Thread.currentThread().getName()); //$NON-NLS-1$
                for (IBmObject bmObject : targets)
                {
                    boolean okM = markSegmentFullRebuild(session, bmObject, M_CHECKS_SEGMENT, checkIds);
                    boolean okCm = markSegmentFullRebuild(session, bmObject, CM_CHECKS_SEGMENT, checkIds);
                    if (okM || okCm)
                        marked[0]++;
                }
            }
        }, 0L, "comfort-recompute-checks"); //$NON-NLS-1$
        Global.tempLog(LOG_TOPIC, "updateDerivedData вернул=" + scheduled + ", помечено объектов=" + marked[0]); //$NON-NLS-1$ //$NON-NLS-2$

        if (marked[0] == 0)
        {
            Global.tempLog(LOG_TOPIC, "ни один объект не помечен — перепроверять нечего"); //$NON-NLS-1$
            return;
        }
        try
        {
            manager.applyForcedUpdates();
            Global.tempLog(LOG_TOPIC, "перепроверка запланирована, помечено объектов: " + marked[0] //$NON-NLS-1$
                + ", isIdle(после)=" + manager.isIdle() //$NON-NLS-1$
                + ", status(после)=" + manager.getDerivedDataStatus()); //$NON-NLS-1$
            for (IBmObject bmObject : targets)
            {
                Global.tempLog(LOG_TOPIC, "цель: id=" + bmObject.bmGetId() //$NON-NLS-1$
                    + ", isComputed(сразу после applyForcedUpdates)=" //$NON-NLS-1$
                    + manager.isComputed(bmObject.bmGetId(), checkIds));
            }
            notifyWhenComplete(manager, project, objectsLabel, objects, targets);
        }
        catch (RuntimeException e)
        {
            Global.tempLogException(LOG_TOPIC, "applyForcedUpdates не удался", e); //$NON-NLS-1$
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
                Global.tempLog(LOG_TOPIC, "ожидание завершения: completed=" + completed); //$NON-NLS-1$
                if (completed)
                {
                    int errorCount = countErrors(project, targets);
                    toastWithAction("Проверить", "Завершена проверка объекта " + objectsLabel //$NON-NLS-1$
                        + ". Обнаружено " + errorCount + " ошибок.", //$NON-NLS-1$ //$NON-NLS-2$
                        () -> showResults(objects), "Показать результаты"); //$NON-NLS-1$
                }
                else
                {
                    toast("Проверить", "Перепроверка объекта " + objectsLabel + " не завершилась за " //$NON-NLS-1$
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
     * Считает маркеры уровня {@link MarkerSeverity#ERRORS} для проверенных объектов, включая
     * вложенные (формы, модули и т.п.) — через {@link IMarkerManager#getNestedMarkers}.
     */
    private static int countErrors(IProject project, List<IBmObject> targets)
    {
        IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
        if (markerManager == null)
        {
            Global.tempLog(LOG_TOPIC, "IMarkerManager недоступен — подсчёт ошибок пропущен"); //$NON-NLS-1$
            return 0;
        }
        int count = 0;
        for (IBmObject bmObject : targets)
        {
            Marker[] markers = markerManager.getNestedMarkers(project, bmObject.bmGetId());
            if (markers == null)
                continue;
            for (Marker marker : markers)
            {
                if (marker.getSeverity() == MarkerSeverity.ERRORS)
                    count++;
            }
        }
        return count;
    }

    /**
     * По клику на тост: для одного проверенного объекта — активирует его редактор и переключает
     * область отбора панели «Ошибки конфигурации» на «Текущий объект» (штатная команда
     * {@value #SCOPE_COMMAND_ID}, см. {@code plugin.xml} бандла {@code com._1c.g5.v8.dt.ui.validation}),
     * чтобы в панели остались только его ошибки; затем выводит панель на передний план.
     * Для нескольких объектов «текущий объект» не имеет смысла — просто показывает панель как есть.
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
            catch (RuntimeException e)
            {
                Global.tempLogException(LOG_TOPIC, "не удалось открыть редактор объекта " + object, e); //$NON-NLS-1$
            }
            // Переключение области — после открытия редактора: панель определяет «текущий объект»
            // по активной части (редактору), а showView ниже сразу же увёл бы фокус на панель.
            switchScopeToCurrentObject();
        }

        try
        {
            page.showView(ProblemViewMarkers.PROBLEM_VIEW_ID);
        }
        catch (PartInitException e)
        {
            Global.tempLogException(LOG_TOPIC, "не удалось открыть панель «Ошибки конфигурации»", e); //$NON-NLS-1$
        }
    }

    private static void switchScopeToCurrentObject()
    {
        try
        {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            IHandlerService handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
            if (commandService == null || handlerService == null)
                return;
            Command command = commandService.getCommand(SCOPE_COMMAND_ID);
            Map<String, Object> parameters = new HashMap<>();
            parameters.put(SCOPE_PARAMETER_ID, SCOPE_CURRENT_OBJECT);
            ParameterizedCommand parameterizedCommand = ParameterizedCommand.generateCommand(command, parameters);
            handlerService.executeCommand(parameterizedCommand, null);
        }
        catch (Exception e)
        {
            Global.tempLogException(LOG_TOPIC, "не удалось переключить область отбора на «Текущий объект»", e); //$NON-NLS-1$
        }
    }

    /**
     * Человекочитаемое имя объектов для тостов запуска/завершения перепроверки, например
     * {@code ОбщаяФорма.Форма1} или {@code 3 объекта: ..., ..., ...} для нескольких.
     */
    private static String describeObjects(Collection<? extends EObject> objects)
    {
        List<String> names = new ArrayList<>();
        for (EObject object : objects)
        {
            String name = null;
            if (object instanceof IBmObject bmObject)
            {
                name = MdTypeMapping.bmFqnToRuFullName(bmObject.bmGetFqn());
                if (name == null)
                    name = bmObject.bmGetFqn();
            }
            if (name == null)
                name = object.eClass().getName();
            names.add(name);
        }
        if (names.isEmpty())
            return "?"; //$NON-NLS-1$
        if (names.size() == 1)
            return names.get(0);
        return names.size() + " объекта: " + String.join(", ", names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Приводит объект к тому, на который навешаны проверки.
     * <p>
     * Из навигатора и из области отбора панели приходит объект метаданных формы
     * ({@code BasicForm}: {@code DocumentFormImpl}, {@code CommonFormImpl} и т.п.), а проверки форм
     * зарегистрированы на {@code form.model.Form} — вложенный объект, который и привязан к
     * BM-транзакции. Без этого перехода объект молча отбраковывается.
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
            {
                Global.tempLog(LOG_TOPIC, "контекст null для сегмента " + segmentId //$NON-NLS-1$
                    + ", объект=" + bmObject); //$NON-NLS-1$
                return false;
            }
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
            String stateAfter = ctx instanceof IObjectDerivedDataContext typed
                ? "fullRebuild=" + typed.isFullRebuild() + ", inactive=" + typed.isInactive() //$NON-NLS-1$ //$NON-NLS-2$
                    + ", version=" + typed.getVersion() //$NON-NLS-1$
                : "(не IObjectDerivedDataContext, класс=" + ctx.getClass().getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLog(LOG_TOPIC, "контекст готов: сегмент=" + segmentId //$NON-NLS-1$
                + ", объект=" + bmObject + ", " + stateAfter); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        catch (Exception e)
        {
            Global.tempLogException(LOG_TOPIC, "пометка сегмента " + segmentId + " не удалась", e); //$NON-NLS-1$ //$NON-NLS-2$
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
