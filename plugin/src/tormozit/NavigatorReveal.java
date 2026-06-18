package tormozit;

import java.lang.reflect.Method;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.navigator.CommonNavigator;

/**
 * Показ {@link EObject} в дереве навигатора EDT ({@code selectReveal}).
 */
public final class NavigatorReveal
{
    private NavigatorReveal() {}

    public static void reveal(EObject eObject, boolean force)
    {
        reveal(eObject, force, null);
    }

    /**
     * @param editorToReactivate редактор, фокус которого вернуть после показа в навигаторе
     *        (редактор сравнения); {@code null} — не трогать активную часть
     */
    public static void reveal(EObject eObject, boolean force, IEditorPart editorToReactivate)
    {
        if (eObject == null)
            return;

        IViewPart view = Global.getViewById(Global.NAVIGATOR_VIEW_ID);
        if (!(view instanceof CommonNavigator navigator))
            return;

        if (!force && !isLinkingEnabled(navigator))
            return;

        if (editorToReactivate != null)
        {
            ESelectionService selectionService =
                editorToReactivate.getSite().getService(ESelectionService.class);
            if (selectionService != null)
                selectionService.setSelection(eObject);
        }

        Display.getDefault().asyncExec(() ->
        {
            if (navigator.getSite() == null)
                return;
            StructuredSelection navSelection = new StructuredSelection(eObject);
            navigator.selectReveal(navSelection);

            if (editorToReactivate == null)
                return;

            Class<?> classMPart = MPart.class;
            Class<?> classEPartService = EPartService.class;
            Object partService = navigator.getSite().getService(classEPartService);
            Object navMPart = navigator.getSite().getService(classMPart);
            Object editorMPart = editorToReactivate.getSite().getService(classMPart);
            if (partService == null || navMPart == null)
                return;
            try
            {
                Method activateMethod =
                    partService.getClass().getMethod("activate", classMPart, boolean.class); //$NON-NLS-1$
                activateMethod.invoke(partService, navMPart, false);
                if (editorMPart != null)
                    activateMethod.invoke(partService, editorMPart, false);
            }
            catch (Exception ignored)
            {
                // активация part — необязательное улучшение UX
            }
        });
    }

    private static boolean isLinkingEnabled(CommonNavigator navigator)
    {
        Object result = Global.call(navigator, "isLinkingEnabled"); //$NON-NLS-1$
        if (result instanceof Boolean)
            return (Boolean) result;
        result = Global.getField(navigator, "isLinkingEnabled"); //$NON-NLS-1$
        return result instanceof Boolean && (Boolean) result;
    }
}
