package tormozit;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.jdt.annotation.Nullable;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;

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
        EObject eObject = resolveEObject(selection, null, true);
        
        if (eObject != null && editor != null) {
            
            // Опционально: можно пушить EObject в глобальную E4 шину
            ESelectionService selectionService = editor.getSite().getService(ESelectionService.class);
            if (selectionService != null) {
                selectionService.setSelection(eObject);
            }
            
            // Оповещаем навигатор
            IWorkbenchPage page = editor.getSite().getPage();
            IViewPart view = page.findView("com._1c.g5.v8.dt.ui2.navigator"); // ID Навигатора EDT
            if (view instanceof CommonNavigator) {
                CommonNavigator navigator = (CommonNavigator) view;
                 if (ifForced || isLinkingEnabled(navigator)) {
                    Display.getDefault().asyncExec(() -> {
                        // Формируем выделение для навигатора
                        StructuredSelection navSelection = new StructuredSelection(eObject);
                        
                        // 1. Показываем объект в дереве навигатора
                        navigator.selectReveal(navSelection);
                        
                        Class<?> classMPart = MPart.class;
                        Class<?> classEPartService = EPartService.class;
                        Object partService = navigator.getSite().getService(classEPartService);
                        Object mPart = navigator.getSite().getService(classMPart);
                        if (partService != null && mPart != null) 
                        {
                            try
                            {
                                // Ищем метод activate именно в EPartService
                                Method activateMethod = partService.getClass().getMethod("activate", classMPart, boolean.class);
                                
                                // Вызываем активацию с параметром false (без перехвата фокуса OS)
                                activateMethod.invoke(partService, mPart, false);
                                activateMethod.invoke(partService, editor.getSite().getService(classMPart), false);
                            }
                            catch (Exception ignored)
                            {
                            }
                        }
                    });
                }
            }
        }
    }
    
    private boolean isLinkingEnabled(CommonNavigator navigator)
    {
        Object result = Global.call(navigator, "isLinkingEnabled");
        if (result instanceof Boolean) return (Boolean) result;
        result = Global.getField(navigator, "isLinkingEnabled");
        return result instanceof Boolean && (Boolean) result;
    }
    
    public EObject resolveEObject(ISelection selection, @Nullable ComparisonSide side, boolean allowNearestParent)
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
            MatchedObjectsComparisonNode matchedNode = resolveMatchedNode(currentElement);
            if (matchedNode != null)
            {
                Long bmId = null;
                if (side == null || side == ComparisonSide.MAIN)
                    bmId = matchedNode.getMainObjectId();
                if (bmId == null || bmId == -1L)
                {
                    if (side == null || side == ComparisonSide.OTHER)
                        bmId = matchedNode.getOtherObjectId();
                }

                if (bmId != null && bmId != -1L)
                {
                    EObject eObject = CompareConfigOpenObjectHandler.getEObject(session, bmId, matchedNode);
                    if (eObject != null)
                    {
                        return eObject;
                    }
                }
            }

            // Если флаг не установлен, сразу прерываем поиск
            if (!allowNearestParent)
            {
                break;
            }

            // Поднимаемся на уровень выше
            currentElement = getParentElement(currentElement);
        }

        return null;
    }

    private Object getParentElement(Object element)
    {
        Object parentViaReflection = Global.call(element, "getParent");
        if (parentViaReflection != null) 
        {
            return parentViaReflection;
        }
        return null;
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