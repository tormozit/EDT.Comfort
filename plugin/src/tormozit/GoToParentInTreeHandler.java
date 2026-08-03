package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Команда «Перейти к родителю в дереве»: выбирает родительский элемент текущего
 * выделения в дереве с фокусом (SWT {@link Tree}), независимо от конкретного
 * представления. По умолчанию Ctrl+Up.
 */
public class GoToParentInTreeHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.GoToParentInTree"; //$NON-NLS-1$

    @Override
    public void setEnabled(Object evaluationContext)
    {
        boolean enabled = false;
        try
        {
            Tree tree = resolveFocusedTree();
            enabled = tree != null && findTargetParents(tree) != null;
        }
        catch (Exception ignored)
        {
            // команда остаётся недоступной
        }
        setBaseEnabled(enabled);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        Tree tree = resolveFocusedTree();
        if (tree == null)
            return null;
        List<TreeItem> parents = findTargetParents(tree);
        if (parents == null)
            return null;
        tree.setRedraw(false);
        try
        {
            tree.setSelection(parents.toArray(new TreeItem[0]));
            tree.showItem(parents.get(0));
            fireSelectionEvent(tree, parents.get(0));
        }
        finally
        {
            tree.setRedraw(true);
        }
        return null;
    }

    private static Tree resolveFocusedTree()
    {
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        if (display == null || display.isDisposed())
            return null;
        Control focus = display.getFocusControl();
        if (focus instanceof Tree tree && !tree.isDisposed())
            return tree;
        return null;
    }

    /**
     * Родители текущего выделения дерева без повторов, в порядке выделения;
     * {@code null}, если выделения нет или ни у одного элемента нет родителя.
     */
    private static List<TreeItem> findTargetParents(Tree tree)
    {
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return null;
        List<TreeItem> parents = new ArrayList<>();
        for (TreeItem item : selection)
        {
            TreeItem parent = item.getParentItem();
            if (parent != null && !parents.contains(parent))
                parents.add(parent);
        }
        return parents.isEmpty() ? null : parents;
    }

    private static void fireSelectionEvent(Tree tree, TreeItem item)
    {
        Event event = new Event();
        event.widget = tree;
        event.item = item;
        tree.notifyListeners(SWT.Selection, event);
    }
}
