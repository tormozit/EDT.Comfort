package tormozit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

/**
 * Имя процедуры/функции, объемлющей заданную строку BSL-модуля — разделяемый резолвер с
 * кэшами. Потребители: колонка «Метод» в списках закладок/задач ({@link MarkersListViewsHook})
 * и колонка «Свойство» в таблице вхождений поиска по конфигурации
 * ({@link ConfigSearchResultsHook}).
 *
 * <p>Сам разбор — лёгкий лексический скан назад от строки ({@link GetRef#findEnclosingMethodName},
 * тот же, что у команды «Копировать ссылку»): ни Xtext, ни EMF-ресурс модуля не поднимаются.
 * Стоимость определяется числом различных модулей, а не числом строк: текст модуля читается и
 * оборачивается в {@link Document} один раз на модуль ({@link #FILE_TEXT_CACHE}), результат по
 * каждой строке кэшируется отдельно ({@link #RESULT_CACHE}, ключ {@code путь#штамп#строка}).
 */
public final class BslModuleMethodResolver
{
    private static final int RESULT_CACHE_LIMIT = 16384;
    private static final int FILE_TEXT_CACHE_LIMIT = 64;
    /** {@code путь#штамп#строка(1)} → имя метода ({@code ""} — вне метода). */
    private static final Map<String, String> RESULT_CACHE = new ConcurrentHashMap<>();
    /** {@code путь#штамп} → текст модуля (чтобы несколько вхождений в одном модуле не читали диск повторно). */
    private static final Map<String, String> FILE_TEXT_CACHE = new ConcurrentHashMap<>();

    private BslModuleMethodResolver()
    {
    }

    /**
     * @return имя процедуры/функции, объемлющей строку {@code line1Based}; {@code ""} — строка вне
     *         метода либо файл не является BSL-модулем; {@code null} — файл не удалось прочитать
     *         (стоит повторить позже, результат не кэшируется).
     */
    static String methodAtLine(IFile file, int line1Based)
    {
        if (!isBslModule(file) || line1Based < 1)
            return ""; //$NON-NLS-1$
        long stamp = file.getModificationStamp();
        String key = resultKey(file, stamp, line1Based);
        String cached = RESULT_CACHE.get(key);
        if (cached != null)
            return cached;

        String text = fileText(file, stamp);
        if (text == null)
            return null;
        String resolved = resolveInText(text, line1Based);
        putBounded(RESULT_CACHE, key, resolved, RESULT_CACHE_LIMIT);
        return resolved;
    }

    /**
     * Уже вычисленное имя метода из кэша, без чтения файла: {@code ""} — вне метода,
     * {@code null} — ещё не вычислялось (или файл не BSL). Для мгновенного восстановления значения
     * при перестроении списка (иначе ячейка мигает «пусто → имя» на каждое обновление).
     */
    static String cachedMethodAtLine(IFile file, int line1Based)
    {
        if (!isBslModule(file) || line1Based < 1)
            return null;
        return RESULT_CACHE.get(resultKey(file, file.getModificationStamp(), line1Based));
    }

    /**
     * Пакетный вариант: текст модуля читается и разбирается один раз для всех запрошенных строк.
     *
     * @return отображение {@code строка(1) → имя метода} ({@code ""} — вне метода) для всех строк
     *         из {@code lines1Based}; {@code null} — файл не удалось прочитать.
     */
    static Map<Integer, String> methodsAtLines(IFile file, Iterable<Integer> lines1Based)
    {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (!isBslModule(file))
        {
            for (Integer line : lines1Based)
                if (line != null)
                    result.put(line, ""); //$NON-NLS-1$
            return result;
        }
        long stamp = file.getModificationStamp();
        boolean anyMissing = false;
        for (Integer line : lines1Based)
        {
            if (line == null || line < 1)
                continue;
            String cached = RESULT_CACHE.get(resultKey(file, stamp, line));
            if (cached != null)
                result.put(line, cached);
            else
                anyMissing = true;
        }
        if (!anyMissing)
            return result;

        String text = fileText(file, stamp);
        if (text == null)
            return null;
        IDocument doc = new Document(text);
        for (Integer line : lines1Based)
        {
            if (line == null || line < 1 || result.containsKey(line))
                continue;
            String name = GetRef.findEnclosingMethodName(doc, line);
            String norm = name != null ? name : ""; //$NON-NLS-1$
            putBounded(RESULT_CACHE, resultKey(file, stamp, line), norm, RESULT_CACHE_LIMIT);
            result.put(line, norm);
        }
        return result;
    }

    static boolean isBslModule(IFile file)
    {
        if (file == null || !file.exists())
            return false;
        String name = file.getName();
        return name != null && name.length() >= 4
            && name.regionMatches(true, name.length() - 4, ".bsl", 0, 4); //$NON-NLS-1$
    }

    private static String resolveInText(String text, int line1Based)
    {
        if (text.isEmpty())
            return ""; //$NON-NLS-1$
        String name = GetRef.findEnclosingMethodName(new Document(text), line1Based);
        return name != null ? name : ""; //$NON-NLS-1$
    }

    private static String resultKey(IFile file, long stamp, int line1Based)
    {
        return file.getFullPath() + "#" + stamp + "#" + line1Based; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String fileText(IFile file, long stamp)
    {
        String key = file.getFullPath() + "#" + stamp; //$NON-NLS-1$
        String cached = FILE_TEXT_CACHE.get(key);
        if (cached != null)
            return cached;
        String text = readFileText(file);
        if (text == null)
            return null;
        putBounded(FILE_TEXT_CACHE, key, text, FILE_TEXT_CACHE_LIMIT);
        return text;
    }

    private static String readFileText(IFile file)
    {
        Charset charset = StandardCharsets.UTF_8;
        try
        {
            String charsetName = file.getCharset(true);
            if (charsetName != null && !charsetName.isBlank())
                charset = Charset.forName(charsetName);
        }
        catch (CoreException | IllegalArgumentException ignored)
        {
            // UTF-8
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(true), charset)))
        {
            StringBuilder sb = new StringBuilder(4096);
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0)
                sb.append(buf, 0, n);
            return sb.toString();
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    private static void putBounded(Map<String, String> cache, String key, String value, int limit)
    {
        if (cache.size() >= limit)
            cache.clear();
        cache.put(key, value);
    }
}
