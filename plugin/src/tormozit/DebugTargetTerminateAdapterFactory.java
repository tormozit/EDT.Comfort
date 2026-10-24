package tormozit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.debug.core.IRequest;
import org.eclipse.debug.core.commands.AbstractDebugCommand;
import org.eclipse.debug.core.commands.IDebugCommandRequest;
import org.eclipse.debug.core.commands.IEnabledStateRequest;
import org.eclipse.debug.core.commands.ITerminateHandler;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;

/**
 * Делает штатную команду «Прекратить» доступной для предметов отладки (issue #881).
 * EDT debug.core 19.0.1.v202607090722: RuntimeDebugTargetThread.canTerminate()
 * всегда возвращает false, но terminate() вызывает terminateRuntimeDebugTargets()
 * и уведомляет модель о завершении предмета. Используем этот штатный путь вместо
 * отсоединения или завершения всего серверного подключения.
 *
 * <p>Адаптер зарегистрирован на конкретный класс EDT: он имеет приоритет перед
 * общим адаптером Eclipse для ITerminate и не меняет команды других моделей.
 */
public final class DebugTargetTerminateAdapterFactory implements IAdapterFactory
{
    private static final ITerminateHandler TERMINATE = new TerminateCommand();

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType)
    {
        return adapterType == ITerminateHandler.class && adaptableObject instanceof IRuntimeDebugTargetThread
            ? adapterType.cast(TERMINATE) : null;
    }

    @Override
    public Class<?>[] getAdapterList()
    {
        return new Class<?>[] { ITerminateHandler.class };
    }

    private static final class TerminateCommand extends AbstractDebugCommand implements ITerminateHandler
    {
        // Как у ForEachCommand Eclipse: проверки разных выделений не выполняются параллельно.
        private final ISchedulingRule enabledStateRule = new ISchedulingRule()
        {
            @Override
            public boolean contains(ISchedulingRule rule)
            {
                return rule == this;
            }

            @Override
            public boolean isConflicting(ISchedulingRule rule)
            {
                return rule == this;
            }
        };

        @Override
        protected Object getTarget(Object element)
        {
            return getAdapter(element, IRuntimeDebugTargetThread.class);
        }

        private static boolean canTerminate(IRuntimeDebugTargetThread target)
        {
            // canDisconnect() EDT не исключает TERMINATED, поэтому проверяем оба состояния явно.
            return !target.isTerminated() && !target.isDisconnected() && target.canDisconnect();
        }

        @Override
        protected boolean isExecutable(Object[] targets, IProgressMonitor monitor, IEnabledStateRequest request)
        {
            for (Object target : targets)
            {
                if (!(target instanceof IRuntimeDebugTargetThread thread) || !canTerminate(thread))
                    return false;
                monitor.worked(1);
            }
            return targets.length > 0;
        }

        @Override
        protected void doExecute(Object[] targets, IProgressMonitor monitor, IRequest request) throws CoreException
        {
            for (Object target : targets)
            {
                if (monitor.isCanceled() || request.isCanceled())
                    return;
                if (target instanceof IRuntimeDebugTargetThread thread && canTerminate(thread))
                    thread.terminate();
                monitor.worked(1);
            }
        }

        @Override
        protected Object getEnabledStateJobFamily(IDebugCommandRequest request)
        {
            return ITerminateHandler.class;
        }

        @Override
        protected ISchedulingRule getEnabledStateSchedulingRule(IDebugCommandRequest request)
        {
            return enabledStateRule;
        }
    }
}
