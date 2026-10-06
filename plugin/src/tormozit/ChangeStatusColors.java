package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AnnotationPreference;

/**
 * Цвета ячейки колонки «Статус» в списках изменений Git («Индексирование Git», «История Git»):
 * «+» — как добавление, «-» и «!» — как удаление. Цвет берётся из маркеров Quick Diff
 * (Параметры → Выделение изменений), адаптируется к теме и смешивается с фактическим фоном списка.
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

    /** Вызывать после подсветки фильтра; выделение строки остаётся за её owner-draw. */
    void apply(ViewerCell cell)
    {
        Control control = cell.getControl();
        String text = cell.getText();
        Color bg = background(control, text);
        if (bg == null)
            return;
        cell.setBackground(bg);
        cell.setForeground(control.getForeground());
    }

    /** Фон для символа статуса; {@code null} — для остальных значений фон не меняется. */
    Color background(Control control, String text)
    {
        boolean added = ADDED.equals(text);
        if (!added && !REMOVED.equals(text) && !CONFLICT.equals(text))
            return null;
        RGB marker = markerColor(added ? ADDITION_TYPE : DELETION_TYPE,
            added ? new RGB(144, 238, 144) : new RGB(255, 182, 193));
        boolean dark = ListSelectionThemeColors.isDarkList(control);
        if (dark)
            marker = ThemeAwareColors.invertLightness(marker);
        RGB listBg = ListSelectionThemeColors.listBackgroundRgb(control);
        // В тёмной теме маркер удаления после инверсии почти чёрный: сильнее разбавляем фоном.
        double scale = added ? 0.6 : (dark ? 0.4 : 0.75);
        RGB mixed = new RGB(
            (int) Math.round(marker.red * scale + listBg.red * (1 - scale)),
            (int) Math.round(marker.green * scale + listBg.green * (1 - scale)),
            (int) Math.round(marker.blue * scale + listBg.blue * (1 - scale)));
        return colors.computeIfAbsent(mixed, rgb -> new Color(control.getDisplay(), rgb));
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
