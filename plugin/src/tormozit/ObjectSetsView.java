package tormozit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;

/**
 * Панель «Наборы объектов».
 */
public final class ObjectSetsView extends ViewPart
{
    public static final String VIEW_ID = "tormozit.ObjectSetsView"; //$NON-NLS-1$

    private static final String SETTINGS_SECTION = "ObjectSetsView"; //$NON-NLS-1$
    private static final String KEY_SASH = "sashWeights"; //$NON-NLS-1$
    private static final String KEY_ITEMS_COL_NAME_WIDTH = "itemsColNameWidth"; //$NON-NLS-1$
    private static final String KEY_ITEMS_COL_PATH_WIDTH = "itemsColPathWidth"; //$NON-NLS-1$
    private static final String KEY_ITEMS_COL_ORDER      = "itemsColumnOrder"; //$NON-NLS-1$

    private static final int DEFAULT_ITEMS_NAME_COL_WIDTH = 120;
    private static final int DEFAULT_ITEMS_PATH_COL_WIDTH = 220;
    private static final int MIN_ITEMS_NAME_COL_WIDTH     = 50;
    private static final int MIN_ITEMS_PATH_COL_WIDTH     = 80;
    private static final int ITEMS_ICON_COL_WIDTH         = 24;
    private static final int ITEMS_COLUMN_SAVE_DELAY_MS   = 300;

    private static ObjectSetsView activeInstance;

    private IMemento workbenchState;

    private FilterInputBox filterInput;
    private SashForm sashForm;
    private TableViewer itemsViewer;
    private TableViewer setsViewer;
    private FormTableInteraction itemsInteraction;
    private TableColumn pathColumn;
    private TableColumn nameColumn;
    private NameLabelProvider nameLabelProvider;
    private ItemIconResolver iconResolver;
    private List<ObjectSets.Item> filteredItems = new ArrayList<>();
    private List<ObjectSets.SetDef> filteredSets = new ArrayList<>();
    private ObjectSets.SetDef selectedSet;
    private ObjectSets.SetDef dragSourceSet;
    private TableItem dropHighlightItem;

    private int itemsColumnSaveGeneration;
    private int cachedItemsNameWidth;
    private int cachedItemsPathWidth;

    private final Runnable storeListener = this::refreshOnUi;

    private ActiveProjectTracker.ContextProjectListener contextProjectListener;

    public static ObjectSetsView getActiveInstance()
    {
        return activeInstance;
    }

    public ObjectSets.SetDef getSelectedSet()
    {
        return selectedSet;
    }

    public ObjectSets.Item getSelectedItem()
    {
        List<ObjectSets.Item> items = getSelectedItems();
        return items.isEmpty() ? null : items.get(0);
    }

    public List<ObjectSets.Item> getSelectedItems()
    {
        List<ObjectSets.Item> result = new ArrayList<>();
        if (itemsViewer == null || itemsViewer.getControl().isDisposed())
            return result;
        IStructuredSelection sel = itemsViewer.getStructuredSelection();
        for (Object o : sel.toList())
        {
            if (o instanceof ObjectSets.Item item)
                result.add(item);
        }
        return result;
    }

    @Override
    public void init(IViewSite site, IMemento memento) throws PartInitException
    {
        super.init(site, memento);
        workbenchState = memento;
    }

    @Override
    public void saveState(IMemento memento)
    {
        writeItemsColumnLayoutToMemento(memento);
        super.saveState(memento);
    }

