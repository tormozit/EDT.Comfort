package tormozit;

/**
 * Одноразовый запрос активации дочернего свойства в только что открытом инспекторе.
 */
final class InspectorPendingFocus
{
    private static final long TTL_MS = 5000L;

    private static volatile String propertyName;
    private static volatile long createdAt;

    private InspectorPendingFocus() {}

    static void set(String name)
    {
        if (name == null || name.isBlank() || isSkipColumnHeader(name))
        {
            clear();
            return;
        }
        propertyName = name.trim();
        createdAt = System.currentTimeMillis();
        DebugValuesDebug.step("pendingFocus", "set " + DebugValuesDebug.quote(propertyName)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String take()
    {
        String name = propertyName;
        long at = createdAt;
        clear();
        if (name == null || name.isBlank())
            return null;
        if (at <= 0 || System.currentTimeMillis() - at > TTL_MS)
        {
            DebugValuesDebug.step("pendingFocus", "expired " + DebugValuesDebug.quote(name)); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        return name;
    }

    private static void clear()
    {
        propertyName = null;
        createdAt = 0L;
    }

    private static boolean isSkipColumnHeader(String header)
    {
        return "Индекс".equalsIgnoreCase(header) //$NON-NLS-1$
            || "Index".equalsIgnoreCase(header); //$NON-NLS-1$
    }
}
