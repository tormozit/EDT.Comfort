package tormozit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** NDJSON-трейс save/restore геометрии окна «Коллекция» (сессия debug). */
final class CollectionWindowGeometryDebug
{
    private static final String SESSION = "74d17f"; //$NON-NLS-1$
    private static final Path LOG = Paths.get("C:\\VC\\EDT.Comfort\\debug-74d17f.log"); //$NON-NLS-1$

    private CollectionWindowGeometryDebug() {}

    // #region agent log
    static void log(String hypothesisId, String location, String message,
        int x, int y, int width, int height, boolean stored)
    {
        try
        {
            long ts = System.currentTimeMillis();
            String line = "{\"sessionId\":\"" + SESSION //$NON-NLS-1$
                + "\",\"hypothesisId\":\"" + hypothesisId //$NON-NLS-1$
                + "\",\"location\":\"" + location //$NON-NLS-1$
                + "\",\"message\":\"" + message //$NON-NLS-1$
                + "\",\"data\":{\"x\":" + x + ",\"y\":" + y //$NON-NLS-1$
                + ",\"width\":" + width + ",\"height\":" + height //$NON-NLS-1$
                + ",\"stored\":" + stored + "}" //$NON-NLS-1$
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
