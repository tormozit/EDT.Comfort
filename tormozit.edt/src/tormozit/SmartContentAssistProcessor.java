package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;
import org.eclipse.swt.graphics.Point;

/**
 * Обёртка над {@link IContentAssistProcessor}.
 *
 * <p><b>Полный список</b> (п.1): при пустом фильтре — кэш delegate без smart-фильтрации.
 * <p><b>Фильтр+сорт+цвет</b> (п.2): при непустом фильтре — {@link #filterAndSort} по кэшу.
 */
public class SmartContentAssistProcessor implements IContentAssistProcessor
{
    private static final int NAME_WEIGHT = 10;
    private static final int PARAM_WEIGHT = 1;
    private static final ICompletionProposal[] EMPTY = new ICompletionProposal[0];

    private final IContentAssistProcessor delegate;
    private final String activationChars;

    private ICompletionProposal[] fullListCache = EMPTY;
    private boolean fullListReady = false;

    public SmartContentAssistProcessor(IContentAssistProcessor delegate, String activationChars)
    {
        this.delegate = delegate;
        this.activationChars = activationChars;
    }

    public IContentAssistProcessor getDelegate()
    {
        return delegate;
    }

    public void invalidateCache()
    {
        fullListReady = false;
        fullListCache = EMPTY;
        ContentAssistDebug.log("invalidateCache"); //$NON-NLS-1$
    }

