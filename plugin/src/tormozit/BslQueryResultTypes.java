package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.util.Triple;
import org.eclipse.xtext.util.Tuples;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.IXtextModelListener;
import org.eclipse.xtext.ui.editor.model.XtextDocument;
import org.eclipse.xtext.util.CancelIndicator;

import com._1c.g5.v8.bm.core.event.BmChangeEvent;
import com._1c.g5.v8.bm.core.event.BmEvent;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.bm.integration.event.BmEventFilter;
import com._1c.g5.v8.bm.integration.event.IBmAsyncEventListener;
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
import com._1c.g5.v8.dt.bsl.typesystem.ValueTableDynamicContextDefProvider;
import com._1c.g5.v8.dt.bsl.typesystem.ValueTreeDynamicContextDefProvider;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.ui.contentassist.stringliteral.AbstractStringLiteralProposalProvider;
import com._1c.g5.v8.dt.bsl.ui.contentassist.stringliteral.IStringLiteralProposalProvider.IBslStringLiteralProposalImageProvider;
import com._1c.g5.modeling.xtext.scoping.IIndexSlicePredicateService;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.mcore.ContextDefWithRefItem;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainerRef;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.ql.DerivedTypeComputerForDbViewQuery;
import com._1c.g5.v8.dt.ql.dcs.resource.IDependentQuerySchemaProvider;
import com._1c.g5.v8.dt.ql.model.ParameterExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchema;
import com._1c.g5.v8.dt.ql.model.QuerySchemaExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTotalControlPoint;
import com._1c.g5.v8.dt.ql.model.QlPackage;
import com._1c.g5.v8.dt.ql.resource.QlMapper;
import com._1c.g5.v8.dt.ql.resource.QlParametersFinder;
import com._1c.g5.v8.dt.validation.git.IGitMarkerFilterManager;

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
        "select", "выбрать", "unload", "выгрузить", "get", "получить", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "getdata", "получитьданные"); //$NON-NLS-1$ //$NON-NLS-2$

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

    private static final Set<String> SET_PARAMETER_METHODS = Set.of("setparameter", "установитьпараметр"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Имена параметров запроса для литерала — первого аргумента {@code Запрос.УстановитьПараметр("…")}:
     * текст запроса той же переменной ({@link Plan#queryLiteral}), параметры — из его схемы.
     * {@code null} — расширенный расчёт типов выключен; пусто — смысл литерала не определён.
     * Вызывающий держит readOnly документа.
     */
    static Map<String, com._1c.g5.v8.dt.mcore.Property> queryParametersAt(XtextResource resource, int offset)
    {
        if (resource.getContents().isEmpty()
            || !BslDocCommentComputedTypes.isExtendedTypesEnabled(resource.getContents().get(0)))
        {
            return null;
        }
        EObject object = new EObjectAtOffsetHelper().resolveContainedElementAt(resource, offset);
        StringLiteral argument = EcoreUtil2.getContainerOfType(object, StringLiteral.class);
        if (argument == null || !(argument.eContainer() instanceof Invocation invocation)
            || invocation.getParams().indexOf(argument) != 0
            || !(invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
            || !SET_PARAMETER_METHODS.contains(lower(access.getName())))
            return Map.of();
        Method method = EcoreUtil2.getContainerOfType(invocation, Method.class);
        if (method == null)
            return Map.of();
        Plan plan = new Plan(method, ""); //$NON-NLS-1$
        StringLiteral query = plan.queryLiteral(access.getSource(), invocation);
        Map<String, com._1c.g5.v8.dt.mcore.Property> names = query == null ? Map.of() : plan.parameters(query);
        return names;
    }

    /**
     * Колонки итогов ({@code ИТОГИ ПО}) запроса, результат которого — {@code expression}: замена
     * {@code ПостроительЗапроса.Измерения} ИР. Запрос — тот, чей результат возвращает {@code Выполнить()}
     * (последний в пакете без {@code ПОМЕСТИТЬ}). {@code null} — текст запроса не определён.
     * Вызывающий держит readOnly документа.
     */
    static List<String> totalColumnsOf(Expression expression)
    {
        Method method = expression == null ? null : EcoreUtil2.getContainerOfType(expression, Method.class);
        if (method == null)
            return null;
        try
        {
            Plan plan = new Plan(method, ""); //$NON-NLS-1$
            Shape shape = plan.shape(expression, Collections.newSetFromMap(new IdentityHashMap<>()));
            List<String> totals = shape != null && shape.kind.equals("QueryResult") //$NON-NLS-1$
                ? shape.results.get(0).totals : null;
            Global.tempLog("expected-type", "query totals=" + totals //$NON-NLS-1$ //$NON-NLS-2$
                + " shape=" + (shape == null ? null : shape.kind)); //$NON-NLS-1$
            return totals;
        }
        catch (RuntimeException | LinkageError failure)
        {
            Global.tempLog("expected-type", "query totals failed: " + failure); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * Колонки результата запроса {@code expression} ({@code РезультатЗапроса.Колонки} ИР) с типами: имя (русское,
     * если есть) → типы. {@code null} — текст запроса не определён. Вызывающий держит readOnly документа.
     */
    static Map<String, List<TypeItem>> resultColumnsOf(Expression expression)
    {
        Method method = expression == null ? null : EcoreUtil2.getContainerOfType(expression, Method.class);
        if (method == null)
            return null;
        try
        {
            Plan plan = new Plan(method, ""); //$NON-NLS-1$
            Shape shape = plan.shape(expression, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (shape == null || !shape.kind.equals("QueryResult") || shape.results.isEmpty()) //$NON-NLS-1$
                return null;
            Map<String, List<TypeItem>> columns = new java.util.LinkedHashMap<>();
            for (Field field : shape.results.get(0).fields)
            {
                String name = field.nameRu != null && !field.nameRu.isBlank() ? field.nameRu : field.name;
                if (name != null && !name.isBlank())
                    columns.putIfAbsent(name, field.types != null ? field.types : List.of());
            }
            return columns;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return null;
        }
    }

    /** Имя колонки точки итогов: {@code ИТОГИ ПО Поле КАК Колонка} — колонка, иначе псевдоним или поле. */
    private static String totalColumnName(QuerySchemaTotalControlPoint point)
    {
        String name = point.getColumnName();
        QuerySchemaExpression expression = point.getExpression();
        if ((name == null || name.isBlank()) && expression != null)
        {
            name = expression.getAlias();
            if (name == null || name.isBlank())
            {
                ICompositeNode node = expression.getExpression() != null
                    ? NodeModelUtils.findActualNodeFor(expression.getExpression()) : null;
                String text = node != null ? NodeModelUtils.getTokenText(node).trim() : ""; //$NON-NLS-1$
                name = text.substring(text.lastIndexOf('.') + 1);
            }
        }
        return name == null ? "" : name.trim(); //$NON-NLS-1$
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
                try
                {
                    Plan plan = session.plans.get(method);
                    String text = node.getText();
                    if (!current(plan, text))
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
                        if (!plan.queries.isEmpty())
                            session.hasQueries = true;
                        session.plans.put(method, plan);
                    }
                    problems.addAll(plan.unknownFields(cancellation));
                }
                catch (QuerySchemaUnavailableException failure)
                {
                    // Не кэшируем недоступность: следующий расчёт повторит получение схемы.
                    session.plans.remove(method);
                }
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
        if (previous != null && previous.complete && current(previous, text))
        {
            return;
        }
        try
        {
            Plan plan = current(previous, text) ? previous : new Plan(method, text);
            // Валидация могла уже подготовить план, не переустанавливая типы BSL.
            if (plan != previous)
                plan.prepare();
            if (!plan.queries.isEmpty())
                session.hasQueries = true;
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
            Global.tempLog("query-value-table", "prepare failed: " + failure); //$NON-NLS-1$ //$NON-NLS-2$
            session.plans.remove(method);
        }
    }

    // ---- Досчёт плана после разбора правки открытого модуля ---------------------------------------

    /** Пауза после разбора правки до досчёта: серия правок склеивается в один досчёт. */
    private static final int EDIT_REFRESH_DELAY_MS = 500;
    private static final Map<IXtextDocument, EditRefresh> WATCHED_DOCUMENTS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * План запросов метода под кареткой досчитывается в фоне после каждого разбора правки открытого модуля:
     * типы результатов запросов (поля выборки и выгрузки) оказываются в модели к любому чтению — списку из
     * UI-потока, премиям, подсказкам, переходам, — а не только у тех, кто досчитывает сам перед чтением.
     * Досчёт перед списком и наведением остаётся быстрым путём: при актуальном плане он возвращается сразу.
     * UI-поток; слушатель ставится один раз на документ.
     */
    static void watchEditor(BslXtextEditor editor)
    {
        IXtextDocument document = editor == null ? null : editor.getDocument();
        ISourceViewer viewer = editor == null ? null : editor.getInternalSourceViewer();
        StyledText widget = viewer == null ? null : viewer.getTextWidget();
        if (document == null || widget == null || widget.isDisposed() || WATCHED_DOCUMENTS.containsKey(document))
            return;
        EditRefresh refresh = new EditRefresh(editor, document);
        WATCHED_DOCUMENTS.put(document, refresh);
        document.addModelListener(refresh);
        widget.addDisposeListener(event ->
        {
            document.removeModelListener(refresh);
            WATCHED_DOCUMENTS.remove(document);
        });
        // Модуль только открыт: разбор уже прошёл, своего события до первой правки не будет
        refresh.modelChanged(null);
    }

    /** Слушатель разбора одного открытого модуля. */
    private static final class EditRefresh implements IXtextModelListener
    {
        private final java.lang.ref.WeakReference<BslXtextEditor> editor;
        private final IXtextDocument document;
        private final Runnable fire = this::fire;

        EditRefresh(BslXtextEditor editor, IXtextDocument document)
        {
            this.editor = new java.lang.ref.WeakReference<>(editor);
            this.document = document;
        }

        /** Xtext зовёт после разбора; здесь только перевзвод таймера на UI-потоке. */
        @Override
        public void modelChanged(XtextResource resource)
        {
            Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
            if (display == null || display.isDisposed())
                return;
            if (Display.getCurrent() == display)
                restart(display, EDIT_REFRESH_DELAY_MS);
            else
                display.asyncExec(() -> restart(display, EDIT_REFRESH_DELAY_MS));
        }

        private void restart(Display display, int delayMs)
        {
            display.timerExec(-1, fire);
            display.timerExec(delayMs, fire);
        }

        /** UI: при открытом списке досчёт делает сам список — откладываем; каретка — модельное смещение. */
        private void fire()
        {
            BslXtextEditor current = editor.get();
            ISourceViewer viewer = current == null ? null : current.getInternalSourceViewer();
            StyledText widget = viewer == null ? null : viewer.getTextWidget();
            if (widget == null || widget.isDisposed())
                return;
            if (assistOpen(current))
            {
                restart(widget.getDisplay(), RELINK_ASSIST_RETRY_MS);
                return;
            }
            int offset = viewer.getSelectedRange().x;
            Job job = new Job("Комфорт: типы результатов запросов") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        document.readOnly(resource ->
                        {
                            BslStructureInsertCommentTypes.refreshQueryTypesForAssist(resource, offset,
                                status -> {});
                            // Сеансы менеджера временных таблиц для литералов модуля (issue 889, решение 1А)
                            BslTempTableSessions.refreshDocument(document, resource, offset);
                            return null;
                        });
                    }
                    catch (RuntimeException | LinkageError failure)
                    {
                        // Досчёт не удался — следующая попытка обновления повторит расчёт.
                    }
                    return Status.OK_STATUS;
                }
            };
            job.setSystem(true);
            job.schedule();
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
        if (!resource.getContents().isEmpty() && resource.getContents().get(0) instanceof Module module)
            watchMetadata(module);
        return session;
    }

    /**
     * Типы полей запроса зависят от представления данных (dbview), а не только от текста метода:
     * смена типа реквизита/измерения обязана сбросить готовые планы, иначе в выборке остаётся старый тип.
     */
    private static final AtomicLong METADATA_GENERATION = new AtomicLong();
    private static final Map<IBmModel, Boolean> WATCHED_MODELS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final IBmAsyncEventListener METADATA_LISTENER = BslQueryResultTypes::onBmEvent;

    private static void watchMetadata(Module module)
    {
        IBmModelManager models = Global.getOsgiService(IBmModelManager.class);
        IProject project = BslStructureInsertCommentTypes.projectOf(module);
        IBmModel model = models != null && project != null ? models.getModel(project) : null;
        boolean added = model != null && WATCHED_MODELS.putIfAbsent(model, Boolean.TRUE) == null;
        if (added)
            model.addAsyncEventListener(METADATA_LISTENER, BmEventFilter.changeFilter());
    }

    private static void onBmEvent(BmEvent event)
    {
        if (event.getChangeEvents() == null)
            return;
        // Поля запросов задаёт только представление данных (dbview): его строят таблицы,
        // доступные в запросах; обработки, отчёты, команды его не меняют.
        for (BmChangeEvent change : event.getChangeEvents().values())
            if (change.getObject() != null
                && change.getObject().eClass().getEPackage() == com._1c.g5.v8.dt.metadata.dbview.DbViewPackage.eINSTANCE)
            {
                METADATA_GENERATION.incrementAndGet();
                scheduleRelink(RELINK_DELAY_MS);
                return;
            }
    }

    /**
     * Привязки вызовов модуля после перестройки dbview ведут на удалённые объекты, и частичный
     * пересчёт типов падает. Открытые модули с запросами перевязываем, как после правки текста.
     */
    private static final int RELINK_DELAY_MS = 500;
    private static final int RELINK_ASSIST_RETRY_MS = 1000;
    private static final AtomicBoolean RELINK_PENDING = new AtomicBoolean();

    /**
     * Включено скрытие проблем базовой версии, и в модуле нет отличий от неё. Сервис отвечает
     * по строкам; у файла без отличий скрыта любая строка ({@code GitMarkerFilterManager.shouldSkipIssue}).
     */
    private static boolean unchangedFromBaseline(IXtextDocument document)
    {
        IGitMarkerFilterManager baseline = Global.getOsgiService(IGitMarkerFilterManager.class);
        if (baseline == null || !baseline.isFilterApplied() || !(document instanceof XtextDocument xtext))
            return false;
        URI uri = xtext.getResourceURI();
        if (uri == null)
            return false;
        // Строка Xtext начинается с единицы, как в BslNotifyingResourceValidator.
        for (int line = 1; line <= document.getNumberOfLines(); line++)
            if (!baseline.shouldSkipIssue(uri, line))
                return false;
        return true;
    }

    private static void scheduleRelink(int delayMs)
    {
        if (!PlatformUI.isWorkbenchRunning() || !RELINK_PENDING.compareAndSet(false, true))
            return;
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display.isDisposed())
        {
            RELINK_PENDING.set(false);
            return;
        }
        display.asyncExec(() -> display.timerExec(delayMs, BslQueryResultTypes::relinkOpenModules));
    }

    /**
     * Структуру данных могут править каждые пару секунд: активный модуль перевязываем сразу,
     * остальные открытые — через эту паузу после последнего изменения (или при их активации).
     */
    private static final int RELINK_INACTIVE_DELAY_MS = 5000;
    /** Открытые неактивные модули, ждущие перевязки по таймеру. */
    private static final Map<IXtextDocument, Boolean> INACTIVE_RELINK =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Runnable RELINK_INACTIVE = BslQueryResultTypes::relinkInactiveModules;

    /** UI: активный модуль — сразу, прочие открытые — по таймеру; при открытом списке автодополнения — позже. */
    private static void relinkOpenModules()
    {
        RELINK_PENDING.set(false);
        BslXtextEditor active = activeModuleEditor();
        if (assistOpen(active))
        {
            scheduleRelink(RELINK_ASSIST_RETRY_MS);
            return;
        }
        IXtextDocument activeDocument = active == null ? null : active.getDocument();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            for (IWorkbenchPage page : window.getPages())
                for (IEditorReference reference : page.getEditorReferences())
                {
                    BslXtextEditor editor = GetRef.getActiveBslEditor(reference.getEditor(false));
                    IXtextDocument document = editor == null ? null : editor.getDocument();
                    if (document != null && document != activeDocument)
                        INACTIVE_RELINK.put(document, Boolean.TRUE);
                }
        ensureActivationListener();
        if (!INACTIVE_RELINK.isEmpty())
        {
            // Каждое новое изменение отодвигает перевязку неактивных модулей.
            Display display = PlatformUI.getWorkbench().getDisplay();
            display.timerExec(-1, RELINK_INACTIVE);
            display.timerExec(RELINK_INACTIVE_DELAY_MS, RELINK_INACTIVE);
        }
        if (activeDocument != null)
        {
            INACTIVE_RELINK.remove(activeDocument);
            scheduleRelinkJob(List.of(activeDocument), false);
        }
    }

    /** UI: таймер неактивных модулей. */
    private static void relinkInactiveModules()
    {
        BslXtextEditor active = activeModuleEditor();
        List<IXtextDocument> documents;
        synchronized (INACTIVE_RELINK)
        {
            documents = new ArrayList<>(INACTIVE_RELINK.keySet());
        }
        if (active != null && documents.contains(active.getDocument()) && assistOpen(active))
        {
            PlatformUI.getWorkbench().getDisplay().timerExec(RELINK_ASSIST_RETRY_MS, RELINK_INACTIVE);
            return;
        }
        documents.forEach(INACTIVE_RELINK::remove);
        scheduleRelinkJob(documents, false);
    }

    /** UI: модуль в активном редакторе активной страницы (фокус может быть в панели). */
    private static BslXtextEditor activeModuleEditor()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        return page == null ? null : GetRef.getActiveBslEditor(page.getActiveEditor());
    }

    private static boolean assistOpen(BslXtextEditor editor)
    {
        boolean open = editor != null && editor.getInternalSourceViewer() instanceof SourceViewer viewer
            && ContentAssistPopupSync.isPopupVisible(ContentAssistPatcher.getContentAssistant(viewer));
        return open;
    }

    private static void scheduleRelinkJob(List<IXtextDocument> documents, boolean onActivation)
    {
        if (documents.isEmpty())
            return;
        Job job = new Job("Перевязка модулей после изменения структуры данных") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                for (IXtextDocument document : documents)
                {
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    relink(document, onActivation);
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** Модули без отличий от базовой версии: перевязка отложена до активации их редактора. */
    private static final Map<IXtextDocument, Boolean> DEFERRED_RELINK =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicBoolean ACTIVATION_LISTENER_INSTALLED = new AtomicBoolean();

    private static void deferUntilActivation(IXtextDocument document)
    {
        DEFERRED_RELINK.put(document, Boolean.TRUE);
        ensureActivationListener();
    }

    private static void ensureActivationListener()
    {
        if (!ACTIVATION_LISTENER_INSTALLED.compareAndSet(false, true))
            return;
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() ->
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                window.getPartService().addPartListener(ACTIVATION_LISTENER);
            PlatformUI.getWorkbench().addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow window)
                {
                    window.getPartService().addPartListener(ACTIVATION_LISTENER);
                }

                @Override
                public void windowActivated(IWorkbenchWindow window) {}

                @Override
                public void windowDeactivated(IWorkbenchWindow window) {}

                @Override
                public void windowClosed(IWorkbenchWindow window) {}
            });
        });
    }

    private static final IPartListener2 ACTIVATION_LISTENER = new IPartListener2()
    {
        @Override
        public void partActivated(IWorkbenchPartReference reference)
        {
            BslXtextEditor editor = GetRef.getActiveBslEditor(reference.getPart(false));
            IXtextDocument document = editor == null ? null : editor.getDocument();
            if (document == null)
                return;
            // Не ждём таймера неактивных: модуль стал активным. Перевязан ли он уже для
            // текущей версии структуры данных, relink проверит сам по сеансу ресурса.
            DEFERRED_RELINK.remove(document);
            INACTIVE_RELINK.remove(document);
            scheduleRelinkJob(List.of(document), true);
        }
    };

    private static void relink(IXtextDocument document, boolean onActivation)
    {
        if (!onActivation && unchangedFromBaseline(document))
        {
            deferUntilActivation(document);
            return;
        }
        long generation = METADATA_GENERATION.get();
        // Не modify: тот сериализует перевязанную модель в текст документа.
        Boolean relinked = document.readOnly(resource ->
        {
            Session session = session(resource, false);
            // В модуле без запросов перевязка не нужна.
            if (session == null || !session.hasQueries || session.relinkedGeneration == generation)
                return false;
            session.relinkedGeneration = generation;
            try
            {
                if (!BslStructureInsertCommentTypes.relinkModuleFully(resource))
                    return false;
                // Планы построены по прежним привязкам: следующий запрос пересчитает их.
                session.plans.clear();
                return true;
            }
            catch (RuntimeException | LinkageError failure)
            {
                return false;
            }
        });
        // Перевязка не меняет текст, и проверку редактора никто не планирует: маркеры модуля
        // и отметка на вкладке остались бы по прежней структуре данных. Вне readOnly.
        if (Boolean.TRUE.equals(relinked) && document instanceof XtextDocument xtext)
        {
            xtext.checkAndUpdateAnnotations();
        }
    }

    /** План построен по тому же тексту метода и тем же метаданным. */
    private static boolean current(Plan plan, String text)
    {
        return plan != null && text.equals(plan.text) && plan.metadataGeneration == METADATA_GENERATION.get();
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

    /**
     * Штатная точка расширения строковых подсказок. Состав колонок берётся из типа
     * таблицы; место параметра определяется по штатной сигнатуре, без списка методов.
     * Сортировать обслуживает собственный провайдер EDT с направлениями сортировки.
     */
    public static final class ColumnProposals extends AbstractStringLiteralProposalProvider
    {
        @Override
        public boolean isAppropriate(Triple<EObject, List<Expression>, Integer> context)
        {
            return !columnTables(context).isEmpty();
        }

        @Override
        public List<Triple<String, String, IBslStringLiteralProposalImageProvider>> computeProposals(
            Triple<EObject, List<Expression>, Integer> context, String prefix,
            IScopeProvider scope, IIndexSlicePredicateService slices, boolean russian)
        {
            var proposals = new ArrayList<Triple<String, String, IBslStringLiteralProposalImageProvider>>();
            int tokenStart = prefix.lastIndexOf(',') + 1;
            while (tokenStart < prefix.length() && Character.isWhitespace(prefix.charAt(tokenStart)))
                tokenStart++;
            String typed = lower(prefix.substring(tokenStart));
            String before = "\"" + prefix.substring(0, tokenStart); //$NON-NLS-1$
            Set<String> names = new java.util.LinkedHashSet<>();
            for (Type table : columnTables(context))
                for (TypeItem item : rowTypes(table))
                    if (item instanceof Type row && row.getContextDef() != null)
                        for (var property : row.getContextDef().allProperties())
                        {
                            if (property.isDependsOnMetadata())
                                continue;
                            String name = russian ? property.getNameRu() : property.getName();
                            if (name == null || name.isBlank())
                                name = property.getName();
                            if (name != null && lower(name).startsWith(typed) && names.add(lower(name)))
                                proposals.add(Tuples.create(before + name, name,
                                    IBslStringLiteralProposalImageProvider.NULL_IMAGE_PROVIDER));
                        }
            Global.tempLog("query-value-table", "columnProposals parameter=" + context.getThird() //$NON-NLS-1$ //$NON-NLS-2$
                + " prefix=" + prefix + " names=" + names); //$NON-NLS-1$ //$NON-NLS-2$
            return proposals;
        }

        private static List<Type> columnTables(Triple<EObject, List<Expression>, Integer> context)
        {
            EObject target = context.getFirst();
            int argument = context.getThird();
            boolean filterKeys = target instanceof OperatorStyleCreator;
            if (filterKeys)
            {
                OperatorStyleCreator creator = (OperatorStyleCreator) target;
                // Только список ключей непосредственно переданной структуры отбора.
                // Строковые значения (второй и следующие аргументы) сюда не попадают.
                if (argument != 0 || !"Structure".equals(McoreUtil.getTypeName(creator.getType())) //$NON-NLS-1$
                    || !(creator.eContainer() instanceof Invocation outer))
                    return List.of();
                argument = outer.getParams().indexOf(creator);
                target = outer;
            }
            if (!(target instanceof Invocation invocation)
                || !(invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
                || access.getSource() == null || argument < 0
                || !BslDocCommentComputedTypes.isExtendedTypesEnabled(invocation))
                return List.of();
            String name = lower(access.getName());
            if (name.equals("sort") || name.equals("сортировать")) //$NON-NLS-1$ //$NON-NLS-2$
                return List.of();
            var rsp = org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(invocation.eResource().getURI());
            Method method = EcoreUtil2.getContainerOfType(invocation, Method.class);
            if (method == null)
                return List.of();
            int parameterIndex = argument;
            List<Type> tables = new ArrayList<>();
            for (TypeItem item : rsp.get(TypesComputer.class)
                .computeTypes(access.getSource(), method.environments()))
            {
                EObject resolved = EcoreUtil.resolve(item, invocation);
                if (!(resolved instanceof Type table) || table.eIsProxy()
                    || !Set.of("ValueTable", "ValueTree", "ValueTreeRowCollection") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        .contains(McoreUtil.getTypeName(table))
                    || table.getContextDef() == null || rowTypes(table).isEmpty())
                    continue;
                boolean columnParameter = table.getContextDef().allMethods().stream()
                    .filter(candidate -> name.equals(lower(candidate.getName()))
                        || name.equals(lower(candidate.getNameRu())))
                    .flatMap(candidate -> candidate.getParamSet().stream())
                    .filter(parameters -> parameterIndex < parameters.getParams().size())
                    .map(parameters -> parameters.getParams().get(parameterIndex))
                    .anyMatch(parameter -> filterKeys
                        ? "FilterParameters".equalsIgnoreCase(parameter.getName()) //$NON-NLS-1$
                        : "Columns".equalsIgnoreCase(parameter.getName()) //$NON-NLS-1$
                            || "Column".equalsIgnoreCase(parameter.getName())); //$NON-NLS-1$
                if (columnParameter)
                    tables.add(table);
            }
            return tables;
        }

        private static List<TypeItem> rowTypes(Type table)
        {
            var rows = "ValueTree".equals(McoreUtil.getTypeName(table)) //$NON-NLS-1$
                ? ValueTreeDynamicContextDefProvider.getValueTreeCollectionElementType(table)
                : table.getCollectionElementTypes();
            return rows == null ? List.of() : rows.allTypes();
        }

        @Override
        public Collection<QualifiedName> getExportedName(
            Triple<EObject, List<Expression>, Integer> context, IScopeProvider scope)
        {
            return List.of();
        }

        @Override
        public List<IReferenceDescription> getReferenceDescriptions(
            Triple<EObject, List<Expression>, Integer> context, IScopeProvider scope)
        {
            return List.of();
        }

        @Override
        public boolean isCorrectValue(String value, Triple<EObject, List<Expression>, Integer> context,
            IScopeProvider scope, IIndexSlicePredicateService slices)
        {
            // Это дополнение списка, а не проверка значения строкового параметра.
            return true;
        }

        @Override
        public boolean hasHyperlinks(Triple<EObject, List<Expression>, Integer> context)
        {
            return false;
        }
    }

    private static final class Session extends AdapterImpl
    {
        final Map<Method, Plan> plans = new java.util.concurrent.ConcurrentHashMap<>();
        /** В модуле разбирался текст запроса: его типы зависят от структуры данных. */
        volatile boolean hasQueries;
        /** Версия структуры данных, для которой модуль уже перевязан. */
        volatile long relinkedGeneration;
    }

    /** Версия метаданных, на которой получена схема запроса литерала. */
    private static final class SchemaGeneration extends AdapterImpl
    {
        final long generation;

        SchemaGeneration(long generation)
        {
            this.generation = generation;
        }

        @Override
        public boolean isAdapterForType(Object type)
        {
            return type == SchemaGeneration.class;
        }
    }

    private record Field(String name, String nameRu, List<TypeItem> types,
        URI literalUri, int offset, int length) {}
    /** @param totals колонки итогов {@code ИТОГИ ПО} в порядке уровней */
    private record Result(List<Field> fields, boolean ordinary, boolean dropped, List<String> totals) {}
    private record Shape(String kind, List<Result> results) {}

    private static final class QuerySchemaUnavailableException extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        QuerySchemaUnavailableException()
        {
            super("Query schema unavailable"); //$NON-NLS-1$
        }
    }

    private static final class Plan
    {
        final Method method;
        final String text;
        final long metadataGeneration = METADATA_GENERATION.get();
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
                // Строки выводит штатная система типов из общего типа таблицы.
                if (shape == null || shape.kind.equals("ValueTableRow")) //$NON-NLS-1$
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
                // Временная таблица менеджера: М.Таблицы["Вт"], М.Таблицы.Найти("Вт") (issue 889)
                if (BslTempTableSessions.isTempTableExpression(expression))
                    return tempTable(expression);
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
                        ? List.of(new Result(List.of(), true, false, List.of())) : query(literal);
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
                if ((name.equals("getdata") || name.equals("получитьданные")) && invocation.getParams().isEmpty() //$NON-NLS-1$ //$NON-NLS-2$
                    && source.kind.equals("QueryTempTable")) //$NON-NLS-1$
                    return new Shape("QueryResult", source.results); //$NON-NLS-1$
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

        /** Временная таблица менеджера ({@code ВременнаяТаблицаЗапроса} ИР) — поля {@code ПолучитьДанные()}. */
        private Shape tempTable(Expression expression)
        {
            if (originsOnly)
                return new Shape("QueryTempTable", List.of(new Result(List.of(), true, false, List.of()))); //$NON-NLS-1$
            BslTempTableSessions.Table table = BslTempTableSessions.tempTable(expression, cancellation);
            if (table == null)
                return null;
            List<Field> fields = new ArrayList<>();
            for (BslTempTableSessions.Column column : table.columns())
            {
                var target = column.target();
                fields.add(new Field(column.name(), column.name(),
                    column.type() == null ? List.of() : List.copyOf(column.type().getTypes()),
                    target == null ? null : target.uri(), target == null ? -1 : target.offset(),
                    target == null ? 0 : target.length()));
            }
            return new Shape("QueryTempTable", List.of(new Result(List.copyOf(fields), true, false, List.of()))); //$NON-NLS-1$
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
            OperatorStyleCreator creator = constructor instanceof OperatorStyleCreator created
                && "Query".equals(McoreUtil.getTypeName(created.getType())) ? created : null; //$NON-NLS-1$
            // Запрос из функции («Запрос = Главный2()», issue 889): текст — только присваиванием после неё
            if (creator == null && (variable == null || creation == null || !BslTempTableSessions.returnsQuery(constructor)))
                return null;
            if (variable != null)
            {
                SimpleStatement textAssignment = lastAssignment(variable, execute, true, end(creation));
                if (textAssignment != null)
                    return dominates(textAssignment, execute)
                        ? queryTextLiteral(textAssignment.getRight()) : null;
            }
            return creator != null && creator.getParams().size() == 1 ? queryTextLiteral(creator.getParams().get(0)) : null;
        }

        /** Исходный полный текст переменной; последующие преобразования строки не вычисляем. */
        private StringLiteral queryTextLiteral(Expression expression)
        {
            if (expression instanceof StringLiteral literal)
                return literal;
            Variable target = variable(expression);
            if (target == null)
                return null;
            SimpleStatement last = null;
            for (SimpleStatement assignment : assignments)
            {
                if (!(assignment.getRight() instanceof StringLiteral)
                    || variable(assignment.getLeft()) != target || end(assignment) > start(expression))
                    continue;
                if (last == null || start(assignment) > start(last))
                    last = assignment;
            }
            return last != null && dominates(last, expression) ? (StringLiteral)last.getRight() : null;
        }

        /**
         * Только однозначное присваивание до использования. Запись в соседней ветви
         * не выбирается по текстовому смещению: если она могла изменить значение,
         * результат не определяем. Конкатенацию и вызовы не вычисляем.
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

        /**
         * Присваивание запроса другой переменной допускает изменение его текста по другой ссылке.
         * Передачу запроса или его текста в параметры вызовов не проверяем.
         */
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
            DefaultStringLiteralTextProvider textProvider = new DefaultStringLiteralTextProvider(literal);
            QuerySchema schema = literalSchema(literal, cancellation);
            if (schema == null || schema.eResource() == null)
            {
                throw new QuerySchemaUnavailableException();
            }
            // Временные таблицы других пакетов менеджера и таблицы-параметры (issue 889)
            BslTempTableSessions.enrich(schema.eResource(), BslTempTableSessions.compute(literal, cancellation));
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
                List<String> totals = new ArrayList<>();
                if (query instanceof QuerySchemaSelectQuery select && select.getPlacementTable() == null)
                {
                    for (QuerySchemaTotalControlPoint point : select.getTotalControlPoints())
                    {
                        String name = totalColumnName(point);
                        if (!name.isEmpty())
                            totals.add(name);
                    }
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
                    query instanceof com._1c.g5.v8.dt.ql.model.QuerySchemaDropTableQuery, List.copyOf(totals)));
            }
            cached = List.copyOf(results);
            queries.put(literal, cached);
            return cached;
        }

        /** Параметры схемы запроса в порядке первого упоминания и их ожидаемые типы EDT. */
        private Map<String, com._1c.g5.v8.dt.mcore.Property> parameters(StringLiteral literal)
        {
            IDependentQuerySchemaProvider provider = Global.getOsgiService(IDependentQuerySchemaProvider.class);
            if (provider == null)
                return Map.of();
            DefaultStringLiteralTextProvider textProvider = new DefaultStringLiteralTextProvider(literal);
            QuerySchema schema = provider.getQuerySchema(literal,
                com._1c.g5.v8.dt.bsl.model.BslPackage.Literals.STRING_LITERAL__LINES,
                (object, feature) -> textProvider.getText(), cancellation);
            if (schema == null)
                return Map.of();
            Map<String, String> names = new java.util.LinkedHashMap<>();
            for (ParameterExpression parameter : EcoreUtil2.getAllContentsOfType(schema, ParameterExpression.class))
            {
                String name = parameter.getName();
                if (name != null && name.startsWith("&")) //$NON-NLS-1$
                    name = name.substring(1);
                if (name != null && !name.isBlank())
                    names.putIfAbsent(lower(name), name);
            }
            Map<String, java.util.Set<TypeItem>> types = new java.util.HashMap<>();
            try
            {
                if (schema.eResource() instanceof XtextResource queryResource)
                {
                    QlParametersFinder finder = queryResource.getResourceServiceProvider().get(QlParametersFinder.class);
                    var parameterTypes = finder.find(schema).getParametersAsExpressionWithTypes();
                    for (var entry : parameterTypes.entrySet())
                    {
                        java.util.Set<TypeItem> labels = types.computeIfAbsent(lower(entry.getKey().getString()),
                            key -> new java.util.LinkedHashSet<>());
                        for (var occurrence : entry.getValue())
                        {
                            var expected = occurrence.getSecond();
                            if (expected == null)
                                continue;
                            for (TypeItem item : expected.getTypes())
                            {
                                if (item != null)
                                    labels.add(item);
                            }
                        }
                    }
                }
            }
            catch (RuntimeException | LinkageError failure)
            {
                // Ошибка расчёта типов не должна убирать найденные имена параметров.
            }
            Map<String, com._1c.g5.v8.dt.mcore.Property> result = new java.util.LinkedHashMap<>();
            for (var entry : names.entrySet())
                result.put(entry.getValue(), BslLiteralValues.valueDescription(entry.getValue(),
                    types.getOrDefault(entry.getKey(), Set.of())));
            return result;
        }

        /** Псевдоним SELECT; без псевдонима — имя поля, иначе само выражение (в том числе *). */
        private static INode fieldNode(DbViewFieldDef field, QuerySchemaSelectQuery select, QlMapper mapper)
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
            if (shape.kind.equals("ValueTable")) //$NON-NLS-1$
                return valueTableType(shape.results.get(0));
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
            else if (shape.kind.equals("QueryResultSelection")) //$NON-NLS-1$
            {
                fillFields(type, shape.results.get(0));
                // Поля посчитаны — платформенное «ИмяПоля» (доступ к любому полю по имени) лишнее
                if (!shape.results.get(0).fields.isEmpty() && type.getContextDef() instanceof ContextDefWithRefItem ref)
                    ref.getRefProperties().removeIf(property -> "FieldName".equals(property.getName())); //$NON-NLS-1$
            }
            return type;
        }

        /**
         * Тот же тип таблицы, который EDT создаёт для «Новый ТаблицаЗначений»:
         * общий тип строки для элементов коллекции и методов, собственный контекст колонок.
         * Параметры и их описания остаются штатными; подсказка Сортировать читает
         * свойства этого же типа строки через getCollectionElementTypes().
         */
        private Type valueTableType(Result result)
        {
            var rsp = org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(method.eResource().getURI());
            // Провайдер изменяет собственные методы и свойства. Ссылочный контекст
            // copyWithRefContext здесь нельзя использовать: он ссылается на платформу.
            Type table = rsp.get(ValueTableDynamicContextDefProvider.class)
                .computeDerivedValueTableType(method,
                    EcoreUtil2.cloneWithProxies(platformType("ValueTable"))); //$NON-NLS-1$
            Type row = (Type) table.getCollectionElementTypes().allTypes().get(0);
            fillFields(row, result);
            // Как expandValueTableAndTreeType в EDT: те же имена доступны через Колонки,
            // но тип значения там — колонка таблицы, а не значение ячейки.
            Type columns = (Type) ValueTableDynamicContextDefProvider.getColumnCollectionType(table)
                .allTypes().get(0);
            Type columnType = platformType("ValueTableColumn"); //$NON-NLS-1$
            for (var property : row.getContextDef().getProperties())
                if (property instanceof DerivedProperty derived
                    && derived.getSource() instanceof BslDerivedPropertySource)
                {
                    DerivedProperty column = EcoreUtil2.cloneWithProxies(derived);
                    column.setWritable(false);
                    TypeContainerRef types = McoreFactory.eINSTANCE.createTypeContainerRef();
                    types.getTypes().add(columnType);
                    column.setTypeContainer(types);
                    columns.getContextDef().getProperties().add(column);
                }
            Global.tempLog("query-value-table", "method=" + method.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + " fields=" + result.fields.stream().map(Field::name).toList() //$NON-NLS-1$
                + " rowMethods=" + table.getContextDef().allMethods().stream() //$NON-NLS-1$
                    .filter(candidate -> candidate.getRetValType().contains(row))
                    .map(com._1c.g5.v8.dt.mcore.Method::getName).toList()
                + " tableMethods=" + table.getContextDef().allMethods().stream() //$NON-NLS-1$
                    .filter(candidate -> candidate.getRetValType().contains(table))
                    .map(com._1c.g5.v8.dt.mcore.Method::getName).toList());
            return table;
        }

        private Type baseType(String name)
        {
            return BslDocCommentTypeMerge.copyWithRefContext(platformType(name), method.environments(), false);
        }

        private Type platformType(String name)
        {
            var rsp = org.eclipse.xtext.resource.IResourceServiceProvider.Registry.INSTANCE
                .getResourceServiceProvider(method.eResource().getURI());
            IRuntimeVersionSupport versions = rsp.get(IRuntimeVersionSupport.class);
            Version version = versions.getRuntimeVersionOrDefault(method, Version.LATEST);
            var provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.TYPE_ITEM, version);
            EObject resolved = EcoreUtil.resolve(provider.createProxy(name), method);
            if (!(resolved instanceof Type type) || type.eIsProxy())
                throw new IllegalStateException("Platform type unavailable: " + name); //$NON-NLS-1$
            return type;
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

    /**
     * Схема запроса литерала штатным сервисом хаба (одна на литерал). EDT держит схему на литерале, пока не
     * изменится сам литерал: после правки метаданных её ссылки ведут на прежние поля — тогда схема перечитывается.
     */
    static QuerySchema literalSchema(StringLiteral literal, CancelIndicator cancellation)
    {
        IDependentQuerySchemaProvider provider = Global.getOsgiService(IDependentQuerySchemaProvider.class);
        if (provider == null)
            throw new IllegalStateException("IDependentQuerySchemaProvider unavailable"); //$NON-NLS-1$
        DefaultStringLiteralTextProvider textProvider = new DefaultStringLiteralTextProvider(literal);
        QuerySchema schema = provider.getQuerySchema(literal,
            com._1c.g5.v8.dt.bsl.model.BslPackage.Literals.STRING_LITERAL__LINES,
            (object, feature) -> textProvider.getText(), cancellation);
        long generation = METADATA_GENERATION.get();
        SchemaGeneration stamp = schema == null ? null
            : (SchemaGeneration)EcoreUtil.getExistingAdapter(schema, SchemaGeneration.class);
        if (stamp != null && stamp.generation != generation)
        {
            provider.unload(schema);
            schema = provider.getQuerySchema(literal,
                com._1c.g5.v8.dt.bsl.model.BslPackage.Literals.STRING_LITERAL__LINES,
                (object, feature) -> textProvider.getText(), cancellation);
            stamp = null;
        }
        if (schema != null && stamp == null)
            schema.eAdapters().add(new SchemaGeneration(generation));
        return schema;
    }

    /** Версия структуры данных: типы полей запросов действительны в пределах одной версии. */
    static long metadataGeneration()
    {
        return METADATA_GENERATION.get();
    }

    /** Место поля запроса литерала в модуле: псевдоним, иначе имя поля или выражение; {@code null} — места нет. */
    static BslStructureInsertCommentTypes.PropertySourceTarget queryFieldTarget(StringLiteral literal,
        DbViewFieldDef field, QuerySchemaSelectQuery select, XtextResource qlResource)
    {
        INode source = Plan.fieldNode(field, select, qlResource.getResourceServiceProvider().get(QlMapper.class));
        return source == null ? null : literalTarget(literal, source.getOffset(), source.getEndOffset());
    }

    /** Диапазон {@code [from, to)} текста запроса литерала — место в модуле; {@code null} — вне литерала. */
    static BslStructureInsertCommentTypes.PropertySourceTarget literalTarget(StringLiteral literal, int from, int to)
    {
        var lines = NodeModelUtils.findNodesForFeature(literal,
            com._1c.g5.v8.dt.bsl.model.BslPackage.Literals.STRING_LITERAL__LINES);
        if (lines.isEmpty())
            return null;
        DefaultStringLiteralTextProvider textProvider = new DefaultStringLiteralTextProvider(literal);
        var location = textProvider.getLocation(from);
        var endLocation = textProvider.getLocation(to);
        if (location == null)
            return null;
        int offset = lines.get(0).getOffset() + location.getSecond();
        int length = endLocation == null ? 0 : endLocation.getSecond() - location.getSecond();
        return new BslStructureInsertCommentTypes.PropertySourceTarget(EcoreUtil.getURI(literal), offset, length);
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
