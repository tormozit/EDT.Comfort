package tormozit;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.IDerivedDataUpdate;
import com._1c.g5.v8.derived.context.IContextCollectingSession;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.context.CheckContextCollectingSession;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Точечный перезапуск проверок конфигурации для конкретных объектов, без пересчёта всего проекта.
 * <p>
 * {@code ICheckScheduler.scheduleValidation} для этой задачи не работает (проверено), а
 * {@code IDerivedDataManager.recomputeAll()} пересчитывает проект целиком — десятки секунд на
 * средней конфигурации. Рабочий путь — пометить объекту контекст производных данных сегмента
 * проверок как «полная перепроверка» и продавить отложенные обновления.
 * <p>
 * Пометка делается штатным {@link CheckContextCollectingSession#addFullCheck} из публичного
 * пакета {@code com.e1c.g5.v8.dt.check.context}: он сам выбирает сегмент по сложности проверки
 * ({@code M_CHECKS_SEGMENT} / {@code CM_CHECKS_SEGMENT}), сам поднимается к top-объекту и
 * заполняет фильтр перепроверяемых проверок — то же самое, что делалось рефлексией по
 * {@code ModelCheckObjectContext}, но типизированно.
 */
public final class ComfortCheckRecompute
{
    private static final String LOG_TOPIC = "comfort-check-recompute"; //$NON-NLS-1$

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

        Global.tempLog(LOG_TOPIC, "перепроверка: проект=" + project.getName() //$NON-NLS-1$
            + ", объектов=" + objects.size() + ", проверок=" + checkIds.size()); //$NON-NLS-1$ //$NON-NLS-2$

        int[] marked = { 0 };
        manager.updateDerivedData(new IDerivedDataUpdate()
        {
            @Override
            public void update(IContextCollectingSession session, IBmModel model)
            {
                for (EObject object : objects)
                {
                    EObject target = toCheckTarget(object);
                    if (!(target instanceof IBmObject bmObject) || bmObject.bmGetTransaction() == null)
                    {
                        Global.tempLog(LOG_TOPIC, "пропущен объект вне BM-транзакции: " + target); //$NON-NLS-1$
                        continue;
                    }
                    for (String checkId : checkIds)
                    {
                        // сложность проверки заранее неизвестна — помечаем оба модельных сегмента,
                        // лишняя пометка безвредна: фильтр просто не найдёт такой проверки в сегменте
                        markFullCheck(session, checkId, CheckComplexity.NORMAL, bmObject);
                        markFullCheck(session, checkId, CheckComplexity.COMPLEX, bmObject);
                    }
                    marked[0]++;
                }
            }
        }, 0L, "comfort-recompute-checks"); //$NON-NLS-1$

        if (marked[0] == 0)
        {
            Global.tempLog(LOG_TOPIC, "ни один объект не помечен — перепроверять нечего"); //$NON-NLS-1$
            return;
        }
        manager.applyForcedUpdates();
        Global.tempLog(LOG_TOPIC, "перепроверка запланирована, помечено объектов: " + marked[0]); //$NON-NLS-1$
    }

    /**
     * Приводит объект к тому, на который навешаны проверки.
     * <p>
     * Из навигатора и из области отбора панели приходит объект метаданных формы
     * ({@code BasicForm}: {@code DocumentFormImpl}, {@code CommonFormImpl} и т.п.), а проверки форм
     * зарегистрированы на {@code form.model.Form} — вложенный объект, который и привязан к
     * BM-транзакции. Без этого перехода объект молча отбраковывается как «вне транзакции».
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

    private static void markFullCheck(IContextCollectingSession session, String checkId,
        CheckComplexity complexity, IBmObject bmObject)
    {
        try
        {
            new CheckContextCollectingSession(checkId, complexity, false, session).addFullCheck(bmObject);
        }
        catch (RuntimeException e)
        {
            Global.tempLogException(LOG_TOPIC, "addFullCheck(" + checkId + ") не удался", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
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
