package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
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

import com._1c.g5.aef2.events.IEventChannelListener;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.ui.aef.events.TreeRefreshEvent;

/**
 * При активации вкладки «Подсистемы» редактора объекта метаданных разворачивает
 * её дерево целиком, в том числе после изменения состава подсистем.
 * Штатно AEF задаёт {@code expandLevel(1)} — видны только верхние подсистемы.
 */
public final class MdEditorSubsystemsExpandHook implements IStartup
{
    private static final String TAG = "MdEditorSubsystemsExpand"; //$NON-NLS-1$

    private static final String PAGE_ID = "editors.pages.subsystems"; //$NON-NLS-1$

    private static final String PAGE_CLASS_SUFFIX = "DtGranularEditorSubsystemsPage"; //$NON-NLS-1$

    private static final String DT_TREE_VIEWER_KEY =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$

    private static final String COMPONENT_CLASS =
        "com._1c.g5.v8.dt.internal.md.ui.editors.pages.subsystems.DtGranularEditorSubsystemsPageComponent"; //$NON-NLS-1$

    private static final String REFRESH_LISTENER_KEY = "tormozit.subsystemsExpandListener"; //$NON-NLS-1$

    private static final String DIALOG_SELECTION_KEY = "tormozit.subsystemsDialogSelection"; //$NON-NLS-1$

