package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyledTextContent;
import org.eclipse.swt.custom.TextChangeListener;
import org.eclipse.swt.custom.TextChangedEvent;
import org.eclipse.swt.custom.TextChangingEvent;

/**
 * Построчное сопоставление между двумя {@link StyledText} через публичный
 * {@link RangeDifferencer} (не связано с внутренней diff-моделью merge-вьюеров).
 *
 * <p>Используется в панелях «Текущая строка» — {@link PasteWithCompareActions}
 * (две стороны) и {@link ModuleMergeCurrentLinesHook} (три стороны).
 */
public final class CompareLineRangeMatcher
{
    private static boolean activating;

    private CompareLineRangeMatcher()
    {
    }

    /** Строка курсора (по смещению каретки), либо {@code 0} при ошибке. */
    public static int lineAtCaret(StyledText styledText)
    {
        try
        {
            return styledText.getLineAtOffset(styledText.getCaretOffset());
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    /** Текст строки, либо {@code ""} если индекс вне диапазона или виджет недоступен. */
    public static String lineOrEmpty(StyledText styledText, int line)
    {
        if (styledText == null || styledText.isDisposed() || line < 0 || line >= styledText.getLineCount())
            return ""; //$NON-NLS-1$
        try
        {
            return styledText.getLine(line);
        }
        catch (Exception e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Возвращает номер строки в {@code other}, сопоставленной строке {@code sourceLine}
     * в {@code source} (по построчному diff всего текста обеих сторон), либо
     * {@code -1}, если сопоставленной строки нет (строка внутри блока вставки/удаления,
     * которому не хватает пары на другой стороне).
     */
    public static int findMatchedLine(StyledText source, int sourceLine, StyledText other)
    {
        try
        {
            Matching matching = matchingFor(source, other);
            for (int i = 0; i < matching.sourceStart.length; i++)
            {
                int selfStart = matching.sourceStart[i];
                if (sourceLine < selfStart || sourceLine >= selfStart + matching.sourceLength[i])
                    continue;

                int relative = sourceLine - selfStart;
                if (relative >= matching.otherLength[i])
                    return -1;
                return matching.otherStart[i] + relative;
            }
            return -1;
        }
        catch (Exception e)
        {
            /*
             * StyledText мог измениться (число строк) между чтением содержимого source/other —
             * например, реальный редактор модуля открывается/синхронизируется в фоне, пока эта
             * же панель сравнения ещё активна (см. showInModule) — тогда getLineCount()/getLine(i)
             * внутри LineRangeComparator могут разойтись с фактическим состоянием виджета и
             * бросить SWT-исключение «Index out of bounds». Не даём этому всплыть краше UI.
             */
            return -1;
        }
    }

    private static final String STAMP_KEY = "tormozit.compareLineRangeMatcher.stamp"; //$NON-NLS-1$
    private static final String MATCHINGS_KEY = "tormozit.compareLineRangeMatcher.matchings"; //$NON-NLS-1$
    private static long stampCounter;

    /**
     * Метка содержимого виджета: меняется при любой правке или замене текста. По ней
     * кэшируется всё, что считается по тексту целиком (построчный diff сторон, документ
     * для поиска метода) — пересчёт на каждое движение каретки в большом модуле даёт
     * заметную задержку перемещения.
     */
    static long contentStamp(StyledText text)
    {
        StyledTextContent content = text.getContent();
        Object data = text.getData(STAMP_KEY);
        if (data instanceof ContentStamp stamp)
        {
            if (stamp.content == content)
                return stamp.value;
            stamp.content.removeTextChangeListener(stamp);
        }
        ContentStamp stamp = new ContentStamp(content);
        content.addTextChangeListener(stamp);
        text.setData(STAMP_KEY, stamp);
        return stamp.value;
    }

    private static final class ContentStamp implements TextChangeListener
    {
        final StyledTextContent content;
        long value = ++stampCounter;

        ContentStamp(StyledTextContent content)
        {
            this.content = content;
        }

        @Override
        public void textChanging(TextChangingEvent event)
        {
            // До правки: слушатель самого StyledText стоит раньше нашего и двигает каретку
            // из своего textChanged — к этому моменту метка уже должна быть новой.
            value = ++stampCounter;
        }

        @Override
        public void textChanged(TextChangedEvent event)
        {
            value = ++stampCounter;
        }

        @Override
        public void textSet(TextChangedEvent event)
        {
            value = ++stampCounter;
        }
    }

    /** Построчное сопоставление пары сторон: диапазоны {@code source} и парные им в {@code other}. */
    private record Matching(long sourceStamp, long otherStamp, int sourceLines, int otherLines,
        int[] sourceStart, int[] sourceLength, int[] otherStart, int[] otherLength)
    {
        boolean isValidFor(StyledText source, StyledText other)
        {
            return sourceStamp == contentStamp(source) && otherStamp == contentStamp(other)
                && sourceLines == source.getLineCount() && otherLines == other.getLineCount();
        }

        Matching swapped()
        {
            return new Matching(otherStamp, sourceStamp, otherLines, sourceLines,
                otherStart, otherLength, sourceStart, sourceLength);
        }
    }

    /** Сопоставление из кэша виджета; считается заново только после изменения текста одной из сторон. */
    private static Matching matchingFor(StyledText source, StyledText other)
    {
        Matching cached = matchings(source).get(other);
        if (cached != null && cached.isValidFor(source, other))
            return cached;

        long sourceStamp = contentStamp(source);
        long otherStamp = contentStamp(other);
        // #region agent log — длительность полного построчного сравнения сторон
        long startedNs = System.nanoTime();
        // #endregion
        LineRangeComparator sourceComparator = new LineRangeComparator(source);
        LineRangeComparator otherComparator = new LineRangeComparator(other);
        RangeDifference[] ranges = RangeDifferencer.findRanges(sourceComparator, otherComparator);
        int count = ranges.length;
        int[] sourceStart = new int[count];
        int[] sourceLength = new int[count];
        int[] otherStart = new int[count];
        int[] otherLength = new int[count];
        for (int i = 0; i < count; i++)
        {
            sourceStart[i] = ranges[i].leftStart();
            sourceLength[i] = ranges[i].leftLength();
            otherStart[i] = ranges[i].rightStart();
            otherLength[i] = ranges[i].rightLength();
        }
        Matching matching = new Matching(sourceStamp, otherStamp, sourceComparator.getRangeCount(),
            otherComparator.getRangeCount(), sourceStart, sourceLength, otherStart, otherLength);
        // #region agent log
        Global.tempLog("compare-caret-perf", "diff lines=" + matching.sourceLines + "/" + matching.otherLines //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " ranges=" + count + " ms=" + (System.nanoTime() - startedNs) / 1_000_000); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        matchings(source).put(other, matching);
        matchings(other).put(source, matching.swapped());
        return matching;
    }

    @SuppressWarnings("unchecked")
    private static Map<StyledText, Matching> matchings(StyledText text)
    {
        Object data = text.getData(MATCHINGS_KEY);
        if (data instanceof Map)
            return (Map<StyledText, Matching>) data;
        Map<StyledText, Matching> map = new HashMap<>();
        text.setData(MATCHINGS_KEY, map);
        return map;
    }

    /**
     * Ставит текущую строку {@code target} на {@code targetLine}, выравнивая её по вертикали
     * с текущей строкой {@code source} (тот же отступ от верха видимой области). Каретку
     * источника не трогает; фокус не забирает. Штатная синхронная прокрутка сравнения, если
     * сдвинет источник, откатывается. Пока выполняется, {@link #isActivating()} — {@code true},
     * чтобы слушатели каретки не зациклились.
     */
    static void revealMatchedLine(StyledText source, StyledText target, int targetLine)
    {
        if (activating || source == null || source.isDisposed() || target == null || target.isDisposed())
            return;
        if (targetLine < 0 || targetLine >= target.getLineCount())
            return;
        int sourceLine = lineAtCaret(source);
        int sourceTop = source.getTopIndex();
        int relative = sourceLine - sourceTop;
        int desiredTop = Math.max(0, targetLine - Math.max(0, relative));
        int currentLine = lineAtCaret(target);
        if (currentLine == targetLine && target.getTopIndex() == desiredTop)
            return;

        activating = true;
        try
        {
            if (currentLine != targetLine)
            {
                int offset = target.getOffsetAtLine(targetLine);
                ITextViewer viewer = TextEditorOccurrencesSupport.viewerFor(target);
                if (viewer != null)
                    viewer.setSelectedRange(offset, 0);
                else
                    target.setSelectionRange(offset, 0);
            }
            if (target.getTopIndex() != desiredTop)
                target.setTopIndex(desiredTop);
            if (source.getTopIndex() != sourceTop)
                source.setTopIndex(sourceTop);
        }
        finally
        {
            activating = false;
        }
    }

    /** Идёт программная постановка текущей строки в соседнем поле — не повторять синхронизацию. */
    static boolean isActivating()
    {
        return activating;
    }

    /** Разбивка {@link StyledText} на строки для {@link RangeDifferencer}. */
    private static final class LineRangeComparator implements IRangeComparator
    {
        private final String[] lines;

        LineRangeComparator(StyledText styledText)
        {
            int count = styledText.getLineCount();
            lines = new String[count];
            for (int i = 0; i < count; i++)
                lines[i] = styledText.getLine(i);
        }

        @Override
        public int getRangeCount()
        {
            return lines.length;
        }

        @Override
        public boolean rangesEqual(int thisIndex, IRangeComparator other, int otherIndex)
        {
            return lines[thisIndex].equals(((LineRangeComparator) other).lines[otherIndex]);
        }

        @Override
        public boolean skipRangeComparison(int length, int maxLength, IRangeComparator other)
        {
            return false;
        }
    }
}
