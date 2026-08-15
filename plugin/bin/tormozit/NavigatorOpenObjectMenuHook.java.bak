package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
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
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Добавляет «Открыть объект» и «Показать в навигаторе» в подменю «Комфорт»
 * дерева «Структура проекта» ({@code org.eclipse.ui.navigator.ProjectExplorer} —
 * подтверждено логом {@code isNavigatorView}, вопреки комментариям в остальном коде
 * плагина, где «Навигатор» — это {@code com._1c.g5.v8.dt.ui2.navigator},
 * {@link Global#NAVIGATOR_VIEW_ID}; это ДВА разных дерева) — те же команды,
 * что уже есть для изменённых файлов Git ({@link GitChangedFileMenuHook}).
 * <p>
 * Ctrl+T в этом дереве также подключён (см. {@link #installFocusNavigatorOverride}) —
 * подменяет обработчик штатной {@value #FOCUS_NAVIGATOR_COMMAND_ID}, которая иначе
 * работает по активному редактору, а не по выделению в дереве.
 */
public final class NavigatorOpenObjectMenuHook implements IStartup
{
    private static final String PROJECT_EXPLORER_ID = "org.eclipse.ui.navigator.ProjectExplorer"; //$NON-NLS-1$
    private static final String FOCUS_NAVIGATOR_COMMAND_ID = "com._1c.g5.v8.dt.ui.commands.focusNavigator"; //$NON-NLS-1$
    private static final String HOOK_MARKER = "tormozit.navigatorOpenObjectHook"; //$NON-NLS-1$
    private static final String ITEM_TEXT_OPEN = "Открыть объект"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP_OPEN =
            "Открыть редактор объекта метаданных выбранного элемента навигатора" //$NON-NLS-1$
            + Global.pluginSignForTooltip();
    private static final String ITEM_TEXT_REVEAL = "Показать в навигаторе"; //$NON-NLS-1$
    private static final String ITEM_TOOLTIP_REVEAL =
            "Выделить и прокрутить к объекту в дереве навигатора" //$NON-NLS-1$
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
        return PROJECT_EXPLORER_ID.equals(id);
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

        installFocusNavigatorOverride(navigator, viewer);
    }

    /**
     * Штатная команда EDT {@value #FOCUS_NAVIGATOR_COMMAND_ID} («Сфокусировать в Навигаторе»)
     * висит на Ctrl+T в широком {@code org.eclipse.ui.contexts.window} и всегда действует по
     * активному редактору, а не по выделению в этом дереве. Подменяем её обработчик через
     * {@code IHandlerService} самого view — такая активация автоматически перекрывает штатную,
     * только пока «Структура проекта» в фокусе (тот же приём, что и
     * {@code StacktracesViewInteractionHook.tryInstallCopy} для Ctrl+C).
     */
    private static void installFocusNavigatorOverride(IViewPart navigator, CommonViewer viewer)
    {
        IHandlerService handlerService = navigator.getSite().getService(IHandlerService.class);
        if (handlerService == null)
            return;

        handlerService.activateHandler(FOCUS_NAVIGATOR_COMMAND_ID, new AbstractHandler()
        {
            @Override
            public Object execute(ExecutionEvent event)
            {
                ISelection selection = viewer.getSelection();
                EObject eObject = resolveEObjectFromSelection(selection);
                if (eObject != null)
                    NavigatorReveal.revealAndActivateIfHidden(eObject);
                return null;
            }
        });
    }

    /**
     * У папки объекта верхнего уровня есть свой mdo — резолвим по нему. У папки дочернего
     * объекта (форма/команда/макет внутри {@code Forms} / {@code Commands} / {@code Templates})
     * mdo нет — там полное имя строится прямо по пути папки
     * ({@link GitChangedFileMenuHook#resolveEObjectForResource}).
     */
    private static EObject resolveEObjectFromSelection(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
            return null;

        IResource resource = NavigatorResourceResolver.resolveFirst(structured);
        if (resource instanceof IFile file)
            return GitChangedFileMenuHook.resolveEObject(file);
        if (!(resource instanceof IFolder folder))
            return null;

        IFile mdoFile = NavigatorResourceResolver.findMdoFileInFolder(folder);
        if (mdoFile != null)
            return GitChangedFileMenuHook.resolveEObject(mdoFile);

        return GitChangedFileMenuHook.resolveEObjectForResource(folder);
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
            private final List<MenuItem> added = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                ISelection selection = viewer.getSelection();
                EObject eObject = resolveEObjectFromSelection(selection);
                if (eObject == null)
                    return;

                MenuItem openItem = ComfortSubmenuHelper.createSortedMenuItem(
                    comfortSub, SWT.PUSH, ITEM_TEXT_OPEN);
                openItem.setToolTipText(ITEM_TOOLTIP_OPEN);
                openItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        GitChangedFileMenuHook.openInEditor(eObject, null, comfortSub.getShell());
                    }
                });
                added.add(openItem);

                MenuItem revealItem = ComfortSubmenuHelper.createSortedMenuItem(
                    comfortSub, SWT.PUSH, ITEM_TEXT_REVEAL);
                revealItem.setToolTipText(ITEM_TOOLTIP_REVEAL);
                revealItem.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        NavigatorReveal.revealAndActivateIfHidden(eObject);
                    }
                });
                added.add(revealItem);
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

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer ? (CommonViewer) viewer : null;
    }
}
