package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.internal.text.html.HTMLPrinter;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FantomVariableOccurrence;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.NumberLiteral;
import com._1c.g5.v8.dt.bsl.model.ProposalElement;
import com._1c.g5.v8.dt.bsl.model.ReturnStatement;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.typesystem.util.TypeSystemUtil;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.DerivedProperty;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;

/** Разделы описания выражения для обеих подсказок. Расчёт никогда не выполняется в потоке показа. */
final class BslExpressionDetails
{
    private static final String MARK = "data-comfort-expression-details"; //$NON-NLS-1$
    private static final Map<Object, Request> REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());

    private BslExpressionDetails() {}

    static boolean hasRequests()
    {
        return !REQUESTS.isEmpty();
    }

    private static final class Request
    {
        final Object index;
        final long stamp;
        final java.lang.ref.WeakReference<IXtextDocument> document;
        volatile String html = ""; //$NON-NLS-1$

        Request(Object index, long stamp, IXtextDocument document)
        {
            this.index = index;
            this.stamp = stamp;
            this.document = new java.lang.ref.WeakReference<>(document);
        }
    }

    static void request(Object input, EObject member, ITextViewer viewer, int offset)
    {
        if (!IrBslHoverHtml.isBslBrowserInput(input) || viewer == null
            || !(viewer.getDocument() instanceof IXtextDocument document))
            return;
        Object index = Global.invoke(input, "getIndex"); //$NON-NLS-1$
        long stamp = stamp(document);
        Request request;
        synchronized (REQUESTS)
        {
            Request old = REQUESTS.get(input);
            if (old != null && old.stamp == stamp && java.util.Objects.equals(old.index, index))
                return;
            request = new Request(index, stamp, document);
            REQUESTS.put(input, request);
        }
        Global.tempLog("expression-details", "request offset=" + offset + " member=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (member == null ? null : member.eClass().getName()) + " stamp=" + stamp); //$NON-NLS-1$
        Display display = viewer.getTextWidget().getDisplay();
        Job job = new Job("Comfort: индексы и подсказка выражения") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    String html = ContentAssistSessionReloader.readOnlyPeekAst(document, resource ->
                        stamp(document) == stamp ? describe(resource, member, offset) : null);
                    Global.tempLog("expression-details", "result offset=" + offset + " htmlLength=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + (html == null ? null : html.length()) + " currentStamp=" + stamp(document)); //$NON-NLS-1$
                    if (html == null || stamp(document) != stamp || REQUESTS.get(input) != request)
                        return Status.OK_STATUS;
                    request.html = html;
                    if (!html.isEmpty() && !display.isDisposed())
                        display.asyncExec(() -> {
                            if (stamp(document) == stamp && REQUESTS.get(input) == request)
                                IrBslHoverHtml.refreshExpressionDetails(input);
                        });
                }
                catch (Throwable failure)
                {
                    Global.tempLogException("expression-details", "request failed", failure); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private static long stamp(IXtextDocument document)
    {
        return ((IDocumentExtension4)document).getModificationStamp();
    }

    /** Вызывается после остальных оформителей, поэтому фоновый результат ими не затирается. */
    static String append(Object input, String html)
    {
        Request request = REQUESTS.get(input);
        IXtextDocument document = request == null ? null : request.document.get();
        if (request == null || request.html.isEmpty()
            || document == null || stamp(document) != request.stamp
            || !java.util.Objects.equals(request.index, Global.invoke(input, "getIndex"))) //$NON-NLS-1$
            return html;
        if (html == null)
            html = IrBslHoverHtml.readHtml(input);
        if (html == null || html.contains(MARK))
            return html;
        String block = "<div " + MARK + ">" + request.html + "</div>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int end = html.toLowerCase(java.util.Locale.ROOT).lastIndexOf("</body>"); //$NON-NLS-1$
        return end < 0 ? html + block : html.substring(0, end) + block + html.substring(end);
    }

    private static String describe(XtextResource resource, EObject member, int offset)
    {
        if (resource == null || resource.getContents().isEmpty())
            return null;
        if (offset >= 0)
        {
            EObject object = BslNodeLookup.resolveContainedElementAt(resource, offset);
            for (EObject current = object; current != null; current = current.eContainer())
                if (current instanceof Expression)
                {
                    member = current;
                    break;
                }
        }
        List<? extends EObject> members = member instanceof ProposalElement proposal ? proposal.getList()
            : member == null ? List.of() : List.of(member);
        Set<String> indexes = new LinkedHashSet<>();
        Set<String> hints = new LinkedHashSet<>();
        for (EObject object : members)
        {
            int position = offset;
            if (object instanceof FantomVariableOccurrence occurrence)
            {
                position = occurrence.getOffset();
                object = occurrence.getVariable();
            }
            Method method = EcoreUtil2.getContainerOfType(object, Method.class);
            Environments environments = method == null ? Environments.ALL : method.environments();
            if (position < 0)
                position = start(object);
            // При наведении на имя функции узел имени — часть вызова; описываем его результат.
            if (object.eContainer() instanceof Invocation call && call.getMethodAccess() == object)
                object = call;
            Property property = object instanceof Property value ? value : property(object, resource);
            if (property != null)
            {
                String hint = metadataHint(property, resource);
                if (hint != null && !hint.isBlank())
                    hints.add(hint);
            }
            List<TypeItem> types = object instanceof Variable variable
                ? TypeSystemUtil.getVariableTypesAtOffset(variable, position, environments)
                : property != null ? new ArrayList<>(property.getTypes())
                    : object instanceof Expression
                        ? resource.getResourceServiceProvider().get(TypesComputer.class).computeTypes(object, environments)
                        : List.of();
            if (types != null && types.stream().anyMatch(type -> "ValueTable".equals(McoreUtil.getTypeName(type)))) //$NON-NLS-1$
                indexes.addAll(new Indexes(environments).collect(object, position));
            Global.tempLog("expression-details", "member=" + object.eClass().getName() + " offset=" + position //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " property=" + (property == null ? null : property.getName()) + " types=" //$NON-NLS-1$ //$NON-NLS-2$
                + (types == null ? null : types.stream().map(McoreUtil::getTypeName).toList())
                + " indexes=" + indexes + " hint=" + (hints.isEmpty() ? "empty" : "present")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        StringBuilder html = new StringBuilder();
        if (!indexes.isEmpty())
            attribute(html, "Индексы", String.join(" ", indexes.stream().map(value -> "[" + value + "]").toList())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (!hints.isEmpty())
            attribute(html, "Подсказка", String.join("\n", hints)); //$NON-NLS-1$ //$NON-NLS-2$
        return html.toString();
    }

    private static void attribute(StringBuilder html, String title, String text)
    {
        html.append("<div><b>") //$NON-NLS-1$
            .append(title).append("</b>: ") //$NON-NLS-1$
            .append(HTMLPrinter.convertToHTMLContent(text).replace("\n", "<br>")) //$NON-NLS-1$ //$NON-NLS-2$
            .append("</div>"); //$NON-NLS-1$
    }

    private static Property property(EObject object, XtextResource resource)
    {
        List<FeatureEntry> entries = object instanceof StaticFeatureAccess access ? access.getFeatureEntries()
            : object instanceof DynamicFeatureAccess access
                ? DynamicFeatureAccessComputer.resolveDynamicFeatureAccess(access,
                    resource.getResourceServiceProvider().get(DynamicFeatureAccessComputer.class), true) : List.of();
        for (FeatureEntry entry : entries)
            if (entry.getFeature() instanceof Property property)
                return property;
        return null;
    }

    private static String metadataHint(Property property, XtextResource resource)
    {
        if (!(property instanceof DerivedProperty derived) || derived.getSource() == null)
            return null;
        EObject source = EcoreUtil.resolve(derived.getSource(), resource);
        // Тот же источник DerivedProperty, что используется для перехода в метаданные.
        Object tooltip = Global.invoke(source, "getToolTip"); //$NON-NLS-1$
        if (!(tooltip instanceof EMap<?, ?> map) || map.isEmpty())
            return null;
        IV8ProjectManager manager = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
        var project = manager == null ? null : manager.getProject(resource.getURI());
        var configuration = project instanceof IConfigurationAware aware ? aware.getConfiguration() : null;
        var language = configuration == null ? null : configuration.getDefaultLanguage();
        Object text = language == null ? null : map.get(language.getLanguageCode());
        Global.tempLog("expression-details", "source=" + source.eClass().getName() + " language=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (language == null ? null : language.getLanguageCode()) + " found=" + (text instanceof String)); //$NON-NLS-1$
        return text instanceof String value ? value : null;
    }

    private static int start(EObject object)
    {
        var node = NodeModelUtils.findActualNodeFor(object);
        return node == null ? -1 : node.getOffset();
    }

    private static int end(EObject object)
    {
        var node = NodeModelUtils.findActualNodeFor(object);
        return node == null ? Integer.MAX_VALUE : node.getEndOffset();
    }

    /** Имена индексов вычисляются по AST, а получатели сравниваются по связанным переменным. */
    private static final class Indexes
    {
        private final Environments environments;
        private final Set<EObject> visiting = Collections.newSetFromMap(new IdentityHashMap<>());

        Indexes(Environments environments)
        {
            this.environments = environments;
        }

        Set<String> collect(EObject object, int position)
        {
            Set<String> result = new LinkedHashSet<>();
            if (object == null || !visiting.add(object))
                return result;
            try
            {
                if (object instanceof StaticFeatureAccess access)
                    return collect(variable(access), position);
                if (object instanceof Invocation invocation)
                {
                    List<FeatureEntry> entries = invocation.getMethodAccess() instanceof StaticFeatureAccess access
                        ? access.getFeatureEntries() : invocation.getMethodAccess() instanceof DynamicFeatureAccess access
                            ? access.getFeatureEntries() : List.of();
                    for (FeatureEntry entry : entries)
                        if (entry.getFeature() instanceof Function function)
                            for (ReturnStatement exit : EcoreUtil2.getAllContentsOfType(function, ReturnStatement.class))
                                result.addAll(collect(exit.getExpression(), start(exit)));
                    return result;
                }
                if (!(object instanceof Variable variable))
                    return result;
                Method method = EcoreUtil2.getContainerOfType(variable, Method.class);
                if (method == null)
                {
                    var module = EcoreUtil2.getContainerOfType(variable, com._1c.g5.v8.dt.bsl.model.Module.class);
                    if (module != null)
                        for (Method candidate : module.allMethods())
                            if (position >= start(candidate) && position <= end(candidate))
                            {
                                method = candidate;
                                break;
                            }
                }
                if (method == null)
                    return result;
                List<SimpleStatement> statements = EcoreUtil2.getAllContentsOfType(method, SimpleStatement.class);
                SimpleStatement assignment = null;
                for (SimpleStatement statement : statements)
                    if (end(statement) <= position && statement.getLeft() instanceof StaticFeatureAccess access
                        && variable(access) == variable && active(statement)
                        && (assignment == null || end(statement) > end(assignment)))
                        assignment = statement;
                int from = assignment == null ? -1 : end(assignment);
                if (assignment != null)
                    result.addAll(collect(assignment.getRight(), start(assignment)));
                List<Invocation> calls = new ArrayList<>(EcoreUtil2.getAllContentsOfType(method, Invocation.class));
                calls.sort(java.util.Comparator.comparingInt(BslExpressionDetails::end));
                for (Invocation call : calls)
                {
                    if (end(call) <= from || end(call) > position || !active(call)
                        || !(call.getMethodAccess() instanceof DynamicFeatureAccess action)
                        || !(action.getSource() instanceof DynamicFeatureAccess collection)
                        || !named(collection.getName(), "Индексы", "Indexes") //$NON-NLS-1$ //$NON-NLS-2$
                        || !(collection.getSource() instanceof StaticFeatureAccess receiver)
                        || variable(receiver) != variable)
                        continue;
                    String value = call.getParams().isEmpty() ? null
                        : string(call.getParams().get(0), statements, Collections.newSetFromMap(new IdentityHashMap<>()));
                    Global.tempLog("expression-details", "indexCall=" + action.getName() + " offset=" + start(call) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + " variable=" + variable.getName() + " value=" + value); //$NON-NLS-1$ //$NON-NLS-2$
                    if (named(action.getName(), "Добавить", "Add") && value != null) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        String normalized = normalize(value);
                        if (!normalized.isEmpty() && result.stream().noneMatch(normalized::equalsIgnoreCase))
                            result.add(normalized);
                    }
                    else if (unconditional(call, method))
                    {
                        if (named(action.getName(), "Очистить", "Clear")) //$NON-NLS-1$ //$NON-NLS-2$
                            result.clear();
                        else if (named(action.getName(), "Удалить", "Delete") && !call.getParams().isEmpty()) //$NON-NLS-1$ //$NON-NLS-2$
                        {
                            String removed = indexValue(call.getParams().get(0), variable, result, statements,
                                Collections.newSetFromMap(new IdentityHashMap<>()));
                            if (removed != null)
                                result.removeIf(removed::equalsIgnoreCase);
                        }
                    }
                }
                return result;
            }
            finally
            {
                visiting.remove(object);
            }
        }

        private boolean active(EObject object)
        {
            var owner = EcoreUtil2.getContainerOfType(object, com._1c.g5.v8.dt.mcore.Environmental.class);
            return owner == null || owner.environments().containsAny(environments);
        }

        /** Удалить принимает объект индекса: сохранённый результат Добавить или Индексы[номер]. */
        private String indexValue(Expression argument, Variable table, Set<String> indexes,
            List<SimpleStatement> statements, Set<EObject> seen)
        {
            if (argument == null || !seen.add(argument))
                return null;
            if (argument instanceof IndexAccess access && isIndexes(access.getSource(), table)
                && access.getIndex() instanceof NumberLiteral number)
            {
                try
                {
                    int index = Integer.parseInt(String.join("", number.getValue())); //$NON-NLS-1$
                    return index >= 0 && index < indexes.size() ? new ArrayList<>(indexes).get(index) : null;
                }
                catch (NumberFormatException ignored)
                {
                    return null;
                }
            }
            if (argument instanceof Invocation call && call.getMethodAccess() instanceof DynamicFeatureAccess action
                && named(action.getName(), "Добавить", "Add") && isIndexes(action.getSource(), table) //$NON-NLS-1$ //$NON-NLS-2$
                && !call.getParams().isEmpty())
            {
                String value = string(call.getParams().get(0), statements,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
                return value == null ? null : normalize(value);
            }
            if (!(argument instanceof StaticFeatureAccess access) || variable(access) == null)
                return null;
            SimpleStatement latest = null;
            for (SimpleStatement statement : statements)
                if (statement.getLeft() instanceof StaticFeatureAccess target && variable(target) == variable(access)
                    && end(statement) < start(argument) && active(statement)
                    && (latest == null || end(statement) > end(latest)))
                    latest = statement;
            return latest == null ? null : indexValue(latest.getRight(), table, indexes, statements, seen);
        }

        private static boolean isIndexes(Expression expression, Variable table)
        {
            return expression instanceof DynamicFeatureAccess access && named(access.getName(), "Индексы", "Indexes") //$NON-NLS-1$ //$NON-NLS-2$
                && access.getSource() instanceof StaticFeatureAccess receiver && variable(receiver) == table;
        }

        private static String normalize(String value)
        {
            return String.join(", ", java.util.Arrays.stream(value.split(",")) //$NON-NLS-1$ //$NON-NLS-2$
                .map(String::trim).filter(part -> !part.isEmpty()).toList());
        }

        private static Variable variable(StaticFeatureAccess access)
        {
            for (FeatureEntry entry : access.getFeatureEntries())
                if (entry.getFeature() instanceof Variable variable)
                    return variable;
            return null;
        }

        private String string(Expression expression, List<SimpleStatement> statements, Set<EObject> seen)
        {
            if (expression == null || !seen.add(expression))
                return null;
            if (expression instanceof StringLiteral literal)
                return new DefaultStringLiteralTextProvider(literal).getText();
            if (expression instanceof BinaryExpression binary && binary.getOperation() == BinaryOperation.PLUS)
            {
                String left = string(binary.getLeft(), statements, seen);
                String right = string(binary.getRight(), statements, seen);
                return left == null || right == null ? null : left + right;
            }
            if (!(expression instanceof StaticFeatureAccess access) || variable(access) == null)
                return null;
            SimpleStatement latest = null;
            for (SimpleStatement statement : statements)
                if (statement.getLeft() instanceof StaticFeatureAccess target && variable(target) == variable(access)
                    && end(statement) < start(expression) && active(statement)
                    && (latest == null || end(statement) > end(latest)))
                    latest = statement;
            return latest == null ? null : string(latest.getRight(), statements, seen);
        }

        private static boolean unconditional(EObject object, Method method)
        {
            for (EObject current = object.eContainer(); current != null && current != method; current = current.eContainer())
                if (current instanceof com._1c.g5.v8.dt.bsl.model.IfStatement
                    || current instanceof com._1c.g5.v8.dt.bsl.model.LoopStatement
                    || current instanceof com._1c.g5.v8.dt.bsl.model.TryExceptStatement
                    || current instanceof com._1c.g5.v8.dt.bsl.model.IfPreprocessor)
                    return false;
            return true;
        }

        private static boolean named(String value, String russian, String english)
        {
            return russian.equalsIgnoreCase(value) || english.equalsIgnoreCase(value);
        }
    }
}
