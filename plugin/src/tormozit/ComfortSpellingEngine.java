package tormozit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.ui.texteditor.spelling.ISpellingEngine;
import org.eclipse.ui.texteditor.spelling.ISpellingProblemCollector;
import org.eclipse.ui.texteditor.spelling.SpellingContext;
import org.eclipse.ui.texteditor.spelling.SpellingProblem;

/**
 * Движок проверки орфографии для EDT на базе словарей Hunspell/MySpell
 * ({@link HunspellDictionary}), т.к. EDT не поставляет ни одной реализации
 * {@code org.eclipse.ui.workbench.texteditor.spellingEngine} (issue 1c-edt-issues#1215).
 * Пути к словарям задаются настройкой {@link ComfortSettings#getSpellingDictionaryBasePaths()}.
 */
public final class ComfortSpellingEngine implements ISpellingEngine
{
    private static volatile List<HunspellDictionary> dictionaries;

    @Override
    public void check(IDocument document, IRegion[] regions, SpellingContext context,
        ISpellingProblemCollector collector, IProgressMonitor monitor)
    {
        List<HunspellDictionary> dicts = getDictionaries();
        collector.beginCollecting();
        try
        {
            for (IRegion region : regions)
            {
                if (monitor != null && monitor.isCanceled())
                    break;
                String word;
                try
                {
                    word = document.get(region.getOffset(), region.getLength());
                }
                catch (BadLocationException e)
                {
                    continue;
                }
                if (!hasLetter(word) || isCorrect(dicts, word))
                    continue;
                collector.accept(new Problem(region.getOffset(), region.getLength(), word));
            }
        }
        finally
        {
            collector.endCollecting();
        }
    }

    private static boolean hasLetter(String word)
    {
        for (int i = 0; i < word.length(); i++)
            if (Character.isLetter(word.charAt(i)))
                return true;
        return false;
    }

    private static boolean isCorrect(List<HunspellDictionary> dicts, String word)
    {
        for (HunspellDictionary dict : dicts)
        {
            if (dict.isCorrect(word))
                return true;
        }
        return false;
    }

    private static List<HunspellDictionary> getDictionaries()
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
            if (basePath.isBlank())
                continue;
            try
            {
                File aff = new File(basePath + ".aff"); //$NON-NLS-1$
                File dic = new File(basePath + ".dic"); //$NON-NLS-1$
                if (!aff.isFile() || !dic.isFile())
                {
                    Global.tempLog("spellCheck", "словарь не найден: " + basePath); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                result.add(HunspellDictionary.load(aff, dic));
                Global.tempLog("spellCheck", "словарь загружен: " + basePath); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (Exception e)
            {
                Global.tempLog("spellCheck", "ошибка загрузки " + basePath + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return result;
    }

    private static final class Problem extends SpellingProblem
    {
        private final int offset;
        private final int length;
        private final String word;

        Problem(int offset, int length, String word)
        {
            this.offset = offset;
            this.length = length;
            this.word = word;
        }

        @Override
        public int getOffset()
        {
            return offset;
        }

        @Override
        public int getLength()
        {
            return length;
        }

        @Override
        public String getMessage()
        {
            return "Слово \"" + word + "\" отсутствует в словаре"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public ICompletionProposal[] getProposals()
        {
            return new ICompletionProposal[0];
        }
    }
}
