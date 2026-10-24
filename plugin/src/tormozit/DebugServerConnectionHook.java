package tormozit;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.core.Launch;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.IShowInTarget;
import org.eclipse.ui.part.ShowInContext;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;

/**
 * Сохраняет серверное подключение отладчика между вызовами сервисов (issue #880).
 * EDT debug.core 19.0.1.v202607090722: RuntimeDebugClientTarget после завершения
 * последнего предмета отладки вызывает terminateClientTargetsIfPermitted(), который
 * читает ATTR_KEEP_SERVER_LAUNCH из конфигурации текущего запуска. Сам EDT включает
 * его только для автоматически созданного серверного запуска веб-клиента.
 *
 * <p>Включаем этот штатный параметр для серверных запусков LocalRuntime/RemoteRuntime.
 * Рабочую копию меняем в памяти; сохранённые конфигурации не перезаписываем.
 * Для сохранённой конфигурации подставляем её рабочую копию в Launch.fConfiguration
 * (подтверждено в Eclipse debug.core 3.21.200.v20231021-1513).
 * Явная остановка и потеря соединения с сервером обрабатываются самим EDT.
 */
public final class DebugServerConnectionHook implements IStartup, ILaunchesListener2
{
    private static final String CORE_PREFIX = "com._1c.g5.v8.dt.debug.core."; //$NON-NLS-1$
    private static final String KEEP_SERVER_LAUNCH = CORE_PREFIX + "ATTR_KEEP_SERVER_LAUNCH"; //$NON-NLS-1$
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Set<ILaunch> PENDING_ACTIVATION = ConcurrentHashMap.newKeySet();

    @Override
    public void earlyStartup()
    {
        if (!INSTALLED.compareAndSet(false, true))
            return;
        DebugPlugin plugin = DebugPlugin.getDefault();
        ILaunchManager manager = plugin.getLaunchManager();
        manager.addLaunchListener(this);
        update(manager.getLaunches(), false);
    }

    @Override
    public void launchesAdded(ILaunch[] launches)
    {
        // Синхронное уведомление менеджера запусков: параметр ставится до первого вызова сервиса.
        update(launches, true);
    }

    @Override
    public void launchesChanged(ILaunch[] launches)
    {
        update(launches, false);
    }

    @Override
    public void launchesRemoved(ILaunch[] launches)
    {
        for (ILaunch launch : launches)
            PENDING_ACTIVATION.remove(launch);
    }

    @Override
    public void launchesTerminated(ILaunch[] launches)
    {
        for (ILaunch launch : launches)
            PENDING_ACTIVATION.remove(launch);
    }

    private static void update(ILaunch[] launches, boolean added)
    {
        for (ILaunch launch : launches)
        {
            try
            {
                ILaunchConfiguration configuration = launch.getLaunchConfiguration();
                if (configuration == null || !ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode()))
                    continue;
                String type = configuration.getType().getIdentifier();
                if (!(CORE_PREFIX + "LocalRuntime").equals(type) //$NON-NLS-1$
                    && !(CORE_PREFIX + "RemoteRuntime").equals(type)) //$NON-NLS-1$
                    continue;
                if (added && !launch.isTerminated())
                    PENDING_ACTIVATION.add(launch);
                activateWhenConnected(launch);
                if (launch.isTerminated() || configuration.getAttribute(KEEP_SERVER_LAUNCH, false))
                    continue;

                if (configuration instanceof ILaunchConfigurationWorkingCopy workingCopy)
                    workingCopy.setAttribute(KEEP_SERVER_LAUNCH, true);
                else if (launch instanceof Launch)
                {
                    ILaunchConfigurationWorkingCopy workingCopy = configuration.getWorkingCopy();
                    workingCopy.setAttribute(KEEP_SERVER_LAUNCH, true);
                    if (!Global.setFieldForce(launch, "fConfiguration", workingCopy)) //$NON-NLS-1$
                        continue;
                }
            }
            catch (CoreException | RuntimeException e)
            {
                // Ошибка доработки не должна прерывать штатный запуск отладчика.
            }
        }
    }

    /** Ждём добавления предмета отладки в запуск, не выбираем пустую строку запуска. */
    private static void activateWhenConnected(ILaunch launch)
    {
        if (!PENDING_ACTIVATION.contains(launch))
            return;
        for (var debugTarget : launch.getDebugTargets())
        {
            if (debugTarget instanceof IRuntimeDebugClientTarget target && !target.isTerminated()
                && PENDING_ACTIVATION.remove(launch))
            {
                Display display = PlatformUI.getWorkbench().getDisplay();
                if (!display.isDisposed())
                    display.asyncExec(() -> activateConnection(target));
                return;
            }
        }
    }

    private static void activateConnection(IRuntimeDebugClientTarget target)
    {
        if (target.isTerminated() || PlatformUI.getWorkbench().isClosing())
            return;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null)
            return;
        try
        {
            IViewPart view = page.showView(IDebugUIConstants.ID_DEBUG_VIEW, null, IWorkbenchPage.VIEW_ACTIVATE);
            // LaunchView.show() использует setSelection(selection, true, true): штатный
            // механизм принудительно выбирает предмет с раскрытием пути.
            if (view instanceof IShowInTarget showInTarget)
                showInTarget.show(new ShowInContext(null, new StructuredSelection(target)));
        }
        catch (CoreException | RuntimeException e)
        {
            // Подключение остаётся доступно, даже если панель не удалось открыть.
        }
    }
}
