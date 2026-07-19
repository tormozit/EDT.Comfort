package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scrollable;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.ITheme;
import org.osgi.framework.Bundle;

/**
 * Подсветка совпадений smart-фильтра: единые цвета для текущей и прочих строк.
 */
public final class SmartMatchHighlight
{
    /** Fallback-фон списка, если системные цвета SWT ещё «светлые» в тёмной EDT */
    private static final RGB DARK_LIST_FALLBACK = new RGB(51, 51, 51);

    private static Color cachedLightForeground;
    private static Color cachedMatchBackground;
    private static Color cachedMatchForeground;
    /** Светлый RGB, из которого собраны кэшированные Color (для инвалидации при смене настройки). */
    private static RGB cachedLightRgb;
    private static RGB cachedMatchBaseRgb;
    private static Font cachedBoldFont;
    /** Кэш осветлённых системных FG для тёмной темы: SWT color id → owned Color. */
    private static final Map<Integer, Color> cachedEffectiveSystemColors = new HashMap<>();
    private static Boolean cachedEffectiveSystemDark;

    private SmartMatchHighlight() {}

    public static Styler styler()
    {
        return styler(null);
    }

    public static Styler styler(Control context)
    {
        return stylerFrom(resolveMatchStyle(context));
    }

    /** Только жирный шрифт — для совпадений на выбранной строке (без fg/bg). */
    public static Styler boldOnlyStyler()
    {
        return new Styler()
        {
            @Override
            public void applyStyles(TextStyle textStyle)
            {
                textStyle.font = boldFont();
            }
        };
    }

    public static void applyRanges(StyledString styled, Iterable<SmartMatcher.HighlightRange> ranges)
    {
        applyRanges(styled, ranges, null);
    }

    public static void applyRanges(StyledString styled, Iterable<SmartMatcher.HighlightRange> ranges, Control context)
    {
        if (styled == null || ranges == null)
            return;
        Styler styler = styler(context);
        for (SmartMatcher.HighlightRange range : ranges)
            styled.setStyle(range.offset, range.length, styler);
    }

    /** Overlay совпадений поверх owner-draw ячейки (после PaintItem платформы). */
    public static void paintTableCellMatchOverlay(Event e, Table table, TableItem item, SmartMatcher matcher)
    {
        paintTableCellMatchOverlay(e, table, item, matcher, false);
    }

    /** @param backgroundOnly {@code true} — только фон совпадений (текст уже нарисован). */
    public static void paintTableCellMatchOverlay(Event e, Table table, TableItem item, SmartMatcher matcher,
            boolean backgroundOnly)
    {
        if (e == null || e.gc == null || table == null || table.isDisposed() || item == null
                || item.isDisposed() || matcher == null || matcher.isEmpty)
            return;
        String text = item.getText(e.index);
        if (text == null || text.isEmpty())
            return;
        // Текст ячейки сам вида "Категория.Имя" (например, TypeComboOverlayHook —
        // "СправочникСсылка.Валюты") — matchesTree сам откатывается к последнему сегменту при
        // однословном фильтре, plain matches() по всей строке матчил бы и нелистовую часть.
        if (!matcher.matchesTree(text))
            return;
        Point origin = tableCellTextOrigin(e.gc, table, item, e.index, e, text);
        MatchStyle style = resolveMatchStyle(table);
        drawMatchFragments(e.gc, text, matcher, origin.x, origin.y, table.getFont(), style, backgroundOnly);
    }

    /** Жирный синий overlay поверх уже отрисованного SWT-текста (Label и др.). */
    public static void paintTextMatchOverlay(GC gc, Control control, String text, SmartMatcher matcher)
    {
        if (gc == null || control == null || control.isDisposed() || text == null || text.isEmpty()
                || matcher == null || matcher.isEmpty)
            return;
        Point origin = swtTextOrigin(gc, control, text);
        drawMatchFragments(gc, text, matcher, origin.x, origin.y, control.getFont(),
            resolveMatchStyle(control), false);
    }

