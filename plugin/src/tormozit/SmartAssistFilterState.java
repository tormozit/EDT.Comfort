package tormozit;

/**
 * Режим smart-фильтрации/сортировки в сессии content assist.
 * По умолчанию включён; Ctrl+Space при открытом popup переключает.
 */
public final class SmartAssistFilterState
{
    private static final ThreadLocal<Boolean> SMART_FILTER_ENABLED =
        ThreadLocal.withInitial(() -> Boolean.TRUE);
    /** После выключения smart-фильтра — перезагрузить полный список у delegate. */
    private static final ThreadLocal<Boolean> UNFILTERED_RELOAD_PENDING =
        new ThreadLocal<>();

    private SmartAssistFilterState() {}

    public static boolean isSmartFilterEnabled()
    {
        return Boolean.TRUE.equals(SMART_FILTER_ENABLED.get());
    }

    public static void setSmartFilterEnabled(boolean enabled)
    {
        if (isSmartFilterEnabled() && !enabled)
            UNFILTERED_RELOAD_PENDING.set(Boolean.TRUE);
        SMART_FILTER_ENABLED.set(enabled);
    }

    /** @return новое состояние */
    public static boolean toggle()
    {
        boolean next = !isSmartFilterEnabled();
        setSmartFilterEnabled(next);
        ContentAssistDebug.log("smartFilter toggled → " + (next ? "ON" : "OFF")); //$NON-NLS-1$ //$NON-NLS-2$
        return next;
    }

    /** Сбросить кэш и заново запросить delegate (полный список без smart-фильтра). */
    public static boolean consumeUnfilteredReloadPending()
    {
        if (!Boolean.TRUE.equals(UNFILTERED_RELOAD_PENDING.get()))
            return false;
        UNFILTERED_RELOAD_PENDING.remove();
        return true;
    }

    public static void reset()
    {
        UNFILTERED_RELOAD_PENDING.remove();
        SMART_FILTER_ENABLED.set(Boolean.TRUE);
    }
}
