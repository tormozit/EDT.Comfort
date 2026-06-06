package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com._1c.g5.v8.dt.common.FuzzyPattern;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Доступ к штатному {@code FuzzySearchHelper} EDT (синонимы, комментарии, подсказки по EMF-путям).
 */
public final class NavigatorFuzzySearch
{
    private static final String[] TOOL_TIP_METHODS = {
            "getToolTip", "getTooltip" //$NON-NLS-1$ //$NON-NLS-2$
    };

    private NavigatorFuzzySearch() {}

    public static List<String> collectSearchTexts(MdObject mdObject)
    {
        if (mdObject == null)
            return Collections.emptyList();

        List<String> texts = new ArrayList<>();
        addToken(texts, mdObject.getName());
        texts.addAll(collectHiddenTexts(mdObject));
        return texts;
    }

    /** Скрытые поля объекта (без имени) — прямые геттеры и extension, без EMF-обхода. */
    public static List<String> hiddenAttributeTexts(MdObject mdObject)
    {
        return new ArrayList<>(collectHiddenTexts(mdObject));
    }

    private static Set<String> collectHiddenTexts(MdObject mdObject)
    {
        if (mdObject == null)
            return Collections.emptySet();

        Set<String> texts = new LinkedHashSet<>();
        addToken(texts, mdObject.getComment());
        appendTextValue(texts, mdObject.getSynonym());
        addToken(texts, localizedSynonym(mdObject));
        appendToolTipFeatures(texts, mdObject);
        appendExtensionTexts(texts, mdObject);
        appendFeature(texts, mdObject, "getExplanation"); //$NON-NLS-1$
        return texts;
    }

    /** Подсказка на объекте и в extension (у реквизитов — {@code getExtension().getTooltip()}). */
    private static void appendToolTipFeatures(Set<String> texts, Object target)
    {
        if (target == null)
            return;
        for (String method : TOOL_TIP_METHODS)
            appendFeature(texts, target, method);
    }

    private static void appendExtensionTexts(Set<String> texts, MdObject mdObject)
    {
        Object extension = Global.invoke(mdObject, "getExtension"); //$NON-NLS-1$
        if (extension == null)
            return;
        appendToolTipFeatures(texts, extension);
        appendFeature(texts, extension, "getExplanation"); //$NON-NLS-1$
        appendFeature(texts, extension, "getHelp"); //$NON-NLS-1$
        appendFeature(texts, extension, "getComment"); //$NON-NLS-1$
    }

    public static String joinSearchTexts(MdObject mdObject)
    {
        List<String> texts = collectSearchTexts(mdObject);
        StringBuilder sb = new StringBuilder();
        for (String text : texts)
            appendToken(sb, text);
        return sb.toString().trim();
    }

    /** Лучший фрагмент для серого квалификатора справа от имени (не само имя). */
    public static QualifierMatch findQualifierMatch(MdObject mdObject, String pattern, String objectName)
    {
        if (mdObject == null || pattern == null || pattern.trim().isEmpty())
            return null;

        String safeName = objectName != null ? objectName.trim() : ""; //$NON-NLS-1$
        QualifierMatch best = null;

        for (String hidden : collectHiddenTexts(mdObject))
        {
            QualifierMatch candidate = tryQualifier(hidden, pattern, safeName);
            if (candidate != null && (best == null || candidate.score > best.score))
                best = candidate;
        }
        return best;
    }

    private static QualifierMatch tryQualifier(String source, String pattern, String objectName)
    {
        if (source == null)
            return null;
        String text = source.trim();
        if (text.isEmpty() || text.equalsIgnoreCase(objectName))
            return null;

        SmartMatcher matcher = new SmartMatcher(pattern);
        if (matcher.isEmpty)
            return null;

        // Многословный паттерн: в квалификаторе достаточно фрагментов из этого поля,
        // не совпадающих с именем (остальные токены могут быть только в имени).
        List<String> hiddenFragments = matcher.fragmentsInNotIn(text, objectName);
        if (!hiddenFragments.isEmpty())
            return buildQualifierMulti(text, hiddenFragments);

        if (!matcher.matches(text))
            return null;

        if (matcher.getFragments().length == 1)
        {
            FuzzyPattern.Match match = fuzzyMatch(pattern, text);
            if (match != null)
                return buildQualifier(text, match);
        }
        return null;
    }

    private static QualifierMatch buildQualifier(String source, FuzzyPattern.Match match)
    {
        List<int[]> ranges = readMatchRanges(match, source.length());
        if (ranges.isEmpty())
            return null;
        return buildQualifierFromRanges(source, ranges);
    }

    /** Слова с вхождением + по одному соседнему слову слева/справа. */
    private static QualifierMatch buildQualifierMulti(String source, List<String> fragments)
    {
        if (source == null || fragments == null || fragments.isEmpty())
            return null;

        List<int[]> ranges = new ArrayList<>();
        for (String frag : fragments)
        {
            int[] span = findFragmentSpan(source, frag);
            if (span != null)
                ranges.add(span);
        }
        if (ranges.isEmpty())
            return null;
        return buildQualifierFromRanges(source, ranges);
    }

