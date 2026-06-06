package tormozit;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;

/**
 * Инициализирует значения по умолчанию для настроек автооткрытия подсказки.
 * Регистрируется через точку расширения {@code org.eclipse.core.runtime.preferences}
 * в {@code plugin.xml}.
 */
public class ContentAssistInitializer extends AbstractPreferenceInitializer
{
    @Override
    public void initializeDefaultPreferences()
    {
        ContentAssistSettings settings =
            ContentAssistSettings.getInstance();
        if (settings == null)
            return; // Activator ещё не запущен — нормальная ситуация при первом запуске

        settings.getPreferenceStore().setDefault(
            ContentAssistSettings.PREF_ENABLED,
            ContentAssistSettings.DEFAULT_ENABLED);

        settings.getPreferenceStore().setDefault(
            ContentAssistSettings.PREF_TIMEOUT,
            ContentAssistSettings.DEFAULT_TIMEOUT);
    }
}