    /** Жирный синий overlay для LWT LightLabel в координатах host-контрола. */
    public static void paintLwtTextMatchOverlay(GC gc, Control host, String text, SmartMatcher matcher,
            int originX, int originY, Object light)
    {
        if (gc == null || host == null || host.isDisposed() || text == null || text.isEmpty()
                || matcher == null || matcher.isEmpty)
            return;
        Font font = PropertySheetControlInterop.lwtFont(light);
        if (font == null)
            font = host.getFont();
        drawMatchFragments(gc, text, matcher, originX, originY, font, resolveMatchStyle(host), false);
    }

    public static void appendMatchRanges(ViewerCell cell, Iterable<SmartMatcher.HighlightRange> ranges)
    {
        if (cell == null || ranges == null)
            return;
        String text = cell.getText();
        if (text == null || text.isEmpty())
            return;

        StyleRange[] added = toStyleRanges(text, ranges, cell.getControl());
        if (added.length == 0)
            return;

        StyleRange[] existing = cell.getStyleRanges();
        if (existing == null || existing.length == 0)
        {
            cell.setStyleRanges(added);
            return;
        }
        StyleRange[] merged = new StyleRange[existing.length + added.length];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        System.arraycopy(added, 0, merged, existing.length, added.length);
        cell.setStyleRanges(merged);
    }

    static Color matchBackground(Color base)
    {
        return ListSelectionThemeColors.matchBackground(base);
    }

    static Color matchForeground(Color base)
    {
        return ListSelectionThemeColors.matchForeground(base);
    }

    private static final class MatchStyle
    {
        final Color foreground;
        final Color background;
        final Font font;

        MatchStyle(Color foreground, Color background, Font font)
        {
            this.foreground = foreground;
            this.background = background;
            this.font = font;
        }
    }

    private static MatchStyle resolveMatchStyle(Control context)
    {
        Display display = currentDisplay();
        RGB baseRgb = resolveMatchBaseRgb(context, display);
        Font font = boldFont();
        boolean dark = isDarkMatchRgb(baseRgb) || isDarkTheme();
        MatchStyle style;
        if (dark)
        {
            primeMatchColors(display);
            // Осветлённый FG из формулы — самостоятельный (как LIGHT_*), без плашки.
            // Плашка DARK_MATCH_BG была парой к почти чёрному DARK_MATCH_FG.
            style = new MatchStyle(cachedMatchForeground, null, font);
        }
        else
        {
            style = new MatchStyle(lightForeground(), null, font);
        }
        return style;
    }

    private static RGB resolveMatchBaseRgb(Control context, Display display)
    {
        // Поле фильтра / Label часто остаются со светлым SWT-фоном при тёмной
        // CSS-теме EDT — ищем реально тёмный фон у предков (Shell/страница).
        RGB ancestorDark = findDarkBackgroundRgb(context);
        if (ancestorDark != null)
            return ancestorDark;
        Color widget = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        if (isDarkLuminance(widget.getRGB()))
            return widget.getRGB();
        Color list = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        if (isDarkLuminance(list.getRGB()))
            return list.getRGB();
        if (isDarkTheme())
            return DARK_LIST_FALLBACK;
        return list.getRGB();
    }

    /** Тёмный фон у control или предков; {@code null} — не найден. */
    private static RGB findDarkBackgroundRgb(Control context)
    {
        Control walk = context;
        while (walk != null && !walk.isDisposed())
        {
            Color bg = walk.getBackground();
            if (bg != null && !bg.isDisposed())
            {
                RGB rgb = bg.getRGB();
                if (isDarkLuminance(rgb))
                    return rgb;
            }
            walk = walk.getParent();
        }
        return null;
    }

