package tormozit;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.ITheme;

/**
 * Подсветка совпадений smart-фильтра: заметный синий, учёт светлой и тёмной темы Eclipse.
 */
public final class SmartMatchHighlight
{
    /** Светлая тема — насыщенный синий */
    private static final int LIGHT_R = 0;
    private static final int LIGHT_G = 102;
    private static final int LIGHT_B = 204;

    /** Тёмная тема — светло-голубой на тёмном фоне */
    private static final int DARK_R = 120;
    private static final int DARK_G = 200;
    private static final int DARK_B = 255;

    private static final Styler STYLER = new Styler() {
        @Override
        public void applyStyles(TextStyle textStyle)
        {
            textStyle.foreground = foreground();
            textStyle.font = boldFont();
        }
    };

    private static Color cachedForeground;
    private static Boolean cachedDark;
    private static Font cachedBoldFont;

    private SmartMatchHighlight() {}

    public static Styler styler()
    {
        return STYLER;
    }

    public static void applyRanges(StyledString styled, Iterable<SmartMatcher.HighlightRange> ranges)
    {
        if (styled == null || ranges == null)
            return;
        Styler styler = styler();
        for (SmartMatcher.HighlightRange range : ranges)
            styled.setStyle(range.offset, range.length, styler);
    }

    private static Color foreground()
    {
        boolean dark = isDarkTheme();
        if (cachedForeground == null || cachedForeground.isDisposed()
            || cachedDark == null || cachedDark.booleanValue() != dark)
        {
            disposeForeground();
            Display display = currentDisplay();
            if (dark)
                cachedForeground = new Color(display, DARK_R, DARK_G, DARK_B);
            else
                cachedForeground = new Color(display, LIGHT_R, LIGHT_G, LIGHT_B);
            cachedDark = dark;
        }
        return cachedForeground;
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
        Color bg = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        int lum = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000;
        return lum < 128;
    }

    private static Display currentDisplay()
    {
        Display current = Display.getCurrent();
        return current != null ? current : Display.getDefault();
    }

    private static void disposeForeground()
    {
        if (cachedForeground != null && !cachedForeground.isDisposed())
            cachedForeground.dispose();
        cachedForeground = null;
        cachedDark = null;
    }
}
