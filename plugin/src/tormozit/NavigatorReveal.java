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
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
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
     * Единое поведение команды «Показать в навигаторе» во всех местах плагина:
     * <ul>
     * <li>панель «Навигатор» видна — только выделяем объект, фокус ей НЕ передаём
     * (пользователь остаётся там, откуда вызвал команду);</li>
     * <li>панель скрыта (закрыта или вытеснена другой вкладкой стека) — активируем её,
     * иначе показывать объект бессмысленно: результата не видно.</li>
     * </ul>
     * Отличие от {@link #reveal(EObject, boolean)}: тот предназначен для реактивного показа
     * по смене выделения в редакторе (linking), учитывает {@code isLinkingEnabled} и не
     * открывает закрытую панель ({@link Global#getViewById} намеренно не создаёт view).
     */
    public static void revealAndActivateIfHidden(EObject eObject)
    {
        Global.tempLog("issue307", "revealAndActivateIfHidden eObject=" //$NON-NLS-1$ //$NON-NLS-2$
            + (eObject == null ? "null" : eObject.eClass().getName())); //$NON-NLS-1$
        ObjectSetSubsystemsFilterBridge.probeStart("revealAndActivateIfHidden"); //$NON-NLS-1$
        if (eObject == null)
            return;

        Display.getDefault().asyncExec(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return;

            IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
            if (view == null)
            {
                try
                {
                    // showView сам делает панель видимой и активной
                    view = page.showView(Global.NAVIGATOR_VIEW_ID);
                }
                catch (Exception ex)
                {
                    Global.log("NavigatorReveal revealAndActivateIfHidden showView error: " + ex); //$NON-NLS-1$
                    return;
                }
            }
            else if (!page.isPartVisible(view))
            {
                page.activate(view);
            }

            if (view instanceof CommonNavigator navigator)
            {
                long t0 = System.nanoTime();
                navigator.selectReveal(new StructuredSelection(eObject));
                Global.tempLog("issue307", "selectReveal ms=" //$NON-NLS-1$ //$NON-NLS-2$
                    + ((System.nanoTime() - t0) / 1_000_000L)
                    + " viewVisible=" + page.isPartVisible(view)); //$NON-NLS-1$
                ObjectSetSubsystemsFilterBridge.scheduleSyncVisibleChevrons("selectReveal"); //$NON-NLS-1$
            }
        });
    }

    /**
     * Вернуть фокус на вкладку редактора (без смены выделения Property Sheet).
     */
    public static void reactivateEditorPart(IEditorPart editorPart)
    {
        if (editorPart == null)
            return;

        Display.getDefault().asyncExec(() ->
        {
            if (editorPart.getSite() == null)
                return;

            Class<?> classMPart = MPart.class;
            Class<?> classEPartService = EPartService.class;
            Object partService = editorPart.getSite().getService(classEPartService);
            Object editorMPart = editorPart.getSite().getService(classMPart);
            if (partService == null || editorMPart == null)
                return;
            try
            {
                Method activateMethod =
                    partService.getClass().getMethod("activate", classMPart, boolean.class); //$NON-NLS-1$
                activateMethod.invoke(partService, editorMPart, false);
            }
            catch (Exception ignored)
            {
                // активация part — необязательное улучшение UX
            }
        });
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
            long t0 = System.nanoTime();
            navigator.selectReveal(navSelection);
            Global.tempLog("issue307", "reveal.selectReveal force=" + force //$NON-NLS-1$ //$NON-NLS-2$
                + " ms=" + ((System.nanoTime() - t0) / 1_000_000L)); //$NON-NLS-1$
            ObjectSetSubsystemsFilterBridge.scheduleSyncVisibleChevrons("reveal.selectReveal"); //$NON-NLS-1$

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
