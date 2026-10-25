package tormozit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.bsl.ui.BslSharedImages;

/**
 * Строка списка автодополнения без ИР для слова, которого нет у EDT: слово-генератор ИР
 * ({@code <ЗаполнитьСвойства>} и т.п.), конструкция, копия соседней строки или выражение под ожидаемый
 * тип ({@code ДобавитьВТаблицуСлов} / {@code УстановитьРейтингСлову} в {@code ПрименитьОжидаемыйТипВТаблицеСлов}).
 * Текст вставки готовит {@link BslExpectedTypePremiums} в фоне; здесь — только вставка
 * ({@code ВставитьУказанныйШаблон}, {@code ВставитьОкружающуюКонструкцию} ИР): {@code <?>} — каретка
 * (последний маркер), строки шаблона после первой — с отступом оставшегося начала строки.
 *
 * <p>Не оборачивается в {@link SmartCompletionProposal}: подпись и вставка свои.
 */
final class BslAddedWordProposal implements ICompletionProposal, ICompletionProposalExtension2,
    ICompletionProposalExtension3, ICompletionProposalExtension5, ICompletionProposalExtension6
{
    /** Вид слова ({@code ТипСлова} ИР) — значок и запись в статистику выбора. */
    enum Kind
    {
        CONSTRUCTION, PROPERTY, METHOD
    }

    /** Что поглощает вставка слева от каретки ({@code ПоглощаемыйПредТекст}). */
    enum Absorb
    {
        /** Набранное начало слова ({@code мНачалоСлова}). */
        WORD,
        /** Родитель с точкой и начало слова ({@code мНачалоКонтекста}). */
        CONTEXT,
        /** Всё начало строки ({@code мТекущаяСтрокаНачало}). */
        LINE,
        /** Начало строки без отступа ({@code СокрЛ(мТекущаяСтрокаНачало)}). */
        LINE_CONTENT,
        /** С начала строки {@link BslAddedWordProposal#absorbOffset} (несколько строк инструкции). */
        FROM_OFFSET
    }

    private static final String CARET = "<?>"; //$NON-NLS-1$
    private static final String CENTER = "<%!%>"; //$NON-NLS-1$

    private final String word;
    private final Kind kind;
    private final String template;
    private final Absorb absorb;
    /** Начало контекста ({@link Absorb#CONTEXT}) или строки ({@link Absorb#FROM_OFFSET}) в документе снимка. */
    private final int absorbOffset;
    /** Текст документа от {@link #absorbOffset} до начала слова в снимке — проверка, что он не сдвинулся. */
    private final String absorbText;
    /**
     * Закрывающая строка {@code ВставитьОкружающуюКонструкцию} ({@code КонецЕсли;}) — дописывается, если следующая
     * строка не глубже или есть выделение; пусто — шаблон уже целиком; {@code null} — простая вставка.
     */
    private final String closing;
    /** {@code ВидПовторногоВызоваАвтодополнения}: открыть список после вставки. */
    private final boolean reopen;
    /** {@code Определение = "Статистический"} и {@code <СоздатьПеременную>}: в статистику выбора не идёт. */
    private final boolean statistical;
    /** Слово метаданных проекта: статистика выбора — проектная. */
    private final boolean projectWord;
    private Point selection;
    private int extractOffset = -1;
    private String extractText;

    /** Команда EDT вместо вставки: исходное выделение восстанавливается после закрытия списка. */
    static BslAddedWordProposal extractLocalVariable(int offset, String selected)
    {
        BslAddedWordProposal proposal = new BslAddedWordProposal("<СоздатьПеременную>", Kind.CONSTRUCTION, //$NON-NLS-1$
            "Извлечь локальную переменную…\nОткроется штатный диалог EDT с подобранным именем.", //$NON-NLS-1$
            Absorb.WORD, -1, null, null, false, true, false);
        proposal.extractOffset = offset;
        proposal.extractText = selected;
        return proposal;
    }

    BslAddedWordProposal(String word, Kind kind, String template, Absorb absorb, int absorbOffset, String absorbText,
        String closing, boolean reopen, boolean statistical, boolean projectWord)
    {
        this.word = word;
        this.kind = kind;
        this.template = template != null ? template : word;
        this.absorb = absorb;
        this.absorbOffset = absorbOffset;
        this.absorbText = absorbText != null ? absorbText : ""; //$NON-NLS-1$
        this.closing = closing;
        this.reopen = reopen;
        this.statistical = statistical;
        this.projectWord = projectWord;
    }

    String getWord()
    {
        return word;
    }

    boolean isMethod()
    {
        return kind == Kind.METHOD;
    }

    boolean isStatistical()
    {
        return statistical;
    }

    boolean isProjectWord()
    {
        return projectWord;
    }

    // ---- Вставка ---------------------------------------------------------------------------------

    private void doApply(ITextViewer viewer, IDocument document, int offset)
    {
        if (document == null)
            return;
        if (extractOffset >= 0)
        {
            selection = new Point(extractOffset, extractText.length());
            BslLocalVariableExtractorWizardHook.extract(viewer, document, extractOffset, extractText);
            return;
        }
        int caret = offset >= 0 ? Math.min(offset, document.getLength()) : document.getLength();
        Boolean previousApply = SmartCompletionProposal.PROPOSAL_APPLY_IN_PROGRESS.get();
        SmartCompletionProposal.PROPOSAL_APPLY_IN_PROGRESS.set(Boolean.TRUE);
        Global.tempLog("assist-literal-quote", "added.apply caret=" + caret + " word=" + word); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try
        {
            if (closing != null)
                applySurround(viewer, document, caret);
            else
                applyTemplate(viewer, document, caret);
        }
        catch (BadLocationException e)
        {
            selection = new Point(caret, 0);
            Global.tempLog("expected-type", "added word apply error " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            if (previousApply == null)
                SmartCompletionProposal.PROPOSAL_APPLY_IN_PROGRESS.remove();
            else
                SmartCompletionProposal.PROPOSAL_APPLY_IN_PROGRESS.set(previousApply);
        }
        if (reopen)
        {
            Display display = Display.getCurrent();
            if (display != null)
                display.asyncExec(() -> ContentAssistPopupSync.showPossibleCompletions(
                    ContentAssistSessionReloader.getActiveAssistant()));
        }
    }

    /** {@code ВставитьУказанныйШаблон(Шаблон, ПоглощаемыйПредТекст)}; выделение за кареткой заменяется. */
    private void applyTemplate(ITextViewer viewer, IDocument document, int caret) throws BadLocationException
    {
        int lineOffset = document.getLineInformationOfOffset(caret).getOffset();
        int from = absorbStart(document, caret, lineOffset);
        int to = wordEnd(document, caret);
        Point range = viewer != null ? viewer.getSelectedRange() : null;
        if (range != null && range.y > 0 && range.x <= caret && range.x + range.y > to)
            to = range.x + range.y;
        String keptLineStart = from > lineOffset ? document.get(lineOffset, from - lineOffset) : ""; //$NON-NLS-1$
        insert(document, from, to, template, blankAnalog(keptLineStart));
    }

    /** {@code ВставитьОкружающуюКонструкцию}: конструкция вокруг выделения или с пустым телом. */
    private void applySurround(ITextViewer viewer, IDocument document, int caret) throws BadLocationException
    {
        Point range = viewer != null ? viewer.getSelectedRange() : null;
        boolean selected = range != null && range.y > 0;
        int start = selected ? range.x : caret;
        IRegion line = document.getLineInformationOfOffset(start);
        int from = line.getOffset();
        int to = selected ? range.x + range.y : wordEnd(document, caret);
        String selectedText = selected ? document.get(from, to - from) : ""; //$NON-NLS-1$
        String indent = leadingWhitespace(selected ? selectedText
            : document.get(line.getOffset(), line.getLength()));
        int lineNumber = document.getLineOfOffset(start);
        boolean nextSameLevel = true;
        if (lineNumber + 1 < document.getNumberOfLines())
        {
            IRegion next = document.getLineInformation(lineNumber + 1);
            nextSameLevel = leadingWhitespace(document.get(next.getOffset(), next.getLength())).length()
                <= indent.length();
        }
        String result = template;
        // Пустая закрывающая строка — шаблон уже целиком, с телом <%!%>
        if (!closing.isEmpty() && (nextSameLevel || selected))
            result = result + "\n" + CENTER + "\n" + closing; //$NON-NLS-1$ //$NON-NLS-2$
        if (selected)
        {
            // Внешний отступ ниже добавляется ко всему шаблону. Убираем его с каждой
            // строки выделения и сдвигаем тело на один уровень, сохраняя вложенность.
            String normalized = stripEnd(selectedText.replace("\r\n", "\n").replace('\r', '\n')); //$NON-NLS-1$ //$NON-NLS-2$
            String[] bodyLines = normalized.split("\n", -1); //$NON-NLS-1$
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < bodyLines.length; i++)
            {
                if (i > 0)
                    body.append('\n');
                String bodyLine = bodyLines[i];
                if (bodyLine.startsWith(indent))
                    bodyLine = bodyLine.substring(indent.length());
                body.append('\t').append(bodyLine);
            }
            Global.tempLog("added-word-surround", "word=" + word + " lines=" + bodyLines.length //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " baseIndent=" + indent.length() + " addedIndent=TAB"); //$NON-NLS-1$ //$NON-NLS-2$
            result = result.replace(CENTER, body.toString());
        }
        else
            result = result.replace(CENTER, word.contains(CARET) ? "\t" : CARET); //$NON-NLS-1$
        StringBuilder indented = new StringBuilder(indent);
        indented.append(result.replace("\n", "\n" + indent)); //$NON-NLS-1$ //$NON-NLS-2$
        if (selected)
            indented.append('\n');
        insert(document, from, to, indented.toString(), ""); //$NON-NLS-1$
    }

    /** Вставка текста шаблона с разбором маркера каретки; строки после первой — с префиксом {@code newLinePrefix}. */
    private void insert(IDocument document, int from, int to, String text, String newLinePrefix)
        throws BadLocationException
    {
        // Выделение содержит переносы документа (CRLF), а шаблон — LF. Нормализуем до
        // разбора маркера: иначе оставшийся CR при сборке с CRLF даёт CRCRLF.
        int sourceLength = text.length();
        text = text.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        StringBuilder plain = new StringBuilder(text.length());
        int caretAt = -1;
        int index = 0;
        while (index < text.length())
        {
            if (text.startsWith(CARET, index))
            {
                caretAt = plain.length();
                index += CARET.length();
                continue;
            }
            plain.append(text.charAt(index++));
        }
        String delimiter = TextUtilities.getDefaultLineDelimiter(document);
        String[] lines = plain.toString().split("\n", -1); //$NON-NLS-1$
        StringBuilder result = new StringBuilder();
        int caretOut = -1;
        int consumed = 0;
        for (int i = 0; i < lines.length; i++)
        {
            String prefix = ""; //$NON-NLS-1$
            if (i > 0)
            {
                result.append(delimiter);
                // Пустая последняя строка — без отступа, как в ИР
                prefix = i == lines.length - 1 && lines[i].isEmpty() ? "" : newLinePrefix; //$NON-NLS-1$
                result.append(prefix);
            }
            if (caretAt >= consumed && caretAt <= consumed + lines[i].length() && caretOut < 0)
                caretOut = result.length() + caretAt - consumed;
            result.append(lines[i]);
            consumed += lines[i].length() + 1;
        }
        if (caretOut < 0)
            caretOut = result.length();
        // Пробел после «=» входит в ту же замену: запасной post-notification replace
        // иначе сдвигает текст, когда selection уже рассчитан без этого символа.
        boolean paddedAfterEquals = SmartCompletionProposal.needsSpaceAfterEquals(document, from, result.toString());
        if (paddedAfterEquals)
        {
            result.insert(0, ' ');
            caretOut++;
        }
        Global.tempLog("added-word-insert", "word=" + word + " from=" + from + " to=" + to //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + " sourceLength=" + sourceLength + " normalizedLength=" + text.length() //$NON-NLS-1$ //$NON-NLS-2$
            + " lines=" + lines.length + " resultLength=" + result.length() + " caret=" + caretOut //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " paddedAfterEquals=" + paddedAfterEquals); //$NON-NLS-1$
        document.replace(from, to - from, result.toString());
        selection = new Point(from + caretOut, 0);
    }

    private int absorbStart(IDocument document, int caret, int lineOffset) throws BadLocationException
    {
        int wordStart = SmartContentAssistProcessor.computeIdentifierWordStart(document, caret);
        switch (absorb)
        {
            case CONTEXT:
            case FROM_OFFSET:
                // Текст перед словом с тех пор не менялся — поглощаем от запомненного места
                if (absorbOffset >= 0 && absorbOffset <= wordStart
                    && document.get(absorbOffset, wordStart - absorbOffset).equals(absorbText))
                    return absorbOffset;
                return absorb == Absorb.CONTEXT ? wordStart : lineOffset;
            case LINE:
                return lineOffset;
            case LINE_CONTENT:
            {
                int pos = lineOffset;
                while (pos < caret && Character.isWhitespace(document.getChar(pos)))
                    pos++;
                return pos;
            }
            default:
                return wordStart;
        }
    }

    /**
     * Конец заменяемого: остаток идентификатора за кареткой, если слово с него продолжается
     * ({@code ЧтоИщем = мНачалоСлова + мКонецКонтекста}); иначе каретка.
     */
    private int wordEnd(IDocument document, int caret) throws BadLocationException
    {
        int start = SmartContentAssistProcessor.computeIdentifierWordStart(document, caret);
        int end = caret;
        while (end < document.getLength() && SmartContentAssistProcessor.isFilterChar(document.getChar(end)))
            end++;
        String whole = document.get(start, end - start);
        return end > caret && word.regionMatches(true, 0, whole, 0, whole.length()) ? end : caret;
    }

    /** {@code ПустойАналогТекста}: табуляции остаются, прочие символы — пробелы. */
    private static String blankAnalog(String text)
    {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
            result.append(text.charAt(i) == '\t' ? '\t' : ' ');
        return result.toString();
    }

    private static String leadingWhitespace(String text)
    {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t'))
            i++;
        return text.substring(0, i);
    }

    private static String stripEnd(String text)
    {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1)))
            end--;
        return text.substring(0, end);
    }

    @Override
    public void apply(IDocument document)
    {
        doApply(null, document, -1);
    }

    @Override
    public void apply(ITextViewer viewer, char trigger, int stateMask, int offset)
    {
        doApply(viewer, viewer != null ? viewer.getDocument() : null, offset);
    }

    @Override
    public Point getSelection(IDocument document)
    {
        return selection;
    }

    // ---- Список ----------------------------------------------------------------------------------

    @Override
    public boolean validate(IDocument document, int offset, DocumentEvent event)
    {
        return SmartContentAssistProcessor.proposalMatchesFilter(this, document, offset, event);
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
    public String getDisplayString()
    {
        return word;
    }

    @Override
    public StyledString getStyledDisplayString()
    {
        StyledString styled = new StyledString(word);
        SmartCodeMatcher matcher = new SmartCodeMatcher(SmartFilterTracker.getCurrentFilter());
        SmartMatchHighlight.applyRanges(styled, matcher.getHighlightRanges(word));
        return styled;
    }

    @Override
    public Image getImage()
    {
        return BslSharedImages.getImage(kind == Kind.METHOD ? BslSharedImages.IMG_EXTERNAL_FUNC
            : kind == Kind.PROPERTY ? BslSharedImages.IMG_EXTERNAL_PROPERTY : BslSharedImages.IMG_STATEMENT_BLOCK);
    }

    /** Боковая подсказка — текст, который будет вставлен (код, без переноса строк). */
    @Override
    public String getAdditionalProposalInfo()
    {
        String text = closing != null && !closing.isEmpty() ? template + "\n" + CENTER + "\n" + closing : template; //$NON-NLS-1$ //$NON-NLS-2$
        return text.equals(word) ? null : text.replace(CARET, "|").replace(CENTER, "…"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public Object getAdditionalProposalInfo(IProgressMonitor monitor)
    {
        return getAdditionalProposalInfo();
    }

    @Override
    public IInformationControlCreator getInformationControlCreator()
    {
        return null;
    }

    @Override
    public CharSequence getPrefixCompletionText(IDocument document, int completionOffset)
    {
        return null;
    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset)
    {
        return completionOffset;
    }

    @Override
    public IContextInformation getContextInformation()
    {
        return null;
    }
}
