package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.common.ui.controls.search.ISearchHistory;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

/**
 * Недавние значения smart-фильтра окна «Коллекция» — штатный {@link SearchBox}, как в окне «Значения» EDT.
 */
final class CollectionFilterHistory
{
    private static final int MAX_ITEMS = 20;
    private static final String PREF_COUNT = "comfort.collection.filter.history.count"; //$NON-NLS-1$
    private static final String PREF_ITEM_PREFIX = "comfort.collection.filter.history."; //$NON-NLS-1$
    /** Максимальная ширина поля smart-фильтра в окне «Коллекция». */
    static final int FILTER_FIELD_MAX_WIDTH = 267;
    /** Отступ справа от поля фильтра до следующего элемента строки. */
    static final int FILTER_FIELD_RIGHT_MARGIN = 10;

    private static ScopedPreferenceStore prefs;

    private CollectionFilterHistory() {}

    static GridData filterFieldLayoutData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = FILTER_FIELD_MAX_WIDTH;
        gd.minimumWidth = 80;
        return gd;
    }

    static SearchBox createSearchBox(Composite parent, Runnable onSearch)
    {
        SearchBox box = new SearchBox(parent);
        box.setLayoutData(filterFieldLayoutData());
        box.setToolTipText("Smart-фильтр (пробел = AND)"); //$NON-NLS-1$
        box.setMessage("Поиск..."); //$NON-NLS-1$
        box.setMinimumSearchTextLength(0);
        box.setSearchDelay(150);
        box.setHistory(new PrefsSearchHistory());
        if (onSearch != null)
            box.setSearchListener((text, monitor) -> onSearch.run());
        return box;
    }

    static void addFieldTrailingSpacer(Composite parent)
    {
        Label spacer = new Label(parent, SWT.NONE);
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = FILTER_FIELD_RIGHT_MARGIN;
        spacer.setLayoutData(gd);
    }

    static void remember(String pattern)
    {
        if (pattern == null)
            return;
        final String trimmed = pattern.trim();
        if (trimmed.isEmpty())
            return;

        List<String> items = new ArrayList<>(load());
        items.removeIf(existing -> trimmed.equalsIgnoreCase(existing));
        items.add(0, trimmed);
        while (items.size() > MAX_ITEMS)
            items.remove(items.size() - 1);
        save(items);
    }

    private static List<String> load()
    {
        ScopedPreferenceStore store = prefs();
        if (store == null)
            return new ArrayList<>();
        int count = store.getInt(PREF_COUNT);
        if (count <= 0)
            return new ArrayList<>();
        List<String> items = new ArrayList<>(Math.min(count, MAX_ITEMS));
        for (int i = 0; i < count && i < MAX_ITEMS; i++)
        {
            String value = store.getString(PREF_ITEM_PREFIX + i);
            if (value != null && !value.isBlank())
                items.add(value.trim());
        }
        return items;
    }

    private static void save(List<String> items)
    {
        ScopedPreferenceStore store = prefs();
        if (store == null || items == null)
            return;
        int count = Math.min(items.size(), MAX_ITEMS);
        store.setValue(PREF_COUNT, count);
        for (int i = 0; i < count; i++)
            store.setValue(PREF_ITEM_PREFIX + i, items.get(i));
        for (int i = count; i < MAX_ITEMS; i++)
            store.setToDefault(PREF_ITEM_PREFIX + i);
        try
        {
            store.save();
        }
        catch (Exception ignored)
        {
            // prefs optional
        }
    }

    private static ScopedPreferenceStore prefs()
    {
        if (prefs != null)
            return prefs;
        try
        {
            String pluginId = FrameworkUtil.getBundle(CollectionFilterHistory.class).getSymbolicName();
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        }
        catch (Exception ignored)
        {
            return null;
        }
        return prefs;
    }

    private static final class PrefsSearchHistory implements ISearchHistory
    {
        @Override
        public void savePattern(String pattern)
        {
            remember(pattern);
        }

        @Override
        public void replacePattern(String pattern)
        {
            remember(pattern);
        }

        @Override
        public String getActivePattern()
        {
            return ""; //$NON-NLS-1$
        }

        @Override
        public List<String> getRecentPatterns(int max)
        {
            List<String> items = load();
            if (max <= 0 || items.size() <= max)
                return items;
            return new ArrayList<>(items.subList(0, max));
        }
    }
}
