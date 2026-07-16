package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Команда «Сортировать строки текста»: сортирует выделенные строки по алфавиту.
 */
public class SortTextLinesHandler extends AbstractHandler
{
    public static final String COMMAND_ID = "tormozit.SortTextLines"; //$NON-NLS-1$

    /** Контекст привязки в {@code plugin.xml} («В окнах»). */
    public static final String BINDING_CONTEXT_ID = "org.eclipse.ui.contexts.window"; //$NON-NLS-1$

    @Override
    public void setEnabled(Object evaluationContext)
    {
        boolean enabled = false;
        try
        {
            Shell shell = resolveShell(evaluationContext);
            TextEditor.Context ctx = resolveContext(evaluationContext, null);
            enabled = SortTextLinesActions.isAvailable(shell, ctx);
        }
        catch (Exception ignored)
        {
            // команда остаётся недоступной
        }
        setBaseEnabled(enabled);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        TextEditor.Context ctx = resolveContext(null, event);
        SortTextLinesActions.run(shell, ctx);
        return null;
    }

    private static TextEditor.Context resolveContext(
            Object evaluationContext, ExecutionEvent event)
    {
        TextEditor.Context ctx = TextEditor.resolveContextFromFocus();
        if (ctx != null)
            return ctx;

        IWorkbenchPart part = event != null ? HandlerUtil.getActivePart(event) : null;
        IEditorPart editor = event != null ? HandlerUtil.getActiveEditor(event) : null;
        if (evaluationContext instanceof IEvaluationContext evalContext)
        {
            Object partVar = evalContext.getVariable(ISources.ACTIVE_PART_NAME);
            Object editorVar = evalContext.getVariable(ISources.ACTIVE_EDITOR_NAME);
            if (part == null && partVar instanceof IWorkbenchPart wp)
                part = wp;
            if (editor == null && editorVar instanceof IEditorPart ep)
                editor = ep;
        }
        if (part == null && editor instanceof IWorkbenchPart wp)
            part = wp;

        return TextEditor.resolveContext(part, editor);
    }

    private static Shell resolveShell(Object evaluationContext)
    {
        Control focus = Display.getCurrent() != null
            ? Display.getCurrent().getFocusControl()
            : null;
        if (focus == null && Display.getDefault() != null && !Display.getDefault().isDisposed())
            focus = Display.getDefault().getFocusControl();
        if (focus != null && !focus.isDisposed())
        {
            Shell shell = focus.getShell();
            if (shell != null && !shell.isDisposed())
                return shell;
        }

        if (evaluationContext instanceof IEvaluationContext context)
        {
            Object shellVar = context.getVariable(ISources.ACTIVE_SHELL_NAME);
            if (shellVar instanceof Shell shell && !shell.isDisposed())
                return shell;
        }
        return null;
    }
}
