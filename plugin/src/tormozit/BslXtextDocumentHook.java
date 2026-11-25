package tormozit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.IDocument;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.Issue;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IDocumentProviderExtension;
import org.eclipse.ui.texteditor.IElementStateListener;
import org.eclipse.ui.texteditor.IElementStateListenerExtension;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.BslResource;
import com._1c.g5.v8.dt.bsl.util.BslUtil;
import com._1c.g5.v8.dt.bsl.validation.IBslValidationContributor.ContextHolderAdapter;

/**
 * Штатная EDT в {@code CustomXtextDocumentLocker.waitUpdatingDataModel} на UI-потоке
 * спит до 5 с ({@code Thread.sleep(25)}), пока не сменится parse result — в том числе
 * при Ctrl+S из {@code BslBreakpointMarkerUpdater.updateMarker}. Комфорт на потоке UI
 * этот цикл пропускает: сохранение не блокирует интерфейс.
 *
 * <p>Вплетение в блокировщик — в раннем бандле ({@code tormozit.early.Activator}, цель
 * {@code DOC_LOCKER}): класс грузится раньше активации основного бандла, а агента перезагрузки
 * классов в EDT нет, поэтому вплетение отсюда не срабатывало ни разу (временный лог 15.10.2026
 * 10:33). Здесь — только функция пропуска ожидания ({@link #PROP_SKIP_WAIT}); ранний бандл
 * зовёт её через {@code System.getProperties}.
 */
public final class BslXtextDocumentHook implements IStartup
{
    /** Совпадает с {@code EarlyWeaving.PROP_SKIP_WAIT} раннего бандла. */
    static final String PROP_SKIP_WAIT = "tormozit.bslXtextDoc.skipWaitUpdatingDataModel"; //$NON-NLS-1$
    /** Совпадает с {@code EarlyWeaving.PROP_ASSIST_READ_DEPTH}: глубина вплетённого чтения на потоке. */
    private static final String PROP_ASSIST_READ_DEPTH = "tormozit.bslXtextDoc.assistReadDepth"; //$NON-NLS-1$
    /** Совпадает с {@code EarlyWeaving.PROP_ASSIST_READ_OBSERVER}. */
    private static final String PROP_ASSIST_READ_OBSERVER = "tormozit.bslXtextDoc.assistReadObserver"; //$NON-NLS-1$
    /** Совпадает с {@code EarlyWeaving.PROP_DOC_LOCKER_PATCH}: итог вплетения в блокировщик. */
    private static final String PROP_DOC_LOCKER_PATCH = "tormozit.bslXtextDoc.lockerPatch"; //$NON-NLS-1$
    private static final String CMD_SAVE = "org.eclipse.ui.file.save"; //$NON-NLS-1$

    private static final AtomicInteger SAVE_SKIP = new AtomicInteger();
    private static final AtomicInteger SAVE_WAIT = new AtomicInteger();
    private static final ThreadLocal<Long> SAVE_STARTED_MS = new ThreadLocal<>();
    private static final ThreadLocal<String> SAVE_MODULE = new ThreadLocal<>();
    private static volatile boolean saveActive;
    private static volatile long saveUiUntilMs;
    private static final AtomicBoolean uiInstalled = new AtomicBoolean();

