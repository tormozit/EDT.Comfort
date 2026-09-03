package tormozit;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Хранилище наборов объектов метаданных (per-set project binding).
 */
public final class ObjectSets
{
    private static final String PREF_KEY = "objectSets.data"; //$NON-NLS-1$
    private static final String SET_MARKER = "@SET"; //$NON-NLS-1$
    private static final String DEFAULT_SET_NAME = "<Основной>"; //$NON-NLS-1$
    private static final String SYSTEM_CHANGED_SET_NAME = "<Измененные Git>"; //$NON-NLS-1$
    private static final String SYSTEM_CHANGED_ID_PREFIX = "@changed:"; //$NON-NLS-1$
    private static final String INFOBASE_CHANGED_ID_PREFIX = "@ibchanged:"; //$NON-NLS-1$
    /** Разделитель имени проекта и UUID базы в идентификаторе набора: в имени проекта недопустим. */
    private static final char INFOBASE_CHANGED_ID_SEPARATOR = '|';
    private static final char SEP = '\t';

    /**
     * Вид набора. Определяет, откуда берётся состав и что с набором можно делать.
     *
     * <ul>
     *   <li>{@link #USER} — обычный набор: состав редактируется вручную и хранится в настройках;</li>
     *   <li>{@link #GIT_CHANGED} — «&lt;Измененные Git&gt;»: состав вычисляется по git-статусу проекта,
     *       набор создаётся автоматически и не удаляется;</li>
     *   <li>{@link #INFOBASE_CHANGED} — «&lt;Измененные <i>ИмяБазы</i>&gt;»: состав вычисляется по
     *       объектам, ожидающим синхронизации с информационной базой; набор создаётся по требованию
     *       и может быть удалён пользователем. В состав можно добавлять объекты — они помечаются
     *       в штатном хранилище синхронизации EDT и уходят в базу при следующем обновлении.</li>
     * </ul>
     */
    public enum SetKind
    {
        USER("0"), //$NON-NLS-1$
        GIT_CHANGED("1"), //$NON-NLS-1$
        INFOBASE_CHANGED("2"); //$NON-NLS-1$

        final String code;

        SetKind(String code)
        {
            this.code = code;
        }

        /** Состав вычисляется динамически (git / база), а не из сохранённого списка. */
        public boolean isDynamic()
        {
            return this != USER;
        }

        static SetKind byCode(String code)
        {
            for (SetKind kind : values())
            {
                if (kind.code.equals(code))
                    return kind;
            }
            return USER;
        }
    }

    private static final ObjectSets INSTANCE = new ObjectSets();

    public static ObjectSets getInstance()
    {
        return INSTANCE;
    }

    public static final class Item
    {
        public final String key;
        public final String navRef;
        public final String displayName;
        public final String ownName;

        public Item(String key, String navRef, String displayName, String ownName)
        {
            this.key = key != null ? key : ""; //$NON-NLS-1$
            this.navRef = navRef != null ? navRef : ""; //$NON-NLS-1$
            this.displayName = displayName != null ? displayName : ""; //$NON-NLS-1$
            this.ownName = ownName != null ? ownName : ""; //$NON-NLS-1$
        }
    }

    public static final class SetDef
    {
        public final String id;
        public String name;
        public final String projectName;
        /** Вид набора: обычный или один из динамических. */
        public final SetKind kind;
        /**
         * Динамический набор (git / база): состав вычисляется, а не берётся из {@link #items}.
         * Синоним {@code kind.isDynamic()} — оставлен, т.к. на него опирается весь UI наборов.
         */
        public final boolean system;
        public final List<Item> items = new ArrayList<>();

        /** Имя фиксировано: динамический набор или набор «<Основной>» — переименование запрещено. */
        public boolean isFixed()
        {
            return system || DEFAULT_SET_NAME.equals(name);
        }

