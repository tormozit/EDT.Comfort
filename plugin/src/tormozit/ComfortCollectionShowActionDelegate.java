package tormozit;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Действие «Показать коллекцию» в контекстном меню отладчика.
 */
public final class ComfortCollectionShowActionDelegate implements IObjectActionDelegate
{
    private static final String TOOLTIP =
        "Открыть коллекцию в отдельном окне" + Global.pluginSignForTooltip(); //$NON-NLS-1$

    private ISelection selection;

    @Override
    public void setActivePart(IAction action, IWorkbenchPart targetPart)
    {
        if (action != null)
            action.setToolTipText(TOOLTIP);
    }

    @Override
    public void selectionChanged(IAction action, ISelection selection)
    {
        this.selection = selection;
        boolean enabled = false;
        if (selection instanceof IStructuredSelection structured && structured.size() == 1)
            enabled = ComfortCollectionShowSupport.isIndexedCollection(structured.getFirstElement());
        if (action != null)
            action.setEnabled(enabled);
    }

    @Override
    public void run(IAction action)
    {
        if (!(selection instanceof IStructuredSelection structured))
            return;
        ComfortCollectionShowSupport.openFromElement(structured.getFirstElement());
    }
}
