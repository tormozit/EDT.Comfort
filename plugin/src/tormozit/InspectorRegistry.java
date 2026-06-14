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
        if (expression == null || expression.isBlank())
            return false;
        purgeDisposed();
        WeakReference<Shell> ref = OPEN.get(expression);
        if (ref == null)
            return false;
        Shell shell = ref.get();
        if (shell == null || shell.isDisposed())
        {
            OPEN.remove(expression);
            return false;
        }
        shell.getDisplay().asyncExec(() -> {
            if (!shell.isDisposed())
            {
                shell.setMinimized(false);
                shell.forceActive();
                shell.forceFocus();
            }
        });
        ComfortCollectionDebug.step("inspect", "activate existing " + expression); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    static void register(String expression, Shell shell)
    {
        if (expression == null || expression.isBlank() || shell == null || shell.isDisposed())
            return;
        OPEN.put(expression, new WeakReference<>(shell));
        shell.addDisposeListener(e -> OPEN.remove(expression));
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
