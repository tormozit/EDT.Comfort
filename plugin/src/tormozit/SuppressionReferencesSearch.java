package tormozit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Matcher;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bsl.parser.antlr.lexer.InternalBslLexer;
import com._1c.g5.v8.dt.common.Functions;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.MdUtil;
import com._1c.g5.v8.dt.md.IExternalPropertyManagerRegistry;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.search.core.text.TextSearchModelMatch;
import com._1c.g5.v8.dt.search.core.text.TextSearchFileMatch;
import com._1c.g5.v8.dt.search.core.Match;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

/** Ссылки в настройках подавлений и комментариях модулей; представление — штатное EDT. */
final class SuppressionReferencesSearch
{
    private static final String MODEL_URI = "http://g5.1c.ru/v8/dt/check/suppress/model"; //$NON-NLS-1$
    private static final Map<Object, Target> TARGETS = Collections.synchronizedMap(new WeakHashMap<>());

    private record Target(IProject project, String ownerFqn, String containmentFqn, String property, CheckUid uid) {}

    static void findReferences(IProject project, CheckUid uid)
    {
        if (project == null || uid == null)
            return;
        try
        {
            Query query = new Query(project, uid);
            Display display = Display.getCurrent();
            Shell dialog = display.getActiveShell();
            Control focus = display.getFocusControl();
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window != null ? window.getActivePage() : null;
            if (page == null)
                throw new IllegalStateException("Рабочая страница EDT недоступна"); //$NON-NLS-1$
            ISearchResultViewPart view = (ISearchResultViewPart)page.showView(
                NewSearchUI.SEARCH_VIEW_ID, null, IWorkbenchPage.VIEW_VISIBLE);
            query.resultView = view;
            NewSearchUI.runQueryInBackground(query, view);
            // NewSearchUI активирует панель даже при переданном view; возвращаем фокус окну параметров.
            if (dialog != null && !dialog.isDisposed())
                dialog.setActive();
            if (focus != null && !focus.isDisposed())
                focus.setFocus();
        }
        catch (ReflectiveOperationException | PartInitException | RuntimeException e)
        {
            ToastNotification.show("Поиск ссылок", "Не удалось запустить поиск: " + e.getMessage(), 8_000); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    static boolean isResult(ISearchResult result)
    {
        return result != null && result.getQuery() instanceof Query;
    }

    static String propertyText(Object match)
    {
        Target target = TARGETS.get(match);
        return target != null ? target.property() : null;
    }

    static boolean openMatch(Object match)
    {
        Target target = TARGETS.get(match);
        if (target == null)
            return false;
        ProblemViewHook.openSuppressionSettings(target.project(), target.ownerFqn(), target.containmentFqn(), target.uid());
        return true;
    }

    private static final class Query implements ISearchQuery
    {
        private final IProject project;
        private final CheckUid uid;
        private final ISearchResult result;
        private ISearchResultViewPart resultView;

        Query(IProject project, CheckUid uid) throws ReflectiveOperationException
        {
            this.project = project;
            this.uid = uid;
            Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.search.ui"); //$NON-NLS-1$
            if (bundle == null)
                throw new IllegalStateException("Бандл поиска EDT недоступен"); //$NON-NLS-1$
            Object created = Global.newInstance(bundle.loadClass("com._1c.g5.v8.dt.internal.search.ui.SearchResult"), this); //$NON-NLS-1$
            if (!(created instanceof ISearchResult nativeResult))
                throw new IllegalStateException("Результат поиска EDT не создан"); //$NON-NLS-1$
            result = nativeResult;
        }

        @Override public IStatus run(IProgressMonitor monitor)
        {
            Global.invokeVoid(result, "reset"); //$NON-NLS-1$
            try
            {
                IBmModelManager models = Global.getOsgiService(IBmModelManager.class);
                IBmModel model = models != null ? models.getModel(project) : null;
                if (model == null)
                    throw new IllegalStateException("Модель текущего проекта недоступна"); //$NON-NLS-1$
                List<Match> matches = new ArrayList<>(model.executeReadonlyTask(
                    new AbstractBmTask<List<TextSearchModelMatch>>("comfort.suppressionReferences") //$NON-NLS-1$
                    {
                        @Override public List<TextSearchModelMatch> execute(IBmTransaction transaction, IProgressMonitor taskMonitor)
                        {
                            return collect(model, transaction, monitor);
                        }
                    }, true));
                collectModuleReferences(model, matches, monitor);
                if (!Global.invokeVoid(result, "addMatches", matches)) //$NON-NLS-1$
                    throw new IllegalStateException("Не удалось передать ссылки в результат EDT"); //$NON-NLS-1$
                return Status.OK_STATUS;
            }
            catch (OperationCanceledException e)
            {
                return Status.CANCEL_STATUS;
            }
            catch (CoreException | IOException | RuntimeException e)
            {
                return new Status(IStatus.ERROR, "tormozit.comfort", "Не удалось найти ссылки на проверку", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
            finally
            {
                Global.invokeVoid(result, "finish"); //$NON-NLS-1$
                refreshCompletedResult();
            }
        }

        private void collectModuleReferences(IBmModel model, List<Match> matches, IProgressMonitor monitor)
            throws CoreException, IOException
        {
            List<IFile> modules = new ArrayList<>();
            var source = project.getFolder("src"); //$NON-NLS-1$
            if (source.exists())
                source.accept(resource ->
                {
                    if (monitor.isCanceled())
                        throw new OperationCanceledException();
                    if (resource instanceof IFile file && "bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                        modules.add(file);
                    return true;
                });
            for (IFile file : modules)
            {
                if (monitor.isCanceled())
                    throw new OperationCanceledException();
                String text;
                try (InputStream stream = file.getContents(true))
                {
                    text = new String(stream.readAllBytes(), Charset.forName(file.getCharset(true)));
                }
                if (text.startsWith("\uFEFF")) //$NON-NLS-1$
                    text = text.substring(1);
                if (!text.contains("@skip-check")) //$NON-NLS-1$
                    continue;
                // Лексер EDT отличает настоящие комментарии, в том числе inline,
                // от строковых литералов и текста запросов внутри строк модуля.
                InternalBslLexer lexer = new InternalBslLexer(new ANTLRStringStream(text))
                {
                    @Override public void emitErrorMessage(String message)
                    {
                        // Синтаксические ошибки исходного модуля показывает валидатор EDT.
                    }
                };
                Token token;
                while ((token = lexer.nextToken()).getType() != Token.EOF)
                {
                    if (monitor.isCanceled())
                        throw new OperationCanceledException();
                    if (token.getType() != InternalBslLexer.RULE_SL_COMMENT || !(token instanceof CommonToken comment))
                        continue;
                    Matcher codes = BslCheckDefinitionHandler.suppressionCodes(comment.getText());
                    if (codes == null)
                        continue;
                    while (codes.find())
                    {
                        if (!uid.getCheckId().equals(codes.group()))
                            continue;
                        int offset = comment.getStartIndex() + codes.start();
                        int lineStart = Math.max(text.lastIndexOf('\n', offset - 1), text.lastIndexOf('\r', offset - 1)) + 1;
                        int lineEnd = offset;
                        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r')
                            lineEnd++;
                        matches.add(new TextSearchFileMatch(model, file, text.substring(lineStart, lineEnd),
                            offset - lineStart, codes.end() - codes.start(), offset, comment.getLine()));
                    }
                }
            }
        }

        /**
         * SearchViewUpdateManager не ставит ChangeElementsJob, если у страницы уже есть две задачи.
         * Reset и первый показ занимают эту очередь раньше быстрого Added. По завершении запроса
         * убираем отложенные задачи этой страницы и штатно перечитываем заполненный результат в UI.
         */
        private void refreshCompletedResult()
        {
            Display display = Display.getDefault();
            if (display.isDisposed())
                return;
            display.syncExec(() ->
            {
                if (resultView == null)
                    return;
                ISearchResultPage page = resultView.getActivePage();
                if (page == null || Global.invoke(page, "getInput") != result //$NON-NLS-1$
                    || page.getControl() == null || page.getControl().isDisposed())
                    return;
                Job.getJobManager().cancel(page);
                page.setInput(result, null);
            });
        }

        private List<TextSearchModelMatch> collect(IBmModel model, IBmTransaction transaction, IProgressMonitor monitor)
        {
            List<TextSearchModelMatch> matches = new ArrayList<>();
            Iterator<EClass> classes = transaction.getTopObjectEClasses();
            while (classes.hasNext())
            {
                EClass type = classes.next();
                if (!MODEL_URI.equals(type.getEPackage().getNsURI()))
                    continue;
                Iterator<IBmObject> roots = transaction.getTopObjectIterator(type);
                while (roots.hasNext())
                {
                    if (monitor.isCanceled())
                        throw new OperationCanceledException();
                    IBmObject root = roots.next();
                    if (!containsUid(root, monitor))
                        continue;
                    String owner = stringFeature(root, "fqn"); //$NON-NLS-1$
                    if (owner == null && "SuppressConfiguration".equals(type.getName())) //$NON-NLS-1$
                    {
                        Iterator<IBmObject> configurations = transaction.getTopObjectIterator(MdClassPackage.Literals.CONFIGURATION);
                        if (configurations.hasNext())
                            owner = configurations.next().bmGetFqn();
                    }
                    if (owner == null)
                        throw new IllegalStateException("Не найден владелец настройки подавления: " + root.bmGetFqn()); //$NON-NLS-1$
                    IBmObject mdObject = resolveOwner(transaction, owner);
                    if (mdObject != null && !(mdObject instanceof MdObject))
                    {
                        EObject metadataOwner;
                        if (mdObject instanceof AbstractForm form)
                            metadataOwner = form.getMdForm();
                        else
                        {
                            IExternalPropertyManagerRegistry registry = Global.getOsgiService(IExternalPropertyManagerRegistry.class);
                            var external = registry != null ? registry.getExternalPropertyManager(model) : null;
                            metadataOwner = external != null ? external.getOwner(mdObject, MdObject.class) : null;
                        }
                        mdObject = metadataOwner instanceof IBmObject bmOwner ? bmOwner : null;
                    }
                    if (mdObject == null)
                        throw new IllegalStateException("Объект настройки подавления не найден: " + owner); //$NON-NLS-1$
                    walk(root, owner, null, mdObject, model, matches, monitor);
                }
            }
            return matches;
        }

        private boolean containsUid(EObject node, IProgressMonitor monitor)
        {
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            if (node instanceof Map.Entry<?, ?> entry && uid.equals(entry.getKey()))
                return true;
            for (EObject child : node.eContents())
                if (containsUid(child, monitor))
                    return true;
            return false;
        }

        private void walk(EObject node, String owner, String path, IBmObject mdObject,
            IBmModel model, List<TextSearchModelMatch> matches, IProgressMonitor monitor)
        {
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            if ("SuppressContainment".equals(node.eClass().getName())) //$NON-NLS-1$
            {
                String segment = stringFeature(node, "fqn"); //$NON-NLS-1$
                if (segment != null)
                    path = path == null ? segment : path + "/" + segment; //$NON-NLS-1$
            }
            if (node instanceof Map.Entry<?, ?> entry && uid.equals(entry.getKey()) && node instanceof IBmObject bm)
            {
                String entryPath = node.eContainingFeature() != null
                    && "contentSuppressions".equals(node.eContainingFeature().getName()) ? "Content" : path; //$NON-NLS-1$ //$NON-NLS-2$
                EStructuralFeature suppressionFeature = mdObject.eClass().getEAllStructuralFeatures().stream()
                    .filter(MdUtil::isSuppressSettingsFeature).findFirst().orElse(null);
                if (suppressionFeature == null)
                    throw new IllegalStateException("Свойство подавлений не найдено: " + owner); //$NON-NLS-1$
                String text = uid.getCheckId();
                TextSearchModelMatch match = new SuppressionMatch(model, text, mdObject,
                    bm.bmGetId(), suppressionFeature);
                String property = "Content".equals(entryPath) ? "Состав.ПодавлениеПроверок" //$NON-NLS-1$ //$NON-NLS-2$
                    : entryPath != null ? elementLabel(mdObject, entryPath) + ".ПодавлениеПроверок" //$NON-NLS-1$
                    : "ПодавлениеПроверок"; //$NON-NLS-1$
                TARGETS.put(match, new Target(project, owner, entryPath, property, uid));
                matches.add(match);
            }
            for (EObject child : node.eContents())
                walk(child, owner, path, mdObject, model, matches, monitor);
        }

        @Override public String getLabel() { return "Ссылки на проверку «" + uid.getCheckId() + "» — " + project.getName(); } //$NON-NLS-1$ //$NON-NLS-2$
        @Override public boolean canRerun() { return true; }
        @Override public boolean canRunInBackground() { return true; }
        @Override public ISearchResult getSearchResult() { return result; }
    }

    /**
     * Штатное дерево поиска строится по resolveMatchObject(), а не по topObjectId.
     * Для него возвращаем владельца метаданных; ID записи сохраняет различие подавлений
     * одного кода у разных вложенных элементов того же объекта.
     */
    private static final class SuppressionMatch extends TextSearchModelMatch
    {
        private final long metadataOwnerId;

        SuppressionMatch(IBmModel model, String text, IBmObject owner, long suppressionId,
            EStructuralFeature feature)
        {
            super(model, text, owner.bmGetTopObject().bmGetId(), suppressionId, feature, 0, text.length());
            metadataOwnerId = owner.bmGetId();
        }

        @Override public Optional<IBmObject> resolveMatchObject()
        {
            return resolveObjectById(metadataOwnerId);
        }
    }

    private static String stringFeature(EObject object, String name)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(name);
        Object value = feature != null ? object.eGet(feature) : null;
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** Как в GoToDefinition: BM принимает FQN верхнего объекта, формы и макеты ищем по коллекциям. */
    private static IBmObject resolveOwner(IBmTransaction transaction, String fqn)
    {
        IBmObject direct = transaction.getTopObjectByFqn(fqn);
        if (direct != null)
            return direct;
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 4)
            return null;
        EObject object = transaction.getTopObjectByFqn(parts[0] + "." + parts[1]); //$NON-NLS-1$
        for (int index = 2; index + 1 < parts.length && object != null; index += 2)
        {
            String featureName = MdTypeMapping.subObjectTypeToEmfFeature(parts[index]);
            EStructuralFeature feature = featureName != null ? object.eClass().getEStructuralFeature(featureName) : null;
            Object children = feature != null ? object.eGet(feature) : null;
            object = null;
            if (children instanceof Iterable<?> items)
                for (Object child : items)
                    if (child instanceof EObject nested && parts[index + 1].equalsIgnoreCase(stringFeature(nested, "name"))) //$NON-NLS-1$
                    {
                        object = nested;
                        break;
                    }
        }
        return object instanceof IBmObject bm ? bm : null;
    }

    private static String elementLabel(EObject object, String path)
    {
        List<String> labels = new ArrayList<>();
        for (String segment : path.split("/")) //$NON-NLS-1$
        {
            int separator = segment.indexOf(':');
            String featureName = separator >= 0 ? segment.substring(0, separator) : segment;
            String name = separator >= 0 ? segment.substring(separator + 1) : ""; //$NON-NLS-1$
            EStructuralFeature feature = object != null ? object.eClass().getEStructuralFeature(featureName) : null;
            labels.add(featureLabel(feature, featureName)
                + (name.isEmpty() ? "" : "." + name)); //$NON-NLS-1$ //$NON-NLS-2$
            Object children = feature != null ? object.eGet(feature) : null;
            object = null;
            if (children instanceof Iterable<?> items)
                for (Object child : items)
                    if (child instanceof EObject nested && name.equals(stringFeature(nested, "name"))) //$NON-NLS-1$
                        object = nested;
        }
        return String.join(".", labels); //$NON-NLS-1$
    }

    private static String featureLabel(EStructuralFeature feature, String featureName)
    {
        // У части коллекций (например commands) EDT возвращает техническое имя.
        // Используем общий словарь типов МД, а не отдельный список переводов поиска.
        for (String type : List.of("Attribute", "TabularSection", "Form", "Template", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Command", "Dimension", "Resource", "EnumValue")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if (featureName.equals(MdTypeMapping.subObjectTypeToEmfFeature(type)))
                return MdTypeMapping.ruSingularToGroupPlural(MdTypeMapping.enSingToRu(type));
        return feature != null ? Functions.featureToLabel().apply(feature) : featureName;
    }
}
