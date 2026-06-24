package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.debug.core.DebugException;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Кэш строк и ячеек коллекции; все runtime-вызовы — только из {@link DebugCollectionLoadScheduler}.
 */
final class DebugCollectionTableModel
{
    static final String PLACEHOLDER = "…"; //$NON-NLS-1$

    static final class CellKey
    {
        final int row;
        final int col;

        CellKey(int row, int col)
        {
            this.row = row;
            this.col = col;
        }

        @Override
        public int hashCode()
        {
            return row * 31 + col;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (!(obj instanceof CellKey other))
                return false;
            return row == other.row && col == other.col;
        }
    }

    static final class CellData
    {
        String baseText = ""; //$NON-NLS-1$
        String displayText = ""; //$NON-NLS-1$
        SizeState sizeState = SizeState.UNKNOWN;
        int nestedSize = -1;
        /** Ячейка хотя бы раз заполнена Job-ом (в т.ч. пустым значением). */
        boolean contentLoaded;
    }

    enum SizeState
    {
        UNKNOWN, PENDING, READY, NA
    }

    private static final int CELL_CACHE_LIMIT_MIN = 12000;

    /** Ёмкость под автоподгрузку browse: rows×cols + запас; иначе LRU вытесняет viewport и цикл 0→N→0. */
    int cellCacheCapacity()
    {
        int rows = totalSize > 0
            ? Math.min(totalSize, DebugCollectionLoadScheduler.AUTO_LOAD_ROW_LIMIT)
            : 512;
        int cols = Math.max(1, columns.columnCount());
        long need = (long) rows * cols + 512L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(CELL_CACHE_LIMIT_MIN, need));
    }
    private static final int ROW_CHILDREN_CACHE_LIMIT = 2000;

    final IBslIndexedValue indexedValue;
    final IBslStackFrame frame;
    final BslValuePath path;
    final DebugCollectionColumnModel columns;
    final String typeTitle;

    volatile int totalSize = -1;
    volatile int loadedRowCount;

    private final ConcurrentHashMap<Integer, IBslVariable> rowVariables = new ConcurrentHashMap<>();
    private final Map<Integer, IBslVariable[]> rowChildrenCache =
        new LinkedHashMap<>(128, 0.75f, true)
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, IBslVariable[]> eldest)
            {
                return size() > ROW_CHILDREN_CACHE_LIMIT;
            }
        };
    private final Map<CellKey, CellData> cellCache =
        new LinkedHashMap<>(256, 0.75f, true)
        {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<CellKey, CellData> eldest)
            {
                return size() > cellCacheCapacity();
            }
        };

    DebugCollectionTableModel(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        DebugCollectionColumnModel columns,
        String typeTitle)
    {
        this.indexedValue = indexedValue;
        this.frame = frame;
        this.path = path;
        this.columns = columns;
        this.typeTitle = typeTitle != null ? typeTitle : "Коллекция"; //$NON-NLS-1$
    }

    String pathKey()
    {
        if (path == null)
            return ""; //$NON-NLS-1$
        String expr = path.getExpression();
        if (expr != null && !expr.isBlank())
            return expr.trim();
        String text = path.toString();
        return text != null ? text.trim() : ""; //$NON-NLS-1$
    }

    IBslVariable getRowVariable(int logicalRow)
    {
        return rowVariables.get(logicalRow);
    }

    void putRowVariables(int from, IBslVariable[] vars)
    {
        if (vars == null)
            return;
        for (int i = 0; i < vars.length; i++)
        {
            IBslVariable v = vars[i];
            if (v != null)
                rowVariables.put(from + i, v);
        }
        loadedRowCount = Math.max(loadedRowCount, from + vars.length);
    }

    void importCachesFrom(DebugCollectionTableModel source)
    {
        if (source == null || source == this)
            return;
        totalSize = source.totalSize;
        loadedRowCount = source.loadedRowCount;
        rowVariables.putAll(source.rowVariables);
        rowChildrenCache.putAll(source.rowChildrenCache);
        synchronized (source.cellCache)
        {
            synchronized (cellCache)
            {
                cellCache.clear();
                for (Map.Entry<CellKey, CellData> entry : source.cellCache.entrySet())
                {
                    CellKey key = entry.getKey();
                    cellCache.put(new CellKey(key.row, key.col), cloneCellData(entry.getValue()));
                }
            }
        }
    }

    private static CellData cloneCellData(CellData source)
    {
        CellData copy = new CellData();
        if (source == null)
            return copy;
        synchronized (source)
        {
            copy.baseText = source.baseText;
            copy.displayText = source.displayText;
            copy.sizeState = source.sizeState;
            copy.nestedSize = source.nestedSize;
            copy.contentLoaded = source.contentLoaded;
        }
        return copy;
    }

    void clearCellCache()
    {
        synchronized (cellCache)
        {
            cellCache.clear();
        }
        rowChildrenCache.clear();
    }

    /** Переложить кэш ячеек при смене порядка visible-колонок (смена «Представления»). */
    void remapCellCacheForVisibleLayout(int[] oldVisibleToModel)
    {
        if (oldVisibleToModel == null || oldVisibleToModel.length == 0)
            return;
        int[] newVisible = columns.copyVisibleToModel();
        if (java.util.Arrays.equals(oldVisibleToModel, newVisible))
            return;

        Map<CellKey, CellData> remapped = new LinkedHashMap<>();
        synchronized (cellCache)
        {
            for (Map.Entry<CellKey, CellData> entry : new ArrayList<>(cellCache.entrySet()))
            {
                CellKey oldKey = entry.getKey();
                int oldVisibleCol = oldKey.col;
                if (oldVisibleCol < 0 || oldVisibleCol >= oldVisibleToModel.length)
                    continue;
                int modelIdx = oldVisibleToModel[oldVisibleCol];
                int newVisibleCol = visibleIndexInLayout(modelIdx, newVisible);
                if (newVisibleCol >= 0)
                    remapped.put(new CellKey(oldKey.row, newVisibleCol), entry.getValue());
            }
            cellCache.clear();
            cellCache.putAll(remapped);
        }
    }

    private static int visibleIndexInLayout(int modelIdx, int[] visibleToModel)
    {
        if (visibleToModel == null || modelIdx < 0)
            return -1;
        for (int i = 0; i < visibleToModel.length; i++)
        {
            if (visibleToModel[i] == modelIdx)
                return i;
        }
        return -1;
    }

    /** Строка или ячейки viewport ещё не заполнены (после смены колонок rowVar может уже быть). */
    boolean needsRowLoad(int row, int colFrom, int colTo)
    {
        if (row < 0)
            return false;
        colFrom = Math.max(0, colFrom);
        colTo = Math.min(colTo, columns.columnCount() - 1);
        for (int c = colFrom; c <= colTo; c++)
        {
            if (!isCellFilled(row, c))
                return true;
        }
        return false;
    }

    /** Число строк в диапазоне, у которых заполнены все видимые колонки. */
    int countRowsFullyLoaded(int first, int last, int colFrom, int colTo)
    {
        if (last < first)
            return 0;
        int count = 0;
        for (int row = first; row <= last; row++)
        {
            if (!needsRowLoad(row, colFrom, colTo))
                count++;
        }
        return count;
    }

    boolean isCellFilled(int row, int col)
    {
        CellKey key = new CellKey(row, col);
        CellData data;
        synchronized (cellCache)
        {
            data = cellCache.get(key);
        }
        if (data == null)
            return false;
        synchronized (data)
        {
            return data.contentLoaded;
        }
    }

    CellData cellData(int logicalRow, int visibleCol)
    {
        CellKey key = new CellKey(logicalRow, visibleCol);
        synchronized (cellCache)
        {
            return cellCache.computeIfAbsent(key, k -> new CellData());
        }
    }

    String getCellDisplayText(int logicalRow, int visibleCol)
    {
        CellData data = cellData(logicalRow, visibleCol);
        synchronized (data)
        {
            if (data.contentLoaded)
                return data.displayText != null ? data.displayText : ""; //$NON-NLS-1$
            if (data.displayText != null && !data.displayText.isEmpty())
                return data.displayText;
            if (data.baseText != null && !data.baseText.isEmpty())
                return data.baseText;
        }
        return PLACEHOLDER;
    }

    String getCellBaseText(int logicalRow, int visibleCol)
    {
        CellData data = cellData(logicalRow, visibleCol);
        synchronized (data)
        {
            return data.baseText != null ? data.baseText : ""; //$NON-NLS-1$
        }
    }

    private static String cellTextWithNestedSize(String baseText, int size)
    {
        return "(" + size + ") " + baseText; //$NON-NLS-1$ //$NON-NLS-2$
    }

    void setCellText(int logicalRow, int visibleCol, String baseText)
    {
        CellData data = cellData(logicalRow, visibleCol);
        synchronized (data)
        {
            data.contentLoaded = true;
            if (baseText == null)
                baseText = ""; //$NON-NLS-1$
            data.baseText = baseText;
            if (data.sizeState == SizeState.READY && data.nestedSize >= 0)
                data.displayText = cellTextWithNestedSize(data.baseText, data.nestedSize);
            else if (PLACEHOLDER.equals(baseText))
                data.displayText = PLACEHOLDER;
            else
                data.displayText = data.baseText;
        }
    }

    void setCellSize(int logicalRow, int visibleCol, int size)
    {
        CellData data = cellData(logicalRow, visibleCol);
        synchronized (data)
        {
            data.nestedSize = size;
            data.sizeState = SizeState.READY;
            if (data.baseText != null && !data.baseText.isEmpty())
                data.displayText = cellTextWithNestedSize(data.baseText, size);
        }
    }

    void markCellSizePending(int logicalRow, int visibleCol)
    {
        CellData data = cellData(logicalRow, visibleCol);
        synchronized (data)
        {
            data.sizeState = SizeState.PENDING;
        }
    }

    String extractCellTextInJob(int logicalRow, int visibleCol) throws DebugException
    {
        DebugCollectionColumnModel.Column col = columns.columnAt(visibleCol);
        if (col == null)
            return ""; //$NON-NLS-1$

        IBslVariable rowVar = rowVariables.get(logicalRow);
        if (rowVar == null)
        {
            rowVar = indexedValue.getVariable(logicalRow);
            if (rowVar != null)
                rowVariables.put(logicalRow, rowVar);
        }
        if (rowVar == null)
            return ""; //$NON-NLS-1$

        return switch (col.kind)
        {
            case INDEX -> formatIndex(logicalRow, rowVar);
            case TYPE -> formatType(rowVar);
            case VALUE -> formatValue(rowVar);
            case PROPERTY -> formatProperty(logicalRow, rowVar, col.propertyName);
        };
    }

    String rowFilterText(int logicalRow) throws DebugException
    {
        return rowFilterText(logicalRow, false);
    }

    String rowFilterText(int logicalRow, boolean presentationOnly) throws DebugException
    {
        if (presentationOnly)
        {
            int modelIdx = columns.presentationModelIndex();
            if (modelIdx <= 0)
                return ""; //$NON-NLS-1$
            int visibleCol = columns.visibleIndexOfModelColumn(modelIdx);
            if (visibleCol < 0)
                return ""; //$NON-NLS-1$
            String text = extractCellTextInJob(logicalRow, visibleCol);
            return text != null ? text : ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < columns.columnCount(); c++)
        {
            String text = extractCellTextInJob(logicalRow, c);
            if (text != null && !text.isEmpty())
            {
                if (sb.length() > 0)
                    sb.append(' ');
                sb.append(text);
            }
        }
        return sb.toString();
    }

    IBslValue resolveCellValue(int logicalRow, int visibleCol) throws DebugException
    {
        DebugCollectionColumnModel.Column col = columns.columnAt(visibleCol);
        if (col == null)
            return null;
        IBslVariable rowVar = rowVariables.get(logicalRow);
        if (rowVar == null)
        {
            rowVar = indexedValue.getVariable(logicalRow);
            if (rowVar != null)
                rowVariables.put(logicalRow, rowVar);
        }
        if (rowVar == null)
            return null;
        return switch (col.kind)
        {
            case INDEX, TYPE, VALUE -> rowVar.getValue();
            case PROPERTY -> resolvePropertyValue(logicalRow, rowVar, col.propertyName);
        };
    }

    private IBslValue resolvePropertyValue(int logicalRow, IBslVariable rowVar, String propertyName)
            throws DebugException
    {
        IBslVariable child = findNamedChild(logicalRow, rowVar, propertyName);
        return child != null ? child.getValue() : null;
    }

    private static String formatIndex(int logicalRow, IBslVariable rowVar)
    {
        return String.valueOf(logicalRow);
    }

    private static String formatType(IBslVariable rowVar) throws DebugException
    {
        IBslValue value = rowVar.getValue();
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value.isPending())
            return null;
        ensureEvaluated(value);
        if (value.isPending())
            return null;
        String typeName = value.getValueTypeName();
        return typeName != null ? typeName : ""; //$NON-NLS-1$
    }

    private static String formatValue(IBslVariable rowVar) throws DebugException
    {
        IBslValue value = rowVar.getValue();
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value.isPending())
            return null;
        ensureEvaluated(value);
        if (value.isPending())
            return null;
        if (value.isUnreadable())
            return "<unreadable>"; //$NON-NLS-1$
        String detail = value.getDetailString();
        String text = detail != null ? detail : ""; //$NON-NLS-1$
        return DebugStringValueFormat.formatForCollectionWindow(text, value);
    }

    private String formatProperty(int logicalRow, IBslVariable rowVar, String propertyName)
            throws DebugException
    {
        if (propertyName == null || propertyName.isBlank())
            return ""; //$NON-NLS-1$
        IBslVariable[] props = rowPropertySource(logicalRow, rowVar);
        if (props == null)
            return null;
        if (props.length == 0)
            return ""; //$NON-NLS-1$
        IBslVariable child = findPropertyVariable(props, propertyName);
        if (child == null)
            return ""; //$NON-NLS-1$
        return formatPropertyValueString(child);
    }

    /** Как EDT: pass 1 — тип/краткое представление; pass 2 — {@code (N) тип} в {@link DebugCollectionSizeResolver}. */
    private static String formatPropertyValueString(IBslVariable child) throws DebugException
    {
        if (child == null)
            return ""; //$NON-NLS-1$
        IBslValue childValue = child.getValue();
        if (childValue == null)
            return ""; //$NON-NLS-1$
        if (childValue.isPending())
            return null;
        if (childValue instanceof IBslIndexedValue indexed)
        {
            String typeName = indexed.getValueTypeName();
            if (typeName != null && !typeName.isBlank())
                return typeName.trim();
            return "Коллекция"; //$NON-NLS-1$
        }
        if (DebugStringValueFormat.isStringValue(childValue))
        {
            String detail = childValue.getDetailString();
            if (detail != null && !detail.isEmpty())
                return DebugStringValueFormat.formatForCollectionWindow(detail, childValue);
        }
        String cached = childValue.getValueString();
        if (cached != null && !cached.isEmpty())
            return DebugStringValueFormat.formatForCollectionWindow(cached, childValue);
        if (!childValue.isEvaluated())
            childValue.evaluate();
        if (childValue.isPending())
            return null;
        String text = childValue.getValueString();
        if (text == null)
            return ""; //$NON-NLS-1$
        return DebugStringValueFormat.formatForCollectionWindow(text, childValue);
    }

    private IBslVariable findNamedChild(int logicalRow, IBslVariable rowVar, String propertyName)
            throws DebugException
    {
        if (rowVar == null || propertyName == null)
            return null;
        IBslVariable[] props = rowPropertySource(logicalRow, rowVar);
        if (props == null || props.length == 0)
            props = rawRowVariables(rowVar);
        if (props == null)
            return null;
        IBslVariable child = findPropertyVariable(props, propertyName);
        if (child == null && DebugCollectionPropertyVariables.isIndexedPlaceholderContext(props))
        {
            int ordinal = propertyColumnOrdinal(propertyName);
            if (ordinal >= 0 && ordinal < props.length)
                child = props[ordinal];
        }
        return child;
    }

    private int propertyColumnOrdinal(String propertyName)
    {
        if (propertyName == null || propertyName.isBlank())
            return -1;
        int ordinal = 0;
        for (DebugCollectionColumnModel.Column col : columns.allColumns())
        {
            if (col.kind != DebugCollectionColumnModel.Kind.PROPERTY)
                continue;
            if (propertyName.equals(col.propertyName) || propertyName.equalsIgnoreCase(col.propertyName))
                return ordinal;
            ordinal++;
        }
        return -1;
    }

    private static IBslVariable[] rawRowVariables(IBslVariable rowVar) throws DebugException
    {
        if (rowVar == null)
            return null;
        IBslValue value = rowVar.getValue();
        if (value == null || value.isPending())
            return null;
        if (!value.isEvaluated())
            value.evaluate();
        if (value.isPending())
            return null;
        IBslVariable[] vars = value.getVariables();
        return vars != null && vars.length > 0 ? vars : null;
    }

    /** EDT: {@code property.equals(child.getName())}, затем ignoreCase. */
    private static IBslVariable findPropertyVariable(IBslVariable[] props, String propertyName)
    {
        if (props == null || propertyName == null)
            return null;
        for (IBslVariable child : props)
        {
            if (child == null)
                continue;
            String name = child.getName();
            if (name != null && name.equals(propertyName))
                return child;
        }
        for (IBslVariable child : props)
        {
            if (child == null)
                continue;
            String name = child.getName();
            if (name != null && name.equalsIgnoreCase(propertyName))
                return child;
        }
        return null;
    }

    private IBslVariable[] rowPropertySource(int logicalRow, IBslVariable rowVar) throws DebugException
    {
        if (rowVar == null)
            return null;
        IBslVariable[] cached = rowChildrenCache.get(logicalRow);
        if (cached != null)
            return cached;

        IBslVariable[] source = DebugCollectionPropertyVariables.propertySource(rowVar.getValue());
        if (source == null || source.length == 0)
            return null;
        rowChildrenCache.put(logicalRow, source);
        return source;
    }

    /** Один evaluate на value; без повторного вызова для pending/evaluated. */
    private static void ensureEvaluated(IBslValue value) throws DebugException
    {
        if (value == null || value.isEvaluated() || value.isPending())
            return;
        value.evaluate();
    }
}
