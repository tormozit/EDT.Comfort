package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Пункт «Показать коллекцию F2» в контекстном меню дерева инспектора (попап и отдельное окно).
 */
public final class DebugInspectorCollectionMenuHook
{
    private static final String HOOK_KEY = "tormozit.debugInspectorCollectionMenuHook"; //$NON-NLS-1$
    private static final String COLLECTION_ITEM_TEXT = "Показать коллекцию"; //$NON-NLS-1$
    private static final String COLLECTION_TOOLTIP =
        "Открыть коллекцию в отдельном окне" //$NON-NLS-1$
            + Global.pluginSignForTooltip();

    private final Tree tree;
    private final Object viewer;
    private Listener menuDetectListener;
    private MenuAdapter menuAdapter;
    private int menuAttachAttempts;

    private DebugInspectorCollectionMenuHook(Tree tree, Object viewer)
    {
        this.tree = tree;
        this.viewer = viewer;
    }

    static void install(Tree tree, Object viewer)
    {
        if (tree == null || tree.isDisposed())
            return;
        if (tree.getData(HOOK_KEY) instanceof DebugInspectorCollectionMenuHook existing
            && existing.isAttached())
            return;

        DebugInspectorCollectionMenuHook hook = new DebugInspectorCollectionMenuHook(tree, viewer);
        if (!hook.attach())
            DebugInspectorDebug.step("collectionMenu", "attach failed"); //$NON-NLS-1$ //$NON-NLS-2$
        else
            tree.setData(HOOK_KEY, hook);
    }

    static void uninstall(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return;
        Object data = tree.getData(HOOK_KEY);
        if (data instanceof DebugInspectorCollectionMenuHook hook)
            hook.detach();
        tree.setData(HOOK_KEY, null);
    }

    /**
     * F2 в дереве инспектора: открыть коллекцию выделенной строки (как пункт контекстного меню),
     * не полагаясь на workbench-selection команды {@link ComfortCollectionShowHandler}.
     */
    static boolean tryOpenCollectionFromTree(Tree tree, Object viewer)
    {
        if (tree == null || tree.isDisposed() || !DebugSessionHelper.isDebugSuspended(null))
            return false;
        Object element = resolveSelectedElement(tree, viewer);
        if (!ComfortCollectionShowSupport.canOpenFrom(element))
            return false;
        ComfortCollectionShowSupport.openFromElement(element);
        return true;
    }

    /**
     * F2 из workbench при активном инспекторе: открыть коллекцию строки дерева
     * и не передавать клавишу в workbench-selection (WatchExpression корня).
     *
     * @return {@code true}, если инспектор в контексте — workbench-handler должен выйти
     */
    static boolean handleWorkbenchF2InInspector()
    {
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getCurrent();
        if (display == null || display.isDisposed())
            return false;

        org.eclipse.swt.widgets.Shell active = display.getActiveShell();
        org.eclipse.swt.widgets.Control focus = display.getFocusControl();
        org.eclipse.swt.widgets.Shell focusShell = focus != null ? focus.getShell() : null;
        boolean inspectorContext = DebugInspectorHook.isInspectorShell(active)
            || (focusShell != null && DebugInspectorHook.isInspectorShell(focusShell));
        if (!inspectorContext)
            return false;

        tryOpenCollectionFromActiveInspector();
        return true;
    }

    private static boolean tryOpenOnInspectorShell(org.eclipse.swt.widgets.Shell shell)
    {
        if (shell == null || shell.isDisposed() || !DebugInspectorHook.isInspectorShell(shell))
            return false;
        Tree tree = DebugInspectorTreeEnhancement.findInspectorTreeOnShell(shell);
        if (tree == null || tree.isDisposed())
            return false;
        Object viewer = DebugInspectorTreeEnhancement.viewerForTree(tree);
        return tryOpenCollectionFromTree(tree, viewer);
    }

    static void tryOpenCollectionFromActiveInspector()
    {
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        org.eclipse.swt.widgets.Shell active = display.getActiveShell();
        if (active != null)
            tryOpenOnInspectorShell(active);
        org.eclipse.swt.widgets.Control focus = display.getFocusControl();
        if (focus != null)
        {
            org.eclipse.swt.widgets.Shell focusShell = focus.getShell();
            if (focusShell != null && focusShell != active)
                tryOpenOnInspectorShell(focusShell);
        }
    }

    private static Object resolveSelectedElement(Tree tree, Object viewer)
    {
        Object fromTree = resolveFromTreeSelection(tree);
        Object fromViewer = resolveFromViewerSelection(viewer);
        if (fromTree != null)
            return fromTree;
        return fromViewer;
    }

    private static Object resolveFromTreeSelection(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return null;
        TreeItem item = selection[0];
        if (item == null || item.isDisposed())
            return null;
        return item.getData();
    }

    private static Object resolveFromViewerSelection(Object viewer)
    {
        if (viewer == null)
            return null;
        Object selectionObj = Global.invoke(viewer, "getSelection"); //$NON-NLS-1$
        if (!(selectionObj instanceof IStructuredSelection structured) || structured.isEmpty())
            return null;
        return structured.getFirstElement();
    }

    private boolean isAttached()
    {
        return tree != null && !tree.isDisposed() && tree.getData(HOOK_KEY) == this;
    }

    private boolean attach()
    {
        if (tree.isDisposed())
            return false;

        menuDetectListener = this::onMenuDetect;
        tree.addListener(SWT.MenuDetect, menuDetectListener);
        scheduleMenuListenerAttach();
        return true;
    }

    private void scheduleMenuListenerAttach()
    {
        Display display = tree.getDisplay();
        if (display == null || display.isDisposed())
            return;
        int delay = menuAttachAttempts == 0 ? 0 : 50 * menuAttachAttempts;
        display.timerExec(delay, this::ensureMenuListener);
    }

