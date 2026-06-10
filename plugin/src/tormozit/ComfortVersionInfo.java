package tormozit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;

/**
 * Версия плагина EDT Comfort: номер, дата публикации и ссылка на описание релиза.
 */
public final class ComfortVersionInfo
{
    private static final String BUNDLE_SYMBOLIC_NAME = "tormozit.comfort"; //$NON-NLS-1$
    private static final String RELEASES_BASE_URL =
            "https://github.com/tormozit/EDT.Comfort/releases/tag/"; //$NON-NLS-1$
    private static final Pattern QUALIFIER_TIMESTAMP =
            Pattern.compile("-(\\d{12})$"); //$NON-NLS-1$
    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy"); //$NON-NLS-1$

    private final String baseVersion;
    private final String fullVersion;
    private final String displayDate;
    private final String changesUrl;

    private ComfortVersionInfo(
            String baseVersion, String fullVersion, String displayDate, String changesUrl)
    {
        this.baseVersion = baseVersion != null ? baseVersion : ""; //$NON-NLS-1$
        this.fullVersion = fullVersion != null ? fullVersion : this.baseVersion;
        this.displayDate = displayDate != null && !displayDate.isBlank()
                ? displayDate : "—"; //$NON-NLS-1$
        this.changesUrl = changesUrl != null ? changesUrl : ""; //$NON-NLS-1$
    }

    /** Номер версии без квалификатора сборки (для сравнения). */
    public String getVersion()
    {
        return baseVersion;
    }

    /** Полная OSGi/p2-версия для отображения (с квалификатором даты). */
    public String getDisplayVersion()
    {
        return fullVersion.isBlank() ? baseVersion : fullVersion;
    }

    public String getDisplayDate()
    {
        return displayDate;
    }

    public String getChangesUrl()
    {
        return changesUrl;
    }

    public boolean hasChangesUrl()
    {
        return !changesUrl.isBlank();
    }

    /** Версия установленного бандла {@code tormozit.comfort}. */
    public static ComfortVersionInfo installed()
    {
        Bundle bundle = Platform.getBundle(BUNDLE_SYMBOLIC_NAME);
        if (bundle == null)
            return empty();
        Version version = bundle.getVersion();
        return fromOsgiVersion(version != null ? version.toString() : ""); //$NON-NLS-1$
    }

    /** Разбор полной p2/OSGi-версии вида {@code 1.0.0.11-202606090924}. */
    public static ComfortVersionInfo fromOsgiVersion(String osgiVersion)
    {
        if (osgiVersion == null || osgiVersion.isBlank())
            return empty();

        String base = osgiVersion;
        String date = "—"; //$NON-NLS-1$
        Matcher m = QUALIFIER_TIMESTAMP.matcher(osgiVersion);
        if (m.find())
        {
            base = osgiVersion.substring(0, m.start());
            date = formatQualifierDate(m.group(1));
        }
        return new ComfortVersionInfo(base, osgiVersion, date, changesUrlFor(base));
    }

    /** Восстановление из кэша настроек (старые записи без квалификатора). */
    public static ComfortVersionInfo fromCached(String storedVersion, String storedDate)
    {
        ComfortVersionInfo info = fromOsgiVersion(storedVersion);
        if ("—".equals(info.displayDate) //$NON-NLS-1$
                && storedDate != null && !storedDate.isBlank() && !"—".equals(storedDate)) //$NON-NLS-1$
        {
            return new ComfortVersionInfo(
                info.baseVersion, info.fullVersion, storedDate, info.changesUrl);
        }
        return info;
    }

    public static String changesUrlFor(String baseVersion)
    {
        if (baseVersion == null || baseVersion.isBlank() || "—".equals(baseVersion)) //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        return RELEASES_BASE_URL + baseVersion;
    }

    /** Сравнение номеров версий (без квалификатора сборки). */
    public static int compareVersionNumbers(String left, String right)
    {
        if (left == null || left.isBlank() || "—".equals(left)) //$NON-NLS-1$
            return right == null || right.isBlank() || "—".equals(right) ? 0 : -1; //$NON-NLS-1$
        if (right == null || right.isBlank() || "—".equals(right)) //$NON-NLS-1$
            return 1;

        String[] leftParts = left.split("\\."); //$NON-NLS-1$
        String[] rightParts = right.split("\\."); //$NON-NLS-1$
        int len = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < len; i++)
        {
            int lv = i < leftParts.length ? parseSegment(leftParts[i]) : 0;
            int rv = i < rightParts.length ? parseSegment(rightParts[i]) : 0;
            if (lv != rv)
                return Integer.compare(lv, rv);
        }
        return 0;
    }

    private static ComfortVersionInfo empty()
    {
        return new ComfortVersionInfo("—", "—", "—", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static int parseSegment(String segment)
    {
        int dash = segment.indexOf('-');
        String numeric = dash >= 0 ? segment.substring(0, dash) : segment;
        try
        {
            return Integer.parseInt(numeric);
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private static String formatQualifierDate(String qualifier)
    {
        if (qualifier == null || qualifier.length() != 12)
            return "—"; //$NON-NLS-1$
        try
        {
            LocalDateTime dt = LocalDateTime.parse(
                qualifier,
                DateTimeFormatter.ofPattern("yyyyMMddHHmm")); //$NON-NLS-1$
            LocalDate date = dt.atZone(ZoneId.systemDefault()).toLocalDate();
            return DISPLAY_DATE.format(date);
        }
        catch (DateTimeParseException e)
        {
            return "—"; //$NON-NLS-1$
        }
    }
}
