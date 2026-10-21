package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.model.BslDerivedPropertySource;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.LoopStatement;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.NumberLiteral;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.typesystem.IInvocationTypesComputerExtension;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainerRef;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.ql.DerivedTypeComputerForDbViewQuery;
import com._1c.g5.v8.dt.ql.dcs.resource.IDependentQuerySchemaProvider;
import com._1c.g5.v8.dt.ql.model.QuerySchema;
import com._1c.g5.v8.dt.ql.model.QuerySchemaExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery;
import com._1c.g5.v8.dt.ql.model.QlPackage;
import com._1c.g5.v8.dt.ql.resource.QlMapper;

/**
 * Поля выборки и выгрузки запроса из отдельного литерала (issue 836).
 * Схему получает тот же сервис, что у проверки «Хаб языка запросов».
 * Расчёт разрешён только из досчёта открытого редактора; расширение системы типов
 * читает готовый план и никогда не запускает разбор запроса из сборки или UI.
 */
public final class BslQueryResultTypes implements IInvocationTypesComputerExtension
{
    private static final Set<String> RESULT_METHODS = Set.of(
        "execute", "выполнить", "executebatch", "выполнитьпакет", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "select", "выбрать", "unload", "выгрузить", "get", "получить"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    @Override
    public List<TypeItem> getTypes(Invocation invocation)
    {
        if (!BslDocCommentComputedTypes.isExtendedTypesEnabled(invocation))
            return List.of();
        Resource resource = invocation.eResource();
        Session session = session(resource, false);
        Method method = EcoreUtil2.getContainerOfType(invocation, Method.class);
        Plan plan = session == null || method == null ? null : session.plans.get(method);
        List<TypeItem> planned = plan == null ? null : plan.types.get(invocation);
        if (planned != null)
            return planned;
        return method == null || !TYPE_WITHOUT_PLAN ? List.of() : queryResultWithoutPlan(method, invocation);
    }

    /**
     * Отключено 21.10.2026: мигание предупреждения «Для каждого» устранила замена штатной проверки
     * ({@code comfort-bsl-for-each-collection}), а расчёт без плана стоит облегчённого плана метода на
     * каждый {@code Х.Выполнить()}, в том числе при сборке. Оставлено на случай, если понадобится.
     */
    private static final boolean TYPE_WITHOUT_PLAN = false;

    /**
     * Тип {@code Запрос.Выполнить()}, пока плана нет: тот же признак запроса, что у плана, но без
     * разбора текста запроса — поэтому без полей. Иначе у вызова штатный {@code [QueryResult, Undefined]},
     * и проверка «Для каждого» молчит до готовности плана, а после — предупреждает (мигание маркера).
     */
    private static List<TypeItem> queryResultWithoutPlan(Method method, Invocation invocation)
    {
        if (!(invocation.getMethodAccess() instanceof DynamicFeatureAccess access))
            return List.of();
        String name = lower(access.getName());
        if (!name.equals("execute") && !name.equals("выполнить")) //$NON-NLS-1$ //$NON-NLS-2$
            return List.of();
        try
        {
            Plan plan = new Plan(method, ""); //$NON-NLS-1$
            plan.originsOnly = true;
            Shape shape = plan.shape(invocation, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (shape == null || !shape.kind.equals("QueryResult")) //$NON-NLS-1$
                return List.of();
            return List.of(plan.baseType("QueryResult")); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError failure)
        {
            return List.of();
        }
    }

    /** Только происхождение обращения к полю; схемы и типы запросов здесь не рассчитываются. */
    static boolean isQueryFieldAt(XtextResource resource, int offset)
    {
        if (resource.getContents().isEmpty()
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0)))
            return false;
        EObject object = new EObjectAtOffsetHelper().resolveContainedElementAt(resource, offset);
        DynamicFeatureAccess access = EcoreUtil2.getContainerOfType(object, DynamicFeatureAccess.class);
        Method method = access == null ? null : EcoreUtil2.getContainerOfType(access, Method.class);
        if (method == null || access.eContainer() instanceof Invocation invocation
            && invocation.getMethodAccess() == access)
            return false;
        ICompositeNode node = NodeModelUtils.findActualNodeFor(access);
        var leaf = node == null ? null : NodeModelUtils.findLeafNodeAtOffset(node, offset);
        if (leaf == null || leaf.isHidden() || !leaf.getText().equalsIgnoreCase(access.getName()))
            return false;
        Plan plan = new Plan(method, ""); //$NON-NLS-1$
        plan.originsOnly = true;
        Shape source = plan.shape(access.getSource(), Collections.newSetFromMap(new IdentityHashMap<>()));
        return source != null && (source.kind.equals("QueryResultSelection") || source.kind.equals("ValueTableRow")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Кандидаты быстрого исправления: только готовый план, без расчёта схемы в UI. */
    static List<String> fieldNamesAt(XtextResource resource, int offset)
    {
        List<String> names = new ArrayList<>();
        if (resource.getContents().isEmpty()
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0)))
            return List.of();
        Object lock = Global.invoke(resource, "getLock"); //$NON-NLS-1$
        if (lock == null)
            return List.of();
        synchronized (lock)
        {
            EObject object = new EObjectAtOffsetHelper().resolveContainedElementAt(resource, offset);
            DynamicFeatureAccess access = EcoreUtil2.getContainerOfType(object, DynamicFeatureAccess.class);
            Method method = access == null ? null : EcoreUtil2.getContainerOfType(access, Method.class);
            Session session = session(resource, false);
            Plan plan = session == null || method == null ? null : session.plans.get(method);
            ICompositeNode node = method == null ? null : NodeModelUtils.findActualNodeFor(method);
            if (plan == null || node == null || !node.getText().equals(plan.text)
                || access.eContainer() instanceof Invocation invocation && invocation.getMethodAccess() == access)
                return List.of();
            names.addAll(plan.fieldCandidates.getOrDefault(access, List.of()));
        }
        return names;
    }

