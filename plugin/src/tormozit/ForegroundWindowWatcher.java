package tormozit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.widgets.Display;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

/**
 * Отслеживает foreground-окно Windows во время подключения ИР.
 * Глубина 10 записей × 200 мс = 2 секунды истории — достаточно,
 * чтобы поймать момент, когда ИР вышел на передний план.
 */
public final class ForegroundWindowWatcher
{
    private static final int POLL_MS = 200;
    private static final int MAX_HISTORY = 10;

    private final Deque<WindowActivity> history = new ArrayDeque<>(MAX_HISTORY);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FG-Watcher");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static final class WindowActivity
    {
        final long time;
        final HWND hwnd;
        final int pid;
        final String title;

        WindowActivity(long time, HWND hwnd, int pid, String title)
        {
            this.time = time;
            this.hwnd = hwnd;
            this.pid = pid;
            this.title = title;
        }
    }

    /** Начать отслеживание. Вызывать ДО создания COM-объекта ИР. */
    public void start()
    {
        if (!WinWindowActivator.isWindows())
            return;
        if (running.compareAndSet(false, true))
        {
            synchronized (history) {
                history.clear();
            }
            executor.scheduleAtFixedRate(this::poll, 0, POLL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Остановить отслеживание. */
    public void stop()
    {
        if (running.compareAndSet(true, false))
        {
            executor.shutdown();
            try
            {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS))
                    executor.shutdownNow();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private void poll()
    {
        try
        {
            HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (WinWindowActivator.isNullHwnd(hwnd))
                return;

            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();

            char[] buf = new char[512];
            int len = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            String title = len > 0 ? new String(buf, 0, len).trim() : "";

            synchronized (history)
            {
                history.addLast(new WindowActivity(System.currentTimeMillis(), hwnd, pid, title));
                while (history.size() > MAX_HISTORY)
                    history.removeFirst();
            }
        }
        catch (Exception ignored) {}
    }

    /**
     * Находит HWND окна, которое было активно до выхода ИР на передний план.
     * Ищет с конца истории — первую запись с PID ИР, затем берёт предыдущую не-ИР.
     *
     * @param irPid PID процесса ИР
     * @return HWND предыдущего окна или null
     */
    public HWND findPreviousWindow(long irPid)
    {
        if (!WinWindowActivator.isWindows() || history.isEmpty())
            return null;

        synchronized (history)
        {
            WindowActivity[] snapshot = history.toArray(new WindowActivity[0]);

            // Ищем с конца первую запись ИР
            int irIndex = -1;
            for (int i = snapshot.length - 1; i >= 0; i--)
            {
                if (snapshot[i].pid == irPid)
                {
                    irIndex = i;
                    break;
                }
            }

            // Если ИР не найден — берём последнее не-ИР
            int start = (irIndex >= 0) ? irIndex - 1 : snapshot.length - 1;

            for (int i = start; i >= 0; i--)
            {
                if (snapshot[i].pid != irPid)
                    return snapshot[i].hwnd;
            }
            return null;
        }
    }

    /** Восстанавливает фокус. Если окно мертво — fallback на workbench Eclipse. */
    public void restorePreviousWindow(long irPid)
    {
        HWND hwnd = findPreviousWindow(irPid);

        boolean valid = hwnd != null
                && !WinWindowActivator.isNullHwnd(hwnd)
                && User32.INSTANCE.IsWindow(hwnd);

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        if (valid)
            display.asyncExec(() -> WinWindowActivator.activateWindow(hwnd));
        else
            display.asyncExec(() -> WinWindowActivator.activateWorkbench());
    }
}