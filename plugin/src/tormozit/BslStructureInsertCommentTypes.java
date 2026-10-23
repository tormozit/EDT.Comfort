// BslStructureInsertCommentTypes.java
package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.util.OnChangeEvictingCache;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.hover.IEObjectHover;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.util.Triple;

import com._1c.g5.v8.bm.core.BmPlatform;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslCommentUtils;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider;
import com._1c.g5.v8.dt.bsl.documentation.comment.LinkPart;
import com._1c.g5.v8.dt.bsl.documentation.comment.TagPart;
import com._1c.g5.v8.dt.bsl.documentation.comment.TextPart;
import com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection;
import com._1c.g5.v8.dt.bsl.model.Block;
import com._1c.g5.v8.dt.bsl.model.BslContextDefMockProperty;
import com._1c.g5.v8.dt.bsl.model.BslDerivedPropertySource;
import com._1c.g5.v8.dt.bsl.model.DeclareStatement;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.ExplicitVariable;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.ExtendedType;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.model.typesytem.ThreadSafeVariableTypeStateProvider;
import com._1c.g5.v8.dt.bsl.model.typesytem.TypeSystemMode;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeState;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProvider;
import com._1c.g5.v8.dt.bsl.model.typesytem.VariableTypeStateProviderCollector;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.typesystem.BslTreeTypeSystem;
import com._1c.g5.v8.dt.bsl.typesystem.BslTypeSystemProvider;
import com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainerRef;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com.e1c.g5.dt.core.api.naming.INamingService;
import com.e1c.g5.dt.core.api.platform.BmOperationContext;

/**
 * Типизация свойства структуры и колонки таблицы значений боковым комментарием (issue 512):
 * {@code Ног.Вставить("Бобр"); // Массив} — и дальше {@code Ног.Бобр.} работает как массив.
 * <p>
 * <b>Что делает EDT сам.</b> {@code BslTreeTypeSystem.expandStructureType} уже создаёт по
 * вызову {@code Вставить("Бобр", Значение)} свойство {@code Бобр} с типами второго параметра.
 * Без второго параметра свойство создаётся с <b>пустым</b> списком типов — имя в списке есть,
 * типа нет. Мы дописываем типы из бокового комментария: не вместо вычисленных, а вместе с
 * ними (флажок проекта «Расширенный расчет типов», issue 509).
 * <p>
 * <b>Почему подменяется экземпляр, а не класс.</b> Замер 13.09.2026: {@code BslTreeTypeSystem},
 * {@code TypesComputer}, {@code DynamicFeatureAccessComputer} — всё, что инжектор BSL создаёт
 * синглтоном при сборке, — загружены до активации нашего бандла, и {@link org.osgi.framework.hooks.weaving.WeavingHook}
 * их уже не видит (ловятся только ленивые: {@code CreatorTreeState}, {@code TypeSystemUtil},
 * {@code BslCommentUtils}). Подмены байткода тут быть не может.
 * <p>
 * Зато систему типов выдаёт <b>одно поле одного синглтона</b>:
 * {@code BslTypeSystemProvider.treeTypeSystem}, и читают его только {@code BslDerivedStateComputer}
 * и {@code BslResource}, оба через провайдер. Кладём туда свой подкласс, переопределяющий
 * публичные точки входа {@code installTypeSystem} и {@code lightInstallingTypeSystem}: внутри
 * сначала штатный расчёт, потом наш проход. Типы попадают в саму модель, поэтому работают
 * везде — автодополнение, подсказка при наведении, валидация, «Найти ссылки», — а не только
 * там, где успел отработать наш код.
 * <p>
 * Отдельно: экспорт внешнего модуля в CA идёт через {@link BslContextDefMockProperty} без
 * типов. Подменяем {@code DynamicFeatureAccessComputer.computerTypes} на
 * {@link ComfortTypesComputer}, чтобы перед расчётом скопировать типы с живого Property.
 * <p>
 * Поля подкласса — копия полей исходного экземпляра (рефлексия): те же сервисы и кэши, тот же
 * объект по поведению, только с довеском.
 */
