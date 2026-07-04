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


import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.widgets.Control;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.swt.widgets.Monitor;

/**
 * Окно «Коллекция &lt;Тип&gt;» — virtual table без блокировки UI-потока.
 */
public final class DebugCollectionWindow implements DebugCollectionLoadScheduler.ProgressListener, DebugCollectionTableHost
{
    private final IBslIndexedValue indexedValue;
    private final IBslStackFrame frame;
    private final BslValuePath path;
    private final int cloneIndex;
    private final String registryKey;
    private final DebugCollectionCloneSnapshot cloneSnapshot;

    private Shell shell;
    private DebugCollectionSplitTable splitTable;
    private FilterInputBox filterInput;
    private Button filterByPresentationCheckbox;
    private boolean updatingFilterByPresentationCheckbox;
    private Combo presentationField;
    private Text collectionPathField;
    private boolean applyingPresentation;
    private DebugCollectionTableModel model;
    private DebugCollectionLoadScheduler scheduler;
    private DebugCollectionRowFilter rowFilter;
    private DebugCollectionFindSession findSession;
    private DebugCollectionTableInteraction indexInteraction;
    private DebugCollectionTableInteraction dataInteraction;
    private DebugCollectionShellPin shellPin;

    private Image inspectImage;
    private ToolItem columnSettingsItem;
    private ToolItem cloneItem;
    private Runnable viewportDebounce;
    private Runnable rowsReadyDebounce;
    private int lastViewportLogicalFirst = -1;
    private int rowsReadyClearFirst = -1;
    private int rowsReadyClearLast = -1;
    private Listener filterEraseListenerIndex;
    private Listener filterEraseListenerData;
    private IContextActivation keyContextActivation;
    private Listener collectionKeyFilter;
    private long lastInspectEnterMs;

    public DebugCollectionWindow(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        int cloneIndex,
        String registryKey)
    {
        this(indexedValue, frame, path, cloneIndex, registryKey, null);
    }

    DebugCollectionWindow(
        IBslIndexedValue indexedValue,
        IBslStackFrame frame,
        BslValuePath path,
        int cloneIndex,
        String registryKey,
        DebugCollectionCloneSnapshot cloneSnapshot)
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
            model = new DebugCollectionTableModel(
                indexedValue, frame, path, DebugCollectionColumnModel.minimal(), typeTitle);

        shell = new Shell(display, SWT.SHELL_TRIM | SWT.MAX | SWT.RESIZE);
        GridLayout shellLayout = new GridLayout(1, false);
        shellLayout.marginHeight = 0;
        shellLayout.marginWidth = 0;
        shellLayout.verticalSpacing = 0;
        shell.setLayout(shellLayout);
        shell.setMinimumSize(640, 400);

        createToolbar(shell);
        createFilterRow(shell);
        createTable(shell);

        rowFilter = cloneSnapshot != null ? cloneSnapshot.toRowFilter() : new DebugCollectionRowFilter(""); //$NON-NLS-1$
        findSession = new DebugCollectionFindSession(this);
        indexInteraction = new DebugCollectionTableInteraction(splitTable.indexTable(), this);
        indexInteraction.install();
        dataInteraction = new DebugCollectionTableInteraction(splitTable.dataTable(), this);
        dataInteraction.install();
        scheduler = new DebugCollectionLoadScheduler(model, display, this, this::onContextColumnsReady);
        scheduler.bindTable(splitTable.dataTable());
        scheduler.bindLogicalRowMapper(this::displayIndexToLogical);
        scheduler.bindShell(shell);
        hookTableEvents();
        hookContextMenu();
        hookFilterHighlight();
        installFilterNavigation();
        hookShellEvents();
        installKeyContext();
        installCollectionKeyFilter();

        if (cloneSnapshot != null && cloneSnapshot.schemaResolved())
            prepareCloneWindowBeforeOpen();
        else
            updateTableItemCount(0);

        updateWindowTitle();
        shell.pack();
        DebugCollectionWindowGeometryStore.applyToShell(shell);
        shell.open();
        if (filterInput != null && !filterInput.isDisposed())
            filterInput.scheduleFocusWhenReady();
        shell.addDisposeListener(e -> disposeWindow());
        if (cloneSnapshot != null && cloneSnapshot.schemaResolved())
            startCloneLoadAfterOpen();
        else
            scheduler.scheduleInitialLoad();
        shellPin = new DebugCollectionShellPin(shell);
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

    DebugCollectionTableModel tableModelForClone()
    {
        return model;
    }

    DebugCollectionRowFilter rowFilterForClone()
    {
        return rowFilter;
    }

    boolean filterByPresentationForClone()
    {
        return isFilterByPresentationSelected();
    }

    String filterTextForClone()
    {
        if (filterInput == null || filterInput.isDisposed())
            return ""; //$NON-NLS-1$
        String text = filterInput.getText();
        return text != null ? text : ""; //$NON-NLS-1$
    }

    int topIndexForClone()
    {
        return splitTable != null ? splitTable.getTopIndex() : 0;
    }

    DebugCollectionTableInteraction activeInteractionForClone()
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
        if (filterInput != null && !filterInput.isDisposed())
            filterInput.setText(cloneSnapshot.filterText());
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
            boolean[] vis = DebugCollectionColumnVisibilityStore.visibilityFor(
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

    private DebugCollectionTableInteraction activeInteraction()
    {
        return interactionForTable(activeTable());
    }

    private Table activeTable()
    {
        Table index = indexTable();
        if (index != null && !index.isDisposed() && index.isFocusControl())
            return index;
        Table data = dataTable();
        if (data != null && !data.isDisposed() && data.isFocusControl())
            return data;
        return data != null ? data : index;
    }

    private DebugCollectionTableInteraction interactionForTable(Table table)
    {
        if (table != null && table == indexTable())
            return indexInteraction;
        if (table != null && table == dataTable())
            return dataInteraction;
        return dataInteraction != null ? dataInteraction : indexInteraction;
    }

    private static Table tableFromEventWidget(Object widget)
    {
        return widget instanceof Table table && !table.isDisposed() ? table : null;
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

    @Override
    public String getCellHoverToolTip(int logicalRow, int visibleCol)
    {
        if (model == null)
            return null;
        DebugCollectionColumnModel.Column col = model.columns.columnAt(visibleCol);
        if (col == null || col.kind == DebugCollectionColumnModel.Kind.INDEX
            || col.kind == DebugCollectionColumnModel.Kind.TYPE)
            return null;
        String baseText = model.getCellBaseText(logicalRow, visibleCol);
        if (baseText == null || baseText.isEmpty())
            return null;
        try
        {
            IBslValue value = model.resolveCellValue(logicalRow, visibleCol);
            return DebugStringValueFormat.tooltipPreviewForTruncated(value, baseText);
        }
        catch (DebugException e)
        {
            return null;
        }
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
            DebugCollectionWindowGeometryStore.saveFromShell(shell);
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
        DebugCollectionWindowRegistry.unregister(registryKey);
    }

    Table getTable()
    {
        return dataTable();
    }

    DebugCollectionTableModel getModel()
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

    @Override
    public void onProgress(int loaded, int total, String phase)
    {
        if ("size".equals(phase)) //$NON-NLS-1$
        {
            updateTableItemCount(total);
            updateWindowTitle();
            if (total == 0)
                updateCloneButtonState();
        }
        else if ("rows".equals(phase)) //$NON-NLS-1$
            updateCloneButtonState();
    }

    @Override
    public void onRepaintLogicalRow(int logicalRow)
    {
        if (splitTable == null || logicalRow < 0)
            return;
        int display = logicalToDisplayIndex(logicalRow);
        if (display < 0 || display >= splitTable.getItemCount())
            return;
        splitTable.clear(display, display);
    }

    private int logicalToDisplayIndex(int logicalRow)
    {
        int total = effectiveTotalSize();
        if (rowFilter != null && rowFilter.isActive())
            return rowFilter.displayIndexForLogicalRow(logicalRow, total);
        return logicalRow;
    }

    @Override
    public void onRowsReady(int displayFirst, int count)
    {
        if (splitTable == null)
            return;
        int itemCount = splitTable.getItemCount();
        if (itemCount <= 0 || count <= 0)
            return;
        int displayLast = Math.min(itemCount - 1, displayFirst + count - 1);
        if (displayLast < displayFirst)
            return;
        if (rowsReadyClearFirst < 0)
        {
            rowsReadyClearFirst = displayFirst;
            rowsReadyClearLast = displayLast;
        }
        else
        {
            rowsReadyClearFirst = Math.min(rowsReadyClearFirst, displayFirst);
            rowsReadyClearLast = Math.max(rowsReadyClearLast, displayLast);
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
        DebugCollectionColumnModel rebuilt = DebugCollectionColumnModel.fromContextVariables(context);
        String pathKey = model.pathKey();
        int count = rebuilt.allColumns().size();
        model.columns.replaceColumns(new java.util.ArrayList<>(rebuilt.allColumns()));
        boolean wideUnion = count > DebugCollectionColumnVisibilityStore.WIDE_SCHEMA_THRESHOLD;
        // #region agent log
        DebugCollectionAgentLog.log("H-schema", "Window.onContextColumnsReady", "schema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"cols\":" + count + ",\"wideUnion\":" + wideUnion + "}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // #endregion
        model.columns.applyVisibility(
            DebugCollectionColumnVisibilityStore.visibilityFor(pathKey, count, model.columns),
            wideUnion
                ? DebugCollectionColumnVisibilityStore.orderWithPreferred(model.columns)
                : DebugCollectionColumnVisibilityStore.orderFor(pathKey, count));
        applyStoredPresentation(pathKey);
        refreshPresentationCombo();
        if (scheduler != null)
            scheduler.resetLoadJobForSchemaChange();
        else
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
                    index.redraw();
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
                    scheduleViewportCapture();
                });
            }
            else
                scheduleViewportCapture();
        }
        else
            scheduleViewportCapture();
        updateColumnSettingsButton();
        refreshPresentationCombo();
        updateCloneButtonState();
        DebugCollectionDebug.step("columns", //$NON-NLS-1$
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

    private String buildTitleSuffix(String typeTitle, int visibleCount, int totalCount)
    {
        StringBuilder suffix = new StringBuilder();
        if (totalCount >= 0)
        {
            if (rowFilter != null && rowFilter.isActive())
                suffix.append('(').append(visibleCount).append('/').append(totalCount).append(')');
            else
                suffix.append('(').append(visibleCount).append(')');
        }
        if (typeTitle != null && !typeTitle.isBlank())
        {
            if (suffix.length() > 0)
                suffix.append(' ');
            suffix.append(typeTitle.trim());
        }
        if (cloneIndex > 0)
        {
            if (suffix.length() > 0)
                suffix.append(' ');
            suffix.append('(').append(cloneIndex).append(')');
        }
        return suffix.toString();
    }

    private void updateWindowTitle()
    {
        if (shell == null || shell.isDisposed())
            return;
        int totalCount = model != null && model.totalSize >= 0 ? model.totalSize : -1;
        int visibleCount = splitTable != null ? splitTable.getItemCount()
            : totalCount >= 0 ? totalCount : -1;
        shell.setText(Global.withPluginWindowTitle("Коллекция", //$NON-NLS-1$
            buildTitleSuffix(resolveTypeTitle(), visibleCount, totalCount)));
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
                DebugCollectionOpener.openClone(DebugCollectionWindow.this);
            }
        });
        updateCloneButtonState();

