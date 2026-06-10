package tormozit;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceUnavailableException;

/**
 * Ожидает регистрации ключевых сервисов EDT (в первую очередь
 * {@link IResourceLookup}) перед запуском хуков, обращающихся к BSL/редакторам.
 */
public final class EdtPlatformGate
{
    private static final long POLL_INTERVAL_MS = 100;
    private static final int MAX_ATTEMPTS = 600;

    private EdtPlatformGate()
    {
    }

    public static boolean isResourceLookupReady()
    {
        try
        {
            return ServiceAccess.get(IResourceLookup.class) != null;
        }
        catch (ServiceUnavailableException e)
        {
            return false;
        }
    }

    /**
     * Выполняет {@code action} на UI-потоке после готовности {@link IResourceLookup}.
     */
    public static void runWhenReady(Runnable action)
    {
        if (action == null)
            return;

        if (isResourceLookupReady())
        {
            scheduleOnUiThread(action);
            return;
        }

        Job gateJob = Job.create("EDT Comfort: platform gate", monitor -> { //$NON-NLS-1$
            long startMs = System.currentTimeMillis();
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++)
            {
                if (monitor.isCanceled())
                    return org.eclipse.core.runtime.Status.CANCEL_STATUS;

                if (isResourceLookupReady())
                {
                    long elapsedMs = System.currentTimeMillis() - startMs;
                    ComfortDebug.log("startup", //$NON-NLS-1$
                        "IResourceLookup ready after " + elapsedMs + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
                    scheduleOnUiThread(action);
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                }

                try
                {
                    Thread.sleep(POLL_INTERVAL_MS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return org.eclipse.core.runtime.Status.CANCEL_STATUS;
                }
            }

            ComfortDebug.log("startup", //$NON-NLS-1$
                "EDT core services not ready after timeout"); //$NON-NLS-1$
            return org.eclipse.core.runtime.Status.OK_STATUS;
        });
        gateJob.setSystem(true);
        gateJob.schedule();
    }

    private static void scheduleOnUiThread(Runnable action)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(action);
    }
}
