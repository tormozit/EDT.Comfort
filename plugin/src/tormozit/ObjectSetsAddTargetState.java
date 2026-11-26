package tormozit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Активный набор для добавления объектов (один на проект, отдельно от выделенной строки).
 */
public final class ObjectSetsAddTargetState
{

    private static final String PREF_KEY = "objectSets.addTargetByProject"; //$NON-NLS-1$
    private static final char SEP = '\t';
    private static final ObjectSetsAddTargetState INSTANCE = new ObjectSetsAddTargetState();
    public static ObjectSetsAddTargetState getInstance()
    {

        return INSTANCE;
    }

    // Общий монитор защищает список наборов и привязки активных наборов к проектам.
    private final ObjectSets objectSets = ObjectSets.getInstance();
    private ScopedPreferenceStore prefs;
    private final Map<String, String> addTargetByProject = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private ObjectSetsAddTargetState() {}

    public void init(String pluginId)
    {
        synchronized (objectSets)
        {
            if (prefs != null)
                return;
            prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
            load();
            migrateFromLegacyFilterState();
        }
    }

    public void addListener(Runnable listener)
    {
        synchronized (objectSets)
        {
            if (listener != null)
                listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener)
    {
        synchronized (objectSets)
        {
            listeners.remove(listener);
        }
    }

    /** Имена проектов, у которых задан активный (add-target) набор. */
    public List<String> projectsWithAddTarget()
    {
        synchronized (objectSets)
        {
            return new java.util.ArrayList<>(addTargetByProject.keySet());
        }
    }

    /**
     * Активные <b>не основные</b> наборы открытых проектов — «ИмяПроекта: ИмяНабора», по строке
     * на проект, отсортировано по имени проекта. Проекты с активным набором «&lt;Основной&gt;»
     * пропускаются. Пусто, если таких наборов нет.
     */
    public String activeSetsPerProjectText()
    {
        synchronized (objectSets)
        {
            List<String> lines = new java.util.ArrayList<>();
            for (Map.Entry<String, String> e : addTargetByProject.entrySet())
            {
                if (e.getKey() == null || !ObjectSets.isProjectOpen(e.getKey()))
                    continue;
                ObjectSets.SetDef set = objectSets.getSetById(e.getValue());
                if (set != null && !set.isDefaultSet())
                    lines.add(e.getKey() + ": " + set.name); //$NON-NLS-1$
            }
            lines.sort(String.CASE_INSENSITIVE_ORDER);
            return String.join("\n", lines); //$NON-NLS-1$
        }
    }

    /**
     * Дописывает к {@code text} блок «Активные не основные наборы:» с перечнем по проектам
     * (см. {@link #activeSetsPerProjectText()}). Если таких наборов нет — «: отсутствуют» на той
     * же строке. Общий вид подсказок «Активные наборы» / кнопки-звезды навигатора.
     */
    public String withActiveSetsLines(String text)
    {
        String list = activeSetsPerProjectText();
        String header = "Активные не основные наборы:"; //$NON-NLS-1$
        return text + "\n" + header //$NON-NLS-1$
            + (list.isEmpty() ? " отсутствуют" : "\n" + list); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public ObjectSets.SetDef getAddTargetSet(String projectName)
    {
        synchronized (objectSets)
        {
            if (projectName == null || projectName.isBlank())
                return null;
            String setId = addTargetByProject.get(projectName);
            if (setId == null || setId.isBlank())
                return null;
            return objectSets.getSetById(setId);
        }
    }

    /** Все активные (●) наборы по проектам пусты. Системный набор считается «непустым» (фильтр по нему значим). */
    public boolean areAllAddTargetSetsEmpty()
    {
        synchronized (objectSets)
        {
            for (String setId : addTargetByProject.values())
            {
                if (setId == null || setId.isBlank())
                    continue;
                ObjectSets.SetDef set = objectSets.getSetById(setId);
                if (set != null && ((set.system && ObjectSets.isApplicable(set)) || !set.items.isEmpty()))
                    return false;
            }
            return true;
        }
    }

    /** Есть ли среди активных (●) наборов динамический (например, «<Измененные Git>»). */
    public boolean isAnyAddTargetSystemSet()
    {
        synchronized (objectSets)
        {
            for (String setId : addTargetByProject.values())
            {
                if (setId == null || setId.isBlank())
                    continue;
                ObjectSets.SetDef set = objectSets.getSetById(setId);
                if (set != null && set.system && ObjectSets.isApplicable(set))
                    return true;
            }
            return false;
        }
    }

    public boolean isAddTarget(String setId)
    {
        synchronized (objectSets)
        {
            if (setId == null || setId.isBlank())
                return false;
            ObjectSets.SetDef set = objectSets.getSetById(setId);
            if (set == null)
                return false;
            return setId.equals(addTargetByProject.get(set.projectName));
        }
    }

    public void setAddTarget(String setId)
    {
        synchronized (objectSets)
        {
            ObjectSets.SetDef set = objectSets.getSetById(setId);
            if (set == null)
                return;
            if (setId.equals(addTargetByProject.get(set.projectName)))
                return;
            addTargetByProject.put(set.projectName, setId);
            save();
            notifyChanged();
        }
    }

    public void ensureForProject(String projectName)
    {
        synchronized (objectSets)
        {
            if (projectName == null || projectName.isBlank())
                return;
            if (addTargetByProject.containsKey(projectName))
            {

                String setId = addTargetByProject.get(projectName);
                if (objectSets.getSetById(setId) != null)
                    return;
            }

            ObjectSets.SetDef pick = pickDefaultSet(projectName);
            if (pick != null)
            {

                addTargetByProject.put(projectName, pick.id);
                save();
                notifyChanged();
            }
        }
    }

    /**
     * Удалить add-target проект целиком (например, проект удалён из workspace).
     */
    public void removeProjectMapping(String projectName)
    {
        synchronized (objectSets)
        {
            if (projectName == null || projectName.isBlank())
                return;
            if (addTargetByProject.remove(projectName) == null)
                return;
            save();
            notifyChanged();
        }
    }

    public void onSetDeleted(String setId, String projectName)
    {
        synchronized (objectSets)
        {
            if (setId == null)
                return;
            boolean changed = false;
            for (Map.Entry<String, String> e : addTargetByProject.entrySet())
            {
                if (setId.equals(e.getValue()))
                {
                    changed = true;
                    break;
                }
            }
            if (!changed)
                return;
            if (projectName == null || projectName.isBlank())
            {
                addTargetByProject.entrySet().removeIf(e -> setId.equals(e.getValue()));
            }
            else
            {
                ObjectSets.SetDef pick = pickDefaultSet(projectName);
                if (pick != null)
                    addTargetByProject.put(projectName, pick.id);
                else
                    addTargetByProject.remove(projectName);
            }
            save();
            notifyChanged();
        }
    }

    private ObjectSets.SetDef pickDefaultSet(String projectName)
    {

        List<ObjectSets.SetDef> sets = objectSets.getSetsForProject(projectName);
        if (sets.isEmpty())
            return null;
        ObjectSets.SetDef fallback = null;
        for (ObjectSets.SetDef set : sets)
        {

            if (set.system)
                continue;
            if (set.isFixed())
                return set;
            if (fallback == null)
                fallback = set;
        }

        return fallback != null ? fallback : sets.get(0);
    }

    private void migrateFromLegacyFilterState()
    {

        ScopedPreferenceStore legacy = new ScopedPreferenceStore(InstanceScope.INSTANCE, Activator.PLUGIN_ID);
        String raw = legacy.getString("objectSets.navFilter.activeSets"); //$NON-NLS-1$
        if (raw == null || raw.isBlank() || !addTargetByProject.isEmpty())
            return;
        for (String line : raw.split("\n", -1)) //$NON-NLS-1$
        {

            if (line.isBlank())
                continue;
            String[] parts = line.split(String.valueOf(SEP), -1);
            if (parts.length < 2)
                continue;
            String projectName = unescape(parts[0]);
            if (projectName.isBlank())
                continue;
            for (int i = 1; i < parts.length; i++)
            {

                String setId = unescape(parts[i]);
                if (!setId.isBlank() && objectSets.getSetById(setId) != null)
                {

                    addTargetByProject.putIfAbsent(projectName, setId);
                    break;
                }

            }

        }

        if (!addTargetByProject.isEmpty())
            save();
    }

    private void notifyChanged()
    {

        if (ObjectSetsNavigatorFilterSupport.isActive())
            ObjectSetSubsystemsFilterBridge.onFilterStateChanged();
        for (Runnable listener : listeners)
        {

            try
            {

                listener.run();
            }

            catch (Exception ex)
            {

                ObjectSetsDebug.problem("addTarget listener: " + ex); //$NON-NLS-1$
            }

        }

    }

    private void save()
    {

        if (prefs == null)
            return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : addTargetByProject.entrySet())
        {

            if (e.getKey() == null || e.getValue() == null || e.getValue().isBlank())
                continue;
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(escape(e.getKey())).append(SEP).append(escape(e.getValue()));
        }

        prefs.setValue(PREF_KEY, sb.toString());
        try
        {

            prefs.save();
        }

        catch (Exception ex)
        {

            ObjectSetsDebug.problem("addTarget save: " + ex); //$NON-NLS-1$
        }

    }

    private void load()
    {

        if (prefs == null)
            return;
        addTargetByProject.clear();
        String raw = prefs.getString(PREF_KEY);
        if (raw == null || raw.isBlank())
            return;
        for (String line : raw.split("\n", -1)) //$NON-NLS-1$
        {

            if (line.isBlank())
                continue;
            String[] parts = line.split(String.valueOf(SEP), 2);
            if (parts.length < 2)
                continue;
            String projectName = unescape(parts[0]);
            String setId = unescape(parts[1]);
            if (!projectName.isBlank() && !setId.isBlank())
                addTargetByProject.put(projectName, setId);
        }

    }

    private static String escape(String s)
    {

        if (s == null)
            return ""; //$NON-NLS-1$
        return s.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\t", "\\t") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String unescape(String s)
    {

        if (s == null)
            return ""; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++)
        {

            char c = s.charAt(i);
            if (esc)
            {

                switch (c)
                {

                    case '\\': sb.append('\\'); break;
                    case 't': sb.append('\t'); break;
                    case 'n': sb.append('\n'); break;
                    default: sb.append('\\'); sb.append(c); break;
                }

                esc = false;
            }

            else if (c == '\\')
                esc = true;
            else
                sb.append(c);
        }

        if (esc)
            sb.append('\\');
        return sb.toString();
    }

}

