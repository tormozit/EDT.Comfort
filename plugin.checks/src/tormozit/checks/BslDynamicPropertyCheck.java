package tormozit.checks;

import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.BslLiteralValues;
import tormozit.ComfortCheckIds;

/** Проверка динамических свойств строк таблиц по общему анализу состава колонок. */
public final class BslDynamicPropertyCheck extends BasicCheck<Object>
{
    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.BSL_DYNAMIC_PROPERTY;
    }

    @Override
    protected void configureCheck(CheckConfigurer builder)
    {
        builder.title("Неизвестное динамическое свойство (тяжелая)") //$NON-NLS-1$
            .description("Проверяет обращения к свойствам строк таблиц значений по известному составу колонок. " //$NON-NLS-1$
                + "При обнаруженной неполноте состава колонок замечания не выдаются.") //$NON-NLS-1$
            .disable()
            .complexity(CheckComplexity.NORMAL)
            .severity(IssueSeverity.MINOR)
            .issueType(IssueType.WARNING)
            .parameter(OpenModulesOnly.PARAMETER, Boolean.class, "true", OpenModulesOnly.TITLE) //$NON-NLS-1$
            .module()
            .checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (!(object instanceof Module module) || monitor.isCanceled())
        {
            return;
        }
        boolean skip = OpenModulesOnly.skip(module, parameters);
        if (skip)
            return;
        try (var analysis = BslLiteralValues.validationPass(module, monitor::isCanceled, getCheckId()))
        {
            var problems = BslLiteralValues.unknownRowMembers(module, monitor::isCanceled, analysis.pass());
            for (var access : problems)
            {
                if (monitor.isCanceled())
                {
                    return;
                }
                acceptor.addIssue("Поле «" + access.getName() + "» отсутствует в составе колонок таблицы",
                    access, BslPackage.Literals.FEATURE_ACCESS__NAME);
            }
            analysis.complete();
        }
    }
}

