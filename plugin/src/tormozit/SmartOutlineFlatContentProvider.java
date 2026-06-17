package tormozit;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;

/**
 * При активном фильтре Quick Outline отдаёт плоский список совпадающих листьев
 * без группирующих веток; без фильтра — делегирует исходному провайдеру.
 */
public final class SmartOutlineFlatContentProvider implements ITreeContentProvider
{

    private ITreeContentProvider delegate;
    private final SmartOutlineFilter filter;
    private final ILabelProvider labelProvider;
    private java.util.List<Object> indexedLeaves;
    private final java.util.Map<Object, String> localFilterTextByElement = new java.util.IdentityHashMap<>();
    private BslOutlineEventsSupport.SubscriptionFlatLabels subscriptionFlatLabels;
    private org.eclipse.jface.viewers.IBaseLabelProvider flatLabelSource;
    private ILabelProvider flatFallbackLabelProvider;
    private TreeViewer outlineViewer;
    private Object outlineContextHost;
    private boolean filterTextCacheReady;
    private boolean filteredResultValid;
    private String cachedFilterPattern = ""; //$NON-NLS-1$
    private Object[] cachedFilteredElements = new Object[0];
    public SmartOutlineFlatContentProvider(ITreeContentProvider delegate, SmartOutlineFilter filter,
                                           ILabelProvider labelProvider)
    {

        this.delegate = delegate;
        this.filter = filter;
        this.labelProvider = labelProvider;
    }

    ITreeContentProvider getDelegate()
    {

        return delegate;
    }

    void setDelegate(ITreeContentProvider newDelegate)
    {

        if (newDelegate != null)
        {

            delegate = newDelegate;
            invalidateCaches();
        }

    }

    void bindFlatLabelContext(BslOutlineEventsSupport.SubscriptionFlatLabels subscriptionFlatLabels,
            org.eclipse.jface.viewers.IBaseLabelProvider flatLabelSource, ILabelProvider flatFallbackLabelProvider,
            TreeViewer outlineViewer, Object outlineContextHost)
    {

        this.subscriptionFlatLabels = subscriptionFlatLabels;
        this.flatLabelSource = flatLabelSource;
        this.flatFallbackLabelProvider = flatFallbackLabelProvider;
        this.outlineViewer = outlineViewer;
        this.outlineContextHost = outlineContextHost;
        invalidateCaches();
    }

    java.util.List<Object> getIndexedLeaves()
    {

        return indexedLeaves;
    }

    void invalidateFilterResultCache()
    {

        filteredResultValid = false;
        cachedFilterPattern = ""; //$NON-NLS-1$
        cachedFilteredElements = new Object[0];
    }

    private void invalidateCaches()
    {

        indexedLeaves = null;
        localFilterTextByElement.clear();
        filterTextCacheReady = false;
        invalidateFilterResultCache();
    }

    /** Один проход по дереву при открытии — без getText на каждый лист. */
    void rebuildLeafIndex(Object inputElement)
    {

        java.util.List<Object> leaves = new java.util.ArrayList<>();
        if (inputElement != null)
        {

            for (Object root : delegate.getElements(inputElement))
                collectAllLeaves(root, leaves);
        }

        indexedLeaves = leaves;
        localFilterTextByElement.clear();
        filterTextCacheReady = false;
        if (subscriptionFlatLabels != null)
            subscriptionFlatLabels.invalidateLeafMapping();
        invalidateFilterResultCache();
    }

    String getFilterText(Object element)
    {

        if (element == null)
            return ""; //$NON-NLS-1$
        if (subscriptionFlatLabels != null)
        {

            String fromFlat = subscriptionFlatLabels.filterTextByElement.get(element);
            if (fromFlat != null)
                return fromFlat;
        }

        String local = localFilterTextByElement.get(element);
        if (local != null)
            return local;
        String resolved = resolveFilterTextLazy(element);
        localFilterTextByElement.put(element, resolved);
        return resolved;
    }

    private void ensureFilterTextCache()
    {

        if (subscriptionFlatLabels != null && outlineViewer != null
                && !subscriptionFlatLabels.leafMappingComplete)
        {

            BslOutlineEventsSupport.enrichSubscriptionFlatLabels(subscriptionFlatLabels, outlineViewer,
                    outlineContextHost, indexedLeaves, flatLabelSource);
            subscriptionFlatLabels.clearFilterTextCaches();
            localFilterTextByElement.clear();
            filterTextCacheReady = false;
            invalidateFilterResultCache();
        }

        if (filterTextCacheReady || indexedLeaves == null)
            return;
        if (subscriptionFlatLabels != null && flatLabelSource != null)
        {

            BslOutlineEventsSupport.populateOutlineFilterTextCache(indexedLeaves, flatLabelSource,
                    subscriptionFlatLabels, flatFallbackLabelProvider);
        }

        else if (labelProvider != null)
        {

            for (Object leaf : indexedLeaves)
            {

                if (leaf == null)
                    continue;
                String text = labelProvider.getText(leaf);
                localFilterTextByElement.put(leaf, text != null ? text : ""); //$NON-NLS-1$
            }

        }

        filterTextCacheReady = true;
    }

    private String resolveFilterTextLazy(Object element)
    {

        if (subscriptionFlatLabels != null && flatLabelSource != null)
        {

            String flat = BslOutlineEventsSupport.formatFlatSubscriptionHandlerLabel(element, flatLabelSource,
                    subscriptionFlatLabels);
            if (flat != null)
            {

                subscriptionFlatLabels.filterTextByElement.put(element, flat);
                return flat;
            }

        }

        ILabelProvider fallback = flatFallbackLabelProvider != null ? flatFallbackLabelProvider : labelProvider;
        if (fallback == null)
            return ""; //$NON-NLS-1$
        String text = fallback.getText(element);
        return text != null ? text : ""; //$NON-NLS-1$
    }

    @Override
    public Object[] getElements(Object inputElement)
    {

        if (!filter.isFlattenWhenFiltered() || !filter.isFiltering())
            return delegate.getElements(inputElement);
        if (indexedLeaves == null)
            rebuildLeafIndex(inputElement);
        String pattern = filter.getPattern();
        if (filteredResultValid && pattern.equals(cachedFilterPattern))
            return cachedFilteredElements;
        ensureFilterTextCache();
        java.util.List<Object> flat = new java.util.ArrayList<>();
        for (Object element : indexedLeaves)
        {

            String text = getFilterText(element);
            if (filter.matchesText(text))
            {

                flat.add(element);
                filter.recordMatchPremiums(element, text);
            }

        }

        cachedFilterPattern = pattern;
        cachedFilteredElements = flat.toArray();
        filteredResultValid = true;
        return cachedFilteredElements;
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

    private void collectAllLeaves(Object element, java.util.List<Object> out)
    {

        if (element == null)
            return;
        if (!delegate.hasChildren(element))
        {

            out.add(element);
            return;
        }

        for (Object child : delegate.getChildren(element))
            collectAllLeaves(child, out);
    }

}

