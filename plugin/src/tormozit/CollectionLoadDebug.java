package tormozit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** NDJSON-трейс загрузки коллекции (debug-сессия). */
final class CollectionLoadDebug
{
    private static final String SESSION = "74d17f"; //$NON-NLS-1$
    private static final Path LOG = Paths.get("C:\\VC\\EDT.Comfort\\debug-74d17f.log"); //$NON-NLS-1$

    private CollectionLoadDebug() {}

    // #region agent log
    static void log(String hypothesisId, String location, String message,
        String dataJson)
    {
        try
        {
            long ts = System.currentTimeMillis();
            String data = dataJson != null && !dataJson.isEmpty() ? dataJson : "{}"; //$NON-NLS-1$
            String line = "{\"sessionId\":\"" + SESSION //$NON-NLS-1$
                + "\",\"hypothesisId\":\"" + hypothesisId //$NON-NLS-1$
                + "\",\"location\":\"" + location //$NON-NLS-1$
                + "\",\"message\":\"" + message //$NON-NLS-1$
                + "\",\"data\":" + data //$NON-NLS-1$
                + ",\"timestamp\":" + ts + "}\n"; //$NON-NLS-1$
            Files.write(LOG, line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception ignored)
        {
            // debug-only
        }
    }
    // #endregion
}
