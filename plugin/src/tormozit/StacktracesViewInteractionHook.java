package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
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
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.stacktraces.model.IStacktrace;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceElement;
import com._1c.g5.v8.dt.stacktraces.model.IStacktraceError;

/**
 * Доработки панели «Трассировки стека»:
 * <ul>
 * <li>двойной клик по строке узла причины ({@link IStacktraceError}) — берёт причину ошибки
 * трассировки и запускает штатное действие EDT «Добавить точку останова по исключению» (открывшийся
 * диалог «Остановка по ошибке» сам подставит причину, см. {@link ExceptionSelectionDialogHook#setPendingReason},
 * без буфера обмена). Только для узла причины, не для строк кадров стека ({@code IStacktraceFrame})
 * — у тех двойной клик уже штатно переходит к исходнику в модуле, вешать туда ещё и это окно поверх
 * — мешать навигации по коду;</li>
 * <li>Ctrl+C — копирует текст текущей (выделенной) строки дерева, а не всю трассировку целиком
 * (штатное {@code org.eclipse.ui.edit.copy} этой панели — {@code CopyStacktraceHandler} — кладёт
 * в буфер весь дамп через {@code IStacktracesClipboardSupport.putStacktrace}).</li>
 * </ul>
 *
 * <p>Панель многостраничная (по вкладке на трассировку, {@code MultiPageViewPart}), поэтому двойной
 * клик — общий {@code SWT.MouseDoubleClick} фильтр Display (с проверкой, что активная часть
 * workbench — именно эта панель; двойной клик по строке сам активирует часть), а не привязка
 * к дереву конкретной страницы. Копирование — переопределение обработчика команды на самом view
 * ({@code IViewSite.getService(IHandlerService.class).activateHandler(...)}, как
 * {@code CreateDebuggerBreakpoints.installGlobalCopyHandler}) — активация на этом уровне перебивает
 * штатный обработчик, зарегистрированный EDT через {@code org.eclipse.ui.handlers}.
 */
public final class StacktracesViewInteractionHook implements IStartup
{
    private static final String VIEW_ID = "com._1c.g5.v8.dt.stacktraces.ui.StacktracesView"; //$NON-NLS-1$
    private static final java.util.Set<IViewPart> COPY_HANDLER_INSTALLED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static final String ADD_ACTION_BUNDLE = "com._1c.g5.v8.dt.debug.ui"; //$NON-NLS-1$
    private static final String ADD_ACTION_CLASS =
            "com._1c.g5.v8.dt.internal.debug.ui.actions.AddBslExceptionBreakpointAction"; //$NON-NLS-1$
    private static final String STOP_ON_ERROR_TITLE = "Остановка по ошибке"; //$NON-NLS-1$
    private static final String NO_REASON_MESSAGE =
            "В выбранной трассировке не найдено описания ошибки"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            installDoubleClick(Display.getDefault());
            hookWindowsForCopy();
        });
    }

    // -----------------------------------------------------------------------
    // Двойной клик по строке причины
    // -----------------------------------------------------------------------

    private static void installDoubleClick(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDoubleClick, event ->
        {
            if (!(event.widget instanceof Tree tree) || tree.isDisposed())
                return;
            if (!isStacktracesViewActive())
                return;
            IStacktraceElement element = resolveErrorRow(tree);
            if (element != null)
                triggerStopOnException(element);
        });
    }

    private static boolean isStacktracesViewActive()
    {
        IWorkbenchPart part = activePart();
        return part != null && VIEW_ID.equals(part.getSite().getId());
    }

    private static IStacktraceElement resolveErrorRow(Tree tree)
    {
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return null;
        return selection[0].getData() instanceof IStacktraceError element ? element : null;
    }

    /** Причина ошибки трассировки, к которой относится {@code element} → диалог «Остановка по ошибке». */
    private static void triggerStopOnException(IStacktraceElement element)
    {
        String errorText = findErrorText(element);
        String reason = BreakpointListHook.firstLine(errorText);
        if (reason.isEmpty())
        {
            ToastNotification.show(STOP_ON_ERROR_TITLE, NO_REASON_MESSAGE, 4_000);
            return;
        }

        ExceptionSelectionDialogHook.setPendingReason(reason);
        runAddExceptionBreakpointAction();
    }

    /** Текст ошибки трассировки, к которой относится {@code element} (см. {@link IStacktraceError}). */
    private static String findErrorText(IStacktraceElement element)
    {
        IStacktrace root = element.getStacktrace();
        if (root == null)
            return null;
        for (IStacktraceElement child : root.getChilden())
        {
            if (child instanceof IStacktraceError errorNode)
                return errorNode.getName();
        }
        return null;
    }

    /**
     * Штатное действие «Добавить точку останова по исключению» не привязано ни к одной команде
     * EDT (нет {@code definitionId}) — открываем тот же диалог, что и оно, напрямую его вызовом
     * через рефлексию (как {@code CreateDebuggerBreakpoints.resolveFactory()} для {@code DebugCorePlugin}).
     */
    private static void runAddExceptionBreakpointAction()
    {
        try
        {
            Bundle bundle = Platform.getBundle(ADD_ACTION_BUNDLE);
            if (bundle == null)
                return;
            Class<?> actionClass = bundle.loadClass(ADD_ACTION_CLASS);
            Object action = actionClass.getDeclaredConstructor().newInstance();
            Global.invokeVoid(action, "run", new Object[] { null }); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Global.log("StacktracesViewInteraction", "runAddExceptionBreakpointAction: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -----------------------------------------------------------------------
    // Ctrl+C — текст текущей строки, а не вся трассировка
    // -----------------------------------------------------------------------

    private static void hookWindowsForCopy()
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
                tryInstallCopy(ref.getView(false));
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partVisible(IWorkbenchPartReference ref) { tryFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partActivated(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}

            private void tryFromRef(IWorkbenchPartReference ref)
            {
                tryInstallCopy(ref != null ? ref.getPart(false) : null);
            }
        });
    }

    private static void tryInstallCopy(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart view) || !VIEW_ID.equals(view.getViewSite().getId()))
            return;
        if (!COPY_HANDLER_INSTALLED.add(view))
            return;

        IHandlerService handlerService = view.getSite().getService(IHandlerService.class);
        if (handlerService == null)
        {
            COPY_HANDLER_INSTALLED.remove(view);
            return;
        }

        handlerService.activateHandler("org.eclipse.ui.edit.copy", new AbstractHandler() //$NON-NLS-1$
        {
            @Override
            public Object execute(ExecutionEvent event)
            {
                copyActiveRow();
                return null;
            }
        });
    }

    private static void copyActiveRow()
    {
        Display display = Display.getCurrent();
        Control focus = display != null ? display.getFocusControl() : null;
        if (!(focus instanceof Tree tree) || tree.isDisposed())
            return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;
        String text = selection[0].getText();
        if (text == null || text.isBlank())
            return;

        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
    }

    private static IWorkbenchPart activePart()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        return page != null ? page.getActivePart() : null;
    }
}
