package tormozit;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.internal.ide.dialogs.CleanDialog;

/** Уточняет название штатной очистки проекта: «Очистить кэши». */
public final class ProjectCleanDialogHook implements IStartup
{
    private static final String LABEL = "Очистить кэши"; //$NON-NLS-1$
    private static final String COMMAND_ID = "org.eclipse.ui.project.cleanAction"; //$NON-NLS-1$
    private static final String MENU_KEY = "tormozit.projectCleanDialog.menu"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Global.tempLog("project-clean", "earlyStartup"); //$NON-NLS-1$ //$NON-NLS-2$
        Display.getDefault().asyncExec(() ->
        {
            Display.getDefault().addFilter(SWT.Show, ProjectCleanDialogHook::handleShow);
            Global.tempLog("project-clean", "Show filter installed"); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    private static void handleShow(Event event)
    {
        if (event.widget instanceof Menu menu)
        {
            // MenuManager наполняет меню в своём слушателе Show, после фильтра Display.
            if (!Boolean.TRUE.equals(menu.getData(MENU_KEY)))
            {
                menu.setData(MENU_KEY, Boolean.TRUE);
                menu.addListener(SWT.Show, shown -> renameMenu(menu));
            }
        }
        else if (event.widget instanceof Shell shell && shell.getData() instanceof CleanDialog)
        {
            shell.setText(LABEL);
            Button button = shell.getDefaultButton();
            if (button != null && !button.isDisposed())
            {
                button.setText(LABEL);
                if (button.getLayoutData() instanceof GridData data)
                    data.widthHint = Math.max(data.widthHint, button.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x);
                button.getParent().layout(true, true);
            }
            Global.tempLog("project-clean", "dialog renamed: defaultButton=" + button); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void renameMenu(Menu menu)
    {
        for (MenuItem item : menu.getItems())
        {
            if (!(item.getData() instanceof ActionContributionItem contribution))
                continue;
            IAction action = contribution.getAction();
            if (!COMMAND_ID.equals(action.getActionDefinitionId()))
                continue;
            String previous = item.getText();
            int shortcut = previous.indexOf('\t');
            action.setText(LABEL + "..."); //$NON-NLS-1$
            item.setText(LABEL + "..." + (shortcut < 0 ? "" : previous.substring(shortcut))); //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLog("project-clean", "menu renamed: " + previous + " -> " + item.getText()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }
}