    private static QualifierMatch buildQualifierFromRanges(String source, List<int[]> matchRanges)
    {
        if (source == null || matchRanges == null || matchRanges.isEmpty())
            return null;

        List<WordSpan> words = parseWords(source);
        if (words.isEmpty())
            return null;

        Set<Integer> included = collectIncludedWordIndices(words, source, matchRanges);
        if (included.isEmpty())
            return null;

        List<Integer> sorted = new ArrayList<>(included);
        Collections.sort(sorted);
        List<int[]> runs = groupConsecutiveIndices(sorted);

        StringBuilder display = new StringBuilder("... "); //$NON-NLS-1$
        List<DisplaySegment> segments = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++)
        {
            if (r > 0)
                display.append(" ... "); //$NON-NLS-1$

            int[] run = runs.get(r);
            int charStart = words.get(run[0]).start;
            int charEnd = words.get(run[1]).end;
            segments.add(new DisplaySegment(charStart, charEnd, display.length()));
            display.append(source, charStart, charEnd);
        }

        int lastWord = sorted.get(sorted.size() - 1);
        if (words.get(lastWord).end < source.length())
            display.append(" ..."); //$NON-NLS-1$

        String displayStr = display.toString();
        List<int[]> displayRanges = mapMatchRangesToDisplay(matchRanges, segments);
        if (displayRanges.isEmpty())
            return null;

