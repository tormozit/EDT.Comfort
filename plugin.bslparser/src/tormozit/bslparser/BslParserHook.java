package tormozit.bslparser;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.antlr.runtime.Token;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.TerminalRule;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.antlr.XtextTokenStream;

/**
 * Хук разбора модулей BSL: директивы препроцессора ({@code #Если}, {@code #Вставка},
 * {@code #Удаление}), разрывающие оператор.
 *
 * <p>Платформа применяет препроцессор к тексту до разбора, поэтому ей годится, например,
 * {@code #Если … #КонецЕсли} или {@code #Вставка … #КонецВставки} вокруг ветки {@code ИначеЕсли}.
 * В грамматике EDT ({@code Bsl.xtext}) директива — узел синтаксического дерева, внутри которого
 * только целые операторы, и такой код даёт «Пропущена лексема "#КонецЕсли"».
 *
 * <p>Перед разбором хук находит блоки {@code #Если…#КонецЕсли}, {@code #Вставка…#КонецВставки},
 * {@code #Удаление…#КонецУдаления}, ветки которых разрывают оператор
 * (несбалансированы по {@code Если/КонецЕсли}, {@code Пока|Для/КонецЦикла},
 * {@code Попытка/КонецПопытки}, {@code Процедура|Функция/Конец…} или содержат
 * {@code ИначеЕсли}/{@code Иначе}/{@code Исключение} на нулевой глубине), и переводит токены
 * <b>только самих директив</b> в тип {@code SL_COMMENT}. Токен скрыт для парсера (Xtext решает по
 * типу), код веток остаётся в дереве — подсказки и переходы работают. Блок скрывается, только
 * если после этого структура его метода сходится; иначе остаётся штатная ошибка EDT.
 * Проверка «Комфорт» ставит на такую {@code #Если} незначительную проблему
 * ({@link #findHiddenIfInstructions}); на {@code #Вставка}/{@code #Удаление} — нет.
 *
 * <p>Частичный разбор ({@code BslPartialParsingHelper.reparse}) перечитывает текст одного узла.
 * Если граница куска рассекает блок директив (в куске директива без пары), разбор куска
 * отбрасывается — {@code reparse} при пустом результате сама делает полный разбор до подмены
 * дерева.
 *
 * <p>Методы ниже вызывает код, вплетённый {@link Activator} в классы EDT.
 *
 * @see <a href="https://github.com/tormozit/EDT.Comfort/issues/561">issue 561</a>
 */
public final class BslParserHook
{
    static final String TOPIC = "bsl-preproc-straddle"; //$NON-NLS-1$

    private static final ThreadLocal<ParseState> STATE = ThreadLocal.withInitial(ParseState::new);

    private BslParserHook()
    {
    }

    // ---- Вызовы из вплетённого кода ----

    /** Начало {@code createParser(XtextTokenStream)}: поток ещё не читался парсером. */
    public static void beforeCreateParser(Object streamObj)
    {
        ParseState state = STATE.get();
        state.resetParse();
        state.parseStartNanos = System.nanoTime();
        if (!(streamObj instanceof XtextTokenStream stream))
            return;
        try
        {
            new Analyzer(stream, state).run();
        }
        catch (Throwable t)
        {
            TempLog.logException(TOPIC, "analyze", t); //$NON-NLS-1$
        }
    }

    /**
     * Результат разбора куска в {@code reparse}. {@code null} — отказ от куска: {@code reparse}
     * тогда сама делает полный разбор, ещё не тронув старое дерево.
     */
    public static Object afterChunkParse(Object result)
    {
        ParseState state = STATE.get();
        if (!state.cut)
            return result;
        long chunkNanos = System.nanoTime() - state.parseStartNanos;
        TempLog.log(TOPIC, String.format(Locale.ROOT,
            "частичный -> полный: кусок %d симв., строка %d; причина: %s; разбор куска %.1f мс", //$NON-NLS-1$
            state.textLength, state.firstLine, state.cutReason, chunkNanos / 1e6));
        state.cut = false;
        state.forcedStartNanos = System.nanoTime();
        return null;
    }

