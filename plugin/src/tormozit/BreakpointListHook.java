package tormozit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.ui.IDebugView;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslExceptionBreakpoint;

/**
 * Панель «Точки останова», точки останова по исключению («Сообщение исключения: […]»):
 * <ul>
 * <li>команда «Свойства точки останова…» для них штатно открывает пустой информационный диалог
 * «Страницы свойств» (EDT не регистрирует {@code propertyPage} для {@link IBslExceptionBreakpoint},
 * только для {@code IBslLineBreakpoint}) — здесь вместо него открывается редактор текста фильтра;</li>
 * <li>двойной клик по такой строке делает то же самое.</li>
 * </ul>
 *
 * <p>{@link #firstLine} — первая строка уже разобранного EDT текста трассировки, общая для этого
 * редактора и для {@link ExceptionSelectionDialogHook}/{@link StacktracesViewInteractionHook}
 * (кнопка «Вставить из буфера» в штатном диалоге «Остановка по ошибке» и двойной клик по причине
 * в панели «Трассировки стека» — оба используют штатный {@code IStacktraceParser} EDT, не свой
 * разбор текста).
 */
public final class BreakpointListHook implements IStartup
{
    /** {@code WorkbenchMessages.PropertyDialog_messageTitle} (ru) — заголовок пустого инф. диалога. */
    private static final String NO_PROPERTY_PAGES_TITLE = "Страницы свойств"; //$NON-NLS-1$
    private static final String DOUBLE_CLICK_KEY = "tormozit.exceptionBreakpointDoubleClick"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Сообщение исключения"; //$NON-NLS-1$
    private static final String DIALOG_MESSAGE = "Текст фильтра сообщения исключения:"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            installPropertiesDialogInterceptor(Display.getDefault());
        });
    }

    // -----------------------------------------------------------------------
    // Двойной клик по строке точки останова по исключению
    // -----------------------------------------------------------------------

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
                tryInstallDoubleClick(ref.getView(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partActivated(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                tryInstallDoubleClick(ref != null ? ref.getPart(false) : null);
            }
        });
    }

    private static void tryInstallDoubleClick(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart view)
                || !IDebugUIConstants.ID_BREAKPOINT_VIEW.equals(view.getViewSite().getId())
                || !(part instanceof IDebugView debugView))
            return;

        Viewer viewer = debugView.getViewer();
        if (!(viewer instanceof StructuredViewer structuredViewer))
            return;
        if (structuredViewer.getControl() == null || structuredViewer.getControl().isDisposed())
            return;
        if (Boolean.TRUE.equals(structuredViewer.getControl().getData(DOUBLE_CLICK_KEY)))
            return;
        structuredViewer.getControl().setData(DOUBLE_CLICK_KEY, Boolean.TRUE);

        structuredViewer.addDoubleClickListener(event ->
        {
            IBslExceptionBreakpoint breakpoint = resolveSingleExceptionBreakpoint(event.getSelection());
            if (breakpoint != null)
                openFilterEditor(breakpoint, structuredViewer.getControl().getShell(),
                        resolveRowBoundsOnDisplay(structuredViewer));
        });
    }

    private static IBslExceptionBreakpoint resolveSingleExceptionBreakpoint(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
            return null;
        Object element = structured.getFirstElement();
        return element instanceof IBslExceptionBreakpoint bp ? bp : null;
    }

    // -----------------------------------------------------------------------
    // Пустой диалог «Страницы свойств» (команда «Свойства точки останова…»)
    // -----------------------------------------------------------------------

    private static void installPropertiesDialogInterceptor(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (!NO_PROPERTY_PAGES_TITLE.equals(shell.getText()))
                return;
            IViewPart breakpointsView = resolveBreakpointsView();
            IBslExceptionBreakpoint breakpoint = resolveSelectedExceptionBreakpoint(breakpointsView);
            if (breakpoint == null)
                return;

            Object data = shell.getData();
            if (data instanceof Window messageWindow)
                messageWindow.close();
            else if (!shell.isDisposed())
                shell.dispose();

            Rectangle anchor = breakpointsView instanceof IDebugView debugView
                    ? resolveRowBoundsOnDisplay(debugView.getViewer()) : null;
            Display.getDefault().asyncExec(() -> openFilterEditor(breakpoint, resolveActiveShell(), anchor));
        });
    }

    private static IViewPart resolveBreakpointsView()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        return page != null ? page.findView(IDebugUIConstants.ID_BREAKPOINT_VIEW) : null;
    }

    private static IBslExceptionBreakpoint resolveSelectedExceptionBreakpoint(IViewPart breakpointsView)
    {
        try
        {
            if (breakpointsView == null)
                return null;
            ISelectionProvider provider = breakpointsView.getSite().getSelectionProvider();
            return provider != null ? resolveSingleExceptionBreakpoint(provider.getSelection()) : null;
        }
        catch (Exception e)
        {
            BreakpointListDebug.problem("resolveSelectedExceptionBreakpoint: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /** Экранные координаты выделенной строки — под ней открывается редактор фильтра. */
    private static Rectangle resolveRowBoundsOnDisplay(Viewer viewer)
    {
        if (!(viewer instanceof TreeViewer treeViewer))
            return null;
        Tree tree = treeViewer.getTree();
        if (tree == null || tree.isDisposed())
            return null;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return null;
        Rectangle bounds = selection[0].getBounds();
        Point topLeft = tree.toDisplay(bounds.x, bounds.y + bounds.height);
        return new Rectangle(topLeft.x, topLeft.y, bounds.width, bounds.height);
    }

    private static Shell resolveActiveShell()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window != null ? window.getShell() : null;
    }

    // -----------------------------------------------------------------------
    // Редактор текста фильтра
    // -----------------------------------------------------------------------

    private static void openFilterEditor(IBslExceptionBreakpoint breakpoint, Shell parentShell, Rectangle anchor)
    {
        String current;
        try
        {
            current = breakpoint.getExceptionMessage();
        }
        catch (CoreException e)
        {
            current = null;
            BreakpointListDebug.problem("getExceptionMessage: " + e.getMessage()); //$NON-NLS-1$
        }

        BreakpointFilterDialog dialog = new BreakpointFilterDialog(parentShell,
                current != null ? current : "", anchor); //$NON-NLS-1$
        if (dialog.open() != Window.OK)
            return;

        String newText = dialog.getValue();
        try
        {
            breakpoint.setExceptionMessage(newText == null || newText.isBlank() ? null : newText);
        }
        catch (CoreException e)
        {
            BreakpointListDebug.problem("setExceptionMessage: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** Позиция под {@code anchor} (строка списка), прижатая к границам её монитора. */
    private static Point clampToMonitor(Display display, Rectangle anchor, Point size)
    {
        int x = anchor.x;
        int y = anchor.y + 2;

        Rectangle monitorArea = resolveMonitorArea(display, anchor);
        x = Math.max(monitorArea.x, Math.min(x, monitorArea.x + monitorArea.width - size.x));
        y = Math.max(monitorArea.y, Math.min(y, monitorArea.y + monitorArea.height - size.y));
        return new Point(x, y);
    }

    private static Rectangle resolveMonitorArea(Display display, Rectangle anchor)
    {
        for (Monitor monitor : display.getMonitors())
        {
            if (monitor.getClientArea().contains(anchor.x, anchor.y))
                return monitor.getClientArea();
        }
        return display.getPrimaryMonitor().getClientArea();
    }

    // -----------------------------------------------------------------------
    // Разбор текста ошибки
    // -----------------------------------------------------------------------

    /**
     * Первая непустая строка уже разобранного EDT текста ошибки — {@code IStacktraceError.getName()}
     * (штатный {@code IStacktraceParser} панели «Трассировки стека» — см.
     * {@link ExceptionSelectionDialogHook}/{@link StacktracesViewInteractionHook}). У 1С сама
     * причина всегда первая строка дампа; в {@code getName()} могут попадать и другие
     * нераспознанные парсером строки (например ссылки без имени модуля {@code {(1)}:...} или
     * служебный тег {@code [ОшибкаВоВремя...]}) — они идут ПОСЛЕ причины, не перед ней.
     */
    static String firstLine(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip(); //$NON-NLS-1$ //$NON-NLS-2$
        if (normalized.isEmpty())
            return ""; //$NON-NLS-1$
        int nl = normalized.indexOf('\n');
        return (nl < 0 ? normalized : normalized.substring(0, nl)).strip();
    }

    // -----------------------------------------------------------------------
    // Диалог редактирования фильтра
    // -----------------------------------------------------------------------

    private static final class BreakpointFilterDialog extends InputDialog
    {
        private final Rectangle anchor;

        BreakpointFilterDialog(Shell parentShell, String initialValue, Rectangle anchor)
        {
            super(parentShell, Global.withPluginWindowTitle(DIALOG_TITLE), DIALOG_MESSAGE, initialValue, null);
            this.anchor = anchor;
        }

        @Override
        protected Point getInitialLocation(Point initialSize)
        {
            if (anchor == null)
                return super.getInitialLocation(initialSize);
            return clampToMonitor(getShell().getDisplay(), anchor, initialSize);
        }
    }

    /** Журнал «Комфорт» для {@link BreakpointListHook}. */
    private static final class BreakpointListDebug
    {
        private static final String TAG = "BreakpointList"; //$NON-NLS-1$

        private BreakpointListDebug() {}

        static void problem(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
