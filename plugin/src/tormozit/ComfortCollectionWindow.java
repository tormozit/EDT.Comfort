package tormozit;

import org.eclipse.debug.core.DebugException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;

/**
 * Окно «Коллекция &lt;Тип&gt;» — virtual table без блокировки UI-потока.
 */
public final class ComfortCollectionWindow implements CollectionLoadScheduler.ProgressListener, CollectionTableHost
{
    private final IBslIndexedValue indexedValue;
    private final IBslStackFrame frame;
    private final BslValuePath path;
    private final int cloneIndex;
    private final String registryKey;

    private Shell shell;
    private CollectionSplitTable splitTable;
    private Combo filterField;
    private Label progressLabel;
    private ComfortCollectionTableModel model;
    private CollectionLoadScheduler scheduler;
    private CollectionRowFilter rowFilter;
    private CollectionFindSession findSession;
    private CollectionTableInteraction indexInteraction;
    private CollectionTableInteraction dataInteraction;
    private CollectionShellPin shellPin;

    private Image inspectImage;
    private ToolItem columnSettingsItem;
    private Runnable filterDebounce;
    private Runnable viewportDebounce;
    private Listener filterEraseListenerIndex;
    private Listener filterEraseListenerData;

    public ComfortCollectionWindow(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        int cloneIndex,
        String registryKey)
    {
        this.indexedValue = indexedValue;
        this.frame = frame;
        this.path = path;
        this.cloneIndex = cloneIndex;
        this.registryKey = registryKey;
    }

    void open()
    {
        Display display = Display.getDefault();
        String typeTitle = resolveTypeTitle();
        CollectionColumnModel columns = CollectionColumnModel.minimal();
        model = new ComfortCollectionTableModel(indexedValue, frame, path, columns, typeTitle);

        shell = new Shell(display, SWT.SHELL_TRIM | SWT.MAX | SWT.RESIZE);
        shell.setText(buildTitle(typeTitle));
        GridLayout shellLayout = new GridLayout(1, false);
        shellLayout.marginHeight = 0;
        shellLayout.marginWidth = 0;
        shellLayout.verticalSpacing = 0;
        shell.setLayout(shellLayout);
        shell.setMinimumSize(640, 400);

        createToolbar(shell);
        createFilterRow(shell);
        createTable(shell);
        createProgress(shell);

        rowFilter = new CollectionRowFilter(""); //$NON-NLS-1$
        findSession = new CollectionFindSession(this);
        indexInteraction = new CollectionTableInteraction(splitTable.indexTable(), this, 0);
        indexInteraction.install();
        dataInteraction = new CollectionTableInteraction(splitTable.dataTable(), this, 1);
        dataInteraction.install();
        scheduler = new CollectionLoadScheduler(model, display, this, this::onContextColumnsReady);
        scheduler.bindTable(splitTable.dataTable());
        scheduler.bindShell(shell);
        hookTableEvents();
        hookContextMenu();
        hookFilterHighlight();
        hookShellEvents();

        updateTableItemCount(1);

        shell.pack();
        CollectionWindowGeometryStore.applyToShell(shell);
        shell.open();
        shell.addDisposeListener(e -> disposeWindow());
        scheduler.scheduleInitialLoad();
        shellPin = new CollectionShellPin(shell);
        shell.setData("tormozit.collectionShellPin", shellPin); //$NON-NLS-1$
        shellPin.install();
    }

    Shell collectionShell()
    {
        return shell;
    }

    private Table dataTable()
    {
        return splitTable != null ? splitTable.dataTable() : null;
    }

    private Table indexTable()
    {
        return splitTable != null ? splitTable.indexTable() : null;
    }

    private CollectionTableInteraction activeInteraction()
    {
        Table data = dataTable();
        if (data != null && !data.isDisposed() && data.isFocusControl())
            return dataInteraction;
        Table index = indexTable();
        if (index != null && !index.isDisposed() && index.isFocusControl())
            return indexInteraction;
        return dataInteraction != null ? dataInteraction : indexInteraction;
    }

