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
    private boolean flattenWhenFiltered;
    private Object treeInput;
    
    private final Map<Object, Integer> namePremiumCache = new HashMap<>();
    private final Map<Object, Integer> paramPremiumCache = new HashMap<>();
    private final Map<Object, Boolean> subtreeMatchMemo = new HashMap<>();

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
        subtreeMatchMemo.clear();
        setPattern(newPattern);
        if (flatContentProvider != null)
            flatContentProvider.invalidateFilterResultCache();
    }

    /** Кэш премий при плоском списке — заполняется content provider, не label provider. */
    public void recordMatchPremiums(Object element, String text) {
        if (element == null || text == null)
            return;
        namePremiumCache.put(element, matcher.computeNamePremium(text));
        paramPremiumCache.put(element, matcher.computeParamPremium(text));
    }

    private SmartOutlineFlatContentProvider flatContentProvider;

    void bindFlatContentProvider(SmartOutlineFlatContentProvider flatContentProvider) {
        this.flatContentProvider = flatContentProvider;
    }

    private String resolveFilterText(Object element) {
        if (flatContentProvider != null && flattenWhenFiltered && !matcher.isEmpty) {
            String cached = flatContentProvider.getFilterText(element);
            if (cached != null)
                return cached;
        }
        return labelProvider.getText(element);
    }

    public Map<Object, Integer> getNamePremiumCache() {
        return namePremiumCache;
    }

    public Map<Object, Integer> getParamPremiumCache() {
        return paramPremiumCache;
    }

    /** Плоский список совпадений без группирующих веток (Quick Outline). */
    public void setFlattenWhenFiltered(boolean flattenWhenFiltered) {
        this.flattenWhenFiltered = flattenWhenFiltered;
    }

    public boolean isFlattenWhenFiltered() {
        return flattenWhenFiltered;
    }

    public boolean isFiltering() {
        return !matcher.isEmpty;
    }

    public String getPattern() {
        return matcher != null ? matcher.fullPattern : ""; //$NON-NLS-1$
    }

    public boolean matchesText(String text) {
        return matcher.matches(text);
    }

    /**
     * При широком фильтре (мало фрагментов, много совпадений) полный рекурсивный обход дерева
     * ради автораскрытия становится синхронно дорогим (по замерам — до ~1с на UI-потоке, см.
     * временный лог "typetree-expand") и ощущается как подвисание ввода. Раскрывать больше этого
     * числа веток всё равно бесполезно — пользователь не увидит их одновременно на экране.
     */
    private static final int MAX_AUTO_EXPAND = 200;

    /**
     * Верхний уровень дерева всегда раскрыт.
     * При непустом фильтре дополнительно раскрываются ветки на пути к совпадениям — но не более
     * {@link #MAX_AUTO_EXPAND}, дальше обход обрывается (см. класс-комментарий).
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
        if (flattenWhenFiltered && !matcher.isEmpty)
            return;
        if (!matcher.isEmpty)
        {
            for (Object root : tcp.getElements(input))
            {
                if (toExpand.size() > MAX_AUTO_EXPAND)
                    break;
                collectExpandPath(tcp, root, toExpand);
            }
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
        if (toExpand.size() > MAX_AUTO_EXPAND)
            return false;
        if (!hasMatchInSubtree(cp, element))
            return false;
        for (Object child : cp.getChildren(element))
            collectExpandPath(cp, child, toExpand);
        if (cp.hasChildren(element))
            toExpand.add(element);
        return true;
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        boolean hasChildren = false;
        TreeViewer treeViewer = viewer instanceof TreeViewer ? (TreeViewer) viewer : null;
        if (treeViewer != null) {
            Object cp = treeViewer.getContentProvider();
            if (cp instanceof ITreeContentProvider)
                hasChildren = ((ITreeContentProvider) cp).hasChildren(element);
            Object input = treeViewer.getInput();
            if (input != null)
                this.treeInput = input;
        }

        if (flattenWhenFiltered && !matcher.isEmpty && flatContentProvider != null && !hasChildren) {
            String text = resolveFilterText(element);
            if (!matcher.matches(text))
                return false;
            recordMatchPremiums(element, text);
            return true;
        }

        String text = resolveFilterText(element);

        if (!hasChildren) {
            if (pruneEmptyBranches && !matcher.isEmpty) {
                String parentText = (parentElement != null && parentElement != treeInput)
                        ? resolveFilterText(parentElement) : "";
                String elemText = resolveFilterText(element);
                String fullName = parentText.isEmpty() ? elemText : parentText + "." + elemText;
                if (!matcher.matchesTree(fullName))
                    return false;
            } else {
                if (!matcher.matches(text))
                    return false;
            }
        }
        else if (pruneEmptyBranches && !matcher.isEmpty && treeViewer != null)
        {
            Object cp = treeViewer.getContentProvider();
            if (cp instanceof ITreeContentProvider
                    && !hasMatchInSubtree((ITreeContentProvider) cp, element))
                return false;
        }

        int namePremium = matcher.computeNamePremium(text);
        int paramPremium = matcher.computeParamPremium(text);
        namePremiumCache.put(element, namePremium);
        paramPremiumCache.put(element, paramPremium);

        return true;
    }

    /** Есть совпадение в узле или любом потомке (мемоизация — один проход на ветку). */
    protected boolean hasMatchInSubtree(ITreeContentProvider cp, Object element)
    {
        Boolean memo = subtreeMatchMemo.get(element);
        if (memo != null)
            return memo.booleanValue();

        boolean self;
        if (pruneEmptyBranches && !matcher.isEmpty) {
            Object parent = cp.getParent(element);
            String parentText = (parent != null && parent != treeInput)
                    ? resolveFilterText(parent) : "";
            String elemText = resolveFilterText(element);
            String fullName = parentText.isEmpty() ? elemText : parentText + "." + elemText;
            self = matcher.matchesTree(fullName);
        } else {
            String text = resolveFilterText(element);
            self = matcher.matches(text);
        }

        if (!cp.hasChildren(element))
        {
            subtreeMatchMemo.put(element, self);
            return self;
        }

        boolean childMatch = false;
        for (Object child : cp.getChildren(element))
        {
            if (hasMatchInSubtree(cp, child))
            {
                childMatch = true;
                break;
            }
        }
        boolean result = self || childMatch;
        subtreeMatchMemo.put(element, result);
        return result;
    }
}
