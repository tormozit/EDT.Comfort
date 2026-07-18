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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
    /** Буквы для генерации правок на расстоянии 1–2 (RU + EN). */
    private static final String SUGGEST_LETTERS =
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюяabcdefghijklmnopqrstuvwxyz"; //$NON-NLS-1$

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
    private String forbiddenFlag;
    private FlagMode flagMode = FlagMode.CHAR;

    private HunspellDictionary()
    {
    }

    static HunspellDictionary load(File affFile, File dicFile) throws IOException
    {
        HunspellDictionary dict = new HunspellDictionary();
        byte[] affBytes = Files.readAllBytes(affFile.toPath());
        Charset charset = detectCharset(affBytes);
        dict.parseAff(new String(affBytes, charset));
        byte[] dicBytes = Files.readAllBytes(dicFile.toPath());
        dict.parseDic(new String(dicBytes, charset));
        return dict;
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
                    Global.tempLog("spellCheck", "unknown charset " + name + ", using UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$
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
                    AffixRule rule = parseRuleLine(ruleLine, flag.length() + prefixToken.length());
                    if (rule != null)
                        rules.add(rule);
                    read++;
                }
                i = j - 1;
            }
        }
    }

    private AffixRule parseRuleLine(String line, int afterFlagIndex)
    {
        // Формат: "SFX|PFX flag strip add [condition]"; add может быть пустой строкой
        // (представлена как отсутствующий токен между двумя пробелами), поэтому разбираем
        // без схлопывания повторяющихся пробелов.
        String[] tokens = line.split(" ", -1); //$NON-NLS-1$
        List<String> fields = new ArrayList<>();
        for (int k = 2; k < tokens.length; k++) // tokens[0]=SFX/PFX, tokens[1]=flag
            fields.add(tokens[k]);
        if (fields.size() < 2)
            return null;
        String strip = normalizeZero(fields.get(0));
        String add = normalizeZero(fields.get(1));
        String rawCondition = fields.size() >= 3 ? fields.get(2) : "."; //$NON-NLS-1$
        if (rawCondition.isEmpty())
            rawCondition = "."; //$NON-NLS-1$
        Pattern condition = null;
        if (!".".equals(rawCondition)) //$NON-NLS-1$
        {
            try
            {
                condition = Pattern.compile(rawCondition);
            }
            catch (PatternSyntaxException e)
            {
                Global.tempLog("spellCheck", "bad condition regex: " + rawCondition + " (" + e + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
        for (int i = 1; i < lines.length; i++) // первая строка - количество слов, пропускаем
        {
            String line = lines[i];
            if (line.isEmpty())
                continue;
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
     * Варианты исправления: кандидаты на edit distance 1, при нехватке — 2
     * (для слов не длиннее 12). Регистр подстраивается под исходное слово.
     */
    List<String> suggest(String word, int max)
    {
        if (word == null || word.isEmpty() || max <= 0)
            return List.of();
        if (isCorrect(word))
            return List.of();
        LinkedHashSet<String> found = new LinkedHashSet<>();
        List<String> distance1 = edits1(word);
        collectCorrectEdits(word, distance1, found, max, null);
        // distance 2 только если на d1 пусто — полный перебор d1×d1 слишком тяжёл для UI
        if (found.isEmpty() && word.length() <= 10)
        {
            int[] checks = { 0 };
            for (String e1 : distance1)
            {
                if (found.size() >= max || checks[0] > 25000)
                    break;
                collectCorrectEdits(word, edits1(e1), found, max, checks);
            }
        }
        return new ArrayList<>(found);
    }

    private void collectCorrectEdits(String original, List<String> candidates,
        LinkedHashSet<String> found, int max, int[] checks)
    {
        for (String cand : candidates)
        {
            if (found.size() >= max)
                return;
            if (checks != null)
            {
                checks[0]++;
                if (checks[0] > 25000)
                    return;
            }
            if (cand.isEmpty() || cand.equalsIgnoreCase(original))
                continue;
            if (!isCorrect(cand))
                continue;
            found.add(matchCase(original, cand));
        }
    }

    private static List<String> edits1(String word)
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
            for (int j = 0; j < SUGGEST_LETTERS.length(); j++)
            {
                char c = SUGGEST_LETTERS.charAt(j);
                if (w.charAt(i) == c)
                    continue;
                edits.add(w.substring(0, i) + c + w.substring(i + 1));
            }
        }
        for (int i = 0; i <= n; i++)
        {
            for (int j = 0; j < SUGGEST_LETTERS.length(); j++)
                edits.add(w.substring(0, i) + SUGGEST_LETTERS.charAt(j) + w.substring(i));
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
