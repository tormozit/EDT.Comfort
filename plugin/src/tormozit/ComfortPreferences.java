package tormozit;



import java.lang.reflect.Field;

import java.lang.reflect.InvocationTargetException;

import java.lang.reflect.Method;

import java.net.URI;



import org.eclipse.core.runtime.ILog;

import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.core.runtime.IStatus;

import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.core.runtime.Platform;

import org.eclipse.core.runtime.Status;

import org.eclipse.swt.custom.BusyIndicator;

import org.eclipse.swt.widgets.Display;

import org.eclipse.swt.widgets.Shell;

import org.osgi.framework.Bundle;

import org.eclipse.ui.PlatformUI;

import org.eclipse.ui.browser.IWorkbenchBrowserSupport;

import org.eclipse.ui.dialogs.PreferencesUtil;

import org.eclipse.ui.IWorkbenchWindow;



/**

 * Открытие страницы настроек плагина, окна «Установить новое ПО» и описаний релизов.

 */

public final class ComfortPreferences

{

    /** Идентификатор страницы «Параметры → Комфорт». */

    public static final String PREFERENCE_PAGE_ID = "tormozit.ComfortPreferencePages"; //$NON-NLS-1$



    private static final String NICKNAME_EDT_COMFORT = "EDT Comfort"; //$NON-NLS-1$

    private static final String REPO_NICKNAME_PROPERTY = "p2.nickname"; //$NON-NLS-1$



    private static final String BUNDLE_P2_UI = "org.eclipse.equinox.p2.ui"; //$NON-NLS-1$

    private static final String BUNDLE_P2_OPERATIONS = "org.eclipse.equinox.p2.operations"; //$NON-NLS-1$

    private static final String BUNDLE_P2_ENGINE = "org.eclipse.equinox.p2.engine"; //$NON-NLS-1$



    private ComfortPreferences()

    {

    }



    /** Открывает окно «Параметры» на странице настроек плагина. */

    public static void openComfortPreferencePage()

    {

        Display display = Display.getDefault();

        if (display == null || display.isDisposed())

            return;

        Runnable open = () -> {

            Shell shell = resolveShell();

            PreferencesUtil.createPreferenceDialogOn(

                shell,

                PREFERENCE_PAGE_ID,

                null,

                null).open();

        };

        if (display.getThread() == Thread.currentThread())

            open.run();

        else

            display.asyncExec(open);

    }



    /**

     * Открывает «Справка → Установить новое ПО…» с сайтом

     * {@link ComfortUpdateChecker#UPDATE_SITE_URL}.

     */

    public static void openInstallNewSoftware()

    {

        openInstallNewSoftware(ComfortUpdateChecker.UPDATE_SITE_URL);

    }



    private static void openInstallNewSoftware(String siteUrl)

    {

        Display display = Display.getDefault();

        if (display == null || display.isDisposed())

            return;



        Runnable open = () -> {

            ComfortDebug.log("install", "openInstallNewSoftware site=" + siteUrl); //$NON-NLS-1$ //$NON-NLS-2$

            if (tryOpenInstallWizardForSite(siteUrl))

            {

                ComfortDebug.log("install", "мастер установки открыт"); //$NON-NLS-1$ //$NON-NLS-2$

                return;

            }

            String message = isP2SelfUpdateAvailable()

                ? "Не удалось открыть окно «Установить новое ПО»." //$NON-NLS-1$

                : "Установщик недоступен в этой среде (нет p2-профиля). Проверьте в установленном EDT."; //$NON-NLS-1$

            ComfortDebug.log("install", "FAIL: " + message); //$NON-NLS-1$ //$NON-NLS-2$

            ToastNotification.show("EDT Comfort", message, 8_000); //$NON-NLS-1$

        };



        if (display.getThread() == Thread.currentThread())

            open.run();

        else

            display.asyncExec(open);

    }



    /** Открывает страницу описания релиза во внешнем браузере. */

    public static void openChangesUrl(String url)

    {

        if (url == null || url.isBlank())

            return;

        openExternalUrl(url);

    }



    private static boolean tryOpenInstallWizardForSite(String siteUrl)

    {

        try

        {

            openInstallWizardForSite(siteUrl);

            return true;

        }

        catch (Exception e)

        {

            logError("Установить новое ПО", e); //$NON-NLS-1$

            return false;

        }

    }



    private static void openInstallWizardForSite(String siteUrl) throws Exception

