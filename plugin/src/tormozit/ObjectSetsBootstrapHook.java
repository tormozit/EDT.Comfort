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
        });
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
            List<String> projectNames = new ArrayList<>();
            collectNewOrOpenedProjects(root, projectNames);
            for (String projectName : projectNames)
                ObjectSets.getInstance().ensureDefaultSetForProject(projectName);
        }

        private static void collectNewOrOpenedProjects(IResourceDelta delta, List<String> out)
        {
            if (delta == null)
                return;
            IResource resource = delta.getResource();
            if (resource instanceof IProject project)
            {
                int kind = delta.getKind();
                if (project.isOpen()
                    && (kind == IResourceDelta.ADDED
                        || (kind == IResourceDelta.CHANGED && (delta.getFlags() & IResourceDelta.OPEN) != 0)))
                {
                    out.add(project.getName());
                }
            }
            for (IResourceDelta child : delta.getAffectedChildren())
                collectNewOrOpenedProjects(child, out);
        }
    }
}
