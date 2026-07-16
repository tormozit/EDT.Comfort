package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;

public final class GitAddToObjectSetHandler extends AbstractHandler implements IElementUpdater
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchPage page = activePage();
        if (page == null)
            return null;

        IWorkbenchPart part = page.getActivePart();
        if (part == null || !GitChangedFileMenuHook.isGitView(part))
            return null;

        IStructuredSelection selection = resolveSelection(page);
        if (selection == null || selection.isEmpty())
            return null;

        // Определяем проект из первого подходящего файла selection
        String projectName = null;
        List<ObjectSets.Item> items = new ArrayList<>();
        for (Object element : selection.toList())
        {
            IFile file = GitChangedFileMenuHook.resolveFile(part, element);
            if (file == null || !file.exists())
                continue;
            if (projectName == null)
                projectName = file.getProject().getName();

            EObject eObject = GitChangedFileMenuHook.resolveEObject(file);
            if (eObject == null)
                continue;

            ObjectSets.Item item = ObjectSetsItems.fromEObject(eObject, projectName);
            if (item != null)
                items.add(item);
        }

        if (projectName == null || items.isEmpty())
            return null;

        ObjectSetsAddTargetState.getInstance().ensureForProject(projectName);
        ObjectSets.SetDef target = ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
        if (target == null)
            return null;

        ObjectSetsItems.addItemsToSet(target, items, HandlerUtil.getActiveShell(event));
        return null;
    }

    @Override
    public boolean isEnabled()
    {
        ObjectSets.SetDef target = currentTargetSet();
        if (target == null)
            return false;
        return true;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters)
    {
        ObjectSets.SetDef target = currentTargetSet();
        if (target == null)
        {
            element.setText("Добавить в набор <\u2026>");
            element.setTooltip("Выберите активный набор в панели \u00abНаборы объектов\u00bb" + Global.pluginSignForTooltip());
            return;
        }
        element.setText("Добавить в набор <" + target.name + ">");
        element.setTooltip("Добавить выбранные объекты метаданных в набор \u00ab"
            + target.name + "\u00bb" + Global.pluginSignForTooltip());
    }

    private static ObjectSets.SetDef currentTargetSet()
    {
        IWorkbenchPage page = activePage();
        if (page == null)
            return null;
        IWorkbenchPart part = page.getActivePart();
        if (part == null || !GitChangedFileMenuHook.isGitView(part))
            return null;

        IStructuredSelection selection = resolveSelection(page);
        if (selection == null || selection.isEmpty())
            return null;

        // Проект из первого подходящего файла
        String projectName = null;
        for (Object element : selection.toList())
        {
            IFile file = GitChangedFileMenuHook.resolveFile(part, element);
            if (file != null && file.exists())
            {
                projectName = file.getProject().getName();
                break;
            }
        }

        if (projectName == null)
        {
            IProject project = ActiveProjectTracker.resolveContextProject(page);
            if (project == null)
                return null;
            projectName = project.getName();
        }

        ObjectSetsAddTargetState.getInstance().ensureForProject(projectName);
        return ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
    }

    private static IStructuredSelection resolveSelection(IWorkbenchPage page)
    {
        // 1) Snapshot, сохранённый GitChangedFileMenuHook при открытии меню (самый надёжный)
        if (GitChangedFileMenuHook.multiSelectionSnapshot != null && !GitChangedFileMenuHook.multiSelectionSnapshot.isEmpty())
            return GitChangedFileMenuHook.multiSelectionSnapshot;
        // 2) Выделение из Table/Tree под фокусом
        ISelection fs = GitChangedFileMenuHook.selectionFromFocusControl();
        if (fs instanceof IStructuredSelection ss && !ss.isEmpty())
            return ss;
        // 3) Selection provider active part / page
        return getSelection(page);
    }

    private static IWorkbenchPage activePage()
    {
        try
        {
            var wb = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            return wb != null ? wb.getActivePage() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static IStructuredSelection getSelection(IWorkbenchPage page)
    {
        if (page.getSelection() instanceof IStructuredSelection s)
            return s;
        IWorkbenchPart part = page.getActivePart();
        if (part != null && part.getSite() != null)
        {
            var provider = part.getSite().getSelectionProvider();
            if (provider != null && provider.getSelection() instanceof IStructuredSelection s)
                return s;
        }
        return null;
    }
}
