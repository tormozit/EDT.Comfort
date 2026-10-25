package tormozit;

import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/** Подсказки страницы «Встроенный язык» (BslLanguageRootPreferencePage из bsl.ui). */
public final class BslLanguagePreferenceHook implements IStartup
{
    private static final String PAGE_CLASS =
        "com._1c.g5.v8.dt.internal.bsl.ui.preferences.BslLanguageRootPreferencePage"; //$NON-NLS-1$
    private static final String WIRED = "tormozit.bslLanguagePreferencesWired"; //$NON-NLS-1$
    private static final String CONTENT_TYPES_LABEL = "Включить расчет типов контента для строковых литералов";
    private static final String CONTENT_TYPES_LABEL_EN = "Enable computing of content types of string literal"; //$NON-NLS-1$
    private static final String CONTENT_TYPES_TOOLTIP =
        "EDT определяет, что содержит строковый литерал: текст запроса, форматную строку, "
        + "имя объекта метаданных, ключ и т. п. Требуется для штатных "
        + "проверок содержимого и подсказок литералов.\n"
        + "Требуется для проверки «Неизвестный литерал» Комфорта.\n"
        + "Создает повышенную нагрузку на процессор.";

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (display.isDisposed())
                return;
            display.addFilter(SWT.Show, event ->
            {
                if (event.widget instanceof Shell shell)
                    wire(shell);
            });
            display.addFilter(SWT.Activate, event ->
            {
                if (event.widget instanceof Shell shell)
                    wire(shell);
            });
            for (Shell shell : display.getShells())
                wire(shell);
        });
    }

    private static void wire(Shell shell)
    {
        if (shell.isDisposed() || !(shell.getData() instanceof PreferenceDialog dialog)
            || Boolean.TRUE.equals(shell.getData(WIRED)))
            return;
        shell.setData(WIRED, Boolean.TRUE);
        dialog.addPageChangedListener(event -> schedulePatch(shell, dialog));
        schedulePatch(shell, dialog);
    }

    private static void schedulePatch(Shell shell, PreferenceDialog dialog)
    {
        shell.getDisplay().asyncExec(() ->
        {
            if (shell.isDisposed())
                return;
            Object selected = dialog.getSelectedPage();
            if (selected instanceof IPreferencePage page && PAGE_CLASS.equals(page.getClass().getName())
                && page.getControl() instanceof Composite control && !control.isDisposed())
                patchButtons(control);
        });
    }

    private static void patchButtons(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0)
            {
                String label = button.getText().replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
                if (CONTENT_TYPES_LABEL.equals(label) || CONTENT_TYPES_LABEL_EN.equals(label))
                    button.setToolTipText(TooltipText.wrap(button,
                        CONTENT_TYPES_TOOLTIP + Global.pluginSignForTooltip()));
            }
            if (child instanceof Composite composite)
                patchButtons(composite);
        }
    }
}
