package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

/**
 * При активном фильтре Quick Outline отдаёт плоский список совпадающих листьев
 * без группирующих веток; без фильтра — делегирует исходному провайдеру.
 */
public final class SmartOutlineFlatContentProvider implements ITreeContentProvider
{
    private final ITreeContentProvider delegate;
    private final SmartOutlineFilter filter;
    private final ILabelProvider labelProvider;

    public SmartOutlineFlatContentProvider(ITreeContentProvider delegate, SmartOutlineFilter filter,
                                           ILabelProvider labelProvider)
    {
        this.delegate = delegate;
        this.filter = filter;
        this.labelProvider = labelProvider;
    }

    @Override
    public Object[] getElements(Object inputElement)
    {
        if (!filter.isFlattenWhenFiltered() || !filter.isFiltering())
            return delegate.getElements(inputElement);

        List<Object> flat = new ArrayList<>();
        if (inputElement != null)
        {
            for (Object root : delegate.getElements(inputElement))
                collectMatchingLeaves(root, flat);
        }
        return flat.toArray();
    }

    @Override
    public Object[] getChildren(Object parentElement)
    {
        if (filter.isFlattenWhenFiltered() && filter.isFiltering())
            return new Object[0];
        return delegate.getChildren(parentElement);
    }

    @Override
    public Object getParent(Object element)
    {
        if (filter.isFlattenWhenFiltered() && filter.isFiltering())
            return null;
        return delegate.getParent(element);
    }

    @Override
    public boolean hasChildren(Object element)
    {
        if (filter.isFlattenWhenFiltered() && filter.isFiltering())
            return false;
        return delegate.hasChildren(element);
    }

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
    {
        delegate.inputChanged(viewer, oldInput, newInput);
    }

    @Override
    public void dispose()
    {
        delegate.dispose();
    }

    private void collectMatchingLeaves(Object element, List<Object> out)
    {
        if (element == null)
            return;
        if (!delegate.hasChildren(element))
        {
            String text = labelProvider != null ? labelProvider.getText(element) : ""; //$NON-NLS-1$
            if (filter.matchesText(text))
                out.add(element);
            return;
        }
        for (Object child : delegate.getChildren(element))
            collectMatchingLeaves(child, out);
    }
}
