package tormozit;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Smart-фильтр строк коллекции с BitSet и прогрессом фонового скана.
 */
final class CollectionRowFilter
{
    private final SmartMatcher matcher;
    private final AtomicReference<BitSet> matches = new AtomicReference<>(new BitSet());
    private volatile int progressLoaded;
    private volatile int progressTotal;
    private volatile boolean scanning;
    private volatile boolean cancelled;
    private volatile CollectionDisplayIndexMap displayIndexMap = CollectionDisplayIndexMap.empty();

    CollectionRowFilter(String pattern)
    {
        matcher = new SmartMatcher(pattern);
    }

    SmartMatcher matcher()
    {
        return matcher;
    }

    boolean isActive()
    {
        return !matcher.isEmpty;
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
        displayIndexMap = CollectionDisplayIndexMap.identity(total);
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
            displayIndexMap = CollectionDisplayIndexMap.identity(total);
        else
            displayIndexMap = CollectionDisplayIndexMap.fromBitSet(matches.get(), total);
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
