package tormozit;

import java.util.ArrayList;
import java.util.List;

/**
 * Фильтр для выпадающего списка поля «Тип» в мастерах создания объектов метаданных
 * (см. {@code TypeComboOverlayHook}).
 *
 * <p>Текст фильтра разбивается точкой {@code .} на секции, каждая секция — пробелом на слова.
 * Секции сопоставляются с точечными сегментами текста элемента списка, начиная с конца:
 * последняя секция фильтра должна найтись (все слова — простым вхождением подстроки,
 * без учёта регистра) в последнем сегменте текста, предпоследняя секция — в предпоследнем
 * сегменте текста и так далее. Секций фильтра не может быть больше, чем сегментов текста.
 * Более ранние (незатронутые) сегменты текста ни на что не влияют — например, текст фильтра
 * «вал» найдёт «СправочникСсылка.Валюты», а «справ.вал» найдёт то же самое, но не найдёт
 * «РегистрСведений.Валюты».
 */
public final class SectionMatcher
{
    private final String[][] sections;
    public final boolean isEmpty;

    public SectionMatcher(String filterPattern)
    {
        if (filterPattern == null || filterPattern.isBlank())
        {
            this.sections = new String[0][];
            this.isEmpty = true;
            return;
        }
        List<String[]> parsed = new ArrayList<>();
        for (String rawSection : filterPattern.split("\\.", -1)) //$NON-NLS-1$
        {
            String[] words = splitWords(rawSection);
            if (words.length > 0)
                parsed.add(words);
        }
        this.sections = parsed.toArray(new String[0][]);
        this.isEmpty = this.sections.length == 0;
    }

    private static String[] splitWords(String text)
    {
        String trimmed = text.trim().toLowerCase();
        if (trimmed.isEmpty())
            return new String[0];
        return trimmed.split("\\s+"); //$NON-NLS-1$
    }

    public boolean matches(String text)
    {
        if (isEmpty)
            return true;
        if (text == null || text.isEmpty())
            return false;

        String[] segments = text.split("\\.", -1); //$NON-NLS-1$
        if (sections.length > segments.length)
            return false;

        int segIdx = segments.length - 1;
        for (int secIdx = sections.length - 1; secIdx >= 0; secIdx--, segIdx--)
        {
            String lowerSegment = segments[segIdx].toLowerCase();
            for (String word : sections[secIdx])
            {
                if (!lowerSegment.contains(word))
                    return false;
            }
        }
        return true;
    }

    /**
     * Диапазоны (offset, length в исходном {@code text}) найденных слов — для подсветки.
     * Предполагает, что {@link #matches(String)} для этого текста уже вернул {@code true}.
     */
    public List<int[]> getHighlightRanges(String text)
    {
        List<int[]> ranges = new ArrayList<>();
        if (isEmpty || text == null || text.isEmpty())
            return ranges;

        String[] segments = text.split("\\.", -1); //$NON-NLS-1$
        if (sections.length > segments.length)
            return ranges;

        int[] segmentStart = new int[segments.length];
        int pos = 0;
        for (int i = 0; i < segments.length; i++)
        {
            segmentStart[i] = pos;
            pos += segments[i].length() + 1; // +1 за точку-разделитель
        }

        int segIdx = segments.length - 1;
        for (int secIdx = sections.length - 1; secIdx >= 0; secIdx--, segIdx--)
        {
            String segment = segments[segIdx];
            String lowerSegment = segment.toLowerCase();
            for (String word : sections[secIdx])
            {
                int idx = lowerSegment.indexOf(word);
                if (idx >= 0)
                    ranges.add(new int[] { segmentStart[segIdx] + idx, word.length() });
            }
        }
        return ranges;
    }
}
