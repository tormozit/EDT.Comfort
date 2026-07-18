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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Загрузчик словарей Hunspell/MySpell и общая токенизация слов (camelCase)
 * для Platform dictionary, панели «Свойства» и орфографии модуля BSL.
 * Пути — {@link ComfortSettings#getSpellingDictionaryBasePaths()}.
 * Пользовательские слова — {@code spelling-user-dictionary.txt} в stateLocation плагина.
 */
public final class ComfortSpellingEngine
{
    private static final String USER_DICT_FILE = "spelling-user-dictionary.txt"; //$NON-NLS-1$

    private static volatile List<HunspellDictionary> dictionaries;
    private static final Object USER_LOCK = new Object();
    private static volatile Set<String> userWords;

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

    /** Подсказки исправления по всем загруженным словарям (порядок — RU, затем EN). */
    static List<String> suggest(String word, int max)
    {
        if (word == null || word.isEmpty() || max <= 0)
            return List.of();
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (HunspellDictionary dict : sharedDictionaries())
        {
            for (String s : dict.suggest(word, max))
            {
                merged.add(s);
                if (merged.size() >= max)
                    return List.copyOf(merged);
            }
        }
        return merged.isEmpty() ? List.of() : List.copyOf(merged);
    }

    static boolean isCorrect(String word)
    {
        if (word == null || word.isEmpty())
            return true;
        if (isUserWord(word))
            return true;
        List<HunspellDictionary> dicts = sharedDictionaries();
        for (HunspellDictionary dict : dicts)
        {
            if (dict.isCorrect(word))
                return true;
        }
        return false;
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
            Global.tempLog("spellCheck", "user-dict load: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

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
            Global.tempLog("spellCheck", "user-dict save: " + e); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static File userDictionaryFile()
    {
        return getUserDictionaryFile();
    }

    /**
     * Ошибочные диапазоны в {@code text} (относительные offset/length), с разбиением
     * Pascal/camelCase и letter↔digit.
     */
    static List<int[]> findMisspelledRanges(String text)
    {
        List<int[]> result = new ArrayList<>();
        if (text == null || text.isEmpty())
            return result;
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
            addIfMisspelled(text, start, i, dicts, result);
            start = -1;
        }
        return result;
    }

    private static void addIfMisspelled(String text, int start, int end, List<HunspellDictionary> dicts,
        List<int[]> out)
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
                    Global.tempLog("spellCheck", "словарь не найден: " + trimmed); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                result.add(HunspellDictionary.load(aff, dic));
                Global.tempLog("spellCheck", "словарь загружен: " + trimmed //$NON-NLS-1$ //$NON-NLS-2$
                    + " (" + aff.getAbsolutePath() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Exception e)
            {
                Global.tempLog("spellCheck", "ошибка загрузки " + trimmed + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return result.isEmpty() ? Collections.emptyList() : List.copyOf(result);
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
            Global.tempLog("spellCheck", "resolveDictionaryFile " + basePath + extension //$NON-NLS-1$ //$NON-NLS-2$
                + ": " + e); //$NON-NLS-1$
            return null;
        }
    }
}
