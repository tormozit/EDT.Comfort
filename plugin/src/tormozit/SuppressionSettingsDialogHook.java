package tormozit;

import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckSettings;

/** Уточняет подпись штатного флажка в окне подавлений проверок EDT. */
public final class SuppressionSettingsDialogHook implements IStartup
{
    private static final String STOCK_LABEL = "Подавить все валидационные проверки для объекта"; //$NON-NLS-1$
    private static final String LABEL = "Подавить все проверки для объекта"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Настройки подавлений проверок"; //$NON-NLS-1$
    private static final String CHECK_CODE_KEY = SuppressionSettingsDialogHook.class.getName() + ".checkCode"; //$NON-NLS-1$
    private static final String INSTALLED_KEY = SuppressionSettingsDialogHook.class.getName() + ".installed"; //$NON-NLS-1$
    private static final String INITIAL_SELECTION_KEY = SuppressionSettingsDialogHook.class.getName()
        + ".initialSelection"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            display.asyncExec(() -> prepareShell(shell, 0));
        }));
    }

    private static void prepareShell(Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        String title = shell.getText();
        if (!title.startsWith(DIALOG_TITLE))
        {
            if (title.isEmpty() && attempt < 20)
                shell.getDisplay().timerExec(250, () -> prepareShell(shell, attempt + 1));
            return;
        }
        rename(shell);
        installCheckTree(shell);
        Tree tree = findTree(shell);
        if ((tree == null || tree.getData(INSTALLED_KEY) == null) && attempt < 120)
            shell.getDisplay().timerExec(250, () -> prepareShell(shell, attempt + 1));
    }

    private static void rename(Composite parent)
    {
        for (Control control : parent.getChildren())
        {
            if (control instanceof Button button && STOCK_LABEL.equals(button.getText()))
            {
                button.setText(LABEL);
                return;
            }
            if (control instanceof Composite child)
                rename(child);
        }
    }

    private static void installCheckTree(Shell shell)
    {
        Tree tree = findTree(shell);
        if (tree == null || tree.getData(INSTALLED_KEY) != null)
            return;
        tree.setData(INSTALLED_KEY, Boolean.TRUE);
        Runnable refresh = () -> refreshChecks(tree);
        tree.addListener(SWT.Expand, event -> tree.getDisplay().asyncExec(refresh));
        tree.addListener(SWT.SetData, event -> tree.getDisplay().asyncExec(refresh));
        tree.addListener(SWT.MouseUp, event -> tree.getDisplay().asyncExec(refresh));
        tree.getDisplay().asyncExec(refresh);
        tree.getDisplay().asyncExec(() -> selectInitialWhenReady(tree, 0));

        Menu menu = tree.getMenu();
        if (menu == null)
        {
            menu = new Menu(tree);
            tree.setMenu(menu);
        }
        Menu checkMenu = menu;
        MenuItem open = new MenuItem(checkMenu, SWT.PUSH);
        open.setText("Открыть проверку"); //$NON-NLS-1$
        ComfortSubmenuHelper.setMenuItemTooltip(open, "Открыть параметры выбранной проверки"); //$NON-NLS-1$
        open.addListener(SWT.Selection, event ->
        {
            TreeItem[] selection = tree.getSelection();
            if (selection.length == 0)
                return;
            String code = (String)selection[0].getData(CHECK_CODE_KEY);
            IProject project = activeProject();
            ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
            if (code == null || project == null || repository == null)
                return;
            Set<CheckUid> uids = repository.getCheckUidForCheckId(code, project);
            if (uids.size() == 1)
                ProblemViewHook.openCheckSettings(shell, project, repository.getShortUid(uids.iterator().next(), project));
        });
        checkMenu.addListener(SWT.Show, event ->
        {
            refreshChecks(tree);
            TreeItem[] selection = tree.getSelection();
            String code = selection.length == 0 ? null : (String)selection[0].getData(CHECK_CODE_KEY);
            open.setEnabled(code != null);
        });
    }

    private static IProject activeProject()
    {
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
            ? Global.getActiveProject(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), false)
            : null;
    }

    private static void refreshChecks(Tree tree)
    {
        if (tree.isDisposed())
            return;
        IProject project = activeProject();
        ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
        if (project == null || repository == null)
            return;
        for (TreeItem item : tree.getItems())
            refreshCheck(item, repository, project);
    }

    private static void selectInitialWhenReady(Tree tree, int attempt)
    {
        if (tree.isDisposed() || tree.getData(INITIAL_SELECTION_KEY) != null)
            return;
        int count = tree.getItemCount();
        if (count == 0)
        {
            if (attempt < 120)
                tree.getDisplay().timerExec(250, () -> selectInitialWhenReady(tree, attempt + 1));
            return;
        }
        tree.setData(INITIAL_SELECTION_KEY, Boolean.TRUE);
        if (tree.getSelectionCount() != 0)
            return;
        TreeItem first = tree.getItem(0);
        tree.setSelection(first);
        tree.setFocus();
        Event selection = new Event();
        selection.item = first;
        tree.notifyListeners(SWT.Selection, selection);
    }

    private static void refreshCheck(TreeItem item, ICheckRepository repository, IProject project)
    {
        String code = (String)item.getData(CHECK_CODE_KEY);
        if (code == null)
        {
            code = item.getText();
            Set<CheckUid> uids = repository.getCheckUidForCheckId(code, project);
            if (uids.size() == 1)
                item.setData(CHECK_CODE_KEY, code);
            else
                code = null;
        }
        if (code != null)
        {
            Set<CheckUid> uids = repository.getCheckUidForCheckId(code, project);
            if (uids.size() == 1)
            {
                ICheckSettings settings = repository.getSettings(uids.iterator().next(), project);
                String title = settings != null ? settings.getTitle() : null;
                if (title != null && !title.isBlank())
                    item.setText(title + " (" + code + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (TreeItem child : item.getItems())
            refreshCheck(child, repository, project);
    }

    private static Tree findTree(Composite parent)
    {
        for (Control control : parent.getChildren())
        {
            if (control instanceof Tree tree)
                return tree;
            if (control instanceof Composite child)
            {
                Tree found = findTree(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
