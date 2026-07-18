package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

/**
 * Конвертация элементов навигатора / «Последних мест» в {@link ObjectSets.Item}
 * и добавление в набор с тостами.
 */
final class ObjectSetsItems
{
    private ObjectSetsItems() {}

    static List<ObjectSets.Item> fromNavigatorSelection(
            IStructuredSelection selection, ObjectSets.SetDef targetSet)
    {
        List<ObjectSets.Item> result = new ArrayList<>();
        if (selection == null || targetSet == null)
            return result;
        for (Object element : selection.toList())
        {
            ObjectSets.Item item = fromNavigatorElement(element, targetSet.projectName);
            if (item != null)
                result.add(item);
        }
        return result;
    }

    static List<ObjectSets.Item> fromRecentPlaceEntries(
            List<RecentPlaces.Entry> entries, ObjectSets.SetDef targetSet)
    {
        List<ObjectSets.Item> result = new ArrayList<>();
        if (entries == null || targetSet == null)
            return result;
        for (RecentPlaces.Entry entry : entries)
        {
            if (entry == null)
                continue;
            if (entry.projectName != null && !entry.projectName.isBlank()
                    && !targetSet.projectName.equals(entry.projectName))
                continue;
            result.add(fromRecentPlaceEntry(entry));
        }
        return result;
    }

    static ObjectSets.Item fromRecentPlaceEntry(RecentPlaces.Entry entry)
    {
        if (entry == null)
            return null;
        return new ObjectSets.Item(entry.key, entry.navRef, entry.displayName, entry.ownName);
    }

    static ObjectSets.Item fromNavigatorElement(Object element, String projectName)
    {
        if (element == null || projectName == null || projectName.isBlank())
            return null;
        String project = resolveProjectName(element);
        if (project != null && !project.isBlank() && !projectName.equals(project))
            return null;
        String fullName = GetRef.fullNameFromNavigatorElement(element);
        if (fullName == null || fullName.isBlank())
            return null;
        String ownName = lastSegment(fullName);
        return new ObjectSets.Item(fullName, fullName, fullName, ownName);
    }

    static ObjectSets.Item fromEObject(EObject eObject, String projectName)
    {
        if (eObject == null || projectName == null || projectName.isBlank())
            return null;
        String fullName = GetRef.fullNameFromNavigatorElement(eObject);
        if (fullName == null || fullName.isBlank())
            return null;
        String ownName = lastSegment(fullName);
        return new ObjectSets.Item(fullName, fullName, fullName, ownName);
    }

    /**
     * Строит {@link ObjectSets.Item} прямо по файлу, без резолва {@link EObject}
     * через BM-транзакцию. Полное имя строится прежде всего через
     * {@link GetRef#pathToFullName} (по пути файла в проекте) — он корректно
     * останавливается на владеющем объекте (например, {@code Forms/<Форма>/Module.bsl}
     * → сама форма), не спускаясь глубже. {@link GoToDefinition#fullNameFromFile}
     * (FQN-конвертер Xtext) — запасной вариант: для модулей форм он на практике
     * даёт FQN с лишней парой сегментов («…Форма.<Имя>.Форма.Module»), поскольку
     * {@link MdTypeMapping#bmFqnToRuFullName} отбрасывает висячий сегмент только
     * при нечётной длине FQN, а тут она чётная — то же самое, что ломало
     * резолв {@link EObject} для «Открыть объект»/«Показать в Навигаторе»
     * (см. {@link GitChangedFileMenuHook#resolveEObject}).
     */
    static ObjectSets.Item fromFilePath(org.eclipse.core.resources.IFile file)
    {
        if (file == null)
            return null;
        String relPath = file.getProjectRelativePath().toString();
        if (GetRef.isConfigurationRootPath(relPath))
            return null;
        String fullName = GetRef.pathToFullName(relPath);
        if (fullName == null || fullName.isBlank())
            fullName = GoToDefinition.fullNameFromFile(file);
        if (fullName == null || fullName.isBlank())
            return null;
        String ownName = lastSegment(fullName);
        return new ObjectSets.Item(fullName, fullName, fullName, ownName);
    }