    {

        URI siteUri = URI.create(normalizeSiteUrl(siteUrl));

        ComfortDebug.log("install", "siteUri=" + siteUri); //$NON-NLS-1$ //$NON-NLS-2$



        Class<?> uiClass = loadBundleClass(BUNDLE_P2_UI,

            "org.eclipse.equinox.p2.ui.ProvisioningUI"); //$NON-NLS-1$

        Object ui = uiClass.getMethod("getDefaultUI").invoke(null); //$NON-NLS-1$



        if (!ensureP2ProfileAvailable(ui, uiClass))

            throw new IllegalStateException("no p2 profile"); //$NON-NLS-1$



        registerUpdateSite(ui, uiClass, siteUri);

        logKnownRepositories(ui, uiClass, "after registerUpdateSite"); //$NON-NLS-1$



        Class<?> jobClass = loadBundleClass(BUNDLE_P2_UI,

            "org.eclipse.equinox.p2.ui.LoadMetadataRepositoryJob"); //$NON-NLS-1$

        Object job = jobClass.getConstructor(uiClass).newInstance(ui);

        configureLoadJob(job, jobClass);

        runLoadJobModal(job, jobClass);

        logLoadJobStatus(job, jobClass);



        Class<?> installOpClass = loadBundleClass(BUNDLE_P2_OPERATIONS,

            "org.eclipse.equinox.p2.operations.InstallOperation"); //$NON-NLS-1$

        Class<?> installWizardClass = loadBundleClass(BUNDLE_P2_UI,

            "org.eclipse.equinox.internal.p2.ui.dialogs.InstallWizard"); //$NON-NLS-1$

        Object wizard = installWizardClass.getConstructor(

            uiClass, installOpClass, java.util.Collection.class, jobClass)

            .newInstance(ui, null, null, job);



        Class<?> dialogClass = loadBundleClass(BUNDLE_P2_UI,

            "org.eclipse.equinox.internal.p2.ui.dialogs.ProvisioningWizardDialog"); //$NON-NLS-1$

        Class<?> provUIClass = loadBundleClass(BUNDLE_P2_UI,

            "org.eclipse.equinox.internal.p2.ui.ProvUI"); //$NON-NLS-1$

        Shell parent = (Shell) provUIClass.getMethod("getDefaultParentShell").invoke(null); //$NON-NLS-1$

        Object dialog = newProvisioningWizardDialog(dialogClass, parent, wizard);



        dialogClass.getMethod("create").invoke(dialog); //$NON-NLS-1$

        ComfortDebug.log("install", "ProvisioningWizardDialog.create() OK"); //$NON-NLS-1$ //$NON-NLS-2$

        selectInstallSiteInDialog(wizard, siteUri);

        dialogClass.getMethod("open").invoke(dialog); //$NON-NLS-1$

    }



    private static Object newProvisioningWizardDialog(
            Class<?> dialogClass, Shell parent, Object wizard) throws Exception
    {
        Class<?> opWizardClass = loadBundleClass(BUNDLE_P2_UI,
            "org.eclipse.equinox.internal.p2.ui.dialogs.ProvisioningOperationWizard"); //$NON-NLS-1$
        try
        {
            return dialogClass.getConstructor(Shell.class, opWizardClass)
                .newInstance(parent, wizard);
        }
        catch (NoSuchMethodException e)
        {
            Class<?> iWizardClass = Class.forName("org.eclipse.jface.wizard.IWizard"); //$NON-NLS-1$
            return dialogClass.getConstructor(Shell.class, iWizardClass)
                .newInstance(parent, wizard);
        }
    }

    private static void registerUpdateSite(Object ui, Class<?> uiClass, URI siteUri)

            throws Exception

    {

        IProgressMonitor monitor = new NullProgressMonitor();

        cancelRepositoryLoadJobs();

        forceRemoveRepository(ui, uiClass, siteUri);

        forceRemoveRepository(ui, uiClass, URI.create(ComfortUpdateChecker.UPDATE_SITE_URL));

        uiClass.getMethod("loadMetadataRepository", //$NON-NLS-1$

            URI.class, boolean.class, IProgressMonitor.class)

            .invoke(ui, siteUri, Boolean.TRUE, monitor);

        uiClass.getMethod("loadArtifactRepository", //$NON-NLS-1$

            URI.class, boolean.class, IProgressMonitor.class)

            .invoke(ui, siteUri, Boolean.TRUE, monitor);

        setRepositoryNickname(ui, uiClass, siteUri);

        ComfortDebug.log("install", "registerUpdateSite OK uri=" + siteUri); //$NON-NLS-1$ //$NON-NLS-2$

    }



