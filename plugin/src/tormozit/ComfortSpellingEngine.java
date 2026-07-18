package tormozit;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Загрузчик словарей Hunspell/MySpell для виртуального Platform dictionary
 * ({@link SpellCheckHook}). Пути — {@link ComfortSettings#getSpellingDictionaryBasePaths()}
 * (по умолчанию из бандла плагина).
 */
public final class ComfortSpellingEngine
{
    private static volatile List<HunspellDictionary> dictionaries;

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
