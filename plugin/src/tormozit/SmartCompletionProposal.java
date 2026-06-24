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
import org.eclipse.xtext.ui.editor.contentassist.ConfigurableCompletionProposal;

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
        StyledString result;
        if (delegate instanceof ICompletionProposalExtension6 ext6)
            result = ext6.getStyledDisplayString();
        else
        {
            String display = delegate.getDisplayString();
            result = new StyledString(display != null ? display : ""); //$NON-NLS-1$
        }
        SmartCodeMatcher matcher = resolveHighlightMatcher();
        if (!matcher.isEmpty)
            SmartMatchHighlight.applyRanges(result, matcher.getHighlightRanges(result.getString()));
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

    /** Активация строки assist (Eclipse {@code selected}), не подтверждение {@code apply}. */
    @Override
    public void selected(ITextViewer viewer, boolean smartToggle)
    {
        if (delegate instanceof IrCompletionProposal ir)
            scheduleIrWordActivation(ir);
        else
            scheduleEdtRowActivation();
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
        if (delegate instanceof ICompletionProposalExtension3 ext3)
            return ext3.getInformationControlCreator();
        return null;
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
        long t0 = System.currentTimeMillis();
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader != null && ComfortSettings.isReplaceListFiltersEnabled())
        {
            String cacheKey = ir.getStableCacheKey();
            String merged = reloader.getIrMergedHtml(cacheKey);
            if (merged == null || merged.isEmpty())
                merged = reloader.getIrMergedHtml(ir.getDisplayString());
            if (merged != null && !merged.isEmpty())
            {
                // #region agent log
                ContentAssistDebug.debugSessionTiming("H19", "getAdditionalProposalInfo", "irReturn", t0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    ",\"htmlLen\":" + merged.length()); //$NON-NLS-1$
                // #endregion
                return merged;
            }
            if (reloader.isIrActivationPending(cacheKey))
            {
                // #region agent log
                ContentAssistDebug.debugSessionTiming("H23", "getAdditionalProposalInfo", "pendingSkip", t0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    ",\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey)); //$NON-NLS-1$
                // #endregion
                return null;
            }
            String html = resolveIrAssistSideHintHtml(reloader, ir);
            if (html != null && !html.isEmpty())
            {
                // #region agent log
                ContentAssistDebug.debugSessionTiming("H19", "getAdditionalProposalInfo", "irReturn", t0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    ",\"htmlLen\":" + html.length()); //$NON-NLS-1$
                // #endregion
                return html;
            }
            maybeScheduleIrWordActivation(reloader, ir);
        }
        String fallback = ir.getAdditionalProposalInfo();
        // #region agent log
        ContentAssistDebug.debugSessionTiming("H19", "getAdditionalProposalInfo", "irFallback", t0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ",\"htmlLen\":" + (fallback != null ? fallback.length() : 0)); //$NON-NLS-1$
        // #endregion
        return fallback;
    }

    /**
     * Боковая подсказка assist для ИР-строки: только кэш активации
     * ({@code ОписаниеТекущегоСловаАвтодополнения}), без {@code ОписаниеХТМЛВыражения}.
     */
    private static String resolveIrAssistSideHintHtml(
        ContentAssistSessionReloader reloader, IrCompletionProposal ir)
    {
        long t0 = System.currentTimeMillis();
        String branch = "none"; //$NON-NLS-1$
        String cacheKey = ir.getStableCacheKey();
        String cached = reloader.getIrMergedHtml(cacheKey);
        if (cached != null && !cached.isEmpty())
            branch = "mergedKey"; //$NON-NLS-1$
        else
        {
            cached = reloader.getIrMergedHtml(ir.getDisplayString());
            if (cached != null && !cached.isEmpty())
                branch = "mergedDisplay"; //$NON-NLS-1$
            else
            {
                String onProposal = ir.getAdditionalProposalInfo();
                if (onProposal != null && !onProposal.isEmpty())
                {
                    cached = onProposal;
                    branch = "proposal"; //$NON-NLS-1$
                }
                else
                {
                    IrBslCompletionSupport.ActivationDescription act =
                        reloader.getIrActivation(cacheKey);
                    if (act != null)
                    {
                        cached = IrBslCompletionSupport.formatActivationHtml(
                            act.description, act.rawHtml);
                        branch = "formatActivation"; //$NON-NLS-1$
                    }
                }
            }
        }
        // #region agent log
        ContentAssistDebug.debugSessionTiming("H19", "resolveIrAssistSideHintHtml", "done", t0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ",\"branch\":" + ContentAssistDebug.jsonStr(branch) //$NON-NLS-1$
                + ",\"htmlLen\":" + (cached != null ? cached.length() : 0)); //$NON-NLS-1$
        // #endregion
        return cached;
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
        if (!IrBslHoverHtml.isBslBrowserInput(baseInfo))
            return baseInfo;
        String irHtml = resolveActivationHtmlFromCache(reloader, cacheKey);
        if (irHtml != null && !irHtml.isEmpty())
        {
            merged = IrBslHoverHtml.mergeHtml(IrBslHoverHtml.readHtml(baseInfo), irHtml);
            reloader.putIrMergedHtml(cacheKey, merged);
            return merged;
        }
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
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return;
        String cacheKey = BslCompletionSideHintResolver.resolveIrCacheKey(delegate);
        String name = BslCompletionSideHintResolver.resolveElementName(delegate);
        String kind = BslCompletionSideHintResolver.resolveElementKind(delegate);
        if (cacheKey == null || cacheKey.isEmpty() || name == null || name.isEmpty()
            || kind == null)
            return;
        Object baseInfo = null;
        if (delegate instanceof ICompletionProposalExtension5 ext5)
            baseInfo = ext5.getAdditionalProposalInfo(new NullProgressMonitor());
        else
            baseInfo = delegate.getAdditionalProposalInfo();
        final Object baseInput = baseInfo;
        IrBslCompletionSupport.ActivationDescription cached = reloader.getIrActivation(cacheKey);
        if (cached != null)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyCachedEdtActivation(
                reloader, cacheKey, delegate.getDisplayString(), cached, baseInput));
            return;
        }
        if (!reloader.tryBeginIrActivation(cacheKey))
            return;
        IRSession session = IrBslExpressionHtmlSupport.resolveConnectedSession(reloader.getBslEditor());
        if (session == null)
        {
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
        display.asyncExec(() -> submitEdtActivationOnUi(
            reloader, session, editor, gen, cacheKey, displayKey, name, isMethod, baseInput));
    }

    /** RDT {@code ПриАктивизацииСтрокиТ9}: динамический тип и описание ИР-слова. */
    private void scheduleIrWordActivation(IrCompletionProposal ir)
    {
        long t0 = System.currentTimeMillis();
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
        if (reloader == null)
            return;
        String cacheKey = ir.getStableCacheKey();
        // #region agent log
        ContentAssistDebug.debugSessionLog("H5", "scheduleIrWordActivation", "enter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"word\":" + ContentAssistDebug.jsonStr(ir.getWordValue()) //$NON-NLS-1$
                + ",\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
        IrBslCompletionSupport.ActivationDescription cached = reloader.getIrActivation(cacheKey);
        if (cached != null)
        {
            // #region agent log
            ContentAssistDebug.debugSessionLog("H13", "scheduleIrWordActivation", "activationCacheHit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"word\":" + ContentAssistDebug.jsonStr(ir.getWordValue()) //$NON-NLS-1$
                    + ",\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            applyCachedIrActivation(reloader, ir, cached);
            // #region agent log
            ContentAssistDebug.debugSessionTiming("H18", "scheduleIrWordActivation", "cacheHitExit", t0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ",\"word\":" + ContentAssistDebug.jsonStr(ir.getWordValue())); //$NON-NLS-1$
            // #endregion
            return;
        }
        if (!reloader.tryBeginIrActivation(cacheKey))
        {
            // #region agent log
            ContentAssistDebug.debugSessionLog("H6", "scheduleIrWordActivation", "tryBeginBlocked", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"pending\":" + reloader.isIrActivationPending(cacheKey) //$NON-NLS-1$
                    + ",\"cached\":" + (reloader.getIrActivation(cacheKey) != null) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
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
        // #region agent log
        ContentAssistDebug.debugSessionTiming("H18", "scheduleIrWordActivation", "fetchSubmitExit", t0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ",\"word\":" + ContentAssistDebug.jsonStr(ir.getWordValue())); //$NON-NLS-1$
        // #endregion
    }

    private static void applyCachedIrActivation(
        ContentAssistSessionReloader reloader, IrCompletionProposal ir,
        IrBslCompletionSupport.ActivationDescription desc)
    {
        long t0 = System.currentTimeMillis();
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
        boolean pinned = publishIrSideHintAfterActivation(
            reloader, assistant, ir, cacheKey, html, true);
        // #region agent log
        ContentAssistDebug.debugSessionTiming("H15", "applyCachedIrActivation", "cacheHitFastPath", t0, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ",\"word\":" + ContentAssistDebug.jsonStr(ir.getWordValue()) //$NON-NLS-1$
                + ",\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey) //$NON-NLS-1$
                + ",\"pinned\":" + pinned); //$NON-NLS-1$
        // #endregion
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
                // #region agent log
                ContentAssistDebug.debugSessionLog("H13", "runIrActivationFetch", "cacheHit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"word\":" + ContentAssistDebug.jsonStr(ir.getWordValue()) //$NON-NLS-1$
                        + ",\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> applyIrActivationResult(
                    reloader, session, gen, ir, cached));
                return;
            }
            long tCom = System.currentTimeMillis();
            IrBslCompletionSupport.ActivationDescription desc =
                IrBslCompletionSupport.fetchWordActivationDescription(
                    session, ir.getWordValue(), ir.isMethod(), ir.getDictionaryKey());
            // #region agent log
            ContentAssistDebug.debugSessionTiming("H18", "runIrActivationFetch", "comFetch", tCom, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                desc == null ? ",\"desc\":null" : ",\"descLen\":" + desc.description.length()); //$NON-NLS-1$ //$NON-NLS-2$
            ContentAssistDebug.debugSessionLog("H3", "runIrActivationFetch", "comResult", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                desc == null ? "{\"desc\":null}" //$NON-NLS-1$
                    : "{\"type\":" + ContentAssistDebug.jsonStr(desc.type) //$NON-NLS-1$
                        + ",\"descLen\":" + desc.description.length() + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            if (desc == null)
                return;
            reloader.putIrActivation(cacheKey, desc);
            // #region agent log
            ContentAssistDebug.debugSessionLog("H14", "runIrActivationFetch", "cachedOnCom", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"word\":" + ContentAssistDebug.jsonStr(ir.getWordValue()) //$NON-NLS-1$
                    + ",\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
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
        if (staleGen)
        {
            // #region agent log
            ContentAssistDebug.debugSessionLog("H2", "applyIrActivationResult", "staleGenDisplayOnly", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"gen\":" + gen + ",\"current\":" + reloader.getIrFetchGeneration() + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
        }
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
        {
            // #region agent log
            ContentAssistDebug.debugSessionLog("H4", "applyIrActivationResult", "skipPopupHidden", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"assistant\":" + (assistant != null) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            return;
        }
        applyIrListTypeFromActivation(reloader, assistant, ir, desc, gen);
        String cacheKey = ir.getStableCacheKey();
        String html = ir.getAdditionalProposalInfo();
        boolean selected = ContentAssistPopupSync.isSelectedIrProposal(assistant, ir);
        boolean pinned = !staleGen && publishIrSideHintAfterActivation(
            reloader, assistant, ir, cacheKey, html, false);
        // #region agent log
        ContentAssistDebug.debugSessionLog("H4", "applyIrActivationResult", "applied", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "{\"display\":" + ContentAssistDebug.jsonStr(ir.getDisplayString()) //$NON-NLS-1$
                + ",\"htmlLen\":" + (html != null ? html.length() : 0) //$NON-NLS-1$
                + ",\"descLen\":" + desc.description.length() //$NON-NLS-1$
                + ",\"fullDoc\":" + IrBslHoverHtml.isFullHtmlDocument(html) //$NON-NLS-1$
                + ",\"selected\":" + selected //$NON-NLS-1$
                + ",\"pinned\":" + pinned + "}"); //$NON-NLS-1$ //$NON-NLS-2$
        // #endregion
    }

    /** Только подпись строки ИР в списке (тип из COM), без боковой подсказки. */
    private static void applyIrListTypeFromActivation(
        ContentAssistSessionReloader reloader,
        org.eclipse.jface.text.contentassist.ContentAssistant assistant,
        IrCompletionProposal ir, IrBslCompletionSupport.ActivationDescription desc, int gen)
    {
        String cacheKey = ir.getStableCacheKey();
        String oldDisplay = ir.getDisplayString();
        if (desc.type != null && !desc.type.isBlank())
            ir.applyActivation(desc.type, desc.description, desc.rawHtml);
        else if (desc.description != null && !desc.description.isBlank())
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
            // #region agent log
            ContentAssistDebug.debugSessionLog("H29", "irListTypeUpdate", "done", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey) //$NON-NLS-1$
                    + ",\"old\":" + ContentAssistDebug.jsonStr(oldDisplay) //$NON-NLS-1$
                    + ",\"new\":" + ContentAssistDebug.jsonStr(newDisplay) //$NON-NLS-1$
                    + ",\"selected\":" + ContentAssistPopupSync.isSelectedIrProposal(assistant, ir) //$NON-NLS-1$
                    + ",\"gen\":" + gen + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
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
        reloader.noteActiveIrDisplayKey(cacheKey);
        boolean pinned = ContentAssistPopupSync.pinIrSideHint(assistant, ir, html);
        if (pinned)
            reloader.markIrSideHintPublished(cacheKey);
        else if (!reloader.isIrSideHintPublishedForKey(cacheKey))
            ContentAssistPopupSync.publishIrActivationSideHint(assistant, html, cacheKey);
        return pinned;
    }

    private static void applyCachedEdtActivation(
        ContentAssistSessionReloader reloader, String cacheKey, String displayKey,
        IrBslCompletionSupport.ActivationDescription desc, Object baseInput)
    {
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        applyEdtActivationToSidePanel(
            reloader, assistant, cacheKey, displayKey, desc, baseInput);
    }

    private static void submitEdtActivationOnUi(
        ContentAssistSessionReloader reloader, IRSession session, BslXtextEditor editor,
        int gen, String cacheKey, String displayKey, String wordValue, boolean isMethod,
        Object baseInput)
    {
        if (editor == null || session == null || reloader == null)
        {
            if (reloader != null)
                reloader.endIrActivation(cacheKey);
            return;
        }
        Runnable fetch = () -> runEdtActivationFetch(
            reloader, session, gen, cacheKey, displayKey, wordValue, isMethod, baseInput);
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
        Object baseInput)
    {
        try
        {
            IrBslCompletionSupport.ActivationDescription cached =
                reloader.getIrActivation(cacheKey);
            if (cached != null)
            {
                // #region agent log
                ContentAssistDebug.debugSessionLog("H13", "runEdtActivationFetch", "cacheHit", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "{\"word\":" + ContentAssistDebug.jsonStr(wordValue) //$NON-NLS-1$
                        + ",\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
                // #endregion
                Display display = Display.getDefault();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() -> applyEdtActivationResult(
                    reloader, session, gen, cacheKey, displayKey, cached, baseInput));
                return;
            }
            IrBslCompletionSupport.ActivationDescription desc =
                IrBslCompletionSupport.fetchWordActivationDescription(
                    session, wordValue, isMethod, ""); //$NON-NLS-1$
            if (desc == null)
                return;
            reloader.putIrActivation(cacheKey, desc);
            // #region agent log
            ContentAssistDebug.debugSessionLog("H14", "runEdtActivationFetch", "cachedOnCom", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "{\"word\":" + ContentAssistDebug.jsonStr(wordValue) //$NON-NLS-1$
                    + ",\"cacheKey\":" + ContentAssistDebug.jsonStr(cacheKey) + "}"); //$NON-NLS-1$ //$NON-NLS-2$
            // #endregion
            session.pumpUserMessagesToUi();
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> applyEdtActivationResult(
                reloader, session, gen, cacheKey, displayKey, desc, baseInput));
        }
        finally
        {
            reloader.endIrActivation(cacheKey);
        }
    }

    private static void applyEdtActivationResult(
        ContentAssistSessionReloader reloader, IRSession session, int gen,
        String cacheKey, String displayKey, IrBslCompletionSupport.ActivationDescription desc,
        Object baseInput)
    {
        reloader.putIrActivation(cacheKey, desc);
        if (gen != reloader.getIrFetchGeneration())
            return;
        org.eclipse.jface.text.contentassist.ContentAssistant assistant =
            ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null || !ContentAssistPopupSync.isPopupVisible(assistant))
            return;
        applyEdtActivationToSidePanel(
            reloader, assistant, cacheKey, displayKey, desc, baseInput);
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
            ContentAssistPopupSync.pinMergedAdditionalInfo(assistant, displayKey, merged);
    }
}
