package tormozit;

import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * Smart-фильтр строк коллекции с BitSet и прогрессом фонового скана.
 *
 * <p>Два слоя отбора, объединяемые по AND (см. {@link #acceptRow}):
 * <ul>
 *   <li>substring — {@link SmartMatcher} по агрегированному тексту строки;</li>
 *   <li>column-value — точное равенство текста ячейки заданным значениям по одной или нескольким
 *       колонкам ({@code visibleCol → value}, AND между колонками).</li>
 * </ul>
 * Состояние column-value хранит окно (переживает пересоздания фильтра); здесь — лишь носитель на
 * время скана, устанавливается через {@link #setColumnFilters} и копируется в {@link #copyFrom}.
 */
final class DebugCollectionRowFilter
{
    private final SmartMatcher matcher;
    private final AtomicReference<BitSet> matches = new AtomicReference<>(new BitSet());
    private volatile int progressLoaded;
    private volatile int progressTotal;
    private volatile boolean scanning;
    private volatile boolean cancelled;
    private volatile boolean presentationOnly;
    /** {@code visibleCol → эталонное значение}; AND между колонками. Пустая — слой снят. */
    private volatile Map<Integer, String> columnFilters = Collections.emptyMap();
    private volatile DebugCollectionDisplayIndexMap displayIndexMap = DebugCollectionDisplayIndexMap.empty();

    DebugCollectionRowFilter(String pattern)
    {
        matcher = new SmartMatcher(pattern);
    }

    static DebugCollectionRowFilter copyFrom(DebugCollectionRowFilter source, String filterText)
    {
        String pattern = filterText != null ? filterText : ""; //$NON-NLS-1$
        if (source == null || !source.isActive())
        {
            DebugCollectionRowFilter copy = new DebugCollectionRowFilter(pattern);
            if (source != null)
            {
                copy.presentationOnly = source.presentationOnly;
                copy.columnFilters = source.columnFilters;
            }
            return copy;
        }
        DebugCollectionRowFilter copy = new DebugCollectionRowFilter(pattern);
        copy.presentationOnly = source.presentationOnly;
        copy.columnFilters = source.columnFilters;
        copy.importFinishedState(source.matches(), source.progressTotal());
        return copy;
    }

    /** Текущий набор column-value фильтров (невозможен после {@code setColumnFilters}). */
    void setColumnFilters(Map<Integer, String> columnFilters)
    {
        this.columnFilters = columnFilters != null && !columnFilters.isEmpty()
            ? new LinkedHashMap<>(columnFilters)
            : Collections.emptyMap();
    }

    boolean hasColumnFilters()
    {
        return !columnFilters.isEmpty();
    }

    /**
     * Проходит ли {@code row} слой column-value: для каждой записи текст ячейки (через
     * {@code cellText}, {@code visibleCol → text}) равен эталонному значению. Вызывается из скана
     * {@code DebugCollectionLoadScheduler.runFilterScan}; {@code cellText} там —
     * {@code col -> model.getCellDisplayText(row, col)}.
     */
    boolean columnFiltersMatch(IntFunction<String> cellText)
    {
        Map<Integer, String> filters = columnFilters;
        if (filters.isEmpty())
            return true;
        for (Map.Entry<Integer, String> entry : filters.entrySet())
        {
            String text = cellText.apply(entry.getKey().intValue());
            if (!entry.getValue().equals(text != null ? text : "")) //$NON-NLS-1$
                return false;
        }
        return true;
    }

    void setPresentationOnly(boolean presentationOnly)
    {
        this.presentationOnly = presentationOnly;
    }

    boolean isPresentationOnly()
    {
        return presentationOnly;
    }

    private void importFinishedState(BitSet sourceMatches, int total)
    {
        cancelled = false;
        scanning = false;
        progressTotal = total;
        progressLoaded = total;
        matches.set(sourceMatches != null ? (BitSet) sourceMatches.clone() : new BitSet(Math.max(total, 1)));
        rebuildDisplayIndexMap();
    }

    SmartMatcher matcher()
    {
        return matcher;
    }

    boolean isActive()
    {
        return !matcher.isEmpty || hasColumnFilters();
    }

    boolean isScanning()
    {
        return scanning;
    }

    int progressLoaded()
    {
        return progressLoaded;
    }

    int progressTotal()
    {
        return progressTotal;
    }

    void beginScan(int total)
    {
        cancelled = false;
        scanning = true;
        progressLoaded = 0;
        progressTotal = total;
        matches.set(new BitSet(Math.max(total, 1)));
        displayIndexMap = DebugCollectionDisplayIndexMap.identity(total);
    }

    void setProgress(int loaded, int total, BitSet partial)
    {
        progressLoaded = loaded;
        progressTotal = total;
        if (partial != null)
            matches.set((BitSet) partial.clone());
    }

    void cancelScan()
    {
        cancelled = true;
        scanning = false;
    }

    boolean isCancelled()
    {
        return cancelled;
    }

    void finishScan(BitSet result)
    {
        if (result != null)
            matches.set((BitSet) result.clone());
        scanning = false;
        rebuildDisplayIndexMap();
    }

    private void rebuildDisplayIndexMap()
    {
        int total = progressTotal;
        if (!isActive() || total <= 0)
            displayIndexMap = DebugCollectionDisplayIndexMap.identity(total);
        else
            displayIndexMap = DebugCollectionDisplayIndexMap.fromBitSet(matches.get(), total);
    }

    BitSet matches()
    {
        return matches.get();
    }

    int visibleCount(int totalSize)
    {
        if (!isActive())
            return totalSize;
        BitSet bs = matches.get();
        return bs.cardinality();
    }

    int logicalRowAtDisplayIndex(int displayIndex, int totalSize)
    {
        if (!isActive())
            return displayIndex >= 0 && displayIndex < totalSize ? displayIndex : -1;
        return displayIndexMap.logicalAt(displayIndex);
    }

    int displayIndexForLogicalRow(int logicalRow, int totalSize)
    {
        if (!isActive())
            return logicalRow;
        BitSet bs = matches.get();
        if (!bs.get(logicalRow))
            return -1;
        return bs.get(0, logicalRow).cardinality();
    }
}
