package tormozit;

import java.lang.reflect.Field;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Окно «Сбор данных рефакторинга» — рабочая кнопка «Отмена».
 * https://github.com/tormozit/EDT.Comfort/issues/353
 *
 * <p><b>Штатно</b> окно открывает {@code RefactoringUIHelper.initiateRefactoring(s)WithProgress}
 * ({@code com._1c.g5.v8.dt.refactoring.ui}) через {@code IProgressService.run(fork=true, cancelable=false, ...)}:
 * кнопка «Отмена» выключена. Прервать сам расчёт нельзя: основное время уходит в участника BSL
 * ({@code ConfigurationObjectRenameSupport.getChanges}), монитор которого создан на месте как
 * {@code new SubProgressMonitor(new NullProgressMonitor(), ...)}, а {@code interrupt()} на ожидании
 * {@code ForkJoinPool.invoke} и {@code waitDerivedDataComputation} ничего не даёт.
 *
 * <p><b>«Отмена»</b> прекращает ожидание: у потока JFace {@code ModalContext.ModalContextThread}
 * снимается {@code continueEventDispatching}, цикл {@code block()} выходит, {@code ModalContext.run}
 * возвращается без результата — окно закрывается, {@code initiateRefactoring(s)WithProgress} отдаёт
 * {@code null}, и {@code openRenameWizardFor}/{@code openDeleteWizardFor} (ловят {@code Throwable})
 * мастер не открывают. Начатый расчёт только читает модель и досчитывается в фоне; результат
 * выбрасывается. О фоновой нагрузке и её окончании сообщают тосты.
 */
public final class RefactoringPreparationDialogHook
{
    private static final String TOAST_TITLE = "Сбор данных рефакторинга"; //$NON-NLS-1$

    private static final String CANCEL_TOOLTIP = "Прекратить ожидание: окно закроется, мастер рефакторинга " //$NON-NLS-1$
        + "не откроется. Уже начатый расчёт досчитается в фоне, о его окончании сообщит уведомление"; //$NON-NLS-1$

    /** {@code RefactoringUIHelper_InitiateRefactoringOperationName} — messages_ru.properties. */
    private static final String TASK_LABEL_SNIPPET = "Сбор данных рефактор"; //$NON-NLS-1$

    private static final String MODAL_CONTEXT_THREAD_NAME = "ModalContext"; //$NON-NLS-1$
    private static final String OPERATION_FRAME_CLASS = "RefactoringUIHelper"; //$NON-NLS-1$
    /** {@code org.eclipse.jface.operation.ModalContext.ModalContextThread}: флаг цикла {@code block()}. */
    private static final String DISPATCHING_FIELD = "continueEventDispatching"; //$NON-NLS-1$

    private static final String SHELL_HANDLED_KEY = "tormozit.refactoringPreparationShell"; //$NON-NLS-1$

    private static final int RETRY_DELAY_MS = 100;
    private static final int MAX_ATTEMPTS = 30;

    private static final long WATCH_MS = 500;

    private RefactoringPreparationDialogHook()
    {
    }

    private static final class Session
    {
        final Shell shell;
        /** Момент нажатия «Отмена»; 0 — ожидание не прекращали. */
        volatile long releasedAt;
        volatile boolean finished;
        /** Только UI-поток. */
        Shell progressToast;

