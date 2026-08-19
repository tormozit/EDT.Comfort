package tormozit;

import java.util.Arrays;
import java.util.function.Function;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
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
 * Сброс на навигатор = «Показать в навигаторе».
 *
 * <p>Источник при {@code dragStart} вызывает {@link #begin(EObject)}; навигатор при наведении
 * показывает курсор перемещения и по сбросу выделяет этот объект. Источники: наборы объектов,
 * последние места, результаты поиска, дерево сравнения конфигураций.
 */
public final class NavigatorRevealDropHook implements IStartup
{
    private static final String DROP_MARKER = "tormozit.navigatorRevealDrop"; //$NON-NLS-1$
    private static final String DRAG_MARKER = "tormozit.navigatorRevealDragSource"; //$NON-NLS-1$
    private static volatile boolean hooksInstalled;
    private static volatile EObject pendingReveal;

    @Override
    public void earlyStartup()
    {
        scheduleInstall(0);
    }

    /**
     * Запомнить объект для сброса на навигатор. Вызывать из {@code dragStart};
     * {@code null} — жест не для навигатора (курсор «запрещено»).
     */
    static void begin(EObject eObject)
    {
        pendingReveal = eObject;
    }

    /** Сбросить запомненный объект. Вызывать из {@code dragFinished}. */
    static void end()
    {
        pendingReveal = null;
    }

    /**
     * Drag-source на таблице/дереве: {@link LocalSelectionTransfer} + {@link #begin(EObject)}.
     * Повторно вешает слушателя на уже существующий {@link DragSource} (дерево поиска EDT).
     */
    static void installViewerDrag(StructuredViewer viewer, Function<IStructuredSelection, EObject> resolve)
    {
        if (viewer == null || resolve == null)
            return;
        Control control = viewer.getControl();
        if (control == null || control.isDisposed() || Boolean.TRUE.equals(control.getData(DRAG_MARKER)))
            return;
        DragSource source;
        Object existing = control.getData(DND.DRAG_SOURCE_KEY);
        if (existing instanceof DragSource ds)
            source = ds;
        else
        {
            try
            {
                source = new DragSource(control, DND.DROP_COPY | DND.DROP_MOVE);
            }
            catch (RuntimeException | SWTError e)
            {
                Global.log("NavigatorRevealDrop: DragSource init error: " + e); //$NON-NLS-1$
                return;
            }
            source.setTransfer(new Transfer[] { LocalSelectionTransfer.getTransfer() });
        }
        ensureDragLocalSelectionTransfer(source);
        source.addDragListener(new DragSourceAdapter()
        {
            @Override
            public void dragStart(DragSourceEvent event)
            {
                IStructuredSelection selection = viewer.getStructuredSelection();
                if (selection == null || selection.isEmpty())
                {
                    event.doit = false;
                    return;
                }
                LocalSelectionTransfer.getTransfer().setSelection(selection);
                begin(resolve.apply(selection));
                event.doit = true;
            }

            @Override
            public void dragSetData(DragSourceEvent event)
            {
                LocalSelectionTransfer.getTransfer().setSelection(viewer.getStructuredSelection());
            }

            @Override
            public void dragFinished(DragSourceEvent event)
            {
                end();
            }
        });
        control.setData(DRAG_MARKER, Boolean.TRUE);
    }

    private static void scheduleInstall(int attempt)
    {
        Runnable install = () ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
            {
                if (attempt < 50)
                {
                    Display display = Display.getDefault();
                    if (display != null)
                        display.timerExec(200, () -> scheduleInstall(attempt + 1));
                }
                return;
            }
            installHooks();
        };
        Display display = Display.getDefault();
        if (display != null)
            display.asyncExec(install);
        else
            install.run();
    }

    private static void installHooks()
    {
        if (hooksInstalled)
            return;
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        hooksInstalled = true;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookWindow(window);
        wb.addWindowListener(new org.eclipse.ui.IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                if (window != null)
                    hookWindow(window);
            }
            @Override public void windowActivated(IWorkbenchWindow window) {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
            @Override public void windowClosed(IWorkbenchWindow window) {}
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
                    tryHook((IViewPart) view, 0);
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
            tryHook((IViewPart) part, 0);
    }

    private static boolean isNavigatorView(Object part)
    {
        if (!(part instanceof IViewPart viewPart))
            return false;
        String id = viewPart.getViewSite().getId();
        return Global.NAVIGATOR_VIEW_ID.equals(id)
                || part.getClass().getName().contains("internal.navigator.ui.Navigator"); //$NON-NLS-1$
    }

    private static void tryHook(IViewPart navigator, int attempt)
    {
        CommonViewer viewer = getCommonViewer(navigator);
        if (viewer == null)
        {
            if (attempt < 30)
            {
                Display display = Display.getDefault();
                if (display != null)
                    display.timerExec(150, () -> tryHook(navigator, attempt + 1));
            }
            return;
        }
        Tree tree = viewer.getTree();
        if (tree == null || tree.isDisposed())
            return;
        installDropTarget(tree);
    }

    private static void installDropTarget(Tree tree)
    {
        if (Boolean.TRUE.equals(tree.getData(DROP_MARKER)))
            return;
        DropTarget target = ensureDropTarget(tree);
        if (target == null)
            return;
        ensureLocalSelectionTransfer(target);
        target.addDropListener(new DropTargetAdapter()
        {
            @Override
            public void dragEnter(DropTargetEvent event)
            {
                updateRevealDropDetail(event);
            }

            @Override
            public void dragOperationChanged(DropTargetEvent event)
            {
                updateRevealDropDetail(event);
            }

            @Override
            public void dragOver(DropTargetEvent event)
            {
                updateRevealDropDetail(event);
            }

            @Override
            public void dropAccept(DropTargetEvent event)
            {
                // CommonDropAdapter.validateDrop для чужих типов даёт false и в dropAccept
                // ставит DROP_NONE — без перехвата drop не приходит.
                updateRevealDropDetail(event);
            }

            @Override
            public void drop(DropTargetEvent event)
            {
                EObject obj = pendingReveal;
                if (obj != null)
                    NavigatorReveal.revealAndActivateIfHidden(obj);
            }
        });
        tree.setData(DROP_MARKER, Boolean.TRUE);
    }

    private static void updateRevealDropDetail(DropTargetEvent event)
    {
        if (pendingReveal == null)
            return;
        preferLocalSelectionDataType(event);
        if ((event.operations & DND.DROP_MOVE) != 0)
            event.detail = DND.DROP_MOVE;
        else if ((event.operations & DND.DROP_COPY) != 0)
            event.detail = DND.DROP_COPY;
        else
            event.detail = DND.DROP_NONE;
    }

    private static void preferLocalSelectionDataType(DropTargetEvent event)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        if (event.currentDataType != null && local.isSupportedType(event.currentDataType))
            return;
        TransferData[] types = event.dataTypes;
        if (types == null)
            return;
        for (TransferData td : types)
        {
            if (local.isSupportedType(td))
            {
                event.currentDataType = td;
                return;
            }
        }
    }

    private static DropTarget ensureDropTarget(Tree tree)
    {
        Object existing = tree.getData(DND.DROP_TARGET_KEY);
        if (existing instanceof DropTarget target)
            return target;
        try
        {
            return new DropTarget(tree, DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_DEFAULT);
        }
        catch (RuntimeException | SWTError e)
        {
            Global.log("NavigatorRevealDrop: DropTarget init error: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void ensureLocalSelectionTransfer(DropTarget target)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        Transfer[] current = target.getTransfer();
        if (current != null)
        {
            for (Transfer t : current)
            {
                if (t == local)
                    return;
            }
            Transfer[] expanded = Arrays.copyOf(current, current.length + 1);
            expanded[current.length] = local;
            target.setTransfer(expanded);
        }
        else
            target.setTransfer(new Transfer[] { local });
    }

    private static void ensureDragLocalSelectionTransfer(DragSource source)
    {
        Transfer local = LocalSelectionTransfer.getTransfer();
        Transfer[] current = source.getTransfer();
        if (current != null)
        {
            for (Transfer t : current)
            {
                if (t == local)
                    return;
            }
            Transfer[] expanded = Arrays.copyOf(current, current.length + 1);
            expanded[current.length] = local;
            source.setTransfer(expanded);
        }
        else
            source.setTransfer(new Transfer[] { local });
    }

    private static CommonViewer getCommonViewer(IViewPart navigator)
    {
        Object viewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
        return viewer instanceof CommonViewer commonViewer ? commonViewer : null;
    }
}
