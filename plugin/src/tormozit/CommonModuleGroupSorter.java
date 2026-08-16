package tormozit;

import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreePathViewerSorter;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.widgets.Control;

import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;

/**
 * Порядок детей папки «Общие модули»: группа стоит там, где в исходном порядке EDT был её
 * самый верхний участник; несгруппированные модули остаются на своих местах.
 *
 * <p>Штатный {@code CommonViewerSorter} CNF для элементов из РАЗНЫХ расширений (наша группа vs
 * модуль EDT) не вызывает {@code commonSorter} расширения, а режет по sequenceNumber — группы
 * уезжали отдельным блоком в конец. Поэтому этот компаратор ставится обёрткой над компаратором
 * навигатора и сравнивает по индексу до делегирования в CNF.
 */
public final class CommonModuleGroupSorter extends TreePathViewerSorter
{
    private final ViewerComparator delegate;

    /** Для {@code plugin.xml} {@code commonSorter} — без обёртки, сравнение по индексу. */
    public CommonModuleGroupSorter()
    {
        this(null);
    }

    CommonModuleGroupSorter(ViewerComparator delegate)
    {
        this.delegate = delegate;
    }

    /**
     * Ставит обёртку один раз на компаратор навигатора.
     *
     * <p>Вызывается из {@code inputChanged} контент-провайдера CNF, а тот инициализируется лениво —
     * прямо внутри {@code createChildren}/{@code setSelectionToWidget} дерева. В этот момент viewer
     * занят, и {@code setComparator} → {@code refresh()} JFace молча гасит («Ignored reentrant call
     * while viewer is busy»), т.е. пересортировка теряется. Поэтому установка откладывается на
     * следующий цикл событий, когда viewer уже свободен и refresh реально отрабатывает.
     */
    static void installOn(Viewer viewer)
    {
        if (!(viewer instanceof StructuredViewer structured))
            return;
        Control control = structured.getControl();
        if (control == null || control.isDisposed())
            return;
        if (structured.getComparator() instanceof CommonModuleGroupSorter)
            return;
        control.getDisplay().asyncExec(() -> {
            if (control.isDisposed())
                return;
            ViewerComparator current = structured.getComparator();
            if (current instanceof CommonModuleGroupSorter)
                return;
            structured.setComparator(new CommonModuleGroupSorter(current));
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
        if (isOrderable(e1) && isOrderable(e2))
            return Integer.compare(
                    CommonModuleGroupContentProvider.sortIndex(e1),
                    CommonModuleGroupContentProvider.sortIndex(e2));
        if (delegate instanceof TreePathViewerSorter treePathSorter)
            return treePathSorter.compare(viewer, parentPath, e1, e2);
        if (delegate != null)
            return delegate.compare(viewer, e1, e2);
        return 0;
    }

    @Override
    public int compare(Viewer viewer, Object e1, Object e2)
    {
        if (isOrderable(e1) && isOrderable(e2))
            return Integer.compare(
                    CommonModuleGroupContentProvider.sortIndex(e1),
                    CommonModuleGroupContentProvider.sortIndex(e2));
        if (delegate != null)
            return delegate.compare(viewer, e1, e2);
        return 0;
    }

    private static boolean isOrderable(Object element)
    {
        return element instanceof CommonModuleGroupNode || element instanceof CommonModule;
    }
}
