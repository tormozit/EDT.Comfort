package tormozit;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
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

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * При активации вкладки «Подсистемы» редактора объекта метаданных разворачивает
 * её дерево целиком. Штатно AEF задаёт {@code expandLevel(1)} — видны только
 * верхние подсистемы.
 */
public final class MdEditorSubsystemsExpandHook implements IStartup
{
    private static final String TAG = "MdEditorSubsystemsExpand"; //$NON-NLS-1$

    private static final String PAGE_ID = "editors.pages.subsystems"; //$NON-NLS-1$

    private static final String PAGE_CLASS_SUFFIX = "DtGranularEditorSubsystemsPage"; //$NON-NLS-1$

    private static final String DT_TREE_VIEWER_KEY =
        "com._1c.g5.v8.dt.ui.aef.swt.views.DtTreeView.treeViewer"; //$NON-NLS-1$

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
            if (viewer.getInput() == null && tree.getItemCount() == 0)
                return ExpandResult.WAIT;
            viewer.expandAll();
            return ExpandResult.DONE;
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "expand subsystems tree", e); //$NON-NLS-1$
            return ExpandResult.NOT_SUBSYSTEMS;
        }
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
