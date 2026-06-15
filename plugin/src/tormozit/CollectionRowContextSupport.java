package tormozit;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * ПКМ строки «Коллекция»: «Показать в Выражения» и «Инспектировать».
 */
final class CollectionRowContextSupport
{
    private static final String WATCH_ITEM = "Показать в Выражения"; //$NON-NLS-1$
    private static final String INSPECT_ITEM = "Инспектировать"; //$NON-NLS-1$
    private static final String EXPRESSIONS_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$

    private CollectionRowContextSupport() {}

    static void showInExpressions(IBslVariable variable, IBslStackFrame frame)
    {
        String expr = resolveWatchExpression(variable);
        if (expr.isBlank())
        {
            ComfortCollectionDebug.problem("watch: empty expression"); //$NON-NLS-1$
            return;
        }
        IWatchExpression watch = DebugPlugin.getDefault().getExpressionManager().newWatchExpression(expr);
        DebugPlugin.getDefault().getExpressionManager().addExpression(watch);
        if (frame != null)
            watch.setExpressionContext(frame);
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null)
            {
                IWorkbenchPage page = window.getActivePage();
                if (page != null)
                    page.showView(EXPRESSIONS_VIEW_ID);
            }
        }
        catch (Exception e)
        {
            ComfortCollectionDebug.problem("show expressions: " + e.getMessage()); //$NON-NLS-1$
        }
        ComfortCollectionDebug.step("watch", expr); //$NON-NLS-1$
    }

    static void runInspect(
        IBslVariable variable,
        IBslStackFrame frame,
        String columnHeader,
        org.eclipse.swt.widgets.Table anchorTable,
        Point anchor,
        Shell keepVisibleShell,
        ComfortCollectionTableModel model,
        int logicalRow)
    {
        if (variable == null)
            return;

        org.eclipse.swt.widgets.Shell parent = inspectParentShell();
        if (parent == null || parent.isDisposed())
            return;

        if (keepVisibleShell != null && !keepVisibleShell.isDisposed())
            CollectionShellPinHelper.refresh(keepVisibleShell);

        IDebugMonitoringManager monitoringManager = Global.getOsgiService(IDebugMonitoringManager.class);
        BslInspectSupport.openInspectForVariable(
            parent,
            anchor,
            variable,
            frame,
            monitoringManager,
            keepVisibleShell,
            columnHeader,
            model,
            logicalRow,
            null);
    }

    static void runInspect(
        IBslVariable variable,
        IBslStackFrame frame,
        String columnHeader,
        org.eclipse.swt.widgets.Table anchorTable,
        Point anchor,
        Shell keepVisibleShell)
    {
        runInspect(variable, frame, columnHeader, anchorTable, anchor, keepVisibleShell, null, -1);
    }

    /** Обёртка для refresh pin без доступа к полю окна. */
    static final class CollectionShellPinHelper
    {
        private CollectionShellPinHelper() {}

        static void refresh(Shell shell)
        {
            if (shell == null || shell.isDisposed())
                return;
            Object data = shell.getData("tormozit.collectionShellPin"); //$NON-NLS-1$
            if (data instanceof CollectionShellPin pin)
                pin.refresh();
        }
    }

    static void runInspect(
        IBslVariable variable,
        IBslStackFrame frame,
        String columnHeader,
        org.eclipse.swt.widgets.Table anchorTable,
        Point anchor)
    {
        runInspect(variable, frame, columnHeader, anchorTable, anchor, null, null, -1);
    }

    private static org.eclipse.swt.widgets.Shell inspectParentShell()
    {
        try
        {
            org.eclipse.ui.IWorkbenchWindow window =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
            {
                org.eclipse.ui.IWorkbenchWindow[] windows =
                    org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows != null && windows.length > 0)
                    window = windows[0];
            }
            if (window != null && window.getShell() != null && !window.getShell().isDisposed())
            {
                org.eclipse.swt.widgets.Shell wb = window.getShell();
                if (!CollectionWindowRegistry.isCollectionShell(wb))
                    return wb;
            }
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
        if (display == null)
            return null;
        org.eclipse.swt.widgets.Shell active = display.getActiveShell();
        if (active != null && !active.isDisposed() && !CollectionWindowRegistry.isCollectionShell(active))
            return active;
        return null;
    }

    static String resolveWatchExpression(IBslVariable variable)
    {
        if (variable == null)
            return ""; //$NON-NLS-1$
        try
        {
            String toWatch = variable.toWatchExpression();
            if (toWatch != null && !toWatch.isBlank())
                return toWatch.trim();
        }
        catch (Exception ignored)
        {
            // fallback ниже
        }
        try
        {
            IBslValue value = variable.getValue();
            if (value != null)
            {
                BslValuePath path = value.getPath();
                if (path != null)
                {
                    String expr = path.getExpression();
                    if (expr != null && !expr.isBlank())
                        return expr.trim();
                    String text = path.toString();
                    if (text != null && !text.isBlank())
                        return text.trim();
                }
            }
        }
        catch (Exception e)
        {
            ComfortCollectionDebug.problem("watch path: " + e.getMessage()); //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    static String watchMenuLabel()
    {
        return WATCH_ITEM;
    }

    static String inspectMenuLabel()
    {
        return INSPECT_ITEM;
    }
}
