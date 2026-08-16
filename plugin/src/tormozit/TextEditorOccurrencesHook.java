package tormozit;

import java.util.Locale;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

/**
 * Триггер {@link TextEditorOccurrencesSupport}: подписка на смену выделения/фокуса
 * любого {@link StyledText}. {@code StyledText} рассылает {@code SWT.Selection} и при
 * движении каретки, и при программной установке выделения (двойной клик, Ctrl+клик,
 * «Найти/Заменить», быстрый поиск Ctrl+F3) — этого достаточно, чтобы заметить
 * «идентификатор выделен целиком».
 */
public final class TextEditorOccurrencesHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            TextEditorOccurrencesSupport.init();
            install(Display.getDefault());
        });
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Selection, TextEditorOccurrencesHook::handleEvent);
        display.addFilter(SWT.FocusIn, TextEditorOccurrencesHook::handleEvent);
        display.addFilter(SWT.KeyDown, TextEditorOccurrencesHook::handleNavigationKey);
        installSearchCommandListener();
    }

    /**
     * Команды поиска платформы (F3 «Найти далее», Shift+F3 «Найти предыдущее» и т.п.)
     * ставят выделение на найденное вхождение — после них подсветка работает в режиме
     * поиска, без требования «слово выделено целиком». Быстрый поиск самого плагина
     * (Ctrl+F3 / Ctrl+Shift+F3) сюда не попадает — он отмечается прямо в
     * {@code TextEditorFastSearchHandler.selectFound}.
     *
     * <p>Ловим именно команду, а не клавишу: сочетания с Ctrl до {@code SWT.KeyDown} не
     * доходят (нативный акселератор Win32), да и раскладки клавиш у пользователей разные.
     */
    private static void installSearchCommandListener()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commandService =
            PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                /*
                 * Отметку ставим ДО выполнения: выделение команда меняет синхронно, и
                 * событие Selection может прийти раньше postExecuteSuccess.
                 */
                if (isSearchCommand(commandId))
                    TextEditorOccurrencesSupport.markSearchNavigation();
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
                if (isSearchCommand(commandId))
                    Display.getDefault().asyncExec(
                        TextEditorOccurrencesSupport::refreshFromFocus);
            }

            @Override public void notHandled(String id, NotHandledException e)          {}
            @Override public void postExecuteFailure(String id, ExecutionException e)   {}
        });
    }

    /**
     * Команда поиска по тексту. Идентификаторы платформы и EDT различаются
     * ({@code org.eclipse.ui.edit.findNext}, {@code ...findPrevious},
     * {@code ...findIncremental} и т.п.), поэтому отбор по подстроке {@code find} —
     * кроме открытия самого диалога, где выделение ещё не изменилось.
     */
    private static boolean isSearchCommand(String commandId)
    {
        if (commandId == null)
            return false;
        String id = commandId.toLowerCase(Locale.ROOT);
        if (id.endsWith("findreplace")) //$NON-NLS-1$
            return false;
        if (id.contains("find")) //$NON-NLS-1$
            return true;
        return id.contains("search") //$NON-NLS-1$
            && (id.contains("next") || id.contains("prev")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void handleEvent(Event e)
    {
        if (e.widget instanceof StyledText text && !text.isDisposed())
            TextEditorOccurrencesSupport.scheduleSelection(text);
    }

    /**
     * Страховка на случай, если платформенный {@code StyledText} не послал
     * {@code SWT.Selection} при клавиатурном движении каретки — иначе подсветка
     * «залипнет» после ухода каретки с выделенного слова.
     */
    private static void handleNavigationKey(Event e)
    {
        if (!(e.widget instanceof StyledText text) || text.isDisposed())
            return;
        switch (e.keyCode)
        {
            case SWT.ARROW_LEFT:
            case SWT.ARROW_RIGHT:
            case SWT.ARROW_UP:
            case SWT.ARROW_DOWN:
            case SWT.PAGE_UP:
            case SWT.PAGE_DOWN:
            case SWT.HOME:
            case SWT.END:
            case SWT.ESC:
                break;
            default:
                return;
        }
        TextEditorOccurrencesSupport.scheduleSelection(text);
    }
}
