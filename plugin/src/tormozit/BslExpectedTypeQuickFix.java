package tormozit;

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.validation.XtextAnnotation;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.bsl.common.IBslPreferences;
import com._1c.g5.v8.dt.bsl.documentation.comment.BslDocumentationComment;
import com._1c.g5.v8.dt.bsl.documentation.comment.TypeSection;
import com._1c.g5.v8.dt.bsl.model.BslContextDefMethod;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.mcore.Environmental;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Быстрое исправление «Редактировать ожидаемый тип» для несовместимости типов из проверки Комфорта:
 * открывает модуль, где явно задан ожидаемый тип, и выделяет его текст для редактирования.
 * Используется и предложением редактора, и framework-исправлением маркера проверки.
 */
public final class BslExpectedTypeQuickFix
{
    public static final String TITLE = "Редактировать ожидаемый тип";

    private static final String CHECK_ID = ComfortCheckIds.BSL_VALUE_TYPE;
    private static final String CHECK_BUNDLE = "tormozit.comfort.checks"; //$NON-NLS-1$
    private static final String FIX_MODEL_BUNDLE = "com.e1c.g5.v8.dt.bsl.check"; //$NON-NLS-1$
    private static final String FIX_MODEL_API =
        "com.e1c.g5.v8.dt.bsl.check.qfix.IXtextBslModuleFixModel"; //$NON-NLS-1$

    private BslExpectedTypeQuickFix()
    {
    }

    /** Место явного типа в модуле: URI метода, смещение и длина текста типа. */
    public record Target(URI uri, int offset, int length, String typeText)
    {
    }

    /** Предложение Ctrl+1 для аннотации редактора модуля. */
    public static ICompletionProposal proposal(XtextAnnotation annotation, Image icon)
    {
        Issue issue = annotation == null ? null : annotation.getIssue();
        IXtextDocument document = annotation == null ? null : annotation.getDocument();
        if (issue == null || document == null || issue.getCode() == null)
            return null;
        Target target = locate(issue, document);
        return target == null ? null : new Proposal(target, icon);
    }

    /** Поиск явного типа по документу проверки, в том числе из модели framework-исправления. */
    public static Target locate(Issue issue, IDocument document)
    {
        if (!(document instanceof IXtextDocument xtext) || issue == null)
            return null;
        try
        {
            return xtext.readOnly(resource -> isThisCheck(issue, resource) ? locate(issue, resource) : null);
        }
        catch (RuntimeException e)
        {
            // Документ или аннотация уже недоступны — исправление не предлагается.
            return null;
        }
    }

    /**
     * Получение issue и документа из модели {@code SingleVariantXtextBslModuleFixContext} без зависимости
     * бандла проверок от внутренних API EDT: модель приходит сюда уже из бандла проверок.
     */
    public static Target locateFromFixModel(Object model)
    {
        if (model == null)
            return null;
        try
        {
            var bundle = Platform.getBundle(FIX_MODEL_BUNDLE);
            if (bundle == null)
                return null;
            Class<?> api = bundle.loadClass(FIX_MODEL_API);
            Object issue = api.getMethod("getIssue").invoke(model); //$NON-NLS-1$
            Object document = api.getMethod("getDocument").invoke(model); //$NON-NLS-1$
            return issue instanceof Issue xtextIssue ? locate(xtextIssue, (IDocument)document) : null;
        }
        catch (ReflectiveOperationException | ClassCastException e)
        {
            // Контекст маркера не дал Xtext-модель — исправление не предлагается.
            return null;
        }
    }

    /** Открывает модуль с явным типом и выделяет его текст, сохраняя позицию в истории переходов. */
    public static void reveal(Target target)
    {
        if (target == null)
            return;
        IWorkbenchPage page = Global.getActivePage();
        if (page == null)
            return;
        // Исправление можно вызвать и из панели «Проблемы»: историю сохраняем, только
        // когда переход идёт из модуля — в остальных случаях отмечать нечего.
        IWorkbenchPart part = page.getActivePart();
        IEditorPart source = part == null ? null : GetRef.getActiveBslEditor(part);
        if (source == null)
        {
            open(page, target);
            return;
        }
        try (NavigationHistoryScope history = new NavigationHistoryScope(page, source))
        {
            open(page, target);
            history.markOpened();
        }
    }

    private static void open(IWorkbenchPage page, Target target)
    {
        new OpenHelper(page).openEditor(target.uri(),
            new TextSelection(target.offset(), Math.max(target.length(), 0)));
    }

    private static boolean isThisCheck(Issue issue, XtextResource resource)
    {
        if (issue.getCode() == null)
            return false;
        if (CHECK_ID.equals(issue.getCode()))
            return true;
        URI uri = resource.getURI();
        if (uri == null || !uri.isPlatformResource())
            return false;
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
            .getFile(new Path(uri.toPlatformString(true))).getProject();
        ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
        CheckUid uid = repository == null ? null : repository.getUidForShortUid(issue.getCode(), project);
        return uid != null && CHECK_ID.equals(uid.getCheckId());
    }

