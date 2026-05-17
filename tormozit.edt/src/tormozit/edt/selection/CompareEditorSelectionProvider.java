package tormozit.edt.selection;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;

import tormozit.edt.Reflect;
import tormozit.edt.handlers.OpenObjectHandler;

/**
 * ISelectionProvider для редактора сравнения, транслирующий узел дерева
 * (MatchedObjectsComparisonNode) в соответствующий EObject.
 *
 * <p>Устанавливается через {@code editor.getSite().setSelectionProvider()} и
 * подписывается непосредственно на тот же tree viewer, что и редактор EDT.
 * Каждый раз, когда пользователь выбирает объект конфигурации в дереве
 * сравнения, этот провайдер публикует resolved {@link EObject} через
 * стандартный механизм {@link org.eclipse.ui.ISelectionService} рабочей
 * среды Eclipse.
 *
 * <p>Благодаря этому любая панель, подписанная на
 * {@link org.eclipse.ui.ISelectionService} (панели «История», «Ошибки
 * конфигурации» и другие), автоматически обновляет свой отбор при
 * активации объекта в дереве сравнения конфигураций.
 *
 * <p>Если выбранный элемент не является {@link MatchedObjectsComparisonNode}
 * (например, свойство объекта) или EObject не удалось получить — публикуется
 * пустое выделение {@link StructuredSelection#EMPTY}.
 */
