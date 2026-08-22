package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Единое форматирование подсказок (тултипов) плагина: длинная строка разбивается по ширине
 * {@link #MAX_WIDTH_PX}.
 *
 * <p>Нативная подсказка Windows переносов сама не делает — подсказка в несколько предложений
 * растягивается на весь экран и читается плохо. Поэтому любой текст подсказки, который плагин
 * ставит сам ({@code setToolTipText}), пропускается через {@link #wrap}: разбиение идёт по
 * пробелам, уже имеющиеся переносы сохраняются, ширина считается ФАКТИЧЕСКИМ шрифтом контрола,
 * а не по числу символов.
 *
 * <p>Своих вариантов разбиения заводить не нужно: ширина у всех подсказок плагина одна.
 *
 * <p><b>Исключение</b> — подсказки с фрагментами кода (модуль, запрос, стек вызовов): там перенос
 * ломает форматирование, и такие подсказки через этот класс не пропускаются.
 */
final class TooltipText
{
    /** Максимальная ширина строки подсказки, px. */
    static final int MAX_WIDTH_PX = 500;

    private static final int TEXT_FLAGS = SWT.DRAW_DELIMITER | SWT.DRAW_TAB | SWT.DRAW_TRANSPARENT;

    private TooltipText()
    {
    }

    /** Разбивает текст подсказки шрифтом контрола; {@code null}/пустой текст возвращается как есть. */
    static String wrap(Control control, String text)
    {
        if (text == null || text.isEmpty() || control == null || control.isDisposed())
            return text;
        return wrap(control.getDisplay(), control.getFont(), text);
    }

    /** Разбивает текст подсказки заданным шрифтом (для виджетов без собственного контрола). */
    static String wrap(Display display, Font font, String text)
    {
        if (text == null || text.isEmpty() || display == null || display.isDisposed())
            return text;
        GC gc = new GC(display);
        try
        {
            if (font != null && !font.isDisposed())
                gc.setFont(font);
            return wrapToWidth(gc, text, MAX_WIDTH_PX);
        }
        finally
        {
            gc.dispose();
        }
    }

    private static String wrapToWidth(GC gc, String text, int maxWidth)
    {
        StringBuilder result = new StringBuilder(text.length() + 16);
        for (String paragraph : text.split("\n", -1)) //$NON-NLS-1$
        {
            if (paragraph.isEmpty())
            {
                if (!result.isEmpty())
                    result.append('\n');
                continue;
            }
            wrapParagraph(gc, paragraph, maxWidth, result);
        }
        return result.toString();
    }

    private static void wrapParagraph(GC gc, String paragraph, int maxWidth, StringBuilder out)
    {
        String remaining = paragraph.trim();
        while (!remaining.isEmpty())
        {
            if (textWidth(gc, remaining) <= maxWidth)
            {
                appendLine(out, remaining);
                return;
            }
            int breakAt = maxPrefixWidth(gc, remaining, maxWidth);
            if (breakAt <= 0)
                breakAt = 1;
            int space = remaining.lastIndexOf(' ', breakAt - 1);
            if (space > 0)
                breakAt = space;
            appendLine(out, remaining.substring(0, breakAt).trim());
            remaining = remaining.substring(breakAt).trim();
        }
    }

    /** Наибольшее число символов текста, умещающееся в {@code maxWidth} (двоичный поиск). */
    private static int maxPrefixWidth(GC gc, String text, int maxWidth)
    {
        int lo = 1;
        int hi = text.length();
        while (lo < hi)
        {
            int mid = (lo + hi + 1) / 2;
            if (textWidth(gc, text.substring(0, mid)) <= maxWidth)
                lo = mid;
            else
                hi = mid - 1;
        }
        return lo;
    }

    private static int textWidth(GC gc, String text)
    {
        return gc.textExtent(text, TEXT_FLAGS).x;
    }

    private static void appendLine(StringBuilder out, String line)
    {
        if (line.isEmpty())
            return;
        if (!out.isEmpty())
            out.append('\n');
        out.append(line);
    }
}
