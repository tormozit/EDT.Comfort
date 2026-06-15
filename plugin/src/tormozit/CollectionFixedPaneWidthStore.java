package tormozit;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

/**
 * Ширина левой (фиксированной) панели split-table «Коллекция» — sash между индексом и данными.
 */
final class CollectionFixedPaneWidthStore
{
    private static final String PREF_WIDTH = "comfort.collection.fixedPane.width"; //$NON-NLS-1$
    static final int DEFAULT_WIDTH = CollectionIndexColumnWidthStore.DEFAULT_WIDTH
        + CollectionTypeColumnWidthStore.DEFAULT_WIDTH;
    static final int MIN_WIDTH = CollectionIndexColumnWidthStore.MIN_WIDTH
        + CollectionTypeColumnWidthStore.MIN_WIDTH;
    static final int MAX_WIDTH = CollectionIndexColumnWidthStore.MAX_WIDTH
        + CollectionTypeColumnWidthStore.MAX_WIDTH
        + CollectionPresentationColumnWidthStore.MAX_WIDTH;

    private static ScopedPreferenceStore prefs;

    private CollectionFixedPaneWidthStore() {}

    static int load()
    {
        ScopedPreferenceStore store = prefs();
        if (store == null || !store.contains(PREF_WIDTH))
            return fallbackWidth();
        return clamp(store.getInt(PREF_WIDTH));
    }

    static void save(int width)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null)
            return;
        store.setValue(PREF_WIDTH, clamp(width));
        try
        {
            store.save();
        }
        catch (Exception ignored)
        {
            // prefs optional
        }
    }

    static int clamp(int width)
    {
        if (width < MIN_WIDTH)
            return MIN_WIDTH;
        if (width > MAX_WIDTH)
            return MAX_WIDTH;
        return width;
    }

    private static int fallbackWidth()
    {
        return clamp(CollectionIndexColumnWidthStore.load() + CollectionTypeColumnWidthStore.load());
    }

    private static ScopedPreferenceStore prefs()
    {
        if (prefs != null)
            return prefs;
        try
        {
            String pluginId = FrameworkUtil.getBundle(CollectionFixedPaneWidthStore.class).getSymbolicName();
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        }
        catch (Exception ignored)
        {
            return null;
        }
        return prefs;
    }
}
