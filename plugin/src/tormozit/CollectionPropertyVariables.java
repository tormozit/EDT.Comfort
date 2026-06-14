package tormozit;

import org.eclipse.debug.core.DebugException;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Property-источник значения строки — как {@code IndexedValuesViewDelegate.getContextVariables}
 * + {@code getPropertyText} в EDT.
 */
final class CollectionPropertyVariables
{
    private CollectionPropertyVariables() {}

    /**
     * Context-свойства элемента строки ({@code getContextVariables()} или {@code getVariables()}).
     * {@code null} — value/context ещё не готов.
     */
    static IBslVariable[] propertySource(IBslValue value) throws DebugException
    {
        if (value == null || value.isPending())
            return null;
        if (!value.isEvaluated())
            value.evaluate();
        if (value.isPending())
            return null;

        if (value instanceof IBslIndexedValue indexed)
        {
            IBslVariable[] ctx = indexed.getContextVariables();
            if (ctx != null && ctx.length > 0)
                return ctx;
            return null;
        }

        IBslVariable[] vars = value.getVariables();
        if (vars != null && vars.length > 0)
            return vars;
        return null;
    }
}
