package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;

/**
 * Цвета подсветки строк и ячеек в списках плагина — единые для таблиц и деревьев.
 *
 * <p>Единственное место, где заданы коэффициенты затемнения: подсветка выделенной строки, прочих
 * строк мультивыделения, активной ячейки и её рамки. Потребители ({@link FormTableInteraction} для
 * {@link org.eclipse.swt.widgets.Table}, {@link FormTreeInteraction} для
 * {@link org.eclipse.swt.widgets.Tree}) только кэшируют и освобождают полученные цвета — своих
 * оттенков не выдумывают, иначе одна и та же строка выглядела бы по-разному в разных окнах.
 *
 * <p><b>Два режима — из-за нативного выделения Windows.</b> В светлой теме список, оставивший
 * флаг {@link SWT#SELECTED} ({@link Mode#NATIVE_SELECTION}), поверх нашей заливки получает ещё и
 * системную подсветку выделения — наше затемнение там лишь слегка подкрашивает фон под ней.
 * Список, который этот флаг гасит ({@link Mode#OWN_SELECTION} — так делают деревья, где системная
 * заливка инвертирует цвета текста и убивает подсветку вхождений фильтра), рисует выделение
 * целиком сам, и та же слабая заливка выглядела бы как «строка не выделена». Поэтому у режимов
 * разная сила затемнения — но задана она в одном месте, здесь.
 *
 * <p>В тёмной теме оттенки берутся у {@link ListSelectionThemeColors} (системная подсветка там в
 * любом случае гасится), и режимы не различаются.
 *
 * <p>Каждый вызов создаёт НОВЫЙ {@link Color}: освобождает его вызывающий (обычно — при смене
 * фокуса списка и при его удалении).
 */
final class ListSelectionPalette
{
    /** Кто рисует само выделение строки в светлой теме. */
    enum Mode
    {
        /** Системная подсветка Windows остаётся ({@link SWT#SELECTED} не снимается) — таблицы. */
        NATIVE_SELECTION(0.045, 0.03, 0.034, 0.0225, 0.034, 0.0225),

        /** Выделение рисует сам список ({@link SWT#SELECTED} снят) — деревья с подсветкой текста. */
        OWN_SELECTION(0.12, 0.08, 0.08, 0.05, 0.08, 0.06);

        private final double rowFocused;

        private final double rowUnfocused;

        private final double inactiveRowFocused;

        private final double inactiveRowUnfocused;

        private final double activeCellFocused;

        private final double activeCellUnfocused;

        Mode(double rowFocused, double rowUnfocused, double inactiveRowFocused,
            double inactiveRowUnfocused, double activeCellFocused, double activeCellUnfocused)
        {
            this.rowFocused = rowFocused;
            this.rowUnfocused = rowUnfocused;
            this.inactiveRowFocused = inactiveRowFocused;
            this.inactiveRowUnfocused = inactiveRowUnfocused;
            this.activeCellFocused = activeCellFocused;
            this.activeCellUnfocused = activeCellUnfocused;
        }
    }

    /** Рамка активной ячейки — темнее её фона; одинакова в обоих режимах. */
    private static final double FRAME = 0.12;

    private ListSelectionPalette()
    {
    }

    static Color rowSelectionBackground(Control list, Mode mode)
    {
        boolean focused = list.isFocusControl();
        if (ListSelectionThemeColors.isDarkList(list))
            return ListSelectionThemeColors.listSelectionBackground(list, focused);
        return slightlyDarker(listBackground(list), focused ? mode.rowFocused : mode.rowUnfocused);
    }

    static Color inactiveRowSelectionBackground(Control list, Mode mode)
    {
        boolean focused = list.isFocusControl();
        if (ListSelectionThemeColors.isDarkList(list))
            return ListSelectionThemeColors.inactiveRowSelectionBackground(list, focused);
        return slightlyDarker(listBackground(list),
            focused ? mode.inactiveRowFocused : mode.inactiveRowUnfocused);
    }

    static Color activeCellBackground(Control list, Color rowBackground, Mode mode)
    {
        if (ListSelectionThemeColors.isDarkList(list))
            return ListSelectionThemeColors.activeCellBackground(list, rowBackground);
        return slightlyDarker(rowBackground,
            list.isFocusControl() ? mode.activeCellFocused : mode.activeCellUnfocused);
    }

    static Color activeCellFrame(Color activeCellBackground)
    {
        return slightlyDarker(activeCellBackground, FRAME);
    }

    static Color slightlyDarker(Color base, double factor)
    {
        Device device = base.getDevice();
        RGB rgb = base.getRGB();
        return new Color(device, channel(rgb.red, factor), channel(rgb.green, factor),
            channel(rgb.blue, factor));
    }

    private static Color listBackground(Control list)
    {
        Color base = list.getBackground();
        if (base == null || base.isDisposed())
            base = list.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        return base;
    }

    private static int channel(int value, double factor)
    {
        return Math.max(0, Math.min(255, (int)(value * (1.0 - factor))));
    }
}
