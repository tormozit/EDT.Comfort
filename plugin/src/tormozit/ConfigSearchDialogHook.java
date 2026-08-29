package tormozit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
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

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.search.core.Match;
import com._1c.g5.v8.dt.search.core.text.TextSearchFileMatch;

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
        SearchScopeGroup.patch(shell, page);
        hideForeignSearchTabs(shell, dialog);
        scheduleRestoreShellSize(shell);

        patchExecutor(page);
    }

    /**
     * Скрыть вкладки чужих движков поиска («Plugin search», «Java Search») в окне
     * {@code org.eclipse.search.internal.ui.SearchDialog}. Вкладка несёт свой дескриптор в
     * {@code CTabItem.getData("descriptor")}, переключение страниц идёт по нему, а не по индексу —
     * удаление лишних {@code CTabItem} безопасно (issue #419).
     */
    private static void hideForeignSearchTabs(Shell shell, Object dialog)
    {
        CTabFolder folder =
            Global.findControl(shell, CTabFolder.class, f -> true);
        if (folder == null || folder.isDisposed())
            return;
        CTabItem configItem = null;
        List<CTabItem> foreign = new ArrayList<>();
        for (CTabItem item : folder.getItems())
        {
            String id = descriptorId(item);
            boolean isForeign = id != null
                && (id.startsWith("org.eclipse.pde") || id.startsWith("org.eclipse.jdt"));
            if (!isForeign && id == null)
            {
                String label = item.getText() != null ? item.getText().toLowerCase(Locale.ROOT) : "";
                isForeign = label.contains("plugin search") || label.contains("java search");
            }
            if (isForeign)
                foreign.add(item);
            else if (configItem == null)
                configItem = item;
        }
        if (foreign.isEmpty())
            return;
        if (configItem != null && foreign.contains(folder.getSelection()))
            folder.setSelection(configItem);
        for (CTabItem item : foreign)
        {
            Control control = item.getControl();
            item.dispose();
            if (control != null && !control.isDisposed())
                control.dispose();
        }
        // SearchDialog.turnToPage читает fCurrentIndex как индекс в CTabFolder — после удаления
        // вкладок он мог «съехать»; выравниваем на оставшуюся выбранную вкладку.
        if (dialog != null)
            Global.setField(dialog, "fCurrentIndex", folder.getSelectionIndex()); //$NON-NLS-1$
        folder.getParent().layout(true, true);
    }

    private static String descriptorId(CTabItem item)
    {
        Object descriptor = item.getData("descriptor"); //$NON-NLS-1$
        Object id = descriptor != null ? Global.invoke(descriptor, "getId") : null;
        return id instanceof String ? (String) id : null;
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

    /**
     * Доработка группы «Область поиска» страницы поиска по конфигурации (issue #420):
     * <ul>
     *   <li>«Рабочая область» → «Вся рабочая область»;</li>
     *   <li>«Пользовательская область» → «По настроенному отбору»;</li>
     *   <li>добавлены варианты «Отобранное в навигаторе» (объекты, видимые в навигаторе с учётом
     *       активного отбора — фильтр по подсистемам / по набору) и «Активные наборы» (объекты
     *       активных наборов всех проектов). Они не привязаны к штатному {@code UiSearchScope}
     *       (поиск идёт по всей рабочей области), результаты ограничиваются владеющими объектами
     *       отбора на уровне сборщика (см. {@link ConfigSearchDialogHook#wrapExecutor}) —
     *       отсекаются вхождения и в дереве, и в таблице панели.</li>
     * </ul>
     */
    private static final class SearchScopeGroup
    {
        private static final String PATCHED_KEY = "tormozit.searchScopeGroupPatched"; //$NON-NLS-1$
        private static final String SCOPE_MODE_KEY = "comfortScopeMode"; //$NON-NLS-1$

        enum ScopeMode
        {
            NONE, NAVIGATOR, ACTIVE_SETS
        }

        /** Режим отбора Комфорт для следующего поиска (окно модальное — состояние стабильно). */
        private static volatile ScopeMode currentMode = ScopeMode.NONE;
        /**
         * Снимок владеющих ссылок по проектам (ключ {@code ""} — объединение). {@code null} —
         * ограничения нет (режим NONE или «Отобранное в навигаторе» без активного отбора).
         */
        private static volatile Map<String, List<String>> currentRefs;

        private SearchScopeGroup()
        {
        }

        static ScopeMode mode()
        {
            return currentMode;
        }

        static Map<String, List<String>> refs()
        {
            return currentRefs;
        }

        static void patch(Shell shell, Object page)
        {
            Object control = Global.invoke(page, "getControl"); //$NON-NLS-1$
            if (!(control instanceof Composite pageControl) || pageControl.isDisposed())
                return;
            Button workspace = findScopeRadio(pageControl, "Рабочая область", "Workspace");
            Button custom = findScopeRadio(pageControl, "Пользовательская область", "Custom scope");
            if (workspace == null || custom == null)
                return;
            Composite radioRow = workspace.getParent();
            if (radioRow == null || radioRow.isDisposed()
                || Boolean.TRUE.equals(radioRow.getData(PATCHED_KEY)))
                return;
            radioRow.setData(PATCHED_KEY, Boolean.TRUE);

            workspace.setText("Вся рабочая область");
            custom.setText("По настроенному отбору");

            Button navigator = addRadio(radioRow, "Отобранное в навигаторе");
            Button activeSets = addRadio(radioRow, "Активные наборы");
            // все варианты области — в одну строку (было 3 колонки под штатные радио)
            if (radioRow.getLayout() instanceof GridLayout grid)
            {
                int radios = 0;
                for (Control child : radioRow.getChildren())
                {
                    if (child instanceof Button b && (b.getStyle() & SWT.RADIO) != 0)
                        radios++;
                }
                grid.numColumns = Math.max(grid.numColumns, radios);
            }
            Object searchData = Global.getField(page, "searchData"); //$NON-NLS-1$
            Button[] nativeRadios = {workspace, custom,
                findScopeRadio(pageControl, "Содержащие проекты", "Enclosing projects")};

            for (Button nativeRadio : nativeRadios)
            {
                if (nativeRadio == null)
                    continue;
                nativeRadio.addListener(SWT.Selection, e -> {
                    if (nativeRadio.getSelection())
                    {
                        navigator.setSelection(false);
                        activeSets.setSelection(false);
                        setMode(ScopeMode.NONE);
                        getDialogSettings().put(SCOPE_MODE_KEY, ScopeMode.NONE.name());
                    }
                });
            }
            navigator.addListener(SWT.Selection,
                e -> onComfortScope(navigator.getSelection(), navigator, activeSets, nativeRadios,
                    searchData, ScopeMode.NAVIGATOR));
            activeSets.addListener(SWT.Selection,
                e -> onComfortScope(activeSets.getSelection(), activeSets, navigator, nativeRadios,
                    searchData, ScopeMode.ACTIVE_SETS));

            setTooltip(workspace, "Искать во всех проектах рабочей области");
            setTooltip(nativeRadios[2],
                "Искать в проектах, содержащих текущий объект или открытый редактор");
            setTooltip(custom,
                "Искать в проектах и типах объектов, отмеченных в списках «среди проектов» и "
                    + "«среди типов объектов»");
            setTooltip(navigator,
                "Искать только в объектах, видимых в навигаторе с учётом его активного отбора "
                    + "(фильтр по подсистемам или по набору). Если отбор в навигаторе не включён — "
                    + "область не ограничивается" + Global.pluginSignForTooltip());
            setTooltip(activeSets, activeSetsTooltip());

            setMode(ScopeMode.NONE);
            Composite top = radioRow.getParent();
            if (top != null && !top.isDisposed())
                top.layout(true, true);
            restoreSavedMode(navigator, activeSets, nativeRadios, searchData);
        }

        /** Восстановить выбранный ранее вариант «Отобранное в навигаторе» / «Активные наборы». */
        private static void restoreSavedMode(Button navigator, Button activeSets,
            Button[] nativeRadios, Object searchData)
        {
            String saved = getDialogSettings().get(SCOPE_MODE_KEY);
            ScopeMode mode;
            try
            {
                mode = saved != null ? ScopeMode.valueOf(saved) : ScopeMode.NONE;
            }
            catch (IllegalArgumentException e)
            {
                mode = ScopeMode.NONE;
            }
            if (mode == ScopeMode.NONE)
                return;
            Button self = mode == ScopeMode.NAVIGATOR ? navigator : activeSets;
            Button other = mode == ScopeMode.NAVIGATOR ? activeSets : navigator;
            final ScopeMode target = mode;
            navigator.getDisplay().asyncExec(
                () -> onComfortScope(true, self, other, nativeRadios, searchData, target));
        }

        private static void onComfortScope(boolean selected, Button self, Button otherComfort,
            Button[] nativeRadios, Object searchData, ScopeMode mode)
        {
            if (!selected || self.isDisposed())
                return;
            getDialogSettings().put(SCOPE_MODE_KEY, mode.name());
            broadenNativeScope(searchData);
            otherComfort.setSelection(false);
            // Штатная привязка после смены модели снова отметит нативное радио —
            // снимаем отметки и оставляем только наше уже после её отработки.
            Runnable fixVisual = () -> {
                if (self.isDisposed())
                    return;
                for (Button nativeRadio : nativeRadios)
                {
                    if (nativeRadio != null && !nativeRadio.isDisposed())
                        nativeRadio.setSelection(false);
                }
                if (!otherComfort.isDisposed())
                    otherComfort.setSelection(false);
                self.setSelection(true);
            };
            self.getDisplay().asyncExec(fixVisual);
            self.getDisplay().timerExec(60, fixVisual);
            setMode(mode);
        }

        /**
         * Наши варианты области не относятся к штатному {@code UiSearchScope}: ставим модель в
         * {@code WORKSPACE} (валидное значение — привязка не «откатывает» на прежнее) и очищаем
         * списки проектов/типов, чтобы {@code performAction} искал по всей рабочей области.
         * Ограничение результатов — уже наше, на уровне сборщика.
         */
        private static void broadenNativeScope(Object searchData)
        {
            if (searchData == null)
                return;
            Object scope = Global.invoke(searchData, "getSearchScope"); //$NON-NLS-1$
            Object workspaceValue = enumConstant(
                "com._1c.g5.v8.dt.internal.search.ui.dialog.UiSearchScope", "WORKSPACE"); //$NON-NLS-1$ //$NON-NLS-2$
            if (scope != null && workspaceValue != null)
                Global.invoke(scope, "setValue", new Object[] {workspaceValue}); //$NON-NLS-1$
            for (String getter : new String[] {"getSearchProjectScope", "getSearchTypeScope"}) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Object set = Global.invoke(searchData, getter);
                if (set != null)
                    Global.invoke(set, "clear"); //$NON-NLS-1$
            }
        }

        private static Object enumConstant(String className, String name)
        {
            try
            {
                Object[] constants = Class.forName(className).getEnumConstants();
                if (constants != null)
                {
                    for (Object constant : constants)
                    {
                        if (name.equals(((Enum<?>) constant).name()))
                            return constant;
                    }
                }
            }
            catch (Exception e)
            {
                log("enumConstant " + className + "#" + name + ": " + e);
            }
            return null;
        }

        private static Button addRadio(Composite radioRow, String text)
        {
            Button button = new Button(radioRow, SWT.RADIO);
            button.setText(text);
            button.setFont(radioRow.getFont());
            return button;
        }

        private static void setTooltip(Button button, String text)
        {
            if (button != null && !button.isDisposed())
                button.setToolTipText(TooltipText.wrap(button, text));
        }

        /** Подсказка «Активные наборы»: активный набор каждого открытого проекта на своей строке. */
        private static String activeSetsTooltip()
        {
            return ObjectSetsAddTargetState.getInstance().withActiveSetsLines(
                "Искать только в объектах активных наборов проектов и их подобъектах"
                    + Global.pluginSignForTooltip());
        }

        private static Button findScopeRadio(Composite root, String... texts)
        {
            return Global.findControl(root, Button.class, button ->
            {
                if ((button.getStyle() & SWT.RADIO) == 0)
                    return false;
                String label = button.getText() != null ? button.getText().trim() : "";
                for (String text : texts)
                {
                    if (text.equalsIgnoreCase(label))
                        return true;
                }
                return false;
            });
        }

        private static void setMode(ScopeMode mode)
        {
            currentMode = mode;
            currentRefs = mode == ScopeMode.NONE ? null : buildRefs(mode);
        }

        private static Map<String, List<String>> buildRefs(ScopeMode mode)
        {
            if (mode == ScopeMode.NAVIGATOR)
                return ObjectSetSubsystemsFilterBridge.visibleOwnerRefsByProject();
            Map<String, List<String>> result = new HashMap<>();
            java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
            for (String project : ObjectSetsAddTargetState.getInstance().projectsWithAddTarget())
            {
                List<String> refs = ObjectSetsItems.addTargetOwnerRefs(project);
                if (refs != null && !refs.isEmpty())
                {
                    result.put(project, refs);
                    union.addAll(refs);
                }
            }
            result.put("", new ArrayList<>(union)); //$NON-NLS-1$
            return result;
        }
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
                if ("run".equals(method.getName()) && args != null && args.length == 3)
                {
                    boolean wholeWord = getDialogSettings().getBoolean(KEY_WHOLE_WORD);
                    Map<String, List<String>> scopeRefs = SearchScopeGroup.refs();
                    if (wholeWord || scopeRefs != null)
                    {
                        Object input = args[0];
                        String sq = (String) Global.invoke(input, "getSearchString");
                        boolean wildcards = sq != null && (sq.contains("?") || sq.contains("*"));
                        String wordFilter = wholeWord && sq != null && !wildcards ? sq : null;
                        boolean caseSensitive =
                            Boolean.TRUE.equals(Global.invoke(input, "isCaseSensitive"));
                        if (wordFilter != null || scopeRefs != null)
                            args[1] = createFilteredCollector(
                                args[1], wordFilter, caseSensitive, scopeRefs, executorCL);
                    }
                }
                return method.invoke(executor, args);
            });
    }

    private static Object createFilteredCollector(Object origCollector, String searchString,
        boolean caseSensitive, Map<String, List<String>> scopeRefs, ClassLoader cl) throws Exception
    {
        Class<?> iface = Class.forName(
            "com._1c.g5.v8.dt.search.core.ISearchResultCollector");
        ClassLoader ifaceCL = iface.getClassLoader();
        if (ifaceCL == null)
            ifaceCL = cl;
        SearchSetMembership membership = scopeRefs != null ? new SearchSetMembership(scopeRefs) : null;
        return Proxy.newProxyInstance(ifaceCL, new Class[] { iface },
            (proxy, method, args) ->
            {
                if ("addMatch".equals(method.getName()) && args != null && args.length == 1)
                {
                    if (accept(args[0], searchString, caseSensitive, membership))
                        return method.invoke(origCollector, args);
                    return null;
                }
                if ("addMatches".equals(method.getName()) && args != null && args.length == 1)
                {
                    Collection<?> matches = (Collection<?>) args[0];
                    List<Object> filtered = new ArrayList<>();
                    for (Object m : matches)
                        if (accept(m, searchString, caseSensitive, membership))
                            filtered.add(m);
                    if (filtered.size() == matches.size())
                        return method.invoke(origCollector, args);
                    return method.invoke(origCollector, new Object[] { filtered });
                }
                return method.invoke(origCollector, args);
            });
    }

    private static boolean accept(Object match, String searchString, boolean caseSensitive,
        SearchSetMembership membership) throws Exception
    {
        if (searchString != null && !isWholeWordMatch(match, searchString, caseSensitive))
            return false;
        if (membership != null && !membership.allows(match))
            return false;
        return true;
    }

    /**
     * Проверка принадлежности вхождения объектам отбора («Активные наборы» / «Отобранное в
     * навигаторе», issue #419). {@code refsByProject} — снимок владеющих ссылок по проектам,
     * сделанный при выборе варианта. Решение по владеющему объекту метаданных кэшируется по его
     * bm-id. Работает на фоновом потоке поиска.
     */
    private static final class SearchSetMembership
    {
        private final Map<Long, Boolean> byTopObject = new HashMap<>();
        private final Map<String, List<String>> refsByProject;

        SearchSetMembership(Map<String, List<String>> refsByProject)
        {
            this.refsByProject = refsByProject != null ? refsByProject : Map.of();
        }

        boolean allows(Object matchObj)
        {
            if (!(matchObj instanceof Match match))
                return true;
            try
            {
                if (match instanceof TextSearchFileMatch fileMatch)
                {
                    IFile file = fileMatch.getFile();
                    if (file == null || file.getProject() == null)
                        return true;
                    String rel = file.getProjectRelativePath().toString();
                    String full = GetRef.isConfigurationRootPath(rel) ? null
                        : GetRef.pathToFullName(rel);
                    return inSet(file.getProject().getName(), full);
                }
                long topId = 0;
                try
                {
                    topId = match.getMetadataTopObjectId();
                }
                catch (RuntimeException ignored)
                {
                    // у вхождения нет parent-provider — резолвим по собственному объекту
                }
                if (topId != 0)
                {
                    Boolean cached = byTopObject.get(topId);
                    if (cached != null)
                        return cached;
                }
                String[] pf = resolve(match, topId);
                boolean result = inSet(pf[0], pf[1]);
                if (topId != 0)
                    byTopObject.put(topId, result);
                return result;
            }
            catch (RuntimeException e)
            {
                log("membership error: " + e);
                return true;
            }
        }

        private boolean inSet(String projectName, String fullName)
        {
            List<String> refs = projectName != null ? refsByProject.get(projectName) : null;
            if ((refs == null || refs.isEmpty()) && refsByProject.containsKey(""))
                refs = refsByProject.get(""); //$NON-NLS-1$
            return refs != null && !refs.isEmpty()
                && ObjectSetsItems.isUnderAnyOwnerRef(refs, fullName);
        }

        private static String[] resolve(Match match, long topId)
        {
            IBmModel model = match.getModel();
            if (model == null)
                return new String[] { null, null };
            final long id = topId != 0 ? topId : idOf(match);
            if (id == 0)
                return new String[] { null, null };
            // Переиспользуем текущую транзакцию поиска, если она есть (как Match.resolveObjectById),
            // иначе — собственная readonly-задача.
            Object engine = Global.invoke(model, "getEngine");
            Object current = engine != null ? Global.invoke(engine, "getCurrentTransaction") : null;
            if (current instanceof IBmTransaction transaction)
                return fromTransaction(transaction, id);
            String[] result = model.executeReadonlyTask(
                new AbstractBmTask<String[]>("comfort.searchSetFilter") //$NON-NLS-1$
                {
                    @Override
                    public String[] execute(IBmTransaction transaction, IProgressMonitor monitor)
                    {
                        return fromTransaction(transaction, id);
                    }
                }, true);
            return result != null ? result : new String[] { null, null };
        }

        private static String[] fromTransaction(IBmTransaction transaction, long id)
        {
            IBmObject object = transaction.getObjectById(id);
            if (!(object instanceof EObject eObject))
                return new String[] { null, null };
            return new String[] { projectNameOf(eObject), GetRef.eObjectToFullName(eObject) };
        }

        private static long idOf(Match match)
        {
            Object value = Global.invoke(match, "getTopObjectId");
            if (!(value instanceof Long))
                value = Global.invoke(match, "getObjectId");
            if (!(value instanceof Long))
            {
                Object source = Global.invoke(match, "getSource");
                if (source != null)
                    value = Global.invoke(source, "getObjectId");
            }
            return value instanceof Long ? (Long) value : 0L;
        }

        private static String projectNameOf(EObject eObject)
        {
            try
            {
                IV8ProjectManager manager = Global.getOsgiService(IV8ProjectManager.class);
                if (manager == null)
                    return null;
                IV8Project project = manager.getProject(eObject);
                if (project != null && project.getProject() != null)
                    return project.getProject().getName();
            }
            catch (RuntimeException ignored)
            {
                // не удалось определить проект — вхождение не отсекаем
            }
            return null;
        }
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
