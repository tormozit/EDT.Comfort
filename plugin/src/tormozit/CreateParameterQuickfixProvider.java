package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
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
import org.eclipse.xtext.ui.editor.validation.XtextAnnotation;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.bsl.model.DeclareStatement;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.ui.quickfix.AbstractExternalQuickfixProvider;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Добавление параметров в сигнатуру метода: по неопределённой переменной в теле метода (issue #846)
 * и по лишним фактическим параметрам вызова метода того же модуля (issue #861).
 */
public final class CreateParameterQuickfixProvider extends AbstractExternalQuickfixProvider
{
    /**
     * «Слишком много параметров» выдаёт проверка «Комфорта», заменяющая штатную: код аннотации короткий SU…,
     * штатный {@code @Fix} по коду её не находит. Та же проверка выдаёт и другие проблемы параметров вызова —
     * отбор по модели.
     */
    private static final String MANY_PARAMETERS_CHECK_ID = ComfortCheckIds.STATIC_ACCESS_PARAMETERS;
    /** Код той же проблемы в штатном валидаторе — на случай, когда реестр не подменил его коротким. */
    private static final String MANY_PARAMETERS_CODE = "many-actual-parameters"; //$NON-NLS-1$

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
                StaticFeatureAccess access = access(resource.getEObject(uri.fragment()));
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

    /** «Добавить параметр» для аннотации «Слишком много параметров» в подсказке редактора модуля. */
    static ICompletionProposal addParameterProposal(XtextAnnotation annotation, Image icon)
    {
        Issue issue = annotation.getIssue();
        IXtextDocument document = annotation.getDocument();
        if (issue == null || document == null || issue.getOffset() == null || issue.getCode() == null)
            return null;
        int offset = issue.getOffset();
        String code = issue.getCode();
        try
        {
            List<String> names = document.readOnly(resource -> {
                if (!isManyParametersCheck(resource, code))
                    return null;
                ILeafNode leaf = NodeModelUtils.findLeafNodeAtOffset(resource.getParseResult().getRootNode(), offset);
                Invocation invocation = leaf == null ? null
                    : invocation(NodeModelUtils.findActualSemanticObjectFor(leaf));
                return addedParameterNames(invocation);
            });
            return names == null ? null : new AddParameterProposal(document, issue, names, icon);
        }
        catch (RuntimeException e)
        {
            // Документ или аннотация уже недоступны — исправление не предлагается.
            return null;
        }
    }

    private static boolean isManyParametersCheck(XtextResource resource, String code)
    {
        if (MANY_PARAMETERS_CODE.equals(code))
            return true;
        URI uri = resource.getURI();
        if (uri == null || !uri.isPlatformResource())
            return false;
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
            .getFile(new Path(uri.toPlatformString(true))).getProject();
        ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
        CheckUid uid = repository == null ? null : repository.getUidForShortUid(code, project);
        return uid != null && MANY_PARAMETERS_CHECK_ID.equals(uid.getCheckId());
    }

    private static StaticFeatureAccess access(EObject element)
    {
        // Ошибка указывает на само обращение либо на FeatureEntry внутри него.
        return EcoreUtil2.getContainerOfType(element, StaticFeatureAccess.class);
    }

    /** Вызов по элементу проблемы: сам вызов (так его отдаёт применение) или обращение к методу — имя слева от скобок. */
    private static Invocation invocation(EObject element)
    {
        if (element instanceof Invocation invocation)
            return invocation;
        StaticFeatureAccess access = access(element);
        return access != null && access.eContainer() instanceof Invocation invocation
            && invocation.getMethodAccess() == access ? invocation : null;
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
        ICompositeNode accessNode = NodeModelUtils.findActualNodeFor(access);
        ILeafNode[] parens = signatureParens(method);
        if (accessNode == null || parens == null || accessNode.getOffset() <= parens[1].getOffset())
            return null;
        return appendParameters(method, List.of(access.getName()));
    }

    /** Вызываемый метод, только если он из того же модуля: правка вносится в документ вызова. */
    private static Method target(Invocation invocation)
    {
        if (invocation == null || !(invocation.getMethodAccess() instanceof StaticFeatureAccess access))
            return null;
        Method target = null;
        for (FeatureEntry entry : access.getFeatureEntries())
        {
            if (!(entry.getFeature() instanceof Method method) || method.eIsProxy()
                || method.eResource() != invocation.eResource())
                continue;
            if (target != null && target != method)
                return null;
            target = method;
        }
        return target;
    }

    /**
     * Имена параметров, которых не хватает вызываемому методу, — имена переменных из лишних фактических
     * параметров; совпадения с параметрами и переменными метода получают числовой суффикс. Если хоть один
     * лишний параметр не чистое имя переменной, исправление не предлагается.
     */
    private static List<String> addedParameterNames(Invocation invocation)
    {
        Method method = target(invocation);
        if (method == null || signatureParens(method) == null)
            return null;
        int formalCount = method.getFormalParams().size();
        List<Expression> actual = invocation.getParams();
        if (actual.size() <= formalCount)
            return null;
        List<String> taken = new ArrayList<>();
        method.getFormalParams().forEach(parameter -> taken.add(parameter.getName()));
        for (DeclareStatement declare : method.getDeclareStatements())
            declare.getVariables().forEach(variable -> taken.add(variable.getName()));
        method.getImplicitVariables().forEach(variable -> taken.add(variable.getName()));
        List<String> names = new ArrayList<>();
        for (int i = formalCount; i < actual.size(); i++)
        {
            if (!(actual.get(i) instanceof StaticFeatureAccess access) || access.getName() == null
                || access.getName().isBlank())
                return null;
            String base = access.getName();
            String name = base;
            for (int n = 2; containsIgnoreCase(taken, name); n++)
                name = base + n;
            taken.add(name);
            names.add(name);
        }
        return names;
    }

    private static boolean containsIgnoreCase(List<String> names, String name)
    {
        return names.stream().anyMatch(name::equalsIgnoreCase);
    }

    private static InsertEdit appendParameters(Method method, List<String> names)
    {
        ILeafNode[] parens = method == null ? null : signatureParens(method);
        if (parens == null)
            return null;
        String text = String.join(", ", names.stream().map(name -> "Знач " + name).toList());
        if (method.getFormalParams().isEmpty())
            return new InsertEdit(parens[0].getEndOffset(), text);
        FormalParam last = method.getFormalParams().get(method.getFormalParams().size() - 1);
        ICompositeNode lastNode = NodeModelUtils.findActualNodeFor(last);
        if (lastNode == null || lastNode.getEndOffset() > parens[1].getOffset())
            return null;
        return new InsertEdit(lastNode.getEndOffset(), ", " + text);
    }

    /** Открывающая и закрывающая скобки сигнатуры; null при синтаксической ошибке в методе. */
    private static ILeafNode[] signatureParens(Method method)
    {
        ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(method);
        if (methodNode == null)
            return null;
        for (INode node : methodNode.getAsTreeIterable())
        {
            if (node.getSyntaxErrorMessage() != null)
                return null;
        }
        // Ищем скобки именно сигнатуры, не текст в директивах, строках и комментариях.
        ILeafNode open = null;
        for (ILeafNode leaf : methodNode.getLeafNodes())
        {
            if (leaf.isHidden() || !(leaf.getGrammarElement() instanceof Keyword keyword)
                || NodeModelUtils.findActualSemanticObjectFor(leaf) != method)
                continue;
            if ("(".equals(keyword.getValue()) && open == null)
                open = leaf;
            else if (")".equals(keyword.getValue()) && open != null)
                return new ILeafNode[] { open, leaf };
        }
        return null;
    }

    private static final class AddParameterProposal implements ICompletionProposal
    {
        private final IXtextDocument document;
        private final Issue issue;
        private final List<String> names;
        private final Image icon;

        AddParameterProposal(IXtextDocument document, Issue issue, List<String> names, Image icon)
        {
            this.document = document;
            this.issue = issue;
            this.names = names;
            this.icon = icon;
        }

        @Override
        public void apply(IDocument target)
        {
            if (target != document)
                return;
            try
            {
                // Штатное применение с поддержкой отмены, как ModuleAccessibilityAtClientFix.LiveProposal.
                new ExternalQuickfixModification<EObject>(issue, EObject.class, element -> {
                    // Вызов или сигнатура могли измениться, пока список исправлений был открыт.
                    Invocation current = invocation(element);
                    return names.equals(addedParameterNames(current))
                        ? appendParameters(target(current), names) : null;
                }).apply(new IModificationContext() {
                    @Override
                    public IXtextDocument getXtextDocument()
                    {
                        return document;
                    }

                    @Override
                    public IXtextDocument getXtextDocument(URI uri)
                    {
                        return document;
                    }
                });
            }
            catch (Exception e)
            {
                // Исправление не применено — документ остался прежним.
            }
        }

        @Override
        public String getDisplayString()
        {
            return (names.size() == 1 ? "Добавить параметр " : "Добавить параметры ")
                + String.join(", ", names.stream().map(name -> "«" + name + "»").toList());
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return "Добавить недостающие параметры со Знач в конец списка параметров вызываемой "
                + "процедуры или функции этого модуля." + Global.pluginSignForTooltip();
        }

        @Override
        public Image getImage()
        {
            return icon;
        }

        @Override
        public Point getSelection(IDocument target)
        {
            return null;
        }

        @Override
        public IContextInformation getContextInformation()
        {
            return null;
        }
    }
}
