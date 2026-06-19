package tormozit;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Добавление выделенных «Последних мест» в активный add-target набор проекта записи.
 */
public final class RecentPlacesAddToObjectSetHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        RecentPlacesView view = RecentPlacesView.getActiveInstance();
        if (view == null)
            return null;
        ObjectSets.SetDef target = targetForSelectedEntries(view);
        if (target == null)
            return null;
        List<RecentPlaces.Entry> entries = view.getSelectedEntries();
        ObjectSetsItems.addItemsToSet(target,
            ObjectSetsItems.fromRecentPlaceEntries(entries, target),
            HandlerUtil.getActiveShell(event));
        return null;
    }

    @Override
    public boolean isEnabled()
    {
        RecentPlacesView view = RecentPlacesView.getActiveInstance();
        return view != null && targetForSelectedEntries(view) != null && view.getSelectedEntry() != null;
    }

    private static ObjectSets.SetDef targetForSelectedEntries(RecentPlacesView view)
    {
        List<RecentPlaces.Entry> entries = view.getSelectedEntries();
        if (entries.isEmpty())
            return null;
        String projectName = entries.get(0).projectName;
        if (projectName == null || projectName.isBlank())
        {
            IProject project = ActiveProjectTracker.resolveContextProject(view.getSite().getPage());
            projectName = project != null ? project.getName() : null;
        }
        if (projectName == null || projectName.isBlank())
            return null;
        ObjectSetsAddTargetState.getInstance().ensureForProject(projectName);
        return ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
    }
}
