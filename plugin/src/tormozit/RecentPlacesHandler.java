package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

/**
 * Глобальная команда «Последние места» (CTRL+3 по умолчанию).
 *
 * <p>Открывает диалог {@link RecentPlacesDialog}, в котором пользователь
 * выбирает место для перехода. После выбора выполняется переход через
 * {@link GoToDefinition#jump}.
 */
public class RecentPlacesHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        Shell          shell  = HandlerUtil.getActiveShell(event);
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) return null;
        IWorkbenchPage page = window.getActivePage();

        if (RecentPlaces.getInstance().size() == 0)
        {
            ToastNotification.show("Последние места",
                "История пуста. Откройте несколько методов или объектов метаданных.", 4000);
            return null;
        }

        RecentPlacesDialog dialog = new RecentPlacesDialog(shell);
        if (dialog.open() != Dialog.OK) return null;

        RecentPlaces.Entry entry = dialog.getSelectedEntry();
        if (entry == null) return null;

        IProject project = resolveProject(entry, page);
        if (project == null)
        {
            if (entry.projectName != null && !entry.projectName.isBlank())
                ToastNotification.show("Последние места",
                    "Проект «" + entry.projectName + "» не найден в рабочей области");
            else
                ToastNotification.show("Последние места", "Отсутствует активный проект");
            return null;
        }

        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(project);
        if (v8Project == null)
        {
            ToastNotification.show("Последние места",
                "Проект " + project.getName() + " не открыт");
            return null;
        }

        if (!GoToDefinition.jump(entry.navRef, shell, page, project))
        {
            if (!GoToDefinition.consumeJumpCancelled())
            {
                ToastNotification.show("Последние места",
                    "Не удалось перейти к:\n" + entry.displayName, 5000);
            }
        }

        return null;
    }

    /**
     * Проект из строки «Последние места»; если имя не задано — активный проект EDT.
     */
    private static IProject resolveProject(RecentPlaces.Entry entry, IWorkbenchPage page)
    {
        if (entry.projectName != null && !entry.projectName.isBlank())
        {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(entry.projectName);
            if (p != null && p.exists())
                return p;
            return null;
        }
        return Global.getActiveProject(page, false);
    }
}
