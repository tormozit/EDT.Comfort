package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Toggle фильтра навигатора по активному набору объектов (жёлтая звезда).
 * При непустом наборе вытесняет штатный фильтр подсистем.
 */
public final class ObjectSetsNavigatorFilterHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        boolean wasActive = ObjectSetsNavigatorFilterSupport.isActive();
        boolean turningOn = !wasActive;
        if (!turningOn)
        {
            ObjectSetsNavigatorFilterSupport.setActive(false);
            ObjectSetsNavigatorFilterSupport.refreshNavigators();
            return null;
        }

        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        boolean allActiveEmpty =
            ObjectSetsAddTargetState.getInstance().areAllAddTargetSetsEmpty();
        boolean panelOpen = ObjectSetsHandler.isViewOpen(page);

        if (allActiveEmpty)
        {
            ObjectSetsNavigatorFilterSupport.setActive(false);
            ObjectSetsHandler.showView(IWorkbenchPage.VIEW_ACTIVATE);
        }
        else
        {
            ObjectSetsNavigatorFilterSupport.setActive(true);
            if (!panelOpen)
                ObjectSetsHandler.showView(IWorkbenchPage.VIEW_VISIBLE);
        }
        ObjectSetsNavigatorFilterSupport.refreshNavigators();
        return null;
    }
}
