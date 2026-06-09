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
            ComfortSettings.PREF_CONTENT_ASSIST_LOG,
            ComfortSettings.DEFAULT_CONTENT_ASSIST_LOG);
    }
}
