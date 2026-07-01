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
import org.eclipse.swt.SWT;
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

import org.eclipse.debug.core.DebugException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.Map;

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

    /** Результат построения пути для инспектора строки коллекции. */
    static final class InspectPathResolution
    {
        enum Source
        {
            PRESENTATION, VAR_NAME, FICTITIOUS, WATCH
        }

        final String path;
        final boolean fictitious;
        final Source source;

        private InspectPathResolution(String path, boolean fictitious, Source source)
        {
            this.path = path != null ? path : ""; //$NON-NLS-1$
            this.fictitious = fictitious;
            this.source = source;
        }

        static InspectPathResolution real(String path, Source source)
        {
            return new InspectPathResolution(path, false, source);
        }

        static InspectPathResolution fictitious(String path)
        {
            return new InspectPathResolution(path, true, Source.FICTITIOUS);
        }
    }

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
            registerInspectShell(dialog, watch);
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
            IDtProject dtProject = Global.getDtProjectFromBslEditor(bsl);
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

    /**
     * Watch-выражение для переменной без контекста ValuesView (окно «Коллекция»).
     */
    static String resolveVariableInspectExpression(IBslVariable variable)
    {
        String toWatch = safeToWatchExpression(variable);
        if (!toWatch.isBlank())
            return toWatch;
        return pathTextFromVariable(variable);
    }

    /**
     * Полный текст watch-выражения для строки «Коллекция», «Значения» или инспектора.
     * Та же логика ветвления, что у {@link #openInspectForVariable}.
     */
    static String resolveExpressionTextForVariable(
        IBslVariable variable,
        DebugCollectionTableModel collectionModel,
        int logicalRow,
        AbstractDebugView valuesView)
    {
        if (variable == null)
            return ""; //$NON-NLS-1$
        if (needsInspectPathResolution(variable))
        {
            if (collectionModel != null && logicalRow >= 0)
                return resolveCollectionRowInspectPath(collectionModel, logicalRow, variable).path;
            if (valuesView != null)
                return resolveValuesInspectPath(valuesView, variable).path;
            return resolveVariableInspectPath(variable).path;
        }
        if (valuesView != null)
            return resolveValuesInspectExpression(valuesView, variable);
        return resolveVariableInspectExpression(variable);
    }

    /**
     * Единая точка инспекта из окна «Коллекция» или «Значения».
     */
    static void openInspectForVariable(
        Shell parent,
        Point anchor,
        IBslVariable variable,
        IBslStackFrame frame,
        IDebugMonitoringManager monitoringManager,
        Shell keepVisibleShell,
        String columnHeader,
        DebugCollectionTableModel collectionModel,
        int logicalRow,
        AbstractDebugView valuesView)
    {
        if (variable == null || parent == null || parent.isDisposed())
            return;

        if (frame == null)
            frame = variable.getStackFrame();
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);
        if (frame == null)
            return;

        InspectPathResolution resolution = null;
        if (needsInspectPathResolution(variable))
        {
            if (collectionModel != null && logicalRow >= 0)
                resolution = resolveCollectionRowInspectPath(collectionModel, logicalRow, variable);
            else if (valuesView != null)
                resolution = resolveValuesInspectPath(valuesView, variable);
            else
                resolution = resolveVariableInspectPath(variable);
            logInspectPathResolution(resolution, valuesView != null);
        }

        String exprText = resolveExpressionTextForVariable(
            variable, collectionModel, logicalRow, valuesView);

        if (exprText.isBlank())
        {
            if (valuesView != null)
                DebugValuesDebug.step("inspect", "empty expression"); //$NON-NLS-1$ //$NON-NLS-2$
            else
                DebugCollectionDebug.problem("inspect: empty expression"); //$NON-NLS-1$
            return;
        }

        InspectorPendingFocus.set(columnHeader);

        if (InspectorRegistry.activateExisting(exprText, keepVisibleShell))
        {
            InspectorRegistry.schedulePendingFocus(exprText);
            return;
        }

        if (resolution != null && resolution.fictitious)
        {
            openInspectPopupForVariable(parent, anchor, variable, exprText, monitoringManager);
            if (valuesView != null)
                DebugValuesDebug.step("inspect.direct", exprText + " fictitious"); //$NON-NLS-1$ //$NON-NLS-2$
            else
                DebugCollectionDebug.step("inspect.direct", exprText); //$NON-NLS-1$
        }
        else
        {
            IWatchExpression watch = newWatchExpression(exprText);
            if (watch == null)
                return;
            openInspectPopup(parent, anchor, watch, frame, monitoringManager);
            if (valuesView != null)
                DebugValuesDebug.step("inspect.expr", exprText); //$NON-NLS-1$ //$NON-NLS-2$
            else
                DebugCollectionDebug.step("inspect", exprText); //$NON-NLS-1$
        }

        InspectorRegistry.raiseAbove(exprText, keepVisibleShell);
    }

    static InspectPathResolution resolveCollectionRowInspectPath(
        DebugCollectionTableModel model,
        int logicalRow,
        IBslVariable variable)
    {
        if (model == null || variable == null)
            return resolveVariableInspectPath(variable);

        String base = collectionBasePath(model);
        if (!base.isBlank())
        {
            String presentationKey = presentationKeyFromModel(model, logicalRow);
            if (!presentationKey.isBlank())
            {
                String path = base + formatBracketKeySuffix(presentationKey);
                return InspectPathResolution.real(path, InspectPathResolution.Source.PRESENTATION);
            }

            String fromName = pathFromVariableNameSuffix(base, variable);
            if (!fromName.isBlank())
                return InspectPathResolution.real(fromName, InspectPathResolution.Source.VAR_NAME);
        }

        return fictitiousPathFromVariable(variable);
    }

    static InspectPathResolution resolveValuesInspectPath(AbstractDebugView view, IBslVariable variable)
    {
        if (view == null || variable == null)
            return resolveVariableInspectPath(variable);

        String fromDelegate = expressionFromDelegateInput(view, variable);
        if (!fromDelegate.isBlank())
            return InspectPathResolution.real(fromDelegate, InspectPathResolution.Source.VAR_NAME);

        return fictitiousPathFromVariable(variable);
    }

    static InspectPathResolution resolveVariableInspectPath(IBslVariable variable)
    {
        return fictitiousPathFromVariable(variable);
    }

    static String formatBracketKeySuffix(String rawKey)
    {
        String key = stripPresentationKey(rawKey);
        if (key.isEmpty())
            return ""; //$NON-NLS-1$
        if (isIntegerKey(key))
            return '[' + key + ']';
        return "[\"" + escapeBslStringKey(key) + "\"]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    static void openInspectPopupForVariable(
        Shell parent,
        Point anchor,
        IBslVariable variable,
        String displayPath,
        IDebugMonitoringManager monitoringManager)
    {
        if (parent == null || parent.isDisposed() || variable == null)
            return;

        String pathText = displayPath != null ? displayPath.trim() : ""; //$NON-NLS-1$
        if (pathText.isEmpty())
            return;

        IWatchExpression watch = newWatchExpression(pathText);
        if (watch == null)
            return;

        IBslStackFrame frame = variable.getStackFrame();
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);
        if (frame == null)
            return;

        try
        {
            Class<?> dialogClass = loadDebugUiClass(CLASS_INSPECT_POPUP);
            Constructor<?> ctor = dialogClass.getConstructor(
                Shell.class, Point.class, String.class, IWatchExpression.class, IDebugMonitoringManager.class);
            Object dialog = ctor.newInstance(parent, anchor, CMD_INSPECT_EDT, watch, monitoringManager);
            watch.setExpressionContext(frame);
            Global.invoke(dialog, "open"); //$NON-NLS-1$
            Global.invoke(dialog, "setInput", variable); //$NON-NLS-1$
            disableFictitiousInspectExpressionCombo(dialog);
            registerInspectShell(dialog, watch);
        }
        catch (Exception e)
        {
            DebugInspectorDebug.problem("openInspectPopupForVariable: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** Combo «Выражение» — только просмотр фиктивного пути (поле {@code searchCombo} в EDT). */
    private static void disableFictitiousInspectExpressionCombo(Object inspectDialog)
    {
        if (inspectDialog == null)
            return;
        try
        {
            Object comboObj = Global.getField(inspectDialog, "searchCombo"); //$NON-NLS-1$
            if (!(comboObj instanceof org.eclipse.swt.widgets.Combo combo) || combo.isDisposed())
                return;
            removeComboListeners(combo, SWT.KeyDown);
            removeComboListeners(combo, SWT.Selection);
            removeComboListeners(combo, SWT.FocusIn);
            removeComboListeners(combo, SWT.FocusOut);
        }
        catch (Exception ignored)
        {
            // опционально
        }
    }

    private static void removeComboListeners(org.eclipse.swt.widgets.Combo combo, int eventType)
    {
        for (org.eclipse.swt.widgets.Listener listener : combo.getListeners(eventType))
            combo.removeListener(eventType, listener);
    }

    private static boolean needsInspectPathResolution(IBslVariable variable)
    {
        if (variable == null)
            return false;
        try
        {
            String toWatch = safeToWatchExpression(variable);
            if (!toWatch.isBlank())
                return false;
            IBslValue value = variable.getValue();
            if (value == null)
                return false;
            BslValuePath path = value.getPath();
            return path != null && !path.canEvaluate();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static InspectPathResolution fictitiousPathFromVariable(IBslVariable variable)
    {
        String path = pathTextFromVariable(variable);
        if (path.isBlank())
            path = safeToWatchExpression(variable);
        return InspectPathResolution.fictitious(path);
    }

    private static String collectionBasePath(DebugCollectionTableModel model)
    {
        if (model == null || model.path == null)
            return ""; //$NON-NLS-1$
        BslValuePath path = model.path;
        String expr = path.getExpression();
        if (expr != null && !expr.isBlank())
            return expr.trim();
        String text = path.toString();
        return text != null ? text.trim() : ""; //$NON-NLS-1$
    }

    private static String presentationKeyFromModel(DebugCollectionTableModel model, int logicalRow)
    {
        int presModel = model.columns.presentationModelIndex();
        if (presModel <= 0)
            return ""; //$NON-NLS-1$
        int visibleCol = model.columns.visibleIndexOfModelColumn(presModel);
        if (visibleCol < 0)
            return ""; //$NON-NLS-1$
        try
        {
            String text = model.extractCellTextInJob(logicalRow, visibleCol);
            return text != null ? text.trim() : ""; //$NON-NLS-1$
        }
        catch (DebugException e)
        {
            DebugCollectionDebug.problem("inspect.presentation: " + e.getMessage()); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    private static String pathFromVariableNameSuffix(String base, IBslVariable variable)
    {
        if (base == null || base.isBlank() || variable == null)
            return ""; //$NON-NLS-1$
        String name = variable.getName();
        if (name == null || name.isBlank())
            return ""; //$NON-NLS-1$
        name = name.trim();
        if (!name.startsWith("[")) //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        return base + name;
    }

    private static String stripPresentationKey(String raw)
    {
        if (raw == null)
            return ""; //$NON-NLS-1$
        String key = raw.trim();
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
            key = key.substring(1, key.length() - 1);
        return key.trim();
    }

    private static boolean isIntegerKey(String key)
    {
        if (key == null || key.isEmpty())
            return false;
        int start = key.charAt(0) == '-' ? 1 : 0;
        if (start >= key.length())
            return false;
        for (int i = start; i < key.length(); i++)
        {
            if (!Character.isDigit(key.charAt(i)))
                return false;
        }
        return true;
    }

    private static String escapeBslStringKey(String key)
    {
        return key.replace("\"", "\"\""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void logInspectPathResolution(InspectPathResolution resolution, boolean valuesView)
    {
        if (resolution == null)
            return;
        String msg = resolution.path + " fictitious=" + resolution.fictitious //$NON-NLS-1$
            + " source=" + resolution.source; //$NON-NLS-1$
        if (valuesView)
            DebugValuesDebug.step("inspect.path", msg); //$NON-NLS-1$
        else
            DebugCollectionDebug.step("inspect.path", msg); //$NON-NLS-1$
    }

    private static void registerInspectShell(Object dialog, IWatchExpression watch)
    {
        if (dialog == null || watch == null)
            return;
        try
        {
            Object shellObj = Global.invoke(dialog, "getShell"); //$NON-NLS-1$
            if (shellObj instanceof Shell shell && !shell.isDisposed())
                InspectorRegistry.register(watchExpressionText(watch), shell);
        }
        catch (Exception ignored)
        {
            // регистрация опциональна
        }
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
            // Не стартуем бандл принудительно — EDT сама его активирует.
            // Вызывающий код retry-ит при необходимости.
            throw new ClassNotFoundException(
                className + " (bundle state=" + bundle.getState() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return bundle.loadClass(className);
    }

    /**
     * Dedup popup-инспекторов по полному watch-выражению.
     */
    private static final class InspectorRegistry
    {
        private static final Map<String, WeakReference<Shell>> OPEN = new ConcurrentHashMap<>();

        private InspectorRegistry() {}

        static boolean activateExisting(String expression)
        {
            return activateExisting(expression, null);
        }

        /**
         * Активировать уже открытый инспектор.
         *
         * @param aboveShell если задан (окно «Коллекция» с pin), поднять инспектор поверх него
         */
        static boolean activateExisting(String expression, Shell aboveShell)
        {
            Shell shell = shellFor(expression);
            if (shell == null)
                return false;
            raiseShell(shell, aboveShell);
            DebugCollectionDebug.step("inspect", "activate existing " + expression); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }

        /** Запланировать фокус свойства в уже открытом инспекторе (после {@link InspectorPendingFocus#set}). */
        static void schedulePendingFocus(String expression)
        {
            Shell shell = shellFor(expression);
            if (shell == null || shell.isDisposed())
                return;
            shell.getDisplay().asyncExec(() ->
            {
                if (!shell.isDisposed())
                    DebugInspectorTreeEnhancement.schedulePendingFocusForShell(shell);
            });
        }

        /** Поднять инспектор по выражению (после {@code openInspectPopup}). */
        static void raiseAbove(String expression, Shell aboveShell)
        {
            Shell shell = shellFor(expression);
            if (shell != null)
                raiseShell(shell, aboveShell);
        }

        static void register(String expression, Shell shell)
        {
            if (expression == null || expression.isBlank() || shell == null || shell.isDisposed())
                return;
            OPEN.put(expression, new WeakReference<>(shell));
            shell.addDisposeListener(e -> OPEN.remove(expression));
        }

        private static Shell shellFor(String expression)
        {
            if (expression == null || expression.isBlank())
                return null;
            purgeDisposed();
            WeakReference<Shell> ref = OPEN.get(expression);
            if (ref == null)
                return null;
            Shell shell = ref.get();
            if (shell == null || shell.isDisposed())
            {
                OPEN.remove(expression);
                return null;
            }
            return shell;
        }

        private static void raiseShell(Shell shell, Shell aboveShell)
        {
            if (shell == null || shell.isDisposed())
                return;
            shell.getDisplay().asyncExec(() -> {
                if (shell.isDisposed())
                    return;
                shell.setMinimized(false);
                if (aboveShell != null && !aboveShell.isDisposed())
                    WinWindowActivator.setShellAboveOwner(shell, aboveShell, true);
                shell.forceActive();
                shell.forceFocus();
            });
        }

        private static void purgeDisposed()
        {
            Iterator<Map.Entry<String, WeakReference<Shell>>> it = OPEN.entrySet().iterator();
            while (it.hasNext())
            {
                Map.Entry<String, WeakReference<Shell>> e = it.next();
                Shell shell = e.getValue().get();
                if (shell == null || shell.isDisposed())
                    it.remove();
            }
        }
    }

}
