package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Tree;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Прячет из папки «Общие модули» те модули, что попали в динамическую группу
 * ({@link CommonModuleGroupNode}) — они показываются только внутри группы, не дублируются в корне.
 * Внутри самого узла-группы ничего не фильтрует (её дети и так только реальные участники группы).
 */
public final class CommonModuleGroupedObjectsFilter extends ViewerFilter
{
    @Override
    public Object[] filter(Viewer viewer, Object parent, Object[] elements)
    {
        if (!ComfortSettings.isGroupCommonModulesEnabled())
            return elements;
        if (isInsideGroup(parent))
            return elements;
        if (isSmartSearchActive(viewer))
            return elements; // идёт поиск — показываем плоский список, чтобы искомое находилось
        if (isSubsystemsRebindPending(viewer))
            return elements; // фильтр подсистем ещё переподключается — не мигать группами

        Object[] result = hideGroupedModules(elements);
        result = hideEmptyGroups(viewer, result);
        return result;
    }

    /**
     * Принадлежность модуля группе берётся из того же кэша, что и сами узлы-группы
     * ({@link CommonModuleGroupContentProvider#groupNodeFor}), а не пересчитывается заново по
     * переданному набору элементов (issue #285): пересчёт стоил {@code getName()} из BM на каждый
     * модуль при каждом вызове фильтра, а результат к тому же мог разойтись с составом групп, если
     * другой фильтр уже отсеял часть модулей из набора.
     */
    private static Object[] hideGroupedModules(Object[] elements)
    {
        List<Object> result = new ArrayList<>(elements.length);
        for (Object element : elements)
            if (!(element instanceof CommonModule cm) || CommonModuleGroupContentProvider.groupNodeFor(cm) == null)
                result.add(element);
        return result.size() == elements.length ? elements : result.toArray();
    }

    /**
     * Прячет узел-группу целиком, если фильтр по подсистемам ({@link ObjectSetSubsystemsFilterBridge})
     * скрыл абсолютно всех её участников — иначе в дереве остаётся видимая пустая «папка»-группа.
     * У штатных папок EDT в этой ситуации пропадает только стрелка разворачивания (см.
     * {@code ObjectSetSubsystemsFilterBridge.applyGroupExpandIndicator}), но для своих синтетических
     * групп прячем полностью — по явному запросу пользователя (issue #117).
     */
    private static Object[] hideEmptyGroups(Viewer viewer, Object[] elements)
    {
        ObjectSetSubsystemsFilterBridge.CombinedSubsystemsFilter subsystemsFilter = findSubsystemsFilter(viewer);
        if (subsystemsFilter == null)
            return elements;

        List<Object> result = new ArrayList<>(elements.length);
        for (Object element : elements)
        {
            if (element instanceof CommonModuleGroupNode group && isEmptyUnderFilter(viewer, subsystemsFilter, group))
                continue;
            result.add(element);
        }
        return result.size() == elements.length ? elements : result.toArray();
    }

    /**
     * За одну пересборку дерева {@link #filterImpl} прогоняется десятки раз с тем же набором
     * общих модулей (по разу на каждый материализуемый узел) — без кэша это на ~137 группах
     * даёт сотни/тысячи синхронных {@code select()} (BM-based) на UI-потоке и заметное мигание
     * при снятии текстового фильтра (issue #117). Ключ — список участников группы (у {@link List}
     * {@code equals()} по содержимому, а сам список у {@link CommonModuleGrouping} строится в
     * стабильном порядке из тех же EObject, так что кэш реально «бьёт» между проходами).
     * Сброс при смене экземпляра {@code subsystemsFilter} — чтобы не залипнуть на устаревшем
     * результате при реальном изменении выбранных подсистем.
     *
     * <p>Устаревание — по ПАУЗЕ без обращений, а не по возрасту записи (issue #285): пересборка
     * дерева на большой конфигурации длится дольше прежних 300 мс, поэтому «возрастной» TTL
     * истекал прямо посреди неё и кэш чистился, ни разу толком не сработав. Пауза
     * {@link #EMPTY_CACHE_IDLE_MS} означает, что пересборка закончилась;
     * {@link #EMPTY_CACHE_MAX_AGE_MS} — жёсткий предел на случай непрерывного потока обращений.
     */
    private static final long EMPTY_CACHE_IDLE_MS = 500;
    private static final long EMPTY_CACHE_MAX_AGE_MS = 3000;
    private static long emptyCacheBuiltAt;
    private static long emptyCacheLastUse;
    private static Object emptyCacheFilterKey;
    private static final Map<List<CommonModule>, Boolean> emptyCache = new HashMap<>();

    private static boolean isEmptyUnderFilter(Viewer viewer,
            ObjectSetSubsystemsFilterBridge.CombinedSubsystemsFilter subsystemsFilter, CommonModuleGroupNode group)
    {
        long now = System.currentTimeMillis();
        if (subsystemsFilter != emptyCacheFilterKey
                || now - emptyCacheLastUse > EMPTY_CACHE_IDLE_MS
                || now - emptyCacheBuiltAt > EMPTY_CACHE_MAX_AGE_MS)
        {
            emptyCache.clear();
            emptyCacheFilterKey = subsystemsFilter;
            emptyCacheBuiltAt = now;
        }
        emptyCacheLastUse = now;
        List<CommonModule> members = group.getMembers();
        Boolean cached = emptyCache.get(members);
        if (cached != null)
            return cached;

        boolean empty = true;
        for (CommonModule member : members)
            if (subsystemsFilter.select(viewer, group, member))
            {
                empty = false;
                break;
            }
        emptyCache.put(members, empty);
        return empty;
    }

    private static ObjectSetSubsystemsFilterBridge.CombinedSubsystemsFilter findSubsystemsFilter(Viewer viewer)
    {
        if (!(viewer instanceof StructuredViewer sv))
            return null;
        for (ViewerFilter filter : sv.getFilters())
            if (filter instanceof ObjectSetSubsystemsFilterBridge.CombinedSubsystemsFilter wrapper)
                return wrapper;
        return null;
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        return true; // вся логика — в filter(), который вызывается для набора элементов сразу
    }

    /**
     * EDT передаёт {@code parent} то как сам элемент, то как {@link TreePath} — оборачивать
     * оба варианта, иначе фильтр не узнаёт, что находится внутри узла-группы, и вычищает
     * её реальных участников до нуля (issue #117).
     */
    private static boolean isInsideGroup(Object parent)
    {
        if (parent instanceof CommonModuleGroupNode)
            return true;
        if (parent instanceof TreePath treePath)
        {
            for (int i = 0; i < treePath.getSegmentCount(); i++)
                if (treePath.getSegment(i) instanceof CommonModuleGroupNode)
                    return true;
        }
        return false;
    }

    /** См. {@link NavigatorFilterHook#isSmartFilterPatternActive}. */
    private static boolean isSmartSearchActive(Viewer viewer)
    {
        if (viewer == null)
            return false;
        Control control = viewer.getControl();
        return control instanceof Tree tree && NavigatorFilterHook.isSmartFilterPatternActive(tree);
    }

    /** См. {@link NavigatorFilterHook#isSubsystemsRebindPending}. */
    private static boolean isSubsystemsRebindPending(Viewer viewer)
    {
        if (viewer == null)
            return false;
        Control control = viewer.getControl();
        return control instanceof Tree tree && NavigatorFilterHook.isSubsystemsRebindPending(tree);
    }
}
