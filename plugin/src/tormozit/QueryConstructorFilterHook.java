package tormozit;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;

public final class QueryConstructorFilterHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.queryConstructorFilterPatched"; //$NON-NLS-1$
    private static final String TAG = "QConstructorFilter"; //$NON-NLS-1$
    private static final String QUERY_WIZARD_CLASS = "QueryWizard"; //$NON-NLS-1$
    private static final String TABLES_TAB_CLASS = "TablesAndFieldsTab"; //$NON-NLS-1$

    private static final Set<Shell> patchedShells =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Global.tempLog(TAG, "earlyStartup enabled=" + ComfortSettings.isReplaceListFiltersEnabled()); //$NON-NLS-1$
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Global.tempLog(TAG, "install display=" + System.identityHashCode(display)); //$NON-NLS-1$
        display.addFilter(SWT.Show, QueryConstructorFilterHook::handleShow);
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell))
            return;
        if (shell.isDisposed())
            return;
        if (shell.getData(PATCHED_KEY) != null)
            return;
        if (patchedShells.contains(shell))
            return;
        Object data = shell.getData();
        String dataClass = data != null ? data.getClass().getName() : "null"; //$NON-NLS-1$
        boolean match = isQueryWizardShell(shell);
        Global.tempLog(TAG, "Show shell title=\"" + shell.getText() + "\" data=" + dataClass + " match=" + match); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!match)
            return;
        scheduleTryPatch(shell, 0);
    }

    private static boolean isQueryWizardShell(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(QUERY_WIZARD_CLASS) && !name.contains("Control"); //$NON-NLS-1$
    }

    private static void scheduleTryPatch(Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 60;
        shell.getDisplay().timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell, attempt))
                return;
                if (attempt < 40)
                    scheduleTryPatch(shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell, int attempt)
    {
        Object dialog = shell.getData();
        if (dialog == null)
        {
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            return true;
        }

        Object qwc = getField(dialog, "queryWizardControl"); //$NON-NLS-1$
        if (qwc == null)
        {
            Global.tempLog(TAG, "try#" + attempt + " qwc=null dialog=" + dialog.getClass().getSimpleName()); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        Object tabsObj = getField(qwc, "tablesAndFieldsTab"); //$NON-NLS-1$
        if (tabsObj == null)
        {
            Global.tempLog(TAG, "try#" + attempt + " tabsObj=null"); //$NON-NLS-1$
            return false;
        }

        TreeViewer tree = getFieldAs(tabsObj, "availableTablesTree", TreeViewer.class); //$NON-NLS-1$
        if (tree == null)
        {
            Global.tempLog(TAG, "try#" + attempt + " tree=null tabsClass=" + tabsObj.getClass().getSimpleName()); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        SearchBox searchBox = findSearchBox(tree.getControl().getParent());
        if (searchBox == null)
        {
            searchBox = findSearchBox(tree.getControl());
        }
        if (searchBox == null)
        {
            Global.tempLog(TAG, "try#" + attempt + " searchBox=null parentClass=" + tree.getControl().getParent().getClass().getSimpleName()); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        shell.setData(PATCHED_KEY, Boolean.TRUE);
        patchedShells.add(shell);

        try
        {
            installFilter(tree, searchBox);
            Global.tempLog(TAG, "try#" + attempt + " PATCH OK"); //$NON-NLS-1$ //$NON-NLS-2$
            Debug.log("PATCH OK tree=" + tree.getClass().getSimpleName() //$NON-NLS-1$
                + " searchBox=" + searchBox.getClass().getSimpleName()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.tempLog(TAG, "try#" + attempt + " EXCEPTION: " + e); //$NON-NLS-1$ //$NON-NLS-2$
            Debug.problem("tryPatch exception: " + e); //$NON-NLS-1$
            Global.logError(Debug.TAG, "tryPatch", e); //$NON-NLS-1$
        }

        shell.addDisposeListener(e -> patchedShells.remove(shell));
        return true;
    }

    private static Object getField(Object obj, String name)
    {
        if (obj == null)
            return null;
        try
        {
            Field f = findField(obj.getClass(), name);
            if (f == null)
                return null;
            f.setAccessible(true);
            return f.get(obj);
        }
        catch (Exception e)
        {
            Debug.problem("getField " + name + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getFieldAs(Object obj, String name, Class<T> type)
    {
        Object val = getField(obj, name);
        return type.isInstance(val) ? (T) val : null;
    }

    private static Field findField(Class<?> clazz, String name)
    {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static SearchBox findSearchBox(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof SearchBox sb)
            return sb;
        if (root instanceof Composite comp)
        {
            for (Control child : comp.getChildren())
            {
                if (child.isDisposed())
                    continue;
                if (child instanceof SearchBox sb)
                    return sb;
                SearchBox found = findSearchBox(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void installFilter(TreeViewer tree, SearchBox searchBox)
    {
        Global.tempLog(TAG, "installFilter treeFilters=" + tree.getFilters().length); //$NON-NLS-1$
        ILabelProvider labelProvider = resolveLabelProvider(tree);
        if (labelProvider == null)
        {
            Debug.problem("installFilter: labelProvider=null"); //$NON-NLS-1$
            return;
        }

        SmartOutlineFilter filter = new SmartOutlineFilter(labelProvider, true, false);
        filter.setExpandTopLevelByDefault(false);

        Object originalListener = readSearchListener(searchBox);

        tree.addFilter(filter);

        setSearchListener(searchBox,
            (SearchBox.ISearchListener) (text, monitor) -> onSearch(text, tree, filter));

        Debug.log("installFilter filters=" + tree.getFilters().length //$NON-NLS-1$
            + " original=" + (originalListener != null
                ? originalListener.getClass().getSimpleName() : "null")); //$NON-NLS-1$
    }

    private static ILabelProvider resolveLabelProvider(TreeViewer tree)
    {
        Object lp = tree.getLabelProvider();
        if (lp instanceof ILabelProvider ilp)
            return ilp;
        try
        {
            java.lang.reflect.Method m = lp.getClass().getMethod("getStyledLabelProvider"); //$NON-NLS-1$
            Object styled = m.invoke(lp);
            if (styled instanceof ILabelProvider ilp)
                return ilp;
        }
        catch (Exception ignored)
        {
        }
        return new SimpleTreeLabelProvider(tree);
    }

    private static Object readSearchListener(SearchBox searchBox)
    {
        try
        {
            Field f = SearchBox.class.getDeclaredField("searchListener"); //$NON-NLS-1$
            f.setAccessible(true);
            return f.get(searchBox);
        }
        catch (Exception e)
        {
            Debug.problem("readSearchListener: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void setSearchListener(SearchBox searchBox, Object listener)
    {
        try
        {
            Field f = SearchBox.class.getDeclaredField("searchListener"); //$NON-NLS-1$
            f.setAccessible(true);
            f.set(searchBox, listener);
        }
        catch (Exception e)
        {
            Debug.problem("setSearchListener: " + e); //$NON-NLS-1$
        }
    }

    private static void onSearch(String text, TreeViewer tree, SmartOutlineFilter filter)
    {
        if (text == null || text.isEmpty())
        {
            tree.removeFilter(filter);
            tree.collapseAll();
            return;
        }
        boolean alreadyAdded = false;
        for (ViewerFilter f : tree.getFilters())
        {
            if (f == filter)
            {
                alreadyAdded = true;
                break;
            }
        }
        if (!alreadyAdded)
            tree.addFilter(filter);
        filter.refreshPattern(text);
        filter.applyTreeExpansion(tree);
    }

    private static final class SimpleTreeLabelProvider implements ILabelProvider
    {
        private final TreeViewer tree;

        SimpleTreeLabelProvider(TreeViewer tree)
        {
            this.tree = tree;
        }

        @Override
        public String getText(Object element)
        {
            if (element == null)
                return ""; //$NON-NLS-1$
            Object cp = tree.getContentProvider();
            if (cp instanceof org.eclipse.jface.viewers.ITreeContentProvider tcp)
            {
                Object input = tree.getInput();
                if (input != null)
                {
                    for (Object root : tcp.getElements(input))
                    {
                        String found = findText(tcp, root, element);
                        if (found != null)
                            return found;
                    }
                }
            }
            return element.toString();
        }

        private String findText(
            org.eclipse.jface.viewers.ITreeContentProvider tcp, Object node, Object target)
        {
            if (node == null)
                return null;
            if (node.equals(target))
                return node.toString();
            for (Object child : tcp.getChildren(node))
            {
                String found = findText(tcp, child, target);
                if (found != null)
                    return found;
            }
            return null;
        }

        @Override
        public org.eclipse.swt.graphics.Image getImage(Object element)
        {
            return null;
        }

        @Override
        public void addListener(org.eclipse.jface.viewers.ILabelProviderListener listener)
        {
        }

        @Override
        public void dispose()
        {
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return false;
        }

        @Override
        public void removeListener(org.eclipse.jface.viewers.ILabelProviderListener listener)
        {
        }
    }

    private static final class Debug
    {
        static final String TAG = "QConstructorFilter"; //$NON-NLS-1$

        private Debug()
        {
        }

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void log(String msg)
        {
            if (isEnabled())
                Global.log(TAG, msg);
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
