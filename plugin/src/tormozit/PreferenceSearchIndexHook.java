package tormozit;

import java.util.WeakHashMap;

import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.ui.dialogs.FilteredTree;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Подключает расширенный поиск по текстам контролов страниц в диалоге
 * «Параметры» при любом способе его открытия.
 */
public final class PreferenceSearchIndexHook implements IStartup
{
    private static final int MAX_ATTEMPTS = 30;

    private static final int RETRY_MS = 100;

    private static final WeakHashMap<Shell, Boolean> pendingWiring =
            new WeakHashMap<>();

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
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed())
                return;
            PreferenceDialog dialog = findPreferenceDialog(shell);
            if (dialog == null)
                return;
            scheduleWireOnce(display, shell, dialog);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void scheduleWire(Display display, Shell shell,
            PreferenceDialog dialog, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
        {
            pendingWiring.remove(shell);
            return;
        }

        FilteredTree tree = PreferenceSearchFilterAugmenter.resolveDialogFilteredTree(dialog);
        if (tree != null)
        {
            PreferenceSearchFilterAugmenter.wireInto(dialog, tree);
            // Только читает кэш с диска (дёшево) — реальная индексация
            // запускается исключительно по клику на кнопку в wireInto.
            PreferenceSearchIndex.getInstance().ensureLoaded(dialog.getPreferenceManager(), display);
            pendingWiring.remove(shell);
            return;
        }

        display.timerExec(RETRY_MS, () -> scheduleWire(display, shell, dialog, attempt + 1));
    }

    private static void scheduleWireOnce(Display display, Shell shell, PreferenceDialog dialog)
    {
        synchronized (pendingWiring)
        {
            if (Boolean.TRUE.equals(pendingWiring.get(shell)))
                return;
            pendingWiring.put(shell, Boolean.TRUE);
        }
        scheduleWire(display, shell, dialog, 0);
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            Object data = current.getData();
            if (data instanceof PreferenceDialog dialog)
                return dialog;
            if (current.getParent() instanceof Shell parent)
                current = parent;
            else
                break;
        }
        return null;
    }
}
