

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import tormozit.edt.assist.ContentAssistAutoOpenManager;
import tormozit.edt.assist.ContentAssistAutoOpenSettings;

/**
 * Activator (точка входа) плагина EDT Compare - Open Object.
 *
 * <p><b>Важно:</b> В {@code start()} выполняется ТОЛЬКО инициализация
 * синглтонов без UI-кода — никакого {@code syncExec} или обращений к
 * Workbench. {@link ContentAssistAutoOpenManager#start()} вызывается
 * позже из {@code CompareConfigMenuHook.earlyStartup()}, когда Workbench
 * гарантированно инициализирован.
 */
public class Activator extends AbstractUIPlugin
{
    public static final String PLUGIN_ID = "tormozit.edt"; //$NON-NLS-1$

    private static Activator instance;

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        instance = this;

        // Только инициализация синглтонов — никакого UI и обращений к Workbench.
        // manager.start() будет вызван из earlyStartup() после запуска Workbench.
        ContentAssistAutoOpenSettings settings =
            ContentAssistAutoOpenSettings.init(PLUGIN_ID);
        ContentAssistAutoOpenManager.init(settings);
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
//        IRApplicationRegistry.disconnectAll(); // TODO

        ContentAssistAutoOpenManager mgr = ContentAssistAutoOpenManager.getInstance();
        if (mgr != null)
            mgr.stop();

        instance = null;
        super.stop(context);
    }

    public static Activator getDefault()
    {
        return instance;
    }
}
