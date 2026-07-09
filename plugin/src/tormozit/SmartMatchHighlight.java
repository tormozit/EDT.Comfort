package tormozit;

import java.util.ArrayList;
import java.util.List;

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
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scrollable;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.ITheme;

/**
 * Подсветка совпадений smart-фильтра: единые цвета для текущей и прочих строк.
 */
public final class SmartMatchHighlight
{
    /** Светлая тема — насыщенный синий текст */
    private static final int LIGHT_R = 0;
    private static final int LIGHT_G = 102;
    private static final int LIGHT_B = 204;

    /** Fallback-фон списка, если системные цвета SWT ещё «светлые» в тёмной EDT */
    private static final RGB DARK_LIST_FALLBACK = new RGB(51, 51, 51);

    private static Color cachedLightForeground;
    private static Color cachedMatchBackground;
    private static Color cachedMatchForeground;
    private static RGB cachedMatchBaseRgb;
    private static Font cachedBoldFont;

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
        if (text == null || text.isEmpty() || !matcher.matches(text))
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
        if (isDarkMatchRgb(baseRgb) || isDarkTheme())
        {
            primeMatchColors(display);
            return new MatchStyle(cachedMatchForeground, cachedMatchBackground, font);
        }
        return new MatchStyle(lightForeground(), null, font);
    }

    private static RGB resolveMatchBaseRgb(Control context, Display display)
    {
        if (context != null && !context.isDisposed())
        {
            Color bg = context.getBackground();
            if (bg != null && !bg.isDisposed() && isDarkMatchRgb(bg.getRGB()))
                return bg.getRGB();
        }
        Color widget = display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        if (isDarkMatchRgb(widget.getRGB()))
            return widget.getRGB();
        Color list = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        if (isDarkMatchRgb(list.getRGB()))
            return list.getRGB();
        if (isDarkTheme())
            return DARK_LIST_FALLBACK;
        return list.getRGB();
    }

    private static boolean isDarkMatchRgb(RGB rgb)
    {
        if (rgb != null)
        {
            int lum = (rgb.red * 299 + rgb.green * 587 + rgb.blue * 114) / 1000;
            if (lum < 128)
                return true;
        }
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

    private static Color lightForeground()
    {
        if (cachedLightForeground == null || cachedLightForeground.isDisposed())
            cachedLightForeground = new Color(currentDisplay(), LIGHT_R, LIGHT_G, LIGHT_B);
        return cachedLightForeground;
    }

    private static void primeMatchColors(Display display)
    {
        if (cachedMatchBackground == null || cachedMatchBackground.isDisposed()
            || cachedMatchForeground == null || cachedMatchForeground.isDisposed())
        {
            disposeMatchColors();
            Color base = new Color(display, DARK_LIST_FALLBACK);
            try
            {
                cachedMatchBackground = ListSelectionThemeColors.matchBackground(base);
                cachedMatchForeground = ListSelectionThemeColors.matchForeground(base);
                cachedMatchBaseRgb = DARK_LIST_FALLBACK;
            }
            finally
            {
                if (!base.isDisposed())
                    base.dispose();
            }
        }
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
                if (theme != null)
                {
                    String id = theme.getId();
                    if (id != null)
                    {
                        String lower = id.toLowerCase();
                        if (lower.contains("dark") || lower.contains("dracula") || lower.contains("night"))
                            return true;
                    }
                }
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
    }
}
