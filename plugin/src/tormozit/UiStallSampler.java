package tormozit;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IPath;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;

/**
 * Запись стеков при зависаниях UI — по флажку «Записывать стеки при зависаниях»
 * ({@link ComfortSettings#isRecordStallStacks()}). Нужна, чтобы по файлу пользователя отличать
 * причину (паузы GC, сборка мусора JavaFX WebKit, чужой код в UI-потоке) без {@code jstack}.
 *
 * <p>Флажок сам выключается через {@link #AUTO_OFF_MS} после того, как сэмплер его увидел, и
 * сбрасывается при старте плагина. Пока он выключен, фоновый поток только спит.
 *
 * <p>Включённый сэмплер раз в {@link #TICK_MS} кладёт в очередь UI-потока «пульс»
 * ({@code asyncExec}). Пока пульс не выполнен дольше {@link #STALL_MS} — идёт эпизод: каждые
 * {@link #TICK_MS} снимается стек UI-потока. Когда пульс выполнен, в {@code ui-stalls.log}
 * (рабочая папка плагина) пишется одна запись: длительность, прирост времени GC-пауз за эпизод и
 * топ стеков по числу попаданий.
 *
 * <p>Как читать запись: {@code gcMs} близко к {@code durMs} — паузы GC (не хватает памяти);
 * в топ-стеке {@code com.sun.webkit.WebPage.twkDoJSCGarbageCollection} — освобождение страницы
 * WebView (JavaFX) в UI-потоке (разбор 05.10.2026: подвисало, пока были открыты синтакс-помощник
 * и «Напарник»); {@code tormozit.*} — наш код; {@code Display.sleep} — UI свободен, причина вне его.
 */
public final class UiStallSampler implements IStartup
{
    private static final String FILE_NAME = "ui-stalls.log"; //$NON-NLS-1$
    private static final long AUTO_OFF_MS = 60_000;
    private static final long TICK_MS = 300;
    private static final long STALL_MS = 1000;
    private static final int STACK_DEPTH = 18;
    private static final int TOP_STACKS = 4;
    /** Жёсткий предел размера файла: старые записи выбрасываются, новые дописываются. */
    private static final long MAX_FILE_BYTES = 1024 * 1024;

