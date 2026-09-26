package tormozit;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpoint;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointListener;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslBreakpointListenerManager;
import com._1c.g5.v8.dt.debug.core.model.breakpoints.IBslLineBreakpoint;

/**
 * Запросы отлаживаемого приложения к EDT. Команда «Открыть в конфигураторе» приходит колбэком
 * {@link IBslBreakpointListener#showMetadataObjectRequested}: штатный {@code BslBreakpointManager}
 * открывает редактор объекта, но окно EDT не выводит на передний план, если оно свёрнуто или
 * перекрыто окном приложения. Подписываемся в тот же {@link IBslBreakpointListenerManager}
 * и активируем окно EDT.
 */
public final class DebugRequestsHook implements IStartup
{
    private static final String DEBUG_CORE_BUNDLE = "com._1c.g5.v8.dt.debug.core"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(DebugRequestsHook::install);
    }

    private static void install()
    {
        try
        {
            Bundle bundle = Platform.getBundle(DEBUG_CORE_BUNDLE);
            if (bundle == null)
                return;
            Class<?> pluginCls = bundle.loadClass("com._1c.g5.v8.dt.internal.debug.core.DebugCorePlugin"); //$NON-NLS-1$
            Object plugin = Global.invoke(pluginCls, "getDefault"); //$NON-NLS-1$
            Object injectorObj = Global.invoke(plugin, "getInjector"); //$NON-NLS-1$
            if (!(injectorObj instanceof com.google.inject.Injector injector))
                return;
            // Типизированный вызов: у Injector.getInstance две одноаргументные перегрузки (Class и Key).
            injector.getInstance(IBslBreakpointListenerManager.class).addListener(new ShowMetadataListener());
        }
        catch (Exception e)
        {
            Global.logError("DebugRequestsHook", "install", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static final class ShowMetadataListener implements IBslBreakpointListener
    {
        @Override
        public void showMetadataObjectRequested(URI uri)
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            // Штатный слушатель открывает редактор в своём asyncExec; порядок с нашим не важен —
            // окно EDT активируется в любом случае.
            display.asyncExec(ShowMetadataListener::activateEdt);
        }

        private static void activateEdt()
        {
            if (WinWindowActivator.activateWorkbench())
                return;
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            Shell shell = window != null ? window.getShell() : null;
            if (shell != null && !shell.isDisposed())
                shell.forceActive();
        }

        @Override
        public Action breakpointHit(IBslBreakpoint breakpoint)
        {
            // SKIP нейтрален: менеджер выбирает SUSPEND, если его вернул хотя бы один слушатель.
            return Action.SKIP;
        }

        @Override
        public void breakpointHasRuntimeError(IBslLineBreakpoint breakpoint, String message, String details)
        {
        }

        @Override
        public void showErrorRequested(URI uri, int line, int column, String message, String details, String extra)
        {
        }
    }
}
