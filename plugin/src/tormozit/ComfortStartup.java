package tormozit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Единственная точка {@code org.eclipse.ui.startup} плагина. Eclipse гоняет все
 * {@code <startup>} одного расширения в одном {@code EarlyStartupRunnable}: исключение
 * при создании класса или в {@code earlyStartup} обрывает остальные хуки. Поэтому сами хуки
 * объявлены в {@code tormozit.comfort.startup}, а здесь каждый создаётся и вызывается
 * в своём {@code try/catch}.
 */
public final class ComfortStartup implements IStartup
{
    private static final String TAG = "ComfortStartup"; //$NON-NLS-1$

    /**
     * Накопитель падений по сеансам — не в {@code temp-logs}, чтобы следующий старт его не стёр.
     */
    private static final Path FAILURES_FILE =
        Path.of("C:\\VC\\EDT.Comfort\\.tmp\\comfort-startup-failures.log"); //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Bundle bundle = FrameworkUtil.getBundle(ComfortStartup.class);
        String pointId = bundle.getSymbolicName() + ".startup"; //$NON-NLS-1$
        IConfigurationElement[] hooks;
        try
        {
            hooks = Platform.getExtensionRegistry().getConfigurationElementsFor(pointId);
        }
        catch (Throwable t)
        {
            fail(bundle, "реестр расширений " + pointId, t); //$NON-NLS-1$
            return;
        }
        if (hooks == null || hooks.length == 0)
        {
            fail(bundle, "нет хуков в " + pointId, null); //$NON-NLS-1$
            return;
        }
        int ok = 0;
        int failed = 0;
        for (IConfigurationElement element : hooks)
        {
            if (!"startup".equals(element.getName())) //$NON-NLS-1$
                continue;
            String className = element.getAttribute("class"); //$NON-NLS-1$
            if (className == null || className.isBlank())
            {
                fail(bundle, "элемент без class", null); //$NON-NLS-1$
                failed++;
                continue;
            }
            Object instance;
            try
            {
                instance = element.createExecutableExtension("class"); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                fail(bundle, "создание " + className, t); //$NON-NLS-1$
                failed++;
                continue;
            }
            if (!(instance instanceof IStartup startup))
            {
                String type = instance == null ? "null" : instance.getClass().getName(); //$NON-NLS-1$
                fail(bundle, className + " не IStartup (" + type + ")", null); //$NON-NLS-1$ //$NON-NLS-2$
                failed++;
                continue;
            }
            try
            {
                startup.earlyStartup();
                ok++;
            }
            catch (Throwable t)
            {
                fail(bundle, "earlyStartup " + className, t); //$NON-NLS-1$
                failed++;
            }
        }
        if (failed > 0)
            platformLog(bundle, IStatus.ERROR,
                "старт хуков: ok=" + ok + ", с ошибкой=" + failed, null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void fail(Bundle bundle, String context, Throwable error)
    {
        appendFailureFile(context, error);
        platformLog(bundle, IStatus.ERROR, context, error);
    }

    private static void platformLog(Bundle bundle, int severity, String message, Throwable error)
    {
        if (bundle == null)
            return;
        String text = "[" + TAG + "] " + message; //$NON-NLS-1$ //$NON-NLS-2$
        Platform.getLog(bundle).log(new Status(severity, bundle.getSymbolicName(), text, error));
    }

    private static void appendFailureFile(String context, Throwable error)
    {
        StringWriter sw = new StringWriter();
        sw.append(java.time.LocalDateTime.now().toString());
        sw.append(' ');
        sw.append(context == null ? "" : context); //$NON-NLS-1$
        sw.append(System.lineSeparator());
        if (error != null)
            error.printStackTrace(new PrintWriter(sw));
        sw.append(System.lineSeparator());
        try
        {
            Path parent = FAILURES_FILE.getParent();
            if (parent != null)
                Files.createDirectories(parent);
            Files.writeString(FAILURES_FILE, sw.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Exception ignored)
        {
        }
    }
}
