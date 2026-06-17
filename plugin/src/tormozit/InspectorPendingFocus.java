package tormozit;

/**
 * Одноразовый запрос активации дочернего свойства в только что открытом инспекторе.
 */
final class InspectorPendingFocus
{

    static final long TTL_MS = 5000L;
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

    /** Имя свойства без снятия запроса (для повторных попыток до успеха или TTL). */
    static String peek()
    {

        String name = propertyName;
        if (name == null || name.isBlank())
            return null;
        if (createdAt <= 0 || System.currentTimeMillis() - createdAt > TTL_MS)
        {

            DebugValuesDebug.step("pendingFocus", "expired " + DebugValuesDebug.quote(name)); //$NON-NLS-1$ //$NON-NLS-2$
            clear();
            return null;
        }

        return name;
    }

    static void complete()
    {

        if (propertyName != null)
            DebugValuesDebug.step("pendingFocus", "complete " + DebugValuesDebug.quote(propertyName)); //$NON-NLS-1$ //$NON-NLS-2$
        clear();
    }

    static String take()
    {

        String name = peek();
        clear();
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

    /**
     * Совпадение подписи свойства в дереве инспектора EDT с именем колонки.
     * Коллекции отображаются как «Имя [N]» или «Имя [*]», а не точным именем свойства.
     */
    static boolean matchesPropertyLabel(String label, String propertyName)
    {

        if (label == null || propertyName == null)
            return false;
        String item = label.trim();
        String needle = propertyName.trim();
        if (item.isEmpty() || needle.isEmpty())
            return false;
        if (item.equalsIgnoreCase(needle))
            return true;
        if (item.length() <= needle.length() + 2)
            return false;
        if (!item.regionMatches(true, 0, needle, 0, needle.length()))
            return false;
        if (item.charAt(needle.length()) != ' ')
            return false;
        if (item.charAt(needle.length() + 1) != '[')
            return false;
        return item.charAt(item.length() - 1) == ']';
    }

}

