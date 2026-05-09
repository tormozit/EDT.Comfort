package tormozit.edt.compare.open_object.handlers;

import java.lang.reflect.Field;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;

/**
 * Разворачивает дерево сравнения EDT, пропуская добавленные/удалённые объекты.
 *
 * Логика:
 *   - MatchedObjectsComparisonNode с обеими сторонами (mainObjectId и otherObjectId)
 *     → объект изменён → раскрываем, рекурсивно обходим детей
 *   - MatchedObjectsComparisonNode только с одной стороной
 *     → объект добавлен или удалён → не раскрываем (и детей не смотрим)
 *   - Узлы других типов (группы, свойства) → раскрываем, обходим рекурсивно
 */
public class ExpandExceptAddedDeletedHandler
    extends AbstractHandler
{

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor == null)
            return null;

        return expand(editor);
    }

    /**
     * @param editor
     * @return
     */
    public static Object expand(IEditorPart editor)
    {
        AbstractTreeViewer viewer = getTreeViewer(editor);
        if (viewer == null)
            return null;

        ITreeContentProvider cp = (ITreeContentProvider)viewer.getContentProvider();
        if (cp == null)
            return null;

        // Сворачиваем всё, затем раскрываем избирательно
        viewer.collapseAll();

        for (Object root : cp.getElements(viewer.getInput()))
        {
            expandSelectively(viewer, cp, root);
        }

        return null;
    }

    /**
     * Рекурсивно раскрывает узел, если он не является добавленным/удалённым.
     */
    private static void expandSelectively(AbstractTreeViewer viewer, ITreeContentProvider cp, Object element)
    {

        if (isAddedOrDeleted(element))
            return;

        if (!cp.hasChildren(element))
            return;

        // Раскрываем на один уровень — это загружает дочерние элементы в вьюер
        viewer.expandToLevel(element, 1);

        for (Object child : cp.getChildren(element))
        {
            expandSelectively(viewer, cp, child);
        }
    }

    /**
     * Возвращает true, если элемент является объектом, присутствующим
     * только в одной стороне сравнения (добавлен или удалён).
     */
    private static boolean isAddedOrDeleted(Object element)
    {
        Object raw = invokeNoArg(element, "retrieveComparisonNode");
        if (!(raw instanceof MatchedObjectsComparisonNode))
            return false;

        MatchedObjectsComparisonNode node = (MatchedObjectsComparisonNode)raw;
        Long mainId = node.getMainObjectId();
        Long otherId = node.getOtherObjectId();
        boolean hasMain = mainId != null && mainId != -1L;
        boolean hasOther = otherId != null && otherId != -1L;

        // Добавлен = нет главной стороны; удалён = нет другой стороны
        return false
            || !hasMain && hasOther 
            || hasMain && !hasOther;
    }

    // ---- Получение TreeViewer из редактора ----

    private static AbstractTreeViewer getTreeViewer(IEditorPart editor)
    {
        Object view = getField(editor, "comparisonView");
        if (!(view instanceof DtComparisonView))
            return null;

        Object treeControl = ((DtComparisonView)view).getTreeControl();
        if (treeControl == null)
            return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer");
        return (viewer instanceof AbstractTreeViewer) ? (AbstractTreeViewer)viewer : null;
    }

    // ---- Утилиты рефлексии ----

    private static Object getField(Object obj, String name)
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
            catch (NoSuchFieldException ignored)
            {
                cls = cls.getSuperclass();
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object o, String name)
    {
        if (o == null)
            return null;
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
