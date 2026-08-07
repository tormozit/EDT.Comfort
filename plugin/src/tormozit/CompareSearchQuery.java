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
        return result != null ? result.getLabel() : "Поиск по дереву сравнения"; //$NON-NLS-1$
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
