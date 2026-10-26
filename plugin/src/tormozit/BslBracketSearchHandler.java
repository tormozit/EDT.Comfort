package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Region;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.menu.AbstractBslBracketSearchHandler;
import com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.IfPreprocessor;
import com._1c.g5.v8.dt.bsl.model.PreprocessorConditional;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;

/**
 * Команды «Перейти по операторным скобкам вперёд/назад» (Ctrl+] / Ctrl+[, с выделением —
 * Ctrl+Shift+] / Ctrl+Shift+[) внутри многострочного текстового литерала переходят к его
 * границам: вперёд — к закрывающей кавычке, назад — к открывающей. Кавычка при этом выделяется,
 * как штатная команда выделяет найденную операторную скобку.
 * В тексте запроса при включённой его раскраске скобка под кареткой сначала проверяется
 * по готовому дереву QL: переход идёт к её паре, включая скобки вложенного запроса.
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
 * идентификатору команды. Дополнительно обрабатывается {@code IfPreprocessor} между методами:
 * этот тип отсутствует в таблицах скобок EDT. Остальные конструкции передаются штатной
 * реализации ({@code AbstractBslBracketSearchHandler}).
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

        if (QueryBracketSupport.handleNativeCommand(withSelection))
            return null;
        if (jumpToStringLiteralBound(event, forward, withSelection))
            return null;
        if (jumpToMethodPreprocessor(event, forward, withSelection))
            return null;

        if (withSelection)
            executeBracketSearchWithSelection(event, forward);
        else
            executeBracketSearch(event, forward);
        return null;
    }

    /**
     * PreprocessorMethodStatementIf создаёт базовый IfPreprocessor, которого нет в
     * OPEN/MIDDLE/CLOSE_BRACKETS EDT. Границы берём по терминалам грамматики и их владельцу,
     * как штатный isTheSameObject: вложенные директивы и itemAfter не принадлежат нашей паре.
     */
    private static boolean jumpToMethodPreprocessor(ExecutionEvent event, boolean forward,
        boolean withSelection)
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        XtextEditor editor = part == null ? null : BslHandlerUtil.extractXtextEditor(part);
        ITextViewer viewer = editor == null ? null : BslHandlerUtil.getTextViewer(editor);
        IXtextDocument document = editor == null ? null : editor.getDocument();
        if (viewer == null || document == null)
            return false;
        Point selection = viewer.getSelectedRange();
        IRegion target = document.readOnly(resource ->
        {
            if (resource == null || resource.getParseResult() == null)
                return null;
            var root = resource.getParseResult().getRootNode();
            var leaf = NodeModelUtils.findLeafNodeAtOffset(root, selection.x);
            if (leaf == null)
                return null;
            // Круглые/квадратные скобки штатный обработчик проверяет раньше логических.
            String text = leaf.getText();
            int relative = selection.x - leaf.getOffset();
            if (relative >= 0 && relative < text.length() && "()[]".indexOf(text.charAt(relative)) >= 0) //$NON-NLS-1$
                return null;
            EObject object = NodeModelUtils.findActualSemanticObjectFor(leaf);
            while (object != null && !(object instanceof IfPreprocessor)
                && !(object instanceof RegionPreprocessor) && !OPEN_BRACKETS.containsKey(object.eClass()))
                object = object.eContainer();
            if (object == null || object.eClass() != BslPackage.Literals.IF_PREPROCESSOR)
                return null;
            var node = NodeModelUtils.findActualNodeFor(object);
            if (node == null)
                return null;
            List<IRegion> brackets = new ArrayList<>();
            for (var candidate : node.getLeafNodes())
            {
                if (candidate.isHidden() || !(candidate.getGrammarElement() instanceof RuleCall call))
                    continue;
                String rule = call.getRule().getName();
                if (!"BEGIN_IFPREPROCESSOR".equals(rule) && !"END_IFPREPROCESSOR".equals(rule) //$NON-NLS-1$ //$NON-NLS-2$
                    && !"ELSEIF_PREPROCESSOR".equals(rule) && !"ELSE_PREPROCESSOR".equals(rule)) //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                EObject owner = NodeModelUtils.findActualSemanticObjectFor(candidate);
                if (owner instanceof PreprocessorConditional)
                    owner = owner.eContainer();
                if (owner == object)
                    brackets.add(new Region(candidate.getOffset(), candidate.getLength()));
            }
            return methodPreprocessorRange(brackets, selection, forward, withSelection);
        });
        if (target == null)
            return false;
        if (viewer instanceof ITextViewerExtension5 extension)
            extension.exposeModelRange(target);
        else
            viewer.resetVisibleRegion();
        viewer.setSelectedRange(target.getOffset(), target.getLength());
        viewer.revealRange(target.getOffset(), target.getLength());
        return true;
    }

    /** Направление, циклический обход и расширение выделения как в getNextProcedureBracket EDT. */
    private static IRegion methodPreprocessorRange(List<IRegion> brackets, Point selection,
        boolean forward, boolean withSelection)
    {
        IRegion next = nextMethodPreprocessorBracket(brackets, selection.x, forward);
        if (next == null || !withSelection)
            return next;
        IRegion first = brackets.get(0);
        IRegion last = brackets.get(brackets.size() - 1);
        boolean wasLast = next == last;
        IRegion other;
        if (forward)
        {
            int initialOffset = next.getOffset();
            int count = 0;
            while (next != null && count < brackets.size() && next.getOffset() >= selection.x
                && next.getOffset() + next.getLength() <= selection.x + selection.y)
            {
                count++;
                next = nextMethodPreprocessorBracket(brackets, next.getOffset() + 1, true);
            }
            if (wasLast && next != last)
            {
                next = first;
                other = last;
            }
            else
                other = nextMethodPreprocessorBracket(brackets, initialOffset, false);
        }
        else if (wasLast)
        {
            next = first;
            other = last;
        }
        else
        {
            int initialEnd = next.getOffset() + next.getLength();
            other = nextMethodPreprocessorBracket(brackets, initialEnd, true);
            IRegion previous = null;
            int count = 0;
            while (other != null && count < brackets.size() && other.getOffset() >= selection.x
                && other.getOffset() + other.getLength() <= selection.x + selection.y)
            {
                count++;
                previous = other;
                other = nextMethodPreprocessorBracket(brackets, other.getOffset() + other.getLength(), true);
            }
            if (previous != null)
                other = previous;
        }
        if (next == null || other == null)
            return null;
        int start = Math.min(next.getOffset(), other.getOffset());
        int end = Math.max(next.getOffset() + next.getLength(), other.getOffset() + other.getLength());
        return new Region(start, end - start);
    }

    private static IRegion nextMethodPreprocessorBracket(List<IRegion> brackets, int offset, boolean forward)
    {
        if (brackets.size() < 2)
            return null;
        for (int i = forward ? 0 : brackets.size() - 1; i >= 0 && i < brackets.size(); i += forward ? 1 : -1)
        {
            IRegion bracket = brackets.get(i);
            if (forward ? offset < bracket.getOffset()
                : offset > bracket.getOffset() + bracket.getLength())
                return bracket;
        }
        return brackets.get(forward ? 0 : brackets.size() - 1);
    }

    /**
     * Выделяет граничную кавычку многострочного литерала, в котором стоит каретка.
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
        IRegion queryPair = BslQueryLiteralSupport.queryBracketPair(document, selection.x);
        if (queryPair != null)
        {
            int target = selection.x == queryPair.getOffset()
                ? queryPair.getOffset() + queryPair.getLength() - 1 : queryPair.getOffset();
            QueryBracketSupport.selectBracket(viewer, selection, target, withSelection);
            return true;
        }
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
        QueryBracketSupport.selectBracket(viewer, selection, target, withSelection);
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
