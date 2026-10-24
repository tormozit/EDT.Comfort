package tormozit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentPartitioner;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.presentation.PresentationReconciler;
import org.eclipse.jface.text.reconciler.IReconciler;
import org.eclipse.jface.text.rules.DefaultDamagerRepairer;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.rules.ITokenScanner;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ICharacterPairMatcher;
import org.eclipse.jface.text.source.MatchingCharacterPainter;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.mylyn.internal.wikitext.ui.editor.syntax.FastMarkupPartitioner;
import org.eclipse.mylyn.wikitext.markdown.MarkdownLanguage;
import org.eclipse.mylyn.wikitext.parser.MarkupParser;
import org.eclipse.mylyn.wikitext.parser.outline.OutlineItem;
import org.eclipse.mylyn.wikitext.parser.outline.OutlineParser;
import org.eclipse.mylyn.wikitext.ui.editor.MarkupSourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.editors.text.TextSourceViewerConfiguration;
import org.eclipse.ui.texteditor.spelling.SpellingAnnotation;
import org.osgi.framework.Bundle;

/**
 * Редактор текста в формате «Обычный», «Markdown», «JSON», «XML» или «HTML» на готовом
 * {@link SourceViewer}: подсветка формата, кнопка «Формат» и оформленный вид («Готовый текст»).
 *
 * <p>Формат меняет только настройку подсветки просмотрщика ({@code SourceViewer.configure}):
 * документ, выделение и каретка не меняются. Markdown — через Mylyn WikiText; без его бандлов
 * (зависимости {@code resolution:=optional}) формата Markdown в списке нет.
 *
 * <p>Потребители: вкладка «Сырой текст» текстового макета ({@link TextTemplateFormatPagesHook}, #868)
 * и окно «Вложенный текст» редактора модуля ({@link EditEmbeddedTextHandler}, #885).
 */
final class FormattedTextEditor
{
    private static final String WIKITEXT_UI_BUNDLE = "org.eclipse.mylyn.wikitext.ui"; //$NON-NLS-1$

    private static final String WIKITEXT_MARKDOWN_BUNDLE = "org.eclipse.mylyn.wikitext.markdown"; //$NON-NLS-1$

