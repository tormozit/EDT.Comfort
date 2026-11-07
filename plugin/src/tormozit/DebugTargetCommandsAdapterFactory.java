package tormozit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.debug.core.IRequest;
import org.eclipse.debug.core.commands.AbstractDebugCommand;
import org.eclipse.debug.core.commands.IDebugCommandRequest;
import org.eclipse.debug.core.commands.IEnabledStateRequest;
import org.eclipse.debug.core.commands.ISuspendHandler;
import org.eclipse.debug.core.commands.ITerminateHandler;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;

/**
 * Доступность штатных команд «Приостановить»/«Прекратить» для узлов EDT в панели «Отладка».
 *
 * <p>Предмет отладки (issue #881). EDT debug.core 19.0.1.v202607090722:
 * RuntimeDebugTargetThread.canTerminate() всегда возвращает false, но terminate() вызывает
 * terminateRuntimeDebugTargets() и уведомляет модель о завершении предмета. Используем этот
 * штатный путь вместо отсоединения или завершения всего серверного подключения.
 *
 * <p>Подключение к серверу отладки. EDT debug.core 18.0.0.v202605050943:
 * RuntimeDebugClientTarget.canSuspend()/canTerminate() не учитывают подключённые предметы,
 * поэтому команды доступны и у подключения без предметов. Здесь они доступны только при
 * наличии подключённого предмета; выполнение штатное. Завершить подключение без предметов
 * можно командами «Завершить и удалить»/«Завершить всё» — они проверяют запуск.
 *
 * <p>Адаптеры зарегистрированы на конкретные классы EDT: они имеют приоритет перед общими
 * адаптерами Eclipse для ITerminate/ISuspendResume и не меняют команды других моделей.
 */
public final class DebugTargetCommandsAdapterFactory implements IAdapterFactory
{
    private static final ITerminateHandler THREAD_TERMINATE = new ThreadTerminateCommand();
    private static final ITerminateHandler CLIENT_TERMINATE = new ClientTerminateCommand();
    private static final ISuspendHandler CLIENT_SUSPEND = new ClientSuspendCommand();

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType)
    {
        if (adaptableObject instanceof IRuntimeDebugTargetThread)
            return adapterType == ITerminateHandler.class ? adapterType.cast(THREAD_TERMINATE) : null;
        if (adaptableObject instanceof IRuntimeDebugClientTarget)
        {
            if (adapterType == ITerminateHandler.class)
                return adapterType.cast(CLIENT_TERMINATE);
            if (adapterType == ISuspendHandler.class)
                return adapterType.cast(CLIENT_SUSPEND);
        }
        return null;
    }

    @Override
    public Class<?>[] getAdapterList()
    {
        return new Class<?>[] { ITerminateHandler.class, ISuspendHandler.class };
    }

    /** Есть ли у подключения хотя бы один подключённый предмет (дочерний узел в панели «Отладка»). */
    private static boolean hasConnectedThreads(IRuntimeDebugClientTarget target)
    {
        for (IRuntimeDebugTargetThread thread : target.getThreads())
            if (!thread.isTerminated() && !thread.isDisconnected())
                return true;
        return false;
    }

    /**
     * Общее для команд: проверки доступности одной команды не выполняются параллельно
     * (как у ForEachCommand Eclipse), семейство задач проверки — по типу обработчика.
     */
    private abstract static class SerialCommand<E> extends AbstractDebugCommand
    {
        private final Class<E> elementType;
        private final Class<?> handlerType;
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

        SerialCommand(Class<E> elementType, Class<?> handlerType)
        {
            this.elementType = elementType;
            this.handlerType = handlerType;
        }

        protected abstract boolean canExecute(E element);

        protected abstract void execute(E element) throws CoreException;

        @Override
        protected Object getTarget(Object element)
        {
            return getAdapter(element, elementType);
        }

        @Override
        protected boolean isExecutable(Object[] targets, IProgressMonitor monitor, IEnabledStateRequest request)
        {
            for (Object target : targets)
            {
                if (!elementType.isInstance(target) || !canExecute(elementType.cast(target)))
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
                if (elementType.isInstance(target) && canExecute(elementType.cast(target)))
                    execute(elementType.cast(target));
                monitor.worked(1);
            }
        }

        @Override
        protected Object getEnabledStateJobFamily(IDebugCommandRequest request)
        {
            return handlerType;
        }

        @Override
        protected ISchedulingRule getEnabledStateSchedulingRule(IDebugCommandRequest request)
        {
            return enabledStateRule;
        }
    }

    private static final class ThreadTerminateCommand extends SerialCommand<IRuntimeDebugTargetThread>
        implements ITerminateHandler
    {
        ThreadTerminateCommand()
        {
            super(IRuntimeDebugTargetThread.class, ITerminateHandler.class);
        }

        @Override
        protected boolean canExecute(IRuntimeDebugTargetThread thread)
        {
            // canDisconnect() EDT не исключает TERMINATED, поэтому проверяем оба состояния явно.
            return !thread.isTerminated() && !thread.isDisconnected() && thread.canDisconnect();
        }

        @Override
        protected void execute(IRuntimeDebugTargetThread thread) throws CoreException
        {
            thread.terminate();
        }
    }

    private static final class ClientTerminateCommand extends SerialCommand<IRuntimeDebugClientTarget>
        implements ITerminateHandler
    {
        ClientTerminateCommand()
        {
            super(IRuntimeDebugClientTarget.class, ITerminateHandler.class);
        }

        @Override
        protected boolean canExecute(IRuntimeDebugClientTarget target)
        {
            return target.canTerminate() && hasConnectedThreads(target);
        }

        @Override
        protected void execute(IRuntimeDebugClientTarget target) throws CoreException
        {
            target.terminate();
        }
    }

    private static final class ClientSuspendCommand extends SerialCommand<IRuntimeDebugClientTarget>
        implements ISuspendHandler
    {
        ClientSuspendCommand()
        {
            super(IRuntimeDebugClientTarget.class, ISuspendHandler.class);
        }

        @Override
        protected boolean canExecute(IRuntimeDebugClientTarget target)
        {
            return target.canSuspend() && hasConnectedThreads(target);
        }

        @Override
        protected void execute(IRuntimeDebugClientTarget target) throws CoreException
        {
            target.suspend();
        }
    }
}
