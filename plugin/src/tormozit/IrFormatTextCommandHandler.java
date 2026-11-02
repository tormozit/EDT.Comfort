package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * Заглушка удалённой команды «Форматировать текст ИР» ({@code tormozit.IrFormatText}).
 *
 * <p>Команда объявлена, чтобы сохранённые в настройках пользователя привязки клавиш (по умолчанию
 * Alt+Shift+F) не вели в «команду не объявлена»: она выполняет штатное «Форматировать».
 * Новых привязок у неё нет.
 */
public final class IrFormatTextCommandHandler extends AbstractHandler
{
    private static final String STOCK_FORMAT_COMMAND_ID = "org.eclipse.xtext.ui.FormatAction"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
        try
        {
            handlers.executeCommand(STOCK_FORMAT_COMMAND_ID, null);
        }
        catch (NotDefinedException | NotHandledException | NotEnabledException e)
        {
            Global.log("IrFormatTextCommandHandler: " + e); //$NON-NLS-1$
        }
        return null;
    }
}
