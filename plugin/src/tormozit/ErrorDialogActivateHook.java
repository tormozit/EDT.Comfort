package tormozit;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

/**
 * Штатное окно EDT «Произошла ошибка в модуле» (заголовок «Ошибка», кнопка «Анализировать»)
 * часто всплывает из фонового задания, когда главное окно EDT свёрнуто или перекрыто другими
 * окнами — пользователь его не видит. {@link Shell#forceActive()} в этом случае лишь мигает
 * иконкой в панели задач Windows (foreground lock: вызов не с потока, владеющего фокусом), поэтому
 * активация идёт через {@link WinWindowActivator#activateWindowWithoutInputAttach}.
 * Потоки ввода EDT и приложения 1С не присоединяются (issue 964).
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

        ActivationDiagnostics.install(display);
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (shell.getData(ACTIVATED_KEY) != null)
                return;
            if (!DIALOG_TITLE.equals(shell.getText()))
                return;
            ActivationDiagnostics.watch(shell);
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
            ActivationDiagnostics.log(shell, "activation-attempt=" + attempt);
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
            ActivationDiagnostics.snapshot(shell, "before-activation");
            if (hwnd != null)
                WinWindowActivator.activateWindowWithoutInputAttach(hwnd);
            else
                shell.forceActive();
            ActivationDiagnostics.snapshot(shell, "after-activation");
        });
    }

    /** Временная безусловная диагностика issue 964; не меняет активацию и обработчики кнопок. */
    private static final class ActivationDiagnostics
    {
        private static final String TOPIC = "error-dialog-964"; //$NON-NLS-1$
        private static final String WATCHED_KEY = "tormozit.errorDialog964Watched"; //$NON-NLS-1$

        static void install(Display display)
        {
            Global.tempLog(TOPIC, "install diagnostics=3 activation-without-input-attach"); //$NON-NLS-1$
            display.addFilter(SWT.Selection, event ->
            {
                if (!(event.widget instanceof Button button) || button.isDisposed())
                    return;
                Shell shell = button.getShell();
                if (shell.getData(WATCHED_KEY) == null)
                    return;
                String phase = "button=" + button.getText() + " id=" + button.getData(); //$NON-NLS-1$ //$NON-NLS-2$
                snapshot(shell, phase + " before-listeners"); //$NON-NLS-1$
                delayedSnapshots(shell, phase);
            });
        }

        static void watch(Shell shell)
        {
            if (shell.getData(WATCHED_KEY) != null)
                return;
            shell.setData(WATCHED_KEY, Boolean.TRUE);
            snapshot(shell, "show"); //$NON-NLS-1$
            for (int type : new int[] { SWT.Activate, SWT.Deactivate, SWT.Close, SWT.Hide, SWT.Dispose })
            {
                shell.addListener(type, event ->
                {
                    String phase = "shell-event=" + event.type + " doit=" + event.doit; //$NON-NLS-1$ //$NON-NLS-2$
                    snapshot(shell, phase);
                    if (event.type == SWT.Dispose)
                        delayedSnapshots(shell, phase);
                });
            }
        }

        static void log(Shell shell, String phase)
        {
            Global.tempLog(TOPIC, "dialog=" + Integer.toHexString(System.identityHashCode(shell)) //$NON-NLS-1$
                + " " + phase + " disposed=" + shell.isDisposed()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static void delayedSnapshots(Shell shell, String phase)
        {
            Display display = shell.getDisplay();
            for (int delay : new int[] { 0, 250, 1000, 3000, 5000, 7000, 10000 })
                display.timerExec(delay, () -> snapshot(shell, phase + " after-ms=" + delay)); //$NON-NLS-1$
        }

        static void snapshot(Shell shell, String phase)
        {
            log(shell, phase);
            try
            {
                Display display = Display.getCurrent();
                if (display != null && !display.isDisposed())
                {
                    for (Shell current : display.getShells())
                    {
                        if (current.isDisposed())
                            continue;
                        Object data = current.getData();
                        Global.tempLog(TOPIC, "swt shell=" //$NON-NLS-1$
                            + Integer.toHexString(System.identityHashCode(current))
                            + " title=" + current.getText() + " style=" + current.getStyle() //$NON-NLS-1$ //$NON-NLS-2$
                            + " visible=" + current.getVisible() + " enabled=" + current.getEnabled() //$NON-NLS-1$ //$NON-NLS-2$
                            + " active=" + (display.getActiveShell() == current) //$NON-NLS-1$
                            + " hwnd=" + WinWindowActivator.hwndFromShell(current) //$NON-NLS-1$
                            + " data=" + (data == null ? "null" : data.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                if (!WinWindowActivator.isWindows())
                    return;
                HWND foreground = User32.INSTANCE.GetForegroundWindow();
                Global.tempLog(TOPIC, "foreground " + windowInfo(foreground)); //$NON-NLS-1$
                logThreadInfo(0);
                Set<Integer> threads = new HashSet<>();
                // Все видимые окна: окно 1С может уже не быть foreground к моменту SWT.Show.
                User32.INSTANCE.EnumWindows((hwnd, data) ->
                {
                    if (User32.INSTANCE.IsWindowVisible(hwnd))
                    {
                        Global.tempLog(TOPIC, "window " + windowInfo(hwnd)); //$NON-NLS-1$
                        int thread = User32.INSTANCE.GetWindowThreadProcessId(hwnd, null);
                        if (thread != 0 && threads.add(thread))
                            logThreadInfo(thread);
                    }
                    return true;
                }, null);
            }
            catch (Exception | LinkageError e)
            {
                Global.tempLog(TOPIC, "snapshot failed: " + e); //$NON-NLS-1$
            }
        }

        private static void logThreadInfo(int thread)
        {
            WinUser.GUITHREADINFO info = new WinUser.GUITHREADINFO();
            info.cbSize = info.size();
            if (!User32.INSTANCE.GetGUIThreadInfo(thread, info))
            {
                Global.tempLog(TOPIC, "gui thread=" + thread + " failed error=" + Native.getLastError()); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            Global.tempLog(TOPIC, "gui thread=" + thread + " flags=0x" + Integer.toHexString(info.flags) //$NON-NLS-1$ //$NON-NLS-2$
                + " active={" + windowInfo(info.hwndActive) + "} focus={" + windowInfo(info.hwndFocus) //$NON-NLS-1$ //$NON-NLS-2$
                + "} capture={" + windowInfo(info.hwndCapture) + "} menuOwner={" + windowInfo(info.hwndMenuOwner) //$NON-NLS-1$ //$NON-NLS-2$
                + "} moveSize=" + info.hwndMoveSize + " caret=" + info.hwndCaret); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String windowInfo(HWND hwnd)
        {
            if (WinWindowActivator.isNullHwnd(hwnd))
                return "hwnd=null"; //$NON-NLS-1$
            IntByReference pid = new IntByReference();
            int thread = User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            char[] title = new char[512];
            int length = User32.INSTANCE.GetWindowText(hwnd, title, title.length);
            return "hwnd=" + hwnd + " pid=" + pid.getValue() + " thread=" + thread //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " valid=" + User32.INSTANCE.IsWindow(hwnd) //$NON-NLS-1$
                + " enabled=" + User32.INSTANCE.IsWindowEnabled(hwnd) //$NON-NLS-1$
                + " visible=" + User32.INSTANCE.IsWindowVisible(hwnd) //$NON-NLS-1$
                + " owner=" + User32.INSTANCE.GetWindow(hwnd, new DWORD(WinUser.GW_OWNER)) //$NON-NLS-1$
                + " title=" + new String(title, 0, Math.max(0, length)).replace('\n', ' ').replace('\r', ' '); //$NON-NLS-1$
        }
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
