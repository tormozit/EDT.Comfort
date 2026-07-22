package tormozit;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.List;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

/**
 * Глобальный фолбэк Ctrl+C для {@link List}, у которого нет своего обработчика команды
 * {@code org.eclipse.ui.edit.copy} — в т.ч. чужие (не наши) списки в диалогах EDT/Eclipse.
 *
 * <p>Перехват — не через {@code SWT.KeyDown} (Ctrl+C идёт через нативную трансляцию Win32-
 * акселератора раньше создания SWT-события, см. {@link KeyBindingToastHook}), а через
 * {@code ICommandService.addExecutionListener}. В отличие от
 * {@link PreferenceSearchFilterAugmenter#wireTreeCopy}, реагируем только на
 * {@link IExecutionListener#notHandled} — этот колбэк вызывается ровно тогда, когда у команды
 * нет активного обработчика, то есть формальный признак «своей команды копирования нет».
 * {@code preExecute} не трогаем, чтобы не вмешиваться в списки, у которых копирование уже
 * работает.
 */
public final class GlobalListCopyHook implements IStartup
{
    private static final String COPY_COMMAND_ID = "org.eclipse.ui.edit.copy"; //$NON-NLS-1$

    private static boolean executionListenerInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null)
            return;
        display.asyncExec(GlobalListCopyHook::installExecutionListener);
    }

    private static void installExecutionListener()
    {
        if (executionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleListCopy(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
        executionListenerInstalled = true;
    }

    private static void handlePossibleListCopy(String commandId)
    {
        if (!COPY_COMMAND_ID.equals(commandId))
            return;
        Control focus = Display.getCurrent().getFocusControl();
        if (!(focus instanceof List list) || list.isDisposed())
        {
            return;
        }
        String[] selection = list.getSelection();
        if (selection.length == 0)
        {
            return;
        }
        String text = String.join("\n", selection); //$NON-NLS-1$
        Clipboard clipboard = new Clipboard(list.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
    }
}
