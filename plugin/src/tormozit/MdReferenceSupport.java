package tormozit;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.rights.model.RightsPackage;

/** Сохранённые ссылки метаданных: общий анализатор проверки и поиска по проекту. */
public final class MdReferenceSupport
{
    public record BrokenReference(EObject owner, EReference feature, int index, EObject target) {}
    /** В результатах не сохраняются объекты завершённой BM-транзакции. */
    public record Location(URI owner, EClass ownerClass, String feature) {}

    private static final Map<EClass, List<EReference>> FEATURES = new ConcurrentHashMap<>();

    public static List<EReference> features(EClass type)
    {
        return FEATURES.computeIfAbsent(type, key -> key.getEAllReferences().stream().filter(reference ->
        {
            if (reference.isContainment() || reference.isContainer() || reference.isDerived()
                || reference.isTransient() || reference.isVolatile())
                return false;
            EClass target = reference.getEReferenceType();
            return target == EcorePackage.Literals.EOBJECT || related(target, MdClassPackage.Literals.MD_OBJECT)
                || related(target, McorePackage.Literals.TYPE_ITEM) || related(target, McorePackage.Literals.PICTURE)
                || related(target, McorePackage.Literals.COLOR) || related(target, McorePackage.Literals.FONT)
                || related(target, McorePackage.Literals.BORDER) || related(target, McorePackage.Literals.COMMAND)
                || related(target, McorePackage.Literals.COMMAND_GROUP);
        }).toList());
    }

    private static boolean related(EClass a, EClass b) { return a.isSuperTypeOf(b) || b.isSuperTypeOf(a); }

    public static List<EClass> referenceClasses()
    {
        return ModelClasses.CLASSES.stream().filter(type -> !type.isAbstract() && !features(type).isEmpty()).toList();
    }

    private static final class ModelClasses
    {
        static final List<EClass> CLASSES = modelClasses();
    }

    private static final class CheckScopes
    {
        static final Map<EClass, Set<EClass>> SCOPES = containmentScopes();
    }

    private static List<EClass> modelClasses()
    {
        List<EClass> result = new ArrayList<>();
        List<EPackage> packages = new ArrayList<>(List.of(MdClassPackage.eINSTANCE, McorePackage.eINSTANCE,
            RightsPackage.eINSTANCE, FormPackage.eINSTANCE));
        Set<EPackage> visited = new HashSet<>(packages);
        for (int i = 0; i < packages.size(); i++)
        {
            EPackage pkg = packages.get(i);
            for (EClassifier classifier : pkg.getEClassifiers())
                if (classifier instanceof EClass type)
                {
                    result.add(type);
                    // Внешние модели форм, макетов и настроек — отдельные BM-объекты.
                    for (EReference reference : type.getEAllReferences())
                        if (!reference.isDerived() && !reference.isTransient() && !reference.isVolatile())
                        {
                            EPackage linked = reference.getEReferenceType().getEPackage();
                            if (linked != null && visited.add(linked))
                                packages.add(linked);
                        }
                }
        }
        return result;
    }

    /** Конкретные классы верхних объектов и достижимые владельцы ссылок внутри них.
     * CheckDefinition хранит EClass по точному ключу, поэтому общий EObject не подходит.
     */
    public static Map<EClass, Set<EClass>> scopes() { return CheckScopes.SCOPES; }

    private static Map<EClass, Set<EClass>> containmentScopes()
    {
        Map<EClass, Set<EClass>> scopes = new HashMap<>();
        Map<EClass, Set<EClass>> parents = new HashMap<>();
        for (EClass type : ModelClasses.CLASSES)
            parents.put(type, new HashSet<>());
        for (EClass type : ModelClasses.CLASSES)
        {
            Set<EClass> owners = new HashSet<>();
            if (!type.isAbstract() && !features(type).isEmpty())
                owners.add(type);
            scopes.put(type, owners);
            for (EReference reference : type.getEAllContainments())
                if (!reference.isDerived() && !reference.isTransient() && !reference.isVolatile())
                    for (EClass candidate : ModelClasses.CLASSES)
                        if (reference.getEReferenceType() == EcorePackage.Literals.EOBJECT
                            || reference.getEReferenceType().isSuperTypeOf(candidate))
                            parents.get(candidate).add(type);
        }
        // Распространяем изменившийся набор только к его родителям, без повторного обхода всей модели.
        ArrayDeque<EClass> pending = new ArrayDeque<>();
        Set<EClass> queued = new HashSet<>();
        for (EClass type : ModelClasses.CLASSES)
            if (!scopes.get(type).isEmpty())
            {
                pending.add(type);
                queued.add(type);
            }
        while (!pending.isEmpty())
        {
            EClass child = pending.remove();
            queued.remove(child);
            for (EClass parent : parents.get(child))
                if (scopes.get(parent).addAll(scopes.get(child)) && queued.add(parent))
                    pending.add(parent);
        }
        scopes.entrySet().removeIf(entry -> entry.getKey().isAbstract() || entry.getValue().isEmpty());
        return scopes;
    }

