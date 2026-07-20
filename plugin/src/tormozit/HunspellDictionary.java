package tormozit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Упрощённый читатель словарей Hunspell/MySpell (пара файлов {@code .aff}/{@code .dic}),
 * используемый {@link ComfortSpellingEngine} и Platform dictionary в {@link SpellCheckHook}.
 * Поддерживает основные директивы {@code SET},
 * {@code FLAG}, {@code PFX}/{@code SFX}, {@code FORBIDDENWORD}; правила суффиксов/приставок
 * применяются без учёта более редких директив (COMPOUND*, MAP, REP и т.п.) — это не полная
 * реализация спецификации Hunspell, а практичное приближение для подсветки и подсказок.
 */
final class HunspellDictionary
{
    /** Алфавит словаря / слова — чтобы не гонять RU-правки для латиницы и наоборот. */
    enum ScriptKind
    {
        CYRILLIC, LATIN, ANY
    }

    private static final String LETTERS_CYRILLIC =
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"; //$NON-NLS-1$
    private static final String LETTERS_LATIN = "abcdefghijklmnopqrstuvwxyz"; //$NON-NLS-1$

    /** Лимит проверок isCorrect на edit-distance 2 (в UI-потоке). */
    private static final int DISTANCE2_MAX_CHECKS = 4000;
    /** Distance 2 только для коротких слов — иначе подсказка подвисает. */
    private static final int DISTANCE2_MAX_WORD_LEN = 7;

    private enum FlagMode
    {
        CHAR, NUM, LONG
    }

    private static final class AffixRule
    {
        final String strip;
        final String add;
        final Pattern condition; // null - условие всегда истинно

        AffixRule(String strip, String add, Pattern condition)
        {
            this.strip = strip;
            this.add = add;
            this.condition = condition;
        }

        boolean conditionMatches(String stem)
        {
            return condition == null || condition.matcher(stem).find();
        }
    }

    private final Map<String, Set<String>> wordFlags = new HashMap<>();
    private final Map<String, List<AffixRule>> suffixRules = new HashMap<>();
    private final Map<String, List<AffixRule>> prefixRules = new HashMap<>();
    private final Set<String> crossProductSuffixFlags = new HashSet<>();
    private final Set<String> crossProductPrefixFlags = new HashSet<>();
    private final Map<String, Boolean> resultCache = new ConcurrentHashMap<>();
    private final ScriptKind script;
    private String forbiddenFlag;
    private FlagMode flagMode = FlagMode.CHAR;

    private HunspellDictionary(ScriptKind script)
    {
        this.script = script != null ? script : ScriptKind.ANY;
    }

    static HunspellDictionary load(File affFile, File dicFile, ScriptKind script) throws IOException
    {
        return load(affFile, dicFile, script, null);
    }

    /**
     * @param dicCharset кодировка {@code .dic}; {@code null} — как у {@code SET} в {@code .aff}
     *            (для comfort-extra UTF-8 при KOI8-R aff AOT)
     */
    static HunspellDictionary load(File affFile, File dicFile, ScriptKind script, Charset dicCharset)
        throws IOException
    {
        HunspellDictionary dict = new HunspellDictionary(script);
        byte[] affBytes = Files.readAllBytes(affFile.toPath());
        Charset affCharset = detectCharset(affBytes);
        dict.parseAff(new String(affBytes, affCharset));
        byte[] dicBytes = Files.readAllBytes(dicFile.toPath());
        Charset forDic = dicCharset != null ? dicCharset : affCharset;
        dict.parseDic(new String(dicBytes, forDic));
        return dict;
    }

    boolean matchesScript(ScriptKind wordScript)
    {
        if (script == ScriptKind.ANY || wordScript == null || wordScript == ScriptKind.ANY)
            return true;
        return script == wordScript;
    }

