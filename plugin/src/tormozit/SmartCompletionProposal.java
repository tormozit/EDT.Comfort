package tormozit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension4;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.jface.text.source.SourceViewer;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Обёртка proposal: кастомны только {@link #validate} и {@link #getStyledDisplayString()};
 * {@link #apply} — pass-through к delegate; если каретка перед {@code (}, вставка без {@code ()}.
 */
public class SmartCompletionProposal implements
    ICompletionProposal,
    ICompletionProposalExtension,
    ICompletionProposalExtension2,
    ICompletionProposalExtension3,
    ICompletionProposalExtension4,
    ICompletionProposalExtension5,
    ICompletionProposalExtension6
{
    private final ICompletionProposal delegate;
    /** Индекс в штатном списке delegate; {@code -1} — неизвестен. */
    private final int delegateOrder;
    /** Как {@code ConfigurableCompletionProposal.apply(ITextViewer,…)} — флаг COMPLETE. */
    private static final int COMPLETE_STATE_MASK = 262144;

    /** Флаг: мы внутри {@link #apply} — document-listener не должен запускать авто-открытие. */
    static final ThreadLocal<Boolean> PROPOSAL_APPLY_IN_PROGRESS = new ThreadLocal<>();

    public SmartCompletionProposal(ICompletionProposal delegate)
    {
        this(delegate, -1);
    }

    public SmartCompletionProposal(ICompletionProposal delegate, int delegateOrder)
    {
        this.delegate = delegate;
        this.delegateOrder = delegateOrder;
    }

    public ICompletionProposal getDelegate()
    {
        return delegate;
    }

    public int getDelegateOrder()
    {
        return delegateOrder;
    }

    // ---- ICompletionProposal ------------------------------------------------

    @Override
    public StyledString getStyledDisplayString()
    {
        String overlapDisplay = resolveIrOverlapDisplayOverride();
        StyledString result;
        if (overlapDisplay != null)
            result = buildIrStyledDisplayString(overlapDisplay);
        else if (delegate instanceof IrCompletionProposal ir)
            result = buildIrStyledDisplayString(ir.getDisplayString());
        else if (delegate instanceof ICompletionProposalExtension6 ext6)
            result = ext6.getStyledDisplayString();
        else
        {
            String display = delegate.getDisplayString();
            result = new StyledString(display != null ? display : ""); //$NON-NLS-1$
        }
        SmartCodeMatcher matcher = resolveHighlightMatcher();
        if (!matcher.isEmpty)
        {
            String display = overlapDisplay != null ? overlapDisplay : delegate.getDisplayString();
            String nameOnly = SmartContentAssistProcessor.parseProposalListName(
                display != null ? display : ""); //$NON-NLS-1$
            if (!nameOnly.isEmpty())
                SmartMatchHighlight.applyRanges(result, matcher.getHighlightRanges(nameOnly));
        }
        return result;
    }

    /** Владелец после {@code ~} — как у штатного assist EDT ({@link StyledString#QUALIFIER_STYLER}). */
    private static StyledString buildIrStyledDisplayString(String display)
    {
        if (display == null || display.isEmpty())
            return new StyledString(""); //$NON-NLS-1$
        int sep = display.indexOf(" ~ "); //$NON-NLS-1$
        if (sep < 0)
            return new StyledString(display);
        StyledString styled = new StyledString(display.substring(0, sep));
        styled.append(display.substring(sep), StyledString.QUALIFIER_STYLER);
        return styled;
    }

    @Override
    public String getDisplayString()
    {
        String overlapDisplay = resolveIrOverlapDisplayOverride();
        if (overlapDisplay != null)
            return overlapDisplay;
        return delegate.getDisplayString();
    }

    /**
     * Подпись overlap EDT+ИР из кэша процессора — не сбрасывается при пересборке popup.
     */
    private String resolveIrOverlapDisplayOverride()
    {
        if (delegate instanceof IrCompletionProposal)
            return null;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return null;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null)
            return null;
        String key = SmartContentAssistProcessor.dedupKeyForMerge(delegate);
        if (key.isEmpty())
            return null;
        return processor.resolveIrOverlapDisplay(delegate.getDisplayString(), key);
    }

    @Override
    public Image getImage()
    {
        return delegate.getImage();
    }

    @Override
    public Point getSelection(IDocument document)
    {
        return delegate.getSelection(document);
    }

    @Override
    public IContextInformation getContextInformation()
    {
        return delegate.getContextInformation();
    }

    @Override
    public void apply(IDocument document)
    {
        PROPOSAL_APPLY_IN_PROGRESS.set(Boolean.TRUE);
        try
        {
            if (tryApplyWordOnly(document, null, -1, null))
                return;
            delegate.apply(document);
        }
        finally
        {
            PROPOSAL_APPLY_IN_PROGRESS.remove();
        }
    }

    // ---- ICompletionProposalExtension ---------------------------------------

    @Override
    public void apply(IDocument document, char trigger, int offset)
    {
        PROPOSAL_APPLY_IN_PROGRESS.set(Boolean.TRUE);
        try
        {
            if (tryApplyWordOnly(document, null, offset, null))
                return;
            if (delegate instanceof ICompletionProposalExtension)
                ((ICompletionProposalExtension) delegate).apply(document, trigger, offset);
            else
                delegate.apply(document);
        }
        finally
        {
            PROPOSAL_APPLY_IN_PROGRESS.remove();
        }
    }

    @Override
    public boolean isValidFor(IDocument document, int offset)
    {
        return matchesFilter(document, offset, null);
    }

    @Override
    public char[] getTriggerCharacters()
    {
        if (delegate instanceof ICompletionProposalExtension)
            return ((ICompletionProposalExtension) delegate).getTriggerCharacters();
        return null;
    }

    @Override
    public int getContextInformationPosition()
    {
        if (delegate instanceof ICompletionProposalExtension)
            return ((ICompletionProposalExtension) delegate).getContextInformationPosition();
        return -1;
    }

    // ---- ICompletionProposalExtension2 --------------------------------------

    @Override
    public void apply(ITextViewer viewer, char trigger, int stateMask, int offset)
    {
        PROPOSAL_APPLY_IN_PROGRESS.set(Boolean.TRUE);
        try
        {
            if (viewer != null && tryApplyWordOnly(viewer.getDocument(), viewer, offset, stateMask))
                return;
            if (true
                && delegate instanceof IrCompletionProposal ir
                && tryApplyWithIrAdapter(ir, viewer, offset))
                return;
            if (delegate instanceof ICompletionProposalExtension2)
                ((ICompletionProposalExtension2) delegate).apply(viewer, trigger, stateMask, offset);
            else if (viewer != null && viewer.getDocument() != null)
                apply(viewer.getDocument(), trigger, offset);
        }
        finally
        {
            PROPOSAL_APPLY_IN_PROGRESS.remove();
        }
    }

    /** Активация строки assist (Eclipse {@code selected}), не подтверждение {@code apply}. */
    @Override
    public void selected(ITextViewer viewer, boolean smartToggle)
    {
        logProposalAtSelection(viewer);
        if (delegate instanceof IrCompletionProposal ir)
            scheduleIrWordActivation(ir);
        else
        {
            ensureLiteralOverlapBrowserIfNeeded(viewer);
            scheduleEdtRowActivation();
        }
        if (delegate instanceof ICompletionProposalExtension2 ext2)
            ext2.selected(viewer, smartToggle);
    }

    @Override
    public void unselected(ITextViewer viewer)
    {
        if (delegate instanceof ICompletionProposalExtension2)
            ((ICompletionProposalExtension2) delegate).unselected(viewer);
    }

    @Override
    public boolean validate(IDocument document, int offset, DocumentEvent event)
    {
        return matchesFilter(document, offset, event);
    }

    // ---- ICompletionProposalExtension4 --------------------------------------

    /**
     * Порт RDT {@code ПриВыбореЗначенияТ9}: перехват вставки ИР-предложения через
     * {@code Адаптер_ПриВыбореСтрокиАвтодополнения}.
     *
     * @return {@code true} если вставка выполнена/подавлена адаптером
     */
    private boolean tryApplyWithIrAdapter(IrCompletionProposal ir, ITextViewer viewer, int offset)
    {
        BslXtextEditor activeBslEditor = GetRef.getActiveBslEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActivePart());
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(activeBslEditor); 
        if (session == null)
            return false;

        IDocument document = viewer != null ? viewer.getDocument() : null;
        if (document == null)
            return false;

        long started = System.currentTimeMillis();
        IRSession.CompletionAdapterResult result;
        try
        {
            final String tmpl = ir.getTemplateText();
            result = session.executeOnComThread(() ->
                session.invokeCompletionAdapter(
                    ir.getWordValue(), ir.isMethod(), ir.getDictionaryKey(), tmpl));
        }
        catch (Exception e)
        {
            IrCompletionDebug.problem("адаптер apply: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
        IrCompletionDebug.timing("Адаптер_ПриВыбореСтрокиАвтодополнения", started); //$NON-NLS-1$

        if (result == null)
            return false;

        // Отступ строки вставки — вычисляем ДО любых изменений документа
        String lineIndent = result.formatText ? computeLineIndent(document, offset) : ""; //$NON-NLS-1$

        // Вычисляем диапазон удаления если генератор с поглощением
        int deleteFrom = -1;
        int deleteTo   = -1;
        if (result.newTemplate != null || result.isGeneratorWithLineStart) {
           int[] range = resolveDeleteRange(result, document, session, offset, viewer);
            deleteFrom = range[0];
            deleteTo   = range[1];
        }
        // Удаляем диапазон перед вставкой
        boolean didDeleteRange = false;
        int insertOffset = offset;
        if (deleteFrom >= 0 && deleteTo > deleteFrom)
        {
            try
            {
                document.replace(deleteFrom, deleteTo - deleteFrom, ""); //$NON-NLS-1$
                insertOffset = deleteFrom;
                didDeleteRange = true;
            }
            catch (BadLocationException e)
            {
                IrCompletionDebug.problem("адаптер deleteRange: " + e.getMessage()); //$NON-NLS-1$
            }
        }

        // НовыйШаблон <> Неопределено
        if (result.newTemplate != null)
        {
            if (result.newTemplate.isEmpty())
            {
                // Отказ — генератор вернул пустой текст
                Display.getDefault().asyncExec(() ->
                    ToastNotification.show(
                        IRApplication.toastTitle(),
                        "Генератор вернул пустой текст для вставки", 3_000)); //$NON-NLS-1$
                return true;
            }
            // Адаптер вернул шаблон — вставляем его
            applyIrInsertion(ir, document, result.newTemplate, insertOffset, didDeleteRange, result.formatText, lineIndent, result.isGeneratorWithLineStart, activeBslEditor);
            return true;
        }

        // НовыйШаблон = Неопределено, без поглощения — стандартная обработка
        if (!didDeleteRange)
            return false;

        // НовыйШаблон = Неопределено, диапазон удалён — вставляем стандартный план
        applyIrInsertion(ir, document, null, insertOffset, true, result.formatText, lineIndent, result.isGeneratorWithLineStart, activeBslEditor);
        return true;
    }
    
    /**
     * Вычисляет диапазон [from, to) для удаления перед вставкой генератора.
     * <p>Порт блока {@code ЛиГенераторСПоглощениемНачалаСтроки} из {@code ПриВыбореЗначенияТΟ}.
     *
     * @return массив [deleteFrom, deleteTo]; оба -1 если не вычислено
     */
    private static int[] resolveDeleteRange(IRSession.CompletionAdapterResult result,
        IDocument document, IRSession session, int completionOffset, ITextViewer viewer)
    {
        // Приоритет — мЗаменяемыйДиапазон, уже прочитаный на COM-потоке
        if (result.deleteFromLf >= 0 && result.deleteToLf > result.deleteFromLf)
        {
            String raw = session.lastSyncedRawText;
            int from = Global.remapOffsetFromLf(raw, result.deleteFromLf);
            int to   = Global.remapOffsetFromLf(raw, result.deleteToLf);
            return new int[] { from, to };
        }
        // Порт: УдалитьТекстС = Позиция - СтрДлина(СокрЛ(СтрокаСлева))
        //        УдалитьТекстПо = Позиция + СтрДлина(ВыделенныйТекст)
        try
        {
            int wordStart = SmartContentAssistProcessor.computeIdentifierWordStart(
                document, completionOffset);
            String effectivePrefix = document.get(wordStart, completionOffset - wordStart);
            int deleteFrom = completionOffset - effectivePrefix.length();
            if (result.isGeneratorWithLineStart)
            {
                deleteFrom = document.getLineOffset(
                    document.getLineOfOffset(completionOffset));
            }
            int selLen = viewer instanceof SourceViewer sv
                ? Math.max(0, sv.getSelectedRange().y) : 0;
            int deleteTo = completionOffset + selLen;
            return new int[] { deleteFrom, deleteTo };
        }
        catch (BadLocationException e)
        {
            IrCompletionDebug.problem("адаптер resolveRange fallback: " + e.getMessage()); //$NON-NLS-1$
            return new int[] { -1, -1 };
        }
    }

    /**
     * Вставляет текст в документ по итогам адаптера.
     * <p>Если {@code template != null} — использует его (адаптерный шаблон от ИР);
     * иначе — стандартный план proposal ({@link IrCompletionProposal#buildInsertPlan()}).
     * <p>Если {@code rangeAlreadyDeleted=true}, перед вставкой ничего не удаляется;
     * иначе заменяет идентификаторный префикс перед {@code insertOffset}.
     *
     * @return длина вставленного текста; {@code 0} при ошибке
     */
    private static void applyIrInsertion(IrCompletionProposal ir, IDocument document,
        String template, int insertOffset, boolean rangeAlreadyDeleted, boolean formatText,
        String lineIndent, boolean indentFirstLine, BslXtextEditor editor)
    {
        IrCompletionProposal.InsertPlan plan = template != null
            ? IrCompletionProposal.planFromTemplate(template)
            : ir.buildInsertPlan();
        int replaceLen;
        if (rangeAlreadyDeleted || template == null)
        {
            replaceLen = 0;
        }
        else
        {
            int prefixStart = SmartContentAssistProcessor.computeIdentifierWordStart(
                document, insertOffset);
            replaceLen = Math.max(0, insertOffset - prefixStart);
        }
        // Отступ вычисляем ДО вставки, по полной строке (не обрезая по каретке)
        try
        {
            document.replace(insertOffset, replaceLen, plan.text);
            int insertedLength = plan.text.length();
            if (formatText && !lineIndent.isEmpty() && insertedLength > 0)
            {
                formatInsertedRegion(insertOffset, insertedLength, lineIndent, indentFirstLine, editor);
                if (indentFirstLine)
                {
                    plan.caretOffset += lineIndent.length();
                }
            }
            ir.setPendingCaretAfterApply(insertOffset + plan.caretOffset);
        }
        catch (BadLocationException e)
        {
            IrCompletionDebug.problem("адаптер applyInsertion: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Форматирует вставленный фрагмент штатным форматтером EDT/BSL.
     * <p>Выделяет регион {@code [offset, offset+length)}, вызывает
     * {@code viewer.doOperation(FORMAT)} и откладывает через {@code asyncExec},
     * чтобы не конфликтовать с завершением сессии автодополнения.
     */
    /**
     * Выравнивает вставленный многострочный фрагмент по отступу строки вставки.
     * <p>Берёт лидирующие пробельные символы строки, в которую попал {@code offset},
     * и добавляет их в начало каждой непервой строки вставленного текста.
     * Работает независимо от AST и применимо к любому тексту.
     */
    /**
     * Выравнивает вставленный многострочный фрагмент по заранее вычисленному отступу.
     * Добавляет {@code lineIndent} в начало каждой непервой строки вставки.
     *
     * @param lineIndent лидирующие пробельные символы строки вставки,
     *                   захваченные до выполнения вставки
     */
    private static void formatInsertedRegion(int offset, int length, String lineIndent,
        boolean indentFirstLine, BslXtextEditor activeBslEditor)
    {
        if (activeBslEditor == null || length <= 0 || lineIndent.isEmpty())
            return;
        org.eclipse.jface.text.source.ISourceViewer viewer =
            activeBslEditor.getInternalSourceViewer();
        if (viewer == null)
            return;
        IDocument document = viewer.getDocument();
        if (document == null)
            return;
        try
        {
            String inserted = document.get(offset, length);
            // Непервые строки — всегда добавляем отступ
            String formatted = inserted.replace("\n", "\n" + lineIndent); //$NON-NLS-1$ //$NON-NLS-2$
            // Первая строка — только если генератор начинается с начала строки
            if (indentFirstLine)
                formatted = lineIndent + formatted;
            if (!formatted.equals(inserted))
                document.replace(offset, length, formatted);
        }
        catch (BadLocationException e)
        {
            IrCompletionDebug.problem("formatInsertedRegion: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Возвращает лидирующие пробельные символы полной строки, содержащей {@code offset}.
     * Использует всю строку целиком — не обрезает по позиции каретки.
     */
    private static String computeLineIndent(IDocument document, int offset)
    {
        try
        {
            int line           = document.getLineOfOffset(offset);
            org.eclipse.jface.text.IRegion lineInfo = document.getLineInformation(line);
            return leadingWhitespace(document.get(lineInfo.getOffset(), lineInfo.getLength()));
        }
        catch (BadLocationException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /** Возвращает лидирующие пробельные символы строки (до первого непробельного). */
    private static String leadingWhitespace(String line)
    {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i)))
            i++;
        return line.substring(0, i);
    }

    // ---- ICompletionProposalExtension4 --------------------------------------

    @Override
    public boolean isAutoInsertable()
    {
        if (delegate instanceof ICompletionProposalExtension4)
            return ((ICompletionProposalExtension4) delegate).isAutoInsertable();
        return true;
    }

    // ---- ICompletionProposalExtension3 / 5 / 6 ------------------------------

    @Override
    public String getAdditionalProposalInfo()
    {
        if (delegate instanceof IrCompletionProposal ir)
            return resolveIrAdditionalProposalInfo(ir);
        Object baseInfo;
        if (delegate instanceof ICompletionProposalExtension5)
            baseInfo = ((ICompletionProposalExtension5) delegate)
                .getAdditionalProposalInfo(new NullProgressMonitor());
        else
            baseInfo = delegate.getAdditionalProposalInfo();
        return objectToSideHintString(resolveEdtAssistSideHint(baseInfo));
    }

    private static String objectToSideHintString(Object info)
    {
        if (info instanceof String)
            return (String) info;
        if (info instanceof StyledString)
            return ((StyledString) info).getString();
        return info != null ? info.toString() : null;
    }

    @Override
    public IInformationControlCreator getInformationControlCreator()
    {
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        if (delegate instanceof IrCompletionProposal)
            return BslCompletionSideHintResolver.resolveAssistBrowserCreatorForProposal(
                assistant, delegate);
        if (isEdtIrOverlapRow())
            return BslCompletionSideHintResolver.resolveAssistBrowserCreatorForProposal(
                assistant, delegate);
        if (delegate instanceof ICompletionProposalExtension3 ext3)
            return ext3.getInformationControlCreator();
        return null;
    }

    /** EDT-строка с тем же ключом, что и слово ИР (overlap в литеральном assist). */
    private boolean isEdtIrOverlapRow()
    {
        if (delegate instanceof IrCompletionProposal)
            return false;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return false;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return false;
        if (IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor()) == null)
            return false;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null || !processor.hasIrProposalsForCurrentContext())
            return false;
        String key = SmartContentAssistProcessor.dedupKeyForMerge(delegate);
        if (key.isEmpty())
            return false;
        if (processor.hasIrProposalForDedupKey(key))
            return true;
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        if (cacheKey != null && !cacheKey.isEmpty())
        {
            String merged = reloader.getIrMergedHtml(cacheKey);
            if (merged != null && !merged.isEmpty())
                return true;
        }
        return false;
    }

    /** Боковая подсказка assist: browser для ИР и overlap EDT+ИР. */
    public boolean usesIrAssistBrowserForSideHint()
    {
        return delegate instanceof IrCompletionProposal || isEdtIrOverlapRow();
    }

    /** H41: идентичность proposal при {@code selected()} в literal assist. */
    private void logProposalAtSelection(ITextViewer viewer)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(sourceViewer);
        if (caret < 0 || !SmartContentAssistProcessor.isStringLiteralAssistContext(
            sourceViewer, caret))
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return;
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(this);
        String displayKey = delegate.getDisplayString() != null ? delegate.getDisplayString() : ""; //$NON-NLS-1$
        String delegateClass = raw != null ? raw.getClass().getSimpleName()
            : delegate.getClass().getSimpleName();
        boolean hasIrMergedHtml = hasIrMergedHtmlForSelection(reloader, raw);
        boolean setupPhase = reloader.isLiteralOpenSetupPhase();
        boolean literalFinishPinPending = reloader.isLiteralFinishPinPending();
        int gen = reloader.getLiteralOpenGen();
        long ms = reloader.msSinceLiteralOpen();
        // #region agent log
        ContentAssistDebug.debugModeLog("H41", "proposalAtSelection", "selected", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"gen\":" + gen //$NON-NLS-1$
                + ",\"msSinceOpen\":" + ms //$NON-NLS-1$
                + ",\"displayKey\":\"" + ContentAssistDebug.jsonEscapeForLog(displayKey) //$NON-NLS-1$
                + "\",\"delegateClass\":\"" + ContentAssistDebug.jsonEscapeForLog(delegateClass) //$NON-NLS-1$
                + "\",\"hasIrMergedHtml\":" + hasIrMergedHtml //$NON-NLS-1$
                + ",\"setupPhase\":" + setupPhase //$NON-NLS-1$
                + ",\"literalFinishPinPending\":" + literalFinishPinPending //$NON-NLS-1$
                + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    private static boolean hasIrMergedHtmlForSelection(ContentAssistSessionReloader reloader,
        ICompletionProposal raw)
    {
        if (reloader == null || raw == null)
            return false;
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(raw);
        if (cacheKey != null && !cacheKey.isEmpty())
        {
            String merged = reloader.getIrMergedHtml(cacheKey);
            if (merged != null && !merged.isEmpty())
                return true;
        }
        String display = raw.getDisplayString();
        if (display != null && !display.isEmpty())
        {
            String merged = reloader.getIrMergedHtml(display);
            return merged != null && !merged.isEmpty();
        }
        return false;
    }

    private void ensureLiteralOverlapBrowserIfNeeded(ITextViewer viewer)
    {
        if (!isEdtIrOverlapRow())
            return;
        if (!(viewer instanceof SourceViewer sourceViewer))
            return;
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null)
            return;
        int caret = SmartContentAssistProcessor.resolveWidgetCaret(sourceViewer);
        if (caret < 0 || !SmartContentAssistProcessor.isStringLiteralAssistContext(
            sourceViewer, caret))
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null && reloader.isLiteralOpenSetupPhase())
            return;
        if (!ContentAssistPopupSync.hasAssistBrowserSidePanel(assistant))
            ContentAssistPopupSync.ensureAssistBrowserCreatorOnController(assistant, sourceViewer);
    }

    @Override
    public Object getAdditionalProposalInfo(IProgressMonitor monitor)
    {
        if (delegate instanceof IrCompletionProposal ir)
            return resolveIrAdditionalProposalInfo(ir);
        Object baseInfo = null;
        if (delegate instanceof ICompletionProposalExtension5)
            baseInfo = ((ICompletionProposalExtension5) delegate).getAdditionalProposalInfo(monitor);
        else
            baseInfo = delegate.getAdditionalProposalInfo();
        return resolveEdtAssistSideHint(baseInfo);
    }

    @Override
    public CharSequence getPrefixCompletionText(IDocument document, int completionOffset)
    {
        if (delegate instanceof ICompletionProposalExtension3)
            return ((ICompletionProposalExtension3) delegate)
                .getPrefixCompletionText(document, completionOffset);
        return null;
    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset)
    {
        if (delegate instanceof ICompletionProposalExtension3)
            return ((ICompletionProposalExtension3) delegate)
                .getPrefixCompletionStart(document, completionOffset);
        return completionOffset;
    }

    /**
     * Каретка непосредственно перед {@code (} — вставка без хвоста {@code ()} у replacement.
     *
     * @return {@code true}, если выполнен fallback вместо штатного delegate.apply
     */
    private boolean tryApplyWordOnly(IDocument document, ITextViewer viewer,
        int completionOffset, Integer stateMask)
    {
        ConfigurableCompletionProposal cp = asConfigurable(delegate);
        if (cp == null || document == null)
            return false;
        int caret = completionOffset >= 0
            ? completionOffset
            : resolveApplyCaretOffset(document);
        if (caret < 0 || !needsWordOnlyInsert(cp, document, caret))
            return false;
        int effectiveOffset = completionOffset >= 0 ? completionOffset : caret;
        applyWordOnly(cp, document, viewer, effectiveOffset, stateMask);
        return true;
    }

    private static ConfigurableCompletionProposal asConfigurable(ICompletionProposal proposal)
    {
        ICompletionProposal raw = SmartContentAssistProcessor.unwrapProposal(proposal);
        return raw instanceof ConfigurableCompletionProposal cp ? cp : null;
    }

    private static int resolveApplyCaretOffset(IDocument document)
    {
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        if (viewer == null || viewer.getDocument() != document)
            return -1;
        if (!(viewer.getTextWidget() instanceof StyledText text) || text.isDisposed())
            return -1;
        int caret = text.getCaretOffset();
        return caret >= 0 ? caret : -1;
    }

    private static boolean needsWordOnlyInsert(ConfigurableCompletionProposal cp,
        IDocument document, int caretOffset)
    {
        String repl = cp.getReplacementString();
        if (repl == null || !repl.endsWith("()")) //$NON-NLS-1$
            return false;
        try
        {
            if (caretOffset >= document.getLength())
                return false;
            return document.getChar(caretOffset) == '(';
        }
        catch (BadLocationException e)
        {
            return false;
        }
    }

    private static String stripTrailingEmptyCallParens(String replacement)
    {
        if (replacement != null && replacement.endsWith("()")) //$NON-NLS-1$
            return replacement.substring(0, replacement.length() - 2);
        return replacement;
    }

    private static void applyWordOnly(ConfigurableCompletionProposal cp, IDocument document,
        ITextViewer viewer, int completionOffset, Integer stateMask)
    {
        String savedRepl = cp.getReplacementString();
        int savedLen = cp.getReplacementLength();
        try
        {
            cp.setReplacementString(stripTrailingEmptyCallParens(savedRepl));
            int newLen = completionOffset - cp.getReplacementOffset();
            Point sel = viewer != null ? viewer.getSelectedRange() : null;
            if (sel != null)
                newLen += sel.y;
            cp.setReplacementLength(newLen);
            if (stateMask != null && (stateMask & COMPLETE_STATE_MASK) != 0)
                cp.setReplacementLength(cp.getReplaceContextLength());
            cp.apply(document);
        }
        finally
        {
            cp.setReplacementString(savedRepl);
            cp.setReplacementLength(savedLen);
        }
    }

    private SmartCodeMatcher resolveHighlightMatcher()
    {
        String filter = SmartFilterTracker.getCurrentFilter();
        return new SmartCodeMatcher(filter != null ? filter : ""); //$NON-NLS-1$
    }

    private boolean matchesFilter(IDocument document, int offset, DocumentEvent event)
    {
        return SmartContentAssistProcessor.proposalMatchesFilter(delegate, document, offset, event);
    }

    private String resolveIrAdditionalProposalInfo(IrCompletionProposal ir)
    {
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null && ComfortSettings.isReplaceListFiltersEnabled())
        {
            String cacheKey = ir.getStableCacheKey();
            String merged = reloader.getIrMergedHtml(cacheKey);
            if (merged == null || merged.isEmpty())
                merged = reloader.getIrMergedHtml(ir.getDisplayString());
            if (merged != null && !merged.isEmpty())
            {
                return merged;
            }
            if (reloader.isIrActivationPending(cacheKey))
            {
                return null;
            }
            String html = resolveIrAssistSideHintHtml(reloader, ir);
            if (html != null && !html.isEmpty())
            {
                return html;
            }
            maybeScheduleIrWordActivation(reloader, ir);
        }
        String fallback = ir.getAdditionalProposalInfo();
        return fallback;
    }

    /**
     * Боковая подсказка assist для ИР-строки: только кэш активации
     * ({@code ОписаниеТекущегоСловаАвтодополнения}), без {@code ОписаниеХТМЛВыражения}.
     */
    private static String resolveIrAssistSideHintHtml(
        ContentAssistSessionReloader reloader, IrCompletionProposal ir)
    {
        String cacheKey = ir.getStableCacheKey();
        String cached = reloader.getIrMergedHtml(cacheKey);
        if (cached != null && !cached.isEmpty())
            return cached;
        cached = reloader.getIrMergedHtml(ir.getDisplayString());
        if (cached != null && !cached.isEmpty())
            return cached;
        String onProposal = ir.getAdditionalProposalInfo();
        if (onProposal != null && !onProposal.isEmpty())
            return onProposal;
        IrBslCompletionSupport.ActivationDescription act = reloader.getIrActivation(cacheKey);
        if (act != null)
            return IrBslCompletionSupport.formatActivationHtml(act.description, act.rawHtml);
        return null;
    }

    private void maybeScheduleIrWordActivation(
        ContentAssistSessionReloader reloader, IrCompletionProposal ir)
    {
        String cacheKey = ir.getStableCacheKey();
        if (reloader.getIrActivation(cacheKey) != null)
            return;
        if (reloader.isIrActivationPending(cacheKey))
            return;
        scheduleIrWordActivation(ir);
    }

    /**
     * EDT-строка: штатная боковая подсказка + HTML из кэша активации
     * ({@code ОписаниеТекущегоСловаАвтодополнения}), без {@code ОписаниеХТМЛВыражения}.
     */
    private Object resolveEdtAssistSideHint(Object baseInfo)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return baseInfo;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return baseInfo;
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        if (cacheKey == null || cacheKey.isEmpty())
            return baseInfo;
        String merged = reloader.getIrMergedHtml(cacheKey);
        if (merged != null && !merged.isEmpty())
            return merged;
        String irHtml = resolveActivationHtmlFromCache(reloader, cacheKey);
        if (irHtml != null && !irHtml.isEmpty())
        {
            // Merge в боковую панель только если EDT дал BslBrowserInput; тип в списке
            // уже обновлён через applyEdtListTypeFromIrActivation.
            if (IrBslHoverHtml.isBslBrowserInput(baseInfo))
            {
                merged = IrBslHoverHtml.mergeHtml(IrBslHoverHtml.readHtml(baseInfo), irHtml);
                reloader.putIrMergedHtml(cacheKey, merged);
                return merged;
            }
            return baseInfo;
        }
        // Активация (ОписаниеТекущегоСловаАвтодополнения) нужна и без BslBrowserInput —
        // иначе overlap EDT+ИР не получает calculated type в подписи строки.
        maybeScheduleEdtRowActivation(reloader);
        return baseInfo;
    }

    private static String resolveActivationHtmlFromCache(
        ContentAssistSessionReloader reloader, String cacheKey)
    {
        IrBslCompletionSupport.ActivationDescription act = reloader.getIrActivation(cacheKey);
        if (act == null)
            return null;
        return IrBslCompletionSupport.formatActivationHtml(act.description, act.rawHtml);
    }

    private void maybeScheduleEdtRowActivation(ContentAssistSessionReloader reloader)
    {
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        if (cacheKey == null || cacheKey.isEmpty())
            return;
        if (reloader.getIrActivation(cacheKey) != null)
            return;
        if (reloader.isIrActivationPending(cacheKey))
            return;
        scheduleEdtRowActivation();
    }

    private void scheduleEdtRowActivation()
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "scheduleEdtRowActivation", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"reason\":\"filtersOff\"}"); //$NON-NLS-1$
            // #endregion
            return;
        }
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "scheduleEdtRowActivation", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"reason\":\"noReloader\"}"); //$NON-NLS-1$
            // #endregion
            return;
        }
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        String name = BslCompletionSideHintResolver.resolveElementName(delegate);
        String kind = BslCompletionSideHintResolver.resolveElementKind(delegate);
        if (cacheKey == null || cacheKey.isEmpty() || name == null || name.isEmpty()
            || kind == null)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "scheduleEdtRowActivation", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"reason\":\"badKey\"" //$NON-NLS-1$
                    + ",\"name\":\"" + ContentAssistDebug.jsonEscapeForLog(name) //$NON-NLS-1$
                    + "\",\"kind\":\"" + ContentAssistDebug.jsonEscapeForLog(kind) //$NON-NLS-1$
                    + "\",\"cacheKeyLen\":" + (cacheKey != null ? cacheKey.length() : -1) //$NON-NLS-1$
                    + ",\"display\":\"" + ContentAssistDebug.jsonEscapeForLog( //$NON-NLS-1$
                        delegate.getDisplayString()) + "\"}"); //$NON-NLS-1$
            // #endregion
            return;
        }
        Object baseInfo = null;
        if (delegate instanceof ICompletionProposalExtension5 ext5)
            baseInfo = ext5.getAdditionalProposalInfo(new NullProgressMonitor());
        else
            baseInfo = delegate.getAdditionalProposalInfo();
        final Object baseInput = baseInfo;
        IrBslCompletionSupport.ActivationDescription cachedRaw = reloader.getIrActivation(cacheKey);
        // Пустой тип при overlap — обычно fetch без КлючНабораСлов до прихода ИР; не держим кэш.
        if (cachedRaw != null && (cachedRaw.type == null || cachedRaw.type.isEmpty())
            && resolveEdtOverlapDictionaryKey(delegate) != null)
        {
            reloader.removeIrActivation(cacheKey);
            cachedRaw = null;
        }
        final IrBslCompletionSupport.ActivationDescription cached = cachedRaw;
        if (cached != null)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "scheduleEdtRowActivation", "cached", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"name\":\"" + ContentAssistDebug.jsonEscapeForLog(name) //$NON-NLS-1$
                    + "\",\"kind\":\"" + ContentAssistDebug.jsonEscapeForLog(kind) //$NON-NLS-1$
                    + "\",\"type\":\"" + ContentAssistDebug.jsonEscapeForLog(cached.type) //$NON-NLS-1$
                    + "\"}"); //$NON-NLS-1$
            // #endregion
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyCachedEdtActivation(
                reloader, cacheKey, delegate.getDisplayString(), cached, baseInput, delegate));
            return;
        }
        if (!reloader.tryBeginIrActivation(cacheKey))
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "scheduleEdtRowActivation", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"reason\":\"pending\",\"name\":\"" //$NON-NLS-1$
                    + ContentAssistDebug.jsonEscapeForLog(name) + "\"}"); //$NON-NLS-1$
            // #endregion
            return;
        }
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor());
        if (session == null)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "scheduleEdtRowActivation", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"reason\":\"noSession\",\"name\":\"" //$NON-NLS-1$
                    + ContentAssistDebug.jsonEscapeForLog(name) + "\"}"); //$NON-NLS-1$
            // #endregion
            reloader.endIrActivation(cacheKey);
            return;
        }
        final boolean isMethod =
            IrBslExpressionHtmlSupport.KIND_METHOD.equals(kind);
        final int gen = reloader.beginIrFetchForDisplay(cacheKey);
        final String displayKey = delegate.getDisplayString();
        final BslXtextEditor editor = reloader.getBslEditor();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            reloader.endIrActivation(cacheKey);
            return;
        }
        // #region agent log
        ContentAssistDebug.debugModeLog("H91", "scheduleEdtRowActivation", "fetch", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"name\":\"" + ContentAssistDebug.jsonEscapeForLog(name) //$NON-NLS-1$
                + "\",\"kind\":\"" + ContentAssistDebug.jsonEscapeForLog(kind) //$NON-NLS-1$
                + "\",\"isMethod\":" + isMethod //$NON-NLS-1$
                + ",\"gen\":" + gen //$NON-NLS-1$
                + ",\"display\":\"" + ContentAssistDebug.jsonEscapeForLog(displayKey) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        display.asyncExec(() -> submitEdtActivationOnUi(
            reloader, session, editor, gen, cacheKey, displayKey, name, isMethod, baseInput, delegate));
    }

    /** RDT {@code ПриАктивизацииСтрокиТ9}: динамический тип и описание ИР-слова. */
    private void scheduleIrWordActivation(IrCompletionProposal ir)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return;
        String cacheKey = ir.getStableCacheKey();
        IrBslCompletionSupport.ActivationDescription cached = reloader.getIrActivation(cacheKey);
        if (cached != null)
        {
            applyCachedIrActivation(reloader, ir, cached);
            return;
        }
        if (!reloader.tryBeginIrActivation(cacheKey))
        {
            return;
        }
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor());
        if (session == null)
        {
            reloader.endIrActivation(cacheKey);
            return;
        }
        final int gen = reloader.beginIrFetchForDisplay(cacheKey);
        final BslXtextEditor editor = reloader.getBslEditor();
        submitIrActivationOnUi(reloader, session, editor, gen, ir);
    }

    private static void applyCachedIrActivation(
        ContentAssistSessionReloader reloader, IrCompletionProposal ir,
        IrBslCompletionSupport.ActivationDescription desc)
    {
        String cacheKey = ir.getStableCacheKey();
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        int gen = reloader.getIrFetchGeneration();
        applyIrListTypeFromActivation(reloader, assistant, ir, desc, gen);
        String html = reloader.getIrMergedHtml(cacheKey);
        if (html == null || html.isEmpty())
            html = ir.getAdditionalProposalInfo();
        publishIrSideHintAfterActivation(reloader, assistant, ir, cacheKey, html, true);
    }

    private static void submitIrActivationOnUi(
        ContentAssistSessionReloader reloader, IRSession session, BslXtextEditor editor,
        int gen, IrCompletionProposal ir)
    {
        String cacheKey = ir != null ? ir.getStableCacheKey() : null;
        if (editor == null || session == null || reloader == null || ir == null)
        {
            if (reloader != null)
                reloader.endIrActivation(cacheKey);
            return;
        }
        Runnable fetch = () -> runIrActivationFetch(reloader, session, gen, ir);
        if (!reloader.isWordsTableReady())
        {
            reloader.runWhenWordsTableReady(() -> session.executor.submit(fetch));
            return;
        }
        session.executor.submit(fetch);
    }

    private static void runIrActivationFetch(
        ContentAssistSessionReloader reloader, IRSession session, int gen, IrCompletionProposal ir)
    {
        String cacheKey = ir.getStableCacheKey();
        try
        {
            IrBslCompletionSupport.ActivationDescription cached =
                reloader.getIrActivation(cacheKey);
            if (cached != null)
            {
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> applyIrActivationResult(
                    reloader, session, gen, ir, cached));
                return;
            }
            IrBslCompletionSupport.ActivationDescription desc =
                IrBslCompletionSupport.fetchWordActivationDescription(
                    session, ir.getWordValue(), ir.isMethod(), ir.getDictionaryKey());
            if (desc == null)
                return;
            reloader.putIrActivation(cacheKey, desc);
            session.pumpUserMessagesToUi();
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyIrActivationResult(reloader, session, gen, ir, desc));
        }
        finally
        {
            reloader.endIrActivation(cacheKey);
        }
    }

    private static void applyIrActivationResult(
        ContentAssistSessionReloader reloader, IRSession session, int gen,
        IrCompletionProposal ir, IrBslCompletionSupport.ActivationDescription desc)
    {
        reloader.putIrActivation(ir.getStableCacheKey(), desc);
        boolean staleGen = gen != reloader.getIrFetchGeneration();
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        applyIrListTypeFromActivation(reloader, assistant, ir, desc, gen);
        String cacheKey = ir.getStableCacheKey();
        String html = ir.getAdditionalProposalInfo();
        if (!staleGen)
            publishIrSideHintAfterActivation(reloader, assistant, ir, cacheKey, html, false);
    }

    /** Только подпись строки ИР в списке (тип из COM), без боковой подсказки. */
    private static void applyIrListTypeFromActivation(
        ContentAssistSessionReloader reloader,
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        IrCompletionProposal ir, IrBslCompletionSupport.ActivationDescription desc, int gen)
    {
        String cacheKey = ir.getStableCacheKey();
        String oldDisplay = ir.getDisplayString();
        if (desc.type != null)
            ir.applyActivation(desc.type, desc.description, desc.rawHtml);
        else if (desc.description != null)
            ir.applyActivation("", desc.description, desc.rawHtml); //$NON-NLS-1$
        String newDisplay = ir.getDisplayString();
        String html = ir.getAdditionalProposalInfo();
        if (html != null && !html.isEmpty())
        {
            reloader.putIrMergedHtml(cacheKey, html);
            reloader.putIrMergedHtml(newDisplay, html);
        }
        if (!oldDisplay.equals(newDisplay))
        {
            ContentAssistPopupSync.refreshProposalTable(assistant);
        }
    }

    /**
     * Боковая подсказка ИР — отдельно от обновления типа в списке.
     *
     * @param fromSelectedActivation {@code true} в {@code selected()} — строка активируется сейчас
     */
    private static boolean publishIrSideHintAfterActivation(
        ContentAssistSessionReloader reloader,
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        IrCompletionProposal ir, String cacheKey, String html,
        boolean fromSelectedActivation)
    {
        if (html == null || html.isEmpty())
            return false;
        if (!fromSelectedActivation
            && !ContentAssistPopupSync.isSelectedIrProposal(assistant, ir))
            return false;
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        int caret = SmartContentAssistProcessor.resolveSessionCaret(assistant, viewer);
        boolean inLiteral = viewer != null && caret >= 0
            && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        if (inLiteral && reloader.isLiteralOpenSetupPhase())
        {
            ContentAssistPopupSync.logLiteralSideHintSuppressed(fromSelectedActivation,
                "setupPhase"); //$NON-NLS-1$
            return false;
        }
        if (fromSelectedActivation && inLiteral && reloader.isLiteralFinishPinPending())
            return false;
        boolean cachedCreatorBefore = reloader.getAssistBrowserCreator() != null;
        reloader.noteActiveIrDisplayKey(cacheKey);
        boolean pinned = inLiteral
            ? ContentAssistPopupSync.pinIrSideHint(assistant, ir, html, false)
            : ContentAssistPopupSync.pinIrSideHint(assistant, ir, html);
        if (pinned)
            reloader.markIrSideHintPublished(cacheKey);
        else if (!reloader.isIrSideHintPublishedForKey(cacheKey))
            ContentAssistPopupSync.publishIrActivationSideHint(assistant, html, cacheKey);
        // #region agent log
        if (fromSelectedActivation && !inLiteral)
        {
            ContentAssistDebug.debugModeLog("H25", "irActivationWarmup", "done", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"inLiteral\":false" //$NON-NLS-1$
                    + ",\"cacheKey\":\"" + ContentAssistDebug.jsonEscapeForLog(cacheKey) //$NON-NLS-1$
                    + "\",\"cachedCreatorBefore\":" + cachedCreatorBefore //$NON-NLS-1$
                    + ",\"cachedCreatorAfter\":" + (reloader.getAssistBrowserCreator() != null) //$NON-NLS-1$
                    + ",\"hasBrowserAfter\":" + ContentAssistPopupSync.hasAssistBrowserSidePanel(assistant) //$NON-NLS-1$
                    + ",\"pinOk\":" + pinned //$NON-NLS-1$
                    + ",\"viewerHash\":" + (viewer != null ? System.identityHashCode(viewer) : -1) //$NON-NLS-1$
                    + ",\"build\":\"" + ContentAssistDebug.LITERAL_ASSIST_BUILD + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // #endregion
        return pinned;
    }

    private static void applyCachedEdtActivation(
        ContentAssistSessionReloader reloader, String cacheKey, String displayKey,
        IrBslCompletionSupport.ActivationDescription desc, Object baseInput,
        ICompletionProposal edtDelegate)
    {
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        applyEdtListTypeFromIrActivation(assistant, edtDelegate, desc);
        applyEdtActivationToSidePanel(
            reloader, assistant, cacheKey, displayKey, desc, baseInput);
    }

    private static void submitEdtActivationOnUi(
        ContentAssistSessionReloader reloader, IRSession session, BslXtextEditor editor,
        int gen, String cacheKey, String displayKey, String wordValue, boolean isMethod,
        Object baseInput, ICompletionProposal edtDelegate)
    {
        if (editor == null || session == null || reloader == null)
        {
            if (reloader != null)
                reloader.endIrActivation(cacheKey);
            return;
        }
        Runnable fetch = () -> runEdtActivationFetch(
            reloader, session, gen, cacheKey, displayKey, wordValue, isMethod, baseInput,
            edtDelegate);
        if (!reloader.isWordsTableReady())
        {
            reloader.runWhenWordsTableReady(() -> session.executor.submit(fetch));
            return;
        }
        session.executor.submit(fetch);
    }

    private static void runEdtActivationFetch(
        ContentAssistSessionReloader reloader, IRSession session, int gen,
        String cacheKey, String displayKey, String wordValue, boolean isMethod,
        Object baseInput, ICompletionProposal edtDelegate)
    {
        try
        {
            IrBslCompletionSupport.ActivationDescription cached =
                reloader.getIrActivation(cacheKey);
            if (cached != null)
            {
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> applyEdtActivationResult(
                    reloader, session, gen, cacheKey, displayKey, cached, baseInput, edtDelegate));
                return;
            }
            IrOverlapActivationTarget target = resolveEdtOverlapActivationTarget(
                edtDelegate, wordValue, isMethod);
            IrBslCompletionSupport.ActivationDescription desc =
                IrBslCompletionSupport.fetchWordActivationDescription(
                    session, target.wordValue, target.isMethod, target.dictionaryKey);
            if (desc == null)
            {
                // #region agent log
                ContentAssistDebug.debugModeLog("H91", "runEdtActivationFetch", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"reason\":\"nullDesc\",\"word\":\"" //$NON-NLS-1$
                        + ContentAssistDebug.jsonEscapeForLog(wordValue)
                        + "\",\"dict\":\"" + ContentAssistDebug.jsonEscapeForLog( //$NON-NLS-1$
                            target.dictionaryKey) + "\"}"); //$NON-NLS-1$
                // #endregion
                return;
            }
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "runEdtActivationFetch", "ok", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"word\":\"" + ContentAssistDebug.jsonEscapeForLog(target.wordValue) //$NON-NLS-1$
                    + "\",\"dict\":\"" + ContentAssistDebug.jsonEscapeForLog(target.dictionaryKey) //$NON-NLS-1$
                    + "\",\"isMethod\":" + target.isMethod //$NON-NLS-1$
                    + ",\"type\":\"" + ContentAssistDebug.jsonEscapeForLog(desc.type) //$NON-NLS-1$
                    + "\",\"gen\":" + gen + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            // Без КлючНабораСлов ИР часто отдаёт пустой Тип — не кэшируем, иначе overlap залипает.
            boolean hasType = desc.type != null && !desc.type.isEmpty();
            boolean hasDict = target.dictionaryKey != null && !target.dictionaryKey.isEmpty();
            if (hasType || hasDict)
                reloader.putIrActivation(cacheKey, desc);
            session.pumpUserMessagesToUi();
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyEdtActivationResult(
                reloader, session, gen, cacheKey, displayKey, desc, baseInput, edtDelegate));
        }
        finally
        {
            reloader.endIrActivation(cacheKey);
        }
    }

    private static void applyEdtActivationResult(
        ContentAssistSessionReloader reloader, IRSession session, int gen,
        String cacheKey, String displayKey, IrBslCompletionSupport.ActivationDescription desc,
        Object baseInput, ICompletionProposal edtDelegate)
    {
        reloader.putIrActivation(cacheKey, desc);
        if (gen != reloader.getIrFetchGeneration())
            return;
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        applyEdtListTypeFromIrActivation(assistant, edtDelegate, desc);
        applyEdtActivationToSidePanel(
            reloader, assistant, cacheKey, displayKey, desc, baseInput);
    }

    /** Overlap EDT+ИР: тип в списке из ИР после активации строки. */
    private static void applyEdtListTypeFromIrActivation(
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        ICompletionProposal edtDelegate, IrBslCompletionSupport.ActivationDescription desc)
    {
        if (desc == null || desc.type == null || desc.type.isEmpty())
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "applyEdtListTypeFromIrActivation", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"reason\":\"noType\"}"); //$NON-NLS-1$
            // #endregion
            return;
        }
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "applyEdtListTypeFromIrActivation", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"reason\":\"noProcessor\"}"); //$NON-NLS-1$
            // #endregion
            return;
        }
        String key = SmartContentAssistProcessor.dedupKeyForMerge(edtDelegate);
        IrCompletionProposal ir = processor.findIrProposalForDedupKey(key);
        if (ir == null)
        {
            // #region agent log
            ContentAssistDebug.debugModeLog("H91", "applyEdtListTypeFromIrActivation", "skip", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"reason\":\"noIr\"" //$NON-NLS-1$
                    + ",\"key\":\"" + ContentAssistDebug.jsonEscapeForLog(key) //$NON-NLS-1$
                    + "\",\"type\":\"" + ContentAssistDebug.jsonEscapeForLog(desc.type) //$NON-NLS-1$
                    + "\",\"display\":\"" + ContentAssistDebug.jsonEscapeForLog( //$NON-NLS-1$
                        edtDelegate.getDisplayString()) + "\"}"); //$NON-NLS-1$
            // #endregion
            return;
        }
        String edtDisplay = edtDelegate.getDisplayString();
        String old = processor.resolveIrOverlapDisplay(edtDisplay, key);
        processor.putIrOverlapTypeFromIr(key, ir, desc.type);
        String display = SmartContentAssistProcessor.injectIrTypeIntoEdtDisplay(
            edtDisplay, ir.getListTypeLabel(), desc.type, ir.getParentContextType());
        SmartContentAssistProcessor.applyIrDisplayToEdt(edtDelegate, display);
        boolean same = display.equals(old);
        // #region agent log
        ContentAssistDebug.debugModeLog("H91", "applyEdtListTypeFromIrActivation", "done", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"key\":\"" + ContentAssistDebug.jsonEscapeForLog(key) //$NON-NLS-1$
                + "\",\"type\":\"" + ContentAssistDebug.jsonEscapeForLog(desc.type) //$NON-NLS-1$
                + "\",\"same\":" + same //$NON-NLS-1$
                + ",\"old\":\"" + ContentAssistDebug.jsonEscapeForLog(old) //$NON-NLS-1$
                + "\",\"new\":\"" + ContentAssistDebug.jsonEscapeForLog(display) + "\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        if (same)
            return;
        ContentAssistPopupSync.refreshProposalTable(assistant);
    }

    private static void applyEdtActivationToSidePanel(
        ContentAssistSessionReloader reloader,
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        String cacheKey, String displayKey,
        IrBslCompletionSupport.ActivationDescription desc, Object baseInput)
    {
        String irHtml = IrBslCompletionSupport.formatActivationHtml(desc.description, desc.rawHtml);
        if (irHtml == null || irHtml.isEmpty())
            return;
        String merged = IrBslHoverHtml.isBslBrowserInput(baseInput)
            ? IrBslHoverHtml.mergeHtml(IrBslHoverHtml.readHtml(baseInput), irHtml)
            : irHtml;
        reloader.putIrMergedHtml(cacheKey, merged);
        if (ContentAssistPopupSync.isSelectedDisplay(assistant, displayKey))
        {
            SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
            int caret = SmartContentAssistProcessor.resolveWidgetCaret(viewer);
            boolean literalOverlap = viewer != null && caret >= 0
                && SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret)
                && IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor()) != null;
            ContentAssistPopupSync.pinMergedAdditionalInfo(assistant, displayKey, merged,
                literalOverlap);
        }
    }

    /** Параметры COM {@code ОписаниеТекущегоСловаАвтодополнения} для overlap EDT+ИР. */
    private static final class IrOverlapActivationTarget
    {
        final String wordValue;
        final boolean isMethod;
        final String dictionaryKey;

        IrOverlapActivationTarget(String wordValue, boolean isMethod, String dictionaryKey)
        {
            this.wordValue = wordValue;
            this.isMethod = isMethod;
            this.dictionaryKey = dictionaryKey != null ? dictionaryKey : ""; //$NON-NLS-1$
        }
    }

    private static IrOverlapActivationTarget resolveEdtOverlapActivationTarget(
        ICompletionProposal edtDelegate, String wordValue, boolean isMethod)
    {
        IrCompletionProposal ir = findIrOverlapForEdt(edtDelegate);
        if (ir == null)
            return new IrOverlapActivationTarget(wordValue, isMethod, ""); //$NON-NLS-1$
        return new IrOverlapActivationTarget(
            ir.getWordValue() != null && !ir.getWordValue().isEmpty()
                ? ir.getWordValue() : wordValue,
            ir.isMethod(),
            ir.getDictionaryKey());
    }

    private static String resolveEdtOverlapDictionaryKey(ICompletionProposal edtDelegate)
    {
        IrCompletionProposal ir = findIrOverlapForEdt(edtDelegate);
        if (ir == null)
            return null;
        String dict = ir.getDictionaryKey();
        return dict != null && !dict.isEmpty() ? dict : null;
    }

    private static IrCompletionProposal findIrOverlapForEdt(ICompletionProposal edtDelegate)
    {
        if (edtDelegate == null)
            return null;
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        if (processor == null)
            return null;
        return processor.findIrProposalForDedupKey(
            SmartContentAssistProcessor.dedupKeyForMerge(edtDelegate));
    }
}
