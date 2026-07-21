package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Команда «Конструктор запроса ИР»: открыть конструктор запроса приложения ИР
 * для текста запроса под кареткой. Доступность — как у команды «Вложенный текст ИР».
 */
public final class IrQueryConstructorCommandHandler extends AbstractHandler
{
    public static final String COMMAND_ID = IrQueryConstructorHandler.COMMAND_ID;

    public static final String BINDING_CONTEXT_ID = "org.eclipse.xtext.ui.XtextEditorScope"; //$NON-NLS-1$

    @Override
    public void setEnabled(Object evaluationContext)
    {
        boolean enabled = false;
        if (evaluationContext instanceof IEvaluationContext context)
        {
            try
            {
                BslXtextEditor editor = resolveBslEditor(context, null);
                enabled = editor != null && IrQueryConstructorHandler.isApplicable(editor);
            }
            catch (Exception ignored)
            {
                // команда остаётся недоступной
            }
        }
        setBaseEnabled(enabled);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        BslXtextEditor editor = resolveBslEditor(null, event);
        IrQueryConstructorHandler.openQueryConstructor(editor);
        return null;
    }

    private static BslXtextEditor resolveBslEditor(IEvaluationContext context, ExecutionEvent event)
    {
        IWorkbenchPart part = event != null ? HandlerUtil.getActivePart(event) : null;
        IEditorPart editor = event != null ? HandlerUtil.getActiveEditor(event) : null;
        if (context != null)
        {
            Object partVar = context.getVariable(ISources.ACTIVE_PART_NAME);
            Object editorVar = context.getVariable(ISources.ACTIVE_EDITOR_NAME);
            if (part == null && partVar instanceof IWorkbenchPart wp)
                part = wp;
            if (editor == null && editorVar instanceof IEditorPart ep)
                editor = ep;
        }

        BslXtextEditor bslEditor = resolveBslEditor(part);
        if (bslEditor == null)
            bslEditor = resolveBslEditor(editor);
        return bslEditor;
    }

    private static BslXtextEditor resolveBslEditor(IWorkbenchPart part)
    {
        if (part instanceof IEditorPart editorPart)
            return GetRef.getActiveBslEditor(editorPart);
        return GetRef.getActiveBslEditor(part);
    }
}
