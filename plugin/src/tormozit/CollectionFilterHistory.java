package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

/**
 * Недавние значения smart-фильтра окна «Коллекция» (выпадающий список, как SearchBox навигатора).
 */
final class CollectionFilterHistory
{
    private static final int MAX_ITEMS = 20;
    private static final String PREF_COUNT = "comfort.collection.filter.history.count"; //$NON-NLS-1$
    private static final String PREF_ITEM_PREFIX = "comfort.collection.filter.history."; //$NON-NLS-1$
    private static final int REMEMBER_DELAY_MS = 120;
    /** Максимальная ширина поля smart-фильтра в окне «Коллекция». */
    static final int FILTER_FIELD_MAX_WIDTH = 400;

    private static ScopedPreferenceStore prefs;

    private CollectionFilterHistory() {}

    static GridData filterFieldLayoutData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.widthHint = FILTER_FIELD_MAX_WIDTH;
        gd.minimumWidth = 120;
        return gd;
    }

    static Combo createCombo(Composite parent, Runnable onModify)
    {
        Combo combo = new Combo(parent, SWT.BORDER | SWT.DROP_DOWN);
        combo.setToolTipText("Smart-фильтр (пробел = AND)"); //$NON-NLS-1$
        refreshItems(combo, null);
        if (onModify != null)
        {
            combo.addModifyListener(e -> onModify.run());
            combo.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    onModify.run();
                }
            });
        }
        combo.addListener(SWT.FocusOut, e -> scheduleRememberOnFocusLost(combo));
        return combo;
    }

    /** Компактная «✕» — без {@link org.eclipse.swt.widgets.ToolBar}, чтобы не раздувать строку фильтра. */
    static Label createClearButton(Composite parent, Runnable onClear)
    {
        Label clear = new Label(parent, SWT.NONE);
        clear.setText("✕"); //$NON-NLS-1$
        clear.setToolTipText("Очистить фильтр"); //$NON-NLS-1$
        clear.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        clear.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        clear.addListener(SWT.MouseDown, e -> {
            if (onClear != null)
                onClear.run();
        });
        return clear;
    }

    private static void scheduleRememberOnFocusLost(Combo combo)
    {
        if (combo == null || combo.isDisposed())
            return;
        combo.getDisplay().timerExec(REMEMBER_DELAY_MS, () -> {
            if (combo.isDisposed())
                return;
            Control focus = combo.getDisplay().getFocusControl();
            if (focus == combo)
                return;
            remember(combo.getText());
            refreshItems(combo, combo.getText());
        });
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

    private static void refreshItems(Combo combo, String keepText)
    {
        if (combo == null || combo.isDisposed())
            return;
        String current = keepText != null ? keepText : combo.getText();
        combo.setItems(loadAsArray());
        if (current != null)
            combo.setText(current);
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

    private static String[] loadAsArray()
    {
        List<String> items = load();
        return items.toArray(new String[0]);
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
}
