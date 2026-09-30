package tormozit;

import java.util.function.Function;

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

import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.metadata.mdclass.BasicFeature;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;

/**
 * Двойной клик / Open по проблеме в панели «Проблемы конфигурации» EDT — после штатного открытия
 * редактора хук доводит переход до конкретного места:
 * <ul>
 * <li><b>право роли на объект</b> ({@code ObjectRight}/{@code ObjectRights} из
 * {@code com._1c.g5.v8.dt.rights.model}) — открывает редактор роли на странице «Права», выделяет
 * строку объекта и прокручивает колонку права в видимую область
 * ({@link ConfigSearchResultsHook#revealRoleRightsRow}, issue #463);</li>
 * <li><b>любая другая проблема на свойстве объекта панели «Свойства»</b> (элемент/реквизит/команда
 * формы, реквизит МД; объект проблемы может быть вложен в него — путь к данным, {@code ExtInfo}
 * картинки подменю) — показывает панель и активирует поле свойства
 * ({@link ConfigSearchResultsHook.PropertyFieldFocus#schedule}).</li>
 * </ul>
 * <p>
 * Включение: Параметры → Комфорт → «Улучшать списки»
 * ({@link ComfortSettings#PREF_REPLACE_LIST_FILTERS}), как и прочие доработки панели
 * ({@link ProblemViewHook}); флажок читается в момент открытия.
 * <p>
 * Штатный путь панели — {@code IOpenListener} ({@code OpenAndLinkWithEditorHelper.open}), а не
 * {@code IDoubleClickListener}; поэтому слушаем open + doubleClick + SWT {@code MouseDoubleClick}
 * на дереве (запасной путь, как в {@link NavigatorAttributePropertiesHook}).
 */
public final class ProblemViewOpenTargetHook implements IStartup
{
    private static final String VIEWER_MARKER = "tormozit.problemViewOpenTargetHook"; //$NON-NLS-1$
    private static final String TREE_MARKER = "tormozit.problemViewOpenTargetTree"; //$NON-NLS-1$

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
        display.addFilter(SWT.MouseDoubleClick, ProblemViewOpenTargetHook::onDisplayDoubleClick);
        displayFilterInstalled = true;
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
            return;
        }
        if (!Boolean.TRUE.equals(viewer.getData(VIEWER_MARKER)))
        {
            viewer.addDoubleClickListener(new FocusDoubleClickListener());
            viewer.addOpenListener(new FocusOpenListener());
            viewer.setData(VIEWER_MARKER, Boolean.TRUE);
        }
        Tree tree = viewer.getTree();
        if (tree != null && !tree.isDisposed() && !Boolean.TRUE.equals(tree.getData(TREE_MARKER)))
        {
            Listener treeListener = event -> {
                if (event.button != 1)
                    return;
                handleOpenFromViewer(viewer);
            };
            tree.addListener(SWT.MouseDoubleClick, treeListener);
            tree.setData(TREE_MARKER, Boolean.TRUE);
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
        handleOpenFromViewer(viewer);
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
            if (event == null || !(event.getSelection() instanceof IStructuredSelection structured))
                return;
            handleOpenFromSelection(structured);
        }
    }

    private static final class FocusOpenListener implements IOpenListener
    {
        @Override
        public void open(OpenEvent event)
        {
            if (event == null || !(event.getSelection() instanceof IStructuredSelection structured))
                return;
            handleOpenFromSelection(structured);
        }
    }

    private static void handleOpenFromViewer(TreeViewer viewer)
    {
        if (viewer == null)
            return;
        if (!(viewer.getSelection() instanceof IStructuredSelection structured) || structured.isEmpty())
        {
            return;
        }
        handleOpenFromSelection(structured);
    }

    private static void handleOpenFromSelection(IStructuredSelection structured)
    {
        // Доработка поведения панели — как и остальные, подчиняется «Улучшать списки»
        if (!ComfortSettings.isReplaceListFiltersEnabled())
            return;
        Object element = structured.getFirstElement();
        Marker marker = resolveMarker(element);
        if (marker == null)
            return;

        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
            ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage() : null;
        if (page == null)
            return;

        Function<EObject, EObject> identity = obj -> obj;
        EObject markerObject = marker.provideObject(identity);
        if (markerObject == null)
            return;
        if (ConfigSearchResultsHook.revealRoleRightsRow(page, markerObject,
            roleRightName(markerObject, marker), marker.getMessage()))
            return;
        focusPropertyField(marker, markerObject, page);
    }

    /**
     * Любая проблема на свойстве объекта, который показывает панель «Свойства» (элемент/реквизит/
     * команда формы, реквизит МД, форма): показывает панель и активирует поле — тем же путём, что
     * и двойной клик по вхождению в результатах поиска по конфигурации
     * ({@link ConfigSearchResultsHook.PropertyFieldFocus#schedule}). Объект проблемы может быть
     * вложенным в объект панели (путь к данным «Список.DefaultPicture» внутри таблицы) — поле
     * панели тогда первый уровень цепочки признаков.
     */
    private static void focusPropertyField(Marker marker, EObject object, IWorkbenchPage page)
    {
        EStructuralFeature feature = resolveFeature(object, marker);
        if (!ConfigSearchResultsHook.PropertyFieldFocus.hasPanelOwner(object))
            return;
        // Сам объект панели без признака — поля нет, штатного выделения в редакторе достаточно
        if (feature == null && isPaletteMember(object))
            return;
        try
        {
            page.showView(IPageLayout.ID_PROP_SHEET);
        }
        catch (Exception e)
        {
        }
        // После штатного openEditor палитра обновляется асинхронно — schedule ждёт её сам.
        ConfigSearchResultsHook.PropertyFieldFocus.schedule(page, object, feature);
    }

    /** Имя права по объекту проблемы ({@code ObjectRight.getRight()}) либо из текста сообщения. */
    private static String roleRightName(EObject markerObject, Marker marker)
    {
        for (EObject cur = markerObject; cur != null; cur = cur.eContainer())
        {
            String className = cur.eClass() != null ? cur.eClass().getName() : null;
            if ("ObjectRights".equals(className)) //$NON-NLS-1$
                break;
            if ("ObjectRight".equals(className)) //$NON-NLS-1$
            {
                Object right = Global.invoke(cur, "getRight"); //$NON-NLS-1$
                String nameRu = asString(Global.invoke(right, "getNameRu")); //$NON-NLS-1$
                if (nameRu != null && !nameRu.isBlank())
                    return nameRu;
                String name = asString(Global.invoke(right, "getName")); //$NON-NLS-1$
                if (name != null && !name.isBlank())
                    return name;
                break;
            }
        }
        String message = marker.getMessage();
        if (message != null)
        {
            int open = message.indexOf('"');
            int close = open >= 0 ? message.indexOf('"', open + 1) : -1;
            if (close > open + 1)
                return message.substring(open + 1, close);
        }
        return null;
    }

    private static String asString(Object value)
    {
        return value instanceof String s ? s : null;
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

    private static boolean isPaletteMember(EObject obj)
    {
        return obj instanceof BasicFeature || obj instanceof FormItem || obj instanceof FormAttribute
            || obj instanceof FormCommand || obj instanceof FormParameter;
    }
}
