package tormozit;

import java.util.function.Supplier;

import org.eclipse.swt.custom.MovementEvent;
import org.eclipse.swt.custom.MovementListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.internal.win32.POINT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;

/**
 * Границы «слова» для Ctrl+←/→ и Ctrl+Shift+←/→, а также для выделения слова двойным кликом
 * и Ctrl+кликом в полях ввода: непрерывная последовательность букв, цифр и {@code _}.
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
     * Граница слова в позиции {@code pos} (между символами {@code pos - 1} и {@code pos}):
     * символы разных категорий (идентификатор / не идентификатор) по разные стороны, начало и
     * конец текста — как отсутствующий (не идентификатор) символ снаружи. Семантика как у
     * {@code \b} в regex, а не «оба соседних символа не идентификатор» — иначе поиск «целое
     * слово» для фрагмента, кончающегося на неидентификатор (например {@code "Найти."}), не
     * находит вообще ни одного вхождения, если сразу после точки идёт идентификатор
     * ({@code "Найти.Метод"}): справа от границы стоит идентификатор, а не отсутствие символа.
     */
    static boolean isWordBoundaryAt(String text, int pos)
    {
        boolean before = pos > 0 && isIdentifierChar(text.charAt(pos - 1));
        boolean after = pos < text.length() && isIdentifierChar(text.charAt(pos));
        return before != after;
    }

    /** Совпадение {@code [matchStart, matchEnd)} — целое слово: граница с обеих сторон. */
    static boolean isWholeWordMatch(String text, int matchStart, int matchEnd)
    {
        return isWordBoundaryAt(text, matchStart) && isWordBoundaryAt(text, matchEnd);
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

    /**
     * Слово-идентификатор под указателем — для Ctrl+клика. Позиция определяется по
     * указателю, а не по каретке — см. {@link #charOffsetAtCursor(Control)}.
     *
     * @return границы слова или {@code null}, если под указателем разделитель
     */
    static Point wordAtCursor(Control field)
    {
        if (field == null || field.isDisposed())
            return null;
        if (field instanceof Combo combo && (combo.getStyle() & SWT.READ_ONLY) != 0)
            return null;

        int offset = charOffsetAtCursor(field);
        if (offset < 0)
            return null;
        return identifierWordAt(textOf(field), offset);
    }

    /**
     * Устанавливает выделение слова в поле ввода {@link Text}: после штатной обработки
     * клика ({@code asyncExec}) и с контрольной переустановкой — EDT иногда возвращает
     * «выделить всё» уже после нашей установки (см. {@link #scheduleWordReassert}).
     */
    static void applyWordSelection(Text field, Point word)
    {
        if (field == null || field.isDisposed() || word == null)
            return;

        String content = field.getText();
        field.getDisplay().asyncExec(() ->
        {
            if (field.isDisposed())
                return;
            field.setSelection(word.x, word.y);
            scheduleWordReassert(field, content, word,
                () -> field.getSelection(), (start, end) -> field.setSelection(start, end));
        });
    }

    /** Устанавливает выделение слова в поле ввода {@link Combo} (см. {@link #applyWordSelection(Text, Point)}). */
    static void applyWordSelection(Combo field, Point word)
    {
        if (field == null || field.isDisposed() || word == null)
            return;

        String content = field.getText();
        field.getDisplay().asyncExec(() ->
        {
            if (field.isDisposed())
                return;
            field.setSelection(word);
            scheduleWordReassert(field, content, word,
                () -> field.getSelection(), (start, end) -> field.setSelection(new Point(start, end)));
        });
    }

    /**
     * Двойной клик в поле ввода {@link Text}: выделяет слово-идентификатор под указателем,
     * заменяя штатный результат двойного клика (нативное слово или «выделить всё» EDT).
     * Если под указателем разделитель — штатный результат остаётся как есть.
     */
    static void selectWordAtDoubleClick(Text field)
    {
        if (field == null || field.isDisposed())
            return;

        Point word = wordAtCursor(field);
        if (word != null)
            applyWordSelection(field, word);
    }

    /** Двойной клик в поле ввода {@link Combo}: выделяет слово-идентификатор под указателем (см. {@link #selectWordAtDoubleClick(Text)}). */
    static void selectWordAtDoubleClick(Combo field)
    {
        if (field == null || field.isDisposed() || (field.getStyle() & SWT.READ_ONLY) != 0)
            return;

        Point word = wordAtCursor(field);
        if (word != null)
            applyWordSelection(field, word);
    }

    /** Задержка повторной установки слова, мс: EDT иногда возвращает «выделить всё» уже после нашей установки. */
    private static final int WORD_REASSERT_DELAY_MS = 150;

    /**
     * Повторная установка выделения слова через {@code timerExec}: штатная обработка EDT
     * иногда возвращает «выделить всё» уже после нашей установки (например, на отпускании
     * кнопки двойного клика). Переставляем только знакомую сигнатуру «выделить всё» и
     * только если текст не менялся и кнопка мыши не нажата (иначе идёт протяжка выделения).
     */
    private static void scheduleWordReassert(Control field, String content, Point word,
        Supplier<Point> getSelection, SelectionSetter setter)
    {
        field.getDisplay().timerExec(WORD_REASSERT_DELAY_MS, () ->
        {
            if (field.isDisposed())
                return;
            try
            {
                Point sel = getSelection.get();
                if (sel == null || sel.equals(word))
                    return;
                boolean leftDown = (OS.GetKeyState(OS.VK_LBUTTON) & 0x8000) != 0;
                if (leftDown || !content.equals(textOf(field)))
                    return;
                if (sel.x != 0 || sel.y != content.length())
                    return; // не «выделить всё» — возможно, действие пользователя, не трогаем
                setter.select(word.x, word.y);
            }
            catch (Throwable ignored)
            {
            }
        });
    }

    private static String textOf(Control field)
    {
        if (field instanceof Text text)
            return text.getText();
        if (field instanceof Combo combo)
            return combo.getText();
        return ""; //$NON-NLS-1$
    }

    /**
     * Смещение символа под указателем в момент события. SWT не даёт координатного API для
     * {@link Text} и {@link Combo}, а каретка ненадёжна (EDT до двойного клика выделяет
     * весь текст и ставит каретку в конец), поэтому позиция указателя берётся из Win32
     * {@code GetCursorPos} (физические пиксели — без пересчёта DPI координат события)
     * и переводится в клиентские координаты поля ввода; символ — {@code EM_CHARFROMPOS}.
     * Для {@link Combo} координаты отображаются в его дочернее поле ввода.
     *
     * @return неотрицательное смещение символа или {@code -1}, если определить не удалось
     */
    static int charOffsetAtCursor(Control field)
    {
        if (field == null || field.isDisposed())
            return -1;
        try
        {
            long hwnd = field.handle;
            long hwndEdit = field instanceof Combo
                ? OS.GetDlgItem(hwnd, 1001 /* Combo.CBID_EDIT */)
                : hwnd;
            if (hwndEdit == 0)
                return -1;

            POINT pt = new POINT();
            if (!OS.GetCursorPos(pt))
                return -1;
            OS.MapWindowPoints(0, hwndEdit, pt, 1); // экран -> клиент поля ввода

            long lParam = ((long)pt.y << 16) | (pt.x & 0xFFFFL);
            return OS.LOWORD(OS.SendMessage(hwndEdit, OS.EM_CHARFROMPOS, 0, lParam));
        }
        catch (Throwable t)
        {
            return -1;
        }
    }

    /**
     * Границы слова-идентификатора у каретки: каретка внутри слова или на его краю
     * (сначала проверяется символ справа, затем слева).
     *
     * @return {@code x} — начало, {@code y} — конец слова, либо {@code null}, если у каретки
     *         только разделители
     */
    static Point identifierWordAt(String content, int caret)
    {
        if (content == null || content.isEmpty())
            return null;

        int len = content.length();
        int pos = Math.max(0, Math.min(caret, len));
        if (pos < len && isIdentifierChar(content.charAt(pos)))
        {
            int start = pos;
            while (start > 0 && isIdentifierChar(content.charAt(start - 1)))
                start--;
            int end = pos;
            while (end < len && isIdentifierChar(content.charAt(end)))
                end++;
            return new Point(start, end);
        }
        if (pos > 0 && isIdentifierChar(content.charAt(pos - 1)))
        {
            int start = pos - 1;
            while (start > 0 && isIdentifierChar(content.charAt(start - 1)))
                start--;
            return new Point(start, pos);
        }
        return null;
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
