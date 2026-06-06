package tormozit;


import org.eclipse.core.runtime.Plugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexProvider;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.wiring.AbstractGuiceAwareExecutableExtensionFactory;
import com._1c.g5.wiring.AbstractServiceAwareModule;
import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * Activator (точка входа) плагина EDT Compare - Open Object.
 *
 * <p><b>Важно:</b> В {@code start()} выполняется ТОЛЬКО инициализация
 * синглтонов без UI-кода — никакого {@code syncExec} или обращений к
 * Workbench. {@link ContentAssistManager#start()} вызывается
 * позже из {@code CompareConfigMenuHook.earlyStartup()}, когда Workbench
 * гарантированно инициализирован.
 */
public class Activator extends AbstractUIPlugin
{
    public static final String PLUGIN_ID = "tormozit"; //$NON-NLS-1$

    private static Activator instance;

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        instance = this;

        // Только инициализация синглтонов — никакого UI и обращений к Workbench.
        // manager.start() будет вызван из earlyStartup() после запуска Workbench.
        ContentAssistSettings settings =
            ContentAssistSettings.init(PLUGIN_ID);
        ContentAssistManager.init(settings);

        // Подключаем персистентное хранилище последних мест.
        RecentPlaces.getInstance().init(PLUGIN_ID);
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
//        IRApplication.disconnectAll(); // TODO

        ContentAssistManager mgr = ContentAssistManager.getInstance();
        if (mgr != null)
            mgr.stop();

        instance = null;
        super.stop(context);
    }

    public static Activator getDefault()
    {
        return instance;
    }

    public class ExternalDependenciesModule extends AbstractServiceAwareModule
    {
        public ExternalDependenciesModule(Plugin bundle)
        {
            super(bundle);
        }
        @Override
        protected void configure()
        {
            bind(IConfigurationProjectManager.class).toService();
            bind(IV8ProjectManager.class).toService();
            bind(IBmModelManager.class).toService();
            bind(IResourceLookup.class).toService();
            bind(IRuntimeVersionSupport.class).toService();
            bind(IExportOperationFactory.class).toService();
        }
        @Override
        protected void doConfigure()
        {
            // TODO Auto-generated method stub
        }
    }

    private Injector injector;
    public synchronized Injector getInjector() {
        if (injector == null)
            injector = createInjector();
        return injector;
    }
    private Injector createInjector() {
        return Guice.createInjector(new ExternalDependenciesModule(this));
    }
}
