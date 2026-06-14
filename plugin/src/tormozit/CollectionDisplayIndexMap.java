package tormozit;

import java.util.BitSet;

/**
 * O(1) отображение displayIndex виртуальной таблицы → logicalRow после фильтра.
 */
final class CollectionDisplayIndexMap
{
    private static final CollectionDisplayIndexMap EMPTY = new CollectionDisplayIndexMap(new int[0]);

    private final int[] displayToLogical;

    private CollectionDisplayIndexMap(int[] displayToLogical)
    {
        this.displayToLogical = displayToLogical;
    }

    static CollectionDisplayIndexMap empty()
    {
        return EMPTY;
    }

    static CollectionDisplayIndexMap identity(int totalSize)
    {
        if (totalSize <= 0)
            return EMPTY;
        int[] arr = new int[totalSize];
        for (int i = 0; i < totalSize; i++)
            arr[i] = i;
        return new CollectionDisplayIndexMap(arr);
    }

    static CollectionDisplayIndexMap fromBitSet(BitSet matches, int totalSize)
    {
        if (matches == null || totalSize <= 0)
            return EMPTY;
        int visible = matches.cardinality();
        if (visible <= 0)
            return EMPTY;
        int[] arr = new int[visible];
        int display = 0;
        for (int logical = 0; logical < totalSize; logical++)
        {
            if (matches.get(logical))
                arr[display++] = logical;
        }
        return new CollectionDisplayIndexMap(arr);
    }

    static CollectionDisplayIndexMap fromLogicalRows(int[] logicalRowsInDisplayOrder)
    {
        if (logicalRowsInDisplayOrder == null || logicalRowsInDisplayOrder.length == 0)
            return EMPTY;
        return new CollectionDisplayIndexMap(logicalRowsInDisplayOrder.clone());
    }

    int logicalAt(int displayIndex)
    {
        if (displayIndex < 0 || displayIndex >= displayToLogical.length)
            return -1;
        return displayToLogical[displayIndex];
    }

    int displayCount()
    {
        return displayToLogical.length;
    }
}
