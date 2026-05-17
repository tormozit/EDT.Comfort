package tormozit.edt.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import tormozit.edt.selection.CompareEditorSelectionProvider;

public class ShowInNavigatorHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        showInNavigator(HandlerUtil.getActiveEditor(event), HandlerUtil.getActiveShell(event));
        return null;
    }
    public static void showInNavigator(IEditorPart editor, Shell shell) {
        ((CompareEditorSelectionProvider) editor.getSite().getSelectionProvider()).showObjectInNavigator(OpenObjectHandler.getSelection(editor), true);
    }
}