    private static boolean isDarkLuminance(RGB rgb)
    {
        if (rgb == null)
            return false;
        int lum = (rgb.red * 299 + rgb.green * 587 + rgb.blue * 114) / 1000;
        return lum < 128;
    }

    private static boolean isDarkMatchRgb(RGB rgb)
    {
        if (isDarkLuminance(rgb))
            return true;
        return isDarkTheme();
    }

    private static Styler stylerFrom(MatchStyle style)
    {
        return new Styler() {
            @Override
            public void applyStyles(TextStyle textStyle)
            {
                textStyle.foreground = style.foreground;
                textStyle.background = style.background;
                textStyle.font = style.font;
            }
        };
    }

    private static Point tableCellTextOrigin(GC gc, Table table, TableItem item, int column, Event e, String text)
    {
        Font font = table.getFont();
        if (font != null && !font.isDisposed())
            gc.setFont(font);
        Point ext = gc.textExtent(text, SWT.DRAW_TRANSPARENT | SWT.DRAW_DELIMITER);
        Rectangle textBounds = item.getTextBounds(column);
        if (textBounds != null && !textBounds.isEmpty())
        {
            int y = textBounds.y + Math.max(0, (textBounds.height - ext.y) / 2);
            return new Point(textBounds.x, y);
        }
        int y = e.y + Math.max(0, (e.height - ext.y) / 2);
        int x = e.x + 6;
        Rectangle imageBounds = item.getImageBounds(column);
        if (imageBounds != null && !imageBounds.isEmpty())
            x = imageBounds.x + imageBounds.width + 2;
        return new Point(x, y);
    }

    private static void drawMatchFragments(GC gc, String text, SmartMatcher matcher,
            int baseX, int baseY, Font baseFont, MatchStyle style, boolean backgroundOnly)
    {
        List<SmartMatcher.HighlightRange> ranges = matcher.getHighlightRanges(text);
        if (ranges.isEmpty())
            return;

        Font prevFont = gc.getFont();
        Color prevFg = gc.getForeground();
        Color prevBg = gc.getBackground();
        Font measureFont = baseFont != null && !baseFont.isDisposed() ? baseFont : prevFont;
        Font bold = boldFontFrom(measureFont);
        try
        {
            for (SmartMatcher.HighlightRange range : ranges)
            {
                if (range.offset < 0 || range.length <= 0 || range.offset + range.length > text.length())
                    continue;
                String prefix = text.substring(0, range.offset);
                String match = text.substring(range.offset, range.offset + range.length);
                gc.setFont(measureFont);
                int x = baseX + gc.textExtent(prefix, SWT.DRAW_TRANSPARENT | SWT.DRAW_DELIMITER).x;
                int w = gc.textExtent(match, SWT.DRAW_TRANSPARENT | SWT.DRAW_DELIMITER).x;
                int h = gc.textExtent(match, SWT.DRAW_TRANSPARENT | SWT.DRAW_DELIMITER).y;
                if (style.background != null)
                {
                    gc.setBackground(style.background);
                    gc.fillRectangle(x, baseY, w, h);
                }
                if (!backgroundOnly)
                {
                    gc.setFont(measureFont);
                    gc.setForeground(style.foreground);
                    gc.drawText(match, x, baseY, true);
                }
            }
        }
        finally
        {
            gc.setFont(prevFont);
            gc.setForeground(prevFg);
            gc.setBackground(prevBg);
            if (bold != cachedBoldFont && bold != null && !bold.isDisposed())
                bold.dispose();
        }
    }