    private static Target locate(Issue issue, XtextResource resource)
    {
        URI problem = issue.getUriToProblem();
        if (problem == null || problem.fragment() == null)
            return null;
        EObject element = resource.getEObject(problem.fragment());
        Invocation invocation = EcoreUtil2.getContainerOfType(element, Invocation.class);
        int index = argumentIndex(element, invocation);
        if (index < 0 || invocation == null)
            return null;
        FeatureAccess access = invocation.getMethodAccess();
        if (access == null)
            return null;
        for (FeatureEntry entry : entries(access, invocation, resource))
        {
            EObject feature = entry.getFeature();
            if (feature != null && feature.eIsProxy())
                feature = EcoreUtil.resolve(feature, invocation);
            if (feature instanceof BslContextDefMethod exported)
                feature = sourceMethod(exported, invocation);
            if (!(feature instanceof Method method) || method.eIsProxy()
                || index >= method.getFormalParams().size())
                continue;
            Target target = commentTarget(method, method.getFormalParams().get(index));
            if (target != null)
                return target;
        }
        return null;
    }

    /** Разрешение вызова так же, как в самой проверке: статический доступ либо динамический через точку. */
    private static List<FeatureEntry> entries(FeatureAccess access, Invocation invocation, XtextResource resource)
    {
        if (access instanceof StaticFeatureAccess stat)
            return stat.getFeatureEntries();
        if (access instanceof DynamicFeatureAccess dyn)
        {
            var services = resource.getResourceServiceProvider();
            DynamicFeatureAccessComputer dynamic = services.get(DynamicFeatureAccessComputer.class);
            IBslPreferences preferences = services.get(IBslPreferences.class);
            if (dynamic == null || preferences == null)
                return List.of();
            Environments envs = environments(invocation, preferences);
            return dynamic.getLastObject(dyn, envs, true);
        }
        return List.of();
    }

    private static Environments environments(EObject object, IBslPreferences preferences)
    {
        Environmental owner = EcoreUtil2.getContainerOfType(object, Environmental.class);
        return owner == null ? Environments.EMPTY
            : preferences.getLoadEnvs(object).intersect(owner.environments());
    }

    private static int argumentIndex(EObject element, Invocation invocation)
    {
        if (element == null || invocation == null)
            return -1;
        EObject current = element;
        while (current.eContainer() != null && current.eContainer() != invocation)
            current = current.eContainer();
        return current.eContainer() == invocation ? invocation.getParams().indexOf(current) : -1;
    }

    /** Экспортный метод модуля приходит методом контекста; исходный BSL-метод лежит по sourceUri. */
    private static Method sourceMethod(BslContextDefMethod exported, EObject context)
    {
        URI uri = exported.getSourceUri();
        if (uri == null)
            return null;
        Resource contextResource = context.eResource();
        if (uri.isRelative() && contextResource != null)
            uri = uri.resolve(contextResource.getURI());
        EObject proxy = EcoreFactory.eINSTANCE.createEObject();
        ((InternalEObject)proxy).eSetProxyURI(uri);
        try
        {
            EObject resolved = EcoreUtil.resolve(proxy, context);
            return resolved instanceof Method method && !method.eIsProxy() ? method : null;
        }
        catch (RuntimeException e)
        {
            // Контекст модели недоступен: явный тип из другого модуля не находится.
            return null;
        }
    }

    /** Диапазон явного типа в комментарии параметра; пусто, если тип в модуле не задан. */
    private static Target commentTarget(Method method, FormalParam parameter)
    {
        Module module = EcoreUtil2.getContainerOfType(method, Module.class);
        ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(method);
        if (module == null || methodNode == null)
            return null;
        BslDocumentationComment comment = BslStructureInsertCommentTypes.parseMethodComment(method, module);
        if (comment == null || comment.getParametersSection() == null)
            return null;
        TypeSection.FieldDefinition definition =
            comment.getParametersSection().getParameterByName(parameter.getName());
        if (definition == null)
            return null;
        int[] lines = comment.getLineGlobalOffsets();
        int start = Integer.MAX_VALUE;
        int end = -1;
        StringBuilder names = new StringBuilder();
        String source = methodNode.getRootNode().getText();
        for (TypeSection section : definition.getTypeSections())
        {
            List<TypeSection.TypeDefinition> types = section.getTypeDefinitions();
            for (TypeSection.TypeDefinition type : types)
            {
                String name = type.getTypeName();
                int line = type.getLineNumber();
                if (name == null || name.isBlank() || lines == null || line < 0 || line >= lines.length)
                    continue;
                int offset = methodNode.getTotalOffset() + lines[line] + type.getNameOffset();
                int finish = offset + name.length();
                if (offset < 0 || finish > source.length()
                    || !source.regionMatches(true, offset, name, 0, name.length()))
                    continue;
                start = Math.min(start, offset);
                end = Math.max(end, finish);
                if (names.length() > 0)
                    names.append(", "); //$NON-NLS-1$
                names.append(name);
            }
        }
        if (end < 0)
            return null;
        return new Target(EcoreUtil.getURI(method), start, end - start, names.toString());
    }

    private static final class Proposal implements ICompletionProposal
    {
        private final Target target;
        private final Image icon;

        Proposal(Target target, Image icon)
        {
            this.target = target;
            this.icon = icon;
        }

        @Override
        public void apply(IDocument document)
        {
            reveal(target);
        }

        @Override
        public String getDisplayString()
        {
            return TITLE;
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return "Открыть модуль, где явно задан ожидаемый тип, и выделить его текст для редактирования."
                + Global.pluginSignForTooltip();
        }

        @Override
        public Image getImage()
        {
            return icon;
        }

        @Override
        public Point getSelection(IDocument document)
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
