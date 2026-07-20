package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.ITheme;
import org.osgi.framework.Bundle;

/**
 * Универсальные цветовые операции с учётом темы: store всегда в координатах
 * светлой темы, в тёмной — эффективный RGB через инверсию светлоты HSL.
 */
public final class ThemeAwareColors
{
    /** Кэш осветлённых системных FG для тёмной темы: SWT color id → owned Color. */
    private static final Map<Integer, Color> cachedEffectiveSystemColors = new HashMap<>();
    private static Boolean cachedEffectiveSystemDark;

    private ThemeAwareColors() {}

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

    /** Сброс кэша эффективных системных цветов (смена темы). */
    public static void clearColorCache()
    {
        disposeEffectiveSystemColors();
    }

    public static boolean isDarkTheme()
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

    private static float hslLightness(RGB rgb)
    {
        float r = rgb.red / 255f;
        float g = rgb.green / 255f;
        float b = rgb.blue / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        return (max + min) / 2f;
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
}
