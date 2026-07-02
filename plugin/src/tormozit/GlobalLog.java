package tormozit;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

/**
 * Кольцевой буфер общего отладочного журнала ({@link GlobalLogView}).
 * Дополнительно зеркалит каждую строку в файл сессии ({@link #SESSION_LOG_FILE}) —
 * без обрезки по размеру, чтобы его можно было читать напрямую с диска после сцены.
 */
public final class GlobalLog
{
    public static final String VIEW_ID = "tormozit.GlobalLogView"; //$NON-NLS-1$

    /** Файл полного журнала текущей сессии — перезаписывается при каждом запуске плагина. */
    public static final String SESSION_LOG_FILE = "C:\\VC\\EDT.Comfort\\comfort-session.log"; //$NON-NLS-1$

    /** Сигнал представлению перечитать {@link #getFullText()} после обрезки буфера. */
    static final String RESYNC = "\u0000"; //$NON-NLS-1$

    private static final int MAX_LINES = 800;
    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    private static final Object LOCK = new Object();
    private static final StringBuilder buffer = new StringBuilder();
    private static int lineCount;
    private static final CopyOnWriteArrayList<Consumer<String>> listeners =
        new CopyOnWriteArrayList<>();
    private static PrintWriter fileWriter;
    private static boolean fileWriterFailed;

    private GlobalLog() {}

    public static void append(String message)
    {
        if (message == null)
            return;
        String line = LocalTime.now().format(TIME) + "  " + message; //$NON-NLS-1$
        boolean trimmed = false;
        synchronized (LOCK)
        {
            if (lineCount >= MAX_LINES)
            {
                trimOldestHalf();
                trimmed = true;
            }
            if (buffer.length() > 0)
                buffer.append('\n');
            buffer.append(line);
            lineCount++;
//            appendToFile(line);
        }
        notifyListeners(trimmed ? RESYNC : line);
    }

    public static String getFullText()
    {
        synchronized (LOCK)
        {
            return buffer.toString();
        }
    }

    private static void appendToFile(String line)
    {
        if (fileWriterFailed)
            return;
        try
        {
            if (fileWriter == null)
                fileWriter = new PrintWriter(new FileWriter(SESSION_LOG_FILE, false), true);
            fileWriter.println(line);
        }
        catch (IOException e)
        {
            fileWriterFailed = true;
            fileWriter = null;
        }
    }

    public static void clear()
    {
        synchronized (LOCK)
        {
            buffer.setLength(0);
            lineCount = 0;
        }
        notifyListeners(null);
    }

    public static void addListener(Consumer<String> listener)
    {
        if (listener != null)
            listeners.add(listener);
    }

    public static void removeListener(Consumer<String> listener)
    {
        listeners.remove(listener);
    }

    /** Открывает и активирует представление «Журнал Комфорт». */
    public static void showView()
    {
        showView(IWorkbenchPage.VIEW_ACTIVATE);
    }

    private static void showView(int mode)
    {
        Display display = getDisplay();
        if (display == null)
            return;
        Runnable open = () -> {
            try
            {
                if (!PlatformUI.isWorkbenchRunning())
                    return;
                var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null)
                    return;
                IWorkbenchPage page = window.getActivePage();
                if (page == null)
                    return;
                var view = page.showView(VIEW_ID, null, mode);
                if (view != null)
                    page.bringToTop(view);
            }
            catch (PartInitException ignored) {}
        };
        if (display.getThread() == Thread.currentThread())
            open.run();
        else
            display.asyncExec(open);
    }

    private static void trimOldestHalf()
    {
        String text = buffer.toString();
        int mid = text.length() / 2;
        int cut = text.indexOf('\n', mid);
        if (cut < 0)
            cut = mid;
        else
            cut++;
        buffer.setLength(0);
        buffer.append(text.substring(cut));
        lineCount = 0;
        for (int i = 0; i < buffer.length(); i++)
        {
            if (buffer.charAt(i) == '\n')
                lineCount++;
        }
        lineCount++;
    }

    private static void notifyListeners(String line)
    {
        Display display = getDisplay();
        if (display == null)
            return;
        for (Consumer<String> listener : listeners)
        {
            display.asyncExec(() -> {
                if (!display.isDisposed())
                    listener.accept(line);
            });
        }
    }

    private static Display getDisplay()
    {
        if (PlatformUI.isWorkbenchRunning())
        {
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (display != null && !display.isDisposed())
                return display;
        }
        return Display.getDefault();
    }
}