    private static void cancelRepositoryLoadJobs() throws Exception

    {

        Class<?> jobClass = Class.forName("org.eclipse.core.runtime.jobs.Job"); //$NON-NLS-1$

        Class<?> loadJobClass = loadBundleClass(BUNDLE_P2_UI,

            "org.eclipse.equinox.p2.ui.LoadMetadataRepositoryJob"); //$NON-NLS-1$

        Object loadFamily = loadJobClass.getField("LOAD_FAMILY").get(null); //$NON-NLS-1$

        Object jobManager = jobClass.getMethod("getJobManager").invoke(null); //$NON-NLS-1$

        jobManager.getClass().getMethod("cancel", Object.class).invoke(jobManager, loadFamily); //$NON-NLS-1$

    }



    private static void forceRemoveRepository(Object ui, Class<?> uiClass, URI siteUri)

    {

        if (siteUri == null)

            return;

        try

        {

            Object session = uiClass.getMethod("getSession").invoke(ui); //$NON-NLS-1$

            Class<?> provUIClass = loadBundleClass(BUNDLE_P2_UI,

                "org.eclipse.equinox.internal.p2.ui.ProvUI"); //$NON-NLS-1$

            for (String managerMethod : new String[] {

                "getMetadataRepositoryManager", //$NON-NLS-1$

                "getArtifactRepositoryManager" //$NON-NLS-1$

            })

            {

                Object manager = provUIClass.getMethod(managerMethod, session.getClass())

                    .invoke(null, session);

                manager.getClass().getMethod("removeRepository", URI.class) //$NON-NLS-1$

                    .invoke(manager, siteUri);

            }

            ComfortDebug.log("install", "removeRepository " + siteUri); //$NON-NLS-1$ //$NON-NLS-2$

        }

        catch (Exception e)

        {

            ComfortDebug.logError("install", "forceRemoveRepository " + siteUri, e); //$NON-NLS-1$ //$NON-NLS-2$

        }

    }



    private static void setRepositoryNickname(Object ui, Class<?> uiClass, URI siteUri)

    {

        try

        {

            Object session = uiClass.getMethod("getSession").invoke(ui); //$NON-NLS-1$

            Class<?> provUIClass = loadBundleClass(BUNDLE_P2_UI,

                "org.eclipse.equinox.internal.p2.ui.ProvUI"); //$NON-NLS-1$

            Object metaManager = provUIClass.getMethod( //$NON-NLS-1$

                "getMetadataRepositoryManager", session.getClass()) //$NON-NLS-1$

                .invoke(null, session);

            metaManager.getClass().getMethod( //$NON-NLS-1$

                "setRepositoryProperty", URI.class, String.class, String.class) //$NON-NLS-1$

                .invoke(metaManager, siteUri, REPO_NICKNAME_PROPERTY, NICKNAME_EDT_COMFORT);

        }

        catch (Exception e)

        {

            ComfortDebug.logError("install", "setRepositoryNickname", e); //$NON-NLS-1$ //$NON-NLS-2$

        }

    }



    /** {@code AvailableIUGroup.AVAILABLE_SPECIFIED} — выбор одного update site. */
    private static final int AVAILABLE_SPECIFIED_SCOPE = 4;



    private static int resolveAvailableSpecifiedScope()

    {

        try

        {

            Class<?> availableIUGroupClass = loadBundleClass(BUNDLE_P2_UI,

                "org.eclipse.equinox.internal.p2.ui.dialogs.AvailableIUGroup"); //$NON-NLS-1$

            return availableIUGroupClass.getField("AVAILABLE_SPECIFIED").getInt(null); //$NON-NLS-1$

        }

        catch (Exception e)

        {

            ComfortDebug.log("install", //$NON-NLS-1$

                "AVAILABLE_SPECIFIED fallback=" + AVAILABLE_SPECIFIED_SCOPE); //$NON-NLS-1$

            return AVAILABLE_SPECIFIED_SCOPE;

        }

    }



    private static void selectInstallSiteInDialog(Object wizard, URI siteUri)

