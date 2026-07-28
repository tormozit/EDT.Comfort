package tormozit;

import java.util.List;

import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.part.IPageSite;

import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;

public class CompareSearchResultPage implements ISearchResultPage
{
    /**
     * Обходной путь (issue #165, платформенная гонка PageBook/CTabFolder — см. описание у вызова):
     * повторно скрывает таблицу, если страница всё ещё деактивирована ({@code this.searchResult
     * == null}) — не трогает состояние, если за это время пришёл новый реальный поиск.
     */
    private void scheduleForceHide(Table table, int delayMs)
    {
        Display display = table.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(delayMs, () -> {
            if (table.isDisposed() || searchResult != null)
                return;
            if (table.getVisible())
                table.setVisible(false);
        });
    }

    private String id;
    private ISearchResultViewPart viewPart;
    private IPageSite pageSite;
    private Table table;
    private TableViewer tableViewer;

    private CompareSearchResult searchResult;
    private String queryText;

    private IMemento restoredState;

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
        table = new Table(parent,
            SWT.MULTI | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        tableViewer = new TableViewer(table);
        tableViewer.setContentProvider(ArrayContentProvider.getInstance());

        addPathColumn();
        addPropertyColumn();
        addTextColumn();
        addStatusColumn();
        addColumnSideColumn();
        applyRestoredState();

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

        table.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if ((e.stateMask & SWT.CTRL) != 0 && (e.keyCode == 'c' || e.keyCode == 'C'))
                    copySelection();
            }
        });

        table.addListener(SWT.EraseItem, event ->
        {
            event.detail &= ~SWT.HOT;
            if (event.item.getData() instanceof CompareSearchMatch m)
            {
                Color bg = getRowBackground(m);
                if (bg != null)
                {
                    event.gc.setBackground(bg);
                    event.gc.fillRectangle(event.x, event.y, event.width, event.height);
                    event.detail &= ~SWT.BACKGROUND;
                }
            }
        });
    }

    private void addPropertyColumn()
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("\u0421\u0432\u043E\u0439\u0441\u0442\u0432\u043E");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(200);
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

    private void addPathColumn()
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("\u041F\u0443\u0442\u044C");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(180);
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

    private void addTextColumn()
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("\u0422\u0435\u043A\u0441\u0442");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(250);
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

    private void addStatusColumn()
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("\u0421\u0442\u0430\u0442\u0443\u0441");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(100);
        col.setLabelProvider(new DelegatingStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (element instanceof CompareSearchMatch m)
                {
                    String status = m.getComparisonStatus();
                    String text = status != null && !status.isEmpty() ? status : "";
                    String prefix = m.isCheckable() ? "" : "\u25CB ";
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

    private void addColumnSideColumn()
    {
        TableViewerColumn col = new TableViewerColumn(tableViewer, SWT.LEFT);
        col.getColumn().setText("\u041A\u043E\u043B\u043E\u043D\u043A\u0430");
        col.getColumn().setResizable(true);
        col.getColumn().setWidth(100);
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
                ss.append(text.substring(idx));
                break;
            }
            if (matchAt > idx)
                ss.append(text.substring(idx, matchAt));
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

        Object node = m.getComparisonNode();
        if (!(node instanceof IPartialModelNode partialNode))
            return null;

        DtComparisonView view = getComparisonView();
        if (view == null)
            return null;

        try
        {
            ComparisonSide side = partialNode.getSide();
            if (side == ComparisonSide.MAIN)
                return view.getColorOnlyMain();
            if (side == ComparisonSide.OTHER)
                return view.getColorOnlyOther();
            if (partialNode.hasDifferences(ComparisonSide.MAIN, ComparisonSide.OTHER))
                return view.getColorHasDiffs();
        }
        catch (Throwable t)
        {
            Global.logError("CompareSearch", "getRowBackground failed", t); //$NON-NLS-1$
        }
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

    private void copySelection()
    {
        TableItem[] selection = table.getSelection();
        if (selection.length == 0)
            return;
        StringBuilder sb = new StringBuilder();
        for (TableItem item : selection)
        {
            if (item.getData() instanceof CompareSearchMatch m)
            {
                sb.append(m.getPropertyName()).append('\t')
                  .append(m.getObjectPath()).append('\t')
                  .append(m.getMatchText()).append('\t')
                  .append(m.getComparisonStatus()).append('\t')
                  .append(m.getColumnSide()).append(System.lineSeparator());
            }
        }
        if (sb.length() == 0)
            return;
        Clipboard clipboard = new Clipboard(table.getDisplay());
        try
        {
            clipboard.setContents(new Object[] { sb.toString() },
                new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
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
        if (search instanceof CompareSearchResult csr)
        {
            this.searchResult = csr;
            this.queryText = csr.getQueryText();
            if (tableViewer != null && !table.isDisposed())
            {
                tableViewer.setInput(csr.getMatches());
                table.setVisible(true);
            }
        }
        else if (search == null)
        {
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
                table.setVisible(false);
                scheduleForceHide(table, 50);
                scheduleForceHide(table, 200);
                scheduleForceHide(table, 500);
                scheduleForceHide(table, 1000);
                scheduleForceHide(table, 2000);
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
        table = null;
        tableViewer = null;
        searchResult = null;
    }

    @Override
    public Control getControl()
    {
        return table;
    }

    @Override
    public void setFocus()
    {
        if (table != null && !table.isDisposed())
            table.setFocus();
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
        return "\u0420\u0435\u0437\u0443\u043B\u044C\u0442\u0430\u0442\u044B \u043F\u043E\u0438\u0441\u043A\u0430 \u043F\u043E \u0434\u0435\u0440\u0435\u0432\u0443 \u0441\u0440\u0430\u0432\u043D\u0435\u043D\u0438\u044F";
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
            memento.putInteger("colWidth" + i, cols[i].getWidth());
        }
    }
}
