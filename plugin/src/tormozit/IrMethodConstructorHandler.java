package tormozit;

import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Порт {@code КонструкторМетода()} из RDT: конструктор метода ИР из редактора модуля.
 */
public final class IrMethodConstructorHandler extends AbstractHandler
{
    /** Идентификатор команды плагина ({@code plugin.xml}). */
    public static final String COMMAND_ID = "tormozit.IrMethodConstructor"; //$NON-NLS-1$

    private static final Pattern METHOD_CONSTRUCTOR_TITLE =
        Pattern.compile("^Конструктор метода.*"); //$NON-NLS-1$
    private static final long DIALOG_WAIT_MS = 5_000;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        BslXtextEditor editor = resolveBslEditor(HandlerUtil.getActivePart(event));
        if (editor == null)
            editor = resolveBslEditor(HandlerUtil.getActiveEditor(event));
        openMethodConstructor(editor);
        return null;
    }

    public static void openMethodConstructor(BslXtextEditor editor)
    {
        if (editor == null)
        {
            toast("Конструктор метода", "Откройте модуль BSL и повторите команду"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        irSession.syncCodeEditorToIR(editor);
        irSession.executor.submit(() ->
        {
            try
            {
                ensureCodeEditor(irSession);
                irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
                long language = ComBridge.toLong(
                    ComBridge.getProperty(irSession.codeEditor, "мЯзыкПрограммы")); //$NON-NLS-1$
                if (language != 0)
                {
                    toast("Конструктор метода", "Команда доступна только в модуле"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                Object result = irSession.runIrModalDialog(METHOD_CONSTRUCTOR_TITLE, DIALOG_WAIT_MS,
                    () -> irSession.invokeCodeEditor("ОткрытьКонструкторМетода"), //$NON-NLS-1$
                    r -> "Ошибка".equals(ComBridge.toString(r))); //$NON-NLS-1$

                if (IRApplication.isCancelled(result))
                    return;

                String resultText = ComBridge.toString(result);
                if ("Ошибка".equals(resultText)) //$NON-NLS-1$
                {
                    if (!WinWindowActivator.isWindows() || irSession.pid <= 0)
                        irSession.showWindow();
                    return;
                }

                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                    display.asyncExec(() -> irSession.syncCodeEditorFromIR(editor));
            }
            catch (Exception e)
            {
                Global.log("IrMethodConstructorHandler: " + e.getMessage()); //$NON-NLS-1$
                toast("Конструктор метода", "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    private static BslXtextEditor resolveBslEditor(IWorkbenchPart part)
    {
        if (part instanceof IEditorPart editorPart)
            return GetRef.getActiveBslEditor(editorPart);
        return GetRef.getActiveBslEditor(part);
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