    void closeAndDispose()
    {
        if (shell != null && !shell.isDisposed())
            shell.close();
        else
            disposeWindow();
    }

    void requestRowLoad(int logicalRow)
    {
        if (scheduler != null)
            scheduler.requestViewport(logicalRow, logicalRow);
    }

    @Override
    public SmartMatcher activeFilterMatcher()
    {
        return rowFilter != null && rowFilter.isActive() ? rowFilter.matcher() : null;
    }

    @Override
    public Table collectionTable()
    {
        return dataTable();
    }

    @Override
    public String getCellDisplayText(int logicalRow, int visibleCol)
    {
        if (model == null)
            return ""; //$NON-NLS-1$
        String text = model.getCellDisplayText(logicalRow, visibleCol);
        return text != null ? text : ""; //$NON-NLS-1$
    }

    void activate()
    {
        if (shell != null && !shell.isDisposed())
        {
            shell.setMinimized(false);
            shell.forceActive();
            shell.forceFocus();
        }
    }

    boolean isDisposed()
    {
        return shell == null || shell.isDisposed();
    }

    void disposeWindow()
    {
        if (shell != null && !shell.isDisposed())
            CollectionWindowGeometryStore.saveFromShell(shell);
        if (scheduler != null)
            scheduler.cancelAll();
        if (shellPin != null)
        {
            shellPin.dispose();
            shellPin = null;
        }
        if (indexInteraction != null)
            indexInteraction.dispose();
        if (dataInteraction != null)
            dataInteraction.dispose();
        if (inspectImage != null && !inspectImage.isDisposed())
            inspectImage.dispose();
        CollectionWindowRegistry.unregister(registryKey);
    }

    Table getTable()
    {
        return dataTable();
    }

    ComfortCollectionTableModel getModel()
    {
        return model;
    }

    @Override
    public int displayIndexToLogical(int displayIndex)
    {
        int total = effectiveTotalSize();
        if (rowFilter != null && rowFilter.isActive())
            return rowFilter.logicalRowAtDisplayIndex(displayIndex, total);
        return displayIndex;
    }

    void selectDisplayRow(int displayIndex, int modelColumn)
    {
        if (splitTable == null)
            return;
        Table data = dataTable();
        Table index = indexTable();
        if (data == null || data.isDisposed() || index == null || index.isDisposed())
            return;
        if (displayIndex < 0 || displayIndex >= splitTable.getItemCount())
            return;
        splitTable.syncSelectionToIndex(displayIndex);
        TableItem indexItem = index.getItem(displayIndex);
        TableItem dataItem = data.getItem(displayIndex);
        if (modelColumn <= 0 && indexInteraction != null && indexItem != null)
            indexInteraction.selectCell(indexItem, 0);
        else if (dataInteraction != null && dataItem != null)
            dataInteraction.selectCell(dataItem, Math.max(0, modelColumn - 1));
    }

