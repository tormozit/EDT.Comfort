package tormozit;

import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.custom.StyledText;

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
            LineRangeComparator sourceComparator = new LineRangeComparator(source);
            LineRangeComparator otherComparator = new LineRangeComparator(other);
            RangeDifference[] ranges = RangeDifferencer.findRanges(sourceComparator, otherComparator);

            for (RangeDifference range : ranges)
            {
                int selfStart = range.leftStart();
                int selfLength = range.leftLength();
                if (sourceLine < selfStart || sourceLine >= selfStart + selfLength)
                    continue;

                int otherStart = range.rightStart();
                int otherLength = range.rightLength();
                int relative = sourceLine - selfStart;
                if (relative >= otherLength)
                    return -1;
                return otherStart + relative;
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
