package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.Triple;
import org.eclipse.xtext.util.Tuples;

import com._1c.g5.v8.bm.core.event.BmEvent;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.event.IBmSyncEventListener;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.modeling.xtext.scoping.ISlicedScope;
import com._1c.g5.modeling.xtext.scoping.IIndexSlicePredicateService;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.ui.contentassist.stringliteral.IStringLiteralProposalProvider.IBslStringLiteralProposalImageProvider;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/** Общий каталог проверки и штатного автодополнения; bsl.ui 22.0.0.v202605050943. */
final class BslTypeCatalog
{
    private static final boolean ENABLED = true;
    // Подключение к списку после «Новый» сохранено, но временно отключено.
    private static final boolean CONSTRUCTOR_CACHE_ENABLED = false;
    private static final String CALLBACK = "tormozit.stringLiteralTypeNames.proposals";
    private static final String CONSTRUCTOR_CALLBACK = "tormozit.constructorTypeNames.descriptions";
    private static final String PROVIDERS = "com._1c.g5.v8.dt.bsl.ui.internal.contentassist.stringliteral.";
    private static final Map<IProject, ProjectCatalog> PROJECTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final IResourceChangeListener WORKSPACE_LISTENER = BslTypeCatalog::workspaceChanged;
    private static final Map<IXtextDocument, org.eclipse.core.runtime.jobs.Job> WARMUPS = new WeakHashMap<>();
    private static final Map<IXtextDocument, org.eclipse.xtext.ui.editor.model.IXtextModelListener> PENDING_MODELS = new WeakHashMap<>();
    private static final ThreadLocal<org.eclipse.core.runtime.IProgressMonitor> WARM_MONITOR = new ThreadLocal<>();
    private static volatile boolean running;

    /** Версия структуры проекта; правки текста BSL её не меняют. */
    static long revision(String projectName)
    {
        synchronized (PROJECTS)
        {
            for (var entry : PROJECTS.entrySet())
                if (entry.getKey().getName().equals(projectName))
                    return entry.getValue().state.generation;
        }
        return -1;
    }

    static void activate(org.eclipse.ui.IEditorPart part)
    {
        var editor = GetRef.getActiveBslEditor(part);
        if (editor != null && editor.getDocument() != null)
            warm(editor.getDocument());
    }

    private record WarmRequest(XtextResource resource, Environments environments,
        com._1c.g5.v8.dt.bsl.model.ModuleType moduleType, boolean russian) {}