    {

        try

        {

            Object[] pages = (Object[]) wizard.getClass().getMethod("getPages").invoke(wizard); //$NON-NLS-1$

            if (pages == null)

            {

                ComfortDebug.log("install", "selectSite: wizard.getPages()=null"); //$NON-NLS-1$ //$NON-NLS-2$

                return;

            }

            ComfortDebug.log("install", "selectSite: pages=" + pages.length); //$NON-NLS-1$ //$NON-NLS-2$



            Class<?> availablePageClass = loadBundleClass(BUNDLE_P2_UI,

                "org.eclipse.equinox.internal.p2.ui.dialogs.AvailableIUsPage"); //$NON-NLS-1$

            int specifiedScope = resolveAvailableSpecifiedScope();



            for (Object page : pages)

            {

                if (!availablePageClass.isInstance(page))

                    continue;

                Field repoSelectorField = availablePageClass.getDeclaredField("repoSelector"); //$NON-NLS-1$

                repoSelectorField.setAccessible(true);

                Object repoSelector = repoSelectorField.get(page);

                if (repoSelector == null)

                {

                    ComfortDebug.log("install", "selectSite: repoSelector=null"); //$NON-NLS-1$ //$NON-NLS-2$

                    return;

                }

                logInstallRepoCombo(repoSelector, "before setRepositorySelection"); //$NON-NLS-1$

                repoSelector.getClass().getMethod( //$NON-NLS-1$

                    "setRepositorySelection", int.class, URI.class) //$NON-NLS-1$

                    .invoke(repoSelector, specifiedScope, siteUri);

                ComfortDebug.log("install", //$NON-NLS-1$

                    "setRepositorySelection(scope=AVAILABLE_SPECIFIED, uri=" + siteUri + ")"); //$NON-NLS-1$ //$NON-NLS-2$

                logInstallRepoCombo(repoSelector, "after setRepositorySelection"); //$NON-NLS-1$

                return;

            }

            ComfortDebug.log("install", "selectSite: AvailableIUsPage not found"); //$NON-NLS-1$ //$NON-NLS-2$

        }

        catch (Exception e)

        {

            ComfortDebug.logError("install", "selectInstallSiteInDialog", e); //$NON-NLS-1$ //$NON-NLS-2$

        }

    }



    private static void runLoadJobModal(Object job, Class<?> jobClass) throws Exception

    {

        Display display = Display.getCurrent();

        Runnable load = () -> {

            try

            {

                jobClass.getMethod("runModal", IProgressMonitor.class) //$NON-NLS-1$

                    .invoke(job, new NullProgressMonitor());

            }

            catch (InvocationTargetException e)

            {

                Throwable cause = e.getCause() != null ? e.getCause() : e;

                throw new RuntimeException(cause);

            }

            catch (Exception e)

            {

                throw new RuntimeException(e);

            }

        };

        if (display != null)

            BusyIndicator.showWhile(display, load);

        else

            load.run();

    }



    private static void configureLoadJob(Object job, Class<?> jobClass) throws Exception

    {

        Class<?> jobBaseClass = Class.forName("org.eclipse.core.runtime.jobs.Job"); //$NON-NLS-1$

        Method setProperty = jobBaseClass.getMethod(

            "setProperty", Class.forName("org.eclipse.core.runtime.QualifiedName"), Object.class); //$NON-NLS-1$ //$NON-NLS-2$



        setJobProperty(setProperty, job, jobClass, "ACCUMULATE_LOAD_ERRORS", Boolean.TRUE); //$NON-NLS-1$

        setJobProperty(setProperty, job, jobClass, "WIZARD_CLIENT_SHOULD_SCHEDULE", Boolean.FALSE); //$NON-NLS-1$

    }



    private static void setJobProperty(

            Method setProperty, Object job, Class<?> jobClass, String fieldName, Boolean value)

            throws Exception

    {

        Object key = jobClass.getField(fieldName).get(null);

        setProperty.invoke(job, key, value.toString());

    }



    private static boolean ensureP2ProfileAvailable(Object ui, Class<?> uiClass)