        /** Набор «<Основной>» (не путать с динамическими «<Измененные …>»). */
        public boolean isDefaultSet()
        {
            return DEFAULT_SET_NAME.equals(name);
        }

        /**
         * Набор можно удалить: «<Измененные Git>» по git создаётся автоматически и удалению не
         * подлежит, набор по изменениям базы — подлежит (пересоздаётся кликом в «Приложениях»).
         */
        public boolean isDeletable()
        {
            return kind != SetKind.GIT_CHANGED;
        }

        /**
         * В набор можно добавлять объекты: обычный — в сохранённый состав, набор по базе —
         * в штатное хранилище синхронизации EDT. Git-набор — нет.
         */
        public boolean allowsAddedItems()
        {
            return kind != SetKind.GIT_CHANGED;
        }

        SetDef(String id, String name, String projectName)
        {
            this(id, name, projectName, SetKind.USER);
        }

        SetDef(String id, String name, String projectName, SetKind kind)
        {
            this.id = id;
            this.name = name;
            this.projectName = projectName != null ? projectName : ""; //$NON-NLS-1$
            this.kind = kind != null ? kind : SetKind.USER;
            this.system = this.kind.isDynamic();
        }
    }

    private final LinkedHashMap<String, SetDef> setsById = new LinkedHashMap<>();
    private ScopedPreferenceStore prefs;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    private ObjectSets() {}

    public synchronized void init(String pluginId)
    {
        if (prefs != null)
            return;
        prefs = new ScopedPreferenceStore(InstanceScope.INSTANCE, pluginId);
        load();
        ensureDefaultSetsForAllOpenProjects();
    }

    public void addChangeListener(Runnable listener)
    {
        if (listener != null)
            changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener)
    {
        changeListeners.remove(listener);
    }

    public synchronized SetDef createSet(String name, String projectName)
    {
        if (name == null || name.isBlank() || projectName == null || projectName.isBlank())
            return null;
        String id = UUID.randomUUID().toString();
        SetDef set = new SetDef(id, name.trim(), projectName.trim());
        setsById.put(id, set);
        save();
        notifyChanged();
        return set;
    }

    public synchronized boolean renameSet(String setId, String newName)
    {
        SetDef set = setsById.get(setId);
        if (set == null || set.isFixed() || newName == null || newName.isBlank())
            return false;
        set.name = newName.trim();
        save();
        notifyChanged();
        return true;
    }

    public synchronized boolean deleteSet(String setId)
    {
        SetDef set = setsById.get(setId);
        if (set == null || !set.isDeletable())
            return false;
        SetDef removed = setsById.remove(setId);
        if (removed == null)
            return false;
        if (projectExistsInWorkspace(removed.projectName))
        {
            if (!hasUserSets(removed.projectName))
                createDefaultSet(removed.projectName);
            ObjectSetsAddTargetState.getInstance().onSetDeleted(setId, removed.projectName);
        }
        else
        {
            ObjectSetsAddTargetState.getInstance().removeProjectMapping(removed.projectName);
        }
        save();
        notifyChanged();
        return true;
    }

    /**
     * Удалить все наборы проекта (например, при удалении проекта из workspace).
     * @return число удалённых наборов
     */
    public synchronized int removeSetsForProject(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return 0;
        List<String> removedIds = new ArrayList<>();
        for (SetDef set : setsById.values())
        {
            if (projectName.equals(set.projectName))
                removedIds.add(set.id);
        }
        if (removedIds.isEmpty())
            return 0;
        for (String setId : removedIds)
            setsById.remove(setId);
        ObjectSetsAddTargetState.getInstance().removeProjectMapping(projectName);
        save();
        notifyChanged();
        return removedIds.size();
    }

