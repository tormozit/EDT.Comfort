package tormozit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.IOpenListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.graphics.Image;
import org.eclipse.search.internal.ui.text.FileMatch;
import org.eclipse.search.internal.ui.text.LineElement;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.search.ui.text.AbstractTextSearchResult;
import org.eclipse.search.ui.text.AbstractTextSearchViewPage;
import org.eclipse.search.ui.text.Match;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

public final class FileSearchResultsHook implements IStartup
{
    private static final String SEARCH_VIEW_ID = "org.eclipse.search.ui.views.SearchView";
    private static final String PAGE_CLASS_MARKER = "FileSearchPage";
    private static final String HOOKED_KEY = "tormozit.fileSearchResultsHooked";
    private static final String COPY_HOOKED_KEY = "tormozit.fileSearchResultsCopyHooked";
    private static final String TREE_OPEN_HOOKED_KEY = "tormozit.fileSearchTreeOpenHooked";

    /** Как в {@code org.eclipse.ui.actions.OpenWithMenu}: не переиспользовать чужой редактор по тому же input. */
    private static final int OPEN_WITH_MATCH =
        IWorkbenchPage.MATCH_INPUT | IWorkbenchPage.MATCH_ID | IWorkbenchPage.MATCH_IGNORE_SIZE;

    private static volatile boolean searchQueryRunning;

    private static final Map<TableViewer, TableColumn> TABLE_COLUMNS_BY_VIEWER = new IdentityHashMap<>();
    private static TableViewer cachedResultTableViewer;

