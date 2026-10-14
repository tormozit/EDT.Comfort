package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.INavigatorContentExtension;
import org.eclipse.ui.navigator.INavigatorContentService;
import org.eclipse.ui.navigator.IPipelinedTreeContentProvider2;
import org.eclipse.ui.navigator.PipelinedShapeModification;
import org.eclipse.ui.navigator.PipelinedViewerUpdate;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateAware;
import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;

/**
 * Добавляет виртуальные узлы-группы общих модулей как ДОПОЛНИТЕЛЬНЫЕ дети папки
 * «Общие модули». Через pipeline CNF участники групп убираются из штатного списка детей:
 * это работает и в диалоге «Выбор объектов», где commonFilter навигатора не вызывается.
 * Группировка считается из {@link CommonModuleGrouping}
 * и кэшируется на время пересборки дерева (см. {@link GroupingSnapshot}) — на диске по-прежнему
 * ничего не хранится.
 *
 * <p>Механизм подтверждён рабочим прецедентом (DitriXNew/EDT-MCP, groups/ui) — попытка
 * перехвата через собственный {@code IAdapterFactory} результата не дала: рендер навигатора
 * EDT не читает лейблы через глобальный {@code AdapterManager}, а идёт через контент-сервис
 * CNF (композиция зарегистрированных {@code navigatorContent} расширений).
 */
public final class CommonModuleGroupContentProvider implements IPipelinedTreeContentProvider2
{
    private static final Object[] NO_CHILDREN = new Object[0];

    private Viewer viewer;
    private INavigatorContentService contentService;
    private INavigatorContentProviderStateAware edtContentProvider;

    @Override
    public void init(ICommonContentExtensionSite aConfig)
    {
        contentService = aConfig.getService();
    }

    @Override
    public Object[] getElements(Object inputElement)
    {
        return getChildren(inputElement);
    }

    @Override
    public Object[] getChildren(Object parentElement)
    {
        boolean groupingActive = isGroupingActive();
        if (!groupingActive)
            return NO_CHILDREN;

        if (parentElement instanceof CommonModuleGroupNode group)
        {
            return group.getMembers().toArray();
        }

        if (isCommonModulesFolder(parentElement))
        {
            CommonModuleGroupSorter.installOn(viewer);
            return snapshotFor(parentElement).nodes.toArray();
        }

        return NO_CHILDREN;
    }

