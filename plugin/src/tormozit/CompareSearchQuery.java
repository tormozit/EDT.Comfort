package tormozit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;

public class CompareSearchQuery implements ISearchQuery
{
    private CompareSearchResult result;

    public CompareSearchQuery()
    {
    }

    public void setSearchResult(CompareSearchResult result)
    {
        this.result = result;
    }

    @Override
    public IStatus run(IProgressMonitor monitor)
    {
        return Status.OK_STATUS;
    }

    @Override
    public String getLabel()
    {
        return result != null ? result.getLabel() : "\u041F\u043E\u0438\u0441\u043A \u043F\u043E \u0434\u0435\u0440\u0435\u0432\u0443 \u0441\u0440\u0430\u0432\u043D\u0435\u043D\u0438\u044F"; //$NON-NLS-1$
    }

    @Override
    public boolean canRerun()
    {
        return false;
    }

    @Override
    public boolean canRunInBackground()
    {
        return true;
    }

    @Override
    public ISearchResult getSearchResult()
    {
        return result;
    }
}
