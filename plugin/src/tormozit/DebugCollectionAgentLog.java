package tormozit;

/**
 * Временная NDJSON-диагностика Debug Mode. Каждый {@link #beginSession()} очищает лог-файл.
 */
final class DebugCollectionAgentLog
{
    private static final String LOG_PATH = "C:\\VC\\EDT.Comfort\\debug-e49ee4.log"; //$NON-NLS-1$

    private static volatile String sessionId = ""; //$NON-NLS-1$

    private DebugCollectionAgentLog() {}

    /** Новая диагностическая сессия (открытие коллекции / restart прогона) — лог с нуля. */
    static synchronized void beginSession()
    {
        sessionId = Long.toHexString(System.currentTimeMillis());
        try
        {
            String header = "{\"sessionId\":\"" + sessionId //$NON-NLS-1$
                + "\",\"hypothesisId\":\"H00\",\"location\":\"DebugCollectionAgentLog\",\"message\":\"sessionStart\"" //$NON-NLS-1$
                + ",\"data\":{\"log\":\"" + LOG_PATH.replace("\\", "\\\\") + "\"}" //$NON-NLS-1$ //$NON-NLS-2$
                + ",\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(LOG_PATH),
                header,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
        }
        catch (Exception ignored)
        {
            // diagnostic only
        }
    }

    // #region agent log
    static void log(String hypothesisId, String location, String message, String dataJson)
    {
        if (sessionId.isEmpty())
            beginSession();
        try
        {
            String line = "{\"sessionId\":\"" + sessionId //$NON-NLS-1$
                + "\",\"hypothesisId\":\"" + hypothesisId //$NON-NLS-1$
                + "\",\"location\":\"" + location //$NON-NLS-1$
                + "\",\"message\":\"" + message //$NON-NLS-1$
                + "\",\"data\":" + dataJson //$NON-NLS-1$
                + ",\"timestamp\":" + System.currentTimeMillis() + "}\n"; //$NON-NLS-1$
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(LOG_PATH),
                line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        }
        catch (Exception ignored)
        {
            // diagnostic only
        }
    }
    // #endregion
}
