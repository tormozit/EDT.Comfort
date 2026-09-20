package tormozit;

import java.text.Collator;
import java.util.Locale;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreePathViewerSorter;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.model.IWorkbenchAdapter;

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

    private static final Collator RU_COLLATOR = createRuCollator();

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
        return element != null && COMMON_NODE_CLASS_NAME.equals(element.getClass().getName());
    }

    private static String labelOf(Object element)
    {
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
