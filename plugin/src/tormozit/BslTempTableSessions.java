package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.DefaultPositionUpdater;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckType;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator;
import com._1c.g5.v8.dt.bsl.model.ReturnStatement;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainerRef;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.ql.DerivedTypeComputerForDbViewQuery;
import com._1c.g5.v8.dt.ql.model.CommonExpression;
import com._1c.g5.v8.dt.ql.model.DbViewDescriptionTempTable;
import com._1c.g5.v8.dt.ql.model.DbViewFieldFieldDefFromQuery;
import com._1c.g5.v8.dt.ql.model.DbViewOuterParamTable;
import com._1c.g5.v8.dt.ql.model.QlFactory;
import com._1c.g5.v8.dt.ql.model.QlPackage;
import com._1c.g5.v8.dt.ql.model.QuerySchema;
import com._1c.g5.v8.dt.ql.model.QuerySchemaSelectQuery;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTempTableDescription;
import com._1c.g5.v8.dt.ql.typesystem.IDynamicDbViewFieldComputer;

/**
 * Сеансы менеджера временных таблиц и таблицы-параметры литерала запроса без ИР (issue 889): перенос веток
 * «Запрос» и «МенеджерВременныхТаблиц» {@code ирКлсПолеТекстаПрограммы.СобратьДинамическиеСвойстваОбъекта},
 * {@code СобратьОперацииМенеджераВременныхТаблиц} и источников {@code ВременнаяТаблица} анализатора запроса
 * ИР ({@code НайтиЗапросВременнойТаблицы}, таблица-параметр {@code &Имя}). ИР ищет присваивания регулярными
 * выражениями по тексту метода, здесь — по AST BSL.
 * <p>
 * Язык запросов EDT имя, не найденное в своём пакете, считает внешней временной таблицей
 * ({@link DbViewDescriptionTempTable}), а {@code ИЗ &Имя} — таблицей-параметром ({@link DbViewOuterParamTable});
 * обе без полей, поле создаётся без типа при первом обращении. {@link #enrich} заполняет их колонками с типами,
 * как ИР делает метаданными источника, — это видят автодополнение, проверка, типы результата и конструктор.
 */
public final class BslTempTableSessions
{
    private BslTempTableSessions() {}

