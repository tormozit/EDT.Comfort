package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

/**
 * Команда «Открыть скелет коллекции» — тест UI без отладки.
 */
public final class ComfortCollectionSkeletonHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        ComfortCollectionSkeletonWindow.open();
        return null;
    }
}