    /** Заполняет кэш полного списка (вызов при старте сессии assist). */
    public void warmFullListCache(ITextViewer viewer)
    {
        ContentAssistDebug.log("warmFullListCache"); //$NON-NLS-1$
        loadFullList(viewer);
    }

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset)
    {
        int caret = resolveCaretOffset(viewer, offset);
        String filter = computeIdentifierFilter(viewer == null ? null : viewer.getDocument(), caret);
        SmartFilterTracker.setCurrentFilter(filter);

        if (filter.isEmpty())
        {
            if (!fullListReady)
                loadFullList(viewer);
            ContentAssistDebug.log("compute FULL offset=" + offset + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
                + " count=" + fullListCache.length //$NON-NLS-1$
                + ContentAssistDebug.sampleTypes(fullListCache, 2));
            return fullListCache;
        }

        if (!fullListReady)
            loadFullList(viewer);
        ICompletionProposal[] out = filterAndSort(fullListCache, filter);
        ContentAssistDebug.log("compute FILTER offset=" + offset + " caret=" + caret //$NON-NLS-1$ //$NON-NLS-2$
            + " filter=\"" + filter + "\" cache=" + fullListCache.length //$NON-NLS-1$ //$NON-NLS-2$
            + " out=" + out.length + ContentAssistDebug.sampleTypes(out, 3)); //$NON-NLS-1$
        return out;
    }

    // ---- Точка вызова алгоритма для popup.validate / isValidFor ----------------

    static boolean proposalMatchesFilter(ICompletionProposal proposal, IDocument document,
                                         int offset, DocumentEvent event)
    {
        String filter = computeActiveFilter(document, offset, event);
        if (filter.isEmpty())
            return true;
        boolean ok = computeScore(new SmartCodeMatcher(filter), proposal) > 0;
        ContentAssistDebug.logValidate(ok, filter, proposal, offset);
        return ok;
    }

    // ---- Кэш полного списка ---------------------------------------------------

    private void loadFullList(ITextViewer viewer)
    {
        int caret = resolveCaretOffset(viewer, 0);
        ICompletionProposal[] raw = delegate.computeCompletionProposals(viewer, caret);
        if (raw == null || raw.length == 0)
        {
            fullListCache = EMPTY;
            fullListReady = raw != null;
            ContentAssistDebug.log("loadFullList EMPTY rawNull=" + (raw == null) + " caret=" + caret); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        fullListCache = unwrapProposals(raw);
        fullListReady = true;
        ContentAssistDebug.log("loadFullList count=" + fullListCache.length + " caret=" + caret); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int resolveCaretOffset(ITextViewer viewer, int fallback)
    {
        if (viewer != null)
        {
            try
            {
                if (viewer.getTextWidget() != null)
                {
                    int caret = viewer.getTextWidget().getCaretOffset();
                    if (caret >= 0)
                        return caret;
                }
                Point sel = viewer.getSelectedRange();
                if (sel != null && sel.x >= 0)
                    return sel.x + Math.max(0, sel.y);
            }
            catch (Exception ignored) {}
        }
        return fallback;
    }

    // ---- Фильтрация + сортировка + обёртка ------------------------------------

    private ICompletionProposal[] filterAndSort(ICompletionProposal[] raw, String filter)
    {
        if (raw == null || raw.length == 0)
            return EMPTY;

        SmartCodeMatcher matcher = new SmartCodeMatcher(filter);
        List<ICompletionProposal> filtered = new ArrayList<>(raw.length);
        int[] scores = new int[raw.length];

        for (ICompletionProposal p : raw)
        {
            int score = computeScore(matcher, p);
            if (score > 0)
            {
                scores[filtered.size()] = score;
                filtered.add(p);
            }
        }

        if (filtered.isEmpty())
            return EMPTY;

        Integer[] idx = new Integer[filtered.size()];
        for (int i = 0; i < idx.length; i++)
            idx[i] = i;

        final int[] s = Arrays.copyOf(scores, filtered.size());
        Arrays.sort(idx, (a, b) -> {
            if (s[a] != s[b])
                return Integer.compare(s[b], s[a]);
            return compareDisplayStrings(filtered.get(a), filtered.get(b));
        });

        ICompletionProposal[] result = new ICompletionProposal[idx.length];
        for (int i = 0; i < idx.length; i++)
            result[i] = new SmartCompletionProposal(filtered.get(idx[i]), matcher);
        return result;
    }

    static int computeScore(SmartCodeMatcher matcher, ICompletionProposal proposal)
    {
        String display = displayString(unwrapProposal(proposal));
        if (display == null || display.isEmpty())
            return 0;
        int name = matcher.computeNamePremium(display);
        if (name <= 0)
            return 0;
        return name * NAME_WEIGHT + matcher.computeParamPremium(display) * PARAM_WEIGHT;
    }

    static ICompletionProposal unwrapProposal(ICompletionProposal proposal)
    {
        while (proposal instanceof SmartCompletionProposal)
            proposal = ((SmartCompletionProposal) proposal).getDelegate();
        return proposal;
    }

    private static ICompletionProposal[] unwrapProposals(ICompletionProposal[] raw)
    {
        ICompletionProposal[] result = new ICompletionProposal[raw.length];
        for (int i = 0; i < raw.length; i++)
            result[i] = unwrapProposal(raw[i]);
        return result;
    }

    public static int compareProposals(SmartCodeMatcher matcher,
                                       ICompletionProposal p1,
                                       ICompletionProposal p2)
    {
        int s1 = computeScore(matcher, p1);
        int s2 = computeScore(matcher, p2);
        if (s1 != s2)
            return Integer.compare(s2, s1);
        return compareDisplayStrings(p1, p2);
    }

    private static int compareDisplayStrings(ICompletionProposal p1, ICompletionProposal p2)
    {
        String d1 = displayString(p1);
        String d2 = displayString(p2);
        if (d1 == null) return d2 == null ? 0 : 1;
        if (d2 == null) return -1;
        return d1.compareToIgnoreCase(d2);
    }

    static String displayString(ICompletionProposal p)
    {
        return (p == null) ? null : p.getDisplayString();
    }

    static String computeIdentifierFilter(IDocument doc, int offset)
    {
        try
        {
            if (doc == null || offset < 0)
                return "";
            int start = offset;
            while (start > 0 && isFilterChar(doc.getChar(start - 1)))
                start--;
            if (start < offset)
                return doc.get(start, offset - start);
            int end = offset;
            while (end < doc.getLength() && isFilterChar(doc.getChar(end)))
                end++;
            return (end > offset) ? doc.get(offset, end - offset) : "";
        }
        catch (Exception e)
        {
            return "";
        }
    }

    static String computeActiveFilter(IDocument doc, int offset, DocumentEvent event)
    {
        int caret = offset;
        if (event != null)
        {
            try
            {
                String text = event.getText();
                int eventEnd = event.getOffset() + (text != null ? text.length() : 0);
                caret = Math.max(caret, eventEnd);
            }
            catch (Exception ignored) {}
        }
        String filter = computeIdentifierFilter(doc, caret);
        if (!filter.isEmpty())
            return filter;
        return computeIdentifierFilter(doc, offset);
    }

    static boolean isFilterChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    @Override
    public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset)
    {
        return delegate.computeContextInformation(viewer, offset);
    }

    @Override
    public char[] getCompletionProposalAutoActivationCharacters()
    {
        if (activationChars != null)
            return activationChars.toCharArray();
        return delegate.getCompletionProposalAutoActivationCharacters();
    }

    @Override
    public char[] getContextInformationAutoActivationCharacters()
    {
        return delegate.getContextInformationAutoActivationCharacters();
    }

    @Override
    public String getErrorMessage()
    {
        return delegate.getErrorMessage();
    }

    @Override
    public IContextInformationValidator getContextInformationValidator()
    {
        return delegate.getContextInformationValidator();
    }
}
