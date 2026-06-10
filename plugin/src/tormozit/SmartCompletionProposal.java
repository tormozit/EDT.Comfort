package tormozit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension4;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

/**
 * Обёртка proposal: штатный popup вызывает {@link #validate} и
 * {@link #getStyledDisplayString()} — фильтр и подсветка через общий {@link SmartCodeMatcher}.
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
        delegate.apply(document);
    }

    // ---- ICompletionProposalExtension ---------------------------------------

    @Override
    public void apply(IDocument document, char trigger, int offset)
    {
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
        return false;
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
        if (delegate instanceof ICompletionProposalExtension5)
            return ((ICompletionProposalExtension5) delegate).getAdditionalProposalInfo(monitor);
        return null;
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

    private SmartCodeMatcher resolveHighlightMatcher()
    {
        String filter = SmartFilterTracker.getCurrentFilter();
        return new SmartCodeMatcher(filter != null ? filter : ""); //$NON-NLS-1$
    }

    private boolean matchesFilter(IDocument document, int offset, DocumentEvent event)
    {
        return SmartContentAssistProcessor.proposalMatchesFilter(delegate, document, offset, event);
    }
}
