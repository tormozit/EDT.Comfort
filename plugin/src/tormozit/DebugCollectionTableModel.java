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
 * Модель коллекции: строки — из {@link IBslIndexedValue} (как input штатного {@code TableViewer}),
 * текст ячеек — лениво при {@code SWT.SetData} через {@link #getCellDisplayText}, с мемо-кэшем до
 * {@link #invalidateAllCells()}. {@link #rowVariables} — опциональный импорт (clone); live-строки — через {@link IBslIndexedValue}.
 */
final class DebugCollectionTableModel
{
    private static final String EVALUATING_ROW_TEXT = "…"; //$NON-NLS-1$
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

    enum SizeState
    {
        UNKNOWN, PENDING, READY, NA
    }

    static final class CellData
    {
        String baseText = ""; //$NON-NLS-1$
        String displayText = ""; //$NON-NLS-1$
        SizeState sizeState = SizeState.UNKNOWN;
        int nestedSize = -1;
        boolean contentLoaded;
    }

    private static final int CELL_CACHE_LIMIT_MIN = 12000;
    private static final int ROW_CHILDREN_CACHE_LIMIT = 2000;

    final IBslIndexedValue indexedValue;
    final IBslStackFrame frame;
    final BslValuePath path;
    final DebugCollectionColumnModel columns;
    final String typeTitle;

    volatile int totalSize = -1;
    volatile int loadedRowCount;

    private volatile java.util.function.IntConsumer dirtyRowHandler;

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

    int cellCacheCapacity()
    {
        int rows = totalSize > 0
            ? Math.min(totalSize, 2048)
            : 512;
        int cols = Math.max(1, columns.columnCount());
        long need = (long) rows * cols + 512L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(CELL_CACHE_LIMIT_MIN, need));
    }

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
        try
        {
            return rowVariable(logicalRow);
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("getRowVariable: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
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

    void setDirtyRowHandler(java.util.function.IntConsumer handler)
    {
        dirtyRowHandler = handler;
    }

    void noteCollectionKicked(int size)
    {
        totalSize = size;
        loadedRowCount = size;
    }

    void clearLiveRowCache()
    {
        rowVariables.clear();
        rowChildrenCache.clear();
        loadedRowCount = 0;
    }

    boolean isRowsLoaded()
    {
        return totalSize >= 0 && loadedRowCount >= totalSize;
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
        invalidateAllCells();
    }

    void invalidateLogicalRow(int logicalRow)
    {
        if (logicalRow < 0)
            return;
        synchronized (cellCache)
        {
            cellCache.entrySet().removeIf(e -> e.getKey().row == logicalRow);
        }
        rowChildrenCache.remove(logicalRow);
    }

    /**
     * Как {@link #invalidateLogicalRow}, но не трогает ячейки с уже готовым размером
     * ({@code READY}) или помеченные {@code NA}. Используется при возврате строки в viewport
     * после прокрутки (priority-путь) — раньше безусловный wipe заставлял уже посчитанную
     * {@code "(N) Тип"} на мгновение превращаться обратно в голый {@code "Тип"} до повторного
     * (пусть и мгновенного, т.к. evaluate() на стороне 1C уже сделан) прохода {@link
     * DebugCollectionSizeResolver} — отсюда и заметное мигание при повторном показе уже
     * посчитанной ячейки.
     */
    void invalidateLogicalRowPreservingReadySizes(int logicalRow)
    {
        if (logicalRow < 0)
            return;
        boolean[] preservedAny = { false };
        synchronized (cellCache)
        {
            cellCache.entrySet().removeIf(e -> {
                if (e.getKey().row != logicalRow)
                    return false;
                CellData data = e.getValue();
                synchronized (data)
                {
                    if (data.sizeState == SizeState.READY || data.sizeState == SizeState.NA)
                    {
                        preservedAny[0] = true;
                        return false;
                    }
                    return true;
                }
            });
        }
        // Если в строке остались непреодолённые (не READY/NA) ячейки, их rowChildrenCache трогать
        // не будем — иначе именно тот механизм из findNamedChild, что даёт SizeResolver стабильную
        // ссылку на IBslValue между повторами, обнулится и async evaluate() придётся начинать заново.
        if (!preservedAny[0])
            rowChildrenCache.remove(logicalRow);
    }

    void invalidateAllCells()
    {
        synchronized (cellCache)
        {
            cellCache.clear();
        }
        rowChildrenCache.clear();
    }

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

    /** Текст ячейки для SetData — ленивое разрешение как у штатного LabelProvider. */
    String getCellDisplayText(int logicalRow, int visibleCol)
    {
        CellKey key = new CellKey(logicalRow, visibleCol);
        CellData cached;
        synchronized (cellCache)
        {
            cached = cellCache.get(key);
        }
        if (cached != null)
        {
            synchronized (cached)
            {
                if (cached.contentLoaded)
                    return cached.displayText != null ? cached.displayText : ""; //$NON-NLS-1$
            }
        }
        String text = resolveCellTextLive(logicalRow, visibleCol);
        if (shouldCacheCell(logicalRow, visibleCol, text))
            cacheCellText(logicalRow, visibleCol, text);
        // #region agent log
        if (EVALUATING_ROW_TEXT.equals(text) && logicalRow % 64 == 0 && visibleCol == 4)
        {
            DebugCollectionColumnModel.Column col = columns.columnAt(visibleCol);
            String kind = col != null ? col.kind.name() : "?"; //$NON-NLS-1$
            DebugCollectionAgentLog.log("H-cell", "TableModel.getCellDisplayText", "pendingCell", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"row\":" + logicalRow + ",\"col\":" + visibleCol //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"kind\":\"" + kind + "\",\"text\":\"…\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // #endregion
        return text;
    }

    private boolean shouldCacheCell(int logicalRow, int visibleCol, String text)
    {
        DebugCollectionColumnModel.Column col = columns.columnAt(visibleCol);
        if (col != null && col.kind == DebugCollectionColumnModel.Kind.INDEX)
            return true;
        if (!hasResolvableRow(logicalRow))
            return false;
        if (text == null || text.isEmpty() || EVALUATING_ROW_TEXT.equals(text))
            return false;
        return true;
    }

    private boolean hasResolvableRow(int logicalRow)
    {
        try
        {
            return rowVariable(logicalRow) != null;
        }
        catch (DebugException e)
        {
            return false;
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

    private void cacheCellText(int logicalRow, int visibleCol, String text)
    {
        CellData data = cellData(logicalRow, visibleCol);
        synchronized (data)
        {
            boolean pendingMarker = false;
            int parsedSize = -1;
            if (text == null)
                text = ""; //$NON-NLS-1$
            if (text.startsWith("("))
            {
                int closeParen = text.indexOf(") ");
                if (closeParen > 1)
                {
                    String inner = text.substring(1, closeParen);
                    text = text.substring(closeParen + 2);
                    if (".".equals(inner))
                    {
                        pendingMarker = true;
                    }
                    else
                    {
                        try
                        {
                            parsedSize = Integer.parseInt(inner);
                        }
                        catch (NumberFormatException e)
                        {
                            text = "(" + inner + ") " + text;
                        }
                    }
                }
            }
            if (text.equals(data.baseText) && (data.sizeState == SizeState.READY || data.sizeState == SizeState.PENDING))
            {
                data.contentLoaded = true;
                return;
            }
            data.baseText = text;
            data.contentLoaded = true;
            data.sizeState = SizeState.UNKNOWN;
            data.nestedSize = -1;
            if (pendingMarker)
            {
                data.displayText = "(.) " + text;
            }
            else if (parsedSize >= 0)
            {
                data.nestedSize = parsedSize;
                data.sizeState = SizeState.READY;
                data.displayText = cellTextWithNestedSize(text, parsedSize);
            }
            else
            {
                data.displayText = text;
            }
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

    String resolveCellTextLive(int logicalRow, int visibleCol)
    {
        try
        {
            String text = extractCellText(logicalRow, visibleCol);
            return text != null ? text : ""; //$NON-NLS-1$
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("cellText: " + e.getMessage()); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    /** Для фонового фильтра — тот же путь, что и для SetData. */
    String extractCellTextInJob(int logicalRow, int visibleCol) throws DebugException
    {
        return extractCellText(logicalRow, visibleCol);
    }

    String extractCellText(int logicalRow, int visibleCol) throws DebugException
    {
        DebugCollectionColumnModel.Column col = columns.columnAt(visibleCol);
        if (col == null)
            return ""; //$NON-NLS-1$

        IBslVariable rowVar = rowVariable(logicalRow);
        if (rowVar == null)
        {
            if (col.kind == DebugCollectionColumnModel.Kind.INDEX)
                return formatIndex(logicalRow);
            if (isCollectionEvaluating())
                return EVALUATING_ROW_TEXT;
            return ""; //$NON-NLS-1$
        }

        return switch (col.kind)
        {
            case INDEX -> formatIndex(logicalRow);
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
            String text = extractCellText(logicalRow, visibleCol);
            return text != null ? text : ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < columns.columnCount(); c++)
        {
            String text = extractCellText(logicalRow, c);
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
        IBslVariable rowVar = rowVariable(logicalRow);
        if (rowVar == null)
            return null;
        return switch (col.kind)
        {
            case INDEX, TYPE, VALUE -> rowVar.getValue();
            case PROPERTY -> resolvePropertyValue(logicalRow, rowVar, col.propertyName);
        };
    }

    private boolean isCollectionEvaluating()
    {
        return indexedValue != null && indexedValue.isPending();
    }

    /** Как {@code IndexedValuesViewDelegate}: строка из core, без локального bulk-map. */
    private IBslVariable rowVariable(int logicalRow) throws DebugException
    {
        IBslVariable imported = rowVariables.get(logicalRow);
        if (imported != null)
            return imported;
        if (indexedValue == null)
            return null;

        IBslVariable rowVar = indexedValue.getVariable(logicalRow);
        if (rowVar != null)
            return rowVar;

        IBslVariable[] page = indexedValue.getVariables(logicalRow, 1);
        if (page != null && page.length > 0 && page[0] != null)
            return page[0];
        return null;
    }

    private IBslValue resolvePropertyValue(int logicalRow, IBslVariable rowVar, String propertyName)
            throws DebugException
    {
        IBslVariable child = findNamedChild(logicalRow, rowVar, propertyName);
        return child != null ? child.getValue() : null;
    }

    private static String formatIndex(int logicalRow)
    {
        return String.valueOf(logicalRow);
    }

    /** Как {@code IndexedValuesViewDelegate$4}: {@code getReferenceTypeName()}. */
    private static String formatType(IBslVariable rowVar) throws DebugException
    {
        String typeName = rowVar.getReferenceTypeName();
        return typeName != null ? typeName : ""; //$NON-NLS-1$
    }

    /** Как {@code IndexedValuesViewDelegate$3}: {@code getValueString()} после sync evaluate. */
    private static String formatValue(IBslVariable rowVar) throws DebugException
    {
        IBslValue value = rowVar.getValue();
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value.isUnreadable())
            return UnreadablePresentation(); //$NON-NLS-1$
        if (!value.isEvaluated())
            value.evaluate();
        if (value.isPending())
            return EVALUATING_ROW_TEXT;
        String text = value.getValueString();
        if (text == null)
            text = ""; //$NON-NLS-1$
        return DebugStringValueFormat.formatForCollectionWindow(text, value);
    }

    public static String UnreadablePresentation()
    {
        return "<недоступно>";
    }

    private String formatProperty(int logicalRow, IBslVariable rowVar, String propertyName)
            throws DebugException
    {
        if (propertyName == null || propertyName.isBlank())
            return ""; //$NON-NLS-1$
        IBslVariable child = findNamedChild(logicalRow, rowVar, propertyName);
        if (child == null)
            return ""; //$NON-NLS-1$
        return formatPropertyValueString(child);
    }

    /**
     * Pass 1 — скаляры: {@code getValueString()} как EDT; коллекции: sync {@code getSize()} → {@code (N) тип}.
     * Pass 2 — {@link DebugCollectionSizeResolver} для pending {@code getSize()}.
     */
    private static String formatPropertyValueString(IBslVariable child) throws DebugException
    {
        if (child == null)
            return ""; //$NON-NLS-1$
        IBslValue childValue = child.getValue();
        if (childValue == null)
            return ""; //$NON-NLS-1$
        if (childValue.isUnreadable())
            return UnreadablePresentation(); //$NON-NLS-1$
        if (childValue instanceof IBslIndexedValue indexed)
            return formatIndexedWithSize(indexed);
        try
        {
            String text = childValue.getValueString();
            if (text != null && !text.isBlank())
                return DebugStringValueFormat.formatForCollectionWindow(text, childValue);
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("propertyText: " + e.getMessage()); //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    private static String formatIndexedWithSize(IBslIndexedValue indexed) throws DebugException
    {
        String typeName = indexed.getValueTypeName();
        if (typeName == null || typeName.isBlank())
            typeName = "Коллекция"; //$NON-NLS-1$
        else
            typeName = typeName.trim();
        // Kickstart EDT evaluation now (UI thread during SetData),
        // so SizeResolver сразу получит реальный getSize() без 5s задержки.
        if (!indexed.isEvaluated())
            indexed.evaluate();
        return "(.) " + typeName;
    }

    private IBslVariable[] propertyContextForRow(IBslVariable rowVar) throws DebugException
    {
        if (rowVar == null)
            return null;
        IBslValue rowValue = rowVar.getValue();
        if (rowValue == null)
            return null;
        if (rowValue instanceof IBslIndexedValue indexed)
        {
            IBslVariable[] ctx = indexed.getContextVariables();
            if (ctx != null && ctx.length > 0)
                return ctx;
        }
        IBslVariable[] vars = rowValue.getVariables();
        if (vars != null && vars.length > 0)
            return vars;
        return DebugCollectionPropertyVariables.propertyVariablesForRow(rowVar);
    }

    private IBslVariable findNamedChild(int logicalRow, IBslVariable rowVar, String propertyName)
            throws DebugException
    {
        if (rowVar == null || propertyName == null)
            return null;
        IBslVariable[] props = rowChildrenCache.get(logicalRow);
        if (props == null)
        {
            props = propertyContextForRow(rowVar);
            if (props != null && props.length > 0)
                rowChildrenCache.put(logicalRow, props);
        }
        if (props == null || props.length == 0)
            props = propertyContextForRow(rowVar);
        if (props == null)
        {
            IBslValue rowValue = rowVar.getValue();
            if (rowValue != null && rowValue.isPending())
                notifyDirtyRow(logicalRow);
            return null;
        }
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

    private void notifyDirtyRow(int logicalRow)
    {
        java.util.function.IntConsumer handler = dirtyRowHandler;
        if (handler != null)
            handler.accept(logicalRow);
    }
}
