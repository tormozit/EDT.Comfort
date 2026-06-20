package tormozit;



import java.util.Map;



import org.eclipse.core.commands.AbstractHandler;

import org.eclipse.core.commands.ExecutionEvent;

import org.eclipse.core.commands.ExecutionException;

import org.eclipse.core.resources.IProject;

import org.eclipse.jface.viewers.ISelection;

import org.eclipse.jface.viewers.IStructuredSelection;

import org.eclipse.ui.IWorkbenchPage;

import org.eclipse.ui.IWorkbenchPart;

import org.eclipse.ui.IWorkbenchWindow;

import org.eclipse.ui.IViewPart;

import org.eclipse.ui.PlatformUI;

import org.eclipse.ui.commands.IElementUpdater;

import org.eclipse.ui.handlers.HandlerUtil;

import org.eclipse.ui.menus.UIElement;



/**

 * Добавление выделения навигатора в активный add-target набор проекта.

 */

public final class NavigatorAddToObjectSetHandler extends AbstractHandler implements IElementUpdater

{

    @Override

    public Object execute(ExecutionEvent event) throws ExecutionException

    {

        ObjectSets.SetDef target = currentTargetSet(event);

        if (target == null)

            return null;

        IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);

        if (selection == null || selection.isEmpty())

            return null;

        ObjectSetsItems.addItemsToSet(target,

            ObjectSetsItems.fromNavigatorSelection(selection, target),

            HandlerUtil.getActiveShell(event));

        return null;

    }



    @Override

    public boolean isEnabled()

    {

        ObjectSets.SetDef target = currentTargetSet(null);

        if (target == null)

            return false;

        IStructuredSelection selection = currentNavigatorSelection();

        return selection != null && !selection.isEmpty()

            && !ObjectSetsItems.fromNavigatorSelection(selection, target).isEmpty();

    }



    @Override

    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters)

    {

        ObjectSets.SetDef target = currentTargetSet(null);

        if (target == null)

        {

            element.setText("Добавить в набор <…>"); //$NON-NLS-1$

            element.setTooltip("Выберите активный набор в панели «Наборы объектов»" + Global.pluginSignForTooltip()); //$NON-NLS-1$

            return;

        }

        element.setText("Добавить в набор <" + target.name + ">"); //$NON-NLS-1$ //$NON-NLS-2$

        element.setTooltip("Добавить выбранный объект метаданных в набор «" //$NON-NLS-1$

            + target.name + "»" + Global.pluginSignForTooltip()); //$NON-NLS-1$

    }



    private static ObjectSets.SetDef currentTargetSet(ExecutionEvent event)

    {

        String projectName = null;

        if (event != null)

        {

            IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);

            projectName = ObjectSetsItems.projectNameFromNavigatorSelection(selection);

        }

        if (projectName == null || projectName.isBlank())

        {

            IStructuredSelection selection = currentNavigatorSelection();

            projectName = ObjectSetsItems.projectNameFromNavigatorSelection(selection);

        }

        if (projectName == null || projectName.isBlank())

        {

            IProject project = ActiveProjectTracker.resolveContextProject(currentPage());

            projectName = project != null ? project.getName() : null;

        }

        if (projectName == null || projectName.isBlank())

            return null;

        ObjectSetsAddTargetState.getInstance().ensureForProject(projectName);

        return ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);

    }



    private static IWorkbenchPage currentPage()

    {

        if (!PlatformUI.isWorkbenchRunning())

            return null;

        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();

        return window != null ? window.getActivePage() : null;

    }



    private static IStructuredSelection currentNavigatorSelection()

    {

        if (!PlatformUI.isWorkbenchRunning())

            return null;

        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();

        if (window == null)

            return null;

        IWorkbenchPage page = window.getActivePage();

        if (page == null)

            return null;

        IWorkbenchPart part = page.getActivePart();

        if (part == null || !Global.NAVIGATOR_VIEW_ID.equals(part.getSite().getId()))

        {

            IViewPart nav = page.findView(Global.NAVIGATOR_VIEW_ID);

            part = nav;

        }

        if (part == null)

            return null;

        ISelection selection = part.getSite().getSelectionProvider().getSelection();

        return selection instanceof IStructuredSelection structured ? structured : null;

    }

}