    private static Charset detectCharset(byte[] affBytes)
    {
        String ascii = new String(affBytes, StandardCharsets.ISO_8859_1);
        for (String line : ascii.split("\r?\n")) //$NON-NLS-1$
        {
            if (line.startsWith("SET ")) //$NON-NLS-1$
            {
                String name = line.substring(4).trim();
                try
                {
                    return Charset.forName(name);
                }
                catch (Exception e)
                {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private void parseAff(String text)
    {
        String[] lines = text.split("\r?\n"); //$NON-NLS-1$
        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i];
            if (line.startsWith("FLAG ")) //$NON-NLS-1$
            {
                String mode = line.substring(5).trim();
                if ("num".equalsIgnoreCase(mode)) //$NON-NLS-1$
                    flagMode = FlagMode.NUM;
                else if ("long".equalsIgnoreCase(mode)) //$NON-NLS-1$
                    flagMode = FlagMode.LONG;
                else
                    flagMode = FlagMode.CHAR;
            }
            else if (line.startsWith("FORBIDDENWORD ")) //$NON-NLS-1$
            {
                forbiddenFlag = line.substring("FORBIDDENWORD ".length()).trim(); //$NON-NLS-1$
            }
            else if (line.startsWith("SFX ") || line.startsWith("PFX ")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                boolean suffix = line.startsWith("SFX "); //$NON-NLS-1$
                String[] header = line.trim().split("\\s+"); //$NON-NLS-1$
                if (header.length < 4)
                    continue;
                String flag = header[1];
                boolean crossProduct = "Y".equalsIgnoreCase(header[2]); //$NON-NLS-1$
                int count;
                try
                {
                    count = Integer.parseInt(header[3]);
                }
                catch (NumberFormatException e)
                {
                    continue;
                }
                if (crossProduct)
                {
                    (suffix ? crossProductSuffixFlags : crossProductPrefixFlags).add(flag);
                }
                List<AffixRule> rules = (suffix ? suffixRules : prefixRules)
                    .computeIfAbsent(flag, k -> new ArrayList<>());
                int read = 0;
                int j = i + 1;
                while (read < count && j < lines.length)
                {
                    String ruleLine = lines[j];
                    j++;
                    String prefixToken = suffix ? "SFX " : "PFX "; //$NON-NLS-1$ //$NON-NLS-2$
                    if (!ruleLine.startsWith(prefixToken))
                        continue;
                    AffixRule rule = parseRuleLine(ruleLine, suffix);
                    if (rule != null)
                        rules.add(rule);
                    read++;
                }
                i = j - 1;
            }
        }
    }

    /**
     * Формат: {@code SFX|PFX flag strip add [condition]}. Пустые strip/add — токен {@code 0}.
     * В en_US.aff поля выровнены несколькими пробелами; схлопываем {@code \\s+}.
     * Условие Hunspell якорится к концу основы (SFX) или началу (PFX).
     */
    private AffixRule parseRuleLine(String line, boolean suffix)
    {
        String[] tokens = line.trim().split("\\s+"); //$NON-NLS-1$
        if (tokens.length < 4)
            return null;
        String strip = normalizeZero(tokens[2]);
        String add = normalizeZero(tokens[3]);
        // continuation/morph flags после '/' нам не нужны
        int slash = add.indexOf('/');
        if (slash >= 0)
            add = add.substring(0, slash);
        String rawCondition = tokens.length >= 5 ? tokens[4] : "."; //$NON-NLS-1$
        if (rawCondition.isEmpty())
            rawCondition = "."; //$NON-NLS-1$
        Pattern condition = null;
        if (!".".equals(rawCondition)) //$NON-NLS-1$
        {
            try
            {
                String anchored = rawCondition;
                if (suffix)
                {
                    if (!anchored.endsWith("$")) //$NON-NLS-1$
                        anchored = anchored + "$"; //$NON-NLS-1$
                }
                else if (!anchored.startsWith("^")) //$NON-NLS-1$
                {
                    anchored = "^" + anchored; //$NON-NLS-1$
                }
                condition = Pattern.compile(anchored);
            }
            catch (PatternSyntaxException e)
            {
                condition = null;
            }
        }
        return new AffixRule(strip, add, condition);
    }

    private static String normalizeZero(String token)
    {
        return "0".equals(token) ? "" : token; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void parseDic(String text)
    {
        String[] lines = text.split("\r?\n"); //$NON-NLS-1$
        boolean countSkipped = false;
        for (int i = 0; i < lines.length; i++)
        {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) //$NON-NLS-1$
                continue;
            // Первая непустая не-комментарий — счётчик слов Hunspell (пропускаем)
            if (!countSkipped)
            {
                countSkipped = true;
                continue;
            }
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx < 0)
                spaceIdx = line.indexOf('\t');
            String entry = spaceIdx < 0 ? line : line.substring(0, spaceIdx);
            int slashIdx = entry.indexOf('/');
            String word;
            Set<String> flags;
            if (slashIdx < 0)
            {
                word = entry;
                flags = Set.of();
            }
            else
            {
                word = entry.substring(0, slashIdx);
                flags = parseFlags(entry.substring(slashIdx + 1));
            }
            if (!word.isEmpty())
                wordFlags.put(word, flags);
        }
    }

    private Set<String> parseFlags(String token)
    {
        if (token.isEmpty())
            return Set.of();
        Set<String> flags = new HashSet<>();
        switch (flagMode)
        {
        case NUM:
            for (String part : token.split(","))
            {
                part = part.trim();
                if (!part.isEmpty())
                    flags.add(part);
            }
            break;
        case LONG:
            for (int i = 0; i + 1 < token.length(); i += 2)
                flags.add(token.substring(i, i + 2));
            break;
        case CHAR:
        default:
            for (int i = 0; i < token.length(); i++)
                flags.add(String.valueOf(token.charAt(i)));
            break;
        }
        return flags;
    }

    boolean isCorrect(String word)
    {
        if (word.isEmpty())
            return true;
        return resultCache.computeIfAbsent(word, this::checkForm);
    }

    /**
     * Превью словоформ по флагу AOT: основа + применение SFX с этим {@code flag}
     * (без приставок). Пустой/неизвестный flag — только лемма.
     */
    List<String> expandForms(String lemma, String flag, int max)
    {
        LinkedHashSet<String> forms = new LinkedHashSet<>();
        if (lemma == null || lemma.isEmpty() || max <= 0)
            return List.of();
        forms.add(lemma);
        if (flag == null || flag.isBlank())
            return new ArrayList<>(forms);
        List<AffixRule> rules = suffixRules.get(flag.trim());
        if (rules == null || rules.isEmpty())
            return new ArrayList<>(forms);
        for (AffixRule rule : rules)
        {
            if (forms.size() >= max)
                break;
            if (!rule.conditionMatches(lemma))
                continue;
            if (!rule.strip.isEmpty() && !lemma.endsWith(rule.strip))
                continue;
            String stem = lemma.substring(0, lemma.length() - rule.strip.length());
            String form = stem + rule.add;
            if (!form.isEmpty())
                forms.add(form);
        }
        return new ArrayList<>(forms);
    }

    /**
     * Варианты исправления: кандидаты на edit distance 1, при нехватке — 2
     * (короткие слова, ограниченный бюджет проверок). Алфавит правок — по script словаря.
     */
    List<String> suggest(String word, int max)
    {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        suggestStreaming(word, max, found::add, null);
        return new ArrayList<>(found);
    }

    /**
     * Потоковая выдача вариантов (для фонового Job + наращивания UI).
     * {@code onSuggestion} вызывается из потока Job, не из UI.
     */
    void suggestStreaming(String word, int max, Consumer<String> onSuggestion, IProgressMonitor monitor)
    {
        if (word == null || word.isEmpty() || max <= 0 || onSuggestion == null)
            return;
        if (isCorrect(word))
            return;
        String letters = lettersForEdits();
        LinkedHashSet<String> found = new LinkedHashSet<>();
        List<String> distance1 = edits1(word, letters);
        collectCorrectEdits(word, distance1, found, max, null, onSuggestion, monitor);
        if (found.isEmpty() && word.length() <= DISTANCE2_MAX_WORD_LEN
            && (monitor == null || !monitor.isCanceled()))
        {
            int[] checks = { 0 };
            for (String e1 : distance1)
            {
                if (found.size() >= max || checks[0] > DISTANCE2_MAX_CHECKS)
                    break;
                if (monitor != null && monitor.isCanceled())
                    break;
                collectCorrectEdits(word, edits1(e1, letters), found, max, checks, onSuggestion, monitor);
            }
        }
    }

    private String lettersForEdits()
    {
        if (script == ScriptKind.CYRILLIC)
            return LETTERS_CYRILLIC;
        if (script == ScriptKind.LATIN)
            return LETTERS_LATIN;
        return LETTERS_CYRILLIC + LETTERS_LATIN;
    }

    private void collectCorrectEdits(String original, List<String> candidates,
        LinkedHashSet<String> found, int max, int[] checks, Consumer<String> onSuggestion,
        IProgressMonitor monitor)
    {
        for (String cand : candidates)
        {
            if (found.size() >= max)
                return;
            if (monitor != null && monitor.isCanceled())
                return;
            if (checks != null)
            {
                checks[0]++;
                if (checks[0] > DISTANCE2_MAX_CHECKS)
                    return;
            }
            if (cand.isEmpty() || cand.equalsIgnoreCase(original))
                continue;
            if (!isCorrect(cand))
                continue;
            String matched = matchCase(original, cand);
            if (found.add(matched) && onSuggestion != null)
                onSuggestion.accept(matched);
        }
    }

    private static List<String> edits1(String word, String letters)
    {
        String w = word.toLowerCase(Locale.ROOT);
        int n = w.length();
        LinkedHashSet<String> edits = new LinkedHashSet<>();
        for (int i = 0; i < n; i++)
            edits.add(w.substring(0, i) + w.substring(i + 1));
        for (int i = 0; i < n - 1; i++)
            edits.add(w.substring(0, i) + w.charAt(i + 1) + w.charAt(i) + w.substring(i + 2));
        for (int i = 0; i < n; i++)
        {
            for (int j = 0; j < letters.length(); j++)
            {
                char c = letters.charAt(j);
                if (w.charAt(i) == c)
                    continue;
                edits.add(w.substring(0, i) + c + w.substring(i + 1));
            }
        }
        for (int i = 0; i <= n; i++)
        {
            for (int j = 0; j < letters.length(); j++)
                edits.add(w.substring(0, i) + letters.charAt(j) + w.substring(i));
        }
        return new ArrayList<>(edits);
    }

    private static String matchCase(String original, String suggestion)
    {
        if (original.isEmpty() || suggestion.isEmpty())
            return suggestion;
        if (original.equals(original.toUpperCase(Locale.ROOT)))
            return suggestion.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(original.charAt(0)))
        {
            if (suggestion.length() == 1)
                return suggestion.toUpperCase(Locale.ROOT);
            return Character.toUpperCase(suggestion.charAt(0)) + suggestion.substring(1);
        }
        return suggestion;
    }

