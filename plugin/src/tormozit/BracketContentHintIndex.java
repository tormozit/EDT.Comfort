package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.ForStatement;
import com._1c.g5.v8.dt.bsl.model.IfPreprocessor;
import com._1c.g5.v8.dt.bsl.model.IfStatement;
import com._1c.g5.v8.dt.bsl.model.LoopStatement;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;
import com._1c.g5.v8.dt.bsl.model.TryExceptStatement;
import com._1c.g5.v8.dt.bsl.model.WhileStatement;

/**
 * Строит индекс блочных конструкций BSL (Процедура/Функция, Если, циклы,
 * Попытка, #Область, #Если) для {@link BracketContentHintHook} — для каждой
 * конструкции запоминает офсеты/строки начала и конца и текст первой строки
 * (содержимое открывающей части), которую нужно показать у закрывающей.
 */
final class BracketContentHintIndex
{
    private static final int MAX_HINT_LENGTH = 100;

    private BracketContentHintIndex()
    {
    }

    /** Одна запись индекса — границы конструкции и предвычисленный текст подсказки. */
    static final class Entry
    {
        final int startLine;
        final int endLine;
        /** Офсет документа сразу после последнего символа закрывающего ключевого слова. */
        final int endOffset;
        final String hintText;

        Entry(int startLine, int endLine, int endOffset, String hintText)
        {
            this.startLine = startLine;
            this.endLine = endLine;
            this.endOffset = endOffset;
            this.hintText = hintText;
        }
    }

    static List<Entry> build(XtextResource resource, IDocument document)
    {
        if (resource == null || resource.getParseResult() == null || document == null)
            return Collections.emptyList();

        EObject root = resource.getParseResult().getRootASTElement();
        if (root == null)
            return Collections.emptyList();

        List<Entry> entries = new ArrayList<>();
        TreeIterator<EObject> iterator = root.eAllContents();
        while (iterator.hasNext())
        {
            EObject element = iterator.next();
            if (isTracked(element))
            {
                Entry entry = toEntry(element, document);
                if (entry != null)
                    entries.add(entry);
            }
        }
        return entries;
    }

    private static boolean isTracked(EObject element)
    {
        return element instanceof Method
            || element instanceof IfStatement
            || element instanceof LoopStatement
            || element instanceof TryExceptStatement
            || element instanceof RegionPreprocessor
            || element instanceof IfPreprocessor;
    }

