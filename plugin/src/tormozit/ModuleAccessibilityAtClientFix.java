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
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.edit.IModificationContext;
import org.eclipse.xtext.ui.editor.validation.XtextAnnotation;
import org.eclipse.xtext.validation.Issue;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.model.PreprocessorConditional;
import com._1c.g5.v8.dt.bsl.model.PreprocessorItemMethodStatement;
import com._1c.g5.v8.dt.bsl.ui.quickfix.AbstractExternalQuickfixProvider;
import com._1c.g5.v8.dt.mcore.util.Environments;
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

/**
 * Исправление «Метод доступен НаКлиенте» (issue #711).
 * Реестр проверок связывает полный CheckUid с проектным Issue.code (SU...).
 * Контекст и модель bsl.check доступны через API, проверенный в JAR EDT 2025/2026;
 * загрузка через Bundle не добавляет зависимости в MANIFEST.MF основного плагина.
 */
public final class ModuleAccessibilityAtClientFix extends AbstractExternalQuickfixProvider
    implements IFix<IFixContext>, IFixVariant<IFixContext>,
        IFixChangeProcessor<IFixChange, IFixContext>
{
    private static final String CONDITION =
        "#Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда";
    private static final String LOG_TOPIC = "module-accessibility-711";
    private static final String BUNDLE = "com.e1c.g5.v8.dt.bsl.check";
    private static final String API = BUNDLE + ".qfix.";
    private static final CheckUid CHECK_UID =
        new CheckUid("module-accessibility-at-client", "com.e1c.v8codestyle.bsl");

    public ModuleAccessibilityAtClientFix()
    {
        // Реестр ещё инициализируется: запросы к ICheckRepository здесь недопустимы.
        Global.tempLog(LOG_TOPIC, "fix created; diagnostic=method-node-v7");
    }

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
            Global.tempLog(LOG_TOPIC, "context API unavailable: " + e);
            return IFixContext.class;
        }
    }

    @Override
    public void onRegistration(FixDescriptor descriptor)
    {
        descriptor.setChangeProcessor(this);
        Global.tempLog(LOG_TOPIC, "registered check=" + CHECK_UID);
    }

    @Override
    public Collection<IFixVariant<IFixContext>> getVariants(IFixContext context, IFixSession session)
    {
        Global.tempLog(LOG_TOPIC, "getVariants context=" + context.getClass().getName());
        logCheckIds();
        Object model = getModel(context, session, false);
        Method method = modelMethod(model);
        boolean applicable = method != null && canFix(method);
        Global.tempLog(LOG_TOPIC, "applicable=" + applicable + " element=" + method);
        return applicable ? List.of(this) : List.of();
    }

    @Override
    public FixVariantDescriptor describeChanges(IFixContext context, IFixSession session)
    {
        Method method = modelMethod(getModel(context, session, false));
        boolean move = method != null
            && findServerBranch(EcoreUtil2.getContainerOfType(method, Module.class)) != null;
        String title = move ? "Переместить под условие компиляции" :
            "Окружить весь модуль условием компиляции";
        String details = move ? "Переместить метод вместе с описанием в первую серверную ветку модуля." :
            "Ограничить компиляцию всего модуля сервером, толстым клиентом обычного приложения "
                + "и внешним соединением.";
        Global.tempLog(LOG_TOPIC, "offered: " + title);
        return new FixVariantDescriptor(title, details + Global.pluginSignForTooltip());
    }

    @Override
    public Collection<IFixChange> prepareChanges(IFixContext context, IFixSession session)
    {
        Method method = modelMethod(getModel(context, session, false));
        if (method == null || !canFix(method))
            return List.of();
        boolean move = findServerBranch(EcoreUtil2.getContainerOfType(method, Module.class)) != null;
        Global.tempLog(LOG_TOPIC, "prepareChanges move=" + move);
        return List.of(new Change(move));
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
        Global.tempLog(LOG_TOPIC, "applyFix change=" + fixChange);
        if (!(fixChange instanceof Change change))
            return;
        Global.tempLog(LOG_TOPIC, "applyFix move=" + change.move);
        Object model = getModel(context, session, true);
        if (model == null)
            return;
        try
        {
            Class<?> interactiveApi = loadApi("IXtextInteractiveBslModuleFixModel");
            if (!interactiveApi.isInstance(model))
            {
                Global.tempLog(LOG_TOPIC, "apply skipped: interactive model unavailable");
                return;
            }
            IModificationContext modificationContext = (IModificationContext)
                interactiveApi.getMethod("getModificationContext").invoke(model);
            Issue issue = (Issue)loadApi("IXtextBslModuleFixModel").getMethod("getIssue").invoke(model);
            IXtextDocument document = modificationContext.getXtextDocument();
            // Как SingleVariantXtextBslModuleFix: актуальный метод и TextEdit под readOnly,
            // применение через штатный ExternalQuickfixModification с поддержкой отмены.
            new ExternalQuickfixModification<Method>(issue, Method.class, method -> {
                Global.tempLog(LOG_TOPIC, "apply method=" + method.getName());
                if (!canFix(method))
                    return null;
                Module module = EcoreUtil2.getContainerOfType(method, Module.class);
                if ((findServerBranch(module) != null) != change.move)
                {
                    Global.tempLog(LOG_TOPIC, "apply skipped: compilation conditions changed");
                    return null;
                }
                try
                {
                    return createEdits(method, document);
                }
                catch (BadLocationException e)
                {
                    Global.tempLog(LOG_TOPIC, "apply failed: " + e);
                    return null;
                }
            }).apply(modificationContext);
        }
        catch (Exception e)
        {
            Global.tempLog(LOG_TOPIC, "applyFix failed: " + e);
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
        Global.tempLog(LOG_TOPIC, "live annotation code=" + issue.getCode()
            + " offset=" + issue.getOffset() + " uri=" + issue.getUriToProblem());
        try
        {
            ICompletionProposal proposal = document.readOnly(resource -> {
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
                Method method = liveMethod(resource, issue);
                boolean applicable = method != null && canFix(method);
                Global.tempLog(LOG_TOPIC, "live applicable=" + applicable + " method=" + method);
                if (!applicable)
                    return null;
                boolean move = findServerBranch(EcoreUtil2.getContainerOfType(method, Module.class)) != null;
                return new LiveProposal(document, issue, method.getName(), move, icon);
            });
            if (proposal == null)
                return base;
            for (ICompletionProposal existing : base)
            {
                if (existing != null && ("Переместить под условие компиляции".equals(existing.getDisplayString())
                    || "Окружить весь модуль условием компиляции".equals(existing.getDisplayString())))
                    return base;
            }
            ICompletionProposal[] result = java.util.Arrays.copyOf(base, base.length + 1);
            result[base.length] = proposal;
            Global.tempLog(LOG_TOPIC, "live offered: " + proposal.getDisplayString());
            return result;
        }
        catch (Exception e)
        {
            Global.tempLog(LOG_TOPIC, "live proposal failed: " + e);
            return base;
        }
    }

    private static Method liveMethod(XtextResource resource, Issue issue)
    {
        URI uri = issue.getUriToProblem();
        if (uri == null || uri.fragment() == null)
            return null;
        EObject element = resource.getEObject(uri.fragment());
        return element instanceof Method method ? method : null;
    }

    private static final class LiveProposal implements ICompletionProposal
    {
        private final IXtextDocument document;
        private final Issue issue;
        private final String methodName;
        private final boolean move;
        private final Image icon;

        LiveProposal(IXtextDocument document, Issue issue, String methodName, boolean move, Image icon)
        {
            this.document = document;
            this.issue = issue;
            this.methodName = methodName;
            this.move = move;
            this.icon = icon;
        }

        @Override
        public void apply(IDocument target)
        {
            Global.tempLog(LOG_TOPIC, "live apply method=" + methodName + " sameDocument=" + (target == document));
            if (target != document)
                return;
            try
            {
                new ExternalQuickfixModification<Method>(issue, Method.class, method -> {
                    Global.tempLog(LOG_TOPIC, "live apply resolved=" + method.getName());
                    if (!methodName.equals(method.getName()) || !canFix(method))
                        return null;
                    try
                    {
                        return createEdits(method, document);
                    }
                    catch (BadLocationException e)
                    {
                        Global.tempLog(LOG_TOPIC, "live edits failed: " + e);
                        return null;
                    }
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
                Global.tempLog(LOG_TOPIC, "live apply finished");
            }
            catch (Exception e)
            {
                Global.tempLog(LOG_TOPIC, "live apply failed: " + e);
            }
        }

        @Override
        public String getDisplayString()
        {
            return move ? "Переместить под условие компиляции" : "Окружить весь модуль условием компиляции";
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return "Ограничить доступность метода серверным контекстом." + Global.pluginSignForTooltip();
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
            Global.tempLog(LOG_TOPIC, "getModel failed: " + e);
            return null;
        }
    }

    private static Method modelMethod(Object model)
    {
        if (model == null)
            return null;
        try
        {
            Object element = loadApi("IXtextBslModuleFixModel").getMethod("getElement").invoke(model);
            Global.tempLog(LOG_TOPIC, "model element=" + element);
            return element instanceof Method method ? method : null;
        }
        catch (ReflectiveOperationException e)
        {
            Global.tempLog(LOG_TOPIC, "model element failed: " + e);
            return null;
        }
    }

    private static void logCheckIds()
    {
        try
        {
            ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
            Global.tempLog(LOG_TOPIC, "check repository=" + repository);
            if (repository != null)
            {
                int found = 0;
                for (CheckUid uid : repository.getChecksWithDescriptions().keySet())
                {
                    if (!CHECK_UID.getCheckId().equals(uid.getCheckId()))
                        continue;
                    found++;
                    Global.tempLog(LOG_TOPIC, "check id=" + uid.getCheckId()
                        + " contributor=" + uid.getContributorId()
                        + " projectCodes=" + repository.getShortUid(uid));
                }
                Global.tempLog(LOG_TOPIC, "matching checks=" + found);
            }
        }
        catch (Throwable e)
        {
            Global.tempLog(LOG_TOPIC, "check UID diagnostic failed: " + e);
        }
    }

    private static final class Change implements IFixChange
    {
        final boolean move;

        Change(boolean move)
        {
            this.move = move;
        }
    }

    private static boolean canFix(Method method)
    {
        Module module = EcoreUtil2.getContainerOfType(method, Module.class);
        boolean objectModule = module != null && isObjectModule(module);
        Environments environments = method.environments();
        boolean atClient = environments.containsAny(Environments.MNG_CLIENTS);
        XtextResource resource = module != null && module.eResource() instanceof XtextResource xtext
            ? xtext : null;
        boolean parsed = resource != null && resource.getParseResult() != null;
        boolean syntaxErrors = parsed && resource.getParseResult().hasSyntaxErrors();
        ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(method);
        boolean methodSyntaxErrors = methodNode == null || hasMethodSyntaxErrors(methodNode);
        if (parsed)
        {
            for (INode error : resource.getParseResult().getSyntaxErrors())
            {
                Global.tempLog(LOG_TOPIC, "parse error offset=" + error.getOffset()
                    + " length=" + error.getLength() + " message=" + error.getSyntaxErrorMessage());
            }
        }
        Global.tempLog(LOG_TOPIC, "canFix method=" + method.getName()
            + " moduleType=" + (module == null ? null : module.getModuleType())
            + " objectModule=" + objectModule + " environments=" + environments
            + " atClient=" + atClient + " resource=" + (resource == null ? null : resource.getURI())
            + " parsed=" + parsed + " syntaxErrors=" + syntaxErrors + " methodSyntaxErrors=" + methodSyntaxErrors
            + " errors=" + (resource == null ? null : resource.getErrors()));
        for (EObject parent = method; parent != null; parent = parent.eContainer())
        {
            Global.tempLog(LOG_TOPIC, "environment parent=" + parent.eClass().getName()
                + " feature=" + (parent.eContainingFeature() == null ? null : parent.eContainingFeature().getName())
                + (parent instanceof com._1c.g5.v8.dt.mcore.Environmental environmental
                    ? " own=" + environmental.getEnvironments() + " effective=" + environmental.environments() : ""));
            if (parent == module)
                break;
        }
        // Условие переносит целый метод; ошибки разбора вне него не меняют его границы.
        // Общий hasSyntaxErrors() скрывал исправление для метода после #КонецЕсли (itemAfter).
        return objectModule && atClient && parsed && !methodSyntaxErrors;
    }

    private static boolean hasMethodSyntaxErrors(ICompositeNode methodNode)
    {
        boolean found = false;
        for (INode node : methodNode.getAsTreeIterable())
        {
            if (node.getSyntaxErrorMessage() != null)
            {
                found = true;
                Global.tempLog(LOG_TOPIC, "method parse error offset=" + node.getOffset()
                    + " message=" + node.getSyntaxErrorMessage());
            }
        }
        return found;
    }

    private static TextEdit createEdits(Method method, IDocument document) throws BadLocationException
    {
        Module module = EcoreUtil2.getContainerOfType(method, Module.class);
        String separator = TextUtilities.getDefaultLineDelimiter(document);
        PreprocessorConditional branch = findServerBranch(module);
        MultiTextEdit edits = new MultiTextEdit();
        if (branch == null)
        {
            int firstLine = 0;
            int lastLine = document.getNumberOfLines() - 1;
            while (firstLine <= lastLine && isBlankLine(document, firstLine))
                firstLine++;
            while (lastLine >= firstLine && isBlankLine(document, lastLine))
                lastLine--;
            if (firstLine > lastLine)
                return null;
            int contentStart = document.getLineOffset(firstLine);
            int contentEnd = document.getLineOffset(lastLine)
                + document.getLineInformation(lastLine).getLength();
            // Заменяем только пустые строки по краям, не трогая описание и тело модуля.
            // Два разделителя строк дают ровно одну пустую строку у каждой директивы.
            edits.addChild(new ReplaceEdit(0, contentStart, CONDITION + separator + separator));
            edits.addChild(new ReplaceEdit(contentEnd, document.getLength() - contentEnd,
                separator + separator + "#КонецЕсли" + separator));
            Global.tempLog(LOG_TOPIC, "wrap contentStart=" + contentStart + " contentEnd=" + contentEnd);
            return edits;
        }

        ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
        if (node == null)
            return null;
        int start = descriptionStart(document, node.getOffset());
        int end = node.getEndOffset();
        int endLine = document.getLineOfOffset(end);
        int lineEnd = document.getLineOffset(endLine) + document.getLineLength(endLine);
        String tail = document.get(end, lineEnd - end).strip();
        if (tail.isEmpty() || tail.startsWith("//"))
            end = lineEnd;

        int target = insertionOffset(document, branch);
        if (target < 0 || target >= start && target <= end)
            return null;
        String text = document.get(start, end - start);
        if (!text.endsWith("\n") && !text.endsWith("\r"))
            text += separator;
        String prefix = target > 0 && document.getChar(target - 1) != '\n'
            && document.getChar(target - 1) != '\r' ? separator : "";
        edits.addChild(new DeleteEdit(start, end - start));
        edits.addChild(new InsertEdit(target, prefix + text + separator));
        return edits;
    }

    private static boolean isBlankLine(IDocument document, int line) throws BadLocationException
    {
        return document.get(document.getLineOffset(line),
            document.getLineInformation(line).getLength()).isBlank();
    }

    private static boolean isObjectModule(Module module)
    {
        ModuleType type = module.getModuleType();
        return type == ModuleType.OBJECT_MODULE || type == ModuleType.MANAGER_MODULE
            || type == ModuleType.RECORDSET_MODULE;
    }

    private static PreprocessorConditional findServerBranch(Module module)
    {
        for (PreprocessorConditional branch : EcoreUtil2.getAllContentsOfType(module, PreprocessorConditional.class))
        {
            if (!(branch.getItem() instanceof PreprocessorItemMethodStatement)
                || EcoreUtil2.getContainerOfType(branch, Method.class) != null)
                continue;
            Environments environments = branch.environments();
            if (environments.containsAny(Environments.SERVER)
                && !environments.containsAny(Environments.MNG_CLIENTS))
                return branch;
        }
        return null;
    }

    private static int insertionOffset(IDocument document, PreprocessorConditional branch)
        throws BadLocationException
    {
        PreprocessorItemMethodStatement item = (PreprocessorItemMethodStatement)branch.getItem();
        EObject first = !item.getMethods().isEmpty() ? item.getMethods().get(0) :
            !item.getPreprocessors().isEmpty() ? item.getPreprocessors().get(0) :
            !item.getStatements().isEmpty() ? item.getStatements().get(0) : null;
        ICompositeNode node = NodeModelUtils.getNode(first == null ? branch : first);
        if (node == null)
            return -1;
        // У ветки нет itemAfter: её конец не захватывает код за #КонецЕсли.
        // При наличии операторов вставляем перед ними: методы должны идти до инициализации.
        return first == null ? node.getEndOffset() : descriptionStart(document, node.getOffset());
    }

    private static int descriptionStart(IDocument document, int offset) throws BadLocationException
    {
        int line = document.getLineOfOffset(offset);
        int start = document.getLineOffset(line);
        // Тот же паттерн переноса описания, что в MoveMethodToModuleHandler.
        while (line > 0)
        {
            int previous = document.getLineOffset(line - 1);
            if (!document.get(previous, start - previous).strip().startsWith("//"))
                break;
            start = previous;
            line--;
        }
        return start;
    }
}
