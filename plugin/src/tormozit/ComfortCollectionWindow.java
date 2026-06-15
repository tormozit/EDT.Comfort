package tormozit;

import org.eclipse.debug.core.DebugException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
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
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

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
    private final CollectionCloneSnapshot cloneSnapshot;

    private Shell shell;
    private CollectionSplitTable splitTable;
    private SearchBox filterField;
    private Button filterByPresentationCheckbox;
    private boolean updatingFilterByPresentationCheckbox;
    private Combo presentationField;
    private Text collectionPathField;
    private boolean applyingPresentation;
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
    private ToolItem cloneItem;
    private Runnable viewportDebounce;
    private Runnable rowsReadyDebounce;
    private int rowsReadyClearFirst = -1;
    private int rowsReadyClearLast = -1;
    private Listener filterEraseListenerIndex;
    private Listener filterEraseListenerData;
    private IContextActivation keyContextActivation;
    private Listener collectionKeyFilter;

    public ComfortCollectionWindow(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        int cloneIndex,
        String registryKey)
    {
        this(indexedValue, frame, path, cloneIndex, registryKey, null);
    }

    ComfortCollectionWindow(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        int cloneIndex,
        String registryKey,
        CollectionCloneSnapshot cloneSnapshot)
    {
        this.indexedValue = indexedValue;
        this.frame = frame;
        this.path = path;
        this.cloneIndex = cloneIndex;
        this.registryKey = registryKey;
        this.cloneSnapshot = cloneSnapshot;
    }

    void open()
    {
        Display display = Display.getDefault();
        String typeTitle = resolveTypeTitle();
        if (cloneSnapshot != null)
            model = cloneSnapshot.toModel(typeTitle);
        else
            model = new ComfortCollectionTableModel(
                indexedValue, frame, path, CollectionColumnModel.minimal(), typeTitle);

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

        rowFilter = cloneSnapshot != null ? cloneSnapshot.toRowFilter() : new CollectionRowFilter(""); //$NON-NLS-1$
        findSession = new CollectionFindSession(this);
        indexInteraction = new CollectionTableInteraction(splitTable.indexTable(), this);
        indexInteraction.install();
        dataInteraction = new CollectionTableInteraction(splitTable.dataTable(), this);
        dataInteraction.install();
        scheduler = new CollectionLoadScheduler(model, display, this, this::onContextColumnsReady);
        scheduler.bindTable(splitTable.dataTable());
        scheduler.bindShell(shell);
        hookTableEvents();
        hookContextMenu();
        hookFilterHighlight();
        hookShellEvents();
        installKeyContext();
        installCollectionKeyFilter();

        if (cloneSnapshot != null && cloneSnapshot.schemaResolved())
            prepareCloneWindowBeforeOpen();
        else
            updateTableItemCount(0);

        shell.pack();
        CollectionWindowGeometryStore.applyToShell(shell);
        shell.open();
        shell.addDisposeListener(e -> disposeWindow());
        if (cloneSnapshot != null && cloneSnapshot.schemaResolved())
            startCloneLoadAfterOpen();
        else
            scheduler.scheduleInitialLoad();
        shellPin = new CollectionShellPin(shell);
        shell.setData("tormozit.collectionShellPin", shellPin); //$NON-NLS-1$
        shellPin.install();
    }

    IBslIndexedValue indexedValueForClone()
    {
        return indexedValue;
    }

    IBslStackFrame stackFrameForClone()
    {
        return frame;
    }

    BslValuePath valuePathForClone()
    {
        return path;
    }

    ComfortCollectionTableModel tableModelForClone()
    {
        return model;
    }

    CollectionRowFilter rowFilterForClone()
    {
        return rowFilter;
    }

    boolean filterByPresentationForClone()
    {
        return isFilterByPresentationSelected();
    }

    String filterTextForClone()
    {
        if (filterField == null || filterField.isDisposed())
            return ""; //$NON-NLS-1$
        String text = filterField.getText();
        return text != null ? text : ""; //$NON-NLS-1$
    }

    int topIndexForClone()
    {
        return splitTable != null ? splitTable.getTopIndex() : 0;
    }

    CollectionTableInteraction activeInteractionForClone()
    {
        Table data = dataTable();
        if (dataInteraction != null && data != null && !data.isDisposed() && data.isFocusControl())
            return dataInteraction;
        Table index = indexTable();
        if (indexInteraction != null && index != null && !index.isDisposed() && index.isFocusControl())
            return indexInteraction;
        if (dataInteraction != null && dataInteraction.selectedItem() != null)
            return dataInteraction;
        if (indexInteraction != null && indexInteraction.selectedItem() != null)
            return indexInteraction;
        return dataInteraction != null ? dataInteraction : indexInteraction;
    }

    String currentPresentationHeaderForClone()
    {
        return currentPresentationHeader();
    }

    private void prepareCloneWindowBeforeOpen()
    {
        applyClonePresentationState();
        syncSplitTableColumns();
        updateColumnSettingsButton();
        int total = model.totalSize >= 0 ? model.totalSize : 0;
        updateTableItemCount(total);
        if (filterField != null && !filterField.isDisposed())
            filterField.setText(cloneSnapshot.filterText());
        refreshPresentationCombo();
        selectPresentationInCombo(cloneSnapshot.presentationHeader());
        applyCloneFilterByPresentationCheckbox();
        if (model.totalSize >= 0)
            onProgress(model.loadedRowCount, model.totalSize, "rows"); //$NON-NLS-1$
        updateCloneButtonState();
    }

    /** Восстановить «Представление» в фиксированной панели (слот 2 после «Индекс» и «Тип»). */
    private void applyClonePresentationState()
    {
        if (cloneSnapshot == null || model == null)
            return;
        String header = cloneSnapshot.presentationHeader();
        if (header == null || header.isBlank())
            return;
        int modelIdx = model.columns.findPresentationColumnByHeader(header);
        if (modelIdx < 0)
            return;
        int[] oldVisible = model.columns.copyVisibleToModel();
        if (model.columns.visibleIndexOfModelColumn(modelIdx) < 0)
        {
            String pathKey = model.pathKey();
            boolean[] vis = CollectionColumnVisibilityStore.visibilityFor(
                pathKey, model.columns.modelColumnCount(), model.columns);
            int[] order = columnOrderForDialog(model.columns.modelColumnCount());
            if (modelIdx < vis.length)
                vis[modelIdx] = true;
            model.columns.applyVisibility(vis, order);
        }
        model.columns.applyPresentationHeader(header);
        model.remapCellCacheForVisibleLayout(oldVisible);
    }

    private void startCloneLoadAfterOpen()
    {
        syncSplitTableColumns();
        applyCloneUiState();
        int top = cloneSnapshot.topIndex();
        int last = top + visibleRowCountEstimate();
        int itemCount = splitTable != null ? splitTable.getItemCount() : 0;
        if (itemCount > 0)
            last = Math.min(last, itemCount - 1);
        int logicalFirst = displayIndexToLogical(top);
        int logicalLast = displayIndexToLogical(last);
        if (logicalFirst < 0)
            logicalFirst = 0;
        if (logicalLast < logicalFirst)
            logicalLast = logicalFirst;
        scheduler.scheduleFromClone(logicalFirst, logicalLast);
    }

    private void applyCloneUiState()
    {
        if (cloneSnapshot == null || splitTable == null)
            return;
        splitTable.clearAll();
        splitTable.setTopIndex(cloneSnapshot.topIndex());
        if (cloneSnapshot.selectedDisplayIndex() >= 0)
            selectDisplayRow(cloneSnapshot.selectedDisplayIndex(), cloneSnapshot.selectedVisibleColumn());
    }

    private void selectPresentationInCombo(String header)
    {
        if (presentationField == null || presentationField.isDisposed() || header == null || header.isBlank())
            return;
        String resolved = model != null ? model.columns.resolveHeaderInSchema(header) : null;
        if (resolved == null)
            resolved = header;
        for (int i = 0; i < presentationField.getItemCount(); i++)
        {
            if (resolved.equalsIgnoreCase(presentationField.getItem(i)))
            {
                applyingPresentation = true;
                try
                {
                    presentationField.select(i);
                }
                finally
                {
                    applyingPresentation = false;
                }
                return;
            }
        }
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
        if (splitTable != null)
            splitTable.persistFixedPaneWidth();
        if (shell != null && !shell.isDisposed())
            CollectionWindowGeometryStore.saveFromShell(shell);
        if (scheduler != null)
            scheduler.cancelAll();
        if (shellPin != null)
        {
            shellPin.dispose();
            shellPin = null;
        }
        deactivateKeyContext();
        removeCollectionKeyFilter();
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

    @Override
    public int firstVisibleColumnIndex(Table table)
    {
        if (model == null || splitTable == null)
            return 0;
        if (table == splitTable.indexTable())
            return 0;
        if (table == splitTable.dataTable())
            return model.columns.fixedColumnCount();
        return 0;
    }

    void selectDisplayRow(int displayIndex, int visibleColumn)
    {
        if (splitTable == null || model == null)
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
        int fixed = model.columns.fixedColumnCount();
        if (visibleColumn < fixed && indexInteraction != null && indexItem != null)
            indexInteraction.selectCell(indexItem, visibleColumn);
        else if (dataInteraction != null && dataItem != null)
            dataInteraction.selectCell(dataItem, Math.max(0, visibleColumn - fixed));
    }

    private static String loadedProgressText(String detail)
    {
        return "Загружено " + detail; //$NON-NLS-1$
    }

    private static String percentProgressDetail(int loaded, int total)
    {
        int percent = total > 0 ? (int) ((long) loaded * 100L / total) : 0;
        return percent + "% (" + loaded + "/" + total + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Текст детализации прогресса без префикса «Загружено».
     * {@code null} — промежуточное «Загрузка…».
     */
    private String formatProgressDetail(int loaded, int total, String phase)
    {
        if (total <= 0 && !"rows".equals(phase)) //$NON-NLS-1$
            return null;

        if ("size".equals(phase)) //$NON-NLS-1$
            return total == 0 ? "100% (0/0)" : "100% (" + total + "/" + total + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if ("filter".equals(phase)) //$NON-NLS-1$
            return percentProgressDetail(loaded, total);

        if ("rows".equals(phase)) //$NON-NLS-1$
        {
            int collectionTotal = model != null && model.totalSize > 0 ? model.totalSize : total;
            int autoLimit = CollectionLoadScheduler.AUTO_LOAD_ROW_LIMIT;
            if (collectionTotal > autoLimit)
                return loaded + "/" + collectionTotal + " (авто до " + autoLimit + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (collectionTotal > 0)
                return percentProgressDetail(loaded, collectionTotal);
            if (model != null && model.totalSize == 0)
                return "100% (0/0)"; //$NON-NLS-1$
            return loaded + " строк"; //$NON-NLS-1$
        }

        return percentProgressDetail(loaded, total);
    }

    @Override
    public void onProgress(int loaded, int total, String phase)
    {
        if (progressLabel == null || progressLabel.isDisposed())
            return;

        String detail = formatProgressDetail(loaded, total, phase);
        if (detail == null)
        {
            progressLabel.setText("Загрузка…"); //$NON-NLS-1$
            return;
        }
        progressLabel.setText(loadedProgressText(detail));

        if ("size".equals(phase)) //$NON-NLS-1$
        {
            updateTableItemCount(total);
            if (total == 0)
                updateCloneButtonState();
        }
        else if ("rows".equals(phase)) //$NON-NLS-1$
            updateCloneButtonState();
    }

    @Override
    public void onRowsReady(int first, int count)
    {
        if (splitTable == null)
            return;
        int last = Math.min(first + count, splitTable.getItemCount()) - 1;
        if (last < first)
            return;
        if (rowsReadyClearFirst < 0)
        {
            rowsReadyClearFirst = first;
            rowsReadyClearLast = last;
        }
        else
        {
            rowsReadyClearFirst = Math.min(rowsReadyClearFirst, first);
            rowsReadyClearLast = Math.max(rowsReadyClearLast, last);
        }
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return;
        if (rowsReadyDebounce != null)
            data.getDisplay().timerExec(-1, rowsReadyDebounce);
        rowsReadyDebounce = () -> {
            rowsReadyDebounce = null;
            int clearFirst = rowsReadyClearFirst;
            int clearLast = rowsReadyClearLast;
            rowsReadyClearFirst = -1;
            rowsReadyClearLast = -1;
            if (splitTable == null || clearFirst < 0 || clearLast < clearFirst)
                return;
            splitTable.clear(clearFirst, clearLast);
        };
        data.getDisplay().timerExec(50, rowsReadyDebounce);
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
        applyStoredPresentation(pathKey);
        if (scheduler != null)
            scheduler.resetLoadJobForSchemaChange();
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
        if (model.totalSize >= 0)
            updateTableItemCount(model.totalSize);
        if (scheduler != null)
        {
            Table data = dataTable();
            if (data != null && !data.isDisposed())
            {
                data.getDisplay().asyncExec(() -> {
                    if (shell == null || shell.isDisposed() || scheduler == null)
                        return;
                    scheduler.captureColumnViewport();
                    scheduleViewportLoad();
                });
            }
            else
            {
                scheduler.captureColumnViewport();
                scheduleViewportLoad();
            }
        }
        else
            scheduleViewportLoad();
        updateColumnSettingsButton();
        refreshPresentationCombo();
        updateCloneButtonState();
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
        cloneItem = clone;
        clone.setText("Клонировать"); //$NON-NLS-1$
        Image cloneImage = PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_TOOL_COPY);
        if (cloneImage != null)
            clone.setImage(cloneImage);
        clone.setEnabled(false);
        clone.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                ComfortCollectionOpener.openClone(ComfortCollectionWindow.this);
            }
        });
        updateCloneButtonState();

        ToolItem inspect = new ToolItem(bar, SWT.PUSH);
        inspect.setText(ComfortSubmenuHelper.toolbarItemTextWithKeyBinding(
            CollectionRowContextSupport.inspectMenuLabel(),
            ComfortCollectionInspectHandler.COMMAND_ID,
            ComfortCollectionInspectHandler.BINDING_CONTEXT_ID));
        inspectImage = BslInspectSupport.loadInspectCommandImage();
        if (inspectImage != null)
            inspect.setImage(inspectImage);
        inspect.setToolTipText("Открыть выбранный элемент в инспекторе" + Global.pluginSignForTooltip()); //$NON-NLS-1$
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
        skeleton.setToolTipText("Открыть тестовую таблицу 1000×100 без отладки"); //$NON-NLS-1$
        skeleton.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                ComfortCollectionSkeletonWindow.open();
            }
        });
    }

    private static void installReadOnlyTextCopySupport(Text text)
    {
        if (text == null || text.isDisposed())
            return;

        Menu menu = new Menu(text);
        MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
        copyItem.setText("Копировать"); //$NON-NLS-1$
        copyItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                copyReadOnlyTextSelection(text);
            }
        });
        text.setMenu(menu);

        text.addListener(SWT.KeyDown, e ->
        {
            if (!(e instanceof Event evt))
                return;
            boolean ctrl = (evt.stateMask & SWT.CTRL) != 0;
            if (!ctrl)
                return;
            if (evt.keyCode == 'a' || evt.keyCode == 'A')
            {
                text.selectAll();
                evt.doit = false;
            }
            else if (evt.keyCode == 'c' || evt.keyCode == 'C')
            {
                copyReadOnlyTextSelection(text);
                evt.doit = false;
            }
        });

        text.addListener(SWT.MouseDown, e ->
        {
            if (e.button == 1 && e.count == 2)
                text.selectAll();
        });
    }

    private static void copyReadOnlyTextSelection(Text text)
    {
        if (text == null || text.isDisposed())
            return;
        String value = text.getSelectionText();
        if (value == null || value.isEmpty())
            value = text.getText();
        if (value == null || value.isEmpty())
            return;
        Display display = text.getDisplay();
        if (display == null || display.isDisposed())
            return;
        Clipboard clipboard = new Clipboard(display);
        clipboard.setContents(
            new Object[] { value },
            new Transfer[] { TextTransfer.getInstance() });
        clipboard.dispose();
    }

    private void createFilterRow(Composite parent)
    {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(6, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        layout.marginTop = 3;
        layout.marginBottom = 3;
        layout.verticalSpacing = 0;
        layout.horizontalSpacing = 4;
        row.setLayout(layout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        filterField = CollectionFilterHistory.createSearchBox(row, this::applyFilterNow);

        filterByPresentationCheckbox = new Button(row, SWT.CHECK);
        filterByPresentationCheckbox.setText("По представлению"); //$NON-NLS-1$
        filterByPresentationCheckbox.setToolTipText("Фильтровать только по представлению"); //$NON-NLS-1$
        filterByPresentationCheckbox.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                onFilterByPresentationChanged();
            }
        });
        updateFilterByPresentationCheckbox();

        Label presentationLabel = new Label(row, SWT.NONE);
        presentationLabel.setText("Представление:"); //$NON-NLS-1$

        presentationField = new Combo(row, SWT.DROP_DOWN | SWT.READ_ONLY);
        GridData presentationGd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        presentationGd.widthHint = 160;
        presentationGd.minimumWidth = 100;
        presentationField.setLayoutData(presentationGd);
        presentationField.setToolTipText("Колонка в фиксированной части таблицы справа от «Индекс»"); //$NON-NLS-1$
        presentationField.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                onPresentationSelected();
            }
        });

        Label pathLabel = new Label(row, SWT.NONE);
        pathLabel.setText("Путь:"); //$NON-NLS-1$

        collectionPathField = new Text(row, SWT.READ_ONLY | SWT.BORDER | SWT.SINGLE);
        GridData pathGd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        pathGd.widthHint = 240;
        pathGd.minimumWidth = 120;
        collectionPathField.setLayoutData(pathGd);
        collectionPathField.setText(pathKeyFromPath());
        collectionPathField.setToolTipText("Путь к коллекции; выделите текст и Ctrl+C"); //$NON-NLS-1$
        installReadOnlyTextCopySupport(collectionPathField);
    }

    private void clearFilter()
    {
        if (filterField == null || filterField.isDisposed())
            return;
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
            columnSettingsItem.setText("⚙ обрезано " + hidden + " колонок"); //$NON-NLS-1$ //$NON-NLS-2$
        columnSettingsItem.setToolTipText("Настройка колонок"); //$NON-NLS-1$
    }

    private void updateCloneButtonState()
    {
        if (cloneItem == null || cloneItem.isDisposed())
            return;
        boolean ready = isCloneReady();
        cloneItem.setEnabled(ready);
        int autoLimit = CollectionLoadScheduler.AUTO_LOAD_ROW_LIMIT;
        if (ready)
            cloneItem.setToolTipText("Открыть копию окна коллекции" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        else
            cloneItem.setToolTipText(
                "Дождитесь завершения автозагрузки (до " + autoLimit + " строк)" + Global.pluginSignForTooltip()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean isCloneReady()
    {
        if (model == null || model.columns.modelColumnCount() <= 1)
            return false;
        if (scheduler == null)
            return false;
        return scheduler.isAutoPrefetchComplete();
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
        progressLabel.setText(loadedProgressText(percentProgressDetail(0, 0)));
    }

    private void hookShellEvents()
    {
        shell.addListener(SWT.Show, e -> scheduleViewportLoadDebounced());
        shell.addListener(SWT.Deiconify, e -> scheduleViewportLoadDebounced());
    }

    private void installKeyContext()
    {
        shell.addListener(SWT.Activate, e -> activateKeyContext());
        shell.addListener(SWT.Deactivate, e -> deactivateKeyContext());
        activateKeyContext();
    }

    /**
     * Перехват F2 и Esc до workbench: F2 «Показать коллекцию» не должен срабатывать
     * в standalone-shell окна «Коллекция»; Esc закрывает окно.
     */
    private void installCollectionKeyFilter()
    {
        if (collectionKeyFilter != null || shell == null || shell.isDisposed())
            return;
        Display display = shell.getDisplay();
        collectionKeyFilter = event -> {
            if (!(event instanceof Event e) || e.type != SWT.KeyDown)
                return;
            if (shell.isDisposed() || display.getActiveShell() != shell)
                return;
            int mods = e.stateMask & (SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | SWT.MOD4);
            if (e.keyCode == SWT.F2 && mods == 0)
            {
                runInspectOnSelection();
                e.doit = false;
                return;
            }
            if (e.keyCode == SWT.ESC && mods == 0)
            {
                if (tryClearFilterOnEsc())
                {
                    e.doit = false;
                    return;
                }
                closeAndDispose();
                e.doit = false;
            }
        };
        display.addFilter(SWT.KeyDown, collectionKeyFilter);
    }

    /** Esc в непустом поле фильтра — сначала очистка, повторный Esc закроет окно. */
    private boolean tryClearFilterOnEsc()
    {
        if (filterField == null || filterField.isDisposed() || !filterField.isFocusControl())
            return false;
        String text = filterField.getText();
        if (text == null || text.isEmpty())
            return false;
        clearFilter();
        return true;
    }

    private void removeCollectionKeyFilter()
    {
        if (collectionKeyFilter == null)
            return;
        Display display = shell != null && !shell.isDisposed() ? shell.getDisplay() : null;
        if (display == null)
            display = Display.getCurrent();
        if (display != null && !display.isDisposed())
            display.removeFilter(SWT.KeyDown, collectionKeyFilter);
        collectionKeyFilter = null;
    }

    private void activateKeyContext()
    {
        if (keyContextActivation != null || shell == null || shell.isDisposed())
            return;
        IContextService contextService = resolveContextService();
        if (contextService == null)
            return;
        keyContextActivation = contextService.activateContext(ComfortCollectionInspectHandler.BINDING_CONTEXT_ID);
    }

    private void deactivateKeyContext()
    {
        if (keyContextActivation == null)
            return;
        IContextService contextService = resolveContextService();
        if (contextService != null)
            contextService.deactivateContext(keyContextActivation);
        keyContextActivation = null;
    }

    private static IContextService resolveContextService()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
            {
                IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows != null && windows.length > 0)
                    window = windows[0];
            }
            if (window != null)
                return window.getService(IContextService.class);
        }
        catch (Exception ignored)
        {
            // команда остаётся без контекста
        }
        return null;
    }

    void inspectSelection()
    {
        runInspectOnSelection();
    }

    boolean canInspectSelection()
    {
        return selectedVariable() != null;
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
            data.addControlListener(new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
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
                if (isFilterByPresentationSelected() && e.index != 1)
                    return;
                SmartMatcher matcher = activeFilterMatcher();
                if (matcher == null)
                    return;
                CollectionFilterEraseSupport.handleEraseItem(
                    index,
                    item,
                    e,
                    matcher,
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
                if (isFilterByPresentationSelected())
                    return;
                CollectionFilterEraseSupport.handleEraseItem(
                    data,
                    item,
                    e,
                    activeFilterMatcher(),
                    filterSkipItem(item),
                    this,
                    firstVisibleColumnIndex(data));
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
        Table index = indexTable();
        int fixedCols = index != null ? index.getColumnCount() : 1;
        for (int col = 0; col < fixedCols; col++)
        {
            String text = model.getCellDisplayText(logical, col);
            item.setText(col, text != null ? text : ""); //$NON-NLS-1$
        }
        requestRowLoadIfNeeded(logical, index);
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
            int visibleCol = model.columns.visibleIndexForDataColumn(dataCol);
            String text = model.getCellDisplayText(logical, visibleCol);
            item.setText(dataCol, text != null ? text : ""); //$NON-NLS-1$
        }
        requestRowLoadIfNeeded(logical, data);
    }

    private void requestRowLoadIfNeeded(int logical, Table table)
    {
        if (scheduler == null || model == null || table == null || table.isDisposed())
            return;
        if (scheduler.isLoadActive())
            return;
        int colFrom = 0;
        int colTo = Math.max(0, model.columns.columnCount() - 1);
        if (table == dataTable())
        {
            int[] cols = CollectionViewportTracker.visibleModelColumnRange(
                table, model.columns.columnCount(), model.columns.fixedColumnCount());
            colFrom = cols[0];
            colTo = cols[1];
        }
        else if (table == indexTable())
            colTo = Math.max(0, model.columns.fixedColumnCount() - 1);
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
            return;
        }
        if (event.keyCode == SWT.F2 && (event.stateMask & (SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | SWT.MOD4)) == 0)
        {
            runInspectOnSelection();
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
        inspect.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
            CollectionRowContextSupport.inspectMenuLabel(),
            ComfortCollectionInspectHandler.COMMAND_ID,
            ComfortCollectionInspectHandler.BINDING_CONTEXT_ID));
        inspect.setToolTipText("Открыть элемент коллекции в инспекторе" + Global.pluginSignForTooltip()); //$NON-NLS-1$
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
        int logicalRow = selectedLogicalRow();
        CollectionTableInteraction interaction = activeInteraction();
        String header = columnHeader(interaction != null ? interaction.modelVisibleColumn() : 0);
        Point anchor = inspectAnchor(data);
        CollectionRowContextSupport.runInspect(
            variable, frame, header, data, anchor, shell, model, logicalRow);
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

    private int selectedLogicalRow()
    {
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return -1;
        TableItem[] sel = data.getSelection();
        if (sel == null || sel.length == 0)
        {
            Table index = indexTable();
            if (index != null && !index.isDisposed())
                sel = index.getSelection();
        }
        if (sel == null || sel.length == 0)
            return -1;
        int displayIndex = CollectionTableItemKeys.displayIndex(sel[0], sel[0].getParent());
        if (displayIndex < 0)
            displayIndex = data.indexOf(sel[0]);
        return displayIndexToLogical(displayIndex);
    }

    private String columnHeader(int visibleCol)
    {
        CollectionColumnModel.Column col = model.columns.columnAt(visibleCol);
        return col != null ? col.header : ""; //$NON-NLS-1$
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
        rowFilter.setPresentationOnly(isFilterByPresentationSelected());
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
        if (splitTable == null || model == null)
            return 0;
        if (model.totalSize >= 0)
            return model.totalSize;
        return splitTable.getItemCount();
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
        dialog.setColumnActivateListener(this::activateColumnFromDialog);
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

    private void activateColumnFromDialog(int modelIndex, boolean[] visibility, int[] order)
    {
        if (model == null || modelIndex < 0)
            return;
        if (model.columns.visibleIndexOfModelColumn(modelIndex) < 0)
            applyColumnDialogResult(visibility, order);
        scheduleActivateColumnByModelIndex(modelIndex);
    }

    private void scheduleActivateColumnByModelIndex(int modelIndex)
    {
        if (shell == null || shell.isDisposed())
            return;
        shell.getDisplay().asyncExec(() -> {
            if (!isDisposed())
                activateColumnByModelIndex(modelIndex);
        });
    }

    private void activateColumnByModelIndex(int modelIndex)
    {
        if (model == null || splitTable == null || modelIndex < 0)
            return;
        int visibleCol = model.columns.visibleIndexOfModelColumn(modelIndex);
        if (visibleCol < 0)
            return;
        scrollToVisibleColumn(visibleCol);
        int displayIndex = selectedDisplayIndex();
        if (displayIndex < 0)
            displayIndex = Math.max(0, splitTable.getTopIndex());
        selectDisplayRow(displayIndex, visibleCol);
        scrollToVisibleColumn(visibleCol);
        focusTableForVisibleColumn(visibleCol);
    }

    private int selectedDisplayIndex()
    {
        CollectionTableInteraction interaction = activeInteraction();
        if (interaction == null)
            return -1;
        TableItem item = interaction.selectedItem();
        if (item == null || item.isDisposed())
            return -1;
        Table table = item.getParent();
        if (table == null || table.isDisposed())
            return -1;
        return CollectionTableItemKeys.displayIndex(item, table);
    }

    private void scrollToVisibleColumn(int visibleCol)
    {
        if (splitTable == null || model == null)
            return;
        int fixed = model.columns.fixedColumnCount();
        if (visibleCol < fixed)
            return;
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return;
        CollectionViewportTracker.scrollColumnIntoView(data, visibleCol - fixed);
    }

    private void focusTableForVisibleColumn(int visibleCol)
    {
        if (splitTable == null || model == null)
            return;
        int fixed = model.columns.fixedColumnCount();
        Table table = visibleCol < fixed ? indexTable() : dataTable();
        if (table != null && !table.isDisposed())
            table.setFocus();
    }

    private int[] columnOrderForDialog(int modelCount)
    {
        if (modelCount > CollectionColumnVisibilityStore.WIDE_SCHEMA_THRESHOLD)
            return CollectionColumnVisibilityStore.orderWithPreferred(model.columns);
        return CollectionColumnVisibilityStore.orderFor(model.pathKey(), modelCount);
    }

    private void applyColumnDialogResult(boolean[] visibility, int[] order)
    {
        String presentation = currentPresentationHeader();
        if (visibility.length > 0)
            visibility[0] = true;
        if (presentation != null)
        {
            int modelIdx = model.columns.findPresentationColumnByHeader(presentation);
            if (modelIdx > 0 && modelIdx < visibility.length)
                visibility[modelIdx] = true;
        }
        model.columns.applyVisibility(visibility, order);
        model.columns.applyPresentationHeader(presentation);
        CollectionColumnVisibilityStore.save(model.pathKey(), visibility, order, presentation);
        model.clearCellCache();
        syncSplitTableColumns();
        if (splitTable != null)
            splitTable.clearAll();
        if (scheduler != null)
            scheduler.captureColumnViewport();
        scheduleViewportLoad();
        updateColumnSettingsButton();
        refreshPresentationCombo();
        applyFilterIfNonEmpty();
    }

    private void applyStoredPresentation(String pathKey)
    {
        if (model == null)
            return;
        String presentation = CollectionColumnVisibilityStore.presentationFor(pathKey, model.columns);
        if (presentation == null || model.columns.findPresentationColumnByHeader(presentation) < 0)
        {
            model.columns.clearPresentation();
            return;
        }
        int modelCount = model.columns.modelColumnCount();
        boolean[] vis = CollectionColumnVisibilityStore.visibilityFor(pathKey, modelCount, model.columns);
        int[] order = modelCount > CollectionColumnVisibilityStore.WIDE_SCHEMA_THRESHOLD
            ? CollectionColumnVisibilityStore.orderWithPreferred(model.columns)
            : CollectionColumnVisibilityStore.orderFor(pathKey, modelCount);
        int modelIdx = model.columns.findPresentationColumnByHeader(presentation);
        if (modelIdx > 0 && modelIdx < vis.length && !vis[modelIdx])
        {
            vis[modelIdx] = true;
            model.columns.applyVisibility(vis, order);
        }
        model.columns.applyPresentationHeader(presentation);
    }

    private void onPresentationSelected()
    {
        if (applyingPresentation || presentationField == null || presentationField.isDisposed() || model == null)
            return;
        int idx = presentationField.getSelectionIndex();
        if (idx < 0)
            return;
        applyPresentationChange(presentationField.getItem(idx));
    }

    private void applyPresentationChange(String header)
    {
        if (model == null || header == null || header.isBlank())
            return;
        String pathKey = model.pathKey();
        int modelIdx = model.columns.findPresentationColumnByHeader(header);
        int[] oldVisible = model.columns.copyVisibleToModel();
        boolean needsVisibilityUpdate = modelIdx > 0 && model.columns.visibleIndexOfModelColumn(modelIdx) < 0;

        if (needsVisibilityUpdate)
        {
            boolean[] vis = CollectionColumnVisibilityStore.visibilityFor(
                pathKey, model.columns.modelColumnCount(), model.columns);
            int[] order = columnOrderForDialog(model.columns.modelColumnCount());
            if (modelIdx < vis.length)
                vis[modelIdx] = true;
            model.columns.applyVisibility(vis, order);
            model.columns.applyPresentationHeader(header);
            CollectionColumnVisibilityStore.save(pathKey, vis, order, header);
        }
        else
        {
            model.columns.applyPresentationHeader(header);
            CollectionColumnVisibilityStore.savePresentation(pathKey, header);
        }

        model.remapCellCacheForVisibleLayout(oldVisible);
        syncSplitTableColumns();
        refreshVisibleTableRows();
        updateFilterByPresentationCheckbox();
        if (needsVisibilityUpdate)
            scheduleViewportLoadDebounced();
        else
            applyFilterIfNonEmpty();
    }

    private void applyFilterIfNonEmpty()
    {
        if (filterField == null || filterField.isDisposed())
            return;
        String text = filterField.getText();
        if (text != null && !text.isBlank())
            applyFilterNow();
    }

    private boolean isFilterByPresentationSelected()
    {
        if (filterByPresentationCheckbox == null || filterByPresentationCheckbox.isDisposed())
            return false;
        if (!filterByPresentationCheckbox.getEnabled())
            return false;
        return filterByPresentationCheckbox.getSelection();
    }

    private void onFilterByPresentationChanged()
    {
        if (updatingFilterByPresentationCheckbox || model == null)
            return;
        if (filterByPresentationCheckbox == null || filterByPresentationCheckbox.isDisposed())
            return;
        CollectionColumnVisibilityStore.saveFilterByPresentation(
            model.pathKey(), filterByPresentationCheckbox.getSelection());
        applyFilterIfNonEmpty();
    }

    private void updateFilterByPresentationCheckbox()
    {
        if (filterByPresentationCheckbox == null || filterByPresentationCheckbox.isDisposed() || model == null)
            return;
        boolean hasPresentation = model.columns.presentationModelIndex() > 0;
        String pathKey = model.pathKey();
        updatingFilterByPresentationCheckbox = true;
        try
        {
            filterByPresentationCheckbox.setEnabled(hasPresentation);
            if (!hasPresentation)
            {
                if (filterByPresentationCheckbox.getSelection())
                {
                    filterByPresentationCheckbox.setSelection(false);
                    CollectionColumnVisibilityStore.saveFilterByPresentation(pathKey, false);
                    applyFilterIfNonEmpty();
                }
            }
            else
                filterByPresentationCheckbox.setSelection(
                    CollectionColumnVisibilityStore.filterByPresentationFor(pathKey));
        }
        finally
        {
            updatingFilterByPresentationCheckbox = false;
        }
    }

    private void applyCloneFilterByPresentationCheckbox()
    {
        if (filterByPresentationCheckbox == null || filterByPresentationCheckbox.isDisposed()
            || model == null || cloneSnapshot == null)
            return;
        boolean hasPresentation = model.columns.presentationModelIndex() > 0;
        updatingFilterByPresentationCheckbox = true;
        try
        {
            filterByPresentationCheckbox.setEnabled(hasPresentation);
            filterByPresentationCheckbox.setSelection(
                hasPresentation && cloneSnapshot.filterByPresentation());
        }
        finally
        {
            updatingFilterByPresentationCheckbox = false;
        }
    }

    private void refreshVisibleTableRows()
    {
        if (splitTable == null)
            return;
        int count = splitTable.getItemCount();
        if (count <= 0)
            return;
        int top = splitTable.getTopIndex();
        int visibleRows = 25;
        Table data = dataTable();
        if (data != null && !data.isDisposed())
        {
            int itemHeight = data.getItemHeight();
            int clientHeight = data.getClientArea().height;
            if (itemHeight > 0 && clientHeight > 0)
                visibleRows = Math.max(1, clientHeight / itemHeight + 2);
        }
        int bottom = Math.min(count - 1, top + visibleRows);
        if (bottom >= top)
            splitTable.clear(top, bottom);
    }

    private void refreshPresentationCombo()
    {
        if (presentationField == null || presentationField.isDisposed() || model == null)
            return;
        applyingPresentation = true;
        try
        {
            String selected = CollectionColumnVisibilityStore.presentationFor(model.pathKey(), model.columns);
            java.util.List<String> headers = model.columns.presentationColumnHeaders();
            presentationField.removeAll();
            for (String header : headers)
                presentationField.add(header);
            int sel = indexOfHeaderIgnoreCase(headers, selected);
            if (sel >= 0)
                presentationField.select(sel);
            else
                presentationField.select(-1);
            presentationField.setEnabled(!headers.isEmpty());
        }
        finally
        {
            applyingPresentation = false;
            updateFilterByPresentationCheckbox();
        }
    }

    private String currentPresentationHeader()
    {
        if (presentationField != null && !presentationField.isDisposed())
        {
            int sel = presentationField.getSelectionIndex();
            if (sel >= 0)
                return presentationField.getItem(sel);
        }
        if (model != null)
            return CollectionColumnVisibilityStore.presentationFor(model.pathKey(), model.columns);
        return null;
    }

    private static int indexOfHeaderIgnoreCase(java.util.List<String> headers, String target)
    {
        if (headers == null || target == null)
            return -1;
        for (int i = 0; i < headers.size(); i++)
        {
            String header = headers.get(i);
            if (header != null && header.equalsIgnoreCase(target))
                return i;
        }
        return -1;
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
