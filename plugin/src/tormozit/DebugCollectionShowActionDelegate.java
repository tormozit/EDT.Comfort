package tormozit;

import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Действие «Показать коллекцию» в контекстном меню отладчика.
 */
public final class DebugCollectionShowActionDelegate implements IObjectActionDelegate
{
    private static final String MENU_LABEL = "Показать коллекцию"; //$NON-NLS-1$

    private static final String TOOLTIP =
        "Открыть коллекцию в отдельном окне" + Global.pluginSignForTooltip(); //$NON-NLS-1$

    private ISelection selection;
    private IWorkbenchPart targetPart;

    @Override
    public void setActivePart(IAction action, IWorkbenchPart targetPart)
    {
        this.targetPart = targetPart;
        if (action != null)
        {
            action.setToolTipText(TOOLTIP);
            applyMenuLabel(action);
        }
    }

    @Override
    public void selectionChanged(IAction action, ISelection selection)
    {
        this.selection = selection;
        boolean enabled = false;
        if (selection instanceof IStructuredSelection structured && structured.size() == 1)
            enabled = DebugCollectionShowSupport.isIndexedCollection(structured.getFirstElement());
        if (action != null)
        {
            action.setEnabled(enabled);
            applyMenuLabel(action);
        }
    }

    private static void applyMenuLabel(IAction action)
    {
        action.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
            MENU_LABEL,
            DebugCollectionShowHandler.COMMAND_ID,
            DebugCollectionShowHandler.BINDING_CONTEXT_ID));
    }

    @Override
    public void run(IAction action)
    {
        if (!(selection instanceof IStructuredSelection structured))
            return;
        AbstractDebugView view = targetPart instanceof AbstractDebugView v ? v : null;
        DebugCollectionShowSupport.openFromElement(structured.getFirstElement(), view);
    }
}
