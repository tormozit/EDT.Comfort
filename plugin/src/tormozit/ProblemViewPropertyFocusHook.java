package tormozit;

import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.IOpenListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.OpenEvent;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.form.model.ExtInfo;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Двойной клик / Open по ошибке «Битая ссылка на картинку» в панели «Ошибки конфигурации»:
 * штатный {@code BmMarkerUiHandler} (через {@code OpenAndLinkWithEditorHelper}) открывает
 * редактор формы, а этот хук дополнительно показывает панель «Свойства» и активирует поле
 * «Картинка» — как двойной клик по вхождению в результатах поиска по конфигурации
 * ({@link ConfigSearchResultsHook.PropertyFieldFocus}).
 * <p>
 * Штатный путь панели — {@code IOpenListener} ({@code OpenAndLinkWithEditorHelper.open}), а не
 * {@code IDoubleClickListener}; поэтому слушаем open + doubleClick + SWT {@code MouseDoubleClick}
 * на дереве (запасной путь, как в {@link NavigatorAttributePropertiesHook}).
 */
public final class ProblemViewPropertyFocusHook implements IStartup
{
    private static final String LOG_TOPIC = "problem-view-propfocus"; //$NON-NLS-1$
    private static final String VIEWER_MARKER = "tormozit.problemViewPropertyFocusHook"; //$NON-NLS-1$
    private static final String TREE_MARKER = "tormozit.problemViewPropertyFocusTree"; //$NON-NLS-1$

