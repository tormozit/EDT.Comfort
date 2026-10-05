package tormozit;

import java.text.Collator;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.PredefinedDataItemViewModel;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;

/**
 * Вкладка «Предопределенные» редактора объекта: сортировка по клику на заголовок колонки
 * «Имя», «Код» или «Наименование»; при первом открытии — по «Имя».
 */
public final class PredefinedDataSortHook implements IStartup
{
    private static final String TAG = "PredefinedDataSort"; //$NON-NLS-1$

    private static final String PAGE_ID = "editors.pages.predefined"; //$NON-NLS-1$

    private static final String PAGE_CLASS_SUFFIX = "DtGranularEditorPredefinedDataPage"; //$NON-NLS-1$

    private static final String PREDEFINED_COMPARATOR_SUFFIX = "PredefinedItemViewerComparator"; //$NON-NLS-1$

    private static final String DT_TREE_VIEWER_KEY =
            "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$

    private static final String HOOK_MARKER = "tormozit.predefinedDataSortHook"; //$NON-NLS-1$

    private static final String SORT_STATE_KEY = "tormozit.predefinedDataSortState"; //$NON-NLS-1$

    private static final String HEADER_LISTENER_KEY = "tormozit.predefinedDataSortHeader"; //$NON-NLS-1$

    private static final String MD_OBJECT_KEY = "tormozit.predefinedDataSortMdObject"; //$NON-NLS-1$

    private static final String FILTER_MARKER = "tormozit.predefinedDataFilterInstalled"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    private static final Collator SORT_COLLATOR = createSortCollator();

