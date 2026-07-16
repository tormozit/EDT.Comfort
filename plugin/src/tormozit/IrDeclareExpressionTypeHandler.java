package tormozit;

import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Порт {@code ОбъявитьТипВыражения()} из RDT: явное объявление типа выражения через приложение ИР.
 */
public final class IrDeclareExpressionTypeHandler
{
    public static final String COMMAND_ID = "tormozit.IrDeclareExpressionType"; //$NON-NLS-1$
    static final String MENU_LABEL = "Объявить тип выражения ИР"; //$NON-NLS-1$

    private IrDeclareExpressionTypeHandler() {}

    public static void declareExpressionType(BslXtextEditor editor)
    {
        if (editor == null)
        {
            toast(MENU_LABEL, "Откройте модуль BSL и повторите команду"); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() -> declareOnComThread(irSession, editor));
    }

    /** COM-поток {@link IRSession#executor}: без вложенного {@link IRSession#executeOnComThread}. */
    private static void declareOnComThread(IRSession irSession, BslXtextEditor editor)
    {
        try
        {
            ensureCodeEditor(irSession);
            irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
            long language = ComBridge.toLong(
                ComBridge.getProperty(irSession.codeEditor, "мЯзыкПрограммы")); //$NON-NLS-1$
            if (language != 0)
            {
                toast(MENU_LABEL, "Команда доступна только в модуле"); //$NON-NLS-1$
                return;
            }

            boolean declared = ComBridge.toBoolean(
                irSession.invokeCodeEditor("ОбъявитьТипВыражения")); //$NON-NLS-1$
            if (!declared)
                return;

            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> irSession.syncCodeEditorFromIR(editor));
        }
        catch (Exception e)
        {
            Global.log("IrDeclareExpressionTypeHandler: " + e.getMessage()); //$NON-NLS-1$
            toast(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
        }
    }

    private static void ensureCodeEditor(IRSession irSession)
    {
        if (irSession.codeEditor != null)
            return;
        Object irCache = irSession.getModule("ирКэш"); //$NON-NLS-1$
        irSession.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
    }

    private static void toast(String title, String message)
    {
        toast(title, message, 3_000);
    }

    private static void toast(String title, String message, int durationMs)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, durationMs));
    }
}
