package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.swt.widgets.TableColumn;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;

/**
 * Колонки таблицы «Коллекция»: индекс + property-колонки из {@code getContextVariables()} (как EDT).
 */
final class DebugCollectionColumnModel
{
    static final String INDEX_HEADER = "Индекс"; //$NON-NLS-1$
    static final String TYPE_HEADER = "Тип"; //$NON-NLS-1$
    static final String VALUE_HEADER = "Значение"; //$NON-NLS-1$
    /** По умолчанию видимы первые N колонок; остальные — баннер над таблицей. */
    static final int MAX_DEFAULT_VISIBLE_COLUMNS = 50;

    enum Kind
    {
        INDEX, TYPE, VALUE, PROPERTY
    }

    static final class Column
    {
        final Kind kind;
        final String header;
        final String propertyName;
        final int modelIndex;

        Column(Kind kind, String header, String propertyName, int modelIndex)
        {
            this.kind = kind;
            this.header = header;
            this.propertyName = propertyName;
            this.modelIndex = modelIndex;
        }
    }

    private List<Column> columns;
    private int[] visibleToModel;
    private int hiddenColumnCount;
    private int presentationModelIndex = -1;

    private DebugCollectionColumnModel(List<Column> columns, int[] visibleToModel)
    {
        this.columns = columns;
        this.visibleToModel = visibleToModel;
    }

    DebugCollectionColumnModel copy()
    {
        DebugCollectionColumnModel copy = new DebugCollectionColumnModel(new ArrayList<>(columns), visibleToModel.clone());
        copy.hiddenColumnCount = hiddenColumnCount;
        copy.presentationModelIndex = presentationModelIndex;
        return copy;
    }

    static DebugCollectionColumnModel minimal()
    {
        List<Column> cols = new ArrayList<>();
        cols.add(new Column(Kind.INDEX, INDEX_HEADER, null, 0));
        cols.add(new Column(Kind.TYPE, TYPE_HEADER, null, 1));
        return withVisibleAll(cols);
    }

    /** Искусственная схема для окна-скелета (тест UI). */
    static DebugCollectionColumnModel skeleton(int propertyColumnCount)
    {
        List<Column> cols = new ArrayList<>();
        cols.add(new Column(Kind.INDEX, INDEX_HEADER, null, 0));
        int count = Math.max(0, propertyColumnCount);
        for (int i = 1; i <= count; i++)
        {
            String name = "Col" + String.format("%02d", i); //$NON-NLS-1$ //$NON-NLS-2$
            cols.add(new Column(Kind.PROPERTY, name, name, cols.size()));
        }
        return withVisibleAll(cols);
    }

    static DebugCollectionColumnModel fromContextVariables(IBslVariable[] contextVars)
    {
        List<Column> cols = new ArrayList<>();
        cols.add(new Column(Kind.INDEX, INDEX_HEADER, null, 0));

        boolean hasType = false;
        boolean hasValue = false;

        if (contextVars != null && contextVars.length > 0)
        {
            for (IBslVariable ctx : contextVars)
            {
                if (ctx == null)
                    continue;
                String name = ctx.getName();
                if (name == null || name.isBlank())
                    continue;
                if (INDEX_HEADER.equalsIgnoreCase(name) || "Index".equalsIgnoreCase(name)) //$NON-NLS-1$
                    continue;
                if (TYPE_HEADER.equalsIgnoreCase(name) || "Type".equalsIgnoreCase(name)) //$NON-NLS-1$
                {
                    hasType = true;
                    cols.add(new Column(Kind.PROPERTY, name, name, cols.size()));
                    continue;
                }
                if (VALUE_HEADER.equalsIgnoreCase(name) || "Value".equalsIgnoreCase(name)) //$NON-NLS-1$
                {
                    hasValue = true;
                    cols.add(new Column(Kind.PROPERTY, name, name, cols.size()));
                    continue;
                }
                cols.add(new Column(Kind.PROPERTY, name, name, cols.size()));
            }
        }

        if (contextVars == null || contextVars.length == 0)
        {
            cols.add(new Column(Kind.TYPE, TYPE_HEADER, null, cols.size()));
            cols.add(new Column(Kind.VALUE, VALUE_HEADER, null, cols.size()));
        }
        else if (!hasType && !hasValue)
        {
            cols.add(new Column(Kind.TYPE, TYPE_HEADER, null, cols.size()));
        }
        else
        {
            if (!hasType)
                cols.add(new Column(Kind.TYPE, TYPE_HEADER, null, cols.size()));
            if (!hasValue)
                cols.add(new Column(Kind.VALUE, VALUE_HEADER, null, cols.size()));
        }

        return withVisibleAll(cols);
    }

