package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.text.DefaultPositionUpdater;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension2;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.AnnotationModelEvent;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.IAnnotationModelListener;
import org.eclipse.jface.text.source.IAnnotationModelListenerExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.resource.ILocationInFileProvider;
import org.eclipse.xtext.ui.editor.XtextSourceViewer;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.embedded.EmbeddedEditor;
import org.eclipse.xtext.ui.editor.hyperlinking.XtextHyperlink;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.util.ITextRegion;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.metadata.dbview.DbViewElement;
import com._1c.g5.v8.dt.qw.ui.ql.QlEditor;
import com._1c.g5.v8.dt.qw.ui.utils.ExpressionEditingType;

/**
 * Автодополнение в строковом литерале с текстом запроса без ИР (issue 837). Список даёт
 * штатный процессор языка запросов скрытого {@link QlEditor} — того же, на котором EDT
 * строит окно «Редактор запроса» ({@code QueryTextEditDialog}): в него кладётся текст
 * литерала целиком (поля из {@code ВЫБРАТЬ} разрешаются по {@code ИЗ} ниже каретки), а
 * предложения переносятся обратно в литерал BSL — с {@code |} в начале новых строк и
 * удвоенными кавычками. Список считается по сегменту, как у литерала-аргумента вызова
 * (issue 628): от начала слова, дальше его сужает наш фильтр.
 *
 * <p>Тем же скрытым редактором проверяются литералы-запросы видимой области модуля: ошибки
 * языка запросов показываются живыми маркерами без автопроверок проекта
 * ({@link #attachMarkers}).
 */
final class BslQueryLiteralSupport
{
    private BslQueryLiteralSupport() {}

