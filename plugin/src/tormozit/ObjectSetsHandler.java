package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

/**
 * Команда открытия панели «Наборы объектов».
 */
public final class ObjectSetsHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        showView(IWorkbenchPage.VIEW_ACTIVATE);
        return null;
    }

    public static boolean isViewOpen(IWorkbenchPage page)
    {
        if (page == null)
            return false;
        IViewReference ref = page.findViewReference(ObjectSetsView.VIEW_ID);
        return ref != null;
    }

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
                var view = page.showView(ObjectSetsView.VIEW_ID, null, mode);
                if (view != null && mode == IWorkbenchPage.VIEW_ACTIVATE)
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
}
