package tormozit.checks;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.GotoStatement;
import com._1c.g5.v8.dt.bsl.model.Label;
import com._1c.g5.v8.dt.bsl.model.LabeledStatement;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.validation.IMessages;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

import tormozit.ComfortCheckIds;

/**
 * Проверка «Метка после «Перейти» не объявлена» (issue #1075).
 *
 * <p>Штатный {@code BslJavaValidator.checkGotoStatement} из {@code com._1c.g5.v8.dt.bsl 28.1.0.v202610211527}
 * молчит, когда ссылка на метку не связалась: он сразу выходит при {@code label.eContainer() == null}.
 * Здесь разбирается именно такой случай — неразрешённая cross-reference {@code GotoStatement.label}.
 * Метка, найденная, но расположенная в другой области видимости, остаётся за штатной проверкой
 * {@code wrong-goto-label}, чтобы не выдавать два замечания на одно место.
 */
public final class BslGotoLabelCheck extends BasicCheck<Object>
{
    private static final String TITLE = "Метка после «Перейти» не объявлена"; //$NON-NLS-1$

    private static final String DESCRIPTION =
        "Оператор «Перейти» ссылается на метку, которой нет в этом модуле."; //$NON-NLS-1$

    private static final String MESSAGE = "Метка после «Перейти» не объявлена"; //$NON-NLS-1$

    @Override
    public String getCheckId()
    {
        return ComfortCheckIds.BSL_GOTO_LABEL;
    }

    @Override
    protected void configureCheck(CheckConfigurer configurer)
    {
        configurer.title(TITLE)
            .description(DESCRIPTION)
            .complexity(CheckComplexity.NORMAL)
            .severity(IssueSeverity.CRITICAL)
            .issueType(IssueType.ERROR)
            .module()
            .checkedObjectType(BslPackage.Literals.MODULE);
    }

    @Override
    protected void check(Object object, ResultAcceptor acceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        if (!(object instanceof Module module) || !(module.eResource() instanceof XtextResource))
            return;
        for (GotoStatement goTo : EcoreUtil2.getAllContentsOfType(module, GotoStatement.class))
        {
            if (monitor.isCanceled())
                return;
            Label label = goTo.getLabel();
            if (label == null)
                continue;
            EObject resolved = label.eIsProxy() ? EcoreUtil.resolve(label, goTo) : label;
            if (!resolved.eIsProxy() && resolved.eContainer() instanceof LabeledStatement)
                continue;
            String name = labelName(goTo);
            String message = name == null ? MESSAGE : IMessages.INSTANCE.wrong_goto_to_label__0(name);
            acceptor.addIssue(message, goTo, BslPackage.Literals.GOTO_STATEMENT__LABEL);
        }
    }

    /** Текст метки берётся из модели узлов: у неразрешённой cross-reference он недоступен через eGet. */
    private static String labelName(GotoStatement goTo)
    {
        List<INode> nodes = NodeModelUtils.findNodesForFeature(goTo, BslPackage.Literals.GOTO_STATEMENT__LABEL);
        for (INode node : nodes)
        {
            String text = node.getText();
            if (text == null || text.isBlank())
                continue;
            return text.charAt(0) == '~' ? text.substring(1) : text;
        }
        return null;
    }
}
