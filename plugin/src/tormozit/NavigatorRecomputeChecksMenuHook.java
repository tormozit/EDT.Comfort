package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
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
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.IDerivedDataUpdate;
import com._1c.g5.v8.derived.context.IContextCollectingSession;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * Добавляет «Проверить» в подменю «Комфорт» навигатора EDT — точечно пересчитывает проверки
 * для одного выбранного объекта.
 */
public final class NavigatorRecomputeChecksMenuHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.navigatorRecomputeChecksHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Проверить"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
            "Пересчитать все проверки по объекту" //$NON-NLS-1$
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
                if (isNavigatorView(view))
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
        if (isNavigatorView(part))
            tryHook((IViewPart) part);
    }

    private static boolean isNavigatorView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return Global.NAVIGATOR_VIEW_ID.equals(id)
                || part.getClass().getName().contains("internal.navigator.ui.Navigator"); //$NON-NLS-1$
    }

    private static void tryHook(IViewPart navigator)
    {
        CommonViewer viewer = getCommonViewer(navigator);
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
                hookComfortSubmenu(menu, viewer);
            }
        };
        menu.addMenuListener(listener);
        tree.setData(HOOK_MARKER, Boolean.TRUE);
        tree.addDisposeListener(ev -> {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    private static void hookComfortSubmenu(Menu contextMenu, CommonViewer viewer)
    {
        MenuItem anchor = ComfortSubmenuHelper.findAnchorAfterEditGroup(contextMenu);
        Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(
            contextMenu, contextMenu.getShell(), anchor);
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
                if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
                    return;
                if (NavigatorElementModels.resolveEObject(structured.getFirstElement()) == null)
                    return;

                MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH, ITEM_TEXT);
                item.setToolTipText(ITEM_TOOLTIP);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        Global.tempLog("navigator-recompute-checks", "widgetSelected called"); //$NON-NLS-1$ //$NON-NLS-2$
                        ISelection current = viewer.getSelection();
                        if (!(current instanceof IStructuredSelection currentStructured))
                        {
                            Global.tempLog("navigator-recompute-checks", "STOP: selection not IStructuredSelection"); //$NON-NLS-1$ //$NON-NLS-2$
                            return;
                        }
                        recomputeChecks(currentStructured);
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
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
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

    private static void recomputeChecks(IStructuredSelection selection)
    {
        Global.tempLog("navigator-recompute-checks", "recomputeChecks: start, selection=" + selection); //$NON-NLS-1$ //$NON-NLS-2$
        EObject model = NavigatorElementModels.resolveEObject(selection.getFirstElement());
        if (model instanceof com._1c.g5.v8.dt.metadata.mdclass.BasicForm basicForm)
        {
            com._1c.g5.v8.dt.metadata.mdclass.AbstractForm form = basicForm.getForm();
            if (form != null)
                model = form;
        }
        Global.tempLog("navigator-recompute-checks", "recomputeChecks: model=" + model); //$NON-NLS-1$ //$NON-NLS-2$
        if (!(model instanceof IBmObject bmObject))
        {
            Global.tempLog("navigator-recompute-checks", "STOP: selected model is not an IBmObject"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IResource resource = NavigatorResourceResolver.resolveFirst(selection);
        IProject project = resource != null ? resource.getProject() : null;
        Global.tempLog("navigator-recompute-checks", //$NON-NLS-1$
            "recomputeChecks: resource=" + resource + ", project=" + project); //$NON-NLS-1$ //$NON-NLS-2$
        if (project == null)
        {
            Global.tempLog("navigator-recompute-checks", "STOP: project is null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        try
        {
            resource.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }
        catch (CoreException e)
        {
            Global.tempLogException("navigator-recompute-checks", "refreshLocal failed for " + resource, e); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IDtProject dtProject = Global.getDtProjectFromWorkspaceProject(project);
        Global.tempLog("navigator-recompute-checks", //$NON-NLS-1$
            "recomputeChecks: dtProject=" + dtProject); //$NON-NLS-1$
        if (dtProject == null)
        {
            Global.tempLog("navigator-recompute-checks", "STOP: IDtProject is null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        ICheckRepository checkRepo = Global.getOsgiService(ICheckRepository.class);
        Global.tempLog("navigator-recompute-checks", //$NON-NLS-1$
            "recomputeChecks: checkRepo=" + checkRepo); //$NON-NLS-1$
        if (checkRepo == null)
        {
            Global.tempLog("navigator-recompute-checks", "STOP: ICheckRepository is null"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        Map<IDtProject, Set<CheckUid>> allCheckUids = checkRepo.getCheckUids();
        Set<CheckUid> projectCheckUids = allCheckUids.get(dtProject);
        Global.tempLog("navigator-recompute-checks", //$NON-NLS-1$
            "recomputeChecks: projectCheckUids count=" + (projectCheckUids != null ? projectCheckUids.size() : 0)); //$NON-NLS-1$
        if (projectCheckUids == null || projectCheckUids.isEmpty())
        {
            Global.tempLog("navigator-recompute-checks", "STOP: no check UIDs for project"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        Set<String> checkIds = projectCheckUids.stream()
            .map(CheckUid::getCheckId)
            .collect(Collectors.toSet());
        Global.tempLog("navigator-recompute-checks", //$NON-NLS-1$
            "recomputeChecks: checkIds count=" + checkIds.size()); //$NON-NLS-1$

        IDerivedDataManagerProvider dmProvider = Global.getOsgiService(IDerivedDataManagerProvider.class);
        IDerivedDataManager dm = dmProvider != null ? dmProvider.get(project) : null;

        if (dm != null)
        {
            Global.tempLog("navigator-recompute-checks",
                "recomputeChecks: setting up check context via updateDerivedData");
            dm.updateDerivedData(new IDerivedDataUpdate()
            {
                @Override
                public void update(IContextCollectingSession session, IBmModel model)
                {
                    try
                    {
                        Object ctx = session.getObjectContext(bmObject, "M_CHECKS_SEGMENT");
                        if (ctx == null)
                        {
                            Global.tempLog("navigator-recompute-checks",
                                "context is null after getObjectContext");
                            return;
                        }
                        ctx.getClass().getMethod("setFullRebuild", boolean.class).invoke(ctx, true);
                        ctx.getClass().getMethod("setInactive", boolean.class).invoke(ctx, false);
                        ctx.getClass().getMethod("addCheckIds", Set.class).invoke(ctx, checkIds);
                        ctx.getClass().getMethod("setFullRebuild", boolean.class).invoke(ctx, true);
                        Global.tempLog("navigator-recompute-checks",
                            "context ready, id=" + ctx.getClass().getMethod("getBmObjectId").invoke(ctx));
                    }
                    catch (Exception e)
                    {
                        Global.tempLogException("navigator-recompute-checks",
                            "context setup reflection error", e);
                    }
                }
            }, 0L, "comfort-recompute-checks-context");
            Global.tempLog("navigator-recompute-checks",
                "recomputeChecks: updateDerivedData returned, calling applyForcedUpdates");

            try
            {
                dm.applyForcedUpdates();
                Global.tempLog("navigator-recompute-checks",
                    "recomputeChecks: applyForcedUpdates returned");
            }
            catch (Exception e)
            {
                Global.tempLogException("navigator-recompute-checks",
                    "applyForcedUpdates failed", e);
            }
        }
        else
        {
            Global.tempLog("navigator-recompute-checks",
                "STOP: IDerivedDataManager is null");
        }
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }
}
