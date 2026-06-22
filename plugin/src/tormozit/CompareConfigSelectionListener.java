package tormozit;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.ui.IEditorPart;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Слушатель выделения для редактора сравнения, транслирующий узел дерева
 * в Навигатор 1C:EDT.
 *
 * <p>Подписывается непосредственно на tree viewer редактора EDT. Не перекрывает
 * Site SelectionProvider, чтобы не ломать родные команды контекстного меню.
 */
public class CompareConfigSelectionListener implements ISelectionChangedListener
{
    private final IEditorPart editor;

    public CompareConfigSelectionListener(IEditorPart editor)
    {
        this.editor = editor;
    }

    public void setTreeViewer(AbstractTreeViewer treeViewer)
    {
        if (treeViewer != null)
            treeViewer.addSelectionChangedListener(this);
    }

    @Override
    public void selectionChanged(SelectionChangedEvent event)
    {
       showObjectInNavigator(event.getSelection(), false);
    }

    public void showObjectInNavigator(ISelection selection, boolean ifForced)
    {
        EObject eObject = resolveNavigatorEObject(selection, null);
        if (eObject != null && editor != null)
            NavigatorReveal.reveal(eObject, ifForced, editor);
    }

    /**
     * Резолв для навигатора: подъём по родителям дерева сравнения до первого {@link MdObject}.
     */
    public EObject resolveNavigatorEObject(ISelection selection, ComparisonSide side)
    {
        if (!(selection instanceof IStructuredSelection))
            return null;

        Object element = ((IStructuredSelection) selection).getFirstElement();
        if (element == null)
            return null;

        IComparisonSession session = getSession(editor);
        if (session == null)
            return null;

        Object currentElement = element;
        while (currentElement != null)
        {
            EObject eObject = resolveEObjectAtElement(session, currentElement, side);
            if (eObject instanceof MdObject)
                return eObject;

            currentElement = getParentElement(currentElement);
        }

        return null;
    }

    public EObject resolveEObject(ISelection selection, ComparisonSide side, boolean allowNearestParent)
    {
        if (!(selection instanceof IStructuredSelection))
            return null;

        Object element = ((IStructuredSelection) selection).getFirstElement();
        if (element == null)
            return null;

        IComparisonSession session = getSession(editor);
        if (session == null)
            return null;

        Object currentElement = element;

        while (currentElement != null)
        {
            EObject eObject = resolveEObjectAtElement(session, currentElement, side);
            if (eObject != null)
                return eObject;

            if (!allowNearestParent)
                break;

            currentElement = getParentElement(currentElement);
        }

        return null;
    }

    private static EObject resolveEObjectAtElement(
            IComparisonSession session, Object element, ComparisonSide side)
    {
        MatchedObjectsComparisonNode matchedNode = resolveMatchedNode(element);
        if (matchedNode == null)
            return null;

        Long bmId = null;
        if (side == null || side == ComparisonSide.MAIN)
            bmId = matchedNode.getMainObjectId();
        if (bmId == null || bmId == -1L)
        {
            if (side == null || side == ComparisonSide.OTHER)
                bmId = matchedNode.getOtherObjectId();
        }

        if (bmId == null || bmId == -1L)
            return null;

        return CompareConfigOpenObjectHandler.getEObject(session, bmId, matchedNode);
    }

    private Object getParentElement(Object element)
    {
        if (element instanceof IPartialModelNode partial)
            return partial.getParent();
        return Global.call(element, "getParent"); //$NON-NLS-1$
    }

    public static IComparisonSession getSession(IEditorPart editor)
    {
        Object list = Global.getField(editor, "comparisonArtifactsList");
        if (list instanceof List) {
            for (Object artifact : (List<?>) list) {
                Object session = Global.call(artifact, "getSession");
                if (session instanceof IComparisonSession) {
                    return (IComparisonSession) session;
                }
            }
        }
        Object root = Global.getField(editor, "root");
        if (root != null) {
            Object session = Global.call(root, "getComparisonSession");
            if (session instanceof IComparisonSession) return (IComparisonSession) session;
        }
        return null;
    }

    public static MatchedObjectsComparisonNode resolveMatchedNode(Object element)
    {
        Object node = Global.call(element, "retrieveComparisonNode");
        if (node instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) node;
        if (element instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) element;
        return null;
    }
}
