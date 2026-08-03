package tormozit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * Команда «Открыть объект конфигурации» — делегирует штатному диалогу EDT
 * ({@code com._1c.g5.v8.dt.md.ui.openMdObjectDialog}). Если в момент вызова
 * выделен подходящий фрагмент текста (без разделителей строк, короче 100
 * символов), он подставляется в поле поиска штатного диалога — сам диалог
 * не даёт штатного способа передать начальный текст, поэтому текст
 * подставляется в его строку поиска уже после открытия.
 */
public class OpenConfigurationObjectHandler extends AbstractHandler
{
    private static final String TAG = "OpenConfigurationObject"; //$NON-NLS-1$

    private static final int MAX_SELECTION_LENGTH = 100;

    /** Идентификатор команды плагина ({@code plugin.xml}). */
    public static final String COMMAND_ID = "tormozit.OpenConfigurationObject"; //$NON-NLS-1$

    private static final String EDT_COMMAND_ID =
            "com._1c.g5.v8.dt.md.ui.openMdObjectDialog"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null)
            return null;

        IHandlerService handlerService = window.getService(IHandlerService.class);
        if (handlerService == null)
            return null;

        String initialFilter = getInitialFilter(event);
        if (initialFilter != null)
        {
            Display display = window.getShell().getDisplay();
            Set<Shell> shellsBefore = new HashSet<>(Arrays.asList(display.getShells()));
            display.asyncExec(() -> applyInitialFilter(display, shellsBefore, initialFilter));
        }

        try
        {
            return handlerService.executeCommand(EDT_COMMAND_ID, null);
        }
        catch (ExecutionException e)
        {
            Global.logError(TAG, "execute openMdObjectDialog", e); //$NON-NLS-1$
            throw e;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "execute openMdObjectDialog", e); //$NON-NLS-1$
            throw new ExecutionException("Открыть объект конфигурации недоступно", e); //$NON-NLS-1$
        }
    }

    private static String getInitialFilter(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof ITextSelection))
            return null;

        String text = ((ITextSelection)selection).getText();
        if (text == null || text.isEmpty() || text.length() >= MAX_SELECTION_LENGTH)
            return null;
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
            return null;

        return text;
    }

    private static void applyInitialFilter(Display display, Set<Shell> shellsBefore, String filter)
    {
        Shell dialogShell = null;
        for (Shell shell : display.getShells())
        {
            if (!shellsBefore.contains(shell) && !shell.isDisposed())
            {
                dialogShell = shell;
                break;
            }
        }
        if (dialogShell == null)
            return;

        Text patternText = findFirstText(dialogShell);
        if (patternText == null || patternText.isDisposed())
            return;

        patternText.setText(filter);
        patternText.setSelection(filter.length());
    }

    private static Text findFirstText(Control control)
    {
        if (control instanceof Text)
            return (Text)control;
        if (control instanceof Composite)
        {
            for (Control child : ((Composite)control).getChildren())
            {
                Text found = findFirstText(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
