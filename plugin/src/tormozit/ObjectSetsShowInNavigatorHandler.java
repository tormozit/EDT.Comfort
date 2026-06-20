package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

/**
 * «Показать в навигаторе» для текущей строки панели «Наборы объектов» (CTRL+T).
 */
public final class ObjectSetsShowInNavigatorHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        ObjectSetsView view = ObjectSetsView.getActiveInstance();
        if (view == null)
            return null;
        view.showSelectedInNavigator();
        return null;
    }
}