    private static Point swtTextOrigin(GC gc, Control control, String text)
    {
        Font font = control.getFont();
        if (font != null)
            gc.setFont(font);

        String measureText = text;
        if (control instanceof Label)
        {
            String labelText = ((Label) control).getText();
            if (labelText != null && !labelText.isEmpty())
                measureText = labelText;
        }

        Point ext = gc.textExtent(measureText);
        int w = control.getSize().x;
        int h = control.getSize().y;
        int originX = 0;
        int originY = 0;
        if (control instanceof Scrollable)
        {
            Rectangle area = ((Scrollable) control).getClientArea();
            if (area.width > 0)
                w = area.width;
            if (area.height > 0)
                h = area.height;
            originX = area.x;
            originY = area.y;
        }

        int x = originX;
        if (control instanceof Label)
        {
            int style = ((Label) control).getStyle();
            if ((style & SWT.CENTER) != 0)
                x = originX + Math.max(0, (w - ext.x) / 2);
            else if ((style & SWT.RIGHT) != 0)
                x = originX + Math.max(0, w - ext.x);
        }
        int y = originY + Math.max(0, (h - ext.y) / 2);
        return new Point(x, y);
    }

    private static Font boldFontFrom(Font base)
    {
        if (base == null || base.isDisposed())
            return boldFont();
        FontData[] data = base.getFontData();
        for (FontData fd : data)
            fd.setStyle(fd.getStyle() | SWT.BOLD);
        return new Font(currentDisplay(), data);
    }

    private static StyleRange[] toStyleRanges(String text, Iterable<SmartMatcher.HighlightRange> ranges, Control context)
    {
        MatchStyle style = resolveMatchStyle(context);
        List<StyleRange> list = new ArrayList<>();
        for (SmartMatcher.HighlightRange range : ranges)
        {
            if (range.offset < 0 || range.length <= 0 || range.offset + range.length > text.length())
                continue;
            StyleRange sr = new StyleRange(range.offset, range.length, style.foreground, style.background);
            sr.font = style.font;
            list.add(sr);
        }
        return list.toArray(new StyleRange[0]);
    }

    /**
     * Эффективный цвет для текущей темы. Store всегда в координатах светлой темы;
     * в тёмной — {@link #invertLightness(RGB)} (HSL L' = 1−L, обратимо).
     */
    public static RGB toEffectiveRgb(RGB lightStored)
    {
        if (lightStored == null)
            return new RGB(0, 0, 0);
        RGB light = sanitizeStoredLightRgb(lightStored);
        if (!isDarkTheme())
            return light;
        return invertLightness(light);
    }

    /**
     * Нормализация к светлой теме для хранения. В тёмной теме — тот же
     * {@link #invertLightness(RGB)} (f∘f = id), в светлой — как есть.
     */
    public static RGB toStoredLightRgb(RGB displayed)
    {
        if (displayed == null)
            return new RGB(0, 0, 0);
        if (!isDarkTheme())
            return displayed;
        return invertLightness(displayed);
    }

    /**
     * Лечит store, куда по ошибке записали dark-effective вместо светлого:
     * яркий RGB (L&gt;0.55), после инверсии заметно темнее → возвращаем инверсию как истинный light.
     */
    public static RGB sanitizeStoredLightRgb(RGB stored)
    {
        if (stored == null)
            return new RGB(0, 0, 0);
        float l = hslLightness(stored);
        if (l <= 0.55f)
            return stored;
        RGB inverted = invertLightness(stored);
        float lInv = hslLightness(inverted);
        if (lInv < l - 0.05f)
            return inverted;
        return stored;
    }

    private static float hslLightness(RGB rgb)
    {
        float r = rgb.red / 255f;
        float g = rgb.green / 255f;
        float b = rgb.blue / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        return (max + min) / 2f;
    }

