package tormozit.checks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.TypesComputer;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.Environment;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.BslNumericIndexTypes;
import tormozit.ComfortCheckIds;

/**
 * Условие «НедопустимыйИндекс» из ИР, задача #892. Реестр 8.3.27 взят из выгрузки ИР;
 * для типов, появившихся с 8.5, применяется указанная в задаче формула по русскому HTML.
 * Общий Type.isIndexAccessible() не различает числовой индекс и ключ свойства.
 */
public final class BslNumericIndexCheck extends BasicCheck<Object>
{
    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.BSL_NUMERIC_INDEX;
    }

    @Override
    protected void configureCheck(CheckConfigurer configurer)
    {
        configurer.title("Обращение по числовому индексу")
            .description("Обращение по числовому индексу к значению, типы которого не разрешают такой доступ. "
                + "Проверка срабатывает, когда среди типов индекса есть Число и нет Строка. "
                + "Соответствия и недостаточно определённые типы не ограничивают доступ.")
            .complexity(CheckComplexity.NORMAL).severity(IssueSeverity.MAJOR).issueType(IssueType.WARNING)
            .module().checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (!(object instanceof Module module) || !(module.eResource() instanceof XtextResource resource))
            return;
        TypesComputer types = resource.getResourceServiceProvider().get(TypesComputer.class);
        IBslPreferences preferences = resource.getResourceServiceProvider().get(IBslPreferences.class);
        TreeIterator<EObject> all = module.eAllContents();
        while (all.hasNext() && !monitor.isCanceled())
        {
            if (!(all.next() instanceof IndexAccess access) || access.getSource() == null || access.getIndex() == null)
                continue;
            Environmental owner = EcoreUtil2.getContainerOfType(access, Environmental.class);
            if (owner == null)
                continue;
            Environments envs = preferences.getLoadEnvs(access).intersect(owner.environments());
            Environments invalid = Environments.EMPTY;
            for (Environment environment : envs.toArray())
                if (isInvalid(access, types, new Environments(environment)))
                    invalid = invalid.add(environment);
            if (!invalid.isEmpty())
            {
                StringBuilder message = new StringBuilder("Обращение по числовому индексу не поддерживается типами значения. [");
                for (Environment environment : invalid.toArray())
                {
                    if (message.charAt(message.length() - 1) != '[')
                        message.append(", "); //$NON-NLS-1$
                    message.append(McoreUtil.getEnvironmentText(environment));
                }
                acceptor.addIssue(message.append(']').toString(), access, BslPackage.Literals.INDEX_ACCESS__INDEX);
            }
        }
    }

    private static boolean isInvalid(IndexAccess access, TypesComputer types, Environments envs)
    {
        Expression source = access.getSource();
        List<TypeItem> parents = types.computeTypes(source, envs);
        Boolean allowed = null;
        Set<String> visited = new HashSet<>();
        for (TypeItem item : parents)
        {
            EObject resolved = EcoreUtil.resolve(item, access);
            if (!(resolved instanceof Type type) || type.eIsProxy() || type.getNameRu() == null)
                continue;
            String category = McoreUtil.getTypeCategoryRu(type);
            String name = McoreUtil.getTypeName(type);
            if ("Arbitrary".equals(name) || category.endsWith("Соответствие") || !visited.add(category)) //$NON-NLS-1$
                continue;
            if ("Undefined".equals(name) && !BslForEachCollectionCheck.isPlatformOnly(source, new HashSet<>())) //$NON-NLS-1$
                continue;
            Boolean current = BslNumericIndexTypes.allows(type, access.eResource());
            if (Boolean.TRUE.equals(current))
            {
                allowed = true;
                break;
            }
            if (Boolean.FALSE.equals(current))
                allowed = false;
        }
        List<TypeItem> indexTypes = Boolean.FALSE.equals(allowed)
            ? types.computeTypes(access.getIndex(), envs) : List.of();
        boolean number = false;
        boolean string = false;
        for (TypeItem item : indexTypes)
        {
            EObject resolved = EcoreUtil.resolve(item, access);
            if (resolved instanceof TypeItem type && !type.eIsProxy())
            {
                number |= "Number".equals(McoreUtil.getTypeName(type)); //$NON-NLS-1$
                string |= "String".equals(McoreUtil.getTypeName(type)); //$NON-NLS-1$
            }
        }
        boolean invalid = Boolean.FALSE.equals(allowed) && number && !string;
        return invalid;
    }

}
