package tormozit;

import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IViewActionDelegate;
import org.eclipse.ui.IViewPart;

/**
 * «Пересчитать все» в тулбаре панелей «Выражения встроенного языка» и «Выражения» ({@code viewContribution}).
 */
public final class RecalculateAllExpressionsAction implements IViewActionDelegate
{
    private static final String TOOLTIP =
        "Полное обновление всех выражений" + Global.pluginSignForTooltip(); //$NON-NLS-1$

    private IViewPart viewPart;

    @Override
    public void init(IViewPart view)
    {
        viewPart = view;
    }

    @Override
    public void run(IAction action)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        if (!DebugSessionHelper.isDebugSuspended(null))
            return;
        if (!(viewPart instanceof AbstractDebugView debugView))
            return;
        DebugExpressionsChangeValueHook.refreshAllExpressions(debugView);
    }

    @Override
    public void selectionChanged(IAction action, ISelection selection)
    {
        applyActionState(action);
    }

    private static void applyActionState(IAction action)
    {
        if (action == null)
            return;
        action.setToolTipText(TOOLTIP);
        action.setEnabled(ComfortSettings.isImproveDebuggerWindowsEnabled());
    }
}
