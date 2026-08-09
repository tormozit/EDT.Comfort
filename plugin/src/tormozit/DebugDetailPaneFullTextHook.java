package tormozit;

import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.IPartListener2;

/**
 * Подключает {@link DebugDetailPaneFullTextSupport} к штатным панелям «Переменные», «Выражения»
 * и «Выражения встроенного языка» — той же довыгрузке полного текста в панель деталей, что уже
 * работает в окне «Инспектор» ({@link DebugInspectorTreeEnhancement}). См. issue #258.
 */
public final class DebugDetailPaneFullTextHook implements IStartup
{
    private static final String BSL_EXPRESSIONS_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$
    private static final String DETAIL_PANE_FIELD = "fDetailPane"; //$NON-NLS-1$
    private static final String HOOKED_KEY = "tormozit.detailPaneFullTextHooked"; //$NON-NLS-1$

    private static volatile boolean windowListenerInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(DebugDetailPaneFullTextHook::install);
    }

    private static void install()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        if (windowListenerInstalled)
            return;
        windowListenerInstalled = true;
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window) { }

            @Override
            public void windowActivated(IWorkbenchWindow window) { }

            @Override
            public void windowDeactivated(IWorkbenchWindow window) { }
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                tryHook(ref.getPart(false));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                tryHook(ref.getPart(false));
            }
        });
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        for (IViewReference ref : page.getViewReferences())
        {
            if (ref != null)
                tryHook(ref.getPart(false));
        }
    }

    private static void tryHook(IWorkbenchPart part)
    {
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        if (!(part instanceof AbstractDebugView debugView) || !isTargetView(debugView))
            return;
        Viewer viewer = debugView.getViewer();
        if (viewer == null)
            return;
        Control control = viewer.getControl();
        if (!(control instanceof Tree tree) || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(HOOKED_KEY)))
            return;
        tree.setData(HOOKED_KEY, Boolean.TRUE);
        tree.addListener(SWT.Selection, e ->
            DebugDetailPaneFullTextSupport.onTreeSelectionChanged(tree, viewer, debugView, DETAIL_PANE_FIELD));
    }

    private static boolean isTargetView(AbstractDebugView view)
    {
        String id = view.getViewSite() != null ? view.getViewSite().getId() : null;
        return IDebugUIConstants.ID_VARIABLE_VIEW.equals(id)
            || IDebugUIConstants.ID_EXPRESSION_VIEW.equals(id)
            || BSL_EXPRESSIONS_VIEW_ID.equals(id);
    }
}
