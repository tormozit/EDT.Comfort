package tormozit;

import org.eclipse.jface.preference.PreferenceDialog;
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
    private static final String TAG = "PreferencesDialogTransferHook"; //$NON-NLS-1$

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

        Global.tempLog(TAG, "rewire shell=" + shell.getText() //$NON-NLS-1$
                + " importItem=" + (importItem != null) //$NON-NLS-1$
                + " exportItem=" + (exportItem != null) //$NON-NLS-1$
                + " importLink=" + (importLink != null) //$NON-NLS-1$
                + " exportLink=" + (exportLink != null)); //$NON-NLS-1$

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
        widget.addListener(SWT.Selection, event ->
        {
            Global.tempLog(TAG, "click widget=" + widget.getClass().getSimpleName() //$NON-NLS-1$
                    + " removedStockListeners=" + existingListeners.length); //$NON-NLS-1$
            action.run();
        });
        Global.tempLog(TAG, "rewireSelection widget=" + widget.getClass().getSimpleName() //$NON-NLS-1$
                + " removedStockListeners=" + existingListeners.length //$NON-NLS-1$
                + " nowHasListeners=" + widget.getListeners(SWT.Selection).length); //$NON-NLS-1$
    }

    private static void openImport(PreferenceDialog dialog, Shell shell)
    {
        Global.tempLog(TAG, "openImport: creating ComfortPreferencesImportWizard"); //$NON-NLS-1$
        ComfortPreferencesImportWizard wizard = new ComfortPreferencesImportWizard();
        wizard.init(PlatformUI.getWorkbench(), null);
        WizardDialog wizardDialog = new WizardDialog(shell, wizard);
        Global.tempLog(TAG, "openImport: opening, windowTitle=" + wizard.getWindowTitle()); //$NON-NLS-1$
        wizardDialog.open();
        Global.tempLog(TAG, "openImport: closed, returnCode=" + wizardDialog.getReturnCode()); //$NON-NLS-1$
        // Мастер — самостоятельный дочерний диалог; родительское окно "Параметры" закрывать
        // не нужно ни при "Готово", ни при "Отмена" (штатный Import/Export родителя не закрывает).
    }

    private static void openExport(PreferenceDialog dialog, Shell shell)
    {
        Global.tempLog(TAG, "openExport: creating ComfortPreferencesExportWizard"); //$NON-NLS-1$
        ComfortPreferencesExportWizard wizard = new ComfortPreferencesExportWizard();
        wizard.init(PlatformUI.getWorkbench(), null);
        WizardDialog wizardDialog = new WizardDialog(shell, wizard);

        int response = org.eclipse.jface.dialogs.MessageDialog.open(
                org.eclipse.jface.dialogs.MessageDialog.CONFIRM, shell,
                WorkbenchMessages.PreferenceExportWarning_title,
                WorkbenchMessages.PreferenceExportWarning_message, SWT.NONE,
                WorkbenchMessages.PreferenceExportWarning_applyAndContinue,
                WorkbenchMessages.PreferenceExportWarning_continue);
        Global.tempLog(TAG, "openExport: confirm response=" + response); //$NON-NLS-1$
        if (response == -1)
            return;
        if (response == 0)
            Global.invokeVoid(dialog, "okPressed"); //$NON-NLS-1$

        Global.tempLog(TAG, "openExport: opening, windowTitle=" + wizard.getWindowTitle()); //$NON-NLS-1$
        wizardDialog.open();
        Global.tempLog(TAG, "openExport: closed, returnCode=" + wizardDialog.getReturnCode()); //$NON-NLS-1$
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
