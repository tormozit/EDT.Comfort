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
     * Подпись колонки таблицы значений: {@code Имя}/{@code Name} у определения колонки,
     * иначе {@link IBslVariable#getName()} (может быть {@code [0]}).
     */
    static String columnLabel(IBslVariable columnDef)
    {
        if (columnDef == null)
            return null;
        String fromProperty = readNameProperty(columnDef);
        if (fromProperty != null && !fromProperty.isBlank())
            return fromProperty.trim();
        String name = columnDef.getName();
        return name != null && !name.isBlank() ? name.trim() : null;
    }

    private static String readNameProperty(IBslVariable columnDef)
    {
        try
        {
            IBslValue value = columnDef.getValue();
            if (value == null)
                return null;
            if (!value.isEvaluated())
                value.evaluate();
            if (value.isPending())
                return null;
            IBslVariable[] props = null;
            if (value instanceof IBslIndexedValue indexed)
            {
                props = indexed.getContextVariables();
                if (props == null || props.length == 0)
                    props = indexed.getVariables();
            }
            if (props == null || props.length == 0)
                props = value.getVariables();
            if (props == null)
                return null;
            IBslVariable nameVar = findByName(props, "Имя"); //$NON-NLS-1$
            if (nameVar == null)
                nameVar = findByName(props, "Name"); //$NON-NLS-1$
            if (nameVar == null)
                return null;
            IBslValue nameValue = nameVar.getValue();
            if (nameValue == null)
                return null;
            if (!nameValue.isEvaluated())
                nameValue.evaluate();
            if (nameValue.isPending())
                return null;
            String text = nameValue.getValueString();
            if (text == null)
                return null;
            text = text.trim();
            if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"')
                text = text.substring(1, text.length() - 1);
            return text.isBlank() ? null : text;
        }
        catch (DebugException e)
        {
            return null;
        }
    }

    private static IBslVariable findByName(IBslVariable[] variables, String name)
    {
        if (variables == null || name == null)
            return null;
        for (IBslVariable variable : variables)
        {
            if (variable == null)
                continue;
            String variableName = variable.getName();
            if (variableName != null && variableName.equalsIgnoreCase(name))
                return variable;
        }
        return null;
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

    /** Все элементы имеют не-placeholder подпись ({@link #columnLabel}). */
    static boolean hasResolvedColumnLabels(IBslVariable[] context)
    {
        if (context == null || context.length == 0)
            return false;
        for (IBslVariable variable : context)
        {
            if (variable == null)
                return false;
            String label = columnLabel(variable);
            if (label == null || label.isBlank() || isIndexedPlaceholderName(label))
                return false;
        }
        return true;
    }

    /**
     * Свойства строки для отображения ячеек — как {@code IndexedValuesViewDelegate.getPropertyText}
     * / {@code getContextVariables(IValue)}: без фильтра placeholder для schema.
     */
    static IBslVariable[] propertyVariablesForRow(IBslVariable rowVar) throws DebugException
    {
        if (rowVar == null)
            return null;
        return propertyVariablesForValue(rowVar.getValue());
    }

    static IBslVariable[] propertyVariablesForValue(IBslValue value) throws DebugException
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
            IBslVariable[] vars = indexed.getVariables();
            if (vars != null && vars.length > 0)
                return vars;
            return null;
        }

        IBslVariable[] vars = value.getVariables();
        return vars != null && vars.length > 0 ? vars : null;
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