    /**
     * Наблюдатель раннего бандла: ожидание монитора ресурса и блокировки чтения во вплетённом
     * {@code readOnlyForContentAssist} (от 5 мс) и ошибки рефлексии.
     */
    private static void observeAssistRead(String event, Long ms)
    {
        if (event.startsWith("lockDiag.")) //$NON-NLS-1$
        {
            Global.tempLog("assist-lock-wait", event + " waitMs=" + ms //$NON-NLS-1$ //$NON-NLS-2$
                + " observerThread=" + Thread.currentThread().getName()); //$NON-NLS-1$
            return;
        }
        SmartContentAssistProcessor.uiBlockLog("modelSync.readLock", //$NON-NLS-1$
            event + " waitMs=" + ms + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Итог вплетения раннего бандла: {@code woven wait=1 read=1}; {@code null} — класс через хук не проходил. */
    private static String lockerPatch()
    {
        return System.getProperty(PROP_DOC_LOCKER_PATCH);
    }

    /**
     * Как можно раньше, из {@link Activator#start}: функция пропуска ожидания должна стоять до
     * первого сохранения.
     */
    public static void install()
    {
        IncrementalEditorValidation.install();
        System.getProperties().put(PROP_SKIP_WAIT,
            (BooleanSupplier) BslXtextDocumentHook::skipWaitUpdatingDataModel);
        // #region agent log
        System.getProperties().put(PROP_ASSIST_READ_OBSERVER,
            (BiConsumer<String, Long>) BslXtextDocumentHook::observeAssistRead);
        // #endregion
    }

    /** Обход выражений для проверок; модель и анализ зависимостей остаются полными. */
    public static org.eclipse.emf.common.util.TreeIterator<EObject> validationContents(Module module)
    {
        var contents = module.eAllContents();
        var methods = IncrementalEditorValidation.methods(module);
        return methods == null ? contents : new IncrementalEditorValidation.ScopedContents(contents, methods);
    }

    /** Методы, выбранные для проверки редактора; при записи возвращаются все методы. */
    public static java.util.List<Method> validationMethods(Module module)
    {
        var methods = IncrementalEditorValidation.methods(module);
        return methods == null ? module.allMethods()
            : module.allMethods().stream().filter(methods::contains).toList();
    }

    /** Согласованный с ранним вплетением режим проверки только незаписанной модели редактора. */
    private static final class IncrementalEditorValidation
    {
        private static final String PREFIX = "tormozit.editorValidation.";
        private static final String SESSION = PREFIX + "session";
        private static final String JOB = "com._1c.g5.v8.dt.bsl.ui.editor.BslXtextDocumentProvider$BslValidationJob";
        private static final java.util.Map<IDocument, java.lang.ref.WeakReference<State>> DOCUMENT_STATES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final ThreadLocal<java.lang.ref.WeakReference<Session>> CHECK_SCOPE = new ThreadLocal<>();

        /** На ресурсе, а не в глобальном кэше: старые AST и закрытые редакторы не удерживаются. */
        private static final class State extends AdapterImpl
        {
            int edits;
            boolean baseline;
            String header;
            int methodStart = -1;
            int originalMethodCount = -1;
            int changePosition = -1;
            int changeShift;
            Session active;
            Session prepared;
            java.lang.ref.WeakReference<IParseResult> validatedParse;
            String validatedHeader;
            BodyRange range;
            final java.util.Set<String> affectedNames = new java.util.HashSet<>();
            final java.util.Set<String> validatedNames = new java.util.HashSet<>();
            final java.util.Set<String> moduleChecks = java.util.concurrent.ConcurrentHashMap.newKeySet();

            @Override public boolean isAdapterForType(Object type) { return type == State.class; }
        }

        private record Session(BslResource resource, IParseResult parse, int edits, boolean partial,
            java.util.Set<Method> methods, int position, int shift) { }

        private record BodyRange(int methodStart, int signatureEnd, int end, String header, String closing)
        {
            boolean matches(ICompositeNode node, INode signature, String currentHeader, String source)
            {
                return node != null && signature != null && methodStart == node.getOffset()
                    && signatureEnd == signature.getEndOffset() && header != null && header.equals(currentHeader)
                    && closing != null && !closing.isEmpty() && source.regionMatches(end, closing, 0, closing.length());
            }

            BodyRange shifted(int delta) { return new BodyRange(methodStart, signatureEnd, end + delta, header, closing); }
        }

        private static boolean scopedCheck(Object check)
        {
            String type = check.getClass().getName();
            return type.startsWith("tormozit.checks.StaticFeatureAccessChecks$") || java.util.Set.of(
                "tormozit.checks.BslValueTypeCheck", "tormozit.checks.BslNumericIndexCheck",
                "tormozit.checks.BslForEachCollectionCheck", "tormozit.checks.BslUnknownLiteralCheck",
                "tormozit.checks.BslGotoLabelCheck", "tormozit.checks.BslQueryResultFieldCheck").contains(type);
        }

        static void install()
        {
            var properties = System.getProperties();
            properties.put(PREFIX + "before", (java.util.function.Consumer<Object[]>) IncrementalEditorValidation::beforeUpdate);
            properties.put(PREFIX + "mark", (java.util.function.BiConsumer<Object, Object>) IncrementalEditorValidation::mark);
            properties.put(PREFIX + "filter", (java.util.function.BiFunction<Object, Object, Object>) IncrementalEditorValidation::filter);
            properties.put(PREFIX + "finish", (java.util.function.BiConsumer<Object, Object>) IncrementalEditorValidation::finish);
            properties.put(PREFIX + "issuesBegin", (java.util.function.BiConsumer<Object, Object>) IncrementalEditorValidation::issuesBegin);
            properties.put(PREFIX + "issuesEnd", (java.util.function.BiConsumer<Object, Object>) IncrementalEditorValidation::issuesEnd);
            properties.put(PREFIX + "graph", (java.util.function.BiFunction<Object, Object, Object>) IncrementalEditorValidation::graph);
            properties.put(PREFIX + "offset", (java.util.function.BiFunction<Object, Object, Object>) (original, target) ->
            {
                Session session = session(target);
                return session == null ? original : session.position();
            });
            properties.put(PREFIX + "shift", (java.util.function.BiFunction<Object, Object, Object>) (original, target) ->
            {
                Session session = session(target);
                return session == null ? original : session.shift();
            });
            properties.put(PREFIX + "module", (java.util.function.Consumer<Object[]>) event ->
            {
                if (!(event[1] instanceof Module module) || !(module.eResource() instanceof BslResource resource))
                    return;
                State state = (State)EcoreUtil.getAdapter(resource.eAdapters(), State.class);
                if (state == null)
                    return;
                synchronized (state)
                {
                    if (state.active == null)
                        return;
                    String code = (String)event[0];
                    boolean scoped = scopedCheck(event[2]);
                    if (Boolean.TRUE.equals(event[3]))
                    {
                        if (scoped && state.active.partial()) CHECK_SCOPE.set(new java.lang.ref.WeakReference<>(state.active));
                        else CHECK_SCOPE.remove();
                        if (scoped) state.moduleChecks.remove(code); else state.moduleChecks.add(code);
                    }
                    else
                    {
                        CHECK_SCOPE.remove();
                    }
                }
            });
            properties.put(PREFIX + "replace", (java.util.function.BiFunction<Object, Object, Object>) (original, argument) ->
            {
                if (Boolean.TRUE.equals(original))
                    return true;
                if (argument instanceof Object[] values && values[0] instanceof String code && values[1] instanceof IDocument document)
                {
                    var reference = DOCUMENT_STATES.get(document);
                    State state = reference == null ? null : reference.get();
                    return state != null && state.moduleChecks.contains(code);
                }
                return false;
            });
            properties.put(PREFIX + "position", (java.util.function.BiConsumer<Object, Object>) IncrementalEditorValidation::position);
            // При записи во время проверки частичный результат также не публикуется в «Проблемы».
            properties.put(PREFIX + "stamp", (java.util.function.BiFunction<Object, Object, Object>) (original, target) ->
            {
                if (target instanceof BslResource resource)
                {
                    State state = (State)EcoreUtil.getAdapter(resource.eAdapters(), State.class);
                    if (state != null)
                        synchronized (state)
                        {
                            if (state.active != null && state.active.partial() && resource.getContextHolder() != null
                                && resource.getContextHolder().getContext().get(SESSION) == state.active)
                                return -1L;
                        }
                }
                return original;
            });
        }

        private static State state(BslResource resource)
        {
            State state = (State)EcoreUtil.getAdapter(resource.eAdapters(), State.class);
            if (state == null)
            {
                state = new State();
                resource.eAdapters().add(state);
            }
            return state;
        }

        private static java.util.Set<Method> methods(Module module)
        {
            if (!(module.eResource() instanceof BslResource resource))
                return null;
            State state = (State)EcoreUtil.getAdapter(resource.eAdapters(), State.class);
            var current = CHECK_SCOPE.get();
            if (state == null || current == null)
                return null;
            synchronized (state)
            {
                Session session = state.active;
                return session != null && current.get() == session && session.partial() && session.parse() == resource.getParseResult()
                    && state.edits == session.edits() && resource.getContextHolder() != null
                    && resource.getContextHolder().getContext().get(SESSION) == session ? session.methods() : null;
            }
        }

        private static Session session(Object target)
        {
            BslResource resource = target instanceof BslResource value ? value
                : target instanceof EObject object && object.eResource() instanceof BslResource value ? value : null;
            if (resource == null || resource.getContextHolder() == null)
                return null;
            Object candidate = resource.getContextHolder().getContext().get(SESSION);
            if (!(candidate instanceof Session session) || !session.partial() || session.parse() != resource.getParseResult())
                return null;
            State state = state(resource);
            synchronized (state)
            {
                return state.active == session && state.edits == session.edits() ? session : null;
            }
        }

        /** И старые вызываемые методы нужны после удаления вызова в одной из накопленных правок. */
        private static Object graph(Object original, Object target)
        {
            Session session = session(target);
            if (session == null || !(original instanceof java.util.Collection<?> collection))
                return original;
            java.util.Set<Object> result = new java.util.LinkedHashSet<>(collection);
            Method current = session.resource().getReparseMethod();
            for (Method method : session.methods())
                if (method != current && !current.getCallees().contains(method))
                    result.add(method);
            return result;
        }

        private static String methodKey(Method method)
        {
            return method.getUniqueName() == null ? method.getName() : method.getUniqueName();
        }

        private static final class ScopedContents implements org.eclipse.emf.common.util.TreeIterator<EObject>
        {
            private final org.eclipse.emf.common.util.TreeIterator<EObject> source;
            private final java.util.Set<Method> methods;
            private EObject next;

            ScopedContents(org.eclipse.emf.common.util.TreeIterator<EObject> source, java.util.Set<Method> methods)
            {
                this.source = source;
                this.methods = methods;
            }

            @Override public boolean hasNext()
            {
                while (next == null && source.hasNext())
                {
                    EObject candidate = source.next();
                    if (candidate instanceof Method method && !methods.contains(method))
                        source.prune();
                    else
                        next = candidate;
                }
                return next != null;
            }

            @Override public EObject next()
            {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                EObject result = next;
                next = null;
                return result;
            }

            @Override public void prune()
            {
                if (next != null) throw new IllegalStateException("prune after hasNext");
                source.prune();
            }
        }

        private static boolean patchesReady()
        {
            if (!Integer.valueOf(2).equals(System.getProperties().get(PREFIX + "revision")))
                return false;
            for (String target : java.util.List.of("com._1c.g5.v8.dt.bsl.resource.BslResource", JOB, JOB + "$1",
                "com._1c.g5.v8.dt.bsl.validation.BslCancelableDiagnostician",
                "com._1c.g5.v8.dt.bsl.validation.BslNotifyingResourceValidator",
                "com.e1c.g5.v8.dt.internal.check.CheckExecutor"))
                if (!Boolean.TRUE.equals(System.getProperties().get(PREFIX + "patch." + target)))
                    return false;
            return true;
        }

        private static String header(Method method)
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
            INode end = BslUtil.getMethodSignatureLastNode(method);
            if (node == null || end == null)
                return null;
            return node.getRootNode().getText().substring(node.getOffset(), end.getEndOffset());
        }

        /** Keyword конца метода; для горячего разбора согласовано исключение ExtName после точки с новой строки. */
        private static int bodyEnd(Method method, ICompositeNode node)
        {
            if (node == null) return -1;
            boolean function = method instanceof com._1c.g5.v8.dt.bsl.model.Function;
            String ruleName = function ? "Function" : "Procedure";
            String english = function ? "EndFunction" : "EndProcedure";
            String russian = function ? "КонецФункции" : "КонецПроцедуры";
            int end = -1;
            for (var leaf : node.getLeafNodes())
                if (!leaf.isHidden() && leaf.getGrammarElement() instanceof org.eclipse.xtext.Keyword keyword
                    && (english.equalsIgnoreCase(keyword.getValue()) || russian.equalsIgnoreCase(keyword.getValue())))
                {
                    var rule = EcoreUtil2.getContainerOfType(keyword, org.eclipse.xtext.ParserRule.class);
                    if (rule != null && ruleName.equals(rule.getName()))
                        end = leaf.getOffset();
                    else if (rule != null && "ExtName".equals(rule.getName())
                        && afterDotOnNewLine(node.getRootNode().getText(), leaf.getOffset()))
                    {
                        end = leaf.getOffset();
                    }
                }
            return end;
        }

        private static boolean afterDotOnNewLine(String source, int offset)
        {
            int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
            if (lineStart == 0)
                return false;
            for (int index = lineStart; index < offset; index++)
                if (!Character.isWhitespace(source.charAt(index)))
                    return false;
            int previous = offset - 1;
            while (previous >= 0 && Character.isWhitespace(source.charAt(previous)))
                previous--;
            return previous >= 0 && source.charAt(previous) == '.';
        }

        /** Вызов внутри updateWriteLock, непосредственно перед штатным обновлением ресурса. */
        private static void beforeUpdate(Object[] event)
        {
            if (!(event[0] instanceof BslResource resource))
                return;
            State state = state(resource);
            synchronized (state)
            {
                try
                {
                    int offset = (Integer)event[1];
                    int removed = (Integer)event[2];
                    String inserted = event[3] instanceof String text ? text : "";
                    ICompositeNode root = resource.getParseResult() == null ? null : resource.getParseResult().getRootNode();
                    // То же условие isNullEdit, что у штатного BslPartialParsingHelper.
                    // Синхронизация автодополнения вызывает также update(0, 0, "").
                    if (removed == inserted.length() && (removed == 0 || root != null
                        && root.getText().regionMatches(offset, inserted, 0, removed)))
                    {
                        return;
                    }
                    String previousHeader = state.header;
                    BodyRange previousRange = state.range;
                    state.edits++;
                    if (state.edits == 1 && resource.getParseResult() != null
                        && resource.getParseResult().getRootASTElement() instanceof Module module)
                        state.originalMethodCount = module.allMethods().size();
                    state.header = null;
                    state.range = null;
                    state.changePosition = state.changePosition < 0 ? offset : Math.min(state.changePosition, offset);
                    state.changeShift += inserted.length() - removed;
                    if (!state.baseline || resource.getParseResult() == null || state.edits > 1 && previousHeader == null)
                        return;
                    var leaf = NodeModelUtils.findLeafNodeAtOffset(root, offset);
                    if (leaf == null || leaf.getText().stripLeading().startsWith("//"))
                        return;
                    Method method = EcoreUtil2.getContainerOfType(NodeModelUtils.findActualSemanticObjectFor(leaf), Method.class);
                    // При неполном операторе semantic object может отсутствовать; родительский
                    // узел метода в node model при этом остаётся (в том числе у пустого метода).
                    if (method == null)
                        for (INode owner = leaf.getParent(); owner != null; owner = owner.getParent())
                            if (owner.getSemanticElement() instanceof Method value) { method = value; break; }
                    if (method == null)
                        return;
                    ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(method);
                    INode signature = BslUtil.getMethodSignatureLastNode(method);
                    String currentHeader = header(method);
                    int parsedEnd = bodyEnd(method, methodNode);
                    BodyRange range = previousRange;
                    if (parsedEnd >= 0 && methodNode != null && signature != null)
                    {
                        var closing = NodeModelUtils.findLeafNodeAtOffset(root, parsedEnd);
                        if (closing != null)
                            range = new BodyRange(methodNode.getOffset(), signature.getEndOffset(), parsedEnd, currentHeader, closing.getText());
                    }
                    boolean verified = range != null && range.matches(methodNode, signature, currentHeader, root.getText());
                    int end = range == null ? -1 : range.end();
                    if (!verified || end < 0 || offset < range.signatureEnd() || offset + removed > end)
                        return;
                    String deleted = root.getText().substring(offset, offset + removed);
                    if (directives(inserted) || directives(deleted))
                        return;
                    int methodStart = methodNode.getOffset();
                    if (state.edits > 1 && (!java.util.Objects.equals(previousHeader, currentHeader) || state.methodStart != methodStart))
                    {
                        return;
                    }
                    state.methodStart = methodStart;
                    for (var caller : BslUtil.getDependMethodsTree(method))
                        if (caller instanceof Method dependent) state.affectedNames.add(methodKey(dependent));
                    for (Method callee : method.getCallees()) state.affectedNames.add(methodKey(callee));
                    state.affectedNames.add(methodKey(method));
                    state.header = currentHeader;
                    state.range = range.shifted(inserted.length() - removed);
                }
                catch (RuntimeException error)
                {
                    state.baseline = false;
                    state.header = null;
                    state.range = null;
                }
            }
        }

        private static boolean directives(String text)
        {
            return text.indexOf('#') >= 0 || text.indexOf('&') >= 0 || text.contains("//");
        }

        /** Вызывается только для нового адаптера, созданного самой BslValidationJob. */
        private static void mark(Object candidate, Object target)
        {
            if (!(candidate instanceof ContextHolderAdapter holder) || !(target instanceof Object[] values)
                || !(values[0] instanceof BslResource resource) || !(values[1] instanceof IDocument document))
                return;
            State state = state(resource);
            DOCUMENT_STATES.put(document, new java.lang.ref.WeakReference<>(state));
            synchronized (state)
            {
                try
                {
                    long stamp = resource.getTimeStamp();
                    Method method = resource.getReparseMethod();
                    boolean unchanged = state.edits == 0 && state.validatedParse != null
                        && state.validatedParse.get() == resource.getParseResult() && state.validatedHeader != null
                        && method != null && state.validatedHeader.equals(header(method));
                    String expectedHeader = unchanged ? state.validatedHeader : state.header;
                    String reason = !patchesReady() ? "patches" : !state.baseline ? "baseline"
                        : expectedHeader == null ? "outside-body"
                        : stamp != 0 && stamp != -1 ? "persisted-resource"
                        : resource.getResourceSet() == null || BslUtil.isBuildOperationResourceSet(resource.getResourceSet()) ? "build"
                        : !resource.isOnlyMethodReparse() || method == null ? "full-parse"
                        : state.edits > 0 && state.originalMethodCount >= 0
                            && resource.getParseResult().getRootASTElement() instanceof Module module
                            && module.allMethods().size() != state.originalMethodCount ? "method-set"
                        : resource.getParseResult().getRootASTElement() instanceof Module module && !module.getStatements().isEmpty() ? "module-statements"
                        : !expectedHeader.equals(header(method)) ? "signature" : unchanged ? "unchanged-model" : "body";
                    java.util.Set<Method> methods = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                    boolean partial = "body".equals(reason) || "unchanged-model".equals(reason);
                    if (partial)
                    {
                        for (var block : BslUtil.getDependMethodsTree(method))
                            if (block instanceof Method dependent) methods.add(dependent);
                        methods.addAll(method.getCallees());
                        methods.add(method);
                        if (resource.getParseResult().getRootASTElement() instanceof Module module)
                            for (Method candidateMethod : module.allMethods())
                                if ((unchanged ? state.validatedNames : state.affectedNames).contains(methodKey(candidateMethod)))
                                    methods.add(candidateMethod);
                    }
                    Session session = new Session(resource, resource.getParseResult(), state.edits, partial, methods,
                        unchanged ? 0 : Math.max(0, state.changePosition), unchanged ? 0 : state.changeShift);
                    state.active = session;
                    holder.getContext().put(SESSION, session);
                }
                catch (RuntimeException error)
                {
                    state.active = null;
                }
            }
        }

        /** Меняет только ответ двух условий выбора охвата; настоящий контекст остаётся установленным. */
        private static Object filter(Object original, Object target)
        {
            if (!(original instanceof ContextHolderAdapter holder))
                return original;
            BslResource resource = target instanceof BslResource value ? value
                : target instanceof EObject object && object.eResource() instanceof BslResource value ? value : null;
            if (!(holder.getContext().get(SESSION) instanceof Session session) || session.resource() != resource || !session.partial())
                return original;
            State state = state(resource);
            synchronized (state)
            {
                return state.active == session && state.edits == session.edits() && resource.getParseResult() == session.parse()
                    ? null : original;
            }
        }

        private static void finish(Object target, Object indicator)
        {
            if (!(target instanceof BslResource resource) || !(indicator instanceof CancelIndicator cancel))
                return;
            State state = state(resource);
            synchronized (state)
            {
                Session session = state.active;
                state.active = null;
                if (session == null)
                    return;
                boolean complete = !cancel.isCanceled() && resource.getParseResult() == session.parse() && state.edits == session.edits();
                state.prepared = complete ? session : null;
                if (!complete)
                    state.baseline = false;
            }
        }

        private static State documentState(Object source)
        {
            var reference = source instanceof IDocument document ? DOCUMENT_STATES.get(document) : null;
            return reference == null ? null : reference.get();
        }

        private static void issuesBegin(Object source, Object monitor)
        {
            State state = documentState(source);
            if (state == null) return;
            synchronized (state)
            {
                // Предыдущий createIssues не вернул результат: его слияние могло оборваться.
                if (state.prepared != null) state.baseline = false;
                state.prepared = null;
            }
        }

        /** Baseline принимается после слияния Issues, а не после одного расчёта модели. */
        private static void issuesEnd(Object source, Object indicator)
        {
            State state = documentState(source);
            if (state == null || !(indicator instanceof org.eclipse.core.runtime.IProgressMonitor monitor)) return;
            synchronized (state)
            {
                Session session = state.prepared;
                state.prepared = null;
                if (session == null) return;
                boolean complete = !monitor.isCanceled() && session.resource().getParseResult() == session.parse()
                    && state.edits == session.edits();
                state.baseline = complete;
                if (complete)
                {
                    state.validatedParse = new java.lang.ref.WeakReference<>(session.parse());
                    Method method = session.resource().getReparseMethod();
                    state.validatedHeader = method == null ? null : header(method);
                    state.validatedNames.clear();
                    for (Method checked : session.methods()) state.validatedNames.add(methodKey(checked));
                    state.affectedNames.clear();
                    state.edits = 0;
                    state.header = null;
                    state.changePosition = -1;
                    state.changeShift = 0;
                    state.originalMethodCount = -1;
                }
            }
        }

        /** EDT сдвигает offset, но оставляет старые line/column; пересчитываем их по текущему документу. */
        private static void position(Object source, Object candidate)
        {
            if (!(source instanceof IDocument document) || !(candidate instanceof Issue.IssueImpl issue) || issue.getOffset() == null)
                return;
            try
            {
                int offset = issue.getOffset();
                int end = Math.min(document.getLength(), offset + (issue.getLength() == null ? 0 : issue.getLength()));
                int line = document.getLineOfOffset(offset);
                int endLine = document.getLineOfOffset(end);
                issue.setLineNumber(line + 1);
                issue.setColumn(offset - document.getLineOffset(line) + 1);
                issue.setLineNumberEnd(endLine + 1);
                issue.setColumnEnd(end - document.getLineOffset(endLine) + 1);
            }
            catch (org.eclipse.jface.text.BadLocationException error)
            {
            }
        }
    }

    @Override
    public void earlyStartup()
    {
        install();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(BslXtextDocumentHook::installUi);
    }

    /**
     * Поток фонового расчёта автодополнения. Ctrl+Space считает список на UI и это
     * ожидание пропускает; фоновый расчёт того же списка должен вести себя так же,
     * иначе он отсиживает до 1000 мс там, где эталон не ждёт ничего.
     */
    private static final ThreadLocal<Boolean> ASSIST_BACKGROUND = new ThreadLocal<>();

    /** Метка на время фонового расчёта автодополнения. Снимать в {@code finally}. */
    public static void markAssistBackground(boolean on)
    {
        if (on)
            ASSIST_BACKGROUND.set(Boolean.TRUE);
        else
            ASSIST_BACKGROUND.remove();
    }

    /**
     * Вызов из вплетённого {@code waitUpdatingDataModel}: {@code true} — сразу выйти, не ждать
     * смены parse result.
     */
    public static boolean skipWaitUpdatingDataModel()
    {
        // Внутри вплетённого readOnlyForContentAssist разбор ждёт наш же монитор — смена
        // parse result до выхода невозможна, ждать её (до 1 с) бессмысленно.
        boolean ui = Display.getCurrent() != null
            || Boolean.TRUE.equals(ASSIST_BACKGROUND.get())
            || isInsideAssistRead();
        if (saveActive && SaveDebug.isEnabled())
        {
            int n = ui ? SAVE_SKIP.incrementAndGet() : SAVE_WAIT.incrementAndGet();
            if (n == 1)
            {
                SaveDebug.step("waitUpdatingDataModel", //$NON-NLS-1$
                    (ui ? "skip" : "wait") //$NON-NLS-1$ //$NON-NLS-2$
                        + " thread=" + Thread.currentThread().getName()); //$NON-NLS-1$
            }
        }
        return ui;
    }

    /** Поток сейчас внутри вплетённого {@code readOnlyForContentAssist} (стек — в раннем бандле). */
    private static boolean isInsideAssistRead()
    {
        return System.getProperties().get(PROP_ASSIST_READ_DEPTH) instanceof IntSupplier depth
            && depth.getAsInt() > 0;
    }

    private static void installUi()
    {
        if (!uiInstalled.compareAndSet(false, true))
            return;
        installSaveLog();
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, event ->
        {
            if (!saveActive && System.currentTimeMillis() > saveUiUntilMs)
                return;
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            String title = shell.getText();
            if (title == null || title.isBlank())
                return;
            boolean modal = (shell.getStyle() & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL
                | SWT.SYSTEM_MODAL)) != 0;
            if (!modal && !looksLikeJobsDialog(title))
                return;
            SaveDebug.step("modal", (modal ? "modal " : "") + title //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " jobs=" + runningJobsBrief()); //$NON-NLS-1$
        });
    }

