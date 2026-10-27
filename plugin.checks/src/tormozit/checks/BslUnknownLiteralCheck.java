package tormozit.checks;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;
import com._1c.g5.v8.dt.bsl.stringliteral.contenttypes.TypeUtil;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.BslLiteralValues;
import tormozit.ComfortCheckIds;

/** ПроверитьВыраженияУровня из ирКлсПолеТекстаПрограммы; список и полнота — общий анализатор. */
public final class BslUnknownLiteralCheck extends BasicCheck<Object>
{
    // Временный контрольный замер CPU: отключён весь анализ этой проверки.
    private static final boolean TEMPORARILY_DISABLED_FOR_CPU_COMPARISON = false;

    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.BSL_UNKNOWN_LITERAL;
    }

    @Override
    protected void configureCheck(CheckConfigurer configurer)
    {
        configurer.title("Неизвестный литерал (тяжелая)")
            .description("Проверяет литералы по полным спискам значений из кода и модели: колонки, ключи, "
                + "реквизиты, параметры форм и расширений. Неполные списки не проверяет."
                + "<br><br>Требует параметр «Включить расчет типов контента для строковых литералов» "
                + "(<a href=\"#\" onclick=\"comfortOpenBslPreferences();return false;\">"
                + "Параметры → V8 → Встроенный язык</a>). Анализ вызываемых методов может заметно "
                + "нагружать процессор при массовой проверке."
                + "<br><br>В отличие от штатной bsl-legacy-check-string-literal, использующей встроенные "
                + "списки EDT, проверяет также литералы вне вызовов, списки через запятую, пути через "
                + "точку и имена сортировки. Проверки дополняют друг друга.")
            .complexity(CheckComplexity.NORMAL).severity(IssueSeverity.MINOR).issueType(IssueType.WARNING)
            .parameter(OpenModulesOnly.PARAMETER, Boolean.class, "true", OpenModulesOnly.TITLE) //$NON-NLS-1$
            .module().checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (TEMPORARILY_DISABLED_FOR_CPU_COMPARISON)
        {
            return;
        }
        if (!TypeUtil.isStringLiteralContentTypesEnabled())
        {
            return;
        }
        if (!(object instanceof Module module) || !(module.eResource() instanceof XtextResource)
            || monitor.isCanceled())
        {
            return;
        }
        if (OpenModulesOnly.skip(module, parameters))
        {
            return;
        }
        // Один проход на модуль: узлы, текст и исходные методы общие для всех литералов модуля.
        BslLiteralValues.Pass pass = new BslLiteralValues.Pass();
        TreeIterator<EObject> all = module.eAllContents();
        while (all.hasNext() && !monitor.isCanceled() && TypeUtil.isStringLiteralContentTypesEnabled())
        {
            EObject next = all.next();
            if (!(next instanceof StringLiteral literal))
                continue;
            for (String value : BslLiteralValues.unknownLiteralValues(literal,
                () -> monitor.isCanceled() || !TypeUtil.isStringLiteralContentTypesEnabled(), pass))
            {
                acceptor.addIssue("Литерал «" + value + "» отсутствует в списке допустимых значений", literal);
            }
        }
    }

}
