package tormozit;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.State;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.RegistryToggleState;

/**
 * Состояние toggle «жёлтая звезда» и обновление навигатора.
 * RegistryToggleState в toolbar не отражает runtime — храним флаг сами.
 */
final class ObjectSetsNavigatorFilterSupport
{
    static final String COMMAND_ID = "tormozit.navigator.filterByObjectSet"; //$NON-NLS-1$

    private static volatile boolean filterActive;

    private ObjectSetsNavigatorFilterSupport() {}

    static boolean isActive()
    {
        return filterActive;
    }

    static void setActive(boolean active)
    {
        filterActive = active;
        syncCommandToggleState(active);
    }

    /** Согласовать runtime-флаг с состоянием toggle-команды (после restore workbench). */
    static void syncFromCommandToggle()
    {
        ICommandService commandService = commandService();
        if (commandService == null)
            return;
        Command command = commandService.getCommand(COMMAND_ID);
        if (command == null)
            return;
        State state = command.getState(RegistryToggleState.STATE_ID);
        if (state == null)
            return;
        boolean active = Boolean.TRUE.equals(state.getValue());
        if (active && ObjectSetsAddTargetState.getInstance().areAllAddTargetSetsEmpty())
        {
            active = false;
            state.setValue(Boolean.FALSE);
        }
        filterActive = active;
    }

    static void refreshNavigators()
    {
        ObjectSetSubsystemsFilterBridge.onFilterStateChanged();
    }

    /**
     * Штатный фильтр навигатора (подстрока или подсистемы) вытесняет фильтр набора.
     */
    static void deactivateBecauseCompetingFilter()
    {
        if (!filterActive)
            return;
        setActive(false);
        refreshNavigators();
    }

    static boolean isCompetingNavigatorFilterActive(IViewPart navigator)
    {
        if (navigator == null)
            return false;
        Object searchActive = Global.invoke(navigator, "isSearchFilterActive"); //$NON-NLS-1$
        if (Boolean.TRUE.equals(searchActive))
            return true;
        Object subsystemsActive = Global.invoke(navigator, "isSubsystemsFilterActive"); //$NON-NLS-1$
        return Boolean.TRUE.equals(subsystemsActive);
    }

    private static void syncCommandToggleState(boolean active)
    {
        ICommandService commandService = commandService();
        if (commandService == null)
            return;
        Command command = commandService.getCommand(COMMAND_ID);
        if (command == null)
            return;
        State state = command.getState(RegistryToggleState.STATE_ID);
        if (state != null)
            state.setValue(active);
    }

    private static ICommandService commandService()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        return window.getService(ICommandService.class);
    }
}
