package tormozit;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;

/**
 * Инициализирует значения по умолчанию настроек плагина.
 * Регистрируется через точку расширения {@code org.eclipse.core.runtime.preferences}
 * в {@code plugin.xml}.
 */
public class ComfortInitializer extends AbstractPreferenceInitializer
{
    @Override
    public void initializeDefaultPreferences()
    {
        ContentAssistSettings settings =
            ContentAssistSettings.getInstance();
        if (settings == null)
            return; // Activator ещё не запущен — нормальная ситуация при первом запуске

        var store = settings.getPreferenceStore();
        store.setDefault(
            ContentAssistSettings.PREF_ENABLED,
            ContentAssistSettings.DEFAULT_ENABLED);
        store.setDefault(
            ContentAssistSettings.PREF_TIMEOUT,
            ContentAssistSettings.DEFAULT_TIMEOUT);
        store.setDefault(
            ComfortSettings.PREF_REPLACE_LIST_FILTERS,
            ComfortSettings.DEFAULT_REPLACE_LIST_FILTERS);
        store.setDefault(
            ComfortSettings.PREF_FILTER_MATCH_COLOR,
            ComfortSettings.DEFAULT_FILTER_MATCH_COLOR);
        store.setDefault(
            ComfortSettings.PREF_DEBUG_LOG,
            ComfortSettings.DEFAULT_DEBUG_LOG);
        store.setDefault(
            ComfortSettings.PREF_LOG_AUTOSCROLL,
            ComfortSettings.DEFAULT_LOG_AUTOSCROLL);
        store.setDefault(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED,
            ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_ENABLED);
        store.setDefault(
            ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR,
            ComfortSettings.DEFAULT_SERVER_CALL_HIGHLIGHTING_COLOR);
        store.setDefault(
            ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR,
            ComfortSettings.DEFAULT_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR);
        store.setDefault(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED,
            ComfortSettings.DEFAULT_BRACKET_CONTENT_HINT_ENABLED);
        store.setDefault(
            ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES,
            ComfortSettings.DEFAULT_BRACKET_CONTENT_HINT_MIN_LINES);
        store.setDefault(
            ComfortSettings.PREF_COMPARE_CURRENT_LINES_VISIBLE,
            ComfortSettings.DEFAULT_COMPARE_CURRENT_LINES_VISIBLE);
        store.setDefault(
            ComfortSettings.PREF_SPELLING_DICT_BASE_PATHS,
            ComfortSettings.DEFAULT_SPELLING_DICT_BASE_PATHS);
        store.setDefault(
            ComfortSettings.PREF_SPELLING_BOOTSTRAPPED,
            ComfortSettings.DEFAULT_SPELLING_BOOTSTRAPPED);
        store.setDefault(
            ComfortSettings.PREF_SPELLING_CHECK_IDENTIFIERS_VISIBLE,
            ComfortSettings.DEFAULT_SPELLING_CHECK_IDENTIFIERS_VISIBLE);
    }
}
