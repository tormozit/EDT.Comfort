package tormozit;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;

import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;

/**
 * Панель «Последние места» — список недавно посещённых методов и объектов МД.
 */
public final class RecentPlacesView extends ViewPart
{
    public static final String VIEW_ID = "tormozit.RecentPlacesView"; //$NON-NLS-1$

    public static final String BINDING_CONTEXT_ID = "tormozit.recentPlaces.context"; //$NON-NLS-1$

    public static final String SHOW_IN_NAVIGATOR_COMMAND_ID =
        "tormozit.recentPlaces.showInNavigator"; //$NON-NLS-1$

    private static final String TABLE_MENU_KEY = "tormozit.recentPlacesTableMenu"; //$NON-NLS-1$

    private static final String ITEM_TEXT_SHOW_IN_NAVIGATOR =
        "Показать в навигаторе \tCTRL+T"; //$NON-NLS-1$

    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("dd.MM HH:mm:ss"); //$NON-NLS-1$

    private static final String SETTINGS_SECTION   = "RecentPlacesDialog"; //$NON-NLS-1$
    private static final String KEY_COL_PLACE_WIDTH   = "colPlaceWidth";   //$NON-NLS-1$
    private static final String KEY_COL_NAME_WIDTH    = "colNameWidth";    //$NON-NLS-1$
    private static final String KEY_COL_PROJECT_WIDTH = "colProjectWidth"; //$NON-NLS-1$
    private static final String KEY_COL_DATE_WIDTH    = "colDateWidth";    //$NON-NLS-1$
    private static final String KEY_COL_ORDER         = "columnOrder";     //$NON-NLS-1$
    private static final String KEY_FILTER_BY_PROJECT = "filterByProject"; //$NON-NLS-1$

    private static final int DEFAULT_PLACE_COL_WIDTH   = 280;
    private static final int DEFAULT_NAME_COL_WIDTH    = 120;
    private static final int DEFAULT_PROJECT_COL_WIDTH = 90;
    private static final int DEFAULT_DATE_COL_WIDTH    = 90;
    private static final int MIN_PLACE_COL_WIDTH       = 80;
    private static final int MIN_NAME_COL_WIDTH        = 50;
    private static final int MIN_PROJECT_COL_WIDTH     = 40;
    private static final int MIN_DATE_COL_WIDTH        = 70;
    private static final int ICON_COL_WIDTH            = 24;

    private static final int COLUMN_SAVE_DELAY_MS = 300;

    private static RecentPlacesView activeInstance;

    private IMemento workbenchState;

    private Composite columnHost;

    private FilterInputBox filterInput;
    private Button filterByProjectCheckbox;
    private TableViewer listViewer;
    private NameLabelProvider nameLabelProvider;
    private EntryIconResolver entryIconResolver;
    private TableColumn iconColumn;
    private TableColumn placeColumn;
    private TableColumn nameColumn;
    private TableColumn projectColumn;
    private TableColumn dateColumn;
    private FormTableInteraction tableInteraction;

    private List<RecentPlaces.Entry> allEntries = new ArrayList<>();
    private List<RecentPlaces.Entry> filtered = new ArrayList<>();

    private IContextActivation keyContextActivation;

    private ActiveProjectTracker.ContextProjectListener contextProjectListener;

    private int columnSaveGeneration;

    private int cachedPlaceWidth;
    private int cachedNameWidth;
    private int cachedProjectWidth;
    private int cachedDateWidth;

    private final Runnable storeChangeListener = this::refreshFromStoreOnUiThread;

    @Override
    public void init(IViewSite site, IMemento memento) throws PartInitException
    {
        super.init(site, memento);
        workbenchState = memento;
    }

    @Override
    public void saveState(IMemento memento)
    {
        writeColumnLayoutToMemento(memento);
        writeFilterByProjectToMemento(memento);
        super.saveState(memento);
    }

    public static RecentPlacesView getActiveInstance()
    {
        return activeInstance;
    }