    @Override
    public void createPartControl(Composite parent)
    {
        activeInstance = this;
        parent.setLayout(new FillLayout());
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        createFilterRow(root);

        sashForm = new SashForm(root, SWT.HORIZONTAL);
        sashForm.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        createItemsPane(sashForm);
        createSetsPane(sashForm);
        sashForm.setWeights(readSashWeights());

        ObjectSets.getInstance().addChangeListener(storeListener);
        ObjectSetsAddTargetState.getInstance().addListener(storeListener);
        installContextProjectListener();
        refreshSetsTable();
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

        filterInput = FilterInputBox.forObjectSets(row, this::applyFilter);

        Label filler = new Label(row, SWT.NONE);
        filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    @Override
    public void setFocus()
    {
        refreshItemIcons();
        if (filterInput != null && !filterInput.isDisposed())
            filterInput.setFocus();
    }

    @Override
    public void dispose()
    {
        saveItemsColumnWidths();
        saveSashWeights();
        ObjectSets.getInstance().removeChangeListener(storeListener);
        ObjectSetsAddTargetState.getInstance().removeListener(storeListener);
        IWorkbenchPage page = getSite() != null ? getSite().getPage() : null;
        if (page != null && contextProjectListener != null)
            ActiveProjectTracker.removeListener(page, contextProjectListener);
        contextProjectListener = null;
        if (activeInstance == this)
            activeInstance = null;
        super.dispose();
    }

    private void createItemsPane(Composite parent)
    {
        Composite pane = new Composite(parent, SWT.NONE);
        pane.setLayout(new FillLayout());

        Composite tableStack = new Composite(pane, SWT.NONE);
        tableStack.setLayout(null);
        tableStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite columnHost = new Composite(tableStack, SWT.NONE);
        TableColumnLayout layout = new TableColumnLayout(true);
        columnHost.setLayout(layout);

        IDialogSettings settings = viewSettings();
        int nameWidth = readColWidth(workbenchState, settings, KEY_ITEMS_COL_NAME_WIDTH,
            DEFAULT_ITEMS_NAME_COL_WIDTH, MIN_ITEMS_NAME_COL_WIDTH);
        int pathWidth = readColWidth(workbenchState, settings, KEY_ITEMS_COL_PATH_WIDTH,
            DEFAULT_ITEMS_PATH_COL_WIDTH, MIN_ITEMS_PATH_COL_WIDTH);
        cachedItemsNameWidth = nameWidth;
        cachedItemsPathWidth = pathWidth;

        itemsViewer = new TableViewer(columnHost,
            SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = itemsViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        iconResolver = new ItemIconResolver();

        TableViewerColumn colIcon = new TableViewerColumn(itemsViewer, SWT.NONE);
        colIcon.getColumn().setText(""); //$NON-NLS-1$
        colIcon.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return ""; //$NON-NLS-1$
            }

            @Override
            public Image getImage(Object element)
            {
                return element instanceof ObjectSets.Item item
                    ? iconResolver.imageFor(item, getSite().getPage(), selectedSet) : null;
            }
        });
        layout.setColumnData(colIcon.getColumn(), new ColumnPixelData(ITEMS_ICON_COL_WIDTH, false, false));

        TableViewerColumn colName = new TableViewerColumn(itemsViewer, SWT.NONE);
        nameColumn = colName.getColumn();
        nameColumn.setText("Имя"); //$NON-NLS-1$
        nameLabelProvider = new NameLabelProvider();
        colName.setLabelProvider(new DelegatingStyledCellLabelProvider(nameLabelProvider));
        layout.setColumnData(nameColumn, new ColumnPixelData(nameWidth, true, true));

