package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;

/**
 * Триггер {@link UniversalOccurrencesSupport}: подписка на смену выделения/фокуса
 * любого {@link StyledText}. {@code StyledText} рассылает {@code SWT.Selection} и при
 * движении каретки, и при программной установке выделения (двойной клик, Ctrl+клик,
 * «Найти/Заменить», быстрый поиск Ctrl+F3) — этого достаточно, чтобы заметить
 * «идентификатор выделен целиком».
 */
public final class UniversalOccurrencesHook implements IStartup
{
    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            UniversalOccurrencesSupport.init();
            install(Display.getDefault());
        });
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Selection, UniversalOccurrencesHook::handleEvent);
        display.addFilter(SWT.FocusIn, UniversalOccurrencesHook::handleEvent);
        display.addFilter(SWT.KeyDown, UniversalOccurrencesHook::handleNavigationKey);
    }

    private static void handleEvent(Event e)
    {
        if (e.widget instanceof StyledText text && !text.isDisposed())
            UniversalOccurrencesSupport.scheduleSelection(text);
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
        UniversalOccurrencesSupport.scheduleSelection(text);
    }
}
