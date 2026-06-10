package tormozit;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Общий отладочный журнал плагина ({@link ContentAssistLogView}).
 * Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class ComfortDebug
{
    private ComfortDebug()
    {
    }

    public static boolean isEnabled()
    {
        return ComfortSettings.isDebugLogEnabled();
    }

    public static void log(String tag, String message)
    {
        if (!isEnabled() || message == null)
            return;
        ContentAssistLog.append(formatTag(tag) + message);
    }

    public static void logError(String tag, String message, Throwable error)
    {
        if (!isEnabled())
            return;
        String text = message != null ? message : ""; //$NON-NLS-1$
        Throwable root = unwrap(error);
        if (root != null)
        {
            String detail = root.getClass().getSimpleName();
            if (root.getMessage() != null && !root.getMessage().isBlank())
                detail += ": " + root.getMessage(); //$NON-NLS-1$
            text = text.isBlank() ? detail : text + ": " + detail; //$NON-NLS-1$
        }
        ContentAssistLog.append(formatTag(tag) + "ERROR " + text); //$NON-NLS-1$
        if (root != null)
            appendStackTrace(root);
    }

    private static void appendStackTrace(Throwable error)
    {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        for (String line : sw.toString().split("\n")) //$NON-NLS-1$
        {
            if (line.isBlank())
                continue;
            ContentAssistLog.append("    " + line); //$NON-NLS-1$
        }
    }

    private static String formatTag(String tag)
    {
        if (tag == null || tag.isBlank())
            return ""; //$NON-NLS-1$
        return "[" + tag + "] "; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Throwable unwrap(Throwable error)
    {
        if (error == null)
            return null;
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root)
            root = root.getCause();
        return root;
    }
}