    /**
     * Общий результат для автопроверки отдельного бандла. Подготовка схемы выполняется
     * один раз для текущей версии метода; проверка не меняет состояния типов BSL.
     */
    public static List<DynamicFeatureAccess> unknownFields(Module module, CancelIndicator cancellation)
    {
        List<DynamicFeatureAccess> problems = new ArrayList<>();
        if (module == null || !(module.eResource() instanceof XtextResource)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(module))
            return List.of();
        Object lock = Global.invoke(module.eResource(), "getLock"); //$NON-NLS-1$
        if (lock == null)
            return List.of();
        synchronized (lock)
        {
            Session session = session(module.eResource(), true);
            session.plans.keySet().removeIf(old -> !module.allMethods().contains(old));
            for (Method method : module.allMethods())
            {
                if (cancellation.isCanceled())
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
                if (node == null)
                    continue;
                Plan plan = session.plans.get(method);
                String text = node.getText();
                if (plan == null || !text.equals(plan.text))
                {
                    session.plans.remove(method);
                    plan = new Plan(method, text);
                    plan.originsOnly = true;
                    try
                    {
                        // Без обращений к полям схемы для автопроверки не нужны.
                        if (!plan.hasQueryFieldAccess(cancellation))
                        {
                            continue;
                        }
                    }
                    finally
                    {
                        plan.originsOnly = false;
                    }
                    plan.cancellation = cancellation;
                    try
                    {
                        plan.prepare();
                    }
                    finally
                    {
                        plan.cancellation = CancelIndicator.NullImpl;
                    }
                    session.plans.put(method, plan);
                }
                problems.addAll(plan.unknownFields(cancellation));
            }
        }
        return problems;
    }

