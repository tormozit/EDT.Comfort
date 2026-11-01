package tormozit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.formatter.IContentFormatter;
import org.eclipse.jface.text.formatter.IFormattingStrategy;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.TypedListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.model.BinaryExpression;
import com._1c.g5.v8.dt.bsl.model.BinaryOperation;
import com._1c.g5.v8.dt.bsl.model.BooleanLiteral;
import com._1c.g5.v8.dt.bsl.model.Conditional;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.IndexAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.ReturnStatement;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.WhileStatement;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Доработки штатной команды «Форматировать» модуля BSL, issue 427.
 *
 * <p>Штатный форматтер редактора ({@code BslContentFormatter}, поле {@code fContentFormatter}
 * просмотрщика) оборачивается {@link ComfortFormatter}: если синтаксическое дерево области
 * доступно — штатное форматирование и проходы Комфорта после него («не разрывать вызов», И-ИЛИ
 * дерево, отступы и пустые строки по ИР); иначе — только порт ИР по тексту
 * ({@link BslTextFormatter}). Всё одним действием отмены.
 *
 * <p>Опции хранятся в штатном узле {@value #NODE} (рабочая область и переопределение в проекте)
 * и показываются на штатной странице «Форматирование» ({@link FormatterPageInjector}): на вкладках
 * «Отступ», «Пустые строки», «Автоперенос» и на новой вкладке «Логические выражения». Примеры
 * страницы форматируются тем же обёрнутым форматтером и поэтому сразу учитывают опции.
 */
public final class BslFormatterHook implements IStartup
{
    /** Узел настроек форматирования BSL ({@code BslPreferenceManager.PLUGIN_ID}). */
    public static final String NODE = "com.e1c.g5.v8.dt.formatter.bsl"; //$NON-NLS-1$

    public static final String KEY_CONTINUATION_INDENT = "comfortContinuationIndent"; //$NON-NLS-1$
    public static final String KEY_COMPRESS_INNER_SPACES = "comfortCompressInnerSpaces"; //$NON-NLS-1$
    public static final String KEY_COMPRESS_BLANK_LINES = "comfortCompressBlankLines"; //$NON-NLS-1$
    public static final String KEY_KEEP_INVOCATION = "comfortKeepInvocation"; //$NON-NLS-1$
    public static final String KEY_AND_OR_TREE = "comfortAndOrTree"; //$NON-NLS-1$
    public static final String KEY_AND_OR_TREE_MIN_OPS = "comfortAndOrTreeMinOps"; //$NON-NLS-1$

    public static final boolean DEFAULT_CONTINUATION_INDENT = true;
    public static final boolean DEFAULT_COMPRESS_INNER_SPACES = false;
    public static final boolean DEFAULT_COMPRESS_BLANK_LINES = false;
    public static final boolean DEFAULT_KEEP_INVOCATION = true;
    public static final boolean DEFAULT_AND_OR_TREE = false;
    public static final int DEFAULT_AND_OR_TREE_MIN_OPS = 3;

    private static final String FORMAT_COMMAND_ID = "org.eclipse.xtext.ui.FormatAction"; //$NON-NLS-1$

    private static final String STOCK_FORMATTER_CLASS =
        "com.e1c.g5.v8.dt.formatter.bsl.ui.BslContentFormatter"; //$NON-NLS-1$

    private static final String TEMP_LOG = "bsl-format"; //$NON-NLS-1$

    private static final String SYNTAX_ERROR = "syntaxError@"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null)
                commands.addExecutionListener(new FormatCommandListener());
            FormatterPageInjector.install(display);
        });
    }

    /** Перед штатной командой «Форматировать» оборачивает форматтер активного модуля. */
    private static final class FormatCommandListener implements IExecutionListener
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!FORMAT_COMMAND_ID.equals(commandId))
                return;
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window == null ? null : window.getActivePage();
                BslXtextEditor editor = page == null ? null : GetRef.getActiveBslEditor(page.getActivePart());
                if (editor != null)
                    install(editor.getInternalSourceViewer());
            }
            catch (RuntimeException e)
            {
                Global.tempLog(TEMP_LOG, "preExecute failed: " + e); //$NON-NLS-1$
            }
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            // не нужно
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            // не нужно
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            // не нужно
        }
    }

    /** Оборачивает штатный форматтер BSL просмотрщика (повторный вызов ничего не меняет). */
    static void install(ISourceViewer viewer)
    {
        if (viewer == null)
            return;
        Object current = Global.getField(viewer, "fContentFormatter"); //$NON-NLS-1$
        if (current instanceof ComfortFormatter || !(current instanceof IContentFormatter delegate))
            return;
        if (!STOCK_FORMATTER_CLASS.equals(delegate.getClass().getName()))
            return;
        boolean done = Global.setFieldForce(viewer, "fContentFormatter", new ComfortFormatter(delegate, viewer)); //$NON-NLS-1$
        Global.tempLog(TEMP_LOG, "install viewer=" + viewer.getClass().getSimpleName() + " ok=" + done); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Значения опций для одного форматирования. */
    private static final class Options
    {
        boolean formattingOn = true;
        boolean continuationIndent = DEFAULT_CONTINUATION_INDENT;
        boolean compressInnerSpaces = DEFAULT_COMPRESS_INNER_SPACES;
        boolean compressBlankLines = DEFAULT_COMPRESS_BLANK_LINES;
        boolean keepInvocation = DEFAULT_KEEP_INVOCATION;
        boolean andOrTree = DEFAULT_AND_OR_TREE;
        int andOrTreeMinOps = DEFAULT_AND_OR_TREE_MIN_OPS;
        int margin = 120;
        int tabWidth = 4;
        boolean spacesForTabs;
        String keywordStandard = "CamelCase"; //$NON-NLS-1$

        String indentUnit()
        {
            return spacesForTabs ? " ".repeat(Math.max(1, tabWidth)) : "\t"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * Как {@code BslContentFormatter}: поставщик опций штатного {@code ServiceProvider}. На странице
         * параметров это поставщик страницы — значения элементов до «Применить»; в редакторе —
         * сохранённые настройки проекта модуля.
         */
        static Options read(IContentFormatter delegate, IXtextDocument document)
        {
            Options options = new Options();
            Object serviceProvider = Global.getField(delegate, "serviceProvider"); //$NON-NLS-1$
            Object provider = serviceProvider == null ? null : Global.invoke(serviceProvider, "getOptionProvider"); //$NON-NLS-1$
            boolean page = provider != null && FormatterPageInjector.isPageOptionProvider(provider.getClass());
            IScopeContext scope = null;
            IProject project = projectOf(document);
            if (project != null)
                scope = new ProjectScope(project);
            Reader reader = new Reader(provider, page, scope);
            options.formattingOn = reader.stockBoolean("formattingOn", true); //$NON-NLS-1$
            options.margin = reader.stockInteger("printMarginColumn", 120); //$NON-NLS-1$
            options.tabWidth = Math.max(1, reader.stockInteger("tabWidth", 4)); //$NON-NLS-1$
            options.spacesForTabs = reader.stockBoolean("spacesForTabs", false); //$NON-NLS-1$
            options.keywordStandard = reader.stockString("keywordStandard", "CamelCase"); //$NON-NLS-1$ //$NON-NLS-2$
            options.continuationIndent = reader.ownBoolean(KEY_CONTINUATION_INDENT, DEFAULT_CONTINUATION_INDENT);
            options.compressInnerSpaces = reader.ownBoolean(KEY_COMPRESS_INNER_SPACES, DEFAULT_COMPRESS_INNER_SPACES);
            options.compressBlankLines = reader.ownBoolean(KEY_COMPRESS_BLANK_LINES, DEFAULT_COMPRESS_BLANK_LINES);
            options.keepInvocation = reader.ownBoolean(KEY_KEEP_INVOCATION, DEFAULT_KEEP_INVOCATION);
            options.andOrTree = reader.ownBoolean(KEY_AND_OR_TREE, DEFAULT_AND_OR_TREE);
            options.andOrTreeMinOps = reader.ownInteger(KEY_AND_OR_TREE_MIN_OPS, DEFAULT_AND_OR_TREE_MIN_OPS);
            return options;
        }

        /** Проект модуля по URI ресурса; у примера страницы параметров проекта нет. */
        private static IProject projectOf(IXtextDocument document)
        {
            URI uri = document.readOnly(resource -> resource == null ? null : resource.getURI());
            if (uri == null || !uri.isPlatformResource() || uri.segmentCount() < 2)
                return null;
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(URI.decode(uri.segment(1)));
            return project.exists() ? project : null;
        }
    }

    /** Чтение опций: штатных — через поставщик, своих — как штатный менеджер, но со своим умолчанием. */
    private record Reader(Object provider, boolean page, IScopeContext scope)
    {
        boolean stockBoolean(String key, boolean def)
        {
            return provided(key, "getBooleanPreference") instanceof Boolean value ? value.booleanValue() : def; //$NON-NLS-1$
        }

        int stockInteger(String key, int def)
        {
            return provided(key, "getIntegerPreference") instanceof Integer value ? value.intValue() : def; //$NON-NLS-1$
        }

        String stockString(String key, String def)
        {
            return provided(key, "getStringPreference") instanceof String value ? value : def; //$NON-NLS-1$
        }

        boolean ownBoolean(String key, boolean def)
        {
            if (page)
                return stockBoolean(key, def);
            return Platform.getPreferencesService().getBoolean(NODE, key, def, contexts());
        }

        int ownInteger(String key, int def)
        {
            if (page)
                return stockInteger(key, def);
            return Platform.getPreferencesService().getInt(NODE, key, def, contexts());
        }

        /** Как {@code AbstractPreferenceManager.readBoolean(key, scope)}: сначала проект, затем порядок по умолчанию. */
        private IScopeContext[] contexts()
        {
            return scope == null ? null : new IScopeContext[] { scope };
        }

        private Object provided(String key, String method)
        {
            if (provider == null)
                return null;
            try
            {
                return page ? Global.invoke(provider, method, key) : Global.invoke(provider, method, key, scope);
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }
    }

    /** Правка текста. */
    private record Edit(int offset, int length, String text) {}

    /**
     * Обёртка штатного форматтера модуля: штатное форматирование и проходы Комфорта одним
     * действием отмены.
     */
    private static final class ComfortFormatter implements IContentFormatter
    {
        private final IContentFormatter delegate;
        private final ISourceViewer viewer;

        ComfortFormatter(IContentFormatter delegate, ISourceViewer viewer)
        {
            this.delegate = delegate;
            this.viewer = viewer;
        }

        @Override
        public IFormattingStrategy getFormattingStrategy(String contentType)
        {
            return delegate.getFormattingStrategy(contentType);
        }

        @Override
        public void format(IDocument document, IRegion region)
        {
            if (!(document instanceof IXtextDocument xtextDocument) || region == null)
            {
                delegate.format(document, region);
                return;
            }
            long started = System.nanoTime();
            Options options = Options.read(delegate, xtextDocument);
            if (!options.formattingOn)
            {
                delegate.format(document, region);
                return;
            }
            int startLine;
            int endLine;
            IRegion wholeLines;
            try
            {
                int end = region.getOffset() + region.getLength();
                startLine = document.getLineOfOffset(region.getOffset());
                endLine = document.getLineOfOffset(end);
                if (region.getLength() > 0 && endLine > startLine && document.getLineOffset(endLine) == end)
                    endLine--;
                wholeLines = lineRegion(document, startLine, endLine);
            }
            catch (BadLocationException e)
            {
                delegate.format(document, region);
                return;
            }
            int linesAfter = document.getNumberOfLines() - 1 - endLine;
            IRewriteTarget target = viewer instanceof ITextViewerExtension extension ? extension.getRewriteTarget() : null;
            if (target != null)
                target.beginCompoundChange();
            StringBuilder log = new StringBuilder();
            try
            {
                String problem = treeProblem(xtextDocument, startLine, endLine);
                log.append("lines=").append(startLine + 1).append('-').append(endLine + 1); //$NON-NLS-1$
                // Штатный пропускает только узлы с ошибкой, остальное (в т.ч. «;» в конце) форматирует.
                if (problem == null || problem.startsWith(SYNTAX_ERROR))
                {
                    if (problem != null)
                        log.append(" before=").append(problem); //$NON-NLS-1$
                    delegate.format(document, wholeLines);
                    problem = treeProblem(xtextDocument, startLine, document.getNumberOfLines() - 1 - linesAfter);
                    log.append(" stock=done"); //$NON-NLS-1$
                }
                if (problem == null)
                {
                    log.append(" engine=stock"); //$NON-NLS-1$
                    if (options.keepInvocation)
                        log.append(" calls=").append(pass(() -> keepInvocations(xtextDocument, //$NON-NLS-1$
                            startLine, document.getNumberOfLines() - 1 - linesAfter, options)));
                    if (options.andOrTree)
                        log.append(" trees=").append(pass(() -> andOrTrees(xtextDocument, //$NON-NLS-1$
                            startLine, document.getNumberOfLines() - 1 - linesAfter, options)));
                    if (options.continuationIndent || options.compressInnerSpaces || options.compressBlankLines)
                        log.append(" text=").append(pass(() -> textPass(document, startLine, //$NON-NLS-1$
                            document.getNumberOfLines() - 1 - linesAfter, options, options.continuationIndent, true)));
                }
                else
                {
                    log.append(" engine=text reason=").append(problem); //$NON-NLS-1$
                    log.append(" text=").append(pass(() -> textPass(document, startLine, //$NON-NLS-1$
                        document.getNumberOfLines() - 1 - linesAfter, options, true, options.continuationIndent)));
                }
            }
            finally
            {
                if (target != null)
                    target.endCompoundChange();
                log.append(" ms=").append((System.nanoTime() - started) / 1_000_000); //$NON-NLS-1$
                Global.tempLog(TEMP_LOG, log.toString());
            }
        }
    }

    private interface PassBody
    {
        int run() throws BadLocationException;
    }

    /** Проход Комфорта: сбой прохода не отменяет штатное форматирование. */
    private static String pass(PassBody body)
    {
        try
        {
            return Integer.toString(body.run());
        }
        catch (Exception | LinkageError e)
        {
            Global.log("BslFormatterHook: " + e); //$NON-NLS-1$
            Global.tempLog(TEMP_LOG, "pass failed: " + e); //$NON-NLS-1$
            return "error"; //$NON-NLS-1$
        }
    }

    /** Почему нельзя форматировать по дереву, или {@code null}, если можно. */
    private static String treeProblem(IXtextDocument document, int startLine, int endLine)
    {
        IRegion region;
        try
        {
            region = lineRegion(document, startLine, endLine);
        }
        catch (BadLocationException e)
        {
            return "region"; //$NON-NLS-1$
        }
        int from = region.getOffset();
        int to = from + region.getLength();
        String problem = document.readOnly(resource ->
        {
            if (resource == null)
                return "noResource"; //$NON-NLS-1$
            if (BslAstCompleteness.isTruncated(resource))
                return "truncated"; //$NON-NLS-1$
            IParseResult parseResult = resource.getParseResult();
            if (parseResult == null || parseResult.getRootASTElement() == null)
                return "noTree"; //$NON-NLS-1$
            for (INode error : parseResult.getSyntaxErrors())
            {
                int offset = error.getOffset();
                if (offset >= from && offset <= to)
                    return SYNTAX_ERROR + offset;
            }
            return null;
        });
        return problem;
    }

    static IRegion lineRegion(IDocument document, int startLine, int endLine) throws BadLocationException
    {
        int offset = document.getLineOffset(startLine);
        IRegion last = document.getLineInformation(Math.max(startLine, endLine));
        return new Region(offset, last.getOffset() + last.getLength() - offset);
    }

    private static String lineDelimiter(IDocument document, int line)
    {
        try
        {
            String delimiter = document.getLineDelimiter(line);
            if (delimiter != null)
                return delimiter;
        }
        catch (BadLocationException e)
        {
            // ниже
        }
        return TextUtilities.getDefaultLineDelimiter(document);
    }

    private static int applyEdits(IDocument document, List<Edit> edits) throws BadLocationException
    {
        edits.sort(Comparator.comparingInt(Edit::offset).reversed());
        for (Edit edit : edits)
            document.replace(edit.offset(), edit.length(), edit.text());
        return edits.size();
    }

    // ---------------------------------------------------------------- отступы и пустые строки (ИР)

    /** Порт ИР по строкам области. @return 1, если текст изменился */
    private static int textPass(IDocument document, int startLine, int endLine, Options options,
        boolean reindent, boolean continuationShift) throws BadLocationException
    {
        BslTextFormatter.Settings settings = new BslTextFormatter.Settings();
        settings.reindent = reindent;
        settings.continuationShift = continuationShift;
        settings.compressInnerSpaces = options.compressInnerSpaces;
        settings.compressBlankLines = options.compressBlankLines;
        settings.indentUnit = options.indentUnit();
        settings.tabWidth = options.tabWidth;
        List<String> lines = new ArrayList<>();
        for (int line = startLine; line <= endLine; line++)
        {
            IRegion info = document.getLineInformation(line);
            lines.add(document.get(info.getOffset(), info.getLength()));
        }
        String lineAbove = null;
        if (startLine > 0)
        {
            IRegion above = document.getLineInformation(startLine - 1);
            lineAbove = document.get(above.getOffset(), above.getLength());
        }
        List<String> formatted = BslTextFormatter.format(lines, lineAbove, settings);
        if (formatted.equals(lines))
            return 0;
        IRegion region = lineRegion(document, startLine, endLine);
        document.replace(region.getOffset(), region.getLength(),
            String.join(lineDelimiter(document, startLine), formatted));
        return 1;
    }

    // ---------------------------------------------------------------- не разрывать вызов метода

    /**
     * Вызов, который штатный перенос разорвал внутри скобок, склеивается: на месте, если
     * помещается в строку, иначе переносится целиком на следующую строку, если там помещается.
     */
    private static int keepInvocations(IXtextDocument document, int startLine, int endLine, Options options)
        throws BadLocationException
    {
        IRegion region = lineRegion(document, startLine, endLine);
        int from = region.getOffset();
        int to = from + region.getLength();
        List<int[]> spans = document.readOnly(resource -> invocationSpans(resource, from, to));
        if (spans == null || spans.isEmpty())
            return 0;
        String text = document.get();
        spans.removeIf(span -> text.substring(span[0], span[1]).indexOf('\n') < 0);
        spans.sort(Comparator.<int[]>comparingInt(span -> span[0]).thenComparingInt(span -> -span[1]));
        List<Edit> edits = new ArrayList<>();
        int takenUntil = -1;
        for (int[] span : spans)
        {
            if (span[0] < takenUntil)
                continue;
            Edit edit = keepInvocation(document, text, span[0], span[1], options);
            if (edit != null)
            {
                edits.add(edit);
                takenUntil = span[1];
            }
        }
        return applyEdits(document, edits);
    }

    /** Отрезки {@code [начало, конец)} вызовов области вместе с цепочкой обращений, которая их начинает. */
    private static List<int[]> invocationSpans(XtextResource resource, int from, int to)
    {
        List<int[]> spans = new ArrayList<>();
        if (resource == null || resource.getParseResult() == null)
            return spans;
        EObject root = resource.getParseResult().getRootASTElement();
        if (root == null)
            return spans;
        Set<Long> seen = new HashSet<>();
        for (Invocation invocation : EcoreUtil2.getAllContentsOfType(root, Invocation.class))
        {
            INode node = NodeModelUtils.findActualNodeFor(invocation);
            if (node == null)
                continue;
            int start = node.getOffset();
            int end = node.getEndOffset();
            if (start < from || end > to)
                continue;
            EObject current = invocation;
            while (current.eContainer() instanceof FeatureAccess
                || current.eContainer() instanceof Invocation
                || current.eContainer() instanceof IndexAccess)
            {
                EObject parent = current.eContainer();
                INode parentNode = NodeModelUtils.findActualNodeFor(parent);
                if (parentNode == null || parentNode.getOffset() != start)
                    break;
                current = parent;
                end = Math.max(end, parentNode.getEndOffset());
            }
            if (seen.add(((long)start << 32) | end))
                spans.add(new int[] { start, end });
        }
        return spans;
    }

    private static Edit keepInvocation(IDocument document, String text, int start, int end, Options options)
        throws BadLocationException
    {
        String body = text.substring(start, end);
        if (hasCommentOrMultilineLiteral(body))
            return null;
        String joined = joinLines(body);
        int line = document.getLineOfOffset(start);
        int lineStart = document.getLineOffset(line);
        String before = text.substring(lineStart, start);
        IRegion lastLine = document.getLineInformation(document.getLineOfOffset(end));
        String tail = BslTextFormatter.trim(text.substring(end, lastLine.getOffset() + lastLine.getLength()));
        if (width(before, options.tabWidth) + joined.length() + tail.length() <= options.margin)
            return new Edit(start, end - start, joined);
        if (BslTextFormatter.trim(before).isEmpty())
            return null;
        String indent = BslTextFormatter.leadingWhitespace(before);
        String movedIndent = indent + options.indentUnit();
        if (width(movedIndent, options.tabWidth) + joined.length() + tail.length() > options.margin)
            return null;
        int breakAt = lineStart + BslTextFormatter.trimRight(before).length();
        return new Edit(breakAt, end - breakAt, lineDelimiter(document, line) + movedIndent + joined);
    }

    /** Комментарий, строка многострочного литерала или директива внутри — склеивать нельзя. */
    private static boolean hasCommentOrMultilineLiteral(String body)
    {
        boolean inString = false;
        boolean lineStart = false;
        for (int i = 0; i < body.length(); i++)
        {
            char c = body.charAt(i);
            if (c == '\n')
            {
                lineStart = true;
                continue;
            }
            if (lineStart)
            {
                if (c == ' ' || c == '\t' || c == '\r')
                    continue;
                if (c == '|' || c == '#')
                    return true;
                lineStart = false;
            }
            if (c == '"')
                inString = !inString;
            else if (!inString && c == '/' && i + 1 < body.length() && body.charAt(i + 1) == '/')
                return true;
        }
        return false;
    }

    private static String joinLines(String body)
    {
        String[] lines = body.split("\r?\n"); //$NON-NLS-1$
        StringBuilder result = new StringBuilder(BslTextFormatter.trimRight(lines[0]));
        for (int i = 1; i < lines.length; i++)
        {
            String line = BslTextFormatter.trim(lines[i]);
            if (line.isEmpty())
                continue;
            char last = result.length() == 0 ? '(' : result.charAt(result.length() - 1);
            boolean glue = last == '(' || last == '.' || line.startsWith(")") || line.startsWith(".") //$NON-NLS-1$ //$NON-NLS-2$
                || line.startsWith(","); //$NON-NLS-1$
            if (!glue)
                result.append(' ');
            result.append(line);
        }
        return result.toString();
    }

    private static int width(String text, int tabWidth)
    {
        int result = 0;
        for (int i = 0; i < text.length(); i++)
            result += text.charAt(i) == '\t' ? tabWidth - result % tabWidth : 1;
        return result;
    }

    // ---------------------------------------------------------------- И-ИЛИ дерево

    private static final Pattern CONDITION_END = Pattern.compile(
        "[ \\t]*(?=(?:Тогда|Then|Цикл|Do)(?![_\\p{L}\\d]))", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Группа операндов одной логической операции. */
    private static final class AndOrGroup
    {
        final BinaryOperation operation;
        final List<Object> children = new ArrayList<>();

        AndOrGroup(BinaryOperation operation)
        {
            this.operation = operation;
        }
    }

    /** Операнд — не логическая операция: текст берётся как есть. */
    private record AndOrLeaf(Expression expression, int start, int end) {}

    /** Комментарий между операндами. */
    private record GapComment(int offset, String text) {}

    /** Ключевые слова дерева на языке модуля и в регистре из штатной опции «Эталоны». */
    private record TreeWords(String and, String or, String trueWord, String falseWord)
    {
        static TreeWords of(boolean russian, String keywordStandard)
        {
            TreeWords words = russian
                ? new TreeWords("И", "Или", "Истина", "Ложь") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                : new TreeWords("And", "Or", "True", "False"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if ("UPPERCASE".equals(keywordStandard)) //$NON-NLS-1$
                return new TreeWords(words.and.toUpperCase(), words.or.toUpperCase(),
                    words.trueWord.toUpperCase(), words.falseWord.toUpperCase());
            if ("lowercase".equals(keywordStandard)) //$NON-NLS-1$
                return new TreeWords(words.and.toLowerCase(), words.or.toLowerCase(),
                    words.trueWord.toLowerCase(), words.falseWord.toLowerCase());
            return words;
        }

        String operation(BinaryOperation operation)
        {
            return operation == BinaryOperation.AND ? and : or;
        }

        String neutral(BinaryOperation operation)
        {
            return operation == BinaryOperation.AND ? trueWord : falseWord;
        }
    }

    /**
     * Условия «Если»/«ИначеЕсли»/«Пока», правая часть присваивания и «Возврат» с логическими
     * операциями переписываются деревом: группа «И» начинается с «Истина», группа «ИЛИ» — с «Ложь»,
     * каждый операнд на своей строке; «Тогда»/«Цикл» — на отдельной строке.
     */
    private static int andOrTrees(IXtextDocument document, int startLine, int endLine, Options options)
        throws BadLocationException
    {
        IRegion region = lineRegion(document, startLine, endLine);
        int from = region.getOffset();
        int to = from + region.getLength();
        String text = document.get();
        String delimiter = lineDelimiter(document, startLine);
        List<Edit> edits = document.readOnly(resource -> andOrEdits(resource, text, from, to, delimiter, options));
        return edits == null ? 0 : applyEdits(document, edits);
    }

    private static List<Edit> andOrEdits(XtextResource resource, String text, int from, int to, String delimiter,
        Options options)
    {
        List<Edit> edits = new ArrayList<>();
        if (resource == null || resource.getParseResult() == null)
            return edits;
        EObject root = resource.getParseResult().getRootASTElement();
        if (root == null)
            return edits;
        List<Expression> candidates = new ArrayList<>();
        for (Conditional conditional : EcoreUtil2.getAllContentsOfType(root, Conditional.class))
            candidates.add(conditional.getPredicate());
        for (WhileStatement statement : EcoreUtil2.getAllContentsOfType(root, WhileStatement.class))
            candidates.add(statement.getPredicate());
        for (SimpleStatement statement : EcoreUtil2.getAllContentsOfType(root, SimpleStatement.class))
            candidates.add(statement.getRight());
        for (ReturnStatement statement : EcoreUtil2.getAllContentsOfType(root, ReturnStatement.class))
            candidates.add(statement.getExpression());
        for (Expression candidate : candidates)
        {
            if (!(candidate instanceof BinaryExpression binary) || !isLogical(binary))
                continue;
            INode node = NodeModelUtils.findActualNodeFor(binary);
            if (node == null || node.getOffset() < from || node.getEndOffset() > to)
                continue;
            Edit edit = andOrEdit(binary, node, text, delimiter, options);
            if (edit != null)
                edits.add(edit);
        }
        return edits;
    }

    private static Edit andOrEdit(BinaryExpression binary, INode node, String text, String delimiter, Options options)
    {
        AndOrGroup tree = buildGroup(binary, text);
        if (tree == null)
            return null;
        removeNeutral(tree);
        if (operationCount(tree) < options.andOrTreeMinOps)
            return null;
        int start = node.getOffset();
        int end = node.getEndOffset();
        List<AndOrLeaf> leaves = new ArrayList<>();
        collectLeaves(tree, leaves);
        if (leaves.isEmpty())
            return null;
        List<GapComment> comments = gapComments(text, start, end, leaves);
        if (comments == null)
            return null;
        INode left = NodeModelUtils.findActualNodeFor(binary.getLeft());
        INode right = NodeModelUtils.findActualNodeFor(binary.getRight());
        String operationText = left == null || right == null || left.getEndOffset() > right.getOffset() ? "" //$NON-NLS-1$
            : text.substring(left.getEndOffset(), right.getOffset());
        boolean russian = operationText.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
        if (operationText.isBlank())
            russian = text.substring(start, end).chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
        TreeWords words = TreeWords.of(russian, options.keywordStandard);
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        String statementIndent = BslTextFormatter.leadingWhitespace(text.substring(lineStart, start));
        List<String> lines = new ArrayList<>();
        int[] nextComment = { 0 };
        renderGroup(tree, 0, "", "", lines, text, comments, nextComment, words, statementIndent, options.indentUnit()); //$NON-NLS-1$ //$NON-NLS-2$
        String replacement = String.join(delimiter, lines);
        int replaceEnd = end;
        EObject owner = binary.eContainer();
        if (owner instanceof Conditional || owner instanceof WhileStatement)
        {
            Matcher matcher = CONDITION_END.matcher(text);
            matcher.region(end, text.length());
            if (matcher.lookingAt())
            {
                replaceEnd = matcher.end();
                replacement += delimiter + statementIndent;
            }
        }
        if (replacement.equals(text.substring(start, replaceEnd)))
            return null;
        return new Edit(start, replaceEnd - start, replacement);
    }

    private static boolean isLogical(BinaryExpression binary)
    {
        return binary.getOperation() == BinaryOperation.AND || binary.getOperation() == BinaryOperation.OR;
    }

    private static AndOrGroup buildGroup(BinaryExpression binary, String text)
    {
        AndOrGroup group = new AndOrGroup(binary.getOperation());
        return addOperand(group, binary.getLeft(), text) && addOperand(group, binary.getRight(), text) ? group : null;
    }

    private static boolean addOperand(AndOrGroup group, Expression operand, String text)
    {
        if (operand instanceof BinaryExpression binary && isLogical(binary))
        {
            if (binary.getOperation() == group.operation)
                return addOperand(group, binary.getLeft(), text) && addOperand(group, binary.getRight(), text);
            AndOrGroup child = buildGroup(binary, text);
            if (child == null)
                return false;
            group.children.add(child);
            return true;
        }
        INode node = operand == null ? null : NodeModelUtils.findActualNodeFor(operand);
        if (node == null || !balanced(text.substring(node.getOffset(), node.getEndOffset())))
            return false;
        group.children.add(new AndOrLeaf(operand, node.getOffset(), node.getEndOffset()));
        return true;
    }

    /** Уже построенное дерево: «Истина» в начале группы «И», «Ложь» в начале группы «ИЛИ». */
    private static void removeNeutral(AndOrGroup group)
    {
        if (group.children.size() > 1 && group.children.get(0) instanceof AndOrLeaf leaf
            && leaf.expression() instanceof BooleanLiteral literal
            && literal.isIsTrue() == (group.operation == BinaryOperation.AND))
            group.children.remove(0);
        for (Object child : group.children)
        {
            if (child instanceof AndOrGroup sub)
                removeNeutral(sub);
        }
    }

    private static int operationCount(AndOrGroup group)
    {
        int result = Math.max(0, group.children.size() - 1);
        for (Object child : group.children)
        {
            if (child instanceof AndOrGroup sub)
                result += operationCount(sub);
        }
        return result;
    }

    private static void collectLeaves(AndOrGroup group, List<AndOrLeaf> leaves)
    {
        for (Object child : group.children)
        {
            if (child instanceof AndOrGroup sub)
                collectLeaves(sub, leaves);
            else
                leaves.add((AndOrLeaf)child);
        }
    }

    private static int firstLeafStart(Object child)
    {
        if (child instanceof AndOrLeaf leaf)
            return leaf.start();
        AndOrGroup group = (AndOrGroup)child;
        return group.children.isEmpty() ? Integer.MAX_VALUE : firstLeafStart(group.children.get(0));
    }

    /**
     * Комментарии между операндами; {@code null}, если комментарий стоит после последнего
     * операнда (ему нет места в дереве).
     */
    private static List<GapComment> gapComments(String text, int start, int end, List<AndOrLeaf> leaves)
    {
        List<GapComment> comments = new ArrayList<>();
        int lastLeafEnd = leaves.stream().mapToInt(AndOrLeaf::end).max().orElse(start);
        int position = start;
        while (position < end)
        {
            AndOrLeaf inside = null;
            for (AndOrLeaf leaf : leaves)
            {
                if (position >= leaf.start() && position < leaf.end())
                {
                    inside = leaf;
                    break;
                }
            }
            if (inside != null)
            {
                position = inside.end();
                continue;
            }
            if (text.startsWith("//", position)) //$NON-NLS-1$
            {
                int lineEnd = text.indexOf('\n', position);
                if (lineEnd < 0 || lineEnd > end)
                    lineEnd = end;
                if (position > lastLeafEnd)
                    return null;
                comments.add(new GapComment(position, BslTextFormatter.trimRight(text.substring(position, lineEnd))));
                position = lineEnd;
                continue;
            }
            position++;
        }
        return comments;
    }

    private static void renderGroup(AndOrGroup group, int depth, String firstIndent, String firstPrefix,
        List<String> lines, String text, List<GapComment> comments, int[] nextComment, TreeWords words,
        String statementIndent, String unit)
    {
        lines.add(firstIndent + firstPrefix + words.neutral(group.operation));
        String childIndent = statementIndent + unit.repeat(depth + 1);
        String operation = words.operation(group.operation) + " "; //$NON-NLS-1$
        for (Object child : group.children)
        {
            int childStart = firstLeafStart(child);
            while (nextComment[0] < comments.size() && comments.get(nextComment[0]).offset() < childStart)
                lines.add(childIndent + comments.get(nextComment[0]++).text());
            if (child instanceof AndOrGroup sub)
            {
                renderGroup(sub, depth + 1, childIndent, operation + "(", lines, text, comments, nextComment, //$NON-NLS-1$
                    words, statementIndent, unit);
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + ")"); //$NON-NLS-1$
            }
            else
            {
                AndOrLeaf leaf = (AndOrLeaf)child;
                String[] leafLines = text.substring(leaf.start(), leaf.end()).split("\r?\n"); //$NON-NLS-1$
                lines.add(childIndent + operation + BslTextFormatter.trimRight(leafLines[0]));
                for (int i = 1; i < leafLines.length; i++)
                {
                    String continuation = BslTextFormatter.trim(leafLines[i]);
                    if (!continuation.isEmpty())
                        lines.add(childIndent + unit + continuation);
                }
            }
        }
    }

    /** Скобки и кавычки операнда парные (узел дерева мог не захватить свои скобки). */
    private static boolean balanced(String operand)
    {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < operand.length(); i++)
        {
            char c = operand.charAt(i);
            if (c == '"')
                inString = !inString;
            else if (!inString && c == '(')
                depth++;
            else if (!inString && c == ')' && --depth < 0)
                return false;
        }
        return depth == 0 && !inString;
    }

    // ---------------------------------------------------------------- страница «Форматирование»

    /**
     * Опции Комфорта на штатной странице «V8 → Встроенный язык → Форматирование» (и её
     * проектном варианте в свойствах проекта).
     *
     * <p>Опции — штатные {@code BooleanOption}/{@code IntegerOption} в картах страницы
     * ({@code booleanOptionMap}/{@code integerOptionMap}): «Применить», «Восстановить умолчания»,
     * импорт/экспорт и поставщик опций примеров работают с ними сами. Элементы создаёт штатный
     * {@code TerminalUIBuilder} поставщика вкладки — он же переформатирует пример при изменении.
     * Поставщик вкладки страница не хранит: его держат слушатели штатных элементов вкладки.
     */
    private static final class FormatterPageInjector
    {
        private static final String PAGE_CLASS =
            "com.e1c.g5.v8.dt.formatter.bsl.ui.BslFormatterPreferencePage"; //$NON-NLS-1$
        private static final String ABSTRACT_PAGE_CLASS =
            "com.e1c.g5.v8.dt.formatter.ui.preferences.page.AbstractFormatterPreferencePage"; //$NON-NLS-1$
        private static final String PAGE_OPTION_PROVIDER_CLASS = ABSTRACT_PAGE_CLASS + "$OptionProvider"; //$NON-NLS-1$
        private static final String TAB_PROVIDER_CLASS =
            "com.e1c.g5.v8.dt.formatter.ui.preferences.page.builders.AbstractPreferenceTabItemProvider"; //$NON-NLS-1$
        private static final String OPTION_PACKAGE = "com.e1c.g5.v8.dt.formatter.ui.preferences.option."; //$NON-NLS-1$

        private static final String PATCHED_KEY = "tormozit.bslFormatterPagePatched"; //$NON-NLS-1$

        private static final String LOGIC_TAB_TITLE = "Логические выражения"; //$NON-NLS-1$

        private static final String AND_OR_ARTICLE_URL = "https://infostart.ru/1c/articles/152801/"; //$NON-NLS-1$

        private static final int MAX_ATTEMPTS = 30;
        private static final int RETRY_MS = 100;

        private static final WeakHashMap<Shell, Boolean> wiredShells = new WeakHashMap<>();

        private enum Tab { INDENT, BLANK_LINES, AUTOWRAP }

        static void install(Display display)
        {
            Listener listener = event ->
            {
                if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                    return;
                if (!(shell.getData() instanceof PreferenceDialog dialog))
                    return;
                if (wiredShells.put(shell, Boolean.TRUE) != null)
                    return;
                IPageChangedListener pageListener = changed -> schedule(display, shell, changed.getSelectedPage(), 0);
                dialog.addPageChangedListener(pageListener);
                schedule(display, shell, dialog.getSelectedPage(), 0);
            };
            display.addFilter(SWT.Show, listener);
            display.addFilter(SWT.Activate, listener);
        }

        static boolean isPageOptionProvider(Class<?> type)
        {
            for (Class<?> current = type; current != null; current = current.getSuperclass())
            {
                if (PAGE_OPTION_PROVIDER_CLASS.equals(current.getName()))
                    return true;
            }
            return false;
        }

        private static boolean isA(Object object, String className)
        {
            if (object == null)
                return false;
            for (Class<?> current = object.getClass(); current != null; current = current.getSuperclass())
            {
                if (className.equals(current.getName()))
                    return true;
            }
            return false;
        }

        private static Class<?> classNamed(Object object, String className)
        {
            for (Class<?> current = object.getClass(); current != null; current = current.getSuperclass())
            {
                if (className.equals(current.getName()))
                    return current;
            }
            return null;
        }

        private static void schedule(Display display, Shell shell, Object page, int attempt)
        {
            if (shell.isDisposed() || !(page instanceof IPreferencePage preferencePage) || !isA(page, PAGE_CLASS))
                return;
            if (patch(preferencePage) || attempt >= MAX_ATTEMPTS)
                return;
            display.timerExec(RETRY_MS, () -> schedule(display, shell, page, attempt + 1));
        }

        /** @return {@code true}, если страница пропатчена сейчас или раньше. */
        private static boolean patch(IPreferencePage page)
        {
            Control root = page.getControl();
            if (root == null || root.isDisposed())
                return false;
            if (root.getData(PATCHED_KEY) != null)
                return true;
            TabFolder folder = find(root, TabFolder.class);
            if (folder == null || folder.getItemCount() == 0)
                return false;
            root.setData(PATCHED_KEY, Boolean.TRUE);
            try
            {
                inject(page, folder);
            }
            catch (Exception | LinkageError e)
            {
                Global.log("BslFormatterHook: page " + e); //$NON-NLS-1$
                Global.tempLog(TEMP_LOG, "page inject failed: " + e); //$NON-NLS-1$
            }
            return true;
        }

        private static void inject(IPreferencePage page, TabFolder folder) throws ReflectiveOperationException
        {
            Object manager = Global.invoke(page, "getPreferenceManager"); //$NON-NLS-1$
            Class<?> abstractPage = classNamed(page, ABSTRACT_PAGE_CLASS);
            if (manager == null || abstractPage == null)
            {
                Global.tempLog(TEMP_LOG, "page inject: manager=" + manager + " abstractPage=" + abstractPage); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            registerDefaults(manager);
            ClassLoader loader = abstractPage.getClassLoader();
            boolean russian = !Boolean.FALSE.equals(Global.invoke(page, "isRussianPreview")); //$NON-NLS-1$

            List<Object> providers = new ArrayList<>();
            Object indentProvider = null;
            Object blankProvider = null;
            Object autowrapProvider = null;
            for (TabItem item : folder.getItems())
            {
                Object provider = findTabProvider(item.getControl());
                Global.tempLog(TEMP_LOG, "page tab '" + item.getText() + "' provider=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (provider == null ? null : provider.getClass().getSimpleName()));
                if (provider == null)
                    continue;
                providers.add(provider);
                Tab tab = tabOf(provider, item.getText());
                if (tab == Tab.INDENT)
                    indentProvider = provider;
                else if (tab == Tab.BLANK_LINES)
                    blankProvider = provider;
                else if (tab == Tab.AUTOWRAP)
                    autowrapProvider = provider;
            }

            Map<String, Object> booleanOptions = map(page, "booleanOptionMap"); //$NON-NLS-1$
            Map<String, Object> integerOptions = map(page, "integerOptionMap"); //$NON-NLS-1$
            if (indentProvider != null)
            {
                addBoolean(indentProvider, folder, loader, manager, booleanOptions, KEY_CONTINUATION_INDENT,
                    "Сдвигать непервые строки инструкции по вложенности скобок", //$NON-NLS-1$
                    "Строка, продолжающая инструкцию, получает отступ по числу незакрытых скобок, и он всегда " //$NON-NLS-1$
                    + "больше отступа первой строки инструкции. Отступы строк пересчитываются по ключевым словам."); //$NON-NLS-1$
                addBoolean(indentProvider, folder, loader, manager, booleanOptions, KEY_COMPRESS_INNER_SPACES,
                    "Сжимать пустоты внутри строки", //$NON-NLS-1$
                    "Пробелы и табуляции подряд внутри строки (до первого строкового литерала) заменять одним " //$NON-NLS-1$
                    + "пробелом. Комментарии и строки многострочного литерала («|») не меняются."); //$NON-NLS-1$
                appendPreview(indentProvider, russian ? INDENT_PREVIEW_RU : INDENT_PREVIEW_EN);
            }
            if (blankProvider != null)
            {
                addBoolean(blankProvider, folder, loader, manager, booleanOptions, KEY_COMPRESS_BLANK_LINES,
                    "Удалять пустые строки, после которых нет комментария", //$NON-NLS-1$
                    "Удалять пустые строки, кроме стоящих перед комментарием, перед инструкцией препроцессора " //$NON-NLS-1$
                    + "или аннотацией («#», «&») и между методами."); //$NON-NLS-1$
                appendPreview(blankProvider, russian ? BLANK_PREVIEW_RU : BLANK_PREVIEW_EN);
            }
            if (autowrapProvider != null)
            {
                addBoolean(autowrapProvider, folder, loader, manager, booleanOptions, KEY_KEEP_INVOCATION,
                    "Не разрывать вызов метода", //$NON-NLS-1$
                    "Если вызов не помещается в строку, переносить его целиком на следующую строку. Параметры " //$NON-NLS-1$
                    + "переносятся, только если вызов не помещается и на отдельной строке."); //$NON-NLS-1$
                appendPreview(autowrapProvider, russian ? AUTOWRAP_PREVIEW_RU : AUTOWRAP_PREVIEW_EN);
            }
            Object logicProvider = providers.isEmpty() ? null
                : addLogicTab(page, folder, providers.get(0), abstractPage, loader, manager, booleanOptions,
                    integerOptions, russian);
            if (logicProvider != null)
                providers.add(logicProvider);

            for (Object provider : providers)
            {
                Object embeddedEditor = Global.getField(provider, "embeddedEditor"); //$NON-NLS-1$
                if (Global.invoke(embeddedEditor, "getViewer") instanceof ISourceViewer viewer) //$NON-NLS-1$
                    BslFormatterHook.install(viewer);
                reformat(provider);
            }
            // Опции Комфорта влияют на примеры всех вкладок, а штатный элемент переформатирует только свою.
            folder.addListener(SWT.Selection, event ->
            {
                int index = folder.getSelectionIndex();
                if (index >= 0)
                    reformat(findTabProvider(folder.getItem(index).getControl()));
            });
            // «Восстановить умолчания» и «Импортировать...» меняют значения опций, но примеры не обновляют.
            Listener reformatAll = event -> folder.getDisplay().asyncExec(() ->
            {
                if (!folder.isDisposed())
                    providers.forEach(FormatterPageInjector::reformat);
            });
            if (Global.invoke(page, "getDefaultsButton") instanceof Button defaults) //$NON-NLS-1$
            {
                defaults.addListener(SWT.Selection, reformatAll);
                for (Control control : defaults.getParent().getChildren())
                {
                    if (control instanceof Button button
                        && ("Импортировать...".equals(button.getText()) || "Import...".equals(button.getText()))) //$NON-NLS-1$ //$NON-NLS-2$
                        button.addListener(SWT.Selection, reformatAll);
                }
            }
            folder.getParent().layout(true, true);
            Global.tempLog(TEMP_LOG, "page inject done tabs=" + providers.size() //$NON-NLS-1$
                + " indent=" + (indentProvider != null) + " blank=" + (blankProvider != null) //$NON-NLS-1$ //$NON-NLS-2$
                + " autowrap=" + (autowrapProvider != null) + " logic=" + (logicProvider != null)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Новая вкладка «Логические выражения» со своим примером. */
        private static Object addLogicTab(IPreferencePage page, TabFolder folder, Object sample, Class<?> abstractPage,
            ClassLoader loader, Object manager, Map<String, Object> booleanOptions, Map<String, Object> integerOptions,
            boolean russian) throws ReflectiveOperationException
        {
            Constructor<?> constructor = sample.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            Object provider = constructor.newInstance();
            Global.setField(provider, "showWhitespaceCharacters", Global.getField(sample, "showWhitespaceCharacters")); //$NON-NLS-1$ //$NON-NLS-2$
            Object treeOption = option(loader, "BooleanOption", KEY_AND_OR_TREE, //$NON-NLS-1$
                "Строить И-ИЛИ дерево логических выражений", manager); //$NON-NLS-1$
            Object minOpsOption = option(loader, "IntegerOption", KEY_AND_OR_TREE_MIN_OPS, //$NON-NLS-1$
                "Минимальное число логических операций", manager); //$NON-NLS-1$
            if (booleanOptions != null)
                booleanOptions.put(KEY_AND_OR_TREE, treeOption);
            if (integerOptions != null)
                integerOptions.put(KEY_AND_OR_TREE_MIN_OPS, minOpsOption);
            Global.invoke(provider, "addOption", null, treeOption); //$NON-NLS-1$
            Global.invoke(provider, "addOption", null, minOpsOption); //$NON-NLS-1$
            Class<?> optionProviderClass = Class.forName(PAGE_OPTION_PROVIDER_CLASS, true, loader);
            Constructor<?> optionProviderConstructor = optionProviderClass.getDeclaredConstructor(abstractPage);
            optionProviderConstructor.setAccessible(true);
            Object optionProvider = optionProviderConstructor.newInstance(page);
            Global.invoke(provider, "setPreview", russian ? LOGIC_PREVIEW_RU : LOGIC_PREVIEW_EN, optionProvider); //$NON-NLS-1$
            int before = folder.getItemCount();
            Global.invoke(provider, "createContent", folder, LOGIC_TAB_TITLE); //$NON-NLS-1$
            if (folder.getItemCount() == before)
                return null;
            TabItem item = folder.getItem(folder.getItemCount() - 1);
            item.setToolTipText(TooltipText.wrap(folder,
                "Оформление условий с логическими операциями деревом И-ИЛИ" + Global.pluginSignForTooltip())); //$NON-NLS-1$
            setTooltips(item.getControl(), Map.of(
                "Строить И-ИЛИ дерево логических выражений", //$NON-NLS-1$
                "Условие «Если», «ИначеЕсли», «Пока», присваивание и «Возврат» с логическими операциями " //$NON-NLS-1$
                + "записывать деревом: каждый операнд на своей строке, группа «И» начинается с «Истина», " //$NON-NLS-1$
                + "группа «ИЛИ» — с «Ложь», «Тогда»/«Цикл» — на отдельной строке.", //$NON-NLS-1$
                "Минимальное число логических операций", //$NON-NLS-1$
                "Дерево строится, только если в выражении не меньше указанного числа операций И/ИЛИ " //$NON-NLS-1$
                + "(не считая начальных «Истина»/«Ложь» уже построенного дерева).")); //$NON-NLS-1$
            addArticleLink(optionsHost(provider, folder));
            return provider;
        }

        /** Ссылка на статью о И-ИЛИ дереве под опциями вкладки. */
        private static void addArticleLink(Composite host)
        {
            if (host == null)
            {
                Global.tempLog(TEMP_LOG, "page inject: no options host for article link"); //$NON-NLS-1$
                return;
            }
            Link link = new Link(host, SWT.NONE);
            link.setText("<a>Статья «Иерархия логических выражений»</a>"); //$NON-NLS-1$
            link.setToolTipText(TooltipText.wrap(link, AND_OR_ARTICLE_URL + Global.pluginSignForTooltip()));
            link.addListener(SWT.Selection, event -> ComfortPreferences.openChangesUrl(AND_OR_ARTICLE_URL));
            GridData data = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
            if (host.getLayout() instanceof GridLayout grid)
                data.horizontalSpan = grid.numColumns;
            data.verticalIndent = 8;
            link.setLayoutData(data);
            host.layout(true, true);
        }

        /** Флажок опции на штатной вкладке, рядом со штатными элементами. */
        private static void addBoolean(Object provider, TabFolder folder, ClassLoader loader, Object manager,
            Map<String, Object> booleanOptions, String key, String label, String tooltip)
            throws ReflectiveOperationException
        {
            Composite host = optionsHost(provider, folder);
            if (host == null)
            {
                Global.tempLog(TEMP_LOG, "page inject: no options host for " + key); //$NON-NLS-1$
                return;
            }
            Object option = option(loader, "BooleanOption", key, label, manager); //$NON-NLS-1$
            if (booleanOptions != null)
                booleanOptions.put(key, option);
            if (Global.getField(provider, "options") instanceof List<?> list) //$NON-NLS-1$
                addToList(list, option);
            Set<Control> before = new HashSet<>(Arrays.asList(host.getChildren()));
            Global.invoke(provider, "applyBooleanOption", option, host); //$NON-NLS-1$
            int columns = host.getLayout() instanceof GridLayout grid ? grid.numColumns : 1;
            for (Control control : host.getChildren())
            {
                if (before.contains(control))
                    continue;
                control.setToolTipText(TooltipText.wrap(control, tooltip + Global.pluginSignForTooltip()));
                if (columns > 1 && control.getLayoutData() instanceof GridData data)
                    data.horizontalSpan = columns;
            }
            host.layout(true, true);
        }

        @SuppressWarnings("unchecked")
        private static void addToList(List<?> list, Object option)
        {
            ((List<Object>)list).add(option);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> map(Object page, String field)
        {
            return Global.getField(page, field) instanceof Map<?, ?> map ? (Map<String, Object>)map : null;
        }

        /** Штатная опция: значение читается из менеджера страницы (рабочая область или проект). */
        private static Object option(ClassLoader loader, String className, String key, String label, Object manager)
            throws ReflectiveOperationException
        {
            Class<?> type = Class.forName(OPTION_PACKAGE + className, true, loader);
            Constructor<?> constructor = null;
            for (Constructor<?> candidate : type.getConstructors())
            {
                if (candidate.getParameterCount() == 4)
                    constructor = candidate;
            }
            if (constructor == null)
                throw new NoSuchMethodException(className + "(String, String, IPreferenceManager, IPreferenceStore)"); //$NON-NLS-1$
            Object option = constructor.newInstance(key, label, manager, null);
            Global.invoke(option, "uploadValue"); //$NON-NLS-1$
            return option;
        }

        /** Без умолчаний штатные {@code uploadValue}/{@code restoreDefault} получают {@code null}. */
        private static void registerDefaults(Object manager)
        {
            Global.invoke(manager, "setDefaultBooleanPreference", KEY_CONTINUATION_INDENT, //$NON-NLS-1$
                Boolean.valueOf(DEFAULT_CONTINUATION_INDENT));
            Global.invoke(manager, "setDefaultBooleanPreference", KEY_COMPRESS_INNER_SPACES, //$NON-NLS-1$
                Boolean.valueOf(DEFAULT_COMPRESS_INNER_SPACES));
            Global.invoke(manager, "setDefaultBooleanPreference", KEY_COMPRESS_BLANK_LINES, //$NON-NLS-1$
                Boolean.valueOf(DEFAULT_COMPRESS_BLANK_LINES));
            Global.invoke(manager, "setDefaultBooleanPreference", KEY_KEEP_INVOCATION, //$NON-NLS-1$
                Boolean.valueOf(DEFAULT_KEEP_INVOCATION));
            Global.invoke(manager, "setDefaultBooleanPreference", KEY_AND_OR_TREE, //$NON-NLS-1$
                Boolean.valueOf(DEFAULT_AND_OR_TREE));
            Global.invoke(manager, "setDefaultIntegerPreference", KEY_AND_OR_TREE_MIN_OPS, //$NON-NLS-1$
                Integer.valueOf(DEFAULT_AND_OR_TREE_MIN_OPS));
        }

        /** Вкладка по ключам её штатных опций; запасной путь — по надписи. */
        private static Tab tabOf(Object provider, String title)
        {
            Set<String> keys = optionKeys(provider);
            if (keys.contains("indentMethodInternal") || keys.contains("indentInsideConditional")) //$NON-NLS-1$ //$NON-NLS-2$
                return Tab.INDENT;
            if (keys.contains("newLineGapMax") || keys.contains("newLineGapMin")) //$NON-NLS-1$ //$NON-NLS-2$
                return Tab.BLANK_LINES;
            if (keys.contains("autowrapInvocation")) //$NON-NLS-1$
                return Tab.AUTOWRAP;
            if ("Отступ".equals(title) || "Indentation".equalsIgnoreCase(title)) //$NON-NLS-1$ //$NON-NLS-2$
                return Tab.INDENT;
            if ("Пустые строки".equals(title) || "Blank lines".equalsIgnoreCase(title)) //$NON-NLS-1$ //$NON-NLS-2$
                return Tab.BLANK_LINES;
            if ("Автоперенос".equals(title) || "Autowrap".equalsIgnoreCase(title)) //$NON-NLS-1$ //$NON-NLS-2$
                return Tab.AUTOWRAP;
            return null;
        }

        private static Set<String> optionKeys(Object provider)
        {
            Set<String> keys = new HashSet<>();
            collectKeys(Global.getField(provider, "options"), keys); //$NON-NLS-1$
            if (Global.getField(provider, "groups") instanceof Map<?, ?> groups) //$NON-NLS-1$
            {
                for (Object group : groups.values())
                    collectKeys(Global.getField(group, "options"), keys); //$NON-NLS-1$
            }
            return keys;
        }

        private static void collectKeys(Object options, Set<String> keys)
        {
            if (!(options instanceof Collection<?> collection))
                return;
            for (Object option : collection)
            {
                if (Global.invoke(option, "getPreferenceKey") instanceof String key) //$NON-NLS-1$
                    keys.add(key);
            }
        }

        /** Поставщик вкладки — его держат слушатели штатных элементов ({@code TerminalUIBuilder}). */
        private static Object findTabProvider(Control control)
        {
            if (control == null || control.isDisposed() || control instanceof StyledText)
                return null;
            if (control instanceof Button || control instanceof Combo || control instanceof Text)
            {
                for (int type : new int[] { SWT.Selection, SWT.Modify })
                {
                    for (Listener listener : control.getListeners(type))
                    {
                        Object found = providerIn(listener, 0);
                        if (found == null && listener instanceof TypedListener typed)
                            found = providerIn(typed.getEventListener(), 0);
                        if (found != null)
                            return found;
                    }
                }
            }
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                {
                    Object found = findTabProvider(child);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private static Object providerIn(Object holder, int depth)
        {
            if (holder == null || depth > 1)
                return null;
            if (isA(holder, TAB_PROVIDER_CLASS))
                return holder;
            for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass())
            {
                for (Field field : type.getDeclaredFields())
                {
                    if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                        continue;
                    try
                    {
                        field.setAccessible(true);
                        Object value = field.get(holder);
                        if (value == null || value instanceof Control || value instanceof String)
                            continue;
                        if (isA(value, TAB_PROVIDER_CLASS))
                            return value;
                        Object nested = providerIn(value, depth + 1);
                        if (nested != null)
                            return nested;
                    }
                    catch (Exception | LinkageError e)
                    {
                        // поле недоступно — следующее
                    }
                }
            }
            return null;
        }

        /** Контейнер штатных элементов вкладки — родитель элемента, слушатель которого держит поставщика. */
        private static Composite optionsHost(Object provider, TabFolder folder)
        {
            for (TabItem item : folder.getItems())
            {
                Control host = hostOf(item.getControl(), provider);
                if (host instanceof Composite composite)
                    return composite instanceof Group group ? group.getParent() : composite;
            }
            return null;
        }

        private static Control hostOf(Control control, Object provider)
        {
            if (control == null || control.isDisposed() || control instanceof StyledText)
                return null;
            if (control instanceof Button || control instanceof Combo || control instanceof Text)
                return findTabProvider(control) == provider ? control.getParent() : null;
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                {
                    Control found = hostOf(child, provider);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private static void setTooltips(Control control, Map<String, String> tooltips)
        {
            if (control == null || control.isDisposed())
                return;
            if (control instanceof Button button && tooltips.containsKey(button.getText()))
                button.setToolTipText(TooltipText.wrap(button, tooltips.get(button.getText()) + Global.pluginSignForTooltip()));
            if (control instanceof org.eclipse.swt.widgets.Label label && tooltips.containsKey(label.getText()))
                label.setToolTipText(TooltipText.wrap(label, tooltips.get(label.getText()) + Global.pluginSignForTooltip()));
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                    setTooltips(child, tooltips);
            }
        }

        /** Строки примера, показывающие опции Комфорта этой вкладки. */
        private static void appendPreview(Object provider, String extra)
        {
            if (!(Global.getField(provider, "previewText") instanceof String preview) || preview.contains(extra.strip())) //$NON-NLS-1$
                return;
            String delimiter = preview.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Global.setField(provider, "previewText", //$NON-NLS-1$
                BslTextFormatter.trimRight(preview.stripTrailing()) + delimiter + delimiter + extra.replace("\n", delimiter)); //$NON-NLS-1$
        }

        private static void reformat(Object provider)
        {
            if (provider == null)
                return;
            try
            {
                Global.invoke(provider, "reformat"); //$NON-NLS-1$
            }
            catch (RuntimeException e)
            {
                Global.tempLog(TEMP_LOG, "page reformat failed: " + e); //$NON-NLS-1$
            }
        }

        private static <T extends Control> T find(Control control, Class<T> type)
        {
            if (type.isInstance(control))
                return type.cast(control);
            if (control instanceof Composite composite)
            {
                for (Control child : composite.getChildren())
                {
                    T found = find(child, type);
                    if (found != null)
                        return found;
                }
            }
            return null;
        }

        private static final String INDENT_PREVIEW_RU = """
            Процедура ПродолжениеИнструкции(Количество, Цена, СтавкаНДС)
            Сумма = Количество * Цена
            + СуммаНДС(Количество * Цена,
            СтавкаНДС);
            Итог   =   Сумма  +  1;
            КонецПроцедуры""";

        private static final String INDENT_PREVIEW_EN = """
            Procedure StatementContinuation(Quantity, Price, VATRate)
            Amount = Quantity * Price
            + VATAmount(Quantity * Price,
            VATRate);
            Total   =   Amount  +  1;
            EndProcedure""";

        private static final String BLANK_PREVIEW_RU = """
            Процедура ПустыеСтроки()
            А = 1;

            Б = 2;

            // Комментарий
            В = 3;
            КонецПроцедуры""";

        private static final String BLANK_PREVIEW_EN = """
            Procedure BlankLines()
            A = 1;

            B = 2;

            // Comment
            C = 3;
            EndProcedure""";

        private static final String AUTOWRAP_PREVIEW_RU = """
            Процедура ВызовЦеликом(Ссылка)
            Результат = ОбщегоНазначения.ЗначениеРеквизита(Ссылка, "Код");
            КонецПроцедуры""";

        private static final String AUTOWRAP_PREVIEW_EN = """
            Procedure WholeCall(Ref)
            Result = Common.ObjectAttributeValue(Ref, "Code", True);
            EndProcedure""";

        private static final String LOGIC_PREVIEW_RU = """
            Процедура Условия(А, Б, В, Г)
            Если А И (Б Или В) И Не Г Тогда
            Возврат;
            КонецЕсли;
            Короткое = А И Б;
            КонецПроцедуры""";

        private static final String LOGIC_PREVIEW_EN = """
            Procedure Conditions(A, B, C, D)
            If A And (B Or C) And Not D Then
            Return;
            EndIf;
            Short = A And B;
            EndProcedure""";
    }
}