        int score = 0;
        for (int[] range : displayRanges)
            score += range[1];
        return new QualifierMatch(displayStr, displayRanges, score);
    }

    private static List<WordSpan> parseWords(String source)
    {
        List<WordSpan> words = new ArrayList<>();
        int index = 0;
        while (index < source.length())
        {
            while (index < source.length() && Character.isWhitespace(source.charAt(index)))
                index++;
            if (index >= source.length())
                break;
            int start = index;
            while (index < source.length() && !Character.isWhitespace(source.charAt(index)))
                index++;
            words.add(new WordSpan(start, index));
        }
        return words;
    }

    private static Set<Integer> collectIncludedWordIndices(List<WordSpan> words, String source,
            List<int[]> matchRanges)
    {
        Set<Integer> included = new TreeSet<>();
        for (int[] range : matchRanges)
        {
            int rangeStart = range[0];
            int rangeEnd = range[0] + range[1];
            for (int wordIndex = 0; wordIndex < words.size(); wordIndex++)
            {
                WordSpan word = words.get(wordIndex);
                if (word.end <= rangeStart || word.start >= rangeEnd)
                    continue;

                included.add(wordIndex);
                if (wordIndex > 0)
                    included.add(wordIndex - 1);
                if (wordIndex < words.size() - 1)
                    included.add(wordIndex + 1);
            }
        }
        return included;
    }

    private static List<int[]> groupConsecutiveIndices(List<Integer> sorted)
    {
        List<int[]> runs = new ArrayList<>();
        int runStart = sorted.get(0);
        int runEnd = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++)
        {
            int value = sorted.get(i);
            if (value == runEnd + 1)
                runEnd = value;
            else
            {
                runs.add(new int[] { runStart, runEnd });
                runStart = runEnd = value;
            }
        }
        runs.add(new int[] { runStart, runEnd });
        return runs;
    }

    private static List<int[]> mapMatchRangesToDisplay(List<int[]> matchRanges, List<DisplaySegment> segments)
    {
        List<int[]> displayRanges = new ArrayList<>();
        for (int[] range : matchRanges)
        {
            int matchStart = range[0];
            int matchEnd = range[0] + range[1];
            for (DisplaySegment segment : segments)
            {
                if (matchEnd <= segment.sourceStart || matchStart >= segment.sourceEnd)
                    continue;

                int overlapStart = Math.max(matchStart, segment.sourceStart);
                int overlapEnd = Math.min(matchEnd, segment.sourceEnd);
                int displayStart = segment.displayStart + (overlapStart - segment.sourceStart);
                int displayLen = overlapEnd - overlapStart;
                if (displayLen > 0)
                    displayRanges.add(new int[] { displayStart, displayLen });
            }
        }
        return displayRanges;
    }

    private static final class WordSpan
    {
        final int start;
        final int end;

        WordSpan(int start, int end)
        {
            this.start = start;
            this.end = end;
        }
    }

    private static final class DisplaySegment
    {
        final int sourceStart;
        final int sourceEnd;
        final int displayStart;

        DisplaySegment(int sourceStart, int sourceEnd, int displayStart)
        {
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
            this.displayStart = displayStart;
        }
    }

    private static int[] findFragmentSpan(String source, String fragment)
    {
        if (source == null || fragment == null || fragment.isEmpty())
            return null;
        int idx = source.toLowerCase().indexOf(fragment.toLowerCase());
        if (idx >= 0)
            return new int[] { idx, fragment.length() };

        FuzzyPattern.Match match = fuzzyMatch(fragment, source);
        if (match == null)
            return null;
        List<int[]> ranges = readMatchRanges(match, source.length());
        if (ranges.isEmpty())
            return null;
        return ranges.get(0);
    }

    private static FuzzyPattern.Match fuzzyMatch(String pattern, String sample)
    {
        if (pattern == null || sample == null)
            return null;
        try
        {
            return new FuzzyPattern(pattern).match(sample, isRu());
        }
        catch (Exception ignored) {}
        return null;
    }

    private static List<int[]> readMatchRanges(FuzzyPattern.Match match, int textLength)
    {
        if (match == null || match.getRanges() == null)
            return Collections.emptyList();

        List<int[]> result = new ArrayList<>();
        for (FuzzyPattern.Match.Range range : match.getRanges())
        {
            int off = rangeOffset(range);
            int len = rangeLength(range);
            if (off >= 0 && len > 0 && off + len <= textLength)
                result.add(new int[] { off, len });
        }
        return result;
    }

    private static int rangeOffset(FuzzyPattern.Match.Range range)
    {
        Object off = Global.invoke(range, "getStart"); //$NON-NLS-1$
        if (!(off instanceof Integer))
            off = Global.invoke(range, "getOffset"); //$NON-NLS-1$
        return off instanceof Integer ? (Integer) off : -1;
    }

    private static int rangeLength(FuzzyPattern.Match.Range range)
    {
        Object len = Global.invoke(range, "getLength"); //$NON-NLS-1$
        return len instanceof Integer ? (Integer) len : 0;
    }

    private static String localizedSynonym(MdObject mdObject)
    {
        try
        {
            Class<?> mdUtil = Class.forName("com._1c.g5.v8.dt.md.MdUtil"); //$NON-NLS-1$
            Object value = Global.invoke(mdUtil, "getSynonym", mdObject); //$NON-NLS-1$
            return value instanceof String ? (String) value : null;
        }
        catch (ClassNotFoundException ignored)
        {
            return null;
        }
    }

    private static boolean isRu()
    {
        String lang = System.getProperty("user.language", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return lang == null || lang.isEmpty() || lang.toLowerCase().startsWith("ru"); //$NON-NLS-1$
    }

    private static void appendFeature(List<String> texts, Object target, String method)
    {
        Object value = Global.invoke(target, method);
        appendTextValue(texts, value);
    }

    private static void appendTextValue(List<String> texts, Object value)
    {
        appendTextValueImpl(value, v -> addToken(texts, v));
    }

    private static void addToken(List<String> texts, String token)
    {
        if (token == null)
            return;
        String trimmed = token.trim();
        if (!trimmed.isEmpty())
            texts.add(trimmed);
    }

    private static void addToken(Set<String> texts, String token)
    {
        if (token == null)
            return;
        String trimmed = token.trim();
        if (!trimmed.isEmpty())
            texts.add(trimmed);
    }

    private static void appendTextValue(Set<String> texts, Object value)
    {
        appendTextValueImpl(value, v -> addToken(texts, v));
    }

    private interface TextConsumer
    {
        void accept(String text);
    }

    private static void appendTextValueImpl(Object value, TextConsumer consumer)
    {
        if (value == null)
            return;
        if (value instanceof String)
        {
            String trimmed = ((String) value).trim();
            if (!trimmed.isEmpty())
                consumer.accept(trimmed);
            return;
        }
        if (value instanceof Map<?, ?>)
        {
            for (Object entryValue : ((Map<?, ?>) value).values())
                appendTextValueImpl(entryValue, consumer);
            return;
        }
        if (value instanceof Iterable<?> && !(value instanceof String))
        {
            for (Object item : (Iterable<?>) value)
                appendTextValueImpl(item, consumer);
            return;
        }

        Object extracted = Global.invoke(value, "getValue"); //$NON-NLS-1$
        if (extracted != null && extracted != value)
            appendTextValueImpl(extracted, consumer);
    }

    private static void appendFeature(Set<String> texts, Object target, String method)
    {
        appendTextValue(texts, Global.invoke(target, method));
    }

    private static void appendToken(StringBuilder sb, String token)
    {
        if (token == null)
            return;
        String trimmed = token.trim();
        if (trimmed.isEmpty())
            return;
        if (sb.length() > 0)
            sb.append(' ');
        sb.append(trimmed);
    }

    public static final class QualifierMatch
    {
        public final String text;
        public final List<int[]> ranges;
        public final int score;

        QualifierMatch(String text, List<int[]> ranges, int score)
        {
            this.text = text;
            this.ranges = ranges;
            this.score = score;
        }
    }
}