    @Override
    public void earlyStartup()
    {
        if (ComfortSettings.isRecordStallStacks())
            ComfortSettings.setRecordStallStacks(false);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Thread thread = new Thread(() -> new Sampler(display).run(), "Комфорт: запись стеков при зависаниях"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    private static File logFile()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
            return null;
        IPath location = activator.getStateLocation();
        return location != null ? location.append(FILE_NAME).toFile() : null;
    }

    /**
     * Открывает файл записей системной программой; если файла ещё нет — создаёт с пояснением,
     * чтобы ссылка работала всегда (в том числе после зависания, которое не дало записей).
     */
    static void openLogFile()
    {
        File file = logFile();
        if (file == null)
            return;
        try
        {
            if (!file.exists())
            {
                Files.createDirectories(file.getParentFile().toPath());
                Files.writeString(file.toPath(),
                    "Записей пока нет. Включите флажок «Записывать стеки при зависаниях» в параметрах Комфорт: " //$NON-NLS-1$
                        + "пока он включён (минуту), сюда пишутся стеки зависаний окна EDT.\n", //$NON-NLS-1$
                    StandardCharsets.UTF_8);
            }
        }
        catch (IOException e)
        {
            return;
        }
        Program.launch(file.getAbsolutePath());
    }

    private static final class Sampler
    {
        private final Display display;
        private final Thread uiThread;
        private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        /** nanoTime постановки невыполненного пульса, 0 — пульса в очереди нет. */
        private final AtomicLong pendingSince = new AtomicLong();
        private final AtomicLong lastPulseDoneNs = new AtomicLong(System.nanoTime());

        /** Всё ниже — только в потоке сэмплера. */
        private final Map<String, Integer> stacks = new HashMap<>();
        private int samples;
        private String lastStack;
        private long episodeStartNs;
        private long episodeGcStart;
        /** nanoTime, когда флажок замечен включённым; 0 — выключен. */
        private long enabledSinceNs;
        private int episodesInRun;

        Sampler(Display display)
        {
            this.display = display;
            this.uiThread = display.getThread();
        }

        void run()
        {
            while (!display.isDisposed())
            {
                try
                {
                    if (ComfortSettings.isRecordStallStacks())
                        tick();
                    else
                        stop(false);
                    Thread.sleep(TICK_MS);
                }
                catch (InterruptedException e)
                {
                    return;
                }
                catch (RuntimeException e)
                {
                    // диагностика не должна падать
                }
            }
        }

        private void tick()
        {
            long now = System.nanoTime();
            if (enabledSinceNs == 0)
            {
                enabledSinceNs = now;
                episodesInRun = 0;
            }
            else if ((now - enabledSinceNs) / 1_000_000 >= AUTO_OFF_MS)
            {
                flushEpisode();
                ComfortSettings.setRecordStallStacks(false);
                stop(true);
                return;
            }
            long since = pendingSince.get();
            if (since == 0)
            {
                flushEpisode();
                pendingSince.set(now);
                display.asyncExec(this::pulse);
                return;
            }
            if ((now - since) / 1_000_000 < STALL_MS)
                return;
            if (episodeStartNs == 0)
            {
                episodeStartNs = since;
                episodeGcStart = gcMillis();
            }
            StackTraceElement[] trace = uiThread.getStackTrace();
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < Math.min(trace.length, STACK_DEPTH); i++)
                key.append("\n    ").append(trace[i]); //$NON-NLS-1$
            String stack = key.toString();
            stacks.merge(stack, 1, Integer::sum);
            samples++;
            // Пишем сразу, а не по окончании: зависание может не закончиться (EDT убьют).
            // Подряд идущие одинаковые стеки не повторяем.
            if (samples == 1)
                write(LocalDateTime.now() + " подвисание UI началось"); //$NON-NLS-1$
            if (!stack.equals(lastStack))
                write("  +" + (now - episodeStartNs) / 1_000_000 + " мс" + stack); //$NON-NLS-1$ //$NON-NLS-2$
            lastStack = stack;
        }

        /** Выключение (по таймеру или вручную): итог эпизода, тост при автоотключении. */
        private void stop(boolean byTimer)
        {
            if (enabledSinceNs == 0)
                return;
            enabledSinceNs = 0;
            flushEpisode();
            if (byTimer && episodesInRun > 0)
            {
                File file = logFile();
                String message = "Зависаний за минуту: " + episodesInRun //$NON-NLS-1$
                    + ". Стеки записаны в файл " + FILE_NAME + "."; //$NON-NLS-1$ //$NON-NLS-2$
                Runnable open = file == null ? null : UiStallSampler::openLogFile;
                display.asyncExec(() -> ToastNotification.showStickyUntilToastHover(
                    "Запись стеков при зависаниях отключена", message, open, //$NON-NLS-1$
                    open == null ? null : "Открыть файл")); //$NON-NLS-1$
            }
        }

        /** Выполняется в UI-потоке: только отметка. */
        private void pulse()
        {
            lastPulseDoneNs.set(System.nanoTime());
            pendingSince.set(0);
        }

        /** В потоке сэмплера: если шёл эпизод и пульс выполнен — записать итог. */
        private void flushEpisode()
        {
            if (episodeStartNs == 0)
                return;
            long endNs = pendingSince.get() == 0 ? lastPulseDoneNs.get() : System.nanoTime();
            long durMs = (endNs - episodeStartNs) / 1_000_000;
            long gcMs = gcMillis() - episodeGcStart;
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now()).append(" подвисание UI закончилось: durMs=").append(durMs) //$NON-NLS-1$
                .append(" gcMs=").append(gcMs).append(" samples=").append(samples); //$NON-NLS-1$ //$NON-NLS-2$
            stacks.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(TOP_STACKS)
                .forEach(e -> sb.append("\n  hits=").append(e.getValue()).append(e.getKey())); //$NON-NLS-1$
            write(sb.toString());
            episodesInRun++;
            stacks.clear();
            samples = 0;
            lastStack = null;
            episodeStartNs = 0;
        }

        /** Дописывает запись (строку или блок строк) в файл; перевод строки добавляется здесь. */
        private void write(String text)
        {
            text += "\n"; //$NON-NLS-1$
            File file = logFile();
            if (file == null)
                return;
            try
            {
                byte[] add = text.getBytes(StandardCharsets.UTF_8);
                if (add.length > MAX_FILE_BYTES)
                    add = java.util.Arrays.copyOf(add, (int) MAX_FILE_BYTES);
                if (file.length() + add.length > MAX_FILE_BYTES)
                    dropOldest(file, MAX_FILE_BYTES - add.length);
                Files.write(file.toPath(), add, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            catch (IOException e)
            {
                // нет доступа к рабочей папке — запись пропускаем
            }
        }

        /**
         * Оставляет в файле не больше {@code keepBytes} байт с конца, начиная с границы записи
         * (строка эпизода начинается с года {@code 20..}, строки стека — с пробелов).
         */
        private static void dropOldest(File file, long keepBytes) throws IOException
        {
            byte[] all = Files.readAllBytes(file.toPath());
            int from = (int) Math.max(0, all.length - Math.max(0, keepBytes));
            while (from < all.length && !(from > 0 && all[from - 1] == '\n' && all[from] == '2'))
                from++;
            Files.write(file.toPath(), java.util.Arrays.copyOfRange(all, from, all.length));
        }

        private long gcMillis()
        {
            long sum = 0;
            for (GarbageCollectorMXBean c : collectors)
                sum += Math.max(0, c.getCollectionTime());
            return sum;
        }
    }
}
