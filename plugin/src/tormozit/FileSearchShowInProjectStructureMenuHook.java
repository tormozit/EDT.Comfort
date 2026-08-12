package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.search.ui.IQueryListener;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResultPage;
import org.eclipse.search.ui.ISearchResultViewPart;
import org.eclipse.search.ui.NewSearchUI;
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
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Добавляет «Показать в структуре проекта» в подменю «Комфорт» панели результатов
 * поиска по файлам ({@code org.eclipse.search.ui.views.SearchView}, страница
 * {@code FileSearchPage}) — выделяет файл или папку в
 * {@code org.eclipse.ui.navigator.ProjectExplorer}
 * через {@link NavigatorShowInProjectStructureHandler}, как и одноимённая команда
 * в навигаторе EDT ({@link NavigatorShowInExplorerMenuHook}) и в панелях Git
 * ({@link GitChangedFileMenuHook}).
 */
public final class FileSearchShowInProjectStructureMenuHook implements IStartup
{
    private static final String SEARCH_VIEW_ID = "org.eclipse.search.ui.views.SearchView"; //$NON-NLS-1$
    private static final String PAGE_CLASS_MARKER = "FileSearchPage"; //$NON-NLS-1$
    private static final String HOOK_MARKER = "tormozit.fileSearchShowInProjectStructureHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Показать в структуре проекта"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
            "Выделить и прокрутить к файлу в дереве «Структура проекта»" //$NON-NLS-1$
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
            wb.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });

            NewSearchUI.addQueryListener(new IQueryListener()
            {
                @Override public void queryAdded(ISearchQuery query) { onQueryEvent(); }
                @Override public void queryRemoved(ISearchQuery query) {}
                @Override public void queryStarting(ISearchQuery query) {}
                @Override public void queryFinished(ISearchQuery query) { onQueryEvent(); }
            });
        });
    }

    private static void onQueryEvent()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            IViewPart view = findSearchViewPart();
            if (view != null)
                schedulePatch(view, 0);
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
                if (isSearchView(view))
                    schedulePatch(view, 0);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r) {}
            @Override public void partDeactivated(IWorkbenchPartReference r) {}
            @Override public void partHidden(IWorkbenchPartReference r) {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isSearchView(part))
                    schedulePatch((IViewPart) part, 0);
            }
        });
    }

    private static boolean isSearchView(Object part)
    {
        if (!(part instanceof IViewPart vp))
            return false;
        return vp.getViewSite() != null && SEARCH_VIEW_ID.equals(vp.getViewSite().getId());
    }

    private static IViewPart findSearchViewPart()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return null;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
        {
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                continue;
            IViewReference ref = page.findViewReference(SEARCH_VIEW_ID);
            if (ref != null)
            {
                IViewPart view = ref.getView(false);
                if (view != null)
                    return view;
            }
        }
        return null;
    }

    private static void schedulePatch(IViewPart view, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        int delay = attempt == 0 ? 0 : 200;
        display.timerExec(delay, () -> {
            if (!tryHook(view) && attempt < 30)
                schedulePatch(view, attempt + 1);
        });
    }

    private static boolean tryHook(IViewPart view)
    {
        try
        {
            if (!(view instanceof ISearchResultViewPart))
                return false;
            ISearchResultPage activePage = ((ISearchResultViewPart) view).getActivePage();
            if (activePage == null)
                return false;
            if (!activePage.getClass().getName().contains(PAGE_CLASS_MARKER))
                return true;

            Object viewerObj = Global.getField(activePage, "fViewer"); //$NON-NLS-1$
            if (!(viewerObj instanceof TreeViewer treeViewer))
                return false;

            Tree tree = treeViewer.getTree();
            if (tree == null || tree.isDisposed())
                return false;
            if (Boolean.TRUE.equals(tree.getData(HOOK_MARKER)))
                return true;

            Menu menu = tree.getMenu();
            if (menu == null)
                return false;

            MenuAdapter listener = new MenuAdapter()
            {
                @Override
                public void menuShown(MenuEvent e)
                {
                    hookComfortSubmenu(menu, treeViewer);
                }
            };
            menu.addMenuListener(listener);
            tree.setData(HOOK_MARKER, Boolean.TRUE);
            tree.addDisposeListener(ev -> {
                if (!menu.isDisposed())
                    menu.removeMenuListener(listener);
            });
            return true;
        }
        catch (Exception ex)
        {
            Global.log("FileSearchShowInProjectStructureMenuHook tryHook error: " + ex); //$NON-NLS-1$
            return false;
        }
    }

    private static void hookComfortSubmenu(Menu contextMenu, TreeViewer viewer)
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
                // Дерево поиска по файлам: проект/папка/файл (и строка совпадения → файл).
                // Как в навигаторе — любой IResource, не только IFile.
                if (NavigatorResourceResolver.resolveFirst(structured) == null)
                    return;

                MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH, ITEM_TEXT);
                item.setToolTipText(ITEM_TOOLTIP);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        ISelection current = viewer.getSelection();
                        if (current instanceof IStructuredSelection currentStructured)
                            NavigatorShowInProjectStructureHandler.showInProjectStructure(currentStructured);
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
}
