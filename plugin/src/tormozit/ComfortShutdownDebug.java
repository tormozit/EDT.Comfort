package tormozit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;

/**
 * Экономное файловое логирование подготовки к закрытию EDT.
 * Пишет только при {@code preShutdown}; не зависит от флага «Общее логирование».
 */
public final class ComfortShutdownDebug
{
    private static final DateTimeFormatter TS =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS"); //$NON-NLS-1$

    private static volatile Path logPath;
    private static volatile long startedMs;
    private static volatile boolean sessionOpen;

    private ComfortShutdownDebug() {}

    static void begin(String reason)
    {
        startedMs = System.currentTimeMillis();
        sessionOpen = true;
        appendLine("BEGIN " + reason); //$NON-NLS-1$
    }

    static void step(String phase)
    {
        if (!sessionOpen)
            return;
        appendLine("+" + elapsedMs() + "ms " + phase); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void problem(String phase, String detail)
    {
        if (!sessionOpen)
            return;
        String msg = detail != null && !detail.isBlank() ? detail : "?"; //$NON-NLS-1$
        appendLine("+" + elapsedMs() + "ms [!] " + phase + ": " + msg); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    static void end(boolean ok)
    {
        if (!sessionOpen)
            return;
        appendLine("END " + (ok ? "ok" : "fail") + " +" + elapsedMs() + "ms total"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        sessionOpen = false;
    }

    private static long elapsedMs()
    {
        return Math.max(0L, System.currentTimeMillis() - startedMs);
    }

    private static void appendLine(String line)
    {
        try
        {
            Path path = resolveLogPath();
            String row = LocalDateTime.now().format(TS) + " " + line + System.lineSeparator(); //$NON-NLS-1$
            Files.writeString(path, row, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException ignored)
        {
            // shutdown path — не мешаем выходу
        }
    }

    private static Path resolveLogPath()
    {
        Path cached = logPath;
        if (cached != null)
            return cached;
        Path resolved = defaultLogPath();
        logPath = resolved;
        return resolved;
    }

    private static Path defaultLogPath()
    {
        try
        {
            Activator plugin = Activator.getDefault();
            if (plugin != null)
            {
                IPath state = Platform.getStateLocation(plugin.getBundle());
                if (state != null)
                    return Path.of(state.toOSString()).resolve("comfort-shutdown.log"); //$NON-NLS-1$
            }
        }
        catch (Exception ignored)
        {
        }
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (home != null && !home.isBlank())
            return Path.of(home, ".comfort-shutdown.log"); //$NON-NLS-1$
        return Path.of(System.getProperty("java.io.tmpdir", "."), "comfort-shutdown.log"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