    /**
     * Эффективный системный цвет шрифта: в светлой теме — штатный SWT (не dispose);
     * в тёмной — owned Color из {@link #toEffectiveRgb(RGB)} (кэш, dispose в {@link #clearColorCache()}).
     */
    public static Color effectiveSystemColor(Display display, int swtColorId)
    {
        Display d = display != null && !display.isDisposed() ? display : currentDisplay();
        boolean dark = isDarkTheme();
        if (cachedEffectiveSystemDark != null && cachedEffectiveSystemDark.booleanValue() != dark)
            disposeEffectiveSystemColors();
        Color system = d.getSystemColor(swtColorId);
        if (!dark)
            return system;
        cachedEffectiveSystemDark = Boolean.TRUE;
        Color cached = cachedEffectiveSystemColors.get(Integer.valueOf(swtColorId));
        if (cached != null && !cached.isDisposed())
            return cached;
        Color created = new Color(d, toEffectiveRgb(system.getRGB()));
        cachedEffectiveSystemColors.put(Integer.valueOf(swtColorId), created);
        return created;
    }

    /**
     * Инверсия светлоты в HSL (H и S без изменений). Involutive: повтор даёт исходный RGB.
     * Универсальная схема light↔dark для цветов шрифта.
     */
    public static RGB invertLightness(RGB rgb)
    {
        float r = rgb.red / 255f;
        float g = rgb.green / 255f;
        float b = rgb.blue / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float h;
        float s;
        float l = (max + min) / 2f;
        if (max == min)
        {
            h = 0f;
            s = 0f;
        }
        else
        {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r)
                h = ((g - b) / d + (g < b ? 6f : 0f)) / 6f;
            else if (max == g)
                h = ((b - r) / d + 2f) / 6f;
            else
                h = ((r - g) / d + 4f) / 6f;
        }
        return hslToRgb(h, s, 1f - l);
    }

    private static RGB hslToRgb(float h, float s, float l)
    {
        float r;
        float g;
        float b;
        if (s == 0f)
        {
            r = g = b = l;
        }
        else
        {
            float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
            float p = 2f * l - q;
            r = hueToRgb(p, q, h + 1f / 3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3f);
        }
        return new RGB(clampByte(Math.round(r * 255f)), clampByte(Math.round(g * 255f)),
            clampByte(Math.round(b * 255f)));
    }

    private static int clampByte(int value)
    {
        return Math.max(0, Math.min(255, value));
    }

    private static float hueToRgb(float p, float q, float t)
    {
        float tt = t;
        if (tt < 0f)
            tt += 1f;
        if (tt > 1f)
            tt -= 1f;
        if (tt < 1f / 6f)
            return p + (q - p) * 6f * tt;
        if (tt < 1f / 2f)
            return q;
        if (tt < 2f / 3f)
            return p + (q - p) * (2f / 3f - tt) * 6f;
        return p;
    }

    /** Сброс кэша цветов (смена настройки / темы). */
    public static void clearColorCache()
    {
        disposeMatchColors();
        if (cachedLightForeground != null && !cachedLightForeground.isDisposed())
            cachedLightForeground.dispose();
        cachedLightForeground = null;
        cachedLightRgb = null;
        disposeEffectiveSystemColors();
    }

    private static void disposeEffectiveSystemColors()
    {
        for (Color c : cachedEffectiveSystemColors.values())
        {
            if (c != null && !c.isDisposed())
                c.dispose();
        }
        cachedEffectiveSystemColors.clear();
        cachedEffectiveSystemDark = null;
    }

    /** Package-private (не {@code private}) — переиспользуется вне resolveMatchStyle(), напр. TypeComboOverlayHook. */
    static Color lightForeground()
    {
        RGB light = ComfortSettings.getFilterMatchLightRgb();
        if (cachedLightForeground == null || cachedLightForeground.isDisposed()
            || cachedLightRgb == null || !cachedLightRgb.equals(light))
        {
            if (cachedLightForeground != null && !cachedLightForeground.isDisposed())
                cachedLightForeground.dispose();
            cachedLightForeground = new Color(currentDisplay(), light);
            cachedLightRgb = light;
        }
        return cachedLightForeground;
    }

    /**
     * Цвет совпадения «только текст» (без плашки): светлая тема — {@link #lightForeground()},
     * тёмная — осветлённый оттенок из {@link #primeMatchColors(Display)}.
     * Для Preferences и др., где background из {@link #styler(Control)} нельзя переносить.
     */
    static Color textOnlyForeground(Control context)
    {
        Display display = currentDisplay();
        RGB baseRgb = resolveMatchBaseRgb(context, display);
        boolean dark = isDarkMatchRgb(baseRgb) || isDarkTheme()
            || findDarkBackgroundRgb(context) != null;
        if (dark)
        {
            primeMatchColors(display);
            return cachedMatchForeground;
        }
        return lightForeground();
    }

    private static void primeMatchColors(Display display)
    {
        RGB light = ComfortSettings.getFilterMatchLightRgb();
        RGB expected = toEffectiveRgb(light);
        if (cachedMatchForeground != null && !cachedMatchForeground.isDisposed()
            && cachedLightRgb != null && cachedLightRgb.equals(light)
            && expected.equals(cachedMatchForeground.getRGB()))
            return;
        disposeMatchColors();
        cachedMatchForeground = new Color(display, expected);
        cachedLightRgb = light;
        cachedMatchBaseRgb = DARK_LIST_FALLBACK;
    }

    private static Font boldFont()
    {
        if (cachedBoldFont != null && !cachedBoldFont.isDisposed())
            return cachedBoldFont;
        Font defaultFont = JFaceResources.getDefaultFont();
        FontData[] data = defaultFont.getFontData();
        for (FontData fd : data)
            fd.setStyle(fd.getStyle() | SWT.BOLD);
        cachedBoldFont = new Font(currentDisplay(), data);
        return cachedBoldFont;
    }

    static boolean isDarkTheme()
    {
        try
        {
            if (PlatformUI.isWorkbenchRunning())
            {
                ITheme theme = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme();
                if (themeIdLooksDark(theme != null ? theme.getId() : null))
                    return true;
                // EDT тёмная тема живёт в e4 CSS, а не в IThemeManager
                if (e4CssThemeLooksDark())
                    return true;
            }
        }
        catch (Exception ignored) {}

        Display display = currentDisplay();
        Color listBg = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        if (ListSelectionThemeColors.isDarkColor(listBg))
            return true;
        Color widgetBg = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        if (ListSelectionThemeColors.isDarkColor(widgetBg))
            return true;
        return false;
    }

    private static boolean themeIdLooksDark(String id)
    {
        if (id == null)
            return false;
        String lower = id.toLowerCase();
        return lower.contains("dark") || lower.contains("dracula") || lower.contains("night");
    }

    private static boolean e4CssThemeLooksDark()
    {
        try
        {
            Bundle cssTheme = Platform.getBundle("org.eclipse.e4.ui.css.swt.theme"); //$NON-NLS-1$
            if (cssTheme == null)
                return false;
            Class<?> engineClass = cssTheme.loadClass("org.eclipse.e4.ui.css.swt.theme.IThemeEngine"); //$NON-NLS-1$
            Object engine = PlatformUI.getWorkbench().getService(engineClass);
            if (engine == null)
                return false;
            Object active = Global.invoke(engine, "getActiveTheme"); //$NON-NLS-1$
            if (active == null)
                return false;
            Object id = Global.invoke(active, "getId"); //$NON-NLS-1$
            return id instanceof String && themeIdLooksDark((String) id);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static Display currentDisplay()
    {
        Display current = Display.getCurrent();
        return current != null ? current : Display.getDefault();
    }

    private static void disposeMatchColors()
    {
        if (cachedMatchBackground != null && !cachedMatchBackground.isDisposed())
            cachedMatchBackground.dispose();
        if (cachedMatchForeground != null && !cachedMatchForeground.isDisposed())
            cachedMatchForeground.dispose();
        cachedMatchBackground = null;
        cachedMatchForeground = null;
        cachedMatchBaseRgb = null;
        // cachedLightRgb сбрасывает clearColorCache / lightForeground при смене pref
    }
}
