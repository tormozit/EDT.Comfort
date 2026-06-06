package tormozit;

import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalSorter;

/**
 * Сортировщик, который вызывается CompletionProposalPopup при каждом
 * изменении фильтра (ввод/удаление символа). Читает текущий фильтр
 * из SmartFilterTracker и сортирует по премии SmartCodeMatcher.
 */
public class SmartCodeProposalSorter implements ICompletionProposalSorter
{
    private static final ThreadLocal<SmartCodeMatcher> matcherCache = new ThreadLocal<>();
    private static final ThreadLocal<String> lastFilter = new ThreadLocal<>();

    @Override
    public int compare(ICompletionProposal p1, ICompletionProposal p2)
    {
        String filter = SmartFilterTracker.getCurrentFilter();
        if (filter.isEmpty())
            return compareDisplay(p1, p2);

        // Кешируем matcher на один UI-цикл — не создаём при каждом compare()
        SmartCodeMatcher matcher = matcherCache.get();
        if (matcher == null || !filter.equals(lastFilter.get()))
        {
            matcher = new SmartCodeMatcher(filter);
            matcherCache.set(matcher);
            lastFilter.set(filter);
        }

        return SmartContentAssistProcessor.compareProposals(matcher, p1, p2);
    }

    private int compareDisplay(ICompletionProposal p1, ICompletionProposal p2)
    {
        String d1 = p1 == null ? null : p1.getDisplayString();
        String d2 = p2 == null ? null : p2.getDisplayString();
        if (d1 == null) return d2 == null ? 0 : 1;
        if (d2 == null) return -1;
        return d1.compareToIgnoreCase(d2);
    }
}