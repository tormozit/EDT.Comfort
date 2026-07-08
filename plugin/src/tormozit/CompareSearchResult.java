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
        return "\u0420\u0435\u0437\u0443\u043B\u044C\u0442\u0430\u0442\u044B \u043F\u043E\u0438\u0441\u043A\u0430 \u043F\u043E \u0434\u0435\u0440\u0435\u0432\u0443 \u0441\u0440\u0430\u0432\u043D\u0435\u043D\u0438\u044F — " + count;
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