    public static void removeBrokenReferences(EObject owner, EReference feature)
    {
        List<BrokenReference> broken = findBrokenReferences(owner, feature, new NullProgressMonitor());
        boolean removed = false;
        for (int i = broken.size() - 1; i >= 0; i--)
        {
            BrokenReference reference = broken.get(i);
            if (reference.feature() != feature)
                continue;
            if (owner instanceof com._1c.g5.v8.dt.metadata.mdclass.ExchangePlanContentItem && "mdObject".equals(feature.getName())
                || owner instanceof com._1c.g5.v8.dt.metadata.mdclass.CommonAttributeContentItem && "metadata".equals(feature.getName())
                || owner instanceof com._1c.g5.v8.dt.rights.model.ObjectRights && "object".equals(feature.getName()))
            {
                EcoreUtil.remove(owner);
                return;
            }
            if (feature.isMany())
                ((List<?>) owner.eGet(feature)).remove(reference.index());
            else
                owner.eUnset(feature);
            removed = true;
        }
        if (removed && owner instanceof com._1c.g5.v8.dt.mcore.TypeDescription description && "types".equals(feature.getName())
            && description.getTypes().isEmpty() && description.eContainingFeature() != null
            && "type".equals(description.eContainingFeature().getName()))
        {
            var provider = com._1c.g5.v8.dt.platform.IEObjectProvider.Registry.INSTANCE.get(
                McorePackage.Literals.TYPE_ITEM, com._1c.g5.v8.dt.platform.version.Version.LATEST);
            EObject string = provider != null ? provider.createProxy("String") : null;
            if (string == null)
                throw new IllegalStateException("Недоступен стандартный тип Строка");
            string = EcoreUtil.resolve(string, description);
            if (!(string instanceof com._1c.g5.v8.dt.mcore.TypeItem type) || string.eIsProxy())
                throw new IllegalStateException("Не удалось разрешить стандартный тип Строка");
            var qualifiers = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createStringQualifiers();
            qualifiers.setLength(10);
            qualifiers.setFixed(false);
            description.setStringQualifiers(qualifiers);
            description.getTypes().add(type);
        }
    }

    public static List<BrokenReference> findBrokenReferences(EObject owner, IProgressMonitor monitor)
    {
        return findBrokenReferences(owner, monitor, null);
    }

    public static List<BrokenReference> findBrokenReferences(EObject owner, EReference feature,
        IProgressMonitor monitor)
    {
        return findBrokenReferences(owner, monitor, null, feature.getName());
    }

    private static List<BrokenReference> findBrokenReferences(EObject owner, IProgressMonitor monitor,
        Map<URI, Boolean> unresolved)
    {
        return findBrokenReferences(owner, monitor, unresolved, null);
    }

