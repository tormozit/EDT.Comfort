package tormozit;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;

/**
 * Диалог «Последние места».
 *
 * <p>Колонки: «Путь», «Имя» (последний фрагмент + фильтр/подсветка), «Проект», «Дата».
 * Layout — {@link TableColumnLayout}; размер окна — {@link #getDialogBoundsSettings()}.
 */
public class RecentPlacesDialog extends Dialog
{
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("dd.MM HH:mm:ss"); //$NON-NLS-1$

    private static final String SETTINGS_SECTION      = "RecentPlacesDialog"; //$NON-NLS-1$
    private static final String KEY_COL_NAME_WIDTH    = "colNameWidth";    //$NON-NLS-1$
    private static final String KEY_COL_PROJECT_WIDTH = "colProjectWidth"; //$NON-NLS-1$
    private static final String KEY_COL_DATE_WIDTH    = "colDateWidth";    //$NON-NLS-1$

    private static final int DEFAULT_NAME_COL_WIDTH    = 120;
    private static final int DEFAULT_PROJECT_COL_WIDTH = 90;
    private static final int DEFAULT_DATE_COL_WIDTH    = 90;
    private static final int MIN_PLACE_COL_WIDTH       = 80;
    private static final int MIN_NAME_COL_WIDTH        = 50;
    private static final int MIN_PROJECT_COL_WIDTH     = 40;
    private static final int MIN_DATE_COL_WIDTH        = 70;

    private Text        filterText;
    private TableViewer listViewer;
    private NameLabelProvider nameLabelProvider;
    private TableColumn nameColumn;
    private TableColumn projectColumn;
    private TableColumn dateColumn;

    private List<RecentPlaces.Entry> allEntries;
    private List<RecentPlaces.Entry> filtered;
    private RecentPlaces.Entry selectedEntry;

    private final Runnable[] pendingFilter = { null };

    public RecentPlacesDialog(Shell parentShell)
    {
        super(parentShell);
        setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    }

    @Override
    protected void configureShell(Shell shell)
    {
        super.configureShell(shell);
        shell.setText(Global.withPluginWindowTitle("Последние места")); //$NON-NLS-1$
    }