    @Override
    public void createPartControl(Composite parent)
    {
        activeInstance = this;
        parent.setLayout(new FillLayout());

        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        IDialogSettings settings = viewSettings();
        createFilterRow(root, settings);
        installContextProjectListener();
        int placeWidth = readColWidth(workbenchState, settings, KEY_COL_PLACE_WIDTH,
            DEFAULT_PLACE_COL_WIDTH, MIN_PLACE_COL_WIDTH);
        int nameWidth = readColWidth(workbenchState, settings, KEY_COL_NAME_WIDTH,
            DEFAULT_NAME_COL_WIDTH, MIN_NAME_COL_WIDTH);
        int projectWidth = readColWidth(workbenchState, settings, KEY_COL_PROJECT_WIDTH,
            DEFAULT_PROJECT_COL_WIDTH, MIN_PROJECT_COL_WIDTH);
        int dateWidth = readColWidth(workbenchState, settings, KEY_COL_DATE_WIDTH,
            DEFAULT_DATE_COL_WIDTH, MIN_DATE_COL_WIDTH);
        cachedPlaceWidth = placeWidth;
        cachedNameWidth = nameWidth;
        cachedProjectWidth = projectWidth;
        cachedDateWidth = dateWidth;

        Composite tableStack = new Composite(root, SWT.NONE);
        tableStack.setLayout(null);
        tableStack.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        columnHost = new Composite(tableStack, SWT.NONE);
        TableColumnLayout columnLayout = new TableColumnLayout(true);
        columnHost.setLayout(columnLayout);

        listViewer = new TableViewer(columnHost,
            SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = listViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        entryIconResolver = new EntryIconResolver();

        TableViewerColumn colIcon = new TableViewerColumn(listViewer, SWT.NONE);
        iconColumn = colIcon.getColumn();
        iconColumn.setText(""); //$NON-NLS-1$
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
                if (!(element instanceof RecentPlaces.Entry))
                    return null;
                return entryIconResolver.imageFor(
                    (RecentPlaces.Entry) element, getSite().getPage());
            }
        });

        TableViewerColumn colPlace = new TableViewerColumn(listViewer, SWT.NONE);
        placeColumn = colPlace.getColumn();
        placeColumn.setText("Путь"); //$NON-NLS-1$
        colPlace.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (!(element instanceof RecentPlaces.Entry))
                    return ""; //$NON-NLS-1$
                RecentPlaces.Entry entry = (RecentPlaces.Entry) element;
                return placePrefix(entry.displayName, entry.ownName);
            }
        });

        TableViewerColumn colName = new TableViewerColumn(listViewer, SWT.NONE);
        nameColumn = colName.getColumn();
        nameColumn.setText("Имя"); //$NON-NLS-1$
        nameLabelProvider = new NameLabelProvider();
        colName.setLabelProvider(new DelegatingStyledCellLabelProvider(nameLabelProvider));

        TableViewerColumn colProject = new TableViewerColumn(listViewer, SWT.NONE);
        projectColumn = colProject.getColumn();
        projectColumn.setText("Проект"); //$NON-NLS-1$
        colProject.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (!(element instanceof RecentPlaces.Entry))
                    return ""; //$NON-NLS-1$
                String name = ((RecentPlaces.Entry) element).projectName;
                return name != null && !name.isBlank() ? name : "\u2014"; //$NON-NLS-1$
            }
        });

        TableViewerColumn colDate = new TableViewerColumn(listViewer, SWT.NONE);
        dateColumn = colDate.getColumn();
        dateColumn.setText("Дата"); //$NON-NLS-1$
        colDate.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                if (!(element instanceof RecentPlaces.Entry))
                    return ""; //$NON-NLS-1$
                RecentPlaces.Entry entry = (RecentPlaces.Entry) element;
                return entry.visitedAt != null ? entry.visitedAt.format(TIME_FMT) : ""; //$NON-NLS-1$
            }
        });

        columnLayout.setColumnData(iconColumn,
            new ColumnPixelData(ICON_COL_WIDTH, false, false));
        columnLayout.setColumnData(placeColumn,
            new ColumnPixelData(placeWidth, true, true));
        columnLayout.setColumnData(nameColumn,
            new ColumnPixelData(nameWidth, true, true));
        columnLayout.setColumnData(projectColumn,
            new ColumnPixelData(projectWidth, true, true));
        columnLayout.setColumnData(dateColumn,
            new ColumnPixelData(dateWidth, true, true));

        FormTableColumnOrder.load(settings, KEY_COL_ORDER, table);
        FormTableColumnOrder.load(workbenchState, KEY_COL_ORDER, table);

        listViewer.setContentProvider(ArrayContentProvider.getInstance());

        tableInteraction = new FormTableInteraction(table, this::cellText);
        tableInteraction.setSelectionSync(item ->
            listViewer.setSelection(new StructuredSelection(item.getData())));
        tableInteraction.install();

        installColumnWidthPersistence();
        installKeyListeners(table);
        installDoubleClick(table);
        installTableContextMenu(table);

        RecentPlaces.getInstance().addChangeListener(storeChangeListener);
        refreshFromStore();
    }

    @Override
    public void setFocus()
    {
        activateKeyContext();
        refreshFromStore();
        if (filterInput != null && !filterInput.isDisposed())
            filterInput.setFocus();
    }

    @Override
    public void dispose()
    {
        saveColumnWidths();
        saveFilterByProject();
        IWorkbenchPage page = getSite().getPage();
        if (page != null && contextProjectListener != null)
            ActiveProjectTracker.removeListener(page, contextProjectListener);
        contextProjectListener = null;
        RecentPlaces.getInstance().removeChangeListener(storeChangeListener);
        deactivateKeyContext();
        if (activeInstance == this)
            activeInstance = null;
        super.dispose();
    }

    public RecentPlaces.Entry getSelectedEntry()
    {
        if (listViewer == null || listViewer.getControl().isDisposed())
            return null;
        IStructuredSelection sel = listViewer.getStructuredSelection();
        if (sel.isEmpty() || !(sel.getFirstElement() instanceof RecentPlaces.Entry))
            return null;
        return (RecentPlaces.Entry) sel.getFirstElement();
    }

    void jumpToSelected()
    {
        RecentPlaces.Entry entry = getSelectedEntry();
        if (entry == null)
            return;
        RecentPlacesHandler.jumpToEntry(entry, getSite().getShell(), getSite().getPage());
    }

    private void refreshFromStoreOnUiThread()
    {
        Display display = listViewer != null ? listViewer.getControl().getDisplay() : null;
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            refreshFromStore();
        else
            display.asyncExec(this::refreshFromStore);
    }

    private void refreshFromStore()
    {
        if (listViewer == null || listViewer.getControl().isDisposed())
            return;
        if (entryIconResolver != null)
            entryIconResolver.clearCache();
        allEntries = RecentPlaces.getInstance().getAll();
        applyFilterFromField();
    }

    private void activateKeyContext()
    {
        if (keyContextActivation != null)
            return;
        IContextService contextService = getSite().getService(IContextService.class);
        if (contextService == null)
            return;
        keyContextActivation = contextService.activateContext(BINDING_CONTEXT_ID);
    }

    private void deactivateKeyContext()
    {
        if (keyContextActivation == null)
            return;
        IContextService contextService = getSite().getService(IContextService.class);
        if (contextService != null)
            contextService.deactivateContext(keyContextActivation);
        keyContextActivation = null;
    }

    private static IDialogSettings viewSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private void createFilterRow(Composite parent, IDialogSettings settings)
    {
        Composite row = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(3, false);
        layout.marginHeight = 0;
        layout.marginWidth = 0;
        layout.marginTop = 3;
        layout.marginBottom = 3;
        layout.verticalSpacing = 0;
        layout.horizontalSpacing = 4;
        row.setLayout(layout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        filterInput = FilterInputBox.forRecentPlaces(row, this::applyFilterFromField);

        filterByProjectCheckbox = new Button(row, SWT.CHECK);
        filterByProjectCheckbox.setText("По проекту"); //$NON-NLS-1$
        filterByProjectCheckbox.setToolTipText("Фильтровать по активному проекту"); //$NON-NLS-1$
        filterByProjectCheckbox.setSelection(readFilterByProject(workbenchState, settings));
        filterByProjectCheckbox.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                saveFilterByProject();
                applyFilterFromField();
            }
        });

        Label filler = new Label(row, SWT.NONE);
        filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void installContextProjectListener()
    {
        IWorkbenchPage page = getSite().getPage();
        if (page == null)
            return;
        ActiveProjectTracker.bootstrapPage(page);
        contextProjectListener = (p, previous, current) -> refreshProjectFilterIfEnabled();
        ActiveProjectTracker.addListener(page, contextProjectListener);
    }

    private void refreshProjectFilterIfEnabled()
    {
        if (filterByProjectCheckbox == null || filterByProjectCheckbox.isDisposed())
            return;
        if (!filterByProjectCheckbox.getSelection())
            return;
        applyFilterFromField();
    }

    private String activeProjectNameForFilter()
    {
        IWorkbenchPage page = getSite().getPage();
        if (page == null)
            return null;
        IProject p = ActiveProjectTracker.peek(page);
        if (p == null)
            p = ActiveProjectTracker.resolveContextProject(page);
        return p != null ? p.getName() : null;
    }

    private static boolean readFilterByProject(IMemento memento, IDialogSettings settings)
    {
        if (memento != null)
        {
            String raw = memento.getString(KEY_FILTER_BY_PROJECT);
            if (raw != null && !raw.isEmpty())
                return Boolean.parseBoolean(raw);
        }
        if (settings != null)
        {
            String raw = settings.get(KEY_FILTER_BY_PROJECT);
            if (raw != null && !raw.isEmpty())
                return Boolean.parseBoolean(raw);
        }
        return false;
    }

    private void saveFilterByProject()
    {
        if (filterByProjectCheckbox == null || filterByProjectCheckbox.isDisposed())
            return;
        String value = Boolean.toString(filterByProjectCheckbox.getSelection());
        viewSettings().put(KEY_FILTER_BY_PROJECT, value);
    }

    private void writeFilterByProjectToMemento(IMemento memento)
    {
        if (memento == null || filterByProjectCheckbox == null || filterByProjectCheckbox.isDisposed())
            return;
        memento.putString(KEY_FILTER_BY_PROJECT,
            Boolean.toString(filterByProjectCheckbox.getSelection()));
    }

    private static int readColWidth(IMemento memento, IDialogSettings settings, String key,
                                    int defaultWidth, int minWidth)
    {
        if (memento != null)
        {
            String raw = memento.getString(key);
            int w = parseColWidth(raw, minWidth);
            if (w > 0)
                return w;
        }
        return readColWidth(settings, key, defaultWidth, minWidth);
    }

    private static int readColWidth(IDialogSettings settings, String key,
                                    int defaultWidth, int minWidth)
    {
        return parseColWidth(settings != null ? settings.get(key) : null, minWidth, defaultWidth);
    }

    private static int parseColWidth(String raw, int minWidth)
    {
        return parseColWidth(raw, minWidth, 0);
    }

    private static int parseColWidth(String raw, int minWidth, int defaultWidth)
    {
        if (raw == null || raw.isEmpty())
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

    private void installColumnWidthPersistence()
    {
        ControlAdapter resizeSave = new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                scheduleSaveColumnWidths();
            }
        };
        for (TableColumn column : new TableColumn[] { placeColumn, nameColumn, projectColumn, dateColumn })
        {
            if (column != null)
                column.addControlListener(resizeSave);
        }
    }

    private void scheduleSaveColumnWidths()
    {
        if (listViewer == null || listViewer.getControl().isDisposed())
            return;
        Display display = listViewer.getControl().getDisplay();
        if (display == null || display.isDisposed())
            return;
        final int generation = ++columnSaveGeneration;
        display.timerExec(COLUMN_SAVE_DELAY_MS, () ->
        {
            if (generation == columnSaveGeneration)
                saveColumnWidths();
        });
    }

    private int liveColumnWidth(TableColumn column, int minWidth)
    {
        if (column == null || column.isDisposed())
            return 0;
        int width = column.getWidth();
        return width >= minWidth ? width : 0;
    }

    private void saveColumnWidths()
    {
        int placeWidth = readLiveOrCached(placeColumn, MIN_PLACE_COL_WIDTH, cachedPlaceWidth);
        int nameWidth = readLiveOrCached(nameColumn, MIN_NAME_COL_WIDTH, cachedNameWidth);
        int projectWidth = readLiveOrCached(projectColumn, MIN_PROJECT_COL_WIDTH, cachedProjectWidth);
        int dateWidth = readLiveOrCached(dateColumn, MIN_DATE_COL_WIDTH, cachedDateWidth);

        if (placeWidth <= 0 && nameWidth <= 0 && projectWidth <= 0 && dateWidth <= 0)
            return;

        if (placeWidth > 0)
            cachedPlaceWidth = placeWidth;
        if (nameWidth > 0)
            cachedNameWidth = nameWidth;
        if (projectWidth > 0)
            cachedProjectWidth = projectWidth;
        if (dateWidth > 0)
            cachedDateWidth = dateWidth;

        IDialogSettings settings = viewSettings();
        if (placeWidth > 0)
            settings.put(KEY_COL_PLACE_WIDTH, Integer.toString(placeWidth));
        if (nameWidth > 0)
            settings.put(KEY_COL_NAME_WIDTH, Integer.toString(nameWidth));
        if (projectWidth > 0)
            settings.put(KEY_COL_PROJECT_WIDTH, Integer.toString(projectWidth));
        if (dateWidth > 0)
            settings.put(KEY_COL_DATE_WIDTH, Integer.toString(dateWidth));
        if (listViewer != null && !listViewer.getControl().isDisposed())
            FormTableColumnOrder.save(settings, KEY_COL_ORDER, listViewer.getTable());
    }

    private int readLiveOrCached(TableColumn column, int minWidth, int cached)
    {
        int live = liveColumnWidth(column, minWidth);
        return live > 0 ? live : cached;
    }

    private void writeColumnLayoutToMemento(IMemento memento)
    {
        if (memento == null)
            return;
        int placeWidth = readLiveOrCached(placeColumn, MIN_PLACE_COL_WIDTH, cachedPlaceWidth);
        int nameWidth = readLiveOrCached(nameColumn, MIN_NAME_COL_WIDTH, cachedNameWidth);
        int projectWidth = readLiveOrCached(projectColumn, MIN_PROJECT_COL_WIDTH, cachedProjectWidth);
        int dateWidth = readLiveOrCached(dateColumn, MIN_DATE_COL_WIDTH, cachedDateWidth);
        if (placeWidth > 0)
            memento.putString(KEY_COL_PLACE_WIDTH, Integer.toString(placeWidth));
        if (nameWidth > 0)
            memento.putString(KEY_COL_NAME_WIDTH, Integer.toString(nameWidth));
        if (projectWidth > 0)
            memento.putString(KEY_COL_PROJECT_WIDTH, Integer.toString(projectWidth));
        if (dateWidth > 0)
            memento.putString(KEY_COL_DATE_WIDTH, Integer.toString(dateWidth));
        if (listViewer != null && !listViewer.getControl().isDisposed())
        {
            Table table = listViewer.getTable();
            if (table.getColumnCount() > 0)
                memento.putString(KEY_COL_ORDER, FormTableColumnOrder.formatOrder(table));
        }
    }

    private static String placePrefix(String displayName, String ownName)
    {
        if (displayName == null || displayName.isEmpty())
            return ""; //$NON-NLS-1$
        if (ownName == null || ownName.isEmpty())
            return displayName;
        int idx = displayName.toLowerCase().lastIndexOf(ownName.toLowerCase());
        return idx > 0 ? displayName.substring(0, idx) : ""; //$NON-NLS-1$
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
            if (!(element instanceof RecentPlaces.Entry))
                return new StyledString();
            String name = ((RecentPlaces.Entry) element).ownName;
            if (name == null)
                name = ""; //$NON-NLS-1$

            StyledString styled = new StyledString();
            for (Segment seg : buildNameSegments(name, matcher))
            {
                if (seg.highlight)
                    styled.append(seg.text, SmartMatchHighlight.styler());
                else
                    styled.append(seg.text);
            }
            return styled;
        }
    }

    private static List<Segment> buildNameSegments(String name, SmartMatcher m)
    {
        List<Segment> result = new ArrayList<>();
        if (m.isEmpty || name.isEmpty())
        {
            result.add(new Segment(name, false));
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
                result.add(new Segment(name.substring(pos, r[0]), false));
            if (r[0] < name.length())
                result.add(new Segment(name.substring(r[0], Math.min(r[1], name.length())), true));
            pos = r[1];
        }
        if (pos < name.length())
            result.add(new Segment(name.substring(pos), false));
        if (result.isEmpty())
            result.add(new Segment(name, false));
        return result;
    }

    private static final class Segment
    {
        final String  text;
        final boolean highlight;
        Segment(String t, boolean h) { text = t; highlight = h; }
    }

    private void applyFilterFromField()
    {
        if (filterInput == null || filterInput.isDisposed())
            return;
        applyFilter(filterInput.getText());
    }

    private void applyFilter(String pattern)
    {
        if (listViewer == null || listViewer.getControl().isDisposed())
            return;
        RecentPlaces.Entry selected = getSelectedEntry();
        String selectedKey = selected != null ? selected.key : null;
        int savedActiveColumn = tableInteraction != null ? tableInteraction.activeColumn() : -1;
        SmartMatcher m = new SmartMatcher(pattern);
        nameLabelProvider.setMatcher(m);
        boolean filterByProject = filterByProjectCheckbox != null
            && filterByProjectCheckbox.getSelection();
        String activeProjectName = null;
        if (filterByProject)
            activeProjectName = activeProjectNameForFilter();
        filtered = new ArrayList<>();
        for (RecentPlaces.Entry e : allEntries)
        {
            if (!m.matches(e.ownName))
                continue;
            if (filterByProject)
            {
                if (activeProjectName == null || e.projectName == null || e.projectName.isBlank()
                    || !activeProjectName.equals(e.projectName))
                    continue;
            }
            filtered.add(e);
        }

        Table table = listViewer.getTable();
        table.setRedraw(false);
        try
        {
            listViewer.setInput(filtered);
            listViewer.refresh();
            if (!restoreSelectionByKey(selectedKey, savedActiveColumn))
                selectFirst();
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    private boolean restoreSelectionByKey(String key, int savedActiveColumn)
    {
        if (key == null || key.isBlank() || filtered.isEmpty())
            return false;
        RecentPlaces.Entry entry = null;
        int index = -1;
        for (int i = 0; i < filtered.size(); i++)
        {
            if (key.equals(filtered.get(i).key))
            {
                entry = filtered.get(i);
                index = i;
                break;
            }
        }
        if (entry == null)
            return false;
        listViewer.setSelection(new StructuredSelection(entry));
        if (tableInteraction == null)
            return true;
        Table table = listViewer.getTable();
        if (table.isDisposed() || index < 0 || index >= table.getItemCount())
            return true;
        TableItem item = table.getItem(index);
        if (item == null)
            return true;
        table.showItem(item);
        int col = savedActiveColumn;
        if (col < 0)
            col = visualColumnIndex(table, placeColumn);
        tableInteraction.selectCell(item, col);
        return true;
    }

    private void selectFirst()
    {
        if (filtered.isEmpty())
            return;
        listViewer.setSelection(new StructuredSelection(filtered.get(0)));
        if (tableInteraction == null)
            return;
        Table table = listViewer.getTable();
        if (table.isDisposed())
            return;
        TableItem first = table.getItem(0);
        if (first != null)
            tableInteraction.selectCell(first, visualColumnIndex(table, placeColumn));
    }

    private static int visualColumnIndex(Table table, TableColumn column)
    {
        if (table == null || column == null || column.isDisposed())
            return 0;
        TableColumn[] cols = table.getColumns();
        for (int i = 0; i < cols.length; i++)
        {
            if (cols[i] == column)
                return i;
        }
        return 0;
    }

    private String cellText(TableItem item, int column)
    {
        if (!(item.getData() instanceof RecentPlaces.Entry entry))
            return ""; //$NON-NLS-1$
        Table table = item.getParent();
        if (column < 0 || column >= table.getColumnCount())
            return ""; //$NON-NLS-1$
        TableColumn col = table.getColumn(column);
        if (col == iconColumn)
            return ""; //$NON-NLS-1$
        if (col == placeColumn)
            return placePrefix(entry.displayName, entry.ownName);
        if (col == nameColumn)
            return entry.ownName != null ? entry.ownName : ""; //$NON-NLS-1$
        if (col == projectColumn)
        {
            String name = entry.projectName;
            return name != null && !name.isBlank() ? name : "\u2014"; //$NON-NLS-1$
        }
        if (col == dateColumn)
            return entry.visitedAt != null ? entry.visitedAt.format(TIME_FMT) : ""; //$NON-NLS-1$
        return ""; //$NON-NLS-1$
    }

    private void installKeyListeners(Table table)
    {
        Control filterKeys = filterInput.inputControl();
        if (filterKeys == null)
            filterKeys = filterInput.widget();
        filterKeys.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)
                    jumpToSelected();
            }
        });

        FilterInputBoxListNavigation.installTableNavigation(filterKeys, table, newIdx ->
        {
            if (newIdx < filtered.size())
                listViewer.setSelection(new StructuredSelection(filtered.get(newIdx)));
        });

        table.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)
                    jumpToSelected();
            }
        });
    }

    private void installDoubleClick(Table table)
    {
        table.addListener(SWT.MouseDoubleClick, event -> jumpToSelected());
    }

    private void installTableContextMenu(Table table)
    {
        table.addListener(SWT.MenuDetect, e ->
        {
            Display display = table.getDisplay();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> ensureTableContextMenu(table));
        });
    }

    private void ensureTableContextMenu(Table table)
    {
        Menu menu = table.getMenu();
        if (menu == null || menu.isDisposed())
            return;
        if (Boolean.TRUE.equals(menu.getData(TABLE_MENU_KEY)))
            return;
        menu.setData(TABLE_MENU_KEY, Boolean.TRUE);

        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem showNav = new MenuItem(menu, SWT.PUSH);
        showNav.setText(ITEM_TEXT_SHOW_IN_NAVIGATOR);
        showNav.setToolTipText("Показать объект метаданных в дереве навигатора"); //$NON-NLS-1$
        showNav.addListener(SWT.Selection, ev -> executeShowInNavigatorCommand());

        MenuItem copyRef = new MenuItem(menu, SWT.PUSH);
        copyRef.setText("Копировать ссылку в буфер обмена"); //$NON-NLS-1$
        copyRef.addListener(SWT.Selection, ev -> copyNavRefToClipboard());

        menu.addListener(SWT.Show, ev ->
        {
            boolean hasSelection = !listViewer.getStructuredSelection().isEmpty();
            showNav.setEnabled(hasSelection);
            copyRef.setEnabled(hasSelection);
        });
    }

    private void executeShowInNavigatorCommand()
    {
        IHandlerService handlerService = getSite().getService(IHandlerService.class);
        if (handlerService == null)
            return;
        try
        {
            handlerService.executeCommand(SHOW_IN_NAVIGATOR_COMMAND_ID, null);
        }
        catch (Exception ex)
        {
            Global.log("RecentPlacesView: showInNavigator: " + ex); //$NON-NLS-1$
        }
    }

    private void copyNavRefToClipboard()
    {
        RecentPlaces.Entry entry = getSelectedEntry();
        if (entry == null)
            return;
        String text = entry.navRef;
        if (text == null || text.isEmpty())
            return;
        Clipboard clipboard = new Clipboard(listViewer.getControl().getDisplay());
        try
        {
            clipboard.setContents(
                new Object[] { text },
                new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
        ToastNotification.show("Скопировано", text, 4000); //$NON-NLS-1$
    }

    /**
     * Иконки типа объекта/модуля метаданных для колонки списка.
     */
    private static final class EntryIconResolver
    {
        private final Map<String, Image> cache = new HashMap<>();

        void clearCache()
        {
            cache.clear();
        }

        Image imageFor(RecentPlaces.Entry entry, IWorkbenchPage page)
        {
            if (entry == null)
                return null;
            String cacheKey = cacheKey(entry);
            Image cached = cache.get(cacheKey);
            if (cached != null)
                return cached;

            Image image = resolveImage(entry, page);
            if (image != null)
                cache.put(cacheKey, image);
            return image;
        }

        private static String cacheKey(RecentPlaces.Entry entry)
        {
            String project = entry.projectName != null ? entry.projectName : ""; //$NON-NLS-1$
            return entry.key + "|" + project; //$NON-NLS-1$
        }

        private static Image resolveImage(RecentPlaces.Entry entry, IWorkbenchPage page)
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

            String mdRef = RecentPlacesKeys.mdObjectRef(entry);
            if (mdRef == null || mdRef.isBlank())
                return null;

            IProject project = RecentPlacesHandler.resolveProject(entry, page);
            if (project != null)
            {
                EObject eObject = GoToDefinition.resolveEObjectForFullName(mdRef, page, project);
                if (eObject != null)
                {
                    Image img = MdUiSharedImages.getMdClassImage(eObject.eClass());
                    if (img != null)
                        return img;
                }
            }

            return typeIconFromFirstSegment(mdRef);
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