    private static List<BrokenReference> findBrokenReferences(EObject owner, IProgressMonitor monitor,
        Map<URI, Boolean> unresolved, String property)
    {
        List<BrokenReference> result = new ArrayList<>();
        for (EReference feature : features(owner.eClass()))
        {
            if (property != null && !property.equals(feature.getName()))
                continue;
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            // Составы проверяет исходный анализатор: расширение на остальные свойства
            // не должно менять его геттеры, обработку записей и критерий eIsProxy().
            List<MdCompositionSupport.BrokenReference> compositionReferences = compositionReferences(owner, feature);
            if (compositionReferences != null)
            {
                for (MdCompositionSupport.BrokenReference reference : compositionReferences)
                    result.add(new BrokenReference(owner, feature, reference.index(), reference.reference()));
                continue;
            }
            // Штатные геттеры BM разрешают значения ссылок перед проверкой eIsProxy.
            Object value = owner.eGet(feature, true);
            if (value instanceof List<?> list)
            {
                for (int i = 0; i < list.size(); i++)
                {
                    if (monitor.isCanceled())
                        throw new OperationCanceledException();
                    Object element = list.get(i);
                    if (element instanceof EObject target && broken(owner, target, unresolved))
                        result.add(new BrokenReference(owner, feature, i, target));
                }
            }
            else if (value instanceof EObject target && broken(owner, target, unresolved))
                result.add(new BrokenReference(owner, feature, -1, target));
        }
        return result;
    }

    /** null — свойство не относится к исходной проверке состава. Пустой список — проверено, ошибок нет. */
    private static List<MdCompositionSupport.BrokenReference> compositionReferences(EObject owner, EReference feature)
    {
        MdCompositionSupport.Composition composition = MdCompositionSupport.composition(owner);
        if (composition != null && composition.feature() == feature)
            return MdCompositionSupport.findBrokenReferences(owner);
        if (owner instanceof com._1c.g5.v8.dt.mcore.TypeDescription
            && feature == McorePackage.Literals.TYPE_DESCRIPTION__TYPES
            && MdCompositionSupport.composition(owner.eContainer()) != null)
            return MdCompositionSupport.findBrokenReferences(owner);
        if (owner instanceof com._1c.g5.v8.dt.metadata.mdclass.ExchangePlanContentItem
                && feature == MdClassPackage.Literals.EXCHANGE_PLAN_CONTENT_ITEM__MD_OBJECT
            || owner instanceof com._1c.g5.v8.dt.metadata.mdclass.CommonAttributeContentItem
                && feature == MdClassPackage.Literals.COMMON_ATTRIBUTE_CONTENT_ITEM__METADATA
            || owner instanceof com._1c.g5.v8.dt.rights.model.ObjectRights
                && feature == RightsPackage.Literals.OBJECT_RIGHTS__OBJECT)
            return MdCompositionSupport.findBrokenReferences(owner);
        return null;
    }

    /** Типы сохранённых целей ссылок, общие для проверки проекта и объединения. */
    public static boolean isMetadataReferenceTarget(EObject target)
    {
        // Ссылки BM могут быть EObject-оболочками без Java-интерфейсов модели.
        // Тип цели определяем по EClass, который оболочка делегирует объекту.
        EClass type = target.eClass();
        boolean metadata = MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(type)
            || McorePackage.Literals.TYPE_ITEM.isSuperTypeOf(type)
            || McorePackage.Literals.PICTURE.isSuperTypeOf(type)
            || McorePackage.Literals.COLOR.isSuperTypeOf(type)
            || McorePackage.Literals.FONT.isSuperTypeOf(type)
            || McorePackage.Literals.BORDER.isSuperTypeOf(type)
            || McorePackage.Literals.COMMAND.isSuperTypeOf(type)
                && !McorePackage.Literals.COMMAND_REF.isSuperTypeOf(type)
            || McorePackage.Literals.COMMAND_GROUP.isSuperTypeOf(type);
        if (target.eIsProxy())
            Global.tempLog("broken-links-reference", "target=" + EcoreUtil.getURI(target)
                + " javaClass=" + target.getClass().getName() + " modelClass=" + type.getName()
                + " metadata=" + metadata);
        return metadata;
    }

    private static boolean broken(EObject owner, EObject target, Map<URI, Boolean> cache)
    {
        if (!target.eIsProxy() || !isMetadataReferenceTarget(target))
            return false;
        URI uri = EcoreUtil.getURI(target);
        if (cache != null && cache.containsKey(uri))
            return cache.get(uri);
        boolean result = EcoreUtil.resolve(target, owner).eIsProxy();
        if (cache != null)
            cache.put(uri, result);
        return result;
    }

