package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Constructor;
import java.net.URL;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Хелперы открытия popup-инспектора EDT для {@link DebugInspectorHook} (hover «Инспектировать»).
 */
public final class BslInspectSupport
{
    private static final String BUNDLE_DEBUG_UI = "com._1c.g5.v8.dt.debug.ui"; //$NON-NLS-1$
    private static final String ICON_INSPECT_EDT = "icons/etool16/insp_sbook.gif"; //$NON-NLS-1$
    private static final String CLASS_INSPECT_POPUP =
        "com._1c.g5.v8.dt.internal.debug.ui.dialogs.PendingAwareInspectPopupDialog"; //$NON-NLS-1$
    private static final String CMD_INSPECT_EDT = "com._1c.g5.v8.dt.debug.ui.commands.Inspect"; //$NON-NLS-1$

    private BslInspectSupport() {}

    static void openInspectPopup(
        Shell parent,
        Point anchor,
        IWatchExpression watch,
        IBslStackFrame frame,
        IDebugMonitoringManager monitoringManager)
    {
        if (parent == null || parent.isDisposed() || watch == null || frame == null)
            return;
        try
        {
            Class<?> dialogClass = loadDebugUiClass(CLASS_INSPECT_POPUP);
            Constructor<?> ctor = dialogClass.getConstructor(
                Shell.class, Point.class, String.class, IWatchExpression.class, IDebugMonitoringManager.class);
            Object dialog = ctor.newInstance(parent, anchor, CMD_INSPECT_EDT, watch, monitoringManager);
            watch.setExpressionContext(frame);
            Global.invoke(dialog, "open"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DebugInspectorDebug.problem("openInspectPopup: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    static IBslStackFrame resolveInspectStackFrame(IEditorPart editor)
    {
        IProject project = null;
        if (editor instanceof BslXtextEditor bsl)
        {
            IDtProject dtProject = DebugIRHandler.getDtProjectFromBslEditor(bsl);
            if (dtProject != null)
                project = dtProject.getWorkspaceProject();
        }
        IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(project);
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);
        return frame;
    }

    static IWatchExpression newWatchExpression(String exprText)
    {
        if (exprText == null || exprText.isBlank())
            return null;
        return DebugPlugin.getDefault().getExpressionManager().newWatchExpression(exprText);
    }

    /**
     * Watch-выражение для строки таблицы «Значения» (элемент коллекции).
     * {@link IBslVariable#toWatchExpression()} для индекса вида {@code [57]} часто пустой
     * (canEvaluate=false), хотя {@link IBslValue#getPath()} уже содержит полный путь.
     * EDT объединяет {@code ValuesView.getFullSelectionPath()} и {@code delegate.getSelectionPath()}.
     */
    static String resolveValuesInspectExpression(AbstractDebugView view, IBslVariable variable)
    {
        String toWatch = safeToWatchExpression(variable);
        if (!toWatch.isBlank())
            return toWatch;

        String fromValuePath = pathTextFromVariable(variable);
        if (!fromValuePath.isBlank())
        {
            DebugValuesDebug.step("inspect.expr", "valuePath " + DebugValuesDebug.quote(fromValuePath)); //$NON-NLS-1$ //$NON-NLS-2$
            return fromValuePath;
        }

        String fromSelectionPath = expressionFromCombinedSelectionPath(view);
        if (!fromSelectionPath.isBlank())
            return fromSelectionPath;

        String fromDelegateInput = expressionFromDelegateInput(view, variable);
        if (!fromDelegateInput.isBlank())
        {
            DebugValuesDebug.step("inspect.expr", "delegateInput " + DebugValuesDebug.quote(fromDelegateInput)); //$NON-NLS-1$ //$NON-NLS-2$
            return fromDelegateInput;
        }

        DebugValuesDebug.step("inspect.expr", "empty toWatch=" + DebugValuesDebug.quote(toWatch) //$NON-NLS-1$ //$NON-NLS-2$
            + " valuePath=" + DebugValuesDebug.quote(fromValuePath) //$NON-NLS-1$
            + " selectionPath=" + DebugValuesDebug.quote(fromSelectionPath) //$NON-NLS-1$
            + " delegateInput=" + DebugValuesDebug.quote(fromDelegateInput)); //$NON-NLS-1$
        return ""; //$NON-NLS-1$
    }

    private static String pathTextFromVariable(IBslVariable variable)
    {
        if (variable == null)
            return ""; //$NON-NLS-1$
        try
        {
            IBslValue value = variable.getValue();
            if (value == null)
                return ""; //$NON-NLS-1$
            BslValuePath path = value.getPath();
            if (path == null)
                return ""; //$NON-NLS-1$
            String text = path.toString();
            return text != null ? text.trim() : ""; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DebugValuesDebug.step("inspect.expr", "valuePath failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return ""; //$NON-NLS-1$
        }
    }

    private static String expressionFromCombinedSelectionPath(AbstractDebugView view)
    {
        List<Object> path = buildValuesSelectionPath(view);
        if (path.isEmpty())
            return ""; //$NON-NLS-1$

        for (int i = path.size() - 1; i >= 0; i--)
        {
            String expr = expressionFromPathSegment(path.get(i));
            if (!expr.isBlank())
            {
                DebugValuesDebug.step("inspect.expr", "selectionPath[" + i + "] " + DebugValuesDebug.quote(expr)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return expr;
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static List<Object> buildValuesSelectionPath(AbstractDebugView view)
    {
        List<Object> result = new ArrayList<>();
        Object fullPath = invokeValuePath(view, "getFullSelectionPath"); //$NON-NLS-1$
        if (fullPath instanceof List<?> fullList)
        {
            for (Object item : fullList)
                result.add(item);
        }

        Object delegate = Global.getField(view, "delegate"); //$NON-NLS-1$
        Object selectionPath = Global.invoke(delegate, "getSelectionPath"); //$NON-NLS-1$
        if (selectionPath instanceof List<?> selectionList && !selectionList.isEmpty())
            result.addAll(selectionList);

        return result;
    }

    private static String expressionFromPathSegment(Object segment)
    {
        if (segment instanceof IBslVariable variable)
        {
            String pathText = pathTextFromVariable(variable);
            if (!pathText.isBlank())
                return pathText;
            return safeToWatchExpression(variable);
        }
        if (segment instanceof IBslValue bslValue)
        {
            BslValuePath path = bslValue.getPath();
            if (path != null)
            {
                String text = path.toString();
                return text != null ? text.trim() : ""; //$NON-NLS-1$
            }
        }
        return expressionFromValuePath(segment);
    }

    private static String expressionFromDelegateInput(AbstractDebugView view, IBslVariable variable)
    {
        if (view == null || variable == null)
            return ""; //$NON-NLS-1$
        try
        {
            Object delegate = Global.getField(view, "delegate"); //$NON-NLS-1$
            Object inputValue = Global.getField(delegate, "value"); //$NON-NLS-1$
            if (!(inputValue instanceof IBslValue bslValue))
                return ""; //$NON-NLS-1$

            BslValuePath basePath = bslValue.getPath();
            if (basePath == null)
                return ""; //$NON-NLS-1$

            String base = basePath.toString();
            if (base == null || base.isBlank())
                return ""; //$NON-NLS-1$
            base = base.trim();

            String name = variable.getName();
            if (name == null || name.isBlank())
                return base;
            name = name.trim();
            if (name.startsWith("[")) //$NON-NLS-1$
                return base + name;
            if (name.startsWith(".")) //$NON-NLS-1$
                return base + name;
            return base + '.' + name;
        }
        catch (Exception e)
        {
            DebugValuesDebug.step("inspect.expr", "delegateInput failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return ""; //$NON-NLS-1$
        }
    }

    private static String safeToWatchExpression(IBslVariable variable)
    {
        if (variable == null)
            return ""; //$NON-NLS-1$
        try
        {
            String expr = variable.toWatchExpression();
            return expr != null ? expr.trim() : ""; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DebugValuesDebug.step("inspect.expr", "toWatch failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return ""; //$NON-NLS-1$
        }
    }

    private static Object invokeValuePath(AbstractDebugView view, String method)
    {
        if (view == null || method == null)
            return null;
        try
        {
            return Global.invoke(view, method);
        }
        catch (Exception e)
        {
            DebugValuesDebug.step("inspect.expr", method + " failed: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static String expressionFromValuePath(Object pathObj)
    {
        if (pathObj instanceof BslValuePath path)
        {
            String expr = path.getExpression();
            return expr != null ? expr.trim() : ""; //$NON-NLS-1$
        }
        if (pathObj instanceof String text)
            return text.trim();
        if (pathObj instanceof List<?> list)
        {
            for (int i = list.size() - 1; i >= 0; i--)
            {
                String expr = expressionFromPathSegment(list.get(i));
                if (!expr.isBlank())
                    return expr;
            }
        }
        return ""; //$NON-NLS-1$
    }

    static IWatchExpression toWatchExpression(Object element)
    {
        if (element instanceof IWatchExpression watch)
            return watch;
        if (element instanceof IBslVariable variable)
        {
            try
            {
                String expr = variable.toWatchExpression();
                return newWatchExpression(expr);
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
        return null;
    }

    static String watchExpressionText(IWatchExpression watch)
    {
        if (watch instanceof IExpression expression)
        {
            String text = expression.getExpressionText();
            if (text != null && !text.isBlank())
                return text.trim();
        }
        return ""; //$NON-NLS-1$
    }

    static Image loadInspectCommandImage()
    {
        try
        {
            Bundle bundle = Platform.getBundle(BUNDLE_DEBUG_UI);
            if (bundle == null)
                return null;
            URL url = bundle.getEntry(ICON_INSPECT_EDT);
            if (url != null)
                return ImageDescriptor.createFromURL(url).createImage(false);
        }
        catch (RuntimeException ignored)
        {
            // иконка опциональна
        }
        return null;
    }

    private static Class<?> loadDebugUiClass(String className) throws ClassNotFoundException
    {
        Bundle bundle = Platform.getBundle(BUNDLE_DEBUG_UI);
        if (bundle == null)
            throw new ClassNotFoundException(className + " (bundle " + BUNDLE_DEBUG_UI + " not installed)"); //$NON-NLS-1$ //$NON-NLS-2$
        if (bundle.getState() != Bundle.ACTIVE)
        {
            try
            {
                bundle.start(Bundle.START_TRANSIENT);
            }
            catch (Exception ignored)
            {
                // bundle activation optional
            }
        }
        return bundle.loadClass(className);
    }
}