    /** Сколько первых строк текста смотрим при определении Markdown. */
    private static final int DETECT_MAX_LINES = 400;

    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}\\s+\\S"); //$NON-NLS-1$

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)"); //$NON-NLS-1$

    private static final Pattern LINK = Pattern.compile("\\[[^\\]\\n]+\\]\\([^)\\n]+\\)"); //$NON-NLS-1$

    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*+]|\\d+\\.)\\s+\\S"); //$NON-NLS-1$

    private static final Pattern BOLD = Pattern.compile("\\*\\*[^*\\n]+\\*\\*"); //$NON-NLS-1$

    private static final Pattern HTML_START =
        Pattern.compile("^(<!doctype\\s+html|<html[\\s>]|<head[\\s>]|<body[\\s>])", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    /** Формат текста. */
    enum Format
    {
        TEXT("Обычный", "org.eclipse.core.runtime.text"), //$NON-NLS-1$ //$NON-NLS-2$
        MARKDOWN("Markdown", "org.eclipse.mylyn.wikitext.markdown"), //$NON-NLS-1$ //$NON-NLS-2$
        JSON("JSON", "tormozit.comfort.json"), //$NON-NLS-1$ //$NON-NLS-2$
        XML("XML", "org.eclipse.core.runtime.xml"), //$NON-NLS-1$ //$NON-NLS-2$
        HTML("HTML", "org.eclipse.wst.html.core.htmlsource"); //$NON-NLS-1$ //$NON-NLS-2$

        final String title;

        /** Тип содержимого платформы — для настроек по типу (направляющие отступов {@link IndentGuideHook}). */
        final String contentTypeId;

        Format(String title, String contentTypeId)
        {
            this.title = title;
            this.contentTypeId = contentTypeId;
        }

        boolean hasPreview()
        {
            return this == MARKDOWN || this == HTML;
        }

        boolean isAvailable()
        {
            return this != MARKDOWN || isWikiTextAvailable();
        }

        /** Умеем ли форматировать текст этого формата в этой установке. */
        boolean canFormat()
        {
            return switch (this)
            {
                case JSON -> true;
                case XML -> WstFormatter.XML.isAvailable();
                case HTML -> WstFormatter.HTML.isAvailable();
                default -> false;
            };
        }

        static Format parse(String value)
        {
            for (Format format : values())
                if (format.name().equals(value))
                    return format;
            return null;
        }
    }

    final SourceViewer viewer;

    /** Настройка просмотрщика для формата «Обычный». */
    private final SourceViewerConfiguration textConfiguration;

    /** Модель пометок документа (для снятия пометок орфографии); {@code null} — нет. */
    private final IAnnotationModel annotationModel;

    /** После смены формата (подсветка уже применена). */
    private final Runnable onFormatApplied;

    /** Узел настроек BSL-редактора EDT (см. {@link TextEditorOccurrencesSupport}). */
    private static final String BSL_UI_QUALIFIER = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$

    /** Цвет подсветки парных скобок Xtext ({@code BracketMatchingPreferencesInitializer.COLOR_KEY}). */
    private static final String BRACKET_COLOR_KEY = "CharacterMatching.Color"; //$NON-NLS-1$

    /** Подсветка парной скобки текущего формата; {@code null} — у формата её нет. */
    private MatchingCharacterPainter bracketPainter;

    /** Пары скобок текущего формата (подсветка и переход); {@code null} — у формата их нет. */
    private BracketPairMatcher bracketMatcher;

    private boolean bracketKeysInstalled;

    private IDocumentPartitioner stockPartitioner;

    private IDocumentPartitioner markdownPartitioner;

    private Format detected;

    private Format chosen;

    private Format format;

    private ToolItem formatItem;

    /** Кнопка «Форматировать». */
    private ToolItem formatTextItem;

    /** Суффикс подсказок кнопок (подпись плагина вне его окон, иначе пусто). */
    private String toolTipSuffix = ""; //$NON-NLS-1$

    private Menu formatMenu;

    /** Ручной выбор в меню «Формат» ({@code null} — «Авто»): сохранить его. */
    private Consumer<Format> onChosen;

    FormattedTextEditor(SourceViewer viewer, SourceViewerConfiguration textConfiguration, IAnnotationModel annotationModel,
        Runnable onFormatApplied)
    {
        this.viewer = viewer;
        this.textConfiguration = textConfiguration;
        this.annotationModel = annotationModel;
        this.onFormatApplied = onFormatApplied;
    }

    /** Подсветить по ручному выбору ({@code null} — «Авто», по содержимому). */
    void start(Format chosenFormat)
    {
        installBracketKeys();
        detected = detect(text());
        chosen = chosenFormat != null && chosenFormat.isAvailable() ? chosenFormat : null;
        apply(chosen != null ? chosen : detected);
    }

    Format format()
    {
        return format;
    }

    // =========================================================================
    // Определение формата
    // =========================================================================

    /** Бандлы WikiText есть в установке (классы из них трогаем только после этой проверки). */
    static boolean isWikiTextAvailable()
    {
        return isResolved(Platform.getBundle(WIKITEXT_UI_BUNDLE))
            && isResolved(Platform.getBundle(WIKITEXT_MARKDOWN_BUNDLE));
    }

    private static boolean isResolved(Bundle bundle)
    {
        return bundle != null && (bundle.getState() & (Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE)) != 0;
    }

    static Format detect(String text)
    {
        if (text == null)
            return Format.TEXT;
        String trimmed = text.strip();
        if (trimmed.isEmpty())
            return Format.TEXT;
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        if ((first == '{' && last == '}') || (first == '[' && last == ']'))
            return Format.JSON;
        if (first == '<')
            return HTML_START.matcher(trimmed).find() ? Format.HTML : Format.XML;
        return isWikiTextAvailable() && looksLikeMarkdown(trimmed) ? Format.MARKDOWN : Format.TEXT;
    }

    /** Похож ли текст на Markdown: заголовки {@code #}, блоки кода, ссылки {@code [текст](адрес)}, списки, {@code **}. */
    static boolean looksLikeMarkdown(String text)
    {
        int headings = 0;
        int fences = 0;
        int links = 0;
        int lists = 0;
        int bold = 0;
        String[] lines = text.split("\\R", DETECT_MAX_LINES + 1); //$NON-NLS-1$
        for (int i = 0; i < lines.length && i < DETECT_MAX_LINES; i++)
        {
            String line = lines[i];
            if (HEADING.matcher(line).find())
                headings++;
            if (FENCE.matcher(line).find())
                fences++;
            if (LINK.matcher(line).find())
                links++;
            if (LIST_ITEM.matcher(line).find())
                lists++;
            if (BOLD.matcher(line).find())
                bold++;
        }
        if (headings + fences + links == 0)
            return false;
        return headings * 3 + fences * 3 + links * 2 + bold + (lists >= 2 ? 1 : 0) >= 3;
    }

    /**
     * Текст фрагмента без разметки — как он выглядит в «Готовом тексте»: без тегов, маркеров
     * заголовков, списков, цитат, выделения и ссылок; первая непустая строка, не длиннее 120 символов.
     */
    static String plainText(String source)
    {
        if (source == null)
            return null;
        for (String line : source.split("\\R")) //$NON-NLS-1$
        {
            String text = line.replaceAll("<[^>]*>", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("^\\s*(#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+\\.\\s+)", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("[*_`~]", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\s+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .strip();
            if (!text.isEmpty())
                return text.length() > 120 ? text.substring(0, 120).strip() : text;
        }
        return null;
    }

    // =========================================================================
    // Текст и выделение
    // =========================================================================

    String text()
    {
        IDocument document = viewer.getDocument();
        return document != null ? document.get() : null;
    }

    int caretOffset()
    {
        ITextSelection selection = selection();
        return selection != null ? selection.getOffset() : 0;
    }

    ITextSelection selection()
    {
        return viewer.getSelectionProvider() != null
            && viewer.getSelectionProvider().getSelection() instanceof ITextSelection selection ? selection : null;
    }

    /** Что искать в «Готовом тексте»: выделенный фрагмент, без выделения — строка под кареткой. */
    String selectionProbe()
    {
        ITextSelection selection = selection();
        IDocument document = viewer.getDocument();
        if (selection == null || document == null)
            return null;
        try
        {
            if (selection.getLength() > 0)
                return plainText(selection.getText());
            int line = document.getLineOfOffset(Math.min(selection.getOffset(), document.getLength()));
            IRegion region = document.getLineInformation(line);
            return plainText(document.get(region.getOffset(), region.getLength()));
        }
        catch (BadLocationException e)
        {
            return null;
        }
    }

    /**
     * Выделить в тексте фрагмент, выделенный в «Готовом тексте»: ближайшее к каретке
     * вхождение; между словами допускаются пробелы и символы разметки ({@code ** _ ` # >} и т.п.).
     */
    void selectInRaw(String rendered)
    {
        String text = text();
        if (text == null || rendered == null)
            return;
        String[] words = rendered.strip().split("\\s+"); //$NON-NLS-1$
        if (words.length == 0 || words[0].isEmpty())
            return;
        StringBuilder regex = new StringBuilder();
        for (String word : words)
        {
            if (regex.length() > 0)
                regex.append("(?:\\s|<[^>]*>|[*_`~#>\\[\\]()])+"); //$NON-NLS-1$
            regex.append(Pattern.quote(word));
        }
        Matcher matcher = Pattern.compile(regex.toString()).matcher(text);
        int caret = caretOffset();
        int bestStart = -1;
        int bestEnd = -1;
        while (matcher.find())
        {
            if (bestStart < 0 || Math.abs(matcher.start() - caret) < Math.abs(bestStart - caret))
            {
                bestStart = matcher.start();
                bestEnd = matcher.end();
            }
        }
        if (bestStart < 0)
            return;
        viewer.setSelectedRange(bestStart, bestEnd - bestStart);
        viewer.revealRange(bestStart, bestEnd - bestStart);
    }

    // =========================================================================
    // Формат
    // =========================================================================

    /** Выбор в меню «Формат»: {@code null} — «Авто». */
    void choose(Format value)
    {
        chosen = value;
        if (onChosen != null)
            onChosen.accept(value);
        detected = detect(text());
        apply(value != null ? value : detected);
    }

    /** Подсветка формата на просмотрщике; выделение, каретка и документ не меняются. */
    private void apply(Format value)
    {
        IDocument document = viewer.getDocument();
        if (document == null || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
            return;
        format = value;
        viewer.unconfigure();
        restorePartitioner(document);
        SourceViewerConfiguration configuration = switch (value)
        {
            case MARKDOWN ->
            {
                stockPartitioner = document.getDocumentPartitioner();
                markdownPartitioner = MarkdownSupport.installPartitioner(document);
                yield MarkdownSupport.createConfiguration();
            }
            case JSON -> new ScannerConfiguration(new JsonScanner(), false);
            case XML, HTML -> new ScannerConfiguration(new MarkupTagScanner(), true);
            default -> textConfiguration;
        };
        viewer.configure(configuration);
        if (value != Format.TEXT)
            removeSpellingAnnotations();
        viewer.invalidateTextPresentation();
        // Направляющие отступов — по типу содержимого формата (могут быть выключены для него).
        IndentGuideHook.attachViewer(viewer, () -> format != null ? format.contentTypeId : null);
        updateBracketMatching();
        updateFormatItem();
        if (onFormatApplied != null)
            onFormatApplied.run();
    }

    /**
     * Подсветка парной скобки у каретки — в форматах с разметкой (у «Обычного» её нет, как в штатном
     * текстовом редакторе). Пары зависят от формата, поэтому рисовальщик пересоздаётся при его смене.
     * Цвет — настройка подсветки скобок BSL-редактора: тот же вид, что в модуле.
     */
    private void updateBracketMatching()
    {
        if (!(viewer instanceof org.eclipse.jface.text.ITextViewerExtension2 painters))
            return;
        if (bracketPainter != null)
        {
            painters.removePainter(bracketPainter);
            bracketPainter.dispose();
            bracketPainter = null;
        }
        bracketMatcher = null;
        if (format == null || format == Format.TEXT)
            return;
        bracketMatcher = new BracketPairMatcher(format);
        bracketPainter = new MatchingCharacterPainter(viewer, bracketMatcher);
        bracketPainter.setColor(EditorsUI.getSharedTextColors().getColor(bracketColor()));
        painters.addPainter(bracketPainter);
    }

    /**
     * Пары скобок формата без скобок внутри строк, комментариев и кода: JSON — строки {@code "…"}/{@code '…'}
     * и комментарии; XML/HTML — значения атрибутов, комментарии {@code <!-- -->}, {@code CDATA},
     * {@code <?…?>} (у них парные только внешние {@code <} и {@code >}); Markdown — код в {@code `…`}
     * и блоки {@code ```}. Пары считаются по всему тексту за один проход и кэшируются до правки документа.
     */
    private static final class BracketPairMatcher implements ICharacterPairMatcher
    {
        private final Format format;

        private IDocument cachedDocument;

        private long cachedStamp = -1;

        /** Позиция скобки → позиция её пары; -1 — не скобка или без пары. */
        private int[] partners = new int[0];

        private int anchor = LEFT;

        BracketPairMatcher(Format format)
        {
            this.format = format;
        }

        @Override
        public IRegion match(IDocument document, int offset)
        {
            if (document == null || offset < 0)
                return null;
            ensurePartners(document);
            // Как штатно: сначала скобка перед кареткой, затем — за ней.
            for (int position : new int[] { offset - 1, offset })
            {
                if (position < 0 || position >= partners.length || partners[position] < 0)
                    continue;
                int partner = partners[position];
                anchor = position < partner ? LEFT : RIGHT;
                int start = Math.min(position, partner);
                return new Region(start, Math.abs(partner - position) + 1);
            }
            return null;
        }

        @Override
        public int getAnchor()
        {
            return anchor;
        }

        @Override
        public void clear()
        {
            anchor = LEFT;
        }

        @Override
        public void dispose()
        {
            cachedDocument = null;
            partners = new int[0];
        }

        /** Пары скобок документа: позиция скобки → позиция пары, -1 — нет пары. */
        int[] partners(IDocument document)
        {
            ensurePartners(document);
            return partners;
        }

        private void ensurePartners(IDocument document)
        {
            long stamp = document instanceof org.eclipse.jface.text.IDocumentExtension4 extension
                ? extension.getModificationStamp() : -1;
            // Метки правки нет (UNKNOWN = -1) — пересчёт каждый раз, иначе пары устарели бы.
            if (document == cachedDocument && stamp == cachedStamp && stamp >= 0)
                return;
            partners = computePartners(document.get(), format);
            cachedDocument = document;
            cachedStamp = stamp;
        }

        static int[] computePartners(String text, Format format)
        {
            int[] result = new int[text.length()];
            java.util.Arrays.fill(result, -1);
            StringBuilder openChars = new StringBuilder();
            List<Integer> openPositions = new ArrayList<>();
            int i = 0;
            while (i < text.length())
            {
                char c = text.charAt(i);
                int skipTo = skipNonCode(text, i, format, result);
                if (skipTo > i)
                {
                    i = skipTo;
                    continue;
                }
                if (format == Format.XML || format == Format.HTML)
                {
                    if (c == '<')
                    {
                        int end = tagEnd(text, i);
                        if (end > i)
                            pair(result, i, end);
                        i = end > i ? end + 1 : i + 1;
                        continue;
                    }
                }
                if ("{[(".indexOf(c) >= 0)
                {
                    openChars.append(c);
                    openPositions.add(i);
                }
                else if ("}])".indexOf(c) >= 0 && openChars.length() > 0
                    && "{[(".indexOf(openChars.charAt(openChars.length() - 1)) == "}])".indexOf(c))
                {
                    int last = openChars.length() - 1;
                    pair(result, openPositions.remove(last), i);
                    openChars.setLength(last);
                }
                i++;
            }
            return result;
        }

        /** Строка, комментарий или код с позиции {@code i}: позиция за ним; иначе {@code i}. */
        private static int skipNonCode(String text, int i, Format format, int[] result)
        {
            char c = text.charAt(i);
            switch (format)
            {
                case JSON ->
                {
                    if (c == '"' || c == '\'')
                        return stringEnd(text, i, c);
                    if (text.startsWith("//", i)) //$NON-NLS-1$
                        return lineEnd(text, i);
                    if (text.startsWith("/*", i)) //$NON-NLS-1$
                        return blockEnd(text, i + 2, "*/"); //$NON-NLS-1$
                }
                case XML, HTML ->
                {
                    // Внутри — не скобки; парные только внешние «<» и «>».
                    for (String[] block : new String[][] { { "<!--", "-->" }, { "<![CDATA[", "]]>" }, { "<?", "?>" } }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                    {
                        if (text.startsWith(block[0], i))
                        {
                            int end = blockEnd(text, i + block[0].length(), block[1]);
                            if (end <= text.length() && text.startsWith(block[1], end - block[1].length()))
                                pair(result, i, end - 1);
                            return end;
                        }
                    }
                }
                case MARKDOWN ->
                {
                    if (text.startsWith("```", i) && (i == 0 || text.charAt(i - 1) == '\n')) //$NON-NLS-1$
                    {
                        int close = text.indexOf("\n```", i + 3); //$NON-NLS-1$
                        return close < 0 ? text.length() : lineEnd(text, close + 1);
                    }
                    if (c == '`')
                    {
                        int close = text.indexOf('`', i + 1);
                        int newline = text.indexOf('\n', i + 1);
                        if (close > i && (newline < 0 || close < newline))
                            return close + 1;
                    }
                }
                default ->
                {
                }
            }
            return i;
        }

        /** Конец тега от {@code <}: позиция {@code >} вне кавычек значений атрибутов; -1 — не закрыт. */
        private static int tagEnd(String text, int start)
        {
            char quote = 0;
            for (int i = start + 1; i < text.length(); i++)
            {
                char c = text.charAt(i);
                if (quote != 0)
                {
                    if (c == quote)
                        quote = 0;
                }
                else if (c == '"' || c == '\'')
                    quote = c;
                else if (c == '>')
                    return i;
                else if (c == '<')
                    return -1;
            }
            return -1;
        }

        private static int stringEnd(String text, int start, char quote)
        {
            for (int i = start + 1; i < text.length(); i++)
            {
                char c = text.charAt(i);
                if (c == '\\')
                    i++;
                else if (c == quote || c == '\n')
                    return i + 1;
            }
            return text.length();
        }

        private static int lineEnd(String text, int start)
        {
            int end = text.indexOf('\n', start);
            return end < 0 ? text.length() : end + 1;
        }

        private static int blockEnd(String text, int from, String terminator)
        {
            int end = text.indexOf(terminator, from);
            return end < 0 ? text.length() : end + terminator.length();
        }

        private static void pair(int[] result, int open, int close)
        {
            result[open] = close;
            result[close] = open;
        }
    }

    /**
     * Ctrl+] / Ctrl+[ (с Shift — с выделением) — как «Перейти по операторным скобкам вперёд/назад»
     * в редакторе модуля. Привязки тех команд действуют только в редакторе модуля, поэтому клавиши
     * ловим в поле ({@code VerifyKey} — до обработки самим текстом). Та же клавиша в русской раскладке —
     * «ъ»/«х»: код клавиши SWT берёт из текущей раскладки.
     */
    private void installBracketKeys()
    {
        if (bracketKeysInstalled || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
            return;
        bracketKeysInstalled = true;
        viewer.getTextWidget().addVerifyKeyListener(event ->
        {
            int modifiers = event.stateMask & SWT.MODIFIER_MASK;
            if (modifiers != SWT.MOD1 && modifiers != (SWT.MOD1 | SWT.SHIFT))
                return;
            char key = Character.toLowerCase((char)event.keyCode);
            boolean forward = key == ']' || key == 'ъ' || key == '}';
            boolean back = key == '[' || key == 'х' || key == '{';
            if ((forward || back) && jumpToBracket(forward, modifiers != SWT.MOD1))
                event.doit = false;
        });
    }

    /**
     * Переход по скобкам, как «Перейти по операторным скобкам» модуля: каретка у скобки — к её паре
     * (направление задаёт скобка, а не команда); иначе вперёд — к закрывающей скобке, охватывающей
     * каретку, назад — к открывающей. Скобка выделяется; {@code withSelection} — выделение от прежнего
     * места до скобки. Скобки в строках и комментариях не считаются ({@link BracketPairMatcher}).
     *
     * @return {@code true} — переход выполнен
     */
    boolean jumpToBracket(boolean forward, boolean withSelection)
    {
        IDocument document = viewer.getDocument();
        if (bracketMatcher == null || document == null)
            return false;
        int[] partners = bracketMatcher.partners(document);
        org.eclipse.swt.graphics.Point selection = viewer.getSelectedRange();
        int target = -1;
        // Как в модуле: у каретки без выделения — скобка перед ней, затем за ней; с выделением — его начало.
        int[] candidates = selection.y == 0 ? new int[] { selection.x - 1, selection.x } : new int[] { selection.x };
        for (int position : candidates)
        {
            if (position >= 0 && position < partners.length && partners[position] >= 0)
            {
                target = partners[position];
                break;
            }
        }
        if (target < 0)
        {
            int caret = selection.x;
            if (forward)
            {
                for (int i = caret; i < partners.length && target < 0; i++)
                    if (partners[i] >= 0 && partners[i] < caret)
                        target = i;
            }
            else
            {
                for (int i = caret - 1; i >= 0 && target < 0; i--)
                    if (partners[i] > i && partners[i] >= caret)
                        target = i;
            }
        }
        if (target < 0)
            return false;
        QueryBracketSupport.selectBracket(viewer, selection, target, withSelection);
        return true;
    }

    /** Цвет подсветки скобок BSL-редактора (Xtext {@code CharacterMatching.Color}); не задан — серый по теме. */
    private static RGB bracketColor()
    {
        try
        {
            String value = Platform.getPreferencesService().getString(BSL_UI_QUALIFIER, BRACKET_COLOR_KEY, null, null);
            if (value != null && !value.isBlank())
                return StringConverter.asRGB(value);
        }
        catch (RuntimeException e)
        {
            // Нет настройки или она испорчена — цвет по умолчанию.
        }
        return ThemeAwareColors.isDarkTheme() ? new RGB(0xA0, 0xA0, 0xA0) : new RGB(0x80, 0x80, 0x80);
    }

    /** Пометки орфографии прежней настройки: её проверка снята вместе с ней, а пометки остались бы. */
    private void removeSpellingAnnotations()
    {
        if (annotationModel == null)
            return;
        List<Annotation> spelling = new ArrayList<>();
        for (Iterator<Annotation> it = annotationModel.getAnnotationIterator(); it.hasNext();)
        {
            Annotation annotation = it.next();
            if (annotation instanceof SpellingAnnotation)
                spelling.add(annotation);
        }
        if (annotationModel instanceof IAnnotationModelExtension extension)
            extension.replaceAnnotations(spelling.toArray(new Annotation[0]), null);
        else
            spelling.forEach(annotationModel::removeAnnotation);
    }

    private void restorePartitioner(IDocument document)
    {
        if (markdownPartitioner == null)
            return;
        if (document.getDocumentPartitioner() == markdownPartitioner)
        {
            markdownPartitioner.disconnect();
            if (stockPartitioner != null)
                stockPartitioner.connect(document);
            document.setDocumentPartitioner(stockPartitioner);
        }
        markdownPartitioner = null;
        stockPartitioner = null;
    }

    /**
     * Кнопки «Формат» (выпадающее меню: «Авто» и форматы) и «Форматировать» на панели {@code toolBar}.
     *
     * @param toolTip подсказка кнопки «Формат»
     * @param toolTipSuffix дописывается ко всем подсказкам кнопок (подпись плагина вне его окон)
     * @param onChosen ручной выбор ({@code null} — «Авто»): сохранить его; {@code null} — не сохранять
     */
    ToolItem createFormatItem(ToolBar toolBar, String toolTip, String toolTipSuffix, Consumer<Format> onChosen)
    {
        this.onChosen = onChosen;
        this.toolTipSuffix = toolTipSuffix != null ? toolTipSuffix : ""; //$NON-NLS-1$
        formatItem = new ToolItem(toolBar, SWT.DROP_DOWN);
        formatItem.setToolTipText(TooltipText.wrap(toolBar, toolTip + this.toolTipSuffix));
        formatTextItem = new ToolItem(toolBar, SWT.PUSH);
        formatTextItem.setText("Форматировать"); //$NON-NLS-1$
        formatTextItem.addListener(SWT.Selection, e -> formatText());
        formatMenu = new Menu(toolBar);
        // Меню — по щелчку и на стрелке, и на самой кнопке.
        formatItem.addListener(SWT.Selection, e ->
        {
            fillFormatMenu();
            Rectangle bounds = formatItem.getBounds();
            formatMenu.setLocation(toolBar.toDisplay(bounds.x, bounds.y + bounds.height));
            formatMenu.setVisible(true);
        });
        updateFormatItem();
        return formatItem;
    }

    private void updateFormatItem()
    {
        if (formatItem == null || formatItem.isDisposed() || format == null)
            return;
        // Ручной выбор видно сразу: иначе его легко принять за ошибку определения формата.
        String suffix = chosen == null ? " (авто)" //$NON-NLS-1$
            : chosen == detected ? " (выбран)" : " (выбран; авто — " + detected.title + ')'; //$NON-NLS-1$ //$NON-NLS-2$
        formatItem.setText("Формат: " + format.title + suffix); //$NON-NLS-1$
        updateFormatTextItem();
        formatItem.getParent().getParent().layout(true, true);
    }

    /** «Форматировать» доступна у форматов, которые умеем форматировать; недоступная — объясняет почему. */
    private void updateFormatTextItem()
    {
        if (formatTextItem == null || formatTextItem.isDisposed())
            return;
        String toolTip;
        boolean enabled = false;
        if (!format.canFormat())
            toolTip = format == Format.XML || format == Format.HTML
                ? "Недоступно: в установке нет форматировщика " + format.title + " (бандлы WST)." //$NON-NLS-1$ //$NON-NLS-2$
                : "Недоступно: текст в формате «" + format.title + "» не форматируется. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Форматировать можно JSON, XML и HTML."; //$NON-NLS-1$
        else if (!viewer.isEditable())
            toolTip = "Недоступно: текст только для чтения."; //$NON-NLS-1$
        else
        {
            enabled = true;
            toolTip = format == Format.JSON
                ? "Расставить отступы (табуляцией) и переводы строк JSON. Строки, числа и комментарии не меняются." //$NON-NLS-1$
                : "Форматировать " + format.title + " по настройкам форматирования " + format.title //$NON-NLS-1$ //$NON-NLS-2$
                    + "-редактора Eclipse."; //$NON-NLS-1$
            toolTip += "\nОтменяется одним Ctrl+Z."; //$NON-NLS-1$
        }
        formatTextItem.setEnabled(enabled);
        formatTextItem.setToolTipText(TooltipText.wrap(formatTextItem.getParent(), toolTip + toolTipSuffix));
    }

    /**
     * Форматировать весь текст одной заменой документа (одна отмена). Каретка остаётся у того же
     * значимого символа: считаются символы без пробелов до неё. Текст с ошибкой не меняется.
     */
    void formatText()
    {
        IDocument document = viewer.getDocument();
        if (document == null || format == null || !format.canFormat() || !viewer.isEditable())
            return;
        String text = document.get();
        String delimiter = TextUtilities.getDefaultLineDelimiter(document);
        String formatted;
        try
        {
            formatted = switch (format)
            {
                case JSON -> JsonFormatter.format(text, delimiter);
                case XML -> WstFormatter.XML.format(text);
                case HTML -> WstFormatter.HTML.format(text);
                default -> text;
            };
        }
        catch (FormatException e)
        {
            ToastNotification.show("Форматировать", e.getMessage(), 5_000); //$NON-NLS-1$
            return;
        }
        formatted = formatted.replaceAll("\\r\\n|\\r|\\n", Matcher.quoteReplacement(delimiter)); //$NON-NLS-1$
        if (formatted.equals(text))
            return;
        int caret = caretOffset();
        int significant = 0;
        for (int i = 0; i < caret && i < text.length(); i++)
            if (!Character.isWhitespace(text.charAt(i)))
                significant++;
        try
        {
            document.replace(0, document.getLength(), formatted);
        }
        catch (BadLocationException e)
        {
            return;
        }
        int newCaret = 0;
        for (int seen = 0; newCaret < formatted.length() && seen < significant; newCaret++)
            if (!Character.isWhitespace(formatted.charAt(newCaret)))
                seen++;
        viewer.setSelectedRange(newCaret, 0);
        viewer.revealRange(newCaret, 0);
    }

    /** Текст не разобран — форматирование не выполнено; сообщение показывается пользователю. */
    private static final class FormatException extends Exception
    {
        private static final long serialVersionUID = 1L;

        FormatException(String message)
        {
            super(message);
        }
    }

    /**
     * JSON: меняются только пробелы и переводы строк между элементами (отступ — табуляция).
     * Строки, числа, литералы и комментарии {@code //}, {@code /* *\/} переносятся как есть.
     */
    private static final class JsonFormatter
    {
        private final String text;

        private final String delimiter;

        private final StringBuilder out = new StringBuilder();

        private int position;

        private int depth;

        /** Открытые скобки — закрывающая должна им соответствовать. */
        private final StringBuilder brackets = new StringBuilder();

        /** Конец последнего элемента в исходном тексте: комментарий на той же строке остаётся на ней. */
        private int lastTokenEnd = -1;

        /** После комментария до конца строки следующий элемент — с новой строки. */
        private boolean pendingNewLine;

        /** После комментария {@code /* *\/} следующий элемент — через пробел. */
        private boolean pendingSpace;

        /** Начало последнего комментария {@code //} в результате, пока за ним не было элемента; иначе -1. */
        private int lineCommentAt = -1;

        private JsonFormatter(String text, String delimiter)
        {
            this.text = text;
            this.delimiter = delimiter;
        }

        static String format(String text, String delimiter) throws FormatException
        {
            return new JsonFormatter(text, delimiter).run();
        }

        private String run() throws FormatException
        {
            while (position < text.length())
            {
                char c = text.charAt(position);
                if (Character.isWhitespace(c))
                {
                    position++;
                    continue;
                }
                if (c == '/' && position + 1 < text.length() && text.charAt(position + 1) == '/')
                {
                    int end = text.indexOf('\n', position);
                    end = end < 0 ? text.length() : end;
                    String comment = text.substring(position, end).stripTrailing();
                    appendInline(comment);
                    lineCommentAt = out.length() - comment.length();
                    position = end;
                    pendingNewLine = true;
                    continue;
                }
                if (c == ',' && pendingNewLine && lineCommentAt >= 0)
                {
                    // «1 // текст» + «,» на следующей строке → «1, // текст»: комментарий не съедает запятую.
                    int at = lineCommentAt;
                    while (at > 0 && out.charAt(at - 1) == ' ')
                        at--;
                    out.insert(at, ',');
                    position++;
                    pendingNewLine = false;
                    lineCommentAt = -1;
                    newLine();
                    lastTokenEnd = position;
                    continue;
                }
                lineCommentAt = -1;
                if (c == '/' && position + 1 < text.length() && text.charAt(position + 1) == '*')
                {
                    int end = text.indexOf("*/", position + 2); //$NON-NLS-1$
                    if (end < 0)
                        throw error("не закрыт комментарий /*"); //$NON-NLS-1$
                    appendInline(text.substring(position, end + 2));
                    position = end + 2;
                    pendingSpace = true;
                    continue;
                }
                breakPendingLine();
                if (pendingSpace)
                {
                    pendingSpace = false;
                    if (!isAtLineStart() && c != ',' && c != ':')
                        out.append(' ');
                }
                switch (c)
                {
                    case '{', '[' ->
                    {
                        out.append(c);
                        position++;
                        char close = c == '{' ? '}' : ']';
                        if (nextSignificant() == close)
                        {
                            skipWhitespace();
                            out.append(close);
                            position++;
                        }
                        else
                        {
                            brackets.append(close);
                            depth++;
                            newLine();
                        }
                    }
                    case '}', ']' ->
                    {
                        if (depth == 0)
                            throw error("лишняя закрывающая скобка «" + c + "»"); //$NON-NLS-1$ //$NON-NLS-2$
                        char expected = brackets.charAt(brackets.length() - 1);
                        if (c != expected)
                            throw error("ожидалась «" + expected + "», а стоит «" + c + "»"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        brackets.setLength(brackets.length() - 1);
                        depth--;
                        newLine();
                        out.append(c);
                        position++;
                    }
                    case ',' ->
                    {
                        trimTrailingSpaces();
                        out.append(c);
                        position++;
                        newLine();
                    }
                    case ':' ->
                    {
                        trimTrailingSpaces();
                        out.append(": "); //$NON-NLS-1$
                        position++;
                    }
                    case '"', '\'' -> out.append(readString(c));
                    default -> out.append(readLiteral());
                }
                lastTokenEnd = position;
            }
            if (depth != 0)
                throw error("не закрыта скобка «" + brackets.charAt(0) + "»"); //$NON-NLS-1$ //$NON-NLS-2$
            trimTrailingSpaces();
            return out.toString();
        }

        private String readString(char quote) throws FormatException
        {
            int start = position++;
            while (position < text.length())
            {
                char c = text.charAt(position++);
                if (c == '\\')
                    position++;
                else if (c == quote)
                    return text.substring(start, position);
                else if (c == '\n' || c == '\r')
                    break;
            }
            throw error("не закрыта строка"); //$NON-NLS-1$
        }

        private String readLiteral()
        {
            int start = position;
            while (position < text.length())
            {
                char c = text.charAt(position);
                if (Character.isWhitespace(c) || "{}[],:\"'".indexOf(c) >= 0 //$NON-NLS-1$
                    || (c == '/' && position + 1 < text.length() && "/*".indexOf(text.charAt(position + 1)) >= 0)) //$NON-NLS-1$
                    break;
                position++;
            }
            if (position == start)
                position++;
            return text.substring(start, position);
        }

        private char nextSignificant()
        {
            for (int i = position; i < text.length(); i++)
                if (!Character.isWhitespace(text.charAt(i)))
                    return text.charAt(i);
            return '\0';
        }

        private void skipWhitespace()
        {
            while (position < text.length() && Character.isWhitespace(text.charAt(position)))
                position++;
        }

        /**
         * Комментарий — через пробел за текущим элементом строки (или с отступа пустой строки).
         * Стоял в исходном тексте на одной строке с предыдущим элементом ({@code [ // …}) —
         * остаётся на его строке, а не уходит на новую.
         */
        private void appendInline(String comment)
        {
            breakPendingLine();
            if (isAtLineStart() && lastTokenEnd >= 0 && text.substring(lastTokenEnd, position).indexOf('\n') < 0)
            {
                trimTrailingSpaces();
                if (endsWithDelimiter())
                    out.setLength(out.length() - delimiter.length());
                pendingNewLine = true;
            }
            if (!isAtLineStart())
            {
                trimTrailingSpaces();
                out.append(' ');
            }
            out.append(comment);
        }

        private void breakPendingLine()
        {
            if (pendingNewLine)
            {
                pendingNewLine = false;
                newLine();
            }
        }

        /** Новая строка с отступом текущей глубины; пустые строки не копятся. */
        private void newLine()
        {
            trimTrailingSpaces();
            if (out.length() > 0 && !endsWithDelimiter())
                out.append(delimiter);
            for (int i = 0; i < depth; i++)
                out.append('\t');
        }

        private boolean isAtLineStart()
        {
            int i = out.length();
            while (i > 0 && out.charAt(i - 1) == '\t')
                i--;
            return i == 0 || out.substring(0, i).endsWith(delimiter);
        }

        private boolean endsWithDelimiter()
        {
            return out.length() >= delimiter.length()
                && out.substring(out.length() - delimiter.length()).equals(delimiter);
        }

        private void trimTrailingSpaces()
        {
            int length = out.length();
            while (length > 0 && (out.charAt(length - 1) == ' ' || out.charAt(length - 1) == '\t'))
                length--;
            out.setLength(length);
        }

        private FormatException error(String detail)
        {
            int line = 1;
            for (int i = 0; i < Math.min(position, text.length()); i++)
                if (text.charAt(i) == '\n')
                    line++;
            return new FormatException("Текст не форматирован — не разобран как JSON: " + detail //$NON-NLS-1$
                + " (строка " + line + ")."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * XML и HTML — форматировщики WST, те же, что команда «Форматировать» XML/HTML-редактора Eclipse
     * (учитывают его настройки форматирования). Классы во внутренних пакетах WST
     * ({@code x-friends}/{@code x-internal} — ограничение только для компиляции), поэтому через загрузку
     * класса из бандла и {@code formatContent(String)} рефлексией: нет бандла или класса — формат
     * не форматируется (кнопка недоступна), а не падение.
     */
    private enum WstFormatter
    {
        XML("org.eclipse.wst.xml.core", "org.eclipse.wst.xml.core.internal.provisional.format.FormatProcessorXML"), //$NON-NLS-1$ //$NON-NLS-2$
        HTML("org.eclipse.wst.html.core", "org.eclipse.wst.html.core.internal.format.HTMLFormatProcessorImpl"); //$NON-NLS-1$ //$NON-NLS-2$

        private final String bundleId;

        private final String className;

        /** Метод {@code formatContent(String)}; {@code null} — ещё не искали; пустой — недоступен. */
        private java.util.Optional<java.lang.reflect.Method> method;

        /**
         * Сам класс форматировщика. Экземпляр — только от него: {@code formatContent} у XML объявлен
         * в абстрактном родителе ({@code AbstractStructuredFormatProcessor}), и {@code getDeclaringClass()}
         * дал бы {@code InstantiationException}.
         */
        private Class<?> processorClass;

        WstFormatter(String bundleId, String className)
        {
            this.bundleId = bundleId;
            this.className = className;
        }

        boolean isAvailable()
        {
            return resolveMethod() != null;
        }

        private java.lang.reflect.Method resolveMethod()
        {
            if (method == null)
            {
                java.lang.reflect.Method found = null;
                try
                {
                    Bundle bundle = Platform.getBundle(bundleId);
                    if (isResolved(bundle))
                    {
                        Class<?> loaded = bundle.loadClass(className);
                        loaded.getConstructor();
                        found = loaded.getMethod("formatContent", String.class); //$NON-NLS-1$
                        processorClass = loaded;
                    }
                }
                catch (ReflectiveOperationException | RuntimeException | LinkageError e)
                {
                    found = null;
                }
                method = java.util.Optional.ofNullable(found);
            }
            return method.orElse(null);
        }

        String format(String text) throws FormatException
        {
            java.lang.reflect.Method formatContent = resolveMethod();
            if (formatContent == null)
                throw new FormatException("Нет форматировщика " + name() + " (бандл " + bundleId + ")."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            try
            {
                Object processor = processorClass.getConstructor().newInstance();
                Object result = formatContent.invoke(processor, text);
                if (!(result instanceof String formatted))
                    throw new FormatException("Форматировщик " + name() + " не вернул текст."); //$NON-NLS-1$ //$NON-NLS-2$
                return formatted;
            }
            catch (java.lang.reflect.InvocationTargetException e)
            {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new FormatException("Текст не форматирован: " + cause); //$NON-NLS-1$
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e)
            {
                throw new FormatException("Текст не форматирован: " + e); //$NON-NLS-1$
            }
        }
    }

    private void fillFormatMenu()
    {
        for (MenuItem item : formatMenu.getItems())
            item.dispose();
        addFormatItem("Авто (" + detect(text()).title + ")", null); //$NON-NLS-1$ //$NON-NLS-2$
        new MenuItem(formatMenu, SWT.SEPARATOR);
        for (Format value : Format.values())
            if (value.isAvailable())
                addFormatItem(value.title, value);
    }

    private void addFormatItem(String text, Format value)
    {
        MenuItem item = new MenuItem(formatMenu, SWT.RADIO);
        item.setText(text);
        item.setSelection(chosen == value);
        item.addListener(SWT.Selection, e ->
        {
            if (item.getSelection() && chosen != value)
                item.getDisplay().asyncExec(() -> choose(value));
        });
    }

    // =========================================================================
    // Подсветка
    // =========================================================================

    /** Настройка просмотрщика: штатные возможности текстового редактора и подсветка своим сканером. */
    private static final class ScannerConfiguration extends TextSourceViewerConfiguration
    {
        private final ITokenScanner scanner;

        /** Перекрашивать весь документ: у разметки многострочные теги и комментарии. */
        private final boolean wholeDocument;

        ScannerConfiguration(ITokenScanner scanner, boolean wholeDocument)
        {
            super(EditorsUI.getPreferenceStore());
            this.scanner = scanner;
            this.wholeDocument = wholeDocument;
        }

        /** Без проверки орфографии: в данных и разметке она находит «ошибки» в именах, ключах и значениях. */
        @Override
        public IReconciler getReconciler(ISourceViewer sourceViewer)
        {
            return null;
        }

        @Override
        public IPresentationReconciler getPresentationReconciler(ISourceViewer sourceViewer)
        {
            PresentationReconciler reconciler = new PresentationReconciler();
            DefaultDamagerRepairer damagerRepairer = wholeDocument ? new WholeDocumentDamagerRepairer(scanner)
                : new DefaultDamagerRepairer(scanner);
            reconciler.setDamager(damagerRepairer, IDocument.DEFAULT_CONTENT_TYPE);
            reconciler.setRepairer(damagerRepairer, IDocument.DEFAULT_CONTENT_TYPE);
            return reconciler;
        }
    }

    /** Повреждённая область — весь документ: сканер разметки начинает с начала и знает, внутри ли тега. */
    private static final class WholeDocumentDamagerRepairer extends DefaultDamagerRepairer
    {
        WholeDocumentDamagerRepairer(ITokenScanner scanner)
        {
            super(scanner);
        }

        @Override
        public IRegion getDamageRegion(ITypedRegion partition, DocumentEvent event, boolean documentPartitioningChanged)
        {
            return new Region(0, event.getDocument().getLength());
        }
    }

    /** Цвета подсветки: пары для светлой и тёмной темы. */
    private static final class Styles
    {
        static IToken token(int light, int dark, int style)
        {
            int rgb = ThemeAwareColors.isDarkTheme() ? dark : light;
            Color color = new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            return new Token(new TextAttribute(color, null, style));
        }

        static IToken key()       { return token(0x0451A5, 0x9CDCFE, SWT.NORMAL); }
        static IToken string()    { return token(0xA31515, 0xCE9178, SWT.NORMAL); }
        static IToken number()    { return token(0x098658, 0xB5CEA8, SWT.NORMAL); }
        static IToken literal()   { return token(0x0000FF, 0x569CD6, SWT.BOLD); }
        static IToken punct()     { return token(0x5A5A5A, 0xB0B0B0, SWT.NORMAL); }
        static IToken tag()       { return token(0x800000, 0x569CD6, SWT.NORMAL); }
        static IToken attribute() { return token(0xE50000, 0x9CDCFE, SWT.NORMAL); }
        static IToken comment()   { return token(0x008000, 0x6A9955, SWT.ITALIC); }
        static IToken special()   { return token(0x800080, 0xC586C0, SWT.NORMAL); }
    }

    /** Базовый посимвольный сканер области документа. */
    private abstract static class CharScanner implements ITokenScanner
    {
        protected IDocument document;

        protected int position;

        protected int end;

        protected int tokenStart;

        @Override
        public void setRange(IDocument document, int offset, int length)
        {
            this.document = document;
            this.position = offset;
            this.end = offset + length;
            this.tokenStart = offset;
        }

        @Override
        public int getTokenOffset()
        {
            return tokenStart;
        }

        @Override
        public int getTokenLength()
        {
            return position - tokenStart;
        }

        protected char charAt(int offset)
        {
            try
            {
                return offset < document.getLength() ? document.getChar(offset) : '\0';
            }
            catch (BadLocationException e)
            {
                return '\0';
            }
        }

        protected boolean startsWith(String text)
        {
            for (int i = 0; i < text.length(); i++)
                if (position + i >= end || charAt(position + i) != text.charAt(i))
                    return false;
            return true;
        }

        /** Пропустить до {@code terminator} включительно или до конца области. */
        protected void skipPast(String terminator)
        {
            while (position < end && !startsWith(terminator))
                position++;
            position = Math.min(end, position + terminator.length());
        }

        protected IToken whitespace()
        {
            while (position < end && Character.isWhitespace(charAt(position)))
                position++;
            return Token.WHITESPACE;
        }
    }

    /** JSON: ключи, строки, числа, {@code true/false/null}, скобки и разделители. */
    private static final class JsonScanner extends CharScanner
    {
        private final IToken key = Styles.key();

        private final IToken string = Styles.string();

        private final IToken number = Styles.number();

        private final IToken literal = Styles.literal();

        private final IToken punct = Styles.punct();

        @Override
        public IToken nextToken()
        {
            tokenStart = position;
            if (position >= end)
                return Token.EOF;
            char c = charAt(position);
            if (Character.isWhitespace(c))
                return whitespace();
            if (c == '"')
            {
                position++;
                while (position < end)
                {
                    char ch = charAt(position++);
                    if (ch == '\\')
                        position++;
                    else if (ch == '"' || ch == '\n' || ch == '\r')
                        break;
                }
                position = Math.min(position, end);
                return isFollowedByColon(position) ? key : string;
            }
            if (c == '-' || Character.isDigit(c))
            {
                position++;
                while (position < end && "0123456789.eE+-".indexOf(charAt(position)) >= 0) //$NON-NLS-1$
                    position++;
                return number;
            }
            if (Character.isLetter(c))
            {
                while (position < end && Character.isLetter(charAt(position)))
                    position++;
                String word = text(tokenStart, position);
                return "true".equals(word) || "false".equals(word) || "null".equals(word) ? literal : Token.UNDEFINED; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            position++;
            return "{}[]:,".indexOf(c) >= 0 ? punct : Token.UNDEFINED; //$NON-NLS-1$
        }

        private boolean isFollowedByColon(int offset)
        {
            int length = document.getLength();
            for (int i = offset; i < length; i++)
            {
                char c = charAt(i);
                if (!Character.isWhitespace(c))
                    return c == ':';
            }
            return false;
        }

        private String text(int from, int to)
        {
            try
            {
                return document.get(from, to - from);
            }
            catch (BadLocationException e)
            {
                return ""; //$NON-NLS-1$
            }
        }
    }

    /** XML и HTML: теги, атрибуты, значения, комментарии, CDATA, {@code <?…?>}, {@code <!…>}, сущности. */
    private static final class MarkupTagScanner extends CharScanner
    {
        private final IToken tag = Styles.tag();

        private final IToken attribute = Styles.attribute();

        private final IToken value = Styles.string();

        private final IToken comment = Styles.comment();

        private final IToken special = Styles.special();

        private final IToken punct = Styles.punct();

        /** Позиция внутри открытого тега (после имени, до {@code >}). */
        private boolean inTag;

        @Override
        public void setRange(IDocument document, int offset, int length)
        {
            super.setRange(document, offset, length);
            inTag = false;
        }

        @Override
        public IToken nextToken()
        {
            tokenStart = position;
            if (position >= end)
                return Token.EOF;
            char c = charAt(position);
            if (inTag)
                return insideTag(c);
            if (startsWith("<!--")) //$NON-NLS-1$
            {
                skipPast("-->"); //$NON-NLS-1$
                return comment;
            }
            if (startsWith("<![CDATA[")) //$NON-NLS-1$
            {
                skipPast("]]>"); //$NON-NLS-1$
                return special;
            }
            if (startsWith("<?")) //$NON-NLS-1$
            {
                skipPast("?>"); //$NON-NLS-1$
                return special;
            }
            if (startsWith("<!")) //$NON-NLS-1$
            {
                skipPast(">"); //$NON-NLS-1$
                return special;
            }
            if (c == '<')
            {
                position++;
                if (position < end && charAt(position) == '/')
                    position++;
                while (position < end && isNameChar(charAt(position)))
                    position++;
                inTag = true;
                return tag;
            }
            if (c == '&')
            {
                position++;
                while (position < end && charAt(position) != ';' && !Character.isWhitespace(charAt(position))
                    && charAt(position) != '<')
                    position++;
                if (position < end && charAt(position) == ';')
                    position++;
                return special;
            }
            while (position < end && charAt(position) != '<' && charAt(position) != '&')
                position++;
            return Token.UNDEFINED;
        }

        private IToken insideTag(char c)
        {
            if (Character.isWhitespace(c))
                return whitespace();
            if (c == '>' || (c == '/' && position + 1 < end && charAt(position + 1) == '>'))
            {
                position += c == '>' ? 1 : 2;
                inTag = false;
                return tag;
            }
            if (c == '"' || c == '\'')
            {
                position++;
                while (position < end && charAt(position) != c)
                    position++;
                position = Math.min(end, position + 1);
                return value;
            }
            if (c == '=')
            {
                position++;
                return punct;
            }
            if (isNameChar(c))
            {
                while (position < end && isNameChar(charAt(position)))
                    position++;
                return attribute;
            }
            position++;
            return Token.UNDEFINED;
        }

        private static boolean isNameChar(char c)
        {
            return Character.isLetterOrDigit(c) || c == ':' || c == '-' || c == '_' || c == '.';
        }
    }

    // =========================================================================
    // Markdown (классы WikiText — только здесь, после проверки бандлов)
    // =========================================================================

    private static final class MarkdownSupport
    {
        static SourceViewerConfiguration createConfiguration()
        {
            MarkupSourceViewerConfiguration configuration = new MarkupSourceViewerConfiguration(EditorsUI.getPreferenceStore())
            {
                /** Без проверки орфографии: в тексте в Markdown — код, имена и адреса, она их «исправляет». */
                @Override
                public IReconciler getReconciler(ISourceViewer sourceViewer)
                {
                    return null;
                }
            };
            configuration.setMarkupLanguage(new MarkdownLanguage());
            return configuration;
        }

        /** Разметка WikiText читает разбор из своего разбивщика документа (как MarkupDocumentProvider). */
        static IDocumentPartitioner installPartitioner(IDocument document)
        {
            FastMarkupPartitioner partitioner = new FastMarkupPartitioner();
            partitioner.setMarkupLanguage(new MarkdownLanguage());
            IDocumentPartitioner previous = document.getDocumentPartitioner();
            if (previous != null)
                previous.disconnect();
            partitioner.connect(document);
            document.setDocumentPartitioner(partitioner);
            return partitioner;
        }

        static String toHtml(String text)
        {
            return new MarkupParser(new MarkdownLanguage()).parseToHtml(text);
        }

        /** Id заголовка раздела, в котором стоит каретка; {@code null} — каретка до первого заголовка. */
        static String headingIdAt(String text, int offset)
        {
            OutlineItem root = new OutlineParser(new MarkdownLanguage()).parse(text);
            OutlineItem item = root != null ? root.findNearestMatchingOffset(offset) : null;
            return item != null && item != root ? item.getId() : null;
        }
    }

    // =========================================================================
    // «Готовый текст»
    // =========================================================================

    /** Оформленный вид текста (Markdown, HTML) в браузере; при показе — к месту под кареткой. */
    static final class Preview
    {
        private final Browser browser;

        private FormattedTextEditor source;

        private String pendingAnchor;

        /** Что найти после загрузки и выделять ли найденное (в тексте был выделен фрагмент). */
        private String pendingProbe;

        private boolean pendingKeepSelection;

        /** Выделение, поставленное при показе: при уходе с готового текста его в текст не переносим. */
        private String placedSelection;

        Preview(Browser browser, FormattedTextEditor source)
        {
            this.browser = browser;
            this.source = source;
            browser.addProgressListener(ProgressListener.completedAdapter(e -> scrollToAnchor()));
        }

        void setSource(FormattedTextEditor source)
        {
            this.source = source;
        }

        /** Показать текст источника; выделенный в нём фрагмент выделяется и здесь. */
        void render()
        {
            if (browser.isDisposed() || source == null)
                return;
            String text = source.text();
            if (text == null)
                text = ""; //$NON-NLS-1$
            pendingAnchor = null;
            ITextSelection selection = source.selection();
            pendingProbe = source.selectionProbe();
            pendingKeepSelection = selection != null && selection.getLength() > 0;
            placedSelection = null;
            if (source.format() == Format.MARKDOWN)
            {
                pendingAnchor = MarkdownSupport.headingIdAt(text, source.caretOffset());
                browser.setText(withStyle(MarkdownSupport.toHtml(text)));
            }
            else
                browser.setText(text);
        }

        /**
         * После загрузки — к месту текста: выделенный там фрагмент выделяется и здесь;
         * без выделения — прокрутка к строке под кареткой (для Markdown — иначе к её разделу).
         */
        private void scrollToAnchor()
        {
            if (browser.isDisposed())
                return;
            if (pendingProbe != null && !pendingProbe.isEmpty())
            {
                Object found = browser.evaluate("return " + findScript(pendingProbe, pendingKeepSelection) + ';'); //$NON-NLS-1$
                if (Boolean.TRUE.equals(found))
                {
                    placedSelection = pendingKeepSelection ? selectedText() : null;
                    return;
                }
            }
            if (pendingAnchor == null)
                return;
            browser.execute("var e=document.getElementById(" + jsString(pendingAnchor) + ");if(e){e.scrollIntoView();}"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /** Выделенное здесь — в текст; выделение, поставленное при показе, назад не переносим. */
        void transferSelectionToRaw()
        {
            if (browser.isDisposed() || source == null)
                return;
            String selected = selectedText();
            if (selected == null || selected.isBlank() || selected.equals(placedSelection))
                return;
            source.selectInRaw(selected);
        }

        private String selectedText()
        {
            try
            {
                Object value = browser.evaluate("return window.getSelection ? String(window.getSelection())" //$NON-NLS-1$
                    + " : (document.selection && document.selection.type != 'None' ? document.selection.createRange().text : '');"); //$NON-NLS-1$
                return value instanceof String text ? text : null;
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }

        /** Найти текст на странице: IE ({@code createTextRange}) и Chromium/Edge ({@code window.find}). */
        private static String findScript(String text, boolean keepSelection)
        {
            return "(function(t,k){try{" //$NON-NLS-1$
                + "if(document.body&&document.body.createTextRange){var r=document.body.createTextRange();" //$NON-NLS-1$
                + "if(r.findText(t)){r.scrollIntoView();if(!k){r.collapse(true);}r.select();return true;}return false;}" //$NON-NLS-1$
                + "if(window.find){var s=window.getSelection();s.removeAllRanges();" //$NON-NLS-1$
                + "if(window.find(t,false,false,true,false,false,false)){var n=s.anchorNode;" //$NON-NLS-1$
                + "var e=n&&(n.nodeType==1?n:n.parentNode);if(e&&e.scrollIntoView){e.scrollIntoView();}" //$NON-NLS-1$
                + "if(!k){s.collapseToStart();}return true;}}" //$NON-NLS-1$
                + "}catch(x){}return false;})(" + jsString(text) + ',' + keepSelection + ')'; //$NON-NLS-1$
        }

        private static String jsString(String text)
        {
            return '\'' + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
                .replace("</", "<\\/") + '\''; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String withStyle(String html)
        {
            String style = "<style>body{font-family:sans-serif;line-height:1.4;margin:12px 16px;}" //$NON-NLS-1$
                + "pre,code{font-family:monospace;}pre{padding:8px;border:1px solid #ccc;}" //$NON-NLS-1$
                + "table{border-collapse:collapse;}td,th{border:1px solid #ccc;padding:2px 6px;}</style>"; //$NON-NLS-1$
            int head = html.indexOf("</head>"); //$NON-NLS-1$
            return head >= 0 ? html.substring(0, head) + style + html.substring(head) : style + html;
        }
    }
}
