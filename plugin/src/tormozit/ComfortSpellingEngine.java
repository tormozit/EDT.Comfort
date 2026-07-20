package tormozit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.internal.ui.text.spelling.SpellCheckEngine;
import org.eclipse.jdt.internal.ui.text.spelling.SpellCheckIterator;
import org.eclipse.jdt.internal.ui.text.spelling.engine.ISpellChecker;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Region;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Загрузчик словарей Hunspell/MySpell и единые правила токенизации Comfort.
 * Единственный источник диапазонов ошибок — {@link #findMisspelledRanges}
 * (BSL, свойства, сообщение коммита): {@link SpellCheckIterator} + при необходимости
 * {@link #splitIdentifierSegments} (CamelCase / цифры) по флагам «Орфография».
 * {@link #isCorrect} — boolean для штатного DefaultSpellChecker (Java-комментарии и т.п.):
 * те же сегменты, без своих аннотаций.
 * Пути словарей — {@link ComfortSettings#getSpellingDictionaryBasePaths()}.
 * Дополнение Comfort — {@code dictionaries/hunspell/comfort-extra-ru.dic} (UTF-8, леммы + флаги AOT)
 * с морфологией из {@code russian-aot-ieyo.aff}; большой {@code .dic} AOT не правим.
 * Пользовательские слова — {@code spelling-user-dictionary.txt} в stateLocation плагина.
 */
public final class ComfortSpellingEngine
{
    private static final String USER_DICT_FILE = "spelling-user-dictionary.txt"; //$NON-NLS-1$
    private static final String AOT_RU_BASE = "dictionaries/hunspell/russian-aot-ieyo"; //$NON-NLS-1$
    /** Леммы IT/1С; .aff берём у AOT (не дублируем). */
    private static final String EXTRA_RU_DIC_BASE = "dictionaries/hunspell/comfort-extra-ru"; //$NON-NLS-1$
    /** ISO 639 languages + ISO 3166 countries (lowercase). */
    private static volatile Set<String> LOCALE_CODES;
    private static final int SUGGEST_CACHE_MAX = 512;

    private static volatile List<HunspellDictionary> dictionaries;
    private static final Object USER_LOCK = new Object();
    private static volatile Set<String> userWords;
    private static final ConcurrentHashMap<String, List<String>> suggestCache =
        new ConcurrentHashMap<>();

    private ComfortSpellingEngine()
    {
    }

    /** Общий кэш словарей для Platform dictionary. */
    static List<HunspellDictionary> sharedDictionaries()
    {
        List<HunspellDictionary> result = dictionaries;
        if (result == null)
        {
            synchronized (ComfortSpellingEngine.class)
            {
                result = dictionaries;
                if (result == null)
                {
                    result = loadAll();
                    dictionaries = result;
                }
            }
        }
        return result;
    }

    /**
     * Подсказки исправления: сначала словарь того же алфавита (латиница → EN, кириллица → RU),
     * с кэшем. Не гоняем RU-словарь для {@code http} и т.п. — иначе UI подвисает на d2.
     */
    static List<String> suggest(String word, int max)
    {
        if (word == null || word.isEmpty() || max <= 0)
            return List.of();
        String cacheKey = suggestCacheKey(word, max);
        List<String> cached = suggestCache.get(cacheKey);
        if (cached != null)
            return cached;
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        suggestStreaming(word, max, merged::add, null);
        List<String> result = merged.isEmpty() ? List.of() : List.copyOf(merged);
        return cacheSuggest(cacheKey, result);
    }

    /** Кэш hit или {@code null}, если ещё не считали. */
    static List<String> peekSuggestCache(String word, int max)
    {
        if (word == null || word.isEmpty() || max <= 0)
            return null;
        return suggestCache.get(suggestCacheKey(word, max));
    }

    /**
     * Потоковый suggest для фонового Job. По завершении (если не отменён) кладёт
     * накопленный список в кэш.
     */
    static void suggestStreaming(String word, int max, Consumer<String> onSuggestion,
        IProgressMonitor monitor)
    {
        if (word == null || word.isEmpty() || max <= 0 || onSuggestion == null)
            return;
        String cacheKey = suggestCacheKey(word, max);
        List<String> cached = suggestCache.get(cacheKey);
        if (cached != null)
        {
            for (String s : cached)
                onSuggestion.accept(s);
            return;
        }
        HunspellDictionary.ScriptKind script = detectWordScript(word);
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (HunspellDictionary dict : sharedDictionaries())
        {
            if (monitor != null && monitor.isCanceled())
                return;
            if (!dict.matchesScript(script))
                continue;
            dict.suggestStreaming(word, max, s ->
            {
                if (merged.add(s))
                    onSuggestion.accept(s);
            }, monitor);
            if (merged.size() >= max)
                break;
        }
        if (monitor == null || !monitor.isCanceled())
            cacheSuggest(cacheKey, merged.isEmpty() ? List.of() : List.copyOf(merged));
    }

    private static String suggestCacheKey(String word, int max)
    {
        return word.toLowerCase(Locale.ROOT) + '#' + max;
    }

    private static List<String> cacheSuggest(String key, List<String> value)
    {
        if (suggestCache.size() >= SUGGEST_CACHE_MAX)
            suggestCache.clear();
        suggestCache.put(key, value);
        return value;
    }

    static HunspellDictionary.ScriptKind detectWordScript(String word)
    {
        boolean cyrillic = false;
        boolean latin = false;
        for (int i = 0; i < word.length(); i++)
        {
            char c = word.charAt(i);
            if (!Character.isLetter(c))
                continue;
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CYRILLIC
                || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY)
                cyrillic = true;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))
                latin = true;
        }
        if (cyrillic && latin)
            return HunspellDictionary.ScriptKind.ANY;
        if (cyrillic)
            return HunspellDictionary.ScriptKind.CYRILLIC;
        if (latin)
            return HunspellDictionary.ScriptKind.LATIN;
        return HunspellDictionary.ScriptKind.ANY;
    }

    private static HunspellDictionary.ScriptKind scriptFromDictPath(String basePath)
    {
        String lower = basePath.toLowerCase(Locale.ROOT);
        if (lower.contains("en_") || lower.contains("/en") || lower.contains("\\en") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.contains("english")) //$NON-NLS-1$
            return HunspellDictionary.ScriptKind.LATIN;
        if (lower.contains("ru_") || lower.contains("russian") || lower.contains("aot") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || lower.contains("/ru") || lower.contains("\\ru")) //$NON-NLS-1$ //$NON-NLS-2$
            return HunspellDictionary.ScriptKind.CYRILLIC;
        return HunspellDictionary.ScriptKind.ANY;
    }

    static boolean isCorrect(String word)
    {
        if (word == null || word.isEmpty())
            return true;
        if (isShortAllCapsWord(word))
            return true;
        if (isLocaleCode(word))
            return true;
        if (isUserWord(word))
            return true;
        List<HunspellDictionary> dicts = sharedDictionaries();
        if (isCorrect(dicts, word))
            return true;
        // DefaultSpellChecker не дробит CamelCase/цифры — те же условия, что findMisspelledRanges.
        if (!shouldSplitIdentifierToken(word, false))
            return false;
        return isCorrectByIdentifierSegments(word, dicts);
    }

    /**
     * Когда Comfort дробит токен iterator’а на сегменты (как в findMisspelledRangesViaJdt).
     */
    private static boolean shouldSplitIdentifierToken(String word, boolean startsSentence)
    {
        if (word == null || word.isEmpty())
            return false;
        IPreferenceStore prefs = PreferenceConstants.getPreferenceStore();
        if (containsDigit(word) && !prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_DIGITS))
            return true;
        return isMixedCaseWord(word, startsSentence)
            && !prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_MIXED);
    }

    /**
     * {@code ТолстыйКлиент} / {@code item2Name}: все буквенные сегменты ≥2 верны в словаре.
     * Один сегмент или нет буквенных частей — {@code false} (целое уже проверено выше).
     */
    private static boolean isCorrectByIdentifierSegments(String word, List<HunspellDictionary> dicts)
    {
        if (word == null || word.length() < 2 || dicts == null || dicts.isEmpty())
            return false;
        List<int[]> segments = splitIdentifierSegments(word, 0, word.length());
        if (segments.size() <= 1)
            return false;
        boolean anyLetterSeg = false;
        for (int[] seg : segments)
        {
            int segStart = seg[0];
            int segEnd = seg[1];
            if (segEnd - segStart < 2)
                continue;
            String part = word.substring(segStart, segEnd);
            if (!hasLetter(part))
                continue;
            anyLetterSeg = true;
            if (isShortAllCapsWord(part) || isLocaleCode(part) || isUserWord(part))
                continue;
            if (!isCorrect(dicts, part))
                return false;
        }
        return anyLetterSeg;
    }

    /** Слова только из заглавных букв длиной ≤ 3 (аббревиатуры вроде ИД, XML) не проверяем. */
    private static boolean isShortAllCapsWord(String word)
    {
        int len = word.length();
        if (len == 0 || len > 3)
            return false;
        for (int i = 0; i < len; i++)
        {
            char c = word.charAt(i);
            if (!Character.isLetter(c) || !Character.isUpperCase(c))
                return false;
        }
        return true;
    }

    /** Двухбуквенные ISO-коды языков/стран ({@code ru}, {@code en}, {@code ua}, {@code kz}). */
    private static boolean isLocaleCode(String word)
    {
        return word != null && word.length() == 2
            && localeCodeSet().contains(word.toLowerCase(Locale.ROOT));
    }

    private static Set<String> localeCodeSet()
    {
        Set<String> cached = LOCALE_CODES;
        if (cached != null)
            return cached;
        synchronized (ComfortSpellingEngine.class)
        {
            if (LOCALE_CODES != null)
                return LOCALE_CODES;
            Set<String> set = new HashSet<>();
            for (String lang : Locale.getISOLanguages())
            {
                if (lang != null && !lang.isEmpty())
                    set.add(lang.toLowerCase(Locale.ROOT));
            }
            for (String country : Locale.getISOCountries())
            {
                if (country != null && !country.isEmpty())
                    set.add(country.toLowerCase(Locale.ROOT));
            }
            LOCALE_CODES = Set.copyOf(set);
            return LOCALE_CODES;
        }
    }

    /** Слово уже в пользовательском словаре Comfort (без учёта регистра). */
    static boolean isUserWord(String word)
    {
        if (word == null || word.isEmpty())
            return false;
        return userWordSet().contains(normalizeUserWord(word));
    }

    /**
     * Добавить слово в пользовательский словарь (persist в stateLocation).
     *
     * @return {@code true}, если слово было новым
     */
    static boolean addUserWord(String word)
    {
        if (word == null || word.isBlank())
            return false;
        String key = normalizeUserWord(word.trim());
        if (key.isEmpty())
            return false;
        Set<String> set = userWordSet();
        synchronized (USER_LOCK)
        {
            if (!set.add(key))
                return false;
            persistUserWords(set);
            return true;
        }
    }

    /**
     * UI-добавление: словарь + тост «Отменить добавление» (10 с) + пересчёт
     * подчёркиваний для этого слова во всех хуках орфографии.
     *
     * @return {@code true}, если слово было новым
     */
    static boolean addUserWordFromUi(String word)
    {
        if (word == null || word.isBlank())
            return false;
        String displayWord = word.trim();
        if (!addUserWord(displayWord))
            return false;
        suggestCache.clear();
        Runnable ui = () ->
        {
            refreshSpellingAfterUserWordChange(displayWord, true);
            ToastNotification.show(
                "Орфография", //$NON-NLS-1$
                "Слово «" + displayWord + "» добавлено в словарь.", //$NON-NLS-1$ //$NON-NLS-2$
                10_000,
                () -> undoUserWordAdd(displayWord),
                "Отменить добавление"); //$NON-NLS-1$
        };
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return true;
        if (display.getThread() == Thread.currentThread())
            ui.run();
        else
            display.asyncExec(ui);
        return true;
    }

    /** Удалить слово из пользовательского словаря. */
    static boolean removeUserWord(String word)
    {
        if (word == null || word.isBlank())
            return false;
        String key = normalizeUserWord(word.trim());
        if (key.isEmpty())
            return false;
        Set<String> set = userWordSet();
        synchronized (USER_LOCK)
        {
            if (!set.remove(key))
                return false;
            persistUserWords(set);
            return true;
        }
    }

    private static void undoUserWordAdd(String word)
    {
        if (!removeUserWord(word))
            return;
        suggestCache.clear();
        refreshSpellingAfterUserWordChange(word, false);
    }

    /**
     * @param added {@code true} — слово только что добавлено (снять ошибки с этим словом);
     *              {@code false} — отмена (пересчитать заново).
     */
    private static void refreshSpellingAfterUserWordChange(String word, boolean added)
    {
        BslModuleSpellCheckHook.onUserDictionaryChanged(word, added);
        PropertySheetSpellCheckHook.onUserDictionaryChanged(word, added);
        CommitMessageSpellCheckHook.onUserDictionaryChanged(word, added);
    }

    /** Полный пересчёт орфографии после перечитывания файла словаря. */
    private static void refreshSpellingAfterUserDictionaryReload()
    {
        BslModuleSpellCheckHook.onUserDictionaryChanged(null, false);
        PropertySheetSpellCheckHook.onUserDictionaryChanged(null, false);
        CommitMessageSpellCheckHook.onUserDictionaryChanged(null, false);
    }

    private static String normalizeUserWord(String word)
    {
        return word.toLowerCase(Locale.ROOT);
    }

    private static Set<String> userWordSet()
    {
        Set<String> result = userWords;
        if (result == null)
        {
            synchronized (USER_LOCK)
            {
                result = userWords;
                if (result == null)
                {
                    result = ConcurrentHashMap.newKeySet();
                    loadUserWords(result);
                    userWords = result;
                }
            }
        }
        return result;
    }

    private static void loadUserWords(Set<String> into)
    {
        File file = userDictionaryFile();
        if (file == null || !file.isFile())
            return;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) //$NON-NLS-1$
                    continue;
                into.add(normalizeUserWord(trimmed));
            }
        }
        catch (IOException e)
        {
        }
    }

    /**
     * Запись на диск всегда в нижнем регистре и по возрастанию
     * ({@link Collections#sort(List)}).
     */
    private static void persistUserWords(Set<String> words)
    {
        File file = userDictionaryFile();
        if (file == null)
            return;
        try
        {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory())
                parent.mkdirs();
            List<String> sorted = new ArrayList<>(words);
            Collections.sort(sorted);
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)))
            {
                for (String w : sorted)
                {
                    writer.write(w);
                    writer.newLine();
                }
            }
        }
        catch (IOException e)
        {
        }
    }

    /** Файл пользовательского словаря Comfort (может ещё не существовать на диске). */
    static File getUserDictionaryFile()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
            return null;
        IPath location = activator.getStateLocation();
        if (location == null)
            return null;
        return location.append(USER_DICT_FILE).toFile();
    }

    /**
     * Создать файл словаря при отсутствии (пустое отсортированное содержимое)
     * и вернуть путь.
     */
    static File ensureUserDictionaryFile()
    {
        File file = getUserDictionaryFile();
        if (file == null)
            return null;
        synchronized (USER_LOCK)
        {
            Set<String> set = userWordSet();
            if (!file.isFile())
                persistUserWords(set);
        }
        return file;
    }

    /**
     * Перечитать файл в память, нормализовать, перезаписать отсортированным,
     * сбросить suggest-кэш и обновить орфографию в UI.
     */
    static void reloadUserDictionaryFromDisk()
    {
        synchronized (USER_LOCK)
        {
            Set<String> fresh = ConcurrentHashMap.newKeySet();
            loadUserWords(fresh);
            userWords = fresh;
            persistUserWords(fresh);
        }
        suggestCache.clear();
        Runnable ui = ComfortSpellingEngine::refreshSpellingAfterUserDictionaryReload;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            ui.run();
        else
            display.asyncExec(ui);
    }

    private static File userDictionaryFile()
    {
        return getUserDictionaryFile();
    }

    /**
     * Ошибочные диапазоны в {@code text} (относительные offset/length).
     * Штатный {@link SpellCheckIterator} (URL и т.п.) + проверка Hunspell;
     * при выключенном {@code SPELLING_IGNORE_MIXED} / {@code SPELLING_IGNORE_DIGITS} —
     * дробление CamelCase и по цифрам через {@link #splitIdentifierSegments}.
     * Fallback — legacy-разбор.
     */
    static List<int[]> findMisspelledRanges(String text)
    {
        if (text == null || text.isEmpty())
            return List.of();
        List<int[]> viaJdt = findMisspelledRangesViaJdt(text);
        if (viaJdt != null)
            return viaJdt;
        return findMisspelledRangesLegacy(text);
    }

    /**
     * @return список диапазонов или {@code null}, если штатный checker/iterator недоступен
     */
    private static List<int[]> findMisspelledRangesViaJdt(String text)
    {
        try
        {
            ISpellChecker checker = SpellCheckEngine.getInstance().getSpellChecker();
            if (checker == null)
                return null;
            Locale locale = checker.getLocale();
            IPreferenceStore prefs = PreferenceConstants.getPreferenceStore();
            if (locale == null)
            {
                String localeKey = prefs.getString(PreferenceConstants.SPELLING_LOCALE);
                locale = SpellCheckEngine.convertToLocale(localeKey);
            }
            if (locale == null)
                locale = new Locale("ru", "RU"); //$NON-NLS-1$ //$NON-NLS-2$
            IDocument document = new Document(maskAmpersand(text));
            SpellCheckIterator iterator = new SpellCheckIterator(document,
                new Region(0, text.length()), locale, null);
            if (prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_SINGLE_LETTERS))
                iterator.setIgnoreSingleLetters(true);
            boolean ignoreMixed = prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_MIXED);
            boolean ignoreUpper = prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_UPPER);
            boolean ignoreDigits = prefs.getBoolean(PreferenceConstants.SPELLING_IGNORE_DIGITS);
            List<int[]> result = new ArrayList<>();
            while (iterator.hasNext())
            {
                String word = iterator.next();
                if (word == null || word.isEmpty())
                    continue;
                int begin = iterator.getBegin();
                int end = iterator.getEnd();
                if (begin < 0 || end < begin || begin >= text.length())
                    continue;
                int tokenEnd = Math.min(end + 1, text.length());
                if (tokenEnd <= begin)
                    continue;
                boolean hasDigit = containsDigit(word);
                if (ignoreDigits && hasDigit)
                    continue;
                if (ignoreUpper && isAllUpperLetters(word))
                    continue;
                boolean startsSentence = iterator.startsSentence();
                boolean mixed = isMixedCaseWord(word, startsSentence);
                if (mixed && ignoreMixed)
                    continue;
                if (shouldSplitIdentifierToken(word, startsSentence))
                {
                    for (int[] seg : splitIdentifierSegments(text, begin, tokenEnd))
                    {
                        int segStart = seg[0];
                        int segEnd = seg[1];
                        if (segEnd - segStart < 2)
                            continue;
                        String part = text.substring(segStart, segEnd);
                        if (!hasLetter(part))
                            continue;
                        if (isCorrect(part))
                            continue;
                        result.add(new int[] { segStart, segEnd - segStart });
                    }
                }
                else if (word.length() >= 2 && !isCorrect(word))
                {
                    result.add(new int[] { begin, tokenEnd - begin });
                }
            }
            return result;
        }
        catch (IllegalStateException | LinkageError e)
        {
            return null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * {@link SpellCheckIterator} рассчитан на Javadoc и трактует {@code &<буква>} без
     * завершающей {@code ;} как незакрытую HTML-сущность - в этом случае склеивает {@code &}
     * со следующим словом в один токен ({@code "&Ссылка"} вместо {@code "Ссылка"}), и токен не
     * находится в словаре. В BSL {@code &} - синтаксис параметра запроса/директивы, а не начало
     * сущности, поэтому гасим его пробелом (та же длина строки, смещения диапазонов не съезжают)
     * до передачи текста в итератор.
     */
    private static String maskAmpersand(String text)
    {
        if (text.indexOf('&') < 0)
            return text;
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++)
        {
            if (chars[i] == '&')
                chars[i] = ' ';
        }
        return new String(chars);
    }

    /** Как DefaultSpellChecker.isMixedCase: первая заглавная в начале предложения не считается. */
    private static boolean isMixedCaseWord(String word, boolean startsSentence)
    {
        if (word == null || word.length() < 2)
            return false;
        boolean hasLower = false;
        boolean hasUpper = false;
        for (int i = 0; i < word.length(); i++)
        {
            char c = word.charAt(i);
            if (!Character.isLetter(c))
                continue;
            if (Character.isLowerCase(c))
                hasLower = true;
            else if (Character.isUpperCase(c) && (i > 0 || !startsSentence))
                hasUpper = true;
        }
        return hasLower && hasUpper;
    }

    private static boolean isAllUpperLetters(String word)
    {
        boolean any = false;
        for (int i = 0; i < word.length(); i++)
        {
            char c = word.charAt(i);
            if (!Character.isLetter(c))
                continue;
            any = true;
            if (!Character.isUpperCase(c))
                return false;
        }
        return any;
    }

    private static boolean containsDigit(String word)
    {
        for (int i = 0; i < word.length(); i++)
        {
            if (Character.isDigit(word.charAt(i)))
                return true;
        }
        return false;
    }

    /** Fallback без JDT iterator — camelCase-сегменты, без флагов «Орфография». */
    private static List<int[]> findMisspelledRangesLegacy(String text)
    {
        List<int[]> result = new ArrayList<>();
        List<HunspellDictionary> dicts = sharedDictionaries();
        if (dicts.isEmpty())
            return result;
        int start = -1;
        int length = text.length();
        for (int i = 0; i <= length; i++)
        {
            boolean wordChar = i < length && isWordChar(text.charAt(i));
            if (wordChar)
            {
                if (start < 0)
                    start = i;
                continue;
            }
            if (start < 0)
                continue;
            addIfMisspelledLegacy(text, start, i, dicts, result);
            start = -1;
        }
        return result;
    }

    private static void addIfMisspelledLegacy(String text, int start, int end,
        List<HunspellDictionary> dicts, List<int[]> out)
    {
        while (start < end && isTrimChar(text.charAt(start)))
            start++;
        while (end > start && isTrimChar(text.charAt(end - 1)))
            end--;
        if (start >= end)
            return;
        String whole = text.substring(start, end);
        if (!hasLetter(whole))
            return;
        List<int[]> segments = splitIdentifierSegments(text, start, end);
        if (segments.size() <= 1)
        {
            if (whole.length() >= 2 && !isCorrect(dicts, whole))
                out.add(new int[] { start, end - start });
            return;
        }
        for (int[] seg : segments)
        {
            int segStart = seg[0];
            int segEnd = seg[1];
            if (segEnd - segStart < 2)
                continue;
            String word = text.substring(segStart, segEnd);
            if (!hasLetter(word))
                continue;
            if (isCorrect(dicts, word))
                continue;
            out.add(new int[] { segStart, segEnd - segStart });
        }
    }

    /** Слово в диапазоне помечено штатной проверкой как ошибка. */
    static boolean isMisspelledAt(String text, int offset, int length)
    {
        if (text == null || length <= 0 || offset < 0 || offset + length > text.length())
            return false;
        for (int[] r : findMisspelledRanges(text))
        {
            if (r[0] == offset && r[1] == length)
                return true;
            if (offset < r[0] + r[1] && offset + length > r[0])
                return true;
        }
        return false;
    }

    static List<int[]> splitIdentifierSegments(String text, int start, int end)
    {
        List<int[]> segments = new ArrayList<>();
        if (text == null || start >= end)
            return segments;
        int segStart = start;
        for (int i = start + 1; i < end; i++)
        {
            char prev = text.charAt(i - 1);
            char cur = text.charAt(i);
            boolean boundary = false;
            if (isLowerLetter(prev) && isUpperLetter(cur))
                boundary = true;
            else if (Character.isLetter(prev) && Character.isDigit(cur))
                boundary = true;
            else if (Character.isDigit(prev) && Character.isLetter(cur))
                boundary = true;
            else if (isUpperLetter(prev) && isUpperLetter(cur)
                && i + 1 < end && isLowerLetter(text.charAt(i + 1)))
                boundary = true;
            if (boundary)
            {
                segments.add(new int[] { segStart, i });
                segStart = i;
            }
        }
        segments.add(new int[] { segStart, end });
        return segments;
    }

    private static boolean isCorrect(List<HunspellDictionary> dicts, String word)
    {
        if (isShortAllCapsWord(word))
            return true;
        if (isLocaleCode(word))
            return true;
        if (isUserWord(word))
            return true;
        for (HunspellDictionary dict : dicts)
        {
            if (dict.isCorrect(word))
                return true;
        }
        return false;
    }

    private static boolean isUpperLetter(char c)
    {
        return Character.isLetter(c) && Character.isUpperCase(c);
    }

    private static boolean isLowerLetter(char c)
    {
        return Character.isLetter(c) && Character.isLowerCase(c);
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '-' || c == '\'';
    }

    private static boolean isTrimChar(char c)
    {
        return c == '-' || c == '\'';
    }

    private static boolean hasLetter(String word)
    {
        for (int i = 0; i < word.length(); i++)
        {
            if (Character.isLetter(word.charAt(i)))
                return true;
        }
        return false;
    }

    private static List<HunspellDictionary> loadAll()
    {
        List<HunspellDictionary> result = new ArrayList<>();
        for (String basePath : ComfortSettings.getSpellingDictionaryBasePaths())
        {
            if (basePath == null || basePath.isBlank())
                continue;
            String trimmed = basePath.trim();
            try
            {
                File aff = resolveDictionaryFile(trimmed, ".aff"); //$NON-NLS-1$
                File dic = resolveDictionaryFile(trimmed, ".dic"); //$NON-NLS-1$
                if (aff == null || dic == null || !aff.isFile() || !dic.isFile())
                {
                    continue;
                }
                HunspellDictionary.ScriptKind script = scriptFromDictPath(trimmed);
                result.add(HunspellDictionary.load(aff, dic, script));
            }
            catch (Exception e)
            {
            }
        }
        loadComfortExtraRu(result);
        return result.isEmpty() ? Collections.emptyList() : List.copyOf(result);
    }

    /**
     * Леммы Comfort + аффиксы AOT (без своей копии .aff). {@code .dic} — UTF-8.
     */
    private static void loadComfortExtraRu(List<HunspellDictionary> result)
    {
        try
        {
            File aff = resolveDictionaryFile(AOT_RU_BASE, ".aff"); //$NON-NLS-1$
            File dic = resolveDictionaryFile(EXTRA_RU_DIC_BASE, ".dic"); //$NON-NLS-1$
            if (aff == null || dic == null || !aff.isFile() || !dic.isFile())
            {
                return;
            }
            result.add(HunspellDictionary.load(aff, dic, HunspellDictionary.ScriptKind.CYRILLIC,
                StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
        }
    }

    /**
     * Абсолютный путь на диске или относительный путь ресурса бандла
     * {@code tormozit.comfort} (без расширения).
     */
    private static File resolveDictionaryFile(String basePath, String extension)
    {
        File direct = new File(basePath + extension);
        if (direct.isFile())
            return direct;
        Bundle bundle = FrameworkUtil.getBundle(ComfortSpellingEngine.class);
        if (bundle == null)
            return null;
        try
        {
            URL found = FileLocator.find(bundle, new Path(basePath + extension), null);
            if (found == null)
                return null;
            URL fileUrl = FileLocator.toFileURL(found);
            URI uri = new URI(fileUrl.getProtocol(), fileUrl.getPath(), null);
            return new File(uri);
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
