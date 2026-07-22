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
    private static final String TEMP_LOG = "preference-search-wire"; //$NON-NLS-1$

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
            {
                if (looksLikePreferencesShell(shell))
                {
                    Global.tempLog(TEMP_LOG, "event=" + eventTypeName(event.type) //$NON-NLS-1$
                            + " dialog=null title=[" + shell.getText() + "] shellData=" //$NON-NLS-1$ //$NON-NLS-2$
                            + describeShellData(shell)); //$NON-NLS-1$
                }
                return;
            }
            Global.tempLog(TEMP_LOG, "event=" + eventTypeName(event.type) //$NON-NLS-1$
                    + " dialog=" + dialog.getClass().getSimpleName() //$NON-NLS-1$
                    + " title=[" + shell.getText() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            scheduleWireOnce(display, shell, dialog);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        Global.tempLog(TEMP_LOG, "install Show+Activate listener"); //$NON-NLS-1$
    }

    private static void scheduleWire(Display display, Shell shell,
            PreferenceDialog dialog, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
        {
            if (attempt >= MAX_ATTEMPTS)
            {
                Global.tempLog(TEMP_LOG, "GIVE UP attempt=" + attempt //$NON-NLS-1$
                        + " filteredTree=" + (PreferenceSearchFilterAugmenter.resolveDialogFilteredTree(dialog) != null) //$NON-NLS-1$
                        + " title=[" + (shell.isDisposed() ? "<disposed>" : shell.getText()) + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            pendingWiring.remove(shell);
            return;
        }

        FilteredTree tree = PreferenceSearchFilterAugmenter.resolveDialogFilteredTree(dialog);
        if (tree != null)
        {
            Global.tempLog(TEMP_LOG, "attempt=" + attempt + " filteredTree=OK class=" //$NON-NLS-1$ //$NON-NLS-2$
                    + tree.getClass().getSimpleName()
                    + " filterControl=" + (tree.getFilterControl() != null && !tree.getFilterControl().isDisposed())); //$NON-NLS-1$
            PreferenceSearchFilterAugmenter.wireInto(dialog, tree);
            // Только читает кэш с диска (дёшево) — реальная индексация
            // запускается исключительно по клику на кнопку в wireInto.
            PreferenceSearchIndex.getInstance().ensureLoaded(dialog.getPreferenceManager(), display);
            pendingWiring.remove(shell);
            return;
        }

        if (attempt == 0 || attempt == MAX_ATTEMPTS - 1)
        {
            Global.tempLog(TEMP_LOG, "attempt=" + attempt + " filteredTree=null retry"); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static boolean looksLikePreferencesShell(Shell shell)
    {
        String title = shell.getText();
        if (title == null)
            return false;
        String lower = title.toLowerCase();
        return lower.contains("preferences") //$NON-NLS-1$
                || lower.contains("параметр") //$NON-NLS-1$
                || lower.contains("настройк"); //$NON-NLS-1$
    }

    private static String describeShellData(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return "null"; //$NON-NLS-1$
        return data.getClass().getName();
    }

    private static String eventTypeName(int type)
    {
        if (type == SWT.Show)
            return "Show"; //$NON-NLS-1$
        if (type == SWT.Activate)
            return "Activate"; //$NON-NLS-1$
        return String.valueOf(type);
    }
}