    private final Set<DtGranularEditor<?>> hookedEditors =
            Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                if (ref.getEditor(false) instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { hookFromRef(ref); }
        });
    }

    private void hookFromRef(IWorkbenchPartReference ref)
    {
        if (ref != null && ref.getPart(false) instanceof DtGranularEditor<?> granular)
            hookEditor(granular);
    }

    private void hookEditor(DtGranularEditor<?> editor)
    {
        if (editor == null)
            return;
        boolean first = hookedEditors.add(editor);
        if (first)
        {
            editor.addPageChangedListener((PageChangedEvent event) ->
            {
                IFormPage hinted = event.getSelectedPage() instanceof IFormPage form ? form : null;
                scheduleInstall(editor, 0, hinted);
            });
        }
        scheduleInstall(editor, 0, null);
    }

    private static void scheduleInstall(DtGranularEditor<?> editor, int attempt, IFormPage hintedPage)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            try
            {
                if (install(editor, hintedPage))
                    return;
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "install", e); //$NON-NLS-1$
            }
            scheduleInstall(editor, attempt + 1, hintedPage);
        });
    }

    private static boolean install(DtGranularEditor<?> editor, IFormPage hintedPage)
    {
        IFormPage active = editor.getActivePageInstance();
        IFormPage page = isPredefinedPage(hintedPage) ? hintedPage : active;
        if (!isPredefinedPage(page))
            return true;

        TreeViewer viewer = findTreeViewer(page);
        if (viewer == null)
            return false;

        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || !tree.getHeaderVisible() || tree.getColumnCount() < 3)
            return false;

        if (!isPredefinedTree(tree, viewer))
            return false;

        if (viewer.getInput() == null && tree.getItemCount() == 0)
            return false;

        EObject mdObject = editor.getModel();
        tree.setData(MD_OBJECT_KEY, mdObject);

        SortState state = sortState(tree);
        if (!Boolean.TRUE.equals(tree.getData(HOOK_MARKER)))
        {
            installHeaderListeners(tree, viewer, state);
            // Активная ячейка — та, по которой кликнули (в любой колонке); Ctrl+C копирует её текст,
            // штатная команда в этом дереве в буфер ничего не кладёт.
            FormTreeInteraction interaction = FormTreeInteraction.install(tree, viewer);
            CopyCommandSupport.wireCopyOverride(tree, () -> copyActiveCellText(tree, interaction));
            tree.setData(HOOK_MARKER, Boolean.TRUE);
        }

        if (!state.userChosen)
        {
            state.column = SortColumn.NAME;
            state.ascending = true;
        }
        applySort(viewer, tree, state);
        installFilter(page, viewer, tree);
        return true;
    }

    private static boolean copyActiveCellText(Tree tree, FormTreeInteraction interaction)
    {
        if (tree == null || tree.isDisposed())
            return false;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0 || selection[0].isDisposed())
            return false;
        int column = interaction != null ? interaction.activeColumn() : 0;
        String text = selection[0].getText(column);
        if (text == null || text.isEmpty())
            return false;
        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
        return true;
    }

    /**
     * Многословный фильтр вместо штатного {@code DtTreeViewFilter}: все слова должны встретиться в
     * «Имя» / «Код» / «Наименование» строки; папка видна, если подходит сама или кто-то из потомков.
     */
    private static void installFilter(IFormPage page, TreeViewer viewer, Tree tree)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled() || Boolean.TRUE.equals(tree.getData(FILTER_MARKER)))
            return;
        SearchBox searchBox = findSearchBox(page.getPartControl(), 0);
        if (searchBox == null || searchBox.isDisposed())
            return;
        tree.setData(FILTER_MARKER, Boolean.TRUE);

        NameCodeFilter filter = new NameCodeFilter();
        int[] generation = { 0 };
        Runnable schedule = () ->
        {
            if (searchBox.isDisposed())
                return;
            int current = ++generation[0];
            searchBox.getDisplay().asyncExec(() ->
            {
                if (!searchBox.isDisposed() && current == generation[0])
                    applyFilter(viewer, filter, searchBox.getText());
            });
        };
        searchBox.setToolTipText(FilterInputBox.FLAT_FILTER_TOOLTIP + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
        searchBox.setMinimumSearchTextLength(0);
        searchBox.setJobScheduleDelay(0);
        FilterInputBox.attachHistory(searchBox, FilterInputBox.Scope.PREDEFINED_DATA);
        searchBox.setSearchListener(new SearchBox.ISearchListener()
        {
            @Override
            public void performSearch(String text, IProgressMonitor monitor)
            {
                // История / Enter; живой ввод — Modify (Job SearchBox глотает последний символ).
                schedule.run();
            }
        });
        searchBox.setRunSearchOnTextChange(false);
        searchBox.addModifyListener(e -> schedule.run());
        FilterInputBoxListNavigation.installTreeNavigation(searchBox, tree,
            () -> MdEditorTreeHook.pressEnterOnTree(tree));
        installSelectionDiag(page, viewer, tree);
        String initial = searchBox.getText();
        if (initial != null && !initial.trim().isEmpty())
            applyFilter(viewer, filter, initial);
    }

    // #region agent log — временная диагностика issue 687: «Свойства» не видят смену строки из поля фильтра
    private static final String DIAG_TOPIC = "predefined-filter-selection"; //$NON-NLS-1$

    private static void installSelectionDiag(IFormPage page, TreeViewer viewer, Tree tree)
    {
        tree.addListener(SWT.Selection, event -> Global.tempLog(DIAG_TOPIC, "SWT.Selection item=" //$NON-NLS-1$
            + (event.item instanceof org.eclipse.swt.widgets.TreeItem item ? item.getText() : String.valueOf(event.item))
            + " focus=" + diagFocus(tree))); //$NON-NLS-1$
        viewer.addSelectionChangedListener(event ->
        {
            StringBuilder listeners = new StringBuilder();
            if (Global.getField(viewer, "selectionChangedListeners") instanceof org.eclipse.core.runtime.ListenerList<?> list) //$NON-NLS-1$
                for (Object listener : list.getListeners())
                    listeners.append(listener.getClass().getName()).append(';');
            Global.tempLog(DIAG_TOPIC, "selectionChanged sel=" + diagSelection(event.getSelection()) //$NON-NLS-1$
                + " focus=" + diagFocus(tree) + " listeners=" + listeners); //$NON-NLS-1$
            tree.getDisplay().timerExec(400, () ->
            {
                if (tree.isDisposed())
                    return;
                ISelection editorSelection = null;
                ISelection windowSelection = null;
                try
                {
                    org.eclipse.ui.IWorkbenchPartSite site = page.getEditor().getSite();
                    if (site.getSelectionProvider() != null)
                        editorSelection = site.getSelectionProvider().getSelection();
                    windowSelection = site.getWorkbenchWindow().getSelectionService().getSelection();
                    Global.tempLog(DIAG_TOPIC, "+400ms editorSel=" + diagSelection(editorSelection) //$NON-NLS-1$
                        + " windowSel=" + diagSelection(windowSelection) //$NON-NLS-1$
                        + " provider=" + (site.getSelectionProvider() != null //$NON-NLS-1$
                            ? site.getSelectionProvider().getClass().getName() : "null") //$NON-NLS-1$
                        + " activePart=" + site.getPage().getActivePart() //$NON-NLS-1$
                        + " focus=" + diagFocus(tree)); //$NON-NLS-1$
                }
                catch (RuntimeException e)
                {
                    Global.tempLogException(DIAG_TOPIC, "+400ms", e); //$NON-NLS-1$
                }
            });
        });
    }

    private static String diagFocus(Tree tree)
    {
        Control focus = tree.getDisplay().getFocusControl();
        return focus != null ? focus.getClass().getSimpleName() + '@' + Integer.toHexString(focus.hashCode()) : "null"; //$NON-NLS-1$
    }

    private static String diagSelection(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured))
            return String.valueOf(selection);
        StringBuilder text = new StringBuilder("[").append(structured.size()).append(']'); //$NON-NLS-1$
        for (Object element : structured.toList())
        {
            text.append(' ').append(element != null ? element.getClass().getSimpleName() : "null"); //$NON-NLS-1$
            if (element instanceof PredefinedDataItemViewModel item)
                text.append(':').append(nameText(item));
            else
                text.append(':').append(element);
        }
        return text.toString();
    }
    // #endregion

    private static void applyFilter(TreeViewer viewer, NameCodeFilter filter, String text)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        String pattern = text != null ? text.trim() : ""; //$NON-NLS-1$
        if (pattern.equals(filter.applied))
            return;
        filter.applied = pattern;
        filter.matcher = new SmartMatcher(pattern);
        // Текущую строку запоминаем до refresh: фильтр может её скрыть, а после очистки она должна вернуться.
        ISelection before = viewer.getSelection();
        installHighlight(viewer, pattern);
        for (ViewerFilter existing : viewer.getFilters())
            if (existing == filter || existing.getClass().getSimpleName().equals("DtTreeViewFilter")) //$NON-NLS-1$
                viewer.removeFilter(existing);
        if (!filter.matcher.isEmpty)
            viewer.addFilter(filter);
        else
            viewer.refresh();
        if (!filter.matcher.isEmpty)
            viewer.expandAll();
        if (!before.isEmpty())
            viewer.setSelection(before, true);
        if (!filter.matcher.isEmpty)
            FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(tree);
        else
            tree.showSelection();
    }

    /**
     * Окраска вхождений фильтра в «Имя» / «Код» / «Наименование»: штатные провайдеры колонок
     * оборачиваются один раз, дальше меняется только шаблон.
     */
    private static void installHighlight(TreeViewer viewer, String pattern)
    {
        Tree tree = viewer.getTree();
        for (int i = 0; i < 3 && i < tree.getColumnCount(); i++)
        {
            if (!(Global.invoke(viewer, "getViewerColumn", Integer.valueOf(i)) instanceof TreeViewerColumn column)) //$NON-NLS-1$
                continue;
            Object current = Global.invoke(column, "getLabelProvider"); //$NON-NLS-1$
            CellLabelHighlightWrapper wrapper = null;
            if (current instanceof CellLabelHighlightWrapper existing)
                wrapper = existing;
            else if (current instanceof CellLabelProvider provider)
            {
                wrapper = new CellLabelHighlightWrapper(provider);
                SmartMatchHighlight.enableColorsOnSelection(wrapper);
                column.setLabelProvider(wrapper);
            }
            if (wrapper != null)
                wrapper.setHighlightPattern(pattern);
        }
    }

    private static SearchBox findSearchBox(Control control, int depth)
    {
        if (control == null || control.isDisposed() || depth > 24)
            return null;
        if (control instanceof SearchBox box)
            return box;
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                SearchBox found = findSearchBox(child, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static final class NameCodeFilter
        extends ViewerFilter
    {
        SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$

        String applied = ""; //$NON-NLS-1$

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            return matches((TreeViewer)viewer, element, 0);
        }

        private boolean matches(TreeViewer viewer, Object element, int depth)
        {
            if (element == null || depth > 16)
                return false;
            if (element instanceof PredefinedDataItemViewModel item)
            {
                String row = nullToEmpty(nameText(item)) + ' ' + nullToEmpty(item.getCode()) + ' '
                    + nullToEmpty(item.getDescription());
                if (matcher.matches(row))
                    return true;
            }
            if (!(viewer.getContentProvider() instanceof ITreeContentProvider content))
                return false;
            for (Object child : content.getChildren(element))
                if (matches(viewer, child, depth + 1))
                    return true;
            return false;
        }
    }

    private static void installHeaderListeners(Tree tree, TreeViewer viewer, SortState state)
    {
        for (int i = 0; i < 3 && i < tree.getColumnCount(); i++)
        {
            TreeColumn column = tree.getColumn(i);
            if (column == null || column.isDisposed())
                continue;
            if (Boolean.TRUE.equals(column.getData(HEADER_LISTENER_KEY)))
                continue;
            column.setData(HEADER_LISTENER_KEY, Boolean.TRUE);
            SortColumn sortColumn = SortColumn.byIndex(i);
            if (sortColumn == null)
                continue;
            column.addListener(SWT.Selection, event ->
            {
                if (tree.isDisposed() || viewer.getControl().isDisposed())
                    return;
                sortByHeader(viewer, tree, sortColumn, column);
            });
        }
    }

    private static void sortByHeader(TreeViewer viewer, Tree tree, SortColumn column, TreeColumn swtColumn)
    {
        SortState state = sortState(tree);
        state.ascending = state.column == column ? !state.ascending : true;
        state.column = column;
        state.userChosen = true;
        applySort(viewer, tree, state);
    }

    private static void applySort(TreeViewer viewer, Tree tree, SortState state)
    {
        Object[] selection = captureSelection(viewer);
        EObject mdObject = tree.getData(MD_OBJECT_KEY) instanceof EObject eObject ? eObject : null;
        boolean foldersOnTop = isFoldersOnTop(mdObject);
        SortColumn column = state.column;
        boolean ascending = state.ascending;

        viewer.setComparator(new ViewerComparator()
        {
            @Override
            public int compare(Viewer v, Object e1, Object e2)
            {
                if (foldersOnTop)
                {
                    int folderOrder = Boolean.compare(isFolder(e2), isFolder(e1));
                    if (folderOrder != 0)
                        return folderOrder;
                }
                int cmp = SORT_COLLATOR.compare(sortKey(e1, column), sortKey(e2, column));
                return ascending ? cmp : -cmp;
            }
        });

        TreeColumn sortColumn = tree.getColumn(column.index);
        if (sortColumn != null && !sortColumn.isDisposed())
        {
            tree.setSortColumn(sortColumn);
            tree.setSortDirection(ascending ? SWT.UP : SWT.DOWN);
        }
        restoreSelection(viewer, selection);
    }

    private static Object[] captureSelection(TreeViewer viewer)
    {
        if (viewer == null)
            return new Object[0];
        IStructuredSelection selection = viewer.getStructuredSelection();
        return selection.isEmpty() ? new Object[0] : selection.toArray();
    }

    private static void restoreSelection(TreeViewer viewer, Object[] elements)
    {
        if (viewer == null || elements.length == 0)
            return;
        viewer.setSelection(new StructuredSelection(elements), true);
    }

    private static SortState sortState(Tree tree)
    {
        if (tree.getData(SORT_STATE_KEY) instanceof SortState existing)
            return existing;
        SortState state = new SortState();
        tree.setData(SORT_STATE_KEY, state);
        return state;
    }

    private static String sortKey(Object element, SortColumn column)
    {
        if (!(element instanceof PredefinedDataItemViewModel item))
            return element != null ? element.toString() : ""; //$NON-NLS-1$
        return switch (column)
        {
            case NAME -> nullToEmpty(nameText(item));
            case CODE -> nullToEmpty(item.getCode());
            case DESCRIPTION -> nullToEmpty(item.getDescription());
        };
    }

    private static String nameText(PredefinedDataItemViewModel item)
    {
        Object text = Global.invoke(item, "getText"); //$NON-NLS-1$
        return text instanceof String string ? string : null;
    }

    private static String nullToEmpty(String value)
    {
        return value != null ? value : ""; //$NON-NLS-1$
    }

    private static boolean isFolder(Object element)
    {
        return element instanceof PredefinedDataItemViewModel item && item.isFolder();
    }

    private static boolean isFoldersOnTop(EObject mdObject)
    {
        if (mdObject instanceof Catalog catalog)
            return catalog.isFoldersOnTop();
        if (mdObject instanceof ChartOfCharacteristicTypes chart)
            return chart.isFoldersOnTop();
        return false;
    }

    private static boolean isPredefinedTree(Tree tree, TreeViewer viewer)
    {
        if (Boolean.TRUE.equals(tree.getData(HOOK_MARKER)))
            return true;
        ViewerComparator comparator = viewer.getComparator();
        return comparator != null
            && comparator.getClass().getName().endsWith(PREDEFINED_COMPARATOR_SUFFIX);
    }

    private static boolean isPredefinedPage(IFormPage page)
    {
        if (page == null)
            return false;
        if (PAGE_ID.equals(page.getId()))
            return true;
        return page.getClass().getName().contains(PAGE_CLASS_SUFFIX);
    }

    private static TreeViewer findTreeViewer(IFormPage page)
    {
        Control root = page.getPartControl();
        if (root == null || root.isDisposed())
            return null;
        return findTreeViewer(root, 0);
    }

    private static TreeViewer findTreeViewer(Control control, int depth)
    {
        if (control == null || control.isDisposed() || depth > 24)
            return null;
        if (control.getData(DT_TREE_VIEWER_KEY) instanceof TreeViewer viewer)
            return viewer;
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                TreeViewer found = findTreeViewer(child, depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Collator createSortCollator()
    {
        Collator collator = Collator.getInstance(new Locale("ru")); //$NON-NLS-1$
        collator.setStrength(Collator.PRIMARY);
        return collator;
    }

    private enum SortColumn
    {
        NAME(0),
        CODE(1),
        DESCRIPTION(2);

        final int index;

        SortColumn(int index)
        {
            this.index = index;
        }

        static SortColumn byIndex(int index)
        {
            for (SortColumn column : values())
            {
                if (column.index == index)
                    return column;
            }
            return null;
        }
    }

    private static final class SortState
    {
        SortColumn column = SortColumn.NAME;

        boolean ascending = true;

        boolean userChosen;
    }
}
