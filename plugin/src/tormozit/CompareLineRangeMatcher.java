package tormozit;

import org.eclipse.compare.rangedifferencer.IRangeComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
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
            Global.tempLogException("showInModule", "CompareLineRangeMatcher.findMatchedLine", e); //$NON-NLS-1$ //$NON-NLS-2$
            return -1;
        }
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