    static AddResult addItemsToSet(ObjectSets.SetDef target, List<ObjectSets.Item> items, Shell shell)
    {
        if (target == null || items == null || items.isEmpty())
            return AddResult.none();
        List<String> keys = new ArrayList<>();
        for (ObjectSets.Item item : items)
        {
            if (item != null && item.key != null && !item.key.isBlank())
                keys.add(item.key);
        }
        if (keys.isEmpty())
            return AddResult.none();
        int existing = ObjectSets.getInstance().countExistingKeys(target, keys);
        int added = ObjectSets.getInstance().addItems(target.id, items);
        showAddToast(target.name, added, existing, keys.size(), shell);
        if (added > 0)
        {
            ObjectSetsView view = ObjectSetsView.getActiveInstance();
            if (view != null)
                view.refreshItemsForSetIfSelected(target.id);
        }
        return new AddResult(added, existing);
    }

    static MoveResult moveItemsToSet(ObjectSets.SetDef source, ObjectSets.SetDef target,
            List<ObjectSets.Item> items, Shell shell)
    {
        if (source == null || target == null || source.id.equals(target.id)
                || items == null || items.isEmpty())
            return MoveResult.none();
        if (!source.projectName.equals(target.projectName))
        {
            ToastNotification.show("Наборы объектов",
                "Нельзя перенести объекты между наборами разных проектов", 4000); //$NON-NLS-1$ //$NON-NLS-2$
            return MoveResult.none();
        }
        List<ObjectSets.Item> unique = dedupeItemsByKey(items);
        if (unique.isEmpty())
            return MoveResult.none();
        List<String> keys = new ArrayList<>();
        for (ObjectSets.Item item : unique)
            keys.add(item.key);
        int added = ObjectSets.getInstance().addItems(target.id, unique);
        int removed = ObjectSets.getInstance().removeItems(source.id, keys);
        showMoveToast(target.name, added, removed, keys.size(), shell);
        if (added > 0 || removed > 0)
        {
            ObjectSetsView view = ObjectSetsView.getActiveInstance();
            if (view != null)
            {
                view.refreshItemsForSetIfSelected(target.id);
                view.refreshItemsForSetIfSelected(source.id);
            }
        }
        return new MoveResult(added, removed);
    }

