package tormozit;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Region;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.menu.AbstractBslBracketSearchHandler;
import com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil;

/**
 * Команды «Перейти по операторным скобкам вперёд/назад» (Ctrl+] / Ctrl+[, с выделением —
 * Ctrl+Shift+] / Ctrl+Shift+[) внутри многострочного текстового литерала переходят к его
 * границам: вперёд — к закрывающей кавычке, назад — к открывающей. Кавычка при этом выделяется,
 * как штатная команда выделяет найденную операторную скобку.
 *
 * <p>Стоя на самой граничной кавычке, обе команды идут к парной кавычке — направление задаёт
 * кавычка, а не команда. Это единообразно со штатным поведением на круглых и квадратных
 * скобках: {@code getBracket} выбирает направление обхода по символу под кареткой, флаг
 * «вперёд/назад» туда не передаётся вовсе.
 *
 * <p>Штатные обработчики EDT внутри литерала ищут ближайшие операторные скобки, то есть уводят
 * каретку из литерала целиком — к {@code КонецЕсли}, {@code КонецПроцедуры} и т.п. Для длинного
 * многострочного текста (запрос, макет, текст сообщения) полезнее сначала дойти до его края.
 *
 * <p>Обработчик один на все четыре команды: направление и режим выделения определяются по
 * идентификатору команды. Вне многострочного литерала работа передаётся штатной реализации
 * (наш класс наследует {@code AbstractBslBracketSearchHandler}), поэтому обычное поведение
 * команд не меняется.
 *
 * <p>{@code activeWhen} в {@code plugin.xml} обязателен и обязан быть «сильнее» штатного:
 * у обработчиков EDT условие построено на переменной {@code activeEditor}
 * ({@code ISources.ACTIVE_EDITOR} = 65536), поэтому наше условие использует {@code activePart}
 * ({@code ISources.ACTIVE_PART} = 1048576) — иначе два обработчика одной команды дают конфликт
 * вместо замены.
 */
