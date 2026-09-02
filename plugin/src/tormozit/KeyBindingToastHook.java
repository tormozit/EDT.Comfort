package tormozit;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListenerWithChecks;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
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
 * <p>Тост показывается <b>по исходу</b> выполнения: «Выполнена команда» либо «Команда не
 * выполнена» с причиной (запрещена, нет обработчика, ошибка). Само «вызвана» пользователю
 * ничего не даёт — команда, чей обработчик в текущем контексте запрещён, завершается тихо, и
 * нажатие выглядит как бездействие. Исход виден только через
 * {@link org.eclipse.core.commands.IExecutionListenerWithChecks} — у обычного
 * {@code IExecutionListener} нет колбэка {@code notEnabled}.
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

    /**
     * Сколько ждём исход выполнения, прежде чем показать тост без него. Команда, открывшая
     * модальный диалог, «завершится» только после его закрытия — молчать всё это время нельзя,
     * а показывать «Выполнена» задним числом тем более.
     */
    private static final int OUTCOME_WAIT_MS = 500;

    private static volatile long lastToastAt;
    private static boolean executionListenerInstalled;

    /** Вызов, по которому ждём исход (всё — в потоке UI, синхронизация не нужна). */
    private static String pendingCommandId;
    private static String pendingMessage;
    private static Runnable pendingTimeout;

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

    private static final IExecutionListenerWithChecks executionListener =
            new IExecutionListenerWithChecks()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            // Только запоминаем вызов: тост покажем по исходу (см. notePendingToast).
            try
            {
                Object trigger = event != null ? event.getTrigger() : null;
                Event triggerEvent = trigger instanceof Event e ? e : null;
                notePendingToast(commandId, triggerEvent);
            }
            catch (Exception e)
            {
            }
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            showOutcomeToast(commandId, "Выполнена команда", null); //$NON-NLS-1$
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            showOutcomeToast(commandId, "Команда не выполнена", //$NON-NLS-1$
                    "нет обработчика в текущем контексте"); //$NON-NLS-1$
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            showOutcomeToast(commandId, "Команда не выполнена", //$NON-NLS-1$
                    describeFailureReason(exception));
        }

        @Override
        public void notEnabled(String commandId, NotEnabledException exception)
        {
            showOutcomeToast(commandId, "Команда не выполнена", //$NON-NLS-1$
                    "недоступна в текущем контексте"); //$NON-NLS-1$
        }

        @Override
        public void notDefined(String commandId, NotDefinedException exception)
        {
            showOutcomeToast(commandId, "Команда не выполнена", //$NON-NLS-1$
                    "команда не объявлена"); //$NON-NLS-1$
        }
    };

    /**
     * Вызов только запоминается: тост показывается по исходу выполнения. «Вызвана» само по себе
     * пользователю ничего не говорит — команда может оказаться запрещённой или без обработчика
     * в текущем контексте, и тогда нажатие выглядит как молчаливое бездействие. Показ идёт из
     * {@link #showOutcomeToast}, а если исход не пришёл за {@link #OUTCOME_WAIT_MS} (команда
     * открыла диалог и не завершается до его закрытия) — из таймера, уже без строки исхода.
     */
    private static void notePendingToast(String commandId, Event trigger)
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

        cancelPendingTimeout();
        pendingCommandId = commandId;
        pendingMessage = message;
        schedulePendingTimeout();
    }

    /** Исход пришёл — показываем единственный тост по нему. */
    private static void showOutcomeToast(String commandId, String title, String reason)
    {
        try
        {
            if (commandId == null || !commandId.equals(pendingCommandId))
                return;
            String message = pendingMessage;
            clearPending();

            StringBuilder sb = new StringBuilder(message);
            if (reason != null && !reason.isBlank())
                sb.append('\n').append(reason);

            showToast(title, sb.toString(), commandId);
        }
        catch (Exception e)
        {
        }
    }

    /**
     * Команда не завершилась за отведённое время (типично — открыла модальный диалог, а тот
     * крутит свой цикл событий, поэтому таймер срабатывает). Показываем тост без исхода:
     * ждать закрытия диалога и сообщать «выполнена» задним числом бессмысленно.
     */
    private static void schedulePendingTimeout()
    {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        String commandId = pendingCommandId;
        pendingTimeout = () ->
        {
            if (commandId == null || !commandId.equals(pendingCommandId))
                return;
            String message = pendingMessage;
            clearPending();
            showToast("Вызвана команда", message, commandId); //$NON-NLS-1$
        };
        display.timerExec(OUTCOME_WAIT_MS, pendingTimeout);
    }

    private static void cancelPendingTimeout()
    {
        if (pendingTimeout == null)
            return;
        Display display = Display.getCurrent();
        if (display != null && !display.isDisposed())
            display.timerExec(-1, pendingTimeout);
        pendingTimeout = null;
    }

    private static void clearPending()
    {
        cancelPendingTimeout();
        pendingCommandId = null;
        pendingMessage = null;
    }

    private static void showToast(String title, String message, String commandId)
    {
        lastToastAt = System.currentTimeMillis();
        ToastNotification.show(
                title,
                message,
                4000,
                () -> ComfortKeysPreferences.openKeysPageForCommand(commandId),
                "Настроить команду"); //$NON-NLS-1$
    }

    private static String describeFailureReason(ExecutionException exception)
    {
        Throwable cause = exception == null ? null : exception.getCause();
        Throwable reported = cause != null ? cause : exception;
        if (reported == null)
            return "ошибка выполнения"; //$NON-NLS-1$
        String message = reported.getMessage();
        return message == null || message.isBlank()
                ? "ошибка выполнения: " + reported.getClass().getSimpleName() //$NON-NLS-1$
                : "ошибка выполнения: " + message; //$NON-NLS-1$
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