    private static List<ObjectSets.Item> dedupeItemsByKey(List<ObjectSets.Item> items)
    {
        List<ObjectSets.Item> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ObjectSets.Item item : items)
        {
            if (item == null || item.key == null || item.key.isBlank())
                continue;
            if (seen.add(item.key))
                result.add(item);
        }
        return result;
    }

    static void showMoveToast(String targetSetName, int added, int removed, int requested, Shell shell)
    {
        if (removed <= 0)
            return;
        String name = targetSetName != null ? targetSetName : ""; //$NON-NLS-1$
        int alreadyInTarget = requested - added;
        if (alreadyInTarget > 0)
        {
            ToastNotification.show("Наборы объектов",
                "Перенесено " + removed + " в «" + name + "» (" + alreadyInTarget + " уже в наборе)", 5000); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return;
        }
        ToastNotification.show("Наборы объектов",
            "Перенесено " + removed + " в «" + name + "»", 4000); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    static void showAddToast(String setName, int added, int existing, int requested, Shell shell)
    {
        String name = setName != null ? setName : ""; //$NON-NLS-1$
        if (added <= 0 && existing > 0)
        {
            ToastNotification.show("Наборы объектов", "Уже в наборе «" + name + "»", 4000); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }
        if (added <= 0)
            return;
        if (existing > 0)
        {
            ToastNotification.show("Наборы объектов",
                "Добавлено " + added + " в «" + name + "» (" + existing + " уже в наборе)", 5000); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return;
        }
        ToastNotification.show("Наборы объектов",
            "Добавлено " + added + " в «" + name + "»", 4000); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    static void beginAddTargetTreeFilterRefresh()
    {
        AddTargetTreeCache.clear();
    }

    /**
     * Видимость узла в навigator при фильтре набора: объект, предки и потомки md-ref.
     */
    static boolean isVisibleInAddTargetSetTree(Viewer viewer, Object element, String projectName)
    {
        return AddTargetTreeCache.forProject(projectName).isVisible(viewer, element);
    }

    /**
     * При включённом фильтре и непустом add-target наборе — добавить новый крупный объект МД в набор.
     * @return {@code true} если объект добавлен
     */
    static boolean tryAutoAddRootObjectToActiveSet(org.eclipse.core.resources.IProject project, String projectRelativePath)
    {
        if (project == null || projectRelativePath == null || projectRelativePath.isBlank())
            return false;
        if (!ObjectSetsNavigatorFilterSupport.isActive())
            return false;
        String fullName = GetRef.pathToFullName(projectRelativePath.replace('\\', '/'));
        if (fullName == null || !MdTypeMapping.isRootMdObjectRef(fullName))
            return false;
        String ownerRef = MdTypeMapping.toOwnerMdObjectRef(fullName);
        if (ownerRef == null || ownerRef.isBlank())
            return false;
        ObjectSetsAddTargetState.getInstance().ensureForProject(project.getName());
        ObjectSets.SetDef set = ObjectSetsAddTargetState.getInstance().getAddTargetSet(project.getName());
        if (set == null || set.items.isEmpty())
            return false;
        ObjectSets.Item item = new ObjectSets.Item(
            ownerRef, ownerRef, ownerRef, lastSegment(ownerRef));
        int added = ObjectSets.getInstance().addItems(set.id, List.of(item));
        return added > 0;
    }

    private static final class AddTargetTreeCache
    {
        private static final ThreadLocal<Map<String, AddTargetTreeCache>> CURRENT =
            ThreadLocal.withInitial(HashMap::new);

        private final String projectName;
        private final List<String> refs;
        private final Map<Object, Boolean> memo = new HashMap<>();

        static void clear()
        {
            Map<String, AddTargetTreeCache> caches = CURRENT.get();
            if (caches != null)
                caches.clear();
        }

        static AddTargetTreeCache forProject(String projectName)
        {
            return CURRENT.get().computeIfAbsent(projectName, AddTargetTreeCache::new);
        }

        private AddTargetTreeCache(String projectName)
        {
            this.projectName = projectName;
            refs = collectRefs(projectName);
        }

        boolean isVisible(Viewer viewer, Object element)
        {
            Boolean cached = memo.get(element);
            if (cached != null)
                return cached;
            boolean result = computeVisible(viewer, element);
            memo.put(element, result);
            return result;
        }

        private boolean computeVisible(Viewer viewer, Object element)
        {
            if (isNavigatorProjectNode(element))
            {
                org.eclipse.core.resources.IProject ws = workspaceProject(element);
                return ws != null && projectName.equals(ws.getName());
            }
            if (NavigatorTreeElementLabels.isNavigatorConfigurationRoot(element))
                return true;
            if (NavigatorTreeElementLabels.keepEmptyGroupVisible(element))
                return true;
            if (NavigatorTreeElementLabels.hasRootMdObjectIdentity(element))
            {
                String ref = fullNameFromElement(element);
                if (ref == null || ref.isBlank())
                    ref = NavigatorTreeElementLabels.inferRootMdObjectRef(element);
                if (ref != null && !ref.isBlank())
                {
                    String owner = MdTypeMapping.toOwnerMdObjectRef(ref);
                    if (owner != null && !owner.isBlank())
                        ref = owner;
                    if (relatesToAnyRef(ref))
                        return true;
                    return false;
                }
            }
            String fullName = fullNameFromElement(element);
            if (fullName != null && !fullName.isBlank())
            {
                if (relatesToAnyRef(fullName))
                    return true;
                return false;
            }
            if (!(viewer instanceof StructuredViewer structuredViewer))
                return false;
            var contentProvider = structuredViewer.getContentProvider();
            if (!(contentProvider instanceof ITreeContentProvider treeContentProvider))
                return false;
            if (!treeContentProvider.hasChildren(element))
                return false;
            for (Object child : treeContentProvider.getChildren(element))
            {
                if (isVisible(viewer, child))
                    return true;
            }
            return false;
        }

        private boolean relatesToAnyRef(String fullName)
        {
            if (refs.isEmpty())
                return false;
            for (String ref : refs)
            {
                if (fullName.equals(ref))
                    return true;
                if (fullName.startsWith(ref + ".")) //$NON-NLS-1$
                    return true;
                if (ref.startsWith(fullName + ".")) //$NON-NLS-1$
                    return true;
            }
            return false;
        }

        private String fullNameFromElement(Object element)
        {
            return GetRef.fullNameFromNavigatorElement(element);
        }

        private static boolean isNavigatorProjectNode(Object element)
        {
            if (element instanceof org.eclipse.core.resources.IProject
                    || element instanceof IDtProject)
                return true;
            if (element == null)
                return false;
            if (!"Project".equals(element.getClass().getSimpleName())) //$NON-NLS-1$
                return false;
            Object ws = Global.call(element, "getWorkspaceProject"); //$NON-NLS-1$
            return ws instanceof org.eclipse.core.resources.IProject;
        }

        private static org.eclipse.core.resources.IProject workspaceProject(Object element)
        {
            if (element instanceof org.eclipse.core.resources.IProject project)
                return project;
            if (element instanceof IDtProject dtProject)
                return dtProject.getWorkspaceProject();
            org.eclipse.core.resources.IResource resource = NavigatorResourceResolver.resolve(element);
            if (resource != null && resource.getProject() != null)
                return resource.getProject();
            Object dtProject = Global.call(element, "getDtProject"); //$NON-NLS-1$
            if (dtProject instanceof IDtProject dt)
                return dt.getWorkspaceProject();
            Object ws = Global.call(element, "getWorkspaceProject"); //$NON-NLS-1$
            if (ws instanceof org.eclipse.core.resources.IProject project)
                return project;
            return null;
        }

        private static List<String> collectRefs(String projectName)
        {
            Set<String> result = new LinkedHashSet<>();
            ObjectSets.SetDef set = ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
            if (set == null)
                return new ArrayList<>();
            for (ObjectSets.Item item : set.items)
            {
                String ref = RecentPlacesKeys.mdObjectRefFromKey(item.key);
                if (ref == null || ref.isBlank())
                    continue;
                String ownerRef = MdTypeMapping.toOwnerMdObjectRef(ref);
                if (ownerRef != null && !ownerRef.isBlank())
                    result.add(ownerRef);
            }
            return new ArrayList<>(result);
        }
    }

    static String projectNameFromNavigatorSelection(IStructuredSelection selection)
    {
        if (selection == null || selection.isEmpty())
            return null;
        for (Object element : selection.toList())
        {
            String project = resolveProjectName(element);
            if (project != null && !project.isBlank())
                return project;
        }
        return null;
    }

    private static String resolveProjectName(Object element)
    {
        org.eclipse.core.resources.IResource resource = NavigatorResourceResolver.resolve(element);
        if (resource != null && resource.getProject() != null)
            return resource.getProject().getName();
        org.eclipse.emf.ecore.EObject eObject = NavigatorElementModels.resolveEObject(element);
        if (eObject != null)
        {
            try
            {
                IV8ProjectManager mgr = Global.getOsgiService(IV8ProjectManager.class);
                if (mgr != null)
                {
                    IV8Project v8 = mgr.getProject(eObject);
                    if (v8 != null && v8.getProject() != null)
                        return v8.getProject().getName();
                }
            }
            catch (Exception ignored)
            {
                // fallback ниже
            }
        }
        if (element instanceof IDtProject dtProject)
        {
            org.eclipse.core.resources.IProject ws = dtProject.getWorkspaceProject();
            if (ws != null)
                return ws.getName();
        }
        return null;
    }

    private static String lastSegment(String fullName)
    {
        if (fullName == null || fullName.isEmpty())
            return ""; //$NON-NLS-1$
        int dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }

    static final class AddResult
    {
        final int added;
        final int existing;

        AddResult(int added, int existing)
        {
            this.added = added;
            this.existing = existing;
        }

        static AddResult none()
        {
            return new AddResult(0, 0);
        }
    }

    static final class MoveResult
    {
        final int added;
        final int removed;

        MoveResult(int added, int removed)
        {
            this.added = added;
            this.removed = removed;
        }

        static MoveResult none()
        {
            return new MoveResult(0, 0);
        }
    }
}
