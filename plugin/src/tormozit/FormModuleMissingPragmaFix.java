package tormozit;

import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.edit.IModificationContext;
import org.eclipse.xtext.ui.editor.validation.XtextAnnotation;
import org.eclipse.xtext.validation.Issue;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.model.DeclareStatement;
import com._1c.g5.v8.dt.bsl.model.ExplicitVariable;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.ui.quickfix.AbstractExternalQuickfixProvider;
import com.e1c.g5.v8.dt.check.qfix.FixDescriptor;
import com.e1c.g5.v8.dt.check.qfix.FixVariantDescriptor;
import com.e1c.g5.v8.dt.check.qfix.IFix;
import com.e1c.g5.v8.dt.check.qfix.IFixChange;
import com.e1c.g5.v8.dt.check.qfix.IFixChangeProcessor;
import com.e1c.g5.v8.dt.check.qfix.IFixContext;
import com.e1c.g5.v8.dt.check.qfix.IFixSession;
import com.e1c.g5.v8.dt.check.qfix.IFixVariant;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/** Добавление серверной директивы для проблемы form-module-missing-pragma. */
public final class FormModuleMissingPragmaFix extends AbstractExternalQuickfixProvider
    implements IFix<IFixContext>, IFixVariant<IFixContext>,
        IFixChangeProcessor<IFixChange, IFixContext>
{
    private static final String TITLE = "Добавить директиву &НаСервере";
    private static final String DESCRIPTION = "Добавить &НаСервере перед объявлением без директивы компиляции.";
    private static final String BUNDLE = "com.e1c.g5.v8.dt.bsl.check";
    private static final String API = BUNDLE + ".qfix.";
    private static final CheckUid CHECK_UID =
        new CheckUid("form-module-missing-pragma", "com.e1c.v8codestyle.bsl");

    @Override
    public CheckUid getCheckId()
    {
        return CHECK_UID;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<IFixContext> getRequiredContextType()
    {
        try
        {
            return (Class<IFixContext>)loadApi("SingleVariantXtextBslModuleFixContext");
        }
        catch (ReflectiveOperationException e)
        {
            return IFixContext.class;
        }
    }

    @Override
    public void onRegistration(FixDescriptor descriptor)
    {
        descriptor.setChangeProcessor(this);
    }

    @Override
    public Collection<IFixVariant<IFixContext>> getVariants(IFixContext context, IFixSession session)
    {
        EObject element = modelElement(getModel(context, session, false));
        return target(element) != null ? List.of(this) : List.of();
    }

    @Override
    public FixVariantDescriptor describeChanges(IFixContext context, IFixSession session)
    {
        return new FixVariantDescriptor(TITLE, DESCRIPTION + Global.pluginSignForTooltip());
    }

    @Override
    public Collection<IFixChange> prepareChanges(IFixContext context, IFixSession session)
    {
        return target(modelElement(getModel(context, session, false))) == null
            ? List.of() : List.of(new Change());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<IFixChange> getProcessedFixType()
    {
        // Реестр должен получить конкретный тип изменения, а не общий IFixChange.
        return (Class<IFixChange>)(Class<?>)Change.class;
    }

    @Override
    public void applyFix(IFixChange fixChange, IFixContext context, IFixSession session)
    {
        if (!(fixChange instanceof Change))
            return;
        Object model = getModel(context, session, true);
        if (model == null)
            return;
        try
        {
            Class<?> interactiveApi = loadApi("IXtextInteractiveBslModuleFixModel");
            if (!interactiveApi.isInstance(model))
                return;
            IModificationContext modificationContext = (IModificationContext)
                interactiveApi.getMethod("getModificationContext").invoke(model);
            Issue issue = (Issue)loadApi("IXtextBslModuleFixModel").getMethod("getIssue").invoke(model);
            IXtextDocument document = modificationContext.getXtextDocument();
            apply(issue, document, modificationContext);
        }
        catch (Exception e)
        {
            // Документ или контекст исправления уже недоступны.
        }
    }

    /** Предложение для текущей аннотации, когда штатный поиск маркера в базе не дал исправления. */
    static ICompletionProposal[] withLiveProposal(ICompletionProposal[] base,
        XtextAnnotation annotation, Image icon)
    {
        Issue issue = annotation.getIssue();
        IXtextDocument document = annotation.getDocument();
        if (issue == null || document == null || issue.getCode() == null
            || !issue.getCode().startsWith("SU"))
            return base;
        try
        {
            ICompletionProposal proposal = ContentAssistSessionReloader.readOnlyPeekAst(document, resource -> {
                URI uri = resource.getURI();
                if (uri == null || !uri.isPlatformResource())
                    return null;
                IProject project = ResourcesPlugin.getWorkspace().getRoot()
                    .getFile(new Path(uri.toPlatformString(true))).getProject();
                ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
                CheckUid uid = repository == null ? null
                    : repository.getUidForShortUid(issue.getCode(), project);
                if (!CHECK_UID.equals(uid))
                    return null;
                EObject element = liveElement(resource, issue);
                return target(element) == null ? null : new LiveProposal(document, issue, icon);
            });
            if (proposal == null)
                return base;
            for (ICompletionProposal existing : base)
            {
                if (existing != null && TITLE.equals(existing.getDisplayString()))
                    return base;
            }
            ICompletionProposal[] result = java.util.Arrays.copyOf(base, base.length + 1);
            result[base.length] = proposal;
            return result;
        }
        catch (Exception e)
        {
            return base;
        }
    }

    private static EObject liveElement(XtextResource resource, Issue issue)
    {
        URI uri = issue.getUriToProblem();
        if (uri == null || uri.fragment() == null)
            return null;
        return resource.getEObject(uri.fragment());
    }

    private static final class LiveProposal implements ICompletionProposal
    {
        private final IXtextDocument document;
        private final Issue issue;
        private final Image icon;

        LiveProposal(IXtextDocument document, Issue issue, Image icon)
        {
            this.document = document;
            this.issue = issue;
            this.icon = icon;
        }

        @Override
        public void apply(IDocument target)
        {
            if (target != document)
                return;
            try
            {
                FormModuleMissingPragmaFix.apply(issue, document, new IModificationContext() {
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
                // Документ или контекст исправления уже недоступны.
            }
        }

        @Override
        public String getDisplayString()
        {
            return TITLE;
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return DESCRIPTION + Global.pluginSignForTooltip();
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

    private static Class<?> loadApi(String name) throws ClassNotFoundException
    {
        Bundle bundle = Platform.getBundle(BUNDLE);
        if (bundle == null)
            throw new ClassNotFoundException(BUNDLE);
        return bundle.loadClass(API + name);
    }

    private static Object getModel(IFixContext context, IFixSession session, boolean interactive)
    {
        try
        {
            Class<?> contextApi = loadApi("SingleVariantXtextBslModuleFixContext");
            if (!contextApi.isInstance(context))
                return null;
            return contextApi.getMethod("getModel", IFixSession.class, boolean.class)
                .invoke(context, session, interactive);
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    private static EObject modelElement(Object model)
    {
        if (model == null)
            return null;
        try
        {
            Object element = loadApi("IXtextBslModuleFixModel").getMethod("getElement").invoke(model);
            return element instanceof EObject object ? object : null;
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    private static final class Change implements IFixChange
    {
    }

    /** Проверка отмечает сам метод либо каждую ExplicitVariable объявления уровня модуля. */
    private static EObject target(EObject element)
    {
        EObject declaration;
        if (element instanceof Method method && method.getPragmas().isEmpty())
            declaration = method;
        else
        {
            DeclareStatement statement = element instanceof DeclareStatement declare ? declare
                : element instanceof ExplicitVariable
                    ? EcoreUtil2.getContainerOfType(element, DeclareStatement.class) : null;
            if (statement == null || !statement.getPragmas().isEmpty()
                || EcoreUtil2.getContainerOfType(statement, Method.class) != null)
                return null;
            declaration = statement;
        }
        Module module = EcoreUtil2.getContainerOfType(declaration, Module.class);
        return module != null && (module.getModuleType() == ModuleType.FORM_MODULE
            || module.getModuleType() == ModuleType.COMMAND_MODULE) ? declaration : null;
    }

    private static void apply(Issue issue, IXtextDocument document, IModificationContext context)
        throws Exception
    {
        new ExternalQuickfixModification<EObject>(issue, EObject.class, element -> {
            EObject declaration = target(element);
            if (declaration == null)
                return null;
            ICompositeNode node = NodeModelUtils.findActualNodeFor(declaration);
            if (node == null)
                return null;
            // Первый видимый узел — ключевое слово объявления; комментарии остаются над директивой.
            for (ILeafNode leaf : node.getLeafNodes())
            {
                if (leaf.isHidden())
                    continue;
                try
                {
                    int offset = leaf.getOffset();
                    int line = document.getLineOfOffset(offset);
                    int lineOffset = document.getLineOffset(line);
                    String prefix = document.get(lineOffset, offset - lineOffset);
                    String delimiter = TextUtilities.getDefaultLineDelimiter(document);
                    // Не переносим предшествующий код, если объявление начинается в середине строки.
                    String indent = prefix.isBlank() ? prefix : "";
                    return new InsertEdit(offset, "&НаСервере" + delimiter + indent);
                }
                catch (BadLocationException e)
                {
                    return null;
                }
            }
            return null;
        }).apply(context);
    }
}
