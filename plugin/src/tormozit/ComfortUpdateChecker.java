package tormozit;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

/**
 * Проверка наличия новой версии плагина на p2-сайте
 * {@linkplain #UPDATE_SITE_URL} и уведомление пользователя.
 */
public final class ComfortUpdateChecker
{
    public static final String UPDATE_SITE_URL =
            "https://tormozit.github.io/EDT.Comfort/"; //$NON-NLS-1$

    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final Pattern FEATURE_VERSION = Pattern.compile(
        "id='tormozit\\.comfort\\.feature\\.feature\\.group' version='([^']+)'"); //$NON-NLS-1$
    private static final Pattern BUNDLE_VERSION = Pattern.compile(
        "id='tormozit\\.comfort' version='([^']+)'"); //$NON-NLS-1$
    private static final Pattern COMPOSITE_CHILD = Pattern.compile(
        "<child location='([^']+)'"); //$NON-NLS-1$

    private static volatile ComfortVersionInfo cachedLatest;
    private static volatile boolean checkInProgress;
    private static volatile Job scheduledCheckJob;
    private static volatile boolean schedulerStarted;

    public static boolean isCheckInProgress()
    {
        return checkInProgress;
    }

    private ComfortUpdateChecker()
    {
    }

    public static ComfortVersionInfo getInstalledVersion()
    {
        return ComfortVersionInfo.installed();
    }

    public static synchronized ComfortVersionInfo getCachedLatestVersion()
    {
        if (cachedLatest != null)
            return cachedLatest;

        var store = ComfortSettings.getInstance().getPreferenceStore();
        String version = store.getString(ComfortSettings.PREF_LATEST_VERSION);
        String date = store.getString(ComfortSettings.PREF_LATEST_VERSION_DATE);
        if (version == null || version.isBlank())
            return null;
        return ComfortVersionInfo.fromCached(version, date);
    }

    public static boolean isUpdateAvailable()
    {
        ComfortVersionInfo installed = getInstalledVersion();
        ComfortVersionInfo latest = getCachedLatestVersion();
        if (latest == null || latest.getVersion().isBlank())
            return false;
        return ComfortVersionInfo.compare(installed, latest) < 0;
    }

    /**
     * Запускает планировщик: при старте EDT — проверка, если прошло больше суток;
     * далее — повтор раз в сутки, пока EDT открыт.
     */
    public static synchronized void startDailyScheduler()
    {
        if (schedulerStarted)
            return;
        schedulerStarted = true;
        scheduleNextCheck(millisUntilNextCheck());
    }

    /**
     * Асинхронная проверка обновления.
     *
     * @param force    {@code true} — выполнить немедленно (открытие «Параметры»)
     * @param onFinish вызывается в UI-потоке после завершения (может быть {@code null})
     */
    public static void checkAsync(boolean force, Runnable onFinish)
    {
        if (!force && !isCheckDue())
            return;
        if (checkInProgress)
        {
            if (onFinish != null)
                runOnDisplayThread(onFinish);
            return;
        }

        checkInProgress = true;
        Job.create("Проверка обновления EDT Comfort", monitor -> { //$NON-NLS-1$
            try
            {
                performCheck();
            }
            finally
            {
                checkInProgress = false;
                if (onFinish != null)
                    runOnDisplayThread(onFinish);
            }
            return org.eclipse.core.runtime.Status.OK_STATUS;
        }).schedule();
    }