    @Override
    protected Point getInitialSize()
    {
        // super читает DIALOG_WIDTH/HEIGHT из getDialogBoundsSettings(); не подменять фиксированным размером.
        IDialogSettings settings = dialogSettings();
        if (settings.get("DIALOG_WIDTH") == null) //$NON-NLS-1$
            return new Point(700, 500);
        return super.getInitialSize();
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings()
    {
        return dialogSettings();
    }

    @Override
    protected int getDialogBoundsStrategy()
    {
        return DIALOG_PERSISTSIZE | DIALOG_PERSISTLOCATION;
    }

    @Override
    protected boolean isResizable()
    {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new org.eclipse.swt.layout.GridLayout(1, false));

        filterText = new Text(area, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        filterText.setMessage("Фильтр по имени метода или объекта (колонка «Имя»)..."); //$NON-NLS-1$
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        IDialogSettings settings = dialogSettings();
        int nameWidth = readColWidth(settings, KEY_COL_NAME_WIDTH,
            DEFAULT_NAME_COL_WIDTH, MIN_NAME_COL_WIDTH);
        int projectWidth = readColWidth(settings, KEY_COL_PROJECT_WIDTH,
            DEFAULT_PROJECT_COL_WIDTH, MIN_PROJECT_COL_WIDTH);
        int dateWidth = readColWidth(settings, KEY_COL_DATE_WIDTH,
            DEFAULT_DATE_COL_WIDTH, MIN_DATE_COL_WIDTH);

        Composite tableHost = new Composite(area, SWT.NONE);
        TableColumnLayout columnLayout = new TableColumnLayout(true);
        tableHost.setLayout(columnLayout);
        tableHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        listViewer = new TableViewer(tableHost,
            SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = listViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        TableViewerColumn colPlace = new TableViewerColumn(listViewer, SWT.NONE);
        TableColumn placeColumn = colPlace.getColumn();
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

        columnLayout.setColumnData(placeColumn,
            new ColumnWeightData(1, MIN_PLACE_COL_WIDTH, true));
        columnLayout.setColumnData(nameColumn,
            new ColumnPixelData(nameWidth, true, true));
        columnLayout.setColumnData(projectColumn,
            new ColumnPixelData(projectWidth, true, true));
        columnLayout.setColumnData(dateColumn,
            new ColumnPixelData(dateWidth, true, true));

        listViewer.setContentProvider(ArrayContentProvider.getInstance());

        allEntries = RecentPlaces.getInstance().getAll();
        filtered   = new ArrayList<>(allEntries);
        listViewer.setInput(filtered);
        selectFirst();

        installFilterListener();
        installKeyListeners(table);
        installDoubleClick(table);
        installContextMenu(table);

        filterText.setFocus();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID,     "Перейти", true);  //$NON-NLS-1$
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed()
    {
        IStructuredSelection sel = listViewer.getStructuredSelection();
        if (!sel.isEmpty())
            selectedEntry = (RecentPlaces.Entry) sel.getFirstElement();
        super.okPressed();
    }

    @Override
    public boolean close()
    {
        saveColumnWidths();
        return super.close();
    }

    // =========================================================================
    // Настройки (окно + колонки)
    // =========================================================================

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static int readColWidth(IDialogSettings settings, String key,
                                    int defaultWidth, int minWidth)
    {
        String raw = settings.get(key);
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

    private void saveColumnWidths()
    {
        if (nameColumn == null || projectColumn == null || dateColumn == null
                || nameColumn.isDisposed() || projectColumn.isDisposed() || dateColumn.isDisposed())
            return;
        IDialogSettings settings = dialogSettings();
        settings.put(KEY_COL_NAME_WIDTH, Integer.toString(nameColumn.getWidth()));
        settings.put(KEY_COL_PROJECT_WIDTH, Integer.toString(projectColumn.getWidth()));
        settings.put(KEY_COL_DATE_WIDTH, Integer.toString(dateColumn.getWidth()));
    }

    /** Префикс пути в колонке «Путь» (всё до последнего фрагмента — {@code ownName}). */
    private static String placePrefix(String displayName, String ownName)
    {
        if (displayName == null || displayName.isEmpty())
            return ""; //$NON-NLS-1$
        if (ownName == null || ownName.isEmpty())
            return displayName;
        int idx = displayName.toLowerCase().lastIndexOf(ownName.toLowerCase());
        return idx > 0 ? displayName.substring(0, idx) : ""; //$NON-NLS-1$
    }

    // =========================================================================
    // Колонка «Имя» — фильтр и подсветка
    // =========================================================================

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
            ranges.add(new int[]{ hr.offset, hr.offset + hr.length });

        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges)
        {
            if (merged.isEmpty() || r[0] >= merged.get(merged.size() - 1)[1])
                merged.add(r);
            else
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], r[1]);
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

    // =========================================================================
    // Фильтрация
    // =========================================================================

    private void installFilterListener()
    {
        filterText.addModifyListener(new ModifyListener()
        {
            @Override
            public void modifyText(ModifyEvent e)
            {
                Display display = filterText.getDisplay();
                if (pendingFilter[0] != null)
                    display.timerExec(-1, pendingFilter[0]);
                pendingFilter[0] = () ->
                {
                    pendingFilter[0] = null;
                    applyFilter(filterText.getText());
                };
                display.timerExec(100, pendingFilter[0]);
            }
        });
    }

    private void applyFilter(String pattern)
    {
        if (listViewer.getControl().isDisposed()) return;
        SmartMatcher m = new SmartMatcher(pattern);
        nameLabelProvider.setMatcher(m);
        filtered = new ArrayList<>();
        for (RecentPlaces.Entry e : allEntries)
            if (m.matches(e.ownName)) filtered.add(e);

        Table table = listViewer.getTable();
        table.setRedraw(false);
        try
        {
            listViewer.setInput(filtered);
            listViewer.refresh();
            selectFirst();
        }
        finally { table.setRedraw(true); }
    }

    private void selectFirst()
    {
        if (!filtered.isEmpty())
            listViewer.setSelection(new StructuredSelection(filtered.get(0)));
    }

    // =========================================================================
    // Клавиатура и меню
    // =========================================================================

    private void installKeyListeners(Table table)
    {
        filterText.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                switch (e.keyCode)
                {
                    case SWT.CR:
                    case SWT.KEYPAD_CR:
                        okPressed();
                        break;
                    default:
                        break;
                }
            }
        });

        FilterFieldListNavigation.installTableNavigation(filterText, table, newIdx ->
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
                    okPressed();
                else if (e.keyCode == 'c' && (e.stateMask & SWT.MOD1) != 0)
                    copySelectedToClipboard();
            }
        });
    }

    private void installDoubleClick(Table table)
    {
        table.addListener(SWT.MouseDoubleClick, event -> okPressed());
    }

    private void installContextMenu(Table table)
    {
        Menu menu = new Menu(table);
        table.setMenu(menu);
        MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
        copyItem.setText("Копировать в буфер обмена\tCtrl+C"); //$NON-NLS-1$
        copyItem.addListener(SWT.Selection, e -> copySelectedToClipboard());
        menu.addListener(SWT.Show, e ->
            copyItem.setEnabled(!listViewer.getStructuredSelection().isEmpty()));
    }

    private void copySelectedToClipboard()
    {
        IStructuredSelection sel = listViewer.getStructuredSelection();
        if (sel.isEmpty()) return;
        RecentPlaces.Entry entry = (RecentPlaces.Entry) sel.getFirstElement();
        String text = entry.navRef;
        Clipboard cb = new Clipboard(listViewer.getControl().getDisplay());
        try
        {
            cb.setContents(
                new Object[]  { text },
                new Transfer[]{ TextTransfer.getInstance() });
        }
        finally { cb.dispose(); }
        ToastNotification.show("Скопировано", text, 4000);
    }

    public RecentPlaces.Entry getSelectedEntry()
    {
        return selectedEntry;
    }
}
