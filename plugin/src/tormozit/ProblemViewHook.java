package tormozit;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;

import com._1c.g5.v8.dt.validation.marker.Marker;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Панель проблем конфигурации ({@code com._1c.g5.v8.dt.ui.problemView}), issue 401.
 *
 * <ul>
 * <li><b>Заголовок.</b> «Ошибки конфигурации» → «Проблемы конфигурации»: панель
 * показывает не только ошибки, но и предупреждения, а «ошибка конфигурации» —
 * это отдельный вид проблемы (он же отдельный флажок в «Настройках отбора», см.
 * {@link ProblemFiltersDialogHook}).</li>
 * <li><b>Двойной щелчок в колонке «Код проверки»</b> открывает настройку этой
 * проверки на странице «Проверки» параметров проекта — вместо перехода к самой
 * проблеме, который остаётся на всех остальных колонках. Штатное открытие
 * редактора для этой колонки подавляется, иначе поверх настроек открывался бы
 * ещё и редактор объекта.</li>
 * </ul>
 *
 * <p>Отдельный файл, а не вложенный класс: точка входа из {@code plugin.xml}.
 */
public final class ProblemViewHook implements IStartup
{
    /** Страница «Проверки» в свойствах проекта ({@code ValidationPreferencePage.PROPERTIES_PAGE_ID}). */
    private static final String CHECKS_PAGE_ID = "com.e1c.g5.v8.dt.checks.properties"; //$NON-NLS-1$
    /** Ключ {@code ValidationPreferencePage.DATA_PROPERTY_CHECK_ID}: короткий код проверки. */
    private static final String CHECK_ID_DATA_KEY = "ValidationPreferencePage.checkId"; //$NON-NLS-1$
    private static final String CODE_COLUMN_TITLE = "Код проверки"; //$NON-NLS-1$
    private static final String VIEW_TITLE = "Проблемы конфигурации"; //$NON-NLS-1$

    private static volatile boolean installed;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(ProblemViewHook::install);
    }

    private static void install()
    {
        if (installed)
            return;
        installed = true;

        IWorkbench workbench = PlatformUI.getWorkbench();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
            }
        });

        Display.getDefault().addFilter(SWT.MouseDoubleClick, ProblemViewHook::onDoubleClick);
        Debug.log("install: installed"); //$NON-NLS-1$
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
                applyTitle(ref.getView(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                applyTitle(partOf(ref));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                applyTitle(partOf(ref));
            }
        });
    }

    private static IWorkbenchPart partOf(IWorkbenchPartReference ref)
    {
        return ref != null ? ref.getPart(false) : null;
    }

    /**
     * Имя панели живёт в модели e4, а не в самом {@code IViewPart}: штатный
     * {@code setPartName} у чужой панели недоступен, зато {@code MPart.setLabel}
     * меняет и заголовок вкладки, и подпись в списке представлений.
     */
    private static void applyTitle(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart view) || !isProblemView(view))
            return;
        Object mpart = view.getSite().getService(MPart.class);
        if (mpart instanceof MPart model && !VIEW_TITLE.equals(model.getLabel()))
        {
            model.setLabel(VIEW_TITLE);
            Debug.log("applyTitle: renamed"); //$NON-NLS-1$
        }
    }

    private static boolean isProblemView(IViewPart view)
    {
        return view != null && ProblemViewMarkers.PROBLEM_VIEW_ID.equals(view.getViewSite().getId());
    }

    private static void onDoubleClick(Event e)
    {
        if (e.button != 1 || !(e.widget instanceof Tree tree) || tree.isDisposed())
            return;
        IViewPart view = findProblemViewForTree(tree);
        if (view == null)
            return;

        Point point = new Point(e.x, e.y);
        TreeItem item = tree.getItem(point);
        if (item == null || !isCodeColumn(tree, item, point))
            return;

        Marker marker = markerOf(item.getData());
        if (marker == null)
            return;
        // Штатный обработчик открыл бы редактор объекта поверх настроек проверки.
        e.doit = false;
        e.type = SWT.None;
        openCheckSettings(tree.getShell(), marker);
    }

    private static boolean isCodeColumn(Tree tree, TreeItem item, Point point)
    {
        for (int i = 0; i < tree.getColumnCount(); i++)
        {
            if (!item.getBounds(i).contains(point))
                continue;
            TreeColumn column = tree.getColumn(i);
            return column != null && CODE_COLUMN_TITLE.equals(column.getText());
        }
        return false;
    }

    /**
     * {@code Marker.getCheckId()} — короткий код проверки в пределах проекта
     * ({@code SU47}), и страница проверок ждёт в {@code applyData} именно его:
     * она сама превращает его в полный {@link CheckUid} через
     * {@link ICheckRepository#getUidForShortUid}.
     */
    private static void openCheckSettings(Shell shell, Marker marker)
    {
        String shortUid = marker.getCheckId();
        IProject project = marker.getProject();
        if (shortUid == null || shortUid.isBlank() || project == null)
        {
            Debug.log("openCheckSettings: no check id or project"); //$NON-NLS-1$
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put(CHECK_ID_DATA_KEY, shortUid);
        Debug.log("openCheckSettings: " + shortUid); //$NON-NLS-1$
        PreferencesUtil.createPropertyDialogOn(shell, project, CHECKS_PAGE_ID, null, data).open();
    }

    private static Marker markerOf(Object element)
    {
        if (element instanceof Marker marker)
            return marker;
        Object marker = Global.invoke(element, "getMarker"); //$NON-NLS-1$
        return marker instanceof Marker m ? m : null;
    }

    private static IViewPart findProblemViewForTree(Tree tree)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (view == null || !isProblemView(view))
                    continue;
                Object adapted = view.getAdapter(TreeViewer.class);
                if (adapted instanceof TreeViewer viewer && viewer.getTree() == tree)
                    return view;
            }
        }
        return null;
    }

    private static final class Debug
    {
        private static final String TAG = "ProblemView"; //$NON-NLS-1$

        private Debug() {}

        static void log(String msg)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, msg);
        }
    }
}