        Session(Shell shell)
        {
            this.shell = shell;
        }
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, RefactoringPreparationDialogHook::handleShow);
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell) || shell.isDisposed())
            return;
        if (shell.getData(SHELL_HANDLED_KEY) != null)
            return;
        // ProgressMonitorJobsDialog кладёт себя на Shell через Window.createShell -> setData(this).
        Object data = shell.getData();
        if (data == null || !data.getClass().getName().contains("ProgressMonitorJobsDialog")) //$NON-NLS-1$
            return;
        shell.setData(SHELL_HANDLED_KEY, Boolean.TRUE);
        scheduleCheck(shell, 0);
    }

    private static void scheduleCheck(Shell shell, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
            return;
        if (findLabelContaining(shell, TASK_LABEL_SNIPPET) == null)
        {
            shell.getDisplay().timerExec(RETRY_DELAY_MS, () -> scheduleCheck(shell, attempt + 1));
            return;
        }
        onMatched(shell);
    }

    private static void onMatched(Shell shell)
    {
        Button cancel = findButtonByText(shell, IDialogConstants.CANCEL_LABEL.replace("&", "")); //$NON-NLS-1$ //$NON-NLS-2$
        if (cancel == null)
            return;
        Session session = new Session(shell);
        cancel.setEnabled(true);
        cancel.setToolTipText(TooltipText.wrap(cancel, CANCEL_TOOLTIP + Global.pluginSignForTooltip()));
        // Штатный слушатель кнопки (cancelPressed) срабатывает раньше: выключает её и ставит
        // монитору setCanceled, но окно не закрывает — оно ждёт конца ModalContext.
        cancel.addListener(SWT.Selection, e -> release(session));
        shell.addListener(SWT.Dispose, e -> onShellDisposed(session));
    }

    private static void release(Session session)
    {
        if (session.releasedAt != 0)
            return;
        Thread thread = findOperationThread();
        if (thread == null || !thread.isAlive())
            return;
        try
        {
            Field dispatching = thread.getClass().getDeclaredField(DISPATCHING_FIELD);
            dispatching.setAccessible(true);
            session.releasedAt = System.currentTimeMillis();
            dispatching.setBoolean(thread, false);
            session.shell.getDisplay().wake();
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            session.releasedAt = 0;
            return;
        }
        Thread watcher = new Thread(() -> watch(session, thread), "comfort-refactoring-preparation-watch"); //$NON-NLS-1$
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void onShellDisposed(Session session)
    {
        if (session.releasedAt == 0 || session.finished)
            return;
        // Тост — после закрытия окна: пока окно живо, тост стал бы его дочерним и исчез вместе с ним.
        Display.getDefault().asyncExec(() -> showProgressToast(session));
    }

    private static void showProgressToast(Session session)
    {
        if (session.finished)
            return;
        session.progressToast = ToastNotification.showStickyUntilToastHover(TOAST_TITLE,
            "Ожидание отменено. Уже начатый расчёт досчитывается в фоне с уведомлением в конце.", //$NON-NLS-1$
            null, null);
    }

    /** Фоновый поток: ждёт, пока отпущенная операция досчитается. */
    private static void watch(Session session, Thread operationThread)
    {
        try
        {
            while (operationThread.isAlive() && containsOperationFrame(operationThread.getStackTrace()))
                Thread.sleep(WATCH_MS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return;
        }
        onOperationFinished(session);
    }

    private static void onOperationFinished(Session session)
    {
        session.finished = true;
        String message = "Фоновый расчёт отменённого рефакторинга завершён за " //$NON-NLS-1$
            + seconds(System.currentTimeMillis() - session.releasedAt);
        Display.getDefault().asyncExec(() ->
        {
            if (session.progressToast != null)
                ToastNotification.close(session.progressToast);
            ToastNotification.show(TOAST_TITLE, message);
        });
    }

    private static String seconds(long ms)
    {
        return Math.max(1, Math.round(ms / 1000.0)) + " сек"; //$NON-NLS-1$
    }

    private static Thread findOperationThread()
    {
        for (var entry : Thread.getAllStackTraces().entrySet())
        {
            if (MODAL_CONTEXT_THREAD_NAME.equals(entry.getKey().getName()) && containsOperationFrame(entry.getValue()))
                return entry.getKey();
        }
        return null;
    }

    private static boolean containsOperationFrame(StackTraceElement[] trace)
    {
        for (StackTraceElement el : trace)
        {
            if (el.getClassName().contains(OPERATION_FRAME_CLASS))
                return true;
        }
        return false;
    }

    private static Label findLabelContaining(Control root, String snippet)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof Label label)
        {
            String text = label.getText();
            if (text != null && text.contains(snippet))
                return label;
        }
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Label found = findLabelContaining(child, snippet);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Button findButtonByText(Control root, String text)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof Button button)
        {
            String buttonText = button.getText();
            if (buttonText != null && buttonText.replace("&", "").equals(text)) //$NON-NLS-1$ //$NON-NLS-2$
                return button;
        }
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Button found = findButtonByText(child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
