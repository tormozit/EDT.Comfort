package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.widgets.Control;
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
 * Подменю «Развернуть до» в подменю «Комфорт» навигатора EDT: разворачивает выбранный узел
 * вглубь ровно до выбранного вида элементов — объектов, форм, команд, макетов или полностью.
 */
public final class NavigatorExpandToMenuHook implements IStartup
{
    private static final String TAG = "NavigatorExpandTo"; //$NON-NLS-1$
    private static final String HOOK_MARKER = "tormozit.navigatorExpandToHook"; //$NON-NLS-1$
    private static final String SUBMENU_TEXT = "Развернуть до"; //$NON-NLS-1$

    /** Число узлов, пройденных последним обходом (для журнала; только UI-поток). */
    private static int visitedCount;

    private enum Target
    {
        OBJECTS("Объекты", "Раскрыть группы до объектов метаданных; сами объекты остаются свёрнутыми"), //$NON-NLS-1$ //$NON-NLS-2$
        FORMS("Формы", "Раскрыть до форм объектов"), //$NON-NLS-1$ //$NON-NLS-2$
        COMMANDS("Команды", "Раскрыть до команд объектов"), //$NON-NLS-1$ //$NON-NLS-2$
        TEMPLATES("Макеты", "Раскрыть до макетов объектов"), //$NON-NLS-1$ //$NON-NLS-2$
        ALL("Все", "Раскрыть все вложенные узлы"); //$NON-NLS-1$ //$NON-NLS-2$

        final String text;
        final String tooltip;

        Target(String text, String tooltip)
        {
            this.text = text;
            this.tooltip = tooltip;
        }
    }

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
        if (!(part instanceof IViewPart view))
            return false;
        return Global.NAVIGATOR_VIEW_ID.equals(view.getViewSite().getId())
            || part.getClass().getName().contains("internal.navigator.ui.Navigator"); //$NON-NLS-1$
    }

    private static void tryHook(IViewPart navigator)
    {
        Object viewerObject = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        if (!(viewerObject instanceof CommonViewer viewer))
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed() || Boolean.TRUE.equals(tree.getData(HOOK_MARKER)))
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
                ISelection selection = ComfortSubmenuHelper.menuSelection(comfortSub, viewer);
                if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty())
                    return;

                MenuItem cascade = ComfortSubmenuHelper.createSortedMenuItem(comfortSub, SWT.CASCADE, SUBMENU_TEXT);
                ComfortSubmenuHelper.setMenuItemTooltip(cascade, "Развернуть выбранные узлы до выбранного вида элементов"); //$NON-NLS-1$
                Menu targets = new Menu(cascade);
                cascade.setMenu(targets);
                for (Target target : Target.values())
                {
                    MenuItem item = new MenuItem(targets, SWT.PUSH);
                    item.setText(target.text);
                    ComfortSubmenuHelper.setMenuItemTooltip(item, target.tooltip);
                    item.addListener(SWT.Selection, ev -> {
                        ISelection current = ComfortSubmenuHelper.menuSelection(comfortSub, viewer);
                        if (current instanceof IStructuredSelection currentStructured)
                            expandTo(viewer, currentStructured, target);
                    });
                }
                added.add(cascade);
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

    private static void expandTo(TreeViewer viewer, IStructuredSelection selection, Target target)
    {
        Control control = viewer.getControl();
        if (control == null || control.isDisposed())
            return;
        Object[] roots = selection.toArray();

        // Обход модели — по content provider, без обращений к вьюверу; разворот — одним вызовом
        Set<Object> toExpand = new LinkedHashSet<>();
        visitedCount = 0;
        long t0 = System.nanoTime();
        if (target != Target.ALL)
        {
            for (Object root : roots)
            {
                try
                {
                    collectPath(viewer, root, target, true, toExpand);
                }
                catch (RuntimeException e)
                {
                    Global.logError(TAG, "collectPath", e); //$NON-NLS-1$
                }
            }
        }
        long t1 = System.nanoTime();

        // Авторазворачивание единственного потомка не должно вмешиваться в программный разворот
        TreeExpander.runSuppressed(() -> {
            control.setRedraw(false);
            try
            {
                for (Object root : roots)
                    viewer.collapseToLevel(root, TreeViewer.ALL_LEVELS);
                if (target == Target.ALL)
                {
                    for (Object root : roots)
                        viewer.expandToLevel(root, TreeViewer.ALL_LEVELS);
                }
                else if (!toExpand.isEmpty())
                {
                    // Соседние ветки вне выбора остаются как были
                    Set<Object> all = new LinkedHashSet<>(Arrays.asList(viewer.getExpandedElements()));
                    all.addAll(toExpand);
                    viewer.setExpandedElements(all.toArray());
                }
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "expand", e); //$NON-NLS-1$
            }
            finally
            {
                if (!control.isDisposed())
                    control.setRedraw(true);
            }
        });
        if (Global.isLogEnabled())
        {
            Global.log(TAG, target + ": пройдено " + visitedCount + ", развёрнуто " + toExpand.size() //$NON-NLS-1$ //$NON-NLS-2$
                + ", обход " + (t1 - t0) / 1_000_000 + " мс" //$NON-NLS-1$ //$NON-NLS-2$
                + ", разворот " + (System.nanoTime() - t1) / 1_000_000 + " мс"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Собирает в {@code toExpand} узлы, которые нужно раскрыть, чтобы показать существующие
     * элементы вида {@code target}. Возвращает {@code true}, если в узле или под ним такие
     * элементы есть; узлы без них в набор не попадают.
     */
    private static boolean collectPath(TreeViewer viewer, Object node, Target target, boolean isRoot,
        Set<Object> toExpand)
    {
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider provider))
            return false;
        visitedCount++;

        // Объект найден: сам он остаётся свёрнутым, раскрываются только его предки
        if (target == Target.OBJECTS && !isRoot && NavigatorTreeElementLabels.isMdObjectNavigatorNode(node))
            return true;

        // Листья отсекаем дешёвым hasChildren, не материализуя детей
        if (!provider.hasChildren(node))
            return false;
        Object[] children = provider.getChildren(node);
        if (children == null || children.length == 0)
            return false;

        // Узел с элементами нужного вида среди детей («Формы», «Команды», «Макеты»): раскрываем его
        // и глубже не идём. Класс адаптера папки не проверяем — он у разных папок разный.
        if (target != Target.OBJECTS && folderHolds(children, target))
        {
            toExpand.add(node);
            return true;
        }

        boolean found = false;
        for (Object child : children)
            found |= collectPath(viewer, child, target, false, toExpand);
        if (found)
            toExpand.add(node);
        return found;
    }

    private static boolean folderHolds(Object[] children, Target target)
    {
        for (Object child : children)
        {
            EObject model = NavigatorElementModels.resolveEObject(child);
            if (model == null)
                continue;
            String kind = model.eClass().getName();
            switch (target)
            {
            case FORMS:
                if (kind.endsWith("Form")) //$NON-NLS-1$
                    return true;
                break;
            case COMMANDS:
                if (MdEditorAttributeMenuHook.isObjectCommand(model))
                    return true;
                break;
            case TEMPLATES:
                if (kind.endsWith("Template")) //$NON-NLS-1$
                    return true;
                break;
            default:
                break;
            }
        }
        return false;
    }
}