public final class BslStructureInsertCommentTypes
    implements IStartup
{
    /** Поле провайдера, через которое EDT выдаёт систему типов. */
    private static final String TREE_FIELD = "treeTypeSystem"; //$NON-NLS-1$

    /** Методы, добавляющие свойство: структура и колонки таблицы (дерева) значений. */
    private static final Set<String> KEY_METHODS =
        Set.of("вставить", "insert", "добавить", "add"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private static final AtomicBoolean installed = new AtomicBoolean();

    @Override
    public void earlyStartup()
    {
        Job job = new Job("Comfort: типы свойств структуры по комментарию") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                install();
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /**
     * Подмена экземпляра системы типов. Идемпотентна: повторный вызов при уже подменённом
     * поле ничего не делает.
     */
    public static void install()
    {
        if (installed.get())
            return;
        try
        {
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(URI.createURI("comfort.bsl")); //$NON-NLS-1$
            if (rsp == null)
                return;
            BslTypeSystemProvider provider = rsp.get(BslTypeSystemProvider.class);
            if (provider == null)
                return;
            Field field = BslTypeSystemProvider.class.getDeclaredField(TREE_FIELD);
            field.setAccessible(true);
            Object current = field.get(provider);
            if (!(current instanceof ComfortTreeTypeSystem))
            {
                if (!(current instanceof BslTreeTypeSystem original))
                    return;
                ComfortTreeTypeSystem ours = new ComfortTreeTypeSystem();
                copyFields(original, ours);
                field.set(provider, ours);
            }
            installTypesComputerWrap(rsp);
            installed.set(true);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Внешний модуль формы: EDT для экспорта подставляет {@link BslContextDefMockProperty}
     * без типов (только имя). {@code Форма.КэшАФВ} видно, {@code Форма.КэшАФВ.} — пусто.
     * Типы лежат на живом {@code Module.getContextDef()} Property — копируем на mock
     * перед {@link TypesComputer#computeTypes}.
     */
    private static void installTypesComputerWrap(IResourceServiceProvider rsp) throws Exception
    {
        DynamicFeatureAccessComputer dfa = rsp.get(DynamicFeatureAccessComputer.class);
        TypesComputer current = rsp.get(TypesComputer.class);
        if (dfa == null || current == null)
            return;
        Field field = DynamicFeatureAccessComputer.class.getDeclaredField("computerTypes"); //$NON-NLS-1$
        field.setAccessible(true);
        Object onDfa = field.get(dfa);
        if (onDfa instanceof ComfortTypesComputer)
            return;
        TypesComputer source = onDfa instanceof TypesComputer typed ? typed : current;
        if (source instanceof ComfortTypesComputer)
        {
            field.set(dfa, source);
            return;
        }
        ComfortTypesComputer ours = new ComfortTypesComputer();
        copyTypesComputerFields(source, ours);
        field.set(dfa, ours);
    }

    private static void copyTypesComputerFields(TypesComputer from, TypesComputer to)
        throws Exception
    {
        for (Field field : TypesComputer.class.getDeclaredFields())
        {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
                continue;
            field.setAccessible(true);
            field.set(to, field.get(from));
        }
    }

    /**
     * Mock экспортного свойства внешнего модуля — без {@code typeContainer}. Берём типы
     * с живого Property того же имени в модуле {@code sourceUri}.
     */
    static void fillMockExportPropertyTypes(EObject object)
    {
        if (!(object instanceof BslContextDefMockProperty mock))
            return;
        try
        {
            if (mock.getTypeContainer() instanceof TypeContainerRef ref
                && ref.getTypes() != null && !ref.getTypes().isEmpty())
                return;
            if (mock.getTypes() != null && !mock.getTypes().isEmpty())
                return;
            String name = mock.getName();
            URI sourceUri = mock.getSourceUri();
            if (name == null || name.isEmpty() || sourceUri == null)
                return;
            Module module = moduleFromSourceUri(sourceUri, mock.getContext());
            if (module == null || module.eIsProxy())
                return;
            enrichExportModuleVariables(module);
            Property live = liveExportProperty(module, name);
            if (live == null)
                return;
            Collection<TypeItem> liveTypes = live.getTypeContainer() instanceof TypeContainerRef ref
                ? ref.getTypes()
                : live.getTypes();
            if (liveTypes == null || liveTypes.isEmpty())
                return;
            TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
            for (TypeItem item : liveTypes)
            {
                if (item instanceof EObject eObject)
                    container.getTypes().add((TypeItem)EcoreUtil.copy(eObject));
            }
            mock.setTypeContainer(container);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static Property liveExportProperty(Module module, String name)
    {
        ContextDef contextDef = module.getContextDef();
        if (contextDef == null || name == null)
            return null;
        for (Property property : contextDef.getProperties())
        {
            if (property != null && name.equalsIgnoreCase(property.getName()))
                return property;
        }
        return null;
    }

    private static Module moduleFromSourceUri(URI sourceUri, EObject context)
    {
        if (sourceUri == null)
            return null;
        try
        {
            ResourceSet resourceSet = null;
            if (context != null && context.eResource() != null)
                resourceSet = context.eResource().getResourceSet();
            if (resourceSet == null)
                return null;
            Resource resource = resourceSet.getResource(sourceUri.trimFragment(), true);
            if (resource == null || resource.getContents().isEmpty())
                return null;
            EObject root = resource.getContents().get(0);
            return root instanceof Module module ? module : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /**
     * Копирует поля исходного экземпляра в наш. Не {@code injectMembers}: так наш объект
     * получает ровно те же сервисы и кэши, что уже живут у EDT, без второго набора.
     */
    private static void copyFields(BslTreeTypeSystem from, BslTreeTypeSystem to) throws Exception
    {
        for (Field field : BslTreeTypeSystem.class.getDeclaredFields())
        {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
                continue;
            field.setAccessible(true);
            field.set(to, field.get(from));
        }
    }

    /** Значение приватного поля системы типов — сервисы EDT берём у неё же. */
    private static Object service(BslTreeTypeSystem self, String name)
    {
        try
        {
            Field field = BslTreeTypeSystem.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(self);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /**
     * Типы экспортных {@code Перем …; // см. …} модуля формы → {@code Property.typeContainer}
     * в {@code Module.getContextDef()}. Без этого после {@code Форма.КэшАФВ.} пусто: EDT
     * копирует снимок при сборке контекста, а из чужого модуля расчёт формы не идёт.
     */
    static void enrichExportModuleVariables(Module module)
    {
        if (module == null || module.eIsProxy())
            return;
        try
        {
            if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(module))
                return;
            IProject project = projectOf(module);
            if (project == null)
                return;
            BslTreeTypeSystem tree = peekTreeTypeSystem();
            if (tree == null)
                return;
            // Повтор, пока нет переносимого ExtendedType с свойствами.
            if (EXPORT_VARS_DONE.contains(module) && exportVarsArePortable(module))
                return;
            // Внутри одного внешнего расчёта — один проход на модуль: обогащение зовётся на
            // каждый тип формы в каждом состоянии, и повтор до «переносимого» типа давал сотни
            // тысяч проходов за расчёт (issue 530).
            Set<Module> scope = EXPORT_VARS_SCOPE.get();
            if (scope != null && !scope.add(module))
                return;
            enrichExportModuleVariables(tree, module);
            if (exportVarsArePortable(module))
                EXPORT_VARS_DONE.add(module);
            else
                EXPORT_VARS_DONE.remove(module);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static boolean exportVarsArePortable(Module module)
    {
        ContextDef contextDef = module.getContextDef();
        if (contextDef == null)
            return false;
        for (Property property : contextDef.getProperties())
        {
            if (property == null)
                continue;
            Collection<TypeItem> types = property.getTypeContainer() instanceof TypeContainerRef ref
                ? ref.getTypes()
                : property.getTypes();
            if (types == null)
                continue;
            for (TypeItem item : types)
            {
                if (!(item instanceof ExtendedType)
                    || BslDocCommentComputedTypes.contextPropertyCount(List.of(item)) <= 0)
                    continue;
                // Только ключи без методов Structure — ещё не готово (как у Предок. с Количество).
                if (item instanceof Type type && type.getContextDef() != null
                    && type.getContextDef().allMethods() != null
                    && !type.getContextDef().allMethods().isEmpty())
                    return true;
            }
        }
        return false;
    }

    /** Один успешный проход (переносимый ExtendedType) на экземпляр модуля за сессию. */
    private static final java.util.Set<Module> EXPORT_VARS_DONE = java.util.Collections
        .synchronizedSet(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    /**
     * Модули, у которых проход экспортных переменных уже сделан в текущем внешнем расчёте
     * потока ({@code installTypeSystem}, {@code lightInstallingTypeSystem},
     * {@link BslFormTypeContextEnrichment#enrichTypes}); вне расчёта — {@code null}.
     */
    private static final ThreadLocal<Set<Module>> EXPORT_VARS_SCOPE = new ThreadLocal<>();

    /** @return {@code true}, если расчёт внешний и область заведена им — передать в {@link #endExportVarsScope}. */
    static boolean beginExportVarsScope()
    {
        if (EXPORT_VARS_SCOPE.get() != null)
            return false;
        EXPORT_VARS_SCOPE.set(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return true;
    }

    static void endExportVarsScope(boolean owner)
    {
        if (owner)
            EXPORT_VARS_SCOPE.remove();
    }

    /**
     * Перевязка модуля, как после правки текста, но всего модуля: частичный результат разбора
     * на время перевязки не указывает метод. Вызывать под {@code readOnly} документа, не под
     * {@code modify}: тот после работы сериализует изменённую модель в текст и падает на
     * служебных свойствах модуля. Блокировка перевязки ресурса берётся здесь.
     *
     * @return {@code false}, если у ресурса нет блокировки
     */
    static boolean relinkModuleFully(org.eclipse.xtext.resource.XtextResource resource)
    {
        Object lock = Global.invoke(resource, "getLock"); //$NON-NLS-1$
        if (lock == null)
            return false;
        return ComfortTreeTypeSystem.underLinkLock(resource, lock, () ->
        {
            Module module = null;
            if (!resource.getContents().isEmpty() && resource.getContents().get(0) instanceof Module root)
                module = root;
            Object partial = module == null ? null : ComfortTreeTypeSystem.partialParseResult(module);
            Runnable restore = partial == null ? null : ComfortTreeTypeSystem.substituteReparseMethod(partial, null);
            try
            {
                resource.relink();
            }
            finally
            {
                if (restore != null)
                    restore.run();
            }
            return true;
        });
    }

    /**
     * Перед автодополнением: если метод под кареткой ссылается «см.» на функцию, сменившую
     * версию после его последнего расчёта, — пересчитать только его (и его вызывающих) штатным
     * частичным расчётом; если расчёт модуля был отменён — модуль целиком. Вызывать в фоновом
     * потоке под {@code readOnly} документа: блокировка перевязки ресурса берётся здесь.
     */
    static void refreshTypesForAssist(Resource resource, int offset,
        java.util.function.Consumer<String> onRecompute)
    {
        if (resource != null && org.eclipse.swt.widgets.Display.getCurrent() == null
            && !resource.getContents().isEmpty()
            && BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0))
            && peekTreeTypeSystem() instanceof ComfortTreeTypeSystem queryTree)
        {
            Object queryLock = Global.invoke(resource, "getLock"); //$NON-NLS-1$
            if (queryLock != null)
                ComfortTreeTypeSystem.underLinkLock(resource, queryLock, () ->
                {
                    BslQueryResultTypes.refresh(resource, offset, onRecompute,
                        method ->
                        {
                            Module module = EcoreUtil2.getContainerOfType(method, Module.class);
                            if (!queryTree.recomputeDependent(module, method, CancelIndicator.NullImpl))
                                return false;
                            java.util.Set<String> changed = BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
                            if (!changed.isEmpty())
                                ComfortTreeTypeSystem.StaleTypes.add(resource, changed);
                            return true;
                        });
                    return true;
                });
        }
        ComfortTreeTypeSystem.StaleTypes stale = resource == null ? null
            : ComfortTreeTypeSystem.StaleTypes.BY_RESOURCE.get(resource);
        if (stale == null || !(resource instanceof org.eclipse.xtext.resource.XtextResource xtext)
            || resource.getContents().isEmpty() || !(resource.getContents().get(0) instanceof Module module)
            || !(peekTreeTypeSystem() instanceof ComfortTreeTypeSystem tree))
            return;
        Object lock = Global.invoke(resource, "getLock"); //$NON-NLS-1$
        if (lock == null)
            return;
        try
        {
            boolean whole;
            synchronized (stale)
            {
                whole = stale.wholeModule;
            }
            // Отмена расчёта — обычное дело (связь со схемой отменяется вводом почти на каждой
            // правке), а следующие расчёты состояния обычно уже вернули: полный пересчёт модуля
            // (7,8 с в модуле БСП, лог 18.10 09:04) — только если у функции состояния правда нет.
            if (whole && !hasFunctionWithoutState(module))
            {
                synchronized (stale)
                {
                    stale.wholeModule = false;
                }
                whole = false;
            }
            // #region agent log second-pass
            long diagStart = System.currentTimeMillis();
            String diagModule = BslDocCommentComputedTypes.diagModuleName(module);
            // #endregion
            if (whole)
            {
                // #region agent log second-pass
                Global.tempLog("see-second-pass", "assist.whole.start module=" + diagModule //$NON-NLS-1$ //$NON-NLS-2$
                    + " th=" + Thread.currentThread().getName()); //$NON-NLS-1$
                // #endregion
                onRecompute.accept("Пересчёт типов модуля…"); //$NON-NLS-1$
                ComfortTreeTypeSystem.SecondPassProgress progress = new ComfortTreeTypeSystem.SecondPassProgress(module, 0);
                try
                {
                    ComfortTreeTypeSystem.underLinkLock(resource, lock, () ->
                    {
                        tree.recomputeModuleFully(module, CancelIndicator.NullImpl);
                        ModuleVariableStates.fix(module);
                        BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
                        return true;
                    });
                }
                finally
                {
                    progress.finish();
                    // #region agent log second-pass
                    Global.tempLog("see-second-pass", "assist.whole.end module=" + diagModule //$NON-NLS-1$ //$NON-NLS-2$
                        + " ms=" + (System.currentTimeMillis() - diagStart)); //$NON-NLS-1$
                    // #endregion
                }
                ComfortTreeTypeSystem.StaleTypes.clear(resource);
                BslDocCommentComputedTypes.bumpTypesGeneration();
                return;
            }
            Method method = EcoreUtil2.getContainerOfType(
                new org.eclipse.xtext.resource.EObjectAtOffsetHelper().resolveContainedElementAt(xtext, offset),
                Method.class);
            if (method == null || method.getName() == null)
                return;
            String key = method.getName().toLowerCase(java.util.Locale.ROOT);
            java.util.Set<String> names;
            int version;
            synchronized (stale)
            {
                version = stale.version;
                if (Integer.valueOf(version).equals(stale.refreshed.get(key)))
                    return;
                names = new java.util.HashSet<>(stale.names);
            }
            List<Method> dependencies = new ArrayList<>();
            collectStaleDependencies(module, method, names,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), dependencies);
            // #region agent log second-pass
            Global.tempLog("see-second-pass", "assist.method module=" + diagModule //$NON-NLS-1$ //$NON-NLS-2$
                + " method=" + method.getName() + " staleNames=" + names.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " version=" + version + " dependencies=" + dependencies.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " th=" + Thread.currentThread().getName()); //$NON-NLS-1$
            // #endregion
            if (!dependencies.isEmpty())
            {
                onRecompute.accept("Пересчёт типов: " + method.getName() + "…"); //$NON-NLS-1$ //$NON-NLS-2$
                ComfortTreeTypeSystem.SecondPassProgress progress =
                    new ComfortTreeTypeSystem.SecondPassProgress(module, dependencies.size());
                boolean done;
                try
                {
                    done = ComfortTreeTypeSystem.underLinkLock(resource, lock, () ->
                    {
                        for (Method dependency : dependencies)
                        {
                            progress.step(dependency.getName());
                            if (!tree.recomputeDependent(module, dependency, CancelIndicator.NullImpl))
                                return false;
                            // Следующий метод должен разрешать «см.» уже по свежему возврату.
                            BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
                            BslDocCommentComputedTypes.bumpTypesGeneration();
                            progress.stepDone();
                        }
                        return true;
                    });
                }
                finally
                {
                    progress.finish();
                    // #region agent log second-pass
                    Global.tempLog("see-second-pass", "assist.method.end module=" + diagModule //$NON-NLS-1$ //$NON-NLS-2$
                        + " method=" + method.getName() //$NON-NLS-1$
                        + " ms=" + (System.currentTimeMillis() - diagStart)); //$NON-NLS-1$
                    // #endregion
                }
                if (!done)
                    // Подставить метод не удалось — пометку не снимаем.
                    return;
            }
            synchronized (stale)
            {
                stale.refreshed.put(key, version);
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // Пересчёт не удался — список посчитается по тому, что есть в модели.
        }
    }

    /**
     * Только зависимости текущего метода: сначала цели «см.» и обычных вызовов,
     * затем потребитель. Пересчёт одной процедуры не обновляет готовые состояния вызываемых
     * функций, поэтому цепочка «МояТЗ → Яйцо → НовыйБук → параметр процедуры» теряла тип таблицы.
     */
    private static boolean collectStaleDependencies(Module module, Method method, Set<String> names,
        Set<Method> visited, List<Method> ordered)
    {
        if (!visited.add(method))
            return ordered.contains(method);
        Set<Method> dependencies = new java.util.LinkedHashSet<>();
        for (Invocation invocation : EcoreUtil2.getAllContentsOfType(method, Invocation.class))
            if (invocation.getMethodAccess() instanceof StaticFeatureAccess access)
                for (FeatureEntry entry : access.getFeatureEntries())
                    if (entry.getFeature() instanceof Method called
                        && EcoreUtil2.getContainerOfType(called, Module.class) == module)
                        dependencies.add(called);
        for (Method candidate : module.allMethods())
            if (candidate.getName() != null && BslDocCommentComputedTypes.methodReferences(method,
                Set.of(candidate.getName().toLowerCase(Locale.ROOT))))
                dependencies.add(candidate);
        boolean stale = BslDocCommentComputedTypes.methodReferences(method, names);
        for (Method dependency : dependencies)
            if (collectStaleDependencies(module, dependency, names, visited, ordered))
                stale = true;
        if (stale)
            ordered.add(method);
        return stale;
    }

    /** У какой-то функции модуля нет NORMAL-состояния возврата (его стёр отменённый расчёт). */
    private static boolean hasFunctionWithoutState(Module module)
    {
        for (Method method : module.allMethods())
            if (method instanceof com._1c.g5.v8.dt.bsl.model.Function function
                && (function.getFinalReturnState() == null
                    || function.getFinalReturnState().get(TypeSystemMode.NORMAL) == null))
                return true;
        return false;
    }

    static BslTreeTypeSystem peekTreeTypeSystem()
    {
        install();
        try
        {
            IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(URI.createURI("comfort.bsl")); //$NON-NLS-1$
            if (rsp == null)
                return null;
            BslTypeSystemProvider provider = rsp.get(BslTypeSystemProvider.class);
            if (provider == null)
                return null;
            Field field = BslTypeSystemProvider.class.getDeclaredField(TREE_FIELD);
            field.setAccessible(true);
            Object current = field.get(provider);
            return current instanceof BslTreeTypeSystem tree ? tree : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /**
     * Полный {@code installTypeSystem} чужого модуля из нашего прохода (модуль формы из
     * {@code см. …}, модуль цели ссылки).
     * <p>
     * Штатный расчёт параллельный: каждый поток ForkJoin открывает свою транзакцию BM, а
     * вызывающий поток держит свою и ждёт их. Если в это время запрошена служебная операция
     * (деактивация проекта при закрытии EDT), новые транзакции ждут её, а она ждёт нашу —
     * взаимоблокировка (issue 530). Непрерываемая операция платформы не даёт служебной
     * операции начаться и пропускает новые транзакции без ожидания; закрытие дождётся конца
     * расчёта. Берём её, только если поток уже в транзакции: тогда служебная операция точно
     * не идёт, и транзакции ForkJoin не попадут внутрь неё.
     */
    static void installTypeSystemNonInterruptable(BslTreeTypeSystem tree, Module module)
    {
        BmPlatform platform = null;
        Object token = null;
        IBmModelManager models = (IBmModelManager)service(tree, "bmModelManager"); //$NON-NLS-1$
        IProject project = projectOf(module);
        IBmModel model = models != null && project != null ? models.getModel(project) : null;
        Object engine = model != null ? Global.invoke(model, "getEngine") : null; //$NON-NLS-1$
        if (engine != null && Global.invoke(engine, "getCurrentTransaction") instanceof IBmTransaction) //$NON-NLS-1$
        {
            platform = models.getBmPlatform();
            if (platform != null)
                token = platform.beginNonInterruptableOperation();
        }
        try
        {
            tree.installTypeSystem(module, CancelIndicator.NullImpl);
        }
        finally
        {
            if (token != null)
                platform.finishNonInterruptableOperation(token);
        }
    }

    private static void enrichExportModuleVariables(BslTreeTypeSystem self, Module module)
    {
        List<DeclareStatement> declares = new ArrayList<>();
        for (DeclareStatement declare : module.allDeclareStatements())
        {
            if (declare == null)
                continue;
            org.eclipse.emf.common.util.EList<ExplicitVariable> variables = declare.getVariables();
            if (variables == null || variables.isEmpty())
                continue;
            for (ExplicitVariable variable : variables)
            {
                if (variable != null && variable.isExport())
                {
                    declares.add(declare);
                    break;
                }
            }
        }
        if (declares.isEmpty())
            return;

        IProject project = projectOf(module);
        if (project == null)
            return;
        IScopeProvider scopeProvider = (IScopeProvider)service(self, "scopeProvider"); //$NON-NLS-1$
        IQualifiedNameConverter nameConverter =
            (IQualifiedNameConverter)service(self, "qualifiedNameConverter"); //$NON-NLS-1$
        BslMultiLineCommentDocumentationProvider commentProvider =
            (BslMultiLineCommentDocumentationProvider)service(self, "commentProvider"); //$NON-NLS-1$
        IV8ProjectManager v8ProjectManager =
            (IV8ProjectManager)service(self, "v8ProjectManager"); //$NON-NLS-1$
        if (scopeProvider == null || nameConverter == null || commentProvider == null
            || v8ProjectManager == null)
            return;

        IScope typeScope = scopeProvider.getScope(module,
            McorePackage.Literals.ABSTRACT_METHOD__RET_VAL_TYPE);
        boolean oldFormat = isOldCommentFormat(self, project);
        ExportModuleVariablesPass pass = new ExportModuleVariablesPass(module, declares,
            typeScope, scopeProvider, nameConverter, commentProvider, v8ProjectManager,
            oldFormat);
        runInTransaction(self, project, pass);
    }

    /**
     * Боковой {@code // …} на {@code DeclareStatement}: штатный {@code computeCommentTypes}
     * без узла берёт узел {@link Block} (модуль тоже {@code Block}).
     */
    private static Collection<TypeItem> computeDeclareSideCommentTypes(DeclareStatement declare,
        IScope typeScope, IScopeProvider scopeProvider, IQualifiedNameConverter nameConverter,
        BslMultiLineCommentDocumentationProvider commentProvider,
        IV8ProjectManager v8ProjectManager, boolean oldFormat, BmOperationContext context)
    {
        Block block = EcoreUtil2.getContainerOfType(declare, Block.class);
        ICompositeNode blockNode = block != null ? NodeModelUtils.findActualNodeFor(block) : null;
        Collection<TypeItem> types = TypeSystemUtil.computeCommentTypes(declare, typeScope,
            scopeProvider, nameConverter, commentProvider, v8ProjectManager, oldFormat, context);
        if (types != null && !types.isEmpty())
            return types;
        if (blockNode != null)
        {
            types = TypeSystemUtil.computeCommentTypes(declare, blockNode, typeScope, scopeProvider,
                nameConverter, commentProvider, v8ProjectManager, oldFormat, context);
            if (types != null && !types.isEmpty())
                return types;
        }
        return types != null ? types : List.of();
    }

    /**
     * Проход по модулю после штатного расчёта: свойства, созданные по вызовам
     * {@code Вставить} / {@code Добавить}, дополняются типами из бокового комментария.
     */
    static void enrich(BslTreeTypeSystem self, Module module, Method scope)
    {
        if (module == null || module.eIsProxy())
            return;
        try
        {
            IProject project = projectOf(module);
            if (project == null || !BslDocCommentComputedTypes.isExtendedTypesEnabled(project))
                return;

            IScopeProvider scopeProvider = (IScopeProvider)service(self, "scopeProvider"); //$NON-NLS-1$
            IQualifiedNameConverter nameConverter =
                (IQualifiedNameConverter)service(self, "qualifiedNameConverter"); //$NON-NLS-1$
            BslMultiLineCommentDocumentationProvider commentProvider =
                (BslMultiLineCommentDocumentationProvider)service(self, "commentProvider"); //$NON-NLS-1$
            IV8ProjectManager v8ProjectManager =
                (IV8ProjectManager)service(self, "v8ProjectManager"); //$NON-NLS-1$
            if (scopeProvider == null || nameConverter == null || commentProvider == null
                || v8ProjectManager == null)
                return;

            EObject root = scope != null ? scope : module;
            PropertyDescriptions.of(module).clear(scope);
            List<Invocation> candidates = new ArrayList<>();
            for (Invocation invocation : EcoreUtil2.getAllContentsOfType(root, Invocation.class))
                if (insertedKey(invocation) != null)
                    candidates.add(invocation);
            if (candidates.isEmpty())
                return;

            IScope typeScope = scopeProvider.getScope(module,
                McorePackage.Literals.ABSTRACT_METHOD__RET_VAL_TYPE);
            boolean oldFormat = isOldCommentFormat(self, project);
            Pass pass = new Pass(candidates, typeScope, scopeProvider, nameConverter,
                commentProvider, v8ProjectManager, oldFormat);
            runInTransaction(self, project, pass);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Имя ключа, если это {@code X.Вставить("Ключ", …)} / {@code X.Добавить("Ключ", …)}.
     * Разбор литерала — как у EDT в {@code expandStructureType}: кавычки снимаются, остаток
     * прогоняется через {@link BslCommentUtils#trim}.
     */
    private static String insertedKey(Invocation invocation)
    {
        FeatureAccess access = invocation.getMethodAccess();
        if (!(access instanceof DynamicFeatureAccess))
            return null;
        String name = access.getName();
        if (name == null || !KEY_METHODS.contains(name.toLowerCase(Locale.ROOT)))
            return null;
        List<Expression> params = invocation.getParams();
        if (params.isEmpty() || !(params.get(0) instanceof StringLiteral literal)
            || literal.getLines().size() != 1)
            return null;
        String line = literal.getLines().get(0);
        if (line == null || line.length() < 2)
            return null;
        Triple<String, Integer, Integer> trimmed =
            BslCommentUtils.trim(line.substring(1, line.length() - 1));
        String key = trimmed != null ? trimmed.getFirst() : null;
        return key == null || key.isEmpty() ? null : key;
    }

    /** Описания из Pass; после перезапуска тип может быть восстановлен EDT раньше нашего прохода. */
    private static String propertyDescription(Property property, EObject context)
    {
        return propertyDescription(property, context, false);
    }

    private static String propertyDescription(Property property, EObject context, boolean projectEnabled)
    {
        if (!(property instanceof DerivedProperty derived)
            || !(derived.getSource() instanceof BslDerivedPropertySource source)
            || source.getModuleUri() == null
            || (!projectEnabled && !BslDocCommentComputedTypes.isExtendedTypesEnabled(context)))
            return null;
        PropertyDescriptions descriptions = PropertyDescriptions.forModuleUri(URI.createURI(source.getModuleUri()));
        if (!descriptions.contains(property))
        {
            return readPropertySourceDescription(property, source, context);
        }
        return descriptions.get(property);
    }

    /** Холодный индекс: строго по сохранённому месту создания, тем же чтением строки, что в Pass. */
    private static String readPropertySourceDescription(Property property, BslDerivedPropertySource source,
        EObject context)
    {
        Module module = moduleFromSourceUri(URI.createURI(source.getModuleUri()), context);
        if (module == null || !BslDocCommentComputedTypes.isExtendedTypesEnabled(module))
            return null;
        // Внутри разбора комментария (commentFieldDescription) наш же хук возврата собирает
        // секцию и спрашивает описания снова — не входим повторно и не кэшируем пустое.
        if (COMMENT_FIELD_PARSE.get() != null)
            return null;
        // Источник есть и у полей документирующего комментария: там смещение указывает
        // на комментарий, а не на Вставить/Добавить. Пустой результат тоже сохраняем.
        // Кэш привязан к исходному Resource и сбрасывается Xtext при его изменении.
        String key = "tormozit.bslPropertySourceDescription\n" + PropertyDescriptions.key(property); //$NON-NLS-1$
        return PROPERTY_SOURCE_DESCRIPTIONS.get(key, module.eResource(),
            () -> readPropertySourceDescription(property, source, module));
    }

    private static final OnChangeEvictingCache PROPERTY_SOURCE_DESCRIPTIONS = new OnChangeEvictingCache();

    /**
     * Функции, пройденные {@link #originalPropertyDescription}: исходное свойство само может вести в другую
     * функцию, а «см.» двух функций друг на друга замкнули бы обход. Живёт на время одного внешнего вызова.
     */
    private static final ThreadLocal<java.util.Set<String>> ORIGINAL_DESCRIPTION_VISITED = new ThreadLocal<>();

    /**
     * Свойство из слитого типа «см. Функция»: место его создания — функция и смещение 0 (поле
     * секции, собранной нашим слиянием), а описание лежит у исходного свойства — в
     * {@code Вставить} внутри другой функции, откуда пришла структура (18.10: «для списка» у
     * «ДобавлятьПанельБыстрыхОтборов2» из «Яйцо» через «см. Новый123…» не показывалось).
     * Берём одноимённое свойство из рассчитанного типа функции с его настоящим местом создания.
     */
    private static String originalPropertyDescription(Property property, Method function, Module module)
    {
        java.util.Set<String> visited = ORIGINAL_DESCRIPTION_VISITED.get();
        boolean owner = visited == null;
        if (owner)
        {
            visited = new java.util.HashSet<>();
            ORIGINAL_DESCRIPTION_VISITED.set(visited);
        }
        try
        {
            if (!visited.add(functionKey(function, module)))
                return null;
            for (OriginalProperty original : originalProperties(property, function, module))
            {
                String description = readPropertySourceDescription(original.property(), original.source(),
                    original.module());
                if (description != null)
                    return description;
            }
            return null;
        }
        finally
        {
            if (owner)
                ORIGINAL_DESCRIPTION_VISITED.remove();
        }
    }

    /** Ключ функции для обхода исходных свойств: модуль + имя без учёта регистра. */
    private static String functionKey(Method function, Module module)
    {
        Resource resource = module.eResource();
        return (resource == null ? "" : resource.getURI().trimFragment().toString()) //$NON-NLS-1$
            + '#' + String.valueOf(function.getName()).toLowerCase(java.util.Locale.ROOT);
    }

    /** Исходное свойство слитого типа с его настоящим местом создания. */
    private record OriginalProperty(Property property, BslDerivedPropertySource source, Module module)
    {
    }

    /** Одноимённые свойства из рассчитанного типа функции, см. {@link #originalPropertyDescription}. */
    private static List<OriginalProperty> originalProperties(Property property, Method function, Module module)
    {
        List<?> types = property.getName() == null ? null : BslDocCommentComputedTypes.cachedTypesFor(function);
        if (types == null)
            return List.of();
        List<OriginalProperty> result = new ArrayList<>();
        collectOriginalProperties(property, types, module,
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), result);
        return result;
    }

    /** Вложенные структуры и колонки: TypeSection.fill раскрывает у таблицы свойства строки. */
    private static void collectOriginalProperties(Property property, List<?> types, Module module,
        Set<Type> visited, List<OriginalProperty> result)
    {
        for (Object item : types)
        {
            if (!(item instanceof Type type) || type.eIsProxy() || !visited.add(type))
                continue;
            Type owner = BslDocCommentComputedTypes.fillPropertyOwner(List.of(type), false);
            if (owner == null || owner.getContextDef() == null)
                continue;
            for (Property candidate : owner.getContextDef().allProperties())
            {
                if (!(candidate instanceof DerivedProperty derived)
                    || !(derived.getSource() instanceof BslDerivedPropertySource origin)
                    || origin.getModuleUri() == null)
                    continue;
                if (candidate != property && property.getName().equalsIgnoreCase(candidate.getName()))
                {
                    Module originModule = moduleOf(module, URI.createURI(origin.getModuleUri()).trimFragment());
                    if (originModule != null)
                        result.add(new OriginalProperty(candidate, origin, originModule));
                }
                if (candidate.getTypeContainer() != null)
                    collectOriginalProperties(property, candidate.getTypeContainer().allTypes(), module,
                        visited, result);
            }
        }
    }

    /** Модуль по URI: тот же, что у свойства, или загруженный в его же наборе ресурсов. */
    private static Module moduleOf(Module module, URI uri)
    {
        Resource resource = module.eResource();
        if (resource != null && uri.equals(resource.getURI().trimFragment()))
            return module;
        Resource other = resource == null || resource.getResourceSet() == null ? null
            : resource.getResourceSet().getResource(uri, false);
        return other != null && !other.getContents().isEmpty() && other.getContents().get(0) instanceof Module found
            ? found : null;
    }

    private static String readPropertySourceDescription(Property property, BslDerivedPropertySource source,
        Module module)
    {
        Block block = module;
        if (source.getMethodName() != null && !source.getMethodName().isEmpty())
        {
            block = null;
            for (Method method : module.allMethods())
            {
                if (source.getMethodName().equalsIgnoreCase(method.getName()))
                {
                    block = method;
                    break;
                }
            }
        }
        ICompositeNode node = block == null ? null : NodeModelUtils.findActualNodeFor(block);
        if (node == null)
        {
            return null;
        }
        int offset = node.getTotalOffset() + source.getLocalOffset();
        if (offset < node.getTotalOffset() || offset >= node.getTotalEndOffset())
        {
            return null;
        }
        var leaf = NodeModelUtils.findLeafNodeAtOffset(node, offset);
        EObject semantic = leaf == null ? null : NodeModelUtils.findActualSemanticObjectFor(leaf);
        Invocation invocation = EcoreUtil2.getContainerOfType(semantic, Invocation.class);
        if (invocation == null && block instanceof Method method)
        {
            // Свойство по полю документирующего комментария: «* Поле - Тип - описание».
            String description = commentFieldDescription(property, source, method, module);
            if (description == null)
                description = originalPropertyDescription(property, method, module);
            PropertyDescriptions.forModuleUri(URI.createURI(source.getModuleUri())).put(property, description);
            return description;
        }
        if (invocation == null || !property.getName().equalsIgnoreCase(insertedKey(invocation)))
        {
            return null;
        }
        Statement statement = EcoreUtil2.getContainerOfType(invocation, Statement.class);
        List<String> lines = sideCommentLines(statement);
        String description = sideCommentDescription(lines);
        PropertyDescriptions.forModuleUri(URI.createURI(source.getModuleUri())).put(property, description);
        return description;
    }

    /**
     * Описание поля документирующего комментария метода, по которому EDT создал свойство.
     * Комментарий разбирает сам EDT ({@code BslCommentUtils.parseTemplateComment}); поле
     * узнаём по месту создания так же, как его считает
     * {@code BslDocumentationComment.createDerivedPropertySource}: смещение имени поля +
     * смещение его строки. Поля ищутся в параметрах и в возвращаемом значении, на всю глубину.
     */
    private static String commentFieldDescription(Property property, BslDerivedPropertySource source,
        Method method, Module module)
    {
        COMMENT_FIELD_PARSE.set(Boolean.TRUE);
        try
        {
            return commentFieldDescriptionImpl(property, source, method, module);
        }
        catch (Throwable ignored)
        {
            return null;
        }
        finally
        {
            COMMENT_FIELD_PARSE.remove();
        }
    }

    private static final ThreadLocal<Boolean> COMMENT_FIELD_PARSE = new ThreadLocal<>();

    private static String commentFieldDescriptionImpl(Property property, BslDerivedPropertySource source,
        Method method, Module module)
    {
        TypeSection.FieldDefinition field = commentField(property, source, method, module);
        return field != null ? fieldDescriptionText(field) : null;
    }

    /** Ссылка «см. …» из типов поля комментария и метод, в области которого её разрешать. */
    record PropertySeeLink(Method method, LinkPart link)
    {
    }

    /**
     * Ссылки «см. …» в типе свойства: из бокового комментария {@code Вставить/Добавить} или из поля
     * документирующего комментария метода, по которому создано свойство (issue 828).
     */
    static List<PropertySeeLink> propertySeeLinks(Property property, EObject context)
    {
        if (!(property instanceof DerivedProperty derived)
            || !(derived.getSource() instanceof BslDerivedPropertySource source)
            || source.getModuleUri() == null || source.getMethodName() == null
            || source.getMethodName().isEmpty()
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(context))
            return List.of();
        Module module = moduleFromSourceUri(URI.createURI(source.getModuleUri()), context);
        if (module == null)
            return List.of();
        COMMENT_FIELD_PARSE.set(Boolean.TRUE);
        try
        {
            List<PropertySeeLink> result = new ArrayList<>();
            collectPropertySeeLinks(property, source, module, new java.util.LinkedHashSet<>(), result,
                new java.util.HashSet<>());
            return result;
        }
        catch (Throwable ignored)
        {
            return List.of();
        }
        finally
        {
            COMMENT_FIELD_PARSE.remove();
        }
    }

    private static void collectPropertySeeLinks(Property property, BslDerivedPropertySource source, Module module,
        java.util.Set<String> seen, List<PropertySeeLink> result, java.util.Set<String> visited)
    {
        Method method = methodNamed(module, source.getMethodName());
        if (method == null)
            return;
        // Как readPropertySourceDescription: вызов в месте создания → боковой комментарий,
        // нет вызова → поле документирующего комментария метода.
        if (invocationAtSource(source, method) != null)
        {
            collectReturnSeeLinks(sideCommentReturnSection(property, source, method, module), method, seen, result);
            return;
        }
        TypeSection.FieldDefinition field = commentField(property, source, method, module);
        int before = result.size();
        if (field != null)
        {
            collectSeeLinks(field.getDescription(), method, seen, result);
            for (TypeSection section : field.getTypeSections())
                collectSeeLinks(section, method, seen, result);
            if (result.size() > before)
                return;
        }
        // Ни вызова, ни ссылок в поле: свойство из слитого типа «см. Функция» — ссылки у исходного
        // свойства, как в originalPropertyDescription (18.10: без «: Лук()» у «ДобавлятьПанельБыстрыхОтборов2»).
        // Поле собранной слиянием секции «см.» не хранит: после пересчёта метода перед подсказкой
        // свойство приходит оттуда, и конструктор пропадал (18.10 10:41, foundCommentField).
        // Повтор функции — замкнутые друг на друга «см.», дальше не идём.
        if (visited.add(functionKey(method, module)))
            for (OriginalProperty original : originalProperties(property, method, module))
                collectPropertySeeLinks(original.property(), original.source(), original.module(), seen, result,
                    visited);
    }

    private static Method methodNamed(Module module, String name)
    {
        if (name == null || name.isEmpty())
            return null;
        for (Method candidate : module.allMethods())
            if (name.equalsIgnoreCase(candidate.getName()))
                return candidate;
        return null;
    }

    private static void collectSeeLinks(TypeSection section, Method method, java.util.Set<String> seen,
        List<PropertySeeLink> result)
    {
        // EDT кладёт «см. …» то в исходное, то в текущее описание раздела типов.
        collectSeeLinks(section.getSourceDescription(), method, seen, result);
        collectSeeLinks(section.getCurrentDescription(), method, seen, result);
        collectSeeLinks(section.getSourceExtensionDescription(), method, seen, result);
        // «Тип - см. Конструктор»: ссылка на состав лежит в самом определении типа.
        for (var definition : section.getTypeDefinitions())
            collectSeeLinks(definition, method, seen, result);
    }

    private static void collectSeeLinks(TypeSection.TypeDefinition definition, Method method,
        java.util.Set<String> seen, List<PropertySeeLink> result)
    {
        LinkPart link = definition.getLinkToExtensionFields();
        if (link != null && seen.add(link.getLinkText()))
            result.add(new PropertySeeLink(method, link));
        for (var nested : definition.getContainTypes())
            collectSeeLinks(nested, method, seen, result);
    }

    private static void collectSeeLinks(BslDocumentationComment.Description description, Method method,
        java.util.Set<String> seen, List<PropertySeeLink> result)
    {
        if (description != null)
            for (Object part : description.getParts())
                if (part instanceof LinkPart link && seen.add(link.getLinkText()))
                    result.add(new PropertySeeLink(method, link));
    }

    /** Вызов {@code Вставить/Добавить} в месте создания свойства — то же чтение, что в описании свойства. */
    private static Invocation invocationAtSource(BslDerivedPropertySource source, Method method)
    {
        ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
        if (node == null)
            return null;
        int offset = node.getTotalOffset() + source.getLocalOffset();
        if (offset < node.getTotalOffset() || offset >= node.getTotalEndOffset())
            return null;
        var leaf = NodeModelUtils.findLeafNodeAtOffset(node, offset);
        EObject semantic = leaf == null ? null : NodeModelUtils.findActualSemanticObjectFor(leaf);
        return EcoreUtil2.getContainerOfType(semantic, Invocation.class);
    }

    /**
     * Раздел возвращаемого значения бокового комментария {@code Структура.Вставить("Ключ", …); // см. Конструктор - …}.
     * Комментарий разбирает сам EDT тем же путём, что и тип свойства ({@code TypeSystemUtil.computeCommentTypes}:
     * {@code getCommentAfterObject} по узлу блока + {@code parseTemplateComment} в формате проекта);
     * вызов ищется по месту создания свойства так же, как в {@link #readPropertySourceDescription}.
     */
    private static BslDocumentationComment.ReturnSection sideCommentReturnSection(Property property,
        BslDerivedPropertySource source, Method method, Module module)
    {
        Invocation invocation = invocationAtSource(source, method);
        if (invocation == null || !property.getName().equalsIgnoreCase(insertedKey(invocation)))
            return null;
        Statement statement = EcoreUtil2.getContainerOfType(invocation, Statement.class);
        Block block = statement == null ? null : EcoreUtil2.getContainerOfType(statement, Block.class);
        ICompositeNode blockNode = block == null ? null : NodeModelUtils.findActualNodeFor(block);
        if (blockNode == null)
            return null;
        boolean oldFormat = oldCommentFormat(module);
        List<String> lines = TypeSystemUtil.getCommentAfterObject(statement, blockNode, oldFormat);
        BslDocumentationComment comment = lines == null || lines.isEmpty() ? null
            : BslCommentUtils.parseTemplateComment(lines, oldFormat);
        return comment == null ? null : comment.getReturnSection();
    }

    /**
     * Ссылки «см. …» раздела возвращаемого значения — как {@code BslDocumentationComment.computeReturnTypes}:
     * типы из разделов типов, а если их нет — из ссылок в описании самого раздела
     * ({@code // см. Конструктор - описание} разбирается именно так).
     */
    private static void collectReturnSeeLinks(BslDocumentationComment.ReturnSection section, Method method,
        java.util.Set<String> seen, List<PropertySeeLink> result)
    {
        if (section == null)
            return;
        for (TypeSection type : section.getReturnTypes())
            collectSeeLinks(type, method, seen, result);
        if (section.getReturnTypes().isEmpty())
            collectSeeLinks(section.getDescription(), method, seen, result);
    }

    /** Ссылки «см. …» в типах параметра метода, на который указывает слово под указателем (issue 828). */
    static List<PropertySeeLink> parameterSeeLinksAt(ITextViewer viewer, int offset)
    {
        if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return List.of();
        try
        {
            List<PropertySeeLink> links = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
                EObject target = new org.eclipse.xtext.resource.EObjectAtOffsetHelper().resolveElementAt(resource, offset);
                if (target instanceof com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess access)
                {
                    // Использование параметра в теле: ссылка — в записях обращения.
                    target = null;
                    for (var entry : access.getFeatureEntries())
                        if (entry != null && entry.getFeature() instanceof com._1c.g5.v8.dt.bsl.model.FormalParam)
                        {
                            target = entry.getFeature();
                            break;
                        }
                }
                return parameterSeeLinks(target);
            });
            return links != null ? links : List.of();
        }
        catch (Throwable ignored)
        {
            return List.of();
        }
    }

    /** Ссылки «см. …» в типах формального параметра из комментария его метода. */
    private static List<PropertySeeLink> parameterSeeLinks(EObject target)
    {
        if (!(target instanceof com._1c.g5.v8.dt.bsl.model.FormalParam param)
            || !(param.eContainer() instanceof Method method)
            || !(method.eContainer() instanceof Module module))
            return List.of();
        BslDocumentationComment comment = parseMethodComment(method, module);
        var definition = comment == null || comment.getParametersSection() == null ? null
            : comment.getParametersSection().getParameterByName(param.getName());
        if (definition == null)
            return List.of();
        List<PropertySeeLink> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (TypeSection section : definition.getTypeSections())
            collectSeeLinks(section, method, seen, result);
        return result;
    }

    static BslDocumentationComment parseMethodComment(Method method, Module module)
    {
        Resource resource = module.eResource();
        IResourceServiceProvider rsp = resource == null || resource.getURI() == null ? null
            : IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(resource.getURI());
        if (rsp == null)
            return null;
        BslMultiLineCommentDocumentationProvider commentProvider =
            rsp.get(BslMultiLineCommentDocumentationProvider.class);
        if (commentProvider == null)
            return null;
        return BslCommentUtils.parseTemplateComment(method, oldCommentFormat(module), commentProvider);
    }

    /** Флажок «старый формат комментария» проекта модуля, как его читает {@code BslTreeTypeSystem}. */
    private static boolean oldCommentFormat(Module module)
    {
        Resource resource = module.eResource();
        IResourceServiceProvider rsp = resource == null || resource.getURI() == null ? null
            : IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(resource.getURI());
        IProject project = projectOf(module);
        if (rsp == null || project == null)
            return false;
        try
        {
            BslTreeTypeSystem typeSystem = rsp.get(BslTreeTypeSystem.class);
            return typeSystem != null && isOldCommentFormat(typeSystem, project);
        }
        catch (RuntimeException ignored)
        {
            // Нет привязки — формат по умолчанию, как при ошибке чтения флажка.
            return false;
        }
    }

    private static TypeSection.FieldDefinition commentField(Property property, BslDerivedPropertySource source,
        Method method, Module module)
    {
        BslDocumentationComment comment = parseMethodComment(method, module);
        if (comment == null)
            return null;
        int[] lineOffsets = comment.getLineGlobalOffsets();
        List<TypeSection.FieldDefinition> roots = new ArrayList<>();
        if (comment.getParametersSection() != null)
            roots.addAll(comment.getParametersSection().getParameterDefinitions());
        List<TypeSection> sections = new ArrayList<>();
        for (TypeSection.FieldDefinition parameter : roots)
            sections.addAll(parameter.getTypeSections());
        if (comment.getReturnSection() != null)
            sections.addAll(comment.getReturnSection().getReturnTypes());
        for (TypeSection section : sections)
        {
            TypeSection.FieldDefinition field = findCommentField(section, property.getName(),
                source.getLocalOffset(), lineOffsets);
            if (field != null)
                return field;
        }
        return null;
    }

    private static TypeSection.FieldDefinition findCommentField(TypeSection section, String name, int localOffset,
        int[] lineOffsets)
    {
        for (var definition : section.getTypeDefinitions())
        {
            for (TypeSection.FieldDefinition field : definition.getFieldDefinitionExtension())
            {
                int line = field.getLineNumber();
                int lineOffset = lineOffsets != null && line >= 0 && line < lineOffsets.length
                    ? lineOffsets[line] : 0;
                if (name.equalsIgnoreCase(field.getName()) && field.getNameOffset() + lineOffset == localOffset)
                    return field;
                for (TypeSection nested : field.getTypeSections())
                {
                    TypeSection.FieldDefinition found = findCommentField(nested, name, localOffset, lineOffsets);
                    if (found != null)
                        return found;
                }
            }
        }
        return null;
    }

    /**
     * В «* Поле - Тип - описание» EDT кладёт описание не в поле, а в раздел его типов
     * ({@code TypeSection.getCurrentDescription}); в поле — только у «* Поле - описание»
     * без типа.
     */
    private static String fieldDescriptionText(TypeSection.FieldDefinition field)
    {
        String text = descriptionText(field.getDescription());
        if (text != null)
            return text;
        for (TypeSection section : field.getTypeSections())
        {
            text = descriptionText(section.getCurrentDescription());
            if (text == null)
                text = sourceDescriptionText(section.getSourceDescription());
            if (text != null)
                return text;
        }
        return null;
    }

    /**
     * Исходный текст раздела типов без ссылок «см. …»: ссылка — это сам тип, она уже стоит
     * в заголовке подсказки конструктором «: Функция()» (issue 828). Остаток без букв и цифр
     * (тире, скобка) описанием не считается.
     */
    private static String sourceDescriptionText(BslDocumentationComment.Description description)
    {
        if (description == null)
            return null;
        StringBuilder text = new StringBuilder();
        for (Object part : description.getParts())
        {
            String piece = part instanceof TextPart textPart ? textPart.getText()
                : part instanceof TagPart tag ? tag.getContent()
                : null;
            if (piece == null || piece.isBlank())
                continue;
            if (text.length() > 0)
                text.append(' ');
            text.append(piece.trim());
        }
        return text.codePoints().anyMatch(Character::isLetterOrDigit) ? text.toString() : null;
    }

    /** Текст описания поля: текст, ссылки «см. …» и теги — как написаны в комментарии. */
    private static String descriptionText(BslDocumentationComment.Description description)
    {
        if (description == null)
            return null;
        StringBuilder text = new StringBuilder();
        for (Object part : description.getParts())
        {
            String piece = part instanceof TextPart textPart ? textPart.getText()
                : part instanceof LinkPart link ? link.getInitialContent()
                : part instanceof TagPart tag ? tag.getContent()
                : null;
            if (piece == null || piece.isBlank())
                continue;
            if (text.length() > 0)
                text.append(' ');
            text.append(piece.trim());
        }
        return text.length() == 0 ? null : text.toString();
    }

    private static List<String> sideCommentLines(Statement statement)
    {
        Block block = statement == null ? null : EcoreUtil2.getContainerOfType(statement, Block.class);
        ICompositeNode node = block == null ? null : NodeModelUtils.findActualNodeFor(block);
        return node == null ? null : TypeSystemUtil.getCommentAfterObject(statement, node, false);
    }

    /** Штатные предложения Xtext, включая режим без «Улучшать списки». */
    static void installPropertyProposalDescriptions(ICompletionProposal[] proposals, ITextViewer viewer)
    {
        if (proposals == null)
            return;
        java.util.Map<IEObjectHover, PropertyDescriptionHover> wrappers = new java.util.IdentityHashMap<>();
        for (ICompletionProposal proposal : proposals)
        {
            ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
            if (!(raw instanceof ConfigurableCompletionProposal configurable))
                continue;
            Object hover = Global.getField(configurable, "hover"); //$NON-NLS-1$
            if (!(hover instanceof IEObjectHover original) || hover instanceof PropertyDescriptionHover)
                continue;
            configurable.setHover(wrappers.computeIfAbsent(original,
                hoverProvider -> new PropertyDescriptionHover(hoverProvider, viewer)));
        }
    }

    private static final class PropertyDescriptionHover implements IEObjectHover, ITextHoverExtension
    {
        private final IEObjectHover delegate;
        private final ITextViewer proposalViewer;

        PropertyDescriptionHover(IEObjectHover delegate, ITextViewer proposalViewer)
        {
            this.delegate = delegate;
            this.proposalViewer = proposalViewer;
        }

        @Override
        public Object getHoverInfo(EObject object, ITextViewer viewer, IRegion region)
        {
            // Provider в ConfigurableCompletionProposal может вернуть EObject только
            // здесь; проверка нужна и после него, до фонового построителя EDT.
            if (!SmartCompletionProposal.isEdtDocumentationObjectReady(object))
                return null;
            Object info = delegate.getHoverInfo(object, viewer, region);
            // У свойства ConfigurableCompletionProposal.viewer == null: EDT задаёт его
            // только в setSimpleLinkedMode. Контекст берём из расчёта списка.
            ITextViewer context = viewer != null ? viewer : proposalViewer;
            completePropertyHover(info, object, context);
            return BslEditorHoverHook.decorateSideHint(info, object, context);
        }

        @Override
        public IInformationControlCreator getHoverControlCreator()
        {
            return delegate instanceof ITextHoverExtension extension
                ? IrBslHoverHtml.wrapCreator(extension.getHoverControlCreator()) : null;
        }
    }

    /** Дополняет именно страницу штатной подсказки; описание уже прочитано в Pass. */
    private static void completePropertyHover(Object info, EObject object, ITextViewer viewer)
    {
        if (object == null || viewer == null
            || !(viewer.getDocument() instanceof IXtextDocument document))
            return;
        try
        {
            // Как и остальные наши расчёты типов — только под флажком проекта.
            if (!Boolean.TRUE.equals(ContentAssistSessionReloader.readOnlyPeekAst(document,
                BslStructureInsertCommentTypes::isExtendedTypesEnabled)))
                return;
            Property property = ContentAssistSessionReloader.readOnlyPeekAst(document,
                resource -> propertyForHint(object, resource));
            if (property == null)
                return;
            String description = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
                resource == null || resource.getContents().isEmpty()
                    ? null : propertyDescription(property, resource.getContents().get(0)));
            Object page = Global.invoke(info, "getViewPage"); //$NON-NLS-1$
            Object value = Global.getField(page, "value"); //$NON-NLS-1$
            Object current = Global.invoke(value, "getDescription"); //$NON-NLS-1$
            if (value == null)
                return;
            String html = current instanceof String text ? text : null;
            if ((html == null || html.isBlank()) && description != null)
                html = description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            Object groups = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
                propertyTypeGroups(property, page, value, resource));
            Object replacement = value.getClass().getConstructor(String.class, List.class).newInstance(html, groups);
            Global.invokeVoid(page, "setValue", replacement); //$NON-NLS-1$
        }
        catch (Throwable failure)
        {
        }
    }

    /** Один выбор свойства для прямого обращения и обёртки предложения автодополнения. */
    static Property propertyForHint(EObject object, Resource resource)
    {
        List<? extends EObject> members = object instanceof com._1c.g5.v8.dt.bsl.model.ProposalElement proposal
            ? proposal.getList() : object == null ? List.of() : List.of(object);
        return propertyForHint(members, resource);
    }

    private static Property propertyForHint(List<? extends EObject> members, Resource resource)
    {
        if (resource == null || !Boolean.TRUE.equals(isExtendedTypesEnabled(resource)))
            return null;
        Property first = null;
        for (EObject member : members)
        {
            if (!(member instanceof Property property))
                continue;
            if (first == null)
                first = property;
            if (propertyDescription(property, resource.getContents().get(0)) != null)
                return property;
        }
        return first;
    }

    /** Как EDT getDocByDerivedProperty, но прокси типов разрешаются в ресурсе редактора. */
    private static Object propertyTypeGroups(Property property, Object page, Object value, Resource resource)
        throws Exception
    {
        Object original = Global.invoke(value, "getGroups"); //$NON-NLS-1$
        if (resource == null || resource.getContents().isEmpty()
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0)))
            return original;
        // У штатного DerivedProperty одна группа. Сохраняем её описание и сноску:
        // это отдельные данные от ValueContent.description.
        if (original instanceof List<?> groups && groups.size() > 1)
            return original;
        Object originalGroup = original instanceof List<?> groups && !groups.isEmpty() ? groups.get(0) : null;
        Object groupDescription = Global.invoke(originalGroup, "getDescription"); //$NON-NLS-1$
        Object groupFootnote = Global.invoke(originalGroup, "getFootnote"); //$NON-NLS-1$
        Object provider = BslSyntaxAssist.documentationProvider();
        Object locale = Global.invoke(page, "getLocale"); //$NON-NLS-1$
        boolean russian = Boolean.TRUE.equals(Global.invoke(locale, "getElementsLanguage")); //$NON-NLS-1$
        Object version = Global.invoke(page, "getVersion"); //$NON-NLS-1$
        List<Object> references = new ArrayList<>();
        for (TypeItem item : property.getTypes())
        {
            TypeItem resolved = (TypeItem)EcoreUtil.resolve(item, resource);
            Object reference = Global.invoke(provider, "convertTypeItemToReference", //$NON-NLS-1$
                resolved, resource, Boolean.valueOf(russian), version, Boolean.FALSE);
            if (reference != null)
                references.add(reference);
        }
        if (references.isEmpty())
            return original;
        ClassLoader loader = value.getClass().getClassLoader();
        Class<?> groupClass = Class.forName(
            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.TypeGroupContent", true, loader); //$NON-NLS-1$
        Class<?> footnoteClass = Class.forName(
            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.Footnote", true, loader); //$NON-NLS-1$
        Object group = groupClass.getConstructor(String.class, List.class, footnoteClass)
            .newInstance(groupDescription, references, groupFootnote);
        return List.of(group);
    }

    /**
     * Свойство под указателем. {@code Структура.Поле} — динамическое обращение: ссылки на
     * свойство в нём нет, и {@code EObjectAtOffsetHelper.resolveCrossReferencedElementAt}
     * даёт {@code null}. Обращение с именем под указателем разрешается, как
     * в штатной подсказке: {@code DynamicFeatureAccessComputer.resolveDynamicFeatureAccess(…, true)}.
     * Из нескольких одноимённых свойств (несколько типов) — первое, у которого есть описание.
     */
    private static Property propertyAt(Resource resource, int offset)
    {
        if (!(resource instanceof org.eclipse.xtext.resource.XtextResource xtext))
            return null;
        EObject object = new org.eclipse.xtext.resource.EObjectAtOffsetHelper().resolveContainedElementAt(xtext, offset);
        FeatureAccess access = null;
        for (EObject current = object; current != null && access == null; current = current.eContainer())
        {
            if (!(current instanceof FeatureAccess candidate))
                continue;
            for (org.eclipse.xtext.nodemodel.INode node : NodeModelUtils.findNodesForFeature(candidate,
                com._1c.g5.v8.dt.bsl.model.BslPackage.Literals.FEATURE_ACCESS__NAME))
                if (offset >= node.getOffset() && offset < node.getEndOffset())
                {
                    access = candidate;
                    break;
                }
        }
        if (access == null)
            return null;
        IResourceServiceProvider rsp = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(resource.getURI());
        DynamicFeatureAccessComputer computer = rsp == null ? null : rsp.get(DynamicFeatureAccessComputer.class);
        List<FeatureEntry> entries = access instanceof StaticFeatureAccess staticAccess
            ? staticAccess.getFeatureEntries()
            // Флажок — как в штатной подсказке (BslDocumentationProvider.getHoverPageGroupByExpression):
            // без него у параметра с типом из «см. Функция» свойство не находится.
            : computer == null ? List.of()
                : DynamicFeatureAccessComputer.resolveDynamicFeatureAccess(access, computer, true);
        return propertyForHint(entries.stream().map(FeatureEntry::getFeature).toList(), resource);
    }

    /**
     * Место создания свойства под указателем: ключ-литерал в {@code X.Вставить("Ключ", …)}
     * или {@code Новый Структура("Ключ", …)}, поле комментария, иначе метод,
     * по которому свойство создано. {@code null} — места нет.
     */
    static PropertySourceTarget propertySourceUriAt(Resource resource, int offset)
    {
        Property property = propertyAt(resource, offset);
        return propertySourceUriFor(property, resource);
    }

    /** Тот же переход для имени в боковой подсказке списка и в подсказке при наведении. */
    static PropertySourceTarget propertySourceUriFor(Property property, Resource resource)
    {
        if (resource == null || !(property instanceof DerivedProperty derived)
            || !(derived.getSource() instanceof BslDerivedPropertySource source)
            || source.getModuleUri() == null || resource.getContents().isEmpty())
            return null;
        Module module = moduleFromSourceUri(URI.createURI(source.getModuleUri()), resource.getContents().get(0));
        Method method = module == null ? null : methodNamed(module, source.getMethodName());
        if (method == null)
            return null;
        COMMENT_FIELD_PARSE.set(Boolean.TRUE);
        try
        {
            PropertySourceTarget exact = exactSourceTarget(property, source, module, new java.util.HashSet<>());
            return exact != null ? exact : new PropertySourceTarget(EcoreUtil.getURI(method), -1, 0);
        }
        catch (Throwable ignored)
        {
            return new PropertySourceTarget(EcoreUtil.getURI(method), -1, 0);
        }
        finally
        {
            COMMENT_FIELD_PARSE.remove();
        }
    }

    /**
     * Точное место: ключ в {@code Вставить} или литерале конструктора, иначе поле комментария (имя поля — по
     * смещению места создания). Свойство из слитого типа «см. Функция» — у исходного свойства, как в
     * {@link #collectPropertySeeLinks}. {@code null} — точного места нет.
     */
    private static PropertySourceTarget exactSourceTarget(Property property, BslDerivedPropertySource source,
        Module module, java.util.Set<String> visited)
    {
        Method method = methodNamed(module, source.getMethodName());
        if (method == null || property.getName() == null)
            return null;
        PropertySourceTarget queryField = BslQueryResultTypes.sourceTarget(property, source, module);
        if (queryField != null)
            return queryField;
        Invocation invocation = invocationAtSource(source, method);
        if (invocation != null && property.getName().equalsIgnoreCase(insertedKey(invocation)))
            return new PropertySourceTarget(EcoreUtil.getURI(invocation.getParams().get(0)), -1, 0);
        ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
        if (node != null)
        {
            // EDT createDerivedPropertySource сохраняет смещение самого ключа внутри
            // строкового литерала, в том числе у Новый Структура("Первый,Второй").
            // Проверяем актуальный текст: после переименования старый адрес не годится.
            int keyOffset = node.getTotalOffset() + source.getLocalOffset();
            if (keyOffset >= node.getTotalOffset() && keyOffset < node.getTotalEndOffset())
            {
                var leaf = NodeModelUtils.findLeafNodeAtOffset(node, keyOffset);
                EObject semantic = leaf == null ? null : NodeModelUtils.findActualSemanticObjectFor(leaf);
                StringLiteral literal = EcoreUtil2.getContainerOfType(semantic, StringLiteral.class);
                if (literal != null && leaf != null)
                {
                    String text = leaf.getText();
                    int start = keyOffset - leaf.getOffset();
                    int end = start + property.getName().length();
                    if (start > 0 && end < text.length()
                        && text.regionMatches(true, start, property.getName(), 0, property.getName().length())
                        && (text.charAt(start - 1) == '"' || text.charAt(start - 1) == ','
                            || Character.isWhitespace(text.charAt(start - 1)))
                        && (text.charAt(end) == '"' || text.charAt(end) == ','
                            || Character.isWhitespace(text.charAt(end))))
                        return new PropertySourceTarget(EcoreUtil.getURI(literal), keyOffset,
                            property.getName().length());
                }
            }
        }
        if (node != null && commentField(property, source, method, module) != null)
            return new PropertySourceTarget(EcoreUtil.getURI(method), node.getTotalOffset() + source.getLocalOffset(),
                property.getName().length());
        if (visited.add(functionKey(method, module)))
            for (OriginalProperty original : originalProperties(property, method, module))
            {
                PropertySourceTarget found = exactSourceTarget(original.property(), original.source(),
                    original.module(), visited);
                if (found != null)
                    return found;
            }
        return null;
    }

    /** Общее открытие места создания свойства из команды и обеих подсказок. */
    static void openPropertySource(PropertySourceTarget target)
    {
        IResourceServiceProvider provider = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(target.uri().trimFragment());
        org.eclipse.xtext.ui.editor.IURIEditorOpener opener = provider == null ? null
            : provider.get(org.eclipse.xtext.ui.editor.IURIEditorOpener.class);
        if (opener == null)
            return;
        if (target.offset() < 0)
        {
            opener.open(target.uri(), true);
            return;
        }
        // Без выделения метода: иначе в историю попадёт и его имя, хотя переход — на свойство.
        org.eclipse.ui.IEditorPart part = opener.open(target.uri(), false);
        if (part instanceof org.eclipse.ui.texteditor.ITextEditor textEditor)
            textEditor.selectAndReveal(target.offset(), target.length());
    }

    /** Куда вести ссылку на имя свойства; {@code offset < 0} — выделить сам объект по URI. */
    record PropertySourceTarget(URI uri, int offset, int length)
    {
    }

    /** Флажок «Расширенный расчёт типов» проекта модуля, открытого в редакторе. */
    private static Boolean isExtendedTypesEnabled(Resource resource)
    {
        return resource != null && !resource.getContents().isEmpty()
            && BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0));
    }

    /**
     * Единая точка для подсказки наведения и боковой подсказки списка (issue 828): ссылки
     * «см. …» в типах свойства или параметра под указателем.
     */
    static List<PropertySeeLink> seeLinksAt(ITextViewer viewer, int offset)
    {
        List<PropertySeeLink> result = new ArrayList<>(propertySeeLinksAt(viewer, offset));
        result.addAll(parameterSeeLinksAt(viewer, offset));
        return result;
    }

    /** То же для члена, известного объектом (предложение списка): свойство или параметр. */
    static List<PropertySeeLink> seeLinksFor(EObject object, ITextViewer viewer)
    {
        if (object == null || viewer == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return List.of();
        try
        {
            List<PropertySeeLink> links = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
                EObject context = resource.getContents().isEmpty() ? null : resource.getContents().get(0);
                // Предложение списка — обёртка: настоящие члены лежат в её списке.
                List<EObject> members = object instanceof com._1c.g5.v8.dt.bsl.model.ProposalElement proposal
                    ? new ArrayList<>(proposal.getList()) : List.of(object);
                for (EObject member : members)
                {
                    // Предложение переменной (параметра) — вхождение-фантом, сама переменная внутри.
                    if (member instanceof com._1c.g5.v8.dt.bsl.model.FantomVariableOccurrence occurrence)
                        member = occurrence.getVariable();
                    List<PropertySeeLink> found = member instanceof Property property
                        ? (context == null ? List.<PropertySeeLink>of() : propertySeeLinks(property, context))
                        : parameterSeeLinks(member);
                    if (!found.isEmpty())
                        return found;
                }
                return List.<PropertySeeLink>of();
            });
            return links != null ? links : List.of();
        }
        catch (Throwable ex)
        {
            return List.of();
        }
    }

    /** Ссылки «см. …» в типах свойства под указателем, см. {@link #propertySeeLinks}. */
    static List<PropertySeeLink> propertySeeLinksAt(ITextViewer viewer, int offset)
    {
        if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return List.of();
        try
        {
            List<PropertySeeLink> links = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
                Property property = Boolean.TRUE.equals(isExtendedTypesEnabled(resource))
                    ? propertyAt(resource, offset) : null;
                return property == null ? List.<PropertySeeLink>of()
                    : propertySeeLinks(property, resource.getContents().get(0));
            });
            return links != null ? links : List.of();
        }
        catch (Throwable ignored)
        {
            return List.of();
        }
    }

    /** Слово под указателем: свойство обращения под указателем, см. {@link #propertyAt}. */
    static void completePropertyHover(Object info, ITextViewer viewer, int offset)
    {
        if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return;
        try
        {
            EObject object = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
                isExtendedTypesEnabled(resource) ? propertyAt(resource, offset) : null);
            completePropertyHover(info, object, viewer);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Ключ — URI модуля и место создания свойства, сохранённое EDT в копиях типов.
     * Расчёт и подсказка читают разные экземпляры Module из разных ResourceSet:
     * адаптер объекта модели между ними не переносится. При новом расчёте область
     * метода/модуля очищается, включая удалённые комментарии.
     */
    private static final class PropertyDescriptions
    {
        private static final java.util.Map<String, PropertyDescriptions> byModuleUri =
            new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, String> values = new java.util.concurrent.ConcurrentHashMap<>();

        static PropertyDescriptions of(Module module)
        {
            return forModuleUri(EcoreUtil.getURI(module));
        }

        static PropertyDescriptions forModuleUri(URI uri)
        {
            return byModuleUri.computeIfAbsent(uri.trimFragment().toString(),
                ignored -> new PropertyDescriptions());
        }

        /** Растёт при каждом изменении любого описания: по нему устаревает {@code FILLED_DESCRIPTIONS}. */
        private static final java.util.concurrent.atomic.AtomicLong version =
            new java.util.concurrent.atomic.AtomicLong();

        static long version()
        {
            return version.get();
        }

        void clear(Method scope)
        {
            boolean removed;
            if (scope == null)
            {
                removed = !values.isEmpty();
                values.clear();
            }
            else
                removed = values.keySet().removeIf(key -> key.startsWith(scope.getName() + "\n")); //$NON-NLS-1$
            if (removed)
                version.incrementAndGet();
        }

        private static String key(Property property)
        {
            if (!(property instanceof DerivedProperty derived)
                || !(derived.getSource() instanceof BslDerivedPropertySource source))
                return null;
            return source.getMethodName() + "\n" + source.getLocalOffset() + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                + property.getName().toLowerCase(Locale.ROOT);
        }

        void put(Property property, String description)
        {
            String key = key(property);
            if (key == null)
                return;
            String value = description == null ? "" : description; //$NON-NLS-1$
            if (!value.equals(values.put(key, value)))
                version.incrementAndGet();
        }

        String get(Property property)
        {
            String key = key(property);
            String result = key == null ? null : values.get(key);
            if (result != null && result.isEmpty())
                result = null;
            return result;
        }

        boolean contains(Property property)
        {
            String key = key(property);
            return key != null && values.containsKey(key);
        }
    }

    /** getCommentAfterObject возвращает ["//Returns:", "// …"], а не только текст строки. */
    private static String sideCommentDescription(List<String> lines)
    {
        if (lines == null)
            return null;
        for (String line : lines)
        {
            String description = sideCommentDescription(line);
            if (description != null)
                return description;
        }
        return null;
    }

    /** Явный разделитель «типы - описание», в том числе «// - описание» без типов. */
    private static String sideCommentDescription(String line)
    {
        String text = line.startsWith("//") ? line.substring(2).trim() : line.trim(); //$NON-NLS-1$
        var delimiter = java.util.regex.Pattern.compile("(?:^|\\s)-(?=\\s|$)").matcher(text); //$NON-NLS-1$
        if (!delimiter.find())
            return null;
        String result = text.substring(delimiter.end()).trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Разложенные описания по ключу функции ({@code BslDocCommentComputedTypes.methodLabel}).
     * EDT разбирает комментарий функции заново для каждого места её вызова — в сборке тысячи
     * раз подряд для одной функции (лог окна ожидания 17.10.2026: 9645 раз за 43 с), а
     * описания для того же списка типов выходят одни и те же.
     */
    private static final java.util.Map<String, FilledDescriptions> FILLED_DESCRIPTIONS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code types} сравнивается по ссылке: это список из кэша типов возврата функции. */
    private record FilledDescriptions(List<?> types, long version, List<FilledDescription> parts)
    {
    }

    /**
     * Путь от корневой секции: тройки «определение, поле, вложенная секция», последняя —
     * без секции.
     */
    private record FilledDescription(int[] path, String text)
    {
    }

    /**
     * {@link #collectPropertyDescriptions(TypeSection, List, EObject, int[], List)} с повтором
     * разложенного результата. Повтор годен, пока тот же список типов и не менялось ни одно
     * описание ({@link PropertyDescriptions#version()}); структуру секции при тех же типах
     * штатный {@code TypeSection.fill} строит ту же.
     *
     * @return {@code true}, если описания взяты из кэша
     */
    static boolean fillPropertyDescriptions(Object section, List<?> types, List<?> cacheTypes,
        String cacheKey, EObject context)
    {
        if (!(section instanceof TypeSection typeSection))
            return false;
        long version = PropertyDescriptions.version();
        FilledDescriptions cached = cacheKey == null ? null : FILLED_DESCRIPTIONS.get(cacheKey);
        if (cached != null && cached.types() == cacheTypes && cached.version() == version)
        {
            for (FilledDescription part : cached.parts())
                applyDescription(typeSection, part);
            return true;
        }
        List<FilledDescription> parts = new ArrayList<>();
        collectPropertyDescriptions(typeSection, types, context, new int[0], parts);
        if (cacheKey != null)
            FILLED_DESCRIPTIONS.put(cacheKey, new FilledDescriptions(cacheTypes, version, parts));
        return false;
    }

    private static void applyDescription(TypeSection section, FilledDescription part)
    {
        TypeSection.FieldDefinition field = fieldAt(section, part.path());
        if (field != null && field.getDescription().getParts().isEmpty())
            field.getDescription().addPart(new TextPart(field.getDescription(),
                field.getLineNumber(), part.text()));
    }

    /** Поле по пути из {@link #childPath}; {@code null}, если секция собрана иначе. */
    private static TypeSection.FieldDefinition fieldAt(TypeSection section, int[] path)
    {
        TypeSection current = section;
        for (int i = 0; i + 1 < path.length; i += 3)
        {
            var definitions = current.getTypeDefinitions();
            if (path[i] >= definitions.size())
                return null;
            var fields = definitions.get(path[i]).getFieldDefinitionExtension();
            if (path[i + 1] >= fields.size())
                return null;
            var field = fields.get(path[i + 1]);
            if (i + 2 >= path.length)
                return field;
            var nested = field.getTypeSections();
            if (path[i + 2] >= nested.size())
                return null;
            current = nested.get(path[i + 2]);
        }
        return null;
    }

    /**
     * Место создания полей секции результата по ключу функции; повтор годен для того же
     * списка типов, как у {@link #FILLED_DESCRIPTIONS}.
     */
    private static final java.util.Map<String, FilledSources> FILLED_SOURCES =
        new java.util.concurrent.ConcurrentHashMap<>();

    private record FilledSources(List<?> types, List<FilledSource> parts)
    {
    }

    /** {@code localOffset} — смещение вызова {@code Вставить} от начала метода, как у EDT. */
    private record FilledSource(int[] path, int localOffset)
    {
    }

    /**
     * Ставит полям секции результата, собранной штатным {@code TypeSection.fill}, место
     * создания свойства — вызов {@code Вставить}/{@code Добавить}.
     * <p>
     * Свойство по полю комментария EDT создаёт в
     * {@code BslDocumentationComment.createDerivedPropertySource}: смещение = смещение имени
     * поля + смещение его строки комментария ({@code getLineGlobalOffsets}). У собранной
     * секции строк в тексте нет, смещение выходит 0 — начало метода, и подсказка не находит
     * описание из бокового комментария вставки. Ставится, только если вставка в том же
     * методе и модуле, что и комментарий: иначе смещение относилось бы к чужому методу.
     */
    static void alignFieldSources(Object section, Object comment, List<?> types, List<?> cacheTypes,
        String cacheKey)
    {
        if (!(section instanceof TypeSection typeSection)
            || !(comment instanceof BslDocumentationComment documentation))
            return;
        FilledSources cached = cacheKey == null ? null : FILLED_SOURCES.get(cacheKey);
        List<FilledSource> parts;
        if (cached != null && cached.types() == cacheTypes)
            parts = cached.parts();
        else
        {
            parts = new ArrayList<>();
            // Своих геттеров у этих полей нет; это те же поля, что EDT кладёт в источник.
            Object methodName = Global.getField(documentation, "methodName"); //$NON-NLS-1$
            Object moduleUri = Global.getField(documentation, "moduleURI"); //$NON-NLS-1$
            if (methodName instanceof String name && !name.isEmpty() && moduleUri instanceof URI uri)
                collectFieldSources(typeSection, types, name, uri.trimFragment(), new int[0], parts);
            if (cacheKey != null)
                FILLED_SOURCES.put(cacheKey, new FilledSources(cacheTypes, parts));
        }
        int[] lineOffsets = documentation.getLineGlobalOffsets();
        for (FilledSource part : parts)
        {
            TypeSection.FieldDefinition field = fieldAt(typeSection, part.path());
            if (field == null)
                continue;
            int line = field.getLineNumber();
            int lineOffset = lineOffsets != null && line >= 0 && line < lineOffsets.length
                ? lineOffsets[line] : 0;
            field.setNameOffset(part.localOffset() - lineOffset);
        }
    }

    private static void collectFieldSources(TypeSection typeSection, List<?> types, String methodName,
        URI moduleUri, int[] path, List<FilledSource> filled)
    {
        var definitions = typeSection.getTypeDefinitions();
        for (int definitionIndex = 0; definitionIndex < definitions.size(); definitionIndex++)
        {
            var definition = definitions.get(definitionIndex);
            if (definition.getTypeName() == null)
                continue;
            java.util.Map<String, List<Property>> properties = propertiesByName(definition.getTypeName(), types);
            var fields = definition.getFieldDefinitionExtension();
            for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++)
            {
                var field = fields.get(fieldIndex);
                List<Property> matching = properties.get(field.getName());
                if (matching == null)
                    continue;
                int[] fieldPath = childPath(path, definitionIndex, fieldIndex);
                for (Property property : matching)
                {
                    if (property instanceof DerivedProperty derived
                        && derived.getSource() instanceof BslDerivedPropertySource source
                        && methodName.equalsIgnoreCase(source.getMethodName())
                        && source.getModuleUri() != null
                        && moduleUri.equals(URI.createURI(source.getModuleUri()).trimFragment())
                        && filled.stream().noneMatch(part -> java.util.Arrays.equals(part.path(), fieldPath)))
                        filled.add(new FilledSource(fieldPath, source.getLocalOffset()));
                    List<TypeItem> nestedTypes = property.getTypeContainer() instanceof TypeContainerRef ref
                        ? ref.getTypes() : property.getTypes();
                    var nestedSections = field.getTypeSections();
                    for (int sectionIndex = 0; sectionIndex < nestedSections.size(); sectionIndex++)
                        collectFieldSources(nestedSections.get(sectionIndex), nestedTypes, methodName, moduleUri,
                            childPath(path, definitionIndex, fieldIndex, sectionIndex), filled);
                }
            }
        }
    }

    /** Свойства контекстных типов, чьё имя совпадает с именем определения типа секции. */
    private static java.util.Map<String, List<Property>> propertiesByName(String typeName, List<?> types)
    {
        java.util.Map<String, List<Property>> properties =
            new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Object item : types)
            if (item instanceof Type type && !type.eIsProxy()
                && (typeName.equals(McoreUtil.getTypeName(type))
                    || typeName.equals(McoreUtil.getTypeNameRu(type))))
            {
                Type owner = BslDocCommentComputedTypes.fillPropertyOwner(List.of(type), false);
                if (owner == null || owner.getContextDef() == null)
                    continue;
                for (Property property : owner.getContextDef().allProperties())
                {
                    String name = property.getName();
                    if (name != null)
                        properties.computeIfAbsent(name, ignored -> new ArrayList<>()).add(property);
                }
            }
        return properties;
    }

    private static int[] childPath(int[] path, int... tail)
    {
        int[] result = java.util.Arrays.copyOf(path, path.length + tail.length);
        System.arraycopy(tail, 0, result, path.length, tail.length);
        return result;
    }

    /** Переносит описания в поля секции результата, которую собрал штатный TypeSection.fill. */
    private static void collectPropertyDescriptions(TypeSection typeSection, List<?> types, EObject context,
        int[] path, List<FilledDescription> filled)
    {
        var definitions = typeSection.getTypeDefinitions();
        for (int definitionIndex = 0; definitionIndex < definitions.size(); definitionIndex++)
        {
            var definition = definitions.get(definitionIndex);
            if (definition.getTypeName() == null)
                continue;
            java.util.Map<String, List<Property>> properties = propertiesByName(definition.getTypeName(), types);
            var fields = definition.getFieldDefinitionExtension();
            for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++)
            {
                var field = fields.get(fieldIndex);
                List<Property> matching = properties.get(field.getName());
                if (matching == null)
                    continue;
                // Сохраняем все совпадения и их порядок: одноимённые свойства могут
                // приходить из нескольких контекстных типов и давать разные вложенные типы.
                for (Property property : matching)
                {
                    if (field.getDescription().getParts().isEmpty())
                    {
                        // Вход из fillReturnSection уже проверил флажок проекта. Не ищем
                        // тот же проект заново для каждого поля и каждой вложенной секции.
                        String description = propertyDescription(property, context, true);
                        if (description != null)
                        {
                            field.getDescription().addPart(new TextPart(field.getDescription(),
                                field.getLineNumber(), description));
                            filled.add(new FilledDescription(
                                childPath(path, definitionIndex, fieldIndex), description));
                        }
                    }
                    List<TypeItem> nestedTypes = property.getTypeContainer() instanceof TypeContainerRef ref
                        ? ref.getTypes() : property.getTypes();
                    var nestedSections = field.getTypeSections();
                    for (int sectionIndex = 0; sectionIndex < nestedSections.size(); sectionIndex++)
                        collectPropertyDescriptions(nestedSections.get(sectionIndex), nestedTypes, context,
                            childPath(path, definitionIndex, fieldIndex, sectionIndex), filled);
                }
            }
        }
    }

    /**
     * Дописывает типы комментария в свойство, созданное EDT для этого ключа. Ищем среди типов
     * переменной-источника сразу после вызова: именно там лежит структура (или таблица
     * значений) с новым свойством.
     */
    private static void fillProperty(Invocation invocation, String key,
        Collection<TypeItem> commentTypes, String description)
    {
        Variable variable = sourceVariable(
            ((DynamicFeatureAccess)invocation.getMethodAccess()).getSource());
        if (variable == null)
            return;
        Environmental environmental =
            EcoreUtil2.getContainerOfType(invocation, Environmental.class);
        ICompositeNode node = NodeModelUtils.findActualNodeFor(invocation);
        if (environmental == null || node == null)
            return;
        // Смещение — конец вызова: именно с него EDT заводит состояние с расширенной
        // структурой (CreatorTreeState.checkForExpandingStructureTypeContextDef).
        List<TypeItem> types = TypeSystemUtil.getVariableTypesAtOffset(variable,
            node.getEndOffset(), environmental.environments());
        if (types == null || types.isEmpty())
            return;

        // Свойство колонки таблицы значений EDT кладёт на тип СТРОКИ (collectionElementTypes),
        // а не на саму таблицу. На коллекцию «Колонки» уходит копия с типом ValueTableColumn —
        // туда дописывать нельзя, поэтому вглубь свойств не идём, только к элементу коллекции.
        List<TypeItem> search = new ArrayList<>(types);
        Collection<TypeItem> elements =
            TypeSystemUtil.getCollectionElementTypes(types, invocation);
        if (elements != null)
            search.addAll(elements);

        for (TypeItem item : search)
        {
            if (!(item instanceof Type type) || type.eIsProxy())
                continue;
            ContextDef contextDef = type.getContextDef();
            if (contextDef == null)
                continue;
            for (Property property : contextDef.getProperties())
            {
                if (!(property instanceof DerivedProperty)
                    || !key.equalsIgnoreCase(property.getName()))
                    continue;
                if (property.getTypeContainer() instanceof TypeContainerRef ref)
                    addMissing(ref.getTypes(), commentTypes);
                Module module = EcoreUtil2.getContainerOfType(invocation, Module.class);
                if (module != null)
                    PropertyDescriptions.of(module).put(property, description);
            }
        }
    }

    /** Переменная слева: {@code Ног.Вставить(…)} и {@code ТЗ.Колонки.Добавить(…)}. */
    private static Variable sourceVariable(Expression source)
    {
        if (source instanceof DynamicFeatureAccess access)
            return sourceVariable(access.getSource());
        if (!(source instanceof StaticFeatureAccess access) || access.getFeatureEntries().isEmpty())
            return null;
        FeatureEntry entry = access.getFeatureEntries().get(0);
        return entry.getFeature() instanceof Variable variable ? variable : null;
    }

    /**
     * Добавляет недостающие типы. Сравнение по имени типа, а не по объекту: расчёт может
     * повторяться на тех же объектах модели (быстрый проход редактора), и сравнение по ссылке
     * дало бы дубли в списке членов.
     */
    private static boolean addMissing(List<TypeItem> target, Collection<TypeItem> extra)
    {
        Set<String> present = new HashSet<>();
        for (TypeItem item : target)
        {
            String name = McoreUtil.getTypeName(item);
            if (name != null)
                present.add(name);
        }
        List<TypeItem> missing = new ArrayList<>();
        for (TypeItem item : extra)
        {
            String name = McoreUtil.getTypeName(item);
            if (name != null && present.add(name))
                missing.add(item);
        }
        if (missing.isEmpty())
            return false;
        target.addAll(missing);
        return true;
    }

    /** Флажок EDT «старый формат комментария» — читаем так же, как сам {@code BslTreeTypeSystem}. */
    private static boolean isOldCommentFormat(BslTreeTypeSystem self, IProject project)
    {
        try
        {
            Object preferences = service(self, "bslPreferences"); //$NON-NLS-1$
            if (preferences == null)
                return false;
            Object properties = Global.invoke(preferences, "getDocumentCommentProperties", project); //$NON-NLS-1$
            Object old = properties != null ? Global.invoke(properties, "oldCommentFormat") : null; //$NON-NLS-1$
            return Boolean.TRUE.equals(old);
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * Выполняет проход с контекстом операции BM. {@code BmOperationContext} без транзакции
     * не создаётся ({@code AssertionFailedException: null argument}), а штатный проход системы
     * типов свою транзакцию к этому моменту уже закрыл. Поэтому: текущая транзакция, если она
     * есть, иначе собственная задача чтения — как в {@code ConfigSearchDialogHook}.
     */
    private static void runInTransaction(BslTreeTypeSystem self, IProject project, Pass pass)
    {
        runInTransaction(self, project, context -> pass.run(context));
    }

    private static void runInTransaction(BslTreeTypeSystem self, IProject project,
        ExportModuleVariablesPass pass)
    {
        runInTransaction(self, project, context -> pass.run(context));
    }

    private interface BmPass
    {
        void run(BmOperationContext context);
    }

    private static void runInTransaction(BslTreeTypeSystem self, IProject project, BmPass pass)
    {
        INamingService naming = (INamingService)service(self, "namingService"); //$NON-NLS-1$
        IBmModelManager models = (IBmModelManager)service(self, "bmModelManager"); //$NON-NLS-1$
        if (naming == null || models == null)
            return;
        IBmModel model = models.getModel(project);
        if (model == null)
            return;
        Object engine = Global.invoke(model, "getEngine"); //$NON-NLS-1$
        Object current = engine != null ? Global.invoke(engine, "getCurrentTransaction") : null; //$NON-NLS-1$
        if (current instanceof IBmTransaction transaction)
        {
            pass.run(new BmOperationContext(naming, models, transaction));
            return;
        }
        model.executeReadonlyTask(new AbstractBmTask<Void>("comfort.structureInsertCommentTypes") //$NON-NLS-1$
        {
            @Override
            public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
            {
                pass.run(new BmOperationContext(naming, models, transaction));
                return null;
            }
        }, true);
    }

    /** {@code Перем … Экспорт; // см. …} → тип в export {@code Property} модуля. */
    private static final class ExportModuleVariablesPass
    {
        private final Module module;
        private final List<DeclareStatement> declares;
        private final IScope typeScope;
        private final IScopeProvider scopeProvider;
        private final IQualifiedNameConverter nameConverter;
        private final BslMultiLineCommentDocumentationProvider commentProvider;
        private final IV8ProjectManager v8ProjectManager;
        private final boolean oldFormat;

        ExportModuleVariablesPass(Module module, List<DeclareStatement> declares,
            IScope typeScope, IScopeProvider scopeProvider,
            IQualifiedNameConverter nameConverter,
            BslMultiLineCommentDocumentationProvider commentProvider,
            IV8ProjectManager v8ProjectManager, boolean oldFormat)
        {
            this.module = module;
            this.declares = declares;
            this.typeScope = typeScope;
            this.scopeProvider = scopeProvider;
            this.nameConverter = nameConverter;
            this.commentProvider = commentProvider;
            this.v8ProjectManager = v8ProjectManager;
            this.oldFormat = oldFormat;
        }

        void run(BmOperationContext context)
        {
            ContextDef contextDef = module.getContextDef();
            if (contextDef == null || contextDef.eIsProxy())
                return;
            for (DeclareStatement declare : declares)
            {
                try
                {
                    Collection<TypeItem> commentTypes = computeDeclareSideCommentTypes(declare,
                        typeScope, scopeProvider, nameConverter, commentProvider, v8ProjectManager,
                        oldFormat, context);
                    Environments envs = declare.environments();
                    for (ExplicitVariable variable : declare.getVariables())
                    {
                        if (variable == null || !variable.isExport())
                            continue;
                        List<TypeItem> live = liveTypesFromSeeComment(declare, oldFormat);
                        Collection<TypeItem> types = live;
                        if (types == null || types.isEmpty())
                        {
                            types = commentTypes;
                        }
                        else
                        {
                            int commentProps =
                                BslDocCommentComputedTypes.contextPropertyCount(commentTypes);
                            int liveProps =
                                BslDocCommentComputedTypes.contextPropertyCount(live);
                            if (commentProps > liveProps && commentTypes != null
                                && !commentTypes.isEmpty())
                                types = commentTypes;
                        }
                        if (types == null || types.isEmpty())
                            continue;
                        BslFormTypeContextEnrichment.enrichTypes(types);
                        List<TypeItem> portable = portableStructureTypes(types, envs);
                        replaceAllExportProperties(contextDef, variable.getName(), portable);
                    }
                }
                catch (Throwable ignored)
                {
                }
            }
        }

        /** Все одноимённые Property (Client/Server) — иначе UI читает «чужой» environment. */
        private static void replaceAllExportProperties(ContextDef contextDef, String name,
            Collection<TypeItem> types)
        {
            if (name == null || name.isEmpty())
                return;
            for (Property property : contextDef.getProperties())
            {
                if (property == null || !name.equalsIgnoreCase(property.getName()))
                    continue;
                TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
                if (types != null)
                    container.getTypes().addAll(types);
                property.setTypeContainer(container);
            }
        }

        /**
         * Копия структуры для чужого модуля через
         * {@link BslDocCommentTypeMerge#copyWithRefContext}.
         */
        private static List<TypeItem> portableStructureTypes(Collection<TypeItem> source,
            Environments environments)
        {
            if (source == null || source.isEmpty())
                return List.of();
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem item : source)
            {
                if (!(item instanceof Type type) || type.eIsProxy())
                {
                    if (item != null)
                        result.add(item);
                    continue;
                }
                ContextDef sourceCtx = type.getContextDef();
                org.eclipse.emf.common.util.EList<Property> sourceProps = sourceCtx != null
                    ? (sourceCtx.allProperties() != null ? sourceCtx.allProperties()
                        : sourceCtx.getProperties())
                    : null;
                if (sourceProps == null || sourceProps.isEmpty())
                {
                    result.add(item);
                    continue;
                }
                Environments envs = environments != null ? environments : Environments.ALL;
                result.add(BslDocCommentTypeMerge.copyWithRefContext(type, envs, true));
            }
            return result;
        }

        /** Имя метода из бокового {@code // см. Модуль.Метод} → живые типы из кэша. */
        private static List<TypeItem> liveTypesFromSeeComment(DeclareStatement declare,
            boolean oldFormat)
        {
            try
            {
                Block block = EcoreUtil2.getContainerOfType(declare, Block.class);
                ICompositeNode blockNode =
                    block != null ? NodeModelUtils.findActualNodeFor(block) : null;
                if (blockNode == null)
                    return List.of();
                List<String> raw =
                    TypeSystemUtil.getCommentAfterObject(declare, blockNode, oldFormat);
                if (raw == null || raw.isEmpty())
                    return List.of();
                String methodName = seeAlsoMethodName(raw);
                if (methodName == null)
                    return List.of();
                return BslDocCommentComputedTypes.peekCachedReturnTypes(methodName);
            }
            catch (Throwable ignored)
            {
                return List.of();
            }
        }

        private static String seeAlsoMethodName(List<String> raw)
        {
            for (String line : raw)
            {
                if (line == null)
                    continue;
                String lower = line.toLowerCase(Locale.ROOT);
                int see = lower.indexOf("см."); //$NON-NLS-1$
                if (see < 0)
                    see = lower.indexOf("see"); //$NON-NLS-1$
                if (see < 0)
                    continue;
                String rest = line.substring(see).replaceFirst("(?iu)^(?:см\\.|see)\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
                rest = rest.trim();
                if (rest.isEmpty())
                    continue;
                int end = rest.length();
                for (int i = 0; i < rest.length(); i++)
                {
                    char c = rest.charAt(i);
                    if (Character.isWhitespace(c) || c == ',' || c == ';' || c == '/')
                    {
                        end = i;
                        break;
                    }
                }
                String path = rest.substring(0, end).trim();
                int dot = path.lastIndexOf('.');
                String name = dot >= 0 ? path.substring(dot + 1) : path;
                return name.isEmpty() ? null : name;
            }
            return null;
        }
    }

    /**
     * Собственно проход: типы бокового комментария каждого вызова дописываются в созданное
     * EDT свойство. Отдельный класс, потому что зовётся из задачи чтения BM и должен нести
     * с собой всё нужное.
     */
    private static final class Pass
    {
        private final List<Invocation> candidates;
        private final IScope typeScope;
        private final IScopeProvider scopeProvider;
        private final IQualifiedNameConverter nameConverter;
        private final BslMultiLineCommentDocumentationProvider commentProvider;
        private final IV8ProjectManager v8ProjectManager;
        private final boolean oldFormat;

        Pass(List<Invocation> candidates, IScope typeScope,
            IScopeProvider scopeProvider, IQualifiedNameConverter nameConverter,
            BslMultiLineCommentDocumentationProvider commentProvider,
            IV8ProjectManager v8ProjectManager, boolean oldFormat)
        {
            this.candidates = candidates;
            this.typeScope = typeScope;
            this.scopeProvider = scopeProvider;
            this.nameConverter = nameConverter;
            this.commentProvider = commentProvider;
            this.v8ProjectManager = v8ProjectManager;
            this.oldFormat = oldFormat;
        }

        void run(BmOperationContext context)
        {
            for (Invocation invocation : candidates)
            {
                String key = insertedKey(invocation);
                if (key == null)
                    continue;
                try
                {
                    Statement statement =
                        EcoreUtil2.getContainerOfType(invocation, Statement.class);
                    Collection<TypeItem> commentTypes = statement == null
                        ? null
                        : TypeSystemUtil.computeCommentTypes(statement, typeScope, scopeProvider,
                            nameConverter, commentProvider, v8ProjectManager, oldFormat, context);
                    List<String> lines = sideCommentLines(statement);
                    String description = sideCommentDescription(lines);
                    // Описание без типа (// - …) тоже должно дойти до свойства.
                    fillProperty(invocation, key, commentTypes == null ? List.of() : commentTypes, description);
                }
                catch (Throwable failure)
                {
                }
            }
        }
    }

    static IProject projectOf(Module module)
    {
        if (module == null)
            return null;
        Resource resource = module.eResource();
        URI uri = resource != null ? resource.getURI() : null;
        if (uri == null)
            return null;
        try
        {
            String projectName = null;
            if (uri.isPlatformResource())
            {
                if (uri.segmentCount() >= 2)
                    projectName = uri.segment(1);
            }
            else if ("bm".equals(uri.scheme())) //$NON-NLS-1$
            {
                projectName = uri.authority();
                if (projectName == null || projectName.isEmpty())
                {
                    if (uri.segmentCount() >= 1)
                        projectName = uri.segment(0);
                }
            }
            if (projectName == null || projectName.isEmpty())
                return null;
            return org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                .getProject(projectName);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /**
     * {@link TypesComputer} плюс заполнение типов у mock-экспорта внешнего модуля.
     * Единственный потребитель — {@link #installTypesComputerWrap}.
     */
    private static final class ComfortTypesComputer
        extends TypesComputer
    {
        @Override
        public java.util.List<TypeItem> computeTypes(EObject object, Environments environments)
        {
            fillMockExportPropertyTypes(object);
            return super.computeTypes(object, environments);
        }
    }

    /**
     * Обход дефекта EDT: тип переменной модуля терялся в методе, перед которым стоит метод
     * с другой директивой ({@code &НаКлиентеНаСервереБезКонтекста} перед {@code &НаКлиенте}).
     * <p>
     * Состояние типа переменной модуля заводится на каждый метод, смещение в нём —
     * <b>относительно начала метода</b> (абсолютное = {@code getOffset() + getBlockOffset()}).
     * {@code VariableTypeStateProvider.getNearestByOffset(int)} берёт по ближайшему состоянию
     * из каждой группы сред, а затем {@code getBestStates} при пересекающихся средах оставляет
     * состояние с большим смещением, сравнивая <b>относительные</b> смещения. Более длинная
     * директива верхнего метода даёт большее относительное смещение — остаётся его состояние,
     * и {@code TypesComputer} отсекает его как лежащее вне текущего метода: типов нет.
     * <p>
     * Исправление — группа состояний с тем же отбором, но по абсолютным смещениям. Ставится
     * вместо штатной после каждого расчёта; флажок проекта не нужен — это ошибка самой EDT.
     * <p>
     * Когда EDT исправит дефект, подмена не нужна: один раз за сессию смотрим байткод штатного
     * {@code getBestStates} — если он уже учитывает {@code getBlockOffset} (или устроен иначе),
     * ничего не делаем.
     */
    private static final class ModuleVariableStates
        extends ThreadSafeVariableTypeStateProvider
    {
        /** Нужна ли подмена в этой версии EDT; считается один раз. */
        private static volatile Boolean needed;

        static void fix(Module module)
        {
            if (module == null || module.eIsProxy() || !isNeeded())
                return;
            try
            {
                for (DeclareStatement declare : module.allDeclareStatements())
                {
                    for (ExplicitVariable variable : declare.getVariables())
                    {
                        VariableTypeStateProviderCollector collector =
                            variable != null ? variable.getTypeStateProvider() : null;
                        if (collector == null)
                            continue;
                        for (TypeSystemMode mode : TypeSystemMode.values())
                        {
                            VariableTypeStateProvider provider = collector.get(mode);
                            if (provider == null || provider instanceof ModuleVariableStates)
                                continue;
                            ModuleVariableStates fixed = new ModuleVariableStates();
                            fixed.addStates(provider.getAll());
                            collector.add(mode, fixed);
                        }
                    }
                }
            }
            catch (Throwable t)
            {
                Global.logError("ModuleVariableStates", "fix", t); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        @Override
        public List<VariableTypeState> getNearestByOffset(int offset)
        {
            // Ближайшее не дальше offset в каждой группе сред — как InnerProvider.getNearestByOffset.
            List<Environments> groups = new ArrayList<>();
            List<VariableTypeState> nearest = new ArrayList<>();
            for (VariableTypeState state : getAll())
            {
                if (state == null)
                    continue;
                int position = absoluteOffset(state);
                if (position > offset)
                    continue;
                int group = groups.indexOf(state.getEnvironments());
                if (group < 0)
                {
                    groups.add(state.getEnvironments());
                    nearest.add(state);
                }
                else if (position >= absoluteOffset(nearest.get(group)))
                {
                    nearest.set(group, state);
                }
            }
            if (nearest.size() <= 1)
                return nearest;
            // Как getBestStates, но по абсолютным смещениям.
            List<VariableTypeState> best = new ArrayList<>();
            for (VariableTypeState state : nearest)
            {
                boolean keep = true;
                for (VariableTypeState other : nearest)
                {
                    if (other != state
                        && state.getEnvironments().containsAny(other.getEnvironments())
                        && absoluteOffset(state) < absoluteOffset(other))
                    {
                        keep = false;
                        break;
                    }
                }
                if (keep)
                    best.add(state);
            }
            return best;
        }

        private static int absoluteOffset(VariableTypeState state)
        {
            return state.getOffset() + state.getBlockOffset();
        }

        private static boolean isNeeded()
        {
            Boolean value = needed;
            if (value == null)
            {
                value = Boolean.valueOf(hasDefect());
                needed = value;            }
            return value.booleanValue();
        }

        /**
         * Дефект на месте, если штатный {@code getBestStates} есть и не зовёт
         * {@code getBlockOffset}. Метод пропал или класс не читается — считаем, что EDT
         * переделала отбор, и не вмешиваемся.
         */
        private static boolean hasDefect()
        {
            Class<?> target = VariableTypeStateProvider.class;
            String resource = target.getName().replace('.', '/') + ".class"; //$NON-NLS-1$
            ClassLoader loader = target.getClassLoader();
            try (java.io.InputStream in = loader != null ? loader.getResourceAsStream(resource) : null)
            {
                if (in == null)
                    return false;
                boolean[] found = new boolean[2]; // [0] метод есть, [1] зовёт getBlockOffset
                new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                    new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9)
                    {
                        @Override
                        public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                            String descriptor, String signature, String[] exceptions)
                        {
                            if (!"getBestStates".equals(name)) //$NON-NLS-1$
                                return null;
                            found[0] = true;
                            return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9)
                            {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String method,
                                    String methodDescriptor, boolean isInterface)
                                {
                                    if ("getBlockOffset".equals(method)) //$NON-NLS-1$
                                        found[1] = true;
                                }
                            };
                        }
                    }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                return found[0] && !found[1];
            }
            catch (Throwable t)
            {
                Global.logError("ModuleVariableStates", "hasDefect", t); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
    }

    // #region agent log
    /** Диагностика: последний полный расчёт модуля отменён или упал. */
    static boolean isIncompleteResourceForDiag(Resource resource)
    {
        return resource != null && ComfortTreeTypeSystem.INCOMPLETE_RESOURCES.contains(resource);
    }
    // #endregion

    /**
     * Система типов EDT плюс наш проход. Единственный потребитель — {@link #install()},
     * поэтому вложенный класс, а не отдельный файл.
     */
    private static final class ComfortTreeTypeSystem
        extends BslTreeTypeSystem
    {
        @Override
        public void installTypeSystem(Module module, CancelIndicator cancelIndicator)
        {
            // Сохранение редакторов при закрытии запускает AutoBuildJob без отмены.
            // Отменяем и штатный первый проход, а не только своё дополнение к нему.
            CancelIndicator shutdownAware = () -> isCanceled(cancelIndicator) || isWorkbenchClosing();
            boolean scopeOwner = beginExportVarsScope();
            Resource resource = module == null ? null : module.eResource();
            boolean complete = false;
            // #region agent log
            // Сторож на дефекты EDT вида «частичный расчёт стёр тип возврата непричастной
            // функции» (20.10.2026, bsl 28.0.1: сброс вызываемых). Только частичный расчёт:
            // у полного «до» состояний нет.
            java.util.Set<String> diagTypedBefore = module != null && partialParseResult(module) != null
                ? BslDocCommentComputedTypes.diagTypedReturnFunctions(module) : null;
            // #endregion
            try
            {
                if (isWorkbenchClosing())
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                installTypeSystemInScope(module, shutdownAware);
                // Отмена штатного прохода может завершиться обычным return. При закрытии
                // передаём отмену вызывающей сборке, чтобы она не приняла пустой результат.
                if (isWorkbenchClosing())
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                complete = !isCanceled(shutdownAware);
            }
            finally
            {
                if (diagTypedBefore != null)
                    logReturnLost(module, diagTypedBefore, complete);
                if (resource != null)
                {
                    if (complete)
                        INCOMPLETE_RESOURCES.remove(resource);
                    else
                        INCOMPLETE_RESOURCES.add(resource);
                }
                endExportVarsScope(scopeOwner);
            }
        }

        /**
         * Ресурсы модулей, чей последний полный расчёт отменён или упал. Отменённый расчёт
         * оставляет методы без состояний, а следующие частичные (правка тела) пересчитывают
         * только переразобранный метод — остальные так и остаются пустыми (лог 18.10:
         * `ОписаниеУзла` NORMAL=noProvider после отмены в Xtext Editor Reconciler, «ОписаниеУзла().»
         * — 0 членов). Следующий расчёт такого модуля делается полным.
         */
        private static final java.util.Set<Resource> INCOMPLETE_RESOURCES = java.util.Collections
            .synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

        // #region agent log
        /**
         * Функция, у которой до частичного расчёта был тип возврата, а после — нет. Свой
         * переразобранный метод сюда не попадает: у него новый объект и «до» нет.
         */
        private static void logReturnLost(Module module, java.util.Set<String> typedBefore, boolean complete)
        {
            if (typedBefore.isEmpty())
                return;
            java.util.Set<String> typedAfter = BslDocCommentComputedTypes.diagTypedReturnFunctions(module);
            for (Method method : module.allMethods())
            {
                String name = method.getName();
                if (name == null || !typedBefore.contains(name) || typedAfter.contains(name))
                    continue;
                Global.tempLog(SmartContentAssistProcessor.RECEIVER_TYPE_LOG, "returnLost function=" + name //$NON-NLS-1$
                    + " module=" + (module.eResource() == null ? null : module.eResource().getURI()) //$NON-NLS-1$
                    + " reparseMethod=" + diagReparseMethodName(module) //$NON-NLS-1$
                    + " complete=" + complete //$NON-NLS-1$
                    + " th=" + Thread.currentThread().getName() //$NON-NLS-1$
                    + " AFTER=" + BslDocCommentComputedTypes.diagReturnStateDeep(method)); //$NON-NLS-1$
            }
        }

        /** Первые имена набора — без простыни на сотни функций. */
        private static String diagNames(java.util.Set<String> names)
        {
            StringBuilder text = new StringBuilder();
            int shown = 0;
            for (String name : names)
            {
                if (shown++ == 15)
                {
                    text.append(" …+").append(names.size() - 15); //$NON-NLS-1$
                    break;
                }
                text.append(shown == 1 ? "" : ",").append(name); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return text.toString();
        }

        private static String diagReparseMethodName(Module module)
        {
            try
            {
                Object parse = module == null || module.eResource() == null ? null
                    : Global.invoke(module.eResource(), "getParseResult"); //$NON-NLS-1$
                if (parse == null || !parse.getClass().getName().endsWith(".PartialParseResult")) //$NON-NLS-1$
                    return "-"; //$NON-NLS-1$
                Object m = Global.invoke(parse, "getReparseMethod"); //$NON-NLS-1$
                return m instanceof com._1c.g5.v8.dt.bsl.model.Method method ? method.getName() : String.valueOf(m);
            }
            catch (Throwable e)
            {
                return "ERR:" + e; //$NON-NLS-1$
            }
        }
        // #endregion

        private void installTypeSystemInScope(Module module, CancelIndicator cancelIndicator)
        {
            Runnable restoreFull = null;
            if (module.eResource() != null && INCOMPLETE_RESOURCES.contains(module.eResource()))
            {
                Object partial = partialParseResult(module);
                if (partial != null && !BslDocCommentComputedTypes.isBuildThread())
                {
                    // Вне сборки — не здесь: полный расчёт в перевязке, синхронизации автодополнения
                    // или связи со схемой сам отменяется вводом, и после каждой отмены следующий снова
                    // полный — цепочка 3–5-секундных расчётов, автодополнение ждало 8 с (лог 18.10
                    // 02:50). Модуль пересчитается целиком при следующем автодополнении в нём.
                    StaleTypes.wholeModule(module.eResource());
                }
                else if (partial != null)
                {
                    try
                    {
                        restoreFull = substituteReparseMethod(partial, null);
                    }
                    catch (IllegalStateException ignored)
                    {
                        // Поле разбора недоступно — остаётся частичный расчёт.
                    }
                }
            }
            // Признак частичности снят на весь расчёт: и второй проход должен идти полным.
            try
            {
                installTypeSystemPass(module, cancelIndicator);
                secondPassIfNeeded(module, cancelIndicator);
            }
            finally
            {
                if (restoreFull != null)
                    restoreFull.run();
            }
        }

        private void secondPassIfNeeded(Module module, CancelIndicator cancelIndicator)
        {
            // Закрытие workbench само по себе не отменяет AutoBuildJob. Дополнительный
            // проход при закрытии не нужен, а сохранение workspace ждёт его окончания.
            if (stopSecondPass(cancelIndicator))
                return;
            // Единственный источник рассчитанных типов возврата для ссылок «см.»: сами мы
            // модули не считаем — только запоминаем то, что EDT посчитал. Ссылки модуля на
            // собственные функции EDT разрешил в этом же расчёте, до записи, — без слияния;
            // если такие есть и тип изменился, модуль считается ещё раз (не больше одного).
            // Отменённый расчёт недосчитан: его типы запомнились бы как «изменившиеся» и запустили
            // бы полный второй проход, который сборке пришлось бы ждать (лог 18.10: сборка отменяет
            // расчёт УправлениеДоступомСлужебный, remembered=160→67→0, второй проход 17 с, закрытие
            // EDT ждёт сборку).
            if (isCanceled(cancelIndicator))
                return;
            java.util.Set<String> changed = BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
            if (stopSecondPass(cancelIndicator))
                return;
            Object partial = partialParseResult(module);
            // Вне сборки второго прохода нет: тип самой изменённой функции посчитан первым проходом,
            // а методы, ссылающиеся на неё «см.», только помечаются устаревшими и пересчитываются
            // по одному — тот, где вызвано автодополнение ({@link #refreshTypesForAssist}). Пересчёт
            // всех сразу — 25 с ожидания на правку в модуле БСП (лог 18.10), из своего задания он
            // невозможен (EDT падает вне контекста перевязки). В сборке — сразу: её проверки идут
            // следом и должны видеть слитые типы.
            if (!BslDocCommentComputedTypes.isBuildThread())
            {
                if (!changed.isEmpty())
                    StaleTypes.add(module.eResource(), changed);
                else if (partial == null)
                    // Полный расчёт без изменений: всё посчитано по свежему кэшу.
                    StaleTypes.clear(module.eResource());
                return;
            }
            if (changed.isEmpty())
                return;
            // #region agent log second-pass
            long diagStart = System.currentTimeMillis();
            String diagModule = BslDocCommentComputedTypes.diagModuleName(module);
            // #endregion
            if (partial == null)
            {
                // Полный переразбор: повторный штатный расчёт всего модуля. Готовые состояния тела
                // он не пересоздаёт (параметр в теле оставался без слияния, лог 18.10: «Структура.»
                // — 0 членов), поэтому NORMAL сначала сбрасывается.
                // #region agent log second-pass
                Global.tempLog("see-second-pass", "build.full.start module=" + diagModule //$NON-NLS-1$ //$NON-NLS-2$
                    + " changed=" + changed.size() + " names=" + diagNames(changed) //$NON-NLS-1$ //$NON-NLS-2$
                    + " th=" + Thread.currentThread().getName()); //$NON-NLS-1$
                // #endregion
                SecondPassProgress progress = new SecondPassProgress(module, 0);
                try
                {
                    for (Method method : module.allMethods())
                        dropNormalStates(method);
                    for (Variable variable : EcoreUtil2.getAllContentsOfType(module, Variable.class))
                        dropNormal(variable.getTypeStateProvider());
                    installTypeSystemPass(module, cancelIndicator);
                    if (!isCanceled(cancelIndicator))
                    {
                        BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
                        BslDocCommentComputedTypes.bumpTypesGeneration();
                    }
                }
                finally
                {
                    progress.finish();
                    // #region agent log second-pass
                    Global.tempLog("see-second-pass", "build.full.end module=" + diagModule //$NON-NLS-1$ //$NON-NLS-2$
                        + " canceled=" + isCanceled(cancelIndicator) //$NON-NLS-1$
                        + " ms=" + (System.currentTimeMillis() - diagStart)); //$NON-NLS-1$
                    // #endregion
                }
                return;
            }
            // Частичный переразбор (правка тела метода): штатный расчёт берёт только
            // переразобранный метод и его вызывающих (initFinalStateForMethod: isOnlyMethodReparse →
            // getDependMethodsTree). Методы со ссылкой «см.» на изменённую функцию — не вызывающие,
            // их тот же частичный расчёт проходит по одному: переразобранным методом на время
            // прохода подставляется ссылающийся. Полный расчёт модуля здесь не годится — на модуле
            // БСП это ~20 с на правку.
            List<Method> dependents = BslDocCommentComputedTypes.methodsReferencing(module, changed);
            // #region agent log second-pass
            Global.tempLog("see-second-pass", "build.partial.start module=" + diagModule //$NON-NLS-1$ //$NON-NLS-2$
                + " reparse=" + diagReparseMethodName(module) //$NON-NLS-1$
                + " changed=" + changed.size() + " dependents=" + dependents.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " referencesMs=" + (System.currentTimeMillis() - diagStart) //$NON-NLS-1$
                + " names=" + diagNames(changed) //$NON-NLS-1$
                + " th=" + Thread.currentThread().getName()); //$NON-NLS-1$
            int diagDone = 0;
            String diagStop = "end"; //$NON-NLS-1$
            // #endregion
            SecondPassProgress progress = new SecondPassProgress(module, dependents.size());
            try
            {
                for (Method dependent : dependents)
                {
                    if (stopSecondPass(cancelIndicator))
                    {
                        // #region agent log second-pass
                        diagStop = "canceled"; //$NON-NLS-1$
                        // #endregion
                        return;
                    }
                    // Отмена в «Ходе выполнения»: оставшиеся методы сохраняют прежний слитый тип.
                    if (progress.cancelRequestedByUser())
                    {
                        // #region agent log second-pass
                        diagStop = "userCancel"; //$NON-NLS-1$
                        // #endregion
                        break;
                    }
                    progress.step(dependent.getName());
                    Runnable restore;
                    try
                    {
                        restore = substituteReparseMethod(partial, dependent);
                    }
                    catch (IllegalStateException ignored)
                    {
                        // Поле разбора недоступно — ссылающиеся методы останутся с прежним типом.
                        // #region agent log second-pass
                        diagStop = "noSubstitute"; //$NON-NLS-1$
                        // #endregion
                        return;
                    }
                    try
                    {
                        dropNormalStates(dependent);
                        for (Object caller : com._1c.g5.v8.dt.bsl.util.BslUtil.getDependMethodsTree(dependent))
                            if (caller instanceof Method callerMethod)
                                dropNormalStates(callerMethod);
                        stockInstall(module, cancelIndicator);
                        BslFormTypeContextEnrichment.enrichMethod(dependent);
                        enrich(this, module, dependent);
                    }
                    finally
                    {
                        restore.run();
                    }
                    progress.stepDone();
                    // #region agent log second-pass
                    diagDone++;
                    // #endregion
                }
            }
            finally
            {
                progress.finish();
                // #region agent log second-pass
                Global.tempLog("see-second-pass", "build.partial.end module=" + diagModule //$NON-NLS-1$ //$NON-NLS-2$
                    + " done=" + diagDone + "/" + dependents.size() + " stop=" + diagStop //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " ms=" + (System.currentTimeMillis() - diagStart)); //$NON-NLS-1$
                // #endregion
            }
            ModuleVariableStates.fix(module);
            if (!isCanceled(cancelIndicator))
            {
                BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
                // Запас членов автодополнения у точки посчитан по прежним состояниям.
                BslDocCommentComputedTypes.bumpTypesGeneration();
            }
        }

        private static boolean stopSecondPass(CancelIndicator cancelIndicator)
        {
            boolean canceled = isCanceled(cancelIndicator);
            boolean closing = isWorkbenchClosing();
            return canceled || closing;
        }

        private static boolean isWorkbenchClosing()
        {
            return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
        }

        private static boolean isCanceled(CancelIndicator cancelIndicator)
        {
            return cancelIndicator != null && cancelIndicator.isCanceled();
        }

        /** Полный повторный расчёт модуля со сбросом NORMAL (вызывающий держит блокировку ресурса). */
        void recomputeModuleFully(Module module, CancelIndicator cancelIndicator)
        {
            Object partial = partialParseResult(module);
            Runnable restore = partial == null ? null : substituteReparseMethod(partial, null);
            try
            {
                for (Method method : module.allMethods())
                    dropNormalStates(method);
                for (Variable variable : EcoreUtil2.getAllContentsOfType(module, Variable.class))
                    dropNormal(variable.getTypeStateProvider());
                installTypeSystemPass(module, cancelIndicator);
            }
            finally
            {
                if (restore != null)
                    restore.run();
            }
        }

        /**
         * Пересчёт одного ссылающегося метода и его вызывающих штатным частичным расчётом
         * (вызывающий держит блокировку ресурса).
         *
         * @return {@code false}, если подставить метод не удалось
         */
        boolean recomputeDependent(Module module, Method dependent, CancelIndicator cancelIndicator)
        {
            Object partial = partialParseResult(module);
            Runnable restore = partial != null ? substituteReparseMethod(partial, dependent)
                : installTemporaryPartialParse(module, dependent);
            if (restore == null)
                return false;
            try
            {
                dropNormalStates(dependent);
                for (Object caller : com._1c.g5.v8.dt.bsl.util.BslUtil.getDependMethodsTree(dependent))
                    if (caller instanceof Method callerMethod)
                        dropNormalStates(callerMethod);
                stockInstall(module, cancelIndicator);
                BslFormTypeContextEnrichment.enrichMethod(dependent);
                enrich(this, module, dependent);
                return true;
            }
            finally
            {
                restore.run();
            }
        }

        /**
         * Модуль разобран полностью: ставим ресурсу временный штатный частичный результат разбора
         * {@code new PartialParseResult(текущий, метод)} (конструктор публичный), чтобы штатный
         * расчёт взял только этот метод и его вызывающих — как после правки тела. Полная перевязка
         * разрешила «см.» до записи типа в кэш, и «готовыми» её состояния считать нельзя (18.10
         * 09:18: пересчёт за 0 мс, параметр «Структура» остался Undefined).
         *
         * @return возврат прежнего результата разбора или {@code null}, если поставить не удалось
         */
        private static Runnable installTemporaryPartialParse(Module module, Method method)
        {
            Resource resource = module.eResource();
            if (!(resource instanceof org.eclipse.xtext.resource.XtextResource xtext))
                return null;
            try
            {
                org.eclipse.xtext.parser.IParseResult current = xtext.getParseResult();
                if (current == null)
                    return null;
                Class<?> partialClass = Class.forName(
                    "com._1c.g5.v8.dt.bsl.parser.antlr.PartialParseResult", true, //$NON-NLS-1$
                    BslStructureInsertCommentTypes.class.getClassLoader());
                Object temporary = partialClass
                    .getConstructor(org.eclipse.xtext.parser.IParseResult.class, Method.class)
                    .newInstance(current, method);
                Field field = org.eclipse.xtext.resource.XtextResource.class.getDeclaredField("parseResult"); //$NON-NLS-1$
                field.setAccessible(true);
                field.set(xtext, temporary);
                return () ->
                {
                    try
                    {
                        field.set(xtext, current);
                    }
                    catch (ReflectiveOperationException ignored)
                    {
                        // Поле только что записывалось тем же способом — сюда не попадаем.
                    }
                };
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError ignored)
            {
                // Поле разбора недоступно в этой версии EDT — пересчёт метода пропускается.
                return null;
            }
        }

        /**
         * Функции модуля, сменившие версию после его последнего полного расчёта, — их «см.» в
         * других методах разрешены по прежнему типу. Методы пересчитываются по одному, лениво:
         * тот, в котором вызвано автодополнение ({@link #refreshTypesForAssist}).
         */
        private static final class StaleTypes
        {
            private static final java.util.Map<Resource, StaleTypes> BY_RESOURCE =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

            final java.util.Set<String> names = new java.util.HashSet<>();
            /** Расчёт модуля отменён и мог оставить методы без состояний — пересчитать целиком. */
            boolean wholeModule;
            /** Растёт с каждым пополнением {@link #names}. */
            int version;
            /** Имя метода (нижний регистр) → {@link #version}, при которой он пересчитан. */
            final java.util.Map<String, Integer> refreshed = new java.util.HashMap<>();

            static void add(Resource resource, java.util.Set<String> changed)
            {
                if (resource == null)
                    return;
                StaleTypes stale = BY_RESOURCE.computeIfAbsent(resource, key -> new StaleTypes());
                synchronized (stale)
                {
                    // Каждое изменение — новая версия, даже если имя функции уже в наборе: иначе
                    // метод, пересчитанный после прошлой правки той же функции, считался свежим
                    // (18.10 09:35 — «см.» не обновлялся).
                    stale.names.addAll(changed);
                    stale.version++;
                }
            }

            static void wholeModule(Resource resource)
            {
                if (resource == null)
                    return;
                StaleTypes stale = BY_RESOURCE.computeIfAbsent(resource, key -> new StaleTypes());
                synchronized (stale)
                {
                    stale.wholeModule = true;
                }
            }

            static void clear(Resource resource)
            {
                if (resource != null)
                    BY_RESOURCE.remove(resource);
            }
        }

        /** Тот же порядок, что у {@code BslResource.linkBatched}: блокировка ресурса, уведомления выключены. */
        private static boolean underLinkLock(Resource resource, Object lock, java.util.function.BooleanSupplier work)
        {
            synchronized (lock)
            {
                boolean deliver = resource.eDeliver();
                resource.eSetDeliver(false);
                try
                {
                    return work.getAsBoolean();
                }
                finally
                {
                    resource.eSetDeliver(deliver);
                }
            }
        }

        /**
         * Видимое задание хода второго прохода. Сам проход идёт в чужом задании (перевязка,
         * синхронизация автодополнения, сборка), и в «Ходе выполнения» видно только его штатное
         * имя — непонятно, что ждём именно пересчёт типов. Задание ничего не считает и блокировок
         * не держит; показывается, только если проход дольше {@link #SHOW_DELAY_MS}. Отмена в нём
         * останавливает оставшиеся пересчёты ссылающихся методов.
         */
        private static final class SecondPassProgress extends Job
        {
            private static final long SHOW_DELAY_MS = 500;

            private final String moduleName;
            private final int total;
            private volatile int done;
            private volatile String current = ""; //$NON-NLS-1$
            private volatile boolean finished;
            private volatile boolean cancelRequested;

            SecondPassProgress(Module module, int total)
            {
                super("Комфорт: пересчёт типов по ссылкам «см.»"); //$NON-NLS-1$
                this.moduleName = moduleTitle(module);
                this.total = total;
                setPriority(Job.SHORT);
                schedule(SHOW_DELAY_MS);
            }

            private static String moduleTitle(Module module)
            {
                Resource resource = module.eResource();
                URI uri = resource == null ? null : resource.getURI();
                if (uri == null || uri.segmentCount() < 2)
                    return ""; //$NON-NLS-1$
                // …/CommonModules/УправлениеДоступомСлужебный/Module.bsl
                return uri.segment(uri.segmentCount() - 2);
            }

            void step(String methodName)
            {
                current = methodName == null ? "" : methodName; //$NON-NLS-1$
            }

            void stepDone()
            {
                done++;
            }

            /** Пользователь нажал «Отмена» в «Ходе выполнения». */
            boolean cancelRequestedByUser()
            {
                return cancelRequested;
            }

            void finish()
            {
                finished = true;
                // Не начатое задание просто снимается — короткий проход не мелькает.
                cancel();
            }

            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                monitor.beginTask(moduleName, total > 0 ? total : IProgressMonitor.UNKNOWN);
                int reported = 0;
                while (!finished)
                {
                    if (monitor.isCanceled())
                    {
                        cancelRequested = true;
                        return Status.CANCEL_STATUS;
                    }
                    if (total > 0)
                    {
                        int now = Math.min(done, total);
                        if (now > reported)
                        {
                            monitor.worked(now - reported);
                            reported = now;
                        }
                        monitor.subTask((Math.min(done + 1, total)) + " из " + total + ": " + current); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    try
                    {
                        Thread.sleep(100);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                monitor.done();
                return Status.OK_STATUS;
            }
        }

        /** {@code PartialParseResult} ресурса модуля или {@code null}, если переразбор полный. */
        private static Object partialParseResult(Module module)
        {
            Object parse = module.eResource() == null ? null : Global.invoke(module.eResource(), "getParseResult"); //$NON-NLS-1$
            if (parse == null || !parse.getClass().getName().endsWith(".PartialParseResult")) //$NON-NLS-1$
                return null;
            return Global.invoke(parse, "getReparseMethod") != null ? parse : null; //$NON-NLS-1$
        }

        /**
         * Подставляет переразобранный метод частичного разбора ({@code reparseMethod} — поле
         * {@code final}, сеттера нет; от него штатный расчёт берёт набор пересчитываемых методов).
         *
         * @return возврат прежнего метода
         * @throws IllegalStateException подставить не удалось
         */
        private static Runnable substituteReparseMethod(Object partial, Method method)
        {
            try
            {
                Field field = partial.getClass().getDeclaredField("reparseMethod"); //$NON-NLS-1$
                field.setAccessible(true);
                Object previous = field.get(partial);
                field.set(partial, method);
                return () ->
                {
                    try
                    {
                        field.set(partial, previous);
                    }
                    catch (ReflectiveOperationException ignored)
                    {
                        // Поле только что записывалось тем же способом — сюда не попадаем.
                    }
                };
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                throw new IllegalStateException("reparse method not substituted", e); //$NON-NLS-1$
            }
        }

        /** NORMAL-состояния метода: его переменные и параметры, возврат, итоговые параметры. */
        private static void dropNormalStates(Method method)
        {
            for (Variable variable : EcoreUtil2.getAllContentsOfType(method, Variable.class))
                dropNormal(variable.getTypeStateProvider());
            if (method instanceof com._1c.g5.v8.dt.bsl.model.Function function)
                dropNormal(function.getFinalReturnState());
            method.getFinalInParamState().forEach(ComfortTreeTypeSystem::dropNormal);
            method.getFinalOutParamState().forEach(ComfortTreeTypeSystem::dropNormal);
        }

        private static void dropNormal(VariableTypeStateProviderCollector collector)
        {
            if (collector != null && collector.get(TypeSystemMode.NORMAL) != null)
                collector.removeMode(TypeSystemMode.NORMAL);
        }

        /**
         * Штатный {@code installTypeSystem} с сохранением типов возврата вызываемых функций:
         * EDT 28.0.1 (сборка 2026-07) в частичном расчёте сбрасывает их, не пересчитывая тел
         * (см. {@code BslDocCommentComputedTypes.seedCalleeReturnFromPartialParse}).
         */
        private void stockInstall(Module module, CancelIndicator cancelIndicator)
        {
            java.util.Map<EObject, List<?>> previous = BslDocCommentComputedTypes.captureCalleeReturns(module);
            try
            {
                super.installTypeSystem(module, cancelIndicator);
            }
            finally
            {
                BslDocCommentComputedTypes.releaseCalleeReturns(previous);
            }
        }

        private void installTypeSystemPass(Module module, CancelIndicator cancelIndicator)
        {
            stockInstall(module, cancelIndicator);
            ModuleVariableStates.fix(module);
            // см. ОбщаяФорма.…: экспорт модуля в тип параметра (BslTreeTypeSystem не ткётся).
            BslFormTypeContextEnrichment.enrichModule(module);
            enrich(this, module, null);
        }

        /**
         * Быстрый проход редактора идёт на каждое изменение текста, поэтому здесь обходим
         * только текущий метод, а не весь модуль.
         */
        @Override
        public void lightInstallingTypeSystem(Module module, Method method, Variable variable,
            Statement statement, int offset, BmOperationContext context)
        {
            boolean scopeOwner = beginExportVarsScope();
            try
            {
                lightInstallingTypeSystemInScope(module, method, variable, statement, offset, context);
            }
            finally
            {
                endExportVarsScope(scopeOwner);
            }
        }

        private void lightInstallingTypeSystemInScope(Module module, Method method,
            Variable variable, Statement statement, int offset, BmOperationContext context)
        {
            super.lightInstallingTypeSystem(module, method, variable, statement, offset, context);
            ModuleVariableStates.fix(module);
            BslFormTypeContextEnrichment.enrichMethod(method);
            enrich(this, module, method);
            dropStaleLightReturn(method);
        }

        /**
         * Облегчённый проход переразобранной функции начинает её тип возврата со старого
         * ({@code initFinalStateForMethod}: режим LIGHT, {@code method == getReparseMethod()} →
         * {@code PartialParseResult.getOldReturnTypes()}) и добавляет к нему новый. А чтение
         * типа ({@code TypesComputer}) берёт LIGHT раньше NORMAL: после правки тела
         * {@code ОписаниеУзла().} показывал прежнее поле «ячс» вместе с новым «мус» (18.10 09:08).
         * Снимаем LIGHT возврата — читается свежий NORMAL перевязки; приём тот же, что для
         * пустого LIGHT у «см.».
         */
        private static void dropStaleLightReturn(Method method)
        {
            if (method instanceof com._1c.g5.v8.dt.bsl.model.Function function
                && function.getFinalReturnState() != null
                && function.getFinalReturnState().get(TypeSystemMode.LIGHT) != null
                && function.getFinalReturnState().get(TypeSystemMode.NORMAL) != null)
                function.getFinalReturnState().removeMode(TypeSystemMode.LIGHT);
        }
    }
}