    {

        try

        {

            String profileId = (String) uiClass.getMethod("getProfileId").invoke(ui); //$NON-NLS-1$

            Object session = uiClass.getMethod("getSession").invoke(ui); //$NON-NLS-1$

            Class<?> sessionClass = loadBundleClass(BUNDLE_P2_OPERATIONS,

                "org.eclipse.equinox.p2.operations.ProvisioningSession"); //$NON-NLS-1$

            Object agent = sessionClass.getMethod("getProvisioningAgent").invoke(session); //$NON-NLS-1$

            if (agent == null || profileId == null || profileId.isBlank())

            {

                ComfortDebug.log("install", "p2 profile missing: profileId=" + profileId //$NON-NLS-1$ //$NON-NLS-2$

                    + " agent=" + (agent != null)); //$NON-NLS-1$

                return false;

            }



            Class<?> agentClass = loadBundleClass("org.eclipse.equinox.p2.core", //$NON-NLS-1$

                "org.eclipse.equinox.p2.core.IProvisioningAgent"); //$NON-NLS-1$

            Class<?> registryClass = loadBundleClass(BUNDLE_P2_ENGINE,

                "org.eclipse.equinox.p2.engine.IProfileRegistry"); //$NON-NLS-1$

            Object registry = agentClass.getMethod("getService", Class.class) //$NON-NLS-1$

                .invoke(agent, registryClass);

            if (registry == null)

                return false;

            Object profile = registryClass.getMethod("getProfile", String.class) //$NON-NLS-1$

                .invoke(registry, profileId);

            boolean ok = profile != null;

            ComfortDebug.log("install", "p2 profileId=" + profileId + " ok=" + ok); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            return ok;

        }

        catch (Exception e)

        {

            ComfortDebug.logError("install", "ensureP2ProfileAvailable", e); //$NON-NLS-1$ //$NON-NLS-2$

            return false;

        }

    }



    private static boolean isP2SelfUpdateAvailable()

    {

        try

        {

            Class<?> uiClass = loadBundleClass(BUNDLE_P2_UI,

                "org.eclipse.equinox.p2.ui.ProvisioningUI"); //$NON-NLS-1$

            Object ui = uiClass.getMethod("getDefaultUI").invoke(null); //$NON-NLS-1$

            return ensureP2ProfileAvailable(ui, uiClass);

        }

        catch (Exception e)

        {

            return false;

        }

    }



    /**

     * Загрузка класса из другого OSGi-бандла.

     * {@link Class#forName(String)} ищет только в classpath нашего плагина.

     */

    private static Class<?> loadBundleClass(String bundleSymbolicName, String className)

            throws ClassNotFoundException

    {

        Bundle bundle = Platform.getBundle(bundleSymbolicName);

        if (bundle == null)

            throw new ClassNotFoundException(

                className + " (bundle " + bundleSymbolicName + " not installed)"); //$NON-NLS-1$ //$NON-NLS-2$

        if (bundle.getState() != Bundle.ACTIVE)

        {

            try

            {

                bundle.start(Bundle.START_TRANSIENT);

            }

            catch (Exception e)

            {

                Global.log("Старт bundle " + bundleSymbolicName + ": " + formatError(e)); //$NON-NLS-1$ //$NON-NLS-2$

            }

        }

        return bundle.loadClass(className);

    }



    private static String normalizeSiteUrl(String url)

    {

        if (url == null || url.isBlank())

            return ComfortUpdateChecker.UPDATE_SITE_URL;

        return url.endsWith("/") ? url : url + '/'; //$NON-NLS-1$

    }



    private static void logError(String message, Throwable t)

