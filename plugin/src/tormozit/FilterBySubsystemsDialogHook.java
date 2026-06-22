package tormozit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Штатный EDT «Фильтр по подсистемам» (в интерфейсе также «Отбор по подсистемам»):
 * автораскрытие ветки проекта из выделения навигатора и запоминание размеров окна
 * между сеансами.
 *
 * <p><b>TODO (наборы объектов):</b> наборы объектов в этом диалоге <em>не</em> показываются
 * и не выбираются — только штатное дерево подсистем. Связь с наборами сейчас только через
 * {@link ObjectSetSubsystemsFilterBridge} (фильтр «звезда» в навигаторе вытесняет фильтр
 * подсистем). Показ/выбор наборов внутри окна «Отбор по подсистемам» — возможная доработка
 * на будущее.
 */
public final class FilterBySubsystemsDialogHook implements IStartup
{
    private static final String HOOKED_KEY = "tormozit.filterBySubsystemsHooked"; //$NON-NLS-1$
    private static final String DIALOG_CLASS = "FilterBySubsystemsDialog"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Фильтр по подсистемам"; //$NON-NLS-1$

    private static final String SETTINGS_SECTION = "FilterBySubsystemsDialog"; //$NON-NLS-1$
    private static final String KEY_SHELL_X = "shell.x"; //$NON-NLS-1$
    private static final String KEY_SHELL_Y = "shell.y"; //$NON-NLS-1$
    private static final String KEY_SHELL_WIDTH = "shell.width"; //$NON-NLS-1$
    private static final String KEY_SHELL_HEIGHT = "shell.height"; //$NON-NLS-1$

