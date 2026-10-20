package tormozit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IPainter;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ICharacterPairMatcher;
import org.eclipse.jface.text.source.ICharacterPairMatcherExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.MatchingCharacterPainter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ActiveShellExpression;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerActivation;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.texteditor.SourceViewerDecorationSupport;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.ui.editor.model.XtextDocument;
import org.eclipse.xtext.ui.editor.model.IXtextModelListener;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.ql.model.AbstractExpression;
import com._1c.g5.v8.dt.ql.model.CaseBody;
import com._1c.g5.v8.dt.ql.model.CaseOperationExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchema;

/** Общие связи слов и символов QL для литералов BSL и самостоятельных редакторов запроса. */
final class QueryBracketSupport
{
    private static final String SESSION_KEY = "tormozit.queryBrackets.session"; //$NON-NLS-1$
    private static final String HANDLERS_KEY = "tormozit.queryBrackets.handlers"; //$NON-NLS-1$
    private static final String ANNOTATION_TYPE = "org.eclipse.xtext.ui.editor.defaultOccurrenceAnnotation"; //$NON-NLS-1$
    private static final Map<IDocument, Index> INDICES = Collections.synchronizedMap(new WeakHashMap<>());

    private QueryBracketSupport() {}

    private static record Index(long stamp, List<List<int[]>> groups, List<int[]> pairs) {}

    /** В модуле координаты переносит провайдер литерала; в обычном QL-редакторе они прямые. */
    static IRegion findPair(IDocument document, int offset)
    {
        if (!ComfortSettings.isQueryLiteralHighlightingEnabled() || document == null
            || offset < 0 || offset >= document.getLength())
            return null;
        if (isBslDocument(document))
            return BslQueryLiteralSupport.queryBracketPair(document, offset);
        Index index = nativeIndex(document);
        if (index == null)
            return null;
        for (int[] pair : index.pairs())
            if (pair[0] == offset || pair[1] == offset)
                return new Region(pair[0], pair[1] - pair[0] + 1);
        return null;
    }

    /** Только готовое дерево текущего текста, без создания редактора и запуска разбора. */
    private static Index nativeIndex(IDocument document)
    {
        if (!(document instanceof XtextDocument xtext) || isBslDocument(document))
            return null;
        long stamp = document instanceof IDocumentExtension4 stamped
            ? stamped.getModificationStamp() : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
        Index cached = INDICES.get(document);
        if (cached != null && stamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP && cached.stamp() == stamp)
            return cached;
        return xtext.tryReadOnly(resource ->
        {
            if (resource == null || resource.getParseResult() == null || resource.getContents().isEmpty())
                return null;
            EObject object = resource.getContents().get(0);
            if (!(object instanceof QuerySchema) && !(object instanceof AbstractExpression))
                return null;
            var root = resource.getParseResult().getRootNode();
            if (!root.getText().equals(document.get()))
                return null;
            Index result = new Index(stamp, collect(root), collectPairs(root));
            INDICES.put(document, result);
            return result;
        });
    }

    /** Прямая ссылка на класс EDT требует отсутствующий в build path HandlyXtextDocument. */
    private static boolean isBslDocument(IDocument document)
    {
        boolean bsl = false;
        for (Class<?> type = document == null ? null : document.getClass(); type != null; type = type.getSuperclass())
        {
            if ("com._1c.g5.v8.dt.bsl.ui.editor.BslXtextDocument".equals(type.getName())) //$NON-NLS-1$
            {
                bsl = true;
                break;
            }
        }
        return bsl;
    }

    /** Общий фокусный вход охватывает модальные и встроенные редакторы QL. */
    static void focusNative(StyledText text)
    {
        text.getDisplay().asyncExec(() ->
        {
            if (text.isDisposed() || !text.isFocusControl())
                return;
            ISourceViewer viewer = TextEditor.resolveViewerFromFocus(text);
            if (viewer == null)
                viewer = TextEditor.resolveViewerFromDialog(text);
            if (viewer != null && viewer.getTextWidget() == text)
                installNativeViewer(viewer);
        });
    }

