package tormozit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Хранилище последних мест (методов и объектов метаданных).
 *
 * <p><b>Ключ</b> — стабильный идентификатор без номера строки:
 * <ul>
 *   <li>Для метода: {@code "МодульПолный.ИмяМетода"},
 *       например {@code "Справочник.Валюты.МодульОбъекта.Записать"}.</li>
 *   <li>Для модуля без активного метода: {@code "МодульПолный"},
 *       например {@code "ОбщийМодуль.Общий1"}
 *       (без суффикса типа модуля, т.е. владелец объекта).</li>
 *   <li>Для объекта МД: полная ссылка объекта.</li>
 * </ul>
 *
 * <p><b>navRef</b> — ссылка для навигации (может содержать номер строки);
 * обновляется при каждом посещении метода.
 *
 * <p>Ёмкость — {@link #MAX_SIZE} записей (LRU). Данные сохраняются между
 * сеансами через Eclipse Preferences (InstanceScope).
 *
 * <p>Формат сериализации (одна запись = одна строка, поля через {@code \t}):
 * {@code <timestamp>\t<key>\t<navRef>\t<displayName>\t<ownName>}
 */
public final class RecentPlaces
{
    // =========================================================================
    // Конфигурация
    // =========================================================================

    /** Максимальное число хранимых мест. */
    public static final int MAX_SIZE = 50;

    private static final String PREF_KEY = "recentPlaces.entries"; //$NON-NLS-1$
    private static final char   SEP      = '\t';
    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"); //$NON-NLS-1$

    // =========================================================================
    // Запись
    // =========================================================================

    public static final class Entry
    {
        /**
         * Стабильный ключ дедупликации (без номера строки).
         * Примеры: {@code "Справочник.Валюты.МодульОбъекта.Записать"},
         * {@code "ОбщийМодуль.Общий1"}, {@code "Справочник.Валюты"}.
         */
        public final String key;

        /**
         * Ссылка для навигации — передаётся в {@link GoToDefinition#jump}.
         * Для метода содержит номер строки: {@code "{МодульПолный(42,0:Метод,0)}"}.
         * Обновляется при каждом новом посещении метода.
         */
        public final String navRef;

        /**
         * Строка для отображения в списке.
         * <ul>
         *   <li>Метод: {@code "Справочник.Валюты.МодульОбъекта: Записать"}</li>
         *   <li>Модуль/объект: полная ссылка</li>
         * </ul>
         */
        public final String displayName;

        /**
         * Собственное имя для фильтрации (имя метода или последний сегмент).
         */
        public final String ownName;

        /** Дата и время последнего посещения. */
        public final LocalDateTime visitedAt;

        public Entry(String key, String navRef, String displayName,
                     String ownName, LocalDateTime visitedAt)
        {
            this.key         = key;
            this.navRef      = navRef;
            this.displayName = displayName;
            this.ownName     = ownName;
            this.visitedAt   = visitedAt;
        }
    }

    // =========================================================================
    // Singleton
    // =========================================================================

    private static final RecentPlaces INSTANCE = new RecentPlaces();
    public static RecentPlaces getInstance() { return INSTANCE; }

    private final LinkedHashMap<String, Entry> map =
        new LinkedHashMap<>(MAX_SIZE * 2, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest)
        {
            return size() > MAX_SIZE;
        }
    };

    private ScopedPreferenceStore prefs;

    private RecentPlaces() {}

    // =========================================================================
    // Инициализация
    // =========================================================================

    public synchronized void init(String pluginId)
    {
        if (prefs != null) return;
        prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        load();
    }

    // =========================================================================
    // Публичный API
    // =========================================================================

    /**
     * Добавляет или обновляет запись.
     *
     * @param key         стабильный ключ (без номера строки)
     * @param navRef      ссылка для навигации (может содержать номер строки)
     * @param displayName строка для отображения
     * @param ownName     собственное имя для фильтрации
     */
    public synchronized void add(String key, String navRef,
                                  String displayName, String ownName)
    {
        if (key == null || key.isBlank()) return;
        map.put(key, new Entry(key, navRef, displayName, ownName, LocalDateTime.now()));
        save();
    }

    /** Возвращает список записей «новейшие первыми». */
    public synchronized List<Entry> getAll()
    {
        List<Entry> result = new ArrayList<>(map.values());
        Collections.reverse(result);
        return result;
    }

    public synchronized void clear()
    {
        map.clear();
        save();
    }

    public synchronized int size()
    {
        return map.size();
    }

    // =========================================================================
    // Сериализация
    // =========================================================================

    private void save()
    {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (Entry e : map.values())
        {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.visitedAt != null ? e.visitedAt.format(DT_FMT) : ""); //$NON-NLS-1$
            sb.append(SEP).append(escape(e.key));
            sb.append(SEP).append(escape(e.navRef));
            sb.append(SEP).append(escape(e.displayName));
            sb.append(SEP).append(escape(e.ownName));
        }
        prefs.setValue(PREF_KEY, sb.toString());
        try { prefs.save(); }
        catch (Exception ex) { Global.log("RecentPlaces.save error: " + ex); } //$NON-NLS-1$
    }

    private void load()
    {
        if (prefs == null) return;
        String raw = prefs.getString(PREF_KEY);
        if (raw == null || raw.isBlank()) return;

        for (String line : raw.split("\n", -1)) //$NON-NLS-1$
        {
            if (line.isBlank()) continue;
            // Новый формат: 5 полей. Старый (4 поля без navRef) — поддерживаем.
            String[] parts = line.split(String.valueOf(SEP), 5);
            try
            {
                if (parts.length == 5)
                {
                    LocalDateTime dt = parseDt(parts[0]);
                    String key         = unescape(parts[1]);
                    String navRef      = unescape(parts[2]);
                    String displayName = unescape(parts[3]);
                    String ownName     = unescape(parts[4]);
                    if (!key.isBlank())
                        map.put(key, new Entry(key, navRef, displayName, ownName, dt));
                }
                else if (parts.length == 4)
                {
                    // Совместимость со старым форматом (navRef = key)
                    LocalDateTime dt = parseDt(parts[0]);
                    String key         = unescape(parts[1]);
                    String displayName = unescape(parts[2]);
                    String ownName     = unescape(parts[3]);
                    if (!key.isBlank())
                        map.put(key, new Entry(key, key, displayName, ownName, dt));
                }
            }
            catch (Exception ex)
            {
                Global.log("RecentPlaces.load: skip line: " + ex); //$NON-NLS-1$
            }
        }
    }

    private static LocalDateTime parseDt(String s)
    {
        return (s == null || s.isBlank()) ? LocalDateTime.now()
                                          : LocalDateTime.parse(s, DT_FMT);
    }

    // =========================================================================
    // Экранирование
    // =========================================================================

    private static String escape(String s)
    {
        if (s == null) return ""; //$NON-NLS-1$
        return s.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\t", "\\t")  //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String unescape(String s)
    {
        if (s == null) return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (esc)
            {
                switch (c)
                {
                    case '\\': sb.append('\\'); break;
                    case 't':  sb.append('\t'); break;
                    case 'n':  sb.append('\n'); break;
                    default:   sb.append('\\'); sb.append(c); break;
                }
                esc = false;
            }
            else if (c == '\\') esc = true;
            else sb.append(c);
        }
        if (esc) sb.append('\\');
        return sb.toString();
    }
}
