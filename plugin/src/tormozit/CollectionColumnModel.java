package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.swt.widgets.TableColumn;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;

/**
 * Колонки таблицы «Коллекция»: индекс + property-колонки из {@code getContextVariables()} (как EDT).
 */
final class CollectionColumnModel
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

    private CollectionColumnModel(List<Column> columns, int[] visibleToModel)
    {
        this.columns = columns;
        this.visibleToModel = visibleToModel;
    }

    static CollectionColumnModel minimal()
    {
        List<Column> cols = new ArrayList<>();
        cols.add(new Column(Kind.INDEX, INDEX_HEADER, null, 0));
        return withVisibleAll(cols);
    }

    /** Искусственная схема для окна-скелета (тест UI). */
    static CollectionColumnModel skeleton(int propertyColumnCount)
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

    static CollectionColumnModel fromContextVariables(IBslVariable[] contextVars)
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
            // property-колонки из EDT уже покрывают структуру — «Тип»/«Значение» не дублируем
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

    private static CollectionColumnModel withVisibleAll(List<Column> cols)
    {
        int[] visible = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++)
            visible[i] = i;
        return new CollectionColumnModel(new ArrayList<>(cols), visible);
    }

    int columnCount()
    {
        return visibleToModel.length;
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

        int hidden = hiddenModelIndicesInOrder(visibleFlags, order).length;
        if (columns.size() > MAX_DEFAULT_VISIBLE_COLUMNS && hidden > 0)
            hiddenColumnCount = hidden;

        visibleToModel = new int[mapped.size()];
        for (int i = 0; i < mapped.size(); i++)
            visibleToModel[i] = mapped.get(i);
    }

    void syncTableHeaders(org.eclipse.swt.widgets.Table table)
    {
        syncSplitTables(null, table);
    }

    /** {@code indexTable} — только «Индекс»; {@code dataTable} — остальные видимые колонки. */
    void syncSplitTables(org.eclipse.swt.widgets.Table indexTable, org.eclipse.swt.widgets.Table dataTable)
    {
        if (indexTable != null && !indexTable.isDisposed())
            syncIndexTableHeader(indexTable);
        if (dataTable != null && !dataTable.isDisposed())
            syncDataTableHeaders(dataTable);
    }

    private void syncIndexTableHeader(org.eclipse.swt.widgets.Table table)
    {
        Column indexCol = findVisibleColumn(Kind.INDEX);
        org.eclipse.swt.widgets.TableColumn[] existing = table.getColumns();
        if (existing.length == 0)
            new org.eclipse.swt.widgets.TableColumn(table, org.eclipse.swt.SWT.NONE);
        org.eclipse.swt.widgets.TableColumn tc = table.getColumn(0);
        tc.setText(indexCol != null ? indexCol.header : INDEX_HEADER);
        tc.setWidth(CollectionIndexColumnWidthStore.load());
        tc.setResizable(true);
        tc.setMoveable(false);
        for (int i = 1; i < existing.length; i++)
            existing[i].dispose();
    }

    private void syncDataTableHeaders(org.eclipse.swt.widgets.Table table)
    {
        int dataCols = Math.max(0, columnCount() - 1);
        org.eclipse.swt.widgets.TableColumn[] existing = table.getColumns();
        for (int i = existing.length; i < dataCols; i++)
            new org.eclipse.swt.widgets.TableColumn(table, org.eclipse.swt.SWT.NONE);
        for (int dataCol = 0; dataCol < dataCols; dataCol++)
        {
            Column col = columnAt(dataCol + 1);
            org.eclipse.swt.widgets.TableColumn tc = table.getColumn(dataCol);
            tc.setText(col != null ? col.header : ""); //$NON-NLS-1$
            tc.setWidth(defaultWidth(col));
            tc.setResizable(true);
            tc.setMoveable(true);
        }
        for (int i = dataCols; i < existing.length; i++)
            existing[i].dispose();
    }

    private Column findVisibleColumn(Kind kind)
    {
        for (int i = 0; i < columnCount(); i++)
        {
            Column col = columnAt(i);
            if (col != null && col.kind == kind)
                return col;
        }
        return null;
    }

    private static int defaultWidth(Column col)
    {
        if (col == null)
            return 80;
        return switch (col.kind)
        {
            case INDEX -> 60;
            case TYPE -> 120;
            case VALUE -> 200;
            case PROPERTY -> 140;
        };
    }
}
