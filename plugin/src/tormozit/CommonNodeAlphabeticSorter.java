package tormozit;

import java.text.Collator;
import java.util.Locale;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreePathViewerSorter;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.model.IWorkbenchAdapter;

import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.VirtualFolderPartialModelNode;

/**
 * Алфавитный порядок непосредственных детей верхнего узла «Общие» конфигурации
 * (Общие модули, Общие реквизиты, Роли, Подсистемы и т.п.; issue #550), вместо
 * захардкоженного в EDT порядка.
 *
 * <p>Узел «Общие» — {@code com._1c.g5.v8.dt.md.ui.navigator.adapters.CommonNavigatorAdapter}
 * (пакет не экспортирован бандлом — класс недоступен для импорта, проверяется по имени).
 * Его {@code getChildren()} — обычный Java-метод с фиксированной последовательностью
 * добавлений; штатный {@code NavigatorSorter} детей не сортирует ({@code compare} всегда 0).
 *
 * <p>Как и {@link CommonModuleGroupSorter} — ставится обёрткой поверх компаратора навигатора:
 * CNF не зовёт {@code commonSorter} стороннего расширения для узлов чужого расширения,
 * а весь навигатор EDT строит одно нативное расширение.
 */
final class CommonNodeAlphabeticSorter extends TreePathViewerSorter
{
    private static final String COMMON_NODE_CLASS_NAME =
            "com._1c.g5.v8.dt.md.ui.navigator.adapters.CommonNavigatorAdapter"; //$NON-NLS-1$
    private static final String COMMON_FOLDER_DESCRIPTOR_CLASS_NAME =
            "com._1c.g5.v8.dt.md.compare.ui.internal.CommonVirtualFolderDescriptor"; //$NON-NLS-1$

    private static final Collator RU_COLLATOR = createRuCollator();
    private static final Set<Object> COMMON_SEARCH_NODES = Collections.newSetFromMap(new WeakHashMap<>());

    /** Общая точка после штатной сортировки JFace; вплетение устанавливает ранний бандл. */
    static void installGlobally()
    {
        System.getProperties().put("tormozit.commonNode.searchNode", //$NON-NLS-1$
            (BiFunction<Object, Object, Object>) (adapter, node) -> {
                if (adapter != null && COMMON_NODE_CLASS_NAME.equals(adapter.getClass().getName()) && node != null)
                {
                    synchronized (COMMON_SEARCH_NODES)
                    {
                        COMMON_SEARCH_NODES.add(node);
                    }
                }
                return node;
            });
        System.getProperties().put("tormozit.commonNode.children", //$NON-NLS-1$
            (BiFunction<Object, Object, Object>) (parent, children) -> {
                Object element = parent instanceof TreePath path ? path.getLastSegment() : parent;
                if (!(children instanceof Object[] elements) || !isEnabled()
                        || !ComfortSettings.isReplaceListFiltersEnabled() || !isCommonNode(element))
                    return children;
                Object[] sorted = elements.clone();
                // Collator не потокобезопасен; провайдер дерева может вызываться в фоне.
                synchronized (RU_COLLATOR)
                {
                    Arrays.sort(sorted, (first, second) -> RU_COLLATOR.compare(labelOf(first), labelOf(second)));
                }
                return sorted;
            });
    }

    private final ViewerComparator delegate;

    private static Collator createRuCollator()
    {
        Collator collator = Collator.getInstance(new Locale("ru")); //$NON-NLS-1$
        collator.setStrength(Collator.PRIMARY);
        return collator;
    }

    private CommonNodeAlphabeticSorter(ViewerComparator delegate)
    {
        this.delegate = delegate;
    }

    /** Ставит обёртку один раз на компаратор навигатора; см. {@link CommonModuleGroupSorter#installOn}. */
    static void installOn(Viewer viewer)
    {
        if (!(viewer instanceof StructuredViewer structured))
            return;
        Control control = structured.getControl();
        if (control == null || control.isDisposed())
            return;
        if (structured.getComparator() instanceof CommonNodeAlphabeticSorter)
            return;
        control.getDisplay().asyncExec(() -> {
            if (control.isDisposed())
                return;
            ViewerComparator current = structured.getComparator();
            if (current instanceof CommonNodeAlphabeticSorter)
                return;
            structured.setComparator(new CommonNodeAlphabeticSorter(current));
        });
    }

    @Override
    public boolean isSorterProperty(Object element, String property)
    {
        return delegate != null && delegate.isSorterProperty(element, property);
    }

    @Override
    public boolean isSorterProperty(TreePath parentPath, Object element, String property)
    {
        if (delegate instanceof TreePathViewerSorter treePathSorter)
            return treePathSorter.isSorterProperty(parentPath, element, property);
        return isSorterProperty(element, property);
    }

    @Override
    public int compare(Viewer viewer, TreePath parentPath, Object e1, Object e2)
    {
        if (isEnabled() && parentPath != null && isCommonNode(parentPath.getLastSegment()))
            return RU_COLLATOR.compare(labelOf(e1), labelOf(e2));
        if (delegate instanceof TreePathViewerSorter treePathSorter)
            return treePathSorter.compare(viewer, parentPath, e1, e2);
        if (delegate != null)
            return delegate.compare(viewer, e1, e2);
        return 0;
    }

    @Override
    public int compare(Viewer viewer, Object e1, Object e2)
    {
        // Обычный TreeViewer сравнения не передаёт TreePath: родитель есть в модели узла.
        if (isEnabled() && e1 instanceof IPartialModelNode first && e2 instanceof IPartialModelNode second
                && first.getParent() == second.getParent() && isCommonNode(first.getParent()))
            return RU_COLLATOR.compare(labelOf(e1), labelOf(e2));
        if (delegate != null)
            return delegate.compare(viewer, e1, e2);
        return 0;
    }

    /** Подчинена флажку «Улучшать списки» — сортировщик ставится только вместе с ним. */
    private static boolean isEnabled()
    {
        return ComfortSettings.isAlphabeticCommonNodeEnabled();
    }

    private static boolean isCommonNode(Object element)
    {
        synchronized (COMMON_SEARCH_NODES)
        {
            if (COMMON_SEARCH_NODES.contains(element))
                return true;
        }
        if (element instanceof VirtualFolderPartialModelNode folder)
            return ComfortSettings.isReplaceListFiltersEnabled()
                    && COMMON_FOLDER_DESCRIPTOR_CLASS_NAME.equals(folder.getDescriptor().getClass().getName());
        return element != null && COMMON_NODE_CLASS_NAME.equals(element.getClass().getName());
    }

    private static String labelOf(Object element)
    {
        if (element != null && "com._1c.g5.v8.dt.internal.search.ui.provider.MatchTreeItem".equals( //$NON-NLS-1$
                element.getClass().getName()))
        {
            // Поле text подтверждено в MatchTreeItem: содержит название без счётчика совпадений.
            Object text = Global.getField(element, "text"); //$NON-NLS-1$
            return text instanceof String value ? value : ""; //$NON-NLS-1$
        }
        if (element instanceof IPartialModelNode node)
        {
            String label = node.getLabel();
            return label != null ? label : ""; //$NON-NLS-1$
        }
        Object adapterObj = Platform.getAdapterManager().getAdapter(element, IWorkbenchAdapter.class);
        if (adapterObj instanceof IWorkbenchAdapter adapter)
        {
            String label = adapter.getLabel(element);
            if (label != null)
                return label;
        }
        return element != null ? element.toString() : ""; //$NON-NLS-1$
    }
}