    private static boolean looksLikeJobsDialog(String title)
    {
        String t = title.toLowerCase();
        return t.contains("ожид") //$NON-NLS-1$
            || t.contains("wait") //$NON-NLS-1$
            || t.contains("фонов") //$NON-NLS-1$
            || t.contains("операция"); //$NON-NLS-1$
    }

    private static String runningJobsBrief()
    {
        Job[] jobs = Job.getJobManager().find(null);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Job job : jobs)
        {
            if (job.getState() != Job.RUNNING)
                continue;
            n++;
            if (n > 6)
            {
                sb.append(" …"); //$NON-NLS-1$
                break;
            }
            if (sb.length() > 0)
                sb.append("; "); //$NON-NLS-1$
            sb.append(job.getName());
        }
        return n == 0 ? "none" : n + " " + sb; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void installSaveLog()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commands == null)
            return;
        commands.addExecutionListener(SAVE_COMMANDS);
    }

    private static final IExecutionListener SAVE_COMMANDS = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!CMD_SAVE.equals(commandId))
                return;
            SAVE_STARTED_MS.remove();
            SAVE_MODULE.remove();
            saveActive = false;
            SAVE_SKIP.set(0);
            SAVE_WAIT.set(0);
            IEditorPart editor = resolveEditor(event);
            BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
            StateValidationAfterSave.beforeSave(bsl);
            if (!SaveDebug.isEnabled())
                return;
            if (bsl == null)
                return;
            String module = moduleLabel(bsl);
            SAVE_STARTED_MS.set(Long.valueOf(System.currentTimeMillis()));
            SAVE_MODULE.set(module);
            saveActive = true;
            saveUiUntilMs = Long.MAX_VALUE;
            boolean dirty = editor != null && editor.isDirty();
            SaveDebug.step("save", module + " dirty=" + dirty //$NON-NLS-1$ //$NON-NLS-2$
                + " lockerPatch=" + lockerPatch()); //$NON-NLS-1$
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            endSave(commandId, "ok"); //$NON-NLS-1$
            if (CMD_SAVE.equals(commandId))
                StateValidationAfterSave.afterSave();
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            endSave(commandId, "fail"); //$NON-NLS-1$
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            endSave(commandId, "notHandled"); //$NON-NLS-1$
        }
    };

    private static void endSave(String commandId, String outcome)
    {
        if (!CMD_SAVE.equals(commandId))
            return;
        Long started = SAVE_STARTED_MS.get();
        String module = SAVE_MODULE.get();
        SAVE_STARTED_MS.remove();
        SAVE_MODULE.remove();
        if (started == null)
            return;
        long spent = System.currentTimeMillis() - started.longValue();
        String name = module != null && !module.isEmpty() ? module : "?"; //$NON-NLS-1$
        saveActive = false;
        saveUiUntilMs = System.currentTimeMillis() + 5_000L;
        SaveDebug.step("save-end", name + " " + outcome + " " + spent + "ms" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " skipWait=" + SAVE_SKIP.get() //$NON-NLS-1$
            + " waitModel=" + SAVE_WAIT.get() //$NON-NLS-1$
            + " lockerPatch=" + lockerPatch()); //$NON-NLS-1$
    }

    private static IEditorPart resolveEditor(ExecutionEvent event)
    {
        if (event != null)
        {
            try
            {
                IEditorPart fromEvent = HandlerUtil.getActiveEditor(event);
                if (fromEvent != null)
                    return fromEvent;
            }
            catch (RuntimeException ignored)
            {
            }
        }
        if (!PlatformUI.isWorkbenchRunning())
            return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null)
            return null;
        return window.getActivePage().getActiveEditor();
    }

    private static String moduleLabel(BslXtextEditor editor)
    {
        IEditorInput input = editor.getEditorInput();
        if (input instanceof IFileEditorInput fileInput)
        {
            IFile file = fileInput.getFile();
            if (file != null)
                return file.getFullPath().toString();
        }
        return input != null ? input.getName() : "?"; //$NON-NLS-1$
    }

    /**
     * После сохранения поставщик документа сбрасывает у редактора признак «состояние
     * проверено», и первая клавиша зовёт {@code validateEditorInputState}: проверке нужна
     * блокировка файла, а её держит сборка, запущенная этим же сохранением. Ввод стоит всю
     * сборку, поверх — окно «Операция пользователя находится в состоянии ожидания».
     * Проверяем состояние сразу после сохранения, пока сборка блокировку ещё не взяла.
     * Взяла — ничего не делаем: первая клавиша подождёт, как штатно.
     * <p>
     * Сброс признака может прийти и позже команды (и при автосохранении), поэтому на поставщик
     * документа сохраняемого редактора вешается слушатель — один раз на поставщик.
     */
    private static final class StateValidationAfterSave
        implements IElementStateListener, IElementStateListenerExtension
    {
        private static final String TOPIC = "save-state-validation"; //$NON-NLS-1$
        private static final java.util.Map<IDocumentProvider, Boolean> LISTENED =
            new java.util.WeakHashMap<>();
        private static java.lang.ref.WeakReference<BslXtextEditor> saving =
            new java.lang.ref.WeakReference<>(null);

        private final java.lang.ref.WeakReference<BslXtextEditor> editor;

        private StateValidationAfterSave(BslXtextEditor editor)
        {
            this.editor = new java.lang.ref.WeakReference<>(editor);
        }

        /** UI-поток, {@code preExecute} команды сохранения. */
        static void beforeSave(BslXtextEditor bsl)
        {
            saving = new java.lang.ref.WeakReference<>(bsl);
            IDocumentProvider provider = bsl == null ? null : bsl.getDocumentProvider();
            if (provider == null || LISTENED.containsKey(provider))
                return;
            LISTENED.put(provider, Boolean.TRUE);
            provider.addElementStateListener(new StateValidationAfterSave(bsl));
        }

        /** UI-поток, сразу после успешной команды сохранения. */
        static void afterSave()
        {
            validateIfFree(saving.get(), "afterSave"); //$NON-NLS-1$
        }

        @Override
        public void elementStateValidationChanged(Object element, boolean isStateValidated)
        {
            if (isStateValidated)
                return;
            BslXtextEditor bsl = editor.get();
            if (bsl == null || element == null || !element.equals(bsl.getEditorInput()))
                return;
            // Сброс приходит изнутри операции сохранения или оповещения об изменении ресурсов:
            // там дерево ресурсов занято, проверку — следующим шагом UI.
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> validateIfFree(bsl, "invalidated")); //$NON-NLS-1$
        }

        private static void validateIfFree(BslXtextEditor bsl, String why)
        {
            if (bsl == null || bsl.getSite() == null || bsl.getEditorInput() == null)
                return;
            IEditorInput input = bsl.getEditorInput();
            if (!(bsl.getDocumentProvider() instanceof IDocumentProviderExtension extension)
                || extension.isStateValidated(input))
                return;
            IFile file = input.getAdapter(IFile.class);
            if (file == null)
                return;
            String busy = conflictingRunningJob(file);
            if (busy != null)
            {
                // #region agent log
                Global.tempLog(TOPIC, why + " skip busy=" + busy + " file=" + file.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // #endregion
                return;
            }
            long started = System.nanoTime();
            boolean ok = bsl.validateEditorInputState();
            // #region agent log
            Global.tempLog(TOPIC, why + " validated ok=" + ok + " ms=" //$NON-NLS-1$ //$NON-NLS-2$
                + (System.nanoTime() - started) / 1_000_000 + " file=" + file.getName()); //$NON-NLS-1$
            // #endregion
        }

        /** Имя выполняющегося задания, чьё правило пересекается с файлом; {@code null} — свободно. */
        private static String conflictingRunningJob(IFile file)
        {
            for (Job job : Job.getJobManager().find(null))
            {
                if (job.getState() != Job.RUNNING)
                    continue;
                org.eclipse.core.runtime.jobs.ISchedulingRule rule = job.getRule();
                if (rule != null && rule.isConflicting(file)
                    || job.belongsTo(ResourcesPlugin.FAMILY_AUTO_BUILD)
                    || job.belongsTo(ResourcesPlugin.FAMILY_MANUAL_BUILD))
                    return job.getName();
            }
            return null;
        }

        @Override public void elementDirtyStateChanged(Object element, boolean isDirty) {}
        @Override public void elementContentAboutToBeReplaced(Object element) {}
        @Override public void elementContentReplaced(Object element) {}
        @Override public void elementDeleted(Object element) {}
        @Override public void elementMoved(Object originalElement, Object movedElement) {}
        @Override public void elementStateChanging(Object element) {}
        @Override public void elementStateChangeFailed(Object element) {}
    }

    private static final class SaveDebug
    {
        private static final String DEBUG_TAG = "BslModuleSave"; //$NON-NLS-1$

        private SaveDebug() {}

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void step(String phase, String detail)
        {
            if (!isEnabled())
                return;
            if (detail == null || detail.isEmpty())
                Global.log(DEBUG_TAG, phase);
            else
                Global.log(DEBUG_TAG, phase + " " + detail); //$NON-NLS-1$
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(DEBUG_TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