    private static final Set<String> TEXT = Set.of("текст", "text"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> MANAGER = Set.of("менеджервременныхтаблиц", "temptablesmanager"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> EXECUTE = Set.of("выполнить", "execute", "выполнитьпакет", "executebatch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "выполнитьпакетспромежуточнымиданными", "executebatchwithintermediatedata"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> SET_PARAMETER = Set.of("установитьпараметр", "setparameter"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> PARAMETERS = Set.of("параметры", "parameters"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> INSERT = Set.of("вставить", "insert"); //$NON-NLS-1$ //$NON-NLS-2$
    /** Предел вложенности переходов в функции и между переменными — защита ИР {@code мРекурсивныйПуть}. */
    private static final int MAX_DEPTH = 6;
    /** Виртуальный документирующий метод ИР в закомментированном коде {@code //!}. */
    private static final Pattern DOCUMENTED_ADD = Pattern.compile(
        "^//!\\s*([\\p{L}_][\\p{L}\\p{Nd}_]*(?:\\s*\\.\\s*[\\p{L}_][\\p{L}\\p{Nd}_]*)*)\\s*\\.\\s*докДобавить\\s*\\(\\s*(.*?)\\s*\\)\\s*;?\\s*$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CALL = Pattern.compile("^([\\p{L}_][\\p{L}\\p{Nd}_]*)\\s*\\(.*\\)$", Pattern.DOTALL); //$NON-NLS-1$
    private static final Pattern PATH = Pattern.compile(
        "^[\\p{L}_][\\p{L}\\p{Nd}_]*(?:\\s*\\.\\s*[\\p{L}_][\\p{L}\\p{Nd}_]*)*$"); //$NON-NLS-1$

    // ---- Модель ----------------------------------------------------------------------------------

    /** Колонка источника; {@code target} — место её объявления в модуле ({@code null} — нет). */
    record Column(String name, TypeDescription type, BslStructureInsertCommentTypes.PropertySourceTarget target) {}

    /** Временная таблица или таблица-параметр; {@code target} — имя после {@code ПОМЕСТИТЬ}. */
    record Table(String name, List<Column> columns, BslStructureInsertCommentTypes.PropertySourceTarget target) {}

    /**
     * Временные таблицы предыдущих пакетов менеджера и таблицы-параметры запроса; ключи — имя в нижнем регистре
     * (параметр — без {@code &}).
     */
    record Session(Map<String, Table> tables, Map<String, Table> parameters, String fingerprint)
    {
        boolean isEmpty()
        {
            return tables.isEmpty() && parameters.isEmpty();
        }
    }

    static final Session EMPTY = new Session(Map.of(), Map.of(), ""); //$NON-NLS-1$

    private static Session session(Map<String, Table> tables, Map<String, Table> parameters)
    {
        StringBuilder fingerprint = new StringBuilder();
        for (var group : List.of(tables, parameters))
        {
            fingerprint.append('|');
            for (Table table : group.values())
            {
                fingerprint.append(table.name()).append('(');
                for (Column column : table.columns())
                {
                    fingerprint.append(column.name()).append(':');
                    if (column.type() != null)
                        for (TypeItem item : column.type().getTypes())
                            fingerprint.append(McoreUtil.getTypeName(item)).append('+');
                    fingerprint.append(',');
                }
                fingerprint.append(')');
            }
        }
        return tables.isEmpty() && parameters.isEmpty() ? EMPTY
            : new Session(Map.copyOf(tables), Map.copyOf(parameters), fingerprint.toString());
    }

    // ---- Обогащение модели языка запросов ------------------------------------------------------------

    /** Поле, заполненное нами: добавленное ({@code added}) или созданное EDT по обращению и получившее тип. */
    private static final class OwnField extends AdapterImpl
    {
        final boolean added;

        OwnField(boolean added)
        {
            this.added = added;
        }

        @Override
        public boolean isAdapterForType(Object type)
        {
            return type == OwnField.class;
        }
    }

    /** Чем заполнена таблица: повтор с тем же составом не трогает модель. */
    private static final class Applied extends AdapterImpl
    {
        final String fingerprint;
        final Table source;

        Applied(String fingerprint, Table source)
        {
            this.fingerprint = fingerprint;
            this.source = source;
        }

        @Override
        public boolean isAdapterForType(Object type)
        {
            return type == Applied.class;
        }
    }

    /**
     * Колонки и типы внешним временным таблицам и таблицам-параметрам ресурса запроса — замена метаданных
     * источника {@code ВременнаяТаблица} ИР. Повторный вызов с другим сеансом снимает прежнее заполнение.
     */
    static void enrich(Resource resource, Session session)
    {
        if (resource != null && session != null)
            enrich(resource.getAllContents(), session);
    }

    /** То же для схемы вне ресурса (рабочая копия конструктора). */
    static void enrich(EObject root, Session session)
    {
        if (root != null && session != null)
            enrich(root.eResource() != null ? root.eResource().getAllContents() : root.eAllContents(), session);
    }

    private static void enrich(java.util.Iterator<? extends EObject> contents, Session session)
    {
        List<DbViewDescriptionTempTable> tables = new ArrayList<>();
        Map<String, String> parameterByAlias = new HashMap<>();
        while (contents.hasNext())
        {
            EObject object = contents.next();
            if (object instanceof DbViewDescriptionTempTable table)
                tables.add(table);
            else if (object instanceof QuerySchemaTempTableDescription description && description.getTable() != null
                && description.getTable().getName() != null)
                parameterByAlias.put(lower(description.getAlias()), lower(description.getTable().getName().substring(
                    description.getTable().getName().startsWith("&") ? 1 : 0))); //$NON-NLS-1$
        }
        for (DbViewDescriptionTempTable table : tables)
        {
            Table source = table instanceof DbViewOuterParamTable
                ? session.parameters().get(parameterByAlias.getOrDefault(lower(table.getName()), "")) //$NON-NLS-1$
                : session.tables().get(lower(table.getName()));
            apply(table, source);
        }
    }

    /** Одна таблица (поле которой показывается); повтор с тем же сеансом модель не трогает. */
    static void enrichTable(DbViewDescriptionTempTable table, Session session)
    {
        if (table == null || session == null)
            return;
        if (table instanceof DbViewOuterParamTable)
            enrich(EcoreUtil.getRootContainer(table), session);
        else
            apply(table, session.tables().get(lower(table.getName())));
    }

    private static void apply(DbViewDescriptionTempTable table, Table source)
    {
        String fingerprint = source == null ? "" : source.toString(); //$NON-NLS-1$
        Applied applied = (Applied)EcoreUtil.getExistingAdapter(table, Applied.class);
        if (applied != null ? applied.fingerprint.equals(fingerprint) : source == null)
            return;
        boolean deliver = table.eDeliver();
        table.eSetDeliver(false);
        try
        {
            for (DbViewFieldDef field : List.copyOf(table.getFields()))
            {
                OwnField own = (OwnField)EcoreUtil.getExistingAdapter(field, OwnField.class);
                if (own == null)
                    continue;
                if (own.added)
                    table.getFields().remove(field);
                else
                {
                    field.setType(McoreFactory.eINSTANCE.createTypeDescription());
                    field.eAdapters().remove(own);
                }
            }
            if (applied != null)
                table.eAdapters().remove(applied);
            if (source == null)
                return;
            for (Column column : source.columns())
            {
                DbViewFieldDef existing = null;
                for (DbViewFieldDef field : table.getFields())
                    if (column.name().equalsIgnoreCase(field.getName()) || column.name().equalsIgnoreCase(field.getNameRu()))
                        existing = field;
                TypeDescription type = column.type() == null ? McoreFactory.eINSTANCE.createTypeDescription()
                    : EcoreUtil.copy(column.type());
                // Поле по обращению создают и EDT, и конструктор — у них разные классы, тип есть у любого
                if (existing != null)
                {
                    existing.setType(type);
                    existing.eAdapters().add(new OwnField(false));
                }
                else
                {
                    DbViewFieldFieldDefFromQuery field = QlFactory.eINSTANCE.createDbViewFieldFieldDefFromQuery();
                    field.setName(column.name());
                    field.setNameRu(column.name());
                    field.setType(type);
                    field.eAdapters().add(new OwnField(true));
                    table.getFields().add(field);
                }
            }
            table.eAdapters().add(new Applied(fingerprint, source));
        }
        finally
        {
            table.eSetDeliver(deliver);
        }
    }

    /**
     * EDT создаёт любое запрошенное поле внешней таблицы (DynamicDbViewFieldComputer), даже если колонки
     * уже заполнены. Сверяем разрешённое поле с исходной схемой, а не с пополненным списком getFields().
     * Источник берём у самой таблицы: учитываются псевдонимы, таблицы-параметры и области вложенных запросов.
     */
    static List<Issue> validateFields(XtextResource resource)
    {
        List<Issue> issues = new ArrayList<>();
        List<CommonExpression> expressions = new ArrayList<>();
        Set<DbViewDescriptionTempTable> knownTables = Collections.newSetFromMap(new IdentityHashMap<>());
        // Разрешение поля может добавить его в модель; обход содержимого к этому моменту должен закончиться.
        resource.getAllContents().forEachRemaining(object ->
        {
            if (object instanceof CommonExpression expression)
                expressions.add(expression);
            else if (object instanceof DbViewDescriptionTempTable table
                && EcoreUtil.getExistingAdapter(table, Applied.class) != null)
                knownTables.add(table);
        });
        if (knownTables.isEmpty())
            return issues;
        var computer = resource.getResourceServiceProvider().get(IDynamicDbViewFieldComputer.class);
        for (CommonExpression expression : expressions)
        {
            if (expression.getContent() == null || expression.getContent().isBlank())
                continue;
            if (!(computer.computeDbView(expression) instanceof DbViewFieldDef field)
                || !(field.eContainer() instanceof DbViewDescriptionTempTable table) || !knownTables.contains(table))
                continue;
            Applied applied = (Applied)EcoreUtil.getExistingAdapter(table, Applied.class);
            if (applied == null)
                continue;
            boolean known = applied.source.columns().stream().anyMatch(column ->
                column.name().equalsIgnoreCase(field.getName()) || column.name().equalsIgnoreCase(field.getNameRu()));
            if (known)
                continue;
            for (INode node : NodeModelUtils.findNodesForFeature(expression, QlPackage.Literals.COMMON_EXPRESSION__CONTENT))
            {
                Issue.IssueImpl issue = new Issue.IssueImpl();
                issue.setSeverity(Severity.ERROR);
                issue.setType(CheckType.FAST);
                issue.setCode("tormozit.comfort.unknownTempTableField"); //$NON-NLS-1$
                issue.setMessage("Поле «" + expression.getContent() + "» не найдено в таблице «" + applied.source.name() + "»");
                issue.setOffset(node.getOffset());
                issue.setLength(node.getLength());
                issues.add(issue);
            }
        }
        return issues;
    }

    /**
     * Куда ведёт переход с элемента модели запроса: внешняя временная таблица — на её {@code ПОМЕСТИТЬ} в другом
     * литерале, её поле — на поле того запроса. {@code null} — элемент не из сеанса.
     */
    static BslStructureInsertCommentTypes.PropertySourceTarget targetOf(EObject object, Session session)
    {
        if (session == null || object == null)
            return null;
        EObject owner = object instanceof DbViewFieldDef ? object.eContainer() : object;
        if (!(owner instanceof DbViewDescriptionTempTable table) || owner instanceof DbViewOuterParamTable)
            return null;
        Table source = session.tables().get(lower(table.getName()));
        if (source == null)
            return null;
        if (!(object instanceof DbViewFieldDef field))
            return source.target();
        for (Column column : source.columns())
            if (column.name().equalsIgnoreCase(field.getName()) || column.name().equalsIgnoreCase(field.getNameRu()))
                return column.target();
        return null;
    }

    /** Открыть место объявления (UI). */
    static void open(BslStructureInsertCommentTypes.PropertySourceTarget target)
    {
        if (target != null)
            BslStructureInsertCommentTypes.openPropertySource(target);
    }

    // ---- Расчёт сеанса литерала ------------------------------------------------------------------------

    /** Готовый сеанс литерала: действителен, пока не изменились методы, которые читал расчёт. */
    private record Cached(Map<Method, Integer> dependencies, long generation, Session session) {}

    private static final Map<StringLiteral, Cached> SESSIONS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Сеанс литерала-текста запроса. Вызывающий держит readOnly документа модуля. */
    static Session compute(StringLiteral literal, CancelIndicator cancellation)
    {
        if (literal == null || !BslDocCommentComputedTypes.isExtendedTypesEnabled(literal))
            return EMPTY;
        Cached cached = SESSIONS.get(literal);
        long generation = BslQueryResultTypes.metadataGeneration();
        if (cached != null && cached.generation == generation && current(cached.dependencies))
            return cached.session;
        Analysis analysis = new Analysis(literal, cancellation);
        Session result;
        try
        {
            result = analysis.run();
        }
        catch (org.eclipse.core.runtime.OperationCanceledException cancelled)
        {
            throw cancelled;
        }
        catch (RuntimeException | LinkageError failure)
        {
            // сбой анализа — литерал без внешних таблиц
            result = EMPTY;
        }
        SESSIONS.put(literal, new Cached(Map.copyOf(analysis.dependencies), generation, result));
        return result;
    }

    private static boolean current(Map<Method, Integer> dependencies)
    {
        for (var entry : dependencies.entrySet())
            if (entry.getKey().eResource() == null || entry.getValue() != textHash(entry.getKey()))
                return false;
        return true;
    }

    private static int textHash(Method method)
    {
        ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
        return node == null ? 0 : node.getText().hashCode();
    }

    // ---- ВременныеТаблицыЗапроса: Таблицы["Вт"], Таблицы.Найти("Вт"), Колонки (ИР стр. 7770, ВычислитьВременныеТаблицыЗапросаНайти) ----

    private static final Set<String> TABLES = Set.of("таблицы", "tables"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> COLUMNS = Set.of("колонки", "columns"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Set<String> FIND = Set.of("найти", "find"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Временные таблицы менеджера по выражению его коллекции {@code М.Таблицы} в её позиции; пусто — не коллекция
     * менеджера или таблиц нет. Вызывающий держит readOnly документа модуля.
     */
    static Map<String, Table> collectionTables(Expression tables, CancelIndicator cancellation)
    {
        if (!isFeature(tables, TABLES) || !BslDocCommentComputedTypes.isExtendedTypesEnabled(tables))
            return Map.of();
        Analysis analysis = new Analysis(null, cancellation);
        try
        {
            return analysis.tables(analysis.managerBatches(((DynamicFeatureAccess)tables).getSource()));
        }
        catch (org.eclipse.core.runtime.OperationCanceledException cancelled)
        {
            throw cancelled;
        }
        catch (RuntimeException | LinkageError failure)
        {
            return Map.of();
        }
    }

    /**
     * Таблица выражения {@code М.Таблицы["Вт"]}, {@code М.Таблицы.Найти("Вт")} или переменной с таким значением;
     * {@code null} — не временная таблица менеджера. Вызывающий держит readOnly документа модуля.
     */
    static Table tempTable(Expression expression, CancelIndicator cancellation)
    {
        Expression origin = tableOrigin(expression, 0);
        if (origin == null)
            return null;
        Expression collection;
        StringLiteral name;
        if (origin instanceof IndexAccess access)
        {
            collection = access.getSource();
            name = (StringLiteral)access.getIndex();
        }
        else
        {
            Invocation find = (Invocation)origin;
            collection = ((DynamicFeatureAccess)find.getMethodAccess()).getSource();
            name = (StringLiteral)find.getParams().get(0);
        }
        return collectionTables(collection, cancellation).get(lower(new DefaultStringLiteralTextProvider(name).getText().strip()));
    }

    /** Выражение — временная таблица менеджера по имени (само или значение переменной); без расчёта менеджера. */
    static boolean isTempTableExpression(Expression expression)
    {
        return tableOrigin(expression, 0) != null;
    }

    private static Expression tableOrigin(Expression expression, int depth)
    {
        if (expression instanceof IndexAccess access && isFeature(access.getSource(), TABLES)
            && access.getIndex() instanceof StringLiteral)
            return expression;
        if (expression instanceof Invocation invocation && invocation.getMethodAccess() instanceof DynamicFeatureAccess find
            && FIND.contains(lower(find.getName())) && isFeature(find.getSource(), TABLES)
            && !invocation.getParams().isEmpty() && invocation.getParams().get(0) instanceof StringLiteral)
            return expression;
        Method method = expression instanceof StaticFeatureAccess && depth < MAX_DEPTH
            ? EcoreUtil2.getContainerOfType(expression, Method.class) : null;
        SimpleStatement origin = method == null || key(expression) == null ? null
            : new Analysis(null, CancelIndicator.NullImpl).lastAssignment(method, key(expression), start(expression));
        return origin == null ? null : tableOrigin(origin.getRight(), depth + 1);
    }

    /** Имена колонок временной таблицы по выражению {@code ….Колонки}; {@code null} — не колонки таблицы менеджера. */
    static List<String> columnNames(Expression columns, CancelIndicator cancellation)
    {
        Table table = isFeature(columns, COLUMNS) ? tempTable(((DynamicFeatureAccess)columns).getSource(), cancellation) : null;
        return table == null ? null : table.columns().stream().map(Column::name).toList();
    }

    /**
     * Имена для литерала-аргумента: таблицы в {@code М.Таблицы["|"]} и {@code М.Таблицы.Найти("|")}, колонки в
     * {@code ….Колонки.Найти("|")}; {@code null} — литерал не из них.
     */
    static List<String> literalNames(StringLiteral literal, CancelIndicator cancellation)
    {
        Expression collection = literalCollection(literal);
        if (isFeature(collection, TABLES))
            return collectionTables(collection, cancellation).values().stream().map(Table::name).toList();
        return collection == null ? null : columnNames(collection, cancellation);
    }

    /** Переход с имени в литерале-аргументе: к {@code ПОМЕСТИТЬ} таблицы или к её полю; {@code null} — места нет. */
    static BslStructureInsertCommentTypes.PropertySourceTarget literalNameTarget(StringLiteral literal,
        CancelIndicator cancellation)
    {
        Expression collection = literalCollection(literal);
        String name = lower(new DefaultStringLiteralTextProvider(literal).getText().strip());
        if (isFeature(collection, TABLES))
        {
            Table table = collectionTables(collection, cancellation).get(name);
            return table == null ? null : table.target();
        }
        Table table = isFeature(collection, COLUMNS)
            ? tempTable(((DynamicFeatureAccess)collection).getSource(), cancellation) : null;
        if (table != null)
            for (Column column : table.columns())
                if (lower(column.name()).equals(name))
                    return column.target();
        return null;
    }

    /** Коллекция {@code Таблицы}/{@code Колонки}, для которой литерал — имя в {@code [""]} или {@code Найти("")}. */
    private static Expression literalCollection(StringLiteral literal)
    {
        Expression collection = null;
        if (literal.eContainer() instanceof IndexAccess access && access.getIndex() == literal)
            collection = access.getSource();
        else if (literal.eContainer() instanceof Invocation invocation && !invocation.getParams().isEmpty()
            && invocation.getParams().get(0) == literal && invocation.getMethodAccess() instanceof DynamicFeatureAccess find
            && FIND.contains(lower(find.getName())))
            collection = find.getSource();
        return isFeature(collection, TABLES) || isFeature(collection, COLUMNS) ? collection : null;
    }

    /** Выражение — вызов функции, возвращающей запрос ({@code Запрос = Главный2()}). */
    static boolean returnsQuery(Expression expression)
    {
        return new Analysis(null, CancelIndicator.NullImpl).returnsQuery(expression, 0);
    }

    /** Пакет запросов: текст, выполненный в менеджер до текущего; параметры — того же запроса в {@code from..to}. */
    private record Batch(StringLiteral literal, Method method, String query, int from, int to) {}

    /** Переменная запроса литерала и позиция, в которой считается её состояние. */
    private record QueryUse(Method method, String query, int position) {}

    private static final class Analysis
    {
        final StringLiteral current;
        final CancelIndicator cancellation;
        final Map<Method, Integer> dependencies = new IdentityHashMap<>();
        final Map<Method, List<SimpleStatement>> statements = new IdentityHashMap<>();
        final Set<String> visiting = new HashSet<>();

        Analysis(StringLiteral current, CancelIndicator cancellation)
        {
            this.current = current;
            this.cancellation = cancellation;
        }

        Session run()
        {
            QueryUse use = queryOf(current, this);
            if (use == null)
                return EMPTY;
            List<Batch> batches = query(use.method(), use.query(), use.position(), 0);
            return session(tables(batches), parameters(use.method(), use.query(), start(use.method()), use.position()));
        }

        /** Временные таблицы пакетов в порядке выполнения: поздняя одноимённая замещает раннюю. */
        Map<String, Table> tables(List<Batch> batches)
        {
            Map<String, Table> tables = new LinkedHashMap<>();
            // ИР: ТекстИмитаторовВременныхТаблиц — каждый пакет разбирается с таблицами пакетов до него
            for (Batch batch : batches)
            {
                checkCanceled();
                QuerySchema schema = BslQueryResultTypes.literalSchema(batch.literal(), cancellation);
                if (schema == null || !(schema.eResource() instanceof XtextResource resource)
                    || !resource.getErrors().isEmpty())
                    continue;
                enrich(resource, session(tables, parameters(batch.method(), batch.query(), batch.from(), batch.to())));
                var computer = resource.getResourceServiceProvider().get(DerivedTypeComputerForDbViewQuery.class);
                for (var query : schema.getQueries())
                {
                    if (!(query instanceof QuerySchemaSelectQuery select) || select.getPlacementTable() == null
                        || select.getPlacementTable().getTableName() == null || select.getDbViewByThis() == null)
                        continue;
                    List<Column> columns = new ArrayList<>();
                    for (DbViewFieldDef field : select.getDbViewByThis().getActualFields())
                    {
                        String name = field.getNameRu() != null && !field.getNameRu().isBlank() ? field.getNameRu()
                            : field.getName();
                        if (name == null || name.isBlank())
                            continue;
                        TypeDescription type = computer.getFieldType(field).getFirst();
                        columns.add(new Column(name, type == null ? null : EcoreUtil.copy(type),
                            BslQueryResultTypes.queryFieldTarget(batch.literal(), field, select, resource)));
                    }
                    INode placement = NodeModelUtils.findActualNodeFor(select.getPlacementTable());
                    String name = select.getPlacementTable().getTableName();
                    tables.remove(lower(name));
                    tables.put(lower(name), new Table(name, List.copyOf(columns), placement == null ? null
                        : BslQueryResultTypes.literalTarget(batch.literal(), placement.getOffset(), placement.getEndOffset())));
                }
            }
            return tables;
        }

        /** Пакеты менеджера, заданного выражением, к его позиции: {@code Х.МенеджерВременныхТаблиц}, переменная, функция. */
        List<Batch> managerBatches(Expression manager)
        {
            Method method = EcoreUtil2.getContainerOfType(manager, Method.class);
            if (method == null)
                return List.of();
            return isFeature(manager, MANAGER)
                ? query(method, key(((DynamicFeatureAccess)manager).getSource()), start(manager), 0)
                : manager(method, manager, start(manager), 0, null);
        }

        /** {@code РазобратьКонтекстЗапросаВТекстовомЛитерале}: переменная запроса — перед {@code .Текст =}. */
        static QueryUse queryOf(StringLiteral literal, Analysis analysis)
        {
            Method method = literal == null ? null : EcoreUtil2.getContainerOfType(literal, Method.class);
            if (method == null)
                return null;
            String query = null;
            int at = -1;
            if (literal.eContainer() instanceof SimpleStatement assignment && assignment.getRight() == literal)
            {
                if (isFeature(assignment.getLeft(), TEXT))
                {
                    query = key(((DynamicFeatureAccess)assignment.getLeft()).getSource());
                    at = end(assignment);
                }
                else
                {
                    // ТекстЗапроса = "…"; Запрос.Текст = ТекстЗапроса;
                    String variable = key(assignment.getLeft());
                    for (SimpleStatement next : analysis.statements(method))
                        if (variable != null && start(next) > end(assignment) && isFeature(next.getLeft(), TEXT)
                            && variable.equals(key(next.getRight())))
                        {
                            query = key(((DynamicFeatureAccess)next.getLeft()).getSource());
                            at = end(next);
                            break;
                        }
                }
            }
            else if (literal.eContainer() instanceof OperatorStyleCreator creator && isType(creator, "Query") //$NON-NLS-1$
                && creator.getParams().indexOf(literal) == 0 && creator.eContainer() instanceof SimpleStatement assignment
                && assignment.getRight() == creator)
            {
                query = key(assignment.getLeft());
                at = end(assignment);
            }
            if (query == null)
                return null;
            // ИР: тип переменной запроса — в позиции её «.Выполнить» после литерала
            int execute = -1;
            for (Invocation invocation : EcoreUtil2.getAllContentsOfType(method, Invocation.class))
                if (invocation.getMethodAccess() instanceof DynamicFeatureAccess access
                    && EXECUTE.contains(lower(access.getName())) && query.equals(key(access.getSource()))
                    && start(invocation) > at && (execute < 0 || start(invocation) < execute))
                    execute = start(invocation);
            return new QueryUse(method, query, execute >= 0 ? execute : at);
        }

        /** Ветка «Запрос»: пакеты менеджера запроса {@code query} к позиции {@code position}. */
        List<Batch> query(Method method, String query, int position, int depth)
        {
            List<Batch> batches = new ArrayList<>();
            if (query == null || depth > MAX_DEPTH || !visiting.add("q:" + start(method) + ':' + query + ':' + position)) //$NON-NLS-1$
                return batches;
            checkCanceled();
            SimpleStatement origin = lastAssignment(method, query, position);
            int from = origin == null ? start(method) : end(origin);
            if (origin != null)
            {
                Expression right = origin.getRight();
                Method function = calledFunction(right);
                if (right instanceof OperatorStyleCreator creator)
                {
                    if (isType(creator, "Query") && !creator.getParams().isEmpty()) //$NON-NLS-1$
                        addBatch(batches, textLiteral(method, creator.getParams().get(0), start(origin)), method, query,
                            from, position);
                }
                else if (function != null)
                {
                    for (ReturnStatement exit : EcoreUtil2.getAllContentsOfType(function, ReturnStatement.class))
                        if (exit.getExpression() != null)
                            addAll(batches, queryValue(function, exit.getExpression(), start(exit), depth + 1));
                }
                else if (key(right) != null)
                    addAll(batches, query(method, key(right), start(origin), depth + 1));
            }
            // СобратьОперацииМенеджераВременныхТаблиц: менеджер запроса
            SimpleStatement manager = null;
            for (SimpleStatement statement : statements(method))
                if (start(statement) > from && end(statement) <= position && isFeature(statement.getLeft(), MANAGER)
                    && query.equals(key(((DynamicFeatureAccess)statement.getLeft()).getSource()))
                    && (manager == null || start(statement) > start(manager)))
                    manager = statement;
            if (manager != null)
                addAll(batches, manager(method, manager.getRight(), start(manager), depth + 1, query));
            addAll(batches, documented(method, query + ".менеджервременныхтаблиц", from, position, depth)); //$NON-NLS-1$
            // Тексты этого запроса: ИР вставляет их после пакетов менеджера в порядке присваиваний
            addAll(batches, texts(method, query, from, position));
            return batches;
        }

        /** Значение выражения — запрос: переменная, конструктор или вызов функции. */
        private List<Batch> queryValue(Method method, Expression expression, int position, int depth)
        {
            List<Batch> batches = new ArrayList<>();
            if (expression instanceof OperatorStyleCreator creator && isType(creator, "Query")) //$NON-NLS-1$
            {
                if (!creator.getParams().isEmpty())
                    addBatch(batches, textLiteral(method, creator.getParams().get(0), position), method, null, position,
                        position);
                return batches;
            }
            Method function = calledFunction(expression);
            if (function != null && depth <= MAX_DEPTH)
            {
                for (ReturnStatement exit : EcoreUtil2.getAllContentsOfType(function, ReturnStatement.class))
                    if (exit.getExpression() != null)
                        addAll(batches, queryValue(function, exit.getExpression(), start(exit), depth + 1));
                return batches;
            }
            return query(method, key(expression), position, depth);
        }

        /** Ветка «МенеджерВременныхТаблиц»: пакеты менеджера, заданного выражением. */
        private List<Batch> manager(Method method, Expression expression, int position, int depth, String exclude)
        {
            List<Batch> batches = new ArrayList<>();
            if (expression == null || depth > MAX_DEPTH || expression instanceof OperatorStyleCreator)
                return batches;
            if (isFeature(expression, MANAGER))
                return query(method, key(((DynamicFeatureAccess)expression).getSource()), position, depth + 1);
            Method function = calledFunction(expression);
            if (function != null)
            {
                for (ReturnStatement exit : EcoreUtil2.getAllContentsOfType(function, ReturnStatement.class))
                    if (exit.getExpression() != null)
                        addAll(batches, manager(function, exit.getExpression(), start(exit), depth + 1, null));
                return batches;
            }
            String variable = key(expression);
            return variable == null ? batches : managerVariable(method, variable, position, depth, exclude);
        }

        /** Переменная-менеджер: её происхождение, запросы с {@code .МенеджерВременныхТаблиц = Переменная} и {@code докДобавить}. */
        private List<Batch> managerVariable(Method method, String variable, int position, int depth, String exclude)
        {
            List<Batch> batches = new ArrayList<>();
            if (depth > MAX_DEPTH || !visiting.add("m:" + start(method) + ':' + variable + ':' + position)) //$NON-NLS-1$
                return batches;
            SimpleStatement origin = lastAssignment(method, variable, position);
            int from = origin == null ? start(method) : end(origin);
            if (origin != null)
                addAll(batches, manager(method, origin.getRight(), start(origin), depth + 1, exclude));
            for (SimpleStatement statement : statements(method))
            {
                if (start(statement) <= from || end(statement) > position || !isFeature(statement.getLeft(), MANAGER)
                    || !variable.equals(key(statement.getRight())))
                    continue;
                String query = key(((DynamicFeatureAccess)statement.getLeft()).getSource());
                if (query != null && !query.equals(exclude))
                    addAll(batches, texts(method, query, end(statement), position));
            }
            addAll(batches, documented(method, variable, from, position, depth));
            return batches;
        }

        /** Тексты запроса {@code query}, присвоенные в {@code from..to}. */
        private List<Batch> texts(Method method, String query, int from, int to)
        {
            List<Batch> batches = new ArrayList<>();
            for (SimpleStatement statement : statements(method))
                if (start(statement) > from && end(statement) <= to && isFeature(statement.getLeft(), TEXT)
                    && query.equals(key(((DynamicFeatureAccess)statement.getLeft()).getSource())))
                    addBatch(batches, textLiteral(method, statement.getRight(), start(statement)), method, query, from, to);
            return batches;
        }

        /** {@code //!Выражение.докДобавить(Менеджер)} в {@code from..to}: пакеты добавляемого менеджера. */
        private List<Batch> documented(Method method, String manager, int from, int to, int depth)
        {
            List<Batch> batches = new ArrayList<>();
            ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
            if (node == null)
                return batches;
            for (ILeafNode leaf : node.getLeafNodes())
            {
                if (!leaf.isHidden() || leaf.getOffset() <= from || leaf.getOffset() >= to
                    || !leaf.getText().startsWith("//!")) //$NON-NLS-1$
                    continue;
                Matcher matcher = DOCUMENTED_ADD.matcher(leaf.getText().strip());
                if (!matcher.matches() || !manager.equals(normalize(matcher.group(1))))
                    continue;
                String argument = matcher.group(2);
                Matcher call = CALL.matcher(argument);
                if (call.matches())
                {
                    Method function = moduleMethod(method, call.group(1));
                    if (function != null)
                        for (ReturnStatement exit : EcoreUtil2.getAllContentsOfType(function, ReturnStatement.class))
                            if (exit.getExpression() != null)
                                addAll(batches, manager(function, exit.getExpression(), start(exit), depth + 1, null));
                }
                else if (PATH.matcher(argument).matches())
                {
                    String path = normalize(argument);
                    int dot = path.lastIndexOf('.');
                    if (dot > 0 && MANAGER.contains(path.substring(dot + 1)))
                        addAll(batches, query(method, path.substring(0, dot), leaf.getOffset(), depth + 1));
                    else
                        addAll(batches, managerVariable(method, path, leaf.getOffset(), depth + 1, null));
                }
            }
            return batches;
        }

        boolean returnsQuery(Expression expression, int depth)
        {
            Method function = calledFunction(expression);
            if (function == null || depth > MAX_DEPTH)
                return false;
            for (ReturnStatement exit : EcoreUtil2.getAllContentsOfType(function, ReturnStatement.class))
            {
                Expression value = exit.getExpression();
                if (value instanceof OperatorStyleCreator creator && isType(creator, "Query") //$NON-NLS-1$
                    || returnsQuery(value, depth + 1))
                    return true;
                SimpleStatement origin = key(value) == null ? null : lastAssignment(function, key(value), start(exit));
                if (origin != null && (origin.getRight() instanceof OperatorStyleCreator creator && isType(creator, "Query") //$NON-NLS-1$
                    || returnsQuery(origin.getRight(), depth + 1)))
                    return true;
            }
            return false;
        }

        /** Таблицы-параметры запроса из {@code УстановитьПараметр}/{@code Параметры.Вставить} в {@code from..to}. */
        private Map<String, Table> parameters(Method method, String query, int from, int to)
        {
            Map<String, Table> tables = new LinkedHashMap<>();
            if (query == null)
                return tables;
            TypesComputer types = null;
            for (Invocation invocation : EcoreUtil2.getAllContentsOfType(method, Invocation.class))
            {
                if (start(invocation) <= from || end(invocation) > to || invocation.getParams().size() < 2
                    || !(invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
                    || !(invocation.getParams().get(0) instanceof StringLiteral nameLiteral))
                    continue;
                String name = lower(access.getName());
                boolean set = SET_PARAMETER.contains(name) && query.equals(key(access.getSource()));
                boolean insert = INSERT.contains(name) && isFeature(access.getSource(), PARAMETERS)
                    && query.equals(key(((DynamicFeatureAccess)access.getSource()).getSource()));
                if (!set && !insert)
                    continue;
                String parameter = new DefaultStringLiteralTextProvider(nameLiteral).getText().strip();
                if (parameter.isEmpty())
                    continue;
                if (types == null)
                    types = IResourceServiceProvider.Registry.INSTANCE
                        .getResourceServiceProvider(method.eResource().getURI()).get(TypesComputer.class);
                List<Column> columns = valueTableColumns(types, method, invocation.getParams().get(1));
                if (!columns.isEmpty())
                    tables.put(lower(parameter), new Table(parameter, columns, null));
            }
            return tables;
        }

        /** {@code ТаблицаИзОписанияПараметраЗапроса}: колонки таблицы значений — значения параметра. */
        private static List<Column> valueTableColumns(TypesComputer types, Method method, Expression value)
        {
            List<Column> columns = new ArrayList<>();
            for (TypeItem item : types.computeTypes(value, method.environments()))
            {
                EObject resolved = EcoreUtil.resolve(item, value);
                if (!(resolved instanceof Type table) || table.eIsProxy()
                    || !"ValueTable".equals(McoreUtil.getTypeName(table)) || table.getCollectionElementTypes() == null) //$NON-NLS-1$
                    continue;
                for (TypeItem rowItem : table.getCollectionElementTypes().allTypes())
                {
                    if (!(rowItem instanceof Type row) || row.getContextDef() == null)
                        continue;
                    for (Property property : row.getContextDef().allProperties())
                    {
                        if (property.isDependsOnMetadata())
                            continue;
                        String name = property.getNameRu() != null && !property.getNameRu().isBlank()
                            ? property.getNameRu() : property.getName();
                        if (name == null || name.isBlank())
                            continue;
                        TypeDescription type = McoreFactory.eINSTANCE.createTypeDescription();
                        var propertyTypes = property.getTypeContainer() instanceof TypeContainerRef ref
                            ? ref.getTypes() : property.getTypes();
                        if (propertyTypes != null)
                            type.getTypes().addAll(propertyTypes);
                        columns.add(new Column(name, type, null));
                    }
                    if (!columns.isEmpty())
                        return columns;
                }
            }
            return columns;
        }

        private void addBatch(List<Batch> batches, StringLiteral literal, Method method, String query, int from, int to)
        {
            if (literal == null || literal == current)
                return;
            for (Batch batch : batches)
                if (batch.literal() == literal)
                    return;
            batches.add(new Batch(literal, method, query, from, to));
        }

        private void addAll(List<Batch> target, List<Batch> source)
        {
            for (Batch batch : source)
                addBatch(target, batch.literal(), batch.method(), batch.query(), batch.from(), batch.to());
        }

        /** Строковый литерал текста: сам литерал или последняя запись переменной литералом. */
        private StringLiteral textLiteral(Method method, Expression expression, int position)
        {
            if (expression instanceof StringLiteral literal)
                return literal;
            String variable = key(expression);
            SimpleStatement origin = variable == null ? null : lastAssignment(method, variable, position);
            return origin != null && origin.getRight() instanceof StringLiteral literal ? literal : null;
        }

        private SimpleStatement lastAssignment(Method method, String variable, int position)
        {
            SimpleStatement last = null;
            for (SimpleStatement statement : statements(method))
                if (statement.getRight() != null && end(statement) <= position && variable.equals(key(statement.getLeft()))
                    && (last == null || start(statement) > start(last)))
                    last = statement;
            return last;
        }

        List<SimpleStatement> statements(Method method)
        {
            return statements.computeIfAbsent(method, key ->
            {
                dependencies.put(key, textHash(key));
                return EcoreUtil2.getAllContentsOfType(key, SimpleStatement.class);
            });
        }

        private void checkCanceled()
        {
            if (cancellation.isCanceled())
                throw new org.eclipse.core.runtime.OperationCanceledException();
        }
    }

    /** Вызываемая функция модуля или общего модуля; {@code null} — не вызов функции. */
    private static Method calledFunction(Expression expression)
    {
        if (!(expression instanceof Invocation invocation))
            return null;
        List<FeatureEntry> entries = invocation.getMethodAccess() instanceof DynamicFeatureAccess dynamic
            ? dynamic.getFeatureEntries()
            : invocation.getMethodAccess() instanceof StaticFeatureAccess access ? access.getFeatureEntries() : List.of();
        for (FeatureEntry entry : entries)
        {
            EObject feature = entry == null ? null : entry.getFeature();
            if (feature != null && feature.eIsProxy())
                feature = EcoreUtil.resolve(feature, invocation);
            if (feature instanceof Method method && !method.eIsProxy())
                return method;
        }
        return null;
    }

    private static Method moduleMethod(Method context, String name)
    {
        Module module = EcoreUtil2.getContainerOfType(context, Module.class);
        if (module != null)
            for (Method method : module.allMethods())
                if (name.equalsIgnoreCase(method.getName()))
                    return method;
        return null;
    }

    private static boolean isFeature(Expression expression, Set<String> names)
    {
        return expression instanceof DynamicFeatureAccess access && names.contains(lower(access.getName()));
    }

    private static boolean isType(OperatorStyleCreator creator, String name)
    {
        return creator.getType() != null && name.equals(McoreUtil.getTypeName(creator.getType()));
    }

    /** Ключ простого выражения ({@code Запрос}, {@code ЭтотОбъект.Запрос}) в нижнем регистре; {@code null} — не путь. */
    private static String key(Expression expression)
    {
        if (expression instanceof StaticFeatureAccess access && access.getName() != null)
            return lower(access.getName());
        if (expression instanceof DynamicFeatureAccess access && access.getName() != null)
        {
            String source = key(access.getSource());
            return source == null ? null : source + '.' + lower(access.getName());
        }
        return null;
    }

    private static String normalize(String path)
    {
        return lower(path.replaceAll("\\s+", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- Сеансы открытого модуля для UI ---------------------------------------------------------------

    private static final String POSITION_CATEGORY = "tormozit.tempTableSessions"; //$NON-NLS-1$

    /** Литерал модуля (позиция открывающей кавычки сдвигается правками) и его сеанс. */
    private record Located(Position position, Session session) {}

    private static final Map<IDocument, List<Located>> PUBLISHED = Collections.synchronizedMap(new WeakHashMap<>());

    /** Готовый сеанс литерала с открывающей кавычкой {@code openQuote}; модель не читается. Только UI. */
    static Session sessionAt(IDocument document, int openQuote)
    {
        List<Located> items = document == null ? null : PUBLISHED.get(document);
        if (items == null)
            return EMPTY;
        for (Located item : items)
            if (!item.position().isDeleted() && item.position().getOffset() == openQuote)
                return item.session();
        return EMPTY;
    }

    /**
     * Сеанс литерала под смещением модуля для команды, вызванной пользователем (конструктор, редактор запроса):
     * готовый опубликованный, иначе считается сейчас под readOnly документа — метод мог ещё не считаться. UI.
     */
    static Session sessionForCommand(IDocument document, int openQuote)
    {
        Session published = sessionAt(document, openQuote);
        if (!published.isEmpty() || !(document instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument xtext))
            return published;
        Session computed = xtext.readOnly(resource ->
        {
            if (resource == null)
                return EMPTY;
            EObject object = new org.eclipse.xtext.resource.EObjectAtOffsetHelper()
                .resolveContainedElementAt(resource, openQuote + 1);
            StringLiteral literal = EcoreUtil2.getContainerOfType(object, StringLiteral.class);
            var lines = literal == null ? List.<INode>of()
                : NodeModelUtils.findNodesForFeature(literal, BslPackage.Literals.STRING_LITERAL__LINES);
            return lines.isEmpty() || lines.get(0).getOffset() != openQuote ? EMPTY
                : compute(literal, CancelIndicator.NullImpl);
        });
        return computed == null ? EMPTY : computed;
    }

    /**
     * Сеансы литералов-запросов метода под кареткой — из фонового досчёта после разбора правки
     * ({@code BslQueryResultTypes.watchEditor}, тот же readOnly). Публикуются в UI; при смене состава —
     * перепроверка живых маркеров запросов.
     */
    static void refreshDocument(IDocument document, XtextResource resource, int offset)
    {
        if (document == null || resource == null || resource.getContents().isEmpty()
            || !(resource.getContents().get(0) instanceof Module module))
            return;
        Map<Integer, Session> found = new LinkedHashMap<>();
        Analysis probe = new Analysis(null, CancelIndicator.NullImpl);
        for (Method method : module.allMethods())
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
            if (node == null)
                continue;
            // Считается только метод под кареткой; остальные — из готового кэша, если он ещё действителен
            boolean caretMethod = offset >= node.getOffset() && offset <= node.getEndOffset();
            for (StringLiteral literal : EcoreUtil2.getAllContentsOfType(method, StringLiteral.class))
            {
                Cached cached = SESSIONS.get(literal);
                if (!caretMethod && (cached == null || !current(cached.dependencies))
                    || caretMethod && Analysis.queryOf(literal, probe) == null)
                    continue;
                var lines = NodeModelUtils.findNodesForFeature(literal, BslPackage.Literals.STRING_LITERAL__LINES);
                if (!lines.isEmpty())
                    found.put(lines.get(0).getOffset(), caretMethod ? compute(literal, CancelIndicator.NullImpl)
                        : cached.session);
            }
        }
        if (!PlatformUI.isWorkbenchRunning())
            return;
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display.isDisposed())
            return;
        display.asyncExec(() -> publish(document, found));
    }

    private static void publish(IDocument document, Map<Integer, Session> found)
    {
        try
        {
            if (!document.containsPositionCategory(POSITION_CATEGORY))
            {
                document.addPositionCategory(POSITION_CATEGORY);
                document.addPositionUpdater(new DefaultPositionUpdater(POSITION_CATEGORY));
            }
            List<Located> previous = PUBLISHED.getOrDefault(document, List.of());
            Map<Integer, String> before = new HashMap<>();
            for (Located item : previous)
            {
                if (!item.position().isDeleted())
                    before.put(item.position().getOffset(), item.session().fingerprint());
                document.removePosition(POSITION_CATEGORY, item.position());
            }
            List<Located> items = new ArrayList<>();
            boolean changed = false;
            for (var entry : found.entrySet())
            {
                if (entry.getKey() > document.getLength())
                    continue;
                Position position = new Position(entry.getKey(), 1);
                document.addPosition(POSITION_CATEGORY, position);
                items.add(new Located(position, entry.getValue()));
                changed |= !entry.getValue().fingerprint().equals(before.getOrDefault(entry.getKey(), ""));
            }
            PUBLISHED.put(document, List.copyOf(items));
            if (changed)
                BslQueryLiteralSupport.tempTablesChanged(document);
        }
        catch (org.eclipse.jface.text.BadLocationException | org.eclipse.jface.text.BadPositionCategoryException failure)
        {
            // документ изменился — следующий досчёт опубликует заново
        }
    }

    // ---- Конструктор и редактор запроса ----------------------------------------------------------------

    /** Сеанс литерала, из которого открывается конструктор запроса; забирается первым окном. UI. */
    private static Session wizardSession = EMPTY;

    static void beginWizard(Session session)
    {
        wizardSession = session == null ? EMPTY : session;
    }

    static Session takeWizardSession()
    {
        Session session = wizardSession;
        wizardSession = EMPTY;
        return session;
    }

    // ---- Общее ------------------------------------------------------------------------------------

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
