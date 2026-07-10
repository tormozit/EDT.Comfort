package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/** Команда «Найти ссылки ИР» через приложение ИР. */
public final class IrFindReferencesCommandHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        BslXtextEditor editor = resolveBslEditor(HandlerUtil.getActivePart(event));
        if (editor == null)
            editor = resolveBslEditor(HandlerUtil.getActiveEditor(event));
        IrFindReferencesHandler.findInModules(editor);
        return null;
    }

    private static BslXtextEditor resolveBslEditor(IWorkbenchPart part)
    {
        if (part instanceof IEditorPart editorPart)
            return GetRef.getActiveBslEditor(editorPart);
        return GetRef.getActiveBslEditor(part);
    }
}
