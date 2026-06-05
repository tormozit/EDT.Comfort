package tormozit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

public class SmartOutlineFilter extends ViewerFilter {
    
    private SmartMatcher matcher;
    private final ILabelProvider labelProvider;
    private final boolean pruneEmptyBranches;
    private final boolean codeMatcher;
    
    private final Map<Object, Integer> namePremiumCache = new HashMap<>();
    private final Map<Object, Integer> paramPremiumCache = new HashMap<>();

    public SmartOutlineFilter(ILabelProvider labelProvider) {
        this(labelProvider, false, false);
    }

    public SmartOutlineFilter(ILabelProvider labelProvider, boolean pruneEmptyBranches, boolean codeMatcher) {
        this.labelProvider = labelProvider;
        this.pruneEmptyBranches = pruneEmptyBranches;
        this.codeMatcher = codeMatcher;
        this.matcher = newMatcher("");
    }

    private SmartMatcher newMatcher(String pattern) {
        return codeMatcher ? new SmartCodeMatcher(pattern) : new SmartMatcher(pattern);
    }

    public void setPattern(String newPattern) {
        this.matcher = newMatcher(newPattern);
    }

    public void refreshPattern(String newPattern) {
        namePremiumCache.clear(); 
        paramPremiumCache.clear();
        setPattern(newPattern);
    }

    public Map<Object, Integer> getNamePremiumCache() {
        return namePremiumCache;
    }

    public Map<Object, Integer> getParamPremiumCache() {
        return paramPremiumCache;
    }

    /**
     * Верхний уровень дерева всегда раскрыт.
     * При непустом фильтре дополнительно раскрываются ветки на пути к совпадениям.
     */
    public void applyTreeExpansion(AbstractTreeViewer viewer)
    {
        if (viewer == null)
            return;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        Object input = viewer.getInput();
        if (input == null)
            return;
        Set<Object> toExpand = new LinkedHashSet<>();
        collectTopLevelExpansion(viewer, toExpand);
        if (!matcher.isEmpty)
        {
            for (Object root : tcp.getElements(input))
                collectExpandPath(tcp, root, toExpand);
        }
        if (!toExpand.isEmpty())
            viewer.setExpandedElements(toExpand.toArray());
    }

    /** Только корневые узлы (проекты). */
    void collectRootExpansion(AbstractTreeViewer viewer, Set<Object> toExpand)
    {
        if (viewer == null || toExpand == null)
            return;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        Object input = viewer.getInput();
        if (input == null)
            return;
        for (Object root : tcp.getElements(input))
            toExpand.add(root);
    }

    /** Корневые узлы и их прямые потомки (группы типов) — при открытии без фильтра. */
    void collectTopLevelExpansion(AbstractTreeViewer viewer, Set<Object> toExpand)
    {
        collectRootExpansion(viewer, toExpand);
        if (viewer == null || toExpand == null)
            return;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        Object input = viewer.getInput();
        if (input == null)
            return;
        for (Object root : tcp.getElements(input))
        {
            for (Object child : tcp.getChildren(root))
                toExpand.add(child);
        }
    }

    private boolean collectExpandPath(ITreeContentProvider cp, Object element, Set<Object> toExpand)
    {
        String text = labelProvider.getText(element);
        if (!cp.hasChildren(element))
            return matcher.matches(text);

        boolean descendant = false;
        for (Object child : cp.getChildren(element))
        {
            if (collectExpandPath(cp, child, toExpand))
                descendant = true;
        }
        if (descendant || matcher.matches(text))
        {
            toExpand.add(element);
            return true;
        }
        return false;
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        boolean hasChildren = false;
        TreeViewer treeViewer = viewer instanceof TreeViewer ? (TreeViewer) viewer : null;
        if (treeViewer != null) {
            Object cp = treeViewer.getContentProvider();
            if (cp instanceof ITreeContentProvider)
                hasChildren = ((ITreeContentProvider) cp).hasChildren(element);
        }

        String text = labelProvider.getText(element);

        if (!hasChildren) {
            if (!matcher.matches(text))
                return false;
        }
        else if (pruneEmptyBranches && !matcher.isEmpty && treeViewer != null)
        {
            if (!matcher.matches(text) && !hasMatchingDescendant(treeViewer, element))
                return false;
        }

        int namePremium = matcher.computeNamePremium(text);
        int paramPremium = matcher.computeParamPremium(text);
        namePremiumCache.put(element, namePremium);
        paramPremiumCache.put(element, paramPremium);

        return true;
    }

    private boolean hasMatchingDescendant(TreeViewer viewer, Object parent)
    {
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return false;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        for (Object child : tcp.getChildren(parent))
        {
            String text = labelProvider.getText(child);
            if (!tcp.hasChildren(child))
            {
                if (matcher.matches(text))
                    return true;
            }
            else if (matcher.matches(text) || hasMatchingDescendant(viewer, child))
            {
                return true;
            }
        }
        return false;
    }
}
