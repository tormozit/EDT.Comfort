package tormozit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
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
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.derived.context.IContextCollectingSession;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Добавляет «Запустить проверки» в подменю «Комфорт» навигатора EDT — точечно пересчитывает проверки
 * для выбранного объекта, на проекте — проверки всего проекта. Штатный пункт «Запустить проверку»
 * из контекстного меню навигатора убирается: наш делает то же.
 */
public final class NavigatorRecomputeChecksMenuHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.navigatorRecomputeChecksHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT = "Запустить проверки"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP =
            "Запустить все проверки объекта с вложенными, на проекте — всего проекта"; //$NON-NLS-1$
    private static final String STOCK_START_CHECK_COMMAND_ID = "com._1c.g5.v8.dt.ui.command.startCheck"; //$NON-NLS-1$
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
                removeStockStartCheck(menu);
                hookComfortSubmenu(menu, viewer);
                hookReferencesSubmenu(menu, viewer);
            }
        };
        menu.addMenuListener(listener);
        tree.setData(HOOK_MARKER, Boolean.TRUE);
        tree.addDisposeListener(ev -> {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    /**
     * Убирает штатный пункт «Запустить проверку»: наш «Запустить проверки» в подменю «Комфорт» делает
     * всё то же (проекты, объекты, множественное выделение), но проверку проекта назначает в фоне —
     * штатный обработчик назначает её в потоке UI и подвешивает EDT на десятки секунд. Два пункта
     * с одним действием в одном меню только путают. Пункт пересоздаётся при каждом показе меню —
     * убираем каждый раз.
     */
    private static void removeStockStartCheck(Menu menu)
    {
        for (MenuItem item : menu.getItems())
            if (!item.isDisposed() && STOCK_START_CHECK_COMMAND_ID.equals(commandId(item.getData())))
                item.dispose();
    }

    /** Команда пункта меню в обоих представлениях: JFace и E4. */
    private static String commandId(Object data)
    {
        if (data instanceof org.eclipse.ui.menus.CommandContributionItem contribution)
            return contribution.getCommand() != null ? contribution.getCommand().getId() : null;
        if (data instanceof org.eclipse.e4.ui.workbench.renderers.swt.HandledContributionItem contribution)
        {
            var model = contribution.getModel();
            if (model == null)
                return null;
            if (model.getWbCommand() != null)
                return model.getWbCommand().getId();
            return model.getCommand() != null ? model.getCommand().getElementId() : null;
        }
        return null;
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
            private final List<MenuItem> added = new ArrayList<>();

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
                item.setImage(ComfortCheckRecompute.icon());
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
                added.add(RebuildDerivedData.createMenuItem(comfortSub, viewer));
                if (viewer != null)
                {
                    added.add(createReferencesItem(comfortSub, viewer, true));

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

                    added.add(createSpellingItem(comfortSub, viewer, false));
                    added.add(createSpellingItem(comfortSub, viewer, true));
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

    /** Дополняет штатное подменю «Ссылки» навигатора. */
    private static void hookReferencesSubmenu(Menu contextMenu, CommonViewer viewer)
    {
        for (MenuItem item : contextMenu.getItems())
        {
            if (!(item.getData() instanceof org.eclipse.jface.action.IContributionItem contribution)
                || !"referencesSubMenu".equals(contribution.getId()))
                continue;
            Menu references = item.getMenu();
            if (references == null || references.isDisposed()
                || Boolean.TRUE.equals(references.getData(HOOK_MARKER)))
                return;
            references.setData(HOOK_MARKER, Boolean.TRUE);
            references.addMenuListener(new MenuAdapter()
            {
                private MenuItem added;

                @Override
                public void menuShown(MenuEvent event)
                {
                    if (added != null && !added.isDisposed())
                        added.dispose();
                    added = createReferencesItem(references, viewer, false);
                }

                @Override
                public void menuHidden(MenuEvent event)
                {
                    MenuItem previous = added;
                    added = null;
                    references.getDisplay().asyncExec(() -> {
                        if (previous != null && !previous.isDisposed())
                            previous.dispose();
                    });
                }
            });
            return;
        }
    }

    /** Поиск ссылок выбранных узлов через общий механизм обхода метаданных. */
    private static MenuItem createReferencesItem(Menu comfortSub, CommonViewer viewer, boolean brokenOnly)
    {
        MenuItem find = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
            brokenOnly ? "Найти битые ссылки метаданных" : "Найти ссылки в объекте");
        ComfortSubmenuHelper.setMenuItemTooltip(find,
            brokenOnly ? "Найти битые ссылки в выбранных объектах с вложенными и показать в панели Поиск"
                : "Найти все ссылки на метаданные в свойствах выбранных объектов с вложенными и показать в панели Поиск");
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
                    if (!brokenOnly)
                        MdReferenceSupport.findReferencesInObjects(project, wholeProject ? null : roots,
                            searchScopeLabel(viewer, objects.toList()));
                    else if (wholeProject)
                        MdReferenceSupport.findInProject(project);
                    else
                        MdReferenceSupport.findInObjects(project, roots,
                            searchScopeLabel(viewer, objects.toList()));
                }
            }
        });
        return find;
    }

    /**
     * Пункт проверки орфографии выбранных объектов с вложенными.
     *
     * @param allTexts {@code false} — только тексты для пользователя; {@code true} — ещё и программные
     */
    private static MenuItem createSpellingItem(Menu comfortSub, CommonViewer viewer, boolean allTexts)
    {
        MenuItem spelling = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.PUSH,
            allTexts ? UserTextSpellCheck.TITLE_ALL : UserTextSpellCheck.TITLE);
        ComfortSubmenuHelper.setMenuItemTooltip(spelling, allTexts
            ? "Найти ошибки орфографии во всех текстах выбранных объектов с вложенными: в текстах для"
                + " пользователя, в именах и комментариях объектов, в комментариях, строках и именах"
                + " модулей — и показать в панели Поиск. Тексты для пользователя отмечены в колонке"
                + " «Пользовательский»"
            : "Найти ошибки орфографии в синонимах, заголовках, подсказках и других текстах для"
                + " пользователя, а также в строках НСтр() модулей выбранных объектов с вложенными"
                + " и показать в панели Поиск");
        spelling.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent event)
            {
                ISelection selected = ComfortSubmenuHelper.menuSelection(comfortSub, viewer);
                if (!(selected instanceof IStructuredSelection objects))
                    return;
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
                if (wholeProject)
                    UserTextSpellCheck.findInProject(project, allTexts);
                else
                    UserTextSpellCheck.findInObjects(project, roots,
                        searchScopeLabel(viewer, objects.toList()), allTexts);
            }
        });
        return spelling;
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

    /**
     * Всё выделение, как у штатной команды: объекты группируются по проектам и проверяются одним
     * запуском на проект; проект или его конфигурация в выделении — проверка всего проекта.
     */
    private static void recomputeChecks(IStructuredSelection selection)
    {
        java.util.Map<IProject, List<EObject>> objectsByProject = new java.util.LinkedHashMap<>();
        java.util.Set<IProject> wholeProjects = new java.util.LinkedHashSet<>();
        int unresolved = 0;
        int unsupported = 0;
        for (Object element : selection.toList())
        {
            EObject model = NavigatorElementModels.resolveEObject(element);
            IResource resource = NavigatorResourceResolver.resolve(element);
            IProject project = resource != null ? resource.getProject() : null;
            if (Global.isLogEnabled())
                Global.log("CheckCommand", "навигатор «Запустить проверки»: узел=" //$NON-NLS-1$ //$NON-NLS-2$
                    + (element == null ? "null" : element.getClass().getName()) //$NON-NLS-1$
                    + ", модель=" + (model == null ? "null" : model.getClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
                    + ", проект=" + (project == null ? "null" : project.getName())); //$NON-NLS-1$ //$NON-NLS-2$
            if (project == null && element instanceof IProject selected)
                project = selected;
            if (project == null)
            {
                unresolved++;
                continue;
            }

            // Корень навигатора — конфигурация или сам проект: точечная перепроверка такого узла
            // отрабатывает мгновенно и не проверяет ничего, нужна полная проверка всех объектов
            if (model instanceof com._1c.g5.v8.dt.metadata.mdclass.Configuration || element instanceof IProject)
            {
                wholeProjects.add(project);
                continue;
            }

            if (!(model instanceof IBmObject) && !(model instanceof com._1c.g5.v8.dt.metadata.mdclass.BasicForm))
            {
                unsupported++;
                continue;
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
            objectsByProject.computeIfAbsent(project, key -> new ArrayList<>()).add(model);
        }

        for (IProject project : wholeProjects)
            ComfortCheckRecompute.recomputeProject(project);
        objectsByProject.keySet().removeAll(wholeProjects);
        for (var entry : objectsByProject.entrySet())
        {
            try
            {
                ComfortCheckRecompute.recomputeObjects(entry.getKey(), entry.getValue());
            }
            catch (Throwable t)
            {
                toast("Ошибка при запуске проверки: " + t.getClass().getSimpleName());
            }
        }

        if (unresolved > 0)
            toast(unresolved == selection.size()
                ? "Не удалось определить проект выбранного объекта."
                : "Пропущено без проекта: " + unresolved + ".");
        if (unsupported > 0)
            toast(unsupported == selection.size()
                ? "Выбранный элемент нельзя точечно перепроверить."
                : "Пропущено элементов, которые нельзя точечно перепроверить: " + unsupported + ".");
    }

    private static void toast(String message)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(ITEM_TEXT, message, 5_000));
    }

    private static final class RebuildDerivedData
    {
        private static final String TITLE = "Очистить кэши"; //$NON-NLS-1$
        private static final String LOG = "navigator-rebuild-derived-data"; //$NON-NLS-1$

        private static MenuItem createMenuItem(Menu menu, CommonViewer viewer)
        {
            MenuItem item = ComfortSubmenuHelper.createSortedMenuItem(menu, SWT.PUSH, TITLE, ITEM_TEXT);
            ComfortSubmenuHelper.setMenuItemTooltip(item,
                "Пересчитать производные данные выбранного объекта и всего его поддерева"); //$NON-NLS-1$
            item.addListener(SWT.Selection, event ->
            {
                try
                {
                    if (ComfortSubmenuHelper.menuSelection(menu, viewer) instanceof IStructuredSelection selection)
                        schedule(selection, viewer);
                }
                catch (RuntimeException e)
                {
                    Global.tempLogException(LOG, "resolve selection", e); //$NON-NLS-1$
                    ComfortCheckRecompute.toast(TITLE, "Не удалось определить объекты для пересчёта."); //$NON-NLS-1$
                }
            });
            return item;
        }

        private static void schedule(IStructuredSelection selection, CommonViewer viewer)
        {
            Map<IProject, Set<Long>> roots = new LinkedHashMap<>();
            Set<IProject> projects = new LinkedHashSet<>();
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object element : selection.toList())
                collectRoots(element, viewer, visited, roots, projects);
            projects.forEach(roots::remove);
            if (roots.isEmpty() && projects.isEmpty())
            {
                ComfortCheckRecompute.toast(TITLE, "Не удалось определить объекты для пересчёта."); //$NON-NLS-1$
                return;
            }

            Job.create(TITLE, monitor ->
            {
                IDerivedDataManagerProvider provider = Global.getOsgiService(IDerivedDataManagerProvider.class);
                Set<IProject> targets = new LinkedHashSet<>(projects);
                targets.addAll(roots.keySet());
                for (IProject project : targets)
                {
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;
                    try
                    {
                        rebuild(provider, project, roots.get(project));
                    }
                    catch (ReflectiveOperationException | RuntimeException | LinkageError e)
                    {
                        Global.tempLogException(LOG, "project=" + project.getName(), e); //$NON-NLS-1$
                        ComfortCheckRecompute.toast(TITLE,
                            "Не удалось запустить пересчёт в проекте «" + project.getName() + "»."); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                return Status.OK_STATUS;
            }).schedule();
        }

        private static void collectRoots(Object element, CommonViewer viewer, Set<Object> visited,
            Map<IProject, Set<Long>> roots, Set<IProject> projects)
        {
            if (!visited.add(element))
                return;
            if (viewer != null && (NavigatorTreeElementLabels.isGroupNode(element)
                || NavigatorTreeElementLabels.isInsideObjectCollectionFolder(element))
                && viewer.getContentProvider() instanceof ITreeContentProvider tree)
            {
                Object[] children = tree.getChildren(element);
                Global.tempLog(LOG, "group=" + element.getClass().getName() //$NON-NLS-1$
                    + " children=" + (children == null ? 0 : children.length)); //$NON-NLS-1$
                if (children != null)
                    for (Object child : children)
                        collectRoots(child, viewer, visited, roots, projects);
                return;
            }

            EObject model = NavigatorElementModels.resolveEObject(element);
            IResource resource = NavigatorResourceResolver.resolve(element);
            IProject project = element instanceof IProject selected ? selected
                : resource != null ? resource.getProject() : null;
            long id = model instanceof IBmObject bm && !model.eIsProxy() ? bm.bmGetId() : IBmObject.BM_NULL_ID;
            Global.tempLog(LOG, "selection project=" + project + " resource=" + resource //$NON-NLS-1$ //$NON-NLS-2$
                + " type=" + (model == null ? null : model.eClass().getName()) + " id=" + id); //$NON-NLS-1$ //$NON-NLS-2$
            if (project != null && (element instanceof IProject || model instanceof Configuration))
                projects.add(project);
            else if (project != null && id != IBmObject.BM_NULL_ID)
                roots.computeIfAbsent(project, key -> new LinkedHashSet<>()).add(id);
        }

        private static void rebuild(IDerivedDataManagerProvider provider, IProject project, Set<Long> roots)
            throws ReflectiveOperationException
        {
            var manager = provider == null ? null : provider.get(project);
            Global.tempLog(LOG, "rebuild project=" + project.getName() + " roots=" + roots //$NON-NLS-1$ //$NON-NLS-2$
                + " manager=" + (manager == null ? null : manager.getClass().getName())); //$NON-NLS-1$
            if (manager == null)
                throw new IllegalStateException("Derived data manager unavailable"); //$NON-NLS-1$
            if (roots == null)
            {
                manager.recomputeAll();
                ComfortCheckRecompute.toast(TITLE, "Запрошен пересчёт производных данных проекта."); //$NON-NLS-1$
                return;
            }

            // EDT собирает контексты полного пересчёта по одному ключу в читающей BM-транзакции.
            Method collect = manager.getClass().getDeclaredMethod("collectRebuildScope", //$NON-NLS-1$
                Object.class, IContextCollectingSession.class, IBmTransaction.class);
            collect.setAccessible(true);
            boolean[] collected = { false };
            boolean accepted = manager.updateDerivedData((session, model) ->
            {
                model.executeReadonlyTask(new AbstractBmTask<Void>("comfort.rebuildDerivedData") //$NON-NLS-1$
                {
                    @Override
                    public Void execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        Set<Object> keys = new LinkedHashSet<>();
                        for (long root : roots)
                        {
                            IBmObject object = transaction.getObjectById(root);
                            Global.tempLog(LOG, "root id=" + root + " resolved=" + (object != null)); //$NON-NLS-1$ //$NON-NLS-2$
                            if (object != null)
                            {
                                collectModelKeys(object, keys);
                                var children = EcoreUtil.<EObject>getAllContents(object, false);
                                while (children.hasNext())
                                    collectModelKeys(children.next(), keys);
                                collectModuleKeys(object, keys);
                            }
                        }
                        for (Object key : keys)
                            collectContext(collect, manager, key, session, transaction);
                        return null;
                    }
                });
                collected[0] = !session.isEmpty();
                Object contexts = Global.getField(session, "attachedContexts"); //$NON-NLS-1$
                Global.tempLog(LOG, "project=" + project.getName() + " collected=" + collected[0] //$NON-NLS-1$ //$NON-NLS-2$
                    + " segments=" + (contexts instanceof Map<?, ?> map ? map.keySet() : "unavailable")); //$NON-NLS-1$ //$NON-NLS-2$
            }, 0L, "«" + TITLE + "»"); //$NON-NLS-1$ //$NON-NLS-2$
            Global.tempLog(LOG, "project=" + project.getName() + " accepted=" + accepted); //$NON-NLS-1$ //$NON-NLS-2$
            if (!accepted)
                throw new IllegalStateException("Derived data manager rejected the update"); //$NON-NLS-1$
            ComfortCheckRecompute.toast(TITLE, collected[0]
                ? "Запрошен пересчёт производных данных поддерева." //$NON-NLS-1$
                : "Нет данных поддерева для пересчёта."); //$NON-NLS-1$
        }

        private static void collectModelKeys(EObject object, Set<Object> keys)
        {
            addModelKey(object, keys);
            if (object instanceof BasicForm form)
                addModelKey(form.getForm(), keys);
            else if (object instanceof BasicTemplate template)
                addModelKey(template.getTemplate(), keys);
        }

        private static void addModelKey(EObject object, Set<Object> keys)
        {
            if (object instanceof IBmObject bm && !object.eIsProxy() && bm.bmGetId() != IBmObject.BM_NULL_ID)
                keys.add(bm.bmGetId());
        }

        private static void collectModuleKeys(EObject object, Set<Object> keys)
        {
            IResource resource = NavigatorResourceResolver.resolve(object);
            IContainer folder = null;
            if (object instanceof BasicForm && object instanceof MdObject form && resource instanceof IContainer parent
                && parent.getName().equals(form.getName()) && parent.getParent() != null
                && ("Forms".equals(parent.getParent().getName()) //$NON-NLS-1$
                    || "CommonForms".equals(parent.getParent().getName()))) //$NON-NLS-1$
                folder = parent;
            else if (object instanceof MdObject && object instanceof IBmObject bm && bm.bmIsTop()
                && resource instanceof IFile file && "mdo".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                folder = file.getParent();
            Global.tempLog(LOG, "module scope resource=" + resource + " folder=" + folder); //$NON-NLS-1$ //$NON-NLS-2$
            if (resource instanceof IFile file && "bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                keys.add(URI.createPlatformResourceURI(file.getFullPath().toString(), true).toPlatformString(false));
            if (folder == null)
                return;
            try
            {
                folder.accept(member ->
                {
                    if (member instanceof IFile file && "bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
                        keys.add(URI.createPlatformResourceURI(file.getFullPath().toString(), true)
                            .toPlatformString(false));
                    return true;
                });
            }
            catch (CoreException e)
            {
                throw new IllegalStateException(e);
            }
        }

        private static void collectContext(Method collect, Object manager, Object key,
            IContextCollectingSession session, IBmTransaction transaction)
        {
            Global.tempLog(LOG, "collect key=" + key); //$NON-NLS-1$
            try
            {
                collect.invoke(manager, key, session, transaction);
            }
            catch (InvocationTargetException e)
            {
                throw new IllegalStateException("Cannot collect derived data for " + key, e.getCause()); //$NON-NLS-1$
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException(e);
            }
        }
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }
}
