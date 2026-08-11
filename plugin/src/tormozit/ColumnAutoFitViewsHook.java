package tormozit;

import java.util.Set;

import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Растягивание колонок вместе с панелью ({@link ColumnAutoFit}) в панелях «Выражения» и «Выражения
 * встроенного языка» (issue #273): колонки там создаёт платформенный {@code InternalTreeModelViewer},
 * задавая ширины один раз при построении и не реагируя на ресайз панели.
 *
 * <p>Остальные места подключают {@link ColumnAutoFit} там, где дерево и так под рукой: окна инспектора
 * отладчика — в {@code DebugInspectorTreeEnhancement}, «Индексирование Git» — в
 * {@link GitStagingFilterHook} рядом с созданием колонок.
 *
 * <p>Подключение — на {@code partOpened}/{@code partVisible} и разовым проходом по уже открытым
 * панелям: дерево существует не раньше создания контролов панели, а панель может открыться и позже
 * старта. Повторные вызовы безопасны, {@link ColumnAutoFit#install} идемпотентен.
 */
public final class ColumnAutoFitViewsHook implements IStartup
{
    /** «Выражения встроенного языка». */
    private static final String BSL_EXPRESSIONS_VIEW_ID =
        "com._1c.g5.v8.dt.debug.ui.variables.BslExpressionsView"; //$NON-NLS-1$

    private static final Set<String> VIEW_IDS =
        Set.of(BSL_EXPRESSIONS_VIEW_ID, IDebugUIConstants.ID_EXPRESSION_VIEW);

    private static volatile boolean installed;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(ColumnAutoFitViewsHook::install);
    }

    private static void install()
    {
        if (installed)
            return;
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        installed = true;
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
            }
        });
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                tryHookPart(ref);
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                tryHookPart(ref);
            }
        });
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        for (IViewReference ref : page.getViewReferences())
            tryHookPart(ref);
    }

    private static void tryHookPart(IWorkbenchPartReference ref)
    {
        if (ref == null || !VIEW_IDS.contains(ref.getId()))
            return;
        if (!ComfortSettings.isImproveDebuggerWindowsEnabled())
            return;
        IWorkbenchPart part = ref.getPart(false);
        if (part == null)
            return;
        Tree tree = viewTree(part);
        // Без условия на число колонок: в панелях отладчика колонки строит платформа, и на момент
        // открытия панели (без активной сессии отладки) их ещё нет — подгонка дождётся их сама.
        if (tree != null && !tree.isDisposed())
            ColumnAutoFit.install(tree);
    }

    /** Дерево панели — только через её собственный viewer, без обхода контролов окна. */
    private static Tree viewTree(IWorkbenchPart part)
    {
        Object viewer = Global.invoke(part, "getViewer"); //$NON-NLS-1$
        if (viewer == null)
            return null;
        Object control = Global.invoke(viewer, "getControl"); //$NON-NLS-1$
        if (control instanceof Tree tree)
            return tree;
        Object treeObj = Global.invoke(viewer, "getTree"); //$NON-NLS-1$
        return treeObj instanceof Tree tree ? tree : null;
    }
}
