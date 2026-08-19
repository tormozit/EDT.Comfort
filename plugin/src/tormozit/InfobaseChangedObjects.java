package tormozit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationListener;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationSettings;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Объекты проекта, ожидающие синхронизации с информационной базой.
 *
 * <h3>Откуда берутся данные</h3>
 * <p>
 * До EDT 2023 у стратегии синхронизации был метод {@code getChangedObjects(InfobaseReference)},
 * им пользовался плагин {@code org.mard.dt.infobase.ui}. В EDT 21 его нет: изменения считаются
 * внутри «потока синхронизации» ({@code sync.v2}) как разница сигнатур ресурсов проекта
 * ({@code IResourceStoreManager.getAllSignatures}) с сигнатурами, запомненными при последней
 * синхронизации с базой; пути изменённых ресурсов затем сворачиваются в объекты BM. Пакеты со
 * стратегиями и внутренностями {@code sync.v2} из бандла не экспортируются, поэтому единственный
 * доступный путь к тому же результату — публичный API потока:
 * {@link IInfobaseSynchronizationStateManager#startUpdateInfobaseFlow} →
 * {@code collectAddedAndModifiedInEdtObjects()} → {@code cancel()}.
 * </p>
 * <p>
 * Сам расчёт только читает состояние, но открытие потока имеет побочные эффекты: на время вызова
 * взводится признак активного потока и захватывается межпроцессный файловый замок. Поэтому расчёт
 * выполняется лениво, результат кэшируется до явного сброса ({@link #invalidate}), а если поток по
 * базе уже активен (идёт настоящая синхронизация) — расчёт пропускается и отдаётся прошлое
 * значение.
 * </p>
 *
 * @see ObjectSets.SetKind#INFOBASE_CHANGED
 */
public final class InfobaseChangedObjects
{
    /**
     * Результат расчёта с временем годности.
     *
     * <p>Достоверный ответ (сравнение реально выполнено) живёт до явного сброса. Ответ «пока
     * неизвестно» — сервисы EDT ещё не поднялись, идёт синхронизация — живёт {@link #UNKNOWN_TTL_MS},
     * после чего слушатели ({@link #addChangeListener}) получают уведомление и пересчитывают.
     */
    private static final class Entry
    {
        final List<String> refs;
        final long expiresAt;

        Entry(List<String> refs, long expiresAt)
        {
            this.refs = refs;
            this.expiresAt = expiresAt;
        }

        boolean isValid()
        {
            return expiresAt == Long.MAX_VALUE || System.currentTimeMillis() < expiresAt;
        }
    }

    /** Текст, пока число изменений неизвестно. */
    public static final String UNKNOWN_COUNT_TEXT = "?"; //$NON-NLS-1$

    /**
     * Текст числа изменений: «?» пока неизвестно, иначе само число (в том числе 0).
     *
     * @param count число объектов; при {@code unknown} не используется
     * @param unknown {@code true}, если достоверного ответа ещё нет
     */
    public static String displayCountText(int count, boolean unknown)
    {
        return unknown ? UNKNOWN_COUNT_TEXT : Integer.toString(count);
    }

    /** Сколько живёт недостоверный ответ, мс. */
    private static final long UNKNOWN_TTL_MS = 3000;

    /** Пауза между повторами, пока сервисы EDT поднимаются; чуть больше {@link #UNKNOWN_TTL_MS}. */
    private static final int PENDING_RETRY_DELAY_MS = 3500;

    /** Сколько раз повторять расчёт по одной базе (примерно полминуты). */
    private static final int PENDING_RETRY_MAX_ATTEMPTS = 10;

    /** Полные имена объектов МД по (проект, база); отсутствие ключа — ещё не считалось. */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** Слушатели сброса кэша: перерисовать свои представления. */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** Сколько раз подряд по ключу кэша ответ был «пока неизвестно». */
    private static final Map<String, Integer> PENDING_ATTEMPTS = new ConcurrentHashMap<>();

    /** Задержка перед сбросом кэша после правок ресурсов, мс. */
    private static final int INVALIDATE_DELAY_MS = 600;


    private static IResourceChangeListener resourceListener;

    private static IInfobaseAssociationListener associationListener;

    private static boolean invalidatePending;

    private static boolean pendingRetryScheduled;

    private InfobaseChangedObjects()
    {
    }

    /**
     * Подписаться на сброс кэша (состав изменённых объектов мог измениться).
     * Слушатель вызывается в потоке UI — в том числе когда недостоверный ответ
     * («сервис синхронизации ещё не готов») пора пересчитать.
     *
     * @param listener слушатель; повторные вызовы с тем же объектом накапливаются
     */
    public static void addChangeListener(Runnable listener)
    {
        if (listener == null)
            return;
        LISTENERS.add(listener);
        installResourceListener();
        // На earlyStartup сервис связей мог быть ещё не поднят — повторяем при открытии панели.
        installApplicationRemovalListener();
    }

    public static void removeChangeListener(Runnable listener)
    {
        LISTENERS.remove(listener);
    }

    /**
     * Правки ресурсов проекта делают кэш неактуальным. Точный расчёт дорог, поэтому по событию
     * только сбрасываем кэш — пересчёт произойдёт при следующем обращении, то есть когда набор
     * «&lt;Измененные <i>ИмяБазы</i>&gt;» действительно показан в панели «Наборы объектов».
     */
    private static synchronized void installResourceListener()
    {
        if (resourceListener != null)
            return;
        resourceListener = event ->
        {
            if (event.getType() == IResourceChangeEvent.POST_CHANGE && event.getDelta() != null)
                scheduleInvalidate();
        };
        ResourcesPlugin.getWorkspace().addResourceChangeListener(
            resourceListener, IResourceChangeEvent.POST_CHANGE);
    }

    /**
     * Отложенный сброс кэша с подавлением дребезга.
     *
     * <p><b>Про потоки.</b> События об изменении ресурсов приходят в рабочем потоке workspace, а
     * {@link Display#timerExec} допустим только из UI-потока — прямой вызов падает с
     * {@code Invalid thread access}. Раньше флаг {@code invalidatePending} выставлялся до этого
     * вызова, поэтому после первого же события он навсегда оставался {@code true} и кэш переставал
     * сбрасываться совсем. Поэтому: сначала переход в UI-поток через {@code asyncExec}, и флаг
     * снимается в любом случае, включая ошибку.
     */
    private static void scheduleInvalidate()
    {
        if (invalidatePending)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        invalidatePending = true;
        try
        {
            display.asyncExec(() -> startInvalidateTimer(display));
        }
        catch (Exception e)
        {
            invalidatePending = false;
            ObjectSetsDebug.problem("InfobaseChangedObjects.scheduleInvalidate: " + e); //$NON-NLS-1$
        }
    }

    private static void startInvalidateTimer(Display display)
    {
        if (display.isDisposed())
        {
            invalidatePending = false;
            return;
        }
        display.timerExec(INVALIDATE_DELAY_MS, () ->
        {
            invalidatePending = false;
            invalidate(null);
            notifyListeners();
        });
    }

    private static void notifyListeners()
    {
        for (Runnable listener : LISTENERS)
        {
            try
            {
                listener.run();
            }
            catch (Exception e)
            {
                ObjectSetsDebug.problem("InfobaseChangedObjects.listener: " + e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Пока сервисы EDT не готовы, ответ «пока неизвестно». Через паузу уведомляем слушателей —
     * они перерисуют свои представления, и расчёт пойдёт ещё раз. Без этого «—» / пустой набор
     * застревали бы до первой правки ресурсов.
     */
    private static void rememberUnknown(String key)
    {
        int attempt = PENDING_ATTEMPTS.merge(key, 1, Integer::sum);
        CACHE.put(key, new Entry(List.of(), System.currentTimeMillis() + UNKNOWN_TTL_MS));
        if (attempt <= PENDING_RETRY_MAX_ATTEMPTS)
            schedulePendingRetry();
    }

    private static void schedulePendingRetry()
    {
        if (pendingRetryScheduled)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        pendingRetryScheduled = true;
        display.timerExec(PENDING_RETRY_DELAY_MS, () ->
        {
            pendingRetryScheduled = false;
            if (display.isDisposed())
                return;
            notifyListeners();
        });
    }

    /**
     * Полные имена изменённых объектов МД, ожидающих синхронизации с базой.
     *
     * @param projectName имя проекта
     * @param infobaseUuid UUID информационной базы
     * @return список полных имён (может быть пуст), не {@code null}
     */
    public static List<String> changedRefs(String projectName, String infobaseUuid)
    {
        if (projectName == null || projectName.isBlank() || infobaseUuid == null || infobaseUuid.isBlank())
            return List.of();
        String key = cacheKey(projectName, infobaseUuid);
        Entry cached = CACHE.get(key);
        if (cached != null && cached.isValid())
            return cached.refs;
        List<String> computed;
        try
        {
            computed = compute(projectName, infobaseUuid);
        }
        catch (Throwable e)
        {
            // Ни одна поломка здесь не должна ломать панель «Приложения» или наборы объектов.
            ObjectSetsDebug.problem("InfobaseChangedObjects.changedRefs: " + e); //$NON-NLS-1$
            computed = null;
        }
        if (computed == null)
        {
            // Достоверного ответа нет — запомнить ненадолго, чтобы не дёргать API на каждой
            // отрисовке ячейки; слушатели получат повтор после {@link #PENDING_RETRY_DELAY_MS}.
            rememberUnknown(key);
            return List.of();
        }
        PENDING_ATTEMPTS.remove(key);
        CACHE.put(key, new Entry(computed, Long.MAX_VALUE));
        return computed;
    }

    /**
     * Число изменённых объектов, ожидающих синхронизации с базой.
     *
     * @param project проект
     * @param infobase информационная база
     * @return число объектов, {@code 0} если проект/база не заданы или синхронизация не настроена
     */
    public static int changedCount(IProject project, InfobaseReference infobase)
    {
        if (project == null || infobase == null || infobase.getUuid() == null)
            return 0;
        return changedRefs(project.getName(), infobase.getUuid().toString()).size();
    }

    /**
     * Создать (или обновить) набор «&lt;Измененные <i>ИмяБазы</i>&gt;» и активировать его строку
     * в панели «Наборы объектов».
     *
     * <p>Общая точка для команды «Показать изменения» панели «Приложения» и для клика по числу
     * в колонке «Изменений» мастера обновления конфигурации. Состав набора вычисляется при показе,
     * здесь только регистрируется сам набор.
     *
     * @param project проект
     * @param infobase информационная база
     */
    public static void showChangedSet(IProject project, InfobaseReference infobase)
    {
        if (project == null || infobase == null || infobase.getUuid() == null)
            return;
        ObjectSets.SetDef set = ObjectSets.getInstance().ensureInfobaseChangedSet(
            project.getName(), infobase.getUuid().toString(), infobase.getName());
        if (set == null)
            return;
        ObjectSetsView.revealSet(set.id);
    }

    /** Элементы набора «<Измененные <i>ИмяБазы</i>>» по его определению. */
    static List<ObjectSets.Item> changedItems(ObjectSets.SetDef set)
    {
        String infobaseUuid = ObjectSets.infobaseUuidOf(set);
        if (infobaseUuid == null)
            return List.of();
        List<String> refs = changedRefs(set.projectName, infobaseUuid);
        if (refs.isEmpty())
            return List.of();
        List<ObjectSets.Item> items = new ArrayList<>(refs.size());
        for (String ref : refs)
            items.add(new ObjectSets.Item(ref, ref, ref, lastSegment(ref)));
        items.sort(ObjectSets.ItemSort.COMPARATOR);
        return items;
    }

    /**
     * Последний ответ — «пока неизвестно» (сервисы EDT ещё не поднялись или идёт синхронизация),
     * то есть расчёт стоит повторить.
     *
     * <p>Отличить это от достоверного «изменений нет» снаружи иначе нельзя: оба случая дают пустой
     * список. Без такой проверки набор, выбранный в панели сразу при старте EDT, показывался бы
     * пустым до первой правки ресурсов.
     *
     * @param set набор вида {@link ObjectSets.SetKind#INFOBASE_CHANGED}
     * @return {@code true}, если ответ недостоверный
     */
    public static boolean isResultPending(ObjectSets.SetDef set)
    {
        String infobaseUuid = ObjectSets.infobaseUuidOf(set);
        if (infobaseUuid == null || set.projectName == null || set.projectName.isBlank())
            return false;
        return isResultPending(set.projectName, infobaseUuid);
    }

    /**
     * @param project проект
     * @param infobase информационная база
     * @return {@code true}, если последний расчёт недостоверный и его стоит повторить
     * @see #isResultPending(ObjectSets.SetDef)
     */
    public static boolean isResultPending(IProject project, InfobaseReference infobase)
    {
        if (project == null || infobase == null || infobase.getUuid() == null)
            return false;
        return isResultPending(project.getName(), infobase.getUuid().toString());
    }

    private static boolean isResultPending(String projectName, String infobaseUuid)
    {
        Entry entry = CACHE.get(cacheKey(projectName, infobaseUuid));
        return entry == null || entry.expiresAt != Long.MAX_VALUE;
    }

    /** Сбросить кэш по всем базам проекта (изменились ресурсы или прошла синхронизация). */
    public static void invalidate(String projectName)
    {
        if (projectName == null || projectName.isBlank())
        {
            CACHE.clear();
            PENDING_ATTEMPTS.clear();
            return;
        }
        String prefix = projectName + "\n"; //$NON-NLS-1$
        CACHE.keySet().removeIf(key -> key.startsWith(prefix));
        PENDING_ATTEMPTS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Пометить объекты как ожидающие синхронизации с базой: вычеркнуть их файлы из
     * запомненных сигнатур последней синхронизации. Файлы проекта и git не меняются.
     *
     * @param projectName имя проекта
     * @param infobaseUuid UUID информационной базы
     * @param fullNames полные имена объектов МД
     * @return сколько объектов помечено, сколько уже были в составе, либо ошибка
     */
    static MarkResult markChanged(String projectName, String infobaseUuid, Collection<String> fullNames)
    {
        if (projectName == null || projectName.isBlank()
            || infobaseUuid == null || infobaseUuid.isBlank()
            || fullNames == null)
        {
            return MarkResult.fail("Не заданы проект или информационная база"); //$NON-NLS-1$
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String name : fullNames)
        {
            if (name != null && !name.isBlank())
                requested.add(name);
        }
        if (requested.isEmpty())
            return MarkResult.none();

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.isOpen())
            return MarkResult.fail("Проект не открыт"); //$NON-NLS-1$
        InfobaseReference infobase = findInfobase(project, infobaseUuid);
        if (infobase == null)
            return MarkResult.fail("Информационная база не связана с проектом"); //$NON-NLS-1$

        Set<String> already = new LinkedHashSet<>(changedRefs(projectName, infobaseUuid));
        LinkedHashSet<String> toMark = new LinkedHashSet<>();
        int existing = 0;
        for (String name : requested)
        {
            if (already.contains(name))
                existing++;
            else
                toMark.add(name);
        }
        if (toMark.isEmpty())
            return new MarkResult(0, existing, null);

        IInfobaseSynchronizationStateManager stateManager =
            Global.getOsgiService(IInfobaseSynchronizationStateManager.class);
        if (stateManager == null)
            return MarkResult.fail("Сервис синхронизации EDT недоступен"); //$NON-NLS-1$
        try
        {
            if (stateManager.isFlowActive(infobase))
                return MarkResult.fail("Идёт синхронизация с базой — состав сейчас нельзя изменить"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("InfobaseChangedObjects.markChanged isFlowActive: " + e); //$NON-NLS-1$
            return MarkResult.fail("Не удалось проверить состояние синхронизации"); //$NON-NLS-1$
        }

        int added;
        try
        {
            added = removeStoredSignatures(stateManager, project, infobase, toMark);
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("InfobaseChangedObjects.markChanged: " + e); //$NON-NLS-1$
            return MarkResult.fail("Не удалось пометить объекты к синхронизации"); //$NON-NLS-1$
        }
        if (added < 0)
            return MarkResult.fail("Не удалось пометить объекты к синхронизации"); //$NON-NLS-1$
        if (added == 0)
            return new MarkResult(0, existing, null);

        invalidate(projectName);
        notifyMarkChangedListeners();
        return new MarkResult(added, existing, null);
    }

    /**
     * Вычеркнуть из запомненных сигнатур ключи файлов указанных объектов.
     *
     * @return число объектов, для которых снята хотя бы одна сигнатура; {@code -1} при сбое
     */
    private static int removeStoredSignatures(
        IInfobaseSynchronizationStateManager stateManager,
        IProject project,
        InfobaseReference infobase,
        Set<String> fullNames)
    {
        Object delegate = Global.invoke(stateManager, "getDelegate"); //$NON-NLS-1$
        if (delegate == null)
            return -1;
        Global.invoke(delegate, "updateInternalSyncStateIfNecessary", infobase); //$NON-NLS-1$
        Object states = Global.getField(delegate, "synchronizationStates"); //$NON-NLS-1$
        if (!(states instanceof Map<?, ?> statesMap))
            return -1;
        Object holder = statesMap.get(infobase.getUuid());
        if (holder == null)
            return -1;

        // Нельзя звать captureLock/MemoryMappedLock.unlock: tryLock поднимает поток
        // IB_sync_flow_lock_touch (Unsafe.putLong в mapped-файл), а unlock снимает
        // отображение, пока поток ещё пишет — EXCEPTION_ACCESS_VIOLATION.
        Object monitor = Global.getField(delegate, "lock"); //$NON-NLS-1$
        if (monitor == null)
            monitor = delegate;
        synchronized (monitor)
        {
            Object syncState = syncStateOf(holder, project);
            if (syncState == null)
                return -1;
            Object rawSignatures = Global.invoke(syncState, "getEdtResourceSignatures"); //$NON-NLS-1$
            if (!(rawSignatures instanceof Map<?, ?> signaturesRaw))
                return -1;
            @SuppressWarnings("unchecked")
            Map<String, byte[]> signatures = (Map<String, byte[]>) signaturesRaw;
            Set<String> addedNames = new LinkedHashSet<>();
            List<String> toRemove = new ArrayList<>();
            for (String path : signatures.keySet())
            {
                if (path == null || path.isBlank())
                    continue;
                String matched = matchedName(fullNameFromSignaturePath(path), fullNames);
                if (matched == null)
                    continue;
                toRemove.add(path);
                addedNames.add(matched);
            }
            if (toRemove.isEmpty())
                return 0;
            for (String path : toRemove)
                signatures.remove(path);
            Object store = Global.getField(holder, "synchronizationStore"); //$NON-NLS-1$
            Object rootState = Global.getField(holder, "state"); //$NON-NLS-1$
            if (store == null || rootState == null)
                return -1;
            Global.invoke(store, "updateSyncrhonizationState", rootState); //$NON-NLS-1$
            markProjectDirty(holder, project);
            return addedNames.size();
        }
    }

    private static Object syncStateOf(Object holder, IProject project)
    {
        Object root = Global.getField(holder, "state"); //$NON-NLS-1$
        if (root == null)
            return null;
        try
        {
            if (project.hasNature("com._1c.g5.v8.dt.core.V8ExtensionNature")) //$NON-NLS-1$
            {
                Object extensions = Global.invoke(root, "getExtensionSyncStates"); //$NON-NLS-1$
                if (extensions instanceof Map<?, ?> byName)
                {
                    Object extensionState = byName.get(project.getName());
                    return extensionState != null ? extensionState : root;
                }
            }
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("InfobaseChangedObjects.syncStateOf: " + e); //$NON-NLS-1$
        }
        return root;
    }

    private static void markProjectDirty(Object holder, IProject project)
    {
        Object timestamps = Global.getField(holder, "lastEdtUpdateTimestamps"); //$NON-NLS-1$
        if (!(timestamps instanceof Map<?, ?>))
            return;
        IDtProjectManager projectManager = Global.getOsgiService(IDtProjectManager.class);
        IDtProject dtProject = projectManager != null ? projectManager.getDtProject(project) : null;
        if (dtProject == null)
            return;
        @SuppressWarnings("unchecked")
        Map<Object, Object> map = (Map<Object, Object>) timestamps;
        map.put(dtProject, Long.valueOf(-1L));
    }

    /**
     * Полное имя объекта МД по ключу сигнатуры EDT. Ключи — пути для {@code IProject.getFile}:
     * обычно {@code src/Catalogs/…}, иногда без префикса {@code src/}.
     */
    private static String fullNameFromSignaturePath(String path)
    {
        String fullName = GetRef.pathToFullName(path);
        if (fullName != null && !fullName.isBlank())
            return fullName;
        if (path != null && !path.startsWith("src/") && !path.startsWith("src\\")) //$NON-NLS-1$ //$NON-NLS-2$
            return GetRef.pathToFullName("src/" + path.replace('\\', '/')); //$NON-NLS-1$
        return fullName;
    }

    /**
     * Какой из запрашиваемых объектов покрывает это полное имя файла.
     * Корневой объект МД — только точное совпадение (не все его формы).
     * Вложенный (форма, макет, команда) — он сам и потомки {@code имя.}.
     */
    private static String matchedName(String pathFullName, Set<String> fullNames)
    {
        if (pathFullName == null || pathFullName.isBlank())
            return null;
        for (String name : fullNames)
        {
            if (pathFullName.equals(name))
                return name;
            if (MdTypeMapping.isRootMdObjectRef(name))
                continue;
            if (pathFullName.startsWith(name + ".")) //$NON-NLS-1$
                return name;
        }
        return null;
    }

    private static void notifyMarkChangedListeners()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable run = () ->
        {
            for (Runnable listener : LISTENERS)
            {
                try
                {
                    listener.run();
                }
                catch (Exception e)
                {
                    ObjectSetsDebug.problem("InfobaseChangedObjects.listener: " + e); //$NON-NLS-1$
                }
            }
            if (ObjectSetsNavigatorFilterSupport.isActive())
                ObjectSetsNavigatorFilterSupport.refreshNavigators();
        };
        if (display.getThread() == Thread.currentThread())
            run.run();
        else
            display.asyncExec(run);
    }

    static final class MarkResult
    {
        final int added;
        final int existing;
        final String error;

        MarkResult(int added, int existing, String error)
        {
            this.added = added;
            this.existing = existing;
            this.error = error;
        }

        static MarkResult none()
        {
            return new MarkResult(0, 0, null);
        }

        static MarkResult fail(String error)
        {
            return new MarkResult(0, 0, error);
        }
    }

    /**
     * Следить за удалением приложений: набор «&lt;Измененные <i>ИмяБазы</i>&gt;» существует только
     * пока база связана с проектом. Отдельного события «приложение удалено» у
     * {@code IApplicationManager} нет ({@code ApplicationEventType} знает только смену состояний),
     * зато удаление приложения инфобазы отвязывает базу от проекта — это и ловим.
     */
    public static synchronized void installApplicationRemovalListener()
    {
        try
        {
            doInstallApplicationRemovalListener();
        }
        catch (Throwable e)
        {
            ObjectSetsDebug.problem("InfobaseChangedObjects.installApplicationRemovalListener: " + e); //$NON-NLS-1$
        }
    }

    private static void doInstallApplicationRemovalListener()
    {
        if (associationListener != null)
            return;
        IInfobaseAssociationManager manager = Global.getOsgiService(IInfobaseAssociationManager.class);
        if (manager == null)
            return;
        associationListener = new IInfobaseAssociationListener()
        {
            @Override
            public void infobaseAssociated(
                IProject project, InfobaseReference infobase, InfobaseAssociationSettings settings)
            {
                // Новая база — наборов по ней ещё нет.
            }

            @Override
            public void infobaseDissociated(
                IProject project, InfobaseReference infobase, InfobaseAssociationContext context)
            {
                onInfobaseDissociated(project, infobase);
            }

            @Override
            public void contextChanged(
                IProject project, InfobaseAssociationContext previous, InfobaseAssociationContext current)
            {
                // Контекст связи на состав наборов не влияет.
            }
        };
        manager.addInfobaseAssociationListener(associationListener);
    }

    private static void onInfobaseDissociated(IProject project, InfobaseReference infobase)
    {
        if (project == null || infobase == null || infobase.getUuid() == null)
            return;
        String projectName = project.getName();
        String infobaseUuid = infobase.getUuid().toString();
        String key = cacheKey(projectName, infobaseUuid);
        CACHE.remove(key);
        PENDING_ATTEMPTS.remove(key);
        ObjectSets.getInstance().deleteInfobaseChangedSet(projectName, infobaseUuid);
    }

    /**
     * Убрать наборы по базам, отвязанным от проекта, пока EDT была закрыта (или если событие
     * отвязки не дошло). Если список баз проекта прочитать не удалось — не удаляем ничего:
     * пустой ответ недоступного сервиса не должен сносить рабочие наборы.
     *
     * @param projectName имя проекта
     */
    public static void pruneRemovedApplicationSets(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return;
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.isOpen())
            return;
        installApplicationRemovalListener();
        Set<String> liveUuids = associatedInfobaseUuids(project);
        if (liveUuids == null)
            return;
        ObjectSets.getInstance().pruneInfobaseChangedSets(projectName, liveUuids);
    }

    /** UUID баз, связанных с проектом; {@code null} — список недоступен. */
    private static Set<String> associatedInfobaseUuids(IProject project)
    {
        IInfobaseAssociationManager manager = Global.getOsgiService(IInfobaseAssociationManager.class);
        if (manager == null)
            return null;
        try
        {
            Optional<IInfobaseAssociation> association = manager.getAssociation(project);
            if (association.isEmpty())
                return Set.of();
            Collection<InfobaseReference> infobases = association.get().getInfobases();
            if (infobases == null)
                return Set.of();
            Set<String> uuids = new LinkedHashSet<>();
            for (InfobaseReference infobase : infobases)
            {
                if (infobase.getUuid() != null)
                    uuids.add(infobase.getUuid().toString());
            }
            return uuids;
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("InfobaseChangedObjects.associatedInfobaseUuids: " + e); //$NON-NLS-1$
            return null;
        }
    }

    /** Информационная база проекта по её UUID (среди связанных с проектом). */
    static InfobaseReference findInfobase(IProject project, String infobaseUuid)
    {
        if (project == null || infobaseUuid == null || infobaseUuid.isBlank())
            return null;
        IInfobaseAssociationManager manager = Global.getOsgiService(IInfobaseAssociationManager.class);
        if (manager == null)
            return null;
        try
        {
            Optional<IInfobaseAssociation> association = manager.getAssociation(project);
            if (association.isEmpty())
                return null;
            Collection<InfobaseReference> infobases = association.get().getInfobases();
            if (infobases == null)
                return null;
            UUID uuid = UUID.fromString(infobaseUuid);
            for (InfobaseReference infobase : infobases)
            {
                if (uuid.equals(infobase.getUuid()))
                    return infobase;
            }
            return null;
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("InfobaseChangedObjects.findInfobase: " + e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * @return состав изменений либо {@code null}, если расчёт пропущен и кэшировать результат
     *         нельзя (идёт настоящая синхронизация или сервисы ещё не подняты)
     */
    private static List<String> compute(String projectName, String infobaseUuid)
    {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.isOpen())
            return List.of();
        // Связи проекта с базами могут быть ещё не загружены — это тоже «пока неизвестно».
        InfobaseReference infobase = findInfobase(project, infobaseUuid);
        if (infobase == null)
            return null;
        IInfobaseSynchronizationStateManager stateManager =
            Global.getOsgiService(IInfobaseSynchronizationStateManager.class);
        if (stateManager == null)
            return null;
        try
        {
            // Синхронизация с базой не настраивалась — сравнивать не с чем.
            boolean hasInfo = stateManager.hasSynchronizationInfo(project, infobase);
            boolean flowActive = hasInfo && stateManager.isFlowActive(infobase);
            boolean dirty = hasInfo && !flowActive && stateManager.isProjectDirty(project, infobase);
            // Ещё не поднялся стор синхронизации — это «пока неизвестно», а не «изменений нет».
            if (!hasInfo)
                return null;
            // Идёт настоящая синхронизация: свой поток открывать нельзя.
            if (flowActive)
                return null;
            // Дешёвая проверка по метке времени: ресурсы не менялись с последней синхронизации.
            if (!dirty)
                return List.of();
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("InfobaseChangedObjects.precheck: " + e); //$NON-NLS-1$
            return null;
        }
        IDtProjectManager projectManager = Global.getOsgiService(IDtProjectManager.class);
        IDtProject dtProject = projectManager != null ? projectManager.getDtProject(project) : null;
        if (dtProject == null)
            return null;
        return collectViaFlow(stateManager, dtProject, infobase);
    }

    /**
     * <b>Почему поток используется только через рефлексию.</b> Типы потока несовместимы между
     * сборками EDT, и компиляционная привязка к ним ломалась дважды:
     *
     * <ul>
     *   <li>тип возврата {@code IUpdateInfobaseFlow} наследует {@code ILoadOption} из пакета
     *       {@code …infobases.sync.connections}, который бандл <i>не экспортирует</i> — загрузка
     *       этого типа нашим загрузчиком падала с {@code NoClassDefFoundError};</li>
     *   <li>в EDT 2026.1.2 метод {@code collectAddedAndModifiedInEdtObjects()} не объявлен на
     *       {@code IInfobaseSynchronizationFlow} (в сборках 21.0.0 он там есть) — прямой вызов
     *       давал {@code NoSuchMethodError}.</li>
     * </ul>
     *
     * <p>Поэтому поток держится как {@code Object}, а все обращения к нему идут через
     * {@link Global#invoke}: он ищет метод по классу реализации и его суперклассам, независимо от
     * того, каким интерфейсом метод объявлен в конкретной сборке. Единственная компиляционная
     * опоры на типы EDT здесь нет вообще: имя метода и разбор его результата подбираются в
     * рантайме (см. {@link #COLLECT_METHOD_NAMES} и {@link #eObjectsOf}).
     */
    private static List<String> collectViaFlow(
        IInfobaseSynchronizationStateManager stateManager, IDtProject dtProject, InfobaseReference infobase)
    {
        Object flow = null;
        try
        {
            flow = Global.invoke(stateManager, "startUpdateInfobaseFlow", dtProject, infobase); //$NON-NLS-1$
            if (flow == null)
                return null;
            Object collected = null;
            for (String methodName : COLLECT_METHOD_NAMES)
            {
                collected = callFlow(flow, methodName);
                if (collected != null)
                    break;
            }
            if (collected == null)
            {
                // Метода нет — это «пока неизвестно», а не «изменений нет».
                String detail = "сбор изменений недоступен" + describeFlowType(flow); //$NON-NLS-1$
                ObjectSetsDebug.problem("InfobaseChangedObjects: " + detail); //$NON-NLS-1$
                return null;
            }
            Set<EObject> changed = eObjectsOf(collected);
            if (changed == null)
            {
                String detail = "не удалось разобрать результат " //$NON-NLS-1$
                    + collected.getClass().getName() + describeFlowType(flow);
                ObjectSetsDebug.problem("InfobaseChangedObjects: " + detail); //$NON-NLS-1$
                return null;
            }
            Set<String> refs = new LinkedHashSet<>();
            addRefs(refs, changed);
            return List.copyOf(refs);
        }
        catch (Exception e)
        {
            // Поток не открылся (например, замок занят) — не кэшировать пустой результат.
            ObjectSetsDebug.problem("InfobaseChangedObjects.collectViaFlow: " + e); //$NON-NLS-1$
            return null;
        }
        finally
        {
            cancelQuietly(flow);
        }
    }

    /** Закрыть поток при любом исходе: незакрытый держит межпроцессный замок базы. */
    private static void cancelQuietly(Object flow)
    {
        if (flow == null)
            return;
        try
        {
            if (!Boolean.TRUE.equals(callFlow(flow, "isFinished"))) //$NON-NLS-1$
                callFlow(flow, "cancel"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("InfobaseChangedObjects.cancel: " + e); //$NON-NLS-1$
        }
    }

    private static void addRefs(Set<String> result, Set<EObject> objects)
    {
        if (objects == null)
            return;
        for (EObject object : objects)
        {
            String fullName = resolveFullName(object);
            if (!isBlank(fullName))
                result.add(fullName);
        }
    }

    /**
     * Полное имя объекта МД для объекта, пришедшего из потока синхронизации.
     *
     * <p>{@link GetRef#eObjectToFullName} умеет разрешать имя только через ресурс EMF, а поток
     * отдаёт BSL-модули <b>без ресурса</b> ({@code eResource() == null}) — именно так терялись
     * все изменения в модулях и формах. Поэтому для них идут запасные пути: владелец модуля
     * ({@link Module#getOwner()} — форма или объект МД), затем FQN самого BM-объекта, затем
     * контейнер.
     *
     * <p>Имя возвращается <b>как есть</b>, без сворачивания до владеющего объекта МД: набор должен
     * показывать изменения с детализацией до форм, макетов и команд
     * ({@code Справочник.Валюты.Форма.ФормаСписка}, а не {@code Справочник.Валюты}). Модуль объекта
     * при этом естественно даёт имя самого объекта — владелец такого модуля и есть объект МД.
     * Этим набор по базе отличается от git-набора «&lt;Измененные Git&gt;», который сворачивает
     * изменения до владельца.
     *
     * @param object объект из потока
     * @return полное имя или {@code null}, если разрешить не удалось
     */
    private static String resolveFullName(EObject object)
    {
        if (object == null)
            return null;
        String fullName = GetRef.eObjectToFullName(object);
        if (!isBlank(fullName))
            return fullName;

        if (object instanceof Module)
        {
            EObject owner = ((Module)object).getOwner();
            if (owner != null)
            {
                fullName = GetRef.eObjectToFullName(owner);
                if (isBlank(fullName))
                    fullName = bmFullName(owner);
                if (!isBlank(fullName))
                    return fullName;
            }
        }

        fullName = bmFullName(object);
        if (!isBlank(fullName))
            return fullName;

        EObject container = object.eContainer();
        return container != null ? GetRef.eObjectToFullName(container) : null;
    }

    /** Полное имя по FQN BM-объекта (работает и для объектов без ресурса EMF). */
    private static String bmFullName(EObject object)
    {
        if (!(object instanceof IBmObject))
            return null;
        try
        {
            String fqn = ((IBmObject)object).bmGetFqn();
            return isBlank(fqn) ? null : MdTypeMapping.bmFqnToRuFullName(fqn);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }


    /**
     * Имена метода сбора изменений в разных сборках EDT, от новых к старым.
     *
     * <p>В бандле 23.0.1 (EDT 2026.1) — {@code collectEdtObjectChanges()}, возвращает
     * {@code org.apache.commons.lang3.tuple.Triple<Set<EObject>, Set<EObject>, Set<String>>}.
     * В бандле 21.0.0 — {@code collectAddedAndModifiedInEdtObjects()}, возвращает
     * {@code com._1c.g5.v8.dt.common.Pair<Set<EObject>, Set<EObject>>}.
     */
    private static final String[] COLLECT_METHOD_NAMES =
        { "collectEdtObjectChanges", "collectAddedAndModifiedInEdtObjects" }; //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Достать изменённые объекты из кортежа, который вернул поток.
     *
     * <p>Тип кортежа между сборками EDT разный ({@code Pair} из бандла EDT против {@code Triple}
     * из {@code commons-lang3}, которого у нас в зависимостях нет), а из-за стирания обобщений
     * геттеры объявлены возвращающими {@code Object}. Поэтому опираться на тип нельзя: перебираем
     * все публичные {@code getXxx()} без аргументов и берём те, что вернули множество из
     * {@link EObject}. Множество строк (в {@code Triple} это третий элемент — удалённые ресурсы)
     * отсеивается само по типу элементов.
     *
     * @return изменённые объекты либо {@code null}, если у результата вообще нет таких геттеров
     */
    private static Set<EObject> eObjectsOf(Object tuple)
    {
        if (tuple == null)
            return null;
        Set<EObject> result = new LinkedHashSet<>();
        boolean anyAccessor = false;
        for (Class<?> type : typeHierarchy(tuple.getClass()))
        {
            for (java.lang.reflect.Method method : type.getDeclaredMethods())
            {
                if (method.getParameterCount() != 0
                    || !java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    || !method.getName().startsWith("get")) //$NON-NLS-1$
                {
                    continue;
                }
                Object value;
                try
                {
                    value = method.invoke(tuple);
                }
                catch (Exception e)
                {
                    continue;
                }
                if (!(value instanceof Set))
                    continue;
                anyAccessor = true;
                for (Object each : (Set<?>)value)
                {
                    if (each instanceof EObject eObject)
                        result.add(eObject);
                }
            }
        }
        return anyAccessor ? result : null;
    }

    /**
     * Вызвать метод потока без аргументов, перебирая всю иерархию типов.
     *
     * <p>{@link Global#invoke} здесь не годится: он обходит только класс и его суперклассы, а
     * реализация потока лежит во внутреннем пакете EDT. Публичный метод такого класса нашим
     * загрузчиком не вызывается — {@code setAccessible}/{@code invoke} дают отказ доступа, и
     * {@code Global.invoke} молча возвращает {@code null} (наблюдалось в EDT 2026.1.2:
     * {@code collectAddedAndModifiedInEdtObjects -> null}).
     *
     * <p>Поэтому метод ищется сначала по <b>интерфейсам</b> — они экспортируются и доступны, —
     * и только затем по классам. Какой именно интерфейс объявляет метод, в разных сборках EDT
     * отличается, поэтому перебираются все.
     *
     * @return результат вызова либо {@code null}, если метод не найден или недоступен
     */
    private static Object callFlow(Object flow, String methodName)
    {
        if (flow == null)
            return null;
        for (Class<?> type : typeHierarchy(flow.getClass()))
        {
            java.lang.reflect.Method method;
            try
            {
                method = type.getDeclaredMethod(methodName);
            }
            catch (NoSuchMethodException e)
            {
                continue;
            }
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                continue;
            try
            {
                return method.invoke(flow);
            }
            catch (java.lang.reflect.InvocationTargetException e)
            {
                Throwable cause = e.getCause();
                throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
            }
            catch (Exception e)
            {
                // Этот носитель метода недоступен — пробуем следующий супертип.
                continue;
            }
        }
        return null;
    }

    /** Интерфейсы (сначала) и классы объекта — порядок важен: интерфейсы доступнее. */
    private static List<Class<?>> typeHierarchy(Class<?> type)
    {
        LinkedHashSet<Class<?>> interfaces = new LinkedHashSet<>();
        LinkedHashSet<Class<?>> classes = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
        {
            classes.add(c);
            collectInterfaces(c, interfaces);
        }
        List<Class<?>> result = new ArrayList<>(interfaces.size() + classes.size());
        result.addAll(interfaces);
        result.addAll(classes);
        return result;
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> result)
    {
        for (Class<?> each : type.getInterfaces())
        {
            if (result.add(each))
                collectInterfaces(each, result);
        }
    }

    /**
     * Для журнала: чем оказался поток и какие у него есть методы сбора изменений.
     *
     * <p>Сигнатура {@code collectAddedAndModifiedInEdtObjects} между сборками EDT менялась, поэтому
     * при неудаче в журнал выводится не только тип, но и все подходящие по имени методы с их
     * параметрами — по ним видно, чем именно отличается конкретная сборка.
     */
    private static String describeFlowType(Object flow)
    {
        if (flow == null)
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(" (flow=").append(flow.getClass().getName()); //$NON-NLS-1$
        sb.append(", кандидаты: "); //$NON-NLS-1$
        boolean any = false;
        for (Class<?> type : typeHierarchy(flow.getClass()))
        {
            for (java.lang.reflect.Method method : type.getDeclaredMethods())
            {
                if (!method.getName().toLowerCase(java.util.Locale.ROOT).contains("collect")) //$NON-NLS-1$
                    continue;
                sb.append(any ? "; " : "").append(type.getSimpleName()).append('.') //$NON-NLS-1$ //$NON-NLS-2$
                    .append(method.getName()).append('(');
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++)
                    sb.append(i > 0 ? ", " : "").append(params[i].getSimpleName()); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(") -> ").append(method.getReturnType().getSimpleName()); //$NON-NLS-1$
                any = true;
            }
        }
        if (!any)
            sb.append("нет методов со словом collect"); //$NON-NLS-1$
        return sb.append(')').toString();
    }

    private static String cacheKey(String projectName, String infobaseUuid)
    {
        return projectName + "\n" + infobaseUuid; //$NON-NLS-1$
    }

    private static String lastSegment(String fullName)
    {
        if (fullName == null || fullName.isEmpty())
            return ""; //$NON-NLS-1$
        int dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }
}
