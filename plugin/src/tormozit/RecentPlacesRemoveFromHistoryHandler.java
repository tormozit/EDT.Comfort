package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

/**
 * «Удалить из истории» для выделенных строк панели «Последние места» (Del в таблице).
 */
public final class RecentPlacesRemoveFromHistoryHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        RecentPlacesView view = RecentPlacesView.getActiveInstance();
        if (view == null)
            return null;

        List<String> keys = new ArrayList<>();
        for (RecentPlaces.Entry entry : view.getSelectedEntries())
        {
            if (entry.key != null && !entry.key.isBlank())
                keys.add(entry.key);
        }
        if (keys.isEmpty())
            return null;

        int removed = RecentPlaces.getInstance().removeKeys(keys);
        if (removed > 0)
            ToastNotification.show("Последние места", "Удалено " + removed, 3000); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    @Override
    public boolean isEnabled()
    {
        RecentPlacesView view = RecentPlacesView.getActiveInstance();
        return view != null && !view.getSelectedEntries().isEmpty();
    }
}