    /** Результат {@code fullyReparse} внутри {@code reparse} — замер, если полный разбор вызвали мы. */
    public static Object afterFullReparse(Object result)
    {
        ParseState state = STATE.get();
        if (state.forcedStartNanos == 0)
            return result;
        long nanos = System.nanoTime() - state.forcedStartNanos;
        state.forcedStartNanos = 0;
        int length = -1;
        if (result instanceof IParseResult parseResult && parseResult.getRootNode() != null)
            length = parseResult.getRootNode().getTotalLength();
        TempLog.log(TOPIC, String.format(Locale.ROOT, "полный разбор (наш): %.1f мс, модуль %d симв.", //$NON-NLS-1$
            nanos / 1e6, length));
        return result;
    }

    // ---- Для проверки «Комфорт» ----

    /**
     * Скрытые хуком инструкции {@code #Если … Тогда} в дереве узлов: {@code {смещение, длина, строка}}.
     * Скрытые {@code #Вставка}/{@code #Удаление} сюда не попадают: проблема о них не нужна.
     *
     * <p>Признак — скрытый лист {@code SL_COMMENT}, текст которого начинается с {@code #}: настоящий
     * комментарий всегда начинается с {@code //}.
     */
    public static List<int[]> findHiddenIfInstructions(ICompositeNode root)
    {
        List<int[]> out = new ArrayList<>();
        if (root == null)
            return out;
        int start = -1;
        int end = -1;
        int line = 0;
        for (ILeafNode leaf : root.getLeafNodes())
        {
            boolean ours = isHiddenDirectiveLeaf(leaf);
            if (start >= 0)
            {
                if (leaf.isHidden() && !ours && !isSlComment(leaf))
                    continue; // пробелы внутри инструкции
                if (ours && !leaf.getText().startsWith("#")) //$NON-NLS-1$
                {
                    end = leaf.getEndOffset();
                    if (isThen(leaf.getText()))
                    {
                        out.add(new int[] {start, end - start, line});
                        start = -1;
                    }
                    continue;
                }
                out.add(new int[] {start, end - start, line}); // без «Тогда» — до последнего своего листа
                start = -1;
            }
            if (!ours)
                continue;
            String word = directiveWord(leaf.getText());
            if ("если".equals(word) || "if".equals(word)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                start = leaf.getOffset();
                end = leaf.getEndOffset();
                line = leaf.getStartLine();
            }
        }
        if (start >= 0)
            out.add(new int[] {start, end - start, line});
        return out;
    }

    private static boolean isHiddenDirectiveLeaf(ILeafNode leaf)
    {
        if (!leaf.isHidden() || !isSlComment(leaf))
            return false;
        String text = leaf.getText();
        return text != null && !text.startsWith("//"); //$NON-NLS-1$
    }

    private static boolean isSlComment(ILeafNode leaf)
    {
        EObject ge = leaf.getGrammarElement();
        return ge instanceof TerminalRule rule && "SL_COMMENT".equals(rule.getName()); //$NON-NLS-1$
    }