    private static final int MIN_WIDTH = 400;
    private static final int MIN_HEIGHT = 300;
    private static final int MAX_PATCH_ATTEMPTS = 15;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, FilterBySubsystemsDialogHook::handleShow);
        FilterBySubsystemsDialogDebug.log("install Show filter"); //$NON-NLS-1$
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell))
            return;
        if (shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(HOOKED_KEY)))
            return;
        if (!isFilterBySubsystemsShell(shell))
            return;
        registerShell(shell);
    }

    private static void registerShell(Shell shell)
    {
        shell.setData(HOOKED_KEY, Boolean.TRUE);
        applyStoredShellBounds(shell);
        shell.addDisposeListener(e -> saveShellBounds((Shell) e.widget));
        schedulePatchAttempt(shell.getDisplay(), shell, 0);
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            if (tryPatchShell(shell, attempt))
                return;
            if (attempt < MAX_PATCH_ATTEMPTS)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatchShell(Shell shell, int attempt)
    {
        Object dialog = resolveDialog(shell);
        if (dialog == null)
        {
            FilterBySubsystemsDialogDebug.step("patch", "attempt=" + attempt + " dialog=null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        }

        CheckboxTreeViewer viewer = resolveViewer(dialog);
        if (viewer == null || viewer.getTree() == null || viewer.getTree().isDisposed())
        {
            FilterBySubsystemsDialogDebug.step("patch", "attempt=" + attempt + " viewer=null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        }

        expandNavigatorProject(viewer, attempt);
        return true;
    }

    private static void applyStoredShellBounds(Shell shell)
    {
        IDialogSettings settings = dialogSettings();
        if (settings.get(KEY_SHELL_WIDTH) == null || settings.get(KEY_SHELL_HEIGHT) == null)
            return;

        int width = settings.getInt(KEY_SHELL_WIDTH);
        int height = settings.getInt(KEY_SHELL_HEIGHT);
        if (width <= 0 || height <= 0)
            return;

        int x = settings.getInt(KEY_SHELL_X);
        int y = settings.getInt(KEY_SHELL_Y);
        Rectangle bounds = clampToMonitor(shell.getDisplay(),
            new Rectangle(x, y, width, height), MIN_WIDTH, MIN_HEIGHT);
        shell.setBounds(bounds);
        FilterBySubsystemsDialogDebug.log("restore bounds " + bounds); //$NON-NLS-1$
    }

    private static void saveShellBounds(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        if (shell.getMinimized())
            return;

        Rectangle bounds = shell.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
            return;

        Rectangle clamped = clampToMonitor(shell.getDisplay(), bounds, MIN_WIDTH, MIN_HEIGHT);
        IDialogSettings settings = dialogSettings();
        settings.put(KEY_SHELL_X, clamped.x);
        settings.put(KEY_SHELL_Y, clamped.y);
        settings.put(KEY_SHELL_WIDTH, clamped.width);
        settings.put(KEY_SHELL_HEIGHT, clamped.height);
        FilterBySubsystemsDialogDebug.log("save bounds " + clamped); //$NON-NLS-1$
    }

    private static void expandNavigatorProject(CheckboxTreeViewer viewer, int attempt)
    {
        IProject project = resolveNavigatorProject();
        if (project == null)
        {
            FilterBySubsystemsDialogDebug.step("expand", "attempt=" + attempt + " no navigator project"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }

        Object input = viewer.getInput();
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider contentProvider))
            return;

        Object[] roots = contentProvider.getElements(input);
        Object projectElement = findProjectElement(roots, project);
        if (projectElement == null)
        {
            FilterBySubsystemsDialogDebug.step("expand", "attempt=" + attempt //$NON-NLS-1$ //$NON-NLS-2$
                + " project=" + project.getName() + " not in tree"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        viewer.setExpandedState(projectElement, true);
        viewer.reveal(projectElement);
        FilterBySubsystemsDialogDebug.step("expand", "attempt=" + attempt //$NON-NLS-1$ //$NON-NLS-2$
            + " project=" + project.getName()); //$NON-NLS-1$
    }

    private static CheckboxTreeViewer resolveViewer(Object dialog)
    {
        Object panel = Global.getField(dialog, "subsystemsPanel"); //$NON-NLS-1$
        if (panel == null)
            return null;
        Object viewer = Global.getField(panel, "viewer"); //$NON-NLS-1$
        return viewer instanceof CheckboxTreeViewer checkboxTreeViewer ? checkboxTreeViewer : null;
    }

    private static Object findProjectElement(Object[] roots, IProject target)
    {
        if (roots == null || target == null)
            return null;
        for (Object root : roots)
        {
            if (sameWorkspaceProject(root, target))
                return root;
        }
        return null;
    }

    private static boolean sameWorkspaceProject(Object element, IProject target)
    {
        if (element instanceof IProject project)
            return target.equals(project);
        if (element instanceof IDtProject dtProject)
        {
            IProject workspaceProject = dtProject.getWorkspaceProject();
            return workspaceProject != null && target.equals(workspaceProject);
        }
        return false;
    }

    private static IProject resolveNavigatorProject()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        IViewPart navigator = page.findView(Global.NAVIGATOR_VIEW_ID);
        if (navigator == null)
            return null;

        ISelection selection = navigator.getSite().getSelectionProvider().getSelection();
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
            return null;

        if (selection instanceof TreeSelection treeSelection)
        {
            TreePath[] paths = treeSelection.getPaths();
            if (paths.length > 0 && paths[0].getSegmentCount() > 0)
            {
                IProject fromPath = projectFromElement(paths[0].getSegment(0));
                if (fromPath != null)
                    return fromPath;
            }
        }
        return projectFromElement(structured.getFirstElement());
    }

    private static IProject projectFromElement(Object element)
    {
        if (element instanceof IProject project)
            return project;
        if (element instanceof IDtProject dtProject)
            return dtProject.getWorkspaceProject();
        if (element instanceof IResource resource)
            return resource.getProject();
        return Adapters.adapt(element, IProject.class);
    }

    private static boolean isFilterBySubsystemsShell(Shell shell)
    {
        if (resolveDialog(shell) != null)
            return true;
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE);
    }

    private static Object resolveDialog(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;

        Object fromShell = resolveDialogOnWidget(shell);
        if (fromShell != null)
            return fromShell;

        return resolveDialogInComposite(shell);
    }

    private static Object resolveDialogInComposite(Composite root)
    {
        if (root == null || root.isDisposed())
            return null;

        Object onRoot = resolveDialogOnWidget(root);
        if (onRoot != null)
            return onRoot;

        for (Control child : root.getChildren())
        {
            if (child.isDisposed())
                continue;
            Object onChild = resolveDialogOnWidget(child);
            if (onChild != null)
                return onChild;
            if (child instanceof Composite composite)
            {
                Object nested = resolveDialogInComposite(composite);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static Object resolveDialogOnWidget(org.eclipse.swt.widgets.Widget widget)
    {
        if (widget == null || widget.isDisposed())
            return null;

        for (String key : new String[] { null, "org.eclipse.jface.window.Window", //$NON-NLS-1$ //$NON-NLS-2$
            "org.eclipse.jface.dialogs.Dialog.dialog" }) //$NON-NLS-1$
        {
            Object data = key == null ? widget.getData() : widget.getData(key);
            if (isFilterBySubsystemsDialog(data))
                return data;
        }
        return null;
    }

    private static boolean isFilterBySubsystemsDialog(Object data)
    {
        return data != null && data.getClass().getName().contains(DIALOG_CLASS);
    }

    private static IDialogSettings dialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static Rectangle clampToMonitor(Display display, Rectangle bounds, int minWidth, int minHeight)
    {
        if (display == null || bounds == null)
            return bounds;

        Monitor monitor = display.getPrimaryMonitor();
        Point center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        for (Monitor candidate : display.getMonitors())
        {
            if (candidate.getBounds().contains(center))
            {
                monitor = candidate;
                break;
            }
        }

        Rectangle client = monitor.getClientArea();
        int width = Math.max(minWidth, Math.min(bounds.width, client.width));
        int height = Math.max(minHeight, Math.min(bounds.height, client.height));
        int x = bounds.x;
        int y = bounds.y;
        if (x + width > client.x + client.width)
            x = client.x + client.width - width;
        if (y + height > client.y + client.height)
            y = client.y + client.height - height;
        if (x < client.x)
            x = client.x;
        if (y < client.y)
            y = client.y;
        return new Rectangle(x, y, width, height);
    }
}


