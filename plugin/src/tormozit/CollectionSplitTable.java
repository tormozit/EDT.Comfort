package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
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
    private final Composite indexPane;
    private final Label indexBottomSpacer;
    private final Composite indexTableStack;
    private final Table indexTable;
    private final Table dataTable;
    private boolean syncingScroll;
    private boolean syncingSelection;
    private boolean syncingIndexLayout;
    private boolean syncingRowArea;
    private boolean indexColumnSizingInstalled;
    private boolean sashSizingInstalled;
    private boolean initialSashWeightsApplied;

    private CollectionSplitTable(Composite panel, SashForm sash, Composite indexPane,
        Label indexBottomSpacer, Composite indexTableStack, Table indexTable, Table dataTable)
    {
        this.panel = panel;
        this.sash = sash;
        this.indexPane = indexPane;
        this.indexBottomSpacer = indexBottomSpacer;
        this.indexTableStack = indexTableStack;
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

        Composite indexPane = new Composite(sash, SWT.NONE);
        GridLayout indexPaneLayout = new GridLayout(1, false);
        indexPaneLayout.marginWidth = 0;
        indexPaneLayout.marginHeight = 0;
        indexPane.setLayout(indexPaneLayout);

        Composite indexTableStack = new Composite(indexPane, SWT.NONE);
        indexTableStack.setLayout(null);
        indexTableStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite indexColumnHost = new Composite(indexTableStack, SWT.NONE);
        indexColumnHost.setLayout(new TableColumnLayout(true));

        Table indexTable = new Table(indexColumnHost, style);
        indexTable.setHeaderVisible(true);
        indexTable.setLinesVisible(true);

        Label indexBottomSpacer = new Label(indexPane, SWT.NONE);
        GridData spacerGd = new GridData(SWT.FILL, SWT.END, true, false);
        spacerGd.heightHint = 0;
        indexBottomSpacer.setLayoutData(spacerGd);

        Composite dataTableStack = new Composite(sash, SWT.NONE);
        dataTableStack.setLayout(null);

        Composite dataColumnHost = new Composite(dataTableStack, SWT.NONE);
        dataColumnHost.setLayout(new TableColumnLayout(true));

        Table dataTable = new Table(dataColumnHost, style);
        dataTable.setHeaderVisible(true);
        dataTable.setLinesVisible(true);

        CollectionSplitTable split = new CollectionSplitTable(panel, sash, indexPane,
            indexBottomSpacer, indexTableStack, indexTable, dataTable);
        split.installVerticalScrollSync();
        split.installSelectionSync();
        split.installSashSizing();
        split.installIndexPaneColumnSync();
        split.installRowAreaAlignmentSync();
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
        applySashWeightsForColumnWidth(CollectionFixedPaneWidthStore.load());
        syncFixedPaneLayout();
        syncRowAreaAlignment();
    }

    /** Сохранить ширину левой панели и колонок «Индекс» / «Тип» / «Представление» (при закрытии окна). */
    void persistFixedPaneWidth()
    {
        persistIndexColumnWidthsFromUi();
        if (indexPane.isDisposed())
            return;
        int width = indexPane.getClientArea().width;
        if (width <= 0)
            width = indexPane.getBounds().width;
        if (width > 0)
            CollectionFixedPaneWidthStore.save(width);
    }

    private void persistIndexColumnWidthsFromUi()
    {
        if (indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
            return;
        int indexW = indexTable.getColumn(0).getWidth();
        if (indexW > 0)
            CollectionIndexColumnWidthStore.save(indexW);
        if (indexTable.getColumnCount() > 1)
        {
            int typeW = indexTable.getColumn(1).getWidth();
            if (typeW > 0)
                CollectionTypeColumnWidthStore.save(typeW);
        }
        if (indexTable.getColumnCount() > 2)
        {
            int presW = indexTable.getColumn(2).getWidth();
            if (presW > 0)
                CollectionPresentationColumnWidthStore.save(presW);
        }
    }

    private int fixedPaneWidth()
    {
        if (indexTable.isDisposed())
            return CollectionIndexColumnWidthStore.load();
        int width = 0;
        for (org.eclipse.swt.widgets.TableColumn column : indexTable.getColumns())
            width += column.getWidth();
        if (width <= 0)
            width = CollectionIndexColumnWidthStore.load();
        if (indexTable.getColumnCount() > 1)
            return width;
        return CollectionIndexColumnWidthStore.clamp(width);
    }

    private void syncFixedPaneColumnWidths()
    {
        if (indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
            return;
        if (indexTable.getColumnCount() == 1)
        {
            syncIndexColumnToFillPane();
            return;
        }
        int paneW = fixedPaneInnerWidth();
        int indexW = CollectionIndexColumnWidthStore.load();
        int typeW = CollectionTypeColumnWidthStore.load();
        int colCount = indexTable.getColumnCount();
        if (colCount == 2)
        {
            int maxIndex = Math.max(CollectionIndexColumnWidthStore.MIN_WIDTH,
                paneW - CollectionTypeColumnWidthStore.MIN_WIDTH);
            if (indexW > maxIndex)
            {
                indexW = maxIndex;
                CollectionIndexColumnWidthStore.save(indexW);
            }
            typeW = Math.max(CollectionTypeColumnWidthStore.MIN_WIDTH, paneW - indexW);
        }
        else
        {
            int presW = CollectionPresentationColumnWidthStore.load();
            int minPres = CollectionPresentationColumnWidthStore.MIN_WIDTH;
            int minReserved = CollectionIndexColumnWidthStore.MIN_WIDTH
                + CollectionTypeColumnWidthStore.MIN_WIDTH;
            int maxReserved = Math.max(minReserved, paneW - minPres);
            int reserved = indexW + typeW;
            if (reserved > maxReserved)
            {
                int overflow = reserved - maxReserved;
                int typeShrink = Math.min(overflow, typeW - CollectionTypeColumnWidthStore.MIN_WIDTH);
                typeW -= typeShrink;
                overflow -= typeShrink;
                if (overflow > 0)
                {
                    indexW = Math.max(CollectionIndexColumnWidthStore.MIN_WIDTH, indexW - overflow);
                    CollectionIndexColumnWidthStore.save(indexW);
                }
            }
            presW = Math.max(minPres, paneW - indexW - typeW);
            syncingIndexLayout = true;
            try
            {
                indexTable.getColumn(0).setWidth(indexW);
                indexTable.getColumn(1).setWidth(typeW);
                indexTable.getColumn(2).setWidth(presW);
                CollectionPresentationColumnWidthStore.save(presW);
            }
            finally
            {
                syncingIndexLayout = false;
            }
            return;
        }
        syncingIndexLayout = true;
        try
        {
            indexTable.getColumn(0).setWidth(indexW);
            indexTable.getColumn(1).setWidth(typeW);
        }
        finally
        {
            syncingIndexLayout = false;
        }
    }

    /**
     * Выравнивает область строк «Индекс» и данных: у data-таблицы снизу занимает место
     * горизонтальный скролл, у index — нет; компенсируем нижним отступом панели индекса.
     */
    void syncRowAreaAlignment()
    {
        if (syncingRowArea || indexPane.isDisposed() || indexTable.isDisposed() || dataTable.isDisposed())
            return;
        syncingRowArea = true;
        try
        {
            int bottomInset = horizontalScrollBarOccupiedHeight(dataTable);
            GridData spacerGd = (GridData) indexBottomSpacer.getLayoutData();
            boolean changed = false;
            if (spacerGd.heightHint != bottomInset)
            {
                spacerGd.heightHint = bottomInset;
                indexBottomSpacer.setVisible(bottomInset > 0);
                changed = true;
            }

            int dataHeader = dataTable.getHeaderVisible() ? dataTable.getHeaderHeight() : 0;
            int indexHeader = indexTable.getHeaderVisible() ? indexTable.getHeaderHeight() : 0;
            int topInset = Math.max(0, dataHeader - indexHeader);
            GridData indexGd = (GridData) indexTableStack.getLayoutData();
            if (indexGd.verticalIndent != topInset)
            {
                indexGd.verticalIndent = topInset;
                changed = true;
            }

            if (changed)
                indexPane.layout(true);
        }
        finally
        {
            syncingRowArea = false;
        }
    }

    /** Слушатель перетаскивания границы колонок фиксированной панели. */
    void installIndexColumnSizing()
    {
        if (indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
            return;
        indexColumnSizingInstalled = true;
        for (TableColumn column : indexTable.getColumns())
        {
            if (column.getData("tormozit.fixedColSizing") != null) //$NON-NLS-1$
                continue;
            column.setData("tormozit.fixedColSizing", Boolean.TRUE); //$NON-NLS-1$
            column.addControlListener(new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
                {
                    onFixedPaneColumnHeaderResized(column);
                }
            });
        }
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
            syncTopIndexBetweenTables(dataTable, indexTable);
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
            indexTable.getDisplay().asyncExec(this::syncFixedPaneLayout);
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
                syncFixedPaneLayout();
            }
        });
    }

    private void installRowAreaAlignmentSync()
    {
        ControlAdapter resizeListener = new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                syncRowAreaAlignment();
            }
        };
        dataTable.addControlListener(resizeListener);
        indexTable.addControlListener(resizeListener);
        sash.addListener(SWT.Resize, e -> syncRowAreaAlignment());

        ScrollBar hBar = dataTable.getHorizontalBar();
        if (hBar != null)
        {
            hBar.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    syncRowAreaAlignment();
                }
            });
        }

        dataTable.getDisplay().asyncExec(this::syncRowAreaAlignment);
    }

    private static int horizontalScrollBarOccupiedHeight(Table table)
    {
        ScrollBar bar = table.getHorizontalBar();
        if (bar == null || bar.isDisposed())
            return 0;
        int height = bar.getSize().y;
        if (height <= 0)
            return 0;
        int scrollRange = bar.getMaximum() - bar.getMinimum() - bar.getThumb() + 1;
        return scrollRange > 0 ? height : 0;
    }

    private void syncIndexColumnToFillPane()
    {
        if (syncingIndexLayout || indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
            return;
        if (indexTable.getColumnCount() > 1)
        {
            syncFixedPaneColumnWidths();
            return;
        }
        int target = indexColumnWidthFromPane();
        TableColumn column = indexTable.getColumn(0);
        int current = column.getWidth();
        if (Math.abs(target - current) < 2)
            return;
        syncingIndexLayout = true;
        try
        {
            column.setWidth(target);
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
        applySashWeightsForColumnWidth(CollectionFixedPaneWidthStore.load());
        if (!indexTable.isDisposed())
            indexTable.getDisplay().asyncExec(this::syncFixedPaneLayout);
    }

    /** Две колонки — «Индекс» + «Тип»; три — + «Представление» с сохранёнными ширинами. */
    private void syncFixedPaneLayout()
    {
        if (indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
            return;
        if (indexTable.getColumnCount() > 1)
            syncFixedPaneColumnWidths();
        else
            syncIndexColumnToFillPane();
    }

    private void onFixedPaneColumnHeaderResized(TableColumn column)
    {
        if (syncingIndexLayout || indexTable.isDisposed() || column == null || column.isDisposed())
            return;
        int columnIndex = indexOfColumn(column);
        if (columnIndex == 0)
        {
            int width = CollectionIndexColumnWidthStore.clamp(column.getWidth());
            CollectionIndexColumnWidthStore.save(width);
            if (indexTable.getColumnCount() > 1)
                syncFixedPaneColumnWidths();
            else
                persistIndexColumnWidth(width);
        }
        else if (columnIndex == 1)
        {
            CollectionTypeColumnWidthStore.save(column.getWidth());
            if (indexTable.getColumnCount() > 1)
                syncFixedPaneColumnWidths();
        }
        else if (columnIndex == 2)
        {
            CollectionPresentationColumnWidthStore.save(column.getWidth());
            if (indexTable.getColumnCount() > 2)
                syncFixedPaneColumnWidths();
        }
        if (indexTable.getColumnCount() <= 1)
            applySashWeightsForColumnWidth(fixedPaneWidth());
    }

    private int indexOfColumn(TableColumn column)
    {
        org.eclipse.swt.widgets.TableColumn[] columns = indexTable.getColumns();
        for (int i = 0; i < columns.length; i++)
        {
            if (columns[i] == column)
                return i;
        }
        return -1;
    }

    private void onIndexColumnHeaderResized(TableColumn column)
    {
        onFixedPaneColumnHeaderResized(column);
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
        if (indexTable.getColumnCount() > 1)
        {
            int maxFixed = CollectionIndexColumnWidthStore.MAX_WIDTH
                + CollectionTypeColumnWidthStore.MAX_WIDTH;
            if (indexTable.getColumnCount() > 2)
                maxFixed += CollectionPresentationColumnWidthStore.MAX_WIDTH;
            columnWidth = Math.min(maxFixed, Math.max(CollectionIndexColumnWidthStore.MIN_WIDTH, columnWidth));
        }
        else
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
        return CollectionIndexColumnWidthStore.clamp(fixedPaneInnerWidth());
    }

    /** Ширина клиентской области index-таблицы без усечения до MAX колонки «Индекс». */
    private int fixedPaneInnerWidth()
    {
        int client = indexTable.getClientArea().width;
        if (client <= 0)
            client = indexTable.getBounds().width;
        if (indexTable.getLinesVisible() && client > 0)
            client = Math.max(CollectionIndexColumnWidthStore.MIN_WIDTH,
                client - indexTable.getGridLineWidth());
        return Math.max(CollectionIndexColumnWidthStore.MIN_WIDTH, client);
    }

    private void installVerticalScrollSync()
    {
        linkVerticalScroll(dataTable, indexTable);
        linkVerticalScroll(indexTable, dataTable);
        syncTopIndexOnMouseWheel(dataTable, indexTable);
        syncTopIndexOnMouseWheel(indexTable, dataTable);
    }

    private void syncTopIndexOnMouseWheel(Table source, Table target)
    {
        source.addListener(SWT.MouseWheel, e ->
            source.getDisplay().asyncExec(() -> syncTopIndexBetweenTables(source, target)));
    }

    private void syncTopIndexBetweenTables(Table source, Table target)
    {
        if (syncingScroll || source.isDisposed() || target.isDisposed())
            return;
        syncingScroll = true;
        try
        {
            int top = source.getTopIndex();
            if (target.getTopIndex() != top)
                target.setTopIndex(top);
        }
        finally
        {
            syncingScroll = false;
        }
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
                syncTopIndexBetweenTables(source, target);
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
            syncTopIndexBetweenTables(source, target);
        }
        finally
        {
            syncingSelection = false;
        }
    }
}
