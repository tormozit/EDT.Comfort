package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonViewer;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.google.inject.Injector;

/**
 * Пункт «Приложение активного проекта» в подменю «Комфорт» контекстного меню панели
 * «Информационные базы». Для выбранной базы: если в панели «Приложения» активного проекта
 * уже есть строка с этой базой — выделяет её и раскрывает панель; иначе открывает штатный
 * мастер создания приложения ({@code DeployConfigurationWizard}) с уже подставленными
 * проектом и базой — тот же путь, что и родная команда «Создать приложение».
 */
public final class InfobaseActiveProjectApplicationMenuHook implements IStartup
{
    private static final String INFOBASES_VIEW_ID =
            "com._1c.g5.v8.dt.platform.services.ui.infobases_view"; //$NON-NLS-1$
    private static final String INFOBASES_VIEW_CLASS =
            "com._1c.g5.v8.dt.internal.platform.services.ui.infobases.InfobasesView"; //$NON-NLS-1$

    private static final String APPLICATIONS_VIEW_ID =
            "com.e1c.g5.dt.applications.ui.view"; //$NON-NLS-1$

    private static final String PLATFORM_SERVICES_UI_BUNDLE =
            "com._1c.g5.v8.dt.platform.services.ui"; //$NON-NLS-1$
    private static final String PLATFORM_SERVICES_UI_PLUGIN_CLASS =
            "com._1c.g5.v8.dt.platform.services.ui.PlatformServicesUiPlugin"; //$NON-NLS-1$
    private static final String DEPLOY_CONFIGURATION_FLOW_CLASS =
            "com._1c.g5.v8.dt.internal.platform.services.ui.infobases.actions.DeployConfigurationFlow"; //$NON-NLS-1$

