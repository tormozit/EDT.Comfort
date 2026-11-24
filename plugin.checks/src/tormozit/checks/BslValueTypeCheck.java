package tormozit.checks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.model.BslContextDefMethod;
import com._1c.g5.v8.dt.bsl.model.BslContextDefProperty;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.EmptyExpression;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.util.BslUtil;
import com._1c.g5.v8.dt.bsl.validation.TypesChecker;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.BslQueryResultTypes;
import tormozit.BslLiteralValues;
import tormozit.BslMethodParameterTypes;
import tormozit.BslDocCommentTypeMerge;
import tormozit.ComfortCheckIds;
import tormozit.FormItemTypes;
import tormozit.Global;
import tormozit.PlatformTemplateTypes;
import tormozit.TypeDescriptionPresentationHook;

/**
 * Замена BslJavaValidator.checkExpressionType из com._1c.g5.v8.dt.bsl 28.0.1.v202605050943.
 * Штатные ветки выполняются через TypesChecker; дополнены аргументы функций платформы
 * и присваивания свойствам платформы. Единственный ожидаемый Undefined допускает любой тип;
 * в наборе с конкретными типами он означает допустимое значение Неопределено.
 */
public final class BslValueTypeCheck extends BasicCheck<Object>
{
    private static final String WRONG_TYPE = "wrong-type-expression"; //$NON-NLS-1$

    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.BSL_VALUE_TYPE;
    }

    @Override
    protected void configureCheck(CheckConfigurer configurer)
    {
        configurer.title("Несовместимость типа значения (тяжелая)")
            .description("Проверяет типы выражений, аргументов вызова и значений, присваиваемых свойствам платформы. "
                + "Единственный ожидаемый тип Неопределено допускает любое значение; "
                + "вместе с конкретными типами он разрешает также значение Неопределено. "
                + "Ожидаемый тип Строка допускает значение любого типа. "
                + "Строковое значение можно присваивать и передавать параметрам типа Число: платформа преобразует его автоматически. "
                + "Значение Неопределено можно присваивать и передавать параметрам любого типа. "
                + "Значение типа-расширения (например, РасширениеТаблицыФормыДляДинамическогоСписка) "
                + "совместимо с его базовым типом (ТаблицаФормы), значение типа-наследника "
                + "(ОбъектМетаданныхСправочник) — с типом-предком (ОбъектМетаданных). "
                + "Шаблонный тип (СправочникОбъектИмяСправочника) совместим со своим общим типом "
                + "(СправочникОбъект) в обоих направлениях; аналогично для других видов метаданных. "
                + "Значение ЭтотОбъект (ЭтаФорма) в модуле формы — тип-расширение этой формы "
                + "(РасширениеФормыКлиентскогоПриложенияДляСправочника для формы справочника и т. п.)."
                + "<br><br>Динамически замещает штатную проверку bsl-legacy-check-expression-type.")
            .complexity(CheckComplexity.NORMAL).severity(IssueSeverity.MINOR).issueType(IssueType.WARNING)
            .parameter(OpenModulesOnly.PARAMETER, Boolean.class, "true", OpenModulesOnly.TITLE) //$NON-NLS-1$
            .module().checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (!(object instanceof Module module) || !(module.eResource() instanceof XtextResource resource)
            || monitor.isCanceled())
            return;
        if (OpenModulesOnly.skip(module, parameters))
            return;
        boolean queryScopeOwner = BslQueryResultTypes.beginOpenModuleCalculation(module);
        try
        {
            Session session = new Session(resource, acceptor);
            session.check(module, monitor);
        }
        finally
        {
            BslQueryResultTypes.endOpenModuleCalculation(queryScopeOwner);
        }
    }

    private static final class Session
    {
        private final TypesChecker legacy;
        private final TypesComputer types;
        private final DynamicFeatureAccessComputer dynamic;
        private final IBslPreferences preferences;
        private final ResultAcceptor acceptor;
        private final Set<IssueKey> reported = new HashSet<>();
        private final Map<EObject, Environments> typeIssues = new IdentityHashMap<>();
        /**
         * Типы параметров из комментария вызываемого метода: на каждый вызов комментарий заново разбирался
         * и типы считались заново. Живёт одну проверку модуля; null — описания нет.
         */
        private final Map<com._1c.g5.v8.dt.bsl.model.FormalParam, List<TypeItem>> documentedParameters =
            new IdentityHashMap<>();
        /** Исходные BSL-методы экспортных методов модулей по URI: разрешение грузит модуль вызываемого метода. */
        private final Map<URI, com._1c.g5.v8.dt.bsl.model.Method> sourceMethods = new HashMap<>();

        Session(XtextResource resource, ResultAcceptor acceptor)
        {
            var services = resource.getResourceServiceProvider();
            legacy = services.get(TypesChecker.class);
            types = services.get(TypesComputer.class);
            dynamic = services.get(DynamicFeatureAccessComputer.class);
            preferences = services.get(IBslPreferences.class);
            this.acceptor = acceptor;
        }

        void check(Module module, IProgressMonitor monitor)
        {
            // Сохраняем арифметику, условия, возврат, передачу клиент/сервер и остальные ветки EDT.
            TreeIterator<EObject> all = tormozit.BslXtextDocumentHook.validationContents(module);
            while (all.hasNext() && !monitor.isCanceled())
            {
                EObject next = all.next();
                if (!(next instanceof Statement))
                    continue;
                Environments envs = environments(next);
                if (envs.isEmpty())
                    continue;
                for (Environment environment : envs.toArray())
                {
                    Environments current = new Environments(environment);
                    for (var error : legacy.checkTypes(next, current))
                    {
                        boolean typeError = error.errorCode == null || WRONG_TYPE.equals(error.errorCode);
                        // EDT проверяет каждый метод родительского типа отдельно. Аргументы
                        // перепроверяем ниже по объединению всех подходящих сигнатур.
                        boolean recheckArgument = typeError && error.source instanceof Expression value
                            && value.eContainer() instanceof Invocation call && call.getParams().contains(value);
                        boolean recheckAssignment = typeError && canRecheckAssignment(error.source, current);
                        boolean compatibleUndefined = typeError
                            && isUndefinedAssignment(error.source, current);
                        boolean stringConcatenation = typeError && isStringConcatenation(error.source, current);
                        if (!compatibleUndefined && !recheckArgument && !recheckAssignment && !stringConcatenation)
                            issue(error.message, error.source, error.feature,
                                error.errorCode == null ? WRONG_TYPE : error.errorCode, current);
                    }
                }
            }
            all = tormozit.BslXtextDocumentHook.validationContents(module);
            while (all.hasNext() && !monitor.isCanceled())
            {
                EObject next = all.next();
                if (!(next instanceof Invocation) && !(next instanceof SimpleStatement))
                    continue;
                // Не смешиваем сигнатуры и типы из разных контекстов выполнения.
                for (Environment environment : environments(next).toArray())
                {
                    Environments envs = new Environments(environment);
                    if (next instanceof Invocation invocation)
                        checkInvocation(invocation, envs);
                    else if (next instanceof SimpleStatement assignment
                        && assignment.getLeft() instanceof FeatureAccess access && assignment.getRight() != null)
                        checkAssignment(access, assignment.getRight(), envs);
                }
            }
        }

        /** При сложении со строкой другой операнд преобразуется в строковое представление. */
        private boolean isStringConcatenation(EObject source, Environments envs)
        {
            if (!(source instanceof Expression value)
                || !(value.eContainer() instanceof BinaryExpression binary)
                || binary.getOperation() != BinaryOperation.PLUS)
                return false;
            boolean compatible = canBeString(binary.getLeft(), envs) || canBeString(binary.getRight(), envs);
            return compatible;
        }

        /** EDT вычисляет тип сложения только по левой части и может потерять строку справа. */
        private boolean canBeString(Expression expression, Environments envs)
        {
            if (expression == null)
                return false;
            if (expression instanceof StringLiteral)
                return true;
            if (expression instanceof BinaryExpression binary && binary.getOperation() == BinaryOperation.PLUS
                && (canBeString(binary.getLeft(), envs) || canBeString(binary.getRight(), envs)))
                return true;
            List<TypeItem> computed = resolve(types.computeTypes(expression, envs), expression);
            return computed != null && hasType(computed, "String"); //$NON-NLS-1$
        }

        /** Неопределено допустимо как значение присваивания и фактического параметра. */
        private boolean isUndefinedAssignment(EObject source, Environments envs)
        {
            if (!(source instanceof Expression value))
                return false;
            EObject parent = value.eContainer();
            if (!(parent instanceof SimpleStatement assignment && assignment.getRight() == value)
                && !(parent instanceof Invocation invocation && invocation.getParams().contains(value)))
                return false;
            List<TypeItem> actual = resolve(types.computeTypes(value, envs), value);
            return actual != null && !actual.isEmpty()
                && actual.stream().allMatch(item -> "Undefined".equals(McoreUtil.getTypeName(item))); //$NON-NLS-1$
        }

        private Environments environments(EObject object)
        {
            Environmental owner = EcoreUtil2.getContainerOfType(object, Environmental.class);
            Environments result = owner == null ? Environments.EMPTY
                : preferences.getLoadEnvs(object).intersect(owner.environments());
            return result;
        }

        private List<FeatureEntry> entries(FeatureAccess access, Environments envs)
        {
            if (access instanceof StaticFeatureAccess stat)
                return stat.getFeatureEntries();
            if (access instanceof DynamicFeatureAccess dyn)
                return dynamic.getLastObject(dyn, envs, true);
            return List.of();
        }

        private List<TypeItem> documentedParameterTypes(com._1c.g5.v8.dt.bsl.model.Method method,
            com._1c.g5.v8.dt.bsl.model.FormalParam parameter)
        {
            if (documentedParameters.containsKey(parameter))
                return documentedParameters.get(parameter);
            List<TypeItem> declared = BslMethodParameterTypes.fromComment(method, parameter);
            if (declared != null)
                declared = declared.isEmpty() ? List.of() : new ArrayList<>(declared);
            documentedParameters.put(parameter, declared);
            return declared;
        }

        /**
         * Исходный BSL-метод экспортного метода модуля: {@code BslContextDefMethod} не хранит типы
         * параметров, а его {@code sourceUri} указывает на метод в модуле, где есть документирующий
         * комментарий. Разрешение грузит модуль, поэтому запоминается на одну проверку.
         */
        private com._1c.g5.v8.dt.bsl.model.Method sourceMethod(BslContextDefMethod exported, EObject context)
        {
            URI uri = exported.getSourceUri();
            if (uri == null)
                return null;
            Resource contextResource = context.eResource();
            if (uri.isRelative() && contextResource != null)
                uri = uri.resolve(contextResource.getURI());
            if (sourceMethods.containsKey(uri))
                return sourceMethods.get(uri);
            com._1c.g5.v8.dt.bsl.model.Method method = null;
            EObject proxy = EcoreFactory.eINSTANCE.createEObject();
            ((InternalEObject)proxy).eSetProxyURI(uri);
            try
            {
                if (EcoreUtil.resolve(proxy, context) instanceof com._1c.g5.v8.dt.bsl.model.Method resolved
                    && !resolved.eIsProxy())
                    method = resolved;
            }
            catch (RuntimeException error)
            {
                // Контекст модели недоступен: вызов остаётся непроверенным, как и раньше.
                Global.tempLogException("value-type-source-method", "resolve uri=" + uri, error); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Global.tempLog("value-type-source-method", "uri=" + uri //$NON-NLS-1$ //$NON-NLS-2$
                + " method=" + (method == null ? "null" : method.getName())); //$NON-NLS-1$ //$NON-NLS-2$
            sourceMethods.put(uri, method);
            return method;
        }

        private void checkInvocation(Invocation invocation, Environments envs)
        {
            FeatureAccess access = invocation.getMethodAccess();
            if (access == null)
                return;
            checkQueryParameter(invocation, envs);
            List<List<TypeItem>> expected = new ArrayList<>();
            for (int i = 0; i < invocation.getParams().size(); i++)
                expected.add(new ArrayList<>());
            // Параметр необязателен хотя бы в одной подходящей сигнатуре: Неопределено — его пропуск.
            boolean[] optional = new boolean[invocation.getParams().size()];
            boolean[] unrestricted = new boolean[invocation.getParams().size()];
            boolean methodFound = false;
            for (FeatureEntry entry : entries(access, envs))
            {
                if (!envs.containsAny(entry.getEnvironments()))
                    continue;
                EObject feature = entry.getFeature();
                // Экспортный метод модуля приходит методом его контекста и не хранит типы параметров:
                // берём их из документации исходного метода модуля.
                if (feature instanceof BslContextDefMethod exported)
                {
                    com._1c.g5.v8.dt.bsl.model.Method source = sourceMethod(exported, invocation);
                    if (source != null)
                        feature = source;
                }
                if (feature instanceof com._1c.g5.v8.dt.bsl.model.Method local)
                {
                    int count = invocation.getParams().size();
                    if (local.eIsProxy() || count > local.getFormalParams().size())
                        return;
                    for (int i = count; i < local.getFormalParams().size(); i++)
                        if (local.getFormalParams().get(i).getDefaultValue() == null)
                            return;
                    methodFound = true;
                    for (int i = 0; i < count; i++)
                    {
                        var parameter = local.getFormalParams().get(i);
                        List<TypeItem> declared = documentedParameterTypes(local, parameter);
                        List<TypeItem> resolved = declared != null ? resolve(declared, invocation) : null;
                        unrestricted[i] |= resolved == null || resolved.isEmpty()
                            || resolved.stream().allMatch(item -> "Undefined".equals(McoreUtil.getTypeName(item))); //$NON-NLS-1$
                        if (declared != null)
                            expected.get(i).addAll(declared);
                        optional[i] |= parameter.getDefaultValue() != null;
                    }
                    continue;
                }
                // Все варианты родительского типа участвуют в одной проверке аргумента,
                // включая методы модулей: несовместимость одного варианта недостаточна.
                if (!(feature instanceof Method method) || method.eIsProxy())
                    return;
                methodFound = true;
                boolean matching = false;
                for (ParamSet set : method.getParamSet())
                {
                    int count = invocation.getParams().size();
                    if (count < set.getMinParams() || set.getMaxParams() != -1 && count > set.getMaxParams())
                        continue;
                    matching = true;
                    for (int i = 0; i < Math.min(count, set.getParams().size()); i++)
                    {
                        var parameter = set.getParams().get(i);
                        List<TypeItem> parameterTypes = resolve(parameter.getType(), invocation);
                        // В модели платформы out=true у всех параметров, включая входные,
                        // поэтому направление не учитываем: иначе аргументы платформы
                        // не проверяются вовсе.
                        unrestricted[i] |= parameterTypes == null || parameterTypes.isEmpty()
                            || parameterTypes.stream()
                                .allMatch(item -> "Undefined".equals(McoreUtil.getTypeName(item))); //$NON-NLS-1$
                        expected.get(i).addAll(parameter.getType());
                        optional[i] |= i >= set.getMinParams();
                    }
                    for (int i = set.getParams().size(); i < count; i++)
                        unrestricted[i] = true;
                }
                // Неверное число параметров проверяется отдельно; не угадываем сигнатуру.
                if (!matching)
                    return;
            }
            if (!methodFound)
                return;
            for (int i = 0; i < expected.size(); i++)
            {
                // EDT помечает значение УстановитьЗначение как out: ограничение выбранного
                // поля проверяем отдельно, сохраняя типы сигнатуры.
                if (i == 1 && checkDataLockValue(invocation, envs, expected.get(i)))
                    continue;
                if (!unrestricted[i])
                    checkValue(invocation.getParams().get(i), expected.get(i), envs, true, optional[i]);
            }
        }

        /** Общая сигнатура УстановитьПараметр не ограничивает значение; ограничение задаёт схема запроса. */
        private void checkQueryParameter(Invocation invocation, Environments envs)
        {
            if (!(invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
                || !("УстановитьПараметр".equalsIgnoreCase(access.getName()) //$NON-NLS-1$
                    || "SetParameter".equalsIgnoreCase(access.getName())) //$NON-NLS-1$
                || invocation.getParams().size() != 2
                || !(invocation.getParams().get(0) instanceof StringLiteral literal))
                return;
            String name = new DefaultStringLiteralTextProvider(literal).getText();
            List<TypeItem> expected = BslQueryResultTypes.queryParameterTypesForCheck(invocation, name);
            Expression value = invocation.getParams().get(1);
            checkValue(value, expected, envs, true, false);
        }

        /** Ограничение значения задаёт поле объекта метаданных, сохранённого в типе элемента блокировки. */
        private boolean checkDataLockValue(Invocation invocation, Environments envs, List<TypeItem> parameterTypes)
        {
            if (!(invocation.getMethodAccess() instanceof DynamicFeatureAccess access)
                || !("УстановитьЗначение".equalsIgnoreCase(access.getName()) //$NON-NLS-1$
                    || "SetValue".equalsIgnoreCase(access.getName()))) //$NON-NLS-1$
                return false;
            List<TypeItem> sourceTypes = resolve(types.computeTypes(access.getSource(), envs), invocation);
            if (sourceTypes == null || !hasType(sourceTypes, "DataLockItem") //$NON-NLS-1$
                || invocation.getParams().size() != 2
                || !(invocation.getParams().get(0) instanceof StringLiteral literal)
                || !(invocation.eResource() instanceof XtextResource resource))
                return false;
            String name = new DefaultStringLiteralTextProvider(literal).getText();
            List<TypeItem> expected = BslLiteralValues.dataLockFieldTypes(resource, sourceTypes, name, parameterTypes);
            Expression value = invocation.getParams().get(1);
            checkValue(value, expected, envs, true, false);
            return true;
        }

        private void checkAssignment(FeatureAccess access, Expression value, Environments envs)
        {
            List<TypeItem> expected = assignmentTypes(access, envs);
            if (expected != null)
                checkValue(value, expected, envs, false, false);
        }

        private boolean canRecheckAssignment(EObject source, Environments envs)
        {
            if (!(source instanceof Expression value)
                || !(value.eContainer() instanceof SimpleStatement assignment)
                || assignment.getRight() != value
                || !(assignment.getLeft() instanceof FeatureAccess access))
                return false;
            List<TypeItem> expected = assignmentTypes(access, envs);
            return expected != null && !expected.isEmpty();
        }

        /** null — присваивание остаётся в ведении штатной проверки EDT. */
        private List<TypeItem> assignmentTypes(FeatureAccess access, Environments envs)
        {
            List<TypeItem> expected = new ArrayList<>();
            for (FeatureEntry entry : entries(access, envs))
            {
                if (!envs.containsAny(entry.getEnvironments()))
                    continue;
                if (!(entry.getFeature() instanceof Property property) || property.eIsProxy()
                    || property instanceof BslContextDefProperty)
                    return null;
                expected.addAll(property.getTypes());
            }
            return expected;
        }

        /** @param optional необязательный параметр метода платформы: Неопределено совместимо с ним */
        private void checkValue(Expression value, List<TypeItem> expected, Environments envs, boolean argument,
            boolean optional)
        {
            if (value == null || value instanceof EmptyExpression || expected.isEmpty()
                || typeIssues.getOrDefault(value, Environments.EMPTY).containsAny(envs))
                return;
            List<TypeItem> resolvedExpected = resolve(expected, value);
            // Голый Undefined в сигнатуре — неизвестный (произвольный) ожидаемый тип.
            // Повторения при объединении сигнатур не добавляют конкретного ограничения.
            if (resolvedExpected != null && resolvedExpected.stream()
                .allMatch(item -> "Undefined".equals(McoreUtil.getTypeName(item)))) //$NON-NLS-1$
                return;
            List<TypeItem> actual = resolve(types.computeTypes(value, envs), value);
            actual = formObjectTypes(value, actual);
            if (resolvedExpected == null || actual == null || actual.isEmpty())
                return;
            List<TypeItem> concreteActual = new ArrayList<>(actual);
            if (hasType(actual, "Undefined")) //$NON-NLS-1$
            {
                // В составном фактическом типе Неопределено не отменяет проверку конкретных типов.
                concreteActual.removeIf(item -> "Undefined".equals(McoreUtil.getTypeName(item))); //$NON-NLS-1$
                // Единственный фактический тип Неопределено совместим с любым приёмником.
                if (concreteActual.isEmpty())
                    return;
            }
            List<TypeItem> concreteExpected = new ArrayList<>();
            for (TypeItem item : resolvedExpected)
                if (!"Undefined".equals(McoreUtil.getTypeName(item))) //$NON-NLS-1$
                    concreteExpected.add(item);
            // Строковый параметр или свойство допускает любое значение (неявное представление).
            if (hasType(concreteExpected, "String")) //$NON-NLS-1$
                return;
            if (hasType(concreteExpected, "Arbitrary")) //$NON-NLS-1$
                return;
            // Строковое значение платформа автоматически преобразует в число.
            if (hasType(concreteExpected, "Number") && hasType(concreteActual, "String")) //$NON-NLS-1$ //$NON-NLS-2$
                return;
            if (!concreteExpected.isEmpty() && !concreteActual.isEmpty())
            {
                boolean compatible = compatibleTypes(concreteExpected, concreteActual, value);
                if (compatible)
                    return;
            }
            if (hasType(actual, "Arbitrary")) //$NON-NLS-1$
                return;
            String message = mismatchMessage(typesLine(resolvedExpected, value), typesLine(actual, value), envs);
            issue(message, value, null, WRONG_TYPE, envs, false);
        }

        /**
         * {@code ЭтотОбъект}/{@code ЭтаФорма} модуля формы: EDT вычисляет базовый тип формы
         * ({@code ClientApplicationForm}), а само значение относится к типу-расширению формы
         * ({@code РасширениеФормыКлиентскогоПриложенияДляСправочника}). Тип-расширение EDT держит
         * в {@code refContextDefs} копии базового типа. Без подмены документированный тип-расширение
         * даёт ложное несоответствие (#1070).
         */
        private List<TypeItem> formObjectTypes(Expression value, List<TypeItem> actual)
        {
            if (actual == null || actual.isEmpty() || !(value instanceof StaticFeatureAccess access)
                || !isFormObjectName(access.getName()))
                return actual;
            Module owner = EcoreUtil2.getContainerOfType(value, Module.class);
            if (owner == null || owner.getModuleType() != ModuleType.FORM_MODULE)
                return actual;
            List<TypeItem> specialized = new ArrayList<>(actual);
            for (int i = 0; i < specialized.size(); i++)
            {
                TypeItem extension = FormItemTypes.formExtension(specialized.get(i));
                if (extension != null)
                    specialized.set(i, extension);
            }
            return specialized;
        }

        private static boolean isFormObjectName(String name)
        {
            return name != null && ("ЭтотОбъект".equalsIgnoreCase(name) || "ЭтаФорма".equalsIgnoreCase(name) //$NON-NLS-1$ //$NON-NLS-2$
                || "ThisObject".equalsIgnoreCase(name) || "ThisForm".equalsIgnoreCase(name)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String mismatchMessage(String expected, String actual, Environments environments)
        {
            return "Нет соответствия между типами [" + environmentsText(environments) + "]:" //$NON-NLS-1$ //$NON-NLS-2$
                + "\n- ожидаемыми: " + expected //$NON-NLS-1$
                + "\n- полученными: " + actual; //$NON-NLS-1$
        }

        private static String environmentsText(Environments environments)
        {
            return java.util.Arrays.stream(environments.toArray()).map(McoreUtil::getEnvironmentText)
                .collect(java.util.stream.Collectors.joining(", ")); //$NON-NLS-1$
        }

        /** В тексте ошибки сохраняем типы элементов, по которым установлена несовместимость массивов. */
        private static String typesLine(List<TypeItem> types, EObject context)
        {
            return "<" + typesLine(types, context, java.util.Collections.newSetFromMap(new IdentityHashMap<>()), " | ") + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        private static String typesLine(List<TypeItem> types, EObject context, Set<TypeItem> path, String separator)
        {
            Set<String> names = new java.util.LinkedHashSet<>();
            // Порядок как в подсказках и полях ввода; сортируем до усечения списка (#965).
            int[] order = TypeDescriptionPresentationHook.displayOrder(types, context);
            List<TypeItem> sorted = types;
            if (order != null)
            {
                sorted = new ArrayList<>(types.size());
                for (int index : order)
                    sorted.add(types.get(index));
            }
            for (TypeItem item : sorted)
            {
                String name = BslUtil.createTypesLineStr(List.of(item), true);
                if (item instanceof Type array && "Array".equals(McoreUtil.getTypeName(item)) //$NON-NLS-1$
                    && array.getCollectionElementTypes() != null && path.add(item))
                {
                    try
                    {
                        List<TypeItem> elements = resolve(array.getCollectionElementTypes().allTypes(), context);
                        if (elements != null && !elements.isEmpty() && !hasType(elements, "Arbitrary")) //$NON-NLS-1$
                        {
                            String elementNames = typesLine(elements, context, path, ", "); //$NON-NLS-1$
                            name += " из " + elementNames; //$NON-NLS-1$
                        }
                    }
                    finally
                    {
                        path.remove(item);
                    }
                }
                names.add(name);
            }
            if (names.size() <= MAX_TYPES_IN_MESSAGE)
                return String.join(separator, names);
            return String.join(separator, new ArrayList<>(names).subList(0, MAX_TYPES_IN_MESSAGE)) + ", ..."; //$NON-NLS-1$
        }

        /** Больше типов в тексте ошибки не показываем (#965). */
        private static final int MAX_TYPES_IN_MESSAGE = 10;

        /**
         * BslUtil.intersectTypeItem через getCastingType допускает Date вместо Number/Boolean
         * и наоборот. Конкретные ссылки и объекты метаданных также нельзя
         * считать совместимыми по общему родителю (CatalogRef, DocumentObject, AnyRef).
         * Составные типы сравниваем попарно: достаточно одной допустимой пары.
         */
        private static boolean compatibleTypes(List<TypeItem> expected, List<TypeItem> actual, EObject context)
        {
            return compatibleTypes(expected, actual, context, new IdentityHashMap<>());
        }

        private static boolean compatibleTypes(List<TypeItem> expected, List<TypeItem> actual, EObject context,
            Map<TypeItem, Set<TypeItem>> comparing)
        {
            for (TypeItem target : expected)
                for (TypeItem value : actual)
                    if (PlatformTemplateTypes.isTemplateOf(value, target, context)
                        || PlatformTemplateTypes.isTemplateOf(target, value, context)
                        || FormItemTypes.isExtensionOfBase(value, target) || descends(value, target, context))
                        return true;
            boolean dateConversion = hasType(expected, "Date") //$NON-NLS-1$
                && (hasType(actual, "Number") || hasType(actual, "Boolean")) //$NON-NLS-1$ //$NON-NLS-2$
                || hasType(actual, "Date") //$NON-NLS-1$
                && (hasType(expected, "Number") || hasType(expected, "Boolean")); //$NON-NLS-1$ //$NON-NLS-2$
            boolean concreteMetadataValues = expected.stream().anyMatch(Session::isConcreteMetadataValue)
                && actual.stream().anyMatch(Session::isConcreteMetadataValue);
            boolean arrays = hasType(expected, "Array") && hasType(actual, "Array"); //$NON-NLS-1$ //$NON-NLS-2$
            if (!dateConversion && !concreteMetadataValues && !arrays)
                return BslUtil.intersectTypeItem(expected, actual, context);
            Set<String> strict = Set.of("Date", "Number", "Boolean"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            for (TypeItem target : expected)
                for (TypeItem value : actual)
                {
                    String targetName = McoreUtil.getTypeName(target);
                    String valueName = McoreUtil.getTypeName(value);
                    if ("Array".equals(targetName) && "Array".equals(valueName) //$NON-NLS-1$ //$NON-NLS-2$
                        && target instanceof Type targetArray && value instanceof Type valueArray)
                    {
                        if (compatibleArrayElements(targetArray, valueArray, context, comparing))
                            return true;
                        continue;
                    }
                    if (isConcreteMetadataValue(target) && isConcreteMetadataValue(value))
                    {
                        if (targetName.equals(valueName))
                            return true;
                        continue;
                    }
                    if (targetName != null && valueName != null && strict.contains(targetName)
                        && strict.contains(valueName) && !targetName.equals(valueName)
                        && ("Date".equals(targetName) || "Date".equals(valueName))) //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    if (BslUtil.intersectTypeItem(List.of(target), List.of(value), context))
                        return true;
                }
            return false;
        }

        /** Наследник по цепочке getParentType: ОбъектМетаданныхСправочник передаётся туда, где ждут ОбъектМетаданных. */
        private static boolean descends(TypeItem value, TypeItem target, EObject context)
        {
            String targetName = McoreUtil.getTypeName(target);
            if (targetName == null || !(value instanceof Type type))
                return false;
            TypeItem current = type.getParentType();
            for (int depth = 0; current != null && depth < 32; depth++)
            {
                if (!(EcoreUtil.resolve(current, context) instanceof Type parent) || parent.eIsProxy())
                    return false;
                if (targetName.equals(McoreUtil.getTypeName(parent)))
                    return true;
                current = parent.getParentType();
            }
            return false;
        }

        /** Неизвестный состав массива не доказывает ошибку; известные элементы сравниваются как значения. */
        private static boolean compatibleArrayElements(Type expected, Type actual, EObject context,
            Map<TypeItem, Set<TypeItem>> comparing)
        {
            if (expected.getCollectionElementTypes() == null || actual.getCollectionElementTypes() == null)
                return true;
            List<TypeItem> targets = resolve(expected.getCollectionElementTypes().allTypes(), context);
            List<TypeItem> values = resolve(actual.getCollectionElementTypes().allTypes(), context);
            if (targets == null || values == null || targets.isEmpty() || values.isEmpty())
                return true;
            if (targets.stream().allMatch(type -> "Undefined".equals(McoreUtil.getTypeName(type)))) //$NON-NLS-1$
                return true;
            values.removeIf(type -> "Undefined".equals(McoreUtil.getTypeName(type))); //$NON-NLS-1$
            targets.removeIf(type -> "Undefined".equals(McoreUtil.getTypeName(type))); //$NON-NLS-1$
            if (values.isEmpty() || hasType(targets, "String") || hasType(targets, "Arbitrary") //$NON-NLS-1$ //$NON-NLS-2$
                || hasType(values, "Arbitrary")) //$NON-NLS-1$
                return true;
            Set<TypeItem> paired = comparing.computeIfAbsent(expected,
                key -> java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            // Повтор той же пары на текущем пути — циклические типы коллекций.
            if (!paired.add(actual))
                return true;
            try
            {
                boolean compatible = compatibleTypes(targets, values, context, comparing);
                return compatible;
            }
            finally
            {
                paired.remove(actual);
            }
        }

        /** Ссылка или объект с именем метаданных: CatalogRef.Валюты, DocumentObject.Заказ и т.п. */
        private static boolean isConcreteMetadataValue(TypeItem type)
        {
            String name = McoreUtil.getTypeName(type);
            return name != null && name.indexOf('.') > 0
                && (McoreUtil.getTypeCategory(type).endsWith("Ref") //$NON-NLS-1$
                    || McoreUtil.getTypeCategory(type).endsWith("Object")); //$NON-NLS-1$
        }

        /** Неразрешённая ссылка — неполное знание, а не доказанная несовместимость. */
        private static List<TypeItem> resolve(List<TypeItem> items, EObject context)
        {
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem item : items)
            {
                if (!(EcoreUtil.resolve(item, context) instanceof TypeItem resolved) || resolved.eIsProxy())
                    return null;
                List<TypeItem> alternatives = BslDocCommentTypeMerge.alternatives(resolved);
                if (alternatives.size() != 1 || alternatives.get(0) != resolved)
                {
                    List<TypeItem> expanded = resolve(alternatives, context);
                    if (expanded == null)
                        return null;
                    result.addAll(expanded);
                    continue;
                }
                // У порождённого TypeSet getTypes() не отражает состав в контексте проекта.
                // Именно этот геттер читает BslUtil.intersectTypeItem, поэтому раскрываем заранее.
                if (resolved instanceof TypeSet
                    && Set.of("DefinedType", "Characteristic").contains(McoreUtil.getTypeCategory(resolved))) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    List<TypeItem> expanded = McoreUtil.flatTypes(List.of(resolved), context);
                    for (TypeItem member : expanded)
                    {
                        if (!(EcoreUtil.resolve(member, context) instanceof TypeItem concrete) || concrete.eIsProxy())
                            return null;
                        result.add(concrete);
                    }
                }
                else
                    result.add(resolved);
            }
            return result;
        }

        private static boolean hasType(List<TypeItem> items, String name)
        {
            for (TypeItem item : items)
                if (name.equals(McoreUtil.getTypeName(item)))
                    return true;
            return false;
        }

        private void issue(String message, EObject source, EStructuralFeature feature, String code,
            Environments envs)
        {
            issue(message, source, feature, code, envs, true);
        }

        private void issue(String message, EObject source, EStructuralFeature feature, String code,
            Environments envs, boolean appendEnvironments)
        {
            if (!reported.add(new IssueKey(source, feature, code, message, envs)))
                return;
            if (WRONG_TYPE.equals(code))
                typeIssues.merge(source, envs, Environments::add);
            String text = appendEnvironments ? message + " [" + environmentsText(envs) + "]" : message; //$NON-NLS-1$ //$NON-NLS-2$
            if (feature == null)
                acceptor.addIssue(text, source);
            else
                acceptor.addIssue(text, source, feature);
        }
    }

    private static record IssueKey(EObject source, EStructuralFeature feature, String code, String message,
        Environments environments) {}
}

