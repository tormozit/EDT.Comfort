package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

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

        // Определяем проект
        IProject project = Global.getActiveProject(page, false);
        if (project == null)
        {
            ToastNotification.show("Последние места", "Отсутствует активный проект");
            return null;
        }

        // Переходим по ссылке
        if (!GoToDefinition.jump(entry.navRef, shell, page, project))
        {
            ToastNotification.show("Последние места",
                "Не удалось перейти к:\n" + entry.displayName, 5000);
        }

        return null;
    }
}
