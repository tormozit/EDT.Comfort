package tormozit;

import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * F2 «Показать коллекцию» в панелях «Выражения» и «Выражения встроенного языка».
 * Штатный EDT перехватывает F2 (rename watch) раньше workbench-команды; в «Переменные» привязка работает.
 */
public final class DebugViewsCollectionKeyHook implements IStartup
{
    private static final String BSL_EXPRESSIONS_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$

    private static volatile boolean filterInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed() || filterInstalled)
            return;
        display.addFilter(SWT.KeyDown, DebugViewsCollectionKeyHook::handleKeyDown);
        filterInstalled = true;
        DebugCollectionDebug.step("expressionsF2", "KeyDown filter installed"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void handleKeyDown(Event e)
    {
        if (e.type != SWT.KeyDown)
            return;
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        if (e.keyCode != SWT.F2)
            return;
        if ((e.stateMask & (SWT.MOD1 | SWT.MOD2 | SWT.MOD3 | SWT.MOD4)) != 0)
            return;
        if (!DebugSessionHelper.isDebugSuspended(null))
            return;
        if (DebugCollectionWindowRegistry.isCollectionWindowFocused())
            return;

        Display display = e.display;
        if (display == null || display.isDisposed())
            return;

        if (isInspectorKeyContext(display))
            return;

        AbstractDebugView debugView = activeExpressionView();
        if (debugView == null)
            return;

        Tree viewTree = treeOf(debugView.getViewer());
        if (viewTree == null || viewTree.isDisposed())
            return;

        Control focus = display.getFocusControl();
        if (focus == null || focus.isDisposed())
            return;
        if (focus != viewTree && !isDescendantOf(focus, viewTree))
        {
            if (isFocusInViewNonTreeText(focus, viewTree))
                return;
            return;
        }

        if (isCellEditorActive(debugView))
            return;

        if (DebugCollectionShowSupport.tryOpenFromDebugTree(viewTree, debugView))
        {
            e.doit = false;
            DebugCollectionDebug.step("expressionsF2", "opened " + viewId(debugView)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static boolean isInspectorKeyContext(Display display)
    {
        Shell active = display.getActiveShell();
        if (active != null && DebugInspectorHook.isInspectorShell(active))
            return true;
        Control focus = display.getFocusControl();
        if (focus != null)
        {
            Shell focusShell = focus.getShell();
            return focusShell != null && DebugInspectorHook.isInspectorShell(focusShell);
        }
        return false;
    }

    private static AbstractDebugView activeExpressionView()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IWorkbenchPart part = page.getActivePart();
            if (!(part instanceof AbstractDebugView debugView))
                return null;
            if (!isExpressionView(debugView))
                return null;
            return debugView;
        }
        catch (Exception e)
        {
            DebugCollectionDebug.problem("expressionsF2 activeView: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static boolean isExpressionView(AbstractDebugView debugView)
    {
        if (debugView == null || debugView.getSite() == null)
            return false;
        String id = debugView.getSite().getId();
        return BSL_EXPRESSIONS_VIEW_ID.equals(id)
            || IDebugUIConstants.ID_EXPRESSION_VIEW.equals(id);
    }

    private static boolean isFocusInViewNonTreeText(Control focus, Tree tree)
    {
        if (focus == null || focus.isDisposed() || tree == null || tree.isDisposed())
            return false;
        if (focus == tree || isDescendantOf(focus, tree))
            return false;
        if (!(focus instanceof StyledText) && !(focus instanceof Text))
            return false;
        Shell viewShell = tree.getShell();
        return viewShell != null && !viewShell.isDisposed() && isDescendantOf(focus, viewShell);
    }

    private static boolean isCellEditorActive(AbstractDebugView debugView)
    {
        if (debugView == null)
            return false;
        Viewer viewer = debugView.getViewer();
        if (viewer == null)
            return false;
        try
        {
            Object result = Global.invoke(viewer, "isCellEditorActive"); //$NON-NLS-1$
            return Boolean.TRUE.equals(result);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static Tree treeOf(Viewer viewer)
    {
        if (viewer instanceof AbstractTreeViewer treeViewer)
        {
            Control control = treeViewer.getControl();
            if (control instanceof Tree tree && !tree.isDisposed())
                return tree;
        }
        if (viewer == null)
            return null;
        try
        {
            Object treeObj = Global.invoke(viewer, "getTree"); //$NON-NLS-1$
            return treeObj instanceof Tree tree ? tree : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static boolean isDescendantOf(Control control, Control ancestor)
    {
        for (Control c = control; c != null; c = c.getParent())
        {
            if (c == ancestor)
                return true;
        }
        return false;
    }

    private static String viewId(AbstractDebugView debugView)
    {
        if (debugView == null || debugView.getSite() == null)
            return ""; //$NON-NLS-1$
        String id = debugView.getSite().getId();
        return id != null ? id : ""; //$NON-NLS-1$
    }
}
