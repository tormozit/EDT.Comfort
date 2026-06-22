package tormozit;

/**
 * Режим smart-фильтрации/сортировки в сессии content assist.
 * По умолчанию включён; Ctrl+Space при открытом popup переключает.
 * Управляется настройкой {@link ComfortSettings#isReplaceListFiltersEnabled()}.
 */
public final class SmartAssistFilterState
{
    private static final ThreadLocal<Boolean> SMART_FILTER_ENABLED =
        ThreadLocal.withInitial(() -> Boolean.TRUE);
    /** После выключения smart-фильтра — перезагрузить полный список у delegate. */
    private static final ThreadLocal<Boolean> UNFILTERED_RELOAD_PENDING =
        new ThreadLocal<>();

    private SmartAssistFilterState() {}

    public static boolean isUnfilteredReloadPending()
    {
        return Boolean.TRUE.equals(UNFILTERED_RELOAD_PENDING.get());
    }

    public static void clearUnfilteredReloadPending()
    {
        UNFILTERED_RELOAD_PENDING.remove();
    }

    public static boolean isSmartFilterEnabled()
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        return Boolean.TRUE.equals(SMART_FILTER_ENABLED.get());
    }

    public static void setSmartFilterEnabled(boolean enabled)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        boolean prev = Boolean.TRUE.equals(SMART_FILTER_ENABLED.get());
        if (prev == enabled)
            return;
        if (prev && !enabled)
            UNFILTERED_RELOAD_PENDING.set(Boolean.TRUE);
        SMART_FILTER_ENABLED.set(enabled);
        ContentAssistPopupSync.clearSyncState();
    }

    /** @return новое состояние */
    public static boolean toggle()
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        boolean next = !Boolean.TRUE.equals(SMART_FILTER_ENABLED.get());
        setSmartFilterEnabled(next);
        ContentAssistDebug.log("smartFilter toggled → " + (next ? "ON" : "OFF")); //$NON-NLS-1$ //$NON-NLS-2$
        return next;
    }

    public static void reset()
    {
        UNFILTERED_RELOAD_PENDING.remove();
        SMART_FILTER_ENABLED.set(
            ComfortSettings.isReplaceListFiltersEnabled() ? Boolean.TRUE : Boolean.FALSE);
    }
}