    /** Вызывающий держит readOnly документа и блокировку перевязки ресурса. */
    static void refresh(Resource resource, int offset, Consumer<String> status,
        Predicate<Method> recompute)
    {
        if (!(resource instanceof XtextResource xtext)
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(
                resource.getContents().isEmpty() ? null : resource.getContents().get(0)))
            return;
        Method method = EcoreUtil2.getContainerOfType(
            new EObjectAtOffsetHelper().resolveContainedElementAt(xtext, offset), Method.class);
        ICompositeNode node = method == null ? null : NodeModelUtils.findActualNodeFor(method);
        if (node == null)
            return;
        Session session = session(resource, true);
        String text = node.getText();
        Plan previous = session.plans.get(method);
        if (previous != null && previous.complete && text.equals(previous.text))
        {
            return;
        }
        try
        {
            Plan plan = previous != null && text.equals(previous.text) ? previous : new Plan(method, text);
            // Валидация могла уже подготовить план, не переустанавливая типы BSL.
            if (plan != previous)
                plan.prepare();
            // В плане нет ссылок на другие методы: при частичном переразборе заменяется
            // только он. Старые методы удаляем, чтобы ресурс редактора их не удерживал.
            Module module = EcoreUtil2.getContainerOfType(method, Module.class);
            if (module != null)
                session.plans.keySet().removeIf(old -> !module.allMethods().contains(old));
            session.plans.put(method, plan);
            if (!plan.types.isEmpty() || previous != null && !previous.types.isEmpty())
            {
                status.accept("Расчёт типов результата запроса…"); //$NON-NLS-1$
                boolean recomputed = recompute.test(method);
                if (!recomputed)
                {
                    return;
                }
                BslDocCommentComputedTypes.bumpTypesGeneration();
            }
            plan.complete = true;
        }
        catch (RuntimeException | LinkageError failure)
        {
            // Не сохраняем неудачу как готовый результат: следующий запрос повторит расчёт.
            session.plans.remove(method);
        }
    }

    private static Session session(Resource resource, boolean create)
    {
        if (resource == null)
            return null;
        for (var adapter : resource.eAdapters())
            if (adapter instanceof Session session)
                return session;
        if (!create)
            return null;
        Session session = new Session();
        resource.eAdapters().add(session);
        return session;
    }

    /** Общий адрес для команды определения, Ctrl+клика и ссылок в обеих подсказках. */
    static BslStructureInsertCommentTypes.PropertySourceTarget sourceTarget(
        com._1c.g5.v8.dt.mcore.Property property, BslDerivedPropertySource source, Module module)
    {
        Session session = session(module.eResource(), false);
        if (session == null || property.getName() == null)
            return null;
        for (Plan plan : session.plans.values())
        {
            if (!plan.method.getName().equalsIgnoreCase(source.getMethodName()))
                continue;
            ICompositeNode node = NodeModelUtils.findActualNodeFor(plan.method);
            if (node == null || !plan.text.equals(node.getText()))
                return null;
            return plan.sources.get(sourceKey(property.getName(), source.getLocalOffset()));
        }
        return null;
    }

    private static String sourceKey(String name, int localOffset)
    {
        return lower(name) + "/" + localOffset; //$NON-NLS-1$
    }

    private static final class Session extends AdapterImpl
    {
        final Map<Method, Plan> plans = new java.util.concurrent.ConcurrentHashMap<>();
    }

    private record Field(String name, String nameRu, List<TypeItem> types,
        URI literalUri, int offset, int length) {}
    private record Result(List<Field> fields, boolean ordinary, boolean dropped) {}
    private record Shape(String kind, List<Result> results) {}

    private static final class Plan
    {
        final Method method;
        final String text;
        final Map<Invocation, List<TypeItem>> types = new IdentityHashMap<>();
        final Map<StringLiteral, List<Result>> queries = new IdentityHashMap<>();
        final Map<String, BslStructureInsertCommentTypes.PropertySourceTarget> sources = new java.util.HashMap<>();
        final List<SimpleStatement> assignments;
        List<StaticFeatureAccess> staticAccesses;
        final Map<String, Set<String>> nativeProperties = new java.util.HashMap<>();
        final Map<DynamicFeatureAccess, List<String>> fieldCandidates = new IdentityHashMap<>();
        boolean complete;
        boolean originsOnly;
        CancelIndicator cancellation = CancelIndicator.NullImpl;

        Plan(Method method, String text)
        {
            this.method = method;
            this.text = text;
            assignments = EcoreUtil2.getAllContentsOfType(method, SimpleStatement.class);
        }

