package tormozit;

import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.navigator.ICommonFilterDescriptor;
import org.eclipse.ui.navigator.INavigatorContentService;
import org.eclipse.ui.navigator.INavigatorFilterService;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.search.core.IModelObjectTreeSearchEngine;

/**
 * Встраивание умного matcher в штатный {@code NavigatorSearchFilter}: подмена только
 * {@code searchEngine}. Фильтрация, SearchJob, {@code applyFilterNonBlockingUi} и
 * {@code expandTreeViewerStepByStep} остаются нативными — ввод не блокируется.
 */
public final class NavigatorNativeSearchBridge
{
    private static final String NATIVE_ENGINE_KEY = "tormozit.nativeSearchEngine"; //$NON-NLS-1$
    private static final String COMFORT_ENGINE_KEY = "tormozit.comfortSearchEngine"; //$NON-NLS-1$
    private static final String NATIVE_FILTER_ID =
            "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSearchFilter"; //$NON-NLS-1$

    private NavigatorNativeSearchBridge() {}

    public static boolean install(IViewPart navigator, CommonViewer viewer, Tree tree)
    {
        if (navigator == null || viewer == null || tree == null || tree.isDisposed())
            return false;
        restoreStoredNativeFilters(viewer, tree);

        Object navFilter = resolveNavigatorSearchFilter(navigator);
        if (navFilter == null)
        {
            NavigatorFilterDebug.log("nativeBridge SKIP NavigatorSearchFilter not found"); //$NON-NLS-1$
            return false;
        }

        IModelObjectTreeSearchEngine nativeDelegate = resolveNativeDelegate(navFilter, tree);
        if (tree.getData(COMFORT_ENGINE_KEY) != null)
        {
            Object current = Global.getField(navFilter, "searchEngine"); //$NON-NLS-1$
            if (current instanceof ComfortNavigatorSearchEngine)
                return true;
        }

        if (nativeDelegate == null)
        {
            NavigatorFilterDebug.log("nativeBridge SKIP searchEngine=null"); //$NON-NLS-1$
            return false;
        }

        Object v8ProjectManager = Global.getField(navFilter, "v8ProjectManager"); //$NON-NLS-1$
        IV8ProjectManager projectManager = v8ProjectManager instanceof IV8ProjectManager
                ? (IV8ProjectManager) v8ProjectManager : null;
        Object bmModelManager = Global.getField(navFilter, "modelManager"); //$NON-NLS-1$
        IBmModelManager modelManager = bmModelManager instanceof IBmModelManager
                ? (IBmModelManager) bmModelManager : Global.getOsgiService(IBmModelManager.class);

        ComfortNavigatorSearchEngine comfortEngine =
                new ComfortNavigatorSearchEngine(nativeDelegate, projectManager, modelManager);
        Global.setField(navFilter, "searchEngine", comfortEngine); //$NON-NLS-1$
        tree.setData(NATIVE_ENGINE_KEY, nativeDelegate);
        tree.setData(COMFORT_ENGINE_KEY, comfortEngine);
        ensureNativeFilterOnViewer(viewer, navFilter);
        NavigatorFilterDebug.log("nativeBridge installed comfortEngine on NavigatorSearchFilter"); //$NON-NLS-1$
        return true;
    }

    public static void restoreStoredNativeFilters(CommonViewer viewer, Tree tree)
    {
        if (viewer == null || tree == null)
            return;
        Object stored = tree.getData("tormozit.storedNavFilters"); //$NON-NLS-1$
        if (!(stored instanceof ViewerFilter[]))
            return;
        for (ViewerFilter filter : (ViewerFilter[]) stored)
        {
            if (filter != null)
                viewer.addFilter(filter);
        }
        tree.setData("tormozit.storedNavFilters", null); //$NON-NLS-1$
        NavigatorFilterDebug.log("nativeBridge restored stored NavigatorSearchFilter(s)"); //$NON-NLS-1$
    }

    private static void ensureNativeFilterOnViewer(CommonViewer viewer, Object navFilter)
    {
        if (viewer == null || !(navFilter instanceof ViewerFilter filter))
            return;
        for (ViewerFilter existing : viewer.getFilters())
        {
            if (existing != null && existing.getClass().getName().contains("NavigatorSearchFilter")) //$NON-NLS-1$
                return;
        }
        viewer.addFilter(filter);
    }

    private static Object resolveNavigatorSearchFilter(IViewPart navigator)
    {
        if (!(navigator instanceof CommonNavigator commonNavigator))
            return null;
        INavigatorContentService contentService = commonNavigator.getNavigatorContentService();
        if (contentService == null)
            return null;
        INavigatorFilterService filterService = contentService.getFilterService();
        if (filterService == null)
            return null;
        ICommonFilterDescriptor[] descriptors = filterService.getVisibleFilterDescriptors();
        if (descriptors == null)
            return null;
        for (ICommonFilterDescriptor descriptor : descriptors)
        {
            if (descriptor == null || !NATIVE_FILTER_ID.equals(descriptor.getId()))
                continue;
            ViewerFilter filter = filterService.getViewerFilter(descriptor);
            return filter;
        }
        return null;
    }

    static IModelObjectTreeSearchEngine unwrapNativeEngine(Tree tree)
    {
        if (tree == null)
            return null;
        Object stored = tree.getData(NATIVE_ENGINE_KEY);
        return stored instanceof IModelObjectTreeSearchEngine engine ? engine : null;
    }

    private static IModelObjectTreeSearchEngine resolveNativeDelegate(Object navFilter, Tree tree)
    {
        Object stored = tree != null ? tree.getData(NATIVE_ENGINE_KEY) : null;
        if (stored instanceof IModelObjectTreeSearchEngine engine)
            return engine;

        Object fromFilter = Global.getField(navFilter, "searchEngine"); //$NON-NLS-1$
        if (fromFilter instanceof ComfortNavigatorSearchEngine)
            return null;
        if (fromFilter instanceof IModelObjectTreeSearchEngine engine)
            return engine;
        return null;
    }
}
