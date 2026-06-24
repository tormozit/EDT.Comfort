package tormozit;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.debug.core.DebugException;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Property-источник значения строки — как {@code IndexedValuesViewDelegate.getContextVariables}
 * + {@code getPropertyText} в EDT.
 */
final class DebugCollectionPropertyVariables
{
    private static final Pattern INDEXED_PLACEHOLDER_NAME = Pattern.compile("^\\[\\d+\\]$"); //$NON-NLS-1$

    private static final Set<String> ROW_METADATA_NAMES = Set.of(
        "индексы", "indexes", //$NON-NLS-1$ //$NON-NLS-2$
        "колонки", "columns", //$NON-NLS-1$ //$NON-NLS-2$
        "элементы", "elements"); //$NON-NLS-1$ //$NON-NLS-2$

    private DebugCollectionPropertyVariables() {}

    static boolean isIndexedPlaceholderName(String name)
    {
        if (name == null || name.isBlank())
            return false;
        return INDEXED_PLACEHOLDER_NAME.matcher(name.trim()).matches();
    }

    /**
     * {@code true}, если все имена — индексные placeholder'ы ({@code [0]}, {@code [1]}, …)
     * до появления имён полей в debug-модели.
     */
    static boolean isIndexedPlaceholderContext(IBslVariable[] context)
    {
        if (context == null || context.length == 0)
            return false;
        for (IBslVariable variable : context)
        {
            if (variable == null)
                continue;
            String name = variable.getName();
            if (name == null || name.isBlank())
                return false;
            if (!isIndexedPlaceholderName(name))
                return false;
        }
        return true;
    }

    /**
     * {@code true}, если все имена — метаданные строки таблицы/дерева
     * ({@code Индексы}, {@code Колонки}, {@code Элементы}), а не поля данных.
     */
    static boolean isRowMetadataContext(IBslVariable[] context)
    {
        if (context == null || context.length == 0)
            return false;
        for (IBslVariable variable : context)
        {
            if (variable == null)
                continue;
            String name = variable.getName();
            if (name == null || name.isBlank())
                return false;
            if (!ROW_METADATA_NAMES.contains(name.trim().toLowerCase(Locale.ROOT)))
                return false;
        }
        return true;
    }

    static boolean isAcceptableColumnContext(IBslVariable[] context)
    {
        return context != null && context.length > 0
            && !isRowMetadataContext(context)
            && !isIndexedPlaceholderContext(context);
    }

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
            IBslVariable[] vars = indexed.getVariables();
            if (ctx != null && ctx.length > 0 && !isRowMetadataContext(ctx)
                && !isIndexedPlaceholderContext(ctx))
                return ctx;
            if (vars != null && vars.length > 0 && !isIndexedPlaceholderContext(vars))
                return vars;
            return null;
        }

        IBslVariable[] vars = value.getVariables();
        if (vars != null && vars.length > 0 && !isIndexedPlaceholderContext(vars))
            return vars;
        return null;
    }
}
