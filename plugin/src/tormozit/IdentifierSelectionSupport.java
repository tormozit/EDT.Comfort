package tormozit;

import org.eclipse.swt.custom.MovementEvent;
import org.eclipse.swt.custom.MovementListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Text;

/**
 * Границы «слова» для Ctrl+←/→ и Ctrl+Shift+←/→: непрерывная последовательность букв, цифр и {@code _}.
 * Один шаг — соседняя граница; {@code .} и прочая пунктуация — разделители между сегментами.
 */
final class IdentifierSelectionSupport
{
    static final String WORD_MOVEMENT_INSTALLED_KEY = "tormozit.identifierWordMovement"; //$NON-NLS-1$

    private IdentifierSelectionSupport()
    {
    }

    static boolean isIdentifierChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Смещение соседней границы идентификатора слева от {@code offset} (для расширения выделения влево).
     */
    static int previousBoundary(String text, int offset)
    {
        if (text == null || text.isEmpty() || offset <= 0)
            return 0;

        int len = text.length();
        int pos = Math.min(offset, len);

        if (pos > 0 && isIdentifierChar(text.charAt(pos - 1)))
        {
            while (pos > 0 && isIdentifierChar(text.charAt(pos - 1)))
                pos--;
            return pos;
        }

        while (pos > 0 && !isIdentifierChar(text.charAt(pos - 1)))
            pos--;
        return pos;
    }

    /**
     * Смещение соседней границы идентификатора справа от {@code offset} (для расширения выделения вправо).
     */
    static int nextBoundary(String text, int offset)
    {
        if (text == null || text.isEmpty())
            return 0;

        int len = text.length();
        if (offset >= len)
            return len;

        int pos = Math.max(0, offset);

        if (isIdentifierChar(text.charAt(pos)))
        {
            while (pos < len && isIdentifierChar(text.charAt(pos)))
                pos++;
            return pos;
        }

        while (pos < len && !isIdentifierChar(text.charAt(pos)))
            pos++;
        return pos;
    }

    static void installWordMovement(StyledText text)
    {
        if (text == null || text.isDisposed())
            return;
        if (Boolean.TRUE.equals(text.getData(WORD_MOVEMENT_INSTALLED_KEY)))
            return;

        MovementListener listener = new IdentifierMovementListener();
        text.addWordMovementListener(listener);
        text.setData(WORD_MOVEMENT_INSTALLED_KEY, Boolean.TRUE);
        text.addDisposeListener(e -> text.removeWordMovementListener(listener));
    }

    /**
     * Расширяет выделение до границы идентификатора.
     *
     * @return {@code true}, если каретка перемещена
     */
    static boolean extendSelection(StyledText text, boolean toLeft)
    {
        if (text == null || text.isDisposed())
            return false;

        String content = text.getText();
        int caret = text.getCaretOffset();
        int newCaret = toLeft
            ? previousBoundary(content, caret)
            : nextBoundary(content, caret);
        if (newCaret == caret)
            return false;

        Point sel = text.getSelection();
        if (caret == sel.x)
            text.setSelectionRange(sel.y, newCaret - sel.y);
        else
            text.setSelectionRange(sel.x, newCaret - sel.x);
        text.showSelection();
        return true;
    }

    /**
     * Перемещает каретку к границе идентификатора (без расширения выделения).
     *
     * @return {@code true}, если каретка перемещена
     */
    static boolean moveCaret(StyledText text, boolean toLeft)
    {
        if (text == null || text.isDisposed())
            return false;

        String content = text.getText();
        int caret = text.getCaretOffset();
        int newCaret = toLeft
            ? previousBoundary(content, caret)
            : nextBoundary(content, caret);
        if (newCaret == caret)
            return false;

        text.setSelection(newCaret, newCaret);
        text.showSelection();
        return true;
    }

    /**
     * Ctrl+←/→ и Ctrl+Shift+←/→ в обычном поле ввода {@link Text} (диалоги, формы EDT).
     *
     * @return {@code true}, если каретка/выделение изменены
     */
    static boolean navigateField(Text field, boolean toLeft, boolean extend)
    {
        if (field == null || field.isDisposed())
            return false;

        return navigate(field.getText(), field.getCaretPosition(), field.getSelection(), toLeft, extend,
            (start, end) -> field.setSelection(start, end));
    }

    /**
     * Ctrl+←/→ и Ctrl+Shift+←/→ в поле ввода выпадающего списка {@link Combo}.
     *
     * @return {@code true}, если каретка/выделение изменены
     */
    static boolean navigateField(Combo field, boolean toLeft, boolean extend)
    {
        if (field == null || field.isDisposed() || (field.getStyle() & SWT.READ_ONLY) != 0)
            return false;

        return navigate(field.getText(), field.getCaretPosition(), field.getSelection(), toLeft, extend,
            (start, end) -> field.setSelection(new Point(start, end)));
    }

    private static boolean navigate(String content, int caret, Point selection, boolean toLeft, boolean extend,
        SelectionSetter setter)
    {
        if (content == null || selection == null)
            return false;

        int newCaret = toLeft
            ? previousBoundary(content, caret)
            : nextBoundary(content, caret);
        // Без Shift снятие выделения — тоже изменение, даже если каретка осталась на месте.
        if (newCaret == caret && (extend || selection.x == selection.y))
            return false;

        if (extend)
        {
            int anchor = caret == selection.x ? selection.y : selection.x;
            setter.select(anchor, newCaret);
        }
        else
        {
            setter.select(newCaret, newCaret);
        }
        return true;
    }

    /** Установка выделения в поле ввода: {@code start} — якорь, {@code end} — каретка. */
    private interface SelectionSetter
    {
        void select(int start, int end);
    }

    private static final class IdentifierMovementListener implements MovementListener
    {
        @Override
        public void getPreviousOffset(MovementEvent event)
        {
            int rel = event.offset - event.lineOffset;
            int newRel = previousBoundary(event.lineText, rel);
            event.newOffset = event.lineOffset + newRel;
        }

        @Override
        public void getNextOffset(MovementEvent event)
        {
            int rel = event.offset - event.lineOffset;
            int newRel = nextBoundary(event.lineText, rel);
            event.newOffset = event.lineOffset + newRel;
        }
    }
}