public class CompareEditorSelectionProvider
        implements ISelectionProvider, ISelectionChangedListener
{
    private final IEditorPart editor;
    private final List<ISelectionChangedListener> listeners = new ArrayList<>();
    private ISelection currentSelection = StructuredSelection.EMPTY;

    /**
     * @param editor редактор сравнения EDT
     */
    public CompareEditorSelectionProvider(IEditorPart editor)
    {
        this.editor = editor;
    }

    /**
     * Подключает tree viewer к провайдеру.
     * Вызывается асинхронно из {@code hookEditor()} — после того, как EDT
     * создаёт {@code comparisonView} и tree viewer становится доступным.
     * К этому моменту workbench уже подключился к провайдеру через
     * {@code addSelectionChangedListener} (в {@code partActivated}),
     * поэтому события дерева будут корректно транслироваться в EObject
     * и доставляться через {@code ISelectionService} всем подписанным панелям.
     */
    public void setTreeViewer(AbstractTreeViewer treeViewer)
    {
        if (treeViewer != null)
            treeViewer.addSelectionChangedListener(this);
    }

    // ---- ISelectionProvider ----

    @Override
    public void addSelectionChangedListener(ISelectionChangedListener listener)
    {
        if (!listeners.contains(listener))
            listeners.add(listener);
    }

    @Override
    public void removeSelectionChangedListener(ISelectionChangedListener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public ISelection getSelection()
    {
        return currentSelection;
    }

    @Override
    public void setSelection(ISelection selection)
    {
        // Внешние вызовы setSelection не переносим на дерево,
        // т.к. не управляем им напрямую. Просто публикуем как есть.
        currentSelection = (selection != null) ? selection : StructuredSelection.EMPTY;
        fireSelectionChanged(currentSelection);
    }

    // ---- ISelectionChangedListener (слушаем tree viewer напрямую) ----

    @Override
    public void selectionChanged(SelectionChangedEvent event)
    {
       showObjectInNavigator(event.getSelection(), false);
    }

    public void showObjectInNavigator(ISelection selection, boolean ifForced)
    {
        EObject eObject = resolveEObject(selection);
        currentSelection = (eObject != null)
                ? new StructuredSelection(eObject)
                : StructuredSelection.EMPTY;
        fireSelectionChanged(currentSelection); // Бесполезно, т.к. слушателей всегда нет - они подписались на стандартный провайдер
        if (eObject != null && editor != null) {
            ESelectionService selectionService = editor.getSite().getService(ESelectionService.class);
            if (selectionService != null) {
                selectionService.setSelection(eObject);
            }
            
            // Оповещаем навигатор
            
//          IWorkbenchPage page = editor.getSite().getPage();
//          IWorkbenchWindow window = page.getWorkbenchWindow();
//          // Повторная активация текущего редактора может заставить LinkEditorAction проснуться.
//          page.activate(editor);
          
            IWorkbenchPage page = editor.getSite().getPage();
            IViewPart view = page.findView("com._1c.g5.v8.dt.ui2.navigator"); // ID Навигатора EDT
            if (view instanceof CommonNavigator) {
                CommonNavigator navigator = (CommonNavigator) view;
                 if (ifForced || isLinkingEnabled(navigator)) {
                    Display.getDefault().asyncExec(() -> {
                        // 1. Показываем объект в дереве навигатора
                        navigator.selectReveal(currentSelection);
    
                        //navigator.setFocus(); // Так будет небольшое мигание
                        
                        Class<?> classMPart = MPart.class;
                        Class<?> classEPartService = EPartService.class;
                        Object partService = navigator.getSite().getService(classEPartService);
                        Object mPart = navigator.getSite().getService(classMPart);
                        if (partService != null && mPart != null) 
                        {
                            try
                            {
                                // 4. Ищем метод activate именно в EPartService
                                // void activate(MPart part, boolean requiresFocus)
                                Method activateMethod = partService.getClass().getMethod("activate", classMPart, boolean.class);
                                
                                // 5. Вызываем активацию с параметром false (без перехвата фокуса OS)
                                activateMethod.invoke(partService, mPart, false);
                                activateMethod.invoke(partService, editor.getSite().getService(classMPart), false);
                            }
                            catch (Exception ignored)
                            {
                                // TODO Auto-generated catch block
                                //e.printStackTrace();
                            }
                        }
                    });
                }
            }
        }
    }
    
    /**
     * Проверка состояния кнопки "Link with Editor"
     */
    private boolean isLinkingEnabled(CommonNavigator navigator)
    {
        Object result = Reflect.call(navigator, "isLinkingEnabled");
        if (result instanceof Boolean) return (Boolean) result;
        result = Reflect.getField(navigator, "isLinkingEnabled");
        return result instanceof Boolean && (Boolean) result;
    }
    
    /**
     * Транслирует выделение дерева в EObject:
     * element → MatchedObjectsComparisonNode → bmId → EObject.
     * Возвращает {@code null}, если элемент не является объектом конфигурации
     * или EObject не удалось получить через сессию сравнения.
     */
    public EObject resolveEObject(ISelection selection)
    {
        //ISelection selection = editor.getSite().getSelectionProvider().getSelection();
        if (!(selection instanceof IStructuredSelection))
            return null;

        Object element = ((IStructuredSelection) selection).getFirstElement();
        if (element == null)
            return null;

        MatchedObjectsComparisonNode matchedNode = resolveMatchedNode(element);
        if (matchedNode == null)
            return null;

        IComparisonSession session = getSession(editor);
        if (session == null)
            return null;

        Long bmId = matchedNode.getMainObjectId();
        if (bmId == null || bmId == -1L)
            bmId = matchedNode.getOtherObjectId();
        if (bmId == null || bmId == -1L)
            return null;

        EObject eObject = OpenObjectHandler.getEObject(session, bmId, matchedNode);
        return eObject;
    }
    
    public static IComparisonSession getSession(IEditorPart editor)
    {
        // Из comparisonArtifactsList
        Object list = Reflect.getField(editor, "comparisonArtifactsList");
        if (list instanceof List) {
            for (Object artifact : (List<?>) list) {
                Object session = Reflect.call(artifact, "getSession");
                if (session instanceof IComparisonSession) {
                    return (IComparisonSession) session;
                }
            }
        }
        // Fallback: из root
        Object root = Reflect.getField(editor, "root");
        if (root != null) {
            Object session = Reflect.call(root, "getComparisonSession");
            if (session instanceof IComparisonSession) return (IComparisonSession) session;
        }
        return null;
    }
    
    /**
     * Разворачивает произвольный элемент дерева в
     * {@link MatchedObjectsComparisonNode}, поддерживая:
     * <ul>
     *   <li>PartialMatchedObjectComparisonNode через рефлексию
     *       {@code retrieveComparisonNode()}</li>
     *   <li>прямое приведение типа</li>
     * </ul>
     */
    private static MatchedObjectsComparisonNode resolveMatchedNode(Object element)
    {
        Object node = Reflect.call(element, "retrieveComparisonNode"); //$NON-NLS-1$
        if (node instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) node;
        if (element instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) element;
        return null;
    }

    private void fireSelectionChanged(ISelection selection)
    {
        if (listeners.isEmpty())
            return;
        SelectionChangedEvent event = new SelectionChangedEvent(this, selection);
        for (ISelectionChangedListener l : new ArrayList<>(listeners))
        {
            try
            {
                l.selectionChanged(event);
            }
            catch (Exception ignored)
            {
            }
        }
    }
}
