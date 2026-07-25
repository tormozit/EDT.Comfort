package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.stacktraces.model.IStacktrace;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceElement;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceError;

/**
 * Команда «Остановка по исключению» в панели «Трассировки стека»: берёт причину ошибки из текущей
 * (выбранной) трассировки и запускает штатное действие EDT «Добавить точку останова по исключению»
 * — открывшийся диалог «Остановка по ошибке» сам подставит эту причину (передаётся напрямую, см.
 * {@link ExceptionSelectionDialogHook#setPendingReason}, без буфера обмена).
 */
public final class StacktraceStopOnExceptionHandler extends AbstractHandler
{
    private static final String ADD_ACTION_BUNDLE = "com._1c.g5.v8.dt.debug.ui"; //$NON-NLS-1$
    private static final String ADD_ACTION_CLASS =
            "com._1c.g5.v8.dt.internal.debug.ui.actions.AddBslExceptionBreakpointAction"; //$NON-NLS-1$
    private static final String TITLE = "Остановка по ошибке"; //$NON-NLS-1$
    private static final String NO_REASON_MESSAGE =
            "В выбранной трассировке не найдено описания ошибки"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        // getActiveMenuSelection — снимок именно в момент показа ЭТОГО popup-меню (ISources.
        // ACTIVE_MENU_SELECTION_NAME); getCurrentSelection тянет общий workbench selection service,
        // который для этой (многостраничной, CTabFolder) панели не всегда успевает обновиться на
        // новую вкладку/строку к моменту вызова команды — воспроизведено логом (устаревший выбор).
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (selection == null)
            selection = HandlerUtil.getCurrentSelection(event);
        Global.tempLog("StacktraceStopOnException", "execute selection=" + selection); //$NON-NLS-1$ //$NON-NLS-2$

        IStacktraceElement element = resolveSelectedElement(selection);
        Global.tempLog("StacktraceStopOnException", //$NON-NLS-1$
                "element=" + (element == null ? "null" : element.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
        if (element != null)
            triggerFor(element);
        return null;
    }

    private static IStacktraceElement resolveSelectedElement(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
            return null;
        return structured.getFirstElement() instanceof IStacktraceElement element ? element : null;
    }

    /**
     * Общая логика команды и двойного клика ({@link StacktracesViewInteractionHook}): причина
     * ошибки трассировки, к которой относится {@code element}, → штатный диалог «Остановка по
     * ошибке» с этой причиной, либо тост, если причины нет.
     */
    static void triggerFor(IStacktraceElement element)
    {
        String errorText = findErrorText(element);
        Global.tempLog("StacktraceStopOnException", "errorText=[" + errorText + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String reason = BreakpointListHook.firstLine(errorText);
        Global.tempLog("StacktraceStopOnException", "reason=[" + reason + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (reason.isEmpty())
        {
            ToastNotification.show(TITLE, NO_REASON_MESSAGE, 4_000);
            return;
        }

        ExceptionSelectionDialogHook.setPendingReason(reason);
        runAddExceptionBreakpointAction();
    }

    /** Текст ошибки трассировки, к которой относится {@code element} (см. {@link IStacktraceError}). */
    private static String findErrorText(IStacktraceElement element)
    {
        IStacktrace root = element.getStacktrace();
        Global.tempLog("StacktraceStopOnException", //$NON-NLS-1$
                "findErrorText root=" + (root == null ? "null" : root.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$
        if (root == null)
            return null;
        for (IStacktraceElement child : root.getChilden())
        {
            Global.tempLog("StacktraceStopOnException", "findErrorText child=" + child.getClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + " isError=" + (child instanceof IStacktraceError) //$NON-NLS-1$
                    + " name=[" + child.getName() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            if (child instanceof IStacktraceError errorNode)
                return errorNode.getName();
        }
        Global.tempLog("StacktraceStopOnException", "findErrorText no IStacktraceError child found"); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    /**
     * Штатное действие «Добавить точку останова по исключению» не привязано ни к одной команде
     * EDT (нет {@code definitionId}) — открываем тот же диалог, что и оно, напрямую его вызовом
     * через рефлексию (как {@code CreateDebuggerBreakpoints.resolveFactory()} для {@code DebugCorePlugin}).
     */
    private static void runAddExceptionBreakpointAction()
    {
        try
        {
            Bundle bundle = Platform.getBundle(ADD_ACTION_BUNDLE);
            if (bundle == null)
                return;
            Class<?> actionClass = bundle.loadClass(ADD_ACTION_CLASS);
            Object action = actionClass.getDeclaredConstructor().newInstance();
            Global.invokeVoid(action, "run", new Object[] { null }); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log("StacktraceStopOnException", "runAddExceptionBreakpointAction: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
