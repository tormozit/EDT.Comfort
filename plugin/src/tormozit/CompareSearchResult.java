package tormozit;

import java.util.List;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.ISearchResultListener;
import org.eclipse.ui.IEditorPart;

public class CompareSearchResult implements ISearchResult
{
    private CompareSearchQuery query;
    private final List<CompareSearchMatch> matches;
    private final IEditorPart editorPart;
    private String queryText;

    public CompareSearchResult(List<CompareSearchMatch> matches, IEditorPart editorPart)
    {
        this.matches = matches;
        this.editorPart = editorPart;
    }

    public void setQuery(CompareSearchQuery query)
    {
        this.query = query;
    }

    public void setQueryText(String queryText)
    {
        this.queryText = queryText;
    }

    public String getQueryText()
    {
        return queryText;
    }

    public List<CompareSearchMatch> getMatches()
    {
        return matches;
    }

    public IEditorPart getEditorPart()
    {
        return editorPart;
    }

    /** Единый формат подписей глобального поиска — {@code "<строка>" в сравнении <представление> - <N> совпадений}. */
    @Override
    public String getLabel()
    {
        int count = matches != null ? matches.size() : 0;
        String q = queryText != null ? queryText.trim() : "";
        String comparisonTitle = editorPart != null ? editorPart.getTitle() : null;
        // Заголовок редактора сравнения сам начинается с "Сравнение/объединение (...)" — не дублируем
        // это слово после нашего "в сравнении", оставляем только скобочную часть (см. репорт: было
        // "в сравнении Сравнение/объединение (...)"; предыдущая попытка через regex.replaceFirst с
        // (?i) не сработала — (?i) без флага UNICODE_CASE не сворачивает регистр кириллицы).
        if (comparisonTitle != null)
        {
            int paren = comparisonTitle.indexOf('(');
            if (paren >= 0)
                comparisonTitle = comparisonTitle.substring(paren);
        }
        if (comparisonTitle == null || comparisonTitle.isEmpty())
            comparisonTitle = "конфигураций";
        String quoted = !q.isEmpty() ? "'" + q + "'" : ""; // одинарные кавычки — как в штатных поисках //$NON-NLS-1$ //$NON-NLS-2$
        return quoted + " в сравнении " + comparisonTitle + " - " + count + " совпадений";
    }

    @Override
    public String getTooltip()
    {
        return getLabel();
    }

    @Override
    public ImageDescriptor getImageDescriptor()
    {
        return null;
    }

    @Override
    public ISearchQuery getQuery()
    {
        return query;
    }

    @Override
    public void addListener(ISearchResultListener l)
    {
    }

    @Override
    public void removeListener(ISearchResultListener l)
    {
    }
}
