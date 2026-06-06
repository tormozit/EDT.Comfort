package tormozit;

/**
 * Хранилище текущего фильтра для {@link SmartCodeProposalSorter}.
 * Значение обновляется {@link ContentAssistSessionReloader} в {@code keyReleased}.
 */
public final class SmartFilterTracker
{
    private static final ThreadLocal<String> currentFilter = new ThreadLocal<>();

    public static void setCurrentFilter(String filter)
    {
        currentFilter.set(filter == null ? "" : filter);
    }

    public static String getCurrentFilter()
    {
        String f = currentFilter.get();
        return f == null ? "" : f;
    }
}