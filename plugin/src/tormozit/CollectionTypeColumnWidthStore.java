package tormozit;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

/**
 * Ширина колонки «Тип» в фиксированной панели split-table «Коллекция».
 */
final class CollectionTypeColumnWidthStore
{
    private static final String PREF_WIDTH = "comfort.collection.typeColumn.width"; //$NON-NLS-1$
    static final int DEFAULT_WIDTH = 48;
    static final int MIN_WIDTH = 32;
    static final int MAX_WIDTH = 160;

    private static ScopedPreferenceStore prefs;

    private CollectionTypeColumnWidthStore() {}

    static int load()
    {
        ScopedPreferenceStore store = prefs();
        if (store == null || !store.contains(PREF_WIDTH))
            return DEFAULT_WIDTH;
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

    private static ScopedPreferenceStore prefs()
    {
        if (prefs != null)
            return prefs;
        try
        {
            String pluginId = FrameworkUtil.getBundle(CollectionTypeColumnWidthStore.class).getSymbolicName();
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        }
        catch (Exception ignored)
        {
            return null;
        }
        return prefs;
    }
}