    /** URI с именованными подчинёнными объектами, без служебных фрагментов производных типов. */
    public static String fullName(URI uri)
    {
        StringBuilder name = new StringBuilder(URI.decode(uri.lastSegment() != null ? uri.lastSegment() : uri.toString()));
        if (uri.fragment() != null)
            for (String part : URI.decode(uri.fragment()).split("/"))
            {
                int colon = part.indexOf(':');
                if (colon <= 0)
                    continue;
                String collection = part.substring(0, colon);
                String type = MdTypeMapping.folderToEnSing(Character.toUpperCase(collection.charAt(0)) + collection.substring(1));
                if (type != null)
                    name.append('.').append(type).append('.').append(part.substring(colon + 1));
            }
        return name.toString();
    }

    public static String localized(URI uri)
    {
        String name = fullName(uri);
        String translated = MdTypeMapping.bmFqnToRuFullName(name);
        return translated != null ? translated : name;
    }

    public static String propertyName(EReference feature)
    {
        return switch (feature.getName())
        {
            case "types", "type" -> "Тип";
            case "content", "metadata", "mdObject", "object" -> "Состав";
            case "owners" -> "Владельцы";
            case "recorders" -> "Регистраторы";
            case "source" -> "Источник";
            default -> {
                String label = ConfigSearchResultsHook.featureLabelForFocus(feature);
                yield label != null && !label.isBlank() ? label : feature.getName();
            }
        };
    }

