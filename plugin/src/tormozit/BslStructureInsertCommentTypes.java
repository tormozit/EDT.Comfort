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
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.naming.IQualifiedNameConverter;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.OnChangeEvictingCache;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
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
import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
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
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
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
import com._1c.g5.v8.dt.bsl.typesystem.ValueTableDynamicContextDefProvider;
import com._1c.g5.v8.dt.bsl.typesystem.ValueTreeDynamicContextDefProvider;
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
import com._1c.g5.v8.dt.mcore.TypeContainer;
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
            ArrayElementTypes.install();
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
        TypesComputer source = onDfa instanceof TypesComputer typed ? typed : current;
        ComfortTypesComputer ours = source instanceof ComfortTypesComputer existing
            ? existing : new ComfortTypesComputer();
        if (ours != source)
            copyTypesComputerFields(source, ours);
        field.set(dfa, ours);
        // CreatorTreeState получает этот вычислитель из BslTreeTypeSystem (проверено в JAR).
        Field treeField = BslTypeSystemProvider.class.getDeclaredField(TREE_FIELD);
        treeField.setAccessible(true);
        Object tree = treeField.get(rsp.get(BslTypeSystemProvider.class));
        Field computerField = BslTreeTypeSystem.class.getDeclaredField("computerTypes");
        computerField.setAccessible(true);
        computerField.set(tree, ours);
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

    /**
     * Один успешный проход (переносимый ExtendedType) на экземпляр модуля за сессию. Ключи слабые:
     * сильный набор удерживал прежние экземпляры модулей после каждой перепроверки (issue 948).
     */
    private static final java.util.Set<Module> EXPORT_VARS_DONE = java.util.Collections
        .synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

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
     * Перед автодополнением — только типы результатов запросов метода под кареткой ({@link BslQueryResultTypes}):
     * план метода строится при смене его текста, и если в нём есть запросы, пересчитываются метод и его вызывающие.
     * Цепочка «см.» ({@link #refreshTypesForAssist}) здесь не пересчитывается: при первом заполнении кэша она
     * разворачивается в сотни методов (3,5 мин на модуле БСП, 23.10.2026). Вызывать в фоновом потоке под
     * {@code readOnly} документа: блокировка перевязки ресурса берётся здесь.
     */
    static void refreshQueryTypesForAssist(Resource resource, int offset,
        java.util.function.Consumer<String> onRecompute)
    {
        refreshQueryTypesForAssist(resource, offset, onRecompute, CancelIndicator.NullImpl);
    }

    static void refreshQueryTypesForAssist(Resource resource, int offset,
        java.util.function.Consumer<String> onRecompute, CancelIndicator cancellation)
    {
        if (cancellation.isCanceled())
            throw new org.eclipse.core.runtime.OperationCanceledException();
        if (resource == null || org.eclipse.swt.widgets.Display.getCurrent() != null
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(BslNodeLookup.rootAst(resource))
            || !(peekTreeTypeSystem() instanceof ComfortTreeTypeSystem queryTree))
            return;
        refreshExternalFunctionTypes(resource, offset, cancellation);
        if (cancellation.isCanceled())
            throw new org.eclipse.core.runtime.OperationCanceledException();
        Object queryLock = Global.invoke(resource, "getLock"); //$NON-NLS-1$
        if (queryLock != null)
            ComfortTreeTypeSystem.underLinkLock(resource, queryLock, () ->
            {
                BslQueryResultTypes.refresh(resource, offset, onRecompute,
                    queryRecompute(queryTree, resource, cancellation));
                return true;
            });
    }

    /** Пересчёт метода с изменившимся планом запросов и его вызывающих; под блокировкой перевязки ресурса. */
    private static java.util.function.Predicate<Method> queryRecompute(ComfortTreeTypeSystem queryTree,
        Resource resource, CancelIndicator cancellation)
    {
        return method ->
        {
            Module module = EcoreUtil2.getContainerOfType(method, Module.class);
            if (!queryTree.recomputeDependent(module, method, cancellation))
                return false;
            java.util.Set<String> changed = BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
            if (!changed.isEmpty())
                ComfortTreeTypeSystem.StaleTypes.add(resource, changed);
            return true;
        };
    }

    /**
     * Типы результатов запросов всех методов модуля — первый досчёт после открытия: тип в одном методе может
     * зависеть от запроса в другом (параметр по вызову). Проверки и поиск — один раз на модуль, без внешних
     * возвратов и поиска метода по смещению ({@link #refreshQueryTypesForAssist} на каждый метод — 25 с на
     * модуле БСП из 882 методов). Блокировка перевязки — на каждый метод отдельно. Фоновый поток под
     * {@code readOnly} документа.
     */
    static void refreshQueryTypesForModule(Resource resource, CancelIndicator cancellation)
    {
        if (resource == null || org.eclipse.swt.widgets.Display.getCurrent() != null
            || !(BslNodeLookup.rootAst(resource) instanceof Module module)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(module)
            || !(peekTreeTypeSystem() instanceof ComfortTreeTypeSystem queryTree))
            return;
        Object queryLock = Global.invoke(resource, "getLock"); //$NON-NLS-1$
        if (queryLock == null)
            return;
        var recompute = queryRecompute(queryTree, resource, cancellation);
        List<Method> methods = new ArrayList<>(module.allMethods());
        for (Method method : methods)
        {
            if (cancellation.isCanceled())
                throw new org.eclipse.core.runtime.OperationCanceledException();
            ComfortTreeTypeSystem.underLinkLock(resource, queryLock, () ->
            {
                BslQueryResultTypes.refresh(resource, method, status -> {}, recompute);
                return true;
            });
        }
    }

    private static final java.util.Map<Method, Long> EXTERNAL_RETURN_REVISIONS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Обновление общей модели потребителя «см.» после изменения рассчитанного возврата цели. */
    static boolean refreshDocReturnConsumer(Resource resource, URI consumer)
    {
        if (resource == null || org.eclipse.swt.widgets.Display.getCurrent() != null
            || resource.getContents().isEmpty() || !(resource.getContents().get(0) instanceof Module module)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(module)
            || !(peekTreeTypeSystem() instanceof ComfortTreeTypeSystem tree))
        {
            return false;
        }
        Method found = null;
        for (Method candidate : module.allMethods())
            if (consumer.equals(EcoreUtil.getURI(candidate)))
            {
                found = candidate;
                break;
            }
        if (found == null)
        {
            return false;
        }
        Method method = found;
        Object lock = Global.invoke(resource, "getLock"); //$NON-NLS-1$
        if (lock == null)
        {
            return false;
        }
        boolean[] updated = { false };
        ComfortTreeTypeSystem.underLinkLock(resource, lock, () ->
        {
            updated[0] = tree.recomputeDependent(module, method, CancelIndicator.NullImpl);
            if (updated[0])
                BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
            return updated[0];
        });
        if (updated[0])
            BslDocCommentComputedTypes.bumpTypesGeneration();
        return updated[0];
    }

    /** Спросить возвраты вызовов в присваиваниях выбранной переменной, без открытия их подсказок. */
    static void requestVariableReturnSources(Resource resource, Variable variable, Method method, int offset,
        String variableKey)
    {
        if (method == null || !(resource instanceof org.eclipse.xtext.resource.XtextResource xtext)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(method)
            || !(peekTreeTypeSystem() instanceof ComfortTreeTypeSystem tree))
            return;
        Object lock = Global.invoke(resource, "getLock");
        if (lock == null)
            return;
        ComfortTreeTypeSystem.underLinkLock(resource, lock, () ->
        {
            var dfa = xtext.getResourceServiceProvider().get(DynamicFeatureAccessComputer.class);
            var owner = (com._1c.g5.v8.dt.bsl.model.resource.owner.IBslOwnerComputerService)
                service(tree, "ownerComputerService");
            if (dfa == null || owner == null)
                return false;
            java.util.Map<String, FunctionReturnTypes.Target> targets = new java.util.LinkedHashMap<>();
            for (var statement : EcoreUtil2.getAllContentsOfType(method,
                com._1c.g5.v8.dt.bsl.model.SimpleStatement.class))
            {
                var node = NodeModelUtils.findActualNodeFor(statement);
                if (node == null || node.getOffset() > offset || statement.getRight() == null
                    || !(statement.getLeft() instanceof StaticFeatureAccess left)
                    || left.getName() == null || !left.getName().equalsIgnoreCase(variable.getName()))
                    continue;
                Set<Invocation> calls = new java.util.LinkedHashSet<>();
                if (statement.getRight() instanceof Invocation invocation)
                    calls.add(invocation);
                calls.addAll(EcoreUtil2.getAllContentsOfType(statement.getRight(), Invocation.class));
                for (Invocation invocation : calls)
                {
                    if (invocation.getMethodAccess() == null)
                        continue;
                    for (FeatureEntry entry : com._1c.g5.v8.dt.bsl.util.BslUtil.getRealObject(
                        invocation.getMethodAccess(), method, dfa, owner, true))
                    {
                        FunctionReturnTypes.Target target = FunctionReturnTypes.targetOf(entry.getFeature(), 0);
                        if (target != null && target.exported() != null)
                            targets.put(target.key(), target);
                    }
                }
            }
            FunctionReturnTypes.rememberVariableSources(variableKey, targets.values());
            if (!targets.isEmpty())
                org.eclipse.swt.widgets.Display.getDefault().asyncExec(() ->
                    targets.values().forEach(target -> FunctionReturnTypes.requestFunction(target, false)));
            return true;
        });
    }

    /** Обновляет штатные состояния вызывающего метода после уточнения внешнего возврата. */
    static void refreshExternalFunctionTypes(Resource resource, int offset)
    {
        refreshExternalFunctionTypes(resource, offset, CancelIndicator.NullImpl);
    }

    private static void refreshExternalFunctionTypes(Resource resource, int offset, CancelIndicator cancellation)
    {
        if (cancellation.isCanceled())
            throw new org.eclipse.core.runtime.OperationCanceledException();
        long revision = FunctionReturnTypes.semanticRevision();
        if (revision == 0 || org.eclipse.swt.widgets.Display.getCurrent() != null
            || !(resource instanceof org.eclipse.xtext.resource.XtextResource xtext)
            || !(BslNodeLookup.rootAst(resource) instanceof Module module)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(module)
            || !(peekTreeTypeSystem() instanceof ComfortTreeTypeSystem tree))
            return;
        Object lock = Global.invoke(resource, "getLock");
        if (lock == null)
            return;
        Method selected = offset < 0 ? null : EcoreUtil2.getContainerOfType(
            BslNodeLookup.containedAt(new org.eclipse.xtext.resource.EObjectAtOffsetHelper(), xtext, offset), Method.class);
        boolean[] changed = { false };
        ComfortTreeTypeSystem.underLinkLock(resource, lock, () ->
        {
            List<Method> refreshed = new ArrayList<>();
            var dfa = xtext.getResourceServiceProvider().get(DynamicFeatureAccessComputer.class);
            var owner = (com._1c.g5.v8.dt.bsl.model.resource.owner.IBslOwnerComputerService)
                service(tree, "ownerComputerService");
            if (dfa == null || owner == null)
                return false;
            for (Method method : new ArrayList<>(module.allMethods()))
            {
                if (cancellation.isCanceled())
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                if (offset >= 0 && method != selected
                    || Long.valueOf(revision).equals(EXTERNAL_RETURN_REVISIONS.get(method)))
                    continue;
                boolean affected = false;
                for (Invocation invocation : EcoreUtil2.getAllContentsOfType(method, Invocation.class))
                {
                    if (cancellation.isCanceled())
                        throw new org.eclipse.core.runtime.OperationCanceledException();
                    if (invocation.getMethodAccess() == null
                        || !FunctionReturnTypes.hasSemanticReturnName(invocation.getMethodAccess().getName()))
                        continue;
                    for (FeatureEntry entry : com._1c.g5.v8.dt.bsl.util.BslUtil.getRealObject(
                        invocation.getMethodAccess(), method, dfa, owner, true))
                    {
                        if (cancellation.isCanceled())
                            throw new org.eclipse.core.runtime.OperationCanceledException();
                        if (entry.getFeature() instanceof com._1c.g5.v8.dt.mcore.Method exported
                            && !FunctionReturnTypes.semanticReturnTypes(exported).isEmpty())
                            affected = true;
                    }
                }
                if (affected && !tree.recomputeDependent(module, method, cancellation))
                {
                    if (cancellation.isCanceled())
                        throw new org.eclipse.core.runtime.OperationCanceledException();
                    continue;
                }
                if (cancellation.isCanceled())
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                changed[0] |= affected;
                refreshed.add(method);
            }
            if (cancellation.isCanceled())
                throw new org.eclipse.core.runtime.OperationCanceledException();
            if (changed[0])
                BslDocCommentComputedTypes.rememberModuleReturnTypes(module);
            for (Method method : refreshed)
                EXTERNAL_RETURN_REVISIONS.put(method, revision);
            return true;
        });
        if (changed[0])
            BslDocCommentComputedTypes.bumpTypesGeneration();
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
        refreshQueryTypesForAssist(resource, offset, onRecompute);
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
            if (whole)
            {
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
                }
                ComfortTreeTypeSystem.StaleTypes.clear(resource);
                BslDocCommentComputedTypes.bumpTypesGeneration();
                return;
            }
            Method method = EcoreUtil2.getContainerOfType(
                BslNodeLookup.containedAt(new org.eclipse.xtext.resource.EObjectAtOffsetHelper(), xtext, offset),
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
     * расчёта. См. {@link #underNonInterruptableBm}.
     */
    static void installTypeSystemNonInterruptable(BslTreeTypeSystem tree, Module module)
    {
        underNonInterruptableBm(tree, module, () ->
        {
            tree.installTypeSystem(module, CancelIndicator.NullImpl);
            return null;
        });
    }

    /**
     * Наш вызов штатного {@code installTypeSystem} (полного или частичного) под непрерываемой
     * операцией BM — см. {@link #installTypeSystemNonInterruptable}. Непрерываемую операцию берём
     * только внутри транзакции: вне её служебная операция может уже идти, и транзакции ForkJoin
     * попали бы внутрь неё. Поэтому поток без транзакции (фоновый пересчёт для автодополнения)
     * сначала открывает свою: она дожидается служебной операции штатно, а пока открыта —
     * служебная операция не начнётся (зависание при запуске сравнения, issue 1015).
     */
    static <T> T underNonInterruptableBm(BslTreeTypeSystem tree, Module module, java.util.function.Supplier<T> work)
    {
        IBmModelManager models = (IBmModelManager)service(tree, "bmModelManager"); //$NON-NLS-1$
        IProject project = projectOf(module);
        IBmModel model = models != null && project != null ? models.getModel(project) : null;
        BmPlatform platform = models != null ? models.getBmPlatform() : null;
        Object engine = model != null ? Global.invoke(model, "getEngine") : null; //$NON-NLS-1$
        boolean inTransaction = engine != null
            && Global.invoke(engine, "getCurrentTransaction") instanceof IBmTransaction; //$NON-NLS-1$
        if (platform == null || model == null)
            return work.get();
        if (inTransaction)
            return nonInterruptable(platform, work);
        RuntimeException[] failure = { null };
        T result = model.executeReadonlyTask(new AbstractBmTask<T>("comfort.typeRecompute") //$NON-NLS-1$
        {
            @Override
            public T execute(IBmTransaction transaction, IProgressMonitor monitor)
            {
                try
                {
                    return nonInterruptable(platform, work);
                }
                catch (RuntimeException e)
                {
                    // Отмену и прочие исключения расчёта отдаём вызывающему как есть, без обёртки BM.
                    failure[0] = e;
                    return null;
                }
            }
        }, true);
        if (failure[0] != null)
            throw failure[0];
        return result;
    }

    private static <T> T nonInterruptable(BmPlatform platform, java.util.function.Supplier<T> work)
    {
        Object token = platform.beginNonInterruptableOperation();
        try
        {
            return work.get();
        }
        finally
        {
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
        String templateDescription = BslTemplateTypes.propertyDescription(property);
        if (templateDescription != null)
            return templateDescription;
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
        var leaf = BslNodeLookup.leafAt(node, offset);
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
        var leaf = BslNodeLookup.leafAt(node, offset);
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
        return sideCommentReturnSection(invocation, module);
    }

    private static BslDocumentationComment.ReturnSection sideCommentReturnSection(Invocation invocation, Module module)
    {
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
     * Есть ли у присваивания боковой комментарий с типом возврата. Комментарий разбирается тем же
     * путём, которым EDT получает типы комментария ({@code getCommentAfterObject} по узлу блока +
     * {@code parseTemplateComment}), поэтому тип, записанный EDT в состояние переменной, и есть
     * «голый» тип этого комментария.
     */
    private static boolean hasAssignmentCommentType(Expression value, Module module)
    {
        return !assignmentCommentTypeNames(value, module).isEmpty();
    }

    /** Имена типов из секции возврата бокового комментария присваивания. */
    private static List<String> assignmentCommentTypeNames(Expression value, Module module)
    {
        try
        {
            if (value == null)
                return List.of();
            SimpleStatement statement = EcoreUtil2.getContainerOfType(value, SimpleStatement.class);
            Block block = statement == null ? null : EcoreUtil2.getContainerOfType(statement, Block.class);
            ICompositeNode blockNode = block == null ? null : NodeModelUtils.findActualNodeFor(block);
            if (statement == null || blockNode == null)
                return List.of();
            boolean oldFormat = oldCommentFormat(module);
            List<String> lines = TypeSystemUtil.getCommentAfterObject(statement, blockNode, oldFormat);
            if (lines == null || lines.isEmpty())
                return List.of();
            BslDocumentationComment comment = BslCommentUtils.parseTemplateComment(lines, oldFormat);
            BslDocumentationComment.ReturnSection section = comment == null ? null : comment.getReturnSection();
            if (section == null || section.getReturnTypes() == null)
                return List.of();
            List<String> names = new ArrayList<>();
            for (var typeSection : section.getReturnTypes())
                for (var definition : typeSection.getTypeDefinitions())
                {
                    String name = definition.getTypeName();
                    if (name != null && !name.isBlank())
                        names.add(name.trim());
                }
            return List.copyOf(names);
        }
        catch (Throwable ignored)
        {
            return List.of();
        }
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

    /** Дополняет именно страницу штатной подсказки; описание уже прочитано в Pass. */
    static void completePropertyHover(Object info, EObject object, ITextViewer viewer)
    {
        BslExpressionDetails.request(info, object, viewer, -1);
        if (object == null || viewer == null
            || !(viewer.getDocument() instanceof IXtextDocument document))
            return;
        try
        {
            // Расчёты типов зависят от флажка проекта; исправление ключа платформы — всегда.
            Property property = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
                Property found = propertyForHint(object, resource);
                return found != null ? found : variablePropertyForHint(object, resource, -1);
            });
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
        if (resource == null || resource.getContents().isEmpty())
            return null;
        boolean extended = Boolean.TRUE.equals(isExtendedTypesEnabled(resource));
        Property first = null;
        for (EObject member : members)
        {
            if (!(member instanceof Property property))
                continue;
            if (!extended && !DynamicFeatureAccessComputerHook.isStringKey(property))
                continue;
            if (first == null)
                first = property;
            if (propertyDescription(property, resource.getContents().get(0)) != null)
                return property;
        }
        return first;
    }

    /** Тип переменной в месте использования — тот же getVariableTypesAtOffset, что у страницы EDT. */
    private static Property variablePropertyForHint(EObject object, Resource resource, int offset)
    {
        if (object == null || resource == null || resource.getContents().isEmpty()
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0)))
            return null;
        List<? extends EObject> members = object instanceof com._1c.g5.v8.dt.bsl.model.ProposalElement proposal
            ? proposal.getList() : object instanceof StaticFeatureAccess access
                ? access.getFeatureEntries().stream().map(FeatureEntry::getFeature).toList() : List.of(object);
        for (EObject member : members)
        {
            int position = offset;
            if (member instanceof com._1c.g5.v8.dt.bsl.model.FantomVariableOccurrence occurrence)
            {
                position = occurrence.getOffset();
                member = occurrence.getVariable();
            }
            if (!(member instanceof Variable variable) || position < 0)
                continue;
            Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
            Module module = EcoreUtil2.getContainerOfType(variable, Module.class);
            Environments environments = method != null ? method.environments()
                : module != null ? module.environments() : Environments.ALL;
            List<TypeItem> types = TypeSystemUtil.getVariableTypesAtOffset(variable, position, environments);
            if (types.stream().anyMatch(type -> type instanceof Type collection
                && (type instanceof com._1c.g5.v8.dt.bsl.model.ExtendedCollectionType
                    || "Array".equals(collection.getName())) //$NON-NLS-1$
                && collection.getCollectionElementTypes() != null
                && !collection.getCollectionElementTypes().allTypes().isEmpty()))
                return BslLiteralValues.valueDescription(variable.getName(), types);
        }
        return null;
    }

    /** Как EDT getDocByDerivedProperty, но прокси типов разрешаются в ресурсе редактора. */
    private static Object propertyTypeGroups(Property property, Object page, Object value, Resource resource)
        throws Exception
    {
        Object original = Global.invoke(value, "getGroups"); //$NON-NLS-1$
        if (resource == null || resource.getContents().isEmpty()
            || (!DynamicFeatureAccessComputerHook.isStringKey(property)
                && !BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0))))
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
        // Страница уже могла получить вложенный тип, но здесь её группы строятся заново.
        // Типы берём из состояния EDT: в объявлении параметра это оба типа док-комментария.
        property = BslFormDataTypes.documentationProperty(property, resource.getContents().get(0));
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

    /** Штатная ссылка свойства не раскрывает элементы ExtendedCollectionType. */
    private static Object collectionTypeReference(TypeItem item, Object reference, Object provider,
        Resource resource, boolean russian, Object version) throws Exception
    {
        if (!(item instanceof Type collection)
            || !(item instanceof com._1c.g5.v8.dt.bsl.model.ExtendedCollectionType
                || "Array".equals(collection.getName())) //$NON-NLS-1$
            || collection.getCollectionElementTypes() == null
            || collection.getCollectionElementTypes().allTypes().stream()
                .anyMatch(element -> "Arbitrary".equals(McoreUtil.getTypeName(element)))) //$NON-NLS-1$
            return reference;
        List<String> elements = new ArrayList<>();
        for (TypeItem element : collection.getCollectionElementTypes().allTypes())
        {
            TypeItem resolved = (TypeItem)EcoreUtil.resolve(element, resource);
            Object elementReference = Global.invoke(provider, "convertTypeItemToReference", //$NON-NLS-1$
                resolved, resource, Boolean.valueOf(russian), version, Boolean.FALSE);
            if (elementReference != null)
                elements.add(typeReferenceHtml(elementReference));
        }
        if (elements.isEmpty())
            return reference;
        String html = typeReferenceHtml(reference) + (russian ? " из " : " of ") //$NON-NLS-1$ //$NON-NLS-2$
            + String.join(", ", elements); //$NON-NLS-1$
        Class<?> referenceClass = Class.forName(
            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.PlatformReference", //$NON-NLS-1$
            true, reference.getClass().getClassLoader());
        // PageUtil оставляет HTML без внешней ссылки при href=null: ссылки массива и элементов независимы.
        return referenceClass.getConstructor(String.class, String.class, String.class).newInstance(null, html, null);
    }

    static String typeReferenceHtml(Object reference) throws Exception
    {
        ClassLoader loader = reference.getClass().getClassLoader();
        Class<?> pageUtil = Class.forName(
            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.PageUtil", true, loader); //$NON-NLS-1$
        Class<?> referenceType = Class.forName(
            "com._1c.g5.v8.dt.internal.bsl.ui.documentation.page.IPageReference", true, loader); //$NON-NLS-1$
        java.lang.reflect.Method render = pageUtil.getDeclaredMethod(
            "generateReferenceShortHtml", referenceType, String[].class); //$NON-NLS-1$
        render.setAccessible(true);
        return (String)render.invoke(null, reference, new String[0]);
    }

    /**
     * Свойство под указателем. {@code Структура.Поле} — динамическое обращение: ссылки на
     * свойство в нём нет, и {@code EObjectAtOffsetHelper.resolveCrossReferencedElementAt}
     * даёт {@code null}. Обращение с именем под указателем разрешается, как
     * в штатной подсказке: {@code DynamicFeatureAccessComputer.resolveDynamicFeatureAccess(…, true)}.
     * Из нескольких одноимённых свойств (несколько типов) — первое, у которого есть описание.
     */
    static Property propertyAt(Resource resource, int offset)
    {
        if (!(resource instanceof org.eclipse.xtext.resource.XtextResource xtext))
            return null;
        EObject object = BslNodeLookup.resolveContainedElementAt(xtext, offset);
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
            Global.tempLog("structure-property-definition", "resolve property=" + property.getName()
                + " method=" + source.getMethodName() + " localOffset=" + source.getLocalOffset()
                + " target=" + exact);
            if (exact == null)
                return new PropertySourceTarget(EcoreUtil.getURI(method), -1, 0, property.getName());
            return exact.uri().equals(EcoreUtil.getURI(method))
                ? new PropertySourceTarget(exact.uri(), exact.offset(), exact.length(), property.getName()) : exact;
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
                var leaf = BslNodeLookup.leafAt(node, keyOffset);
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
        Global.tempLog("structure-property-definition", "open target=" + target);
        IResourceServiceProvider provider = IResourceServiceProvider.Registry.INSTANCE
            .getResourceServiceProvider(target.uri().trimFragment());
        org.eclipse.xtext.ui.editor.IURIEditorOpener opener = provider == null ? null
            : provider.get(org.eclipse.xtext.ui.editor.IURIEditorOpener.class);
        if (opener == null)
            return;
        if (target.offset() < 0 && target.propertyName() == null)
        {
            opener.open(target.uri(), true);
            return;
        }
        // Открываем модуль без адреса метода, затем выделяем само свойство.
        // EDT возвращает также составные редакторы: получаем текстовую страницу как в CustomURIEditorOpener.
        org.eclipse.ui.IEditorPart part = opener.open(target.uri().trimFragment(), true);
        org.eclipse.xtext.ui.editor.XtextEditor textEditor =
            org.eclipse.xtext.ui.editor.utils.EditorUtils.getXtextEditor(part);
        if (textEditor != null)
        {
            PropertySourceTarget current = target;
            if (target.propertyName() != null)
            {
                List<PropertySourceTarget> places = ContentAssistSessionReloader.readOnlyPeekAst(
                    textEditor.getDocument(), resource -> currentPropertySources(resource, target));
                Global.tempLog("structure-property-definition", "current places=" + places + " target=" + target);
                if (places == null)
                    return;
                if (places.isEmpty())
                {
                    opener.open(target.uri(), true);
                    return;
                }
                current = choosePropertySource(textEditor, places, target.propertyName());
                if (current == null)
                    return;
            }
            textEditor.selectAndReveal(current.offset(), current.length());
            Global.tempLog("structure-property-definition", "selected="
                + textEditor.getSelectionProvider().getSelection() + " target=" + target);
        }
    }

    /** Читаем текущий AST открытого модуля, а не смещение из сохранённой ссылки потребителя. */
    private static List<PropertySourceTarget> currentPropertySources(Resource resource, PropertySourceTarget target)
    {
        EObject object = resource.getEObject(target.uri().fragment());
        Method method = EcoreUtil2.getContainerOfType(object, Method.class);
        Module module = EcoreUtil2.getContainerOfType(method, Module.class);
        ICompositeNode node = method == null ? null : NodeModelUtils.findActualNodeFor(method);
        if (module == null || node == null)
            return null;
        List<PropertySourceTarget> result = new ArrayList<>();
        COMMENT_FIELD_PARSE.set(Boolean.TRUE);
        try
        {
            BslDocumentationComment comment = parseMethodComment(method, module);
            if (comment != null)
            {
                if (comment.getParametersSection() != null)
                    for (TypeSection.FieldDefinition parameter : comment.getParametersSection().getParameterDefinitions())
                        for (TypeSection section : parameter.getTypeSections())
                            currentCommentSources(section, comment.getLineGlobalOffsets(), node, target, result);
                if (comment.getReturnSection() != null)
                    for (TypeSection section : comment.getReturnSection().getReturnTypes())
                        currentCommentSources(section, comment.getLineGlobalOffsets(), node, target, result);
            }
            Global.tempLog("structure-property-definition", "comment property=" + target.propertyName()
                + " method=" + method.getName() + " places=" + result);
        }
        finally
        {
            COMMENT_FIELD_PARSE.remove();
        }
        if (!result.isEmpty())
            return result;
        Set<Variable> returned = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (com._1c.g5.v8.dt.bsl.model.ReturnStatement exit : EcoreUtil2.getAllContentsOfType(method,
            com._1c.g5.v8.dt.bsl.model.ReturnStatement.class))
            if (exit.getExpression() instanceof StaticFeatureAccess access)
            {
                Variable variable = sourceVariable(access);
                if (variable != null)
                    returned.add(variable);
            }
        for (Invocation call : EcoreUtil2.getAllContentsOfType(method, Invocation.class))
            if (target.propertyName().equalsIgnoreCase(insertedKey(call))
                && call.getMethodAccess() instanceof DynamicFeatureAccess access
                && access.getSource() instanceof StaticFeatureAccess receiver
                && returned.contains(sourceVariable(receiver)))
            {
                ICompositeNode key = NodeModelUtils.findActualNodeFor(call.getParams().get(0));
                if (key != null)
                {
                    int start = key.getText().toLowerCase(Locale.ROOT)
                        .indexOf(target.propertyName().toLowerCase(Locale.ROOT));
                    if (start >= 0)
                        result.add(new PropertySourceTarget(target.uri(), key.getTotalOffset() + start,
                            target.propertyName().length()));
                }
            }
        Global.tempLog("structure-property-definition", "body property=" + target.propertyName()
            + " method=" + method.getName() + " returnedVariables=" + returned.size() + " places=" + result);
        return result;
    }

    private static void currentCommentSources(TypeSection section, int[] lines, ICompositeNode methodNode,
        PropertySourceTarget target, List<PropertySourceTarget> result)
    {
        for (var definition : section.getTypeDefinitions())
            for (TypeSection.FieldDefinition field : definition.getFieldDefinitionExtension())
            {
                int line = field.getLineNumber();
                if (target.propertyName().equalsIgnoreCase(field.getName())
                    && lines != null && line >= 0 && line < lines.length)
                {
                    int offset = methodNode.getTotalOffset() + lines[line] + field.getNameOffset();
                    if (methodNode.getRootNode().getText().regionMatches(true, offset, target.propertyName(),
                        0, target.propertyName().length()))
                        result.add(new PropertySourceTarget(target.uri(), offset, target.propertyName().length()));
                }
                for (TypeSection nested : field.getTypeSections())
                    currentCommentSources(nested, lines, methodNode, target, result);
            }
    }

    private static PropertySourceTarget choosePropertySource(org.eclipse.xtext.ui.editor.XtextEditor editor,
        List<PropertySourceTarget> places, String name)
    {
        if (places.size() == 1)
            return places.get(0);
        org.eclipse.ui.dialogs.ElementListSelectionDialog dialog =
            new org.eclipse.ui.dialogs.ElementListSelectionDialog(editor.getSite().getShell(),
                new org.eclipse.jface.viewers.LabelProvider()
                {
                    @Override
                    public String getText(Object element)
                    {
                        PropertySourceTarget place = (PropertySourceTarget)element;
                        try
                        {
                            var document = editor.getDocument();
                            int line = document.getLineOfOffset(place.offset());
                            var region = document.getLineInformation(line);
                            return "Строка " + (line + 1) + ": "
                                + document.get(region.getOffset(), region.getLength()).trim();
                        }
                        catch (org.eclipse.jface.text.BadLocationException failure)
                        {
                            Global.tempLog("structure-property-definition", "choice failed=" + failure);
                            return "Смещение " + place.offset();
                        }
                    }
                });
        dialog.setTitle(Global.withPluginWindowTitle("Определение свойства"));
        dialog.setMessage("Выберите место определения свойства «" + name + "»:");
        dialog.setElements(places.toArray());
        dialog.setMultipleSelection(false);
        if (dialog.open() != org.eclipse.jface.window.Window.OK)
            return null;
        return dialog.getFirstResult() instanceof PropertySourceTarget selected ? selected : null;
    }

    /** Куда вести ссылку на имя свойства; {@code offset < 0} — выделить сам объект по URI. */
    record PropertySourceTarget(URI uri, int offset, int length, String propertyName)
    {
        PropertySourceTarget(URI uri, int offset, int length)
        {
            this(uri, offset, length, null);
        }
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
        BslExpressionDetails.request(info, null, viewer, offset);
        if (viewer == null || !(viewer.getDocument() instanceof IXtextDocument document))
            return;
        try
        {
            EObject object = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
                if (!isExtendedTypesEnabled(resource))
                    return null;
                Property property = propertyAt(resource, offset);
                if (property != null)
                    return property;
                var helper = resource.getResourceServiceProvider()
                    .get(org.eclipse.xtext.resource.EObjectAtOffsetHelper.class);
                EObject referenced = helper.resolveCrossReferencedElementAt(resource, offset);
                Property variable = variablePropertyForHint(referenced, resource, offset);
                if (variable != null)
                    return variable;
                EObject expression = BslNodeLookup.containedAt(helper, resource, offset);
                return variablePropertyForHint(expression, resource, offset);
            });
            completePropertyHover(info, object, viewer);
        }
        catch (Throwable failure)
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
                {
                    dropRepeatedTypes(ref.getTypes());
                    addMissing(ref.getTypes(), commentTypes);
                }
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
     * Оставляет по одному экземпляру одинаковых типов значения ключа. Штатный расчёт
     * {@code Вставить("Ключ", Объект.Реквизит)} при нескольких типах объекта кладёт тип реквизита
     * от каждого: у {@code ДанныеФайла.ПодписанЭП} — «Булево» 13 раз, по справочнику
     * присоединённых файлов, и так в подсказке при наведении и в разделе возврата «см.».
     * Одинаковым считается тот же объект, та же ссылка-заглушка или то же имя у типа без
     * выведенных свойств; структуры с полями различаются полями и не сворачиваются.
     */
    private static void dropRepeatedTypes(List<TypeItem> types)
    {
        if (types.size() < 2)
            return;
        Set<Object> seen = new HashSet<>();
        try
        {
            types.removeIf(item -> !seen.add(sameTypeKey(item)));
        }
        catch (UnsupportedOperationException e)
        {
            // Список EDT неизменяемый — оставляем как есть.
        }
    }

    private static Object sameTypeKey(TypeItem item)
    {
        if (item.eIsProxy())
            return ((org.eclipse.emf.ecore.InternalEObject)item).eProxyURI();
        String name = McoreUtil.getTypeName(item);
        if (name == null || item instanceof Type type && type.getContextDef() != null
            && type.getContextDef().getProperties().stream().anyMatch(DerivedProperty.class::isInstance))
            return new IdentityKey(item);
        return name;
    }

    /** Ключ по ссылке на объект: {@code TypeItem} может переопределять {@code equals}. */
    private record IdentityKey(Object item)
    {
        @Override
        public boolean equals(Object other)
        {
            return other instanceof IdentityKey key && key.item == item;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(item);
        }
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
        protected List<TypeItem> _compute(com._1c.g5.v8.dt.mcore.Method method, Environments environments)
        {
            List<TypeItem> computed = FunctionReturnTypes.semanticReturnTypes(method);
            return computed.isEmpty() ? super._compute(method, environments) : computed;
        }

        @Override
        public java.util.List<TypeItem> computeTypes(EObject object, Environments environments)
        {
            fillMockExportPropertyTypes(object);
            List<TypeItem> types = arrayExpressionTypes(super.computeTypes(object, environments), object, environments);
            // Один и тот же тип приходит из нескольких состояний EDT: у объединения и у сужения
            // разные экземпляры с одним именем. Сворачиваем здесь — до разрешения членов.
            if (types != null && types.size() > 1)
            {
                Set<Object> distinct = new HashSet<>();
                boolean repeated = !types.stream().allMatch(item -> distinct.add(sameTypeKey(item)));
                if (repeated)
                {
                    types = new ArrayList<>(types);
                    dropRepeatedTypes(types);
                }
            }
            return types;
        }
    }

    /**
     * Сужение типа переменной или выражения с точкой условием {@code Если ТипЗнч(А.Б) = Тип("Х") Тогда} (#1000)
     * и таким же условием тернарного оператора {@code ?(ТипЗнч(А.Б) = Тип("Х"), А.Б.Член(), …)}.
     * EDT сужает так только переменную ({@code CreatorTreeState.createTypesByPredicates}: тип из
     * {@code typeScope} по имени, разделённому точками); для {@code А.Б} внутри ветки берём тот же тип вместо
     * вычисленных. Сравнение связано с корнем условия только через «И»; присваивание пути, его начала
     * или корневой переменной в ветке до обращения сужение отменяет.
     * <p>Самой переменной ({@code ТипЗнч(Х)}) тип сужает и EDT, но готовое состояние, которое читает подсказка
     * при наведении, приходит из LIGHT ({@code TypeSystemUtil.getVariableTypesAtOffset} предпочитает LIGHT) —
     * без состояния сужения, объединением с типом присваивания. Поэтому внутри ветки оставляем проверенный тип
     * и для обращения к переменной.
     * <p>Тернарный оператор в модели — {@link Invocation} с именем метода {@code "?"} и тремя параметрами:
     * условие, ветка «Тогда», ветка «Иначе». Сужается только вторая — как ветка «Тогда»: ветку «Иначе»
     * не сужает и {@code Если} (исключение типа из набора — отдельное поведение EDT, здесь не повторяется).
     */
    private static final class TypeOfBranchNarrowing
    {
        static List<TypeItem> narrow(List<TypeItem> types, Expression access)
        {
            try
            {
                EObject child = access;
                for (EObject container = access.eContainer(); container != null; container = container.eContainer())
                {
                    if (container instanceof com._1c.g5.v8.dt.bsl.model.Conditional branch
                        && branch.getPredicate() != child)
                    {
                        String typeName = typeNameFor(branch.getPredicate(), access);
                        if (typeName != null)
                            return narrowed(types, access, branch, typeName);
                    }
                    else if (container instanceof Invocation ternary && isTernary(ternary)
                        && ternary.getParams().get(1) == child)
                    {
                        String typeName = typeNameFor(ternary.getParams().get(0), access);
                        if (typeName != null)
                            return narrowed(types, access, null, typeName);
                    }
                    child = container;
                }
            }
            catch (RuntimeException | LinkageError failure)
            {
            }
            return types;
        }

        /** Тернарный оператор {@code ?(условие, тогда, иначе)} — {@link Invocation} с методом {@code "?"}. */
        private static boolean isTernary(Invocation invocation)
        {
            return invocation.getParams().size() == 3
                && invocation.getMethodAccess() instanceof StaticFeatureAccess method
                && "?".equals(method.getName()); //$NON-NLS-1$
        }

        /** @param branch ветка {@code Если} либо {@code null} — ветка «Тогда» тернарного оператора */
        private static List<TypeItem> narrowed(List<TypeItem> types, Expression access,
            com._1c.g5.v8.dt.bsl.model.Conditional branch, String typeName)
        {
            if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(access)
                || branch != null && reassigned(branch, access))
                return types;
            TypeItem type = typeByName(access, typeName);
            return type == null ? types : List.of(type);
        }

        /** Имя типа из сравнения {@code ТипЗнч(путь) = Тип("…")} в условии, связанного с корнем только через «И». */
        private static String typeNameFor(Expression predicate, Expression access)
        {
            if (!(predicate instanceof BinaryExpression binary))
                return null;
            if (binary.getOperation() == BinaryOperation.AND)
            {
                String left = typeNameFor(binary.getLeft(), access);
                return left != null ? left : typeNameFor(binary.getRight(), access);
            }
            if (binary.getOperation() != BinaryOperation.EQ)
                return null;
            String name = typeLiteral(binary.getRight());
            if (name != null && samePath(typeOfArgument(binary.getLeft()), access))
                return name;
            name = typeLiteral(binary.getLeft());
            return name != null && samePath(typeOfArgument(binary.getRight()), access) ? name : null;
        }

        /** Присваивание в ветке до обращения: самому пути, его началу или корневой переменной. */
        private static boolean reassigned(com._1c.g5.v8.dt.bsl.model.Conditional branch, Expression access)
        {
            ICompositeNode accessNode = NodeModelUtils.findActualNodeFor(access);
            if (accessNode == null)
                return true;
            for (Statement statement : branch.getStatements())
            {
                List<SimpleStatement> assignments = new ArrayList<>();
                if (statement instanceof SimpleStatement simple)
                    assignments.add(simple);
                assignments.addAll(EcoreUtil2.getAllContentsOfType(statement, SimpleStatement.class));
                for (SimpleStatement assignment : assignments)
                {
                    ICompositeNode node = NodeModelUtils.findActualNodeFor(assignment);
                    if (assignment.getRight() == null || node == null || node.getOffset() >= accessNode.getOffset())
                        continue;
                    for (Expression prefix = access; prefix != null;
                        prefix = prefix instanceof DynamicFeatureAccess dynamic ? dynamic.getSource() : null)
                        if (samePath(assignment.getLeft(), prefix))
                            return true;
                }
            }
            return false;
        }

        /** Один и тот же путь {@code А.Б.В}: та же переменная в корне и те же имена свойств. */
        private static boolean samePath(Expression left, Expression right)
        {
            if (left instanceof DynamicFeatureAccess first && right instanceof DynamicFeatureAccess second)
                return first.getName() != null && first.getName().equalsIgnoreCase(second.getName())
                    && samePath(first.getSource(), second.getSource());
            if (!(left instanceof StaticFeatureAccess first) || !(right instanceof StaticFeatureAccess second))
                return false;
            Variable variable = sourceVariable(first);
            return variable != null ? variable == sourceVariable(second)
                : first.getName() != null && first.getName().equalsIgnoreCase(second.getName())
                    && sourceVariable(second) == null;
        }

        private static Expression typeOfArgument(Expression expression)
        {
            return namedCall(expression, "ТипЗнч", "TypeOf") ? ((Invocation)expression).getParams().get(0) : null; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String typeLiteral(Expression expression)
        {
            if (!namedCall(expression, "Тип", "Type") //$NON-NLS-1$ //$NON-NLS-2$
                || !(((Invocation)expression).getParams().get(0) instanceof StringLiteral literal)
                || literal.getLines().size() != 1)
                return null;
            String line = literal.getLines().get(0).trim();
            if (line.length() < 2 || !line.startsWith("\"") || !line.endsWith("\"")) //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            String name = line.substring(1, line.length() - 1).trim();
            return name.isEmpty() || name.contains("\"") ? null : name; //$NON-NLS-1$
        }

        private static boolean namedCall(Expression expression, String russian, String english)
        {
            return expression instanceof Invocation invocation && invocation.getParams().size() == 1
                && invocation.getMethodAccess() instanceof StaticFeatureAccess method
                && (russian.equalsIgnoreCase(method.getName()) || english.equalsIgnoreCase(method.getName()));
        }

        /** Как EDT: {@code typeScope.getSingleElement(QualifiedName.create(имя.split("\\.")))}. */
        private static TypeItem typeByName(EObject context, String typeName)
        {
            if (!(context.eResource() instanceof XtextResource resource))
                return null;
            IScopeProvider scopes = resource.getResourceServiceProvider().get(IScopeProvider.class);
            IScope scope = scopes == null ? null : scopes.getScope(context, McorePackage.Literals.TYPE_DESCRIPTION__TYPES);
            var element = scope == null ? null
                : scope.getSingleElement(org.eclipse.xtext.naming.QualifiedName.create(typeName.split("\\."))); //$NON-NLS-1$
            EObject resolved = element == null ? null : EcoreUtil.resolve(element.getEObjectOrProxy(), context);
            return resolved instanceof TypeItem type && !type.eIsProxy() ? type : null;
        }
    }

    /**
     * Проверенный условием тип обращения в точке {@code offset}. Нужен тем, кто читает готовые типы EDT
     * напрямую, минуя {@link InteractiveTypes}: подсказка при наведении получает переменную как
     * {@link Variable} и её тип в режиме без состояния сужения (готовый набор — объединение).
     */
    static List<TypeItem> narrowAtOffset(List<TypeItem> types, XtextResource resource, int offset)
    {
        if (types == null || types.isEmpty() || resource == null || offset < 0)
            return types;
        try
        {
            return narrowFor(types, occurrenceAt(resource, offset));
        }
        catch (RuntimeException | LinkageError failure)
        {
            return types;
        }
    }

    /** Ближайшее выражение в точке: обращение для сужения по условию. */
    private static Expression occurrenceAt(XtextResource resource, int offset)
    {
        var parsed = resource.getParseResult();
        if (parsed == null)
            return null;
        ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(parsed.getRootNode(), offset);
        for (INode node = leaf; node != null; node = node.getParent())
            if (node.getSemanticElement() instanceof Expression expression)
                return expression;
        return null;
    }

    /** Тот же проверенный тип, когда обращение уже разрешено: строка «Родитель» списка автодополнения. */
    static List<TypeItem> narrowFor(List<TypeItem> types, Expression occurrence)
    {
        return types == null || types.isEmpty() || occurrence == null ? types
            : TypeOfBranchNarrowing.narrow(types, occurrence);
    }

    /** Позиция обращения, переданная штатному вычислителю; используется и проверками закрытых модулей. */
    public static List<TypeItem> arrayExpressionTypes(List<TypeItem> types, EObject object, Environments environments)
    {
        InteractiveTypes interactive = INTERACTIVE_TYPES.get();
        if (interactive != null && interactive.nativeRead == 0 && types != null
            && (object instanceof Expression || object instanceof com._1c.g5.v8.dt.bsl.model.Function))
            return interactive.expression(object, environments, types);
        if (object instanceof com._1c.g5.v8.dt.bsl.model.Function function)
            return ColumnRenames.inferReturn(types, function, environments);
        if (object instanceof DynamicFeatureAccess dynamic)
            return TypeOfBranchNarrowing.narrow(types, dynamic);
        if (!(object instanceof StaticFeatureAccess access))
            return types;
        Variable variable = sourceVariable(access);
        ICompositeNode node = NodeModelUtils.findActualNodeFor(access);
        return variable == null || node == null ? types
            : arrayVariableTypes(types, variable, node.getOffset(), environments);
    }

    /** Общий вход для TypesComputer и прямых читателей состояний переменной EDT. */
    public static List<TypeItem> arrayVariableTypes(List<TypeItem> types, Variable variable, int offset,
        Environments environments)
    {
        InteractiveTypes interactive = INTERACTIVE_TYPES.get();
        if (interactive != null && interactive.nativeRead == 0 && types != null && variable != null && offset >= 0)
            return interactive.variable(variable, offset, environments, types);
        return localVariableTypes(types, variable, offset, environments);
    }

    private static List<TypeItem> localVariableTypes(List<TypeItem> types, Variable variable, int offset,
        Environments environments)
    {
        try
        {
            if (types == null || variable == null || offset < 0
                || !BslDocCommentComputedTypes.isExtendedTypesEnabled(variable))
                return types;
            List<TypeItem> comment = BslDocCommentComputedTypes.iteratorCommentTypes(variable);
            if (!comment.isEmpty())
                return filterTypesByCommentMask(types, comment);
            if (types.stream().anyMatch(type -> "Array".equals(McoreUtil.getTypeName(type)))) //$NON-NLS-1$
                types = ArrayElementTypes.infer(types, variable, offset, environments);
            types = CollectionValueTypes.infer(types, variable, offset, environments);
            types = FormItemKinds.infer(types, variable, offset, environments);
            return ColumnRenames.infer(types, variable, offset, environments);
        }
        catch (RuntimeException failure)
        {
            throw failure;
        }
    }

    private static List<TypeItem> filterTypesByCommentMask(List<TypeItem> types, List<TypeItem> comment)
    {
        if (types == null || types.isEmpty())
            return comment != null && !comment.isEmpty() ? comment : List.of();
        if (comment == null || comment.isEmpty())
            return types;
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (TypeItem c : comment)
        {
            if (c == null)
                continue;
            String n = McoreUtil.getTypeName(c);
            String nr = McoreUtil.getTypeNameRu(c);
            if (n != null && !n.isEmpty())
                allowed.add(n.toLowerCase(java.util.Locale.ROOT));
            if (nr != null && !nr.isEmpty())
                allowed.add(nr.toLowerCase(java.util.Locale.ROOT));
        }
        if (allowed.isEmpty())
            return comment;
        List<TypeItem> filtered = new ArrayList<>();
        for (TypeItem t : types)
        {
            if (t == null)
                continue;
            String tn = McoreUtil.getTypeName(t);
            String tnr = McoreUtil.getTypeNameRu(t);
            boolean match = (tn != null && allowed.contains(tn.toLowerCase(java.util.Locale.ROOT)))
                || (tnr != null && allowed.contains(tnr.toLowerCase(java.util.Locale.ROOT)));
            if (match)
                filtered.add(t);
        }
        if (filtered.isEmpty())
            return comment;
        return filtered;
    }

    /**
     * ИР: СобратьДинамическиеСвойстваОбъекта, ветка таблицы/дерева значений:
     * Колонки.Ссылка.Имя = "Начало" и Колонки[Индекс].Имя = НовоеИмя.
     * Как в ИР, собираем новые имена накопительно, не удаляя исходных.
     */
    private static final class ColumnRenames
    {
        private static final BslSourceModelCache PLANS = new BslSourceModelCache();
        private static final BslSourceModelCache BOUND_PLANS = new BslSourceModelCache(true);
        private static final ThreadLocal<Set<EObject>> RETURNING = ThreadLocal.withInitial(
            () -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

        /**
         * Тип возврата функции берётся из готового состояния EDT, без переименований внутри неё:
         * применяем те, что видны в точке каждого «Возврат Переменная».
         */
        static List<TypeItem> inferReturn(List<TypeItem> types, com._1c.g5.v8.dt.bsl.model.Function function,
            Environments environments)
        {
            if (types == null || types.stream().noneMatch(ColumnRenames::isTable)
                || !BslDocCommentComputedTypes.isExtendedTypesEnabled(function))
                return types;
            Set<EObject> returning = RETURNING.get();
            if (!returning.add(function))
                return types;
            try
            {
                for (com._1c.g5.v8.dt.bsl.model.ReturnStatement exit : EcoreUtil2.getAllContentsOfType(function,
                    com._1c.g5.v8.dt.bsl.model.ReturnStatement.class))
                {
                    Variable variable = exit.getExpression() instanceof StaticFeatureAccess access
                        ? sourceVariable(access) : null;
                    ICompositeNode node = NodeModelUtils.findActualNodeFor(exit);
                    if (variable != null && node != null)
                        types = infer(types, variable, node.getOffset(), environments);
                }
            }
            finally
            {
                returning.remove(function);
                if (returning.isEmpty())
                    RETURNING.remove();
            }
            return types;
        }

        /**
         * Состояние переменной из «Перем = Функция(...)» EDT связывает прямо с финальным состоянием
         * возврата функции (CreatorTreeState.getAllDependStateInv), мимо computeTypes.
         */
        private static List<TypeItem> calleeRenames(List<TypeItem> types, SimpleStatement assignment,
            Environments environments)
        {
            if (assignment == null || !(assignment.getRight() instanceof com._1c.g5.v8.dt.bsl.model.Invocation call)
                || call.getMethodAccess() == null)
                return types;
            List<com._1c.g5.v8.dt.bsl.model.FeatureEntry> features =
                call.getMethodAccess() instanceof StaticFeatureAccess access ? access.getFeatureEntries()
                    : call.getMethodAccess() instanceof DynamicFeatureAccess access ? access.getFeatureEntries()
                    : List.of();
            for (com._1c.g5.v8.dt.bsl.model.FeatureEntry entry : features)
                if (entry.getFeature() instanceof com._1c.g5.v8.dt.bsl.model.Function function)
                    types = inferReturn(types, function, environments);
            return types;
        }

        /**
         * Состояние параметра EDT связывает прямо с состоянием аргумента в точке вызова, мимо
         * computeTypes: применяем переименования, видимые у аргумента в каждом вызове из модуля.
         */
        private static List<TypeItem> callerRenames(List<TypeItem> types, com._1c.g5.v8.dt.bsl.model.FormalParam param,
            Method method, Environments environments)
        {
            int index = method.getFormalParams().indexOf(param);
            Module module = EcoreUtil2.getContainerOfType(method, Module.class);
            if (index < 0 || module == null || module.eResource() == null || method.getName() == null)
                return types;
            Set<EObject> returning = RETURNING.get();
            if (!returning.add(param))
                return types;
            try
            {
                java.util.Map<String, List<com._1c.g5.v8.dt.bsl.model.Invocation>> callsByName = PLANS.get(
                    new CallIndexKey(module), module.eResource(), () -> callIndex(module));
                for (com._1c.g5.v8.dt.bsl.model.Invocation call : callsByName.getOrDefault(method.getName(), List.of()))
                {
                    if (!(call.getMethodAccess() instanceof StaticFeatureAccess access)
                        || access.getFeatureEntries().stream().noneMatch(entry -> entry.getFeature() == method)
                        || call.getParams().size() <= index)
                        continue;
                    Variable argument = call.getParams().get(index) instanceof StaticFeatureAccess value
                        ? sourceVariable(value) : null;
                    ICompositeNode node = NodeModelUtils.findActualNodeFor(call);
                    if (argument == null || node == null)
                        continue;
                    types = infer(types, argument, node.getOffset(), environments);
                }
            }
            finally
            {
                returning.remove(param);
                if (returning.isEmpty())
                    RETURNING.remove();
            }
            return types;
        }

        private record CallIndexKey(Module module) {}

        /**
         * Вызовы модуля по имени метода, без учёта регистра, в порядке текста. Без индекса каждый
         * параметр перебирал все вызовы модуля: на 1,8 МБ это основная доля проверки модуля.
         */
        private static java.util.Map<String, List<com._1c.g5.v8.dt.bsl.model.Invocation>> callIndex(Module module)
        {
            java.util.Map<String, List<com._1c.g5.v8.dt.bsl.model.Invocation>> index =
                new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (com._1c.g5.v8.dt.bsl.model.Invocation call : EcoreUtil2.getAllContentsOfType(module,
                com._1c.g5.v8.dt.bsl.model.Invocation.class))
                if (call.getMethodAccess() instanceof StaticFeatureAccess access && access.getName() != null)
                    index.computeIfAbsent(access.getName(), key -> new ArrayList<>()).add(call);
            return index;
        }

        static List<TypeItem> infer(List<TypeItem> types, Variable variable, int offset, Environments environments)
        {
            return inferImpl(types, variable, offset, environments);
        }

        private static List<TypeItem> inferImpl(List<TypeItem> types, Variable variable, int offset,
            Environments environments)
        {
            if (types.stream().noneMatch(ColumnRenames::isTable))
                return types;
            Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
            Module module = EcoreUtil2.getContainerOfType(variable, Module.class);
            if (method == null && module != null)
                for (Method candidate : module.allMethods())
                {
                    ICompositeNode node = NodeModelUtils.findActualNodeFor(candidate);
                    if (node != null && offset >= node.getOffset() && offset <= node.getEndOffset())
                    {
                        method = candidate;
                        break;
                    }
                }
            if (method == null || method.eResource() == null)
                return types;
            Method owner = method;
            List<SimpleStatement> statements = PLANS.get(owner, owner.eResource(),
                () -> EcoreUtil2.getAllContentsOfType(owner, SimpleStatement.class));
            StatementPlan plan = BOUND_PLANS.get(new StatementPlanKey(owner), owner.eResource(),
                () -> new StatementPlan(statements));
            int from = 0;
            SimpleStatement assignment = null;
            for (SimpleStatement statement : plan.assignments.getOrDefault(variable, List.of()))
                if (end(statement) <= offset && end(statement) > from)
                {
                    from = end(statement);
                    assignment = statement;
                }
            types = assignment == null && variable instanceof com._1c.g5.v8.dt.bsl.model.FormalParam param
                ? callerRenames(types, param, owner, environments)
                : calleeRenames(types, assignment, environments);
            List<SimpleStatement> renames = new ArrayList<>();
            for (SimpleStatement statement : plan.renames.getOrDefault(variable, List.of()))
            {
                if (end(statement) <= from || end(statement) > offset)
                    continue;
                Environmental environment = EcoreUtil2.getContainerOfType(statement, Environmental.class);
                if (environment == null || !environment.environments().containsAny(environments))
                    continue;
                renames.add(statement);
            }
            if (renames.isEmpty())
                return types;
            List<TypeItem> result = new ArrayList<>(types.size());
            for (TypeItem item : types)
            {
                Type copy = null;
                if (isTable(item))
                    for (SimpleStatement rename : renames)
                    {
                        String name = stringValue(rename.getRight(), statements, environments);
                        if (name == null || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
                            continue;
                        if (copy == null)
                            copy = EcoreUtil2.cloneWithProxies((Type)EcoreUtil.resolve(item, variable));
                        add(copy, name, unconditional(rename, owner) ? oldName(rename.getLeft()) : null, rename,
                            owner);
                    }
                result.add(copy == null ? item : copy);
            }
            return result;
        }

        private static boolean isTable(TypeItem item)
        {
            String name = McoreUtil.getTypeName(item);
            return item instanceof Type && ("ValueTable".equals(name) || "ValueTree".equals(name)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Строго одно обращение к колонке после Колонки; произвольные свойства Имя не подходят. */
        private record StatementPlanKey(Method method) {}

        /** Разрешение ссылок и поиск переименований — один раз на метод, а не для каждого значения. */
        private static final class StatementPlan
        {
            final java.util.Map<Variable, List<SimpleStatement>> assignments = new java.util.IdentityHashMap<>();
            final java.util.Map<Variable, List<SimpleStatement>> renames = new java.util.IdentityHashMap<>();

            StatementPlan(List<SimpleStatement> statements)
            {
                for (SimpleStatement statement : statements)
                {
                    if (statement.getLeft() instanceof StaticFeatureAccess access)
                    {
                        Variable variable = sourceVariable(access);
                        if (variable != null)
                            assignments.computeIfAbsent(variable, key -> new ArrayList<>()).add(statement);
                    }
                    Variable renamed = renameVariable(statement.getLeft());
                    if (renamed != null)
                        renames.computeIfAbsent(renamed, key -> new ArrayList<>()).add(statement);
                }
            }
        }

        private static Variable renameVariable(Expression expression)
        {
            if (!(expression instanceof DynamicFeatureAccess name)
                || !("Имя".equalsIgnoreCase(name.getName()) || "Name".equalsIgnoreCase(name.getName()))) //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            Expression collection = name.getSource() instanceof DynamicFeatureAccess column ? column.getSource()
                : name.getSource() instanceof IndexAccess index ? index.getSource()
                : name.getSource() instanceof Invocation call && isColumnFind(call)
                    ? ((DynamicFeatureAccess)call.getMethodAccess()).getSource() : null;
            if (!(collection instanceof DynamicFeatureAccess columns)
                || !("Колонки".equalsIgnoreCase(columns.getName()) || "Columns".equalsIgnoreCase(columns.getName())) //$NON-NLS-1$ //$NON-NLS-2$
                || !(columns.getSource() instanceof StaticFeatureAccess))
                return null;
            return sourceVariable(columns.getSource());
        }

        private static boolean isColumnFind(Invocation call)
        {
            return call.getMethodAccess() instanceof DynamicFeatureAccess access
                && ("Найти".equalsIgnoreCase(access.getName()) || "Find".equalsIgnoreCase(access.getName())) //$NON-NLS-1$ //$NON-NLS-2$
                && call.getParams().size() == 1;
        }

        /** Грубо и быстро: не вложено ни в ветвление, ни в цикл, ни в Попытку, ни в #Если. */
        private static boolean unconditional(EObject statement, Method method)
        {
            for (EObject container = statement.eContainer(); container != null && container != method;
                container = container.eContainer())
                if (container instanceof com._1c.g5.v8.dt.bsl.model.IfStatement
                    || container instanceof com._1c.g5.v8.dt.bsl.model.LoopStatement
                    || container instanceof com._1c.g5.v8.dt.bsl.model.TryExceptStatement
                    || container instanceof com._1c.g5.v8.dt.bsl.model.IfPreprocessor)
                    return false;
            return true;
        }

        /** Прежнее имя колонки: Колонки.Имя.Имя или Колонки["Имя"].Имя; иначе {@code null}. */
        private static String oldName(Expression left)
        {
            if (!(left instanceof DynamicFeatureAccess name))
                return null;
            if (name.getSource() instanceof DynamicFeatureAccess column)
                return column.getName();
            if (name.getSource() instanceof Invocation call && isColumnFind(call))
                return call.getParams().get(0) instanceof StringLiteral literal
                    ? new DefaultStringLiteralTextProvider(literal).getText() : null;
            return name.getSource() instanceof IndexAccess index && index.getIndex() instanceof StringLiteral literal
                ? new DefaultStringLiteralTextProvider(literal).getText() : null;
        }

        /** Литерал, сложение строк или предшествующее присваивание переменной в том же методе. */
        private static String stringValue(Expression expression, List<SimpleStatement> statements,
            Environments environments)
        {
            if (expression instanceof StringLiteral literal)
                return new DefaultStringLiteralTextProvider(literal).getText();
            if (expression instanceof BinaryExpression binary && binary.getOperation() == BinaryOperation.PLUS)
            {
                String left = stringValue(binary.getLeft(), statements, environments);
                String right = left == null ? null : stringValue(binary.getRight(), statements, environments);
                return right == null ? null : left + right;
            }
            if (!(expression instanceof StaticFeatureAccess access))
                return null;
            Variable variable = sourceVariable(access);
            ICompositeNode node = NodeModelUtils.findActualNodeFor(expression);
            if (variable == null || node == null)
                return null;
            SimpleStatement latest = null;
            for (SimpleStatement statement : statements)
            {
                Environmental owner = EcoreUtil2.getContainerOfType(statement, Environmental.class);
                if (statement.getLeft() instanceof StaticFeatureAccess target
                    && sourceVariable(target) == variable && end(statement) < node.getOffset()
                    && owner != null && owner.environments().containsAny(environments)
                    && (latest == null || end(statement) > end(latest)))
                    latest = statement;
            }
            return latest == null ? null : stringValue(latest.getRight(), statements, environments);
        }

        private record EndOffsetKey(EObject object) {}

        private static int end(EObject object)
        {
            var resource = object.eResource();
            if (resource == null)
                return uncachedEnd(object);
            // Тот же срок жизни, что у плана операторов: сброс при изменении ресурса.
            return PLANS.get(new EndOffsetKey(object), resource, () -> uncachedEnd(object));
        }

        private static int uncachedEnd(EObject object)
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(object);
            return node == null ? Integer.MAX_VALUE : node.getEndOffset();
        }

        /**
         * Контейнер, все типы которого принадлежат {@code table}: общие типы заменяются копиями через
         * {@code replace}. {@code null} — менять общий тип нельзя, а заменить нечем.
         */
        private static TypeContainer ownTypes(Type table, TypeContainer container,
            java.util.function.Consumer<com._1c.g5.v8.dt.mcore.TypeContainerDef> replace)
        {
            if (container.allTypes().stream().allMatch(item -> EcoreUtil.isAncestor(table, item)))
                return container;
            if (replace == null)
                return null;
            com._1c.g5.v8.dt.mcore.TypeContainerDef def = McoreFactory.eINSTANCE.createTypeContainerDef();
            for (TypeItem item : new ArrayList<>(container.allTypes()))
            {
                EObject resolved = EcoreUtil.resolve(item, table);
                if (!(resolved instanceof TypeItem resolvedItem) || resolvedItem.eIsProxy())
                    return null;
                def.getTypes().add(EcoreUtil.isAncestor(table, resolvedItem) ? resolvedItem
                    : (TypeItem)EcoreUtil2.cloneWithProxies(resolvedItem));
            }
            replace.accept(def);
            return def;
        }

        /** {@code oldName} — безусловное переименование: свойство прежнего имени переименовываем на месте. */
        private static void add(Type table, String name, String oldName, SimpleStatement statement, Method method)
        {
            boolean tree = "ValueTree".equals(McoreUtil.getTypeName(table)); //$NON-NLS-1$
            TypeContainer rows = tree ? ValueTreeDynamicContextDefProvider.getValueTreeCollectionElementType(table)
                : table.getCollectionElementTypes();
            TypeContainer columns = tree ? ValueTreeDynamicContextDefProvider.getColumnCollectionType(table)
                : ValueTableDynamicContextDefProvider.getColumnCollectionType(table);
            if (rows == null || columns == null)
                return;
            // У копии платформенной таблицы строка и коллекция колонок — ссылки на общие типы платформы:
            // свойство, добавленное в них, появилось бы у всех таблиц значений проекта.
            TypeContainer oldColumns = columns;
            rows = ownTypes(table, rows, tree ? null : def -> table.setCollectionElementTypes(def));
            columns = ownTypes(table, columns, def ->
            {
                for (com._1c.g5.v8.dt.mcore.Property property : table.getContextDef().getProperties())
                    if (property.getTypeContainer() == oldColumns)
                        property.setTypeContainer(def);
            });
            if (rows == null || columns == null)
                return;
            for (TypeItem row : rows.allTypes())
                if (row instanceof Type type && !renameProperty(table, type, oldName, name))
                    addProperty(type, name, statement, method, true, List.of());
            Collection<TypeItem> columnTypes = TypeSystemUtil.getCollectionElementTypes(columns.allTypes(), statement);
            for (TypeItem column : columns.allTypes())
                if (column instanceof Type type && !renameProperty(table, type, oldName, name))
                    addProperty(type, name, statement, method, false, columnTypes == null ? List.of() : columnTypes);
        }

        /** Только свойство внутри копии таблицы: общий тип EDT менять нельзя. */
        private static boolean renameProperty(Type table, Type type, String oldName, String name)
        {
            if (oldName == null || type.getContextDef() == null)
                return false;
            for (com._1c.g5.v8.dt.mcore.Property property : type.getContextDef().getProperties())
                if ((oldName.equalsIgnoreCase(property.getName()) || oldName.equalsIgnoreCase(property.getNameRu()))
                    && EcoreUtil.isAncestor(table, property))
                {
                    property.setName(name);
                    property.setNameRu(name);
                    return true;
                }
            return false;
        }

        private static void addProperty(Type type, String name, SimpleStatement statement, Method method,
            boolean writable, Collection<TypeItem> valueTypes)
        {
            if (type.getContextDef() == null || type.getContextDef().allProperties().stream()
                .anyMatch(property -> name.equalsIgnoreCase(property.getName()) || name.equalsIgnoreCase(property.getNameRu())))
                return;
            DerivedProperty property = McoreFactory.eINSTANCE.createDerivedProperty();
            property.setName(name);
            property.setNameRu(name);
            property.setReadable(true);
            property.setWritable(writable);
            property.setEnvironments(Environments.ALL);
            TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
            container.getTypes().addAll(valueTypes);
            property.setTypeContainer(container);
            BslDerivedPropertySource source = BslFactory.eINSTANCE.createBslDerivedPropertySource();
            source.setMethodName(method.getName());
            source.setModuleUri(EcoreUtil.getURI(method.eResource().getContents().get(0)).trimFragment().toString());
            source.setLocalOffset(NodeModelUtils.findActualNodeFor(statement.getRight()).getOffset()
                - NodeModelUtils.findActualNodeFor(method).getTotalOffset());
            property.setSource(source);
            type.getContextDef().getProperties().add(property);
        }
    }

    /**
     * ИР: усечение расширений элемента формы по {@code Элемент.Вид = ВидПоляФормы.ПолеВвода} выше позиции.
     * Обход с конца; другой вид в более раннем присваивании — остаётся базовый тип. Вид → расширение —
     * таблица EDT form ({@code ExtInfoManagementService.create*ExtInfo} + {@code FormItemInformationService}),
     * ключ — английское имя значения, оно же литерал {@code ManagedForm*Type}.
     */
    private static final class FormItemKinds
    {
        private static final BslSourceModelCache PLANS = new BslSourceModelCache();
        /** Базовый тип → {русское имя перечисления, английское}. */
        private static final java.util.Map<String, String[]> ENUMS = java.util.Map.of(
            "FormField", new String[] {"ВидПоляФормы", "FormFieldType"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "FormGroup", new String[] {"ВидГруппыФормы", "FormGroupType"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "FormDecoration", new String[] {"ВидДекорацииФормы", "FormDecorationType"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        private static final java.util.Map<String, String> EXTENSIONS = java.util.Map.ofEntries(
            java.util.Map.entry("FormField.InputField", "FormFieldExtensionForATextBox"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.LabelField", "FormFieldExtensionForALabelField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.CheckBoxField", "FormFieldExtensionForACheckBoxField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.PictureField", "FormFieldExtensionForAPictureField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.RadioButtonField", "FormFieldExtensionForARadioButtonField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.SpreadsheetDocumentField", "FormFieldExtensionForASpreadsheetDocumentField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.TextDocumentField", "FormFieldExtensionForATextDocument"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.CalendarField", "FormFieldExtensionForACalendarField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.ProgressBarField", "FormFieldExtensionForAProgressBarField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.TrackBarField", "FormFieldExtensionForATrackBarField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.ChartField", "FormFieldExtensionForAChartField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.GanttChartField", "FormFieldExtensionForAGanttChartField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.DendrogramField", "FormFieldExtensionForADendrogramField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.GraphicalSchemaField", "FormFieldExtensionForAGraphicalSchemaField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.HTMLDocumentField", "FormExtensionForAHTMLDocumentField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.GeographicalSchemaField", "FormFieldExtensionForAGeographicalSchemaField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.FormattedDocumentField", "FormFieldExtensionForAFormattedDocument"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.PDFDocumentField", "FormExtensionForAPDFDocumentField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.PlannerField", "FormFieldExtensionForAPlanner"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormField.PeriodField", "FormFieldExtensionForAPeriodField"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormGroup.UsualGroup", "FormGroupExtensionForAUsualGroup"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormGroup.ColumnGroup", "FormGroupExtensionForAGroupOfColumns"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormGroup.CommandBar", "FormGroupExtensionForACommandBar"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormGroup.Popup", "FormGroupExtensionForAPopup"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormGroup.Page", "FormGroupExtensionForAPage"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormGroup.Pages", "FormGroupExtensionForPages"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormDecoration.Label", "FormDecorationExtensionForALabel"), //$NON-NLS-1$ //$NON-NLS-2$
            java.util.Map.entry("FormDecoration.Picture", "FormDecorationExtensionForAPicture")); //$NON-NLS-1$ //$NON-NLS-2$

        static List<TypeItem> infer(List<TypeItem> types, Variable variable, int offset, Environments environments)
        {
            try
            {
                String base = null;
                for (TypeItem item : types)
                {
                    String segment = FormItemTypes.segmentOf(item);
                    if (segment != null && ENUMS.containsKey(segment))
                    {
                        base = segment;
                        break;
                    }
                }
                if (base == null)
                    return types;
                Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
                Module module = EcoreUtil2.getContainerOfType(variable, Module.class);
                if (method == null && module != null)
                    for (Method candidate : module.allMethods())
                    {
                        ICompositeNode node = NodeModelUtils.findActualNodeFor(candidate);
                        if (node != null && offset >= node.getOffset() && offset <= node.getEndOffset())
                        {
                            method = candidate;
                            break;
                        }
                    }
                if (method == null || !(method.eResource() instanceof XtextResource resource))
                    return types;
                Method owner = method;
                List<SimpleStatement> statements = PLANS.get(owner, resource,
                    () -> EcoreUtil2.getAllContentsOfType(owner, SimpleStatement.class));
                // Как в ColumnRenames: присваивания до последней перезаписи самой переменной не действуют
                int from = 0;
                for (SimpleStatement statement : statements)
                    if (statement.getLeft() instanceof StaticFeatureAccess access && sourceVariable(access) == variable
                        && end(statement) <= offset)
                        from = Math.max(from, end(statement));
                List<Kind> kinds = new ArrayList<>();
                for (SimpleStatement statement : statements)
                    if (end(statement) > from && end(statement) <= offset && sameEnvironment(statement, environments)
                        && isKindOf(statement.getLeft(), variable))
                        kinds.add(new Kind(end(statement), statement.getRight()));
                // ИР МожноПрименитьПрисвоениеТипаИзЕсли: «Если Поле.Вид = … Тогда» — внутри ветки и дальше по условию,
                // если до него только «И»; как сужение по «Если ТипЗнч(Поле) = Тип(…) Тогда» в EDT
                List<BinaryExpression> comparisons = PLANS.get(List.of(owner, BinaryExpression.class), resource,
                    () -> EcoreUtil2.getAllContentsOfType(owner, BinaryExpression.class));
                for (BinaryExpression comparison : comparisons)
                {
                    if (comparison.getOperation() != BinaryOperation.EQ || end(comparison) <= from
                        || end(comparison) >= offset || !sameEnvironment(comparison, environments))
                        continue;
                    Expression value = isKindOf(comparison.getLeft(), variable) ? comparison.getRight()
                        : isKindOf(comparison.getRight(), variable) ? comparison.getLeft() : null;
                    com._1c.g5.v8.dt.bsl.model.Conditional branch = value != null ? guardedBranch(comparison) : null;
                    ICompositeNode node = branch != null ? NodeModelUtils.findActualNodeFor(branch) : null;
                    if (node != null && offset <= node.getEndOffset())
                        kinds.add(new Kind(end(comparison), value));
                }
                if (kinds.isEmpty())
                    return types;
                kinds.sort(java.util.Comparator.comparingInt(Kind::end));
                var computer = resource.getResourceServiceProvider()
                    .get(com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer.class);
                String current = base;
                for (int index = kinds.size() - 1; index >= 0; index--)
                {
                    String extension = extensionName(base, kinds.get(index).value(), computer, environments);
                    if (extension == null)
                        continue;
                    if (!current.equals(base) && !current.equals(extension))
                    {
                        current = base;
                        break;
                    }
                    current = extension;
                }
                TypeItem chosen = current.equals(base) ? null : FormItemTypes.platformType(current);
                if (!current.equals(base) && chosen == null)
                    return types;
                List<TypeItem> result = new ArrayList<>(types.size());
                for (TypeItem item : types)
                    if (!FormItemTypes.isExtensionOf(FormItemTypes.segmentOf(item), base))
                        result.add(item);
                if (chosen != null)
                    result.add(chosen);
                return result.size() == types.size() && result.containsAll(types) ? types : result;
            }
            catch (RuntimeException | LinkageError failure)
            {
                return types;
            }
        }

        private static record Kind(int end, Expression value) {}

        /** {@code Переменная.Вид}. */
        private static boolean isKindOf(Expression expression, Variable variable)
        {
            return expression instanceof DynamicFeatureAccess property
                && ("Вид".equalsIgnoreCase(property.getName()) || "Type".equalsIgnoreCase(property.getName())) //$NON-NLS-1$ //$NON-NLS-2$
                && property.getSource() instanceof StaticFeatureAccess && sourceVariable(property.getSource()) == variable;
        }

        private static boolean sameEnvironment(EObject object, Environments environments)
        {
            Environmental environment = EcoreUtil2.getContainerOfType(object, Environmental.class);
            return environment != null && environment.environments().containsAny(environments);
        }

        /** Ветка {@code Если}/{@code ИначеЕсли}, в условии которой сравнение связано с корнем только через «И». */
        private static com._1c.g5.v8.dt.bsl.model.Conditional guardedBranch(BinaryExpression comparison)
        {
            EObject child = comparison;
            for (EObject container = comparison.eContainer(); container != null; container = container.eContainer())
            {
                if (container instanceof com._1c.g5.v8.dt.bsl.model.Conditional branch)
                    return branch.getPredicate() == child ? branch : null;
                if (!(container instanceof BinaryExpression binary) || binary.getOperation() != BinaryOperation.AND)
                    return null;
                child = container;
            }
            return null;
        }

        /** {@code ВидПоляФормы.ПолеВвода} → {@code FormFieldExtensionForATextBox}; иначе {@code null}. */
        private static String extensionName(String base, Expression value,
            com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer computer, Environments environments)
        {
            String[] enumNames = ENUMS.get(base);
            if (!(value instanceof DynamicFeatureAccess access) || !(access.getSource() instanceof StaticFeatureAccess source)
                || !(enumNames[0].equalsIgnoreCase(source.getName()) || enumNames[1].equalsIgnoreCase(source.getName())))
                return null;
            List<FeatureEntry> entries = access.getFeatureEntries();
            if (entries.isEmpty() && computer != null)
                entries = computer.resolveObject(access, environments);
            String name = access.getName();
            for (FeatureEntry entry : entries)
                if (entry.getFeature() instanceof com._1c.g5.v8.dt.mcore.DuallyNamedElement named && named.getName() != null)
                {
                    name = named.getName();
                    break;
                }
            return EXTENSIONS.get(base + "." + name); //$NON-NLS-1$
        }

        private static int end(EObject object)
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(object);
            return node == null ? Integer.MAX_VALUE : node.getEndOffset();
        }
    }

    /** Область запроса редактора; потоки проверки модуля её не наследуют. */
    private static final ThreadLocal<InteractiveTypes> INTERACTIVE_TYPES = new ThreadLocal<>();

    /** Только фоновый запрос редактора. Обычные читатели типов эту область не открывают. */
    static InteractiveTypesScope interactiveTypes(String purpose, java.util.function.BooleanSupplier canceled)
    {
        InteractiveTypes previous = INTERACTIVE_TYPES.get();
        InteractiveTypes current = previous != null ? previous : new InteractiveTypes(canceled);
        INTERACTIVE_TYPES.set(current);
        return new InteractiveTypesScope(previous, previous == null ? current : null, purpose);
    }

    /** Последовательные проверки одной модели используют один вычислитель, без сохранения монитора проверки. */
    static final class InteractiveTypesSession
    {
        private final InteractiveTypes types;

        InteractiveTypesSession(java.util.function.BooleanSupplier canceled,
            java.util.function.Consumer<EObject> observe)
        {
            types = new InteractiveTypes(canceled);
            types.observe = observe;
            types.computingCallingModules = true;
        }

        InteractiveTypesScope open(String purpose)
        {
            InteractiveTypes previous = INTERACTIVE_TYPES.get();
            INTERACTIVE_TYPES.set(types);
            return new InteractiveTypesScope(previous, types, purpose);
        }
    }

    static boolean interactiveTypesActive()
    {
        InteractiveTypes current = INTERACTIVE_TYPES.get();
        return current != null && current.nativeRead == 0;
    }

    static List<TypeItem> interactiveExpressionTypes(EObject object, Environments environments)
    {
        InteractiveTypes current = INTERACTIVE_TYPES.get();
        return current == null ? List.of() : current.expression(object, environments, null);
    }

    /**
     * Тип переменной для подсказки при наведении. Формальный параметр описан до тела метода,
     * поэтому в точке объявления состояние EDT ещё не знает о присваивании с боковым
     * комментарием внутри метода: берём последнее присваивание этого параметра.
     */
    static List<TypeItem> interactiveVariableTypes(Variable variable, int offset, Environments environments)
    {
        InteractiveTypes current = INTERACTIVE_TYPES.get();
        return current == null || current.nativeRead != 0
            ? TypeSystemUtil.getVariableTypesAtOffset(variable, offset, environments)
            : current.variableForHint(variable, offset, environments);
    }

    /** Вызывается фоновым расчётом сведений о параметрах; UI читает только его готовый кэш. */
    static List<TypeItem> callingModuleParameterTypes(Method method, int index)
    {
        if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(method) || BslCallingModules.names(method).isEmpty())
            return List.of();
        try (var scope = interactiveTypes("callingModules", () -> Thread.currentThread().isInterrupted()))
        {
            InteractiveTypes current = INTERACTIVE_TYPES.get();
            boolean previous = current.computingCallingModules;
            current.computingCallingModules = true;
            try
            {
                return current.incomingFromCallingModules(method, index,
                    method.environments() == null ? Environments.ALL : method.environments());
            }
            finally
            {
                current.computingCallingModules = previous;
            }
        }
    }

    /** Тип документированного поля — тот же ленивый переход по ссылке, что у выражения после точки. */
    static List<TypeItem> interactivePropertyTypes(Property property, EObject context, Environments environments)
    {
        InteractiveTypes current = INTERACTIVE_TYPES.get();
        if (current == null || current.nativeRead != 0 || !BslDocCommentComputedTypes.isExtendedTypesEnabled(context))
            return new ArrayList<>(property.getTypes());
        return current.propertyTypes(property, context, environments == null ? Environments.ALL : environments);
    }

    /** Члены вычисленного получателя без чтения/сброса закэшированных FeatureEntries обращения. */
    static List<EObject> interactiveDocumentationMembers(DynamicFeatureAccess access)
    {
        InteractiveTypes current = INTERACTIVE_TYPES.get();
        if (current == null || current.nativeRead != 0)
            return List.of();
        Method method = EcoreUtil2.getContainerOfType(access, Method.class);
        Environments environments = method == null || method.environments() == null ? Environments.ALL : method.environments();
        boolean invocation = access.eContainer() instanceof Invocation call && call.getMethodAccess() == access;
        List<EObject> members = current.memberObjects(current.expression(access.getSource(), environments, null),
            access.getName(), access, invocation, environments);
        return members;
    }

    private static InteractiveTypesScope suspendInteractiveTypes()
    {
        InteractiveTypes previous = INTERACTIVE_TYPES.get();
        INTERACTIVE_TYPES.remove();
        return new InteractiveTypesScope(previous);
    }

    static final class InteractiveTypesScope implements AutoCloseable
    {
        private final InteractiveTypes previous;
        private final InteractiveTypes observed;
        private final String purpose;

        InteractiveTypesScope(InteractiveTypes previous)
        {
            this(previous, null, null);
        }

        InteractiveTypesScope(InteractiveTypes previous, InteractiveTypes observed, String purpose)
        {
            this.previous = previous;
            this.observed = observed;
            this.purpose = purpose;
        }

        @Override
        public void close()
        {
            if (previous == null)
                INTERACTIVE_TYPES.remove();
            else
                INTERACTIVE_TYPES.set(previous);
        }
    }

    /** Уточняет только запрошенное выражение; состояния проверки и модель AST не изменяет. */
    private static final class InteractiveTypes
    {
        private record Identity(EObject object)
        {
            @Override public int hashCode() { return System.identityHashCode(object); }
            @Override public boolean equals(Object other) { return other instanceof Identity key && object == key.object; }
        }
        private record ExpressionKey(Identity expression, Frame frame, Environments environments) {}
        private record VariableKey(Identity variable, int offset, Frame frame, Environments environments) {}
        private record CallKey(Identity method, List<List<Identity>> arguments, Environments environments) {}
        private record Assignment(int end, Expression value) {}
        private record Loop(Variable variable, Expression collection, int start, int end) {}

        private static final class Frame
        {
            final java.util.Map<Variable, List<TypeItem>> parameters = new java.util.IdentityHashMap<>();
        }

        private final java.util.function.BooleanSupplier canceled;
        private java.util.function.Consumer<EObject> observe = object -> {};
        private final java.util.Map<ExpressionKey, List<TypeItem>> expressions = new java.util.HashMap<>();
        private final java.util.Map<VariableKey, List<TypeItem>> variables = new java.util.HashMap<>();
        private final java.util.Map<ExpressionKey, List<TypeItem>> properties = new java.util.HashMap<>();
        private final Set<ExpressionKey> activeProperties = new HashSet<>();
        private final Set<ExpressionKey> activeExpressions = new HashSet<>();
        private final Set<VariableKey> activeVariables = new HashSet<>();
        private final Set<Method> activeMethods = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private final java.util.Map<CallKey, List<TypeItem>> returns = new java.util.HashMap<>();
        private final java.util.Map<Method, Plan> plans = new java.util.IdentityHashMap<>();
        /** Индекс только завершённых обходов; порядок вызовов каждого метода совпадает с AST. */
        private final java.util.Map<Module, java.util.Map<Method, List<Invocation>>> calls = new java.util.IdentityHashMap<>();
        private final java.util.Map<Method, List<org.eclipse.core.resources.IFile>> callingModules =
            new java.util.IdentityHashMap<>();
        private final java.util.Map<String, List<List<TypeItem>>> callingModuleTypes = new java.util.HashMap<>();
        private final Set<String> activeCallingModules = new HashSet<>();
        private boolean computingCallingModules;
        private final long started = System.nanoTime();
        private long expressionRequests;
        private long expressionHits;
        private long incomingRequests;
        private long incomingCandidates;
        private long callIndexNodes;
        private long callIndexEntries;
        private Frame frame = new Frame();
        private int nativeRead;

        InteractiveTypes(java.util.function.BooleanSupplier canceled)
        {
            this.canceled = canceled;
        }

        private void check()
        {
            BslNodeLookup.checkCanceled();
            if (Thread.currentThread().isInterrupted() || canceled != null && canceled.getAsBoolean())
                throw new org.eclipse.core.runtime.OperationCanceledException();
        }

        private static boolean ready(EObject object)
        {
            return object != null && (!(object.eResource() instanceof com._1c.g5.v8.dt.bsl.resource.BslResource resource)
                || resource.isLinkedBatch() || resource.getContextHolder() != null);
        }

        private List<TypeItem> nativeTypes(EObject object, Environments environments, List<TypeItem> provided)
        {
            observe.accept(object);
            if (provided != null)
                return provided;
            if (!ready(object) || !(object.eResource() instanceof XtextResource resource))
                return object instanceof Expression expression ? new ArrayList<>(expression.getTypes()) : List.of();
            nativeRead++;
            try
            {
                List<TypeItem> result = resource.getResourceServiceProvider().get(TypesComputer.class)
                    .computeTypes(object, environments);
                return result == null ? List.of() : result;
            }
            finally
            {
                nativeRead--;
            }
        }

        List<TypeItem> expression(EObject object, Environments environments, List<TypeItem> provided)
        {
            expressionRequests++;
            check();
            observe.accept(object);
            // Штатный TypesComputer допускает null (без ограничения сред),
            // локальные сборщики используют containsAny и требуют явный ALL.
            if (environments == null)
            {
                environments = Environments.ALL;
            }
            if (!ready(object) || !BslDocCommentComputedTypes.isExtendedTypesEnabled(object))
                return nativeTypes(object, environments, provided);
            ExpressionKey key = new ExpressionKey(new Identity(object), frame, environments);
            List<TypeItem> cached = expressions.get(key);
            if (cached != null)
            {
                expressionHits++;
                return cached;
            }
            if (!activeExpressions.add(key))
                return nativeTypes(object, environments, provided);
            try
            {
                List<TypeItem> inferred = List.of();
                if (object instanceof StaticFeatureAccess access && sourceVariable(access) != null)
                    inferred = variable(sourceVariable(access), position(access), environments, provided);
                else if (object instanceof IndexAccess index)
                    inferred = indexed(index, environments);
                else if (object instanceof DynamicFeatureAccess access)
                    inferred = members(expression(access.getSource(), environments, null), access.getName(), object, false, environments);
                else if (object instanceof Invocation call)
                    inferred = invocation(call, environments);
                else if (object instanceof com._1c.g5.v8.dt.bsl.model.Function function)
                    inferred = methodReturns(function, environments);
                // Свойство, вызов и индекс имеют специальные правила EDT. Наш
                // расчёт дополняет их результат, сохраняя контекст и типы аргументов.
                boolean preserveNative = object instanceof DynamicFeatureAccess
                    || object instanceof Invocation || object instanceof IndexAccess;
                List<TypeItem> nativeResult = preserveNative || inferred.isEmpty()
                    ? nativeTypes(object, environments, provided) : List.of();
                List<TypeItem> result = preserveNative ? merge(nativeResult, inferred)
                    : inferred.isEmpty() ? nativeResult : inferred;
                if (object instanceof DynamicFeatureAccess access)
                {
                    result = TypeOfBranchNarrowing.narrow(result, access);
                }
                else if (object instanceof StaticFeatureAccess access && sourceVariable(access) != null)
                {
                    // Готовый тип внутри ветки уже сужен, а объединение с типом присваивания его теряет.
                    result = TypeOfBranchNarrowing.narrow(result, access);
                }
                result = List.copyOf(result);
                expressions.put(key, result);
                return result;
            }
            finally
            {
                activeExpressions.remove(key);
            }
        }

        private List<TypeItem> variableForHint(Variable variable, int offset, Environments environments)
        {
            int effective = offset;
            if (variable instanceof com._1c.g5.v8.dt.bsl.model.FormalParam)
            {
                Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
                if (method != null)
                {
                    Plan plan = plans.computeIfAbsent(method, Plan::new);
                    List<Assignment> assignments = plan.assignments.getOrDefault(variable, List.of());
                    if (!assignments.isEmpty())
                        effective = assignments.get(assignments.size() - 1).end;
                }
            }
            return variable(variable, effective, environments, null);
        }

        List<TypeItem> variable(Variable variable, int offset, Environments environments, List<TypeItem> provided)
        {
            check();
            observe.accept(variable);
            if (environments == null)
            {
                environments = Environments.ALL;
            }
            VariableKey key = new VariableKey(new Identity(variable), offset, frame, environments);
            List<TypeItem> cached = variables.get(key);
            if (cached != null)
                return cached;
            List<TypeItem> comment = BslDocCommentComputedTypes.iteratorCommentTypes(variable);
            if (!comment.isEmpty())
            {
                List<TypeItem> nativeTypes = variableNative(variable, offset, environments, provided);
                List<TypeItem> filtered = List.copyOf(filterTypesByCommentMask(nativeTypes, comment));
                List<TypeItem> iteratorResult = filtered.isEmpty() ? List.copyOf(comment) : filtered;
                variables.put(key, iteratorResult);
                return iteratorResult;
            }
            if (!activeVariables.add(key))
                return variableNative(variable, offset, environments, provided);
            try
            {
                Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
                if (method == null || !ready(variable))
                    return variableNative(variable, offset, environments, provided);
                Plan plan = plans.computeIfAbsent(method, Plan::new);
                Assignment assignment = null;
                for (Assignment candidate : plan.assignments.getOrDefault(variable, List.of()))
                    if (candidate.end <= offset && (assignment == null || candidate.end > assignment.end))
                        assignment = candidate;
                List<TypeItem> inferred = List.of();
                if (assignment != null)
                    inferred = expression(assignment.value, environments, null);
                else if (frame.parameters.containsKey(variable))
                    inferred = frame.parameters.get(variable);
                else
                    for (Loop loop : plan.loops)
                        if (loop.variable == variable && offset >= loop.start && offset <= loop.end)
                        {
                            inferred = elements(expression(loop.collection, environments, null), variable);
                            break;
                        }
                if (inferred.isEmpty() && variable instanceof com._1c.g5.v8.dt.bsl.model.FormalParam param)
                    inferred = incoming(param, method, environments);
                int from = assignment == null ? -1 : assignment.end;
                boolean changed = plan.changes.getOrDefault(variable, List.of()).stream()
                    .anyMatch(position -> position > from && position <= offset);
                List<TypeItem> result;
                if (inferred.isEmpty())
                    result = variableNative(variable, offset, environments, provided);
                else if (!changed && frame.parameters.containsKey(variable))
                    result = inferred;
                else
                {
                    // Уточняем объединённую основу: переименование должно перенести тип
                    // готовой колонки EDT, а не создать пустую колонку у типа из «Новый».
                    List<TypeItem> nativeBasis = variableNative(variable, offset, environments, provided);
                    Module module = EcoreUtil2.getContainerOfType(method, Module.class);
                    List<String> sideNames = assignment == null ? List.of()
                        : assignmentCommentTypeNames(assignment.value, module);
                    boolean commentType = assignment != null && hasAssignmentCommentType(assignment.value, module);
                    // Боковой комментарий накладывает маску на рассчитанные типы: из расчёта
                    // берётся тип с тем же верхним именем, иначе — тип из комментария.
                    List<TypeItem> basis = commentType
                        ? filterTypesByCommentMask(inferred, nativeBasis)
                        : merge(nativeBasis, inferred);
                    basis = annotatedProperties(basis, variable, offset, from, environments, plan);
                    result = localVariableTypes(basis, variable, offset, environments);
                }
                result = List.copyOf(result);
                variables.put(key, result);
                return result;
            }
            finally
            {
                activeVariables.remove(key);
            }
        }

        private final java.util.Map<ExpressionKey, List<TypeItem>> annotationTypes = new java.util.HashMap<>();

        /** Ссылки бокового комментария считаются только в текущем фоновом запросе редактора. */
        private List<TypeItem> annotatedProperties(List<TypeItem> types, Variable variable, int offset, int from,
            Environments environments, Plan plan)
        {
            List<TypeItem> result = types;
            for (Invocation call : plan.annotations.getOrDefault(variable, List.of()))
            {
                check();
                ICompositeNode node = NodeModelUtils.findActualNodeFor(call);
                if (node == null || node.getOffset() < from || node.getEndOffset() > offset)
                    continue;
                String key = insertedKey(call);
                if (key == null)
                    continue;
                ExpressionKey cacheKey = new ExpressionKey(new Identity(call), frame, environments);
                List<TypeItem> values = annotationTypes.get(cacheKey);
                if (values == null)
                {
                    Method method = EcoreUtil2.getContainerOfType(call, Method.class);
                    Module module = EcoreUtil2.getContainerOfType(call, Module.class);
                    List<PropertySeeLink> links = new ArrayList<>();
                    collectReturnSeeLinks(sideCommentReturnSection(call, module), method,
                        new java.util.LinkedHashSet<>(), links);
                    values = new ArrayList<>();
                    for (PropertySeeLink link : links)
                    {
                        check();
                        EObject target = ParamHintHtmlModifier.ParameterSeeLinks.resolveTarget(link.link(), link.method());
                        if (target != null && !target.eIsProxy() && ready(target))
                            add(values, expression(target, environments, null));
                    }
                    values = List.copyOf(values);
                    annotationTypes.put(cacheKey, values);
                }
                if (values.isEmpty())
                    continue;
                List<TypeItem> updated = new ArrayList<>();
                for (TypeItem item : result)
                {
                    check();
                    updated.add(withAnnotatedProperty(item, key, values));
                }
                result = updated;
            }
            return result;
        }

        private static TypeItem withAnnotatedProperty(TypeItem item, String key, List<TypeItem> values)
        {
            if (!(item instanceof Type type) || type.eIsProxy())
                return item;
            boolean own = hasProperty(type, key);
            boolean rows = type.getCollectionElementTypes() != null
                && type.getCollectionElementTypes().allTypes().stream()
                    .anyMatch(element -> element instanceof Type row && !row.eIsProxy() && hasProperty(row, key));
            if (!own && !rows)
                return item;
            Type copy = EcoreUtil2.cloneWithProxies(type);
            if (own)
                fillAnnotatedProperty(copy, copy, key, values);
            if (rows)
            {
                TypeContainer elements = ColumnRenames.ownTypes(copy, copy.getCollectionElementTypes(),
                    copy::setCollectionElementTypes);
                if (elements != null)
                    for (TypeItem element : elements.allTypes())
                        if (element instanceof Type row)
                            fillAnnotatedProperty(copy, row, key, values);
            }
            return copy;
        }

        private static boolean hasProperty(Type type, String key)
        {
            return type.getContextDef() != null && type.getContextDef().allProperties().stream()
                .anyMatch(property -> key.equalsIgnoreCase(property.getName()) || key.equalsIgnoreCase(property.getNameRu()));
        }

        private static void fillAnnotatedProperty(Type owner, Type type, String key, List<TypeItem> values)
        {
            if (type.getContextDef() == null)
                return;
            for (Property property : new ArrayList<>(type.getContextDef().allProperties()))
                if (key.equalsIgnoreCase(property.getName()) || key.equalsIgnoreCase(property.getNameRu()))
                {
                    Property copy = property;
                    if (!EcoreUtil.isAncestor(owner, property))
                    {
                        copy = EcoreUtil2.cloneWithProxies(property);
                        type.getContextDef().getProperties().add(copy);
                    }
                    List<TypeItem> combined = new ArrayList<>(copy.getTypes());
                    add(combined, values);
                    TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
                    container.getTypes().addAll(combined);
                    copy.setTypeContainer(container);
                }
        }

        private List<TypeItem> variableNative(Variable variable, int offset, Environments environments, List<TypeItem> provided)
        {
            if (provided != null)
                return provided;
            nativeRead++;
            try
            {
                return TypeSystemUtil.getVariableTypesAtOffset(variable, offset, environments);
            }
            finally
            {
                nativeRead--;
            }
        }

        private List<TypeItem> invocation(Invocation call, Environments environments)
        {
            List<FeatureEntry> entries = List.of();
            if (call.getMethodAccess() instanceof StaticFeatureAccess access)
                entries = access.getFeatureEntries();
            else if (call.getMethodAccess() instanceof DynamicFeatureAccess access)
                entries = access.getFeatureEntries();
            List<TypeItem> result = new ArrayList<>();
            for (FeatureEntry entry : entries)
                if (entry.getFeature() instanceof com._1c.g5.v8.dt.bsl.model.Function function && ready(function)
                    && !activeMethods.contains(function))
                {
                    Frame arguments = new Frame();
                    List<List<Identity>> identities = new ArrayList<>();
                    for (int index = 0; index < function.getFormalParams().size(); index++)
                    {
                        List<TypeItem> types = index < call.getParams().size()
                            ? expression(call.getParams().get(index), environments, null) : List.of();
                        arguments.parameters.put(function.getFormalParams().get(index), types);
                        identities.add(types.stream().map(Identity::new).toList());
                    }
                    CallKey key = new CallKey(new Identity(function), identities, environments);
                    List<TypeItem> types = returns.get(key);
                    if (types == null)
                    {
                        Frame previous = frame;
                        frame = arguments;
                        try { types = methodReturns(function, environments); }
                        finally { frame = previous; }
                        returns.put(key, types);
                    }
                    add(result, types);
                }
            if (result.isEmpty() && call.getMethodAccess() instanceof DynamicFeatureAccess access)
                add(result, members(expression(access.getSource(), environments, null), access.getName(), call, true, environments));
            return result;
        }

        private List<TypeItem> methodReturns(com._1c.g5.v8.dt.bsl.model.Function function, Environments environments)
        {
            observe.accept(function);
            if (!activeMethods.add(function))
                return List.of();
            try
            {
                List<TypeItem> result = new ArrayList<>();
                for (Expression expression : plans.computeIfAbsent(function, Plan::new).returns)
                    add(result, expression(expression, environments, null));
                // Тело дополняет готовое документированное описание, в том числе
                // рекурсивную схему с пока не раскрытыми ссылками на поля.
                return merge(nativeTypes(function, environments, null), result);
            }
            finally { activeMethods.remove(function); }
        }

        private List<TypeItem> incoming(com._1c.g5.v8.dt.bsl.model.FormalParam parameter, Method method, Environments environments)
        {
            incomingRequests++;
            Module module = EcoreUtil2.getContainerOfType(method, Module.class);
            int index = method.getFormalParams().indexOf(parameter);
            if (module == null || index < 0)
                return List.of();
            java.util.Map<Method, List<Invocation>> indexed = calls.get(module);
            if (indexed == null)
            {
                indexed = new java.util.IdentityHashMap<>();
                for (var iterator = module.eAllContents(); iterator.hasNext();)
                {
                    check();
                    callIndexNodes++;
                    if (!(iterator.next() instanceof Invocation call)
                        || !(call.getMethodAccess() instanceof StaticFeatureAccess access)
                        || call.getParams().isEmpty())
                        continue;
                    Set<Method> targets = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    for (FeatureEntry entry : access.getFeatureEntries())
                    {
                        check();
                        callIndexEntries++;
                        if (entry.getFeature() instanceof Method target && targets.add(target))
                            indexed.computeIfAbsent(target, ignored -> new ArrayList<>()).add(call);
                    }
                }
                calls.put(module, indexed);
            }
            List<TypeItem> result = new ArrayList<>();
            for (Invocation call : indexed.getOrDefault(method, List.of()))
            {
                check();
                incomingCandidates++;
                if (call.getParams().size() > index)
                    add(result, expression(call.getParams().get(index), environments, null));
            }
            add(result, incomingFromCallingModules(method, index, environments));
            return result;
        }

        /** Только область фонового расчёта редактора/проверки; показ подсказки её не открывает. */
        private List<TypeItem> incomingFromCallingModules(Method method, int index, Environments environments)
        {
            if (BslCallingModules.names(method).isEmpty())
                return List.of();
            if (!computingCallingModules)
                return BslCallingModules.readyTypes(method, index);
            String key = EcoreUtil.getURI(method) + "|" + environments;
            List<List<TypeItem>> cached = callingModuleTypes.get(key);
            if (cached != null)
                return cached.get(index);
            if (!activeCallingModules.add(key))
                return List.of();
            try
            {
                List<org.eclipse.core.resources.IFile> files = callingModules.computeIfAbsent(method,
                    target -> BslCallingModules.files(target, BslCallingModules.names(target)));
                String stamp = BslCallingModules.typesStamp(method);
                List<List<TypeItem>> result = new ArrayList<>();
                for (int parameter = 0; parameter < method.getFormalParams().size(); parameter++)
                    result.add(new ArrayList<>());
                for (var file : files)
                {
                    check();
                    URI uri = URI.createPlatformResourceURI(file.getFullPath().toPortableString(), true);
                    var buffer = org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager()
                        .getTextFileBuffer(file.getFullPath(), org.eclipse.core.filebuffers.LocationKind.IFILE);
                    var document = buffer != null && buffer.getDocument() instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument live
                        ? live : null;
                    var read = ForeignModuleModel.read(uri, file, document, resource -> {
                        List<List<TypeItem>> found = new ArrayList<>();
                        for (int parameter = 0; parameter < method.getFormalParams().size(); parameter++)
                            found.add(new ArrayList<>());
                        if (resource == null || resource.getContents().isEmpty())
                            return found;
                        observe.accept(resource.getContents().get(0));
                        for (var iterator = resource.getAllContents(); iterator.hasNext();)
                        {
                            check();
                            if (!(iterator.next() instanceof Invocation call) || call.getParams().isEmpty())
                                continue;
                            List<FeatureEntry> entries = call.getMethodAccess() instanceof StaticFeatureAccess access
                                ? access.getFeatureEntries() : call.getMethodAccess() instanceof DynamicFeatureAccess access
                                    ? access.getFeatureEntries() : List.of();
                            boolean matches = entries.stream().anyMatch(entry -> BslCallingModules.sameMethod(entry.getFeature(), method));
                            if (!matches)
                                continue;
                            incomingCandidates++;
                            Frame previous = frame;
                            frame = new Frame();
                            try
                            {
                                for (int parameter = 0; parameter < Math.min(found.size(), call.getParams().size()); parameter++)
                                    add(found.get(parameter), expression(call.getParams().get(parameter), environments, null));
                            }
                            finally
                            {
                                frame = previous;
                            }
                        }
                        return found;
                    });
                    if (read.value() != null)
                        for (int parameter = 0; parameter < result.size(); parameter++)
                            add(result.get(parameter), read.value().get(parameter));
                }
                callingModuleTypes.put(key, result.stream().map(List::copyOf).toList());
                BslCallingModules.rememberTypes(method, stamp, result);
                return result.get(index);
            }
            catch (RuntimeException error)
            {
                throw error;
            }
            finally
            {
                activeCallingModules.remove(key);
            }
        }

        /** Индекс соответствия — значение Get, строковый литерал — поле, число — элемент. */
        private List<TypeItem> indexed(IndexAccess access, Environments environments)
        {
            Expression index = access.getIndex();
            if (index == null)
                return List.of();
            List<TypeItem> owners = expression(access.getSource(), environments, null);
            List<TypeItem> maps = new ArrayList<>();
            for (TypeItem owner : owners)
            {
                String name = McoreUtil.getTypeName(owner);
                if ("Map".equals(name) || "FixedMap".equals(name))
                    maps.add(owner);
            }
            // Как в TypesComputer EDT: при наличии соответствия индекс любого
            // типа обозначает ключ, а не пару из перечисления коллекции.
            if (!maps.isEmpty())
                return members(maps, "Get", access, true, environments);
            if (index instanceof StringLiteral literal)
                return literal.getLines().size() == 1
                    ? members(owners, new DefaultStringLiteralTextProvider(literal).getText(), access, false, environments)
                    : List.of();
            List<TypeItem> indexTypes = expression(index, environments, null);
            return indexTypes.stream().anyMatch(type -> "Number".equals(McoreUtil.getTypeName(type)))
                ? elements(owners, access) : List.of();
        }

        private List<TypeItem> elements(List<TypeItem> owners, EObject context)
        {
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem owner : owners)
            {
                check();
                EObject resolved = owner.eIsProxy() ? EcoreUtil.resolve(owner, context) : owner;
                if (resolved instanceof Type type && type.getCollectionElementTypes() != null)
                    add(result, type.getCollectionElementTypes().allTypes());
            }
            return result;
        }

        private List<EObject> memberObjects(List<TypeItem> owners, String name, EObject context, boolean methods,
            Environments environments)
        {
            if (name == null || name.isEmpty())
                return List.of();
            List<EObject> result = new ArrayList<>();
            for (TypeItem owner : owners)
            {
                check();
                EObject resolved = owner.eIsProxy() ? EcoreUtil.resolve(owner, context) : owner;
                if (!(resolved instanceof Type type) || type.getContextDef() == null)
                    continue;
                List<? extends EObject> candidates = methods ? type.getContextDef().allMethods()
                    : type.getContextDef().allProperties();
                for (EObject candidate : candidates)
                {
                    boolean matches = false;
                    if (candidate instanceof com._1c.g5.v8.dt.mcore.Method method)
                        matches = name.equalsIgnoreCase(method.getName()) || name.equalsIgnoreCase(method.getNameRu());
                    else if (candidate instanceof Property property)
                        matches = name.equalsIgnoreCase(property.getName()) || name.equalsIgnoreCase(property.getNameRu());
                    if (matches)
                    {
                        Environments declared = candidate instanceof com._1c.g5.v8.dt.mcore.Environmental environmental
                            ? environmental.environments() : null;
                        boolean available = declared == null || declared.containsAny(environments);
                        if (available && result.stream().noneMatch(existing -> existing == candidate))
                            result.add(candidate);
                    }
                }
            }
            return result;
        }

        private List<TypeItem> members(List<TypeItem> owners, String name, EObject context, boolean methods,
            Environments environments)
        {
            List<TypeItem> result = new ArrayList<>();
            for (EObject member : memberObjects(owners, name, context, methods, environments))
            {
                if (member instanceof com._1c.g5.v8.dt.mcore.Method method)
                    add(result, method.getRetValType());
                else if (member instanceof Property property)
                    add(result, propertyTypes(property, context, environments));
            }
            return result;
        }

        private List<TypeItem> propertyTypes(Property property, EObject context, Environments environments)
        {
            check();
            ExpressionKey key = new ExpressionKey(new Identity(property), frame, environments);
            List<TypeItem> cached = properties.get(key);
            if (cached != null)
                return cached;
            List<TypeItem> result = new ArrayList<>(property.getTypes());
            if (!activeProperties.add(key))
                return result;
            try
            {
                // Раскрываем только запрошенное поле. Вложенные поля остаются
                // ссылками, поэтому самоссылка в описании не разворачивает схему.
                for (PropertySeeLink link : propertySeeLinks(property, context))
                {
                    check();
                    EObject target = ParamHintHtmlModifier.ParameterSeeLinks.resolveTarget(link.link(), link.method());
                    if (target != null && !target.eIsProxy() && ready(target))
                        add(result, expression(target, environments, null));
                }
                List<TypeItem> computed = List.copyOf(result);
                properties.put(key, computed);
                return computed;
            }
            finally
            {
                activeProperties.remove(key);
            }
        }

        private static void add(List<TypeItem> result, List<TypeItem> types)
        {
            if (types != null)
                for (TypeItem type : types)
                    if (type != null && result.stream().noneMatch(existing -> existing == type))
                        result.add(type);
        }



        private static List<TypeItem> merge(List<TypeItem> original, List<TypeItem> inferred)
        {
            List<TypeItem> result = new ArrayList<>(inferred);
            // Одинаковое имя не означает одинаковый контекст: состояние EDT уже
            // содержит колонки таблицы и их типы из комментариев, которых нет у «Новый».
            // Сохраняем готовые альтернативы без рекурсивного сравнения или копирования.
            add(result, original);
            // Но один и тот же тип из объединения и из сужения — два экземпляра с одним именем;
            // свёрнутый список даёт по одному члену (иначе CTRL+F1 видит два одинаковых).
            dropRepeatedTypes(result);
            return result;
        }

        private static int position(EObject object)
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(object);
            return node == null ? -1 : node.getOffset();
        }

        private final class Plan
        {
            final java.util.Map<Variable, List<Assignment>> assignments = new java.util.IdentityHashMap<>();
            final java.util.Map<Variable, List<Integer>> changes = new java.util.IdentityHashMap<>();
            final java.util.Map<Variable, List<Invocation>> annotations = new java.util.IdentityHashMap<>();
            final List<Loop> loops = new ArrayList<>();
            final List<Expression> returns = new ArrayList<>();

            Plan(Method method)
            {
                for (var iterator = method.eAllContents(); iterator.hasNext();)
                {
                    check();
                    EObject object = iterator.next();
                    ICompositeNode node = NodeModelUtils.findActualNodeFor(object);
                    if (node == null)
                        continue;
                    if (object instanceof SimpleStatement statement && statement.getRight() != null)
                    {
                        if (statement.getLeft() instanceof StaticFeatureAccess access && sourceVariable(access) != null)
                            assignments.computeIfAbsent(sourceVariable(access), ignored -> new ArrayList<>()).add(new Assignment(node.getEndOffset(), statement.getRight()));
                        else
                        {
                            Expression left = statement.getLeft();
                            Expression owner = left instanceof IndexAccess index ? index.getSource()
                                : left instanceof DynamicFeatureAccess access ? access.getSource() : null;
                            changed(owner, node.getEndOffset());
                        }
                    }
                    else if (object instanceof Invocation call && call.getMethodAccess() instanceof DynamicFeatureAccess access)
                    {
                        changed(access.getSource(), node.getEndOffset());
                        Variable variable = sourceVariable(access.getSource());
                        if (variable != null && insertedKey(call) != null)
                            annotations.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(call);
                    }
                    else if (object instanceof com._1c.g5.v8.dt.bsl.model.ReturnStatement statement && statement.getExpression() != null)
                        returns.add(statement.getExpression());
                    else if (object instanceof com._1c.g5.v8.dt.bsl.model.ForEachStatement loop)
                    {
                        ICompositeNode collection = NodeModelUtils.findActualNodeFor(loop.getCollection());
                        if (collection != null)
                            loops.add(new Loop(sourceVariable(loop.getVariableAccess()), loop.getCollection(), collection.getEndOffset(), node.getEndOffset()));
                    }
                }
            }

            private void changed(Expression source, int offset)
            {
                Variable variable = sourceVariable(source);
                if (variable != null)
                    changes.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(offset);
            }
        }
    }

    /** ИР: СобратьДинамическиеСвойстваОбъекта — ключи соответствия и значения двух первых добавлений. */
    private static final class CollectionValueTypes
    {
        private static final ThreadLocal<Set<Variable>> RESOLVING = ThreadLocal.withInitial(
            () -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

        static List<TypeItem> infer(List<TypeItem> types, Variable variable, int offset, Environments environments)
        {
            Set<Variable> resolving = RESOLVING.get();
            if (!resolving.add(variable))
                return types;
            try
            {
                if (!(variable.eResource() instanceof XtextResource resource))
                    return types;
                Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
                if (method == null && !resource.getContents().isEmpty()
                    && resource.getContents().get(0) instanceof Module module)
                    for (Method candidate : module.allMethods())
                    {
                        ICompositeNode node = NodeModelUtils.findActualNodeFor(candidate);
                        if (node != null && offset >= node.getOffset() && offset <= node.getEndOffset())
                        {
                            method = candidate;
                            break;
                        }
                    }
                if (method == null)
                    return types;
                Plan plan = Plan.of(method);
                if (!plan.loopsByVariable.containsKey(variable) && types.stream().noneMatch(item ->
                    item instanceof Type && ("Map".equals(McoreUtil.getTypeName(item))
                        || "ValueList".equals(McoreUtil.getTypeName(item)))))
                    return types;
                TypesComputer computer = resource.getResourceServiceProvider().get(TypesComputer.class);
                types = loopTypes(types, variable, offset, environments, plan, computer);
                List<TypeItem> result = new ArrayList<>();
                for (TypeItem item : types)
                {
                    String name = McoreUtil.getTypeName(item);
                    if (!(item instanceof Type collection) || !("Map".equals(name) || "ValueList".equals(name))) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        result.add(item);
                        continue;
                    }
                    boolean map = "Map".equals(name); //$NON-NLS-1$
                    int from = plan.assignments.getOrDefault(variable, List.of()).stream()
                        .filter(position -> position <= offset).mapToInt(Integer::intValue).max().orElse(0);
                    List<TypeItem> keys = new ArrayList<>();
                    List<TypeItem> values = new ArrayList<>();
                    int count = 0;
                    for (Addition addition : plan.additions.getOrDefault(variable, List.of()))
                    {
                        if (addition.start < from || addition.end > offset || addition.map != map
                            || !addition.environments.containsAny(environments))
                            continue;
                        if (++count > 2)
                            break;
                        if (map && addition.key != null)
                            for (TypeItem key : computer.computeTypes(addition.key, environments))
                                ArrayElementTypes.addElement(keys, key);
                        if (addition.value != null)
                            for (TypeItem value : computer.computeTypes(addition.value, environments))
                                ArrayElementTypes.addElement(values, value);
                    }
                    result.add(keys.isEmpty() && values.isEmpty() ? item
                        : specialize(collection, keys, values, variable, map));
                }
                return result;
            }
            catch (RuntimeException | LinkageError failure)
            {
                return types;
            }
            finally
            {
                resolving.remove(variable);
                if (resolving.isEmpty())
                    RESOLVING.remove();
            }
        }

        private static List<TypeItem> loopTypes(List<TypeItem> types, Variable variable, int offset,
            Environments environments, Plan plan, TypesComputer computer)
        {
            for (var loop : plan.loopsByVariable.getOrDefault(variable, List.of()))
            {
                ICompositeNode node = NodeModelUtils.findActualNodeFor(loop);
                ICompositeNode collectionNode = NodeModelUtils.findActualNodeFor(loop.getCollection());
                Environmental owner = EcoreUtil2.getContainerOfType(loop, Environmental.class);
                if (node == null || collectionNode == null || offset < collectionNode.getEndOffset()
                    || offset > node.getEndOffset() || sourceVariable(loop.getVariableAccess()) != variable
                    || owner == null || !owner.environments().containsAny(environments)
                    || plan.assignments.getOrDefault(variable, List.of()).stream()
                        .anyMatch(position -> position > collectionNode.getEndOffset() && position <= offset))
                    continue;
                List<TypeItem> elements = new ArrayList<>();
                for (TypeItem item : computer.computeTypes(loop.getCollection(), environments))
                    if (item instanceof Type collection
                        && ("Map".equals(McoreUtil.getTypeName(item)) || "ValueList".equals(McoreUtil.getTypeName(item))) //$NON-NLS-1$ //$NON-NLS-2$
                        && collection.getCollectionElementTypes() != null)
                        elements.addAll(collection.getCollectionElementTypes().allTypes());
                if (!elements.isEmpty())
                    return elements;
            }
            return types;
        }

        /** Копируем только экземпляр типа; платформенные свойства и методы не меняем. */
        private static Type specialize(Type source, List<TypeItem> keys, List<TypeItem> values,
            EObject context, boolean map)
        {
            Type result = EcoreUtil2.cloneWithProxies(source);
            if (source.getCollectionElementTypes() == null)
                return source;
            var elements = McoreFactory.eINSTANCE.createTypeContainerDef();
            Type element = null;
            for (TypeItem item : source.getCollectionElementTypes().allTypes())
            {
                EObject resolved = EcoreUtil.resolve(item, context);
                if (!(resolved instanceof Type type))
                    continue;
                Type copy = EcoreUtil2.cloneWithProxies(type);
                for (Property property : copy.getContextDef().getProperties())
                {
                    List<TypeItem> inferred = "Value".equals(property.getName()) ? values //$NON-NLS-1$
                        : map && "Key".equals(property.getName()) ? keys : List.of(); //$NON-NLS-1$
                    if (inferred.isEmpty())
                        continue;
                    List<TypeItem> combined = new ArrayList<>(inferred);
                    for (TypeItem existing : property.getTypes())
                        if (!"Arbitrary".equals(McoreUtil.getTypeName(existing))) //$NON-NLS-1$
                            ArrayElementTypes.addElement(combined, existing);
                    TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
                    container.getTypes().addAll(combined);
                    property.setTypeContainer(container);
                }
                elements.getTypes().add(copy);
                element = copy;
            }
            if (element == null)
                return source;
            result.setCollectionElementTypes(elements);
            for (com._1c.g5.v8.dt.mcore.Method method : result.getContextDef().getMethods())
            {
                if (map && "Get".equals(method.getName()) && !values.isEmpty()) //$NON-NLS-1$
                {
                    method.getRetValType().removeIf(type -> "Arbitrary".equals(McoreUtil.getTypeName(type))); //$NON-NLS-1$
                    for (TypeItem value : values)
                        if (!method.getRetValType().contains(value))
                            method.getRetValType().add(value);
                }
                else if (!map)
                    for (int index = 0; index < method.getRetValType().size(); index++)
                        if ("ValueListItem".equals(McoreUtil.getTypeName(method.getRetValType().get(index)))) //$NON-NLS-1$
                            method.getRetValType().set(index, element);
            }
            return result;
        }

        private static record Addition(int start, int end, Expression key, Expression value,
            boolean map, Environments environments) {}

        private static final class Plan extends org.eclipse.emf.common.notify.impl.AdapterImpl
        {
            final ICompositeNode node;
            final java.util.Map<Variable, List<Addition>> additions = new java.util.IdentityHashMap<>();
            final java.util.Map<Variable, List<Integer>> assignments = new java.util.IdentityHashMap<>();
            final java.util.Map<Variable, List<com._1c.g5.v8.dt.bsl.model.ForEachStatement>> loopsByVariable =
                new java.util.IdentityHashMap<>();

            Plan(Method method, ICompositeNode node)
            {
                this.node = node;
                List<com._1c.g5.v8.dt.bsl.model.ForEachStatement> loops = new ArrayList<>();
                List<SimpleStatement> statements = new ArrayList<>();
                List<Invocation> invocations = new ArrayList<>();
                int visited = 0;
                // Один обход AST; обработка присвоений и вызовов остаётся в прежнем порядке.
                for (var iterator = method.eAllContents(); iterator.hasNext();)
                {
                    EObject object = iterator.next();
                    visited++;
                    if (object instanceof com._1c.g5.v8.dt.bsl.model.ForEachStatement loop)
                        loops.add(loop);
                    if (object instanceof SimpleStatement statement)
                        statements.add(statement);
                    if (object instanceof Invocation invocation)
                        invocations.add(invocation);
                }
                loops.sort(java.util.Comparator.comparingInt((com._1c.g5.v8.dt.bsl.model.ForEachStatement loop) -> {
                    ICompositeNode position = NodeModelUtils.findActualNodeFor(loop);
                    return position == null ? -1 : position.getOffset();
                }).reversed());
                for (SimpleStatement statement : statements)
                {
                    if (statement.getRight() == null)
                        continue;
                    ICompositeNode position = NodeModelUtils.findActualNodeFor(statement);
                    if (statement.getLeft() instanceof StaticFeatureAccess access)
                    {
                        Variable variable = sourceVariable(access);
                        if (variable != null && position != null)
                            assignments.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(position.getEndOffset());
                    }
                    else if (statement.getLeft() instanceof IndexAccess index)
                        add(index.getSource(), index.getIndex(), statement.getRight(), true, statement);
                }
                for (Invocation invocation : invocations)
                {
                    if (!(invocation.getMethodAccess() instanceof DynamicFeatureAccess access))
                        continue;
                    List<Expression> params = invocation.getParams();
                    String name = access.getName();
                    if (("Вставить".equalsIgnoreCase(name) || "Insert".equalsIgnoreCase(name)) && params.size() >= 2) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        add(access.getSource(), params.get(0), params.get(1), true, invocation);
                        add(access.getSource(), null, params.get(1), false, invocation);
                    }
                    else if (("Добавить".equalsIgnoreCase(name) || "Add".equalsIgnoreCase(name)) && !params.isEmpty()) //$NON-NLS-1$ //$NON-NLS-2$
                        add(access.getSource(), null, params.get(0), false, invocation);
                }
                additions.values().forEach(entries -> entries.sort(java.util.Comparator.comparingInt(Addition::start)));
                for (var loop : loops)
                {
                    Variable variable = sourceVariable(loop.getVariableAccess());
                    if (variable != null)
                        loopsByVariable.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(loop);
                }
            }

            private void add(Expression source, Expression key, Expression value, boolean map, EObject expression)
            {
                // Изменение вложенной коллекции не является добавлением в корневую переменную.
                if (!(source instanceof StaticFeatureAccess))
                    return;
                Variable variable = sourceVariable(source);
                ICompositeNode position = NodeModelUtils.findActualNodeFor(expression);
                Environmental owner = EcoreUtil2.getContainerOfType(expression, Environmental.class);
                if (variable != null && position != null && owner != null)
                    additions.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(new Addition(
                        position.getOffset(), position.getEndOffset(), key, value, map, owner.environments()));
            }

            static Plan of(Method method)
            {
                ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
                for (var iterator = method.eAdapters().iterator(); iterator.hasNext();)
                    if (iterator.next() instanceof Plan existing)
                    {
                        if (existing.node == node)
                            return existing;
                        iterator.remove();
                    }
                Plan plan = new Plan(method, node);
                method.eAdapters().add(plan);
                return plan;
            }
        }
    }

    /** ИР: СобратьДинамическиеСвойстваОбъекта, ветка Массив — первые два Добавить/Вставить в диапазоне. */
    private static final class ArrayElementTypes
    {
        private static final String CALLBACK = "tormozit.arrayElementTypes.variableTypes"; //$NON-NLS-1$
        private static final ThreadLocal<Set<TypeItem>> REFERENCES = ThreadLocal.withInitial(
            () -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        private static final ThreadLocal<Set<Variable>> RESOLVING = ThreadLocal.withInitial(
            () -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

        static void install()
        {
            System.getProperties().put(CALLBACK, (java.util.function.Function<Object, Object>) payload -> {
                Object[] args = (Object[])payload;
                @SuppressWarnings("unchecked")
                List<TypeItem> types = (List<TypeItem>)args[0];
                if (args.length == 3)
                    return arrayExpressionTypes(types, (EObject)args[1], (Environments)args[2]);
                return arrayVariableTypes(types, (Variable)args[1], ((Integer)args[2]).intValue(),
                    (Environments)args[3]);
            });
            System.getProperties().put("tormozit.arrayElementTypes.reference", //$NON-NLS-1$
                (java.util.function.Function<Object, Object>) payload -> arrayReference((Object[])payload));
        }

        private static Object arrayReference(Object[] args)
        {
            Object original = args[0];
            TypeItem item = (TypeItem)args[1];
            Resource resource = (Resource)args[2];
            if (original == null || !(item instanceof Type collection)
                || !(item instanceof com._1c.g5.v8.dt.bsl.model.ExtendedCollectionType
                    || "Array".equals(McoreUtil.getTypeName(item))) //$NON-NLS-1$
                || resource == null || resource.getContents().isEmpty()
                || !BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0)))
                return original;
            Set<TypeItem> references = REFERENCES.get();
            if (!references.add(item))
                return original;
            try
            {
                return collectionTypeReference(item, original, args[5], resource,
                    ((Boolean)args[3]).booleanValue(), args[4]);
            }
            catch (Exception | LinkageError failure)
            {
                return original;
            }
            finally
            {
                references.remove(item);
                if (references.isEmpty())
                    REFERENCES.remove();
            }
        }

        static List<TypeItem> infer(List<TypeItem> types, Variable variable, int offset, Environments environments)
        {
            Set<Variable> resolving = RESOLVING.get();
            if (!resolving.add(variable))
                return types;
            try
            {
                Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
                if (method == null && variable.eResource() != null && !variable.eResource().getContents().isEmpty()
                    && variable.eResource().getContents().get(0) instanceof Module module)
                    for (Method candidate : module.allMethods())
                    {
                        ICompositeNode node = NodeModelUtils.findActualNodeFor(candidate);
                        if (node != null && offset >= node.getOffset() && offset <= node.getEndOffset())
                        {
                            method = candidate;
                            break;
                        }
                    }
                if (method == null || !(variable.eResource() instanceof XtextResource resource))
                    return types;
                Plan plan = Plan.of(method);
                List<Addition> additions = plan.additions.getOrDefault(variable, List.of());
                if (additions.isEmpty())
                    return types;
                var services = resource.getResourceServiceProvider();
                TypesComputer computer = services.get(TypesComputer.class);
                List<TypeItem> elements = collect(types, additions, plan.assignments.getOrDefault(variable, List.of()),
                    offset, environments, (expression, envs) -> computer.computeTypes(expression, envs));
                if (elements.isEmpty())
                    return types;
                var versions = services.get(com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport.class);
                var version = versions.getRuntimeVersionOrDefault(variable, com._1c.g5.v8.dt.platform.version.Version.LATEST);
                var provider = com._1c.g5.v8.dt.platform.IEObjectProvider.Registry.INSTANCE
                    .get(McorePackage.Literals.TYPE_ITEM, version);
                Type array = TypeSystemUtil.createExtendedArrayType(elements, provider, variable);
                List<TypeItem> result = new ArrayList<>();
                for (TypeItem item : types)
                    if (!"Array".equals(McoreUtil.getTypeName(item))) //$NON-NLS-1$
                        result.add(item);
                result.add(array);
                return result;
            }
            catch (RuntimeException | LinkageError failure)
            {
                return types;
            }
            finally
            {
                resolving.remove(variable);
                if (resolving.isEmpty())
                    RESOLVING.remove();
            }
        }

        private static List<TypeItem> collect(List<TypeItem> original, List<Addition> additions,
            List<Integer> assignments, int offset, Environments environments,
            java.util.function.BiFunction<Expression, Environments, List<TypeItem>> computer)
        {
            int from = 0;
            for (int assignment : assignments)
                if (assignment <= offset)
                    from = Math.max(from, assignment);
            List<TypeItem> elements = new ArrayList<>();
            for (TypeItem item : original)
                if (item instanceof Type type && "Array".equals(McoreUtil.getTypeName(type)) //$NON-NLS-1$
                    && type.getCollectionElementTypes() != null)
                    for (TypeItem element : type.getCollectionElementTypes().allTypes())
                        if (!"Arbitrary".equals(McoreUtil.getTypeName(element))) //$NON-NLS-1$
                            addElement(elements, element);
            int count = 0;
            for (Addition addition : additions)
            {
                if (addition.start < from || addition.end > offset || !addition.environments.containsAny(environments))
                    continue;
                if (++count > 2)
                    break;
                List<TypeItem> inferred = computer.apply(addition.value, environments);
                for (TypeItem element : inferred)
                    addElement(elements, element);
            }
            return elements;
        }

        private static void addElement(List<TypeItem> elements, TypeItem item)
        {
            if (item == null)
                return;
            String name = McoreUtil.getTypeName(item);
            if (!(item instanceof ExtendedType)
                && !(item instanceof com._1c.g5.v8.dt.bsl.model.ExtendedCollectionType)
                && elements.stream().anyMatch(existing -> java.util.Objects.equals(name, McoreUtil.getTypeName(existing))))
                return;
            if (!elements.contains(item))
                elements.add(item);
        }

        private static Expression addedValue(Invocation invocation)
        {
            if (!(invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
                || !(access.getSource() instanceof StaticFeatureAccess))
                return null;
            List<Expression> args = invocation.getParams();
            String name = access.getName();
            if (("Добавить".equalsIgnoreCase(name) || "Add".equalsIgnoreCase(name)) && args.size() == 1) //$NON-NLS-1$ //$NON-NLS-2$
                return args.get(0);
            if (("Вставить".equalsIgnoreCase(name) || "Insert".equalsIgnoreCase(name)) && args.size() == 2) //$NON-NLS-1$ //$NON-NLS-2$
                return args.get(1);
            return null;
        }

        private static record Addition(int start, int end, Expression value, Environments environments) {}

        private static final class Plan extends org.eclipse.emf.common.notify.impl.AdapterImpl
        {
            private final ICompositeNode node;
            private final java.util.Map<Variable, List<Addition>> additions = new java.util.IdentityHashMap<>();
            private final java.util.Map<Variable, List<Integer>> assignments = new java.util.IdentityHashMap<>();

            private Plan(Method method, ICompositeNode node)
            {
                this.node = node;
                for (com._1c.g5.v8.dt.bsl.model.SimpleStatement statement : EcoreUtil2.getAllContentsOfType(
                    method, com._1c.g5.v8.dt.bsl.model.SimpleStatement.class))
                    if (statement.getLeft() instanceof StaticFeatureAccess access)
                    {
                        Variable variable = sourceVariable(access);
                        ICompositeNode position = NodeModelUtils.findActualNodeFor(statement);
                        if (variable != null && position != null)
                            assignments.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(position.getEndOffset());
                    }
                for (Invocation invocation : EcoreUtil2.getAllContentsOfType(method, Invocation.class))
                {
                    Expression value = addedValue(invocation);
                    if (value == null)
                        continue;
                    Variable variable = sourceVariable(((DynamicFeatureAccess)invocation.getMethodAccess()).getSource());
                    ICompositeNode position = NodeModelUtils.findActualNodeFor(invocation);
                    Environmental owner = EcoreUtil2.getContainerOfType(invocation, Environmental.class);
                    if (variable != null && position != null && owner != null)
                        additions.computeIfAbsent(variable, ignored -> new ArrayList<>()).add(new Addition(
                            position.getOffset(), position.getEndOffset(), value, owner.environments()));
                }
                for (List<Addition> calls : additions.values())
                    calls.sort(java.util.Comparator.comparingInt(Addition::start));
            }

            static Plan of(Method method)
            {
                ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
                for (var iterator = method.eAdapters().iterator(); iterator.hasNext();)
                    if (iterator.next() instanceof Plan existing)
                    {
                        if (existing.node == node)
                            return existing;
                        iterator.remove();
                    }
                Plan plan = new Plan(method, node);
                method.eAdapters().add(plan);
                return plan;
            }
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
            boolean queryScopeOwner = BslQueryResultTypes.beginOpenModuleCalculation(module);
            try (InteractiveTypesScope ignored = suspendInteractiveTypes())
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
                BslQueryResultTypes.endOpenModuleCalculation(queryScopeOwner);
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
            if (partial == null)
            {
                // Полный переразбор: повторный штатный расчёт всего модуля. Готовые состояния тела
                // он не пересоздаёт (параметр в теле оставался без слияния, лог 18.10: «Структура.»
                // — 0 членов), поэтому NORMAL сначала сбрасывается.
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
            SecondPassProgress progress = new SecondPassProgress(module, dependents.size());
            try
            {
                for (Method dependent : dependents)
                {
                    if (stopSecondPass(cancelIndicator))
                    {
                        return;
                    }
                    // Отмена в «Ходе выполнения»: оставшиеся методы сохраняют прежний слитый тип.
                    if (progress.cancelRequestedByUser())
                    {
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
                }
            }
            finally
            {
                progress.finish();
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
                underNonInterruptableBm(this, module, () ->
                {
                    installTypeSystemPass(module, cancelIndicator);
                    return null;
                });
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
                {
                    if (caller instanceof Method callerMethod)
                        dropNormalStates(callerMethod);
                }
                underNonInterruptableBm(this, module, () ->
                {
                    stockInstall(module, cancelIndicator);
                    return null;
                });
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
                resetPartialMethodStates(module, cancelIndicator);
                super.installTypeSystem(module, cancelIndicator);
            }
            finally
            {
                BslDocCommentComputedTypes.releaseCalleeReturns(previous);
            }
        }

        /**
         * Частичный NORMAL-проход EDT пересоздаёт итоговые входы, но getOrInitVariableProvider
         * оставляет прежние состояния параметров и тела. После повторного расчёта одного AST
         * чтение параметра поэтому может идти через старый контекст формы (лог 28.10.2026).
         * Сбрасываем тот же набор методов, что в штатном initFinalStateForMethod:
         * переразобранный и его вызывающие. Такой же сброс используется в recomputeDependent.
         */
        private static void resetPartialMethodStates(Module module, CancelIndicator cancelIndicator)
        {
            if (isCanceled(cancelIndicator)
                || !(module.eResource() instanceof com._1c.g5.v8.dt.bsl.resource.BslResource resource)
                || !resource.isOnlyMethodReparse())
                return;
            Method reparsed = resource.getReparseMethod();
            if (reparsed == null)
                return;
            java.util.Set<Method> methods = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            methods.add(reparsed);
            for (Object caller : com._1c.g5.v8.dt.bsl.util.BslUtil.getDependMethodsTree(reparsed))
                if (caller instanceof Method method)
                    methods.add(method);
            for (Method method : methods)
            {
                dropNormalStates(method);
            }
        }

        private void installTypeSystemPass(Module module, CancelIndicator cancelIndicator)
        {
            long nativeFinished = 0L;
            List<Method> affected = null;
            if (module.eResource() instanceof com._1c.g5.v8.dt.bsl.resource.BslResource resource
                && resource.isOnlyMethodReparse() && resource.getReparseMethod() != null)
            {
                // Тот же набор, что у штатного initFinalStateForMethod: вызывающие и сам метод.
                affected = new ArrayList<>();
                for (Object caller : com._1c.g5.v8.dt.bsl.util.BslUtil.getDependMethodsTree(resource.getReparseMethod()))
                    if (caller instanceof Method method && !affected.contains(method))
                        affected.add(method);
                if (!affected.contains(resource.getReparseMethod()))
                    affected.add(resource.getReparseMethod());
            }
            try
            {
                stockInstall(module, cancelIndicator);
                nativeFinished = System.nanoTime();
                if (isCanceled(cancelIndicator))
                    return;
                ModuleVariableStates.fix(module);
                if (affected == null)
                {
                    BslFormTypeContextEnrichment.enrichModule(module);
                    enrich(this, module, null);
                }
                else
                {
                    enrichExportModuleVariables(module);
                    for (Method method : affected)
                    {
                        if (isCanceled(cancelIndicator))
                            return;
                        BslFormTypeContextEnrichment.enrichMethod(method);
                        enrich(this, module, method);
                    }
                }
            }
            finally
            {
            }
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
            boolean queryScopeOwner = BslQueryResultTypes.beginOpenModuleCalculation(module);
            try (InteractiveTypesScope ignored = suspendInteractiveTypes())
            {
                lightInstallingTypeSystemInScope(module, method, variable, statement, offset, context);
            }
            finally
            {
                BslQueryResultTypes.endOpenModuleCalculation(queryScopeOwner);
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

