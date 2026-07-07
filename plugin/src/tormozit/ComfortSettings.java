package tormozit;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Общие настройки плагина Comfort (не только content assist).
 */
public final class ComfortSettings
{
    /**
     * Ключ булевой настройки «Улучшать списки» (имя ключа исторически осталось прежним).
     * Управляет заменой фильтров по подстроке в списках (навигатор, список баз и т.д.),
     * а также доработками панели глобального поиска ({@link SearchViewAggregationHook}, issue #79)
     * и поиска по файлам ({@link FileSearchResultsHook}).
     */
    public static final String PREF_REPLACE_LIST_FILTERS = "comfort.replaceListFilters"; //$NON-NLS-1$

    /** Ключ: общий отладочный журнал ({@link GlobalLogView}). */
    public static final String PREF_DEBUG_LOG = "comfort.debugLog"; //$NON-NLS-1$

    /** Ключ: автопрокрутка журнала к последней строке ({@link GlobalLogView}). */
    public static final String PREF_LOG_AUTOSCROLL = "comfort.log.autoscroll"; //$NON-NLS-1$

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

    /** Автопрокрутка журнала включена по умолчанию. */
    public static final boolean DEFAULT_LOG_AUTOSCROLL = true;

    /** Префикс ключа «Авто» (автоподключение ИР) per infobase UUID. */
    public static final String PREF_IR_AUTO_CONNECT_PREFIX = "comfort.ir.autoConnect."; //$NON-NLS-1$

    /** Автоподключение ИР выключено по умолчанию. */
    public static final boolean DEFAULT_IR_AUTO_CONNECT = false;

    /** Префикс ключа «Динамическое автообновление ИР» per infobase UUID. */
    public static final String PREF_IR_DYNAMIC_AUTO_UPDATE_PREFIX = "comfort.ir.dynamicAutoUpdate."; //$NON-NLS-1$

    /** Динамическое автообновление включено по умолчанию. */
    public static final boolean DEFAULT_IR_DYNAMIC_AUTO_UPDATE = true;

    /** Ключ: закрытие независимого окна инспектора (F9) при клике вне окна. */
    public static final String PREF_DEBUG_INSPECTOR_AUTO_CLOSE = "comfort.debug.inspectorAutoClose"; //$NON-NLS-1$

    /** Закрытие независимого инспектора включено по умолчанию. */
    public static final boolean DEFAULT_DEBUG_INSPECTOR_AUTO_CLOSE = true;

    /** Ключ: автоматические доработки штатных окон отладки (инспектор, «Переменные»). */
    public static final String PREF_IMPROVE_DEBUGGER_WINDOWS = "comfort.debug.improveWindows"; //$NON-NLS-1$

    /** Автодоработки отладчика включены по умолчанию. */
    public static final boolean DEFAULT_IMPROVE_DEBUGGER_WINDOWS = true;

    /** Prefix for file search table column widths. */
    private static final String PREF_FILE_SEARCH_COLUMN_PREFIX = "comfort.fileSearch.columnWidth."; //$NON-NLS-1$

    /** Prefix for file search sash weights. */
    private static final String PREF_FILE_SEARCH_SASH_PREFIX = "comfort.fileSearch.sash."; //$NON-NLS-1$

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

