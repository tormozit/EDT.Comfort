package tormozit;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;

/** Полная очистка маркеров, оставленных проверкой ИР (на случай, если ИР что-то напортачил). */
public final class IrModuleCheckClearHandler
{
    public static final String COMMAND_ID = "tormozit.IrModuleCheckClearMarkers"; //$NON-NLS-1$
    static final String MENU_LABEL = "Очистить ошибки ИР"; //$NON-NLS-1$

    private IrModuleCheckClearHandler() {}

    public static void clearMarkers(BslXtextEditor editor)
    {
        if (editor == null)
        {
            ToastNotification.show(MENU_LABEL, "Откройте модуль BSL и повторите команду", 3000); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IProject project = dtProject.getWorkspaceProject();
        int editorMarkersRemoved = clearEditorMarkers(project);

        IMarkerManager markerManager = Global.getOsgiService(IMarkerManager.class);
        if (markerManager == null)
        {
            IrModuleCheckDebug.problem("IMarkerManager недоступен как OSGi-сервис"); //$NON-NLS-1$
            ToastNotification.show(MENU_LABEL, "Сервис маркеров EDT недоступен", 4_000); //$NON-NLS-1$
            return;
        }

        markerManager.removeMarkersByCheckId(project, IrModuleCheckHandler.CHECK_ID);
        IrModuleCheckDebug.log("removeMarkersByCheckId project=" + project.getName() //$NON-NLS-1$
            + " editorMarkersRemoved=" + editorMarkersRemoved); //$NON-NLS-1$
        ToastNotification.show(MENU_LABEL,
            "Все маркеры проверки ИР для проекта удалены" //$NON-NLS-1$
                + (editorMarkersRemoved > 0 ? " (в т.ч. в редакторе: " + editorMarkersRemoved + ")" : ""), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            4_000);
    }

    /**
     * Удаляет обычные {@code IMarker.PROBLEM} (подчёркивания в редакторе) по всему проекту —
     * отдельное от {@link IMarkerManager} хранилище, {@code removeMarkersByCheckId} их не видит.
     */
    private static int clearEditorMarkers(IProject project)
    {
        int removed = 0;
        try
        {
            for (IMarker m : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE))
            {
                if (IrModuleCheckHandler.EDITOR_MARKER_ATTR_VALUE.equals(
                    m.getAttribute(IrModuleCheckHandler.EDITOR_MARKER_ATTR, null)))
                {
                    m.delete();
                    removed++;
                }
            }
        }
        catch (CoreException e)
        {
            IrModuleCheckDebug.problem("clearEditorMarkers: " + e); //$NON-NLS-1$
        }
        return removed;
    }
}
