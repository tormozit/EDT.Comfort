package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Таблица «Коллекция»: фиксированная колонка «Индекс» + прокручиваемые данные.
 * Между панелями — {@link SashForm} (перетаскиваемая граница).
 */
final class CollectionSplitTable
{
    private final Composite panel;
    private final SashForm sash;
    private final Table indexTable;
    private final Table dataTable;
    private boolean syncingScroll;
    private boolean syncingSelection;
    private boolean syncingIndexLayout;
    private boolean indexColumnSizingInstalled;
    private boolean sashSizingInstalled;
    private boolean initialSashWeightsApplied;

    private CollectionSplitTable(Composite panel, SashForm sash, Table indexTable, Table dataTable)
    {
        this.panel = panel;
        this.sash = sash;
        this.indexTable = indexTable;
        this.dataTable = dataTable;
    }

    static CollectionSplitTable create(Composite parent)
    {
        Composite panel = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        panel.setLayout(layout);
        panel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        SashForm sash = new SashForm(panel, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        int style = SWT.VIRTUAL | SWT.FULL_SELECTION | SWT.BORDER | SWT.MULTI;
        Table indexTable = new Table(sash, style | SWT.NO_SCROLL);
        indexTable.setHeaderVisible(true);
        indexTable.setLinesVisible(true);

        Table dataTable = new Table(sash, style);
        dataTable.setHeaderVisible(true);
        dataTable.setLinesVisible(true);

        CollectionSplitTable split = new CollectionSplitTable(panel, sash, indexTable, dataTable);
        split.installVerticalScrollSync();
        split.installSelectionSync();
        split.installSashSizing();
        split.installIndexPaneColumnSync();
        return split;
    }

    Composite panel()
    {
        return panel;
    }

    Table indexTable()
    {
        return indexTable;
    }

    Table dataTable()
    {
        return dataTable;
    }

    /** После {@link CollectionColumnModel#syncSplitTables} — sash и колонка «Индекс» по сохранённой ширине. */
    void syncIndexColumnLayout()
    {
        if (indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
            return;
        int width = CollectionIndexColumnWidthStore.clamp(indexTable.getColumn(0).getWidth());
        applySashWeightsForColumnWidth(width);
    }

    /** Слушатель перетаскивания границы колонки «Индекс» в заголовке (один раз). */
    void installIndexColumnSizing()
    {
        if (indexColumnSizingInstalled || indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
            return;
        indexColumnSizingInstalled = true;
        TableColumn column = indexTable.getColumn(0);
        column.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                onIndexColumnHeaderResized(column);
            }
        });
    }

    void setItemCount(int count)
    {
        if (!indexTable.isDisposed())
            indexTable.setItemCount(count);
        if (!dataTable.isDisposed())
            dataTable.setItemCount(count);
    }

    void clearAll()
    {
        if (!indexTable.isDisposed())
            indexTable.clearAll();
        if (!dataTable.isDisposed())
            dataTable.clearAll();
    }

    void clear(int first, int last)
    {
        if (!indexTable.isDisposed())
            indexTable.clear(first, last);
        if (!dataTable.isDisposed())
            dataTable.clear(first, last);
    }

    int getTopIndex()
    {
        return dataTable.isDisposed() ? 0 : dataTable.getTopIndex();
    }

    void setTopIndex(int index)
    {
        if (indexTable.isDisposed() || dataTable.isDisposed())
            return;
        syncingScroll = true;
        try
        {
            indexTable.setTopIndex(index);
            dataTable.setTopIndex(index);
        }
        finally
        {
            syncingScroll = false;
        }
    }

    int getItemCount()
    {
        return dataTable.isDisposed() ? 0 : dataTable.getItemCount();
    }

    void syncSelectionToIndex(int displayIndex)
    {
        if (indexTable.isDisposed() || dataTable.isDisposed())
            return;
        if (displayIndex < 0 || displayIndex >= indexTable.getItemCount())
            return;
        syncingSelection = true;
        try
        {
            TableItem item = indexTable.getItem(displayIndex);
            if (item != null && !item.isDisposed())
                indexTable.setSelection(item);
            item = dataTable.getItem(displayIndex);
            if (item != null && !item.isDisposed())
                dataTable.setSelection(item);
        }
        finally
        {
            syncingSelection = false;
        }
    }

    private void installSashSizing()
    {
        if (sashSizingInstalled || sash.isDisposed())
            return;
        sashSizingInstalled = true;

        sash.addListener(SWT.Selection, e -> {
            if (indexTable.isDisposed())
                return;
            indexTable.getDisplay().asyncExec(this::syncIndexColumnToFillPane);
        });
        sash.addListener(SWT.Resize, e -> applyInitialSashWeightsOnce());
    }

