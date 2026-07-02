package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

/**
 * Добавляет «Открыть в Навигаторе» и «Открыть объект» в подменю «Комфорт»
 * контекстного меню изменённых файлов в EGit-представлениях
 * ({@code StagingView}, {@code RepositoryExplorerView}).
 */
public final class GitChangedFileMenuHook implements IStartup
{
    private static final String ROOT_MARKER = "tormozit.gitChangedFileRootHook"; //$NON-NLS-1$
    private static final String SUB_MARKER  = "tormozit.gitChangedFileSubHook";  //$NON-NLS-1$

    private static final String STAGING_VIEW_ID = "org.eclipse.egit.ui.StagingView"; //$NON-NLS-1$
    private static final String REPOS_VIEW_ID   = "org.eclipse.egit.ui.RepositoryExplorerView"; //$NON-NLS-1$

    private static final String NAV_ITEM_TEXT = "Открыть в Навигаторе"; //$NON-NLS-1$
    private static final String OBJ_ITEM_TEXT = "Открыть объект";       //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.addFilter(SWT.MenuDetect, GitChangedFileMenuHook::handleMenuDetect);
            Global.log("GitChangedFileMenu: MenuDetect filter installed"); //$NON-NLS-1$
            dumpViewIds();
        });
    }

    private static void dumpViewIds()
    {
        try
        {
            IWorkbenchWindow window = activeWindow();
            if (window == null)
                return;
            for (IWorkbenchPage page : window.getPages())
            {
                for (IViewReference ref : page.getViewReferences())
                {
                    IViewPart view = ref.getView(false);
                    if (view == null)
                    {
                        Global.log("GitChangedFileMenu: viewRef id=" + ref.getId() + " (not loaded)"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    else
                    {
                        Global.log("GitChangedFileMenu: view id=" + view.getSite().getId() //$NON-NLS-1$
                            + " class=" + view.getClass().getName()); //$NON-NLS-1$
                    }
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: dumpViewIds error: " + e); //$NON-NLS-1$
        }
    }

    private static void handleMenuDetect(Event event)
    {
        Global.log("GitChangedFileMenu: MenuDetect widget="
            + (event.widget == null ? "null" : event.widget.getClass().getName())); //$NON-NLS-1$ //$NON-NLS-2$

        // Находим Table или Tree — как сам event.widget, так и его предков
        Control target = resolveMenuControl(event);
        if (target == null)
        {
            Global.log("GitChangedFileMenu: no Table/Tree found"); //$NON-NLS-1$
            return;
        }
        Global.log("GitChangedFileMenu: resolved control="
            + target.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$

        IViewPart view = findGitViewForControl(target);
        if (view == null)
        {
            Global.log("GitChangedFileMenu: findGitView returned null"); //$NON-NLS-1$
            return;
        }

        tryAttachMenuListener(target, view, 0);
    }

    /**
     * Возвращает сам event.widget, если это Table или Tree,
     * либо первый Table/Tree среди предков.
     */
    private static Control resolveMenuControl(Event event)
    {
        if (!(event.widget instanceof Control c))
            return null;

        if (c instanceof Table || c instanceof Tree)
            return c;

        Composite parent = c.getParent();
        while (parent != null)
        {
            if (parent instanceof Table || parent instanceof Tree)
                return parent;
            parent = parent.getParent();
        }
        return null;
    }

    private static void tryAttachMenuListener(Control control, IViewPart view, int attempt)
    {
        if (control == null || control.isDisposed())
            return;

        Menu menu = control.getMenu();
        if (menu == null || menu.isDisposed())
        {
            if (attempt < 3)
            {
                final int nextAttempt = attempt + 1;
                control.getDisplay().asyncExec(() ->
                    tryAttachMenuListener(control, view, nextAttempt));
            }
            return;
        }

        if (Boolean.TRUE.equals(menu.getData(ROOT_MARKER)))
            return;

        Global.log("GitChangedFileMenu: attach root listener view="
            + view.getSite().getId() + " attempt=" + attempt); //$NON-NLS-1$ //$NON-NLS-2$

        menu.setData(ROOT_MARKER, Boolean.TRUE);
        menu.addMenuListener(buildRootMenuListener(view));
    }

    // ========================================================================
    // Root menu — создаёт/находит подменю «Комфорт»
    // ========================================================================

    private static MenuAdapter buildRootMenuListener(IViewPart view)
    {
        return new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                Menu contextMenu = (Menu) e.widget;
                Menu comfortSub = ComfortSubmenuHelper.findOrCreateComfortSubmenu(
                    contextMenu, contextMenu.getShell());
                if (comfortSub == null || comfortSub.isDisposed())
                    return;

                if (Boolean.TRUE.equals(comfortSub.getData(SUB_MARKER)))
                    return;

                Global.log("GitChangedFileMenu: attach items listener"); //$NON-NLS-1$
                comfortSub.setData(SUB_MARKER, Boolean.TRUE);
                comfortSub.addMenuListener(buildItemsMenuListener(view));
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
            }
        };
    }

    // ========================================================================
    // Подменю «Комфорт» — добавляет пункты при каждом открытии
    // ========================================================================

    private static MenuAdapter buildItemsMenuListener(IViewPart view)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                ISelection selection = selectionOf(view);
                if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
                    return;

                Object element = structured.getFirstElement();
                Global.log("GitChangedFileMenu: resolve element class="
                    + element.getClass().getName() + " toString=" + element); //$NON-NLS-1$ //$NON-NLS-2$

                EObject eObject = resolveToEObject(element);
                if (eObject == null)
                {
                    Global.log("GitChangedFileMenu: EObject not resolved"); //$NON-NLS-1$
                    return;
                }

                Menu submenu = (Menu) e.widget;
                Shell shell = submenu.getShell();
                EObject captured = eObject;

                MenuItem navItem = new MenuItem(submenu, SWT.PUSH);
                navItem.setText(NAV_ITEM_TEXT);
                navItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        NavigatorReveal.reveal(captured, true);
                    }
                });
                addedItems.add(navItem);

                MenuItem objItem = new MenuItem(submenu, SWT.PUSH);
                objItem.setText(OBJ_ITEM_TEXT);
                objItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        openInEditor(captured, shell);
                    }
                });
                addedItems.add(objItem);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                List<MenuItem> snapshot = new ArrayList<>(addedItems);
                addedItems.clear();
                ((Menu) e.widget).getDisplay().asyncExec(() ->
                {
                    for (MenuItem mi : snapshot)
                    {
                        if (!mi.isDisposed())
                            mi.dispose();
                    }
                });
            }
        };
    }

    // ========================================================================
    // Resolution
    // ========================================================================

    private static EObject resolveToEObject(Object element)
    {
        IFile file = resolveFile(element);
        if (file == null || !file.exists())
            return null;

        return resolveEObject(file);
    }

    private static IFile resolveFile(Object element)
    {
        if (element instanceof IFile file)
            return file;

        if (element instanceof org.eclipse.core.runtime.IAdaptable adaptable)
        {
            IFile adapted = adaptable.getAdapter(IFile.class);
            if (adapted != null)
                return adapted;
        }

        String path = null;

        if (element instanceof String str)
            path = str;

        if (path == null)
        {
            try
            {
                Object p = Global.call(element, "getPath"); //$NON-NLS-1$
                if (p instanceof String s)
                    path = s;
            }
            catch (Exception ignored)
            {
            }
        }

        if (path != null)
        {
            IProject project = resolveProject();
            if (project != null)
            {
                String normalized = path.replace('\\', '/');
                IFile file = project.getFile(normalized);
                if (file.exists())
                    return file;
            }
        }

        return null;
    }

    private static IProject resolveProject()
    {
        IWorkbenchPage page = activePage();
        return page != null ? Global.getActiveProject(page, false) : null;
    }

    private static EObject resolveEObject(IFile file)
    {
        try
        {
            IProject project = file.getProject();
            IV8ProjectManager projectManager =
                (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
            if (projectManager == null)
                return null;
            IV8Project v8Project = projectManager.getProject(project);
            if (v8Project == null)
                return null;
            return GoToDefinition.resolveEObjectViaResourceSet(file, v8Project);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // ========================================================================
    // OpenHelper — открытие объекта в редакторе EDT
    // ========================================================================

    private static void openInEditor(EObject eObject, Shell shell)
    {
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper"); //$NON-NLS-1$
            Object helper = cls.getConstructor().newInstance();
            for (java.lang.reflect.Method m : cls.getMethods())
            {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 1) //$NON-NLS-1$
                    continue;
                if (m.getParameterTypes()[0].isAssignableFrom(eObject.getClass()))
                {
                    m.invoke(helper, eObject);
                    return;
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    // ========================================================================
    // Finding EGit view by widget hierarchy (не полагаясь на activePart)
    // ========================================================================

    private static IViewPart findGitViewForControl(Control control)
    {
        IWorkbenchWindow window = activeWindow();
        if (window == null)
            return null;

        for (IWorkbenchPage page : window.getPages())
        {
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (view == null || !isGitView(view))
                    continue;

                if (controlBelongsToView(control, view))
                    return view;
            }
        }
        return null;
    }

    private static boolean controlBelongsToView(Control control, IViewPart view)
    {
        try
        {
            if (view.getViewSite() == null)
                return false;

            IWorkbenchPart part = view.getViewSite().getPart();
            Object pc = Global.call(view, "getPartControl"); //$NON-NLS-1$
            if (!(pc instanceof Composite viewComposite))
                return false;

            Composite parent = control.getParent();
            while (parent != null)
            {
                if (parent == viewComposite)
                    return true;
                parent = parent.getParent();
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    // ========================================================================
    // Workbench helpers
    // ========================================================================

    private static IWorkbenchWindow activeWindow()
    {
        try
        {
            return PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static IWorkbenchPage activePage()
    {
        IWorkbenchWindow window = activeWindow();
        return window != null ? window.getActivePage() : null;
    }

    private static boolean isGitView(IWorkbenchPart part)
    {
        if (part == null || part.getSite() == null)
            return false;
        String id = part.getSite().getId();
        return STAGING_VIEW_ID.equals(id) || REPOS_VIEW_ID.equals(id);
    }

    private static ISelection selectionOf(IWorkbenchPart view)
    {
        ISelectionProvider provider = view.getSite().getSelectionProvider();
        return provider != null ? provider.getSelection() : null;
    }
}
