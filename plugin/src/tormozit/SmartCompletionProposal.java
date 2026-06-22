package tormozit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IInformationControlExtension5;
import org.eclipse.jface.util.OpenStrategy;
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
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;
import org.eclipse.xtext.ui.editor.hover.html.IXtextBrowserInformationControl;

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
        String display = delegate.getDisplayString();
        if (display == null)
            return new StyledString(""); //$NON-NLS-1$

        SmartCodeMatcher matcher = resolveHighlightMatcher();
        if (matcher.isEmpty)
            return new StyledString(display);

        StyledString result = new StyledString(display);
        SmartMatchHighlight.applyRanges(result, matcher.getHighlightRanges(display));
        return result;
    }

    @Override
    public String getDisplayString()
    {
        return delegate.getDisplayString();
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
        if (tryApplyWordOnly(document, null, -1, null))
            return;
        delegate.apply(document);
    }

    // ---- ICompletionProposalExtension ---------------------------------------

    @Override
    public void apply(IDocument document, char trigger, int offset)
    {
        if (tryApplyWordOnly(document, null, offset, null))
            return;
        if (delegate instanceof ICompletionProposalExtension)
            ((ICompletionProposalExtension) delegate).apply(document, trigger, offset);
        else
            delegate.apply(document);
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
        if (viewer != null && tryApplyWordOnly(viewer.getDocument(), viewer, offset, stateMask))
            return;
        if (delegate instanceof ICompletionProposalExtension2)
            ((ICompletionProposalExtension2) delegate).apply(viewer, trigger, stateMask, offset);
        else if (viewer != null && viewer.getDocument() != null)
            apply(viewer.getDocument(), trigger, offset);
    }

    @Override
    public void selected(ITextViewer viewer, boolean smartToggle)
    {
        if (delegate instanceof ICompletionProposalExtension2)
            ((ICompletionProposalExtension2) delegate).selected(viewer, smartToggle);
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
        if (delegate instanceof ICompletionProposalExtension5)
        {
            Object info = ((ICompletionProposalExtension5) delegate)
                .getAdditionalProposalInfo(new NullProgressMonitor());
            if (info instanceof String)
                return (String) info;
            if (info instanceof StyledString)
                return ((StyledString) info).getString();
            return info != null ? info.toString() : null;
        }
        return delegate.getAdditionalProposalInfo();
    }

    @Override
    public IInformationControlCreator getInformationControlCreator()
    {
        if (delegate instanceof ICompletionProposalExtension3)
            return ((ICompletionProposalExtension3) delegate).getInformationControlCreator();
        return null;
    }

    @Override
    public Object getAdditionalProposalInfo(IProgressMonitor monitor)
    {
        Object info = null;
        if (delegate instanceof ICompletionProposalExtension5)
            info = ((ICompletionProposalExtension5) delegate).getAdditionalProposalInfo(monitor);
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null && ComfortSettings.isReplaceListFiltersEnabled())
        {
            String cached = reloader.getIrMergedHtml(delegate.getDisplayString());
            if (cached != null && !cached.isEmpty())
                return cached;
        }
        scheduleIrSideHintEnrichment(info);
        return info;
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

    private void scheduleIrSideHintEnrichment(Object baseInfo)
    {
        if (!IrBslHoverHtml.isBslBrowserInput(baseInfo))
            return;
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null)
        {
            String cached = reloader.getIrMergedHtml(delegate.getDisplayString());
            if (cached != null && !cached.isEmpty())
                return;
        }
        String name = BslCompletionSideHintResolver.resolveElementName(delegate);
        String kind = BslCompletionSideHintResolver.resolveElementKind(delegate);
        if (name == null || name.isEmpty() || kind == null)
            return;
        if (reloader == null)
            return;
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor());
        if (session == null)
            return;
        final int gen = reloader.beginIrFetchForDisplay(delegate.getDisplayString());
        final String displayKey = delegate.getDisplayString();
        final Object baseInput = baseInfo;
        final BslXtextEditor editor = reloader.getBslEditor();
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> submitIrFetchOnUi(
            reloader, session, editor, gen, displayKey, baseInput, name, kind));
    }

    /** Sync/payload — только UI-поток; {@code getAdditionalProposalInfo} вызывается с фонового. */
    private static void submitIrFetchOnUi(
        ContentAssistSessionReloader reloader, IRSession session, BslXtextEditor editor,
        int gen, String displayKey, Object baseInput, String name, String kind)
    {
        if (editor == null || session == null || reloader == null)
            return;
        int caret = resolveAssistCaret(reloader);
        IRSession.CodeEditorSyncPayload syncPayload =
            caret >= 0 ? session.prepareCodeEditorSyncForHover(editor, caret) : null;
        Runnable fetch = () -> runIrFetch(
            reloader, session, gen, displayKey, baseInput, name, kind, syncPayload, false);
        if (!reloader.isWordsTableReady())
        {
            reloader.runWhenWordsTableReady(() -> session.executor.submit(fetch));
            return;
        }
        session.executor.submit(fetch);
    }

    private static void runIrFetch(
        ContentAssistSessionReloader reloader, IRSession session,
        int gen, String displayKey, Object baseInput,
        String name, String kind, IRSession.CodeEditorSyncPayload syncPayload, boolean retried)
    {
        String irHtml = IrBslExpressionHtmlSupport.fetchDescriptionHtmlAfterPreparedSync(
            session, syncPayload, name, kind);
        if ((irHtml == null || irHtml.isBlank()) && !retried && syncPayload != null)
        {
            runIrFetch(reloader, session, gen, displayKey, baseInput, name, kind, syncPayload, true);
            return;
        }
        if (irHtml == null || irHtml.isBlank())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> applyIrSideHintEnrichment(
            reloader, gen, displayKey, baseInput, irHtml, 0));
    }

    private static int resolveAssistCaret(ContentAssistSessionReloader reloader)
    {
        SourceViewer viewer = ContentAssistSessionReloader.getActiveViewer();
        if (viewer != null && viewer.getTextWidget() instanceof StyledText text
            && !text.isDisposed())
        {
            int caret = text.getCaretOffset();
            if (caret >= 0)
                return caret;
        }
        return reloader.getWordsTableCaret();
    }

    /** Панель assist появляется с задержкой {@link OpenStrategy#getPostSelectionDelay()}. */
    private static final int APPLY_RETRY_MS = 80;
    private static final int APPLY_MAX_ATTEMPTS = 12;

    private static void applyIrSideHintEnrichment(
        ContentAssistSessionReloader reloader, int gen,
        String displayKey, Object baseInput, String irHtml, int attempt)
    {
        if (gen != reloader.getIrFetchGeneration())
        {
            BslSideHintDebug.step("ir skip", "stale gen=" + gen); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null)
            return;
        if (!ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        IXtextBrowserInformationControl control =
            ContentAssistPopupSync.resolveVisibleBrowserControl(assistant);
        if (control == null
            || !(control instanceof IInformationControlExtension5 ext5)
            || !ext5.isVisible())
        {
            if (attempt < APPLY_MAX_ATTEMPTS)
            {
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed())
                {
                    display.timerExec(APPLY_RETRY_MS, () -> applyIrSideHintEnrichment(
                        reloader, gen, displayKey, baseInput, irHtml, attempt + 1));
                }
                return;
            }
            BslSideHintDebug.step("ir skip", "control null"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        publishIrMergedHint(assistant, reloader, displayKey, baseInput, irHtml, attempt);
    }

    private static void publishIrMergedHint(
        ContentAssistant assistant, ContentAssistSessionReloader reloader,
        String displayKey, Object baseInput, String irHtml, int attempt)
    {
        String merged = IrBslHoverHtml.mergeHtml(IrBslHoverHtml.readHtml(baseInput), irHtml);
        reloader.putIrMergedHtml(displayKey, merged);
        if (!ContentAssistPopupSync.isSelectedDisplay(assistant, displayKey))
            return;
        scheduleSingleMergedPin(assistant, reloader, displayKey, irHtml.length());
    }

    /** Одно обновление панели — после штатного {@code showInformation} с базой. */
    private static void scheduleSingleMergedPin(
        ContentAssistant assistant, ContentAssistSessionReloader reloader,
        String displayKey, int irLen)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        final int scheduleId = reloader.nextRepinScheduleId();
        int postDelay = OpenStrategy.getPostSelectionDelay() + 50;
        long pinAt = reloader.getIrSelectionEpochMs() + postDelay;
        long delay = Math.max(0L, pinAt - System.currentTimeMillis());
        if (delay > Integer.MAX_VALUE)
            delay = Integer.MAX_VALUE;
        display.timerExec((int) delay, () -> {
            if (!reloader.isCurrentRepinSchedule(scheduleId))
                return;
            if (!ContentAssistPopupSync.isPopupVisible(assistant))
                return;
            if (!ContentAssistPopupSync.isSelectedDisplay(assistant, displayKey))
                return;
            String cached = reloader.getIrMergedHtml(displayKey);
            if (cached == null || cached.isEmpty())
                return;
            ContentAssistPopupSync.pinMergedAdditionalInfo(assistant, displayKey, cached);
            BslSideHintDebug.log("ir enriched assist name=" + displayKey + " len=" + irLen); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }
}
