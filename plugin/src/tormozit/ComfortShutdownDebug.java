package tormozit;

/**
 * Экономное файловое логирование подготовки к закрытию EDT.
 * Отключено для релиза — все методы-заглушки.
 */
public final class ComfortShutdownDebug
{
    private ComfortShutdownDebug() {}

    static void begin(String reason)
    {
    }

    static void step(String phase)
    {
    }

    static void problem(String phase, String detail)
    {
    }

    static void end(boolean ok)
    {
    }
}
