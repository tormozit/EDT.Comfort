package tormozit;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Dedup окон «Коллекция» по пути значения и кадру стека.
 */
final class DebugCollectionWindowRegistry
{
    private static final Map<String, WeakReference<DebugCollectionWindow>> OPEN =
        new ConcurrentHashMap<>();

    private DebugCollectionWindowRegistry() {}

    static DebugCollectionWindow findExisting(String registryKey)
    {
        purgeDisposed();
        WeakReference<DebugCollectionWindow> ref = OPEN.get(registryKey);
        if (ref == null)
            return null;
        DebugCollectionWindow window = ref.get();
        if (window == null || window.isDisposed())
        {
            OPEN.remove(registryKey);
            return null;
        }
        return window;
    }

    static void register(String registryKey, DebugCollectionWindow window)
    {
        if (registryKey == null || registryKey.isBlank() || window == null)
            return;
        OPEN.put(registryKey, new WeakReference<>(window));
    }

    static void unregister(String registryKey)
    {
        if (registryKey != null)
            OPEN.remove(registryKey);
    }

    static boolean isCollectionShell(Shell shell)
    {
        return windowForShell(shell) != null;
    }

    /** Фокус в одном из окон «Коллекция» — отладочные привязки клавиш не должны срабатывать. */
    static boolean isCollectionWindowFocused()
    {
        Display display = Display.getCurrent();
        if (display == null)
            display = Display.getDefault();
        if (display == null)
            return false;
        return isCollectionShell(display.getActiveShell());
    }

    static DebugCollectionWindow windowForShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        purgeDisposed();
        for (WeakReference<DebugCollectionWindow> ref : OPEN.values())
        {
            DebugCollectionWindow window = ref.get();
            if (window == null || window.isDisposed())
                continue;
            Shell collectionShell = window.collectionShell();
            if (shell == collectionShell)
                return window;
        }
        return null;
    }

    static void disposeAll()
    {
        purgeDisposed();
        for (WeakReference<DebugCollectionWindow> ref : OPEN.values())
        {
            DebugCollectionWindow w = ref.get();
            if (w != null && !w.isDisposed())
                w.closeAndDispose();
        }
        OPEN.clear();
    }

    private static void purgeDisposed()
    {
        Iterator<Map.Entry<String, WeakReference<DebugCollectionWindow>>> it = OPEN.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, WeakReference<DebugCollectionWindow>> e = it.next();
            DebugCollectionWindow w = e.getValue().get();
            if (w == null || w.isDisposed())
                it.remove();
        }
    }

    static String registryKey(String pathKey, com._1c.g5.v8.dt.debug.core.model.IBslStackFrame frame)
    {
        String frameId = frame != null ? String.valueOf(System.identityHashCode(frame)) : "null"; //$NON-NLS-1$
        return pathKey + "@" + frameId; //$NON-NLS-1$
    }

    static String cloneKey(String baseKey, int cloneIndex)
    {
        return baseKey + "#clone" + cloneIndex; //$NON-NLS-1$
    }
}
