package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AnnotationPreference;

/**
 * Фон ячейки колонки «Статус» в списках изменений Git («Индексирование Git», «История Git»):
 * «+» — как добавление, «-» и «!» — как удаление. Цвет берётся из маркеров Quick Diff
 * (Параметры → Выделение изменений) и смешивается с фоном списка — как клетка на полосе номеров.
 * Экземпляр владеет созданными {@link Color}; {@link #dispose()} вызывает владелец провайдера.
 */
final class ChangeStatusColors
{
    static final String ADDED = "+"; //$NON-NLS-1$
    static final String REMOVED = "-"; //$NON-NLS-1$
    static final String CONFLICT = "!"; //$NON-NLS-1$
    static final String RENAMED = "/"; //$NON-NLS-1$

    private static final String ADDITION_TYPE = "org.eclipse.ui.workbench.texteditor.quickdiff.addition"; //$NON-NLS-1$
    private static final String DELETION_TYPE = "org.eclipse.ui.workbench.texteditor.quickdiff.deletion"; //$NON-NLS-1$

    private final Map<RGB, Color> colors = new HashMap<>();

    /** Фон для символа статуса; {@code null} — для остальных значений фон не меняется. */
    Color background(Display display, String text)
    {
        boolean added = ADDED.equals(text);
        if (!added && !REMOVED.equals(text) && !CONFLICT.equals(text))
            return null;
        RGB marker = markerColor(added ? ADDITION_TYPE : DELETION_TYPE,
            added ? new RGB(144, 238, 144) : new RGB(255, 182, 193));
        RGB listBg = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
        double scale = added ? 0.6 : 0.75;
        RGB mixed = new RGB(
            (int) Math.round(marker.red * scale + listBg.red * (1 - scale)),
            (int) Math.round(marker.green * scale + listBg.green * (1 - scale)),
            (int) Math.round(marker.blue * scale + listBg.blue * (1 - scale)));
        return colors.computeIfAbsent(mixed, rgb -> new Color(display, rgb));
    }

    private static RGB markerColor(String annotationType, RGB fallback)
    {
        AnnotationPreference pref = EditorsUI.getAnnotationPreferenceLookup().getAnnotationPreference(annotationType);
        if (pref == null)
            return fallback;
        IPreferenceStore store = EditorsUI.getPreferenceStore();
        String key = pref.getColorPreferenceKey();
        RGB rgb = store != null && key != null && store.contains(key) && !store.isDefault(key)
            ? PreferenceConverter.getColor(store, key) : pref.getColorPreferenceValue();
        return rgb != null ? rgb : fallback;
    }

    void dispose()
    {
        colors.values().forEach(Color::dispose);
        colors.clear();
    }
}