    private static synchronized void scheduleNextCheck(long delayMs)
    {
        if (!schedulerStarted)
            return;
        if (scheduledCheckJob != null)
            scheduledCheckJob.cancel();
        scheduledCheckJob = Job.create("Планировщик проверки EDT Comfort", monitor -> { //$NON-NLS-1$
            if (isCheckDue())
                performCheck();
            scheduleNextCheck(CHECK_INTERVAL_MS);
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        scheduledCheckJob.setSystem(true);
        scheduledCheckJob.schedule(delayMs);
    }

    private static long millisUntilNextCheck()
    {
        long lastCheck = ComfortSettings.getInstance().getPreferenceStore()
            .getLong(ComfortSettings.PREF_LAST_UPDATE_CHECK_MS);
        long elapsed = System.currentTimeMillis() - lastCheck;
        return Math.max(0, CHECK_INTERVAL_MS - elapsed);
    }

    private static void performCheck()
    {
        try
        {
            if (!isInternetAvailable())
                return;

            ComfortVersionInfo latest = fetchLatestVersion();
            if (latest != null)
            {
                cacheLatestVersion(latest);
                maybeShowUpdateToast(latest);
            }
            markCheckCompleted();
        }
        catch (Exception e)
        {
            Global.log("Проверка обновления EDT Comfort: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static boolean isCheckDue()
    {
        long lastCheck = ComfortSettings.getInstance().getPreferenceStore()
            .getLong(ComfortSettings.PREF_LAST_UPDATE_CHECK_MS);
        return System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS;
    }

    private static void markCheckCompleted()
    {
        var store = ComfortSettings.getInstance().getPreferenceStore();
        store.setValue(ComfortSettings.PREF_LAST_UPDATE_CHECK_MS,
            System.currentTimeMillis());
    }

    private static synchronized void cacheLatestVersion(ComfortVersionInfo latest)
    {
        cachedLatest = latest;
        var store = ComfortSettings.getInstance().getPreferenceStore();
        store.setValue(ComfortSettings.PREF_LATEST_VERSION, latest.getDisplayVersion());
        store.setValue(ComfortSettings.PREF_LATEST_VERSION_DATE,
            latest.getDisplayDate());
    }

    private static void maybeShowUpdateToast(ComfortVersionInfo latest)
    {
        if (!isUpdateAvailableFor(latest))
            return;

        var store = ComfortSettings.getInstance().getPreferenceStore();
        String notified = store.getString(ComfortSettings.PREF_LAST_NOTIFIED_VERSION);
        String latestKey = latest.getDisplayVersion();
        if (latestKey.equals(notified))
            return;

        store.setValue(ComfortSettings.PREF_LAST_NOTIFIED_VERSION, latestKey);

        String message = "Обнаружена новая версия " + latestKey; //$NON-NLS-1$
        runOnDisplayThread(() -> ToastNotification.show(
            "EDT Comfort", //$NON-NLS-1$
            message,
            8_000,
            ComfortPreferences::openComfortPreferencePage,
            "Открыть")); //$NON-NLS-1$
    }

    private static boolean isUpdateAvailableFor(ComfortVersionInfo latest)
    {
        ComfortVersionInfo installed = getInstalledVersion();
        return ComfortVersionInfo.compare(installed, latest) < 0;
    }

    private static boolean isInternetAvailable()
    {
        HttpURLConnection conn = null;
        try
        {
            URL url = URI.create(UPDATE_SITE_URL).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD"); //$NON-NLS-1$
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        }
        catch (IOException e)
        {
            return false;
        }
        finally
        {
            if (conn != null)
                conn.disconnect();
        }
    }

    private static ComfortVersionInfo fetchLatestVersion() throws IOException
    {
        String siteUrl = resolveLatestVersionSiteUrl();
        String xml = downloadContentXml(siteUrl + "content.jar"); //$NON-NLS-1$
        if (xml == null)
            xml = downloadText(siteUrl + "content.xml"); //$NON-NLS-1$
        if (xml == null || xml.isBlank())
            return null;
        return parseContentXml(xml);
    }

    /** Корневой composite-сайт или каталог последней версии. */
    private static String resolveLatestVersionSiteUrl() throws IOException
    {
        String compositeXml = downloadCompositeContentXml(UPDATE_SITE_URL);
        if (compositeXml == null)
            return UPDATE_SITE_URL;

        Matcher child = COMPOSITE_CHILD.matcher(compositeXml);
        if (!child.find())
            return UPDATE_SITE_URL;

        String location = child.group(1);
        if (location.startsWith("http://") || location.startsWith("https://")) //$NON-NLS-1$ //$NON-NLS-2$
            return location.endsWith("/") ? location : location + '/'; //$NON-NLS-1$
        return UPDATE_SITE_URL + location + (location.endsWith("/") ? "" : "/"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String downloadCompositeContentXml(String baseUrl) throws IOException
    {
        String xml = downloadContentXmlFromJar(baseUrl + "compositeContent.jar", //$NON-NLS-1$
            "compositeContent.xml"); //$NON-NLS-1$
        if (xml != null)
            return xml;
        return downloadText(baseUrl + "compositeContent.xml"); //$NON-NLS-1$
    }

    private static String downloadContentXml(String jarUrl) throws IOException
    {
        return downloadContentXmlFromJar(jarUrl, "content.xml"); //$NON-NLS-1$
    }

    private static String downloadContentXmlFromJar(String jarUrl, String entryName)
            throws IOException
    {
        HttpURLConnection conn = null;
        try
        {
            conn = openConnection(jarUrl);
            try (InputStream raw = conn.getInputStream();
                 ZipInputStream zip = new ZipInputStream(raw))
            {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null)
                {
                    if (entryName.equals(entry.getName()))
                        return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        catch (IOException e)
        {
            return null;
        }
        finally
        {
            if (conn != null)
                conn.disconnect();
        }
        return null;
    }

    private static String downloadText(String url) throws IOException
    {
        HttpURLConnection conn = null;
        try
        {
            conn = openConnection(url);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400)
                return null;
            try (InputStream in = conn.getInputStream())
            {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        finally
        {
            if (conn != null)
                conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url) throws IOException
    {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "*/*"); //$NON-NLS-1$ //$NON-NLS-2$
        return conn;
    }

    static ComfortVersionInfo parseContentXml(String xml)
    {
        Matcher feature = FEATURE_VERSION.matcher(xml);
        if (feature.find())
            return ComfortVersionInfo.fromOsgiVersion(feature.group(1));

        Matcher bundle = BUNDLE_VERSION.matcher(xml);
        if (bundle.find())
            return ComfortVersionInfo.fromOsgiVersion(bundle.group(1));

        return null;
    }

    private static void runOnDisplayThread(Runnable action)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            action.run();
        else
            display.asyncExec(action);
    }

}
