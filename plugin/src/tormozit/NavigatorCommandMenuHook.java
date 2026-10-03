package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
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

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.shared.MdUiSharedImages;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Пункты «Функциональные опции» и «Права» в контекстном меню команды объекта в навигаторе:
 * открывают редактор объекта-владельца и делают то же, что одноимённые пункты меню команды
 * на вкладке «Команды» этого редактора ({@link MdEditorAttributeMenuHook}).
 */
public final class NavigatorCommandMenuHook implements IStartup
{
    private static final String TAG = "NavigatorCommandMenu"; //$NON-NLS-1$

    private static final String HOOK_MARKER = "tormozit.navigatorCommandMenuHook"; //$NON-NLS-1$

    private static final String ITEM_FO = "Функциональные опции"; //$NON-NLS-1$

    private static final String ITEM_RIGHTS = "Права"; //$NON-NLS-1$

    private static final String PROPERTIES_MENU_TEXT = "Свойства"; //$NON-NLS-1$

    private static final int MAX_ATTEMPTS = 40;

    private static final int RETRY_MS = 100;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
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
            private final List<MenuItem> added = new ArrayList<>(3);

            @Override
            public void menuShown(MenuEvent e)
            {
                removeAdded();
                ISelection selection = viewer.getSelection();
                if (!(selection instanceof IStructuredSelection structured) || structured.size() != 1)
                    return;
                EObject command = NavigatorElementModels.resolveEObject(structured.getFirstElement());
                if (!MdEditorAttributeMenuHook.isObjectCommand(command) || command.eContainer() == null)
                    return;
                fillItems(menu, command, added);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                menu.getDisplay().asyncExec(this::removeAdded);
            }

            private void removeAdded()
            {
                for (MenuItem item : added)
                {
                    if (!item.isDisposed())
                        item.dispose();
                }
                added.clear();
            }
        };
        menu.addMenuListener(listener);
        tree.setData(HOOK_MARKER, Boolean.TRUE);
        tree.addDisposeListener(ev ->
        {
            if (!menu.isDisposed())
                menu.removeMenuListener(listener);
        });
    }

    private static void fillItems(Menu menu, EObject command, List<MenuItem> added)
    {
        int index = insertIndex(menu);
        EObject owner = command.eContainer();

        added.add(new MenuItem(menu, SWT.SEPARATOR, index));

        MenuItem foItem = new MenuItem(menu, SWT.PUSH, index + 1);
        foItem.setText(ITEM_FO);
        Image foImage = MdEditorAttributeMenuHook.mdImage(MdUiSharedImages.OBJS_FUNCTIONAL_OPTION);
        if (foImage != null)
            foItem.setImage(foImage);
        ComfortSubmenuHelper.setMenuItemTooltip(foItem,
            "Открыть редактор объекта, перейти на вкладку «Функц. опции» и выделить эту команду"); //$NON-NLS-1$
        foItem.addListener(SWT.Selection, ev -> openAndReveal(owner, command, true));
        added.add(foItem);

        MenuItem rightsItem = new MenuItem(menu, SWT.PUSH, index + 2);
        rightsItem.setText(ITEM_RIGHTS);
        Image rightsImage = MdEditorAttributeMenuHook.mdImage(MdUiSharedImages.OBJS_ROLE);
        if (rightsImage != null)
            rightsItem.setImage(rightsImage);
        ComfortSubmenuHelper.setMenuItemTooltip(rightsItem,
            "Открыть редактор объекта, перейти на вкладку «Права» и отфильтровать по имени команды"); //$NON-NLS-1$
        rightsItem.addListener(SWT.Selection, ev -> openAndReveal(owner, command, false));
        added.add(rightsItem);
    }

    private static int insertIndex(Menu menu)
    {
        MenuItem[] items = menu.getItems();
        for (int i = 0; i < items.length; i++)
        {
            String text = items[i].getText();
            if (text == null)
                continue;
            int tab = text.indexOf('\t');
            if (tab >= 0)
                text = text.substring(0, tab);
            if (PROPERTIES_MENU_TEXT.equals(text.replace("&", "").trim())) //$NON-NLS-1$ //$NON-NLS-2$
                return i;
        }
        return items.length;
    }

    private static void openAndReveal(EObject owner, EObject command, boolean functionalOptions)
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return;
        IEditorPart part;
        try
        {
            part = new OpenHelper(page).openEditor(owner);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "open editor", e); //$NON-NLS-1$
            return;
        }
        if (part instanceof DtGranularEditor<?> editor)
            scheduleReveal(editor, command, functionalOptions, 0);
    }

    /** Страницы редактора могут появиться не сразу после открытия — ждём нужную вкладку. */
    private static void scheduleReveal(DtGranularEditor<?> editor, EObject command, boolean functionalOptions,
        int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        display.timerExec(attempt == 0 ? 0 : RETRY_MS, () ->
        {
            boolean ready = MdEditorAttributeMenuHook.findPage(editor, functionalOptions
                ? MdEditorAttributeMenuHook::isFunctionalOptionsPage
                : MdEditorAttributeMenuHook::isRightsPage) != null;
            if (!ready)
            {
                scheduleReveal(editor, command, functionalOptions, attempt + 1);
                return;
            }
            if (functionalOptions)
            {
                MdEditorAttributeMenuHook.revealOnFunctionalOptions(editor, command);
                return;
            }
            String name = MdEditorAttributeMenuHook.relativeName(command, editor.getModel());
            if (name != null && !name.isBlank())
                MdEditorAttributeMenuHook.revealOnRights(editor, name);
        });
    }
}
