package tormozit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.viewers.CellLabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;

/**
 * Иерархический фильтр ({@link SmartMatcher#matchesTree}) в окне EDT «Сравнение прав роли»
 * ({@code com._1c.g5.v8.dt.rights.compare.ui.RightsDialog}).
 *
 * <p>Фильтрует только верхний уровень дерева (узлы {@code *ObjectRightsNode*} — объекты МД
 * с подписями вида {@code Тип.Имя}); дочерние права всегда видимы, если виден родитель —
 * как штатный {@code SearchViewerFilter}.
 *
 * <p>Фильтр кладётся в слот {@code searchListener.filter} и применяется через
 * {@code refreshFilters()}, чтобы не теряться при смене фильтра сравнения/объектов
 * ({@code viewer.setFilters(...)}).
 */
public final class RightsDialogFilterHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.rightsDialogFilterPatched"; //$NON-NLS-1$
    private static final String HIGHLIGHT_KEY = "tormozit.rightsDialogHighlights"; //$NON-NLS-1$
    private static final String TREE_WATCH_KEY = "tormozit.rightsDialogTreeWatch"; //$NON-NLS-1$
    private static final String COLUMN_VIEWER_KEY = "org.eclipse.jface.columnViewer"; //$NON-NLS-1$
    private static final String DIALOG_MARKER = "RightsDialog"; //$NON-NLS-1$
    private static final String TITLE_RU = "Сравнение прав роли"; //$NON-NLS-1$
    private static final String TITLE_EN = "Role's rights comparison"; //$NON-NLS-1$
    private static final String LAST_PAT_KEY = "tormozit.rightsDialogLastPattern"; //$NON-NLS-1$
    private static final String FILTER_SELECTION_KEY = "tormozit.rightsDialogFilterSelection"; //$NON-NLS-1$
    private static final String FILTER_SELECTION_PATH_KEY = "tormozit.rightsDialogFilterSelectionPath"; //$NON-NLS-1$
    private static final String FILTER_SELECTION_INDEX_KEY = "tormozit.rightsDialogFilterSelectionIndex"; //$NON-NLS-1$
    private static final String FILTER_EXPANDED_KEY = "tormozit.rightsDialogFilterExpanded"; //$NON-NLS-1$
    private static final String SELECTION_TRACKER_KEY = "tormozit.rightsDialogSelectionTracker"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = new Listener()
        {
            @Override
            public void handleEvent(Event event)
            {
                if (!(event.widget instanceof Shell shell))
                    return;
                if (shell.isDisposed())
                    return;
                onShellEvent(display, shell);
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    /**
     * На дереве сравнения конфигураций — короткий опрос shell после клика (шестерёнка / dblclick).
     */
    static void attachCompareTreeWatch(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(TREE_WATCH_KEY)))
            return;
        tree.setData(TREE_WATCH_KEY, Boolean.TRUE);

        Display display = tree.getDisplay();
        tree.addListener(SWT.MouseDown, event -> {
            if (event.button == 1)
                scheduleShellScanBurst(display, 8000);
        });
    }

    /**
     * Во время блокирующего {@code editMergeSettings}/{@code RightsDialog.open()} на UI-потоке
     * периодически ищем shell диалога и патчим SearchBox.
     */
    static void runWhileBlocking(Runnable action)
    {
        if (action == null)
            return;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            action.run();
            return;
        }

        Display display = Display.getDefault();
        final boolean[] running = { true };
        Runnable poller = new Runnable()
        {
            @Override
            public void run()
            {
                if (!running[0])
                    return;
                scanAllShells(display);
                if (running[0])
                    display.timerExec(30, this);
            }
        };
        display.timerExec(0, poller);
        try
        {
            action.run();
        }
        finally
        {
            running[0] = false;
            scanAllShells(display);
        }
    }

    private static void onShellEvent(Display display, Shell shell)
    {
        if (shell.getData(PATCHED_KEY) != null)
            return;
        if (!isRightsDialogShell(shell))
            return;
        schedulePatchAttempt(display, shell, 0);
    }

    private static void scheduleShellScanBurst(Display display, long durationMs)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        long end = System.currentTimeMillis() + durationMs;
        Runnable scan = new Runnable()
        {
            @Override
            public void run()
            {
                scanAllShells(display);
                if (System.currentTimeMillis() < end && !display.isDisposed())
                    display.timerExec(50, this);
            }
        };
        display.timerExec(0, scan);
    }

    private static void scanAllShells(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        for (Shell shell : display.getShells())
        {
            if (shell == null || shell.isDisposed())
                continue;
            if (shell.getData(PATCHED_KEY) != null)
                continue;
            if (!isRightsDialogShell(shell))
                continue;
            schedulePatchAttempt(display, shell, 0);
        }
    }

    private static boolean isRightsDialogShell(Shell shell)
    {
        Object dialog = resolveDialog(shell);
        if (dialog != null)
            return true;
        String title = shell.getText();
        if (title != null && (title.contains(TITLE_RU) || title.contains(TITLE_EN)))
            return true;
        return findSearchBox(shell) != null && hasRightsDialogTree(shell);
    }

    private static boolean hasRightsDialogTree(Shell shell)
    {
        Control[] children = shell.getChildren();
        if (children == null)
            return false;
        for (Control child : children)
        {
            if (findTreeInHierarchy(child) != null)
                return true;
        }
        return false;
    }

    private static Control findTreeInHierarchy(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if ("Tree".equals(root.getClass().getSimpleName())) //$NON-NLS-1$
            return root;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Control tree = findTreeInHierarchy(child);
                if (tree != null)
                    return tree;
            }
        }
        return null;
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;

        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () -> {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatchDialog(shell))
                return;
            if (attempt < 25)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    private static Object resolveDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && data.getClass().getName().contains(DIALOG_MARKER))
            return data;
        Object win = shell.getData("org.eclipse.jface.window.Window"); //$NON-NLS-1$
        if (win != null && win.getClass().getName().contains(DIALOG_MARKER))
            return win;

        SearchBox searchBox = findSearchBox(shell);
        if (searchBox != null)
        {
            Object fromListener = dialogFromSearchBox(searchBox);
            if (fromListener != null)
                return fromListener;
        }
        return null;
    }

    private static Object dialogFromSearchBox(SearchBox searchBox)
    {
        Object listener = Global.getField(searchBox, "searchListener"); //$NON-NLS-1$
        if (listener == null)
            return null;
        Object outer = Global.getField(listener, "this$0"); //$NON-NLS-1$
        if (outer != null && outer.getClass().getName().contains(DIALOG_MARKER))
            return outer;
        return null;
    }

    private static boolean tryPatchDialog(Shell shell)
    {
        try
        {
            Object dialog = resolveDialog(shell);
            SearchBox searchBox = findSearchBox(shell);
            Object searchListener = dialog != null
                    ? Global.getField(dialog, "searchListener") //$NON-NLS-1$
                    : null;

            if (dialog == null || searchListener == null || searchBox == null || searchBox.isDisposed())
                return false;

            synchronized (shell)
            {
                if (shell.getData(PATCHED_KEY) != null)
                    return true;
                shell.setData(PATCHED_KEY, Boolean.TRUE);
            }

            final Object dialogRef = dialog;
            final Object searchListenerRef = searchListener;
            final int[] applyGeneration = { 0 };

            searchBox.setToolTipText(FilterInputBox.HIERARCHICAL_FILTER_TOOLTIP
                    + "\nCtrl+↓ — история запросов."); //$NON-NLS-1$
            searchBox.setMinimumSearchTextLength(0);
            searchBox.setJobScheduleDelay(0);
            FilterInputBox.attachHistory(searchBox, FilterInputBox.Scope.RIGHTS_DIALOG);
            searchBox.setSearchListener(new SearchBox.ISearchListener()
            {
                @Override
                public void performSearch(String text, IProgressMonitor monitor)
                {
                    scheduleApplyPattern(searchBox, dialogRef, searchListenerRef, text, applyGeneration);
                }
            });
            searchBox.setRunSearchOnTextChange(true);

            TreeViewer viewer = viewerFromDialog(dialog);
            if (viewer != null)
            {
                shell.setData(HIGHLIGHT_KEY, installColumnHighlights(viewer));
                installSelectionTracker(viewer);
                FilterInputBoxListNavigation.installTreeNavigation(searchBox, viewer.getTree());
            }

            String initial = searchBox.getText();
            if (initial != null && !initial.isEmpty())
                applyPattern(dialogRef, searchListenerRef, initial);

            return true;
        }
        catch (Exception e)
        {
            shell.setData(PATCHED_KEY, null);
            Global.logError("RightsDialogFilter", "patch", e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    /**
     * {@code SearchBox} по умолчанию гоняет {@code performSearch} через {@code Job}; с
     * {@code setRunSearchOnUiThread(true)} job блокируется на тяжёлом {@link #applyPattern}
     * (до секунд), и выбор из истории «залипает». Применяем на UI через {@code asyncExec}
     * с поколением — устаревшие отложенные вызовы отбрасываются.
     */
    private static void scheduleApplyPattern(SearchBox searchBox, Object dialog, Object searchListener,
            String text, int[] applyGeneration)
    {
        if (searchBox == null || searchBox.isDisposed() || applyGeneration == null)
            return;
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;

        int generation = ++applyGeneration[0];
        display.asyncExec(() -> {
            if (searchBox.isDisposed())
                return;
            if (generation != applyGeneration[0])
                return;
            applyPattern(dialog, searchListener, text);
        });
    }

    private static void applyPattern(Object dialog, Object searchListener, String text)
    {
        String pattern = text != null ? text.trim() : ""; //$NON-NLS-1$
        TreeViewer viewer = viewerFromDialog(dialog);
        Tree tree = viewer != null ? viewer.getTree() : null;

        String lastPattern = tree != null && tree.getData(LAST_PAT_KEY) instanceof String
                ? (String) tree.getData(LAST_PAT_KEY) : ""; //$NON-NLS-1$
        if (pattern.equals(lastPattern))
            return;

        boolean filtering = !pattern.isEmpty();
        boolean clearingFilter = !filtering && !lastPattern.isEmpty();
        boolean startingFilter = filtering && lastPattern.isEmpty();

        ISelection savedSelection = null;
        String[] savedPathKey = null;
        int[] savedIndexKey = null;
        Object[] savedExpanded = null;
        if (filtering && viewer != null)
            rememberSelection(tree, viewer, viewer.getSelection());
        if (startingFilter && tree != null)
            tree.setData(FILTER_EXPANDED_KEY, snapshotExpandedElements(viewer));

        if (clearingFilter && viewer != null)
        {
            savedExpanded = tree != null ? expandedSnapshot(tree) : null;
            ISelection live = viewer.getSelection();
            if (live instanceof IStructuredSelection ss && !ss.isEmpty())
            {
                savedSelection = live;
                Object element = ss.getFirstElement();
                savedIndexKey = captureSelectionIndices(viewer, element);
                savedPathKey = captureSelectionPath(viewer, element);
            }
            if (savedSelection == null && tree != null)
            {
                Object pending = tree.getData(FILTER_SELECTION_KEY);
                if (pending instanceof ISelection ps && !ps.isEmpty())
                    savedSelection = ps;
                Object pathObj = tree.getData(FILTER_SELECTION_PATH_KEY);
                if (pathObj instanceof String[] path && path.length > 0)
                    savedPathKey = path;
                Object indexObj = tree.getData(FILTER_SELECTION_INDEX_KEY);
                if (indexObj instanceof int[] index && index.length > 0)
                    savedIndexKey = index;
                if (savedSelection instanceof IStructuredSelection ss && !ss.isEmpty())
                {
                    Object element = ss.getFirstElement();
                    if (savedIndexKey == null)
                        savedIndexKey = captureSelectionIndices(viewer, element);
                    if (savedPathKey == null)
                        savedPathKey = captureSelectionPath(viewer, element);
                }
            }
        }

        updateHighlightPattern(dialog, pattern);
        ViewerFilter filter = pattern.isEmpty() ? null : new TopLevelSmartFilter(pattern);
        Global.setField(searchListener, "filter", filter); //$NON-NLS-1$
        Global.invoke(dialog, "refreshFilters"); //$NON-NLS-1$

        if (clearingFilter)
        {
            clearViewerHighlights(viewer);
            if (savedSelection != null)
            {
                final String[] pathKey = savedPathKey;
                int[] indexKey = savedIndexKey;
                if (indexKey == null && pathKey != null)
                    indexKey = deriveIndicesFromPath(viewer, pathKey);
                final int[] indexKeyFinal = indexKey;
                final Object[] expanded = savedExpanded;
                scheduleRestoreAttempts(viewer, pathKey, indexKeyFinal, expanded);
            }
        }
        else if (viewer != null)
        {
            final TreeViewer highlightViewer = viewer;
            Display display = tree != null ? tree.getDisplay() : null;
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> refreshViewerHighlights(highlightViewer));
            else
                refreshViewerHighlights(viewer);
        }

        if (tree != null && !tree.isDisposed())
        {
            tree.setData(LAST_PAT_KEY, pattern);
            if (clearingFilter)
            {
                tree.setData(FILTER_SELECTION_KEY, null);
                tree.setData(FILTER_SELECTION_PATH_KEY, null);
                tree.setData(FILTER_SELECTION_INDEX_KEY, null);
                tree.setData(FILTER_EXPANDED_KEY, null);
            }
        }
    }

    private static void installSelectionTracker(TreeViewer viewer)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(SELECTION_TRACKER_KEY)))
            return;
        tree.setData(SELECTION_TRACKER_KEY, Boolean.TRUE);
        viewer.addSelectionChangedListener(event -> {
            if (tree.isDisposed())
                return;
            String lastPattern = tree.getData(LAST_PAT_KEY) instanceof String
                    ? (String) tree.getData(LAST_PAT_KEY) : ""; //$NON-NLS-1$
            if (lastPattern.isEmpty())
                return;
            ISelection selection = event.getSelection();
            rememberSelection(tree, viewer, selection);
        });
    }

    private static void rememberSelection(Tree tree, TreeViewer viewer, ISelection selection)
    {
        if (tree == null || viewer == null || !(selection instanceof IStructuredSelection ss) || ss.isEmpty())
            return;
        tree.setData(FILTER_SELECTION_KEY, selection);
        String[] pathKey = captureSelectionPath(viewer, ss.getFirstElement());
        if (pathKey != null && pathKey.length > 0)
            tree.setData(FILTER_SELECTION_PATH_KEY, pathKey);
        int[] indexKey = captureSelectionIndices(viewer, ss.getFirstElement());
        if (indexKey != null && indexKey.length > 0)
            tree.setData(FILTER_SELECTION_INDEX_KEY, indexKey);
    }

    private static Object[] snapshotExpandedElements(TreeViewer viewer)
    {
        if (viewer == null)
            return null;
        Object[] expanded = viewer.getExpandedElements();
        if (expanded == null || expanded.length == 0)
            return null;
        return expanded.clone();
    }

    private static Object[] expandedSnapshot(Tree tree)
    {
        if (tree == null)
            return null;
        Object data = tree.getData(FILTER_EXPANDED_KEY);
        return data instanceof Object[] expanded ? expanded : null;
    }

    private static void scheduleRestoreAttempts(TreeViewer viewer, String[] pathKey, int[] indexKey,
            Object[] expandedElements)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        restoreSelection(viewer, pathKey, indexKey, expandedElements);
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        int[] delays = { 30, 80, 150 };
        for (int delay : delays)
            display.timerExec(delay, () -> restoreSelection(viewer, pathKey, indexKey, expandedElements));
    }

    private static void restoreSelection(TreeViewer viewer, String[] pathKey, int[] indexKey,
            Object[] expandedElements)
    {
        if (viewer == null)
            return;
        ResolvedTarget resolved = resolveRestoreTarget(viewer, pathKey, indexKey);
        if (resolved == null || resolved.element == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;

        Set<Object> toExpand = new LinkedHashSet<>();
        if (expandedElements != null)
        {
            for (Object expanded : expandedElements)
                toExpand.add(expanded);
        }
        if (resolved.path != null)
        {
            for (int i = 0; i < resolved.path.size() - 1; i++)
                toExpand.add(resolved.path.get(i));
        }
        else
            addAncestorChain(viewer, resolved.element, toExpand);

        tree.setRedraw(false);
        try
        {
            if (resolved.path != null && resolved.path.size() > 1)
                viewer.expandToLevel(new TreePath(resolved.path.toArray()), 0);
            else
            {
                for (Object ancestor : toExpand)
                    viewer.setExpandedState(ancestor, true);
            }

            TreeItem item = findTreeItemForPath(tree, resolved);
            if (item == null)
                item = findTreeItemByLabels(tree, pathKey);
            if (item != null)
            {
                tree.setSelection(item);
                tree.showItem(item);
                fireTreeSelection(tree, item);
            }
            viewer.setSelection(selectionForResolvedTarget(resolved), true);
            if (item != null)
                tree.showItem(item);
        }
        finally
        {
            tree.setRedraw(true);
        }
    }

    private static void fireTreeSelection(Tree tree, TreeItem item)
    {
        if (tree == null || tree.isDisposed() || item == null || item.isDisposed())
            return;
        Event event = new Event();
        event.widget = tree;
        event.item = item;
        tree.notifyListeners(SWT.Selection, event);
    }

    private static TreeItem findTreeItemForPath(Tree tree, ResolvedTarget resolved)
    {
        if (tree == null || tree.isDisposed() || resolved == null)
            return null;
        if (resolved.path != null && !resolved.path.isEmpty())
        {
            TreeItem[] level = tree.getItems();
            TreeItem current = null;
            for (int i = 0; i < resolved.path.size(); i++)
            {
                current = findTreeItemAmongSiblings(level, resolved.path.get(i));
                if (current == null)
                    break;
                if (i < resolved.path.size() - 1)
                {
                    if (!current.getExpanded())
                        current.setExpanded(true);
                    level = current.getItems();
                }
            }
            if (current != null && !current.isDisposed())
                return current;
        }
        return findTreeItemForElement(tree.getItems(), resolved.element);
    }

    private static TreeItem findTreeItemByLabels(Tree tree, String[] pathKey)
    {
        if (tree == null || tree.isDisposed() || pathKey == null || pathKey.length == 0)
            return null;
        TreeItem[] level = tree.getItems();
        TreeItem current = null;
        for (int i = 0; i < pathKey.length; i++)
        {
            current = null;
            for (TreeItem item : level)
            {
                if (item == null || item.isDisposed())
                    continue;
                if (labelsMatch(item.getText(), pathKey[i]))
                {
                    current = item;
                    break;
                }
            }
            if (current == null)
                return null;
            if (i < pathKey.length - 1)
            {
                if (!current.getExpanded())
                    current.setExpanded(true);
                level = current.getItems();
            }
        }
        return current;
    }

    private static TreeItem findTreeItemAmongSiblings(TreeItem[] items, Object element)
    {
        if (items == null || element == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            if (item.getData() == element || element.equals(item.getData()))
                return item;
        }
        return null;
    }

    private static TreeItem findTreeItemForElement(TreeItem[] items, Object element)
    {
        if (items == null || element == null)
            return null;
        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            Object data = item.getData();
            if (data == element || (data != null && data.equals(element)))
                return item;
            TreeItem nested = findTreeItemForElement(item.getItems(), element);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private static ISelection selectionForResolvedTarget(ResolvedTarget resolved)
    {
        if (resolved.path != null && resolved.path.size() > 1)
            return new TreeSelection(new TreePath(resolved.path.toArray()));
        return new StructuredSelection(resolved.element);
    }

    private static final class ResolvedTarget
    {
        private final Object element;
        private final List<Object> path;

        private ResolvedTarget(Object element, List<Object> path)
        {
            this.element = element;
            this.path = path;
        }
    }

    private static ResolvedTarget resolveRestoreTarget(TreeViewer viewer, String[] pathKey, int[] indexKey)
    {
        if (viewer == null)
            return null;
        if (indexKey != null && indexKey.length > 0)
        {
            List<Object> path = navigateByIndices(viewer, indexKey, true);
            if (path != null && !path.isEmpty())
                return new ResolvedTarget(path.get(path.size() - 1), path);
        }
        if (pathKey != null && pathKey.length > 0)
        {
            List<Object> path = navigateByLabels(viewer, pathKey, true);
            if (path != null && !path.isEmpty())
                return new ResolvedTarget(path.get(path.size() - 1), path);
        }
        return null;
    }

    private static List<Object> navigateByIndices(TreeViewer viewer, int[] indexKey, boolean expand)
    {
        if (viewer == null || indexKey == null || indexKey.length == 0)
            return null;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider tcp))
            return null;
        Object input = viewer.getInput();
        if (input == null)
            return null;

        List<Object> path = new ArrayList<>(indexKey.length);
        Object current = null;
        for (int i = 0; i < indexKey.length; i++)
        {
            Object[] siblings = i == 0 ? tcp.getElements(input) : tcp.getChildren(current);
            int idx = indexKey[i];
            if (idx < 0 || idx >= siblings.length)
                return null;
            current = siblings[idx];
            path.add(current);
            if (expand && i < indexKey.length - 1)
                viewer.setExpandedState(current, true);
        }
        return path;
    }

    private static List<Object> navigateByLabels(TreeViewer viewer, String[] pathKey, boolean expand)
    {
        if (viewer == null || pathKey == null || pathKey.length == 0)
            return null;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider tcp))
            return null;
        Object input = viewer.getInput();
        if (input == null)
            return null;

        List<Object> path = new ArrayList<>(pathKey.length);
        Object current = null;
        for (int i = 0; i < pathKey.length; i++)
        {
            Object[] siblings = i == 0 ? tcp.getElements(input) : tcp.getChildren(current);
            Object next = null;
            for (Object sibling : siblings)
            {
                if (labelsMatch(nodeLabel(sibling), pathKey[i]))
                {
                    next = sibling;
                    break;
                }
            }
            if (next == null)
                return null;
            current = next;
            path.add(current);
            if (expand && i < pathKey.length - 1)
                viewer.setExpandedState(current, true);
        }
        return path;
    }

    private static int[] deriveIndicesFromPath(TreeViewer viewer, String[] pathKey)
    {
        if (viewer == null || pathKey == null || pathKey.length == 0)
            return null;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider tcp))
            return null;
        Object input = viewer.getInput();
        if (input == null)
            return null;

        int[] indices = new int[pathKey.length];
        Object current = null;
        for (int i = 0; i < pathKey.length; i++)
        {
            Object[] siblings = i == 0 ? tcp.getElements(input) : tcp.getChildren(current);
            int idx = indexOfLabel(siblings, pathKey[i]);
            if (idx < 0)
                return null;
            indices[i] = idx;
            current = siblings[idx];
        }
        return indices;
    }

    private static int[] captureSelectionIndices(TreeViewer viewer, Object element)
    {
        List<Object> nodes = buildPathNodes(viewer, element);
        if (nodes == null || nodes.isEmpty())
            return null;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider tcp))
            return null;
        Object input = viewer.getInput();
        if (input == null)
            return null;

        int[] indices = new int[nodes.size()];
        Object parentNode = null;
        for (int i = 0; i < nodes.size(); i++)
        {
            Object target = nodes.get(i);
            Object[] siblings = i == 0 ? tcp.getElements(input) : tcp.getChildren(parentNode);
            int idx = indexOfElement(siblings, target);
            if (idx < 0)
                idx = indexOfLabel(siblings, nodeLabel(target));
            if (idx < 0)
                return null;
            indices[i] = idx;
            parentNode = siblings[idx];
        }
        return indices;
    }

    private static int indexOfLabel(Object[] siblings, String label)
    {
        if (siblings == null || label == null)
            return -1;
        for (int i = 0; i < siblings.length; i++)
        {
            if (labelsMatch(nodeLabel(siblings[i]), label))
                return i;
        }
        return -1;
    }

    private static int indexOfElement(Object[] siblings, Object target)
    {
        if (siblings == null || target == null)
            return -1;
        for (int i = 0; i < siblings.length; i++)
        {
            if (siblings[i] == target || (siblings[i] != null && siblings[i].equals(target)))
                return i;
        }
        return -1;
    }

    private static String[] captureSelectionPath(TreeViewer viewer, Object element)
    {
        List<Object> nodes = buildPathNodes(viewer, element);
        if (nodes == null || nodes.isEmpty())
            return null;
        String[] labels = new String[nodes.size()];
        for (int i = 0; i < nodes.size(); i++)
            labels[i] = nodeLabel(nodes.get(i));
        return labels;
    }

    private static List<Object> buildPathNodes(TreeViewer viewer, Object element)
    {
        if (viewer == null || element == null)
            return null;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider tcp))
            return null;

        List<Object> viaParent = new ArrayList<>();
        Object current = element;
        while (current != null)
        {
            viaParent.add(0, current);
            current = tcp.getParent(current);
        }
        if (viaParent.size() > 1)
            return viaParent;

        Object input = viewer.getInput();
        if (input == null)
            return List.of(element);
        for (Object root : tcp.getElements(input))
        {
            List<Object> path = new ArrayList<>();
            if (collectPath(tcp, root, element, path))
                return path;
        }
        return List.of(element);
    }

    private static String nodeLabel(Object node)
    {
        if (node instanceof IPartialModelNode pmn)
        {
            String main = pmn.getSideLabel(ComparisonSide.MAIN);
            if (main != null && !main.isEmpty())
                return main;
            String other = pmn.getSideLabel(ComparisonSide.OTHER);
            if (other != null && !other.isEmpty())
                return other;
        }
        return node != null ? String.valueOf(node) : ""; //$NON-NLS-1$
    }

    private static boolean labelsMatch(String actual, String expected)
    {
        return expected != null && expected.equals(actual);
    }

    private static boolean collectPath(ITreeContentProvider cp, Object node, Object target, List<Object> path)
    {
        path.add(node);
        if (node.equals(target))
            return true;
        if (!cp.hasChildren(node))
        {
            path.remove(path.size() - 1);
            return false;
        }
        for (Object child : cp.getChildren(node))
        {
            if (collectPath(cp, child, target, path))
                return true;
        }
        path.remove(path.size() - 1);
        return false;
    }

    private static void addAncestorChain(TreeViewer viewer, Object element, Set<Object> toExpand)
    {
        if (viewer == null || element == null || toExpand == null)
            return;
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider tcp))
            return;
        Object parent = tcp.getParent(element);
        boolean found = false;
        while (parent != null)
        {
            toExpand.add(parent);
            found = true;
            parent = tcp.getParent(parent);
        }
        if (found)
            return;

        Object input = viewer.getInput();
        if (input == null)
            return;
        for (Object root : tcp.getElements(input))
        {
            if (findPathTo(tcp, root, element, toExpand))
                break;
        }
    }

    private static boolean findPathTo(ITreeContentProvider cp, Object node, Object target, Set<Object> toExpand)
    {
        if (node == null)
            return false;
        if (node.equals(target))
            return true;
        if (!cp.hasChildren(node))
            return false;
        for (Object child : cp.getChildren(node))
        {
            if (findPathTo(cp, child, target, toExpand))
            {
                toExpand.add(node);
                return true;
            }
        }
        return false;
    }

    /**
     * При сбросе фильтра — только перерисовка подсветки на видимых строках.
     * {@code viewer.refresh(true)} после restore сносит выделение дочерних узлов
     * (lazy {@code ObjectRightsSupplier} пересоздаёт детей).
     */
    private static void clearViewerHighlights(TreeViewer viewer)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;

        tree.setRedraw(false);
        try
        {
            updateVisibleColumnLabels(viewer, tree.getItems());
        }
        finally
        {
            tree.setRedraw(true);
        }
    }

    /**
     * {@code refreshFilters()} в EDT зовёт {@code setFilters} → {@code refresh(false)}: дерево
     * перефильтровывается, но {@link CellLabelHighlightWrapper#update} не вызывается — на
     * переиспользованных {@code TreeItem} остаются {@code StyleRange} от прошлого паттерна.
     */
    private static void refreshViewerHighlights(TreeViewer viewer)
    {
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;

        tree.setRedraw(false);
        try
        {
            viewer.refresh(true);
            updateVisibleColumnLabels(viewer, tree.getItems());
            viewer.refresh(true);
        }
        finally
        {
            tree.setRedraw(true);
        }
    }

    private static void updateVisibleColumnLabels(TreeViewer viewer, TreeItem[] items)
    {
        if (viewer == null || items == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;

        for (TreeItem item : items)
        {
            if (item == null || item.isDisposed())
                continue;
            Object element = item.getData();
            if (element != null)
                viewer.update(element, null);
            updateVisibleColumnLabels(viewer, item.getItems());
        }
    }

    private static TreeViewer viewerFromDialog(Object dialog)
    {
        Object viewer = dialog != null ? Global.getField(dialog, "viewer") : null; //$NON-NLS-1$
        return viewer instanceof TreeViewer tv ? tv : null;
    }

    @SuppressWarnings("unchecked")
    private static void updateHighlightPattern(Object dialog, String pattern)
    {
        Shell shell = dialog != null ? (Shell) Global.invoke(dialog, "getShell") : null; //$NON-NLS-1$
        if (shell == null || shell.isDisposed())
            return;

        List<CellLabelHighlightWrapper> highlights =
                (List<CellLabelHighlightWrapper>) shell.getData(HIGHLIGHT_KEY);
        if (highlights == null)
        {
            TreeViewer viewer = viewerFromDialog(dialog);
            if (viewer == null)
                return;
            highlights = installColumnHighlights(viewer);
            shell.setData(HIGHLIGHT_KEY, highlights);
        }
        for (CellLabelHighlightWrapper wrapper : highlights)
            wrapper.setHighlightPattern(pattern);
    }

    private static List<CellLabelHighlightWrapper> installColumnHighlights(TreeViewer viewer)
    {
        List<CellLabelHighlightWrapper> result = new ArrayList<>();
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return result;

        int count = tree.getColumnCount();
        for (int i = 0; i < count; i++)
        {
            TreeViewerColumn column = resolveViewerColumn(viewer, tree, i);
            if (column == null)
                continue;

            Object lpObj = Global.invoke(column, "getLabelProvider"); //$NON-NLS-1$
            if (!(lpObj instanceof CellLabelProvider lp))
                continue;
            if (lp instanceof CellLabelHighlightWrapper existing)
            {
                existing.setTreeSectionHighlight(true);
                result.add(existing);
                continue;
            }

            CellLabelHighlightWrapper wrapper = new CellLabelHighlightWrapper(lp);
            wrapper.setTreeSectionHighlight(true);
            column.setLabelProvider(wrapper);
            result.add(wrapper);
        }
        return result;
    }

    private static TreeViewerColumn resolveViewerColumn(TreeViewer viewer, Tree tree, int index)
    {
        Object vc = Global.invoke(viewer, "getViewerColumn", Integer.valueOf(index)); //$NON-NLS-1$
        if (vc instanceof TreeViewerColumn tvc)
            return tvc;
        if (index >= 0 && index < tree.getColumnCount())
        {
            TreeColumn column = tree.getColumn(index);
            if (column != null)
            {
                Object colData = column.getData(COLUMN_VIEWER_KEY);
                if (colData instanceof TreeViewerColumn tvcFromData)
                    return tvcFromData;
            }
        }
        return null;
    }

    private static SearchBox findSearchBox(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof SearchBox sb)
            return sb;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                SearchBox found = findSearchBox(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /**
     * Как штатный {@code RightsDialog$SearchViewerFilter}: матч только для узлов объектов МД
     * (верхний уровень); остальные узлы (права) всегда проходят.
     */
    private static final class TopLevelSmartFilter extends ViewerFilter
    {
        private final SmartMatcher matcher;

        TopLevelSmartFilter(String pattern)
        {
            this.matcher = new SmartMatcher(pattern);
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element)
        {
            if (!isTopLevelObjectRights(element))
                return true;
            if (matcher.isEmpty)
                return true;
            if (!(element instanceof IPartialModelNode node))
                return true;
            return matchesSide(node, ComparisonSide.MAIN) || matchesSide(node, ComparisonSide.OTHER);
        }

        private boolean matchesSide(IPartialModelNode node, ComparisonSide side)
        {
            String label = node.getSideLabel(side);
            if (label == null || label.isEmpty())
                return false;
            return matcher.matchesTree(label);
        }

        private static boolean isTopLevelObjectRights(Object element)
        {
            if (element == null)
                return false;
            return element.getClass().getName().contains("ObjectRightsNode"); //$NON-NLS-1$
        }
    }
}
