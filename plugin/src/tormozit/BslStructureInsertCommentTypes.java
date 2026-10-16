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
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
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
import com._1c.g5.v8.dt.bsl.documentation.comment.BslMultiLineCommentDocumentationProvider;
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
        if (!(property instanceof DerivedProperty derived)
            || !(derived.getSource() instanceof BslDerivedPropertySource source)
            || source.getModuleUri() == null
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(context))
            return null;
        PropertyDescriptions descriptions = PropertyDescriptions.forModuleUri(URI.createURI(source.getModuleUri()));
        if (!descriptions.contains(property))
        {
            readPropertySourceDescription(property, source, context);
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
        Block block = module;
        if (source.getMethodName() != null && !source.getMethodName().isEmpty())
        {
            block = null;
            for (Method method : module.allMethods())
                if (source.getMethodName().equalsIgnoreCase(method.getName()))
                {
                    block = method;
                    break;
                }
        }
        ICompositeNode node = block == null ? null : NodeModelUtils.findActualNodeFor(block);
        if (node == null)
            return null;
        int offset = node.getTotalOffset() + source.getLocalOffset();
        if (offset < node.getTotalOffset() || offset >= node.getTotalEndOffset())
            return null;
        var leaf = NodeModelUtils.findLeafNodeAtOffset(node, offset);
        EObject semantic = leaf == null ? null : NodeModelUtils.findActualSemanticObjectFor(leaf);
        Invocation invocation = EcoreUtil2.getContainerOfType(semantic, Invocation.class);
        if (invocation == null || !property.getName().equalsIgnoreCase(insertedKey(invocation)))
            return null;
        Statement statement = EcoreUtil2.getContainerOfType(invocation, Statement.class);
        List<String> lines = sideCommentLines(statement);
        String description = sideCommentDescription(lines);
        PropertyDescriptions.forModuleUri(URI.createURI(source.getModuleUri())).put(property, description);
        return description;
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
            completePropertyHover(info, object, viewer != null ? viewer : proposalViewer);
            return info;
        }

        @Override
        public IInformationControlCreator getHoverControlCreator()
        {
            return delegate instanceof ITextHoverExtension extension ? extension.getHoverControlCreator() : null;
        }
    }

    /** Дополняет именно страницу штатной подсказки; описание уже прочитано в Pass. */
    private static void completePropertyHover(Object info, EObject object, ITextViewer viewer)
    {
        if (!(object instanceof Property property) || viewer == null
            || !(viewer.getDocument() instanceof IXtextDocument document))
            return;
        try
        {
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
        catch (Throwable ignored)
        {
        }
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

    /** Слово под указателем разрешается тем же API, что в штатном BslDispatchingEObjectTextHover. */
    static void completePropertyHover(Object info, ITextViewer viewer, int offset)
    {
        if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return;
        try
        {
            EObject object = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
                resource == null ? null : new org.eclipse.xtext.resource.EObjectAtOffsetHelper()
                    .resolveCrossReferencedElementAt(resource, offset));
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

        void clear(Method scope)
        {
            if (scope == null)
                values.clear();
            else
                values.keySet().removeIf(key -> key.startsWith(scope.getName() + "\n")); //$NON-NLS-1$
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
            values.put(key, description == null ? "" : description); //$NON-NLS-1$
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

    /** Переносит описания в поля секции результата, которую собрал штатный TypeSection.fill. */
    static void fillPropertyDescriptions(Object section, List<?> types, EObject context)
    {
        if (!(section instanceof TypeSection typeSection))
            return;
        for (var definition : typeSection.getTypeDefinitions())
        {
            if (definition.getTypeName() == null)
                continue;
            List<Property> properties = new ArrayList<>();
            for (Object item : types)
                if (item instanceof Type type && type.getContextDef() != null
                    && (definition.getTypeName().equals(McoreUtil.getTypeName(type))
                        || definition.getTypeName().equals(McoreUtil.getTypeNameRu(type))))
                    properties.addAll(type.getContextDef().allProperties());
            for (var field : definition.getFieldDefinitionExtension())
                for (Property property : properties)
                    if (field.getName().equalsIgnoreCase(property.getName()))
                    {
                        String description = propertyDescription(property, context);
                        if (description != null && field.getDescription().getParts().isEmpty())
                            field.getDescription().addPart(new TextPart(field.getDescription(),
                                field.getLineNumber(), description));
                        List<TypeItem> nestedTypes = property.getTypeContainer() instanceof TypeContainerRef ref
                            ? ref.getTypes() : property.getTypes();
                        for (TypeSection nested : field.getTypeSections())
                            fillPropertyDescriptions(nested, nestedTypes, context);
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
                    BslDocCommentComputedTypes.enableForceLightInstall();
                    Collection<TypeItem> commentTypes;
                    try
                    {
                        commentTypes = computeDeclareSideCommentTypes(declare, typeScope,
                            scopeProvider, nameConverter, commentProvider, v8ProjectManager,
                            oldFormat, context);
                    }
                    finally
                    {
                        BslDocCommentComputedTypes.disableForceLightInstall();
                    }
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
                catch (Throwable ignored)
                {
                }
            }
        }
    }

    private static IProject projectOf(Module module)
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
            boolean scopeOwner = beginExportVarsScope();
            try
            {
                installTypeSystemInScope(module, cancelIndicator);
            }
            finally
            {
                endExportVarsScope(scopeOwner);
            }
        }

        private void installTypeSystemInScope(Module module, CancelIndicator cancelIndicator)
        {
            super.installTypeSystem(module, cancelIndicator);
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
        }
    }
}
