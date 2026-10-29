package tormozit.checks;

import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.Module;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.BslQueryResultTypes;
import tormozit.ComfortCheckIds;

/** Проверка читает общий план запроса; собственного разбора и системы типов здесь нет. */
public final class BslQueryResultFieldCheck extends BasicCheck<Object>
{
    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.BSL_QUERY_RESULT_FIELD;
    }

    @Override
    protected void configureCheck(CheckConfigurer builder)
    {
        builder.title("Неизвестное поле результата запроса") //$NON-NLS-1$
            .description("Проверяет имена полей выборки по рассчитанному тексту запроса. " //$NON-NLS-1$
                + "Неоднозначный текст запроса и изменяемые строки" //$NON-NLS-1$
                + " таблиц значений не проверяются.") //$NON-NLS-1$
            .complexity(CheckComplexity.NORMAL)
            .severity(IssueSeverity.MAJOR)
            .issueType(IssueType.WARNING)
            .parameter(OpenModulesOnly.PARAMETER, Boolean.class, "true", "Только в открытых модулях") //$NON-NLS-1$
            .module()
            .checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (!(object instanceof Module module) || monitor.isCanceled())
            return;
        if (OpenModulesOnly.skip(module, parameters))
        {
            return;
        }
        for (var access : BslQueryResultTypes.unknownFields(module, monitor::isCanceled))
        {
            if (monitor.isCanceled())
                return;
            acceptor.addIssue("Поле «" + access.getName() + "» отсутствует в результате запроса", //$NON-NLS-1$ //$NON-NLS-2$
                access, BslPackage.Literals.FEATURE_ACCESS__NAME);
        }
    }
}
