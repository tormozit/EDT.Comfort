package tormozit;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ITreeSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Возвращает текущую строку навигатора, если её выделение сняла не рука пользователя, а
 * перестроение дерева (наложение фильтра по подсистемам, перечитывание содержимого и т.п.).
 *
 * <p>Почему это нужно: штатный {@code NavigatorSubsystemsFilter.refreshProjects()} просто зовёт
 * {@code CommonViewer.refresh(project)}. JFace в {@code refresh()} пытается сохранить выделение,
 * но если в момент пересчёта фильтров выделенный объект их не проходит (фильтр только что включён,
 * данные индекса подтягиваются асинхронно), выделение становится пустым — и назад не возвращается,
 * даже когда объект снова виден. Своего кода в этом пути нет: выделение теряет сама EDT.
 *
 * <p>Отличие «снял пользователь» от «сняло дерево» — по времени последнего ввода в дерево
 * ({@link #USER_INPUT_WINDOW_MS}): клик по пустому месту или Escape гасят выделение сразу после
 * ввода, и такое снятие мы уважаем. Восстановление идёт через {@code setSelection(..., true)} —
 * reveal сам разворачивает родителей пути, иначе показать строку невозможно. Если объект после
 * фильтрации реально исчез, {@code setSelection} ничего не найдёт и попытки просто закончатся.
 */
public final class NavigatorCurrentRowKeeper implements IStartup
{
    /** Снятие выделения в пределах этого окна после ввода в дерево считается действием пользователя. */
    private static final long USER_INPUT_WINDOW_MS = 500;

    /** Перестроение дерева асинхронное — пробуем вернуть строку несколько раз, с нарастающей паузой. */
    private static final int[] RESTORE_DELAYS_MS = { 0, 100, 300, 700, 1500 };

    private static final String STATE_KEY = "comfort.navigator.currentRowKeeper"; //$NON-NLS-1$

    /** Последняя непустая строка навигатора и время последнего ввода пользователя в дерево. */
    private static final class State
    {
        TreePath[] paths;
        long lastUserInputAt;
    }

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            IWorkbench workbench = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench() : null;
            if (workbench == null)
                return;
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
                hookWindow(window);
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
                scheduleInstall(ref.getView(false), 0);
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { scheduleFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { scheduleFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { scheduleFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void scheduleFromRef(IWorkbenchPartReference ref)
    {
        IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
        if (part instanceof IViewPart view)
            scheduleInstall(view, 0);
    }

    private static void scheduleInstall(IViewPart view, int attempt)
    {
        if (!isNavigatorView(view) || attempt > 20)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(attempt == 0 ? 0 : 200, () -> {
            if (!install(view))
                scheduleInstall(view, attempt + 1);
        });
    }

    /** Навигатор EDT или Project Explorer — общий признак, см. {@link Global#isNavigatorPart}. */
    private static boolean isNavigatorView(Object part)
    {
        return part instanceof IWorkbenchPart workbenchPart && Global.isNavigatorPart(workbenchPart);
    }

    private static boolean install(IViewPart navigator)
    {
        Object viewerObj = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof CommonViewer viewer))
            return false;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return false;
        if (tree.getData(STATE_KEY) != null)
            return true;

        State state = new State();
        tree.setData(STATE_KEY, state);

        Listener inputListener = event -> state.lastUserInputAt = System.currentTimeMillis();
        tree.addListener(SWT.MouseDown, inputListener);
        tree.addListener(SWT.MouseUp, inputListener);
        tree.addListener(SWT.KeyDown, inputListener);

        viewer.addSelectionChangedListener(event -> onSelectionChanged(viewer, state, event.getSelection()));
        return true;
    }

    private static void onSelectionChanged(CommonViewer viewer, State state, ISelection selection)
    {
        if (selection instanceof ITreeSelection treeSelection && !treeSelection.isEmpty())
        {
            state.paths = treeSelection.getPaths();
            return;
        }
        if (state.paths == null || state.paths.length == 0)
            return;
        if (System.currentTimeMillis() - state.lastUserInputAt < USER_INPUT_WINDOW_MS)
        {
            state.paths = null; // выделение снял пользователь — не навязываться
            return;
        }
        scheduleRestore(viewer, state, state.paths, 0);
    }

    private static void scheduleRestore(CommonViewer viewer, State state, TreePath[] paths, int attempt)
    {
        if (paths == null || paths.length == 0 || attempt >= RESTORE_DELAYS_MS.length)
            return;
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        tree.getDisplay().timerExec(RESTORE_DELAYS_MS[attempt], () -> {
            if (tree.isDisposed() || state.paths != paths)
                return; // появилась новая текущая строка — не мешать
            if (!viewer.getSelection().isEmpty())
                return;
            if (System.currentTimeMillis() - state.lastUserInputAt < USER_INPUT_WINDOW_MS)
                return; // пользователь уже работает с деревом — не перебивать
            viewer.setSelection(new TreeSelection(paths), true);
            if (viewer.getSelection().isEmpty())
                scheduleRestore(viewer, state, paths, attempt + 1);
        });
    }
}