    /** Скрытые model-колонки в порядке order. */
    static int[] hiddenModelIndicesInOrder(boolean[] visibleFlags, int[] order)
    {
        if (visibleFlags == null || order == null)
            return new int[0];
        List<Integer> hidden = new ArrayList<>();
        for (int modelIdx : order)
        {
            if (modelIdx < 0 || modelIdx >= visibleFlags.length)
                continue;
            if (!visibleFlags[modelIdx])
                hidden.add(modelIdx);
        }
        int[] result = new int[hidden.size()];
        for (int i = 0; i < hidden.size(); i++)
            result[i] = hidden.get(i);
        return result;
    }

    private static DebugCollectionColumnModel withVisibleAll(List<Column> cols)
    {
        int[] visible = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++)
            visible[i] = i;
        return new DebugCollectionColumnModel(new ArrayList<>(cols), visible);
    }

    int columnCount()
    {
        return visibleToModel.length;
    }

    /** Число колонок в фиксированной панели: «Индекс» + «Тип» + опционально «Представление». */
    int fixedColumnCount()
    {
        int count = 1;
        if (typeModelIndex() >= 0)
            count++;
        if (presentationModelIndex > 0)
        {
            for (int modelIdx : visibleToModel)
            {
                if (modelIdx == presentationModelIndex)
                    return count + 1;
            }
        }
        return count;
    }

    int typeModelIndex()
    {
        for (Column col : columns)
        {
            if (col.kind == Kind.TYPE)
                return col.modelIndex;
        }
        for (Column col : columns)
        {
            if (isTypePropertyColumn(col))
                return col.modelIndex;
        }
        return -1;
    }

    static boolean isTypeColumn(Column col)
    {
        if (col == null)
            return false;
        if (col.kind == Kind.TYPE)
            return true;
        return isTypePropertyColumn(col);
    }

    private static boolean isTypePropertyColumn(Column col)
    {
        return col.kind == Kind.PROPERTY
            && col.header != null
            && (TYPE_HEADER.equalsIgnoreCase(col.header) || "Type".equalsIgnoreCase(col.header)); //$NON-NLS-1$
    }

    int dataColumnCount()
    {
        return Math.max(0, columnCount() - fixedColumnCount());
    }

    int presentationModelIndex()
    {
        return presentationModelIndex;
    }

    String presentationHeader()
    {
        if (presentationModelIndex <= 0)
            return null;
        Column col = columnByModelIndex(presentationModelIndex);
        return col != null ? col.header : null;
    }

    int findPropertyColumnByHeader(String header)
    {
        if (header == null || header.isBlank())
            return -1;
        for (Column col : columns)
        {
            if (col.kind != Kind.PROPERTY || col.header == null)
                continue;
            if (col.header.equalsIgnoreCase(header))
                return col.modelIndex;
        }
        return -1;
    }

    int findPresentationColumnByHeader(String header)
    {
        if (header == null || header.isBlank())
            return -1;
        int property = findPropertyColumnByHeader(header);
        if (property >= 0)
            return property;
        for (Column col : columns)
        {
            if (col.kind == Kind.INDEX || col.header == null)
                continue;
            if (col.header.equalsIgnoreCase(header))
                return col.modelIndex;
        }
        return -1;
    }

    String resolveHeaderInSchema(String header)
    {
        int idx = findPresentationColumnByHeader(header);
        if (idx < 0)
            return null;
        Column col = columnByModelIndex(idx);
        return col != null ? col.header : null;
    }

    java.util.List<String> presentationColumnHeaders()
    {
        java.util.List<String> headers = new java.util.ArrayList<>();
        for (Column col : columns)
        {
            if (col.kind == Kind.INDEX || col.header == null || col.header.isBlank())
                continue;
            headers.add(col.header);
        }
        headers.sort(String::compareToIgnoreCase);
        return headers;
    }

    void applyPresentationHeader(String header)
    {
        presentationModelIndex = findPresentationColumnByHeader(header);
        pinFixedColumns();
    }

    int[] copyVisibleToModel()
    {
        return visibleToModel != null ? visibleToModel.clone() : new int[0];
    }

    int visibleIndexOfModelColumn(int modelIdx)
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

    void clearPresentation()
    {
        presentationModelIndex = -1;
    }

    int visibleIndexForDataColumn(int dataCol)
    {
        return fixedColumnCount() + Math.max(0, dataCol);
    }

    int modelColumnCount()
    {
        return columns.size();
    }

    boolean hasHiddenColumns()
    {
        return hiddenColumnCount > 0;
    }

    int hiddenColumnCount()
    {
        return hiddenColumnCount;
    }

    Column columnAt(int visibleIndex)
    {
        if (visibleIndex < 0 || visibleIndex >= visibleToModel.length)
            return null;
        return columns.get(visibleToModel[visibleIndex]);
    }

    List<Column> allColumns()
    {
        return Collections.unmodifiableList(columns);
    }

    void replaceColumns(java.util.List<Column> newColumns)
    {
        if (newColumns == null || newColumns.isEmpty())
            return;
        columns = new java.util.ArrayList<>();
        for (int i = 0; i < newColumns.size(); i++)
        {
            Column c = newColumns.get(i);
            columns.add(new Column(c.kind, c.header, c.propertyName, i));
        }
        visibleToModel = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++)
            visibleToModel[i] = i;
        hiddenColumnCount = 0;
        presentationModelIndex = -1;
    }

    void applyVisibility(boolean[] visibleFlags, int[] order)
    {
        if (visibleFlags == null || order == null)
            return;
        hiddenColumnCount = 0;
        List<Integer> mapped = new ArrayList<>();
        for (int modelIdx : order)
        {
            if (modelIdx < 0 || modelIdx >= visibleFlags.length)
                continue;
            if (visibleFlags[modelIdx])
                mapped.add(modelIdx);
        }
        if (mapped.isEmpty())
            mapped.add(0);

        int indexModel = indexModelIndex();
        if (!mapped.contains(indexModel))
            mapped.add(indexModel);
        int typeModel = typeModelIndex();
        if (typeModel >= 0 && !mapped.contains(typeModel))
            mapped.add(typeModel);

        int hidden = hiddenModelIndicesInOrder(visibleFlags, order).length;
        if (columns.size() > MAX_DEFAULT_VISIBLE_COLUMNS && hidden > 0)
            hiddenColumnCount = hidden;

        visibleToModel = new int[mapped.size()];
        for (int i = 0; i < mapped.size(); i++)
            visibleToModel[i] = mapped.get(i);
        pinFixedColumns();
    }

    /** Слот 0 — «Индекс», слот 1 — «Тип», слот 2 — «Представление»; остальные — порядок пользователя. */
    private void pinFixedColumns()
    {
        if (visibleToModel == null || visibleToModel.length == 0)
            return;

        int indexModel = indexModelIndex();
        int typeModel = typeModelIndex();
        Set<Integer> pinned = new HashSet<>();
        List<Integer> result = new ArrayList<>();

        boolean indexVisible = false;
        for (int modelIdx : visibleToModel)
        {
            if (modelIdx == indexModel)
            {
                indexVisible = true;
                break;
            }
        }
        if (indexVisible)
        {
            result.add(indexModel);
            pinned.add(indexModel);
        }

        if (typeModel >= 0)
        {
            for (int modelIdx : visibleToModel)
            {
                if (modelIdx == typeModel)
                {
                    result.add(typeModel);
                    pinned.add(typeModel);
                    break;
                }
            }
        }

        if (presentationModelIndex > 0)
        {
            for (int modelIdx : visibleToModel)
            {
                if (modelIdx == presentationModelIndex)
                {
                    result.add(presentationModelIndex);
                    pinned.add(presentationModelIndex);
                    break;
                }
            }
        }

        for (int modelIdx : visibleToModel)
        {
            if (!pinned.contains(modelIdx))
                result.add(modelIdx);
        }

        if (result.isEmpty())
            result.add(indexModel);

        visibleToModel = new int[result.size()];
        for (int i = 0; i < result.size(); i++)
            visibleToModel[i] = result.get(i);
    }

    private int indexModelIndex()
    {
        for (Column col : columns)
        {
            if (col.kind == Kind.INDEX)
                return col.modelIndex;
        }
        return 0;
    }

    private Column columnByModelIndex(int modelIndex)
    {
        if (modelIndex < 0 || modelIndex >= columns.size())
            return null;
        return columns.get(modelIndex);
    }

    void syncTableHeaders(org.eclipse.swt.widgets.Table table)
    {
        syncSplitTables(null, table);
    }

    /** {@code indexTable} — «Индекс», «Тип», опционально «Представление»; {@code dataTable} — остальные. */
    void syncSplitTables(org.eclipse.swt.widgets.Table indexTable, org.eclipse.swt.widgets.Table dataTable)
    {
        if (indexTable != null && !indexTable.isDisposed())
            syncIndexTableHeader(indexTable);
        if (dataTable != null && !dataTable.isDisposed())
            syncDataTableHeaders(dataTable);
    }

    private void syncIndexTableHeader(org.eclipse.swt.widgets.Table table)
    {
        int fixedCols = fixedColumnCount();
        org.eclipse.swt.widgets.TableColumn[] existing = table.getColumns();
        for (int i = existing.length; i < fixedCols; i++)
            new org.eclipse.swt.widgets.TableColumn(table, org.eclipse.swt.SWT.NONE);
        for (int i = 0; i < fixedCols; i++)
        {
            Column col = columnAt(i);
            org.eclipse.swt.widgets.TableColumn tc = table.getColumn(i);
            tc.setText(col != null ? col.header : ""); //$NON-NLS-1$
            tc.setWidth(fixedColumnWidth(col));
            tc.setResizable(true);
            tc.setMoveable(false);
        }
        for (int i = fixedCols; i < existing.length; i++)
            existing[i].dispose();
    }

    private void syncDataTableHeaders(org.eclipse.swt.widgets.Table table)
    {
        int dataCols = dataColumnCount();
        int fixed = fixedColumnCount();
        org.eclipse.swt.widgets.TableColumn[] existing = table.getColumns();
        for (int i = existing.length; i < dataCols; i++)
            new org.eclipse.swt.widgets.TableColumn(table, org.eclipse.swt.SWT.NONE);
        for (int dataCol = 0; dataCol < dataCols; dataCol++)
        {
            Column col = columnAt(fixed + dataCol);
            org.eclipse.swt.widgets.TableColumn tc = table.getColumn(dataCol);
            tc.setText(col != null ? col.header : ""); //$NON-NLS-1$
            tc.setWidth(defaultWidth(col));
            tc.setResizable(true);
            tc.setMoveable(true);
        }
        for (int i = dataCols; i < existing.length; i++)
            existing[i].dispose();
    }

    private static int fixedColumnWidth(Column col)
    {
        if (col == null)
            return 80;
        if (col.kind == Kind.INDEX)
            return DebugCollectionIndexColumnWidthStore.load();
        if (isTypeColumn(col))
            return DebugCollectionTypeColumnWidthStore.load();
        return DebugCollectionPresentationColumnWidthStore.load();
    }

    private static int defaultWidth(Column col)
    {
        if (col == null)
            return 80;
        return switch (col.kind)
        {
            case INDEX -> 60;
            case TYPE -> DebugCollectionTypeColumnWidthStore.DEFAULT_WIDTH;
            case VALUE -> 200;
            case PROPERTY -> 140;
        };
    }
}
