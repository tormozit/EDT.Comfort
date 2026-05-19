

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

public class CompareConfigShowInNavigatorHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        showInNavigator(HandlerUtil.getActiveEditor(event), HandlerUtil.getActiveShell(event));
        return null;
    }
    public static void showInNavigator(IEditorPart editor, Shell shell) {
        ((CompareConfigSelectionProvider) editor.getSite().getSelectionProvider()).showObjectInNavigator(CompareConfigOpenObjectHandler.getSelection(editor), true);
    }
}
