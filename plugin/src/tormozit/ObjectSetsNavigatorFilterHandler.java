package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;

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
        boolean allEmpty = ObjectSets.getInstance().areAllSetsEmpty();
        if (turningOn && allEmpty)
        {
            ObjectSetsNavigatorFilterSupport.setActive(false);
            ObjectSetsHandler.showView(IWorkbenchPage.VIEW_ACTIVATE);
            ObjectSetsNavigatorFilterSupport.refreshNavigators();
            return null;
        }
        ObjectSetsNavigatorFilterSupport.setActive(turningOn);
        if (turningOn)
            ObjectSetsHandler.showView(IWorkbenchPage.VIEW_ACTIVATE);
        ObjectSetsNavigatorFilterSupport.refreshNavigators();
        return null;
    }
}


