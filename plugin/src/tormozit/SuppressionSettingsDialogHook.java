package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/** Уточняет подпись штатного флажка в окне подавлений проверок EDT. */
public final class SuppressionSettingsDialogHook implements IStartup
{
    private static final String STOCK_LABEL = "Подавить все валидационные проверки для объекта"; //$NON-NLS-1$
    private static final String LABEL = "Подавить все проверки для объекта"; //$NON-NLS-1$

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
            display.asyncExec(() ->
            {
                if (!shell.isDisposed())
                    rename(shell);
            });
        }));
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
}
