package tormozit;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

/**
 * Последние размер и позиция окна «Коллекция» (между сеансами EDT).
 */
final class CollectionWindowGeometryStore
{
    private static final String PREF_X = "comfort.collection.window.x"; //$NON-NLS-1$
    private static final String PREF_Y = "comfort.collection.window.y"; //$NON-NLS-1$
    private static final String PREF_WIDTH = "comfort.collection.window.width"; //$NON-NLS-1$
    private static final String PREF_HEIGHT = "comfort.collection.window.height"; //$NON-NLS-1$

    private static final int DEFAULT_WIDTH = 900;
    private static final int DEFAULT_HEIGHT = 600;

    private static ScopedPreferenceStore prefs;

    private CollectionWindowGeometryStore() {}

    static void applyToShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        Rectangle bounds = load(shell.getDisplay());
        shell.setBounds(bounds);
        // #region agent log
        CollectionWindowGeometryDebug.log("G1", "CollectionWindowGeometryStore.applyToShell", "restore", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            bounds.x, bounds.y, bounds.width, bounds.height, hasStored());
        // #endregion
    }

    static void saveFromShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        Rectangle bounds = shell.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
            return;
        Rectangle clamped = clampToMonitor(shell.getDisplay(), bounds,
            shell.getMinimumSize().x, shell.getMinimumSize().y);
        save(clamped);
        // #region agent log
        CollectionWindowGeometryDebug.log("G2", "CollectionWindowGeometryStore.saveFromShell", "persist", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-1$
            clamped.x, clamped.y, clamped.width, clamped.height, true);
        // #endregion
    }

    private static Rectangle load(Display display)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null || !hasStored(store))
            return defaultBounds(display);

        int x = store.getInt(PREF_X);
        int y = store.getInt(PREF_Y);
        int width = store.getInt(PREF_WIDTH);
        int height = store.getInt(PREF_HEIGHT);
        if (width <= 0 || height <= 0)
            return defaultBounds(display);
        return clampToMonitor(display, new Rectangle(x, y, width, height), 640, 400);
    }

    private static void save(Rectangle bounds)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null || bounds == null)
            return;
        store.setValue(PREF_X, bounds.x);
        store.setValue(PREF_Y, bounds.y);
        store.setValue(PREF_WIDTH, bounds.width);
        store.setValue(PREF_HEIGHT, bounds.height);
        try
        {
            store.save();
        }
        catch (Exception ignored)
        {
            // prefs optional
        }
    }

    private static boolean hasStored()
    {
        return hasStored(prefs());
    }

    private static boolean hasStored(ScopedPreferenceStore store)
    {
        return store != null && store.contains(PREF_WIDTH) && store.contains(PREF_HEIGHT);
    }

    private static Rectangle defaultBounds(Display display)
    {
        Monitor monitor = display != null ? display.getPrimaryMonitor() : null;
        Rectangle client = monitor != null ? monitor.getClientArea() : new Rectangle(0, 0, 1920, 1080);
        int width = Math.min(DEFAULT_WIDTH, client.width);
        int height = Math.min(DEFAULT_HEIGHT, client.height);
        int x = client.x + Math.max(0, (client.width - width) / 2);
        int y = client.y + Math.max(0, (client.height - height) / 2);
        return new Rectangle(x, y, width, height);
    }

    private static Rectangle clampToMonitor(Display display, Rectangle bounds, int minWidth, int minHeight)
    {
        if (display == null || bounds == null)
            return bounds;

        Monitor monitor = display.getPrimaryMonitor();
        Point center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        for (Monitor candidate : display.getMonitors())
        {
            if (candidate.getBounds().contains(center))
            {
                monitor = candidate;
                break;
            }
        }

        Rectangle client = monitor.getClientArea();
        int width = Math.max(minWidth, Math.min(bounds.width, client.width));
        int height = Math.max(minHeight, Math.min(bounds.height, client.height));
        int x = bounds.x;
        int y = bounds.y;
        if (x + width > client.x + client.width)
            x = client.x + client.width - width;
        if (y + height > client.y + client.height)
            y = client.y + client.height - height;
        if (x < client.x)
            x = client.x;
        if (y < client.y)
            y = client.y;
        return new Rectangle(x, y, width, height);
    }

    private static ScopedPreferenceStore prefs()
    {
        if (prefs != null)
            return prefs;
        try
        {
            String pluginId = FrameworkUtil.getBundle(CollectionWindowGeometryStore.class).getSymbolicName();
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        }
        catch (Exception ignored)
        {
            return null;
        }
        return prefs;
    }
}
