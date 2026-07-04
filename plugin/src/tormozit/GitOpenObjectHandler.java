package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

public class GitOpenObjectHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPage page = activePage();
        if (page == null)
            return null;

        IWorkbenchPart part = page.getActivePart();
        if (part == null || !GitChangedFileMenuHook.isGitView(part))
            return null;
        Global.log("GitOpenObject: called part=" + part.getSite().getId()); //$NON-NLS-1$

        // Сначала пробуем selection из Table/Tree под фокусом (работает в HistoryView),
        // затем page.getSelection() как fallback
        IStructuredSelection selection = null;
        ISelection fs = GitChangedFileMenuHook.selectionFromFocusControl();
        if (fs instanceof IStructuredSelection ss && ss.size() == 1)
            selection = ss;
        if (selection == null)
            selection = getSelection(page);
        if (selection == null || selection.size() != 1) {
            Global.log("GitOpenObject: selection failed, size=" + (selection == null ? 0 : selection.size())); //$NON-NLS-1$
            return null;
        }

        Object element = selection.getFirstElement();
        Global.log("GitOpenObject: element class=" + element.getClass().getName() + " toString=" + element); //$NON-NLS-1$ //$NON-NLS-2$

        IFile file = GitChangedFileMenuHook.resolveFile(part, element);
        if (file == null || !file.exists()) {
            Global.log("GitOpenObject: file=null for " + element); //$NON-NLS-1$
            return null;
        }
        Global.log("GitOpenObject: file=" + file.getFullPath()); //$NON-NLS-1$

        EObject eObject = GitChangedFileMenuHook.resolveEObject(file);
        if (eObject == null) {
            Global.log("GitOpenObject: eObject=null for " + file.getFullPath()); //$NON-NLS-1$
            return null;
        }

        Shell shell = HandlerUtil.getActiveShell(event);
        GitChangedFileMenuHook.openInEditor(eObject, file, shell);
        Global.log("GitOpenObject: openInEditor OK"); //$NON-NLS-1$
        return null;
    }

    private static IWorkbenchPage activePage() {
        try {
            var wb = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            return wb != null ? wb.getActivePage() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static IStructuredSelection getSelection(IWorkbenchPage page) {
        if (page.getSelection() instanceof IStructuredSelection s)
            return s;
        IWorkbenchPart part = page.getActivePart();
        if (part != null && part.getSite() != null) {
            var provider = part.getSite().getSelectionProvider();
            if (provider != null && provider.getSelection() instanceof IStructuredSelection s)
                return s;
        }
        return null;
    }
}
