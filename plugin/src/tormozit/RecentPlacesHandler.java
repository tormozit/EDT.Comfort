package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

/**
 * Глобальная команда «Последние места» (CTRL+3 по умолчанию).
 *
 * <p>Открывает панель {@link RecentPlacesView}; переход к месту — Enter / двойной клик.
 */
public class RecentPlacesHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        if (RecentPlaces.getInstance().size() == 0)
        {
            ToastNotification.show("Последние места",
                "История пуста. Откройте несколько методов или объектов метаданных.", 4000);
            return null;
        }

        showView(IWorkbenchPage.VIEW_ACTIVATE);
        return null;
    }

    /** Открывает и активирует панель «Последние места». */
    public static void showView(int mode)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable open = () ->
        {
            try
            {
                if (!PlatformUI.isWorkbenchRunning())
                    return;
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null)
                    return;
                IWorkbenchPage page = window.getActivePage();
                if (page == null)
                    return;
                var view = page.showView(RecentPlacesView.VIEW_ID, null, mode);
                if (view != null)
                    page.bringToTop(view);
            }
            catch (PartInitException ignored)
            {
                // панель недоступна
            }
        };
        if (display.getThread() == Thread.currentThread())
            open.run();
        else
            display.asyncExec(open);
    }

    /**
     * Переход к записи «Последние места» через {@link GoToDefinition#jump}.
     */
    public static void jumpToEntry(RecentPlaces.Entry entry, Shell shell, IWorkbenchPage page)
    {
        if (entry == null || page == null)
            return;

        IProject project = resolveProject(entry, page);
        if (project == null)
        {
            if (entry.projectName != null && !entry.projectName.isBlank())
                ToastNotification.show("Последние места",
                    "Проект «" + entry.projectName + "» не найден в рабочей области"); //$NON-NLS-1$ //$NON-NLS-2$
            else
                ToastNotification.show("Последние места", "Отсутствует активный проект"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(project);
        if (v8Project == null)
        {
            ToastNotification.show("Последние места",
                "Проект " + project.getName() + " не открыт"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        if (!GoToDefinition.jump(entry.navRef, shell, page, project)
                && !GoToDefinition.consumeJumpCancelled())
        {
            ToastNotification.show("Последние места",
                "Не удалось перейти к:\n" + entry.displayName, 5000); //$NON-NLS-1$
        }
    }

    /**
     * Проект из строки «Последние места»; если имя не задано — активный проект EDT.
     */
    public static IProject resolveProject(RecentPlaces.Entry entry, IWorkbenchPage page)
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