public final class BslBracketSearchHandler extends AbstractBslBracketSearchHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        String commandId = event.getCommand() == null ? "" : event.getCommand().getId(); //$NON-NLS-1$
        boolean forward = commandId.contains("Forward"); //$NON-NLS-1$
        boolean withSelection = commandId.contains("WithSelection"); //$NON-NLS-1$

        if (jumpToStringLiteralBound(event, forward, withSelection))
            return null;

        if (withSelection)
            executeBracketSearchWithSelection(event, forward);
        else
            executeBracketSearch(event, forward);
        return null;
    }

    /**
     * Переставляет каретку на границу многострочного литерала, внутри которого она находится.
     *
     * @return {@code false}, если каретка не внутри многострочного литерала — тогда команду
     *         должен выполнить штатный обработчик.
     */
    private static boolean jumpToStringLiteralBound(ExecutionEvent event, boolean forward,
        boolean withSelection)
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        XtextEditor editor = part == null ? null : BslHandlerUtil.extractXtextEditor(part);
        ITextViewer viewer = editor == null ? null : BslHandlerUtil.getTextViewer(editor);
        IXtextDocument document = editor == null ? null : editor.getDocument();
        if (viewer == null || document == null)
            return false;

        // Координаты модели: getSelectedRange даёт модельные офсеты, каретка виджета со
        // свёртками выше неё им не равна (см. AGENTS.md, «Каретка редактора: виджет ≠ модель»).
        Point selection = viewer.getSelectedRange();
        IRegion literal = findMultilineStringLiteral(document, selection.x);
        if (literal == null)
            return false;

        int openQuote = literal.getOffset();
        int closeQuote = literal.getOffset() + literal.getLength() - 1;
        /*
         * Стоя на самой кавычке, направление задаёт кавычка, а не команда: с открывающей всегда
         * к закрывающей, с закрывающей — к открывающей. Так же ведут себя штатные команды на
         * круглых и квадратных скобках: AbstractBslBracketSearchHandler.getBracket выбирает
         * направление обхода по символу под кареткой, а флаг «вперёд/назад» туда даже не
         * передаётся (передаётся withSelection).
         */
        int target;
        if (selection.x == openQuote)
            target = closeQuote;
        else if (selection.x == closeQuote)
            target = openQuote;
        else
            target = forward ? closeQuote : openQuote;
        int offset;
        int length;
        if (withSelection)
        {
            // Как в штатной реализации: диапазон от края исходного выделения до цели,
            // длина всегда неотрицательна; граничная кавычка входит в выделение.
            offset = Math.min(selection.x, target);
            length = Math.max(selection.x + selection.y, target + 1) - offset;
        }
        else
        {
            offset = target;
            length = 1; // выделяется сама кавычка — как штатная команда выделяет скобку
        }

        if (viewer instanceof ITextViewerExtension5 extension)
        {
            if (!extension.exposeModelRange(new Region(offset, length)))
                viewer.resetVisibleRegion();
        }
        else
        {
            viewer.resetVisibleRegion();
        }
        viewer.setSelectedRange(offset, length);
        viewer.revealRange(offset, length);
        return true;
    }

    /**
     * Область многострочного текстового литерала, внутри которого стоит каретка: от открывающей
     * кавычки до закрывающей включительно. {@code null}, если каретка вне литерала или литерал
     * однострочный (для него штатное поведение команд не меняется).
     *
     * <p>Разбор текстовый, а не по AST: команда должна работать и в модуле с синтаксическими
     * ошибками, и до окончания фоновой сборки модели. Учитываются удвоенные кавычки внутри
     * литерала ({@code ""}) и комментарии {@code //} вне литерала.
     *
     * <p>Каретка считается внутри литерала, когда она не левее открывающей кавычки и не правее
     * закрывающей — включая позиции на самих кавычках, иначе с выделенной граничной кавычкой
     * не найти парную. Уход наружу решается уже вызывающим кодом, по направлению команды.
     */
    private static IRegion findMultilineStringLiteral(IXtextDocument document, int caret)
    {
        try
        {
            int caretLine = document.getLineOfOffset(caret);
            int startLine = firstNonContinuationLine(document, caretLine);
            int lastLine = document.getNumberOfLines() - 1;

            boolean inString = false;
            int stringStart = -1;
            for (int line = startLine; line <= lastLine; line++)
            {
                IRegion lineInfo = document.getLineInformation(line);
                String text = document.get(lineInfo.getOffset(), lineInfo.getLength());
                if (inString && line > startLine && !text.stripLeading().startsWith("|")) //$NON-NLS-1$
                    return null; // литерал не закрыт корректно — не наш случай

                int i = 0;
                while (i < text.length())
                {
                    char c = text.charAt(i);
                    if (inString)
                    {
                        if (c != '"')
                        {
                            i++;
                            continue;
                        }
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"')
                        {
                            i += 2; // удвоенная кавычка — часть содержимого
                            continue;
                        }
                        int closeOffset = lineInfo.getOffset() + i;
                        if (caret >= stringStart && caret <= closeOffset)
                        {
                            return document.getLineOfOffset(stringStart) == document
                                .getLineOfOffset(closeOffset)
                                    ? null // однострочный литерал
                                    : new Region(stringStart, closeOffset - stringStart + 1);
                        }
                        if (closeOffset >= caret)
                            return null; // каретка осталась позади, вне литерала
                        inString = false;
                        i++;
                    }
                    else if (c == '"')
                    {
                        inString = true;
                        stringStart = lineInfo.getOffset() + i;
                        i++;
                    }
                    else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/')
                    {
                        i = text.length(); // комментарий до конца строки
                    }
                    else
                    {
                        i++;
                    }
                }

                if (!inString && lineInfo.getOffset() + lineInfo.getLength() >= caret)
                    return null; // строка каретки кончилась вне литерала
            }
            return null;
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    /**
     * Строка, с которой начинается разбор: первая при движении вверх, не являющаяся
     * продолжением многострочного литерала (не начинается с {@code |}).
     */
    private static int firstNonContinuationLine(IXtextDocument document, int caretLine)
        throws BadLocationException
    {
        for (int line = caretLine; line > 0; line--)
        {
            IRegion lineInfo = document.getLineInformation(line);
            String text = document.get(lineInfo.getOffset(), lineInfo.getLength());
            if (!text.stripLeading().startsWith("|")) //$NON-NLS-1$
                return line;
        }
        return 0;
    }
}