    private static Entry toEntry(EObject element, IDocument document)
    {
        ICompositeNode node = NodeModelUtils.getNode(element);
        if (node == null)
            return null;

        int startOffset = node.getOffset();
        int endOffset = resolveEndOffset(element, node);
        if (endOffset < 0 || endOffset <= startOffset)
            return null;

        try
        {
            int startLine = document.getLineOfOffset(startOffset);
            int endLine = document.getLineOfOffset(endOffset - 1);
            if (endLine <= startLine)
                return null; // конструкция целиком на одной строке — подсказка не нужна

            if (!hasEmptyTail(document, endOffset, endLine))
                return null; // справа от закрывающего ключевого слова есть код/комментарий

            String hintText = element instanceof Method method
                ? buildMethodHint(method)
                : extractConstructHintText(document, node, element, leadingKeywordCount(element));
            if (hintText == null || hintText.isEmpty())
                return null;
            if (hintText.length() > MAX_HINT_LENGTH)
                hintText = hintText.substring(0, MAX_HINT_LENGTH) + "…"; //$NON-NLS-1$

            return new Entry(startLine, endLine, endOffset, hintText);
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    /**
     * Для {@link RegionPreprocessor}/{@link IfPreprocessor} правило грамматики
     * BSL ({@code PreprocessorStatementRegion}/{@code PreprocessorStatementIf})
     * содержит фичу {@code itemAfter}, которая рекурсивно захватывает ВЕСЬ
     * список операторов после {@code #КонецОбласти}/{@code #КонецЕсли} —
     * включая следующие {@code #Область}/{@code #Если}. Поэтому
     * {@code node.getEndOffset()} для этих типов указывает не на конец самой
     * конструкции, а на конец всей последующей цепочки. Нужно брать офсет
     * конца именно СВОЕГО закрывающего терминала — см. {@link #findOwnClosingTerminalEndOffset}.
     * Для остальных типов (Если/Пока/Для/Попытка/Процедура/Функция) узел
     * ограничен корректно — используется {@code node.getEndOffset()}.
     */
    private static int resolveEndOffset(EObject element, ICompositeNode node)
    {
        if (element instanceof RegionPreprocessor || element instanceof IfPreprocessor)
            return findOwnClosingTerminalEndOffset(node);
        return node.getEndOffset();
    }

    /**
     * Офсет конца СОБСТВЕННОГО закрывающего терминала узла (не вложенного).
     * Узел {@link RegionPreprocessor}/{@link IfPreprocessor} не только "раздут"
     * через {@code itemAfter} (см. {@link #resolveEndOffset}), но и рекурсивно
     * включает листья ЛЮБЫХ вложенных блоков того же семейства — вложенный
     * {@code #Область}/{@code #Если}/{@code #Удаление}/{@code #Вставка} внутри.
     * Наивный поиск "первого попавшегося" {@code END_*}-терминала находил
     * закрывающий токен ВЛОЖЕННОГО блока вместо своего — из-за этого имена
     * вложенных и внешних областей "накладывались" на одной строке.
     *
     * <p>Все терминалы блочных препроцессорных директив в грамматике BSL
     * названы по единому соглашению — {@code BEGIN_REGION}/{@code END_REGION},
     * {@code BEGIN_IFPREPROCESSOR}/{@code END_IFPREPROCESSOR},
     * {@code BEGIN_DELETE}/{@code END_DELETE}, {@code BEGIN_INSERT}/{@code END_INSERT}.
     * Считаем глубину вложенности по префиксу {@code BEGIN_}/{@code END_} —
     * первый лист узла всегда наш собственный {@code BEGIN_*} (глубина 0→1),
     * и когда встречный {@code END_*} возвращает глубину к 0 — это гарантированно
     * наш собственный закрывающий терминал (грамматика не допускает
     * пересечения разных типов пар).
     *
     * <p>Для ссылки на терминал по имени {@link ILeafNode#getGrammarElement()}
     * возвращает {@link RuleCall} (ссылку на правило), а не сам {@code AbstractRule} —
     * имя нужно брать через {@code ruleCall.getRule().getName()}.
     */
    private static int findOwnClosingTerminalEndOffset(ICompositeNode node)
    {
        int depth = 0;
        for (ILeafNode leaf : node.getLeafNodes())
        {
            if (leaf.isHidden())
                continue;
            if (!(leaf.getGrammarElement() instanceof RuleCall ruleCall))
                continue;

            String ruleName = ruleCall.getRule().getName();
            if (ruleName.startsWith("BEGIN_")) //$NON-NLS-1$
            {
                depth++;
            }
            else if (ruleName.startsWith("END_")) //$NON-NLS-1$
            {
                depth--;
                if (depth == 0)
                    return leaf.getEndOffset();
            }
        }
        return -1; // собственный закрывающий терминал не найден — конструкция не распознана как ожидалось
    }

    /**
     * Справа от конца конструкции на её последней строке допустимы только
     * пробельные символы (включая табуляцию) и {@code ;} — любой другой текст,
     * включая {@code //}-комментарий, отменяет показ подсказки для этой строки.
     */
    private static boolean hasEmptyTail(IDocument document, int endOffset, int endLine) throws BadLocationException
    {
        int lineOffset = document.getLineOffset(endLine);
        int lineLength = document.getLineLength(endLine);
        int relativeEnd = endOffset - lineOffset;
        if (relativeEnd < 0 || relativeEnd > lineLength)
            return false;

        String tail = document.get(lineOffset + relativeEnd, lineLength - relativeEnd);
        for (int i = 0; i < tail.length(); i++)
        {
            char c = tail.charAt(i);
            if (Character.isWhitespace(c) || c == ';')
                continue;
            return false;
        }
        return true;
    }

    /**
     * Сколько ведущих ключевых слов узла нужно пропустить в подсказке — они
     * избыточны, так как уже понятны по закрывающему слову ({@code КонецЕсли}
     * говорит, что это было {@code Если}, и т.д.).
     *
     * <p>{@link ForStatement} (в т.ч. {@code ForToStatement}/{@code ForEachStatement})
     * — особый случай: грамматика {@code ForStatement returns LoopStatement:
     * ('For'|'Для') (ForToStatementRest | ForEachStatementRest)} не создаёт
     * собственный объект для внешнего правила (нет действия {@code {LoopStatement}}),
     * поэтому токен {@code Для} НЕ входит в узел ни {@code ForToStatement}, ни
     * {@code ForEachStatement} — их первый лист уже сразу после {@code Для}
     * (переменная цикла для {@code ForToStatement}, {@code Каждого} для
     * {@code ForEachStatement}). Пропускать для них ничего не нужно — {@code Для}
     * и так не попадает в подсказку. Подтверждено логами {@code bracket-hint.log}:
     * первый лист {@code ForToStatement} — {@code RuleCall(IDENT)=Индекс}, а не {@code Для}.
     */
    private static int leadingKeywordCount(EObject element)
    {
        if (element instanceof ForStatement)
            return 0;
        if (element instanceof IfStatement
            || element instanceof WhileStatement
            || element instanceof TryExceptStatement
            || element instanceof RegionPreprocessor
            || element instanceof IfPreprocessor)
            return 1;
        return 0;
    }

    /**
     * Ключевые слова, которыми условие/заголовок конструкции заканчивается
     * и начинается тело (Если/#Если — {@code Тогда}/{@code Then}; Пока/Для —
     * {@code Цикл}/{@code Do}). Используются, чтобы найти границу заголовка
     * в узле, если условие занимает несколько физических строк — иначе для
     * многострочных условий подсказка обрезалась бы на первой строке.
     */
    private static boolean isHeaderEndKeyword(String leafText)
    {
        String t = leafText.toLowerCase(Locale.ROOT);
        return t.equals("тогда") || t.equals("then") || t.equals("цикл") || t.equals("do"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Многострочный заголовок ищем только у {@link IfStatement}/{@link WhileStatement}/
     * {@link ForStatement} — их условие реально может быть разбито на
     * несколько строк ({@code И}/{@code ИЛИ} на отдельных строках).
     * {@link IfPreprocessor} (#Если) — строго однострочная директива
     * препроцессора, многострочных условий не бывает, поиск не нужен.
     * {@link RegionPreprocessor} (имя — один токен) и {@link TryExceptStatement}
     * (голое {@code Попытка}, без условия) вообще не имеют маркера
     * {@code Тогда}/{@code Цикл} в заголовке — поиск на их узле (у Region ещё
     * и "раздутом" через {@code itemAfter}, см. {@link #resolveEndOffset})
     * рисковал бы случайно найти {@code Тогда}/{@code Цикл}, принадлежащий
     * вложенной конструкции глубоко в теле.
     */
    private static boolean hasBooleanHeader(EObject element)
    {
        return element instanceof IfStatement
            || element instanceof WhileStatement
            || element instanceof ForStatement;
    }

    /**
     * Текст подсказки для конструкций, у которых начало — это заголовок
     * исходника, БЕЗ ведущих ключевых слов (см. {@link #leadingKeywordCount}).
     * Для {@link #hasBooleanHeader} конструкций заголовок может занимать
     * несколько строк (условие с {@code И}/{@code ИЛИ} на отдельных строках) —
     * они склеиваются через пробел до слова {@code Тогда}/{@code Цикл}. Для
     * {@link Method} (Процедура/Функция) используется отдельный {@link #buildMethodHint}.
     */
    private static String extractConstructHintText(IDocument document, ICompositeNode node, EObject element,
        int skipKeywords) throws BadLocationException
    {
        int contentStart = skipKeywords > 0 ? skipLeadingKeywords(node, skipKeywords) : node.getOffset();

        if (hasBooleanHeader(element))
        {
            int headerEnd = findHeaderEndOffset(node, contentStart);
            if (headerEnd > contentStart)
                return joinLines(document, contentStart, headerEnd);
        }

        return extractSingleLineText(document, contentStart);
    }

    /** Офсет конца ближайшего (после {@code fromOffset}) листа-ключевого слова {@link #isHeaderEndKeyword}. */
    private static int findHeaderEndOffset(ICompositeNode node, int fromOffset)
    {
        for (ILeafNode leaf : node.getLeafNodes())
        {
            if (leaf.isHidden() || leaf.getOffset() < fromOffset)
                continue;
            if (isHeaderEndKeyword(leaf.getText().strip()))
                return leaf.getEndOffset();
        }
        return -1;
    }

    private static String extractSingleLineText(IDocument document, int contentStart) throws BadLocationException
    {
        int line = document.getLineOfOffset(contentStart);
        int lineOffset = document.getLineOffset(line);
        int lineLength = document.getLineLength(line);
        String lineText = document.get(lineOffset, lineLength);

        int relativeStart = contentStart - lineOffset;
        if (relativeStart < 0 || relativeStart > lineText.length())
            return ""; //$NON-NLS-1$

        return lineText.substring(relativeStart).stripLeading().stripTrailing();
    }

    /** Склеивает текст {@code [start, end)} через пробел построчно (каждая строка — без своих отступов). */
    private static String joinLines(IDocument document, int start, int end) throws BadLocationException
    {
        String raw = document.get(start, end - start);
        StringBuilder sb = new StringBuilder();
        for (String rawLine : raw.split("\\r?\\n")) //$NON-NLS-1$
        {
            String piece = rawLine.strip();
            if (piece.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(piece);
        }
        return sb.toString();
    }

    /** Офсет конца {@code count}-го непустого (не скрытого пробела/комментария) листа узла. */
    private static int skipLeadingKeywords(ICompositeNode node, int count)
    {
        int offset = node.getOffset();
        int skipped = 0;
        for (ILeafNode leaf : node.getLeafNodes())
        {
            if (leaf.isHidden())
                continue;
            offset = leaf.getEndOffset();
            skipped++;
            if (skipped >= count)
                break;
        }
        return offset;
    }

    /** «&Директива1 &Директива2 ИмяМетода» — без ключевого слова Процедура/Функция, без параметров. */
    private static String buildMethodHint(Method method)
    {
        StringBuilder sb = new StringBuilder();
        for (Pragma pragma : method.getPragmas())
        {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append('&').append(pragma.getSymbol());
        }
        if (sb.length() > 0)
            sb.append(' ');
        sb.append(method.getName());
        return sb.toString();
    }
}