        TableViewerColumn colPath = new TableViewerColumn(itemsViewer, SWT.NONE);
        pathColumn = colPath.getColumn();
        pathColumn.setText("Путь"); //$NON-NLS-1$
        colPath.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (!(element instanceof ObjectSets.Item item))
                    return ""; //$NON-NLS-1$
                return placePrefix(item.displayName, item.ownName);
            }
        });
        layout.setColumnData(pathColumn, new ColumnPixelData(pathWidth, true, true));

        FormTableColumnOrder.load(settings, KEY_ITEMS_COL_ORDER, table);
        FormTableColumnOrder.load(workbenchState, KEY_ITEMS_COL_ORDER, table);

        itemsViewer.setContentProvider(ArrayContentProvider.getInstance());
        itemsInteraction = new FormTableInteraction(table, itemsViewer, (item, column) ->
        {
            if (!(item.getData() instanceof ObjectSets.Item row))
                return ""; //$NON-NLS-1$
            TableColumn col = table.getColumn(column);
            if (col == nameColumn)
                return row.ownName != null ? row.ownName : ""; //$NON-NLS-1$
            if (col == pathColumn)
                return placePrefix(row.displayName, row.ownName);
            return ""; //$NON-NLS-1$
        });
        itemsInteraction.install();

        installItemsColumnPersistence();

        table.addListener(SWT.MouseDoubleClick, e -> jumpToSelectedItem());
        table.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.keyCode == SWT.DEL)
                    removeSelectedItems();
                else if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)
                    jumpToSelectedItem();
            }
        });
        installItemsContextMenu(table);
        installItemsDropTarget(table);
        installItemsDragSource(table);
        tableStack.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                org.eclipse.swt.graphics.Rectangle area = tableStack.getClientArea();
                if (area.width < ITEMS_ICON_COL_WIDTH || area.height < 10)
                    return;
                scheduleItemIconsRefresh();
            }
        });
    }

    private void createSetsPane(Composite parent)
    {
        Composite pane = new Composite(parent, SWT.NONE);
        pane.setLayout(new GridLayout(1, false));

        setsViewer = new TableViewer(pane,
            SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = setsViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableViewerColumn colActive = new TableViewerColumn(setsViewer, SWT.NONE);
        colActive.getColumn().setText("Активный"); //$NON-NLS-1$
        colActive.getColumn().setWidth(56);
        colActive.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (!(element instanceof ObjectSets.SetDef set))
                    return ""; //$NON-NLS-1$
                return ObjectSetsAddTargetState.getInstance().isAddTarget(set.id) ? "●" : ""; //$NON-NLS-1$ //$NON-NLS-2$
            }
        });

        TableViewerColumn colSetName = new TableViewerColumn(setsViewer, SWT.NONE);
        colSetName.getColumn().setText("Имя"); //$NON-NLS-1$
        colSetName.getColumn().setWidth(120);
        colSetName.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof ObjectSets.SetDef set ? set.name : ""; //$NON-NLS-1$
            }
        });

        TableViewerColumn colCount = new TableViewerColumn(setsViewer, SWT.NONE);
        colCount.getColumn().setText("Объектов"); //$NON-NLS-1$
        colCount.getColumn().setWidth(64);
        colCount.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (!(element instanceof ObjectSets.SetDef set))
                    return ""; //$NON-NLS-1$
                return Integer.toString(set.items.size());
            }
        });

        TableViewerColumn colProject = new TableViewerColumn(setsViewer, SWT.NONE);
        colProject.getColumn().setText("Проект"); //$NON-NLS-1$
        colProject.getColumn().setWidth(90);
        colProject.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return element instanceof ObjectSets.SetDef set ? set.projectName : ""; //$NON-NLS-1$
            }
        });

        setsViewer.setContentProvider(ArrayContentProvider.getInstance());

        table.addListener(SWT.MouseDown, e ->
        {
            TableItem row = table.getItem(new Point(e.x, e.y));
            if (row == null)
                return;
            int col = columnIndexAt(table, e.x, row);
            if (col == 0 && row.getData() instanceof ObjectSets.SetDef set)
            {
                ObjectSetsAddTargetState.getInstance().setAddTarget(set.id);
                setsViewer.refresh();
            }
        });

        setsViewer.addSelectionChangedListener(event ->
        {
            ObjectSets.SetDef set = null;
            if (event.getStructuredSelection().getFirstElement() instanceof ObjectSets.SetDef s)
                set = s;
            selectedSet = set;
            refreshItemsTable();
        });

        installSetsDropTarget(table);

        Composite buttons = new Composite(pane, SWT.NONE);
        buttons.setLayout(new RowLayout(SWT.HORIZONTAL));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createButton(buttons, "Добавить", this::addSet); //$NON-NLS-1$
        createButton(buttons, "Переименовать", this::renameSet); //$NON-NLS-1$
        createButton(buttons, "Удалить", this::deleteSet); //$NON-NLS-1$
        createButton(buttons, "Выгрузить", this::exportSets); //$NON-NLS-1$
        createButton(buttons, "Загрузить", this::importSets); //$NON-NLS-1$
    }

    private static void createButton(Composite parent, String text, Runnable action)
    {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                action.run();
            }
        });
    }

    private void refreshOnUi()
    {
        Display display = getSite().getShell().getDisplay();
        if (display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (getViewSite() == null || getSite().getShell().isDisposed())
                return;
            refreshSetsTable();
        });
    }

    private void refreshSetsTable()
    {
        if (setsViewer == null || setsViewer.getControl().isDisposed())
            return;
        ensureDefaultSetForActiveProject();
        filteredSets = new ArrayList<>(ObjectSets.getInstance().getAllSets());
        applyFilter();
        ObjectSets.SetDef keep = selectedSet;
        setsViewer.setInput(filteredSets);
        if (keep != null)
        {
            for (ObjectSets.SetDef set : filteredSets)
            {
                if (keep.id.equals(set.id))
                {
                    setsViewer.setSelection(new StructuredSelection(set));
                    selectedSet = set;
                    break;
                }
            }
        }
        if (selectedSet == null && !filteredSets.isEmpty())
        {
            ObjectSets.SetDef preferred = addTargetSetForActiveProject();
            ObjectSets.SetDef pick = null;
            if (preferred != null)
            {
                for (ObjectSets.SetDef set : filteredSets)
                {
                    if (preferred.id.equals(set.id))
                    {
                        pick = set;
                        break;
                    }
                }
            }
            if (pick == null)
                pick = filteredSets.get(0);
            setsViewer.setSelection(new StructuredSelection(pick));
            selectedSet = pick;
        }
        refreshItemsTable();
    }

    private void installContextProjectListener()
    {
        IWorkbenchPage page = getSite().getPage();
        if (page == null)
            return;
        ActiveProjectTracker.bootstrapPage(page);
        contextProjectListener = (p, previous, current) -> onContextProjectChanged(current);
        ActiveProjectTracker.addListener(page, contextProjectListener);
    }

    private void onContextProjectChanged(IProject current)
    {
        if (setsViewer == null || setsViewer.getControl().isDisposed())
            return;
        Display display = getSite().getShell().getDisplay();
        if (display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (getViewSite() == null || setsViewer.getControl().isDisposed())
                return;
            if (current != null)
            {
                ObjectSets.getInstance().ensureDefaultSetForProject(current.getName());
                ObjectSetsAddTargetState.getInstance().ensureForProject(current.getName());
            }
            selectAddTargetSetForActiveProject();
        });
    }

    private void selectAddTargetSetForActiveProject()
    {
        if (setsViewer == null || setsViewer.getControl().isDisposed())
            return;
        ObjectSets.SetDef target = addTargetSetForActiveProject();
        if (target == null)
            return;
        if (filteredSets.isEmpty())
        {
            refreshSetsTable();
            return;
        }
        for (ObjectSets.SetDef set : filteredSets)
        {
            if (target.id.equals(set.id))
            {
                selectedSet = set;
                setsViewer.setSelection(new StructuredSelection(set));
                setsViewer.refresh();
                refreshItemsTable();
                return;
            }
        }
        refreshSetsTable();
    }

    private ObjectSets.SetDef addTargetSetForActiveProject()
    {
        String projectName = activeProjectNameForSelection();
        if (projectName == null || projectName.isBlank())
            return null;
        ObjectSetsAddTargetState.getInstance().ensureForProject(projectName);
        return ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
    }

    private String activeProjectNameForSelection()
    {
        IWorkbenchPage page = getSite() != null ? getSite().getPage() : null;
        if (page == null)
            return null;
        IProject project = ActiveProjectTracker.peek(page);
        if (project == null)
            project = ActiveProjectTracker.resolveContextProject(page);
        return project != null ? project.getName() : null;
    }

    private void ensureDefaultSetForActiveProject()
    {
        String projectName = activeProjectNameForSelection();
        ObjectSets.getInstance().ensureDefaultSetForProject(projectName);
    }

    private void refreshItemsTable()
    {
        if (itemsViewer == null || itemsViewer.getControl().isDisposed())
            return;
        if (iconResolver != null)
            iconResolver.clearCache();
        if (selectedSet == null)
        {
            filteredItems = List.of();
            Table table = itemsViewer.getTable();
            table.setRedraw(false);
            try
            {
                itemsViewer.setInput(filteredItems);
                itemsViewer.refresh();
            }
            finally
            {
                table.setRedraw(true);
            }
            return;
        }
        applyFilter();
        scheduleItemIconsRefresh();
    }

    private void refreshItemIcons()
    {
        if (itemsViewer == null || itemsViewer.getControl().isDisposed())
            return;
        if (iconResolver != null)
            iconResolver.clearCache();
        Table table = itemsViewer.getTable();
        table.setRedraw(false);
        try
        {
            itemsViewer.refresh(true);
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    private void scheduleItemIconsRefresh()
    {
        if (itemsViewer == null || itemsViewer.getControl().isDisposed())
            return;
        Display display = itemsViewer.getControl().getDisplay();
        if (display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            if (itemsViewer == null || itemsViewer.getControl().isDisposed())
                return;
            refreshItemIcons();
        });
    }

    private void applyFilter()
    {
        if (itemsViewer == null || itemsViewer.getControl().isDisposed() || selectedSet == null)
            return;
        String pattern = filterInput != null ? filterInput.getText().trim() : ""; //$NON-NLS-1$
        SmartMatcher matcher = new SmartMatcher(pattern);
        nameLabelProvider.setMatcher(matcher);
        List<ObjectSets.Item> all = ObjectSets.getInstance().getItemsForDisplay(selectedSet.id);
        filteredItems = new ArrayList<>();
        for (ObjectSets.Item item : all)
        {
            if (matcher.isEmpty || matcher.matches(item.ownName))
                filteredItems.add(item);
        }
        Table table = itemsViewer.getTable();
        table.setRedraw(false);
        try
        {
            itemsViewer.setInput(filteredItems);
            itemsViewer.refresh();
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    void addToSetFromDrop(ObjectSets.SetDef target, IStructuredSelection dragSelection)
    {
        if (target == null || dragSelection == null || dragSelection.isEmpty())
            return;
        List<ObjectSets.Item> items;
        Object first = dragSelection.getFirstElement();
        if (first instanceof RecentPlaces.Entry)
        {
            List<RecentPlaces.Entry> entries = new ArrayList<>();
            for (Object o : dragSelection.toList())
            {
                if (o instanceof RecentPlaces.Entry entry)
                    entries.add(entry);
            }
            items = ObjectSetsItems.fromRecentPlaceEntries(entries, target);
        }
        else
            items = ObjectSetsItems.fromNavigatorSelection(dragSelection, target);
        ObjectSetsItems.addItemsToSet(target, items, getSite().getShell());
        setsViewer.setSelection(new StructuredSelection(target));
        selectedSet = target;
        refreshItemsTable();
    }

    private void addSet()
    {
        IWorkbenchPage page = getSite().getPage();
        IProject project = ActiveProjectTracker.resolveContextProject(page);
        if (project == null)
        {
            ToastNotification.show("Наборы объектов", "Отсутствует активный проект", 4000); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        InputDialog dialog = new InputDialog(getSite().getShell(),
            "Новый набор", "Имя набора:", "", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (dialog.open() != Window.OK)
            return;
        ObjectSets.SetDef set = ObjectSets.getInstance().createSet(dialog.getValue(), project.getName());
        if (set != null)
        {
            setsViewer.setSelection(new StructuredSelection(set));
            selectedSet = set;
            refreshItemsTable();
        }
    }

    private void renameSet()
    {
        if (selectedSet == null)
            return;
        InputDialog dialog = new InputDialog(getSite().getShell(),
            "Переименовать набор", "Имя:", selectedSet.name, null); //$NON-NLS-1$ //$NON-NLS-2$
        if (dialog.open() == Window.OK)
            ObjectSets.getInstance().renameSet(selectedSet.id, dialog.getValue());
    }

    private void deleteSet()
    {
        if (selectedSet == null)
            return;
        ObjectSets.getInstance().deleteSet(selectedSet.id);
        selectedSet = null;
    }

    private void exportSets()
    {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setFilterExtensions(new String[] { "*.txt", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
        String path = dialog.open();
        if (path == null)
            return;
        try
        {
            Files.writeString(new File(path).toPath(),
                ObjectSets.getInstance().exportText(), StandardCharsets.UTF_8);
            ToastNotification.show("Наборы объектов", "Выгружено в файл", 3000); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception ex)
        {
            ToastNotification.show("Наборы объектов", "Ошибка выгрузки: " + ex.getMessage(), 5000); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void importSets()
    {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setFilterExtensions(new String[] { "*.txt", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
        String path = dialog.open();
        if (path == null)
            return;
        try
        {
            String text = Files.readString(new File(path).toPath(), StandardCharsets.UTF_8);
            ObjectSets.getInstance().importText(text, true);
            ToastNotification.show("Наборы объектов", "Загружено из файла", 3000); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception ex)
        {
            ToastNotification.show("Наборы объектов", "Ошибка загрузки: " + ex.getMessage(), 5000); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void removeSelectedItems()
    {
        if (selectedSet == null)
            return;
        List<String> keys = new ArrayList<>();
        for (ObjectSets.Item item : getSelectedItems())
            keys.add(item.key);
        if (keys.isEmpty())
            return;
        int removed = ObjectSets.getInstance().removeItems(selectedSet.id, keys);
        if (removed > 0)
            ToastNotification.show("Наборы объектов", "Удалено " + removed, 3000); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void jumpToSelectedItem()
    {
        ObjectSets.Item item = getSelectedItem();
        if (item == null || selectedSet == null)
            return;
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(selectedSet.projectName);
        if (project == null || !project.exists())
        {
            ToastNotification.show("Наборы объектов",
                "Проект «" + selectedSet.projectName + "» не найден", 4000); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(project);
        if (v8Project == null)
        {
            ToastNotification.show("Наборы объектов",
                "Проект " + project.getName() + " не открыт", 4000); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (!GoToDefinition.jump(item.navRef, getSite().getShell(), getSite().getPage(), project)
                && !GoToDefinition.consumeJumpCancelled())
        {
            ToastNotification.show("Наборы объектов",
                "Не удалось перейти к:\n" + item.displayName, 5000); //$NON-NLS-1$
        }
    }

    private final class NameLabelProvider extends LabelProvider implements IStyledLabelProvider
    {
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

        void setMatcher(SmartMatcher m)
        {
            matcher = m;
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            if (!(element instanceof ObjectSets.Item item))
                return new StyledString();
            String name = item.ownName;
            if (name == null)
                name = ""; //$NON-NLS-1$

            StyledString styled = new StyledString();
            for (NameSegment seg : buildNameSegments(name, matcher))
            {
                if (seg.highlight)
                    styled.append(seg.text, SmartMatchHighlight.styler());
                else
                    styled.append(seg.text);
            }
            return styled;
        }
    }

    private static List<NameSegment> buildNameSegments(String name, SmartMatcher m)
    {
        List<NameSegment> result = new ArrayList<>();
        if (m.isEmpty || name.isEmpty())
        {
            result.add(new NameSegment(name, false));
            return result;
        }

        List<SmartMatcher.HighlightRange> raw = m.getHighlightRanges(name);
        List<int[]> ranges = new ArrayList<>();
        for (SmartMatcher.HighlightRange hr : raw)
            ranges.add(new int[] { hr.offset, hr.offset + hr.length });

        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges)
        {
            if (merged.isEmpty() || r[0] >= merged.get(merged.size() - 1)[1])
                merged.add(r);
            else
                merged.get(merged.size() - 1)[1] =
                    Math.max(merged.get(merged.size() - 1)[1], r[1]);
        }

        int pos = 0;
        for (int[] r : merged)
        {
            if (r[0] > pos)
                result.add(new NameSegment(name.substring(pos, r[0]), false));
            if (r[0] < name.length())
                result.add(new NameSegment(name.substring(r[0], Math.min(r[1], name.length())), true));
            pos = r[1];
        }
        if (pos < name.length())
            result.add(new NameSegment(name.substring(pos), false));
        if (result.isEmpty())
            result.add(new NameSegment(name, false));
        return result;
    }

    private static final class NameSegment
    {
        final String  text;
        final boolean highlight;
        NameSegment(String t, boolean h) { text = t; highlight = h; }
    }

    private static String placePrefix(String displayName, String ownName)
    {
        if (displayName == null || displayName.isEmpty())
            return ""; //$NON-NLS-1$
        String result;
        if (ownName == null || ownName.isEmpty())
            result = displayName;
        else
        {
            int idx = displayName.toLowerCase().lastIndexOf(ownName.toLowerCase());
            result = idx > 0 ? displayName.substring(0, idx) : ""; //$NON-NLS-1$
        }
        return trimPathDisplaySuffix(result);
    }

    private static String trimPathDisplaySuffix(String value)
    {
        if (value == null || value.isEmpty())
            return value != null ? value : ""; //$NON-NLS-1$
        while (value.endsWith(".") || value.endsWith(":")) //$NON-NLS-1$ //$NON-NLS-2$
            value = value.substring(0, value.length() - 1);
        return value;
    }

    private void installItemsContextMenu(Table table)
    {
        Menu menu = new Menu(table);
        table.setMenu(menu);
        MenuItem showNav = new MenuItem(menu, SWT.PUSH);
        showNav.setText("Показать в навигаторе"); //$NON-NLS-1$
        showNav.addListener(SWT.Selection, e -> jumpToSelectedItem());
        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.setText("Копировать ссылку \tCtrl+C"); //$NON-NLS-1$
        copy.addListener(SWT.Selection, e -> copySelectedNavRef());
        MenuItem remove = new MenuItem(menu, SWT.PUSH);
        remove.setText("Удалить из набора \tDel"); //$NON-NLS-1$
        remove.addListener(SWT.Selection, e -> removeSelectedItems());
        menu.addListener(SWT.Show, e ->
        {
            boolean has = !getSelectedItems().isEmpty();
            showNav.setEnabled(has);
            remove.setEnabled(has && selectedSet != null);
        });
    }

    private void copySelectedNavRef()
    {
        ObjectSets.Item item = getSelectedItem();
        if (item == null || item.navRef == null || item.navRef.isEmpty())
            return;
        org.eclipse.swt.dnd.Clipboard clipboard =
            new org.eclipse.swt.dnd.Clipboard(itemsViewer.getControl().getDisplay());
        try
        {
            clipboard.setContents(
                new Object[] { item.navRef },
                new org.eclipse.swt.dnd.Transfer[] { org.eclipse.swt.dnd.TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
        ToastNotification.show("Скопировано", item.navRef, 4000); //$NON-NLS-1$
    }

    private void installItemsDropTarget(Table table)
    {
        DropTarget target = new DropTarget(table, DND.DROP_COPY | DND.DROP_DEFAULT);
        target.setTransfer(new Transfer[] { LocalSelectionTransfer.getTransfer() });
        target.addDropListener(new DropTargetAdapter()
        {
            @Override
            public void dragOver(DropTargetEvent event)
            {
                if (selectedSet == null)
                {
                    event.detail = DND.DROP_NONE;
                    return;
                }
                event.detail = DND.DROP_COPY;
            }

            @Override
            public void drop(DropTargetEvent event)
            {
                Object selObj = LocalSelectionTransfer.getTransfer().getSelection();
                if (selectedSet != null && selObj instanceof IStructuredSelection sel && !sel.isEmpty())
                    addToSetFromDrop(selectedSet, sel);
            }
        });
    }

    private void installSetsDropTarget(Table table)
    {
        DropTarget target = new DropTarget(table, DND.DROP_COPY | DND.DROP_DEFAULT);
        target.setTransfer(new Transfer[] { LocalSelectionTransfer.getTransfer() });
        target.addDropListener(new DropTargetAdapter()
        {
            @Override
            public void dragOver(DropTargetEvent event)
            {
                ObjectSets.SetDef set = setAt(event);
                if (set == null)
                {
                    event.detail = DND.DROP_NONE;
                    return;
                }
                event.detail = DND.DROP_COPY;
                highlightDropRow(table, set);
            }

            @Override
            public void drop(DropTargetEvent event)
            {
                clearDropHighlight();
                ObjectSets.SetDef set = setAt(event);
                Object selObj = LocalSelectionTransfer.getTransfer().getSelection();
                if (set != null && selObj instanceof IStructuredSelection sel && !sel.isEmpty())
                    addToSetFromDrop(set, sel);
            }

            @Override
            public void dragLeave(DropTargetEvent event)
            {
                clearDropHighlight();
            }

            private ObjectSets.SetDef setAt(DropTargetEvent event)
            {
                Point pt = Display.getCurrent().map(null, table, event.x, event.y);
                TableItem item = table.getItem(pt);
                if (item == null || !(item.getData() instanceof ObjectSets.SetDef set))
                    return selectedSet;
                return set;
            }
        });
    }

    private void highlightDropRow(Table table, ObjectSets.SetDef set)
    {
        TableItem[] items = table.getItems();
        for (TableItem item : items)
        {
            if (item.getData() == set)
            {
                dropHighlightItem = item;
                table.setSelection(item);
                return;
            }
        }
    }

    private void clearDropHighlight()
    {
        dropHighlightItem = null;
    }

    private static int columnIndexAt(Table table, int x, TableItem item)
    {
        for (int i = 0; i < table.getColumnCount(); i++)
        {
            if (item.getBounds(i).contains(x, 0))
                return i;
        }
        return -1;
    }

    private int[] readSashWeights()
    {
        IDialogSettings settings = viewSettings();
        String raw = settings.get(KEY_SASH);
        if (raw == null || !raw.contains(",")) //$NON-NLS-1$
            return new int[] { 65, 35 };
        try
        {
            String[] parts = raw.split(","); //$NON-NLS-1$
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        }
        catch (Exception ignored)
        {
            return new int[] { 65, 35 };
        }
    }

    private void saveSashWeights()
    {
        if (sashForm == null || sashForm.isDisposed())
            return;
        int[] weights = sashForm.getWeights();
        if (weights == null || weights.length < 2)
            return;
        viewSettings().put(KEY_SASH, weights[0] + "," + weights[1]); //$NON-NLS-1$
    }

    private static int readColWidth(IMemento memento, IDialogSettings settings, String key,
        int defaultWidth, int minWidth)
    {
        if (memento != null)
        {
            String raw = memento.getString(key);
            if (raw != null && !raw.isBlank())
            {
                try
                {
                    int w = Integer.parseInt(raw.trim());
                    if (w >= minWidth)
                        return w;
                }
                catch (NumberFormatException ignored)
                {
                }
            }
        }
        String raw = settings.get(key);
        if (raw == null || raw.isBlank())
            return defaultWidth;
        try
        {
            int w = Integer.parseInt(raw);
            return w >= minWidth ? w : defaultWidth;
        }
        catch (NumberFormatException ex)
        {
            return defaultWidth;
        }
    }

    private void installItemsColumnPersistence()
    {
        ControlAdapter columnChangeSave = new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                scheduleSaveItemsColumnWidths();
            }

            @Override
            public void controlMoved(ControlEvent e)
            {
                scheduleSaveItemsColumnWidths();
            }
        };
        for (TableColumn column : new TableColumn[] { nameColumn, pathColumn })
        {
            if (column != null)
                column.addControlListener(columnChangeSave);
        }
    }

    private void scheduleSaveItemsColumnWidths()
    {
        if (itemsViewer == null || itemsViewer.getControl().isDisposed())
            return;
        Display display = itemsViewer.getControl().getDisplay();
        if (display == null || display.isDisposed())
            return;
        final int generation = ++itemsColumnSaveGeneration;
        display.timerExec(ITEMS_COLUMN_SAVE_DELAY_MS, () ->
        {
            if (generation == itemsColumnSaveGeneration)
                saveItemsColumnWidths();
        });
    }

    private static int liveColumnWidth(TableColumn column, int minWidth)
    {
        if (column == null || column.isDisposed())
            return 0;
        int width = column.getWidth();
        return width >= minWidth ? width : 0;
    }

    private void saveItemsColumnWidths()
    {
        int nameWidth = readLiveOrCached(nameColumn, MIN_ITEMS_NAME_COL_WIDTH, cachedItemsNameWidth);
        int pathWidth = readLiveOrCached(pathColumn, MIN_ITEMS_PATH_COL_WIDTH, cachedItemsPathWidth);

        if (nameWidth <= 0 && pathWidth <= 0)
            return;

        if (nameWidth > 0)
            cachedItemsNameWidth = nameWidth;
        if (pathWidth > 0)
            cachedItemsPathWidth = pathWidth;

        IDialogSettings settings = viewSettings();
        if (nameWidth > 0)
            settings.put(KEY_ITEMS_COL_NAME_WIDTH, Integer.toString(nameWidth));
        if (pathWidth > 0)
            settings.put(KEY_ITEMS_COL_PATH_WIDTH, Integer.toString(pathWidth));
        if (itemsViewer != null && !itemsViewer.getControl().isDisposed())
            FormTableColumnOrder.save(settings, KEY_ITEMS_COL_ORDER, itemsViewer.getTable());
    }

    private int readLiveOrCached(TableColumn column, int minWidth, int cached)
    {
        int live = liveColumnWidth(column, minWidth);
        return live > 0 ? live : cached;
    }

    private void writeItemsColumnLayoutToMemento(IMemento memento)
    {
        if (memento == null)
            return;
        int nameWidth = readLiveOrCached(nameColumn, MIN_ITEMS_NAME_COL_WIDTH, cachedItemsNameWidth);
        int pathWidth = readLiveOrCached(pathColumn, MIN_ITEMS_PATH_COL_WIDTH, cachedItemsPathWidth);
        if (nameWidth > 0)
            memento.putString(KEY_ITEMS_COL_NAME_WIDTH, Integer.toString(nameWidth));
        if (pathWidth > 0)
            memento.putString(KEY_ITEMS_COL_PATH_WIDTH, Integer.toString(pathWidth));
        if (itemsViewer != null && !itemsViewer.getControl().isDisposed())
        {
            Table table = itemsViewer.getTable();
            if (table.getColumnCount() > 0)
                memento.putString(KEY_ITEMS_COL_ORDER, FormTableColumnOrder.formatOrder(table));
        }
    }

    private static IDialogSettings viewSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static final class ItemIconResolver
    {
        private final Map<String, Image> cache = new HashMap<>();

        void clearCache()
        {
            cache.clear();
        }

        Image imageFor(ObjectSets.Item entry, IWorkbenchPage page, ObjectSets.SetDef set)
        {
            if (entry == null)
                return null;
            String projectName = set != null ? set.projectName : ""; //$NON-NLS-1$
            String cacheKey = entry.key + "|" + projectName; //$NON-NLS-1$
            Image cached = cache.get(cacheKey);
            if (cached != null && !cached.isDisposed())
                return cached;
            if (cached != null)
                cache.remove(cacheKey);
            Image image = resolveImage(entry, page, set);
            if (image != null)
                cache.put(cacheKey, image);
            return image;
        }

        private static Image resolveImage(ObjectSets.Item entry, IWorkbenchPage page, ObjectSets.SetDef set)
        {
            String key = entry.key;
            if (key == null || key.isBlank())
                return null;
            int methodSep = key.indexOf(": "); //$NON-NLS-1$
            if (methodSep >= 0)
            {
                Image moduleIcon = moduleTypeIcon(key.substring(0, methodSep).trim());
                if (moduleIcon != null)
                    return moduleIcon;
            }
            String mdRef = RecentPlacesKeys.mdObjectRefFromKey(key);
            if (mdRef == null || mdRef.isBlank())
                mdRef = entry.displayName;
            if (mdRef == null || mdRef.isBlank())
                return null;
            if (set != null && page != null)
            {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(set.projectName);
                if (project != null && project.exists())
                {
                    EObject eObject = GoToDefinition.resolveEObjectForFullName(mdRef, page, project);
                    if (eObject != null)
                    {
                        Image img = MdUiSharedImages.getMdClassImage(eObject.eClass());
                        if (img != null)
                            return img;
                    }
                }
            }
            Image typeIcon = typeIconFromFirstSegment(mdRef);
            if (typeIcon != null)
                return typeIcon;
            return typeIconFromFirstSegment(entry.displayName);
        }

        private static Image moduleTypeIcon(String modulePath)
        {
            if (modulePath == null || modulePath.isBlank())
                return null;
            int dot = modulePath.lastIndexOf('.');
            if (dot < 0)
                return null;
            String suffix = modulePath.substring(dot + 1);
            if (!MdTypeMapping.isModuleTypeSuffix(suffix))
                return null;
            String en = MdTypeMapping.ruToEnSing(suffix);
            return en != null ? MdUiSharedImages.getTypeImage(en) : null;
        }

        private static Image typeIconFromFirstSegment(String mdRef)
        {
            String typeRu = MdTypeMapping.firstSegment(mdRef);
            if (typeRu == null || typeRu.isBlank())
                return null;
            String en = MdTypeMapping.ruToEnSing(typeRu);
            return en != null ? MdUiSharedImages.getTypeImage(en) : null;
        }
    }
}
