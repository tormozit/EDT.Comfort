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

    @Override
    public String getLabel()
    {
        int count = matches != null ? matches.size() : 0;
        String q = queryText != null ? queryText.trim() : "";
        if (!q.isEmpty())
            return "Результаты поиска по дереву сравнения «" + q + "» — " + count;
        return "Результаты поиска по дереву сравнения — " + count;
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
