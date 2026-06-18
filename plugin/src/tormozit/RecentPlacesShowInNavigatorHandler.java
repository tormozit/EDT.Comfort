package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

/**
 * «Показать в навигаторе» для выбранной строки панели «Последние места» (CTRL+T).
 */
public final class RecentPlacesShowInNavigatorHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        RecentPlacesView view = RecentPlacesView.getActiveInstance();
        if (view == null)
            return null;
        RecentPlaces.Entry entry = view.getSelectedEntry();
        if (entry == null)
            return null;

        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        IProject project = RecentPlacesHandler.resolveProject(entry, page);
        if (project == null)
        {
            if (entry.projectName != null && !entry.projectName.isBlank())
                ToastNotification.show("Последние места",
                    "Проект «" + entry.projectName + "» не найден в рабочей области"); //$NON-NLS-1$ //$NON-NLS-2$
            else
                ToastNotification.show("Последние места", "Отсутствует активный проект"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(project);
        if (v8Project == null)
        {
            ToastNotification.show("Последние места",
                "Проект " + project.getName() + " не открыт"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        String mdRef = RecentPlacesKeys.mdObjectRef(entry);
        if (mdRef == null || mdRef.isBlank())
        {
            ToastNotification.show("Последние места",
                "Не удалось определить объект для навигатора", 4000); //$NON-NLS-1$
            return null;
        }

        EObject eObject = GoToDefinition.resolveEObjectForFullName(mdRef, page, project);
        if (eObject == null)
        {
            ToastNotification.show("Последние места",
                "Не удалось найти в навигаторе:\n" + entry.displayName, 5000); //$NON-NLS-1$
            return null;
        }

        NavigatorReveal.reveal(eObject, true);
        return null;
    }
}
