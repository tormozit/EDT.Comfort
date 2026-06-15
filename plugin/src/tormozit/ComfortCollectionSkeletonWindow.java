package tormozit;

import java.util.Arrays;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;


/**
 * Окно-скелет «Коллекция» для теста UI: виртуальная таблица без dbgs.
 */
public final class ComfortCollectionSkeletonWindow implements CollectionTableHost
{
    public static final int ROW_COUNT = 1000;
    /** Видимых колонок в таблице (включая «Индекс»). */
    public static final int VISIBLE_COLUMN_COUNT = 100;

    private Shell shell;
    private Table table;
    private FilterInputBox filterInput;
    private Label progressLabel;
    private CollectionColumnModel columns;
    private CollectionShellPin shellPin;
    private CollectionTableInteraction interaction;
    private SmartMatcher filterMatcher;
    private CollectionDisplayIndexMap displayIndexMap = CollectionDisplayIndexMap.identity(ROW_COUNT);
    private Runnable viewportDebounce;
    private int visibleRowCount = ROW_COUNT;

    private ComfortCollectionSkeletonWindow() {}

    public static void open()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> new ComfortCollectionSkeletonWindow().openWindow());
    }

    private void openWindow()
    {
        Display display = Display.getDefault();
        columns = CollectionColumnModel.skeleton(VISIBLE_COLUMN_COUNT - 1);

        shell = new Shell(display, SWT.SHELL_TRIM | SWT.MAX | SWT.RESIZE);
        shell.setText(Global.withPluginWindowTitle("Коллекция", //$NON-NLS-1$
            "[скелет " + ROW_COUNT + "×" + columns.columnCount() + "]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        shell.setLayout(new GridLayout(1, false));
        shell.setMinimumSize(640, 400);

        createToolbar(shell);
        createFilterRow(shell);
        createTable(shell);
        createProgress(shell);
        interaction = new CollectionTableInteraction(table, this);
        interaction.install();
        hookTableEvents();
        hookFilterHighlight();

        updateProgressLabel();
        table.setItemCount(visibleRowCount);

        shell.pack();
        shell.setSize(900, 600);
        shell.open();
        shell.addDisposeListener(e -> disposeWindow());
        shellPin = new CollectionShellPin(shell);
        shell.setData("tormozit.collectionShellPin", shellPin); //$NON-NLS-1$
        shellPin.install();

        ComfortCollectionDebug.step("skeleton", "open rows=" + ROW_COUNT + " cols=" + columns.columnCount()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void createToolbar(Composite parent)
    {
        ToolBar bar = new ToolBar(parent, SWT.FLAT | SWT.RIGHT);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        ToolItem reopen = new ToolItem(bar, SWT.PUSH);
        reopen.setText("Скелет"); //$NON-NLS-1$
        reopen.setToolTipText("Открыть ещё одно окно-скелет 1000×100"); //$NON-NLS-1$
        reopen.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                open();
            }
        });
    }

    private void createFilterRow(Composite parent)
    {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        layout.marginTop = 3;
        layout.marginBottom = 3;
        layout.verticalSpacing = 0;
        row.setLayout(layout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        filterInput = FilterInputBox.forCollection(row, this::applyFilterNow);
    }

    private void clearFilter()
    {
        if (filterInput == null || filterInput.isDisposed())
            return;
        filterInput.setText(""); //$NON-NLS-1$
        applyFilterNow();
    }

    private void createTable(Composite parent)
    {
        Composite tableStack = new Composite(parent, SWT.NONE);
        tableStack.setLayout(null);
        tableStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite columnHost = new Composite(tableStack, SWT.NONE);
        columnHost.setLayout(new TableColumnLayout(true));

        table = new Table(columnHost, SWT.VIRTUAL | SWT.FULL_SELECTION | SWT.BORDER | SWT.MULTI);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        columns.syncTableHeaders(table);
    }

    private void createProgress(Composite parent)
    {
        progressLabel = new Label(parent, SWT.NONE);
        progressLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        progressLabel.setText(""); //$NON-NLS-1$
    }

    private void hookTableEvents()
    {
        table.addListener(SWT.SetData, this::onSetData);

        ScrollBar vertical = table.getVerticalBar();
        if (vertical != null)
            vertical.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    scheduleViewportLogDebounced();
                }
            });
        ScrollBar horizontal = table.getHorizontalBar();
        if (horizontal != null)
            horizontal.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    scheduleViewportLogDebounced();
                }
            });
    }

    @Override
    public Table collectionTable()
    {
        return table;
    }

    @Override
    public SmartMatcher activeFilterMatcher()
    {
        return filterMatcher;
    }

    @Override
    public int displayIndexToLogical(int displayIndex)
    {
        if (filterMatcher == null)
            return displayIndex >= 0 && displayIndex < ROW_COUNT ? displayIndex : -1;
        return displayIndexMap.logicalAt(displayIndex);
    }

    @Override
    public String getCellDisplayText(int logicalRow, int visibleCol)
    {
        return cellText(logicalRow, visibleCol);
    }

    private void hookFilterHighlight()
    {
        table.addListener(SWT.EraseItem, e -> {
            if (e.type != SWT.EraseItem || !(e.item instanceof TableItem item))
                return;
            CollectionFilterEraseSupport.handleEraseItem(
                table,
                item,
                e,
                filterMatcher,
                interaction != null ? interaction.selectedItem() : null,
                this);
        });
    }

    private void onSetData(Event event)
    {
        TableItem item = (TableItem) event.item;
        int displayIndex = event.index;
        int logical = displayIndexToLogical(displayIndex);
        if (logical < 0)
            return;

        CollectionTableItemKeys.bindRow(item, displayIndex, logical);

        for (int col = 0; col < columns.columnCount(); col++)
        {
            String text = cellText(logical, col);
            item.setText(col, text);
        }
    }

    private void applyFilterNow()
    {
        String text = filterInput.getText();
        if (text == null || text.isBlank())
        {
            int anchorLogical = -1;
            if (filterMatcher != null)
                anchorLogical = CollectionFilterViewportAnchor.captureLogicalRow(
                    table, interaction, this::displayIndexToLogical);

            filterMatcher = null;
            visibleRowCount = ROW_COUNT;
            displayIndexMap = CollectionDisplayIndexMap.identity(ROW_COUNT);
            table.setItemCount(Math.max(visibleRowCount, 0));
            table.clearAll();
            if (anchorLogical >= 0)
                CollectionFilterViewportAnchor.restoreLogicalRow(
                    table, anchorLogical, ROW_COUNT, interaction);
            updateProgressLabel();
            scheduleViewportLogDebounced();
            return;
        }

        filterMatcher = new SmartMatcher(text);
        int[] logicalRows = new int[ROW_COUNT];
        int count = 0;
        for (int row = 0; row < ROW_COUNT; row++)
        {
            if (filterMatcher.matches(rowSummary(row)))
                logicalRows[count++] = row;
        }
        visibleRowCount = count;
        displayIndexMap = CollectionDisplayIndexMap.fromLogicalRows(Arrays.copyOf(logicalRows, count));
        table.setItemCount(Math.max(visibleRowCount, 0));
        table.clearAll();
        updateProgressLabel();
        scheduleViewportLogDebounced();
    }

    private void scheduleViewportLogDebounced()
    {
        if (table == null || table.isDisposed())
            return;
        if (viewportDebounce != null)
            table.getDisplay().timerExec(-1, viewportDebounce);
        viewportDebounce = () -> {
            viewportDebounce = null;
            logViewport();
        };
        table.getDisplay().timerExec(150, viewportDebounce);
    }

    private void logViewport()
    {
        if (table == null || table.isDisposed())
            return;
        int top = table.getTopIndex();
        int bottom = top + visibleRowCountEstimate();
        int logicalTop = displayIndexToLogical(top);
        int logicalBottom = displayIndexToLogical(Math.min(bottom, table.getItemCount() - 1));
        int colFrom = CollectionViewportTracker.firstVisibleColumn(table);
        int colTo = CollectionViewportTracker.lastVisibleColumn(table);
        ComfortCollectionDebug.step("skeleton viewport", //$NON-NLS-1$
            "rows=" + logicalTop + ".." + logicalBottom + " cols=" + colFrom + ".." + colTo); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private int visibleRowCountEstimate()
    {
        if (table.getItemHeight() <= 0)
            return 20;
        return table.getClientArea().height / table.getItemHeight() + 2;
    }

    private String rowSummary(int logicalRow)
    {
        StringBuilder sb = new StringBuilder();
        for (int col = 0; col < columns.columnCount(); col++)
        {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(cellText(logicalRow, col));
        }
        return sb.toString();
    }

    private String cellText(int logicalRow, int visibleCol)
    {
        CollectionColumnModel.Column col = columns.columnAt(visibleCol);
        if (col == null)
            return ""; //$NON-NLS-1$
        if (col.kind == CollectionColumnModel.Kind.INDEX)
            return String.valueOf(logicalRow);
        return "R" + logicalRow + "·" + col.header; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void updateProgressLabel()
    {
        if (progressLabel == null || progressLabel.isDisposed())
            return;
        if (filterMatcher == null)
            progressLabel.setText("100% (" + ROW_COUNT + "/" + ROW_COUNT + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        else
            progressLabel.setText(visibleRowCount + " / " + ROW_COUNT + " (фильтр)"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void disposeWindow()
    {
        if (interaction != null)
            interaction.dispose();
        if (shellPin != null)
        {
            shellPin.dispose();
            shellPin = null;
        }
    }
}
