package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;

/**
 * В диалоге «Выбор цвета» двойной клик по строке списка цветов закрывает диалог кнопкой «ОК».
 */
public class ColorDialogHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.colorDialogPatched"; //$NON-NLS-1$
    private static final String DIALOG_CLASS =
            "com._1c.g5.v8.dt.md.ui.dialogs.color.ColorDialog"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell)event.widget;
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (findDialog(shell) == null)
                return;
            schedulePatch(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static Object findDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            data = shell.getData("org.eclipse.jface.window.Window"); //$NON-NLS-1$
        if (data == null || !DIALOG_CLASS.equals(data.getClass().getName()))
            return null;
        return data;
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
        if (!hookLists(shell, dialog))
            return false;
        shell.setData(PATCHED_KEY, Boolean.TRUE);
        return true;
    }

    /** Навешивает двойной клик на все списки цветов диалога; {@code false}, если списков ещё нет. */
    private static boolean hookLists(Composite parent, Object dialog)
    {
        boolean hooked = false;
        for (Control child : parent.getChildren())
        {
            if (child.isDisposed())
                continue;
            if (child instanceof Tree || child instanceof Table)
            {
                hookControl(child, dialog);
                hooked = true;
            }
            else if (child instanceof Composite)
                hooked |= hookLists((Composite)child, dialog);
        }
        return hooked;
    }

    private static void hookControl(Control control, Object dialog)
    {
        control.addListener(SWT.MouseDoubleClick, event ->
        {
            if (event.button != 1 || control.isDisposed())
                return;
            if (!hasItemAt(control, event.x, event.y))
                return;
            // Выбор строки уже применён к модели диалога; ОК — после обработки текущего события.
            control.getDisplay().asyncExec(() ->
            {
                if (!control.isDisposed())
                    Global.invokeVoid(dialog, "okPressed"); //$NON-NLS-1$
            });
        });
    }

    private static boolean hasItemAt(Control control, int x, int y)
    {
        Point point = new Point(x, y);
        if (control instanceof Tree)
            return ((Tree)control).getItem(point) != null;
        return ((Table)control).getItem(point) != null;
    }
}