    private void ensureMenuListener()
    {
        if (!isAttached() || tree.isDisposed())
            return;
        Menu menu = tree.getMenu();
        if (menu == null || menu.isDisposed())
        {
            if (menuAttachAttempts < 8)
            {
                menuAttachAttempts++;
                scheduleMenuListenerAttach();
            }
            else
                DebugInspectorDebug.step("collectionMenu", "menu=null after retries"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (menuAdapter != null)
            return;

        menuAdapter = buildMenuListener();
        menu.addMenuListener(menuAdapter);
        DebugInspectorDebug.step("collectionMenu", "MenuAdapter attached"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void onMenuDetect(Event e)
    {
        if (e.widget != tree || tree.isDisposed())
            return;
        selectRowAt(new Point(e.x, e.y));
        ensureMenuListener();
    }

    private void selectRowAt(Point loc)
    {
        if (loc == null)
            return;
        TreeItem item = tree.getItem(loc);
        if (item == null || item.isDisposed())
            return;
        tree.setSelection(new TreeItem[] { item });
        tree.setFocus();
        applyViewerSelection(item);
    }

    private void applyViewerSelection(TreeItem item)
    {
        if (viewer == null || item == null)
            return;
        if (item.getData() == null)
            return;
        List<Object> path = new ArrayList<>();
        TreeItem current = item;
        while (current != null)
        {
            Object element = current.getData();
            if (element != null)
                path.add(0, element);
            current = current.getParentItem();
        }
        if (path.isEmpty())
            return;
        TreeSelection selection = new TreeSelection(new TreePath(path.toArray()));
        Global.invoke(viewer, "setSelection", selection); //$NON-NLS-1$
    }

    private MenuAdapter buildMenuListener()
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(1);

            @Override
            public void menuShown(MenuEvent e)
            {
                if (!DebugSessionHelper.isDebugSuspended(null))
                {
                    DebugInspectorDebug.step("collectionMenu", "skip not suspended"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                Object element = resolveSelectedElement();
                logMenuShownDiagnostics(element);
                if (!ComfortCollectionShowSupport.canOpenFrom(element))
                {
                    DebugInspectorDebug.step("collectionMenu", "skip not indexed " //$NON-NLS-1$ //$NON-NLS-2$
                        + elementBrief(element));
                    return;
                }

                Menu menu = (Menu) e.widget;
                MenuItem item = createShowCollectionMenuItem(menu, element);
                addedItems.add(item);
                DebugInspectorDebug.step("collectionMenu", "added item"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(addedItems);
                addedItems.clear();
                Menu menu = (Menu) e.widget;
                menu.getDisplay().asyncExec(() ->
                {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };
    }

    private Object resolveSelectedElement()
    {
        Object fromViewer = resolveFromViewerSelection(viewer);
        Object fromTree = resolveFromTreeSelection(tree);
        if (fromTree != null)
            return fromTree;
        return fromViewer;
    }

    private void logMenuShownDiagnostics(Object element)
    {
        String valueInfo = valueDiagnostics(element);
        boolean canOpen = ComfortCollectionShowSupport.canOpenFrom(element);
        DebugInspectorDebug.step("collectionMenu", "diag " + elementBrief(element) //$NON-NLS-1$ //$NON-NLS-2$
            + " value=" + valueInfo + " canOpen=" + canOpen); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String valueDiagnostics(Object element)
    {
        if (element instanceof IBslVariable variable)
        {
            try
            {
                IValue value = variable.getValue();
                return describeValue(value);
            }
            catch (Exception e)
            {
                return "getValueErr=" + e.getMessage(); //$NON-NLS-1$
            }
        }
        if (element instanceof IWatchExpression watch)
        {
            try
            {
                return describeValue(watch.getValue());
            }
            catch (Exception e)
            {
                return "watchValueErr=" + e.getMessage(); //$NON-NLS-1$
            }
        }
        if (element instanceof IValue value)
            return describeValue(value);
        return "n/a"; //$NON-NLS-1$
    }

    private static String describeValue(IValue value)
    {
        if (value == null)
            return "null"; //$NON-NLS-1$
        String kind = value.getClass().getSimpleName();
        if (value instanceof IBslValue bsl)
        {
            return kind + " evaluated=" + bsl.isEvaluated() //$NON-NLS-1$
                + " pending=" + bsl.isPending() //$NON-NLS-1$
                + " indexed=" + (value instanceof IBslIndexedValue); //$NON-NLS-1$
        }
        return kind + " indexed=" + (value instanceof IBslIndexedValue); //$NON-NLS-1$
    }

    private static String elementBrief(Object element)
    {
        if (element == null)
            return "null"; //$NON-NLS-1$
        return element.getClass().getSimpleName();
    }

    private static MenuItem createShowCollectionMenuItem(Menu menu, Object element)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH, 0);
        item.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
            COLLECTION_ITEM_TEXT,
            ComfortCollectionShowHandler.COMMAND_ID,
            ComfortCollectionShowHandler.BINDING_CONTEXT_ID));
        item.setToolTipText(COLLECTION_TOOLTIP);
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                ComfortCollectionShowSupport.openFromElement(element);
            }
        });
        return item;
    }

    private void detach()
    {
        if (tree != null && !tree.isDisposed())
        {
            if (menuDetectListener != null)
                tree.removeListener(SWT.MenuDetect, menuDetectListener);
            Menu menu = tree.getMenu();
            if (menu != null && !menu.isDisposed() && menuAdapter != null)
                menu.removeMenuListener(menuAdapter);
        }
        menuDetectListener = null;
        menuAdapter = null;
    }
}
