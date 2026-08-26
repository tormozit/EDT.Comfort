package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;

/**
 * Диалог команды «Исправь» ({@code com.e1c.edt.ai.ui.handlers.FixDialog}).
 * Окно определяется только по классу в {@code shell.getData()}, как {@link ColorDialogHook}.
 * Ctrl+Enter подтверждает через {@code okPressed}; Enter по-прежнему вставляет строку.
 * <p>
 * У {@link Text} событие Verify для Ctrl+Enter приходит без {@code SWT.MOD1}
 * (нативный перевод строки вместо кнопки по умолчанию). Поэтому Ctrl запоминается
 * по KeyDown/KeyUp клавиши {@link SWT#CTRL}.
 */
public final class NaparnikFixDialogHook implements IStartup
{
    private static final String DIALOG_CLASS =
            "com.e1c.edt.ai.ui.handlers.FixDialog"; //$NON-NLS-1$
    private static final String WINDOW_DATA_KEY = "org.eclipse.jface.window.Window"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.naparnikFixDialogPatched"; //$NON-NLS-1$
    private static final String CLOSING_KEY = "tormozit.naparnikFixDialogClosing"; //$NON-NLS-1$

    private static boolean ctrlHeld;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener show = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (findDialog(shell) == null)
                return;
            schedulePatch(display, shell, 0);
        };
        display.addFilter(SWT.Show, show);
        display.addFilter(SWT.Activate, show);

        display.addFilter(SWT.KeyDown, NaparnikFixDialogHook::handleKeyOrVerify);
        display.addFilter(SWT.KeyUp, NaparnikFixDialogHook::handleKeyOrVerify);
        display.addFilter(SWT.Verify, NaparnikFixDialogHook::handleKeyOrVerify);
        display.addFilter(SWT.Traverse, NaparnikFixDialogHook::handleKeyOrVerify);
    }

    private static void schedulePatch(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        display.timerExec(attempt == 0 ? 0 : 50, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 8)
                schedulePatch(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object dialog = findDialog(shell);
        if (dialog == null)
            return false;
        Object field = Global.getField(dialog, "detailsText"); //$NON-NLS-1$
        if (!(field instanceof Text text) || text.isDisposed())
            return false;
        shell.setData(PATCHED_KEY, Boolean.TRUE);
        Listener onText = NaparnikFixDialogHook::handleKeyOrVerify;
        text.addListener(SWT.Verify, onText);
        text.addListener(SWT.KeyDown, onText);
        text.addListener(SWT.Traverse, onText);
        return true;
    }

    private static void handleKeyOrVerify(Event event)
    {
        if (event.keyCode == SWT.CTRL)
        {
            if (event.type == SWT.KeyDown)
                ctrlHeld = true;
            else if (event.type == SWT.KeyUp)
                ctrlHeld = false;
        }
        if (event.type == SWT.KeyUp)
            return;
        if (event.type == SWT.Traverse && event.detail != SWT.TRAVERSE_RETURN)
            return;
        if (!isCtrlEnter(event))
            return;
        if (!(event.widget instanceof Control control) || control.isDisposed())
            return;
        Shell shell = control.getShell();
        Object dialog = findDialog(shell);
        if (dialog == null)
            return;
        consume(event);
        submit(shell, dialog);
    }

    private static void submit(Shell shell, Object dialog)
    {
        if (Boolean.TRUE.equals(shell.getData(CLOSING_KEY)))
            return;
        shell.setData(CLOSING_KEY, Boolean.TRUE);
        shell.getDisplay().asyncExec(() ->
        {
            if (!shell.isDisposed())
                Global.invokeVoid(dialog, "okPressed"); //$NON-NLS-1$
        });
    }

    private static void consume(Event event)
    {
        event.doit = false;
        if (event.type == SWT.Traverse)
            event.detail = SWT.TRAVERSE_NONE;
        event.type = SWT.None;
    }

    private static boolean isCtrlEnter(Event event)
    {
        if (!ctrlHeld && (event.stateMask & SWT.MOD1) == 0 && (event.stateMask & SWT.CTRL) == 0)
            return false;
        if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
            return true;
        if (event.character == SWT.CR || event.character == SWT.LF)
            return true;
        if (event.type == SWT.Verify && event.text != null
                && ("\n".equals(event.text) || "\r".equals(event.text) //$NON-NLS-1$ //$NON-NLS-2$
                    || "\r\n".equals(event.text))) //$NON-NLS-1$
            return true;
        return event.type == SWT.Traverse && event.detail == SWT.TRAVERSE_RETURN;
    }

    /** Как {@link ColorDialogHook}: {@code shell.getData()} — экземпляр {@code FixDialog}. */
    private static Object findDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Object data = shell.getData();
        if (isFixDialog(data))
            return data;
        data = shell.getData(WINDOW_DATA_KEY);
        return isFixDialog(data) ? data : null;
    }

    private static boolean isFixDialog(Object data)
    {
        if (data == null)
            return false;
        for (Class<?> c = data.getClass(); c != null; c = c.getSuperclass())
        {
            if (DIALOG_CLASS.equals(c.getName()))
                return true;
        }
        return false;
    }
}
