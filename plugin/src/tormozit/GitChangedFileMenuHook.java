package tormozit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;

import org.eclipse.ui.handlers.IHandlerService;

/**
 * Добавляет «Открыть в Навигаторе» и «Открыть объект» в подменю «Комфорт»
 * контекстного меню изменённых файлов в EGit/EDT-представлениях
 * ({@code StagingView}, {@code RepositoryExplorerView}, {@code GenericHistoryView}).
 */
public final class GitChangedFileMenuHook implements IStartup
{
    private static final String ROOT_MARKER = "tormozit.gitChangedFileRootHook"; //$NON-NLS-1$
    private static final String SUB_MARKER  = "tormozit.gitChangedFileSubHook";  //$NON-NLS-1$

    private static final String DT_STAGING_VIEW_ID  = "com._1c.g5.v8.dt.internal.team.ui.views.DtStagingView"; //$NON-NLS-1$
    private static final String DT_TEAM_VIEW_ID     = "com._1c.g5.v8.dt.team.ui.development.view"; //$NON-NLS-1$
    private static final String EGIT_STAGING_VIEW_ID = "org.eclipse.egit.ui.StagingView"; //$NON-NLS-1$
    private static final String EGIT_REPOS_VIEW_ID   = "org.eclipse.egit.ui.RepositoryExplorerView"; //$NON-NLS-1$
    private static final String EGIT_HISTORY_VIEW_ID  = "org.eclipse.egit.ui.HistoryView"; //$NON-NLS-1$
    private static final String TEAM_HISTORY_VIEW_ID   = "org.eclipse.team.ui.GenericHistoryView"; //$NON-NLS-1$

    private static final String NAV_ITEM_TEXT = "Открыть в Навигаторе"; //$NON-NLS-1$
    private static final String OBJ_ITEM_TEXT = "Открыть объект";       //$NON-NLS-1$
    private static final String ADD_TO_SET_CMD = "tormozit.git.addToObjectSet"; //$NON-NLS-1$
    private static final String ADD_TO_SET_MARKER = "tormozit.gitAddToObjectSetItem"; //$NON-NLS-1$

    /**
     * Snapshot мультивыделения, сохранённый при открытии меню.
     * Handler читает его при клике по пункту «Добавить в набор».
     */
    static IStructuredSelection multiSelectionSnapshot;

