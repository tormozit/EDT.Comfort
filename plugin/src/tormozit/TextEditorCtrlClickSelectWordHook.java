package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;

/**
 * Ctrl+клик в текстовых полях выделяет слово под курсором — как двойной клик, — если это
 * слово ещё не выделено. Пока слово не выделено, Ctrl над ним не должен ничего менять
 * внешне: ни подчёркивания гиперссылки, ни указателя-руки. Как только выделение в точности
 * совпало с границами слова, всё работает штатно — рисуется гиперссылка, указатель
 * принимает форму руки, клик выполняет переход.
 *
 * <p><b>Как это сделано.</b> Вместо гашения событий у {@code StyledText} снимается только
 * бит {@link SWT#MOD1} в {@code stateMask} события. {@code HyperlinkManager} на входе и
 * {@code mouseMove}, и {@code mouseDown} проверяет {@code isRegisteredStateMask(stateMask)}
 * и при несовпадении вызывает {@code deactivate()} — гиперссылка прячется, указатель
 * возвращается, а {@code fActive} становится {@code false}, из-за чего его {@code mouseUp}
 * (единственное место, где вызывается {@code fActiveHyperlinks[0].open()}) уже ничего не
 * открывает. Сами события при этом доходят до {@code StyledText} целиком.
 *
 * <p>Гасить {@link SWT#MouseUp} нельзя: {@code StyledText.handleMouseUp} сбрасывает
 * {@code clickCount}, и без этого сброса каждое последующее движение мыши воспринимается
 * как протяжка выделения — каретка «ездит» за указателем без нажатой кнопки.
 *
 * <p>Решение о выделении принимается на {@link SWT#MouseDown}: только там ещё видно
 * выделение, существовавшее до клика — штатный обработчик {@code StyledText} снимает его
 * раньше, чем управление дойдёт до {@link SWT#MouseUp}. Выделение слова ставится после
 * {@code MouseUp} через {@code asyncExec}, чтобы не спорить со штатной обработкой клика.
 *
 * <p>После выделения, пока Ctrl ещё зажат, шлётся синтетический {@link SWT#MouseMove}:
 * {@code HyperlinkManager} иначе ждёт реального движения указателя, чтобы нарисовать
 * ссылку и сменить курсор на руку.
 *
 * <p>На {@code mouseMove} гиперссылка разрешена только если слово под указателем уже
 * выделено. Смещение считается как у менеджера ({@code getCursorLocation} + шаг назад
 * из левой половины глифа) — иначе на {@code (} между слитными фрагментами подчёркивается
 * соседний идентификатор.
 */