    @Override
    public void onProgress(int loaded, int total, String phase)
    {
        if (progressLabel == null || progressLabel.isDisposed())
            return;
        if (total <= 0 && !"rows".equals(phase)) //$NON-NLS-1$
        {
            progressLabel.setText("Загрузка…"); //$NON-NLS-1$
            return;
        }
        if ("size".equals(phase) && total > 0) //$NON-NLS-1$
        {
            progressLabel.setText("100% (" + total + "/" + total + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            updateTableItemCount(total);
            return;
        }
        if ("filter".equals(phase)) //$NON-NLS-1$
        {
            int percent = (int) ((long) loaded * 100L / total);
            progressLabel.setText(percent + "% (" + loaded + "/" + total + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }
        if ("rows".equals(phase)) //$NON-NLS-1$
        {
            int collectionTotal = model != null && model.totalSize > 0 ? model.totalSize : total;
            int autoLimit = CollectionLoadScheduler.AUTO_LOAD_ROW_LIMIT;
            if (collectionTotal > autoLimit)
                progressLabel.setText(loaded + "/" + collectionTotal + " (авто до " + autoLimit + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            else if (collectionTotal > 0)
            {
                int percent = (int) ((long) loaded * 100L / collectionTotal);
                progressLabel.setText(percent + "% (" + loaded + "/" + collectionTotal + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            else
                progressLabel.setText(loaded + " строк"); //$NON-NLS-1$
            return;
        }
        int percent = total > 0 ? (int) ((long) loaded * 100L / total) : 0;
        progressLabel.setText(percent + "% (" + loaded + "/" + total + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Override
    public void onRowsReady(int first, int count)
    {
        if (splitTable == null)
            return;
        splitTable.clear(first, Math.min(first + count, splitTable.getItemCount()) - 1);
    }

    private void onContextColumnsReady(com._1c.g5.v8.dt.debug.core.model.IBslVariable[] context)
    {
        if (shell == null || shell.isDisposed() || model == null)
            return;
        CollectionColumnModel rebuilt = CollectionColumnModel.fromContextVariables(context);
        String pathKey = model.pathKey();
        int count = rebuilt.allColumns().size();
        model.columns.replaceColumns(new java.util.ArrayList<>(rebuilt.allColumns()));
        boolean wideUnion = count > CollectionColumnVisibilityStore.WIDE_SCHEMA_THRESHOLD;
        model.columns.applyVisibility(
            CollectionColumnVisibilityStore.visibilityFor(pathKey, count, model.columns),
            wideUnion
                ? CollectionColumnVisibilityStore.orderWithPreferred(model.columns)
                : CollectionColumnVisibilityStore.orderFor(pathKey, count));
        model.clearCellCache();
        if (splitTable != null)
        {
            Table index = indexTable();
            Table data = dataTable();
            if (index != null && data != null)
            {
                index.setRedraw(false);
                data.setRedraw(false);
                try
                {
                    syncSplitTableColumns();
                }
                finally
                {
                    index.setRedraw(true);
                    data.setRedraw(true);
                }
            }
        }
        if (splitTable != null)
            splitTable.clearAll();
        if (model.totalSize > 0)
            updateTableItemCount(model.totalSize);
        if (scheduler != null)
        {
            scheduler.resetLoadJobForSchemaChange();
            scheduler.captureColumnViewport();
        }
        scheduleViewportLoad();
        updateColumnSettingsButton();
        ComfortCollectionDebug.step("columns", //$NON-NLS-1$
            "count=" + model.columns.columnCount() //$NON-NLS-1$
                + (wideUnion ? " wideUnion" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + (model.columns.hasHiddenColumns()
                    ? " hidden=" + model.columns.hiddenColumnCount() : "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String pathKeyFromPath()
    {
        if (path == null)
            return ""; //$NON-NLS-1$
        String expr = path.getExpression();
        if (expr != null && !expr.isBlank())
            return expr.trim();
        String text = path.toString();
        return text != null ? text.trim() : ""; //$NON-NLS-1$
    }

    private String resolveTypeTitle()
    {
        try
        {
            String typeName = indexedValue.getValueTypeName();
            if (typeName != null && !typeName.isBlank())
                return typeName.trim();
        }
        catch (Exception ignored)
        {
            // fallback
        }
        return "Коллекция"; //$NON-NLS-1$
    }

    private String buildTitle(String typeTitle)
    {
        String suffix = typeTitle;
        if (cloneIndex > 0)
            suffix = suffix + " (" + cloneIndex + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        return Global.withPluginWindowTitle("Коллекция", suffix); //$NON-NLS-1$
    }

    private void createToolbar(Composite parent)
    {
        ToolBar bar = new ToolBar(parent, SWT.FLAT | SWT.RIGHT);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        ToolItem clone = new ToolItem(bar, SWT.PUSH);
        clone.setText("Клонировать"); //$NON-NLS-1$
        clone.setToolTipText("Открыть копию окна коллекции"); //$NON-NLS-1$
        clone.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                ComfortCollectionOpener.open(indexedValue, frame, path, ComfortCollectionOpener.OpenMode.CLONE);
            }
        });

        ToolItem inspect = new ToolItem(bar, SWT.PUSH);
        inspect.setText(CollectionRowContextSupport.inspectMenuLabel());
        inspectImage = BslInspectSupport.loadInspectCommandImage();
        if (inspectImage != null)
            inspect.setImage(inspectImage);
        inspect.setToolTipText("Открыть выбранный элемент в инспекторе"); //$NON-NLS-1$
        inspect.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                runInspectOnSelection();
            }
        });

        columnSettingsItem = new ToolItem(bar, SWT.PUSH);
        columnSettingsItem.setText("⚙"); //$NON-NLS-1$
        columnSettingsItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                openColumnDialog();
            }
        });
        updateColumnSettingsButton();

        ToolItem skeleton = new ToolItem(bar, SWT.PUSH);
        skeleton.setText("Скелет"); //$NON-NLS-1$
        skeleton.setToolTipText("Открыть тестовую таблицу 1000×50 без отладки"); //$NON-NLS-1$
        skeleton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                ComfortCollectionSkeletonWindow.open();
            }
        });
    }

    private void createFilterRow(Composite parent)
    {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        layout.marginTop = 3;
        layout.marginBottom = 3;
        layout.verticalSpacing = 0;
        layout.horizontalSpacing = 4;
        row.setLayout(layout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label label = new Label(row, SWT.NONE);
        label.setText("Фильтр:"); //$NON-NLS-1$

        Composite field = new Composite(row, SWT.NONE);
        GridLayout fieldLayout = new GridLayout(2, false);
        fieldLayout.marginHeight = 0;
        fieldLayout.marginWidth = 0;
        fieldLayout.horizontalSpacing = 2;
        field.setLayout(fieldLayout);
        GridData fieldGd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        fieldGd.widthHint = CollectionFilterHistory.FILTER_FIELD_MAX_WIDTH;
        field.setLayoutData(fieldGd);

        filterField = CollectionFilterHistory.createCombo(field, this::scheduleFilterApplyDebounced);
        filterField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        CollectionFilterHistory.createClearButton(field, this::clearFilter);
    }

    private void clearFilter()
    {
        if (filterField == null || filterField.isDisposed())
            return;
        if (filterDebounce != null)
        {
            filterField.getDisplay().timerExec(-1, filterDebounce);
            filterDebounce = null;
        }
        filterField.setText(""); //$NON-NLS-1$
        cancelFilterScan();
    }

    private void updateColumnSettingsButton()
    {
        if (columnSettingsItem == null || columnSettingsItem.isDisposed() || model == null)
            return;
        int hidden = model.columns.hiddenColumnCount();
        if (hidden <= 0)
            columnSettingsItem.setText("⚙"); //$NON-NLS-1$
        else
            columnSettingsItem.setText("обрезано " + hidden + " колонок"); //$NON-NLS-1$ //$NON-NLS-2$
        columnSettingsItem.setToolTipText("Настройка колонок"); //$NON-NLS-1$
    }

    private void createTable(Composite parent)
    {
        splitTable = CollectionSplitTable.create(parent);
        syncSplitTableColumns();
    }

    private void syncSplitTableColumns()
    {
        if (splitTable == null || model == null)
            return;
        model.columns.syncSplitTables(splitTable.indexTable(), splitTable.dataTable());
        splitTable.syncIndexColumnLayout();
        splitTable.installIndexColumnSizing();
    }

    private void createProgress(Composite parent)
    {
        progressLabel = new Label(parent, SWT.NONE);
        progressLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        progressLabel.setText("0% (0/0)"); //$NON-NLS-1$
    }

    private void hookShellEvents()
    {
        shell.addListener(SWT.Show, e -> scheduleViewportLoadDebounced());
        shell.addListener(SWT.Deiconify, e -> scheduleViewportLoadDebounced());
    }

    private void hookTableEvents()
    {
        Table index = indexTable();
        Table data = dataTable();
        if (index != null)
        {
            index.addListener(SWT.SetData, this::onSetDataIndex);
            index.addListener(SWT.KeyDown, this::onTableKeyDown);
            index.addListener(SWT.MouseDoubleClick, this::onTableDoubleClick);
            ScrollBar vertical = index.getVerticalBar();
            if (vertical != null)
                vertical.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        scheduleViewportLoadDebounced();
                    }
                });
        }
        if (data != null)
        {
            data.addListener(SWT.SetData, this::onSetDataData);
            data.addListener(SWT.KeyDown, this::onTableKeyDown);
            data.addListener(SWT.MouseDoubleClick, this::onTableDoubleClick);
            ScrollBar vertical = data.getVerticalBar();
            if (vertical != null)
                vertical.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        scheduleViewportLoadDebounced();
                    }
                });
            ScrollBar horizontal = data.getHorizontalBar();
            if (horizontal != null)
                horizontal.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        scheduleViewportLoadDebounced();
                    }
                });
        }
    }

    private void hookFilterHighlight()
    {
        Table index = indexTable();
        if (index != null)
        {
            filterEraseListenerIndex = e -> {
                if (e.type != SWT.EraseItem || !(e.item instanceof TableItem item))
                    return;
                CollectionFilterEraseSupport.handleEraseItem(
                    index,
                    item,
                    e,
                    activeFilterMatcher(),
                    filterSkipItem(item),
                    this,
                    0);
            };
            index.addListener(SWT.EraseItem, filterEraseListenerIndex);
        }
        Table data = dataTable();
        if (data != null)
        {
            filterEraseListenerData = e -> {
                if (e.type != SWT.EraseItem || !(e.item instanceof TableItem item))
                    return;
                CollectionFilterEraseSupport.handleEraseItem(
                    data,
                    item,
                    e,
                    activeFilterMatcher(),
                    filterSkipItem(item),
                    this,
                    1);
            };
            data.addListener(SWT.EraseItem, filterEraseListenerData);
        }
    }

    private TableItem filterSkipItem(TableItem item)
    {
        if (item == null)
            return null;
        if (indexInteraction != null && item == indexInteraction.selectedItem())
            return item;
        if (dataInteraction != null && item == dataInteraction.selectedItem())
            return item;
        return null;
    }

    private void hookContextMenu()
    {
        Table data = dataTable();
        if (data != null)
            data.addListener(SWT.MenuDetect, this::onMenuDetect);
        Table index = indexTable();
        if (index != null)
            index.addListener(SWT.MenuDetect, this::onMenuDetect);
    }

    private void onSetDataIndex(Event event)
    {
        TableItem item = (TableItem) event.item;
        int displayIndex = event.index;
        int logical = displayIndexToLogical(displayIndex);
        if (logical < 0)
            return;

        CollectionTableItemKeys.bindRow(item, displayIndex, logical);
        String text = model.getCellDisplayText(logical, 0);
        item.setText(0, text != null ? text : ""); //$NON-NLS-1$
        requestRowLoadIfNeeded(logical);
    }

    private void onSetDataData(Event event)
    {
        TableItem item = (TableItem) event.item;
        int displayIndex = event.index;
        int logical = displayIndexToLogical(displayIndex);
        if (logical < 0)
            return;

        CollectionTableItemKeys.bindRow(item, displayIndex, logical);

        Table data = dataTable();
        int dataCols = data != null ? data.getColumnCount() : 0;
        for (int dataCol = 0; dataCol < dataCols; dataCol++)
        {
            String text = model.getCellDisplayText(logical, dataCol + 1);
            item.setText(dataCol, text != null ? text : ""); //$NON-NLS-1$
        }
        requestRowLoadIfNeeded(logical);
    }

    private void requestRowLoadIfNeeded(int logical)
    {
        if (scheduler == null)
            return;
        scheduler.captureColumnViewport();
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return;
        int colFrom = 0;
        int colTo = CollectionViewportTracker.lastVisibleColumn(data) + 1;
        if (model.needsRowLoad(logical, colFrom, colTo))
            scheduleViewportLoadDebounced();
    }

    private void onTableKeyDown(Event event)
    {
        if (event.keyCode == 'f' && (event.stateMask & SWT.MOD1) != 0)
        {
            findSession.openFindDialog(shell);
            event.doit = false;
            return;
        }
        if (event.keyCode == SWT.F3)
        {
            findSession.findNext((event.stateMask & SWT.SHIFT) == 0);
            event.doit = false;
        }
    }

    private void onMenuDetect(Event event)
    {
        if (!(event.widget instanceof Table table) || table.isDisposed())
            return;
        Point loc = table.toControl(event.x, event.y);
        selectTableCellAt(table, loc);
        Menu menu = table.getMenu();
        if (menu == null)
        {
            menu = new Menu(table);
            table.setMenu(menu);
        }
        ensureContextMenuItems(menu);
    }

    private void onTableDoubleClick(Event event)
    {
        if (!(event.widget instanceof Table table) || table.isDisposed())
            return;
        if (!selectTableCellAt(table, new Point(event.x, event.y)))
            return;
        runInspectOnSelection();
    }

    /** @return {@code false}, если под координатами нет ячейки */
    private boolean selectTableCellAt(Table table, Point loc)
    {
        if (table == null || table.isDisposed() || loc == null)
            return false;
        TableItem item = table.getItem(loc);
        if (item == null)
            return false;
        splitTable.syncSelectionToIndex(table.indexOf(item));
        int col = columnAt(loc.x, loc.y, item);
        if (table == indexTable() && indexInteraction != null)
            indexInteraction.selectCell(item, col >= 0 ? col : 0);
        else if (dataInteraction != null)
            dataInteraction.selectCell(item, col >= 0 ? col : 0);
        return true;
    }

    private void ensureContextMenuItems(Menu menu)
    {
        if (menu.getData("tormozit.collectionMenu") != null) //$NON-NLS-1$
            return;
        menu.setData("tormozit.collectionMenu", Boolean.TRUE); //$NON-NLS-1$
        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                rebuildContextMenu((Menu) e.widget);
            }
        });
    }

    private void rebuildContextMenu(Menu menu)
    {
        for (MenuItem item : menu.getItems())
            item.dispose();

        MenuItem watch = new MenuItem(menu, SWT.PUSH);
        watch.setText(CollectionRowContextSupport.watchMenuLabel());
        watch.setToolTipText("Добавить элемент в панель «Выражения»"); //$NON-NLS-1$
        watch.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                IBslVariable variable = selectedVariable();
                if (variable != null)
                    CollectionRowContextSupport.showInExpressions(variable, frame);
            }
        });

        MenuItem inspect = new MenuItem(menu, SWT.PUSH);
        inspect.setText(CollectionRowContextSupport.inspectMenuLabel());
        inspect.setToolTipText("Открыть элемент коллекции в инспекторе"); //$NON-NLS-1$
        if (inspectImage != null)
            inspect.setImage(inspectImage);
        inspect.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                runInspectOnSelection();
            }
        });
    }

    private void runInspectOnSelection()
    {
        IBslVariable variable = selectedVariable();
        Table data = dataTable();
        if (variable == null || shell == null || shell.isDisposed() || data == null || data.isDisposed())
            return;
        CollectionTableInteraction interaction = activeInteraction();
        String header = columnHeader(interaction != null ? interaction.modelVisibleColumn() : 0);
        Point anchor = inspectAnchor(data);
        CollectionRowContextSupport.runInspect(variable, frame, header, data, anchor, shell);
    }

    private static Point inspectAnchor(Table table)
    {
        if (table == null || table.isDisposed())
            return new Point(100, 100);
        Rectangle bounds = table.getBounds();
        Point origin = table.toDisplay(0, 0);
        return new Point(origin.x + bounds.width / 2, origin.y + 20);
    }

    private IBslVariable selectedVariable()
    {
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return null;
        TableItem[] sel = data.getSelection();
        if (sel == null || sel.length == 0)
        {
            Table index = indexTable();
            if (index != null && !index.isDisposed())
                sel = index.getSelection();
        }
        if (sel == null || sel.length == 0)
            return null;
        int displayIndex = CollectionTableItemKeys.displayIndex(sel[0], sel[0].getParent());
        if (displayIndex < 0)
            displayIndex = data.indexOf(sel[0]);
        int logical = displayIndexToLogical(displayIndex);
        if (logical < 0)
            return null;
        return model.getRowVariable(logical);
    }

    private String columnHeader(int visibleCol)
    {
        CollectionColumnModel.Column col = model.columns.columnAt(visibleCol);
        return col != null ? col.header : ""; //$NON-NLS-1$
    }

    private void scheduleFilterApplyDebounced()
    {
        if (filterField == null || filterField.isDisposed())
            return;
        if (filterDebounce != null)
            filterField.getDisplay().timerExec(-1, filterDebounce);
        filterDebounce = () -> {
            filterDebounce = null;
            if (filterField != null && !filterField.isDisposed())
                applyFilterNow();
        };
        filterField.getDisplay().timerExec(150, filterDebounce);
    }

    private void cancelFilterScan()
    {
        int anchorLogical = -1;
        Table data = dataTable();
        CollectionTableInteraction interaction = activeInteraction();
        if (rowFilter != null && rowFilter.isActive())
            anchorLogical = CollectionFilterViewportAnchor.captureLogicalRow(
                data, interaction, this::displayIndexToLogical);

        if (rowFilter != null)
            rowFilter.cancelScan();
        if (scheduler != null)
            scheduler.cancelFilterScan();
        rowFilter = new CollectionRowFilter(""); //$NON-NLS-1$
        int total = effectiveTotalSize();
        updateTableItemCount(total);
        if (splitTable != null)
        {
            splitTable.clearAll();
            if (anchorLogical >= 0)
            {
                CollectionFilterViewportAnchor.restoreLogicalRow(data, anchorLogical, total, interaction);
                splitTable.setTopIndex(data.getTopIndex());
            }
            scheduleViewportLoad();
        }
    }

    private void applyFilterNow()
    {
        if (filterField == null || filterField.isDisposed())
            return;
        String text = filterField.getText();
        if (text == null || text.isBlank())
        {
            cancelFilterScan();
            return;
        }
        if (rowFilter != null)
            rowFilter.cancelScan();
        if (scheduler != null)
            scheduler.cancelFilterScan();
        rowFilter = new CollectionRowFilter(text);
        int total = effectiveTotalSize();
        if (!rowFilter.isActive())
        {
            updateTableItemCount(total);
            if (splitTable != null)
                splitTable.clearAll();
            scheduleViewportLoad();
            return;
        }
        rowFilter.beginScan(total);
        scheduler.scheduleFilterScan(rowFilter, () -> {
            if (rowFilter.isCancelled())
                return;
            updateTableItemCount(rowFilter.visibleCount(total));
            if (splitTable != null)
                splitTable.clearAll();
            scheduleViewportLoad();
        });
    }

    private void scheduleViewportLoadDebounced()
    {
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return;
        if (viewportDebounce != null)
            data.getDisplay().timerExec(-1, viewportDebounce);
        viewportDebounce = () -> {
            viewportDebounce = null;
            scheduleViewportLoad();
        };
        data.getDisplay().timerExec(150, viewportDebounce);
    }

    private void scheduleViewportLoad()
    {
        if (splitTable == null || scheduler == null)
            return;
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return;
        int first = splitTable.getTopIndex();
        int last = first + visibleRowCountEstimate();
        int logicalFirst = displayIndexToLogical(first);
        int logicalLast = displayIndexToLogical(Math.min(last, splitTable.getItemCount() - 1));
        if (logicalFirst >= 0 && logicalLast >= logicalFirst)
            scheduler.requestViewport(logicalFirst, logicalLast);
    }

    private int visibleRowCountEstimate()
    {
        Table data = dataTable();
        if (data == null || data.isDisposed() || data.getItemHeight() <= 0)
            return 20;
        return data.getClientArea().height / data.getItemHeight() + 2;
    }

    private int effectiveTotalSize()
    {
        if (splitTable == null)
            return 1;
        return model.totalSize > 0 ? model.totalSize : Math.max(splitTable.getItemCount(), 1);
    }

    private void updateTableItemCount(int count)
    {
        if (rowFilter != null && rowFilter.isActive())
            count = rowFilter.visibleCount(count);
        if (count < 0)
            count = 0;
        if (splitTable != null)
            splitTable.setItemCount(count);
    }

    private void openColumnDialog()
    {
        int focusModel = activeColumnModelIndex();
        openColumnDialog(null, focusModel);
    }

    private void openColumnDialog(int[] enableAndPreselectModelIndices)
    {
        openColumnDialog(enableAndPreselectModelIndices, -1);
    }

    private void openColumnDialog(int[] enableAndPreselectModelIndices, int focusModelIndex)
    {
        int modelCount = model.columns.allColumns().size();
        boolean[] vis = CollectionColumnVisibilityStore.visibilityFor(model.pathKey(), modelCount, model.columns);
        int[] order = columnOrderForDialog(modelCount);
        if (enableAndPreselectModelIndices != null)
        {
            for (int idx : enableAndPreselectModelIndices)
            {
                if (idx >= 0 && idx < vis.length)
                    vis[idx] = true;
            }
        }
        CollectionColumnVisibilityDialog dialog =
            new CollectionColumnVisibilityDialog(shell, model.columns, vis, order);
        if (enableAndPreselectModelIndices != null && enableAndPreselectModelIndices.length > 0)
            dialog.preselectModelIndices(enableAndPreselectModelIndices);
        else if (focusModelIndex >= 0)
            dialog.focusModelIndex(focusModelIndex);
        if (dialog.open() == org.eclipse.jface.window.Window.OK)
            applyColumnDialogResult(dialog.resultVisibility(), dialog.resultOrder());
    }

    private int activeColumnModelIndex()
    {
        CollectionTableInteraction interaction = activeInteraction();
        if (interaction == null || model == null)
            return -1;
        int visibleCol = interaction.modelVisibleColumn();
        CollectionColumnModel.Column col = model.columns.columnAt(visibleCol);
        return col != null ? col.modelIndex : -1;
    }

    private int[] columnOrderForDialog(int modelCount)
    {
        if (modelCount > CollectionColumnVisibilityStore.WIDE_SCHEMA_THRESHOLD)
            return CollectionColumnVisibilityStore.orderWithPreferred(model.columns);
        return CollectionColumnVisibilityStore.orderFor(model.pathKey(), modelCount);
    }

    private void applyColumnDialogResult(boolean[] visibility, int[] order)
    {
        model.columns.applyVisibility(visibility, order);
        CollectionColumnVisibilityStore.save(model.pathKey(), visibility, order);
        model.clearCellCache();
        syncSplitTableColumns();
        if (splitTable != null)
            splitTable.clearAll();
        if (scheduler != null)
            scheduler.captureColumnViewport();
        scheduleViewportLoad();
        updateColumnSettingsButton();
    }

    private static int columnAt(int x, int y, TableItem item)
    {
        if (item == null)
            return -1;
        Table table = item.getParent();
        for (int i = 0; i < table.getColumnCount(); i++)
        {
            if (item.getBounds(i).contains(x, y))
                return i;
        }
        return 0;
    }
}