    /** После изменения ширины панели индекса (sash) — растянуть колонку «Индекс» на всю панель. */
    private void installIndexPaneColumnSync()
    {
        indexTable.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                syncIndexColumnToFillPane();
            }
        });
    }

    private void syncIndexColumnToFillPane()
    {
        if (syncingIndexLayout || indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
            return;
        int target = indexColumnWidthFromPane();
        TableColumn column = indexTable.getColumn(0);
        int current = column.getWidth();
        if (Math.abs(target - current) < 2)
            return;
        // #region agent log
        CollectionLoadDebug.log("H4", "CollectionSplitTable.syncIndexColumnToFillPane", "stretch index column", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"pane\":" + target + ",\"colBefore\":" + current + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        syncingIndexLayout = true;
        try
        {
            column.setWidth(target);
            CollectionIndexColumnWidthStore.save(target);
        }
        finally
        {
            syncingIndexLayout = false;
        }
    }

    private void applyInitialSashWeightsOnce()
    {
        if (initialSashWeightsApplied || sash.isDisposed())
            return;
        int total = sash.getClientArea().width;
        if (total <= 0)
            return;
        initialSashWeightsApplied = true;
        applySashWeightsForColumnWidth(CollectionIndexColumnWidthStore.load());
        if (!indexTable.isDisposed())
            indexTable.getDisplay().asyncExec(this::syncIndexColumnToFillPane);
    }

    private void onIndexColumnHeaderResized(TableColumn column)
    {
        if (syncingIndexLayout || indexTable.isDisposed())
            return;
        int width = CollectionIndexColumnWidthStore.clamp(column.getWidth());
        // #region agent log
        CollectionLoadDebug.log("H1", "CollectionSplitTable.onIndexColumnHeaderResized", "header drag", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"width\":" + width + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        persistIndexColumnWidth(width);
        applySashWeightsForColumnWidth(width);
    }

    private void persistIndexColumnWidth(int width)
    {
        width = CollectionIndexColumnWidthStore.clamp(width);
        CollectionIndexColumnWidthStore.save(width);
        syncingIndexLayout = true;
        try
        {
            if (indexTable.getColumnCount() > 0)
                indexTable.getColumn(0).setWidth(width);
        }
        finally
        {
            syncingIndexLayout = false;
        }
    }

    private void applySashWeightsForColumnWidth(int columnWidth)
    {
        if (sash.isDisposed() || syncingIndexLayout)
            return;
        int total = sash.getClientArea().width;
        if (total <= 0)
            return;
        columnWidth = CollectionIndexColumnWidthStore.clamp(columnWidth);
        int dataWeight = Math.max(1, total - columnWidth);
        syncingIndexLayout = true;
        try
        {
            sash.setWeights(new int[] { columnWidth, dataWeight });
        }
        finally
        {
            syncingIndexLayout = false;
        }
    }

    private int indexColumnWidthFromPane()
    {
        int client = indexTable.getClientArea().width;
        if (client <= 0)
            client = indexTable.getBounds().width;
        if (indexTable.getLinesVisible() && client > 0)
            client = Math.max(CollectionIndexColumnWidthStore.MIN_WIDTH,
                client - indexTable.getGridLineWidth());
        return CollectionIndexColumnWidthStore.clamp(client);
    }

    private void installVerticalScrollSync()
    {
        linkVerticalScroll(indexTable, dataTable);
        linkVerticalScroll(dataTable, indexTable);
    }

    private void linkVerticalScroll(Table source, Table target)
    {
        ScrollBar bar = source.getVerticalBar();
        if (bar == null)
            return;
        bar.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                if (syncingScroll || source.isDisposed() || target.isDisposed())
                    return;
                syncingScroll = true;
                try
                {
                    target.setTopIndex(source.getTopIndex());
                }
                finally
                {
                    syncingScroll = false;
                }
            }
        });
    }

    private void installSelectionSync()
    {
        indexTable.addListener(SWT.Selection, e -> syncSelectionFrom(indexTable, dataTable));
        dataTable.addListener(SWT.Selection, e -> syncSelectionFrom(dataTable, indexTable));
    }

    private void syncSelectionFrom(Table source, Table target)
    {
        if (syncingSelection || source.isDisposed() || target.isDisposed())
            return;
        TableItem[] sel = source.getSelection();
        if (sel == null || sel.length == 0)
            return;
        int idx = source.indexOf(sel[0]);
        if (idx < 0 || idx >= target.getItemCount())
            return;
        syncingSelection = true;
        try
        {
            TableItem targetItem = target.getItem(idx);
            if (targetItem != null && !targetItem.isDisposed())
                target.setSelection(targetItem);
        }
        finally
        {
            syncingSelection = false;
        }
    }
}
