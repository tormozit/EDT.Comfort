package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Форматирование модуля по тексту, без синтаксического дерева: порт
 * {@code ирКлсПолеТекстаПрограммы.СтроитьДеревоПрограммныхСкобок} (без карты родителей) и
 * {@code НайтиКлючевыеСловаФорматирования} из ИР. Отступ строки — уровень инструкции плюс
 * вложенность скобок незавершённого выражения; число строк меняется только сжатием пустых строк.
 *
 * <p>Отличия от ИР: к русским ключевым словам добавлены английские; пустая строка остаётся пустой
 * (ИР дописывает ей отступ); без пересчёта отступов ({@link Settings#reindent}) у строки остаётся
 * прежнее начало; без сдвига продолжения ({@link Settings#continuationShift}) непервая строка
 * инструкции сдвигается не больше чем на один уровень.
 */
final class BslTextFormatter
{
    /** Что делать со строками. */
    static final class Settings
    {
        /** Пересчитывать отступы; иначе меняется только содержимое строки. */
        boolean reindent = true;
        /** Сдвиг непервых строк инструкции по вложенности скобок. */
        boolean continuationShift = true;
        /** {@code СжиматьВнутренниеПустотыВСтроке}. */
        boolean compressInnerSpaces;
        /** {@code СжиматьПустыеСтроки}. */
        boolean compressBlankLines;
        /** Один уровень отступа. */
        String indentUnit = "\t"; //$NON-NLS-1$
        /** Ширина табуляции в пробелах ({@code ЧислоПробеловВТабуляции}). */
        int tabWidth = 4;
    }

    /** {@code мПлатформа.шПустоеНачалоСтроки}. */
    private static final String EMPTY_LINE_START = "(?:\\n|^)(?:\\t| |\\u00a0)*"; //$NON-NLS-1$

    private static final String LETTER = "_ЁА-ЯA-Z"; //$NON-NLS-1$

    /** {@code шПредИмяПрямое}: перед именем нет буквы, цифры, точки и префикса параметра. */
    private static final String PRE_NAME = "(?:^|[^\\d\\." + LETTER + "&@~\\?#])"; //$NON-NLS-1$ //$NON-NLS-2$

    private static final String NAME = "[" + LETTER + "][" + LETTER + "\\d]*"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * {@code ЗалитьКомментарииИСтроковыеЛитералы(Строка,, Истина,, Истина, Ложь)}: строковые литералы
     * и комментарии удаляются, кроме литералов-имён ({@code "Forms.TextBox.1"}) и перечня имён
     * ({@code "Имя, Описание"}); инструкции препроцессора остаются.
     */
    private static final Pattern FILL_PATTERN = compile(
        "(\"" + NAME + "(?:\\.[" + LETTER + "\\d]+)*\")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        + "|(\"(?:" + NAME + "\\s*,\\s*)*" + NAME + "\")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        + "|//[^\\n]*" //$NON-NLS-1$
        + "|\"(?:(?:\"\")|[^\"\\n])*\"?" //$NON-NLS-1$
        + "|(?:(\\n|^)\\s*\\|(?:(?:\"\")|[^\"\\n])*\"?)" //$NON-NLS-1$
        + "|([\\s\\S])"); //$NON-NLS-1$

    /**
     * {@code ГруппыКлючевыхСлов}: «начало*конец условия, середина, конец конструкции»;
     * {@code *} — любая строка.
     */
    private static final String[] KEYWORD_GROUPS = {
        "#?(Если|ИначеЕсли|Для|Пока|If|ElsIf|For|While)*(Тогда|Цикл|Then|Do),#?(?:Иначе|Else)," //$NON-NLS-1$
            + "#?(КонецЕсли|КонецЦикла|EndIf|EndDo)", //$NON-NLS-1$
        "(?:(?:Асинх\\s+|Async\\s+)?(?:Процедура|Функция|Procedure|Function)|Попытка|Try|#Область|#Region)*," //$NON-NLS-1$
            + "(?:Исключение|Except)," //$NON-NLS-1$
            + "(?:КонецПроцедуры|КонецФункции|КонецПопытки|#КонецОбласти|EndProcedure|EndFunction|EndTry|#EndRegion)" //$NON-NLS-1$
    };

    private static final Pattern INNER_SPACES = Pattern.compile("[ \\f\\t\\u000B]+"); //$NON-NLS-1$

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private BslTextFormatter() {}

    /** Уровни разбора между строками. */
    private static final class State
    {
        int instructionLevel;
        int expressionLevel;
        int conditionStartLevel;
        int nextLevelShift;
    }

    /**
     * Форматирует строки области.
     *
     * @param lines строки области без разделителей
     * @param lineAbove строка над областью или {@code null}, если область начинается с первой строки
     * @return новые строки области (их число меняется только сжатием пустых строк)
     */
    static List<String> format(List<String> lines, String lineAbove, Settings settings)
    {
        List<String> document = new ArrayList<>(lines);
        if (document.isEmpty())
            return document;
        boolean hasLineAbove = lineAbove != null;
        String firstLine = hasLineAbove ? lineAbove : document.get(0);
        String firstLineIndent = leadingWhitespace(firstLine).replace("\t", " ".repeat(settings.tabWidth)); //$NON-NLS-1$ //$NON-NLS-2$
        int baseLevel = firstLineIndent.length() / Math.max(1, settings.tabWidth);
        if (hasLineAbove)
            document.add(0, lineAbove);

        State state = new State();
        String previousLine = null;
        Integer commentedBlockLevel = null;
        for (int index = 0; index < document.size(); index++)
        {
            String line = document.get(index);
            String lineTrimmedLeft = trimLeft(line);
            if (lineTrimmedLeft.startsWith("~") && trimRight(line).endsWith(":")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                // Метка
                if (settings.reindent)
                    document.set(index, lineTrimmedLeft);
                continue;
            }
            String bare = trim(line);
            String code = fillCommentsAndLiterals(bare);
            String bareCode = trim(code);
            if (index > 0 && settings.compressBlankLines && bare.isEmpty())
            {
                String nextLine = index == document.size() - 1 ? "" : trimLeft(document.get(index + 1)); //$NON-NLS-1$
                boolean canDelete = true;
                if (isBlank(previousLine))
                {
                    //
                }
                else if ((startsWith(nextLine, "//") //$NON-NLS-1$
                        || startsWith(nextLine, "#") //$NON-NLS-1$
                        || startsWith(previousLine, "#") //$NON-NLS-1$
                        || startsWith(nextLine, "&")) //$NON-NLS-1$
                    && !isMethodStart(previousLine))
                {
                    canDelete = false;
                    commentedBlockLevel = state.instructionLevel;
                }
                else if ((commentedBlockLevel != null && commentedBlockLevel.intValue() == state.instructionLevel
                        && !startsWith(nextLine, "Иначе") && !startsWith(nextLine, "Конец") //$NON-NLS-1$ //$NON-NLS-2$
                        && !startsWith(nextLine, "Else") && !startsWith(nextLine, "End")) //$NON-NLS-1$ //$NON-NLS-2$
                    || isMethodStart(nextLine)
                    || isMethodEnd(previousLine))
                {
                    canDelete = false;
                    commentedBlockLevel = null;
                }
                else
                {
                    commentedBlockLevel = null;
                }
                if (canDelete)
                {
                    document.remove(index);
                    if (document.size() < index + 1)
                        break;
                    index--;
                    continue;
                }
            }
            if (settings.compressInnerSpaces && !bare.startsWith("|") && !startsWith(bare, "//")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                int quote = bare.indexOf('"');
                String head = quote < 0 ? bare : bare.substring(0, quote);
                bare = INNER_SPACES.matcher(head).replaceAll(" ") + (quote < 0 ? "" : bare.substring(quote)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            int levelShift = 0;
            boolean keywordFound = findFormattingKeywords(code, state, previousLine);
            state.instructionLevel = Math.max(-baseLevel, state.instructionLevel);
            if (index > 0)
            {
                if (!keywordFound)
                {
                    if (state.expressionLevel == 1 && bareCode.startsWith(")")) // В конце сигнатуры //$NON-NLS-1$
                        levelShift = state.expressionLevel - 2;
                    else
                        levelShift = state.expressionLevel;
                    if (!settings.continuationShift)
                        levelShift = Math.min(levelShift, 1);
                }
                int lineLevel = baseLevel + state.instructionLevel + levelShift;
                document.set(index, lineWithIndent(line, bare, lineLevel, settings));
            }
            else
            {
                if (keywordFound)
                    state.instructionLevel = 0;
                if (!hasLineAbove)
                    document.set(index, lineWithIndent(line, bare, baseLevel, settings));
            }
            state.instructionLevel += state.nextLevelShift;
            previousLine = line;
            if (isBlank(code))
                continue;
            int newExpressionLevel;
            if (bareCode.startsWith("&") || bareCode.endsWith(";")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                newExpressionLevel = 0;
            }
            else
            {
                newExpressionLevel = state.expressionLevel;
                if (newExpressionLevel == 0 && !keywordFound)
                    newExpressionLevel = 1;
                newExpressionLevel = Math.max(0,
                    newExpressionLevel + count(bareCode, '(') - count(bareCode, ')'));
            }
            state.expressionLevel = newExpressionLevel;
        }
        if (hasLineAbove && !document.isEmpty())
            document.remove(0);
        return document;
    }

    /** {@code НайтиКлючевыеСловаФорматирования}. */
    private static boolean findFormattingKeywords(String code, State state, String previousLine)
    {
        state.nextLevelShift = 0;
        boolean found = false;
        boolean foundReliably = false;
        for (String group : KEYWORD_GROUPS)
        {
            String[] words = group.split(","); //$NON-NLS-1$
            for (int i = 0; i < words.length; i++)
                words[i] = words[i].trim();
            String constructionEnd = words[words.length - 1];
            if (count(EMPTY_LINE_START + constructionEnd + "(?=//|\\s*;|\\s*$)", code) > 0) //$NON-NLS-1$
            {
                state.instructionLevel--;
                found = true;
            }
            else
            {
                for (int wordIndex = 0; wordIndex < words.length - 1; wordIndex++)
                {
                    String word = words[wordIndex];
                    if (word.indexOf('*') < 0)
                    {
                        if (count(EMPTY_LINE_START + word + "(?=//|\\s*$)", code) > 0) //$NON-NLS-1$
                        {
                            state.instructionLevel--;
                            found = true;
                            state.nextLevelShift = 1;
                            state.expressionLevel = 0;
                        }
                    }
                    else
                    {
                        String[] fragments = word.split("\\*", -1); //$NON-NLS-1$
                        if (!fragments[1].isEmpty()
                            && count(EMPTY_LINE_START + fragments[1] + "(?=//|\\s*$)", code) > 0) //$NON-NLS-1$
                        {
                            // Конец условия на отдельной строке
                            state.instructionLevel = state.conditionStartLevel;
                            found = true;
                            state.nextLevelShift = 1;
                            state.expressionLevel = 0;
                        }
                        else
                        {
                            Matcher starts = pattern(EMPTY_LINE_START + fragments[0] + "(?=//|\\s|$)").matcher(code); //$NON-NLS-1$
                            int conditionStarts = 0;
                            String firstWord = ""; //$NON-NLS-1$
                            while (starts.find())
                            {
                                if (conditionStarts == 0 && starts.groupCount() > 0 && starts.group(1) != null)
                                    firstWord = starts.group(1);
                                conditionStarts++;
                            }
                            boolean needConstructionEnd = false;
                            int conditionEnds;
                            if (!fragments[1].isEmpty())
                            {
                                Matcher ends = pattern(PRE_NAME + fragments[1] + "(?=//|\\s|$)([^\\n]*)").matcher(code); //$NON-NLS-1$
                                conditionEnds = 0;
                                String lineEnd = null;
                                while (ends.find())
                                {
                                    conditionEnds++;
                                    lineEnd = ends.group(1);
                                }
                                if (conditionEnds > 0)
                                    needConstructionEnd = lineEnd != null && !lineEnd.isEmpty()
                                        && !trimLeft(lineEnd).startsWith("//"); //$NON-NLS-1$
                            }
                            else
                            {
                                conditionEnds = conditionStarts;
                            }
                            if (wordIndex > 0 || "ИначеЕсли".equalsIgnoreCase(firstWord) //$NON-NLS-1$
                                || "ElsIf".equalsIgnoreCase(firstWord)) //$NON-NLS-1$
                                state.instructionLevel--;
                            if (conditionStarts > conditionEnds)
                            {
                                state.expressionLevel = 1;
                                found = true;
                                state.conditionStartLevel = state.instructionLevel;
                            }
                            if (conditionEnds > 0)
                            {
                                state.expressionLevel = 0;
                                found = true;
                                foundReliably = conditionStarts >= conditionEnds;
                                boolean directive = code.startsWith("#"); //$NON-NLS-1$
                                if ((!needConstructionEnd && !directive)
                                    || ((state.instructionLevel > 0
                                            || !directive
                                            || (previousLine != null && !previousLine.isEmpty()
                                                && !trimLeft(previousLine).startsWith("//"))) //$NON-NLS-1$
                                        && count(PRE_NAME + constructionEnd + "(?=//|\\s*;|\\s*$)", code) == 0)) //$NON-NLS-1$
                                    state.nextLevelShift = 1;
                            }
                        }
                    }
                    if (found && foundReliably)
                        break;
                }
            }
            if (found && foundReliably)
                break;
        }
        return found;
    }

    /** {@code ЗалитьКомментарииИСтроковыеЛитералы}: длина строки меняется. */
    static String fillCommentsAndLiterals(String text)
    {
        if (text == null || text.isEmpty())
            return text;
        Matcher matcher = FILL_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        while (matcher.find())
        {
            for (int group = 1; group <= 4; group++)
            {
                String value = matcher.group(group);
                if (value != null)
                    result.append(value);
            }
        }
        return result.toString();
    }

    private static String lineWithIndent(String original, String bare, int level, Settings settings)
    {
        if (bare.isEmpty())
            return bare;
        if (!settings.reindent)
            return leadingWhitespace(original) + bare;
        return settings.indentUnit.repeat(Math.max(0, level)) + bare;
    }

    private static boolean isMethodStart(String line)
    {
        return startsWith(line, "Процедура ") || startsWith(line, "Функция ") //$NON-NLS-1$ //$NON-NLS-2$
            || startsWith(line, "Procedure ") || startsWith(line, "Function "); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isMethodEnd(String line)
    {
        return startsWith(line, "КонецПроцедуры") || startsWith(line, "КонецФункции") //$NON-NLS-1$ //$NON-NLS-2$
            || startsWith(line, "EndProcedure") || startsWith(line, "EndFunction"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@code СтрНачинаетсяСЛкс} без учёта регистра. */
    private static boolean startsWith(String text, String prefix)
    {
        return text != null && text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static int count(String regex, String text)
    {
        Matcher matcher = pattern(regex).matcher(text);
        int result = 0;
        while (matcher.find())
            result++;
        return result;
    }

    private static int count(String text, char c)
    {
        int result = 0;
        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == c)
                result++;
        }
        return result;
    }

    private static Pattern pattern(String regex)
    {
        return PATTERNS.computeIfAbsent(regex, BslTextFormatter::compile);
    }

    private static Pattern compile(String regex)
    {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static boolean isBlank(String text)
    {
        return text == null || trim(text).isEmpty();
    }

    private static boolean isSpace(char c)
    {
        return c == ' ' || c == '\t' || c == '\u00a0' || c == '\f' || c == '\r' || c == '\u000B';
    }

    static String leadingWhitespace(String text)
    {
        int i = 0;
        while (i < text.length() && isSpace(text.charAt(i)))
            i++;
        return text.substring(0, i);
    }

    static String trimLeft(String text)
    {
        return text.substring(leadingWhitespace(text).length());
    }

    static String trimRight(String text)
    {
        int end = text.length();
        while (end > 0 && isSpace(text.charAt(end - 1)))
            end--;
        return text.substring(0, end);
    }

    static String trim(String text)
    {
        return trimRight(trimLeft(text));
    }
}
