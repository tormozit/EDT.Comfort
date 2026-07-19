package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;

/**
 * Показывает файл или папку выбранного элемента навигатора EDT в Project Explorer.
 */
public class NavigatorShowInProjectStructureHandler extends AbstractHandler
{
    static final String PROJECT_EXPLORER_ID = "org.eclipse.ui.navigator.ProjectExplorer"; //$NON-NLS-1$

    @Override
    public void setEnabled(Object evaluationContext)
    {
        super.setEnabled(evaluationContext);
        if (!isHandled())
            return;
        setBaseEnabled(NavigatorResourceResolver.resolveFirst(navigatorSelection()) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IStructuredSelection selection = menuSelection(event);
        if (selection == null)
            selection = navigatorSelection();
        showInProjectStructure(selection);
        return null;
    }

    static void showInProjectStructure(IStructuredSelection selection)
    {
        IResource resource = NavigatorResourceResolver.resolveFirst(selection);
        if (resource == null)
            return;

        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;

        IViewPart view = page.findView(PROJECT_EXPLORER_ID);
        if (view == null)
        {
            try
            {
                view = page.showView(PROJECT_EXPLORER_ID);
            }
            catch (Exception ignored)
            {
                return;
            }
        }
        if (!(view instanceof CommonNavigator navigator))
            return;

        navigator.selectReveal(new StructuredSelection(resource));
        page.activate(view);
    }

    private static IStructuredSelection menuSelection(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty())
            return structured;
        return HandlerUtil.getCurrentStructuredSelection(event);
    }

    private static IStructuredSelection navigatorSelection()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
        if (!(view instanceof CommonNavigator))
            return null;
        ISelection selection = view.getSite().getSelectionProvider().getSelection();
        return selection instanceof IStructuredSelection structured ? structured : null;
    }
}