public final class TextEditorCtrlClickSelectWordHook implements IStartup
{
    /** Ключ SWT-данных: границы слова, которое надо выделить после {@link SWT#MouseUp}. */
    private static final String PENDING_WORD_KEY = "tormozit.ctrlClickPendingWord"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseMove, TextEditorCtrlClickSelectWordHook::handleMouseMove);
        display.addFilter(SWT.MouseDown, TextEditorCtrlClickSelectWordHook::handleMouseDown);
        display.addFilter(SWT.MouseUp, TextEditorCtrlClickSelectWordHook::handleMouseUp);
    }

    /**
     * Гиперссылка и рука — только пока указатель над уже выделенным словом. Иначе
     * {@code HyperlinkManager} на границе ({@code (} между слитными фрагментами) берёт
     * соседний идентификатор: он считает смещение через {@code getCursorLocation} и шаг
     * назад из левой половины глифа, а не {@code event.x/y}.
     */
    private static void handleMouseMove(Event event)
    {
        if (!(event.widget instanceof StyledText text) || text.isDisposed())
            return;
        if (!ComfortSettings.isCtrlClickSelectWordEnabled())
            return;
        if (!isCtrlOnly(event.stateMask))
            return;
        if (text.getBlockSelection())
            return;
        if (selectedWordUnderPointer(text))
            return;
        suppressHyperlinkModifier(event);
    }

    private static void handleMouseDown(Event event)
    {
        if (!(event.widget instanceof StyledText text) || text.isDisposed())
            return;

        text.setData(PENDING_WORD_KEY, null);

        if (event.button != 1)
            return;
        Point word = pendingWordFor(text, event);
        if (word == null)
            return; // слово уже выделено (или его нет) — штатное поведение, переход по ссылке

        text.setData(PENDING_WORD_KEY, word);
        suppressHyperlinkModifier(event);
    }

    private static void handleMouseUp(Event event)
    {
        if (!(event.widget instanceof StyledText text) || text.isDisposed())
            return;

        Object pending = text.getData(PENDING_WORD_KEY);
        text.setData(PENDING_WORD_KEY, null);
        if (event.button != 1 || !(pending instanceof Point word))
            return;

        // После штатной обработки клика: она сама двигает каретку и снимает выделение.
        text.getDisplay().asyncExec(() ->
        {
            if (text.isDisposed())
                return;
            text.setSelectionRange(word.x, word.y - word.x);
            text.showSelection();
            pokeHyperlinkManager(text);
        });
    }

    /**
     * {@code HyperlinkManager} ищет ссылку и меняет курсор только в {@code mouseMove}.
     * После нашего выделения указатель ещё не двигался — шлём то же событие, которое
     * пришло бы при малейшем сдвиге. Координаты берём с реального указателя
     * ({@code getOffsetForCursorLocation} смотрит туда, а не в {@code event.x/y}).
     * {@code stateMask} — Ctrl, иначе менеджер сразу {@code deactivate()}.
     */
    private static void pokeHyperlinkManager(StyledText text)
    {
        if (!isCtrlOnlyPressed())
            return;
        Point loc = text.toControl(text.getDisplay().getCursorLocation());
        Event move = new Event();
        move.x = loc.x;
        move.y = loc.y;
        move.stateMask = SWT.MOD1;
        text.notifyListeners(SWT.MouseMove, move);
    }

    /**
     * Под указателем — то же слово, что выделено. Смещение как у {@code HyperlinkManager}:
     * {@link Display#getCursorLocation()} и шаг назад, если точка в левой половине глифа
     * ({@code JFaceTextUtil.getOffsetForCursorLocation}). На пунктуации ({@code (}, {@code .})
     * — {@code false}: иначе подчёркивается соседний слитно пристыкованный идентификатор.
     */
    private static boolean selectedWordUnderPointer(StyledText text)
    {
        Point loc = text.toControl(text.getDisplay().getCursorLocation());
        int offset = hyperlinkOffsetAt(text, loc.x, loc.y);
        Point word = wordRangeAtOffset(text, offset, false);
        if (word == null)
            return false;
        Point selection = text.getSelection();
        return selection.x == word.x && selection.y == word.y;
    }

    /**
     * Смещение символа под точкой — как {@code JFaceTextUtil.getOffsetForCursorLocation}
     * (без перевода widget→model: мы сравниваем с {@link StyledText#getSelection()}).
     */
    private static int hyperlinkOffsetAt(StyledText text, int x, int y)
    {
        int offset;
        try
        {
            offset = text.getOffsetAtPoint(new Point(x, y));
        }
        catch (IllegalArgumentException ex)
        {
            return -1;
        }
        if (offset < 0)
            return -1;
        try
        {
            Point glyph = text.getLocationAtOffset(offset);
            if (glyph.x > x)
                offset--;
        }
        catch (IllegalArgumentException ex)
        {
            return -1;
        }
        return offset;
    }

    /**
     * Слово под указателем, которое надо выделить вместо перехода по гиперссылке.
     *
     * @return {@code null}, если режим выключен, модификаторы не те, слова под указателем нет
     *         или оно уже выделено ровно по своим границам
     */
    private static Point pendingWordFor(StyledText text, Event event)
    {
        if (!isCtrlOnly(event.stateMask))
            return null;
        if (!ComfortSettings.isCtrlClickSelectWordEnabled())
            return null;
        if (text.getBlockSelection())
            return null;

        Point word = wordRangeAt(text, event.x, event.y);
        if (word == null)
            return null;

        Point selection = text.getSelection();
        if (selection.x == word.x && selection.y == word.y)
            return null;

        return word;
    }

    /**
     * Снимает бит Ctrl у события: {@code HyperlinkManager} перестаёт считать его «своим»
     * и деактивируется. Само событие доходит до {@code StyledText} без изменений поведения —
     * Ctrl при клике и движении мыши штатный виджет не использует.
     */
    private static void suppressHyperlinkModifier(Event event)
    {
        event.stateMask &= ~SWT.MOD1;
    }

    /** Ctrl без Shift и Alt — тот же модификатор, по которому EDT показывает гиперссылку. */
    private static boolean isCtrlOnly(int stateMask)
    {
        return (stateMask & SWT.MOD1) != 0 && (stateMask & (SWT.SHIFT | SWT.ALT)) == 0;
    }

    /** Текущее состояние клавиш (не {@code stateMask} прошлого события): Ctrl без Shift и Alt. */
    private static boolean isCtrlOnlyPressed()
    {
        boolean ctrl = (OS.GetKeyState(OS.VK_CONTROL) & 0x8000) != 0;
        boolean shift = (OS.GetKeyState(OS.VK_SHIFT) & 0x8000) != 0;
        boolean alt = (OS.GetKeyState(OS.VK_MENU) & 0x8000) != 0;
        return ctrl && !shift && !alt;
    }

    /**
     * Границы слова (буквы, цифры, {@code _}) под точкой — как при двойном клике.
     *
     * @return {@code x} — начало, {@code y} — конец слова в смещениях виджета, либо
     *         {@code null}, если под указателем нет слова
     */
    private static Point wordRangeAt(StyledText text, int x, int y)
    {
        int offset;
        try
        {
            offset = text.getOffsetAtPoint(new Point(x, y));
        }
        catch (IllegalArgumentException ex)
        {
            return null; // указатель вне области текста (поля, отступы)
        }
        return wordRangeAtOffset(text, offset, true);
    }

    /**
     * @param includeWordToTheLeft {@code true} — как двойной клик: точка сразу за словом
     *        (на {@code (} после идентификатора) относится к этому слову. Для гиперссылки
     *        {@code false}: иначе {@code (} между слитными фрагментами подчёркивает соседа.
     */
    private static Point wordRangeAtOffset(StyledText text, int offset, boolean includeWordToTheLeft)
    {
        if (offset < 0)
            return null;

        int line;
        try
        {
            line = text.getLineAtOffset(offset);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
        int lineStart = text.getOffsetAtLine(line);
        String lineText = text.getLine(line);
        int rel = offset - lineStart;
        if (rel < 0 || rel > lineText.length())
            return null;

        boolean onWord = rel < lineText.length()
            && IdentifierSelectionSupport.isIdentifierChar(lineText.charAt(rel));
        boolean afterWord = includeWordToTheLeft && rel > 0
            && IdentifierSelectionSupport.isIdentifierChar(lineText.charAt(rel - 1));
        if (!onWord && !afterWord)
            return null;

        int start = rel;
        while (start > 0 && IdentifierSelectionSupport.isIdentifierChar(lineText.charAt(start - 1)))
            start--;
        int end = rel;
        while (end < lineText.length() && IdentifierSelectionSupport.isIdentifierChar(lineText.charAt(end)))
            end++;
        if (end <= start)
            return null;

        return new Point(lineStart + start, lineStart + end);
    }
}
