package tormozit;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.Transfer;
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
import org.eclipse.core.resources.IProject;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * DnD из навигатора и «Комфорт / Добавить в &lt;набор&gt;» в контекстном меню дерева.
 */
public final class NavigatorAddToObjectSetMenuHook implements IStartup
{
    private static final String TREE_MARKER = "tormozit.navigatorAddToObjectSetHook"; //$NON-NLS-1$
    private static final String MENU_MARKER = "tormozit.navigatorAddToObjectSetMenu"; //$NON-NLS-1$
    private static final String ITEM_MARKER = "tormozit.navigatorAddToObjectSetItem"; //$NON-NLS-1$
    private static final String DRAG_MARKER = "tormozit.navigatorObjectSetDrag"; //$NON-NLS-1$
    private static final String COMMAND_ID = "tormozit.navigator.addToObjectSet"; //$NON-NLS-1$
    private static volatile boolean hooksInstalled;

    @Override
    public void earlyStartup()
    {
        scheduleInstall(0);
    }

    private static void scheduleInstall(int attempt)
    {
        Runnable install = () -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
            {
                if (attempt < 50)
                {
                    Display display = Display.getDefault();
                    if (display != null)
                        display.timerExec(200, () -> scheduleInstall(attempt + 1));
                }
                return;
            }
            installHooks();
        };
        Display display = Display.getDefault();
        if (display != null)
            display.asyncExec(install);
        else
            install.run();
    }

    private static void installHooks()
    {
        if (hooksInstalled)
            return;
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        hooksInstalled = true;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookWindow(window);
        wb.addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                if (window != null)
                    hookWindow(window);
            }
            @Override public void windowActivated(IWorkbenchWindow window) {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
            @Override public void windowClosed(IWorkbenchWindow window) {}
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
                    tryHook((IViewPart) view, 0);
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
            tryHook((IViewPart) part, 0);
    }

    private static boolean isNavigatorView(Object part)
    {
        if (!(part instanceof IViewPart viewPart))
            return false;
        String id = viewPart.getViewSite().getId();
        return Global.NAVIGATOR_VIEW_ID.equals(id)
                || part.getClass().getName().contains("internal.navigator.ui.Navigator"); //$NON-NLS-1$
    }

    private static void tryHook(IViewPart navigator, int attempt)
    {
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
        {
            if (attempt < 30)
            {
                Display display = Display.getDefault();
                if (display != null)
                    display.timerExec(150, () -> tryHook(navigator, attempt + 1));
            }
            return;
        }
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        if (!Boolean.TRUE.equals(tree.getData(TREE_MARKER)))
        {
            installDragSource(viewer);
            tree.setData(TREE_MARKER, Boolean.TRUE);
        }
        Menu menu = tree.getMenu();
        if (menu == null || menu.isDisposed())
        {
            if (attempt < 30)
            {
                Display display = Display.getDefault();
                if (display != null)
                    display.timerExec(150, () -> tryHook(navigator, attempt + 1));
            }
            return;
        }
        if (Boolean.TRUE.equals(menu.getData(MENU_MARKER)))
            return;
        menu.setData(MENU_MARKER, Boolean.TRUE);
        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                Menu contextMenu = (Menu) e.widget;
                if (contextMenu == null || contextMenu.isDisposed())
                    return;
                ensureMenuItem(navigator, contextMenu, viewer);
            }
        });
    }

    private static void ensureMenuItem(IViewPart navigator, Menu contextMenu, CommonViewer viewer)
    {
        if (isComfortSubmenu(contextMenu))
            return;
        if (contextMenu == null || contextMenu.isDisposed())
            return;
        MenuItem anchor = ComfortSubmenuHelper.findAnchorAfterEditGroup(contextMenu);
        Menu comfortMenu = ComfortSubmenuHelper.findOrCreateComfortSubmenu(
            contextMenu, contextMenu.getShell(), anchor);
        if (comfortMenu == null)
            return;
        MenuItem item = findOrCreateItem(navigator, comfortMenu);
        ObjectSets.SetDef set = resolveTargetSet(navigator, viewer);
        if (set == null)
        {
            item.setText("Добавить в набор <…>"); //$NON-NLS-1$
            item.setToolTipText("Выберите активный набор в панели «Наборы объектов»" + Global.pluginSignForTooltip()); //$NON-NLS-1$
            item.setEnabled(false);
            return;
        }
        item.setText("Добавить в набор <" + set.name + ">"); //$NON-NLS-1$ //$NON-NLS-2$
        item.setToolTipText("Добавить выбранный объект метаданных в набор «" //$NON-NLS-1$
            + set.name + "»" + Global.pluginSignForTooltip()); //$NON-NLS-1$
        IStructuredSelection selection = viewer.getStructuredSelection();
        boolean enabled = !selection.isEmpty()
            && !ObjectSetsItems.fromNavigatorSelection(selection, set).isEmpty();
        item.setEnabled(enabled);
    }

    private static boolean isComfortSubmenu(Menu menu)
    {
        MenuItem parentItem = menu.getParentItem();
        if (parentItem == null || parentItem.isDisposed())
            return false;
        String text = parentItem.getText();
        if (text == null)
            return false;
        return ComfortSubmenuHelper.SUBMENU_TEXT.equals(text.replace("&", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static MenuItem findOrCreateItem(IViewPart navigator, Menu comfort)
    {
        for (MenuItem item : comfort.getItems())
        {
            if (item.isDisposed())
                continue;
            if (Boolean.TRUE.equals(item.getData(ITEM_MARKER)))
                return item;
        }
        MenuItem item = new MenuItem(comfort, SWT.PUSH);
        item.setData(ITEM_MARKER, Boolean.TRUE);
        item.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                IHandlerService handlerService =
                    navigator.getViewSite().getService(IHandlerService.class);
                if (handlerService == null)
                    return;
                try
                {
                    handlerService.executeCommand(COMMAND_ID, null);
                }
                catch (Exception ex)
                {
                    Global.log("NavigatorAddToObjectSet: " + ex); //$NON-NLS-1$
                }
            }
        });
        return item;
    }

    private static void installDragSource(CommonViewer viewer)
    {
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || Boolean.TRUE.equals(tree.getData(DRAG_MARKER)))
            return;
        DragSource source;
        try
        {
            source = new DragSource(tree, DND.DROP_COPY | DND.DROP_MOVE);
        }
        catch (RuntimeException | SWTError e)
        {
            Global.log("DragSource init error: " + e); //$NON-NLS-1$
            return;
        }
        source.setTransfer(new Transfer[] { LocalSelectionTransfer.getTransfer() });
        source.addDragListener(new DragSourceAdapter()
        {
            @Override
            public void dragStart(DragSourceEvent event)
            {
                IStructuredSelection selection = viewer.getStructuredSelection();
                LocalSelectionTransfer.getTransfer().setSelection(selection);
                event.doit = !selection.isEmpty();
            }

            @Override
            public void dragSetData(DragSourceEvent event)
            {
                LocalSelectionTransfer.getTransfer().setSelection(viewer.getStructuredSelection());
            }
        });
        tree.setData(DRAG_MARKER, Boolean.TRUE);
    }

    private static ObjectSets.SetDef resolveTargetSet(IViewPart navigator, CommonViewer viewer)
    {
        IStructuredSelection selection = viewer.getStructuredSelection();
        String projectName = ObjectSetsItems.projectNameFromNavigatorSelection(selection);
        if (projectName == null || projectName.isBlank())
        {
            IWorkbenchPage page = navigator.getViewSite().getPage();
            IProject project = ActiveProjectTracker.resolveContextProject(page);
            projectName = project != null ? project.getName() : null;
        }
        if (projectName == null || projectName.isBlank())
            return null;
        ObjectSetsAddTargetState.getInstance().ensureForProject(projectName);
        return ObjectSetsAddTargetState.getInstance().getAddTargetSet(projectName);
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer commonViewer ? commonViewer : null;
    }
}
