package tormozit;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com.e1c.g5.dt.applications.IApplication;

/**
 * Обработчик команды «Отладить ИР» в контекстном меню BSL-редактора и панелей отладчика.
 * Порт {@code ОтладитьОбъект()} из RDT.txt.
 */
public final class DebugIRHandler
{
    private static final String REQUIRE_DEFERRED_DEBUG = "Ложь"; //$NON-NLS-1$
    private static final String IR_CACHE_OS_PID_EXPR = "ирКэш.ИдентификаторПроцессаОСЛкс()"; //$NON-NLS-1$
    private static final String DEFAULT_DEBUG_CALL = "ирОбщий.От"; //$NON-NLS-1$
    private static final long EVAL_TIMEOUT_MS = 1_000;
    private static final long IR_WINDOW_WAIT_MS = 2_000;
    private static final Pattern IR_TOOL_WINDOW_TITLE =
        Pattern.compile("^(Консоль|Исследователь|Табличный|Дерево|Таблица)"); //$NON-NLS-1$

    private DebugIRHandler() {}

    public static boolean isApplicable(BslXtextEditor editor)
    {
        IProject project = getWorkspaceProject(editor);
        return project != null && DebugSessionHelper.isDebugSuspended(project);
    }

    public static boolean isApplicableForDebugView(IProject project)
    {
        // Только проверка suspend — без getSession(), чтобы не запускать подключение ИР при открытии меню.
        return project != null && DebugSessionHelper.isDebugSuspended(project);
    }

