package tormozit;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.core.Launch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.ui.IStartup;

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
 * При новом запуске удаляем старые клиентские запуски с завершёнными процессами,
 * которые удерживает общий объект сохранённого серверного подключения.
 */
public final class DebugServerConnectionHook implements IStartup, ILaunchesListener2
{
    private static final String CORE_PREFIX = "com._1c.g5.v8.dt.debug.core."; //$NON-NLS-1$
    private static final String KEEP_SERVER_LAUNCH = CORE_PREFIX + "ATTR_KEEP_SERVER_LAUNCH"; //$NON-NLS-1$
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override
    public void earlyStartup()
    {
        if (!INSTALLED.compareAndSet(false, true))
            return;
        DebugPlugin plugin = DebugPlugin.getDefault();
        ILaunchManager manager = plugin.getLaunchManager();
        manager.addLaunchListener(this);
        update(manager.getLaunches());
    }

    @Override
    public void launchesAdded(ILaunch[] launches)
    {
        // Синхронное уведомление менеджера запусков: параметр ставится до первого вызова сервиса.
        update(launches);
        removeFinishedClientLaunches(launches);
    }

    @Override
    public void launchesChanged(ILaunch[] launches)
    {
        update(launches);
    }

    @Override
    public void launchesRemoved(ILaunch[] launches)
    {
    }

    @Override
    public void launchesTerminated(ILaunch[] launches)
    {
    }

    /** Дополняет штатную очистку, сохраняя её настройку и серверное подключение. */
    private static void removeFinishedClientLaunches(ILaunch[] added)
    {
        try
        {
            boolean enabled = DebugUITools.getPreferenceStore()
                .getBoolean(IDebugUIConstants.PREF_AUTO_REMOVE_OLD_LAUNCHES);
            if (!enabled)
                return;
            ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
            for (ILaunch launch : manager.getLaunches())
            {
                try
                {
                    boolean removable = canRemoveFinishedClientLaunch(launch, added, manager);
                    if (!removable)
                        continue;
                    // removeLaunch снимает запуск из модели без terminate/disconnect общего объекта.
                    manager.removeLaunch(launch);
                }
                catch (CoreException | RuntimeException e)
                {
                    // Ошибка очистки не должна прерывать новый запуск.
                }
            }
        }
        catch (RuntimeException e)
        {
            // Недоступность настройки не должна прерывать новый запуск.
        }
    }

    /** Завершённый клиент удерживается только отдельным сохранённым сервером. */
    private static boolean canRemoveFinishedClientLaunch(ILaunch launch, ILaunch[] added, ILaunchManager manager)
        throws CoreException
    {
        for (ILaunch current : added)
            if (launch == current)
                return false;
        ILaunchConfiguration configuration = launch.getLaunchConfiguration();
        if (configuration == null || !ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())
            || !"com._1c.g5.v8.dt.launching.core.RuntimeClient".equals(configuration.getType().getIdentifier())) //$NON-NLS-1$
            return false;
        IProcess[] processes = launch.getProcesses();
        if (processes.length == 0)
            return false;
        for (IProcess process : processes)
            if (!process.isTerminated())
                return false;
        IDebugTarget[] targets = launch.getDebugTargets();
        if (targets.length == 0)
            return false;
        for (IDebugTarget target : targets)
        {
            ILaunch owner = target.getLaunch();
            if (!(target instanceof IRuntimeDebugClientTarget) || owner == null || owner == launch
                || !manager.isRegistered(owner) || target.isTerminated() || target.isDisconnected())
                return false;
            ILaunchConfiguration server = owner.getLaunchConfiguration();
            if (server == null || !server.getAttribute(CORE_PREFIX + "ATTR_SERVER_LAUNCH", false) //$NON-NLS-1$
                || !server.getAttribute(KEEP_SERVER_LAUNCH, false))
                return false;
        }
        return true;
    }

    private static void update(ILaunch[] launches)
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

}
