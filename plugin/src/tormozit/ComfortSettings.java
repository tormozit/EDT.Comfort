package tormozit;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Общие настройки плагина Comfort (не только content assist).
 */
public final class ComfortSettings
{
    /** Ключ булевой настройки «Заменять фильтры в списках». */
    public static final String PREF_REPLACE_LIST_FILTERS = "comfort.replaceListFilters"; //$NON-NLS-1$

    /** Значение «Заменять фильтры в списках» по умолчанию. */
    public static final boolean DEFAULT_REPLACE_LIST_FILTERS = true;

    private static ComfortSettings instance;

    private final ScopedPreferenceStore preferenceStore;

    private ComfortSettings(String pluginId)
    {
        preferenceStore = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
    }

    public static synchronized ComfortSettings init(String pluginId)
    {
        if (instance == null)
            instance = new ComfortSettings(pluginId);
        return instance;
    }

    public static ComfortSettings getInstance()
    {
        return instance;
    }

    public ScopedPreferenceStore getPreferenceStore()
    {
        return preferenceStore;
    }

    /** Читает актуальное значение из хранилища (без кэша). */
    public static boolean isReplaceListFiltersEnabled()
    {
        ComfortSettings settings = instance;
        if (settings == null)
            return DEFAULT_REPLACE_LIST_FILTERS;
        return settings.preferenceStore.getBoolean(PREF_REPLACE_LIST_FILTERS);
    }
}
