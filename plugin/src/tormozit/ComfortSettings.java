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

    /** Ключ: общий отладочный журнал ({@link ContentAssistLogView}). */
    public static final String PREF_DEBUG_LOG = "comfort.debugLog"; //$NON-NLS-1$

    /** Устаревший ключ; читается при миграции настроек. */
    private static final String PREF_CONTENT_ASSIST_LOG_LEGACY = "comfort.contentAssistLog"; //$NON-NLS-1$

    /** Время последней проверки обновления (мс с эпохи). */
    public static final String PREF_LAST_UPDATE_CHECK_MS = "comfort.update.lastCheckMs"; //$NON-NLS-1$

    /** Кэш: актуальная версия на сайте обновления. */
    public static final String PREF_LATEST_VERSION = "comfort.update.latestVersion"; //$NON-NLS-1$

    /** Кэш: дата публикации актуальной версии. */
    public static final String PREF_LATEST_VERSION_DATE = "comfort.update.latestVersionDate"; //$NON-NLS-1$

    /** Версия, о которой уже показано уведомление. */
    public static final String PREF_LAST_NOTIFIED_VERSION = "comfort.update.lastNotifiedVersion"; //$NON-NLS-1$

    /** Значение «Заменять фильтры в списках» по умолчанию. */
    public static final boolean DEFAULT_REPLACE_LIST_FILTERS = true;

    /** Общее логирование выключено по умолчанию. */
    public static final boolean DEFAULT_DEBUG_LOG = false;

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

    public static boolean isDebugLogEnabled()
    {
        ComfortSettings settings = instance;
        if (settings == null)
            return DEFAULT_DEBUG_LOG;
        var store = settings.preferenceStore;
        if (store.contains(PREF_DEBUG_LOG))
            return store.getBoolean(PREF_DEBUG_LOG);
        if (store.contains(PREF_CONTENT_ASSIST_LOG_LEGACY))
            return store.getBoolean(PREF_CONTENT_ASSIST_LOG_LEGACY);
        return DEFAULT_DEBUG_LOG;
    }
}
