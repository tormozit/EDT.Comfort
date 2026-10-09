package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListenerWithChecks;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandler2;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
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
import org.eclipse.ui.ISources;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
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
    private static final String COMPARE_COMMAND_ID =
        "com._1c.g5.v8.dt.compare.ui.openCompareWizard"; //$NON-NLS-1$
    private static final String SELECTION_ORDER = "tormozit.navigatorProjectSelectionOrder"; //$NON-NLS-1$
    private static boolean compareListenerInstalled;
    private static IHandler nativeCompareHandler;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            installCompareSelectionOrder(workbench);
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
        var viewer = navigator.getCommonViewer();
        var tree = viewer.getTree();
        if (tree.getData(SELECTION_ORDER) == null)
        {
            ProjectSelectionOrder order = new ProjectSelectionOrder();
            tree.setData(SELECTION_ORDER, order);
            order.update(viewer.getStructuredSelection());
            viewer.addSelectionChangedListener(event ->
            {
                if (event.getSelection() instanceof IStructuredSelection selection)
                    order.update(selection);
            });
        }
        Menu menu = tree.getMenu();
        if (menu == null || menu.isDisposed() || Boolean.TRUE.equals(menu.getData(HOOK_MARKER)))
            return;
        // Слушатель JFace уже зарегистрирован: к этому моменту EDT наполнила меню
        // для текущего выделения, включая доступность действий создания.
        menu.addListener(SWT.Show, event -> flattenSingleCreate(menu));
        menu.setData(HOOK_MARKER, Boolean.TRUE);
    }

    /** Порядок добавления проектов в выделение, независимо от сортировки дерева SWT. */
    private static final class ProjectSelectionOrder
    {
        private final List<IProject> projects = new ArrayList<>();

        void update(IStructuredSelection selection)
        {
            List<IProject> selected = new ArrayList<>();
            for (Object element : selection.toList())
                if (element instanceof IProject project)
                    selected.add(project);
            projects.removeIf(project -> !selected.contains(project));
            for (IProject project : selected)
                if (!projects.contains(project))
                    projects.add(project);
        }

        IStructuredSelection ordered(IStructuredSelection selection)
        {
            if (selection.size() < 2 || selection.size() > 3
                || selection.size() != projects.size()
                || !projects.containsAll(selection.toList()))
                return null;
            return new StructuredSelection(new ArrayList<>(projects));
        }
    }

    private static void installCompareSelectionOrder(IWorkbench workbench)
    {
        if (compareListenerInstalled)
            return;
        ICommandService service = workbench.getService(ICommandService.class);
        if (service == null)
            return;
        service.addExecutionListener(new IExecutionListenerWithChecks()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                if (!COMPARE_COMMAND_ID.equals(commandId))
                    return;
                if (!(HandlerUtil.getActivePart(event) instanceof CommonNavigator navigator)
                    || !Global.isNavigatorPart(navigator)
                    || !(event.getApplicationContext() instanceof IEvaluationContext context))
                    return;
                Object data = navigator.getCommonViewer().getTree().getData(SELECTION_ORDER);
                if (!(data instanceof ProjectSelectionOrder order))
                    return;
                IStructuredSelection selection = order.ordered(HandlerUtil.getCurrentStructuredSelection(event));
                if (selection == null)
                    return;
                Command command = event.getCommand();
                if (command.getHandler() == null || command.getHandler() instanceof OrderedCompareHandler)
                    return;
                command.setHandler(new OrderedCompareHandler(command, context, selection));
            }

            private void restore(String commandId)
            {
                if (COMPARE_COMMAND_ID.equals(commandId)
                    && service.getCommand(commandId).getHandler() instanceof OrderedCompareHandler handler)
                    handler.restore();
            }

            @Override public void postExecuteSuccess(String commandId, Object result) { restore(commandId); }
            @Override public void postExecuteFailure(String commandId, ExecutionException error) { restore(commandId); }
            @Override public void notHandled(String commandId, NotHandledException error) { restore(commandId); }
            @Override public void notEnabled(String commandId, NotEnabledException error) { restore(commandId); }
            @Override public void notDefined(String commandId, NotDefinedException error) { restore(commandId); }
        });
        compareListenerInstalled = true;
    }

    private static IHandler nativeCompareHandler() throws ExecutionException
    {
        if (nativeCompareHandler != null)
            return nativeCompareHandler;
        // Ровно тот defaultHandler и Guice-фабрика, которые объявлены самой EDT.
        // Command.getHandler() возвращает e4-прослойку, теряющую наш контекст.
        for (IConfigurationElement element : Platform.getExtensionRegistry()
            .getConfigurationElementsFor("org.eclipse.ui.commands")) //$NON-NLS-1$
        {
            if (!"command".equals(element.getName()) //$NON-NLS-1$
                || !COMPARE_COMMAND_ID.equals(element.getAttribute("id"))) //$NON-NLS-1$
                continue;
            try
            {
                Object handler = element.createExecutableExtension("defaultHandler"); //$NON-NLS-1$
                if (handler instanceof IHandler nativeHandler)
                {
                    nativeCompareHandler = nativeHandler;
                    return nativeHandler;
                }
            }
            catch (CoreException error)
            {
                throw new ExecutionException("Не удалось получить обработчик сравнения EDT", error); //$NON-NLS-1$
            }
        }
        throw new ExecutionException("Не найден обработчик сравнения EDT"); //$NON-NLS-1$
    }

    /** Подменяет контекст одного вызова, сохраняя штатный мастер EDT. */
    private static final class OrderedCompareHandler extends AbstractHandler
    {
        private final Command command;
        private final IHandler original;
        private final IEvaluationContext context;
        private final IStructuredSelection selection;

        OrderedCompareHandler(Command command, IEvaluationContext context, IStructuredSelection selection)
        {
            this.command = command;
            this.original = command.getHandler();
            this.context = context;
            this.selection = selection;
        }

        @Override public boolean isEnabled() { return original.isEnabled(); }
        @Override public boolean isHandled() { return original.isHandled(); }

        @Override
        public void setEnabled(Object evaluationContext)
        {
            if (original instanceof IHandler2 handler)
                handler.setEnabled(evaluationContext);
        }

        @Override
        public Object execute(ExecutionEvent event) throws ExecutionException
        {
            // Восстанавливаем до модального мастера: вложенные команды обычные.
            restore();
            IEvaluationContext orderedContext = new EvaluationContext(context, selection);
            orderedContext.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);
            try
            {
                IHandler handler = nativeCompareHandler();
                return handler.execute(new ExecutionEvent(command, event.getParameters(),
                    event.getTrigger(), orderedContext));
            }
            catch (Exception error)
            {
                throw new ExecutionException("Не удалось открыть сравнение проектов", error); //$NON-NLS-1$
            }
        }

        void restore()
        {
            if (command.getHandler() == this)
                command.setHandler(original);
        }
    }

    private static void flattenSingleCreate(Menu menu)
    {
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
