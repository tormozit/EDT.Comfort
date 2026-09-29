package tormozit;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;

/**
 * Тост «не хватает памяти», когда EDT парализован сборкой мусора: паузы GC съедают большую часть
 * времени, а куча даже сразу после сборок почти полная. Во время паузы стоят все потоки, включая UI,
 * поэтому пользователь видит «зависание» без объяснения, а лечится оно только подъёмом {@code -Xmx}.
 *
 * <p>Замер — стандартный {@code java.lang.management}, раз в {@link #POLL_MS}:
 * <ul>
 *   <li>доля времени в паузах — прирост суммы {@link GarbageCollectorMXBean#getCollectionTime()}
 *   (у G1 это stop-the-world паузы young/mixed/full) к приросту настенного времени;</li>
 *   <li>заполненность — МИНИМУМ занятой кучи по отсчётам окна: даже в самый свободный момент куча
 *   была занята не меньше. {@code MemoryPoolMXBean.getCollectionUsage()} для «G1 Old Gen» на JDK 17
 *   не годится — после одних young GC держит устаревшее значение (проверено: 0 при 250 МиБ живых).</li>
 * </ul>
 * Сторож перегрузки 1С ({@code HostResourceManager$SystemOverloadWatchdogRunnable}) не используется:
 * он меряет общую нехватку CPU и не отличает сборку мусора от прочих причин, а его «Memory X used of Y»
 * считается как {@code maxMemory() - freeMemory()} — это не занятость кучи.
 *
 * <p>Один тост на непрерывный период: вход — доля пауз не меньше {@link #ENTER_GC_SHARE} за
 * {@link #ENTER_WINDOW_MS} и заполненность не меньше {@link #ENTER_HEAP_SHARE}; выход — доля пауз
 * ниже {@link #EXIT_GC_SHARE} за целые {@link #EXIT_WINDOW_MS}. Опрос под параличом сам запаздывает
 * (поток замера тоже стоит в паузах), но доли считаются по фактическим интервалам между отсчётами.
 * Пороги проверены стресс-тестом на кучах 512 МБ и 8 ГБ: паралич — 91–95% пауз при 90% кучи.
 */
public final class GcPressureToastHook implements IStartup
{
    private static final long POLL_MS = 1000;
    /** Окно входа — оно же «за последние N секунд» в тексте тоста. */
    private static final long ENTER_WINDOW_MS = 10_000;
    private static final double ENTER_GC_SHARE = 0.5;
    private static final double ENTER_HEAP_SHARE = 0.85;
    private static final long EXIT_WINDOW_MS = 60_000;
    private static final double EXIT_GC_SHARE = 0.2;

    @Override
    public void earlyStartup()
    {
        new Monitor().start();
    }

    private static void showToast(String title, String message)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.showStickyUntilToastHover(title, message, null, null));
    }

    /** Целые ГБ ({@code 7,94 → 8 ГБ}); меньше гигабайта — целые МБ. */
    private static String formatBytes(long bytes)
    {
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb < 1)
            return Math.round(bytes / (1024.0 * 1024)) + " МБ"; //$NON-NLS-1$
        return Math.round(gb) + " ГБ"; //$NON-NLS-1$
    }

    /** Откуда взялся предел кучи: {@code (-Xmx4g)} или «не задан — ¼ ОЗУ» (умолчание JVM). */
    private static String limitSource()
    {
        String xmx = null;
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments())
            if (arg.startsWith("-Xmx") || arg.startsWith("-XX:MaxHeapSize=")) //$NON-NLS-1$ //$NON-NLS-2$
                xmx = arg; // действует последний
        return xmx != null ? "(" + xmx + ")" : "(-Xmx не задан, поэтому 1/4 от общей)"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Всё состояние — только в потоке замера. */
    private static final class Monitor
    {
        /** {@code {System.nanoTime(), сумма времени пауз мс, занято кучи байт}}. */
        private final ArrayDeque<long[]> samples = new ArrayDeque<>();
        private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private boolean inEpisode;

        void start()
        {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
            {
                Thread thread = new Thread(r, "Комфорт: контроль нехватки памяти"); //$NON-NLS-1$
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(this::safePoll, 0, POLL_MS, TimeUnit.MILLISECONDS);
        }

        /** Исключение из задачи молча отменило бы все следующие запуски {@code scheduleWithFixedDelay}. */
        private void safePoll()
        {
            try
            {
                poll();
            }
            catch (RuntimeException e)
            {
                // пропускаем отсчёт — следующий через POLL_MS
            }
        }

        private void poll()
        {
            long now = System.nanoTime();
            long gcMillis = 0;
            for (GarbageCollectorMXBean collector : collectors)
                gcMillis += Math.max(0, collector.getCollectionTime());
            MemoryUsage heap = memory.getHeapMemoryUsage();
            long max = heap.getMax() > 0 ? heap.getMax() : Runtime.getRuntime().maxMemory();

            samples.addLast(new long[] {now, gcMillis, heap.getUsed()});
            while (samples.size() > 2 && ageMs(now, samples.getFirst()) > EXIT_WINDOW_MS + 5 * POLL_MS)
                samples.removeFirst();

            if (inEpisode)
            {
                if (gcShare(now, gcMillis, EXIT_WINDOW_MS) < EXIT_GC_SHARE)
                    inEpisode = false;
                return;
            }
            double gcShare = gcShare(now, gcMillis, ENTER_WINDOW_MS);
            double heapShare = (double) minUsed(now, ENTER_WINDOW_MS) / max;
            if (gcShare >= ENTER_GC_SHARE && heapShare >= ENTER_HEAP_SHARE)
            {
                inEpisode = true;
                int seconds = (int) (ENTER_WINDOW_MS / 1000);
                showToast("Острая нехватка памяти процессу EDT", //$NON-NLS-1$
                    "Сборка мусора заняла " + Math.round(gcShare * 100) + "% времени за " + seconds + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + Global.russianPlural(seconds, "секунду", "секунды", "секунд") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + ". Процессу выделено " + formatBytes(max) + " " + limitSource() + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        private static long ageMs(long now, long[] sample)
        {
            return (now - sample[0]) / 1_000_000;
        }

        /** Доля пауз от самого свежего отсчёта не моложе окна до {@code now}; {@code NaN} — истории меньше окна. */
        private double gcShare(long now, long gcMillis, long windowMs)
        {
            long[] start = null;
            for (Iterator<long[]> it = samples.descendingIterator(); it.hasNext();)
            {
                long[] sample = it.next();
                if (ageMs(now, sample) >= windowMs)
                {
                    start = sample;
                    break;
                }
            }
            if (start == null)
                return Double.NaN;
            double wallMs = (now - start[0]) / 1_000_000.0;
            return Math.min(1, (gcMillis - start[1]) / wallMs);
        }

        private long minUsed(long now, long windowMs)
        {
            long min = Long.MAX_VALUE;
            for (Iterator<long[]> it = samples.descendingIterator(); it.hasNext();)
            {
                long[] sample = it.next();
                min = Math.min(min, sample[2]);
                if (ageMs(now, sample) >= windowMs)
                    break;
            }
            return min;
        }
    }
}
