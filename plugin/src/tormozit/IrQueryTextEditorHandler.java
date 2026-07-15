package tormozit;

import java.util.regex.Pattern;

import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Открытие текста запроса в текстовом редакторе ИР (псевдомодально).
 */
public final class IrQueryTextEditorHandler
{
    static final String MENU_LABEL = "Редактор ИР"; //$NON-NLS-1$
    private static final long DIALOG_WAIT_MS = 5_000;

    private IrQueryTextEditorHandler() {}

    public static void openQueryTextInIrEditor(ISourceViewer viewer, Object queryDialog,
        Shell shell)
    {
        if (viewer == null || viewer.getDocument() == null)
            return;

        String text = viewer.getDocument().get();
        if (text == null || text.isEmpty())
            return;

        IDtProject dtProject = IrFormatTextHandler.resolveDtProjectForQuery(queryDialog);
        if (dtProject == null)
        {
            toast(MENU_LABEL, "Не удалось определить проект EDT"); //$NON-NLS-1$
            return;
        }

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        String sourceRef = shell.getText();
        Pattern titlePattern = Pattern.compile("^Текст: .*"); //$NON-NLS-1$ //$NON-NLS-2$

        irSession.executor.submit(() -> {
            try
            {
                String newText = irSession.runIrModalDialog(titlePattern, DIALOG_WAIT_MS, () -> {
                    Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
                    String lfText = Global.normalizeLineSeparators(text);
                    // (Текст, Заголовок, ВариантПросмотра, ТолькоПросмотр,
                    //  КлючУникальности, ВладелецФормы, ВыделитьВсе, Модально,
                    //  ВыделениеДвумерное, ИскомаяСтрока, КлючИсточника)
                    Object result = ComBridge.invoke(irClient, "ОткрытьТекстЛкс", lfText, sourceRef, //$NON-NLS-1$
                        null, false, null, null, false, true, null, ""); //$NON-NLS-1$
                    if (IRApplication.isCancelled(result))
                        return null;
                    return ComBridge.toString(result);
                });
                if (newText == null || newText.equals(text))
                    return;
                String normalized = Global.normalizeLineSeparators(newText);
                Display ui = Display.getDefault();
                if (ui != null && !ui.isDisposed())
                    ui.asyncExec(() -> {
                        if (viewer.getDocument() != null)
                            viewer.getDocument().set(normalized);
                    });
            }
            catch (Exception e)
            {
                Global.log("IrQueryTextEditorHandler: " + e.getMessage()); //$NON-NLS-1$
                toast(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
            }
        });
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
