package tormozit;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.swt.widgets.Shell;

/**
 * Dedup popup-инспекторов по полному watch-выражению.
 */
final class InspectorRegistry
{
    private static final Map<String, WeakReference<Shell>> OPEN = new ConcurrentHashMap<>();

    private InspectorRegistry() {}

    static boolean activateExisting(String expression)
    {
        return activateExisting(expression, null);
    }

    /**
     * Активировать уже открытый инспектор.
     *
     * @param aboveShell если задан (окно «Коллекция» с pin), поднять инспектор поверх него
     */
    static boolean activateExisting(String expression, Shell aboveShell)
    {
        Shell shell = shellFor(expression);
        if (shell == null)
            return false;
        raiseShell(shell, aboveShell);
        ComfortCollectionDebug.step("inspect", "activate existing " + expression); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    /** Запланировать фокус свойства в уже открытом инспекторе (после {@link InspectorPendingFocus#set}). */
    static void schedulePendingFocus(String expression)
    {
        Shell shell = shellFor(expression);
        if (shell == null || shell.isDisposed())
            return;
        shell.getDisplay().asyncExec(() ->
        {
            if (!shell.isDisposed())
                DebugInspectorTreeEnhancement.schedulePendingFocusForShell(shell);
        });
    }

    /** Поднять инспектор по выражению (после {@code openInspectPopup}). */
    static void raiseAbove(String expression, Shell aboveShell)
    {
        Shell shell = shellFor(expression);
        if (shell != null)
            raiseShell(shell, aboveShell);
    }

    static void register(String expression, Shell shell)
    {
        if (expression == null || expression.isBlank() || shell == null || shell.isDisposed())
            return;
        OPEN.put(expression, new WeakReference<>(shell));
        shell.addDisposeListener(e -> OPEN.remove(expression));
    }

    private static Shell shellFor(String expression)
    {
        if (expression == null || expression.isBlank())
            return null;
        purgeDisposed();
        WeakReference<Shell> ref = OPEN.get(expression);
        if (ref == null)
            return null;
        Shell shell = ref.get();
        if (shell == null || shell.isDisposed())
        {
            OPEN.remove(expression);
            return null;
        }
        return shell;
    }

    private static void raiseShell(Shell shell, Shell aboveShell)
    {
        if (shell == null || shell.isDisposed())
            return;
        shell.getDisplay().asyncExec(() -> {
            if (shell.isDisposed())
                return;
            shell.setMinimized(false);
            if (aboveShell != null && !aboveShell.isDisposed())
                WinWindowActivator.setShellAboveOwner(shell, aboveShell, true);
            shell.forceActive();
            shell.forceFocus();
        });
    }

    private static void purgeDisposed()
    {
        Iterator<Map.Entry<String, WeakReference<Shell>>> it = OPEN.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, WeakReference<Shell>> e = it.next();
            Shell shell = e.getValue().get();
            if (shell == null || shell.isDisposed())
                it.remove();
        }
    }
}