        void prepare()
        {
            for (Invocation invocation : EcoreUtil2.getAllContentsOfType(method, Invocation.class))
            {
                if (cancellation.isCanceled())
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                if (!(invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
                    || !RESULT_METHODS.contains(lower(access.getName())))
                    continue;
                Shape shape = shape(invocation, Collections.newSetFromMap(new IdentityHashMap<>()));
                if (shape == null)
                    continue;
                Type type = resultType(shape);
                if (type != null)
                    types.put(invocation, List.of(type));
            }
        }

        boolean hasQueryFieldAccess(CancelIndicator indicator)
        {
            for (DynamicFeatureAccess access : EcoreUtil2.getAllContentsOfType(method, DynamicFeatureAccess.class))
            {
                if (indicator.isCanceled())
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                if (access.eContainer() instanceof Invocation invocation && invocation.getMethodAccess() == access)
                    continue;
                Shape source = shape(access.getSource(), Collections.newSetFromMap(new IdentityHashMap<>()));
                if (source != null && source.kind.equals("QueryResultSelection")) //$NON-NLS-1$
                    return true;
            }
            return false;
        }

        List<DynamicFeatureAccess> unknownFields(CancelIndicator indicator)
        {
            List<DynamicFeatureAccess> problems = new ArrayList<>();
            fieldCandidates.clear();
            for (DynamicFeatureAccess access : EcoreUtil2.getAllContentsOfType(method, DynamicFeatureAccess.class))
            {
                if (indicator.isCanceled())
                    throw new org.eclipse.core.runtime.OperationCanceledException();
                if (access.getName() == null || access.getName().isBlank()
                    || access.eContainer() instanceof Invocation invocation && invocation.getMethodAccess() == access)
                    continue;
                Shape source = shape(access.getSource(), Collections.newSetFromMap(new IdentityHashMap<>()));
                // Выборка неизменяема; у выгруженной таблицы колонки могут добавляться
                // после запроса, поэтому одной его схемы для строгой проверки недостаточно.
                if (source == null || !source.kind.equals("QueryResultSelection") //$NON-NLS-1$
                    || source.results.size() != 1 || !source.results.get(0).ordinary)
                    continue;
                String name = lower(access.getName());
                boolean found = source.results.get(0).fields.stream()
                    .anyMatch(field -> name.equals(lower(field.name)) || name.equals(lower(field.nameRu)));
                if (!found && !nativeProperties.computeIfAbsent(source.kind, kind ->
                    {
                        Set<String> names = new java.util.HashSet<>();
                        for (var property : baseType(kind).getContextDef().allProperties())
                        {
                            names.add(lower(property.getName()));
                            names.add(lower(property.getNameRu()));
                        }
                        return names;
                    }).contains(name))
                {
                    problems.add(access);
                    Set<String> names = new java.util.LinkedHashSet<>();
                    for (Field field : source.results.get(0).fields)
                    {
                        if (field.name != null && !field.name.isBlank())
                            names.add(field.name);
                        if (field.nameRu != null && !field.nameRu.isBlank())
                            names.add(field.nameRu);
                    }
                    fieldCandidates.put(access, List.copyOf(names));
                }
            }
            return problems;
        }

        private Shape shape(Expression expression, Set<EObject> visiting)
        {
            if (expression == null || !visiting.add(expression))
                return null;
            try
            {
                Variable variable = variable(expression);
                if (variable != null)
                {
                    if (hasLoopWrite(variable, expression, false))
                        return null;
                    SimpleStatement assignment = lastAssignment(variable, expression, false, -1);
                    return assignment == null || !dominates(assignment, expression)
                        ? null : shape(assignment.getRight(), visiting);
                }
                if (expression instanceof IndexAccess index)
                    return indexed(shape(index.getSource(), visiting), index.getIndex());
                if (!(expression instanceof Invocation invocation)
                    || !(invocation.getMethodAccess() instanceof DynamicFeatureAccess access))
                    return null;
                String name = lower(access.getName());
                if (name.equals("execute") || name.equals("выполнить") //$NON-NLS-1$ //$NON-NLS-2$
                    || name.equals("executebatch") || name.equals("выполнитьпакет")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    if (!invocation.getParams().isEmpty())
                        return null;
                    StringLiteral literal = queryLiteral(access.getSource(), invocation);
                    if (literal == null)
                        return null;
                    List<Result> results = originsOnly
                        ? List.of(new Result(List.of(), true, false)) : query(literal);
                    if (results.isEmpty())
                        return null;
                    if (name.equals("execute") || name.equals("выполнить")) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        Result result = results.get(results.size() - 1);
                        for (int i = results.size() - 1; i >= 0; i--)
                            if (results.get(i).ordinary)
                            {
                                result = results.get(i);
                                break;
                            }
                        return single(result);
                    }
                    return new Shape("Array", results); //$NON-NLS-1$
                }
                Shape source = shape(access.getSource(), visiting);
                if (source == null)
                    return null;
                if ((name.equals("get") || name.equals("получить")) && invocation.getParams().size() == 1) //$NON-NLS-1$ //$NON-NLS-2$
                    return indexed(source, invocation.getParams().get(0));
                if (source.kind.equals("QueryResult") || source.kind.equals("QueryResultSelection")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    if (name.equals("select") || name.equals("выбрать")) //$NON-NLS-1$ //$NON-NLS-2$
                        return new Shape("QueryResultSelection", source.results); //$NON-NLS-1$
                    if ((name.equals("unload") || name.equals("выгрузить")) && invocation.getParams().isEmpty()) //$NON-NLS-1$ //$NON-NLS-2$
                        return new Shape("ValueTable", source.results); //$NON-NLS-1$
                }
                return null;
            }
            finally
            {
                visiting.remove(expression);
            }
        }

