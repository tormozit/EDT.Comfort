package tormozit;

import org.eclipse.debug.core.DebugException;

import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Kick evaluation: EDT 2024 — {@code evaluate()}, EDT 2026 — метода нет, есть {@code reevaluate()}.
 */
final class BslValueEvaluate
{
    private BslValueEvaluate() {}

    static void ensureEvaluated(IBslValue value)
    {
        if (value == null || value.isEvaluated())
            return;
        try
        {
            value.evaluate();
        }
        catch (NoSuchMethodError e)
        {
            value.reevaluate();
        }
        catch (DebugException e)
        {
            // ещё не готово
        }
    }
}
