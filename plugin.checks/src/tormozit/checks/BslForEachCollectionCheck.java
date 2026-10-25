package tormozit.checks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.ForEachStatement;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.UndefinedLiteral;
import com._1c.g5.v8.dt.bsl.model.Variable;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.bsl.validation.IMessages;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.ComfortCheckIds;

/**
 * Копия {@code BslJavaValidator.checkForEachStatetementCollection} из бандла
 * {@code com._1c.g5.v8.dt.bsl 28.0.1.v202605050943} (в {@code 28.0.1.v202607090722} метод тот же) с одним отличием.
 * Штатная считает {@code Неопределено} среди типов выражения неизвестным типом и молчит. Но в методах
 * и свойствах платформы оно указывается только как конечное значение, которое обойти нельзя;
 * неизвестным (предварительным) оно бывает только в методах модулей. Поэтому здесь {@code Неопределено}
 * считается неизвестным, лишь если хоть одно происхождение выражения — не платформа.
 */
public final class BslForEachCollectionCheck extends BasicCheck<Object>
{
    /** Типы, которые штатная проверка тоже пропускает: произвольный и наборы записей (в модели не перечисляемые). */
    private static final Set<String> COLLECTION_NAMES = Set.of("Arbitrary", //$NON-NLS-1$
        "InformationRegisterRecordSet", "AccumulationRegisterRecordSet", //$NON-NLS-1$ //$NON-NLS-2$
        "CalculationRegisterRecordSet", "AccountingRegisterRecordSet"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String REPLACEMENT_NOTE = "<br><br>Заменяет штатную bsl-legacy-check-for-each-statetement-collection, её включить нельзя.";

    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.BSL_FOR_EACH_COLLECTION;
    }

    @Override
    protected void configureCheck(CheckConfigurer configurer)
    {
        configurer.title("Обход «Для каждого» не коллекции")
            .description("Выражение после «Из» в операторе «Для каждого» не является коллекцией. "
                + "Неопределено из методов и свойств платформы считается значением, а не неизвестным типом."
                + REPLACEMENT_NOTE)
            .complexity(CheckComplexity.NORMAL).severity(IssueSeverity.MINOR).issueType(IssueType.WARNING)
            .module().checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (!(object instanceof Module module) || !(module.eResource() instanceof XtextResource resource))
            return;
        TypesComputer types = resource.getResourceServiceProvider().get(TypesComputer.class);
        if (types == null)
            return;
        TreeIterator<EObject> all = module.eAllContents();
        while (all.hasNext() && !monitor.isCanceled())
        {
            if (all.next() instanceof ForEachStatement loop && loop.getCollection() != null
                && !isCollection(loop, types))
                acceptor.addIssue(IMessages.INSTANCE.possible_expression_is_not_collection(), loop.getCollection());
        }
    }

    private static boolean isCollection(ForEachStatement loop, TypesComputer types)
    {
        Expression collection = loop.getCollection();
        Environmental environmental = EcoreUtil2.getContainerOfType(loop, Environmental.class);
        List<TypeItem> computed = types.computeTypes(collection,
            environmental == null ? null : environmental.environments());
        if (computed.isEmpty())
            return true;
        for (TypeItem item : computed)
        {
            TypeItem resolved = (TypeItem)EcoreUtil.resolve(item, loop);
            String name = McoreUtil.getTypeName(resolved);
            if (resolved instanceof Type type && type.isIterable() || COLLECTION_NAMES.contains(name))
                return true;
            if ("Undefined".equals(name) && !isPlatformOnly(collection, new HashSet<>())) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    /**
     * Все происхождения значения — платформа: литерал {@code Неопределено}, методы и свойства
     * платформы, а у переменной метода — правые части всех её присваиваний. Иначе (метод модуля,
     * параметр, переменная модуля, свойство из комментария) {@code Неопределено} может значить «что угодно».
     */
    static boolean isPlatformOnly(Expression expression, Set<EObject> visiting)
    {
        if (expression == null || !visiting.add(expression))
            return false;
        if (expression instanceof UndefinedLiteral)
            return true;
        if (expression instanceof Invocation invocation)
            return isPlatformFeature(invocation.getMethodAccess());
        if (expression instanceof DynamicFeatureAccess access)
            return isPlatformFeature(access);
        if (!(expression instanceof StaticFeatureAccess access))
            return false;
        Variable variable = variable(access);
        if (variable == null)
            return isPlatformFeature(access);
        Method method = variable instanceof FormalParam ? null
            : EcoreUtil2.getContainerOfType(variable, Method.class);
        if (method == null)
            return false;
        boolean assigned = false;
        for (SimpleStatement assignment : EcoreUtil2.getAllContentsOfType(method, SimpleStatement.class))
        {
            if (!(assignment.getLeft() instanceof StaticFeatureAccess left) || !refersTo(left, variable))
                continue;
            if (!isPlatformOnly(assignment.getRight(), visiting))
                return false;
            assigned = true;
        }
        return assigned;
    }

    private static Variable variable(StaticFeatureAccess access)
    {
        if (access.getImplicitVariable() != null)
            return access.getImplicitVariable();
        for (FeatureEntry entry : access.getFeatureEntries())
            if (entry.getFeature() instanceof Variable variable)
                return variable;
        return null;
    }

    private static boolean refersTo(StaticFeatureAccess access, Variable variable)
    {
        if (access.getImplicitVariable() == variable)
            return true;
        for (FeatureEntry entry : access.getFeatureEntries())
            if (entry.getFeature() == variable)
                return true;
        return false;
    }

    /** Метод или свойство из модели платформы, а не из модуля и не созданное расчётом типов. */
    private static boolean isPlatformFeature(FeatureAccess access)
    {
        EList<FeatureEntry> entries = access instanceof StaticFeatureAccess staticAccess
            ? staticAccess.getFeatureEntries()
            : access instanceof DynamicFeatureAccess dynamicAccess ? dynamicAccess.getFeatureEntries() : null;
        if (entries == null || entries.isEmpty())
            return false;
        for (FeatureEntry entry : entries)
        {
            EObject feature = entry.getFeature();
            if (feature == null || feature.eIsProxy() || feature instanceof Method || feature instanceof Variable
                || !(feature instanceof com._1c.g5.v8.dt.mcore.Method) && !(feature instanceof Property))
                return false;
            Resource resource = feature.eResource();
            if (resource == null || resource instanceof XtextResource)
                return false;
        }
        return true;
    }
}
