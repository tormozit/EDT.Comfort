package tormozit;

import java.util.ArrayList;
import java.util.List;

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

/**
 * Добавляет «Показать в проводнике» в подменю Open With навигатора EDT.
 * Декларативный {@code popup:org.eclipse.ui.OpenWithMenu} не работает — OpenWithMenu
 * заполняется динамически через JFace {@code ContributionItem}.
 */
public final class NavigatorShowInExplorerMenuHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.navigatorShowInExplorerHook"; //$NON-NLS-1$
    private static final String SUB_HOOK_MARKER = "tormozit.navigatorShowInExplorerSubHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Показать в проводнике"; //$NON-NLS-1$

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
                hookOpenWithSubmenu(menu, viewer);
            }
        };
        menu.addMenuListener(listener);
        tree.setData(HOOK_MARKER, Boolean.TRUE);
        tree.addDisposeListener(ev -> {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    private static void hookOpenWithSubmenu(Menu contextMenu, CommonViewer viewer)
    {
        MenuItem openWith = findOpenWithCascade(contextMenu);
        if (openWith == null)
            return;
        Menu subMenu = openWith.getMenu();
        if (subMenu == null || subMenu.isDisposed())
            return;
        if (Boolean.TRUE.equals(subMenu.getData(SUB_HOOK_MARKER)))
            return;

        MenuAdapter subListener = new MenuAdapter()
        {
            private final List<MenuItem> added = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                ISelection selection = viewer.getSelection();
                if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
                    return;
                if (NavigatorResourceResolver.resolveFirst(structured) == null)
                    return;

                added.add(new MenuItem(subMenu, SWT.SEPARATOR));
                MenuItem item = new MenuItem(subMenu, SWT.PUSH);
                item.setText(ITEM_TEXT);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        ISelection current = viewer.getSelection();
                        if (current instanceof IStructuredSelection currentStructured)
                            NavigatorShowInExplorerHandler.showInExplorer(currentStructured, subMenu.getShell());
                    }
                });
                added.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(added);
                added.clear();
                subMenu.getDisplay().asyncExec(() -> {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };

        subMenu.addMenuListener(subListener);
        subMenu.setData(SUB_HOOK_MARKER, Boolean.TRUE);
        subMenu.addDisposeListener(ev -> {
            if (!subMenu.isDisposed())
                subMenu.removeMenuListener(subListener);
        });
    }

    private static MenuItem findOpenWithCascade(Menu menu)
    {
        if (menu == null || menu.isDisposed())
            return null;
        for (MenuItem item : menu.getItems())
        {
            if (item.isDisposed() || (item.getStyle() & SWT.CASCADE) == 0)
                continue;
            if (isOpenWithLabel(item.getText()))
                return item;
        }
        return null;
    }

    private static boolean isOpenWithLabel(String text)
    {
        if (text == null)
            return false;
        String normalized = text.replace("&", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        return normalized.equalsIgnoreCase("Open With") //$NON-NLS-1$
                || normalized.equalsIgnoreCase("Open with"); //$NON-NLS-1$
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }
}
