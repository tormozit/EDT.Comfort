package tormozit;


import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.ui.IEditorPart;

import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.AbstractDirectPartialModelNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.ProjectPartialModelNode;

public class CompareConfigExpandHandler extends AbstractHandler
{
    private static Method retrieveMethodCache = null;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        return null;
    }

    public static Object expand(IEditorPart editor, CompareConfigExpandMode mode)
    {
        AbstractTreeViewer viewer = getTreeViewer(editor);
        if (viewer == null) return null;

        ITreeContentProvider cp = (ITreeContentProvider) viewer.getContentProvider();
        if (cp == null) return null;

        ISelection selection = viewer.getSelection();

        Set<Object> toExpand = new HashSet<>();

        viewer.collapseAll();
        for (Object root : cp.getElements(viewer.getInput()))
        {
            collectElementsToExpand(cp, root, mode, toExpand, viewer);
        }
        viewer.setExpandedElements(toExpand.toArray());

        if (selection != null && !selection.isEmpty())
        {
            viewer.setSelection(selection, true);
        }
        return null;
    }

    /**
     * Рекурсивно собирает список узлов для раскрытия.
     * Никаких вызовов вьювера внутри!
     */
    private static void collectElementsToExpand(ITreeContentProvider cp, Object element, CompareConfigExpandMode mode, Set<Object> toExpand, AbstractTreeViewer viewer)
    {
        if (false
                || !cp.hasChildren(element)
                || mode == CompareConfigExpandMode.toBothElement && isAddedOrDeleted(element)
                || mode == CompareConfigExpandMode.toObject      && isObject(element)
                || mode == CompareConfigExpandMode.toMarked      && !isMarked(element))
            return;

        toExpand.add(element);
        Object[] children;
        try
        {
            children = cp.getChildren(element);
        }
        catch (Exception e)
        {
            // https://github.com/tormozit/EDT-Tormozit/issues/8
            e.printStackTrace();
            return;
        }
        for (Object child : children)
        {
            if (CompareConfigSearchDialogHook.isNodeMatchFilters(child, viewer));
                collectElementsToExpand(cp, child, mode, toExpand, viewer);
        }
    }

    /**
     * Возвращает {@code true} если у узла установлен чекбокс в дереве сравнения.
     */
    private static boolean isMarked(Object element)
    {
        boolean isChecked = false;
        try
        {
            Method methodDesc = element.getClass().getMethod("isChecked"); //$NON-NLS-1$
            isChecked = (Boolean) methodDesc.invoke(element);
//            return isChecked;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        boolean is = false;
        if (isChecked)
        {
            MatchedObjectsComparisonNode node = extractMatchedNode(element);
            is = !(node == null || node.getNodeSide() == null)
                || (node != null && !node.getComparisonFlags().hasDiffsMainOther());
            is = !is;
        }
        return is;
    }

    /**
     * Возвращает {@code true} если элемент является узлом объекта конфигурации.
     */
    private static boolean isObject(Object element)
    {
        MatchedObjectsComparisonNode node = extractMatchedNode(element);
        if (node == null)
            return false;

        Long mainId  = node.getMainObjectId();
        Long otherId = node.getOtherObjectId();
        return (mainId  != null && mainId  != -1L)
            || (otherId != null && otherId != -1L);
    }

    /**
     * Возвращает {@code true} если объект присутствует только в одной стороне
     * сравнения (добавлен или удалён).
     */
    private static boolean isAddedOrDeleted(Object element)
    {
        boolean isCheckable = true;
        try
        {
            Method methodDesc = element.getClass().getMethod("isCheckable"); //$NON-NLS-1$
            isCheckable = (Boolean) methodDesc.invoke(element);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        boolean is = true;
        if (isCheckable)
        {
            MatchedObjectsComparisonNode node = extractMatchedNode(element);
            is = !(node == null || node.getNodeSide() == null)
                || (node != null && !node.getComparisonFlags().hasDiffsMainOther());
        }
        return is;
    }

    /**
     * Извлекает {@link MatchedObjectsComparisonNode} из обёртки элемента дерева.
     */
    private static MatchedObjectsComparisonNode extractMatchedNode(Object element)
    {
        if (retrieveMethodCache == null)
        {
            try
            {
                retrieveMethodCache = element.getClass().getMethod("retrieveComparisonNode"); //$NON-NLS-1$
            }
            catch (NoSuchMethodException e)
            {
                return null;
            }
        }
        Object raw;
        try
        {
            raw = retrieveMethodCache.invoke(element);
        }
        catch (Exception e)
        {
            return null;
        }
        if (raw instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) raw;
        if (element instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) element;
        return null;
    }

    // ---- Утилиты рефлексии ----

    private static AbstractTreeViewer getTreeViewer(IEditorPart editor)
    {
        Object view = getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView))
            return null;

        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null)
            return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return (viewer instanceof AbstractTreeViewer)
            ? (AbstractTreeViewer) viewer : null;
    }

    static Object getField(Object obj, String name)
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            try
            {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            catch (Exception ignored)            { return null; }
        }
        return null;
    }

    static Object invokeNoArg(Object o, String name)
    {
        if (o == null) return null;
        try
        {
            return o.getClass().getMethod(name).invoke(o);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}