    private static final String GIT_CONTEXT_ID = "tormozit.gitChangedFile.context"; //$NON-NLS-1$

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
        });

        // Установка слушателя активации/деактивации контекста для git-представлений
        PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow w) { hookPartListener(w); }
            @Override
            public void windowActivated(IWorkbenchWindow w) {}
            @Override
            public void windowDeactivated(IWorkbenchWindow w) {}
            @Override
            public void windowClosed(IWorkbenchWindow w) { unhookPartListener(w); }
        });
        for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookPartListener(w);
    }

    private static void hookPartListener(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(gitViewPartListener);
    }

    private static void unhookPartListener(IWorkbenchWindow window)
    {
        window.getPartService().removePartListener(gitViewPartListener);
    }

    private static IContextActivation gitContextActivation;
    private static IContextService contextService;

    private static IContextService contextService()
    {
        if (contextService == null)
            contextService = PlatformUI.getWorkbench().getService(IContextService.class);
        return contextService;
    }

    private static final IPartListener2 gitViewPartListener = new IPartListener2()
    {
        @Override
        public void partActivated(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (!(part instanceof IWorkbenchPart))
                return;

            IContextService cs = contextService();
            if (cs == null)
                return;

            if (isGitView(part))
            {
                if (gitContextActivation == null)
                {
                    gitContextActivation = cs.activateContext(GIT_CONTEXT_ID);
                    Global.log("GitChangedFileMenu: context activated for " + part.getSite().getId()); //$NON-NLS-1$
                }
            }
            else if (gitContextActivation != null)
            {
                cs.deactivateContext(gitContextActivation);
                gitContextActivation = null;
                Global.log("GitChangedFileMenu: context deactivated (non-git part=" + part.getSite().getId() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof IWorkbenchPart && isGitView(part) && gitContextActivation != null)
            {
                IContextService cs = contextService();
                if (cs != null)
                {
                    cs.deactivateContext(gitContextActivation);
                    gitContextActivation = null;
                    Global.log("GitChangedFileMenu: context deactivated (closed)"); //$NON-NLS-1$
                }
            }
        }

        @Override public void partOpened(IWorkbenchPartReference ref) {}
        @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
        @Override public void partDeactivated(IWorkbenchPartReference ref) {}
        @Override public void partHidden(IWorkbenchPartReference ref) {}
        @Override public void partVisible(IWorkbenchPartReference ref) {}
        @Override public void partInputChanged(IWorkbenchPartReference ref) {}
    };

    // ========================================================================
    // Entry point — диагностика event.widget
    // ========================================================================

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Control target) || target.isDisposed())
        {
            Global.log("GitChangedFileMenu: event.widget is not a Control: "
                + (event.widget != null ? event.widget.getClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        Global.log("GitChangedFileMenu: MenuDetect widget="
            + target.getClass().getName() //$NON-NLS-1$
            + " hasMenu=" + (target.getMenu() != null)); //$NON-NLS-1$

        // Ищем control, у которого есть меню (может быть на родителе)
        Control menuControl = target;
        while (menuControl != null && !menuControl.isDisposed() && menuControl.getMenu() == null)
            menuControl = menuControl.getParent();

        if (menuControl == null || menuControl.isDisposed())
        {
            Global.log("GitChangedFileMenu: no control with menu found"); //$NON-NLS-1$
            return;
        }

        if (menuControl != target)
        {
            Global.log("GitChangedFileMenu: menu on parent class="
                + menuControl.getClass().getName()); //$NON-NLS-1$
        }

        IViewPart view = findGitViewContaining(target);
        if (view == null)
            return;

        tryAttachMenuListener(menuControl, view, 0);
    }

    // ========================================================================
    // Поиск git-представления по control (4 подхода)
    // ========================================================================

    private static IViewPart findGitViewContaining(Control control)
    {
        IViewPart result;

        // 1) Walk parent chain: ищем IEclipseContext с MPart
        result = findByContextWalk(control);
        if (result != null)
        {
            Global.log("GitChangedFileMenu: found by contextWalk view="
                + result.getSite().getId()); //$NON-NLS-1$
            return result;
        }
        Global.log("GitChangedFileMenu: contextWalk failed"); //$NON-NLS-1$

        // 2) ViewPart.getControl() via reflection → isDescendantOf
        result = findByViewPartGetControl(control);
        if (result != null)
        {
            Global.log("GitChangedFileMenu: found by ViewPart.getControl view="
                + result.getSite().getId()); //$NON-NLS-1$
            return result;
        }
        Global.log("GitChangedFileMenu: ViewPart.getControl failed"); //$NON-NLS-1$

        // 3) MPart.getWidget() → isDescendantOf
        result = findByMPartWidget(control);
        if (result != null)
        {
            Global.log("GitChangedFileMenu: found by MPart.getWidget view="
                + result.getSite().getId()); //$NON-NLS-1$
            return result;
        }
        Global.log("GitChangedFileMenu: MPart.getWidget failed"); //$NON-NLS-1$

        // 4) Fallback: active part
        try
        {
            IWorkbenchPage page = activePage();
            if (page != null)
            {
                IWorkbenchPart active = page.getActivePart();
                if (active != null)
                {
                    String activeId = active.getSite() != null ? active.getSite().getId() : "no-site";
                    Global.log("GitChangedFileMenu: activePart id=" + activeId); //$NON-NLS-1$
                    if (isGitView(active))
                    {
                        Global.log("GitChangedFileMenu: found by activePart view="
                            + activeId); //$NON-NLS-1$
                        return (IViewPart) active;
                    }
                }
                else
                {
                    Global.log("GitChangedFileMenu: activePart is null"); //$NON-NLS-1$
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: activePart error: " + e); //$NON-NLS-1$
        }
        Global.log("GitChangedFileMenu: activePart failed"); //$NON-NLS-1$

        return null;
    }

    // ========================================================================
    // Approach 1: контекстный walk (parent chain, IEclipseContext → MPart)
    // ========================================================================

    private static IViewPart findByContextWalk(Control control)
    {
        IWorkbenchPage page = activePage();
        if (page == null)
            return null;

        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            Object ctx = c.getData(
                "org.eclipse.e4.ui.workbench.IPresentationEngine.ACTIVE_CONTEXT"); //$NON-NLS-1$
            if (ctx == null)
                continue;

            try
            {
                Method getMethod = ctx.getClass().getMethod("get", Class.class); //$NON-NLS-1$
                Object mpart = getMethod.invoke(ctx, MPart.class);
                if (mpart == null)
                    continue;

                Method getElementId = mpart.getClass().getMethod("getElementId"); //$NON-NLS-1$
                String elementId = (String) getElementId.invoke(mpart);
                if (elementId == null)
                    continue;

                IViewPart view = page.findView(elementId);
                if (isGitView(view))
                    return view;
            }
            catch (Exception ignored)
            {
            }
        }
        return null;
    }

    // ========================================================================
    // Approach 2: ViewPart.getControl() via reflection
    // ========================================================================

    private static IViewPart findByViewPartGetControl(Control control)
    {
        try
        {
            IWorkbenchPage page = activePage();
            if (page == null)
                return null;

            logAllViewReferences(page, "ViewPart");

            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (view == null || !isGitView(view))
                    continue;

                Control root = getViewControlViaViewPart(view);
                if (root == null)
                {
                    Global.log("GitChangedFileMenu: ViewPart.getControl null for "
                        + view.getSite().getId()); //$NON-NLS-1$
                    continue;
                }

                if (isDescendantOf(control, root))
                {
                    Global.log("GitChangedFileMenu: ViewPart.getControl OK for "
                        + view.getSite().getId()); //$NON-NLS-1$
                    return view;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: ViewPart.getControl error: " + e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Пытается получить корневой Control представления через
     * {@code ViewPart.getControl()} (protected, через рефлексию).
     */
    private static Control getViewControlViaViewPart(IViewPart view)
    {
        try
        {
            // Пробуем getControl() — protected в ViewPart
            for (java.lang.reflect.Method m : view.getClass().getMethods())
            {
                if (!"getControl".equals(m.getName()) || m.getParameterCount() != 0) //$NON-NLS-1$
                    continue;
                m.setAccessible(true);
                Object result = m.invoke(view);
                if (result instanceof Control c)
                    return c;
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    // ========================================================================
    // Approach 3: MPart.getWidget()
    // ========================================================================

    private static IViewPart findByMPartWidget(Control control)
    {
        try
        {
            IWorkbenchPage page = activePage();
            if (page == null)
                return null;

            logAllViewReferences(page, "MPart");

            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (view == null || !isGitView(view))
                    continue;

                Control root = getViewControlViaMPart(view);
                if (root == null)
                {
                    Global.log("GitChangedFileMenu: MPart.getWidget null for "
                        + view.getSite().getId()); //$NON-NLS-1$
                    continue;
                }

                if (isDescendantOf(control, root))
                {
                    Global.log("GitChangedFileMenu: MPart.getWidget OK for "
                        + view.getSite().getId()); //$NON-NLS-1$
                    return view;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: MPart.getWidget error: " + e); //$NON-NLS-1$
        }
        return null;
    }

    private static Control getViewControlViaMPart(IViewPart view)
    {
        try
        {
            Object mpart = view.getSite().getService(MPart.class);
            if (mpart == null)
            {
                Global.log("GitChangedFileMenu: MPart service null for "
                    + view.getSite().getId()); //$NON-NLS-1$
                return null;
            }

            Method getWidget = MPart.class.getMethod("getWidget"); //$NON-NLS-1$
            Object widget = getWidget.invoke(mpart);
            if (widget instanceof Control c)
                return c;

            Global.log("GitChangedFileMenu: MPart.getWidget returned non-Control: "
                + (widget != null ? widget.getClass().getName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: MPart.getWidget exception: " + e); //$NON-NLS-1$
        }
        return null;
    }

    // ========================================================================
    // Utilities: isDescendantOf
    // ========================================================================

    private static boolean isDescendantOf(Control child, Control ancestor)
    {
        if (child == null || ancestor == null || child.isDisposed() || ancestor.isDisposed())
            return false;
        for (Control c = child; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c == ancestor)
                return true;
        }
        return false;
    }

    // ========================================================================
    // tryAttachMenuListener
    // ========================================================================

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
        menu.setData("menuControl", control); //$NON-NLS-1$
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

    /**
     * Берёт selection напрямую из Table/Tree под фокусом.
     * Используется handler'ами клавиатурных привязок как fallback,
     * когда {@code page.getSelection()} возвращает неверный тип
     * (например, SWTCommit вместо FileDiff в HistoryView).
     */
    public static ISelection selectionFromFocusControl()
    {
        Display d = Display.getCurrent();
        if (d == null)
            return null;
        Control control = d.getFocusControl();
        if (control == null || control.isDisposed())
            return null;
        Control c = control;
        while (c != null && !c.isDisposed() && !(c instanceof Table) && !(c instanceof Tree))
            c = c.getParent();
        if (c == null || c.isDisposed())
            return null;
        if (c instanceof Table table)
        {
            TableItem[] items = table.getSelection();
            if (items.length > 0)
            {
                List<Object> list = new ArrayList<>(items.length);
                for (TableItem ti : items)
                {
                    Object d2 = ti.getData();
                    if (d2 != null)
                        list.add(d2);
                }
                return new StructuredSelection(list);
            }
        }
        else if (c instanceof Tree tree)
        {
            TreeItem[] items = tree.getSelection();
            if (items.length > 0)
            {
                List<Object> list = new ArrayList<>(items.length);
                for (TreeItem ti : items)
                {
                    Object d2 = ti.getData();
                    if (d2 != null)
                        list.add(d2);
                }
                return new StructuredSelection(list);
            }
        }
        return null;
    }

    /** @return selection from the widget's own Table/Tree items, or null */
    private static ISelection selectionOfClickedControl(Widget submenuWidget)
    {
        if (!(submenuWidget instanceof Menu submenu))
            return null;
        MenuItem parentItem = submenu.getParentItem();
        if (parentItem == null)
            return null;
        Menu rootMenu = parentItem.getParent();
        if (rootMenu == null)
            return null;
        Object data = rootMenu.getData("menuControl"); //$NON-NLS-1$
        if (!(data instanceof Control control) || control.isDisposed())
            return null;

        Object element = null;
        if (control instanceof Table table)
        {
            TableItem[] items = table.getSelection();
            if (items.length > 0)
                element = items[0].getData();
        }
        else if (control instanceof Tree tree)
        {
            TreeItem[] items = tree.getSelection();
            if (items.length > 0)
                element = items[0].getData();
        }

        if (element != null)
            return new StructuredSelection(element);
        return null;
    }

    private static MenuAdapter buildItemsMenuListener(IViewPart view)
    {
        return new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(3);

            @Override
            public void menuShown(MenuEvent e)
            {
                ISelection selection = selectionOf(view);

                // Try to get selection from the viewer that owns the clicked control
                ISelection viewerSel = selectionOfClickedControl(e.widget);
                if (viewerSel instanceof IStructuredSelection vs
                    && (selection == null || vs.size() > ((IStructuredSelection) selection).size()))
                    selection = vs;

                if (selection == null)
                {
                    Global.log("GitChangedFileMenu: selection is null"); //$NON-NLS-1$
                    return;
                }
                if (!(selection instanceof IStructuredSelection structured))
                {
                    Global.log("GitChangedFileMenu: selection class="
                        + selection.getClass().getName()); //$NON-NLS-1$
                    return;
                }
                // Snapshot для handler'а «Добавить в набор» (выполняется после клика по пункту)
                multiSelectionSnapshot = structured;

                Menu submenu = (Menu) e.widget;
                Shell shell = submenu.getShell();

                // === Одиночное выделение: "Открыть в Навигаторе" и "Открыть объект" ===
                if (structured.size() == 1)
                {
                    Object element = structured.getFirstElement();
                    Global.log("GitChangedFileMenu: resolve single element class="
                        + element.getClass().getName() + " toString=" + element); //$NON-NLS-1$

                    IFile file = resolveFile(view, element);
                    if (file != null && file.exists())
                    {
                        EObject eObject = resolveEObject(file);
                        if (eObject != null)
                        {
                            Global.log("GitChangedFileMenu: resolved IFile=" + file.getFullPath() //$NON-NLS-1$
                                + " EObject=" + eObject); //$NON-NLS-1$

                            IFile capturedFile = file;
                            EObject captured = eObject;

                            MenuItem navItem = new MenuItem(submenu, SWT.PUSH);
                            navItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                                NAV_ITEM_TEXT, "tormozit.git.showInNavigator", GIT_CONTEXT_ID));
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
                            objItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                                OBJ_ITEM_TEXT, "tormozit.git.openObject", GIT_CONTEXT_ID));
                            objItem.addSelectionListener(new SelectionAdapter()
                            {
                                @Override
                                public void widgetSelected(SelectionEvent ev)
                                {
                                    openInEditor(captured, capturedFile, shell);
                                }
                            });
                            addedItems.add(objItem);

                            addIrHistoryItemIfNeeded(submenu, view, capturedFile, addedItems);
                        }
                    }
                }

                // === Любое выделение: "Добавить в набор" ===
                MenuItem addToSetItem = new MenuItem(submenu, SWT.PUSH);
                // Определяем проект из первого подходящего элемента selection
                String pName = null;
                for (Object element : structured.toList())
                {
                    IFile f = resolveFile(view, element);
                    if (f != null && f.exists())
                    {
                        IProject p = f.getProject();
                        if (p != null)
                        {
                            pName = p.getName();
                            break;
                        }
                    }
                }
                if (pName == null)
                {
                    IProject p = ActiveProjectTracker.resolveContextProject(view.getViewSite().getPage());
                    if (p != null)
                        pName = p.getName();
                }

                ObjectSets.SetDef addTarget = null;
                if (pName != null)
                {
                    ObjectSetsAddTargetState.getInstance().ensureForProject(pName);
                    addTarget = ObjectSetsAddTargetState.getInstance().getAddTargetSet(pName);
                }
                if (addTarget != null)
                {
                    addToSetItem.setText(ComfortSubmenuHelper.menuItemTextWithKeyBinding(
                        "Добавить в набор <" + addTarget.name + ">", ADD_TO_SET_CMD, GIT_CONTEXT_ID));
                    addToSetItem.setToolTipText("Добавить выбранные объекты метаданных в набор \u00ab" + addTarget.name + "\u00bb" + Global.pluginSignForTooltip());
                    addToSetItem.setEnabled(true);
                }
                else
                {
                    addToSetItem.setText("Добавить в набор <\u2026>");
                    addToSetItem.setToolTipText("Выберите активный набор в панели \u00abНаборы объектов\u00bb" + Global.pluginSignForTooltip());
                    addToSetItem.setEnabled(false);
                }
                addToSetItem.setData(ADD_TO_SET_MARKER, Boolean.TRUE);
                addToSetItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        IHandlerService handlerService =
                            view.getViewSite().getService(IHandlerService.class);
                        if (handlerService == null)
                            return;
                        try
                        {
                            handlerService.executeCommand(ADD_TO_SET_CMD, null);
                        }
                        catch (Exception ex)
                        {
                            Global.log("GitAddToObjectSet: " + ex);
                        }
                    }
                });
                addedItems.add(addToSetItem);
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
    // HistoryView: «История ИР»
    // ========================================================================

    private static void addIrHistoryItemIfNeeded(Menu submenu, IViewPart view,
        IFile capturedFile, List<MenuItem> addedItems)
    {
        if (!isHistoryView(view))
            return;

        MenuItem irItem = new MenuItem(submenu, SWT.PUSH);
        irItem.setText("История ИР");
        irItem.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent ev)
            {
                String commitSha = extractCommitShaFrom(view);
                HistoryViewHandler.executeIrHistoryWithFile(capturedFile, commitSha);
            }
        });
        addedItems.add(irItem);
    }

    private static String extractCommitShaFrom(IViewPart view)
    {
        ISelection sel = selectionOf(view);
        if (!(sel instanceof IStructuredSelection ss) || ss.size() != 1)
            return "";
        Object element = ss.getFirstElement();
        return HistoryViewHandler.extractCommitSha(element);
    }

    // ========================================================================
    // Resolution
    // ========================================================================

    public static IFile resolveFile(IWorkbenchPart view, Object element)
    {
        if (element instanceof IFile file)
            return file;

        // IAdaptable (PlatformObject subclasses like StagingEntry)
        if (element instanceof org.eclipse.core.runtime.IAdaptable adaptable)
        {
            IFile adapted = adaptable.getAdapter(IFile.class);
            if (adapted != null)
                return adapted;
        }

        // StagingEntry.getFile() via reflection — returns IFile directly
        try
        {
            Object f = Global.call(element, "getFile"); //$NON-NLS-1$
            if (f instanceof IFile file)
                return file;
        }
        catch (Exception ignored)
        {
        }

        // StagingEntry.getLocation() via reflection — returns absolute IPath
        try
        {
            Object loc = Global.call(element, "getLocation"); //$NON-NLS-1$
            if (loc instanceof IPath p)
            {
                IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocation(p);
                if (files != null && files.length > 0)
                    return files[0];
            }
        }
        catch (Exception ignored)
        {
        }

        // Fallback: getPath() → project-relative path → IFile
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
            IProject project = resolveProject(view, element);
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

    private static IProject resolveProject(IWorkbenchPart view, Object element)
    {
        // Try to get project from the part that triggered the menu (e.g. history view)
        if (view != null)
        {
            IProject p = Global.getActiveProject(view, false);
            if (p != null)
                return p;
        }

        IWorkbenchPage page = activePage();
        if (page == null)
            return null;

        // Try from active page
        IProject project = Global.getActiveProject(page, false);
        if (project != null)
            return project;

        // Fallback: try to extract project from StagingEntry's repository path
        try
        {
            Object repo = Global.call(element, "getRepository"); //$NON-NLS-1$
            if (repo != null)
            {
                Object workTree = Global.call(repo, "getWorkTree"); //$NON-NLS-1$
                if (workTree instanceof java.io.File workDir)
                {
                    String repoPath = workDir.getAbsolutePath().replace('\\', '/');
                    for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                    {
                        if (!p.isOpen())
                            continue;
                        String projPath = p.getLocation().toFile().getAbsolutePath().replace('\\', '/');
                        if (repoPath.equals(projPath) || repoPath.startsWith(projPath + "/"))
                            return p;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return null;
    }

    public static EObject resolveEObject(IFile file)
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

    public static void openInEditor(EObject eObject, IFile file, Shell shell)
    {
        try
        {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper"); //$NON-NLS-1$
            Object helper = cls.getConstructor().newInstance();

            // For .bsl files: use platform URI to open parent object with module page
            if (file != null && "bsl".equalsIgnoreCase(file.getFileExtension())) //$NON-NLS-1$
            {
                URI moduleUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true)
                                   .appendFragment("/0"); //$NON-NLS-1$
                for (java.lang.reflect.Method m : cls.getMethods())
                {
                    if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 2) //$NON-NLS-1$
                        continue;
                    if (m.getParameterTypes()[0].equals(URI.class)
                        && m.getParameterTypes()[1].equals(ISelection.class))
                    {
                        m.invoke(helper, moduleUri, null);
                        return;
                    }
                }
            }

            // Default: openEditor(EObject)
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

    private static void logAllViewReferences(IWorkbenchPage page, String tag)
    {
        try
        {
            StringBuilder sb = new StringBuilder("GitChangedFileMenu: " + tag + " views=["); //$NON-NLS-1$ //$NON-NLS-2$
            for (IViewReference ref : page.getViewReferences())
            {
                if (sb.length() > 40)
                    sb.append(", "); //$NON-NLS-1$
                IViewPart v = ref.getView(false);
                sb.append(v != null ? v.getSite().getId() : ref.getId() + "(not-created)");
            }
            sb.append("]"); //$NON-NLS-1$
            Global.log(sb.toString());
        }
        catch (Exception e)
        {
            Global.log("GitChangedFileMenu: logViews error: " + e); //$NON-NLS-1$
        }
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

    private static boolean isHistoryView(IWorkbenchPart part)
    {
        if (part == null || part.getSite() == null)
            return false;
        String id = part.getSite().getId();
        return EGIT_HISTORY_VIEW_ID.equals(id) || TEAM_HISTORY_VIEW_ID.equals(id);
    }

    public static boolean isGitView(IWorkbenchPart part)
    {
        if (part == null || part.getSite() == null)
            return false;
        String id = part.getSite().getId();
        return DT_STAGING_VIEW_ID.equals(id) || DT_TEAM_VIEW_ID.equals(id)
            || EGIT_STAGING_VIEW_ID.equals(id) || EGIT_REPOS_VIEW_ID.equals(id)
            || EGIT_HISTORY_VIEW_ID.equals(id) || TEAM_HISTORY_VIEW_ID.equals(id);
    }

    private static ISelection selectionOf(IWorkbenchPart view)
    {
        ISelectionProvider provider = view.getSite().getSelectionProvider();
        return provider != null ? provider.getSelection() : null;
    }
}
