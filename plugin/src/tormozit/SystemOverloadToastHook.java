package tormozit;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;

/**
 * EDT сама детектирует устойчивую перегрузку CPU (в т.ч. из-за почти непрерывной сборки мусора —
 * см. {@code HostResourceManager$SystemOverloadWatchdogRunnable} в бандле
 * {@code com._1c.g5.resourcemanagement}), но сигнализирует об этом только тихой записью
 * {@link IStatus#WARNING} в платформенный лог — без единого признака в интерфейсе. Из-за этого
 * пользователь видит просто «зависший» EDT и не понимает, что причина — не деградация конкретной
 * операции, а нехватка процессорного времени на всё приложение целиком.
 *
 * <p>Хук слушает платформенный лог через {@link ILogListener} и превращает такие записи в тост:
 * достаточно фильтра по {@code pluginId} — текст сообщений («CPU overload…», «Critical CPU
 * overload…», «Sustained CPU overload…») 1C не документирует и может измениться, поэтому вместо
 * разбора текста используется степень серьёзности через ключевые слова, а числа памяти из чужого
 * сообщения не показываются пользователю (в байтах, без форматирования — не несут ценности).
 *
 * <p>Переход обратно в нормальное состояние платформа не логирует ({@code onNormalLoadState} не
 * вызывает {@code log}), поэтому тост об окончании перегрузки не показывается — узнать об этом
 * можно только по тому, что система снова отвечает.
 *
 * <p><b>Дедупликация.</b> {@code HostResourceManager$SystemOverloadWatchdogRunnable.
 * onNextSlowdownDetection} пишет такой {@code WARNING} на каждом «плохом» приближающем периоде
 * ({@code APPROXIMATION_PERIOD} = 2000 мс), а не один раз на смену состояния — счётчик
 * {@code criticalCounter} не сбрасывается ни в {@code reportGenericOverload}/
 * {@code reportCriticalOverload}, ни при входе в {@code SUSTAINED_OVERLOAD}. При затяжной
 * перегрузке 1C сам будет слать такие записи каждые ~2 с, поэтому один тост на непрерывный период
 * держим сами: запись, пришедшая раньше {@link #EPISODE_GAP_MS} после предыдущей, считается тем же
 * периодом перегрузки и тост не повторяет; молчание дольше этого промежутка — признак того, что
 * период закончился (после {@code NORMAL_PERIODS_TILL_RESTORATION} нормальных периодов watchdog
 * просто перестаёт логировать), и следующая запись открывает новый период.
 */
public final class SystemOverloadToastHook implements IStartup
{
    /** {@code ResourceManagementPlugin.PLUGIN_ID} — совпадает с именем самого класса. */
    private static final String RESOURCE_MANAGEMENT_PLUGIN_ID =
        "com._1c.g5.v8.internal.resourcemanagement.ResourceManagementPlugin"; //$NON-NLS-1$

    /**
     * Порог «тот же непрерывный период перегрузки». {@code APPROXIMATION_PERIOD} (2000 мс) из
     * байткода — цикл watchdog-потока БЕЗ помех; под самой перегрузкой этот же поток недополучает
     * CPU наравне со всеми, и его собственный цикл измерения растягивается. По реальному логу
     * растянутого зависания ({@code .bak_0.log}, 170 записей WARNING за ~107 минут) максимальный
     * интервал между соседними записями внутри одного непрерывного инцидента — 245.2 с; следующий по
     * величине скачок (2984 с, ~50 мин) — уже граница между двумя разными инцидентами (полная тишина
     * в логе). 300 000 мс перекрывает первое с запасом и остаётся далеко меньше второго — на этом
     * логе даёт ровно 2 тоста (по одному на инцидент) вместо 65 при прежних 8 000 мс.
     */
    private static final long EPISODE_GAP_MS = 300_000;

    private static final ILogListener LOG_LISTENER = (status, plugin) -> onLogEntry(status);

    /** Читается/пишется только внутри {@link #onLogEntry} — метод синхронизирован. */
    private static long lastWarningAt;

    @Override
    public void earlyStartup()
    {
        Platform.addLogListener(LOG_LISTENER);
    }

    private static synchronized void onLogEntry(IStatus status)
    {
        if (status == null || status.getSeverity() != IStatus.WARNING
            || !RESOURCE_MANAGEMENT_PLUGIN_ID.equals(status.getPlugin()))
            return;

        long now = System.currentTimeMillis();
        boolean sameEpisode = now - lastWarningAt <= EPISODE_GAP_MS;
        lastWarningAt = now;
        if (sameEpisode)
            return; // уже показали тост на этот непрерывный период — не повторяем

        String message = status.getMessage();
        boolean sustained = message != null && message.contains("Sustained"); //$NON-NLS-1$
        boolean critical = message != null && message.contains("Critical"); //$NON-NLS-1$

        String title;
        String text;
        int durationMs;
        if (sustained)
        {
            title = "Устойчивая перегрузка системы"; //$NON-NLS-1$
            text = "EDT долго не получает процессорное время (часто — из-за почти непрерывной" //$NON-NLS-1$
                + " сборки мусора). Интерфейс и фоновые задачи будут заметно тормозить, пока" //$NON-NLS-1$
                + " нагрузка не спадёт сама — минимум ещё несколько минут."; //$NON-NLS-1$
            durationMs = 0; // липкий: наводит на серьёзность ситуации, не должен потеряться
        }
        else if (critical)
        {
            title = "Критическая перегрузка системы"; //$NON-NLS-1$
            text = "EDT почти не получает процессорное время. Если это продлится — интерфейс" //$NON-NLS-1$
                + " может выглядеть зависшим."; //$NON-NLS-1$
            durationMs = 6000;
        }
        else
        {
            title = "Перегрузка системы"; //$NON-NLS-1$
            text = "EDT обнаружила нехватку процессорного времени — возможна кратковременная" //$NON-NLS-1$
                + " просадка отзывчивости."; //$NON-NLS-1$
            durationMs = 5000;
        }

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> ToastNotification.show(title, text, durationMs));
    }
}