        private Shape indexed(Shape source, Expression index)
        {
            if (source == null || !(index instanceof NumberLiteral number))
                return null;
            try
            {
                int position = Integer.parseInt(String.join("", number.getValue())); //$NON-NLS-1$
                if (source.kind.equals("ValueTable") && position >= 0) //$NON-NLS-1$
                    return new Shape("ValueTableRow", source.results); //$NON-NLS-1$
                if (!source.kind.equals("Array")) //$NON-NLS-1$
                    return null;
                if (originsOnly && position >= 0)
                    return single(source.results.get(0));
                return position < 0 || position >= source.results.size() ? null
                    : single(source.results.get(position));
            }
            catch (NumberFormatException ignored)
            {
                return null;
            }
        }

        private Shape single(Result result)
        {
            return new Shape(result.dropped ? "Undefined" : "QueryResult", List.of(result)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private StringLiteral queryLiteral(Expression expression, Invocation execute)
        {
            Variable variable = variable(expression);
            SimpleStatement creation = variable == null ? null : lastAssignment(variable, execute, false, -1);
            if (creation != null && !dominates(creation, execute))
                return null;
            if (variable != null && (hasLoopWrite(variable, execute, false)
                || hasLoopWrite(variable, execute, true) || escaped(variable, creation, execute)))
                return null;
            Expression constructor = creation == null ? expression : creation.getRight();
            if (!(constructor instanceof OperatorStyleCreator creator)
                || !"Query".equals(McoreUtil.getTypeName(creator.getType()))) //$NON-NLS-1$
                return null;
            if (variable != null)
            {
                SimpleStatement textAssignment = lastAssignment(variable, execute, true, end(creation));
                if (textAssignment != null)
                    return dominates(textAssignment, execute)
                        && textAssignment.getRight() instanceof StringLiteral literal ? literal : null;
            }
            return creator.getParams().size() == 1 && creator.getParams().get(0) instanceof StringLiteral literal
                ? literal : null;
        }

        /**
         * Только однозначное присваивание до использования. Запись в соседней ветви
         * не выбирается по текстовому смещению: если она могла изменить значение,
         * результат не определяем. Текст через переменную/конкатенацию не вычисляем.
         */
        private SimpleStatement lastAssignment(Variable target, EObject use, boolean queryText, int after)
        {
            SimpleStatement last = null;
            int position = start(use);
            for (SimpleStatement assignment : assignments)
            {
                if (assignment.getRight() == null || end(assignment) > position || start(assignment) <= after)
                    continue;
                Expression left = assignment.getLeft();
                if (queryText)
                {
                    if (!(left instanceof DynamicFeatureAccess access)
                        || !(lower(access.getName()).equals("text") || lower(access.getName()).equals("текст")) //$NON-NLS-1$ //$NON-NLS-2$
                        || variable(access.getSource()) != target)
                        continue;
                }
                else if (variable(left) != target)
                    continue;
                if (last == null || start(assignment) > start(last))
                    last = assignment;
            }
            return last;
        }

        /** Поздняя запись в теле цикла достигает использования на следующей итерации. */
        private boolean hasLoopWrite(Variable target, EObject use, boolean queryText)
        {
            for (EObject parent = use.eContainer(); parent != null && parent != method; parent = parent.eContainer())
            {
                if (!(parent instanceof LoopStatement))
                    continue;
                for (SimpleStatement assignment : assignments)
                {
                    if (assignment.getRight() == null || start(assignment) <= start(use)
                        || !EcoreUtil.isAncestor(parent, assignment))
                        continue;
                    Expression left = assignment.getLeft();
                    if (!queryText && variable(left) == target)
                        return true;
                    if (queryText && left instanceof DynamicFeatureAccess access
                        && (lower(access.getName()).equals("text") || lower(access.getName()).equals("текст")) //$NON-NLS-1$ //$NON-NLS-2$
                        && variable(access.getSource()) == target)
                        return true;
                }
            }
            return false;
        }

        /** Передача запроса наружу/другой переменной допускает изменение его текста по другой ссылке. */
        private boolean escaped(Variable target, SimpleStatement creation, Invocation execute)
        {
            if (staticAccesses == null)
                staticAccesses = EcoreUtil2.getAllContentsOfType(method, StaticFeatureAccess.class);
            for (StaticFeatureAccess access : staticAccesses)
            {
                if (variable(access) != target || start(access) <= end(creation) || end(access) >= start(execute))
                    continue;
                SimpleStatement assignment = EcoreUtil2.getContainerOfType(access, SimpleStatement.class);
                if (assignment != null && assignment.getRight() == access)
                    return true;
                Invocation invocation = EcoreUtil2.getContainerOfType(access, Invocation.class);
                if (invocation != null && invocation.getParams().stream()
                    .anyMatch(param -> param == access || EcoreUtil.isAncestor(param, access)))
                    return true;
            }
            return false;
        }

        /** Присваивание и использование проходят через один список операторов одной ветви. */
        private boolean dominates(SimpleStatement assignment, EObject use)
        {
            for (EObject statement = assignment; statement.eContainer() != null; statement = statement.eContainer())
            {
                if (!(statement instanceof Statement) || !statement.eContainingFeature().isMany())
                    continue;
                for (EObject current = use; current != null; current = current.eContainer())
                    if (current.eContainer() == statement.eContainer()
                        && current.eContainingFeature() == statement.eContainingFeature())
                        return true;
                return false;
            }
            return false;
        }

        private List<Result> query(StringLiteral literal)
        {
            List<Result> cached = queries.get(literal);
            if (cached != null)
            {
                return cached;
            }
            IDependentQuerySchemaProvider provider = Global.getOsgiService(IDependentQuerySchemaProvider.class);
            if (provider == null)
                throw new IllegalStateException("IDependentQuerySchemaProvider unavailable"); //$NON-NLS-1$
            DefaultStringLiteralTextProvider textProvider = new DefaultStringLiteralTextProvider(literal);
            QuerySchema schema = provider.getQuerySchema(literal,
                com._1c.g5.v8.dt.bsl.model.BslPackage.Literals.STRING_LITERAL__LINES,
                (object, feature) -> textProvider.getText(), cancellation);
            if (schema == null || schema.eResource() == null)
                throw new IllegalStateException("Query schema unavailable"); //$NON-NLS-1$
            // DependentModelProvider.createDependentModel уже вызывает
            // EcoreUtil2.resolveLazyCrossReferences перед возвратом схемы.
            if (!schema.eResource().getErrors().isEmpty())
            {
                queries.put(literal, List.of());
                return List.of();
            }
            DerivedTypeComputerForDbViewQuery computer =
                ((XtextResource)schema.eResource()).getResourceServiceProvider()
                    .get(DerivedTypeComputerForDbViewQuery.class);
            QlMapper mapper = ((XtextResource)schema.eResource()).getResourceServiceProvider().get(QlMapper.class);
            int firstLine = NodeModelUtils.findNodesForFeature(literal,
                com._1c.g5.v8.dt.bsl.model.BslPackage.Literals.STRING_LITERAL__LINES).get(0).getOffset();
            URI literalUri = EcoreUtil.getURI(literal);
            List<Result> results = new ArrayList<>();
            for (var query : schema.getQueries())
            {
                List<Field> fields = new ArrayList<>();
                if (query instanceof QuerySchemaSelectQuery select && select.getPlacementTable() == null)
                {
                    var view = select.getDbViewByThis();
                    if (view == null || view.eIsProxy())
                        throw new IllegalStateException("Query result fields unavailable"); //$NON-NLS-1$
                    for (DbViewFieldDef field : view.getActualFields())
                    {
                        TypeDescription type = computer.getFieldType(field).getFirst();
                        int offset;
                        int length;
                        INode source = fieldNode(field, select, mapper);
                        var location = source == null ? null : textProvider.getLocation(source.getOffset());
                        var endLocation = source == null ? null : textProvider.getLocation(source.getEndOffset());
                        offset = location == null ? -1 : firstLine + location.getSecond();
                        length = location == null || endLocation == null ? 0
                            : endLocation.getSecond() - location.getSecond();
                        fields.add(new Field(field.getName(), field.getNameRu(),
                            type == null ? List.of() : List.copyOf(type.getTypes()),
                            literalUri, offset, length));
                    }
                }
                // Сохраняем место УНИЧТОЖИТЬ/ПОМЕСТИТЬ в пакете. Его колонки
                // не равны колонкам SELECT: их здесь не подставляем.
                results.add(new Result(List.copyOf(fields),
                    query instanceof QuerySchemaSelectQuery select && select.getPlacementTable() == null,
                    query instanceof com._1c.g5.v8.dt.ql.model.QuerySchemaDropTableQuery));
            }
            cached = List.copyOf(results);
            queries.put(literal, cached);
            return cached;
        }

        /** Псевдоним SELECT; без псевдонима — имя поля, иначе само выражение (в том числе *). */
        private INode fieldNode(DbViewFieldDef field, QuerySchemaSelectQuery select, QlMapper mapper)
        {
            QuerySchemaExpression expression = mapper.getSourceObjects(field).stream()
                .filter(object -> object instanceof QuerySchemaExpression
                    && EcoreUtil2.getContainerOfType(object, QuerySchemaSelectQuery.class) == select
                    && NodeModelUtils.findActualNodeFor(object) != null)
                .map(object -> (QuerySchemaExpression)object)
                .min(java.util.Comparator.comparingInt(BslQueryResultTypes::start)).orElse(null);
            if (expression == null)
                return null;
            List<INode> aliases = NodeModelUtils.findNodesForFeature(expression,
                QlPackage.Literals.QUERY_SCHEMA_EXPRESSION__ALIAS);
            if (!aliases.isEmpty())
                return aliases.get(0);
            ICompositeNode node = NodeModelUtils.findActualNodeFor(expression.getExpression());
            if (node == null)
                return null;
            INode last = null;
            for (var leaf : node.getLeafNodes())
                if (!leaf.isHidden() && (leaf.getText().equalsIgnoreCase(field.getName())
                    || leaf.getText().equalsIgnoreCase(field.getNameRu())))
                    last = leaf;
            return last == null ? node : last;
        }

        private Type resultType(Shape shape)
        {
            Type type = baseType(shape.kind);
            if (type == null)
                return null;
            if (shape.kind.equals("Array")) //$NON-NLS-1$
            {
                TypeContainerRef elements = McoreFactory.eINSTANCE.createTypeContainerRef();
                if (shape.results.stream().anyMatch(result -> !result.dropped))
                    elements.getTypes().add(baseType("QueryResult")); //$NON-NLS-1$
                if (shape.results.stream().anyMatch(Result::dropped))
                {
                    Type undefined = baseType("Undefined"); //$NON-NLS-1$
                    if (undefined != null)
                        elements.getTypes().add(undefined);
                }
                type.setCollectionElementTypes(elements);
            }
            else if (shape.kind.equals("ValueTable")) //$NON-NLS-1$
            {
                Type row = baseType("ValueTableRow"); //$NON-NLS-1$
                if (row == null)
                    return null;
                fillFields(row, shape.results.get(0));
                TypeContainerRef elements = McoreFactory.eINSTANCE.createTypeContainerRef();
                elements.getTypes().add(row);
                type.setCollectionElementTypes(elements);
            }
            else if (shape.kind.equals("QueryResultSelection") || shape.kind.equals("ValueTableRow")) //$NON-NLS-1$ //$NON-NLS-2$
                fillFields(type, shape.results.get(0));
            return type;
        }

        private Type baseType(String name)
        {
            var rsp = org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(method.eResource().getURI());
            IRuntimeVersionSupport versions = rsp.get(IRuntimeVersionSupport.class);
            Version version = versions.getRuntimeVersionOrDefault(method, Version.LATEST);
            var provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
            EObject resolved = EcoreUtil.resolve(provider.createProxy(name), method);
            if (!(resolved instanceof Type type) || type.eIsProxy())
                throw new IllegalStateException("Platform type unavailable: " + name); //$NON-NLS-1$
            return BslDocCommentTypeMerge.copyWithRefContext(type, method.environments(), false);
        }

        private void fillFields(Type type, Result result)
        {
            for (Field field : result.fields)
            {
                if (field.name == null || field.name.isBlank())
                    continue;
                DerivedProperty property = McoreFactory.eINSTANCE.createDerivedProperty();
                property.setName(field.name);
                property.setNameRu(field.nameRu == null || field.nameRu.isBlank() ? field.name : field.nameRu);
                property.setReadable(true);
                property.setWritable(type.getName().equals("ValueTableRow")); //$NON-NLS-1$
                property.setEnvironments(Environments.ALL);
                TypeContainerRef container = McoreFactory.eINSTANCE.createTypeContainerRef();
                container.getTypes().addAll(field.types);
                property.setTypeContainer(container);
                if (field.offset >= 0 && field.length > 0)
                {
                    BslDerivedPropertySource source = BslFactory.eINSTANCE.createBslDerivedPropertySource();
                    source.setMethodName(method.getName());
                    source.setModuleUri(EcoreUtil.getURI(method.eResource().getContents().get(0)).trimFragment().toString());
                    source.setLocalOffset(field.offset - NodeModelUtils.findActualNodeFor(method).getTotalOffset());
                    property.setSource(source);
                    sources.put(sourceKey(field.name, source.getLocalOffset()),
                        new BslStructureInsertCommentTypes.PropertySourceTarget(field.literalUri, field.offset, field.length));
                }
                type.getContextDef().getProperties().add(property);
            }
        }
    }

    private static Variable variable(Expression expression)
    {
        if (!(expression instanceof StaticFeatureAccess access) || access.getFeatureEntries().size() != 1)
            return null;
        return access.getFeatureEntries().get(0).getFeature() instanceof Variable variable ? variable : null;
    }

    private static String lower(String name)
    {
        return name == null ? "" : name.toLowerCase(Locale.ROOT); //$NON-NLS-1$
    }

    private static int start(EObject object)
    {
        ICompositeNode node = object == null ? null : NodeModelUtils.findActualNodeFor(object);
        return node == null ? -1 : node.getOffset();
    }

    private static int end(EObject object)
    {
        ICompositeNode node = object == null ? null : NodeModelUtils.findActualNodeFor(object);
        return node == null ? -1 : node.getEndOffset();
    }
}