    private static final String HOOK_MARKER = "tormozit.infobaseActiveProjectApplicationHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Приложение активного проекта"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
            "Найти в панели «Приложения» строку с этой базой для активного проекта," //$NON-NLS-1$
            + " либо открыть мастер создания приложения" //$NON-NLS-1$
            + Global.pluginSignForTooltip();

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
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
                if (isInfobasesView(view))
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
        if (isInfobasesView(part))
            tryHook((IViewPart) part);
    }

    private static boolean isInfobasesView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        IViewPart vp = (IViewPart) part;
        String id = vp.getViewSite().getId();
        if (INFOBASES_VIEW_ID.equals(id))
            return true;
        return vp.getClass().getName().equals(INFOBASES_VIEW_CLASS);
    }

    private static void tryHook(IViewPart infobasesView)
    {
        CommonViewer viewer = getCommonViewer(infobasesView);
        if (viewer == null)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (Boolean.TRUE.equals(tree.getData(HOOK_MARKER)))
            return;

        Menu menu = tree.getMenu();
        if (menu == null)
            return;

        MenuAdapter listener = new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                hookComfortSubmenu(menu, infobasesView, viewer);
            }
        };
        menu.addMenuListener(listener);
        tree.setData(HOOK_MARKER, Boolean.TRUE);
        tree.addDisposeListener(ev -> {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    private static void hookComfortSubmenu(Menu contextMenu, IViewPart infobasesView, CommonViewer viewer)
    {
        Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(contextMenu, contextMenu.getShell());
        if (comfortSub == null || comfortSub.isDisposed())
            return;
        if (Boolean.TRUE.equals(comfortSub.getData(HOOK_MARKER)))
            return;

        MenuAdapter subListener = new MenuAdapter()
        {
            private final List<MenuItem> added = new ArrayList<>(1);

            @Override
            public void menuShown(MenuEvent e)
            {
                ISelection selection = viewer.getSelection();
                if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
                    return;
                InfobaseReference infobase = resolveInfobase(structured.getFirstElement());
                if (infobase == null)
                    return;

                MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH, ITEM_TEXT);
                item.setToolTipText(ITEM_TOOLTIP);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        activateOrCreateApplication(infobasesView, infobase);
                    }
                });
                added.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(added);
                added.clear();
                comfortSub.getDisplay().asyncExec(() -> {
                    for (MenuItem mi : snapshot)
                        if (!mi.isDisposed())
                            mi.dispose();
                });
            }
        };

        comfortSub.addMenuListener(subListener);
        comfortSub.setData(HOOK_MARKER, Boolean.TRUE);
        comfortSub.addDisposeListener(ev -> {
            if (!comfortSub.isDisposed())
                comfortSub.removeMenuListener(subListener);
        });
    }

    private static InfobaseReference resolveInfobase(Object element)
    {
        if (element instanceof InfobaseReference ib)
            return ib;
        if (element == null)
            return null;
        Object adapted = Platform.getAdapterManager().getAdapter(element, InfobaseReference.class);
        return adapted instanceof InfobaseReference ? (InfobaseReference) adapted : null;
    }

    // -----------------------------------------------------------------------
    // Действие
    // -----------------------------------------------------------------------

    private static void activateOrCreateApplication(IViewPart infobasesView, InfobaseReference infobase)
    {
        IWorkbenchPage page = infobasesView.getSite().getPage();
        if (page == null)
            return;

        IProject project = ActiveProjectTracker.resolveContextProject(page);
        if (project == null)
            project = Global.getActiveProject(page, true);
        if (project == null)
            return;

        IViewPart appsView;
        try
        {
            appsView = page.showView(APPLICATIONS_VIEW_ID);
        }
        catch (PartInitException e)
        {
            Global.log("InfobaseActiveProjectApplication: showView: " + e); //$NON-NLS-1$
            return;
        }
        if (appsView == null)
            return;

        Object currentProject = Global.invoke(appsView, "getCurrentProject"); //$NON-NLS-1$
        if (!project.equals(currentProject))
            Global.invoke(appsView, "updateViewUsingProject", project); //$NON-NLS-1$

        TreeItem matched = findApplicationRow(appsView, infobase);
        if (matched != null)
        {
            selectAndReveal(appsView, matched);
            return;
        }

        openDeployWizard(page.getWorkbenchWindow().getShell(), project, infobase);
    }

    private static TreeItem findApplicationRow(IViewPart appsView, InfobaseReference infobase)
    {
        ColumnViewer viewer = ApplicationsViewHook.findViewer(appsView);
        if (viewer == null)
            return null;
        Control control = viewer.getControl();
        if (!(control instanceof Tree tree) || tree.isDisposed())
            return null;

        UUID targetUuid = infobase.getUuid();
        for (TreeItem item : tree.getItems())
        {
            InfobaseReference candidate = ApplicationsViewHook.getInfobase(item.getData());
            if (candidate != null && targetUuid != null && targetUuid.equals(candidate.getUuid()))
                return item;
        }
        return null;
    }

    private static void selectAndReveal(IViewPart appsView, TreeItem item)
    {
        ColumnViewer viewer = ApplicationsViewHook.findViewer(appsView);
        if (viewer == null || item.isDisposed())
            return;
        viewer.setSelection(new StructuredSelection(item.getData()), true);
        Control control = viewer.getControl();
        if (control != null && !control.isDisposed())
            control.setFocus();
    }

    // -----------------------------------------------------------------------
    // Мастер создания приложения (родной DeployConfigurationFlow)
    // -----------------------------------------------------------------------

    private static void openDeployWizard(Shell shell, IProject project, InfobaseReference infobase)
    {
        Object flow = resolveDeployConfigurationFlow();
        if (flow == null)
        {
            Global.log("InfobaseActiveProjectApplication: DeployConfigurationFlow недоступен"); //$NON-NLS-1$
            return;
        }
        Object statusObj = Global.invoke(flow, "deployProject", shell, project, infobase); //$NON-NLS-1$
        if (statusObj instanceof IStatus status && status.getSeverity() == IStatus.ERROR)
            ToastNotification.show("Комфорт", status.getMessage(), 6000); //$NON-NLS-1$
    }

    /** {@code DeployConfigurationFlow} — internal-класс бандла platform-services-ui, берём через его Guice-инжектор. */
    private static Object resolveDeployConfigurationFlow()
    {
        try
        {
            Bundle bundle = Platform.getBundle(PLATFORM_SERVICES_UI_BUNDLE);
            if (bundle == null)
                return null;
            Class<?> pluginClass = bundle.loadClass(PLATFORM_SERVICES_UI_PLUGIN_CLASS);
            Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
            Object injectorObj = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
            if (!(injectorObj instanceof Injector injector))
                return null;
            Class<?> flowClass = bundle.loadClass(DEPLOY_CONFIGURATION_FLOW_CLASS);
            return injector.getInstance(flowClass);
        }
        catch (Exception e)
        {
            Global.log("InfobaseActiveProjectApplication: resolveDeployConfigurationFlow: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static CommonViewer getCommonViewer(IViewPart view)
    {
        Object v = Global.invoke(view, "getCommonViewer"); //$NON-NLS-1$
        return v instanceof CommonViewer ? (CommonViewer) v : null;
    }
}
