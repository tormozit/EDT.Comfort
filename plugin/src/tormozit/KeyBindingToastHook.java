package tormozit;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.bindings.keys.SWTKeySupport;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.internal.util.PrefUtil;
import org.eclipse.ui.keys.IBindingService;

/**
 * Дополняет штатный оверлей Eclipse «Show key binding when command is invoked» (Клавиши →
 * «При нажатии клавиш», преф-ключ {@code showCommandKeysForKeyboard}) тостом Комфорт с
 * гиперссылкой «Настроить команду» — открывает страницу «Клавиши» с выделенной привязкой
 * сработавшей команды.
 *
 * <p>Определение «сработало с клавиатуры» — тем же приёмом, что и штатный
 * {@code org.eclipse.ui.internal.keys.show.ShowKeysListener}: НЕ через {@code Display.KeyDown}-
 * фильтр (он не видит команды, чьё сочетание идёт через нативную трансляцию акселератора меню
 * на Win32 — например Ctrl+Shift+F), а через {@code ExecutionEvent.getTrigger()} — тот же
 * {@code Event}, что донёс срабатывание команды независимо от пути (обычный KeyDown или
 * нативный акселератор), плюс {@code SWTKeySupport.convertEventToUnmodifiedAccelerator} +
 * {@code KeyStroke.getNaturalKey() != 0} для проверки, что это реально клавиатурный ввод.
 */
public final class KeyBindingToastHook implements IStartup
{
    private static final String SHOW_COMMAND_KEYS_FOR_KEYBOARD_PREF =
            "showCommandKeysForKeyboard"; //$NON-NLS-1$

    /** Не показывать повторный тост чаще этого интервала (защита от дребезга/повторов). */
    private static final long TOAST_MIN_INTERVAL_MS = 1500;

    private static volatile long lastToastAt;
    private static boolean executionListenerInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null)
            return;
        display.asyncExec(KeyBindingToastHook::installExecutionListener);
    }

    private static void installExecutionListener()
    {
        if (executionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService =
                PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(executionListener);
        executionListenerInstalled = true;
    }

    private static final IExecutionListener executionListener = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            // Как и штатный ShowKeysListener — показываем ДО выполнения команды, а не после
            // (postExecuteSuccess): если команда открывает модальный диалог, она не «завершится»
            // до его закрытия — тост ждал бы всё это время вместо мгновенного показа.
            try
            {
                Object trigger = event != null ? event.getTrigger() : null;
                maybeShowToast(commandId, trigger instanceof Event triggerEvent ? triggerEvent : null);
            }
            catch (Exception e)
            {
            }
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            // показ уже произошёл в preExecute
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            // ничего — тост уже мог показаться в preExecute, это ожидаемо (как у ShowKeysListener)
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            // ничего — тост уже мог показаться в preExecute, это ожидаемо (как у ShowKeysListener)
        }
    };

    private static void maybeShowToast(String commandId, Event trigger)
    {
        if (commandId == null || commandId.isBlank())
            return;
        if (!isShowCommandKeysForKeyboardEnabled())
        {
            return;
        }
        if (!isKeyboardTrigger(trigger))
        {
            return;
        }

        long now = System.currentTimeMillis();
        long sinceLastToast = now - lastToastAt;
        if (sinceLastToast < TOAST_MIN_INTERVAL_MS)
        {
            return;
        }

        Command command = resolveCommand(commandId);
        if (command == null)
        {
            return;
        }
        String message = buildToastMessage(command);
        if (message == null)
        {
            return;
        }

        lastToastAt = now;
        String finalCommandId = commandId;
        ToastNotification.show(
                "Выполнена команда", //$NON-NLS-1$
                message,
                4000,
                () -> ComfortKeysPreferences.openKeysPageForCommand(finalCommandId),
                "Настроить команду"); //$NON-NLS-1$
    }

    /**
     * Тот же приём, что {@code ShowKeysUI.getFormattedShortcut}: реконструирует {@link KeyStroke}
     * из триггера и проверяет {@code getNaturalKey() != 0} — признак того, что триггер нёс
     * реальную клавишу (а не, например, клик мышью или программный вызов без {@code Event}).
     * Работает и для команд, чьё сочетание идёт через нативную трансляцию акселератора меню —
     * триггер-{@code Event} у них есть, даже если {@code Display.KeyDown}-фильтр его не видел.
     */
    private static boolean isKeyboardTrigger(Event trigger)
    {
        if (trigger == null)
            return false;
        try
        {
            int accelerator = SWTKeySupport.convertEventToUnmodifiedAccelerator(trigger);
            KeyStroke stroke = SWTKeySupport.convertAcceleratorToKeyStroke(accelerator);
            return stroke != null && stroke.getNaturalKey() != 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static String buildToastMessage(Command command)
    {
        String name = resolveCommandName(command);
        if (name == null)
            return null;

        StringBuilder sb = new StringBuilder(name);

        String description = resolveCommandDescription(command);
        if (description != null && !description.isBlank())
            sb.append('\n').append(description);

        String sequence = resolveFormattedBinding(command.getId());
        if (sequence != null && !sequence.isBlank())
            sb.append('\n').append("Сочетание: ").append(sequence); //$NON-NLS-1$

        return sb.toString();
    }

    private static String resolveFormattedBinding(String commandId)
    {
        if (PlatformUI.getWorkbench() == null)
            return null;
        IBindingService bindingService =
                PlatformUI.getWorkbench().getService(IBindingService.class);
        if (bindingService == null)
            return null;
        try
        {
            return bindingService.getBestActiveBindingFormattedFor(commandId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String resolveCommandDescription(Command command)
    {
        try
        {
            return command.getDescription();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static boolean isShowCommandKeysForKeyboardEnabled()
    {
        try
        {
            IPreferenceStore store = PrefUtil.getInternalPreferenceStore();
            return store != null && store.getBoolean(SHOW_COMMAND_KEYS_FOR_KEYBOARD_PREF);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static Command resolveCommand(String commandId)
    {
        ICommandService commandService =
                PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return null;
        Command command = commandService.getCommand(commandId);
        return command != null && command.isDefined() ? command : null;
    }

    private static String resolveCommandName(Command command)
    {
        try
        {
            return command.getName();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