    /** Проект ещё существует в workspace (удалённый проект — {@code false}). */
    private boolean projectExistsInWorkspace(String projectName)
    {
        try
        {
            return ResourcesPlugin.getWorkspace().getRoot().getProject(projectName).exists();
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("projectExistsInWorkspace: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /** Проект открыт в workspace (закрытый или отсутствующий — {@code false}). */
    static boolean isProjectOpen(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return false;
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            return project != null && project.isOpen();
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("isProjectOpen: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /** Оповестить слушателей без записи (закрытие/открытие проекта и т.п.). */
    void fireChanged()
    {
        notifyChanged();
    }

    /**
     * Если у проекта нет ни одного набора — создать «Основной».
     * @return созданный набор или {@code null}
     */
    public synchronized SetDef ensureDefaultSetForProject(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return null;
        if (hasUserSets(projectName))
            return null;
        SetDef set = createDefaultSet(projectName);
        save();
        notifyChanged();
        return set;
    }

    /**
     * Для каждого открытого проекта workspace гарантирует хотя бы один набор.
     * @return число созданных наборов
     */
    public synchronized int ensureDefaultSetsForAllOpenProjects()
    {
        int created = 0;
        try
        {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (project == null || !project.isOpen())
                    continue;
                if (ensureDefaultSetForProjectNoNotify(project.getName()) != null)
                    created++;
                if (ensureSystemSetForProjectNoNotify(project.getName()) != null)
                    created++;
            }
        }
        catch (Exception e)
        {
            ObjectSetsDebug.problem("ensureDefaultSetsForAllOpenProjects: " + e.getMessage()); //$NON-NLS-1$
        }
        if (created > 0)
        {
            save();
            notifyChanged();
        }
        return created;
    }

    private SetDef ensureDefaultSetForProjectNoNotify(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return null;
        if (hasUserSets(projectName))
            return null;
        return createDefaultSet(projectName);
    }

    /**
     * Системный набор «<Измененные Git>» для проекта (если ещё не создан).
     * @return созданный набор или {@code null}
     */
    public synchronized SetDef ensureSystemSetForProject(String projectName)
    {
        SetDef created = ensureSystemSetForProjectNoNotify(projectName);
        if (created != null)
        {
            save();
            notifyChanged();
        }
        return created;
    }

    private SetDef ensureSystemSetForProjectNoNotify(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return null;
        if (!ObjectSetsItems.isProjectUnderGit(projectName))
            return null;
        String id = systemSetId(projectName);
        if (setsById.containsKey(id))
            return null;
        SetDef set = new SetDef(id, SYSTEM_CHANGED_SET_NAME, projectName.trim(), SetKind.GIT_CHANGED);
        setsById.put(id, set);
        return set;
    }

    /**
     * Набор «&lt;Измененные <i>ИмяБазы</i>&gt;» с составом по объектам, ожидающим синхронизации
     * с информационной базой. Если набор уже есть — возвращается он же (с обновлённым именем базы,
     * если базу переименовали).
     *
     * @param projectName имя проекта
     * @param infobaseUuid UUID информационной базы
     * @param infobaseName отображаемое имя информационной базы
     * @return набор или {@code null}, если аргументы пусты
     */
    public synchronized SetDef ensureInfobaseChangedSet(
        String projectName, String infobaseUuid, String infobaseName)
    {
        if (projectName == null || projectName.isBlank()
            || infobaseUuid == null || infobaseUuid.isBlank())
        {
            return null;
        }
        String id = infobaseChangedSetId(projectName.trim(), infobaseUuid.trim());
        String name = infobaseChangedSetName(infobaseName);
        SetDef existing = setsById.get(id);
        if (existing != null)
        {
            if (name.equals(existing.name))
                return existing;
            existing.name = name;
            save();
            notifyChanged();
            return existing;
        }
        SetDef set = new SetDef(id, name, projectName.trim(), SetKind.INFOBASE_CHANGED);
        setsById.put(id, set);
        save();
        notifyChanged();
        return set;
    }

    /**
     * Удалить набор «&lt;Измененные <i>ИмяБазы</i>&gt;» — приложение с этой базой убрали из проекта.
     *
     * @param projectName имя проекта
     * @param infobaseUuid UUID информационной базы
     * @return {@code true}, если набор был и удалён
     */
    public synchronized boolean deleteInfobaseChangedSet(String projectName, String infobaseUuid)
    {
        if (projectName == null || projectName.isBlank() || infobaseUuid == null || infobaseUuid.isBlank())
            return false;
        return deleteSet(infobaseChangedSetId(projectName.trim(), infobaseUuid.trim()));
    }

    /**
     * Убрать наборы по базам, которых у проекта больше нет (приложение удалили, пока EDT была
     * закрыта, либо событие об удалении не дошло).
     *
     * @param projectName имя проекта
     * @param liveInfobaseUuids UUID баз, связанных с проектом сейчас; {@code null} — не проверять
     *            (список недоступен, удалять ничего нельзя)
     * @return число удалённых наборов
     */
    public synchronized int pruneInfobaseChangedSets(String projectName, Set<String> liveInfobaseUuids)
    {
        if (projectName == null || projectName.isBlank() || liveInfobaseUuids == null)
            return 0;
        List<String> staleIds = new ArrayList<>();
        for (SetDef set : setsById.values())
        {
            if (set.kind != SetKind.INFOBASE_CHANGED || !projectName.equals(set.projectName))
                continue;
            String uuid = infobaseUuidOf(set);
            if (uuid == null || !liveInfobaseUuids.contains(uuid))
                staleIds.add(set.id);
        }
        int deleted = 0;
        for (String setId : staleIds)
        {
            if (deleteSet(setId))
                deleted++;
        }
        return deleted;
    }

    /**
     * Набор применим к проекту в его нынешнем состоянии.
     *
     * <p>Существенно только для {@link SetKind#GIT_CHANGED}: без git-репозитория такой набор
     * смысла не имеет и в таблице не показывается. Набор по изменениям базы от git не зависит —
     * раньше это правило распространялось на все динамические наборы через флаг {@code system},
     * из-за чего набор «&lt;Измененные <i>ИмяБазы</i>&gt;» пропадал из таблицы в проектах без git.
     *
     * @param set набор
     * @return {@code false}, если набор в текущем состоянии проекта неприменим
     */
    public static boolean isApplicable(SetDef set)
    {
        if (set == null)
            return false;
        if (set.kind == SetKind.GIT_CHANGED)
            return ObjectSetsItems.isProjectUnderGit(set.projectName);
        return true;
    }

    /**
     * Имя набора для вставки в текст (тост, подсказка, пункт меню). Обычное имя обрамляется
     * кавычками-«ёлочками»; системное имя «с треугольными скобками» ({@code <Основной>},
     * {@code <Измененные …>}) само себе ограничитель — кавычки к нему не добавляются.
     */
    public static String quotedName(String name)
    {
        if (name == null || name.isBlank())
            return "«»"; //$NON-NLS-1$
        String trimmed = name.trim();
        boolean bracketed = trimmed.startsWith("<") && trimmed.endsWith(">"); //$NON-NLS-1$ //$NON-NLS-2$
        return bracketed ? name : "«" + name + "»"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@link #quotedName(String)} для набора; {@code null}-набор — «ёлочки» вокруг пустой строки. */
    public static String quotedName(SetDef set)
    {
        return quotedName(set != null ? set.name : null);
    }

    /**
     * Имя набора в угловых скобках для текста пункта меню («Добавить в набор &lt;…&gt;»).
     * Системное имя уже само в скобках ({@code <Основной>}, {@code <Измененные …>}) —
     * второй пары не добавляем.
     */
    public static String bracketedName(String name)
    {
        if (name == null || name.isBlank())
            return "<>"; //$NON-NLS-1$
        String trimmed = name.trim();
        boolean bracketed = trimmed.startsWith("<") && trimmed.endsWith(">"); //$NON-NLS-1$ //$NON-NLS-2$
        return bracketed ? name : "<" + name + ">"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@link #bracketedName(String)} для набора. */
    public static String bracketedName(SetDef set)
    {
        return bracketedName(set != null ? set.name : null);
    }

    /** UUID информационной базы набора {@link SetKind#INFOBASE_CHANGED} (иначе {@code null}). */
    public static String infobaseUuidOf(SetDef set)
    {
        if (set == null || set.kind != SetKind.INFOBASE_CHANGED || set.id == null)
            return null;
        int sep = set.id.lastIndexOf(INFOBASE_CHANGED_ID_SEPARATOR);
        if (sep < 0 || sep + 1 >= set.id.length())
            return null;
        return set.id.substring(sep + 1);
    }

    private static String infobaseChangedSetName(String infobaseName)
    {
        String suffix = infobaseName != null ? infobaseName.trim() : ""; //$NON-NLS-1$
        return suffix.isEmpty()
            ? "<Измененные>" //$NON-NLS-1$
            : "<Измененные " + suffix + ">"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Привести имя к каноническому виду. Имя набора по базе хранится как есть (в нём имя базы),
     * git-набор всегда «&lt;Измененные Git&gt;», старое «Основной» — в «&lt;Основной&gt;».
     */
    private static String normalizeSetName(String name, SetKind kind)
    {
        if (kind == SetKind.GIT_CHANGED)
            return SYSTEM_CHANGED_SET_NAME;
        if (kind == SetKind.INFOBASE_CHANGED)
            return name;
        if ("Основной".equals(name)) //$NON-NLS-1$
            return DEFAULT_SET_NAME;
        return name;
    }

    static String systemSetId(String projectName)
    {
        return SYSTEM_CHANGED_ID_PREFIX + projectName;
    }

    static String infobaseChangedSetId(String projectName, String infobaseUuid)
    {
        return INFOBASE_CHANGED_ID_PREFIX + projectName + INFOBASE_CHANGED_ID_SEPARATOR + infobaseUuid;
    }

    /** Есть ли у проекта хоть один несистемный (пользовательский) набор. */
    private boolean hasUserSets(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return false;
        for (SetDef set : setsById.values())
        {
            if (projectName.equals(set.projectName) && !set.system)
                return true;
        }
        return false;
    }

    private SetDef createDefaultSet(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return null;
        SetDef set = new SetDef(UUID.randomUUID().toString(), DEFAULT_SET_NAME, projectName.trim());
        setsById.put(set.id, set);
        ObjectSetsAddTargetState.getInstance().ensureForProject(projectName.trim());
        return set;
    }

    public synchronized boolean areAllSetsEmpty()
    {
        for (SetDef set : setsById.values())
        {
            if (!set.items.isEmpty())
                return false;
        }
        return true;
    }

    public synchronized int addItems(String setId, List<Item> items)
    {
        SetDef set = setsById.get(setId);
        if (set == null || set.system || items == null || items.isEmpty())
            return 0;
        int added = 0;
        for (Item item : items)
        {
            if (item == null || item.key.isBlank())
                continue;
            if (containsKey(set, item.key))
                continue;
            set.items.add(item);
            added++;
        }
        if (added > 0)
        {
            sortItemsInPlace(set);
            save();
            notifyChanged();
        }
        return added;
    }

    public synchronized int removeItems(String setId, Iterable<String> keys)
    {
        SetDef set = setsById.get(setId);
        if (set == null || set.system || keys == null)
            return 0;
        int removed = 0;
        for (String key : keys)
        {
            if (key == null || key.isBlank())
                continue;
            if (set.items.removeIf(i -> key.equals(i.key)))
                removed++;
        }
        if (removed > 0)
        {
            save();
            notifyChanged();
        }
        return removed;
    }

    public synchronized SetDef getSetById(String setId)
    {
        return setId != null ? setsById.get(setId) : null;
    }

    public synchronized List<SetDef> getAllSets()
    {
        return new ArrayList<>(setsById.values());
    }

    public synchronized List<SetDef> getSetsForProject(String projectName)
    {
        if (projectName == null || projectName.isBlank())
            return List.of();
        List<SetDef> result = new ArrayList<>();
        for (SetDef set : setsById.values())
        {
            if (projectName.equals(set.projectName))
                result.add(set);
        }
        result.sort(Comparator.comparing(s -> s.name, Collator.getInstance(Locale.getDefault())));
        return result;
    }

    public synchronized List<SetDef> getSetsByIds(Iterable<String> ids)
    {
        List<SetDef> result = new ArrayList<>();
        if (ids == null)
            return result;
        for (String id : ids)
        {
            SetDef set = setsById.get(id);
            if (set != null)
                result.add(set);
        }
        result.sort(Comparator.comparing(s -> s.name, Collator.getInstance(Locale.getDefault())));
        return result;
    }

    public synchronized List<Item> getItemsForDisplay(String setId)
    {
        SetDef set = setsById.get(setId);
        if (set == null)
            return List.of();
        if (set.kind == SetKind.GIT_CHANGED)
            return ObjectSetsItems.collectGitChangedItems(set.projectName);
        if (set.kind == SetKind.INFOBASE_CHANGED)
            return InfobaseChangedObjects.changedItems(set);
        List<Item> copy = new ArrayList<>(set.items);
        copy.sort(ItemSort.COMPARATOR);
        return copy;
    }

    public synchronized boolean containsKey(SetDef set, String key)
    {
        if (set == null || key == null)
            return false;
        for (Item item : set.items)
        {
            if (key.equals(item.key))
                return true;
        }
        return false;
    }

    public synchronized int countExistingKeys(SetDef set, Iterable<String> keys)
    {
        if (set == null || keys == null)
            return 0;
        int count = 0;
        for (String key : keys)
        {
            if (key != null && !key.isBlank() && containsKey(set, key))
                count++;
        }
        return count;
    }

    public synchronized String exportText()
    {
        StringBuilder sb = new StringBuilder();
        for (SetDef set : setsById.values())
        {
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(SET_MARKER).append(SEP).append(escape(set.name))
                .append(SEP).append(escape(set.projectName)).append(SEP).append(escape(set.id))
                .append(SEP).append(set.kind.code);
            sortItemsInPlace(set);
            for (Item item : set.items)
            {
                sb.append('\n').append(escape(item.key)).append(SEP).append(escape(item.navRef))
                    .append(SEP).append(escape(item.displayName)).append(SEP).append(escape(item.ownName));
            }
        }
        return sb.toString();
    }

    public synchronized int importText(String text, boolean merge)
    {
        if (text == null || text.isBlank())
            return 0;
        if (!merge)
            setsById.clear();
        int imported = 0;
        SetDef current = null;
        for (String line : text.split("\n", -1)) //$NON-NLS-1$
        {
            if (line.isBlank())
                continue;
            if (line.startsWith(SET_MARKER))
            {
                String[] parts = line.split(String.valueOf(SEP), 5);
                if (parts.length < 3)
                    continue;
                String name = unescape(parts[1]);
                String projectName = unescape(parts[2]);
                String id = parts.length >= 4 ? unescape(parts[3]) : UUID.randomUUID().toString();
                SetKind kind = parts.length >= 5 ? SetKind.byCode(unescape(parts[4])) : SetKind.USER;
                if (name.isBlank() || projectName.isBlank())
                    continue;
                current = findOrCreateSet(name, projectName, id, merge, kind);
                imported++;
                continue;
            }
            if (current == null)
                continue;
            String[] parts = line.split(String.valueOf(SEP), 4);
            if (parts.length < 4)
                continue;
            Item item = new Item(unescape(parts[0]), unescape(parts[1]),
                unescape(parts[2]), unescape(parts[3]));
            if (item.key.isBlank() || current.system)
                continue;
            if (!containsKey(current, item.key))
                current.items.add(item);
        }
        for (SetDef set : setsById.values())
            sortItemsInPlace(set);
        save();
        notifyChanged();
        return imported;
    }

    private SetDef findOrCreateSet(String name, String projectName, String id, boolean merge, SetKind kind)
    {
        name = normalizeSetName(name, kind);
        if (merge)
        {
            for (SetDef set : setsById.values())
            {
                if (name.equals(set.name) && projectName.equals(set.projectName))
                    return set;
            }
        }
        SetDef set = new SetDef(id.isBlank() ? UUID.randomUUID().toString() : id, name, projectName, kind);
        setsById.put(set.id, set);
        return set;
    }

    static void sortItemsInPlace(SetDef set)
    {
        if (set == null || set.items.size() < 2)
            return;
        set.items.sort(ItemSort.COMPARATOR);
    }

    private void notifyChanged()
    {
        if (ObjectSetsNavigatorFilterSupport.isActive())
            ObjectSetsNavigatorFilterSupport.refreshNavigators();
        for (Runnable listener : changeListeners)
        {
            try
            {
                listener.run();
            }
            catch (Exception ex)
            {
                ObjectSetsDebug.problem("listener: " + ex); //$NON-NLS-1$
            }
        }
    }

    private void save()
    {
        if (prefs == null)
            return;
        prefs.setValue(PREF_KEY, exportText());
        try
        {
            prefs.save();
        }
        catch (Exception ex)
        {
            ObjectSetsDebug.problem("save: " + ex); //$NON-NLS-1$
        }
    }

    private void load()
    {
        if (prefs == null)
            return;
        setsById.clear();
        String raw = prefs.getString(PREF_KEY);
        if (raw == null || raw.isBlank())
            return;
        SetDef current = null;
        for (String line : raw.split("\n", -1)) //$NON-NLS-1$
        {
            if (line.isBlank())
                continue;
            if (line.startsWith(SET_MARKER))
            {
                String[] parts = line.split(String.valueOf(SEP), 5);
                if (parts.length < 3)
                    continue;
                String name = unescape(parts[1]);
                String projectName = unescape(parts[2]);
                String id = parts.length >= 4 && !unescape(parts[3]).isBlank()
                    ? unescape(parts[3]) : UUID.randomUUID().toString();
                SetKind kind = parts.length >= 5 ? SetKind.byCode(unescape(parts[4])) : SetKind.USER;
                if (name.isBlank() || projectName.isBlank())
                    continue;
                name = normalizeSetName(name, kind);
                current = new SetDef(id, name, projectName, kind);
                setsById.put(id, current);
                continue;
            }
            if (current == null)
                continue;
            String[] parts = line.split(String.valueOf(SEP), 4);
            if (parts.length < 4)
                continue;
            Item item = new Item(unescape(parts[0]), unescape(parts[1]),
                unescape(parts[2]), unescape(parts[3]));
            if (!item.key.isBlank() && !current.system)
                current.items.add(item);
        }
        for (SetDef set : setsById.values())
            sortItemsInPlace(set);
    }

    static final class ItemSort
    {
        private static final Collator COLLATOR = Collator.getInstance(Locale.getDefault());

        static final Comparator<Item> COMPARATOR = (a, b) ->
        {
            int byName = COLLATOR.compare(nameKey(a), nameKey(b));
            if (byName != 0)
                return byName;
            String ka = a != null && a.key != null ? a.key : ""; //$NON-NLS-1$
            String kb = b != null && b.key != null ? b.key : ""; //$NON-NLS-1$
            return COLLATOR.compare(ka, kb);
        };

        private ItemSort() {}

        static String nameKey(Item item)
        {
            if (item == null)
                return ""; //$NON-NLS-1$
            if (item.ownName != null && !item.ownName.isBlank())
                return item.ownName;
            if (item.displayName != null && !item.displayName.isBlank())
                return item.displayName;
            return item.key != null ? item.key : ""; //$NON-NLS-1$
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
