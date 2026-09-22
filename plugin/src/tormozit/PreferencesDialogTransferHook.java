package tormozit;

import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.internal.WorkbenchMessages;

/**
 * Подменяет обработку кликов по кнопкам «Импортировать...»/«Экспортировать...»
 * в штатном окне «Параметры» (Window → Preferences) на свои мастера
 * ({@link ComfortPreferencesImportWizard}/{@link ComfortPreferencesExportWizard}).
 * <p>
 * Штатные кнопки — {@code org.eclipse.ui.internal.dialogs.FilteredPreferenceDialog}
 * (тулбар {@link ToolItem} либо {@link Link}, если тулбар недоступен), их слушатель
 * {@code SWT.Selection} навешан напрямую ({@code addListener}), поэтому его можно
 * снять через {@link Widget#getListeners(int)} и заменить своим — без рефлексии.
 */
public final class PreferencesDialogTransferHook implements IStartup
{
    private static final int MAX_ATTEMPTS = 20;

    private static final int RETRY_MS = 150;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (!(shell.getData() instanceof PreferenceDialog dialog))
                return;
            scheduleRewire(display, dialog, shell, 0);
        };
        display.addFilter(SWT.Show, listener);
    }

    private static void scheduleRewire(Display display, PreferenceDialog dialog, Shell shell, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        if (!rewire(dialog, shell))
            display.timerExec(RETRY_MS, () -> scheduleRewire(display, dialog, shell, attempt + 1));
    }

    private static boolean rewire(PreferenceDialog dialog, Shell shell)
    {
        ToolItem importItem = findToolItem(shell, WorkbenchMessages.Preference_import);
        ToolItem exportItem = findToolItem(shell, WorkbenchMessages.Preference_export);
        Link importLink = importItem != null ? null : findLink(shell, WorkbenchMessages.Preference_import);
        Link exportLink = exportItem != null ? null : findLink(shell, WorkbenchMessages.Preference_export);

        boolean found = importItem != null || exportItem != null || importLink != null || exportLink != null;

        if (importItem != null)
            rewireSelection(importItem, () -> openImport(dialog, shell));
        if (exportItem != null)
            rewireSelection(exportItem, () -> openExport(dialog, shell));
        if (importLink != null)
            rewireSelection(importLink, () -> openImport(dialog, shell));
        if (exportLink != null)
            rewireSelection(exportLink, () -> openExport(dialog, shell));

        return found;
    }

    private static void rewireSelection(Widget widget, Runnable action)
    {
        Listener[] existingListeners = widget.getListeners(SWT.Selection);
        for (Listener existing : existingListeners)
            widget.removeListener(SWT.Selection, existing);
        widget.addListener(SWT.Selection, event -> action.run());
    }

    private static void openImport(PreferenceDialog dialog, Shell shell)
    {
        ComfortPreferencesImportWizard wizard = new ComfortPreferencesImportWizard();
        wizard.init(PlatformUI.getWorkbench(), null);
        WizardDialog wizardDialog = new WizardDialog(shell, wizard);
        wizardDialog.open();
        int returnCode = wizardDialog.getReturnCode();
        // Мастер — самостоятельный дочерний диалог; родительское окно "Параметры" закрывать
        // не нужно при "Отмена" (штатный Import/Export родителя не закрывает). При успешном
        // "Готово" — переоткрываем его же на той же странице, см. javadoc reopenOnSamePage.
        if (returnCode == Window.OK)
            reopenOnSamePage(dialog, shell);
    }

    /**
     * Переоткрывает окно "Параметры" на той же странице, где стоял пользователь до импорта —
     * issue #566 (Напарник): {@code IPreferencesService.applyPreferences(...)} пишет новые
     * значения в {@code IPreferenceStore} сразу и корректно, но уже созданная страница ЭТОГО
     * экземпляра диалога (например {@code FieldEditorPreferencePage}) читает значения из стора
     * только один раз при своём создании ({@code IPreferenceNode} кэширует {@code getPage()} на
     * весь срок жизни диалога) и не подписана на изменения извне — на экране остаются старые
     * значения до переоткрытия диалога вручную. Здесь делаем то же самое переоткрытие
     * автоматически: {@link PreferencesUtil#createPreferenceDialogOn} строит новый
     * {@code PreferenceManager} и новые страницы с нуля, ровно как ручное закрытие/открытие.
     * <p>
     * {@code owner} (родитель нового диалога) — Shell активного окна EDT, а не {@code shell}
     * закрываемого {@code dialog} (он будет уничтожен в {@link PreferenceDialog#close()}).
     */
    private static void reopenOnSamePage(PreferenceDialog dialog, Shell shell)
    {
        String pageId = currentPageId(dialog);
        Shell owner = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell()
                : shell.getParent() instanceof Shell parentShell ? parentShell : null;
        dialog.close();
        if (owner == null || owner.isDisposed())
            return;
        PreferenceDialog reopened = PreferencesUtil.createPreferenceDialogOn(owner, pageId, null, null);
        if (reopened != null)
            reopened.open();
    }

    /** Id узла дерева "Параметры", выбранного сейчас (до закрытия {@code dialog}), либо {@code null}. */
    private static String currentPageId(PreferenceDialog dialog)
    {
        TreeViewer tree = dialog.getTreeViewer();
        if (tree == null)
            return null;
        ISelection selection = tree.getSelection();
        if (selection instanceof IStructuredSelection structured
                && structured.getFirstElement() instanceof IPreferenceNode node)
            return node.getId();
        return null;
    }

    private static void openExport(PreferenceDialog dialog, Shell shell)
    {
        ComfortPreferencesExportWizard wizard = new ComfortPreferencesExportWizard();
        wizard.init(PlatformUI.getWorkbench(), null);
        WizardDialog wizardDialog = new WizardDialog(shell, wizard);

        int response = org.eclipse.jface.dialogs.MessageDialog.open(
                org.eclipse.jface.dialogs.MessageDialog.CONFIRM, shell,
                WorkbenchMessages.PreferenceExportWarning_title,
                WorkbenchMessages.PreferenceExportWarning_message, SWT.NONE,
                WorkbenchMessages.PreferenceExportWarning_applyAndContinue,
                WorkbenchMessages.PreferenceExportWarning_continue);
        if (response == -1)
            return;
        if (response == 0)
            Global.invokeVoid(dialog, "okPressed"); //$NON-NLS-1$

        wizardDialog.open();
        // В штатном коде (FilteredPreferenceDialog.openExportWizard) тут стояло
        // "if (dialogResponse == 1) close();" — родительское окно закрывалось при выборе
        // "Продолжить" ДАЖЕ если в самом мастере нажали "Отмена". Родителя не закрываем вовсе:
        // мастер — самостоятельный дочерний диалог, "Готово"/"Отмена" в нём не должны закрывать
        // окно "Параметры" (пользователь мог захотеть выгрузить/загрузить ещё раз или дальше
        // менять другие параметры).
    }

    private static ToolItem findToolItem(Control root, String tooltip)
    {
        if (root instanceof ToolBar toolBar)
        {
            for (ToolItem item : toolBar.getItems())
                if (tooltip.equals(item.getToolTipText()))
                    return item;
        }
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                ToolItem found = findToolItem(child, tooltip);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Link findLink(Control root, String text)
    {
        if (root instanceof Link link && link.getText() != null && link.getText().contains(text))
            return link;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Link found = findLink(child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
