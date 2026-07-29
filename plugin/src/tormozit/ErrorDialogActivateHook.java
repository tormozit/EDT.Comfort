package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com.sun.jna.platform.win32.WinDef.HWND;

/**
 * Штатное окно EDT «Произошла ошибка в модуле» (заголовок «Ошибка», кнопка «Анализировать»)
 * часто всплывает из фонового задания, когда главное окно EDT свёрнуто или перекрыто другими
 * окнами — пользователь его не видит. {@link Shell#forceActive()} в этом случае лишь мигает
 * иконкой в панели задач Windows (foreground lock: вызов не с потока, владеющего фокусом), поэтому
 * активация идёт через {@link WinWindowActivator#activateWindowOnUiThread} (AttachThreadInput +
 * SetForegroundWindow), что реально поднимает окно в Foreground.
 */
public final class ErrorDialogActivateHook implements IStartup
{
    private static final String DIALOG_TITLE = "Ошибка"; //$NON-NLS-1$
    private static final String ANALYZE_BUTTON_TEXT = "Анализировать"; //$NON-NLS-1$
    private static final String ACTIVATED_KEY = "tormozit.errorDialogActivated"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (shell.getData(ACTIVATED_KEY) != null)
                return;
            if (!DIALOG_TITLE.equals(shell.getText()))
                return;
            scheduleActivateAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Show, listener);
    }

    private static void scheduleActivateAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(ACTIVATED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 60;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(ACTIVATED_KEY) != null)
                return;
            if (!DIALOG_TITLE.equals(shell.getText()))
                return;
            if (!hasAnalyzeButton(shell))
            {
                if (attempt < 15)
                    scheduleActivateAttempt(display, shell, attempt + 1);
                return;
            }
            shell.setData(ACTIVATED_KEY, Boolean.TRUE);
            HWND hwnd = WinWindowActivator.hwndFromShell(shell);
            if (hwnd != null)
                WinWindowActivator.activateWindowOnUiThread(hwnd);
            else
                shell.forceActive();
        });
    }

    private static boolean hasAnalyzeButton(Composite composite)
    {
        for (Control child : composite.getChildren())
        {
            if (child instanceof org.eclipse.swt.widgets.Button button
                    && ANALYZE_BUTTON_TEXT.equals(button.getText()))
                return true;
            if (child instanceof Composite childComposite && hasAnalyzeButton(childComposite))
                return true;
        }
        return false;
    }
}
