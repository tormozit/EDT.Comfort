package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Неблокирующее применение результата фильтра — по образцу штатного
 * {@code NavigatorUtil.applyFilterNonBlockingUi} + {@code refresh(project)}.
 *
 * <p><b>ФИЛЬТРАЦИЯ НЕ ДОЛЖНА БЛОКИРОВАТЬ ВВОД</b>: не делаем {@code viewer.refresh(element)} по каждому
 * совпадению; один {@link NavigatorOutlineFilter#filter} на тик UI и {@code refresh} только корней проектов.
 *
 * <p><b>При выводе фильтрованного дерева раскрывать узлы на пути к совпадениям</b> — через
 * {@link NavigatorOutlineFilter#applyPrecomputedExpansion} / {@code setExpandedElements} по
 * множеству {@code expand} от worker (после {@code filter} и {@code refresh} корней).
 */
public final class NavigatorNonBlockingUi
{
    private static final String UI_LAST_VISIBLE_KEY = "tormozit.navigatorUiLastVisible"; //$NON-NLS-1$
    private static final String UI_PENDING_VISIBLE_KEY = "tormozit.navigatorUiPendingVisible"; //$NON-NLS-1$
    private static final String UI_PENDING_EXPAND_KEY = "tormozit.navigatorUiPendingExpand"; //$NON-NLS-1$
    private static final String UI_PENDING_COMPLETED_KEY = "tormozit.navigatorUiPendingCompleted"; //$NON-NLS-1$
    private static final String UI_SCHEDULED_KEY = "tormozit.navigatorUiScheduled"; //$NON-NLS-1$

    private NavigatorNonBlockingUi() {}

    public static void resetState(Tree tree)
    {
        if (tree == null)
            return;
        tree.setData(UI_LAST_VISIBLE_KEY, null);
        tree.setData(UI_PENDING_VISIBLE_KEY, null);
        tree.setData(UI_PENDING_EXPAND_KEY, null);
        tree.setData(UI_PENDING_COMPLETED_KEY, null);
        tree.setData(UI_SCHEDULED_KEY, null);
    }

    /**
     * Как {@code NavigatorUtil.applyFilterNonBlockingUi}: coalesce порций worker в один тик UI,
     * без массового {@code viewer.refresh()}.
     */
    public static void applyPrecomputedUi(CommonViewer viewer, Tree tree, NavigatorOutlineFilter filter,
            Set<Object> visible, Set<Object> expand, boolean completed)
    {
        if (viewer == null || tree == null || tree.isDisposed() || filter == null)
            return;

        Set<Object> safeVisible = visible != null ? visible : Collections.emptySet();
        tree.setData(UI_PENDING_VISIBLE_KEY, new HashSet<>(safeVisible));
        mergePendingExpand(tree, safeVisible, expand);

        if (Boolean.TRUE.equals(tree.getData(UI_PENDING_COMPLETED_KEY)))
            completed = true;
        else if (completed)
            tree.setData(UI_PENDING_COMPLETED_KEY, Boolean.TRUE);

        scheduleCoalescedApply(viewer, tree, filter);
    }

    private static void scheduleCoalescedApply(CommonViewer viewer, Tree tree, NavigatorOutlineFilter filter)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(UI_SCHEDULED_KEY)))
            return;
        tree.setData(UI_SCHEDULED_KEY, Boolean.TRUE);
        display.timerExec(0, () -> {
            tree.setData(UI_SCHEDULED_KEY, null);
            if (tree.isDisposed() || viewer.getControl() == null || viewer.getControl().isDisposed())
                return;
            runFilterApply(viewer, tree, filter);
        });
    }

    private static void runFilterApply(CommonViewer viewer, Tree tree, NavigatorOutlineFilter filter)
    {
        @SuppressWarnings("unchecked")
        Set<Object> pendingVisible = (Set<Object>) tree.getData(UI_PENDING_VISIBLE_KEY);
        if (pendingVisible == null)
            pendingVisible = Collections.emptySet();

        @SuppressWarnings("unchecked")
        Set<Object> lastVisible = (Set<Object>) tree.getData(UI_LAST_VISIBLE_KEY);
        if (lastVisible == null)
            lastVisible = Collections.emptySet();

        boolean completed = Boolean.TRUE.equals(tree.getData(UI_PENDING_COMPLETED_KEY));

        // Штатный путь: applyNonBlockingFilter — не filter(viewer, null, null) (NPE на TreePath).
        NavigatorOutlineFilter.applyNonBlockingFilter(viewer, filter);
        refreshAffectedProjectRoots(viewer, lastVisible, pendingVisible, completed);

        tree.setData(UI_LAST_VISIBLE_KEY, new HashSet<>(pendingVisible));

        if (pendingVisible.isEmpty())
        {
            if (completed)
            {
                tree.setData(UI_PENDING_COMPLETED_KEY, null);
                viewer.setExpandedElements(new Object[0]);
            }
            return;
        }

        // При выводе фильтрованного дерева раскрывать узлы на пути к совпадениям.
        // Откладываем на следующий тик: после refresh(project) дочерние узлы должны появиться в дереве.
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.timerExec(0, () -> {
                if (tree.isDisposed() || viewer.getControl() == null || viewer.getControl().isDisposed())
                    return;
                applyMatchPathExpansion(viewer, tree, filter);
            });
        }
        else
            applyMatchPathExpansion(viewer, tree, filter);

        if (!completed)
            return;

        tree.setData(UI_PENDING_COMPLETED_KEY, null);
    }

    /** Как {@code NavigatorSearchFilter.refreshProjects}: refresh только корней {@code IProject}. */
    private static void refreshAffectedProjectRoots(CommonViewer viewer, Set<Object> lastVisible,
            Set<Object> currentVisible, boolean completed)
    {
        Set<Object> rootsToRefresh = new LinkedHashSet<>();
        if (currentVisible.isEmpty() && completed)
        {
            collectWorkspaceRoots(viewer, rootsToRefresh);
            for (Object wasVisible : lastVisible)
                addRootProject(viewer, wasVisible, rootsToRefresh);
        }
        else
        {
            Set<Object> delta = new HashSet<>(currentVisible);
            delta.removeAll(lastVisible);
            if (delta.isEmpty() && !lastVisible.isEmpty())
                return;
            Iterable<Object> scan = delta.isEmpty() ? currentVisible : delta;
            for (Object element : scan)
                addRootProject(viewer, element, rootsToRefresh);
            if (lastVisible.isEmpty() && !currentVisible.isEmpty())
                collectWorkspaceRoots(viewer, rootsToRefresh);
        }
        for (Object root : rootsToRefresh)
            viewer.refresh(root);
    }

    private static void collectWorkspaceRoots(CommonViewer viewer, Set<Object> rootsToRefresh)
    {
        Object cp = viewer.getContentProvider();
        Object input = viewer.getInput();
        if (!(cp instanceof ITreeContentProvider) || input == null)
            return;
        for (Object root : ((ITreeContentProvider) cp).getElements(input))
        {
            if (root != null)
                rootsToRefresh.add(root);
        }
    }

    private static void addRootProject(CommonViewer viewer, Object element, Set<Object> rootsToRefresh)
    {
        if (element == null)
            return;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        Object current = element;
        Object root = element;
        while (current != null)
        {
            root = current;
            current = tcp.getParent(current);
        }
        if (root != null)
            rootsToRefresh.add(root);
    }

    private static void mergePendingExpand(Tree tree, Set<Object> visible, Set<Object> expand)
    {
        if (tree == null)
            return;
        if (visible == null || visible.isEmpty())
        {
            tree.setData(UI_PENDING_EXPAND_KEY, new LinkedHashSet<>());
            return;
        }
        @SuppressWarnings("unchecked")
        Set<Object> pending = (Set<Object>) tree.getData(UI_PENDING_EXPAND_KEY);
        LinkedHashSet<Object> merged = new LinkedHashSet<>();
        if (pending != null)
            merged.addAll(pending);
        if (expand != null)
            merged.addAll(expand);
        tree.setData(UI_PENDING_EXPAND_KEY, merged);
    }

    /**
     * Раскрыть ветки на пути к совпадениям. Сначала предки (по глубине), затем
     * {@code setExpandedElements} — иначе глубокие узлы не появляются в дереве.
     */
    private static void applyMatchPathExpansion(CommonViewer viewer, Tree tree, NavigatorOutlineFilter filter)
    {
        if (viewer == null || filter == null)
            return;
        @SuppressWarnings("unchecked")
        Set<Object> expand = (Set<Object>) tree.getData(UI_PENDING_EXPAND_KEY);
        if (expand == null || expand.isEmpty())
        {
            filter.applyFilterExpansion(viewer);
            return;
        }
        for (Object element : orderByDepthAscending(viewer, expand))
            viewer.setExpandedState(element, true);
        viewer.setExpandedElements(expand.toArray());
    }

    private static List<Object> orderByDepthAscending(CommonViewer viewer, Set<Object> expand)
    {
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return new ArrayList<>(expand);
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        List<Object> ordered = new ArrayList<>(expand);
        ordered.sort((a, b) -> Integer.compare(depthOf(tcp, a), depthOf(tcp, b)));
        return ordered;
    }

    private static int depthOf(ITreeContentProvider tcp, Object element)
    {
        int depth = 0;
        Object current = element;
        while (current != null)
        {
            depth++;
            current = tcp.getParent(current);
        }
        return depth;
    }
}
