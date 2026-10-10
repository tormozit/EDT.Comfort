package tormozit;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;

/**
 * Устраняет лишнее ожидание снимка перед переключением ветки EDT.
 * DtExclusiveContext.doRelease(INITIAL) удаляет проект, но не уменьшает
 * numWaiting; поздний callback освобождённой операции тоже его не уменьшает.
 * Пустой контекст из-за этого ждёт 15 секунд. Обход действует только при
 * подтверждённом ожидании checkout на мониторе счётчика пустого контекста.
 * Поля проверены по JAR EDT, включая EDT 2026.1.
 */
public final class GitBranchSwitchHook implements IStartup
{
    private static final String SNAPSHOT_CONTEXT = "com.e1c.g5.v8.dt.internal.snapshot.DtExclusiveContext";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override
    public void earlyStartup()
    {
        if (!INSTALLED.compareAndSet(false, true))
            return;
        Display display = PlatformUI.getWorkbench().getDisplay();
        Timer timer = new Timer("ComfortGitBranchSwitch", true);
        timer.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                if (display.isDisposed())
                {
                    timer.cancel();
                    return;
                }
                try { completeStaleCheckoutWaits(); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError error)
                {
                    GitBranchSwitchDebug.problem(error);
                }
            }
        }, 1000, 1000);
    }

    private static void completeStaleCheckoutWaits() throws ReflectiveOperationException
    {
        IWorkspaceOrchestrator orchestrator = Global.getOsgiService(IWorkspaceOrchestrator.class);
        if (orchestrator == null)
            return;
        Object projectOrchestrator = read(orchestrator, "projectOrchestrator");
        Object listeners = read(projectOrchestrator, "listeners");
        if (!(listeners instanceof Collection<?> collection))
            return;
        for (Object wrapper : collection.toArray())
        {
            if (!wrapper.getClass().getName().endsWith("WorkspaceOrchestrator$ProjectOrchestratorListener"))
                continue;
            Object delegate = read(wrapper, "delegate");
            if (delegate == null || !delegate.getClass().getName().startsWith(SNAPSHOT_CONTEXT + "$$Lambda$"))
                continue;
            // this::processOrchestratorEvent захватывает DtExclusiveContext.
            // Имя синтетического поля JVM может отличаться, тип подтверждён кодом EDT.
            for (Field field : delegate.getClass().getDeclaredFields())
            {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(SNAPSHOT_CONTEXT))
                    continue;
                field.setAccessible(true);
                Object context = field.get(delegate);
                Object projects = read(context, "projects");
                Object counter = read(context, "numWaiting");
                if (projects instanceof Map<?, ?> projectMap && projectMap.isEmpty()
                    && counter instanceof AtomicInteger atomicCounter && atomicCounter.get() > 0)
                    completeStaleWait(context, projectMap, atomicCounter);
            }
        }
    }

    private static boolean isCheckoutWaiter(ThreadInfo thread, AtomicInteger counter)
    {
        if (thread == null || thread.getLockInfo() == null
            || (thread.getThreadState() != Thread.State.WAITING
                && thread.getThreadState() != Thread.State.TIMED_WAITING)
            || thread.getLockInfo().getIdentityHashCode() != System.identityHashCode(counter)
            || !thread.getLockInfo().getClassName().equals(counter.getClass().getName()))
            return false;
        boolean acquisition = false;
        boolean checkout = false;
        for (StackTraceElement frame : thread.getStackTrace())
        {
            acquisition |= frame.getClassName().equals(SNAPSHOT_CONTEXT)
                && frame.getMethodName().equals("waitAcquisition");
            checkout |= frame.getClassName()
                .equals("com._1c.g5.v8.dt.internal.team.ui.BranchCheckoutManager");
        }
        return acquisition && checkout;
    }

    private static void completeStaleWait(Object context, Map<?, ?> projects, AtomicInteger counter)
        throws ReflectiveOperationException
    {
        // Стеки нужны только для пустого контекста с положительным счётчиком.
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        ThreadInfo[] candidates = threads.getThreadInfo(threads.getAllThreadIds(), 128);
        synchronized (counter)
        {
            int before = counter.get();
            if (read(context, "projects") != projects || read(context, "numWaiting") != counter
                || !projects.isEmpty() || before <= 0)
                return;
            for (ThreadInfo candidate : candidates)
            {
                if (!isCheckoutWaiter(candidate, counter))
                    continue;
                // Перепроверяем живой поток под монитором, на котором ждёт EDT.
                ThreadInfo current = threads.getThreadInfo(candidate.getThreadId(), 128);
                if (!isCheckoutWaiter(current, counter))
                    continue;
                if (!projects.isEmpty() || !counter.compareAndSet(before, 0))
                    return;
                counter.notifyAll();
                GitBranchSwitchDebug.corrected(before);
                return;
            }
        }
    }

    private static Object read(Object object, String name) throws ReflectiveOperationException
    {
        if (object == null)
            return null;
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass())
            try
            {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            }
            catch (NoSuchFieldException missing) { /* искать в базовом типе */ }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    private static final class GitBranchSwitchDebug
    {
        private static final String TAG = "GitBranchSwitch";

        private static void corrected(int before)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, "Завершено ложное ожидание снимка перед переключением ветки: счётчик " + before + " → 0");
        }

        private static void problem(Throwable error)
        {
            if (Global.isLogEnabled())
                Global.log(TAG, "[!] Не удалось проверить ожидание снимка: " + error);
        }
    }
}
