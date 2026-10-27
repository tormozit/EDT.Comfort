package tormozit;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.ArrayList;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com._1c.g5.v8.dt.debug.core.model.IDebugSession;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.e1c.g5.dt.applications.contributors.IApplicationBehaviourDelegate;

/**
 * Патч диалога EDT «Выбор действия» при блокировке инфобазы: автоматический выбор
 * «Обновить динамически» по настройке из списка приложений, уведомление о переподключении ИР
 * и предложение отключить ИР при исключительной блокировке без динамического обновления.
 * Заголовок содержит приложение выполняющегося обновления; «Завершить и обновить»
 * удаляет через RAC остальные сеансы базы, сохраняя конфигуратор
 * (порт RDT {@code ПриОткрытииОкна}).
 */
public final class InfobaseSyncQuestionDialogHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.syncQuestionPatched"; //$NON-NLS-1$
    private static final String APPLICATION_KEY = "tormozit.syncQuestionApplication"; //$NON-NLS-1$
    private static final String CALLER_KEY = "tormozit.syncQuestionCaller"; //$NON-NLS-1$
    private static final Map<Thread, IApplication> UPDATING_APPLICATIONS = new ConcurrentHashMap<>();
    private static final Map<Thread, BackgroundUpdate> BACKGROUND_UPDATES = new ConcurrentHashMap<>();
    private static volatile Object trackedRegistry;
    private static final String AUTO_TRIGGERED_KEY = "tormozit.syncQuestionAutoTriggered"; //$NON-NLS-1$
    private static final String DISCONNECT_TOAST_KEY = "tormozit.syncQuestionDisconnectToast"; //$NON-NLS-1$
    private static final String DIALOG_CLASS =
        "InfobaseSynchonizationQuestionDialog"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Выбор действия"; //$NON-NLS-1$
    private static final String BTN_DYNAMIC = "Обновить динамически"; //$NON-NLS-1$
    private static final String EXCLUSIVE_LOCK_SNIPPET = "исключительной блокировки"; //$NON-NLS-1$
    private static final String TOAST_MESSAGE =
        "БД обновлена динамически. Переподключить приложение ИР для обновления в нем метаданных?"; //$NON-NLS-1$
    private static final String DISCONNECT_TOAST_MESSAGE =
        "Приложение ИР блокирует обновление базы. Отключить?"; //$NON-NLS-1$

    private static final long PENDING_TIMEOUT_MS = 30L * 60_000;

    private static final AtomicReference<PendingDynamicUpdate> pendingAutoUpdate =
        new AtomicReference<>();

    private static volatile boolean jobListenerInstalled;

    private static final class PendingDynamicUpdate
    {
        final InfobaseReference infobase;
        final long startedMs;

        PendingDynamicUpdate(InfobaseReference infobase)
        {
            this.infobase = infobase;
            startedMs = System.currentTimeMillis();
        }

        boolean expired()
        {
            return System.currentTimeMillis() - startedMs > PENDING_TIMEOUT_MS;
        }
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        ensureJobListener();
        ensureApplicationContextTracking();
        // Сервис приложений может появиться позже startup; устанавливаемся до команды обновления.
        display.addFilter(SWT.Selection, event -> ensureApplicationContextTracking());

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell) event.widget;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (!isSyncQuestionShell(shell))
                return;
            bindApplication(display, shell);
            patchBeforeShow(display, shell);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    /**
     * ApplicationManager → applicationBehaviourDelegates → update(application, ...).
     * Поля подтверждены в applications 7.0.0.v202607090722; getApplicationBehaviourDelegates()
     * возвращает список из этого реестра. ShellDesignerSession.execute() вызывает вопрос
     * синхронно; UI-handler platform.services.ui 17.0.0.v202605050943 делает Display.syncExec().
     */
    private static synchronized void ensureApplicationContextTracking()
    {
        try
        {
            Object manager = Global.unwrapServiceProxy(Global.getOsgiService(IApplicationManager.class));
            Object registry = Global.getField(manager, "applicationsExtensionRegistry"); //$NON-NLS-1$
            if (registry == null || registry == trackedRegistry)
                return;
            synchronized (registry)
            {
                Global.invoke(registry, "initialize"); //$NON-NLS-1$
                Object value = Global.getField(registry, "applicationBehaviourDelegates"); //$NON-NLS-1$
                if (!(value instanceof Map<?, ?> delegates))
                    return;
                Map<Object, List<Object>> wrapped = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : delegates.entrySet())
                {
                    if (!(entry.getValue() instanceof List<?> list))
                        return;
                    List<Object> replacements = new ArrayList<>();
                    for (Object delegate : list)
                    {
                        if (delegate instanceof IApplicationBehaviourDelegate
                            && !(Proxy.isProxyClass(delegate.getClass())
                                && Proxy.getInvocationHandler(delegate) instanceof ApplicationContextHandler))
                            replacements.add(Proxy.newProxyInstance(IApplicationBehaviourDelegate.class.getClassLoader(),
                                new Class<?>[] { IApplicationBehaviourDelegate.class }, new ApplicationContextHandler(delegate)));
                        else
                            replacements.add(delegate);
                    }
                    wrapped.put(entry.getKey(), replacements);
                }
                if (Global.setFieldForce(registry, "applicationBehaviourDelegates", wrapped)) //$NON-NLS-1$
                {
                    trackedRegistry = registry;
                    Global.tempLog("infobase-sync-context", "application delegate tracking installed types=" + wrapped.size()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        catch (Exception e)
        {
            Global.tempLog("infobase-sync-context", "tracking installation failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static final class ApplicationContextHandler implements InvocationHandler
    {
        private final Object delegate;

        private ApplicationContextHandler(Object delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable
        {
            Thread thread = Thread.currentThread();
            IApplication application = arguments != null && arguments.length > 0
                && arguments[0] instanceof IInfobaseApplication candidate ? candidate : null;
            IApplication previous = null;
            if (application != null)
            {
                previous = UPDATING_APPLICATIONS.put(thread, application);
                Global.tempLog("infobase-sync-context", "enter thread=" + thread.getId() + " method=" + method.getName() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " application=" + application.getName() + " base=" + ((IInfobaseApplication)application).getInfobase().getUuid()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Consumer<Boolean> updateCompletion = application != null && "update".equals(method.getName()) //$NON-NLS-1$
                ? LongOperationNotifyHook.trackInfobaseUpdate(application.getName()) : null;
            boolean updated = false;
            long logStarted = 0;
            try
            {
                logStarted = InfobaseSyncDebug.begin(delegate, method, arguments);
                if (application instanceof IInfobaseApplication infobaseApplication
                    && "update".equals(method.getName())) //$NON-NLS-1$
                    terminateDebugSessionsBeforeUpdate(infobaseApplication, (IProgressMonitor)arguments[3]);
                Object result = method.invoke(delegate, arguments);
                updated = result == ApplicationUpdateState.UPDATED;
                InfobaseSyncDebug.finish(method, logStarted, result, null);
                return result;
            }
            catch (InvocationTargetException e)
            {
                InfobaseSyncDebug.finish(method, logStarted, null, e.getCause());
                throw e.getCause();
            }
            catch (Throwable e)
            {
                InfobaseSyncDebug.finish(method, logStarted, null, e);
                throw e;
            }
            finally
            {
                if (application != null)
                {
                    if (previous == null)
                        UPDATING_APPLICATIONS.remove(thread);
                    else
                        UPDATING_APPLICATIONS.put(thread, previous);
                    Global.tempLog("infobase-sync-context", "leave thread=" + thread.getId() + " method=" + method.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                if (updateCompletion != null)
                {
                    updateCompletion.accept(updated);
                    BackgroundUpdate background = BACKGROUND_UPDATES.remove(thread);
                    if (background != null && !updated && !background.display.isDisposed())
                        background.display.asyncExec(background::restore);
                }
            }
        }
    }

    /** Компактная диагностика обновления, включаемая флажком «Вести журнал». */
    private static final class InfobaseSyncDebug
    {
        private static final String TAG = "InfobaseSync"; //$NON-NLS-1$

        private static long begin(Object delegate, Method method, Object[] arguments)
        {
            if (!Global.isLogEnabled()
                || !("update".equals(method.getName()) || "prepare".equals(method.getName()))) //$NON-NLS-1$ //$NON-NLS-2$
                return 0;
            long started = System.nanoTime();
            IApplication application = arguments != null && arguments.length > 0
                && arguments[0] instanceof IApplication candidate ? candidate : null;
            String detail = "thread=" + Thread.currentThread().getId() //$NON-NLS-1$
                + " application=" + (application == null ? "null" : application.getName()) //$NON-NLS-1$ //$NON-NLS-2$
                + " class=" + (application == null ? "null" : application.getClass().getName()) //$NON-NLS-1$ //$NON-NLS-2$
                + " delegate=" + delegate.getClass().getName(); //$NON-NLS-1$
            if (Proxy.isProxyClass(delegate.getClass()))
                detail += " handler=" + Proxy.getInvocationHandler(delegate).getClass().getName(); //$NON-NLS-1$
            if ("update".equals(method.getName()) && arguments.length > 1) //$NON-NLS-1$
                detail += " type=" + arguments[1]; //$NON-NLS-1$
            Global.log(TAG, method.getName() + " start " + detail); //$NON-NLS-1$
            if ("update".equals(method.getName())) //$NON-NLS-1$
                logSynchronizationServices();
            return started;
        }

        private static void finish(Method method, long started, Object result, Throwable failure)
        {
            if (started == 0 || !Global.isLogEnabled())
                return;
            String detail = method.getName() + " end thread=" + Thread.currentThread().getId() //$NON-NLS-1$
                + " ms=" + (System.nanoTime() - started) / 1_000_000; //$NON-NLS-1$
            if (failure != null)
                Global.log(TAG, "[!] " + detail + " error=" + failure); //$NON-NLS-1$ //$NON-NLS-2$
            else
                Global.log(TAG, detail + " result=" + result); //$NON-NLS-1$
        }

        /** Только сведения реестра OSGi: службы не получаем и не активируем. */
        private static void logSynchronizationServices()
        {
            try
            {
                BundleContext context = Global.ourContext();
                ServiceReference<?>[] references = context == null ? null : context.getServiceReferences(
                    "com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager", null); //$NON-NLS-1$
                StringBuilder detail = new StringBuilder("sync services thread=") //$NON-NLS-1$
                    .append(Thread.currentThread().getId());
                if (references != null)
                    for (ServiceReference<?> reference : references)
                    {
                        var bundle = reference.getBundle();
                        detail.append(" [bundle=").append(bundle == null ? "null" : bundle.getSymbolicName()) //$NON-NLS-1$ //$NON-NLS-2$
                            .append(" version=").append(bundle == null ? "null" : bundle.getVersion()) //$NON-NLS-1$ //$NON-NLS-2$
                            .append(" id=").append(reference.getProperty("service.id")) //$NON-NLS-1$ //$NON-NLS-2$
                            .append(" ranking=").append(reference.getProperty("service.ranking")).append(']'); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                Global.log(TAG, detail.toString());
            }
            catch (Exception e)
            {
                Global.log(TAG, "[!] sync services: " + e); //$NON-NLS-1$
            }
        }
    }

    /** Завершаем только сеансы обновляемой серверной базы с подключёнными предметами отладки. */
    private static void terminateDebugSessionsBeforeUpdate(IInfobaseApplication application, IProgressMonitor monitor)
    {
        if (!(application.getInfobase().getConnectionString() instanceof ServerConnectionString))
            return;
        RacSessionClient client = null;
        try
        {
            Map<UUID, BigDecimal> sessions = new LinkedHashMap<>();
            for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches())
                for (var debugTarget : launch.getDebugTargets())
                {
                    if (!(debugTarget instanceof IRuntimeDebugClientTarget target)
                        || target.isTerminated() || target.isDisconnected())
                        continue;
                    IApplication debugApplication = target.getApplication().orElse(null);
                    if (!(debugApplication instanceof IInfobaseApplication debugInfobase)
                        || !application.getProject().equals(debugApplication.getProject())
                        || !application.getInfobase().getUuid().equals(debugInfobase.getInfobase().getUuid()))
                        continue;
                    for (var group : target.getThreadGroups())
                    {
                        if (!(group instanceof IDebugSession session))
                            continue;
                        for (var thread : session.getThreads())
                        {
                            if (thread.isTerminated() || thread.isDisconnected())
                                continue;
                            if (session.getSessionUuid() == null || session.getSessionId() == null)
                                throw new ApplicationException("EDT не передала UUID и номер отлаживаемого сеанса.");
                            sessions.put(session.getSessionUuid(), session.getSessionId());
                            break;
                        }
                    }
                }
            Global.tempLog("debug-session-rac", "before update application=" + application.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + " debugSessions=" + sessions.size()); //$NON-NLS-1$
            if (sessions.isEmpty())
                return;
            if (monitor.isCanceled())
                throw new OperationCanceledException();
            AtomicReference<RasConfiguration> selected = new AtomicReference<>();
            AtomicReference<CoreException> selectionFailure = new AtomicReference<>();
            Display.getDefault().syncExec(() ->
            {
                try
                {
                    selected.set(chooseRasConfiguration(Display.getDefault().getActiveShell(), application));
                }
                catch (CoreException e)
                {
                    selectionFailure.set(e);
                }
            });
            if (selectionFailure.get() != null)
                throw selectionFailure.get();
            RasConfiguration configuration = selected.get();
            if (configuration == null)
                throw new OperationCanceledException();
            String problem = DebugSessionRasTab.validationProblem(configuration.configuration());
            if (problem != null)
                throw new ApplicationException("Завершение отлаживаемых сеансов перед обновлением: " + problem);
            client = new RacSessionClient(configuration.project(), configuration.infobase(),
                DebugSessionRasTab.normalizedAddress(configuration.configuration().getAttribute(DebugSessionRasTab.ADDRESS, "")), //$NON-NLS-1$
                configuration.configuration().getAttribute(DebugSessionRasTab.USER, "").trim(), //$NON-NLS-1$
                configuration.configuration().getAttribute(DebugSessionRasTab.PASSWORD, "")); //$NON-NLS-1$
            for (var session : sessions.entrySet())
                client.delete(session.getKey(), session.getValue(), monitor);
        }
        catch (OperationCanceledException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            String message = client != null ? client.redact(e.getLocalizedMessage()) : e.getLocalizedMessage();
            Global.tempLog("debug-session-rac", "before update failed: " + message); //$NON-NLS-1$ //$NON-NLS-2$
            throw new ApplicationException("Обновление базы отменено: не удалось завершить отлаживаемые сеансы. " + message);
        }
    }

    /** Захватываем на Show/Activate: в отложенном timerExec getSyncThread() уже не обязан существовать. */
    private static void bindApplication(Display display, Shell shell)
    {
        if (shell.getData(APPLICATION_KEY) instanceof IApplication)
            return;
        Thread caller = dialogCaller(display);
        IApplication application = UPDATING_APPLICATIONS.get(caller);
        Global.tempLog("infobase-sync-context", "bind dialog caller=" + caller.getId() //$NON-NLS-1$ //$NON-NLS-2$
            + " application=" + (application != null ? application.getName() : "unresolved")); //$NON-NLS-1$ //$NON-NLS-2$
        logBindingContext(display, shell);
        if (application != null)
        {
            shell.setData(APPLICATION_KEY, application);
            shell.setData(CALLER_KEY, caller);
            shell.setText(DIALOG_TITLE + " — " + application.getName()); //$NON-NLS-1$
        }
        else
            shell.setText(DIALOG_TITLE + " — приложение не определено");
    }

    /**
     * UISynchronizer.syncExec() при владении блокировками использует asyncExec().
     * Исходный поток тогда хранится в lockListener.currentWork.operation.
     * Цепочка подтверждена в org.eclipse.ui.workbench 3.131.0.v20231201-0512.
     */
    private static Thread dialogCaller(Display display)
    {
        Thread caller = display.getSyncThread();
        if (caller == null)
            caller = Thread.currentThread();
        if (UPDATING_APPLICATIONS.containsKey(caller))
            return caller;
        Object synchronizer = display.getSynchronizer();
        Object lockListener = Global.getField(synchronizer, "lockListener"); //$NON-NLS-1$
        Object currentWork = Global.getField(lockListener, "currentWork"); //$NON-NLS-1$
        Object operation = Global.getField(currentWork, "operation"); //$NON-NLS-1$
        Global.tempLog("infobase-sync-context", "workbench caller synchronizer=" + synchronizer.getClass().getName() //$NON-NLS-1$ //$NON-NLS-2$
            + " operationThread=" + (operation instanceof Thread thread ? thread.getId() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        return operation instanceof Thread thread && UPDATING_APPLICATIONS.containsKey(thread) ? thread : caller;
    }

    /** Временная диагностика фактического перехода от обновления к UI вопроса. */
    private static void logBindingContext(Display display, Shell shell)
    {
        Thread uiThread = Thread.currentThread();
        Thread syncThread = display.getSyncThread();
        Global.tempLog("infobase-sync-context", "binding detail shell=" + System.identityHashCode(shell) //$NON-NLS-1$ //$NON-NLS-2$
            + " uiThread=" + uiThread.getId() + " syncThread=" + (syncThread == null ? "null" : syncThread.getId()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " activeUpdates=" + UPDATING_APPLICATIONS.size()); //$NON-NLS-1$
        logBindingThread("ui", uiThread); //$NON-NLS-1$
        for (Map.Entry<Thread, IApplication> entry : UPDATING_APPLICATIONS.entrySet())
        {
            Global.tempLog("infobase-sync-context", "active update thread=" + entry.getKey().getId() //$NON-NLS-1$ //$NON-NLS-2$
                + " application=" + entry.getValue().getName()); //$NON-NLS-1$
            logBindingThread("update", entry.getKey()); //$NON-NLS-1$
        }
    }

    private static void resizeForAdditionalButton(Shell shell, Composite bar)
    {
        Point before = shell.getSize();
        Point required = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        shell.setSize(Math.max(before.x, required.x), Math.max(before.y, required.y));
        shell.layout(true, true);
        Global.tempLog("infobase-sync-context", "button layout shellBefore=" + before //$NON-NLS-1$ //$NON-NLS-2$
            + " required=" + required + " shellAfter=" + shell.getSize() + " bar=" + bar.getClientArea()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (Control control : bar.getChildren())
            if (control instanceof Button button)
                Global.tempLog("infobase-sync-context", "button layout text=" + button.getText() //$NON-NLS-1$ //$NON-NLS-2$
                    + " bounds=" + button.getBounds()); //$NON-NLS-1$
    }

    private static void logBindingThread(String role, Thread thread)
    {
        Global.tempLog("infobase-sync-context", "thread role=" + role + " id=" + thread.getId() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " name=" + thread.getName() + " state=" + thread.getState()); //$NON-NLS-1$ //$NON-NLS-2$
        for (StackTraceElement frame : thread.getStackTrace())
            Global.tempLog("infobase-sync-context", "stack role=" + role + " thread=" + thread.getId() + " at " + frame); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static InfobaseReference dialogInfobase(Shell shell)
    {
        return shell.getData(APPLICATION_KEY) instanceof IInfobaseApplication application ? application.getInfobase() : null;
    }

    private static void ensureJobListener()
    {
        if (jobListenerInstalled)
            return;
        jobListenerInstalled = true;
        Job.getJobManager().addJobChangeListener(new IJobChangeListener()
        {
            @Override public void aboutToRun(IJobChangeEvent event) {}
            @Override public void awake(IJobChangeEvent event) {}
            @Override public void sleeping(IJobChangeEvent event) {}
            @Override public void running(IJobChangeEvent event) {}
            @Override public void scheduled(IJobChangeEvent event) {}

            @Override
            public void done(IJobChangeEvent event)
            {
                PendingDynamicUpdate pending = pendingAutoUpdate.get();
                if (pending == null)
                    return;
                Job job = event.getJob();
                IStatus result = event.getResult();
                if (pending.expired())
                {
                    pendingAutoUpdate.compareAndSet(pending, null);
                    return;
                }
                if (!isUpdateJob(job))
                    return;
                if (result == null || !result.isOK())
                    return;
                if (!pendingAutoUpdate.compareAndSet(pending, null))
                    return;
                InfobaseReference ib = pending.infobase;
                Display.getDefault().asyncExec(() -> showReconnectToast(ib));
            }
        });
    }

    private static boolean isSyncQuestionShell(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && data.getClass().getName().contains(DIALOG_CLASS))
            return true;
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE);
    }

    private static boolean hasExclusiveLockText(Shell shell, Object dialog)
    {
        String message = getDialogMessage(dialog);
        if (message != null && message.contains(EXCLUSIVE_LOCK_SNIPPET))
            return true;
        return findControlContainingText(shell, EXCLUSIVE_LOCK_SNIPPET) != null;
    }

    /** Window.open() создаёт все контролы и рассчитывает размеры до Shell.open()/SWT.Show. */
    private static void patchBeforeShow(Display display, Shell shell)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        Object dialog = shell.getData();
        Button dynamic = findButtonByText(shell, BTN_DYNAMIC);
        if (dynamic == null && !hasExclusiveLockText(shell, dialog))
            return;
        shell.setData(PATCHED_KEY, Boolean.TRUE);
        Global.tempLog("infobase-sync-context", "patch before show visible=" + shell.getVisible()); //$NON-NLS-1$ //$NON-NLS-2$
        if (dynamic != null)
            patchDialog(display, shell, dialog, dynamic);
        else
            patchExclusiveLockDialog(shell, dialog);
    }

    private static void patchExclusiveLockDialog(Shell shell, Object dialog)
    {
        if (shell.getData(DISCONNECT_TOAST_KEY) != null)
            return;

        InfobaseReference infobase = dialogInfobase(shell);
        if (infobase == null)
        {
            if (Global.isLogEnabled())
                Global.log("[ExclusiveLock] infobase not resolved for exclusive lock dialog"); //$NON-NLS-1$
            return;
        }
        if (!IRApplication.getInstance().isConnected(infobase))
        {
            if (Global.isLogEnabled())
                Global.log("[ExclusiveLock] IR not connected, skip disconnect toast for infobase " //$NON-NLS-1$
                    + IRApplication.extractInfobaseUuid(infobase));
            return;
        }

        shell.setData(DISCONNECT_TOAST_KEY, Boolean.TRUE);
        if (Global.isLogEnabled())
            Global.log("[ExclusiveLock] showing disconnect toast for infobase " //$NON-NLS-1$
                + IRApplication.extractInfobaseUuid(infobase));
        final InfobaseReference ib = infobase;
        ToastNotification.show(
            IRApplication.toastTitle(),
            DISCONNECT_TOAST_MESSAGE,
            6_000,
            () -> IRApplication.disconnect(ib),
            "Отключить", //$NON-NLS-1$
            shell);
    }

    private static void patchDialog(Display display, Shell shell, Object dialog, Button btnDynamic)
    {
        addTerminateAndUpdateButton(shell, dialog, btnDynamic);
        InfobaseReference infobase = dialogInfobase(shell);
        if (infobase == null)
            Global.log("[DynamicAutoUpdate] infobase not resolved for sync question dialog"); //$NON-NLS-1$

        wrapDynamicButton(btnDynamic, infobase);

        if (infobase != null
            && IRApplication.getInstance().isDynamicAutoUpdate(infobase)
            && IRApplication.getInstance().isConnected(infobase)
            && shell.getData(AUTO_TRIGGERED_KEY) == null)
        {
            shell.setData(AUTO_TRIGGERED_KEY, Boolean.TRUE);
            shell.addDisposeListener(e -> tryReconnectToastOnAutoDialogDispose(shell));
            final InfobaseReference ib = infobase;
            display.asyncExec(() ->
            {
                if (btnDynamic.isDisposed() || shell.isDisposed())
                    return;
                markAutoUpdatePending(ib);
                Event ev = new Event();
                ev.type = SWT.Selection;
                ev.widget = btnDynamic;
                btnDynamic.notifyListeners(SWT.Selection, ev);
            });
        }
    }

    /** Приложение привязано к окну по выполняющемуся вызову обновления EDT. */
    private static void addTerminateAndUpdateButton(Shell shell, Object dialog, Button dynamic)
    {
        if (!(shell.getData(APPLICATION_KEY) instanceof IInfobaseApplication contextApplication)
            || contextApplication.getInfobase() == null
            || !(contextApplication.getInfobase().getConnectionString() instanceof ServerConnectionString))
            return;
        Button retry = findButtonByText(shell, "Повторить");
        if (retry == null || !hasExclusiveLockText(shell, dialog))
            return;
        Composite bar = retry.getParent();
        if (!(bar.getLayout() instanceof GridLayout layout))
            return;
        Button terminate = new Button(bar, SWT.PUSH);
        terminate.setText("Завершить и обновить");
        terminate.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        terminate.setToolTipText(TooltipText.wrap(terminate,
            "Завершить через RAC все сеансы обновляемой информационной базы, проверить их удаление "
                + "и повторить обновление. Параметры RAS берутся из конфигурации подключения к серверу отладки."
                + Global.pluginSignForTooltip()));
        layout.numColumns++;
        terminate.moveAbove(retry);
        resizeForAdditionalButton(shell, bar);
        terminate.addListener(SWT.Selection, event ->
        {
            try
            {
                if (!(shell.getData(APPLICATION_KEY) instanceof IInfobaseApplication application))
                    return;
                RasConfiguration selected = chooseRasConfiguration(shell, application);
                if (selected == null)
                    return;
                ILaunchConfiguration configuration = selected.configuration();
                String problem = DebugSessionRasTab.validationProblem(configuration);
                if (problem != null)
                {
                    DebugSessionRasTab.requestFocus(configuration);
                    try
                    {
                        DebugUITools.openLaunchConfigurationDialogOnGroup(shell,
                            new StructuredSelection(configuration), IDebugUIConstants.ID_DEBUG_LAUNCH_GROUP,
                            new Status(IStatus.WARNING, "tormozit.comfort", problem)); //$NON-NLS-1$
                    }
                    finally
                    {
                        DebugSessionRasTab.clearFocusRequest();
                    }
                    return;
                }
                RacSessionClient client = new RacSessionClient(selected.project(), selected.infobase(),
                    DebugSessionRasTab.normalizedAddress(configuration.getAttribute(DebugSessionRasTab.ADDRESS, "")), //$NON-NLS-1$
                    configuration.getAttribute(DebugSessionRasTab.USER, "").trim(), //$NON-NLS-1$
                    configuration.getAttribute(DebugSessionRasTab.PASSWORD, "")); //$NON-NLS-1$
                if (!(shell.getData(CALLER_KEY) instanceof Thread caller)
                    || UPDATING_APPLICATIONS.get(caller) != application)
                    throw new IllegalStateException("Не удалось определить выполняющееся обновление приложения.");
                terminate.setEnabled(false);
                retry.setEnabled(false);
                dynamic.setEnabled(false);
                Display display = shell.getDisplay();
                BackgroundUpdate background = new BackgroundUpdate(shell);
                BACKGROUND_UPDATES.put(caller, background);
                background.hide();
                Job job = Job.create("Завершение сеансов перед обновлением базы", monitor ->
                {
                    boolean deleted = false;
                    String failure = null;
                    try
                    {
                        client.deleteAllInfobaseSessions(monitor);
                        deleted = true;
                    }
                    catch (OperationCanceledException e)
                    {
                        return Status.CANCEL_STATUS;
                    }
                    catch (Exception e)
                    {
                        failure = client.redact(e.getLocalizedMessage());
                        Global.tempLog("debug-session-rac", "terminate and update failed: " + failure); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    finally
                    {
                        boolean resume = deleted && !monitor.isCanceled();
                        String error = failure;
                        if (!display.isDisposed())
                            display.asyncExec(() ->
                            {
                                if (shell.isDisposed())
                                    return;
                                terminate.setEnabled(true);
                                retry.setEnabled(true);
                                dynamic.setEnabled(true);
                                if (resume)
                                {
                                    Global.tempLog("debug-session-rac", "all non-designer sessions absent; retry update"); //$NON-NLS-1$ //$NON-NLS-2$
                                    Event selection = new Event();
                                    selection.widget = retry;
                                    selection.type = SWT.Selection;
                                    retry.notifyListeners(SWT.Selection, selection);
                                }
                                else
                                {
                                    BACKGROUND_UPDATES.remove(caller, background);
                                    background.restore();
                                    if (error != null)
                                        MessageDialog.openError(shell, Global.withPluginWindowTitle("Завершить и обновить"), error);
                                }
                            });
                    }
                    return Status.OK_STATUS;
                });
                job.setUser(false);
                shell.addDisposeListener(disposed -> job.cancel());
                job.schedule();
            }
            catch (Exception e)
            {
                Global.tempLog("debug-session-rac", "prepare terminate and update failed: " + e); //$NON-NLS-1$ //$NON-NLS-2$
                if (shell.getData(CALLER_KEY) instanceof Thread caller)
                {
                    BackgroundUpdate background = BACKGROUND_UPDATES.remove(caller);
                    if (background != null)
                        background.restore();
                }
                terminate.setEnabled(true);
                retry.setEnabled(true);
                dynamic.setEnabled(true);
                MessageDialog.openError(shell, Global.withPluginWindowTitle("Завершить и обновить"), e.getLocalizedMessage());
            }
        });
    }

    /** Скрытие Shell снимает его модальность в SWT/Windows, сохраняя штатный цикл Dialog.open(). */
    private static final class BackgroundUpdate
    {
        final Display display;
        private final List<Shell> shells = new ArrayList<>();
        private final List<Shell> busyCursorShells = new ArrayList<>();

        BackgroundUpdate(Shell question)
        {
            display = question.getDisplay();
            int modal = SWT.PRIMARY_MODAL | SWT.APPLICATION_MODAL | SWT.SYSTEM_MODAL;
            for (Shell current = question; current != null && (current.getStyle() & modal) != 0;
                current = current.getParent() instanceof Shell parent ? parent : null)
                if (current.getVisible())
                    shells.add(current);
            // WizardDialog.aboutToStart() ставит курсор ожидания и основным окнам EDT.
            for (Shell shell = question; shell != null;
                shell = shell.getParent() instanceof Shell parent ? parent : null)
                if (shell.getCursor() == display.getSystemCursor(SWT.CURSOR_WAIT))
                    busyCursorShells.add(shell);
        }

        void hide()
        {
            for (Shell shell : shells)
            {
                Global.tempLog("infobase-sync-context", "background hide shell=" + shell.getText()); //$NON-NLS-1$ //$NON-NLS-2$
                shell.setVisible(false);
            }
            for (Shell shell : busyCursorShells)
                if (!shell.isDisposed())
                    shell.setCursor(null);
        }

        void restore()
        {
            for (int i = shells.size() - 1; i >= 0; i--)
            {
                Shell shell = shells.get(i);
                if (!shell.isDisposed())
                {
                    Global.tempLog("infobase-sync-context", "background restore shell=" + shell.getText()); //$NON-NLS-1$ //$NON-NLS-2$
                    shell.setVisible(true);
                }
            }
        }
    }

    private record RasConfiguration(ILaunchConfiguration configuration, IProject project, InfobaseReference infobase) {}

    private static RasConfiguration chooseRasConfiguration(Shell shell, IInfobaseApplication application) throws CoreException
    {
        List<RasConfiguration> choices = new ArrayList<>();
        InfobaseReference infobase = application.getInfobase();
        for (ILaunchConfiguration configuration : DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations())
        {
            String type = configuration.getType().getIdentifier();
            if (!"com._1c.g5.v8.dt.debug.core.RemoteRuntime".equals(type) //$NON-NLS-1$
                && !"com._1c.g5.v8.dt.debug.core.LocalRuntime".equals(type)) //$NON-NLS-1$
                continue;
            String projectName = configuration.getAttribute("com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME", ""); //$NON-NLS-1$ //$NON-NLS-2$
            String baseUuid = configuration.getAttribute("com._1c.g5.v8.dt.debug.core.ATTR_INFOBASE_UUID", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (!application.getProject().getName().equals(projectName)
                || !String.valueOf(infobase.getUuid()).equalsIgnoreCase(baseUuid))
                continue;
            if (infobase.getConnectionString() instanceof ServerConnectionString)
                choices.add(new RasConfiguration(configuration, application.getProject(), infobase));
        }
        if (choices.isEmpty())
        {
            DebugUITools.openLaunchConfigurationDialogOnGroup(shell, StructuredSelection.EMPTY,
                IDebugUIConstants.ID_DEBUG_LAUNCH_GROUP, new Status(IStatus.WARNING, "tormozit.comfort", //$NON-NLS-1$
                    "Для приложения «" + application.getName()
                        + "» нет конфигурации подключения к этой базе. Выберите её базу на вкладке «Соединить» и заполните вкладку «RAS»."));
            return null;
        }
        if (choices.size() == 1)
            return choices.get(0);
        ElementListSelectionDialog chooser = new ElementListSelectionDialog(shell, new LabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                RasConfiguration entry = (RasConfiguration)element;
                ServerConnectionString connection = (ServerConnectionString)entry.infobase().getConnectionString();
                return entry.configuration().getName() + " — " + connection.getServer() + " / " + connection.getReference(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
        chooser.setTitle(Global.withPluginWindowTitle("Завершить и обновить"));
        chooser.setMessage("У приложения «" + application.getName()
            + "» несколько конфигураций подключения. Выберите параметры RAS; будут завершены все сеансы этой базы, кроме конфигуратора:");
        chooser.setMultipleSelection(false);
        chooser.setElements(choices.toArray());
        return chooser.open() == Window.OK ? (RasConfiguration)chooser.getFirstResult() : null;
    }

    private static Control findControlContainingText(Control root, String snippet)
    {
        if (root instanceof Label)
        {
            String text = ((Label) root).getText();
            if (text != null && text.contains(snippet))
                return root;
        }
        if (root instanceof org.eclipse.swt.widgets.Text)
        {
            String text = ((org.eclipse.swt.widgets.Text) root).getText();
            if (text != null && text.contains(snippet))
                return root;
        }
        if (root instanceof Composite)
        {
            for (Control child : ((Composite) root).getChildren())
            {
                Control found = findControlContainingText(child, snippet);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void wrapDynamicButton(Button btnDynamic, InfobaseReference infobase)
    {
        Listener[] original = btnDynamic.getListeners(SWT.Selection);
        for (Listener l : original)
            btnDynamic.removeListener(SWT.Selection, l);

        btnDynamic.addListener(SWT.Selection, event ->
        {
            if (infobase != null && shellMarkedAutoTriggered(btnDynamic.getShell()))
                markAutoUpdatePending(infobase);
            for (Listener l : original)
                l.handleEvent(event);
        });
    }

    private static boolean shellMarkedAutoTriggered(Shell shell)
    {
        return shell != null && shell.getData(AUTO_TRIGGERED_KEY) != null;
    }

    private static void markAutoUpdatePending(InfobaseReference infobase)
    {
        if (infobase == null)
            return;
        pendingAutoUpdate.set(new PendingDynamicUpdate(infobase));
        Global.log("[DynamicAutoUpdate] pending reconnect toast for infobase " //$NON-NLS-1$
            + IRApplication.extractInfobaseUuid(infobase));
    }

    /** Запасной триггер reconnect-тоста, если job listener не сработал. */
    private static void tryReconnectToastOnAutoDialogDispose(Shell shell)
    {
        if (shell == null || shell.getData(AUTO_TRIGGERED_KEY) == null)
            return;
        PendingDynamicUpdate pending = pendingAutoUpdate.get();
        if (pending == null)
            return;
        if (pending.expired())
        {
            pendingAutoUpdate.compareAndSet(pending, null);
            return;
        }
        if (!pendingAutoUpdate.compareAndSet(pending, null))
            return;
        InfobaseReference ib = pending.infobase;
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> showReconnectToast(ib));
    }

    private static void showReconnectToast(InfobaseReference infobase)
    {
        if (infobase == null)
            return;
        Global.log("[DynamicAutoUpdate] dynamic update finished, showing reconnect toast"); //$NON-NLS-1$
        ToastNotification.show(
            IRApplication.toastTitle(),
            TOAST_MESSAGE,
            6_000,
            () -> IRApplication.getInstance().reconnectInfobase(infobase),
            "Выполнить"); //$NON-NLS-1$
    }

    private static String getDialogMessage(Object dialog)
    {
        if (dialog == null)
            return ""; //$NON-NLS-1$
        Object presentation = Global.getField(dialog, "questionPresentation"); //$NON-NLS-1$
        if (presentation == null)
            return ""; //$NON-NLS-1$
        Object msg = Global.invoke(presentation, "getMessage"); //$NON-NLS-1$
        return msg instanceof String ? (String) msg : ""; //$NON-NLS-1$
    }

    private static Button findButtonByText(Control root, String text)
    {
        if (root instanceof Button)
        {
            Button b = (Button) root;
            if ((b.getStyle() & SWT.PUSH) != 0 && text.equals(b.getText()))
                return b;
        }
        if (root instanceof Composite)
        {
            for (Control child : ((Composite) root).getChildren())
            {
                Button found = findButtonByText(child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean isUpdateJob(Job job)
    {
        if (job == null)
            return false;
        String name = job.getName();
        if (name != null && name.contains("списка языков")) //$NON-NLS-1$
            return false;
        String className = job.getClass().getName();
        if (className.contains("DeployWithProgressOperation") //$NON-NLS-1$
            || className.contains("UpdateApplications")) //$NON-NLS-1$
            return true;
        return name != null && name.contains("Обновление конфигурации"); //$NON-NLS-1$
    }
}
