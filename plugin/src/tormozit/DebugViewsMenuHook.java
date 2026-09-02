package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;

/**
 * «Отладить объект ИР» в контекстном меню панелей «Переменные», «Выражения» и
 * «Выражения встроенного языка».
 * Только {@link SWT#MenuDetect} при правом клике — без partListener и без getViewer() на активации view.
 */
public final class DebugViewsMenuHook implements IStartup
{
    private static final String HOOK_MARKER_PREFIX = "tormozit.debugViewsMenuHooked."; //$NON-NLS-1$
    private static final String VARIABLE_VIEW_ID = "org.eclipse.debug.ui.VariableView"; //$NON-NLS-1$
    private static final String BSL_EXPRESSIONS_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Отладить объект ИР"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
        "Текущее выражение передается в отладочный инструмент в окно приложения ИР или предмета отладки" //$NON-NLS-1$
        + Global.pluginSignForTooltip();

    private enum ViewKind
    {
        VARIABLES,
        EXPRESSIONS
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MenuDetect, DebugViewsMenuHook::handleMenuDetect);
        DebugViewsDebug.log("install MenuDetect filter"); //$NON-NLS-1$
    }

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Tree tree))
            return;

        ActiveView active = activeDebugView(tree);
        if (active == null)
            return;

        Menu menu = tree.getMenu();
        if (menu == null || menu.isDisposed())
            return;

        String hookMarker = HOOK_MARKER_PREFIX + active.kind.name();
        if (Boolean.TRUE.equals(menu.getData(hookMarker)))
            return;

        menu.setData(hookMarker, Boolean.TRUE);
        menu.addMenuListener(buildMenuListener(active.view, active.kind));
        DebugViewsDebug.log("MenuDetect " + active.kind + ": MenuAdapter attached"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static MenuAdapter buildMenuListener(AbstractDebugView view, ViewKind kind)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                if (!DebugSessionHelper.isDebugSuspended(null))
                    return;

                ISelection selection = selectionOf(view);
                DebugViewsDebug.log("menuShown " + kind + " " + DebugViewsDebug.selectionBrief(selection)); //$NON-NLS-1$ //$NON-NLS-2$

                if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
                    return;
                if (!isSupportedSelection(kind, structured.getFirstElement()))
                    return;

                Menu menu = (Menu) e.widget;
                MenuItem item = new MenuItem(menu, SWT.PUSH);
                item.setText(ITEM_TEXT);
                item.setToolTipText(ITEM_TOOLTIP);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        runCommand(view, kind);
                    }
                });
                addedItems.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(addedItems);
                addedItems.clear();
                ((Menu) e.widget).getDisplay().asyncExec(() ->
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

    private static void runCommand(AbstractDebugView view, ViewKind kind)
    {
        ISelection selection = selectionOf(view);
        DebugViewsDebug.log("command run " + kind + " " + DebugViewsDebug.selectionBrief(selection)); //$NON-NLS-1$ //$NON-NLS-2$
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
            return;

        Object element = structured.getFirstElement();
        if (kind == ViewKind.VARIABLES && element instanceof IBslVariable variable)
        {
            DebugIRHandler.debugVariable(variable);
            return;
        }
        if (kind == ViewKind.EXPRESSIONS && element instanceof IWatchExpression watchExpr)
        {
            IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(null);
            IProject project = DebugIRHandler.getProjectFromStackFrame(frame);
            DebugIRHandler.debugWatchExpression(watchExpr, frame, project);
        }
    }

    private static boolean isSupportedSelection(ViewKind kind, Object element)
    {
        return kind == ViewKind.VARIABLES && element instanceof IBslVariable
            || kind == ViewKind.EXPRESSIONS && element instanceof IWatchExpression;
    }

    private static ISelection selectionOf(AbstractDebugView view)
    {
        ISelectionProvider provider = view.getSite().getSelectionProvider();
        return provider != null ? provider.getSelection() : null;
    }

    private record ActiveView(AbstractDebugView view, ViewKind kind) {}

    /**
     * Без {@code getViewer()} — только activePart + viewId, чтобы не трогать дерево VariableView
     * при переключении вкладок (гонка с EDT ChildrenCountUpdate).
     */
    private static ActiveView activeDebugView(Tree tree)
    {
        if (tree == null || tree.isDisposed())
            return null;
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

            ViewKind kind = viewKind(part);
            if (kind == null)
                return null;

            return new ActiveView(debugView, kind);
        }
        catch (Exception e)
        {
            DebugViewsDebug.logError("activeDebugView", e); //$NON-NLS-1$
            return null;
        }
    }

    private static ViewKind viewKind(IWorkbenchPart part)
    {
        if (part == null)
            return null;
        String id = part.getSite().getId();
        if (VARIABLE_VIEW_ID.equals(id))
            return ViewKind.VARIABLES;
        if (BSL_EXPRESSIONS_VIEW_ID.equals(id) || IDebugUIConstants.ID_EXPRESSION_VIEW.equals(id))
            return ViewKind.EXPRESSIONS;
        return null;
    }
}
