package tormozit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IStartup;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.search.core.Match;
import com._1c.g5.v8.dt.search.core.SearchFor;
import com._1c.g5.v8.dt.search.core.SearchIn;
import com._1c.g5.v8.dt.search.core.text.TextSearchFileMatch;
import com._1c.g5.v8.dt.search.core.text.TextSearchModelMatch;

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
    private static final String SEARCH_IN_COUNT_KEY = "tormozit.configSearchSearchInCount";
    private static final String SEARCH_IN_TITLE_RU = "Искать в";
    private static final String SEARCH_IN_TITLE_EN = "Search In";
    private static final String OBJECT_TYPES_TITLE_RU ="среди типов объектов";
    private static final String OBJECT_TYPES_TITLE_EN = "within object types";
    private static final String PROJECTS_TITLE_RU = "среди проектов";
    private static final String PROJECTS_TITLE_EN = "within projects";
    private static final Pattern TITLE_COUNT_SUFFIX = Pattern.compile(" \\(\\d+\\)$");

    /** Регистрируется из Activator.start до первой загрузки поискового класса EDT. */
    public static void installWeavingHook()
    {
        ChoiceParameterSearchPatch.install();
    }

    @Override
    public void earlyStartup()
    {
        ChoiceParameterSearchPatch.installFallback();
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
        installSearchInCountTitle(page);
        SearchScopeGroup.patch(shell, page);
        NStrCategory.patchLabels(page);
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

    /** Счётчик включённых пометок в заголовке группы «Искать в». */
    private static void installSearchInCountTitle(Object page)
    {
        Control pageControl = (Control) Global.invoke(page, "getControl");
        if (!(pageControl instanceof Composite root) || root.isDisposed())
            return;
        Group group = Global.findControl(root, Group.class,
            g -> isScopeListTitle(g.getText(), SEARCH_IN_TITLE_RU, SEARCH_IN_TITLE_EN));
        if (group == null || Boolean.TRUE.equals(group.getData(SEARCH_IN_COUNT_KEY)))
            return;
        group.setData(SEARCH_IN_COUNT_KEY, Boolean.TRUE);

        final String baseTitle = stripCountSuffix(group.getText()).trim();
        Runnable refresh = () -> {
            if (group.isDisposed())
                return;
            int checked = 0;
            for (Control child : group.getChildren())
            {
                if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0
                    && button.getSelection())
                    checked++;
            }
            String title = baseTitle + " (" + checked + ")";
            if (!title.equals(group.getText()))
                group.setText(title);
        };

        for (Control child : group.getChildren())
        {
            if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0)
                button.addListener(SWT.Selection, e -> refresh.run());
        }
        // Пометки меняет и сама EDT (подстановка прошлого запроса из истории) — без события Selection.
        Object searchData = Global.getField(page, "searchData");
        Object searchIn = searchData != null ? Global.invoke(searchData, "getSearchIn") : null;
        if (searchIn instanceof Map<?, ?> searchInMap)
            addSearchInCheckAllButtons(root, group, searchInMap, refresh);
        if (searchIn != null)
        {
            try
            {
                // интерфейс — загрузчиком самой модели: пакет databinding плагину не виден
                Class<?> listenerClass = Class.forName(
                    "org.eclipse.core.databinding.observable.map.IMapChangeListener", true,
                    searchIn.getClass().getClassLoader());
                Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class[] { listenerClass },
                    (proxy, method, args) -> {
                        // список слушателей сравнивает их через equals/hashCode: null здесь = NPE
                        switch (method.getName())
                        {
                        case "equals": //$NON-NLS-1$
                            return Boolean.valueOf(proxy == args[0]);
                        case "hashCode": //$NON-NLS-1$
                            return Integer.valueOf(System.identityHashCode(proxy));
                        case "toString": //$NON-NLS-1$
                            return "ConfigSearchDialogHook.searchInListener"; //$NON-NLS-1$
                        case "handleMapChange": //$NON-NLS-1$
                            if (!group.isDisposed())
                                group.getDisplay().asyncExec(refresh);
                            return null;
                        default:
                            return null;
                        }
                    });
                searchIn.getClass().getMethod("addMapChangeListener", listenerClass)
                    .invoke(searchIn, listener);
            }
            catch (Exception e)
            {
                log("installSearchInCountTitle error: " + e + " cause=" + e.getCause());
            }
        }
        refresh.run();
        root.getDisplay().timerExec(100, refresh);
    }

    /**
     * Кнопки «установить / снять все флажки» в группе «Искать в». Меняется модель диалога
     * ({@code ConfigurationSearchData.getSearchIn()}), флажки обновляет штатная привязка —
     * {@code Button.setSelection} события не шлёт, и модель осталась бы прежней.
     */
    private static void addSearchInCheckAllButtons(Composite root, Group group, Map<?, ?> searchIn,
        Runnable refreshCount)
    {
        // значки — как у штатных кнопок над списками «среди проектов» / «среди типов объектов»
        ToolBar stock = Global.findControl(root, ToolBar.class, bar -> bar.getItemCount() == 2);
        // кнопки — в начале последней колонки последней строки (под её флажками)
        if (group.getLayout() instanceof GridLayout grid && grid.numColumns > 0)
        {
            int cells = group.getChildren().length;
            for (int filler = grid.numColumns - 1 - cells % grid.numColumns; filler > 0; filler--)
                new Label(group, SWT.NONE);
        }
        ToolBar toolBar = new ToolBar(group, SWT.FLAT | SWT.HORIZONTAL);
        addSearchInCheckAllItem(toolBar, searchIn, true, "Установить все флажки",
            stock != null ? stock.getItem(0).getImage() : null, "Все", refreshCount);
        addSearchInCheckAllItem(toolBar, searchIn, false, "Снять все флажки",
            stock != null ? stock.getItem(1).getImage() : null, "Ничего", refreshCount);

        toolBar.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        root.layout(true, true);
    }

    @SuppressWarnings("unchecked")
    private static void addSearchInCheckAllItem(ToolBar toolBar, Map<?, ?> searchIn, boolean check,
        String tooltip, Image image, String fallbackText, Runnable refreshCount)
    {
        ToolItem item = new ToolItem(toolBar, SWT.PUSH);
        if (image != null && !image.isDisposed())
            item.setImage(image);
        else
            item.setText(fallbackText);
        item.setToolTipText(TooltipText.wrap(toolBar, tooltip + Global.pluginSignForTooltip()));
        item.addListener(SWT.Selection, e -> {
            for (SearchIn value : SearchIn.values())
                ((Map<Object, Object>) searchIn).put(value, Boolean.valueOf(check));
            toolBar.getDisplay().asyncExec(refreshCount);
        });
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
                    Object input = args[0];
                    NStrCategory nstr = NStrCategory.forInput(input);
                    try
                    {
                        String wordFilter = null;
                        boolean caseSensitive = false;
                        if (wholeWord || scopeRefs != null)
                        {
                            String sq = (String) Global.invoke(input, "getSearchString");
                            boolean wildcards = sq != null && (sq.contains("?") || sq.contains("*"));
                            wordFilter = wholeWord && sq != null && !wildcards ? sq : null;
                            caseSensitive =
                                Boolean.TRUE.equals(Global.invoke(input, "isCaseSensitive"));
                        }
                        if (wordFilter != null || scopeRefs != null || nstr != null)
                            args[1] = createFilteredCollector(
                                args[1], wordFilter, caseSensitive, scopeRefs, nstr, executorCL);
                        return method.invoke(executor, args);
                    }
                    finally
                    {
                        if (nstr != null)
                            nstr.restore(input);
                    }
                }
                return method.invoke(executor, args);
            });
    }

    private static Object createFilteredCollector(Object origCollector, String searchString,
        boolean caseSensitive, Map<String, List<String>> scopeRefs, NStrCategory nstr, ClassLoader cl)
        throws Exception
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
                    if (accept(args[0], searchString, caseSensitive, membership, nstr))
                        return method.invoke(origCollector, args);
                    return null;
                }
                if ("addMatches".equals(method.getName()) && args != null && args.length == 1)
                {
                    Collection<?> matches = (Collection<?>) args[0];
                    List<Object> filtered = new ArrayList<>();
                    for (Object m : matches)
                        if (accept(m, searchString, caseSensitive, membership, nstr))
                            filtered.add(m);
                    if (filtered.size() == matches.size())
                        return method.invoke(origCollector, args);
                    return method.invoke(origCollector, new Object[] { filtered });
                }
                return method.invoke(origCollector, args);
            });
    }

    private static boolean accept(Object match, String searchString, boolean caseSensitive,
        SearchSetMembership membership, NStrCategory nstr) throws Exception
    {
        if (searchString != null && !isWholeWordMatch(match, searchString, caseSensitive))
            return false;
        if (nstr != null && !nstr.allows(match))
            return false;
        if (membership != null && !membership.allows(match))
            return false;
        return true;
    }

    /**
     * Строковые литералы — аргументы {@code НСтр()} в модулях относятся к категории «Строки
     * пользовательского интерфейса», а не «Языковые элементы» (issue #454).
     *
     * <p>Штатный индекс категорий внутри модуля не различает: каждая строка любого файла записана
     * с {@code searchFor = LANGUAGE_ELEMENTS} ({@code BaseTextSearchIndex.indexFile}), а категория
     * «Строки пользовательского интерфейса» модули не просматривает вовсе. Поэтому:
     * <ul>
     *   <li>отмечены только «Языковые элементы» — из результатов убираются вхождения внутри
     *       литералов {@code НСтр()};</li>
     *   <li>отмечены только «Строки пользовательского интерфейса» — на время поиска в запрос
     *       добавляется {@code LANGUAGE_ELEMENTS} (иначе модули не попадут в выборку), а сборщик
     *       оставляет из файлов только литералы {@code НСтр()}, из модели — только реквизиты,
     *       проходящие штатный предикат исходного набора категорий;</li>
     *   <li>отмечены обе либо ни одна из двух — поиск не меняется.</li>
     * </ul>
     * Принадлежность литералу — лексически по тексту модуля (согласовано): Xtext-разбор каждого
     * модуля с вхождениями замедлил бы поиск в разы.
     */
    private static final class NStrCategory
    {
        private static final String INPUT_CLASS =
            "com._1c.g5.v8.dt.internal.search.ui.text.TextSearchInput"; //$NON-NLS-1$
        private static final String INPUT_FIELD = "searchFors"; //$NON-NLS-1$
        private static final String PREDICATES_CLASS =
            "com._1c.g5.v8.dt.internal.search.core.SearchForPredicatesProvider"; //$NON-NLS-1$
        private static final String LABELS_CLASS = "com._1c.g5.v8.dt.search.ui.SearchLabels"; //$NON-NLS-1$

        /** {@code true} — из модулей нужны только литералы НСтр(); {@code false} — всё, кроме них. */
        private final boolean onlyNStr;
        /** Исходный набор категорий, подменённый на время поиска; {@code null} — не подменялся. */
        private final Object originalSearchFors;
        /** Штатный предикат реквизитов исходного набора категорий (только при {@link #onlyNStr}). */
        private final Predicate<EAttribute> originalAttributes;

        private NStrCategory(boolean onlyNStr, Object originalSearchFors,
            Predicate<EAttribute> originalAttributes)
        {
            this.onlyNStr = onlyNStr;
            this.originalSearchFors = originalSearchFors;
            this.originalAttributes = originalAttributes;
        }

        /** @return {@code null}, если для этого поиска разделять литералы НСтр() не требуется */
        static NStrCategory forInput(Object input)
        {
            if (input == null || !INPUT_CLASS.equals(input.getClass().getName()))
                return null;
            if (!(Global.getField(input, INPUT_FIELD) instanceof Set<?> searchFors))
                return null;
            boolean language = searchFors.contains(SearchFor.LANGUAGE_ELEMENTS);
            boolean uiStrings = searchFors.contains(SearchFor.UI_STRINGS);
            if (language == uiStrings)
                return null;
            if (language)
                return new NStrCategory(false, null, null);

            Predicate<EAttribute> attributes = attributePredicate(searchFors);
            if (attributes == null)
                return null;
            Set<Object> broadened = new HashSet<>(searchFors);
            broadened.add(SearchFor.LANGUAGE_ELEMENTS);
            if (!Global.setFieldForce(input, INPUT_FIELD, broadened))
                return null;
            return new NStrCategory(true, searchFors, attributes);
        }

        /** Вернуть исходный набор категорий — запрос можно выполнить повторно («Искать снова»). */
        void restore(Object input)
        {
            if (originalSearchFors != null)
                Global.setFieldForce(input, INPUT_FIELD, originalSearchFors);
        }

        boolean allows(Object match)
        {
            if (match instanceof TextSearchFileMatch fileMatch)
            {
                Boolean inside = insideNStr(fileMatch);
                return inside == null ? !onlyNStr : inside.booleanValue() == onlyNStr;
            }
            if (onlyNStr && match instanceof TextSearchModelMatch modelMatch)
                return !(modelMatch.getFeature() instanceof EAttribute attribute)
                    || originalAttributes.test(attribute);
            return true;
        }

        @SuppressWarnings("unchecked")
        private static Predicate<EAttribute> attributePredicate(Set<?> searchFors)
        {
            try
            {
                Class<?> provider = Class.forName(PREDICATES_CLASS, true, Match.class.getClassLoader());
                return (Predicate<EAttribute>) provider
                    .getMethod("getEAttributeSearchForPredicate", Collection.class) //$NON-NLS-1$
                    .invoke(null, searchFors);
            }
            catch (Exception e)
            {
                log("NStrCategory.attributePredicate: " + e);
                return null;
            }
        }

        /** @return {@code null} — не модуль либо позицию вхождения в тексте определить не удалось */
        private static Boolean insideNStr(TextSearchFileMatch match)
        {
            String content = BslModuleMethodResolver.moduleText(match.getFile());
            if (content == null)
                return null;
            int offset = locate(content, match);
            if (offset < 0)
                return null;
            return Boolean.valueOf(isNStrLiteral(content, offset));
        }

        /** Смещение вхождения в {@code content}, сверенное с текстом самого вхождения. */
        private static int locate(String content, TextSearchFileMatch match)
        {
            String line = match.getText();
            int inLine = match.getTextOffset();
            int length = match.getTextLength();
            if (line == null || inLine < 0 || length <= 0 || inLine + length > line.length())
                return -1;
            int fileOffset = match.getFileOffset();
            if (fileOffset >= 0 && content.regionMatches(fileOffset, line, inLine, length))
                return fileOffset;

            long lineNumber = match.getLineNumber();
            int lineStart = 0;
            for (long current = 1; current < lineNumber; current++)
            {
                int eol = content.indexOf('\n', lineStart);
                if (eol < 0)
                    return -1;
                lineStart = eol + 1;
            }
            int byLine = lineStart + inLine;
            return content.regionMatches(byLine, line, inLine, length) ? byLine : -1;
        }

        private static boolean isNStrLiteral(String content, int offset)
        {
            int openQuote = literalOpenQuote(content, offset);
            if (openQuote < 0)
                return false;
            int paren = skipBlankBack(content, openQuote);
            if (paren == 0 || content.charAt(paren - 1) != '(')
                return false;
            int nameEnd = skipBlankBack(content, paren - 1);
            int nameStart = nameEnd;
            while (nameStart > 0 && isIdentifierChar(content.charAt(nameStart - 1)))
                nameStart--;
            String name = content.substring(nameStart, nameEnd);
            if (!"НСтр".equalsIgnoreCase(name) && !"NStr".equalsIgnoreCase(name)) //$NON-NLS-1$
                return false;
            int before = skipBlankBack(content, nameStart);
            return before == 0 || content.charAt(before - 1) != '.';
        }

        /**
         * Смещение открывающей кавычки литерала, внутри которого лежит {@code offset}, либо
         * {@code -1}. Многострочный литерал: строки продолжения начинаются с {@code |}, между
         * ними допустимы строки-комментарии; {@code ""} внутри литерала — экранированная кавычка.
         */
        private static int literalOpenQuote(String content, int offset)
        {
            int length = content.length();
            int start = lineStart(content, offset);
            while (start > 0 && isContinuationOrComment(content, start))
                start = lineStart(content, previousLineEnd(content, start));

            boolean inside = false;
            int openQuote = -1;
            int pos = start;
            while (pos < offset)
            {
                char c = content.charAt(pos);
                if (!inside)
                {
                    if (c == '"')
                    {
                        inside = true;
                        openQuote = pos;
                    }
                    else if (c == '/' && pos + 1 < length && content.charAt(pos + 1) == '/')
                    {
                        pos = lineEnd(content, pos);
                        continue;
                    }
                }
                else if (c == '"')
                {
                    if (pos + 1 < length && content.charAt(pos + 1) == '"')
                    {
                        pos += 2;
                        continue;
                    }
                    inside = false;
                }
                else if (c == '\n')
                {
                    int next = skipInlineBlank(content, pos + 1);
                    if (next < length && content.charAt(next) == '|')
                    {
                        pos = next + 1;
                        continue;
                    }
                    if (next + 1 < length && content.charAt(next) == '/' && content.charAt(next + 1) == '/')
                    {
                        pos = lineEnd(content, next);
                        if (pos >= offset)
                            return -1;
                        continue;
                    }
                    inside = false;
                }
                pos++;
            }
            return inside ? openQuote : -1;
        }

        private static boolean isContinuationOrComment(String content, int lineStart)
        {
            int pos = skipInlineBlank(content, lineStart);
            if (pos >= content.length())
                return false;
            char c = content.charAt(pos);
            return c == '|'
                || c == '/' && pos + 1 < content.length() && content.charAt(pos + 1) == '/';
        }

        private static int lineStart(String content, int offset)
        {
            int pos = Math.min(offset, content.length());
            while (pos > 0 && content.charAt(pos - 1) != '\n')
                pos--;
            return pos;
        }

        /** Позиция внутри предыдущей строки (перед её разделителем) для строки с началом {@code lineStart}. */
        private static int previousLineEnd(String content, int lineStart)
        {
            int pos = lineStart - 1;
            if (pos > 0 && content.charAt(pos - 1) == '\r')
                pos--;
            return pos;
        }

        private static int lineEnd(String content, int offset)
        {
            int pos = offset;
            while (pos < content.length() && content.charAt(pos) != '\n')
                pos++;
            return pos;
        }

        private static int skipInlineBlank(String content, int offset)
        {
            int pos = offset;
            while (pos < content.length() && (content.charAt(pos) == ' ' || content.charAt(pos) == '\t'))
                pos++;
            return pos;
        }

        private static int skipBlankBack(String content, int offset)
        {
            int pos = offset;
            while (pos > 0 && Character.isWhitespace(content.charAt(pos - 1)))
                pos--;
            return pos;
        }

        private static boolean isIdentifierChar(char c)
        {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        /** Надпись и подсказки пометок категорий в группе «Искать». */
        static void patchLabels(Object page)
        {
            Object control = Global.invoke(page, "getControl"); //$NON-NLS-1$
            if (!(control instanceof Composite pageControl) || pageControl.isDisposed())
                return;
            Button language = findCategory(page, pageControl, SearchFor.LANGUAGE_ELEMENTS);
            Button uiStrings = findCategory(page, pageControl, SearchFor.UI_STRINGS);
            Button comments = findCategory(page, pageControl, SearchFor.COMMENTS);
            if (language == null || uiStrings == null)
                return;
            String uiStringsLabel = uiStrings.getText();
            language.setText("Языковые элементы кроме НСтр()");
            if (comments != null)
            {
                comments.setText("Комментарии метаданных");
                comments.setToolTipText(TooltipText.wrap(comments,
                    "Свойство «Комментарий» объектов метаданных, реквизитов, команд и параметров "
                        + "форм" + Global.pluginSignForTooltip()));
            }
            language.setToolTipText(TooltipText.wrap(language,
                "Имена и другие служебные строки объектов метаданных, тексты модулей и макетов. "
                    + "Строковые литералы внутри НСтр() сюда не входят — они ищутся по пометке «"
                    + uiStringsLabel + "»" + Global.pluginSignForTooltip()));
            uiStrings.setToolTipText(TooltipText.wrap(uiStrings,
                "Синонимы, заголовки, подсказки и другие тексты, которые видит пользователь, "
                    + "а также строковые литералы внутри НСтр() в модулях"
                    + Global.pluginSignForTooltip()));
            pageControl.layout(true, true);
        }

        private static Button findCategory(Object page, Composite pageControl, SearchFor category)
        {
            String label;
            try
            {
                label = (String) Class.forName(LABELS_CLASS, true, page.getClass().getClassLoader())
                    .getMethod("getSearchForText", SearchFor.class) //$NON-NLS-1$
                    .invoke(null, category);
            }
            catch (Exception e)
            {
                log("NStrCategory.findCategory " + category + ": " + e);
                return null;
            }
            if (label == null)
                return null;
            return Global.findControl(pageControl, Button.class,
                button -> (button.getStyle() & SWT.CHECK) != 0 && label.equals(button.getText()));
        }
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

        return IdentifierSelectionSupport.isWholeWordMatch(fullText, offset, offset + length);
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

    /** Добавляет имя параметра выбора к полям, просматриваемым штатным поиском элементов языка. */
    private static final class ChoiceParameterSearchPatch
    {
        private static final String TARGET =
            "com._1c.g5.v8.dt.internal.search.core.SearchForPredicatesProvider"; //$NON-NLS-1$
        private static final String TARGET_INTERNAL = TARGET.replace('.', '/');
        private static final String ATTRIBUTE_OWNER =
            "com/_1c/g5/v8/dt/metadata/common/CommonPackage$Literals"; //$NON-NLS-1$
        private static final String ATTRIBUTE_NAME = "CHOICE_PARAMETER__NAME"; //$NON-NLS-1$
        private static final String ATTRIBUTE_DESC = "Lorg/eclipse/emf/ecore/EAttribute;"; //$NON-NLS-1$
        private static final String PREDICATE_DESC = "(Lorg/eclipse/emf/ecore/EAttribute;)Z"; //$NON-NLS-1$

        private static final AtomicBoolean installed = new AtomicBoolean();
        private static volatile boolean woven;

        private ChoiceParameterSearchPatch() {}

        static void install()
        {
            if (!installed.compareAndSet(false, true))
                return;
            Bundle bundle = FrameworkUtil.getBundle(ConfigSearchDialogHook.class);
            BundleContext context = bundle != null ? bundle.getBundleContext() : null;
            if (context == null)
                return;
            context.registerService(WeavingHook.class, new SearchWeavingHook(), null);
        }

        static void installFallback()
        {
            install();
            if (woven)
                return;
            BslDocCommentDescriptionFix.registerExtraTransformer(new SearchTransformer(), TARGET);
        }

        private static byte[] transform(byte[] original)
        {
            ClassReader reader = new ClassReader(original);
            AtomicBoolean found = new AtomicBoolean();
            AtomicBoolean alreadyPatched = new AtomicBoolean();
            reader.accept(new ClassVisitor(Opcodes.ASM9)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    if (!"lambda$1".equals(name) || !PREDICATE_DESC.equals(descriptor)) //$NON-NLS-1$
                        return null;
                    found.set(true);
                    return new MethodVisitor(Opcodes.ASM9)
                    {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String field,
                            String fieldDescriptor)
                        {
                            if (opcode == Opcodes.GETSTATIC && ATTRIBUTE_OWNER.equals(owner)
                                && ATTRIBUTE_NAME.equals(field))
                                alreadyPatched.set(true);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!found.get() || alreadyPatched.get())
                return null;

            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
            {
                @Override
                protected String getCommonSuperClass(String type1, String type2)
                {
                    return "java/lang/Object"; //$NON-NLS-1$
                }
            };
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature,
                        exceptions);
                    if (!"lambda$1".equals(name) || !PREDICATE_DESC.equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitCode()
                        {
                            super.visitCode();
                            org.objectweb.asm.Label originalCode = new org.objectweb.asm.Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTE_OWNER,
                                ATTRIBUTE_NAME, ATTRIBUTE_DESC);
                            super.visitJumpInsn(Opcodes.IF_ACMPNE, originalCode);
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitInsn(Opcodes.IRETURN);
                            super.visitLabel(originalCode);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        }

        private static final class SearchWeavingHook implements WeavingHook
        {
            @Override
            public void weave(WovenClass wovenClass)
            {
                if (wovenClass.getState() != WovenClass.TRANSFORMING
                    || !TARGET.equals(wovenClass.getClassName()))
                    return;
                try
                {
                    byte[] changed = transform(wovenClass.getBytes());
                    if (changed != null)
                    {
                        wovenClass.setBytes(changed);
                        woven = true;
                    }
                }
                catch (Throwable t)
                {
                    // Сбой подмены не должен мешать загрузке класса.
                }
            }
        }

        private static final class SearchTransformer implements ClassFileTransformer
        {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain, byte[] classfileBuffer)
            {
                if (!TARGET_INTERNAL.equals(className))
                    return null;
                try
                {
                    byte[] changed = ChoiceParameterSearchPatch.transform(classfileBuffer);
                    if (changed != null)
                        woven = true;
                    return changed;
                }
                catch (Throwable t)
                {
                    return null;
                }
            }
        }
    }
}