    private static void warm(IXtextDocument document)
    {
        synchronized (WARMUPS)
        {
            if (!running || WARMUPS.containsKey(document) || PENDING_MODELS.containsKey(document))
                return;
            var job = new org.eclipse.core.runtime.jobs.Job("Комфорт: каталог типов")
            {
                @Override
                protected org.eclipse.core.runtime.IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
                {
                    WARM_MONITOR.set(monitor);
                    try
                    {
                        WarmRequest request = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
                            if (!(BslNodeLookup.rootAst(resource) instanceof Module module))
                                return null;
                            IV8Project project = v8Project(resource);
                            return new WarmRequest(resource, module.environments(), module.getModuleType(),
                                project == null || project.getScriptVariant() != ScriptVariant.ENGLISH);
                        });
                        if (request == null)
                        {
                            // Первая активация может предшествовать первому результату разбора.
                            var listener = new org.eclipse.xtext.ui.editor.model.IXtextModelListener()
                            {
                                @Override
                                public void modelChanged(XtextResource resource)
                                {
                                    document.removeModelListener(this);
                                    synchronized (WARMUPS)
                                    {
                                        PENDING_MODELS.remove(document, this);
                                    }
                                    // Текущее задание к этому моменту должно выйти из WARMUPS.
                                    org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> warm(document));
                                }
                            };
                            synchronized (WARMUPS)
                            {
                                if (running)
                                {
                                    PENDING_MODELS.put(document, listener);
                                    document.addModelListener(listener);
                                }
                            }
                            return org.eclipse.core.runtime.Status.OK_STATUS;
                        }
                        List<Environments> variants = new ArrayList<>();
                        if (request.moduleType() == com._1c.g5.v8.dt.bsl.model.ModuleType.FORM_MODULE
                            || request.moduleType() == com._1c.g5.v8.dt.bsl.model.ModuleType.COMMAND_MODULE)
                        {
                            variants.add(request.environments().intersect(Environments.ALL_CLIENTS));
                            variants.add(request.environments().intersect(Environments.ALL_SERVERS));
                        }
                        else
                            variants.add(request.environments());
                        var services = request.resource().getResourceServiceProvider();
                        var global = services.get(org.eclipse.xtext.scoping.IGlobalScopeProvider.class);
                        var slices = services.get(IIndexSlicePredicateService.class);
                        var information = services.get(com._1c.g5.v8.dt.bsl.resource.BslAdditionalIndexInformationHelper.class);
                        for (Environments environments : variants)
                        {
                            if (environments == null || environments.isEmpty())
                                continue;
                            var filter = new com._1c.g5.v8.dt.bsl.scoping.BslGlobalScopeFilter(
                                environments, false, false, information);
                            java.util.function.Supplier<IScope> scope = () -> global.getScope(request.resource(),
                                McorePackage.Literals.TYPE_DESCRIPTION__TYPES, filter);
                            for (boolean constructor : new boolean[] { false, true })
                            {
                                if (monitor.isCanceled())
                                    throw new org.eclipse.core.runtime.OperationCanceledException();
                                catalog(request.resource(), environments, request.russian(), constructor, scope, slices);
                            }
                        }
                        return org.eclipse.core.runtime.Status.OK_STATUS;
                    }
                    catch (org.eclipse.core.runtime.OperationCanceledException canceled)
                    {
                        return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                    }
                    catch (RuntimeException | LinkageError failure)
                    {
                        return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                    }
                    finally
                    {
                        WARM_MONITOR.remove();
                        synchronized (WARMUPS)
                        {
                            WARMUPS.remove(document, this);
                        }
                    }
                }
            };
            WARMUPS.put(document, job);
            job.setSystem(true);
            job.schedule();
        }
    }

    static void install()
    {
        running = ENABLED;
        System.getProperties().put(CALLBACK, (java.util.function.Function<Object[], Object>)BslTypeCatalog::proposals);
        System.getProperties().put(CONSTRUCTOR_CALLBACK,
            (java.util.function.Function<Object[], Object>)BslTypeCatalog::constructorDescriptions);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(WORKSPACE_LISTENER,
            IResourceChangeEvent.POST_CHANGE | IResourceChangeEvent.PRE_CLOSE | IResourceChangeEvent.PRE_DELETE);
    }

    static void stop()
    {
        synchronized (WARMUPS)
        {
            running = false;
            WARMUPS.values().forEach(org.eclipse.core.runtime.jobs.Job::cancel);
            PENDING_MODELS.forEach(IXtextDocument::removeModelListener);
            PENDING_MODELS.clear();
        }
        System.getProperties().put(CALLBACK, (java.util.function.Function<Object[], Object>)args -> null);
        System.getProperties().put(CONSTRUCTOR_CALLBACK, (java.util.function.Function<Object[], Object>)args -> null);
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(WORKSPACE_LISTENER);
        List<ProjectCatalog> catalogs;
        synchronized (PROJECTS)
        {
            catalogs = new ArrayList<>(PROJECTS.values());
            PROJECTS.clear();
        }
        catalogs.forEach(ProjectCatalog::close);
    }

    private static Object proposals(Object[] args)
    {
        if (!ENABLED)
            return null;
        try
        {
            Object provider = args[0];
            String name = provider.getClass().getName();
            boolean constructor = name.equals(PROVIDERS + "StringLiteralProposalProviderFuncCtor");
            boolean description = name.equals(PROVIDERS + "StringLiteralProposalProviderTypeDescription");
            if (!constructor && !description && !name.equals(PROVIDERS + "StringLiteralProposalProviderMethodType"))
                return null;
            var bundle = org.osgi.framework.FrameworkUtil.getBundle(provider.getClass());
            if (bundle == null || !bundle.getVersion().toString().equals("22.0.0.v202605050943"))
                return null;
            EObject context = (EObject)args[1];
            if (!(context.eResource() instanceof XtextResource resource) || !(args[2] instanceof String typed))
                return null;
            Environments environments = environments(context);
            if (environments == null)
                return null;
            boolean russian = (Boolean)args[5];
            NameIndex index = catalog(resource, environments, russian, constructor,
                () -> ((IScopeProvider)args[3]).getScope(context, McorePackage.Literals.TYPE_DESCRIPTION__TYPES),
                (IIndexSlicePredicateService)args[4]);
            if (index == null)
                return null;
            String token = typed;
            String before = "";
            if (description)
            {
                String[] parts = typed.split(",");
                if (parts.length == 0)
                    return List.of();
                token = parts[parts.length - 1].trim();
                before = typed.substring(0, typed.lastIndexOf(token));
            }
            int dot = token.lastIndexOf('.');
            String parent = dot >= 0 ? token.substring(0, dot) : "";
            List<NameOption> options = (description ? index.description : index.ordinary)
                .getOrDefault(parent, List.of());
            List<Triple<String, String, IBslStringLiteralProposalImageProvider>> result = new ArrayList<>(options.size());
            for (NameOption option : options)
                result.add(Tuples.create("\"" + before + option.text, option.label,
                    IBslStringLiteralProposalImageProvider.NULL_IMAGE_PROVIDER));
            return result;
        }
        catch (org.eclipse.core.runtime.OperationCanceledException canceled)
        {
            throw canceled;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return null;
        }
    }

    private static NameIndex catalog(XtextResource resource, Environments environments,
        boolean russian, boolean constructor, java.util.function.Supplier<IScope> scope,
        IIndexSlicePredicateService slices)
    {
        IV8Project v8 = v8Project(resource);
        IProject project = v8 != null ? v8.getProject() : null;
        IBmModelManager models = Global.getOsgiService(IBmModelManager.class);
        IBmModel model = project != null && models != null ? models.getModel(project) : null;
        if (project == null || model == null || model.isDisposed())
            return null;
        if (v8 instanceof com._1c.g5.v8.dt.core.platform.IDependentProject dependent
            && dependent.getParent() != null)
        {
            IProject parent = dependent.getParent().getProject();
            IBmModel parentModel = parent != null ? models.getModel(parent) : null;
            if (parentModel == null || parentModel.isDisposed())
                return null;
            ProjectCatalog parentCatalog;
            synchronized (PROJECTS)
            {
                parentCatalog = PROJECTS.computeIfAbsent(parent, key -> new ProjectCatalog(key.getName()));
            }
            // Изменение базовой конфигурации должно сбрасывать каталог расширения,
            // даже если сам каталог базового проекта ещё ни разу не запрашивали.
            parentCatalog.watch(parentModel);
        }
        ProjectCatalog projectCatalog;
        synchronized (PROJECTS)
        {
            projectCatalog = PROJECTS.computeIfAbsent(project, key -> new ProjectCatalog(key.getName()));
        }
        projectCatalog.watch(model);
        CatalogState state = projectCatalog.state;
        TypeNameKey key = new TypeNameKey(environments, constructor);
        LanguageCatalog catalog = state.catalogs.computeIfAbsent(key, unused -> new LanguageCatalog());
        int language = russian ? 0 : 1;
        NameIndex index = catalog.languages.get(language);
        boolean hit = index != null;
        if (!hit)
        {
            index = build(scope.get(),
                slices,
                russian, constructor);
            // Публикуется только готовый каталог. Строим без замка: другой расчёт может держать AST.
            catalog.languages.compareAndSet(language, null, index);
            index = catalog.languages.get(language);
        }
        return index;
    }

    private static Environments environments(EObject context)
    {
        Method method = EcoreUtil2.getContainerOfType(context, Method.class);
        Module module = EcoreUtil2.getContainerOfType(context, Module.class);
        return method != null ? method.environments() : module != null ? module.environments() : null;
    }

    private static Object constructorDescriptions(Object[] args)
    {
        long started = System.nanoTime();
        try
        {
            EObject context = (EObject)args[0];
            if (!(context.eResource() instanceof XtextResource resource))
                return null;
            Environments environments = environments(context);
            if (environments == null)
                return null;
            var services = resource.getResourceServiceProvider();
            // Штатный провайдер сам проверяет TYPE и no_ctor. Передаём полный
            // перечень описаний типов, не применяя фильтр строковых предложений.
            Iterable<IEObjectDescription> descriptions;
            int typeCount;
            if (ENABLED && CONSTRUCTOR_CACHE_ENABLED)
            {
                NameIndex index = catalog(resource, environments, (Boolean)args[2], false,
                    () -> (IScope)args[1], services.get(IIndexSlicePredicateService.class));
                if (index == null)
                    return null;
                descriptions = index.types();
                typeCount = index.types().size();
            }
            else
            {
                var slices = services.get(IIndexSlicePredicateService.class);
                var filter = slices.getPredicate((Boolean)args[2] ? "indexSliceScriptRu" : "indexSliceScriptIntnl");
                descriptions = ((ISlicedScope)args[1]).getAllElements(List.of(filter));
                typeCount = -1;
            }
            Global.tempLog("constructor-cost", "begin catalogMs=" + ((System.nanoTime() - started) / 1_000_000.0)
                + " cacheEnabled=" + (ENABLED && CONSTRUCTOR_CACHE_ENABLED)
                + " types=" + typeCount + " thread=" + Thread.currentThread().getName());
            // Измеряем штатный цикл обработки готовых описаний без изменения его порядка.
            return (Iterable<IEObjectDescription>)() -> new java.util.Iterator<IEObjectDescription>()
            {
                final java.util.Iterator<IEObjectDescription> iterator = descriptions.iterator();
                final long loopStarted = System.nanoTime();
                int visited;
                boolean logged;

                @Override
                public boolean hasNext()
                {
                    boolean more = iterator.hasNext();
                    if (!more && !logged)
                    {
                        logged = true;
                        Global.tempLog("constructor-cost", "end nativeLoopMs="
                            + ((System.nanoTime() - loopStarted) / 1_000_000.0)
                            + " visited=" + visited + " thread=" + Thread.currentThread().getName());
                    }
                    return more;
                }

                @Override
                public IEObjectDescription next()
                {
                    IEObjectDescription result = iterator.next();
                    visited++;
                    return result;
                }
            };
        }
        catch (org.eclipse.core.runtime.OperationCanceledException canceled)
        {
            throw canceled;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return null;
        }
    }

    private static NameIndex build(IScope scope, IIndexSlicePredicateService slices,
        boolean russian, boolean constructor)
    {
        var filter = slices.getPredicate(russian ? "indexSliceScriptRu" : "indexSliceScriptIntnl");
        List<IEObjectDescription> types = new ArrayList<>();
        Map<String, Map<String, NameOption>> ordinary = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Map<String, NameOption>> description = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (IEObjectDescription item : ((ISlicedScope)scope).getAllElements(List.of(filter)))
        {
            if (Thread.currentThread().isInterrupted()
                || (WARM_MONITOR.get() != null && WARM_MONITOR.get().isCanceled()))
                throw new org.eclipse.core.runtime.OperationCanceledException();
            boolean plainType = item.getEClass() == McorePackage.Literals.TYPE;
            if (plainType)
                types.add(item);
            if (item.getUserData("MetaType") != null || item.getEClass() == McorePackage.Literals.TYPE_SET)
                continue;
            if (constructor && (!plainType || !((Type)item.getEObjectOrProxy()).isCreatedByNewOperator()))
                continue;
            QualifiedName name = item.getQualifiedName();
            String parent = "";
            for (int segment = 0; segment < name.getSegmentCount(); segment++)
            {
                String label = name.getSegment(segment);
                String text = parent.isEmpty() ? label : parent + "." + label;
                NameOption option = new NameOption(text, label);
                description.computeIfAbsent(parent, unused -> new LinkedHashMap<>())
                    .putIfAbsent(lower(text), option);
                if (plainType)
                    ordinary.computeIfAbsent(parent, unused -> new LinkedHashMap<>())
                        .putIfAbsent(lower(text), option);
                parent = text;
            }
        }
        return new NameIndex(freeze(ordinary), freeze(description), List.copyOf(types));
    }

    private static Map<String, List<NameOption>> freeze(Map<String, Map<String, NameOption>> source)
    {
        Map<String, List<NameOption>> result = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        source.forEach((parent, options) -> result.put(parent, List.copyOf(options.values())));
        return Collections.unmodifiableMap(result);
    }

    private static void invalidateProjects(String reason)
    {
        List<ProjectCatalog> catalogs;
        synchronized (PROJECTS)
        {
            catalogs = new ArrayList<>(PROJECTS.values());
        }
        // Зависимые проекты и расширения тоже могут использовать типы изменённой конфигурации.
        catalogs.forEach(catalog -> catalog.invalidate(reason));
    }

    private static void workspaceChanged(IResourceChangeEvent event)
    {
        if (event.getType() != IResourceChangeEvent.POST_CHANGE)
        {
            if (event.getResource() instanceof IProject project)
            {
                ProjectCatalog removed = PROJECTS.remove(project);
                if (removed != null)
                    removed.close();
                invalidateProjects("projectClosed:" + project.getName());
            }
            return;
        }
        if (event.getDelta() == null)
            return;
        for (var delta : event.getDelta().getAffectedChildren())
            if (delta.getResource() instanceof IProject project
                && (delta.findMember(new org.eclipse.core.runtime.Path(".settings")) != null
                    || delta.findMember(new org.eclipse.core.runtime.Path(".project")) != null
                    || (delta.getFlags() & org.eclipse.core.resources.IResourceDelta.OPEN) != 0))
            {
                invalidateProjects("projectSettings:" + project.getName());
            }
    }

    private static record TypeNameKey(Environments environments, boolean constructor)
    {
    }

    private static record NameOption(String text, String label)
    {
    }

    private static record NameIndex(Map<String, List<NameOption>> ordinary,
        Map<String, List<NameOption>> description, List<IEObjectDescription> types)
    {
    }

    private static final class LanguageCatalog
    {
        final java.util.concurrent.atomic.AtomicReferenceArray<NameIndex> languages =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(2);
    }

    private static final class CatalogState
    {
        final long generation;
        final Map<TypeNameKey, LanguageCatalog> catalogs = new ConcurrentHashMap<>();

        CatalogState(long generation)
        {
            this.generation = generation;
        }
    }

    private static final class ProjectCatalog
    {
        final String projectName;
        volatile CatalogState state = new CatalogState(0);
        private java.lang.ref.WeakReference<IBmModel> model = new java.lang.ref.WeakReference<>(null);
        private final IBmSyncEventListener listener = this::metadataChanged;

        ProjectCatalog(String projectName)
        {
            this.projectName = projectName;
        }

        synchronized void watch(IBmModel current)
        {
            if (model.get() == current)
                return;
            close();
            current.addSyncEventListener(listener);
            model = new java.lang.ref.WeakReference<>(current);
            invalidate("modelChanged");
        }

        synchronized void close()
        {
            IBmModel previous = model.get();
            if (previous != null && !previous.isDisposed())
                previous.removeSyncEventListener(listener);
            model.clear();
            invalidate("close");
        }

        void invalidate(String reason)
        {
            // Новый объект состояния: расчёт, начавшийся до события, не заполнит новый каталог.
            CatalogState previous = state;
            state = new CatalogState(previous.generation + 1);
        }

        private static boolean metadata(Object object)
        {
            return object instanceof EObject node
                && node.eClass().getEPackage() == com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.eINSTANCE;
        }

        private void metadataChanged(BmEvent event)
        {
            try
            {
                onMetadataChanged(event);
            }
            catch (RuntimeException | LinkageError failure)
            {
                metadataInvalidated("listenerFailure");
            }
        }

        private void metadataInvalidated(String reason)
        {
            invalidateProjects("metadata:" + projectName + ":" + reason);
        }

        private void onMetadataChanged(BmEvent event)
        {
            if (event.getChangeEvents() != null)
                for (var change : event.getChangeEvents().values())
                    if (metadata(change.getObject()))
                    {
                        metadataInvalidated(change.isFqnChanged() ? "rename:" + change.getOldFqn() : "metadataChange");
                        return;
                    }
            var association = event.getAssociationEvent();
            if (association == null)
                return;
            if (association.getAssociated() != null)
                for (Object object : association.getAssociated())
                    if (metadata(object))
                    {
                        metadataInvalidated("metadataAdded");
                        return;
                    }
            if (association.getDeassociated() != null)
                for (Object object : association.getDeassociated())
                    if (metadata(object))
                    {
                        metadataInvalidated("metadataRemoved");
                        return;
                    }
            // При удалении BM может передать только идентификаторы уже отсоединённых объектов.
            if (association.isDeassociated()
                && (association.getDeassociated() == null || association.getDeassociated().isEmpty()))
                metadataInvalidated("removedIds");
        }
    }
    private static String lower(String value)
    {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static IV8Project v8Project(XtextResource resource)
    {
        var projects = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        return projects != null && resource != null ? projects.getProject(resource.getURI()) : null;
    }
}