    private static final String[] QUERY_START_WORDS =
        { "ВЫБРАТЬ", "SELECT", "УНИЧТОЖИТЬ", "DROP" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /** Предел строк литерала при разборе — защита от несбалансированных кавычек. */
    private static final int MAX_LINES = 5000;

    private static Shell hiddenShell;
    private static final Map<IProject, QlEditor> EDITORS = new HashMap<>();

    // Кэш ключа сегмента: зовётся на каждом такте набора из нескольких мест.
    private static IDocument keyDoc;
    private static long keyStamp = IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    private static int keyOffset = -1;
    private static int keyValue = -1;

    /** Текст литерала и смещения его символов в документе. */
    private static final class Literal
    {
        final StringBuilder text = new StringBuilder();
        int[] map = new int[64];
        /** Индекс в {@link #text} для запрошенного смещения документа; {@code -1} — не в литерале. */
        int probeIndex = -1;
        int openQuote = -1;
        /** Закрывающая кавычка; {@code -1} — литерал не закрыт. */
        int closeQuote = -1;
        /** Отступ перед {@code |} для новых строк вставки. */
        String indent = ""; //$NON-NLS-1$
        /** Сколько символов слова после {@link #probeIndex} убрано из текста скрытого редактора. */
        int removedLength;

        /** Смещение документа для индекса текста скрытого редактора (без убранного слова). */
        int docOffsetOfQuery(int index)
        {
            return docOffset(index > probeIndex ? index + removedLength : index);
        }

        void add(char c, int docOffset)
        {
            int i = text.length();
            if (i == map.length)
                map = Arrays.copyOf(map, i * 2);
            map[i] = docOffset;
            text.append(c);
        }

        /** Смещение документа для индекса текста (в том числе для конца текста). */
        int docOffset(int index)
        {
            if (index >= 0 && index < text.length())
                return map[index];
            if (closeQuote >= 0)
                return closeQuote;
            return text.length() > 0 ? map[text.length() - 1] + 1 : openQuote + 1;
        }

        boolean isQuery()
        {
            int i = 0;
            int n = text.length();
            while (i < n)
            {
                char c = text.charAt(i);
                if (Character.isWhitespace(c))
                    i++;
                else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/')
                {
                    while (i < n && text.charAt(i) != '\n')
                        i++;
                }
                else
                    break;
            }
            int start = i;
            while (i < n && Character.isLetter(text.charAt(i)))
                i++;
            // Первое слово ещё набирается — запросом литерал станет со следующим символом.
            if (i == n || i == start)
                return false;
            String word = text.substring(start, i);
            for (String w : QUERY_START_WORDS)
            {
                if (w.equalsIgnoreCase(word))
                    return true;
            }
            return false;
        }
    }

    /**
     * Литерал BSL, внутри которого {@code offset} (одно- или многострочный, строки
     * {@code //} между строками продолжения пропускаются). {@code whole} — дочитать до
     * закрывающей кавычки, иначе разбор останавливается на {@code offset}.
     */
    private static Literal parse(IDocument doc, int offset, boolean whole)
    {
        try
        {
            if (doc == null || offset < 0 || offset > doc.getLength())
                return null;
            int caretLine = doc.getLineOfOffset(offset);
            int openLine = caretLine;
            if (lineText(doc, caretLine).stripLeading().startsWith("|")) //$NON-NLS-1$
            {
                openLine = -1;
                for (int ln = caretLine - 1; ln >= 0 && caretLine - ln <= MAX_LINES; ln--)
                {
                    String s = lineText(doc, ln).stripLeading();
                    if (s.startsWith("|") || s.startsWith("//")) //$NON-NLS-1$ //$NON-NLS-2$
                        continue;
                    openLine = ln;
                    break;
                }
                if (openLine < 0)
                    return null;
            }
            IRegion openInfo = doc.getLineInformation(openLine);
            String openText = doc.get(openInfo.getOffset(), openInfo.getLength());
            int limit = openLine == caretLine ? offset - openInfo.getOffset() : openText.length();
            int quote = openQuoteBefore(openText, limit);
            if (quote < 0)
                return null;
            Literal lit = new Literal();
            lit.openQuote = openInfo.getOffset() + quote;
            lit.indent = leadingWhitespace(openText) + "\t"; //$NON-NLS-1$
            int close = scanLine(lit, openText, quote + 1, openInfo.getOffset(), offset);
            if (close >= 0)
                lit.closeQuote = openInfo.getOffset() + close;
            int prevEnd = openInfo.getOffset() + openInfo.getLength();
            boolean indentFromContinuation = false;
            for (int ln = openLine + 1; close < 0 && ln < doc.getNumberOfLines()
                && ln - openLine <= MAX_LINES; ln++)
            {
                if (!whole && lit.probeIndex >= 0)
                    break;
                IRegion li = doc.getLineInformation(ln);
                String t = doc.get(li.getOffset(), li.getLength());
                String stripped = t.stripLeading();
                if (stripped.startsWith("//")) //$NON-NLS-1$
                    continue;
                if (!stripped.startsWith("|")) //$NON-NLS-1$
                    break;
                int bar = t.length() - stripped.length();
                if (!indentFromContinuation || ln == caretLine)
                {
                    lit.indent = t.substring(0, bar);
                    indentFromContinuation = true;
                }
                lit.add('\n', prevEnd);
                close = scanLine(lit, t, bar + 1, li.getOffset(), offset);
                if (close >= 0)
                    lit.closeQuote = li.getOffset() + close;
                prevEnd = li.getOffset() + li.getLength();
            }
            return lit.probeIndex >= 0 ? lit : null;
        }
        catch (org.eclipse.jface.text.BadLocationException e)
        {
            return null;
        }
    }

    /**
     * Текст строки литерала от {@code from} до закрывающей кавычки ({@code ""} — одна
     * кавычка текста). Отмечает {@link Literal#probeIndex} на {@code probeOffset}.
     *
     * @return индекс закрывающей кавычки в строке или {@code -1}
     */
    private static int scanLine(Literal lit, String line, int from, int lineOffset, int probeOffset)
    {
        for (int i = from; i < line.length(); i++)
        {
            if (lineOffset + i == probeOffset)
                lit.probeIndex = lit.text.length();
            char c = line.charAt(i);
            if (c == '"')
            {
                if (i + 1 < line.length() && line.charAt(i + 1) == '"')
                {
                    lit.add('"', lineOffset + i);
                    i++;
                    continue;
                }
                return i;
            }
            lit.add(c, lineOffset + i);
        }
        if (lineOffset + line.length() == probeOffset)
            lit.probeIndex = lit.text.length();
        return -1;
    }

    /** Открывающая кавычка строки, не закрытая до {@code limit}; {@code -1} — вне строки. */
    private static int openQuoteBefore(String line, int limit)
    {
        boolean inString = false;
        int start = -1;
        int end = Math.min(limit, line.length());
        for (int i = 0; i < end; i++)
        {
            char c = line.charAt(i);
            if (!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/')
                break;
            if (c != '"')
                continue;
            if (!inString)
            {
                inString = true;
                start = i;
            }
            else if (i + 1 < end && line.charAt(i + 1) == '"')
                i++;
            else
                inString = false;
        }
        return inString ? start : -1;
    }

    private static String lineText(IDocument doc, int line) throws org.eclipse.jface.text.BadLocationException
    {
        IRegion info = doc.getLineInformation(line);
        return doc.get(info.getOffset(), info.getLength());
    }

    private static String leadingWhitespace(String line)
    {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i)))
            i++;
        return line.substring(0, i);
    }

    /**
     * Граница сегмента в литерале-запросе: символ перед началом текущего слова (как
     * кавычка или точка у литерала-аргумента); {@code -1} — не литерал с запросом.
     */
    static int segmentKeyAt(IDocument doc, int caret)
    {
        if (doc == null || caret < 0)
            return -1;
        long stamp = doc instanceof IDocumentExtension4 ext4
            ? ext4.getModificationStamp() : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
        if (doc == keyDoc && caret == keyOffset && stamp == keyStamp
            && stamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP)
            return keyValue;
        int key = -1;
        Literal lit = parse(doc, caret, false);
        if (lit != null && lit.isQuery())
        {
            int k = lit.probeIndex;
            while (k > 0 && isIdentifierChar(lit.text.charAt(k - 1)))
                k--;
            // Слово не переносится через строку: в документе оно стоит подряд до каретки.
            key = caret - (lit.probeIndex - k) - 1;
        }
        keyDoc = doc;
        keyStamp = stamp;
        keyOffset = caret;
        keyValue = key;
        return key;
    }

    private static boolean isIdentifierChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Полный список сегмента ({@code key} — его граница): зонд на начале слова, пустой
     * префикс. Только UI — {@link QlEditor} живёт на виджетах.
     */
    static ICompletionProposal[] compute(ITextViewer viewer, int key)
    {
        if (Display.getCurrent() == null)
            return SmartContentAssistProcessor.EMPTY;
        IDocument doc = viewer != null ? viewer.getDocument() : null;
        Literal lit = parse(doc, key + 1, true);
        if (lit == null || !lit.isQuery())
            return SmartContentAssistProcessor.EMPTY;
        IProject project = resolveProject(viewer);
        if (project == null)
            return SmartContentAssistProcessor.EMPTY;
        // Набранное в сегменте до каретки в текст запроса не кладём: процессор языка запросов
        // сужает список по слову под зондом даже на его начале (лог 18.10.2026: в корне
        // «К» давало 2 варианта). Список сегмента — полный, сужает его наш фильтр. Слово
        // после каретки — уже написанный текст: без него запрос ломается (перед
        // «СрезПоследних(…)» — 0 вариантов).
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
        int typed = Math.max(0, caret - (key + 1));
        int wordEnd = lit.probeIndex;
        while (wordEnd < lit.text.length() && wordEnd - lit.probeIndex < typed
            && isIdentifierChar(lit.text.charAt(wordEnd)))
            wordEnd++;
        lit.removedLength = wordEnd - lit.probeIndex;
        String query = lit.text.substring(0, lit.probeIndex) + lit.text.substring(wordEnd);
        try
        {
            QlEditor editor = hiddenEditor(project);
            EmbeddedEditor embedded = editor.getEmbeddedQlEditor();
            XtextSourceViewer qlViewer = embedded.getViewer();
            if (!query.equals(embedded.getDocument().get()))
                editor.setFullText(query);
            // Контекст автодополнения Xtext берёт выделение у viewer.
            qlViewer.setSelectedRange(lit.probeIndex, 0);
            ContentAssistant assistant = ContentAssistPatcher.getContentAssistant(qlViewer);
            IContentAssistProcessor processor = assistant != null
                ? assistant.getContentAssistProcessor(IDocument.DEFAULT_CONTENT_TYPE) : null;
            while (processor instanceof SmartContentAssistProcessor smart)
                processor = smart.getDelegate();
            ICompletionProposal[] raw = processor != null
                ? processor.computeCompletionProposals(qlViewer, lit.probeIndex) : null;
            String lineBreak = TextUtilities.getDefaultLineDelimiter(doc) + lit.indent + "|"; //$NON-NLS-1$
            List<ICompletionProposal> result = new ArrayList<>(raw != null ? raw.length : 0);
            // Шаблоны (не ConfigurableCompletionProposal) в литерал не перенести.
            for (int i = 0; raw != null && i < raw.length; i++)
            {
                if (raw[i] instanceof ConfigurableCompletionProposal cp)
                    result.add(new QueryLiteralProposal(cp, lit, lineBreak));
            }
            return result.toArray(SmartContentAssistProcessor.EMPTY);
        }
        catch (Exception | LinkageError e)
        {
            // Сбой языка запросов — список литерала пуст, набор не страдает.
            return SmartContentAssistProcessor.EMPTY;
        }
    }

    private static IProject resolveProject(ITextViewer viewer)
    {
        return resolveProject(SmartContentAssistProcessor.resolveEditorForLiteralAudit(viewer));
    }

    private static IProject resolveProject(BslXtextEditor editor)
    {
        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);
        IProject project = dtProject != null ? dtProject.getWorkspaceProject() : null;
        return project != null && project.isOpen() ? project : null;
    }

    /** Скрытый редактор запроса проекта: окно никогда не открывается. */
    private static QlEditor hiddenEditor(IProject project)
    {
        QlEditor editor = EDITORS.get(project);
        if (editor != null && editor.getParent() != null && !editor.getParent().isDisposed())
            return editor;
        if (hiddenShell == null || hiddenShell.isDisposed())
        {
            hiddenShell = new Shell(Display.getCurrent(), SWT.NONE);
            hiddenShell.setLayout(new GridLayout());
            EDITORS.clear();
        }
        Composite host = new Composite(hiddenShell, SWT.NONE);
        host.setLayout(new GridLayout());
        editor = new QlEditor(project, host, ExpressionEditingType.NONE, false, false);
        EDITORS.put(project, editor);
        return editor;
    }

    /** Предложение языка запросов, перенесённое в литерал BSL. */
    private static final class QueryLiteralProposal implements ICompletionProposal,
        ICompletionProposalExtension2, ICompletionProposalExtension3,
        ICompletionProposalExtension5, ICompletionProposalExtension6
    {
        private final ConfigurableCompletionProposal ql;
        private final int start;
        private final int end;
        private final String replacement;
        private final String insert;
        private final int cursor;
        private Point selection;

        QueryLiteralProposal(ConfigurableCompletionProposal ql, Literal lit, String lineBreak)
        {
            this.ql = ql;
            int offset = ql.getReplacementOffset();
            this.start = lit.docOffsetOfQuery(offset);
            this.end = Math.max(start, lit.docOffsetOfQuery(offset + ql.getReplacementLength()));
            this.replacement = ql.getReplacementString() != null ? ql.getReplacementString() : ""; //$NON-NLS-1$
            this.insert = escape(replacement, lineBreak);
            int cursorPosition = Math.max(0, Math.min(ql.getCursorPosition(), replacement.length()));
            this.cursor = escape(replacement.substring(0, cursorPosition), lineBreak).length();
        }

        private static String escape(String text, String lineBreak)
        {
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
            StringBuilder sb = new StringBuilder(normalized.length() + 8);
            for (int i = 0; i < normalized.length(); i++)
            {
                char c = normalized.charAt(i);
                if (c == '"')
                    sb.append("\"\""); //$NON-NLS-1$
                else if (c == '\n')
                    sb.append(lineBreak);
                else
                    sb.append(c);
            }
            return sb.toString();
        }

        private void applyTo(IDocument document, int offset)
        {
            if (document == null)
                return;
            try
            {
                // Набранное после расчёта списка тоже заменяется: список посчитан от начала слова.
                int to = Math.min(document.getLength(), Math.max(end, offset));
                document.replace(start, Math.max(0, to - start), insert);
                selection = new Point(start + cursor, 0);
            }
            catch (org.eclipse.jface.text.BadLocationException e)
            {
                // Литерал изменился после расчёта списка — вставку пропускаем.
            }
        }

        @Override
        public void apply(IDocument document)
        {
            applyTo(document, end);
        }

        @Override
        public void apply(ITextViewer viewer, char trigger, int stateMask, int offset)
        {
            applyTo(viewer != null ? viewer.getDocument() : null, offset);
        }

        @Override
        public Point getSelection(IDocument document)
        {
            return selection;
        }

        @Override
        public String getAdditionalProposalInfo()
        {
            return ql.getAdditionalProposalInfo();
        }

        @Override
        public Object getAdditionalProposalInfo(IProgressMonitor monitor)
        {
            return ql.getAdditionalProposalInfo(monitor);
        }

        @Override
        public String getDisplayString()
        {
            return ql.getDisplayString();
        }

        @Override
        public StyledString getStyledDisplayString()
        {
            return ql.getStyledDisplayString();
        }

        @Override
        public Image getImage()
        {
            return ql.getImage();
        }

        @Override
        public IContextInformation getContextInformation()
        {
            return null;
        }

        @Override
        public void selected(ITextViewer viewer, boolean smartToggle)
        {
        }

        @Override
        public void unselected(ITextViewer viewer)
        {
        }

        @Override
        public boolean validate(IDocument document, int offset, DocumentEvent event)
        {
            if (document == null || offset < start)
                return false;
            try
            {
                String typed = document.get(start, offset - start);
                return replacement.regionMatches(true, 0, typed, 0, typed.length());
            }
            catch (org.eclipse.jface.text.BadLocationException e)
            {
                return false;
            }
        }

        @Override
        public IInformationControlCreator getInformationControlCreator()
        {
            return ql.getInformationControlCreator();
        }

        @Override
        public CharSequence getPrefixCompletionText(IDocument document, int completionOffset)
        {
            return null;
        }

        @Override
        public int getPrefixCompletionStart(IDocument document, int completionOffset)
        {
            return start;
        }
    }

    // --- «Перейти к определению» и Ctrl+клик в литерале-запросе (issue 837) ---

    /**
     * Ссылка языка запросов под {@code offset} — та же, что даёт Ctrl+клик в окне «Редактор
     * запроса»: детектор гиперссылок скрытого {@link QlEditor}. Область переносится в литерал.
     * {@code null} — не литерал-запрос или ссылки нет. Только UI.
     */
    static IHyperlink queryLink(BslXtextEditor editor, ITextViewer viewer, int offset)
    {
        if (editor == null || viewer == null || Display.getCurrent() == null)
            return null;
        Literal lit = parse(viewer.getDocument(), offset, true);
        if (lit == null || !lit.isQuery())
            return null;
        IProject project = resolveProject(editor);
        if (project == null)
            return null;
        String query = lit.text.toString();
        try
        {
            QlEditor ql = hiddenEditor(project);
            EmbeddedEditor embedded = ql.getEmbeddedQlEditor();
            if (!query.equals(embedded.getDocument().get()))
                ql.setFullText(query);
            XtextSourceViewer qlViewer = embedded.getViewer();
            IHyperlinkDetector[] detectors = embedded.getConfiguration().getHyperlinkDetectors(qlViewer);
            if (detectors == null && Global.getField(qlViewer, "fHyperlinkDetectors") instanceof IHyperlinkDetector[] own) //$NON-NLS-1$
                detectors = own;
            for (int i = 0; detectors != null && i < detectors.length; i++)
            {
                IHyperlink[] links = detectors[i] == null ? null
                    : detectors[i].detectHyperlinks(qlViewer, new Region(lit.probeIndex, 0), false);
                if (links == null || links.length == 0 || links[0] == null)
                    continue;
                IRegion region = links[0].getHyperlinkRegion();
                int start = lit.docOffset(region.getOffset());
                int end = Math.max(start, lit.docOffset(region.getOffset() + region.getLength()));
                return new QueryHyperlink(links[0], new Region(start, end - start), editor, project, query,
                    lit.openQuote);
            }
            return null;
        }
        catch (Exception | LinkageError e)
        {
            // Сбой языка запросов — ссылки нет, остальные ссылки модуля работают.
            return null;
        }
    }

    /**
     * Ссылка языка запросов в литерале BSL. Цель внутри самого запроса (псевдоним, временная
     * таблица) лежит в ресурсе скрытого редактора — туда не переходим, а выделяем то же место
     * в литерале. Прочие цели (объекты метаданных) открывает штатная ссылка.
     */
    private static final class QueryHyperlink implements IHyperlink
    {
        private final IHyperlink delegate;
        private final IRegion region;
        private final BslXtextEditor editor;
        private final IProject project;
        private final String query;
        private final int openQuote;

        QueryHyperlink(IHyperlink delegate, IRegion region, BslXtextEditor editor, IProject project,
                       String query, int openQuote)
        {
            this.delegate = delegate;
            this.region = region;
            this.editor = editor;
            this.project = project;
            this.query = query;
            this.openQuote = openQuote;
        }

        @Override public IRegion getHyperlinkRegion() { return region; }
        @Override public String getTypeLabel() { return delegate.getTypeLabel(); }
        @Override public String getHyperlinkText() { return delegate.getHyperlinkText(); }

        @Override
        public void open()
        {
            try
            {
                if (delegate instanceof XtextHyperlink xtext && xtext.getURI() != null
                    && (openInsideLiteral(xtext.getURI()) || openMdObjectOfDbView(xtext.getURI())))
                    return;
                delegate.open();
            }
            catch (Exception | LinkageError e)
            {
                // Цель не открылась — переход не выполняется, как у штатной ссылки без цели.
            }
        }

        /**
         * Поле таблицы запроса — элемент описания таблицы ({@code dbViewDefs/…/fields:Валюта}),
         * а не реквизит: штатное открытие ведёт на главную страницу объекта. Открываем реквизит,
         * на котором поле построено ({@link DbViewElement#getMdObject()}), — редактор переходит
         * на его страницу и активирует его.
         *
         * @return {@code false} — цель не элемент описания таблицы или реквизита нет
         */
        private boolean openMdObjectOfDbView(org.eclipse.emf.common.util.URI target)
        {
            return BslQueryLiteralSupport.openMdObjectOfDbView(hiddenEditor(project).getEmbeddedQlEditor().getDocument(),
                target, editor, editor.getSite().getShell());
        }

        /** Цель в тексте запроса — выделить её в литерале; {@code false} — цель снаружи. */
        private boolean openInsideLiteral(org.eclipse.emf.common.util.URI target)
        {
            QlEditor ql = hiddenEditor(project);
            EmbeddedEditor embedded = ql.getEmbeddedQlEditor();
            // Скрытый редактор мог взять другой текст (автодополнение, маркеры) — вернуть свой.
            if (!query.equals(embedded.getDocument().get()))
                ql.setFullText(query);
            int[] found = embedded.getDocument().readOnly(resource ->
            {
                if (resource == null || resource.getURI() == null
                    || !resource.getURI().equals(target.trimFragment()))
                    return null;
                EObject object = resource.getEObject(target.fragment());
                ILocationInFileProvider locations = object == null ? null
                    : resource.getResourceServiceProvider().get(ILocationInFileProvider.class);
                ITextRegion text = locations == null ? null : locations.getSignificantTextRegion(object);
                return text == null ? null : new int[] { text.getOffset(), text.getLength() };
            });
            if (found == null)
                return false;
            ISourceViewer viewer = editor.getInternalSourceViewer();
            Literal lit = viewer == null ? null : parse(viewer.getDocument(), openQuote + 1, true);
            if (lit == null || !query.equals(lit.text.toString()))
                return true;
            int start = lit.docOffset(found[0]);
            int end = Math.max(start, lit.docOffset(found[0] + found[1]));
            editor.selectAndReveal(start, end - start);
            return true;
        }
    }

    /**
     * Поле таблицы запроса — элемент описания таблицы ({@code dbViewDefs/…/fields:Валюта}), а не
     * реквизит: штатное открытие такой ссылки ведёт на главную страницу объекта (и в окне
     * «Редактор запроса» тоже). Открываем реквизит, на котором поле построено
     * ({@link DbViewElement#getMdObject()}), — редактор переходит на его страницу и активирует его.
     *
     * @param document документ запроса — в его наборе ресурсов разрешается {@code target}
     * @return {@code false} — цель не элемент описания таблицы или реквизита нет
     */
    static boolean openMdObjectOfDbView(org.eclipse.xtext.ui.editor.model.IXtextDocument document,
                                        org.eclipse.emf.common.util.URI target, IEditorPart editor, Shell shell)
    {
        EObject md = document == null || target == null ? null : document.readOnly(resource ->
        {
            if (resource == null || resource.getResourceSet() == null)
                return null;
            EObject object = resource.getResourceSet().getEObject(target, true);
            if (!(object instanceof DbViewElement element) || element.getMdObject() == null)
                return null;
            EObject owner = element.getMdObject();
            return owner.eIsProxy() ? org.eclipse.emf.ecore.util.EcoreUtil.resolve(owner, resource) : owner;
        });
        if (md == null || md.eIsProxy())
            return false;
        CompareConfigOpenObjectHandler.openInEditor(md, editor, shell);
        return true;
    }

    /**
     * Окно «Редактор запроса»: ссылки его редактора на поле таблицы открывают реквизит, как
     * в литерале-запросе модуля. Элементы общего с {@code HyperlinkManager} массива детекторов
     * меняются по месту (как в {@code BslEditorHyperlinkHook.attach}). Только UI.
     */
    static void wrapQueryEditorHyperlinks(ISourceViewer viewer)
    {
        if (!(Global.getField(viewer, "fHyperlinkDetectors") instanceof IHyperlinkDetector[] detectors)) //$NON-NLS-1$
            return;
        synchronized (detectors)
        {
            for (int i = 0; i < detectors.length; i++)
            {
                IHyperlinkDetector detector = detectors[i];
                if (detector == null || detector instanceof DbViewLinkDetector)
                    continue;
                detectors[i] = detector instanceof IHyperlinkDetectorExtension2
                    ? new ModifiedDbViewLinkDetector(detector) : new DbViewLinkDetector(detector);
            }
        }
    }

    private static class DbViewLinkDetector implements IHyperlinkDetector, IHyperlinkDetectorExtension
    {
        final IHyperlinkDetector delegate;

        DbViewLinkDetector(IHyperlinkDetector delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public IHyperlink[] detectHyperlinks(ITextViewer viewer, IRegion region, boolean multiple)
        {
            IHyperlink[] links = delegate.detectHyperlinks(viewer, region, multiple);
            if (links == null)
                return null;
            IHyperlink[] result = links.clone();
            for (int i = 0; i < result.length; i++)
            {
                if (result[i] instanceof XtextHyperlink xtext && xtext.getURI() != null
                    && viewer.getDocument() instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument document)
                    result[i] = new DbViewAwareLink(xtext, document, viewer);
            }
            return result;
        }

        @Override
        public void dispose()
        {
            if (delegate instanceof IHyperlinkDetectorExtension extension)
                extension.dispose();
        }
    }

    private static final class ModifiedDbViewLinkDetector extends DbViewLinkDetector
        implements IHyperlinkDetectorExtension2
    {
        ModifiedDbViewLinkDetector(IHyperlinkDetector delegate)
        {
            super(delegate);
        }

        @Override
        public int getStateMask()
        {
            return ((IHyperlinkDetectorExtension2) delegate).getStateMask();
        }
    }

    private static final class DbViewAwareLink implements IHyperlink
    {
        private final XtextHyperlink delegate;
        private final org.eclipse.xtext.ui.editor.model.IXtextDocument document;
        private final ITextViewer viewer;

        DbViewAwareLink(XtextHyperlink delegate, org.eclipse.xtext.ui.editor.model.IXtextDocument document,
                        ITextViewer viewer)
        {
            this.delegate = delegate;
            this.document = document;
            this.viewer = viewer;
        }

        @Override public IRegion getHyperlinkRegion() { return delegate.getHyperlinkRegion(); }
        @Override public String getTypeLabel() { return delegate.getTypeLabel(); }
        @Override public String getHyperlinkText() { return delegate.getHyperlinkText(); }

        @Override
        public void open()
        {
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window == null ? null : window.getActivePage();
                IEditorPart active = page == null ? null : page.getActiveEditor();
                Shell shell = viewer.getTextWidget() != null ? viewer.getTextWidget().getShell() : null;
                if (openMdObjectOfDbView(document, delegate.getURI(), active, shell))
                    return;
            }
            catch (Exception | LinkageError e)
            {
                // Реквизит не открылся — штатное открытие ниже.
            }
            delegate.open();
        }
    }

    // --- Окно «Редактор запроса» из модуля: перенос выделения туда и обратно (issue 837) ---

    /**
     * Окно «Редактор запроса», открытое из литерала модуля. Выделение модуля переносится в
     * окно (EDT считает начальную позицию без учёта «|» и отступов), а при закрытии — обратно
     * (EDT выделяет весь новый текст запроса). Автопереход окна на первую ошибку снимается.
     * Вызывать при показе окна. Только UI.
     */
    static void bridgeQueryEditorDialog(Shell shell, ISourceViewer qlViewer)
    {
        if (shell == null || shell.isDisposed() || qlViewer == null || qlViewer.getDocument() == null)
            return;
        removeFirstErrorJump(qlViewer);
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        BslXtextEditor editor = page == null ? null : GetRef.getActiveBslEditor(page.getActivePart());
        ISourceViewer bslViewer = editor == null ? null : editor.getInternalSourceViewer();
        IDocument bslDocument = bslViewer == null ? null : bslViewer.getDocument();
        // Координаты модели, не виджета (свёртки).
        Point selection = bslViewer == null ? null : bslViewer.getSelectedRange();
        Literal start = selection == null ? null : parse(bslDocument, selection.x, true);
        Literal end = start == null ? null : parse(bslDocument, selection.x + selection.y, true);
        String dialogText = qlViewer.getDocument().get();
        // Окно открывается на любом литерале, не только начинающемся с ВЫБРАТЬ.
        if (start == null)
            return;
        String literalText = start.text.toString();
        int from = mapQueryIndex(literalText, start.probeIndex, dialogText);
        int to = end != null && end.openQuote == start.openQuote
            ? mapQueryIndex(literalText, end.probeIndex, dialogText) : from;
        qlViewer.setSelectedRange(from, Math.max(0, to - from));
        qlViewer.revealRange(from, Math.max(0, to - from));
        final int openQuote = start.openQuote;
        shell.addListener(SWT.Dispose, event ->
        {
            // Окно ещё цело: Dispose приходит до уничтожения дочерних виджетов.
            StyledText widget = qlViewer.getTextWidget();
            if (widget == null || widget.isDisposed() || qlViewer.getDocument() == null)
                return;
            Point closing = qlViewer.getSelectedRange();
            String closingText = qlViewer.getDocument().get();
            // После замены литерала и выделения всего текста штатным обработчиком закрытия.
            Display.getCurrent().asyncExec(() -> restoreModuleSelection(editor, openQuote, closingText, closing));
        });
    }

    /** Снять слушатель {@code QlEditor}, ставящий каретку на первую ошибку после проверки. */
    private static void removeFirstErrorJump(ISourceViewer qlViewer)
    {
        IAnnotationModel model = qlViewer.getAnnotationModel();
        if (!(Global.getField(model, "fAnnotationModelListeners") instanceof List<?> listeners)) //$NON-NLS-1$
            return;
        for (Object listener : new ArrayList<>(listeners))
        {
            // Анонимный QlEditor$… из addAnnotationModelListenerForChangeCursorPosition(initPosition).
            if (listener instanceof IAnnotationModelListener annotationListener
                && listener.getClass().getName().startsWith("com._1c.g5.v8.dt.qw.ui.ql.QlEditor$") //$NON-NLS-1$
                && Global.getField(listener, "val$initPosition") != null) //$NON-NLS-1$
                model.removeAnnotationModelListener(annotationListener);
        }
    }

    private static void restoreModuleSelection(BslXtextEditor editor, int openQuote, String dialogText,
                                               Point dialogSelection)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null || viewer.getTextWidget() == null || viewer.getTextWidget().isDisposed())
            return;
        Literal lit = parse(viewer.getDocument(), openQuote + 1, true);
        if (lit == null || lit.openQuote != openQuote)
            return;
        String literalText = lit.text.toString();
        int from = mapQueryIndex(dialogText, dialogSelection.x, literalText);
        int to = mapQueryIndex(dialogText, dialogSelection.x + dialogSelection.y, literalText);
        int start = lit.docOffset(from);
        int end = Math.max(start, lit.docOffset(to));
        editor.selectAndReveal(start, end - start);
    }

    /**
     * Позиция {@code index} текста запроса {@code from} в другом представлении того же запроса
     * {@code to} (литерал модуля ↔ окно: другие переводы строк, отступы, форматирование).
     * Строка ищется по содержимому без отступов (сначала та же по номеру), столбец считается
     * от начала текста строки.
     */
    static int mapQueryIndex(String from, int index, String to)
    {
        List<int[]> fromLines = lineBounds(from);
        List<int[]> toLines = lineBounds(to);
        int line = 0;
        while (line + 1 < fromLines.size() && fromLines.get(line + 1)[0] <= index)
            line++;
        int[] source = fromLines.get(line);
        String sourceText = from.substring(source[0], source[1]);
        int sourceLead = sourceText.length() - sourceText.stripLeading().length();
        int column = Math.max(0, Math.min(index, source[1]) - source[0]);
        String key = sourceText.strip();
        int target = -1;
        if (line < toLines.size() && to.substring(toLines.get(line)[0], toLines.get(line)[1]).strip().equals(key))
            target = line;
        for (int distance = 1; target < 0 && distance < Math.max(fromLines.size(), toLines.size()); distance++)
        {
            for (int candidate : new int[] { line - distance, line + distance })
            {
                if (candidate >= 0 && candidate < toLines.size()
                    && to.substring(toLines.get(candidate)[0], toLines.get(candidate)[1]).strip().equals(key))
                {
                    target = candidate;
                    break;
                }
            }
        }
        if (target < 0)
            target = Math.min(line, toLines.size() - 1);
        int[] bounds = toLines.get(target);
        String targetText = to.substring(bounds[0], bounds[1]);
        int targetLead = targetText.length() - targetText.stripLeading().length();
        int mapped = column <= sourceLead ? Math.min(column, targetLead) : targetLead + (column - sourceLead);
        return bounds[0] + Math.min(mapped, targetText.length());
    }

    /** Границы строк текста без перевода строки ({@code \r\n} или {@code \n}). */
    private static List<int[]> lineBounds(String text)
    {
        List<int[]> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++)
        {
            if (i == text.length() || text.charAt(i) == '\n')
            {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                result.add(new int[] { start, end });
                start = i + 1;
            }
        }
        return result;
    }

    // --- Живые маркеры ошибок запроса в видимой области редактора (issue 837) ---

    // Стандартные типы: их рисует любой текстовый редактор (подчёркивание, полоса, обзор).
    private static final String ERROR_TYPE = "org.eclipse.ui.workbench.texteditor.error"; //$NON-NLS-1$
    private static final String WARNING_TYPE = "org.eclipse.ui.workbench.texteditor.warning"; //$NON-NLS-1$
    private static final String INFO_TYPE = "org.eclipse.ui.workbench.texteditor.info"; //$NON-NLS-1$
    private static final int MARKERS_DELAY_MS = 600;
    /** Сколько литералов видимой области проверяется за проход. */
    private static final int MARKERS_MAX_LITERALS = 20;
    /**
     * Начало текста маркера штатной проверки {@code bsl-ql-hub} ({@code BslQlDelegatingCheck},
     * сообщение «Запрос в строковом литерале: {0}»).
     */
    private static final String[] HUB_PREFIXES =
        { "Запрос в строковом литерале: ", "Query in string literal: " }; //$NON-NLS-1$ //$NON-NLS-2$
    private static final Map<BslXtextEditor, MarkerSession> MARKER_SESSIONS = new WeakHashMap<>();
    /** Ответы валидатора по тексту запроса: прокрутка и правки вне литерала не проверяют его заново. */
    private static final Map<String, List<Issue>> ISSUES_CACHE = new LinkedHashMap<>(16, 0.75f, true)
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Issue>> eldest)
        {
            return size() > 64;
        }
    };

    /**
     * Живые маркеры ошибок запросов в литералах видимой области редактора модуля: без
     * автопроверок проекта, после паузы в наборе и при прокрутке. Повторный вызов ничего не
     * делает. Только UI.
     */
    static void attachMarkers(BslXtextEditor editor)
    {
        if (editor == null || MARKER_SESSIONS.containsKey(editor))
            return;
        installMarkersPreferenceListener();
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null || viewer.getDocument() == null || viewer.getTextWidget() == null
            || viewer.getTextWidget().isDisposed())
            return;
        MarkerSession session = new MarkerSession(editor, viewer);
        MARKER_SESSIONS.put(editor, session);
        session.install();
        session.schedule(MARKERS_DELAY_MS);
    }

    private static boolean markersPreferenceListenerInstalled;

    /**
     * Живые маркеры показываются вместе с раскраской запросов плагина: переключение флажка
     * «Синтаксическая раскраска запросов в модуле» сразу перепроверяет открытые редакторы.
     */
    private static void installMarkersPreferenceListener()
    {
        if (markersPreferenceListenerInstalled)
            return;
        markersPreferenceListenerInstalled = true;
        // Хранилище, в которое пишет страница «Редактор кода»: у Activator — другой экземпляр,
        // его слушатель смену флажка не получал.
        ComfortSettings.getInstance().getPreferenceStore().addPropertyChangeListener(event ->
        {
            if (!ComfortSettings.PREF_QUERY_LITERAL_HIGHLIGHTING_ENABLED.equals(event.getProperty()))
                return;
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() ->
                {
                    for (MarkerSession session : new ArrayList<>(MARKER_SESSIONS.values()))
                        session.schedule(0);
                });
        });
    }

    /**
     * Живая отметка ошибки запроса. Быстрых исправлений у неё нет: штатные по её позиции —
     * исправления хаба («Подавить предупреждение» вставляет {@code //@skip-check bsl-ql-hub}),
     * к живой отметке они не относятся.
     */
    static boolean isLiveQueryAnnotation(Annotation annotation)
    {
        return annotation instanceof QueryProblemAnnotation;
    }

    /** Ошибка запроса в литерале, найденная проверкой скрытого редактора запроса. */
    private static final class QueryProblemAnnotation extends Annotation
    {
        QueryProblemAnnotation(String type, String text)
        {
            super(type, false, text);
        }
    }

    /** Категория позиций документа: литералы под живой проверкой. */
    private static final String POSITION_CATEGORY = "tormozit.queryLiteralMarkers"; //$NON-NLS-1$

    /**
     * Литерал под живой проверкой. Только под покрытием отметки хаба заменены живыми; вне его
     * их не трогаем. Диапазон сдвигается вместе с текстом. Только UI.
     */
    private static final class Coverage
    {
        final Position range;
        String query;
        final List<QueryProblemAnnotation> ours = new ArrayList<>();
        /** Сколько отметок хаба убрано под этим покрытием. */
        int hubHidden;

        Coverage(Position range)
        {
            this.range = range;
        }

        boolean overlaps(int offset, int length)
        {
            return !range.isDeleted() && offset <= range.getOffset() + range.getLength()
                && offset + Math.max(0, length) >= range.getOffset();
        }
    }

    private static final class MarkerSession
    {
        final BslXtextEditor editor;
        final ISourceViewer viewer;
        final IDocument document;
        final List<Coverage> coverages = new ArrayList<>();
        final IDocumentListener documentListener = new IDocumentListener()
        {
            @Override
            public void documentAboutToBeChanged(DocumentEvent event)
            {
            }

            @Override
            public void documentChanged(DocumentEvent event)
            {
                schedule(MARKERS_DELAY_MS);
            }
        };
        final IViewportListener viewportListener = verticalOffset -> schedule(MARKERS_DELAY_MS);
        /** Отметки хаба пересозданы (сохранение, автопроверка) — под покрытием убрать сразу. */
        final IAnnotationModelListener annotationListener = new AnnotationListener();
        DefaultPositionUpdater positionUpdater;
        IAnnotationModel listenedModel;
        int generation;

        MarkerSession(BslXtextEditor editor, ISourceViewer viewer)
        {
            this.editor = editor;
            this.viewer = viewer;
            this.document = viewer.getDocument();
        }

        void install()
        {
            if (!document.containsPositionCategory(POSITION_CATEGORY))
                document.addPositionCategory(POSITION_CATEGORY);
            positionUpdater = new DefaultPositionUpdater(POSITION_CATEGORY);
            document.addPositionUpdater(positionUpdater);
            document.addDocumentListener(documentListener);
            viewer.addViewportListener(viewportListener);
            listenedModel = viewer.getAnnotationModel();
            if (listenedModel != null)
                listenedModel.addAnnotationModelListener(annotationListener);
            viewer.getTextWidget().addDisposeListener(e -> dispose());
        }

        void dispose()
        {
            if (!MARKER_SESSIONS.containsKey(editor))
                return;
            ++generation;
            MARKER_SESSIONS.remove(editor);
            try
            {
                List<Annotation> remove = new ArrayList<>();
                for (Coverage coverage : new ArrayList<>(coverages))
                    dropCoverage(coverage, remove);
                IAnnotationModel visual = viewer.getAnnotationModel();
                if (visual != null)
                    remove.forEach(visual::removeAnnotation);
            }
            catch (RuntimeException e)
            {
                // Редактор уже закрыт — модель аннотаций ушла вместе с ним.
            }
            try
            {
                document.removeDocumentListener(documentListener);
                document.removePositionUpdater(positionUpdater);
                viewer.removeViewportListener(viewportListener);
                if (listenedModel != null)
                    listenedModel.removeAnnotationModelListener(annotationListener);
            }
            catch (RuntimeException e)
            {
                // Редактор уже закрыт.
            }
        }

        void schedule(int delayMs)
        {
            final int gen = ++generation;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.timerExec(delayMs, () ->
            {
                if (gen == generation)
                    run();
            });
        }

        private Position track(int offset, int length)
        {
            try
            {
                Position position = new Position(offset, Math.max(0, length));
                document.addPosition(POSITION_CATEGORY, position);
                return position;
            }
            catch (org.eclipse.jface.text.BadLocationException
                | org.eclipse.jface.text.BadPositionCategoryException e)
            {
                return null;
            }
        }

        private void untrack(Position position)
        {
            try
            {
                document.removePosition(POSITION_CATEGORY, position);
            }
            catch (org.eclipse.jface.text.BadPositionCategoryException e)
            {
                // Категория уже снята.
            }
        }

        private void run()
        {
            StyledText widget = viewer.getTextWidget();
            if (widget == null || widget.isDisposed())
            {
                dispose();
                return;
            }
            IAnnotationModel model = viewer.getAnnotationModel();
            if (model == null || viewer.getDocument() != document)
                return;
            // Флажок раскраски запросов выключен — живых маркеров нет, отметки хаба на месте.
            if (!ComfortSettings.isQueryLiteralHighlightingEnabled())
            {
                if (coverages.isEmpty())
                    return;
                List<Annotation> remove = new ArrayList<>();
                boolean hubWasHidden = false;
                for (Coverage coverage : new ArrayList<>(coverages))
                    hubWasHidden |= dropCoverage(coverage, remove);
                if (model instanceof IAnnotationModelExtension extension)
                    extension.replaceAnnotations(remove.toArray(new Annotation[0]), Map.of());
                else
                    remove.forEach(model::removeAnnotation);
                if (hubWasHidden)
                    requestRevalidation();
                return;
            }
            int top = Math.max(0, viewer.getTopIndex());
            int bottom = Math.max(top, Math.min(viewer.getBottomIndex(), document.getNumberOfLines() - 1));
            int visStart;
            int visEnd;
            try
            {
                visStart = document.getLineOffset(top);
                visEnd = document.getLineOffset(bottom) + document.getLineLength(bottom);
            }
            catch (org.eclipse.jface.text.BadLocationException e)
            {
                return;
            }
            List<Literal> literals = visibleQueryLiterals(document, top, bottom);
            IProject project = literals.isEmpty() ? null : resolveProject(editor);
            List<Annotation> remove = new ArrayList<>();
            Map<Annotation, Position> add = new LinkedHashMap<>();
            java.util.Set<Coverage> matched = new java.util.HashSet<>();
            for (Literal lit : literals)
            {
                if (project == null)
                    break;
                int start = lit.openQuote;
                int end = lit.closeQuote >= 0 ? lit.closeQuote + 1 : lit.docOffset(lit.text.length());
                Coverage coverage = null;
                for (Coverage candidate : coverages)
                {
                    if (!candidate.range.isDeleted() && candidate.range.getOffset() == start)
                        coverage = candidate;
                }
                String query = lit.text.toString();
                String cacheKey = project.getName() + '\n' + query;
                List<Issue> issues = ISSUES_CACHE.get(cacheKey);
                if (issues == null)
                {
                    issues = validate(project, query);
                    if (issues == null)
                    {
                        // Сбой проверки: покрытие (если было) не трогаем.
                        if (coverage != null)
                            matched.add(coverage);
                        continue;
                    }
                    ISSUES_CACHE.put(cacheKey, issues);
                }
                if (coverage == null)
                {
                    Position range = track(start, end - start);
                    if (range == null)
                        continue;
                    coverage = new Coverage(range);
                    coverages.add(coverage);
                }
                else
                    coverage.range.setLength(end - start);
                matched.add(coverage);
                if (query.equals(coverage.query))
                    continue;
                remove.addAll(coverage.ours);
                coverage.ours.clear();
                coverage.query = query;
                for (Issue issue : issues)
                {
                    String type = annotationType(issue.getSeverity());
                    if (type == null || issue.getOffset() == null || issue.getMessage() == null)
                        continue;
                    int offset = issue.getOffset();
                    int length = issue.getLength() != null ? Math.max(1, issue.getLength()) : 1;
                    int problemStart = lit.docOffset(offset);
                    int problemEnd = Math.max(problemStart + 1, lit.docOffset(offset + length));
                    QueryProblemAnnotation annotation = new QueryProblemAnnotation(type, issue.getMessage());
                    coverage.ours.add(annotation);
                    add.put(annotation, new Position(problemStart, problemEnd - problemStart));
                }
            }
            // Покрытие в видимой области, где литерала-запроса больше нет, снимается.
            boolean hubWasHidden = false;
            for (Coverage coverage : new ArrayList<>(coverages))
            {
                if (matched.contains(coverage))
                    continue;
                if (coverage.range.isDeleted() || coverage.overlaps(visStart, visEnd - visStart))
                    hubWasHidden |= dropCoverage(coverage, remove);
            }
            // Проход проверки модуля после правки мог пройти, пока покрытие ещё стояло.
            if (hubWasHidden)
                requestRevalidation();
            if (!remove.isEmpty() || !add.isEmpty())
            {
                if (model instanceof IAnnotationModelExtension extension)
                    extension.replaceAnnotations(remove.toArray(new Annotation[0]), add);
                else
                {
                    remove.forEach(model::removeAnnotation);
                    add.forEach(model::addAnnotation);
                }
            }
            hideHubUnderCoverage(model, allAnnotations(model));
        }

        /**
         * Снять покрытие: свои отметки — в {@code remove}. Убранные отметки хаба не
         * возвращаются: это отметки проверки модуля, каждый её проход пересоздаёт их, и
         * сохранённые к этому моменту протухли. Актуальные даёт перепроверка модуля.
         *
         * @return под покрытием были убраны отметки хаба — нужна перепроверка
         */
        private boolean dropCoverage(Coverage coverage, List<Annotation> remove)
        {
            coverages.remove(coverage);
            remove.addAll(coverage.ours);
            coverage.ours.clear();
            untrack(coverage.range);
            return coverage.hubHidden > 0;
        }

        /**
         * Перепроверка модуля штатной проверкой редактора: хаб заново поставит актуальные
         * отметки там, где живых больше нет.
         */
        private void requestRevalidation()
        {
            if (document instanceof org.eclipse.xtext.ui.editor.model.XtextDocument xtext)
                xtext.checkAndUpdateAnnotations();
        }

        /** Модель аннотаций документа (маркеры ресурса); отметки хаба живут в ней. */
        private IAnnotationModel documentAnnotationModel()
        {
            try
            {
                return editor.getDocumentProvider() != null
                    ? editor.getDocumentProvider().getAnnotationModel(editor.getEditorInput()) : null;
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }

        /**
         * Живые отметки побеждают только там, где они есть: отметка хаба {@code bsl-ql-hub},
         * задевающая литерал под покрытием, убирается из модели аннотаций редактора. При снятии
         * покрытия актуальные отметки вернёт перепроверка модуля. Маркер ресурса и панель
         * «Проблемы» не трогаются. Флаг {@code markDeleted} не годится: подчёркивание его не учитывает.
         * Только UI.
         */
        private void hideHubUnderCoverage(IAnnotationModel visual, List<Annotation> candidates)
        {
            if (coverages.isEmpty())
                return;
            IAnnotationModel owner = documentAnnotationModel();
            for (Annotation annotation : candidates)
            {
                if (annotation instanceof QueryProblemAnnotation || !isHubAnnotation(annotation))
                    continue;
                Position position = visual.getPosition(annotation);
                if (position == null || position.isDeleted())
                    continue;
                Coverage coverage = null;
                for (Coverage candidate : coverages)
                {
                    if (candidate.overlaps(position.getOffset(), position.getLength()))
                        coverage = candidate;
                }
                if (coverage == null)
                    continue;
                boolean inOwner = owner != null && owner.getPosition(annotation) != null;
                (inOwner ? owner : visual).removeAnnotation(annotation);
                if (visual.getPosition(annotation) == null)
                    coverage.hubHidden++;
            }
        }

        private final class AnnotationListener implements IAnnotationModelListener, IAnnotationModelListenerExtension
        {
            @Override
            public void modelChanged(IAnnotationModel model)
            {
                // Подробности — в modelChanged(AnnotationModelEvent).
            }

            /**
             * Отметку хаба под покрытием убираем сразу при её появлении: автопроверка
             * пересоздаёт отметки многократно, и ждать следующего прохода значило бы мигание
             * двух разных отметок одной проблемы.
             */
            @Override
            public void modelChanged(AnnotationModelEvent event)
            {
                List<Annotation> candidates = new ArrayList<>();
                for (Annotation annotation : event.getAddedAnnotations())
                {
                    if (!(annotation instanceof QueryProblemAnnotation) && isHubAnnotation(annotation))
                        candidates.add(annotation);
                }
                for (Annotation annotation : event.getChangedAnnotations())
                {
                    if (!(annotation instanceof QueryProblemAnnotation) && isHubAnnotation(annotation))
                        candidates.add(annotation);
                }
                if (candidates.isEmpty())
                    return;
                // Модель меняем не внутри рассылки её же события.
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                    display.asyncExec(() ->
                    {
                        IAnnotationModel visual = viewer.getAnnotationModel();
                        if (visual != null && MARKER_SESSIONS.get(editor) == MarkerSession.this)
                            hideHubUnderCoverage(visual, candidates);
                    });
            }
        }
    }

    private static List<Annotation> allAnnotations(IAnnotationModel model)
    {
        List<Annotation> result = new ArrayList<>();
        for (Iterator<?> it = model.getAnnotationIterator(); it.hasNext();)
        {
            if (it.next() instanceof Annotation annotation)
                result.add(annotation);
        }
        return result;
    }

    private static String annotationType(Severity severity)
    {
        if (severity == null)
            return null;
        return switch (severity)
        {
            case ERROR -> ERROR_TYPE;
            case WARNING -> WARNING_TYPE;
            case INFO -> INFO_TYPE;
            default -> null;
        };
    }

    private static boolean isHubAnnotation(Annotation annotation)
    {
        String text = annotation.getText();
        if (text == null)
            return false;
        for (String prefix : HUB_PREFIXES)
        {
            if (text.startsWith(prefix))
                return true;
        }
        return false;
    }

    /** Литералы с текстом запроса, задевающие строки {@code top..bottom} (строки документа). */
    private static List<Literal> visibleQueryLiterals(IDocument doc, int top, int bottom)
    {
        List<Literal> result = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        try
        {
            int last = Math.min(bottom, doc.getNumberOfLines() - 1);
            for (int line = top; line <= last && result.size() < MARKERS_MAX_LITERALS; line++)
            {
                IRegion info = doc.getLineInformation(line);
                String text = doc.get(info.getOffset(), info.getLength());
                String stripped = text.stripLeading();
                if (stripped.startsWith("|")) //$NON-NLS-1$
                {
                    int bar = text.length() - stripped.length();
                    collectLiteral(doc, info.getOffset() + bar + 1, seen, result);
                    // Литерал может закрыться на этой строке, а за ним начаться следующий.
                }
                boolean inString = stripped.startsWith("|"); //$NON-NLS-1$
                int from = inString ? text.length() - stripped.length() + 1 : 0;
                for (int i = from; i < text.length(); i++)
                {
                    char c = text.charAt(i);
                    if (!inString && c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/')
                        break;
                    if (c != '"')
                        continue;
                    if (!inString)
                    {
                        inString = true;
                        collectLiteral(doc, info.getOffset() + i + 1, seen, result);
                    }
                    else if (i + 1 < text.length() && text.charAt(i + 1) == '"')
                        i++;
                    else
                        inString = false;
                }
            }
        }
        catch (org.eclipse.jface.text.BadLocationException e)
        {
            // Документ изменился во время обхода — следующий проход повторит.
        }
        return result;
    }

    private static void collectLiteral(IDocument doc, int offset, java.util.Set<Integer> seen, List<Literal> result)
    {
        Literal lit = parse(doc, offset, true);
        if (lit != null && seen.add(lit.openQuote) && lit.isQuery())
            result.add(lit);
    }

    /** Проверка текста запроса штатным валидатором языка запросов; {@code null} — сбой. Только UI. */
    private static List<Issue> validate(IProject project, String query)
    {
        try
        {
            QlEditor editor = hiddenEditor(project);
            EmbeddedEditor embedded = editor.getEmbeddedQlEditor();
            if (!query.equals(embedded.getDocument().get()))
                editor.setFullText(query);
            List<Issue> issues = embedded.getDocument().readOnly(resource ->
            {
                if (resource == null)
                    return List.<Issue>of();
                IResourceValidator validator = resource.getResourceServiceProvider().getResourceValidator();
                return validator.validate(resource, CheckMode.NORMAL_AND_FAST, CancelIndicator.NullImpl);
            });
            return issues != null ? new ArrayList<>(issues) : List.of();
        }
        catch (Exception | LinkageError e)
        {
            return null;
        }
    }
}
