package tormozit.bslparser;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Временный лог через {@code Global.tempLog} Комфорта (он кладёт приёмник в {@link #PROP_SINK}).
 *
 * <p>Этот бандл стартует раньше Комфорта, а Комфорт при старте очищает папку временных логов.
 * Поэтому до появления приёмника строки копятся в памяти с исходным временем и отдаются ему
 * при первой записи после его появления.
 */
final class TempLog
{
    /** {@code BiConsumer<String, String>}: {@code (тема, текст)}. Ставит {@code tormozit.Activator}. */
    static final String PROP_SINK = "tormozit.comfort.tempLog"; //$NON-NLS-1$

    private static final int MAX_PENDING = 5000;

    private static final List<String[]> PENDING = new ArrayList<>();

    private TempLog()
    {
    }

    static void log(String topic, String text)
    {
        BiConsumer<String, String> sink = sink();
        synchronized (PENDING)
        {
            if (sink == null)
            {
                if (PENDING.size() < MAX_PENDING)
                    PENDING.add(new String[] {topic, "[" + LocalDateTime.now() + "] " + text}); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            for (String[] line : PENDING)
                sink.accept(line[0], line[1]);
            PENDING.clear();
        }
        sink.accept(topic, text);
    }

    static void logException(String topic, String context, Throwable t)
    {
        StringWriter sw = new StringWriter();
        if (t != null)
            t.printStackTrace(new PrintWriter(sw));
        log(topic, (context != null ? context : "") + System.lineSeparator() + sw); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static BiConsumer<String, String> sink()
    {
        Object sink = System.getProperties().get(PROP_SINK);
        return sink instanceof BiConsumer ? (BiConsumer<String, String>) sink : null;
    }
}
