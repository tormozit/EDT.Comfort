package tormozit;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.ui.IStartup;

/**
 * Закрывает окна «Коллекция» при завершении отладки.
 */
public final class DebugCollectionSessionHook implements IStartup
{
    private static volatile boolean installed;

    @Override
    public void earlyStartup()
    {
        if (installed)
            return;
        installed = true;
        IDebugEventSetListener listener = events -> {
            for (DebugEvent event : events)
            {
                if (event.getKind() == DebugEvent.TERMINATE && event.getSource() instanceof IDebugTarget)
                {
                    DebugCollectionWindowRegistry.disposeAll();
                    DebugCollectionDebug.step("session", "debug terminate → dispose windows"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        };
        DebugPlugin.getDefault().addDebugEventListener(listener);
    }
}
