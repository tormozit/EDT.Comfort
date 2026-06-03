package tormozit;

import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalSorter;

/** Не пересортировывает список — порядок задаёт {@link SmartContentAssistProcessor}. */
public final class PreserveOrderProposalSorter implements ICompletionProposalSorter
{
    @Override
    public int compare(ICompletionProposal p1, ICompletionProposal p2)
    {
        return 0;
    }
}