    private static final StyledString.Styler MATCH_STYLER = new StyledString.Styler()
    {
        @Override
        public void applyStyles(TextStyle textStyle)
        {
            textStyle.foreground = Display.getCurrent().getSystemColor(SWT.COLOR_DARK_BLUE);
        }
    };

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            NewSearchUI.addQueryListener(new IQueryListener()
            {
                @Override public void queryAdded(ISearchQuery query)     { onQueryEvent(); }
                @Override public void queryRemoved(ISearchQuery query)    {}
                @Override public void queryStarting(ISearchQuery query)   { searchQueryRunning = true; }
                @Override public void queryFinished(ISearchQuery query)   { searchQueryRunning = false; onQueryEvent(); }
            });
        });
    }

    private static void onQueryEvent()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            IViewPart view = findSearchViewPart();
            if (view != null)
            {
                schedulePatch(view, 0);
                if (!searchQueryRunning)
                    selectFirstTreeResult(view, 0);
            }
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null) return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null) continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isSearchView(view))
                    schedulePatch(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)    { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref)   { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isSearchView(part))
                    schedulePatch((IViewPart) part, 0);
            }
        });
    }

    private static boolean isSearchView(Object part)
    {
        if (!(part instanceof IViewPart vp))
            return false;
        return vp.getViewSite() != null && SEARCH_VIEW_ID.equals(vp.getViewSite().getId());
    }

    private static IViewPart findSearchViewPart()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null) return null;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            IWorkbenchPage page = window.getActivePage();
            if (page == null) continue;
            IViewReference ref = page.findViewReference(SEARCH_VIEW_ID);
            if (ref != null)
            {
                IViewPart view = ref.getView(false);
                if (view != null)
                    return view;
            }
        }
        return null;
    }

    private static void schedulePatch(IViewPart view, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 150;
        display.timerExec(delay, () -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (!tryPatch(view) && attempt < 40)
                schedulePatch(view, attempt + 1);
        });
    }

    private static boolean tryPatch(IViewPart view)
    {
        try
        {
            if (!(view instanceof ISearchResultViewPart))
                return false;
            ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
            if (activePage == null)
                return false;
            if (!activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
                return true;

            Object viewerObj = Global.getField(activePage, "fViewer");
            if (!(viewerObj instanceof TreeViewer treeViewer))
            {
                Global.invoke(activePage, "setLayout",
                    Integer.valueOf(AbstractTextSearchViewPage.FLAG_LAYOUT_TREE));
                return false;
            }

            Tree tree = treeViewer.getTree();
            if (tree == null || tree.isDisposed())
                return false;

            if (tree.getData(HOOKED_KEY) != null)
            {
                reinstallHandlers(activePage, view);
                return true;
            }

            Object pagebookObj = Global.getField(activePage, "fPagebook");
            if (!(pagebookObj instanceof Composite pagebook) || pagebook.isDisposed())
                return false;

            installSplitLayout(treeViewer, pagebook, activePage, view);

            tree.setData(HOOKED_KEY, Boolean.TRUE);
            log("tryPatch: OK");
            return true;
        }
        catch (Exception e)
        {
            log("tryPatch EXCEPTION: " + e);
            return false;
        }
    }

    private static void installSplitLayout(TreeViewer treeViewer, Composite pagebook, Object page, IViewPart view)
    {
        Tree tree = treeViewer.getTree();
        log("installSplitLayout: tree.parent=" + tree.getParent()
            + " pagebook=" + pagebook + " pagebook.children=" + pagebook.getChildren().length);

        Composite parent = pagebook.getParent();
        if (parent == null || parent.isDisposed())
        {
            log("installSplitLayout: parent is null or disposed, aborting");
            return;
        }
        log("installSplitLayout: pagebook.parent=" + parent);

        // Create SashForm in pagebook's parent, at the same level as pagebook
        SashForm sashForm = new SashForm(parent, SWT.HORIZONTAL);
        sashForm.setSashWidth(3);

        // Reparent pagebook itself (not the tree!) into the left side of the SashForm.
        // Pagebook's children (tree, busyLabel) remain intact, and showControl still works.
        pagebook.setParent(sashForm);
        log("installSplitLayout: pagebook reparented, pagebook.parent=" + pagebook.getParent()
            + " parent.children=" + parent.getChildren().length
            + " sashForm.children=" + sashForm.getChildren().length);

        // Create the table on the right side
        TableViewer tableViewer = createResultTable(sashForm);
        cachedResultTableViewer = tableViewer;
        registerCopyHandler(tableViewer);
        registerOpenHandler(tableViewer, treeViewer, page);
        registerContextMenu(tableViewer, treeViewer, page);
        registerTreeContextMenu(treeViewer, page);
        registerGlobalCopyHandler(tableViewer, page, view);

        sashForm.setWeights(new int[] {
            ComfortSettings.getFileSearchSashWeight("left", 60),
            ComfortSettings.getFileSearchSashWeight("right", 40)
        });

        // EDT's PageBook uses showPage(Control) — NOT showControl(Control)
        boolean shown = Global.invokeVoid(parent, "showPage", sashForm);
        log("installSplitLayout: showPage=" + shown);

        sashForm.layout();

        // Persist sash weights on resize (debounced)
        sashForm.addControlListener(new ControlAdapter()
        {
            private Runnable pending;

            @Override
            public void controlResized(ControlEvent e)
            {
                if (pending != null)
                    Display.getDefault().timerExec(-1, pending);
                pending = () -> {
                    if (!sashForm.isDisposed())
                    {
                        int[] w = sashForm.getWeights();
                        if (w.length == 2)
                            ComfortSettings.setFileSearchSashWeights(w[0], w[1]);
                    }
                    pending = null;
                };
                Display.getDefault().timerExec(300, pending);
            }
        });

        treeViewer.addPostSelectionChangedListener(event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            updateTableFromSelection(treeViewer, tableViewer);
        });

        if (!searchQueryRunning)
            updateTableFromSelection(treeViewer, tableViewer);

        installFileTreeMatchCount(treeViewer);
        TreeSoleChildAutoExpand.installForComfortLists(treeViewer);

        log("installSplitLayout: done");
    }

    private static TableViewer createResultTable(Composite parent)
    {
        Composite rightComposite = new Composite(parent, SWT.NONE);
        rightComposite.setLayout(new FillLayout());

        Table table = new Table(rightComposite,
            SWT.MULTI | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        TableViewer tableViewer = new TableViewer(table);
        tableViewer.setContentProvider(org.eclipse.jface.viewers.ArrayContentProvider.getInstance());

        TableViewerColumn pathCol = new TableViewerColumn(tableViewer, SWT.LEFT);
        pathCol.getColumn().setText("Путь");
        pathCol.getColumn().setToolTipText("Путь" + Global.pluginSignForTooltip());
        pathCol.getColumn().setResizable(true);
        pathCol.getColumn().setWidth(ComfortSettings.getFileSearchColumnWidth("path", 180));
        pathCol.getColumn().addListener(SWT.Resize, e -> {
            int w = pathCol.getColumn().getWidth();
            if (w > 0) ComfortSettings.setFileSearchColumnWidth("path", w);
        });
        pathCol.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                if (cell.getElement() instanceof FileSearchRow row)
                    cell.setText(row.path != null ? row.path : "");
            }
        });
        TABLE_COLUMNS_BY_VIEWER.put(tableViewer, pathCol.getColumn());

        TableViewerColumn fileCol = new TableViewerColumn(tableViewer, SWT.LEFT);
        fileCol.getColumn().setText("Файл");
        fileCol.getColumn().setToolTipText("Файл" + Global.pluginSignForTooltip());
        fileCol.getColumn().setResizable(true);
        fileCol.getColumn().setWidth(ComfortSettings.getFileSearchColumnWidth("file", 250));
        fileCol.getColumn().addListener(SWT.Resize, e -> {
            int w = fileCol.getColumn().getWidth();
            if (w > 0) ComfortSettings.setFileSearchColumnWidth("file", w);
        });
        fileCol.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                if (cell.getElement() instanceof FileSearchRow row)
                    cell.setText(row.file != null ? row.file : "");
            }
        });

        TableViewerColumn lineCol = new TableViewerColumn(tableViewer, SWT.RIGHT);
        lineCol.getColumn().setText("Номер строки");
        lineCol.getColumn().setToolTipText("Номер строки" + Global.pluginSignForTooltip());
        lineCol.getColumn().setResizable(true);
        lineCol.getColumn().setWidth(ComfortSettings.getFileSearchColumnWidth("line", 60));
        lineCol.getColumn().addListener(SWT.Resize, e -> {
            int w = lineCol.getColumn().getWidth();
            if (w > 0) ComfortSettings.setFileSearchColumnWidth("line", w);
        });
        lineCol.setLabelProvider(new CellLabelProvider()
        {
            @Override
            public void update(ViewerCell cell)
            {
                if (cell.getElement() instanceof FileSearchRow row)
                    cell.setText(row.lineNumber > 0 ? String.valueOf(row.lineNumber) : "");
            }
        });

        TableViewerColumn textCol = new TableViewerColumn(tableViewer, SWT.LEFT);
        textCol.getColumn().setText("Текст");
        textCol.getColumn().setToolTipText("Текст" + Global.pluginSignForTooltip());
        textCol.getColumn().setResizable(true);
        textCol.getColumn().setWidth(ComfortSettings.getFileSearchColumnWidth("text", 300));
        textCol.getColumn().addListener(SWT.Resize, e -> {
            int w = textCol.getColumn().getWidth();
            if (w > 0) ComfortSettings.setFileSearchColumnWidth("text", w);
        });
        textCol.setLabelProvider(new DelegatingStyledCellLabelProvider(new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                if (!(element instanceof FileSearchRow row))
                    return new StyledString("");
                String text = row.text != null ? row.text : "";
                if (row.matchOffsets == null || row.matchOffsets.length == 0)
                    return new StyledString(text);
                StyledString ss = new StyledString();
                int pos = 0;
                for (int i = 0; i < row.matchOffsets.length; i++)
                {
                    int off = row.matchOffsets[i];
                    int len = row.matchLengths[i];
                    if (off > pos)
                        ss.append(text.substring(pos, off));
                    int end = off + len;
                    if (end > text.length()) end = text.length();
                    if (end > off)
                        ss.append(text.substring(off, end), MATCH_STYLER);
                    pos = end;
                }
                if (pos < text.length())
                    ss.append(text.substring(pos));
                return ss;
            }

            @Override
            public Image getImage(Object element) { return null; }

            @Override
            public void addListener(ILabelProviderListener listener) {}

            @Override
            public void dispose() {}

            @Override
            public boolean isLabelProperty(Object element, String property) { return false; }

            @Override
            public void removeListener(ILabelProviderListener listener) {}
        }));

        tableViewer.setComparator(new org.eclipse.jface.viewers.ViewerComparator()
        {
            @Override
            public int compare(org.eclipse.jface.viewers.Viewer viewer,
                    Object e1, Object e2)
            {
                if (!(e1 instanceof FileSearchRow r1) || !(e2 instanceof FileSearchRow r2))
                    return 0;
                int cmp = compareStrings(r1.path, r2.path);
                if (cmp != 0) return cmp;
                cmp = compareStrings(r1.file, r2.file);
                if (cmp != 0) return cmp;
                return Integer.compare(r1.lineNumber, r2.lineNumber);
            }
        });

        return tableViewer;
    }

    private static int compareStrings(String a, String b)
    {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
    }

    private static final class FileSearchRow
    {
        final String path;
        final String file;
        final int lineNumber;
        final String text;
        final IFile iFile;
        final LineElement lineElement;
        final int[] matchOffsets;
        final int[] matchLengths;

        FileSearchRow(String path, String file, int lineNumber, String text,
                IFile iFile, LineElement lineElement, int[] matchOffsets, int[] matchLengths)
        {
            this.path = path;
            this.file = file;
            this.lineNumber = lineNumber;
            this.text = text;
            this.iFile = iFile;
            this.lineElement = lineElement;
            this.matchOffsets = matchOffsets;
            this.matchLengths = matchLengths;
        }
    }

    private static void updateTableFromSelection(TreeViewer treeViewer, TableViewer tableViewer)
    {
        try
        {
            IStructuredSelection sel = treeViewer.getStructuredSelection();
            List<Object> selectedNodes = sel.toList();
            if (selectedNodes.isEmpty())
            {
                tableViewer.setInput(List.of());
                return;
            }

            ITreeContentProvider cp = (ITreeContentProvider) treeViewer.getContentProvider();
            List<FileSearchRow> rows = new ArrayList<>();
            Object resultInput = treeViewer.getInput();
            AbstractTextSearchResult searchResult = resultInput instanceof AbstractTextSearchResult r ? r : null;
            for (Object node : selectedNodes)
                collectRows(node, cp, rows, searchResult);

            rows.sort(Comparator
                .<FileSearchRow, String>comparing(r -> r.path != null ? r.path : "", String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.file != null ? r.file : "", String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(r -> r.lineNumber));

            tableViewer.setInput(rows);
        }
        catch (Exception e)
        {
            log("updateTable: " + e);
        }
    }

    private static void collectRows(Object node, ITreeContentProvider cp,
            List<FileSearchRow> out, AbstractTextSearchResult searchResult)
    {
        if (node instanceof IFile file)
        {
            Object[] children = cp.getChildren(node);
            for (Object child : children)
            {
                if (child instanceof LineElement le)
                {
                    String filePath = file.getProjectRelativePath().toString();
                    String mdPath = resolveMdPath(filePath);
                    int[] offs = new int[0], lens = new int[0];
                    if (searchResult != null)
                    {
                        List<Integer> offList = new ArrayList<>();
                        List<Integer> lenList = new ArrayList<>();
                        for (Match m : searchResult.getMatches(file))
                        {
                            if (m instanceof FileMatch fm && le.equals(fm.getLineElement()))
                            {
                                offList.add(fm.getOffset() - le.getOffset());
                                lenList.add(fm.getLength());
                            }
                        }
                        offs = offList.stream().mapToInt(Integer::intValue).toArray();
                        lens = lenList.stream().mapToInt(Integer::intValue).toArray();
                    }
                    out.add(new FileSearchRow(mdPath, filePath,
                        le.getLine(), le.getContents(), file, le, offs, lens));
                }
            }
        }
        else if (cp.hasChildren(node))
        {
            Object[] children = cp.getChildren(node);
            for (Object child : children)
                collectRows(child, cp, out, searchResult);
        }
    }

    private static String resolveMdPath(String projectRelativePath)
    {
        try
        {
            GetRef.ModuleRef ref = GetRef.pathToModuleRef(projectRelativePath);
            if (ref != null && ref.modulePath != null && !ref.modulePath.isEmpty())
                return ref.modulePath;
            String fullName = GetRef.pathToFullName(projectRelativePath);
            if (fullName != null && !fullName.isEmpty())
                return fullName;
        }
        catch (Exception ignored)
        {
        }
        return projectRelativePath;
    }

    private static void registerCopyHandler(TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed() || table.getData(COPY_HOOKED_KEY) != null)
            return;
        table.setData(COPY_HOOKED_KEY, Boolean.TRUE);
        table.addListener(SWT.KeyDown, event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if ((event.stateMask & SWT.MOD1) == 0)
                return;
            if (event.keyCode != 'c' && event.keyCode != 'C')
                return;
            if (copySelectedRowsToClipboard(tableViewer))
                event.doit = false;
        });
    }

    private static boolean copySelectedRowsToClipboard(TableViewer tableViewer)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return false;
        TableItem[] selected = table.getSelection();
        if (selected == null || selected.length == 0)
            return false;

        StringBuilder clipboard = new StringBuilder();
        for (TableItem item : selected)
        {
            if (item == null || item.isDisposed())
                continue;
            Object data = item.getData();
            if (!(data instanceof FileSearchRow row))
                continue;
            if (clipboard.length() > 0)
                clipboard.append('\n');
            clipboard.append(row.path != null ? row.path : "");
            clipboard.append('\t');
            clipboard.append(row.file != null ? row.file : "");
            clipboard.append('\t');
            clipboard.append(row.lineNumber > 0 ? String.valueOf(row.lineNumber) : "");
            clipboard.append('\t');
            clipboard.append(row.text != null ? row.text : "");
        }
        if (clipboard.length() == 0)
            return false;

        Clipboard cb = new Clipboard(table.getDisplay());
        try
        {
            cb.setContents(
                new Object[] { clipboard.toString() },
                new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            cb.dispose();
        }
        return true;
    }

    private static void registerOpenHandler(TableViewer tableViewer,
            TreeViewer treeViewer, Object page)
    {
        Table table = tableViewer.getTable();
        table.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseDoubleClick(MouseEvent e)
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                openFileInEditor(tableViewer, treeViewer, page);
            }
        });
        replaceTreeOpenHandler(treeViewer, page);
    }

    private static void replaceTreeOpenHandler(TreeViewer treeViewer, Object page)
    {
        if (Boolean.TRUE.equals(treeViewer.getData(TREE_OPEN_HOOKED_KEY)))
            return;
        treeViewer.setData(TREE_OPEN_HOOKED_KEY, Boolean.TRUE);
        try
        {
            Object listenerListObj = Global.getField(treeViewer, "openListeners");
            Object[] saved = listenerListObj != null
                ? (Object[]) Global.invoke(listenerListObj, "getListeners")
                : new Object[0];
            if (listenerListObj != null)
                Global.invoke(listenerListObj, "clear");
            treeViewer.addOpenListener(event -> {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                {
                    for (Object o : saved)
                    {
                        if (o instanceof IOpenListener ol)
                            ol.open(event);
                    }
                    return;
                }
                openFileInEditorFromTree(treeViewer, page);
            });
        }
        catch (Exception e)
        {
            log("replaceTreeOpenHandler: " + e);
            treeViewer.addOpenListener(ev -> {
                if (ComfortSettings.isReplaceListFiltersEnabled())
                    openFileInEditorFromTree(treeViewer, page);
            });
        }
    }

    private static void registerContextMenu(TableViewer tableViewer,
            TreeViewer treeViewer, Object page)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        MenuManager menuManager = new MenuManager();
        menuManager.add(new Action("Открыть редактор объекта")
        {
            @Override
            public void run()
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                openSelectedRow(tableViewer, treeViewer, page);
            }
        });
        menuManager.add(new Action("Открыть редактор файла")
        {
            @Override
            public void run()
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                openFileInEditor(tableViewer, treeViewer, page);
            }
        });
        menuManager.add(new Action("Копировать")
        {
            @Override
            public void run()
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return;
                copySelectedRowsToClipboard(tableViewer);
            }
        });
        Menu menu = menuManager.createContextMenu(table);
        table.setMenu(menu);
    }

    private static void openSelectedRow(TableViewer tableViewer, TreeViewer treeViewer, Object page)
    {
        Table table = tableViewer.getTable();
        TableItem[] selection = table.getSelection();
        if (selection == null || selection.length == 0)
            return;
        Object data = selection[0].getData();
        if (!(data instanceof FileSearchRow row))
            return;
        if (row.lineElement == null || row.iFile == null)
            return;
        Match[] matches = findMatches(treeViewer, row.iFile, row.lineElement);
        if (matches != null && matches.length > 0)
        {
            Match match = matches[0];
            Global.invoke(page, "showMatch", match,
                Integer.valueOf(row.lineElement.getOffset()),
                Integer.valueOf(row.lineElement.getLength()),
                Boolean.TRUE);
        }
    }

    private static Match[] findMatches(TreeViewer treeViewer, IFile file, LineElement lineElement)
    {
        Object input = treeViewer.getInput();
        if (input instanceof AbstractTextSearchResult result)
        {
            Match[] allMatches = result.getMatches(file);
            if (allMatches == null)
                return null;
            List<Match> matched = new ArrayList<>();
            for (Match m : allMatches)
            {
                if (m instanceof FileMatch fm && lineElement.equals(fm.getLineElement()))
                    matched.add(fm);
            }
            return matched.toArray(new Match[0]);
        }
        return null;
    }

    private static final String TREE_MENU_ITEM_KEY = "tormozit.fileSearchTreeMenuItemAdded";
    private static final String TREE_MENU_LISTENER_KEY = "tormozit.fileSearchTreeMenuListener";

    private static void registerTreeContextMenu(TreeViewer treeViewer, Object page)
    {
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed())
            return;

        // Remove old listener if re-registering
        Listener oldListener = (Listener) tree.getData(TREE_MENU_LISTENER_KEY);
        if (oldListener != null)
            tree.removeListener(SWT.MenuDetect, oldListener);

        Listener listener = event -> {
            Menu menu = tree.getMenu();
            if (menu == null || menu.isDisposed())
            {
                menu = new Menu(tree);
                tree.setMenu(menu);
            }
            if (Boolean.TRUE.equals(menu.getData(TREE_MENU_ITEM_KEY)))
                return;
            menu.setData(TREE_MENU_ITEM_KEY, Boolean.TRUE);

            new MenuItem(menu, SWT.SEPARATOR);
            MenuItem menuItem = new MenuItem(menu, SWT.PUSH);
            menuItem.setText("Открыть редактор файла");
            menuItem.addListener(SWT.Selection, e -> openFileInEditorFromTree(treeViewer, page));
        };

        tree.setData(TREE_MENU_LISTENER_KEY, listener);
        tree.addListener(SWT.MenuDetect, listener);
    }

    private static void openFileInEditor(TableViewer tableViewer, TreeViewer treeViewer, Object page)
    {
        Table table = tableViewer.getTable();
        if (table == null || table.isDisposed())
            return;
        TableItem[] selection = table.getSelection();
        if (selection == null || selection.length == 0)
            return;
        Object data = selection[0].getData();
        if (!(data instanceof FileSearchRow row))
            return;
        if (row.iFile == null || row.lineElement == null)
            return;

        openFileInEditorImpl(row.iFile, row.lineElement, treeViewer, page, row);
    }

    /**
     * ID встроенного в Eclipse простого текстового редактора ({@code org.eclipse.ui.editors.text.EditorsUI.DEFAULT_TEXT_EDITOR_ID}).
     * Задан литералом, чтобы не тянуть зависимость от бандла org.eclipse.ui.editors.
     */
    private static final String DEFAULT_TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor";

    /** «Открыть с помощью → Редактор XML» (org.eclipse.wst.xml.ui). */
    private static final String XML_EDITOR_ID =
        "org.eclipse.wst.xml.ui.internal.tabletree.XMLMultiPageEditorPart";

    private static final java.util.Set<String> XML_SOURCE_EXTENSIONS = java.util.Set.of(
        "form", "cmi", "xml", "mxlx", "mdo");

    private static void openFileInEditorImpl(IFile file, LineElement lineElement,
            TreeViewer treeViewer, Object page, FileSearchRow row)
    {
        if (file == null || lineElement == null)
            return;
        int[] bounds = resolveMatchBounds(file, lineElement, treeViewer, row);

        if (isBslModuleFile(file))
            openBslMatchInObjectEditor(file, bounds);
        else
            openMatchInSourceEditor(file, bounds);
    }

    private static boolean isBslModuleFile(IFile file)
    {
        return file != null && "bsl".equalsIgnoreCase(file.getFileExtension());
    }

    private static int[] resolveMatchBounds(IFile file, LineElement lineElement,
            TreeViewer treeViewer, FileSearchRow row)
    {
        Match[] matches = findMatches(treeViewer, file, lineElement);
        if (matches != null && matches.length > 0 && matches[0] instanceof FileMatch fm)
            return new int[] { fm.getOffset(), Math.max(fm.getLength(), 1) };

        if (row != null && row.matchOffsets != null && row.matchOffsets.length > 0
            && row.matchLengths != null && row.matchLengths.length > 0)
        {
            int offset = lineElement.getOffset() + row.matchOffsets[0];
            return new int[] { offset, Math.max(row.matchLengths[0], 1) };
        }

        if (lineElement.getLine() > 0)
            return new int[] { lineElement.getOffset(), 1 };

        return new int[] { 0, 1 };
    }

    private static void openBslMatchInObjectEditor(IFile file, int[] bounds)
    {
        IWorkbenchPage wbPage = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage();
        if (wbPage == null)
            return;
        ITextSelection selection = new TextSelection(bounds[0], bounds[1]);
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper");
            Object helper = cls.getConstructor(IWorkbenchPage.class).newInstance(wbPage);
            for (java.lang.reflect.Method m : cls.getMethods())
            {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 2)
                    continue;
                if (m.getParameterTypes()[0].equals(IFile.class)
                    && m.getParameterTypes()[1].equals(ISelection.class))
                {
                    Object editorPart = m.invoke(helper, file, selection);
                    if (editorPart instanceof IEditorPart ep)
                        revealMatchInEditor(ep, bounds[0], bounds[1]);
                    return;
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static String resolveSourceEditorId(IFile file)
    {
        if (file == null)
            return DEFAULT_TEXT_EDITOR_ID;
        String ext = file.getFileExtension();
        if (ext != null && XML_SOURCE_EXTENSIONS.contains(ext.toLowerCase(java.util.Locale.ROOT)))
            return XML_EDITOR_ID;
        return DEFAULT_TEXT_EDITOR_ID;
    }

    private static void openMatchInSourceEditor(IFile file, int[] bounds)
    {
        IWorkbenchPage wbPage = PlatformUI.getWorkbench()
            .getActiveWorkbenchWindow().getActivePage();
        if (wbPage == null)
            return;
        String editorId = resolveSourceEditorId(file);
        IEditorInput input = new FileEditorInput(file);
        IEditorPart editor;
        try
        {
            editor = wbPage.openEditor(input, editorId, true, OPEN_WITH_MATCH);
        }
        catch (PartInitException e)
        {
            return;
        }
        revealMatchInEditor(editor, bounds[0], bounds[1]);
    }

    private static void revealMatchInEditor(IEditorPart editor, int offset, int length)
    {
        ITextEditor textEditor = findTextEditorInEditor(editor);
        if (textEditor == null)
            return;
        Display.getDefault().asyncExec(() -> {
            if (textEditor.getSite() != null
                && textEditor.getSite().getShell() != null
                && !textEditor.getSite().getShell().isDisposed())
                textEditor.selectAndReveal(offset, length);
        });
    }

    private static ITextEditor findTextEditorInEditor(IEditorPart editor)
    {
        if (editor == null)
            return null;
        if (editor instanceof ITextEditor te)
            return te;
        ITextEditor fromBsl = extractXtextEditorViaReflection(editor);
        if (fromBsl != null)
            return fromBsl;
        Object countObj = Global.invoke(editor, "getPageCount");
        if (!(countObj instanceof Integer count))
            return null;
        for (int i = 0; i < count; i++)
        {
            Object pageObj = Global.invoke(editor, "getEditor", Integer.valueOf(i));
            if (pageObj instanceof IEditorPart pageEditor)
            {
                ITextEditor nested = findTextEditorInEditor(pageEditor);
                if (nested != null)
                    return nested;
            }
            else if (pageObj instanceof ITextEditor te)
                return te;
        }
        return null;
    }

    private static ITextEditor extractXtextEditorViaReflection(IEditorPart editor)
    {
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil");
            java.lang.reflect.Method method = cls.getMethod("extractXtextEditor", IEditorPart.class);
            Object result = method.invoke(null, editor);
            if (result instanceof ITextEditor te)
                return te;
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static void openFileInEditorFromTree(TreeViewer treeViewer, Object page)
    {
        IStructuredSelection selection = treeViewer.getStructuredSelection();
        Object first = selection.getFirstElement();
        if (first == null)
            return;

        ITreeContentProvider cp = (ITreeContentProvider) treeViewer.getContentProvider();

        if (first instanceof IFile file)
        {
            LineElement le = findFirstLineElement(file, cp);
            if (le != null)
                openFileInEditorImpl(file, le, treeViewer, page, null);
        }
        else if (first instanceof LineElement le)
        {
            IFile file = findFileForLineElement(le, treeViewer);
            if (file != null)
                openFileInEditorImpl(file, le, treeViewer, page, null);
        }
    }

    private static LineElement findFirstLineElement(IFile file, ITreeContentProvider cp)
    {
        Object[] children = cp.getChildren(file);
        if (children == null)
            return null;
        for (Object child : children)
        {
            if (child instanceof LineElement le)
                return le;
        }
        return null;
    }

    private static IFile findFileForLineElement(LineElement lineElement, TreeViewer treeViewer)
    {
        ITreeContentProvider cp = (ITreeContentProvider) treeViewer.getContentProvider();
        Object input = treeViewer.getInput();
        if (cp.hasChildren(input))
            return findFileForLineElementRec(lineElement, cp, cp.getChildren(input));
        return null;
    }

    private static IFile findFileForLineElementRec(LineElement lineElement,
            ITreeContentProvider cp, Object[] nodes)
    {
        if (nodes == null)
            return null;
        for (Object node : nodes)
        {
            if (node instanceof IFile file)
            {
                Object[] fileChildren = cp.getChildren(file);
                if (fileChildren != null)
                {
                    for (Object child : fileChildren)
                    {
                        if (lineElement.equals(child))
                            return file;
                    }
                }
            }
            else if (cp.hasChildren(node))
            {
                IFile found = findFileForLineElementRec(lineElement, cp, cp.getChildren(node));
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void selectFirstTreeResult(IViewPart view, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            if (!(view instanceof ISearchResultViewPart))
                return;
            ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
            if (activePage == null || !activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
            {
                if (attempt < 40)
                    selectFirstTreeResult(view, attempt + 1);
                return;
            }
            Object viewerObj = Global.getField(activePage, "fViewer");
            if (!(viewerObj instanceof TreeViewer tv))
            {
                if (attempt < 40)
                    selectFirstTreeResult(view, attempt + 1);
                return;
            }
            Tree tree = tv.getTree();
            if (tree == null || tree.isDisposed() || tree.getItemCount() == 0)
            {
                if (attempt < 40)
                    selectFirstTreeResult(view, attempt + 1);
                return;
            }
            if (tree.getData(HOOKED_KEY) == null)
            {
                if (attempt < 40)
                    selectFirstTreeResult(view, attempt + 1);
                return;
            }
            Object first = tree.getItem(0).getData();
            if (first != null)
            {
                tv.setSelection(new StructuredSelection(first), true);
                log("selectFirstTreeResult: selected " + first);
            }
        });
    }

    private static void registerGlobalCopyHandler(TableViewer tableViewer, Object page, IViewPart view)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        // KeyUp filter: SWT Table consumes Ctrl+C KeyDown in its window procedure,
        // so we intercept the KeyUp of 'C' and overwrite clipboard after e4 handler.
        display.addFilter(SWT.KeyUp, event -> {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                return;
            Table table = tableViewer.getTable();
            if (table == null || table.isDisposed() || !table.isFocusControl())
                return;
            if ((event.stateMask & SWT.MOD1) == 0)
                return;
            if (event.keyCode != 'c' && event.keyCode != 'C')
                return;
            display.asyncExec(() -> copySelectedRowsToClipboard(tableViewer));
        });

        // IHandlerService: handler override for environments where KeyUp is not needed
        IHandlerService handlerService = view.getSite().getService(IHandlerService.class);
        if (handlerService == null)
        {
            try
            {
                handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
            }
            catch (Exception e) { /* ignore */ }
        }
        if (handlerService == null)
            return;
        handlerService.activateHandler("org.eclipse.ui.edit.copy", new AbstractHandler()
        {
            @Override
            public Object execute(ExecutionEvent event) throws ExecutionException
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return null;
                copySelectedRowsToClipboard(tableViewer);
                return null;
            }

            @Override
            public boolean isHandled()
            {
                if (!ComfortSettings.isReplaceListFiltersEnabled())
                    return false;
                Table table = tableViewer.getTable();
                return table != null && !table.isDisposed() && table.isFocusControl();
            }
        });
    }

    private static void reinstallHandlers(Object page, IViewPart view)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        TableViewer tv = cachedResultTableViewer;
        if (tv == null || tv.getTable() == null || tv.getTable().isDisposed())
            return;
        tv.getTable().setData(COPY_HOOKED_KEY, null);
        registerCopyHandler(tv);
        registerGlobalCopyHandler(tv, page, view);

        ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
        if (activePage != null && activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
        {
            Object viewerObj = Global.getField(activePage, "fViewer");
            if (viewerObj instanceof TreeViewer treeViewer)
                registerTreeContextMenu(treeViewer, activePage);
        }
    }

    private static void installFileTreeMatchCount(TreeViewer treeViewer)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        IBaseLabelProvider baseLp = treeViewer.getLabelProvider();
        if (baseLp == null)
            return;
        IStyledLabelProvider innerStyled;
        if (baseLp instanceof DelegatingStyledCellLabelProvider dscp)
            innerStyled = dscp.getStyledStringProvider();
        else if (baseLp instanceof IStyledLabelProvider slp)
            innerStyled = slp;
        else
            return;
        IStyledLabelProvider wrapper = new IStyledLabelProvider()
        {
            @Override
            public StyledString getStyledText(Object element)
            {
                StyledString original = innerStyled.getStyledText(element);
                if (!(element instanceof IResource resource) || element instanceof LineElement)
                    return original;
                int count = countMatchesForResource(resource, treeViewer);
                if (count <= 0)
                    return original;
                String cleanText = stripCountSuffix(original.getString());
                StyledString result = new StyledString();
                result.append(cleanText);
                result.append(" (" + count + ")");
                return result;
            }

            @Override
            public Image getImage(Object element) { return innerStyled.getImage(element); }

            @Override
            public void addListener(ILabelProviderListener listener) { innerStyled.addListener(listener); }

            @Override
            public void dispose() { innerStyled.dispose(); }

            @Override
            public boolean isLabelProperty(Object element, String property) { return innerStyled.isLabelProperty(element, property); }

            @Override
            public void removeListener(ILabelProviderListener listener) { innerStyled.removeListener(listener); }
        };
        treeViewer.setLabelProvider(new DelegatingStyledCellLabelProvider(wrapper));
        treeViewer.refresh();
    }

    private static String stripCountSuffix(String text)
    {
        String result = text.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        result = result.replaceAll("\\s+\\d+$", "").trim();
        return result;
    }

    private static int countMatchesForResource(IResource resource, TreeViewer tv)
    {
        Object input = tv.getInput();
        if (!(input instanceof AbstractTextSearchResult result))
            return 0;
        if (resource instanceof IFile file)
            return result.getMatches(file) != null ? result.getMatches(file).length : 0;
        int total = 0;
        ITreeContentProvider cp = (ITreeContentProvider) tv.getContentProvider();
        for (Object child : cp.getChildren(resource))
        {
            if (child instanceof IFile file)
            {
                int cnt = result.getMatches(file) != null ? result.getMatches(file).length : 0;
                total += cnt;
            }
            else if (child instanceof IResource resChild)
            {
                total += countMatchesForResource(resChild, tv);
            }
        }
        return total;
    }

    private static void log(String message)
    {
        Global.log("FileSearchResults", message);
    }
}
