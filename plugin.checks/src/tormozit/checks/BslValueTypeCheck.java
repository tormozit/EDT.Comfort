package tormozit.checks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.common.DefaultStringLiteralTextProvider;
import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.model.BslContextDefProperty;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.EmptyExpression;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.util.BslUtil;
import com._1c.g5.v8.dt.bsl.validation.IMessages;
import com._1c.g5.v8.dt.bsl.validation.TypesChecker;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Property;
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
import tormozit.ComfortCheckIds;

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
        configurer.title("Несовместимость типа значения")
            .description("Проверяет типы выражений, аргументов вызова и значений, присваиваемых свойствам платформы. "
                + "Единственный ожидаемый тип Неопределено допускает любое значение; "
                + "вместе с конкретными типами он разрешает также значение Неопределено. "
                + "Ожидаемый тип Строка допускает значение любого типа. "
                + "Значение Неопределено можно присваивать и передавать параметрам любого типа."
                + "<br><br>Динамически замещает штатную проверку bsl-legacy-check-expression-type.")
            .complexity(CheckComplexity.NORMAL).severity(IssueSeverity.MINOR).issueType(IssueType.WARNING)
            .module().checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (!(object instanceof Module module) || !(module.eResource() instanceof XtextResource resource)
            || monitor.isCanceled())
            return;
        boolean queryScopeOwner = BslQueryResultTypes.beginOpenModuleCalculation(module);
        try
        {
            new Session(resource, acceptor).check(module, monitor);
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
            TreeIterator<EObject> all = module.eAllContents();
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
            all = module.eAllContents();
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
            boolean platform = false;
            for (FeatureEntry entry : entries(access, envs))
            {
                if (!envs.containsAny(entry.getEnvironments()))
                    continue;
                // Все варианты родительского типа участвуют в одной проверке аргумента,
                // включая методы модулей: несовместимость одного варианта недостаточна.
                if (!(entry.getFeature() instanceof Method method) || method.eIsProxy())
                    return;
                platform = true;
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
                        unrestricted[i] |= parameter.isOut() || parameterTypes == null || parameterTypes.isEmpty()
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
            if (!platform)
                return;
            for (int i = 0; i < expected.size(); i++)
            {
                // EDT помечает значение УстановитьЗначение как out. Ограничение выбранного поля
                // проверяем до общего пропуска выходных параметров, сохраняя типы сигнатуры.
                if (i == 1 && checkDataLockValue(invocation, envs, expected.get(i)))
                    continue;
                if (!unrestricted[i] && !BslForEachCollectionCheck.isOutputArgument(invocation, i))
                {
                    checkValue(invocation.getParams().get(i), expected.get(i), envs, true, optional[i]);
                }
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
            if (!concreteExpected.isEmpty() && !concreteActual.isEmpty())
            {
                boolean compatible = compatibleTypes(concreteExpected, concreteActual, value);
                if (compatible)
                    return;
            }
            if (hasType(actual, "Arbitrary")) //$NON-NLS-1$
                return;
            String message = IMessages.INSTANCE.no_intersection_between_expected_types__and_expression_types(
                BslUtil.createTypesLineStr(resolvedExpected, true), BslUtil.createTypesLineStr(actual, true));
            issue(message, value, null, WRONG_TYPE, envs);
        }

        /**
         * BslUtil.intersectTypeItem через getCastingType допускает Date вместо Number/Boolean
         * и наоборот. Конкретные ссылки на разные объекты метаданных также нельзя
         * считать совместимыми по общему родителю (CatalogRef, AnyRef).
         * Составные типы сравниваем попарно: достаточно одной допустимой пары.
         */
        private static boolean compatibleTypes(List<TypeItem> expected, List<TypeItem> actual, EObject context)
        {
            boolean dateConversion = hasType(expected, "Date") //$NON-NLS-1$
                && (hasType(actual, "Number") || hasType(actual, "Boolean")) //$NON-NLS-1$ //$NON-NLS-2$
                || hasType(actual, "Date") //$NON-NLS-1$
                && (hasType(expected, "Number") || hasType(expected, "Boolean")); //$NON-NLS-1$ //$NON-NLS-2$
            boolean concreteReferences = expected.stream().anyMatch(Session::isConcreteReference)
                && actual.stream().anyMatch(Session::isConcreteReference);
            if (!dateConversion && !concreteReferences)
                return BslUtil.intersectTypeItem(expected, actual, context);
            Set<String> strict = Set.of("Date", "Number", "Boolean"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            for (TypeItem target : expected)
                for (TypeItem value : actual)
                {
                    String targetName = McoreUtil.getTypeName(target);
                    String valueName = McoreUtil.getTypeName(value);
                    if (isConcreteReference(target) && isConcreteReference(value))
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

        /** Категория ссылки с именем объекта метаданных: CatalogRef.Валюты, DocumentRef.Заказ и т.п. */
        private static boolean isConcreteReference(TypeItem type)
        {
            String name = McoreUtil.getTypeName(type);
            return name != null && name.indexOf('.') > 0
                && McoreUtil.getTypeCategory(type).endsWith("Ref"); //$NON-NLS-1$
        }

        /** Неразрешённая ссылка — неполное знание, а не доказанная несовместимость. */
        private static List<TypeItem> resolve(List<TypeItem> items, EObject context)
        {
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem item : items)
            {
                if (!(EcoreUtil.resolve(item, context) instanceof TypeItem resolved) || resolved.eIsProxy())
                    return null;
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
            if (!reported.add(new IssueKey(source, feature, code, message, envs)))
                return;
            if (WRONG_TYPE.equals(code))
                typeIssues.merge(source, envs, Environments::add);
            StringBuilder text = new StringBuilder(message).append(" ["); //$NON-NLS-1$
            for (Environment environment : envs.toArray())
            {
                if (text.charAt(text.length() - 1) != '[')
                    text.append(", "); //$NON-NLS-1$
                text.append(McoreUtil.getEnvironmentText(environment));
            }
            text.append(']');
            if (feature == null)
                acceptor.addIssue(text.toString(), source);
            else
                acceptor.addIssue(text.toString(), source, feature);
        }
    }

    private static record IssueKey(EObject source, EStructuralFeature feature, String code, String message,
        Environments environments) {}
}

