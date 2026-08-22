package tormozit;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.part.IPageSite;

import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;

public class CompareSearchResultPage implements ISearchResultPage
{
    /**
     * Обходной путь (issue #165, платформенная гонка PageBook/CTabFolder — см. описание у вызова):
     * повторно скрывает таблицу, если страница всё ещё деактивирована ({@code this.searchResult
     * == null}) — не трогает состояние, если за это время пришёл новый реальный поиск.
     */
    private void scheduleForceHide(Control control, int delayMs)
    {
        Display display = control.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(delayMs, () -> {
            if (control.isDisposed() || searchResult != null)
                return;
            if (control.getVisible())
                control.setVisible(false);
        });
    }

    private String id;
    private ISearchResultViewPart viewPart;
    private IPageSite pageSite;
    private Composite tableStack;
    private Table table;
    private TableViewer tableViewer;
    private FormTableInteraction tableInteraction;
    private TableColumn checkColumn;

    private CompareSearchResult searchResult;
    private String queryText;

    private IMemento restoredState;

    private CheckboxTreeViewer hookedTreeViewer;
    private ICheckStateListener treeCheckListener;
    private boolean applyingCheckFromResults;
    /** Только факт «были изменения флажков», без списка узлов. */
    private final AtomicBoolean treeCheckDirty = new AtomicBoolean(false);
    private Job treeCheckSyncJob;

    private Image checkImageUnchecked;
    private Image checkImageChecked;
    private Image checkImageGrayed;

    private static final int TREE_CHECK_SYNC_DELAY_MS = 1000;
    private static final String KEY_CHECKED = "tormozit.compareSearchChecked"; //$NON-NLS-1$
    private static final String KEY_GRAYED = "tormozit.compareSearchGrayed"; //$NON-NLS-1$

    private static final String SETTINGS_SECTION = "CompareSearchResults"; //$NON-NLS-1$
    /** Второстепенные данные (порядок/ширина колонок) — в {@link IDialogSettings},
     * сохраняются при закрытии панели и явно перед {@link #setInput} (повторный поиск). */
    private static final String KEY_COL_ORDER = "columnOrder"; //$NON-NLS-1$
    private static final String KEY_COL_FILL_MODE = "colFillMode"; //$NON-NLS-1$
    private static final String KEY_COL_PATH_WIDTH = "colPathWidth"; //$NON-NLS-1$
    private static final String KEY_COL_PROPERTY_WIDTH = "colPropertyWidth"; //$NON-NLS-1$
    private static final String KEY_COL_TEXT_WIDTH = "colTextWidth"; //$NON-NLS-1$
    private static final String KEY_COL_STATUS_WIDTH = "colStatusWidth"; //$NON-NLS-1$
    private static final String KEY_COL_SIDE_WIDTH = "colSideWidth"; //$NON-NLS-1$
    private static final int CHECK_COLUMN_WIDTH = 22;
    @Override
    public void init(IPageSite site)
    {
        this.pageSite = site;
    }

    @Override
    public IPageSite getSite()
    {
        return pageSite;
    }

