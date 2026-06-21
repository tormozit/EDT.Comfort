package tormozit;

import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPart;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Определение indexed-коллекций и открытие окна «Коллекция».
 */
public final class DebugCollectionShowSupport
{
    static final String VALUES_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.values.ValuesView"; //$NON-NLS-1$

    private DebugCollectionShowSupport() {}

    public static boolean isIndexedCollection(Object element)
    {
        return canOpenFrom(element);
    }

    public static boolean canOpenFrom(Object element)
    {
        return resolveIndexedValue(element) != null;
    }

    public static boolean canOpenFromValuesView(AbstractDebugView view)
    {
        return resolveIndexedValueFromView(view) != null;
    }

    public static boolean canOpenFromHandler(IWorkbenchPart part, ISelection selection)
    {
        if (part instanceof AbstractDebugView view && isValuesView(part))
            return canOpenFromValuesView(view);
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty())
            return canOpenFrom(structured.getFirstElement());
        return false;
    }

    public static boolean tryOpenFromHandler(IWorkbenchPart part, ISelection selection)
    {
        if (!DebugSessionHelper.isDebugSuspended(null))
            return false;
        if (part instanceof AbstractDebugView view && isValuesView(part))
        {
            if (openFromValuesView(view))
                return true;
        }
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty())
        {
            Object element = structured.getFirstElement();
            if (canOpenFrom(element))
            {
                AbstractDebugView debugView = part instanceof AbstractDebugView v ? v : null;
                openFromElement(element, debugView);
                return true;
            }
        }
        return false;
    }

    public static boolean openFromValuesView(AbstractDebugView view)
    {
        IBslIndexedValue indexed = resolveIndexedValueFromView(view);
        if (indexed == null)
            return false;
        IBslStackFrame frame = indexed.getStackFrame();
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);
        BslValuePath path = indexed.getPath();
        if (path == null && indexed instanceof IBslValue bslValue)
            path = bslValue.getPath();
        DebugCollectionOpener.open(indexed, frame, path, DebugCollectionOpener.OpenMode.NORMAL);
        DebugCollectionDebug.step("open", pathKey(path)); //$NON-NLS-1$
        return true;
    }

    public static void openFromElement(Object element)
    {
        openFromElement(element, null);
    }

    public static void openFromElement(Object element, AbstractDebugView view)
    {
        IBslIndexedValue indexed = resolveIndexedValue(element);
        if (indexed == null)
        {
            String kind = element != null ? element.getClass().getSimpleName() : "null"; //$NON-NLS-1$
            DebugCollectionDebug.problem("open: not indexed " + kind); //$NON-NLS-1$
            return;
        }

        IBslStackFrame frame = indexed.getStackFrame();
        if (frame == null && element instanceof IBslVariable variable)
            frame = variable.getStackFrame();
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);

        BslValuePath path = resolvePathForOpen(element, indexed, view);
        DebugCollectionOpener.open(indexed, frame, path, DebugCollectionOpener.OpenMode.NORMAL);
        DebugCollectionDebug.step("open", pathKey(path)); //$NON-NLS-1$
    }

    public static void openFromVariable(IBslVariable variable)
    {
        openFromElement(variable);
    }

    public static void openFromWatchExpression(IWatchExpression watch)
    {
        openFromElement(watch);
    }

    static IBslIndexedValue resolveIndexedValue(Object element)
    {
        if (element == null)
            return null;
        if (element instanceof IBslIndexedValue indexed)
            return indexed;
        if (element instanceof IBslVariable variable)
        {
            try
            {
                return resolveIndexedValue(variable.getValue());
            }
            catch (Exception e)
            {
                DebugCollectionDebug.problem("resolve variable: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }
        }
        if (element instanceof IWatchExpression watch)
            return resolveIndexedValue(watch.getValue());
        if (element instanceof IExpression expression)
            return resolveIndexedValue(expression.getValue());
        if (element instanceof IValue value)
            return resolveIndexedValueFromValue(value);
        return null;
    }

    private static IBslIndexedValue resolveIndexedValueFromView(AbstractDebugView view)
    {
        if (view == null)
            return null;
        try
        {
            Object delegate = Global.getField(view, "delegate"); //$NON-NLS-1$
            Object inputValue = Global.getField(delegate, "value"); //$NON-NLS-1$
            if (inputValue instanceof IBslIndexedValue indexed)
                return indexed;
        }
        catch (Exception e)
        {
            DebugCollectionDebug.problem("openFromValuesView: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private static boolean isValuesView(IWorkbenchPart part)
    {
        return part != null && part.getSite() != null
            && VALUES_VIEW_ID.equals(part.getSite().getId());
    }

    private static IBslIndexedValue resolveIndexedValueFromValue(IValue value)
    {
        if (value instanceof IBslIndexedValue indexed)
            return indexed;
        return null;
    }

    private static BslValuePath resolvePathForOpen(
        Object element,
        IBslIndexedValue indexed,
        AbstractDebugView view)
    {
        String exprText = resolveExpressionTextForOpen(element, view);
        BslValuePath fallback = indexed.getPath();
        if (fallback == null && indexed instanceof IBslValue bslValue)
            fallback = bslValue.getPath();

        if (!exprText.isBlank())
        {
            logPathResolutionIfDifferent(exprText, fallback);
            return new BslValuePath(exprText);
        }
        return fallback;
    }

    private static String resolveExpressionTextForOpen(Object element, AbstractDebugView view)
    {
        if (element instanceof IWatchExpression watch)
        {
            String text = BslInspectSupport.watchExpressionText(watch);
            if (!text.isBlank())
                return text;
        }
        if (element instanceof IBslVariable variable)
        {
            if (view != null && isValuesView(view))
            {
                String text = BslInspectSupport.resolveValuesInspectExpression(view, variable);
                if (!text.isBlank())
                    return text;
            }
            String text = BslInspectSupport.resolveVariableInspectExpression(variable);
            if (!text.isBlank())
                return text;
        }
        return ""; //$NON-NLS-1$
    }

    private static void logPathResolutionIfDifferent(String exprText, BslValuePath fallback)
    {
        String resolved = exprText.trim();
        String fallbackKey = pathKey(fallback);
        if (!resolved.equals(fallbackKey))
            DebugCollectionDebug.step("path", "resolved=" + resolved + " indexedPath=" + fallbackKey); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String pathKey(BslValuePath path)
    {
        if (path == null)
            return ""; //$NON-NLS-1$
        String expr = path.getExpression();
        if (expr != null && !expr.isBlank())
            return expr.trim();
        String text = path.toString();
        return text != null ? text.trim() : ""; //$NON-NLS-1$
    }
}