    private static volatile boolean displayFilterInstalled;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            ensureDisplayFilter();
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
        });
    }

    private static void ensureDisplayFilter()
    {
        if (displayFilterInstalled)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDoubleClick, ProblemViewPropertyFocusHook::onDisplayDoubleClick);
        displayFilterInstalled = true;
        Global.tempLog(LOG_TOPIC, "displayFilter installed"); //$NON-NLS-1$
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isProblemView(view))
                    tryHook(view);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryHookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void tryHookFromRef(IWorkbenchPartReference ref)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        if (isProblemView(part))
            tryHook((IViewPart)part);
    }

    private static boolean isProblemView(Object part)
    {
        return part instanceof IViewPart view
            && ProblemViewMarkers.PROBLEM_VIEW_ID.equals(view.getViewSite().getId());
    }

    private static void tryHook(IViewPart view)
    {
        TreeViewer viewer = resolveTreeViewer(view);
        if (viewer == null)
        {
            Global.tempLog(LOG_TOPIC, "tryHook: viewer=null view=" + view.getClass().getName()); //$NON-NLS-1$
            return;
        }
        if (!Boolean.TRUE.equals(viewer.getData(VIEWER_MARKER)))
        {
            viewer.addDoubleClickListener(new FocusDoubleClickListener());
            viewer.addOpenListener(new FocusOpenListener());
            viewer.setData(VIEWER_MARKER, Boolean.TRUE);
            Global.tempLog(LOG_TOPIC, "hooked viewer listeners=" + viewer.getClass().getSimpleName()); //$NON-NLS-1$
        }
        Tree tree = viewer.getTree();
        if (tree != null && !tree.isDisposed() && !Boolean.TRUE.equals(tree.getData(TREE_MARKER)))
        {
            Listener treeListener = event -> {
                if (event.button != 1)
                    return;
                Global.tempLog(LOG_TOPIC, "tree MouseDoubleClick"); //$NON-NLS-1$
                handleOpenFromViewer(viewer, "treeMouseDblClick"); //$NON-NLS-1$
            };
            tree.addListener(SWT.MouseDoubleClick, treeListener);
            tree.setData(TREE_MARKER, Boolean.TRUE);
            Global.tempLog(LOG_TOPIC, "hooked tree MouseDoubleClick"); //$NON-NLS-1$
        }
    }

    private static TreeViewer resolveTreeViewer(IViewPart view)
    {
        Object adapted = view.getAdapter(TreeViewer.class);
        return adapted instanceof TreeViewer treeViewer ? treeViewer : null;
    }

    private static void onDisplayDoubleClick(Event e)
    {
        if (e.button != 1 || !(e.widget instanceof Tree tree) || tree.isDisposed())
            return;
        IViewPart problemView = findProblemViewForTree(tree);
        if (problemView == null)
            return;
        TreeViewer viewer = resolveTreeViewer(problemView);
        if (viewer == null || viewer.getTree() != tree)
            return;
        Global.tempLog(LOG_TOPIC, "displayFilter MouseDoubleClick"); //$NON-NLS-1$
        handleOpenFromViewer(viewer, "displayFilter"); //$NON-NLS-1$
    }

    private static IViewPart findProblemViewForTree(Tree tree)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (!isProblemView(view))
                    continue;
                TreeViewer viewer = resolveTreeViewer(view);
                if (viewer != null && viewer.getTree() == tree)
                    return view;
            }
        }
        return null;
    }

    private static final class FocusDoubleClickListener implements IDoubleClickListener
    {
        @Override
        public void doubleClick(DoubleClickEvent event)
        {
            Global.tempLog(LOG_TOPIC, "IDoubleClickListener fired"); //$NON-NLS-1$
            if (event == null || !(event.getSelection() instanceof IStructuredSelection structured))
                return;
            handleOpenFromSelection(structured, "doubleClick"); //$NON-NLS-1$
        }
    }

    private static final class FocusOpenListener implements IOpenListener
    {
        @Override
        public void open(OpenEvent event)
        {
            Global.tempLog(LOG_TOPIC, "IOpenListener fired"); //$NON-NLS-1$
            if (event == null || !(event.getSelection() instanceof IStructuredSelection structured))
                return;
            handleOpenFromSelection(structured, "open"); //$NON-NLS-1$
        }
    }

    private static void handleOpenFromViewer(TreeViewer viewer, String source)
    {
        if (viewer == null)
            return;
        if (!(viewer.getSelection() instanceof IStructuredSelection structured) || structured.isEmpty())
        {
            Global.tempLog(LOG_TOPIC, source + " empty selection"); //$NON-NLS-1$
            return;
        }
        handleOpenFromSelection(structured, source);
    }

    private static void handleOpenFromSelection(IStructuredSelection structured, String source)
    {
        Object element = structured.getFirstElement();
        Marker marker = resolveMarker(element);
        Global.tempLog(LOG_TOPIC, source + " element=" + (element != null ? element.getClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " marker=" + (marker != null) //$NON-NLS-1$
            + " checkId=" + (marker != null ? marker.getCheckId() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " ours=" + (marker != null && isBrokenFormPictureMarker(marker))); //$NON-NLS-1$
        if (marker == null || !isBrokenFormPictureMarker(marker))
            return;

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
            ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage() : null;
        if (page == null)
            return;

        Function<EObject, EObject> identity = obj -> obj;
        EObject object = marker.provideObject(identity);
        if (object == null)
        {
            Global.tempLog(LOG_TOPIC, source + " provideObject=null"); //$NON-NLS-1$
            return;
        }

        EStructuralFeature feature = resolveFeature(object, marker);
        EObject paletteMember = resolvePaletteMember(object);
        Global.tempLog(LOG_TOPIC, source + " object=" + object.eClass().getName() //$NON-NLS-1$
            + " member=" + (paletteMember != null ? paletteMember.eClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " feature=" + (feature != null ? feature.getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
            + " featureId=" + marker.getFeatureId()); //$NON-NLS-1$
        if (paletteMember == null || feature == null)
            return;

        try
        {
            page.showView(IPageLayout.ID_PROP_SHEET);
        }
        catch (Exception e)
        {
            Global.tempLog(LOG_TOPIC, "showView: " + e); //$NON-NLS-1$
        }
        // После штатного openEditor палитра обновляется асинхронно — scheduleExact ждёт до ~6с.
        ConfigSearchResultsHook.PropertyFieldFocus.scheduleExact(page, paletteMember, feature);
    }

    /**
     * В маркере {@link Marker#getCheckId()} — короткий UID проекта ({@code SU47}), а не
     * {@link BrokenFormPictureCheck#CHECK_ID}. Резолв через
     * {@link ICheckRepository#getUidForShortUid(String, IProject)}.
     */
    private static boolean isBrokenFormPictureMarker(Marker marker)
    {
        String id = marker.getCheckId();
        if (id == null || id.isBlank())
            return false;
        if (BrokenFormPictureCheck.CHECK_ID.equals(id))
            return true;
        ICheckRepository repository = Global.getOsgiService(ICheckRepository.class);
        IProject project = marker.getProject();
        if (repository == null || project == null)
            return false;
        CheckUid uid = repository.getUidForShortUid(id, project);
        return uid != null && BrokenFormPictureCheck.CHECK_ID.equals(uid.getCheckId());
    }

    private static Marker resolveMarker(Object element)
    {
        if (element instanceof Marker marker)
            return marker;
        Object marker = Global.invoke(element, "getMarker"); //$NON-NLS-1$
        return marker instanceof Marker m ? m : null;
    }

    private static EStructuralFeature resolveFeature(EObject object, Marker marker)
    {
        int featureId = marker.getFeatureId();
        if (featureId >= 0)
        {
            EStructuralFeature feature = object.eClass().getEStructuralFeature(featureId);
            if (feature != null)
                return feature;
        }
        Object extra = marker.getExtraInfo() != null
            ? marker.getExtraInfo().get(StandardExtraInfo.MODEL_FEATURE_ID)
            : null;
        if (extra instanceof String text && !text.isBlank())
        {
            try
            {
                return object.eClass().getEStructuralFeature(Integer.parseInt(text));
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
        return null;
    }

    /**
     * Объект, который показывает палитра «Свойства» после штатного выделения в редакторе формы.
     * Для {@link ExtInfo} (картинка подменю и т.п.) — родитель-{@link FormItem}; для команды
     * формы — сама команда.
     */
    private static EObject resolvePaletteMember(EObject object)
    {
        if (object == null)
            return null;
        if (isPaletteMember(object))
            return object;
        if (object instanceof ExtInfo)
        {
            EObject container = object.eContainer();
            if (isPaletteMember(container))
                return container;
        }
        for (EObject cur = object; cur != null; cur = cur.eContainer())
            if (isPaletteMember(cur))
                return cur;
        return object;
    }

    private static boolean isPaletteMember(EObject obj)
    {
        return obj instanceof BasicFeature || obj instanceof FormItem || obj instanceof FormAttribute
            || obj instanceof FormCommand || obj instanceof FormParameter;
    }
}