    private boolean checkForm(String word)
    {
        if (checkOneForm(word))
            return true;
        if (Character.isUpperCase(word.charAt(0)))
        {
            String lower = word.substring(0, 1).toLowerCase() + word.substring(1);
            if (checkOneForm(lower))
                return true;
        }
        return false;
    }

    private boolean checkOneForm(String word)
    {
        if (isValidRoot(word, null))
            return true;
        if (checkSuffix(word) != null)
            return true;
        if (checkPrefix(word) != null)
            return true;
        return checkPrefixAndSuffix(word);
    }

    /** @return true, если {@code root} есть в словаре, не помечено как запрещённое, и
     * (если requiredFlag != null) содержит этот флаг. */
    private boolean isValidRoot(String root, String requiredFlag)
    {
        Set<String> flags = wordFlags.get(root);
        if (flags == null)
            return false;
        if (forbiddenFlag != null && flags.contains(forbiddenFlag))
            return false;
        return requiredFlag == null || flags.contains(requiredFlag);
    }

    /** @return корень слова, если подходит правило суффикса, иначе null. */
    private String checkSuffix(String word)
    {
        for (Map.Entry<String, List<AffixRule>> e : suffixRules.entrySet())
        {
            String flag = e.getKey();
            for (AffixRule rule : e.getValue())
            {
                if (!word.endsWith(rule.add))
                    continue;
                int stemEnd = word.length() - rule.add.length();
                if (stemEnd < 0)
                    continue;
                String stem = word.substring(0, stemEnd) + rule.strip;
                if (!rule.conditionMatches(stem))
                    continue;
                if (isValidRoot(stem, flag))
                    return stem;
            }
        }
        return null;
    }

