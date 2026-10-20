package tormozit;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.VerifyEvent;

/**
 * Порт OR-условия {@code ОбработкаОбъект.ирКлсПолеТекстаПрограммы.ПриНажатииКлавишиАвтодополнение}.
 * Без проверок {@code ЯзыкПрограммы} (см. закомментированные ветки ниже).
 */
public final class CompletionAutoOpenTrigger
{
    private CompletionAutoOpenTrigger() {}

    /**
     * @return ветка триггера ({@code printable}, {@code dot}, …) или {@code null} если не fetch
     */
    public static String diagnoseFetch(IDocument doc, char insertedChar, int insertOffset,
                                       int caretAfter, boolean popupWasOpen, int stateMask)
    {
        if (doc == null || insertedChar == 0 || caretAfter < 0)
            return null;
        if (popupWasOpen && insertedChar != '.' && insertedChar != ' ')
            return null;

        String linePrefix = BslAssistSourceHeuristics.linePrefixToCaret(
            doc, caretAfter, insertedChar, insertOffset);
        if (BslAssistSourceHeuristics.isInsideLineComment(linePrefix))
        {
            // Пока список готовится после «см. », ввод имени не должен отменять открытие.
            int nameStart = linePrefix.length();
            while (nameStart > 0)
            {
                char c = linePrefix.charAt(nameStart - 1);
                if (!Character.isLetterOrDigit(c) && c != '_')
                    break;
                nameStart--;
            }
            if ((insertedChar == ' ' || isPrintableKey(stateMask, insertedChar)) && nameStart >= 4
                && linePrefix.regionMatches(true, nameStart - 4, "см. ", 0, 4)) //$NON-NLS-1$
            {
                int start = nameStart - 4;
                if (start == 0 || (!Character.isLetterOrDigit(linePrefix.charAt(start - 1))
                    && linePrefix.charAt(start - 1) != '_'))
                    return "commentLink"; //$NON-NLS-1$
            }
            if (popupWasOpen && insertedChar != '.')
                return null;
            // Остальные триггеры комментария — member-access.
            if (insertedChar == '.')
            {
                if (!BslAssistSourceHeuristics.lastTwoCharsNotEqual(
                    doc, caretAfter, insertedChar, insertOffset))
                    return null;
                String parent = BslAssistSourceHeuristics.parentContextBeforeDot(
                    doc, caretAfter, insertedChar, insertOffset);
                if (parent != null && !parent.isEmpty())
                    return "dot";
                return null;
            }
            if (isPrintableKey(stateMask, insertedChar))
            {
                if (SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(doc, insertOffset) >= 0)
                    return "printable";
                return null;
            }
            return null;
        }

        if (popupWasOpen && insertedChar != '.')
            return null;
        if (isPrintableKey(stateMask, insertedChar))
            return "printable"; //$NON-NLS-1$

        if (insertedChar == '_')
            return "underscore"; //$NON-NLS-1$

        if (insertedChar == '&' || insertedChar == '~' || insertedChar == '#')
        {
            if (BslAssistSourceHeuristics.lastTwoCharsNotEqual(
                doc, caretAfter, insertedChar, insertOffset))
                return "symbol"; //$NON-NLS-1$
            return null;
        }

        if (insertedChar == '.')
        {
            if (!BslAssistSourceHeuristics.lastTwoCharsNotEqual(
                doc, caretAfter, insertedChar, insertOffset))
                return null;
            String parent = BslAssistSourceHeuristics.parentContextBeforeDot(
                doc, caretAfter, insertedChar, insertOffset);
            if (parent != null && !parent.isEmpty())
                return "dot"; //$NON-NLS-1$
            return null;
        }

        if (insertedChar == '=' || insertedChar == ' ')
        {
            if (BslAssistSourceHeuristics.lastTwoCharsNotEqual(
                doc, caretAfter, insertedChar, insertOffset))
                return insertedChar == '=' ? "equals" : "space"; //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        if (insertedChar == '"')
        {
            if (!BslAssistSourceHeuristics.lastTwoCharsNotEqual(doc, caretAfter, insertedChar, insertOffset))  
                return null;
            if (BslAssistSourceHeuristics.isInsideStringLiteral(linePrefix))
                return "quote"; //$NON-NLS-1$
        }

        return null;
    }

    public static boolean shouldFetch(IDocument doc, VerifyEvent event, char insertedChar,
                                      int caretAfter, boolean popupWasOpen)
    {
        if (doc == null || event == null || insertedChar == 0 || caretAfter < 0)
            return false;
        int stateMask = event.stateMask;
        return diagnoseFetch(doc, insertedChar, event.start, caretAfter, popupWasOpen,
            stateMask) != null;
    }

    public static boolean shouldFetchDocument(IDocument doc, char insertedChar, int insertOffset,
                                              int caretAfter, boolean popupWasOpen)
    {
        return diagnoseFetch(doc, insertedChar, insertOffset, caretAfter, popupWasOpen, 0) != null;
    }

    private static boolean isPrintableKey(int stateMask, char insertedChar)
    {
        if ((stateMask & SWT.CTRL) != 0 || (stateMask & SWT.ALT) != 0)
            return false;
        if (insertedChar == '_')
            return true;
        return Character.isLetter(insertedChar);
    }
}
