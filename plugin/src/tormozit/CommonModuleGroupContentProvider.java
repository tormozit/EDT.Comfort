package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonContentProvider;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Добавляет виртуальные узлы-группы общих модулей как ДОПОЛНИТЕЛЬНЫЕ дети папки
 * «Общие модули» — штатное содержимое EDT не подменяется, а дополняется (Common Navigator
 * Framework, additive content extension). Группировка считается из {@link CommonModuleGrouping}
 * и кэшируется на время пересборки дерева (см. {@link GroupingSnapshot}) — на диске по-прежнему
 * ничего не хранится.
 *
 * <p>Механизм подтверждён рабочим прецедентом (DitriXNew/EDT-MCP, groups/ui) — попытка
 * перехвата через собственный {@code IAdapterFactory} результата не дала: рендер навигатора
 * EDT не читает лейблы через глобальный {@code AdapterManager}, а идёт через контент-сервис
 * CNF (композиция зарегистрированных {@code navigatorContent} расширений).
 */
public final class CommonModuleGroupContentProvider implements ICommonContentProvider
{
    private static final Object[] NO_CHILDREN = new Object[0];

    private Viewer viewer;

    @Override
    public void init(ICommonContentExtensionSite aConfig)
    {
    }

    @Override
    public Object[] getElements(Object inputElement)
    {
        return getChildren(inputElement);
    }

    @Override
    public Object[] getChildren(Object parentElement)
    {
        if (!isGroupingActive())
            return NO_CHILDREN;

        if (parentElement instanceof CommonModuleGroupNode group)
            return group.getMembers().toArray();

        if (isCommonModulesFolder(parentElement))
            return snapshotFor(parentElement).nodes.toArray();

        return NO_CHILDREN;
    }

    /**
     * Для сгруппированного модуля возвращает узел его группы (не {@code null}) — иначе JFace
     * при восстановлении выделения/раскрытия после перестроения дерева строит путь «Папка→Модуль»,
     * который не совпадает с реальным «Папка→Группа→Модуль», и выделение/раскрытие теряется
     * (issue #117: пропадает активная строка модуля после снятия текстового фильтра).
     */
    @Override
    public Object getParent(Object element)
    {
        if (element instanceof CommonModuleGroupNode group)
            return group.getParent();

        if (element instanceof CommonModule module && isGroupingActive())
            return groupNodeFor(module);

        return null;
    }

    /** Реальный (штатный EDT) родитель элемента — через его собственный {@link IWorkbenchAdapter}. */
    private static Object realParent(Object element)
    {
        Object adapterObj = Platform.getAdapterManager().getAdapter(element, IWorkbenchAdapter.class);
        return adapterObj instanceof IWorkbenchAdapter wa ? wa.getParent(element) : null;
    }

    @Override
    public boolean hasChildren(Object element)
    {
        if (!isGroupingActive())
            return false;
        if (element instanceof CommonModuleGroupNode group)
            return !group.getMembers().isEmpty();
        if (isCommonModulesFolder(element))
            return !snapshotFor(element).nodes.isEmpty();
        return false;
    }

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
    {
        this.viewer = viewer;
    }

    /**
     * Выключено флажком, либо временно приостановлено — пока активен умный фильтр (иначе поиск
     * не найдёт вложенные модули) или идёт переподключение фильтра по подсистемам после снятия
     * поиска (иначе виден мусорный неотфильтрованный промежуточный результат, issue #117).
     */
    private boolean isGroupingActive()
    {
        if (!ComfortSettings.isGroupCommonModulesEnabled())
            return false;
        Control control = viewer != null ? viewer.getControl() : null;
        if (!(control instanceof Tree tree))
            return true;
        return !NavigatorFilterHook.isSmartFilterPatternActive(tree)
                && !NavigatorFilterHook.isSubsystemsRebindPending(tree);
    }

    @Override
    public void dispose()
    {
    }

    @Override
    public void restoreState(IMemento aMemento)
    {
    }

    @Override
    public void saveState(IMemento aMemento)
    {
    }

