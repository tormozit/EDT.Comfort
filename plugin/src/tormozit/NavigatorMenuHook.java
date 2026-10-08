package tormozit;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

/** Упрощает контекстное меню навигатора: единственная команда «Создать» без подменю. */
public final class NavigatorMenuHook implements IStartup
{
    private static final String HOOK_MARKER = "tormozit.navigatorMenuHook"; //$NON-NLS-1$
    private static final String NEW_MENU_ID = "new.menu"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        for (IWorkbenchPage page : window.getPages())
            for (IViewReference reference : page.getViewReferences())
                tryHook(reference.getView(false));
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference reference) { tryHook(reference.getPart(false)); }
            @Override public void partVisible(IWorkbenchPartReference reference) { tryHook(reference.getPart(false)); }
            @Override public void partActivated(IWorkbenchPartReference reference) { tryHook(reference.getPart(false)); }
            @Override public void partBroughtToTop(IWorkbenchPartReference reference) {}
            @Override public void partClosed(IWorkbenchPartReference reference) {}
            @Override public void partDeactivated(IWorkbenchPartReference reference) {}
            @Override public void partHidden(IWorkbenchPartReference reference) {}
            @Override public void partInputChanged(IWorkbenchPartReference reference) {}
        });
    }

    private static void tryHook(IWorkbenchPart part)
    {
        if (!Global.isNavigatorPart(part) || !(part instanceof CommonNavigator navigator))
            return;
        Menu menu = navigator.getCommonViewer().getTree().getMenu();
        if (menu == null || menu.isDisposed() || Boolean.TRUE.equals(menu.getData(HOOK_MARKER)))
            return;
        // Слушатель JFace уже зарегистрирован: к этому моменту EDT наполнила меню
        // для текущего выделения, включая доступность действий создания.
        menu.addListener(SWT.Show, event -> flattenSingleCreate(menu));
        menu.setData(HOOK_MARKER, Boolean.TRUE);
    }

    private static void flattenSingleCreate(Menu menu)
    {
        Global.tempLog("navigator-menu", "show items=" + menu.getItemCount()); //$NON-NLS-1$ //$NON-NLS-2$
        for (MenuItem item : menu.getItems())
        {
            if (!(item.getData() instanceof MenuManager submenu)
                || !NEW_MENU_ID.equals(submenu.getId())
                || !(submenu.getParent() instanceof IMenuManager parent))
                continue;

            // NewActionProvider.fillContextMenu заполняет модель сразу; разделители
            // и маркеры групп не являются вариантами создания.
            IContributionItem only = null;
            for (IContributionItem child : submenu.getItems())
            {
                if (!child.isVisible() || child.isSeparator() || child.isGroupMarker())
                    continue;
                if (only != null)
                    return;
                only = child;
            }
            if (!(only instanceof ActionContributionItem actionItem)
                || actionItem.getAction().getStyle() != IAction.AS_PUSH_BUTTON)
                return;

            // Новый contribution использует то же действие EDT. Сам исходный
            // contribution переносить нельзя: он владеет виджетом подменю.
            submenu.updateAll(true);
            ImageData imageData = null;
            if (actionItem.getWidget() instanceof MenuItem original)
            {
                Image image = original.getImage();
                if (image != null && !image.isDisposed())
                    imageData = image.getImageData();
            }
            ActionContributionItem direct = new SingleCreateContribution(
                actionItem.getAction(), submenu.getMenuText(), imageData);
            Global.tempLog("navigator-menu", "flatten action=" + actionItem.getAction().getId() //$NON-NLS-1$ //$NON-NLS-2$
                + " text=" + actionItem.getAction().getText() //$NON-NLS-1$
                + " enabled=" + actionItem.getAction().isEnabled() //$NON-NLS-1$
                + " image=" + (imageData != null)); //$NON-NLS-1$
            parent.insertBefore(NEW_MENU_ID, direct);
            parent.remove(submenu);
            // Меняем только этот SWT-пункт. parent.update удалил бы пункты,
            // добавленные другими хуками прямо в SWT-меню (включая «Комфорт»).
            direct.fill(menu, menu.indexOf(item));
            item.dispose();
            submenu.dispose();
            return;
        }
    }

    /** Сохраняет подпись «Создать» и штатное действие, включая обработку событий JFace. */
    private static final class SingleCreateContribution extends ActionContributionItem
    {
        private final String menuText;
        private final ImageData imageData;
        private Image preservedImage;

        SingleCreateContribution(IAction action, String menuText, ImageData imageData)
        {
            super(action);
            this.menuText = menuText;
            this.imageData = imageData;
            setId(NEW_MENU_ID);
        }

        @Override
        public void update(String propertyName)
        {
            super.update(propertyName);
            if (getWidget() instanceof MenuItem item && !item.isDisposed())
            {
                if (imageData != null)
                {
                    if (preservedImage == null || preservedImage.isDisposed())
                    {
                        Image image = new Image(item.getDisplay(), imageData);
                        preservedImage = image;
                        item.addListener(SWT.Dispose, event -> image.dispose());
                    }
                    // Копия не зависит от ресурса уничтожаемого подменю.
                    item.setImage(preservedImage);
                }
                // Акселератор штатного действия остаётся виден справа от подписи.
                String text = item.getText();
                int tab = text.indexOf('\t');
                item.setText(menuText + (tab < 0 ? "" : text.substring(tab))); //$NON-NLS-1$
                ComfortSubmenuHelper.setMenuItemTooltip(item,
                    "Создать: " + getAction().getText().replace("&", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
    }
}