    static void installNativeViewer(ISourceViewer viewer)
    {
        if (!ComfortSettings.isQueryLiteralHighlightingEnabled()
            || viewer == null || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed()
            || nativeIndex(viewer.getDocument()) == null)
            return;
        StyledText text = viewer.getTextWidget();
        if (!(text.getData(SESSION_KEY) instanceof NativeSession))
        {
            NativeSession session = new NativeSession(viewer);
            text.setData(SESSION_KEY, session);
            session.install();
        }
        if (text.isFocusControl())
            installCommands(text.getShell());
        // EmbeddedEditorFactory хранит decoration support локально. Штатный painter находится
        // в TextViewer.fPaintManager.fPainters (подтверждено исходниками JFace в кэше).
        Object manager = Global.getField(viewer, "fPaintManager"); //$NON-NLS-1$
        Object painters = Global.getField(manager, "fPainters"); //$NON-NLS-1$
        if (painters instanceof List<?> list)
        {
            for (Object candidate : new ArrayList<>(list))
            {
                if (candidate instanceof MatchingCharacterPainter painter)
                {
                    Object matcher = Global.getField(painter, "fMatcher"); //$NON-NLS-1$
                    if (matcher instanceof ICharacterPairMatcher stock && !(stock instanceof QueryPairMatcher))
                    {
                        Global.setField(painter, "fMatcher", new QueryPairMatcher(stock)); //$NON-NLS-1$
                        painter.paint(IPainter.CONFIGURATION);
                    }
                }
            }
        }
    }

    static boolean handleNativeCommand(boolean withSelection)
    {
        Display display = Display.getCurrent();
        if (display != null && display.getFocusControl() instanceof StyledText text
            && text.getData(SESSION_KEY) instanceof NativeSession session
            && ComfortSettings.isQueryLiteralHighlightingEnabled())
        {
            jump(session.viewer, withSelection);
            return true;
        }
        return false;
    }

    /** Связанную конструкцию рисует общий сеанс, обычный поиск текста сюда не добавляется. */
    static boolean hasLogicalSelection(StyledText text)
    {
        return ComfortSettings.isQueryLiteralHighlightingEnabled()
            && text.getData(SESSION_KEY) instanceof NativeSession session && !session.annotations.isEmpty();
    }

    private static boolean jump(ISourceViewer viewer, boolean withSelection)
    {
        Point selection = viewer.getSelectedRange();
        int anchor = selection.y == 0 ? selection.x - 1 : selection.x;
        IRegion pair = findPair(viewer.getDocument(), anchor);
        if (pair == null && selection.y == 0)
        {
            anchor = selection.x;
            pair = findPair(viewer.getDocument(), anchor);
        }
        if (pair == null)
            return false;
        int target = anchor == pair.getOffset() ? pair.getOffset() + pair.getLength() - 1 : pair.getOffset();
        selectBracket(viewer, selection, target, withSelection);
        return true;
    }

