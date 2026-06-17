package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Держит окно «Коллекция» поверх workbench (Win32 owner), как standalone-инспектор.
 */
final class DebugCollectionShellPin
{
    private final Shell shell;
    private boolean pinned;
    private Listener pinListener;

    DebugCollectionShellPin(Shell shell)
    {
        this.shell = shell;
    }

    void install()
    {
        if (shell == null || shell.isDisposed())
            return;
        pinned = true;
        applyNow();
        installMaintenance();
        scheduleRetries();
    }

    void refresh()
    {
        if (pinned && shell != null && !shell.isDisposed())
            applyNow();
    }

    void dispose()
    {
        pinned = false;
        removeMaintenance();
        if (shell != null && !shell.isDisposed())
            WinWindowActivator.setShellAboveOwner(shell, null, false);
    }

    private void applyNow()
    {
        if (shell == null || shell.isDisposed() || !pinned)
            return;
        WinWindowActivator.clearShellTopmost(shell);
        WinWindowActivator.setShellAboveOwner(shell, resolveOwnerShell(), true);
    }

    private void installMaintenance()
    {
        if (pinListener != null || shell == null || shell.isDisposed())
            return;
        pinListener = e -> {
            if (pinned && shell != null && !shell.isDisposed())
                applyNow();
        };
        shell.addListener(SWT.Show, pinListener);
        shell.addListener(SWT.Activate, pinListener);
        shell.addListener(SWT.Deiconify, pinListener);
    }

    private void removeMaintenance()
    {
        if (pinListener == null || shell == null || shell.isDisposed())
            return;
        shell.removeListener(SWT.Show, pinListener);
        shell.removeListener(SWT.Activate, pinListener);
        shell.removeListener(SWT.Deiconify, pinListener);
        pinListener = null;
    }

    private void scheduleRetries()
    {
        if (shell == null || shell.isDisposed())
            return;
        Display display = shell.getDisplay();
        for (int delay : new int[] { 0, 50, 150, 400, 800 })
        {
            display.timerExec(delay, () -> {
                if (pinned && shell != null && !shell.isDisposed())
                    applyNow();
            });
        }
    }

    private static Shell resolveOwnerShell()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
            {
                IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows != null && windows.length > 0)
                    window = windows[0];
            }
            if (window != null)
            {
                Shell workbenchShell = window.getShell();
                if (workbenchShell != null && !workbenchShell.isDisposed())
                    return workbenchShell;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        return null;
    }
}