    /** Слово директивы в нижнем регистре ({@code "#  Если"} → {@code "если"}); не директива — {@code null}. */
    private static String directiveWord(String text)
    {
        if (text == null || !text.startsWith("#")) //$NON-NLS-1$
            return null;
        return text.substring(1).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean isThen(String text)
    {
        String word = text == null ? "" : text.strip().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        return "тогда".equals(word) || "then".equals(word); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- Состояние разбора в потоке ----

    private static final class ParseState
    {
        long parseStartNanos;
        boolean cut;
        String cutReason;
        int textLength;
        int firstLine;
        /** Не сбрасывается в {@link #resetParse}: внутри нашего полного разбора идёт свой разбор. */
        long forcedStartNanos;

        void resetParse()
        {
            parseStartNanos = 0;
            cut = false;
            cutReason = null;
            textLength = 0;
            firstLine = 0;
        }
    }

    // ---- Анализ потока токенов ----

    private static final class Analyzer
    {
        private static final int K_NONE = 0;
        private static final int K_OPEN_IF = 1;
        private static final int K_OPEN_LOOP = 2;
        private static final int K_OPEN_TRY = 3;
        private static final int K_OPEN_PROC = 4;
        private static final int K_OPEN_FUNC = 5;
        private static final int K_CLOSE_IF = 6;
        private static final int K_CLOSE_LOOP = 7;
        private static final int K_CLOSE_TRY = 8;
        private static final int K_CLOSE_PROC = 9;
        private static final int K_CLOSE_FUNC = 10;
        private static final int K_ELSIF = 11;
        private static final int K_ELSE = 12;
        private static final int K_EXCEPT = 13;
        private static final int K_THEN = 14;
        private static final int D_BEGIN = 20;
        private static final int D_ELSEIF = 21;
        private static final int D_ELSE = 22;
        private static final int D_END = 23;
        private static final int D_INS_BEGIN = 24;
        private static final int D_INS_END = 25;
        private static final int D_DEL_BEGIN = 26;
        private static final int D_DEL_END = 27;

        /** Условие директивы длиннее — считаем её недописанной. */
        private static final int MAX_CONDITION_TOKENS = 64;

        private static final Map<String, Integer> KEYWORDS = Map.ofEntries(
            Map.entry("если", K_OPEN_IF), Map.entry("if", K_OPEN_IF), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("пока", K_OPEN_LOOP), Map.entry("while", K_OPEN_LOOP), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("для", K_OPEN_LOOP), Map.entry("for", K_OPEN_LOOP), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("попытка", K_OPEN_TRY), Map.entry("try", K_OPEN_TRY), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("процедура", K_OPEN_PROC), Map.entry("procedure", K_OPEN_PROC), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("функция", K_OPEN_FUNC), Map.entry("function", K_OPEN_FUNC), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("конецесли", K_CLOSE_IF), Map.entry("endif", K_CLOSE_IF), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("конеццикла", K_CLOSE_LOOP), Map.entry("enddo", K_CLOSE_LOOP), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("конецпопытки", K_CLOSE_TRY), Map.entry("endtry", K_CLOSE_TRY), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("конецпроцедуры", K_CLOSE_PROC), Map.entry("endprocedure", K_CLOSE_PROC), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("конецфункции", K_CLOSE_FUNC), Map.entry("endfunction", K_CLOSE_FUNC), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("иначеесли", K_ELSIF), Map.entry("elsif", K_ELSIF), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("иначе", K_ELSE), Map.entry("else", K_ELSE), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("исключение", K_EXCEPT), Map.entry("except", K_EXCEPT), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("тогда", K_THEN), Map.entry("then", K_THEN)); //$NON-NLS-1$ //$NON-NLS-2$

        /** Типы токенов берутся из констант сгенерированного лексера по имени — не зашиты числами. */
        private static final ClassValue<int[]> TYPES = new ClassValue<>()
        {
            @Override
            protected int[] computeValue(Class<?> lexer)
            {
                try
                {
                    String[] names = {"RULE_SL_COMMENT", "RULE_WS", "RULE_UTF8_BOM", "RULE_IDENT", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "RULE_BEGIN_IFPREPROCESSOR", "RULE_ELSEIF_PREPROCESSOR", "RULE_ELSE_PREPROCESSOR", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "RULE_END_IFPREPROCESSOR", "RULE_BEGIN_INSERT", "RULE_END_INSERT", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "RULE_BEGIN_DELETE", "RULE_END_DELETE"}; //$NON-NLS-1$ //$NON-NLS-2$
                    int[] types = new int[names.length];
                    for (int i = 0; i < names.length; i++)
                        types[i] = lexer.getField(names[i]).getInt(null);
                    return types;
                }
                catch (ReflectiveOperationException | RuntimeException e)
                {
                    return null;
                }
            }
        };

        private static final AtomicBoolean typesProblemLogged = new AtomicBoolean();

        private final XtextTokenStream stream;
        private final ParseState state;

        private int tSl;
        private int tWs;
        private int tBom;
        private int tIdent;
        private int tBeginIf;
        private int tElseIf;
        private int tElse;
        private int tEndIf;
        private int tBeginIns;
        private int tEndIns;
        private int tBeginDel;
        private int tEndDel;

        private List<?> list;
        /** Индексы значимых (не скрытых) токенов в {@link #list}. */
        private int[] sig;
        private int sigCount;
        private int[] kind;
        /** Токены инструкций препроцессора — вне расчёта структуры. */
        private boolean[] mask;

        Analyzer(XtextTokenStream stream, ParseState state)
        {
            this.stream = stream;
            this.state = state;
        }

        void run()
        {
            Object source = stream.getTokenSource();
            int[] types = source != null ? TYPES.get(source.getClass()) : null;
            if (types == null)
            {
                if (typesProblemLogged.compareAndSet(false, true))
                    TempLog.log(TOPIC, "нет констант лексера: " + (source == null ? null : source.getClass())); //$NON-NLS-1$
                return;
            }
            tSl = types[0];
            tWs = types[1];
            tBom = types[2];
            tIdent = types[3];
            tBeginIf = types[4];
            tElseIf = types[5];
            tElse = types[6];
            tEndIf = types[7];
            tBeginIns = types[8];
            tEndIns = types[9];
            tBeginDel = types[10];
            tEndDel = types[11];

            long t0 = System.nanoTime();
            list = stream.getTokens(); // заполняет буфер целиком — парсер сделал бы это сам
            int n = list.size();
            sig = new int[n];
            boolean anyDirective = false;
            for (int i = 0; i < n; i++)
            {
                int type = token(i).getType();
                if (type == tSl || type == tWs || type == tBom)
                    continue;
                if (type == tBeginIf || type == tElseIf || type == tElse || type == tEndIf || type == tBeginIns
                    || type == tEndIns || type == tBeginDel || type == tEndDel)
                    anyDirective = true;
                sig[sigCount++] = i;
            }
            if (!anyDirective)
                return;

            if (n > 0)
            {
                Token last = token(n - 1);
                state.textLength = last instanceof org.antlr.runtime.CommonToken ct ? ct.getStopIndex() + 1 : -1;
                state.firstLine = token(0).getLine();
            }
            classify();
            List<List<int[]>> blocks = collectBlocks();
            if (blocks.isEmpty())
            {
                logSummary(0, List.of(), System.nanoTime() - t0);
                return;
            }
            int[] segment = new int[sigCount];
            BitSet badSegments = validate(segment);

            int hidden = 0;
            List<String> rejected = new ArrayList<>();
            for (List<int[]> block : blocks)
            {
                if (!straddles(block))
                    continue;
                int beginSig = block.get(0)[0];
                if (badSegments.get(segment[beginSig]))
                {
                    rejected.add(String.valueOf(token(sig[beginSig]).getLine()));
                    continue;
                }
                for (int[] instr : block)
                    for (int j = instr[0]; j <= instr[1]; j++)
                        token(sig[j]).setType(tSl);
                hidden++;
            }
            if (hidden > 0)
                repositionStream();
            logSummary(hidden, rejected, System.nanoTime() - t0);
        }

        private Token token(int listIndex)
        {
            return (Token) list.get(listIndex);
        }

        private void classify()
        {
            kind = new int[sigCount];
            mask = new boolean[sigCount];
            String prevText = null;
            for (int j = 0; j < sigCount; j++)
            {
                Token t = token(sig[j]);
                int type = t.getType();
                String text = t.getText();
                if (type == tBeginIf)
                    kind[j] = D_BEGIN;
                else if (type == tElseIf)
                    kind[j] = D_ELSEIF;
                else if (type == tElse)
                    kind[j] = D_ELSE;
                else if (type == tEndIf)
                    kind[j] = D_END;
                else if (type == tBeginIns)
                    kind[j] = D_INS_BEGIN;
                else if (type == tEndIns)
                    kind[j] = D_INS_END;
                else if (type == tBeginDel)
                    kind[j] = D_DEL_BEGIN;
                else if (type == tEndDel)
                    kind[j] = D_DEL_END;
                else if (type != tIdent && text != null && !".".equals(prevText)) //$NON-NLS-1$
                {
                    // После точки ключевое слово — имя свойства, не оператор.
                    Integer k = KEYWORDS.get(text.toLowerCase(Locale.ROOT));
                    kind[j] = k != null ? k.intValue() : K_NONE;
                }
                prevText = text;
            }
        }

        /**
         * Блоки {@code #Если…#КонецЕсли}, {@code #Вставка…#КонецВставки}, {@code #Удаление…#КонецУдаления}:
         * список инструкций {@code {первый, последний, недописана}} (индексы в {@link #sig}). Директива
         * без пары (или закрывающая чужой вид блока) — огрызок: отметка для частичного разбора.
         */
        private List<List<int[]>> collectBlocks()
        {
            List<List<int[]>> blocks = new ArrayList<>();
            List<List<int[]>> stack = new ArrayList<>();
            List<Integer> stackKinds = new ArrayList<>();
            for (int j = 0; j < sigCount; j++)
            {
                int k = kind[j];
                if (k < D_BEGIN)
                    continue;
                int[] instr = instruction(j);
                for (int x = instr[0]; x <= instr[1]; x++)
                    mask[x] = true;
                j = instr[1];
                if (k == D_BEGIN || k == D_INS_BEGIN || k == D_DEL_BEGIN)
                {
                    List<int[]> block = new ArrayList<>();
                    block.add(instr);
                    stack.add(block);
                    stackKinds.add(Integer.valueOf(k));
                    continue;
                }
                int open = stackKinds.isEmpty() ? -1 : stackKinds.get(stackKinds.size() - 1).intValue();
                boolean matches = switch (k)
                {
                    case D_ELSEIF, D_ELSE, D_END -> open == D_BEGIN;
                    case D_INS_END -> open == D_INS_BEGIN;
                    case D_DEL_END -> open == D_DEL_BEGIN;
                    default -> false;
                };
                if (!matches)
                {
                    markCut("директива без пары, строка " + token(sig[instr[0]]).getLine()); //$NON-NLS-1$
                    continue;
                }
                List<int[]> block = stack.get(stack.size() - 1);
                block.add(instr);
                if (k == D_ELSEIF || k == D_ELSE)
                    continue;
                stack.remove(stack.size() - 1);
                stackKinds.remove(stackKinds.size() - 1);
                if (block.stream().anyMatch(i -> i[2] != 0))
                    markCut("директива без «Тогда», строка " + token(sig[block.get(0)[0]]).getLine()); //$NON-NLS-1$
                else
                    blocks.add(block);
            }
            if (!stack.isEmpty())
                markCut("директива без закрывающей, строка " + token(sig[stack.get(0).get(0)[0]]).getLine()); //$NON-NLS-1$
            return blocks;
        }

        /** {@code {первый, последний, недописана?1:0}}: {@code #Если}/{@code #ИначеЕсли} — до «Тогда». */
        private int[] instruction(int j)
        {
            int k = kind[j];
            if (k != D_BEGIN && k != D_ELSEIF)
                return new int[] {j, j, 0};
            int limit = Math.min(sigCount - 1, j + MAX_CONDITION_TOKENS);
            for (int x = j + 1; x <= limit; x++)
            {
                if (kind[x] >= D_BEGIN)
                    break;
                if (kind[x] == K_THEN)
                    return new int[] {j, x, 0};
            }
            return new int[] {j, j, 1};
        }

        private void markCut(String reason)
        {
            if (!state.cut)
                state.cutReason = reason;
            state.cut = true;
        }

        /** Хотя бы одна ветка блока несбалансирована — директивы разрывают оператор. */
        private boolean straddles(List<int[]> block)
        {
            for (int b = 0; b + 1 < block.size(); b++)
            {
                int depth = 0;
                boolean broken = false;
                for (int j = block.get(b)[1] + 1; j < block.get(b + 1)[0] && !broken; j++)
                {
                    if (mask[j])
                        continue;
                    int k = kind[j];
                    if (k >= K_OPEN_IF && k <= K_OPEN_FUNC)
                        depth++;
                    else if (k >= K_CLOSE_IF && k <= K_CLOSE_FUNC)
                        broken = --depth < 0;
                    else if (k == K_ELSIF || k == K_ELSE || k == K_EXCEPT)
                        broken = depth == 0;
                }
                if (broken || depth != 0)
                    return true;
            }
            return false;
        }

        /**
         * Структура кода без инструкций препроцессора. Отрезок — от одного {@code Процедура}/{@code Функция}
         * до следующего; в {@code segment} — номер отрезка каждого значимого токена. Ошибка портит
         * только свой отрезок: методы вложенными не бывают, на следующем стек сбрасывается.
         */
        private BitSet validate(int[] segment)
        {
            BitSet bad = new BitSet();
            int[] frameKind = new int[64];
            boolean[] frameFlag = new boolean[64];
            int top = 0;
            int seg = 0;
            for (int j = 0; j < sigCount; j++)
            {
                int k = mask[j] ? K_NONE : kind[j];
                if (k == K_OPEN_PROC || k == K_OPEN_FUNC)
                {
                    if (top > 0)
                        bad.set(seg);
                    seg++;
                    top = 0;
                }
                segment[j] = seg;
                if (k == K_NONE || k == K_THEN || k >= D_BEGIN)
                    continue;
                int topKind = top > 0 ? frameKind[top - 1] : K_NONE;
                switch (k)
                {
                    case K_OPEN_IF, K_OPEN_LOOP, K_OPEN_TRY, K_OPEN_PROC, K_OPEN_FUNC -> {
                        if (top == frameKind.length)
                        {
                            frameKind = java.util.Arrays.copyOf(frameKind, top * 2);
                            frameFlag = java.util.Arrays.copyOf(frameFlag, top * 2);
                        }
                        frameKind[top] = k;
                        frameFlag[top] = false;
                        top++;
                    }
                    case K_ELSIF -> {
                        if (topKind != K_OPEN_IF || frameFlag[top - 1])
                            bad.set(seg);
                    }
                    case K_ELSE, K_EXCEPT -> {
                        int need = k == K_ELSE ? K_OPEN_IF : K_OPEN_TRY;
                        if (topKind != need || frameFlag[top - 1])
                            bad.set(seg);
                        else
                            frameFlag[top - 1] = true;
                    }
                    case K_CLOSE_IF, K_CLOSE_LOOP, K_CLOSE_TRY, K_CLOSE_PROC, K_CLOSE_FUNC -> {
                        int need = k - (K_CLOSE_IF - K_OPEN_IF);
                        boolean ok = topKind == need && (k != K_CLOSE_TRY || frameFlag[top - 1])
                            && (k != K_CLOSE_PROC && k != K_CLOSE_FUNC || top == 1);
                        if (ok)
                            top--;
                        else
                        {
                            bad.set(seg);
                            if (k == K_CLOSE_PROC || k == K_CLOSE_FUNC)
                                top = 0;
                        }
                    }
                    default -> {
                    }
                }
            }
            if (top > 0)
                bad.set(seg);
            return bad;
        }

        /** Текущая позиция потока могла стать скрытым токеном — сдвинуть на следующий значимый. */
        private void repositionStream()
        {
            int p = stream.index();
            int n = list.size();
            if (p < 0 || p >= n || token(p).getType() != tSl)
                return;
            int next = p;
            while (next < n)
            {
                int type = token(next).getType();
                if (type != tSl && type != tWs && type != tBom)
                    break;
                next++;
            }
            stream.seek(next);
        }

        private void logSummary(int hidden, List<String> rejected, long nanos)
        {
            if (hidden == 0 && rejected.isEmpty() && !state.cut)
                return;
            TempLog.log(TOPIC, String.format(Locale.ROOT,
                "разбор %d симв. (с строки %d), токенов %d: скрыто блоков %d, отклонено %s, огрызок: %s; анализ %.2f мс", //$NON-NLS-1$
                state.textLength, state.firstLine, list.size(), hidden, rejected, state.cut ? state.cutReason : "нет", //$NON-NLS-1$
                nanos / 1e6));
        }
    }
}