    static void selectBracket(ITextViewer viewer, Point selection, int target, boolean withSelection)
    {
        int offset = withSelection ? Math.min(selection.x, target) : target;
        int length = withSelection ? Math.max(selection.x + selection.y, target + 1) - offset : 1;
        Global.tempLog("query-bracket-navigation", "from=" + selection + ", target=" + target //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ", range=" + offset + ":" + length); //$NON-NLS-1$ //$NON-NLS-2$
        if (viewer instanceof ITextViewerExtension5 extension)
        {
            // false означает «диапазон уже виден», а не ошибку раскрытия.
            boolean changed = extension.exposeModelRange(new Region(offset, length));
            Global.tempLog("query-bracket-navigation", "projection changed=" + changed); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
            viewer.resetVisibleRegion();
        viewer.setSelectedRange(offset, length);
        viewer.revealRange(offset, length);
    }

    private static void installCommands(Shell shell)
    {
        if (!ComfortSettings.isQueryLiteralHighlightingEnabled())
        {
            releaseCommands(shell);
            return;
        }
        if (shell.getData(HANDLERS_KEY) instanceof CommandScope)
            return;
        IHandlerService service = PlatformUI.getWorkbench().getService(IHandlerService.class);
        if (service == null)
            return;
        List<IHandlerActivation> activations = new ArrayList<>();
        for (String name : new String[] { "ForwardBslBracketSearchHandler", "BackBslBracketSearchHandler", //$NON-NLS-1$ //$NON-NLS-2$
            "ForwardBslBracketSearchWithSelectionHandler", "BackBslBracketSearchWithSelectionHandler" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            boolean select = name.contains("WithSelection"); //$NON-NLS-1$
            activations.add(service.activateHandler("com._1c.g5.v8.dt.bsl.ui.menu." + name, new AbstractHandler() //$NON-NLS-1$
            {
                @Override public boolean isEnabled()
                {
                    return ComfortSettings.isQueryLiteralHighlightingEnabled()
                        && shell.getDisplay().getFocusControl() instanceof StyledText text
                        && text.getShell() == shell && text.getData(SESSION_KEY) instanceof NativeSession;
                }
                @Override public Object execute(ExecutionEvent event)
                {
                    handleNativeCommand(select);
                    return null;
                }
            }, new ActiveShellExpression(shell)));
        }
        // Штатные привязки скобок объявлены именно в XtextEditorScope (bsl-ui/plugin.xml).
        // Как и у EmbeddedBslXtextContextHook, контекст живёт только при фокусе в тексте.
        IContextService contexts = PlatformUI.getWorkbench().getService(IContextService.class);
        IContextActivation context = contexts == null ? null
            : contexts.activateContext("org.eclipse.xtext.ui.XtextEditorScope", new ActiveShellExpression(shell)); //$NON-NLS-1$
        shell.setData(HANDLERS_KEY, new CommandScope(service, activations, contexts, context));
        if (!Boolean.TRUE.equals(shell.getData(HANDLERS_KEY + ".cleanup"))) //$NON-NLS-1$
        {
            shell.setData(HANDLERS_KEY + ".cleanup", Boolean.TRUE); //$NON-NLS-1$
            shell.addDisposeListener(event -> releaseCommands(shell));
        }
    }

    private static void releaseCommands(Shell shell)
    {
        if (shell.getData(HANDLERS_KEY) instanceof CommandScope scope)
        {
            scope.activations().forEach(scope.service()::deactivateHandler);
            if (scope.context() != null)
                scope.contexts().deactivateContext(scope.context());
            shell.setData(HANDLERS_KEY, null);
        }
    }

    private static record CommandScope(IHandlerService service, List<IHandlerActivation> activations,
        IContextService contexts, IContextActivation context) {}

    /** В bsl-ui заданы и латинские, и русские варианты штатных сочетаний. */
    private static boolean isBracketKey(int key)
    {
        int upper = Character.toUpperCase(key);
        return upper == '[' || upper == ']' || upper == 'Х' || upper == 'Ъ';
    }

    private static final class NativeSession
    {
        final ISourceViewer viewer;
        final List<Annotation> annotations = new ArrayList<>();
        IAnnotationModel annotationModel;
        boolean refreshPending;

        NativeSession(ISourceViewer viewer) { this.viewer = viewer; }

        void install()
        {
            StyledText text = viewer.getTextWidget();
            org.eclipse.jface.viewers.ISelectionChangedListener listener = event -> scheduleRefresh();
            viewer.getSelectionProvider().addSelectionChangedListener(listener);
            // Каретка меняется и без уведомления ISelectionProvider (обычные стрелки/щелчки).
            text.addCaretListener(event -> scheduleRefresh());
            XtextDocument document = (XtextDocument)viewer.getDocument();
            IXtextModelListener modelListener = resource -> text.getDisplay().asyncExec(() ->
            {
                if (!text.isDisposed())
                {
                    INDICES.remove(document);
                    refresh();
                }
            });
            document.addModelListener(modelListener);
            text.addDisposeListener(event ->
            {
                viewer.getSelectionProvider().removeSelectionChangedListener(listener);
                document.removeModelListener(modelListener);
                replace(Map.of());
                INDICES.remove(viewer.getDocument());
            });
            Shell shell = text.getShell();
            text.addFocusListener(new FocusAdapter()
            {
                @Override public void focusGained(FocusEvent event)
                {
                    installCommands(shell);
                    refresh();
                }
                @Override public void focusLost(FocusEvent event)
                {
                    text.getDisplay().asyncExec(() ->
                    {
                        if (!shell.isDisposed()
                            && !(shell.getDisplay().getFocusControl() instanceof StyledText focused
                                && focused.getShell() == shell && focused.getData(SESSION_KEY) instanceof NativeSession))
                            releaseCommands(shell);
                    });
                }
            });
            // VerifyKey срабатывает до StyledText. Командный канал выше обслуживает Win32-акселераторы.
            text.addVerifyKeyListener(event ->
            {
                if ((event.stateMask & SWT.MOD1) != 0 && (event.stateMask & ~(SWT.MOD1 | SWT.SHIFT)) == 0
                    && isBracketKey(event.keyCode)
                    && ComfortSettings.isQueryLiteralHighlightingEnabled()
                    && jump(viewer, (event.stateMask & SWT.SHIFT) != 0))
                    event.doit = false;
            });
            if (text.isFocusControl())
                installCommands(shell);
            refresh();
        }

        void scheduleRefresh()
        {
            if (refreshPending)
                return;
            refreshPending = true;
            viewer.getTextWidget().getDisplay().asyncExec(() ->
            {
                refreshPending = false;
                refresh();
            });
        }

        void refresh()
        {
            if (viewer.getTextWidget().isDisposed())
                return;
            Map<Annotation, Position> result = new LinkedHashMap<>();
            if (ComfortSettings.isQueryLiteralHighlightingEnabled() && TextEditorOccurrencesSupport.isEnabled())
            {
                Index index = nativeIndex(viewer.getDocument());
                Point selected = viewer.getSelectedRange();
                if (index != null)
                {
                    for (List<int[]> group : index.groups())
                    {
                        boolean hit = false;
                        for (int[] range : group)
                            hit |= selected.y == 0 && selected.x >= range[0] && selected.x <= range[0] + range[1]
                                || selected.x == range[0] && selected.y == range[1];
                        if (!hit)
                            continue;
                        for (int[] range : group)
                            result.put(new Annotation(ANNOTATION_TYPE, false, ""), new Position(range[0], range[1])); //$NON-NLS-1$
                        break;
                    }
                }
            }
            replace(result);
            installNativeViewer(viewer);
        }

        void replace(Map<Annotation, Position> next)
        {
            IAnnotationModel current = viewer.getAnnotationModel();
            if (annotationModel != null && annotationModel != current)
                annotations.forEach(annotationModel::removeAnnotation);
            if (current instanceof IAnnotationModelExtension extension)
                extension.replaceAnnotations(annotations.toArray(new Annotation[0]), next);
            else if (current != null)
            {
                annotations.forEach(current::removeAnnotation);
                next.forEach(current::addAnnotation);
            }
            annotations.clear();
            annotations.addAll(next.keySet());
            annotationModel = current;
        }
    }

    static boolean isKeyword(String word)
    {
        return word.equalsIgnoreCase("ВЫБОР") || word.equalsIgnoreCase("CASE") //$NON-NLS-1$ //$NON-NLS-2$
            || word.equalsIgnoreCase("КОГДА") || word.equalsIgnoreCase("WHEN") //$NON-NLS-1$ //$NON-NLS-2$
            || word.equalsIgnoreCase("ИНАЧЕ") || word.equalsIgnoreCase("ELSE") //$NON-NLS-1$ //$NON-NLS-2$
            || word.equalsIgnoreCase("КОНЕЦ") || word.equalsIgnoreCase("END"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static List<List<int[]>> collect(org.eclipse.xtext.nodemodel.ICompositeNode root)
    {
        if (root == null)
            return List.of();
        Map<CaseOperationExpression, List<int[]>> groups = new LinkedHashMap<>();
        for (var leaf : root.getLeafNodes())
        {
            if (leaf.isHidden() || !(leaf.getGrammarElement() instanceof Keyword)
                || !isKeyword(leaf.getText()))
                continue;
            CaseOperationExpression owner = caseOwner(NodeModelUtils.findActualSemanticObjectFor(leaf));
            if (owner != null)
                groups.computeIfAbsent(owner, key -> new ArrayList<>())
                    .add(new int[] { leaf.getOffset(), leaf.getLength() });
        }
        List<List<int[]>> result = new ArrayList<>();
        for (List<int[]> group : groups.values())
            result.add(List.copyOf(group));
        return List.copyOf(result);
    }

    static List<int[]> collectPairs(org.eclipse.xtext.nodemodel.ICompositeNode root)
    {
        List<int[]> result = new ArrayList<>();
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        if (root != null)
        {
            for (var leaf : root.getLeafNodes())
            {
                String value = leaf.getText();
                // Расширения запроса СКД могут оставлять отдельные { и } без Keyword.
                // Строки и комментарии содержат свои ограничители, поэтому сюда не попадают.
                boolean curly = "{".equals(value) || "}".equals(value); //$NON-NLS-1$ //$NON-NLS-2$
                if (!curly && (leaf.isHidden() || !(leaf.getGrammarElement() instanceof Keyword)))
                    continue;
                // В грамматике QL есть составные знаки ".(" и ")." — скобка внутри их листа.
                for (int i = 0; i < value.length(); i++)
                {
                    char ch = value.charAt(i);
                    if (ch == '(' || ch == '[' || ch == '{')
                        stack.push(new int[] { leaf.getOffset() + i, ch });
                    else if ((ch == ')' || ch == ']' || ch == '}') && !stack.isEmpty())
                    {
                        int[] open = stack.peek();
                        if (open[1] == '(' && ch == ')' || open[1] == '[' && ch == ']'
                            || open[1] == '{' && ch == '}')
                        {
                            stack.pop();
                            result.add(new int[] { open[0], leaf.getOffset() + i });
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static CaseOperationExpression caseOwner(EObject object)
    {
        if (object instanceof CaseOperationExpression expression)
            return expression;
        return object instanceof CaseBody && object.eContainer() instanceof CaseOperationExpression expression
            ? expression : null;
    }

    /** Поля подтверждены AbstractDecoratedTextEditor и MatchingCharacterPainter в кэше бандлов. */
    static void installModuleEditor(BslXtextEditor editor)
    {
        Object support = Global.getField(editor, "fSourceViewerDecorationSupport"); //$NON-NLS-1$
        Object matcher = Global.getField(support, "fCharacterPairMatcher"); //$NON-NLS-1$
        if (matcher instanceof QueryPairMatcher)
            return;
        if (!(support instanceof SourceViewerDecorationSupport decoration)
            || !(matcher instanceof ICharacterPairMatcher stock))
        {
            return;
        }
        QueryPairMatcher wrapper = new QueryPairMatcher(stock);
        wrapper.bind(editor, decoration);
        decoration.setCharacterPairMatcher(wrapper);
        Object painter = Global.getField(support, "fMatchingCharacterPainter"); //$NON-NLS-1$
        if (painter instanceof MatchingCharacterPainter matching)
        {
            Global.setField(matching, "fMatcher", wrapper); //$NON-NLS-1$
            matching.paint(IPainter.CONFIGURATION);
        }
    }

    /** Добавляет пары QL к штатному рисовальщику, сохраняя его цвета и настройки. */
    private static final class QueryPairMatcher implements ICharacterPairMatcher, ICharacterPairMatcherExtension
    {
        private final ICharacterPairMatcher stock;
        private boolean queryMatch;
        private boolean queryPending;
        private int anchor;
        private BslXtextEditor editor;
        private SourceViewerDecorationSupport decoration;

        QueryPairMatcher(ICharacterPairMatcher stock)
        {
            this.stock = stock;
        }

        /** Рисовальщик создаётся при включении подсветки скобок и пересоздаётся — брать его в момент перерисовки. */
        void bind(BslXtextEditor editor, SourceViewerDecorationSupport decoration)
        {
            this.editor = editor;
            this.decoration = decoration;
        }

        /** В модуле пару считает фон: рисовальщик не должен ждать блокировку документа. */
        private IRegion pairForPaint(IDocument document, int offset)
        {
            return isBslDocument(document)
                ? BslQueryLiteralSupport.cachedQueryBracketPair(document, offset, this::repaintLater)
                : findPair(document, offset);
        }

        private void repaintLater()
        {
            if (editor == null || decoration == null || !PlatformUI.isWorkbenchRunning())
            {
                Global.tempLog("query-bracket-pair", "repaint skip unbound"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            PlatformUI.getWorkbench().getDisplay().asyncExec(() ->
            {
                ISourceViewer viewer = editor.getInternalSourceViewer();
                StyledText widget = viewer == null ? null : viewer.getTextWidget();
                Object painter = Global.getField(decoration, "fMatchingCharacterPainter"); //$NON-NLS-1$
                // После dispose рисовальщик сбрасывает fMatcher — тогда рисовать нечего.
                boolean ours = painter instanceof MatchingCharacterPainter
                    && Global.getField(painter, "fMatcher") == this; //$NON-NLS-1$
                Global.tempLog("query-bracket-pair", "repaint widget=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (widget != null && !widget.isDisposed()) + " painter=" + (painter != null) + " ours=" + ours); //$NON-NLS-1$ //$NON-NLS-2$
                if (widget != null && !widget.isDisposed() && ours)
                    ((MatchingCharacterPainter)painter).paint(IPainter.INTERNAL);
            });
        }

        @Override
        public IRegion match(IDocument document, int offset)
        {
            return match(document, offset, 0);
        }

        @Override
        public IRegion match(IDocument document, int offset, int length)
        {
            queryMatch = false;
            queryPending = false;
            if (ComfortSettings.isQueryLiteralHighlightingEnabled() && document != null && Math.abs(length) <= 1)
            {
                // Выбран один символ — он и есть опора; без выделения проверяем обе стороны каретки.
                int[] candidates = length == 0 ? new int[] { offset - 1, offset }
                    : new int[] { Math.min(offset, offset + length) };
                for (int candidate : candidates)
                {
                    IRegion pair = pairForPaint(document, candidate);
                    if (pair != null)
                    {
                        queryMatch = true;
                        anchor = candidate == pair.getOffset() ? LEFT : RIGHT;
                        return pair;
                    }
                    queryPending |= BslQueryLiteralSupport.isQueryBracketPending(document, candidate);
                }
                // Нашу пару дорисует repaintLater; штатная успела бы показать чужую.
                if (queryPending)
                    return null;
            }
            return stock instanceof ICharacterPairMatcherExtension extension
                ? extension.match(document, offset, length) : stock.match(document, offset);
        }

        @Override public int getAnchor() { return queryMatch ? anchor : stock.getAnchor(); }
        @Override public void clear() { queryMatch = false; stock.clear(); }
        @Override public void dispose() { stock.dispose(); }

        @Override
        public IRegion findEnclosingPeerCharacters(IDocument document, int offset, int length)
        {
            queryMatch = false;
            if (queryPending)
                return null;
            return stock instanceof ICharacterPairMatcherExtension extension
                ? extension.findEnclosingPeerCharacters(document, offset, length) : null;
        }

        @Override
        public boolean isMatchedChar(char ch)
        {
            return ComfortSettings.isQueryLiteralHighlightingEnabled() && "()[]{}".indexOf(ch) >= 0 //$NON-NLS-1$
                || stock instanceof ICharacterPairMatcherExtension extension && extension.isMatchedChar(ch);
        }

        @Override
        public boolean isMatchedChar(char ch, IDocument document, int offset)
        {
            return ComfortSettings.isQueryLiteralHighlightingEnabled()
                && pairForPaint(document, offset) != null
                || stock instanceof ICharacterPairMatcherExtension extension && extension.isMatchedChar(ch, document, offset);
        }

        @Override
        public boolean isRecomputationOfEnclosingPairRequired(IDocument document, IRegion current, IRegion previous)
        {
            return stock instanceof ICharacterPairMatcherExtension extension
                && extension.isRecomputationOfEnclosingPairRequired(document, current, previous);
        }
    }


}
