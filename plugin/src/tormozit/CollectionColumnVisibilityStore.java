package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Настройки видимости колонок по выражению пути коллекции (сессия JVM).
 */
final class CollectionColumnVisibilityStore
{
    static final int WIDE_SCHEMA_THRESHOLD = 8;

    private static final int DEFAULT_VISIBLE_PROPERTY_COUNT = 5;

    private static final String[] PREFERRED_PROPERTY_HEADERS = {
        "Имя", "Name", //$NON-NLS-1$ //$NON-NLS-2$
        "Представление", "Presentation", //$NON-NLS-1$ //$NON-NLS-2$
        "Тип", "Type", //$NON-NLS-1$ //$NON-NLS-2$
        "Синоним", "Synonym", //$NON-NLS-1$ //$NON-NLS-2$
        "Значение", "Value", //$NON-NLS-1$ //$NON-NLS-2$
    };

    private static final Map<String, boolean[]> VISIBILITY = new HashMap<>();
    private static final Map<String, int[]> ORDER = new HashMap<>();
    private static final Map<String, String> PRESENTATION = new HashMap<>();
    private static final Map<String, Boolean> FILTER_BY_PRESENTATION = new HashMap<>();

    /** Приоритет колонки «Представление» по умолчанию (рус / англ). */
    private static final String[][] DEFAULT_PRESENTATION_PRIORITY = {
        { "Имя", "Name" }, //$NON-NLS-1$ //$NON-NLS-2$
        { "Поле", "Field" }, //$NON-NLS-1$ //$NON-NLS-2$
        { "ПутьКДанным", "PathToData", "DataPath" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        { "Заголовок", "Title", "Caption" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        { "Ключ", "Key" }, //$NON-NLS-1$ //$NON-NLS-2$
        { "Значение", "Value" }, //$NON-NLS-1$ //$NON-NLS-2$
    };

    private CollectionColumnVisibilityStore() {}

    static boolean[] visibilityFor(String pathKey, int columnCount, CollectionColumnModel columns)
    {
        boolean[] stored = VISIBILITY.get(pathKey);
        if (stored != null && stored.length == columnCount)
            return stored.clone();
        int count = columns.allColumns().size();
        if (count > WIDE_SCHEMA_THRESHOLD)
            return wideUnionVisibility(count, orderWithPreferred(columns));
        return defaultVisibility(columns);
    }

    /**
     * Широкая union-схема по умолчанию: первые {@link CollectionColumnModel#MAX_DEFAULT_VISIBLE_COLUMNS}
     * в preferred-порядке; остальные скрыты (колонка-затычка в таблице).
     */
    static boolean[] wideUnionVisibility(int columnCount, int[] order)
    {
        boolean[] vis = new boolean[columnCount];
        int shown = 0;
        if (order != null)
        {
            for (int modelIdx : order)
            {
                if (modelIdx < 0 || modelIdx >= columnCount)
                    continue;
                if (shown >= CollectionColumnModel.MAX_DEFAULT_VISIBLE_COLUMNS)
                    break;
                vis[modelIdx] = true;
                shown++;
            }
        }
        if (shown == 0 && columnCount > 0)
            vis[0] = true;
        return vis;
    }

    /** Порядок: индекс, preferred property, остальные. */
    static int[] orderWithPreferred(CollectionColumnModel columns)
    {
        int count = columns.allColumns().size();
        List<Integer> ordered = new ArrayList<>();
        Set<Integer> used = new HashSet<>();

        for (int i = 0; i < count; i++)
        {
            CollectionColumnModel.Column col = columns.allColumns().get(i);
            if (col.kind == CollectionColumnModel.Kind.INDEX)
            {
                ordered.add(i);
                used.add(i);
                break;
            }
        }

        int typeModel = columns.typeModelIndex();
        if (typeModel >= 0 && !used.contains(typeModel))
        {
            ordered.add(typeModel);
            used.add(typeModel);
        }

        for (String preferred : PREFERRED_PROPERTY_HEADERS)
        {
            for (int i = 0; i < count; i++)
            {
                if (used.contains(i))
                    continue;
                CollectionColumnModel.Column col = columns.allColumns().get(i);
                if (col.kind != CollectionColumnModel.Kind.PROPERTY)
                    continue;
                if (col.header != null && col.header.equalsIgnoreCase(preferred))
                {
                    ordered.add(i);
                    used.add(i);
                    break;
                }
            }
        }

        for (int i = 0; i < count; i++)
        {
            if (!used.contains(i))
                ordered.add(i);
        }

        int[] result = new int[ordered.size()];
        for (int i = 0; i < ordered.size(); i++)
            result[i] = ordered.get(i);
        return result;
    }

    static boolean[] visibilityFor(String pathKey, int columnCount)
    {
        boolean[] stored = VISIBILITY.get(pathKey);
        if (stored == null || stored.length != columnCount)
        {
            boolean[] defaults = new boolean[columnCount];
            for (int i = 0; i < columnCount; i++)
                defaults[i] = true;
            return defaults;
        }
        return stored.clone();
    }

    static int[] orderFor(String pathKey, int columnCount)
    {
        int[] stored = ORDER.get(pathKey);
        if (stored == null || stored.length != columnCount)
        {
            int[] defaults = new int[columnCount];
            for (int i = 0; i < columnCount; i++)
                defaults[i] = i;
            return defaults;
        }
        return stored.clone();
    }

    static void save(String pathKey, boolean[] visibility, int[] order)
    {
        save(pathKey, visibility, order, null);
    }

    static void save(String pathKey, boolean[] visibility, int[] order, String presentationHeader)
    {
        if (pathKey == null || pathKey.isBlank())
            return;
        if (visibility != null)
            VISIBILITY.put(pathKey, visibility.clone());
        if (order != null)
            ORDER.put(pathKey, order.clone());
        if (presentationHeader != null && !presentationHeader.isBlank())
            PRESENTATION.put(pathKey, presentationHeader);
    }

    static void savePresentation(String pathKey, String presentationHeader)
    {
        save(pathKey, null, null, presentationHeader);
    }

    static boolean filterByPresentationFor(String pathKey)
    {
        if (pathKey == null || pathKey.isBlank())
            return false;
        return Boolean.TRUE.equals(FILTER_BY_PRESENTATION.get(pathKey));
    }

    static void saveFilterByPresentation(String pathKey, boolean value)
    {
        if (pathKey == null || pathKey.isBlank())
            return;
        if (value)
            FILTER_BY_PRESENTATION.put(pathKey, Boolean.TRUE);
        else
            FILTER_BY_PRESENTATION.remove(pathKey);
    }

    static String presentationFor(String pathKey, CollectionColumnModel columns)
    {
        String stored = PRESENTATION.get(pathKey);
        if (stored != null && columns.findPresentationColumnByHeader(stored) >= 0)
            return stored;
        return defaultPresentationHeader(columns);
    }

    static String defaultPresentationHeader(CollectionColumnModel columns)
    {
        if (columns == null)
            return null;
        for (String[] group : DEFAULT_PRESENTATION_PRIORITY)
        {
            for (String candidate : group)
            {
                String resolved = columns.resolveHeaderInSchema(candidate);
                if (resolved != null)
                    return resolved;
            }
        }
        return null;
    }

    private static boolean[] defaultVisibility(CollectionColumnModel columns)
    {
        int count = columns.allColumns().size();
        boolean[] vis = new boolean[count];
        if (count <= WIDE_SCHEMA_THRESHOLD)
        {
            for (int i = 0; i < count; i++)
                vis[i] = true;
            return vis;
        }

        for (int i = 0; i < count; i++)
        {
            CollectionColumnModel.Column col = columns.allColumns().get(i);
            vis[i] = col.kind == CollectionColumnModel.Kind.INDEX
                || CollectionColumnModel.isTypeColumn(col);
        }

        int propertyShown = 0;
        Set<Integer> shown = new HashSet<>();

        for (String preferred : PREFERRED_PROPERTY_HEADERS)
        {
            if (propertyShown >= DEFAULT_VISIBLE_PROPERTY_COUNT)
                break;
            for (int i = 0; i < count; i++)
            {
                if (shown.contains(i))
                    continue;
                CollectionColumnModel.Column col = columns.allColumns().get(i);
                if (col.kind != CollectionColumnModel.Kind.PROPERTY)
                    continue;
                if (col.header != null && col.header.equalsIgnoreCase(preferred))
                {
                    vis[i] = true;
                    shown.add(i);
                    propertyShown++;
                    break;
                }
            }
        }

        for (int i = 0; i < count && propertyShown < DEFAULT_VISIBLE_PROPERTY_COUNT; i++)
        {
            if (shown.contains(i))
                continue;
            CollectionColumnModel.Column col = columns.allColumns().get(i);
            if (col.kind == CollectionColumnModel.Kind.PROPERTY)
            {
                vis[i] = true;
                shown.add(i);
                propertyShown++;
            }
        }

        if (!anyVisible(vis))
            vis[0] = true;
        return vis;
    }

    private static boolean anyVisible(boolean[] vis)
    {
        for (boolean v : vis)
        {
            if (v)
                return true;
        }
        return false;
    }
}
