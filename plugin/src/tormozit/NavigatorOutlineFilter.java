package tormozit;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Фильтр навигатора EDT. НЕ ТРОГАТЬ ГРУППЫ: {@link NavigatorTreeElementLabels#isGroupNode(Object)}
 * не сравниваются с паттерном; остаются видимыми только как путь к совпадениям в потомках.
 *
 * <p><b>При выводе фильтрованного дерева раскрывать узлы на пути к совпадениям</b>
 * ({@link #applyFilterExpansion}, precomputed {@link #precomputedExpand}).
 */
public final class NavigatorOutlineFilter extends SmartOutlineFilter
{
    private volatile Set<Object> precomputedVisible = Collections.emptySet();
    private volatile Set<Object> precomputedExpand = Collections.emptySet();
    private volatile boolean usePrecomputed;

    public NavigatorOutlineFilter(ILabelProvider labelProvider, boolean pruneEmptyBranches, boolean codeMatcher)
    {
        super(labelProvider, pruneEmptyBranches, codeMatcher);
    }

    public void setPrecomputedResult(Set<Object> visible, Set<Object> expand, boolean usePrecomputed)
    {
        this.precomputedVisible = visible != null ? visible : Collections.emptySet();
        this.precomputedExpand = expand != null ? expand : Collections.emptySet();
        this.usePrecomputed = usePrecomputed;
    }

    public void clearPrecomputedResult()
    {
        this.precomputedVisible = Collections.emptySet();
        this.precomputedExpand = Collections.emptySet();
        this.usePrecomputed = false;
    }

    public void applyPrecomputedExpansion(TreeViewer viewer)
    {
        if (!usePrecomputed || viewer == null || precomputedExpand.isEmpty())
            return;
        viewer.setExpandedElements(precomputedExpand.toArray());
    }

    /**
     * Раскрыть все узлы, в поддереве которых есть совпадения с паттерном
     * (включая группы EDT — только если есть покрытие в потомках).
     */
    public void applyFilterExpansion(TreeViewer viewer)
    {
        if (viewer == null || !isFiltering())
            return;
        if (usePrecomputed && !precomputedExpand.isEmpty())
        {
            viewer.setExpandedElements(precomputedExpand.toArray());
            return;
        }
        Set<Object> toExpand = new LinkedHashSet<>();
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        Object input = viewer.getInput();
        if (input == null)
            return;
        for (Object root : tcp.getElements(input))
            collectExpandWithCoverage(tcp, root, toExpand);
        if (!toExpand.isEmpty())
            viewer.setExpandedElements(toExpand.toArray());
    }

    private void collectExpandWithCoverage(ITreeContentProvider cp, Object element, Set<Object> toExpand)
    {
        if (!hasCoverageInSubtree(cp, element))
            return;
        for (Object child : cp.getChildren(element))
            collectExpandWithCoverage(cp, child, toExpand);
        if (cp.hasChildren(element))
            toExpand.add(element);
    }

    private boolean hasCoverageInSubtree(ITreeContentProvider cp, Object element)
    {
        // НЕ ТРОГАТЬ ГРУППЫ: покрытие только через потомков, не по имени папки.
        if (NavigatorTreeElementLabels.isGroupNode(element))
        {
            if (!cp.hasChildren(element))
                return false;
            for (Object child : cp.getChildren(element))
            {
                if (hasCoverageInSubtree(cp, child))
                    return true;
            }
            return false;
        }
        return hasMatchInSubtree(cp, element);
    }

    /**
     * Как {@code NavigatorUtil.applyFilterNonBlockingUi}: пересчёт верхнего уровня без полного
     * {@code viewer.refresh()}. Нельзя вызывать {@code filter(viewer, null, null)} — в JFace
     * выбирается перегрузка {@code filter(Viewer, TreePath, Object[])} и падает NPE.
     */
    public static void applyNonBlockingFilter(CommonViewer viewer, NavigatorOutlineFilter filter)
    {
        if (viewer == null || filter == null)
            return;
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        Object[] elements = new Object[0];
        Object cp = viewer.getContentProvider();
        if (cp instanceof IStructuredContentProvider)
            elements = ((IStructuredContentProvider) cp).getElements(root);
        filter.filter(viewer, root, elements);
    }

    @Override
    public Object[] filter(Viewer viewer, TreePath parentPath, Object[] elements)
    {
        Object parent = parentPath != null ? parentPath.getLastSegment() : null;
        return filter(viewer, parent, elements);
    }

    @Override
    public Object[] filter(Viewer viewer, Object parent, Object[] elements)
    {
        if (parent == null && viewer instanceof CommonViewer)
        {
            CommonViewer commonViewer = (CommonViewer) viewer;
            Object cp = commonViewer.getContentProvider();
            if (cp instanceof IStructuredContentProvider)
            {
                IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
                elements = ((IStructuredContentProvider) cp).getElements(root);
                parent = root;
            }
        }
        return super.filter(viewer, parent, elements != null ? elements : new Object[0]);
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        if (usePrecomputed && isFiltering())
            return precomputedVisible.contains(element);

        if (NavigatorTreeElementLabels.isGroupNode(element))
        {
            if (!isFiltering())
                return true;
            TreeViewer treeViewer = viewer instanceof TreeViewer ? (TreeViewer) viewer : null;
            if (treeViewer == null)
                return true;
            Object cp = treeViewer.getContentProvider();
            if (!(cp instanceof ITreeContentProvider))
                return true;
            return hasMatchInSubtree((ITreeContentProvider) cp, element);
        }
        return super.select(viewer, parentElement, element);
    }
}
