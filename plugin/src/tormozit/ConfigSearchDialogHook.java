package tormozit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.IStartup;

public class ConfigSearchDialogHook implements IStartup
{
    private static final String SETTINGS_SECTION = "TormozitConfigurationSearchSettings";
    private static final String KEY_WHOLE_WORD = "wholeWord";
    private static final String KEY_SHELL_WIDTH = "shell.width";
    private static final String KEY_SHELL_HEIGHT = "shell.height";
    private static final String KEY_PROJECTS_TABLE_WIDTH = "projectsTable.width";
    private static final String KEY_PROJECTS_TABLE_HEIGHT = "projectsTable.height";
    private static final String KEY_OBJECT_TYPES_TABLE_WIDTH = "objectTypesTable.width";
    private static final String KEY_OBJECT_TYPES_TABLE_HEIGHT = "objectTypesTable.height";
    private static final String SEARCH_DIALOG_CLASS = "org.eclipse.search.internal.ui.SearchDialog";
    private static final String PAGE_CLASS = "com._1c.g5.v8.dt.internal.search.ui.dialog.ConfigurationSearchDialogPage";
    private static final String HOOKED_KEY = "tormozit.configSearchHooked";
    private static final String LISTENER_KEY = "tormozit.configSearchListener";
    private static final String SIZE_MEMORY_KEY = "tormozit.configSearchSizeMemory";
    private static final String OBJECT_TYPES_COUNT_KEY = "tormozit.configSearchObjectTypesCount";
    private static final String OBJECT_TYPES_TITLE_RU = "среди типов объектов";
    private static final String OBJECT_TYPES_TITLE_EN = "within object types";
    private static final String PROJECTS_TITLE_RU = "среди проектов";
    private static final String PROJECTS_TITLE_EN = "within projects";
    private static final Pattern TITLE_COUNT_SUFFIX = Pattern.compile(" \\(\\d+\\)$");

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            Display.getDefault().addFilter(SWT.Show, event ->
            {
                if (!(event.widget instanceof Shell))
                    return;
                Shell shell = (Shell) event.widget;
                Object dialog = findSearchDialog(shell);
                if (dialog == null)
                    return;

                installShellSizeMemory(shell);

                if (shell.getData(LISTENER_KEY) == null)
                {
                    shell.setData(LISTENER_KEY, Boolean.TRUE);
                    addPageChangeListener(dialog, shell);
                }

                schedulePatch(shell, dialog, 0);
            });
        });
    }

    private static Object findSearchDialog(Shell shell)
    {
        Object dialog = shell.getData();
        if (dialog != null && SEARCH_DIALOG_CLASS.equals(dialog.getClass().getName()))
            return dialog;
        dialog = shell.getData("org.eclipse.jface.window.Window");
        if (dialog != null && SEARCH_DIALOG_CLASS.equals(dialog.getClass().getName()))
            return dialog;
        return null;
    }

    private static void addPageChangeListener(Object dialog, Shell shell)
    {
        try
        {
            Class<?> listenerClass = Class.forName(
                "org.eclipse.jface.dialogs.IPageChangedListener");
            Object listener = Proxy.newProxyInstance(
                ConfigSearchDialogHook.class.getClassLoader(),
                new Class[] { listenerClass },
                (proxy, method, args) -> {
                    schedulePatch(shell, dialog, 0);
                    scheduleRestoreShellSize(shell);
                    return null;
                });
            dialog.getClass().getMethod("addPageChangedListener", listenerClass)
                .invoke(dialog, listener);
        }
        catch (Exception e)
        {
            log("addPageChangeListener error: " + e);
        }
    }

    private static void schedulePatch(Shell shell, Object dialog, int attempt)
    {
        if (shell == null || shell.isDisposed())
            return;
        Display.getDefault().timerExec(attempt == 0 ? 0 : 200, () ->
        {
            if (shell.isDisposed())
                return;

            Object page = getSelectedPage(dialog);
            if (page == null)
            {
                if (attempt < 100)
                    schedulePatch(shell, dialog, attempt + 1);
                return;
            }

            if (!PAGE_CLASS.equals(page.getClass().getName()))
            {
                if (attempt < 100)
                    schedulePatch(shell, dialog, attempt + 1);
                return;
            }

            if (Global.getField(page, "searchExecutorProvider") == null)
            {
                if (attempt < 100)
                    schedulePatch(shell, dialog, attempt + 1);
                return;
            }

            patchPage(shell, dialog, page);
        });
    }

    private static Object getSelectedPage(Object dialog)
    {
        try
        {
            return dialog.getClass().getMethod("getSelectedPage").invoke(dialog);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void patchPage(Shell shell, Object dialog, Object page)
    {
        if (shell.getData(HOOKED_KEY) != null)
            return;
        shell.setData(HOOKED_KEY, Boolean.TRUE);

        Button btnCase = findCaseSensitiveButton(shell);
        Composite parent = btnCase != null ? btnCase.getParent()
            : (Composite) Global.invoke(page, "getControl");

        if (parent == null)
        {
            log("cannot determine parent composite, aborting");
            return;
        }

        IDialogSettings settings = getDialogSettings();

        if (btnCase != null)
        {
            Composite vGroup = new Composite(parent, SWT.NONE);
            GridLayout vLayout = new GridLayout(1, false);
            vLayout.marginWidth = 0;
            vLayout.marginHeight = 0;
            vLayout.verticalSpacing = 0;
            vGroup.setLayout(vLayout);

            GridData caseGd = (GridData) btnCase.getLayoutData();
            GridData vGd;
            if (caseGd != null)
            {
                vGd = new GridData(caseGd.horizontalAlignment, caseGd.verticalAlignment,
                    caseGd.grabExcessHorizontalSpace, caseGd.grabExcessVerticalSpace);
                vGd.horizontalIndent = caseGd.horizontalIndent;
                vGd.horizontalSpan = caseGd.horizontalSpan;
                vGd.verticalSpan = caseGd.verticalSpan;
                vGd.widthHint = caseGd.widthHint;
                vGd.heightHint = caseGd.heightHint;
                vGd.exclude = caseGd.exclude;
            }
            else
            {
                vGd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
            }
            vGroup.setLayoutData(vGd);

            vGroup.moveBelow(btnCase);

            btnCase.setParent(vGroup);
            btnCase.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));

            Button cbWholeWord = new Button(vGroup, SWT.CHECK);
            cbWholeWord.setText("Слово целиком");
            cbWholeWord.setToolTipText("Искать только целые слова, а не подстроки"
                + Global.pluginSignForTooltip());
            cbWholeWord.setSelection(settings.getBoolean(KEY_WHOLE_WORD));
            cbWholeWord.addListener(SWT.Selection,
                e -> settings.put(KEY_WHOLE_WORD, cbWholeWord.getSelection()));
            cbWholeWord.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        }
        else
        {
            Button cbWholeWord = new Button(parent, SWT.CHECK);
            cbWholeWord.setText("Слово целиком");
            cbWholeWord.setToolTipText("Искать только целые слова, а не подстроки"
                + Global.pluginSignForTooltip());
            cbWholeWord.setSelection(settings.getBoolean(KEY_WHOLE_WORD));
            cbWholeWord.setLayoutData(
                new GridData(GridData.BEGINNING, GridData.CENTER, false, false));
            cbWholeWord.addListener(SWT.Selection,
                e -> settings.put(KEY_WHOLE_WORD, cbWholeWord.getSelection()));
        }
        parent.layout(true, true);
        restoreScopeTableSizes(page);
        installObjectTypesCountLabel(page);
        scheduleRestoreShellSize(shell);

        patchExecutor(page);
    }

    private static Button findCaseSensitiveButton(Shell shell)
    {
        return Global.findControl(shell, Button.class, btn ->
        {
            if ((btn.getStyle() & SWT.CHECK) == 0)
                return false;
            String text = btn.getText();
            return text != null
                && (text.contains("регистр") || text.toLowerCase().contains("case"));
        });
    }

    /** Запоминание размеров окна «Поиск» между открытиями и после смены вкладки. */
    private static void installShellSizeMemory(Shell shell)
    {
        if (Boolean.TRUE.equals(shell.getData(SIZE_MEMORY_KEY)))
            return;
        shell.setData(SIZE_MEMORY_KEY, Boolean.TRUE);

        Point[] lastSize = { null };
        shell.addListener(SWT.Resize, e ->
        {
            if (shell.getMaximized() || shell.getMinimized())
                return;
            Point size = shell.getSize();
            if (size.x > 0 && size.y > 0)
                lastSize[0] = size;
        });
        shell.addDisposeListener(e -> {
            saveShellSize(lastSize[0]);
            saveScopeTableSizes(shell);
        });

        restoreShellSize(shell);
        scheduleRestoreShellSize(shell);
    }

    private static void scheduleRestoreShellSize(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> restoreShellSize(shell));
        display.timerExec(50, () -> restoreShellSize(shell));
    }

    private static void restoreShellSize(Shell shell)
    {
        if (shell == null || shell.isDisposed() || shell.getMaximized())
            return;
        IDialogSettings settings = getDialogSettings();
        if (settings.get(KEY_SHELL_WIDTH) == null || settings.get(KEY_SHELL_HEIGHT) == null)
            return;

        int width;
        int height;
        try
        {
            width = settings.getInt(KEY_SHELL_WIDTH);
            height = settings.getInt(KEY_SHELL_HEIGHT);
        }
        catch (NumberFormatException e)
        {
            return;
        }
        if (width <= 0 || height <= 0)
            return;

        Point current = shell.getSize();
        if (current.x == width && current.y == height)
            return;
        // Только размер — x/y не трогаем. Пересчёт «центра» сдвигал окно при смене вкладки.
        shell.setSize(width, height);
    }

    private static void saveShellSize(Point size)
    {
        if (size == null || size.x <= 0 || size.y <= 0)
            return;
        IDialogSettings settings = getDialogSettings();
        settings.put(KEY_SHELL_WIDTH, size.x);
        settings.put(KEY_SHELL_HEIGHT, size.y);
    }

    private static void restoreScopeTableSizes(Object page)
    {
        Control pageControl = (Control) Global.invoke(page, "getControl");
        if (!(pageControl instanceof Composite root) || root.isDisposed())
            return;

        applyStoredTableSize(findScopeTable(root, PROJECTS_TITLE_RU, PROJECTS_TITLE_EN),
            KEY_PROJECTS_TABLE_WIDTH, KEY_PROJECTS_TABLE_HEIGHT);
        applyStoredTableSize(findScopeTable(root, OBJECT_TYPES_TITLE_RU, OBJECT_TYPES_TITLE_EN),
            KEY_OBJECT_TYPES_TABLE_WIDTH, KEY_OBJECT_TYPES_TABLE_HEIGHT);
        root.layout(true, true);
    }

    private static void saveScopeTableSizes(Shell shell)
    {
        Object dialog = findSearchDialog(shell);
        if (dialog == null)
            return;
        Object page = getSelectedPage(dialog);
        if (page == null || !PAGE_CLASS.equals(page.getClass().getName()))
            page = findConfigurationSearchPage(shell);
        if (page == null)
            return;

        Control pageControl = (Control) Global.invoke(page, "getControl");
        if (!(pageControl instanceof Composite root))
            return;

        saveTableSize(findScopeTable(root, PROJECTS_TITLE_RU, PROJECTS_TITLE_EN),
            KEY_PROJECTS_TABLE_WIDTH, KEY_PROJECTS_TABLE_HEIGHT);
        saveTableSize(findScopeTable(root, OBJECT_TYPES_TITLE_RU, OBJECT_TYPES_TITLE_EN),
            KEY_OBJECT_TYPES_TABLE_WIDTH, KEY_OBJECT_TYPES_TABLE_HEIGHT);
    }

    private static Object findConfigurationSearchPage(Shell shell)
    {
        Object dialog = findSearchDialog(shell);
        if (dialog == null)
            return null;
        try
        {
            Object descriptors = Global.getField(dialog, "fDescriptors");
            if (!(descriptors instanceof List<?> list))
                return null;
            for (Object descriptor : list)
            {
                Object pg = Global.invoke(descriptor, "getPage");
                if (pg != null && PAGE_CLASS.equals(pg.getClass().getName()))
                    return pg;
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private static Table findScopeTable(Composite root, String ruTitle, String enTitle)
    {
        Label label = Global.findControl(root, Label.class,
            lbl -> isScopeListTitle(lbl.getText(), ruTitle, enTitle));
        if (label == null || label.isDisposed())
            return null;
        Composite parent = label.getParent();
        if (parent == null || parent.isDisposed())
            return null;
        for (Control child : parent.getChildren())
        {
            if (child instanceof Table table && !table.isDisposed()
                    && (table.getStyle() & SWT.CHECK) != 0)
                return table;
        }
        return null;
    }

    private static boolean isScopeListTitle(String text, String ruTitle, String enTitle)
    {
        if (text == null)
            return false;
        String base = stripCountSuffix(text).trim();
        return ruTitle.equalsIgnoreCase(base) || enTitle.equalsIgnoreCase(base);
    }

    private static String stripCountSuffix(String text)
    {
        if (text == null)
            return "";
        Matcher matcher = TITLE_COUNT_SUFFIX.matcher(text);
        return matcher.find() ? text.substring(0, matcher.start()) : text;
    }

    private static void applyStoredTableSize(Table table, String widthKey, String heightKey)
    {
        if (table == null || table.isDisposed())
            return;
        IDialogSettings settings = getDialogSettings();
        if (settings.get(widthKey) == null || settings.get(heightKey) == null)
            return;

        int width;
        int height;
        try
        {
            width = settings.getInt(widthKey);
            height = settings.getInt(heightKey);
        }
        catch (NumberFormatException e)
        {
            return;
        }
        if (width <= 0 || height <= 0)
            return;

        Object layoutData = table.getLayoutData();
        if (!(layoutData instanceof GridData gd))
            return;
        gd.widthHint = width;
        gd.heightHint = height;
    }

    private static void saveTableSize(Table table, String widthKey, String heightKey)
    {
        if (table == null || table.isDisposed())
            return;
        Point size = table.getSize();
        if (size.x <= 0 || size.y <= 0)
            return;
        IDialogSettings settings = getDialogSettings();
        settings.put(widthKey, size.x);
        settings.put(heightKey, size.y);
    }

    /** Счётчик помеченных типов объектов в заголовке списка «среди типов объектов». */
    private static void installObjectTypesCountLabel(Object page)
    {
        Control pageControl = (Control) Global.invoke(page, "getControl");
        if (!(pageControl instanceof Composite root) || root.isDisposed())
            return;
        if (Boolean.TRUE.equals(root.getData(OBJECT_TYPES_COUNT_KEY)))
            return;

        Label titleLabel = Global.findControl(root, Label.class,
            lbl -> isScopeListTitle(lbl.getText(), OBJECT_TYPES_TITLE_RU, OBJECT_TYPES_TITLE_EN));
        Table typesTable = findScopeTable(root, OBJECT_TYPES_TITLE_RU, OBJECT_TYPES_TITLE_EN);
        if (titleLabel == null || typesTable == null)
            return;

        String strippedTitle = stripCountSuffix(titleLabel.getText()).trim();
        final String baseTitle = strippedTitle.isEmpty() ? OBJECT_TYPES_TITLE_RU : strippedTitle;

        Runnable refresh = () -> {
            if (titleLabel.isDisposed() || typesTable.isDisposed())
                return;
            int checked = typesTable.getItemCount() > 0 ? countCheckedItems(typesTable) : 0;
            titleLabel.setText(baseTitle + " (" + checked + ")");
        };

        typesTable.addListener(SWT.Selection, e -> refresh.run());
        Composite section = titleLabel.getParent();
        if (section != null && !section.isDisposed())
        {
            for (Control child : section.getChildren())
            {
                if (child instanceof org.eclipse.swt.widgets.ToolBar toolbar)
                {
                    for (org.eclipse.swt.widgets.ToolItem item : toolbar.getItems())
                        item.addListener(SWT.Selection, e -> section.getDisplay().asyncExec(refresh));
                }
            }
        }
        addSearchTypeScopeListener(page, typesTable, refresh);
        root.setData(OBJECT_TYPES_COUNT_KEY, Boolean.TRUE);
        refresh.run();
        root.getDisplay().timerExec(100, refresh);
    }

    private static void addSearchTypeScopeListener(Object page, Table typesTable, Runnable refresh)
    {
        Object searchData = Global.getField(page, "searchData");
        if (searchData == null)
            return;
        Object typeScope = Global.invoke(searchData, "getSearchTypeScope");
        if (typeScope == null)
            return;
        try
        {
            Class<?> listenerClass = Class.forName(
                "org.eclipse.core.databinding.observable.set.ISetChangeListener");
            Object listener = Proxy.newProxyInstance(
                ConfigSearchDialogHook.class.getClassLoader(),
                new Class[] { listenerClass },
                (proxy, method, args) -> {
                    if ("handleSetChange".equals(method.getName()) && typesTable != null
                            && !typesTable.isDisposed())
                        typesTable.getDisplay().asyncExec(refresh);
                    return null;
                });
            typeScope.getClass().getMethod("addSetChangeListener", listenerClass)
                .invoke(typeScope, listener);
        }
        catch (Exception e)
        {
            log("addSearchTypeScopeListener error: " + e);
        }
    }

    private static int countCheckedItems(Table table)
    {
        int count = 0;
        for (int i = 0; i < table.getItemCount(); i++)
            if (table.getItem(i).getChecked())
                count++;
        return count;
    }

    private static void patchExecutor(Object page)
    {
        Object origExecProvider = Global.getField(page, "searchExecutorProvider");
        if (origExecProvider == null)
        {
            log("searchExecutorProvider is null");
            return;
        }
        try
        {
            Object proxyProvider = createExecutorProviderProxy(origExecProvider);
            Global.setFieldForce(page, "searchExecutorProvider", proxyProvider);
            log("executor provider patched");
        }
        catch (Exception e)
        {
            log("patchExecutor error: " + e);
        }
    }

    private static Object createExecutorProviderProxy(Object origExecProvider) throws Exception
    {
        ClassLoader cl = ConfigSearchDialogHook.class.getClassLoader();

        Class<?> providerInterface = null;
        for (String cn : new String[] {
            "com.google.inject.Provider",
            "javax.inject.Provider",
            "jakarta.inject.Provider"
        }) {
            try { providerInterface = Class.forName(cn); break; } catch (Exception ignored) {}
        }
        if (providerInterface == null)
        {
            log("cannot find Provider interface");
            return origExecProvider;
        }

        return Proxy.newProxyInstance(cl, new Class[] { providerInterface },
            (proxy, method, args) ->
            {
                if ("get".equals(method.getName()))
                {
                    Object executor = method.invoke(origExecProvider, args);
                    if (executor == null)
                        return null;
                    return wrapExecutor(executor);
                }
                return method.invoke(origExecProvider, args);
            });
    }

    private static Object wrapExecutor(Object executor) throws Exception
    {
        ClassLoader cl = executor.getClass().getClassLoader();
        final ClassLoader executorCL = cl != null ? cl
            : ConfigSearchDialogHook.class.getClassLoader();
        Class<?>[] interfaces = executor.getClass().getInterfaces();
        if (interfaces == null || interfaces.length == 0)
            return executor;
        return Proxy.newProxyInstance(executorCL, interfaces,
            (proxy, method, args) ->
            {
                if ("run".equals(method.getName()) && args != null && args.length == 3
                    && getDialogSettings().getBoolean(KEY_WHOLE_WORD))
                {
                    Object input = args[0];
                    String sq = (String) Global.invoke(input, "getSearchString");
                    if (sq != null && !sq.contains("?") && !sq.contains("*"))
                    {
                        boolean caseSensitive = (Boolean) Global.invoke(input, "isCaseSensitive");
                        Object filteredCollector = createFilteredCollector(
                            args[1], sq, caseSensitive, executorCL);
                        args[1] = filteredCollector;
                    }
                }
                return method.invoke(executor, args);
            });
    }

    private static Object createFilteredCollector(Object origCollector, String searchString,
        boolean caseSensitive, ClassLoader cl) throws Exception
    {
        Class<?> iface = Class.forName(
            "com._1c.g5.v8.dt.search.core.ISearchResultCollector");
        ClassLoader ifaceCL = iface.getClassLoader();
        if (ifaceCL == null)
            ifaceCL = cl;
        return Proxy.newProxyInstance(ifaceCL, new Class[] { iface },
            (proxy, method, args) ->
            {
                if ("addMatch".equals(method.getName()) && args != null && args.length == 1)
                {
                    if (isWholeWordMatch(args[0], searchString, caseSensitive))
                        return method.invoke(origCollector, args);
                    return null;
                }
                if ("addMatches".equals(method.getName()) && args != null && args.length == 1)
                {
                    Collection<?> matches = (Collection<?>) args[0];
                    List<Object> filtered = new ArrayList<>();
                    for (Object m : matches)
                        if (isWholeWordMatch(m, searchString, caseSensitive))
                            filtered.add(m);
                    if (filtered.size() == matches.size())
                        return method.invoke(origCollector, args);
                    return method.invoke(origCollector, new Object[] { filtered });
                }
                return method.invoke(origCollector, args);
            });
    }

    private static boolean isWholeWordMatch(Object match, String searchWord,
        boolean caseSensitive) throws Exception
    {
        if (match == null || searchWord == null)
            return true;
        String cn = match.getClass().getName();
        if (!cn.startsWith("com._1c.g5.v8.dt.search.core.text.TextSearch"))
            return true;

        String fullText = (String) Global.invoke(match, "getText");
        if (fullText == null)
            return true;

        int offset = (Integer) Global.invoke(match, "getTextOffset");
        int length = (Integer) Global.invoke(match, "getTextLength");

        if (offset < 0 || length <= 0 || offset + length > fullText.length())
            return true;

        String matched = fullText.substring(offset, offset + length);
        if (!caseSensitive)
        {
            matched = matched.toLowerCase(Locale.ROOT);
            searchWord = searchWord.toLowerCase(Locale.ROOT);
        }
        if (!matched.equals(searchWord))
            return false;

        if (offset > 0 && isWordChar(fullText.charAt(offset - 1)))
            return false;
        int end = offset + length;
        if (end < fullText.length() && isWordChar(fullText.charAt(end)))
            return false;
        return true;
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static IDialogSettings getDialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log("ConfigSearchHook", msg);
        Activator.getDefault().getLog().log(
            new Status(Status.INFO, "tormozit.comfort", "ConfigurationSearchHook: " + msg));
    }
}
