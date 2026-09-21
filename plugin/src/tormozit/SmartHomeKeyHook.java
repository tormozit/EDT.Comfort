package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;

/**
 * Штатный «умный Home» ({@code AbstractTextEditor.LineStartAction}: первое нажатие — на
 * первый непробельный символ строки, повторное — уже в 0-ю колонку) не срабатывает в
 * BSL-редакторе EDT — Home всегда ставит каретку в начало строки,
 * https://github.com/tormozit/EDT.Comfort/issues/559. Причина — в самом EDT/Xtext, не в
 * плагине, поэтому лечится здесь как обход: перехватываем Home/Shift+Home на любом
 * {@code StyledText} и считаем целевую позицию сами по тексту виджетной строки,
 * не трогая документ/AST (для одной видимой строки виджетные и модельные координаты
 * совпадают — свёртки прячут только целые строки выше, см. правило про каретку и folding).
 *
 * <p>Поведение включено безусловно, без привязки к штатному чекбоксу «Автоматически
 * помещать курсор в начало или конец строки» (Window → Preferences → General → Editors →
 * Text Editors) — связь с этой формулировкой неочевидна пользователю плагина.
 * Ctrl+Home/Ctrl+Shift+Home (переход в начало документа) не трогаем.
 */
public final class SmartHomeKeyHook implements IStartup
{
    private static final String INSTALLED_KEY = "tormozit.smartHomeKeyInstalled"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.FocusIn, SmartHomeKeyHook::handleFocusIn);
    }

    private static void handleFocusIn(Event event)
    {
        if (!(event.widget instanceof StyledText text) || text.isDisposed())
            return;
        if (Boolean.TRUE.equals(text.getData(INSTALLED_KEY)))
            return;
        text.setData(INSTALLED_KEY, Boolean.TRUE);
        text.addVerifyKeyListener(SmartHomeKeyHook::handleVerifyKey);
    }

    private static void handleVerifyKey(VerifyEvent e)
    {
        if (e.keyCode != SWT.HOME || (e.stateMask & ~SWT.SHIFT) != 0)
            return;
        if (!(e.widget instanceof StyledText text) || text.isDisposed())
            return;

        e.doit = false;
        boolean select = (e.stateMask & SWT.SHIFT) != 0;
        boolean caretAtStart = text.getCaretOffset() == text.getSelection().x;

        int[] ranges = text.getSelectionRanges();
        int[] newRanges = new int[ranges.length];
        for (int i = 0; i < ranges.length; i += 2)
        {
            int offset = ranges[i];
            int length = ranges[i + 1];
            int caretOffset = caretAtStart ? offset : offset + length;

            int line = text.getLineAtOffset(caretOffset);
            int lineOffset = text.getOffsetAtLine(line);
            String lineText = text.getLine(line);

            int firstNonBlank = 0;
            while (firstNonBlank < lineText.length()
                && Character.isWhitespace(lineText.charAt(firstNonBlank)))
                firstNonBlank++;

            int target = (caretOffset - lineOffset == firstNonBlank)
                ? lineOffset
                : lineOffset + firstNonBlank;
            int anchor = select ? (caretAtStart ? offset + length : offset) : target;

            newRanges[i] = anchor;
            newRanges[i + 1] = target - anchor;
        }

        text.setSelectionRanges(newRanges);
        if (newRanges.length == 2)
            text.showSelection();
    }
}