    public static void debugObject(BslXtextEditor editor)
    {
        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IProject wsProject = dtProject.getWorkspaceProject();
        IRSession irSession = IRApplication.getSession(dtProject);
        if (irSession == null || irSession.executor == null)
            return;

        String ref = GetRef.buildExtendedRef(editor, false);
        String selectedText = getSelectedText(editor);
        irSession.syncCodeEditorToIR(editor);

        irSession.executor.submit(() ->
        {
            try
            {
                ensureCodeEditor(irSession);
                irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
                String debugContext = ComBridge.toString(
                    irSession.invokeCodeEditor("ВычисляемыйКонтекстОтладчика")); //$NON-NLS-1$
                if (debugContext == null || debugContext.isBlank())
                {
                    toast("Отладить ИР",
                        "Команда применима только при остановке отладки в редакторе модуля"); //$NON-NLS-1$
                    return;
                }
                String textCall = debugContext.split("\\*")[0] + "От"; //$NON-NLS-1$ //$NON-NLS-2$
                String moduleRef = ComBridge.toString(
                    irSession.invokeCodeEditor("СсылкаСтрокиМодуля", null, false)); //$NON-NLS-1$
                String exprText = selectedText;
                if (exprText == null || exprText.isBlank())
                    exprText = ComBridge.toString(ComBridge.getProperty(irSession.codeEditor, "мКонтекст")); //$NON-NLS-1$
                if (exprText == null || exprText.isBlank())
                    return;
                if (isHierarchicalLogicalExpression(exprText))
                {
                    handleHierarchicalExpression(irSession, wsProject, exprText, ref);
                    return;
                }
                String expression = exprText.startsWith(textCall) ? exprText : buildDebugExpression(textCall, exprText, moduleRef);
                IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(wsProject);
                runDebugEvaluation("debugObject", frame, expression, wsProject, irSession); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.log("DebugIRHandler: " + e.getMessage()); //$NON-NLS-1$
                toast("Отладить ИР", "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    public static void debugVariable(IBslVariable variable)
    {
        if (variable == null)
            return;

        DebugViewsDebug.log("debugVariable enter name=" + safeVariableName(variable) //$NON-NLS-1$
            + " thread=" + DebugViewsDebug.threadBrief()); //$NON-NLS-1$

        IBslStackFrame frame;
        frame = variable.getStackFrame();
        if (frame == null)
        {
            DebugViewsDebug.problem("debugVariable: frame=null"); //$NON-NLS-1$
            return;
        }

        IProject project = getProjectFromStackFrame(frame);
        if (!isApplicableForDebugView(project))
        {
            DebugViewsDebug.log("debugVariable skip: not applicable project=" + project); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = Global.getDtProjectFromWorkspaceProject(project);
        IRSession irSession = IRApplication.getSession(dtProject);
        if (irSession == null || irSession.executor == null)
            return;

        String expr;
        expr = variable.toWatchExpression();
        if (expr == null || expr.isBlank())
            return;

        String evalExpr = "ирОбщий.ОтлКс(" + expr + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        DebugViewsDebug.log("debugVariable evalExpr=" + evalExpr); //$NON-NLS-1$
        irSession.executor.submit(() ->
        {
            try
            {
                runDebugEvaluation("debugVariable", frame, evalExpr, project, irSession); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.log("DebugIRHandler.debugVariable: " + e.getMessage()); //$NON-NLS-1$
                toast("Отладить ИР", "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    public static void debugWatchExpression(IWatchExpression watchExpr, IBslStackFrame frame, IProject project)
    {
        if (watchExpr == null || frame == null || project == null)
            return;
        if (!isApplicableForDebugView(project))
            return;

        IDtProject dtProject = Global.getDtProjectFromWorkspaceProject(project);
        IRSession irSession = IRApplication.getSession(dtProject);
        if (irSession == null || irSession.executor == null)
            return;

        String exprText = getWatchExpressionText(watchExpr);
        if (exprText == null || exprText.isBlank())
            return;

        irSession.executor.submit(() ->
        {
            try
            {
                if (isHierarchicalLogicalExpression(exprText))
                {
                    handleHierarchicalExpression(irSession, project, exprText, ""); //$NON-NLS-1$
                    return;
                }

                String textCall = DEFAULT_DEBUG_CALL;
                String moduleRef = ""; //$NON-NLS-1$
                BslXtextEditor editor = getActiveBslEditor();
                if (editor != null)
                {
                    irSession.syncCodeEditorToIR(editor);
                    ensureCodeEditor(irSession);
                    irSession.invokeCodeEditor("РазобратьТекущийКонтекст"); //$NON-NLS-1$
                    String debugContext = ComBridge.toString(
                        irSession.invokeCodeEditor("ВычисляемыйКонтекстОтладчика")); //$NON-NLS-1$
                    if (debugContext != null && !debugContext.isBlank())
                    {
                        textCall = debugContext.split("\\*")[0] + "От"; //$NON-NLS-1$ //$NON-NLS-2$
                        moduleRef = ComBridge.toString(
                            irSession.invokeCodeEditor("СсылкаСтрокиМодуля", null, false)); //$NON-NLS-1$
                    }
                }

                String expression = exprText.startsWith(textCall)
                    ? exprText
                    : buildDebugExpression(textCall, exprText, moduleRef);
                runDebugEvaluation("debugWatchExpression", frame, expression, project, irSession); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.log("DebugIRHandler.debugWatchExpression: " + e.getMessage()); //$NON-NLS-1$
                toast("Отладить ИР", "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    private static void runDebugEvaluation(
        String source, IBslStackFrame frame, String expression, IProject wsProject, IRSession irSession) throws Exception
    {
        long thickPid = 0;
        if (DebugSessionHelper.isThickClientDebug(wsProject))
            thickPid = evaluateThickClientOsPid(wsProject);
        DebugSessionHelper.EvalResult evalResult = evaluateOnUiThread(frame, expression);
        processEvalResult(irSession, evalResult, thickPid);
    }

    private static void processEvalResult(
        IRSession irSession, DebugSessionHelper.EvalResult evalResult, long thickPid) throws Exception
    {
        if (thickPid > 0)
            if (WinWindowActivator.waitForWindowTitle(thickPid, IR_TOOL_WINDOW_TITLE, IR_WINDOW_WAIT_MS))
                WinWindowActivator.activateMainWindow(thickPid);
        if (evalResult != null && evalResult.hasValue()
            && evalResult.valueText.toLowerCase(Locale.ROOT).contains("открыть объект для отладки")) //$NON-NLS-1$
        {
            Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
            ComBridge.invoke(irClient, "ОтладитьОтложенныйОбъектЛкс", evalResult.valueText); //$NON-NLS-1$
            irSession.showWindow();
            return;
        }
        if (evalResult == null || !evalResult.hasValue())
        {
            toast("Отладить ИР",
                "Не дождались снимка объекта. Либо повторите команду после его появления, "
                    + "либо активируйте окно отлаживаемого приложения.", //$NON-NLS-1$
                5_000);
        }
    }

    private static void handleHierarchicalExpression(
        IRSession irSession, IProject wsProject, String exprText, String ref) throws Exception
    {
        Object irCommon = irSession.getModule("ирОбщий"); //$NON-NLS-1$
        String wrapped = ComBridge.toString(
            ComBridge.invoke(irCommon, "ТекстВВыражениеВстроенногоЯзыкаЛкс", exprText)); //$NON-NLS-1$
        String evalExpr = "ирОбщий.РасшифровкаИерархическогоЛогическогоВыраженияЛкс(" + wrapped + ")"; //$NON-NLS-1$ //$NON-NLS-2$

        IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(wsProject);
        DebugSessionHelper.EvalResult evalResult = evaluateOnUiThread(frame, evalExpr);
        if (evalResult == null || !evalResult.hasValue())
            return;

        irSession.showWindow();
        irSession.openTextEditor(evalResult.valueText, ref != null ? ref : ""); //$NON-NLS-1$
    }

    private static String buildDebugExpression(String textCall, String exprText, String moduleRef)
    {
        if (moduleRef == null)
            moduleRef = ""; //$NON-NLS-1$
        String commaSuffix = exprText.contains(",") ? ",," : ",,,"; //$NON-NLS-1$ //$NON-NLS-2$
        return textCall + "(" + exprText + commaSuffix + " " + REQUIRE_DEFERRED_DEBUG //$NON-NLS-1$ //$NON-NLS-2$
            + ",,,, \"" + moduleRef + "\")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isHierarchicalLogicalExpression(String exprText)
    {
        if (exprText == null || !exprText.contains("\n") && !exprText.contains("\r")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;

        String firstLine = exprText.split("\\R", 2)[0].strip(); //$NON-NLS-1$
        if (firstLine.startsWith("(")) //$NON-NLS-1$
            firstLine = firstLine.substring(1).stripLeading();
        firstLine = firstLine.toLowerCase(Locale.ROOT);
        return "истина".equals(firstLine) || "ложь".equals(firstLine); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static long evaluateThickClientOsPid(IProject project)
    {
        return parseIntFromEval(evaluateOnUiThread(project, IR_CACHE_OS_PID_EXPR));
    }

    private static long parseIntFromEval(DebugSessionHelper.EvalResult result)
    {
        if (result == null || !result.hasValue())
            return 0L;

        String text = result.valueText.strip();
        if (text.isEmpty())
            return 0L;

        String numeric = stripIntegerGroupSeparators(text);
        if (numeric.isEmpty() || "+".equals(numeric)) //$NON-NLS-1$
            return 0L;
        if ("-".equals(numeric)) //$NON-NLS-1$
            return 0L;

        try
        {
            return Long.parseLong(numeric);
        }
        catch (NumberFormatException e)
        {
            Global.log("DebugIRHandler.parseIntFromEval: " + text); //$NON-NLS-1$
            return 0L;
        }
    }

    private static String stripIntegerGroupSeparators(String text)
    {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == ',' || c == '\'' || Character.isWhitespace(c))
                continue;
            if (c == '\u00A0' || c == '\u202F' || c == '\u2009') // NBSP, narrow NBSP, thin space
                continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static DebugSessionHelper.EvalResult evaluateOnUiThread(IProject project, String expression)
    {
        IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(project);
        return evaluateOnUiThread(frame, expression);
    }

    private static DebugSessionHelper.EvalResult evaluateOnUiThread(IBslStackFrame frame, String expression)
    {
        final DebugSessionHelper.EvalResult[] box = new DebugSessionHelper.EvalResult[1];
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return new DebugSessionHelper.EvalResult(null, null, "no display"); //$NON-NLS-1$

        display.syncExec(() ->
            box[0] = DebugSessionHelper.evaluateExpression(frame, expression, EVAL_TIMEOUT_MS));
        return box[0];
    }

    private static void ensureCodeEditor(IRSession irSession)
    {
        if (irSession.codeEditor != null)
            return;
        Object irCache = irSession.getModule("ирКэш"); //$NON-NLS-1$
        irSession.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
    }

    private static String getSelectedText(BslXtextEditor editor)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return ""; //$NON-NLS-1$
        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel))
            return ""; //$NON-NLS-1$
        if (textSel.getLength() == 0)
            return ""; //$NON-NLS-1$
        return textSel.getText();
    }

    private static String getWatchExpressionText(IWatchExpression watchExpr)
    {
        if (watchExpr instanceof IExpression expression)
            return expression.getExpressionText();
        return null;
    }

    private static BslXtextEditor getActiveBslEditor()
    {
        try
        {
            var workbench = PlatformUI.getWorkbench();
            if (workbench == null)
                return null;
            var window = workbench.getActiveWorkbenchWindow();
            if (window == null)
                return null;
            var page = window.getActivePage();
            if (page == null)
                return null;
            IEditorPart editor = page.getActiveEditor();
            if (editor instanceof BslXtextEditor bslEditor)
                return bslEditor;
            return null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    static IProject getProjectFromStackFrame(IBslStackFrame frame)
    {
        if (frame == null)
            return null;
        IDebugTarget debugTarget = frame.getDebugTarget();
        if (!(debugTarget instanceof IRuntimeDebugClientTarget target))
            return null;
        Optional<IApplication> app = target.getApplication();
        if (!app.isPresent())
            return null;
        Object appProject = Global.call(app.get(), "getProject"); //$NON-NLS-1$
        return appProject instanceof IProject ? (IProject) appProject : null;
    }

    private static IProject getWorkspaceProject(BslXtextEditor editor)
    {
        IDtProject dt = Global.getDtProjectFromBslEditor(editor);
        return dt != null ? dt.getWorkspaceProject() : null;
    }

    private static void toast(String title, String message)
    {
        toast(title, message, 3_000);
    }

    private static void toast(String title, String message, int durationMs)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, durationMs));
    }

    private static String safeVariableName(IBslVariable variable)
    {
        try
        {
            return variable.getName();
        }
        catch (Exception e)
        {
            return "?"; //$NON-NLS-1$
        }
    }
}