    /** Автопатч инспектора и префикс длинных строк в штатных деревьях (не команды меню). */
    public static boolean isImproveDebuggerWindowsEnabled()
    {
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        if (settings == null)
            return DEFAULT_IMPROVE_DEBUGGER_WINDOWS;
        return settings.getPreferenceStore().getBoolean(PREF_IMPROVE_DEBUGGER_WINDOWS);
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

    public static boolean isLogAutoscroll()
    {
        ComfortSettings settings = instance;
        if (settings == null)
            return DEFAULT_LOG_AUTOSCROLL;
        return settings.preferenceStore.getBoolean(PREF_LOG_AUTOSCROLL);
    }

    public static void setLogAutoscroll(boolean enabled)
    {
        ComfortSettings settings = instance;
        if (settings == null)
            return;
        settings.preferenceStore.setValue(PREF_LOG_AUTOSCROLL, enabled);
        try
        {
            settings.preferenceStore.save();
        }
        catch (Exception ex)
        {
            Global.log("ComfortSettings save error (logAutoscroll): " + ex); //$NON-NLS-1$
        }
    }

    public static String dynamicAutoUpdatePrefKey(String infobaseUuid)
    {
        return PREF_IR_DYNAMIC_AUTO_UPDATE_PREFIX + infobaseUuid;
    }

    public static String autoConnectPrefKey(String infobaseUuid)
    {
        return PREF_IR_AUTO_CONNECT_PREFIX + infobaseUuid;
    }

    public static boolean isAutoConnect(String infobaseUuid)
    {
        return getPerInfobaseBoolean(PREF_IR_AUTO_CONNECT_PREFIX, infobaseUuid, DEFAULT_IR_AUTO_CONNECT);
    }

    public static void setAutoConnect(String infobaseUuid, boolean enabled)
    {
        setPerInfobaseBoolean(PREF_IR_AUTO_CONNECT_PREFIX, infobaseUuid, enabled);
    }

    /** Читает настройку динамического автообновления для UUID инфобазы (по умолчанию {@link #DEFAULT_IR_DYNAMIC_AUTO_UPDATE}). */
    public static boolean isDynamicAutoUpdate(String infobaseUuid)
    {
        return getPerInfobaseBoolean(PREF_IR_DYNAMIC_AUTO_UPDATE_PREFIX, infobaseUuid,
            DEFAULT_IR_DYNAMIC_AUTO_UPDATE);
    }

    public static void setDynamicAutoUpdate(String infobaseUuid, boolean enabled)
    {
        setPerInfobaseBoolean(PREF_IR_DYNAMIC_AUTO_UPDATE_PREFIX, infobaseUuid, enabled);
    }

    public static boolean isDebugInspectorAutoClose()
    {
        IPreferenceStore store = inspectorPreferenceStore();
        if (store == null)
            return DEFAULT_DEBUG_INSPECTOR_AUTO_CLOSE;
        if (!preferenceStoreContains(store, PREF_DEBUG_INSPECTOR_AUTO_CLOSE))
        {
            ComfortSettings settings = instance;
            if (settings != null
                && preferenceStoreContains(settings.preferenceStore, PREF_DEBUG_INSPECTOR_AUTO_CLOSE))
            {
                boolean legacy = settings.preferenceStore.getBoolean(PREF_DEBUG_INSPECTOR_AUTO_CLOSE);
                setDebugInspectorAutoClose(legacy);
                return legacy;
            }
            return DEFAULT_DEBUG_INSPECTOR_AUTO_CLOSE;
        }
        return store.getBoolean(PREF_DEBUG_INSPECTOR_AUTO_CLOSE);
    }

    public static void setDebugInspectorAutoClose(boolean enabled)
    {
        saveInspectorBoolean(PREF_DEBUG_INSPECTOR_AUTO_CLOSE, enabled, "inspectorAutoClose"); //$NON-NLS-1$
    }

    // ---- FileSearch column widths ----

    public static int getFileSearchColumnWidth(String columnId, int defaultWidth)
    {
        ComfortSettings settings = instance;
        if (settings == null)
            return defaultWidth;
        String key = PREF_FILE_SEARCH_COLUMN_PREFIX + columnId;
        if (!settings.preferenceStore.contains(key))
            return defaultWidth;
        return settings.preferenceStore.getInt(key);
    }

    public static void setFileSearchColumnWidth(String columnId, int width)
    {
        ComfortSettings settings = instance;
        if (settings == null)
            return;
        settings.preferenceStore.setValue(PREF_FILE_SEARCH_COLUMN_PREFIX + columnId, width);
        try
        {
            settings.preferenceStore.save();
        }
        catch (Exception ex)
        {
            Global.log("ComfortSettings save error (columnWidth): " + ex); //$NON-NLS-1$
        }
    }

    // ---- FileSearch sash weights ----

    public static int getFileSearchSashWeight(String side, int defaultWeight)
    {
        ComfortSettings settings = instance;
        if (settings == null)
            return defaultWeight;
        String key = PREF_FILE_SEARCH_SASH_PREFIX + side;
        if (!settings.preferenceStore.contains(key))
            return defaultWeight;
        return settings.preferenceStore.getInt(key);
    }

    public static void setFileSearchSashWeights(int left, int right)
    {
        ComfortSettings settings = instance;
        if (settings == null)
            return;
        settings.preferenceStore.setValue(PREF_FILE_SEARCH_SASH_PREFIX + "left", left);
        settings.preferenceStore.setValue(PREF_FILE_SEARCH_SASH_PREFIX + "right", right);
        try
        {
            settings.preferenceStore.save();
        }
        catch (Exception ex)
        {
            Global.log("ComfortSettings save error (sashWeights): " + ex); //$NON-NLS-1$
        }
    }

    private static boolean getInspectorBoolean(String key, boolean defaultValue)
    {
        IPreferenceStore store = inspectorPreferenceStore();
        if (store == null)
            return defaultValue;
        if (!preferenceStoreContains(store, key))
            return defaultValue;
        return store.getBoolean(key);
    }

    private static void saveInspectorBoolean(String key, boolean enabled, String logLabel)
    {
        IPreferenceStore store = inspectorPreferenceStore();
        if (store == null)
            return;
        store.setValue(key, enabled);
        if (store instanceof ScopedPreferenceStore scoped)
        {
            try
            {
                scoped.save();
            }
            catch (Exception ex)
            {
                Global.log("ComfortSettings save error (" + logLabel + "): " + ex); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private static IPreferenceStore inspectorPreferenceStore()
    {
        return debuggerEnhancementPreferenceStore();
    }

    private static IPreferenceStore debuggerEnhancementPreferenceStore()
    {
        Activator activator = Activator.getDefault();
        if (activator != null)
            return activator.getPreferenceStore();
        ComfortSettings settings = instance;
        return settings != null ? settings.preferenceStore : null;
    }

    private static boolean preferenceStoreContains(IPreferenceStore store, String key)
    {
        if (store instanceof ScopedPreferenceStore scoped)
            return scoped.contains(key);
        return store.getString(key) != null;
    }

    private static boolean getPerInfobaseBoolean(String prefPrefix, String infobaseUuid, boolean defaultValue)
    {
        if (infobaseUuid == null || infobaseUuid.isEmpty())
            return defaultValue;
        ComfortSettings settings = instance;
        if (settings == null)
            return defaultValue;
        var store = settings.preferenceStore;
        String key = prefPrefix + infobaseUuid;
        if (!store.contains(key))
            return defaultValue;
        return store.getBoolean(key);
    }

    private static void setPerInfobaseBoolean(String prefPrefix, String infobaseUuid, boolean enabled)
    {
        if (infobaseUuid == null || infobaseUuid.isEmpty())
            return;
        ComfortSettings settings = instance;
        if (settings == null)
            return;
        settings.preferenceStore.setValue(prefPrefix + infobaseUuid, enabled);
        try
        {
            settings.preferenceStore.save();
        }
        catch (Exception ex)
        {
            Global.log("ComfortSettings save error (" + prefPrefix + "): " + ex); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
