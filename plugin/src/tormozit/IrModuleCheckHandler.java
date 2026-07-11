package tormozit;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/** Проверка модуля через приложение ИР ({@code ОткрытьПроверкуМодуля}). */
public final class IrModuleCheckHandler
{
    public static final String COMMAND_ID = "tormozit.IrModuleCheck"; //$NON-NLS-1$
    static final String MENU_LABEL = "Проверить модуль ИР"; //$NON-NLS-1$
    private static final long CTRL_ENTER_DELAY_MS = 200;

    private IrModuleCheckHandler() {}

    public static void checkModule(BslXtextEditor editor)
    {
        if (editor == null)
        {
            ToastNotification.show(MENU_LABEL, "Откройте модуль BSL и повторите команду", 3000); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject);
        if (irSession == null || irSession.executor == null)
            return;

        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() -> checkModuleOnComThread(irSession));
    }

    /** COM-поток {@link IRSession#executor}; sync уже выполнен через {@link IRSession#syncCodeEditorToIR}. */
    private static void checkModuleOnComThread(IRSession irSession)
    {
        try
        {
            irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
            irSession.showWindow();
            irSession.invokeCodeEditor("ОткрытьПроверкуМодуля"); //$NON-NLS-1$
            WinWindowActivator.sendCtrlEnterToIrAfterDelay(irSession, CTRL_ENTER_DELAY_MS);
        }
        catch (Exception e)
        {
            Global.log("IrModuleCheckHandler: " + e.getMessage()); //$NON-NLS-1$
            ToastNotification.show(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }
}
