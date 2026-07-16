package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

/**
 * Персистентная история значений строки фильтра, отдельная по {@code scopeId}
 * для каждого места использования (например, поиск по «Параметрам» и фильтр
 * страницы «Клавиши» не должны видеть историю друг друга). По образцу
 * {@code FilterInputBox}, но без зависимости от его закрытого {@code Scope}.
 */
final class FilterHistoryStore
{
    private static final int MAX_ITEMS = 20;

    private static ScopedPreferenceStore prefs;

    private FilterHistoryStore()
    {
    }

    static void remember(String scopeId, String pattern)
    {
        if (pattern == null)
            return;
        String trimmed = pattern.trim();
        if (trimmed.isEmpty())
            return;

        List<String> items = new ArrayList<>(load(scopeId));
        items.removeIf(existing -> trimmed.equalsIgnoreCase(existing));
        items.add(0, trimmed);
        while (items.size() > MAX_ITEMS)
            items.remove(items.size() - 1);
        save(scopeId, items);
    }

    static List<String> load(String scopeId)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null)
            return new ArrayList<>();
        int count = store.getInt(countKey(scopeId));
        if (count <= 0)
            return new ArrayList<>();
        List<String> items = new ArrayList<>(Math.min(count, MAX_ITEMS));
        for (int i = 0; i < count && i < MAX_ITEMS; i++)
        {
            String value = store.getString(itemKey(scopeId, i));
            if (value != null && !value.isBlank())
                items.add(value.trim());
        }
        return items;
    }

    private static void save(String scopeId, List<String> items)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null || items == null)
            return;
        int count = Math.min(items.size(), MAX_ITEMS);
        store.setValue(countKey(scopeId), count);
        for (int i = 0; i < count; i++)
            store.setValue(itemKey(scopeId, i), items.get(i));
        for (int i = count; i < MAX_ITEMS; i++)
            store.setToDefault(itemKey(scopeId, i));
        try
        {
            store.save();
        }
        catch (Exception ex)
        {
            Global.log("FilterHistoryStore.save error: " + ex); //$NON-NLS-1$
        }
    }

    private static String countKey(String scopeId)
    {
        return "comfort." + scopeId + ".filter.history.count"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String itemKey(String scopeId, int index)
    {
        return "comfort." + scopeId + ".filter.history." + index; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ScopedPreferenceStore prefs()
    {
        if (prefs != null)
            return prefs;
        try
        {
            String pluginId = FrameworkUtil.getBundle(FilterHistoryStore.class).getSymbolicName();
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        }
        catch (Exception ignored)
        {
            return null;
        }
        return prefs;
    }
}
