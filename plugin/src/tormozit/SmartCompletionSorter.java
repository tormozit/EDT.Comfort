package tormozit;

import java.util.List;

import org.eclipse.jface.text.contentassist.ICompletionProposal;

/**
 * Утилитный класс для сортировки списков предложений автодополнения.
 * Используется для тестов и прямых вызовов вне {@link SmartContentAssistProcessor}.
 *
 * <p>Фактическая сортировка при работе редактора — через {@link SmartCodeProposalSorter}
 * ({@code ContentAssistant.setSorter}).
 */
public final class SmartCompletionSorter
{
    private SmartCompletionSorter() {}

    public static void sortProposals(List<ICompletionProposal> proposals, String filterText)
    {
        if (proposals == null || proposals.size() < 2)
            return;
        SmartCodeMatcher matcher = new SmartCodeMatcher(filterText);
        if (matcher.isEmpty)
            return;
        proposals.sort((p1, p2) -> SmartContentAssistProcessor.compareProposals(matcher, p1, p2));
    }
}
