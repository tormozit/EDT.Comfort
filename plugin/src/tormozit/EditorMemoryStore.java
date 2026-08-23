package tormozit;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

/**
 * Персистентная память редакторов «ключ → значение», переживающая перезапуск EDT.
 *
 * <p>Значение обновляется в памяти (дёшево — можно звать на каждое изменение выделения/каретки),
 * а на диск попадает только по {@link #flush()} — при уходе с редактора или его закрытии.
 * Хранится не больше {@code maxSize} записей: при переполнении вытесняется самая давняя
 * (обновление значения делает запись самой свежей).
 *
 * <p>Формат в настройках: строки, разделённые {@code \n}, в строке {@code ключ\tзначение}.
 * Значение может само содержать {@code \t} (позиция каретки — «строка\tколонка\tверхняя строка»),
 * поэтому строка разбирается только по первому разделителю.
 *
 * <p>Потребители: {@link BslModulePositionMemoryHook} (позиция каретки в модуле),
 * {@link FormEditorHook} (текущий элемент дерева формы). Каждый заводит свой экземпляр
 * со своим ключом настроек.
 */
final class EditorMemoryStore
{
    private static final char SEP = '\t';

    private static ScopedPreferenceStore prefs;

    private final String prefKey;

    private final int maxSize;

    private Map<String, String> cache;

    EditorMemoryStore(String prefKey, int maxSize)
    {
        this.prefKey = prefKey;
        this.maxSize = maxSize;
    }

    /** Значение для ключа или {@code null}, если оно не запоминалось. */
    synchronized String load(String key)
    {
        ensureLoaded();
        return cache.get(key);
    }

    /** Обновляет значение в памяти, без записи на диск. */
    synchronized void updateMemory(String key, String value)
    {
        ensureLoaded();
        cache.remove(key);
        cache.put(key, value);
        while (cache.size() > maxSize)
        {
            String oldest = cache.keySet().iterator().next();
            cache.remove(oldest);
        }
    }

    /** Сбрасывает текущее состояние памяти на диск. */
    synchronized void flush()
    {
        ensureLoaded();
        persist();
    }

    private void ensureLoaded()
    {
        if (cache != null)
            return;
        cache = new LinkedHashMap<>();
        ScopedPreferenceStore store = prefs();
        if (store == null)
            return;
        String raw = store.getString(prefKey);
        if (raw == null || raw.isBlank())
            return;
        for (String line : raw.split("\n")) //$NON-NLS-1$
        {
            int sep = line.indexOf(SEP);
            if (sep <= 0)
                continue;
            cache.put(line.substring(0, sep), line.substring(sep + 1));
        }
    }

    private void persist()
    {
        ScopedPreferenceStore store = prefs();
        if (store == null)
            return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cache.entrySet())
        {
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(e.getKey()).append(SEP).append(e.getValue());
        }
        store.setValue(prefKey, sb.toString());
        try
        {
            store.save();
        }
        catch (Exception ignored)
        {
            // Настройки опциональны: без них память просто не переживёт перезапуск.
        }
    }

    private static synchronized ScopedPreferenceStore prefs()
    {
        if (prefs != null)
            return prefs;
        try
        {
            String pluginId = FrameworkUtil.getBundle(EditorMemoryStore.class).getSymbolicName();
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        }
        catch (Exception ignored)
        {
            return null;
        }
        return prefs;
    }
}
