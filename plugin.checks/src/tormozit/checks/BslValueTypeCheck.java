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

import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
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
import com._1c.g5.v8.dt.bsl.model.SourceObjectLinkProvider;
import com._1c.g5.v8.dt.bsl.model.Statement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.UndefinedLiteral;
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
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.ComfortCheckIds;

/**
 * Замена BslJavaValidator.checkExpressionType из com._1c.g5.v8.dt.bsl 28.0.1.v202605050943.
 * Штатные ветки выполняются через TypesChecker; дополнены аргументы функций платформы
 * и присваивания свойствам платформы. В ожидаемых типах платформы Undefined означает значение,
 * а не неизвестный тип (тот же принцип, что в BslForEachCollectionCheck).
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
                + "Неопределено среди допустимых типов платформы означает конкретное значение."
                + "<br><br>Заменяет штатную bsl-legacy-check-expression-type, её включить нельзя.")
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
        new Session(resource, acceptor).check(module, monitor);
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
                for (var error : legacy.checkTypes(next, envs))
                    issue(error.message, error.source, error.feature,
                        error.errorCode == null ? WRONG_TYPE : error.errorCode, envs);
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

        private Environments environments(EObject object)
        {
            Environmental owner = EcoreUtil2.getContainerOfType(object, Environmental.class);
            return owner == null ? Environments.EMPTY
                : preferences.getLoadEnvs(object).intersect(owner.environments());
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
            List<List<TypeItem>> expected = new ArrayList<>();
            for (int i = 0; i < invocation.getParams().size(); i++)
                expected.add(new ArrayList<>());
            boolean platform = false;
            for (FeatureEntry entry : entries(access, envs))
            {
                if (!envs.containsAny(entry.getEnvironments()))
                    continue;
                // BSL-сигнатуры уже обрабатывает TypesChecker. Их Undefined может быть неизвестным типом.
                if (!(entry.getFeature() instanceof Method method) || method.eIsProxy()
                    || method instanceof SourceObjectLinkProvider)
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
                        expected.get(i).addAll(set.getParams().get(i).getType());
                }
                // Неверное число параметров проверяется отдельно; не угадываем сигнатуру.
                if (!matching)
                    return;
            }
            if (!platform)
                return;
            for (int i = 0; i < expected.size(); i++)
                checkValue(invocation.getParams().get(i), expected.get(i), envs, true);
        }

        private void checkAssignment(FeatureAccess access, Expression value, Environments envs)
        {
            List<TypeItem> expected = new ArrayList<>();
            for (FeatureEntry entry : entries(access, envs))
            {
                if (!envs.containsAny(entry.getEnvironments()))
                    continue;
                if (!(entry.getFeature() instanceof Property property) || property.eIsProxy()
                    || property instanceof BslContextDefProperty)
                    return;
                expected.addAll(property.getTypes());
            }
            checkValue(value, expected, envs, false);
        }

        private void checkValue(Expression value, List<TypeItem> expected, Environments envs, boolean argument)
        {
            if (value == null || value instanceof EmptyExpression || expected.isEmpty()
                || typeIssues.getOrDefault(value, Environments.EMPTY).containsAny(envs))
                return;
            List<TypeItem> resolvedExpected = resolve(expected, value);
            List<TypeItem> actual = resolve(types.computeTypes(value, envs), value);
            if (resolvedExpected == null || actual == null || actual.isEmpty())
                return;
            List<TypeItem> concreteActual = new ArrayList<>(actual);
            if (hasType(actual, "Undefined")) //$NON-NLS-1$
            {
                // Не меняем передачу явного Неопределено (в т.ч. значения по умолчанию).
                // У результата BSL оно может быть неизвестным типом, а у свойства платформы — только значением.
                if (value instanceof UndefinedLiteral
                    || !BslForEachCollectionCheck.isPlatformOnly(value, new HashSet<>())
                    || hasType(resolvedExpected, "Undefined")) //$NON-NLS-1$
                    return;
                concreteActual.removeIf(item -> "Undefined".equals(McoreUtil.getTypeName(item))); //$NON-NLS-1$
            }
            List<TypeItem> concreteExpected = new ArrayList<>();
            for (TypeItem item : resolvedExpected)
                if (!"Undefined".equals(McoreUtil.getTypeName(item))) //$NON-NLS-1$
                    concreteExpected.add(item);
            // EDT разрешает передавать любое значение строковому параметру (неявное представление).
            if (argument && hasType(concreteExpected, "String")) //$NON-NLS-1$
                return;
            if (hasType(concreteExpected, "Arbitrary")) //$NON-NLS-1$
                return;
            if (!concreteExpected.isEmpty() && !concreteActual.isEmpty()
                && BslUtil.intersectTypeItem(concreteExpected, concreteActual, value))
                return;
            if (hasType(actual, "Arbitrary")) //$NON-NLS-1$
                return;
            String message = IMessages.INSTANCE.no_intersection_between_expected_types__and_expression_types(
                BslUtil.createTypesLineStr(resolvedExpected, true), BslUtil.createTypesLineStr(actual, true));
            issue(message, value, null, WRONG_TYPE, envs);
        }

        /** Неразрешённая ссылка — неполное знание, а не доказанная несовместимость. */
        private static List<TypeItem> resolve(List<TypeItem> items, EObject context)
        {
            List<TypeItem> result = new ArrayList<>();
            for (TypeItem item : items)
            {
                if (!(EcoreUtil.resolve(item, context) instanceof TypeItem resolved) || resolved.eIsProxy())
                    return null;
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

