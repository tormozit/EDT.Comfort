package tormozit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Дорабатывает штатное окно EGit «Результат получения изменений: …»
 * ({@code org.eclipse.egit.ui.internal.fetch.FetchResultDialog}) — issue #108.
 *
 * <p>Само окно EGit уже открывает немодальным ({@code setShellStyle(… & ~SWT.APPLICATION_MODAL
 * | SWT.MODELESS)}, {@code setBlockOnOpen(false)}), поэтому демодализация тут не нужна. Хук
 * добавляет то, чего в окне нет:
 * <ul>
 * <li>двойной клик по строке ветки — показ этой ветки в панели «Репозитории Git» (панель
 * открывается, если закрыта, и активируется, если вытеснена другой вкладкой; видимую панель
 * из фокуса не выдёргиваем — правило команды «Показать в навигаторе»);</li>
 * <li>контекстное меню на строке ветки со штатными командами EGit (Извлечь, Слить,
 * Перебазировать, Создать/Настроить/Удалить ветку, Отправить, Синхронизировать).</li>
 * </ul>
 *
 * <p>Команды выполняются штатными обработчиками EGit через
 * {@link IHandlerService#executeCommandInContext}: в снимок контекста подставляется выделение
 * из одного узла дерева панели «Репозитории Git» ({@code RefNode}). Это работает потому, что
 * {@code LegacyHandlerService.executeCommandInContext} создаёт дочерний {@code IEclipseContext}
 * и переносит в него переменные переданного контекста — по ним заново вычисляются и
 * {@code activeWhen} обработчика, и {@code HandlerUtil.getCurrentSelection} внутри
 * {@code RepositoriesViewCommandHandler.getSelectedNodes}. Доступность пунктов заранее НЕ
 * вычисляется: если обработчик команды в этом контексте не найден или отключён, показывается
 * тост, а не молчаливое ничего.
 *
 * <p>Узел ветки ищется обходом дерева самой панели «Репозитории Git» (её content provider), а не
 * сборкой цепочки {@code RepositoryNode → BranchesNode → RemoteTrackingNode → RefNode} вручную:
 * обход одинаково работает и при плоском, и при иерархическом показе веток
 * ({@code BranchHierarchyNode}), и для меток.
 */
public final class FetchResultDialogHook implements IStartup
{
    private static final String TAG = "FetchResultDialog"; //$NON-NLS-1$

    private static final String DIALOG_CLASS_SUFFIX = "FetchResultDialog"; //$NON-NLS-1$
    private static final String ADAPTER_CLASS_SUFFIX = "FetchResultTable$FetchResultAdapter"; //$NON-NLS-1$

    private static final String REPOSITORIES_VIEW_ID = "org.eclipse.egit.ui.RepositoriesView"; //$NON-NLS-1$
    private static final String EGIT_UI_BUNDLE = "org.eclipse.egit.ui"; //$NON-NLS-1$

    private static final String HOOKED_KEY = "tormozit.fetchResultDialogHooked"; //$NON-NLS-1$

    /** Типы узлов панели «Репозитории Git», внутрь которых имеет смысл спускаться в поиске ссылки. */
    private static final List<String> CONTAINER_TYPES = List.of("REPO", "REPOGROUP", "BRANCHES", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "LOCAL", "REMOTETRACKING", "BRANCHHIERARCHY", "TAGS"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Ограничитель обхода дерева панели: репозиторий → Ветки → Отслеживание удалённых → ссылка,
     * плюс запас на иерархический показ веток по каталогам.
     */
    private static final int MAX_DEPTH = 8;

    /**
     * Пункт контекстного меню: идентификатор штатной команды EGit и ключ её надписи в
     * {@code plugin.properties} бандла {@code org.eclipse.egit.ui} (та же надпись, что в меню
     * панели «Репозитории Git»). {@code null} вместо команды — разделитель.
     */
    private record BranchCommand(String commandId, String labelKey, String fallbackLabel)
    {}

    private static final BranchCommand SEPARATOR = new BranchCommand(null, null, null);

    private static final BranchCommand[] BRANCH_COMMANDS = {
        new BranchCommand("org.eclipse.egit.ui.CheckoutCommand", "RepoViewCheckout.label", //$NON-NLS-1$ //$NON-NLS-2$
            "&Извлечь (checkout)"), //$NON-NLS-1$
        new BranchCommand("org.eclipse.egit.ui.team.Merge", "MergeAction_label", "&Слить (merge)..."), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new BranchCommand("org.eclipse.egit.ui.team.Rebase", "RebaseAction_label", "Перебазировать"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        SEPARATOR,
        new BranchCommand("org.eclipse.egit.ui.RepositoriesViewCreateBranch", //$NON-NLS-1$
            "RepoViewCreateBranch.label", "Создать ветку..."), //$NON-NLS-1$ //$NON-NLS-2$
        new BranchCommand("org.eclipse.egit.ui.RepositoriesViewConfigureBranch", //$NON-NLS-1$
            "ConfigurBranchCommand.label", "Настроить ветку..."), //$NON-NLS-1$ //$NON-NLS-2$
        new BranchCommand("org.eclipse.ui.edit.delete", "RepoViewDeleteBranch.label", "Удалить ветку"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        SEPARATOR,
        new BranchCommand("org.eclipse.egit.ui.team.Push", "RepoViewPushBranch.label", //$NON-NLS-1$ //$NON-NLS-2$
            "Отправить ветку…"), //$NON-NLS-1$
        new BranchCommand("org.eclipse.egit.ui.team.Synchronize", "RepoViewSynchronize.label", //$NON-NLS-1$ //$NON-NLS-2$
            "&Синхронизировать с рабочим каталогом") //$NON-NLS-1$
    };

    @Override
    public void earlyStartup()
    {
        // earlyStartup вызывается НЕ в UI-потоке — Display трогаем только через asyncExec.
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = FetchResultDialogHook::handleShellEvent;
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Resize, listener);
        Global.log(TAG, "install Show + Resize filters"); //$NON-NLS-1$
    }

    // --- перехват окна ------------------------------------------------------------------------

    private static void handleShellEvent(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;
        if (Boolean.TRUE.equals(shell.getData(HOOKED_KEY)))
            return;

        Object dialog = resolveDialog(shell);
        if (dialog == null)
            return;

        Tree tree = findTree(shell);
        if (tree == null)
            return; // дерево ещё не создано — дождёмся следующего события

        shell.setData(HOOKED_KEY, Boolean.TRUE);
        Repository repo = Global.getField(dialog, "localDb") instanceof Repository r ? r : null; //$NON-NLS-1$
        installOnTree(tree, repo);
        Global.log(TAG, "hooked, repo=" //$NON-NLS-1$
            + (repo == null ? "?" : repo.getDirectory())); //$NON-NLS-1$
    }

    private static Object resolveDialog(Shell shell)
    {
        for (String key : new String[] { null, "org.eclipse.jface.window.Window", //$NON-NLS-1$
            "org.eclipse.jface.dialogs.Dialog.dialog" }) //$NON-NLS-1$
        {
            Object data = key == null ? shell.getData() : shell.getData(key);
            if (data != null && data.getClass().getName().endsWith(DIALOG_CLASS_SUFFIX))
                return data;
        }
        return null;
    }

    private static Tree findTree(Control control)
    {
        if (control instanceof Tree tree)
            return tree;
        if (control instanceof Composite composite && !composite.isDisposed())
        {
            for (Control child : composite.getChildren())
            {
                Tree found = findTree(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    // --- двойной клик и контекстное меню ------------------------------------------------------

    private static void installOnTree(Tree tree, Repository repo)
    {
        tree.addListener(SWT.MouseDoubleClick, event -> {
            String refName = refNameAt(tree, new Point(event.x, event.y));
            if (refName != null)
                revealInRepositoriesView(repo, refName, true);
        });

        Menu menu = new Menu(tree);
        tree.setMenu(menu);

        tree.addListener(SWT.MenuDetect, event -> {
            // e.x/e.y — координаты дисплея; в дереве выбираем строку под курсором, чтобы меню
            // относилось именно к ней, а не к прежнему выделению.
            Point local = tree.toControl(event.x, event.y);
            TreeItem item = tree.getItem(local);
            if (item != null)
                tree.setSelection(item);
            event.doit = refName(item) != null;
        });

        menu.addListener(SWT.Show, event -> fillMenu(menu, tree, repo));
    }

    private static void fillMenu(Menu menu, Tree tree, Repository repo)
    {
        for (MenuItem item : menu.getItems())
            item.dispose();

        TreeItem[] selection = tree.getSelection();
        String refName = selection.length == 1 ? refName(selection[0]) : null;
        if (refName == null)
            return;

        MenuItem reveal = new MenuItem(menu, SWT.PUSH);
        reveal.setText("Показать в панели «" + repositoriesViewLabel() + "»"); //$NON-NLS-1$ //$NON-NLS-2$
        reveal.addListener(SWT.Selection, e -> revealInRepositoriesView(repo, refName, true));
        menu.setDefaultItem(reveal);

        new MenuItem(menu, SWT.SEPARATOR);

        for (BranchCommand command : BRANCH_COMMANDS)
        {
            if (command == SEPARATOR)
            {
                new MenuItem(menu, SWT.SEPARATOR);
                continue;
            }

            MenuItem item = new MenuItem(menu, SWT.PUSH);
            item.setText(commandLabel(command));
            item.addListener(SWT.Selection, e -> runCommand(command, repo, refName));
        }
    }

    /** Имя ссылки (например {@code refs/remotes/origin/master}) строки дерева, или {@code null}. */
    private static String refNameAt(Tree tree, Point point)
    {
        return refName(tree.getItem(point));
    }

    private static String refName(TreeItem item)
    {
        if (item == null || item.isDisposed())
            return null;

        Object data = item.getData();
        if (data == null || !data.getClass().getName().endsWith(ADAPTER_CLASS_SUFFIX))
            return null;

        if (!(Global.getField(data, "update") instanceof TrackingRefUpdate update)) //$NON-NLS-1$
            return null;

        String local = update.getLocalName();
        return local == null || local.isBlank() ? null : local;
    }

    // --- показ ветки в панели «Репозитории Git» ------------------------------------------------

    private static void revealInRepositoriesView(Repository repo, String refName, boolean activateIfHidden)
    {
        IWorkbenchPage page = activePage();
        if (page == null || repo == null)
            return;

        IViewPart view = page.findView(REPOSITORIES_VIEW_ID);
        try
        {
            if (view == null)
                view = page.showView(REPOSITORIES_VIEW_ID); // делает панель видимой и активной
            else if (activateIfHidden && !page.isPartVisible(view))
                page.activate(view);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "showView", e); //$NON-NLS-1$
            return;
        }

        if (!(view instanceof CommonNavigator navigator))
            return;

        CommonViewer viewer = navigator.getCommonViewer();
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed())
            return;

        Object node = findRefNode(viewer, repo, refName);
        if (node == null)
        {
            Global.log(TAG, "ref not found in repositories view: " + refName); //$NON-NLS-1$
            return;
        }
        viewer.setSelection(new StructuredSelection(node), true);
    }

    private static Object findRefNode(CommonViewer viewer, Repository repo, String refName)
    {
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider provider))
            return null;

        Object[] roots;
        try
        {
            roots = provider.getElements(viewer.getInput());
        }
        catch (Exception e)
        {
            Global.logError(TAG, "getElements", e); //$NON-NLS-1$
            return null;
        }

        List<Object> level = new ArrayList<>();
        for (Object root : roots)
        {
            if (belongsTo(root, repo))
                level.add(root);
        }

        for (int depth = 0; depth < MAX_DEPTH && !level.isEmpty(); depth++)
        {
            List<Object> next = new ArrayList<>();
            for (Object node : level)
            {
                if (refName.equals(refNameOf(node)))
                    return node;
                if (CONTAINER_TYPES.contains(nodeType(node)))
                    next.addAll(children(provider, node));
            }
            level = next;
        }
        return null;
    }

    private static List<Object> children(ITreeContentProvider provider, Object node)
    {
        try
        {
            Object[] children = provider.getChildren(node);
            return children == null ? List.of() : List.of(children);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "getChildren", e); //$NON-NLS-1$
            return List.of();
        }
    }

    /** Имя ссылки узла панели «Репозитории Git» ({@code RefNode}/{@code TagNode}), или {@code null}. */
    private static String refNameOf(Object node)
    {
        return Global.invoke(node, "getObject") instanceof Ref ref ? ref.getName() : null; //$NON-NLS-1$
    }

    private static String nodeType(Object node)
    {
        return Global.invoke(node, "getType") instanceof Enum<?> type ? type.name() : null; //$NON-NLS-1$
    }

    private static boolean belongsTo(Object node, Repository repo)
    {
        // Узел группы репозиториев своего репозитория не имеет — в него спускаемся всегда.
        if ("REPOGROUP".equals(nodeType(node))) //$NON-NLS-1$
            return true;

        if (!(Global.invoke(node, "getRepository") instanceof Repository nodeRepo)) //$NON-NLS-1$
            return false;

        File a = nodeRepo.getDirectory();
        File b = repo.getDirectory();
        return a != null && a.equals(b);
    }

    // --- выполнение штатных команд EGit --------------------------------------------------------

    private static void runCommand(BranchCommand command, Repository repo, String refName)
    {
        IWorkbenchPage page = activePage();
        if (page == null)
            return;

        // Обработчики EGit ищут панель через RepositoriesView.getView(event) — без неё возможен
        // отказ; создаём панель, не забирая фокус у окна результата.
        IViewPart view = page.findView(REPOSITORIES_VIEW_ID);
        try
        {
            if (view == null)
                view = page.showView(REPOSITORIES_VIEW_ID, null, IWorkbenchPage.VIEW_VISIBLE);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "showView (visible)", e); //$NON-NLS-1$
        }

        Object node = view instanceof CommonNavigator navigator && navigator.getCommonViewer() != null
            ? findRefNode(navigator.getCommonViewer(), repo, refName) : null;
        if (node == null)
        {
            ToastNotification.show("Результат получения изменений", //$NON-NLS-1$
                "Ветка «" + shortName(refName) + "» не найдена в панели «" //$NON-NLS-1$ //$NON-NLS-2$
                    + repositoriesViewLabel() + "»."); //$NON-NLS-1$
            return;
        }

        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
        if (commands == null || handlers == null)
            return;

        Command cmd = commands.getCommand(command.commandId());
        if (cmd == null || !cmd.isDefined())
        {
            unavailable(command, refName);
            return;
        }

        StructuredSelection selection = new StructuredSelection(node);
        IEvaluationContext context = handlers.createContextSnapshot(false);
        context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);
        context.addVariable(ISources.ACTIVE_MENU_SELECTION_NAME, selection);

        try
        {
            handlers.executeCommandInContext(new ParameterizedCommand(cmd, null), null, context);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "execute " + command.commandId(), e); //$NON-NLS-1$
            unavailable(command, refName);
        }
    }

    private static void unavailable(BranchCommand command, String refName)
    {
        ToastNotification.show("Результат получения изменений", //$NON-NLS-1$
            "Команда «" + commandLabel(command).replace("&", "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "» для ветки «" + shortName(refName) + "» сейчас недоступна."); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String shortName(String refName)
    {
        return Repository.shortenRefName(refName);
    }

    // --- надписи ------------------------------------------------------------------------------

    private static String commandLabel(BranchCommand command)
    {
        String label = resourceString(command.labelKey());
        return label == null ? command.fallbackLabel() : label;
    }

    /** Надпись из {@code plugin.properties} бандла EGit — та же, что в меню панели репозиториев. */
    private static String resourceString(String key)
    {
        try
        {
            org.osgi.framework.Bundle bundle = Platform.getBundle(EGIT_UI_BUNDLE);
            if (bundle == null)
                return null;
            String value = Platform.getResourceString(bundle, "%" + key); //$NON-NLS-1$
            return value == null || value.isBlank() || value.equals("%" + key) ? null : value; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String repositoriesViewLabel()
    {
        try
        {
            var descriptor = PlatformUI.getWorkbench().getViewRegistry().find(REPOSITORIES_VIEW_ID);
            if (descriptor != null && descriptor.getLabel() != null && !descriptor.getLabel().isBlank())
                return descriptor.getLabel();
        }
        catch (Exception ignored)
        {
            // остаётся запасная надпись
        }
        return "Репозитории Git"; //$NON-NLS-1$
    }

    private static IWorkbenchPage activePage()
    {
        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            return window == null ? null : window.getActivePage();
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
