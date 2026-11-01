package tormozit;

// #region agent log
/**
 * Временные замеры проверок Комфорта. Этапы проверок меряет ранний бандл вплетением в их классы:
 * бандл проверок при пересборке не меняет версию, и EDT может оставить его прежнюю копию. Здесь —
 * то, что знает только основной бандл: после каждого вызова проверки ранний бандл спрашивает
 * {@code tormozit.checkTiming.afterCheck} и дописывает ответ в свою строку {@code check-phases}.
 */
public final class CheckTimingLog
{
    private static final String AFTER_CHECK = "tormozit.checkTiming.afterCheck"; //$NON-NLS-1$

    private CheckTimingLog()
    {
    }

    /** Из {@code Activator.start}: {имя класса проверки} → сводка анализа литералов или {@code null}. */
    static void install()
    {
        System.getProperties().put(AFTER_CHECK, (java.util.function.Function<Object[], Object>) args ->
        {
            try
            {
                return String.valueOf(args[0]).endsWith(".BslUnknownLiteralCheck") //$NON-NLS-1$
                    ? BslLiteralValues.Pass.takeTimingSummary() : null;
            }
            catch (Throwable e)
            {
                return "afterCheck failed: " + e; //$NON-NLS-1$
            }
        });
        // Отметка сборки в check-timing.log: основной бандл с замерами литералов запущен.
        @SuppressWarnings("unchecked")
        var note = (java.util.function.Function<Object[], Object>) System.getProperties()
            .get("tormozit.checkTiming.note"); //$NON-NLS-1$
        if (note != null)
            note.apply(new Object[] {"main bundle started; afterCheck installed (literal timing v11: unlinked callee variable typed by its assignments)"}); //$NON-NLS-1$
    }

    static void uninstall()
    {
        System.getProperties().remove(AFTER_CHECK);
    }

    static String ms(long nanos)
    {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1e6); //$NON-NLS-1$
    }
}
// #endregion
