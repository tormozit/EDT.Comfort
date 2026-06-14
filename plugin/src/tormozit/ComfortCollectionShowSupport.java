package tormozit;

import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IWatchExpression;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;

/**
 * Определение indexed-коллекций и открытие окна «Коллекция».
 */
public final class ComfortCollectionShowSupport
{
    private ComfortCollectionShowSupport() {}

    public static boolean isIndexedCollection(Object element)
    {
        IBslIndexedValue indexed = resolveIndexedValue(element);
        boolean result = indexed != null;
        if (ComfortCollectionDebug.isEnabled() && element != null)
        {
            ComfortCollectionDebug.step(
                "enable", //$NON-NLS-1$
                element.getClass().getSimpleName() + " indexed=" + result); //$NON-NLS-1$
        }
        return result;
    }

    public static void openFromElement(Object element)
    {
        IBslIndexedValue indexed = resolveIndexedValue(element);
        if (indexed == null)
        {
            String kind = element != null ? element.getClass().getSimpleName() : "null"; //$NON-NLS-1$
            ComfortCollectionDebug.problem("open: not indexed " + kind); //$NON-NLS-1$
            return;
        }

        IBslStackFrame frame = indexed.getStackFrame();
        if (frame == null && element instanceof IBslVariable variable)
            frame = variable.getStackFrame();
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);

        BslValuePath path = indexed.getPath();
        ComfortCollectionOpener.open(indexed, frame, path, ComfortCollectionOpener.OpenMode.NORMAL);
        ComfortCollectionDebug.step("open", pathKey(path)); //$NON-NLS-1$
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
                ComfortCollectionDebug.problem("resolve variable: " + e.getMessage()); //$NON-NLS-1$
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

    private static IBslIndexedValue resolveIndexedValueFromValue(IValue value)
    {
        if (value instanceof IBslIndexedValue indexed)
            return indexed;
        return null;
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
