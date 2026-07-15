package tormozit;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.dt.ui.commands.ShowPropertiesHandler;

/**
 * Двойной клик по реквизиту в навигаторе: штатный {@code OpenHelper} (редактор объекта МД)
 * + активация панели «Свойства» последней ({@link ShowPropertiesHandler}).
 */
public final class NavigatorAttributePropertiesHook implements IStartup
{
    private static final String VIEWER_MARKER = "tormozit.navigatorAttributePropertiesHook"; //$NON-NLS-1$

    private static volatile boolean displayFilterInstalled;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null)
            display.asyncExec(NavigatorAttributePropertiesHook::ensureDisplayFilter);
    }

    /**
     * Вызывается из {@link NavigatorFilterHook} после успешного патча навигатора
     * (тот же момент, когда в журнале есть {@code NavigatorFilter PATCH OK}).
     */
    static void ensureInstalled(IViewPart navigator, CommonViewer viewer)
    {
        if (navigator == null || viewer == null)
            return;
        if (Boolean.TRUE.equals(viewer.getData(VIEWER_MARKER)))
            return;

        viewer.addDoubleClickListener(new AttributeDoubleClickListener(navigator));
        viewer.setData(VIEWER_MARKER, Boolean.TRUE);
        NavigatorAttributePropertiesDebug.trace("ensureInstalled viewer=" + viewer); //$NON-NLS-1$
    }

    private static void ensureDisplayFilter()
    {
        if (displayFilterInstalled)
            return;
        if (!PlatformUI.isWorkbenchRunning())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDoubleClick, NavigatorAttributePropertiesHook::onDisplayDoubleClick);
        displayFilterInstalled = true;
        NavigatorAttributePropertiesDebug.trace("displayFilter installed"); //$NON-NLS-1$
    }

    /** Запасной перехват: dblclick на дереве навигатора до JFace {@code OpenAction}. */
    private static void onDisplayDoubleClick(Event e)
    {
        if (e.button != 1)
            return;
        if (!(e.widget instanceof Tree tree) || tree.isDisposed())
            return;

        IViewPart navigator = navigatorForTree(tree);
        if (navigator == null)
            return;

        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null || viewer.getTree() != tree)
            return;

        Object element = resolveDoubleClickElement(viewer, tree, e);
        NavigatorAttributePropertiesDebug.trace("displayFilter dblclick element=" //$NON-NLS-1$
                + NavigatorAttributePropertiesDebug.describe(element)
                + " attribute=" + isMetadataAttributeNode(element)); //$NON-NLS-1$
        if (!isMetadataAttributeNode(element))
            return;

        scheduleActivateProperties(navigator, tree.getDisplay(), "displayFilter"); //$NON-NLS-1$
    }

    private static IViewPart navigatorForTree(Tree tree)
    {
        IViewPart view = Global.getViewById(Global.NAVIGATOR_VIEW_ID);
        if (!(view instanceof IViewPart navigator))
            return null;
        CommonViewer viewer = getCommonViewer(navigator);
        return viewer != null && viewer.getTree() == tree ? navigator : null;
    }

    private static void scheduleActivateProperties(IViewPart navigator, Display display, String source)
    {
        if (navigator == null || display == null || display.isDisposed())
            return;
        NavigatorAttributePropertiesDebug.trace(source + " -> schedule ShowProperties"); //$NON-NLS-1$
        display.asyncExec(() -> activateProperties(navigator));
    }

    private static void activateProperties(IViewPart navigator)
    {
        if (navigator == null || navigator.getSite() == null)
        {
            NavigatorAttributePropertiesDebug.trace("activateProperties SKIP site=null"); //$NON-NLS-1$
            return;
        }
        NavigatorAttributePropertiesDebug.trace("activateProperties ShowPropertiesHandler.run"); //$NON-NLS-1$
        ShowPropertiesHandler.run(navigator.getViewSite());
    }

    private static Object resolveDoubleClickElement(CommonViewer viewer, Tree tree, Event e)
    {
        if (viewer != null)
        {
            IStructuredSelection selection = viewer.getStructuredSelection();
            if (selection != null && !selection.isEmpty())
                return selection.getFirstElement();
        }
        TreeItem[] items = tree.getSelection();
        if (items != null && items.length > 0)
            return items[0].getData();
        TreeItem item = tree.getItem(new Point(e.x, e.y));
        return item != null ? item.getData() : null;
    }

    /** Реквизит метаданных в навигаторе (обычный, стандартный, общий, ТЧ…). */
    static boolean isMetadataAttributeNode(Object element)
    {
        if (element == null || NavigatorTreeElementLabels.isGroupNode(element))
            return false;

        String simpleName = element.getClass().getSimpleName();
        if (simpleName.contains("Folder")) //$NON-NLS-1$
            return false;
        if (simpleName.contains("AttributeNavigatorAdapter")) //$NON-NLS-1$
            return true;

        EObject eObject = NavigatorElementModels.resolveEObject(element);
        if (eObject == null)
            return false;
        String typeName = eObject.eClass().getName();
        return typeName != null
                && (typeName.equals("Attribute") || typeName.endsWith("Attribute")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer commonViewer ? commonViewer : null;
    }

    private static final class AttributeDoubleClickListener implements IDoubleClickListener
    {
        private final IViewPart navigator;

        AttributeDoubleClickListener(IViewPart navigator)
        {
            this.navigator = navigator;
        }

        @Override
        public void doubleClick(DoubleClickEvent event)
        {
            if (event == null || event.getSelection() == null)
                return;
            Object element = event.getSelection() instanceof IStructuredSelection structured
                    && !structured.isEmpty() ? structured.getFirstElement() : null;
            NavigatorAttributePropertiesDebug.trace("viewerDblclick element=" //$NON-NLS-1$
                    + NavigatorAttributePropertiesDebug.describe(element)
                    + " attribute=" + isMetadataAttributeNode(element)); //$NON-NLS-1$
            if (!isMetadataAttributeNode(element))
                return;

            Display display = Display.getCurrent();
            if (display == null)
                display = Display.getDefault();
            scheduleActivateProperties(navigator, display, "viewerDblclick"); //$NON-NLS-1$
        }
    }

    /** Обёртка над {@link Global.log} — сохраняет интерфейс вызова в хуках. */
    private static final class NavigatorAttributePropertiesDebug
    {
        private static final String TAG = "NavigatorAttributeProperties"; //$NON-NLS-1$

        private NavigatorAttributePropertiesDebug() {}

        static void trace(String msg)
        {
            if (msg == null)
                return;
            Global.log(TAG, msg);
        }

        static String describe(Object element)
        {
            if (element == null)
                return "<null>"; //$NON-NLS-1$
            return element.getClass().getName();
        }
    }
}
