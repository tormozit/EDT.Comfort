package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.viewers.ITreeContentProvider;
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

/**
 * Добавляет «Проверить» в подменю «Комфорт» навигатора EDT — точечно пересчитывает проверки
 * для одного выбранного объекта.
 */
public final class NavigatorRecomputeChecksMenuHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.navigatorRecomputeChecksHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Проверить"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
            "Пересчитать все проверки по объекту с вложенными"; //$NON-NLS-1$
    static final String UNREACHABLE_TOOLTIP =
            "Найти в папках выбранных узлов папки объектов, не связанные с конфигурацией, и показать в панели Поиск";

    @Override
    public void earlyStartup()
    {
        // Те же пункты — во внешние меню объекта (например, меню имени в заголовке редактора МД)
        ComfortSubmenuHelper.addExternalMenuFiller(context -> hookComfortSubmenu(context.menu(), null));
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
        Menu comfortSub = ComfortSubmenuHelper.findOrCreateNavigatorComfortSubmenu(
            contextMenu, contextMenu.getShell());
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
                ISelection selection = ComfortSubmenuHelper.menuSelection(comfortSub, viewer);
                if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
                    return;
                if (NavigatorElementModels.resolveEObject(structured.getFirstElement()) == null
                    && !(structured.getFirstElement() instanceof IProject)
                    && !(viewer != null && NavigatorTreeElementLabels.isGroupNode(structured.getFirstElement())))
                    return;

                MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH, ITEM_TEXT);
                ComfortSubmenuHelper.setMenuItemTooltip(item, ITEM_TOOLTIP);
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        ISelection current = ComfortSubmenuHelper.menuSelection(comfortSub, viewer);
                        if (!(current instanceof IStructuredSelection currentStructured))
                            return;
                        recomputeChecks(currentStructured);
                    }
                });
                added.add(item);
                if (viewer != null)
                {
                    MenuItem find = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        "Найти битые ссылки метаданных");
                    ComfortSubmenuHelper.setMenuItemTooltip(find,
                        "Найти битые ссылки в выбранных объектах с вложенными и показать в панели Поиск");
                    find.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent event)
                        {
                            ISelection selected = ComfortSubmenuHelper.menuSelection(comfortSub, viewer);
                            if (selected instanceof IStructuredSelection objects)
                            {
                                IResource resource = NavigatorResourceResolver.resolveFirst(objects);
                                IProject project = resource != null ? resource.getProject()
                                    : objects.getFirstElement() instanceof IProject root ? root : null;
                                if (project == null)
                                    project = Global.getActiveProject(Global.getActivePage(), true);
                                List<URI> roots = new ArrayList<>();
                                java.util.Set<Object> visited = java.util.Collections.newSetFromMap(
                                    new java.util.IdentityHashMap<>());
                                boolean wholeProject = false;
                                for (Object node : objects.toList())
                                    wholeProject |= collectSearchRoots(viewer, node, roots, visited);
                                Global.tempLog("broken-links-project", "navigator selection=" + objects.toList()
                                    + " wholeProject=" + wholeProject + " roots=" + roots);
                                if (wholeProject)
                                    MdReferenceSupport.findInProject(project);
                                else
                                    MdReferenceSupport.findInObjects(project, roots,
                                        searchScopeLabel(viewer, objects.toList()));
                            }
                        }
                    });
                    added.add(find);

                    MenuItem unreachable = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
                        MdReachability.TITLE);
                    ComfortSubmenuHelper.setMenuItemTooltip(unreachable, UNREACHABLE_TOOLTIP);
                    unreachable.addSelectionListener(new SelectionAdapter()
                    {
                        @Override
                        public void widgetSelected(SelectionEvent event)
                        {
                            ISelection selected = ComfortSubmenuHelper.menuSelection(comfortSub, viewer);
                            if (!(selected instanceof IStructuredSelection objects))
                                return;
                            java.util.Set<IContainer> folders = new java.util.LinkedHashSet<>();
                            java.util.Set<Object> visited = java.util.Collections.newSetFromMap(
                                new java.util.IdentityHashMap<>());
                            for (Object node : objects.toList())
                                collectFolders(viewer, node, folders, visited);
                            MdReachability.findIn(folders);
                        }
                    });
                    added.add(unreachable);
                }
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

    /** Подписи выбранных узлов для заголовка результата: «Справочники», «Справочник.Товары». */
    private static String searchScopeLabel(CommonViewer viewer, List<?> nodes)
    {
        List<String> labels = new ArrayList<>();
        for (Object node : nodes)
        {
            boolean folder = NavigatorTreeElementLabels.isGroupNode(node)
                || NavigatorTreeElementLabels.isInsideObjectCollectionFolder(node);
            EObject object = folder ? null : NavigatorElementModels.resolveEObject(node);
            String label = object != null ? MdReferenceSupport.localized(EcoreUtil.getURI(object))
                : SmartTreeElementLabels.resolve(node, viewer.getLabelProvider());
            // Декоратор Git ставит перед подписью изменённого узла «> ».
            if (label != null && label.startsWith("> "))
                label = label.substring(2);
            if (label != null && !label.isBlank() && !labels.contains(label))
                labels.add(label);
        }
        return MdReferenceSupport.scopeLabel(labels);
    }

    /**
     * Папки проекта для узлов навигатора. Объект даёт свою папку, конфигурация и проект — весь
     * проект. Узел-группа своей папки в модели не имеет: её дают потомки — папку вида объектов
     * (или папку объекта-владельца для группы внутри объекта).
     */
    private static void collectFolders(CommonViewer viewer, Object node, java.util.Set<IContainer> folders,
        java.util.Set<Object> visited)
    {
        if (!visited.add(node))
            return;
        if (node instanceof IProject project)
        {
            folders.add(project);
            return;
        }
        boolean group = NavigatorTreeElementLabels.isGroupNode(node)
            || NavigatorTreeElementLabels.isInsideObjectCollectionFolder(node);
        if (!group && NavigatorElementModels.resolveEObject(node) != null)
        {
            IContainer folder = MdReachability.folderOf(NavigatorResourceResolver.resolve(node));
            if (folder != null)
                folders.add(folder);
            return;
        }
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider provider))
            return;
        Object[] children = provider.getChildren(node);
        if (children == null)
            return;
        boolean parentAdded = false;
        for (Object child : children)
        {
            if (NavigatorTreeElementLabels.isGroupNode(child)
                || NavigatorTreeElementLabels.isInsideObjectCollectionFolder(child))
            {
                collectFolders(viewer, child, folders, visited);
                continue;
            }
            // Все объекты одной группы лежат в одной папке: достаточно первого.
            if (parentAdded)
                continue;
            IResource resource = NavigatorResourceResolver.resolve(child);
            EObject object = NavigatorElementModels.resolveEObject(child);
            if (resource == null || object == null)
                continue;
            // Вложенный объект без своей папки (реквизит) разрешается в описатель владельца.
            boolean own = resource instanceof IContainer || object instanceof IBmObject top && top.bmIsTop();
            IContainer folder = resource instanceof IContainer container ? container : resource.getParent();
            IContainer parent = own ? folder.getParent() : folder;
            if (parent != null && parent.getType() != IResource.ROOT)
            {
                folders.add(parent);
                parentAdded = true;
            }
        }
    }

    private static boolean collectSearchRoots(CommonViewer viewer, Object node, List<URI> roots,
        java.util.Set<Object> visited)
    {
        if (!visited.add(node))
            return false;
        if (node instanceof IProject)
            return true;
        boolean folder = NavigatorTreeElementLabels.isGroupNode(node)
            || NavigatorTreeElementLabels.isInsideObjectCollectionFolder(node);
        EObject object = folder ? null : NavigatorElementModels.resolveEObject(node);
        if (object != null)
        {
            if (com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage.Literals.CONFIGURATION.isSuperTypeOf(object.eClass()))
                return true;
            URI uri = EcoreUtil.getURI(object);
            if (!roots.contains(uri))
                roots.add(uri);
            return false;
        }
        if (viewer.getContentProvider() instanceof ITreeContentProvider provider)
        {
            Object[] children = provider.getChildren(node);
            if (children != null)
                for (Object child : children)
                    if (collectSearchRoots(viewer, child, roots, visited))
                        return true;
        }
        return false;
    }

    private static void recomputeChecks(IStructuredSelection selection)
    {
        EObject model = NavigatorElementModels.resolveEObject(selection.getFirstElement());
        IResource resource = NavigatorResourceResolver.resolveFirst(selection);
        IProject project = resource != null ? resource.getProject() : null;
        if (Global.isLogEnabled())
        {
            Object firstElement = selection.getFirstElement();
            Global.log("CheckCommand", "навигатор «Проверить»: узел=" //$NON-NLS-1$ //$NON-NLS-2$
                + (firstElement == null ? "null" : firstElement.getClass().getName()) //$NON-NLS-1$
                + ", модель=" + (model == null ? "null" : model.getClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
                + ", проект=" + (project == null ? "null" : project.getName())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (project == null && selection.getFirstElement() instanceof IProject selected)
            project = selected;
        if (project == null)
        {
            toast("Не удалось определить проект выбранного объекта.");
            return;
        }

        // Корень навигатора — конфигурация или сам проект: точечная перепроверка такого узла
        // отрабатывает мгновенно и не проверяет ничего, нужна полная проверка всех объектов
        if (model instanceof com._1c.g5.v8.dt.metadata.mdclass.Configuration
            || selection.getFirstElement() instanceof IProject)
        {
            ComfortCheckRecompute.recomputeProject(project);
            return;
        }

        if (!(model instanceof IBmObject) && !(model instanceof com._1c.g5.v8.dt.metadata.mdclass.BasicForm))
        {
            toast("Выбранный элемент нельзя точечно перепроверить.");
            return;
        }

        if (resource != null)
        {
            try
            {
                resource.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            }
            catch (CoreException ignored)
            {
            }
        }

        try
        {
            ComfortCheckRecompute.recomputeObjects(project, List.of(model));
        }
        catch (Throwable t)
        {
            toast("Ошибка при запуске проверки: " + t.getClass().getSimpleName());
        }
    }

    private static void toast(String message)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(ITEM_TEXT, message, 5_000));
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }
}
