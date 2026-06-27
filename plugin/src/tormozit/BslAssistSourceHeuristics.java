package tormozit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.VerifyEvent;

/**
 * Быстрые строковые эвристики для триггера автооткрытия assist (без AST/Xtext).
 * Порты функций ИР {@code ирОбщий} и {@code мРодительскийКонтекст}.
 */
public final class BslAssistSourceHeuristics
{
    private BslAssistSourceHeuristics() {}

    /**
     * Порт {@code ирОбщий.ЛиВнутриТекстовогоЛитералаЛкс}: нечётное число {@code "}
     * в префиксе строки до каретки (с учётом {@code |} в начале trim-left).
     */
    public static boolean isInsideStringLiteral(String linePrefix)
    {
        if (linePrefix == null)
            return false;
        String trimmedLeft = linePrefix.stripLeading();
        int pipeAdjust = trimmedLeft.startsWith("|") ? 1 : 0; //$NON-NLS-1$
        int quotes = 0;
        for (int i = 0; i < linePrefix.length(); i++)
        {
            if (linePrefix.charAt(i) == '"')
                quotes++;
        }
        return (pipeAdjust + quotes) % 2 == 1;
    }

    /**
     * Порт {@code ирОбщий.ЛиВнутриКомментарияЛкс}: {@code //} в префиксе строки,
     * если фрагмент до {@code //} не внутри строкового литерала.
     */
    public static boolean isInsideLineComment(String linePrefix)
    {
        if (linePrefix == null || linePrefix.isEmpty())
            return false;
        int slash = firstFragment(linePrefix, "//", false); //$NON-NLS-1$
        if (slash < 0)
            return false;
        String beforeSlash = linePrefix.substring(0, slash);
        return !isInsideStringLiteral(beforeSlash);
    }

    /**
     * Порт {@code ирОбщий.ПервыйФрагментЛкс(Строка, Разделитель, ЛиБратьГраницуЕслиРазделительНеНайден)}.
     */
    public static int firstFragment(String text, String separator, boolean takeEndIfMissing)
    {
        if (text == null || separator == null || separator.isEmpty())
            return takeEndIfMissing && text != null ? text.length() : -1;
        int idx = text.indexOf(separator);
        if (idx >= 0)
            return idx;
        return takeEndIfMissing ? text.length() : -1;
    }

    /**
     * Префикс текущей строки до каретки; при вставке символа на {@code pendingOffset}
     * включает {@code pendingChar}.
     */
    public static String linePrefixToCaret(IDocument doc, int caretAfter, char pendingChar,
                                           int pendingOffset)
    {
        if (doc == null || caretAfter < 0)
            return ""; //$NON-NLS-1$
        try
        {
            int line = doc.getLineOfOffset(Math.min(caretAfter, doc.getLength()));
            int lineStart = doc.getLineOffset(line);
            int lineEnd = lineStart + doc.getLineLength(line);
            int end = Math.min(caretAfter, lineEnd);
            if (pendingOffset >= lineStart && pendingOffset < end && pendingChar != 0)
            {
                StringBuilder sb = new StringBuilder(end - lineStart + 1);
                if (pendingOffset > lineStart)
                    sb.append(doc.get(lineStart, pendingOffset - lineStart));
                sb.append(pendingChar);
//                if (pendingOffset < end)
//                    sb.append(doc.get(pendingOffset, end - pendingOffset));
                return sb.toString();
            }
            return doc.get(lineStart, end - lineStart);
        }
        catch (BadLocationException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Порт {@code ирОбщий.ЛиНажатаПечатнаяКлавишаЛкс}: буква или {@code _}, не цифра,
     * без Ctrl/Alt.
     */
    public static boolean isPrintableKey(VerifyEvent event, char insertedChar)
    {
        if (event == null || insertedChar == 0)
            return false;
        int sm = event.stateMask;
        if ((sm & SWT.CTRL) != 0 || (sm & SWT.ALT) != 0)
            return false;
        if (insertedChar == '_')
            return true;
        return Character.isLetter(insertedChar);
    }

    /** Два символа слева от каретки (после вставки) различаются; &lt;2 символов → {@code true}. */
    public static boolean lastTwoCharsNotEqual(IDocument doc, int caretAfter, char pendingChar,
                                               int pendingOffset)
    {
        if (caretAfter < 2)
            return true;
        char last = charAtSimulated(doc, caretAfter - 1, pendingChar, pendingOffset);
        char prev = charAtSimulated(doc, caretAfter - 2, pendingChar, pendingOffset);
        return prev != last;
    }

    /**
     * Порт {@code мРодительскийКонтекст}: текст выражения слева от ближайшей {@code .}
     * перед кареткой (многострочный scan до {@code ;} на нулевой глубине скобок).
     */
    public static String parentContextBeforeDot(IDocument doc, int caretAfter, char pendingChar,
                                                int pendingOffset)
    {
        if (doc == null || caretAfter <= 0)
            return ""; //$NON-NLS-1$
        int dotIndex = -1;
        for (int i = caretAfter - 1; i >= 0; i--)
        {
            char c = charAtSimulated(doc, i, pendingChar, pendingOffset);
            if (c == '.')
            {
                dotIndex = i;
                break;
            }
        }
        if (dotIndex < 0)
            return ""; //$NON-NLS-1$
        int exprStart = findExpressionStart(doc, dotIndex, pendingChar, pendingOffset);
        if (exprStart < 0 || exprStart >= dotIndex)
            return ""; //$NON-NLS-1$
        try
        {
            if (pendingChar != 0 && pendingOffset >= exprStart && pendingOffset < dotIndex)
            {
                StringBuilder sb = new StringBuilder(dotIndex - exprStart);
                if (pendingOffset > exprStart)
                    sb.append(doc.get(exprStart, pendingOffset - exprStart));
                sb.append(pendingChar);
                if (pendingOffset + 1 < dotIndex)
                    sb.append(doc.get(pendingOffset, dotIndex - pendingOffset - 1));
                return sb.toString().trim();
            }
            return doc.get(exprStart, dotIndex - exprStart).trim();
        }
        catch (BadLocationException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    private static int findExpressionStart(IDocument doc, int beforeDot, char pendingChar,
                                           int pendingOffset)
    {
        int paren = 0;
        int bracket = 0;
        for (int i = beforeDot - 1; i >= 0; i--)
        {
            char c = charAtSimulated(doc, i, pendingChar, pendingOffset);
            switch (c)
            {
                case ')':
                    paren++;
                    break;
                case '(':
                    if (paren > 0)
                        paren--;
                    else if (bracket == 0)
                        return i + 1;
                    break;
                case ']':
                    bracket++;
                    break;
                case '[':
                    if (bracket > 0)
                        bracket--;
                    break;
                case ';':
                    if (paren == 0 && bracket == 0)
                        return i + 1;
                    break;
                default:
                    break;
            }
        }
        return 0;
    }

    private static char charAtSimulated(IDocument doc, int offset, char pendingChar,
                                        int pendingOffset)
    {
        if (pendingChar != 0 && offset == pendingOffset)
            return pendingChar;
        try
        {
            return doc.getChar(offset);
        }
        catch (BadLocationException e)
        {
            return '\0';
        }
    }
}