        ToolItem inspect = new ToolItem(bar, SWT.PUSH);
        inspect.setText(ComfortSubmenuHelper.toolbarItemTextWithKeyBinding(
            DebugCollectionRowContextSupport.inspectMenuLabel(),
            DebugCollectionInspectHandler.COMMAND_ID,
            DebugCollectionInspectHandler.BINDING_CONTEXT_ID));
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
        columnSettingsItem.setText("⚙ Колонки"); //$NON-NLS-1$
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
                DebugCollectionSkeletonWindow.open();
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

        filterInput = FilterInputBox.forCollection(row, this::applyFilterNow);

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
        collectionPathField.setToolTipText("Путь к коллекции"); //$NON-NLS-1$
        installReadOnlyTextCopySupport(collectionPathField);
    }

    private void clearFilter()
    {
        if (filterInput == null || filterInput.isDisposed())
            return;
        filterInput.setText(""); //$NON-NLS-1$
        cancelFilterScan();
    }

    private void updateColumnSettingsButton()
    {
        if (columnSettingsItem == null || columnSettingsItem.isDisposed() || model == null)
            return;
        int hidden = model.columns.hiddenColumnCount();
        if (hidden <= 0)
            columnSettingsItem.setText("⚙ Колонки"); //$NON-NLS-1$
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
        if (ready)
            cloneItem.setToolTipText("Открыть копию окна коллекции" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        else
            cloneItem.setToolTipText(
                "Дождитесь завершения автозагрузки" + Global.pluginSignForTooltip()); //$NON-NLS-1$
    }

    private boolean isCloneReady()
    {
        if (model == null || model.columns.modelColumnCount() <= 1)
            return false;
        return model.isRowsLoaded();
    }

    private void createTable(Composite parent)
    {
        splitTable = DebugCollectionSplitTable.create(parent);
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

    private void hookShellEvents()
    {
        shell.addListener(SWT.Show, e -> scheduleViewportCaptureDebounced());
        shell.addListener(SWT.Deiconify, e -> scheduleViewportCaptureDebounced());
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
        if (filterInput == null || filterInput.isDisposed() || !filterInput.isFocusControl())
            return false;
        String text = filterInput.getText();
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
        keyContextActivation = contextService.activateContext(DebugCollectionInspectHandler.BINDING_CONTEXT_ID);
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

    private void installFilterNavigation()
    {
        if (filterInput == null || filterInput.isDisposed())
            return;
        Table index = indexTable();
        if (index == null || index.isDisposed())
            return;
        Control filterKeys = filterInput.inputControl();
        if (filterKeys == null)
            filterKeys = filterInput.widget();
        FilterInputBoxListNavigation.installTableNavigation(filterKeys, index, newIdx ->
        {
            int col = indexInteraction != null ? indexInteraction.activeColumn() : 0;
            selectDisplayRow(newIdx, col);
        }, false, () ->
        {
            Table idx = indexTable();
            if (idx != null && !idx.isDisposed())
                runInspectOnSelection(idx);
            return true;
        });
    }

    private void hookTableEvents()
    {
        Table index = indexTable();
        Table data = dataTable();
        if (index != null)
        {
            index.addListener(SWT.SetData, this::onSetDataIndex);
            index.addListener(SWT.KeyDown, this::onTableKeyDown);
            index.addListener(SWT.Traverse, this::onTableTraverse);
            index.addListener(SWT.MouseDoubleClick, this::onTableDoubleClick);
            ScrollBar vertical = index.getVerticalBar();
            if (vertical != null)
                vertical.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        onVerticalScroll();
                    }
                });
        }
        if (data != null)
        {
            data.addListener(SWT.SetData, this::onSetDataData);
            data.addListener(SWT.KeyDown, this::onTableKeyDown);
            data.addListener(SWT.Traverse, this::onTableTraverse);
            data.addListener(SWT.MouseDoubleClick, this::onTableDoubleClick);
            ScrollBar vertical = data.getVerticalBar();
            if (vertical != null)
                vertical.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        onVerticalScroll();
                    }
                });
            ScrollBar horizontal = data.getHorizontalBar();
            if (horizontal != null)
                horizontal.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent e)
                    {
                        scheduleViewportCaptureDebounced();
                    }
                });
            data.addControlListener(new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
                {
                    scheduleViewportCaptureDebounced();
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
                DebugCollectionFilterEraseSupport.handleEraseItem(
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
                DebugCollectionFilterEraseSupport.handleEraseItem(
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

        DebugCollectionTableItemKeys.bindRow(item, displayIndex, logical);
        Table index = indexTable();
        int fixedCols = index != null ? index.getColumnCount() : 1;
        for (int col = 0; col < fixedCols; col++)
        {
            String text = model.getCellDisplayText(logical, col);
            item.setText(col, text != null ? text : ""); //$NON-NLS-1$
        }
    }

    private void onSetDataData(Event event)
    {
        TableItem item = (TableItem) event.item;
        int displayIndex = event.index;
        int logical = displayIndexToLogical(displayIndex);
        if (logical < 0)
            return;

        DebugCollectionTableItemKeys.bindRow(item, displayIndex, logical);

        Table data = dataTable();
        int dataCols = data != null ? data.getColumnCount() : 0;
        int maxDataLen = 0;
        for (int dataCol = 0; dataCol < dataCols; dataCol++)
        {
            int visibleCol = model.columns.visibleIndexForDataColumn(dataCol);
            String text = model.getCellDisplayText(logical, visibleCol);
            int len = text != null ? text.length() : 0;
            if (len > maxDataLen)
                maxDataLen = len;
            item.setText(dataCol, text != null ? text : ""); //$NON-NLS-1$
        }
        // #region agent log
        if (logical == 0 || logical == 16 || logical == 24 || logical == 100 || logical == 500
            || logical >= 1700)
        {
            DebugCollectionAgentLog.log("H-setData", "Window.onSetDataData", "cellPaint", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"logical\":" + logical + ",\"display\":" + displayIndex //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"cols\":" + model.columns.columnCount() //$NON-NLS-1$
                    + ",\"maxDataLen\":" + maxDataLen + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // #endregion
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
            runInspectOnSelection(tableFromEventWidget(event.widget));
            event.doit = false;
            return;
        }
        if (isTableInspectEnter(event))
        {
            inspectSelectionOnEnter(tableFromEventWidget(event.widget));
            event.doit = false;
            return;
        }
        int key = event.keyCode;
        if (key == SWT.END || key == SWT.HOME || key == SWT.PAGE_DOWN || key == SWT.PAGE_UP)
        {
            Table data = dataTable();
            if (data != null && !data.isDisposed())
                data.getDisplay().asyncExec(this::scheduleViewportCapturePriority);
        }
    }

    private void onTableTraverse(Event event)
    {
        if (event.type == SWT.Traverse)
        {
            int detail = event.detail;
            if (detail == SWT.TRAVERSE_PAGE_NEXT || detail == SWT.TRAVERSE_PAGE_PREVIOUS)
            {
                Table data = dataTable();
                if (data != null && !data.isDisposed())
                    data.getDisplay().asyncExec(this::scheduleViewportCapturePriority);
            }
        }
        if (!isTableInspectEnter(event))
            return;
        inspectSelectionOnEnter(tableFromEventWidget(event.widget));
        event.doit = false;
        event.detail = SWT.TRAVERSE_NONE;
    }

    private void inspectSelectionOnEnter(Table sourceTable)
    {
        long now = System.currentTimeMillis();
        if (now - lastInspectEnterMs < 100)
            return;
        lastInspectEnterMs = now;
        runInspectOnSelection(sourceTable);
    }

    /** Enter без модификаторов — открыть инспектор, как двойной клик. */
    private static boolean isTableInspectEnter(Event event)
    {
        if ((event.stateMask & (SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | SWT.MOD4)) != 0)
            return false;
        if (event.type == SWT.Traverse)
            return event.detail == SWT.TRAVERSE_RETURN;
        return event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR;
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
        runInspectOnSelection(table);
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
        watch.setText(DebugCollectionRowContextSupport.watchMenuLabel());
        watch.setToolTipText("Добавить элемент в панель «Выражения»"); //$NON-NLS-1$
        watch.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                Table sourceTable = activeTable();
                IBslVariable variable = selectedVariable(sourceTable);
                if (variable != null)
                {
                    int logicalRow = selectedLogicalRow(sourceTable);
                    DebugCollectionTableInteraction interaction = interactionForTable(sourceTable);
                    int visibleCol = interaction != null ? interaction.modelVisibleColumn() : 0;
                    DebugCollectionRowContextSupport.showInExpressions(
                        variable, frame, model, logicalRow, visibleCol);
                }
            }
        });

        MenuItem inspect = new MenuItem(menu, SWT.PUSH);
        inspect.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
            DebugCollectionRowContextSupport.inspectMenuLabel(),
            DebugCollectionInspectHandler.COMMAND_ID,
            DebugCollectionInspectHandler.BINDING_CONTEXT_ID));
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
        runInspectOnSelection(null);
    }

    private void runInspectOnSelection(Table sourceTable)
    {
        Table inspectTable = sourceTable != null && !sourceTable.isDisposed()
            ? sourceTable
            : activeTable();
        IBslVariable variable = selectedVariable(inspectTable);
        if (variable == null || shell == null || shell.isDisposed())
            return;
        Table anchorTable = inspectTable != null && !inspectTable.isDisposed() ? inspectTable : dataTable();
        if (anchorTable == null || anchorTable.isDisposed())
            return;
        int logicalRow = selectedLogicalRow(inspectTable);
        DebugCollectionTableInteraction interaction = interactionForTable(inspectTable);
        String header = columnHeader(interaction != null ? interaction.modelVisibleColumn() : 0);
        Point anchor = inspectAnchor(anchorTable);
        DebugCollectionRowContextSupport.runInspect(
            variable, frame, header, anchorTable, anchor, shell, model, logicalRow);
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
        return selectedVariable(null);
    }

    private IBslVariable selectedVariable(Table preferredTable)
    {
        TableItem[] sel = selectionOnTable(preferredTable);
        if (sel == null || sel.length == 0)
            return null;
        Table itemTable = sel[0].getParent();
        int displayIndex = DebugCollectionTableItemKeys.displayIndex(sel[0], itemTable);
        if (displayIndex < 0 && itemTable != null && !itemTable.isDisposed())
            displayIndex = itemTable.indexOf(sel[0]);
        int logical = displayIndexToLogical(displayIndex);
        if (logical < 0)
            return null;
        return model.getRowVariable(logical);
    }

    private int selectedLogicalRow()
    {
        return selectedLogicalRow(null);
    }

    private int selectedLogicalRow(Table preferredTable)
    {
        TableItem[] sel = selectionOnTable(preferredTable);
        if (sel == null || sel.length == 0)
            return -1;
        Table itemTable = sel[0].getParent();
        int displayIndex = DebugCollectionTableItemKeys.displayIndex(sel[0], itemTable);
        if (displayIndex < 0 && itemTable != null && !itemTable.isDisposed())
            displayIndex = itemTable.indexOf(sel[0]);
        return displayIndexToLogical(displayIndex);
    }

    private TableItem[] selectionOnTable(Table preferredTable)
    {
        Table primary = preferredTable;
        if (primary == null || primary.isDisposed())
            primary = activeTable();
        if (primary != null && !primary.isDisposed())
        {
            TableItem[] sel = primary.getSelection();
            if (sel != null && sel.length > 0)
                return sel;
        }
        Table fallback = primary == indexTable() ? dataTable() : indexTable();
        if (fallback != null && !fallback.isDisposed())
        {
            TableItem[] sel = fallback.getSelection();
            if (sel != null && sel.length > 0)
                return sel;
        }
        return null;
    }

    private String columnHeader(int visibleCol)
    {
        DebugCollectionColumnModel.Column col = model.columns.columnAt(visibleCol);
        return col != null ? col.header : ""; //$NON-NLS-1$
    }

    private void cancelFilterScan()
    {
        int anchorLogical = -1;
        Table data = dataTable();
        DebugCollectionTableInteraction interaction = activeInteraction();
        if (rowFilter != null && rowFilter.isActive())
            anchorLogical = DebugCollectionFilterViewportAnchor.captureLogicalRow(
                data, interaction, this::displayIndexToLogical);

        if (rowFilter != null)
            rowFilter.cancelScan();
        if (scheduler != null)
            scheduler.cancelFilterScan();
        rowFilter = new DebugCollectionRowFilter(""); //$NON-NLS-1$
        int total = effectiveTotalSize();
        updateTableItemCount(total);
        if (splitTable != null)
        {
            splitTable.clearAll();
            if (anchorLogical >= 0)
            {
                DebugCollectionFilterViewportAnchor.restoreLogicalRow(data, anchorLogical, total, interaction);
                splitTable.setTopIndex(data.getTopIndex());
            }
            scheduleViewportCapture();
        }
    }

    private void applyFilterNow()
    {
        if (filterInput == null || filterInput.isDisposed())
        {
            DebugCollectionDebug.step("filter", "SKIP filterInput null");
            return;
        }
        String text = filterInput.getText();
        DebugCollectionDebug.step("filter", "text=[" + text + "]");
        if (text == null || text.isBlank())
        {
            cancelFilterScan();
            return;
        }
        if (rowFilter != null)
            rowFilter.cancelScan();
        if (scheduler != null)
            scheduler.cancelFilterScan();
        rowFilter = new DebugCollectionRowFilter(text);
        rowFilter.setPresentationOnly(isFilterByPresentationSelected());
        int total = effectiveTotalSize();
        if (!rowFilter.isActive())
        {
            updateTableItemCount(total);
            if (splitTable != null)
                splitTable.clearAll();
            scheduleViewportCapture();
            DebugCollectionDebug.step("filter", "scheduled viewport after filter");
            return;
        }
        rowFilter.beginScan(total);
        DebugCollectionDebug.step("filter", "beginScan total=" + total);
        scheduler.scheduleFilterScan(rowFilter, () -> {
            DebugCollectionDebug.step("filter", "scanDone visible=" + rowFilter.visibleCount(total) + " cancelled=" + rowFilter.isCancelled());
            if (rowFilter.isCancelled())
                return;
            updateTableItemCount(rowFilter.visibleCount(total));
            if (splitTable != null)
                splitTable.clearAll();
            scheduleViewportCapture();
        });
    }

    private void onVerticalScroll()
    {
        if (splitTable == null)
        {
            scheduleViewportCaptureDebounced();
            return;
        }
        int first = splitTable.getTopIndex();
        int logicalFirst = displayIndexToLogical(first);
        int visible = visibleRowCountEstimate();
        if (lastViewportLogicalFirst >= 0 && logicalFirst >= 0)
        {
            int delta = Math.abs(logicalFirst - lastViewportLogicalFirst);
            if (delta > Math.max(visible / 2, 8))
            {
                Table data = dataTable();
                if (data != null && !data.isDisposed())
                    data.getDisplay().asyncExec(this::scheduleViewportCapturePriority);
                return;
            }
        }
        scheduleViewportCaptureDebounced();
    }

    private void scheduleViewportCaptureDebounced()
    {
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return;
        if (viewportDebounce != null)
            data.getDisplay().timerExec(-1, viewportDebounce);
        viewportDebounce = () -> {
            viewportDebounce = null;
            scheduleViewportCapture();
        };
        data.getDisplay().timerExec(150, viewportDebounce);
    }

    /** Запомнить viewport для refresh по DebugEvent; SetData грузит ячейки лениво. */
    private void scheduleViewportCapture()
    {
        captureViewportRange(false);
    }

    private void scheduleViewportCapturePriority()
    {
        captureViewportRange(true);
    }

    private void captureViewportRange(boolean priority)
    {
        if (splitTable == null || scheduler == null || model == null)
            return;
        Table data = dataTable();
        if (data == null || data.isDisposed())
            return;
        int first = splitTable.getTopIndex();
        int last = first + visibleRowCountEstimate();
        int itemCount = splitTable.getItemCount();
        int displayLast = Math.min(last, itemCount - 1);
        int logicalFirst = displayIndexToLogical(first);
        int logicalLast = displayIndexToLogical(displayLast);
        if (logicalFirst < 0 || logicalLast < logicalFirst)
            return;
        lastViewportLogicalFirst = logicalFirst;
        if (priority)
        {
            for (int row = logicalFirst; row <= logicalLast; row++)
                model.invalidateLogicalRowPreservingReadySizes(row);
            splitTable.clear(first, displayLast);
            // #region agent log
            DebugCollectionAgentLog.log("H-endPaint", "Window.captureViewportRange", "priorityClear", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"logicalFirst\":" + logicalFirst + ",\"logicalLast\":" + logicalLast //$NON-NLS-1$ //$NON-NLS-2$
                    + ",\"displayTop\":" + first + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            scheduler.captureSizeViewport(logicalFirst, logicalLast, data);
            scheduler.requestViewportPriority(logicalFirst, logicalLast);
        }
        else
        {
            scheduler.captureSizeViewport(logicalFirst, logicalLast, data);
            scheduler.requestViewport(logicalFirst, logicalLast);
        }
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
        updateWindowTitle();
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
        boolean[] vis = DebugCollectionColumnVisibilityStore.visibilityFor(model.pathKey(), modelCount, model.columns);
        int[] order = columnOrderForDialog(modelCount);
        if (enableAndPreselectModelIndices != null)
        {
            for (int idx : enableAndPreselectModelIndices)
            {
                if (idx >= 0 && idx < vis.length)
                    vis[idx] = true;
            }
        }
        DebugCollectionColumnVisibilityDialog dialog =
            new DebugCollectionColumnVisibilityDialog(shell, model.columns, vis, order);
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
        DebugCollectionTableInteraction interaction = activeInteraction();
        if (interaction == null || model == null)
            return -1;
        int visibleCol = interaction.modelVisibleColumn();
        DebugCollectionColumnModel.Column col = model.columns.columnAt(visibleCol);
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
        DebugCollectionTableInteraction interaction = activeInteraction();
        if (interaction == null)
            return -1;
        TableItem item = interaction.selectedItem();
        if (item == null || item.isDisposed())
            return -1;
        Table table = item.getParent();
        if (table == null || table.isDisposed())
            return -1;
        return DebugCollectionTableItemKeys.displayIndex(item, table);
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
        DebugCollectionViewportTracker.scrollColumnIntoView(data, visibleCol - fixed);
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
        if (modelCount > DebugCollectionColumnVisibilityStore.WIDE_SCHEMA_THRESHOLD)
            return DebugCollectionColumnVisibilityStore.orderWithPreferred(model.columns);
        return DebugCollectionColumnVisibilityStore.orderFor(model.pathKey(), modelCount);
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
        DebugCollectionColumnVisibilityStore.save(model.pathKey(), visibility, order, presentation);
        model.clearCellCache();
        syncSplitTableColumns();
        if (splitTable != null)
            splitTable.clearAll();
        if (scheduler != null)
            scheduler.captureColumnViewport();
        scheduleViewportCapture();
        updateColumnSettingsButton();
        refreshPresentationCombo();
        applyFilterIfNonEmpty();
    }

    private void applyStoredPresentation(String pathKey)
    {
        if (model == null)
            return;
        String presentation = DebugCollectionColumnVisibilityStore.presentationFor(pathKey, model.columns);
        if (presentation == null || model.columns.findPresentationColumnByHeader(presentation) < 0)
        {
            model.columns.clearPresentation();
            return;
        }
        int modelCount = model.columns.modelColumnCount();
        boolean[] vis = DebugCollectionColumnVisibilityStore.visibilityFor(pathKey, modelCount, model.columns);
        int[] order = modelCount > DebugCollectionColumnVisibilityStore.WIDE_SCHEMA_THRESHOLD
            ? DebugCollectionColumnVisibilityStore.orderWithPreferred(model.columns)
            : DebugCollectionColumnVisibilityStore.orderFor(pathKey, modelCount);
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
            boolean[] vis = DebugCollectionColumnVisibilityStore.visibilityFor(
                pathKey, model.columns.modelColumnCount(), model.columns);
            int[] order = columnOrderForDialog(model.columns.modelColumnCount());
            if (modelIdx < vis.length)
                vis[modelIdx] = true;
            model.columns.applyVisibility(vis, order);
            model.columns.applyPresentationHeader(header);
            DebugCollectionColumnVisibilityStore.save(pathKey, vis, order, header);
        }
        else
        {
            model.columns.applyPresentationHeader(header);
            DebugCollectionColumnVisibilityStore.savePresentation(pathKey, header);
        }

        model.remapCellCacheForVisibleLayout(oldVisible);
        syncSplitTableColumns();
        refreshVisibleTableRows();
        updateFilterByPresentationCheckbox();
        if (needsVisibilityUpdate)
                        scheduleViewportCaptureDebounced();
        else
            applyFilterIfNonEmpty();
    }

    private void applyFilterIfNonEmpty()
    {
        if (filterInput == null || filterInput.isDisposed())
            return;
        String text = filterInput.getText();
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
        DebugCollectionColumnVisibilityStore.saveFilterByPresentation(
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
                    DebugCollectionColumnVisibilityStore.saveFilterByPresentation(pathKey, false);
                    applyFilterIfNonEmpty();
                }
            }
            else
                filterByPresentationCheckbox.setSelection(
                    DebugCollectionColumnVisibilityStore.filterByPresentationFor(pathKey));
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
            String selected = DebugCollectionColumnVisibilityStore.presentationFor(model.pathKey(), model.columns);
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
            return DebugCollectionColumnVisibilityStore.presentationFor(model.pathKey(), model.columns);
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

    /**
     * Таблица «Коллекция»: фиксированная колонка «Индекс» + прокручиваемые данные.
     * Между панелями — {@link SashForm} (перетаскиваемая граница).
     */
    private static final class DebugCollectionSplitTable
    {
        private static final int FALLBACK_VERTICAL_SCROLLBAR_WIDTH = 17;

        private final Composite panel;
        private final SashForm sash;
        private final Composite indexPane;
        private final Label indexBottomSpacer;
        private final Composite indexTableStack;
        private final Composite dataTableStack;
        private final Table indexTable;
        private final Table dataTable;
        private boolean syncingScroll;
        private boolean syncingSelection;
        private boolean syncingIndexLayout;
        private boolean syncingIndexPaneLayout;
        private boolean syncingRowArea;
        private boolean indexColumnSizingInstalled;
        private boolean sashSizingInstalled;
        private boolean initialSashWeightsApplied;
        private Runnable pendingSyncFixedPaneLayout;
        private Runnable pendingSyncRowAreaAlignment;

        private DebugCollectionSplitTable(Composite panel, SashForm sash, Composite indexPane,
            Label indexBottomSpacer, Composite indexTableStack, Composite dataTableStack,
            Table indexTable, Table dataTable)
        {
            this.panel = panel;
            this.sash = sash;
            this.indexPane = indexPane;
            this.indexBottomSpacer = indexBottomSpacer;
            this.indexTableStack = indexTableStack;
            this.dataTableStack = dataTableStack;
            this.indexTable = indexTable;
            this.dataTable = dataTable;
        }

        static DebugCollectionSplitTable create(Composite parent)
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

            DebugCollectionSplitTable split = new DebugCollectionSplitTable(panel, sash, indexPane,
                indexBottomSpacer, indexTableStack, dataTableStack, indexTable, dataTable);
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

        /** После {@link DebugCollectionColumnModel#syncSplitTables} — sash и колонка «Индекс» по сохранённой ширине. */
        void syncIndexColumnLayout()
        {
            if (indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
                return;
            syncFixedPaneLayout();
            Table data = dataTable();
            if (data != null && !data.isDisposed() && data.getColumnCount() > 0)
                DebugCollectionColumnModel.applyIndexTableColumnLayout(data, false);
            applySashWeightsForColumnWidth(DebugCollectionFixedPaneWidthStore.load());
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
                DebugCollectionFixedPaneWidthStore.save(width);
        }

        private void persistIndexColumnWidthsFromUi()
        {
            if (indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
                return;
            int indexW = indexTable.getColumn(0).getWidth();
            if (indexW > 0)
                DebugCollectionIndexColumnWidthStore.save(indexW);
            if (indexTable.getColumnCount() > 1)
            {
                int typeW = indexTable.getColumn(1).getWidth();
                if (typeW > 0)
                    DebugCollectionTypeColumnWidthStore.save(typeW);
            }
            if (indexTable.getColumnCount() > 2)
            {
                int presW = indexTable.getColumn(2).getWidth();
                if (presW > 0)
                    DebugCollectionPresentationColumnWidthStore.save(presW);
            }
        }

        private int fixedPaneWidth()
        {
            if (indexTable.isDisposed())
                return DebugCollectionIndexColumnWidthStore.load();
            int width = 0;
            for (org.eclipse.swt.widgets.TableColumn column : indexTable.getColumns())
                width += column.getWidth();
            if (width <= 0)
                width = DebugCollectionIndexColumnWidthStore.load();
            if (indexTable.getColumnCount() > 1)
                return width;
            return DebugCollectionIndexColumnWidthStore.clamp(width);
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
            int indexW = DebugCollectionIndexColumnWidthStore.load();
            int typeW = DebugCollectionTypeColumnWidthStore.load();
            int colCount = indexTable.getColumnCount();
            if (colCount == 2)
            {
                int maxIndex = Math.max(DebugCollectionIndexColumnWidthStore.MIN_WIDTH,
                    paneW - DebugCollectionTypeColumnWidthStore.MIN_WIDTH);
                if (indexW > maxIndex)
                {
                    indexW = maxIndex;
                    DebugCollectionIndexColumnWidthStore.save(indexW);
                }
                typeW = Math.max(DebugCollectionTypeColumnWidthStore.MIN_WIDTH, paneW - indexW);
            }
            else
            {
                int presW = DebugCollectionPresentationColumnWidthStore.load();
                int minPres = DebugCollectionPresentationColumnWidthStore.MIN_WIDTH;
                int minReserved = DebugCollectionIndexColumnWidthStore.MIN_WIDTH
                    + DebugCollectionTypeColumnWidthStore.MIN_WIDTH;
                int maxReserved = Math.max(minReserved, paneW - minPres);
                int reserved = indexW + typeW;
                if (reserved > maxReserved)
                {
                    int overflow = reserved - maxReserved;
                    int typeShrink = Math.min(overflow, typeW - DebugCollectionTypeColumnWidthStore.MIN_WIDTH);
                    typeW -= typeShrink;
                    overflow -= typeShrink;
                    if (overflow > 0)
                    {
                        indexW = Math.max(DebugCollectionIndexColumnWidthStore.MIN_WIDTH, indexW - overflow);
                        DebugCollectionIndexColumnWidthStore.save(indexW);
                    }
                }
                presW = Math.max(minPres, paneW - indexW - typeW);
                syncingIndexLayout = true;
                try
                {
                    indexTable.getColumn(0).setWidth(indexW);
                    indexTable.getColumn(1).setWidth(typeW);
                    indexTable.getColumn(2).setWidth(presW);
                    DebugCollectionPresentationColumnWidthStore.save(presW);
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
            if (syncingRowArea || indexPane.isDisposed() || indexTable.isDisposed() || dataTable.isDisposed()
                || dataTableStack.isDisposed())
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

                GridData indexGd = (GridData) indexTableStack.getLayoutData();
                if (indexGd.verticalIndent != 0)
                {
                    indexGd.verticalIndent = 0;
                    changed = true;
                }

                if (changed)
                    indexPane.layout(true);

                layoutIndexColumnHostAligned(bottomInset);
            }
            finally
            {
                syncingRowArea = false;
            }
        }

        private void layoutIndexColumnHostAligned(int bottomInset)
        {
            if (indexTable.isDisposed() || dataTableStack.isDisposed())
                return;
            Composite indexColumnHost = indexTable.getParent();
            if (indexColumnHost == null || indexColumnHost.isDisposed())
                return;
            int dataStackH = dataTableStack.getClientArea().height;
            if (dataStackH <= 0)
                dataStackH = dataTableStack.getBounds().height;
            int targetStackH = Math.max(0, dataStackH - bottomInset);
            int dataHeader = dataTable.getHeaderVisible() ? dataTable.getHeaderHeight() : 0;
            int indexHeader = indexTable.getHeaderVisible() ? indexTable.getHeaderHeight() : 0;
            int topInset = Math.max(0, dataHeader - indexHeader);
            Rectangle stackArea = indexTableStack.getClientArea();
            int stackW = stackArea.width;
            if (stackW <= 0)
                stackW = indexTableStack.getBounds().width;
            int hostH = Math.max(0, targetStackH - topInset);
            indexColumnHost.setBounds(0, topInset, stackW, hostH);
        }

        private void scheduleSyncRowAreaAlignment()
        {
            if (indexPane.isDisposed())
                return;
            Display display = indexPane.getDisplay();
            if (display == null || display.isDisposed())
                return;
            if (pendingSyncRowAreaAlignment != null)
                display.timerExec(-1, pendingSyncRowAreaAlignment);
            pendingSyncRowAreaAlignment = () -> {
                pendingSyncRowAreaAlignment = null;
                syncRowAreaAlignment();
            };
            display.timerExec(0, pendingSyncRowAreaAlignment);
        }

        private void wireHorizontalBarRowAreaSync(ScrollBar bar)
        {
            if (bar == null || bar.isDisposed())
                return;
            bar.addListener(SWT.Show, e -> scheduleSyncRowAreaAlignment());
            bar.addListener(SWT.Hide, e -> scheduleSyncRowAreaAlignment());
            bar.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    scheduleSyncRowAreaAlignment();
                }
            });
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
            boolean countChanged = !indexTable.isDisposed() && count != indexTable.getItemCount();
            if (!indexTable.isDisposed())
                indexTable.setItemCount(count);
            if (!dataTable.isDisposed())
                dataTable.setItemCount(count);
            if (countChanged)
                scheduleSyncFixedPaneLayout("setItemCount"); //$NON-NLS-1$
        }

        void clearAll()
        {
            if (!indexTable.isDisposed())
                indexTable.clearAll();
            if (!dataTable.isDisposed())
                dataTable.clearAll();
        }

        void clearDataAll()
        {
            if (!dataTable.isDisposed())
                dataTable.clearAll();
        }

        void clear(int first, int last)
        {
            if (!indexTable.isDisposed())
                indexTable.clear(first, last);
            if (!dataTable.isDisposed())
                dataTable.clear(first, last);
            if (!indexTable.isDisposed())
                indexTable.redraw();
            if (!dataTable.isDisposed())
                dataTable.redraw();
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
                scheduleSyncFixedPaneLayout("sashSelection"); //$NON-NLS-1$
            });
            sash.addListener(SWT.Resize, e -> applyInitialSashWeightsOnce());
        }

        /** После изменения ширины stack index-панели (не самой таблицы) — пересчитать ширины колонок. */
        private void installIndexPaneColumnSync()
        {
            indexTableStack.addControlListener(new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
                {
                    scheduleSyncFixedPaneLayout("indexStackResize"); //$NON-NLS-1$
                }
            });
            ScrollBar verticalBar = indexTable.getVerticalBar();
            if (verticalBar != null)
            {
                verticalBar.addListener(SWT.Show, e -> scheduleSyncFixedPaneLayout("vbarShow")); //$NON-NLS-1$
                verticalBar.addListener(SWT.Hide, e -> scheduleSyncFixedPaneLayout("vbarHide")); //$NON-NLS-1$
            }
        }

        private void scheduleSyncFixedPaneLayout()
        {
            scheduleSyncFixedPaneLayout("unknown"); //$NON-NLS-1$
        }

        private void scheduleSyncFixedPaneLayout(String source)
        {
            if (indexTable.isDisposed())
                return;
            Display display = indexTable.getDisplay();
            if (display == null || display.isDisposed())
                return;
            if (pendingSyncFixedPaneLayout != null)
                display.timerExec(-1, pendingSyncFixedPaneLayout);
            pendingSyncFixedPaneLayout = () -> {
                pendingSyncFixedPaneLayout = null;
                syncFixedPaneLayout();
            };
            display.timerExec(syncFixedPaneLayoutDelayMs(source), pendingSyncFixedPaneLayout);
        }

        private static int syncFixedPaneLayoutDelayMs(String source)
        {
            if ("indexStackResize".equals(source) || "sashSelection".equals(source) //$NON-NLS-1$ //$NON-NLS-2$
                || "vbarShow".equals(source) || "vbarHide".equals(source) //$NON-NLS-1$ //$NON-NLS-2$
                || "setItemCount".equals(source)) //$NON-NLS-1$
                return 0;
            return 50;
        }

        private void installRowAreaAlignmentSync()
        {
            ControlAdapter resizeListener = new ControlAdapter()
            {
                @Override
                public void controlResized(ControlEvent e)
                {
                    scheduleSyncRowAreaAlignment();
                }
            };
            dataTable.addControlListener(resizeListener);
            indexTable.addControlListener(resizeListener);
            indexTableStack.addControlListener(resizeListener);
            dataTableStack.addControlListener(resizeListener);
            sash.addListener(SWT.Resize, e -> scheduleSyncRowAreaAlignment());

            wireHorizontalBarRowAreaSync(dataTable.getHorizontalBar());
            wireHorizontalBarRowAreaSync(indexTable.getHorizontalBar());

            indexPane.getDisplay().asyncExec(this::scheduleSyncRowAreaAlignment);
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

        /** Резерв под вертикальный скролл index-таблицы — без горизонтального. */
        private static int verticalScrollBarOccupiedWidth(Table table)
        {
            if (table == null || table.isDisposed())
                return 0;
            ScrollBar bar = table.getVerticalBar();
            if (bar != null && !bar.isDisposed() && bar.isVisible() && bar.getSize().x > 0)
                return 0;
            int itemCount = table.getItemCount();
            if (itemCount <= 0)
                return 0;
            int rowHeight = table.getItemHeight() + (table.getLinesVisible() ? 1 : 0);
            int headerHeight = table.getHeaderVisible() ? table.getHeaderHeight() : 0;
            int contentHeight = headerHeight + itemCount * rowHeight;
            int availableHeight = table.getClientArea().height;
            if (availableHeight <= 0)
                availableHeight = table.getBounds().height;
            if (contentHeight <= availableHeight)
                return 0;
            if (bar != null && !bar.isDisposed() && bar.getSize().x > 0)
                return bar.getSize().x;
            return FALLBACK_VERTICAL_SCROLLBAR_WIDTH;
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
            applySashWeightsForColumnWidth(DebugCollectionFixedPaneWidthStore.load());
            if (!indexTable.isDisposed())
                scheduleSyncFixedPaneLayout("applyInitialSash"); //$NON-NLS-1$
        }

        /** Две колонки — «Индекс» + «Тип»; три — + «Представление» с сохранёнными ширинами. */
        private void syncFixedPaneLayout()
        {
            if (syncingIndexPaneLayout || indexTable.isDisposed() || indexTable.getColumnCount() <= 0)
                return;
            int[] colsBefore = indexColumnWidths();
            syncingIndexPaneLayout = true;
            try
            {
                if (indexTable.getColumnCount() > 1)
                    syncFixedPaneColumnWidths();
                else
                    syncIndexColumnToFillPane();
                clampIndexColumnsToClientArea();
                if (columnWidthsChanged(colsBefore, indexColumnWidths()))
                    DebugCollectionColumnModel.applyIndexTableColumnLayout(indexTable);
                trimIndexHorizontalScrollIfNeeded();
            }
            finally
            {
                syncingIndexPaneLayout = false;
            }
        }

        private void onFixedPaneColumnHeaderResized(TableColumn column)
        {
            if (syncingIndexLayout || syncingIndexPaneLayout || indexTable.isDisposed() || column == null
                || column.isDisposed())
                return;
            int columnIndex = indexOfColumn(column);
            if (columnIndex == 0)
            {
                int width = DebugCollectionIndexColumnWidthStore.clamp(column.getWidth());
                DebugCollectionIndexColumnWidthStore.save(width);
                if (indexTable.getColumnCount() > 1)
                    scheduleSyncFixedPaneLayout("columnHeaderResize"); //$NON-NLS-1$
                else
                    persistIndexColumnWidth(width);
            }
            else if (columnIndex == 1)
            {
                DebugCollectionTypeColumnWidthStore.save(column.getWidth());
                if (indexTable.getColumnCount() > 1)
                    scheduleSyncFixedPaneLayout("columnHeaderResize"); //$NON-NLS-1$
            }
            else if (columnIndex == 2)
            {
                DebugCollectionPresentationColumnWidthStore.save(column.getWidth());
                if (indexTable.getColumnCount() > 2)
                    scheduleSyncFixedPaneLayout("columnHeaderResize"); //$NON-NLS-1$
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
            width = DebugCollectionIndexColumnWidthStore.clamp(width);
            DebugCollectionIndexColumnWidthStore.save(width);
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
                int maxFixed = DebugCollectionIndexColumnWidthStore.MAX_WIDTH
                    + DebugCollectionTypeColumnWidthStore.MAX_WIDTH;
                if (indexTable.getColumnCount() > 2)
                    maxFixed += DebugCollectionPresentationColumnWidthStore.MAX_WIDTH;
                columnWidth = Math.min(maxFixed, Math.max(DebugCollectionIndexColumnWidthStore.MIN_WIDTH, columnWidth));
            }
            else
                columnWidth = DebugCollectionIndexColumnWidthStore.clamp(columnWidth);
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
            return DebugCollectionIndexColumnWidthStore.clamp(fixedPaneInnerWidth());
        }

        /** Ширина клиентской области index-таблицы без усечения до MAX колонки «Индекс». */
        private int fixedPaneInnerWidth()
        {
            return indexColumnSpanMaxWidth();
        }

        /** Сумма ширин колонок, умещающаяся в client area (минус vscroll и линии сетки). */
        private int indexColumnSpanMaxWidth()
        {
            if (indexTable.isDisposed())
                return DebugCollectionIndexColumnWidthStore.MIN_WIDTH;
            int client = indexTable.getClientArea().width;
            if (client <= 0)
                client = indexTable.getBounds().width;
            client -= verticalScrollBarOccupiedWidth(indexTable);
            client -= fixedPaneGridLineReserve();
            return Math.max(DebugCollectionIndexColumnWidthStore.MIN_WIDTH, client);
        }

        private int fixedPaneGridLineReserve()
        {
            if (!indexTable.getLinesVisible() || indexTable.getColumnCount() <= 1)
                return 0;
            return indexTable.getGridLineWidth() * (indexTable.getColumnCount() - 1);
        }

        /** Подгонка суммы колонок под client area — без горизонтального скролла. */
        private void clampIndexColumnsToClientArea()
        {
            if (syncingIndexLayout || indexTable.isDisposed())
                return;
            int colCount = indexTable.getColumnCount();
            if (colCount <= 0)
                return;
            int maxSum = indexColumnSpanMaxWidth();
            int sum = 0;
            for (int i = 0; i < colCount; i++)
                sum += indexTable.getColumn(i).getWidth();
            if (sum <= maxSum)
                return;
            int overflow = sum - maxSum;
            syncingIndexLayout = true;
            try
            {
                for (int i = colCount - 1; i >= 0 && overflow > 0; i--)
                {
                    TableColumn column = indexTable.getColumn(i);
                    int minW = minWidthForFixedColumn(i);
                    int shrink = Math.min(overflow, Math.max(0, column.getWidth() - minW));
                    if (shrink <= 0)
                        continue;
                    column.setWidth(column.getWidth() - shrink);
                    overflow -= shrink;
                }
            }
            finally
            {
                syncingIndexLayout = false;
            }
        }

        private void trimIndexHorizontalScrollIfNeeded()
        {
            if (syncingIndexLayout || indexTable.isDisposed())
                return;
            int colCount = indexTable.getColumnCount();
            if (colCount <= 0)
                return;
            int scrollRange = indexHorizontalScrollRange(indexTable);
            if (scrollRange <= 0)
                return;
            syncingIndexLayout = true;
            try
            {
                TableColumn column = indexTable.getColumn(colCount - 1);
                int minW = minWidthForFixedColumn(colCount - 1);
                column.setWidth(Math.max(minW, column.getWidth() - scrollRange));
            }
            finally
            {
                syncingIndexLayout = false;
            }
        }

        private static int minWidthForFixedColumn(int columnIndex)
        {
            if (columnIndex == 0)
                return DebugCollectionIndexColumnWidthStore.MIN_WIDTH;
            if (columnIndex == 1)
                return DebugCollectionTypeColumnWidthStore.MIN_WIDTH;
            return DebugCollectionPresentationColumnWidthStore.MIN_WIDTH;
        }

        private int[] indexColumnWidths()
        {
            if (indexTable.isDisposed())
                return new int[0];
            int count = indexTable.getColumnCount();
            int[] widths = new int[count];
            for (int i = 0; i < count; i++)
                widths[i] = indexTable.getColumn(i).getWidth();
            return widths;
        }

        private static int indexHorizontalScrollRange(Table table)
        {
            ScrollBar bar = table.getHorizontalBar();
            if (bar == null || bar.isDisposed())
                return 0;
            return bar.getMaximum() - bar.getMinimum() - bar.getThumb() + 1;
        }

        private static boolean columnWidthsChanged(int[] before, int[] after)
        {
            if (before == null || after == null)
                return before != after;
            if (before.length != after.length)
                return true;
            for (int i = 0; i < before.length; i++)
            {
                if (Math.abs(before[i] - after[i]) >= 2)
                    return true;
            }
            return false;
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

        /**
         * Ширина левой (фиксированной) панели split-table «Коллекция» — sash между индексом и данными.
         */
        private static final class DebugCollectionFixedPaneWidthStore
        {
            private static final String PREF_WIDTH = "debug.collection.fixedPane.width"; //$NON-NLS-1$
            static final int DEFAULT_WIDTH = DebugCollectionIndexColumnWidthStore.DEFAULT_WIDTH
                + DebugCollectionTypeColumnWidthStore.DEFAULT_WIDTH;
            static final int MIN_WIDTH = DebugCollectionIndexColumnWidthStore.MIN_WIDTH
                + DebugCollectionTypeColumnWidthStore.MIN_WIDTH;
            static final int MAX_WIDTH = DebugCollectionIndexColumnWidthStore.MAX_WIDTH
                + DebugCollectionTypeColumnWidthStore.MAX_WIDTH
                + DebugCollectionPresentationColumnWidthStore.MAX_WIDTH;

            private static ScopedPreferenceStore prefs;

            private DebugCollectionFixedPaneWidthStore() {}

            static int load()
            {
                ScopedPreferenceStore store = prefs();
                if (store == null || !store.contains(PREF_WIDTH))
                    return fallbackWidth();
                return clamp(store.getInt(PREF_WIDTH));
            }

            static void save(int width)
            {
                ScopedPreferenceStore store = prefs();
                if (store == null)
                    return;
                store.setValue(PREF_WIDTH, clamp(width));
                try
                {
                    store.save();
                }
                catch (Exception ignored)
                {
                    // prefs optional
                }
            }

            static int clamp(int width)
            {
                if (width < MIN_WIDTH)
                    return MIN_WIDTH;
                if (width > MAX_WIDTH)
                    return MAX_WIDTH;
                return width;
            }

            private static int fallbackWidth()
            {
                return clamp(DebugCollectionIndexColumnWidthStore.load() + DebugCollectionTypeColumnWidthStore.load());
            }

            private static ScopedPreferenceStore prefs()
            {
                if (prefs != null)
                    return prefs;
                try
                {
                    String pluginId = FrameworkUtil.getBundle(DebugCollectionFixedPaneWidthStore.class).getSymbolicName();
                    prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
                }
                catch (Exception ignored)
                {
                    return null;
                }
                return prefs;
            }
        }

    }


    /**
     * Диалог видимости колонок коллекции (⚙): список с чекбоксами, «Включить» / «Выключить».
     */
    private static final class DebugCollectionColumnVisibilityDialog extends Dialog
    {
        @FunctionalInterface
        interface ColumnActivateListener
        {
            void onColumnActivate(int modelIndex, boolean[] visibility, int[] order);
        }

        private final DebugCollectionColumnModel columns;
        private final boolean[] visibility;
        private int[] order;
        private int[] preselectedModelIndices;
        private int focusModelIndex = -1;

        private Table columnTable;
        private String findText = ""; //$NON-NLS-1$
        private int lastFindListIndex = -1;
        private int findGeneration;
        private ColumnActivateListener columnActivateListener;

        DebugCollectionColumnVisibilityDialog(
            Shell parent,
            DebugCollectionColumnModel columns,
            boolean[] visibility,
            int[] order)
        {
            super(parent);
            this.columns = columns;
            this.visibility = visibility.clone();
            this.order = order.clone();
            setShellStyle(getShellStyle() | SWT.RESIZE);
        }

        boolean[] resultVisibility()
        {
            return visibility.clone();
        }

        int[] resultOrder()
        {
            return order.clone();
        }

        /** Выделить строки model-колонок; чекбоксы уже должны быть включены в {@code visibility}. */
        void preselectModelIndices(int[] modelIndices)
        {
            preselectedModelIndices = modelIndices != null ? modelIndices.clone() : null;
        }

        /** Только выделить и прокрутить к колонке (без изменения чекбоксов). */
        void focusModelIndex(int modelIndex)
        {
            focusModelIndex = modelIndex;
        }

        void setColumnActivateListener(ColumnActivateListener listener)
        {
            columnActivateListener = listener;
        }

        @Override
        protected void configureShell(Shell shell)
        {
            super.configureShell(shell);
            shell.setText(Global.withPluginWindowTitle("Колонки")); //$NON-NLS-1$
        }

        @Override
        protected Control createDialogArea(Composite parent)
        {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(2, false));

            Label hint = new Label(area, SWT.WRAP);
            hint.setText(
                "Отметьте видимые колонки. Двойной щелчок по строке — активировать колонку в таблице. «По алфавиту» — сортировка списка. «Вверх» / «Вниз» меняет позиции колонок. Ctrl+F — поиск. Настройки запоминаются в привязке к пути и типу колллекции до конца сессии."); //$NON-NLS-1$
            hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

            Composite actionBar = new Composite(area, SWT.NONE);
            actionBar.setLayout(new GridLayout(5, false));
            actionBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

            Button enable = new Button(actionBar, SWT.PUSH);
            enable.setText("Включить"); //$NON-NLS-1$
            enable.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            enable.addListener(SWT.Selection, e -> setCheckedOnSelection(true));

            Button disable = new Button(actionBar, SWT.PUSH);
            disable.setText("Выключить"); //$NON-NLS-1$
            disable.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            disable.addListener(SWT.Selection, e -> setCheckedOnSelection(false));

            Button sortAlpha = new Button(actionBar, SWT.PUSH);
            sortAlpha.setText("По алфавиту"); //$NON-NLS-1$
            sortAlpha.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            sortAlpha.addListener(SWT.Selection, e -> sortOrderAlphabetically());

            Button up = new Button(actionBar, SWT.PUSH);
            up.setText("Вверх"); //$NON-NLS-1$
            up.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            up.addListener(SWT.Selection, e -> moveSelected(-1));

            Button down = new Button(actionBar, SWT.PUSH);
            down.setText("Вниз"); //$NON-NLS-1$
            down.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            down.addListener(SWT.Selection, e -> moveSelected(1));

            columnTable = new Table(area, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.MULTI);
            GridData listGd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
            listGd.heightHint = 220;
            listGd.widthHint = 280;
            columnTable.setLayoutData(listGd);
            columnTable.setHeaderVisible(false);
            columnTable.setLinesVisible(true);
            refreshTableItems();
            applyPreselection();
            applyFocusSelection();
            installFindKeys();
            installColumnActivateOnDoubleClick();

            return area;
        }

        private void installColumnActivateOnDoubleClick()
        {
            columnTable.addListener(SWT.MouseDoubleClick, e -> {
                if (columnTable == null || columnTable.isDisposed() || columnActivateListener == null)
                    return;
                TableItem item = columnTable.getItem(new Point(e.x, e.y));
                if (item == null)
                    return;
                Object data = item.getData();
                if (!(data instanceof Integer modelIdx))
                    return;
                if (!item.getChecked())
                    item.setChecked(true);
                applyChecksToVisibility();
                columnActivateListener.onColumnActivate(modelIdx.intValue(), visibility.clone(), order.clone());
            });
        }

        private void installFindKeys()
        {
            Shell shell = getShell();
            if (shell == null)
                return;
            Listener filter = this::onFindKey;
            shell.addListener(SWT.KeyDown, filter);
            if (columnTable != null)
                columnTable.addListener(SWT.KeyDown, filter);
        }

        private void onFindKey(Event event)
        {
            if (event.keyCode == 'f' && (event.stateMask & SWT.MOD1) != 0)
            {
                openFindDialog();
                event.doit = false;
            }
            else if (event.keyCode == SWT.F3)
            {
                findNext((event.stateMask & SWT.SHIFT) == 0);
                event.doit = false;
            }
        }

        private void openFindDialog()
        {
            InputDialog dialog = new InputDialog(
                getShell(),
                Global.withPluginWindowTitle("Поиск"), //$NON-NLS-1$
                "Текст для поиска в списке колонок:", //$NON-NLS-1$
                findText,
                null);
            if (dialog.open() != org.eclipse.jface.window.Window.OK)
                return;
            findText = dialog.getValue() != null ? dialog.getValue().trim() : ""; //$NON-NLS-1$
            findGeneration++;
            lastFindListIndex = -1;
            findNext(true);
        }

        private void findNext(boolean forward)
        {
            if (findText.isEmpty() || columnTable == null || columnTable.isDisposed())
                return;
            int count = columnTable.getItemCount();
            if (count <= 0)
                return;

            String needle = findText.toLowerCase();
            int start = lastFindListIndex < 0 ? (forward ? -1 : count) : lastFindListIndex;
            int gen = findGeneration;

            for (int step = 1; step <= count; step++)
            {
                int listIdx = forward ? (start + step) % count : (start - step + count) % count;
                TableItem item = columnTable.getItem(listIdx);
                String text = item != null ? item.getText() : null;
                if (text != null && text.toLowerCase().contains(needle))
                {
                    if (gen != findGeneration)
                        return;
                    lastFindListIndex = listIdx;
                    columnTable.setSelection(listIdx);
                    columnTable.setFocus();
                    columnTable.showItem(item);
                    return;
                }
            }
        }

        @Override
        protected void okPressed()
        {
            applyChecksToVisibility();
            super.okPressed();
        }

        private void applyPreselection()
        {
            if (preselectedModelIndices == null || preselectedModelIndices.length == 0
                || columnTable == null || columnTable.isDisposed())
                return;

            java.util.Set<Integer> wanted = new java.util.HashSet<>();
            for (int idx : preselectedModelIndices)
                wanted.add(idx);

            java.util.List<Integer> listIndices = new java.util.ArrayList<>();
            for (int listIdx = 0; listIdx < columnTable.getItemCount(); listIdx++)
            {
                TableItem item = columnTable.getItem(listIdx);
                Object data = item.getData();
                if (!(data instanceof Integer modelIdx))
                    continue;
                if (!wanted.contains(modelIdx))
                    continue;
                item.setChecked(true);
                listIndices.add(listIdx);
            }

            if (listIndices.isEmpty())
                return;

            selectListRows(listIndices, true);
        }

        private void applyFocusSelection()
        {
            if (focusModelIndex < 0 || columnTable == null || columnTable.isDisposed())
                return;
            if (preselectedModelIndices != null && preselectedModelIndices.length > 0)
                return;

            int listIdx = listIndexForModel(focusModelIndex);
            if (listIdx < 0)
                return;
            selectListRows(java.util.List.of(listIdx), true);
        }

        private int listIndexForModel(int modelIndex)
        {
            for (int listIdx = 0; listIdx < columnTable.getItemCount(); listIdx++)
            {
                TableItem item = columnTable.getItem(listIdx);
                Object data = item.getData();
                if (data instanceof Integer idx && idx.intValue() == modelIndex)
                    return listIdx;
            }
            return -1;
        }

        private void selectListRows(java.util.List<Integer> listIndices, boolean focusTable)
        {
            if (listIndices.isEmpty())
                return;
            int firstListIdx = listIndices.get(0);
            int[] sel = new int[listIndices.size()];
            for (int i = 0; i < listIndices.size(); i++)
                sel[i] = listIndices.get(i);
            columnTable.setSelection(sel);
            if (focusTable)
                columnTable.setFocus();
            if (firstListIdx >= 0)
                columnTable.showItem(columnTable.getItem(firstListIdx));
        }

        private java.util.List<Integer> selectedModelIndices()
        {
            java.util.List<Integer> models = new java.util.ArrayList<>();
            for (int listIdx : columnTable.getSelectionIndices())
            {
                if (listIdx < 0 || listIdx >= columnTable.getItemCount())
                    continue;
                Object data = columnTable.getItem(listIdx).getData();
                if (data instanceof Integer modelIdx)
                    models.add(modelIdx);
            }
            return models;
        }

        private void sortOrderAlphabetically()
        {
            java.util.List<Integer> selectedModels = selectedModelIndices();
            java.util.List<Integer> sorted = new java.util.ArrayList<>(order.length);
            for (int modelIdx : order)
                sorted.add(modelIdx);
            sorted.sort((a, b) -> columnHeader(a).compareToIgnoreCase(columnHeader(b)));
            for (int i = 0; i < order.length; i++)
                order[i] = sorted.get(i);
            refreshTableItems();
            if (!selectedModels.isEmpty())
            {
                java.util.List<Integer> listIndices = new java.util.ArrayList<>();
                for (int modelIdx : selectedModels)
                {
                    int listIdx = listIndexForModel(modelIdx);
                    if (listIdx >= 0)
                        listIndices.add(listIdx);
                }
                if (!listIndices.isEmpty())
                    selectListRows(listIndices, false);
            }
            else if (focusModelIndex >= 0)
            {
                int listIdx = listIndexForModel(focusModelIndex);
                if (listIdx >= 0)
                    selectListRows(java.util.List.of(listIdx), false);
            }
        }

        private String columnHeader(int modelIndex)
        {
            if (modelIndex < 0 || modelIndex >= columns.allColumns().size())
                return ""; //$NON-NLS-1$
            String header = columns.allColumns().get(modelIndex).header;
            return header != null ? header : ""; //$NON-NLS-1$
        }

        private void refreshTableItems()
        {
            if (columnTable != null && !columnTable.isDisposed() && columnTable.getItemCount() > 0)
                applyChecksToVisibility();
            columnTable.removeAll();
            for (int modelIdx : order)
            {
                if (modelIdx < 0 || modelIdx >= columns.allColumns().size())
                    continue;
                TableItem item = new TableItem(columnTable, SWT.NONE);
                item.setText(columns.allColumns().get(modelIdx).header);
                item.setChecked(modelIdx < visibility.length && visibility[modelIdx]);
                item.setData(Integer.valueOf(modelIdx));
            }
        }

        private void setCheckedOnSelection(boolean checked)
        {
            for (int listIdx : columnTable.getSelectionIndices())
            {
                if (listIdx >= 0 && listIdx < columnTable.getItemCount())
                    columnTable.getItem(listIdx).setChecked(checked);
            }
        }

        private void applyChecksToVisibility()
        {
            for (int i = 0; i < visibility.length; i++)
                visibility[i] = false;
            for (TableItem item : columnTable.getItems())
            {
                Object data = item.getData();
                if (!(data instanceof Integer modelIdx))
                    continue;
                if (modelIdx >= 0 && modelIdx < visibility.length && item.getChecked())
                    visibility[modelIdx] = true;
            }
            if (noneVisible())
                visibility[0] = true;
        }

        private boolean noneVisible()
        {
            for (boolean v : visibility)
            {
                if (v)
                    return false;
            }
            return true;
        }

        private void moveSelected(int delta)
        {
            int[] sel = columnTable.getSelectionIndices();
            if (sel == null || sel.length != 1)
                return;
            int idx = sel[0];
            int target = idx + delta;
            if (target < 0 || target >= order.length)
                return;
            int tmp = order[idx];
            order[idx] = order[target];
            order[target] = tmp;
            refreshTableItems();
            columnTable.select(target);
        }
    }


    /**
     * Настройки видимости колонок по выражению пути коллекции (сессия JVM).
     */
    private static final class DebugCollectionColumnVisibilityStore
    {
        static final int WIDE_SCHEMA_THRESHOLD = 8;

        private static final int DEFAULT_VISIBLE_PROPERTY_COUNT = 5;

        private static final String[] PREFERRED_PROPERTY_HEADERS = {
            "Имя", "Name", //$NON-NLS-1$ //$NON-NLS-2$
            "Представление", "Presentation", //$NON-NLS-1$ //$NON-NLS-2$
            "Тип", "Type", //$NON-NLS-1$ //$NON-NLS-2$
            "Синоним", "Synonym", //$NON-NLS-1$ //$NON-NLS-2$
            "Значение", "Value", //$NON-NLS-1$ //$NON-NLS-2$
        };

        private static final Map<String, boolean[]> VISIBILITY = new HashMap<>();
        private static final Map<String, int[]> ORDER = new HashMap<>();
        private static final Map<String, String> PRESENTATION = new HashMap<>();
        private static final Map<String, Boolean> FILTER_BY_PRESENTATION = new HashMap<>();

        /** Приоритет колонки «Представление» по умолчанию (рус / англ). */
        private static final String[][] DEFAULT_PRESENTATION_PRIORITY = {
            { "Имя", "Name" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "Поле", "Field" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "ПутьКДанным", "PathToData", "DataPath" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { "Заголовок", "Title", "Caption" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { "Ключ", "Key" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "Значение", "Value" }, //$NON-NLS-1$ //$NON-NLS-2$
        };

        private DebugCollectionColumnVisibilityStore() {}

        static boolean[] visibilityFor(String pathKey, int columnCount, DebugCollectionColumnModel columns)
        {
            boolean[] stored = VISIBILITY.get(pathKey);
            if (stored != null && stored.length == columnCount)
                return stored.clone();
            int count = columns.allColumns().size();
            if (count > WIDE_SCHEMA_THRESHOLD)
                return wideUnionVisibility(count, orderWithPreferred(columns));
            return defaultVisibility(columns);
        }

        /**
         * Широкая union-схема по умолчанию: первые {@link DebugCollectionColumnModel#MAX_DEFAULT_VISIBLE_COLUMNS}
         * в preferred-порядке; остальные скрыты (колонка-затычка в таблице).
         */
        static boolean[] wideUnionVisibility(int columnCount, int[] order)
        {
            boolean[] vis = new boolean[columnCount];
            int shown = 0;
            if (order != null)
            {
                for (int modelIdx : order)
                {
                    if (modelIdx < 0 || modelIdx >= columnCount)
                        continue;
                    if (shown >= DebugCollectionColumnModel.MAX_DEFAULT_VISIBLE_COLUMNS)
                        break;
                    vis[modelIdx] = true;
                    shown++;
                }
            }
            if (shown == 0 && columnCount > 0)
                vis[0] = true;
            return vis;
        }

        /** Порядок: индекс, preferred property, остальные. */
        static int[] orderWithPreferred(DebugCollectionColumnModel columns)
        {
            int count = columns.allColumns().size();
            List<Integer> ordered = new ArrayList<>();
            Set<Integer> used = new HashSet<>();

            for (int i = 0; i < count; i++)
            {
                DebugCollectionColumnModel.Column col = columns.allColumns().get(i);
                if (col.kind == DebugCollectionColumnModel.Kind.INDEX)
                {
                    ordered.add(i);
                    used.add(i);
                    break;
                }
            }

            int typeModel = columns.typeModelIndex();
            if (typeModel >= 0 && !used.contains(typeModel))
            {
                ordered.add(typeModel);
                used.add(typeModel);
            }

            for (String preferred : PREFERRED_PROPERTY_HEADERS)
            {
                for (int i = 0; i < count; i++)
                {
                    if (used.contains(i))
                        continue;
                    DebugCollectionColumnModel.Column col = columns.allColumns().get(i);
                    if (col.kind != DebugCollectionColumnModel.Kind.PROPERTY)
                        continue;
                    if (col.header != null && col.header.equalsIgnoreCase(preferred))
                    {
                        ordered.add(i);
                        used.add(i);
                        break;
                    }
                }
            }

            for (int i = 0; i < count; i++)
            {
                if (!used.contains(i))
                    ordered.add(i);
            }

            int[] result = new int[ordered.size()];
            for (int i = 0; i < ordered.size(); i++)
                result[i] = ordered.get(i);
            return result;
        }

        static boolean[] visibilityFor(String pathKey, int columnCount)
        {
            boolean[] stored = VISIBILITY.get(pathKey);
            if (stored == null || stored.length != columnCount)
            {
                boolean[] defaults = new boolean[columnCount];
                for (int i = 0; i < columnCount; i++)
                    defaults[i] = true;
                return defaults;
            }
            return stored.clone();
        }

        static int[] orderFor(String pathKey, int columnCount)
        {
            int[] stored = ORDER.get(pathKey);
            if (stored == null || stored.length != columnCount)
            {
                int[] defaults = new int[columnCount];
                for (int i = 0; i < columnCount; i++)
                    defaults[i] = i;
                return defaults;
            }
            return stored.clone();
        }

        static void save(String pathKey, boolean[] visibility, int[] order)
        {
            save(pathKey, visibility, order, null);
        }

        static void save(String pathKey, boolean[] visibility, int[] order, String presentationHeader)
        {
            if (pathKey == null || pathKey.isBlank())
                return;
            if (visibility != null)
                VISIBILITY.put(pathKey, visibility.clone());
            if (order != null)
                ORDER.put(pathKey, order.clone());
            if (presentationHeader != null && !presentationHeader.isBlank())
                PRESENTATION.put(pathKey, presentationHeader);
        }

        static void savePresentation(String pathKey, String presentationHeader)
        {
            save(pathKey, null, null, presentationHeader);
        }

        static boolean filterByPresentationFor(String pathKey)
        {
            if (pathKey == null || pathKey.isBlank())
                return false;
            return Boolean.TRUE.equals(FILTER_BY_PRESENTATION.get(pathKey));
        }

        static void saveFilterByPresentation(String pathKey, boolean value)
        {
            if (pathKey == null || pathKey.isBlank())
                return;
            if (value)
                FILTER_BY_PRESENTATION.put(pathKey, Boolean.TRUE);
            else
                FILTER_BY_PRESENTATION.remove(pathKey);
        }

        static String presentationFor(String pathKey, DebugCollectionColumnModel columns)
        {
            String stored = PRESENTATION.get(pathKey);
            if (stored != null && columns.findPresentationColumnByHeader(stored) >= 0)
                return stored;
            return defaultPresentationHeader(columns);
        }

        static String defaultPresentationHeader(DebugCollectionColumnModel columns)
        {
            if (columns == null)
                return null;
            for (String[] group : DEFAULT_PRESENTATION_PRIORITY)
            {
                for (String candidate : group)
                {
                    String resolved = columns.resolveHeaderInSchema(candidate);
                    if (resolved != null)
                        return resolved;
                }
            }
            return null;
        }

        private static boolean[] defaultVisibility(DebugCollectionColumnModel columns)
        {
            int count = columns.allColumns().size();
            boolean[] vis = new boolean[count];
            if (count <= WIDE_SCHEMA_THRESHOLD)
            {
                for (int i = 0; i < count; i++)
                    vis[i] = true;
                return vis;
            }

            for (int i = 0; i < count; i++)
            {
                DebugCollectionColumnModel.Column col = columns.allColumns().get(i);
                vis[i] = col.kind == DebugCollectionColumnModel.Kind.INDEX
                    || DebugCollectionColumnModel.isTypeColumn(col);
            }

            int propertyShown = 0;
            Set<Integer> shown = new HashSet<>();

            for (String preferred : PREFERRED_PROPERTY_HEADERS)
            {
                if (propertyShown >= DEFAULT_VISIBLE_PROPERTY_COUNT)
                    break;
                for (int i = 0; i < count; i++)
                {
                    if (shown.contains(i))
                        continue;
                    DebugCollectionColumnModel.Column col = columns.allColumns().get(i);
                    if (col.kind != DebugCollectionColumnModel.Kind.PROPERTY)
                        continue;
                    if (col.header != null && col.header.equalsIgnoreCase(preferred))
                    {
                        vis[i] = true;
                        shown.add(i);
                        propertyShown++;
                        break;
                    }
                }
            }

            for (int i = 0; i < count && propertyShown < DEFAULT_VISIBLE_PROPERTY_COUNT; i++)
            {
                if (shown.contains(i))
                    continue;
                DebugCollectionColumnModel.Column col = columns.allColumns().get(i);
                if (col.kind == DebugCollectionColumnModel.Kind.PROPERTY)
                {
                    vis[i] = true;
                    shown.add(i);
                    propertyShown++;
                }
            }

            if (!anyVisible(vis))
                vis[0] = true;
            return vis;
        }

        private static boolean anyVisible(boolean[] vis)
        {
            for (boolean v : vis)
            {
                if (v)
                    return true;
            }
            return false;
        }
    }


    /**
     * Ctrl+F / F3 / Shift+F3 по таблице коллекции.
     */
    private static final class DebugCollectionFindSession
    {
        private final DebugCollectionWindow window;
        private String findText = ""; //$NON-NLS-1$
        private int findGeneration;
        private int lastDisplayIndex = -1;

        DebugCollectionFindSession(DebugCollectionWindow window)
        {
            this.window = window;
        }

        void openFindDialog(Shell shell)
        {
            if (shell != null && shell.isDisposed())
                shell = null;
            InputDialog dialog = new InputDialog(
                shell,
                Global.withPluginWindowTitle("Поиск"), //$NON-NLS-1$
                "Текст для поиска:", //$NON-NLS-1$
                findText,
                null);
            if (dialog.open() != org.eclipse.jface.window.Window.OK)
                return;
            findText = dialog.getValue() != null ? dialog.getValue().trim() : ""; //$NON-NLS-1$
            findGeneration++;
            lastDisplayIndex = -1;
            findNext(true);
        }

        void findNext(boolean forward)
        {
            if (findText.isEmpty())
                return;
            Table table = window.getTable();
            if (table == null || table.isDisposed())
                return;

            int count = table.getItemCount();
            if (count <= 0)
                return;

            String needle = findText.toLowerCase();
            int start = lastDisplayIndex < 0 ? (forward ? -1 : count) : lastDisplayIndex;
            int gen = findGeneration;

            for (int step = 1; step <= count; step++)
            {
                int idx = forward ? (start + step) % count : (start - step + count) % count;
                int logical = window.displayIndexToLogical(idx);
                if (logical < 0)
                    continue;
                int hitCol = findHitColumn(logical, needle);
                if (hitCol >= 0)
                {
                    if (gen != findGeneration)
                        return;
                    lastDisplayIndex = idx;
                    window.selectDisplayRow(idx, hitCol);
                    TableItem item = table.getItem(idx);
                    if (item != null)
                        table.showItem(item);
                    window.requestRowLoad(logical);
                    return;
                }
            }
        }

        private int findHitColumn(int logicalRow, String needle)
        {
            int cols = window.getModel().columns.columnCount();
            for (int c = 0; c < cols; c++)
            {
                String text = window.getModel().getCellDisplayText(logicalRow, c);
                if (text != null && text.toLowerCase().contains(needle))
                    return c;
            }
            return -1;
        }
    }


    /**
     * ПКМ строки «Коллекция»: «Показать в Выражения» и «Инспектировать».
     */
    private static final class DebugCollectionRowContextSupport
    {
        private static final String WATCH_ITEM = "Показать в Выражения"; //$NON-NLS-1$
        private static final String INSPECT_ITEM = "Инспектировать"; //$NON-NLS-1$
        private static final String EXPRESSIONS_VIEW_ID =
            "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$

        private DebugCollectionRowContextSupport() {}

        static void showInExpressions(
            IBslVariable variable,
            IBslStackFrame frame,
            DebugCollectionTableModel model,
            int logicalRow,
            int visibleCol)
        {
            String expr = BslInspectSupport.resolveExpressionTextForVariable(
                variable, model, logicalRow, null);
            if (model != null)
                expr = expr + model.columns.propertyWatchSuffix(visibleCol);
            if (expr.isBlank())
            {
                DebugCollectionDebug.problem("watch: empty expression"); //$NON-NLS-1$
                return;
            }
            IWatchExpression watch = DebugPlugin.getDefault().getExpressionManager().newWatchExpression(expr);
            DebugPlugin.getDefault().getExpressionManager().addExpression(watch);
            if (frame != null)
                watch.setExpressionContext(frame);
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null)
                {
                    IWorkbenchPage page = window.getActivePage();
                    if (page != null)
                        page.showView(EXPRESSIONS_VIEW_ID);
                }
            }
            catch (Exception e)
            {
                DebugCollectionDebug.problem("show expressions: " + e.getMessage()); //$NON-NLS-1$
            }
            DebugCollectionDebug.step("watch", expr); //$NON-NLS-1$
        }

        static void runInspect(
            IBslVariable variable,
            IBslStackFrame frame,
            String columnHeader,
            org.eclipse.swt.widgets.Table anchorTable,
            Point anchor,
            Shell keepVisibleShell,
            DebugCollectionTableModel model,
            int logicalRow)
        {
            if (variable == null)
                return;

            org.eclipse.swt.widgets.Shell parent = inspectParentShell();
            if (parent == null || parent.isDisposed())
                return;

            if (keepVisibleShell != null && !keepVisibleShell.isDisposed())
                DebugCollectionShellPinHelper.refresh(keepVisibleShell);

            IDebugMonitoringManager monitoringManager = Global.getOsgiService(IDebugMonitoringManager.class);
            BslInspectSupport.openInspectForVariable(
                parent,
                anchor,
                variable,
                frame,
                monitoringManager,
                keepVisibleShell,
                columnHeader,
                model,
                logicalRow,
                null);
        }

        static void runInspect(
            IBslVariable variable,
            IBslStackFrame frame,
            String columnHeader,
            org.eclipse.swt.widgets.Table anchorTable,
            Point anchor,
            Shell keepVisibleShell)
        {
            runInspect(variable, frame, columnHeader, anchorTable, anchor, keepVisibleShell, null, -1);
        }

        /** Обёртка для refresh pin без доступа к полю окна. */
        static final class DebugCollectionShellPinHelper
        {
            private DebugCollectionShellPinHelper() {}

            static void refresh(Shell shell)
            {
                if (shell == null || shell.isDisposed())
                    return;
                Object data = shell.getData("tormozit.collectionShellPin"); //$NON-NLS-1$
                if (data instanceof DebugCollectionShellPin pin)
                    pin.refresh();
            }
        }

        static void runInspect(
            IBslVariable variable,
            IBslStackFrame frame,
            String columnHeader,
            org.eclipse.swt.widgets.Table anchorTable,
            Point anchor)
        {
            runInspect(variable, frame, columnHeader, anchorTable, anchor, null, null, -1);
        }

        private static org.eclipse.swt.widgets.Shell inspectParentShell()
        {
            try
            {
                org.eclipse.ui.IWorkbenchWindow window =
                    org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null)
                {
                    org.eclipse.ui.IWorkbenchWindow[] windows =
                        org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows();
                    if (windows != null && windows.length > 0)
                        window = windows[0];
                }
                if (window != null && window.getShell() != null && !window.getShell().isDisposed())
                {
                    org.eclipse.swt.widgets.Shell wb = window.getShell();
                    if (!DebugCollectionWindowRegistry.isCollectionShell(wb))
                        return wb;
                }
            }
            catch (Exception ignored)
            {
                // fallback ниже
            }
            org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
            if (display == null)
                return null;
            org.eclipse.swt.widgets.Shell active = display.getActiveShell();
            if (active != null && !active.isDisposed() && !DebugCollectionWindowRegistry.isCollectionShell(active))
                return active;
            return null;
        }

        static String watchMenuLabel()
        {
            return WATCH_ITEM;
        }

        static String inspectMenuLabel()
        {
            return INSPECT_ITEM;
        }
    }


    /**
     * Последние размер и позиция окна «Коллекция» (между сеансами EDT).
     */
    private static final class DebugCollectionWindowGeometryStore
    {
        private static final String PREF_X = "debug.collection.window.x"; //$NON-NLS-1$
        private static final String PREF_Y = "debug.collection.window.y"; //$NON-NLS-1$
        private static final String PREF_WIDTH = "debug.collection.window.width"; //$NON-NLS-1$
        private static final String PREF_HEIGHT = "debug.collection.window.height"; //$NON-NLS-1$

        private static final int DEFAULT_WIDTH = 900;
        private static final int DEFAULT_HEIGHT = 600;

        private static ScopedPreferenceStore prefs;

        private DebugCollectionWindowGeometryStore() {}

        static void applyToShell(Shell shell)
        {
            if (shell == null || shell.isDisposed())
                return;
            Rectangle bounds = load(shell.getDisplay());
            shell.setBounds(bounds);
        }

        static void saveFromShell(Shell shell)
        {
            if (shell == null || shell.isDisposed())
                return;
            Rectangle bounds = shell.getBounds();
            if (bounds.width <= 0 || bounds.height <= 0)
                return;
            Rectangle clamped = clampToMonitor(shell.getDisplay(), bounds,
                shell.getMinimumSize().x, shell.getMinimumSize().y);
            save(clamped);
        }

        private static Rectangle load(Display display)
        {
            ScopedPreferenceStore store = prefs();
            if (store == null || !hasStored(store))
                return defaultBounds(display);

            int x = store.getInt(PREF_X);
            int y = store.getInt(PREF_Y);
            int width = store.getInt(PREF_WIDTH);
            int height = store.getInt(PREF_HEIGHT);
            if (width <= 0 || height <= 0)
                return defaultBounds(display);
            return clampToMonitor(display, new Rectangle(x, y, width, height), 640, 400);
        }

        private static void save(Rectangle bounds)
        {
            ScopedPreferenceStore store = prefs();
            if (store == null || bounds == null)
                return;
            store.setValue(PREF_X, bounds.x);
            store.setValue(PREF_Y, bounds.y);
            store.setValue(PREF_WIDTH, bounds.width);
            store.setValue(PREF_HEIGHT, bounds.height);
            try
            {
                store.save();
            }
            catch (Exception ignored)
            {
                // prefs optional
            }
        }

        private static boolean hasStored()
        {
            return hasStored(prefs());
        }

        private static boolean hasStored(ScopedPreferenceStore store)
        {
            return store != null && store.contains(PREF_WIDTH) && store.contains(PREF_HEIGHT);
        }

        private static Rectangle defaultBounds(Display display)
        {
            Monitor monitor = display != null ? display.getPrimaryMonitor() : null;
            Rectangle client = monitor != null ? monitor.getClientArea() : new Rectangle(0, 0, 1920, 1080);
            int width = Math.min(DEFAULT_WIDTH, client.width);
            int height = Math.min(DEFAULT_HEIGHT, client.height);
            int x = client.x + Math.max(0, (client.width - width) / 2);
            int y = client.y + Math.max(0, (client.height - height) / 2);
            return new Rectangle(x, y, width, height);
        }

        private static Rectangle clampToMonitor(Display display, Rectangle bounds, int minWidth, int minHeight)
        {
            if (display == null || bounds == null)
                return bounds;

            Monitor monitor = display.getPrimaryMonitor();
            Point center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            for (Monitor candidate : display.getMonitors())
            {
                if (candidate.getBounds().contains(center))
                {
                    monitor = candidate;
                    break;
                }
            }

            Rectangle client = monitor.getClientArea();
            int width = Math.max(minWidth, Math.min(bounds.width, client.width));
            int height = Math.max(minHeight, Math.min(bounds.height, client.height));
            int x = bounds.x;
            int y = bounds.y;
            if (x + width > client.x + client.width)
                x = client.x + client.width - width;
            if (y + height > client.y + client.height)
                y = client.y + client.height - height;
            if (x < client.x)
                x = client.x;
            if (y < client.y)
                y = client.y;
            return new Rectangle(x, y, width, height);
        }

        private static ScopedPreferenceStore prefs()
        {
            if (prefs != null)
                return prefs;
            try
            {
                String pluginId = FrameworkUtil.getBundle(DebugCollectionWindowGeometryStore.class).getSymbolicName();
                prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
            }
            catch (Exception ignored)
            {
                return null;
            }
            return prefs;
        }
    }

}
