package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/** Команда «Форматировать текст ИР» через приложение ИР. */
public final class IrFormatTextCommandHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.IrFormatText"; //$NON-NLS-1$
    public static final String BINDING_CONTEXT_ID = "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        BslXtextEditor editor = resolveBslEditor(part);
        if (editor == null)
            editor = resolveBslEditor(editorPart);
        if (IrFormatTextDebug.isKeyDiagnosticEnabled())
        {
            IrFormatTextDebug.step("handler execute", //$NON-NLS-1$
                "part=" + IrFormatTextDebug.formatPartBrief(part) //$NON-NLS-1$
                    + " editorPart=" + IrFormatTextDebug.formatPartBrief(editorPart) //$NON-NLS-1$
                    + " bsl=" + IrFormatTextDebug.formatEditorBrief(editor) //$NON-NLS-1$
                    + " applicable=" + IrFormatTextHandler.isApplicableBsl(editor)); //$NON-NLS-1$
            if (editor == null)
                IrFormatTextDebug.problem("handler execute: editor null"); //$NON-NLS-1$
        }

        IrFormatTextHandler.formatBslModule(editor);
        return null;
    }

    private static BslXtextEditor resolveBslEditor(IWorkbenchPart part)
    {
        if (part instanceof IEditorPart ep)
            return GetRef.getActiveBslEditor(ep);
        return GetRef.getActiveBslEditor(part);
    }
}