    /** @return корень слова, если подходит правило приставки, иначе null. */
    private String checkPrefix(String word)
    {
        for (Map.Entry<String, List<AffixRule>> e : prefixRules.entrySet())
        {
            String flag = e.getKey();
            for (AffixRule rule : e.getValue())
            {
                if (!word.startsWith(rule.add))
                    continue;
                String stem = rule.strip + word.substring(rule.add.length());
                if (!rule.conditionMatches(stem))
                    continue;
                if (isValidRoot(stem, flag))
                    return stem;
            }
        }
        return null;
    }

    /** Комбинация "приставка + суффикс" — только для правил с cross-product (Y). */
    private boolean checkPrefixAndSuffix(String word)
    {
        for (Map.Entry<String, List<AffixRule>> se : suffixRules.entrySet())
        {
            String suffixFlag = se.getKey();
            if (!crossProductSuffixFlags.contains(suffixFlag))
                continue;
            for (AffixRule sRule : se.getValue())
            {
                if (!word.endsWith(sRule.add))
                    continue;
                int stemEnd = word.length() - sRule.add.length();
                if (stemEnd < 0)
                    continue;
                String afterSuffix = word.substring(0, stemEnd) + sRule.strip;
                if (!sRule.conditionMatches(afterSuffix))
                    continue;
                for (Map.Entry<String, List<AffixRule>> pe : prefixRules.entrySet())
                {
                    String prefixFlag = pe.getKey();
                    if (!crossProductPrefixFlags.contains(prefixFlag))
                        continue;
                    for (AffixRule pRule : pe.getValue())
                    {
                        if (!afterSuffix.startsWith(pRule.add))
                            continue;
                        String root = pRule.strip + afterSuffix.substring(pRule.add.length());
                        if (!pRule.conditionMatches(root))
                            continue;
                        Set<String> flags = wordFlags.get(root);
                        if (flags == null)
                            continue;
                        if (forbiddenFlag != null && flags.contains(forbiddenFlag))
                            continue;
                        if (flags.contains(suffixFlag) && flags.contains(prefixFlag))
                            return true;
                    }
                }
            }
        }
        return false;
    }
}
