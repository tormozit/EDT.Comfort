package tormozit;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Версия появления слова платформы в подсказке редактора и синтакс-помощнике.
 * API страниц и поле titleId проверены в бандле bsl.ui (EDT 22).
 */
final class PlatformWordAvailability
{
    private static final String MARKER = "comfort-platform-word-availability";
    private static final Pattern VERSION = Pattern.compile("(?<![\\d.])(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?!\\d)");

    private PlatformWordAvailability() {}

    static String hoverBlock(Object input, String color)
    {
        Object page = Global.invoke(input, "getViewPage");
        String text = availabilityText(page);
        Object version = page == null ? null : Global.invoke(page, "getVersion");
        boolean recent = text != null && version instanceof Version current && isRecent(text, current);
        if (!recent)
            return null;
        String plain = text.replaceAll("<[^>]*>", "").trim();
        String escaped = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<div id=\"" + MARKER + "\" style=\"margin:4px 0;"
            + "color:" + color + ";\">" + escaped + "</div>";
    }

    static String injectHoverBlock(String html, String block)
    {
        if (html == null || block == null || html.contains(MARKER))
            return html;
        int insertAt = html.indexOf("<div class=\"hover-content\">");
        if (insertAt < 0)
            return html;
        return html.substring(0, insertAt) + block + html.substring(insertAt);
    }

    static void styleSyntaxPage(Object browser, String color)
    {
        Object descriptor = Global.invoke(browser, "getPageDescriptor");
        if (descriptor == null || !descriptor.getClass().getName().equals(
            "com._1c.g5.v8.dt.internal.bsl.ui.syntaxassist.description.DocumentationPageDescriptor"))
            return;
        Object page = Global.getField(descriptor, "page");
        String text = availabilityText(page);
        Object version = page == null ? null : Global.invoke(page, "getVersion");
        boolean recent = text != null && version instanceof Version current && isRecent(text, current);
        // versioninfo сохраняется из PlatformDocPage в getViewText дополнительной главы.
        String script = "(function(){var d=document,s=d.getElementById('" + MARKER + "');"
            + "if(s)s.parentNode.removeChild(s);"
            + (recent ? "if(d.head){s=d.createElement('style');s.id='" + MARKER + "';"
                + "s.textContent='.versioninfo{color:" + color + " !important}';d.head.appendChild(s);}" : "")
            + "return d.querySelectorAll('.versioninfo').length;})()";
        Global.invoke(browser, "executeScript", script);
    }

    private static String availabilityText(Object page)
    {
        if (page == null)
            return null;
        Object chapters = Global.invoke(page, "getAdditionalChapters");
        if (chapters instanceof List<?> list)
        {
            for (Object chapter : list)
            {
                if ("availablesince".equals(Global.getField(chapter, "titleId")))
                {
                    Object text = Global.invoke(chapter, "getHoverText");
                    return text instanceof String value && !value.isBlank() ? value : null;
                }
            }
        }
        return null;
    }

    static boolean isRecent(String text, Version current)
    {
        if (text == null || current == null)
            return false;
        Matcher match = VERSION.matcher(text.replaceAll("<[^>]*>", ""));
        if (!match.find())
            return false;
        Version since;
        try
        {
            since = new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                match.group(3) == null ? 0 : Integer.parseInt(match.group(3)));
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
        if (since.compareTo(current) > 0)
            return false;
        if (since.getMajor() == current.getMajor() && since.getMinor() == current.getMinor())
            return current.getMicro() - since.getMicro() < 3;
        // На переходе 8.3 → 8.5 считаем выпуски по списку самой EDT.
        List<Version> releases = Version.getPlatformSupportVersions();
        if (!releases.contains(since) || !releases.contains(current))
            return false;
        long distance = releases.stream().filter(v -> v.compareTo(since) > 0 && v.compareTo(current) <= 0).count();
        return distance < 3;
    }

    static String recentColor()
    {
        return ThemeAwareColors.isDarkTheme() ? "#D9819B" : "#800020";
    }
}
