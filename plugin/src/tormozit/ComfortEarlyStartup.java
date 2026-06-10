package tormozit;

import org.eclipse.swt.widgets.Display;

/**
 * Единая точка отложенного старта хуков {@code org.eclipse.ui.startup}.
 */
public final class ComfortEarlyStartup
{
    private ComfortEarlyStartup()
    {
    }

    /**
     * Планирует {@code body} на UI-потоке после готовности платформы EDT.
     */
    public static void defer(Runnable body)
    {
        if (body == null)
            return;

        Display display = Display.getDefault();
        if (display == null)
            return;

        display.asyncExec(() -> EdtPlatformGate.runWhenReady(body));
    }
}
