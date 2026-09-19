package tormozit;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.internal.wizards.preferences.PreferencesExportWizard;

/**
 * Замена штатного {@link PreferencesExportWizard}, вызываемая из кнопки
 * «Экспортировать...» окна «Параметры» ({@link PreferencesDialogTransferHook}).
 */
public class ComfortPreferencesExportWizard extends PreferencesExportWizard
{
    /**
     * {@code performFinish()} штатного {@link PreferencesExportWizard} зовёт
     * приватное поле {@code mainPage}, которое наш переопределённый
     * {@code addPages()} не заполняет — поэтому {@code performFinish()} тоже
     * переопределён, без вызова {@code super}.
     */
    private ComfortPreferencesExportPage page;

    @Override
    public void init(IWorkbench workbench, IStructuredSelection selection)
    {
        // super.init(...) сам зовёт setWindowTitle(PreferencesMessages.PreferencesExportWizard_export) —
        // штатный заголовок нужно переставить уже после него, иначе он затирает наш.
        super.init(workbench, selection);
        setWindowTitle(Global.withPluginWindowTitle("Экспортировать параметры")); //$NON-NLS-1$
    }

    @Override
    public void addPages()
    {
        page = new ComfortPreferencesExportPage();
        addPage(page);
    }

    @Override
    public boolean performFinish()
    {
        return page.finish();
    }
}