    private static final String DIALOG_HOOK_KEY = "tormozit.subsystemsDialogHook"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    /** Повторный разворот после штатного {@code expandLevel(1)} / восстановления состояния. */
    private static final int EXTRA_EXPAND_MS = 200;

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display.getDefault().addFilter(SWT.Show, event ->
            {
                if (event.widget instanceof Shell shell)
                    hookSelectionDialog(shell);
            });
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
            editor.addPageChangedListener(event -> scheduleExpand(editor, 0));
        scheduleExpand(editor, 0);
    }

    private static void scheduleExpand(DtGranularEditor<?> editor, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            ExpandResult result = tryExpand(editor);
            if (result == ExpandResult.NOT_SUBSYSTEMS)
                return;
            if (result == ExpandResult.DONE)
            {
                display.timerExec(EXTRA_EXPAND_MS, () -> tryExpand(editor));
                return;
            }
            scheduleExpand(editor, attempt + 1);
        });
    }

    private enum ExpandResult
    {
        NOT_SUBSYSTEMS, WAIT, DONE
    }

    private static ExpandResult tryExpand(DtGranularEditor<?> editor)
    {
        try
        {
            IFormPage page = editor.getActivePageInstance();
            if (!isSubsystemsPage(page))
                return ExpandResult.NOT_SUBSYSTEMS;
            TreeViewer viewer = findTreeViewer(page);
            if (viewer == null)
                return ExpandResult.WAIT;
            Tree tree = viewer.getTree();
            if (tree == null || tree.isDisposed())
                return ExpandResult.WAIT;
            if (!attachRefreshListener(editor, page, tree))
                return ExpandResult.WAIT;
            if (viewer.getInput() == null && tree.getItemCount() == 0)
                return ExpandResult.WAIT;
            viewer.expandAll();
            restoreDialogSelection(page, viewer);
            return ExpandResult.DONE;
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "expand subsystems tree", e); //$NON-NLS-1$
            return ExpandResult.NOT_SUBSYSTEMS;
        }
    }

    /** Текущая строка диалога независима от списка помеченных объектов. */
    private static void hookSelectionDialog(Shell shell)
    {
        if (shell.isDisposed() || shell.getData(DIALOG_HOOK_KEY) != null)
            return;
        Object dialog = shell.getData();
        if (!(dialog instanceof Window window))
            return;
        // Поле проверено в SwtDialogRenderer$1; канал view-model — её компонент AEF.
        Object viewModel = Global.getField(dialog, "val$dialogViewModel"); //$NON-NLS-1$
        Object component = Global.invoke(viewModel, "getEventChannel"); //$NON-NLS-1$
        if (component == null || !component.getClass().getName().equals(
            "com._1c.g5.v8.dt.md.ui.aef.components.NavigatorTreeDialogComponent")) //$NON-NLS-1$
            return;
        IWorkbenchWindow workbenchWindow = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage workbenchPage = workbenchWindow != null ? workbenchWindow.getActivePage() : null;
        if (workbenchPage == null
            || !(workbenchPage.getActiveEditor() instanceof DtGranularEditor<?> editor))
            return;
        IFormPage page = editor.getActivePageInstance();
        if (!isSubsystemsPage(page))
            return;
        TreeViewer target = findTreeViewer(page);
        TreeViewer source = findTreeViewer(shell, 0);
        Object navigator = Global.getField(component, "navigatorTreeComponent"); //$NON-NLS-1$
        Object mapper = Global.invoke(navigator, "getMapper"); //$NON-NLS-1$
        if (target == null || source == null || mapper == null)
            return;
        Tree tree = target.getTree();
        shell.setData(DIALOG_HOOK_KEY, Boolean.TRUE);
        String[] selectedUri = { subsystemUri(mapper, source.getStructuredSelection().getFirstElement()) };
        source.addSelectionChangedListener(event ->
        {
            selectedUri[0] = subsystemUri(mapper, source.getStructuredSelection().getFirstElement());
        });
        shell.addDisposeListener(event ->
        {
            if (window.getReturnCode() != Window.OK || selectedUri[0] == null || tree.isDisposed())
                return;
            tree.setData(DIALOG_SELECTION_KEY, new DialogSelection(selectedUri[0]));
            scheduleExpand(editor, 0);
        });
    }

    private static String subsystemUri(Object mapper, Object element)
    {
        if (element == null)
            return null;
        EObject model = NavigatorElementModels.resolveEObject(
            Global.invoke(mapper, "mapViewToModel", element)); //$NON-NLS-1$
        return model instanceof Subsystem ? EcoreUtil.getURI(model).toString() : null;
    }

    private static void restoreDialogSelection(IFormPage page, TreeViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (!(tree.getData(DIALOG_SELECTION_KEY) instanceof DialogSelection selection))
            return;
        Object component = findSubsystemsComponent(Global.getField(page, "pageComponent"), 0); //$NON-NLS-1$
        Object mapper = Global.invoke(component, "getMapper"); //$NON-NLS-1$
        Object element = findSubsystemItem(tree.getItems(), mapper, selection.uri);
        if (element == null)
            return;
        viewer.setSelection(new StructuredSelection(element), true);
        if (!selection.focused)
        {
            selection.focused = true;
            tree.setFocus();
        }
    }

    private static Object findSubsystemItem(TreeItem[] items, Object mapper, String uri)
    {
        for (TreeItem item : items)
        {
            if (uri.equals(subsystemUri(mapper, item.getData())))
                return item.getData();
            Object found = findSubsystemItem(item.getItems(), mapper, uri);
            if (found != null)
                return found;
        }
        return null;
    }

    private static final class DialogSelection
    {
        private final String uri;
        private boolean focused;

        private DialogSelection(String uri)
        {
            this.uri = uri;
        }
    }

    /** EDT обновляет дерево этим событием после изменения Subsystem.content. */
    private static boolean attachRefreshListener(DtGranularEditor<?> editor, IFormPage page, Tree tree)
    {
        if (tree.getData(REFRESH_LISTENER_KEY) != null)
            return true;
        Object component = findSubsystemsComponent(Global.getField(page, "pageComponent"), 0); //$NON-NLS-1$
        if (component == null)
        {
            return false;
        }
        IEventChannelListener listener = event ->
        {
            if (event instanceof TreeRefreshEvent)
            {
                // После доставки события рендерер EDT сначала обновит строки дерева.
                tree.getDisplay().asyncExec(() ->
                {
                    if (!tree.isDisposed() && editor.getActivePageInstance() == page)
                    {
                        scheduleExpand(editor, 0);
                    }
                });
            }
        };
        if (!Global.invokeVoid(component, "addListener", listener)) //$NON-NLS-1$
        {
            return false;
        }
        tree.setData(REFRESH_LISTENER_KEY, listener);
        // Последующие обновления EDT сохраняют цель, пока пользователь не выбрал другую строку.
        tree.addListener(SWT.Selection, event -> tree.setData(DIALOG_SELECTION_KEY, null));
        tree.addDisposeListener(event -> Global.invoke(component, "removeListener", listener)); //$NON-NLS-1$
        return true;
    }

    private static Object findSubsystemsComponent(Object component, int depth)
    {
        if (component == null || depth > 24)
            return null;
        if (COMPONENT_CLASS.equals(component.getClass().getName()))
            return component;
        for (Object child : AefFieldFocus.childComponents(component))
        {
            Object found = findSubsystemsComponent(child, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    private static boolean isSubsystemsPage(IFormPage page)
    {
        if (page == null)
            return false;
        String id = page.getId();
        if (PAGE_ID.equals(id))
            return true;
        return page.getClass().getName().endsWith(PAGE_CLASS_SUFFIX);
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
}
