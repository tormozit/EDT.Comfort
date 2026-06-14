package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.debug.core.DebugException;

import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.values.IBslIndexedValue;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;

/**
 * Колонки property: union имён из строк (как EDT {@code getMutualProperties}) или
 * {@code getContextVariables()} коллекции.
 */
final class CollectionContextColumnsResolver
{
    private static final int SAMPLE_ROWS = 16;
    private static final int MAX_DIRECT_CONTEXT_COLS = 32;
    private static final int MAX_COLUMN_COUNT = 256;

    private CollectionContextColumnsResolver() {}

    static IBslVariable[] resolve(IBslIndexedValue indexed) throws DebugException
    {
        if (indexed == null)
            return new IBslVariable[0];

        IBslVariable[] rowUnion = resolveRowSampleUnion(indexed);
        IBslVariable[] direct = indexed.getContextVariables();
        if (direct == null)
            direct = new IBslVariable[0];

        if (rowUnion.length > 0)
        {
            IBslVariable[] chosen = capColumns(rowUnion);
            ComfortCollectionDebug.step("columns.ctx", //$NON-NLS-1$
                "rowUnion=" + rowUnion.length //$NON-NLS-1$
                    + (direct.length > 0 ? " direct=" + direct.length : "")); //$NON-NLS-1$ //$NON-NLS-2$
            return chosen;
        }

        if (direct.length > 0 && direct.length <= MAX_DIRECT_CONTEXT_COLS)
        {
            ComfortCollectionDebug.step("columns.ctx", "direct=" + direct.length); //$NON-NLS-1$ //$NON-NLS-2$
            return direct;
        }

        if (direct.length > MAX_DIRECT_CONTEXT_COLS)
        {
            IBslVariable[] union = capColumns(direct);
            ComfortCollectionDebug.step("columns.ctx", //$NON-NLS-1$
                "direct=" + direct.length + " wide→directUnion=" + union.length); //$NON-NLS-1$ //$NON-NLS-2$
            return union;
        }

        IBslVariable[] mutual = resolveMutualFromRows(indexed);
        if (mutual.length > 0)
        {
            ComfortCollectionDebug.step("columns.ctx", "mutual=" + mutual.length); //$NON-NLS-1$ //$NON-NLS-2$
            return mutual;
        }

        if (direct.length > 0)
        {
            ComfortCollectionDebug.step("columns.ctx", "directFallback=" + direct.length); //$NON-NLS-1$ //$NON-NLS-2$
            return capColumns(direct);
        }
        return new IBslVariable[0];
    }

    /** Union property-имён из первых строк — как EDT {@code getMutualProperties}. */
    private static IBslVariable[] resolveRowSampleUnion(IBslIndexedValue indexed) throws DebugException
    {
        int size = indexed.getSize();
        if (size <= 0)
            return new IBslVariable[0];

        int count = Math.min(size, SAMPLE_ROWS);
        IBslVariable[] rows = indexed.getVariables(0, count);
        if (rows == null || rows.length == 0)
            return new IBslVariable[0];

        Map<String, IBslVariable> templates = new LinkedHashMap<>();
        for (IBslVariable row : rows)
        {
            if (row == null)
                continue;
            IBslValue value = row.getValue();
            if (value == null || value.isPending())
                continue;
            if (!value.isEvaluated())
                value.evaluate();
            if (value.isPending())
                continue;

            IBslVariable[] props = CollectionPropertyVariables.propertySource(value);
            if (props == null)
                continue;
            for (IBslVariable prop : props)
            {
                if (prop == null)
                    continue;
                String name = prop.getName();
                if (name == null || name.isBlank())
                    continue;
                templates.putIfAbsent(name, prop);
            }
        }
        return templates.values().toArray(new IBslVariable[0]);
    }

    private static IBslVariable[] capColumns(IBslVariable[] direct)
    {
        if (direct.length <= MAX_COLUMN_COUNT)
            return direct;
        ComfortCollectionDebug.step("columns.ctx", //$NON-NLS-1$
            "directUnion capped " + direct.length + "→" + MAX_COLUMN_COUNT); //$NON-NLS-1$ //$NON-NLS-2$
        return Arrays.copyOf(direct, MAX_COLUMN_COUNT);
    }

    private static IBslVariable[] resolveMutualFromRows(IBslIndexedValue indexed) throws DebugException
    {
        int size = indexed.getSize();
        if (size <= 0)
            return new IBslVariable[0];

        int count = Math.min(size, SAMPLE_ROWS);
        IBslVariable[] rows = indexed.getVariables(0, count);
        if (rows == null || rows.length == 0)
            return new IBslVariable[0];

        Set<String> mutual = null;
        Map<String, IBslVariable> templates = new LinkedHashMap<>();
        for (IBslVariable row : rows)
        {
            Set<String> rowNames = new LinkedHashSet<>();
            collectPropertyNames(row, rowNames, templates);
            if (rowNames.isEmpty())
                continue;
            if (mutual == null)
                mutual = new LinkedHashSet<>(rowNames);
            else
                mutual.retainAll(rowNames);
        }

        if (mutual == null || mutual.isEmpty())
            return new IBslVariable[0];

        List<IBslVariable> result = new ArrayList<>();
        for (String name : mutual)
        {
            IBslVariable template = templates.get(name);
            if (template != null)
                result.add(template);
        }
        ComfortCollectionDebug.step("columns.ctx", "mutualRows=" + rows.length); //$NON-NLS-1$ //$NON-NLS-2$
        return result.toArray(new IBslVariable[0]);
    }

    private static void collectPropertyNames(
        IBslVariable row,
        Set<String> names,
        Map<String, IBslVariable> templates) throws DebugException
    {
        if (row == null)
            return;
        IBslValue value = row.getValue();
        if (value == null)
            return;

        IBslVariable[] props = CollectionPropertyVariables.propertySource(value);
        if (props == null)
            return;
        for (IBslVariable prop : props)
        {
            if (prop == null)
                continue;
            String name = prop.getName();
            if (name == null || name.isBlank())
                continue;
            if (names.add(name))
                templates.putIfAbsent(name, prop);
        }
    }
}
