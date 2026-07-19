package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/** Команда «Очистить ошибки ИР» — удаляет все маркеры проверки ИР для активного проекта. */
public final class IrModuleCheckClearCommandHandler extends AbstractHandler
{
    public static final String BINDING_CONTEXT_ID = "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        BslXtextEditor editor = resolveBslEditor(HandlerUtil.getActivePart(event));
        if (editor == null)
            editor = resolveBslEditor(HandlerUtil.getActiveEditor(event));
        IrModuleCheckClearHandler.clearMarkers(editor);
        return null;
    }

    private static BslXtextEditor resolveBslEditor(IWorkbenchPart part)
    {
        if (part instanceof IEditorPart editorPart)
            return GetRef.getActiveBslEditor(editorPart);
        return GetRef.getActiveBslEditor(part);
    }
}
