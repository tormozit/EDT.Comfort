package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.FrameworkUtil;

/**
 * ВРЕМЕННО — ручная проверка {@link SystemOverloadToastHook} без реальной перегрузки CPU/GC.
 * Один вызов команды {@code tormozit.debug.testOverloadToast} (Ctrl+Shift+Alt+F12) шлёт в
 * платформенный лог одну {@code WARNING}-запись как от {@code ResourceManagementPlugin} — хук её
 * подхватывает тем же путём, что и настоящую, и должен тут же показать тост «Перегрузка системы».
 * Повторные нажатия быстрее {@code EPISODE_GAP_MS} (5 мин) новый тост показывать не должны —
 * так проверяется дедупликация без ожидания.
 *
 * <p>Убрать вместе с регистрацией команды/обработчика/биндинга в {@code plugin.xml} после проверки.
 */
public final class SystemOverloadToastTestHandler extends AbstractHandler
{
    private static final String RESOURCE_MANAGEMENT_PLUGIN_ID =
        "com._1c.g5.v8.internal.resourcemanagement.ResourceManagementPlugin"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        ILog log = Platform.getLog(FrameworkUtil.getBundle(SystemOverloadToastTestHandler.class));
        log.log(new Status(IStatus.WARNING, RESOURCE_MANAGEMENT_PLUGIN_ID,
            "CPU overload. Memory 1 used of 2")); //$NON-NLS-1$
        return null;
    }
}