    {

        Throwable root = unwrap(t);

        String detail = root != null ? formatError(root) : ""; //$NON-NLS-1$

        String text = detail.isBlank() ? message : message + ": " + detail; //$NON-NLS-1$

        Global.log(text);

        ComfortDebug.logError("install", message, t); //$NON-NLS-1$

        getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, text, root));

    }



    private static void logKnownRepositories(Object ui, Class<?> uiClass, String stage)

    {

        try

        {

            Object session = uiClass.getMethod("getSession").invoke(ui); //$NON-NLS-1$

            Object tracker = uiClass.getMethod("getRepositoryTracker").invoke(ui); //$NON-NLS-1$

            URI[] repos = (URI[]) tracker.getClass().getMethod( //$NON-NLS-1$

                "getKnownRepositories", session.getClass()) //$NON-NLS-1$

                .invoke(tracker, session);

            int count = repos != null ? repos.length : 0;

            ComfortDebug.log("install", stage + ": knownRepositories=" + count); //$NON-NLS-1$ //$NON-NLS-2$

            if (repos != null)

            {

                for (URI repo : repos)

                    ComfortDebug.log("install", "  repo " + repo); //$NON-NLS-1$ //$NON-NLS-2$

            }

        }

        catch (Exception e)

        {

            ComfortDebug.logError("install", "logKnownRepositories " + stage, e); //$NON-NLS-1$ //$NON-NLS-2$

        }

    }



    private static void logLoadJobStatus(Object job, Class<?> jobClass)

    {

        try

        {

            Class<?> statusClass = Class.forName("org.eclipse.core.runtime.IStatus"); //$NON-NLS-1$

            Class<?> jobBaseClass = Class.forName("org.eclipse.core.runtime.jobs.Job"); //$NON-NLS-1$

            Object status = jobBaseClass.getMethod("getResult").invoke(job); //$NON-NLS-1$

            if (status == null)

            {

                ComfortDebug.log("install", "LoadMetadataRepositoryJob: status=null"); //$NON-NLS-1$ //$NON-NLS-2$

                return;

            }

            boolean ok = (Boolean) statusClass.getMethod("isOK").invoke(status); //$NON-NLS-1$

            int severity = (Integer) statusClass.getMethod("getSeverity").invoke(status); //$NON-NLS-1$

            String msg = (String) statusClass.getMethod("getMessage").invoke(status); //$NON-NLS-1$

            ComfortDebug.log("install", "LoadMetadataRepositoryJob: ok=" + ok //$NON-NLS-1$ //$NON-NLS-2$

                + " severity=" + severity + " msg=" + msg); //$NON-NLS-1$ //$NON-NLS-2$

        }

        catch (Exception e)

        {

            ComfortDebug.logError("install", "logLoadJobStatus", e); //$NON-NLS-1$ //$NON-NLS-2$

        }

    }



    private static void logInstallRepoCombo(Object repoSelector, String stage)

    {

        try

        {

            Field comboField = repoSelector.getClass().getDeclaredField("repoCombo"); //$NON-NLS-1$

            comboField.setAccessible(true);

            Object combo = comboField.get(repoSelector);

            if (combo == null)

            {

                ComfortDebug.log("install", stage + ": repoCombo=null"); //$NON-NLS-1$ //$NON-NLS-2$

                return;

            }

            String text = (String) combo.getClass().getMethod("getText").invoke(combo); //$NON-NLS-1$

            int sel = (Integer) combo.getClass().getMethod("getSelectionIndex").invoke(combo); //$NON-NLS-1$

            ComfortDebug.log("install", stage + ": combo text=\"" + text //$NON-NLS-1$ //$NON-NLS-2$

                + "\" selectionIndex=" + sel); //$NON-NLS-1$

        }

        catch (Exception e)

        {

            ComfortDebug.logError("install", "logInstallRepoCombo " + stage, e); //$NON-NLS-1$ //$NON-NLS-2$

        }

    }



    private static ILog getLog()

    {

        Activator plugin = Activator.getDefault();

        if (plugin != null)

            return plugin.getLog();

        return Platform.getLog(ComfortPreferences.class);

    }



    private static Throwable unwrap(Throwable e)

    {

        if (e instanceof InvocationTargetException ite && ite.getCause() != null)

            return ite.getCause();

        return e;

    }



    private static String formatError(Throwable e)

    {

        if (e == null)

            return ""; //$NON-NLS-1$

        String msg = e.getMessage();

        return e.getClass().getSimpleName()

            + (msg != null && !msg.isBlank() ? ": " + msg : ""); //$NON-NLS-1$ //$NON-NLS-2$

    }



    private static void openExternalUrl(String url)

    {

        Display display = Display.getDefault();

        if (display == null || display.isDisposed())

            return;

        display.asyncExec(() -> {

            try

            {

                IWorkbenchBrowserSupport support =

                    PlatformUI.getWorkbench().getBrowserSupport();

                support.getExternalBrowser().openURL(URI.create(url).toURL());

            }

            catch (Exception e)

            {

                Global.log("Открытие URL: " + formatError(e)); //$NON-NLS-1$

            }

        });

    }



    private static Shell resolveShell()

    {

        IWorkbenchWindow window =

            PlatformUI.getWorkbench().getActiveWorkbenchWindow();

        if (window != null && window.getShell() != null)

            return window.getShell();

        Display display = Display.getDefault();

        if (display != null && !display.isDisposed())

        {

            Shell active = display.getActiveShell();

            if (active != null)

                return active;

        }

        return null;

    }

}


