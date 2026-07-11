package tormozit;

/**
 * Логирование автоосвобождения горячих клавиш команд ИР ({@link IrKeyBindingHook}).
 */
final class IrKeyBindingDebug
{
    private static final String TAG = "IrKeyBinding"; //$NON-NLS-1$

    private static final String[] IR_MIRROR_COMMAND_IDS = {
        IrMethodConstructorHandler.COMMAND_ID,
        EditEmbeddedTextCommandHandler.COMMAND_ID,
        IrFormatTextCommandHandler.COMMAND_ID,
        IrModuleCheckHandler.COMMAND_ID
    };

    private IrKeyBindingDebug()
    {
    }

    static void step(String phase, String detail)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, phase + ": " + detail); //$NON-NLS-1$
    }

    /** Сводка USER-зеркал Priority-хука по каждой команде ИР. */
    static void logIrMirrorSummary()
    {
        if (!Global.isLogEnabled())
            return;

        for (String commandId : IR_MIRROR_COMMAND_IDS)
        {
            int mirrorCount = PriorityGlobalKeyBindingHook.countMirrorOverridesForCommand(commandId);
            if (mirrorCount == 0)
                continue;
            Global.log(TAG, "mirror overrides: " + commandId + " bindings=" + mirrorCount); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
