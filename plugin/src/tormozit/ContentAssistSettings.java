package tormozit;

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Хранит настройки функции автооткрытия подсказки BSL-редактора.
 *
 * <p>Символы-триггеры ({@link #CHARSET_VALUE}) скрыты из UI и заданы
 * константой — пользователю доступны только «Включено» и «Задержка».
 */
public final class ContentAssistSettings
{
    /** Ключ булевой настройки «Включено». */
    public static final String PREF_ENABLED = "contentAssistAutoOpen.enabled";  //$NON-NLS-1$
    /** Ключ целочисленной настройки «Задержка» (мс). */
    public static final String PREF_TIMEOUT = "contentAssistAutoOpen.timeout";  //$NON-NLS-1$

    /**
     * Фиксированный набор символов-триггеров автооткрытия подсказки:
     * точка + все буквы кириллицы + все буквы латиницы (оба регистра).
     */
    public static final String CHARSET_VALUE =
        ".АаБбВвГгДдЕеЁёЖжЗзИиЙйКкЛлМмНнОоПпРрСсТтУуФфХхЦцЧчШшЩщЪъЫыЬьЭэЮюЯя" //$NON-NLS-1$
        + ".abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"; //$NON-NLS-1$

    /** Значение «Включено» по умолчанию. */
    public static final boolean DEFAULT_ENABLED = true;
    /** Значение «Задержка» по умолчанию (мс). */
    public static final int     DEFAULT_TIMEOUT  = 0;

    // ---- Синглтон ----

    private static ContentAssistSettings instance;

    private final ScopedPreferenceStore preferenceStore;
    private boolean enabled = DEFAULT_ENABLED;
    private int     timeout = DEFAULT_TIMEOUT;

    private ContentAssistSettings(String pluginId)
    {
        this.preferenceStore =
            new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
    }

    /**
     * Создаёт и возвращает единственный экземпляр.
     * Вызывается один раз из {@code Activator.start()}.
     */
    public static synchronized ContentAssistSettings init(String pluginId)
    {
        if (instance == null)
            instance = new ContentAssistSettings(pluginId);
        return instance;
    }

    /** Возвращает синглтон (после {@link #init}). */
    public static ContentAssistSettings getInstance()
    {
        return instance;
    }

    // ---- API ----

    /** Хранилище настроек Eclipse Preferences, привязанное к ID нашего плагина. */
    public ScopedPreferenceStore getPreferenceStore()
    {
        return preferenceStore;
    }

    /**
     * Считывает {@link #PREF_ENABLED} и {@link #PREF_TIMEOUT} из хранилища
     * в поля. Вызывается при старте и в ответ на изменение настроек.
     */
    public void loadSettings()
    {
        this.enabled = preferenceStore.getBoolean(PREF_ENABLED);
        this.timeout = preferenceStore.getInt(PREF_TIMEOUT);
    }

    public boolean isEnabled() { return enabled; }

    public int getTimeout() { return timeout; }

    /**
     * Всегда возвращает {@link #CHARSET_VALUE} — захардкоженный список
     * символов-триггеров. Не читается из хранилища.
     */
    public String getCharset() { return CHARSET_VALUE; }

    public void addPropertyChangeListener(IPropertyChangeListener listener)
    {
        preferenceStore.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(IPropertyChangeListener listener)
    {
        preferenceStore.removePropertyChangeListener(listener);
    }
}
