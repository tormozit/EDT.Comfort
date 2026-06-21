package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Команда «Показать коллекцию» (F2 по умолчанию в контексте отладки 1С).
 */
public final class DebugCollectionShowHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.showCollection"; //$NON-NLS-1$

    public static final String COMMAND_ID_FROM_EXPRESSION =
        "tormozit.showCollectionFromExpression"; //$NON-NLS-1$

    /** Контекст привязки в {@code plugin.xml} (отладка 1С). */
    public static final String BINDING_CONTEXT_ID =
        "com._1c.g5.v8.dt.debug.ui.debugging"; //$NON-NLS-1$

    @Override
    public void setEnabled(Object evaluationContext)
    {
        if (DebugCollectionWindowRegistry.isCollectionWindowFocused())
        {
            setBaseEnabled(false);
            return;
        }
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
        {
            setBaseEnabled(false);
            return;
        }
        boolean enabled = false;
        try
        {
            if (DebugSessionHelper.isDebugSuspended(null)
                && evaluationContext instanceof IEvaluationContext context)
            {
                IWorkbenchPart part = partFrom(context);
                ISelection selection = selectionFrom(context);
                enabled = DebugCollectionShowSupport.canOpenFromHandler(part, selection);
            }
        }
        catch (Exception ignored)
        {
            // команда остаётся недоступной
        }
        setBaseEnabled(enabled);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        if (DebugCollectionWindowRegistry.isCollectionWindowFocused())
            return null;
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return null;
        if (DebugInspectorCollectionMenuHook.handleWorkbenchF2InInspector())
            return null;
        DebugCollectionShowSupport.tryOpenFromHandler(
            HandlerUtil.getActivePart(event),
            HandlerUtil.getCurrentSelection(event));
        return null;
    }

    private static IWorkbenchPart partFrom(IEvaluationContext context)
    {
        Object partVar = context.getVariable(ISources.ACTIVE_PART_NAME);
        if (partVar instanceof IWorkbenchPart part)
            return part;
        return null;
    }

    private static ISelection selectionFrom(IEvaluationContext context)
    {
        Object selVar = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        if (selVar instanceof ISelection selection)
            return selection;
        return null;
    }
}
