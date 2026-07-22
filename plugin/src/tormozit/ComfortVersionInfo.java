package tormozit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;

/**
 * Версия плагина EDT Comfort: номер, дата публикации и ссылка на описание релиза.
 */
public final class ComfortVersionInfo
{
    private static final String BUNDLE_SYMBOLIC_NAME = "tormozit.comfort"; //$NON-NLS-1$
    private static final String FEATURE_IU_ID =
            "tormozit.comfort.feature.feature.group"; //$NON-NLS-1$
    private static final String BUNDLE_P2_CORE = "org.eclipse.equinox.p2.core"; //$NON-NLS-1$
    private static final String BUNDLE_P2_ENGINE = "org.eclipse.equinox.p2.engine"; //$NON-NLS-1$
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

    /** Номер версии без квалификатора сборки. */
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

    /** Версия установленного плагина (max из bundle и feature-IU в p2-профиле). */
    public static ComfortVersionInfo installed()
    {
        ComfortVersionInfo bundle = fromBundle();
        ComfortVersionInfo feature = fromInstalledFeatureGroup();
        if (feature == null || "—".equals(feature.getVersion())) //$NON-NLS-1$
            return bundle;
        if ("—".equals(bundle.getVersion())) //$NON-NLS-1$
            return feature;
        return newerOf(bundle, feature);
    }

    private static ComfortVersionInfo fromBundle()
    {
        Bundle bundle = Platform.getBundle(BUNDLE_SYMBOLIC_NAME);
        if (bundle == null)
            return empty();
        Version version = bundle.getVersion();
        return fromOsgiVersion(version != null ? version.toString() : ""); //$NON-NLS-1$
    }

    private static ComfortVersionInfo newerOf(ComfortVersionInfo left, ComfortVersionInfo right)
    {
        return compare(left, right) >= 0 ? left : right;
    }

    /** Версия установленной feature-IU из p2-профиля EDT. */
    private static ComfortVersionInfo fromInstalledFeatureGroup()
    {
        try
        {
            Bundle p2Core = Platform.getBundle(BUNDLE_P2_CORE);
            Bundle p2Engine = Platform.getBundle(BUNDLE_P2_ENGINE);
            if (p2Core == null || p2Engine == null)
                return null;

            BundleContext ctx = p2Core.getBundleContext();
            ServiceReference<?> agentRef = ctx.getServiceReference(
                "org.eclipse.equinox.p2.core.IProvisioningAgent"); //$NON-NLS-1$
            if (agentRef == null)
                return null;

            Object agent = ctx.getService(agentRef);
            if (agent == null)
                return null;

            try
            {
                Class<?> registryClass = p2Engine.loadClass(
                    "org.eclipse.equinox.p2.engine.IProfileRegistry"); //$NON-NLS-1$
                Object registry = agent.getClass()
                    .getMethod("getService", Class.class) //$NON-NLS-1$
                    .invoke(agent, registryClass);
                if (registry == null)
                    return null;

                String profileId = (String) registryClass
                    .getMethod("getDefaultProfileId") //$NON-NLS-1$
                    .invoke(registry);
                Object profile = registryClass
                    .getMethod("getProfile", String.class) //$NON-NLS-1$
                    .invoke(registry, profileId);
                if (profile == null)
                    return null;

                Class<?> queryUtil = p2Engine.loadClass(
                    "org.eclipse.equinox.p2.query.QueryUtil"); //$NON-NLS-1$
                Class<?> queryClass = p2Engine.loadClass(
                    "org.eclipse.equinox.p2.query.IQuery"); //$NON-NLS-1$
                Class<?> monitorClass = Platform.getBundle("org.eclipse.core.runtime") //$NON-NLS-1$
                    .loadClass("org.eclipse.core.runtime.IProgressMonitor"); //$NON-NLS-1$

                Object query = queryUtil
                    .getMethod("createIUQuery", String.class) //$NON-NLS-1$
                    .invoke(null, FEATURE_IU_ID);
                Object result = profile.getClass()
                    .getMethod("query", queryClass, monitorClass) //$NON-NLS-1$
                    .invoke(profile, query, new NullProgressMonitor());

                var iterator = (java.util.Iterator<?>) result.getClass()
                    .getMethod("iterator") //$NON-NLS-1$
                    .invoke(result);
                if (!iterator.hasNext())
                    return null;

                Object iu = iterator.next();
                Object version = iu.getClass().getMethod("getVersion").invoke(iu); //$NON-NLS-1$
                return fromOsgiVersion(version != null ? version.toString() : ""); //$NON-NLS-1$
            }
            finally
            {
                ctx.ungetService(agentRef);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
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

    /**
     * Полное сравнение версий: сначала числовые сегменты базы, при равенстве —
     * квалификатор сборки ({@code yyyyMMddHHmm} после {@code -}).
     */
    public static int compare(ComfortVersionInfo left, ComfortVersionInfo right)
    {
        if (left == null && right == null)
            return 0;
        if (left == null)
            return -1;
        if (right == null)
            return 1;

        int cmp = compareVersionNumbers(left.getVersion(), right.getVersion());
        if (cmp != 0)
            return cmp;
        return compareBuildQualifiers(left.getDisplayVersion(), right.getDisplayVersion());
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

    /**
     * Сравнение timestamp-квалификаторов полной OSGi-версии.
     * Без квалификатора считается старше версии с квалификатором; при равной базе
     * {@code yyyyMMddHHmm} сравнивается лексикографически (совпадает с хронологией).
     */
    static int compareBuildQualifiers(String leftFull, String rightFull)
    {
        String leftQ = extractBuildQualifier(leftFull);
        String rightQ = extractBuildQualifier(rightFull);
        if (leftQ.isEmpty() && rightQ.isEmpty())
        {
            String left = leftFull != null ? leftFull : ""; //$NON-NLS-1$
            String right = rightFull != null ? rightFull : ""; //$NON-NLS-1$
            return left.compareTo(right);
        }
        if (leftQ.isEmpty())
            return -1;
        if (rightQ.isEmpty())
            return 1;
        return leftQ.compareTo(rightQ);
    }

    private static String extractBuildQualifier(String osgiVersion)
    {
        if (osgiVersion == null || osgiVersion.isBlank())
            return ""; //$NON-NLS-1$
        Matcher m = QUALIFIER_TIMESTAMP.matcher(osgiVersion);
        return m.find() ? m.group(1) : ""; //$NON-NLS-1$
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
