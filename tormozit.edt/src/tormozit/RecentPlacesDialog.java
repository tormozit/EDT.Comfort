package tormozit;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Point;
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
 * <p>Список — одноколоночный {@link TableViewer} с {@link DelegatingStyledCellLabelProvider},
 * как в {@code OpenMdObjectSelectionDialog} / {@code FilteredItemsSelectionDialog}
 * (не {@link org.eclipse.jface.viewers.TableViewerColumn} — на Windows ломает выделение).
 */
public class RecentPlacesDialog extends Dialog
{
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("dd.MM HH:mm:ss"); //$NON-NLS-1$

    private Text        filterText;
    private TableViewer listViewer;
    private TableColumn listColumn;
    private ListLabelProvider listLabelProvider;

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
        shell.setText("Последние места"); //$NON-NLS-1$
    }

    @Override
    protected Point getInitialSize()
    {
        return new Point(700, 500);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new org.eclipse.swt.layout.GridLayout(1, false));

        filterText = new Text(area, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        filterText.setMessage("Фильтр по имени метода или объекта..."); //$NON-NLS-1$
        filterText.setLayoutData(new org.eclipse.swt.layout.GridData(
            SWT.FILL, SWT.CENTER, true, false));

        // Как FilteredItemsSelectionDialog: одна колонка, без заголовка, DelegatingStyledCellLabelProvider на viewer
        listViewer = new TableViewer(area,
            SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = listViewer.getTable();
        table.setHeaderVisible(false);
        table.setLinesVisible(false);
        table.setLayoutData(new org.eclipse.swt.layout.GridData(
            SWT.FILL, SWT.FILL, true, true));

        listColumn = new TableColumn(table, SWT.NONE);
        table.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                if (!table.isDisposed())
                    listColumn.setWidth(Math.max(50, table.getClientArea().width - 4));
            }
        });

        listLabelProvider = new ListLabelProvider();
        listViewer.setContentProvider(ArrayContentProvider.getInstance());
        listViewer.setLabelProvider(new DelegatingStyledCellLabelProvider(listLabelProvider));

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

    // =========================================================================
    // Label provider (тот же контракт, что OpenMdObjectLabelProvider)
    // =========================================================================

    private final class ListLabelProvider extends LabelProvider implements IStyledLabelProvider
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
            RecentPlaces.Entry entry = (RecentPlaces.Entry) element;
            String display = entry.displayName != null ? entry.displayName : ""; //$NON-NLS-1$
            String own     = entry.ownName     != null ? entry.ownName     : ""; //$NON-NLS-1$

            StyledString styled = new StyledString();
            for (Segment seg : buildSegments(display, own, matcher))
            {
                if (seg.highlight)
                    styled.append(seg.text, SmartMatchHighlight.styler());
                else
                    styled.append(seg.text);
            }
            if (entry.visitedAt != null)
            {
                styled.append("  ", StyledString.QUALIFIER_STYLER); //$NON-NLS-1$
                styled.append(entry.visitedAt.format(TIME_FMT), StyledString.QUALIFIER_STYLER);
            }
            return styled;
        }
    }

    // =========================================================================
    // Сегменты подсветки
    // =========================================================================

    private static List<Segment> buildSegments(String displayName, String ownName, SmartMatcher m)
    {
        List<Segment> result = new ArrayList<>();
        if (m.isEmpty || ownName.isEmpty())
        {
            result.add(new Segment(displayName, false));
            return result;
        }

        int ownOffset = findOwnOffset(displayName, ownName);
        List<SmartMatcher.HighlightRange> raw = m.getHighlightRanges(ownName);
        List<int[]> ranges = new ArrayList<>();
        for (SmartMatcher.HighlightRange hr : raw)
        {
            int start = ownOffset + hr.offset;
            int end   = start + hr.length;
            if (ownOffset >= 0 && start >= 0 && end <= displayName.length())
                ranges.add(new int[]{ start, end });
        }

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
                result.add(new Segment(displayName.substring(pos, r[0]), false));
            result.add(new Segment(displayName.substring(r[0], r[1]), true));
            pos = r[1];
        }
        if (pos < displayName.length())
            result.add(new Segment(displayName.substring(pos), false));
        if (result.isEmpty())
            result.add(new Segment(displayName, false));
        return result;
    }

    private static int findOwnOffset(String displayName, String ownName)
    {
        int idx = displayName.toLowerCase().lastIndexOf(ownName.toLowerCase());
        return idx >= 0 ? idx : 0;
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
        listLabelProvider.setMatcher(m);
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