    static boolean isCommonModulesFolder(Object element)
    {
        if (element == null)
            return false;
        String className = element.getClass().getName();
        return className.contains("CommonModuleNavigatorAdapter") && className.contains("Folder"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Готовая группировка папки «Общие модули»: узлы-группы + обратный индекс «модуль → его группа».
     *
     * <p>Кэш обязателен, а не оптимизация «на будущее» (issue #285): штатный
     * {@code NavigatorSubsystemsFilter.getProject(Object)} и {@code NavigatorProblemsLabelDecorator}
     * дёргают {@link #getParent(Object)} на КАЖДЫЙ элемент дерева, а без кэша каждый такой вызов
     * заново читал из BM всё содержимое папки и звал {@code getName()} на каждом модуле. На большой
     * конфигурации это давало сотни тысяч BM-обращений на один {@code refresh()} и блокировало UI
     * на минуты. С кэшем группировка строится один раз на пересборку дерева.
     *
     * <p>Устаревание — по паузе без обращений, а не по возрасту: пересборка дерева идёт сплошным
     * потоком вызовов, и «возрастной» TTL истекал бы прямо посреди неё, обнуляя кэш (та же ошибка,
     * что была в {@code CommonModuleGroupedObjectsFilter}). Пауза {@link #IDLE_MS} означает, что
     * пересборка закончилась; {@link #MAX_AGE_MS} — жёсткий предел, чтобы новые/переименованные
     * модули появились и без явного обновления навигатора.
     */
    private static final class GroupingSnapshot
    {
        private static final long IDLE_MS = 500;
        private static final long MAX_AGE_MS = 3000;

        final List<String> suffixes;
        final Map<CommonModule, CommonModuleGroupNode> nodeByModule = new HashMap<>();
        final List<CommonModuleGroupNode> nodes = new ArrayList<>();
        final long builtAt = System.currentTimeMillis();
        volatile long lastAccess = builtAt;

        GroupingSnapshot(Object folder, List<CommonModule> modules, List<String> suffixes)
        {
            this.suffixes = new ArrayList<>(suffixes == null ? List.of() : suffixes);
            Map<String, List<CommonModule>> groups = CommonModuleGrouping.groupBySuffix(modules, this.suffixes);
            for (Map.Entry<String, List<CommonModule>> entry : groups.entrySet())
            {
                CommonModuleGroupNode node = new CommonModuleGroupNode(entry.getKey(), entry.getValue(), folder);
                nodes.add(node);
                for (CommonModule member : entry.getValue())
                    nodeByModule.put(member, node);
            }
            // Модули без «братьев» тоже в индексе — со значением null: «знаем про него, группы нет».
            for (CommonModule module : modules)
                nodeByModule.putIfAbsent(module, null);
        }

        boolean isFresh(List<String> currentSuffixes)
        {
            long now = System.currentTimeMillis();
            return suffixes.equals(currentSuffixes == null ? List.of() : currentSuffixes)
                    && now - lastAccess <= IDLE_MS
                    && now - builtAt <= MAX_AGE_MS;
        }
    }

    private static final Object CACHE_LOCK = new Object();
    private static final Map<Object, GroupingSnapshot> SNAPSHOTS = new HashMap<>();
    private static final Map<CommonModule, GroupingSnapshot> SNAPSHOT_BY_MODULE = new HashMap<>();

    /**
     * Сбрасывает кэш группировки. Вызывать при явном обновлении навигатора (применение настроек) —
     * дешёвая операция без обращений к BM, безопасная и при выключенной группировке.
     */
    static void invalidateGroupingCache()
    {
        synchronized (CACHE_LOCK)
        {
            SNAPSHOTS.clear();
            SNAPSHOT_BY_MODULE.clear();
        }
    }

    /**
     * Узел-группа сгруппированного модуля, либо {@code null} для модуля без «братьев».
     * Обращение к BM — только при промахе кэша.
     */
    static CommonModuleGroupNode groupNodeFor(CommonModule module)
    {
        List<String> suffixes = ComfortSettings.getGroupCommonModulesSuffixes();
        synchronized (CACHE_LOCK)
        {
            GroupingSnapshot snapshot = SNAPSHOT_BY_MODULE.get(module);
            if (snapshot != null && snapshot.nodeByModule.containsKey(module) && snapshot.isFresh(suffixes))
            {
                snapshot.lastAccess = System.currentTimeMillis();
                return snapshot.nodeByModule.get(module);
            }
        }

        Object folder = realParent(module);
        if (!isCommonModulesFolder(folder))
            return null;
        return snapshotFor(folder).nodeByModule.get(module);
    }

    /**
     * Снимок группировки папки. Построение (чтение из BM) — вне блокировки: {@link #getParent(Object)}
     * зовут и фоновые потоки (декоратор проблем), держать на них общий замок нельзя.
     */
    private static GroupingSnapshot snapshotFor(Object folderElement)
    {
        List<String> suffixes = ComfortSettings.getGroupCommonModulesSuffixes();
        synchronized (CACHE_LOCK)
        {
            GroupingSnapshot cached = SNAPSHOTS.get(folderElement);
            if (cached != null && cached.isFresh(suffixes))
            {
                cached.lastAccess = System.currentTimeMillis();
                return cached;
            }
        }

        GroupingSnapshot built = new GroupingSnapshot(folderElement, realCommonModules(folderElement), suffixes);
        synchronized (CACHE_LOCK)
        {
            GroupingSnapshot stale = SNAPSHOTS.put(folderElement, built);
            if (stale != null)
                SNAPSHOT_BY_MODULE.values().removeIf(snapshot -> snapshot == stale);
            for (CommonModule module : built.nodeByModule.keySet())
                SNAPSHOT_BY_MODULE.put(module, built);
        }
        return built;
    }

    /** Читает реальный (штатный EDT) список общих модулей папки — через её собственный {@link IWorkbenchAdapter}. */
    static List<CommonModule> realCommonModules(Object folderElement)
    {
        List<CommonModule> modules = new ArrayList<>();
        Object adapterObj = Platform.getAdapterManager().getAdapter(folderElement, IWorkbenchAdapter.class);
        if (!(adapterObj instanceof IWorkbenchAdapter wa))
            return modules;
        Object[] children = wa.getChildren(folderElement);
        if (children == null)
            return modules;
        for (Object child : children)
            if (child instanceof CommonModule cm)
                modules.add(cm);
        return modules;
    }
}