    /** Меняет состав детей до преобразования объектов в строки дерева AEF (issue #766). */
    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void getPipelinedChildren(Object parent, Set currentChildren)
    {
        if (!isGroupingActive() || !isCommonModulesFolder(parent))
            return;

        CommonModuleGroupSorter.installOn(viewer);
        for (CommonModuleGroupNode group : snapshotFor(parent).nodes)
        {
            // Штатный провайдер диалога уже ограничил состав доступными объектами.
            // Общий кэш мог прийти из навигатора: не возвращать отсечённые EDT модули.
            List<CommonModule> members = new ArrayList<>();
            for (CommonModule member : group.getMembers())
                if (currentChildren.contains(member))
                    members.add(member);
            if (members.isEmpty())
                continue;
            currentChildren.removeAll(members);
            currentChildren.add(members.size() == group.getMembers().size() ? group
                    : new CommonModuleGroupNode(group.getBaseName(), members, parent, group.getSortIndex()));
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void getPipelinedElements(Object input, Set currentElements)
    {
        getPipelinedChildren(input, currentElements);
    }

    @Override
    public Object getPipelinedParent(Object element, Object suggestedParent)
    {
        Object parent = getParent(element);
        // CNF передаёт сюда null даже при найденном штатном родителе. Возврат null
        // в Eclipse 3.12.200 оставляет вместо родителя Object[]: фильтр подсистем
        // не находит проект и пропускает модуль без проверки. Для модуля без группы
        // (и при выключенной группировке) возвращаем его штатную папку явно.
        if (parent == null)
            parent = suggestedParent != null ? suggestedParent : realParent(element);
        return parent;
    }

    @Override
    public boolean hasPipelinedChildren(Object element, boolean currentHasChildren)
    {
        return element instanceof CommonModuleGroupNode ? hasChildren(element) : currentHasChildren;
    }

    @Override
    public PipelinedShapeModification interceptAdd(PipelinedShapeModification modification)
    {
        return modification;
    }

    @Override
    public PipelinedShapeModification interceptRemove(PipelinedShapeModification modification)
    {
        return modification;
    }

    @Override
    public boolean interceptRefresh(PipelinedViewerUpdate update)
    {
        return false;
    }

    @Override
    public boolean interceptUpdate(PipelinedViewerUpdate update)
    {
        return false;
    }

    /**
     * Для сгруппированного модуля возвращает узел его группы (не {@code null}) — иначе JFace
     * при восстановлении выделения/раскрытия после перестроения дерева строит путь «Папка→Модуль»,
     * который не совпадает с реальным «Папка→Группа→Модуль», и выделение/раскрытие теряется
     * (issue #117: пропадает активная строка модуля после снятия текстового фильтра).
     * Для остальных общих модулей возвращает штатную папку: после объявления override
     * CNF выбирает этот провайдер вместо EDT и напрямую вызывает getParent(), без pipeline.
     * Возврат null обрывает поиск проекта в NavigatorSubsystemsFilter и отключает отбор модуля.
     */
    @Override
    public Object getParent(Object element)
    {
        Object parent = null;
        if (element instanceof CommonModuleGroupNode group)
            parent = group.getParent();
        else if (element instanceof CommonModule module)
        {
            if (isGroupingActive())
                parent = groupNodeFor(module);
            if (parent == null)
                parent = realParent(module);
        }
        return parent;
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
        CommonModuleGroupSorter.installOn(viewer);
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
            return isObjectPickerContentActive();
        return !NavigatorFilterHook.isSmartFilterPatternActive(tree)
                && !NavigatorFilterHook.isSubsystemsRebindPending(tree);
    }

    /**
     * UiLessTreeViewer используется и диалогом выбора, и деревьями редакторов AEF.
     * NavigatorTreeDialogModel включает штатное состояние с trie допустимых объектов.
     * Панель «Состав» вместо него использует ContainmentTreeContentProvider, чей
     * фиксированный PARENT_TO_CHILDREN_CLASSES_MAP не поддерживает виртуальные группы.
     * Там сохраняем штатные папки: иначе уже переданный по OK модуль не виден (issue #766).
     */
    private boolean isObjectPickerContentActive()
    {
        if (edtContentProvider == null && contentService != null)
        {
            INavigatorContentExtension extension = contentService.getContentExtensionById(
                    "com._1c.g5.v8.dt.navigator.ui.v8model"); //$NON-NLS-1$
            if (extension != null && extension.getContentProvider() instanceof INavigatorContentProviderStateAware stateAware)
                edtContentProvider = stateAware;
        }
        INavigatorContentProviderStateProvider state = edtContentProvider != null
                ? edtContentProvider.getNavigatorContentProviderState() : null;
        return state != null && state.isActive();
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

        final List<String> suffixes1;
        final List<String> suffixes2;
        final Map<CommonModule, CommonModuleGroupNode> nodeByModule = new HashMap<>();
        final Map<CommonModule, Integer> indexByModule = new HashMap<>();
        final List<CommonModuleGroupNode> nodes = new ArrayList<>();
        final long builtAt = System.currentTimeMillis();
        volatile long lastAccess = builtAt;

        GroupingSnapshot(Object folder, List<CommonModule> modules, List<String> suffixes1, List<String> suffixes2)
        {
            this.suffixes1 = new ArrayList<>(suffixes1 == null ? List.of() : suffixes1);
            this.suffixes2 = new ArrayList<>(suffixes2 == null ? List.of() : suffixes2);
            for (int i = 0; i < modules.size(); i++)
                indexByModule.put(modules.get(i), i);
            Map<String, List<CommonModule>> groups =
                    CommonModuleGrouping.groupBySuffix(modules, this.suffixes1, this.suffixes2);
            for (Map.Entry<String, List<CommonModule>> entry : groups.entrySet())
            {
                int topIndex = Integer.MAX_VALUE;
                for (CommonModule member : entry.getValue())
                {
                    Integer idx = indexByModule.get(member);
                    if (idx != null && idx < topIndex)
                        topIndex = idx;
                }
                CommonModuleGroupNode node =
                        new CommonModuleGroupNode(entry.getKey(), entry.getValue(), folder, topIndex);
                nodes.add(node);
                for (CommonModule member : entry.getValue())
                    nodeByModule.put(member, node);
            }
            // Модули без «братьев» тоже в индексе — со значением null: «знаем про него, группы нет».
            for (CommonModule module : modules)
                nodeByModule.putIfAbsent(module, null);
        }

        boolean isFresh(List<String> current1, List<String> current2)
        {
            long now = System.currentTimeMillis();
            return suffixes1.equals(current1 == null ? List.of() : current1)
                    && suffixes2.equals(current2 == null ? List.of() : current2)
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
     * Позиция в исходном порядке папки «Общие модули»: у группы — индекс самого верхнего
     * участника, у модуля — его собственный индекс. Неизвестный элемент — в конец.
     */
    static int sortIndex(Object element)
    {
        if (element instanceof CommonModuleGroupNode group)
            return group.getSortIndex();
        if (!(element instanceof CommonModule module))
            return Integer.MAX_VALUE;
        synchronized (CACHE_LOCK)
        {
            GroupingSnapshot snapshot = SNAPSHOT_BY_MODULE.get(module);
            if (snapshot == null)
                return Integer.MAX_VALUE;
            Integer idx = snapshot.indexByModule.get(module);
            return idx != null ? idx : Integer.MAX_VALUE;
        }
    }

    /**
     * Узел-группа сгруппированного модуля, либо {@code null} для модуля без «братьев».
     * Обращение к BM — только при промахе кэша.
     */
    static CommonModuleGroupNode groupNodeFor(CommonModule module)
    {
        List<String> suffixes1 = ComfortSettings.getGroupCommonModulesSuffixes1();
        List<String> suffixes2 = ComfortSettings.getGroupCommonModulesSuffixes2();
        synchronized (CACHE_LOCK)
        {
            GroupingSnapshot snapshot = SNAPSHOT_BY_MODULE.get(module);
            if (snapshot != null && snapshot.nodeByModule.containsKey(module)
                    && snapshot.isFresh(suffixes1, suffixes2))
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
        List<String> suffixes1 = ComfortSettings.getGroupCommonModulesSuffixes1();
        List<String> suffixes2 = ComfortSettings.getGroupCommonModulesSuffixes2();
        synchronized (CACHE_LOCK)
        {
            GroupingSnapshot cached = SNAPSHOTS.get(folderElement);
            if (cached != null && cached.isFresh(suffixes1, suffixes2))
            {
                cached.lastAccess = System.currentTimeMillis();
                return cached;
            }
        }

        GroupingSnapshot built = new GroupingSnapshot(
                folderElement, realCommonModules(folderElement), suffixes1, suffixes2);
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
