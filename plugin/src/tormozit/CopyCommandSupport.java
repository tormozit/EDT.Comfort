package tormozit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

/**
 * Общий безопасный перехват Ctrl+C для кастомных виджетов Комфорт (дерево, Text, Table и т.п.),
 * которые не получают {@code SWT.KeyDown} — нативная Win32-трансляция акселератора съедает
 * букву раньше, чем до неё доходит SWT (тот же архитектурный потолок, что у
 * {@code KeyBindingToastHook}/Ctrl+Shift+F). Перехват — через {@code ICommandService} на команде
 * {@code org.eclipse.ui.edit.copy}.
 *
 * <p><b>Копирование выполняется ТОЛЬКО из {@code postExecuteSuccess}/{@code notHandled}/
 * {@code postExecuteFailure}, НИКОГДА из {@code preExecute}.</b> Подтверждено логом сессии
 * (см. историю правок {@code ToastNotification}): если писать в буфер из {@code preExecute},
 * штатный обработчик {@code edit.copy}, который в некоторых контекстах (реальный редактор,
 * виджет с зарегистрированным global action handler) выполняется СРАЗУ ПОСЛЕ, перезаписывает
 * буфер обратно на своё представление о выделении — наша запись из {@code preExecute} молча
 * теряется. Запись после выполнения команды (успешного или нет) гарантированно остаётся
 * последней.
 *
 * <p>Один общий {@code IExecutionListener} на весь плагин — не по слушателю на виджет.
 */
public final class CopyCommandSupport
{
    private static final String EDIT_COPY_COMMAND_ID = "org.eclipse.ui.edit.copy"; //$NON-NLS-1$

    private static final Map<Control, Runnable> targets = new ConcurrentHashMap<>();

    private static boolean listenerInstalled;

    private CopyCommandSupport()
    {
    }

    /**
     * Подключает {@code copyAction} — вызывается, когда команда {@code edit.copy} срабатывает
     * (успешно или нет) при фокусе на {@code control}. {@code copyAction} сам решает, что и как
     * копировать (обычно — читает текущее выделение виджета и пишет в {@link
     * org.eclipse.swt.dnd.Clipboard}); ничего не копировать при пустом выделении — тоже на
     * усмотрение {@code copyAction}.
     */
    public static void wireCopyOverride(Control control, Runnable copyAction)
    {
        if (control == null || control.isDisposed() || copyAction == null)
            return;
        targets.put(control, copyAction);
        control.addDisposeListener(e -> targets.remove(control));
        installExecutionListener();
    }

    private static void installExecutionListener()
    {
        if (listenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                // Намеренно пусто — см. javadoc класса: писать в буфер здесь нельзя.
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                handlePossibleCopy(commandId);
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleCopy(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
                handlePossibleCopy(commandId);
            }
        });
        listenerInstalled = true;
    }

    private static void handlePossibleCopy(String commandId)
    {
        if (!EDIT_COPY_COMMAND_ID.equals(commandId))
            return;
        Display display = Display.getCurrent();
        if (display == null)
            return;
        Control focus = display.getFocusControl();
        if (focus == null)
            return;
        for (Control c = focus; c != null && !c.isDisposed(); c = c.getParent())
        {
            Runnable action = targets.get(c);
            if (action != null)
            {
                action.run();
                return;
            }
        }
    }
}