    @Override
    public void createControl(Composite parent)
    {
        // FormTableInteraction (accent заголовка) требует родителя Table без layout
        // либо TableColumnLayout — эталон tableStack в ConfigSearchResultsHook / RecentPlacesView.
        tableStack = new Composite(parent, SWT.NONE);
        tableStack.setLayout(null);

        // Не SWT.CHECK: EraseItem + StyledCellLabelProvider включают owner-draw,
        // и нативные флажки Win32 не рисуются. Колонка — иконки + клик.
        table = new Table(tableStack,
            SWT.MULTI | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        table.setHeaderVisible(true);
        ThemeAwareColors.applyGridLines(table);
        tableStack.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                if (!table.isDisposed())
                    table.setBounds(tableStack.getClientArea());
            }
        });

        tableViewer = new TableViewer(table);
        tableViewer.setContentProvider(ArrayContentProvider.getInstance());

        ensureCheckImages();
        IDialogSettings columnSettings = dialogSettings();
        addCheckColumn();
        addPathColumn(columnSettings);
        addPropertyColumn(columnSettings);
        addTextColumn(columnSettings);
        addStatusColumn(columnSettings);
        addColumnSideColumn(columnSettings);
        FormTableColumnState.loadOrder(columnSettings, KEY_COL_ORDER, table);
        applyRestoredState();

        table.addListener(SWT.MouseDown, event ->
        {
            if (event.button != 1)
                return;
            TableItem item = table.getItem(new Point(event.x, event.y));
            if (item == null)
                return;
            Rectangle checkBounds = item.getBounds(0);
            if (!checkBounds.contains(event.x, event.y))
                return;
            handleResultCheckToggle(item);
        });

        table.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseDoubleClick(MouseEvent e)
            {
                TableItem[] selection = table.getSelection();
                if (selection == null || selection.length == 0)
                    return;
                Object data = selection[0].getData();
                if (data instanceof CompareSearchMatch match)
                    navigateToNode(match);
            }
        });

        // Фон статуса сравнения — только вне selection; выделение/ячейка — FormTableInteraction.
        table.addListener(SWT.EraseItem, event ->
        {
            event.detail &= ~SWT.HOT;
            if (!(event.item instanceof TableItem item))
                return;
            if (isTableItemSelected(item))
                return;
            if (!(item.getData() instanceof CompareSearchMatch m))
                return;
            Color bg = getRowBackground(m);
            if (bg != null)
            {
                event.gc.setBackground(bg);
                event.gc.fillRectangle(event.x, event.y, event.width, event.height);
                event.detail &= ~SWT.BACKGROUND;
            }
        });

        table.addListener(SWT.PaintItem, event ->
        {
            if (event.index != 0 || !(event.item instanceof TableItem item))
                return;
            if (!(item.getData() instanceof CompareSearchMatch match) || !match.isCheckable())
                return;
            Image img = checkImageForTableItem(item, match);
            if (img == null || img.isDisposed())
                return;
            Rectangle imgBounds = img.getBounds();
            int x = event.x + Math.max(0, (event.width - imgBounds.width) / 2);
            int y = event.y + Math.max(0, (event.height - imgBounds.height) / 2);
            event.gc.drawImage(img, x, y);
        });

        // Дерево → результаты: в каскаде только AtomicBoolean; синхронизация — Job.DECORATE.
        table.addListener(SWT.FocusIn, event -> syncChecksFromTree());

        installContextMenu();

        tableInteraction = new FormTableInteraction(table, tableViewer);
        TableColumn[] ownerDraw = new TableColumn[table.getColumnCount() - 1];
        for (int i = 1; i < table.getColumnCount(); i++)
            ownerDraw[i - 1] = table.getColumn(i);
        tableInteraction.setOwnerDrawColumns(ownerDraw);
        boolean hasSavedColumnWidths = FormTableColumnState.hasSavedColumnWidths(columnSettings, KEY_COL_FILL_MODE,
            KEY_COL_PATH_WIDTH, KEY_COL_PROPERTY_WIDTH, KEY_COL_TEXT_WIDTH, KEY_COL_STATUS_WIDTH, KEY_COL_SIDE_WIDTH);
        tableInteraction.install(hasSavedColumnWidths);
        if (checkColumn != null && !checkColumn.isDisposed())
            checkColumn.setMoveable(false);
        // Второстепенные данные — при закрытии; при повторном поиске — явно в {@link #setInput}.
        table.addDisposeListener(e -> saveColumnLayout());
    }

    private static boolean isTableItemSelected(TableItem item)
    {
        if (item == null || item.isDisposed())
            return false;
        Table t = item.getParent();
        if (t == null || t.isDisposed())
            return false;
        for (TableItem sel : t.getSelection())
        {
            if (sel == item)
                return true;
        }
        return false;
    }

    private void installContextMenu()
    {
        MenuManager menuManager = new MenuManager();
        menuManager.setRemoveAllWhenShown(true);
        menuManager.addMenuListener(manager ->
        {
            boolean hasCheckable = selectionHasCheckable();
            Action setMarks = new Action("Установить пометки")
            {
                @Override
                public void run()
                {
                    applyCheckToSelection(true);
                }
            };
            setMarks.setEnabled(hasCheckable);
            setMarks.setToolTipText(
                    "Установить пометки для выделенных строк" + Global.pluginSignForTooltip());

            Action clearMarks = new Action("Снять пометки")
            {
                @Override
                public void run()
                {
                    applyCheckToSelection(false);
                }
            };
            clearMarks.setEnabled(hasCheckable);
            clearMarks.setToolTipText(
                    "Снять пометки с выделенных строк" + Global.pluginSignForTooltip());

            manager.add(setMarks);
            manager.add(clearMarks);
        });
        Menu menu = menuManager.createContextMenu(table);
        table.setMenu(menu);
    }

    private boolean selectionHasCheckable()
    {
        if (table == null || table.isDisposed())
            return false;
        TableItem[] selection = table.getSelection();
        if (selection == null || selection.length == 0)
            return false;
        for (TableItem item : selection)
        {
            if (item.getData() instanceof CompareSearchMatch m && m.isCheckable())
                return true;
        }
        return false;
    }

    private void applyCheckToSelection(boolean want)
    {
        if (table == null || table.isDisposed())
            return;
        TableItem[] selection = table.getSelection();
        if (selection == null || selection.length == 0)
            return;

        Map<Object, Boolean> uniqueNodes = new IdentityHashMap<>();
        for (TableItem item : selection)
        {
            if (!(item.getData() instanceof CompareSearchMatch match) || !match.isCheckable())
                continue;
            Object node = match.getComparisonNode();
            if (node != null)
                uniqueNodes.put(node, Boolean.TRUE);
        }
        if (uniqueNodes.isEmpty())
            return;

        applyingCheckFromResults = true;
        try
        {
            for (Object node : uniqueNodes.keySet())
                applyCheckToTree(node, want);
            syncChecksFromTree();
        }
        finally
        {
            applyingCheckFromResults = false;
        }
    }

    private void addCheckColumn()
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.CENTER);
        checkColumn = col.getColumn();
        checkColumn.setText("");
        checkColumn.setResizable(false);
        checkColumn.setMoveable(false);
        checkColumn.setWidth(CHECK_COLUMN_WIDTH);
        checkColumn.setToolTipText("Объединить" + Global.pluginSignForTooltip());
        // Текст/image через label provider в owner-draw таблице не видны —
        // флажок рисуется в SWT.PaintItem (колонка 0).
        col.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return "";
            }
        });
    }

    private Image checkImageFor(CompareSearchMatch match)
    {
        if (isMatchGrayed(match))
            return checkImageGrayed;
        return isMatchChecked(match) ? checkImageChecked : checkImageUnchecked;
    }

    private Image checkImageForTableItem(TableItem item, CompareSearchMatch match)
    {
        Object cached = item.getData(KEY_CHECKED);
        if (cached instanceof Boolean checked)
        {
            boolean grayed = Boolean.TRUE.equals(item.getData(KEY_GRAYED));
            if (!checked.booleanValue())
                return checkImageUnchecked;
            return grayed ? checkImageGrayed : checkImageChecked;
        }
        return checkImageFor(match);
    }

    private void ensureCheckImages()
    {
        if (checkImageUnchecked != null)
            return;
        Display display = table.getDisplay();
        checkImageUnchecked = createDrawnCheckImage(display, false, false);
        checkImageChecked = createDrawnCheckImage(display, true, false);
        checkImageGrayed = createDrawnCheckImage(display, true, true);
    }

    private static Image createDrawnCheckImage(Display display, boolean checked, boolean grayed)
    {
        int size = 16;
        Image image = new Image(display, size, size);
        GC gc = new GC(image);
        try
        {
            drawFallbackCheckbox(display, gc, new Point(size, size), checked, grayed);
        }
        finally
        {
            gc.dispose();
        }
        return image;
    }

    private static void drawFallbackCheckbox(Display display, GC gc, Point size, boolean checked,
            boolean grayed)
    {
        gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
        gc.fillRectangle(0, 0, size.x, size.y);
        int box = Math.min(size.x, size.y) - 4;
        int x = (size.x - box) / 2;
        int y = (size.y - box) / 2;
        gc.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
        gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
        gc.fillRectangle(x, y, box, box);
        gc.drawRectangle(x, y, box - 1, box - 1);
        if (!checked)
            return;
        gc.setForeground(display.getSystemColor(grayed ? SWT.COLOR_GRAY : SWT.COLOR_BLACK));
        int x1 = x + 2;
        int y1 = y + box / 2;
        int x2 = x + box / 2 - 1;
        int y2 = y + box - 3;
        int x3 = x + box - 2;
        int y3 = y + 2;
        gc.drawLine(x1, y1, x2, y2);
        gc.drawLine(x2, y2, x3, y3);
        gc.drawLine(x1, y1 - 1, x2, y2 - 1);
        gc.drawLine(x2, y2 - 1, x3, y3 - 1);
    }

    private static boolean isMatchChecked(CompareSearchMatch match)
    {
        if (match == null || !match.isCheckable())
            return false;
        Object node = match.getComparisonNode();
        return node instanceof IPartialModelNode partial && partial.isChecked();
    }

    private static boolean isMatchGrayed(CompareSearchMatch match)
    {
        if (match == null || !match.isCheckable())
            return false;
        Object node = match.getComparisonNode();
        return node instanceof IPartialModelNode partial && partial.isGrayed();
    }

    private void addPropertyColumn(IDialogSettings settings)
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("Свойство");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(FormTableColumnState.readWidth(settings, KEY_COL_PROPERTY_WIDTH, 200, 1));
        col.setLabelProvider(new DelegatingStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (element instanceof CompareSearchMatch m)
                {
                    String name = m.getPropertyName();
                    if (!m.isCheckable() && name != null)
                        return new StyledString(name);
                    return highlight(name);
                }
                return new StyledString("");
            }

            @Override
            public Image getImage(Object element) { return null; }
            @Override
            public void addListener(ILabelProviderListener listener) {}
            @Override
            public void removeListener(ILabelProviderListener listener) {}
            @Override
            public void dispose() {}
            @Override
            public boolean isLabelProperty(Object element, String property) { return false; }
        }));
    }

    private void addPathColumn(IDialogSettings settings)
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("Путь");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(FormTableColumnState.readWidth(settings, KEY_COL_PATH_WIDTH, 360, 1));
        col.setLabelProvider(new DelegatingStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (element instanceof CompareSearchMatch m)
                    return highlight(m.getObjectPath());
                return new StyledString("");
            }

            @Override
            public Image getImage(Object element) { return null; }
            @Override
            public void addListener(ILabelProviderListener listener) {}
            @Override
            public void removeListener(ILabelProviderListener listener) {}
            @Override
            public void dispose() {}
            @Override
            public boolean isLabelProperty(Object element, String property) { return false; }
        }));
    }

    private void addTextColumn(IDialogSettings settings)
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("Текст");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(FormTableColumnState.readWidth(settings, KEY_COL_TEXT_WIDTH, 250, 1));
        col.setLabelProvider(new DelegatingStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (element instanceof CompareSearchMatch m)
                    return highlight(m.getMatchText());
                return new StyledString("");
            }

            @Override
            public Image getImage(Object element) { return null; }
            @Override
            public void addListener(ILabelProviderListener listener) {}
            @Override
            public void removeListener(ILabelProviderListener listener) {}
            @Override
            public void dispose() {}
            @Override
            public boolean isLabelProperty(Object element, String property) { return false; }
        }));
    }

    private void addStatusColumn(IDialogSettings settings)
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("Статус");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(FormTableColumnState.readWidth(settings, KEY_COL_STATUS_WIDTH, 100, 1));
        col.setLabelProvider(new DelegatingStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (element instanceof CompareSearchMatch m)
                {
                    String status = m.getComparisonStatus();
                    String text = status != null && !status.isEmpty() ? status : "";
                    String prefix = m.isCheckable() ? "" : "○ ";
                    StyledString ss = new StyledString(prefix + text);
                    if (!m.isCheckable())
                        ss.setStyle(0, 1, UNEDITABLE_STYLER);
                    return ss;
                }
                return new StyledString("");
            }

            @Override
            public Image getImage(Object element) { return null; }
            @Override
            public void addListener(ILabelProviderListener listener) {}
            @Override
            public void removeListener(ILabelProviderListener listener) {}
            @Override
            public void dispose() {}
            @Override
            public boolean isLabelProperty(Object element, String property) { return false; }
        }));
    }

    private void addColumnSideColumn(IDialogSettings settings)
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("Колонка");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(FormTableColumnState.readWidth(settings, KEY_COL_SIDE_WIDTH, 100, 1));
        col.setLabelProvider(new DelegatingStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (element instanceof CompareSearchMatch m)
                    return highlight(m.getColumnSide());
                return new StyledString("");
            }

            @Override
            public Image getImage(Object element) { return null; }
            @Override
            public void addListener(ILabelProviderListener listener) {}
            @Override
            public void removeListener(ILabelProviderListener listener) {}
            @Override
            public void dispose() {}
            @Override
            public boolean isLabelProperty(Object element, String property) { return false; }
        }));
    }

    private static final StyledString.Styler UNEDITABLE_STYLER = new StyledString.Styler()
    {
        @Override
        public void applyStyles(TextStyle textStyle)
        {
            textStyle.foreground = ThemeAwareColors.effectiveSystemColor(
                Display.getCurrent(), SWT.COLOR_DARK_GRAY);
        }
    };

    private StyledString highlight(String text)
    {
        if (text == null)
            return new StyledString("");
        if (queryText == null || queryText.isEmpty())
            return new StyledString(text);

        String lowerText = text.toLowerCase();
        String lowerQuery = queryText.toLowerCase();
        StyledString.Styler matchStyler = SmartMatchHighlight.textOnlyStyler(table);
        StyledString ss = new StyledString();
        int idx = 0;
        while (idx < text.length())
        {
            int matchAt = lowerText.indexOf(lowerQuery, idx);
            if (matchAt < 0)
            {
                // plainStyler(), а не без стиля вообще — иначе у "голого" куска текста нет ни одного
                // StyleRange (см. SmartMatchHighlight.plainStyler()).
                ss.append(text.substring(idx), SmartMatchHighlight.plainStyler());
                break;
            }
            if (matchAt > idx)
                ss.append(text.substring(idx, matchAt), SmartMatchHighlight.plainStyler());
            int matchEnd = matchAt + queryText.length();
            ss.append(text.substring(matchAt, Math.min(matchEnd, text.length())), matchStyler);
            idx = matchEnd;
        }
        return ss;
    }

    private Color getRowBackground(CompareSearchMatch m)
    {
        if (m == null)
            return null;

        DtComparisonView view = getComparisonView();
        if (view == null)
            return null;

        CompareSearchMatch.RowColorKind kind = m.getRowColorKind();
        if (kind == null || kind == CompareSearchMatch.RowColorKind.NONE)
            return null;
        if (kind == CompareSearchMatch.RowColorKind.ONLY_MAIN)
            return view.getColorOnlyMain();
        if (kind == CompareSearchMatch.RowColorKind.ONLY_OTHER)
            return view.getColorOnlyOther();
        if (kind == CompareSearchMatch.RowColorKind.HAS_DIFFS)
            return view.getColorHasDiffs();
        return null;
    }

    private DtComparisonView getComparisonView()
    {
        if (searchResult == null)
            return null;
        IEditorPart editor = searchResult.getEditorPart();
        if (editor == null)
            return null;
        Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
        return view instanceof DtComparisonView dtView ? dtView : null;
    }

    private CheckboxTreeViewer getCheckboxTreeViewer()
    {
        if (searchResult == null)
            return null;
        IEditorPart editor = searchResult.getEditorPart();
        if (editor == null)
            return null;
        Object view = Global.getField(editor, "comparisonView"); //$NON-NLS-1$
        if (view == null)
            return null;
        Object treeControl = Global.call(view, "getTreeControl"); //$NON-NLS-1$
        if (treeControl == null)
            return null;
        Object viewer = Global.call(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return viewer instanceof CheckboxTreeViewer ctv ? ctv : null;
    }

    private void handleResultCheckToggle(TableItem item)
    {
        if (item == null || item.isDisposed())
            return;
        Object data = item.getData();
        if (!(data instanceof CompareSearchMatch match) || !match.isCheckable())
            return;

        boolean want = !isMatchChecked(match);
        applyingCheckFromResults = true;
        try
        {
            applyCheckToTree(match.getComparisonNode(), want);
            syncChecksFromTree();
        }
        finally
        {
            applyingCheckFromResults = false;
        }
    }

    /**
     * Как клик по флажку в дереве сравнения: через {@code CheckStateChangedEvent},
     * чтобы сработали EDT-слушатели и FilterAware-обёртка Comfort.
     */
    private void applyCheckToTree(Object node, boolean want)
    {
        CheckboxTreeViewer ctv = getCheckboxTreeViewer();
        if (ctv != null && ctv.getControl() != null && !ctv.getControl().isDisposed()
                && hasCheckStateListeners(ctv))
        {
            ctv.setChecked(node, want);
            CheckStateChangedEvent event = new CheckStateChangedEvent(ctv, node, want);
            Global.invoke(ctv, "fireCheckStateChanged", event); //$NON-NLS-1$
            return;
        }
        applyCheckDirect(node, want);
        if (ctv != null && ctv.getControl() != null && !ctv.getControl().isDisposed())
            ctv.setChecked(node, want);
    }

    private static boolean hasCheckStateListeners(CheckboxTreeViewer ctv)
    {
        Object listObj = Global.getField(ctv, "checkStateListeners"); //$NON-NLS-1$
        if (listObj == null)
            return false;
        Object raw = Global.invoke(listObj, "getListeners"); //$NON-NLS-1$
        return raw instanceof Object[] arr && arr.length > 0;
    }

    private static void applyCheckDirect(Object element, boolean want)
    {
        if (!(element instanceof IPartialModelNode node))
            return;
        IComparisonSession session = node.getComparisonSession();
        long id = node.getNodeId();
        if (session == null || id == -1L)
        {
            node.check(want);
            node.setChecked(want);
            return;
        }
        session.setMustBeMerged(id, want, true);
        node.setChecked(want);
    }

    private void syncChecksFromTree()
    {
        if (table == null || table.isDisposed())
            return;
        treeCheckDirty.set(false);
        CheckboxTreeViewer ctv = getCheckboxTreeViewer();
        for (TableItem item : table.getItems())
        {
            if (item.isDisposed() || !(item.getData() instanceof CompareSearchMatch match))
                continue;
            if (!match.isCheckable())
            {
                item.setData(KEY_CHECKED, null);
                item.setData(KEY_GRAYED, null);
                continue;
            }
            Object node = match.getComparisonNode();
            boolean checked;
            boolean grayed;
            Widget treeRow = findTreeRow(ctv, node);
            if (treeRow instanceof TreeItem ti && !ti.isDisposed())
            {
                // Строка дерева создана — берём её UI-состояние.
                checked = ti.getChecked();
                grayed = ti.getGrayed();
            }
            else if (node instanceof IPartialModelNode partial)
            {
                // Нет TreeItem (узел не развёрнут): ctv.getChecked() врёт (всегда false).
                // Источник истины — модель узла.
                checked = partial.isChecked();
                grayed = partial.isGrayed();
            }
            else
            {
                checked = false;
                grayed = false;
            }
            item.setData(KEY_CHECKED, Boolean.valueOf(checked));
            item.setData(KEY_GRAYED, Boolean.valueOf(grayed));
            Rectangle bounds = item.getBounds(0);
            table.redraw(bounds.x, bounds.y, bounds.width, bounds.height, false);
        }
    }

    private static Widget findTreeRow(CheckboxTreeViewer ctv, Object node)
    {
        if (ctv == null || node == null)
            return null;
        Object found = Global.invoke(ctv, "findItem", node); //$NON-NLS-1$
        if (found instanceof Widget widget)
            return widget;
        found = Global.invoke(ctv, "testFindItem", node); //$NON-NLS-1$
        return found instanceof Widget widget ? widget : null;
    }

    /** Горячий путь каскада: только AtomicBoolean. Job.schedule — лишь при false→true. */
    private void noteTreeCheckChanged()
    {
        if (applyingCheckFromResults)
            return;
        if (!treeCheckDirty.getAndSet(true))
            ensureTreeCheckSyncJob();
    }

    private void requestTreeCheckSync()
    {
        if (!treeCheckDirty.getAndSet(true))
            ensureTreeCheckSyncJob();
    }

    private void ensureTreeCheckSyncJob()
    {
        if (treeCheckSyncJob == null)
        {
            treeCheckSyncJob = new Job("Комфорт: синхронизация пометок поиска сравнения")
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    return runTreeCheckSyncJob(monitor);
                }
            };
            treeCheckSyncJob.setPriority(Job.DECORATE);
            treeCheckSyncJob.setSystem(true);
        }
        if (treeCheckSyncJob.getState() == Job.NONE)
            treeCheckSyncJob.schedule();
    }

    private IStatus runTreeCheckSyncJob(IProgressMonitor monitor)
    {
        try
        {
            while (!monitor.isCanceled() && searchResult != null)
            {
                if (!treeCheckDirty.get())
                {
                    try
                    {
                        Thread.sleep(300);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        return Status.CANCEL_STATUS;
                    }
                    if (!treeCheckDirty.get())
                        break;
                    continue;
                }
                if (!waitForTreeCheckQuietPeriod(monitor, TREE_CHECK_SYNC_DELAY_MS))
                    return Status.CANCEL_STATUS;
                if (monitor.isCanceled() || searchResult == null)
                    return Status.CANCEL_STATUS;
                syncChecksFromTreeOnUiThread();
            }
            return Status.OK_STATUS;
        }
        finally
        {
            // schedule() на ещё RUNNING job поставит повторный запуск после завершения.
            if (treeCheckDirty.get() && searchResult != null
                    && monitor != null && !monitor.isCanceled() && treeCheckSyncJob != null)
                treeCheckSyncJob.schedule(100);
        }
    }

    /**
     * Ждёт {@code delayMs} без новых note (dirty не взводится заново).
     * Во время каскада dirty постоянно true → ждём до конца каскада + delayMs.
     */
    private boolean waitForTreeCheckQuietPeriod(IProgressMonitor monitor, int delayMs)
    {
        while (!monitor.isCanceled())
        {
            treeCheckDirty.set(false);
            long deadline = System.currentTimeMillis() + delayMs;
            while (System.currentTimeMillis() < deadline)
            {
                if (monitor.isCanceled())
                    return false;
                if (treeCheckDirty.get())
                    break;
                try
                {
                    Thread.sleep(50);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (!treeCheckDirty.get())
                return true;
        }
        return false;
    }

    private void syncChecksFromTreeOnUiThread()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        // syncExec: дождаться применения, пока Job ещё в quiet-цикле.
        display.syncExec(() ->
        {
            if (table == null || table.isDisposed() || searchResult == null)
                return;
            syncChecksFromTree();
        });
    }

    private void installTreeCheckListener()
    {
        uninstallTreeCheckListener();
        CheckboxTreeViewer ctv = getCheckboxTreeViewer();
        if (ctv == null || ctv.getControl() == null || ctv.getControl().isDisposed())
            return;
        treeCheckListener = event -> noteTreeCheckChanged();
        ctv.addCheckStateListener(treeCheckListener);
        hookedTreeViewer = ctv;
    }

    private void uninstallTreeCheckListener()
    {
        if (treeCheckSyncJob != null)
            treeCheckSyncJob.cancel();
        if (hookedTreeViewer != null && treeCheckListener != null)
        {
            try
            {
                if (hookedTreeViewer.getControl() != null
                        && !hookedTreeViewer.getControl().isDisposed())
                    hookedTreeViewer.removeCheckStateListener(treeCheckListener);
            }
            catch (Throwable ignored)
            {
            }
        }
        hookedTreeViewer = null;
        treeCheckListener = null;
        treeCheckDirty.set(false);
    }

    private void navigateToNode(CompareSearchMatch match)
    {
        if (match == null || searchResult == null)
            return;
        IEditorPart editor = searchResult.getEditorPart();
        if (editor == null || editor.getSite() == null)
            return;

        Object view = Global.getField(editor, "comparisonView");
        if (view == null)
            return;
        Object treeControl = Global.call(view, "getTreeControl");
        if (treeControl == null)
            return;
        Object viewer = Global.call(treeControl, "getTreeViewer");
        if (!(viewer instanceof org.eclipse.jface.viewers.AbstractTreeViewer treeViewer))
            return;

        treeViewer.setSelection(new StructuredSelection(match.getComparisonNode()), true);
        treeViewer.reveal(match.getComparisonNode());

        editor.getSite().getPage().activate(editor);
    }

    @Override
    public void setInput(ISearchResult search, Object uiState)
    {
        // Панель не закрывается при повторном «Найти все» — Dispose не вызовется;
        // зафиксировать текущие ширины до подмены набора результатов.
        saveColumnLayout();
        if (search instanceof CompareSearchResult csr)
        {
            this.searchResult = csr;
            this.queryText = csr.getQueryText();
            if (tableViewer != null && !table.isDisposed())
            {
                tableViewer.setInput(csr.getMatches());
                syncChecksFromTree();
                if (tableInteraction != null)
                    tableInteraction.resyncSelectionTheme();
                if (tableStack != null && !tableStack.isDisposed())
                    tableStack.setVisible(true);
            }
            installTreeCheckListener();
        }
        else if (search == null)
        {
            uninstallTreeCheckListener();
            this.searchResult = null;
            this.queryText = null;
            if (tableViewer != null && !table.isDisposed())
            {
                tableViewer.setInput(List.of());
                // PageBook панели "Поиск" не всегда скрывает контрол этой страницы при
                // переключении на другой вид результатов поиска (штатный или другого расширения) —
                // без явного скрытия старая таблица остаётся visible=true и накладывается на новую
                // страницу с теми же bounds. Причина — платформенная гонка (issue #165, та же область:
                // CTabFolder/PageBook desync), надёжного фикса на уровне SWT не найдено; таблица
                // возвращает getVisible()=true без прохождения публичного Control.setVisible() (не
                // ловится ни точечным, ни глобальным SWT.Show listener — проверено). Обходной путь —
                // повторно скрывать её, пока страница остаётся деактивированной.
                Control pageControl = tableStack != null ? tableStack : table;
                pageControl.setVisible(false);
                scheduleForceHide(pageControl, 50);
                scheduleForceHide(pageControl, 200);
                scheduleForceHide(pageControl, 500);
                scheduleForceHide(pageControl, 1000);
                scheduleForceHide(pageControl, 2000);
            }
        }
    }

    @Override
    public void setViewPart(ISearchResultViewPart part)
    {
        this.viewPart = part;
    }

    @Override
    public Object getUIState()
    {
        return null;
    }

    @Override
    public void setActionBars(IActionBars actionBars)
    {
    }

    @Override
    public void dispose()
    {
        saveColumnLayout();
        uninstallTreeCheckListener();
        disposeCheckImages();
        tableInteraction = null;
        tableStack = null;
        table = null;
        tableViewer = null;
        searchResult = null;
    }

    private void disposeCheckImages()
    {
        if (checkImageUnchecked != null && !checkImageUnchecked.isDisposed())
            checkImageUnchecked.dispose();
        if (checkImageChecked != null && !checkImageChecked.isDisposed())
            checkImageChecked.dispose();
        if (checkImageGrayed != null && !checkImageGrayed.isDisposed())
            checkImageGrayed.dispose();
        checkImageUnchecked = null;
        checkImageChecked = null;
        checkImageGrayed = null;
    }

    @Override
    public Control getControl()
    {
        return tableStack != null ? tableStack : table;
    }

    @Override
    public void setFocus()
    {
        if (table != null && !table.isDisposed())
        {
            syncChecksFromTree();
            table.setFocus();
        }
    }

    @Override
    public void setID(String id)
    {
        this.id = id;
    }

    @Override
    public String getID()
    {
        return id;
    }

    @Override
    public String getLabel()
    {
        if (searchResult != null)
            return searchResult.getLabel();
        return "Результаты поиска по дереву сравнения";
    }

    @Override
    public void restoreState(IMemento memento)
    {
        restoredState = memento;
    }

    private void applyRestoredState()
    {
        if (restoredState == null || table == null || table.isDisposed())
            return;
        org.eclipse.swt.widgets.TableColumn[] cols = table.getColumns();
        for (int i = 0; i < cols.length; i++)
        {
            if (i == 0)
            {
                cols[i].setWidth(CHECK_COLUMN_WIDTH);
                continue;
            }
            Integer w = restoredState.getInteger("colWidth" + i);
            if (w != null && w > 0)
                cols[i].setWidth(w);
        }
        restoredState = null;
    }

    @Override
    public void saveState(IMemento memento)
    {
        if (memento == null || table == null || table.isDisposed())
            return;
        org.eclipse.swt.widgets.TableColumn[] cols = table.getColumns();
        for (int i = 0; i < cols.length; i++)
        {
            if (i == 0)
            {
                memento.putInteger("colWidth" + i, CHECK_COLUMN_WIDTH);
                continue;
            }
            memento.putInteger("colWidth" + i, cols[i].getWidth());
        }
        // Дублируем в DialogSettings — основной канал (повторный поиск / закрытие панели).
        saveColumnLayout();
    }

    /**
     * Порядок и ширины колонок (без колонки-флажка) + флаг fill-mode.
     * Колонка 0 — фиксированная пометка, в persist не входит.
     */
    private void saveColumnLayout()
    {
        if (table == null || table.isDisposed() || table.getColumnCount() < 6)
            return;
        boolean fillMode = tableInteraction != null && tableInteraction.isColumnsExactFill();
        FormTableColumnState.saveOrderAndWidths(dialogSettings(), KEY_COL_ORDER, KEY_COL_FILL_MODE, fillMode,
            new String[] { KEY_COL_PATH_WIDTH, KEY_COL_PROPERTY_WIDTH, KEY_COL_TEXT_WIDTH,
                KEY_COL_STATUS_WIDTH, KEY_COL_SIDE_WIDTH },
            new TableColumn[] {
                table.getColumn(1), table.getColumn(2), table.getColumn(3),
                table.getColumn(4), table.getColumn(5) },
            table);
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }
}
