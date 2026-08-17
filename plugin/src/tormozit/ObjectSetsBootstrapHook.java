package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;

/**
 * При старте workbench и при создании/открытии проекта гарантирует набор «Основной» для каждого проекта.
 */
public final class ObjectSetsBootstrapHook implements IStartup
{
    private static boolean projectListenerInstalled;

    @Override
    public void earlyStartup()
    {
        installProjectListener();
        Display display = Display.getDefault();
        if (display == null)
            return;
        display.asyncExec(() ->
        {
            ObjectSets.getInstance().ensureDefaultSetsForAllOpenProjects();
            InfobaseChangedObjects.installApplicationRemovalListener();
            pruneRemovedApplicationSetsForAllOpenProjects();
        });
    }

    /**
     * Приложение могли удалить, пока EDT была закрыта: набор «&lt;Измененные <i>ИмяБазы</i>&gt;»
     * по такой базе больше не нужен.
     */
    private static void pruneRemovedApplicationSetsForAllOpenProjects()
    {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (project.isOpen())
                InfobaseChangedObjects.pruneRemovedApplicationSets(project.getName());
        }
    }

    private static void installProjectListener()
    {
        if (projectListenerInstalled)
            return;
        projectListenerInstalled = true;
        ResourcesPlugin.getWorkspace().addResourceChangeListener(new ProjectDefaultSetListener());
    }

    private static final class ProjectDefaultSetListener implements IResourceChangeListener
    {
        @Override
        public void resourceChanged(IResourceChangeEvent event)
        {
            if (event.getType() != IResourceChangeEvent.POST_CHANGE)
                return;
            IResourceDelta root = event.getDelta();
            if (root == null)
                return;
            List<String> openedProjectNames = new ArrayList<>();
            List<String> closedProjectNames = new ArrayList<>();
            List<String> removedProjectNames = new ArrayList<>();
            collectProjectChanges(root, openedProjectNames, closedProjectNames, removedProjectNames);
            for (String projectName : removedProjectNames)
                ObjectSets.getInstance().removeSetsForProject(projectName);
            for (String projectName : openedProjectNames)
            {
                ObjectSets.getInstance().ensureDefaultSetForProject(projectName);
                ObjectSets.getInstance().ensureSystemSetForProject(projectName);
                InfobaseChangedObjects.pruneRemovedApplicationSets(projectName);
            }
            if (!openedProjectNames.isEmpty() || !closedProjectNames.isEmpty())
                ObjectSets.getInstance().fireChanged();
        }

        private static void collectProjectChanges(
                IResourceDelta delta, List<String> opened, List<String> closed, List<String> removed)
        {
            if (delta == null)
                return;
            IResource resource = delta.getResource();
            if (resource instanceof IProject project)
            {
                int kind = delta.getKind();
                if (kind == IResourceDelta.REMOVED)
                {
                    removed.add(project.getName());
                }
                else if (kind == IResourceDelta.ADDED && project.isOpen())
                {
                    opened.add(project.getName());
                }
                else if (kind == IResourceDelta.CHANGED
                    && (delta.getFlags() & IResourceDelta.OPEN) != 0)
                {
                    if (project.isOpen())
                        opened.add(project.getName());
                    else
                        closed.add(project.getName());
                }
            }
            for (IResourceDelta child : delta.getAffectedChildren())
                collectProjectChanges(child, opened, closed, removed);
        }
    }
}
