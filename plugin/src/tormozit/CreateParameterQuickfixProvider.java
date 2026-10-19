package tormozit;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.edit.IModificationContext;
import org.eclipse.xtext.ui.editor.model.edit.IssueModificationContext;
import org.eclipse.xtext.ui.editor.quickfix.Fix;
import org.eclipse.xtext.ui.editor.quickfix.IssueResolutionAcceptor;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.ui.quickfix.AbstractExternalQuickfixProvider;

/** Создание параметра по неопределённой переменной в теле метода (issue #846). */
public final class CreateParameterQuickfixProvider extends AbstractExternalQuickfixProvider
{
    @Fix("undefined-variable")
    public void createParameter(Issue issue, IssueResolutionAcceptor acceptor)
    {
        URI uri = issue.getUriToProblem();
        if (uri == null || uri.fragment() == null)
            return;
        try
        {
            // Тот же контекст документа, который Xtext использует для быстрых исправлений.
            Object factory = BslSyntaxAssist.injected(IssueModificationContext.Factory.class.getName());
            if (!(factory instanceof IssueModificationContext.Factory contextFactory))
                return;
            IModificationContext context = contextFactory.createModificationContext(issue);
            IXtextDocument document = context.getXtextDocument();
            if (document == null)
                return;
            String name = document.readOnly(resource -> {
                StaticFeatureAccess access = access(resource, issue);
                return insertion(access) == null ? null : access.getName();
            });
            if (name == null)
                return;
            String title = "Создать параметр «" + name + "»";
            acceptor.accept(issue, title,
                "Добавить параметр со Знач в конец списка параметров текущей процедуры или функции."
                    + Global.pluginSignForTooltip(),
                null, (IModificationContext modificationContext) -> {
                    new ExternalQuickfixModification<EObject>(issue,
                        EObject.class, element -> {
                            // Аннотация могла устареть, пока список исправлений был открыт.
                            StaticFeatureAccess current = access(element);
                            return current != null && name.equals(current.getName())
                                ? insertion(current) : null;
                        }).apply(modificationContext);
                }, -1); // Штатное создание переменной имеет приоритет 0 и идёт раньше.
        }
        catch (RuntimeException e)
        {
            // Документ или аннотация уже недоступны — исправление не предлагается.
        }
    }

    private static StaticFeatureAccess access(XtextResource resource, Issue issue)
    {
        EObject element = resource.getEObject(issue.getUriToProblem().fragment());
        return access(element);
    }

    private static StaticFeatureAccess access(EObject element)
    {
        // Ошибка указывает на само обращение либо на FeatureEntry внутри него.
        return EcoreUtil2.getContainerOfType(element, StaticFeatureAccess.class);
    }

    /** Смещения берутся из модели узлов: свёртки редактора на них не влияют. */
    private static InsertEdit insertion(StaticFeatureAccess access)
    {
        if (access == null || access.getName() == null || access.getName().isBlank()
            || access.getImplicitVariable() != null)
            return null;
        // Для undefined-variable EDT может вернуть обращение без единого FeatureEntry.
        boolean unresolved = access.getFeatureEntries().isEmpty()
            || access.getFeatureEntries().stream().anyMatch(entry ->
                entry.getFeature() == null || entry.getFeature().eIsProxy());
        if (!unresolved)
            return null;
        Method method = EcoreUtil2.getContainerOfType(access, Method.class);
        if (method == null)
            return null;
        for (FormalParam parameter : method.getFormalParams())
        {
            if (access.getName().equalsIgnoreCase(parameter.getName()))
                return null;
        }
        ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(method);
        ICompositeNode accessNode = NodeModelUtils.findActualNodeFor(access);
        if (methodNode == null || accessNode == null)
            return null;
        for (INode node : methodNode.getAsTreeIterable())
        {
            if (node.getSyntaxErrorMessage() != null)
                return null;
        }
        // Ищем скобки именно сигнатуры, не текст в директивах, строках и комментариях.
        ILeafNode open = null;
        ILeafNode close = null;
        for (ILeafNode leaf : methodNode.getLeafNodes())
        {
            if (leaf.isHidden() || !(leaf.getGrammarElement() instanceof Keyword keyword)
                || NodeModelUtils.findActualSemanticObjectFor(leaf) != method)
                continue;
            if ("(".equals(keyword.getValue()) && open == null)
                open = leaf;
            else if (")".equals(keyword.getValue()) && open != null)
            {
                close = leaf;
                break;
            }
        }
        if (open == null || close == null || accessNode.getOffset() <= close.getOffset())
            return null;
        if (method.getFormalParams().isEmpty())
            return new InsertEdit(open.getEndOffset(), "Знач " + access.getName());
        FormalParam last = method.getFormalParams().get(method.getFormalParams().size() - 1);
        ICompositeNode lastNode = NodeModelUtils.findActualNodeFor(last);
        if (lastNode == null || lastNode.getEndOffset() > close.getOffset())
            return null;
        return new InsertEdit(lastNode.getEndOffset(), ", Знач " + access.getName());
    }
}

