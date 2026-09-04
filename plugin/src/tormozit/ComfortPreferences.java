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

    private static final String NICKNAME_ECLIPSE_RELEASES = "Eclipse Releases"; //$NON-NLS-1$
    /** Минимальный IU для орфографии Comfort (не вся feature JDT). */
    private static final String JDT_UI_IU_ID = "org.eclipse.jdt.ui"; //$NON-NLS-1$
    /** Fallback IU, если в metadata нет отдельного плагина как корня. */
    private static final String JDT_FEATURE_IU_ID = "org.eclipse.jdt.feature.group"; //$NON-NLS-1$
    private static final String ECLIPSE_RELEASES_LATEST =
        "https://download.eclipse.org/releases/latest/"; //$NON-NLS-1$
    private static final String JDT_INSTALL_TOAST_OK =
        "Если модуль org.eclipse.jdt.ui установлен — перезапустите EDT, чтобы включить орфографию."; //$NON-NLS-1$
    private static final String JDT_INSTALL_TOAST_FEATURE =
        "Выбран пакет Eclipse Java Development Tools (включает org.eclipse.jdt.ui)."; //$NON-NLS-1$
    private static final String JDT_INSTALL_TOAST_BROWSE =
        "В фильтре введите org.eclipse.jdt.ui и отметьте «Java Development Tools UI»."; //$NON-NLS-1$
    private static final String JDT_BROWSE_FILTER = "jdt.ui"; //$NON-NLS-1$

    /**
     * Открывает «Справка → Установить новое ПО…» с сайтом
     * {@link ComfortUpdateChecker#UPDATE_SITE_URL}.
     */
    public static void openInstallNewSoftware()
    {

        openInstallNewSoftware(ComfortUpdateChecker.UPDATE_SITE_URL);
    }

    /**
     * Мастер установки {@code org.eclipse.jdt.ui} (модуль орфографии Comfort)
     * с сайта Eclipse releases. Ставится минимум, не вся feature JDT.
     * После установки нужен перезапуск.
     */
    public static void openInstallJdtForSpelling()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable open = () ->
        {
            if (ComfortJdtAvailability.isJdtUiAvailable())
            {
                ToastNotification.show("Орфография", //$NON-NLS-1$
                    "Модуль JDT UI уже установлен. Перезапустите EDT, если орфография не активна.", //$NON-NLS-1$
                    8_000);
                return;
            }
            String siteUrl = resolveEclipseReleasesSiteUrl();
            try
            {
                boolean preselected = openInstallWizardForJdt(siteUrl);
                if (preselected)
                {
                    ToastNotification.show("Орфография", //$NON-NLS-1$
                        JDT_INSTALL_TOAST_OK, 12_000);
                }
                // browse: тост уже показан до открытия мастера
            }
            catch (Exception e)
            {
                logError("Установка JDT (орфография)", e); //$NON-NLS-1$
                String message = isP2SelfUpdateAvailable()
                    ? "Не удалось открыть установку JDT UI. Попробуйте Справка → Установить новое ПО…" //$NON-NLS-1$
                    : "Установщик недоступен в этой среде (нет p2-профиля)."; //$NON-NLS-1$
                ToastNotification.show("Орфография", message, 8_000); //$NON-NLS-1$
            }
        };
        if (display.getThread() == Thread.currentThread())
            open.run();
        else
            display.asyncExec(open);
    }

    private static void openInstallNewSoftware(String siteUrl)
    {

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable open = () -> {
            if (tryOpenInstallWizardForSite(siteUrl))
                return;

            String message = isP2SelfUpdateAvailable()
                ? "Не удалось открыть окно «Установить новое ПО»." //$NON-NLS-1$
                : "Установщик недоступен в этой среде (нет p2-профиля). Проверьте в установленном EDT."; //$NON-NLS-1$
            ToastNotification.show("EDT Comfort", message, 8_000); //$NON-NLS-1$
        };
        if (display.getThread() == Thread.currentThread())
            open.run();
        else
            display.asyncExec(open);
    }

    /**
     * URL simrel releases по версии {@code org.eclipse.platform} / {@code org.eclipse.ui}.
     * Eclipse 4.30 ≈ 2023-12, далее +3 месяца на каждый minor.
     */
    static String resolveEclipseReleasesSiteUrl()
    {
        try
        {
            Bundle platform = Platform.getBundle("org.eclipse.platform"); //$NON-NLS-1$
            if (platform == null)
                platform = Platform.getBundle("org.eclipse.ui"); //$NON-NLS-1$
            if (platform == null)
                return ECLIPSE_RELEASES_LATEST;
            org.osgi.framework.Version v = platform.getVersion();
            if (v.getMajor() != 4 || v.getMinor() < 20)
                return ECLIPSE_RELEASES_LATEST;
            int delta = v.getMinor() - 30;
            int totalMonths = 2023 * 12 + 11 + delta * 3; // 2023-12 = month index 11
            int year = totalMonths / 12;
            int month = totalMonths % 12 + 1;
            if (year < 2020 || year > 2030)
                return ECLIPSE_RELEASES_LATEST;
            return String.format(
                "https://download.eclipse.org/releases/%04d-%02d/", //$NON-NLS-1$
                Integer.valueOf(year), Integer.valueOf(month));
        }
        catch (Exception e)
        {
            Global.logError("install", "resolveEclipseReleasesSiteUrl", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ECLIPSE_RELEASES_LATEST;
        }
    }

    /**
     * @return {@code true}, если открыт Preselected-мастер с уже выбранным IU
     */
    private static boolean openInstallWizardForJdt(String siteUrl) throws Exception
    {
        URI siteUri = URI.create(normalizeSiteUrl(siteUrl));
        Class<?> uiClass = loadBundleClass(BUNDLE_P2_UI,
            "org.eclipse.equinox.p2.ui.ProvisioningUI"); //$NON-NLS-1$
        Object ui = uiClass.getMethod("getDefaultUI").invoke(null); //$NON-NLS-1$
        if (!ensureP2ProfileAvailable(ui, uiClass))
            throw new IllegalStateException("no p2 profile"); //$NON-NLS-1$
        registerEclipseReleasesSite(ui, uiClass, siteUri);
        Class<?> jobClass = loadBundleClass(BUNDLE_P2_UI,
            "org.eclipse.equinox.p2.ui.LoadMetadataRepositoryJob"); //$NON-NLS-1$
        Object job = jobClass.getConstructor(uiClass).newInstance(ui);
        configureLoadJob(job, jobClass);
        runLoadJobModal(job, jobClass);

        java.util.List<Object> ius = queryInstallableUnits(ui, uiClass, siteUri, JDT_UI_IU_ID);
        boolean featureFallback = false;
        if (ius == null || ius.isEmpty())
        {
            ius = queryInstallableUnits(ui, uiClass, siteUri, JDT_FEATURE_IU_ID);
            featureFallback = ius != null && !ius.isEmpty();
        }
        if (ius == null || ius.isEmpty())
        {
            openBrowseInstallWizardWithJdtFilter(ui, uiClass, siteUri, job, jobClass);
            return false;
        }

        Object latest = pickLatestInstallableUnit(ius);
        java.util.Collection<Object> selected = java.util.Collections.singletonList(latest);

        Class<?> installOpClass = loadBundleClass(BUNDLE_P2_OPERATIONS,
            "org.eclipse.equinox.p2.operations.InstallOperation"); //$NON-NLS-1$
        Object installOp = uiClass.getMethod(
            "getInstallOperation", java.util.Collection.class, URI[].class) //$NON-NLS-1$
            .invoke(ui, selected, new URI[] { siteUri });
        installOpClass.getMethod("resolveModal", IProgressMonitor.class) //$NON-NLS-1$
            .invoke(installOp, new NullProgressMonitor());

        uiClass.getMethod("openInstallWizard", //$NON-NLS-1$
            java.util.Collection.class, installOpClass, jobClass)
            .invoke(ui, selected, installOp, job);
        if (featureFallback)
            ToastNotification.show("Орфография", JDT_INSTALL_TOAST_FEATURE, 10_000); //$NON-NLS-1$
        return true;
    }

    /**
     * Fallback: browse-мастер. Фильтр только после выбора сайта и с задержкой —
     * иначе список остаётся пустым (каталог ещё не подгружен).
     */
    private static void openBrowseInstallWizardWithJdtFilter(
            Object ui, Class<?> uiClass, URI siteUri, Object job, Class<?> jobClass)
            throws Exception
    {
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
        selectInstallSiteInDialog(wizard, siteUri);
        ToastNotification.show("Орфография", JDT_INSTALL_TOAST_BROWSE, 12_000); //$NON-NLS-1$
        // Каталог сайта подгружается асинхронно — повторять фильтр до 10 с, стоп при успехе
        java.util.concurrent.atomic.AtomicBoolean browseReady =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        for (int sec = 1; sec <= 10; sec++)
            scheduleBrowseFilterAndCheck(wizard, ui, uiClass, siteUri, sec * 1000, browseReady);
        dialogClass.getMethod("open").invoke(dialog); //$NON-NLS-1$
    }

    private static void scheduleBrowseFilterAndCheck(
            Object wizard, Object ui, Class<?> uiClass, URI siteUri, int delayMs,
            java.util.concurrent.atomic.AtomicBoolean browseReady)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(delayMs, () ->
        {
            if (display.isDisposed() || browseReady.get())
                return;
            setAvailableSoftwareFilter(wizard, JDT_BROWSE_FILTER);
            if (tryCheckVisibleJdtUi(wizard, ui, uiClass, siteUri))
                browseReady.set(true);
        });
    }

    /**
     * Пометить видимый IU орфографии на AvailableIUsPage.
     * @return {@code true}, если удалось отметить элемент
     */
    private static boolean tryCheckVisibleJdtUi(
            Object wizard, Object ui, Class<?> uiClass, URI siteUri)
    {
        try
        {
            java.util.List<Object> ius = queryInstallableUnits(ui, uiClass, siteUri, JDT_UI_IU_ID);
            if (ius == null || ius.isEmpty())
                ius = queryInstallableUnits(ui, uiClass, siteUri, JDT_FEATURE_IU_ID);
            if (ius == null || ius.isEmpty())
                return false;
            Object latest = pickLatestInstallableUnit(ius);
            Object[] pages = (Object[]) wizard.getClass().getMethod("getPages").invoke(wizard); //$NON-NLS-1$
            if (pages == null)
                return false;
            Class<?> availablePageClass = loadBundleClass(BUNDLE_P2_UI,
                "org.eclipse.equinox.internal.p2.ui.dialogs.AvailableIUsPage"); //$NON-NLS-1$
            for (Object page : pages)
            {
                if (!availablePageClass.isInstance(page))
                    continue;
                availablePageClass.getMethod("setCheckedElements", Object[].class) //$NON-NLS-1$
                    .invoke(page, (Object) new Object[] { latest });
                return true;
            }
            return false;
        }
        catch (Exception e)
        {
            Global.logError("install", "tryCheckVisibleJdtUi", e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    private static void setAvailableSoftwareFilter(Object wizard, String filterText)
    {
        try
        {
            Object[] pages = (Object[]) wizard.getClass().getMethod("getPages").invoke(wizard); //$NON-NLS-1$
            if (pages == null)
                return;
            Class<?> availablePageClass = loadBundleClass(BUNDLE_P2_UI,
                "org.eclipse.equinox.internal.p2.ui.dialogs.AvailableIUsPage"); //$NON-NLS-1$
            for (Object page : pages)
            {
                if (!availablePageClass.isInstance(page))
                    continue;
                Field groupField = availablePageClass.getDeclaredField("availableIUGroup"); //$NON-NLS-1$
                groupField.setAccessible(true);
                Object group = groupField.get(page);
                if (group == null)
                    return;
                // Видимое поле фильтра AvailableIUGroup / DelayedFilterCheckboxTree
                if (setFilterTextOnControlTree(group, filterText)
                    || applyPatternToAvailableGroup(group, filterText))
                    return;
                Field treeField = findField(group.getClass(), "tree", "filteredTree", //$NON-NLS-1$ //$NON-NLS-2$
                    "availableIUTree"); //$NON-NLS-1$
                if (treeField != null)
                {
                    treeField.setAccessible(true);
                    Object tree = treeField.get(group);
                    if (tree != null
                        && (setFilterTextOnControlTree(tree, filterText)
                            || applyPatternToAvailableGroup(tree, filterText)))
                        return;
                }
                return;
            }
        }
        catch (Exception e)
        {
            Global.logError("install", "setAvailableSoftwareFilter", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** Пишет текст в SWT Text фильтра (то, что видит пользователь). */
    private static boolean setFilterTextOnControlTree(Object root, String filterText)
    {
        try
        {
            Method getFilterControl = findMethod(root.getClass(), "getFilterControl"); //$NON-NLS-1$
            if (getFilterControl != null)
            {
                Object control = getFilterControl.invoke(root);
                if (control instanceof org.eclipse.swt.widgets.Text text && !text.isDisposed())
                {
                    text.setText(filterText);
                    return true;
                }
            }
            Field filterTextField = findField(root.getClass(), "filterText", "filterControl"); //$NON-NLS-1$ //$NON-NLS-2$
            if (filterTextField != null)
            {
                filterTextField.setAccessible(true);
                Object control = filterTextField.get(root);
                if (control instanceof org.eclipse.swt.widgets.Text text && !text.isDisposed())
                {
                    text.setText(filterText);
                    return true;
                }
            }
        }
        catch (Exception e)
        {
            Global.logError("install", "setFilterTextOnControlTree", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return false;
    }

    private static boolean applyPatternToAvailableGroup(Object target, String filterText)
    {
        Method setPattern = findMethod(target.getClass(), "setPattern", String.class); //$NON-NLS-1$
        if (setPattern != null)
        {
            try
            {
                setPattern.setAccessible(true);
                setPattern.invoke(target, filterText);
                return true;
            }
            catch (Exception e)
            {
                Global.logError("install", "applyPattern setPattern", e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        Field patternField = findField(target.getClass(), "patternFilter", "filterText", //$NON-NLS-1$ //$NON-NLS-2$
            "filter", "pattern"); //$NON-NLS-1$ //$NON-NLS-2$
        if (patternField == null)
            return false;
        try
        {
            patternField.setAccessible(true);
            Object filter = patternField.get(target);
            if (filter == null)
                return false;
            if (filter instanceof org.eclipse.swt.widgets.Text text)
            {
                text.setText(filterText);
                return true;
            }
            Method set = findMethod(filter.getClass(), "setPattern", String.class); //$NON-NLS-1$
            if (set == null)
                set = findMethod(filter.getClass(), "setText", String.class); //$NON-NLS-1$
            if (set == null)
                return false;
            set.setAccessible(true);
            set.invoke(filter, filterText);
            return true;
        }
        catch (Exception e)
        {
            Global.logError("install", "applyPattern field", e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    private static Field findField(Class<?> type, String... names)
    {
        for (String name : names)
        {
            Class<?> c = type;
            while (c != null)
            {
                try
                {
                    return c.getDeclaredField(name);
                }
                catch (NoSuchFieldException e)
                {
                    c = c.getSuperclass();
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params)
    {
        Class<?> c = type;
        while (c != null)
        {
            try
            {
                return c.getDeclaredMethod(name, params);
            }
            catch (NoSuchMethodException e)
            {
                try
                {
                    return c.getMethod(name, params);
                }
                catch (NoSuchMethodException e2)
                {
                    c = c.getSuperclass();
                }
            }
        }
        return null;
    }

    private static Object pickLatestInstallableUnit(java.util.List<Object> ius) throws Exception
    {
        Object best = null;
        Comparable<Object> bestVer = null;
        for (Object iu : ius)
        {
            @SuppressWarnings("unchecked")
            Comparable<Object> ver = (Comparable<Object>) iu.getClass()
                .getMethod("getVersion").invoke(iu); //$NON-NLS-1$
            if (best == null || ver.compareTo(bestVer) > 0)
            {
                best = iu;
                bestVer = ver;
            }
        }
        return best;
    }

    private static void registerEclipseReleasesSite(Object ui, Class<?> uiClass, URI siteUri)
            throws Exception
    {
        IProgressMonitor monitor = new NullProgressMonitor();
        cancelRepositoryLoadJobs();
        forceRemoveRepository(ui, uiClass, siteUri);
        uiClass.getMethod("loadMetadataRepository", //$NON-NLS-1$
            URI.class, boolean.class, IProgressMonitor.class)
            .invoke(ui, siteUri, Boolean.TRUE, monitor);
        uiClass.getMethod("loadArtifactRepository", //$NON-NLS-1$
            URI.class, boolean.class, IProgressMonitor.class)
            .invoke(ui, siteUri, Boolean.TRUE, monitor);
        setRepositoryNickname(ui, uiClass, siteUri, NICKNAME_ECLIPSE_RELEASES);
    }

    /**
     * Запрос IU по id: сначала конкретный репозиторий сайта, затем весь metadata manager.
     */
    private static java.util.List<Object> queryInstallableUnits(
            Object ui, Class<?> uiClass, URI siteUri, String iuId)
    {
        try
        {
            Object session = uiClass.getMethod("getSession").invoke(ui); //$NON-NLS-1$
            Class<?> provUIClass = loadBundleClass(BUNDLE_P2_UI,
                "org.eclipse.equinox.internal.p2.ui.ProvUI"); //$NON-NLS-1$
            Object metaManager = provUIClass.getMethod(
                "getMetadataRepositoryManager", session.getClass()) //$NON-NLS-1$
                .invoke(null, session);
            Object repo = metaManager.getClass()
                .getMethod("loadRepository", URI.class, IProgressMonitor.class) //$NON-NLS-1$
                .invoke(metaManager, siteUri, new NullProgressMonitor());
            Class<?> queryUtil = loadQueryUtilClass();
            Object query = queryUtil.getMethod("createIUQuery", String.class) //$NON-NLS-1$
                .invoke(null, iuId);
            java.util.List<Object> fromRepo = queryToList(repo, query);
            if (!fromRepo.isEmpty())
                return fromRepo;
            return queryToList(metaManager, query);
        }
        catch (Exception e)
        {
            Global.logError("install", "queryInstallableUnits " + iuId, e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static java.util.List<Object> queryToList(Object queryable, Object query)
            throws Exception
    {
        if (queryable == null || query == null)
            return java.util.Collections.emptyList();
        Method queryMethod = null;
        for (Method m : queryable.getClass().getMethods())
        {
            if (!"query".equals(m.getName()) || m.getParameterCount() != 2) //$NON-NLS-1$
                continue;
            Class<?>[] pts = m.getParameterTypes();
            if (IProgressMonitor.class.isAssignableFrom(pts[1])
                || pts[1].getName().equals("org.eclipse.core.runtime.IProgressMonitor")) //$NON-NLS-1$
            {
                queryMethod = m;
                break;
            }
        }
        if (queryMethod == null)
            return java.util.Collections.emptyList();
        Object result = queryMethod.invoke(queryable, query, new NullProgressMonitor());
        if (result == null)
            return java.util.Collections.emptyList();
        // IQueryResult extends Iterable — for-each, без reflection по HashIterator (JPMS)
        java.util.List<Object> list = new java.util.ArrayList<>();
        if (result instanceof Iterable<?> iterable)
        {
            for (Object iu : iterable)
                list.add(iu);
            return list;
        }
        if (result instanceof java.util.Collection<?> collection)
        {
            list.addAll(collection);
            return list;
        }
        Method toSet = findMethod(result.getClass(), "toUnmodifiableSet"); //$NON-NLS-1$
        if (toSet != null)
        {
            toSet.setAccessible(true);
            java.util.Collection<?> set = (java.util.Collection<?>) toSet.invoke(result);
            if (set != null)
                list.addAll(set);
            return list;
        }
        for (Method m : result.getClass().getMethods())
        {
            if (!"toArray".equals(m.getName()) || m.getParameterCount() != 1) //$NON-NLS-1$
                continue;
            if (!m.getParameterTypes()[0].isArray())
                continue;
            Object[] array = (Object[]) m.invoke(result, (Object) new Object[0]);
            if (array != null)
            {
                for (Object iu : array)
                    list.add(iu);
            }
            return list;
        }
        return list;
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
        Class<?> uiClass = loadBundleClass(BUNDLE_P2_UI,
            "org.eclipse.equinox.p2.ui.ProvisioningUI"); //$NON-NLS-1$
        Object ui = uiClass.getMethod("getDefaultUI").invoke(null); //$NON-NLS-1$
        if (!ensureP2ProfileAvailable(ui, uiClass))
            throw new IllegalStateException("no p2 profile"); //$NON-NLS-1$
        registerUpdateSite(ui, uiClass, siteUri);
        Class<?> jobClass = loadBundleClass(BUNDLE_P2_UI,
            "org.eclipse.equinox.p2.ui.LoadMetadataRepositoryJob"); //$NON-NLS-1$
        Object job = jobClass.getConstructor(uiClass).newInstance(ui);
        configureLoadJob(job, jobClass);
        runLoadJobModal(job, jobClass);
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
        }

        catch (Exception e)
        {

            Global.logError("install", "forceRemoveRepository " + siteUri, e); //$NON-NLS-1$ //$NON-NLS-2$
        }

    }

    private static void setRepositoryNickname(Object ui, Class<?> uiClass, URI siteUri)
    {
        setRepositoryNickname(ui, uiClass, siteUri, NICKNAME_EDT_COMFORT);
    }

    private static void setRepositoryNickname(
            Object ui, Class<?> uiClass, URI siteUri, String nickname)
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
                .invoke(metaManager, siteUri, REPO_NICKNAME_PROPERTY, nickname);
        }

        catch (Exception e)
        {

            Global.logError("install", "setRepositoryNickname", e); //$NON-NLS-1$ //$NON-NLS-2$
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
            return AVAILABLE_SPECIFIED_SCOPE;
        }

    }

    private static void selectInstallSiteInDialog(Object wizard, URI siteUri)
    {

        try
        {

            Object[] pages = (Object[]) wizard.getClass().getMethod("getPages").invoke(wizard); //$NON-NLS-1$
            if (pages == null)
                return;

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
                    return;

                repoSelector.getClass().getMethod( //$NON-NLS-1$
                    "setRepositorySelection", int.class, URI.class) //$NON-NLS-1$
                    .invoke(repoSelector, specifiedScope, siteUri);
                return;
            }
        }

        catch (Exception e)
        {

            Global.logError("install", "selectInstallSiteInDialog", e); //$NON-NLS-1$ //$NON-NLS-2$
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
                return false;

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
            return profile != null;
        }

        catch (Exception e)
        {

            Global.logError("install", "ensureP2ProfileAvailable", e); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static Class<?> loadQueryUtilClass() throws ClassNotFoundException
    {
        try
        {
            return loadBundleClass("org.eclipse.equinox.p2.metadata", //$NON-NLS-1$
                "org.eclipse.equinox.p2.query.QueryUtil"); //$NON-NLS-1$
        }
        catch (ClassNotFoundException e)
        {
            return loadBundleClass(BUNDLE_P2_ENGINE,
                "org.eclipse.equinox.p2.query.QueryUtil"); //$NON-NLS-1$
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
        Global.logError("install", message, t); //$NON-NLS-1$
        getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, text, root));
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

    /** Тег журнала «Комфорт» для разбора отказов открытия внешних ссылок. */
    private static final String URL_LOG_TAG = "Заявка"; //$NON-NLS-1$

    private static void openExternalUrl(String url)
    {

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {

            Global.log(URL_LOG_TAG, "выход: Display недоступен"); //$NON-NLS-1$
            return;
        }
        display.asyncExec(() -> {

            try
            {

                Global.log(URL_LOG_TAG, "открытие URL, длина " + url.length() + " симв."); //$NON-NLS-1$ //$NON-NLS-2$
                IWorkbenchBrowserSupport support =
                    PlatformUI.getWorkbench().getBrowserSupport();
                support.getExternalBrowser().openURL(URI.create(url).toURL());
                Global.log(URL_LOG_TAG, "внешний браузер вызван без ошибки"); //$NON-NLS-1$
            }

            catch (Exception e)
            {

                Global.logError(URL_LOG_TAG, "открытие URL не удалось", e); //$NON-NLS-1$
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

