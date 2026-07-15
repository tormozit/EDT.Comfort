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
    private boolean markedOnly;
    private Object treeInput;
    /** {@code true} — прежнее поведение (верхний уровень всегда раскрыт, см. InfobasesViewHook,
     * обычный Outline). {@code false} — как в штатном EDT: ничего не раскрываем по умолчанию,
     * только путь к совпадениям при непустом фильтре (см. {@link #applyTreeExpansion}). */
    private boolean expandTopLevelByDefault = true;
    /** Раскрытость дерева на момент открытия окна (см. {@link #captureInitialExpandedElements}) —
     * то, что штатно раскрыл нативный код (например, путь к уже выбранному элементу). При пустом
     * фильтре и {@code !expandTopLevelByDefault} возвращаемся именно к этому состоянию, а не
     * схлопываем всё подряд — иначе мы бы сами стирали то, что нативно раскрыл не наш код. */
    private Set<Object> initialExpandedElements;

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

    public void setMarkedOnly(boolean markedOnly) {
        this.markedOnly = markedOnly;
        subtreeMatchMemo.clear();
    }

    public boolean isMarkedOnly() {
        return markedOnly;
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

    public void setExpandTopLevelByDefault(boolean expandTopLevelByDefault) {
        this.expandTopLevelByDefault = expandTopLevelByDefault;
    }

    public boolean isExpandTopLevelByDefault() {
        return expandTopLevelByDefault;
    }

    /**
     * Снимок раскрытости дерева на момент открытия окна — вызывать один раз, ДО того как мы сами
     * начали фильтровать/раскрывать что-либо (то есть сразу после установки нашего фильтра на уже
     * готовый нативный viewer). См. использование в {@link #applyTreeExpansion}.
     */
    public void captureInitialExpandedElements(AbstractTreeViewer viewer) {
        if (viewer == null) {
            this.initialExpandedElements = new LinkedHashSet<>();
            return;
        }
        Object[] current = viewer.getExpandedElements();
        this.initialExpandedElements = current != null
            ? new LinkedHashSet<>(java.util.Arrays.asList(current)) : new LinkedHashSet<>();
    }

    /**
     * При широком фильтре (мало фрагментов, много совпадений) полный рекурсивный обход дерева
     * ради автораскрытия становится синхронно дорогим (по замерам — до ~1с на UI-потоке) и
     * ощущается как подвисание ввода. Раскрывать больше этого числа веток всё равно бесполезно —
     * пользователь не увидит их одновременно на экране.
     */
    private static final int MAX_AUTO_EXPAND = 200;

    /**
     * Верхний уровень дерева раскрыт по умолчанию только если {@link #expandTopLevelByDefault}
     * (для дерева типов — выключено, там штатное поведение EDT — свёрнуто при открытии, см.
     * {@code SmartOutlineHook}).
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

        if (!expandTopLevelByDefault && matcher.isEmpty)
        {
            // Пустой фильтр и мы не форсируем дефолтное раскрытие (дерево типов, см.
            // expandTopLevelByDefault) — возвращаемся к состоянию раскрытия на момент открытия
            // окна (initialExpandedElements), а НЕ схлопываем всё подряд: там может быть уже
            // раскрыт нативным кодом путь к текущему выбранному элементу — его схлопывать нельзя.
            applyExpandedElementsIfChanged(viewer,
                initialExpandedElements != null ? initialExpandedElements : new LinkedHashSet<>());
            return;
        }

        Set<Object> toExpand = new LinkedHashSet<>();
        if (expandTopLevelByDefault)
            collectTopLevelExpansion(viewer, toExpand);
        if (flattenWhenFiltered && !matcher.isEmpty)
        {
            applyExpandedElementsIfChanged(viewer, toExpand);
            return;
        }
        if (!matcher.isEmpty)
        {
            // Потолок считаем ОТДЕЛЬНО от toExpand.size() — тот уже содержит базовые верхние
            // узлы (collectTopLevelExpansion), их может быть намного больше MAX_AUTO_EXPAND само
            // по себе (в реальном дереве типов — ~1200), и тогда цикл обрывался бы на первой же
            // итерации, ни разу не вызвав collectExpandPath — раскрытие пути к совпадению
            // переставало бы работать вообще.
            int[] matchedAdded = { 0 };
            for (Object root : tcp.getElements(input))
            {
                if (matchedAdded[0] > MAX_AUTO_EXPAND)
                    break;
                collectExpandPath(tcp, root, toExpand, matchedAdded);
            }
        }
        applyExpandedElementsIfChanged(viewer, toExpand);
    }

    /**
     * {@code setExpandedElements} — недешёвый SWT-layout (по замерам — сотни мс на дереве типов).
     * Не вызываем его повторно, если набор раскрытых веток не изменился с прошлого раза — именно
     * повторный вызов с тем же результатом (например, один и тот же однобуквенный фильтр набран
     * заново) оказался основной причиной ощущения подвисания ввода.
     */
    private void applyExpandedElementsIfChanged(AbstractTreeViewer viewer, Set<Object> toExpand)
    {
        // Пустой toExpand — валидная цель (полностью свернуть), а не «нечего делать»: раньше
        // здесь был ранний выход на toExpand.isEmpty(), из-за которого возврат к пустому базовому
        // состоянию (см. вызов из applyTreeExpansion) не срабатывал, если раскрытых веток сейчас
        // не было — обычно безобидно, но именно тут был бы риск не свернуть остальное. Единственная
        // причина реально ничего не делать — набор раскрытых веток совпадает с уже установленным.
        Object[] current = viewer.getExpandedElements();
        if (current != null && sameElements(current, toExpand))
            return;
        viewer.setExpandedElements(toExpand.toArray());
    }

    private static boolean sameElements(Object[] current, Set<Object> toExpand)
    {
        if (current.length != toExpand.size())
            return false;
        for (Object o : current)
        {
            if (!toExpand.contains(o))
                return false;
        }
        return true;
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

    private boolean collectExpandPath(ITreeContentProvider cp, Object element, Set<Object> toExpand, int[] matchedAdded)
    {
        if (matchedAdded[0] > MAX_AUTO_EXPAND)
            return false;
        if (!hasMatchInSubtree(cp, element))
            return false;
        for (Object child : cp.getChildren(element))
            collectExpandPath(cp, child, toExpand, matchedAdded);
        if (cp.hasChildren(element))
        {
            if (toExpand.add(element))
                matchedAdded[0]++;
        }
        return true;
    }

    /**
     * Проверить, помечен ли элемент через {@code isChecked()}.
     * Совместимо с LWT/AEF-деревьями, где {@code TreeItem.getChecked()} не работает.
     */
    static boolean isElementChecked(Object element)
    {
        if (element == null)
            return false;
        Object state = Global.invoke(element, "getCheckState"); //$NON-NLS-1$
        if (state == null)
            return false;
        String name = state instanceof Enum ? ((Enum<?>) state).name() : String.valueOf(state);
        return "CHECKED".equals(name); //$NON-NLS-1$
    }

    /** Есть ли хотя бы один помеченный потомок (рекурсивно, через content provider). */
    private boolean hasCheckedDescendant(ITreeContentProvider cp, Object element)
    {
        for (Object child : cp.getChildren(element))
        {
            if (isElementChecked(child))
                return true;
            if (cp.hasChildren(child) && hasCheckedDescendant(cp, child))
                return true;
        }
        return false;
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        boolean hasChildren = false;
        TreeViewer treeViewer = viewer instanceof TreeViewer ? (TreeViewer) viewer : null;
        ITreeContentProvider treeCp = null;
        if (treeViewer != null) {
            Object cp = treeViewer.getContentProvider();
            if (cp instanceof ITreeContentProvider) {
                treeCp = (ITreeContentProvider) cp;
                hasChildren = treeCp.hasChildren(element);
            }
            Object input = treeViewer.getInput();
            if (input != null)
                this.treeInput = input;
        }

        if (markedOnly) {
            if (!isElementChecked(element)) {
                if (!hasChildren || treeCp == null || !hasCheckedDescendant(treeCp, element))
                    return false;
            }
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