    /**
     * Удаляет битые ссылки свойств фоновым заданием. Владельцы находятся до первого удаления:
     * удаление записи состава сдвигает адреса следующих записей.
     *
     * @param onDone вызывается в потоке интерфейса после успешной записи; может быть {@code null}
     */
    public static void removeBrokenReferences(IProject project, List<Location> locations, Runnable onDone)
    {
        if (project == null || locations == null || locations.isEmpty())
            return;
        List<Location> targets = locations.stream().distinct().toList();
        new Job("Удаление битых ссылок")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                Global.tempLog("broken-links-problem", "clear start locations=" + targets);
                try
                {
                    IBmModelManager manager = Global.getOsgiService(IBmModelManager.class);
                    IBmModel model = manager != null ? manager.getModel(project) : null;
                    if (model == null)
                        throw new IllegalStateException("Не найдена модель проекта " + project.getName());
                    var editing = model.createLocalContext("Удаление битых ссылок");
                    try
                    {
                        editing.execute(new AbstractBmTask<Void>("Удаление битых ссылок")
                        {
                            @Override
                            public Void execute(IBmTransaction transaction, IProgressMonitor taskMonitor)
                            {
                                List<EObject> owners = new ArrayList<>(targets.size());
                                List<EReference> features = new ArrayList<>(targets.size());
                                for (Location location : targets)
                                {
                                    EObject owner = transaction.getObjectByUri(location.owner());
                                    if (owner == null || owner.eIsProxy()
                                        || !(owner.eClass().getEStructuralFeature(location.feature())
                                            instanceof EReference feature))
                                        throw new IllegalStateException("Не найдено свойство " + location.feature()
                                            + " объекта " + location.owner());
                                    owners.add(owner);
                                    features.add(feature);
                                }
                                for (int i = 0; i < owners.size(); i++)
                                    removeBrokenReferences(owners.get(i), features.get(i));
                                return null;
                            }
                        });
                        editing.save();
                    }
                    finally { editing.dispose(); }
                    Global.tempLog("broken-links-problem", "clear finished locations=" + targets.size());
                    if (onDone != null)
                        Display.getDefault().asyncExec(onDone);
                    return Status.OK_STATUS;
                }
                catch (Exception error)
                {
                    Global.tempLog("broken-links-problem", "clear failed " + error);
                    return new Status(IStatus.ERROR, "tormozit.comfort", error.getMessage(), error);
                }
            }
        }.schedule();
    }

    /**
     * @param referenceFqn полное имя цели битой ссылки; по нему в Configuration.mdo выделяется строка
     */
    public static void open(IProject project, Location location, String referenceFqn)
    {
        if (project != null && location != null
            && MdClassPackage.Literals.CONFIGURATION.isSuperTypeOf(location.ownerClass()))
        {
            // Свойства корня — списки объектов конфигурации, страницы редактора у них нет.
            ProblemViewOpenInTextEditorHandler.openConfigurationReference(project, location.feature(), referenceFqn);
            return;
        }
        open(project, location);
    }

    public static void open(IProject project, Location location)
    {
        if (project == null || location == null)
            return;
        IBmModelManager manager = Global.getOsgiService(IBmModelManager.class);
        IBmModel model = manager != null ? manager.getModel(project) : null;
        if (model == null)
            return;
        EObject owner = model.getEngine().resolve(location.owner(), location.ownerClass());
        if (owner == null || owner.eIsProxy())
            return;
        ConfigSearchResultsHook.openMetadataReferenceProperty(owner,
            owner.eClass().getEStructuralFeature(location.feature()));
    }

    public static void findInProject(IProject project)
    {
        findInProject(project, null);
    }

    /** location ограничивает поиск одним свойством проблемы конфигурации. */
    public static void findInProject(IProject project, Location location)
    {
        String scope = null;
        if (location != null)
        {
            var feature = location.ownerClass().getEStructuralFeature(location.feature());
            scope = "свойстве «" + (feature instanceof EReference reference ? propertyName(reference)
                : location.feature()) + "» объекта «" + localized(location.owner()) + "»";
        }
        findInProject(project, location, null, scope);
    }

    /**
     * Поиск только в выбранных объектах и их вложенных объектах; пустой набор не расширяется до проекта.
     *
     * @param scope подписи выбранных узлов для заголовка результата
     */
    public static void findInObjects(IProject project, List<URI> roots, String scope)
    {
        findInProject(project, null, List.copyOf(roots), scope);
    }

    private static void findInProject(IProject project, Location location, List<URI> roots, String scope)
    {
        if (project == null)
            return;
        new Job("Поиск битых ссылок метаданных")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                Global.tempLog("broken-links-project", "start project=" + project.getName() + " location=" + location
                    + " roots=" + roots);
                try
                {
                    IBmModelManager manager = Global.getOsgiService(IBmModelManager.class);
                    IBmModel model = manager != null ? manager.getModel(project) : null;
                    if (model == null)
                        throw new IllegalStateException("Не найдена модель проекта " + project.getName());
                    List<URI> unreadable = new ArrayList<>();
                    List<CompareSearchMatch> matches = model.executeReadonlyTask(new AbstractBmTask<List<CompareSearchMatch>>(
                        "Поиск битых ссылок метаданных")
                    {
                        @Override
                        public List<CompareSearchMatch> execute(IBmTransaction transaction, IProgressMonitor taskMonitor)
                        {
                            SubMonitor progress = SubMonitor.convert(monitor, 100);
                            List<EObject> owners = new ArrayList<>();
                            Set<Long> ids = new HashSet<>();
                            SubMonitor indexing = progress.split(20);
                            if (location != null)
                            {
                                EObject owner = transaction.getObjectByUri(location.owner());
                                if (owner != null && !owner.eIsProxy())
                                    owners.add(owner);
                                indexing.done();
                            }
                            else if (roots != null)
                            {
                                List<EObject> selected = new ArrayList<>(roots.size());
                                for (URI uri : roots)
                                {
                                    indexing.checkCanceled();
                                    EObject object = transaction.getObjectByUri(uri);
                                    if (object != null && !object.eIsProxy())
                                        selected.add(object);
                                }
                                collect(selected.iterator(), ids, owners, unreadable, indexing);
                                indexing.done();
                            }
                            else
                            {
                                collect(transaction.getTopObjectIterator(), ids, owners, unreadable, indexing);
                                indexing.done();
                            }
                            SubMonitor checking = progress.split(80).setWorkRemaining(Math.max(1, owners.size()));
                            Map<URI, Boolean> unresolved = new HashMap<>();
                            List<CompareSearchMatch> rows = new ArrayList<>();
                            for (EObject owner : owners)
                            {
                                checking.checkCanceled();
                                for (BrokenReference reference : findBrokenReferences(owner, checking, unresolved,
                                    location != null ? location.feature() : null))
                                    rows.add(new CompareSearchMatch(new Location(EcoreUtil.getURI(owner), owner.eClass(),
                                        reference.feature().getName()), localized(EcoreUtil.getURI(owner)),
                                        propertyName(reference.feature()), localized(EcoreUtil.getURI(reference.target())),
                                        fullName(EcoreUtil.getURI(reference.target()))));
                                checking.worked(1);
                            }
                            checking.done();
                            return rows;
                        }
                    });
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    matches.sort(java.util.Comparator.comparing(CompareSearchMatch::getObjectPath,
                        String.CASE_INSENSITIVE_ORDER).thenComparing(CompareSearchMatch::getPropertyName,
                            String.CASE_INSENSITIVE_ORDER).thenComparing(CompareSearchMatch::getMatchText,
                                String.CASE_INSENSITIVE_ORDER));
                    Global.tempLog("broken-links-project", "finished project=" + project.getName() + " matches=" + matches.size());
                    Display.getDefault().asyncExec(() ->
                    {
                        CompareSearchResult result = new CompareSearchResult(matches, null, project);
                        result.setQueryText("битые ссылки метаданных");
                        result.setScopeLabel(scope);
                        CompareSearchQuery query = new CompareSearchQuery();
                        result.setQuery(query);
                        query.setSearchResult(result);
                        NewSearchUI.runQueryInBackground(query);
                        if (!unreadable.isEmpty())
                            ToastNotification.show("Поиск битых ссылок метаданных", "Пропущено вложенных объектов без"
                                + " данных в модели: " + unreadable.size() + ". Первый: " + localized(unreadable.get(0)),
                                10_000);
                    });
                    return Status.OK_STATUS;
                }
                catch (OperationCanceledException canceled) { return Status.CANCEL_STATUS; }
                catch (Exception error)
                {
                    Global.tempLog("broken-links-project", "failed " + error);
                    return new Status(IStatus.ERROR, "tormozit.comfort", error.getMessage(), error);
                }
            }
        }.schedule();
    }

    private static void collect(Iterator<? extends EObject> iterator, Set<Long> ids, List<EObject> owners,
        List<URI> unreadable, SubMonitor monitor)
    {
        Map<EClass, Set<EClass>> ownerScopes = scopes();
        Set<EObject> visitedObjects = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        ArrayDeque<EObject> pending = new ArrayDeque<>();
        while (iterator.hasNext())
        {
            monitor.checkCanceled();
            pending.add(iterator.next());
        }
        int visited = 0;
        while (!pending.isEmpty())
        {
            monitor.checkCanceled();
            EObject object = pending.remove();
            monitor.setWorkRemaining(Math.max(1, pending.size() + 1));
            monitor.worked(1);
            if (!visitedObjects.add(object)
                || object instanceof IBmObject bm && (!ids.add(bm.bmGetId())
                    || "command_interface_root".equals(bm.bmGetTopObject().bmGetFqn())))
                continue;
            // Тело формы — внешний самостоятельный BM-объект, а не сохранённый containment.
            if (object instanceof com._1c.g5.v8.dt.metadata.mdclass.BasicForm form && form.getForm() != null)
                enqueueSavedChild(form.getForm(), pending, unreadable);
            if (!ownerScopes.containsKey(object.eClass()))
                continue;
            visited++;
            if (!features(object.eClass()).isEmpty())
                owners.add(object);
            for (EReference containment : object.eClass().getEAllContainments())
            {
                if (containment.isDerived() || containment.isTransient() || containment.isVolatile())
                    continue;
                Object value = object.eGet(containment, true);
                if (value instanceof EObject child)
                    enqueueSavedChild(child, pending, unreadable);
                else if (value instanceof List<?> children)
                    for (Object child : children)
                    {
                        monitor.checkCanceled();
                        if (child instanceof EObject nested)
                            enqueueSavedChild(nested, pending, unreadable);
                    }
            }
        }
        Global.tempLog("broken-links-project", "saved owner traversal visited=" + visited + " owners=" + owners.size());
    }

    /**
     * Вложенный объект без данных в модели (например, форма без файла Form.form) пропускается:
     * один такой объект не должен срывать поиск по всему проекту.
     */
    private static void enqueueSavedChild(EObject child, ArrayDeque<EObject> pending, List<URI> unreadable)
    {
        if (child.eIsProxy())
        {
            unreadable.add(EcoreUtil.getURI(child));
            return;
        }
        // При поиске по выделенному поддереву верхнего итератора нет; дубли отсечёт общий набор ids.
        pending.add(child);
    }

    private MdReferenceSupport() {}
}
